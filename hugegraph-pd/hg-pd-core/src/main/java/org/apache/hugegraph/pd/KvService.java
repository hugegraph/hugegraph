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

package org.apache.hugegraph.pd;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.hugegraph.pd.common.PDException;
import org.apache.hugegraph.pd.config.PDConfig;
import org.apache.hugegraph.pd.grpc.Pdpb;
import org.apache.hugegraph.pd.grpc.kv.Kv;
import org.apache.hugegraph.pd.grpc.kv.TxnCompare;
import org.apache.hugegraph.pd.grpc.kv.TxnOp;
import org.apache.hugegraph.pd.grpc.kv.TxnRequest;
import org.apache.hugegraph.pd.grpc.kv.TxnResponse;
import org.apache.hugegraph.pd.grpc.kv.V;
import org.apache.hugegraph.pd.meta.MetadataKeyHelper;
import org.apache.hugegraph.pd.meta.MetadataRocksDBStore;
import org.apache.hugegraph.pd.store.KV;
import org.apache.hugegraph.pd.store.KvTxnApplier;
import org.springframework.stereotype.Service;

import com.google.protobuf.InvalidProtocolBufferException;

import lombok.extern.slf4j.Slf4j;

/**
 *
 **/
@Slf4j
@Service
public class KvService {

    public static final char KV_DELIMITER = '@';
    private static final String TTL_PREFIX = "T";
    private static final String KV_PREFIX = "K";
    private static final String LOCK_PREFIX = "L";
    private static final String KV_PREFIX_DELIMITER = KV_PREFIX + KV_DELIMITER;
    private static final byte[] EMPTY_VALUE = new byte[0];
    private MetadataRocksDBStore meta;
    private PDConfig pdConfig;
    // Whether the TTL cleanup deletes with plain removes, because a PD peer blocks txn
    private final AtomicBoolean plainTtlCleanup = new AtomicBoolean();

    public KvService(PDConfig config) {
        this.pdConfig = config;
        meta = new MetadataRocksDBStore(config);
    }

    public static String getKey(Object... keys) {
        StringBuilder builder = MetadataKeyHelper.getStringBuilderHelper();
        builder.append(KV_PREFIX).append(KV_DELIMITER);
        for (Object key : keys) {
            builder.append(key == null ? "" : key).append(KV_DELIMITER);
        }
        return builder.substring(0, builder.length() - 1);
    }

    public static byte[] getKeyBytes(Object... keys) {
        String key = getKey(keys);
        return key.getBytes(Charset.defaultCharset());
    }

    /**
     * The TTL marker {@link #getTTLStoreKey(String, long)} wrote next to the store key of a
     * value that expires at expireAt (the ttl field of its {@link V}), built from the store
     * key's bytes so every raft node names the same marker. clearTTLData deletes the key a
     * marker names once the marker's time has passed, whatever the key holds by then, so a
     * write that replaces a TTL value must delete its marker too.
     */
    public static byte[] ttlMarkerKey(byte[] storeKey, long expireAt) {
        // K@T@<time>@<key>; the prefix is ASCII, which every default charset encodes alike
        byte[] prefix = (KV_PREFIX_DELIMITER + TTL_PREFIX + KV_DELIMITER + expireAt +
                         KV_DELIMITER).getBytes(StandardCharsets.US_ASCII);
        int keyStart = KV_PREFIX_DELIMITER.length();
        byte[] marker = new byte[prefix.length + storeKey.length - keyStart];
        System.arraycopy(prefix, 0, marker, 0, prefix.length);
        System.arraycopy(storeKey, keyStart, marker, prefix.length, storeKey.length - keyStart);
        return marker;
    }

    public static String getKeyWithoutPrefix(Object... keys) {
        StringBuilder builder = MetadataKeyHelper.getStringBuilderHelper();
        for (Object key : keys) {
            builder.append(key == null ? "" : key).append(KV_DELIMITER);
        }
        return builder.substring(0, builder.length() - 1);
    }

