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

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.GenericsType;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.expr.ArrayExpression;
import org.codehaus.groovy.ast.expr.AttributeExpression;
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.ast.expr.BitwiseNegationExpression;
import org.codehaus.groovy.ast.expr.CastExpression;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.codehaus.groovy.ast.expr.GStringExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.MethodPointerExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.DoWhileStatement;
import org.codehaus.groovy.ast.stmt.ForStatement;
import org.codehaus.groovy.ast.stmt.WhileStatement;
import org.codehaus.groovy.classgen.GeneratorContext;
import org.codehaus.groovy.control.CompilePhase;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.customizers.CompilationCustomizer;
import org.codehaus.groovy.transform.stc.StaticTypesMarker;

public final class ScriptExpressionGuard extends CompilationCustomizer {

    private static final Set<String> STATIC_TYPES = Set.of(
            "java.lang.Math", "java.lang.String", "java.lang.Integer", "java.lang.Long",
            "java.lang.Double", "java.lang.Float", "java.lang.Boolean", "java.lang.Short",
            "java.lang.Byte", "java.lang.Character", "java.util.UUID",
            "org.apache.tinkerpop.gremlin.process.traversal.P",
            "org.apache.tinkerpop.gremlin.process.traversal.TextP",
            "org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__");
    private static final Set<String> ENUM_TYPES = Set.of(
            "org.apache.tinkerpop.gremlin.structure.T",
            "org.apache.tinkerpop.gremlin.structure.Direction",
            "org.apache.tinkerpop.gremlin.structure.VertexProperty$Cardinality",
            "org.apache.tinkerpop.gremlin.process.traversal.Order",
            "org.apache.tinkerpop.gremlin.process.traversal.Scope",
            "org.apache.tinkerpop.gremlin.process.traversal.Pop",
            "org.apache.tinkerpop.gremlin.structure.Column",
            "org.apache.tinkerpop.gremlin.process.traversal.Pick",
            "org.apache.hugegraph.type.define.Directions");
    private static final Set<String> LOCAL_TYPES = Set.of(
            "java.lang.Object", "java.lang.String", "java.lang.Number", "java.lang.Integer",
            "java.lang.Long", "java.lang.Double", "java.lang.Float", "java.lang.Boolean",
            "java.lang.Short", "java.lang.Byte", "java.lang.Character", "java.math.BigDecimal",
            "java.math.BigInteger", "java.util.UUID", "java.util.List", "java.util.Map",
            "java.util.Set", "java.util.Collection", "java.util.Iterator", "java.util.Optional",
            "java.util.Map$Entry", "groovy.lang.Closure", "int", "long", "double", "float",
            "boolean", "short", "byte", "char",
            "org.apache.tinkerpop.gremlin.structure.Vertex",
            "org.apache.tinkerpop.gremlin.structure.Edge",
            "org.apache.tinkerpop.gremlin.structure.Element",
            "org.apache.tinkerpop.gremlin.structure.Property",
            "org.apache.tinkerpop.gremlin.structure.VertexProperty",
            "org.apache.tinkerpop.gremlin.process.traversal.Traverser",
            "org.apache.tinkerpop.gremlin.process.traversal.Traversal",
            "org.apache.tinkerpop.gremlin.process.traversal.Path",
            "org.apache.tinkerpop.gremlin.process.traversal.P",
            "org.apache.tinkerpop.gremlin.process.traversal.TextP",
            "org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal",
            "org.apache.hugegraph.schema.SchemaManager");
    private final int preludeLines;
    private final ScriptExecutionProfile profile;

    static boolean allowsStaticImport(String type) {
        return STATIC_TYPES.contains(type) || ENUM_TYPES.contains(type);
    }

    public ScriptExpressionGuard(int preludeLines, ScriptExecutionProfile profile) {
        super(CompilePhase.INSTRUCTION_SELECTION);
        this.preludeLines = preludeLines;
        this.profile = profile;
    }

