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

package org.apache.hugegraph.security.script;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

import org.apache.tinkerpop.gremlin.structure.Element;
import org.codehaus.groovy.runtime.DefaultGroovyMethods;
import org.codehaus.groovy.runtime.StringGroovyMethods;
import org.codehaus.groovy.runtime.typehandling.NumberMath;

import groovy.lang.Closure;
import groovy.lang.GString;

/** Fixed data operations for values whose element type is not known at compilation. */
public final class ScriptDataOperations {

    private static final Set<Class<?>> NUMBERS = Set.of(Byte.class, Short.class, Integer.class,
            Long.class, Float.class, Double.class, BigDecimal.class, BigInteger.class);

    private ScriptDataOperations() {
    }

    private static Number number(Object value) {
        if (value == null || !NUMBERS.contains(value.getClass())) {
            throw new IllegalArgumentException("SCRIPT_DATA_TYPE_DENIED: number required");
        }
        return (Number) value;
    }

    @SuppressWarnings("unchecked")
    public static <T> List<T> plus(List<T> left, Object right) {
        return (List<T>) plus((Object) left, right);
    }

    @SuppressWarnings("unchecked")
    public static <T> Set<T> plus(Set<T> left, Object right) {
        return (Set<T>) plus((Object) left, right);
    }

    @SuppressWarnings("unchecked")
    public static <K, V> Map<K, V> plus(Map<K, V> left, Object right) {
        return (Map<K, V>) plus((Object) left, right);
    }

    public static String plus(String left, Object right) {
        return (String) plus((Object) left, right);
    }

    @SuppressWarnings("unchecked")
    public static <T> List<T> minus(List<T> left, Object right) {
        return (List<T>) minus((Object) left, right);
    }

    @SuppressWarnings("unchecked")
    public static <T> Set<T> minus(Set<T> left, Object right) {
        return (Set<T>) minus((Object) left, right);
    }

    @SuppressWarnings("unchecked")
    public static <K, V> Map<K, V> minus(Map<K, V> left, Object right) {
        return (Map<K, V>) minus((Object) left, right);
    }

    public static String minus(String left, Object right) {
        return (String) minus((Object) left, right);
    }

    public static GString plus(GString left, GString right) {
        return ((GString) ScriptBindings.data(left)).plus((GString) ScriptBindings.data(right));
    }

    public static GString plus(GString left, String right) {
        return ((GString) ScriptBindings.data(left)).plus(right);
    }

    @SuppressWarnings("unchecked")
    public static <T> T[] plus(T[] left, Object right) {
        return (T[]) plus((Object) left, right);
    }

    @SuppressWarnings("unchecked")
    public static <T> List<T> multiply(List<T> left, Number right) {
        return (List<T>) multiply((Object) left, right);
    }

