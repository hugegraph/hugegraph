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

package org.apache.hugegraph.unit.security;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.script.Bindings;
import javax.script.ScriptContext;
import javax.script.SimpleBindings;
import javax.script.SimpleScriptContext;

import org.apache.hugegraph.HugeFactory;
import org.apache.hugegraph.HugeGraph;
import org.apache.hugegraph.auth.HugeGraphAuthProxy;
import org.apache.hugegraph.auth.HugeAuthenticator;
import org.apache.hugegraph.auth.RolePermission;
import org.apache.hugegraph.backend.query.Condition;
import org.apache.hugegraph.security.GremlinLangRestrictionStrategy;
import org.apache.hugegraph.security.GremlinLangTraversalVerifier;
import org.apache.hugegraph.security.HugeGraphGremlinLangScriptEngine;
import org.apache.hugegraph.security.HugeGraphGremlinLangScriptEngineFactory;
import org.apache.hugegraph.testutil.Assert;
import org.apache.hugegraph.task.TaskManager;
import org.apache.hugegraph.unit.FakeObjects;
import org.apache.tinkerpop.gremlin.jsr223.CachedGremlinScriptEngineManager;
import org.apache.tinkerpop.gremlin.jsr223.Customizer;
import org.apache.tinkerpop.gremlin.jsr223.GremlinLangPlugin;
import org.apache.tinkerpop.gremlin.jsr223.GremlinScriptEngine;
import org.apache.tinkerpop.gremlin.jsr223.GremlinScriptEngineManager;
import org.apache.tinkerpop.gremlin.jsr223.JavaTranslator;
import org.apache.tinkerpop.gremlin.jsr223.VariableResolverPlugin;
import org.apache.tinkerpop.gremlin.process.traversal.Bytecode;
import org.apache.tinkerpop.gremlin.process.traversal.Compare;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.TextP;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.step.HasContainerHolder;
import org.apache.tinkerpop.gremlin.process.traversal.util.TraversalHelper;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph;
import org.junit.Test;

public class HugeGraphGremlinLangScriptEngineTest {

    @Test
    public void testFactoryDoesNotClaimTinkerPopDiscoveryAliases() {
        HugeGraphGremlinLangScriptEngineFactory factory =
                new HugeGraphGremlinLangScriptEngineFactory();

        Assert.assertTrue(factory.getExtensions().isEmpty());
        Assert.assertTrue(factory.getMimeTypes().isEmpty());
    }

    @Test
    public void testManagerRegistersPublicNameToHugeGraphEngine() {
        GremlinScriptEngineManager manager =
                new CachedGremlinScriptEngineManager();
        manager.addPlugin(GremlinLangPlugin.build()
                                           .cacheEnabled(true)
                                           .caffeine("maximumSize=16")
                                           .create());
        manager.addPlugin(VariableResolverPlugin.build()
                                                .resolver(
                                                        "DefaultVariableResolver")
                                                .create());

        GremlinScriptEngine internal = manager.getEngineByName(
                HugeGraphGremlinLangScriptEngineFactory.INTERNAL_ENGINE_NAME);
        Assert.assertInstanceOf(HugeGraphGremlinLangScriptEngine.class,
                                internal);
        manager.registerEngineName(
                HugeGraphGremlinLangScriptEngineFactory.ENGINE_NAME,
                internal.getFactory());

        Assert.assertSame(internal, manager.getEngineByName(
                HugeGraphGremlinLangScriptEngineFactory.ENGINE_NAME));
    }

    @Test
    public void testRepeatedEvalUsesCurrentBindingValues() throws Exception {
        TinkerGraph graph = TinkerGraph.open();
        graph.addVertex(T.label, "person", "name", "marko");
        graph.addVertex(T.label, "person", "name", "stephen");
        GraphTraversalSource g = graph.traversal();
        HugeGraphGremlinLangScriptEngine engine = registeredEngine(g);
        Bindings bindings = new SimpleBindings();
        bindings.put("g", g);

        bindings.put("name", "marko");
        Traversal<?, ?> first = (Traversal<?, ?>) engine.eval(
                "g.V().has('name', name).values('name')", bindings);
        Assert.assertEquals("marko", first.next());

        bindings.put("name", "stephen");
        Traversal<?, ?> second = (Traversal<?, ?>) engine.eval(
                "g.V().has('name', name).values('name')", bindings);
        Assert.assertEquals("stephen", second.next());
        Assert.assertEquals(1, engine.traversalSourceCount());
        g.close();
        graph.close();
    }

    @Test
    public void testTextContainsLiteralUsesHugeGraphPredicate()
            throws Exception {
        try (TinkerGraph graph = TinkerGraph.open();
             GraphTraversalSource g = graph.traversal()) {
            graph.addVertex("name", "marko");
            graph.addVertex("name", "stephen");
            HugeGraphGremlinLangScriptEngine engine = registeredEngine(g);
            Bindings bindings = new SimpleBindings();
            bindings.put("g", g);

            Traversal<?, ?> traversal = (Traversal<?, ?>) engine.eval(
                    "g.V().has('name', Text.contains('ark'))" +
                    ".values('name')",
                    bindings);
            traversal.asAdmin().applyStrategies();
            HasContainerHolder<?, ?> step =
                    TraversalHelper.getStepsOfAssignableClass(
                            HasContainerHolder.class,
                            traversal.asAdmin()).get(0);
            Assert.assertSame(Condition.RelationType.TEXT_CONTAINS,
                              step.getHasContainers().get(0)
                                  .getBiPredicate());
            Assert.assertEquals("marko", traversal.next());
        }
    }

