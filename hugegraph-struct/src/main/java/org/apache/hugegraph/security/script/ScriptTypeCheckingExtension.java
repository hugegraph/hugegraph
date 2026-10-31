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

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.codehaus.groovy.ast.ClassCodeExpressionTransformer;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.expr.ArgumentListExpression;
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.ast.expr.CastExpression;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MethodCall;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression;
import org.codehaus.groovy.ast.expr.TupleExpression;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.transform.sc.StaticCompilationMetadataKeys;
import org.codehaus.groovy.transform.sc.transformers.BinaryExpressionTransformer;
import org.codehaus.groovy.transform.sc.transformers.StaticCompilationTransformer;
import org.codehaus.groovy.transform.stc.ExtensionMethodNode;
import org.codehaus.groovy.transform.stc.StaticTypeCheckingVisitor;
import org.codehaus.groovy.transform.stc.StaticTypesMarker;
import org.codehaus.groovy.transform.stc.TypeCheckingExtension;

public final class ScriptTypeCheckingExtension extends TypeCheckingExtension {

    static final ThreadLocal<CompilationPolicy> POLICY = new ThreadLocal<>();

    public ScriptTypeCheckingExtension(StaticTypeCheckingVisitor visitor) {
        super(visitor);
        if (POLICY.get() == null) {
            throw new IllegalStateException("Missing compilation policy");
        }
    }

    static final Object DATA_CALL = new Object();

