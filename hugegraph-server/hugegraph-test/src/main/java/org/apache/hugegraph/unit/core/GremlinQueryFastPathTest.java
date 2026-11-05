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

package org.apache.hugegraph.unit.core;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import javax.script.ScriptException;
import javax.script.SimpleBindings;

import org.apache.hugegraph.security.script.PolicyScriptEngine;
import org.apache.hugegraph.security.script.ScriptBytecodePolicy;
import org.apache.hugegraph.security.script.ScriptExecutionProfile;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.verification.ReadOnlyStrategy;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph;
import org.junit.Assert;
import org.junit.Test;

public class GremlinQueryFastPathTest {

    @Test
    public void testParametersAndGraphAreResolvedPerRequest() throws Exception {
        try (TinkerGraph first = TinkerGraph.open(); TinkerGraph second = TinkerGraph.open();
             PolicyScriptEngine engine = new PolicyScriptEngine(ScriptExecutionProfile.QUERY)) {
            first.addVertex("age", 21);
            second.addVertex("age", 40);
            second.addVertex("age", 50);
            String source = "g.V().has('age',gt(ageLimit)).count().next()";
            Assert.assertEquals(1L, engine.eval(source,
                    new SimpleBindings(Map.of("g", first.traversal(), "ageLimit", 18))));
            Assert.assertEquals(0L, engine.eval(source,
                    new SimpleBindings(Map.of("g", first.traversal(), "ageLimit", 30))));
            Assert.assertEquals(2L, engine.eval(source,
                    new SimpleBindings(Map.of("g", second.traversal(), "ageLimit", 30))));
            Assert.assertEquals(0L, engine.compilationCount());
            Assert.assertEquals(3L, (long) engine.metrics().get("gremlinExecutions"));
            Assert.assertEquals(1L, (long) engine.metrics().get("gremlinParseMisses"));
        }
    }

    @Test
    public void testNestedTraversalsAndTerminalResults() throws Exception {
        try (TinkerGraph graph = TinkerGraph.open(); GraphTraversalSource g = graph.traversal();
             PolicyScriptEngine engine = new PolicyScriptEngine(ScriptExecutionProfile.QUERY)) {
            SimpleBindings bindings = new SimpleBindings(Map.of("g", g));
            Assert.assertEquals(List.of(2, 3), engine.eval(
                    "g.inject(1,2,3).filter(__.is(gt(1))).toList()", bindings));
            Assert.assertEquals(Set.of(1, 2), engine.eval("g.inject(1,2,2).toSet()", bindings));
            Assert.assertEquals(true, engine.eval("g.inject(1).hasNext()", bindings));
            Assert.assertEquals(java.util.Optional.of(1), engine.eval("g.inject(1).tryNext()", bindings));
            try (Traversal<?, ?> traversal = (Traversal<?, ?>) engine.eval("g.inject(1,2)", bindings)) {
                Assert.assertEquals(List.of(1, 2), traversal.toList());
            }
            try (Traversal<?, ?> traversal = (Traversal<?, ?>) engine.eval("g.inject(1).iterate()", bindings)) {
                Assert.assertFalse(traversal.hasNext());
            }
            Assert.assertEquals(0L, engine.compilationCount());
        }
    }

    @Test
    public void testGroovyAndSessionCompatibilityFallback() throws Exception {
        try (TinkerGraph graph = TinkerGraph.open(); GraphTraversalSource g = graph.traversal();
             PolicyScriptEngine engine = new PolicyScriptEngine(ScriptExecutionProfile.QUERY);
             PolicyScriptEngine session = new PolicyScriptEngine(ScriptExecutionProfile.QUERY, true)) {
            SimpleBindings bindings = new SimpleBindings(Map.of("g", g));
            Assert.assertEquals(List.of(2, 3), engine.eval("g.inject(1,2).map { it.get() + 1 }.toList()", bindings));
            Assert.assertEquals(3, engine.eval("def x = 2; x + 1", bindings));
            Assert.assertEquals(1, session.eval("g.inject(1).next()", new SimpleBindings(new HashMap<>(bindings))));
            Assert.assertEquals(2L, engine.compilationCount());
            Assert.assertEquals(1L, session.compilationCount());
            Assert.assertEquals(0L, (long) engine.metrics().get("gremlinExecutions"));
        }
    }

