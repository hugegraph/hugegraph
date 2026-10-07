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

package org.apache.hugegraph.pd.core.store;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.apache.hugegraph.pd.KvService;
import org.apache.hugegraph.pd.common.PDException;
import org.apache.hugegraph.pd.config.PDConfig;
import org.apache.hugegraph.pd.grpc.kv.TxnCompare;
import org.apache.hugegraph.pd.grpc.kv.TxnOp;
import org.apache.hugegraph.pd.grpc.kv.TxnRecord;
import org.apache.hugegraph.pd.grpc.kv.TxnRequest;
import org.apache.hugegraph.pd.grpc.kv.TxnResponse;
import org.apache.hugegraph.pd.grpc.kv.V;
import org.apache.hugegraph.pd.raft.KVOperation;
import org.apache.hugegraph.pd.raft.KVStoreClosure;
import org.apache.hugegraph.pd.raft.RaftEngine;
import org.apache.hugegraph.pd.raft.RaftStateMachine;
import org.apache.hugegraph.pd.store.BaseKVStoreClosure;
import org.apache.hugegraph.pd.store.HgKVStore;
import org.apache.hugegraph.pd.store.HgKVStoreImpl;
import org.apache.hugegraph.pd.store.KvTxnApplier;
import org.apache.hugegraph.pd.store.RaftKVStore;
import org.apache.hugegraph.pd.store.TxnApplyListener;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.alipay.sofa.jraft.Status;
import com.google.gson.Gson;

public class KvTxnApplierTest {

    private static final String RECORD = "HUGEGRAPH/hg/SCHEMA_SYNC/DEFAULT/g";

    private File dir;
    private HgKVStore store;
    private final List<String> applied = new ArrayList<>();
    private final TxnApplyListener listener = (index, key, rev, inc, state) ->
            this.applied.add(index + " " + key + " " + rev + " " + inc + " " + state);

    @Before
    public void setup() throws Exception {
        this.dir = Files.createTempDirectory("kv-txn").toFile();
        PDConfig config = new PDConfig();
        config.setDataPath(this.dir.getPath());
        this.store = new HgKVStoreImpl();
        this.store.init(config);
    }

    @After
    public void teardown() throws Exception {
        this.store.close();
        FileUtils.deleteDirectory(this.dir);
    }

    @Test
    public void testOpsWrittenTogether() throws Exception {
        TxnResponse response = apply(5, txn(put("a", "1"), put("b", "2")).build());
        Assert.assertTrue(response.getSucceeded());
        Assert.assertEquals(5, response.getRevision());
        Assert.assertEquals("1", value("a"));
        Assert.assertEquals("2", value("b"));

        response = apply(6, txn(op(TxnOp.Type.DELETE, "a", ""), put("b", "3")).build());
        Assert.assertTrue(response.getSucceeded());
        Assert.assertNull(value("a"));
        Assert.assertEquals("3", value("b"));
        Assert.assertEquals(6, KvTxnApplier.lastIndex(this.store));
        Assert.assertTrue(this.applied.isEmpty());
    }

    @Test
    public void testFailedCompareWritesNothing() throws Exception {
        apply(1, txn(put("a", "1")).build());
        TxnRequest request = txn(put("b", "2"))
                .addCompares(compare(TxnCompare.Kind.EQUALS, "a", "1"))
                .addCompares(compare(TxnCompare.Kind.ABSENT, "a", ""))
                .setRecord(record(TxnRecord.Op.BUMP, 0)).build();
        TxnResponse response = apply(2, request);
        Assert.assertFalse(response.getSucceeded());
        Assert.assertEquals(TxnResponse.Failure.COMPARE_FAILED, response.getFailure());
        Assert.assertEquals(1, response.getFailedCompare());
        Assert.assertNull(value("b"));
        Assert.assertNull(value(RECORD));
        Assert.assertEquals(2, KvTxnApplier.lastIndex(this.store));

        request = txn(put("b", "2"))
                .addCompares(compare(TxnCompare.Kind.EQUALS, "a", "1"))
                .addCompares(compare(TxnCompare.Kind.ABSENT, "b", "")).build();
        Assert.assertTrue(apply(3, request).getSucceeded());
        Assert.assertEquals("2", value("b"));
    }

