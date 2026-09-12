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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javax.script.Bindings;

import javax.script.SimpleBindings;

import org.apache.hugegraph.auth.HugeAuthenticator.User;
import org.apache.hugegraph.testutil.Whitebox;
import org.apache.hugegraph.security.script.PolicyScriptEngine;
import org.apache.hugegraph.task.TaskManager;
import org.apache.tinkerpop.gremlin.groovy.engine.GremlinExecutor;
import org.apache.tinkerpop.gremlin.server.Context;
import org.apache.tinkerpop.gremlin.server.Settings;
import org.apache.tinkerpop.gremlin.server.op.session.Session;
import org.apache.tinkerpop.gremlin.server.util.MetricManager;
import org.apache.tinkerpop.gremlin.util.function.ThrowingConsumer;

/** Version-pinned adapter retaining TinkerPop 3.8.1 session transaction/lifecycle code. */
final class PolicySession extends Session {

    private volatile PolicySessionContext identity;
    private volatile boolean closeRequested;
    private PolicyGremlinScriptEngineManager manager;
    private User owner;
    private PolicyScriptEngine engine;
    private final Map<String, String> aliases = new LinkedHashMap<>();
    private final ThreadLocal<Map<String, String>> pendingAliases = new ThreadLocal<>();

    PolicySession(String id, Context context, ConcurrentHashMap<String, Session> sessions, User owner) {
        super(id, bootstrapFree(context), sessions);
        this.owner = User.fromJson(owner.toJson());
        this.identity = new PolicySessionContext(this.owner);
        ExecutorService original = super.getExecutor();
        try {
            GremlinExecutor executor = this.getGremlinExecutor();
            // The binding supplier resolves current graphs on the session worker on every request.
            this.manager = new PolicyGremlinScriptEngineManager(new SimpleBindings(), this.getBindings());
            this.engine = (PolicyScriptEngine) this.manager.getEngineByName("gremlin-groovy");
            this.engine.deferSessionPublication();
            Consumer<Bindings> afterSuccess = Whitebox.getInternalState(executor, "afterSuccess");
            Consumer<Bindings> publish = bindings -> {
                this.engine.publishSession(bindings);
                afterSuccess.accept(bindings);
                this.publishAliases();
            };
            Whitebox.setInternalState(executor, "afterSuccess", publish);
            ExecutorService wrapped = new OwnerExecutor(original);
            Whitebox.setInternalState(this, "executor", wrapped);
            Whitebox.setInternalState(executor, "executorService", wrapped);
            Whitebox.setInternalState(executor, "globalBindings", new SimpleBindings());
            Whitebox.setInternalState(executor, "gremlinScriptEngineManager", this.manager);
            if (this.closeRequested || !context.getChannelHandlerContext().channel().isActive()) {
                throw new IllegalStateException("SCRIPT_SESSION_CLOSED");
            }
        } catch (Exception error) {
            original.shutdownNow();
            if (this.manager != null) {
                this.manager.close();
            }
            MetricManager.INSTANCE.getRegistry().removeMatching((name, metric) -> name.contains(".session." + id + "."));
            throw new IllegalStateException("Failed to initialize policy session", error);
        }
    }

    boolean ownedBy(User user) {
        return this.owner.equals(user);
    }

    void execute(ThrowingConsumer<Context> operation, Context context) throws Exception {
        Exception[] failure = new Exception[1];
        this.identity.run(() -> {
            try {
                operation.accept(context);
            } catch (Exception error) {
                failure[0] = error;
            }
        });
        if (failure[0] != null) {
            throw failure[0];
        }
    }

    Map<String, String> aliases() {
        return this.aliases;
    }

    void stageAliases(Map<String, String> aliases) {
        this.pendingAliases.set(new LinkedHashMap<>(aliases));
    }

    void publishAliases() {
        Map<String, String> candidate = this.pendingAliases.get();
        if (candidate != null) {
            this.aliases.clear();
            this.aliases.putAll(candidate);
            this.pendingAliases.remove();
        }
    }

    @Override
    public synchronized void kill(boolean force) {
        this.closeRequested = true;
        PolicySessionContext current = this.identity;
        if (current == null) {
            return;
        }
        current.run(() -> {
            try {
                super.kill(force);
            } finally {
                // A channel can close while computeIfAbsent is still publishing this session.
                if (!this.getExecutor().isShutdown()) {
                    this.getExecutor().shutdownNow();
                }
            }
        });
    }

    private static Context bootstrapFree(Context context) {
        Settings settings = new Settings();
        settings.evaluationTimeout = context.getSettings().evaluationTimeout;
        settings.processors = context.getSettings().processors;
        settings.scriptEngines = Collections.singletonMap("gremlin-groovy", new Settings.ScriptEngineSettings());
        return new Context(context.getRequestMessage(), context.getChannelHandlerContext(), settings,
                context.getGraphManager(), context.getGremlinExecutor(), context.getScheduledExecutorService());
    }

    private final class OwnerExecutor extends AbstractExecutorService {

        private final ExecutorService delegate;

        OwnerExecutor(ExecutorService delegate) {
            this.delegate = delegate;
        }

        @Override
        public void execute(Runnable command) {
            this.delegate.execute(() -> {
                Thread worker = Thread.currentThread();
                String previous = worker.getName();
                worker.setName("gremlin-server-exec-policy-session-" + previous);
                try {
                    HugeGraphAuthProxy.resetContext();
                    TaskManager.resetContext();
                    identity.run(command);
                } finally {
                    engine.abortSession();
                    pendingAliases.remove();
                    HugeGraphAuthProxy.resetContext();
                    TaskManager.resetContext();
                    worker.setName(previous);
                }
            });
        }

        @Override
        public void shutdown() {
            this.delegate.shutdown();
        }

        @Override
        public List<Runnable> shutdownNow() {
            try {
                return this.delegate.shutdownNow();
            } finally {
                manager.close();
            }
        }

        @Override
        public boolean isShutdown() {
            return this.delegate.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return this.delegate.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return this.delegate.awaitTermination(timeout, unit);
        }
    }
}
