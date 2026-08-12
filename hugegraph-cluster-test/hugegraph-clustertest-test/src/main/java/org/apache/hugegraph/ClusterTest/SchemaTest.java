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

public class SchemaTest extends BaseClusterTest {

    @Test
    public void testPropertyKeyCRUD() {
        String body = "{\"name\":\"pk_test\",\"data_type\":\"TEXT\"," +
                      "\"cardinality\":\"SINGLE\",\"check_exist\":false}";
        Response r = client.createPropertyKey(body);
        assertTrue("Expected 2xx, got " + r.getStatus(),
                   r.getStatus() >= 200 && r.getStatus() < 300);

        r = client.get("/schema/propertykeys/pk_test");
        assertEquals(200, r.getStatus());
        String content = r.readEntity(String.class);
        assertTrue(content.contains("\"name\":\"pk_test\""));
    }

    @Test
    public void testVertexLabelCRUD() {
        client.createPropertyKey("{\"name\":\"vl_name\",\"data_type\":\"TEXT\"," +
                                 "\"cardinality\":\"SINGLE\",\"check_exist\":false}");

        String body = "{\"name\":\"vl_test\",\"id_strategy\":\"PRIMARY_KEY\"," +
                      "\"primary_keys\":[\"vl_name\"],\"properties\":[\"vl_name\"]," +
                      "\"check_exist\":false}";
        Response r = client.createVertexLabel(body);
        assertTrue("Expected 2xx, got " + r.getStatus(),
                   r.getStatus() >= 200 && r.getStatus() < 300);

        r = client.get("/schema/vertexlabels/vl_test");
        assertEquals(200, r.getStatus());
        assertTrue(r.readEntity(String.class).contains("\"name\":\"vl_test\""));
    }

    @Test
    public void testEdgeLabelCRUD() {
        createBasicSchema();

        String body = "{\"name\":\"el_test\",\"source_label\":\"person\"," +
                      "\"target_label\":\"person\",\"properties\":[\"weight\"]," +
                      "\"check_exist\":false}";
        Response r = client.createEdgeLabel(body);
        assertTrue("Expected 2xx, got " + r.getStatus(),
                   r.getStatus() >= 200 && r.getStatus() < 300);

        r = client.get("/schema/edgelabels/el_test");
        assertEquals(200, r.getStatus());
        assertTrue(r.readEntity(String.class).contains("\"name\":\"el_test\""));
    }

    @Test
    public void testIndexLabelCRUD() {
        createBasicSchema();

        String body = "{\"name\":\"ageIdx\",\"base_type\":\"VERTEX_LABEL\"," +
                      "\"base_value\":\"person\",\"index_type\":\"RANGE\"," +
                      "\"fields\":[\"age\"],\"check_exist\":false}";
        Response r = client.createIndexLabel(body);
        assertTrue("Expected 2xx, got " + r.getStatus() + ": " +
                   (r.getStatus() >= 300 ? r.readEntity(String.class) : ""),
                   r.getStatus() >= 200 && r.getStatus() < 300);

        r = client.get("/schema/indexlabels/ageIdx");
        assertEquals(200, r.getStatus());
        assertTrue(r.readEntity(String.class).contains("\"name\":\"ageIdx\""));
    }
}
