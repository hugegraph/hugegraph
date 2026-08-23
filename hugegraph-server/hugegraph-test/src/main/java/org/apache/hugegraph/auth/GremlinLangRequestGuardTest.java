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

package org.apache.hugegraph.auth;

import static io.netty.handler.codec.http.HttpHeaderNames.ACCEPT;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpMethod.POST;
import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import javax.script.Bindings;

import org.apache.hugegraph.HugeException;
import org.apache.hugegraph.testutil.Assert;
import org.apache.tinkerpop.gremlin.groovy.engine.GremlinExecutor;
import org.apache.tinkerpop.gremlin.process.traversal.Bytecode;
import org.apache.tinkerpop.gremlin.server.GraphManager;
import org.apache.tinkerpop.gremlin.server.Settings;
import org.apache.tinkerpop.gremlin.util.MessageSerializer;
import org.apache.tinkerpop.gremlin.util.Tokens;
import org.apache.tinkerpop.gremlin.util.function.Lambda;
import org.apache.tinkerpop.gremlin.util.message.ResponseMessage;
import org.apache.tinkerpop.gremlin.util.message.ResponseStatusCode;
import org.apache.tinkerpop.gremlin.util.message.RequestMessage;
import org.apache.tinkerpop.gremlin.util.ser.GraphBinaryMessageSerializerV1;
import org.apache.tinkerpop.gremlin.util.ser.GraphSONUntypedMessageSerializerV1;
import org.junit.Test;
import org.mockito.Mockito;

import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;

public class GremlinLangRequestGuardTest {

    private static final String STANDARD_CHANNELIZER =
            "org.apache.tinkerpop.gremlin.server.channel." +
            "WsAndHttpChannelizer";

    @Test
    public void testRejectsUnprotectedServerChannelizer() {
        Settings settings = new Settings();
        settings.channelizer = STANDARD_CHANNELIZER;
        settings.gremlinPool = 1;
        ExecutorService executor = null;

        try {
            executor = ContextGremlinServer.newGremlinExecutorService(
                    settings);
            Assert.fail("Expected an unprotected channelizer error");
        } catch (HugeException e) {
            Assert.assertContains("channelizer", e.getMessage());
        } finally {
            if (executor != null) {
                executor.shutdownNow();
            }
        }
    }

    @Test
    public void testServerCleanupWaitsForAsyncStopCompletion() {
        CompletableFuture<Void> stop = new CompletableFuture<>();
        AtomicBoolean cleaned = new AtomicBoolean(false);

        CompletableFuture<Void> result = ContextGremlinServer.afterStop(
                stop, () -> cleaned.set(true));

        Assert.assertFalse(cleaned.get());
        stop.complete(null);
        result.join();
        Assert.assertTrue(cleaned.get());
    }

    @Test
    public void testAllowsStandardGremlinLangEval() {
        RequestMessage request = eval("gremlin-lang");

        Assert.assertNull(GremlinLangRequestGuard.rejection(request));
    }

    @Test
    public void testDefaultsMissingLanguageToGremlinLang() {
        RequestMessage request = RequestMessage.build(Tokens.OPS_EVAL)
                                               .addArg(Tokens.ARGS_GREMLIN,
                                                       "g.V().count()")
                                               .create();

        Assert.assertNull(GremlinLangRequestGuard.rejection(request));
        RequestMessage normalized = GremlinLangRequestGuard.normalize(request);
        Assert.assertEquals("hugegraph-gremlin-lang",
                            normalized.getArg(Tokens.ARGS_LANGUAGE));
        Assert.assertEquals(request.getRequestId(), normalized.getRequestId());
    }

    @Test
    public void testRejectsExplicitNullLanguage() {
        RequestMessage request = RequestMessage.build(Tokens.OPS_EVAL)
                                               .addArg(Tokens.ARGS_GREMLIN,
                                                       "g.V().count()")
                                               .addArg(Tokens.ARGS_LANGUAGE,
                                                       null)
                                               .create();

        Assert.assertContains("string",
                              GremlinLangRequestGuard.rejection(request));
    }

