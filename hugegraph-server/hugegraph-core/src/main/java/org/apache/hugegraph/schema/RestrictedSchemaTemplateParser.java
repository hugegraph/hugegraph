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
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.hugegraph.schema.SchemaTemplatePlan.SchemaCommand;
import org.apache.hugegraph.schema.SchemaTemplatePlan.SchemaKind;
import org.apache.hugegraph.schema.SchemaTemplatePlan.SchemaMethod;
import org.apache.hugegraph.schema.SchemaTemplatePlan.SchemaOperation;
import org.apache.hugegraph.type.HugeType;
import org.apache.hugegraph.type.define.AggregateType;
import org.apache.hugegraph.type.define.Cardinality;
import org.apache.hugegraph.type.define.DataType;
import org.apache.hugegraph.type.define.Frequency;
import org.apache.hugegraph.type.define.IdStrategy;
import org.apache.hugegraph.type.define.IndexType;
import org.apache.hugegraph.type.define.WriteType;

public final class RestrictedSchemaTemplateParser {

    private static final int MAX_LITERAL_NESTING = 32;
    private static final Map<SchemaKind, Map<SchemaMethod, ArgumentRule>>
            ALLOWED_METHODS = allowedMethods();

    private final String input;
    private int offset;
    private int line;
    private int column;
    private int literalNesting;

    private RestrictedSchemaTemplateParser(String input) {
        if (input == null) {
            throw new IllegalArgumentException(
                      "Schema template is null at line 1, column 1");
        }
        this.input = input;
        this.offset = 0;
        this.line = 1;
        this.column = 1;
        this.literalNesting = 0;
    }

    public static SchemaTemplatePlan parse(String template) {
        return new RestrictedSchemaTemplateParser(template).parseDocument();
    }

    public static void validate(String template) {
        parse(template);
    }

    private SchemaTemplatePlan parseDocument() {
        this.skipIgnored();
        this.tryParseSchemaAssignment();

        List<SchemaCommand> commands = new ArrayList<>();
        this.skipIgnored();
        while (!this.end()) {
            commands.add(this.parseCommand());
            this.skipIgnored();
        }
        if (commands.isEmpty()) {
            throw this.error("Schema template contains no schema commands");
        }
        return new SchemaTemplatePlan(commands);
    }

    private void tryParseSchemaAssignment() {
        Cursor cursor = this.cursor();
        if (!this.tryIdentifier("schema")) {
            return;
        }
        this.skipIgnored();
        if (!this.tryConsume('=')) {
            this.restore(cursor);
            return;
        }

        this.skipIgnored();
        this.expectIdentifier("graph");
        this.skipIgnored();
        this.expect('.');
        this.skipIgnored();
        this.expectIdentifier("schema");
        this.skipIgnored();
        this.expect('(');
        this.skipIgnored();
        this.expect(')');
        boolean separated = this.end() || this.peek(';') ||
                            this.ignoredAhead();
        this.skipIgnored();
        this.tryConsume(';');
        this.skipIgnored();
        if (!separated && !this.end()) {
            throw this.error("Expected a separator after schema assignment");
        }
    }

    private SchemaCommand parseCommand() {
        String root = this.readIdentifier("schema command root");
        if ("schema".equals(root)) {
            this.skipIgnored();
            this.expect('.');
        } else if ("graph".equals(root)) {
            this.skipIgnored();
            this.expect('.');
            this.skipIgnored();
            this.expectIdentifier("schema");
            this.skipIgnored();
            this.expect('(');
            this.skipIgnored();
            this.expect(')');
            this.skipIgnored();
            this.expect('.');
        } else {
            throw this.error("Unsupported schema template root '" + root + "'");
        }

        this.skipIgnored();
        String builder = this.readIdentifier("schema builder");
        SchemaKind kind = SchemaKind.fromDsl(builder);
        if (kind == null) {
            throw this.error("Unsupported schema builder '" + builder + "'");
        }
        this.skipIgnored();
        this.expect('(');
        this.skipIgnored();
        Object name = this.parseLiteral();
        if (!(name instanceof String)) {
            throw this.error("Schema name must be a string literal");
        }
        this.skipIgnored();
        this.expect(')');

        List<SchemaOperation> operations = new ArrayList<>();
        boolean created = false;
        while (!created) {
            this.skipIgnored();
            this.expect('.');
            this.skipIgnored();
            String methodName = this.readIdentifier("schema method");
            SchemaMethod method = SchemaMethod.fromDsl(methodName);
            if (method == null) {
                throw this.error("Unsupported schema method '" + methodName + "'");
            }
            this.skipIgnored();
            List<Object> arguments = this.parseArguments();
            this.validateOperation(kind, method, arguments);
            operations.add(new SchemaOperation(method, arguments));
            created = method == SchemaMethod.CREATE;
        }

        boolean separated = this.end() || this.peek(';') ||
                            this.ignoredAhead();
        this.skipIgnored();
        if (this.peek('.')) {
            throw this.error("No method is allowed after create()");
        }
        if (!this.tryConsume(';') && !separated && !this.end()) {
            throw this.error("Expected a separator after create()");
        }
        return new SchemaCommand(kind, (String) name, operations);
    }

