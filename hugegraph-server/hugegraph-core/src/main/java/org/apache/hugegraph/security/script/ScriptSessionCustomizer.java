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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ClassCodeExpressionTransformer;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.CodeVisitorSupport;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.VariableScope;
import org.codehaus.groovy.ast.Variable;
import org.codehaus.groovy.ast.expr.ArgumentListExpression;
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.BooleanExpression;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.codehaus.groovy.ast.expr.EmptyExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.expr.PrefixExpression;
import org.codehaus.groovy.ast.expr.PostfixExpression;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.codehaus.groovy.ast.stmt.IfStatement;
import org.codehaus.groovy.ast.stmt.EmptyStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.ast.stmt.TryCatchStatement;
import org.codehaus.groovy.classgen.GeneratorContext;
import org.codehaus.groovy.classgen.VariableScopeVisitor;
import org.codehaus.groovy.control.CompilePhase;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.customizers.CompilationCustomizer;
import org.codehaus.groovy.syntax.Token;
import org.codehaus.groovy.syntax.Types;

/** Exports checked top-level data variables without enabling dynamic property access. */
final class ScriptSessionCustomizer extends CompilationCustomizer {

    private final int preludeLines;
    private final Set<String> inputNames;

    ScriptSessionCustomizer(int preludeLines, Set<String> inputNames) {
        super(CompilePhase.CONVERSION);
        this.preludeLines = preludeLines;
        this.inputNames = inputNames;
    }

