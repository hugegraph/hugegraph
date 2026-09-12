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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.transform.stc.ExtensionMethodNode;

/** Exact declaring type, method name and parameter types for this dependency version. */
public final class ScriptMethodPolicy {

    public static final Set<String> TRAVERSAL_STEPS = Set.of(
            "V", "E", "addV", "addE", "has", "hasId", "hasLabel", "hasKey", "hasValue",
            "hasNot", "is", "not", "and", "or", "filter", "map", "flatMap", "constant",
            "out", "in", "both", "outE", "inE", "bothE", "outV", "inV", "bothV", "otherV",
            "values", "valueMap", "elementMap", "properties", "id", "label", "key", "value",
            "count", "sum", "min", "max", "mean", "fold", "unfold", "dedup", "order", "by",
            "limit", "range", "skip", "tail", "sample", "coin", "simplePath", "cyclicPath",
            "path", "select", "project", "as", "where", "match", "optional", "coalesce",
            "choose", "branch", "option", "union", "local", "repeat", "until", "emit",
            "times", "loops", "group", "groupCount", "aggregate", "store", "cap", "sack",
            "barrier", "identity", "inject", "property", "drop", "from", "to", "sideEffect");

    private final Set<String> signatures = new HashSet<>();

    public ScriptMethodPolicy(ScriptExecutionProfile profile) {

        this.allow("java.lang.String", "length", "isEmpty", "equals", "equalsIgnoreCase",
                   "compareTo", "contains", "startsWith", "endsWith", "substring", "trim",
                   "toLowerCase", "toUpperCase", "charAt", "indexOf", "lastIndexOf", "concat",
                   "toString", "valueOf");
        for (String name : new String[]{"Integer", "Long", "Double", "Float", "Short", "Byte",
                                        "Number", "Boolean", "Character"}) {
            this.allow("java.lang." + name, "intValue", "longValue", "doubleValue", "floatValue",
                       "shortValue", "byteValue", "booleanValue", "charValue", "compareTo",
                       "equals", "toString", "valueOf", "parseInt", "parseLong", "parseDouble");
        }
        this.allow("java.lang.Math", "abs", "min", "max", "floor", "ceil", "round", "sqrt");
        this.allow("java.util.UUID", "toString", "equals", "fromString");
        for (String type : new String[]{"java.util.List", "java.util.Collection", "java.util.Map",
                                       "java.util.ArrayList", "java.util.LinkedHashMap",
                                       "java.util.Set", "java.util.Map$Entry"}) {
            this.allow(type, "get", "getOrDefault", "contains", "containsKey", "containsValue",
                       "size", "isEmpty", "indexOf", "lastIndexOf", "keySet", "values",
                       "entrySet", "getKey", "getValue", "iterator", "subList");
            if (profile != ScriptExecutionProfile.STORE_FILTER) {
                this.allow(type, "add", "addAll", "put", "putAll", "remove", "set", "clear");
            }
        }
        this.allow("java.util.Iterator", "hasNext", "next");
        this.allow(ScriptExecutionBudget.class.getName(), "check", "deadline");
        this.allow("org.codehaus.groovy.runtime.DefaultGroovyMethods",
                   "getAt", "size", "contains", "isEmpty", "sum", "min", "max");
        this.allow("org.codehaus.groovy.runtime.StringGroovyMethods",
                   "getAt", "size", "contains", "isInteger", "isLong", "isNumber",
                   "toInteger", "toLong", "toDouble", "take", "drop", "plus", "minus");
        if (profile == ScriptExecutionProfile.STORE_FILTER) {
            this.allow(ScriptElementView.class.getName(), "id", "label", "property", "properties");
            this.verifyManifest(profile);
            return;
        }
        this.allowExact("org.codehaus.groovy.runtime.DefaultGroovyMethods", "next", Number.class);
        this.allowExact("org.codehaus.groovy.runtime.DefaultGroovyMethods", "previous", Number.class);
        this.allow("groovy.lang.Closure", "call");
        this.allow("org.codehaus.groovy.runtime.DefaultGroovyMethods",
                   "collect", "findAll", "find", "any", "every", "each", "eachWithIndex",
                   "inject", "groupBy", "sort", "unique", "reverse", "flatten", "plus", "minus",
                   "putAt", "leftShift", "toList", "toSet", "take", "drop");
        Set<String> methods = new HashSet<>(TRAVERSAL_STEPS);
        methods.addAll(Set.of("next", "hasNext", "tryNext", "toList", "toSet", "toBulkSet", "iterate"));
        this.allow("org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal",
                   methods.toArray(new String[0]));
        this.allow("org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__",
                   TRAVERSAL_STEPS.toArray(new String[0]));
        this.allow("org.apache.tinkerpop.gremlin.process.traversal.Traversal",
                   "next", "hasNext", "tryNext", "toList", "toSet", "toBulkSet", "iterate");
        this.allow("org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource",
                   "V", "E", "addV", "addE", "inject");
        this.allow("org.apache.tinkerpop.gremlin.process.traversal.Traverser", "get", "path",
                   "loops", "sack", "bulk");
        this.allow("org.apache.tinkerpop.gremlin.process.traversal.Path", "get", "objects", "labels",
                   "size", "isSimple");
        this.allow("org.apache.tinkerpop.gremlin.process.traversal.P",
                   "eq", "neq", "lt", "lte", "gt", "gte", "inside", "outside", "between",
                   "within", "without", "and", "or", "not", "negate");
        this.allow("org.apache.tinkerpop.gremlin.process.traversal.TextP",
                   "startingWith", "notStartingWith", "endingWith", "notEndingWith",
                   "containing", "notContaining");
        this.allow("org.apache.tinkerpop.gremlin.structure.Element", "id", "label", "value",
                   "property", "properties", "keys", "remove");
        this.allow("org.apache.tinkerpop.gremlin.structure.Vertex", "property", "properties",
                   "edges", "vertices", "addEdge");
        this.allow("org.apache.tinkerpop.gremlin.structure.Edge", "outVertex", "inVertex",
                   "vertices", "properties");
        this.allow("org.apache.tinkerpop.gremlin.structure.Property", "key", "value", "isPresent",
                   "remove", "orElse");
        this.allow("org.apache.tinkerpop.gremlin.structure.VertexProperty", "value", "properties");
        this.allow("java.util.Optional", "isPresent", "isEmpty", "get", "orElse");
        this.allow(ScriptJobContext.class.getName(),
                   "setMinSaveInterval", "updateProgress", "progress");
        if (profile == ScriptExecutionProfile.SCHEMA) {
            this.allow("org.apache.hugegraph.HugeGraph", "schema");
            this.allow("org.apache.hugegraph.schema.SchemaManager", "propertyKey", "vertexLabel",
                       "edgeLabel", "indexLabel");
            for (String type : new String[]{"PropertyKey", "VertexLabel", "EdgeLabel", "IndexLabel"}) {
                this.allow("org.apache.hugegraph.schema." + type + "$Builder",
                           "asText", "asInt", "asLong", "asDouble", "asFloat", "asBoolean",
                           "asByte", "asDate", "asBlob", "asUUID", "valueSingle", "valueList",
                           "valueSet", "properties", "primaryKeys", "nullableKeys", "id",
                           "useAutomaticId", "useCustomizeStringId", "useCustomizeNumberId",
                           "usePrimaryKeyId", "enableLabelIndex", "sourceLabel", "targetLabel",
                           "link", "singleTime", "multiTimes", "sortKeys", "onV", "onE", "by",
                           "secondary", "range", "search", "shard", "unique", "ifNotExist", "create");
            }
        }
        this.verifyManifest(profile);
    }

