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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.hugegraph.HugeGraph;
import org.apache.hugegraph.schema.SchemaTemplatePlan.SchemaCommand;
import org.apache.hugegraph.schema.SchemaTemplatePlan.SchemaOperation;
import org.apache.hugegraph.schema.builder.SchemaBuilder;
import org.apache.hugegraph.type.HugeType;
import org.apache.hugegraph.type.define.AggregateType;
import org.apache.hugegraph.type.define.Cardinality;
import org.apache.hugegraph.type.define.DataType;
import org.apache.hugegraph.type.define.Frequency;
import org.apache.hugegraph.type.define.IdStrategy;
import org.apache.hugegraph.type.define.IndexType;
import org.apache.hugegraph.type.define.WriteType;
import org.apache.hugegraph.util.E;

public final class SchemaTemplateExecutor {

    private SchemaTemplateExecutor() {
    }

    public static void execute(HugeGraph graph, String template) {
        execute(graph, RestrictedSchemaTemplateParser.parse(template));
    }

    public static void execute(HugeGraph graph, SchemaTemplatePlan plan) {
        E.checkNotNull(graph, "graph");
        E.checkNotNull(plan, "schema template plan");
        for (SchemaCommand command : plan.commands()) {
            switch (command.kind()) {
                case PROPERTY_KEY:
                    executePropertyKey(graph, command);
                    break;
                case VERTEX_LABEL:
                    executeVertexLabel(graph, command);
                    break;
                case EDGE_LABEL:
                    executeEdgeLabel(graph, command);
                    break;
                case INDEX_LABEL:
                    executeIndexLabel(graph, command);
                    break;
                default:
                    throw new AssertionError("Unknown schema kind " +
                                             command.kind());
            }
        }
    }

    private static void executePropertyKey(HugeGraph graph,
                                           SchemaCommand command) {
        PropertyKey.Builder builder = graph.schema().propertyKey(command.name());
        for (SchemaOperation operation : command.operations()) {
            if (applyCommon(builder, operation)) {
                continue;
            }
            switch (operation.method()) {
                case AS_TEXT:
                    builder.asText();
                    break;
                case AS_INT:
                    builder.asInt();
                    break;
                case AS_DATE:
                    builder.asDate();
                    break;
                case AS_UUID:
                    builder.asUUID();
                    break;
                case AS_BOOLEAN:
                    builder.asBoolean();
                    break;
                case AS_BYTE:
                    builder.asByte();
                    break;
                case AS_BLOB:
                    builder.asBlob();
                    break;
                case AS_DOUBLE:
                    builder.asDouble();
                    break;
                case AS_FLOAT:
                    builder.asFloat();
                    break;
                case AS_LONG:
                    builder.asLong();
                    break;
                case VALUE_SINGLE:
                    builder.valueSingle();
                    break;
                case VALUE_LIST:
                    builder.valueList();
                    break;
                case VALUE_SET:
                    builder.valueSet();
                    break;
                case CALC_MAX:
                    builder.calcMax();
                    break;
                case CALC_MIN:
                    builder.calcMin();
                    break;
                case CALC_SUM:
                    builder.calcSum();
                    break;
                case CALC_OLD:
                    builder.calcOld();
                    break;
                case CALC_SET:
                    builder.calcSet();
                    break;
                case CALC_LIST:
                    builder.calcList();
                    break;
                case WRITE_TYPE:
                    builder.writeType(argument(operation, 0, WriteType.class));
                    break;
                case CARDINALITY:
                    builder.cardinality(argument(operation, 0,
                                                 Cardinality.class));
                    break;
                case DATA_TYPE:
                    builder.dataType(argument(operation, 0, DataType.class));
                    break;
                case AGGREGATE_TYPE:
                    builder.aggregateType(argument(operation, 0,
                                                   AggregateType.class));
                    break;
                case USERDATA:
                    applyUserdata(builder, operation);
                    break;
                default:
                    throw unexpected(command, operation);
            }
        }
    }

