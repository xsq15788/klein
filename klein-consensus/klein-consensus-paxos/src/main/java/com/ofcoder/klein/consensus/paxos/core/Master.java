/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ofcoder.klein.consensus.paxos.core;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ofcoder.klein.common.Lifecycle;
import com.ofcoder.klein.common.serialization.Hessian2Util;
import com.ofcoder.klein.common.util.timer.RepeatedTimer;
import com.ofcoder.klein.consensus.facade.AbstractInvokeCallback;
import com.ofcoder.klein.consensus.facade.Quorum;
import com.ofcoder.klein.consensus.facade.Result;
import com.ofcoder.klein.consensus.facade.config.ConsensusProp;
import com.ofcoder.klein.consensus.paxos.PaxosMemberConfiguration;
import com.ofcoder.klein.consensus.paxos.PaxosNode;
import com.ofcoder.klein.consensus.paxos.PaxosQuorum;
import com.ofcoder.klein.consensus.paxos.core.sm.ElectionOp;
import com.ofcoder.klein.consensus.paxos.core.sm.MasterSM;
import com.ofcoder.klein.consensus.paxos.rpc.vo.Ping;
import com.ofcoder.klein.consensus.paxos.rpc.vo.Pong;
import com.ofcoder.klein.rpc.facade.InvokeParam;
import com.ofcoder.klein.rpc.facade.RpcClient;
import com.ofcoder.klein.rpc.facade.RpcEngine;
import com.ofcoder.klein.rpc.facade.RpcProcessor;

/**
 * @author 释慧利
 */
public class Master implements Lifecycle<ConsensusProp> {
    private static final Logger LOG = LoggerFactory.getLogger(Master.class);
    private PaxosNode self;
    private RepeatedTimer electTimer;
    private RepeatedTimer sendHeartbeatTimer;
    private RpcClient client;
    private ConsensusProp prop;
    private final AtomicBoolean electing = new AtomicBoolean(false);

    public Master(PaxosNode self) {
        this.self = self;
    }

    @Override
    public void shutdown() {
        if (electTimer != null) {
            electTimer.destroy();
        }
        if (sendHeartbeatTimer != null) {
            sendHeartbeatTimer.destroy();
        }
    }

    @Override
    public void init(ConsensusProp op) {
        this.prop = op;
        this.client = RpcEngine.getClient();

        // first run after 1 second, because the system may not be started
        electTimer = new RepeatedTimer("elect-master", 1000) {
            @Override
            protected void onTrigger() {
                election();
            }

            @Override
            protected int adjustTimeout(int timeoutMs) {
                return ThreadLocalRandom.current().nextInt(600, 800);
            }
        };

        sendHeartbeatTimer = new RepeatedTimer("master-heartbeat", 100) {
            @Override
            protected void onTrigger() {
                sendHeartbeat();
            }
        };

        electTimer.start();
    }

    private void election() {

        if (!electing.compareAndSet(false, true)) {
            return;
        }
        long start = System.currentTimeMillis();
        try {
            LOG.info("start electing master.");
            ElectionOp req = new ElectionOp();
            req.setNodeId(self.getSelf().getId());

            CountDownLatch latch = new CountDownLatch(1);
            RoleAccessor.getProposer().propose(MasterSM.GROUP, req, new ProposeDone() {
                @Override
                public void negotiationDone(Result.State result) {
                    if (result == Result.State.UNKNOWN) {
                        latch.countDown();
                    }
                }

                @Override
                public void applyDone(Object result) {
                    latch.countDown();
                }
            });

            try {
                boolean await = latch.await(this.prop.getRoundTimeout() * this.prop.getRetry(), TimeUnit.MILLISECONDS);
                // do nothing for await's result, stop this timer in {@link #onChangeMaster}
            } catch (InterruptedException e) {
                LOG.debug(e.getMessage());
            }
        } finally {
            electing.compareAndSet(true, false);
        }
        LOG.info("end election master, cost: {}", System.currentTimeMillis() - start);
    }

    private void sendHeartbeat() {
        final PaxosMemberConfiguration memberConfiguration = self.getMemberConfiguration().createRef();
        final Quorum quorum = PaxosQuorum.createInstance(memberConfiguration);
        final Ping req = Ping.Builder.aPing()
                .nodeId(self.getSelf().getId())
                .proposalNo(self.getCurProposalNo())
                .memberConfigurationVersion(memberConfiguration.getVersion())
                .build();

        final CompletableFuture<Quorum.GrantResult> complete = new CompletableFuture<>();
        // for self
        if (onReceiveHeartbeat(req, true)) {
            quorum.grant(self.getSelf());
        }

        // for other members
        InvokeParam param = InvokeParam.Builder.anInvokeParam()
                .service(Ping.class.getSimpleName())
                .method(RpcProcessor.KLEIN)
                .data(ByteBuffer.wrap(Hessian2Util.serialize(req))).build();
        memberConfiguration.getMembersWithoutSelf().forEach(it -> {
            client.sendRequestAsync(it, param, new AbstractInvokeCallback<Pong>() {
                @Override
                public void error(Throwable err) {
                    LOG.debug("node: " + it.getId() + ", " + err.getMessage());
                    quorum.refuse(it);
                    if (quorum.isGranted() == Quorum.GrantResult.REFUSE) {
                        complete.complete(quorum.isGranted());
                    }
                }

                @Override
                public void complete(Pong result) {
                    quorum.grant(it);
                    if (quorum.isGranted() == Quorum.GrantResult.PASS) {
                        complete.complete(quorum.isGranted());
                    }
                }
            }, 100);
        });
        try {
            Quorum.GrantResult grantResult = complete.get(110L, TimeUnit.MICROSECONDS);
            if (grantResult != Quorum.GrantResult.PASS) {
                LOG.info("心跳多数派拒绝，重新选举master");
                restartElect();
            }
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            LOG.info(e.getClass().getName() + "，心跳等待超时，重新选举master");
            restartElect();
        }
    }

    public boolean onReceiveHeartbeat(Ping request, boolean isSelf) {
        LOG.info("receive heartbeat from node-{}", request.getNodeId());
        final PaxosMemberConfiguration memberConfiguration = self.getMemberConfiguration();
        if (memberConfiguration.getMaster() != null
                && StringUtils.equals(request.getNodeId(), memberConfiguration.getMaster().getId())
                && request.getMemberConfigurationVersion() == memberConfiguration.getVersion()) {
            // todo: check and update instance
            if (!isSelf) {
                restartElect();
            }
            return true;
        } else {
            LOG.info("receive heartbeat from node-{}, result: {}. local.master: {}, req.version: {}", request.getNodeId(), false
                    , memberConfiguration, request.getMemberConfigurationVersion());
            return false;
        }
    }

    private void restartElect() {
        sendHeartbeatTimer.stop();
        electTimer.restart();
        electTimer.reset(ThreadLocalRandom.current().nextInt(600, 800));
    }

    private void restartHeartbeat() {
        electTimer.stop();
        sendHeartbeatTimer.restart();
    }

    public void onChangeMaster(final String newMaster) {
        if (StringUtils.equals(newMaster, self.getSelf().getId())) {
            restartHeartbeat();
        } else {
            restartElect();
        }
    }
}