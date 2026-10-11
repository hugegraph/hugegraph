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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.hugegraph.pd.KvService;
import org.apache.hugegraph.pd.common.PDException;
import org.apache.hugegraph.pd.config.PDConfig;
import org.apache.hugegraph.pd.grpc.kv.TxnCompare;
import org.apache.hugegraph.pd.grpc.kv.TxnOp;
import org.apache.hugegraph.pd.grpc.kv.TxnRecord;
import org.apache.hugegraph.pd.grpc.kv.TxnRequest;
import org.apache.hugegraph.pd.grpc.kv.TxnResponse;
import org.junit.Assert;
import org.junit.Test;

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
     * Two Servers creating the same graph at the same time: for each name exactly one CREATE
     * gets it, and the config is the winner's
     */
    @Test
    public void testConcurrentCreatesOfOneName() throws Exception {
        KvService service = new KvService(getPdConfig());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int n = 0; n < 20; n++) {
                String config = "txn-race/conf/g" + n;
                String record = "HUGEGRAPH/hg/SCHEMA_SYNC/race/g" + n;
                CountDownLatch start = new CountDownLatch(1);
                List<Future<TxnResponse>> creates = new ArrayList<>();
                for (String server : new String[]{"A", "B"}) {
                    TxnRequest create = TxnRequest.newBuilder()
                            .addCompares(TxnCompare.newBuilder().setKey(config)
                                                   .setKind(TxnCompare.Kind.ABSENT))
                            .addOps(TxnOp.newBuilder().setKey(config).setValue(server))
                            .setRecord(TxnRecord.newBuilder().setKey(record)
                                                .setOp(TxnRecord.Op.CREATE))
                            .setRequestId(server + n).build();
                    creates.add(pool.submit(() -> {
                        start.await();
                        return service.txn(create);
                    }));
                }
                start.countDown();
                TxnResponse a = creates.get(0).get(10, TimeUnit.SECONDS);
                TxnResponse b = creates.get(1).get(10, TimeUnit.SECONDS);
                Assert.assertTrue(a.getSucceeded() ^ b.getSucceeded());
                TxnResponse lost = a.getSucceeded() ? b : a;
                Assert.assertNotEquals(TxnResponse.Failure.NONE, lost.getFailure());
                Assert.assertEquals(a.getSucceeded() ? "A" : "B", service.get(config));
                Assert.assertTrue(service.get(record).contains("\"inc\":1,\"state\":" +
                                                               "\"CREATING\""));
            }
        } finally {
            pool.shutdownNow();
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
