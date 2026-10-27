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

package org.apache.hugegraph.pd.core;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.hugegraph.pd.PartitionService;
import org.apache.hugegraph.pd.StoreNodeService;
import org.apache.hugegraph.pd.TaskScheduleService;
import org.apache.hugegraph.pd.common.KVPair;
import org.apache.hugegraph.pd.grpc.Metapb;
import org.apache.hugegraph.pd.meta.StoreInfoMeta;
import org.apache.hugegraph.pd.raft.RaftEngine;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

import com.alipay.sofa.jraft.Node;

public class StorePlacementSubmissionTest {

    @Test
    public void testMovePlanRevalidatesDestinationBeforeSubmission() throws Exception {
        StoreNodeService stores = Mockito.mock(StoreNodeService.class,
                                               Mockito.CALLS_REAL_METHODS);
        StoreInfoMeta meta = Mockito.mock(StoreInfoMeta.class);
        PartitionService partitions = Mockito.mock(PartitionService.class);
        Map<Long, Metapb.Store> current = new HashMap<>();
        Metapb.Store source = store(1L, Metapb.StoreState.Up);
        Metapb.Store destinationLease = store(2L, Metapb.StoreState.Up);
        current.put(source.getId(), source);
        current.put(destinationLease.getId(),
                    destinationLease.toBuilder().setState(Metapb.StoreState.Exiting).build());
        Mockito.when(meta.getActiveStores()).thenReturn(List.of(source, destinationLease));
        Mockito.when(meta.getStore(Mockito.anyLong())).thenAnswer(call ->
                current.get((Long) call.getArgument(0)));
        setField(StoreNodeService.class, stores, "storeInfoMeta", meta);

        TaskScheduleService tasks = Mockito.mock(TaskScheduleService.class,
                                                 Mockito.CALLS_REAL_METHODS);
        setField(TaskScheduleService.class, tasks, "storeService", stores);
        setField(TaskScheduleService.class, tasks, "partitionService", partitions);

        RaftEngine raft = RaftEngine.getInstance();
        Field nodeField = RaftEngine.class.getDeclaredField("raftNode");
        nodeField.setAccessible(true);
        Object previous = nodeField.get(raft);
        Node node = Mockito.mock(Node.class);
        Mockito.when(node.isLeader(true)).thenReturn(true);
        nodeField.set(raft, node);
        try {
            Map<Integer, KVPair<Long, Long>> stalePlan = new HashMap<>();
            stalePlan.put(0, new KVPair<>(source.getId(), destinationLease.getId()));
            Assert.assertTrue(tasks.movePartitions(stalePlan).isEmpty());
            Mockito.verify(partitions, Mockito.never())
                   .movePartitionsShard(Mockito.anyInt(), Mockito.anyLong(), Mockito.anyLong());

            current.put(destinationLease.getId(), destinationLease);
            Map<Integer, KVPair<Long, Long>> validPlan = new HashMap<>();
            validPlan.put(0, new KVPair<>(source.getId(), destinationLease.getId()));
            Assert.assertEquals(1, tasks.movePartitions(validPlan).size());
            Mockito.verify(partitions).movePartitionsShard(0, source.getId(),
                                                           destinationLease.getId());
        } finally {
            nodeField.set(raft, previous);
        }
    }

    private static Metapb.Store store(long id, Metapb.StoreState state) {
        return Metapb.Store.newBuilder().setId(id).setState(state).build();
    }

    private static void setField(Class<?> owner, Object target, String name, Object value)
            throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
