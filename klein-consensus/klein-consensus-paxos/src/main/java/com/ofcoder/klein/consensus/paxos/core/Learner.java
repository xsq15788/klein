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
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Lists;
import com.ofcoder.klein.common.Lifecycle;
import com.ofcoder.klein.common.util.KleinThreadFactory;
import com.ofcoder.klein.common.util.ThreadExecutor;
import com.ofcoder.klein.consensus.facade.AbstractInvokeCallback;
import com.ofcoder.klein.consensus.facade.MemberManager;
import com.ofcoder.klein.consensus.facade.SM;
import com.ofcoder.klein.consensus.facade.config.ConsensusProp;
import com.ofcoder.klein.consensus.paxos.PaxosNode;
import com.ofcoder.klein.consensus.paxos.rpc.vo.ConfirmReq;
import com.ofcoder.klein.consensus.paxos.rpc.vo.PrepareRes;
import com.ofcoder.klein.rpc.facade.InvokeParam;
import com.ofcoder.klein.rpc.facade.RpcClient;
import com.ofcoder.klein.rpc.facade.RpcEngine;
import com.ofcoder.klein.rpc.facade.RpcProcessor;
import com.ofcoder.klein.rpc.facade.serialization.Hessian2Util;
import com.ofcoder.klein.storage.facade.Instance;
import com.ofcoder.klein.storage.facade.LogManager;
import com.ofcoder.klein.storage.facade.StorageEngine;

/**
 * @author 释慧利
 */
public class Learner implements Lifecycle<ConsensusProp> {
    private static final Logger LOG = LoggerFactory.getLogger(Learner.class);
    private RpcClient client;
    private final PaxosNode self;
    private LogManager logManager;
    private SM sm;
    private BlockingQueue<ConfirmReq> applyQueue = new PriorityBlockingQueue<>(11, Comparator.comparingLong(ConfirmReq::getInstanceId));
    private ExecutorService applyExecutor = Executors.newFixedThreadPool(1, KleinThreadFactory.create("audit-predict", true));
    private CountDownLatch shutdownLatch;
    private ConsensusProp prop;


    public Learner(PaxosNode self) {
        this.self = self;
    }

    @Override
    public void init(ConsensusProp op) {
        this.prop = op;
        logManager = StorageEngine.getLogManager();
        this.client = RpcEngine.getClient();


        applyExecutor.execute(() -> {
            while (shutdownLatch == null) {
                try {
                    ConfirmReq take = applyQueue.take();
                    apply(take.getInstanceId(), take.getDatas());
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }

    @Override
    public void shutdown() {
        shutdownLatch = new CountDownLatch(1);
        ThreadExecutor.submit(() -> {
            try {
                sm.makeImage();
            } finally {
                shutdownLatch.countDown();
            }
        });
        try {
            shutdownLatch.await();
        } catch (InterruptedException e) {
        }

    }

    public void loadSM(SM sm) {
        this.sm = sm;
    }

    public void learn(long instanceId) {
        LOG.info("start learn, instanceId: {}", instanceId);
        ProposeContext ctxt = new ProposeContext(instanceId, Lists.newArrayList(Noop.DEFAULT), Lists.newArrayList());
        // only runs once
        ctxt.setTimes(prop.getRetry() - 1);
        RoleAccessor.getProposer().forcePrepare(ctxt, new PhaseCallback.PreparePhaseCallback() {
            @Override
            public void granted(ProposeContext context) {

                RoleAccessor.getProposer().accept(ctxt, new PhaseCallback.AcceptPhaseCallback() {
                    @Override
                    public void granted(ProposeContext context) {

                    }
                });
            }

            @Override
            public void confirmed(ProposeContext context) {
                handleConfirmRequest(
                        ConfirmReq.Builder.aConfirmReq().nodeId(self.getSelf().getId())
                                .datas(context.getPrepareQuorum().getTempValue())
                                .instanceId(context.getInstanceId())
                                .build()
                );
            }

            @Override
            public void refused(ProposeContext context) {
                learn(context.getInstanceId());
            }
        });
    }


    private void apply(long instanceId, List<Object> datas) {
        if (instanceId <= logManager.maxAppliedInstanceId()) {
            // the Instance has been applied.
            return;
        }
        long exceptConfirmId = logManager.maxAppliedInstanceId() + 1;
        if (instanceId > exceptConfirmId) {
            long pre = instanceId - 1;
            Instance preInstance = logManager.getInstance(pre);
            if (preInstance != null && preInstance.getState() == Instance.State.CONFIRMED){
                apply(pre, preInstance.getGrantedValue());
            } else {
                learn(pre);
            }
        }

        // update log to applied.
        try {
            logManager.getLock().writeLock().lock();

            Instance localInstance = logManager.getInstance(instanceId);
            if (!localInstance.getApplied().compareAndSet(false, true)) {
                return;
            }
            logManager.updateInstance(localInstance);
        } finally {
            logManager.getLock().writeLock().unlock();
        }

        for (Object data : datas) {
            try {
                sm.apply(data);
            } catch (Exception e) {
                LOG.warn(String.format("apply instance[%s] to sm, %s", instanceId, e.getMessage()), e);
            }
        }
    }


    public void confirm(long instanceId, List<Object> datas) {
        LOG.info("start confirm phase, instanceId: {}", instanceId);
        ConfirmReq req = ConfirmReq.Builder.aConfirmReq()
                .nodeId(self.getSelf().getId())
                .instanceId(instanceId)
                .datas(datas)
                .build();

        InvokeParam param = InvokeParam.Builder.anInvokeParam()
                .service(ConfirmReq.class.getSimpleName())
                .method(RpcProcessor.KLEIN)
                .data(ByteBuffer.wrap(Hessian2Util.serialize(req))).build();

        MemberManager.getAllMembers().forEach(it -> {
            client.sendRequestAsync(it, param, new AbstractInvokeCallback<PrepareRes>() {
                @Override
                public void error(Throwable err) {
                    LOG.error(err.getMessage(), err);
                }

                @Override
                public void complete(PrepareRes result) {
                    LOG.info("node-{} confirm result: {}", it.getId(), result);
                }
            }, 1000);
        });
    }

    public void handleConfirmRequest(ConfirmReq req) {

        try {
            logManager.getLock().writeLock().lock();

            Instance localInstance = logManager.getInstance(req.getInstanceId());
            if (localInstance == null) {
                // the prepare message is not received, the confirm message is received.
                // however, the instance has reached confirm, indicating that it has reached a consensus.
                localInstance = Instance.Builder.anInstance()
                        .instanceId(req.getInstanceId())
                        .build();
            }
            localInstance.setState(Instance.State.CONFIRMED);
            localInstance.setGrantedValue(req.getDatas());
            logManager.updateInstance(localInstance);

            // apply statemachine
            applyQueue.offer(req);
        } finally {
            logManager.getLock().writeLock().unlock();
        }
    }


}
