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
import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.hugegraph.util.JsonUtil;
import org.apache.tinkerpop.gremlin.groovy.engine.GremlinExecutor;
import org.apache.tinkerpop.gremlin.server.GraphManager;
import org.apache.tinkerpop.gremlin.server.Settings;
import org.apache.tinkerpop.gremlin.server.handler.HttpGremlinEndpointHandler;
import org.apache.tinkerpop.gremlin.server.handler.HttpHandlerUtil;
import org.apache.tinkerpop.gremlin.util.MessageSerializer;
import org.apache.tinkerpop.gremlin.util.message.RequestMessage;
import org.apache.tinkerpop.gremlin.util.ser.SerializationException;

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
        String rejection = null;
        try {
            RequestMessage gremlinRequest =
                    HttpHandlerUtil.getRequestMessageFromHttpRequest(
                            request, this.serializers);
            rejection = GremlinLangRequestGuard.rejection(gremlinRequest);
        } catch (SerializationException | IllegalArgumentException ignored) {
            // Let TinkerPop produce its normal malformed-request response.
        } finally {
            content.readerIndex(readerIndex);
        }

        if (rejection != null) {
            this.sendRejection(context, request, rejection);
            return;
        }
        super.channelRead(context, message);
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
