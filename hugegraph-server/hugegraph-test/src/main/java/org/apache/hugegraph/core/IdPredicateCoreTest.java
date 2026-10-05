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

package org.apache.hugegraph.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import org.apache.hugegraph.testutil.Assert;
import org.apache.hugegraph.traversal.optimize.HugeCountStepStrategy;
import org.apache.hugegraph.traversal.optimize.HugeGraphStepStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.GType;
import org.apache.tinkerpop.gremlin.process.traversal.NotP;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.TextP;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.HasStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.GraphStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.HasContainer;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.junit.Test;

public class IdPredicateCoreTest extends BaseCoreTest {

    private static final UUID UUID1 = UUID.fromString("835e1153-9281-4957-8691-cf79258e90eb");
    private static final UUID UUID2 = UUID.fromString("835e1153-9281-4957-8691-cf79258e90ee");
    private static final UUID MISSING = UUID.fromString("835e1153-9281-4957-8691-cf79258e90ef");

    private GraphTraversalSource[] sources() {
        return new GraphTraversalSource[] {
                graph().traversal(),
                graph().traversal().withoutStrategies(HugeGraphStepStrategy.class,
                                                     HugeCountStepStrategy.class)
        };
    }

    private Vertex[] initUuidGraph() {
        graph().schema().propertyKey("name").asText().create();
        graph().schema().vertexLabel("uuid").useCustomizeUuidId().properties("name").create();
        Vertex first = graph().addVertex(T.label, "uuid", T.id, UUID1, "name", "first");
        Vertex second = graph().addVertex(T.label, "uuid", T.id, UUID2, "name", "second");
        commitTx();
        return new Vertex[] {first, second};
    }

    private static HasContainer localIdContainer(GraphTraversal<?, ?> traversal) {
        traversal.asAdmin().applyStrategies();
        Assert.assertInstanceOf(GraphStep.class, traversal.asAdmin().getStartStep());
        Assert.assertEquals(0, ((GraphStep<?, ?>) traversal.asAdmin().getStartStep()).getIds().length);
        for (Step<?, ?> step : traversal.asAdmin().getSteps()) {
            if (!(step instanceof HasStep)) {
                continue;
            }
            for (HasContainer has : ((HasStep<?>) step).getHasContainers()) {
                if (T.id.getAccessor().equals(has.getKey())) {
                    return has;
                }
            }
        }
        throw new AssertionError("The local ID predicate must remain in HasStep");
    }

    private static void assertLocalId(GraphTraversalSource source, P<?> predicate,
                                      Vertex... expected) {
        GraphTraversal<Vertex, Vertex> traversal = source.V().hasId(predicate);
        localIdContainer(traversal);
        List<Vertex> actual = traversal.toList();
        Assert.assertEquals(expected.length, actual.size());
        Assert.assertEquals(new HashSet<>(Arrays.asList(expected)), new HashSet<>(actual));
    }

    @Test
    public void testNegatedUuidId() {
        Vertex[] vertices = this.initUuidGraph();
        GraphTraversal<Vertex, Vertex> equality = graph().traversal().V().hasId(P.eq(UUID1));
        equality.asAdmin().applyStrategies();
        Assert.assertEquals(1, ((GraphStep<?, ?>) equality.asAdmin().getStartStep()).getIds().length);
        Assert.assertEquals(Collections.singletonList(vertices[0]), equality.toList());
        for (GraphTraversalSource source : this.sources()) {
            Assert.assertEquals(Collections.singletonList(vertices[0]),
                                source.V().hasId(P.eq(vertices[0].id())).toList());
            assertLocalId(source, P.not(P.neq(UUID1)), vertices[0]);
            assertLocalId(source, P.not(P.eq(UUID1)), vertices[1]);
            assertLocalId(source, P.not(P.neq(MISSING)));
            assertLocalId(source, P.not(P.neq(vertices[0].id())), vertices[0]);
        }
    }

    @Test
    public void testNegatedUuidKeepsWholeHasStep() {
        Vertex[] vertices = this.initUuidGraph();
        for (GraphTraversalSource source : this.sources()) {
            GraphTraversal<Vertex, Vertex> traversal = source.V()
                    .hasId(P.not(P.neq(UUID1))).has("name", "first");
            HasContainer id = localIdContainer(traversal);
            Assert.assertInstanceOf(NotP.class, id.getPredicate());
            for (Step<?, ?> step : traversal.asAdmin().getSteps()) {
                if (step instanceof HasStep) {
                    List<HasContainer> containers = ((HasStep<?>) step).getHasContainers();
                    Assert.assertEquals(2, containers.size());
                    Assert.assertEquals(T.id.getAccessor(), containers.get(0).getKey());
                    Assert.assertEquals("name", containers.get(1).getKey());
                }
            }
            Assert.assertEquals(Collections.singletonList(vertices[0]), traversal.toList());
        }
    }

    private void assertUuidEqRetainedByProperty(P<?> property) {
        Vertex[] vertices = this.initUuidGraph();
        P<UUID> predicate = P.eq(UUID1);
        for (GraphTraversalSource source : this.sources()) {
            GraphTraversal<Vertex, Vertex> traversal = source.V()
                    .hasId(predicate).has("name", property);
            HasContainer id = localIdContainer(traversal);
            Assert.assertEquals(Collections.singletonList(vertices[0]), traversal.toList());
            Assert.assertNotSame(predicate, id.getPredicate());
            Assert.assertEquals(UUID1, predicate.getValue());
        }
    }