    private List<Object> parseArguments() {
        this.expect('(');
        this.skipIgnored();
        List<Object> arguments = new ArrayList<>();
        if (this.tryConsume(')')) {
            return arguments;
        }

        while (true) {
            arguments.add(this.parseLiteral());
            this.skipIgnored();
            if (this.tryConsume(')')) {
                return arguments;
            }
            this.expect(',');
            this.skipIgnored();
        }
    }

    private Object parseLiteral() {
        if (this.end()) {
            throw this.error("Expected a literal but reached the end");
        }
        char current = this.current();
        if (current == '[') {
            return this.readCollection();
        }
        if (current == '\'' || current == '"') {
            return this.readString();
        }
        if (current == '-' || Character.isDigit(current)) {
            return this.readNumber();
        }
        if (isIdentifierStart(current)) {
            String identifier = this.readIdentifier("literal");
            if ("true".equals(identifier)) {
                return Boolean.TRUE;
            }
            if ("false".equals(identifier)) {
                return Boolean.FALSE;
            }
            return this.readEnum(identifier);
        }
        throw this.error("Unsupported literal starting with '" + current + "'");
    }

    private Object readCollection() {
        if (this.literalNesting >= MAX_LITERAL_NESTING) {
            throw this.error("Literal nesting exceeds " +
                             MAX_LITERAL_NESTING + " levels");
        }
        this.literalNesting++;
        try {
            return this.readCollectionValue();
        } finally {
            this.literalNesting--;
        }
    }

    private Object readCollectionValue() {
        this.expect('[');
        this.skipIgnored();
        if (this.tryConsume(']')) {
            return new ArrayList<>();
        }
        if (this.tryConsume(':')) {
            this.skipIgnored();
            this.expect(']');
            return new LinkedHashMap<String, Object>();
        }

        Object first = this.parseLiteral();
        this.skipIgnored();
        if (this.tryConsume(':')) {
            if (!(first instanceof String)) {
                throw this.error("Map literal keys must be strings");
            }
            Map<String, Object> values = new LinkedHashMap<>();
            this.skipIgnored();
            this.putMapValue(values, (String) first, this.parseLiteral());
            this.skipIgnored();
            while (this.tryConsume(',')) {
                this.skipIgnored();
                if (this.tryConsume(']')) {
                    return values;
                }
                Object key = this.parseLiteral();
                if (!(key instanceof String)) {
                    throw this.error("Map literal keys must be strings");
                }
                this.skipIgnored();
                this.expect(':');
                this.skipIgnored();
                this.putMapValue(values, (String) key, this.parseLiteral());
                this.skipIgnored();
            }
            this.expect(']');
            return values;
        }

        List<Object> values = new ArrayList<>();
        values.add(first);
        while (this.tryConsume(',')) {
            this.skipIgnored();
            if (this.tryConsume(']')) {
                return values;
            }
            values.add(this.parseLiteral());
            this.skipIgnored();
        }
        this.expect(']');
        return values;
    }

    private void putMapValue(Map<String, Object> values, String key,
                             Object value) {
        if (values.putIfAbsent(key, value) != null) {
            throw this.error("Duplicate map literal key '" + key + "'");
        }
    }

