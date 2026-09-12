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

package org.apache.hugegraph.unit.core;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.script.ScriptException;
import javax.script.SimpleBindings;

import org.apache.commons.configuration2.BaseConfiguration;
import org.apache.commons.io.FileUtils;
import org.apache.hugegraph.HugeFactory;
import org.apache.hugegraph.HugeGraph;
import org.apache.hugegraph.auth.ContextGremlinServer;
import org.apache.hugegraph.auth.HugeAuthenticator;
import org.apache.hugegraph.auth.HugeGraphAuthProxy;
import org.apache.hugegraph.auth.RolePermission;
import org.apache.hugegraph.backend.id.IdGenerator;
import org.apache.hugegraph.config.HugeConfig;
import org.apache.hugegraph.auth.AuthContext;
import org.apache.hugegraph.core.GraphManager;
import org.apache.hugegraph.dist.RegisterUtil;
import org.apache.hugegraph.event.EventHub;
import org.apache.hugegraph.job.GremlinJob;
import org.apache.hugegraph.security.HugeSecurityManager;
import org.apache.hugegraph.security.script.PolicyScriptEngine;
import org.apache.hugegraph.security.script.PolicyScriptEngines;
import org.apache.hugegraph.security.script.ScriptExecutionProfile;
import org.apache.hugegraph.security.script.ScriptPolicyRuntime;
import org.apache.hugegraph.security.script.ScriptSecurityMode;
import org.apache.hugegraph.task.HugeTask;
import org.apache.hugegraph.task.TaskManager;
import org.apache.hugegraph.traversal.optimize.HugeScriptTraversal;
import org.apache.hugegraph.util.Events;
import org.apache.hugegraph.util.JsonUtil;
import org.apache.tinkerpop.gremlin.driver.Client;
import org.apache.tinkerpop.gremlin.driver.Cluster;
import org.apache.tinkerpop.gremlin.driver.RequestOptions;
import org.apache.tinkerpop.gremlin.groovy.jsr223.GremlinGroovyScriptEngine;
import org.apache.tinkerpop.gremlin.process.traversal.GraphOp;
import org.apache.tinkerpop.gremlin.server.Settings;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.util.message.RequestMessage;
import org.junit.Assert;

public final class PolicyGraphModeProbe {

