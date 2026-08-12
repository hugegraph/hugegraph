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

import org.junit.Assert;
import org.junit.Test;

import jakarta.ws.rs.core.Response;

public class HybridClusterTest extends BaseClusterTest {

    @Test
    public void testHybridClusterNodeCount() {
        Assert.assertTrue("At least 1 PD node expected",
                env.getAlivePDNodeCount() >= 1);
        Assert.assertTrue("At least 1 Server node expected",
                env.getAliveServerNodeCount() >= 1);
        Assert.assertTrue("At least 1 Store node expected",
                env.getAliveStoreNodeCount() >= 1);
    }

    @Test
    public void testRealStoreDataOperations() {
        createBasicSchema();

        Response r = client.createVertex(
                "{\"label\":\"person\",\"properties\":{\"name\":\"hybrid-test\",\"age\":30}}");
        Assert.assertEquals(201, r.getStatus());

        String content = r.readEntity(String.class);
        Assert.assertTrue(content.contains("hybrid-test"));
    }
}
