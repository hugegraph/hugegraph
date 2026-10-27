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

package org.apache.hugegraph.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.hugegraph.pd.grpc.Metapb;
import org.apache.hugegraph.store.business.BusinessHandler;
import org.apache.hugegraph.store.meta.GraphManager;
import org.apache.hugegraph.store.meta.MetadataKeyHelper;
import org.apache.hugegraph.store.meta.Partition;
import org.apache.hugegraph.store.meta.PartitionManager;
import org.apache.hugegraph.store.meta.ShardGroup;
import org.apache.hugegraph.store.meta.Store;
import org.apache.hugegraph.store.meta.StoreMetadata;
import org.apache.hugegraph.store.options.HgStoreEngineOptions;
import org.apache.hugegraph.store.pd.PdProvider;
import org.apache.hugegraph.store.util.PartitionMetaStoreWrapper;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.alipay.sofa.jraft.JRaftUtils;
import com.alipay.sofa.jraft.conf.ConfigurationManager;
import com.alipay.sofa.jraft.entity.EnumOutter.EntryType;
import com.alipay.sofa.jraft.entity.LogEntry;
import com.alipay.sofa.jraft.entity.LogId;
import com.alipay.sofa.jraft.entity.codec.v2.LogEntryV2CodecFactory;
import com.alipay.sofa.jraft.option.LogStorageOptions;
import com.alipay.sofa.jraft.option.RaftOptions;
import com.alipay.sofa.jraft.rpc.RpcServer;
import com.alipay.sofa.jraft.storage.LogStorage;
import com.alipay.sofa.jraft.storage.impl.RocksDBLogStorage;

public class PartitionRecoveryMembershipTest {

    private static final int GROUP = 0;
    private static final long SELF = 3L;
    private static final String SELF_ADDRESS = "127.0.0.1:18539";
    private static final String OTHER_ADDRESS = "127.0.0.1:18538";

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private final HgStoreEngine engine = HgStoreEngine.getInstance();
    private final Map<String, Object> originalFields = new HashMap<>();
    private PdProvider pd;
    private PartitionManager manager;
    private PartitionMetaStoreWrapper wrapper;
    private StoreMetadata metadata;
    private HgStoreEngineOptions options;
    private List<Metapb.Partition> persisted;
    private AtomicReference<Metapb.ShardGroup> localGroup;
    private Metapb.ShardGroup directGroup;
    private Path data;
    private Map<Integer, PartitionEngine> engines;

    @Before
    @SuppressWarnings("unchecked")
    public void setUp() throws Exception {
        data = folder.newFolder().toPath();
        Files.createDirectories(data.resolve("db/00000"));
        Files.createDirectories(data.resolve("raft/00000/log"));
        options = new HgStoreEngineOptions();
        options.setDataPath(data.toString());
        options.setRaftPath(data.toString());
        options.setRaftAddress(SELF_ADDRESS);
        options.getRaftOptions().setUseRocksDBSegmentLogStorage(false);
        options.getRaftOptions().setSnapshotIntervalSecs(0);
        pd = mock(PdProvider.class);
        manager = new PartitionManager(pd, options);
        wrapper = mock(PartitionMetaStoreWrapper.class);
        metadata = mock(StoreMetadata.class);
        setField(manager, "wrapper", wrapper);
        setField(manager, "storeMetadata", metadata);
        setField(manager, "graphManager", mock(GraphManager.class));
        persisted = new ArrayList<>(List.of(partition("graph")));
        localGroup = new AtomicReference<>(group(1L));
        directGroup = group(SELF, 1L);
        Store self = store(SELF, SELF_ADDRESS);
        when(metadata.getStore()).thenReturn(self);
        when(metadata.getPartitionStore(GROUP)).thenReturn(Metapb.PartitionStore.newBuilder()
                .setPartitionId(GROUP).setStoreLocation(data.toString()).build());
        when(metadata.getPartitionRaft(GROUP)).thenReturn(Metapb.PartitionRaft.newBuilder()
                .setPartitionId(GROUP).setRaftLocation(data.toString()).build());
        when(metadata.getPartitionRaftLocation(GROUP)).thenReturn(data.toString());
        when(metadata.getPartitionStoreLocation(eq(GROUP), anyString())).thenReturn(data.toString());
        when(metadata.getPartitionStores()).thenAnswer(call -> List.of(metadata.getPartitionStore(GROUP)));
        when(wrapper.scan(eq(GROUP), eq(Metapb.Partition.parser()), any(byte[].class)))
                .thenAnswer(call -> new ArrayList<>(persisted));
        when(wrapper.get(eq(GROUP), any(byte[].class), eq(Metapb.ShardGroup.parser())))
                .thenAnswer(call -> localGroup.get());
        doAnswer(call -> {
            byte[] key = call.getArgument(1);
            if (Arrays.equals(key, MetadataKeyHelper.getShardGroupKey(GROUP))) {
                localGroup.set(Metapb.ShardGroup.parseFrom((byte[]) call.getArgument(2)));
            }
            return null;
        }).when(wrapper).put(eq(GROUP), any(byte[].class), any(byte[].class));
        when(pd.getShardGroupDirect(GROUP)).thenAnswer(call -> directGroup);
        when(pd.getShardGroup(GROUP)).thenAnswer(call -> directGroup);
        when(pd.getPartitionByID(anyString(), eq(GROUP)))
                .thenAnswer(call -> new Partition(partition(call.getArgument(0))));
        when(pd.getPartitionsByStore(SELF)).thenAnswer(call -> {
            List<Partition> parts = new ArrayList<>();
            persisted.forEach(p -> parts.add(new Partition(p)));
            return parts;
        });
        when(pd.getStoreByID(anyLong())).thenAnswer(call -> {
            long id = call.getArgument(0);
            return id == SELF ? self : store(id, OTHER_ADDRESS);
        });
        Field map = HgStoreEngine.class.getDeclaredField("partitionEngines");
        map.setAccessible(true);
        engines = (Map<Integer, PartitionEngine>) map.get(engine);
        assertTrue("Recovery tests require an isolated Store", engines.isEmpty());
        replaceEngineField("partitionManager", manager);
        replaceEngineField("options", options);
        replaceEngineField("pdProvider", pd);
        replaceEngineField("businessHandler", mock(BusinessHandler.class));
        replaceEngineField("heartbeatService", mock(HeartbeatService.class));
        RpcServer rpc = mock(RpcServer.class);
        when(rpc.boundPort()).thenReturn(18539);
        replaceEngineField("rpcServer", rpc);
    }