    @Override
    public void call(SourceUnit source, GeneratorContext context, ClassNode node) {
        if (!node.isScript()) {
            return;
        }
        MethodNode run = node.getDeclaredMethod("run", org.codehaus.groovy.ast.Parameter.EMPTY_ARRAY);
        if (run == null || !(run.getCode() instanceof BlockStatement)) {
            throw new SecurityException("SCRIPT_SESSION_SYNTAX_DENIED");
        }
        BlockStatement original = (BlockStatement) run.getCode();
        List<Statement> prefix = new ArrayList<>();
        List<Statement> body = new ArrayList<>();
        Map<String, VariableExpression> declarations = new LinkedHashMap<>();
        Set<String> exported = new LinkedHashSet<>(this.inputNames);
        Set<String> lexicalNames = new LinkedHashSet<>();
        for (Statement statement : original.getStatements()) {
            if (statement.getLineNumber() > this.preludeLines && statement instanceof ExpressionStatement) {
                Expression expression = ((ExpressionStatement) statement).getExpression();
                if (expression instanceof DeclarationExpression &&
                    !((DeclarationExpression) expression).isMultipleAssignmentDeclaration()) {
                    lexicalNames.add(((DeclarationExpression) expression).getVariableExpression().getName());
                }
            }
        }
        exported.removeAll(lexicalNames);
        Set<String> declared = new LinkedHashSet<>(this.inputNames);
        original.visit(new CodeVisitorSupport() {
            @Override
            public void visitClosureExpression(ClosureExpression closure) {
                // Closure locals have their own scope and never create session bindings here.
            }

            @Override
            public void visitDeclarationExpression(DeclarationExpression declaration) {
                if (!declaration.isMultipleAssignmentDeclaration()) {
                    declared.add(declaration.getVariableExpression().getName());
                }
                super.visitDeclarationExpression(declaration);
            }
        });
        original.visit(new CodeVisitorSupport() {
            @Override
            public void visitClosureExpression(ClosureExpression closure) {
                // A closure can still mutate an already declared/session variable.
            }

            @Override
            public void visitBinaryExpression(BinaryExpression expression) {
                if (!(expression instanceof DeclarationExpression) &&
                    expression.getOperation().getType() == Types.ASSIGN &&
                    expression.getLeftExpression() instanceof VariableExpression) {
                    VariableExpression variable = (VariableExpression) expression.getLeftExpression();
                    if (!declared.contains(variable.getName())) {
                        if (variable.getName().startsWith("__hg")) {
                            throw new SecurityException("SCRIPT_SESSION_VARIABLE_DENIED");
                        }
                        declarations.putIfAbsent(variable.getName(), variable);
                        exported.add(variable.getName());
                    }
                }
                super.visitBinaryExpression(expression);
            }
        });
        for (Statement statement : original.getStatements()) {
            if (statement.getLineNumber() > 0 && statement.getLineNumber() <= this.preludeLines) {
                if (statement instanceof ExpressionStatement &&
                    ((ExpressionStatement) statement).getExpression() instanceof DeclarationExpression &&
                    lexicalNames.contains(((DeclarationExpression) ((ExpressionStatement) statement)
                            .getExpression()).getVariableExpression().getName())) {
                    continue;
                }
                prefix.add(statement);
                continue;
            }
            body.add(statement);
        }
        declarations.forEach((name, variable) -> {
            DeclarationExpression declaration = new DeclarationExpression(variable,
                    Token.newSymbol(Types.ASSIGN, -1, -1), EmptyExpression.INSTANCE);
            declaration.setSourcePosition(variable);
            prefix.add(new ExpressionStatement(declaration));
        });
        BlockStatement cleanup = new BlockStatement();
        for (String name : exported) {
            MethodCallExpression binding = binding();
            MethodCallExpression save = new MethodCallExpression(binding, "setVariable",
                    new ArgumentListExpression(new ConstantExpression(name), new VariableExpression(name)));
            // Only compiler-generated calls receive this trusted source position.
            save.setLineNumber(1);
            MethodCallExpression assigned = new MethodCallExpression(binding(), "hasVariable",
                    new ArgumentListExpression(new ConstantExpression(name)));
            assigned.setLineNumber(1);
            cleanup.addStatement(new IfStatement(new BooleanExpression(assigned),
                    new ExpressionStatement(save), EmptyStatement.INSTANCE));
        }
        prefix.add(new TryCatchStatement(new BlockStatement(body, new VariableScope()), cleanup));
        run.setCode(new BlockStatement(prefix, original.getVariableScope()));
        new VariableScopeVisitor(source).visitClass(node);
        Map<String, Variable> sessionVariables = new LinkedHashMap<>();
        for (Statement statement : prefix) {
            if (statement instanceof ExpressionStatement &&
                ((ExpressionStatement) statement).getExpression() instanceof DeclarationExpression) {
                VariableExpression variable = ((DeclarationExpression) ((ExpressionStatement) statement)
                        .getExpression()).getVariableExpression();
                if (exported.contains(variable.getName())) {
                    sessionVariables.put(variable.getName(), variable);
                }
            }
        }
        run.getCode().visit(new ClassCodeExpressionTransformer() {
            @Override
            protected SourceUnit getSourceUnit() {
                return source;
            }

            private boolean sessionVariable(Expression expression) {
                if (!(expression instanceof VariableExpression)) {
                    return false;
                }
                VariableExpression variable = (VariableExpression) expression;
                return sessionVariables.containsKey(variable.getName()) &&
                       variable.getAccessedVariable() == sessionVariables.get(variable.getName());
            }

            @Override
            public Expression transform(Expression expression) {
                if (expression instanceof ClosureExpression) {
                    // Resolve by accessed-variable identity, so closure parameters and lexical shadows stay local.
                    ((ClosureExpression) expression).getCode().visit(this);
                    return expression;
                }
                Expression transformed = super.transform(expression);
                VariableExpression assigned = null;
                boolean postfix = false;
                if (transformed instanceof BinaryExpression && !(transformed instanceof DeclarationExpression)) {
                    BinaryExpression binary = (BinaryExpression) transformed;
                    if (Set.of("=", "+=", "-=", "*=", "/=", "%=", "**=", "<<=", ">>=", ">>>=", "&=", "|=", "^=")
                            .contains(binary.getOperation().getText()) && this.sessionVariable(binary.getLeftExpression())) {
                        assigned = (VariableExpression) binary.getLeftExpression();
                    }
                } else if (transformed instanceof PrefixExpression &&
                           this.sessionVariable(((PrefixExpression) transformed).getExpression())) {
                    assigned = (VariableExpression) ((PrefixExpression) transformed).getExpression();
                } else if (transformed instanceof PostfixExpression &&
                           this.sessionVariable(((PostfixExpression) transformed).getExpression())) {
                    assigned = (VariableExpression) ((PostfixExpression) transformed).getExpression();
                    postfix = true;
                }
                if (assigned == null) {
                    return transformed;
                }
                ArgumentListExpression arguments = new ArgumentListExpression(binding(),
                        new ConstantExpression(assigned.getName()), transformed);
                if (postfix) {
                    VariableExpression current = new VariableExpression(assigned.getName(), assigned.getOriginType());
                    current.setAccessedVariable(assigned.getAccessedVariable());
                    arguments.addExpression(current);
                }
                MethodCallExpression record = new MethodCallExpression(
                        new ClassExpression(ClassHelper.make(ScriptSessionState.class)),
                        postfix ? "recordPostfix" : "record", arguments);
                record.setLineNumber(1);
                return record;
            }
        });
        new VariableScopeVisitor(source).visitClass(node);
    }

    private static MethodCallExpression binding() {
        MethodCallExpression binding = new MethodCallExpression(VariableExpression.THIS_EXPRESSION,
                "getBinding", ArgumentListExpression.EMPTY_ARGUMENTS);
        binding.setLineNumber(1);
        return binding;
    }
}
