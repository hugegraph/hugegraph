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

import java.util.Set;

import org.codehaus.groovy.ast.ClassCodeExpressionTransformer;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.expr.ArgumentListExpression;
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.ArrayExpression;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.RangeExpression;
import org.codehaus.groovy.ast.expr.TupleExpression;
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression;
import org.codehaus.groovy.classgen.GeneratorContext;
import org.codehaus.groovy.control.CompilePhase;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.customizers.CompilationCustomizer;
import org.codehaus.groovy.syntax.Types;

/** Replaces collection property projection with data-only access before static compilation. */
final class ScriptDataSyntaxCustomizer extends CompilationCustomizer {

    private static final Object CHECKED_NUMBER = new Object();
    ScriptDataSyntaxCustomizer() {
        super(CompilePhase.SEMANTIC_ANALYSIS);
    }

    @Override
    public void call(SourceUnit source, GeneratorContext context, ClassNode node) {
        new ClassCodeExpressionTransformer() {
            @Override
            protected SourceUnit getSourceUnit() {
                return source;
            }

            private Expression dataCall(Expression original, String method, Expression receiver, Expression key) {
                StaticMethodCallExpression call = new StaticMethodCallExpression(
                        ClassHelper.make(ScriptDataOperations.class), method,
                        new ArgumentListExpression(this.transform(receiver), this.transform(key)));
                call.setSourcePosition(original);
                call.putNodeMetaData(ScriptTypeCheckingExtension.DATA_CALL, Boolean.TRUE);
                return call;
            }

            @Override
            public Expression transform(Expression expression) {
                if (expression instanceof RangeExpression) {
                    RangeExpression range = (RangeExpression) expression;
                    if (this.emptyRange(range)) {
                        return this.safeCall(expression, "emptyRange",
                                new ArgumentListExpression(this.transform(range.getFrom())));
                    }
                    return this.safeCall(expression, "range", new ArgumentListExpression(java.util.List.of(
                            this.transform(range.getFrom()), this.transform(range.getTo()),
                            new ConstantExpression(range.isExclusiveLeft()),
                            new ConstantExpression(range.isExclusiveRight()))));
                }
                if (expression instanceof ArrayExpression) {
                    ArrayExpression array = (ArrayExpression) expression;
                    if (array.getSizeExpression() != null && !array.getSizeExpression().isEmpty()) {
                        if (array.getSizeExpression().size() != 1) {
                            throw new SecurityException("SCRIPT_EXPRESSION_DENIED: multidimensional allocation");
                        }
                        array.getSizeExpression().set(0, this.safeCall(expression, "capacity",
                                new ArgumentListExpression(this.transform(array.getSizeExpression().get(0)))));
                    }
                }
                if (expression instanceof ConstructorCallExpression) {
                    ConstructorCallExpression constructor = (ConstructorCallExpression) expression;
                    String type = constructor.getType().getName();
                    if (type.equals("groovy.lang.IntRange")) {
                        return this.safeCall(expression, "intRange", this.transform(constructor.getArguments()));
                    }
                    if (Set.of("java.util.ArrayList", "java.util.LinkedList", "java.util.HashMap",
                               "java.util.LinkedHashMap", "java.util.HashSet", "java.util.LinkedHashSet")
                            .contains(type) &&
                        constructor.getArguments() instanceof TupleExpression) {
                        java.util.List<Expression> arguments =
                                ((TupleExpression) constructor.getArguments()).getExpressions();
                        if (!arguments.isEmpty()) {
                            arguments.set(0, this.safeCall(expression, "constructorArgument",
                                    new ArgumentListExpression(this.transform(arguments.get(0)))));
                        }
                    }
                    if (Set.of("java.math.BigDecimal", "java.math.BigInteger").contains(type)) {
                        return this.safeCall(expression, "checkedNumber",
                                new ArgumentListExpression(super.transform(expression)));
                    }
                }
                if (expression instanceof ConstantExpression && expression.getNodeMetaData(CHECKED_NUMBER) == null &&
                    (((ConstantExpression) expression).getValue() instanceof java.math.BigDecimal ||
                     ((ConstantExpression) expression).getValue() instanceof java.math.BigInteger)) {
                    expression.putNodeMetaData(CHECKED_NUMBER, Boolean.TRUE);
                    return this.safeCall(expression, "checkedNumber", new ArgumentListExpression(expression));
                }
                if (expression instanceof StaticMethodCallExpression) {
                    StaticMethodCallExpression call = (StaticMethodCallExpression) expression;
                    if (call.getOwnerType().getName().equals("groovy.json.JsonOutput") &&
                        call.getMethod().equals("toJson")) {
                        call.setOwnerType(ClassHelper.make(ScriptDataOperations.class));
                        call.putNodeMetaData(ScriptTypeCheckingExtension.DATA_CALL, Boolean.TRUE);
                    }
                }
                if (expression instanceof MethodCallExpression) {
                    MethodCallExpression call = (MethodCallExpression) expression;
                    if ("userdata".equals(call.getMethodAsString()) &&
                        call.getArguments() instanceof org.codehaus.groovy.ast.expr.TupleExpression) {
                        java.util.List<Expression> args = ((org.codehaus.groovy.ast.expr.TupleExpression)
                                                          call.getArguments()).getExpressions();
                        if (args.size() == 1 || args.size() == 2) {
                            int index = args.size() - 1;
                            StaticMethodCallExpression data = new StaticMethodCallExpression(
                                    ClassHelper.make(ScriptDataOperations.class),
                                    args.size() == 1 ? "checkedMap" : "checkedData",
                                    new ArgumentListExpression(this.transform(args.get(index))));
                            data.setSourcePosition(args.get(index));
                            data.putNodeMetaData(ScriptTypeCheckingExtension.DATA_CALL, Boolean.TRUE);
                            args.set(index, data);
                        }
                    }
                    org.codehaus.groovy.ast.ImportNode imported = source.getAST().getStaticImports()
                            .get(call.getMethodAsString());
                    if (call.isImplicitThis() && imported != null &&
                        imported.getType().getName().equals("groovy.json.JsonOutput") &&
                        "toJson".equals(imported.getFieldName())) {
                        StaticMethodCallExpression safe = new StaticMethodCallExpression(
                                ClassHelper.make(ScriptDataOperations.class), "toJson",
                                this.transform(call.getArguments()));
                        safe.setSourcePosition(call);
                        safe.putNodeMetaData(ScriptTypeCheckingExtension.DATA_CALL, Boolean.TRUE);
                        return safe;
                    }
                }
                if (expression instanceof ClosureExpression) {
                    ((ClosureExpression) expression).getCode().visit(this);
                    return expression;
                }
                if (expression instanceof BinaryExpression) {
                    BinaryExpression binary = (BinaryExpression) expression;
                    if (binary.getOperation().getType() == Types.LEFT_SQUARE_BRACKET &&
                        binary.getRightExpression() instanceof RangeExpression &&
                        this.emptyRange((RangeExpression) binary.getRightExpression())) {
                        // Prefer Groovy's CharSequence/EmptyRange overload over String/Range.
                        binary.setLeftExpression(this.safeCall(binary, "emptyRangeReceiver",
                                new ArgumentListExpression(this.transform(binary.getLeftExpression()))));
                        binary.setRightExpression(this.transform(binary.getRightExpression()));
                        return binary;
                    }
                    if (Set.of("==", "!=").contains(binary.getOperation().getText())) {
                        Expression equal = this.dataCall(binary, "equal", binary.getLeftExpression(),
                                                         binary.getRightExpression());
                        if ("==".equals(binary.getOperation().getText())) {
                            return equal;
                        }
                        org.codehaus.groovy.ast.expr.NotExpression result =
                                new org.codehaus.groovy.ast.expr.NotExpression(equal);
                        result.setSourcePosition(binary);
                        return result;
                    }
                    if (Set.of("<", ">", "<=", ">=", "<=>").contains(binary.getOperation().getText())) {
                        Expression compared = this.dataCall(binary, "compareTo", binary.getLeftExpression(),
                                                             binary.getRightExpression());
                        if ("<=>".equals(binary.getOperation().getText())) {
                            return compared;
                        }
                        BinaryExpression result = new BinaryExpression(compared, binary.getOperation(),
                                                                       new ConstantExpression(0));
                        result.setSourcePosition(binary);
                        return result;
                    }
                    if ("==~".equals(binary.getOperation().getText())) {
                        return this.dataCall(binary, "regexMatches", binary.getLeftExpression(),
                                             binary.getRightExpression());
                    }
                    if ("=~".equals(binary.getOperation().getText())) {
                        return this.dataCall(binary, "regexFind", binary.getLeftExpression(),
                                             binary.getRightExpression());
                    }
                    String operation = java.util.Map.of("+=", "plus", "-=", "minus", "*=", "multiply",
                            "/=", "div", "%=", "mod", "**=", "power").get(binary.getOperation().getText());
                    if (operation != null && binary.getLeftExpression() instanceof
                                             org.codehaus.groovy.ast.expr.VariableExpression) {
                        Expression value = this.dataCall(binary, operation, binary.getLeftExpression(),
                                                         binary.getRightExpression());
                        ClassNode targetType = ((org.codehaus.groovy.ast.expr.VariableExpression)
                                                binary.getLeftExpression()).getOriginType();
                        if (!ClassHelper.isDynamicTyped(targetType)) {
                            org.codehaus.groovy.ast.expr.CastExpression cast =
                                    new org.codehaus.groovy.ast.expr.CastExpression(targetType, value);
                            cast.setSourcePosition(binary);
                            value = cast;
                        }
                        BinaryExpression assigned = new BinaryExpression(binary.getLeftExpression(),
                                org.codehaus.groovy.syntax.Token.newSymbol(Types.ASSIGN, binary.getLineNumber(),
                                                                           binary.getColumnNumber()),
                                value);
                        assigned.setSourcePosition(binary);
                        return assigned;
                    }
                    if (Types.isAssignment(binary.getOperation().getType())) {
                        // Keep [ ] / property assignment targets as lvalues. Nested rvalues
                        // in keys or receivers still go through transform().
                        binary.setLeftExpression(this.transformLValue(binary.getLeftExpression()));
                        binary.setRightExpression(this.transform(binary.getRightExpression()));
                        return binary;
                    }
                    if (binary.getOperation().getType() == Types.LEFT_SQUARE_BRACKET &&
                        binary.getRightExpression() instanceof ConstantExpression &&
                        ((ConstantExpression) binary.getRightExpression()).getValue() instanceof String) {
                        return this.dataCall(binary, binary.isSafe() ? "getAtSafe" : "getAt",
                                             binary.getLeftExpression(), binary.getRightExpression());
                    }
                }
                if (expression instanceof PropertyExpression && ((PropertyExpression) expression).isSpreadSafe()) {
                    PropertyExpression property = (PropertyExpression) expression;
                    if (property.getPropertyAsString() != null) {
                        return this.dataCall(property, "project", property.getObjectExpression(),
                                             property.getProperty());
                    }
                }
                return super.transform(expression);
            }

            private Expression safeCall(Expression original, String method, Expression arguments) {
                StaticMethodCallExpression call = new StaticMethodCallExpression(
                        ClassHelper.make(ScriptDataOperations.class), method, arguments);
                call.setSourcePosition(original);
                call.putNodeMetaData(ScriptTypeCheckingExtension.DATA_CALL, Boolean.TRUE);
                return call;
            }

            private boolean emptyRange(RangeExpression range) {
                return (range.isExclusiveLeft() || range.isExclusiveRight()) &&
                       range.getFrom() instanceof ConstantExpression && range.getTo() instanceof ConstantExpression &&
                       ((ConstantExpression) range.getFrom()).getValue() instanceof Comparable &&
                       ((ConstantExpression) range.getFrom()).getValue()
                               .equals(((ConstantExpression) range.getTo()).getValue());
            }

            private Expression transformLValue(Expression expression) {
                if (expression instanceof BinaryExpression) {
                    BinaryExpression index = (BinaryExpression) expression;
                    if (index.getOperation().getType() == Types.LEFT_SQUARE_BRACKET) {
                        index.setLeftExpression(this.transformLValue(index.getLeftExpression()));
                        index.setRightExpression(this.transform(index.getRightExpression()));
                        return index;
                    }
                }
                if (expression instanceof PropertyExpression) {
                    PropertyExpression property = (PropertyExpression) expression;
                    property.setObjectExpression(this.transformLValue(property.getObjectExpression()));
                    return property;
                }
                // Nested rvalues (method calls, lists, maps) are not themselves assignable
                // targets; rewrite them so userdata/data checks still apply.
                return this.transform(expression);
            }
        }.visitClass(node);
    }
}
