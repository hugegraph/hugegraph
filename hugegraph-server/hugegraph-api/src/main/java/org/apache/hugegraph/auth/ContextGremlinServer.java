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

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;

import org.apache.hugegraph.HugeException;
import org.apache.hugegraph.HugeGraph;
import org.apache.hugegraph.auth.HugeGraphAuthProxy.Context;
import org.apache.hugegraph.auth.HugeGraphAuthProxy.ContextThreadPoolExecutor;
import org.apache.hugegraph.config.CoreOptions;
import org.apache.hugegraph.event.EventHub;
import org.apache.hugegraph.security.script.PolicyScriptEngines;
import org.apache.hugegraph.security.script.ScriptPolicyMonitor;
import org.apache.hugegraph.security.script.ScriptPolicyRuntime;
import org.apache.hugegraph.testutil.Whitebox;
import org.apache.hugegraph.util.Events;
import org.apache.hugegraph.util.Log;
import org.apache.tinkerpop.gremlin.groovy.engine.GremlinExecutor;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.server.GraphManager;
import org.apache.tinkerpop.gremlin.server.GremlinServer;
import org.apache.tinkerpop.gremlin.server.Settings;
import org.apache.tinkerpop.gremlin.server.util.ThreadFactoryUtil;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.slf4j.Logger;

/**
 * GremlinServer with custom ServerGremlinExecutor, which can pass Context
 */
public class ContextGremlinServer extends GremlinServer {

    private static final Logger LOG = Log.logger(ContextGremlinServer.class);

    private static final String G_PREFIX = "__g_";

    private final EventHub eventHub;
    private PolicyGremlinScriptEngineManager policyManager;
    private boolean authGraphs;

    static {
        HugeGraphAuthProxy.setContext(Context.admin());
    }

    public ContextGremlinServer(final Settings settings, EventHub eventHub) {
        /*
         * pass custom Executor https://github.com/apache/tinkerpop/pull/813
         */
        super(policySettings(settings), newGremlinExecutorService(settings));
        if (ScriptPolicyRuntime.enabled()) {
            GremlinExecutor executor = this.getServerGremlinExecutor().getGremlinExecutor();
            try {
                this.policyManager = new PolicyGremlinScriptEngineManager(
                        executor.getScriptEngineManager().getBindings());
                Whitebox.setInternalState(executor, "gremlinScriptEngineManager", this.policyManager);
            } catch (Exception e) {
                super.stop().join();
                throw new HugeException("Failed to initialize script policy", e);
            }
            LOG.info("Script security mode={}, policy={}, securityManager={}",
                     ScriptPolicyRuntime.mode().configValue(),
                     ScriptPolicyMonitor.VERSION,
                     System.getSecurityManager() == null ? "none" :
                     System.getSecurityManager().getClass().getName());
        }
        this.eventHub = eventHub;
        this.listenChanges();
    }

    private void listenChanges() {
        this.eventHub.listen(Events.GRAPH_CREATE, event -> {
            LOG.debug("GremlinServer accepts event '{}'", event.name());
            event.checkArgs(HugeGraph.class);
            HugeGraph graph = (HugeGraph) event.args()[0];
            this.injectGraph(graph);
            return null;
        });
        this.eventHub.listen(Events.GRAPH_DROP, event -> {
            LOG.debug("GremlinServer accepts event '{}'", event.name());
            event.checkArgs(HugeGraph.class);
            HugeGraph graph = (HugeGraph) event.args()[0];
            this.removeGraph(graph);
            return null;
        });
    }

    private void unlistenChanges() {
        if (this.eventHub == null) {
            return;
        }
        this.eventHub.unlisten(Events.GRAPH_CREATE);
        this.eventHub.unlisten(Events.GRAPH_DROP);
    }

    @Override
    public synchronized CompletableFuture<Void> stop() {
        try {
            return super.stop().whenComplete((value, error) -> {
                if (this.policyManager != null) {
                    this.policyManager.close();
                }
                PolicyScriptEngines.close();
            });
        } finally {
            this.unlistenChanges();
        }
    }

