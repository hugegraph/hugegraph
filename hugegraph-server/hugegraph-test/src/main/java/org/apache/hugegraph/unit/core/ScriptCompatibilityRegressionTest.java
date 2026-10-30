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
import java.util.Map;
import java.util.UUID;

import javax.script.ScriptException;
import javax.script.SimpleBindings;

import org.apache.hugegraph.security.script.PolicyScriptEngine;
import org.apache.hugegraph.security.script.ScriptDataOperations;
import org.apache.hugegraph.security.script.ScriptExecutionProfile;
import org.junit.Assert;
import org.junit.Test;

public class ScriptCompatibilityRegressionTest {

    @Test
    public void testRejectedSpreadClosesItsCursor() {
        java.util.concurrent.atomic.AtomicInteger closed = new java.util.concurrent.atomic.AtomicInteger();
        org.apache.tinkerpop.gremlin.structure.util.CloseableIterator<String> cursor =
                new org.apache.tinkerpop.gremlin.structure.util.CloseableIterator<>() {
                    @Override
                    public boolean hasNext() {
                        return true;
                    }

                    @Override
                    public String next() {
                        return "x";
                    }

                    @Override
                    public void close() {
                        closed.incrementAndGet();
                    }
                };
        IllegalArgumentException error = Assert.assertThrows(IllegalArgumentException.class, () -> {
            for (Object ignored : ScriptDataOperations.boundedValues(cursor)) {
                // Exceed the item budget without allocating a large source collection.
            }
        });
        Assert.assertEquals("SCRIPT_DATA_ALLOCATION_LIMIT", error.getMessage());
        Assert.assertEquals(1, closed.get());
    }