    @Test
    public void testRejectsNonStringLanguage() {
        RequestMessage request = RequestMessage.build(Tokens.OPS_EVAL)
                                               .addArg(Tokens.ARGS_GREMLIN,
                                                       "g.V().count()")
                                               .addArg(Tokens.ARGS_LANGUAGE,
                                                       1)
                                               .create();

        Assert.assertContains("string",
                              GremlinLangRequestGuard.rejection(request));
    }

    @Test
    public void testRejectsNonStringEvalPayload() {
        RequestMessage request = RequestMessage.build(Tokens.OPS_EVAL)
                                               .addArg(Tokens.ARGS_GREMLIN,
                                                       new Bytecode())
                                               .create();

        Assert.assertContains("string",
                              GremlinLangRequestGuard.rejection(request));
    }

    @Test
    public void testRejectsGroovy() {
        RequestMessage request = eval("gremlin-groovy");

        Assert.assertContains("gremlin-groovy",
                              GremlinLangRequestGuard.rejection(request));
    }

    @Test
    public void testRejectsGroovyFromHttpRequest() {
        RequestMessage request = RequestMessage.build("")
                                               .addArg(Tokens.ARGS_GREMLIN,
                                                       "g.V().count()")
                                               .addArg(Tokens.ARGS_LANGUAGE,
                                                       "gremlin-groovy")
                                               .create();

        Assert.assertContains("gremlin-groovy",
                              GremlinLangRequestGuard.rejection(request));
    }

    @Test
    public void testAllowsSessionEval() {
        RequestMessage request = RequestMessage.build(Tokens.OPS_EVAL)
                                               .processor("session")
                                               .addArg(Tokens.ARGS_GREMLIN,
                                                       "g.V().count()")
                                               .addArg(Tokens.ARGS_SESSION,
                                                       "session-id")
                                               .create();

        Assert.assertNull(GremlinLangRequestGuard.rejection(request));
        RequestMessage normalized = GremlinLangRequestGuard.normalize(request);
        Assert.assertEquals("hugegraph-gremlin-lang",
                            normalized.getArg(Tokens.ARGS_LANGUAGE));
    }

    @Test
    public void testAllowsTraversalBytecodeWithoutLambda() {
        RequestMessage request = bytecode("traversal", new Bytecode());

        Assert.assertNull(GremlinLangRequestGuard.rejection(request));
        Assert.assertSame(request,
                          GremlinLangRequestGuard.normalize(request));
    }

    @Test
    public void testAllowsSessionBytecodeWithoutLambda() {
        RequestMessage request = RequestMessage.from(
                bytecode("session", new Bytecode()))
                                               .addArg(Tokens.ARGS_SESSION,
                                                       "session-id")
                                               .create();

        Assert.assertNull(GremlinLangRequestGuard.rejection(request));
    }

    @Test
    public void testRejectsBytecodeWithLambda() {
        Bytecode bytecode = new Bytecode();
        bytecode.addStep("filter", Lambda.predicate("true"));
        RequestMessage request = bytecode("traversal", bytecode);

        Assert.assertContains("Lambda",
                              GremlinLangRequestGuard.rejection(request));
    }

    @Test
    public void testRejectsBytecodeThatRemovesTraversalStrategies() {
        Bytecode bytecode = new Bytecode();
        bytecode.addSource("withoutStrategies", Object.class);
        RequestMessage request = bytecode("traversal", bytecode);

        Assert.assertContains("withoutStrategies",
                              GremlinLangRequestGuard.rejection(request));
    }

    @Test
    public void testAllowsLegacySessionClose() {
        RequestMessage request = RequestMessage.build(Tokens.OPS_CLOSE)
                                               .processor("session")
                                               .addArg(Tokens.ARGS_SESSION,
                                                       "session-id")
                                               .create();

        Assert.assertNull(GremlinLangRequestGuard.rejection(request));
    }

