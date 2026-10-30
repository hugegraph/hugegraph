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
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.Iterator;
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
import groovy.lang.IntRange;
import groovy.lang.Range;

/** Fixed data operations for values whose element type is not known at compilation. */
public final class ScriptDataOperations {

    private static final int MAX_ALLOCATION = 65536;
    private static final Pattern FORMAT_SPECIFIER = Pattern.compile(
            "%(?:[0-9]++\\$)?[-#+ 0,(<]*+([0-9]++)?(?:\\.([0-9]++))?[tT]?[a-zA-Z%]");
    private static final Set<Class<?>> NUMBERS = Set.of(Byte.class, Short.class, Integer.class,
            Long.class, Float.class, Double.class, BigDecimal.class, BigInteger.class);

    private ScriptDataOperations() {
    }

    private static void allocation(long size) {
        if (size < 0 || size > MAX_ALLOCATION) {
            throw new IllegalArgumentException("SCRIPT_DATA_ALLOCATION_LIMIT");
        }
    }

    public static int capacity(int size) {
        allocation(size);
        return size;
    }

    public static <T> T constructorArgument(T value) {
        if (value instanceof Number) {
            allocation(((Number) value).longValue());
        } else if (value instanceof Collection) {
            allocation(((Collection<?>) value).size());
        } else if (value instanceof Map) {
            allocation(((Map<?, ?>) value).size());
        }
        return value;
    }

    private static String repeat(String value, int count) {
        allocation((long) value.length() * count);
        allocation(count);
        return value.repeat(count);
    }

    public static String format(String pattern, Object... arguments) {
        return format(java.util.Locale.getDefault(java.util.Locale.Category.FORMAT), pattern, arguments);
    }

    public static String format(java.util.Locale locale, String pattern, Object... arguments) {
        if (arguments != null) {
            for (Object argument : arguments) {
                if (argument instanceof BigDecimal) {
                    checkedNumber((BigDecimal) argument);
                } else if (argument instanceof BigInteger) {
                    checkedNumber((BigInteger) argument);
                } else if (argument instanceof java.util.Formattable) {
                    throw new IllegalArgumentException("SCRIPT_DATA_TYPE_DENIED: custom formatter");
                }
            }
        }
        Matcher specifier = FORMAT_SPECIFIER.matcher(pattern);
        for (int start = pattern.indexOf('%'); start >= 0; start = pattern.indexOf('%', specifier.end())) {
            specifier.region(start, pattern.length());
            if (!specifier.lookingAt()) {
                throw new java.util.UnknownFormatConversionException(
                        start + 1 < pattern.length() ? pattern.substring(start + 1, start + 2) : "%");
            }
            for (int group = 1; group <= 2; group++) {
                if (specifier.group(group) != null) {
                    try {
                        allocation(Long.parseLong(specifier.group(group)));
                    } catch (NumberFormatException error) {
                        throw new IllegalArgumentException("SCRIPT_DATA_ALLOCATION_LIMIT");
                    }
                }
            }
        }
        StringBuilder result = new StringBuilder();
        Appendable output = new Appendable() {
            @Override
            public Appendable append(CharSequence value) {
                CharSequence text = value == null ? "null" : value;
                return this.append(text, 0, text.length());
            }

            @Override
            public Appendable append(CharSequence value, int start, int end) {
                allocation((long) result.length() + end - start);
                result.append(value, start, end);
                return this;
            }

            @Override
            public Appendable append(char value) {
                allocation((long) result.length() + 1);
                result.append(value);
                return this;
            }
        };
        // Formatter still owns conversion, locale, flag and invalid-format semantics.
        try (java.util.Formatter formatter = new java.util.Formatter(output, locale)) {
            formatter.format(pattern, arguments);
        }
        return result.toString();
    }

    private static BigInteger pow(BigInteger value, int exponent) {
        allocation(exponent);
        allocation((long) value.bitLength() * exponent);
        return value.pow(exponent);
    }

