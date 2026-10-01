/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hugegraph.compatibility;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.hugegraph.backend.BackendColumn;
import org.apache.hugegraph.backend.BinaryId;
import org.apache.hugegraph.backend.Shard;
import org.apache.hugegraph.id.EdgeId;
import org.apache.hugegraph.id.Id;
import org.apache.hugegraph.id.IdGenerator;
import org.apache.hugegraph.id.IdUtil;
import org.apache.hugegraph.id.SplicingIdGenerator;
import org.apache.hugegraph.query.Aggregate.AggregateFunc;
import org.apache.hugegraph.query.Condition;
import org.apache.hugegraph.query.ConditionQuery;
import org.apache.hugegraph.query.Query;
import org.apache.hugegraph.query.serializer.AbstractSerializerAdapter;
import org.apache.hugegraph.query.serializer.QueryAdapter;
import org.apache.hugegraph.query.serializer.QueryIdAdapter;
import org.apache.hugegraph.serializer.BytesBuffer;
import org.apache.hugegraph.struct.schema.EdgeLabel;
import org.apache.hugegraph.struct.schema.IndexLabel;
import org.apache.hugegraph.struct.schema.PropertyKey;
import org.apache.hugegraph.struct.schema.Userdata;
import org.apache.hugegraph.struct.schema.SchemaElement;
import org.apache.hugegraph.struct.schema.VertexLabel;
import org.apache.hugegraph.struct.schema.builder.SchemaBuilder;
import org.apache.hugegraph.structure.Index;
import org.apache.hugegraph.type.GraphType;
import org.apache.hugegraph.type.Indexfiable;
import org.apache.hugegraph.type.Namifiable;
import org.apache.hugegraph.type.Propfiable;
import org.apache.hugegraph.type.Typifiable;
import org.apache.hugegraph.type.define.DataType;
import org.apache.hugegraph.util.Bytes;
import org.apache.hugegraph.util.KryoUtil;
import org.apache.hugegraph.util.LegacyClassNames;
import org.apache.tinkerpop.shaded.kryo.KryoException;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/** Original core OBJECT payloads and original default-reader receipts. */
public class LegacyKryoCompatibilityTest {

    private Properties fixtures;

    @Before
    public void prepare() throws IOException {
        this.fixtures = new Properties();
        try (InputStream input = this.getClass().getResourceAsStream(
                                "/compatibility/2f827d6e8/core-kryo-objects.properties")) {
            Assert.assertNotNull(input);
            this.fixtures.load(input);
        }
    }

    @Test
    public void testReadableRelocatedEnumsAndClassLiteral() {
        this.assertValue("aggregate_func", AggregateFunc.AVG);
        this.assertValue("relation_type", Condition.RelationType.GTE);
        this.assertValue("condition_type", Condition.ConditionType.OR);
        this.assertValue("query_order", Query.Order.DESC);
        this.assertValue("query_order_type", Query.OrderType.ORDER_STRICT);
        this.assertValue("optimized_type", ConditionQuery.OptimizedType.INDEX_FILTER);
        this.assertValue("id_type", Id.IdType.UUID);
        this.assertValue("graph_type_class", GraphType.class);
    }

    @Test
    public void testReadableRelocatedUserdata() {
        Object decoded = this.read("userdata");
        Assert.assertEquals(Userdata.class, decoded.getClass());
        Assert.assertEquals(expectedUserdata(), decoded);
        Assert.assertEquals(decoded, roundTrip(decoded));
    }

    @Test
    public void testReadableRelocatedArrayTypes() {
        Object ids = this.read("id_array");
        Assert.assertEquals(Id[].class, ids.getClass());
        Assert.assertArrayEquals(new Id[]{null}, (Id[]) ids);
        Assert.assertArrayEquals((Id[]) ids, (Id[]) roundTrip(ids));
        Object matrix = this.read("id_matrix");
        Assert.assertEquals(Id[][].class, matrix.getClass());
        Assert.assertArrayEquals(new Id[][]{new Id[]{null}, new Id[0]}, (Id[][]) matrix);
        Assert.assertArrayEquals((Id[][]) matrix, (Id[][]) roundTrip(matrix));
        Object shards = this.read("shard_array");
        Assert.assertEquals(Shard[].class, shards.getClass());
        Assert.assertArrayEquals(new Shard[]{null}, (Shard[]) shards);
        Assert.assertArrayEquals((Shard[]) shards, (Shard[]) roundTrip(shards));
        Object schemas = this.read("schema_array");
        Assert.assertEquals(PropertyKey[].class, schemas.getClass());
        Assert.assertArrayEquals(new PropertyKey[]{null}, (PropertyKey[]) schemas);
        Assert.assertArrayEquals((PropertyKey[]) schemas, (PropertyKey[]) roundTrip(schemas));
    }