    @Test
    public void testAllowsAuthenticationOperation() {
        RequestMessage request = RequestMessage.build(
                Tokens.OPS_AUTHENTICATION).create();

        Assert.assertNull(GremlinLangRequestGuard.rejection(request));
    }

    @Test
    public void testRejectsUnknownProcessorAndOperation() {
        RequestMessage request = RequestMessage.build("future-operation")
                                               .processor("future-processor")
                                               .create();

        Assert.assertContains("future-processor",
                              GremlinLangRequestGuard.rejection(request));
    }

    @Test
    public void testAllowsCypherProcessor() {
        RequestMessage request = RequestMessage.build(Tokens.OPS_EVAL)
                                               .processor("cypher")
                                               .addArg(Tokens.ARGS_GREMLIN,
                                                       "MATCH (n) RETURN n")
                                               .create();

        Assert.assertNull(GremlinLangRequestGuard.rejection(request));
    }

    @Test
    public void testRejectsBytecodeWithCypherProcessor() {
        RequestMessage request = bytecode("cypher", new Bytecode());

        Assert.assertContains("text eval",
                              GremlinLangRequestGuard.rejection(request).
                              toLowerCase());
    }

    @Test
    public void testWebSocketHandlerRejectsGroovyBeforeOpSelector() {
        EmbeddedChannel channel = new EmbeddedChannel(
                new GremlinLangRequestHandler());

        Assert.assertFalse(channel.writeInbound(eval("gremlin-groovy")));
        ResponseMessage response = channel.readOutbound();
        Assert.assertEquals(ResponseStatusCode.REQUEST_ERROR_INVALID_REQUEST_ARGUMENTS,
                            response.getStatus().getCode());
        Assert.assertContains("gremlin-groovy",
                              response.getStatus().getMessage());
        channel.finishAndReleaseAll();
    }

    @Test
    public void testWebSocketHandlerNormalizesGremlinLang() {
        EmbeddedChannel channel = new EmbeddedChannel(
                new GremlinLangRequestHandler());
        RequestMessage request = eval("gremlin-lang");

        Assert.assertTrue(channel.writeInbound(request));
        RequestMessage normalized = channel.readInbound();
        Assert.assertEquals("hugegraph-gremlin-lang",
                            normalized.getArg(Tokens.ARGS_LANGUAGE));
        Assert.assertEquals(request.getRequestId(), normalized.getRequestId());
        channel.finishAndReleaseAll();
    }

    @Test
    public void testWebSocketHandlerDefaultsMissingLanguage() {
        EmbeddedChannel channel = new EmbeddedChannel(
                new GremlinLangRequestHandler());
        RequestMessage request = RequestMessage.build(Tokens.OPS_EVAL)
                                               .addArg(Tokens.ARGS_GREMLIN,
                                                       "g.V().count()")
                                               .create();

        Assert.assertTrue(channel.writeInbound(request));
        RequestMessage normalized = channel.readInbound();
        Assert.assertEquals("hugegraph-gremlin-lang",
                            normalized.getArg(Tokens.ARGS_LANGUAGE));
        channel.finishAndReleaseAll();
    }

    @Test
    public void testHttpHandlerRejectsGroovyBeforeEvaluation() {
        String json = "{\"gremlin\":\"g.V().count()\"," +
                      "\"language\":\"gremlin-groovy\"}";

        assertHttpBadRequest(json, "gremlin-groovy");
    }

    @Test
    public void testHttpHandlerRejectsExplicitNullLanguageBeforeEvaluation() {
        assertHttpBadRequest("{\"gremlin\":\"g.V().count()\"," +
                             "\"language\":null}", "gremlin-lang");
    }