    @Test
    public void testUuidEqRetainedByNegatedProperty() {
        this.assertUuidEqRetainedByProperty(P.not(P.neq("first")));
    }

    @Test
    public void testUuidEqRetainedByTextProperty() {
        this.assertUuidEqRetainedByProperty(TextP.containing("ir"));
    }

    @Test
    public void testUuidEqRetainedByTypePredicate() {
        this.assertUuidEqRetainedByProperty(P.typeOf(GType.STRING));
    }

    @Test
    public void testNegatedUuidCollection() {
        Vertex[] vertices = this.initUuidGraph();
        List<Object> operands = Arrays.asList(UUID1, vertices[1].id());
        P<Object> predicate = P.not(P.without(operands));
        for (GraphTraversalSource source : this.sources()) {
            assertLocalId(source, predicate, vertices);
            assertLocalId(source, P.not(P.within(UUID1)), vertices[1]);
            assertLocalId(source, P.not(P.without(MISSING)));
        }
        Assert.assertEquals(operands, ((NotP<?>) predicate).negate().getValue());
        Assert.assertInstanceOf(UUID.class, operands.get(0));
    }

    @Test
    public void testNestedUuidPredicatesPreserveOriginal() {
        Vertex[] vertices = this.initUuidGraph();
        P<UUID> predicate = P.not(P.neq(UUID1)).and(P.not(P.eq(UUID2)));
        P<UUID> original = predicate.clone();
        for (GraphTraversalSource source : this.sources()) {
            assertLocalId(source, predicate, vertices[0]);
            assertLocalId(source, P.not(P.neq(MISSING)).or(P.not(P.neq(UUID2))), vertices[1]);
            assertLocalId(source, new NotP<>(P.neq(UUID1).and(P.neq(UUID2))), vertices);
            assertLocalId(source, new NotP<>(new NotP<>(P.eq(UUID1))), vertices[0]);
        }
        Assert.assertEquals(original, predicate);
        Assert.assertTrue(predicate.test(UUID1));
        Assert.assertFalse(predicate.test(UUID2));
    }

    @Test
    public void testNegatedUuidPredicateClone() {
        Vertex[] vertices = this.initUuidGraph();
        P<UUID> predicate = P.not(P.neq(UUID1));
        GraphTraversal<Vertex, Vertex> traversal = graph().traversal().V().hasId(predicate);
        HasContainer has = localIdContainer(traversal);
        Assert.assertInstanceOf(NotP.class, has.getPredicate());
        Assert.assertNotSame(predicate, has.getPredicate());
        Assert.assertEquals(UUID1, predicate.getValue());
        GraphTraversal.Admin<Vertex, Vertex> clone = traversal.asAdmin().clone();
        Assert.assertEquals(Collections.singletonList(vertices[0]), traversal.toList());
        Assert.assertEquals(Collections.singletonList(vertices[0]), clone.toList());
        Assert.assertEquals(Collections.singletonList(vertices[0]),
                            graph().traversal().V().hasId(predicate).toList());
        Assert.assertEquals(UUID1, predicate.getValue());
    }

    @Test
    public void testNegatedNumericIdPreservesComparison() {
        graph().schema().vertexLabel("number").useCustomizeNumberId().create();
        Vertex first = graph().addVertex(T.label, "number", T.id, 42L);
        Vertex second = graph().addVertex(T.label, "number", T.id, 43L);
        commitTx();
        for (GraphTraversalSource source : this.sources()) {
            assertLocalId(source, P.not(P.neq(42)), first);
            assertLocalId(source, P.not(P.neq(42L)), first);
            assertLocalId(source, P.not(P.neq(42.0)), first);
            assertLocalId(source, P.not(P.neq(42.5)));
            assertLocalId(source, P.not(P.eq(42.5)), first, second);
            assertLocalId(source, P.not(P.without(42, 43L)), first, second);
        }
    }

    @Test
    public void testNegatedStringIdPreservesStringMode() {
        graph().schema().vertexLabel("string").useCustomizeStringId().create();
        Vertex first = graph().addVertex(T.label, "string", T.id, "first");
        Vertex second = graph().addVertex(T.label, "string", T.id, "second");
        commitTx();
        for (GraphTraversalSource source : this.sources()) {
            assertLocalId(source, P.not(P.neq("first")), first);
            assertLocalId(source, P.not(P.eq("first")), second);
            assertLocalId(source, P.not(P.without("first", "second")), first, second);
            assertLocalId(source, P.not(P.within("missing")), first, second);
            assertLocalId(source, new NotP<>(new NotP<>(P.eq("first"))), first);
        }
    }

    @Test
    public void testIdTypeOperandRemainsSemanticMarker() {
        Vertex[] vertices = this.initUuidGraph();
        P<Object> predicate = P.not(P.typeOf(GType.STRING));
        for (GraphTraversalSource source : this.sources()) {
            GraphTraversal<Vertex, Vertex> traversal = source.V().hasId(predicate);
            HasContainer has = localIdContainer(traversal);
            Assert.assertEquals(GType.STRING, has.getPredicate().getValue());
            Assert.assertEquals(new HashSet<>(Arrays.asList(vertices)),
                                new HashSet<>(traversal.toList()));
        }
        Assert.assertEquals(GType.STRING, predicate.getValue());
    }
}
