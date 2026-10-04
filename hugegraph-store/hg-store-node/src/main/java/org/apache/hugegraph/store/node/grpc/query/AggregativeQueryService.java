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

package org.apache.hugegraph.store.node.grpc.query;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.hugegraph.rocksdb.access.RocksDBSession;
import org.apache.hugegraph.store.HgStoreEngine;
import org.apache.hugegraph.store.consts.PoolNames;
import org.apache.hugegraph.store.grpc.common.Kv;
import org.apache.hugegraph.store.grpc.query.QueryRequest;
import org.apache.hugegraph.store.grpc.query.QueryResponse;
import org.apache.hugegraph.store.grpc.query.QueryServiceGrpc;
import org.apache.hugegraph.store.query.KvSerializer;
import org.apache.hugegraph.store.util.ExecutorUtil;
import org.lognet.springboot.grpc.GRpcService;

import com.google.protobuf.ByteString;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@GRpcService
public class AggregativeQueryService extends QueryServiceGrpc.QueryServiceImplBase {

    private final Set<AggregativeQueryObserver> queries = new HashSet<>();
    private boolean closing;

    private final int batchSize;

    private final Long timeout;

    @Getter
    private final ThreadPoolExecutor threadPool;

    public AggregativeQueryService() {
        var queryPushDownOption = HgStoreEngine.getInstance().getOption().getQueryPushDownOption();

        timeout = queryPushDownOption.getFetchTimeout();
        batchSize = queryPushDownOption.getFetchBatchSize();

        this.threadPool = ExecutorUtil.createExecutor(PoolNames.SCAN_V2,
                                                      Runtime.getRuntime().availableProcessors(),
                                                      queryPushDownOption.getThreadPoolSize(),
                                                      10000, true);
    }

    AggregativeQueryService(ThreadPoolExecutor threadPool, long timeout, int batchSize) {
        this.threadPool = threadPool;
        this.timeout = timeout;
        this.batchSize = batchSize;
    }

    public synchronized void stopAcceptingQueries() {
        this.closing = true;
    }

    /** Cancel queries, but let every queued parent and partition release its iterator. */
    public void shutdownQueries() {
        AggregativeQueryObserver[] active;
        synchronized (this) {
            this.closing = true;
            active = this.queries.toArray(new AggregativeQueryObserver[0]);
        }
        for (AggregativeQueryObserver query : active) {
            try {
                query.cancel();
            } catch (RuntimeException | Error failure) {
                // Response callbacks cannot skip cancellation or cleanup waits for other queries.
                log.error("Failed to cancel aggregate query response; continuing shutdown", failure);
            }
        }
        boolean interrupted = false;
        synchronized (this) {
            try {
                while (!this.queries.isEmpty()) {
                    try {
                        this.wait(5000);
                        if (!this.queries.isEmpty()) {
                            log.warn("Waiting for {} aggregate queries to release iterators",
                                     this.queries.size());
                        }
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        // Parents can still enqueue partition cleanup until the last query is finished.
        this.threadPool.shutdown();
    }

    private synchronized void finished(AggregativeQueryObserver observer) {
        this.queries.remove(observer);
        this.notifyAll();
    }

    AggregativeQueryObserver newObserver(StreamObserver<QueryResponse> sender) {
        return new AggregativeQueryObserver(sender, this.threadPool, this.timeout,
                                            this.batchSize, this::finished);
    }

    /**
     * Generate error response.
     *
     * @param queryId query identifier
     * @param t       exception object
     * @return query response object
     */
    public static QueryResponse errorResponse(QueryResponse.Builder builder, String queryId,
                                              Throwable t) {
        return builder.setQueryId(queryId)
                      .setIsOk(false)
                      .setIsFinished(false)
                      .setMessage(t.getMessage() == null ? "" : t.getMessage())
                      .build();
    }

    @Override
    public synchronized StreamObserver<QueryRequest> query(StreamObserver<QueryResponse> observer) {
        if (this.closing) {
            throw Status.UNAVAILABLE.withDescription("Store queries are stopping").asRuntimeException();
        }
        AggregativeQueryObserver query = newObserver(observer);
        this.queries.add(query);
        return query;
    }

    @Override
    public void query0(QueryRequest request, StreamObserver<QueryResponse> observer) {

        var itr = QueryUtil.getIterator(request);
        var builder = QueryResponse.newBuilder();
        var kvBuilder = Kv.newBuilder();

        try {
            while (itr.hasNext()) {
                var column = (RocksDBSession.BackendColumn) itr.next();
                if (column != null) {
                    builder.addData(kvBuilder.setKey(ByteString.copyFrom(column.name))
                                             .setValue(column.value == null ? ByteString.EMPTY :
                                                       ByteString.copyFrom(column.value))
                                             .build());
                }
            }
            builder.setQueryId(request.getQueryId());
            builder.setIsOk(true);
            builder.setIsFinished(true);
            observer.onNext(builder.build());
        } catch (Exception e) {
            observer.onNext(errorResponse(builder, request.getQueryId(), e));
        }
        observer.onCompleted();
    }

    /**
     * Query data count
     *
     * @param request  query request object
     * @param observer Observer object for receiving query response results
     */
    @Override
    public void count(QueryRequest request, StreamObserver<QueryResponse> observer) {

        log.debug("query id : {}, simple count of table: {}", request.getQueryId(),
                  request.getTable());
        var builder = QueryResponse.newBuilder();
        var kvBuilder = Kv.newBuilder();

        try {

            var handler = new QueryUtil().getHandler();
            long start = System.currentTimeMillis();
            long count = handler.count(request.getGraph(), request.getTable());
            log.debug("query id: {}, count of cost: {} ms", request.getQueryId(),
                      System.currentTimeMillis() - start);
            List<Object> array = new ArrayList<>();
            for (int i = 0; i < request.getFunctionsList().size(); i++) {
                array.add(new AtomicLong(count));
            }

            kvBuilder.setKey(ByteString.copyFrom(KvSerializer.toBytes(List.of())));
            kvBuilder.setValue(ByteString.copyFrom(KvSerializer.toBytes(array)));
            builder.addData(kvBuilder.build());
            builder.setQueryId(request.getQueryId());
            builder.setIsOk(true);
            builder.setIsFinished(true);
            observer.onNext(builder.build());
        } catch (Exception e) {
            observer.onNext(errorResponse(builder, request.getQueryId(), e));
        }
        observer.onCompleted();
    }
}
