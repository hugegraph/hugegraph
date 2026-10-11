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

package org.apache.hugegraph.pd.store;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.hugegraph.pd.KvService;
import org.apache.hugegraph.pd.common.PDException;
import org.apache.hugegraph.pd.grpc.Pdpb;
import org.apache.hugegraph.pd.grpc.kv.TxnCompare;
import org.apache.hugegraph.pd.grpc.kv.TxnOp;
import org.apache.hugegraph.pd.grpc.kv.TxnRecord;
import org.apache.hugegraph.pd.grpc.kv.TxnRequest;
import org.apache.hugegraph.pd.grpc.kv.TxnResponse;
import org.apache.hugegraph.pd.grpc.kv.V;

import com.google.common.primitives.Bytes;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.protobuf.InvalidProtocolBufferException;

import lombok.extern.slf4j.Slf4j;

/**
 * Applies a KV TXN to a local store. Every raft node runs it on the apply thread with the
 * entry's log index, so it must be deterministic: it reads only the store and the request,
 * never the clock (TTL expiry is ignored), and a failed compare or a rejected record is a
 * result, not an exception.
 * <p>
 * Keys and values use the encoding of {@link KvService#put(String, String)}, so KvService get
 * and scan read what a TXN writes.
 * <p>
 * Resend: a client that lost the response of a TXN sends it again with the same request id.
 * The outcome of each request id is stored with the TXN, and a TXN whose id has an outcome is
 * answered with it and not applied again. Outcomes are kept for {@link #REQUEST_WINDOW} raft
 * entries and then deleted by the TXN that passes that point, so every node keeps the same ones.
 * <p>
 * Replay: jraft applies the entries after the last snapshot again on restart, and without a
 * snapshot that is over the store that holds them. {@link #LAST_INDEX_KEY} is written with
 * every TXN and holds its index, the last applied index of the store as far as TXNs go, and
 * RaftKVStore skips every store write (TXN or plain) at or below it: all of them were applied
 * before it. Skipping only the TXNs would not do, since a plain put replayed before a skipped
 * TXN, such as a schema write from before the upgrade, would overwrite what the TXN wrote. Plain
 * writes after the last TXN are blind writes and replay in order to the same store.
 */
@Slf4j
public final class KvTxnApplier {

    public static final String CREATING = "CREATING";
    public static final String LIVE = "LIVE";
    public static final String DROPPED = "DROPPED";

    /**
     * Raw store key outside the K@ space of KvService, so no KV call reads or writes it
     */
    public static final byte[] LAST_INDEX_KEY =
            "__TXN_LAST_INDEX".getBytes(StandardCharsets.UTF_8);

    /**
     * How many raft entries the outcome of a request id is kept for
     */
    public static final long REQUEST_WINDOW = 100_000L;

    private static final int MAX_REQUEST_ID = 64;
    // Raw keys as well: the outcome by request id, and the request id by raft index
    private static final String REQUEST_PREFIX = "__TXN_REQUEST/";
    private static final String REQUEST_INDEX_PREFIX = "__TXN_REQUEST_AT/";

    private static final Gson GSON = new Gson();

    /**
     * Stands for a stored record that can't be read
     */
    private static final GraphRecord INVALID = new GraphRecord(0L, 0L, null);

    private static volatile TxnApplyListener listener;

    private KvTxnApplier() {
    }

    /**
     * The registration point of schema sync dispatch; null (the default) calls nothing
     */
    public static void setListener(TxnApplyListener txnListener) {
        listener = txnListener;
    }

