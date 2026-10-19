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

package org.apache.hugegraph.pd.service;

import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.apache.commons.io.FileUtils;
import org.apache.hugegraph.pd.KvService;
import org.apache.hugegraph.pd.common.PDException;
import org.apache.hugegraph.pd.config.PDConfig;
import org.apache.hugegraph.pd.grpc.Pdpb;
import org.apache.hugegraph.pd.grpc.kv.K;
import org.apache.hugegraph.pd.grpc.kv.KResponse;
import org.apache.hugegraph.pd.grpc.kv.ScanPrefixResponse;
import org.apache.hugegraph.pd.grpc.kv.TxnOp;
import org.apache.hugegraph.pd.grpc.kv.TxnRequest;
import org.apache.hugegraph.pd.grpc.kv.TxnResponse;
import org.apache.hugegraph.pd.grpc.kv.WatchAckRequest;
import org.apache.hugegraph.pd.grpc.kv.WatchRequest;
import org.apache.hugegraph.pd.grpc.kv.WatchResponse;
import org.apache.hugegraph.pd.grpc.kv.WatchState;
import org.apache.hugegraph.pd.raft.RaftEngine;
import org.apache.hugegraph.pd.sync.SchemaSyncTracker;
import org.apache.hugegraph.pd.watch.KvWatchSubject;
import org.apache.hugegraph.testutil.Whitebox;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.alipay.sofa.jraft.Node;
import com.alipay.sofa.jraft.Status;
import com.alipay.sofa.jraft.closure.ReadIndexClosure;
import com.alipay.sofa.jraft.error.RaftError;

import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.stub.StreamObserver;

/**
 * get and scanPrefix read the local store of the leader. A leader elected a moment ago may not
 * have applied every committed write, so both wait for a ReadIndex first, and return an error
 * rather than a stored value when it fails. The raft node answers each ReadIndex the way
 * jraft does: it sets the result and runs the closure.
 * <p>
 * And stopScheduling() takes the schema sync tracker and the closes it started into the
 * shutdown drain.
 */
public class KvServiceGrpcImplTest {

    private final List<String> calls = new CopyOnWriteArrayList<>();
    private volatile Status readIndex = Status.OK();
    private Node originalRaftNode;
    private PDConfig.Raft originalRaftConfig;
    private PDConfig config;
    private KvServiceGrpcImpl service;
    // What the raft node answers to isLeader(), and what a redirect forwarded
    private volatile boolean leader = true;
    private volatile Object redirected;
    // Set to fail the next txn apply, and with deposedByTxn to lose the leadership first
    private volatile PDException txnFailure;
    private volatile boolean deposedByTxn;

