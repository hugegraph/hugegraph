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
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;

import javax.script.SimpleBindings;

import org.apache.hugegraph.id.EdgeId;
import org.apache.hugegraph.id.IdGenerator;
import org.apache.hugegraph.struct.schema.SchemaElement;
import org.apache.hugegraph.struct.schema.builder.SchemaBuilder;
import org.apache.hugegraph.testutil.Whitebox;
import org.apache.hugegraph.util.Blob;
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
import org.apache.tinkerpop.gremlin.process.traversal.util.Metrics;
import org.apache.tinkerpop.gremlin.process.traversal.util.TraversalMetrics;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Property;
import org.apache.tinkerpop.gremlin.structure.Transaction;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.util.CloseableIterator;
import org.apache.tinkerpop.gremlin.util.function.ConstantSupplier;

import groovy.lang.Closure;
import groovy.lang.GString;
import groovy.lang.MetaClass;
import groovy.lang.Script;

final class ScriptResults {

    private static final Set<Class<?>> RESULT_TYPES = resultTypes();

    private ScriptResults() {
    }

    private static Set<Class<?>> resultTypes() {
        Set<Class<?>> types = new HashSet<>(Set.of(Date.class, OffsetDateTime.class, Blob.class,
                IdGenerator.StringId.class, IdGenerator.LongId.class, IdGenerator.UuidId.class, EdgeId.class));
        // Resolve fixed provider types without adding a Server or TinkerGraph dependency to Struct.
        for (String name : new String[]{
                "org.apache.hugegraph.structure.HugeVertex",
                "org.apache.hugegraph.structure.HugeVertex$HugeVertex4Insert",
                "org.apache.hugegraph.structure.HugeEdge",
                "org.apache.hugegraph.structure.HugeVertexProperty",
                "org.apache.hugegraph.structure.HugeEdgeProperty",
                "org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerVertex",
                "org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerEdge",
                "org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerVertexProperty",
                "org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerProperty",
                "org.apache.tinkerpop.gremlin.structure.util.detached.DetachedVertex",
                "org.apache.tinkerpop.gremlin.structure.util.detached.DetachedEdge",
                "org.apache.tinkerpop.gremlin.structure.util.detached.DetachedVertexProperty",
                "org.apache.tinkerpop.gremlin.structure.util.detached.DetachedProperty",
                "org.apache.tinkerpop.gremlin.structure.util.reference.ReferenceVertex",
                "org.apache.tinkerpop.gremlin.structure.util.reference.ReferenceEdge",
                "org.apache.tinkerpop.gremlin.structure.util.reference.ReferenceVertexProperty",
                "org.apache.tinkerpop.gremlin.structure.util.reference.ReferenceProperty",
                "org.apache.tinkerpop.gremlin.structure.util.empty.EmptyProperty",
                "org.apache.tinkerpop.gremlin.structure.util.empty.EmptyVertexProperty",
                "org.apache.tinkerpop.gremlin.structure.util.detached.DetachedPath",
                "org.apache.tinkerpop.gremlin.structure.util.reference.ReferencePath",
                "org.apache.tinkerpop.gremlin.process.traversal.step.util.MutablePath",
                "org.apache.tinkerpop.gremlin.process.traversal.step.util.ImmutablePath",
                "org.apache.tinkerpop.gremlin.process.traversal.step.util.EmptyPath",
                "org.apache.tinkerpop.gremlin.process.traversal.step.util.Tree",
                "org.apache.tinkerpop.gremlin.process.traversal.step.util.BulkSet",
                "org.apache.tinkerpop.gremlin.process.traversal.util.DefaultTraversalMetrics",
                "org.apache.tinkerpop.gremlin.process.traversal.util.ImmutableMetrics",
                "org.apache.tinkerpop.gremlin.process.traversal.util.MutableMetrics",
                "org.apache.tinkerpop.gremlin.process.traversal.util.DependantMutableMetrics",
                "org.apache.tinkerpop.gremlin.process.traversal.util.TraversalExplanation",
                "org.apache.tinkerpop.gremlin.process.traversal.util.ImmutableExplanation",
                "org.apache.hugegraph.type.define.Directions",
                "org.apache.hugegraph.struct.schema.PropertyKey",
                "org.apache.hugegraph.struct.schema.VertexLabel",
                "org.apache.hugegraph.struct.schema.EdgeLabel",
                "org.apache.hugegraph.struct.schema.IndexLabel",
                "groovy.lang.IntRange", "groovy.lang.NumberRange", "groovy.lang.ObjectRange", "groovy.lang.EmptyRange",
                "org.codehaus.groovy.runtime.GStringImpl",
                "groovy.json.internal.LazyMap", "org.apache.groovy.json.internal.LazyMap"}) {
            try {
                types.add(Class.forName(name, false, ScriptResults.class.getClassLoader()));
            } catch (ClassNotFoundException optionalProvider) {
                // Server provider types are absent in the Store runtime.
            }
        }
        return Set.copyOf(types);
    }