    @Test
    public void testRepeatedTextContainsUsesCurrentBinding()
            throws Exception {
        try (TinkerGraph graph = TinkerGraph.open();
             GraphTraversalSource g = graph.traversal()) {
            graph.addVertex("name", "marko");
            graph.addVertex("name", "stephen");
            HugeGraphGremlinLangScriptEngine engine = registeredEngine(g);
            Bindings bindings = new SimpleBindings();
            bindings.put("g", g);

            bindings.put("keyword", "ark");
            Traversal<?, ?> first = (Traversal<?, ?>) engine.eval(
                    "g.V().has('name', Text.contains(keyword))" +
                    ".values('name')",
                    bindings);
            Assert.assertEquals("marko", first.next());

            bindings.put("keyword", "teph");
            Traversal<?, ?> second = (Traversal<?, ?>) engine.eval(
                    "g.V().has('name', Text.contains(keyword))" +
                    ".values('name')",
                    bindings);
            Assert.assertEquals("stephen", second.next());
        }
    }

    @Test
    public void testTextContainsDoesNotReuseDirectResolverValue()
            throws Exception {
        try (TinkerGraph graph = TinkerGraph.open();
             GraphTraversalSource g = graph.traversal()) {
            graph.addVertex("name", "marko");
            graph.addVertex("name", "stephen");
            HugeGraphGremlinLangScriptEngine engine = engine(true, false);
            engine.add(g);
            Bindings bindings = new SimpleBindings();
            bindings.put("g", g);

            bindings.put("keyword", "ark");
            Traversal<?, ?> first = (Traversal<?, ?>) engine.eval(
                    "g.V().has('name', Text.contains(keyword))" +
                    ".values('name')",
                    bindings);
            Assert.assertEquals("marko", first.next());

            bindings.put("keyword", "teph");
            Traversal<?, ?> second = (Traversal<?, ?>) engine.eval(
                    "g.V().has('name', Text.contains(keyword))" +
                    ".values('name')",
                    bindings);
            Assert.assertEquals("stephen", second.next());
        }
    }

    @Test
    public void testTextContainsSupportsNestedAndThreeArgumentHas()
            throws Exception {
        try (TinkerGraph graph = TinkerGraph.open();
             GraphTraversalSource g = graph.traversal()) {
            graph.addVertex(T.label, "person", "name", "marko");
            graph.addVertex(T.label, "software", "name", "lop");
            HugeGraphGremlinLangScriptEngine engine = registeredEngine(g);
            Bindings bindings = new SimpleBindings();
            bindings.put("g", g);
            bindings.put("keyword", "ark");

            Assert.assertEquals("marko", engine.eval(
                    "g.V().where(__.has('name', " +
                    "Text.contains(keyword))).values('name').next()",
                    bindings));
            Assert.assertEquals("marko", engine.eval(
                    "g.V().has('person', 'name', " +
                    "Text.contains(keyword)).values('name').next()",
                    bindings));
        }
    }

    @Test
    public void testTextContainsSupportsReaderAndMultiplePredicates()
            throws Exception {
        try (TinkerGraph graph = TinkerGraph.open();
             GraphTraversalSource g = graph.traversal()) {
            graph.addVertex("name", "marko", "city", "santa fe");
            graph.addVertex("name", "marko", "city", "beijing");
            HugeGraphGremlinLangScriptEngine engine = registeredEngine(g);
            Bindings bindings = new SimpleBindings();
            bindings.put("g", g);
            bindings.put("namePart", "ark");
            bindings.put("cityPart", "anta");

            Assert.assertEquals("santa fe", engine.eval(
                    new StringReader(
                            "g.V().has('name', " +
                            "Text.contains(namePart)).has('city', " +
                            "Text.contains(cityPart)).values('city').next()"),
                    bindings));
        }
    }

    @Test
    public void testTextContainsWorksWhenParserCacheIsDisabled()
            throws Exception {
        try (TinkerGraph graph = TinkerGraph.open();
             GraphTraversalSource g = graph.traversal()) {
            graph.addVertex("name", "marko");
            HugeGraphGremlinLangScriptEngine engine = engine(false);
            engine.add(g);
            Bindings bindings = new SimpleBindings();
            bindings.put("g", g);

            Assert.assertEquals("marko", engine.eval(
                    "g.V().has('name', Text.contains('ark'))" +
                    ".values('name').next()",
                    bindings));
        }
    }

