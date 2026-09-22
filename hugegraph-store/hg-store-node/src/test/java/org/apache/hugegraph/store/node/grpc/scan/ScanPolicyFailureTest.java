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

package org.apache.hugegraph.store.node.grpc.scan;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.hugegraph.store.business.BusinessHandler;
import org.apache.hugegraph.store.business.GraphStoreIterator;
import org.apache.hugegraph.store.grpc.Graphpb;
import org.apache.hugegraph.store.grpc.Graphpb.ScanPartitionRequest;
import org.apache.hugegraph.store.grpc.Graphpb.ScanResponse;
import org.apache.hugegraph.testutil.Whitebox;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;

public class ScanPolicyFailureTest {

    @Test
    public void testInvalidConditionFailsOnceBeforeIteratorExists() throws Exception {
        BusinessHandler handler = Mockito.mock(BusinessHandler.class);
        ScanPartitionRequest request = request();
        Mockito.when(handler.scan(request)).thenThrow(new IllegalArgumentException("invalid condition"));
        RecordingSender sender = new RecordingSender();
        ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(2);
        try {
            ScanResponseObserver<?> observer = new ScanResponseObserver<>(sender, handler, executor);
            observer.onNext(request);
            observer.onNext(request);
            observer.onCompleted();
            Assertions.assertEquals(1, sender.errors.get());
            Assertions.assertEquals(0, sender.completed.get());
            Assertions.assertEquals(Status.Code.INVALID_ARGUMENT, Status.fromThrowable(sender.error).getCode());
            Mockito.verify(handler, Mockito.times(1)).scan(request);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void testEvaluationFailureClosesIteratorAndDoesNotComplete() throws Exception {
        assertEvaluationFailure(new IllegalStateException("filter failed"));
    }

    @Test
    public void testAssertionFailureClosesIteratorAndDoesNotComplete() throws Exception {
        assertEvaluationFailure(new AssertionError("filter assertion failed"));
    }

    private static void assertEvaluationFailure(Throwable failure) throws Exception {
        BusinessHandler handler = Mockito.mock(BusinessHandler.class);
        GraphStoreIterator<Object> iterator = Mockito.mock(GraphStoreIterator.class);
        Mockito.when(iterator.hasNext()).thenReturn(true).thenThrow(failure);
        ScanPartitionRequest request = request();
        Mockito.when(handler.scan(request)).thenReturn(iterator);
        RecordingSender sender = new RecordingSender();
        ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(2);
        try {
            ScanResponseObserver<?> observer = new ScanResponseObserver<>(sender, handler, executor);
            observer.onNext(request);
            Assertions.assertTrue(sender.terminal.await(5, TimeUnit.SECONDS));
            observer.onCompleted();
            Assertions.assertEquals(1, sender.errors.get());
            Assertions.assertEquals(0, sender.completed.get());
            Assertions.assertEquals(0, sender.rows.get());
            Assertions.assertEquals(Status.Code.INTERNAL, Status.fromThrowable(sender.error).getCode());
            Mockito.verify(iterator, Mockito.times(1)).close();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void testEmptyScanCompletesAndClosesOnce() throws Exception {
        BusinessHandler handler = Mockito.mock(BusinessHandler.class);
        GraphStoreIterator<Object> iterator = Mockito.mock(GraphStoreIterator.class);
        ScanPartitionRequest request = request();
        Mockito.when(handler.scan(request)).thenReturn(iterator);
        RecordingSender sender = new RecordingSender();
        ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(2);
        try {
            ScanResponseObserver<?> observer = new ScanResponseObserver<>(sender, handler, executor);
            observer.onNext(request);
            Assertions.assertTrue(sender.terminal.await(5, TimeUnit.SECONDS));
            observer.onCompleted();
            Assertions.assertEquals(0, sender.errors.get());
            Assertions.assertEquals(1, sender.completed.get());
            Mockito.verify(iterator, Mockito.times(1)).close();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void testScanLimitCompletesWithoutReadingAnotherRow() throws Exception {
        BusinessHandler handler = Mockito.mock(BusinessHandler.class);
        GraphStoreIterator<Object> iterator = Mockito.mock(GraphStoreIterator.class);
        Mockito.when(iterator.hasNext()).thenReturn(true);
        Mockito.when(iterator.next()).thenReturn(
                Graphpb.Vertex.newBuilder().build());
        ScanPartitionRequest request = request().toBuilder().setScanRequest(
                request().getScanRequest().toBuilder().setLimit(1)).build();
        Mockito.when(handler.scan(request)).thenReturn(iterator);
        RecordingSender sender = new RecordingSender();
        ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(2);
        try {
            ScanResponseObserver<?> observer = new ScanResponseObserver<>(sender, handler, executor);
            observer.onNext(request);
            Assertions.assertTrue(sender.terminal.await(5, TimeUnit.SECONDS));
            observer.onCompleted();
            Assertions.assertEquals(0, sender.errors.get());
            Assertions.assertEquals(1, sender.completed.get());
            Assertions.assertEquals(1, sender.rows.get());
            Mockito.verify(iterator, Mockito.times(1)).next();
            Mockito.verify(iterator, Mockito.times(1)).close();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void testFailureAfterEmittedBatchEndsWithOneError() throws Exception {
        BusinessHandler handler = Mockito.mock(BusinessHandler.class);
        GraphStoreIterator<Object> iterator = Mockito.mock(GraphStoreIterator.class);
        RecordingSender sender = new RecordingSender();
        AtomicInteger read = new AtomicInteger();
        Mockito.when(iterator.next()).thenAnswer(call -> {
            read.incrementAndGet();
            return Graphpb.Vertex.getDefaultInstance();
        });
        Mockito.when(iterator.hasNext()).thenAnswer(call -> {
            if (read.get() >= 100000) {
                Assertions.assertTrue(sender.firstBatch.await(10, TimeUnit.SECONDS), "first batch must be delivered");
                throw new IllegalStateException("later filter failed");
            }
            return true;
        });
        ScanPartitionRequest request = request();
        Mockito.when(handler.scan(request)).thenReturn(iterator);
        ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(2);
        try {
            ScanResponseObserver<?> observer = new ScanResponseObserver<>(sender, handler, executor);
            observer.onNext(request);
            Assertions.assertTrue(sender.terminal.await(20, TimeUnit.SECONDS));
            observer.onCompleted();
            Assertions.assertEquals(1, sender.rows.get());
            Assertions.assertEquals(1, sender.errors.get());
            Assertions.assertEquals(0, sender.completed.get());
            Mockito.verify(iterator, Mockito.times(1)).close();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void testExecutorRejectionClosesInitializedIterator() throws Exception {
        BusinessHandler handler = Mockito.mock(BusinessHandler.class);
        GraphStoreIterator<Object> iterator = Mockito.mock(GraphStoreIterator.class);
        Mockito.when(iterator.hasNext()).thenReturn(true);
        ScanPartitionRequest request = request();
        Mockito.when(handler.scan(request)).thenReturn(iterator);
        RecordingSender sender = new RecordingSender();
        ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(2);
        executor.shutdown();
        ScanResponseObserver<?> observer = new ScanResponseObserver<>(sender, handler, executor);
        observer.onNext(request);
        Assertions.assertTrue(sender.terminal.await(5, TimeUnit.SECONDS));
        Assertions.assertEquals(1, sender.errors.get());
        Assertions.assertEquals(0, sender.completed.get());
        Mockito.verify(iterator, Mockito.times(1)).close();
    }

    @Test
    public void testCancellationWhileOpeningIteratorClosesItAfterPublication() throws Exception {
        BusinessHandler handler = Mockito.mock(BusinessHandler.class);
        GraphStoreIterator<Object> iterator = Mockito.mock(GraphStoreIterator.class);
        CountDownLatch opening = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ScanPartitionRequest request = request();
        Mockito.when(handler.scan(request)).thenAnswer(call -> {
            opening.countDown();
            Assertions.assertTrue(release.await(5, TimeUnit.SECONDS));
            return iterator;
        });
        RecordingSender sender = new RecordingSender();
        ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(2);
        try {
            ScanResponseObserver<?> observer = new ScanResponseObserver<>(sender, handler, executor);
            Future<?> initial = executor.submit(() -> observer.onNext(request));
            Assertions.assertTrue(opening.await(5, TimeUnit.SECONDS));
            observer.onCompleted();
            release.countDown();
            initial.get(5, TimeUnit.SECONDS);
            Assertions.assertEquals(0, sender.errors.get());
            Assertions.assertEquals(0, sender.completed.get());
            Mockito.verify(iterator, Mockito.times(1)).close();
            Mockito.verify(iterator, Mockito.never()).hasNext();
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void testClientCancelInterruptsOngoingFilterAndClosesIterator() throws Exception {
        BusinessHandler handler = Mockito.mock(BusinessHandler.class);
        GraphStoreIterator<Object> iterator = Mockito.mock(GraphStoreIterator.class);
        CountDownLatch entered = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        Mockito.when(iterator.hasNext()).thenAnswer(call -> {
            entered.countDown();
            try {
                Thread.sleep(TimeUnit.SECONDS.toMillis(30));
                return true;
            } catch (InterruptedException error) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Store scan cancelled");
            }
        });
        ScanPartitionRequest request = request();
        Mockito.when(handler.scan(request)).thenReturn(iterator);
        RecordingSender sender = new RecordingSender();
        ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(2);
        try {
            ScanResponseObserver<?> observer = new ScanResponseObserver<>(sender, handler, executor);
            observer.onNext(request);
            Assertions.assertTrue(entered.await(5, TimeUnit.SECONDS));
            observer.onCompleted();
            Mockito.verify(iterator, Mockito.timeout(5000).times(1)).close();
            Assertions.assertTrue(interrupted.get(), "ongoing filter must observe interruption");
            Assertions.assertEquals(0, sender.rows.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void testReadSuccessorRemainsCancellableWhenFirstSubmissionReturnsLate() throws Exception {
        verifySuccessorCancellation(true);
    }

    @Test
    public void testSendSuccessorRemainsCancellableWhenFirstSubmissionReturnsLate() throws Exception {
        verifySuccessorCancellation(false);
    }

    private static void verifySuccessorCancellation(boolean reader) throws Exception {
        BusinessHandler handler = Mockito.mock(BusinessHandler.class);
        GraphStoreIterator<Object> iterator = Mockito.mock(GraphStoreIterator.class);
        ScanPartitionRequest request = request();
        Mockito.when(handler.scan(request)).thenReturn(iterator);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        Runnable block = () -> {
            entered.countDown();
            try {
                Assertions.assertTrue(release.await(30, TimeUnit.SECONDS), "test must release the blocked successor");
            } catch (InterruptedException error) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
                throw new IllegalStateException("scan cancelled", error);
            }
        };
        Mockito.when(iterator.hasNext()).thenAnswer(call -> {
            block.run();
            return false;
        });
        RecordingSender sender = new RecordingSender();
        StreamObserver<ScanResponse> output = reader ? sender : new StreamObserver<ScanResponse>() {
            @Override
            public void onNext(ScanResponse response) {
                block.run();
                sender.onNext(response);
            }

            @Override
            public void onError(Throwable error) {
                sender.onError(error);
            }

            @Override
            public void onCompleted() {
                sender.onCompleted();
            }
        };
        DelayedFirstSubmission executor = new DelayedFirstSubmission();
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            ScanResponseObserver<?> observer = new ScanResponseObserver<>(output, handler, executor);
            LinkedBlockingQueue<ScanResponse> packages = Whitebox.getInternalState(observer, "packages");
            String start = reader ? "startRead" : "startSend";
            if (reader) {
                // Backpressure ends the first reader without generating millions of fixture rows.
                // Keep the sender at its acknowledgement window while the second reader is tested.
                AtomicInteger next = Whitebox.getInternalState(observer, "nextSeqNo");
                next.set(8);
                executor.beforeFirst = () -> {
                    while (packages.offer(ScanResponse.getDefaultInstance())) {
                        // Fill the queue after admission but before the first reader executes.
                    }
                };
            } else {
                packages.add(ScanResponse.getDefaultInstance());
                // The first sender observes an empty queue and exits, without completing the scan.
                executor.beforeFirst = packages::clear;
            }
            Future<?> first = caller.submit(() -> {
                if (reader) {
                    observer.onNext(request);
                } else {
                    Whitebox.invoke(ScanResponseObserver.class, start, observer);
                }
            });
            Assertions.assertTrue(executor.firstFinished.await(5, TimeUnit.SECONDS),
                                  "first task must finish before its submission returns");
            packages.clear();
            if (!reader) {
                packages.add(ScanResponse.getDefaultInstance());
            }
            Whitebox.invoke(ScanResponseObserver.class, start, observer);
            Assertions.assertTrue(entered.await(5, TimeUnit.SECONDS), "successor must be executing");
            executor.releaseFirst.countDown();
            first.get(5, TimeUnit.SECONDS);
            // Old code overwrites the successor's Future at this point, losing its cancellation handle.
            Future<?> cancel = caller.submit(observer::onCompleted);
            Assertions.assertTrue(interrupted.await(5, TimeUnit.SECONDS),
                                  "cancel must interrupt the current successor");
            cancel.get(5, TimeUnit.SECONDS);
            if (reader) {
                Mockito.verify(iterator, Mockito.times(1)).close();
            }
            Assertions.assertEquals(0, sender.rows.get());
            Assertions.assertEquals(0, sender.errors.get());
            Assertions.assertEquals(0, sender.completed.get());
        } finally {
            release.countDown();
            executor.releaseFirst.countDown();
            caller.shutdownNow();
            executor.shutdownNow();
            Assertions.assertTrue(caller.awaitTermination(5, TimeUnit.SECONDS));
            Assertions.assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static final class DelayedFirstSubmission extends ThreadPoolExecutor {

        private final AtomicBoolean first = new AtomicBoolean(true);
        private final CountDownLatch firstFinished = new CountDownLatch(1);
        private final CountDownLatch releaseFirst = new CountDownLatch(1);
        private Runnable beforeFirst;

        DelayedFirstSubmission() {
            super(2, 2, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        }

        @Override
        public void execute(Runnable command) {
            if (!this.first.compareAndSet(true, false)) {
                super.execute(command);
                return;
            }
            this.beforeFirst.run();
            super.execute(command);
            try {
                ((Future<?>) command).get(5, TimeUnit.SECONDS);
                this.firstFinished.countDown();
                Assertions.assertTrue(this.releaseFirst.await(30, TimeUnit.SECONDS),
                                      "test must release the first submission");
            } catch (Exception error) {
                throw new AssertionError("controlled executor failed", error);
            }
        }
    }

    private static ScanPartitionRequest request() {
        return ScanPartitionRequest.newBuilder().setScanRequest(
                ScanPartitionRequest.Request.newBuilder().setScanType(
                        ScanPartitionRequest.ScanType.SCAN_VERTEX)).build();
    }

    private static final class RecordingSender implements StreamObserver<ScanResponse> {
        final AtomicInteger errors = new AtomicInteger();
        final AtomicInteger completed = new AtomicInteger();
        final AtomicInteger rows = new AtomicInteger();
        final CountDownLatch firstBatch = new CountDownLatch(1);
        final CountDownLatch terminal = new CountDownLatch(1);
        volatile Throwable error;

        @Override
        public void onNext(ScanResponse response) {
            rows.incrementAndGet();
            firstBatch.countDown();
        }

        @Override
        public void onError(Throwable failure) {
            error = failure;
            errors.incrementAndGet();
            terminal.countDown();
        }

        @Override
        public void onCompleted() {
            completed.incrementAndGet();
            terminal.countDown();
        }
    }
}
