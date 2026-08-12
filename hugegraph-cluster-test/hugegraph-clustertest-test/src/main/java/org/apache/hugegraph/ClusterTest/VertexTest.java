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

import java.util.Map;

import org.junit.Test;

import jakarta.ws.rs.core.Response;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class VertexTest extends BaseClusterTest {

    @Test
    public void testCreateAndQueryVertex() {
        createBasicSchema();

        String body = "{\"label\":\"person\",\"properties\":{\"name\":\"tom\",\"age\":25}}";
        Response r = client.createVertex(body);
        assertEquals(201, r.getStatus());
        String content = r.readEntity(String.class);
        assertTrue(content.contains("\"name\":\"tom\""));

        Map<String, String> params = queryParams("label", "person");
        r = client.getVertices(params);
        assertEquals(200, r.getStatus());
        content = r.readEntity(String.class);
        assertTrue(content.contains("tom"));
    }

    @Test
    public void testUpdateVertexProperty() {
        createBasicSchema();

        String body = "{\"label\":\"person\",\"properties\":{\"name\":\"alice\",\"age\":30}}";
        Response r = client.createVertex(body);
        assertEquals(201, r.getStatus());
        String vertexId = extractId(r.readEntity(String.class));

        String updateBody = "{\"properties\":{\"age\":31}}";
        Map<String, String> params = queryParams("action", "append");
        r = client.put("/graph/vertices/" + formatIdForUrl(vertexId), updateBody, params);
        assertEquals(200, r.getStatus());
    }

    @Test
    public void testDeleteVertex() {
        createBasicSchema();

        String body = "{\"label\":\"person\",\"properties\":{\"name\":\"bob\",\"age\":22}}";
        Response r = client.createVertex(body);
        assertEquals(201, r.getStatus());
        String vertexId = extractId(r.readEntity(String.class));

        r = client.deleteVertex(formatIdForUrl(vertexId));
        assertEquals(204, r.getStatus());
    }

    @Test
    public void testBatchCreateVertices() {
        createBasicSchema();

        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 50; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"label\":\"person\",\"properties\":{\"name\":\"user")
              .append(i).append("\",\"age\":").append(i % 50 + 20).append("}}");
        }
        sb.append("]");
        Response r = client.post("/graph/vertices/batch", sb.toString());
        assertEquals(201, r.getStatus());
    }
}