    @Test
    public void testTextContainsIgnoresStringContents()
            throws Exception {
        try (TinkerGraph graph = TinkerGraph.open();
             GraphTraversalSource g = graph.traversal()) {
            graph.addVertex("name", "Text.contains('ark')");
            HugeGraphGremlinLangScriptEngine engine = registeredEngine(g);
            Bindings bindings = new SimpleBindings();
            bindings.put("g", g);

            Assert.assertEquals("Text.contains('ark')", engine.eval(
                    "g.V().has('name', \"Text.contains('ark')\")" +
                    ".values('name').next()",
                    bindings));
        }
    }

    @Test
    public void testTextContainsSupportsDoubleQuotesEmptyAndLineComments()
            throws Exception {
        try (TinkerGraph graph = TinkerGraph.open();
             GraphTraversalSource g = graph.traversal()) {
            graph.addVertex("name", "marko");
            HugeGraphGremlinLangScriptEngine engine = registeredEngine(g);
            Bindings bindings = new SimpleBindings();
            bindings.put("g", g);

            Assert.assertEquals("marko", engine.eval(
                    "g.V().has(\"name\", Text.contains(\"ark\"))" +
                    ".values('name').next()",
                    bindings));
            Assert.assertEquals(1L, engine.eval(
                    "g.V().has('name', Text.contains('')).count().next()",
                    bindings));
            Assert.assertEquals("marko", engine.eval(
                    "g.V().has('name', Text.\n// HugeGraph predicate\n" +
                    "contains('ark')).values('name').next()",
                    bindings));
            Assert.assertEquals(1L, engine.eval(
                    "// Text.contains('ignored')\n" +
                    "g.V().count().next()",
                    bindings));
        }
    }

    @Test
    public void testTextContainsSupportsSupplementaryUnicode()
            throws Exception {
        try (TinkerGraph graph = TinkerGraph.open();
             GraphTraversalSource g = graph.traversal()) {
            graph.addVertex("tag", "😀", "name", "marko",
                            "city", "北😀京");
            HugeGraphGremlinLangScriptEngine engine = registeredEngine(g);
            Bindings bindings = new SimpleBindings();
            bindings.put("g", g);

            Traversal<?, ?> beforePredicate = (Traversal<?, ?>) engine.eval(
                    "g.V().has('tag', '😀').has('name', " +
                    "Text.contains('ark')).values('name')",
                    bindings);
            Assert.assertEquals("marko", beforePredicate.next());

            Traversal<?, ?> insidePredicate = (Traversal<?, ?>) engine.eval(
                    "g.V().has('city', Text.contains('😀')).values('name')",
                    bindings);
            Assert.assertEquals("marko", insidePredicate.next());

            Traversal<?, ?> multiplePredicates =
                    (Traversal<?, ?>) engine.eval(
                            "g.V().has('tag', '😀').has('name', " +
                            "Text.contains('ark')).has('city', " +
                            "Text.contains('😀京')).values('name')",
                            bindings);
            Assert.assertEquals("marko", multiplePredicates.next());
        }
    }

    @Test
    public void testTextPContainingKeepsTinkerPopSemantics()
            throws Exception {
        try (TinkerGraph graph = TinkerGraph.open();
             GraphTraversalSource g = graph.traversal()) {
            graph.addVertex("name", "marko");
            HugeGraphGremlinLangScriptEngine engine = registeredEngine(g);
            Bindings bindings = new SimpleBindings();
            bindings.put("g", g);

            Traversal<?, ?> traversal = (Traversal<?, ?>) engine.eval(
                    "g.V().has('name', TextP.containing('ark'))",
                    bindings);
            traversal.asAdmin().applyStrategies();
            HasContainerHolder<?, ?> step =
                    TraversalHelper.getStepsOfAssignableClass(
                            HasContainerHolder.class,
                            traversal.asAdmin()).get(0);
            P<?> predicate = step.getHasContainers().get(0).getPredicate();
            Assert.assertSame(TextP.containing("ark").getBiPredicate(),
                              predicate.getBiPredicate());
            Assert.assertEquals("ark", predicate.getValue());
        }
    }

    @Test
    public void testTextContainsRejectsUnsupportedForms() throws Exception {
        try (TinkerGraph graph = TinkerGraph.open();
             GraphTraversalSource g = graph.traversal()) {
            HugeGraphGremlinLangScriptEngine engine = registeredEngine(g);
            Bindings bindings = new SimpleBindings();
            bindings.put("g", g);
            bindings.put("keyword", 1);

            assertUnsupportedTextContains(engine, bindings,
                    "g.V().values('name').is(Text.contains('ark'))");
            assertUnsupportedTextContains(engine, bindings,
                    "g.V().has('name', Text.contains('a', 'b'))");
            assertUnsupportedTextContains(engine, bindings,
                    "g.V().has('name', Text.contains(keyword.toString()))");
            assertUnsupportedTextContains(engine, bindings,
                    "g.V().has('name', " +
                    "org.apache.hugegraph.Text.contains('ark'))");
            Assert.assertThrows(IllegalArgumentException.class,
                                () -> engine.eval(
                                        "g.V().has('name', " +
                                        "Text.contains(keyword))",
                                        bindings),
                                e -> Assert.assertContains("must be a String",
                                                           e.getMessage()));
            bindings.put("keyword", null);
            Assert.assertThrows(IllegalArgumentException.class,
                                () -> engine.eval(
                                        "g.V().has('name', " +
                                        "Text.contains(keyword))",
                                        bindings),
                                e -> Assert.assertContains("must be a String",
                                                           e.getMessage()));
        }
    }

