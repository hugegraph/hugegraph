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

import org.apache.hugegraph.util.JsonUtil;
import org.apache.tinkerpop.gremlin.process.traversal.Bytecode;
import org.apache.tinkerpop.gremlin.groovy.engine.GremlinExecutor;
import org.apache.tinkerpop.gremlin.server.GraphManager;
import org.apache.tinkerpop.gremlin.server.Settings;
import org.apache.tinkerpop.gremlin.server.handler.HttpGremlinEndpointHandler;
import org.apache.tinkerpop.gremlin.server.handler.HttpHandlerUtil;
import org.apache.tinkerpop.gremlin.util.MessageSerializer;
import org.apache.tinkerpop.gremlin.util.Tokens;
import org.apache.tinkerpop.gremlin.util.message.RequestMessage;
import org.apache.tinkerpop.gremlin.util.ser.SerializationException;
import org.apache.tinkerpop.shaded.jackson.databind.JsonNode;
import org.apache.tinkerpop.shaded.jackson.databind.ObjectMapper;
import org.apache.tinkerpop.shaded.jackson.databind.node.ObjectNode;

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
        RequestMessage gremlinRequest;
        boolean serialized = this.isSerializedRequest(request);
        boolean languageProvided;
        String rejection = null;
        try {
            languageProvided = !serialized &&
                               this.hasLanguageArgument(request);
            gremlinRequest = HttpHandlerUtil.getRequestMessageFromHttpRequest(
                    request, this.serializers);
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
        } catch (SerializationException | IllegalArgumentException ignored) {
            // Let TinkerPop produce its normal malformed-request response.
            gremlinRequest = null;
            languageProvided = true;
        } finally {
            content.readerIndex(readerIndex);
        }

        if (rejection != null) {
            this.sendRejection(context, request, rejection);
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
            this.sendRejection(context, request, e.getMessage());
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

    private boolean hasLanguageArgument(FullHttpRequest request) {
        if (request.method() == GET) {
            String uri = request.uri();
            return new io.netty.handler.codec.http.QueryStringDecoder(uri).
                   parameters().containsKey(Tokens.ARGS_LANGUAGE);
        }
        if (request.method() != POST) {
            return true;
        }
        try {
            JsonNode body = JSON_MAPPER.readTree(
                    request.content().toString(StandardCharsets.UTF_8));
            return body != null && body.has(Tokens.ARGS_LANGUAGE);
        } catch (Exception e) {
            throw new IllegalArgumentException("body could not be parsed", e);
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
                               String rejection) {
        boolean keepAlive = HttpUtil.isKeepAlive(request);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", BAD_REQUEST.code());
        body.put("message", rejection);
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
        ChannelFuture future = context.writeAndFlush(response);
        if (!keepAlive) {
            future.addListener(ChannelFutureListener.CLOSE);
        }
    }
}