    @Test
    public void testGroovyLiteralAndBindingSemantics() throws Exception {
        try (TinkerGraph graph = TinkerGraph.open(); GraphTraversalSource g = graph.traversal();
             PolicyScriptEngine engine = new PolicyScriptEngine(ScriptExecutionProfile.QUERY)) {
            SimpleBindings bindings = new SimpleBindings(Map.of("g", g, "value", 7, "name", "Ada"));
            Assert.assertEquals(7, engine.eval("g.inject(value).next()", bindings));
            Assert.assertEquals(new BigDecimal("1.5"), engine.eval("g.inject(1.5).next()", bindings));
            Object interpolated = engine.eval("g.inject(\"hello $name\").next()", bindings);
            Assert.assertTrue(interpolated instanceof groovy.lang.GString);
            Assert.assertEquals("hello Ada", interpolated.toString());
            Assert.assertEquals(List.of(1, 2, 3), engine.eval("g.inject(1..3).next()", bindings));
            Assert.assertEquals(0L, (long) engine.metrics().get("gremlinExecutions"));
            // The same cached syntax remains usable when the keyword is no longer shadowed.
            Assert.assertEquals(org.apache.tinkerpop.gremlin.structure.T.value,
                    engine.eval("g.inject(value).next()", new SimpleBindings(Map.of("g", g))));
            Assert.assertEquals(1L, (long) engine.metrics().get("gremlinExecutions"));
        }
    }

    @Test
    public void testArrayBindingsPreserveArgumentShape() throws Exception {
        try (TinkerGraph graph = TinkerGraph.open(); GraphTraversalSource g = graph.traversal();
             PolicyScriptEngine engine = new PolicyScriptEngine(ScriptExecutionProfile.QUERY)) {
            graph.addVertex(org.apache.tinkerpop.gremlin.structure.T.label, "a", "name", "n");
            SimpleBindings array = new SimpleBindings(Map.of("g", g, "param", new Object[]{1}));
            String equality = "g.inject(1).is(param).toList()";
            Assert.assertEquals(List.of(), engine.compile(equality, array).eval(array));
            Assert.assertEquals(List.of(), engine.eval(equality, array));
            SimpleBindings keys = new SimpleBindings(Map.of("g", g, "param", new String[]{"name", "n"}));
            String has = "g.V().has('a',param).count().next()";
            Assert.assertEquals(0L, engine.compile(has, keys).eval(keys));
            Assert.assertEquals(0L, engine.eval(has, keys));
            Assert.assertEquals(0L, (long) engine.metrics().get("gremlinExecutions"));
        }
    }

    @Test
    public void testStringCoercionAndVarargsRetainGroovyBehavior() throws Exception {
        try (TinkerGraph graph = TinkerGraph.open(); GraphTraversalSource g = graph.traversal();
             PolicyScriptEngine engine = new PolicyScriptEngine(ScriptExecutionProfile.QUERY)) {
            Vertex a = graph.addVertex(
                    org.apache.tinkerpop.gremlin.structure.T.label, "a");
            a.addEdge("knows", graph.addVertex());
            SimpleBindings array = new SimpleBindings(Map.of("g", g, "param", new String[]{"knows"}));
            String out = "g.V().out(param).count().next()";
            Assert.assertEquals(1L, engine.compile(out, array).eval(array));
            Assert.assertEquals(1L, engine.eval(out, array));
            SimpleBindings string = new SimpleBindings(Map.of("g", g, "param",
                    new org.codehaus.groovy.runtime.GStringImpl(new Object[]{"a"}, new String[]{"", ""})));
            String label = "g.V().hasLabel(param).count().next()";
            Assert.assertEquals(1L, engine.compile(label, string).eval(string));
            Assert.assertEquals(1L, engine.eval(label, string));
            Assert.assertEquals(0L, (long) engine.metrics().get("gremlinExecutions"));
        }
    }

