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

import java.util.ArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.hugegraph.store.business.BusinessHandler;
import org.apache.hugegraph.store.business.GraphStoreIterator;
import org.apache.hugegraph.store.grpc.Graphpb.Error;
import org.apache.hugegraph.store.grpc.Graphpb.ErrorType;
import org.apache.hugegraph.store.grpc.Graphpb.ResponseHeader;
import org.apache.hugegraph.store.grpc.Graphpb.ScanPartitionRequest;
import org.apache.hugegraph.store.grpc.Graphpb.ScanPartitionRequest.Request;
import org.apache.hugegraph.store.grpc.Graphpb.ScanPartitionRequest.ScanType;
import org.apache.hugegraph.store.grpc.Graphpb.ScanResponse;

import com.google.protobuf.Descriptors;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ScanResponseObserver<T> implements
                                     StreamObserver<ScanPartitionRequest> {

    private static final int BATCH_SIZE = 100000;
    private static final int MAX_PAGE = 8; //
    private static final Error ok = Error.newBuilder().setType(ErrorType.OK).build();
    private static final ResponseHeader okHeader =
            ResponseHeader.newBuilder().setError(ok).build();
    private final BusinessHandler handler;
    private final AtomicInteger nextSeqNo = new AtomicInteger(0);
    private final AtomicInteger cltSeqNo = new AtomicInteger(0);
    private final ThreadPoolExecutor executor;
    private final AtomicBoolean readOver = new AtomicBoolean(false);
    private final LinkedBlockingQueue<ScanResponse> packages =
            new LinkedBlockingQueue(MAX_PAGE * 2);
    private final Descriptors.FieldDescriptor vertexField =
            ScanResponse.getDescriptor().findFieldByNumber(3);
    private final Descriptors.FieldDescriptor edgeField =
            ScanResponse.getDescriptor().findFieldByNumber(4);
    private final AtomicBoolean reading = new AtomicBoolean();
    private final AtomicBoolean sending = new AtomicBoolean();
    private StreamObserver<ScanResponse> sender;
    private ScanPartitionRequest scanReq;
    private volatile GraphStoreIterator iter;
    private final AtomicBoolean terminated = new AtomicBoolean();
    private final AtomicBoolean iteratorClosed = new AtomicBoolean();
    private final AtomicBoolean initialized = new AtomicBoolean();
    private volatile long leftCount;
    private volatile Future<?> sendTask;
    private volatile Future<?> readTask;

    /*
     * November 1, 2022
     * 1. onNext needs to be processed asynchronously to prevent the grpc call from being blocked.
     * 2. Do not read iterators or send data do not produce thread waiting.
     * 3. Before sending, try to prepare the data to be sent as much as possible.
     * */

    /*
     * November 2, 2022
     * 1. Read the thread of rocksdb iterator read
     * 2. Perform data conversion and send to the blocking queue thread offer
     * 3. Thread for reading data from the blocking queue and sending, including waking up the
     * reading and sending threads when no data is read
     * */

    public ScanResponseObserver(StreamObserver<ScanResponse> sender,
                                BusinessHandler handler,
                                ThreadPoolExecutor executor) {
        this.sender = sender;
        this.handler = handler;
        this.executor = executor;
    }

    private boolean readCondition() {
        return iter != null && !terminated.get() && packages.remainingCapacity() != 0 && !readOver.get();
    }

    private boolean sendCondition() {
        return !terminated.get() && nextSeqNo.get() - cltSeqNo.get() < MAX_PAGE;
    }

    private void offer(Iterable<T> data, boolean isVertex) {
        if (terminated.get()) {
            return;
        }
        ScanResponse.Builder builder = ScanResponse.newBuilder();
        builder.setHeader(okHeader).setSeqNo(nextSeqNo.get());
        if (isVertex) {
            builder = builder.setField(vertexField, data);
        } else {
            builder = builder.setField(edgeField, data);
        }
        ScanResponse response = builder.build();
        packages.offer(response);
        startSend();
    }

    private void startRead() {
        if (readCondition() && reading.compareAndSet(false, true)) {
            try {
                readTask = executor.submit(rr);
            } catch (RuntimeException error) {
                reading.set(false);
                fail(error);
            }
        }
    }

    private void startSend() {
        if (sendCondition() && (!packages.isEmpty() || readOver.get()) &&
            sending.compareAndSet(false, true)) {
            try {
                sendTask = executor.submit(sr);
            } catch (RuntimeException error) {
                sending.set(false);
                fail(error);
            }
        }
    }

    @Override
    public void onNext(ScanPartitionRequest scanReq) {
        if (terminated.get()) {
            return;
        }
        try {
            this.accept(scanReq);
        } catch (Exception error) {
            this.fail(error);
        }
    }

    private void accept(ScanPartitionRequest scanReq) {
        if (terminated.get()) {
            return;
        }
        if (scanReq.hasScanRequest() && !scanReq.hasReplyRequest()) {
            if (!initialized.compareAndSet(false, true)) {
                throw new IllegalArgumentException("A scan stream accepts one scan request");
            }
            this.scanReq = scanReq;
            Request request = scanReq.getScanRequest();
            long rl = request.getLimit();
            leftCount = rl > 0 ? rl : Long.MAX_VALUE;
            iter = handler.scan(scanReq);
            if (terminated.get()) {
                close();
                return;
            }
            boolean available;
            synchronized (iter) {
                if (terminated.get()) {
                    return;
                }
                available = iter.hasNext();
            }
            if (!available) {
                complete();
            } else {
                startRead();
            }
        } else {
            cltSeqNo.getAndIncrement();
            startSend();
        }
    }

    @Override
    public void onError(Throwable t) {
        close();
        log.warn("receive client error:", t);
    }

    @Override
    public void onCompleted() {
        close();
    }

    private void complete() {
        boolean notify = terminated.compareAndSet(false, true);
        close();
        if (notify) {
            synchronized (sender) {
                sender.onCompleted();
            }
        }
    }

    private void fail(Throwable error) {
        boolean notify = terminated.compareAndSet(false, true);
        close();
        if (notify) {
            synchronized (sender) {
                Status status = error instanceof IllegalArgumentException ?
                        Status.INVALID_ARGUMENT : Status.INTERNAL;
                sender.onError(status.withDescription("Store scan failed")
                                     .withCause(error).asRuntimeException());
            }
        }
    }

    private void send(ScanResponse response) {
        synchronized (sender) {
            if (!terminated.get()) {
                sender.onNext(response.toBuilder().setSeqNo(nextSeqNo.get()).build());
                nextSeqNo.incrementAndGet();
            }
        }
    }

    private void close() {
        terminated.set(true);
        try {
            nextSeqNo.set(0);
            if (sendTask != null) {
                sendTask.cancel(true);
            }
            if (readTask != null) {
                readTask.cancel(true);
            }
            readOver.set(true);
            packages.clear();
            GraphStoreIterator closing = iter;
            if (closing != null && iteratorClosed.compareAndSet(false, true)) {
                synchronized (closing) {
                    closing.close();
                }
            }
        } catch (Exception e) {
            log.warn("on Complete with error:", e);
        }
    }

    Runnable rr = new Runnable() {
        @Override
        public void run() {
            try {
                while (readCondition()) {
                    ArrayList<T> data = new ArrayList<>(BATCH_SIZE);
                    boolean finished;
                    synchronized (iter) {
                        if (terminated.get()) {
                            return;
                        }
                        while (leftCount > 0 && data.size() < BATCH_SIZE && iter.hasNext()) {
                            if (terminated.get()) {
                                return;
                            }
                            leftCount--;
                            data.add((T) iter.next());
                        }
                        finished = leftCount <= 0 || data.size() < BATCH_SIZE;
                    }
                    // Scheduling, callbacks and terminal cleanup never hold the iterator lock.
                    if (!data.isEmpty()) {
                        offer(data, scanReq.getScanRequest().getScanType().equals(ScanType.SCAN_VERTEX));
                    }
                    if (finished) {
                        readOver.set(true);
                        return;
                    }
                }
            } catch (Exception e) {
                log.warn("read data with error: ", e);
                fail(e);
            } finally {
                reading.set(false);
                startRead();
                startSend();
            }
        }
    };

    Runnable sr = () -> {
        try {
            while (sendCondition()) {
                ScanResponse response;
                try {
                    if (readOver.get()) {
                        if ((response = packages.poll()) == null) {
                            complete();
                            return;
                        } else {
                            send(response);
                        }
                    } else {
                        response = packages.poll(10,
                                                 TimeUnit.MILLISECONDS);
                        if (response != null) {
                            send(response);
                            startRead();
                        } else {
                            break;
                        }
                    }

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception error) {
                    fail(error);
                    break;
                }
            }
        } finally {
            sending.set(false);
            startRead();
            startSend();
        }
    };
}
