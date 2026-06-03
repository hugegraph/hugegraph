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

package org.apache.hugegraph.unit.serializer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.apache.hugegraph.backend.id.IdGenerator;
import org.apache.hugegraph.io.HugeGraphIoRegistry;
import org.apache.hugegraph.schema.PropertyKey;
import org.apache.hugegraph.testutil.Assert;
import org.apache.hugegraph.unit.BaseUnitTest;
import org.apache.hugegraph.unit.FakeObjects;
import org.apache.tinkerpop.gremlin.structure.io.graphson.GraphSONMapper;
import org.apache.tinkerpop.gremlin.structure.io.graphson.GraphSONVersion;
import org.apache.tinkerpop.gremlin.structure.io.graphson.GraphSONWriter;
import org.apache.tinkerpop.gremlin.structure.io.graphson.TypeInfo;
import org.junit.Test;

public class HugeGraphSONModuleTest extends BaseUnitTest {

    @Test
    public void testSerializeFileWithGraphSONTypeInfo() throws IOException {
        String json = writeTyped(new File("test.text"));

        Assert.assertContains("hugegraph:File", json);
        Assert.assertContains("\"file\"", json);
        Assert.assertContains("test.text", json);
    }

    @Test
    public void testSerializeIdWithGraphSONTypeInfo() throws IOException {
        String stringId = writeTyped(IdGenerator.of("marko"));
        String longId = writeTyped(IdGenerator.of(123L));

        Assert.assertContains("hugegraph:StringId", stringId);
        Assert.assertContains("marko", stringId);
        Assert.assertContains("hugegraph:LongId", longId);
        Assert.assertContains("123", longId);
    }

    @Test
    public void testSerializeSchemaWithGraphSONModule() throws IOException {
        FakeObjects objects = new FakeObjects();
        PropertyKey propertyKey = objects.newPropertyKey(IdGenerator.of(1L),
                                                         "name");

        String json = writeUntyped(propertyKey);

        Assert.assertContains("\"name\"", json);
    }

    private static String writeTyped(Object object) throws IOException {
        GraphSONMapper mapper = mapper(TypeInfo.PARTIAL_TYPES);
        GraphSONWriter writer = GraphSONWriter.build().mapper(mapper).create();
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        writer.writeObject(output, object);

        return output.toString(StandardCharsets.UTF_8.name());
    }

    private static String writeUntyped(Object object) throws IOException {
        GraphSONMapper mapper = mapper(TypeInfo.NO_TYPES);
        GraphSONWriter writer = GraphSONWriter.build().mapper(mapper).create();
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        writer.writeObject(output, object);

        return output.toString(StandardCharsets.UTF_8.name());
    }

    private static GraphSONMapper mapper(TypeInfo typeInfo) {
        GraphSONMapper mapper = GraphSONMapper.build()
                                              .version(GraphSONVersion.V3_0)
                                              .typeInfo(typeInfo)
                                              .addRegistry(HugeGraphIoRegistry.instance())
                                              .create();

        return mapper;
    }
}