    @Test
    public void testReadableNestedClassAndArrayGraphs() {
        Object classes = this.read("class_list");
        Assert.assertEquals(expectedClasses(), classes);
        Assert.assertEquals(classes, roundTrip(classes));
        Object nested = this.read("nested");
        assertNested(nested);
        assertNested(roundTrip(nested));
    }

    @Test
    public void testReadableRenamedContractAndUtilityClassLiterals() {
        List<Class<?>> expected = Arrays.asList(Namifiable.class, Typifiable.class,
                                  Indexfiable.class, Propfiable.class, IdUtil.class,
                                  SplicingIdGenerator.class, SchemaBuilder.class,
                                  AbstractSerializerAdapter.class, QueryAdapter.class,
                                  QueryIdAdapter.class);
        Object decoded = this.read("contract_classes");
        Assert.assertEquals(expected, decoded);
        Assert.assertEquals(expected, roundTrip(decoded));
    }

    @Test
    public void testReadableLegacyBackendColumn() {
        this.assertValue("backend_column_class", BackendColumn.class);
        Object decoded = this.read("backend_column");
        assertColumn(decoded);
        assertColumn(roundTrip(decoded));
        Object array = this.read("backend_column_array");
        Assert.assertEquals(BackendColumn[].class, array.getClass());
        Assert.assertEquals(2, ((BackendColumn[]) array).length);
        assertColumn(((BackendColumn[]) array)[0]);
        Assert.assertNull(((BackendColumn[]) array)[1]);
        assertColumn(((BackendColumn[]) roundTrip(array))[0]);
    }

    @Test
    public void testReadableNestedSchemaClassLiteralsAndArrays() {
        List<Class<?>> expected = Arrays.asList(SchemaElement.TaskWithSchema.class,
                                  nestedClass(PropertyKey.class, "P"), nestedClass(VertexLabel.class, "P"),
                                  nestedClass(EdgeLabel.class, "P"), nestedClass(IndexLabel.class, "P"),
                                  SchemaElement.P.class, PropertyKey.Builder.class, VertexLabel.Builder.class,
                                  EdgeLabel.Builder.class, IndexLabel.Builder.class);
        Object classes = this.read("schema_nested_classes");
        Assert.assertEquals(expected, classes);
        Assert.assertEquals(expected, roundTrip(classes));
        List<?> arrays = (List<?>) this.read("schema_nested_arrays");
        List<?> written = (List<?>) roundTrip(arrays);
        for (int i = 0; i < expected.size(); i++) {
            Class<?> arrayClass = Array.newInstance(expected.get(i), 1).getClass();
            Assert.assertEquals(arrayClass, arrays.get(i).getClass());
            Assert.assertEquals(arrayClass, written.get(i).getClass());
            Assert.assertEquals(1, Array.getLength(arrays.get(i)));
            Assert.assertNull(Array.get(arrays.get(i), 0));
        }
    }

    @Test
    public void testReadableRelocatedIndexClassLiteralsAndArrays() {
        List<Class<?>> expected = Arrays.asList(Index.class, Index.IdWithExpiredTime.class);
        Object classes = this.read("index_classes");
        Assert.assertEquals(expected, classes);
        Assert.assertEquals(expected, roundTrip(classes));
        Object indexes = this.read("index_array");
        Assert.assertEquals(Index[].class, indexes.getClass());
        Assert.assertArrayEquals(new Index[]{null}, (Index[]) indexes);
        Assert.assertArrayEquals((Index[]) indexes, (Index[]) roundTrip(indexes));
        Object expires = this.read("expiry_id_array");
        Assert.assertEquals(Index.IdWithExpiredTime[].class, expires.getClass());
        Assert.assertArrayEquals(new Index.IdWithExpiredTime[]{null}, (Index.IdWithExpiredTime[]) expires);
        Assert.assertArrayEquals((Index.IdWithExpiredTime[]) expires,
                                 (Index.IdWithExpiredTime[]) roundTrip(expires));
    }

