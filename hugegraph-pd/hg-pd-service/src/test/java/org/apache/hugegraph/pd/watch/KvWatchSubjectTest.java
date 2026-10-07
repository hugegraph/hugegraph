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

package org.apache.hugegraph.pd.watch;

import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.apache.hugegraph.pd.KvService;
import org.apache.hugegraph.pd.common.PDException;
import org.apache.hugegraph.pd.config.PDConfig;
import org.apache.hugegraph.pd.grpc.kv.Kv;
import org.apache.hugegraph.pd.grpc.kv.WatchResponse;
import org.apache.hugegraph.pd.raft.RaftEngine;
import org.apache.hugegraph.testutil.Whitebox;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.alipay.sofa.jraft.Node;

import io.grpc.stub.StreamObserver;

/**
 * A watch stream PD closes must end even when its registration cannot be deleted: a stream
 * left open keeps being renewed by the generic keepalive, and its client never reconnects
 */
public class KvWatchSubjectTest {

    private static final String PREFIX = "HUGEGRAPH/hg/SCHEMA_SYNC/";

    private Node originalRaftNode;
    private File dataPath;

    @Before
    public void setUp() throws Exception {
        RaftEngine engine = RaftEngine.getInstance();
        this.originalRaftNode = engine.getRaftNode();
        // The leader deletes the registration through raft
        Whitebox.setInternalState(engine, "raftNode", leaderNode());
        this.dataPath = Files.createTempDirectory("kv-watch-subject").toFile();
    }

    @After
    public void tearDown() throws Exception {
        Whitebox.setInternalState(RaftEngine.getInstance(), "raftNode", this.originalRaftNode);
        FileUtils.deleteQuietly(this.dataPath);
    }

    @Test
    public void testClosedClientEndsWhenRegistrationDeleteFails() throws Exception {
        PDConfig config = new PDConfig();
        config.setDataPath(this.dataPath.getAbsolutePath());
        PDConfig.Raft raft = config.new Raft();
        raft.setEnable(false);
        config.setRaft(raft);
        KvWatchSubject subject = new KvWatchSubject(config);
        Whitebox.setInternalState(subject, "kvService", new KvService(config) {
            @Override
            public List<Kv> deleteWithPrefix(String key) throws PDException {
                throw new PDException(-1, "raft is down");
            }
        });

        Recorder observer = new Recorder();
        subject.addObserver(PREFIX, 7L, observer, KvWatchSubject.PREFIX_DELIMITER);
        subject.closePrefixClient(PREFIX, 7L);
        Assert.assertEquals(1, observer.completed);
        // Gone from memory too: nothing is left to renew or close again
        subject.closePrefixClient(PREFIX, 7L);
        Assert.assertEquals(1, observer.completed);
    }

    private static Node leaderNode() {
        return (Node) Proxy.newProxyInstance(
                Node.class.getClassLoader(), new Class<?>[]{Node.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "isLeader":
                            return true;
                        case "toString":
                            return "leader raft node";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        default:
                            throw new AssertionError("unexpected call " + method.getName());
                    }
                });
    }

    private static final class Recorder implements StreamObserver<WatchResponse> {

        private int completed;

        @Override
        public void onNext(WatchResponse value) {
        }

        @Override
        public void onError(Throwable t) {
        }

        @Override
        public void onCompleted() {
            this.completed++;
        }
    }
}
