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

public class EdgeTest extends BaseClusterTest {

    @Test
    public void testCreateAndQueryEdge() {
        createBasicSchema();

        String v1 = "{\"label\":\"person\",\"properties\":{\"name\":\"alice\",\"age\":30}}";
        Response r1 = client.createVertex(v1);
        assertEquals(201, r1.getStatus());
        String id1 = extractId(r1.readEntity(String.class));

        String v2 = "{\"label\":\"person\",\"properties\":{\"name\":\"bob\",\"age\":25}}";
        Response r2 = client.createVertex(v2);
        assertEquals(201, r2.getStatus());
        String id2 = extractId(r2.readEntity(String.class));

        String edgeBody = "{\"label\":\"knows\",\"outV\":\"" + id1 +
                          "\",\"inV\":\"" + id2 +
                          "\",\"properties\":{\"weight\":0.8}}";
        Response re = client.createEdge(edgeBody);
        assertEquals(201, re.getStatus());
        String content = re.readEntity(String.class);
        assertTrue(content.contains("\"label\":\"knows\""));

        Map<String, String> params = queryParams("label", "knows");
        re = client.getEdges(params);
        assertEquals(200, re.getStatus());
        content = re.readEntity(String.class);
        assertTrue(content.contains("knows"));
    }

    @Test
    public void testDeleteEdge() {
        createBasicSchema();

        String v1 = "{\"label\":\"person\",\"properties\":{\"name\":\"eve\",\"age\":28}}";
        Response r1 = client.createVertex(v1);
        assertEquals(201, r1.getStatus());
        String id1 = extractId(r1.readEntity(String.class));

        String v2 = "{\"label\":\"person\",\"properties\":{\"name\":\"carol\",\"age\":35}}";
        Response r2 = client.createVertex(v2);
        assertEquals(201, r2.getStatus());
        String id2 = extractId(r2.readEntity(String.class));

        String edgeBody = "{\"label\":\"knows\",\"outV\":\"" + id1 +
                          "\",\"inV\":\"" + id2 +
                          "\",\"properties\":{\"weight\":0.5}}";
        Response re = client.createEdge(edgeBody);
        assertEquals(201, re.getStatus());
        String edgeId = extractId(re.readEntity(String.class));

        re = client.deleteEdge(edgeId);
        assertEquals(204, re.getStatus());
    }
}
