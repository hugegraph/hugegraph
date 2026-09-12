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

package org.apache.hugegraph.store.core;

import org.apache.hugegraph.backend.BackendColumn;
import org.apache.hugegraph.id.IdGenerator;
import org.apache.hugegraph.rocksdb.access.RocksDBSession;
import org.apache.hugegraph.rocksdb.access.ScanIterator;
import org.apache.hugegraph.security.script.PolicyScriptEngines;
import org.apache.hugegraph.security.script.ScriptPolicyRuntime;
import org.apache.hugegraph.store.business.GraphStoreIterator;
import org.apache.hugegraph.store.grpc.Graphpb;
import org.apache.hugegraph.struct.schema.VertexLabel;
import org.apache.hugegraph.structure.BaseElement;
import org.apache.hugegraph.structure.BaseVertex;
import org.junit.Assert;

public final class StorePolicyModeProbe {

    public static void main(String[] args) {
        try {
            Assert.assertTrue(ScriptPolicyRuntime.enabled());
            Assert.assertNull(System.getSecurityManager());
            FakeRows rows = new FakeRows();
            Assert.assertThrows(IllegalArgumentException.class,
                    () -> new GraphStoreIterator<>(rows, request("System.getProperty('java.version')")));
            Assert.assertEquals(0, rows.read);
            Assert.assertTrue(rows.closed);

            for (String condition : new String[]{"true", "false", "element.id().equals('v1')"}) {
                FakeRows input = new FakeRows();
                try (GraphStoreIterator<?> iterator = iterator(input, condition)) {
                    Assert.assertEquals(!condition.equals("false"), iterator.hasNext());
                    Assert.assertEquals(1, input.read);
                }
                Assert.assertTrue(input.closed);
            }
            for (String condition : new String[]{"1", "(int) element.property('missing') > 0"}) {
                FakeRows input = new FakeRows();
                GraphStoreIterator<?> iterator = iterator(input, condition);
                Assert.assertThrows(IllegalStateException.class, iterator::hasNext);
                Assert.assertNotNull(iterator.getStopCause());
                Assert.assertTrue(input.closed);
            }
            System.out.println("STORE_POLICY_VERIFIED " + ScriptPolicyRuntime.mode().configValue());
        } finally {
            PolicyScriptEngines.close();
        }
    }

    private static GraphStoreIterator<?> iterator(FakeRows rows, String source) {
        return new GraphStoreIterator<>(rows, request(source)) {
            @Override
            public BaseElement parseEntry(BackendColumn column, boolean vertex) {
                return new BaseVertex(IdGenerator.of("v1"),
                        new VertexLabel(null, IdGenerator.of(1), "person"));
            }
        };
    }

    private static Graphpb.ScanPartitionRequest request(String source) {
        return Graphpb.ScanPartitionRequest.newBuilder().setScanRequest(
                Graphpb.ScanPartitionRequest.Request.newBuilder().setCondition(source)
                        .setScanType(Graphpb.ScanPartitionRequest.ScanType.SCAN_VERTEX)).build();
    }

    private static final class FakeRows implements ScanIterator {
        int read;
        boolean closed;

        @Override
        public boolean hasNext() {
            return read == 0;
        }

        @Override
        public boolean isValid() {
            return !closed;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T next() {
            read++;
            return (T) RocksDBSession.BackendColumn.of(new byte[]{1}, new byte[]{1});
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
