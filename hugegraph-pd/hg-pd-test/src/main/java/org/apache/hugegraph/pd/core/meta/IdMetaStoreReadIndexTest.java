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

package org.apache.hugegraph.pd.core.meta;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import org.apache.hugegraph.pd.common.PDException;
import org.apache.hugegraph.pd.config.PDConfig;
import org.apache.hugegraph.pd.grpc.Pdpb;
import org.apache.hugegraph.pd.meta.IdMetaStore;
import org.apache.hugegraph.pd.meta.MetadataFactory;
import org.apache.hugegraph.pd.raft.RaftEngine;
import org.apache.hugegraph.pd.store.HgKVStore;
import org.apache.hugegraph.pd.store.RaftKVStore;
import org.apache.hugegraph.testutil.Whitebox;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link IdMetaStore#getId} and both {@code getCId} overloads read local state and then write
 * through raft as two steps, so the read must follow a raft ReadIndex: a leader elected a
 * moment ago may not have applied its predecessor's last write yet, and would hand out the
 * same id twice.
 */
public class IdMetaStoreReadIndexTest {

    private HgKVStore originalStore;
    private HgKVStore store;
    private IdMetaStore idMetaStore;

    @Before
    public void setUp() {
        // The metadata stores share one factory-held store; swap in a mock for this test
        this.originalStore = Whitebox.getInternalState(MetadataFactory.class, "store");
        this.store = mock(HgKVStore.class);
        Whitebox.setInternalState(MetadataFactory.class, "store", this.store);
        this.idMetaStore = new IdMetaStore(new PDConfig());
    }

    @After
    public void tearDown() {
        Whitebox.setInternalState(MetadataFactory.class, "store", this.originalStore);
    }

    @Test
    public void testGetIdReadsOnlyAfterTheReadIndex() throws PDException {
        when(this.store.get(any())).thenReturn(IdMetaStore.longToBytes(100L));

        Assert.assertEquals(100L, this.idMetaStore.getId("read-index", 10));

        InOrder order = inOrder(this.store);
        order.verify(this.store).waitReadIndex();
        order.verify(this.store).get(any());
        order.verify(this.store).put(any(), any());
    }

    @Test
    public void testGetIdLeavesTheCounterAloneWhenTheReadIndexFails() throws PDException {
        doThrow(new PDException(Pdpb.ErrorType.NOT_LEADER_VALUE, "not leader"))
                .when(this.store).waitReadIndex();

        PDException e = Assert.assertThrows(PDException.class, () -> {
            this.idMetaStore.getId("read-index", 10);
        });

        Assert.assertEquals(Pdpb.ErrorType.NOT_LEADER_VALUE, e.getErrorCode());
        verify(this.store, never()).get(any());
        verify(this.store, never()).put(any(), any());
    }

    @Test
    public void testGetCIdReadsOnlyAfterTheReadIndex() throws PDException {
        when(this.store.get(any())).thenReturn(IdMetaStore.longToBytes(5L));

        Assert.assertEquals(5L, this.idMetaStore.getCId("read-index", 16));

        InOrder order = inOrder(this.store);
        order.verify(this.store).waitReadIndex();
        order.verify(this.store).get(any());
        order.verify(this.store).scanRange(any(), any());
        order.verify(this.store, times(2)).put(any(), any());
    }

    @Test
    public void testGetCIdByNameReadsTheDelayedSlotOnlyAfterTheReadIndex()
            throws PDException {
        // A minimal in-memory store, so the delayed slot is written the way PD writes it
        Map<ByteBuffer, byte[]> data = new HashMap<>();
        doAnswer(inv -> data.put(ByteBuffer.wrap(inv.getArgument(0)), inv.getArgument(1)))
                .when(this.store).put(any(), any());
        when(this.store.get(any())).thenAnswer(inv -> {
            return data.get(ByteBuffer.wrap(inv.getArgument(0)));
        });
        this.idMetaStore.delCIdDelay("read-index", "graph", 7L);
        clearInvocations(this.store);

        Assert.assertEquals(7L, this.idMetaStore.getCId("read-index", "graph", 16));

        InOrder order = inOrder(this.store);
        order.verify(this.store).waitReadIndex();
        order.verify(this.store).scanPrefix(any());
        order.verify(this.store).get(any());
        order.verify(this.store).remove(any());
        verify(this.store, times(1)).waitReadIndex();
        verify(this.store, never()).put(any(), any());
    }

    @Test
    public void testGetCIdLeavesTheSlotsAloneWhenTheReadIndexFails() throws PDException {
        doThrow(new PDException(Pdpb.ErrorType.NOT_LEADER_VALUE, "not leader"))
                .when(this.store).waitReadIndex();

        PDException e = Assert.assertThrows(PDException.class, () -> {
            this.idMetaStore.getCId("read-index", 16);
        });
        Assert.assertEquals(Pdpb.ErrorType.NOT_LEADER_VALUE, e.getErrorCode());
        e = Assert.assertThrows(PDException.class, () -> {
            this.idMetaStore.getCId("read-index", "graph", 16);
        });
        Assert.assertEquals(Pdpb.ErrorType.NOT_LEADER_VALUE, e.getErrorCode());

        verify(this.store, never()).get(any());
        verify(this.store, never()).scanPrefix(any());
        verify(this.store, never()).scanRange(any(), any());
        verify(this.store, never()).put(any(), any());
        verify(this.store, never()).remove(any());
    }

    @Test
    public void testRaftStoreWaitsOnTheRaftEngine() throws PDException {
        RaftEngine engine = mock(RaftEngine.class);
        HgKVStore local = mock(HgKVStore.class);

        new RaftKVStore(engine, local).waitReadIndex();

        verify(engine).waitReadIndex();
    }
}
