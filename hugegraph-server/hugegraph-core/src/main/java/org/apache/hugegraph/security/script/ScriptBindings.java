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

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

import javax.lang.model.SourceVersion;
import javax.script.Bindings;
import javax.script.SimpleBindings;

import org.apache.hugegraph.HugeGraph;
import org.apache.hugegraph.util.Blob;
import org.apache.hugegraph.schema.SchemaManager;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;

public final class ScriptBindings {

    public static final int MAX_SOURCE_BYTES = 65536;
    private static final Pattern BINDING_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Set<Class<?>> SCALARS = Set.of(
            String.class, Boolean.class, Byte.class, Short.class, Integer.class,
            Long.class, Float.class, Double.class, BigDecimal.class, BigInteger.class,
            Character.class, UUID.class);
    private static final Set<String> RESERVED = Set.of(
            "this", "super", "binding", "metaClass", "class", "def", "in", "as",
            "trait", "var", "yield", "record", "permits", "sealed");

    private ScriptBindings() {
    }

    /** Validate client data before graph aliases or trusted objects are merged. */
    public static Bindings client(Map<String, Object> input) {
        if (input.keySet().stream().anyMatch(name ->
                Set.of("g", "graph", "schema", "job", "gremlinJob").contains(name))) {
            throw denied("reserved server binding");
        }
        return copy(input, false, ScriptExecutionProfile.QUERY);
    }

    /** Graph objects here must come from server-side alias resolution. */
    public static Bindings execution(Map<String, Object> input,
                                     ScriptExecutionProfile profile) {
        return copy(input, true, profile);
    }

    private static Bindings copy(Map<String, Object> input, boolean trustedObjects,
                                 ScriptExecutionProfile profile) {
        return process(input, trustedObjects, profile, true);
    }

    /** Apply the same checks as execution(), without allocating a data snapshot. */
    static void validateExecution(Map<String, Object> input, ScriptExecutionProfile profile) {
        process(input, true, profile, false);
    }