    private static BigDecimal pow(BigDecimal value, int exponent) {
        allocation(exponent);
        allocation((long) value.unscaledValue().bitLength() * exponent);
        allocation(Math.abs((long) value.scale() * exponent));
        return value.pow(exponent);
    }

    public static BigDecimal checkedNumber(BigDecimal value) {
        allocation(value.precision());
        allocation(Math.abs((long) value.scale()));
        return value;
    }

    public static BigDecimal valueOf(long value, int scale) {
        allocation(Math.abs((long) scale));
        return BigDecimal.valueOf(value, scale);
    }

    private static BigInteger multiplyNumbers(BigInteger left, BigInteger right) {
        allocation((long) left.bitLength() + right.bitLength());
        return left.multiply(right);
    }

    private static BigDecimal multiplyNumbers(BigDecimal left, BigDecimal right) {
        checkedNumber(left);
        checkedNumber(right);
        allocation(Math.abs((long) left.scale() + right.scale()));
        allocation((long) left.unscaledValue().bitLength() + right.unscaledValue().bitLength());
        return checkedNumber(left.multiply(right));
    }

    private static BigDecimal divide(BigDecimal left, BigDecimal right) {
        checkedNumber(left);
        checkedNumber(right);
        return checkedNumber(left.divide(right));
    }

    private static BigDecimal divide(BigDecimal left, BigDecimal right, java.math.RoundingMode rounding) {
        checkedNumber(left);
        checkedNumber(right);
        return checkedNumber(left.divide(right, rounding));
    }

    private static BigDecimal divide(BigDecimal left, BigDecimal right, int rounding) {
        return divide(left, right, java.math.RoundingMode.valueOf(rounding));
    }

    private static BigDecimal divide(BigDecimal left, BigDecimal right, int scale, java.math.RoundingMode rounding) {
        scale(left, scale);
        checkedNumber(right);
        return checkedNumber(left.divide(right, scale, rounding));
    }

    private static BigDecimal divide(BigDecimal left, BigDecimal right, int scale, int rounding) {
        return divide(left, right, scale, java.math.RoundingMode.valueOf(rounding));
    }

    private static void scale(BigDecimal value, int scale) {
        checkedNumber(value);
        allocation(Math.abs((long) scale));
        allocation(value.precision() + Math.max(0L, (long) scale - value.scale()));
    }

    private static BigDecimal setScale(BigDecimal value, int scale) {
        scale(value, scale);
        return value.setScale(scale);
    }

    private static BigDecimal setScale(BigDecimal value, int scale, java.math.RoundingMode rounding) {
        scale(value, scale);
        return value.setScale(scale, rounding);
    }

    private static BigDecimal setScale(BigDecimal value, int scale, int rounding) {
        return setScale(value, scale, java.math.RoundingMode.valueOf(rounding));
    }

    private static String padLeft(CharSequence value, Number width) {
        return padLeft(value, width, " ");
    }

    private static String padLeft(CharSequence value, Number width, CharSequence padding) {
        allocation(Math.max(0, width.intValue()));
        return StringGroovyMethods.padLeft(value, width, padding);
    }

    private static String padRight(CharSequence value, Number width) {
        return padRight(value, width, " ");
    }

    private static String padRight(CharSequence value, Number width, CharSequence padding) {
        allocation(Math.max(0, width.intValue()));
        return StringGroovyMethods.padRight(value, width, padding);
    }

    private static String center(CharSequence value, Number width) {
        return center(value, width, " ");
    }

    private static String center(CharSequence value, Number width, CharSequence padding) {
        allocation(Math.max(0, width.intValue()));
        return StringGroovyMethods.center(value, width, padding);
    }

    public static BigInteger checkedNumber(BigInteger value) {
        allocation(value.bitLength());
        return value;
    }

    public static <T> BoundedValue<T> bounded(T value) {
        return value == null ? null : new BoundedValue<>(value);
    }

