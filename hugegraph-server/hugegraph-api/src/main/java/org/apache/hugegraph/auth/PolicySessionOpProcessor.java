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
        PolicySession session = this.ensureSession(context, true);
        synchronized (session) {
            ensureRegistered(session, context);
            super.evalOp(new PolicySessionRequestContext(context));
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
                synchronized (session) {
                    ensureRegistered(session, current);
                    session.execute(operation, current);
                }
            }
        });
    }

    private static void ensureRegistered(PolicySession session, Context context) throws OpProcessorException {
        // TP's eval and bytecode dispatch look up the session again and create a legacy Session if absent.
        // Hold the same monitor as kill() through dispatch; execution remains asynchronous on its worker.
        if (sessions.get(session.getSessionId()) != session || !session.acceptingRequests()) {
            throw denied(context, "SCRIPT_SESSION_OWNER_OR_STATE_DENIED");
        }
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
            Map<String, String> aliases = new LinkedHashMap<>(policy.aliases());
            // A successful script can replace an ordinary alias with data, including null.
            aliases.keySet().removeIf(name -> bindings.containsKey(name) &&
                    !(bindings.get(name) instanceof Graph) && !(bindings.get(name) instanceof TraversalSource));
            // Persist data and alias names; resolve graph objects again after graph create/drop.
            bindings.entrySet().removeIf(entry -> entry.getValue() instanceof Graph ||
                                                 entry.getValue() instanceof TraversalSource);
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
            context.getGraphManager().getAsBindings().forEach((name, value) -> {
                if (!bindings.containsKey(name)) {
                    bindings.put(name, value);
                }
            });
            bindings.putAll(resolved);
            Object input = context.getRequestMessage().getArgs().get(Tokens.ARGS_BINDINGS);
            if (input instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> parameters = (Map<String, Object>) input;
                bindings.putAll(ScriptBindings.client(parameters));
                aliases.keySet().removeAll(parameters.keySet());
            }
            // TP retains valid request parameters and aliases even if subsequent compilation/evaluation fails.
            Bindings retained = ScriptBindings.execution(bindings,
                    org.apache.hugegraph.security.script.ScriptExecutionProfile.QUERY);
            session.getBindings().clear();
            session.getBindings().putAll(retained);
            policy.stageAliases(aliases);
            policy.publishAliases();
            return session.getBindings();
        };
    }

    private static OpProcessorException denied(Context context, String message) {
        return new OpProcessorException(message, ResponseMessage.build(context.getRequestMessage())
                .code(ResponseStatusCode.REQUEST_ERROR_INVALID_REQUEST_ARGUMENTS)
                .statusMessage(message).create());
    }
}