    public static String getDelimiter() {
        return String.valueOf(KV_DELIMITER);
    }

    public PDConfig getPdConfig() {
        return pdConfig;
    }

    public void setPdConfig(PDConfig pdConfig) {
        this.pdConfig = pdConfig;
    }

    public void put(String key, String value) throws PDException {
        V storeValue = V.newBuilder().setValue(value).setTtl(0).build();
        meta.put(getStoreKey(key), storeValue.toByteArray());
        // log.warn("add key with key-{}:value-{}", key, value);
    }

    public void put(String key, String value, long ttl) throws PDException {
        long curTime = System.currentTimeMillis();
        curTime += ttl;
        V storeValue = V.newBuilder().setValue(value).setSt(ttl).setTtl(curTime).build();
        meta.put(getStoreKey(key), storeValue.toByteArray());
        meta.put(getTTLStoreKey(key, curTime), EMPTY_VALUE);
        // log.warn("add key with key-{}:value-{}:ttl-{}", key, value, ttl);
    }

    /**
     * Atomic multi-key write, see {@link KvTxnApplier}. A failed compare or a rejected record
     * is an unsuccessful response, not an exception. Keys a TXN writes must not be written with
     * put or delete as well.
     */
    public TxnResponse txn(TxnRequest request) throws PDException {
        KvTxnApplier.validate(request);
        return meta.getStore().txn(request);
    }

    public String get(String key) throws PDException {
        byte[] storeKey = getStoreKey(key);
        return get(storeKey);
    }

    public String get(byte[] keyBytes) throws PDException {
        byte[] bytes = meta.getOne(keyBytes);
        String v = getValue(keyBytes, bytes);
        return v;
    }

    private String getValue(byte[] keyBytes, byte[] valueBytes) throws PDException {
        if (valueBytes == null || valueBytes.length == 0) {
            return "";
        }
        try {
            V v = V.parseFrom(valueBytes);
            if (v.getTtl() == 0 || v.getTtl() >= System.currentTimeMillis()) {
                return v.getValue();
            } else {
                meta.remove(keyBytes);
                meta.remove(getTTLStoreKey(new String(keyBytes), v.getTtl()));
            }
        } catch (Exception e) {
            log.error("parse value with error:{}", e.getMessage());
            throw new PDException(-1, e.getMessage());
        }
        return null;
    }

    public boolean keepAlive(String key) throws PDException {
        byte[] bytes = meta.getOne(getStoreKey(key));
        try {
            if (bytes == null || bytes.length == 0) {
                return false;
            }
            V v = V.parseFrom(bytes);
            if (v != null) {
                long ttl = v.getTtl();
                long st = v.getSt();
                meta.remove(getTTLStoreKey(key, ttl));
                put(key, v.getValue(), st);
                return true;
            } else {
                return false;
            }
        } catch (InvalidProtocolBufferException e) {
            throw new PDException(-1, e.getMessage());
        }
    }

    public Kv delete(String key) throws PDException {
        byte[] storeKey = getStoreKey(key);
        String value = this.get(storeKey);
        meta.remove(storeKey);
        Kv.Builder builder = Kv.newBuilder().setKey(key);
        if (value != null) {
            builder.setValue(value);
        }
        Kv kv = builder.build();
        // log.warn("delete kv with key :{}", key);
        return kv;
    }

    public List<Kv> deleteWithPrefix(String key) throws PDException {
        byte[] storeKey = getStoreKey(key);
        //TODO to many rows for scan
        List<KV> kvList = meta.scanPrefix(storeKey);
        LinkedList<Kv> kvs = new LinkedList<>();
        for (KV kv : kvList) {
            String kvKey = new String(kv.getKey()).replaceFirst(KV_PREFIX_DELIMITER, "");
            String kvValue = getValue(kv.getKey(), kv.getValue());
            if (kvValue != null) {
                kvs.add(Kv.newBuilder().setKey(kvKey).setValue(kvValue).build());
            }
        }
        meta.removeByPrefix(storeKey);
        // log.warn("delete kv with key prefix :{}", key);
        return kvs;
    }