    public static <T> Iterable<BoundedValue<T>> boundedValues(Iterable<T> values) {
        return values == null ? null : () -> boundedIterator(values.iterator());
    }

    public static <T> Iterable<BoundedValue<T>> boundedValues(T[] values) {
        return values == null ? null : boundedValues(Arrays.asList(values));
    }

    public static <T> Iterable<BoundedValue<T>> boundedValues(Iterator<T> values) {
        return values == null ? null : () -> boundedIterator(values);
    }

    private static <T> Iterator<BoundedValue<T>> boundedIterator(Iterator<T> values) {
        return new Iterator<>() {
            private int count;
            private final long deadline = ScriptExecutionBudget.deadline();

            @Override
            public boolean hasNext() {
                this.check(false);
                return values.hasNext();
            }

            @Override
            public BoundedValue<T> next() {
                this.check(true);
                return bounded(values.next());
            }

            private void check(boolean advance) {
                try {
                    ScriptExecutionBudget.check(this.deadline);
                    if (advance) {
                        allocation(++this.count);
                    }
                } catch (RuntimeException failure) {
                    try {
                        org.apache.tinkerpop.gremlin.structure.util.CloseableIterator.closeIterator(values);
                    } catch (RuntimeException closeFailure) {
                        failure.addSuppressed(closeFailure);
                    }
                    throw failure;
                }
            }
        };
    }

    /** A receiver bridge preserves Groovy's safe/spread argument evaluation order. */
    public static final class BoundedValue<T> {
        private final T value;

        private BoundedValue(T value) {
            this.value = value;
        }

        public String repeat(int count) {
            return ScriptDataOperations.repeat(text(this.value), count);
        }

        @SuppressWarnings("unchecked")
        public T pow(int exponent) {
            return (T) (this.value instanceof BigInteger ?
                    ScriptDataOperations.pow((BigInteger) this.value, exponent) :
                    ScriptDataOperations.pow((BigDecimal) this.value, exponent));
        }

        @SuppressWarnings("unchecked")
        public T multiply(T right) {
            return (T) (this.value instanceof BigInteger ?
                    ScriptDataOperations.multiplyNumbers((BigInteger) this.value, (BigInteger) right) :
                    ScriptDataOperations.multiplyNumbers((BigDecimal) this.value, (BigDecimal) right));
        }

        public BigDecimal setScale(int scale) {
            return ScriptDataOperations.setScale((BigDecimal) this.value, scale);
        }

        public BigDecimal setScale(int scale, java.math.RoundingMode rounding) {
            return ScriptDataOperations.setScale((BigDecimal) this.value, scale, rounding);
        }

        public BigDecimal setScale(int scale, int rounding) {
            return ScriptDataOperations.setScale((BigDecimal) this.value, scale, rounding);
        }

        public BigDecimal divide(BigDecimal right) {
            return ScriptDataOperations.divide((BigDecimal) this.value, right);
        }

        public BigDecimal divide(BigDecimal right, java.math.RoundingMode rounding) {
            return ScriptDataOperations.divide((BigDecimal) this.value, right, rounding);
        }

        public BigDecimal divide(BigDecimal right, int rounding) {
            return ScriptDataOperations.divide((BigDecimal) this.value, right, rounding);
        }

        public BigDecimal divide(BigDecimal right, int scale, java.math.RoundingMode rounding) {
            return ScriptDataOperations.divide((BigDecimal) this.value, right, scale, rounding);
        }

        public BigDecimal divide(BigDecimal right, int scale, int rounding) {
            return ScriptDataOperations.divide((BigDecimal) this.value, right, scale, rounding);
        }

        public String padLeft(Number width) {
            return ScriptDataOperations.padLeft((CharSequence) this.value, width);
        }

        public String padLeft(Number width, CharSequence padding) {
            return ScriptDataOperations.padLeft((CharSequence) this.value, width, padding);
        }

        public String padRight(Number width) {
            return ScriptDataOperations.padRight((CharSequence) this.value, width);
        }

