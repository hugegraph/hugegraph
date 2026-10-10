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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.hugegraph.config.HugeConfig;
import org.apache.hugegraph.dist.RegisterUtil;
import org.apache.hugegraph.id.IdGenerator;
import org.apache.hugegraph.job.EphemeralJob;
import org.apache.hugegraph.job.SysJob;
import org.apache.hugegraph.masterelection.GlobalMasterInfo;
import org.apache.hugegraph.task.DistributedTaskScheduler;
import org.apache.hugegraph.task.HugeTask;
import org.apache.hugegraph.task.TaskAndResultSchedulerTest.EmptyCallable;
import org.apache.hugegraph.task.TaskStatus;
import org.apache.hugegraph.task.TaskCallable;
import org.apache.hugegraph.meta.MetaManager;
import org.apache.hugegraph.meta.managers.TaskMetaManager;
import org.apache.hugegraph.meta.lock.LockResult;
import org.mockito.Mockito;
import org.apache.hugegraph.testutil.Assert;
import org.apache.hugegraph.testutil.Whitebox;
import org.apache.hugegraph.unit.FakeObjects;
import org.junit.Test;

public class DistributedTaskAdmissionTest {

    @Test
    public void testQueuedEphemeralAdmissionDrainsBeforeClose() throws Exception {
        this.checkQueuedAdmission(true);
    }

    @Test
    public void testQueuedPersistentAdmissionDrainsBeforeClose() throws Exception {
        this.checkQueuedAdmission(false);
    }