    @Test
    public void testNullArgumentsKeepGroovyOverloadResolution() throws Exception {
        try (TinkerGraph graph = TinkerGraph.open(); GraphTraversalSource g = graph.traversal();
             PolicyScriptEngine engine = new PolicyScriptEngine(ScriptExecutionProfile.QUERY)) {
            SimpleBindings bindings = new SimpleBindings(new HashMap<>(Map.of("g", g)));
            bindings.put("nullableValue", null);
            String source = "g.inject(1).is(nullableValue).toList()";
            Assert.assertEquals(List.of(), engine.compile(source, bindings).eval(bindings));
            Assert.assertEquals(List.of(), engine.eval(source, bindings));
            Assert.assertEquals(engine.compile("g.inject(null).toList()", bindings).eval(bindings),
                                engine.eval("g.inject(null).toList()", new SimpleBindings(Map.of("g", g))));
            Assert.assertEquals(0L, (long) engine.metrics().get("gremlinExecutions"));
        }
    }

    @Test
    public void testTerminalTraversalResultsKeepLazyGuards() throws Exception {
        try (TinkerGraph graph = TinkerGraph.open(); GraphTraversalSource g = graph.traversal();
             PolicyScriptEngine engine = new PolicyScriptEngine(ScriptExecutionProfile.QUERY)) {
            SimpleBindings bindings = new SimpleBindings(Map.of("g", g));
            try (Traversal<?, ?> result = (Traversal<?, ?>) engine.eval("g.inject(__.inject(1)).next()", bindings)) {
                Assert.assertEquals(1, result.next());
            }
            String source = "g.inject(__.inject(__.inject(1))).next()";
            try (Traversal<?, ?> expected = (Traversal<?, ?>) engine.compile(source, bindings).eval(bindings);
                 Traversal<?, ?> actual = (Traversal<?, ?>) engine.eval(source, bindings)) {
                Exception original = Assert.assertThrows(IllegalArgumentException.class, expected::next);
                Exception checked = Assert.assertThrows(IllegalArgumentException.class, actual::next);
                Assert.assertTrue(original.getMessage(), original.getMessage().contains("SCRIPT_RESULT_DENIED"));
                Assert.assertEquals(original.getMessage(), checked.getMessage());
            }
        }
    }

    @Test
    public void testSourceStateRetainsGroovyCompatibility() throws Exception {
        try (TinkerGraph graph = TinkerGraph.open(); GraphTraversalSource g = graph.traversal();
             PolicyScriptEngine engine = new PolicyScriptEngine(ScriptExecutionProfile.QUERY)) {
            SimpleBindings bindings = new SimpleBindings(Map.of("g", g));
            for (String source : List.of(
                    "g.withSack(T.id).inject(0).sack().next()",
                    "g.withSideEffect('x',T.id).inject(0).cap('x').next()",
                    "g.withSideEffect('x',[(T.id):1]).inject(0).cap('x').next()",
                    "g.withSideEffect('x',__.inject(1)).inject(0).count().next()")) {
                Assert.assertEquals(source, engine.compile(source, bindings).eval(bindings),
                                    engine.eval(source, bindings));
            }
            Assert.assertEquals(0L, (long) engine.metrics().get("gremlinExecutions"));
        }
    }

    @Test
    public void testTrustedSourceConfigurationIsPreserved() throws Exception {
        try (TinkerGraph graph = TinkerGraph.open();
             GraphTraversalSource g = graph.traversal().withSack(7)
                     .withSideEffect("preset", org.apache.tinkerpop.gremlin.structure.T.id)
                     .withStrategies(ReadOnlyStrategy.instance());
             PolicyScriptEngine engine = new PolicyScriptEngine(ScriptExecutionProfile.QUERY)) {
            SimpleBindings bindings = new SimpleBindings(Map.of("g", g));
            String original = g.getBytecode().toString();
            for (String source : List.of("g.withBulk(false).inject(1).sack().next()",
                    "g.withPath().inject(1).cap('preset').next()",
                    "g.inject(1).map(__.sack()).next()")) {
                Assert.assertEquals(source, engine.compile(source, bindings).eval(bindings),
                                    engine.eval(source, bindings));
            }
            Assert.assertEquals(3L, (long) engine.metrics().get("gremlinExecutions"));
            Assert.assertThrows(ScriptException.class, () -> engine.eval("g.addV('x').iterate()", bindings));
            Assert.assertEquals(0L, (long) graph.traversal().V().count().next());
            Assert.assertEquals(original, g.getBytecode().toString());
            // Trusted configuration must not relax the native client Bytecode entry point.
            Assert.assertThrows(IllegalArgumentException.class, () -> ScriptBytecodePolicy.validate(g.getBytecode()));
            Assert.assertThrows(ScriptException.class, () -> engine.eval(
                    "g.withoutStrategies(ReadOnlyStrategy).addV('x').iterate()", bindings));
            Assert.assertEquals(0L, (long) graph.traversal().V().count().next());
        }
    }

