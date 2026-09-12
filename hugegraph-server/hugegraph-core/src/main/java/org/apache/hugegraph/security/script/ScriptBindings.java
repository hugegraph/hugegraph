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
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import javax.lang.model.SourceVersion;
import javax.script.Bindings;
import javax.script.SimpleBindings;

import org.apache.hugegraph.HugeGraph;
import org.apache.hugegraph.schema.SchemaManager;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;

public final class ScriptBindings {

    public static final int MAX_SOURCE_BYTES = 65536;
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
        if (!trustedObjects && input.size() > 256) {
            throw denied("too many bindings");
        }
        Budget budget = new Budget();
        Bindings result = new SimpleBindings();
        input.forEach((name, value) -> {
            if (trustedObjects && (value instanceof HugeGraph || value instanceof GraphTraversalSource) &&
                name != null && !name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                return;
            }
            if (result.size() >= 256) {
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
                result.put(name, value);
            } else if (trustedObjects && value instanceof ScriptJobContext &&
                       profile == ScriptExecutionProfile.QUERY) {
                result.put(name, value);
            } else if (trustedObjects && value instanceof SchemaManager &&
                       profile == ScriptExecutionProfile.SCHEMA) {
                result.put(name, value);
            } else if (trustedObjects && value instanceof ScriptElementView &&
                       profile == ScriptExecutionProfile.STORE_FILTER &&
                       "element".equals(name)) {
                result.put(name, value);
            } else {
                result.put(name, budget.copy(value, 0));
            }
        });
        return result;
    }

    static Object data(Object value) {
        return new Budget().copy(value, 0);
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
            } else {
                type = value.getClass();
            }
            result.put(name, type);
        });
        return Collections.unmodifiableMap(result);
    }

    private static void validateName(String name) {
        if (name == null || name.length() > 128 || !name.matches("[A-Za-z_][A-Za-z0-9_]*") ||
            name.startsWith("__hg") || RESERVED.contains(name) ||
            SourceVersion.isKeyword(name)) {
            throw denied("invalid or reserved binding name");
        }
    }

    private static IllegalArgumentException denied(String reason) {
        return new IllegalArgumentException("SCRIPT_BINDING_DENIED: " + reason);
    }

    private static final class Budget {

        private int nodes;
        private long size;
        private final IdentityHashMap<Object, Boolean> active = new IdentityHashMap<>();

        Object copy(Object value, int depth) {
            if (++this.nodes > 4096 || depth > 16) {
                throw denied("binding structure limit");
            }
            if (value == null) {
                return null;
            }
            if (SCALARS.contains(value.getClass())) {
                this.size += value.toString().length() * 2L;
                if (this.size > MAX_SOURCE_BYTES) {
                    throw denied("binding size limit");
                }
                return value;
            }
            if (this.active.put(value, Boolean.TRUE) != null) {
                throw denied("cyclic binding");
            }
            try {
                if (value instanceof Map) {
                    if (!Set.of("java.util.HashMap", "java.util.LinkedHashMap", "java.util.TreeMap",
                            "java.util.ImmutableCollections$Map1", "java.util.ImmutableCollections$MapN",
                            "java.util.Collections$EmptyMap", "java.util.Collections$SingletonMap")
                            .contains(value.getClass().getName()) || value.getClass().getClassLoader() != null) {
                        throw denied("custom map implementation");
                    }
                    Map<Object, Object> map = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> item : ((Map<?, ?>) value).entrySet()) {
                        Object key = item.getKey();
                        if (key == null || !SCALARS.contains(key.getClass())) {
                            throw denied("non-scalar map key");
                        }
                        map.put(this.copy(key, depth + 1), this.copy(item.getValue(), depth + 1));
                    }
                    return map;
                }
                if (value instanceof Collection || value.getClass().isArray()) {
                    List<Object> list = new ArrayList<>();
                    if (value instanceof Collection) {
                        if (!Set.of("java.util.ArrayList", "java.util.LinkedList", "java.util.Arrays$ArrayList",
                                "java.util.HashSet", "java.util.LinkedHashSet", "java.util.TreeSet",
                                "java.util.ImmutableCollections$List12", "java.util.ImmutableCollections$ListN",
                                "java.util.ImmutableCollections$Set12", "java.util.ImmutableCollections$SetN",
                                "java.util.Collections$EmptyList", "java.util.Collections$SingletonList",
                                "java.util.Collections$EmptySet", "java.util.Collections$SingletonSet")
                                .contains(value.getClass().getName()) || value.getClass().getClassLoader() != null) {
                            throw denied("custom collection implementation");
                        }
                        for (Object item : (Collection<?>) value) {
                            list.add(this.copy(item, depth + 1));
                        }
                    } else {
                        for (int i = 0; i < Array.getLength(value); i++) {
                            list.add(this.copy(Array.get(value, i), depth + 1));
                        }
                    }
                    return list;
                }
                throw denied("unsupported binding type");
            } finally {
                this.active.remove(value);
            }
        }
    }
}