    @Test
    public void testLegacyArrayDescriptorDimensionBoundary() {
        String legacy = "org.apache.hugegraph.backend.id.Id";
        Assert.assertEquals(Id.class, LegacyClassNames.lookup(legacy));
        Assert.assertEquals(Id[].class, LegacyClassNames.lookup("[L" + legacy + ";"));
        Class<?> limit = LegacyClassNames.lookup("[".repeat(255) + "L" + legacy + ";");
        int dimensions = 0;
        while (limit.isArray()) {
            dimensions++;
            limit = limit.getComponentType();
        }
        Assert.assertEquals(255, dimensions);
        Assert.assertEquals(Id.class, limit);
        Assert.assertNull(LegacyClassNames.lookup("[".repeat(256) + "L" + legacy + ";"));
        Assert.assertNull(LegacyClassNames.lookup("[".repeat(5000) + "L" + legacy + ";"));
        Assert.assertNull(LegacyClassNames.lookup("[Lexample.UserClass;"));
        Assert.assertNull(LegacyClassNames.lookup("[I"));
    }

    @Test
    public void testOriginalUnsupportedInstancesKeepConstructorPolicy() {
        for (String name : new String[]{"long_id", "string_id", "uuid_id", "edge_id", "binary_id",
                                       "shard", "property_key", "vertex_label", "edge_label",
                                       "index_label", "expiry_id", "unsupported_arrays_list"}) {
            Assert.assertEquals(name, "false", this.fixtures.getProperty(name + ".baseline_readable"));
            Assert.assertTrue(this.fixtures.getProperty(name + ".baseline_error")
                                          .contains("missing no-arg constructor"));
            // Reset failure state before and after a read, keeping independent
            // fixtures independent without changing the instantiation strategy.
            KryoUtil.kryo().reset();
            try {
                KryoException error = Assert.assertThrows(KryoException.class,
                                      () -> this.decode(name));
                Assert.assertTrue(name + ": " + error.getMessage(),
                                  error.getMessage().contains("missing no-arg constructor"));
            } finally {
                KryoUtil.kryo().reset();
            }
        }
    }

    private void assertValue(String name, Object expected) {
        Object decoded = this.read(name);
        Assert.assertEquals(expected, decoded);
        // Java API names may change. New writes need semantic compatibility,
        // rather than continuing to emit removed core class names.
        Assert.assertEquals(expected, roundTrip(decoded));
    }

    private Object read(String name) {
        Assert.assertEquals(name, "true", this.fixtures.getProperty(name + ".baseline_readable"));
        return this.decode(name);
    }

    private Object decode(String name) {
        BytesBuffer reader = BytesBuffer.wrap(Bytes.fromHex(this.fixtures.getProperty(name + ".hex")));
        Object decoded = reader.readProperty(DataType.OBJECT);
        Assert.assertEquals(0, reader.remaining());
        return decoded;
    }

    private static Object roundTrip(Object value) {
        BytesBuffer writer = BytesBuffer.allocate(128);
        writer.writeProperty(DataType.OBJECT, value);
        return BytesBuffer.wrap(writer.bytes()).readProperty(DataType.OBJECT);
    }

    private static Userdata expectedUserdata() {
        Userdata userdata = new Userdata();
        userdata.put("tag", "legacy");
        userdata.put("count", 7);
        userdata.put(Userdata.CREATE_TIME, new Date(1609459200123L));
        return userdata;
    }

    private static List<Class<?>> expectedClasses() {
        return new ArrayList<>(Arrays.asList(IdGenerator.LongId.class,
                               IdGenerator.StringId.class, IdGenerator.UuidId.class,
                               EdgeId.class, BinaryId.class, Shard.class, PropertyKey.class,
                               VertexLabel.class, EdgeLabel.class, IndexLabel.class));
    }

    private static Class<?> nestedClass(Class<?> owner, String name) {
        return Arrays.stream(owner.getDeclaredClasses())
                     .filter(type -> type.getSimpleName().equals(name))
                     .findFirst().orElseThrow(AssertionError::new);
    }

    private static void assertColumn(Object value) {
        Assert.assertEquals(BackendColumn.class, value.getClass());
        BackendColumn column = (BackendColumn) value;
        Assert.assertArrayEquals(new byte[]{1, 2}, column.name);
        Assert.assertArrayEquals(new byte[]{3, 4}, column.value);
    }

    private static void assertNested(Object value) {
        Assert.assertTrue(value instanceof Map);
        Map<?, ?> nested = (Map<?, ?>) value;
        Assert.assertEquals(expectedClasses(), nested.get("classes"));
        Assert.assertEquals(expectedUserdata(), nested.get("metadata"));
        Assert.assertEquals(Id[][].class, nested.get("ids").getClass());
        Assert.assertArrayEquals(new Id[][]{new Id[]{null}, new Id[0]}, (Id[][]) nested.get("ids"));
        Assert.assertEquals(Condition.RelationType.GTE, nested.get("predicate"));
        assertColumn(nested.get("column"));
    }
}