    /**
     * scan result ranged from key start and key end
     *
     * @param keyStart
     * @param keyEnd
     * @return Records
     * @throws PDException
     */
    public Map<String, String> scanRange(String keyStart, String keyEnd) throws PDException {
        List<KV> list = meta.scanRange(getStoreKey(keyStart), getStoreKey(keyEnd));
        Map<String, String> map = new HashMap<>();
        for (KV kv : list) {
            String kvKey = new String(kv.getKey()).replaceFirst(KV_PREFIX_DELIMITER, "");
            String kvValue = getValue(kv.getKey(), kv.getValue());
            if (kvValue != null) {
                map.put(kvKey, kvValue);
            }
        }
        return map;
    }

    public Map<String, String> scanWithPrefix(String key) throws PDException {
        List<KV> kvList = meta.scanPrefix(getStoreKey(key));
        HashMap<String, String> map = new HashMap<>();
        for (KV kv : kvList) {
            String kvKey = new String(kv.getKey()).replaceFirst(KV_PREFIX_DELIMITER, "");
            String kvValue = getValue(kv.getKey(), kv.getValue());
            if (kvValue != null) {
                map.put(kvKey, kvValue);
            }
        }
        return map;
    }

    public boolean locked(String key) throws PDException {
        String lockKey = KvService.getKeyWithoutPrefix(KvService.LOCK_PREFIX, key);
        Map<String, String> allLock = scanWithPrefix(lockKey + KV_DELIMITER);
        return allLock != null && allLock.size() != 0;
    }

    private boolean owned(String key, long clientId) throws PDException {
        String lockKey = KvService.getKeyWithoutPrefix(KvService.LOCK_PREFIX, key);
        Map<String, String> allLock = scanWithPrefix(lockKey + KV_DELIMITER);
        if (allLock.size() == 0) {
            return true;
        }
        for (Map.Entry<String, String> entry : allLock.entrySet()) {
            String entryKey = entry.getKey();
            String[] split = entryKey.split(String.valueOf(KV_DELIMITER));
            if (Long.valueOf(split[split.length - 1]).equals(clientId)) {
                return true;
            }
        }
        return false;
    }

    public boolean lock(String key, long ttl, long clientId) throws PDException {
        //TODO lock improvement
        synchronized (KvService.class) {
            if (!owned(key, clientId)) {
                return false;
            }
            put(getLockKey(key, clientId), " ", ttl);
            return true;
        }
    }

    public boolean lockWithoutReentrant(String key, long ttl,
                                        long clientId) throws PDException {
        synchronized (KvService.class) {
            if (locked(key)) {
                return false;
            }
            put(getLockKey(key, clientId), " ", ttl);
            return true;
        }
    }

    public boolean unlock(String key, long clientId) throws PDException {
        synchronized (KvService.class) {
            if (!owned(key, clientId)) {
                return false;
            }
            delete(getLockKey(key, clientId));
            return true;
        }
    }

    public boolean keepAlive(String key, long clientId) throws PDException {
        String lockKey = getLockKey(key, clientId);
        return keepAlive(lockKey);
    }

    public String getLockKey(String key, long clientId) {
        return getKeyWithoutPrefix(LOCK_PREFIX, key, clientId);
    }

    public byte[] getStoreKey(String key) {
        return getKeyBytes(key);
    }

    public byte[] getTTLStoreKey(String key, long time) {
        return getKeyBytes(TTL_PREFIX, time, key);
    }

