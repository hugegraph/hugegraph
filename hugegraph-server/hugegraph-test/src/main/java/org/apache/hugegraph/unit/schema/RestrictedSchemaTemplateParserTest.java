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

package org.apache.hugegraph.unit.schema;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.apache.hugegraph.schema.RestrictedSchemaTemplateParser;
import org.apache.hugegraph.schema.SchemaTemplatePlan;
import org.apache.hugegraph.schema.SchemaTemplatePlan.SchemaCommand;
import org.apache.hugegraph.schema.SchemaTemplatePlan.SchemaKind;
import org.apache.hugegraph.testutil.Assert;
import org.apache.hugegraph.unit.BaseUnitTest;
import org.junit.Test;

public class RestrictedSchemaTemplateParserTest extends BaseUnitTest {

    @Test
    public void testParseAllSchemaKinds() {
        String template = "schema = graph.schema();\n" +
                          "schema.propertyKey('name').asText()" +
                          ".valueSingle().ifNotExist().create();\n" +
                          "schema.vertexLabel(\"person\")" +
                          ".properties('name').primaryKeys('name')" +
                          ".ifNotExist().create();\n" +
                          "schema.edgeLabel('knows')" +
                          ".link('person', 'person').multiTimes()" +
                          ".ifNotExist().create();\n" +
                          "schema.indexLabel('personByName').onV('person')" +
                          ".by('name').secondary().ifNotExist().create();";

        SchemaTemplatePlan plan = RestrictedSchemaTemplateParser.parse(template);

        Assert.assertEquals(4, plan.commands().size());
        assertCommand(plan.commands().get(0), SchemaKind.PROPERTY_KEY, "name",
                      "asText", "valueSingle", "ifNotExist", "create");
        assertCommand(plan.commands().get(1), SchemaKind.VERTEX_LABEL, "person",
                      "properties", "primaryKeys", "ifNotExist", "create");
        assertCommand(plan.commands().get(2), SchemaKind.EDGE_LABEL, "knows",
                      "link", "multiTimes", "ifNotExist", "create");
        assertCommand(plan.commands().get(3), SchemaKind.INDEX_LABEL,
                      "personByName", "onV", "by", "secondary",
                      "ifNotExist", "create");
    }

    @Test
    public void testParseCommentsEscapesAndLiterals() {
        String template = "// schema for imported data\n" +
                          "graph.schema().propertyKey('person\\'s score')" +
                          ".asDouble().userdata(\"source\", \"a\\\"b\")" +
                          ".ifNotExist().create(); /* kept on import */\n" +
                          "schema.vertexLabel('person').ttl(3600)" +
                          ".enableLabelIndex(false).ifNotExist().create()";

        SchemaTemplatePlan plan = RestrictedSchemaTemplateParser.parse(template);

        Assert.assertEquals(2, plan.commands().size());
        Assert.assertEquals("person's score", plan.commands().get(0).name());
        Assert.assertEquals("a\"b", plan.commands().get(0).operations()
                                                   .get(1).arguments().get(1));
        Assert.assertEquals(3600L, plan.commands().get(1).operations()
                                                  .get(0).arguments().get(0));
        Assert.assertEquals(false, plan.commands().get(1).operations()
                                                   .get(1).arguments().get(0));
    }

    @Test
    public void testParseLongLiteralSuffix() {
        String template = "schema.propertyKey('code').id(100L)" +
                          ".asText().userdata(['rank': -3l]).create();" +
                          "schema.vertexLabel('person').ttl(86400L).create();";

        SchemaTemplatePlan plan = RestrictedSchemaTemplateParser.parse(template);

        Assert.assertEquals(100L, plan.commands().get(0).operations()
                                           .get(0).arguments().get(0));
        Object userdata = plan.commands().get(0).operations().get(2)
                              .arguments().get(0);
        Assert.assertEquals(-3L, ((Map<?, ?>) userdata).get("rank"));
        Assert.assertEquals(86400L, plan.commands().get(1).operations()
                                             .get(0).arguments().get(0));
    }