    @Test
    public void testReplayIsSkipped() throws Exception {
        TxnRequest create = txn(put("a", "1")).setRecord(record(TxnRecord.Op.CREATE, 0)).build();
        Assert.assertEquals(1, apply(4, create).getIncarnation());
        apply(5, txn(put("a", "2")).build());

        // A restart applies 4 and 5 again over the store that holds them
        Assert.assertFalse(apply(4, create).getSucceeded());
        Assert.assertFalse(apply(5, txn(put("a", "2")).build()).getSucceeded());
        Assert.assertEquals("2", value("a"));
        assertRecord(4, 1, KvTxnApplier.LIVE);
        Assert.assertEquals(1, this.applied.size());
    }

    @Test
    public void testDeletePrefix() throws Exception {
        apply(1, txn(put("p/1", "x"), put("p/2", "x"), put("q/1", "x")).build());
        TxnResponse response = apply(2, txn(put("p/3", "x"), op(TxnOp.Type.DELETE_PREFIX, "p/", ""),
                                            put("p/4", "y")).build());
        Assert.assertTrue(response.getSucceeded());
        Assert.assertNull(value("p/1"));
        Assert.assertNull(value("p/2"));
        Assert.assertNull(value("p/3"));
        Assert.assertEquals("y", value("p/4"));
        Assert.assertEquals("x", value("q/1"));
    }

    @Test
    public void testRecordLifecycle() throws Exception {
        // A graph created before records existed
        TxnResponse response = apply(3, txn(put("s", "1"))
                .setRecord(record(TxnRecord.Op.BUMP, 0)).build());
        Assert.assertTrue(response.getSucceeded());
        Assert.assertEquals(1, response.getIncarnation());
        assertRecord(3, 1, KvTxnApplier.LIVE);

        response = apply(4, txn().setRecord(record(TxnRecord.Op.BUMP, 1)).build());
        Assert.assertTrue(response.getSucceeded());
        assertRecord(4, 1, KvTxnApplier.LIVE);

        response = apply(5, txn(op(TxnOp.Type.DELETE_PREFIX, "s", ""))
                .setRecord(record(TxnRecord.Op.DROP, 1)).build());
        Assert.assertTrue(response.getSucceeded());
        assertRecord(5, 1, KvTxnApplier.DROPPED);
        Assert.assertNull(value("s"));

        // A delayed write of the dropped graph can't resurrect it
        response = apply(6, txn(put("s", "2")).setRecord(record(TxnRecord.Op.BUMP, 0)).build());
        Assert.assertFalse(response.getSucceeded());
        Assert.assertEquals(TxnResponse.Failure.GRAPH_DROPPED, response.getFailure());
        Assert.assertEquals(1, response.getIncarnation());
        Assert.assertNull(value("s"));
        assertRecord(5, 1, KvTxnApplier.DROPPED);

        response = apply(7, txn().setRecord(record(TxnRecord.Op.CREATE, 0)).build());
        Assert.assertTrue(response.getSucceeded());
        Assert.assertEquals(2, response.getIncarnation());
        assertRecord(7, 2, KvTxnApplier.LIVE);

        Assert.assertEquals(List.of("3 " + RECORD + " 3 1 LIVE", "4 " + RECORD + " 4 1 LIVE",
                                    "5 " + RECORD + " 5 1 DROPPED",
                                    "7 " + RECORD + " 7 2 LIVE"), this.applied);
    }

    @Test
    public void testIncarnationMismatch() throws Exception {
        apply(1, txn().setRecord(record(TxnRecord.Op.CREATE, 0)).build());
        apply(2, txn().setRecord(record(TxnRecord.Op.DROP, 1)).build());
        apply(3, txn().setRecord(record(TxnRecord.Op.CREATE, 0)).build());

        TxnResponse response = apply(4, txn(put("s", "old"))
                .setRecord(record(TxnRecord.Op.BUMP, 1)).build());
        Assert.assertFalse(response.getSucceeded());
        Assert.assertEquals(TxnResponse.Failure.INCARNATION_MISMATCH, response.getFailure());
        Assert.assertEquals(2, response.getIncarnation());
        Assert.assertNull(value("s"));

        response = apply(5, txn().setRecord(record(TxnRecord.Op.DROP, 1)).build());
        Assert.assertEquals(TxnResponse.Failure.INCARNATION_MISMATCH, response.getFailure());
        assertRecord(3, 2, KvTxnApplier.LIVE);

        Assert.assertTrue(apply(6, txn(put("s", "new"))
                .setRecord(record(TxnRecord.Op.BUMP, 2)).build()).getSucceeded());
        Assert.assertEquals("new", value("s"));
        Assert.assertEquals(4, this.applied.size());
    }

