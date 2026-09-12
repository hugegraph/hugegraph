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

import javax.script.ScriptException;
import javax.script.SimpleBindings;

import org.apache.hugegraph.auth.PolicyGremlinScriptEngineManager;
import org.apache.hugegraph.security.script.PolicyScriptEngine;
import org.apache.hugegraph.security.script.ScriptExecutionProfile;
import org.apache.hugegraph.security.script.ScriptMethodPolicy;
import org.apache.tinkerpop.gremlin.structure.util.empty.EmptyGraph;
import org.junit.Assert;
import org.junit.Test;

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
