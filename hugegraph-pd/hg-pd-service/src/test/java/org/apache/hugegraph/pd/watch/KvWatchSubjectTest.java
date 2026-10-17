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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.commons.io.FileUtils;
import org.apache.hugegraph.pd.KvService;
import org.apache.hugegraph.pd.common.PDException;
import org.apache.hugegraph.pd.config.PDConfig;
import org.apache.hugegraph.pd.grpc.kv.Kv;
import org.apache.hugegraph.pd.grpc.kv.WatchKv;
import org.apache.hugegraph.pd.grpc.kv.WatchResponse;
import org.apache.hugegraph.pd.grpc.kv.WatchType;
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
 * left open keeps being renewed by the generic keepalive, and its client never reconnects.
 * And a closed stream must leave no registration key behind.
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
        // The store is opened once per JVM, in the directory of the first test that makes a
        // KvService, and stays open: the directory has to outlive the test
        File created = this.dataPath;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> FileUtils.deleteQuietly(created)));
    }

    @After
    public void tearDown() throws Exception {
        Whitebox.setInternalState(RaftEngine.getInstance(), "raftNode", this.originalRaftNode);
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

    /**
     * A schema sync watch is a new client on every connect, so a key left by each stream PD
     * closes would pile up until its TTL, and every plain write scans them all
     */
    @Test
    public void testClosedClientLeavesNoWatchKey() throws Exception {
        PDConfig config = new PDConfig();
        config.setDataPath(this.dataPath.getAbsolutePath());
        PDConfig.Raft raft = config.new Raft();
        raft.setEnable(false);
        config.setRaft(raft);
        KvWatchSubject subject = new KvWatchSubject(config);
        // The keys alone, as put, delete and scan see them
        TreeMap<String, String> keys = new TreeMap<>();
        Whitebox.setInternalState(subject, "kvService", new KvService(config) {
            @Override
            public void put(String key, String value, long ttl) {
                keys.put(key, value);
            }

            @Override
            public Kv delete(String key) {
                keys.remove(key);
                return Kv.newBuilder().setKey(key).build();
            }

            @Override
            public List<Kv> deleteWithPrefix(String key) {
                keys.keySet().removeIf(stored -> stored.startsWith(key));
                return List.of();
            }
        });

        Recorder observer = new Recorder();
        subject.addObserver(PREFIX, 7L, observer, KvWatchSubject.PREFIX_DELIMITER);
        subject.addObserver(PREFIX, 8L, new Recorder(), KvWatchSubject.PREFIX_DELIMITER);
        Assert.assertEquals(Set.of("W@PW@" + PREFIX + "@7", "W@7@PW@" + PREFIX + "@7",
                                   "W@PW@" + PREFIX + "@8", "W@8@PW@" + PREFIX + "@8"),
                            keys.keySet());

        subject.closePrefixClient(PREFIX, 7L);
        Assert.assertEquals(1, observer.completed);
        // Only the other client's keys are left
        Assert.assertEquals(Set.of("W@PW@" + PREFIX + "@8", "W@8@PW@" + PREFIX + "@8"),
                            keys.keySet());
        subject.closePrefixClient(PREFIX, 8L);
        Assert.assertTrue(keys.toString(), keys.isEmpty());
    }

    /**
     * A plain put or delete under a schema sync prefix is not an event of a session. Sent as it
     * is, it would reach the consumer outside coalescing, resend and ACK tracking, as a Delete
     * or with a value that is not a record. Other watches get it as before.
     */
    @Test
    public void testPlainWriteIsNotSentToSyncSession() throws Exception {
        PDConfig config = new PDConfig();
        config.setDataPath(this.dataPath.getAbsolutePath());
        PDConfig.Raft raft = config.new Raft();
        raft.setEnable(false);
        config.setRaft(raft);
        KvWatchSubject subject = new KvWatchSubject(config);
        TreeMap<String, String> keys = new TreeMap<>();
        Whitebox.setInternalState(subject, "kvService", new KvService(config) {
            @Override
            public void put(String key, String value, long ttl) {
                keys.put(key, value);
            }

            @Override
            public Map<String, String> scanWithPrefix(String key) {
                TreeMap<String, String> found = new TreeMap<>(keys);
                found.keySet().removeIf(stored -> !stored.startsWith(key));
                return found;
            }
        });

        String recordKey = PREFIX + "DEFAULT/g";
        Recorder session = new Recorder();
        Recorder narrowSession = new Recorder();
        Recorder plainPrefix = new Recorder();
        Recorder plainKey = new Recorder();
        subject.addObserver(PREFIX, 21L, session, KvWatchSubject.PREFIX_DELIMITER);
        subject.addObserver(PREFIX + "DEFAULT/", 22L, narrowSession,
                            KvWatchSubject.PREFIX_DELIMITER);
        subject.addObserver("HUGEGRAPH/hg/", 23L, plainPrefix, KvWatchSubject.PREFIX_DELIMITER);
        subject.addObserver(recordKey, 24L, plainKey, KvWatchSubject.KEY_DELIMITER);

        WatchKv kv = WatchKv.newBuilder().setKey(recordKey).setValue("garbage").build();
        subject.notifyAllObserver(recordKey, WatchType.Put, new WatchKv[]{kv});
        subject.notifyAllObserver(recordKey, WatchType.Delete, new WatchKv[]{kv});

        Assert.assertTrue(session.frames.toString(), session.frames.isEmpty());
        Assert.assertTrue(narrowSession.frames.toString(), narrowSession.frames.isEmpty());
        for (Recorder plain : List.of(plainPrefix, plainKey)) {
            Assert.assertEquals(2, plain.frames.size());
            Assert.assertEquals(WatchType.Put, plain.frames.get(0).getEvents(0).getType());
            Assert.assertEquals(kv, plain.frames.get(0).getEvents(0).getCurrent());
            Assert.assertEquals(WatchType.Delete, plain.frames.get(1).getEvents(0).getType());
        }
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

        private final List<WatchResponse> frames = new ArrayList<>();
        private int completed;

        @Override
        public void onNext(WatchResponse value) {
            this.frames.add(value);
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
