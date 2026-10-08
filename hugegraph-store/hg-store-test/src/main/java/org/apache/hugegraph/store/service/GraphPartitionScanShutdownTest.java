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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.hugegraph.rocksdb.access.ScanIterator;
import org.apache.hugegraph.store.business.BusinessHandler;
import org.apache.hugegraph.store.business.GraphStoreIterator;
import org.apache.hugegraph.store.grpc.Graphpb;
import org.apache.hugegraph.store.grpc.Graphpb.ScanPartitionRequest;
import org.apache.hugegraph.store.grpc.Graphpb.ScanResponse;
import org.apache.hugegraph.store.node.grpc.HgStoreStreamImpl;
import org.junit.Test;

import io.grpc.stub.StreamObserver;

public class GraphPartitionScanShutdownTest {

    @Test
    public void testNaturalEndAndLimitCloseIteratorBeforeCompleting() throws Exception {
        for (int limit : new int[]{0, 1}) {
            ThreadPoolExecutor executor = executor();
            try {
                HgStoreStreamImpl service = service(executor);
                GraphStoreIterator<Graphpb.Vertex> iterator = mock(GraphStoreIterator.class);
                AtomicInteger consumed = new AtomicInteger();
                when(iterator.hasNext()).thenAnswer(call -> consumed.get() < 2);
                when(iterator.next()).thenAnswer(call -> {
                    consumed.incrementAndGet();
                    return Graphpb.Vertex.getDefaultInstance();
                });
                BusinessHandler handler = mock(BusinessHandler.class);
                doReturn(iterator).when(handler).scan(any());
                StreamObserver<ScanResponse> response = mock(StreamObserver.class);
                AtomicInteger delivered = new AtomicInteger();
                doAnswer(call -> {
                    delivered.addAndGet(((ScanResponse) call.getArgument(0)).getVertexCount());
                    return null;
                }).when(response).onNext(any());
                StreamObserver<ScanPartitionRequest> request = service.scanGraphPartition(response, handler);
                request.onNext(request(limit));
                verify(response, timeout(2000)).onCompleted();
                service.awaitScanCleanup();
                verify(iterator).close();
                assertEquals(limit == 0 ? 2 : 1, consumed.get());
                assertEquals(consumed.get(), delivered.get());
                assertTrue(registry(service).isEmpty());
                request.onError(new IllegalStateException("late cancellation"));
                verify(iterator).close();
            } finally {
                executor.shutdown();
                assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    public void testEmptyScanClosesOnceAndReleasesBarrier() throws Exception {
        ThreadPoolExecutor executor = executor();
        try {
            HgStoreStreamImpl service = service(executor);
            GraphStoreIterator<?> iterator = mock(GraphStoreIterator.class);
            BusinessHandler handler = mock(BusinessHandler.class);
            doReturn(iterator).when(handler).scan(any());
            StreamObserver<ScanResponse> response = mock(StreamObserver.class);
            service.scanGraphPartition(response, handler).onNext(request(0));
            verify(response, timeout(2000)).onCompleted();
            service.awaitScanCleanup();
            verify(iterator).close();
            assertTrue(registry(service).isEmpty());
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testRejectedWorkerClosesIteratorAndTerminatesResponse() throws Exception {
        ThreadPoolExecutor executor = executor();
        executor.shutdown();
        HgStoreStreamImpl service = service(executor);
        GraphStoreIterator<?> iterator = mock(GraphStoreIterator.class);
        BusinessHandler handler = mock(BusinessHandler.class);
        doReturn(iterator).when(handler).scan(any());
        StreamObserver<ScanResponse> response = mock(StreamObserver.class);
        service.scanGraphPartition(response, handler).onNext(request(0));
        verify(response).onError(any());
        verify(iterator).close();
        assertTrue(registry(service).isEmpty());
    }

    @Test
    public void testReadFailureClosesIteratorAndPreservesPrimaryError() throws Exception {
        ThreadPoolExecutor executor = executor();
        try {
            HgStoreStreamImpl service = service(executor);
            GraphStoreIterator<?> iterator = mock(GraphStoreIterator.class);
            RuntimeException failure = new IllegalStateException("native read failed");
            when(iterator.hasNext()).thenThrow(failure);
            BusinessHandler handler = mock(BusinessHandler.class);
            doReturn(iterator).when(handler).scan(any());
            StreamObserver<ScanResponse> response = mock(StreamObserver.class);
            service.scanGraphPartition(response, handler).onNext(request(0));
            verify(response, timeout(2000)).onError(failure);
            service.awaitScanCleanup();
            verify(iterator).close();
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testFailedNativeCloseRemainsInShutdownBarrier() throws Exception {
        ThreadPoolExecutor executor = executor();
        try {
            HgStoreStreamImpl service = service(executor);
            GraphStoreIterator<?> iterator = mock(GraphStoreIterator.class);
            RuntimeException failure = new IllegalStateException("native close failed");
            doThrow(failure).when(iterator).close();
            BusinessHandler handler = mock(BusinessHandler.class);
            doReturn(iterator).when(handler).scan(any());
            StreamObserver<ScanResponse> response = mock(StreamObserver.class);
            service.scanGraphPartition(response, handler).onNext(request(0));
            verify(response, timeout(2000)).onError(failure);
            assertEquals(1, registry(service).size());
            Object lifecycle = registry(service).keySet().iterator().next();
            java.lang.reflect.Method cleanup = lifecycle.getClass().getDeclaredMethod("cleanupFailure");
            cleanup.setAccessible(true);
            assertSame(failure, cleanup.invoke(lifecycle));
            verify(iterator).close();
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testShutdownWaitsForActiveReadBeforeClosingNativeIterator() throws Exception {
        ThreadPoolExecutor executor = executor();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        CountDownLatch stoppingStarted = new CountDownLatch(1);
        try {
            HgStoreStreamImpl service = service(executor);
            GraphStoreIterator<?> iterator = mock(GraphStoreIterator.class);
            when(iterator.hasNext()).thenAnswer(call -> {
                entered.countDown();
                assertTrue(release.await(2, TimeUnit.SECONDS));
                return false;
            });
            BusinessHandler handler = mock(BusinessHandler.class);
            doReturn(iterator).when(handler).scan(any());
            StreamObserver<ScanResponse> response = mock(StreamObserver.class);
            service.scanGraphPartition(response, handler).onNext(request(0));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            Thread stopping = new Thread(() -> {
                service.stopAcceptingScans();
                stoppingStarted.countDown();
                service.shutdownScans();
                service.awaitScanCleanup();
                stopped.countDown();
            });
            stopping.start();
            assertTrue(stoppingStarted.await(2, TimeUnit.SECONDS));
            assertFalse(stopped.await(100, TimeUnit.MILLISECONDS));
            release.countDown();
            assertTrue(stopped.await(2, TimeUnit.SECONDS));
            stopping.join(2000);
            verify(iterator).close();
            verify(response).onError(any());
            assertTrue(registry(service).isEmpty());
        } finally {
            release.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testClientCompletionBeforeInitializationReleasesBarrier() throws Exception {
        ThreadPoolExecutor executor = executor();
        try {
            HgStoreStreamImpl service = service(executor);
            StreamObserver<ScanResponse> response = mock(StreamObserver.class);
            StreamObserver<ScanPartitionRequest> request =
                    service.scanGraphPartition(response, mock(BusinessHandler.class));
            request.onCompleted();
            request.onCompleted();
            verify(response).onError(any());
            assertTrue(registry(service).isEmpty());
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testReadFailureKeepsCloseFailureSuppressedAndSticky() throws Exception {
        ThreadPoolExecutor executor = executor();
        try {
            HgStoreStreamImpl service = service(executor);
            GraphStoreIterator<?> iterator = mock(GraphStoreIterator.class);
            RuntimeException primary = new IllegalStateException("read");
            RuntimeException cleanup = new IllegalArgumentException("close");
            when(iterator.hasNext()).thenThrow(primary);
            doThrow(cleanup).when(iterator).close();
            BusinessHandler handler = mock(BusinessHandler.class);
            doReturn(iterator).when(handler).scan(any());
            StreamObserver<ScanResponse> response = mock(StreamObserver.class);
            service.scanGraphPartition(response, handler).onNext(request(0));
            verify(response, timeout(2000)).onError(primary);
            assertEquals(1, primary.getSuppressed().length);
            assertSame(cleanup, primary.getSuppressed()[0]);
            verify(iterator).close();
            assertEquals(1, registry(service).size());
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testFullCreditWindowResumesWithOrderedPagesAndKeepsFinalPartialBatch() throws Exception {
        ThreadPoolExecutor executor = executor();
        HgStoreStreamImpl service = service(executor);
        final int batchSize = 100000;
        final int window = 8;
        final int total = batchSize * window + 7;
        AtomicInteger closes = new AtomicInteger();
        Field suppliers = org.apache.hugegraph.store.business.BusinessHandlerImpl.class
                .getDeclaredField("GRAPH_SUPPLIER_CACHE");
        suppliers.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, org.apache.hugegraph.HugeGraphSupplier> graphCache =
                (Map<String, org.apache.hugegraph.HugeGraphSupplier>) suppliers.get(null);
        String graphName = "TEST/credit-window";
        org.apache.hugegraph.HugeGraphSupplier previous =
                graphCache.put(graphName, mock(org.apache.hugegraph.HugeGraphSupplier.class));
        GraphStoreIterator<Graphpb.Vertex> iterator;
        try {
            iterator = new GraphStoreIterator<Graphpb.Vertex>(
                    mock(ScanIterator.class), request(0)) {
                private int row;

                @Override
                public boolean hasNext() {
                    return this.row < total;
                }

                @Override
                public Graphpb.Vertex next() {
                    return Graphpb.Vertex.newBuilder().setId(Graphpb.Variant.newBuilder()
                            .setType(Graphpb.VariantType.VT_LONG).setValueInt64(this.row++)).build();
                }

                @Override
                public void close() {
                    closes.incrementAndGet();
                }
            };
        } finally {
            if (previous == null) {
                graphCache.remove(graphName);
            } else {
                graphCache.put(graphName, previous);
            }
        }
        LinkedBlockingQueue<ScanResponse> responses = new LinkedBlockingQueue<>();
        AtomicInteger terminals = new AtomicInteger();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(1);
        StreamObserver<ScanResponse> response = new StreamObserver<ScanResponse>() {
            @Override
            public void onNext(ScanResponse value) {
                responses.add(value);
            }

            @Override
            public void onError(Throwable failure) {
                error.set(failure);
                terminals.incrementAndGet();
                finished.countDown();
            }

            @Override
            public void onCompleted() {
                terminals.incrementAndGet();
                finished.countDown();
            }
        };
        try {
            BusinessHandler handler = mock(BusinessHandler.class);
            doReturn(iterator).when(handler).scan(any());
            StreamObserver<ScanPartitionRequest> request = service.scanGraphPartition(response, handler);
            request.onNext(request(0));
            long expectedId = 0;
            for (int page = 0; page < window; page++) {
                ScanResponse value = responses.poll(20, TimeUnit.SECONDS);
                assertTrue("Expected page " + page, value != null);
                assertEquals(page, value.getSeqNo());
                assertEquals(batchSize, value.getVertexCount());
                for (Graphpb.Vertex vertex : value.getVertexList()) {
                    assertEquals(expectedId++, vertex.getId().getValueInt64());
                }
            }
            assertNull("No ninth page is allowed without credit", responses.poll(100, TimeUnit.MILLISECONDS));
            assertEquals(0, terminals.get());
            for (int page = 0; page < window; page++) {
                request.onNext(ScanPartitionRequest.newBuilder().setReplyRequest(
                        ScanPartitionRequest.Reply.newBuilder().setSeqNo(page)).build());
            }
            ScanResponse last = responses.poll(20, TimeUnit.SECONDS);
            assertTrue("The last partial batch must be delivered", last != null);
            assertEquals(window, last.getSeqNo());
            assertEquals(7, last.getVertexCount());
            for (Graphpb.Vertex vertex : last.getVertexList()) {
                assertEquals(expectedId++, vertex.getId().getValueInt64());
            }
            assertEquals(total, expectedId);
            assertTrue(finished.await(20, TimeUnit.SECONDS));
            assertNull(error.get());
            service.awaitScanCleanup();
            request.onCompleted();
            assertEquals(1, terminals.get());
            assertEquals(1, closes.get());
            assertTrue(responses.isEmpty());
            assertTrue(registry(service).isEmpty());
        } finally {
            service.shutdownScans();
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static ScanPartitionRequest request(long limit) {
        return ScanPartitionRequest.newBuilder().setScanRequest(Graphpb.ScanPartitionRequest.Request.newBuilder()
                .setGraphName("TEST/credit-window")
                .setScanType(Graphpb.ScanPartitionRequest.ScanType.SCAN_VERTEX).setLimit(limit)).build();
    }

    private static ThreadPoolExecutor executor() {
        return new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
    }

    private static HgStoreStreamImpl service(ThreadPoolExecutor executor) throws Exception {
        HgStoreStreamImpl service = new HgStoreStreamImpl();
        Field field = HgStoreStreamImpl.class.getDeclaredField("executor");
        field.setAccessible(true);
        field.set(service, executor);
        return service;
    }

    private static Map<?, ?> registry(HgStoreStreamImpl service) throws Exception {
        Field field = HgStoreStreamImpl.class.getDeclaredField("scans");
        field.setAccessible(true);
        return (Map<?, ?>) field.get(service);
    }
}
