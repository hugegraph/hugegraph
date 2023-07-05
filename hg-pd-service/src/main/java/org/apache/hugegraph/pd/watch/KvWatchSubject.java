/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.apache.hugegraph.pd.watch;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiPredicate;

import org.apache.hugegraph.pd.KvService;
import org.apache.hugegraph.pd.common.PDException;
import org.apache.hugegraph.pd.config.PDConfig;
import org.apache.hugegraph.pd.grpc.kv.WatchEvent;
import org.apache.hugegraph.pd.grpc.kv.WatchKv;
import org.apache.hugegraph.pd.grpc.kv.WatchResponse;
import org.apache.hugegraph.pd.grpc.kv.WatchState;
import org.apache.hugegraph.pd.grpc.kv.WatchType;

import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;

/**
 * watch订阅、响应处理类
 *
 * @author zhangyingjie
 * @date 2022/6/21
 **/
@Slf4j
public class KvWatchSubject {

    public static final String KEY_DELIMITER = "KW";
    public static final String PREFIX_DELIMITER = "PW";
    public static final String ALL_PREFIX = "W";
    public static final long WATCH_TTL = 20000L;
    private static final ConcurrentMap<String, StreamObserver<WatchResponse>> clients =
            new ConcurrentHashMap<>();
    private final KvService kvService;
    BiPredicate<String, String> equal = String::equals;
    BiPredicate<String, String> startWith = String::startsWith;

    /**
     * 会使用以下三组key:
     * clients -> W@KW@key@clientId
     * rocksdb key1 ->W@KW@key@clientId
     * rocksdb key2 ->W@clientId@KW@key@clientId
     **/
    public KvWatchSubject(PDConfig pdConfig) {
        this.kvService = new KvService(pdConfig);
    }

    public String getWatchKey(String key, String watchDelimiter) {
        return KvService.getKeyWithoutPrefix(ALL_PREFIX, watchDelimiter, key);
    }

    private void addWatchKey(String key, String delimiter, long clientId) throws PDException {
        String watchKey = KvService.getKeyWithoutPrefix(ALL_PREFIX, delimiter, key, clientId);
        kvService.put(watchKey, "", WATCH_TTL);
        String clientFirstKey =
                KvService.getKeyWithoutPrefix(ALL_PREFIX, clientId, delimiter, key, clientId);
        kvService.put(clientFirstKey, "", WATCH_TTL);
    }

    private void removeWatchKey(String key, String delimiter, long clientId) throws PDException {
        String watchKey = KvService.getKeyWithoutPrefix(ALL_PREFIX, delimiter, key, clientId);
        kvService.delete(watchKey);
        String clientFirstKey = KvService.getKeyWithoutPrefix(ALL_PREFIX, clientId, delimiter, key);
        kvService.deleteWithPrefix(clientFirstKey);
    }

    /**
     * 增加观察者
     *
     * @param key       观察的key
     * @param clientId  客户端标识
     * @param observer
     * @param delimiter 观察类型标识符，对前缀监听或者对key的监听可以通过此参数区分
     * @throws PDException
     */
    public void addObserver(String key, long clientId, StreamObserver<WatchResponse> observer,
                            String delimiter) throws PDException {
        String keyWithoutPrefix =
                KvService.getKeyWithoutPrefix(ALL_PREFIX, delimiter, key, clientId);
        clients.putIfAbsent(keyWithoutPrefix, observer);
        addWatchKey(key, delimiter, clientId);
        log.info("client:{},start to watch key:{}", clientId, key);
    }

    public void removeObserver(String key, long clientId, String delimiter) throws PDException {
        removeWatchKey(key, delimiter, clientId);
        String keyWithoutPrefix =
                KvService.getKeyWithoutPrefix(ALL_PREFIX, delimiter, key, clientId);
        clients.remove(keyWithoutPrefix);
    }

