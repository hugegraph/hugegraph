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
import static org.junit.Assert.fail;

import org.junit.Before;
import org.junit.Test;

public class ToplingRocksDBProviderTest {

    private ToplingRocksDBProvider provider;

    @Before
    public void setUp() {
        provider = new ToplingRocksDBProvider();
    }

    /**
     * Test: getProviderName() returns "topling" - the identifier used for config matching.
     * Users set rocksdb.provider=topling to activate this provider.
     */
    @Test
    public void testProviderName() {
        assertEquals("topling", provider.getProviderName());
    }

    /**
     * Test: isAvailable() returns false when org.rocksdb.SidePluginRepo is not on the classpath.
     * In a standard test environment (using vanilla rocksdbjni), ToplingDB features
     * are not available. This is the expected state for most developers.
     */
    @Test
    public void testIsNotAvailableWithoutSidePluginRepo() {
        assertFalse(provider.isAvailable());
    }

    /**
     * Test: Attempting to selectProvider("topling") via the loader throws IllegalStateException
     * with a message indicating the provider is found but not available.
     * This simulates the error a user would see if they configure rocksdb.provider=topling
     * without installing the ToplingDB addon.
     */
    @Test
    public void testSelectToplingThrowsWhenUnavailable() {
        RocksDBProviderLoader loader = RocksDBProviderLoader.getInstance();
        loader.reload();

        try {
            loader.selectProvider("topling");
            fail("Expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertNotNull(e.getMessage());
            // Should mention the provider is found but not available
            assertEquals(true, e.getMessage().contains("not available"));
        }
    }

    /**
     * Test: The topling provider is still discoverable via getProvider even when unavailable.
     * This distinguishes between "provider JAR not on classpath" (not found)
     * and "provider JAR present but native libs missing" (found but not available).
     */
    @Test
    public void testProviderDiscoverableViaLoader() {
        RocksDBProviderLoader loader = RocksDBProviderLoader.getInstance();
        loader.reload();

        RocksDBProvider found = loader.getProvider("topling");
        assertNotNull(found);
        assertEquals("topling", found.getProviderName());
        assertFalse(found.isAvailable());
    }
}
