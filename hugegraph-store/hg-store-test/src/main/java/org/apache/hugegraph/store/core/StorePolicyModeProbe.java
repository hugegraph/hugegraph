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

import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;

import org.apache.hugegraph.HugeGraphSupplier;
import org.apache.hugegraph.backend.BackendColumn;
import org.apache.hugegraph.id.IdGenerator;
import org.apache.hugegraph.rocksdb.access.RocksDBFactory;
import org.apache.hugegraph.rocksdb.access.RocksDBSession;
import org.apache.hugegraph.rocksdb.access.ScanIterator;
import org.apache.hugegraph.security.script.PolicyScriptEngines;
import org.apache.hugegraph.security.script.ScriptPolicyRuntime;
import org.apache.hugegraph.serializer.BinaryElementSerializer;
import org.apache.hugegraph.store.business.BusinessHandlerImpl;
import org.apache.hugegraph.store.business.GraphStoreIterator;
import org.apache.hugegraph.store.grpc.Graphpb;
import org.apache.hugegraph.struct.schema.EdgeLabel;
import org.apache.hugegraph.struct.schema.PropertyKey;
import org.apache.hugegraph.struct.schema.VertexLabel;
import org.apache.hugegraph.structure.BaseEdge;
import org.apache.hugegraph.structure.BaseElement;
import org.apache.hugegraph.structure.BaseVertex;
import org.apache.hugegraph.testutil.Whitebox;
import org.apache.hugegraph.type.define.Cardinality;
import org.apache.hugegraph.type.define.DataType;
import org.apache.hugegraph.util.Blob;
import org.junit.Assert;
import org.mockito.Mockito;

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
            verifyRealBinaryProperties();
            verifyRealBinaryCollectionProperties();
            System.out.println("STORE_POLICY_VERIFIED " + ScriptPolicyRuntime.mode().configValue());
        } finally {
            BusinessHandlerImpl.setMockGraphSupplier(null);
            // Loading BusinessHandlerImpl starts RocksDBFactory's process-owned watcher.
            ScheduledExecutorService watcher = Whitebox.getInternalState(
                    RocksDBFactory.getInstance(), "scheduledExecutor");
            watcher.shutdownNow();
            PolicyScriptEngines.close();
        }
    }

    private static void verifyRealBinaryCollectionProperties() {
        for (Cardinality cardinality : new Cardinality[]{Cardinality.LIST, Cardinality.SET}) {
            for (DataType dataType : new DataType[]{DataType.DATE, DataType.BLOB}) {
                PropertyKey name = new PropertyKey(null, IdGenerator.of(1L), "name");
                name.dataType(DataType.TEXT);
                PropertyKey values = new PropertyKey(null, IdGenerator.of(2L), "values");
                values.dataType(dataType);
                values.cardinality(cardinality);
                VertexLabel person = new VertexLabel(null, IdGenerator.of(10L), "person");
                BaseVertex vertex = new BaseVertex(IdGenerator.of("v1"), person);
                vertex.addProperty(name, "alice");
                Object first = dataType == DataType.DATE ? new Date(1000L) :
                               Blob.wrap(new byte[]{1, 2});
                Object second = dataType == DataType.DATE ? new Date(2000L) :
                                Blob.wrap(new byte[]{3, 4});
                vertex.addProperty(values, List.of(first, first, second));
                HugeGraphSupplier graph = Mockito.mock(HugeGraphSupplier.class);
                Mockito.when(graph.vertexLabelOrNone(person.id())).thenReturn(person);
                Mockito.when(graph.propertyKey(name.id())).thenReturn(name);
                Mockito.when(graph.propertyKey(values.id())).thenReturn(values);
                BusinessHandlerImpl.setMockGraphSupplier(graph);
                BinaryElementSerializer serializer = new BinaryElementSerializer();
                BackendColumn column = serializer.writeVertex(vertex);
                BaseElement parsed = serializer.parseVertex(graph, column, null);
                Collection<?> decoded = (Collection<?>) parsed.getProperty(values.id()).value();
                Assert.assertEquals(cardinality == Cardinality.SET, decoded instanceof Set);
                int count = cardinality == Cardinality.LIST ? 3 : 2;
                Assert.assertEquals(count, decoded.size());
                Assert.assertTrue(decoded.contains(first));
                Assert.assertTrue(decoded.contains(second));

                String member = dataType == DataType.DATE ? "1000L" :
                                "[Byte.valueOf('1'), Byte.valueOf('2')]";
                String valueCondition = "((List) element.property('values')).size() == " + count +
                                        " && ((List) element.property('values')).contains(" + member + ")";
                if (cardinality == Cardinality.LIST) {
                    valueCondition += " && ((List) element.property('values')).indexOf(" + member + ") == 0" +
                                      " && ((List) element.property('values')).lastIndexOf(" + member + ") == 1";
                }
                for (String condition : new String[]{"false", "element.property('name').equals('alice')",
                                                     valueCondition}) {
                    String scenario = cardinality + "/" + dataType + ": " + condition;
                    FakeRows rows = new FakeRows(column.name, column.value);
                    Graphpb.ScanPartitionRequest scan = request(condition);
                    // The existing protobuf encoder only supports scalar property values.
                    scan = scan.toBuilder().setScanRequest(scan.getScanRequest().toBuilder()
                            .addProperties(name.id().asLong())).build();
                    try (GraphStoreIterator<?> iterator = new GraphStoreIterator<>(rows, scan)) {
                        Assert.assertEquals(scenario, !condition.equals("false"), iterator.hasNext());
                        if (!condition.equals("false")) {
                            Graphpb.Vertex result = (Graphpb.Vertex) iterator.next();
                            Assert.assertEquals(scenario, 1, result.getPropertiesCount());
                            Assert.assertEquals(scenario, "alice", result.getProperties(0).getValue().getValueString());
                        }
                        Assert.assertNull(scenario, iterator.getStopCause());
                        Assert.assertFalse(scenario, iterator.hasNext());
                    } catch (RuntimeException | AssertionError error) {
                        throw new AssertionError(scenario, error);
                    }
                    Assert.assertEquals(scenario, 1, rows.read);
                    Assert.assertTrue(scenario, rows.closed);
                }
            }
        }
    }

    private static void verifyRealBinaryProperties() {
        PropertyKey name = new PropertyKey(null, IdGenerator.of(1L), "name");
        name.dataType(DataType.TEXT);
        PropertyKey age = new PropertyKey(null, IdGenerator.of(2L), "age");
        age.dataType(DataType.INT);
        VertexLabel person = new VertexLabel(null, IdGenerator.of(10L), "person");
        BaseVertex vertex = new BaseVertex(IdGenerator.of("v1"), person);
        vertex.addProperty(name, "alice");
        vertex.addProperty(age, 21);
        HugeGraphSupplier graph = Mockito.mock(HugeGraphSupplier.class);
        Mockito.when(graph.vertexLabelOrNone(person.id())).thenReturn(person);
        Mockito.when(graph.propertyKey(name.id())).thenReturn(name);
        Mockito.when(graph.propertyKey(age.id())).thenReturn(age);
        BusinessHandlerImpl.setMockGraphSupplier(graph);
        BinaryElementSerializer serializer = new BinaryElementSerializer();
        BackendColumn column = serializer.writeVertex(vertex);
        BaseElement parsed = serializer.parseVertex(null, column, null);
        Assert.assertEquals("~undefined", parsed.schemaLabel().name());
        Assert.assertEquals("10", parsed.schemaLabel().id().asString());
        Assert.assertEquals("alice", parsed.getProperty(IdGenerator.of(1L)).value());
        Assert.assertEquals(21, parsed.getProperty(IdGenerator.of(2L)).value());

        FakeRows matched = new FakeRows(column.name, column.value);
        try (GraphStoreIterator<?> iterator = new GraphStoreIterator<>(matched, request(
                "element.label().equals('person') && element.property('name').equals('alice') && " +
                "(int) element.property('age') > 18"))) {
            Assert.assertTrue(iterator.hasNext());
        }
        Assert.assertTrue(matched.closed);

        for (String condition : new String[]{"element.property('missing') == null",
                "!element.properties().containsKey('missing')",
                "element.property('age') != null && (int) element.property('age') > 18"}) {
            FakeRows input = new FakeRows(column.name, column.value);
            try (GraphStoreIterator<?> iterator = new GraphStoreIterator<>(input, request(condition))) {
                Assert.assertTrue(iterator.hasNext());
                Assert.assertNull(iterator.getStopCause());
            }
            Assert.assertTrue(input.closed);
        }
        FakeRows absent = new FakeRows(column.name, column.value);
        try (GraphStoreIterator<?> iterator = new GraphStoreIterator<>(absent, request(
                "element.property('missing') != null"))) {
            Assert.assertFalse(iterator.hasNext());
            Assert.assertNull(iterator.getStopCause());
        }
        EdgeLabel knows = new EdgeLabel(null, IdGenerator.of(20L), "knows");
        knows.sourceLabel(person.id());
        knows.targetLabel(person.id());
        Mockito.when(graph.edgeLabelOrNone(knows.id())).thenReturn(knows);
        BaseEdge edge = BaseEdge.constructEdge(graph, vertex, true, knows, "", IdGenerator.of("v2"));
        edge.addProperty(age, 21);
        BackendColumn edgeColumn = serializer.writeEdge(edge);
        FakeRows edgeRows = new FakeRows(edgeColumn.name, edgeColumn.value);
        Graphpb.ScanPartitionRequest edgeRequest = request(
                "element.label().equals('knows') && (int) element.property('age') > 18 && " +
                "element.property('name') == null");
        edgeRequest = edgeRequest.toBuilder().setScanRequest(edgeRequest.getScanRequest().toBuilder()
                .setScanType(Graphpb.ScanPartitionRequest.ScanType.SCAN_EDGE)).build();
        try (GraphStoreIterator<?> iterator = new GraphStoreIterator<>(edgeRows, edgeRequest)) {
            Assert.assertTrue(iterator.hasNext());
            Assert.assertTrue(iterator.next() instanceof Graphpb.Edge);
        }
        Assert.assertTrue(edgeRows.closed);
        Mockito.when(graph.propertyKey(age.id())).thenThrow(new IllegalStateException("schema unavailable"));
        FakeRows broken = new FakeRows(column.name, column.value);
        try (GraphStoreIterator<?> iterator = new GraphStoreIterator<>(broken, request("true"))) {
            Assert.assertThrows(IllegalStateException.class, iterator::hasNext);
            Assert.assertNotNull(iterator.getStopCause());
            Assert.assertTrue(broken.closed);
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
        final byte[] name;
        final byte[] value;

        FakeRows() {
            this(new byte[]{1}, new byte[]{1});
        }

        FakeRows(byte[] name, byte[] value) {
            this.name = name;
            this.value = value;
        }

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
            return (T) RocksDBSession.BackendColumn.of(name, value);
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
