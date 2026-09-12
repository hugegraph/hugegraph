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

import java.util.Map;
import java.util.UUID;

import org.apache.tinkerpop.gremlin.groovy.engine.GremlinExecutor;
import org.apache.tinkerpop.gremlin.server.GraphManager;
import org.apache.tinkerpop.gremlin.server.Settings;
import org.apache.tinkerpop.gremlin.server.handler.HttpGremlinEndpointHandler;
import org.apache.tinkerpop.gremlin.server.handler.HttpHandlerUtil;
import org.apache.tinkerpop.gremlin.util.MessageSerializer;
import org.apache.tinkerpop.gremlin.util.Tokens;
import org.apache.tinkerpop.gremlin.util.message.RequestMessage;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;

/** Validate HTTP before the standard endpoint consumes the request directly. */
@ChannelHandler.Sharable
public final class PolicyHttpGremlinEndpointHandler extends HttpGremlinEndpointHandler {

    private final Map<String, MessageSerializer<?>> serializers;
    private final ScriptRequestGuard guard;

    public PolicyHttpGremlinEndpointHandler(Map<String, MessageSerializer<?>> serializers,
                                            GremlinExecutor executor,
                                            GraphManager graphs,
                                            Settings settings) {
        super(serializers, executor, graphs, settings);
        this.serializers = serializers;
        this.guard = new ScriptRequestGuard(settings.evaluationTimeout);
    }

    @Override
    public void channelRead(ChannelHandlerContext context, Object message) {
        if (message instanceof FullHttpRequest) {
            FullHttpRequest request = (FullHttpRequest) message;
            if ((request.method().equals(HttpMethod.GET) ||
                 request.method().equals(HttpMethod.POST)) &&
                !"/favicon.ico".equals(request.uri())) {
                RequestMessage decoded = null;
                try {
                    // A duplicate has independent reader/mark indices; the
                    // endpoint still owns the original reference count.
                    decoded = HttpHandlerUtil.getRequestMessageFromHttpRequest(
                            request.duplicate(), this.serializers);
                    if ("session".equals(decoded.getProcessor())) {
                        throw new IllegalArgumentException("SCRIPT_HTTP_SESSION_UNSUPPORTED");
                    }
                    RequestMessage checked = this.guard.validate(decoded);
                    rejectTimeoutOverride(decoded, checked);
                } catch (Exception error) {
                    String reason = error instanceof IllegalArgumentException &&
                                    error.getMessage() != null &&
                                    error.getMessage().startsWith("SCRIPT_") ?
                                    error.getMessage() : "SCRIPT_REQUEST_DENIED";
                    UUID requestId = decoded == null ?
                                     UUID.randomUUID() : decoded.getRequestId();
                    sendDenied(context, request, requestId, reason);
                    ReferenceCountUtil.release(message);
                    return;
                }
            }
        }
        super.channelRead(context, message);
    }

    private static void rejectTimeoutOverride(RequestMessage decoded,
                                              RequestMessage checked) {
        Object timeout = decoded.getArgs().get(Tokens.ARGS_EVAL_TIMEOUT);
        if (timeout == null) {
            return;
        }
        Object limited = checked.getArgs().get(Tokens.ARGS_EVAL_TIMEOUT);
        if (!(timeout instanceof Number) || !(limited instanceof Number) ||
            ((Number) timeout).longValue() <= 0 ||
            ((Number) timeout).longValue() > ((Number) limited).longValue()) {
            throw new IllegalArgumentException("SCRIPT_TIMEOUT_OVERRIDE_DENIED");
        }
    }

    private static void sendDenied(ChannelHandlerContext context,
                                   FullHttpRequest request,
                                   UUID requestId,
                                   String reason) {
        boolean keepAlive = HttpUtil.isKeepAlive(request);
        String body = "{\"message\":\"" + reason + "\",\"requestId\":\"" +
                      requestId + "\"}";
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_REQUEST,
                Unpooled.copiedBuffer(body, CharsetUtil.UTF_8));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        HttpUtil.setKeepAlive(response, keepAlive);
        HttpUtil.setContentLength(response, response.content().readableBytes());
        if (!keepAlive) {
            context.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        } else {
            context.writeAndFlush(response);
        }
    }
}
