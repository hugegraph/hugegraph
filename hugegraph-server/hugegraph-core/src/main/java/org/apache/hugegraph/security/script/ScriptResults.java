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
import org.apache.hugegraph.schema.SchemaElement;
import org.apache.hugegraph.schema.builder.SchemaBuilder;
import org.apache.hugegraph.testutil.Whitebox;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.Path;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalSideEffects;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.Traverser;
import org.apache.tinkerpop.gremlin.process.traversal.step.sideEffect.LambdaSideEffectStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.AbstractStep;
import org.apache.tinkerpop.gremlin.process.traversal.util.DefaultTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.util.DefaultTraversalSideEffects;
import org.apache.tinkerpop.gremlin.process.traversal.util.FastNoSuchElementException;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Property;
import org.apache.tinkerpop.gremlin.structure.Transaction;
import org.apache.tinkerpop.gremlin.structure.util.CloseableIterator;
import org.apache.tinkerpop.gremlin.util.function.ConstantSupplier;

import groovy.lang.Closure;
import groovy.lang.GString;
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
        try {
            validate(value, new IdentityHashMap<>(), 0, new int[]{0}, ScriptExecutionBudget.deadline());
        } catch (RuntimeException | Error failure) {
            // Rejected values never reach the response owner. Close nested cursors as well,
            // including those after the first invalid item, without consuming their results.
            closeRejected(value, new IdentityHashMap<>(), 0, new int[]{0}, failure);
            throw failure;
        }
    }

    static void closeDiscarded(Object candidate, Object result) {
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        // The returned value is owned by result preparation or by the response iterator.
        // Record its references without closing, then release only discarded session cursors.
        closeRejected(result, seen, 0, new int[]{0}, null);
        closeRejected(candidate, seen, 0, new int[]{0},
                      new IllegalArgumentException("SCRIPT_SESSION_STATE_DISCARDED"));
    }

    private static void closeRejected(Object value, IdentityHashMap<Object, Boolean> seen, int depth,
                                      int[] count, Throwable failure) {
        // Cleanup ignores the expired execution deadline but retains bounded container traversal.
        if (value == null || depth > 64 || ++count[0] > 100000 || seen.put(value, Boolean.TRUE) != null) {
            return;
        }
        try {
            if (value instanceof Iterator) {
                if (failure != null) {
                    CloseableIterator.closeIterator((Iterator<?>) value);
                }
                if (value instanceof Traversal.Admin) {
                    closeRejected(((Traversal.Admin<?, ?>) value).getSideEffects(),
                                  seen, depth + 1, count, failure);
                }
            } else if (value instanceof Traverser) {
                Traverser<?> traverser = (Traverser<?>) value;
                closeRejected(traverser.get(), seen, depth + 1, count, failure);
                closeRejected(traverser.path(), seen, depth + 1, count, failure);
                try {
                    closeRejected(traverser.sack(), seen, depth + 1, count, failure);
                } catch (UnsupportedOperationException ignored) {
                    // Traversers without a sack still have to release get()/path()/sideEffects.
                }
                if (traverser instanceof Traverser.Admin) {
                    closeRejected(((Traverser.Admin<?>) traverser).getSideEffects(),
                                  seen, depth + 1, count, failure);
                }
            } else if (value instanceof TraversalSideEffects) {
                closeStoredSideEffects((TraversalSideEffects) value, seen, depth, count, failure);
            } else if (value instanceof SchemaElement) {
                closeRejected(((SchemaElement) value).userdata(), seen, depth + 1, count, failure);
            } else if (value instanceof GString) {
                closeRejected(((GString) value).getValues(), seen, depth + 1, count, failure);
            } else if (value instanceof Path) {
                closeRejected(((Path) value).objects(), seen, depth + 1, count, failure);
            } else if (value instanceof Property) {
                Property<?> property = (Property<?>) value;
                if (property.isPresent()) {
                    closeRejected(property.value(), seen, depth + 1, count, failure);
                }
            } else if (value instanceof Optional) {
                closeRejected(((Optional<?>) value).orElse(null), seen, depth + 1, count, failure);
            } else if (value.getClass().isArray()) {
                for (int i = 0; i < Array.getLength(value) && count[0] < 100000; i++) {
                    closeRejected(Array.get(value, i), seen, depth + 1, count, failure);
                }
            } else if (value instanceof Map) {
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                    closeRejected(entry.getKey(), seen, depth + 1, count, failure);
                    closeRejected(entry.getValue(), seen, depth + 1, count, failure);
                    if (count[0] >= 100000) {
                        break;
                    }
                }
            } else if (value instanceof Collection) {
                for (Object item : (Collection<?>) value) {
                    closeRejected(item, seen, depth + 1, count, failure);
                    if (count[0] >= 100000) {
                        break;
                    }
                }
            } else if (value instanceof Map.Entry) {
                closeRejected(((Map.Entry<?, ?>) value).getKey(), seen, depth + 1, count, failure);
                closeRejected(((Map.Entry<?, ?>) value).getValue(), seen, depth + 1, count, failure);
            }
        } catch (RuntimeException | Error closeFailure) {
            if (failure != null && closeFailure != failure) {
                failure.addSuppressed(closeFailure);
            }
        }
    }

    private static void closeStoredSideEffects(TraversalSideEffects effects,
                                               IdentityHashMap<Object, Boolean> seen, int depth,
                                               int[] count, Throwable failure) {
        Map<?, ?> stored = storedSideEffectMap(effects, "objectMap");
        if (stored != null) {
            for (Object item : stored.values()) {
                closeRejected(item, seen, depth + 1, count, failure);
                if (count[0] >= 100000) {
                    return;
                }
            }
        }
        Map<?, ?> suppliers = storedSideEffectMap(effects, "supplierMap");
        if (suppliers == null) {
            return;
        }
        for (Object supplier : suppliers.values()) {
            // withSideEffect(String, A) stores A in a ConstantSupplier. Read that boxed
            // value without calling TraversalSideEffects.get(key), which would run unused
            // suppliers. Other Supplier implementations stay unevaluated.
            if (supplier instanceof ConstantSupplier) {
                closeRejected(((ConstantSupplier<?>) supplier).get(), seen, depth + 1, count, failure);
                if (count[0] >= 100000) {
                    return;
                }
            }
        }
    }

    private static Map<?, ?> storedSideEffectMap(TraversalSideEffects effects, String field) {
        if (!(effects instanceof DefaultTraversalSideEffects)) {
            return null;
        }
        try {
            Object stored = Whitebox.getInternalState(effects, field);
            return stored instanceof Map ? (Map<?, ?>) stored : null;
        } catch (RuntimeException ignored) {
            // Unknown side-effect layouts must not force Supplier evaluation through get(key).
            return null;
        }
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
            value instanceof SchemaManager || value instanceof SchemaBuilder || value instanceof ScriptJobContext ||
            // Internal traversers remain in the pipeline; only their values reach this boundary.
            // A traverser returned as a value can retain executable objects and traversal state.
            value instanceof Iterator || value instanceof Traverser) {
            throw new IllegalArgumentException("SCRIPT_RESULT_DENIED");
        }
        if (seen.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("SCRIPT_RESULT_DENIED: cyclic result");
        }
        try {
            if (value instanceof P ||
                value instanceof Graph) {
                throw new IllegalArgumentException("SCRIPT_RESULT_DENIED");
            } else if (value instanceof SchemaElement) {
                validate(((SchemaElement) value).userdata(), seen, depth + 1, count, deadline);
            } else if (value instanceof GString) {
                // Lazy interpolation must not carry executable closures past transaction/context cleanup.
                validate(((GString) value).getValues(), seen, depth + 1, count, deadline);
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