    @Override
    public void afterVisitMethod(MethodNode node) {
        if (node.getCode() == null) {
            return;
        }
        // Resolve the original overloads first, then preserve their inferred result types.
        node.getCode().visit(new ClassCodeExpressionTransformer() {
            @Override
            protected SourceUnit getSourceUnit() {
                return node.getDeclaringClass().getModule().getContext();
            }

            @Override
            public Expression transform(Expression expression) {
                if (expression instanceof ClosureExpression) {
                    ((ClosureExpression) expression).getCode().visit(this);
                    return expression;
                }
                expression = super.transform(expression);
                if (expression == null || expression.getLineNumber() <= POLICY.get().preludeLines ||
                    Boolean.TRUE.equals(expression.getNodeMetaData(DATA_CALL))) {
                    return expression;
                }
                ClassNode result = ScriptTypeCheckingExtension.this.getType(expression);
                MethodNode target = expression.getNodeMetaData(StaticTypesMarker.DIRECT_METHOD_CALL_TARGET);
                Object[] binaryTarget = expression.getNodeMetaData(StaticCompilationMetadataKeys.BINARY_EXP_TARGET);
                if (binaryTarget != null && binaryTarget[0] instanceof MethodNode) {
                    target = (MethodNode) binaryTarget[0];
                }
                ClassNode argument = target == null || target.getParameters().length == 0 ? ClassHelper.OBJECT_TYPE :
                                     target.getParameters()[target.getParameters().length - 1].getOriginType();
                if (target instanceof ExtensionMethodNode) {
                    MethodNode nativeMethod = ((ExtensionMethodNode) target).getExtensionMethodNode();
                    argument = nativeMethod.getParameters()[nativeMethod.getParameters().length - 1].getOriginType();
                }
                argument = erased(argument);
                Expression bridge = null;
                BinaryExpression assignment = null;
                if (expression instanceof BinaryExpression) {
                    BinaryExpression binary = (BinaryExpression) expression;
                    String name = binary.getOperation().getText();
                    ClassNode left = ScriptTypeCheckingExtension.this.getType(binary.getLeftExpression());
                    ClassNode right = ScriptTypeCheckingExtension.this.getType(binary.getRightExpression());
                    boolean addition = Set.of("+", "+=").contains(name);
                    if ((addition && (concatenation(left) || concatenation(right))) ||
                        (Set.of("**", "**=").contains(name) &&
                         Set.of("int", "long", "java.lang.Integer", "java.lang.Long").contains(left.getName()) &&
                         Set.of("int", "java.lang.Integer").contains(right.getName()))) {
                        Expression receiver = binary.getLeftExpression();
                        Expression operand = binary.getRightExpression();
                        if (name.endsWith("=")) {
                            // Groovy owns lvalue memoization and the temporary-slot cleanup callback.
                            StaticCompilationTransformer nativeTransform = new StaticCompilationTransformer(
                                    this.getSourceUnit(), typeCheckingVisitor) {
                                @Override
                                public Expression transform(Expression value) {
                                    return value;
                                }

                                @Override
                                public ClassNode getClassNode() {
                                    return node.getDeclaringClass();
                                }
                            };
                            Expression lowered = new BinaryExpressionTransformer(nativeTransform)
                                    .transformBinaryExpression(binary);
                            if (!(lowered instanceof BinaryExpression) || lowered == binary ||
                                !(((BinaryExpression) lowered).getRightExpression() instanceof MethodCallExpression)) {
                                throw new SecurityException("SCRIPT_EXPRESSION_DENIED: compound allocation");
                            }
                            assignment = (BinaryExpression) lowered;
                            MethodCallExpression call = (MethodCallExpression) assignment.getRightExpression();
                            receiver = call.getObjectExpression();
                            operand = ((TupleExpression) call.getArguments()).getExpression(0);
                        }
                        bridge = new StaticMethodCallExpression(ClassHelper.make(ScriptDataOperations.class),
                                addition ? "plus" : "integerPower",
                                addition ? new ArgumentListExpression(List.of(
                                        receiver, operand,
                                        new ClassExpression(result), new ClassExpression(argument))) :
                                new ArgumentListExpression(receiver, operand));
                    }
                } else if (expression instanceof MethodCallExpression) {
                    MethodCallExpression call = (MethodCallExpression) expression;
                    ClassNode receiverType = ScriptTypeCheckingExtension.this.getType(call.getObjectExpression());
                    if ("plus".equals(call.getMethodAsString()) && (concatenation(receiverType) ||
                        (call.isSpreadSafe() && (receiverType.getName().equals("java.util.Iterator") ||
                         receiverType.implementsInterface(ClassHelper.make(java.util.Iterator.class)))))) {
                        StaticMethodCallExpression receiver = new StaticMethodCallExpression(
                                ClassHelper.make(ScriptDataOperations.class),
                                call.isSpreadSafe() ? "boundedValues" : call.isSafe() ? "bounded" : "boundedPlus",
                                new ArgumentListExpression(call.getObjectExpression()));
                        receiver.putNodeMetaData(DATA_CALL, Boolean.TRUE);
                        call.setObjectExpression(receiver);
                        if (call.getArguments() instanceof TupleExpression &&
                            ((TupleExpression) call.getArguments()).getExpressions().size() == 1) {
                            ClassNode item = result;
                            if (call.isSpreadSafe() && result.getGenericsTypes() != null &&
                                result.getGenericsTypes().length > 0) {
                                item = result.getGenericsTypes()[0].getType();
                            }
                            List<Expression> arguments = ((TupleExpression) call.getArguments()).getExpressions();
                            call.setArguments(new ArgumentListExpression(List.of(arguments.get(0),
                                    new ClassExpression(item), new ClassExpression(argument))));
                        }
                        bridge = call;
                    }
                }
                if (bridge == null) {
                    return expression;
                }
                bridge.setSourcePosition(expression);
                bridge.putNodeMetaData(DATA_CALL, Boolean.TRUE);
                bridge.visit(typeCheckingVisitor);
                // This cast belongs to the compiler; user casts keep their source positions.
                Expression checked = new CastExpression(result, bridge);
                if (assignment != null) {
                    assignment.setRightExpression(checked);
                    assignment.putNodeMetaData(StaticTypesMarker.INFERRED_TYPE, result);
                    return assignment;
                }
                return checked;
            }
        });
    }

    private static boolean concatenation(ClassNode type) {
        return type.isArray() || Set.of("java.lang.String", "java.lang.CharSequence", "java.lang.Iterable",
                                       "java.util.Map").contains(type.getName()) ||
               type.implementsInterface(ClassHelper.make(CharSequence.class)) ||
               type.implementsInterface(ClassHelper.make(Iterable.class)) ||
               type.implementsInterface(ClassHelper.make(java.util.Map.class));
    }

