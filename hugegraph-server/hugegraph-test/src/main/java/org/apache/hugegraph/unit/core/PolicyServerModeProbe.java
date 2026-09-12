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

package org.apache.hugegraph.unit.core;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.apache.hugegraph.auth.ContextGremlinServer;
import org.apache.hugegraph.event.EventHub;
import org.apache.hugegraph.security.HugeSecurityManager;
import org.apache.hugegraph.security.script.ScriptPolicyRuntime;
import org.apache.hugegraph.security.script.ScriptSecurityMode;
import org.apache.hugegraph.util.JsonUtil;
import org.apache.tinkerpop.gremlin.driver.Client;
import org.apache.tinkerpop.gremlin.driver.Cluster;
import org.apache.tinkerpop.gremlin.driver.exception.ResponseException;
import org.apache.tinkerpop.gremlin.driver.remote.DriverRemoteConnection;
import org.apache.tinkerpop.gremlin.groovy.jsr223.GremlinGroovyScriptEngine;
import org.apache.tinkerpop.gremlin.process.traversal.AnonymousTraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.server.Settings;
import org.apache.tinkerpop.gremlin.structure.util.empty.EmptyGraph;
import org.apache.tinkerpop.gremlin.util.message.ResponseStatusCode;
import org.junit.Assert;

/** Runs in its own JVM so deployment mode and installed manager cannot leak between tests. */
public final class PolicyServerModeProbe {

    public static void main(String[] args) throws Exception {
        ScriptSecurityMode mode = ScriptPolicyRuntime.mode();
        // Initialize the trusted compiler before worker threads, as the server bootstrap does.
        new GremlinGroovyScriptEngine().eval("1 + 1");
        if (mode != ScriptSecurityMode.POLICY_ONLY || args.length > 0) {
            System.setSecurityManager(new HugeSecurityManager());
        }
        ScriptPolicyRuntime.validateSecurityManager();
        int port;
        try (ServerSocket reservation = new ServerSocket(0)) {
            port = reservation.getLocalPort();
        }
        Settings settings = new Settings();
        settings.host = "127.0.0.1";
        settings.port = port;
        settings.gremlinPool = 1;
        settings.threadPoolWorker = 1;
        settings.threadPoolBoss = 1;
        settings.channelizer = "org.apache.tinkerpop.gremlin.server.channel.WsAndHttpChannelizer";
        settings.scriptEngines.clear();
        settings.scriptEngines.put("gremlin-groovy", new Settings.ScriptEngineSettings());
        settings.serializers = new ArrayList<>();
        for (String name : new String[]{"GraphBinaryMessageSerializerV1", "GraphSONMessageSerializerV3"}) {
            Settings.SerializerSettings serializer = new Settings.SerializerSettings();
            serializer.className = "org.apache.tinkerpop.gremlin.util.ser." + name;
            settings.serializers.add(serializer);
        }
        ContextGremlinServer server = new ContextGremlinServer(settings, new EventHub("script-policy-probe"));
        server.getServerGremlinExecutor().getGraphManager()
              .putTraversalSource("g", EmptyGraph.instance().traversal());
        try {
            server.start().get(20, TimeUnit.SECONDS);
            Cluster cluster = Cluster.build("127.0.0.1").port(port).create();
            try {
                Client client = cluster.connect();
                if (client.submit("1 + 1").all().get(10, TimeUnit.SECONDS).get(0).getInt() != 2) {
                    throw new AssertionError("WebSocket result");
                }
                try (GraphTraversalSource g = AnonymousTraversalSource.traversal()
                        .withRemote(DriverRemoteConnection.using(cluster, "g"))) {
                    if (g.V().count().next() != 0L) {
                        throw new AssertionError("native bytecode result");
                    }
                }
                boolean rejected = false;
                try {
                    client.submit("new String('value')").all().get(10, TimeUnit.SECONDS);
                } catch (ExecutionException expected) {
                    Assert.assertTrue(expected.getCause() instanceof ResponseException);
                    ResponseException failure = (ResponseException) expected.getCause();
                    Assert.assertEquals(ResponseStatusCode.SERVER_ERROR_EVALUATION, failure.getResponseStatusCode());
                    Assert.assertTrue(failure.getMessage(), failure.getMessage().contains("SCRIPT_COMPILE_DENIED"));
                    rejected = true;
                }
                if (rejected != mode.policyEnabled()) {
                    throw new AssertionError("WebSocket policy state");
                }
                if (mode.policyEnabled()) {
                    Client session = cluster.connect("policy-session-test");
                    try {
                        session.submit("1 + 1").all().get(10, TimeUnit.SECONDS);
                        throw new AssertionError("session bypass");
                    } catch (ExecutionException expected) {
                        Assert.assertTrue(expected.getCause() instanceof ResponseException);
                        ResponseException failure = (ResponseException) expected.getCause();
                        Assert.assertEquals(ResponseStatusCode.REQUEST_ERROR_INVALID_REQUEST_ARGUMENTS,
                                            failure.getResponseStatusCode());
                        Assert.assertTrue(failure.getMessage(),
                                          failure.getMessage().contains("SCRIPT_SESSION_OR_PROCESSOR_DENIED"));
                    } finally {
                        session.closeAsync();
                    }
                }
                client.close();
            } finally {
                cluster.close();
            }
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/gremlin"))
                    .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"gremlin\":\"1+1\"}")).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new AssertionError("HTTP success status " + response.statusCode() + " " + response.body());
            }
            Map<String, Object> success = JsonUtil.fromJson(response.body(), Map.class);
            Assert.assertEquals(200, ((Map<?, ?>) success.get("status")).get("code"));
            Assert.assertEquals(Map.of("@type", "g:List", "@value",
                    List.of(Map.of("@type", "g:Int32", "@value", 2))),
                    ((Map<?, ?>) success.get("result")).get("data"));
            Assert.assertNotNull(success.get("requestId"));
            HttpRequest forbidden = HttpRequest.newBuilder(request.uri()).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json").header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"gremlin\":\"new String('value')\"}")).build();
            HttpResponse<String> denied = http.send(forbidden, HttpResponse.BodyHandlers.ofString());
            if ((denied.statusCode() != 200) != mode.policyEnabled()) {
                throw new AssertionError("HTTP policy state " + denied.statusCode());
            }
            if (mode.policyEnabled()) {
                Assert.assertEquals(denied.body(), 500, denied.statusCode());
                Assert.assertTrue(denied.body(), denied.body().contains("SCRIPT_COMPILE_DENIED"));
                Assert.assertFalse(denied.body().contains("new String"));
                HttpRequest reserved = HttpRequest.newBuilder(request.uri()).timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json").header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"gremlin\":\"g+1\",\"bindings\":{\"g\":1}}")).build();
                HttpResponse<String> rejected = http.send(reserved, HttpResponse.BodyHandlers.ofString());
                Assert.assertEquals(rejected.body(), 500, rejected.statusCode());
                Assert.assertTrue(rejected.body(), rejected.body().contains("SCRIPT_EXECUTION_FAILED"));
            }
            System.out.println("POLICY_MODE_VERIFIED " + mode.configValue() +
                               " manager=" + (System.getSecurityManager() == null ? "none" : "installed"));
        } finally {
            server.stop().get(20, TimeUnit.SECONDS);
        }
    }
}