    private Object readEnum(String firstIdentifier) {
        List<String> parts = new ArrayList<>();
        parts.add(firstIdentifier);
        this.skipIgnored();
        while (this.tryConsume('.')) {
            this.skipIgnored();
            parts.add(this.readIdentifier("enum constant"));
            this.skipIgnored();
        }
        if (parts.size() < 2) {
            throw this.error("Unsupported dynamic value '" + firstIdentifier + "'");
        }

        String type = parts.get(parts.size() - 2);
        String constant = parts.get(parts.size() - 1);
        try {
            switch (type) {
                case "WriteType":
                    return WriteType.valueOf(constant);
                case "Cardinality":
                    return Cardinality.valueOf(constant);
                case "DataType":
                    return DataType.valueOf(constant);
                case "AggregateType":
                    return AggregateType.valueOf(constant);
                case "IdStrategy":
                    return IdStrategy.valueOf(constant);
                case "Frequency":
                    return Frequency.valueOf(constant);
                case "IndexType":
                    return IndexType.valueOf(constant);
                case "HugeType":
                    return HugeType.valueOf(constant);
                default:
                    throw this.error("Unsupported enum type '" + type + "'");
            }
        } catch (IllegalArgumentException e) {
            if (e.getMessage() != null && e.getMessage().contains("at line")) {
                throw e;
            }
            throw this.error("Unsupported enum constant '" + type + "." +
                             constant + "'");
        }
    }

    private String readString() {
        char quote = this.current();
        this.advance();
        StringBuilder value = new StringBuilder();
        while (!this.end()) {
            char current = this.current();
            this.advance();
            if (current == quote) {
                return value.toString();
            }
            if (current != '\\') {
                value.append(current);
                continue;
            }
            if (this.end()) {
                throw this.error("Unterminated string escape");
            }
            char escaped = this.current();
            this.advance();
            switch (escaped) {
                case 'b':
                    value.append('\b');
                    break;
                case 'f':
                    value.append('\f');
                    break;
                case 'n':
                    value.append('\n');
                    break;
                case 'r':
                    value.append('\r');
                    break;
                case 't':
                    value.append('\t');
                    break;
                case '\\':
                case '\'':
                case '"':
                    value.append(escaped);
                    break;
                default:
                    throw this.error("Unsupported string escape '\\" + escaped +
                                     "'");
            }
        }
        throw this.error("Unterminated string literal");
    }

    private Number readNumber() {
        int start = this.offset;
        this.tryConsume('-');
        if (this.end() || !Character.isDigit(this.current())) {
            throw this.error("Invalid numeric literal");
        }
        while (!this.end() && Character.isDigit(this.current())) {
            this.advance();
        }

        boolean floating = false;
        if (this.tryConsume('.')) {
            floating = true;
            if (this.end() || !Character.isDigit(this.current())) {
                throw this.error("Invalid numeric literal");
            }
            while (!this.end() && Character.isDigit(this.current())) {
                this.advance();
            }
        }
        if (!this.end() && (this.current() == 'e' || this.current() == 'E')) {
            floating = true;
            this.advance();
            if (!this.end() && (this.current() == '+' || this.current() == '-')) {
                this.advance();
            }
            if (this.end() || !Character.isDigit(this.current())) {
                throw this.error("Invalid numeric exponent");
            }
            while (!this.end() && Character.isDigit(this.current())) {
                this.advance();
            }
        }

        int numberEnd = this.offset;
        if (!this.end() && (this.current() == 'L' ||
                            this.current() == 'l')) {
            if (floating) {
                throw this.error("Long suffix is only allowed for integer " +
                                 "literals");
            }
            this.advance();
        }

        String value = this.input.substring(start, numberEnd);
        try {
            if (floating) {
                return Double.valueOf(value);
            }
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            throw this.error("Invalid numeric literal '" + value + "'");
        }
    }

    private void validateOperation(SchemaKind kind, SchemaMethod method,
                                   List<Object> arguments) {
        ArgumentRule rule = ALLOWED_METHODS.get(kind).get(method);
        if (rule == null) {
            throw this.error("Method '" + method.dslName() +
                             "' is not allowed for " + kind.dslName());
        }
        String mismatch = rule.validate(arguments);
        if (mismatch != null) {
            throw this.error("Invalid arguments for '" + method.dslName() +
                             "': " + mismatch);
        }
    }

    private void skipIgnored() {
        boolean skipped;
        do {
            skipped = false;
            while (!this.end() && Character.isWhitespace(this.current())) {
                this.advance();
                skipped = true;
            }
            if (this.startsWith("//")) {
                skipped = true;
                while (!this.end() && this.current() != '\n') {
                    this.advance();
                }
            } else if (this.startsWith("/*")) {
                skipped = true;
                this.advance();
                this.advance();
                while (!this.end() && !this.startsWith("*/")) {
                    this.advance();
                }
                if (this.end()) {
                    throw this.error("Unterminated block comment");
                }
                this.advance();
                this.advance();
            }
        } while (skipped);
    }

