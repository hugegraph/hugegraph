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

import static com.codahale.metrics.MetricRegistry.name;
import static io.netty.handler.codec.http.HttpHeaderNames.CONNECTION;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpHeaderValues.KEEP_ALIVE;
import static io.netty.handler.codec.http.HttpMethod.GET;
import static io.netty.handler.codec.http.HttpMethod.POST;
import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.apache.hugegraph.util.JsonUtil;
import org.apache.tinkerpop.gremlin.process.traversal.Bytecode;
import org.apache.tinkerpop.gremlin.groovy.engine.GremlinExecutor;
import org.apache.tinkerpop.gremlin.server.GraphManager;
import org.apache.tinkerpop.gremlin.server.GremlinServer;
import org.apache.tinkerpop.gremlin.server.Settings;
import org.apache.tinkerpop.gremlin.server.handler.HttpGremlinEndpointHandler;
import org.apache.tinkerpop.gremlin.server.handler.HttpHandlerUtil;
import org.apache.tinkerpop.gremlin.server.util.MetricManager;
import org.apache.tinkerpop.gremlin.util.MessageSerializer;
import org.apache.tinkerpop.gremlin.util.Tokens;
import org.apache.tinkerpop.gremlin.util.message.RequestMessage;
import org.apache.tinkerpop.gremlin.util.ser.SerializationException;
import org.apache.tinkerpop.shaded.jackson.databind.JsonNode;
import org.apache.tinkerpop.shaded.jackson.databind.ObjectMapper;
import org.apache.tinkerpop.shaded.jackson.databind.node.ObjectNode;

import com.codahale.metrics.Meter;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.util.ReferenceCountUtil;

public class GremlinLangHttpHandler extends HttpGremlinEndpointHandler {

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final Meter ERROR_METER = MetricManager.INSTANCE.getMeter(
            name(GremlinServer.class, "errors"));

    private final Map<String, MessageSerializer<?>> serializers;

    public GremlinLangHttpHandler(
            Map<String, MessageSerializer<?>> serializers,
            GremlinExecutor gremlinExecutor,
            GraphManager graphManager,
            Settings settings) {
        super(serializers, gremlinExecutor, graphManager, settings);
        this.serializers = serializers;
    }

    @Override
    public void channelRead(ChannelHandlerContext context, Object message) {
        if (!(message instanceof FullHttpRequest)) {
            super.channelRead(context, message);
            return;
        }

        FullHttpRequest request = (FullHttpRequest) message;
        ByteBuf content = request.content();
        int readerIndex = content.readerIndex();
        RequestMessage gremlinRequest = null;
        UUID requestId = null;
        boolean serialized = this.isSerializedRequest(request);
        boolean languageProvided = true;
        String rejection = null;
        try {
            JsonNode jsonBody = !serialized && request.method() == POST ?
                                this.parseJsonBody(request) : null;
            languageProvided = !serialized &&
                               this.hasLanguageArgument(request, jsonBody);
            rejection = this.rawTextRejection(jsonBody);
            requestId = this.requestId(jsonBody);
            if (rejection == null) {
                gremlinRequest =
                        HttpHandlerUtil.getRequestMessageFromHttpRequest(
                                request, this.serializers);
                requestId = gremlinRequest.getRequestId();
                if (serialized) {
                    languageProvided = gremlinRequest.getArgs().containsKey(
                            Tokens.ARGS_LANGUAGE);
                }
                if (!languageProvided) {
                    gremlinRequest.getArgs().remove(Tokens.ARGS_LANGUAGE);
                }
                if (serialized && gremlinRequest.getArgs().get(
                        Tokens.ARGS_GREMLIN) instanceof Bytecode) {
                    rejection = "HTTP Bytecode requests are not supported; " +
                                "use the standard WebSocket traversal protocol";
                } else {
                    rejection = GremlinLangRequestGuard.rejection(
                            gremlinRequest);
                }
            }
        } catch (SerializationException | IllegalArgumentException ignored) {
            // Let TinkerPop produce its normal malformed-request response.
            gremlinRequest = null;
        } finally {
            content.readerIndex(readerIndex);
        }

        if (rejection != null) {
            this.sendRejection(context, request, requestId, rejection);
            return;
        }
        if (gremlinRequest == null || languageProvided) {
            super.channelRead(context, message);
            return;
        }

        FullHttpRequest normalized;
        try {
            normalized = this.withDefaultLanguage(context, request,
                                                  gremlinRequest, serialized);
        } catch (SerializationException | IllegalArgumentException e) {
            this.sendRejection(context, request,
                               gremlinRequest.getRequestId(), e.getMessage());
            return;
        }
        super.channelRead(context, normalized);
    }

