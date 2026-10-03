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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.hugegraph.pd.common.KVPair;
import org.apache.hugegraph.rocksdb.access.RocksDBSession;
import org.apache.hugegraph.rocksdb.access.ScanIterator;
import org.apache.hugegraph.store.grpc.common.ScanMethod;
import org.apache.hugegraph.store.grpc.common.Header;
import org.apache.hugegraph.store.grpc.common.ScanOrderType;
import org.apache.hugegraph.store.grpc.stream.KvPageRes;
import org.apache.hugegraph.store.grpc.stream.KvStream;
import org.apache.hugegraph.store.grpc.stream.ScanStreamReq;
import org.apache.hugegraph.store.grpc.stream.ScanStreamBatchReq;
import org.apache.hugegraph.store.grpc.stream.ScanQueryRequest;
import org.apache.hugegraph.store.node.AppConfig;
import org.apache.hugegraph.store.node.grpc.HgStoreStreamImpl;
import org.apache.hugegraph.store.node.grpc.HgStoreWrapperEx;
import org.apache.hugegraph.store.node.grpc.ScanBatchResponse;
import org.apache.hugegraph.store.node.grpc.ParallelScanIterator;
import org.apache.hugegraph.store.node.grpc.QueryCondition;
import org.apache.hugegraph.store.node.grpc.ScanOneShotResponse;
import org.apache.hugegraph.store.node.grpc.ScanBatchOneShotResponse;
import org.apache.hugegraph.store.node.grpc.ScanBatchResponse3;
import org.apache.hugegraph.store.node.grpc.ScanStreamResponse;
import org.apache.hugegraph.store.node.util.HgChannel;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;

public class ScanShutdownTest {

    @Test
    public void testClosingPreventsLazyExecutorAndStateCreation() {
        HgStoreStreamImpl service = new HgStoreStreamImpl();
        service.stopAcceptingScans();
        assertUnavailable(service::getExecutor);
        assertUnavailable(service::getState);
        assertUnavailable(() -> service.scan(mock(StreamObserver.class)));
        service.shutdownScans();
        assertNull(service.getRealExecutor());
    }

