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

import java.util.List;
import java.util.Map;

import jakarta.ws.rs.core.Response;

import org.apache.hugegraph.ct.base.ClusterConstant;
import org.apache.hugegraph.ct.env.BaseEnv;
import org.apache.hugegraph.ct.env.EnvFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HugeGraphCluster implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(HugeGraphCluster.class);

    private static final String DEFAULT_GRAPH_NAME = "hugegraph";
    private static final int RETRY_INTERVAL_MS = 1000;
    private static final int MAX_RETRIES = 30;

    private final BaseEnv env;
    private final String graphName;
    private ClusterRestClient client;

    public HugeGraphCluster(int pdCount, int storeCount, int serverCount,
                            String graphName) {
        this.env = EnvFactory.getEnv(pdCount, storeCount, serverCount);
        this.graphName = graphName != null ? graphName : DEFAULT_GRAPH_NAME;
    }

    public void start() {
        LOG.info("Starting HugeGraph cluster");
        env.startCluster();
        List<String> serverAddrs = env.getServerRestAddrs();
        if (serverAddrs == null || serverAddrs.isEmpty()) {
            throw new IllegalStateException("No server addresses available after cluster start");
        }
        String baseUrl = "http://" + serverAddrs.get(0);
        this.client = new ClusterRestClient(baseUrl, this.graphName);
        ensureGraphExists();
        LOG.info("HugeGraph cluster started, server at {}", baseUrl);
    }

    private void ensureGraphExists() {
        String graphBody = String.format(
                "{\"name\":\"%s\"}", this.graphName);
        Response resp = this.client.createGraph(graphBody);
        int status = resp.getStatus();
        if (status == 200 || status == 201 || status == 202) {
            LOG.info("Graph '{}' created", this.graphName);
        } else if (status == 400) {
            LOG.info("Graph '{}' already exists", this.graphName);
        } else {
            LOG.warn("Unexpected status {} when creating graph '{}': {}",
                     status, this.graphName, resp.readEntity(String.class));
        }
    }

    public void stop() {
        LOG.info("Stopping HugeGraph cluster");
        if (this.client != null) {
            this.client.close();
            this.client = null;
        }
        env.stopCluster();
        LOG.info("HugeGraph cluster stopped");
    }

    public ClusterRestClient client() {
        return this.client;
    }

    public BaseEnv env() {
        return this.env;
    }

    public int addPDNode() {
        return env.addPDNode();
    }

    public int addStoreNode() {
        return env.addStoreNode();
    }

    public int addServerNode() {
        return env.addServerNode();
    }

    public void removePDNode(int index) {
        env.removePDNode(index);
    }

    public void removeStoreNode(int index) {
        env.removeStoreNode(index);
    }

    public void removeServerNode(int index) {
        env.removeServerNode(index);
    }

    public int getAlivePDNodeCount() {
        return env.getAlivePDNodeCount();
    }

    public int getAliveStoreNodeCount() {
        return env.getAliveStoreNodeCount();
    }

    public int getAliveServerNodeCount() {
        return env.getAliveServerNodeCount();
    }

    public Response createVertex(Object vertexBody) {
        return this.client.createVertex(vertexBody);
    }

    public Response createEdge(Object edgeBody) {
        return this.client.createEdge(edgeBody);
    }

    public Response getVertices() {
        return this.client.getVertices();
    }

    public Response getVertices(Map<String, String> queryParams) {
        return this.client.getVertices(queryParams);
    }

    public Response getEdges() {
        return this.client.getEdges();
    }

    public Response getEdges(Map<String, String> queryParams) {
        return this.client.getEdges(queryParams);
    }

    public Response deleteVertex(String vertexId) {
        return this.client.deleteVertex(vertexId);
    }

    public Response deleteEdge(String edgeId) {
        return this.client.deleteEdge(edgeId);
    }

    public Response createPropertyKey(Object propertyKeyBody) {
        return this.client.createPropertyKey(propertyKeyBody);
    }

    public Response createVertexLabel(Object vertexLabelBody) {
        return this.client.createVertexLabel(vertexLabelBody);
    }

    public Response createEdgeLabel(Object edgeLabelBody) {
        return this.client.createEdgeLabel(edgeLabelBody);
    }

    public Response createIndexLabel(Object indexLabelBody) {
        return this.client.createIndexLabel(indexLabelBody);
    }

    public Response executeGremlin(Object gremlinBody) {
        return this.client.executeGremlin(gremlinBody);
    }

    @Override
    public void close() {
        stop();
    }
}
