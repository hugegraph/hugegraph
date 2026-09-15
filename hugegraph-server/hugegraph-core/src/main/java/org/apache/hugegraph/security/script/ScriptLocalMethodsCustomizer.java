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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.CodeVisitorSupport;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.expr.ArgumentListExpression;
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.ast.expr.CastExpression;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.codehaus.groovy.ast.expr.EmptyExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.codehaus.groovy.ast.stmt.ReturnStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.classgen.GeneratorContext;
import org.codehaus.groovy.classgen.ReturnAdder;
import org.codehaus.groovy.control.CompilePhase;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.customizers.CompilationCustomizer;
import org.codehaus.groovy.syntax.Token;
import org.codehaus.groovy.syntax.Types;

/** Keeps helper functions local to the checked request, with the same closure budget. */
final class ScriptLocalMethodsCustomizer extends CompilationCustomizer {

    ScriptLocalMethodsCustomizer() {
        super(CompilePhase.CONVERSION);
    }

    @Override
    public void call(SourceUnit source, GeneratorContext context, ClassNode node) {
        if (!node.isScript()) {
            return;
        }
        MethodNode run = node.getDeclaredMethod("run", org.codehaus.groovy.ast.Parameter.EMPTY_ARRAY);
        BlockStatement body = (BlockStatement) run.getCode();
        List<Statement> declarations = new ArrayList<>();
        List<Statement> assignments = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (MethodNode method : node.getMethods()) {
            if (method.getLineNumber() > 0 && !names.add(method.getName())) {
                throw new SecurityException("SCRIPT_SYNTAX_DENIED: overloaded local helper");
            }
        }
        for (MethodNode method : new ArrayList<>(node.getMethods())) {
            if (method.getLineNumber() <= 0) {
                continue;
            }
            MethodNode lowered = new MethodNode(method.getName(), method.getModifiers(), ClassHelper.OBJECT_TYPE,
                    method.getParameters(), method.getExceptions(), method.getCode());
            new ReturnAdder().visitMethod(lowered);
            lowered.getCode().visit(new CodeVisitorSupport() {
                @Override
                public void visitClosureExpression(ClosureExpression expression) {
                    // A nested closure retains its own return type.
                }

                @Override
                public void visitReturnStatement(ReturnStatement statement) {
                    if (ClassHelper.VOID_TYPE.equals(method.getReturnType())) {
                        StaticMethodCallExpression discard = new StaticMethodCallExpression(
                                ClassHelper.make(ScriptDataOperations.class), "discard",
                                new ArgumentListExpression(statement.getExpression()));
                        discard.setSourcePosition(statement.getExpression());
                        discard.putNodeMetaData(ScriptTypeCheckingExtension.DATA_CALL, Boolean.TRUE);
                        statement.setExpression(discard);
                    } else if (!ClassHelper.isDynamicTyped(method.getReturnType())) {
                        CastExpression cast = new CastExpression(method.getReturnType(), statement.getExpression());
                        cast.setSourcePosition(statement.getExpression());
                        statement.setExpression(cast);
                    }
                }
            });
            ClosureExpression closure = new ClosureExpression(method.getParameters(), lowered.getCode());
            closure.setSourcePosition(method);
            ClassNode closureType = ClassHelper.CLOSURE_TYPE.getPlainNodeReference();
            closureType.setGenericsTypes(new org.codehaus.groovy.ast.GenericsType[]{
                    new org.codehaus.groovy.ast.GenericsType(ClassHelper.OBJECT_TYPE)});
            DeclarationExpression declaration = new DeclarationExpression(
                    new VariableExpression(method.getName(), closureType),
                    Token.newSymbol(Types.ASSIGN, method.getLineNumber(), method.getColumnNumber()),
                    EmptyExpression.INSTANCE);
            declaration.setSourcePosition(method);
            declarations.add(new ExpressionStatement(declaration));
            BinaryExpression assignment = new BinaryExpression(new VariableExpression(method.getName()),
                    Token.newSymbol(Types.ASSIGN, method.getLineNumber(), method.getColumnNumber()), closure);
            assignment.setSourcePosition(method);
            assignments.add(new ExpressionStatement(assignment));
            node.removeMethod(method);
        }
        declarations.addAll(assignments);
        body.getStatements().addAll(0, declarations);
        body.visit(new CodeVisitorSupport() {
            @Override
            public void visitMethodCallExpression(MethodCallExpression call) {
                if (call.isImplicitThis() && names.contains(call.getMethodAsString())) {
                    call.setObjectExpression(new VariableExpression(call.getMethodAsString()));
                    call.setMethod(new org.codehaus.groovy.ast.expr.ConstantExpression("call"));
                    call.setImplicitThis(false);
                }
                super.visitMethodCallExpression(call);
            }
        });
    }
}