    public static String multiply(String left, Number right) {
        return (String) multiply((Object) left, right);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static Object plus(Object left, Object right) {
        if (left instanceof Object[]) {
            if (right instanceof Object[]) {
                return DefaultGroovyMethods.plus((Object[]) left, (Object[]) right);
            }
            if (right instanceof Collection) {
                return DefaultGroovyMethods.plus((Object[]) left, (Collection<?>) right);
            }
            return DefaultGroovyMethods.plus((Object[]) left, right);
        }
        if (left instanceof Collection) {
            // An empty subtraction preserves Groovy's collection-copy semantics.
            Collection<Object> values = DefaultGroovyMethods.minus((Collection<Object>) left,
                                                                     java.util.Collections.emptyList());
            if (right instanceof Collection) {
                values.addAll((Collection<?>) right);
            } else {
                values.add(right);
            }
            return values;
        }
        if (left instanceof Map && right instanceof Map) {
            return DefaultGroovyMethods.plus((Map) left, (Map) right);
        }
        if (left instanceof GString) {
            GString value = (GString) ScriptBindings.data(left);
            if (right instanceof GString) {
                return value.plus((GString) ScriptBindings.data(right));
            }
            if (right instanceof String) {
                return value.plus((String) right);
            }
            return value.toString() + concatenatedText(right);
        }
        if (left instanceof String) {
            return left + concatenatedText(right);
        }
        if (right instanceof String) {
            return text(left) + right;
        }
        return NumberMath.add(number(left), number(right));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static Object minus(Object left, Object right) {
        if (left instanceof Collection) {
            if (right instanceof Collection) {
                return DefaultGroovyMethods.minus((Collection<?>) left, (Collection<?>) right);
            }
            return DefaultGroovyMethods.minus((Iterable<?>) left, right);
        }
        if (left instanceof Map && right instanceof Map) {
            return DefaultGroovyMethods.minus((Map) left, (Map) right);
        }
        if (left instanceof String || left instanceof GString) {
            return StringGroovyMethods.minus(text(left), text(right));
        }
        return NumberMath.subtract(number(left), number(right));
    }

    public static Object multiply(Object left, Object right) {
        if (left instanceof Collection) {
            return DefaultGroovyMethods.multiply((Iterable<?>) left, number(right));
        }
        if (left instanceof String || left instanceof GString) {
            return StringGroovyMethods.multiply(text(left), number(right));
        }
        return NumberMath.multiply(number(left), number(right));
    }

    public static Object div(Object left, Object right) {
        return NumberMath.divide(number(left), number(right));
    }

    public static Object mod(Object left, Object right) {
        return NumberMath.mod(number(left), number(right));
    }

    public static Object power(Object left, Object right) {
        return DefaultGroovyMethods.power(number(left), number(right));
    }

    public static boolean equal(Object left, Object right) {
        return org.codehaus.groovy.runtime.ScriptBytecodeAdapter.compareEqual(left, right);
    }

    public static int compareTo(Object left, Object right) {
        left = left instanceof GString ? text(left) : left;
        right = right instanceof GString ? text(right) : right;
        for (Object value : new Object[]{left, right}) {
            if (value != null && !NUMBERS.contains(value.getClass()) &&
                !Set.of(String.class, Boolean.class, Character.class, Date.class, UUID.class,
                        org.apache.hugegraph.util.Blob.class)
                    .contains(value.getClass())) {
                throw new IllegalArgumentException("SCRIPT_DATA_TYPE_DENIED: comparable scalar required");
            }
        }
        return org.codehaus.groovy.runtime.ScriptBytecodeAdapter.compareTo(left, right);
    }

    private static String concatenatedText(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean ||
            value instanceof Character || value instanceof GString || NUMBERS.contains(value.getClass())) {
            return text(value);
        }
        return org.codehaus.groovy.runtime.InvokerHelper.toString(ScriptBindings.snapshot(value));
    }

    private static String text(Object value) {
        if (value instanceof GString) {
            return ScriptBindings.data(value).toString();
        }
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof Character ||
            NUMBERS.contains(value.getClass())) {
            return String.valueOf(value);
        }
        throw new IllegalArgumentException("SCRIPT_DATA_TYPE_DENIED: scalar required");
    }

    public static String toString(Object value, boolean safe) {
        return safe && value == null ? null : String.valueOf(ScriptBindings.data(value));
    }

    public static Object discard(Object value) {
        return null;
    }

