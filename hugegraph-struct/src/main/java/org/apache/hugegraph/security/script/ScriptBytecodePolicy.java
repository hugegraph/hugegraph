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
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.apache.tinkerpop.gremlin.process.traversal.Bytecode;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.TextP;
import org.apache.tinkerpop.gremlin.process.traversal.Text;
import org.apache.tinkerpop.gremlin.process.traversal.util.ConnectiveP;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.Property;
import org.apache.tinkerpop.gremlin.structure.util.detached.DetachedEdge;
import org.apache.tinkerpop.gremlin.structure.util.detached.DetachedProperty;
import org.apache.tinkerpop.gremlin.structure.util.detached.DetachedVertex;
import org.apache.tinkerpop.gremlin.structure.util.detached.DetachedVertexProperty;
import org.apache.tinkerpop.gremlin.structure.util.reference.ReferenceEdge;
import org.apache.tinkerpop.gremlin.structure.util.reference.ReferenceProperty;
import org.apache.tinkerpop.gremlin.structure.util.reference.ReferenceVertex;
import org.apache.tinkerpop.gremlin.structure.util.reference.ReferenceVertexProperty;
import org.apache.tinkerpop.gremlin.util.function.Lambda;

/** Checks native bytecode before the existing traversal processor interprets it. */
public final class ScriptBytecodePolicy {

    private static final Set<Class<?>> ELEMENT_TYPES = Set.of(
            ReferenceVertex.class, ReferenceEdge.class, ReferenceVertexProperty.class, ReferenceProperty.class,
            DetachedVertex.class, DetachedEdge.class, DetachedVertexProperty.class, DetachedProperty.class);
    private static final Set<String> PREDICATE_TYPES = Set.of(
            "org.apache.tinkerpop.gremlin.process.traversal.Compare",
            "org.apache.tinkerpop.gremlin.process.traversal.Contains",
            "org.apache.tinkerpop.gremlin.process.traversal.Text");
    private static final Set<String> ENUM_TYPES = Set.of(
            "org.apache.tinkerpop.gremlin.structure.T",
            "org.apache.tinkerpop.gremlin.structure.Direction",
            "org.apache.tinkerpop.gremlin.structure.VertexProperty$Cardinality",
            "org.apache.tinkerpop.gremlin.process.traversal.Order",
            "org.apache.tinkerpop.gremlin.process.traversal.Scope",
            "org.apache.tinkerpop.gremlin.process.traversal.Pop",
            "org.apache.tinkerpop.gremlin.structure.Column",
            "org.apache.tinkerpop.gremlin.process.traversal.Pick",
            "org.apache.tinkerpop.gremlin.process.traversal.Merge",
            "org.apache.tinkerpop.gremlin.process.traversal.GType",
            "org.apache.tinkerpop.gremlin.process.traversal.DT");

    private ScriptBytecodePolicy() {
    }

    public static void validate(Bytecode bytecode) {
        check(bytecode, 0, new int[]{0});
    }

    // A string query builds on a trusted server-side source. Validate every new instruction;
    // its existing source configuration was already trusted on the Groovy/JavaTranslator routes.
    // Native client Bytecode always enters through validate(Bytecode) with no trusted prefix.
    static void validate(Bytecode bytecode, int sourceInstructions) {
        check(bytecode, 0, new int[]{0}, sourceInstructions);
    }

    private static void check(Object value, int depth, int[] count) {
        check(value, depth, count, 0);
    }

    private static void check(Object value, int depth, int[] count, int sourceInstructions) {
        if (++count[0] > 4096 || depth > 16) {
            throw new IllegalArgumentException("SCRIPT_BYTECODE_LIMIT");
        }
        if (value instanceof Lambda) {
            throw new IllegalArgumentException("SCRIPT_BYTECODE_LAMBDA_DENIED");
        }
        if (value instanceof Bytecode) {
            Bytecode code = (Bytecode) value;
            for (int i = sourceInstructions; i < code.getSourceInstructions().size(); i++) {
                Bytecode.Instruction instruction = code.getSourceInstructions().get(i);
                if (++count[0] > 4096) {
                    throw new IllegalArgumentException("SCRIPT_BYTECODE_LIMIT");
                }
                Object[] arguments = instruction.getArguments();
                String operator = instruction.getOperator();
                boolean allowed = ("withBulk".equals(operator) && arguments.length == 1 &&
                                   arguments[0] instanceof Boolean) ||
                                  ("withPath".equals(operator) && arguments.length == 0) ||
                                  ("withSack".equals(operator) && arguments.length == 1) ||
                                  ("withSideEffect".equals(operator) && arguments.length == 2 &&
                                   arguments[0] instanceof String);
                if (!allowed) {
                    throw new IllegalArgumentException("SCRIPT_BYTECODE_SOURCE_OPTION_DENIED");
                }
                for (Object argument : arguments) {
                    // Source state accepts ordinary values, never suppliers, reducers or strategies.
                    ScriptBindings.validateData(argument);
                    check(argument, depth + 1, count);
                }
            }
            for (Bytecode.Instruction instruction : code.getStepInstructions()) {
                if (++count[0] > 4096) {
                    throw new IllegalArgumentException("SCRIPT_BYTECODE_LIMIT");
                }
                if (!ScriptMethodPolicy.TRAVERSAL_STEPS.contains(instruction.getOperator()) &&
                    !"with".equals(instruction.getOperator())) {
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
                boolean ordinaryPredicate = predicate instanceof Enum &&
                        PREDICATE_TYPES.contains(((Enum<?>) predicate).getDeclaringClass().getName());
                if (!ordinaryPredicate && predicate.getClass() != Text.RegexPredicate.class) {
                    throw new IllegalArgumentException("SCRIPT_BYTECODE_PREDICATE_DENIED");
                }
                check(((P<?>) value).getValue(), depth + 1, count);
            }
        } else if (value instanceof Enum) {
            String type = ((Enum<?>) value).getDeclaringClass().getName();
            if (!ENUM_TYPES.contains(type)) {
                throw new IllegalArgumentException("SCRIPT_BYTECODE_ENUM_DENIED");
            }
        } else if (value instanceof Element || value instanceof Property) {
            // Client serializers use these data-only forms. Never call arbitrary provider accessors.
            if (!ELEMENT_TYPES.contains(value.getClass())) {
                throw new IllegalArgumentException("SCRIPT_BYTECODE_ELEMENT_DENIED");
            }
            if (value instanceof Element) {
                Element element = (Element) value;
                checkData(element.id(), depth + 1, count);
                checkData(element.label(), depth + 1, count);
                if (element instanceof Edge) {
                    check(((Edge) element).outVertex(), depth + 1, count);
                    check(((Edge) element).inVertex(), depth + 1, count);
                }
                Iterator<? extends Property<?>> properties = element.properties();
                while (properties.hasNext()) {
                    check(properties.next(), depth + 1, count);
                }
            }
            if (value instanceof Property) {
                Property<?> property = (Property<?>) value;
                checkData(property.key(), depth + 1, count);
                checkData(property.value(), depth + 1, count);
                // Parent links point back to the vertex/edge; they are not property payloads.
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
            ScriptBindings.validateData(value);
        }
    }

    private static void checkData(Object value, int depth, int[] count) {
        ScriptBindings.validateData(value);
        check(value, depth, count);
    }
}
