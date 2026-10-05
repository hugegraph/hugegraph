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

package org.apache.hugegraph.core;

import org.apache.hugegraph.schema.EdgeLabel;
import org.apache.hugegraph.schema.IndexLabel;
import org.apache.hugegraph.schema.PropertyKey;
import org.apache.hugegraph.schema.SchemaTemplateExecutor;
import org.apache.hugegraph.schema.VertexLabel;
import org.apache.hugegraph.testutil.Assert;
import org.apache.hugegraph.type.HugeType;
import org.apache.hugegraph.type.define.DataType;
import org.apache.hugegraph.type.define.Frequency;
import org.apache.hugegraph.type.define.IdStrategy;
import org.apache.hugegraph.type.define.IndexType;
import org.junit.Test;

public class SchemaTemplateCoreTest extends BaseCoreTest {

    @Test
    public void testParseWholeTemplateBeforeCreatingSchema() {
        String template = "schema.propertyKey('safe').asText().create();\n" +
                          "System.exit(0);";

        Assert.assertThrows(IllegalArgumentException.class,
                            () -> SchemaTemplateExecutor.execute(this.graph(),
                                                                 template));
        Assert.assertFalse(this.graph().existsPropertyKey("safe"));
    }

    @Test
    public void testPrepareSchemaRejectsWholeTemplateBeforeCreatingSchema() {
        String template = "schema.propertyKey('safe').asText().create();\n" +
                          "schema.propertyKey('other').remove();";

        Assert.assertThrows(IllegalArgumentException.class,
                            () -> GraphManager.prepareSchema(this.graph(),
                                                             template));
        Assert.assertFalse(this.graph().existsPropertyKey("safe"));
    }

    @Test
    public void testExecuteTypedSchemaTemplate() {
        String template = "schema.propertyKey('name').asText()" +
                          ".valueSingle()" +
                          ".userdata(['source': 'import', 'rank': 3])" +
                          ".create();" +
                          "schema.propertyKey('age')" +
                          ".dataType(DataType.INT).create();" +
                          "schema.vertexLabel('person').usePrimaryKeyId()" +
                          ".properties(['name', 'age']).primaryKeys('name')" +
                          ".create();" +
                          "schema.edgeLabel('knows')" +
                          ".link('person', 'person').properties('age')" +
                          ".sortKeys('age')" +
                          ".frequency(Frequency.MULTIPLE).create();" +
                          "schema.indexLabel('personByAge')" +
                          ".on(HugeType.VERTEX_LABEL, 'person').by('age')" +
                          ".indexType(IndexType.SECONDARY).create();";

        SchemaTemplateExecutor.execute(this.graph(), template);

        PropertyKey name = this.graph().propertyKey("name");
        PropertyKey age = this.graph().propertyKey("age");
        VertexLabel person = this.graph().vertexLabel("person");
        EdgeLabel knows = this.graph().edgeLabel("knows");
        IndexLabel personByAge = this.graph().indexLabel("personByAge");
        Assert.assertEquals(DataType.TEXT, name.dataType());
        Assert.assertEquals("import", name.userdata().get("source"));
        Assert.assertEquals(3L, name.userdata().get("rank"));
        Assert.assertEquals(DataType.INT, age.dataType());
        Assert.assertEquals(IdStrategy.PRIMARY_KEY, person.idStrategy());
        Assert.assertTrue(person.primaryKeys().contains(name.id()));
        Assert.assertEquals("person", knows.sourceLabelName());
        Assert.assertEquals("person", knows.targetLabelName());
        Assert.assertEquals(Frequency.MULTIPLE, knows.frequency());
        Assert.assertEquals(HugeType.VERTEX_LABEL, personByAge.baseType());
        Assert.assertEquals(IndexType.SECONDARY, personByAge.indexType());
        Assert.assertTrue(personByAge.indexFields().contains(age.id()));
    }

    @Test
    public void testExecuteIfNotExistTemplateTwice() {
        String template = "schema.propertyKey('name').asText()" +
                          ".ifNotExist().create();";

        SchemaTemplateExecutor.execute(this.graph(), template);
        SchemaTemplateExecutor.execute(this.graph(), template);

        Assert.assertEquals(DataType.TEXT,
                            this.graph().propertyKey("name").dataType());
    }
}