    /**
     * Rejects what the apply could not handle, before the TXN is proposed
     */
    public static void validate(TxnRequest request) throws PDException {
        for (TxnCompare compare : request.getComparesList()) {
            if (compare.getKey().isEmpty()) {
                throw invalid("a TXN compare needs a key");
            }
            if (compare.getKind() == TxnCompare.Kind.UNRECOGNIZED) {
                throw invalid("unknown compare kind on key " + compare.getKey());
            }
        }
        for (TxnOp op : request.getOpsList()) {
            // An empty key is the whole KV space to a DELETE_PREFIX
            if (op.getKey().isEmpty()) {
                throw invalid("a TXN op needs a key");
            }
            if (op.getType() == TxnOp.Type.UNRECOGNIZED) {
                throw invalid("unknown op type on key " + op.getKey());
            }
        }
        if (request.getRequestId().length() > MAX_REQUEST_ID) {
            throw invalid("a TXN request id has at most " + MAX_REQUEST_ID + " chars");
        }
        if (!request.hasRecord()) {
            return;
        }
        TxnRecord record = request.getRecord();
        if (record.getKey().isEmpty() || record.getOp() == TxnRecord.Op.UNRECOGNIZED) {
            throw invalid("a TXN record needs a key and a known op");
        }
        if ((record.getOp() == TxnRecord.Op.DROP ||
             record.getOp() == TxnRecord.Op.COMMIT_CREATE) &&
            record.getExpectedIncarnation() <= 0L) {
            throw invalid(record.getOp() + " needs the expected incarnation");
        }
        if (record.getOp() == TxnRecord.Op.ADOPT &&
            (request.getComparesCount() > 0 || request.getOpsCount() > 0)) {
            throw invalid("ADOPT takes no compares and no ops");
        }
    }

    public static long lastIndex(HgKVStore store) throws PDException {
        byte[] bytes = store.get(LAST_INDEX_KEY);
        if (bytes == null || bytes.length == 0) {
            return 0L;
        }
        return Long.parseLong(new String(bytes, StandardCharsets.UTF_8));
    }

    public static TxnResponse apply(HgKVStore store, TxnRequest request, long index)
            throws PDException {
        return apply(store, request, index, listener);
    }

    public static TxnResponse apply(HgKVStore store, TxnRequest request, long index,
                                    TxnApplyListener txnListener) throws PDException {
        if (lastIndex(store) >= index) {
            // The store holds this entry already, and no caller waits on a replay
            return TxnResponse.getDefaultInstance();
        }
        // Written whatever the outcome, so that a replay does not run the TXN again
        List<KV> batch = new ArrayList<>();
        batch.add(new KV(LAST_INDEX_KEY, Long.toString(index).getBytes(StandardCharsets.UTF_8)));
        Set<String> expired = expireRequests(store, index, batch);

        String requestId = request.getRequestId();
        byte[] requestKey = requestId.isEmpty() ? null : bytes(REQUEST_PREFIX + requestId);
        byte[] outcome = requestKey == null || expired.contains(requestId) ?
                         null : store.get(requestKey);
        if (outcome != null && outcome.length > 0) {
            // A resend of a TXN that was applied: the outcome it had then
            store.writeBatch(batch);
            try {
                return TxnResponse.parseFrom(outcome);
            } catch (InvalidProtocolBufferException e) {
                throw new PDException(Pdpb.ErrorType.UNKNOWN_VALUE, e);
            }
        }

        TxnResponse response = evaluate(store, request, index, batch);
        // Not for an ADOPT that found the record, which writes nothing
        boolean recordWritten = response.getSucceeded() && request.hasRecord() &&
                                response.getRevision() == index;
        if (requestKey != null) {
            batch.add(new KV(requestKey, response.toByteArray()));
            batch.add(new KV(requestIndexKey(index), bytes(requestId)));
        }
        store.writeBatch(batch);

        if (recordWritten) {
            notify(txnListener, index, request.getRecord().getKey(), new GraphRecord(
                    index, response.getIncarnation(), response.getState()));
        }
        return response;
    }

