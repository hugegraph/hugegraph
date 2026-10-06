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
import java.util.List;

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
 * Replay guard: jraft applies the entries after the last snapshot again on restart, over a
 * store that already holds them. A TXN is not idempotent (CREATE increments the incarnation,
 * a compare may pass the second time), so {@link #LAST_INDEX_KEY} keeps the index of the last
 * applied TXN and an entry at or below it is skipped. This holds while the keys a TXN writes
 * are written by TXNs alone: a plain put is replayed, and one that came before a skipped TXN
 * would overwrite what that TXN wrote.
 */
@Slf4j
public final class KvTxnApplier {

    public static final String LIVE = "LIVE";
    public static final String DROPPED = "DROPPED";

    /**
     * Raw store key outside the K@ space of KvService, so no KV call reads or writes it
     */
    public static final byte[] LAST_INDEX_KEY =
            "__TXN_LAST_INDEX".getBytes(StandardCharsets.UTF_8);

    private static final Gson GSON = new Gson();

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
            if (compare.getKind() == TxnCompare.Kind.UNRECOGNIZED) {
                throw invalid("unknown compare kind on key " + compare.getKey());
            }
        }
        for (TxnOp op : request.getOpsList()) {
            if (op.getType() == TxnOp.Type.UNRECOGNIZED) {
                throw invalid("unknown op type on key " + op.getKey());
            }
        }
        if (request.hasRecord() && (request.getRecord().getKey().isEmpty() ||
                                    request.getRecord().getOp() == TxnRecord.Op.UNRECOGNIZED)) {
            throw invalid("a TXN record needs a key and a known op");
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
        // Written on failure too, so that a replay does not run the compares again
        KV guard = new KV(LAST_INDEX_KEY, Long.toString(index).getBytes(StandardCharsets.UTF_8));
        TxnResponse.Builder response = TxnResponse.newBuilder();
        for (int i = 0; i < request.getComparesCount(); i++) {
            if (!matches(store, request.getCompares(i))) {
                store.writeBatch(Collections.singletonList(guard));
                return response.setFailure(TxnResponse.Failure.COMPARE_FAILED)
                               .setFailedCompare(i).build();
            }
        }

        byte[] recordKey = null;
        GraphRecord record = null;
        if (request.hasRecord()) {
            recordKey = KvService.getKeyBytes(request.getRecord().getKey());
            GraphRecord stored = readRecord(store.get(recordKey));
            TxnResponse.Failure failure = check(request.getRecord(), stored);
            if (failure != TxnResponse.Failure.NONE) {
                store.writeBatch(Collections.singletonList(guard));
                return response.setFailure(failure)
                               .setIncarnation(stored == null ? 0L : stored.inc).build();
            }
            record = next(request.getRecord().getOp(), stored, index);
        }

        List<KV> batch = new ArrayList<>();
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
                    batch.removeIf(kv -> Bytes.indexOf(kv.getKey(), key) == 0);
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
        }
        batch.add(guard);
        store.writeBatch(batch);

        if (record != null) {
            notify(txnListener, index, request.getRecord().getKey(), record);
            response.setIncarnation(record.inc);
        }
        return response.setSucceeded(true).setRevision(index).build();
    }

    private static TxnResponse.Failure check(TxnRecord request, GraphRecord stored) {
        if (request.getOp() == TxnRecord.Op.CREATE) {
            return TxnResponse.Failure.NONE;
        }
        if (request.getOp() == TxnRecord.Op.BUMP && stored != null &&
            DROPPED.equals(stored.state)) {
            return TxnResponse.Failure.GRAPH_DROPPED;
        }
        long expected = request.getExpectedIncarnation();
        if (expected != 0L && (stored == null || stored.inc != expected)) {
            return TxnResponse.Failure.INCARNATION_MISMATCH;
        }
        return TxnResponse.Failure.NONE;
    }

    private static GraphRecord next(TxnRecord.Op op, GraphRecord stored, long index) {
        // An absent record is a graph created before records existed: incarnation 1
        long inc = stored == null ? 1L : stored.inc;
        switch (op) {
            case BUMP:
                return new GraphRecord(index, inc, LIVE);
            case CREATE:
                return new GraphRecord(index, stored == null ? 1L : inc + 1L, LIVE);
            case DROP:
                return new GraphRecord(index, inc, DROPPED);
            default:
                throw new AssertionError("Unknown TXN record op " + op);
        }
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
     * An unreadable record counts as absent: throwing here would fail the apply on every node
     */
    private static GraphRecord readRecord(byte[] stored) {
        if (stored == null || stored.length == 0) {
            return null;
        }
        try {
            return GSON.fromJson(V.parseFrom(stored).getValue(), GraphRecord.class);
        } catch (Exception e) {
            log.warn("Ignore an unreadable graph record", e);
            return null;
        }
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