    /**
     * 通知观察者方法，key和prefix都使用此方法，predicate不同
     *
     * @param key
     * @param watchType 观察类型，一般是增加和删除
     * @param predicate 判断等于或者是前匹配，用来适配key或prefix观察
     * @param kvs
     * @throws PDException
     */
    public void notifyObserver(String key, WatchType watchType,
                               BiPredicate<String, String> predicate,
                               WatchKv... kvs) throws PDException {
        boolean isEqual = predicate.equals(equal);
        String watchDelimiter = isEqual ? KEY_DELIMITER : PREFIX_DELIMITER;
        String watchKeyPrefix = isEqual ? key : "";
        String storeKey = getWatchKey(watchKeyPrefix, watchDelimiter);
        Map<String, String> map = kvService.scanWithPrefix(storeKey);
        String delimiter = String.valueOf(KvService.KV_DELIMITER);
        WatchResponse watchResponse;
        for (String keyAndClient : map.keySet()) {
            String[] values = keyAndClient.split(delimiter);
            assert values.length == 4;
            String watchKey = values[2];
            String c = values[3];
            long clientId = Long.parseLong(c);
            LinkedList<WatchEvent> watchEvents = new LinkedList<>();
            for (WatchKv kv : kvs) {
                String kvKey = kv.getKey();
                boolean match = predicate.test(kvKey, watchKey);
                if (!match) {
                    continue;
                }
                WatchKv watchKv =
                        WatchKv.newBuilder().setKey(kvKey).setValue(kv.getValue()).build();
                WatchEvent event =
                        WatchEvent.newBuilder().setCurrent(watchKv).setType(watchType).build();
                watchEvents.add(event);
            }
            StreamObserver<WatchResponse> observer = clients.get(keyAndClient);
            watchResponse =
                    WatchResponse.newBuilder().setState(WatchState.Started).setClientId(clientId)
                                 .addAllEvents(watchEvents).build();

            try {
                if (observer != null) {
                    synchronized (observer) {
                        // log.info("notifyObserver for clientId:{}", clientId);
                        observer.onNext(watchResponse);
                    }
                } else {
                    log.info("cannot find StreamObserver for clientId:{}", clientId);
                }
            } catch (StatusRuntimeException ignored) {

            } catch (Exception e) {
                log.warn("notifyObserver with error:{}", clientId, e);
            }
        }
    }

    public void notifyAllObserver(String key, WatchType watchType, WatchKv[] kvs) throws
                                                                                  PDException {
        notifyObserver(key, watchType, equal, kvs);
        notifyObserver(key, watchType, startWith, kvs);
    }

    /**
     * 续活客户端
     * 1.往客户端发一个alive的消息，带重试哈
     * 2.如果有响应，则续活之前保存的那两组key
     * 3.如果多次都失败，则删除内存和rocksdb的数据
     */
    public void keepClientAlive() {
        WatchResponse testAlive = WatchResponse.newBuilder().setState(WatchState.Alive).build();
        Set<Map.Entry<String, StreamObserver<WatchResponse>>> entries = clients.entrySet();
        Map.Entry<String, StreamObserver<WatchResponse>>[] array =
                entries.toArray(new Map.Entry[0]);
        Arrays.stream(array).parallel().forEach(entry -> {
            StreamObserver<WatchResponse> value = entry.getValue();
            String key = entry.getKey();
            String delimiter = KvService.getDelimiter();
            String client = key.split(delimiter)[3];
            String clientKey = KvService.getKeyWithoutPrefix(ALL_PREFIX, client);
            if (value == null) {
                removeClient(null, key, clientKey);
            }
            boolean done = false;
            String removes = client + KvService.KV_DELIMITER;
            for (int i = 0; i < 3; i++) {
                try {
                    synchronized (value) {
                        value.onNext(testAlive);
                    }
                    Map<String, String> clientKeys = kvService.scanWithPrefix(clientKey);
                    for (Map.Entry<String, String> keyEntry : clientKeys.entrySet()) {
                        String entryKey = keyEntry.getKey();
                        String aliveKey = entryKey.replaceFirst(removes, "");
                        boolean keepAliveKey = kvService.keepAlive(aliveKey);
                        boolean keepAliveEntry = kvService.keepAlive(entryKey);
                        // log.info("keep alive client:{},{}:{},{}:{}", client, aliveKey,
                        // keepAliveKey,
                        //         entryKey,
                        //         keepAliveEntry);
                        done = true;
                    }
                    break;
                } catch (Exception e) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ex) {
                        log.info("keep alive client {} with error:{}", client, e);
                    }
                }
            }
            if (!done) {
                log.info("remove client {} for no data", client);
                removeClient(value, key, clientKey);
            }
        });
    }

    private void removeClient(StreamObserver<WatchResponse> value, String key, String clientKey) {
        try {
            log.info("remove null observer, client {}", clientKey);
            kvService.deleteWithPrefix(clientKey);
            if (value != null) {
                synchronized (value) {
                    value.onCompleted();
                }
            }
            clients.remove(key);
        } catch (PDException e) {
            log.error("remove client with error:", e);
        }
    }

    /**
     * 通知客户端leader切换了，重连
     */
    public void notifyClientChangeLeader() {
        WatchResponse response =
                WatchResponse.newBuilder().setState(WatchState.Leader_Changed).build();
        for (Map.Entry<String, StreamObserver<WatchResponse>> entry : clients.entrySet()) {
            StreamObserver<WatchResponse> value = entry.getValue();
            String key = entry.getKey();
            String client = key.split(KvService.getDelimiter())[3];
            String clientKey = KvService.getKeyWithoutPrefix(ALL_PREFIX, client);
            if (value == null) {
                removeClient(null, key, clientKey);
            }
            for (int i = 0; i < 3; i++) {
                try {
                    synchronized (value) {
                        value.onNext(response);
                    }
                    removeClient(value, key, clientKey);
                    break;
                } catch (Exception e) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        }
    }
}