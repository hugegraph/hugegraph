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

package org.apache.hugegraph.meta.managers;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import org.apache.hugegraph.exception.HugeException;
import org.apache.hugegraph.meta.MetaDriver;
import org.apache.hugegraph.meta.PdMetaDriver;
import org.apache.hugegraph.pd.grpc.kv.TxnCompare;
import org.apache.hugegraph.pd.grpc.kv.TxnOp;
import org.apache.hugegraph.pd.grpc.kv.TxnRecord;
import org.apache.hugegraph.pd.grpc.kv.TxnRequest;
import org.apache.hugegraph.pd.grpc.kv.TxnResponse;
import org.apache.hugegraph.util.JsonUtil;
import org.mockito.Mockito;

/**
 * An in-memory PD behind a mocked {@link MetaDriver}: get, put and commit, with a commit
 * applied the way PD's KvTxnApplier applies a TXN (an answer kept by request id, compares, the
 * graph record check, then the ops and the record in one step). Several SchemaMetaManagers on
 * one FakeSchemaSyncPd stand for several Servers on one PD.
 */
public class FakeSchemaSyncPd {

    private final Map<String, String> kvs = new TreeMap<>();
    private final Map<String, TxnResponse> outcomes = new HashMap<>();
    private long revision;
    private Runnable beforeNextCommit;
    private boolean resendNextCommit;
    private boolean loseNextCommit;

    public MetaDriver driver() {
        MetaDriver driver = Mockito.mock(PdMetaDriver.class);
        Mockito.when(driver.get(Mockito.anyString()))
               .thenAnswer(invocation -> this.get(invocation.getArgument(0)));
        Mockito.doAnswer(invocation -> {
            this.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(driver).put(Mockito.anyString(), Mockito.anyString());
        Mockito.when(driver.commit(Mockito.any()))
               .thenAnswer(invocation -> this.commit(invocation.getArgument(0)));
        return driver;
    }

    /**
     * As PD's KV get, "" for an absent key
     */
    public synchronized String get(String key) {
        return this.kvs.getOrDefault(key, "");
    }

    public synchronized void put(String key, String value) {
        this.kvs.put(key, value);
    }

    /**
     * Runs the action, such as a write of another Server, just before the next commit applies
     */
    public synchronized void beforeNextCommit(Runnable action) {
        this.beforeNextCommit = action;
    }

    /**
     * Applies the next commit, then gets it again and returns only the second response, as
     * when PD applied a TXN and its response was lost, so the client sent it again
     */
    public synchronized void resendNextCommit() {
        this.resendNextCommit = true;
    }

    /**
     * Applies the next commit and fails it on the caller, as when PD applied a TXN and the
     * client gave up after it lost every response
     */
    public synchronized void loseNextCommit() {
        this.loseNextCommit = true;
    }

    public synchronized TxnResponse commit(TxnRequest request) {
        if (this.resendNextCommit) {
            this.resendNextCommit = false;
            this.apply(request);
        }
        TxnResponse response = this.apply(request);
        if (this.loseNextCommit) {
            this.loseNextCommit = false;
            throw new HugeException("Failed to commit a txn of %s ops to pd",
                                    request.getOpsCount());
        }
        return response;
    }

    private TxnResponse apply(TxnRequest request) {
        Runnable action = this.beforeNextCommit;
        this.beforeNextCommit = null;
        if (action != null) {
            action.run();
        }
        long index = ++this.revision;
        TxnResponse outcome = this.outcomes.get(request.getRequestId());
        if (outcome == null) {
            outcome = this.evaluate(request, index);
            if (!request.getRequestId().isEmpty()) {
                this.outcomes.put(request.getRequestId(), outcome);
            }
        }
        return outcome;
    }

    @SuppressWarnings("unchecked")
    private TxnResponse evaluate(TxnRequest request, long index) {
        TxnResponse.Builder response = TxnResponse.newBuilder();
        for (int i = 0; i < request.getComparesCount(); i++) {
            TxnCompare compare = request.getCompares(i);
            String stored = this.kvs.get(compare.getKey());
            boolean matches = compare.getKind() == TxnCompare.Kind.ABSENT ? stored == null :
                              compare.getValue().equals(stored);
            if (!matches) {
                return response.setFailure(TxnResponse.Failure.COMPARE_FAILED)
                               .setFailedCompare(i).build();
            }
        }

        String recordValue = null;
        long inc = 0L;
        String state = "";
        if (request.hasRecord()) {
            TxnRecord record = request.getRecord();
            String stored = this.kvs.get(record.getKey());
            Map<String, Object> value = stored == null ? null :
                                        JsonUtil.fromJson(stored, Map.class);
            long storedInc = value == null ? 0L : ((Number) value.get("inc")).longValue();
            String storedState = value == null ? "" : (String) value.get("state");
            long expected = record.getExpectedIncarnation();
            TxnResponse.Failure failure = TxnResponse.Failure.NONE;
            switch (record.getOp()) {
                case CREATE:
                    if (value != null && !"DROPPED".equals(storedState)) {
                        failure = TxnResponse.Failure.GRAPH_EXISTS;
                    }
                    break;
                case BUMP:
                    if ("DROPPED".equals(storedState)) {
                        failure = TxnResponse.Failure.GRAPH_DROPPED;
                    } else if (expected != 0L && expected != storedInc) {
                        failure = TxnResponse.Failure.INCARNATION_MISMATCH;
                    }
                    break;
                case DROP:
                case COMMIT_CREATE:
                    if (expected != storedInc) {
                        failure = TxnResponse.Failure.INCARNATION_MISMATCH;
                    } else if ("DROPPED".equals(storedState)) {
                        failure = TxnResponse.Failure.GRAPH_DROPPED;
                    } else if (record.getOp() == TxnRecord.Op.COMMIT_CREATE &&
                               !"CREATING".equals(storedState)) {
                        failure = TxnResponse.Failure.GRAPH_EXISTS;
                    }
                    break;
                case ADOPT:
                    if (value != null) {
                        return response.setSucceeded(true)
                                       .setRevision(((Number) value.get("rev")).longValue())
                                       .setIncarnation(storedInc).setState(storedState).build();
                    }
                    break;
                default:
                    throw new AssertionError("Unknown TXN record op " + record.getOp());
            }
            if (failure != TxnResponse.Failure.NONE) {
                return response.setFailure(failure).setIncarnation(storedInc)
                               .setState(storedState).build();
            }
            inc = value == null ? 1L : storedInc;
            switch (record.getOp()) {
                case CREATE:
                    inc = value == null ? 1L : storedInc + 1L;
                    state = "CREATING";
                    break;
                case BUMP:
                    state = value == null ? "LIVE" : storedState;
                    break;
                case DROP:
                    state = "DROPPED";
                    break;
                default:
                    state = "LIVE";
                    break;
            }
            recordValue = String.format("{\"rev\":%s,\"inc\":%s,\"state\":\"%s\"}",
                                        index, inc, state);
        }

        for (TxnOp op : request.getOpsList()) {
            switch (op.getType()) {
                case PUT:
                    this.kvs.put(op.getKey(), op.getValue());
                    break;
                case DELETE:
                    this.kvs.remove(op.getKey());
                    break;
                case DELETE_PREFIX:
                    this.kvs.keySet().removeIf(key -> key.startsWith(op.getKey()));
                    break;
                default:
                    throw new AssertionError("Unknown TXN op " + op.getType());
            }
        }
        if (recordValue != null) {
            this.kvs.put(request.getRecord().getKey(), recordValue);
        }
        return response.setSucceeded(true).setRevision(index).setIncarnation(inc)
                       .setState(state).build();
    }
}