    public static void main(String[] args) throws Exception {
        RegisterUtil.registerBackends();
        BaseConfiguration config = new BaseConfiguration();
        String backend = args.length == 0 ? "memory" : args[0];
        Path directory = Files.createTempDirectory("hg-policy-graph-");
        config.setProperty("backend", backend);
        config.setProperty("rocksdb.data_path", directory.resolve("data").toString());
        config.setProperty("rocksdb.wal_path", directory.resolve("wal").toString());
        config.setProperty("serializer", backend.equals("memory") ? "text" : "binary");
        config.setProperty("store", "policy_graph");
        config.setProperty("query.ignore_invalid_data", false);
        HugeGraph graph = HugeFactory.open(new HugeConfig(config));
        try {
            graph.initBackend();
            // Match GraphManager startup: restore task metadata before accepting script workers.
            graph.serverStarted(null);
            // HugeGraphServer also initializes the deployment engine before restoring HSM.
            new GremlinGroovyScriptEngine().eval("1 + 1");
            if (ScriptPolicyRuntime.mode() == ScriptSecurityMode.COMBINED) {
                System.setSecurityManager(new HugeSecurityManager());
            }
            Assert.assertEquals(ScriptPolicyRuntime.mode() == ScriptSecurityMode.COMBINED,
                    System.getSecurityManager() instanceof HugeSecurityManager);
            PolicyScriptEngines.initializeServer();
            runOnWorker(() -> {
                GraphManager.prepareSchema(graph,
                        "schema.propertyKey('name').asText().ifNotExist().create(); " +
                        "schema.vertexLabel('person').properties('name').useCustomizeStringId().ifNotExist().create()");
                graph.tx().commit();
                return null;
            });
            try (HugeScriptTraversal<?, ?> traversal = new HugeScriptTraversal<>(graph.traversal(),
                    "gremlin-groovy", "g.addV('person').property(T.id, 'v1').property('name', 'alice')",
                    Map.of(), Map.of())) {
                Assert.assertTrue(traversal.hasNext());
                traversal.next();
            }
            graph.tx().commit();
            try (PolicyScriptEngine engine = new PolicyScriptEngine(ScriptExecutionProfile.QUERY)) {
                Assert.assertEquals(1L, engine.eval("g.V().count().next()",
                        new SimpleBindings(Map.of("g", graph.traversal()))));
                Assert.assertThrows(ScriptException.class, () -> engine.eval("graph.schema()",
                        new SimpleBindings(Map.of("graph", graph))));
            }
            TestJob job = new TestJob(graph,
                    "g.addV('person').property(T.id, 'rollback').property('name', 'bob').iterate(); " +
                    "int x = 0; 1 / x");
            Assert.assertThrows(Exception.class, job::execute);
            Assert.assertFalse(graph.vertices("rollback").hasNext());
            new TestJob(graph,
                    "g.addV('person').property(T.id, 'committed').property('name', 'bob').iterate(); " +
                    "gremlinJob.progress()").execute();
            Assert.assertTrue(graph.vertices("committed").hasNext());
            graph.tx().rollback();

            HugeGraphAuthProxy proxy = new HugeGraphAuthProxy(graph);
            try (PolicyScriptEngine engine = new PolicyScriptEngine(ScriptExecutionProfile.QUERY)) {
                String source = "g.V().count().next()";
                SimpleBindings bindings = new SimpleBindings(Map.of("g", proxy.traversal()));
                TaskManager.setContext(new HugeAuthenticator.User("admin", RolePermission.admin()).toJson());
                Assert.assertEquals(2L, engine.eval(source, bindings));
                TaskManager.setContext(new HugeAuthenticator.User("denied", RolePermission.none()).toJson());
                Assert.assertThrows(ScriptException.class, () -> engine.eval(source, bindings));
                TaskManager.setContext(new HugeAuthenticator.User("admin", RolePermission.admin()).toJson());
                Assert.assertEquals(2L, engine.eval(source, bindings));
                Assert.assertEquals(1L, engine.compilationCount());
            } finally {
                TaskManager.resetContext();
                HugeGraphAuthProxy.resetContext();
            }
            runOnTaskWorker(graph, "g.V().count().next()");
            AtomicReference<Throwable> denied = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try {
                    new TestJob(graph, "System.exit(0)").execute();
                } catch (Throwable error) {
                    denied.set(error);
                }
            }, "task-worker-policy-deny");
            worker.setDaemon(true);
            worker.start();
            worker.join(TimeUnit.SECONDS.toMillis(30));
            Assert.assertFalse(worker.isAlive());
            Assert.assertNotNull(denied.get());
            verifyDynamicGraphDrop(graph);
            System.out.println("GRAPH_POLICY_VERIFIED " +
                               ScriptPolicyRuntime.mode().configValue() +
                               " manager=" + (System.getSecurityManager() == null ?
                                              "none" : "installed"));
        } finally {
            PolicyScriptEngines.close();
            graph.close();
            HugeFactory.shutdown(10L);
            FileUtils.deleteDirectory(directory.toFile());
        }
    }

    private static void runOnTaskWorker(HugeGraph graph, String source) throws Exception {
        runOnWorker(() -> new TestJob(graph, source).execute());
    }

    private static void runOnWorker(Callable<?> action) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                action.call();
            } catch (Throwable error) {
                failure.set(error);
            }
        }, "task-worker-policy-graph");
        worker.setDaemon(true);
        worker.start();
        worker.join(TimeUnit.SECONDS.toMillis(30));
        Assert.assertFalse(worker.isAlive());
        if (failure.get() != null) {
            throw new AssertionError("Task worker failed", failure.get());
        }
    }

    private static void verifyDynamicGraphDrop(HugeGraph graph) throws Exception {
        int port;
        try (ServerSocket reservation = new ServerSocket(0)) {
            port = reservation.getLocalPort();
        }
        Settings settings = new Settings();
        settings.host = "127.0.0.1";
        settings.port = port;
        settings.gremlinPool = 1;
        settings.threadPoolWorker = 1;
        settings.threadPoolBoss = 1;
        settings.channelizer = "org.apache.tinkerpop.gremlin.server.channel.WsAndHttpChannelizer";
        settings.scriptEngines.clear();
        settings.scriptEngines.put("gremlin-groovy", new Settings.ScriptEngineSettings());
        EventHub hub = new EventHub("script-policy-graph-lifecycle");
        ContextGremlinServer server = new ContextGremlinServer(settings, hub);
        try {
            String name = graph.spaceGraphName();
            server.getServerGremlinExecutor().getGraphManager().putGraph(name, graph);
            server.injectAuthGraph();
            Graph registered = server.getServerGremlinExecutor().getGraphManager().getGraph(name);
            Assert.assertTrue(registered instanceof HugeGraphAuthProxy);
            // AllowAll skips validateUser(), which normally prepares this user's audit limiter.
            // Verify the fixture's existing data under its owner before dispatching session work.
            assertVertexVisible(graph, "v1", true);
            HugeGraphAuthProxy.resetContext();
            TaskManager.resetContext();
            server.start().get(20, TimeUnit.SECONDS);
            Cluster cluster = Cluster.build("127.0.0.1").port(port).create();
            try {
                String sessionId = UUID.randomUUID().toString();
                Client session = cluster.connect(sessionId);
                RequestOptions aliases = RequestOptions.build().addAlias("g", "__g_" + name).create();
                try {
                    session.submit("g.addV('person').property(T.id, 'session-commit')" +
                                   ".property('name', 'session').iterate(); 1", aliases)
                           .all().get(10, TimeUnit.SECONDS);
                    assertVertexVisible(graph, "session-commit", false);
                    sessionTransaction(session, sessionId, "__g_" + name, GraphOp.TX_COMMIT);
                    assertVertexVisible(graph, "session-commit", true);
                    session.submit("g.addV('person').property(T.id, 'session-rollback')" +
                                   ".property('name', 'session').iterate(); 1", aliases)
                           .all().get(10, TimeUnit.SECONDS);
                    sessionTransaction(session, sessionId, "__g_" + name, GraphOp.TX_ROLLBACK);
                    assertVertexVisible(graph, "session-rollback", false);
                    session.submit("g.addV('person').property(T.id, 'session-script-commit')" +
                                   ".property('name', 'session').iterate(); 1", aliases)
                           .all().get(10, TimeUnit.SECONDS);
                    assertVertexVisible(graph, "session-script-commit", false);
                    session.submit("g.tx().commit()").all().get(10, TimeUnit.SECONDS);
                    assertVertexVisible(graph, "session-script-commit", true);
                    session.submit("g.addV('person').property(T.id, 'session-script-rollback')" +
                                   ".property('name', 'session').iterate(); 1", aliases)
                           .all().get(10, TimeUnit.SECONDS);
                    session.submit("g.tx().rollback(); 1").all().get(10, TimeUnit.SECONDS);
                    assertVertexVisible(graph, "session-script-rollback", false);
                    Assert.assertThrows(ExecutionException.class,
                            () -> session.submit("g.tx()").all().get(10, TimeUnit.SECONDS));
                    Assert.assertThrows(ExecutionException.class,
                            () -> session.submit("savedTx = g.tx(); 1").all().get(10, TimeUnit.SECONDS));
                    hub.notify(Events.GRAPH_DROP, graph).get(10, TimeUnit.SECONDS);
                    Assert.assertFalse(server.getServerGremlinExecutor().getGraphManager()
                            .getGraphNames().contains(name));
                    ExecutionException removed = Assert.assertThrows(ExecutionException.class,
                            () -> session.submit("g.V().count().next()").all().get(10, TimeUnit.SECONDS));
                    Assert.assertTrue(removed.getCause().getMessage(),
                                      removed.getCause().getMessage().contains("SCRIPT_SESSION_ALIAS_UNAVAILABLE"));
                } finally {
                    session.close();
                }
            } finally {
                cluster.close();
            }
        } finally {
            server.stop().get(20, TimeUnit.SECONDS);
        }
    }

    private static void assertVertexVisible(HugeGraph graph, String id, boolean visible) {
        HugeGraphAuthProxy.resetContext();
        AuthContext.setContext(HugeAuthenticator.User.ANONYMOUS.toJson());
        try {
            graph.tx().rollback();
            java.util.Iterator<?> vertices = graph.vertices(id);
            try {
                Assert.assertEquals(id, visible, vertices.hasNext());
            } finally {
                org.apache.tinkerpop.gremlin.structure.util.CloseableIterator.closeIterator(vertices);
            }
        } finally {
            try {
                graph.tx().rollback();
            } finally {
                HugeGraphAuthProxy.resetContext();
            }
        }
    }

    private static void sessionTransaction(Client session, String id, String alias, GraphOp operation)
            throws Exception {
        RequestMessage request = RequestMessage.build("bytecode").processor("session")
                .addArg("session", id).addArg("gremlin", operation.getBytecode())
                .addArg("aliases", Map.of("g", alias)).create();
        session.submitAsync(request).get(10, TimeUnit.SECONDS).all().get(10, TimeUnit.SECONDS);
    }

    private static final class TestJob extends GremlinJob {
        TestJob(HugeGraph graph, String source) {
            HugeTask<Object> task = new HugeTask<>(IdGenerator.of(1L), null, this);
            task.input(JsonUtil.toJson(Map.of("gremlin", source, "bindings", Map.of(),
                                             "language", "gremlin-groovy", "aliases", Map.of())));
            this.graph(graph);
            this.task(task);
        }
    }
}
