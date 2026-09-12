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

import java.util.UUID;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.hugegraph.auth.HugeAuthenticator.User;
import org.apache.hugegraph.security.script.PolicyScriptEngine;
import org.apache.hugegraph.task.TaskManager;
import org.apache.tinkerpop.gremlin.server.Context;
import org.apache.tinkerpop.gremlin.server.Settings;
import org.apache.tinkerpop.gremlin.server.op.session.Session;
import org.apache.tinkerpop.gremlin.server.util.DefaultGraphManager;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Transaction;
import org.apache.tinkerpop.gremlin.util.Tokens;
import org.apache.tinkerpop.gremlin.util.message.RequestMessage;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;

public class PolicySessionLifecycleTest {

    @Test
    public void testRollbackUsesSessionThreadAndOwnerAndClosesEngine() throws Exception {
        Settings settings = new Settings();
        DefaultGraphManager graphs = new DefaultGraphManager(settings);
        Graph graph = Mockito.mock(Graph.class);
        Graph.Features features = Mockito.mock(Graph.Features.class);
        Graph.Features.GraphFeatures graphFeatures = Mockito.mock(Graph.Features.GraphFeatures.class);
        Transaction transaction = Mockito.mock(Transaction.class);
        Mockito.when(graph.features()).thenReturn(features);
        Mockito.when(features.graph()).thenReturn(graphFeatures);
        Mockito.when(graphFeatures.supportsTransactions()).thenReturn(true);
        Mockito.when(graph.tx()).thenReturn(transaction);
        Mockito.when(transaction.isOpen()).thenReturn(true);
        graphs.putGraph("test", graph);
        AtomicLong rollbackThread = new AtomicLong();
        Mockito.doAnswer(invocation -> {
            rollbackThread.set(Thread.currentThread().getId());
            Assert.assertEquals("alice", HugeGraphAuthProxy.getContext().user().username());
            return null;
        }).when(transaction).rollback();
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
        ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
        PolicySession session = null;
        String oldAuth = AuthContext.getContext();
        String oldTask = TaskManager.getContext();
        String oldSpace = HugeGraphAuthProxy.getRequestGraphSpace();
        try {
            String id = UUID.randomUUID().toString();
            Context context = new Context(RequestMessage.build(Tokens.OPS_EVAL)
                    .addArg(Tokens.ARGS_SESSION, id).addArg(Tokens.ARGS_GREMLIN, "1").create(),
                    channel.pipeline().firstContext(), settings, graphs, null, timer);
            session = new PolicySession(id, context, sessions, new User("alice", HugeAuthenticator.ROLE_NONE));
            sessions.put(id, session);
            AuthContext.setContext(User.ADMIN.toJson());
            TaskManager.setContext(User.ADMIN.toJson());
            HugeGraphAuthProxy.setRequestGraphSpace("stale-admin-space");
            long worker = session.getExecutor().submit(() -> {
                Assert.assertEquals("alice", HugeGraphAuthProxy.getContext().user().username());
                Assert.assertTrue(Thread.currentThread().getName().startsWith("gremlin-server-exec-"));
                Assert.assertNull(TaskManager.getContext());
                Assert.assertNull(HugeGraphAuthProxy.getRequestGraphSpace());
                return Thread.currentThread().getId();
            }).get(10, TimeUnit.SECONDS);
            Assert.assertEquals(User.ADMIN.toJson(), AuthContext.getContext());
            Assert.assertEquals(User.ADMIN.toJson(), TaskManager.getContext());
            Assert.assertEquals("stale-admin-space", HugeGraphAuthProxy.getRequestGraphSpace());
            PolicyScriptEngine engine = (PolicyScriptEngine) session.getGremlinExecutor()
                    .getScriptEngineManager().getEngineByName("gremlin-groovy");
            session.manualKill(false);
            Assert.assertEquals(worker, rollbackThread.get());
            Assert.assertFalse(sessions.containsKey(id));
            Assert.assertTrue(session.getExecutor().isShutdown());
            Assert.assertThrows(javax.script.ScriptException.class, () -> engine.eval("1"));
        } finally {
            if (session != null) {
                session.manualKill(true);
            }
            channel.finishAndReleaseAll();
            timer.shutdownNow();
            HugeGraphAuthProxy.setRequestGraphSpace(oldSpace);
            if (oldAuth == null) {
                AuthContext.resetContext();
            } else {
                AuthContext.setContext(oldAuth);
            }
            if (oldTask == null) {
                TaskManager.resetContext();
            } else {
                TaskManager.setContext(oldTask);
            }
        }
    }
    @Test
    public void testContextRestoresRawIdentityBehindInternalOverrides() {
        HugeGraphAuthProxy.Context original = HugeGraphAuthProxy.setContext(null);
        String auth = AuthContext.getContext();
        String task = TaskManager.getContext();
        String space = HugeGraphAuthProxy.getRequestGraphSpace();
        try {
            User raw = new User("original", HugeAuthenticator.ROLE_NONE);
            HugeGraphAuthProxy.setContext(new HugeGraphAuthProxy.Context(raw));
            AuthContext.setContext(User.ADMIN.toJson());
            TaskManager.setContext(User.ADMIN.toJson());
            HugeGraphAuthProxy.setRequestGraphSpace("original-space");
            PolicySessionContext owner = new PolicySessionContext(new User("alice", HugeAuthenticator.ROLE_NONE));
            owner.run(() -> {
                Assert.assertEquals("alice", HugeGraphAuthProxy.getContext().user().username());
                Assert.assertNull(TaskManager.getContext());
                Assert.assertNull(HugeGraphAuthProxy.getRequestGraphSpace());
            });
            Assert.assertEquals(User.ADMIN.toJson(), AuthContext.getContext());
            Assert.assertEquals(User.ADMIN.toJson(), TaskManager.getContext());
            Assert.assertEquals("original-space", HugeGraphAuthProxy.getRequestGraphSpace());
            AuthContext.resetContext();
            TaskManager.resetContext();
            Assert.assertSame(raw, HugeGraphAuthProxy.getContext().user());
        } finally {
            HugeGraphAuthProxy.resetContext();
            if (original != null) {
                HugeGraphAuthProxy.setContext(original);
            }
            if (auth != null) {
                AuthContext.setContext(auth);
            }
            if (task == null) {
                TaskManager.resetContext();
            } else {
                TaskManager.setContext(task);
            }
            if (space != null) {
                HugeGraphAuthProxy.setRequestGraphSpace(space);
            }
        }
    }