    @Test
    public void testBoundedDataAllocationsAcrossProfiles() throws Exception {
        for (ScriptExecutionProfile profile : ScriptExecutionProfile.values()) {
            try (PolicyScriptEngine engine = new PolicyScriptEngine(profile)) {
                for (String source : List.of(
                        "def s='a'; \"${s}\".repeat(2) == 'aa'",
                        "def f(Object x) { x ** -1 }; f(BigDecimal.TEN) == 0.1G",
                        "def f(Object x) { x ** -1 }; f(BigInteger.TEN) == 0.1G",
                        "def x=BigDecimal.TEN; x **= -1; x == 0.1G",
                        "String.format('%s',(Object[])null) == 'null'",
                        "String.format('ok',(Object[])null) == 'ok'",
                        "def i=1; 'abcd'[i..<i] == '' && [0,1,2,3][i..<i] == []",
                        "def i=1; 'abcd'[i<..i] == '' && [0,1,2,3][i<..i] == []",
                        "Integer i=-1; 'abcd'[i..<i] == '' && [0,1,2,3][i..<i] == []")) {
                    Assert.assertEquals(profile + ": " + source, true, engine.eval(source, new SimpleBindings()));
                }
                if (profile == ScriptExecutionProfile.STORE_FILTER) {
                    for (String source : List.of(
                            "'ab'.repeat(3) == 'ababab'", "BigInteger.TWO.pow(8) == 256G",
                            "'ab'.padLeft(-1) == 'ab' && 'ab'.padRight(-1) == 'ab' && 'ab'.center(-1) == 'ab'",
                            "String.format('%.3s', 'x'.repeat(33000)) == 'xxx'",
                            "BigDecimal.TEN.pow(3) == 1000G", "BigDecimal.valueOf(1L,2) == 0.01G",
                            "['x','y']*.repeat(3) == ['xxx','yyy']",
                            "[new BigDecimal('1.2'),null]*.setScale(3) == [1.200G,null]",
                            "(true ? [1.0G] : [2.0G])*.setScale(3) == [1.000G]",
                            "String s=null; int n=0; s?.repeat(n++); n == 0",
                            "(1..3).toList() == [1,2,3]", "(1L..3L).sum() == 6L",
                            "('aa'..'ac').toList() == ['aa','ab','ac']",
                            "'abcd'[1..<1] == ''", "[0,1,2,3][1..<1] == []",
                            "new IntRange(1,4).contains(2)", "new int[3] == ([0,0,0] as int[])",
                            "def twice(Object x) { x * 2 }; twice('ab') == 'abab'")) {
                        Assert.assertEquals(source, true, engine.eval(source, new SimpleBindings()));
                    }
                } else {
                    Assert.assertEquals("ababab", engine.eval("'ab'.repeat(3)", new SimpleBindings()));
                    Assert.assertEquals("xxx", engine.eval("def s='x'; s?.repeat(3)", new SimpleBindings()));
                    Assert.assertEquals(0, engine.eval("String s=null; int n=0; s?.repeat(n++); n", new SimpleBindings()));
                    Assert.assertEquals(List.of("xxx", "yyy"),
                            engine.eval("['x','y']*.repeat(3)", new SimpleBindings()));
                    Assert.assertEquals(java.util.Arrays.asList(new java.math.BigDecimal("1.200"), null,
                            new java.math.BigDecimal("3.400")), engine.eval(
                            "[new BigDecimal('1.2'),null,new BigDecimal('3.4')]*.setScale(3)", new SimpleBindings()));
                    Assert.assertEquals(new java.math.BigInteger("256"),
                            engine.eval("BigInteger.TWO.pow(8)", new SimpleBindings()));
                    Assert.assertEquals(new java.math.BigDecimal("1000"),
                            engine.eval("BigDecimal.TEN.pow(3)", new SimpleBindings()));
                    Assert.assertEquals(new java.math.BigDecimal("0.01"),
                            engine.eval("BigDecimal.valueOf(1L,2)", new SimpleBindings()));
                    Assert.assertEquals(List.of(1, 2, 3), engine.eval("(1..3).toList()", new SimpleBindings()));
                    Assert.assertEquals(List.of(3, 2), engine.eval("(3..<1).toList()", new SimpleBindings()));
                    Assert.assertEquals(List.of(2, 3), engine.eval("(1<..3).toList()", new SimpleBindings()));
                    Assert.assertEquals(List.of(2), engine.eval("(3<..<1).toList()", new SimpleBindings()));
                    Assert.assertEquals("", engine.eval("'abcd'[1<..<1]", new SimpleBindings()));
                    Assert.assertEquals(List.of(), engine.eval("[0,1,2,3][1<..<1]", new SimpleBindings()));
                    Assert.assertEquals(List.of("aa", "ab", "ac"),
                            engine.eval("('aa'..'ac').toList()", new SimpleBindings()));
                    Assert.assertEquals(6L, engine.eval("(1L..3L).sum()", new SimpleBindings()));
                    Assert.assertEquals(true, engine.eval("new IntRange(1,4).contains(2)", new SimpleBindings()));
                    Assert.assertEquals(1, engine.eval("def m=new HashMap(16); m.put('x',1); m.size()",
                                                       new SimpleBindings()));
                    Assert.assertArrayEquals(new int[3], (int[]) engine.eval("new int[3]", new SimpleBindings()));
                    Assert.assertEquals("abab", engine.eval(
                            "def twice(Object x) { x * 2 }; twice('ab')", new SimpleBindings()));
                    Assert.assertEquals(List.of(1, 2, 1, 2), engine.eval("def a=[1,2]; a *= 2; a", new SimpleBindings()));
                }
                for (String source : List.of("'x'.repeat(65537)", "BigInteger.TWO.pow(65537)",
                        "BigDecimal.TEN.pow(65537)", "new ArrayList(65537)", "new HashMap(65537)",
                        "new HashSet(65537)", "new int[65537]",
                        "new IntRange(0,65536).toList()", "(0..65536).sum()", "(0L..65536L).toList()",
                        "(65536..0).toList()", "'x'.padLeft(65537)", "String.format('%65537s','x')",
                        "BigDecimal.ONE.setScale(65537)", "1E+65537G.toPlainString()",
                        "BigDecimal.valueOf(1L,-65537).toPlainString()",
                        "BigDecimal.ONE.divide(BigDecimal.TEN,65537,java.math.RoundingMode.HALF_UP)",
                        "def x=1E-60000G; x.multiply(x).scale()",
                        "new BigDecimal('1E+65537').toBigInteger()",
                        "def grow(Object x) { x * 65537 }; grow('x')",
                        "def grow(Object x) { x * 65537 }; grow([1])")) {
                    ScriptException error = Assert.assertThrows(profile + ": " + source, ScriptException.class,
                            () -> engine.eval(source, new SimpleBindings()));
                    Throwable cause = error;
                    while (cause != null &&
                           !String.valueOf(cause.getMessage()).contains("SCRIPT_DATA_ALLOCATION_LIMIT")) {
                        cause = cause.getCause();
                    }
                    Assert.assertNotNull(profile + ": " + source + " -> " + error, cause);
                }
                for (String source : List.of("new HashMap(16,0.0001f)", "new int[2][2]")) {
                    Assert.assertThrows(ScriptException.class, () -> engine.eval(source, new SimpleBindings()));
                }
            }
        }
        Assert.assertThrows(IllegalArgumentException.class, () ->
                org.apache.hugegraph.security.script.ScriptBindings.client(
                        Map.of("value", new java.math.BigDecimal("1E+65537"))));
    }