        public String padRight(Number width, CharSequence padding) {
            return ScriptDataOperations.padRight((CharSequence) this.value, width, padding);
        }

        public String center(Number width) {
            return ScriptDataOperations.center((CharSequence) this.value, width);
        }

        public String center(Number width, CharSequence padding) {
            return ScriptDataOperations.center((CharSequence) this.value, width, padding);
        }
    }

    private static void rangeSize(Object from, Object to, boolean left, boolean right) {
        long start;
        long end;
        if (from instanceof Character && to instanceof Character) {
            start = (Character) from;
            end = (Character) to;
        } else if (from instanceof String && to instanceof String &&
                   ((String) from).length() == 1 && ((String) to).length() == 1) {
            start = ((String) from).charAt(0);
            end = ((String) to).charAt(0);
        } else if (from instanceof Number && to instanceof Number) {
            Number first = number(from);
            Number last = number(to);
            BigDecimal difference = new BigDecimal(first.toString()).subtract(new BigDecimal(last.toString())).abs();
            if (difference.compareTo(BigDecimal.valueOf(MAX_ALLOCATION)) > 0) {
                throw new IllegalArgumentException("SCRIPT_DATA_ALLOCATION_LIMIT");
            }
            // Fractional endpoints need at most floor(distance) + 1 entries.
            start = 0;
            end = difference.longValue();
        } else if (from instanceof String && to instanceof String) {
            // Groovy owns multi-character string ordering and successor semantics.
            return;
        } else {
            throw new IllegalArgumentException("SCRIPT_DATA_TYPE_DENIED: unsupported range endpoints");
        }
        allocation(Math.max(0, Math.abs(end - start) + 1 - (left ? 0 : 1) - (right ? 0 : 1)));
    }

    public static IntRange range(int from, int to, boolean exclusiveLeft, boolean exclusiveRight) {
        return intRange(!exclusiveLeft, !exclusiveRight, from, to);
    }

    public static Range<?> range(Object from, Object to, boolean exclusiveLeft, boolean exclusiveRight)
            throws Throwable {
        rangeSize(from, to, !exclusiveLeft, !exclusiveRight);
        Range<?> range = (Range<?>) org.codehaus.groovy.runtime.ScriptBytecodeAdapter
                .createRange(from, to, exclusiveLeft, exclusiveRight);
        if (from instanceof String && ((String) from).length() > 1) {
            int count = 0;
            long deadline = ScriptExecutionBudget.deadline();
            for (Object ignored : range) {
                ScriptExecutionBudget.check(deadline);
                allocation(++count);
            }
        } else {
            allocation(range.size());
        }
        return range;
    }

    public static groovy.lang.EmptyRange<?> emptyRange(Comparable<?> from) {
        return new groovy.lang.EmptyRange<>(from);
    }

    public static CharSequence emptyRangeReceiver(CharSequence value) {
        return value;
    }

    public static <T> T emptyRangeReceiver(T value) {
        return value;
    }

    public static IntRange intRange(int from, int to) {
        rangeSize(from, to, true, true);
        return new IntRange(from, to);
    }

    public static IntRange intRange(boolean inclusive, int from, int to) {
        rangeSize(from, to, true, inclusive);
        return new IntRange(inclusive, from, to);
    }

    public static IntRange intRange(boolean left, boolean right, int from, int to) {
        rangeSize(from, to, left, right);
        return new IntRange(left, right, from, to);
    }

