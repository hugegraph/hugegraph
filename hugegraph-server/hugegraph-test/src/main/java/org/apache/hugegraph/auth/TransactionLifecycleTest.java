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

package org.apache.hugegraph.auth;

import java.util.concurrent.atomic.AtomicInteger;

import org.apache.hugegraph.HugeFactory;
import org.apache.hugegraph.auth.HugeGraphAuthProxy.Context;
import org.apache.hugegraph.auth.HugeGraphAuthProxy.ContextTask;
import org.apache.hugegraph.id.IdGenerator;
import org.apache.hugegraph.core.BaseCoreTest;
import org.apache.hugegraph.testutil.Assert;
import org.apache.hugegraph.traversal.algorithm.KneighborTraverser;
import org.apache.hugegraph.type.define.Directions;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Transaction;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class TransactionLifecycleTest extends BaseCoreTest {

    @Before
    public void initSchema() {
        graph().schema().vertexLabel("lifecycle").useCustomizeNumberId().create();
        HugeFactory.closeCurrentThreadTransactions();
    }

    @After
    public void cleanup() {
        try {
            HugeFactory.closeCurrentThreadTransactions();
        } finally {
            HugeGraphAuthProxy.resetContext();
        }
    }

    @Test
    public void testTraverserClosePreservesCallerWrites() {
        graph().addVertex(T.label, "lifecycle", T.id, 1L);
        graph().tx().commit();

        try (KneighborTraverser traverser = new KneighborTraverser(graph())) {
            Assert.assertTrue(traverser.kneighbor(IdGenerator.of(1L), Directions.BOTH,
                                                 null, 1, 100L, 100L).isEmpty());
            graph().addVertex(T.label, "lifecycle", T.id, 2L);
        }

        Assert.assertTrue(graph().tx().isOpen());
        graph().tx().commit();
        HugeFactory.closeCurrentThreadTransactions();
        Assert.assertEquals(2L, graph().traversal().V().count().next().longValue());
    }

    @Test
    public void testFailedTaskRollsBackDespiteCommitOnClose() {
        AtomicInteger rollbackEvents = new AtomicInteger();
        HugeGraphAuthProxy.setContext(Context.admin());
        IllegalStateException failure = new IllegalStateException("task failed after writing");
        ContextTask task = new ContextTask(() -> {
            Transaction tx = graph().tx();
            tx.onClose(Transaction.CLOSE_BEHAVIOR.COMMIT);
            tx.onReadWrite(Transaction.READ_WRITE_BEHAVIOR.MANUAL);
            tx.open();
            tx.addTransactionListener(status -> rollbackEvents.incrementAndGet());
            graph().addVertex(T.label, "lifecycle", T.id, 1L);
            throw failure;
        });
        HugeGraphAuthProxy.resetContext();

        Assert.assertSame(failure, Assert.assertThrows(IllegalStateException.class, task::run));
        Assert.assertFalse(graph().tx().isOpen());
        Assert.assertNull(HugeGraphAuthProxy.getContext());
        Assert.assertEquals(1, rollbackEvents.get());
        // Reuse the worker thread: AUTO and default rollback must be restored.
        Assert.assertEquals(0L, graph().traversal().V().count().next().longValue());
        graph().addVertex(T.label, "lifecycle", T.id, 2L);
        graph().tx().close();
        Assert.assertEquals(0L, graph().traversal().V().count().next().longValue());
        Assert.assertEquals(1, rollbackEvents.get());
    }

    @Test
    public void testSuccessfulTaskKeepsExplicitCommitAndResetsThreadState() {
        AtomicInteger commitEvents = new AtomicInteger();
        HugeGraphAuthProxy.setContext(Context.admin());
        ContextTask task = new ContextTask(() -> {
            Transaction tx = graph().tx();
            tx.onClose(Transaction.CLOSE_BEHAVIOR.COMMIT);
            tx.onReadWrite(Transaction.READ_WRITE_BEHAVIOR.MANUAL);
            tx.open();
            tx.addTransactionListener(status -> commitEvents.incrementAndGet());
            graph().addVertex(T.label, "lifecycle", T.id, 1L);
            tx.commit();
        });
        HugeGraphAuthProxy.resetContext();

        task.run();

        Assert.assertFalse(graph().tx().isOpen());
        Assert.assertNull(HugeGraphAuthProxy.getContext());
        Assert.assertEquals(1L, graph().traversal().V().count().next().longValue());
        graph().addVertex(T.label, "lifecycle", T.id, 2L);
        graph().tx().close();
        Assert.assertEquals(1L, graph().traversal().V().count().next().longValue());
        Assert.assertEquals(1, commitEvents.get());
    }
}