    @Test
    public void testTypedLocalHelperReturnsAcrossProfiles() throws Exception {
        for (ScriptExecutionProfile profile : ScriptExecutionProfile.values()) {
            try (PolicyScriptEngine engine = new PolicyScriptEngine(profile)) {
                for (String source : List.of(
                        "byte helper() { 257 }; helper() == 1",
                        "short helper() { 65537 }; helper() == 1",
                        "char helper() { 65 }; helper() == 'A'",
                        "Byte helper() { 1 }; helper() == 1",
                        "Short helper() { 1 }; helper() == 1",
                        "Character helper() { Character.valueOf('A'.charAt(0)) }; helper() == 'A'",
                        "UUID helper() { UUID.fromString('00000000-0000-0000-0000-000000000001') }; " +
                        "helper().toString() == '00000000-0000-0000-0000-000000000001'",
                        "Number helper() { 1 }; helper() == 1",
                        "Object helper() { 1 }; helper() == 1",
                        "List<Byte> helper() { [] }; helper().isEmpty()",
                        "byte helper(boolean early) { if (early) { return 257 }; 258 }; " +
                        "helper(true) == 1 && helper(false) == 2",
                        "UUID helper() { null }; helper() == null",
                        "String helper() { 'abc' }; helper() == 'abc'",
                        "String helper() { \"v${1}\" }; helper() == 'v1'",
                        "IntRange helper() { new IntRange(1, 4) }; helper().contains(2)",
                        "Number helper(Number value) { value }; helper(1) == 1")) {
                    Assert.assertEquals(profile + ": " + source, true,
                                        engine.eval(source, new SimpleBindings()));
                }
            }
        }
    }

    @Test
    public void testTypedLocalHelperStillRejectsUnsafeTypesAndCalls() throws Exception {
        for (ScriptExecutionProfile profile : ScriptExecutionProfile.values()) {
            try (PolicyScriptEngine engine = new PolicyScriptEngine(profile)) {
                for (String source : List.of(
                        "java.io.File helper() { null }; helper() == null",
                        "Class helper() { null }; helper() == null",
                        "List<java.io.File> helper() { [] }; helper().isEmpty()",
                        "UUID helper() { UUID.randomUUID() }; helper().getClass() == null",
                        "Object helper() { System.getProperties() }; helper() == null")) {
                    Assert.assertThrows(profile + ": " + source, ScriptException.class,
                                        () -> engine.eval(source, new SimpleBindings()));
                }
                Assert.assertThrows(ScriptException.class, () -> engine.eval(
                        "((UUID) identifier) == null",
                        new SimpleBindings(Map.of("identifier", new UUID(0L, 1L)))));
            }
        }
    }

    @Test
    public void testReturnConversionKeepsNumbersAndRejectsConstructorArguments() {
        Assert.assertEquals(Byte.valueOf((byte) 1), ScriptDataOperations.convertReturn(257, byte.class));
        Assert.assertEquals(Short.valueOf((short) 1), ScriptDataOperations.convertReturn(65537, short.class));
        Assert.assertEquals(Character.valueOf('A'), ScriptDataOperations.convertReturn(65, char.class));
        Assert.assertEquals(Byte.valueOf((byte) 1), ScriptDataOperations.convertReturn(1, Byte.class));
        Assert.assertNull(ScriptDataOperations.convertReturn(null, UUID.class));
        List<Integer> values = List.of(1);
        Assert.assertSame(values, ScriptDataOperations.convertReturn(values, List.class));
        SecurityException denied = Assert.assertThrows(SecurityException.class,
                () -> ScriptDataOperations.convertReturn(List.of(1, 4), groovy.lang.IntRange.class));
        Assert.assertEquals("SCRIPT_EXPRESSION_DENIED: return conversion", denied.getMessage());
        denied = Assert.assertThrows(SecurityException.class,
                () -> ScriptDataOperations.convertReturn(Map.of("from", 1), groovy.lang.IntRange.class));
        Assert.assertEquals("SCRIPT_EXPRESSION_DENIED: return conversion", denied.getMessage());
    }