    /**
     * Two Servers creating the same graph: PD gives the name to the first CREATE only, the
     * second is rejected without fencing the first
     */
    @Test
    public void testCreateRejectsALiveGraph() throws Exception {
        TxnResponse response = apply(1, txn().setRecord(record(TxnRecord.Op.CREATE, 0)).build());
        Assert.assertTrue(response.getSucceeded());
        Assert.assertEquals(1, response.getIncarnation());

        response = apply(2, txn(put("s", "x")).setRecord(record(TxnRecord.Op.CREATE, 0)).build());
        Assert.assertFalse(response.getSucceeded());
        Assert.assertEquals(TxnResponse.Failure.GRAPH_EXISTS, response.getFailure());
        // The LIVE incarnation, which a recovery of an orphaned record drops
        Assert.assertEquals(1, response.getIncarnation());
        Assert.assertNull(value("s"));
        assertRecord(1, 1, KvTxnApplier.LIVE);
        // The first creator still writes
        Assert.assertTrue(apply(3, txn().setRecord(record(TxnRecord.Op.BUMP, 1)).build())
                                  .getSucceeded());

        // A created graph whose record came from a BUMP (before the upgrade) is LIVE as well
        String legacy = "HUGEGRAPH/hg/SCHEMA_SYNC/DEFAULT/legacy";
        apply(4, txn().setRecord(record(TxnRecord.Op.BUMP, 0).toBuilder().setKey(legacy))
                      .build());
        response = apply(5, txn().setRecord(record(TxnRecord.Op.CREATE, 0).toBuilder()
                                                                         .setKey(legacy))
                                 .build());
        Assert.assertEquals(TxnResponse.Failure.GRAPH_EXISTS, response.getFailure());

        // Once dropped, the name can be created again with the next incarnation
        Assert.assertTrue(apply(6, txn().setRecord(record(TxnRecord.Op.DROP, 1)).build())
                                  .getSucceeded());
        response = apply(7, txn().setRecord(record(TxnRecord.Op.CREATE, 0)).build());
        Assert.assertTrue(response.getSucceeded());
        Assert.assertEquals(2, response.getIncarnation());
    }

    /**
     * A record that can't be read fails every TXN on it the same way on every node, rather
     * than counting as absent and restarting the incarnation at 1
     */
    @Test
    public void testUnreadableRecordFailsTheTxn() throws Exception {
        String[] invalid = {
                "not json", "[1]", "null", "{}",
                "{\"rev\":1,\"state\":\"LIVE\"}",
                "{\"rev\":1,\"inc\":0,\"state\":\"LIVE\"}",
                "{\"rev\":1,\"inc\":-2,\"state\":\"LIVE\"}",
                "{\"rev\":1,\"inc\":1.5,\"state\":\"LIVE\"}",
                "{\"rev\":1,\"inc\":\"3\",\"state\":\"LIVE\"}",
                "{\"rev\":1,\"inc\":99999999999999999999,\"state\":\"LIVE\"}",
                "{\"rev\":1,\"inc\":3}",
                "{\"rev\":1,\"inc\":3,\"state\":\"GONE\"}",
                "{\"rev\":1,\"inc\":3,\"state\":1}",
                "{\"rev\":1,\"inc\":{},\"state\":\"LIVE\"}"
        };
        long index = 0;
        for (String value : invalid) {
            apply(++index, txn(put(RECORD, value)).build());
            for (TxnRecord.Op op : new TxnRecord.Op[]{TxnRecord.Op.BUMP, TxnRecord.Op.CREATE,
                                                      TxnRecord.Op.DROP}) {
                TxnResponse response = apply(++index, txn(put("s", value))
                        .setRecord(record(op, 0)).build());
                Assert.assertFalse(value, response.getSucceeded());
                Assert.assertEquals(value, TxnResponse.Failure.INVALID_RECORD,
                                    response.getFailure());
                Assert.assertEquals(0, response.getIncarnation());
                Assert.assertEquals(value, value(RECORD));
                Assert.assertNull(value("s"));
                Assert.assertEquals(index, KvTxnApplier.lastIndex(this.store));
            }
        }
        Assert.assertTrue(this.applied.isEmpty());

        // A valid record, also with an integral number written as 3.0, still works
        apply(++index, txn(put(RECORD, "{\"rev\":1,\"inc\":3.0,\"state\":\"LIVE\"}"))
                .build());
        TxnResponse response = apply(++index, txn().setRecord(record(TxnRecord.Op.BUMP, 3))
                                                    .build());
        Assert.assertTrue(response.getSucceeded());
        assertRecord(index, 3, KvTxnApplier.LIVE);
    }

