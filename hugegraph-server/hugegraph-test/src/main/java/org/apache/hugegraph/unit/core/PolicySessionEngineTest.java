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

import java.util.List;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Set;
import java.lang.management.ManagementFactory;

import javax.management.ObjectName;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import javax.script.ScriptException;
import javax.script.SimpleBindings;

import org.apache.hugegraph.auth.PolicyGremlinScriptEngineManager;
import org.apache.hugegraph.backend.store.BackendEntryIterator;
import org.apache.hugegraph.testutil.Whitebox;
import org.apache.tinkerpop.gremlin.groovy.engine.GremlinExecutor;
import org.apache.hugegraph.security.script.PolicyScriptEngine;
import org.apache.hugegraph.security.script.ScriptExecutionProfile;
import org.apache.hugegraph.security.script.ScriptMethodPolicy;
import org.apache.tinkerpop.gremlin.structure.util.empty.EmptyGraph;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.util.CloseableIterator;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;

public class PolicySessionEngineTest {

    @Test
    public void testAssignmentsMutationsAndCacheDoNotCrossSessions() throws Exception {
        try (PolicyScriptEngine first = new PolicyScriptEngine(ScriptExecutionProfile.QUERY, true);
             PolicyScriptEngine second = new PolicyScriptEngine(ScriptExecutionProfile.QUERY, true)) {
            SimpleBindings a = new SimpleBindings();
            SimpleBindings b = new SimpleBindings();
            Assert.assertEquals(1, first.eval("x = 1", a));
            Assert.assertEquals(2, first.eval("x = x + 1; x", a));
            Assert.assertEquals(2, a.get("x"));
            Assert.assertEquals(4, first.eval("if (true) { branch = 4 }; branch", a));
            Assert.assertEquals(4, a.get("branch"));
            Assert.assertEquals(1, first.eval("if (false) { absent = 9 }; 1", a));
            Assert.assertFalse(a.containsKey("absent"));
            Assert.assertNull(first.eval("nullable = null", a));
            Assert.assertTrue(a.containsKey("nullable"));
            Assert.assertEquals(2, first.eval("for (int i = 0; i < 3; i++) { loopValue = i }; loopValue", a));
            Assert.assertEquals(2, a.get("loopValue"));
            Assert.assertEquals(List.of(1, 2), first.eval("values = [1, 2]; values", a));
            Assert.assertEquals(3, first.eval("values.add(3); values.size()", a));
            Assert.assertEquals(List.of(1, 2, 3), a.get("values"));
            Assert.assertEquals(3, first.eval("x + 1", a));
            Assert.assertEquals(3, first.eval("x + 1", a));
            Assert.assertThrows(ScriptException.class, () -> second.eval("x + 1", b));
            Assert.assertEquals(40, second.eval("x = 40", b));
            Assert.assertEquals(2, first.eval("x", a));
            Assert.assertEquals(40, second.eval("x", b));
        }
    }

    @Test
    public void testRejectedSessionStateClosesOpenedTraversal() throws Exception {
        assertOpenedTraversalsClosed("def t=g.V(); t.hasNext(); saved={1}; t", true, 1);
    }

    @Test
    public void testRejectedNestedResultsCloseEveryOpenedTraversal() throws Exception {
        for (boolean session : List.of(false, true)) {
            for (String result : List.of("[t,u,t]", "[bad:{1}, cursors:[t,u,t]]", "[first:t,second:u]")) {
                assertOpenedTraversalsClosed("def t=g.V(); t.hasNext(); def u=g.V(); u.hasNext(); " + result,
                                             session, 2);
            }
        }
    }

    @Test
    public void testDiscardedSessionBindingsCloseOpenedTraversals() throws Exception {
        for (String result : List.of("1", "saved", "[saved]", "int zero=0; 1/zero")) {
            assertOpenedTraversalsClosed("saved=g.V(); saved.hasNext(); " + result, true, 1);
        }
    }