    public void clearTTLData() {
        try {
            byte[] ttlStartKey = getTTLStoreKey("", 0);
            byte[] ttlEndKey = getTTLStoreKey("", System.currentTimeMillis());
            List<KV> kvList = meta.scanRange(ttlStartKey, ttlEndKey);
            // A marker is K@T@<time>@<key>: the key starts after the delimiter that ends the time
            int timeStart = (KV_PREFIX_DELIMITER + TTL_PREFIX + KV_DELIMITER).length();
            for (KV kv : kvList) {
                String key = new String(kv.getKey());
                int index = key.indexOf(KV_DELIMITER, timeStart);
                String delKey = key.substring(index + 1);
                byte[] stored = meta.getOne(getStoreKey(delKey));
                removeExpired(delKey, key.substring(KV_PREFIX_DELIMITER.length()), stored);
            }
        } catch (Exception e) {
            log.error("clear ttl data with error :", e);
        }
    }

    /**
     * Deletes a key whose TTL marker expired, and the marker, with one guarded txn bound to
     * that marker's generation of the value. The value the cleanup read must carry the
     * marker's expiry, so a value written after the marker (with another TTL, or none) is kept
     * and only the stale marker goes. At the apply, the txn deletes only while the marker
     * still exists and the key still holds the value read: a txn that wrote the key removes
     * its marker in the same batch, so it keeps its value whenever it lands, even with the
     * identical string. A plain put without a TTL keeps the marker, so one that rewrites the
     * identical string between the read and the apply is still deleted. While a PD peer blocks
     * txn (a rolling upgrade), the cleanup deletes with plain removes as before, and says so
     * once.
     *
     * @param key    the key, without the K@ prefix
     * @param marker the marker key, without the K@ prefix: T@<time>@<key>
     * @param stored what the cleanup read under the key, null if it is absent
     */
    public void removeExpired(String key, String marker, byte[] stored) throws PDException {
        if (stored == null) {
            meta.remove(getStoreKey(marker));
            return;
        }
        V read;
        try {
            read = V.parseFrom(stored);
        } catch (InvalidProtocolBufferException e) {
            // Not a value KvService wrote: the plain delete of before
            delete(key);
            meta.remove(getStoreKey(marker));
            return;
        }
        int timeEnd = marker.indexOf(KV_DELIMITER, TTL_PREFIX.length() + 1);
        long expireAt = Long.parseLong(marker.substring(TTL_PREFIX.length() + 1, timeEnd));
        if (read.getTtl() != expireAt) {
            // Written after the marker, with another TTL or none: not the value it names
            meta.remove(getStoreKey(marker));
            return;
        }
        TxnRequest request = TxnRequest.newBuilder()
                // The marker still exists: no txn wrote the key since the scan
                .addCompares(TxnCompare.newBuilder().setKey(marker)
                                       .setKind(TxnCompare.Kind.EQUALS).setValue(""))
                .addCompares(TxnCompare.newBuilder().setKey(key)
                                       .setKind(TxnCompare.Kind.EQUALS)
                                       .setValue(read.getValue()))
                .addOps(TxnOp.newBuilder().setType(TxnOp.Type.DELETE).setKey(key))
                .addOps(TxnOp.newBuilder().setType(TxnOp.Type.DELETE).setKey(marker))
                .build();
        TxnResponse response;
        try {
            response = txn(request);
        } catch (PDException e) {
            if (e.getErrorCode() != Pdpb.ErrorType.TXN_NOT_PROPOSED_VALUE) {
                throw e;
            }
            if (this.plainTtlCleanup.compareAndSet(false, true)) {
                log.warn("TTL cleanup deletes with plain removes until txn is allowed: {}",
                         e.getMessage());
            }
            delete(key);
            meta.remove(getStoreKey(marker));
            return;
        }
        if (this.plainTtlCleanup.compareAndSet(true, false)) {
            log.info("TTL cleanup deletes with guarded txns again");
        }
        if (!response.getSucceeded()) {
            // The key changed since the read: keep it, drop the marker that named the old value
            meta.remove(getStoreKey(marker));
        }
    }

    public MetadataRocksDBStore getMeta() {
        return meta;
    }
}
