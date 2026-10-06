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

/**
 * Hands a committed graph record change to schema sync dispatch. Called on the raft apply
 * thread of every node, right after the write batch of a successful TXN that carries a record;
 * never for a failed or replayed TXN. It must not block. What it throws is logged and dropped:
 * the TXN is committed whatever the listener does.
 */
public interface TxnApplyListener {

    /**
     * @param index       the raft log index of the TXN
     * @param recordKey   the key of the graph record
     * @param revision    the record's new revision, equal to index
     * @param incarnation the record's incarnation after the TXN
     * @param state       LIVE or DROPPED
     */
    void onTxnApplied(long index, String recordKey, long revision, long incarnation,
                      String state);
}