    private static boolean supported(Object value) {
        Class<?> type = value.getClass();
        return type.isArray() || RESULT_TYPES.contains(type) ||
               (type.getClassLoader() == null &&
                (value instanceof Map || value instanceof Collection || value instanceof Map.Entry ||
                 value instanceof Optional)) ||
               (value instanceof Enum &&
                (type.getClassLoader() == null ||
                 ScriptBytecodePolicy.isSupportedEnum((Enum<?>) value)));
    }

    private static Iterator<? extends Property<?>> properties(Element element) {
        // HugeGraph does not support vertex meta-properties; its implementation throws
        // rather than returning an empty iterator. Other fixed providers retain their payload checks.
        if (element.getClass().getName().equals("org.apache.hugegraph.structure.HugeVertexProperty")) {
            return Collections.emptyIterator();
        }
        return element.properties();
    }

    static Object prepare(Object value) {
        return prepare(value, ScriptExecutionBudget.deadline());
    }

    static Object prepare(Object value, long deadline) {
        try {
            ScriptExecutionBudget.check(deadline);
        } catch (RuntimeException | Error failure) {
            // Preparation still owns the result. The response iterator cannot
            // release a cursor that was rejected before ownership was handed off.
            closeRejected(value, new IdentityHashMap<>(), 0, new int[]{0}, failure);
            throw failure;
        }
        if (value instanceof Traversal.Admin) {
            Traversal.Admin<?, ?> traversal =
                    (Traversal.Admin<?, ?>) value;
            if (traversal.isLocked()) {
                DefaultTraversal<Object, Object> checked = new DefaultTraversal<>();
                traversal.getGraph().ifPresent(checked::setGraph);
                checked.setSideEffects(traversal.getSideEffects());
                checked.addStep(new RemainingResultsStep(checked, traversal, deadline));
                return checked;
            }
            traversal.addStep(new LambdaSideEffectStep<>(
                    traversal, traverser -> validate(traverser.get(), deadline)));
        } else if (value instanceof Iterator) {
            return new CheckedIterator((Iterator<?>) value, deadline);
        } else {
            validate(value, deadline);
        }
        return value;
    }

    private static final class CheckedIterator implements CloseableIterator<Object> {

        private final Iterator<?> original;
        private final long deadline;
        private boolean closed;

        CheckedIterator(Iterator<?> original, long deadline) {
            this.original = original;
            this.deadline = deadline;
        }