    @After
    public void tearDown() throws Exception {
        if (engines == null) {
            return;
        }
        for (PartitionEngine partition : engines.values()) {
            if (!org.mockito.Mockito.mockingDetails(partition).isMock()) {
                partition.shutdown();
            }
        }
        engines.clear();
        for (Map.Entry<String, Object> field : originalFields.entrySet()) {
            setField(engine, field.getKey(), field.getValue());
        }
    }

    @Test
    public void testRestoresStaleDerivedGroupWithoutReplacingDurableRaftConfiguration()
            throws Exception {
        LogStorage storage = new RocksDBLogStorage(data.resolve("raft/00000/log").toString(), new RaftOptions());
        LogStorageOptions logOptions = new LogStorageOptions();
        logOptions.setGroupId("hg_0");
        logOptions.setConfigurationManager(new ConfigurationManager());
        logOptions.setLogEntryCodecFactory(LogEntryV2CodecFactory.getInstance());
        assertTrue(storage.init(logOptions));
        try {
            LogEntry conf = new LogEntry(EntryType.ENTRY_TYPE_CONFIGURATION);
            conf.setId(new LogId(1, 1));
            conf.setPeers(List.of(JRaftUtils.getPeerId(SELF_ADDRESS)));
            assertTrue(storage.appendEntry(conf));
        } finally {
            storage.shutdown();
        }

        manager.loadPartition();
        assertTrue(localGroup.get().getShardsList().stream().anyMatch(s -> s.getStoreId() == SELF));
        engine.restoreLocalPartitionEngine();

        PartitionEngine restored = engine.getPartitionEngine(GROUP);
        assertNotNull("Stale metadata must not filter out the persisted group", restored);
        assertNotNull(restored.waitForLeader(5000));
        // PD's bootstrap includes another peer; JRaft must retain its durable one-voter config.
        assertEquals(List.of(JRaftUtils.getPeerId(SELF_ADDRESS)), restored.getRaftNode().listPeers());
        assertTrue(manager.hasPartition("graph", GROUP));
        verify(wrapper, never()).delete(anyInt(), any(byte[].class));
    }

    @Test
    public void testSharedGroupRefreshesOnce() throws Exception {
        persisted.add(partition("other-graph"));

        manager.loadPartition();
        manager.syncPartitionsFromPD(p -> {
            throw new AssertionError("A current member must not be removed");
        });

        assertTrue(manager.hasPartition("graph", GROUP));
        assertTrue(manager.hasPartition("other-graph", GROUP));
        verify(pd, times(1)).getShardGroupDirect(GROUP);
        verify(wrapper, times(1)).put(eq(GROUP), eq(MetadataKeyHelper.getShardGroupKey(GROUP)),
                                    any(byte[].class));
    }

