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

import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.expr.ArgumentListExpression;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MethodCall;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression;
import org.codehaus.groovy.ast.expr.TupleExpression;
import org.codehaus.groovy.transform.stc.ExtensionMethodNode;
import org.codehaus.groovy.transform.stc.StaticTypeCheckingVisitor;
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
    public boolean beforeMethodCall(MethodCall call) {
        if (call instanceof StaticMethodCallExpression) {
            StaticMethodCallExpression method = (StaticMethodCallExpression) call;
            if ("toJson".equals(method.getMethod()) &&
                method.getOwnerType().getName().equals("groovy.json.JsonOutput")) {
                method.setOwnerType(ClassHelper.make(ScriptDataOperations.class));
                method.putNodeMetaData(DATA_CALL, Boolean.TRUE);
            }
        }
        if (call instanceof MethodCallExpression) {
            MethodCallExpression method = (MethodCallExpression) call;
            String name = method.getMethodAsString();
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
                StaticMethodCallExpression receiver = new StaticMethodCallExpression(
                        ClassHelper.make(ScriptDataOperations.class),
                        method.isSpreadSafe() ? "regexTexts" : "regexText",
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