    private boolean isSerializedRequest(FullHttpRequest request) {
        String contentType = request.headers().get(CONTENT_TYPE);
        return request.method() == POST && contentType != null &&
               !"application/json".equals(contentType) &&
               this.serializers.containsKey(contentType);
    }

    private boolean hasLanguageArgument(FullHttpRequest request,
                                        JsonNode jsonBody) {
        if (request.method() == GET) {
            String uri = request.uri();
            return new io.netty.handler.codec.http.QueryStringDecoder(uri).
                   parameters().containsKey(Tokens.ARGS_LANGUAGE);
        }
        return request.method() != POST ||
               jsonBody != null && jsonBody.has(Tokens.ARGS_LANGUAGE);
    }

    private JsonNode parseJsonBody(FullHttpRequest request) {
        try {
            return JSON_MAPPER.readTree(
                    request.content().toString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalArgumentException("body could not be parsed", e);
        }
    }

    private String rawTextRejection(JsonNode jsonBody) {
        if (jsonBody == null) {
            return null;
        }
        JsonNode gremlin = jsonBody.get(Tokens.ARGS_GREMLIN);
        if (gremlin != null && !gremlin.isTextual()) {
            return "The gremlin argument for a text eval request must be " +
                   "a string";
        }
        JsonNode language = jsonBody.get(Tokens.ARGS_LANGUAGE);
        if (jsonBody.has(Tokens.ARGS_LANGUAGE) && !language.isTextual()) {
            return "The language argument must be a string when provided";
        }
        return null;
    }

    private UUID requestId(JsonNode jsonBody) {
        if (jsonBody == null) {
            return null;
        }
        JsonNode requestId = jsonBody.get(Tokens.REQUEST_ID);
        if (requestId == null || !requestId.isTextual()) {
            return null;
        }
        try {
            return UUID.fromString(requestId.asText());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private FullHttpRequest withDefaultLanguage(
            ChannelHandlerContext context,
            FullHttpRequest request,
            RequestMessage gremlinRequest,
            boolean serialized) throws SerializationException {
        if (request.method() == GET) {
            String separator = request.uri().contains("?") ? "&" : "?";
            request.setUri(request.uri() + separator + Tokens.ARGS_LANGUAGE +
                           "=" + GremlinLangRequestGuard.GREMLIN_LANG);
            return request;
        }

        ByteBuf normalizedContent;
        if (serialized) {
            String contentType = request.headers().get(CONTENT_TYPE);
            MessageSerializer<?> serializer = this.serializers.get(
                    contentType);
            normalizedContent = serializer.serializeRequestAsBinary(
                    GremlinLangRequestGuard.normalize(gremlinRequest),
                    context.alloc());
        } else {
            try {
                JsonNode parsed = JSON_MAPPER.readTree(
                        request.content().toString(StandardCharsets.UTF_8));
                if (!(parsed instanceof ObjectNode)) {
                    throw new IllegalArgumentException(
                            "The request body must be a JSON object");
                }
                ObjectNode body = (ObjectNode) parsed;
                body.put(Tokens.ARGS_LANGUAGE,
                         GremlinLangRequestGuard.GREMLIN_LANG);
                byte[] bytes = JSON_MAPPER.writeValueAsBytes(body);
                normalizedContent = context.alloc().buffer();
                normalizedContent.writeBytes(bytes);
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalArgumentException(
                        "Failed to apply the default Gremlin language", e);
            }
        }

        FullHttpRequest normalized = request.replace(normalizedContent);
        normalized.headers().setInt(CONTENT_LENGTH,
                                    normalizedContent.readableBytes());
        ReferenceCountUtil.release(request);
        return normalized;
    }

    private void sendRejection(ChannelHandlerContext context,
                               FullHttpRequest request,
                               UUID requestId,
                               String rejection) {
        boolean keepAlive = HttpUtil.isKeepAlive(request);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", BAD_REQUEST.code());
        body.put("message", rejection);
        if (requestId != null) {
            body.put(Tokens.REQUEST_ID, requestId.toString());
        }
        ByteBuf content = Unpooled.copiedBuffer(JsonUtil.toJson(body),
                                                StandardCharsets.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HTTP_1_1, BAD_REQUEST, content);
        response.headers().set(CONTENT_TYPE,
                               "application/json; charset=UTF-8");
        response.headers().setInt(CONTENT_LENGTH, content.readableBytes());
        if (keepAlive) {
            response.headers().set(CONNECTION, KEEP_ALIVE);
        }

        ReferenceCountUtil.release(request);
        ERROR_METER.mark();
        ChannelFuture future = context.writeAndFlush(response);
        if (!keepAlive) {
            future.addListener(ChannelFutureListener.CLOSE);
        }
    }
}
