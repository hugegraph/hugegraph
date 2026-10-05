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

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.hugegraph.store.business.BusinessHandler;
import org.apache.hugegraph.store.business.GraphStoreIterator;
import org.apache.hugegraph.store.grpc.Graphpb.ScanPartitionRequest;
import org.apache.hugegraph.store.grpc.Graphpb.ScanPartitionRequest.Request;
import org.apache.hugegraph.store.grpc.Graphpb.ScanResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;

public class ScanResponseObserverTest {

    private ThreadPoolExecutor executor;

    @BeforeEach
    public void setup() {
        this.executor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>());
    }

    @AfterEach
    public void teardown() {
        this.executor.shutdownNow();
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testRejectNonEmptyConditionBeforeStoreScan() {
        BusinessHandler handler = Mockito.mock(BusinessHandler.class);
        GraphStoreIterator<Object> iterator =
                Mockito.mock(GraphStoreIterator.class);
        Mockito.when(handler.scan(Mockito.any())).thenReturn(iterator);
        RecordingObserver sender = new RecordingObserver();
        ScanResponseObserver<Object> observer =
                new ScanResponseObserver<>(sender, handler, this.executor);

        observer.onNext(request("element.name() == 'marko'"));

        Assertions.assertNotNull(sender.error);
        Assertions.assertEquals(Status.Code.UNIMPLEMENTED,
                                Status.fromThrowable(sender.error).getCode());
        Assertions.assertTrue(Status.fromThrowable(sender.error)
                                    .getDescription().contains("condition"));
        Assertions.assertFalse(sender.completed);
        Mockito.verifyNoInteractions(handler);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testEmptyConditionKeepsExistingScanBehavior() {
        BusinessHandler handler = Mockito.mock(BusinessHandler.class);
        GraphStoreIterator<Object> iterator =
                Mockito.mock(GraphStoreIterator.class);
        Mockito.when(handler.scan(Mockito.any())).thenReturn(iterator);
        Mockito.when(iterator.hasNext()).thenReturn(false);
        RecordingObserver sender = new RecordingObserver();
        ScanResponseObserver<Object> observer =
                new ScanResponseObserver<>(sender, handler, this.executor);
        ScanPartitionRequest request = request("");

        observer.onNext(request);

        Assertions.assertNull(sender.error);
        Assertions.assertTrue(sender.completed);
        Mockito.verify(handler).scan(request);
        Mockito.verify(iterator).close();
    }

    private static ScanPartitionRequest request(String condition) {
        Request request = Request.newBuilder().setCondition(condition).build();
        return ScanPartitionRequest.newBuilder().setScanRequest(request).build();
    }

    private static final class RecordingObserver
            implements StreamObserver<ScanResponse> {

        private Throwable error;
        private boolean completed;

        @Override
        public void onNext(ScanResponse value) {
            // No response is expected from the empty iterator fixture.
        }

        @Override
        public void onError(Throwable throwable) {
            this.error = throwable;
        }

        @Override
        public void onCompleted() {
            this.completed = true;
        }
    }
}
