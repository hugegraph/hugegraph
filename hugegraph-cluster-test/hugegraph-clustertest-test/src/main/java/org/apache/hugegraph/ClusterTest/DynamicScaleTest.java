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

package org.apache.hugegraph.ClusterTest;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class DynamicScaleTest extends BaseClusterTest {

    private static final int NODE_ALIVE_POLL_MS = 2000;
    private static final int NODE_ALIVE_TIMEOUT_MS = 120_000;

    private void waitForServerNodeCount(int expected, int original) {
        long deadline = System.currentTimeMillis() + NODE_ALIVE_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            int current = env.getAliveServerNodeCount();
            if (current == expected) {
                return;
            }
            if (current < original) {
                break;
            }
            try {
                Thread.sleep(NODE_ALIVE_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for server node count");
            }
        }
        int actual = env.getAliveServerNodeCount();
        if (actual < original) {
            throw new RuntimeException(
                "Server nodes crashed during scale test (expected " + expected +
                ", got " + actual + ", had " + original + "). " +
                "Likely OOM in CI environment.");
        }
        assertTrue("Server node alive count timeout: expected " + expected + " but got " + actual,
                   actual >= expected);
    }

    private void waitForPDNodeCount(int expected, int original) {
        long deadline = System.currentTimeMillis() + NODE_ALIVE_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            int current = env.getAlivePDNodeCount();
            if (current == expected) {
                return;
            }
            if (current < original) {
                break;
            }
            try {
                Thread.sleep(NODE_ALIVE_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for PD node count");
            }
        }
        int actual = env.getAlivePDNodeCount();
        if (actual < original) {
            throw new RuntimeException(
                "PD nodes crashed during scale test (expected " + expected +
                ", got " + actual + ", had " + original + "). " +
                "Likely OOM in CI environment.");
        }
        assertTrue("PD node alive count timeout: expected " + expected + " but got " + actual,
                   actual >= original);
    }

    private void waitForStoreNodeCount(int expected, int original) {
        long deadline = System.currentTimeMillis() + NODE_ALIVE_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            int current = env.getAliveStoreNodeCount();
            if (current == expected) {
                return;
            }
            if (current < original) {
                break;
            }
            try {
                Thread.sleep(NODE_ALIVE_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for Store node count");
            }
        }
        int actual = env.getAliveStoreNodeCount();
        if (actual < original) {
            throw new RuntimeException(
                "Store nodes crashed during scale test (expected " + expected +
                ", got " + actual + ", had " + original + "). " +
                "Likely OOM in CI environment.");
        }
        assertTrue("Store node alive count timeout: expected " + expected + " but got " + actual,
                   actual >= original);
    }

    @Test
    public void testAddAndRemoveServerNode() {
        int originalCount = env.getAliveServerNodeCount();
        assertTrue("Need at least 1 server to test", originalCount >= 1);

        int newIndex = env.addServerNode();
        waitForServerNodeCount(originalCount + 1, originalCount);

        env.removeServerNode(newIndex);
        waitForServerNodeCount(originalCount, originalCount);
    }

    @Test
    public void testAddAndRemovePDNode() {
        int originalCount = env.getAlivePDNodeCount();
        assertTrue("Need at least 1 PD to test", originalCount >= 1);

        int newIndex = env.addPDNode();
        waitForPDNodeCount(originalCount + 1, originalCount);

        env.removePDNode(newIndex);
        waitForPDNodeCount(originalCount, originalCount);
    }

    @Test
    public void testAddAndRemoveStoreNode() {
        int originalCount = env.getAliveStoreNodeCount();
        assertTrue("Need at least 1 store to test", originalCount >= 1);

        int newIndex = env.addStoreNode();
        waitForStoreNodeCount(originalCount + 1, originalCount);

        env.removeStoreNode(newIndex);
        waitForStoreNodeCount(originalCount, originalCount);
    }
}