    @Test
    public void testHttpHandlerDefaultsMissingLanguageToGremlinLang() {
        GremlinExecutor gremlinExecutor = Mockito.mock(
                GremlinExecutor.class);
        GraphManager graphManager = Mockito.mock(GraphManager.class);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CompletableFuture<Object> pending = new CompletableFuture<>();
        Mockito.when(gremlinExecutor.getExecutorService())
               .thenReturn(executor);
        Mockito.when(gremlinExecutor.eval(
                       Mockito.eq("g.V().count()"), Mockito.anyString(),
                       Mockito.any(Bindings.class), Mockito.isNull(),
                       Mockito.<Function<Object, Object>>any()))
               .thenReturn(pending);

        GremlinLangHttpHandler handler = new GremlinLangHttpHandler(
                Collections.singletonMap(
                        "application/json",
                        new GraphSONUntypedMessageSerializerV1()),
                gremlinExecutor, graphManager, new Settings());
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                HTTP_1_1, POST, "/gremlin",
                Unpooled.copiedBuffer("{\"gremlin\":\"g.V().count()\"}",
                                      StandardCharsets.UTF_8));
        request.headers().set(CONTENT_TYPE, "application/json");

        try {
            Assert.assertFalse(channel.writeInbound(request));
            Mockito.verify(gremlinExecutor).eval(
                    Mockito.eq("g.V().count()"),
                    Mockito.eq(GremlinLangRequestGuard.GREMLIN_LANG),
                    Mockito.any(Bindings.class), Mockito.isNull(),
                    Mockito.<Function<Object, Object>>any());
        } finally {
            pending.cancel(true);
            executor.shutdownNow();
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void testHttpHandlerDefaultsSerializedTextToGremlinLang()
            throws Exception {
        GraphBinaryMessageSerializerV1 graphBinary =
                new GraphBinaryMessageSerializerV1();
        String mimeType = graphBinary.mimeTypesSupported()[0];
        Map<String, MessageSerializer<?>> serializers = Map.of(
                        mimeType, graphBinary,
                        "application/json",
                        new GraphSONUntypedMessageSerializerV1());
        GremlinExecutor gremlinExecutor = Mockito.mock(
                GremlinExecutor.class);
        GraphManager graphManager = Mockito.mock(GraphManager.class);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CompletableFuture<Object> pending = new CompletableFuture<>();
        Mockito.when(gremlinExecutor.getExecutorService())
               .thenReturn(executor);
        Mockito.when(gremlinExecutor.eval(
                       Mockito.eq("g.V().count()"), Mockito.anyString(),
                       Mockito.any(Bindings.class), Mockito.isNull(),
                       Mockito.<Function<Object, Object>>any()))
               .thenReturn(pending);

        GremlinLangHttpHandler handler = new GremlinLangHttpHandler(
                serializers, gremlinExecutor, graphManager, new Settings());
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        RequestMessage gremlinRequest = RequestMessage.build(Tokens.OPS_EVAL)
                                                      .addArg(
                                                              Tokens.ARGS_GREMLIN,
                                                              "g.V().count()")
                                                      .create();
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                HTTP_1_1, POST, "/gremlin",
                graphBinary.serializeRequestAsBinary(
                        gremlinRequest, UnpooledByteBufAllocator.DEFAULT));
        request.headers().set(CONTENT_TYPE, mimeType);
        request.headers().set(ACCEPT, "application/json");

        try {
            Assert.assertFalse(channel.writeInbound(request));
            Mockito.verify(gremlinExecutor).eval(
                    Mockito.eq("g.V().count()"),
                    Mockito.eq("hugegraph-gremlin-lang"),
                    Mockito.any(Bindings.class), Mockito.isNull(),
                    Mockito.<Function<Object, Object>>any());
        } finally {
            pending.cancel(true);
            executor.shutdownNow();
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void testHttpHandlerRejectsSerializedBytecode() throws Exception {
        GraphBinaryMessageSerializerV1 serializer =
                new GraphBinaryMessageSerializerV1();
        String mimeType = serializer.mimeTypesSupported()[0];
        RequestMessage gremlinRequest = bytecode("traversal",
                                                 new Bytecode());
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                HTTP_1_1, POST, "/gremlin",
                serializer.serializeRequestAsBinary(
                        gremlinRequest, UnpooledByteBufAllocator.DEFAULT));
        request.headers().set(CONTENT_TYPE, mimeType);
        GremlinLangHttpHandler handler = new GremlinLangHttpHandler(
                Collections.singletonMap(mimeType, serializer),
                null, null, new Settings());
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        Assert.assertFalse(channel.writeInbound(request));
        FullHttpResponse response = channel.readOutbound();
        Assert.assertEquals(BAD_REQUEST, response.status());
        Assert.assertContains(
                "standard WebSocket traversal",
                response.content().toString(StandardCharsets.UTF_8));
        response.release();
        channel.finishAndReleaseAll();
    }

    @Test
    public void testHttpHandlerRejectsSerializedNonStringLanguage()
            throws Exception {
        GraphBinaryMessageSerializerV1 serializer =
                new GraphBinaryMessageSerializerV1();
        String mimeType = serializer.mimeTypesSupported()[0];
        RequestMessage gremlinRequest = RequestMessage.build(Tokens.OPS_EVAL)
                                                      .addArg(
                                                              Tokens.ARGS_GREMLIN,
                                                              "g.V()")
                                                      .addArg(
                                                              Tokens.ARGS_LANGUAGE,
                                                              1)
                                                      .create();
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                HTTP_1_1, POST, "/gremlin",
                serializer.serializeRequestAsBinary(
                        gremlinRequest, UnpooledByteBufAllocator.DEFAULT));
        request.headers().set(CONTENT_TYPE, mimeType);
        GremlinLangHttpHandler handler = new GremlinLangHttpHandler(
                Collections.singletonMap(mimeType, serializer),
                null, null, new Settings());
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        Assert.assertFalse(channel.writeInbound(request));
        FullHttpResponse response = channel.readOutbound();
        Assert.assertEquals(BAD_REQUEST, response.status());
        Assert.assertContains(
                "must be a string",
                response.content().toString(StandardCharsets.UTF_8));
        response.release();
        channel.finishAndReleaseAll();
    }