    private static ClassNode erased(ClassNode type) {
        return type.isArray() ? erased(type.getComponentType()).makeArray() :
               type.isGenericsPlaceHolder() ? ClassHelper.OBJECT_TYPE : type.redirect();
    }

    @Override
    public boolean beforeMethodCall(MethodCall call) {
        if (call instanceof StaticMethodCallExpression) {
            StaticMethodCallExpression method = (StaticMethodCallExpression) call;
            if ("format".equals(method.getMethod()) && method.getOwnerType().getName().equals("java.lang.String")) {
                method.setOwnerType(ClassHelper.make(ScriptDataOperations.class));
                method.putNodeMetaData(DATA_CALL, Boolean.TRUE);
            }
            if ("valueOf".equals(method.getMethod()) &&
                method.getOwnerType().getName().equals("java.math.BigDecimal") &&
                ((TupleExpression) method.getArguments()).getExpressions().size() == 2) {
                method.setOwnerType(ClassHelper.make(ScriptDataOperations.class));
                method.putNodeMetaData(DATA_CALL, Boolean.TRUE);
            }
            if ("toJson".equals(method.getMethod()) &&
                method.getOwnerType().getName().equals("groovy.json.JsonOutput")) {
                method.setOwnerType(ClassHelper.make(ScriptDataOperations.class));
                method.putNodeMetaData(DATA_CALL, Boolean.TRUE);
            }
        }
        if (call instanceof MethodCallExpression) {
            MethodCallExpression method = (MethodCallExpression) call;
            String name = method.getMethodAsString();
            if ("format".equals(name) && method.getObjectExpression() instanceof ClassExpression &&
                ((ClassExpression) method.getObjectExpression()).getType().getName().equals("java.lang.String")) {
                method.setObjectExpression(new ClassExpression(ClassHelper.make(ScriptDataOperations.class)));
                method.putNodeMetaData(DATA_CALL, Boolean.TRUE);
            }
            if ("valueOf".equals(name) && method.getObjectExpression() instanceof ClassExpression &&
                ((ClassExpression) method.getObjectExpression()).getType().getName().equals("java.math.BigDecimal") &&
                ((TupleExpression) method.getArguments()).getExpressions().size() == 2) {
                method.setObjectExpression(new ClassExpression(ClassHelper.make(ScriptDataOperations.class)));
                method.putNodeMetaData(DATA_CALL, Boolean.TRUE);
            }
            if (Set.of("repeat", "pow", "setScale", "padLeft", "padRight", "center", "multiply", "divide")
                    .contains(name == null ? "" : name)) {
                Expression original = method.getObjectExpression();
                original.visit(this.typeCheckingVisitor);
                ClassNode receiver = this.getType(original);
                ClassNode element = receiver;
                if (method.isSpreadSafe()) {
                    if (receiver.isArray()) {
                        element = receiver.getComponentType();
                    } else if (receiver.getGenericsTypes() != null && receiver.getGenericsTypes().length > 0) {
                        element = receiver.getGenericsTypes()[receiver.getGenericsTypes().length - 1].getType();
                    }
                }
                boolean integer = element.getName().equals("java.math.BigInteger");
                boolean decimal = element.getName().equals("java.math.BigDecimal");
                boolean text = element.getName().equals("java.lang.String") ||
                               element.implementsInterface(ClassHelper.make(CharSequence.class));
                if (("repeat".equals(name) && text) ||
                    (Set.of("pow", "multiply").contains(name) && (integer || decimal)) ||
                    (Set.of("setScale", "divide").contains(name) && decimal) ||
                    (Set.of("padLeft", "padRight", "center").contains(name) && text)) {
                    StaticMethodCallExpression bounded = new StaticMethodCallExpression(
                            ClassHelper.make(ScriptDataOperations.class),
                            method.isSpreadSafe() ? "boundedValues" : "bounded", new ArgumentListExpression(original));
                    bounded.setSourcePosition(original);
                    bounded.putNodeMetaData(DATA_CALL, Boolean.TRUE);
                    method.setObjectExpression(bounded);
                }
            }
            if ("toString".equals(name) && !method.isSpreadSafe() &&
                this.getType(method.getObjectExpression()).equals(ClassHelper.OBJECT_TYPE) &&
                method.getArguments() instanceof TupleExpression &&
                ((TupleExpression) method.getArguments()).getExpressions().isEmpty()) {
                Expression receiver = method.getObjectExpression();
                method.setObjectExpression(new ClassExpression(ClassHelper.make(ScriptDataOperations.class)));
                method.setArguments(new ArgumentListExpression(receiver,
                        new org.codehaus.groovy.ast.expr.ConstantExpression(method.isSafe())));
                method.setSafe(false);
                method.setImplicitThis(false);
                method.putNodeMetaData(DATA_CALL, Boolean.TRUE);
            }
            if ("toJson".equals(name) && method.getObjectExpression() instanceof ClassExpression &&
                ((ClassExpression) method.getObjectExpression()).getType().getName().equals("groovy.json.JsonOutput")) {
                method.setObjectExpression(new ClassExpression(ClassHelper.make(ScriptDataOperations.class)));
                method.putNodeMetaData(DATA_CALL, Boolean.TRUE);
            }
            // A chained receiver such as Traverser<String>.get() may not have
            // its inferred type yet when Groovy invokes beforeMethodCall.
            if (Set.of("matches", "replaceAll", "replaceFirst", "split").contains(name == null ? "" : name) &&
                method.getObjectExpression() instanceof MethodCallExpression &&
                this.getType(method.getObjectExpression()).equals(ClassHelper.OBJECT_TYPE)) {
                this.typeCheckingVisitor.visitMethodCallExpression(
                        (MethodCallExpression) method.getObjectExpression());
            }
            if (Set.of("matches", "replaceAll", "replaceFirst", "split").contains(name == null ? "" : name) &&
                (method.isSpreadSafe() || this.getType(method.getObjectExpression()).equals(ClassHelper.STRING_TYPE))) {
                String bridge = "regexText";
                if (method.isSpreadSafe()) {
                    bridge = this.getType(method.getObjectExpression()).isArray() ? "regexArrayTexts" : "regexTexts";
                }
                StaticMethodCallExpression receiver = new StaticMethodCallExpression(
                        ClassHelper.make(ScriptDataOperations.class), bridge,
                        new ArgumentListExpression(method.getObjectExpression()));
                receiver.setSourcePosition(method.getObjectExpression());
                receiver.putNodeMetaData(DATA_CALL, Boolean.TRUE);
                method.setObjectExpression(receiver);
                this.typeCheckingVisitor.visitStaticMethodCallExpression(receiver);
            }
            if ("collect".equals(method.getMethodAsString()) && !method.isSpreadSafe() && !method.isSafe() &&
                method.getArguments() instanceof TupleExpression &&
                ((TupleExpression) method.getArguments()).getExpressions().size() == 1) {
                Expression argument = ((TupleExpression) method.getArguments()).getExpression(0);
                ClassNode receiver = this.getType(method.getObjectExpression());
                if (argument instanceof ClosureExpression && ((ClosureExpression) argument).getParameters() != null &&
                    Arrays.stream(((ClosureExpression) argument).getParameters())
                          .anyMatch(parameter -> !ClassHelper.isDynamicTyped(parameter.getOriginType())) &&
                    (receiver.getName().equals("java.util.List") ||
                     receiver.implementsInterface(ClassHelper.make(List.class)))) {
                    Expression original = method.getObjectExpression();
                    method.setObjectExpression(new ClassExpression(ClassHelper.make(ScriptDataOperations.class)));
                    method.setImplicitThis(false);
                    method.setArguments(new ArgumentListExpression(original, argument));
                    method.putNodeMetaData(DATA_CALL, Boolean.TRUE);
                }
            }
        }
        return false;
    }