    @Test
    public void testTextContainsRejectsReservedBindings() throws Exception {
        try (TinkerGraph graph = TinkerGraph.open();
             GraphTraversalSource g = graph.traversal()) {
            graph.addVertex("name", "hugegraphTextContainsInternal0");
            HugeGraphGremlinLangScriptEngine engine = registeredEngine(g);
            Bindings bindings = new SimpleBindings();
            bindings.put("g", g);
            bindings.put("hugegraphTextContainsInternal0", "ark");

            Assert.assertThrows(IllegalArgumentException.class,
                                () -> engine.eval(
                                        "g.V().has('name', " +
                                        "hugegraphTextContainsInternal0)",
                                        bindings),
                                e -> Assert.assertContains("reserved",
                                                           e.getMessage()));
            Assert.assertThrows(IllegalArgumentException.class,
                                () -> engine.eval(
                                        "g.V().has('name', " +
                                        "Text.contains('ark'))",
                                        bindings),
                                e -> Assert.assertContains("reserved",
                                                           e.getMessage()));

            Bindings safeBindings = new SimpleBindings();
            safeBindings.put("g", g);
            Assert.assertThrows(IllegalArgumentException.class,
                                () -> engine.eval(
                                        "g.V().has('name', " +
                                        "hugegraphTextContainsInternal0)",
                                        safeBindings),
                                e -> Assert.assertContains("reserved",
                                                           e.getMessage()));
            Assert.assertTrue(((Traversal<?, ?>) engine.eval(
                    "g.V().has('name', " +
                    "'hugegraphTextContainsInternal0')",
                    safeBindings)).hasNext());

            SimpleScriptContext context = new SimpleScriptContext();
            context.setBindings(safeBindings, ScriptContext.ENGINE_SCOPE);
            Bindings globalBindings = new SimpleBindings();
            globalBindings.put("hugegraphTextContainsInternal0", "ark");
            context.setBindings(globalBindings, ScriptContext.GLOBAL_SCOPE);
            Assert.assertThrows(IllegalArgumentException.class,
                                () -> engine.eval("g.V().count()", context),
                                e -> Assert.assertContains("reserved",
                                                           e.getMessage()));
        }
    }

    @Test
    public void testBytecodeCannotForgeTextContainsMarker()
            throws Exception {
        try (TinkerGraph graph = TinkerGraph.open();
             GraphTraversalSource g = graph.traversal()) {
            graph.addVertex("name", "hugegraphTextContainsInternal0");
            HugeGraphGremlinLangScriptEngine engine = registeredEngine(g);
            Bindings bindings = new SimpleBindings();
            bindings.put("g", g);
            Bytecode bytecode = g.V()
                                 .has("name",
                                      "hugegraphTextContainsInternal0")
                                 .asAdmin().getBytecode();

            Traversal.Admin<?, ?> traversal = engine.eval(bytecode,
                                                          bindings, "g");
            traversal.applyStrategies();
            HasContainerHolder<?, ?> step =
                    TraversalHelper.getStepsOfAssignableClass(
                            HasContainerHolder.class, traversal).get(0);
            Assert.assertSame(Compare.eq,
                              step.getHasContainers().get(0)
                                  .getBiPredicate());
            Assert.assertTrue(traversal.hasNext());
        }
    }