    @Test
    public void testListenerFailureKeepsTheTxn() throws Exception {
        TxnRequest request = txn(put("a", "1")).setRecord(record(TxnRecord.Op.BUMP, 0)).build();
        TxnResponse response = KvTxnApplier.apply(this.store, request, 1, (i, k, r, n, s) -> {
            throw new IllegalStateException("dispatch failed");
        });
        Assert.assertTrue(response.getSucceeded());
        Assert.assertEquals("1", value("a"));
        assertRecord(1, 1, KvTxnApplier.LIVE);
    }

    @Test
    public void testValidate() {
        TxnRequest request = txn().setRecord(TxnRecord.newBuilder().setOp(TxnRecord.Op.BUMP))
                                  .build();
        Assert.assertThrows(PDException.class, () -> KvTxnApplier.validate(request));
    }

    /**
     * The TXN result reaches the proposer through the closure adapter of the state machine
     */
    @Test
    public void testRaftApplyReturnsTheResult() throws Exception {
        RaftKVStore raftStore = new RaftKVStore(RaftEngine.getInstance(), this.store);
        KVOperation op = KVOperation.createTxn(txn(put("a", "1")).build().toByteArray());
        op.setIndex(9);
        KVStoreClosure closure = new BaseKVStoreClosure() {
            @Override
            public void run(Status status) {
            }
        };
        raftStore.invoke(op, new RaftStateMachine.RaftClosureAdapter(op, closure));
        TxnResponse response = (TxnResponse) closure.getData();
        Assert.assertTrue(response.getSucceeded());
        Assert.assertEquals(9, response.getRevision());
        Assert.assertEquals("1", value("a"));
    }

    private TxnResponse apply(long index, TxnRequest request) throws Exception {
        return KvTxnApplier.apply(this.store, request, index, this.listener);
    }

    private String value(String key) throws Exception {
        byte[] bytes = this.store.get(KvService.getKeyBytes(key));
        return bytes == null ? null : V.parseFrom(bytes).getValue();
    }

    @SuppressWarnings("unchecked")
    private void assertRecord(long rev, long inc, String state) throws Exception {
        Map<String, Object> record = new Gson().fromJson(value(RECORD), Map.class);
        Assert.assertEquals((double) rev, record.get("rev"));
        Assert.assertEquals((double) inc, record.get("inc"));
        Assert.assertEquals(state, record.get("state"));
    }

    private static TxnRequest.Builder txn(TxnOp... ops) {
        TxnRequest.Builder builder = TxnRequest.newBuilder();
        for (TxnOp op : ops) {
            builder.addOps(op);
        }
        return builder;
    }

    private static TxnOp put(String key, String value) {
        return op(TxnOp.Type.PUT, key, value);
    }

    private static TxnOp op(TxnOp.Type type, String key, String value) {
        return TxnOp.newBuilder().setType(type).setKey(key).setValue(value).build();
    }

    private static TxnCompare compare(TxnCompare.Kind kind, String key, String value) {
        return TxnCompare.newBuilder().setKind(kind).setKey(key).setValue(value).build();
    }

    private static TxnRecord record(TxnRecord.Op op, long expectedIncarnation) {
        return TxnRecord.newBuilder().setKey(RECORD).setOp(op)
                        .setExpectedIncarnation(expectedIncarnation).build();
    }
}
