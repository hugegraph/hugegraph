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

package org.apache.hugegraph.unit.traversal.optimize;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.script.Bindings;

import org.apache.hugegraph.security.HugeGraphGremlinLangScriptEngine;
import org.apache.hugegraph.security.HugeGraphGremlinLangScriptEngineFactory;
import org.apache.hugegraph.testutil.Assert;
import org.apache.hugegraph.traversal.optimize.HugeScriptTraversal;
import org.apache.hugegraph.unit.BaseUnitTest;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.optimization.LazyBarrierStrategy;
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph;
import org.junit.Test;

public class HugeScriptTraversalTest extends BaseUnitTest {

    @Test
    public void testExecuteGremlinLangWithBindings() throws Exception {
        try (TinkerGraph graph = TinkerGraph.open();
             GraphTraversalSource g = graph.traversal()) {
            graph.addVertex("name", "marko");
            Map<String, Object> bindings = Collections.singletonMap("person",
                                                                     "marko");
            HugeScriptTraversal<Object, Object> traversal =
                    new HugeScriptTraversal<>(
                            g, "g.V().has('name', person).values('name')",
                            bindings, Collections.emptyMap());

            Assert.assertEquals(List.of("marko"), traversal.toList());
            traversal.close();
        }
    }

    @Test
    public void testAllowRemovingOrdinaryTraversalStrategy() throws Exception {
        try (TinkerGraph graph = TinkerGraph.open();
             GraphTraversalSource g = graph.traversal()) {
            graph.addVertex();
            HugeScriptTraversal<Object, Object> traversal =
                    traversal(g,
                              "g.withoutStrategies(LazyBarrierStrategy)" +
                              ".V().count()");

            Assert.assertEquals(1L, traversal.next());
            traversal.close();
        }
    }

    @Test
    public void testPreserveCallerTraversalSourceStrategies() throws Exception {
        try (TinkerGraph graph = TinkerGraph.open();
             GraphTraversalSource g = graph.traversal();
             GraphTraversalSource requestSource =
                     g.withoutStrategies(LazyBarrierStrategy.class)) {
            Assert.assertTrue(g.getStrategies()
                               .getStrategy(LazyBarrierStrategy.class)
                               .isPresent());
            HugeScriptTraversal<Object, Object> traversal =
                    traversal(requestSource, "g.V()");

            traversal.asAdmin().applyStrategies();

            Assert.assertFalse(traversal.getStrategies()
                                        .getStrategy(LazyBarrierStrategy.class)
                                        .isPresent());
            traversal.close();
        }
    }

    @Test
    public void testDoesNotRetireCallerProtectedTraversalSource()
            throws Exception {
        try (TinkerGraph graph = TinkerGraph.open();
             GraphTraversalSource g = graph.traversal()) {
            graph.addVertex();
            HugeGraphGremlinLangScriptEngine engine =
                    new HugeGraphGremlinLangScriptEngineFactory()
                            .getScriptEngine();
            GraphTraversalSource protectedSource = engine.add(g);
            HugeScriptTraversal<Object, Object> traversal =
                    traversal(protectedSource, "g.V().count()");

            Assert.assertEquals(1L, traversal.next());
            traversal.close();

            Bindings bindings = engine.createBindings();
            bindings.put("g", protectedSource);
            Assert.assertEquals(1L, engine.eval("g.V().count().next()",
                                                bindings));
        }
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testLegacyConstructorCannotSelectGroovy() throws Exception {
        try (TinkerGraph graph = TinkerGraph.open();
             GraphTraversalSource g = graph.traversal()) {
            HugeScriptTraversal<Object, Object> traversal =
                    new HugeScriptTraversal<>(
                            g, "gremlin-lang", "g.inject(1)",
                            Collections.emptyMap(), Collections.emptyMap());

            Assert.assertEquals(1, traversal.next());
            traversal.close();
            Assert.assertThrows(IllegalArgumentException.class,
                                () -> new HugeScriptTraversal<>(
                                        g, "gremlin-groovy", "g.inject(1)",
                                        Collections.emptyMap(),
                                        Collections.emptyMap()),
                                e -> Assert.assertContains("gremlin-lang",
                                                           e.getMessage()));
        }
    }

    @Test
    public void testRejectCallStep() throws Exception {
        try (TinkerGraph graph = TinkerGraph.open();
             GraphTraversalSource g = graph.traversal()) {
            HugeScriptTraversal<Object, Object> traversal =
                    traversal(g, "g.call('service').iterate()");

            Assert.assertThrows(SecurityException.class, traversal::hasNext,
                                e -> Assert.assertContains("CallStep",
                                                           e.getMessage()));
            traversal.close();
        }
    }

    @Test
    public void testRejectGroovySyntax() throws Exception {
        try (TinkerGraph graph = TinkerGraph.open();
             GraphTraversalSource g = graph.traversal()) {
            HugeScriptTraversal<Object, Object> systemExit =
                    traversal(g, "System.exit(0)");
            HugeScriptTraversal<Object, Object> closure =
                    traversal(g, "g.V().map { it.get() }");

            Assert.assertThrows(RuntimeException.class, systemExit::hasNext);
            Assert.assertThrows(RuntimeException.class, closure::hasNext);
            systemExit.close();
            closure.close();
        }
    }

    private static HugeScriptTraversal<Object, Object> traversal(
                   GraphTraversalSource g, String script) {
        return new HugeScriptTraversal<>(g, script, Collections.emptyMap(),
                                         Collections.emptyMap());
    }
}