    private static void assertOpenedTraversalsClosed(String script, boolean session, int expected) throws Exception {
        Graph graph = Mockito.mock(Graph.class);
        Vertex vertex = Mockito.mock(Vertex.class);
        int[] calls = new int[2];
        Mockito.when(graph.vertices(Mockito.any(Object[].class))).thenAnswer(invocation -> {
            calls[0]++;
            return new CloseableIterator<Vertex>() {
                @Override
                public boolean hasNext() {
                    return true;
                }

                @Override
                public Vertex next() {
                    return vertex;
                }

                @Override
                public void close() {
                    calls[1]++;
                }
            };
        });
        try (GraphTraversalSource source = new GraphTraversalSource(graph);
             PolicyScriptEngine engine = new PolicyScriptEngine(ScriptExecutionProfile.QUERY, session)) {
            SimpleBindings state = new SimpleBindings(Map.of("g", source));
            Assert.assertThrows(ScriptException.class, () -> engine.eval(script, state));
            Assert.assertEquals(script, expected, calls[0]);
            Assert.assertEquals(script, expected, calls[1]);
            Assert.assertFalse(state.containsKey("saved"));
        }
    }

    @Test
    public void testBackendInterruptUsesTimeoutLifecycle() throws Exception {
        for (boolean policy : List.of(false, true)) {
            Graph graph = Mockito.mock(Graph.class);
            AtomicInteger checks = new AtomicInteger();
            AtomicInteger timeouts = new AtomicInteger();
            AtomicInteger failures = new AtomicInteger();
            Mockito.when(graph.vertices(Mockito.any(Object[].class))).thenAnswer(invocation -> {
                checks.incrementAndGet();
                Thread.currentThread().interrupt();
                BackendEntryIterator.checkInterrupted();
                throw new AssertionError("Backend interruption must throw");
            });
            try (GraphTraversalSource source = new GraphTraversalSource(graph);
                 PolicyGremlinScriptEngineManager manager = policy ?
                         new PolicyGremlinScriptEngineManager(new SimpleBindings(), new SimpleBindings()) : null;
                 GremlinExecutor executor = GremlinExecutor.build().evaluationTimeout(0L)
                         .afterTimeout((bindings, error) -> timeouts.incrementAndGet())
                         .afterFailure((bindings, error) -> failures.incrementAndGet()).create()) {
                if (policy) {
                    Whitebox.setInternalState(executor, "gremlinScriptEngineManager", manager);
                }
                ExecutionException error = Assert.assertThrows(ExecutionException.class, () -> executor.eval(
                        "g.V().toList()", new SimpleBindings(Map.of("g", source))).get(10, TimeUnit.SECONDS));
                Assert.assertTrue(error.toString(), error.getCause() instanceof TimeoutException);
                Assert.assertEquals(1, checks.get());
                Assert.assertEquals(1, timeouts.get());
                Assert.assertEquals(0, failures.get());
            }
        }
    }

    @Test
    public void testExplicitViewSnapshotsCanBeRetained() throws Exception {
        try (PolicyScriptEngine engine = new PolicyScriptEngine(ScriptExecutionProfile.QUERY, true)) {
            SimpleBindings state = new SimpleBindings();
            engine.eval("keys=[a:1].keySet().toSet(); values=[a:1].values().toList(); " +
                        "part=[1,2].subList(0,1).toList(); 1", state);
            Assert.assertEquals(Set.of("a"), state.get("keys"));
            Assert.assertEquals(List.of(1), state.get("values"));
            Assert.assertEquals(List.of(1), state.get("part"));
            Assert.assertEquals(List.of(1, 2), engine.eval("part.add(2); part", state));
        }
    }

    @Test
    public void testFailureRetainsMutationsButNotReassignments() throws Exception {
        try (PolicyScriptEngine engine = new PolicyScriptEngine(ScriptExecutionProfile.QUERY, true)) {
            SimpleBindings state = new SimpleBindings();
            engine.eval("counter = 6; values = [1,2,3]; 1", state);
            Assert.assertThrows(ScriptException.class, () -> engine.eval(
                    "counter = 7; values.add(9); values = []; fresh = 1; int zero = 0; 1 / zero", state));
            Assert.assertEquals(6, state.get("counter"));
            Assert.assertEquals(List.of(1, 2, 3, 9), state.get("values"));
            Assert.assertFalse(state.containsKey("fresh"));
            Assert.assertThrows(ScriptException.class, () -> engine.eval(
                    "values.add({ 1 }); int zero = 0; 1 / zero", state));
            Assert.assertEquals(List.of(1, 2, 3, 9), state.get("values"));
        }
    }

