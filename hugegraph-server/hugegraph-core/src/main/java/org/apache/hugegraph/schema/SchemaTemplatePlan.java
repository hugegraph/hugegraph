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

package org.apache.hugegraph.schema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.hugegraph.util.E;

public final class SchemaTemplatePlan {

    private final List<SchemaCommand> commands;

    SchemaTemplatePlan(List<SchemaCommand> commands) {
        E.checkArgument(commands != null && !commands.isEmpty(),
                        "Schema template must contain at least one command");
        this.commands = immutableCopy(commands);
    }

    public List<SchemaCommand> commands() {
        return this.commands;
    }

    private static <T> List<T> immutableCopy(List<T> values) {
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    public enum SchemaKind {
        PROPERTY_KEY("propertyKey"),
        VERTEX_LABEL("vertexLabel"),
        EDGE_LABEL("edgeLabel"),
        INDEX_LABEL("indexLabel");

        private final String dslName;

        SchemaKind(String dslName) {
            this.dslName = dslName;
        }

        public String dslName() {
            return this.dslName;
        }

        static SchemaKind fromDsl(String name) {
            for (SchemaKind kind : values()) {
                if (kind.dslName.equals(name)) {
                    return kind;
                }
            }
            return null;
        }
    }

    public enum SchemaMethod {
        ID("id"),
        AS_TEXT("asText"),
        AS_INT("asInt"),
        AS_DATE("asDate"),
        AS_UUID("asUUID"),
        AS_BOOLEAN("asBoolean"),
        AS_BYTE("asByte"),
        AS_BLOB("asBlob"),
        AS_DOUBLE("asDouble"),
        AS_FLOAT("asFloat"),
        AS_LONG("asLong"),
        VALUE_SINGLE("valueSingle"),
        VALUE_LIST("valueList"),
        VALUE_SET("valueSet"),
        CALC_MAX("calcMax"),
        CALC_MIN("calcMin"),
        CALC_SUM("calcSum"),
        CALC_OLD("calcOld"),
        CALC_SET("calcSet"),
        CALC_LIST("calcList"),
        WRITE_TYPE("writeType"),
        CARDINALITY("cardinality"),
        DATA_TYPE("dataType"),
        AGGREGATE_TYPE("aggregateType"),
        USERDATA("userdata"),
        IF_NOT_EXIST("ifNotExist"),
        CHECK_EXIST("checkExist"),
        ID_STRATEGY("idStrategy"),
        USE_AUTOMATIC_ID("useAutomaticId"),
        USE_PRIMARY_KEY_ID("usePrimaryKeyId"),
        USE_CUSTOMIZE_STRING_ID("useCustomizeStringId"),
        USE_CUSTOMIZE_NUMBER_ID("useCustomizeNumberId"),
        USE_CUSTOMIZE_UUID_ID("useCustomizeUuidId"),
        PROPERTIES("properties"),
        PRIMARY_KEYS("primaryKeys"),
        NULLABLE_KEYS("nullableKeys"),
        TTL("ttl"),
        TTL_START_TIME("ttlStartTime"),
        ENABLE_LABEL_INDEX("enableLabelIndex"),
        AS_BASE("asBase"),
        WITH_BASE("withBase"),
        LINK("link"),
        SOURCE_LABEL("sourceLabel"),
        TARGET_LABEL("targetLabel"),
        SINGLE_TIME("singleTime"),
        MULTI_TIMES("multiTimes"),
        SORT_KEYS("sortKeys"),
        FREQUENCY("frequency"),
        ON_V("onV"),
        ON_E("onE"),
        BY("by"),
        SECONDARY("secondary"),
        RANGE("range"),
        SEARCH("search"),
        SHARD("shard"),
        UNIQUE("unique"),
        ON("on"),
        INDEX_TYPE("indexType"),
        REBUILD("rebuild"),
        CREATE("create");

        private static final Map<String, SchemaMethod> METHODS_BY_DSL_NAME;

        static {
            Map<String, SchemaMethod> methods = new HashMap<>();
            for (SchemaMethod method : values()) {
                methods.put(method.dslName, method);
            }
            METHODS_BY_DSL_NAME = Collections.unmodifiableMap(methods);
        }

        private final String dslName;

        SchemaMethod(String dslName) {
            this.dslName = dslName;
        }

        public String dslName() {
            return this.dslName;
        }

        static SchemaMethod fromDsl(String name) {
            return METHODS_BY_DSL_NAME.get(name);
        }
    }

    public static final class SchemaCommand {

        private final SchemaKind kind;
        private final String name;
        private final List<SchemaOperation> operations;

        SchemaCommand(SchemaKind kind, String name,
                      List<SchemaOperation> operations) {
            E.checkNotNull(kind, "schema kind");
            E.checkNotNull(name, "schema name");
            E.checkNotNull(operations, "schema operations");
            this.kind = kind;
            this.name = name;
            this.operations = immutableCopy(operations);
        }

        public SchemaKind kind() {
            return this.kind;
        }

        public String name() {
            return this.name;
        }

        public List<SchemaOperation> operations() {
            return this.operations;
        }
    }

    public static final class SchemaOperation {

        private final SchemaMethod method;
        private final List<Object> arguments;

        SchemaOperation(SchemaMethod method, List<Object> arguments) {
            E.checkNotNull(method, "schema method");
            E.checkNotNull(arguments, "schema arguments");
            this.method = method;
            this.arguments = immutableArguments(arguments);
        }

        public SchemaMethod method() {
            return this.method;
        }

        public List<Object> arguments() {
            return this.arguments;
        }

        private static List<Object> immutableArguments(List<Object> values) {
            List<Object> copy = new ArrayList<>(values.size());
            for (Object value : values) {
                copy.add(immutableValue(value));
            }
            return Collections.unmodifiableList(copy);
        }

        private static Object immutableValue(Object value) {
            if (value instanceof List) {
                @SuppressWarnings("unchecked")
                List<Object> list = (List<Object>) value;
                return immutableArguments(list);
            }
            if (value instanceof Map) {
                Map<?, ?> map = (Map<?, ?>) value;
                Map<Object, Object> copy = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    copy.put(entry.getKey(), immutableValue(entry.getValue()));
                }
                return Collections.unmodifiableMap(copy);
            }
            return value;
        }
    }
}
