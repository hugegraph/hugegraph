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
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.hugegraph.store.options.HgStoreEngineOptions;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.alipay.sofa.jraft.conf.ConfigurationManager;
import com.alipay.sofa.jraft.entity.EnumOutter.EntryType;
import com.alipay.sofa.jraft.entity.LogEntry;
import com.alipay.sofa.jraft.entity.LogId;
import com.alipay.sofa.jraft.entity.codec.v2.LogEntryV2CodecFactory;
import com.alipay.sofa.jraft.option.LogStorageOptions;
import com.alipay.sofa.jraft.option.RaftOptions;
import com.alipay.sofa.jraft.storage.LogStorage;
import com.alipay.sofa.jraft.storage.impl.RocksDBLogStorage;
import com.alipay.sofa.jraft.storage.log.RocksDBSegmentLogStorage;

public class PartitionLogStorageTest {

    private static final int MIB = 1024 * 1024;
    private static final String LEGACY_SIZE_PROPERTY = "jraft.log_storage.segment.max.size.bytes";

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void testConfiguredSegmentSize() throws IOException {
        this.assertNewSegmentSize(16 * MIB);
        this.assertNewSegmentSize(64 * MIB);
    }

    @Test
    public void testDefaultSegmentSize() throws IOException {
        String previous = System.getProperty(LEGACY_SIZE_PROPERTY);
        try {
            System.clearProperty(LEGACY_SIZE_PROPERTY);
            int size = new HgStoreEngineOptions.RaftOptions().getMaxSegmentFileSize();
            assertEquals(1024 * MIB, size);
            this.assertNewSegmentSize(size);
        } finally {
            this.restoreLegacyProperty(previous);
        }
    }

    @Test
    public void testLegacySystemPropertyFallbackAndExplicitOverride() throws IOException {
        String previous = System.getProperty(LEGACY_SIZE_PROPERTY);
        try {
            System.setProperty(LEGACY_SIZE_PROPERTY, Integer.toString(16 * MIB));
            HgStoreEngineOptions.RaftOptions options = new HgStoreEngineOptions.RaftOptions();
            assertEquals(16 * MIB, options.getMaxSegmentFileSize());
            Path path = this.folder.newFolder().toPath();
            LogStorage storage = PartitionEngine.createLogStorage(path.toString(), new RaftOptions(), options);
            this.init(storage);
            try {
                this.append(storage, 1, new byte[8192]);
            } finally {
                storage.shutdown();
            }
            this.assertSegmentSizes(this.segmentFiles(path), 16 * MIB);
            this.assertNewSegmentSize(64 * MIB);
        } finally {
            this.restoreLegacyProperty(previous);
        }
    }

    @Test
    public void testPlainRocksDBStorage() throws IOException {
        Path path = this.folder.newFolder().toPath();
        HgStoreEngineOptions.RaftOptions options = new HgStoreEngineOptions.RaftOptions();
        options.setUseRocksDBSegmentLogStorage(false);
        LogStorage storage = PartitionEngine.createLogStorage(path.toString(), new RaftOptions(), options);
        assertTrue(storage instanceof RocksDBLogStorage);
        assertFalse(storage instanceof RocksDBSegmentLogStorage);
        this.init(storage);
        try {
            byte[] data = new byte[8192];
            this.append(storage, 1, data);
            assertEquals(ByteBuffer.wrap(data), storage.getEntry(1).getData());
            assertFalse(Files.exists(path.resolve("segments")));
        } finally {
            storage.shutdown();
        }
    }

    @Test
    public void testReopenLegacySegmentsWithSmallerSize() throws IOException {
        String previous = System.getProperty(LEGACY_SIZE_PROPERTY);
        try {
            System.clearProperty(LEGACY_SIZE_PROPERTY);
            this.reopenLegacySegmentsWithSmallerSize();
        } finally {
            this.restoreLegacyProperty(previous);
        }
    }

    private void reopenLegacySegmentsWithSmallerSize() throws IOException {
        Path path = this.folder.newFolder().toPath();
        // Reading an old record larger than the new 16 MiB maximum must use the
        // existing file's size rather than mapping only the new configured limit.
        byte[] data = new byte[20 * MIB];
        Arrays.fill(data, (byte) 73);
        LogStorage storage = RocksDBSegmentLogStorage.builder(path.toString(), new RaftOptions())
                                                    .setMaxSegmentFileSize(1024 * MIB).build();
        this.init(storage);
        try {
            this.append(storage, 1, data);
        } finally {
            storage.shutdown();
        }
        List<Path> legacySegments = this.segmentFiles(path);
        this.assertSegmentSizes(legacySegments, 1024L * MIB);

        for (int size : new int[]{64 * MIB, 16 * MIB}) {
            storage = this.create(path, size);
            this.init(storage);
            try {
                assertEquals(1L, storage.getFirstLogIndex());
                assertEquals(1L, storage.getTerm(1));
                assertEquals(ByteBuffer.wrap(data), storage.getEntry(1).getData());
                long index = storage.getLastLogIndex() + 1;
                this.append(storage, index, new byte[8192]);
                assertEquals(index, storage.getLastLogIndex());
            } finally {
                storage.shutdown();
            }
            this.assertSegmentSizes(legacySegments, 1024L * MIB);
        }
    }

    private void assertNewSegmentSize(int size) throws IOException {
        Path path = this.folder.newFolder().toPath();
        LogStorage storage = this.create(path, size);
        this.init(storage);
        try {
            this.append(storage, 1, new byte[8192]);
        } finally {
            storage.shutdown();
        }
        this.assertSegmentSizes(this.segmentFiles(path), size);
    }

    private LogStorage create(Path path, int size) {
        HgStoreEngineOptions.RaftOptions options = new HgStoreEngineOptions.RaftOptions();
        options.setMaxSegmentFileSize(size);
        return PartitionEngine.createLogStorage(path.toString(), new RaftOptions(), options);
    }

    private void init(LogStorage storage) {
        LogStorageOptions options = new LogStorageOptions();
        options.setGroupId("segment-size-test");
        options.setConfigurationManager(new ConfigurationManager());
        options.setLogEntryCodecFactory(LogEntryV2CodecFactory.getInstance());
        assertTrue(storage.init(options));
    }

    private void append(LogStorage storage, long index, byte[] data) {
        LogEntry entry = new LogEntry(EntryType.ENTRY_TYPE_DATA);
        entry.setId(new LogId(index, 1));
        entry.setData(ByteBuffer.wrap(data));
        assertTrue(storage.appendEntry(entry));
    }

    private List<Path> segmentFiles(Path path) throws IOException {
        try (Stream<Path> files = Files.list(path.resolve("segments"))) {
            return files.filter(file -> file.toString().endsWith(".s"))
                        .collect(Collectors.toList());
        }
    }

    private void assertSegmentSizes(List<Path> segments, long size) throws IOException {
        assertFalse(segments.isEmpty());
        for (Path segment : segments) {
            assertEquals(segment.toString(), size, Files.size(segment));
        }
    }

    private void restoreLegacyProperty(String previous) {
        if (previous == null) {
            System.clearProperty(LEGACY_SIZE_PROPERTY);
        } else {
            System.setProperty(LEGACY_SIZE_PROPERTY, previous);
        }
    }
}
