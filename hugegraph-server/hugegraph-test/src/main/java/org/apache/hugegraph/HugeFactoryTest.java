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

package org.apache.hugegraph;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.hugegraph.backend.store.BackendStoreProvider;
import org.apache.hugegraph.backend.tx.GraphTransaction;
import org.apache.hugegraph.config.HugeConfig;
import org.apache.hugegraph.dist.RegisterUtil;
import org.apache.hugegraph.exception.HugeException;
import org.apache.hugegraph.masterelection.GlobalMasterInfo;
import org.apache.hugegraph.task.DistributedTaskScheduler;
import org.apache.hugegraph.task.TaskManager;
import org.apache.hugegraph.task.TaskScheduler;
import org.apache.hugegraph.testutil.Assert;
import org.apache.hugegraph.testutil.Whitebox;
import org.apache.hugegraph.unit.FakeObjects;
import org.junit.Test;
import org.mockito.AdditionalAnswers;
import org.mockito.Mockito;

public class HugeFactoryTest {

    @Test
    public void testCleanupContinuesAfterGraphFailure() {
        StandardHugeGraph failed = Mockito.mock(StandardHugeGraph.class);
        StandardHugeGraph cleaned = Mockito.mock(StandardHugeGraph.class);
        Mockito.doThrow(new HugeException("test"))
               .when(failed).closeCurrentThreadTransaction();

        Assert.assertThrows(HugeException.class, () -> {
            HugeFactory.closeCurrentThreadTransactions(
                    Arrays.asList(failed, cleaned));
        });

        Mockito.verify(failed).closeCurrentThreadTransaction();
        Mockito.verify(cleaned).closeCurrentThreadTransaction();
    }

    @Test
    public void testGraphCloseContinuesAfterReleasedWorkerOwnerFails() throws Exception {
        this.checkGraphCloseAfterOwnerFailure(false);
    }

    @Test
    public void testGraphCloseContinuesAfterWorkerOwnerFailsBeforeRelease() throws Exception {
        this.checkGraphCloseAfterOwnerFailure(true);
    }

    private void checkGraphCloseAfterOwnerFailure(boolean beforeRelease) throws Exception {
        RegisterUtil.registerBackends();
        HugeConfig config = FakeObjects.newConfig();
        config.setProperty("backend", "memory");
        config.setProperty("serializer", "text");
        config.setProperty("store", "graph_close_owner_failure_" + (beforeRelease ? "before" : "after"));
        HugeGraph graph = HugeFactory.open(config);
        TaskScheduler scheduler = graph.taskScheduler();
        AtomicReference<GraphTransaction> workerOwner = new AtomicReference<>();
        try {
            graph.initBackend();
            graph.serverStarted(GlobalMasterInfo.master("close-owner-test"));
            HugeGraphParams params = Whitebox.getInternalState(graph, "params");
            TaskManager manager = TaskManager.instance();
            ExecutorService workers = Whitebox.getInternalState(manager, "taskExecutor");
            ThreadLocal<?> owners = Whitebox.getInternalState(graph.tx(), "transactions");
            RuntimeException first = new IllegalStateException("released task DB owner failed");
            RuntimeException second = new IllegalArgumentException("released caller owner failed");
            AtomicInteger workerClosed = new AtomicInteger();
            AtomicInteger callerClosed = new AtomicInteger();
            AtomicInteger otherWorkerClosed = new AtomicInteger();
            BackendStoreProvider provider = Mockito.spy(graph.storeProvider());
            Whitebox.setInternalState(graph, "storeProvider", provider);

            scheduler.call(() -> {
                workerOwner.set(failOwnerClose(graph, params, first, workerClosed, beforeRelease));
                return null;
            });
            workers.submit(() -> {
                GraphTransaction realOwner = params.graphTransaction();
                GraphTransaction owner = Mockito.mock(GraphTransaction.class,
                                                      AdditionalAnswers.delegatesTo(realOwner));
                Mockito.doAnswer(invocation -> {
                    realOwner.close();
                    otherWorkerClosed.incrementAndGet();
                    return null;
                }).when(owner).close();
                Whitebox.setInternalState(owners.get(), "graphTx", owner);
            }).get(10L, TimeUnit.SECONDS);
            failOwnerClose(graph, params, second, callerClosed, false);

            Throwable failure = Assert.assertThrows(HugeException.class, graph::close);
            Assert.assertSame(first, HugeException.rootCause(failure));
            Assert.assertTrue(Arrays.asList(failure.getSuppressed()).contains(second));
            Assert.assertTrue(graph.closed());
            Assert.assertNull(manager.getScheduler(params));
            Assert.assertTrue((boolean) Whitebox.getInternalState(scheduler.serverManager(), "closed"));
            Assert.assertNull(scheduler.call(owners::get));
            Assert.assertNull(owners.get());
            Assert.assertEquals(1, workerClosed.get());
            Assert.assertEquals(1, callerClosed.get());
            Assert.assertEquals(1, otherWorkerClosed.get());
            Mockito.verify(provider).close();
        } finally {
            try {
                // Only the memory fixture owns this retained failed transaction.
                // Logical graph closure deliberately does not claim it was released.
                GraphTransaction retained = workerOwner.get();
                if (beforeRelease && retained != null) {
                    scheduler.call(() -> {
                        retained.close();
                        return null;
                    });
                }
                if (!graph.closed()) {
                    graph.close();
                }
            } finally {
                HugeFactory.remove(graph);
            }
        }
    }