    public ScriptMethodPolicy(ScriptExecutionProfile profile, boolean session) {
        this(profile);
        if (session) {
            if (profile != ScriptExecutionProfile.QUERY) {
                throw new IllegalArgumentException("Session methods require the query profile");
            }
            Set<String> baseline = new HashSet<>(this.signatures);
            this.allowExact("org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource", "tx");
            this.allowExact("org.apache.tinkerpop.gremlin.structure.Graph", "tx");
            this.allowExact("org.apache.tinkerpop.gremlin.structure.Transaction", "commit");
            this.allowExact("org.apache.tinkerpop.gremlin.structure.Transaction", "rollback");
            Set<String> overlay = new HashSet<>(this.signatures);
            overlay.removeAll(baseline);
            this.verifyManifest("session-methods.txt", overlay);
        }
    }

    private void verifyManifest(ScriptExecutionProfile profile) {
        this.verifyManifest(profile.name().toLowerCase(Locale.ROOT) + "-methods.txt", this.signatures);
    }

    private void verifyManifest(String resource, Set<String> actual) {
        Set<String> approved = new HashSet<>();
        try (InputStream stream = ScriptMethodPolicy.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("Missing script policy manifest " + resource);
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                reader.lines().map(String::trim).filter(line -> !line.isEmpty() && !line.startsWith("#"))
                      .forEach(approved::add);
            }
        } catch (IOException error) {
            throw new IllegalStateException("Cannot load script policy manifest", error);
        }
        if (!actual.equals(approved)) {
            throw new IllegalStateException("Script policy dependency signatures changed for " + resource);
        }
    }

    public boolean allows(MethodNode target) {
        if (target instanceof ExtensionMethodNode) {
            target = ((ExtensionMethodNode) target).getExtensionMethodNode();
        }
        String parameters = Arrays.stream(target.getParameters())
                                  .map(p -> typeName(p.getType()))
                                  .collect(Collectors.joining(","));
        return this.signatures.contains(target.getDeclaringClass().getName() + "#" +
                                        target.getName() + "(" + parameters + ")");
    }

    private static String typeName(ClassNode type) {
        if (type.isArray()) {
            return typeName(type.getComponentType()) + "[]";
        }
        return type.redirect().getName();
    }

    public Set<String> signatures() {
        return Collections.unmodifiableSet(this.signatures);
    }

    private void allowExact(String className, String name, Class<?>... parameterTypes) {
        try {
            Class<?> type = Class.forName(className, false, ScriptMethodPolicy.class.getClassLoader());
            Method method = type.getMethod(name, parameterTypes);
            String parameters = Arrays.stream(method.getParameterTypes())
                                      .map(Class::getTypeName).collect(Collectors.joining(","));
            this.signatures.add(method.getDeclaringClass().getName() + "#" + name + "(" + parameters + ")");
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Missing exact script policy method " + className + "#" + name, error);
        }
    }

    private void allow(String className, String... names) {
        Set<String> allowedNames = new HashSet<>(Arrays.asList(names));
        try {
            Class<?> type = Class.forName(className, false, ScriptMethodPolicy.class.getClassLoader());
            for (Method method : type.getMethods()) {
                if (!Modifier.isPublic(method.getModifiers()) ||
                    !allowedNames.contains(method.getName())) {
                    continue;
                }
                if (className.equals("org.codehaus.groovy.runtime.DefaultGroovyMethods")) {
                    Class<?>[] parameters = method.getParameterTypes();
                    if (parameters.length > 0 && parameters[0] == Object.class ||
                        (method.getName().equals("getAt") && parameters.length == 2 &&
                         parameters[0] == Collection.class &&
                         parameters[1] == String.class) ||
                        (method.getName().equals("groupBy") && parameters.length >= 2 &&
                         (parameters[1] == Object[].class || parameters[1] == List.class))) {
                        // Generic helpers can read arbitrary bean properties.
                        continue;
                    }
                }
                String parameters = Arrays.stream(method.getParameterTypes())
                                          .map(Class::getTypeName).collect(Collectors.joining(","));
                this.signatures.add(method.getDeclaringClass().getName() + "#" +
                                    method.getName() + "(" + parameters + ")");
            }
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Missing script policy type: " + className, e);
        }
    }
}
