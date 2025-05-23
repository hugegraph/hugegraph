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

package org.apache.hugegraph.meta;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.apache.hugegraph.meta.lock.LockResult;

public interface MetaDriver {

    void put(String key, String value);

    String get(String key);

    void delete(String key);

    void deleteWithPrefix(String prefix);

    Map<String, String> scanWithPrefix(String prefix);

    <T> void listen(String key, Consumer<T> consumer);

    <T> void listenPrefix(String prefix, Consumer<T> consumer);

    <T> List<String> extractValuesFromResponse(T response);

    /**
     * Extract K-V pairs of response
     *
     * @param <T>
     * @param response
     * @return
     */
    <T> Map<String, String> extractKVFromResponse(T response);

    LockResult tryLock(String key, long ttl, long timeout);

    /**
     * return if the key is Locked.
     *
     * @param key
     * @return bool
     */
    boolean isLocked(String key);

    void unlock(String key, LockResult lockResult);

    /**
     * keepAlive of current lease
     *
     * @param key
     * @param lease
     * @return next leaseId
     */
    long keepAlive(String key, long lease);
}