    @Test
    public void testTypedLocalHelperRejectsImplicitConstruction() throws Exception {
        List<String> implicit = List.of(
                "IntRange helper() { List args = [1, 4]; args }; helper() == null",
                "IntRange helper() { Map args = [from: 1, to: 4]; args }; helper() == null",
                "org.apache.hugegraph.schema.SchemaManager helper() { List args = [null, null]; args }; " +
                "helper() == null",
                "P helper() { List args = ['x']; args }; helper() == null");
        List<String> declared = List.of(
                "List args = [1, 4]; IntRange value = args; value.contains(2)",
                "List args = ['x']; P value = args; value == null",
                "List args = [null, null]; (org.apache.hugegraph.schema.SchemaManager) args == null",
                "new org.apache.hugegraph.schema.SchemaManager(null, null) == null");
        for (ScriptExecutionProfile profile : ScriptExecutionProfile.values()) {
            try (PolicyScriptEngine engine = new PolicyScriptEngine(profile)) {
                for (String source : implicit) {
                    ScriptException error = Assert.assertThrows(profile + ": " + source, ScriptException.class,
                            () -> engine.eval(source, new SimpleBindings()));
                    Throwable cause = error.getCause();
                    Assert.assertTrue(profile + ": " + source + " -> " + error.getMessage(),
                            cause instanceof SecurityException &&
                            "SCRIPT_EXPRESSION_DENIED: return conversion".equals(cause.getMessage()));
                }
                for (String source : declared) {
                    Assert.assertThrows(profile + ": " + source, ScriptException.class,
                            () -> engine.eval(source, new SimpleBindings()));
                }
                Assert.assertEquals(profile + ": explicit constructor", true,
                        engine.eval("new IntRange(1, 4).contains(2)", new SimpleBindings()));
            }
        }
    }

    @Test
    public void testArraySpreadRegexAcrossProfiles() throws Exception {
        for (ScriptExecutionProfile profile : ScriptExecutionProfile.values()) {
            try (PolicyScriptEngine engine = new PolicyScriptEngine(profile)) {
                for (String source : List.of(
                        "String[] values = ['a', 'b']; values*.matches('a') == [true, false]",
                        "String[] values = ['a11', 'b22']; values*.replaceAll('[0-9]', '') == ['a', 'b']",
                        "String[] values = ['a11', 'b22']; values*.replaceFirst('[0-9]', '') == ['a1', 'b2']",
                        "String[] values = ['a,b,c', 'd,e']; " +
                        "values*.split(',').collect { it.toList() } == [['a', 'b', 'c'], ['d', 'e']]",
                        "String[] values = ['a,b,c', 'd,e']; " +
                        "values*.split(',', 2).collect { it.toList() } == [['a', 'b,c'], ['d', 'e']]",
                        "['a', 'b']*.matches('a') == [true, false]")) {
                    Assert.assertEquals(profile + ": " + source, true,
                                        engine.eval(source, new SimpleBindings()));
                }
                Assert.assertEquals(true, engine.eval("values*.matches('a') == [true, false]",
                        new SimpleBindings(Map.of("values", new String[]{"a", "b"}))));
            }
        }
    }

    @Test
    public void testArraySpreadRegexPreservesNullAndEmptyValues() throws Exception {
        for (ScriptExecutionProfile profile : ScriptExecutionProfile.values()) {
            try (PolicyScriptEngine engine = new PolicyScriptEngine(profile)) {
                for (String source : List.of(
                        "String[] values = null; List<String> items = null; " +
                        "values*.matches('a') == items*.matches('a')",
                        "String[] values = []; values*.matches('a') == []",
                        "String[] values = ['a', null, 'b']; values*.matches('a') == [true, null, false]",
                        "String[] values = ['a1', null, 'b2']; " +
                        "values*.replaceAll('[0-9]', '') == ['a', null, 'b']")) {
                    Assert.assertEquals(profile + ": " + source, true,
                                        engine.eval(source, new SimpleBindings()));
                }
            }
        }
    }

    @Test
    public void testNullSpreadRegexAcrossProfiles() throws Exception {
        for (ScriptExecutionProfile profile : ScriptExecutionProfile.values()) {
            try (PolicyScriptEngine engine = new PolicyScriptEngine(profile)) {
                for (String operation : List.of("matches('a')", "replaceAll('a', 'b')", "replaceFirst('a', 'b')",
                                                "split(',')", "split(',', 2)")) {
                    String source = "String[] values = null; List<String> items = null; " +
                                    "null*." + operation + " == [] && " +
                                    "values*." + operation + " == [] && items*." + operation + " == []";
                    Assert.assertEquals(profile + ": " + source, true,
                                        engine.eval(source, new SimpleBindings()));
                }
            }
        }
    }

    @Test
    public void testArrayRegexBridgePreservesNullReceiver() {
        Assert.assertNull(ScriptDataOperations.regexArrayTexts(null));
    }

    @Test
    public void testArrayRegexBridgeStillChecksInterruption() {
        Thread.currentThread().interrupt();
        try {
            IllegalStateException error = Assert.assertThrows(IllegalStateException.class,
                    () -> ScriptDataOperations.regexArrayTexts(new String[]{"a"}));
            Assert.assertEquals("SCRIPT_EXECUTION_TIMEOUT", error.getMessage());
            Assert.assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }
}