    private String readIdentifier(String description) {
        if (this.end() || !isIdentifierStart(this.current())) {
            throw this.error("Expected " + description);
        }
        int start = this.offset;
        this.advance();
        while (!this.end() && isIdentifierPart(this.current())) {
            this.advance();
        }
        return this.input.substring(start, this.offset);
    }

    private boolean tryIdentifier(String value) {
        if (this.end() || !isIdentifierStart(this.current())) {
            return false;
        }
        Cursor cursor = this.cursor();
        String actual = this.readIdentifier("identifier");
        if (value.equals(actual)) {
            return true;
        }
        this.restore(cursor);
        return false;
    }

    private void expectIdentifier(String value) {
        String actual = this.readIdentifier("'" + value + "'");
        if (!value.equals(actual)) {
            throw this.error("Expected '" + value + "' but found '" + actual +
                             "'");
        }
    }

    private void expect(char expected) {
        if (!this.tryConsume(expected)) {
            String actual = this.end() ? "end of template" :
                            "'" + this.current() + "'";
            throw this.error("Expected '" + expected + "' but found " + actual);
        }
    }

    private boolean tryConsume(char expected) {
        if (this.end() || this.current() != expected) {
            return false;
        }
        this.advance();
        return true;
    }

    private boolean peek(char expected) {
        return !this.end() && this.current() == expected;
    }

    private boolean startsWith(String value) {
        return this.input.startsWith(value, this.offset);
    }

    private boolean ignoredAhead() {
        return !this.end() &&
               (Character.isWhitespace(this.current()) ||
                this.startsWith("//") || this.startsWith("/*"));
    }

    private char current() {
        return this.input.charAt(this.offset);
    }

    private boolean end() {
        return this.offset >= this.input.length();
    }

    private void advance() {
        char current = this.input.charAt(this.offset++);
        if (current == '\n') {
            this.line++;
            this.column = 1;
        } else {
            this.column++;
        }
    }

    private Cursor cursor() {
        return new Cursor(this.offset, this.line, this.column);
    }

    private void restore(Cursor cursor) {
        this.offset = cursor.offset;
        this.line = cursor.line;
        this.column = cursor.column;
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException(message + " at line " + this.line +
                                            ", column " + this.column);
    }

    private static boolean isIdentifierStart(char value) {
        return value == '_' || value >= 'A' && value <= 'Z' ||
               value >= 'a' && value <= 'z';
    }

    private static boolean isIdentifierPart(char value) {
        return isIdentifierStart(value) || Character.isDigit(value);
    }

