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
package com.ofcoder.klein.consensus.paxos.core.sm;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.commons.collections4.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ofcoder.klein.consensus.facade.MemberConfiguration;
import com.ofcoder.klein.consensus.paxos.core.RoleAccessor;
import com.ofcoder.klein.rpc.facade.Endpoint;

/**
 * @author 释慧利
 */
public class PaxosMemberConfiguration extends MemberConfiguration {
    private static final Logger LOG = LoggerFactory.getLogger(PaxosMemberConfiguration.class);
    private volatile Endpoint master;

    public Endpoint getMaster() {
        return this.master;
    }

    protected boolean changeMaster(String nodeId) {
        if (isValid(nodeId)) {
            this.master = getEndpointById(nodeId);
            this.version.incrementAndGet();

            RoleAccessor.getMaster().onChangeMaster(nodeId);
            LOG.info("node-{} was promoted to master, version: {}", nodeId, this.version.get());
            return true;
        } else {
            return false;
        }
    }

    protected void writeOn(Endpoint node) {
        super.writeOn(node);
    }

    protected void writeOff(Endpoint node) {
        super.writeOff(node);
    }

    protected void init(List<Endpoint> nodes) {
        super.init(nodes);
    }

    protected void loadSnap(PaxosMemberConfiguration snap) {
        this.master = new Endpoint(snap.master.getId(), snap.master.getIp(), snap.master.getPort());
        this.version = new AtomicInteger(snap.version.get());
        this.allMembers.clear();
        this.allMembers.putAll(snap.allMembers);
    }

    public PaxosMemberConfiguration createRef() {
        PaxosMemberConfiguration target = new PaxosMemberConfiguration();
        target.allMembers.putAll(allMembers);
        if (master != null) {
            target.master = new Endpoint(master.getId(), master.getIp(), master.getPort());
        }
        target.version = new AtomicInteger(version.get());
        return target;
    }

    @Override
    public String toString() {
        return "PaxosMemberConfiguration{" +
                "master=" + master +
                ", version=" + version +
                ", allMembers=" + allMembers +
                '}';
    }
}