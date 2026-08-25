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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.script.Bindings;
import javax.script.SimpleBindings;

import org.apache.hugegraph.schema.SchemaManager;
import org.apache.hugegraph.security.HugeGraphGremlinLangScriptEngine;
import org.apache.hugegraph.security.HugeGraphGremlinLangScriptEngineFactory;
import org.apache.hugegraph.testutil.Assert;
import org.apache.tinkerpop.gremlin.jsr223.Customizer;
import org.apache.tinkerpop.gremlin.jsr223.GremlinLangPlugin;
import org.apache.tinkerpop.gremlin.jsr223.VariableResolverPlugin;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.structure.T;
import org.junit.Test;

public class GremlinLangTextContainsCoreTest extends BaseCoreTest {

    @Test
    public void testTextContainsUsesHugeGraphSearchIndexSemantics()
            throws Exception {
        SchemaManager schema = graph().schema();
        schema.propertyKey("name").asText().create();
        schema.propertyKey("description").asText().create();
        schema.vertexLabel("dog")
              .properties("name", "description")
              .primaryKeys("name")
              .create();
        schema.indexLabel("dogByDescription").onV("dog")
              .search().by("description").create();

        graph().addVertex(T.label, "dog", "name", "Bella",
                          "description", "black hair and eyes");
        graph().addVertex(T.label, "dog", "name", "Daisy",
                          "description", "yellow hair yellow tail");
        graph().addVertex(T.label, "dog", "name", "Coco",
                          "description", "yellow hair golden tail");
        this.commitTx();

        try (GraphTraversalSource g = graph().traversal()) {
            HugeGraphGremlinLangScriptEngine engine = engine(g);
            Bindings bindings = new SimpleBindings();
            bindings.put("g", g);
            try {
                bindings.put("keyword", "yellow hair");
                Assert.assertEquals(3L, engine.eval(
                        "g.V().has('description', " +
                        "Text.contains(keyword)).count().next()",
                        bindings));

                bindings.put("keyword", "black golden");
                Assert.assertEquals(2L, engine.eval(
                        "g.V().has('description', " +
                        "Text.contains(keyword)).count().next()",
                        bindings));

                bindings.put("keyword", "(hair)");
                Assert.assertEquals(3L, engine.eval(
                        "g.V().has('description', " +
                        "Text.contains(keyword)).count().next()",
                        bindings));

                bindings.put("keyword", "(black|golden)");
                Assert.assertEquals(2L, engine.eval(
                        "g.V().has('description', " +
                        "Text.contains(keyword)).count().next()",
                        bindings));
            } finally {
                engine.clear();
            }
        }
    }

    private static HugeGraphGremlinLangScriptEngine engine(
            GraphTraversalSource g) {
        List<Customizer> customizers = new ArrayList<>();
        GremlinLangPlugin cache = GremlinLangPlugin.build()
                                                   .cacheEnabled(true)
                                                   .caffeine(
                                                           "maximumSize=16")
                                                   .create();
        VariableResolverPlugin variables =
                VariableResolverPlugin.build()
                                      .resolver("DefaultVariableResolver")
                                      .create();
        customizers.addAll(Arrays.asList(
                cache.getCustomizers("gremlin-lang").get()));
        customizers.addAll(Arrays.asList(
                variables.getCustomizers("gremlin-lang").get()));
        HugeGraphGremlinLangScriptEngineFactory factory =
                new HugeGraphGremlinLangScriptEngineFactory(
                        customizers.toArray(new Customizer[0]));
        HugeGraphGremlinLangScriptEngine engine = factory.getScriptEngine();
        engine.add(g);
        return engine;
    }
}
