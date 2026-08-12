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

import jakarta.ws.rs.core.Response;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class GremlinTest extends BaseClusterTest {

    @Test
    public void testGremlinVertexCount() {
        createBasicSchema();

        Response r1 = client.createVertex(
                "{\"label\":\"person\",\"properties\":{\"name\":\"g1\",\"age\":20}}");
        assertTrue("createVertex g1 expected 2xx, got " + r1.getStatus(),
                   r1.getStatus() >= 200 && r1.getStatus() < 300);
        r1.close();

        Response r2 = client.createVertex(
                "{\"label\":\"person\",\"properties\":{\"name\":\"g2\",\"age\":21}}");
        assertTrue("createVertex g2 expected 2xx, got " + r2.getStatus(),
                   r2.getStatus() >= 200 && r2.getStatus() < 300);
        r2.close();

        String body = "{\"gremlin\":\"g.V().hasLabel('person').count()\"}";
        Response r = client.executeGremlin(body);
        assertEquals(201, r.getStatus());
        String content = r.readEntity(String.class);
        assertTrue(content.contains("task_id"));
    }
}
