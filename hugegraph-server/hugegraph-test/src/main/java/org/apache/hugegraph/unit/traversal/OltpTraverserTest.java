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

package org.apache.hugegraph.unit.traversal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.apache.commons.configuration2.MapConfiguration;
import org.apache.hugegraph.HugeFactory;
import org.apache.hugegraph.HugeGraph;
import org.apache.hugegraph.config.HugeConfig;
import org.apache.hugegraph.dist.RegisterUtil;
import org.apache.hugegraph.testutil.Whitebox;
import org.apache.hugegraph.traversal.algorithm.OltpTraverser;
import org.apache.hugegraph.util.Consumers;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.util.CloseableIterator;
import org.junit.Assert;
import org.junit.Test;

public class OltpTraverserTest {

    @Test
    public void testOneNormal() throws Exception {
        this.checkCleanup(false, "normal", 1);
    }

    @Test
    public void testOneFailure() throws Exception {
        this.checkCleanup(false, "failure", 1);
    }

    @Test
    public void testOneStop() throws Exception {
        this.checkCleanup(false, "stop", 1);
    }

    @Test
    public void testBatchNormal() throws Exception {
        this.checkCleanup(true, "normal", 1);
    }

    @Test
    public void testBatchFailure() throws Exception {
        this.checkCleanup(true, "failure", 1);
    }

    @Test
    public void testBatchStop() throws Exception {
        this.checkCleanup(true, "stop", 1);
    }

    @Test
    public void testIdleWorkers() throws Exception {
        this.checkCleanup(false, "normal", 3);
    }

    private void checkCleanup(boolean batch, String outcome, int workers) throws Exception {
        RegisterUtil.registerBackends();
        OltpTraverser.destroy();
        HugeConfig config = new HugeConfig(new MapConfiguration(Collections.emptyMap()));
        config.setProperty("backend", "memory");
        config.setProperty("serializer", "text");
        config.setProperty("store", "oltp_cleanup_" + batch + "_" + outcome + "_" + workers);
        config.setProperty("oltp.concurrent_threads", workers);
        HugeGraph graph = HugeFactory.open(config);
        ExecutorService executor = null;
        Consumers.ExecutorPool pool = null;
        try {
            graph.initBackend();
            graph.schema().propertyKey("name").asText().create();
            graph.schema().vertexLabel("person").properties("name").primaryKeys("name").create();
            Vertex saved = graph.addVertex(T.label, "person", "name", "saved");
            graph.tx().commit();
            Vertex pending = graph.addVertex(T.label, "person", "name", "caller_pending");
            AtomicReference<Thread> owner = new AtomicReference<>();
            Probe traverser = new Probe(graph);
            Consumer<Integer> consumer = item -> {
                owner.set(Thread.currentThread());
                Iterator<Vertex> vertices = graph.vertices(saved.id());
                try {
                    Assert.assertTrue(vertices.hasNext());
                    vertices.next();
                } finally {
                    CloseableIterator.closeIterator(vertices);
                }
                if ("failure".equals(outcome)) {
                    throw new IllegalStateException("worker failed");
                } else if ("stop".equals(outcome)) {
                    throw new Consumers.StopExecution("worker stopped");
                }
            };
            try {
                traverser.run(batch, consumer);
                Assert.assertNotEquals("failure", outcome);
            } catch (IllegalStateException e) {
                Assert.assertEquals("failure", outcome);
                Assert.assertEquals("worker failed", e.getMessage());
            }
            traverser.close();
            Assert.assertTrue(graph.tx().isOpen());
            Assert.assertTrue(graph.vertices(pending.id()).hasNext());
            graph.tx().commit();
            Assert.assertEquals("caller_pending", graph.vertices(pending.id()).next().value("name"));
            HugeFactory.closeCurrentThreadTransactions();

            pool = Whitebox.getInternalState(OltpTraverser.class, "executors");
            executor = pool.getExecutor();
            CountDownLatch allWorkers = new CountDownLatch(workers);
            List<Future<?>> checks = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                checks.add(executor.submit(() -> {
                    allWorkers.countDown();
                    try {
                        Assert.assertTrue(allWorkers.await(10L, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(e);
                    }
                    Assert.assertFalse(graph.tx().isOpen());
                    ThreadLocal<?> transactions = Whitebox.getInternalState(graph.tx(), "transactions");
                    Assert.assertNull(transactions.get());
                    if (workers == 1) {
                        Assert.assertSame(owner.get(), Thread.currentThread());
                    }
                }));
            }
            for (Future<?> check : checks) {
                check.get(10L, TimeUnit.SECONDS);
            }
        } finally {
            if (executor != null) {
                // Teardown must also release real worker owners on the failing baseline.
                CountDownLatch allWorkers = new CountDownLatch(workers);
                List<Future<?>> cleanup = new ArrayList<>();
                for (int i = 0; i < workers; i++) {
                    cleanup.add(executor.submit(() -> {
                        allWorkers.countDown();
                        try {
                            allWorkers.await(10L, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        HugeFactory.closeCurrentThreadTransactions();
                    }));
                }
                for (Future<?> close : cleanup) {
                    close.get(10L, TimeUnit.SECONDS);
                }
                pool.returnExecutor(executor);
            }
            HugeFactory.closeCurrentThreadTransactions();
            graph.close();
            HugeFactory.remove(graph);
            OltpTraverser.destroy();
        }
    }

    private static final class Probe extends OltpTraverser {

        private Probe(HugeGraph graph) {
            super(graph);
        }

        private void run(boolean batch, Consumer<Integer> consumer) {
            if (batch) {
                this.traverseByBatch(Collections.singletonList(Collections.singletonList(1).iterator()).iterator(),
                                     items -> items.forEachRemaining(consumer), "test-batch", 1);
            } else {
                this.traverseByOne(Collections.singletonList(1).iterator(), consumer, "test-one");
            }
        }
    }
}
