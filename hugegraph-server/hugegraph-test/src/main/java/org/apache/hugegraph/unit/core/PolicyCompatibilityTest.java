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

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.script.ScriptException;
import javax.script.SimpleBindings;

import org.apache.hugegraph.security.script.PolicyScriptEngine;
import org.apache.hugegraph.security.script.ScriptBindings;
import org.apache.hugegraph.security.script.ScriptDataOperations;
import org.apache.hugegraph.security.script.ScriptElementView;
import org.apache.hugegraph.security.script.ScriptExecutionProfile;
import org.apache.tinkerpop.gremlin.groovy.jsr223.GremlinGroovyScriptEngine;
import org.apache.tinkerpop.gremlin.process.traversal.util.TraversalMetrics;
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph;
import org.junit.Assert;
import org.junit.Test;

public class PolicyCompatibilityTest {

    @Test
    public void testDataExpressionsAgainstOriginalEngine() throws Exception {
        GremlinGroovyScriptEngine original = new GremlinGroovyScriptEngine();
        try (TinkerGraph graph = TinkerGraph.open();
             PolicyScriptEngine policy = new PolicyScriptEngine(ScriptExecutionProfile.QUERY)) {
            graph.addVertex("age", 1);
            graph.addVertex("age", 2);
            for (String script : List.of(
                "[1,2,3].collect { it * 2 }",
                "[1,2,3].findAll { it > 1 }",
                "[1,2,3].groupBy { it % 2 }",
                "[[age:1],[age:2]].collect { it.age }",
                "rows.collect { it.age }",
                "rows.collect { Map row -> (int) row.get(\"age\") * 2 }",
                "xs.collect { it * 2 }",
                "xs.collect { int x -> x * 2 }",
                "[[k:\"a\",v:1],[k:\"b\",v:2]].collectEntries { [(it.k):it.v] }",
                "[1,2,3].count { it > 1 }",
                "[1,2,3].findResults { it > 1 ? it : null }",
                "[[age:1],[age:1]].unique { it.age }",
                "[[age:1],[age:2]].sum { it.age }",
                "[[age:2],[age:1]].sort { it.age }",
                "[1,2,3,4].collate(2)",
                "[[1,2],[3]].collectMany { it }",
                "[1,2,3].join(\",\")",
                "[[1,2],[3]].flatten()",
                "[1,2,3].inject(0) { a,b -> a+b }",
                "[1,2,3].reverse()",
                "def a=[1,2]; a -= [1]; a",
                "def a=\"${1}\"; a += \"${2}\"; a.toString()",
                "def a=\"${1}\"; a += \"${2}\"; a",
                "def a=\"${1}\"; a += '2'; a",
                "\"${1}\" + \"${2}\"",
                "def a='abc'; a -= 'b'; a",
                "def a=[1,2]; a *= 2; a",
                "def a=[1,2].toSet(); a += 3; a",
                "def a=[a:1,b:2]; a -= [a:1]; a",
                "def a=[1,2].toSet(); a -= [1]; a",
                "def a=[1,2].toSet(); a -= 1; a",
                "def a=[1,2]; a += [3,4]; a",
                "def a=[1,2]; a += [3]; a.add(4); a",
                "def a='a'; a += 'b'; a.length()",
                "def a=[a:1]; a += [b:2]; a.get('b')",
                "[1,1,2].toSet()",
                "(1..3).toList()",
                "int n=0; for (int x in [1,2,3]) { n += x }; n",
                "int n=0; while(n<3) {n++}; n",
                "def n=0; 3.times { n++ }; n",
                "def name=\"bob\"; \"hello ${name}\"",
                "\"hello \" + \"bob\"",
                "\"a,b\".split(\",\")",
                "\"a,b\".tokenize(\",\")",
                "\"abc\".replace(\"a\",\"x\")",
                "\"a1b2\".replaceAll(\"[0-9]\", \"\")",
                "\"abc\" ==~ /a.*/",
                "\"abc\"[0]",
                "[age:2].age",
                "[[age:1],[age:2]]*.age",
                "[\"a\",\"b\"]*.toUpperCase()",
                "new ArrayList()",
                "new Date(0)",
                "\"12\" as Integer",
                "Integer.parseInt(\"12\")",
                "1.25G + 2.50G",
                "1.255G.setScale(2, java.math.RoundingMode.HALF_UP)",
                "UUID.fromString(\"00000000-0000-0000-0000-000000000001\")",
                "new groovy.json.JsonSlurper().parseText(\"{\\\"a\\\":1}\")",
                "def twice(x) { x * 2 }; twice(3)",
                "def twice = { int x -> x * 2 }; twice(3)",
                "def (a,b)=[1,2]; a+b",
                "new int[2]",
                "Integer[] a=[1,2]; a += [3]; a",
                "Integer[] a=[1,2]; a += 3; a",
                "Integer[] a=[1,2]; Integer[] b=[3,4]; a += b; a",
                "[[age:1],[age:2]][\"age\"]",
                "def rows=[[age:1],[age:2]]; def key='age'; rows.collect { Map row -> row.get(key) }",
                "def a=org.apache.hugegraph.util.Blob.wrap(new byte[]{1}); " +
                "def b=org.apache.hugegraph.util.Blob.wrap(new byte[]{2}); a <=> b",
                "def a=org.apache.hugegraph.util.Blob.wrap(new byte[]{1}); " +
                "def b=org.apache.hugegraph.util.Blob.wrap(new byte[]{2}); a < b",
                "g.V().count().next()",
                "g.V().values(\"age\").map { it.get() * 2 }.toList()",
                "g.V().values(\"age\").map { (int) it.get() * 2 }.toList()",
                "g.V().valueMap().toList().collect { it.age }",
                "g.V().next().label",
                "g.V().next().label()",
                "g.withBulk(false).V().count().next()",
                "[\"class\":\"x\"].get(\"class\")",
                "[\"class\":\"x\"].class",
                "xs.collect { (int) it * 2 }",
                "rows.collect { ((Map) it).get(\"age\") }",
                "rows.collect { def row = (Map) it; (int) row.get(\"age\") * 2 }",
                "xs = []; xs",
                "x = 1; x + 1",
                "def k=\"age\"; def row=[age:1]; row[k]",
                "Math.PI",
                "String.format(\"n=%d\", 2)",
                "[a:1].entrySet().collect { it.key }",
                "[a:1].entrySet().collect { it.getKey() }",
                "[1,2,3][0..1]")) {
                SimpleBindings before = bindings(graph);
                SimpleBindings after = bindings(graph);
                Object expected = original.eval(script, before);
                Object actual = policy.eval(script, after);
                assertValue(script, expected, actual);
            }
            Assert.assertTrue(policy.eval("UUID.randomUUID()", bindings(graph)) instanceof UUID);
            Assert.assertTrue(policy.eval("g.V().profile().next()", bindings(graph)) instanceof TraversalMetrics);
        }
    }

