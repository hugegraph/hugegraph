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
import java.util.UUID;
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
import org.apache.tinkerpop.gremlin.process.traversal.Bytecode;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.server.Settings;
import org.apache.tinkerpop.gremlin.structure.util.empty.EmptyGraph;
import org.apache.tinkerpop.gremlin.util.MessageSerializer;
import org.apache.tinkerpop.gremlin.util.message.RequestMessage;
import org.apache.tinkerpop.gremlin.util.message.ResponseStatusCode;
import org.apache.tinkerpop.gremlin.util.ser.GraphBinaryMessageSerializerV1;
import org.apache.tinkerpop.gremlin.util.ser.GraphSONMessageSerializerV3;
import org.junit.Assert;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;

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
            serializer.className = name.equals("GraphBinaryMessageSerializerV1") ?
                                   PolicySessionProtocolChecks.FailingResultSerializer.class.getName() :
                                   "org.apache.tinkerpop.gremlin.util.ser." + name;
            settings.serializers.add(serializer);
        }
        ContextGremlinServer server = new ContextGremlinServer(settings, new EventHub("script-policy-probe"));
        server.getServerGremlinExecutor().getGraphManager()
              .putTraversalSource("g", EmptyGraph.instance().traversal().withSideEffect("marker", 1));
        server.getServerGremlinExecutor().getGraphManager()
              .putTraversalSource("gOther", EmptyGraph.instance().traversal().withSideEffect("marker", 2));
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
                    RequestMessage cypher = RequestMessage.build("eval").processor("cypher")
                            .addArg("gremlin", "RETURN 1 AS value")
                            .addArg("aliases", Map.of("g", "g")).create();
                    Assert.assertEquals(Map.of("value", 1L), client.submitAsync(cypher)
                            .get(10, TimeUnit.SECONDS).all().get(10, TimeUnit.SECONDS).get(0).getObject());
                    Client session = cluster.connect("policy-session-test");
                    try {
                        Assert.assertEquals(2, session.submit("1 + 1").all()
                                .get(10, TimeUnit.SECONDS).get(0).getInt());
                        Assert.assertEquals(3, session.submit("x = 3; x").all()
                                .get(10, TimeUnit.SECONDS).get(0).getInt());
                        Assert.assertEquals(4, session.submit("x + 1").all()
                                .get(10, TimeUnit.SECONDS).get(0).getInt());
                        ExecutionException denied = Assert.assertThrows(ExecutionException.class,
                                () -> session.submit("System.getProperty('java.version')").all()
                                             .get(10, TimeUnit.SECONDS));
                        Assert.assertTrue(denied.getCause() instanceof ResponseException);
                    } finally {
                        session.close();
                    }
                    PolicySessionProtocolChecks.verify(cluster);
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
                Assert.assertEquals(rejected.body(), 400, rejected.statusCode());
                Assert.assertTrue(rejected.body(), rejected.body().contains("SCRIPT_BINDING_DENIED"));
                verifySerializedHttp(http, request.uri());
            }
            HttpResponse<String> again = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (again.statusCode() != 200) {
                throw new AssertionError("HTTP keep-alive status " + again.statusCode());
            }
            System.out.println("POLICY_MODE_VERIFIED " + mode.configValue() +
                               " manager=" + (System.getSecurityManager() == null ? "none" : "installed"));
        } finally {
            server.stop().get(20, TimeUnit.SECONDS);
        }
    }

    private static void verifySerializedHttp(HttpClient http, URI uri) throws Exception {
        MessageSerializer<?> graphson = new GraphSONMessageSerializerV3();
        MessageSerializer<?> binary = new GraphBinaryMessageSerializerV1();
        UUID requestId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        HttpResponse<String> timeout = postSerialized(http, uri, graphson,
                RequestMessage.build("eval").overrideRequestId(requestId)
                        .addArg("gremlin", "1+1")
                        .addArg("evaluationTimeout", 999999L).create());
        Assert.assertEquals(timeout.body(), 400, timeout.statusCode());
        Assert.assertTrue(timeout.body(), timeout.body().contains("SCRIPT_TIMEOUT_OVERRIDE_DENIED"));
        Assert.assertTrue(timeout.body(), timeout.body().contains(requestId.toString()));

        HttpResponse<String> session = postSerialized(http, uri, binary,
                RequestMessage.build("eval").processor("session")
                        .addArg("gremlin", "1+1").create());
        Assert.assertEquals(session.body(), 400, session.statusCode());
        Assert.assertTrue(session.body(), session.body().contains("SCRIPT_HTTP_SESSION_UNSUPPORTED"));

        Bytecode bytecode = new Bytecode();
        bytecode.addStep("V");
        bytecode.addStep("count");
        HttpResponse<String> nativeBytecode = postSerialized(http, uri, graphson,
                RequestMessage.build("eval").addArg("gremlin", bytecode)
                        .addArg("aliases", Map.of("g", "g")).create());
        Assert.assertEquals(nativeBytecode.body(), 200, nativeBytecode.statusCode());
        Assert.assertTrue(nativeBytecode.body(), nativeBytecode.body().contains("\"@value\":0"));
    }

    private static HttpResponse<String> postSerialized(HttpClient http, URI uri,
                                                       MessageSerializer<?> serializer,
                                                       RequestMessage request) throws Exception {
        ByteBuf buffer = serializer.serializeRequestAsBinary(request,
                UnpooledByteBufAllocator.DEFAULT);
        byte[] payload;
        try {
            payload = new byte[buffer.readableBytes()];
            buffer.readBytes(payload);
        } finally {
            buffer.release();
        }
        HttpRequest posted = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                .header("Content-Type", serializer.mimeTypesSupported()[0])
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build();
        return http.send(posted, HttpResponse.BodyHandlers.ofString());
    }
}
