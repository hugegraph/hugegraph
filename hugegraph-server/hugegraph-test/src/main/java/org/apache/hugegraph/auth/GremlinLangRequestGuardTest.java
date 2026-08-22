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

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpMethod.POST;
import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.ExecutorService;

import org.apache.hugegraph.HugeException;
import org.apache.hugegraph.testutil.Assert;
import org.apache.tinkerpop.gremlin.server.Settings;
import org.apache.tinkerpop.gremlin.util.Tokens;
import org.apache.tinkerpop.gremlin.util.message.ResponseMessage;
import org.apache.tinkerpop.gremlin.util.message.ResponseStatusCode;
import org.apache.tinkerpop.gremlin.util.message.RequestMessage;
import org.apache.tinkerpop.gremlin.util.ser.GraphSONUntypedMessageSerializerV1;
import org.junit.Test;

import io.netty.buffer.Unpooled;
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
    public void testAllowsStandardGremlinLangEval() {
        RequestMessage request = eval("gremlin-lang");

        Assert.assertNull(GremlinLangRequestGuard.rejection(request));
    }

    @Test
    public void testRejectsMissingLanguage() {
        RequestMessage request = RequestMessage.build(Tokens.OPS_EVAL)
                                               .addArg(Tokens.ARGS_GREMLIN,
                                                       "g.V().count()")
                                               .create();

        Assert.assertContains("gremlin-lang",
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
    public void testRejectsSessionProcessor() {
        RequestMessage request = RequestMessage.build(Tokens.OPS_EVAL)
                                               .processor("session")
                                               .addArg(Tokens.ARGS_GREMLIN,
                                                       "g.V().count()")
                                               .addArg(Tokens.ARGS_LANGUAGE,
                                                       "gremlin-lang")
                                               .create();

        Assert.assertContains("session",
                              GremlinLangRequestGuard.rejection(request));
    }

    @Test
    public void testRejectsBytecode() {
        RequestMessage request = RequestMessage.build(Tokens.OPS_BYTECODE)
                                               .create();

        Assert.assertContains("bytecode",
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
        RequestMessage request = RequestMessage.build(Tokens.OPS_BYTECODE)
                                               .processor("cypher")
                                               .create();

        Assert.assertContains("bytecode",
                              GremlinLangRequestGuard.rejection(request));
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
    public void testWebSocketHandlerForwardsGremlinLang() {
        EmbeddedChannel channel = new EmbeddedChannel(
                new GremlinLangRequestHandler());
        RequestMessage request = eval("gremlin-lang");

        Assert.assertTrue(channel.writeInbound(request));
        Assert.assertSame(request, channel.readInbound());
        channel.finishAndReleaseAll();
    }

    @Test
    public void testHttpHandlerRejectsGroovyBeforeEvaluation() {
        String json = "{\"gremlin\":\"g.V().count()\"," +
                      "\"language\":\"gremlin-groovy\"}";

        assertHttpBadRequest(json, "gremlin-groovy");
    }

    @Test
    public void testHttpHandlerRejectsMissingLanguageBeforeEvaluation() {
        assertHttpBadRequest("{\"gremlin\":\"g.V().count()\"}",
                             "gremlin-lang");
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
}