    @Test
    public void testConcurrentTextContainsRequestsKeepBindingsIsolated()
            throws Exception {
        try (TinkerGraph graph = TinkerGraph.open();
             GraphTraversalSource g = graph.traversal()) {
            int requests = 16;
            for (int i = 0; i < requests; i++) {
                graph.addVertex("name", "value-" + (char) ('a' + i));
            }
            HugeGraphGremlinLangScriptEngine engine = registeredEngine(g);
            ExecutorService executor = Executors.newFixedThreadPool(8);
            try {
                List<Future<Object>> results = new ArrayList<>();
                for (int i = 0; i < requests; i++) {
                    String keyword = "value-" + (char) ('a' + i);
                    results.add(executor.submit(() -> {
                        Bindings bindings = new SimpleBindings();
                        bindings.put("g", g);
                        bindings.put("keyword", keyword);
                        Traversal<?, ?> traversal =
                                (Traversal<?, ?>) engine.eval(
                                        "g.V().has('name', " +
                                        "Text.contains(keyword))" +
                                        ".values('name')",
                                        bindings);
                        return traversal.next();
                    }));
                }
                for (int i = 0; i < requests; i++) {
                    Assert.assertEquals("value-" + (char) ('a' + i),
                                        results.get(i).get());
                }
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    public void testProtectsHugeGraphAuthTraversalSource() throws Exception {
        HugeGraph graph = HugeFactory.open(FakeObjects.newConfig());
        HugeGraphAuthProxy proxy = new HugeGraphAuthProxy(graph);
        HugeAuthenticator.User user = new HugeAuthenticator.User(
                "sandbox-test", RolePermission.admin());
        TaskManager.setContext(user.toJson());
        HugeGraphGremlinLangScriptEngine mainEngine = engine();
        HugeGraphGremlinLangScriptEngine sessionEngine = engine();
        GraphTraversalSource source = proxy.traversal();
        GraphTraversalSource protectedSource = null;
        GraphTraversalSource replacement = null;
        GraphTraversalSource protectedReplacement = null;
        try {
            protectedSource = mainEngine.add(source);
            Assert.assertEquals(source.getClass(),
                                protectedSource.getClass());
            Bindings bindings = new SimpleBindings();
            bindings.put("g", protectedSource);

            Assert.assertEquals(0L, mainEngine.eval(
                    "g.V().count().next()", bindings));
            Assert.assertEquals(0L, mainEngine.eval(
                    "g.V().count().next()", bindings));
            Assert.assertEquals(0L, sessionEngine.eval(
                    "g.V().count().next()", bindings));

            Bytecode bytecode = new Bytecode();
            bytecode.addStep("V");
            bytecode.addStep("count");
            Traversal.Admin<?, ?> mainTraversal = mainEngine.eval(
                    bytecode, bindings, "g");
            Assert.assertEquals(0L, mainTraversal.next());
            Traversal.Admin<?, ?> sessionTraversal = sessionEngine.eval(
                    bytecode, bindings, "g");
            Assert.assertEquals(0L, sessionTraversal.next());

            mainEngine.remove(protectedSource);
            Assert.assertEquals(0, sessionEngine.traversalSourceCount());
            Assert.assertThrows(IllegalArgumentException.class,
                                () -> sessionEngine.eval(
                                        "g.V().count().next()", bindings),
                                e -> Assert.assertContains("active",
                                                           e.getMessage()));

            replacement = proxy.traversal();
            protectedReplacement = mainEngine.add(replacement);
            bindings.put("g", protectedReplacement);
            Assert.assertEquals(0L, sessionEngine.eval(
                    "g.V().count().next()", bindings));
        } finally {
            mainEngine.clear();
            sessionEngine.clear();
            if (protectedReplacement != null) {
                protectedReplacement.close();
            }
            if (replacement != null) {
                replacement.close();
            }
            if (protectedSource != null) {
                protectedSource.close();
            }
            source.close();
            graph.close();
            HugeGraphAuthProxy.resetContext();
            TaskManager.resetContext();
        }
    }

    @Test
    public void testCacheKeepsTraversalSourcesIsolated() throws Exception {
        try (TinkerGraph firstGraph = TinkerGraph.open();
             TinkerGraph secondGraph = TinkerGraph.open();
             GraphTraversalSource first = firstGraph.traversal();
             GraphTraversalSource second = secondGraph.traversal()) {
            firstGraph.addVertex();
            secondGraph.addVertex();
            secondGraph.addVertex();
            HugeGraphGremlinLangScriptEngine engine = registeredEngine(
                    first, second);

            Bindings bindings = new SimpleBindings();
            bindings.put("g", first);
            Assert.assertEquals(1L, engine.eval(
                    "g.V().count().next()", bindings));

            bindings.put("g", second);
            Assert.assertEquals(2L, engine.eval(
                    "g.V().count().next()", bindings));
            Assert.assertEquals(2, engine.traversalSourceCount());
        }
    }

    @Test
    public void testConcurrentRequestsKeepBindingValuesIsolated()
            throws Exception {
        try (TinkerGraph graph = TinkerGraph.open();
             GraphTraversalSource g = graph.traversal()) {
            int requests = 32;
            for (int i = 0; i < requests; i++) {
                graph.addVertex("name", "person-" + i);
            }

            HugeGraphGremlinLangScriptEngine engine = registeredEngine(g);
            ExecutorService executor = Executors.newFixedThreadPool(8);
            try {
                List<Future<Object>> results = new ArrayList<>();
                for (int i = 0; i < requests; i++) {
                    String name = "person-" + i;
                    results.add(executor.submit(() -> {
                        Bindings bindings = new SimpleBindings();
                        bindings.put("g", g);
                        bindings.put("name", name);
                        return engine.eval(
                                "g.V().has('name', name).count().next()",
                                bindings);
                    }));
                }
                for (Future<Object> result : results) {
                    Assert.assertEquals(1L, result.get());
                }
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    public void testParameterizedTraversalRequestsKeepValuesIsolated()
            throws Exception {
        try (TinkerGraph graph = TinkerGraph.open();
             GraphTraversalSource g = graph.traversal()) {
            graph.addVertex("name", "warm");
            graph.addVertex("name", "first");
            graph.addVertex("name", "second");
            HugeGraphGremlinLangScriptEngine engine = registeredEngine(g);
            String script = "g.V().has('name', name).values('name')";

            Bindings warmBindings = new SimpleBindings();
            warmBindings.put("g", g);
            warmBindings.put("name", "warm");
            Traversal<?, ?> warm = (Traversal<?, ?>) engine.eval(
                    script, warmBindings);
            Assert.assertEquals("warm", warm.next());

            Bindings firstBindings = new SimpleBindings();
            firstBindings.put("g", g);
            firstBindings.put("name", "first");
            Traversal<?, ?> first = (Traversal<?, ?>) engine.eval(
                    script, firstBindings);

            Bindings secondBindings = new SimpleBindings();
            secondBindings.put("g", g);
            secondBindings.put("name", "second");
            Traversal<?, ?> second = (Traversal<?, ?>) engine.eval(
                    script, secondBindings);

            first.asAdmin().applyStrategies();
            second.asAdmin().applyStrategies();
            Assert.assertEquals("first", first.next());
            Assert.assertEquals("second", second.next());
        }
    }

    @Test
    public void testRemoveTraversalSourceDropsItsDelegate() throws Exception {
        TinkerGraph graph = TinkerGraph.open();
        GraphTraversalSource g = graph.traversal();
        HugeGraphGremlinLangScriptEngine engine = registeredEngine(g);
        Bindings bindings = new SimpleBindings();
        bindings.put("g", g);

        engine.eval("g.V().count()", bindings);
        Assert.assertEquals(1, engine.traversalSourceCount());

        engine.remove(g);
        Assert.assertEquals(0, engine.traversalSourceCount());
        Assert.assertThrows(IllegalArgumentException.class,
                            () -> engine.eval("g.V().count()", bindings),
                            e -> Assert.assertContains("registered",
                                                       e.getMessage()));
        Assert.assertEquals(0, engine.traversalSourceCount());
        g.close();
        graph.close();
    }

    @Test
    public void testProtectedSourceBlocksBytecodeIoTraversal()
            throws Exception {
        Path directory = Files.createTempDirectory("hugegraph-bytecode");
        Path output = directory.resolve("graph.json");
        try (TinkerGraph graph = TinkerGraph.open();
             GraphTraversalSource g = graph.traversal()) {
            HugeGraphGremlinLangScriptEngine engine = engine();
            GraphTraversalSource protectedSource = engine.add(g);
            Bytecode bytecode = g.io(output.toString())
                                 .write().asAdmin().getBytecode();
            Traversal.Admin<?, ?> traversal = JavaTranslator.of(
                    protectedSource).translate(bytecode);

            Assert.assertThrows(SecurityException.class,
                                traversal::iterate,
                                e -> Assert.assertContains("IoStep",
                                                           e.getMessage()));
            Assert.assertFalse(Files.exists(output));
        } finally {
            Files.deleteIfExists(output);
            Files.deleteIfExists(directory);
        }
    }

    @Test
    public void testProtectedSourceAllowsSafeBytecodeTraversal()
            throws Exception {
        try (TinkerGraph graph = TinkerGraph.open();
             GraphTraversalSource g = graph.traversal()) {
            graph.addVertex();
            HugeGraphGremlinLangScriptEngine engine = engine();
            GraphTraversalSource protectedSource = engine.add(g);
            Bytecode bytecode = g.V().count().asAdmin().getBytecode();
            Traversal.Admin<?, ?> traversal = JavaTranslator.of(
                    protectedSource).translate(bytecode);

            Assert.assertEquals(1L, traversal.next());
        }
    }

    @Test
    public void testSessionEngineLazilyUsesProtectedTraversalSource()
            throws Exception {
        try (TinkerGraph graph = TinkerGraph.open();
             GraphTraversalSource g = graph.traversal()) {
            graph.addVertex();
            HugeGraphGremlinLangScriptEngine mainEngine = engine();
            GraphTraversalSource protectedSource = mainEngine.add(g);
            HugeGraphGremlinLangScriptEngine sessionEngine = engine();
            Bindings bindings = new SimpleBindings();
            bindings.put("g", protectedSource);

            Assert.assertEquals(1L, sessionEngine.eval(
                    "g.V().count().next()", bindings));
            Assert.assertEquals(1,
                                sessionEngine.traversalSourceCount());
        }
    }

    @Test
    public void testRemovingSourceInvalidatesSessionEngine() throws Exception {
        try (TinkerGraph graph = TinkerGraph.open();
             GraphTraversalSource g = graph.traversal()) {
            graph.addVertex();
            HugeGraphGremlinLangScriptEngine mainEngine = engine();
            GraphTraversalSource protectedSource = mainEngine.add(g);
            HugeGraphGremlinLangScriptEngine sessionEngine = engine();
            Bindings bindings = new SimpleBindings();
            bindings.put("g", protectedSource);
            Assert.assertEquals(1L, sessionEngine.eval(
                    "g.V().count().next()", bindings));

            mainEngine.remove(protectedSource);

            Assert.assertEquals(0, sessionEngine.traversalSourceCount());
            Assert.assertThrows(IllegalArgumentException.class,
                                () -> sessionEngine.eval(
                                        "g.V().count().next()", bindings),
                                e -> Assert.assertContains("active",
                                                           e.getMessage()));
        }
    }

    @Test
    public void testClearingSourcesInvalidatesSessionEngine()
            throws Exception {
        try (TinkerGraph graph = TinkerGraph.open();
             GraphTraversalSource g = graph.traversal()) {
            graph.addVertex();
            HugeGraphGremlinLangScriptEngine mainEngine = engine();
            GraphTraversalSource protectedSource = mainEngine.add(g);
            HugeGraphGremlinLangScriptEngine sessionEngine = engine();
            Bindings bindings = new SimpleBindings();
            bindings.put("g", protectedSource);
            Assert.assertEquals(1L, sessionEngine.eval(
                    "g.V().count().next()", bindings));

            mainEngine.clear();

            Assert.assertEquals(0, sessionEngine.traversalSourceCount());
            Assert.assertThrows(IllegalArgumentException.class,
                                () -> sessionEngine.eval(
                                        "g.V().count().next()", bindings),
                                e -> Assert.assertContains("active",
                                                           e.getMessage()));
        }
    }

    @Test
    public void testSessionEngineRejectsUnprotectedTraversalSource()
            throws Exception {
        try (TinkerGraph graph = TinkerGraph.open();
             GraphTraversalSource g = graph.traversal()) {
            HugeGraphGremlinLangScriptEngine sessionEngine = engine();
            Bindings bindings = new SimpleBindings();
            bindings.put("g", g);

            Assert.assertThrows(IllegalArgumentException.class,
                                () -> sessionEngine.eval(
                                        "g.V().count()", bindings),
                                e -> Assert.assertContains("protected",
                                                           e.getMessage()));
        }
    }

    @Test
    public void testSessionEngineRejectsUnregisteredProtectedSource()
            throws Exception {
        try (TinkerGraph graph = TinkerGraph.open();
             GraphTraversalSource g = graph.traversal();
             GraphTraversalSource protectedSource = g.withStrategies(
                     GremlinLangRestrictionStrategy.instance())) {
            HugeGraphGremlinLangScriptEngine sessionEngine = engine();
            Bindings bindings = new SimpleBindings();
            bindings.put("g", protectedSource);

            Assert.assertThrows(IllegalArgumentException.class,
                                () -> sessionEngine.eval(
                                        "g.V().count()", bindings),
                                e -> Assert.assertContains("active",
                                                           e.getMessage()));
        }
    }

    @Test
    public void testRemovedProtectedSourceCannotRecreateDelegate()
            throws Exception {
        try (TinkerGraph graph = TinkerGraph.open();
             GraphTraversalSource g = graph.traversal()) {
            HugeGraphGremlinLangScriptEngine engine = engine();
            GraphTraversalSource protectedSource = engine.add(g);
            Bindings bindings = new SimpleBindings();
            bindings.put("g", protectedSource);

            engine.remove(protectedSource);

            Assert.assertThrows(IllegalArgumentException.class,
                                () -> engine.eval("g.V()", bindings),
                                e -> Assert.assertContains("registered",
                                                           e.getMessage()));
            Assert.assertEquals(0, engine.traversalSourceCount());
        }
    }

    @Test
    public void testAllowsSafeTerminalExecution() throws Exception {
        try (TinkerGraph graph = TinkerGraph.open();
             GraphTraversalSource g = graph.traversal()) {
            HugeGraphGremlinLangScriptEngine engine = registeredEngine(g);
            Bindings bindings = new SimpleBindings();
            bindings.put("g", g);

            Assert.assertEquals(0L,
                                engine.eval("g.V().count().next()",
                                            bindings));
        }
    }

    @Test
    public void testAllowsTinkerPopInitializationProbeWithoutTraversalSource()
            throws Exception {
        HugeGraphGremlinLangScriptEngine engine = engine();

        Assert.assertEquals(2, engine.eval("1+1", new SimpleBindings()));
        Assert.assertEquals(0, engine.traversalSourceCount());
        Assert.assertThrows(IllegalArgumentException.class,
                            () -> engine.eval("1+1", new SimpleBindings()),
                            e -> Assert.assertContains(
                                    "GraphTraversalSource",
                                    e.getMessage()));
    }

    @Test
    public void testRejectsOtherScriptsWithoutTraversalSource() {
        HugeGraphGremlinLangScriptEngine engine = engine();

        Assert.assertThrows(IllegalArgumentException.class,
                            () -> engine.eval("2+2", new SimpleBindings()),
                            e -> Assert.assertContains(
                                    "GraphTraversalSource",
                                    e.getMessage()));
    }

    @Test
    public void testVerifierAllowsNormalTraversal() throws Exception {
        try (TinkerGraph graph = TinkerGraph.open();
             GraphTraversalSource g = graph.traversal()) {
            GremlinLangTraversalVerifier.verify(g.V().hasLabel("person"));
        }
    }

    @Test
    public void testVerifierRejectsIoStep() throws Exception {
        try (TinkerGraph graph = TinkerGraph.open();
             GraphTraversalSource g = graph.traversal()) {
            Assert.assertThrows(SecurityException.class,
                                () -> GremlinLangTraversalVerifier.verify(
                                        g.io("graph.json")),
                                e -> Assert.assertContains("IoStep",
                                                           e.getMessage()));
        }
    }

    @Test
    public void testVerifierRejectsCallStep() throws Exception {
        try (TinkerGraph graph = TinkerGraph.open();
             GraphTraversalSource g = graph.traversal()) {
            Assert.assertThrows(SecurityException.class,
                                () -> GremlinLangTraversalVerifier.verify(
                                        g.call("service")),
                                e -> Assert.assertContains("CallStep",
                                                           e.getMessage()));
        }
    }

    @Test
    public void testVerifierRejectsCallStepBeforeTerminalExecution()
            throws Exception {
        try (TinkerGraph graph = TinkerGraph.open();
             GraphTraversalSource g = graph.traversal()) {
            HugeGraphGremlinLangScriptEngine engine = registeredEngine(g);
            Bindings bindings = new SimpleBindings();
            bindings.put("g", g);

            Assert.assertThrows(SecurityException.class,
                                () -> engine.eval(
                                        "g.call('service').iterate()",
                                        bindings),
                                e -> Assert.assertContains("CallStep",
                                                           e.getMessage()));
        }
    }

    @Test
    public void testVerifierRejectsParameterizedCallStep() throws Exception {
        try (TinkerGraph graph = TinkerGraph.open();
             GraphTraversalSource g = graph.traversal()) {
            HugeGraphGremlinLangScriptEngine engine = registeredEngine(g);
            Bindings bindings = new SimpleBindings();
            bindings.put("g", g);
            bindings.put("params",
                         Collections.singletonMap("key", "value"));

            Assert.assertThrows(SecurityException.class,
                                () -> engine.eval(
                                        "g.call('service', params)",
                                        bindings),
                                e -> Assert.assertContains("CallStep",
                                                           e.getMessage()));
        }
    }

    @Test
    public void testVerifierRejectsParameterizedCallStepBeforeTerminalExecution()
            throws Exception {
        try (TinkerGraph graph = TinkerGraph.open();
             GraphTraversalSource g = graph.traversal()) {
            HugeGraphGremlinLangScriptEngine engine = registeredEngine(g);
            Bindings bindings = new SimpleBindings();
            bindings.put("g", g);
            bindings.put("params",
                         Collections.singletonMap("key", "value"));

            Assert.assertThrows(SecurityException.class,
                                () -> engine.eval(
                                        "g.call('service', params).iterate()",
                                        bindings),
                                e -> Assert.assertContains("CallStep",
                                                           e.getMessage()));
        }
    }

    @Test
    public void testVerifierRejectsIoStepBeforeTerminalExecution()
            throws Exception {
        Path directory = Files.createTempDirectory("hugegraph-gremlin-lang");
        Path output = directory.resolve("graph.json");
        try (TinkerGraph graph = TinkerGraph.open();
             GraphTraversalSource g = graph.traversal()) {
            HugeGraphGremlinLangScriptEngine engine = registeredEngine(g);
            Bindings bindings = new SimpleBindings();
            bindings.put("g", g);

            Assert.assertThrows(SecurityException.class,
                                () -> engine.eval(
                                        String.format(
                                                "g.io('%s').write().iterate()",
                                                output),
                                        bindings),
                                e -> Assert.assertContains("IoStep",
                                                           e.getMessage()));
            Assert.assertFalse(Files.exists(output));
        } finally {
            Files.deleteIfExists(output);
            Files.deleteIfExists(directory);
        }
    }

    private static HugeGraphGremlinLangScriptEngine engine() {
        return engine(true);
    }

    private static HugeGraphGremlinLangScriptEngine engine(
            boolean cacheEnabled) {
        return engine(cacheEnabled, true);
    }

    private static HugeGraphGremlinLangScriptEngine engine(
            boolean cacheEnabled, boolean defaultVariableResolver) {
        List<Customizer> customizers = new ArrayList<>();
        GremlinLangPlugin cache = cacheEnabled ?
                                  GremlinLangPlugin.build()
                                                   .cacheEnabled(true)
                                                   .caffeine(
                                                           "maximumSize=16")
                                                   .create() :
                                  GremlinLangPlugin.build()
                                                   .cacheEnabled(false)
                                                   .create();
        customizers.addAll(Arrays.asList(
                cache.getCustomizers("gremlin-lang").get()));
        if (defaultVariableResolver) {
            VariableResolverPlugin variables =
                    VariableResolverPlugin.build()
                                          .resolver(
                                                  "DefaultVariableResolver")
                                          .create();
            customizers.addAll(Arrays.asList(
                    variables.getCustomizers("gremlin-lang").get()));
        }
        HugeGraphGremlinLangScriptEngineFactory factory =
                new HugeGraphGremlinLangScriptEngineFactory(
                        customizers.toArray(new Customizer[0]));
        return factory.getScriptEngine();
    }

    private static void assertUnsupportedTextContains(
            HugeGraphGremlinLangScriptEngine engine, Bindings bindings,
            String script) {
        Assert.assertThrows(IllegalArgumentException.class,
                            () -> engine.eval(script, bindings),
                            e -> Assert.assertContains("only supported",
                                                       e.getMessage()));
    }

    private static HugeGraphGremlinLangScriptEngine registeredEngine(
            GraphTraversalSource... traversalSources) {
        HugeGraphGremlinLangScriptEngine engine = engine();
        for (GraphTraversalSource traversalSource : traversalSources) {
            engine.add(traversalSource);
        }
        return engine;
    }
}