    private static void executeVertexLabel(HugeGraph graph,
                                           SchemaCommand command) {
        VertexLabel.Builder builder = graph.schema().vertexLabel(command.name());
        for (SchemaOperation operation : command.operations()) {
            if (applyCommon(builder, operation)) {
                continue;
            }
            switch (operation.method()) {
                case ID_STRATEGY:
                    builder.idStrategy(argument(operation, 0, IdStrategy.class));
                    break;
                case USE_AUTOMATIC_ID:
                    builder.useAutomaticId();
                    break;
                case USE_PRIMARY_KEY_ID:
                    builder.usePrimaryKeyId();
                    break;
                case USE_CUSTOMIZE_STRING_ID:
                    builder.useCustomizeStringId();
                    break;
                case USE_CUSTOMIZE_NUMBER_ID:
                    builder.useCustomizeNumberId();
                    break;
                case USE_CUSTOMIZE_UUID_ID:
                    builder.useCustomizeUuidId();
                    break;
                case PROPERTIES:
                    builder.properties(strings(operation));
                    break;
                case PRIMARY_KEYS:
                    builder.primaryKeys(strings(operation));
                    break;
                case NULLABLE_KEYS:
                    builder.nullableKeys(strings(operation));
                    break;
                case TTL:
                    builder.ttl(integer(operation, 0));
                    break;
                case TTL_START_TIME:
                    builder.ttlStartTime(string(operation, 0));
                    break;
                case ENABLE_LABEL_INDEX:
                    builder.enableLabelIndex(bool(operation, 0));
                    break;
                case USERDATA:
                    applyUserdata(builder, operation);
                    break;
                default:
                    throw unexpected(command, operation);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private static void executeEdgeLabel(HugeGraph graph,
                                         SchemaCommand command) {
        EdgeLabel.Builder builder = graph.schema().edgeLabel(command.name());
        for (SchemaOperation operation : command.operations()) {
            if (applyCommon(builder, operation)) {
                continue;
            }
            switch (operation.method()) {
                case AS_BASE:
                    builder.asBase();
                    break;
                case WITH_BASE:
                    builder.withBase(string(operation, 0));
                    break;
                case LINK:
                    builder.link(string(operation, 0), string(operation, 1));
                    break;
                case SOURCE_LABEL:
                    builder.sourceLabel(string(operation, 0));
                    break;
                case TARGET_LABEL:
                    builder.targetLabel(string(operation, 0));
                    break;
                case SINGLE_TIME:
                    builder.singleTime();
                    break;
                case MULTI_TIMES:
                    builder.multiTimes();
                    break;
                case SORT_KEYS:
                    builder.sortKeys(strings(operation));
                    break;
                case PROPERTIES:
                    builder.properties(strings(operation));
                    break;
                case NULLABLE_KEYS:
                    builder.nullableKeys(strings(operation));
                    break;
                case FREQUENCY:
                    builder.frequency(argument(operation, 0, Frequency.class));
                    break;
                case TTL:
                    builder.ttl(integer(operation, 0));
                    break;
                case TTL_START_TIME:
                    builder.ttlStartTime(string(operation, 0));
                    break;
                case ENABLE_LABEL_INDEX:
                    builder.enableLabelIndex(bool(operation, 0));
                    break;
                case USERDATA:
                    applyUserdata(builder, operation);
                    break;
                default:
                    throw unexpected(command, operation);
            }
        }
    }

    private static void executeIndexLabel(HugeGraph graph,
                                          SchemaCommand command) {
        IndexLabel.Builder builder = graph.schema().indexLabel(command.name());
        for (SchemaOperation operation : command.operations()) {
            if (applyCommon(builder, operation)) {
                continue;
            }
            switch (operation.method()) {
                case ON_V:
                    builder.onV(string(operation, 0));
                    break;
                case ON_E:
                    builder.onE(string(operation, 0));
                    break;
                case BY:
                    builder.by(strings(operation));
                    break;
                case SECONDARY:
                    builder.secondary();
                    break;
                case RANGE:
                    builder.range();
                    break;
                case SEARCH:
                    builder.search();
                    break;
                case SHARD:
                    builder.shard();
                    break;
                case UNIQUE:
                    builder.unique();
                    break;
                case ON:
                    builder.on(argument(operation, 0, HugeType.class),
                               string(operation, 1));
                    break;
                case INDEX_TYPE:
                    builder.indexType(argument(operation, 0, IndexType.class));
                    break;
                case USERDATA:
                    applyUserdata(builder, operation);
                    break;
                case REBUILD:
                    builder.rebuild(bool(operation, 0));
                    break;
                default:
                    throw unexpected(command, operation);
            }
        }
    }

    private static boolean applyCommon(SchemaBuilder<?> builder,
                                       SchemaOperation operation) {
        switch (operation.method()) {
            case ID:
                builder.id(integer(operation, 0));
                return true;
            case IF_NOT_EXIST:
                builder.ifNotExist();
                return true;
            case CHECK_EXIST:
                builder.checkExist(bool(operation, 0));
                return true;
            case CREATE:
                builder.create();
                return true;
            default:
                return false;
        }
    }

    private static IllegalArgumentException unexpected(
                                            SchemaCommand command,
                                            SchemaOperation operation) {
        return new IllegalArgumentException(
                   "Unexpected method '" + operation.method().dslName() +
                   "' for " + command.kind().dslName());
    }

    private static String string(SchemaOperation operation, int index) {
        return argument(operation, index, String.class);
    }

    private static long integer(SchemaOperation operation, int index) {
        return argument(operation, index, Long.class);
    }

    private static boolean bool(SchemaOperation operation, int index) {
        return argument(operation, index, Boolean.class);
    }

    private static Object value(SchemaOperation operation, int index) {
        return operation.arguments().get(index);
    }

    private static String[] strings(SchemaOperation operation) {
        List<Object> arguments = operation.arguments();
        if (arguments.size() == 1 && arguments.get(0) instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> values = (List<Object>) arguments.get(0);
            return strings(operation, values);
        }
        return strings(operation, arguments);
    }

    private static String[] strings(SchemaOperation operation,
                                    List<Object> arguments) {
        String[] values = new String[arguments.size()];
        for (int i = 0; i < arguments.size(); i++) {
            Object value = arguments.get(i);
            if (!(value instanceof String)) {
                throw new IllegalArgumentException(
                          "Expected String for method '" +
                          operation.method().dslName() + "' argument " + i);
            }
            values[i] = (String) value;
        }
        return values;
    }

    private static void applyUserdata(PropertyKey.Builder builder,
                                      SchemaOperation operation) {
        if (operation.arguments().size() == 1) {
            builder.userdata(userdata(operation));
        } else {
            builder.userdata(string(operation, 0), value(operation, 1));
        }
    }

    private static void applyUserdata(VertexLabel.Builder builder,
                                      SchemaOperation operation) {
        if (operation.arguments().size() == 1) {
            builder.userdata(userdata(operation));
        } else {
            builder.userdata(string(operation, 0), value(operation, 1));
        }
    }

    private static void applyUserdata(EdgeLabel.Builder builder,
                                      SchemaOperation operation) {
        if (operation.arguments().size() == 1) {
            builder.userdata(userdata(operation));
        } else {
            builder.userdata(string(operation, 0), value(operation, 1));
        }
    }

    private static void applyUserdata(IndexLabel.Builder builder,
                                      SchemaOperation operation) {
        if (operation.arguments().size() == 1) {
            builder.userdata(userdata(operation));
        } else {
            builder.userdata(string(operation, 0), value(operation, 1));
        }
    }

    private static Map<String, Object> userdata(SchemaOperation operation) {
        Object value = operation.arguments().get(0);
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException(
                      "Expected Map for method 'userdata' argument 0");
        }
        Map<?, ?> source = (Map<?, ?>) value;
        Map<String, Object> userdata = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String)) {
                throw new IllegalArgumentException(
                          "Expected String key for method 'userdata'");
            }
            userdata.put((String) entry.getKey(), entry.getValue());
        }
        return userdata;
    }

    private static <T> T argument(SchemaOperation operation, int index,
                                  Class<T> type) {
        Object value = operation.arguments().get(index);
        if (!type.isInstance(value)) {
            throw new IllegalArgumentException(
                      "Expected " + type.getSimpleName() + " for method '" +
                      operation.method().dslName() + "' argument " + index);
        }
        return type.cast(value);
    }
}
