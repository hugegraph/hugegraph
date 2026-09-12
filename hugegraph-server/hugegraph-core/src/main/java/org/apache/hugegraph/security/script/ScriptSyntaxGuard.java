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

import org.codehaus.groovy.ast.AnnotatedNode;
import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.classgen.GeneratorContext;
import org.codehaus.groovy.control.CompilePhase;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.customizers.CompilationCustomizer;

/**
 * Rejects user annotations and declarations before local AST transforms run.
 * This is only the syntax gate: callers must separately constrain global
 * transforms, method calls, bindings and runtime execution.
 */
public final class ScriptSyntaxGuard extends CompilationCustomizer {

    public ScriptSyntaxGuard() {
        super(CompilePhase.CONVERSION);
    }

    @Override
    public void call(SourceUnit source, GeneratorContext context,
                     ClassNode node) {
        if (!node.isScript()) {
            throw rejected("class declaration");
        }
        for (MethodNode method : node.getMethods()) {
            if (method.getLineNumber() > 0) {
                throw rejected("method declaration");
            }
        }
        source.getAST().getImports().forEach(ScriptSyntaxGuard::checkAnnotations);
        source.getAST().getStarImports().forEach(ScriptSyntaxGuard::checkAnnotations);
        source.getAST().getStaticImports().values()
              .forEach(ScriptSyntaxGuard::checkAnnotations);
        source.getAST().getStaticStarImports().values()
              .forEach(ScriptSyntaxGuard::checkAnnotations);
        if (source.getAST().getPackage() != null) {
            checkAnnotations(source.getAST().getPackage());
        }
        new ClassCodeVisitorSupport() {
            @Override
            protected SourceUnit getSourceUnit() {
                return source;
            }

            @Override
            public void visitAnnotations(AnnotatedNode annotated) {
                checkAnnotations(annotated);
                super.visitAnnotations(annotated);
            }
        }.visitClass(node);
    }

    private static void checkAnnotations(AnnotatedNode node) {
        if (!node.getAnnotations().isEmpty()) {
            throw rejected("annotation");
        }
    }

    private static SecurityException rejected(String kind) {
        return new SecurityException("SCRIPT_SYNTAX_DENIED: " + kind);
    }
}