    @Test
    public void testSharedContainerReferencesSurviveRequests() throws Exception {
        try (PolicyScriptEngine engine = new PolicyScriptEngine(ScriptExecutionProfile.QUERY, true)) {
            SimpleBindings state = new SimpleBindings();
            engine.eval("xs = []; ys = xs; 1", state);
            Assert.assertSame(state.get("xs"), state.get("ys"));
            Assert.assertEquals(List.of(1), engine.eval("xs.add(1); ys", state));
            Assert.assertThrows(ScriptException.class, () -> engine.eval(
                    "ys.add(2); int zero = 0; 1 / zero", state));
            Assert.assertEquals(List.of(1, 2), engine.eval("xs", state));
        }
    }

    @Test
    public void testCachedEngineAndManagerShareScopes() throws Exception {
        SimpleBindings globals = new SimpleBindings(Map.of("constant", 7));
        SimpleBindings session = new SimpleBindings();
        try (PolicyGremlinScriptEngineManager manager = new PolicyGremlinScriptEngineManager(globals, session)) {
            Assert.assertEquals(7, manager.getEngineByName("gremlin-groovy").eval("constant"));
            Assert.assertEquals(8, manager.getEngineByName("gremlin-groovy").eval("value = constant + 1"));
            Assert.assertEquals(8, session.get("value"));
            Assert.assertSame(manager.getEngineByName("gremlin-groovy"), manager.getEngineByExtension("groovy"));
            Assert.assertEquals(8, manager.getEngineByExtension("groovy").eval("value"));
        }
    }

    @Test
    public void testRejectedStateDoesNotPoisonSession() throws Exception {
        try (PolicyScriptEngine engine = new PolicyScriptEngine(ScriptExecutionProfile.QUERY, true)) {
            SimpleBindings state = new SimpleBindings(new HashMap<>(Map.of("value", 2)));
            Assert.assertThrows(ScriptException.class,
                    () -> engine.eval("saved = { 1 }; 1", state));
            Assert.assertFalse(state.containsKey("saved"));
            Assert.assertThrows(ScriptException.class,
                    () -> engine.eval("System.getProperty('java.version')", state));
            Assert.assertThrows(ScriptException.class,
                    () -> engine.eval("__hgSecret = 1", state));
            Assert.assertEquals(3, engine.eval("value + 1", state));
        }
    }

    @Test
    public void testLexicalVariablesDoNotBecomeBindings() throws Exception {
        try (PolicyScriptEngine engine = new PolicyScriptEngine(ScriptExecutionProfile.QUERY, true)) {
            SimpleBindings state = new SimpleBindings(new HashMap<>(Map.of("existing", 9)));
            Assert.assertEquals(3, engine.eval("def local = 1; int other = 2; local + other", state));
            Assert.assertFalse(state.containsKey("local"));
            Assert.assertFalse(state.containsKey("other"));
            Assert.assertEquals(2, engine.eval("def existing = 2; existing", state));
            Assert.assertEquals(9, state.get("existing"));
            Assert.assertEquals(1, engine.eval("def localClosure = { 1 }; 1", state));
            Assert.assertFalse(state.containsKey("localClosure"));
        }
    }

    @Test
    public void testDeferredStatePublishesOnlyAfterLazyIteration() throws Exception {
        SimpleBindings state = new SimpleBindings(new HashMap<>(Map.of(
                "g", EmptyGraph.instance().traversal(), "counter", 0, "values", List.of())));
        try (PolicyScriptEngine engine = new PolicyScriptEngine(ScriptExecutionProfile.QUERY, true)) {
            engine.deferSessionPublication();
            Object result = engine.eval("g.inject(1, 2).map { counter += 1; values.add(it.get()); it.get() }", state);
            Assert.assertTrue(result instanceof Iterator);
            Iterator<?> iterator = (Iterator<?>) result;
            Assert.assertEquals(0, state.get("counter"));
            Assert.assertEquals(1, iterator.next());
            Assert.assertEquals(0, state.get("counter"));
            Assert.assertEquals(2, iterator.next());
            Assert.assertFalse(iterator.hasNext());
            engine.publishSession(state);
            Assert.assertEquals(2, state.get("counter"));
            Assert.assertEquals(List.of(1, 2), state.get("values"));
            Iterator<?> invalid = (Iterator<?>) engine.eval("g.inject(1).map { values.add(g); it.get() }", state);
            Assert.assertThrows(IllegalArgumentException.class, invalid::hasNext);
            engine.abortSession();
            Assert.assertEquals(List.of(1, 2), state.get("values"));
        }
    }

