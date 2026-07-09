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

package org.apache.hugegraph.rocksdb.provider;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Set;

import org.junit.Before;
import org.junit.Test;

public class RocksDBProviderLoaderTest {

    private RocksDBProviderLoader loader;

    @Before
    public void setUp() {
        loader = RocksDBProviderLoader.getInstance();
        loader.reload();
    }

    /**
     * Test: selectProvider("standard") should activate the StandardRocksDBProvider.
     * This verifies the core config-driven selection mechanism works for the default provider.
     */
    @Test
    public void testSelectProviderStandard() {
        RocksDBProvider provider = loader.selectProvider("standard");
        assertNotNull(provider);
        assertEquals("standard", provider.getProviderName());
        assertTrue(provider.isAvailable());
    }

    /**
     * Test: selectProvider("topling") should throw IllegalStateException with "not available"
     * message because SidePluginRepo class is not on the test classpath.
     * This ensures proper error reporting when the addon is not installed.
     */
    @Test
    public void testSelectProviderToplingUnavailable() {
        try {
            loader.selectProvider("topling");
            fail("Expected IllegalStateException for unavailable topling provider");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("not available"));
            assertTrue(e.getMessage().contains("topling"));
        }
    }

    /**
     * Test: selectProvider with an unknown name should throw IllegalStateException
     * with "not found" message and list available providers.
     * This ensures users get actionable error info for typos or misconfiguration.
     */
    @Test
    public void testSelectProviderNotFound() {
        try {
            loader.selectProvider("nonexistent");
            fail("Expected IllegalStateException for unknown provider");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("not found"));
            assertTrue(e.getMessage().contains("nonexistent"));
            assertTrue(e.getMessage().contains("standard"));
        }
    }

    /**
     * Test: selectProviderIfNeeded is idempotent - calling it multiple times with the same
     * name should not change the already-activated provider.
     * This ensures that multiple RocksDB instances opening in the same JVM don't conflict.
     */
    @Test
    public void testSelectProviderIfNeededIdempotent() {
        loader.selectProviderIfNeeded("standard");
        RocksDBProvider first = loader.getActiveProvider();

        // Second call with same name should be a no-op
        loader.selectProviderIfNeeded("standard");
        RocksDBProvider second = loader.getActiveProvider();

        assertSame(first, second);
    }

    /**
     * Test: selectProviderIfNeeded with a different name than the already-active provider
     * should throw IllegalStateException to surface configuration inconsistency.
     */
    @Test
    public void testSelectProviderIfNeededMismatchThrows() {
        loader.selectProviderIfNeeded("standard");

        try {
            loader.selectProviderIfNeeded("topling");
            fail("Expected IllegalStateException for provider mismatch");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("already active"));
            assertTrue(e.getMessage().contains("standard"));
            assertTrue(e.getMessage().contains("topling"));
        }
    }

    /**
     * Test: getActiveProvider() before any selectProvider call should throw IllegalStateException.
     * This ensures proper fail-fast behavior if the configuration step is missed.
     */
    @Test(expected = IllegalStateException.class)
    public void testGetActiveProviderBeforeSelect() {
        loader.getActiveProvider();
    }

    /**
     * Test: reload() clears the active provider, requiring selectProvider to be called again.
     * This verifies that hot-reload scenarios work correctly.
     */
    @Test
    public void testReloadClearsState() {
        loader.selectProvider("standard");
        assertNotNull(loader.getActiveProvider());

        loader.reload();

        try {
            loader.getActiveProvider();
            fail("Expected IllegalStateException after reload");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("No RocksDB provider has been activated"));
        }
    }

    /**
     * Test: getAvailableProviderNames() should discover both "standard" and "topling"
     * providers via SPI, regardless of their runtime availability.
     */
    @Test
    public void testGetAvailableProviderNames() {
        Set<String> names = loader.getAvailableProviderNames();
        assertTrue(names.contains("standard"));
        assertTrue(names.contains("topling"));
    }

    /**
     * Test: isProviderAvailable("standard") should return true in a normal environment.
     */
    @Test
    public void testIsProviderAvailableStandard() {
        assertTrue(loader.isProviderAvailable("standard"));
    }

    /**
     * Test: isProviderAvailable("topling") should return false when SidePluginRepo
     * is not on the classpath (standard test environment).
     */
    @Test
    public void testIsProviderAvailableToplingFalse() {
        assertFalse(loader.isProviderAvailable("topling"));
    }

    /**
     * Test: getProvider returns the provider instance or null without requiring activation.
     * Useful for inspection/diagnostic purposes.
     */
    @Test
    public void testGetProvider() {
        assertNotNull(loader.getProvider("standard"));
        assertNotNull(loader.getProvider("topling"));
        assertNull(loader.getProvider("unknown"));
    }
}