    @Test
    public void testStructuralFallbackDoesNotExecuteSourceSuppliersTwice() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (TinkerGraph graph = TinkerGraph.open();
             GraphTraversalSource g = graph.traversal().withSideEffect("preset", calls::incrementAndGet);
             PolicyScriptEngine engine = new PolicyScriptEngine(ScriptExecutionProfile.QUERY)) {
            String nested = "__.identity()";
            for (int i = 0; i < 16; i++) {
                nested = "__.map(" + nested + ")";
            }
            String source = "g.addV('x').map(" + nested + ").cap('preset').next()";
            Assert.assertEquals(1, engine.eval(source, new SimpleBindings(Map.of("g", g))));
            Assert.assertEquals(1, calls.get());
            Assert.assertEquals(1L, (long) graph.traversal().V().count().next());
            Assert.assertEquals(0L, (long) engine.metrics().get("gremlinExecutions"));
        }
    }

    @Test
    public void testSharedParametersKeepGroovyAliasesWithoutSharingRequests() throws Exception {
        try (TinkerGraph graph = TinkerGraph.open(); GraphTraversalSource g = graph.traversal();
             PolicyScriptEngine engine = new PolicyScriptEngine(ScriptExecutionProfile.QUERY)) {
            List<Integer> values = new ArrayList<>(List.of(1));
            SimpleBindings bindings = new SimpleBindings(Map.of("g", g, "items", values));
            String source = "g.addV('x').property('a',items).property('b',items).next()";
            Vertex groovy =
                    (Vertex) engine.compile(source, bindings).eval(bindings);
            Vertex fast =
                    (Vertex) engine.eval(source, bindings);
            Assert.assertSame(groovy.value("a"), groovy.value("b"));
            Assert.assertSame(fast.value("a"), fast.value("b"));
            Assert.assertNotSame(values, fast.value("a"));
            Assert.assertNotSame(groovy.value("a"), fast.value("a"));
            values.add(2);
            Assert.assertEquals(List.of(1), fast.value("a"));
            Vertex next =
                    (Vertex) engine.eval(source, bindings);
            Assert.assertEquals(List.of(1, 2), next.value("a"));
            Assert.assertSame(next.value("a"), next.value("b"));
            Assert.assertNotSame(fast.value("a"), next.value("a"));
            Assert.assertNotSame(values, next.value("a"));
            Assert.assertEquals(2L, (long) engine.metrics().get("gremlinExecutions"));
        }
    }

    @Test
    public void testNumericParametersDoNotNarrowSilently() throws Exception {
        try (TinkerGraph graph = TinkerGraph.open(); GraphTraversalSource g = graph.traversal();
             PolicyScriptEngine engine = new PolicyScriptEngine(ScriptExecutionProfile.QUERY)) {
            String source = "g.inject(1,2,3).limit(rowLimit).toList()";
            Assert.assertEquals(List.of(1), engine.eval(source, new SimpleBindings(Map.of("g", g, "rowLimit", 1L))));
            for (Number value : List.of(1.9, new BigDecimal("1.9"), new BigInteger("18446744073709551617"))) {
                SimpleBindings bindings = new SimpleBindings(Map.of("g", g, "rowLimit", value));
                Assert.assertThrows(ScriptException.class, () -> engine.compile(source, bindings));
                Assert.assertThrows(ScriptException.class, () -> engine.eval(source, bindings));
            }
            Assert.assertEquals(1L, (long) engine.metrics().get("gremlinExecutions"));
            Assert.assertEquals(2147483648L, engine.eval("g.inject(2147483648).next()",
                    new SimpleBindings(Map.of("g", g))));
        }
    }

    @Test
    public void testSupportedQueriesMatchGroovy() throws Exception {
        try (TinkerGraph graph = TinkerGraph.open(); GraphTraversalSource g = graph.traversal();
             PolicyScriptEngine engine = new PolicyScriptEngine(ScriptExecutionProfile.QUERY)) {
            SimpleBindings bindings = new SimpleBindings(Map.of("g", g, "ageLimit", 1));
            List<String> sources = List.of(
                    "g.inject(1,2,3).is(within([1,3])).toList()",
                    "g.inject('ab','cd').order().by(desc).toList()",
                    "g.inject([age:1],[age:2]).select('age').toList()",
                    "g.inject(1,1,2).toBulkSet()",
                    "g.inject(1,2).fold().next()",
                    "g.inject(3).union(__.constant(1),__.constant(2)).toList()",
                    "g.inject(1,2).filter(__.is(gt(ageLimit))).toList()",
                    "g.inject([1,2]).unfold().toList()",
                    "g.inject([(T.id):1]).next()");
            for (String source : sources) {
                Object expected = engine.compile(source, bindings).eval(bindings);
                Object actual = engine.eval(source, bindings);
                Assert.assertEquals(source, expected, actual);
                Assert.assertEquals(source, expected.getClass(), actual.getClass());
            }
            Assert.assertEquals(sources.size(), (long) engine.metrics().get("gremlinExecutions"));
        }
    }

    @Test
    public void testComplexQueriesFallBackBeforeGraphAccess() throws Exception {
        try (TinkerGraph graph = TinkerGraph.open(); GraphTraversalSource g = graph.traversal();
             PolicyScriptEngine engine = new PolicyScriptEngine(ScriptExecutionProfile.QUERY)) {
            SimpleBindings bindings = new SimpleBindings(Map.of("g", g));
            String nested = "__.identity()";
            for (int i = 0; i < 16; i++) {
                nested = "__.map(" + nested + ")";
            }
            String source = "g.inject(1).map(" + nested + ").toList()";
            Assert.assertEquals(List.of(1), engine.compile(source, bindings).eval(bindings));
            Assert.assertEquals(List.of(1), engine.eval(source, bindings));
            Assert.assertEquals(1L, engine.eval("g.addV('person').map(" + nested + ").count().next()", bindings));
            Assert.assertEquals(1L, (long) g.V().count().next());
            Assert.assertEquals(0L, (long) engine.metrics().get("gremlinExecutions"));
        }
    }

    @Test
    public void testEntireInputIsCheckedBeforeAnyWrite() throws Exception {
        try (TinkerGraph graph = TinkerGraph.open(); GraphTraversalSource g = graph.traversal();
             PolicyScriptEngine engine = new PolicyScriptEngine(ScriptExecutionProfile.QUERY)) {
            SimpleBindings bindings = new SimpleBindings(Map.of("g", g));
            for (String source : List.of("g.addV('x').iterate(); System.exit(0)",
                    "g.addV('x').iterate() trailingGarbage", "g.addV('x').iterate() L", "g.io('file').read().iterate()",
                    "g.withStrategies(ReadOnlyStrategy).V()", "g.inject(1,2).sample(2147483648).toList()")) {
                Assert.assertThrows(source, ScriptException.class, () -> engine.eval(source, bindings));
                Assert.assertEquals(0L, (long) g.V().count().next());
            }
        }
    }

    @Test
    public void testExecutionFailureNeverRetriesThroughGroovy() throws Exception {
        try (TinkerGraph graph = TinkerGraph.open(); GraphTraversalSource g = graph.traversal();
             PolicyScriptEngine engine = new PolicyScriptEngine(ScriptExecutionProfile.QUERY)) {
            Assert.assertThrows(ScriptException.class, () -> engine.eval(
                    "g.addV('person').values('missing').next()", new SimpleBindings(Map.of("g", g))));
            Assert.assertEquals(1L, (long) g.V().count().next());
            Assert.assertEquals(0L, engine.compilationCount());
        }
    }

    @Test
    public void testCachedPlanDoesNotShareConcurrentBindings() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(4);
        try (TinkerGraph graph = TinkerGraph.open(); GraphTraversalSource g = graph.traversal();
             PolicyScriptEngine engine = new PolicyScriptEngine(ScriptExecutionProfile.QUERY)) {
            for (int i = 0; i < 24; i++) {
                graph.addVertex("age", i);
            }
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                int value = i;
                futures.add(workers.submit(() -> engine.eval("g.V().has('age',ageLimit).values('age').next()",
                        new SimpleBindings(Map.of("g", g, "ageLimit", value)))));
            }
            for (int i = 0; i < futures.size(); i++) {
                Assert.assertEquals(i, futures.get(i).get());
            }
            Assert.assertEquals(0L, engine.compilationCount());
        } finally {
            workers.shutdownNow();
        }
    }
}