    @Test
    public void testRejectLongSuffixOnFloatingPointLiteral() {
        List<String> templates = Arrays.asList(
                "schema.vertexLabel('person').ttl(1.0L).create();",
                "schema.vertexLabel('person').ttl(1e3l).create();"
        );

        for (String template : templates) {
            Assert.assertThrows(
                    IllegalArgumentException.class,
                    () -> RestrictedSchemaTemplateParser.parse(template),
                    e -> Assert.assertContains("Long suffix", e.getMessage()));
        }
    }

    @Test
    public void testAllowSchemaAssignmentWithoutSemicolon() {
        String template = "schema = graph.schema()\n" +
                          "schema.propertyKey('name').asText().create()";

        SchemaTemplatePlan plan = RestrictedSchemaTemplateParser.parse(template);

        Assert.assertEquals(1, plan.commands().size());
        Assert.assertEquals("name", plan.commands().get(0).name());
    }

    @Test
    public void testParseListAndMapLiterals() {
        String template = "schema.propertyKey('name').asText()" +
                          ".userdata(['source': 'import', 'rank': 3])" +
                          ".create();" +
                          "schema.vertexLabel('person')" +
                          ".properties(['name']).create();";

        SchemaTemplatePlan plan = RestrictedSchemaTemplateParser.parse(template);

        Object userdata = plan.commands().get(0).operations().get(1)
                              .arguments().get(0);
        Assert.assertTrue(userdata instanceof Map);
        Assert.assertEquals("import", ((Map<?, ?>) userdata).get("source"));
        Assert.assertEquals(3L, ((Map<?, ?>) userdata).get("rank"));
        Object properties = plan.commands().get(1).operations().get(0)
                                .arguments().get(0);
        Assert.assertEquals(List.of("name"), properties);
    }

    @Test
    public void testRejectUnsafeOrDynamicSyntax() {
        List<String> templates = Arrays.asList(
                "System.exit(0)",
                "schema.getClass().forName('java.lang.Runtime')",
                "new File('/tmp/x')",
                "schema.propertyKey(name).asText().create()",
                "schema.propertyKey('name').remove()",
                "schema.propertyKey('name').create(); Runtime.runtime.exec('id')",
                "schema.propertyKey('a').create()" +
                "schema.propertyKey('b').create()",
                "schema=graph.schema()" +
                "schema.propertyKey('name').create()",
                "for (i in 1..10) { schema.propertyKey('p' + i).create() }"
        );

        for (String template : templates) {
            Assert.assertThrows(IllegalArgumentException.class,
                                () -> RestrictedSchemaTemplateParser.parse(template),
                                e -> {
                                    Assert.assertContains("line 1", e.getMessage());
                                    Assert.assertContains("column", e.getMessage());
                                });
        }
    }

    @Test
    public void testRejectTrailingUnsafeStatementBeforeAnyExecution() {
        String template = "schema.propertyKey('safe').asText().create();\n" +
                          "Runtime.runtime.exec('id');";

        Assert.assertThrows(IllegalArgumentException.class,
                            () -> RestrictedSchemaTemplateParser.validate(template),
                            e -> {
                                Assert.assertContains("line 2", e.getMessage());
                                Assert.assertContains("Runtime", e.getMessage());
                            });
    }

    @Test
    public void testRejectExcessiveLiteralNesting() {
        String nested = "[".repeat(33) + "1" + "]".repeat(33);
        String template = "schema.propertyKey('name')" +
                          ".userdata('nested', " + nested + ").create()";

        Assert.assertThrows(IllegalArgumentException.class,
                            () -> RestrictedSchemaTemplateParser.parse(template),
                            e -> Assert.assertContains("nesting",
                                                       e.getMessage()));
    }

    private static void assertCommand(SchemaCommand command, SchemaKind kind,
                                      String name, String... methods) {
        Assert.assertEquals(kind, command.kind());
        Assert.assertEquals(name, command.name());
        Assert.assertEquals(methods.length, command.operations().size());
        for (int i = 0; i < methods.length; i++) {
            Assert.assertEquals(methods[i], command.operations().get(i)
                                                         .method().dslName());
        }
    }
}