    private static Map<SchemaKind, Map<SchemaMethod, ArgumentRule>>
                   allowedMethods() {
        Map<SchemaKind, Map<SchemaMethod, ArgumentRule>> methods =
                new EnumMap<>(SchemaKind.class);
        for (SchemaKind kind : SchemaKind.values()) {
            methods.put(kind, new EnumMap<>(SchemaMethod.class));
            allow(methods, kind, ArgumentRule.LONG, SchemaMethod.ID);
            allow(methods, kind, ArgumentRule.USERDATA,
                  SchemaMethod.USERDATA);
            allow(methods, kind, ArgumentRule.NONE,
                  SchemaMethod.IF_NOT_EXIST, SchemaMethod.CREATE);
            allow(methods, kind, ArgumentRule.BOOLEAN, SchemaMethod.CHECK_EXIST);
        }

        allow(methods, SchemaKind.PROPERTY_KEY, ArgumentRule.NONE,
              SchemaMethod.AS_TEXT, SchemaMethod.AS_INT, SchemaMethod.AS_DATE,
              SchemaMethod.AS_UUID, SchemaMethod.AS_BOOLEAN,
              SchemaMethod.AS_BYTE, SchemaMethod.AS_BLOB,
              SchemaMethod.AS_DOUBLE, SchemaMethod.AS_FLOAT,
              SchemaMethod.AS_LONG, SchemaMethod.VALUE_SINGLE,
              SchemaMethod.VALUE_LIST, SchemaMethod.VALUE_SET,
              SchemaMethod.CALC_MAX, SchemaMethod.CALC_MIN,
              SchemaMethod.CALC_SUM, SchemaMethod.CALC_OLD,
              SchemaMethod.CALC_SET, SchemaMethod.CALC_LIST);
        allow(methods, SchemaKind.PROPERTY_KEY,
              ArgumentRule.enumValue(WriteType.class), SchemaMethod.WRITE_TYPE);
        allow(methods, SchemaKind.PROPERTY_KEY,
              ArgumentRule.enumValue(Cardinality.class),
              SchemaMethod.CARDINALITY);
        allow(methods, SchemaKind.PROPERTY_KEY,
              ArgumentRule.enumValue(DataType.class), SchemaMethod.DATA_TYPE);
        allow(methods, SchemaKind.PROPERTY_KEY,
              ArgumentRule.enumValue(AggregateType.class),
              SchemaMethod.AGGREGATE_TYPE);

        allow(methods, SchemaKind.VERTEX_LABEL,
              ArgumentRule.enumValue(IdStrategy.class),
              SchemaMethod.ID_STRATEGY);
        allow(methods, SchemaKind.VERTEX_LABEL, ArgumentRule.NONE,
              SchemaMethod.USE_AUTOMATIC_ID, SchemaMethod.USE_PRIMARY_KEY_ID,
              SchemaMethod.USE_CUSTOMIZE_STRING_ID,
              SchemaMethod.USE_CUSTOMIZE_NUMBER_ID,
              SchemaMethod.USE_CUSTOMIZE_UUID_ID);
        allow(methods, SchemaKind.VERTEX_LABEL, ArgumentRule.STRINGS,
              SchemaMethod.PROPERTIES, SchemaMethod.PRIMARY_KEYS,
              SchemaMethod.NULLABLE_KEYS);
        allow(methods, SchemaKind.VERTEX_LABEL, ArgumentRule.LONG,
              SchemaMethod.TTL);
        allow(methods, SchemaKind.VERTEX_LABEL, ArgumentRule.STRING,
              SchemaMethod.TTL_START_TIME);
        allow(methods, SchemaKind.VERTEX_LABEL, ArgumentRule.BOOLEAN,
              SchemaMethod.ENABLE_LABEL_INDEX);

        allow(methods, SchemaKind.EDGE_LABEL, ArgumentRule.NONE,
              SchemaMethod.AS_BASE, SchemaMethod.SINGLE_TIME,
              SchemaMethod.MULTI_TIMES);
        allow(methods, SchemaKind.EDGE_LABEL, ArgumentRule.STRING,
              SchemaMethod.WITH_BASE, SchemaMethod.SOURCE_LABEL,
              SchemaMethod.TARGET_LABEL, SchemaMethod.TTL_START_TIME);
        allow(methods, SchemaKind.EDGE_LABEL, ArgumentRule.TWO_STRINGS,
              SchemaMethod.LINK);
        allow(methods, SchemaKind.EDGE_LABEL, ArgumentRule.STRINGS,
              SchemaMethod.SORT_KEYS, SchemaMethod.PROPERTIES,
              SchemaMethod.NULLABLE_KEYS);
        allow(methods, SchemaKind.EDGE_LABEL,
              ArgumentRule.enumValue(Frequency.class), SchemaMethod.FREQUENCY);
        allow(methods, SchemaKind.EDGE_LABEL, ArgumentRule.LONG,
              SchemaMethod.TTL);
        allow(methods, SchemaKind.EDGE_LABEL, ArgumentRule.BOOLEAN,
              SchemaMethod.ENABLE_LABEL_INDEX);

        allow(methods, SchemaKind.INDEX_LABEL, ArgumentRule.STRING,
              SchemaMethod.ON_V, SchemaMethod.ON_E);
        allow(methods, SchemaKind.INDEX_LABEL, ArgumentRule.STRINGS,
              SchemaMethod.BY);
        allow(methods, SchemaKind.INDEX_LABEL, ArgumentRule.NONE,
              SchemaMethod.SECONDARY, SchemaMethod.RANGE, SchemaMethod.SEARCH,
              SchemaMethod.SHARD, SchemaMethod.UNIQUE);
        allow(methods, SchemaKind.INDEX_LABEL,
              ArgumentRule.ENUM_HUGE_TYPE_AND_STRING, SchemaMethod.ON);
        allow(methods, SchemaKind.INDEX_LABEL,
              ArgumentRule.enumValue(IndexType.class), SchemaMethod.INDEX_TYPE);
        allow(methods, SchemaKind.INDEX_LABEL, ArgumentRule.BOOLEAN,
              SchemaMethod.REBUILD);
        return methods;
    }

    private static void allow(
                        Map<SchemaKind, Map<SchemaMethod, ArgumentRule>> methods,
                        SchemaKind kind, ArgumentRule rule,
                        SchemaMethod... allowed) {
        for (SchemaMethod method : allowed) {
            methods.get(kind).put(method, rule);
        }
    }