    @Override
    public void afterMethodCall(MethodCall call) {
        if (!(call instanceof Expression)) {
            return;
        }
        Expression expression = (Expression) call;
        Object marked = expression.getNodeMetaData(ScriptLocalMethodsCustomizer.RETURN_TYPE);
        if (marked instanceof ClassNode) {
            // convertReturn is declared as Object. Keep the helper's declared type so the
            // closure return, and calls such as helper().isEmpty(), stay statically typed.
            expression.putNodeMetaData(StaticTypesMarker.INFERRED_TYPE, marked);
        }
    }

    @Override
    public List<MethodNode> handleMissingMethod(ClassNode receiver, String name,
                                               ArgumentListExpression arguments,
                                               ClassNode[] argumentTypes, MethodCall call) {
        if ((receiver.equals(ClassHelper.OBJECT_TYPE) || ClassHelper.isPrimitiveType(receiver) ||
             Set.of("java.lang.Number", "java.lang.Integer", "java.lang.Long", "java.lang.Double",
                    "java.lang.Float", "java.lang.Short", "java.lang.Byte", "java.lang.String",
                    "java.math.BigDecimal", "java.math.BigInteger").contains(receiver.getName())) &&
            argumentTypes.length == 1 &&
            Set.of("plus", "minus", "multiply", "div", "mod", "power", "compareTo").contains(name)) {
            MethodNode helper = ClassHelper.make(ScriptDataOperations.class).getDeclaredMethods(name).stream()
                    .filter(method -> method.getParameters().length == 2 &&
                            method.getParameters()[0].getType().equals(ClassHelper.OBJECT_TYPE) &&
                            method.getParameters()[1].getType().equals(ClassHelper.OBJECT_TYPE))
                    .findFirst().orElseThrow();
            ExtensionMethodNode extension = new ExtensionMethodNode(helper, name,
                    helper.getModifiers() & ~Modifier.STATIC, helper.getReturnType(),
                    Arrays.copyOfRange(helper.getParameters(), 1, helper.getParameters().length),
                    helper.getExceptions(), helper.getCode(), false);
            extension.setDeclaringClass(helper.getParameters()[0].getType());
            if (POLICY.get().methods.allows(extension)) {
                return List.of(extension);
            }
        }
        return List.of();
    }