    public static Object checkedData(Object value) {
        return ScriptBindings.data(value);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> checkedMap(Map<String, Object> value) {
        return (Map<String, Object>) ScriptBindings.data(value);
    }

    public static String toJson(Object value) {
        return groovy.json.JsonOutput.toJson(ScriptBindings.snapshot(value));
    }

    public static boolean regexMatches(Object value, Object pattern) {
        if (value == null || pattern == null) {
            return false;
        }
        return Pattern.compile(text(pattern)).matcher(new RegexInput(text(value))).matches();
    }

    public static Matcher regexFind(Object value, Object pattern) {
        return Pattern.compile(text(pattern)).matcher(new RegexInput(text(value)));
    }

    public static String regexReplaceAll(String value, String pattern, String replacement) {
        return Pattern.compile(pattern).matcher(new RegexInput(value)).replaceAll(replacement);
    }

    public static String regexReplaceFirst(String value, String pattern, String replacement) {
        return Pattern.compile(pattern).matcher(new RegexInput(value)).replaceFirst(replacement);
    }

    public static String[] regexSplit(String value, String pattern) {
        return regexSplit(value, pattern, 0);
    }

    public static String[] regexSplit(String value, String pattern, int limit) {
        return Pattern.compile(pattern).split(new RegexInput(value), limit);
    }

    public static List<RegexText> regexTexts(Collection<?> values) {
        if (values == null) {
            return null;
        }
        List<RegexText> result = new ArrayList<>();
        long deadline = ScriptExecutionBudget.deadline();
        for (Object value : values) {
            ScriptExecutionBudget.check(deadline);
            if (value != null && !(value instanceof String)) {
                throw new IllegalArgumentException("SCRIPT_DATA_TYPE_DENIED: string required");
            }
            result.add(regexText((String) value));
        }
        return result;
    }

    public static RegexText regexText(String value) {
        return value == null ? null : new RegexText(value);
    }

    public static final class RegexText {

        private final String value;

        private RegexText(String value) {
            this.value = value;
        }

        public boolean matches(String pattern) {
            return Pattern.compile(pattern).matcher(new RegexInput(this.value)).matches();
        }

        public String replaceAll(String pattern, String replacement) {
            return regexReplaceAll(this.value, pattern, replacement);
        }

        public String replaceFirst(String pattern, String replacement) {
            return regexReplaceFirst(this.value, pattern, replacement);
        }

        public String[] split(String pattern) {
            return regexSplit(this.value, pattern);
        }

        public String[] split(String pattern, int limit) {
            return regexSplit(this.value, pattern, limit);
        }
    }

    private static final class RegexInput implements CharSequence {

        private final String value;
        private final long deadline;

        RegexInput(String value) {
            this(value, ScriptExecutionBudget.deadline());
        }

        RegexInput(String value, long deadline) {
            this.value = value;
            this.deadline = deadline;
        }

        @Override
        public int length() {
            ScriptExecutionBudget.check(this.deadline);
            return this.value.length();
        }

        @Override
        public char charAt(int index) {
            ScriptExecutionBudget.check(this.deadline);
            return this.value.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            ScriptExecutionBudget.check(this.deadline);
            return new RegexInput(this.value.substring(start, end), this.deadline);
        }

        @Override
        public String toString() {
            ScriptExecutionBudget.check(this.deadline);
            return this.value;
        }
    }

    public static Map<String, Object> elementProperties(Element element) {
        if (element == null) {
            return null;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("label", element.label());
        values.put("id", element.id());
        return values;
    }

    public static Object getAt(Object value, String key) {
        if (value instanceof Collection) {
            return project(value, key, true);
        }
        return propertyMap(value).get(key);
    }

    public static Object getAtSafe(Object value, String key) {
        return value == null ? null : getAt(value, key);
    }

    public static List<?> project(Object value, String key) {
        return project(value, key, false);
    }

    private static List<?> project(Object value, String key, boolean skipNull) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Collection) || value.getClass().getClassLoader() != null) {
            throw new IllegalArgumentException("SCRIPT_DATA_TYPE_DENIED: collection required");
        }
        long deadline = ScriptExecutionBudget.deadline();
        List<Object> result = new ArrayList<>();
        for (Object item : (Collection<?>) value) {
            ScriptExecutionBudget.check(deadline);
            if (skipNull && item == null) {
                continue;
            }
            if (item instanceof Element && Set.of("id", "label").contains(key)) {
                result.add(elementProperties((Element) item).get(key));
            } else {
                result.add(item == null ? null : propertyMap(item).get(key));
            }
        }
        return result;
    }

    public static List<?> collect(List<?> values, Closure<?> closure) {
        return DefaultGroovyMethods.collect(values, closure);
    }

    public static Map<?, ?> propertyMap(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map && ScriptBindings.isJsonMap(value)) {
            return (Map<?, ?>) value;
        }
        if (value instanceof Map && Set.of("java.util.HashMap", "java.util.LinkedHashMap",
                "java.util.TreeMap", "java.util.ImmutableCollections$Map1",
                "java.util.ImmutableCollections$MapN", "java.util.Collections$EmptyMap",
                "java.util.Collections$SingletonMap", "java.util.Collections$UnmodifiableMap")
                .contains(value.getClass().getName()) &&
            value.getClass().getClassLoader() == null) {
            return (Map<?, ?>) value;
        }
        throw new IllegalArgumentException("SCRIPT_DATA_TYPE_DENIED: map required");
    }
}