    private static Number number(Object value) {
        if (value == null || !NUMBERS.contains(value.getClass())) {
            throw new IllegalArgumentException("SCRIPT_DATA_TYPE_DENIED: number required");
        }
        if (value instanceof BigDecimal) {
            checkedNumber((BigDecimal) value);
        } else if (value instanceof BigInteger) {
            checkedNumber((BigInteger) value);
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
            allocation(number(right).intValue());
            allocation((long) ((Collection<?>) left).size() * number(right).intValue());
            return DefaultGroovyMethods.multiply((Iterable<?>) left, number(right));
        }
        if (left instanceof String || left instanceof GString) {
            allocation(number(right).intValue());
            allocation((long) text(left).length() * number(right).intValue());
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
        Number exponent = number(right);
        allocation((long) Math.ceil(Math.abs(exponent.doubleValue())));
        long growth = Math.max(0L, exponent.longValue());
        if (left instanceof BigInteger) {
            allocation((long) ((BigInteger) left).bitLength() * growth);
        } else if (left instanceof BigDecimal) {
            allocation((long) ((BigDecimal) left).unscaledValue().bitLength() * growth);
        }
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

    /**
     * Helper return conversion. Numeric narrowing and an already compatible value are
     * allowed. Groovy cast coercion is not: a collection or map must not become a
     * constructor call just because the helper declares a reference return type.
     */
    public static Object convertReturn(Object value, Class<?> type) {
        if (type == null || (value == null && type.isPrimitive())) {
            throw deniedReturn();
        }
        if (value == null || type.isInstance(value)) {
            return value;
        }
        Class<?> boxed = boxedPrimitive(type);
        if (boxed != null && boxed.isInstance(value)) {
            return value;
        }
        if (value instanceof Number || value instanceof Character) {
            Object converted = convertNumber(value, type);
            if (converted != null) {
                return converted;
            }
        }
        if (type == String.class && value instanceof GString) {
            return value.toString();
        }
        throw deniedReturn();
    }

    private static Object convertNumber(Object value, Class<?> type) {
        if (!isNumericReturn(type)) {
            return null;
        }
        Number number = value instanceof Character ? Integer.valueOf((Character) value) : (Number) value;
        if (type == byte.class || type == Byte.class) {
            return number.byteValue();
        }
        if (type == short.class || type == Short.class) {
            return number.shortValue();
        }
        if (type == int.class || type == Integer.class) {
            return number.intValue();
        }
        if (type == long.class || type == Long.class) {
            return number.longValue();
        }
        if (type == float.class || type == Float.class) {
            return number.floatValue();
        }
        if (type == double.class || type == Double.class) {
            double converted = number.doubleValue();
            if (!(number instanceof Double) &&
                (converted == Double.NEGATIVE_INFINITY || converted == Double.POSITIVE_INFINITY)) {
                throw deniedReturn();
            }
            return converted;
        }
        if (type == char.class || type == Character.class) {
            return (char) number.intValue();
        }
        if (type == BigDecimal.class) {
            return NumberMath.toBigDecimal(number);
        }
        if (type == BigInteger.class) {
            return NumberMath.toBigInteger(number);
        }
        return null;
    }

    private static boolean isNumericReturn(Class<?> type) {
        return type == byte.class || type == short.class || type == int.class || type == long.class ||
               type == float.class || type == double.class || type == char.class ||
               type == Byte.class || type == Short.class || type == Integer.class || type == Long.class ||
               type == Float.class || type == Double.class || type == Character.class ||
               type == BigDecimal.class || type == BigInteger.class;
    }

    private static Class<?> boxedPrimitive(Class<?> type) {
        if (type == boolean.class) {
            return Boolean.class;
        }
        if (type == byte.class) {
            return Byte.class;
        }
        if (type == short.class) {
            return Short.class;
        }
        if (type == int.class) {
            return Integer.class;
        }
        if (type == long.class) {
            return Long.class;
        }
        if (type == float.class) {
            return Float.class;
        }
        if (type == double.class) {
            return Double.class;
        }
        if (type == char.class) {
            return Character.class;
        }
        return null;
    }

    private static SecurityException deniedReturn() {
        return new SecurityException("SCRIPT_EXPRESSION_DENIED: return conversion");
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

    public static List<RegexText> regexArrayTexts(String[] values) {
        return regexTexts(values == null ? null : Arrays.asList(values));
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
