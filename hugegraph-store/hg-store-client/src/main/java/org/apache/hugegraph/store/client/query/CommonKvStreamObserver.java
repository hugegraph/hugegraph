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

package org.apache.hugegraph.store.client.query;

import java.util.Iterator;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

import org.apache.hugegraph.store.client.type.HgStoreClientException;

import io.grpc.stub.StreamObserver;
import lombok.Data;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class CommonKvStreamObserver<R, T> implements StreamObserver<R> {

    /**
     * Queue to store result
     */
    private final BlockingQueue<Iterator<T>> queue;

    /**
     * Function that send requests to server
     */
    @Setter
    private Consumer<Boolean> requestSender;

    /**
     * Handling the case that server has no results, close channel
     */
    @Setter
    private Consumer<Boolean> transferComplete;

    /**
     * Parser for data returned from server
     */
    private final Function<R, Iterator<T>> valueExtractor;
    /**
     * Parser for data state returned from server
     */
    private final Function<R, ResultState> stateWatcher;

    /**
     * It can be ended by the client to stop receiving redundant data.
     */
    private final AtomicBoolean closed = new AtomicBoolean(false);
    // Outgoing RPC calls must not hold the monitor used by incoming callbacks.
    private final Object requestLock = new Object();

    @Setter
    private long timeout = 1800 * 1000;

    /**
     * Monitor internal state
     */
    private final ResultStateWatcher watcher = new ResultStateWatcher();

    public CommonKvStreamObserver(Function<R, Iterator<T>> valueExtractor,
                                  Function<R, ResultState> stateWatcher) {
        this.queue = new LinkedBlockingQueue<>();
        this.valueExtractor = valueExtractor;
        this.stateWatcher = stateWatcher;
    }

    /**
     * Send requests
     */
    public void sendRequest() {
        synchronized (this.requestLock) {
            synchronized (this) {
                if (isServerFinished() || this.closed.get()) {
                    return;
                }
                this.watcher.setState(ResultState.WAITING);
            }
            this.requestSender.accept(true);
        }
    }

    public synchronized boolean isServerFinished() {
        return this.watcher.getState() == ResultState.FINISHED
               || this.watcher.getState() == ResultState.ERROR;
    }

    @Override
    public synchronized void onNext(R value) {
        watcher.setState(ResultState.INNER_BUSY);
        try {
            var state = stateWatcher.apply(value);
            log.debug("observer state: {}", state);

            switch (state) {
                case IDLE:
                case FINISHED:
                    if (!this.closed.get()) {
                        queue.offer(this.valueExtractor.apply(value));
                    }
                    // this.stop();
                    break;
                default:
                    queue.offer(new ErrorMessageIterator<>(state.getMessage()));
                    break;
            }
            watcher.setState(state);
            // sendRequest();
        } catch (Exception e) {
            log.error("handling server data, got error: ", e);
            queue.offer(new ErrorMessageIterator<>(e.getMessage()));
        }
    }

    public Iterator<T> consume() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                var iterator = this.queue.poll(200, TimeUnit.MILLISECONDS);
                if (iterator != null) {
                    sendRequest();
                    return iterator;
                }

                // Read terminal state before the queue: a final batch is published with that state.
                if (isServerFinished() && this.queue.isEmpty()) {
                    return null;
                }
                if ((System.nanoTime() - watcher.current) / 1000_000 > this.timeout) {
                    throw new HgStoreClientException("iterator timeout");
                }

            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return null;
    }

    /**
     * Stop feedback and invoke the request stream's completion or cancellation callback.
     */
    public void clear() {
        synchronized (this.requestLock) {
            try {
                if (this.closed.compareAndSet(false, true)) {
                    this.transferComplete.accept(true);
                }
            } finally {
                this.queue.clear();
            }
        }
    }

    @Override
    public synchronized void onError(Throwable t) {
        log.error("StreamObserver got error:", t);
        this.queue.offer(new ErrorMessageIterator<>(t.getMessage()));
        this.watcher.setState(ResultState.ERROR);
    }

    @Override
    public synchronized void onCompleted() {
        if (watcher.getState() != ResultState.ERROR) {
            watcher.setState(ResultState.FINISHED);
        }
    }

    public void setWatcherQueryId(String queryId) {
        this.watcher.setQueryId(queryId);
    }

    @Data
    private static class ResultStateWatcher {

        private long current = System.nanoTime();
        private volatile ResultState state = ResultState.IDLE;

        private String queryId;

        public void setState(ResultState state) {
            log.debug("query Id: {}, COST_STAT: {} -> {}, cost {} ms", this.queryId, this.state,
                      state,
                      +(System.nanoTime() - current) * 1.0 / 1000000);
            this.state = state;
            this.current = System.nanoTime();
        }
    }
}
