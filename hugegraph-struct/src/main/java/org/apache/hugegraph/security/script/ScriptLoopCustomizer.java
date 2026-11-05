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

import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.expr.ArgumentListExpression;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.DoWhileStatement;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.codehaus.groovy.ast.stmt.ForStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.ast.stmt.WhileStatement;
import org.codehaus.groovy.classgen.GeneratorContext;
import org.codehaus.groovy.control.CompilePhase;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.customizers.CompilationCustomizer;

/** Captures a request deadline in the script so returned closures retain it. */
public final class ScriptLoopCustomizer extends CompilationCustomizer {

    public ScriptLoopCustomizer() {
        super(CompilePhase.CONVERSION);
    }

    @Override
    public void call(SourceUnit source, GeneratorContext context, ClassNode node) {
        new ClassCodeVisitorSupport() {
            @Override
            protected SourceUnit getSourceUnit() {
                return source;
            }

            private Statement checked(Statement body) {
                BlockStatement block = new BlockStatement();
                block.addStatement(new ExpressionStatement(new StaticMethodCallExpression(
                        ClassHelper.make(ScriptExecutionBudget.class), "check",
                        new ArgumentListExpression(new VariableExpression("__hgDeadline")))));
                block.addStatement(body);
                return block;
            }

            @Override
            public void visitClosureExpression(ClosureExpression expression) {
                expression.setCode(this.checked(expression.getCode()));
                super.visitClosureExpression(expression);
            }

            @Override
            public void visitForLoop(ForStatement statement) {
                statement.setLoopBlock(this.checked(statement.getLoopBlock()));
                super.visitForLoop(statement);
            }

            @Override
            public void visitWhileLoop(WhileStatement statement) {
                statement.setLoopBlock(this.checked(statement.getLoopBlock()));
                super.visitWhileLoop(statement);
            }

            @Override
            public void visitDoWhileLoop(DoWhileStatement statement) {
                statement.setLoopBlock(this.checked(statement.getLoopBlock()));
                super.visitDoWhileLoop(statement);
            }
        }.visitClass(node);
    }
}
