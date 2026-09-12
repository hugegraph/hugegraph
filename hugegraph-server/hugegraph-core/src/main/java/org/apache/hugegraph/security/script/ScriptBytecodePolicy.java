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

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.apache.tinkerpop.gremlin.process.traversal.Bytecode;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.TextP;
import org.apache.tinkerpop.gremlin.process.traversal.util.ConnectiveP;
import org.apache.tinkerpop.gremlin.util.function.Lambda;

/** Checks native bytecode before the existing traversal processor interprets it. */
public final class ScriptBytecodePolicy {

    private ScriptBytecodePolicy() {
    }

    public static void validate(Bytecode bytecode) {
        check(bytecode, 0, new int[]{0});
    }

    private static void check(Object value, int depth, int[] count) {
        if (++count[0] > 4096 || depth > 16) {
            throw new IllegalArgumentException("SCRIPT_BYTECODE_LIMIT");
        }
        if (value instanceof Lambda) {
            throw new IllegalArgumentException("SCRIPT_BYTECODE_LAMBDA_DENIED");
        }
        if (value instanceof Bytecode) {
            Bytecode code = (Bytecode) value;
            if (!code.getSourceInstructions().isEmpty()) {
                throw new IllegalArgumentException("SCRIPT_BYTECODE_SOURCE_OPTION_DENIED");
            }
            for (Bytecode.Instruction instruction : code.getStepInstructions()) {
                if (++count[0] > 4096) {
                    throw new IllegalArgumentException("SCRIPT_BYTECODE_LIMIT");
                }
                if (!ScriptMethodPolicy.TRAVERSAL_STEPS.contains(instruction.getOperator())) {
                    throw new IllegalArgumentException("SCRIPT_BYTECODE_STEP_DENIED");
                }
                for (Object argument : instruction.getArguments()) {
                    check(argument, depth + 1, count);
                }
            }
        } else if (value instanceof Bytecode.Binding) {
            check(((Bytecode.Binding<?>) value).value(), depth + 1, count);
        } else if (value instanceof P) {
            if (value.getClass() != P.class && value.getClass() != TextP.class &&
                !value.getClass().getName().equals("org.apache.tinkerpop.gremlin.process.traversal.util.AndP") &&
                !value.getClass().getName().equals("org.apache.tinkerpop.gremlin.process.traversal.util.OrP")) {
                throw new IllegalArgumentException("SCRIPT_BYTECODE_PREDICATE_DENIED");
            }
            if (value instanceof ConnectiveP) {
                for (P<?> predicate : ((ConnectiveP<?>) value)
                        .getPredicates()) {
                    check(predicate, depth + 1, count);
                }
            } else {
                Object predicate = ((P<?>) value).getBiPredicate();
                if (!(predicate instanceof Enum) || !Set.of(
                        "org.apache.tinkerpop.gremlin.process.traversal.Compare",
                        "org.apache.tinkerpop.gremlin.process.traversal.Contains",
                        "org.apache.tinkerpop.gremlin.process.traversal.Text")
                        .contains(((Enum<?>) predicate).getDeclaringClass().getName())) {
                    throw new IllegalArgumentException("SCRIPT_BYTECODE_PREDICATE_DENIED");
                }
                check(((P<?>) value).getValue(), depth + 1, count);
            }
        } else if (value instanceof Enum) {
            String type = ((Enum<?>) value).getDeclaringClass().getName();
            if (!Set.of("org.apache.tinkerpop.gremlin.structure.T",
                    "org.apache.tinkerpop.gremlin.structure.Direction",
                    "org.apache.tinkerpop.gremlin.structure.VertexProperty$Cardinality",
                    "org.apache.tinkerpop.gremlin.process.traversal.Order",
                    "org.apache.tinkerpop.gremlin.process.traversal.Scope",
                    "org.apache.tinkerpop.gremlin.process.traversal.Pop",
                    "org.apache.tinkerpop.gremlin.structure.Column",
                    "org.apache.tinkerpop.gremlin.process.traversal.Pick").contains(type)) {
                throw new IllegalArgumentException("SCRIPT_BYTECODE_ENUM_DENIED");
            }
        } else if (value instanceof Collection) {
            for (Object item : (Collection<?>) value) {
                check(item, depth + 1, count);
            }
        } else if (value instanceof Map) {
            ((Map<?, ?>) value).forEach((key, item) -> {
                check(key, depth + 1, count);
                check(item, depth + 1, count);
            });
        } else if (value instanceof Object[]) {
            for (Object item : (Object[]) value) {
                check(item, depth + 1, count);
            }
        } else {
            Map<String, Object> single = new HashMap<>();
            single.put("value", value);
            ScriptBindings.client(single);
        }
    }
}