    @Test
    public void testCacheWithEmptyMixedAndChangedData() throws Exception {
        try (PolicyScriptEngine engine = new PolicyScriptEngine(ScriptExecutionProfile.QUERY)) {
            String source = "xs.collect { it * 2 }";
            Assert.assertEquals(List.of(), engine.eval(source, new SimpleBindings(Map.of("xs", List.of()))));
            assertValue(source, List.of(2, 4L, new BigDecimal("5.0")), engine.eval(source,
                    new SimpleBindings(Map.of("xs", List.of(1, 2L, new BigDecimal("2.5"))))));
            Assert.assertEquals(List.of(6), engine.eval(source, new SimpleBindings(Map.of("xs", List.of(3)))));
            Assert.assertEquals(1L, engine.compilationCount());
            Assert.assertEquals(List.of(), engine.eval("rows.collect { it.age }",
                    new SimpleBindings(Map.of("rows", List.of()))));
            List<Object> expected = new ArrayList<>();
            expected.add(1);
            expected.add(null);
            expected.add("unknown");
            Assert.assertEquals(expected, engine.eval("rows.collect { it.age }", new SimpleBindings(Map.of(
                    "rows", List.of(Map.of("age", 1), Map.of(), Map.of("age", "unknown"))))));
            Map<String, Object> missing = new LinkedHashMap<>();
            missing.put("row", null);
            Assert.assertNull(engine.eval("row?.age", new SimpleBindings(missing)));
        }
    }