    @Override
    public boolean handleUnresolvedProperty(PropertyExpression property) {
        String name = property.getPropertyAsString();
        if (name == null || Set.of("class", "metaClass", "binding", "owner", "delegate", "thisObject")
                               .contains(name) ||
            this.typeCheckingVisitor.getTypeCheckingContext().isTargetOfEnclosingAssignment(property)) {
            return false;
        }
        ClassNode type = this.getType(property.getObjectExpression());
        String operation;
        if (type.equals(ClassHelper.OBJECT_TYPE)) {
            operation = "propertyMap";
        } else if ((type.getName().equals("org.apache.tinkerpop.gremlin.structure.Element") ||
                    type.implementsInterface(ClassHelper.make(org.apache.tinkerpop.gremlin.structure.Element.class))) &&
                   Set.of("id", "label").contains(name)) {
            operation = "elementProperties";
        } else {
            return false;
        }
        StaticMethodCallExpression receiver = new StaticMethodCallExpression(
                ClassHelper.make(ScriptDataOperations.class), operation,
                new ArgumentListExpression(property.getObjectExpression()));
        receiver.setSourcePosition(property.getObjectExpression());
        receiver.putNodeMetaData(DATA_CALL, Boolean.TRUE);
        property.setObjectExpression(receiver);
        this.typeCheckingVisitor.visitStaticMethodCallExpression(receiver);
        this.typeCheckingVisitor.visitPropertyExpression(property);
        return true;
    }

    @Override
    public void onMethodSelection(Expression expression, MethodNode target) {
        CompilationPolicy policy = POLICY.get();
        int line = expression.getLineNumber();
        // Only the engine prelude is trusted. Unknown/generated line numbers
        // still have to match the method allow-list.
        if (line > 0 && line <= policy.preludeLines) {
            return;
        }
        if (!policy.methods.allows(target)) {
            this.addStaticTypeError("SCRIPT_METHOD_DENIED: " +
                                    target.getDeclaringClass().getName() + "#" + target.getName(),
                                    expression);
        }
    }

    static final class CompilationPolicy {

        final int preludeLines;
        final ScriptMethodPolicy methods;

        CompilationPolicy(int preludeLines, ScriptMethodPolicy methods) {
            this.preludeLines = preludeLines;
            this.methods = methods;
        }
    }
}