        @Override
        public boolean hasNext() {
            if (this.closed) {
                return false;
            }
            try {
                ScriptExecutionBudget.check(this.deadline);
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
                ScriptExecutionBudget.check(this.deadline);
                Object next = this.original.next();
                validate(next, this.deadline);
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
        private final long deadline;
        private boolean closed;

        RemainingResultsStep(Traversal.Admin<?, ?> traversal, Traversal.Admin<?, ?> original,
                             long deadline) {
            super(traversal);
            this.original = original;
            this.deadline = deadline;
        }

        @Override
        @SuppressWarnings("unchecked")
        protected Traverser.Admin<Object> processNextStart() {
            ScriptExecutionBudget.check(this.deadline);
            if (!this.original.hasNext()) {
                throw FastNoSuchElementException.instance();
            }
            Traverser.Admin<Object> next = (Traverser.Admin<Object>) this.original.nextTraverser();
            validate(next.get(), this.deadline);
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
        validate(value, ScriptExecutionBudget.deadline());
    }

    static void validate(Object value, long deadline) {
        // Scalar leaves cannot contain cycles or executable objects.
        if (value == null || ScriptBindings.isScalar(value.getClass())) {
            ScriptExecutionBudget.check(deadline);
            return;
        }
        try {
            validate(value, new IdentityHashMap<>(), 0, new int[]{0}, deadline);
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
            if (!(value instanceof Iterator) && !(value instanceof Traverser) &&
                !(value instanceof TraversalSideEffects) && value.getClass() != SimpleBindings.class &&
                !supported(value)) {
                return;
            }
            if (value instanceof Property && !((Property<?>) value).isPresent()) {
                return;
            }
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
                closeRejected(((Path) value).labels(), seen, depth + 1, count, failure);
            } else if (value instanceof Element) {
                closeRejected(((Element) value).id(), seen, depth + 1, count, failure);
                if (value instanceof Edge) {
                    for (Vertex endpoint : new Vertex[]{((Edge) value).inVertex(), ((Edge) value).outVertex()}) {
                        if (endpoint != null && RESULT_TYPES.contains(endpoint.getClass())) {
                            closeRejected(endpoint.id(), seen, depth + 1, count, failure);
                        }
                    }
                }
                if (value instanceof Property && ((Property<?>) value).isPresent()) {
                    closeRejected(((Property<?>) value).value(), seen, depth + 1, count, failure);
                }
                Iterator<? extends Property<?>> properties = properties((Element) value);
                try {
                    while (count[0] < 100000 && properties.hasNext()) {
                        closeRejected(properties.next(), seen, depth + 1, count, failure);
                    }
                } finally {
                    CloseableIterator.closeIterator(properties);
                }
            } else if (value instanceof Property) {
                Property<?> property = (Property<?>) value;
                if (property.isPresent()) {
                    closeRejected(property.value(), seen, depth + 1, count, failure);
                }
            } else if (value instanceof TraversalMetrics) {
                closeRejected(((TraversalMetrics) value).getMetrics(), seen, depth + 1, count, failure);
            } else if (value instanceof Metrics) {
                closeRejected(((Metrics) value).getAnnotations(), seen, depth + 1, count, failure);
                closeRejected(((Metrics) value).getNested(), seen, depth + 1, count, failure);
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

    private static void validateEndpoint(Vertex vertex, IdentityHashMap<Object, Boolean> seen, int depth,
                                         int[] count, long deadline) {
        if (vertex == null || !RESULT_TYPES.contains(vertex.getClass())) {
            throw new IllegalArgumentException("SCRIPT_RESULT_DENIED: unsupported edge endpoint");
        }
        validate(vertex.id(), seen, depth + 1, count, deadline);
        validate(vertex.label(), seen, depth + 1, count, deadline);
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
        if (depth > 64) {
            throw new IllegalArgumentException("SCRIPT_RESULT_DENIED");
        }
        if (ScriptBindings.isScalar(value.getClass())) {
            return;
        }
        if (value instanceof Class || value instanceof ClassLoader ||
            value instanceof Script || value instanceof Closure || value instanceof MetaClass ||
            value instanceof Thread ||
            ScriptHostTypes.isGraph(value) || value instanceof TraversalSource || value instanceof Transaction ||
            ScriptHostTypes.isSchemaManager(value) || value instanceof SchemaBuilder ||
            value instanceof ScriptJobContext ||
            value instanceof Matcher ||
            // Internal traversers remain in the pipeline; only their values reach this boundary.
            // A traverser returned as a value can retain executable objects and traversal state.
            value instanceof Iterator || value instanceof Traverser) {
            throw new IllegalArgumentException("SCRIPT_RESULT_DENIED");
        }
        if (!supported(value)) {
            throw new IllegalArgumentException("SCRIPT_RESULT_DENIED: unsupported result type");
        }
        if (value instanceof Property && !((Property<?>) value).isPresent()) {
            return;
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
                validate(((Path) value).labels(), seen, depth + 1, count, deadline);
            } else if (value instanceof Element) {
                Element element = (Element) value;
                validate(element.id(), seen, depth + 1, count, deadline);
                validate(element.label(), seen, depth + 1, count, deadline);
                if (value instanceof Edge) {
                    validateEndpoint(((Edge) value).inVertex(), seen, depth, count, deadline);
                    validateEndpoint(((Edge) value).outVertex(), seen, depth, count, deadline);
                }
                if (value instanceof Property && ((Property<?>) value).isPresent()) {
                    validate(((Property<?>) value).value(), seen, depth + 1, count, deadline);
                }
                Iterator<? extends Property<?>> properties = properties(element);
                try {
                    while (properties.hasNext()) {
                        validate(properties.next(), seen, depth + 1, count, deadline);
                    }
                } finally {
                    CloseableIterator.closeIterator(properties);
                }
            } else if (value instanceof Property) {
                Property<?> property = (Property<?>) value;
                if (property.isPresent()) {
                    validate(property.value(), seen, depth + 1, count, deadline);
                }
            } else if (value instanceof TraversalMetrics) {
                validate(((TraversalMetrics) value).getMetrics(), seen, depth + 1, count, deadline);
            } else if (value instanceof Metrics) {
                validate(((Metrics) value).getAnnotations(), seen, depth + 1, count, deadline);
                validate(((Metrics) value).getNested(), seen, depth + 1, count, deadline);
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
