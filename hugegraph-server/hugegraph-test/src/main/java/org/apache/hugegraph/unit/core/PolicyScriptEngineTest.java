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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.script.CompiledScript;
import javax.script.ScriptException;
import javax.script.SimpleBindings;

import org.apache.hugegraph.security.script.PolicyScriptEngine;
import org.apache.hugegraph.security.script.ScriptBindings;
import org.apache.hugegraph.security.script.ScriptElementView;
import org.apache.hugegraph.security.script.ScriptExecutionProfile;
import org.apache.tinkerpop.gremlin.process.traversal.Bytecode;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.structure.util.empty.EmptyGraph;
import org.apache.tinkerpop.gremlin.structure.util.CloseableIterator;
import org.apache.tinkerpop.gremlin.util.function.Lambda;
import org.junit.Assert;
import org.junit.Test;

public class PolicyScriptEngineTest {

    @Test
    public void testEngineRejectsAstTestBeforeSideEffect() throws Exception {
        String key = "hugegraph.script.policy.engine-ast-test";
        String old = System.getProperty(key);
        try {
            System.clearProperty(key);
            try (PolicyScriptEngine engine = new PolicyScriptEngine(ScriptExecutionProfile.QUERY)) {
                Assert.assertThrows(ScriptException.class, () -> engine.eval(
                        "@groovy.transform.ASTTest(value={ System.setProperty('" + key +
                        "', 'executed') }) def value = 1; value",
                        new SimpleBindings()));
            }
            Assert.assertNull(System.getProperty(key));
        } finally {
            if (old == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, old);
            }
        }
    }

    @Test
    public void testStaticCompilationAndParameterTypes() throws Exception {
        try (PolicyScriptEngine engine = new PolicyScriptEngine(ScriptExecutionProfile.QUERY)) {
            Assert.assertEquals(3, engine.eval("x + 1", new SimpleBindings(Map.of("x", 2))));
            Assert.assertEquals(5, engine.eval("x + 1", new SimpleBindings(Map.of("x", 4))));
            Assert.assertEquals(1L, engine.compilationCount());
            Assert.assertEquals("a1", engine.eval("x + 1", new SimpleBindings(Map.of("x", "a"))));
            Assert.assertEquals(2L, engine.compilationCount());
        }
    }

    @Test
    public void testGremlinAndBusinessClosures() throws Exception {
        try (PolicyScriptEngine engine = new PolicyScriptEngine(ScriptExecutionProfile.QUERY)) {
            SimpleBindings bindings = new SimpleBindings(Map.of("g", EmptyGraph.instance().traversal()));
            Assert.assertEquals(0L, engine.eval("g.V().count().next()", bindings));
            Assert.assertEquals(List.of(2, 3, 4), engine.eval(
                    "g.inject(1, 2, 3).map { it.get() + 1 }.toList()", bindings));
            Assert.assertEquals(List.of(3, 2), engine.eval(
                    "g.inject(1, 2, 3).is(gt(1)).order().by(Order.desc).toList()", bindings));
            Assert.assertEquals(6, engine.eval(
                    "List<Integer> values = []; for (int i = 0; i < 3; i++) { values.add(i) }; " +
                    "values.collect { it + 1 }.sum()", new SimpleBindings()));
            Assert.assertEquals(List.of(2, 3), engine.eval(
                    "[1, 2, 3].findAll { it > 1 }", new SimpleBindings()));
            Assert.assertEquals(2, engine.eval(
                    "[1, 2, 3].groupBy { it > 1 }.size()", new SimpleBindings()));
        }
    }

    @Test
    public void testStaticImportsUseTheSameMethodPolicy() throws Exception {
        try (PolicyScriptEngine engine = new PolicyScriptEngine(ScriptExecutionProfile.QUERY)) {
            Assert.assertEquals(2, engine.eval("import static java.lang.Math.abs; abs(-2)", new SimpleBindings()));
            Assert.assertEquals(2, engine.eval("import static java.lang.Math.*; abs(-2)", new SimpleBindings()));
            Assert.assertEquals(2, engine.eval("import static java.lang.Math.abs as magnitude; magnitude(-2)",
                                              new SimpleBindings()));
            Assert.assertThrows(ScriptException.class, () -> engine.eval(
                    "import static java.lang.Math.random; random()", new SimpleBindings()));
            Assert.assertThrows(ScriptException.class, () -> engine.eval(
                    "import static java.lang.System.setProperty; setProperty('hg.policy.unexpected', 'yes')",
                    new SimpleBindings()));
            Assert.assertNull(System.getProperty("hg.policy.unexpected"));
        }
    }

    @Test
    public void testDeniedCallsAndIndirectAccess() throws Exception {
        try (PolicyScriptEngine engine = new PolicyScriptEngine(ScriptExecutionProfile.QUERY)) {
            for (String source : List.of(
                    "@Grab('invalid:blocked:0') def x = 1; x", "System.exit(0)",
                    "System.setProperty('hg.policy.unexpected', 'yes')",
                    "new File('/tmp/hg-policy-must-not-create')",
                    "'id'.execute()", "Class.forName('java.lang.Runtime')",
                    "this.binding", "this", "getBinding()", "'x'.class", "'x'.getClass()",
                    "'x'.metaClass", "def f = { 1 }; f.owner", "def f = { 1 }; f.delegate",
                    "'x'.&toString", "'x'.('get' + 'Class')()", "evaluate('1+1')",
                    "Thread.currentThread()", "System.out", "String", "__hgDeadline = 1",
                    "File f = ['/tmp/hg-policy-must-not-create']; f",
                    "ProcessBuilder p = [['id']]; p", "Runnable r = { 1 }; r",
                    "def f = { File x -> x }; 1", "'a' =~ 'a'", "~'a'",
                    "int[] values = new int[1]; values", "Class<String> x = null; x",
                    "def x = 'a'; \"${x}\"", "[1, 2, 3].groupBy('class')",
                    "package hidden; 1", "import static java.lang.System.exit; exit(0)")) {
                Assert.assertThrows(source, ScriptException.class,
                                    () -> engine.eval(source, new SimpleBindings()));
            }
            Assert.assertNull(System.getProperty("hg.policy.unexpected"));
        }
    }

    @Test
    public void testBindingsAreCopiedAndArbitraryObjectsRejected() throws Exception {
        try (PolicyScriptEngine engine = new PolicyScriptEngine(ScriptExecutionProfile.QUERY)) {
            List<Integer> values = new ArrayList<>(List.of(1));
            SimpleBindings bindings = new SimpleBindings(Map.of("values", values));
            Assert.assertEquals(2, engine.eval("values.add(2); values.size()", bindings));
            Assert.assertEquals(List.of(1), values);
            Assert.assertThrows(ScriptException.class, () -> engine.eval("x",
                    new SimpleBindings(Map.of("x", System.class))));
            Map<String, Object> cycle = new HashMap<>();
            cycle.put("self", cycle);
            Assert.assertThrows(IllegalArgumentException.class,
                                () -> ScriptBindings.client(cycle));
        }
    }

    @Test
    public void testStoreProfileIsReadOnlyAndBoolean() throws Exception {
        try (PolicyScriptEngine engine = new PolicyScriptEngine(ScriptExecutionProfile.STORE_FILTER)) {
            SimpleBindings bindings = new SimpleBindings(Map.of("element",
                    new ScriptElementView("v1", "person", Map.of("age", 20))));
            Assert.assertEquals(true, engine.eval("element.id().equals('v1')", bindings));
            Assert.assertEquals(true, engine.eval("(int) element.property('age') > 18", bindings));
            Assert.assertEquals(true, engine.eval("element.property('missing') == null", bindings));
            Assert.assertEquals(false, engine.eval("element.property('missing') != null", bindings));
            Assert.assertEquals(true, engine.eval("!element.properties().containsKey('missing')", bindings));
            Assert.assertThrows(ScriptException.class, () -> engine.eval(
                    "element.property('missing')", bindings));
            for (String source : List.of("element", "element.properties().put('age', 1)",
                    "element.properties()['age'] = 1; true", "while (true) {}; true",
                    "def f = { true }; f()")) {
                Assert.assertThrows(source, ScriptException.class, () -> engine.eval(source, bindings));
            }
        }
    }

    @Test
    public void testCustomContainersAreRejectedBeforeCallbacks() {
        AtomicBoolean called = new AtomicBoolean();
        Map<String, Object> custom = new HashMap<>() {
            @Override
            public Set<Map.Entry<String, Object>> entrySet() {
                called.set(true);
                return super.entrySet();
            }
        };
        Assert.assertThrows(IllegalArgumentException.class,
                            () -> ScriptBindings.client(Map.of("value", custom)));
        Assert.assertFalse(called.get());
    }

    @Test
    public void testLoopRespondsToInterruption() throws Exception {
        try (PolicyScriptEngine engine = new PolicyScriptEngine(ScriptExecutionProfile.QUERY)) {
            engine.compile("while (true) {}", new SimpleBindings());
            AtomicReference<Throwable> failure =
                    new AtomicReference<>();
            Thread runner = new Thread(() -> {
                try {
                    engine.eval("while (true) {}", new SimpleBindings());
                } catch (Throwable error) {
                    failure.set(error);
                }
            });
            runner.setDaemon(true);
            runner.start();
            boolean executing = false;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (runner.isAlive() && System.nanoTime() < deadline) {
                if (Arrays.stream(runner.getStackTrace()).anyMatch(frame ->
                        frame.getClassName().contains("HugeGraphPolicyScript"))) {
                    executing = true;
                    break;
                }
                Thread.sleep(10);
            }
            runner.interrupt();
            runner.join(2000);
            Assert.assertTrue("loop must actually execute before cancellation", executing);
            Assert.assertFalse("loop must stop after interruption", runner.isAlive());
            Assert.assertTrue(failure.get() instanceof ScriptException);
            Assert.assertEquals("SCRIPT_EXECUTION_TIMEOUT", failure.get().getMessage());
            Assert.assertEquals(1L, engine.metrics().get("executionTimeouts").longValue());
        }
    }

    @Test
    public void testSourceCannotOverrideServerTimeout() throws Exception {
        try (PolicyScriptEngine engine = new PolicyScriptEngine(ScriptExecutionProfile.QUERY)) {
            String source = "\"g.with('evaluationTimeout', 0)\"; 1";
            ScriptException error = Assert.assertThrows(ScriptException.class,
                    () -> engine.eval(source, new SimpleBindings()));
            Assert.assertEquals("SCRIPT_TIMEOUT_OVERRIDE_DENIED", error.getMessage());
            Assert.assertEquals(0L, engine.compilationCount());
        }
    }

    @Test
    public void testIndexSyntaxCannotAccessBeanProperties() throws Exception {
        try (PolicyScriptEngine engine = new PolicyScriptEngine(ScriptExecutionProfile.QUERY)) {
            SimpleBindings bindings = new SimpleBindings(Map.of("g", EmptyGraph.instance().traversal()));
            for (String source : List.of("g['graph']", "g['class']", "[g]['graph']",
                                         "'x'.getAt('class')", "g['graph'] = null; 1")) {
                Assert.assertThrows(source, ScriptException.class, () -> engine.compile(source, bindings));
            }
            Assert.assertEquals("value", engine.eval("['class': 'value']['class']", new SimpleBindings()));
            Assert.assertEquals(2, engine.eval("[1, 2, 3][1]", new SimpleBindings()));
        }
    }

    @Test
    public void testConcurrentRequestsShareCodeButNotBindings() throws Exception {
        try (PolicyScriptEngine engine = new PolicyScriptEngine(ScriptExecutionProfile.QUERY)) {
            ExecutorService pool = Executors.newFixedThreadPool(4);
            try {
                List<Future<Object>> results = new ArrayList<>();
                for (int i = 0; i < 24; i++) {
                    int value = i;
                    results.add(pool.submit(() -> engine.eval("value + 1",
                            new SimpleBindings(Map.of("value", value)))));
                }
                for (int i = 0; i < results.size(); i++) {
                    Assert.assertEquals(i + 1, results.get(i).get());
                }
                Assert.assertEquals(1L, engine.compilationCount());
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    public void testPreparedScriptsCheckBindingTypesAndClose() throws Exception {
        PolicyScriptEngine engine = new PolicyScriptEngine(ScriptExecutionProfile.QUERY);
        CompiledScript script = engine.compile("value + 1",
                new SimpleBindings(Map.of("value", 1)));
        Assert.assertEquals(3, script.eval(new SimpleBindings(Map.of("value", 2))));
        Assert.assertThrows(ScriptException.class,
                            () -> script.eval(new SimpleBindings(Map.of("value", "different type"))));
        engine.close();
        Assert.assertThrows(ScriptException.class,
                            () -> script.eval(new SimpleBindings(Map.of("value", 2))));
    }

    @Test
    public void testResultsCannotCarryExecutionObjects() throws Exception {
        try (PolicyScriptEngine engine = new PolicyScriptEngine(ScriptExecutionProfile.QUERY)) {
            SimpleBindings bindings = new SimpleBindings(Map.of("g", EmptyGraph.instance().traversal()));
            for (String source : List.of("g", "[g]", "P.eq(g)", "g.inject(g).tryNext()",
                                         "g.inject(g).path().next()")) {
                Assert.assertThrows(source, ScriptException.class, () -> engine.eval(source, bindings));
            }
            Traversal<?, ?> traversal =
                    (Traversal<?, ?>)
                            engine.eval("g.inject(g)", bindings);
            Assert.assertThrows(IllegalArgumentException.class, traversal::next);
            traversal.close();
        }
    }

    @Test(timeout = 10000L)
    public void testSharedResultExpansionHasBoundedWork() throws Exception {
        try (PolicyScriptEngine engine = new PolicyScriptEngine(ScriptExecutionProfile.QUERY)) {
            CompiledScript script = engine.compile(
                    "List a = [1]; for (int i = 0; i < 40; i++) { a = [a, a] }; a",
                    new SimpleBindings());
            ScriptException error = Assert.assertThrows(ScriptException.class,
                    () -> script.eval(new SimpleBindings()));
            Assert.assertTrue(error.getMessage().startsWith("SCRIPT_EXECUTION_FAILED"));
            Assert.assertEquals(Arrays.asList(List.of(1), List.of(1)),
                    engine.eval("List a = [1]; [a, a]", new SimpleBindings()));
        }
    }

    @Test
    public void testClosedEngineRejectsCachedScripts() throws Exception {
        PolicyScriptEngine engine = new PolicyScriptEngine(ScriptExecutionProfile.QUERY);
        Assert.assertEquals(2, engine.eval("1 + 1", new SimpleBindings()));
        engine.close();
        Assert.assertThrows(ScriptException.class, () -> engine.eval("1 + 1", new SimpleBindings()));
    }

    @Test
    public void testForEachDeclaredTypeCannotCreateIoObjects() throws Exception {
        Path marker = Files.createTempFile("hg-policy-foreach-", ".marker");
        Files.deleteIfExists(marker);
        try (PolicyScriptEngine engine = new PolicyScriptEngine(ScriptExecutionProfile.QUERY)) {
            SimpleBindings bindings = new SimpleBindings(Map.of("path", marker.toString()));
            Assert.assertThrows(ScriptException.class, () -> engine.eval(
                    "List values = [[path]]; for (java.io.FileWriter out : values) { 1 }; 1",
                    bindings));
            Assert.assertFalse(Files.exists(marker));
        } finally {
            Files.deleteIfExists(marker);
        }
    }

    @Test
    public void testIterateAndPartialConsumptionReturnRemainingResults() throws Exception {
        try (PolicyScriptEngine engine = new PolicyScriptEngine(ScriptExecutionProfile.QUERY)) {
            SimpleBindings bindings = new SimpleBindings(
                    Map.of("g", EmptyGraph.instance().traversal()));
            Traversal<?, ?> iterated = (Traversal<?, ?>) engine.eval(
                    "g.inject(1, 2, 3).iterate()", bindings);
            Assert.assertFalse(iterated.hasNext());
            iterated.close();

            Traversal<?, ?> remaining = (Traversal<?, ?>) engine.eval(
                    "def t = g.inject(1, 2, 3); t.next(); t", bindings);
            Assert.assertEquals(2, remaining.next());
            Assert.assertEquals(3, remaining.next());
            Assert.assertFalse(remaining.hasNext());
            remaining.close();
            remaining.close();
        }
    }

    @Test
    public void testOrdinaryIteratorStreamsCheckedValuesAndCloses() throws Exception {
        try (PolicyScriptEngine engine = new PolicyScriptEngine(ScriptExecutionProfile.QUERY)) {
            Iterator<?> values = (Iterator<?>) engine.eval("[1, 2].iterator()", new SimpleBindings());
            Assert.assertEquals(1, values.next());
            Assert.assertEquals(2, values.next());
            Assert.assertFalse(values.hasNext());
            CloseableIterator.closeIterator(values);
            Assert.assertThrows(ScriptException.class, () -> engine.eval(
                    "[[1, 2].iterator()]", new SimpleBindings()));

            Iterator<?> denied = (Iterator<?>) engine.eval("[g].iterator()", new SimpleBindings(
                    Map.of("g", EmptyGraph.instance().traversal())));
            Assert.assertThrows(IllegalArgumentException.class, denied::next);
            Assert.assertFalse(denied.hasNext());
        }
        Class<?> results = Class.forName("org.apache.hugegraph.security.script.ScriptResults");
        java.lang.reflect.Method prepare = results.getDeclaredMethod("prepare", Object.class);
        prepare.setAccessible(true);
        for (boolean invalid : new boolean[]{false, true}) {
            int[] calls = new int[2];
            CloseableIterator<Object> source = new CloseableIterator<>() {
                @Override
                public boolean hasNext() {
                    return true;
                }

                @Override
                public Object next() {
                    calls[0]++;
                    return invalid ? System.class : 1;
                }

                @Override
                public void close() {
                    calls[1]++;
                }
            };
            Iterator<?> checked = (Iterator<?>) prepare.invoke(null, source);
            Assert.assertEquals(0, calls[0]);
            if (invalid) {
                Assert.assertThrows(IllegalArgumentException.class, checked::next);
            } else {
                Assert.assertEquals(1, checked.next());
            }
            CloseableIterator.closeIterator(checked);
            CloseableIterator.closeIterator(checked);
            Assert.assertEquals(1, calls[0]);
            Assert.assertEquals(1, calls[1]);
        }
    }

    @Test
    public void testBytecodeEvalUsesTranslatorAndRejectsLambda() throws Exception {
        try (PolicyScriptEngine engine = new PolicyScriptEngine(ScriptExecutionProfile.QUERY)) {
            SimpleBindings bindings = new SimpleBindings(
                    Map.of("g", EmptyGraph.instance().traversal()));
            Bytecode bytecode = new Bytecode();
            bytecode.addStep("V");
            bytecode.addStep("count");
            Traversal.Admin<?, ?> traversal = engine.eval(bytecode, bindings, "g");
            Assert.assertEquals(0L, traversal.next());
            traversal.close();

            Bytecode lambda = new Bytecode();
            lambda.addStep("map", Lambda.function("it.get()"));
            ScriptException error = Assert.assertThrows(ScriptException.class,
                    () -> engine.eval(lambda, bindings, "g"));
            Assert.assertEquals("SCRIPT_BYTECODE_LAMBDA_DENIED", error.getMessage());
        }
    }
}
