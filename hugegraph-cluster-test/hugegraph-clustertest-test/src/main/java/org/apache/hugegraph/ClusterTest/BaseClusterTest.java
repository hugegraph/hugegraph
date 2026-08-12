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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.hugegraph.ct.client.ClusterRestClient;
import org.apache.hugegraph.ct.config.ClusterTestConfig;
import org.apache.hugegraph.ct.env.BaseEnv;
import org.apache.hugegraph.ct.env.EnvFactory;
import org.apache.hugegraph.ct.node.BaseNodeWrapper;
import org.junit.Assert;
import org.junit.Before;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.ws.rs.core.Response;

public class BaseClusterTest {

    private static final Logger LOG = LoggerFactory.getLogger(BaseClusterTest.class);

    protected static BaseEnv env;
    protected static ClusterRestClient client;
    protected static List<ClusterRestClient> clients = new ArrayList<>();
    protected static ClusterTestConfig config;
    protected static Process p;

    private static boolean clusterStarted = false;
    private static final int REST_API_RETRY_INTERVAL_MS = 3000;
    private static final int REST_API_MAX_RETRIES = 120;

    @Before
    public void refreshClientIfNeeded() {
        if (!clusterStarted || client == null) {
            return;
        }
        // Find a working client (server may have crashed after DynamicScaleTest)
        ClusterRestClient working = null;
        for (ClusterRestClient c : clients) {
            try {
                Response r = c.get("");
                int status = r.getStatus();
                r.close();
                if (status < 500) {
                    working = c;
                    break;
                }
            } catch (Exception ignored) {
            }
        }
        if (working == null) {
            // All existing clients failed, recreate them
            LOG.warn("All clients lost, recreating connections to servers");
            List<String> serverAddrs = env.getServerRestAddrs();
            clients.clear();
            for (String addr : serverAddrs) {
                clients.add(new ClusterRestClient("http://" + addr, config.getGraphName()));
            }
            // Try again with new clients
            for (ClusterRestClient c : clients) {
                try {
                    Response r = c.get("");
                    int status = r.getStatus();
                    r.close();
                    if (status < 500) {
                        working = c;
                        break;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        if (working != null && working != client) {
            LOG.warn("Switching default client to alive server");
            client = working;
        }
    }

    public static synchronized void ensureClusterStarted() {
        if (clusterStarted) {
            return;
        }
        config = ClusterTestConfig.load();
        env = EnvFactory.getEnv(config);
        if (config.isLightweight()) {
            setAllNodeWrappersLightweight(env, true);
        }
        env.startCluster();

        try {
            List<String> serverAddrs = env.getServerRestAddrs();
            clients.clear();
            for (String addr : serverAddrs) {
                clients.add(new ClusterRestClient("http://" + addr, config.getGraphName()));
            }
            client = clients.get(0);
            waitForRestApiReady(serverAddrs);
            initGraph();
            clusterStarted = true;
        } catch (Exception e) {
            LOG.error("Cluster startup failed, cleaning up", e);
            for (ClusterRestClient c : clients) {
                try { c.close(); } catch (Exception ignored) {}
            }
            clients.clear();
            client = null;
            try { env.stopCluster(); } catch (Exception ignored) {}
            throw e;
        }
    }

    protected static void setAllNodeWrappersLightweight(BaseEnv env, boolean lightweight) {
        for (BaseNodeWrapper node : env.getPDNodeWrappers()) {
            node.setLightweight(lightweight);
        }
        for (BaseNodeWrapper node : env.getStoreNodeWrappers()) {
            node.setLightweight(lightweight);
        }
        for (BaseNodeWrapper node : env.getServerNodeWrappers()) {
            node.setLightweight(lightweight);
        }
    }

    public static synchronized void shutdownCluster() {
        if (!clusterStarted) {
            return;
        }
        for (ClusterRestClient c : clients) {
            c.close();
        }
        clients.clear();
        client = null;
        env.stopCluster();
        clusterStarted = false;
    }

    private static void waitForRestApiReady(List<String> serverAddrs) {
        long timeoutMs = (long) REST_API_MAX_RETRIES * REST_API_RETRY_INTERVAL_MS;
        LOG.info("Waiting for REST API to become ready (max {}s), servers: {}",
                 timeoutMs / 1000, serverAddrs);
        for (String addr : serverAddrs) {
            String url = "http://" + addr + "/graphspaces";
            boolean ready = false;
            for (int i = 0; i < REST_API_MAX_RETRIES; i++) {
                try {
                    HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(3000);
                    conn.setReadTimeout(3000);
                    int code = conn.getResponseCode();
                    conn.disconnect();
                    if (code < 500) {
                        LOG.info("REST API at {} is ready (status {})", addr, code);
                        ready = true;
                        break;
                    }
                    LOG.warn("REST API at {} returned {}", addr, code);
                } catch (java.net.ConnectException ce) {
                    LOG.debug("REST API at {} not yet accepting connections (attempt {}/{})",
                              addr, i + 1, REST_API_MAX_RETRIES);
                } catch (Exception e) {
                    LOG.debug("REST API check at {} failed: {}", addr, e.getMessage());
                }
                try {
                    Thread.sleep(REST_API_RETRY_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted while waiting for REST API at " + addr);
                }
            }
            if (!ready) {
                LOG.error("REST API at {} not ready after {}s, server nodes alive: {}",
                          addr, timeoutMs / 1000, env.getAliveServerNodeCount());
                throw new RuntimeException("REST API at " + addr + " not ready after " +
                        (timeoutMs / 1000) + "s. Server alive count: " +
                        env.getAliveServerNodeCount());
            }
        }
    }

    protected static void initGraph() {
        Response r = client.get("");
        if (r.getStatus() == 200) {
            return;
        }
        String body = String.format(
                "{\"backend\":\"%s\",\"serializer\":\"%s\",\"store\":\"%s\"," +
                "\"search.text_analyzer\":\"jieba\",\"search.text_analyzer_mode\":\"INDEX\"}",
                config.getBackend(), config.getSerializer(), config.getGraphName());
        r = client.createGraph(body);
        if (r.getStatus() != 200 && r.getStatus() != 201 && r.getStatus() != 202) {
            throw new RuntimeException("Failed to create graph: " + r.readEntity(String.class));
        }
    }

    protected void createBasicSchema() {
        createBasicSchema(client);
    }

    protected void createBasicSchema(ClusterRestClient c) {
        c.createPropertyKey("{\"name\":\"name\",\"data_type\":\"TEXT\"," +
                            "\"cardinality\":\"SINGLE\",\"check_exist\":false}");
        c.createPropertyKey("{\"name\":\"age\",\"data_type\":\"INT\"," +
                            "\"cardinality\":\"SINGLE\",\"check_exist\":false}");
        c.createPropertyKey("{\"name\":\"weight\",\"data_type\":\"DOUBLE\"," +
                            "\"cardinality\":\"SINGLE\",\"check_exist\":false}");

        c.createVertexLabel("{\"name\":\"person\",\"id_strategy\":\"PRIMARY_KEY\"," +
                            "\"primary_keys\":[\"name\"],\"properties\":[\"name\",\"age\"]," +
                            "\"check_exist\":false}");
        c.createVertexLabel("{\"name\":\"software\",\"id_strategy\":\"PRIMARY_KEY\"," +
                            "\"primary_keys\":[\"name\"],\"properties\":[\"name\",\"age\"]," +
                            "\"check_exist\":false}");

        c.createEdgeLabel("{\"name\":\"knows\",\"source_label\":\"person\"," +
                          "\"target_label\":\"person\",\"properties\":[\"weight\"]," +
                          "\"check_exist\":false}");
        c.createEdgeLabel("{\"name\":\"created\",\"source_label\":\"person\"," +
                          "\"target_label\":\"software\",\"properties\":[\"weight\"]," +
                          "\"check_exist\":false}");
    }

    protected static String extractId(String content) {
        int idx = content.indexOf("\"id\":");
        if (idx < 0) return "";
        int start = idx + 5;
        if (content.charAt(start) == '"') start++;
        int end = start;
        while (end < content.length() &&
               content.charAt(end) != ',' && content.charAt(end) != '"' &&
               content.charAt(end) != '}') {
            end++;
        }
        return content.substring(start, end);
    }

    protected static String formatIdForUrl(String id) {
        return "\"" + id + "\"";
    }

    protected String execCmd(String[] cmds) throws IOException {
        ProcessBuilder process = new ProcessBuilder(cmds);
        p = process.start();
        BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
        StringBuilder builder = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            builder.append(line);
            builder.append(System.lineSeparator());
        }
        p.destroy();
        return builder.toString();
    }

    protected static String assertResponseStatus(int status, Response response) {
        String content = response.readEntity(String.class);
        String message = String.format("Response with status %s and content %s",
                                       response.getStatus(), content);
        Assert.assertEquals(message, status, response.getStatus());
        return content;
    }

    protected static Map<String, String> queryParams(Object... keyValues) {
        Map<String, String> params = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            params.put((String) keyValues[i], String.valueOf(keyValues[i + 1]));
        }
        return params;
    }

    protected static ClusterRestClient getWorkingClient() {
        // First try the default client (should be master)
        try {
            Response r = client.get("");
            int status = r.getStatus();
            r.close();
            if (status < 500) {
                return client;
            }
        } catch (Exception ignored) {
        }
        // Fallback: find any working client, but do NOT replace the default client
        // (schema/Gremlin operations require master, which is the default client)
        for (ClusterRestClient c : clients) {
            if (c == client) continue;
            try {
                Response r = c.get("");
                int status = r.getStatus();
                r.close();
                if (status < 500) {
                    LOG.warn("Default client unavailable, using alternate client for this request");
                    return c;
                }
            } catch (Exception ignored) {
            }
        }
        // All clients failed, return default and let caller handle the error
        LOG.error("No working REST client found among {} clients", clients.size());
        return client;
    }
}
