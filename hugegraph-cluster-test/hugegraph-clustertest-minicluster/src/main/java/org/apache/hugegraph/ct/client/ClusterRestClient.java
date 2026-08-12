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

package org.apache.hugegraph.ct.client;

import java.util.Map;

import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.client.authentication.HttpAuthenticationFeature;
import org.glassfish.jersey.message.GZipEncoder;
import org.glassfish.jersey.client.filter.EncodingFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClusterRestClient {

    private static final Logger LOG = LoggerFactory.getLogger(ClusterRestClient.class);

    private static final String DEFAULT_USER = "admin";
    private static final String DEFAULT_PASSWORD = "pa";
    private static final String GRAPHSPACES_PREFIX = "graphspaces/DEFAULT/graphs";

    private final Client client;
    private final String baseUrl;
    private final String graphName;

    public ClusterRestClient(String baseUrl, String graphName) {
        this(baseUrl, graphName, DEFAULT_USER, DEFAULT_PASSWORD);
    }

    public ClusterRestClient(String baseUrl, String graphName,
                             String user, String password) {
        this.baseUrl = baseUrl;
        this.graphName = graphName;
        ClientConfig config = new ClientConfig();
        config.register(HttpAuthenticationFeature.basic(user, password));
        config.register(EncodingFilter.class);
        config.register(GZipEncoder.class);
        this.client = ClientBuilder.newBuilder()
                        .withConfig(config)
                        .build();
    }

    private String graphPrefix() {
        return String.format("%s/%s/%s", baseUrl, GRAPHSPACES_PREFIX, graphName);
    }

    private WebTarget target(String url) {
        return client.target(url);
    }

    private WebTarget targetWithParams(String url, Map<String, String> queryParams) {
        WebTarget t = client.target(url);
        if (queryParams != null) {
            for (Map.Entry<String, String> entry : queryParams.entrySet()) {
                t = t.queryParam(entry.getKey(), entry.getValue());
            }
        }
        return t;
    }

    public Response get(String path) {
        return get(path, null);
    }

    public Response get(String path, Map<String, String> queryParams) {
        WebTarget t = targetWithParams(graphPrefix() + path, queryParams);
        return t.request(MediaType.APPLICATION_JSON_TYPE).get();
    }

    public Response post(String path, Object body) {
        return post(path, body, null);
    }

    public Response post(String path, Object body, Map<String, String> queryParams) {
        WebTarget t = targetWithParams(graphPrefix() + path, queryParams);
        return t.request(MediaType.APPLICATION_JSON_TYPE)
                .post(Entity.entity(body, MediaType.APPLICATION_JSON_TYPE));
    }

    public Response put(String path, Object body) {
        return put(path, body, null);
    }

    public Response put(String path, Object body, Map<String, String> queryParams) {
        WebTarget t = targetWithParams(graphPrefix() + path, queryParams);
        return t.request(MediaType.APPLICATION_JSON_TYPE)
                .put(Entity.entity(body, MediaType.APPLICATION_JSON_TYPE));
    }

    public Response delete(String path) {
        return delete(path, null);
    }

    public Response delete(String path, Map<String, String> queryParams) {
        WebTarget t = targetWithParams(graphPrefix() + path, queryParams);
        return t.request(MediaType.APPLICATION_JSON_TYPE).delete();
    }

    public Response createGraph(String graphBody) {
        return post("", graphBody);
    }

    public Response dropGraph() {
        return delete("");
    }

    public Response createPropertyKey(Object propertyKeyBody) {
        return post("/schema/propertykeys", propertyKeyBody);
    }

    public Response createVertexLabel(Object vertexLabelBody) {
        return post("/schema/vertexlabels", vertexLabelBody);
    }

    public Response createEdgeLabel(Object edgeLabelBody) {
        return post("/schema/edgelabels", edgeLabelBody);
    }

    public Response createIndexLabel(Object indexLabelBody) {
        return post("/schema/indexlabels", indexLabelBody);
    }

    public Response createVertex(Object vertexBody) {
        return post("/graph/vertices", vertexBody);
    }

    public Response createEdge(Object edgeBody) {
        return post("/graph/edges", edgeBody);
    }

    public Response getVertices() {
        return get("/graph/vertices");
    }

    public Response getVertices(Map<String, String> queryParams) {
        return get("/graph/vertices", queryParams);
    }

    public Response getEdges() {
        return get("/graph/edges");
    }

    public Response getEdges(Map<String, String> queryParams) {
        return get("/graph/edges", queryParams);
    }

    public Response deleteVertex(String vertexId) {
        return delete("/graph/vertices/" + vertexId);
    }

    public Response deleteEdge(String edgeId) {
        return delete("/graph/edges/" + edgeId);
    }

    public Response executeGremlin(Object gremlinBody) {
        String url = String.format("%s/jobs/gremlin", graphPrefix());
        return client.target(url)
                .request(MediaType.APPLICATION_JSON_TYPE)
                .post(Entity.entity(gremlinBody, MediaType.APPLICATION_JSON_TYPE));
    }

    public void assertStatus(Response response, int expectedStatus) {
        int actualStatus = response.getStatus();
        if (actualStatus != expectedStatus) {
            String msg = String.format("Expected status %d but got %d: %s",
                                       expectedStatus, actualStatus,
                                       response.readEntity(String.class));
            LOG.error(msg);
            throw new AssertionError(msg);
        }
    }

    public void close() {
        if (this.client != null) {
            this.client.close();
        }
    }
}