    @Test
    public void testHttpHandlerKeepsMalformedRequestResponse() {
        assertHttpBadRequest("{\"gremlin\"", "body could not be parsed");
    }

    private static void assertHttpBadRequest(String json,
                                             String expectedMessage) {
        GremlinLangHttpHandler handler = new GremlinLangHttpHandler(
                Collections.singletonMap(
                        "application/json",
                        new GraphSONUntypedMessageSerializerV1()),
                null, null, new Settings());
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                HTTP_1_1, POST, "/gremlin",
                Unpooled.copiedBuffer(json, StandardCharsets.UTF_8));
        request.headers().set(CONTENT_TYPE, "application/json");

        Assert.assertFalse(channel.writeInbound(request));
        FullHttpResponse response = channel.readOutbound();
        Assert.assertEquals(BAD_REQUEST, response.status());
        Assert.assertContains(expectedMessage,
                              response.content().toString(StandardCharsets.UTF_8));
        response.release();
        channel.finishAndReleaseAll();
    }

    private static RequestMessage eval(String language) {
        return RequestMessage.build(Tokens.OPS_EVAL)
                             .addArg(Tokens.ARGS_GREMLIN, "g.V().count()")
                             .addArg(Tokens.ARGS_LANGUAGE, language)
                             .create();
    }

    private static RequestMessage bytecode(String processor,
                                           Bytecode bytecode) {
        return RequestMessage.build(Tokens.OPS_BYTECODE)
                             .processor(processor)
                             .addArg(Tokens.ARGS_GREMLIN, bytecode)
                             .addArg(Tokens.ARGS_ALIASES,
                                     Map.of("g", "__g_hugegraph"))
                             .create();
    }
}
