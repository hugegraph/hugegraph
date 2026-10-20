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

import java.util.Map;

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
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

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
                                                   .setType(TxnOp.Type.PUT).setValue("1"))
                                      .addOps(TxnOp.newBuilder().setKey("txn-test/b")
                                                   .setType(TxnOp.Type.PUT).setValue("2"))
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
                TxnOp.newBuilder().setKey("txn-test/c").setType(TxnOp.Type.PUT)
                     .setValue("3")).build());
        Assert.assertFalse(rejected.getSucceeded());
        Assert.assertEquals(TxnResponse.Failure.COMPARE_FAILED, rejected.getFailure());
        Assert.assertEquals("", service.get("txn-test/c"));

        // An empty DELETE_PREFIX key is every KV key: rejected, and nothing is deleted
        TxnRequest all = TxnRequest.newBuilder().addOps(
                TxnOp.newBuilder().setType(TxnOp.Type.DELETE_PREFIX)).build();
        Assert.assertThrows(PDException.class, () -> service.txn(all));
        Assert.assertEquals("1", service.get("txn-test/a"));

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

    /**
     * The TTL cleanup deletes the key an expired marker names, and the marker
     */
    @Test
    public void testTtlCleanupDeletesTheExpiredKey() throws Exception {
        KvService service = new KvService(getPdConfig());
        service.put("ttl-test/a", "old", 1L);
        byte[] storeKey = service.getStoreKey("ttl-test/a");
        String marker = "T@" + V.parseFrom(service.getMeta().getOne(storeKey)).getTtl() +
                        "@ttl-test/a";
        Thread.sleep(20L);
        service.clearTTLData();
        Assert.assertNull(service.getMeta().getOne(storeKey));
        Assert.assertNull(service.getMeta().getOne(service.getStoreKey(marker)));
    }

    /**
     * A txn that writes the key between the cleanup's read and its delete keeps its value:
     * the cleanup's compare on the value it read fails, and only the marker goes
     */
    @Test
    public void testTtlCleanupKeepsAValueATxnWroteMeanwhile() throws Exception {
        KvService service = new KvService(getPdConfig());
        service.put("ttl-test/b", "old", 1L);
        String marker = marker(service, "ttl-test/b");
        byte[] scanned = service.getMeta().getOne(service.getStoreKey("ttl-test/b"));
        TxnRequest write = TxnRequest.newBuilder().addOps(
                TxnOp.newBuilder().setType(TxnOp.Type.PUT).setKey("ttl-test/b")
                     .setValue("new")).build();
        Assert.assertTrue(service.txn(write).getSucceeded());
        service.removeExpired("ttl-test/b", marker, scanned);
        Assert.assertEquals("new", service.get("ttl-test/b"));
        Assert.assertNull(service.getMeta().getOne(service.getStoreKey(marker)));
    }

    /**
     * A txn that writes the key between the cleanup's marker scan and its read: the cleanup
     * reads the new value, which does not carry the marker's expiry, so it keeps it
     */
    @Test
    public void testTtlCleanupKeepsAValueATxnWroteBeforeItsRead() throws Exception {
        KvService service = new KvService(getPdConfig());
        service.put("ttl-test/e", "old", 1L);
        String marker = marker(service, "ttl-test/e");
        Assert.assertTrue(service.txn(putTxn("ttl-test/e", "new")).getSucceeded());
        byte[] read = service.getMeta().getOne(service.getStoreKey("ttl-test/e"));
        service.removeExpired("ttl-test/e", marker, read);
        Assert.assertEquals("new", service.get("ttl-test/e"));
        Assert.assertNull(service.getMeta().getOne(service.getStoreKey(marker)));
    }

    /**
     * A txn that rewrites the identical string between the cleanup's read and its delete
     * removed the marker in its batch, so the cleanup's compare on the marker fails at the
     * apply and the value is kept
     */
    @Test
    public void testTtlCleanupKeepsAnIdenticalValueATxnWrote() throws Exception {
        KvService service = new KvService(getPdConfig());
        service.put("ttl-test/f", "same", 1L);
        String marker = marker(service, "ttl-test/f");
        byte[] read = service.getMeta().getOne(service.getStoreKey("ttl-test/f"));
        Assert.assertTrue(service.txn(putTxn("ttl-test/f", "same")).getSucceeded());
        service.removeExpired("ttl-test/f", marker, read);
        byte[] kept = service.getMeta().getOne(service.getStoreKey("ttl-test/f"));
        Assert.assertNotNull(kept);
        Assert.assertEquals(0L, V.parseFrom(kept).getTtl());
        Assert.assertEquals("same", service.get("ttl-test/f"));
    }

    /**
     * A plain put without a TTL over a TTL value keeps the marker; the cleanup sees the value
     * is not the one the marker names and drops the marker alone
     */
    @Test
    public void testTtlCleanupKeepsAPlainPutOverATtlValue() throws Exception {
        KvService service = new KvService(getPdConfig());
        service.put("ttl-test/g", "old", 1L);
        String marker = marker(service, "ttl-test/g");
        service.put("ttl-test/g", "plain");
        Thread.sleep(20L);
        service.clearTTLData();
        Assert.assertEquals("plain", service.get("ttl-test/g"));
        Assert.assertNull(service.getMeta().getOne(service.getStoreKey(marker)));
    }

    private static TxnRequest putTxn(String key, String value) {
        return TxnRequest.newBuilder().addOps(
                TxnOp.newBuilder().setType(TxnOp.Type.PUT).setKey(key).setValue(value)).build();
    }

    /**
     * While a PD peer blocks txn, the cleanup deletes with plain removes as before; any other
     * txn failure is the cleanup's error, with nothing removed
     */
    @Test
    public void testTtlCleanupFallsBackWhileTxnIsBlocked() throws Exception {
        KvService service = Mockito.spy(new KvService(getPdConfig()));
        Mockito.doThrow(new PDException(Pdpb.ErrorType.TXN_NOT_PROPOSED_VALUE, "peer"))
               .when(service).txn(Mockito.any());
        service.put("ttl-test/c", "old", 1L);
        String marker = marker(service, "ttl-test/c");
        byte[] scanned = service.getMeta().getOne(service.getStoreKey("ttl-test/c"));
        service.removeExpired("ttl-test/c", marker, scanned);
        service.removeExpired("ttl-test/c", marker, scanned);
        Assert.assertNull(service.getMeta().getOne(service.getStoreKey("ttl-test/c")));
        Assert.assertNull(service.getMeta().getOne(service.getStoreKey(marker)));

        Mockito.doThrow(new PDException(Pdpb.ErrorType.UNKNOWN_VALUE, "lost"))
               .when(service).txn(Mockito.any());
        service.put("ttl-test/d", "old", 1L);
        String other = marker(service, "ttl-test/d");
        byte[] stored = service.getMeta().getOne(service.getStoreKey("ttl-test/d"));
        Assert.assertThrows(PDException.class,
                            () -> service.removeExpired("ttl-test/d", other, stored));
        Assert.assertNotNull(service.getMeta().getOne(service.getStoreKey(other)));
        Assert.assertNotNull(service.getMeta().getOne(service.getStoreKey("ttl-test/d")));
    }

    /**
     * The marker key of a TTL value, without the K@ prefix
     */
    private static String marker(KvService service, String key) throws Exception {
        byte[] stored = service.getMeta().getOne(service.getStoreKey(key));
        return "T@" + V.parseFrom(stored).getTtl() + "@" + key;
    }
}
