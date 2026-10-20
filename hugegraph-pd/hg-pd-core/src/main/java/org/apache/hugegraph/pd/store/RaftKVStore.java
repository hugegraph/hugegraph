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

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.apache.hugegraph.pd.common.PDException;
import org.apache.hugegraph.pd.config.PDConfig;
import org.apache.hugegraph.pd.grpc.Pdpb;
import org.apache.hugegraph.pd.grpc.kv.TxnEntry;
import org.apache.hugegraph.pd.grpc.kv.TxnRequest;
import org.apache.hugegraph.pd.grpc.kv.TxnResponse;
import org.apache.hugegraph.pd.raft.KVOperation;
import org.apache.hugegraph.pd.raft.KVStoreClosure;
import org.apache.hugegraph.pd.raft.RaftEngine;
import org.apache.hugegraph.pd.raft.RaftStateMachine;
import org.apache.hugegraph.pd.raft.RaftTaskHandler;

import com.alipay.sofa.jraft.Status;
import com.alipay.sofa.jraft.entity.Task;
import com.alipay.sofa.jraft.error.RaftError;
import com.google.protobuf.InvalidProtocolBufferException;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class RaftKVStore implements HgKVStore, RaftTaskHandler {

    private final RaftEngine engine;
    private final HgKVStore store;

    public RaftKVStore(RaftEngine engine, HgKVStore store) {
        this.engine = engine;
        this.store = store;
    }

    @Override
    public void init(PDConfig config) {
        this.store.init(config);
        this.engine.addTaskHandler(this);
    }

    private BaseKVStoreClosure createClosure() {
        return new BaseKVStoreClosure() {
            @Override
            public void run(Status status) {
                if (!status.isOk()) {
                    log.error("An exception occurred while performing the RAFT,{}",
                              status.getErrorMsg());
                } else {
                    log.info("RAFT done!");
                }
            }
        };
    }

    @Override
    public void put(byte[] key, byte[] value) throws PDException {
        KVOperation operation = KVOperation.createPut(key, value);
        try {
            applyOperation(operation).get();
        } catch (Exception e) {
            throw new PDException(Pdpb.ErrorType.UNKNOWN_VALUE, e.getMessage());
        }
    }

    /**
     * Queries can be read without rafting
     */
    @Override
    public byte[] get(byte[] key) throws PDException {
        return store.get(key);

    }

    @Override
    public List<KV> scanPrefix(byte[] prefix) {
        return store.scanPrefix(prefix);
    }

    @Override
    public long remove(byte[] bytes) throws PDException {
        try {
            applyOperation(KVOperation.createRemove(bytes)).get();
        } catch (Exception e) {
            throw new PDException(Pdpb.ErrorType.UNKNOWN_VALUE, e.getMessage());
        }
        return 0;
    }

    @Override
    public long removeByPrefix(byte[] bytes) throws PDException {
        try {
            applyOperation(KVOperation.createRemoveByPrefix(bytes)).get();
        } catch (Exception e) {
            throw new PDException(Pdpb.ErrorType.UNKNOWN_VALUE, e.getMessage());
        }
        return 0;
    }

    @Override
    public void clear() throws PDException {
        try {
            applyOperation(KVOperation.createClear()).get();
        } catch (Exception e) {
            throw new PDException(Pdpb.ErrorType.UNKNOWN_VALUE, e.getMessage());
        }
    }

    @Override
    public void putWithTTL(byte[] key, byte[] value, long ttl) throws PDException {
        try {
            applyOperation(KVOperation.createPutWithTTL(key, value, ttl)).get();
        } catch (Exception e) {
            throw new PDException(Pdpb.ErrorType.UNKNOWN_VALUE, e.getMessage());
        }
    }

    @Override
    public void putWithTTL(byte[] key, byte[] value, long ttl, TimeUnit timeUnit) throws
                                                                                  PDException {
        try {
            applyOperation(KVOperation.createPutWithTTL(key, value, ttl, timeUnit)).get();
        } catch (Exception e) {
            throw new PDException(Pdpb.ErrorType.UNKNOWN_VALUE, e.getMessage());
        }
    }

    @Override
    public List<byte[]> getListWithTTL(byte[] key) throws PDException {
        return store.getListWithTTL(key);
    }

    @Override
    public byte[] getWithTTL(byte[] key) throws PDException {
        return store.getWithTTL(key);
    }

    @Override
    public void removeWithTTL(byte[] key) throws PDException {
        try {
            applyOperation(KVOperation.createRemoveWithTTL(key)).get();
        } catch (Exception e) {
            throw new PDException(Pdpb.ErrorType.UNKNOWN_VALUE, e.getMessage());
        }
    }

    @Override
    public void saveSnapshot(String snapshotPath) throws PDException {
        store.saveSnapshot(snapshotPath);
    }

    @Override
    public void loadSnapshot(String snapshotPath) throws PDException {
        store.loadSnapshot(snapshotPath);
    }

    @Override
    public List<KV> scanRange(byte[] start, byte[] end) {
        return store.scanRange(start, end);
    }

    /**
     * A batch reaches the local store only from inside a TXN apply
     */
    @Override
    public void writeBatch(List<KV> kvs) {
        throw new UnsupportedOperationException("Write a batch through txn");
    }

    /**
     * Proposes the TXN as one raft entry, with its keys encoded here, and returns its outcome
     * on this node
     */
    @Override
    public TxnResponse txn(TxnRequest request) throws PDException {
        // Not before every peer is confirmed to apply the entry: TXN_NOT_PROPOSED otherwise
        this.engine.checkTxnPeers();
        try {
            byte[] entry = KvTxnApplier.entry(request).toByteArray();
            return this.<TxnResponse>applyOperation(KVOperation.createTxn(entry)).get();
        } catch (Exception e) {
            // With the cause: the message alone doesn't tell what failed, and where
            throw new PDException(Pdpb.ErrorType.UNKNOWN_VALUE, e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        store.close();
    }

    /**
     * Reads above are served from the local state, which on a new leader can trail the
     * entries its predecessor committed; this waits for them to be applied
     */
    @Override
    public void waitReadIndex() throws PDException {
        this.engine.waitReadIndex();
    }

    @Override
    public long waitReadIndexInTerm() throws PDException {
        return this.engine.waitReadIndexInTerm();
    }

    @Override
    public void put(byte[] key, byte[] value, long term) throws PDException {
        applyInTerm(KVOperation.createPut(key, value), term);
    }

    @Override
    public long remove(byte[] key, long term) throws PDException {
        applyInTerm(KVOperation.createRemove(key), term);
        return 0;
    }

    private void applyInTerm(KVOperation op, long term) throws PDException {
        try {
            applyOperation(op, term).get();
        } catch (ExecutionException e) {
            // Keeps NOT_LEADER, which is how jraft's EPERM for another term arrives here
            throw e.getCause() instanceof PDException ? (PDException) e.getCause() :
                  new PDException(Pdpb.ErrorType.UNKNOWN_VALUE, e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PDException(Pdpb.ErrorType.UNKNOWN_VALUE, e);
        }
    }

    /**
     * Need to walk the real operation of Raft
     */
    private void doPut(byte[] key, byte[] value) throws PDException {

        store.put(key, value);
    }

    public long doRemove(byte[] bytes) throws PDException {
        return this.store.remove(bytes);
    }

    public long doRemoveByPrefix(byte[] bytes) throws PDException {
        return this.store.removeByPrefix(bytes);
    }

    public void doRemoveWithTTL(byte[] key) throws PDException {
        this.store.removeWithTTL(key);
    }

    public void doClear() throws PDException {
        this.store.clear();
    }

    public void doPutWithTTL(byte[] key, byte[] value, long ttl) throws PDException {
        this.store.putWithTTL(key, value, ttl);
    }

    public void doPutWithTTL(byte[] key, byte[] value, long ttl, TimeUnit timeUnit) throws
                                                                                    PDException {
        this.store.putWithTTL(key, value, ttl, timeUnit);
    }

    /**
     * Whether a raft entry that writes the store is one the store holds already. jraft applies
     * the entries after the last snapshot again on restart, and without a snapshot that is
     * over the store that holds them. Every entry up to the last applied TXN was applied before
     * it, so all of them are skipped, plain writes too: a plain write replayed before a skipped
     * TXN would overwrite what the TXN wrote. Plain writes after the last TXN are applied again
     * in order, which leaves the store as it was. TTL entries are kept in memory, not in the
     * store, so their replay is not skipped, and neither is CLEAR, which empties that memory.
     * <p>
     * Only a replay is skipped: an entry that was in this node's log when it started. An entry
     * above that index is new in this run, and a store that claims to hold it is ahead of the
     * log (restored from a backup without the log, or written by a build that put another
     * count under the TXN index key). Skipping it would leave this node behind the peers, which
     * apply it, so the apply fails instead: jraft stops this node's state machine, a leader
     * steps down, the probes report the error, and the node takes no further part until an
     * operator removes its data directory and lets it rejoin from a snapshot.
     */
    private boolean appliedBefore(KVOperation op) throws PDException {
        if (op.getIndex() <= 0L) {
            // Not a raft entry, such as a snapshot save or load
            return false;
        }
        switch (op.getOp()) {
            case KVOperation.PUT:
            case KVOperation.REMOVE:
            case KVOperation.REMOVE_BY_PREFIX:
            case KVOperation.TXN:
                break;
            default:
                return false;
        }
        long last = KvTxnApplier.lastIndex(this.store);
        if (op.getIndex() > last) {
            return false;
        }
        if (op.getIndex() > this.engine.getLastLogIndexAtStart()) {
            String message = "The store of this PD node is ahead of its raft log: entry " +
                             op.getIndex() + " is new in this run, and the store holds TXN " +
                             "index " + last + " already. The entry is applied on the other " +
                             "nodes; this node stops applying, so that it does not serve " +
                             "stale data. Remove its data directory and let it rejoin from a " +
                             "snapshot.";
            log.error(message);
            throw new PDException(Pdpb.ErrorType.UNKNOWN_VALUE, message);
        }
        return true;
    }

    public TxnResponse doTxn(byte[] entry, long index) throws PDException {
        try {
            return KvTxnApplier.apply(this.store, TxnEntry.parseFrom(entry), index);
        } catch (InvalidProtocolBufferException e) {
            throw new PDException(Pdpb.ErrorType.UNKNOWN_VALUE, e);
        }
    }

    public void doSaveSnapshot(String snapshotPath) throws PDException {
        this.store.saveSnapshot(snapshotPath);
    }

    public void doLoadSnapshot(String snapshotPath) throws PDException {
        this.store.loadSnapshot(snapshotPath);
    }

    private <T> CompletableFuture<T> applyOperation(final KVOperation op) throws PDException {
        // -1 is jraft's default: the entry is accepted in any term
        return applyOperation(op, -1L);
    }

    private <T> CompletableFuture<T> applyOperation(final KVOperation op, long expectedTerm)
            throws PDException {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            final Task task = new Task();
            task.setExpectedTerm(expectedTerm);
            task.setData(ByteBuffer.wrap(op.toByteArray()));
            task.setDone(new RaftStateMachine.RaftClosureAdapter(op, new KVStoreClosure() {
                Object data;
                Pdpb.Error error;

                @Override
                public Pdpb.Error getError() {
                    return error;
                }

                @Override
                public void setError(Pdpb.Error error) {
                    this.error = error;
                }

                @Override
                public Object getData() {
                    return data;
                }

                @Override
                public void setData(Object data) {
                    this.data = data;
                }

                @Override
                public void run(Status status) {
                    if (status.isOk()) {
                        future.complete((T) data);
                    } else {
                        RaftError raftError = status.getRaftError();
                        Pdpb.ErrorType type;
                        if (RaftError.EPERM.equals(raftError)) {
                            type = Pdpb.ErrorType.NOT_LEADER;
                        } else {
                            type = Pdpb.ErrorType.UNKNOWN;
                        }
                        error = Pdpb.Error.newBuilder().setType(type)
                                          .setMessage(status.getErrorMsg())
                                          .build();
                        future.completeExceptionally(
                                new PDException(error.getTypeValue()));
                    }
                }
            }));
            this.engine.addTask(task);
            return future;
        } catch (Exception e) {
            future.completeExceptionally(e);
            return future;
        }
    }

    private boolean isLeader() {
        return this.engine.isLeader();
    }

    @Override
    public boolean invoke(KVOperation op, KVStoreClosure response) throws PDException {
        if (this.appliedBefore(op)) {
            return false;
        }
        switch (op.getOp()) {
            case KVOperation.GET:
                break;
            case KVOperation.PUT:
                doPut(op.getKey(), op.getValue());
                break;
            case KVOperation.REMOVE:
                doRemove(op.getKey());
                break;
            case KVOperation.PUT_WITH_TTL:
                doPutWithTTL(op.getKey(), op.getValue(), (long) op.getArg());
                break;
            case KVOperation.PUT_WITH_TTL_UNIT:
                Object[] arg = (Object[]) op.getArg();
                doPutWithTTL(op.getKey(), op.getValue(), (long) arg[0], (TimeUnit) arg[1]);
                break;
            case KVOperation.REMOVE_BY_PREFIX:
                doRemoveByPrefix(op.getKey());
                break;
            case KVOperation.REMOVE_WITH_TTL:
                doRemoveWithTTL(op.getKey());
                break;
            case KVOperation.CLEAR:
                doClear();
                break;
            case KVOperation.TXN:
                TxnResponse result = doTxn(op.getValue(), op.getIndex());
                if (response != null) {
                    response.setData(result);
                }
                break;
            case KVOperation.SAVE_SNAPSHOT:
                doSaveSnapshot((String) op.getAttach());
                break;
            case KVOperation.LOAD_SNAPSHOT:
                doLoadSnapshot((String) op.getAttach());
                break;
            default:
                log.error("Err op {}", op.getOp());
        }
        return false;
    }
}
