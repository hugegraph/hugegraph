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

package org.apache.hugegraph.store.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.lang3.tuple.Triple;
import org.apache.hugegraph.rocksdb.access.RocksDBSession;
import org.apache.hugegraph.rocksdb.access.ScanIterator;
import org.apache.hugegraph.rocksdb.access.SessionOperator;
import org.apache.hugegraph.store.business.BusinessHandlerImpl;
import org.apache.hugegraph.store.business.InnerKeyCreator;
import org.apache.hugegraph.store.constant.HugeServerTables;
import org.apache.hugegraph.store.node.AppConfig;
import org.apache.hugegraph.store.node.task.TTLCleaner;
import org.apache.hugegraph.store.node.task.ttl.TaskInfo;
import org.junit.Test;

public class TTLCleanerCleanupTest {

    @Test(timeout = 5000)
    public void testCompletionWaitsForNativeClose() throws Exception {
        TTLCleaner cleaner = newCleaner();
        CountDownLatch completed = new CountDownLatch(1);
        CountDownLatch closing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ScanIterator scan = mock(ScanIterator.class);
        doAnswer(invocation -> {
            closing.countDown();
            release.await();
            return null;
        }).when(scan).close();
        FutureTask<Void> task = new FutureTask<>(task(cleaner, scan, completed), null);
        Thread worker = new Thread(task, "test-ttl-close");
        worker.setDaemon(true);
        try {
            worker.start();
            assertTrue(closing.await(1, TimeUnit.SECONDS));
            assertEquals("TTL must not compact before native close finishes", 1L,
                         completed.getCount());
            release.countDown();
            task.get(1, TimeUnit.SECONDS);
            assertEquals(0L, completed.getCount());
            cleaner.awaitCleanup();
        } finally {
            release.countDown();
            cleaner.getScheduler().shutdownNow();
        }
    }

    @Test(timeout = 5000)
    public void testFailedCloseStaysBlockedAfterInterruption() throws Exception {
        TTLCleaner cleaner = newCleaner();
        RuntimeException failure = new IllegalStateException("native release failed");
        ScanIterator scan = mock(ScanIterator.class);
        doAnswer(invocation -> { throw failure; }).when(scan).close();
        CountDownLatch completed = new CountDownLatch(1);
        CountDownLatch awaiting = new CountDownLatch(1);
        FutureTask<Boolean> cleanup = new FutureTask<>(() -> {
            awaiting.countDown();
            cleaner.awaitCleanup();
            return Thread.currentThread().isInterrupted();
        });
        Thread shutdown = new Thread(cleanup, "test-ttl-cleanup-failure");
        shutdown.setDaemon(true);
        Field field = TTLCleaner.class.getDeclaredField("cleanupFailure");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        AtomicReference<Throwable> retained = (AtomicReference<Throwable>) field.get(cleaner);
        try {
            task(cleaner, scan, completed).run();
            assertEquals(0L, completed.getCount());
            assertSame(failure, retained.get());
            shutdown.start();
            assertTrue(awaiting.await(1, TimeUnit.SECONDS));
            shutdown.interrupt();
            assertFalse("interruption cannot authorize native storage destruction",
                        cleanup.isDone());
            assertSame(failure, retained.get());
        } finally {
            // Production never clears this failure. Release only this test's daemon waiter.
            synchronized (retained) {
                retained.set(null);
                retained.notifyAll();
            }
            shutdown.interrupt();
            assertTrue(cleanup.get(1, TimeUnit.SECONDS));
            cleaner.getScheduler().shutdownNow();
        }
    }

    private static TTLCleaner newCleaner() {
        AppConfig config = mock(AppConfig.class);
        AppConfig.JobConfig job = mock(AppConfig.JobConfig.class);
        when(config.getJobConfig()).thenReturn(job);
        when(job.getStartTime()).thenReturn(19);
        return new TTLCleaner(config);
    }

    private static Runnable task(TTLCleaner cleaner, ScanIterator scan,
                                 CountDownLatch completed) throws Exception {
        BusinessHandlerImpl handler = mock(BusinessHandlerImpl.class);
        RocksDBSession session = mock(RocksDBSession.class);
        SessionOperator operator = mock(SessionOperator.class);
        when(handler.getSession(1)).thenReturn(session);
        when(handler.getKeyCreator()).thenReturn(mock(InnerKeyCreator.class));
        when(session.sessionOp()).thenReturn(operator);
        when(operator.scan(anyString(), any(), any(), anyInt())).thenReturn(scan);
        TaskInfo info = mock(TaskInfo.class);
        when(info.getTableCounter()).thenReturn(new ConcurrentHashMap<>(
                Collections.singletonMap(HugeServerTables.VERTEX_TABLE, new AtomicLong())));
        Method method = TTLCleaner.class.getDeclaredMethod("getTask", BusinessHandlerImpl.class,
                CountDownLatch.class, Triple.class, Map.class, Map.class);
        method.setAccessible(true);
        return (Runnable) method.invoke(cleaner, handler, completed,
                Triple.of(1, "graph", HugeServerTables.VERTEX_TABLE),
                Collections.singletonMap("graph", info),
                Collections.singletonMap(1, new AtomicLong()));
    }
}