    @Test
    public void testSessionBindingReassignmentPreservesNumericTypes() throws Exception {
        SimpleBindings state = new SimpleBindings(new HashMap<>(Map.of("value", 1)));
        try (PolicyScriptEngine engine = new PolicyScriptEngine(ScriptExecutionProfile.QUERY, true)) {
            Assert.assertEquals(2147483648L, engine.eval("value=2147483648L; value", state));
            Assert.assertEquals(Long.class, state.get("value").getClass());
            Assert.assertEquals(new java.math.BigDecimal("2147483648.5"),
                    engine.eval("value+=0.5; value", state));
            Assert.assertEquals(java.math.BigDecimal.class, state.get("value").getClass());
            Assert.assertEquals("changed", engine.eval("value='changed'; value", state));
        }
    }

    @Test
    public void testSessionPreservesOrdinaryInterpolationButRejectsClosures() throws Exception {
        SimpleBindings state = new SimpleBindings(new HashMap<>());
        try (PolicyScriptEngine engine = new PolicyScriptEngine(ScriptExecutionProfile.QUERY, true)) {
            engine.eval("who='Ada'; greeting=\"Hello ${who}\"; 1", state);
            Assert.assertEquals("Hello Ada", engine.eval("greeting.toString()", state));
            Assert.assertTrue(state.get("greeting") instanceof groovy.lang.GString);
            Assert.assertThrows(ScriptException.class, () -> engine.eval(
                    "greeting=\"${ -> 42 }\"; 1", state));
            Assert.assertEquals("Hello Ada", engine.eval("greeting.toString()", state));
        }
    }

    @Test
    public void testFailurePublishesToPersistentSessionBeyondRequestCopy() throws Exception {
        SimpleBindings persistent = new SimpleBindings(new HashMap<>(Map.of(
                "g", EmptyGraph.instance().traversal(), "counter", 0, "values", List.of(1))));
        try (PolicyScriptEngine engine = new PolicyScriptEngine(ScriptExecutionProfile.QUERY, true)) {
            engine.deferSessionPublication(persistent);
            SimpleBindings request = new SimpleBindings(new HashMap<>(persistent));
            Assert.assertThrows(ScriptException.class, () -> engine.eval(
                    "values.add(2); counter=7; int zero=0; 1/zero", request));
            Assert.assertEquals(List.of(1, 2), persistent.get("values"));
            Assert.assertEquals(0, persistent.get("counter"));
            SimpleBindings next = new SimpleBindings(new HashMap<>(persistent));
            Iterator<?> result = (Iterator<?>) engine.eval(
                    "g.inject(1).map { values.add(3); counter=9; int zero=0; 1/zero }", next);
            Assert.assertThrows(RuntimeException.class, result::hasNext);
            engine.abortSession();
            Assert.assertEquals(List.of(1, 2, 3), persistent.get("values"));
            Assert.assertEquals(0, persistent.get("counter"));
        }
    }