    private static final class Cursor {

        private final int offset;
        private final int line;
        private final int column;

        private Cursor(int offset, int line, int column) {
            this.offset = offset;
            this.line = line;
            this.column = column;
        }
    }

    private static final class ArgumentRule {

        private static final ArgumentRule NONE = new ArgumentRule(Kind.NONE,
                                                                  null);
        private static final ArgumentRule LONG = new ArgumentRule(Kind.LONG,
                                                                  null);
        private static final ArgumentRule BOOLEAN =
                new ArgumentRule(Kind.BOOLEAN, null);
        private static final ArgumentRule STRING =
                new ArgumentRule(Kind.STRING, null);
        private static final ArgumentRule STRINGS =
                new ArgumentRule(Kind.STRINGS, null);
        private static final ArgumentRule TWO_STRINGS =
                new ArgumentRule(Kind.TWO_STRINGS, null);
        private static final ArgumentRule USERDATA =
                new ArgumentRule(Kind.USERDATA, null);
        private static final ArgumentRule ENUM_HUGE_TYPE_AND_STRING =
                new ArgumentRule(Kind.ENUM_HUGE_TYPE_AND_STRING, null);

        private final Kind kind;
        private final Class<? extends Enum<?>> enumType;

        private ArgumentRule(Kind kind, Class<? extends Enum<?>> enumType) {
            this.kind = kind;
            this.enumType = enumType;
        }

        private static ArgumentRule enumValue(
                                    Class<? extends Enum<?>> enumType) {
            return new ArgumentRule(Kind.ENUM, enumType);
        }

        private String validate(List<Object> arguments) {
            switch (this.kind) {
                case NONE:
                    return arguments.isEmpty() ? null : "expected no arguments";
                case LONG:
                    return exact(arguments, 1, Long.class, "one integer");
                case BOOLEAN:
                    return exact(arguments, 1, Boolean.class, "one boolean");
                case STRING:
                    return exact(arguments, 1, String.class, "one string");
                case STRINGS:
                    if (arguments.isEmpty()) {
                        return "expected at least one string";
                    }
                    if (arguments.size() == 1 &&
                        arguments.get(0) instanceof List) {
                        List<?> values = (List<?>) arguments.get(0);
                        if (values.isEmpty()) {
                            return "expected at least one string";
                        }
                        return all(values, String.class,
                                   "expected only string arguments");
                    }
                    return all(arguments, String.class,
                               "expected only string arguments");
                case TWO_STRINGS:
                    if (arguments.size() != 2) {
                        return "expected two strings";
                    }
                    return all(arguments, String.class, "expected two strings");
                case USERDATA:
                    if (arguments.size() == 1 &&
                        arguments.get(0) instanceof Map) {
                        return mapKeys((Map<?, ?>) arguments.get(0),
                                       String.class,
                                       "expected only string map keys");
                    }
                    if (arguments.size() == 2 &&
                        arguments.get(0) instanceof String) {
                        return null;
                    }
                    return "expected a map or a string key and literal value";
                case ENUM:
                    return exact(arguments, 1, this.enumType,
                                 "one " + this.enumType.getSimpleName() +
                                 " constant");
                case ENUM_HUGE_TYPE_AND_STRING:
                    if (arguments.size() != 2 ||
                        !(arguments.get(0) instanceof HugeType) ||
                        !(arguments.get(1) instanceof String)) {
                        return "expected a HugeType constant and one string";
                    }
                    return null;
                default:
                    throw new AssertionError("Unknown argument rule " + this.kind);
            }
        }

        private static String exact(List<Object> arguments, int count,
                                    Class<?> type, String description) {
            if (arguments.size() != count ||
                !type.isInstance(arguments.get(0))) {
                return "expected " + description;
            }
            return null;
        }

        private static String all(List<?> arguments, Class<?> type,
                                  String description) {
            for (Object argument : arguments) {
                if (!type.isInstance(argument)) {
                    return description;
                }
            }
            return null;
        }

        private static String mapKeys(Map<?, ?> arguments, Class<?> type,
                                      String description) {
            for (Object key : arguments.keySet()) {
                if (!type.isInstance(key)) {
                    return description;
                }
            }
            return null;
        }

        private enum Kind {
            NONE,
            LONG,
            BOOLEAN,
            STRING,
            STRINGS,
            TWO_STRINGS,
            USERDATA,
            ENUM,
            ENUM_HUGE_TYPE_AND_STRING
        }
    }
}
