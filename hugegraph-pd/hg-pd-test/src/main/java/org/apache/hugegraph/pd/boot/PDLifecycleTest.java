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

package org.apache.hugegraph.pd.boot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.hugegraph.pd.meta.MetadataFactory;
import org.apache.hugegraph.pd.raft.RaftEngine;
import org.apache.hugegraph.pd.service.MetadataService;
import org.apache.hugegraph.pd.service.StartupMetadataFailure;
import org.apache.hugegraph.pd.store.HgKVStore;
import org.apache.hugegraph.pd.util.HgExecutorUtil;
import org.apache.hugegraph.pd.util.ShutdownUtil;
import org.apache.hugegraph.pd.util.grpc.GRpcServerConfig;
import org.apache.hugegraph.testutil.Whitebox;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.lognet.springboot.grpc.context.GRpcServerInitializedEvent;
import org.mockito.Mockito;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import io.grpc.Server;

public class PDLifecycleTest {

    private Object originalStore;
    private Object originalClosing;
    private Object originalRaft;
    private Object originalJobs;
    private ThreadPoolExecutor originalCallbacks;
    private Map<String, ThreadPoolExecutor> callbackPools;
    private HgKVStore store;
    private RaftEngine raft;
    private ThreadPoolExecutor jobs;
    private final List<CountDownLatch> releases = new ArrayList<>();
    private final List<Thread> threads = new ArrayList<>();
    private final List<ExecutorService> executors = new ArrayList<>();

    @Before
    public void setUp() {
        originalStore = Whitebox.getInternalState(MetadataFactory.class, "store");
        originalClosing = Whitebox.getInternalState(MetadataFactory.class, "closing");
        originalRaft = Whitebox.getInternalState(RaftEngine.class, "instance");
        originalJobs = Whitebox.getInternalState(MetadataService.class, "uninterruptibleJobs");
        callbackPools = Whitebox.getInternalState(HgExecutorUtil.class, "EXECUTOR_MAP");
        originalCallbacks = callbackPools.remove(GRpcServerConfig.EXECUTOR_NAME);
        store = Mockito.mock(HgKVStore.class);
        raft = Mockito.mock(RaftEngine.class);
        jobs = (ThreadPoolExecutor) Executors.newFixedThreadPool(1);
        executors.add(jobs);
        Whitebox.setInternalState(MetadataFactory.class, "store", store);
        Whitebox.setInternalState(MetadataFactory.class, "closing", false);
        Whitebox.setInternalState(RaftEngine.class, "instance", raft);
        Whitebox.setInternalState(MetadataService.class, "uninterruptibleJobs", jobs);
    }

