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

package org.apache.hugegraph.store.core;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.hugegraph.store.business.BusinessHandler;
import org.apache.hugegraph.store.business.GraphStoreIterator;
import org.apache.hugegraph.store.grpc.Graphpb;
import org.apache.hugegraph.store.grpc.Graphpb.ScanPartitionRequest;
import org.apache.hugegraph.store.grpc.Graphpb.ScanResponse;
import org.apache.hugegraph.store.node.grpc.scan.ScanResponseObserver;
import org.junit.Assert;
import org.junit.Test;
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
            Assert.assertEquals(1, sender.errors.get());
            Assert.assertEquals(0, sender.completed.get());
            Assert.assertEquals(Status.Code.INVALID_ARGUMENT, Status.fromThrowable(sender.error).getCode());
            Mockito.verify(handler, Mockito.times(1)).scan(request);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void testEvaluationFailureClosesIteratorAndDoesNotComplete() throws Exception {
        BusinessHandler handler = Mockito.mock(BusinessHandler.class);
        GraphStoreIterator<Object> iterator = Mockito.mock(GraphStoreIterator.class);
        Mockito.when(iterator.hasNext()).thenReturn(true).thenThrow(new IllegalStateException("filter failed"));
        ScanPartitionRequest request = request();
        Mockito.when(handler.scan(request)).thenReturn(iterator);
        RecordingSender sender = new RecordingSender();
        ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(2);
        try {
            ScanResponseObserver<?> observer = new ScanResponseObserver<>(sender, handler, executor);
            observer.onNext(request);
            Assert.assertTrue(sender.terminal.await(5, TimeUnit.SECONDS));
            observer.onCompleted();
            Assert.assertEquals(1, sender.errors.get());
            Assert.assertEquals(0, sender.completed.get());
            Assert.assertEquals(0, sender.rows.get());
            Assert.assertEquals(Status.Code.INTERNAL, Status.fromThrowable(sender.error).getCode());
            Mockito.verify(iterator, Mockito.times(1)).close();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void testEmptyScanCompletesAndClosesOnce() {
        BusinessHandler handler = Mockito.mock(BusinessHandler.class);
        GraphStoreIterator<Object> iterator = Mockito.mock(GraphStoreIterator.class);
        ScanPartitionRequest request = request();
        Mockito.when(handler.scan(request)).thenReturn(iterator);
        RecordingSender sender = new RecordingSender();
        ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(2);
        try {
            ScanResponseObserver<?> observer = new ScanResponseObserver<>(sender, handler, executor);
            observer.onNext(request);
            observer.onCompleted();
            Assert.assertEquals(0, sender.errors.get());
            Assert.assertEquals(1, sender.completed.get());
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
            Assert.assertTrue(sender.terminal.await(5, TimeUnit.SECONDS));
            observer.onCompleted();
            Assert.assertEquals(0, sender.errors.get());
            Assert.assertEquals(1, sender.completed.get());
            Assert.assertEquals(1, sender.rows.get());
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
                Assert.assertTrue("first batch must be delivered", sender.firstBatch.await(10, TimeUnit.SECONDS));
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
            Assert.assertTrue(sender.terminal.await(20, TimeUnit.SECONDS));
            observer.onCompleted();
            Assert.assertEquals(1, sender.rows.get());
            Assert.assertEquals(1, sender.errors.get());
            Assert.assertEquals(0, sender.completed.get());
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
        Assert.assertTrue(sender.terminal.await(5, TimeUnit.SECONDS));
        Assert.assertEquals(1, sender.errors.get());
        Assert.assertEquals(0, sender.completed.get());
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
            Assert.assertTrue(release.await(5, TimeUnit.SECONDS));
            return iterator;
        });
        RecordingSender sender = new RecordingSender();
        ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(2);
        try {
            ScanResponseObserver<?> observer = new ScanResponseObserver<>(sender, handler, executor);
            Future<?> initial = executor.submit(() -> observer.onNext(request));
            Assert.assertTrue(opening.await(5, TimeUnit.SECONDS));
            observer.onCompleted();
            release.countDown();
            initial.get(5, TimeUnit.SECONDS);
            Assert.assertEquals(0, sender.errors.get());
            Assert.assertEquals(0, sender.completed.get());
            Mockito.verify(iterator, Mockito.times(1)).close();
            Mockito.verify(iterator, Mockito.never()).hasNext();
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void testCancellationDoesNotCloseDuringInitialRead() throws Exception {
        BusinessHandler handler = Mockito.mock(BusinessHandler.class);
        GraphStoreIterator<Object> iterator = Mockito.mock(GraphStoreIterator.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Mockito.when(iterator.hasNext()).thenAnswer(call -> {
            entered.countDown();
            Assert.assertTrue(release.await(5, TimeUnit.SECONDS));
            return true;
        });
        ScanPartitionRequest request = request();
        Mockito.when(handler.scan(request)).thenReturn(iterator);
        RecordingSender sender = new RecordingSender();
        ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(2);
        ScanResponseObserver<?> observer = new ScanResponseObserver<>(sender, handler, executor);
        Thread closer = new Thread(observer::onCompleted);
        try {
            Future<?> initial = executor.submit(() -> observer.onNext(request));
            Assert.assertTrue(entered.await(5, TimeUnit.SECONDS));
            closer.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (closer.isAlive() && closer.getState() != Thread.State.BLOCKED &&
                   System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            Assert.assertEquals("close must wait for the active read", Thread.State.BLOCKED, closer.getState());
            Mockito.verify(iterator, Mockito.never()).close();
            release.countDown();
            initial.get(5, TimeUnit.SECONDS);
            closer.join(5000);
            Assert.assertFalse(closer.isAlive());
            Mockito.verify(iterator, Mockito.times(1)).close();
            Assert.assertEquals(0, sender.rows.get());
        } finally {
            release.countDown();
            closer.join(5000);
            executor.shutdownNow();
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