    @Test(timeout = 5000)
    public void testQueuedStreamCancellationDrainsWithoutOpeningIterator() throws Exception {
        ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch occupied = new CountDownLatch(1);
        executor.execute(() -> {
            occupied.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        HgStoreWrapperEx wrapper = mock(HgStoreWrapperEx.class);
        AppConfig config = mock(AppConfig.class);
        when(config.getServerWaitTime()).thenReturn(60);
        StreamObserver<KvPageRes> output = mock(StreamObserver.class);
        ScanStreamResponse response = ScanStreamResponse.of(output, wrapper, executor, config);
        FutureTask<Void> request = new FutureTask<>(() -> {
            response.onNext(ScanStreamReq.newBuilder().setMethod(ScanMethod.ALL)
                                         .setPageSize(1).setLimit(10).build());
            return null;
        });
        Thread caller = new Thread(request, "scan-shutdown-test");
        try {
            assertTrue(occupied.await(1, TimeUnit.SECONDS));
            caller.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (executor.getQueue().isEmpty() && System.nanoTime() < deadline) {
                Thread.yield();
            }
            assertEquals(1, executor.getQueue().size());
            response.onError(Status.CANCELLED.asRuntimeException());
            executor.shutdown();
            release.countDown();
            request.get(1, TimeUnit.SECONDS);
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            verifyNoInteractions(wrapper);
            assertEquals(2L, executor.getCompletedTaskCount());
        } finally {
            response.onCompleted();
            release.countDown();
            executor.shutdownNow();
            caller.join(1000);
        }
    }

    @Test(timeout = 5000)
    public void testOneShotObservesContextCancellationAndClosesIterator() {
        HgStoreWrapperEx wrapper = mock(HgStoreWrapperEx.class);
        ScanIterator iterator = mock(ScanIterator.class);
        StreamObserver<KvPageRes> output = mock(StreamObserver.class);
        Context.CancellableContext context = Context.current().withCancellation();
        when(wrapper.scanAll(anyString(), anyString(), any(byte[].class))).thenReturn(iterator);
        when(iterator.hasNext()).thenAnswer(invocation -> {
            context.cancel(null);
            return true;
        });
        when(iterator.next()).thenReturn(RocksDBSession.BackendColumn.of(new byte[4], new byte[0]));
        when(iterator.position()).thenReturn(new byte[4]);
        try {
            context.run(() -> ScanOneShotResponse.scanOneShot(
                    ScanStreamReq.newBuilder().setMethod(ScanMethod.ALL).setLimit(100).build(),
                    output, wrapper));
            verify(iterator).close();
            verifyNoInteractions(output);
        } finally {
            context.cancel(null);
        }
    }

    @Test
    public void testPausedBatchClosesIteratorAndRejectsLaterQuery() throws Exception {
        ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(1);
        HgStoreWrapperEx wrapper = mock(HgStoreWrapperEx.class);
        ScanIterator iterator = mock(ScanIterator.class);
        StreamObserver<KvStream> output = mock(StreamObserver.class);
        ScanBatchResponse response = new ScanBatchResponse(output, wrapper, executor);
        Field field = ScanBatchResponse.class.getDeclaredField("iterator");
        field.setAccessible(true);
        field.set(response, iterator);
        try {
            response.onCompleted();
            response.onNext(ScanStreamBatchReq.newBuilder()
                                            .setQueryRequest(ScanQueryRequest.getDefaultInstance())
                                            .build());
            response.onCompleted();
            verify(iterator).close();
            verifyNoInteractions(wrapper);
            assertNull(field.get(response));
            assertFalse(executor.isShutdown());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test(timeout = 5000)
    public void testRejectedStreamReportsOnlyFailure() {
        ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(1);
        executor.shutdown();
        HgStoreWrapperEx wrapper = mock(HgStoreWrapperEx.class);
        AppConfig config = mock(AppConfig.class);
        when(config.getServerWaitTime()).thenReturn(60);
        StreamObserver<KvPageRes> output = mock(StreamObserver.class);
        ScanStreamResponse response = ScanStreamResponse.of(output, wrapper, executor, config);
        ScanStreamReq request = ScanStreamReq.newBuilder().setMethod(ScanMethod.ALL)
                                             .setPageSize(1).setLimit(10).build();

        response.onNext(request);
        response.onNext(request);
        response.onCompleted();

        verify(output).onError(any(Throwable.class));
        verify(output, never()).onNext(any(KvPageRes.class));
        verify(output, never()).onCompleted();
        verifyNoInteractions(wrapper);
    }

    @Test(timeout = 5000)
    public void testStreamIteratorFailureReportsOnlyFailure() throws Exception {
        ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(1);
        HgStoreWrapperEx wrapper = mock(HgStoreWrapperEx.class);
        when(wrapper.scanAll(anyString(), anyString(), any(byte[].class)))
                .thenThrow(new IllegalStateException("iterator failed"));
        AppConfig config = mock(AppConfig.class);
        when(config.getServerWaitTime()).thenReturn(60);
        StreamObserver<KvPageRes> output = mock(StreamObserver.class);
        ScanStreamResponse response = ScanStreamResponse.of(output, wrapper, executor, config);
        FutureTask<Void> request = new FutureTask<>(() -> {
            response.onNext(ScanStreamReq.newBuilder().setMethod(ScanMethod.ALL)
                                         .setPageSize(1).setLimit(10).build());
            return null;
        });
        Thread caller = new Thread(request, "scan-failure-test");
        try {
            caller.start();
            request.get(1, TimeUnit.SECONDS);
            executor.shutdown();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            response.onCompleted();
            verify(output).onError(any(Throwable.class));
            verify(output, never()).onNext(any(KvPageRes.class));
            verify(output, never()).onCompleted();
        } finally {
            response.onCompleted();
            executor.shutdownNow();
            caller.join(1000);
        }
    }

    @Test(timeout = 5000)
    public void testReceiveFailureDoesNotInterruptItsCallbackThread() throws Exception {
        ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(1);
        CountDownLatch occupied = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        executor.execute(() -> {
            occupied.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        HgStoreWrapperEx wrapper = mock(HgStoreWrapperEx.class);
        AppConfig config = mock(AppConfig.class);
        when(config.getServerWaitTime()).thenReturn(0);
        StreamObserver<KvPageRes> output = mock(StreamObserver.class);
        ScanStreamResponse response = ScanStreamResponse.of(output, wrapper, executor, config);
        FutureTask<Boolean> request = new FutureTask<>(() -> {
            response.onNext(ScanStreamReq.newBuilder().setMethod(ScanMethod.ALL)
                                         .setPageSize(1).setLimit(10).build());
            return Thread.currentThread().isInterrupted();
        });
        Thread caller = new Thread(request, "scan-receive-failure-test");
        try {
            assertTrue(occupied.await(1, TimeUnit.SECONDS));
            caller.start();
            assertFalse(request.get(1, TimeUnit.SECONDS));
            release.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            verify(output).onError(any(Throwable.class));
            verify(output, never()).onNext(any(KvPageRes.class));
            verify(output, never()).onCompleted();
            verifyNoInteractions(wrapper);
        } finally {
            release.countDown();
            response.onCompleted();
            executor.shutdownNow();
            caller.join(1000);
        }
    }

    @Test(timeout = 10000)
    public void testStreamFailureWaitsForPageCallback() throws Exception {
        AtomicReference<Thread> scanWorker = new AtomicReference<>();
        ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(1, task -> {
            Thread thread = new Thread(task, "scan-page-failure-worker");
            scanWorker.set(thread);
            return thread;
        });
        HgStoreWrapperEx wrapper = mock(HgStoreWrapperEx.class);
        ScanIterator iterator = mock(ScanIterator.class);
        when(wrapper.scanAll(anyString(), anyString(), any(byte[].class))).thenReturn(iterator);
        when(iterator.next()).thenReturn(RocksDBSession.BackendColumn.of(new byte[4], new byte[0]));
        when(iterator.position()).thenReturn(new byte[4]);
        CountDownLatch pageEntered = new CountDownLatch(1);
        CountDownLatch releasePage = new CountDownLatch(1);
        CountDownLatch iteratorFailed = new CountDownLatch(1);
        CountDownLatch errorReceived = new CountDownLatch(1);
        AtomicInteger advances = new AtomicInteger();
        when(iterator.hasNext()).thenAnswer(invocation -> {
            if (advances.incrementAndGet() <= 2) {
                return true;
            }
            assertTrue(pageEntered.await(1, TimeUnit.SECONDS));
            iteratorFailed.countDown();
            throw new IllegalStateException("failure after first page");
        });
        StreamObserver<KvPageRes> output = mock(StreamObserver.class);
        List<String> signals = Collections.synchronizedList(new ArrayList<>());
        doAnswer(invocation -> {
            signals.add("page-start");
            pageEntered.countDown();
            assertTrue(releasePage.await(2, TimeUnit.SECONDS));
            signals.add("page-end");
            return null;
        }).when(output).onNext(any(KvPageRes.class));
        doAnswer(invocation -> {
            signals.add("error");
            errorReceived.countDown();
            return null;
        }).when(output).onError(any(Throwable.class));
        AppConfig config = mock(AppConfig.class);
        when(config.getServerWaitTime()).thenReturn(60);
        ScanStreamResponse response = ScanStreamResponse.of(output, wrapper, executor, config);
        FutureTask<Void> request = new FutureTask<>(() -> {
            response.onNext(ScanStreamReq.newBuilder().setMethod(ScanMethod.ALL)
                                         .setPageSize(1).setLimit(10).build());
            return null;
        });
        Thread caller = new Thread(request, "scan-page-failure-test");
        try {
            caller.start();
            assertTrue(pageEntered.await(1, TimeUnit.SECONDS));
            assertTrue(iteratorFailed.await(1, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (scanWorker.get().getState() != Thread.State.BLOCKED &&
                   errorReceived.getCount() != 0 && System.nanoTime() < deadline) {
                Thread.yield();
            }
            assertEquals("worker must reach the response serialization point",
                         Thread.State.BLOCKED, scanWorker.get().getState());
            assertEquals(1L, errorReceived.getCount());
            releasePage.countDown();
            request.get(1, TimeUnit.SECONDS);
            executor.shutdown();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            assertEquals(Arrays.asList("page-start", "page-end", "error"), signals);
            verify(output).onError(any(Throwable.class));
            verify(output, never()).onCompleted();
            verify(iterator).close();
        } finally {
            releasePage.countDown();
            response.onCompleted();
            executor.shutdownNow();
            caller.join(1000);
        }
    }

    @Test(timeout = 10000)
    public void testNormalStreamCancellationClaimsTerminalBeforeInterruptingWorker() throws Exception {
        for (boolean closeRequest : new boolean[]{false, true}) {
            CountDownLatch workerEntered = new CountDownLatch(1);
            CountDownLatch iteratorClosed = new CountDownLatch(1);
            ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(1);
            HgStoreWrapperEx wrapper = mock(HgStoreWrapperEx.class);
            ScanIterator iterator = mock(ScanIterator.class);
            when(wrapper.scanAll(anyString(), anyString(), any(byte[].class))).thenReturn(iterator);
            when(iterator.next()).thenReturn(RocksDBSession.BackendColumn.of(new byte[4], new byte[0]));
            when(iterator.position()).thenReturn(new byte[4]);
            AtomicInteger advances = new AtomicInteger();
            when(iterator.hasNext()).thenAnswer(invocation -> {
                if (advances.incrementAndGet() <= 2) {
                    return true;
                }
                workerEntered.countDown();
                new CountDownLatch(1).await();
                return false;
            });
            doAnswer(invocation -> {
                iteratorClosed.countDown();
                return null;
            }).when(iterator).close();
            AppConfig config = mock(AppConfig.class);
            when(config.getServerWaitTime()).thenReturn(60);
            StreamObserver<KvPageRes> output = mock(StreamObserver.class);
            ScanStreamResponse response = ScanStreamResponse.of(output, wrapper, executor, config);
            try {
                response.onNext(ScanStreamReq.newBuilder().setMethod(ScanMethod.ALL)
                                             .setPageSize(1).setLimit(10).build());
                assertTrue(workerEntered.await(1, TimeUnit.SECONDS));
                if (closeRequest) {
                    response.onNext(ScanStreamReq.newBuilder().setCloseFlag(1).build());
                } else {
                    response.onCompleted();
                }
                executor.shutdown();
                assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
                verify(output, never()).onError(any(Throwable.class));
                verify(output).onCompleted();
                verify(iterator).close();
            } finally {
                response.onCompleted();
                executor.shutdownNow();
            }
        }
    }

    @Test
    public void testRejectedBatchReportsFailureWithoutSuccessfulCompletion() {
        ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(1);
        executor.shutdown();
        StreamObserver<KvStream> output = mock(StreamObserver.class);
        ScanBatchResponse response = new ScanBatchResponse(output, mock(HgStoreWrapperEx.class), executor);
        response.onNext(ScanStreamBatchReq.newBuilder()
                                        .setQueryRequest(ScanQueryRequest.getDefaultInstance()).build());
        verify(output).onError(any(Throwable.class));
        verify(output, never()).onCompleted();
    }

    @Test
    public void testBatchIteratorFailureClosesResourcesWithoutSuccessfulCompletion() throws Exception {
        ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(1);
        StreamObserver<KvStream> output = mock(StreamObserver.class);
        ScanIterator iterator = mock(ScanIterator.class);
        IllegalStateException failure = new IllegalStateException("iterator failed");
        when(iterator.hasNext()).thenThrow(failure);
        ScanBatchResponse response = new ScanBatchResponse(output, mock(HgStoreWrapperEx.class), executor);
        Field field = ScanBatchResponse.class.getDeclaredField("iterator");
        field.setAccessible(true);
        field.set(response, iterator);
        Method send = ScanBatchResponse.class.getDeclaredMethod("sendEntries");
        send.setAccessible(true);
        try {
            send.invoke(response);
            verify(iterator).close();
            verify(output).onError(failure);
            verify(output, never()).onCompleted();
        } finally {
            response.onCompleted();
            executor.shutdownNow();
        }
    }

    @Test(timeout = 5000)
    public void testActiveStreamCancellationReleasesBlockedProducer() throws Exception {
        ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(1);
        HgStoreWrapperEx wrapper = mock(HgStoreWrapperEx.class);
        ScanIterator iterator = mock(ScanIterator.class);
        when(wrapper.scanAll(anyString(), anyString(), any(byte[].class))).thenReturn(iterator);
        when(iterator.hasNext()).thenReturn(true);
        when(iterator.next()).thenReturn(RocksDBSession.BackendColumn.of(new byte[4], new byte[0]));
        when(iterator.position()).thenReturn(new byte[4]);
        AppConfig config = mock(AppConfig.class);
        when(config.getServerWaitTime()).thenReturn(60);
        StreamObserver<KvPageRes> output = mock(StreamObserver.class);
        ScanStreamResponse response = ScanStreamResponse.of(output, wrapper, executor, config);
        try {
            // Consume just one page, then leave the producer waiting for its consumer.
            response.onNext(ScanStreamReq.newBuilder().setMethod(ScanMethod.ALL)
                                         .setPageSize(1).setLimit(1000).build());
            response.onError(Status.CANCELLED.asRuntimeException());
            executor.shutdown();
            assertTrue("cancel must release a producer without its 60-second timeout",
                       executor.awaitTermination(1, TimeUnit.SECONDS));
            verify(iterator).close();
        } finally {
            response.onCompleted();
            executor.shutdownNow();
        }
    }

    @Test(timeout = 5000)
    public void testParallelPausedScannerReleasesIterator() throws Exception {
        ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(1);
        ScanIterator source = mock(ScanIterator.class);
        when(source.hasNext()).thenReturn(true);
        when(source.next()).thenReturn(RocksDBSession.BackendColumn.of(new byte[4], new byte[0]));
        Field bodySize = ParallelScanIterator.class.getDeclaredField("maxBodySize");
        bodySize.setAccessible(true);
        int originalBodySize = bodySize.getInt(null);
        bodySize.setInt(null, 2);
        ParallelScanIterator scan = null;
        try {
            scan = ParallelScanIterator.of(
                    () -> new KVPair<>(mock(QueryCondition.class), source), () -> Long.MAX_VALUE,
                    ScanQueryRequest.getDefaultInstance(), executor);
            // One worker fills its output allowance and pauses while retaining its iterator.
            executor.submit(() -> { }).get(2, TimeUnit.SECONDS);
            verify(source, atLeastOnce()).next();
            verify(source, never()).close();
            scan.close();
            scan.close();
            verify(source).close();
            executor.shutdown();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        } finally {
            if (scan != null) {
                scan.close();
            }
            bodySize.setInt(null, originalBodySize);
            executor.shutdownNow();
        }
    }

    @Test(timeout = 5000)
    public void testParallelOrderedScannerReleasesQueueLockOnEmptyIterator() throws Exception {
        ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(1);
        ScanIterator first = mock(ScanIterator.class);
        ScanIterator empty = mock(ScanIterator.class);
        when(first.hasNext()).thenReturn(true);
        when(first.next()).thenReturn(RocksDBSession.BackendColumn.of(new byte[4], new byte[0]));
        when(empty.hasNext()).thenReturn(false);

        AtomicInteger supplies = new AtomicInteger();
        Field bodySize = ParallelScanIterator.class.getDeclaredField("maxBodySize");
        bodySize.setAccessible(true);
        int originalBodySize = bodySize.getInt(null);
        bodySize.setInt(null, 2);
        ParallelScanIterator scan = null;
        try {
            scan = ParallelScanIterator.of(
                    () -> {
                        int supply = supplies.incrementAndGet();
                        if (supply == 1) {
                            return new KVPair<>(mock(QueryCondition.class), first);
                        }
                        if (supply == 2) {
                            return new KVPair<>(mock(QueryCondition.class), empty);
                        }
                        return new KVPair<>(mock(QueryCondition.class), null);
                    },
                    () -> 1L,
                    ScanQueryRequest.newBuilder().setOrderType(ScanOrderType.ORDER_WITHIN_VERTEX).build(),
                    executor);

            // Wait for the scanner task to finish before checking the lock owner.
            executor.submit(() -> { }).get(2, TimeUnit.SECONDS);
            Field queueLock = ParallelScanIterator.class.getDeclaredField("queueLock");
            queueLock.setAccessible(true);
            assertFalse(((ReentrantLock) queueLock.get(scan)).isLocked());
        } finally {
            if (scan != null) {
                scan.close();
            }
            bodySize.setInt(null, originalBodySize);
            executor.shutdownNow();
        }
    }

    @Test(timeout = 5000)
    public void testParallelOrderedProducerCancelsWithFullOutputQueue() throws Exception {
        ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(1);
        ScanIterator source = mock(ScanIterator.class);
        Field bodySize = ParallelScanIterator.class.getDeclaredField("maxBodySize");
        bodySize.setAccessible(true);
        byte[] value = new byte[bodySize.getInt(null)];
        CountDownLatch full = new CountDownLatch(1);
        AtomicInteger rows = new AtomicInteger();
        when(source.hasNext()).thenReturn(true);
        when(source.next()).thenAnswer(invocation -> {
            if (rows.incrementAndGet() == 5) {
                full.countDown();
            }
            return RocksDBSession.BackendColumn.of(new byte[4], value);
        });
        ParallelScanIterator scan = ParallelScanIterator.of(
                () -> new KVPair<>(mock(QueryCondition.class), source), () -> Long.MAX_VALUE,
                ScanQueryRequest.newBuilder().setOrderType(ScanOrderType.ORDER_WITHIN_VERTEX).build(),
                executor);
        try {
            assertTrue("four queued batches must fill the single-scanner queue",
                       full.await(2, TimeUnit.SECONDS));
            scan.close();
            executor.shutdown();
            assertTrue("cancellation must release the blocked ordered producer",
                       executor.awaitTermination(1, TimeUnit.SECONDS));
            verify(source).close();
        } finally {
            scan.close();
            executor.shutdownNow();
        }
    }

    @Test
    public void testInterruptedOneShotDoesNotSendPartialSuccess() {
        for (boolean batch : new boolean[]{false, true}) {
            HgStoreWrapperEx wrapper = mock(HgStoreWrapperEx.class);
            ScanIterator iterator = mock(ScanIterator.class);
            StreamObserver<KvPageRes> output = mock(StreamObserver.class);
            when(wrapper.scanAll(anyString(), anyString(), any(byte[].class))).thenReturn(iterator);
            when(iterator.hasNext()).thenReturn(true);
            when(iterator.position()).thenReturn(new byte[4]);
            when(iterator.next()).thenAnswer(invocation -> {
                Thread.currentThread().interrupt();
                return RocksDBSession.BackendColumn.of(new byte[4], new byte[0]);
            });
            try {
                if (batch) {
                    ScanBatchOneShotResponse.scanOneShot(batchRequest(), output, wrapper);
                } else {
                    ScanOneShotResponse.scanOneShot(
                            ScanStreamReq.newBuilder().setMethod(ScanMethod.ALL).setLimit(10).build(),
                            output, wrapper);
                }
                verify(iterator).next();
                assertTrue("scan must preserve an external interrupt",
                           Thread.currentThread().isInterrupted());
                verify(output).onError(any(Throwable.class));
                verify(output, never()).onNext(any(KvPageRes.class));
                verify(output, never()).onCompleted();
                verify(iterator).close();
            } finally {
                Thread.interrupted();
            }
        }
    }

    @Test(timeout = 5000)
    public void testInterruptedBatchWorkerDoesNotCompletePartialPage() throws Exception {
        ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(1);
        HgStoreWrapperEx wrapper = mock(HgStoreWrapperEx.class);
        ScanIterator iterator = mock(ScanIterator.class);
        StreamObserver<KvPageRes> output = mock(StreamObserver.class);
        when(wrapper.scanAll(anyString(), anyString(), any(byte[].class))).thenReturn(iterator);
        when(iterator.hasNext()).thenReturn(true);
        when(iterator.position()).thenReturn(new byte[4]);
        when(iterator.next()).thenAnswer(invocation -> {
            Thread.currentThread().interrupt();
            return RocksDBSession.BackendColumn.of(new byte[4], new byte[0]);
        });
        StreamObserver<ScanStreamBatchReq> response = ScanBatchResponse3.of(output, wrapper, executor);
        try {
            response.onNext(batchRequest());
            executor.shutdown();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            verify(iterator).next();
            assertCancelled(output);
            verify(output, never()).onNext(any(KvPageRes.class));
            verify(output, never()).onCompleted();
            verify(iterator).close();
        } finally {
            response.onCompleted();
            executor.shutdownNow();
        }
    }

    @Test(timeout = 10000)
    public void testCancellationDoesNotInterruptReceiverAfterHandoff() throws Exception {
        assertReceiverInterruptAfterHandoff(false);
    }

    @Test(timeout = 10000)
    public void testCancellationPreservesExternalReceiverInterruptAfterHandoff() throws Exception {
        assertReceiverInterruptAfterHandoff(true);
    }

    @Test(timeout = 5000)
    public void testReceivePreservesExternalInterrupt() throws Exception {
        ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(1);
        HgStoreWrapperEx wrapper = mock(HgStoreWrapperEx.class);
        AppConfig config = mock(AppConfig.class);
        when(config.getServerWaitTime()).thenReturn(60);
        StreamObserver<KvPageRes> output = mock(StreamObserver.class);
        ScanStreamResponse response = ScanStreamResponse.of(output, wrapper, executor, config);
        FutureTask<Boolean> request = new FutureTask<>(() -> {
            Thread.currentThread().interrupt();
            response.onNext(ScanStreamReq.newBuilder().setMethod(ScanMethod.ALL)
                                         .setPageSize(1).setLimit(10).build());
            return Thread.currentThread().isInterrupted();
        });
        Thread caller = new Thread(request, "scan-external-interrupt-test");
        try {
            caller.start();
            assertTrue(request.get(1, TimeUnit.SECONDS));
            assertCancelled(output);
            verify(output, never()).onCompleted();
            executor.shutdown();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        } finally {
            response.onCompleted();
            executor.shutdownNow();
            caller.join(1000);
        }
    }

    private static void assertReceiverInterruptAfterHandoff(boolean externalInterrupt) throws Exception {
        ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(1);
        HgStoreWrapperEx wrapper = mock(HgStoreWrapperEx.class);
        ScanIterator iterator = mock(ScanIterator.class);
        when(wrapper.scanAll(anyString(), anyString(), any(byte[].class))).thenReturn(iterator);
        when(iterator.next()).thenReturn(RocksDBSession.BackendColumn.of(new byte[4], new byte[0]));
        when(iterator.position()).thenReturn(new byte[4]);
        CountDownLatch beforePage = new CountDownLatch(1);
        CountDownLatch releasePage = new CountDownLatch(1);
        AtomicInteger advances = new AtomicInteger();
        when(iterator.hasNext()).thenAnswer(invocation -> {
            int advance = advances.incrementAndGet();
            if (advance == 2) {
                beforePage.countDown();
                assertTrue(releasePage.await(2, TimeUnit.SECONDS));
            }
            return advance <= 2;
        });
        AppConfig config = mock(AppConfig.class);
        when(config.getServerWaitTime()).thenReturn(60);
        StreamObserver<KvPageRes> output = mock(StreamObserver.class);
        ScanStreamResponse response = ScanStreamResponse.of(output, wrapper, executor, config);
        Field responseLock = ScanStreamResponse.class.getDeclaredField("responseLock");
        responseLock.setAccessible(true);
        FutureTask<Boolean> request = new FutureTask<>(() -> {
            response.onNext(ScanStreamReq.newBuilder().setMethod(ScanMethod.ALL)
                                         .setPageSize(1).setLimit(10).build());
            return Thread.currentThread().isInterrupted();
        });
        Thread caller = new Thread(request, "scan-late-cancellation-test");
        try {
            caller.start();
            assertTrue(beforePage.await(1, TimeUnit.SECONDS));
            synchronized (responseLock.get(response)) {
                // The real channel hands off a page before its callback reaches responseLock.
                releasePage.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                while (caller.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                    Thread.yield();
                }
                assertEquals(Thread.State.BLOCKED, caller.getState());
                if (externalInterrupt) {
                    caller.interrupt();
                }
                response.onCompleted();
            }
            assertEquals(externalInterrupt, request.get(1, TimeUnit.SECONDS));
            executor.shutdown();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            verify(output).onCompleted();
            verify(output, never()).onError(any(Throwable.class));
            verify(output, never()).onNext(any(KvPageRes.class));
            verify(iterator).close();
        } finally {
            releasePage.countDown();
            response.onCompleted();
            executor.shutdownNow();
            caller.join(1000);
        }
    }

    @Test(timeout = 5000)
    public void testInterruptedStreamWorkerDoesNotCompletePartialPage() throws Exception {
        ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(1);
        HgStoreWrapperEx wrapper = mock(HgStoreWrapperEx.class);
        ScanIterator iterator = mock(ScanIterator.class);
        when(wrapper.scanAll(anyString(), anyString(), any(byte[].class))).thenReturn(iterator);
        when(iterator.hasNext()).thenReturn(true);
        when(iterator.position()).thenReturn(new byte[4]);
        when(iterator.next()).thenAnswer(invocation -> {
            Thread.currentThread().interrupt();
            return RocksDBSession.BackendColumn.of(new byte[4], new byte[0]);
        });
        AppConfig config = mock(AppConfig.class);
        when(config.getServerWaitTime()).thenReturn(60);
        StreamObserver<KvPageRes> output = mock(StreamObserver.class);
        ScanStreamResponse response = ScanStreamResponse.of(output, wrapper, executor, config);
        try {
            response.onNext(ScanStreamReq.newBuilder().setMethod(ScanMethod.ALL)
                                         .setPageSize(1).setLimit(10).build());
            executor.shutdown();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            verify(iterator).next();
            assertCancelled(output);
            verify(output, never()).onNext(any(KvPageRes.class));
            verify(output, never()).onCompleted();
            verify(iterator).close();
        } finally {
            response.onCompleted();
            executor.shutdownNow();
        }
    }

    @Test(timeout = 5000)
    public void testInterruptedStreamSenderReportsCancellation() throws Exception {
        AtomicReference<Thread> worker = new AtomicReference<>();
        ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(1, task -> {
            Thread thread = new Thread(task, "scan-interrupted-sender-test");
            worker.set(thread);
            return thread;
        });
        HgStoreWrapperEx wrapper = mock(HgStoreWrapperEx.class);
        ScanIterator iterator = mock(ScanIterator.class);
        when(wrapper.scanAll(anyString(), anyString(), any(byte[].class))).thenReturn(iterator);
        when(iterator.hasNext()).thenReturn(true);
        when(iterator.next()).thenReturn(RocksDBSession.BackendColumn.of(new byte[4], new byte[0]));
        when(iterator.position()).thenReturn(new byte[4]);
        AppConfig config = mock(AppConfig.class);
        when(config.getServerWaitTime()).thenReturn(60);
        StreamObserver<KvPageRes> output = mock(StreamObserver.class);
        CountDownLatch errorReceived = new CountDownLatch(1);
        doAnswer(invocation -> {
            errorReceived.countDown();
            return null;
        }).when(output).onError(any(Throwable.class));
        ScanStreamResponse response = ScanStreamResponse.of(output, wrapper, executor, config);
        try {
            // Consume one page, then interrupt the actual worker waiting to hand off its next page.
            response.onNext(ScanStreamReq.newBuilder().setMethod(ScanMethod.ALL)
                                         .setPageSize(1).setLimit(10).build());
            awaitTimedWaiting(worker.get());
            worker.get().interrupt();
            assertTrue(errorReceived.await(1, TimeUnit.SECONDS));
            executor.shutdown();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            assertCancelled(output);
            verify(output).onNext(any(KvPageRes.class));
            verify(output, never()).onCompleted();
            verify(iterator).close();
        } finally {
            response.onCompleted();
            executor.shutdownNow();
        }
    }

    @Test(timeout = 5000)
    public void testChannelCloseReleasesWaitingReceiverWithoutInterrupt() throws Exception {
        HgChannel<String> channel = HgChannel.of(60);
        FutureTask<Boolean> receive = new FutureTask<>(() -> {
            assertNull(channel.receive());
            return Thread.currentThread().isInterrupted();
        });
        Thread caller = new Thread(receive, "channel-close-receiver-test");
        try {
            caller.start();
            awaitTimedWaiting(caller);
            channel.close();
            assertFalse(receive.get(1, TimeUnit.SECONDS));
            assertNull(channel.receive());
            assertFalse(channel.send("after-close"));
        } finally {
            channel.close();
            caller.join(1000);
        }
    }

    @Test(timeout = 5000)
    public void testChannelCloseReleasesWaitingProducer() throws Exception {
        HgChannel<String> channel = HgChannel.of(60);
        FutureTask<Boolean> send = new FutureTask<>(() -> channel.send("page"));
        Thread producer = new Thread(send, "channel-close-producer-test");
        try {
            producer.start();
            awaitTimedWaiting(producer);
            channel.close();
            assertFalse(send.get(1, TimeUnit.SECONDS));
        } finally {
            channel.close();
            producer.join(1000);
        }
    }

    @Test(timeout = 5000)
    public void testChannelRetainsTimeoutAndNormalHandoff() throws Exception {
        HgChannel<String> channel = HgChannel.of(1);
        AtomicInteger timeouts = new AtomicInteger();
        long started = System.nanoTime();
        assertNull(channel.receive(timeout -> {
            assertEquals(Long.valueOf(1L), timeout);
            timeouts.incrementAndGet();
        }));
        assertEquals(1, timeouts.get());
        assertTrue("short close polling must not shorten the configured timeout",
                   System.nanoTime() - started >= TimeUnit.MILLISECONDS.toNanos(900));
        FutureTask<Boolean> send = new FutureTask<>(() -> channel.send("page"));
        Thread producer = new Thread(send, "channel-handoff-test");
        try {
            producer.start();
            assertEquals("page", channel.receive());
            assertTrue(send.get(1, TimeUnit.SECONDS));
        } finally {
            channel.close();
            producer.join(1000);
        }
    }

    private static void awaitTimedWaiting(Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        Thread.State observed = thread.getState();
        while (observed != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) {
            Thread.yield();
            observed = thread.getState();
        }
        assertEquals(Thread.State.TIMED_WAITING, observed);
    }

    private static ScanStreamBatchReq batchRequest() {
        return ScanStreamBatchReq.newBuilder().setHeader(Header.newBuilder().setGraph("g"))
                                 .setQueryRequest(ScanQueryRequest.newBuilder().setMethod(ScanMethod.ALL)
                                                                 .setTable("t").setLimit(10)
                                                                 .setPerKeyMax(Long.MAX_VALUE)
                                                                 .setPageSize(1)).build();
    }

    private static void assertCancelled(StreamObserver<KvPageRes> output) {
        ArgumentCaptor<Throwable> failure = ArgumentCaptor.forClass(Throwable.class);
        verify(output).onError(failure.capture());
        assertEquals(Status.Code.CANCELLED, Status.fromThrowable(failure.getValue()).getCode());
    }

    private static void assertUnavailable(Runnable action) {
        try {
            action.run();
            fail("Scan admission must be closed");
        } catch (StatusRuntimeException e) {
            assertEquals(Status.Code.UNAVAILABLE, e.getStatus().getCode());
        }
    }
}