    @Override
    public void call(SourceUnit source, GeneratorContext context, ClassNode node) {
        new ClassCodeVisitorSupport() {
            private final Set<ClassExpression> receivers =
                    Collections.newSetFromMap(new IdentityHashMap<>());

            private boolean user(ASTNode expression) {
                return expression.getLineNumber() > preludeLines;
            }

            @Override
            protected SourceUnit getSourceUnit() {
                return source;
            }

            private void checkLocalType(ClassNode type) {
                if (!ClassHelper.isDynamicTyped(type) && !LOCAL_TYPES.contains(type.redirect().getName()) &&
                    !ENUM_TYPES.contains(type.redirect().getName())) {
                    throw denied("declared type");
                }
                if (type.getGenericsTypes() != null) {
                    for (GenericsType generic : type.getGenericsTypes()) {
                        if (!generic.isPlaceholder() && !generic.isWildcard()) {
                            this.checkLocalType(generic.getType());
                        }
                    }
                }
            }

            @Override
            public void visitDeclarationExpression(DeclarationExpression expression) {
                if (this.user(expression)) {
                    if (expression.isMultipleAssignmentDeclaration()) {
                        throw denied("multiple assignment");
                    }
                    this.checkLocalType(expression.getVariableExpression().getOriginType());
                }
                super.visitDeclarationExpression(expression);
            }

            @Override
            public void visitArrayExpression(ArrayExpression expression) {
                if (this.user(expression)) {
                    throw denied("array allocation; use list literals");
                }
                super.visitArrayExpression(expression);
            }

            @Override
            public void visitBinaryExpression(BinaryExpression expression) {
                if (this.user(expression)) {
                    String operator = expression.getOperation().getText();
                    if (Set.of("=~", "==~").contains(operator)) {
                        throw denied("regular expression");
                    }
                    if ("[".equals(operator)) {
                        ClassNode receiver = expression.getLeftExpression()
                                                       .getNodeMetaData(StaticTypesMarker.INFERRED_TYPE);
                        if (receiver == null) {
                            receiver = expression.getLeftExpression().getType();
                        }
                        ClassNode index = expression.getRightExpression()
                                                    .getNodeMetaData(StaticTypesMarker.INFERRED_TYPE);
                        if (index == null) {
                            index = expression.getRightExpression().getType();
                        }
                        boolean map = receiver.getName().equals("java.util.Map") ||
                                      receiver.implementsInterface(ClassHelper.make(Map.class));
                        boolean sequence = receiver.getName().equals("java.util.List") ||
                                           receiver.implementsInterface(ClassHelper.make(List.class)) ||
                                           receiver.getName().equals("java.lang.String");
                        if (!map && (!sequence || index.getName().equals("java.lang.String") ||
                                     index.getName().equals("java.lang.Object"))) {
                            throw denied("only data containers support indexing");
                        }
                    }
                    if (profile == ScriptExecutionProfile.STORE_FILTER &&
                        operator.endsWith("=") && !Set.of("==", "!=", ">=", "<=", "===", "!==")
                                                     .contains(operator) &&
                        !(expression instanceof DeclarationExpression)) {
                        throw denied("assignment in Store condition");
                    }
                }
                super.visitBinaryExpression(expression);
            }

            @Override
            public void visitBitwiseNegationExpression(BitwiseNegationExpression expression) {
                if (this.user(expression)) {
                    throw denied("bitwise negation or regular expression");
                }
                super.visitBitwiseNegationExpression(expression);
            }

            @Override
            public void visitStaticMethodCallExpression(StaticMethodCallExpression call) {
                String type = call.getOwnerType().redirect().getName();
                if (type.equals(ScriptExecutionBudget.class.getName())) {
                    if (!"check".equals(call.getMethod())) {
                        throw denied("static method call");
                    }
                } else if (!STATIC_TYPES.contains(type)) {
                    throw denied("static method call");
                }
                super.visitStaticMethodCallExpression(call);
            }

            @Override
            public void visitMethodCallExpression(MethodCallExpression call) {
                if (this.user(call)) {
                    if (call.getMethodAsString() == null || call.isSpreadSafe()) {
                        throw denied("dynamic method call");
                    }
                    if (call.getObjectExpression() instanceof ClassExpression) {
                        ClassExpression receiver = (ClassExpression) call.getObjectExpression();
                        if (!STATIC_TYPES.contains(receiver.getType().getName())) {
                            throw denied("static receiver");
                        }
                        this.receivers.add(receiver);
                    }
                }
                super.visitMethodCallExpression(call);
            }

            @Override
            public void visitPropertyExpression(PropertyExpression property) {
                if (this.user(property)) {
                    String name = property.getPropertyAsString();
                    if (name == null || Set.of("class", "metaClass", "binding", "owner",
                                               "delegate", "thisObject").contains(name)) {
                        throw denied("property access");
                    }
                    if (property.getObjectExpression() instanceof ClassExpression) {
                        ClassExpression receiver = (ClassExpression) property.getObjectExpression();
                        if (!ENUM_TYPES.contains(receiver.getType().getName()) ||
                            receiver.getType().getField(name) == null ||
                            !receiver.getType().getField(name).isEnum()) {
                            throw denied("static property");
                        }
                        this.receivers.add(receiver);
                    } else {
                        ClassNode type = property.getObjectExpression()
                                                 .getNodeMetaData(StaticTypesMarker.INFERRED_TYPE);
                        if (type == null) {
                            type = property.getObjectExpression().getType();
                        }
                        if (!type.getName().equals("java.util.Map") &&
                            !type.implementsInterface(ClassHelper.make(Map.class))) {
                            throw denied("use an allowed accessor method");
                        }
                    }
                }
                super.visitPropertyExpression(property);
            }

            @Override
            public void visitClassExpression(ClassExpression expression) {
                if (this.user(expression) && !this.receivers.contains(expression)) {
                    throw denied("class value");
                }
                super.visitClassExpression(expression);
            }

            @Override
            public void visitVariableExpression(VariableExpression variable) {
                if (this.user(variable) && (variable.isThisExpression() || variable.isSuperExpression() ||
                                           variable.getName().startsWith("__hg"))) {
                    throw denied("script internals");
                }
                super.visitVariableExpression(variable);
            }

            @Override
            public void visitAttributeExpression(AttributeExpression expression) {
                if (this.user(expression)) {
                    throw denied("direct field access");
                }
                super.visitAttributeExpression(expression);
            }

            @Override
            public void visitGStringExpression(GStringExpression expression) {
                if (this.user(expression)) {
                    throw denied("GString interpolation");
                }
                super.visitGStringExpression(expression);
            }

            @Override
            public void visitMethodPointerExpression(MethodPointerExpression expression) {
                if (this.user(expression)) {
                    throw denied("method pointer");
                }
                super.visitMethodPointerExpression(expression);
            }

            @Override
            public void visitConstructorCallExpression(ConstructorCallExpression expression) {
                if (this.user(expression)) {
                    throw denied("constructor call; use literals");
                }
                super.visitConstructorCallExpression(expression);
            }

            @Override
            public void visitCastExpression(CastExpression expression) {
                if (this.user(expression) && expression.isCoerce()) {
                    throw denied("user coercion");
                }
                if (this.user(expression) && !Set.of("java.lang.String", "java.lang.Integer",
                        "java.lang.Long", "java.lang.Double", "java.lang.Float", "java.lang.Boolean",
                        "int", "long", "double", "float", "boolean", "java.util.List", "java.util.Map",
                        "org.apache.tinkerpop.gremlin.structure.Vertex",
                        "org.apache.tinkerpop.gremlin.structure.Edge",
                        "org.apache.tinkerpop.gremlin.process.traversal.Traverser")
                        .contains(expression.getType().getName())) {
                    throw denied("cast type");
                }
                super.visitCastExpression(expression);
            }

            @Override
            public void visitClosureExpression(ClosureExpression expression) {
                if (profile == ScriptExecutionProfile.STORE_FILTER && this.user(expression)) {
                    throw denied("closure in Store condition");
                }
                if (this.user(expression) && expression.getParameters() != null) {
                    for (Parameter parameter : expression.getParameters()) {
                        this.checkLocalType(parameter.getOriginType());
                    }
                }
                super.visitClosureExpression(expression);
            }

            @Override
            public void visitForLoop(ForStatement statement) {
                this.checkLoop();
                if (this.user(statement) &&
                    statement.getVariable() != ForStatement.FOR_LOOP_DUMMY) {
                    this.checkLocalType(statement.getVariable().getOriginType());
                }
                super.visitForLoop(statement);
            }

            @Override
            public void visitWhileLoop(WhileStatement statement) {
                this.checkLoop();
                super.visitWhileLoop(statement);
            }

            @Override
            public void visitDoWhileLoop(DoWhileStatement statement) {
                this.checkLoop();
                super.visitDoWhileLoop(statement);
            }

            private void checkLoop() {
                if (profile == ScriptExecutionProfile.STORE_FILTER) {
                    throw denied("loop in Store condition");
                }
            }
        }.visitClass(node);
    }

    private static SecurityException denied(String reason) {
        return new SecurityException("SCRIPT_EXPRESSION_DENIED: " + reason);
    }
}