    private static Bindings process(Map<String, Object> input, boolean trustedObjects,
                                    ScriptExecutionProfile profile, boolean materialize) {
        if (!trustedObjects && input.size() > 256) {
            throw denied("too many bindings");
        }
        Budget budget = new Budget(false, materialize);
        Bindings result = materialize ? new SimpleBindings() : null;
        int count = 0;
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            String name = entry.getKey();
            Object value = entry.getValue();
            if (trustedObjects && (value instanceof HugeGraph || value instanceof GraphTraversalSource) &&
                name != null && !BINDING_NAME.matcher(name).matches()) {
                continue;
            }
            if (++count > 256) {
                throw denied("too many bindings");
            }
            validateName(name);
            if (trustedObjects && (("g".equals(name) && !(value instanceof GraphTraversalSource)) ||
                    ("graph".equals(name) && !(value instanceof HugeGraph)) ||
                    ("schema".equals(name) && !(value instanceof SchemaManager)) ||
                    (("job".equals(name) || "gremlinJob".equals(name)) &&
                     !(value instanceof ScriptJobContext)))) {
                throw denied("reserved server binding");
            }
            if (trustedObjects && (value instanceof HugeGraph ||
                                   value instanceof GraphTraversalSource)) {
                if (profile == ScriptExecutionProfile.STORE_FILTER) {
                    throw denied("graph in Store filter");
                }
                if (value instanceof GraphTraversalSource && value.getClass() != GraphTraversalSource.class &&
                    !(value.getClass().getName().equals(
                            "org.apache.hugegraph.auth.HugeGraphAuthProxy$GraphTraversalSourceProxy") &&
                      value.getClass().getClassLoader() == HugeGraph.class.getClassLoader())) {
                    throw denied("custom traversal source");
                }
                if (value instanceof HugeGraph && (!Set.of("org.apache.hugegraph.StandardHugeGraph",
                        "org.apache.hugegraph.auth.HugeGraphAuthProxy").contains(value.getClass().getName()) ||
                        value.getClass().getClassLoader() != HugeGraph.class.getClassLoader())) {
                    throw denied("custom graph binding");
                }
                if (materialize) {
                    result.put(name, value);
                }
            } else if (trustedObjects && value instanceof ScriptJobContext &&
                       profile == ScriptExecutionProfile.QUERY) {
                if (materialize) {
                    result.put(name, value);
                }
            } else if (trustedObjects && value instanceof SchemaManager &&
                       profile == ScriptExecutionProfile.SCHEMA) {
                if (materialize) {
                    result.put(name, value);
                }
            } else if (trustedObjects && value instanceof ScriptElementView &&
                       profile == ScriptExecutionProfile.STORE_FILTER &&
                       "element".equals(name)) {
                if (materialize) {
                    result.put(name, value);
                }
            } else {
                Object copied = budget.copy(value, 0);
                if (materialize) {
                    result.put(name, copied);
                }
            }
        }
        return result;
    }

    static Object data(Object value) {
        return new Budget().copy(value, 0);
    }

    // Persisted record sizes follow storage limits, not client request quotas.
    static Object storedData(Object value) {
        return new Budget(false, true, false).copy(value, 0);
    }

    public static Map<String, Class<?>> types(Bindings bindings) {
        Map<String, Class<?>> result = new TreeMap<>();
        bindings.forEach((name, value) -> {
            Class<?> type;
            if (value == null) {
                type = Object.class;
            } else if (value instanceof GraphTraversalSource) {
                type = GraphTraversalSource.class;
            } else if (value instanceof HugeGraph) {
                type = HugeGraph.class;
            } else if (value instanceof ScriptJobContext) {
                type = ScriptJobContext.class;
            } else if (value instanceof SchemaManager) {
                type = SchemaManager.class;
            } else if (value instanceof List) {
                type = List.class;
            } else if (value instanceof Map) {
                type = Map.class;
            } else if (value instanceof Set) {
                type = Set.class;
            } else {
                type = value.getClass();
            }
            result.put(name, type);
        });
        return Collections.unmodifiableMap(result);
    }

    private static void validateName(String name) {
        if (name == null || name.length() > 128 || !BINDING_NAME.matcher(name).matches() ||
            name.startsWith("__hg") || RESERVED.contains(name) ||
            SourceVersion.isKeyword(name)) {
            throw denied("invalid or reserved binding name");
        }
    }

    static boolean isJsonMap(Object value) {
        return value.getClass().getName().equals("org.apache.groovy.json.internal.LazyMap") &&
               value.getClass().getClassLoader() == groovy.json.JsonSlurper.class.getClassLoader();
    }

    private static IllegalArgumentException denied(String reason) {
        return new IllegalArgumentException("SCRIPT_BINDING_DENIED: " + reason);
    }

    // Script-produced views may be materialized for JSON. Session data must use explicit copies,
    // since silently materializing a view there would break its connection to the original container.
    static Object snapshot(Object value) {
        return new Budget(true).copy(value, 0);
    }

    private static int scalarLength(Object value) {
        if (value instanceof String) {
            return ((String) value).length();
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            long number = ((Number) value).longValue();
            int length = number < 0 ? 2 : 1;
            // Negative division also handles Long.MIN_VALUE without overflow.
            while ((number /= 10) != 0) {
                length++;
            }
            return length;
        }
        return value.toString().length();
    }

    private static final class Budget {

        private final boolean views;
        private final boolean materialize;
        private final boolean bounded;

        Budget() {
            this(false);
        }

        Budget(boolean views) {
            this(views, true);
        }

        Budget(boolean views, boolean materialize) {
            this(views, materialize, true);
        }

        Budget(boolean views, boolean materialize, boolean bounded) {
            this.views = views;
            this.materialize = materialize;
            this.bounded = bounded;
        }

        private boolean allowsView(Object value) {
            return this.views && value.getClass().getClassLoader() == null &&
                   Set.of("java.util.ArrayList$SubList", "java.util.LinkedHashMap$LinkedKeySet",
                          "java.util.LinkedHashMap$LinkedValues", "java.util.HashMap$KeySet",
                          "java.util.HashMap$Values").contains(value.getClass().getName());
        }


        private int nodes;
        private long size;
        private final IdentityHashMap<Object, Boolean> active = new IdentityHashMap<>();
        private final IdentityHashMap<Object, Object> copies = new IdentityHashMap<>();

        Object copy(Object value, int depth) {
            if ((this.bounded && ++this.nodes > 4096) || depth > 16) {
                throw denied("binding structure limit");
            }
            if (value == null) {
                return null;
            }
            if (value.getClass() == Blob.class) {
                if (this.copies.containsKey(value)) {
                    return this.copies.get(value);
                }
                byte[] bytes = (byte[]) this.copy(((Blob) value).bytes(), depth + 1);
                Blob copied = this.materialize ? Blob.wrap(bytes) : (Blob) value;
                this.copies.put(value, copied);
                return copied;
            }
            if (value.getClass() == Date.class) {
                this.size += Long.BYTES;
                if (this.bounded && this.size > MAX_SOURCE_BYTES) {
                    throw denied("binding size limit");
                }
                return this.copies.computeIfAbsent(value, date -> this.materialize ? new Date(((Date) date).getTime()) : date);
            }
            if (SCALARS.contains(value.getClass())) {
                this.size += scalarLength(value) * 2L;
                if (this.bounded && this.size > MAX_SOURCE_BYTES) {
                    throw denied("binding size limit");
                }
                return value;
            }
            if (this.active.put(value, Boolean.TRUE) != null) {
                throw denied("cyclic binding");
            }
            try {
                if (this.copies.containsKey(value)) {
                    return this.copies.get(value);
                }
                if (value.getClass() == org.codehaus.groovy.runtime.GStringImpl.class) {
                    groovy.lang.GString string = (groovy.lang.GString) value;
                    Object[] values = (Object[]) this.copy(string.getValues(), depth + 1);
                    String[] strings = (String[]) this.copy(string.getStrings(), depth + 1);
                    Object copied = this.materialize ?
                                    new org.codehaus.groovy.runtime.GStringImpl(values, strings) : value;
                    this.copies.put(value, copied);
                    return copied;
                }
                if (value instanceof Map) {
                    if (!Set.of("java.util.HashMap", "java.util.LinkedHashMap", "java.util.TreeMap",
                            "java.util.ImmutableCollections$Map1", "java.util.ImmutableCollections$MapN",
                            "java.util.Collections$EmptyMap", "java.util.Collections$SingletonMap")
                            .contains(value.getClass().getName()) || value.getClass().getClassLoader() != null) {
                        if (!isJsonMap(value)) {
                            throw denied("custom map implementation");
                        }
                    }
                    Map<Object, Object> map = this.materialize ? new LinkedHashMap<>() : null;
                    this.copies.put(value, this.materialize ? map : value);
                    for (Map.Entry<?, ?> item : ((Map<?, ?>) value).entrySet()) {
                        Object key = item.getKey();
                        if (key == null || (!SCALARS.contains(key.getClass()) &&
                                            key.getClass() != org.codehaus.groovy.runtime.GStringImpl.class)) {
                            throw denied("non-scalar map key");
                        }
                        Object copiedKey = this.copy(key, depth + 1);
                        Object copiedValue = this.copy(item.getValue(), depth + 1);
                        if (this.materialize) {
                            map.put(copiedKey, copiedValue);
                        }
                    }
                    return this.materialize ? map : value;
                }
                if (value instanceof Collection || value.getClass().isArray()) {
                    Collection<Object> list = !this.materialize ? null :
                                              value instanceof Set ? new LinkedHashSet<>() : new ArrayList<>();
                    if (value instanceof Collection) {
                        this.copies.put(value, this.materialize ? list : value);
                        if (!Set.of("java.util.ArrayList", "java.util.LinkedList", "java.util.Arrays$ArrayList",
                                "java.util.HashSet", "java.util.LinkedHashSet", "java.util.TreeSet",
                                "java.util.ImmutableCollections$List12", "java.util.ImmutableCollections$ListN",
                                "java.util.ImmutableCollections$Set12", "java.util.ImmutableCollections$SetN",
                                "java.util.Collections$EmptyList", "java.util.Collections$SingletonList",
                                "java.util.Collections$EmptySet", "java.util.Collections$SingletonSet")
                                .contains(value.getClass().getName()) || value.getClass().getClassLoader() != null) {
                            if (!this.allowsView(value)) {
                                throw denied("custom collection implementation");
                            }
                        }
                        for (Object item : (Collection<?>) value) {
                            Object copied = this.copy(item, depth + 1);
                            if (this.materialize) {
                                list.add(copied);
                            }
                        }
                    } else {
                        int length = Array.getLength(value);
                        Class<?> component = value.getClass().getComponentType();
                        if (!component.isPrimitive() && component != Object.class &&
                            !SCALARS.contains(component) && component != Date.class) {
                            throw denied("unsupported array component");
                        }
                        if (this.bounded && length > 4096 - this.nodes) {
                            throw denied("binding structure limit");
                        }
                        Object array = this.materialize ? Array.newInstance(component, length) : value;
                        this.copies.put(value, array);
                        if (!this.bounded && component.isPrimitive()) {
                            if (this.materialize) {
                                System.arraycopy(value, 0, array, 0, length);
                            }
                            return array;
                        }
                        for (int i = 0; i < length; i++) {
                            Object copied = this.copy(Array.get(value, i), depth + 1);
                            if (this.materialize) {
                                Array.set(array, i, copied);
                            }
                        }
                        return array;
                    }
                    return this.materialize ? list : value;
                }
                throw denied("unsupported binding type");
            } finally {
                this.active.remove(value);
            }
        }
    }
}