    /**
     * Runs the compares and the record check, and adds the writes of a TXN that passes them
     * to the batch
     */
    private static TxnResponse evaluate(HgKVStore store, TxnRequest request, long index,
                                        List<KV> batch) throws PDException {
        TxnResponse.Builder response = TxnResponse.newBuilder();
        for (int i = 0; i < request.getComparesCount(); i++) {
            if (!matches(store, request.getCompares(i))) {
                return response.setFailure(TxnResponse.Failure.COMPARE_FAILED)
                               .setFailedCompare(i).build();
            }
        }

        byte[] recordKey = null;
        GraphRecord record = null;
        if (request.hasRecord()) {
            recordKey = KvService.getKeyBytes(request.getRecord().getKey());
            GraphRecord stored = readRecord(store.get(recordKey));
            TxnResponse.Failure failure = stored == INVALID ?
                                          TxnResponse.Failure.INVALID_RECORD :
                                          check(request.getRecord(), stored);
            if (failure != TxnResponse.Failure.NONE) {
                return response.setFailure(failure)
                               .setIncarnation(stored == null ? 0L : stored.inc)
                               .setState(stored == null || stored.state == null ?
                                         "" : stored.state).build();
            }
            if (request.getRecord().getOp() == TxnRecord.Op.ADOPT && stored != null) {
                // The record as it is, nothing to write
                return response.setSucceeded(true).setRevision(stored.rev)
                               .setIncarnation(stored.inc).setState(stored.state).build();
            }
            record = next(request.getRecord().getOp(), stored, index);
        }

        int first = batch.size();
        for (TxnOp op : request.getOpsList()) {
            byte[] key = KvService.getKeyBytes(op.getKey());
            switch (op.getType()) {
                case PUT:
                    batch.add(new KV(key, wrap(op.getValue())));
                    break;
                case DELETE:
                    batch.add(new KV(key, null));
                    break;
                case DELETE_PREFIX:
                    // Earlier ops of this TXN are not in the store yet
                    batch.subList(first, batch.size())
                         .removeIf(kv -> Bytes.indexOf(kv.getKey(), key) == 0);
                    for (KV kv : store.scanPrefix(key)) {
                        batch.add(new KV(kv.getKey(), null));
                    }
                    break;
                default:
                    throw new AssertionError("Unknown TXN op " + op.getType());
            }
        }
        if (record != null) {
            batch.add(new KV(recordKey, wrap(GSON.toJson(record))));
            response.setIncarnation(record.inc).setState(record.state);
        }
        return response.setSucceeded(true).setRevision(index).build();
    }

    private static TxnResponse.Failure check(TxnRecord request, GraphRecord stored) {
        long expected = request.getExpectedIncarnation();
        switch (request.getOp()) {
            case ADOPT:
                return TxnResponse.Failure.NONE;
            case CREATE:
                // The name is taken while the graph is CREATING or LIVE, so two creates can't
                // both succeed
                return stored != null && !DROPPED.equals(stored.state) ?
                       TxnResponse.Failure.GRAPH_EXISTS : TxnResponse.Failure.NONE;
            case BUMP:
                if (stored != null && DROPPED.equals(stored.state)) {
                    return TxnResponse.Failure.GRAPH_DROPPED;
                }
                return expected != 0L && (stored == null || stored.inc != expected) ?
                       TxnResponse.Failure.INCARNATION_MISMATCH : TxnResponse.Failure.NONE;
            case DROP:
            case COMMIT_CREATE:
                if (stored == null || stored.inc != expected) {
                    return TxnResponse.Failure.INCARNATION_MISMATCH;
                }
                if (DROPPED.equals(stored.state)) {
                    return TxnResponse.Failure.GRAPH_DROPPED;
                }
                return request.getOp() == TxnRecord.Op.COMMIT_CREATE &&
                       !CREATING.equals(stored.state) ?
                       TxnResponse.Failure.GRAPH_EXISTS : TxnResponse.Failure.NONE;
            default:
                throw new AssertionError("Unknown TXN record op " + request.getOp());
        }
    }

    private static GraphRecord next(TxnRecord.Op op, GraphRecord stored, long index) {
        switch (op) {
            case BUMP:
                // An absent record is a graph created before records existed: incarnation 1
                return stored == null ? new GraphRecord(index, 1L, LIVE) :
                       new GraphRecord(index, stored.inc, stored.state);
            case ADOPT:
                return new GraphRecord(index, 1L, LIVE);
            case CREATE:
                return new GraphRecord(index, stored == null ? 1L : stored.inc + 1L, CREATING);
            case DROP:
                return new GraphRecord(index, stored.inc, DROPPED);
            case COMMIT_CREATE:
                return new GraphRecord(index, stored.inc, LIVE);
            default:
                throw new AssertionError("Unknown TXN record op " + op);
        }
    }