    @Test
    public void testHelperReturnTypesArraysJsonAndRegex() throws Exception {
        GremlinGroovyScriptEngine original = new GremlinGroovyScriptEngine();
        try (PolicyScriptEngine policy = new PolicyScriptEngine(ScriptExecutionProfile.QUERY)) {
            for (String script : List.of(
                    "int add(int n=2) { n+1 }; add()",
                    "int half(int n) { n / 2 }; half(3)", "void f(){ 123 }; f()",
                    "def fact(int n) { n <= 1 ? 1 : n * fact(n-1) }; fact(4)",
                    "def a(int n) { b(n) }; def b(int n) { n+1 }; a(2)",
                    "int[] a=[1,2]; a[0]=3; a[-1]+a[0]",
                    "[null,[name:'Ada']]['name']", "[null,[name:'Ada']]*.name",
                    "def m=null; m?['name']",
                    "Object value=null; value.toString()", "Object value=null; value?.toString()",
                    "Object value=[1,2]; value.toString()",
                    "def n=0; def read={ n++; null }; read()?['name']; n",
                    "def n=0; def read={ n++; [name:'Ada'] }; [read()?['name'], n]",
                    "['a', 'b']*.matches('a')", "String a=null; a?.matches('a')",
                    "if ('abc' =~ 'a.*') { 1 } else { 0 }", "null ==~ 'null'", "'null' ==~ null",
                    "def b='b'; 'a' < \"${b}\"",
                    "def who='Ada'; groovy.json.JsonOutput.toJson([name:\"${who}\"])",
                    "groovy.json.JsonOutput.toJson([a:1])",
                    "groovy.json.JsonOutput.toJson([a:1].values())",
                    "groovy.json.JsonOutput.toJson([a:1].keySet())",
                    "groovy.json.JsonOutput.toJson([1,2].subList(0,1))",
                    "import static groovy.json.JsonOutput.toJson; toJson([a:1])")) {
                assertValue(script, original.eval(script), policy.eval(script));
            }
        }
    }

    @Test
    public void testLazyInterpolationCannotEscapeExecution() throws Exception {
        try (TinkerGraph graph = TinkerGraph.open();
             PolicyScriptEngine policy = new PolicyScriptEngine(ScriptExecutionProfile.QUERY)) {
            for (String script : List.of("\"${ -> g.addV('person').next() }\"",
                    "[text: \"${ -> g.addV('person').next() }\"]")) {
                Assert.assertThrows(script, ScriptException.class, () -> policy.eval(script, bindings(graph)));
                Assert.assertEquals(0L, graph.traversal().V().count().next().longValue());
            }
            Assert.assertEquals("n=2", policy.eval("\"n=${1+1}\"").toString());
            Assert.assertThrows(ScriptException.class, () -> policy.eval(
                    "groovy.json.JsonOutput.toJson(g)", bindings(graph)));
        }
    }

    @Test
    public void testBindingTypesAndCopies() {
        Date date = new Date(1000L);
        Set<Integer> values = new LinkedHashSet<>(List.of(1, 2));
        byte[] bytes = new byte[]{1, 2};
        Map<String, Object> copied = ScriptBindings.client(Map.of("date", date, "values", values, "bytes", bytes));
        Assert.assertEquals(date, copied.get("date"));
        Assert.assertNotSame(date, copied.get("date"));
        Assert.assertTrue(copied.get("values") instanceof Set);
        Assert.assertArrayEquals(bytes, (byte[]) copied.get("bytes"));
        Assert.assertNotSame(bytes, copied.get("bytes"));
    }

