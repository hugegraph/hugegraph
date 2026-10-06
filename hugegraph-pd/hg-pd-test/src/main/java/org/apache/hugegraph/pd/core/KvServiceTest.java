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

package org.apache.hugegraph.pd.core;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.apache.hugegraph.pd.KvService;
import org.apache.hugegraph.pd.common.PDException;
import org.apache.hugegraph.pd.config.PDConfig;
import org.apache.hugegraph.pd.grpc.kv.TxnCompare;
import org.apache.hugegraph.pd.grpc.kv.TxnOp;
import org.apache.hugegraph.pd.grpc.kv.TxnRecord;
import org.apache.hugegraph.pd.grpc.kv.TxnRequest;
import org.apache.hugegraph.pd.grpc.kv.TxnResponse;
import org.apache.hugegraph.pd.grpc.kv.WatchResponse;
import org.apache.hugegraph.pd.grpc.kv.WatchState;
import org.apache.hugegraph.pd.raft.RaftEngine;
import org.apache.hugegraph.pd.store.KvTxnApplier;
import org.apache.hugegraph.pd.sync.SchemaSyncTracker;
import org.junit.Assert;
import org.junit.Test;

import io.grpc.stub.StreamObserver;

public class KvServiceTest extends PDCoreTestBase {

    @Test
    public void testKv() {
        try {
            PDConfig pdConfig = getPdConfig();
            KvService service = new KvService(pdConfig);
            String key = "kvTest";
            String kvTest = service.get(key);
            Assert.assertEquals(kvTest, "");
            service.put(key, "kvTestValue");
            kvTest = service.get(key);
            Assert.assertEquals(kvTest, "kvTestValue");
            service.scanWithPrefix(key);
            service.delete(key);
            service.put(key, "kvTestValue");
            service.deleteWithPrefix(key);
            service.put(key, "kvTestValue", 1000L);
            service.keepAlive(key);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * The suite runs PD without raft, so this covers the local TXN path and its encoding
     */
    @Test
    public void testTxn() throws PDException {
        KvService service = new KvService(getPdConfig());
        String record = "HUGEGRAPH/hg/SCHEMA_SYNC/DEFAULT/txn-test";
        TxnRequest create = TxnRequest.newBuilder()
                                      .addCompares(TxnCompare.newBuilder().setKey("txn-test/a")
                                                             .setKind(TxnCompare.Kind.ABSENT))
                                      .addOps(TxnOp.newBuilder().setKey("txn-test/a")
                                                   .setValue("1"))
                                      .addOps(TxnOp.newBuilder().setKey("txn-test/b")
                                                   .setValue("2"))
                                      .setRecord(TxnRecord.newBuilder().setKey(record)
                                                          .setOp(TxnRecord.Op.BUMP))
                                      .build();
        TxnResponse first = service.txn(create);
        Assert.assertTrue(first.getSucceeded());
        Assert.assertEquals(1, first.getIncarnation());
        Map<String, String> kvs = service.scanWithPrefix("txn-test/");
        Assert.assertEquals(2, kvs.size());
        Assert.assertEquals("1", kvs.get("txn-test/a"));
        String value = "{\"rev\":" + first.getRevision() + ",\"inc\":1,\"state\":\"LIVE\"}";
        Assert.assertEquals(value, service.get(record));

        // a exists now, so the compare fails and nothing is written
        TxnResponse rejected = service.txn(create.toBuilder().addOps(
                TxnOp.newBuilder().setKey("txn-test/c").setValue("3")).build());
        Assert.assertFalse(rejected.getSucceeded());
        Assert.assertEquals(TxnResponse.Failure.COMPARE_FAILED, rejected.getFailure());
        Assert.assertEquals("", service.get("txn-test/c"));

        TxnRequest clear = TxnRequest.newBuilder()
                                     .addOps(TxnOp.newBuilder().setKey("txn-test/")
                                                  .setType(TxnOp.Type.DELETE_PREFIX))
                                     .setRecord(TxnRecord.newBuilder().setKey(record)
                                                         .setOp(TxnRecord.Op.BUMP)
                                                         .setExpectedIncarnation(1))
                                     .build();
        TxnResponse second = service.txn(clear);
        Assert.assertTrue(second.getSucceeded());
        Assert.assertTrue(second.getRevision() > first.getRevision());
        Assert.assertTrue(service.scanWithPrefix("txn-test/").isEmpty());
    }

    /**
     * Schema sync on a real raft node: the handshake waits for the ReadIndex and sends the
     * record committed before it, then Synced; a TXN committed afterwards reaches the session
     * through the apply listener; only a revision that was sent is acknowledged. The gRPC
     * wiring is covered by KvClientTest against a running PD.
     */
    @Test
    public void testSchemaSyncOnRaft() throws Exception {
        PDConfig config = getPdConfig();
        PDConfig.SchemaSync sync = config.new SchemaSync();
        sync.setCoalesceWindow(10L);
        sync.setMaxWait(50L);
        // The base config leaves it 0, which fails the ReadIndex at once
        int rpcTimeout = config.getRaft().getRpcTimeout();
        config.getRaft().setRpcTimeout(5000);
        List<Long> closed = new CopyOnWriteArrayList<>();
        SchemaSyncTracker tracker = new SchemaSyncTracker(
                sync, () -> RaftEngine.getInstance().isLeader(),
                (prefix, clientId) -> closed.add(clientId));
        KvTxnApplier.setListener(tracker);
        KvService service = new KvService(config);
        SchemaSyncTracker.RecordReader reader = prefix -> {
            RaftEngine.getInstance().waitReadIndex();
            return service.scanWithPrefix(prefix);
        };
        try {
            String prefix = "HUGEGRAPH/sync-test/SCHEMA_SYNC/";
            long revA = bump(service, prefix + "DEFAULT/a");

            Frames frames = new Frames();
            Assert.assertTrue(tracker.handshake(prefix, 1L, frames, reader));
            Assert.assertEquals(2, frames.frames.size());
            WatchResponse initial = frames.frames.get(0);
            Assert.assertEquals(prefix + "DEFAULT/a", initial.getEvents(0).getCurrent().getKey());
            Assert.assertEquals("{\"rev\":" + revA + ",\"inc\":1,\"state\":\"LIVE\"}",
                                initial.getEvents(0).getCurrent().getValue());
            Assert.assertEquals(WatchState.Synced, frames.frames.get(1).getState());

            long revB = bump(service, prefix + "DEFAULT/b");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10L);
            while (frames.events().size() < 2 && System.nanoTime() < deadline) {
                Thread.sleep(10L);
            }
            WatchResponse event = frames.events().get(1);
            Assert.assertEquals(1L, event.getClientId());
            Assert.assertTrue(event.getEvents(0).getCurrent().getValue()
                                   .startsWith("{\"rev\":" + revB + ","));

            Assert.assertTrue(tracker.ack(1L, prefix + "DEFAULT/a", revA));
            Assert.assertFalse(tracker.ack(1L, prefix + "DEFAULT/b", revB + 1));
            Assert.assertFalse(tracker.ack(2L, prefix + "DEFAULT/b", revB));
            Assert.assertEquals(1, tracker.pendingCount(false));
            Assert.assertTrue(tracker.ack(1L, prefix + "DEFAULT/b", revB));
            Assert.assertEquals(0, tracker.pendingCount(false));

            // A ReadIndex that fails: the session never reaches Synced and is closed
            config.getRaft().setRpcTimeout(0);
            Frames failed = new Frames();
            Assert.assertFalse(tracker.handshake(prefix, 2L, failed, reader));
            Assert.assertTrue(failed.frames.isEmpty());
            Assert.assertEquals(List.of(2L), closed);
            Assert.assertEquals(1, tracker.sessionCount());
            Assert.assertEquals(1L, tracker.invalidationCount(
                    SchemaSyncTracker.Reason.HANDSHAKE_FAILED));
        } finally {
            config.getRaft().setRpcTimeout(rpcTimeout);
            KvTxnApplier.setListener(null);
            tracker.clear();
        }
    }

    private static long bump(KvService service, String record) throws PDException {
        TxnResponse response = service.txn(TxnRequest.newBuilder().setRecord(
                TxnRecord.newBuilder().setKey(record).setOp(TxnRecord.Op.BUMP)).build());
        Assert.assertTrue(response.getSucceeded());
        return response.getRevision();
    }

    private static final class Frames implements StreamObserver<WatchResponse> {

        private final List<WatchResponse> frames = new CopyOnWriteArrayList<>();

        @Override
        public void onNext(WatchResponse value) {
            this.frames.add(value);
        }

        @Override
        public void onError(Throwable t) {
        }

        @Override
        public void onCompleted() {
        }

        private List<WatchResponse> events() {
            return this.frames.stream().filter(frame -> frame.getEventsCount() > 0)
                              .collect(Collectors.toList());
        }
    }

    @Test
    public void testMember() {
        try {
            PDConfig pdConfig = getPdConfig();
            KvService service = new KvService(pdConfig);
            service.setPdConfig(pdConfig);
            PDConfig config = service.getPdConfig();
            // TODO
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
