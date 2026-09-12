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
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import javax.script.Bindings;
import javax.script.SimpleBindings;

import org.apache.hugegraph.auth.HugeAuthenticator.User;
import org.apache.hugegraph.security.script.ScriptBindings;
import org.apache.hugegraph.security.script.ScriptPolicyRuntime;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalSource;
import org.apache.tinkerpop.gremlin.server.Context;
import org.apache.tinkerpop.gremlin.server.auth.AuthenticatedUser;
import org.apache.tinkerpop.gremlin.server.auth.AllowAllAuthenticator;
import org.apache.tinkerpop.gremlin.server.handler.StateKey;
import org.apache.tinkerpop.gremlin.server.op.OpProcessorException;
import org.apache.tinkerpop.gremlin.server.op.session.Session;
import org.apache.tinkerpop.gremlin.server.op.session.SessionOpProcessor;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.util.Tokens;
import org.apache.tinkerpop.gremlin.util.function.ThrowingConsumer;
import org.apache.tinkerpop.gremlin.util.message.ResponseMessage;
import org.apache.tinkerpop.gremlin.util.message.ResponseStatusCode;

/** Separate SPI name avoids replacing the legacy processor in the process-wide OpLoader. */
public final class PolicySessionOpProcessor extends SessionOpProcessor {

    @Override
    public String getName() {
        return "policy-session";
    }

    @Override
    protected void evalOp(Context context) throws OpProcessorException {
        this.ensureSession(context, true);
        super.evalOp(new PolicySessionRequestContext(context));
    }

    @Override
    @SuppressWarnings("rawtypes")
    protected void handleIterator(Context context, Iterator iterator) throws InterruptedException {
        super.handleIterator(context, iterator);
        if (!(context instanceof PolicySessionRequestContext) ||
            !((PolicySessionRequestContext) context).succeeded()) {
            // TP handles serialization errors internally and otherwise returns normally. Re-enter its
            // ordinary failure/iterator-cleanup path instead of publishing a failed request's bindings.
            throw new IllegalStateException("SCRIPT_SESSION_RESPONSE_FAILED");
        }
    }

    @Override
    public Optional<ThrowingConsumer<Context>> selectOther(Context context) throws OpProcessorException {
        Optional<ThrowingConsumer<Context>> selected = super.selectOther(context);
        return selected.map(operation -> current -> {
            PolicySession session = this.ensureSession(current,
                    !Tokens.OPS_CLOSE.equals(current.getRequestMessage().getOp()));
            if (session == null) {
                operation.accept(current);
            } else {
                session.execute(operation, current);
            }
        });
    }

    private PolicySession ensureSession(Context context, boolean create) throws OpProcessorException {
        if (!ScriptPolicyRuntime.enabled()) {
            throw denied(context, "SCRIPT_SESSION_PROCESSOR_DENIED");
        }
        Object id = context.getRequestMessage().getArgs().get(Tokens.ARGS_SESSION);
        if (!(id instanceof String) || ((String) id).isEmpty() || ((String) id).length() > 256) {
            throw denied(context, "SCRIPT_SESSION_ID_DENIED");
        }
        AuthenticatedUser authenticated = context.getChannelHandlerContext().channel()
                                               .attr(StateKey.AUTHENTICATED_USER).get();
        User user;
        if (authenticated instanceof User) {
            user = (User) authenticated;
        } else if (AllowAllAuthenticator.class.getName().equals(context.getSettings().authentication.authenticator)) {
            user = User.ANONYMOUS;
        } else {
            throw denied(context, "SCRIPT_SESSION_IDENTITY_DENIED");
        }
        Session existing;
        try {
            existing = create ? sessions.computeIfAbsent((String) id,
                    key -> new PolicySession(key, context, sessions, user)) : sessions.get(id);
        } catch (RuntimeException error) {
            throw denied(context, "SCRIPT_SESSION_INITIALIZATION_FAILED");
        }
        if (existing == null) {
            return null;
        }
        if (!(existing instanceof PolicySession) || !((PolicySession) existing).ownedBy(user) ||
            !existing.isBoundTo(context.getChannelHandlerContext().channel()) || !existing.acceptingRequests()) {
            throw denied(context, "SCRIPT_SESSION_OWNER_OR_STATE_DENIED");
        }
        return (PolicySession) existing;
    }

    @Override
    protected Function<Context, BindingSupplier> getBindingMaker(Session session) {
        return context -> () -> {
            PolicySession policy = (PolicySession) session;
            Bindings bindings = new SimpleBindings(new LinkedHashMap<>(session.getBindings()));
            // Persist data and alias names; resolve graph objects again after graph create/drop.
            bindings.entrySet().removeIf(entry -> entry.getValue() instanceof Graph ||
                                                 entry.getValue() instanceof TraversalSource);
            Map<String, String> aliases = new LinkedHashMap<>(policy.aliases());
            Object requestedAliases = context.getRequestMessage().getArgs().get(Tokens.ARGS_ALIASES);
            if (requestedAliases instanceof Map) {
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) requestedAliases).entrySet()) {
                    if (!(entry.getKey() instanceof String) || !(entry.getValue() instanceof String)) {
                        throw denied(context, "SCRIPT_SESSION_ALIAS_DENIED");
                    }
                    aliases.put((String) entry.getKey(), (String) entry.getValue());
                }
            }
            Map<String, Object> resolved = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : aliases.entrySet()) {
                Object graph = context.getGraphManager().getGraph(entry.getValue());
                if (graph == null) {
                    graph = context.getGraphManager().getTraversalSource(entry.getValue());
                }
                if (graph == null) {
                    throw denied(context, "SCRIPT_SESSION_ALIAS_UNAVAILABLE");
                }
                resolved.put(entry.getKey(), graph);
            }
            Object input = context.getRequestMessage().getArgs().get(Tokens.ARGS_BINDINGS);
            if (input instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> parameters = (Map<String, Object>) input;
                bindings.putAll(ScriptBindings.client(parameters));
            }
            bindings.putAll(context.getGraphManager().getAsBindings());
            bindings.putAll(resolved);
            policy.stageAliases(aliases);
            return bindings;
        };
    }

    private static OpProcessorException denied(Context context, String message) {
        return new OpProcessorException(message, ResponseMessage.build(context.getRequestMessage())
                .code(ResponseStatusCode.REQUEST_ERROR_INVALID_REQUEST_ARGUMENTS)
                .statusMessage(message).create());
    }
}
