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

package org.apache.hugegraph.unit.core;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.AbstractCollection;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import javax.script.Bindings;
import javax.script.ScriptException;

import org.apache.hugegraph.id.IdGenerator;
import org.apache.hugegraph.security.script.PolicyScriptEngine;
import org.apache.hugegraph.security.script.ScriptExecutionBudget;
import org.apache.hugegraph.security.script.ScriptExecutionProfile;
import org.apache.hugegraph.util.Blob;
import org.apache.tinkerpop.gremlin.process.traversal.Order;
import org.apache.tinkerpop.gremlin.process.traversal.Path;
import org.apache.tinkerpop.gremlin.process.traversal.step.sideEffect.InjectStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.MutablePath;
import org.apache.tinkerpop.gremlin.process.traversal.util.DefaultTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.util.DefaultTraversalMetrics;
import org.apache.tinkerpop.gremlin.process.traversal.util.MutableMetrics;
import org.apache.tinkerpop.gremlin.structure.util.CloseableIterator;
import org.apache.tinkerpop.gremlin.structure.util.detached.DetachedEdge;
import org.apache.tinkerpop.gremlin.structure.util.empty.EmptyProperty;
import org.apache.tinkerpop.gremlin.structure.util.empty.EmptyVertexProperty;
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerFactory;
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph;
import org.junit.Assert;
import org.junit.Test;

public class ScriptResultPreparationTest {

    @Test
    public void testExpiredPreparationClosesUnownedIterator() throws Exception {
        TrackingIterator cursor = new TrackingIterator();
        Throwable failure = preparationFailure(cursor, System.nanoTime() - 1L);
        Assert.assertEquals("SCRIPT_EXECUTION_TIMEOUT", failure.getMessage());
        Assert.assertEquals(1, cursor.closes);
        Assert.assertEquals(0, cursor.reads);
        discarded(Map.of("cursor", cursor), cursor);
        Assert.assertEquals("session cleanup must not close the rejected result twice", 1, cursor.closes);
    }