    public void injectAuthGraph() {
        this.authGraphs = true;
        GraphManager manager = this.getServerGremlinExecutor()
                                   .getGraphManager();
        for (String name : manager.getGraphNames()) {
            Graph graph = manager.getGraph(name);
            graph = new HugeGraphAuthProxy((HugeGraph) graph);
            manager.putGraph(name, graph);
            if (this.policyManager != null) {
                this.policyManager.put(name, graph);
                manager.putTraversalSource(G_PREFIX + name, graph.traversal());
            }
        }
    }

    public void injectTraversalSource() {
        GraphManager manager = this.getServerGremlinExecutor()
                                   .getGraphManager();
        for (String graph : manager.getGraphNames()) {
            GraphTraversalSource g = manager.getGraph(graph).traversal();
            String gName = G_PREFIX + graph;
            if (manager.getTraversalSource(gName) != null) {
                throw new HugeException(
                        "Found existing name '%s' in global bindings, " +
                        "it may lead to gremlin query error.", gName);
            }
            // Add a traversal source for all graphs with customed rule.
            manager.putTraversalSource(gName, g);
        }
    }

    private synchronized void injectGraph(HugeGraph graph) {
        String name = graph.spaceGraphName();
        GraphManager manager = this.getServerGremlinExecutor()
                                   .getGraphManager();
        GremlinExecutor executor = this.getServerGremlinExecutor()
                                       .getGremlinExecutor();

        if (this.policyManager != null && this.authGraphs && !(graph instanceof HugeGraphAuthProxy)) {
            graph = new HugeGraphAuthProxy(graph);
        }
        manager.putGraph(name, graph);

        GraphTraversalSource g = manager.getGraph(name).traversal();
        manager.putTraversalSource(G_PREFIX + name, g);

        Whitebox.invoke(executor, "globalBindings",
                        new Class<?>[]{String.class, Object.class},
                        "put", name, graph);
    }

    private synchronized void removeGraph(HugeGraph graph) {
        String name = graph.spaceGraphName();
        GraphManager manager = this.getServerGremlinExecutor()
                                   .getGraphManager();
        GremlinExecutor executor = this.getServerGremlinExecutor()
                                       .getGremlinExecutor();
        try {
            Graph registered = manager.getGraph(name);
            if (registered != graph && !sameOriginGraph(registered, graph)) {
                return;
            }
            if (manager.getTraversalSource(G_PREFIX + name) != null) {
                manager.removeTraversalSource(G_PREFIX + name);
            }
            Whitebox.invoke(executor, "globalBindings",
                            new Class<?>[]{Object.class},
                            "remove", name);
            if (manager.getGraph(name) != null) {
                manager.removeGraph(name);
            }
        } catch (Exception e) {
            throw new HugeException("Failed to remove graph '%s' from " +
                                    "gremlin server context", e, name);
        }
    }

    private static boolean sameOriginGraph(Graph registered, HugeGraph graph) {
        return registered instanceof HugeGraphAuthProxy &&
               ((HugeGraphAuthProxy) registered).originGraph() == graph;
    }

    private static Settings policySettings(Settings settings) {
        if (!ScriptPolicyRuntime.enabled()) {
            return settings;
        }
        ScriptPolicyRuntime.validateSecurityManager();
        if (!"org.apache.tinkerpop.gremlin.server.channel.WsAndHttpChannelizer".equals(settings.channelizer)) {
            throw new IllegalArgumentException("Script policy requires WsAndHttpChannelizer");
        }
        if (!settings.scriptEngines.keySet().equals(Set.of("gremlin-groovy"))) {
            throw new IllegalArgumentException("Script policy requires only gremlin-groovy");
        }
        PolicyScriptEngines.initializeServer();
        settings.channelizer = PolicyWsAndHttpChannelizer.class.getName();
        if (settings.evaluationTimeout <= 0 || settings.evaluationTimeout > 30000L) {
            settings.evaluationTimeout = 30000L;
        }
        return settings;
    }

    static ExecutorService newGremlinExecutorService(Settings settings) {
        if (settings.gremlinPool == 0) {
            settings.gremlinPool = CoreOptions.CPUS;
        }
        int size = settings.gremlinPool;
        ThreadFactory factory = ThreadFactoryUtil.create("exec-%d");
        return new ContextThreadPoolExecutor(size, size, factory);
    }
}
