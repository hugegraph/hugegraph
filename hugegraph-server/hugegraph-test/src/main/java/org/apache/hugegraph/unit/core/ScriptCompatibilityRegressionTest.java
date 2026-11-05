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
    public void testNativeGrowthBudgetsAcrossProfiles() throws Exception {
        for (ScriptExecutionProfile profile : ScriptExecutionProfile.values()) {
            try (PolicyScriptEngine engine = new PolicyScriptEngine(profile)) {
                for (String source : List.of(
                        "'ab'.concat('cd') == 'abcd'",
                        "def x='a'; \"${x}\".concat('b') == 'ab' && \"${x}\".replace('a','b') == 'b'",
                        "int n=0; def x=\"${-> ++n}\"; 'a'.concat(x)=='a1' && n==1",
                        "int n=0; def x=\"${-> n++}\"; x.concat(''+n++)=='01' && n==2",
                        "int n=0; def x=\"${-> n++}\"; x?.concat(''+n++)=='01' && n==2",
                        "int n=0; def x=\"${-> n++}\"; x.replace('z',''+n++)=='0' && n==2",
                        "int n=0; def x=\"${-> n++}\"; [x,null,x]*.concat(''+n++)==['01',null,'23'] && n==4",
                        "def a=(0..4999).toList(); def x=\"$a\"; ''.concat(x).startsWith('[0, 1, 2')",
                        "'a'.concat(\"${1..3}\")=='a[1, 2, 3]'",
                        "def x=\"${-> 'b'}\"; 'a'.concat(\"${[x]}\")=='a[b]'",
                        "def x=\"${-> 'b'}\"; 'a'.concat(\"${[x:x]}\")=='a[x:b]'",
                        "int n=0; def x=\"${-> ++n}\"; " +
                        "try { 'a'.replace(x,null) } catch(NullPointerException ignored) {}; n==1",
                        "String s=null; int n=0; s?.concat(''+n++); n==0",
                        "int n=0; ['a',null,'b']*.concat(''+n++) == ['a0',null,'b1'] && n==2",
                        "'abab'.replace('ab','x') == 'xx' && 'ab'.replace('','x') == 'xaxbx'",
                        "'aaa'.replace('a'.charAt(0),'b'.charAt(0)) == 'bbb'",
                        "'aaa'.replace('aa','x') == 'xa' && 'aaa'.replace('a','') == ''",
                        "String s=null; int n=0; s?.replace('a',''+n++); n==0",
                        "['ab',null,'ba']*.replace('a','x') == ['xb',null,'bx']",
                        "def a=[1,2]; def b=a; a.addAll(a) && b==[1,2,1,2]",
                        "def a=[1,3]; a.addAll(1,[2]) && a==[1,2,3]",
                        "Collection<Integer> a=[1]; a.addAll([2]) && a==[1,2]",
                        "Set<Integer> a=[1]; !a.addAll([1]) && a.addAll([2]) && a==([1,2] as Set)",
                        "List a=null; int n=0; a?.addAll([n++]); n==0",
                        "def a=[[1],null,[2]]; a*.addAll([3]) == [true,null,true] && a==[[1,3],null,[2,3]]",
                        "'x'.repeat(32768).concat('x'.repeat(32768)).length()==65536",
                        "'x'.repeat(32768).replace('x','xx').length()==65536")) {
                    Assert.assertEquals(profile + ": " + source, true, engine.eval(source, new SimpleBindings()));
                }
                for (String source : List.of(
                        "def s='x'.repeat(32769); s.concat(s); true",
                        "def s='x'.repeat(32768); ''.concat(\"$s$s$s\"); true",
                        "def s='x'.repeat(32768); def x=\"${-> \"$s$s$s\"}\"; 'a'.replace('z',x); true",
                        "'x'.repeat(32769).replace('x','xx'); true",
                        "'x'.repeat(32768).replace('','x'); true",
                        "['x'.repeat(32769)]*.concat('x'.repeat(32769)); true",
                        "def a=(0..32768).toList(); a.addAll(a); true",
                        "def a=(0..32768).toList(); a.addAll(0,a); true",
                        "Collection a=(0..32768).toList(); a.addAll(a); true",
                        "def a=[(0..32768).toList()]; a*.addAll(a[0]); true")) {
                    assertAllocationDenied(engine, profile, source);
                }
            }
        }
    }

    @Test
    public void testRegexReplacementBudgetsAcrossProfiles() throws Exception {
        for (ScriptExecutionProfile profile : ScriptExecutionProfile.values()) {
            try (PolicyScriptEngine engine = new PolicyScriptEngine(profile)) {
                for (String source : List.of(
                        "'ab'.replaceAll('(a)(b)', '$2$1$0') == 'baab'",
                        "'ab ab'.replaceFirst('(?<word>ab)', '${word}${word}') == 'abab ab'",
                        "'ab'.replaceAll('', '-') == '-a-b-'",
                        "int n=0; def x=\"${-> ++n}\"; 'a'.replaceAll('a',x)=='1' && n==1",
                        "int n=0; def x=\"${-> ++n}\"; 'a'.replaceAll('z',x)=='a' && n==1",
                        "int n=0; def x=\"${-> n++}\"; '0'.replaceAll(x,''+n++)=='1' && n==2",
                        "int n=0; def x=\"${-> n++}\"; '0'.replaceFirst(x,''+n++)=='1' && n==2",
                        "['ab',null,'ab']*.replaceAll('(a)(b)', '$2$1') == ['ba',null,'ba']",
                        "String s=null; int n=0; s?.replaceAll('x',''+n++); n==0",
                        "'x'.repeat(32768).replaceAll('x','xx').length()==65536")) {
                    Assert.assertEquals(profile + ": " + source, true, engine.eval(source, new SimpleBindings()));
                }
                for (String source : List.of(
                        "'x'.repeat(32769).replaceAll('x','xx'); true",
                        "def s='x'.repeat(32768); 'ab'.replaceFirst('z',\"$s$s$s\"); true",
                        "def s='x'.repeat(32768); def x=\"${-> \"$s$s$s\"}\"; 'ab'.replaceFirst('z',x); true",
                        "'x'.repeat(32768).replaceAll('','x'); true",
                        "'x'.repeat(32769).replaceFirst('(.*)', '$1$1'); true",
                        "'x'.repeat(32769).replaceAll('(?<word>.*)', '${word}${word}'); true",
                        "'ab'.replaceFirst('b','x'.repeat(65536)); true",
                        "'ab'.replaceFirst('a','x'.repeat(65536)); true",
                        "['x'.repeat(32769)]*.replaceAll('x','xx'); true")) {
                    assertAllocationDenied(engine, profile, source);
                }
            }
        }
    }

    @Test
    public void testRegexReplacementMatchesJdk() {
        String unchanged = new String(new char[]{'a'});
        Assert.assertSame(unchanged, ScriptDataOperations.regexReplaceAll(unchanged, "z", "x"));
        Assert.assertSame(unchanged, ScriptDataOperations.regexReplaceFirst(unchanged, "z", "x"));
        IllegalArgumentException oversizedName = Assert.assertThrows(IllegalArgumentException.class,
                () -> ScriptDataOperations.regexReplaceAll("a", "a", "${" + "a".repeat(65537) + "}"));
        Assert.assertEquals("SCRIPT_DATA_ALLOCATION_LIMIT", oversizedName.getMessage());
        for (String value : List.of("", "a", "ab", "aba", "\uD83D\uDE00ab")) {
            for (String pattern : List.of("", "a", "(a)(b)?", "(?<word>a)(b)?", "(?=a)", "$", "(a*)")) {
                for (String replacement : java.util.Arrays.asList(null, "", "x", "$0", "$1", "$12", "$01", "$00",
                        "$2", "${word}", "$1$1", "\\$1", "\\\\", "$", "\\", "$x", "$9", "${}", "${word")) {
                    for (boolean first : new boolean[]{false, true}) {
                        Object expected;
                        try {
                            expected = first ? value.replaceFirst(pattern, replacement) :
                                       value.replaceAll(pattern, replacement);
                        } catch (RuntimeException error) {
                            expected = error.getClass();
                        }
                        Object actual;
                        try {
                            actual = first ? ScriptDataOperations.regexReplaceFirst(value, pattern, replacement) :
                                     ScriptDataOperations.regexReplaceAll(value, pattern, replacement);
                        } catch (RuntimeException error) {
                            actual = error.getClass();
                        }
                        Assert.assertEquals(value + " / " + pattern + " / " + replacement + " / " + first,
                                            expected, actual);
                    }
                }
            }
        }
    }

    @Test
    public void testRejectedBulkAddDoesNotMutateReceiver() {
        List<Integer> values = new java.util.ArrayList<>(java.util.Collections.nCopies(32769, 1));
        Assert.assertThrows(IllegalArgumentException.class, () -> ScriptDataOperations.bounded(values).addAll(values));
        Assert.assertEquals(32769, values.size());
        Assert.assertThrows(IllegalArgumentException.class,
                            () -> ScriptDataOperations.bounded(values).addAll(0, values));
        Assert.assertEquals(32769, values.size());
    }

    @Test
    public void testRejectedTextConversionClosesCursor() {
        java.util.concurrent.atomic.AtomicInteger closed = new java.util.concurrent.atomic.AtomicInteger();
        org.apache.tinkerpop.gremlin.structure.util.CloseableIterator<CharSequence> values =
                new org.apache.tinkerpop.gremlin.structure.util.CloseableIterator<>() {
                    @Override
                    public boolean hasNext() {
                        return true;
                    }

                    @Override
                    public CharSequence next() {
                        String text = "x".repeat(32769);
                        return new org.codehaus.groovy.runtime.GStringImpl(new Object[]{text, text},
                                                                         new String[]{"", "", ""});
                    }

                    @Override
                    public void close() {
                        closed.incrementAndGet();
                        throw new IllegalStateException("close failure");
                    }
                };
        IllegalArgumentException failure = Assert.assertThrows(IllegalArgumentException.class,
                () -> ScriptDataOperations.boundedTexts(values).iterator().next());
        Assert.assertEquals("SCRIPT_DATA_ALLOCATION_LIMIT", failure.getMessage());
        Assert.assertEquals(1, closed.get());
        Assert.assertTrue(failure.getSuppressed()[0].getMessage().contains("close failure"));
    }

    @Test
    public void testConcatenationBudgetsAcrossProfiles() throws Exception {
        for (ScriptExecutionProfile profile : ScriptExecutionProfile.values()) {
            try (PolicyScriptEngine engine = new PolicyScriptEngine(profile)) {
                for (String source : List.of(
                        "String s=null; s.plus('x') == 'nullx' && s+null == 'nullnull'",
                        "String s=null; int n=0; s?.plus(n++); n == 0",
                        "int n=0; def r=['a',null,'b']*.plus(n++); n == 2 && r == ['a0',null,'b1']",
                        "def f(Object rhs) { List<Object> a=[1]; a+rhs }; f([2,3]) == [1,[2,3]]",
                        "def f(Object rhs) { List<Object> a=[1]; a.plus(rhs) }; f([2,3]) == [1,[2,3]]",
                        "def f(Object rhs) { Object[] a=[1]; a+rhs }; f([2,3]) == ([1,[2,3]] as Object[])",
                        "def x='v'; [a:1]+\"${x}\" == '[a:1]v' && 1+\"${x}\" == '1v'",
                        "([1,2] as Set) + [2,3] == ([1,2,3] as Set)",
                        "([1] as Integer[]) + ([2,3] as Integer[]) == ([1,2,3] as Integer[])",
                        "Object[] a=[1]; int[] b=[2,3]; a+b == ([1,2,3] as Object[])",
                        "Object[] a=[1]; int[] b=[2,3]; a.plus(b) == ([1,2,3] as Object[])",
                        "[1,3].plus(1,[2]) == [1,2,3]",
                        "def f() { 'a'+'b' }; def g={ f()+'c' }; g() == 'abc'",
                        "Iterator<String> a=['a',null,'b'].iterator(); a*.plus('x') == ['ax',null,'bx']",
                        "def a=['a']; def x=(a[0]+='b'); x=='ab' && a==['ab']",
                        "def a=['a']; a[0]+=(a[0]='z'); a==['az']",
                        "def a=[x:'a']; a.x+=(a.x='z'); a.x=='az'",
                        "def a=['a']; int n=0; int k=0; def r={n++;a}; def i={k++;0}; " +
                        "r()[i()]+='b'; n==1 && k==1 && a==['ab']",
                        "def l=['x'.repeat(30000)]; def s=\"$l$l\"; (s+'').length()==60004",
                        "'x'.repeat(32768) + 'x'.repeat(32768) == 'x'.repeat(65536)")) {
                    Assert.assertEquals(profile + ": " + source, true, engine.eval(source, new SimpleBindings()));
                }
                for (String source : List.of(
                        "def s='x'.repeat(32769); s += s; true",
                        "def s='x'.repeat(32769); s = s+s; true",
                        "def s='x'.repeat(32769); s.plus(s); true",
                        "def a=['x'.repeat(32769)]; a[0]+=a[0]; true",
                        "def a=[x:'x'.repeat(32769)]; a.x+=a.x; true",
                        "Iterator<String> a=['x'.repeat(32769)].iterator(); a*.plus('x'.repeat(32769)); true",
                        "def l=['x'.repeat(30000)]; def s=\"$l$l$l\"; s+''; true",
                        "def l=['x'.repeat(30000)]; def a=[l,l,l]; ''+a; true",
                        "def f={ def s='x'.repeat(32769); s+s }; f(); true",
                        "def a=(0..32768).toList(); a += a; true",
                        "def a=(0..32768).toList(); a+a; true",
                        "def a=new Integer[32769]; a+a; true",
                        "def a=(0..32768).toList(); a.plus(0,a); true")) {
                    assertAllocationDenied(engine, profile, source);
                }
            }
        }
    }

    @Test
    public void testNativeIterableConcatenation() throws Exception {
        try (org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource g =
                     org.apache.tinkerpop.gremlin.structure.util.empty.EmptyGraph.instance().traversal();
             PolicyScriptEngine engine = new PolicyScriptEngine(ScriptExecutionProfile.QUERY)) {
            for (String source : List.of(
                    "def p=g.inject(1).path().next(); List<Object> b=[2]; p+b == [1,2]",
                    "def p=g.inject(1).path().next(); List<Object> b=[2]; p.plus(b) == [1,2]")) {
                Assert.assertEquals(source, true, engine.eval(source, new SimpleBindings(Map.of("g", g))));
            }
        }
    }

    @Test
    public void testPowerBudgetsPreserveNumericDispatch() throws Exception {
        Assert.assertEquals(Double.POSITIVE_INFINITY, ScriptDataOperations.power(9223372036854775807L, 1041));
        for (ScriptExecutionProfile profile : ScriptExecutionProfile.values()) {
            try (PolicyScriptEngine engine = new PolicyScriptEngine(profile)) {
                for (String source : List.of("2 ** 8 == 256", "2L ** 8 == 256L", "2 ** 40 == 1099511627776G",
                        "def x=1E-60000G; x **= 2; x == 0",
                        "def f(Object x) { x ** -1 }; f(BigDecimal.TEN) == 0.1G")) {
                    Assert.assertEquals(profile + ": " + source, true, engine.eval(source, new SimpleBindings()));
                }
                for (String source : List.of("def x=9223372036854775807L; x ** 1041; true",
                        "def x=2147483647; x ** 2115; true",
                        "def f={ Long x=9223372036854775807L; x ** 1041 }; f(); true")) {
                    assertAllocationDenied(engine, profile, source);
                }
            }
        }
    }

    private static void assertAllocationDenied(PolicyScriptEngine engine, ScriptExecutionProfile profile,
                                               String source) {
        ScriptException error = Assert.assertThrows(profile + ": " + source, ScriptException.class,
                () -> engine.eval(source, new SimpleBindings()));
        Throwable cause = error;
        while (cause != null && !String.valueOf(cause.getMessage()).contains("SCRIPT_DATA_ALLOCATION_LIMIT")) {
            cause = cause.getCause();
        }
        Assert.assertNotNull(profile + ": " + source + " -> " + error, cause);
    }

    @Test
    public void testRejectedConcatenationClosesItsCursor() {
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
                        throw new IllegalStateException("close failure");
                    }
                };
        Iterable<String> items = () -> cursor;
        IllegalArgumentException error = Assert.assertThrows(IllegalArgumentException.class, () ->
                ScriptDataOperations.plus(List.of("x"), items, List.class, Iterable.class));
        Assert.assertEquals("SCRIPT_DATA_ALLOCATION_LIMIT", error.getMessage());
        Assert.assertEquals(1, closed.get());
        Assert.assertEquals(1, error.getSuppressed().length);
    }

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