    @After
    public void tearDown() throws Exception {
        releases.forEach(CountDownLatch::countDown);
        for (Thread thread : threads) {
            thread.join(5000);
            Assert.assertFalse("shutdown worker leaked", thread.isAlive());
        }
        for (ExecutorService executor : executors) {
            executor.shutdownNow();
            Assert.assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
        callbackPools.remove(GRpcServerConfig.EXECUTOR_NAME);
        if (originalCallbacks != null) {
            callbackPools.put(GRpcServerConfig.EXECUTOR_NAME, originalCallbacks);
        }
        Whitebox.setInternalState(MetadataFactory.class, "store", originalStore);
        Whitebox.setInternalState(MetadataFactory.class, "closing", originalClosing);
        Whitebox.setInternalState(RaftEngine.class, "instance", originalRaft);
        Whitebox.setInternalState(MetadataService.class, "uninterruptibleJobs", originalJobs);
    }

    @Test
    public void testProducerRaftAndSnapshotDrainPrecedeClose() throws Exception {
        CountDownLatch producerEntered = new CountDownLatch(1);
        CountDownLatch producerRelease = release();
        CountDownLatch raftStopped = new CountDownLatch(1);
        CountDownLatch snapshotEntered = new CountDownLatch(1);
        CountDownLatch snapshotRelease = release();
        jobs.submit(() -> {
            snapshotEntered.countDown();
            await(snapshotRelease);
        });
        Assert.assertTrue(snapshotEntered.await(5, TimeUnit.SECONDS));
        Mockito.doAnswer(call -> {
            raftStopped.countDown();
            return null;
        }).when(raft).shutDown();
        PDLifecycle owner = new PDLifecycle(new PDRequestGate());
        owner.registerProducer(() -> {
            producerEntered.countDown();
            await(producerRelease);
        });
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread stop = stop(owner, error);
        Assert.assertTrue(producerEntered.await(5, TimeUnit.SECONDS));
        Mockito.verifyNoInteractions(raft, store);
        producerRelease.countDown();
        Assert.assertTrue(raftStopped.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(stop.isAlive());
        Mockito.verifyNoInteractions(store);
        snapshotRelease.countDown();
        stop.join(5000);
        Assert.assertNull(error.get());
        Assert.assertFalse(stop.isAlive());
        Mockito.verify(store, Mockito.times(1)).close();
        owner.destroy();
        owner.stop();
        Mockito.verify(store, Mockito.times(1)).close();
        Assert.assertThrows(IllegalStateException.class, () -> owner.registerProducer(() -> {}));
        Assert.assertThrows(IllegalStateException.class, () -> MetadataFactory.getStore(null));
    }

    @Test
    public void testFailedStartupDestroyBeforeStartClosesOwnedStore() {
        PDLifecycle owner = new PDLifecycle(new PDRequestGate());
        AtomicBoolean stopped = new AtomicBoolean();
        owner.registerProducer(() -> stopped.set(true));
        Assert.assertFalse(owner.isRunning());
        owner.destroy();
        Assert.assertTrue(stopped.get());
        Mockito.verify(raft).shutDown();
        Mockito.verify(store).close();
    }

    @Test
    public void testRealRefreshFailureDestroysOwnerBeforeAnyMetadataInitializer() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        // Intentionally register the resource-acquiring bean first.
        context.register(StartupMetadataFailure.class, PDLifecycleConfiguration.class,
                         PDLifecycle.class, PDRequestGate.class);
        try {
            Assert.assertThrows(org.springframework.beans.BeansException.class, context::refresh);
            Mockito.verify(raft).shutDown();
            Mockito.verify(store).close();
        } finally {
            context.close();
        }
    }

    @Test
    public void testFailedProducerDrainLeavesDatabaseOpenForRetry() {
        PDLifecycle owner = new PDLifecycle(new PDRequestGate());
        AtomicBoolean fail = new AtomicBoolean(true);
        owner.registerProducer(() -> {
            if (fail.get()) {
                throw new IllegalStateException("fixture drain failed");
            }
        });
        Assert.assertThrows(IllegalStateException.class, owner::stop);
        Mockito.verifyNoInteractions(raft, store);
        fail.set(false);
        owner.stop();
        Mockito.verify(store).close();
    }

    @Test
    public void testTransportTimeoutDoesNotCloseDatabase() throws Exception {
        Server server = Mockito.mock(Server.class);
        Mockito.when(server.awaitTermination(ShutdownUtil.DRAIN_SECONDS, TimeUnit.SECONDS))
               .thenReturn(false);
        PDLifecycle owner = new PDLifecycle(new PDRequestGate());
        owner.onApplicationEvent(new GRpcServerInitializedEvent(new StaticApplicationContext(), server));
        Assert.assertThrows(IllegalStateException.class, owner::stop);
        Mockito.verify(server).shutdownNow();
        Mockito.verifyNoInteractions(raft, store);
    }

    @Test
    public void testTransportTerminationDoesNotWaiveCallbackDrain() throws Exception {
        ThreadPoolExecutor callbacks = HgExecutorUtil.createExecutor(
                GRpcServerConfig.EXECUTOR_NAME, 1, 1, 10);
        executors.add(callbacks);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = release();
        callbacks.submit(() -> {
            entered.countDown();
            await(release);
        });
        Assert.assertTrue(entered.await(5, TimeUnit.SECONDS));
        CountDownLatch transportStopped = new CountDownLatch(1);
        Server server = Mockito.mock(Server.class);
        Mockito.when(server.awaitTermination(ShutdownUtil.DRAIN_SECONDS, TimeUnit.SECONDS))
               .thenAnswer(call -> {
                   transportStopped.countDown();
                   return true;
               });
        PDLifecycle owner = new PDLifecycle(new PDRequestGate());
        owner.onApplicationEvent(new GRpcServerInitializedEvent(new StaticApplicationContext(), server));
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread stop = stop(owner, error);
        Assert.assertTrue(transportStopped.await(5, TimeUnit.SECONDS));
        Mockito.verifyNoInteractions(raft, store);
        release.countDown();
        stop.join(5000);
        Assert.assertNull(error.get());
        Assert.assertTrue(callbacks.isTerminated());
        Mockito.verify(store).close();
    }

    @Test
    public void testInterruptedShutdownDoesNotCloseDatabase() throws Exception {
        PDLifecycle owner = new PDLifecycle(new PDRequestGate());
        AtomicBoolean interruptPreserved = new AtomicBoolean();
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            Thread.currentThread().interrupt();
            try {
                owner.stop();
            } catch (Throwable t) {
                error.set(t);
                interruptPreserved.set(Thread.currentThread().isInterrupted());
            }
        });
        thread.start();
        thread.join(5000);
        Assert.assertTrue(error.get() instanceof IllegalStateException);
        Assert.assertTrue(interruptPreserved.get());
        Mockito.verifyNoInteractions(raft, store);
    }

    private CountDownLatch release() {
        CountDownLatch latch = new CountDownLatch(1);
        releases.add(latch);
        return latch;
    }

    private Thread stop(PDLifecycle owner, AtomicReference<Throwable> error) {
        Thread thread = new Thread(() -> {
            try {
                owner.stop();
            } catch (Throwable t) {
                error.set(t);
            }
        });
        threads.add(thread);
        thread.start();
        return thread;
    }

    private static void await(CountDownLatch release) {
        try {
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("fixture release timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
