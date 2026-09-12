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

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

import org.apache.hugegraph.HugeGraph;
import org.apache.hugegraph.schema.SchemaManager;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.Path;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.step.sideEffect.LambdaSideEffectStep;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Property;

import groovy.lang.Closure;
import groovy.lang.Script;

final class ScriptResults {

    private ScriptResults() {
    }

    static void prepare(Object value) {
        if (value instanceof Traversal.Admin) {
            Traversal.Admin<?, ?> traversal =
                    (Traversal.Admin<?, ?>) value;
            if (traversal.isLocked()) {
                throw new IllegalArgumentException("SCRIPT_RESULT_DENIED: already consumed traversal");
            }
            traversal.addStep(new LambdaSideEffectStep<>(
                    traversal, traverser -> validate(traverser.get())));
        } else {
            validate(value);
        }
    }

    static void validate(Object value) {
        validate(value, new IdentityHashMap<>(), 0, new int[]{0}, ScriptExecutionBudget.deadline());
    }

    private static void validate(Object value, IdentityHashMap<Object, Boolean> seen, int depth,
                                 int[] count, long deadline) {
        ScriptExecutionBudget.check(deadline);
        if (++count[0] > 100000) {
            throw new IllegalArgumentException("SCRIPT_RESULT_LIMIT");
        }
        if (value == null) {
            return;
        }
        if (depth > 64 || value instanceof Class || value instanceof ClassLoader ||
            value instanceof Script || value instanceof Closure || value instanceof Thread ||
            value instanceof HugeGraph || value instanceof TraversalSource ||
            value instanceof SchemaManager || value instanceof ScriptJobContext ||
            value instanceof Iterator) {
            throw new IllegalArgumentException("SCRIPT_RESULT_DENIED");
        }
        if (seen.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("SCRIPT_RESULT_DENIED: cyclic result");
        }
        try {
            if (value instanceof P ||
                value instanceof Graph) {
                throw new IllegalArgumentException("SCRIPT_RESULT_DENIED");
            } else if (value instanceof Path) {
                validate(((Path) value).objects(), seen, depth + 1, count, deadline);
            } else if (value instanceof Property) {
                Property<?> property =
                        (Property<?>) value;
                if (property.isPresent()) {
                    validate(property.value(), seen, depth + 1, count, deadline);
                }
            } else if (value instanceof Optional) {
                validate(((Optional<?>) value).orElse(null), seen, depth + 1, count, deadline);
            } else if (value.getClass().isArray()) {
                for (int i = 0; i < Array.getLength(value); i++) {
                    validate(Array.get(value, i), seen, depth + 1, count, deadline);
                }
            } else if (value instanceof Map) {
                ((Map<?, ?>) value).forEach((key, item) -> {
                    validate(key, seen, depth + 1, count, deadline);
                    validate(item, seen, depth + 1, count, deadline);
                });
            } else if (value instanceof Collection) {
                for (Object item : (Collection<?>) value) {
                    validate(item, seen, depth + 1, count, deadline);
                }
            } else if (value instanceof Map.Entry) {
                validate(((Map.Entry<?, ?>) value).getKey(), seen, depth + 1, count, deadline);
                validate(((Map.Entry<?, ?>) value).getValue(), seen, depth + 1, count, deadline);
            }
        } finally {
            seen.remove(value);
        }
    }
}