    @Before
    public void setUp() throws Exception {
        // The store is opened once per JVM, in the directory of the first test that makes a
        // KvService, and stays open: the directory has to outlive the test
        File dataPath = Files.createTempDirectory("kv-service-grpc").toFile();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> FileUtils.deleteQuietly(dataPath)));
        PDConfig config = this.config = new PDConfig();
        config.setDataPath(dataPath.getAbsolutePath());
        PDConfig.Raft raft = config.new Raft();
        raft.setEnable(false);
        // Bounds the wait for the ReadIndex: one that times out is reported at this deadline
        raft.setRpcTimeout(300);
        config.setRaft(raft);

        RaftEngine engine = RaftEngine.getInstance();
        this.originalRaftNode = engine.getRaftNode();
        this.originalRaftConfig = Whitebox.getInternalState(engine, "config");
        Whitebox.setInternalState(engine, "raftNode", leaderNode());
        Whitebox.setInternalState(engine, "config", raft);

        this.service = new KvServiceGrpcImpl() {
            @Override
            public boolean isLeader() {
                return KvServiceGrpcImplTest.this.leader;
            }

            @Override
            public <ReqT, RespT> void redirectToLeader(ManagedChannel channel,
                                                       MethodDescriptor<ReqT, RespT> method,
                                                       ReqT request,
                                                       StreamObserver<RespT> observer) {
                KvServiceGrpcImplTest.this.calls.add("redirect " + method.getBareMethodName());
                KvServiceGrpcImplTest.this.redirected = request;
            }
        };
        // The reads alone: what they return is what the store holds when they run
        this.service.kvService = new KvService(config) {
            @Override
            public String get(String key) {
                KvServiceGrpcImplTest.this.calls.add("get");
                return "stored";
            }

            @Override
            public Map<String, String> scanWithPrefix(String key) {
                KvServiceGrpcImplTest.this.calls.add("scan");
                return Map.of(key + "a", "stored");
            }

            @Override
            public TxnResponse txn(TxnRequest request) throws PDException {
                KvServiceGrpcImplTest.this.calls.add("txn");
                PDException failure = KvServiceGrpcImplTest.this.txnFailure;
                if (failure != null) {
                    if (KvServiceGrpcImplTest.this.deposedByTxn) {
                        KvServiceGrpcImplTest.this.leader = false;
                    }
                    throw failure;
                }
                return TxnResponse.newBuilder().setSucceeded(true).build();
            }
        };
    }

    @After
    public void tearDown() {
        RaftEngine engine = RaftEngine.getInstance();
        Whitebox.setInternalState(engine, "raftNode", this.originalRaftNode);
        Whitebox.setInternalState(engine, "config", this.originalRaftConfig);
    }

    @Test
    public void testGetAndScanReadAfterTheReadIndex() {
        KResponse got = get("k");
        Assert.assertEquals(Pdpb.ErrorType.OK, got.getHeader().getError().getType());
        Assert.assertEquals("stored", got.getValue());
        Assert.assertEquals(List.of("readIndex", "get"), this.calls);

        this.calls.clear();
        ScanPrefixResponse scanned = scan("k/");
        Assert.assertEquals(Pdpb.ErrorType.OK, scanned.getHeader().getError().getType());
        Assert.assertEquals(Map.of("k/a", "stored"), scanned.getKvsMap());
        Assert.assertEquals(List.of("readIndex", "scan"), this.calls);
    }

    @Test
    public void testFailedReadIndexReturnsNoStoredValue() {
        // As on a leader that cannot confirm its log with a quorum
        this.readIndex = new Status(RaftError.ETIMEDOUT, "no quorum");

        KResponse got = get("k");
        Assert.assertEquals(Pdpb.ErrorType.UNKNOWN, got.getHeader().getError().getType());
        Assert.assertEquals("", got.getValue());

        ScanPrefixResponse scanned = scan("k/");
        Assert.assertEquals(Pdpb.ErrorType.UNKNOWN, scanned.getHeader().getError().getType());
        Assert.assertTrue(scanned.getKvsMap().isEmpty());
        // The store was not read at all
        Assert.assertEquals(List.of("readIndex", "readIndex"), this.calls);
    }

    /**
     * PD stops raft and closes the store right after stopScheduling(). A close of a session
     * deletes its watch key through raft, so it runs on the watch scheduler, which is drained
     * here, and the tracker is stopped first: a poll of its timer during the shutdown would
     * close every session as NOT_LEADER, against a store that is already closed.
     */
    @Test
    public void testStopSchedulingStopsTrackerAndDrainsCloses() throws Exception {
        String prefix = "HUGEGRAPH/hg/SCHEMA_SYNC/";
        List<String> closes = new CopyOnWriteArrayList<>();
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);
        Thread scheduler = executor.submit(Thread::currentThread).get();
        Whitebox.setInternalState(this.service, "executor", executor);
        Whitebox.setInternalState(this.service, "subjects", new KvWatchSubject(this.config) {
            @Override
            public void closePrefixClient(String key, long clientId) {
                closes.add(clientId + (Thread.currentThread() == scheduler ?
                                       " on the watch scheduler" : " on another thread"));
            }
        });
        SchemaSyncTracker tracker = new SchemaSyncTracker(() -> true,
                                                          this.service::closeSyncSession);
        Whitebox.setInternalState(this.service, "syncTracker", tracker);
        Capture<WatchResponse> stream = new Capture<>();

        // The scheduler is busy, as with a keepalive round, while one session is closed
        CountDownLatch busy = new CountDownLatch(1);
        executor.execute(() -> {
            try {
                busy.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        tracker.register(prefix, 1L, stream);
        Assert.assertFalse(tracker.handshake(prefix, 2L, stream, read -> {
            throw new PDException(-1, "read index timed out");
        }));
        Assert.assertEquals(1, tracker.sessionCount());

        busy.countDown();
        this.service.stopScheduling();
        // The close that had started was waited for; session 1 was dropped, not closed
        Assert.assertTrue(executor.isTerminated());
        Assert.assertEquals(List.of("2 on the watch scheduler"), closes);
        Assert.assertEquals(0, tracker.sessionCount());

        // A handshake that was still on its way takes no session and starts no close
        Assert.assertFalse(tracker.handshake(prefix, 3L, stream, read -> Map.of()));
        Assert.assertEquals(0, tracker.sessionCount());
        Assert.assertEquals(1, closes.size());
        // From the shutdown hook and again from @PreDestroy
        this.service.stopScheduling();
    }

    /**
     * Only the leader applies a txn, and a follower forwards it. A failed apply is answered
     * with its error, also when the node lost the leadership meanwhile: the entry may be on a
     * majority already, and a forward would let the next leader apply it twice.
     */
    @Test
    public void testTxnIsRedirectedToTheLeader() {
        TxnRequest request = TxnRequest.newBuilder().addOps(
                TxnOp.newBuilder().setKey("k").setValue("v")).build();
        this.leader = false;
        this.service.txn(request, new Capture<>());
        Assert.assertEquals(List.of("redirect txn"), this.calls);
        Assert.assertSame(request, this.redirected);

        for (boolean deposed : new boolean[]{true, false}) {
            this.calls.clear();
            this.redirected = null;
            this.leader = true;
            this.txnFailure = new PDException(Pdpb.ErrorType.NOT_LEADER_VALUE, "not leader");
            this.deposedByTxn = deposed;
            Capture<TxnResponse> failed = new Capture<>();
            this.service.txn(request, failed);
            Assert.assertEquals(List.of("txn"), this.calls);
            Assert.assertNull(this.redirected);
            Assert.assertFalse(failed.result().getSucceeded());
            Assert.assertEquals(Pdpb.ErrorType.NOT_LEADER,
                                failed.result().getHeader().getError().getType());
        }
    }

    @Test
    public void testWatchAckOnAFollowerIsRedirected() {
        this.leader = false;
        WatchAckRequest request = WatchAckRequest.newBuilder().setClientId(7L).setKey("k")
                                                 .setRevision(3L).build();
        this.service.watchAck(request, new Capture<>());
        Assert.assertEquals(List.of("redirect watchAck"), this.calls);
        Assert.assertSame(request, this.redirected);
    }

    /**
     * A schema sync watch is a new session on every connect, also when the request of a
     * reconnect carries the clientId of its last session; a plain watch keeps its clientId
     */
    @Test
    public void testSyncWatchGetsAFreshClientId() throws Exception {
        String prefix = "HUGEGRAPH/hg/SCHEMA_SYNC/";
        List<String> observers = new CopyOnWriteArrayList<>();
        Whitebox.setInternalState(this.service, "subjects", new KvWatchSubject(this.config) {
            @Override
            public void addObserver(String key, long clientId,
                                    StreamObserver<WatchResponse> observer, String delimiter) {
                observers.add(key + " " + clientId);
            }
        });
        SchemaSyncTracker tracker = new SchemaSyncTracker(() -> true, (key, clientId) -> { });
        Whitebox.setInternalState(this.service, "syncTracker", tracker);
        List<WatchResponse> frames = new CopyOnWriteArrayList<>();
        StreamObserver<WatchResponse> stream = new StreamObserver<WatchResponse>() {
            @Override
            public void onNext(WatchResponse value) {
                frames.add(value);
            }

            @Override
            public void onError(Throwable t) {
                throw new AssertionError("unexpected stream error", t);
            }

            @Override
            public void onCompleted() {
            }
        };

        this.service.watchPrefix(WatchRequest.newBuilder().setKey(prefix).setClientId(7L)
                                             .build(), stream);
        WatchResponse starting = frames.get(0);
        Assert.assertEquals(WatchState.Starting, starting.getState());
        Assert.assertNotEquals(7L, starting.getClientId());
        Assert.assertNotEquals(0L, starting.getClientId());
        Assert.assertEquals(List.of(prefix + " " + starting.getClientId()), observers);

        frames.clear();
        observers.clear();
        this.service.watchPrefix(WatchRequest.newBuilder().setKey("plain/").setClientId(7L)
                                             .build(), stream);
        Assert.assertEquals(WatchState.Started, frames.get(0).getState());
        Assert.assertEquals(List.of("plain/ 7"), observers);
        tracker.stop();
    }

    private KResponse get(String key) {
        Capture<KResponse> observer = new Capture<>();
        this.service.get(K.newBuilder().setKey(key).build(), observer);
        return observer.result();
    }

    private ScanPrefixResponse scan(String prefix) {
        Capture<ScanPrefixResponse> observer = new Capture<>();
        this.service.scanPrefix(K.newBuilder().setKey(prefix).build(), observer);
        return observer.result();
    }

    private Node leaderNode() {
        return (Node) Proxy.newProxyInstance(
                Node.class.getClassLoader(), new Class<?>[]{Node.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "isLeader":
                            return true;
                        case "readIndex":
                            this.calls.add("readIndex");
                            Status status = this.readIndex;
                            ReadIndexClosure closure = (ReadIndexClosure) args[args.length - 1];
                            closure.setResult(status.isOk() ? 42L :
                                              ReadIndexClosure.INVALID_LOG_INDEX, new byte[0]);
                            closure.run(status);
                            return null;
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

    private static final class Capture<T> implements StreamObserver<T> {

        private T value;
        private boolean completed;

        @Override
        public void onNext(T value) {
            this.value = value;
        }

        @Override
        public void onError(Throwable t) {
            throw new AssertionError("unexpected stream error", t);
        }

        @Override
        public void onCompleted() {
            this.completed = true;
        }

        private T result() {
            Assert.assertTrue(this.completed);
            return this.value;
        }
    }
}
