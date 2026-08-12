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

import org.apache.hugegraph.ct.client.ClusterRestClient;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.ws.rs.core.Response;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ConsistencyTest extends BaseClusterTest {

    private static final Logger LOG = LoggerFactory.getLogger(ConsistencyTest.class);

    private static final int CONSISTENCY_POLL_MS = 2000;
    private static final int CONSISTENCY_TIMEOUT_MS = 30_000;

    @Test
    public void testWriteOnOneServerReadOnAnother() {
        if (clients.size() < 2) {
            return;
        }

        ClusterRestClient writer = clients.get(0);
        createBasicSchema(writer);

        String body = "{\"label\":\"person\",\"properties\":{\"name\":\"consistency_test\",\"age\":30}}";
        Response r = writer.createVertex(body);
        assertEquals(201, r.getStatus());
        String vertexContent = r.readEntity(String.class);
        assertTrue(vertexContent.contains("consistency_test"));
        String vertexId = extractId(vertexContent);

        for (int i = 1; i < clients.size(); i++) {
            ClusterRestClient reader = clients.get(i);
            boolean found = false;
            long deadline = System.currentTimeMillis() + CONSISTENCY_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                try {
                    Response readR = reader.get("/graph/vertices/" + formatIdForUrl(vertexId));
                    if (readR.getStatus() == 200) {
                        String content = readR.readEntity(String.class);
                        if (content.contains("consistency_test")) {
                            found = true;
                            break;
                        }
                    }
                    readR.close();
                } catch (Exception e) {
                    LOG.debug("Read from server {} failed, retrying: {}", i, e.getMessage());
                }
                try {
                    Thread.sleep(CONSISTENCY_POLL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(
                            "Interrupted while waiting for consistency on server " + i);
                }
            }
            assertTrue("Server " + i + " should see the vertex within " +
                       CONSISTENCY_TIMEOUT_MS / 1000 + "s", found);
        }
    }
}
