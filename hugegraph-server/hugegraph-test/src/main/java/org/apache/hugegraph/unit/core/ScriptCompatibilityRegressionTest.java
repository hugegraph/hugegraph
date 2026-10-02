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