    private static GraphTransaction failOwnerClose(HugeGraph graph, HugeGraphParams params,
                                                   RuntimeException failure, AtomicInteger closed,
                                                   boolean beforeRelease) {
        GraphTransaction realOwner = params.graphTransaction();
        GraphTransaction owner = Mockito.mock(GraphTransaction.class,
                                              AdditionalAnswers.delegatesTo(realOwner));
        Mockito.doAnswer(invocation -> {
            if (!beforeRelease) {
                realOwner.close();
            }
            closed.incrementAndGet();
            throw failure;
        }).when(owner).close();
        ThreadLocal<?> owners = Whitebox.getInternalState(graph.tx(), "transactions");
        Whitebox.setInternalState(owners.get(), "graphTx", owner);
        return realOwner;
    }

    @Test
    public void testDistributedCloseRetriesDrainAfterTimeout() throws Exception {
        HugeGraphParams params = Mockito.mock(HugeGraphParams.class);
        Mockito.when(params.graph()).thenReturn(Mockito.mock(HugeGraph.class));
        Mockito.when(params.configuration()).thenReturn(FakeObjects.newConfig());
        ScheduledThreadPoolExecutor cron = Mockito.mock(ScheduledThreadPoolExecutor.class);
        Mockito.doReturn(Mockito.mock(ScheduledFuture.class)).when(cron).scheduleWithFixedDelay(
                Mockito.any(Runnable.class), Mockito.anyLong(), Mockito.anyLong(), Mockito.eq(TimeUnit.SECONDS));
        Mockito.doReturn(CompletableFuture.completedFuture(null)).when(cron).submit(Mockito.any(Runnable.class));
        ExecutorService executor = Mockito.mock(ExecutorService.class);
        Mockito.when(executor.isShutdown()).thenReturn(true);
        DistributedTaskScheduler scheduler = Mockito.spy(new DistributedTaskScheduler(
                params, cron, executor, executor, executor, executor, executor));
        AtomicBoolean active = new AtomicBoolean(true);
        AtomicInteger drainAttempts = new AtomicInteger();
        Mockito.doAnswer(invocation -> {
            drainAttempts.incrementAndGet();
            if (active.get()) {
                throw new TimeoutException("Task still running");
            }
            return null;
        }).when(scheduler).waitUntilAllTasksCompleted(Mockito.anyLong());

        Assert.assertFalse(scheduler.close());
        Assert.assertFalse(scheduler.close());
        Assert.assertEquals(2, drainAttempts.get());
        Assert.assertFalse((boolean) Whitebox.getInternalState(scheduler.serverManager(), "closed"));
        active.set(false);
        Assert.assertTrue(scheduler.close());
        Assert.assertEquals(3, drainAttempts.get());
        Assert.assertTrue((boolean) Whitebox.getInternalState(scheduler.serverManager(), "closed"));
        Assert.assertTrue(scheduler.close());
        Assert.assertEquals(3, drainAttempts.get());
    }
}