    @Test
    public void testInterruptedPreparationClosesUnownedIterator() throws Exception {
        TrackingIterator cursor = new TrackingIterator();
        Thread.currentThread().interrupt();
        try {
            Throwable failure = preparationFailure(cursor, ScriptExecutionBudget.deadline());
            Assert.assertEquals("SCRIPT_EXECUTION_TIMEOUT", failure.getMessage());
            Assert.assertTrue(Thread.currentThread().isInterrupted());
            Assert.assertEquals(1, cursor.closes);
            Assert.assertEquals(0, cursor.reads);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void testExpiredPreparationClosesMaterializedTraversal() throws Exception {
        TrackingTraversal traversal = new TrackingTraversal();
        Assert.assertTrue(traversal.hasNext());
        Assert.assertTrue(traversal.isLocked());
        Throwable failure = preparationFailure(traversal, System.nanoTime() - 1L);
        Assert.assertEquals("SCRIPT_EXECUTION_TIMEOUT", failure.getMessage());
        Assert.assertEquals(1, traversal.closes);
    }

    @Test
    public void testExpiredPreparationClosesNestedCursorsAfterCloseFailure() throws Exception {
        TrackingIterator first = new TrackingIterator();
        first.closeFailure = new IllegalStateException("close failed");
        TrackingIterator second = new TrackingIterator();
        Throwable failure = preparationFailure(List.of(first, Map.of("cursor", second)),
                                               System.nanoTime() - 1L);
        Assert.assertEquals("SCRIPT_EXECUTION_TIMEOUT", failure.getMessage());
        Assert.assertEquals(1, failure.getSuppressed().length);
        Assert.assertSame(first.closeFailure, failure.getSuppressed()[0].getCause());
        Assert.assertEquals(1, first.closes);
        Assert.assertEquals(1, second.closes);
        Assert.assertEquals(0, first.reads + second.reads);
    }

    @Test
    public void testSuccessfulPreparationTransfersCursorOwnership() throws Exception {
        TrackingIterator cursor = new TrackingIterator();
        Iterator<?> prepared = (Iterator<?>) resultMethod("prepare", Object.class, long.class)
                .invoke(null, cursor, ScriptExecutionBudget.deadline());
        Assert.assertEquals(0, cursor.closes);
        Assert.assertEquals(1, prepared.next());
        CloseableIterator.closeIterator(prepared);
        CloseableIterator.closeIterator(prepared);
        Assert.assertEquals(1, cursor.closes);
        Assert.assertEquals(1, cursor.reads);
    }

    @Test
    public void testRejectsUnknownResultsAndNestedCapabilitiesWithoutInvokingGetters() throws Exception {
        Object capability = new Object() {
            public String getValue() {
                throw new AssertionError("serializer getter must not run");
            }
        };
        for (Object value : List.of(capability, List.of(capability), Map.of("value", capability))) {
            Assert.assertTrue(preparationFailure(value, ScriptExecutionBudget.deadline())
                              .getMessage().startsWith("SCRIPT_RESULT_DENIED"));
        }
        TrackingIterator cursor = new TrackingIterator();
        Throwable failure = preparationFailure(List.of(capability, cursor), ScriptExecutionBudget.deadline());
        Assert.assertTrue(failure.getMessage().startsWith("SCRIPT_RESULT_DENIED"));
        Assert.assertEquals(1, cursor.closes);
    }

    @Test
    public void testRejectedCustomResultContainerIsNotIteratedDuringValidationOrCleanup() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Object container = new AbstractCollection<Object>() {
            @Override
            public Iterator<Object> iterator() {
                calls.incrementAndGet();
                return List.<Object>of(1).iterator();
            }

            @Override
            public int size() {
                calls.incrementAndGet();
                return 1;
            }
        };
        Assert.assertTrue(preparationFailure(container, ScriptExecutionBudget.deadline())
                          .getMessage().startsWith("SCRIPT_RESULT_DENIED"));
        Assert.assertEquals(0, calls.get());
    }

    @Test
    public void testRetainsSupportedDataElementsPathsAndTraversalDiagnostics() throws Exception {
        try (TinkerGraph graph = TinkerFactory.createModern()) {
            for (Object value : List.of(new Date(0), Blob.wrap(new byte[]{1}), IdGenerator.of("v1"),
                    Order.asc, java.math.RoundingMode.HALF_UP, graph.traversal().V(1).next(),
                    graph.traversal().E().next(), graph.traversal().V(1).properties("name").next(),
                    EmptyProperty.instance(), EmptyVertexProperty.instance(),
                    List.of(EmptyVertexProperty.instance()),
                    graph.traversal().V(1).out().path().next(), graph.traversal().V().profile().next(),
                    graph.traversal().inject("2020-01-01T00:00:00Z").asDate().next(),
                    List.of(OffsetDateTime.parse("2020-01-01T00:00:00Z")),
                    graph.traversal().V().explain())) {
                Assert.assertSame(value, resultMethod("prepare", Object.class, long.class)
                        .invoke(null, value, ScriptExecutionBudget.deadline()));
            }
        }
    }

    @Test
    public void testRejectsCapabilitiesInEdgeEndpointIds() throws Exception {
        for (boolean incoming : new boolean[]{false, true}) {
            TrackingIterator cursor = new TrackingIterator();
            DetachedEdge edge = new DetachedEdge(1, "edge", Map.of(),
                    incoming ? 1 : cursor, "person", incoming ? cursor : 2, "person");
            Assert.assertTrue(preparationFailure(edge, ScriptExecutionBudget.deadline())
                              .getMessage().startsWith("SCRIPT_RESULT_DENIED"));
            Assert.assertEquals(1, cursor.closes);
            Assert.assertEquals(0, cursor.reads);
        }
        DetachedEdge edge = new DetachedEdge(1, "edge", Map.of(), 1, "person", 2, "person");
        Assert.assertSame(edge, resultMethod("prepare", Object.class, long.class)
                .invoke(null, edge, ScriptExecutionBudget.deadline()));
    }

    @Test
    public void testRejectedMetricAnnotationsCloseCursors() throws Exception {
        for (boolean traversal : new boolean[]{false, true}) {
            TrackingIterator cursor = new TrackingIterator();
            MutableMetrics metrics = new MutableMetrics("id", "step");
            metrics.getAnnotations().put("cursor", cursor);
            Object result = traversal ? new DefaultTraversalMetrics(1L, List.of(metrics)) : metrics;
            Assert.assertTrue(preparationFailure(result, ScriptExecutionBudget.deadline())
                              .getMessage().startsWith("SCRIPT_RESULT_DENIED"));
            Assert.assertEquals(1, cursor.closes);
            Assert.assertEquals(0, cursor.reads);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testRejectedPathLabelsCloseCursors() throws Exception {
        TrackingIterator cursor = new TrackingIterator();
        Path path = MutablePath.make().extend(1, new HashSet<>());
        ((Set<Object>) (Set<?>) path.labels().get(0)).add(cursor);
        Assert.assertTrue(preparationFailure(path, ScriptExecutionBudget.deadline())
                          .getMessage().startsWith("SCRIPT_RESULT_DENIED"));
        Assert.assertEquals(1, cursor.closes);
        Assert.assertEquals(0, cursor.reads);
    }

    @Test
    public void testRejectsGraphSourcesInsideMutablePathLabels() throws Exception {
        try (TinkerGraph graph = TinkerFactory.createModern();
             PolicyScriptEngine engine = new PolicyScriptEngine(ScriptExecutionProfile.QUERY)) {
            Bindings bindings = engine.createBindings();
            bindings.put("g", graph.traversal());
            ScriptException failure = Assert.assertThrows(ScriptException.class,
                    () -> engine.eval(
                    "def p = g.inject(1).as('x').path().by(__.identity()).next(); " +
                    "Set<Object> labels = (Set<Object>) p.labels().get(0); labels.add(g); p", bindings));
            Assert.assertNotNull(failure.getCause());
            Assert.assertTrue(failure.getCause().getMessage().startsWith("SCRIPT_RESULT_DENIED"));
        }
    }

    @Test
    public void testRetainsEmptyRangesDirectlyAndInsideContainers() throws Exception {
        try (PolicyScriptEngine engine = new PolicyScriptEngine(ScriptExecutionProfile.QUERY)) {
            Assert.assertEquals(List.of(), engine.eval("1..<1"));
            Assert.assertEquals(Map.of("range", List.of()), engine.eval("[range: 1..<1]"));
        }
    }

    private static Throwable preparationFailure(Object value, long deadline) throws Exception {
        Method prepare = resultMethod("prepare", Object.class, long.class);
        InvocationTargetException failure = Assert.assertThrows(InvocationTargetException.class,
                () -> prepare.invoke(null, value, deadline));
        return failure.getCause();
    }

    private static void discarded(Object candidate, Object result) throws Exception {
        resultMethod("closeDiscarded", Object.class, Object.class).invoke(null, candidate, result);
    }

    private static Method resultMethod(String name, Class<?>... types) throws Exception {
        Class<?> results = Class.forName("org.apache.hugegraph.security.script.ScriptResults");
        Method method = results.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method;
    }

    private static final class TrackingIterator implements CloseableIterator<Integer> {

        private int closes;
        private int reads;
        private RuntimeException closeFailure;

        @Override
        public boolean hasNext() {
            this.reads++;
            return true;
        }

        @Override
        public Integer next() {
            this.reads++;
            return 1;
        }

        @Override
        public void close() {
            this.closes++;
            if (this.closeFailure != null) {
                throw this.closeFailure;
            }
        }
    }

    private static final class TrackingTraversal extends DefaultTraversal<Integer, Integer> {

        private static final long serialVersionUID = 1L;
        private int closes;

        private TrackingTraversal() {
            this.addStep(new InjectStep<>(this, 1));
        }

        @Override
        public void close() throws Exception {
            this.closes++;
            super.close();
        }
    }
}