    @Test
    public void testStoreDataClosuresAndMissingValues() throws Exception {
        try (PolicyScriptEngine engine = new PolicyScriptEngine(ScriptExecutionProfile.STORE_FILTER)) {
            SimpleBindings bindings = new SimpleBindings(Map.of("element", new ScriptElementView("1", "person",
                    Map.of("ages", List.of(10, 20)))));
            Assert.assertEquals(true, engine.eval(
                    "((List) element.property('ages')).any { it > 18 }", bindings));
            Assert.assertEquals(true, engine.eval("element.property('missing') == null", bindings));
            Assert.assertThrows(ScriptException.class, () -> engine.eval("assert false; true", bindings));
            Assert.assertEquals(true, engine.eval("element.properties()['ages'] == [10,20]", bindings));
            Assert.assertEquals(true, engine.eval("element.properties()['missing'] == null", bindings));
            Assert.assertEquals(true, engine.eval("element.properties().getAt('ages') == [10,20]", bindings));
            Assert.assertEquals(true, engine.eval(
                    "def total=0; ((List) element.property('ages')).each { total += it }; total == 30", bindings));
            Assert.assertEquals(true, engine.eval("def values=[]; values.add(1); values.size() == 1", bindings));
            Assert.assertThrows(ScriptException.class, () -> engine.eval(
                    "element.properties().put('ages', []); true", bindings));
        }
    }

    @Test
    public void testCompatibilityDoesNotExposeExecutionCapabilities() throws Exception {
        AtomicBoolean called = new AtomicBoolean();
        Map<String, Object> custom = new LinkedHashMap<>() {
            @Override
            public Object get(Object key) {
                called.set(true);
                return null;
            }
        };
        Assert.assertThrows(IllegalArgumentException.class, () -> ScriptDataOperations.propertyMap(custom));
        Assert.assertFalse(called.get());
        try (PolicyScriptEngine engine = new PolicyScriptEngine(ScriptExecutionProfile.QUERY)) {
            for (String script : List.of(
                    "def helper() { System.setProperty('hg.compatibility.escape', 'yes') }; helper()",
                    "new File('/tmp/hg-compatibility-should-not-exist').createNewFile()",
                    "new groovy.json.JsonSlurper().parse(new URL('http://127.0.0.1/'))",
                    "new ArrayList().class.classLoader", "'x' as Class", "'x'.metaClass",
                    "def rows = [['x']]; rows*.class", "def a = { 1 }; a.owner",
                    "org.apache.hugegraph.security.script.ScriptDataOperations.propertyMap([a:1])",
                    "def run() { 1 }; 1")) {
                Assert.assertThrows(script, ScriptException.class, () -> engine.eval(script, new SimpleBindings()));
            }
            Assert.assertNull(System.getProperty("hg.compatibility.escape"));
        }
    }

    private static SimpleBindings bindings(TinkerGraph graph) {
        return new SimpleBindings(new LinkedHashMap<>(Map.of("g", graph.traversal(),
                "xs", List.of(1, 2, 3), "rows", List.of(Map.of("age", 1), Map.of("age", 2)))));
    }

    private static void assertValue(String script, Object expected, Object actual) {
        if (expected != null && expected.getClass().isArray()) {
            Assert.assertEquals(script, expected.getClass(), actual.getClass());
            Assert.assertEquals(script, Array.getLength(expected), Array.getLength(actual));
            for (int i = 0; i < Array.getLength(expected); i++) {
                assertValue(script, Array.get(expected, i), Array.get(actual, i));
            }
        } else if (expected instanceof List) {
            Assert.assertTrue(script, actual instanceof List);
            Assert.assertEquals(script, ((List<?>) expected).size(), ((List<?>) actual).size());
            for (int i = 0; i < ((List<?>) expected).size(); i++) {
                assertValue(script, ((List<?>) expected).get(i), ((List<?>) actual).get(i));
            }
        } else if (expected instanceof Map) {
            Assert.assertTrue(script, actual instanceof Map);
            Assert.assertEquals(script, ((Map<?, ?>) expected).keySet(), ((Map<?, ?>) actual).keySet());
            ((Map<?, ?>) expected).forEach((key, value) -> assertValue(script, value, ((Map<?, ?>) actual).get(key)));
        } else {
            Assert.assertEquals(script, expected, actual);
            if (expected instanceof Number) {
                Assert.assertEquals(script, expected.getClass(), actual.getClass());
            }
        }
    }
}