    @Test
    public void testPostfixAndPrefixInLazyClosures() throws Exception {
        SimpleBindings state = new SimpleBindings(new HashMap<>(Map.of(
                "g", EmptyGraph.instance().traversal(), "counter", 3)));
        try (PolicyScriptEngine engine = new PolicyScriptEngine(ScriptExecutionProfile.QUERY, true)) {
            engine.deferSessionPublication();
            Iterator<?> result = (Iterator<?>) engine.eval("g.inject(1, 2).map { counter++ }", state);
            Assert.assertEquals(3, result.next());
            Assert.assertEquals(4, result.next());
            Assert.assertFalse(result.hasNext());
            engine.publishSession(state);
            Assert.assertEquals(5, state.get("counter"));
            Iterator<?> prefix = (Iterator<?>) engine.eval("g.inject(1).map { ++counter }", state);
            Assert.assertEquals(6, prefix.next());
            Assert.assertFalse(prefix.hasNext());
            engine.publishSession(state);
            Assert.assertEquals(6, state.get("counter"));
            Iterator<?> decrement = (Iterator<?>) engine.eval("g.inject(1, 2).map { counter-- }", state);
            Assert.assertEquals(6, decrement.next());
            Assert.assertEquals(5, decrement.next());
            Assert.assertFalse(decrement.hasNext());
            engine.publishSession(state);
            Assert.assertEquals(4, state.get("counter"));
            Iterator<?> prefixDecrement = (Iterator<?>) engine.eval("g.inject(1).map { --counter }", state);
            Assert.assertEquals(3, prefixDecrement.next());
            Assert.assertFalse(prefixDecrement.hasNext());
            engine.publishSession(state);
            Assert.assertEquals(3, state.get("counter"));
            Iterator<?> primitives = (Iterator<?>) engine.eval(
                    "g.inject(1).map { int value = 3; [value++, ++value, value--, --value, value] }", state);
            Assert.assertEquals(List.of(3, 5, 5, 3, 3), primitives.next());
            Assert.assertFalse(primitives.hasNext());
            engine.publishSession(state);
            Assert.assertEquals(3, state.get("counter"));
        }
    }

    @Test
    public void testSessionTransactionOverlayIsNarrow() throws Exception {
        Set<String> base = new ScriptMethodPolicy(ScriptExecutionProfile.QUERY).signatures();
        Set<String> overlay = new java.util.HashSet<>(new ScriptMethodPolicy(
                ScriptExecutionProfile.QUERY, true).signatures());
        overlay.removeAll(base);
        Assert.assertEquals(Set.of(
                "org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource#tx()",
                "org.apache.tinkerpop.gremlin.structure.Graph#tx()",
                "org.apache.tinkerpop.gremlin.structure.Transaction#commit()",
                "org.apache.tinkerpop.gremlin.structure.Transaction#rollback()"), overlay);
        SimpleBindings state = new SimpleBindings(Map.of("g", EmptyGraph.instance().traversal()));
        try (PolicyScriptEngine session = new PolicyScriptEngine(ScriptExecutionProfile.QUERY, true);
             PolicyScriptEngine stateless = new PolicyScriptEngine(ScriptExecutionProfile.QUERY)) {
            Assert.assertNotNull(session.compile("g.tx().commit(); 1", state));
            Assert.assertNotNull(session.compile("g.tx().rollback(); 1", state));
            Assert.assertThrows(ScriptException.class, () -> stateless.compile("g.tx().commit(); 1", state));
            for (String method : List.of("open", "close", "createThreadedTx")) {
                Assert.assertThrows(ScriptException.class, () -> session.compile("g.tx()." + method + "(); 1", state));
            }
        }
    }

    @Test
    public void testManagerFailedInitializationUnregistersEngine() throws Exception {
        ObjectName name = new ObjectName("org.apache.hugegraph:type=ScriptPolicy,profile=QUERY");
        try (PolicyScriptEngine registered = new PolicyScriptEngine(ScriptExecutionProfile.QUERY)) {
            Object before = ManagementFactory.getPlatformMBeanServer().getAttribute(name, "ActiveEngines");
            SimpleBindings broken = new SimpleBindings() {
                @Override
                public Set<Map.Entry<String, Object>> entrySet() {
                    throw new IllegalStateException("test startup failure");
                }
            };
            Assert.assertThrows(IllegalStateException.class, () -> new PolicyGremlinScriptEngineManager(broken));
            Assert.assertEquals(before, ManagementFactory.getPlatformMBeanServer().getAttribute(name, "ActiveEngines"));
        }
    }

    @Test
    public void testStatelessEngineStillCopiesMutableInput() throws Exception {
        SimpleBindings input = new SimpleBindings(Map.of("values", List.of(1)));
        try (PolicyScriptEngine engine = new PolicyScriptEngine(ScriptExecutionProfile.QUERY)) {
            Assert.assertEquals(2, engine.eval("values.add(2); values.size()", input));
            Assert.assertEquals(List.of(1), input.get("values"));
        }
    }
}
