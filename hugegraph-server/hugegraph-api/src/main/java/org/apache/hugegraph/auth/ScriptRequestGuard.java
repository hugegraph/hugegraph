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

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.hugegraph.security.script.ScriptBindings;
import org.apache.hugegraph.security.script.ScriptBytecodePolicy;
import org.apache.hugegraph.security.script.ScriptExecutionBudget;
import org.apache.tinkerpop.gremlin.process.traversal.Bytecode;
import org.apache.tinkerpop.gremlin.process.traversal.GraphOp;
import org.apache.tinkerpop.gremlin.util.Tokens;
import org.apache.tinkerpop.gremlin.util.message.RequestMessage;
import org.apache.tinkerpop.gremlin.util.message.ResponseMessage;
import org.apache.tinkerpop.gremlin.util.message.ResponseStatusCode;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

public final class ScriptRequestGuard extends ChannelInboundHandlerAdapter {

    private final long timeout;

    public ScriptRequestGuard(long timeout) {
        this.timeout = timeout > 0 ? Math.min(timeout, ScriptExecutionBudget.TIMEOUT_MILLIS) :
                       ScriptExecutionBudget.TIMEOUT_MILLIS;
    }

    @Override
    public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
        if (!(message instanceof RequestMessage)) {
            context.fireChannelRead(message);
            return;
        }
        RequestMessage request = (RequestMessage) message;
        try {
            context.fireChannelRead(this.validate(request));
        } catch (IllegalArgumentException error) {
            context.writeAndFlush(ResponseMessage.build(request)
                    .code(ResponseStatusCode.REQUEST_ERROR_INVALID_REQUEST_ARGUMENTS)
                    .statusMessage(error.getMessage()).create());
        }
    }

    RequestMessage validate(RequestMessage request) {
        boolean session = "session".equals(request.getProcessor());
        if (!request.getProcessor().isEmpty() && !"traversal".equals(request.getProcessor()) &&
            !"cypher".equals(request.getProcessor()) && !session) {
            throw new IllegalArgumentException("SCRIPT_SESSION_OR_PROCESSOR_DENIED");
        }
        if (session) {
            Object id = request.getArgs().get(Tokens.ARGS_SESSION);
            if (!(id instanceof String) || ((String) id).isEmpty() || ((String) id).length() > 256) {
                throw new IllegalArgumentException("SCRIPT_SESSION_ID_DENIED");
            }
            if (!Tokens.OPS_EVAL.equals(request.getOp()) && !Tokens.OPS_BYTECODE.equals(request.getOp()) &&
                !Tokens.OPS_CLOSE.equals(request.getOp())) {
                throw new IllegalArgumentException("SCRIPT_SESSION_OPERATION_DENIED");
            }
        }
        Object data = request.getArgs().get(Tokens.ARGS_BINDINGS);
        if (data != null) {
            if (!(data instanceof Map)) {
                throw new IllegalArgumentException("SCRIPT_BINDING_DENIED");
            }
            Map<String, Object> parameters = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) data).entrySet()) {
                if (!(entry.getKey() instanceof String)) {
                    throw new IllegalArgumentException("SCRIPT_BINDING_DENIED");
                }
                parameters.put((String) entry.getKey(), entry.getValue());
            }
            ScriptBindings.client(parameters);
        }
        Object script = request.getArgs().get(Tokens.ARGS_GREMLIN);
        if (session && ((Tokens.OPS_EVAL.equals(request.getOp()) && !(script instanceof String)) ||
                        (Tokens.OPS_BYTECODE.equals(request.getOp()) && !(script instanceof Bytecode)))) {
            throw new IllegalArgumentException("SCRIPT_SESSION_SCRIPT_FORMAT_DENIED");
        }
        if ("traversal".equals(request.getProcessor()) && !(script instanceof Bytecode)) {
            throw new IllegalArgumentException("SCRIPT_BYTECODE_FORMAT_DENIED");
        }
        if (script instanceof Bytecode) {
            if (!session || (!GraphOp.TX_COMMIT.equals((Bytecode) script) &&
                             !GraphOp.TX_ROLLBACK.equals((Bytecode) script))) {
                ScriptBytecodePolicy.validate((Bytecode) script);
            }
        }
        Object requestedTimeout = request.getArgs().get(Tokens.ARGS_EVAL_TIMEOUT);
        long limit = this.timeout;
        if (requestedTimeout instanceof Number && ((Number) requestedTimeout).longValue() > 0) {
            limit = Math.min(limit, ((Number) requestedTimeout).longValue());
        }
        return RequestMessage.from(request)
                .processor(session ? "policy-session" : request.getProcessor())
                .addArg(Tokens.ARGS_EVAL_TIMEOUT, limit).create();
    }
}