    /**
     * Adds the deletes of the request outcomes that left the window to the batch
     *
     * @return their request ids
     */
    private static Set<String> expireRequests(HgKVStore store, long index, List<KV> batch) {
        if (index <= REQUEST_WINDOW) {
            return Collections.emptySet();
        }
        Set<String> expired = new HashSet<>();
        for (KV kv : store.scanRange(requestIndexKey(0L),
                                     requestIndexKey(index - REQUEST_WINDOW))) {
            String requestId = new String(kv.getValue(), StandardCharsets.UTF_8);
            expired.add(requestId);
            batch.add(new KV(kv.getKey(), null));
            batch.add(new KV(bytes(REQUEST_PREFIX + requestId), null));
        }
        return expired;
    }

    private static byte[] requestIndexKey(long index) {
        // Zero padded, so the keys sort by index
        return bytes(String.format("%s%019d", REQUEST_INDEX_PREFIX, index));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static void notify(TxnApplyListener txnListener, long index, String key,
                               GraphRecord record) {
        if (txnListener == null) {
            return;
        }
        try {
            txnListener.onTxnApplied(index, key, record.rev, record.inc, record.state);
        } catch (Throwable e) {
            log.error("TXN apply listener failed at index {} for {}", index, key, e);
        }
    }

    /**
     * The stored record, null if it is absent, or {@link #INVALID} if it can't be read: not a
     * JSON object, inc not a positive integer, rev not a non-negative integer when present, or
     * state not CREATING, LIVE or DROPPED. Treating it as absent would restart the incarnation
     * at 1, and throwing would fail the apply on every node, so the TXN fails with
     * INVALID_RECORD instead; the check reads only the bytes, so every node reaches the same
     * result.
     */
    private static GraphRecord readRecord(byte[] stored) {
        if (stored == null || stored.length == 0) {
            return null;
        }
        String value = null;
        try {
            value = V.parseFrom(stored).getValue();
            JsonObject json = JsonParser.parseString(value).getAsJsonObject();
            JsonPrimitive inc = json.getAsJsonPrimitive("inc");
            JsonPrimitive state = json.getAsJsonPrimitive("state");
            JsonPrimitive rev = json.getAsJsonPrimitive("rev");
            if (inc.isNumber() && state.isString() && (rev == null || rev.isNumber())) {
                long incarnation = inc.getAsBigDecimal().longValueExact();
                long revision = rev == null ? 0L : rev.getAsBigDecimal().longValueExact();
                String graphState = state.getAsString();
                if (incarnation > 0L && revision >= 0L &&
                    (CREATING.equals(graphState) || LIVE.equals(graphState) ||
                     DROPPED.equals(graphState))) {
                    return new GraphRecord(revision, incarnation, graphState);
                }
            }
        } catch (Exception e) {
            // Not JSON, not an object, a field missing or not a primitive, or not a long
        }
        log.warn("The graph record '{}' can't be read", value);
        return INVALID;
    }

    private static boolean matches(HgKVStore store, TxnCompare compare) throws PDException {
        byte[] stored = store.get(KvService.getKeyBytes(compare.getKey()));
        boolean absent = stored == null || stored.length == 0;
        if (compare.getKind() == TxnCompare.Kind.ABSENT) {
            return absent;
        }
        try {
            return !absent && V.parseFrom(stored).getValue().equals(compare.getValue());
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] wrap(String value) {
        return V.newBuilder().setValue(value).setTtl(0).build().toByteArray();
    }

    private static PDException invalid(String message) {
        return new PDException(Pdpb.ErrorType.UNKNOWN_VALUE, message);
    }

    /**
     * The value of a graph record, serialized as {"rev":n,"inc":n,"state":"LIVE"}
     */
    private static final class GraphRecord {

        private final long rev;
        private final long inc;
        private final String state;

        private GraphRecord(long rev, long inc, String state) {
            this.rev = rev;
            this.inc = inc;
            this.state = state;
        }
    }
}
