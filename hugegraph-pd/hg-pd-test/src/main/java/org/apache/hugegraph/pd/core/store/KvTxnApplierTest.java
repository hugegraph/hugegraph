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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;

import org.apache.commons.io.FileUtils;
import org.apache.hugegraph.pd.KvService;
import org.apache.hugegraph.pd.common.PDException;
import org.apache.hugegraph.pd.config.PDConfig;
import org.apache.hugegraph.pd.grpc.Pdpb;
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
import org.apache.hugegraph.pd.store.KV;
import org.apache.hugegraph.pd.store.KvTxnApplier;
import org.apache.hugegraph.pd.store.RaftKVStore;
import org.apache.hugegraph.pd.store.TxnApplyListener;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import com.alipay.sofa.jraft.Status;
import com.alipay.sofa.jraft.entity.Task;
import com.alipay.sofa.jraft.error.RaftError;
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

    /**
     * An empty value is stored as zero bytes, which is not an absent key
     */
    @Test
    public void testEmptyValueIsNotAbsent() throws Exception {
        Assert.assertTrue(apply(1, txn(put("e", "")).setRecord(record(TxnRecord.Op.BUMP, 0))
                                   .build()).getSucceeded());
        Assert.assertEquals("", value("e"));

        TxnResponse response = apply(2, txn(put("x", "1"))
                .addCompares(compare(TxnCompare.Kind.ABSENT, "e", ""))
                .setRecord(record(TxnRecord.Op.BUMP, 1)).build());
        Assert.assertEquals(TxnResponse.Failure.COMPARE_FAILED, response.getFailure());
        Assert.assertNull(value("x"));

        Assert.assertTrue(apply(3, txn(put("x", "1"))
                .addCompares(compare(TxnCompare.Kind.EQUALS, "e", ""))
                .setRecord(record(TxnRecord.Op.BUMP, 1)).build()).getSucceeded());
        Assert.assertEquals("1", value("x"));
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

        // Created again: the next incarnation, LIVE at once
        response = apply(7, txn().setRecord(record(TxnRecord.Op.CREATE, 0)).build());
        Assert.assertTrue(response.getSucceeded());
        Assert.assertEquals(2, response.getIncarnation());
        Assert.assertEquals(KvTxnApplier.LIVE, response.getState());
        assertRecord(7, 2, KvTxnApplier.LIVE);
        response = apply(8, txn(put("s", "3")).setRecord(record(TxnRecord.Op.BUMP, 2)).build());
        Assert.assertTrue(response.getSucceeded());
        assertRecord(8, 2, KvTxnApplier.LIVE);
        Assert.assertEquals("3", value("s"));

        Assert.assertEquals(List.of("3 " + RECORD + " 3 1 LIVE", "4 " + RECORD + " 4 1 LIVE",
                                    "5 " + RECORD + " 5 1 DROPPED", "7 " + RECORD + " 7 2 LIVE",
                                    "8 " + RECORD + " 8 2 LIVE"), this.applied);
    }

    /**
     * A CREATE marks the record it writes "src":"create", and BUMP and DROP keep the mark; a
     * record a BUMP created, as for a graph opened from a local config, has none. The mark
     * never changes: the Server uses it to tell a create that died before its config write
     * from a graph no create made.
     */
    @Test
    public void testCreateMarksTheRecordForGood() throws Exception {
        apply(1, txn().setRecord(record(TxnRecord.Op.BUMP, 0)).build());
        Assert.assertNull(recordField("src"));
        apply(2, txn().setRecord(record(TxnRecord.Op.DROP, 1)).build());
        Assert.assertNull(recordField("src"));

        apply(3, txn().setRecord(record(TxnRecord.Op.CREATE, 0)).build());
        Assert.assertEquals(KvTxnApplier.CREATED, recordField("src"));
        apply(4, txn(put("s", "1")).setRecord(record(TxnRecord.Op.BUMP, 2)).build());
        assertRecord(4, 2, KvTxnApplier.LIVE);
        Assert.assertEquals(KvTxnApplier.CREATED, recordField("src"));
        apply(5, txn().setRecord(record(TxnRecord.Op.DROP, 2)).build());
        assertRecord(5, 2, KvTxnApplier.DROPPED);
        Assert.assertEquals(KvTxnApplier.CREATED, recordField("src"));

        // Any other value is no mark, and is not carried over as one
        apply(6, txn(put(RECORD, "{\"rev\":6,\"inc\":3,\"state\":\"LIVE\",\"src\":7}"))
                .build());
        Assert.assertTrue(apply(7, txn().setRecord(record(TxnRecord.Op.BUMP, 3)).build())
                                  .getSucceeded());
        Assert.assertNull(recordField("src"));
    }

    /**
     * DROP takes only a LIVE record of its incarnation
     */
    @Test
    public void testDropChecksStateAndIncarnation() throws Exception {
        apply(1, txn().setRecord(record(TxnRecord.Op.CREATE, 0)).build());
        TxnResponse response = apply(2, txn().setRecord(record(TxnRecord.Op.DROP, 2)).build());
        Assert.assertEquals(TxnResponse.Failure.INCARNATION_MISMATCH, response.getFailure());
        Assert.assertEquals(KvTxnApplier.LIVE, response.getState());
        Assert.assertTrue(apply(3, txn().setRecord(record(TxnRecord.Op.DROP, 1)).build())
                                  .getSucceeded());
        response = apply(4, txn().setRecord(record(TxnRecord.Op.DROP, 1)).build());
        Assert.assertEquals(TxnResponse.Failure.GRAPH_DROPPED, response.getFailure());
        response = apply(5, txn().setRecord(record(TxnRecord.Op.BUMP, 1)).build());
        Assert.assertEquals(TxnResponse.Failure.GRAPH_DROPPED, response.getFailure());
        assertRecord(3, 1, KvTxnApplier.DROPPED);

        // No record: nothing to drop
        String other = "HUGEGRAPH/hg/SCHEMA_SYNC/DEFAULT/other";
        response = apply(6, txn().setRecord(record(TxnRecord.Op.DROP, 1).toBuilder()
                                                                       .setKey(other)).build());
        Assert.assertEquals(TxnResponse.Failure.INCARNATION_MISMATCH, response.getFailure());
        Assert.assertNull(value(other));
    }

    /**
     * The Server guards a CREATE with its graph config ABSENT and the record as it read it,
     * absent or DROPPED, so a LIVE record is never taken over. A create whose Server died
     * before it wrote the config leaves one: the name stays taken until a DROP of that
     * incarnation, then the next CREATE starts the next incarnation and removes the schema
     * the dead create left, and the instance of the dead create can't write any more.
     */
    @Test
    public void testGuardedCreateWaitsForTheNameToBeDropped() throws Exception {
        TxnRequest create = txn(op(TxnOp.Type.DELETE_PREFIX, "schema/g/", ""))
                .addCompares(compare(TxnCompare.Kind.ABSENT, "conf/g", ""))
                .addCompares(compare(TxnCompare.Kind.ABSENT, RECORD, ""))
                .setRecord(record(TxnRecord.Op.CREATE, 0)).build();
        TxnResponse response = apply(1, create);
        Assert.assertTrue(response.getSucceeded());
        Assert.assertEquals(1, response.getIncarnation());
        apply(2, txn(put("schema/g/1", "pk")).setRecord(record(TxnRecord.Op.BUMP, 1)).build());

        // The creator died before it wrote the config: the name is still taken
        response = apply(3, create);
        Assert.assertEquals(TxnResponse.Failure.COMPARE_FAILED, response.getFailure());
        Assert.assertEquals(1, response.getFailedCompare());
        Assert.assertEquals("pk", value("schema/g/1"));
        assertRecord(2, 1, KvTxnApplier.LIVE);

        // Released by a drop of its incarnation, then created again
        Assert.assertTrue(apply(4, txn().setRecord(record(TxnRecord.Op.DROP, 1)).build())
                                  .getSucceeded());
        TxnRequest again = txn(op(TxnOp.Type.DELETE_PREFIX, "schema/g/", ""))
                .addCompares(compare(TxnCompare.Kind.ABSENT, "conf/g", ""))
                .addCompares(compare(TxnCompare.Kind.EQUALS, RECORD, value(RECORD)))
                .setRecord(record(TxnRecord.Op.CREATE, 0)).build();
        response = apply(5, again);
        Assert.assertTrue(response.getSucceeded());
        Assert.assertEquals(2, response.getIncarnation());
        Assert.assertNull(value("schema/g/1"));
        assertRecord(5, 2, KvTxnApplier.LIVE);
        response = apply(6, txn(put("schema/g/1", "old"))
                .setRecord(record(TxnRecord.Op.BUMP, 1)).build());
        Assert.assertEquals(TxnResponse.Failure.INCARNATION_MISMATCH, response.getFailure());
        Assert.assertNull(value("schema/g/1"));

        // The record read before the create no longer matches
        response = apply(7, again);
        Assert.assertEquals(TxnResponse.Failure.COMPARE_FAILED, response.getFailure());
        assertRecord(5, 2, KvTxnApplier.LIVE);
    }

    /**
     * A TXN is not deduplicated: sent again after a lost response, it is applied again. A BUMP
     * with puts writes the same values, a CREATE starts one more incarnation, and a DROP of an
     * incarnation already dropped is rejected with GRAPH_DROPPED.
     */
    @Test
    public void testTxnSentAgainIsAppliedAgain() throws Exception {
        TxnRequest create = txn().setRecord(record(TxnRecord.Op.CREATE, 0)).build();
        Assert.assertEquals(1, apply(1, create).getIncarnation());
        Assert.assertEquals(2, apply(2, create).getIncarnation());

        TxnRequest bump = txn(put("a", "1")).setRecord(record(TxnRecord.Op.BUMP, 2)).build();
        Assert.assertEquals(3, apply(3, bump).getRevision());
        Assert.assertEquals(4, apply(4, bump).getRevision());
        Assert.assertEquals("1", value("a"));
        assertRecord(4, 2, KvTxnApplier.LIVE);

        TxnRequest drop = txn().setRecord(record(TxnRecord.Op.DROP, 2)).build();
        Assert.assertTrue(apply(5, drop).getSucceeded());
        Assert.assertEquals(TxnResponse.Failure.GRAPH_DROPPED, apply(6, drop).getFailure());
        assertRecord(5, 2, KvTxnApplier.DROPPED);
        Assert.assertEquals(5, this.applied.size());
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
     * A record that can't be read fails every TXN on it the same way on every node, rather
     * than counting as absent and restarting the incarnation at 1
     */
    @Test
    public void testUnreadableRecordFailsTheTxn() throws Exception {
        String[] invalid = {
                // An empty value is a value: a BUMP 0 must not take it for an absent record
                "", "not json", "[1]", "null", "{}",
                "{\"rev\":1,\"state\":\"LIVE\"}",
                "{\"rev\":1,\"inc\":0,\"state\":\"LIVE\"}",
                "{\"rev\":1,\"inc\":-2,\"state\":\"LIVE\"}",
                "{\"rev\":1,\"inc\":1.5,\"state\":\"LIVE\"}",
                "{\"rev\":1,\"inc\":\"3\",\"state\":\"LIVE\"}",
                "{\"rev\":1,\"inc\":99999999999999999999,\"state\":\"LIVE\"}",
                "{\"rev\":1,\"inc\":3}",
                "{\"rev\":1,\"inc\":3,\"state\":\"GONE\"}",
                "{\"rev\":1,\"inc\":3,\"state\":1}",
                "{\"rev\":1,\"inc\":{},\"state\":\"LIVE\"}",
                "{\"rev\":\"1\",\"inc\":3,\"state\":\"LIVE\"}",
                "{\"rev\":-1,\"inc\":3,\"state\":\"LIVE\"}",
                // Without a positive rev, which the tracker and the Server reject too
                "{\"inc\":3,\"state\":\"LIVE\"}",
                "{\"rev\":0,\"inc\":3,\"state\":\"LIVE\"}"
        };
        long index = 0;
        for (String value : invalid) {
            apply(++index, txn(put(RECORD, value)).build());
            for (TxnRecord.Op op : TxnRecord.Op.values()) {
                if (op == TxnRecord.Op.UNRECOGNIZED) {
                    continue;
                }
                TxnResponse response = apply(++index, txn(put("s", value))
                        .setRecord(record(op, 1)).build());
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
        apply(++index, txn(put(RECORD, "{\"rev\":1,\"inc\":3.0,\"state\":\"LIVE\"}")).build());
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
    public void testValidate() throws PDException {
        TxnRequest noKey = txn().setRecord(TxnRecord.newBuilder().setOp(TxnRecord.Op.BUMP))
                                .build();
        Assert.assertThrows(PDException.class, () -> KvTxnApplier.validate(noKey));
        TxnRequest unchecked = txn().setRecord(record(TxnRecord.Op.DROP, 0)).build();
        Assert.assertThrows(PDException.class, () -> KvTxnApplier.validate(unchecked));
        // An empty key: to a DELETE_PREFIX it would be every KV key
        for (TxnOp.Type type : new TxnOp.Type[]{TxnOp.Type.PUT, TxnOp.Type.DELETE,
                                                TxnOp.Type.DELETE_PREFIX}) {
            TxnRequest emptyKey = txn(op(type, "", "1")).build();
            Assert.assertThrows(PDException.class, () -> KvTxnApplier.validate(emptyKey));
        }
        TxnRequest emptyCompare = txn(put("a", "1")).addCompares(
                compare(TxnCompare.Kind.ABSENT, "", "")).build();
        Assert.assertThrows(PDException.class, () -> KvTxnApplier.validate(emptyCompare));
        KvTxnApplier.validate(txn(op(TxnOp.Type.DELETE_PREFIX, "a", "")).addCompares(
                compare(TxnCompare.Kind.ABSENT, "a", "")).build());
        KvTxnApplier.validate(txn().setRecord(record(TxnRecord.Op.DROP, 1)).build());
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

    /**
     * A restart without a snapshot applies the log again over the store that holds it. The log
     * here starts with plain writes from before the upgrade (a schema put and delete of a
     * Server that did not use TXNs yet), then has TXNs of the graph's lifecycle and plain
     * writes after them. Replaying it, once or twice, leaves the store as it was; a plain
     * write before a TXN must not be applied again over what the TXN wrote.
     */
    @Test
    public void testRaftReplayOfLegacyPlainWritesAndTxns() throws Exception {
        RaftKVStore raftStore = new RaftKVStore(RaftEngine.getInstance(), this.store);
        List<KVOperation> log = new ArrayList<>();
        log.add(KVOperation.createPut(KvService.getKeyBytes("schema/a"), wrap("old")));
        log.add(KVOperation.createPut(KvService.getKeyBytes("schema/b"), wrap("x")));
        log.add(KVOperation.createRemove(KvService.getKeyBytes("schema/b")));
        // A TTL entry and the CLEAR of the TTL memory, both from before the first TXN
        log.add(KVOperation.createPutWithTTL(KvService.getKeyBytes("ttl/a"), wrap("t"), 3600L));
        log.add(KVOperation.createClear());
        log.add(txnOp(txn().setRecord(record(TxnRecord.Op.BUMP, 0))));
        log.add(txnOp(txn(put("schema/a", "new"), put("schema/b", "y"))
                              .setRecord(record(TxnRecord.Op.BUMP, 1))));
        log.add(KVOperation.createPut(KvService.getKeyBytes("other"), wrap("1")));
        log.add(txnOp(txn(op(TxnOp.Type.DELETE_PREFIX, "schema/", ""))
                              .setRecord(record(TxnRecord.Op.DROP, 1))));
        log.add(txnOp(txn(put("schema/a", "v2")).setRecord(record(TxnRecord.Op.CREATE, 0))));
        log.add(KVOperation.createRemove(KvService.getKeyBytes("other")));
        log.add(KVOperation.createPut(KvService.getKeyBytes("other"), wrap("2")));
        for (int i = 0; i < log.size(); i++) {
            log.get(i).setIndex(i + 1);
            raftStore.invoke(log.get(i), null);
        }
        Map<String, String> applied = rawKeys("");
        Assert.assertEquals("v2", value("schema/a"));
        Assert.assertNull(value("schema/b"));
        Assert.assertEquals("2", value("other"));
        Assert.assertNull(this.store.getWithTTL(KvService.getKeyBytes("ttl/a")));
        assertRecord(10, 2, KvTxnApplier.LIVE);

        // The restart, twice: the TTL put is applied again, and so is the CLEAR after it
        for (int round = 0; round < 2; round++) {
            for (KVOperation op : log) {
                raftStore.invoke(op, null);
            }
            Assert.assertEquals(applied, rawKeys(""));
            Assert.assertNull(this.store.getWithTTL(KvService.getKeyBytes("ttl/a")));
        }
        Assert.assertEquals(10, KvTxnApplier.lastIndex(this.store));

        // An operation without a raft index is never skipped
        raftStore.invoke(KVOperation.createPut(KvService.getKeyBytes("schema/a"),
                                               wrap("direct")), null);
        Assert.assertEquals("direct", value("schema/a"));
    }

    /**
     * A TXN that fails in raft, here on a node that lost the leadership, reaches the caller
     * with the failure as its cause, not only with a message
     */
    @Test
    public void testRaftTxnFailureKeepsItsCause() {
        RaftEngine engine = Mockito.mock(RaftEngine.class);
        Mockito.doAnswer(invocation -> {
            Task task = invocation.getArgument(0);
            task.getDone().run(new Status(RaftError.EPERM, "Not leader"));
            return null;
        }).when(engine).addTask(Mockito.any());
        RaftKVStore raftStore = new RaftKVStore(engine, this.store);

        PDException e = Assert.assertThrows(PDException.class, () -> {
            raftStore.txn(txn(put("a", "1")).build());
        });
        Assert.assertTrue(e.getCause() instanceof ExecutionException);
        Throwable applied = e.getCause().getCause();
        Assert.assertTrue(applied instanceof PDException);
        Assert.assertEquals(Pdpb.ErrorType.NOT_LEADER_VALUE,
                            ((PDException) applied).getErrorCode());
    }

    private KVOperation txnOp(TxnRequest.Builder request) {
        return KVOperation.createTxn(request.build().toByteArray());
    }

    /**
     * Every key in the store that starts with the prefix, raw keys included, with its value
     */
    private Map<String, String> rawKeys(String prefix) {
        Map<String, String> kvs = new TreeMap<>();
        for (KV kv : this.store.scanPrefix(prefix.getBytes(StandardCharsets.UTF_8))) {
            kvs.put(new String(kv.getKey(), StandardCharsets.ISO_8859_1),
                    new String(kv.getValue(), StandardCharsets.ISO_8859_1));
        }
        return kvs;
    }

    private static byte[] wrap(String value) {
        return V.newBuilder().setValue(value).build().toByteArray();
    }

    private TxnResponse apply(long index, TxnRequest request) throws Exception {
        return KvTxnApplier.apply(this.store, request, index, this.listener);
    }

    private String value(String key) throws Exception {
        byte[] bytes = this.store.get(KvService.getKeyBytes(key));
        return bytes == null ? null : V.parseFrom(bytes).getValue();
    }

    @SuppressWarnings("unchecked")
    private Object recordField(String name) throws Exception {
        return new Gson().fromJson(value(RECORD), Map.class).get(name);
    }

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