    private void checkQueuedAdmission(boolean ephemeral) throws Exception {
        try (Fixture fixture = new Fixture("queued_" + ephemeral)) {
            CountDownLatch occupied = fixture.worker.reached;
            CountDownLatch release = fixture.worker.release;
            fixture.worker.armed.set(true);
            ExecutorService closing = Executors.newSingleThreadExecutor();
            try {
                AtomicBoolean ran = new AtomicBoolean();
                HugeTask<?> admitted = task(ephemeral, ran, 9999910L);
                fixture.scheduler.schedule(admitted);
                Assert.assertTrue(occupied.await(10L, TimeUnit.SECONDS));
                Assert.assertEquals(1, fixture.scheduler.pendingTasks());
                Future<Boolean> close = closing.submit(fixture.scheduler::close);
                AtomicBoolean closed = Whitebox.getInternalState(fixture.scheduler, "closed");
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10L);
                while (!closed.get() && System.nanoTime() < deadline) {
                    Thread.yield();
                }
                Assert.assertTrue(closed.get());
                Assert.assertEquals(0, fixture.scheduler.lateSaved.get());
                for (boolean lateEphemeral : new boolean[]{true, false}) {
                    HugeTask<?> late = task(lateEphemeral, ran, 9999911L);
                    Assert.assertThrows(IllegalStateException.class, () -> fixture.scheduler.schedule(late));
                    Assert.assertEquals(TaskStatus.NEW, late.status());
                }
                Assert.assertEquals(0, fixture.scheduler.lateSaved.get());
                Assert.assertFalse(close.isDone());
                // Wait for actual cancellation, not just the earlier admission transition.
                deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10L);
                while (!admitted.isCancelled() && System.nanoTime() < deadline) {
                    Thread.yield();
                }
                Assert.assertTrue(admitted.isCancelled());
                release.countDown();
                Assert.assertTrue(close.get(10L, TimeUnit.SECONDS));
                Assert.assertFalse(ran.get());
                Assert.assertTrue(admitted.isCancelled());
                Assert.assertEquals(0, fixture.scheduler.pendingTasks());
                ThreadLocal<?> owners = Whitebox.getInternalState(fixture.graph.tx(), "transactions");
                Assert.assertNull(fixture.worker.submit(owners::get).get(10L, TimeUnit.SECONDS));
                Assert.assertNull(fixture.worker.submit(org.apache.hugegraph.task.TaskManager::getContext)
                                               .get(10L, TimeUnit.SECONDS));
                Assert.assertTrue(fixture.scheduler.close());
            } finally {
                release.countDown();
                closing.shutdownNow();
                Assert.assertTrue(closing.awaitTermination(10L, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    public void testReturnedFutureQueuedCancellationReleasesAdmission() throws Exception {
        try (Fixture fixture = new Fixture("future_queued_cancel")) {
            fixture.worker.armed.set(true);
            AtomicBoolean ran = new AtomicBoolean();
            HugeTask<?> task = task(true, ran, 9999921L);
            Future<?> returned = fixture.scheduler.schedule(task);
            try {
                Assert.assertTrue(fixture.worker.reached.await(10L, TimeUnit.SECONDS));
                Assert.assertEquals(1, fixture.scheduler.pendingTasks());
                fixture.graph.schema().vertexLabel("caller").useCustomizeStringId().create();
                fixture.graph.addVertex(org.apache.tinkerpop.gremlin.structure.T.id, "uncommitted",
                                        org.apache.tinkerpop.gremlin.structure.T.label, "caller");
                Assert.assertTrue(fixture.graph.tx().isOpen());
                Assert.assertTrue(returned.cancel(true));
                Assert.assertTrue(fixture.graph.tx().isOpen());
                Assert.assertTrue(fixture.graph.vertices("uncommitted").hasNext());
                fixture.graph.tx().rollback();
                Assert.assertFalse(task.isCancelled());
                Assert.assertEquals(TaskStatus.NEW, task.status());
                Assert.assertEquals(0, fixture.scheduler.pendingTasks());
                Assert.assertTrue(fixture.scheduler.close());
                Assert.assertFalse(ran.get());
            } finally {
                fixture.worker.release.countDown();
            }
            fixture.worker.submit(() -> null).get(10L, TimeUnit.SECONDS);
            Assert.assertFalse(ran.get());
            Assert.assertEquals(0, fixture.scheduler.pendingTasks());
        }
    }

    @Test
    public void testQueuedClosePreservesCallerTransactionAndCancellationCallbacks() throws Exception {
        this.checkQueuedOwnedCancellation(true);
    }

    @Test
    public void testQueuedCancelPreservesCallerTransactionAndCancellationCallbacks() throws Exception {
        this.checkQueuedOwnedCancellation(false);
    }

    private void checkQueuedOwnedCancellation(boolean close) throws Exception {
        try (Fixture fixture = new Fixture("owned_cancel_" + close)) {
            fixture.worker.armed.set(true);
            CancellationJob callable = new CancellationJob();
            HugeTask<?> task = new HugeTask<>(IdGenerator.of(9999930L), null, callable);
            task.type("test");
            task.name("owned-cancel");
            fixture.scheduler.schedule(task);
            Assert.assertTrue(fixture.worker.reached.await(10L, TimeUnit.SECONDS));
            AtomicBoolean nestedRan = new AtomicBoolean();
            HugeTask<?> nested = task(true, nestedRan, 9999931L);
            fixture.scheduler.schedule(nested);
            // Reentrant cancellation on the same scheduler worker must not wait on itself.
            callable.onCancelled = () -> fixture.scheduler.cancel(nested);
            fixture.graph.schema().vertexLabel("caller").useCustomizeStringId().create();
            fixture.graph.addVertex(org.apache.tinkerpop.gremlin.structure.T.id, "uncommitted",
                                    org.apache.tinkerpop.gremlin.structure.T.label, "caller");
            Assert.assertTrue(fixture.graph.tx().isOpen());
            ExecutorService release = Executors.newSingleThreadExecutor();
            try {
                Future<?> gate = release.submit(() -> {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10L);
                    while (callable.cancelled.get() == 0 && System.nanoTime() < deadline) {
                        Thread.yield();
                    }
                    fixture.worker.release.countDown();
                });
                if (close) {
                    Assert.assertTrue(fixture.scheduler.close());
                } else {
                    fixture.scheduler.cancel(task);
                }
                Assert.assertTrue(fixture.graph.tx().isOpen());
                Assert.assertTrue(fixture.graph.vertices("uncommitted").hasNext());
                fixture.graph.tx().rollback();
                Assert.assertEquals(1, callable.done.get());
                Assert.assertEquals(1, callable.cancelled.get());
                Assert.assertNotEquals(Thread.currentThread(), callable.cancellationThread);
                Assert.assertTrue(task.isCancelled());
                Assert.assertTrue(nested.isCancelled());
                Assert.assertFalse(callable.ran.get());
                Assert.assertFalse(nestedRan.get());
                // Query the real persisted SysJob status even after scheduler.close cleanup.
                Assert.assertEquals(TaskStatus.CANCELLED, fixture.scheduler.task(task.id(), false).status());
                fixture.scheduler.close();
                gate.get(10L, TimeUnit.SECONDS);
                Assert.assertEquals(0, fixture.scheduler.pendingTasks());
                ThreadLocal<?> owners = Whitebox.getInternalState(fixture.graph.tx(), "transactions");
                Assert.assertNull(fixture.cron.submit(owners::get).get(10L, TimeUnit.SECONDS));
                Assert.assertNull(fixture.cron.submit(org.apache.hugegraph.task.TaskManager::getContext)
                                             .get(10L, TimeUnit.SECONDS));
            } finally {
                fixture.worker.release.countDown();
                release.shutdownNow();
                Assert.assertTrue(release.awaitTermination(10L, TimeUnit.SECONDS));
            }
        }
    }

    public static class CancellationJob extends SysJob<Object> {

        private final AtomicInteger done = new AtomicInteger();
        private final AtomicInteger cancelled = new AtomicInteger();
        private final AtomicBoolean ran = new AtomicBoolean();
        private volatile Thread cancellationThread;
        private Runnable onCancelled = () -> { };

        @Override
        public String type() {
            return "test";
        }

        @Override
        public Object execute() {
            this.ran.set(true);
            return null;
        }

        @Override
        protected void done() {
            this.done.incrementAndGet();
            super.done();
        }

        @Override
        protected void cancelled() {
            this.cancellationThread = Thread.currentThread();
            this.cancelled.incrementAndGet();
            this.onCancelled.run();
            super.cancelled();
        }
    }

    @Test
    public void testReturnedFutureActiveCancellationRetainsOwnerUntilCleanup() throws Exception {
        try (Fixture fixture = new Fixture("future_active_cancel")) {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch interrupted = new CountDownLatch(1);
            HugeTask<?> task = new HugeTask<>(IdGenerator.of(-9999922L), null, new EphemeralJob<Object>() {
                @Override
                public String type() {
                    return "test";
                }

                @Override
                public Object execute() {
                    this.graph().vertices().hasNext();
                    started.countDown();
                    while (release.getCount() != 0L) {
                        try {
                            Assert.assertTrue(release.await(10L, TimeUnit.SECONDS));
                        } catch (InterruptedException e) {
                            interrupted.countDown();
                        }
                    }
                    return null;
                }
            });
            task.type("test");
            task.name("active-future-cancellation");
            Future<?> returned = fixture.scheduler.schedule(task);
            try {
                Assert.assertTrue(started.await(10L, TimeUnit.SECONDS));
                Assert.assertTrue(returned.cancel(true));
                Assert.assertTrue(interrupted.await(10L, TimeUnit.SECONDS));
                Assert.assertEquals(1, fixture.scheduler.pendingTasks());
            } finally {
                release.countDown();
            }
            fixture.worker.submit(() -> null).get(10L, TimeUnit.SECONDS);
            Assert.assertEquals(0, fixture.scheduler.pendingTasks());
            ThreadLocal<?> owners = Whitebox.getInternalState(fixture.graph.tx(), "transactions");
            Assert.assertNull(fixture.worker.submit(owners::get).get(10L, TimeUnit.SECONDS));
            Assert.assertNull(fixture.worker.submit(org.apache.hugegraph.task.TaskManager::getContext)
                                           .get(10L, TimeUnit.SECONDS));
            Assert.assertTrue(fixture.scheduler.close());
        }
    }

    @Test
    public void testLostLeaseKeepsLocalExecutionReservedUntilOwnerCleanup() throws Exception {
        MetaManager meta = MetaManager.instance();
        TaskMetaManager original = Whitebox.getInternalState(meta, "taskMetaManager");
        TaskMetaManager locks = Mockito.mock(TaskMetaManager.class);
        LockResult acquired = new LockResult();
        acquired.lockSuccess(true);
        Mockito.when(locks.tryLockTask(Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
               .thenReturn(acquired);
        Mockito.when(locks.isLockedTask(Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
               .thenReturn(false);
        Whitebox.setInternalState(meta, "taskMetaManager", locks);
        try (Fixture fixture = new Fixture("lost_lease")) {
            LeaseCallable callable = new LeaseCallable();
            callable.scheduler = fixture.scheduler;
            HugeTask<Object> task = new HugeTask<>(IdGenerator.of(9999923L), null, callable);
            task.type("test");
            task.name("lost-lease-owner");
            fixture.scheduler.schedule(task);
            ExecutorService closing = Executors.newSingleThreadExecutor();
            try {
                Assert.assertTrue(callable.started.await(10L, TimeUnit.SECONDS));
                Assert.assertEquals(TaskStatus.RUNNING, fixture.scheduler.task(task.id(), false).status());
                fixture.scheduler.cronSchedule();
                Assert.assertTrue(fixture.scheduler.leaseRecovered.get());
                Assert.assertEquals(TaskStatus.FAILED, fixture.scheduler.task(task.id(), false).status());
                Assert.assertEquals(1, fixture.scheduler.pendingTasks());
                Future<Boolean> close = closing.submit(fixture.scheduler::close);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10L);
                while (!task.isCancelled() && System.nanoTime() < deadline) {
                    Thread.yield();
                }
                Assert.assertTrue(task.isCancelled());
                Assert.assertFalse(close.isDone());
                Assert.assertEquals(1, fixture.scheduler.pendingTasks());
                callable.release.countDown();
                Assert.assertTrue(close.get(10L, TimeUnit.SECONDS));
                Assert.assertEquals(0, fixture.scheduler.pendingTasks());
                ThreadLocal<?> owners = Whitebox.getInternalState(fixture.graph.tx(), "transactions");
                Assert.assertNull(fixture.worker.submit(owners::get).get(10L, TimeUnit.SECONDS));
            } finally {
                callable.release.countDown();
                closing.shutdownNow();
                Assert.assertTrue(closing.awaitTermination(10L, TimeUnit.SECONDS));
            }
        } finally {
            Whitebox.setInternalState(meta, "taskMetaManager", original);
        }
    }

    public static class LeaseCallable extends TaskCallable<Object> {

        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private CountingScheduler scheduler;

        @Override
        public Object call() {
            this.graph().vertices().hasNext();
            this.scheduler.save(this.task());
            this.started.countDown();
            while (this.release.getCount() != 0L) {
                try {
                    Assert.assertTrue(this.release.await(10L, TimeUnit.SECONDS));
                } catch (InterruptedException ignored) {
                    // Deliberately retain the real owner until the test releases the callable.
                }
            }
            return null;
        }
    }

    @Test
    public void testRejectedSubmissionRollsBackDispatchReservation() throws Exception {
        for (boolean ephemeral : new boolean[]{true, false}) {
            try (Fixture fixture = new Fixture("rejected_" + ephemeral)) {
                fixture.worker.shutdown();
                AtomicBoolean ran = new AtomicBoolean();
                Assert.assertThrows(RejectedExecutionException.class,
                                    () -> fixture.scheduler.schedule(task(ephemeral, ran, 9999920L)));
                Assert.assertEquals(0, fixture.scheduler.pendingTasks());
                Assert.assertFalse(ran.get());
                Assert.assertTrue(fixture.scheduler.close());
            }
        }
    }

    private static HugeTask<?> task(boolean ephemeral, AtomicBoolean ran, long id) {
        HugeTask<?> task;
        if (ephemeral) {
            task = new HugeTask<>(IdGenerator.of(-id), null, new EphemeralJob<Object>() {
                @Override
                public String type() {
                    return "test";
                }

                @Override
                public Object execute() {
                    ran.set(true);
                    return null;
                }
            });
        } else {
            task = new HugeTask<>(IdGenerator.of(id), null, new EmptyCallable());
        }
        task.type("test");
        task.name("admission-test");
        return task;
    }

    private static class Fixture implements AutoCloseable {

        private final HugeGraph graph;
        private final ScheduledThreadPoolExecutor cron = new ScheduledThreadPoolExecutor(1);
        private final ExecutorService database = Executors.newSingleThreadExecutor();
        private final GatedExecutor worker = new GatedExecutor();
        private final CountingScheduler scheduler;

        Fixture(String suffix) {
            RegisterUtil.registerBackends();
            HugeConfig config = FakeObjects.newConfig();
            config.setProperty("backend", "memory");
            config.setProperty("serializer", "text");
            config.setProperty("store", "distributed_admission_" + suffix);
            config.setProperty("task.schedule_period", 600L);
            config.setProperty("task.retry", 0);
            this.graph = HugeFactory.open(config);
            this.graph.initBackend();
            this.graph.serverStarted(GlobalMasterInfo.master("distributed-admission-test"));
            HugeGraphParams params = Whitebox.getInternalState(this.graph, "params");
            this.scheduler = new CountingScheduler(params, this.cron, this.database, this.worker);
            this.scheduler.init();
        }

        @Override
        public void close() throws Exception {
            try {
                this.worker.release.countDown();
                this.scheduler.close();
            } finally {
                for (ExecutorService executor : new ExecutorService[]{this.worker, this.database, this.cron}) {
                    executor.shutdownNow();
                    Assert.assertTrue(executor.awaitTermination(10L, TimeUnit.SECONDS));
                }
                try {
                    this.graph.close();
                } finally {
                    HugeFactory.remove(this.graph);
                }
            }
        }
    }

    private static class GatedExecutor extends ThreadPoolExecutor {

        private final AtomicBoolean armed = new AtomicBoolean();
        private final CountDownLatch reached = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        GatedExecutor() {
            super(1, 1, 0L, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        }

        @Override
        protected void beforeExecute(Thread thread, Runnable work) {
            if (this.armed.compareAndSet(true, false)) {
                this.reached.countDown();
                try {
                    Assert.assertTrue(this.release.await(10L, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
        }
    }

    private static class CountingScheduler extends DistributedTaskScheduler {

        private final AtomicInteger lateSaved = new AtomicInteger();
        private final AtomicBoolean leaseRecovered = new AtomicBoolean();

        CountingScheduler(HugeGraphParams graph, ScheduledThreadPoolExecutor cron,
                          ExecutorService database, ExecutorService worker) {
            super(graph, cron, database, worker, worker, worker, worker);
        }

        @Override
        protected boolean updateStatusWithLock(org.apache.hugegraph.id.Id id,
                                               TaskStatus previous, TaskStatus status) {
            boolean updated = super.updateStatusWithLock(id, previous, status);
            if (updated && previous == TaskStatus.RUNNING && status == TaskStatus.FAILED) {
                this.leaseRecovered.set(true);
            }
            return updated;
        }

        @Override
        public <V> void save(HugeTask<V> task) {
            if (Math.abs(task.id().asLong()) == 9999911L) {
                this.lateSaved.incrementAndGet();
            }
            super.save(task);
        }
    }
}