    @Test
    public void testRetainedAliasesResolveCurrentGraphAndOwnerIsPinned() throws Exception {
        Settings settings = new Settings();
        DefaultGraphManager graphs = new DefaultGraphManager(settings);
        Graph initial = Mockito.mock(Graph.class);
        Graph replacement = Mockito.mock(Graph.class);
        graphs.putGraph("test", initial);
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
        EmbeddedChannel other = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
        ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
        PolicySession session = null;
        try {
            String id = UUID.randomUUID().toString();
            RequestMessage request = RequestMessage.build(Tokens.OPS_EVAL)
                    .addArg(Tokens.ARGS_SESSION, id).addArg(Tokens.ARGS_GREMLIN, "1")
                    .addArg(Tokens.ARGS_ALIASES, Map.of("chosen", "test")).create();
            Context first = new Context(request, channel.pipeline().firstContext(), settings, graphs, null, timer);
            session = new PolicySession(id, first, sessions, new User("alice", HugeAuthenticator.ROLE_NONE));
            sessions.put(id, session);
            Assert.assertTrue(session.ownedBy(new User("alice", HugeAuthenticator.ROLE_NONE)));
            Assert.assertFalse(session.ownedBy(new User("bob", HugeAuthenticator.ROLE_NONE)));
            Assert.assertFalse(session.isBoundTo(other));
            PolicySessionOpProcessor processor = new PolicySessionOpProcessor();
            PolicySession active = session;
            Assert.assertSame(initial, active.getExecutor().submit(() -> {
                javax.script.Bindings candidate = processor.getBindingMaker(active).apply(first).get();
                active.publishAliases();
                return candidate.get("chosen");
            }).get(10, TimeUnit.SECONDS));
            Context following = new Context(RequestMessage.build(Tokens.OPS_EVAL)
                    .addArg(Tokens.ARGS_SESSION, id).addArg(Tokens.ARGS_GREMLIN, "1").create(),
                    channel.pipeline().firstContext(), settings, graphs, null, timer);
            graphs.removeGraph("test");
            ExecutionException unavailable = Assert.assertThrows(ExecutionException.class,
                    () -> active.getExecutor().submit(() -> processor.getBindingMaker(active)
                            .apply(following).get()).get(10, TimeUnit.SECONDS));
            Assert.assertTrue(unavailable.getCause().getMessage().contains("SCRIPT_SESSION_ALIAS_UNAVAILABLE"));
            graphs.putGraph("test", replacement);
            Assert.assertSame(replacement, active.getExecutor().submit(() -> processor.getBindingMaker(active)
                    .apply(following).get().get("chosen")).get(10, TimeUnit.SECONDS));
        } finally {
            if (session != null) {
                session.manualKill(true);
            }
            channel.finishAndReleaseAll();
            other.finishAndReleaseAll();
            timer.shutdownNow();
        }
    }

}
