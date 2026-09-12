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

import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.expr.Expression;
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
