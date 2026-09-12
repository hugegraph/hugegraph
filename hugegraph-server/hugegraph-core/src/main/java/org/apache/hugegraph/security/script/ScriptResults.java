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
import org.apache.tinkerpop.gremlin.process.traversal.Traverser;
import org.apache.tinkerpop.gremlin.process.traversal.step.sideEffect.LambdaSideEffectStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.AbstractStep;
import org.apache.tinkerpop.gremlin.process.traversal.util.DefaultTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.util.FastNoSuchElementException;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Property;
import org.apache.tinkerpop.gremlin.structure.Transaction;
import org.apache.tinkerpop.gremlin.structure.util.CloseableIterator;

import groovy.lang.Closure;
import groovy.lang.MetaClass;
import groovy.lang.Script;

final class ScriptResults {

    private ScriptResults() {
    }

    static Object prepare(Object value) {
        if (value instanceof Traversal.Admin) {
            Traversal.Admin<?, ?> traversal =
                    (Traversal.Admin<?, ?>) value;
            if (traversal.isLocked()) {
                DefaultTraversal<Object, Object> checked = new DefaultTraversal<>();
                traversal.getGraph().ifPresent(checked::setGraph);
                checked.setSideEffects(traversal.getSideEffects());
                checked.addStep(new RemainingResultsStep(checked, traversal));
                return checked;
            }
            traversal.addStep(new LambdaSideEffectStep<>(
                    traversal, traverser -> validate(traverser.get())));
        } else if (value instanceof Iterator) {
            return new CheckedIterator((Iterator<?>) value);
        } else {
            validate(value);
        }
        return value;
    }

    private static final class CheckedIterator implements CloseableIterator<Object> {

        private final Iterator<?> original;
        private boolean closed;

        CheckedIterator(Iterator<?> original) {
            this.original = original;
        }

        @Override
        public boolean hasNext() {
            if (this.closed) {
                return false;
            }
            try {
                ScriptExecutionBudget.check(ScriptExecutionBudget.deadline());
                if (!this.original.hasNext()) {
                    this.close();
                    return false;
                }
                return true;
            } catch (RuntimeException | Error failure) {
                this.closeAfterFailure(failure);
                throw failure;
            }
        }

        @Override
        public Object next() {
            if (this.closed) {
                throw FastNoSuchElementException.instance();
            }
            try {
                ScriptExecutionBudget.check(ScriptExecutionBudget.deadline());
                Object next = this.original.next();
                validate(next);
                return next;
            } catch (RuntimeException | Error failure) {
                this.closeAfterFailure(failure);
                throw failure;
            }
        }

        private void closeAfterFailure(Throwable failure) {
            try {
                this.close();
            } catch (RuntimeException | Error closeFailure) {
                failure.addSuppressed(closeFailure);
            }
        }

        @Override
        public void close() {
            if (!this.closed) {
                this.closed = true;
                CloseableIterator.closeIterator(this.original);
            }
        }
    }

    private static final class RemainingResultsStep extends AbstractStep<Object, Object>
            implements AutoCloseable {

        private final Traversal.Admin<?, ?> original;
        private boolean closed;

        RemainingResultsStep(Traversal.Admin<?, ?> traversal, Traversal.Admin<?, ?> original) {
            super(traversal);
            this.original = original;
        }

        @Override
        @SuppressWarnings("unchecked")
        protected Traverser.Admin<Object> processNextStart() {
            ScriptExecutionBudget.check(ScriptExecutionBudget.deadline());
            if (!this.original.hasNext()) {
                throw FastNoSuchElementException.instance();
            }
            Traverser.Admin<Object> next = (Traverser.Admin<Object>) this.original.nextTraverser();
            validate(next.get());
            return next;
        }

        @Override
        public void close() throws Exception {
            if (!this.closed) {
                this.closed = true;
                this.original.close();
            }
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
            value instanceof Script || value instanceof Closure || value instanceof MetaClass ||
            value instanceof Thread ||
            value instanceof HugeGraph || value instanceof TraversalSource || value instanceof Transaction ||
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