    @Test
    public void testNullDirectGroupPreservesMetadata() {
        directGroup = null;
        assertRecoveryBlocked();
    }

    @Test
    public void testPdFailurePreservesMetadata() {
        when(pd.getShardGroupDirect(GROUP)).thenThrow(new IllegalStateException("PD unavailable"));
        assertRecoveryBlocked();
    }

    @Test
    public void testCorruptDirectGroupsPreserveMetadata() {
        for (Metapb.ShardGroup corrupt : List.of(group(SELF).toBuilder().setId(1).build(),
                group(), group(0L), group(SELF, SELF), group(SELF).toBuilder()
                .setShards(0, Metapb.Shard.newBuilder().setStoreId(SELF)
                                          .setRole(Metapb.ShardRole.None)).build())) {
            directGroup = corrupt;
            assertRecoveryBlocked();
        }
    }

    @Test
    public void testMissingOrMismatchedPdPartitionPreservesMetadata() {
        when(pd.getPartitionByID("graph", GROUP)).thenReturn(null);
        assertRecoveryBlocked();
        when(pd.getPartitionByID("graph", GROUP)).thenReturn(new Partition(partition("wrong")));
        assertRecoveryBlocked();
    }

    @Test
    public void testPdPartitionFailurePreservesMetadata() {
        when(pd.getPartitionByID("graph", GROUP)).thenThrow(new IllegalStateException("PD unavailable"));
        assertRecoveryBlocked();
    }

    @Test
    public void testRunningEngineIsNotOverwritten() {
        PartitionEngine running = mock(PartitionEngine.class);
        ShardGroup observed = ShardGroup.from(group(SELF));
        when(running.getShardGroup()).thenReturn(observed);
        engines.put(GROUP, running);
        Metapb.ShardGroup original = localGroup.get();

        manager.loadPartition();

        assertSame(original, localGroup.get());
        assertSame(observed, running.getShardGroup());
        verify(wrapper, never()).put(eq(GROUP), eq(MetadataKeyHelper.getShardGroupKey(GROUP)),
                                    any(byte[].class));
    }

    @Test
    public void testPdExclusionBlocksRecoveryWithoutRetiringData() {
        directGroup = group(1L);
        assertRecoveryBlocked();
        assertFalse(manager.hasPartition("graph", GROUP));
    }

    @Test
    public void testNoPersistentPartitionDoesNotRefreshGroup() {
        persisted.clear();
        manager.loadPartition();
        verify(pd, never()).getShardGroupDirect(GROUP);
        verify(wrapper, never()).put(anyInt(), any(byte[].class), any(byte[].class));
    }

    @Test
    public void testLearnerWithoutLeaderCanRecoverStartupMetadata() {
        directGroup = group(SELF).toBuilder().setShards(0, Metapb.Shard.newBuilder()
                .setStoreId(SELF).setRole(Metapb.ShardRole.Learner)).build();
        manager.loadPartition();
        assertEquals(directGroup, localGroup.get());
    }

    private void assertRecoveryBlocked() {
        Metapb.ShardGroup original = localGroup.get();
        assertThrows(PartitionManager.InvalidShardException.class, manager::loadPartition);
        assertSame(original, localGroup.get());
        assertEquals(1, persisted.size());
        verify(wrapper, never()).put(anyInt(), any(byte[].class), any(byte[].class));
        verify(wrapper, never()).delete(anyInt(), any(byte[].class));
        verify(metadata, never()).removePartitionStore(anyInt());
    }

    private void replaceEngineField(String name, Object value) throws Exception {
        Field field = HgStoreEngine.class.getDeclaredField(name);
        field.setAccessible(true);
        originalFields.put(name, field.get(engine));
        field.set(engine, value);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Metapb.Partition partition(String graph) {
        return Metapb.Partition.newBuilder().setId(GROUP).setGraphName(graph)
                               .setEndKey(65535).setState(Metapb.PartitionState.PState_Normal).build();
    }

    private static Metapb.ShardGroup group(long... stores) {
        Metapb.ShardGroup.Builder group = Metapb.ShardGroup.newBuilder().setId(GROUP);
        for (long id : stores) {
            group.addShards(Metapb.Shard.newBuilder().setStoreId(id)
                                  .setRole(Metapb.ShardRole.Follower));
        }
        return group.build();
    }

    private static Store store(long id, String address) {
        Store store = new Store();
        store.setId(id);
        store.setRaftAddress(address);
        return store;
    }
}
