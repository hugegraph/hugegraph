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

package org.apache.hugegraph.benchmark;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.apache.commons.configuration2.BaseConfiguration;
import org.apache.commons.io.FileUtils;
import org.apache.hugegraph.HugeFactory;
import org.apache.hugegraph.HugeGraph;
import org.apache.hugegraph.auth.ContextGremlinServer;
import org.apache.hugegraph.config.HugeConfig;
import org.apache.hugegraph.dist.RegisterUtil;
import org.apache.hugegraph.event.EventHub;
import org.apache.hugegraph.security.HugeSecurityManager;
import org.apache.hugegraph.security.script.ScriptPolicyRuntime;
import org.apache.tinkerpop.gremlin.driver.Client;
import org.apache.tinkerpop.gremlin.driver.Cluster;
import org.apache.tinkerpop.gremlin.driver.RequestOptions;
import org.apache.tinkerpop.gremlin.server.Settings;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;

/** External, serial loopback network experiment; not part of automated acceptance. */
public final class PolicyNetworkBenchmark {
    public static void main(String[] args) throws Exception {
        String variant = args[0];
        boolean smoke = args.length > 1 && args[1].equals("smoke");
        boolean policy = variant.equals("C") || variant.equals("E");
        boolean manager = variant.equals("B") || variant.equals("C");
        if (policy != ScriptPolicyRuntime.enabled()) {
            throw new IllegalStateException("Variant and mode disagree");
        }
        RegisterUtil.registerBackends();
        Path directory = Files.createTempDirectory("hg-policy-network-bench-");
        BaseConfiguration config = new BaseConfiguration();
        config.setProperty("backend", "rocksdb");
        config.setProperty("serializer", "binary");
        config.setProperty("store", "policy_benchmark");
        config.setProperty("rocksdb.data_path", directory.resolve("data").toString());
        config.setProperty("rocksdb.wal_path", directory.resolve("wal").toString());
        config.setProperty("query.ignore_invalid_data", false);
        HugeGraph graph = HugeFactory.open(new HugeConfig(config));
        ContextGremlinServer server = null;
        Cluster cluster = null;
        try {
            graph.initBackend();
            graph.serverStarted(null);
            graph.schema().propertyKey("group").asInt().create();
            graph.schema().vertexLabel("bench_node").properties("group").useCustomizeStringId().create();
            graph.schema().edgeLabel("bench_link").sourceLabel("bench_node").targetLabel("bench_node").create();
            for (int i = 1; i <= 5000; i++) {
                graph.addVertex(T.id, Integer.toString(i), T.label, "bench_node", "group", i % 16);
                if (i % 500 == 0) graph.tx().commit();
            }
            for (int i = 1; i <= 5000; i++) {
                Vertex v = graph.vertices(Integer.toString(i)).next();
                v.addEdge("bench_link", graph.vertices(Integer.toString(i % 5000 + 1)).next());
                v.addEdge("bench_link", graph.vertices(Integer.toString((i + 10) % 5000 + 1)).next());
                if (i % 500 == 0) graph.tx().commit();
            }
            graph.tx().commit();
            // Seed and open backend state outside measured requests for every variant.
            if (graph.traversal().V().count().next() != 5000L ||
                graph.traversal().E().count().next() != 10000L) {
                throw new IllegalStateException("Dataset mismatch");
            }
            graph.tx().rollback();
            Settings settings = new Settings();
            settings.host = "127.0.0.1";
            try (ServerSocket socket = new ServerSocket(0)) { settings.port = socket.getLocalPort(); }
            settings.gremlinPool = 1;
            settings.threadPoolWorker = 1;
            settings.threadPoolBoss = 1;
            settings.channelizer = "org.apache.tinkerpop.gremlin.server.channel.WsAndHttpChannelizer";
            settings.scriptEngines.clear();
            settings.scriptEngines.put("gremlin-groovy", new Settings.ScriptEngineSettings());
            server = new ContextGremlinServer(settings, new EventHub("network-benchmark"));
            server.getServerGremlinExecutor().getGraphManager().putGraph("bench", graph);
            server.injectTraversalSource();
            server.start().get(30, TimeUnit.SECONDS);
            cluster = Cluster.build("127.0.0.1").port(settings.port).create();
            // Construct server and driver during trusted startup, as in the deployed bootstrap.
            Client sessionless = cluster.connect();
            Client session = cluster.connect("benchmark-session");
            sessionless.init();
            session.init();
            if (manager) System.setSecurityManager(new HugeSecurityManager());
            if (manager != (System.getSecurityManager() != null)) {
                throw new IllegalStateException("SecurityManager mismatch");
            }
            RequestOptions aliases = RequestOptions.build().addAlias("g", "__g_bench").create();
            String[] names = {"point-lookup", "two-hop", "vertex-count", "closure-query"};
            String[] scripts = {"g.V('1').values('group')", "g.V('1').out('bench_link').out('bench_link').count()", "g.V().count()", "g.V('1').values('group').map { ((int) it.get()) + 1 }.toList()"};
            long[] expected = {1, 4, 5000, 2};
            for (int clientIndex = 0; clientIndex < 2; clientIndex++) {
                Client client = clientIndex == 0 ? sessionless : session;
                String kind = clientIndex == 0 ? "sessionless" : "session";
                for (int q = 0; q < scripts.length; q++) {
                    for (int i = 0; i < (smoke ? 10 : 500); i++) request(client, aliases, scripts[q], expected[q]);
                    long[] samples = new long[smoke ? 20 : 1000];
                    long all = System.nanoTime();
                    for (int i = 0; i < samples.length; i++) {
                        long begin = System.nanoTime();
                        request(client, aliases, scripts[q], expected[q]);
                        samples[i] = System.nanoTime() - begin;
                    }
                    report(variant, kind + "-" + names[q], samples, System.nanoTime() - all);
                }
            }
            System.out.println("NETWORK_BENCHMARK_COMPLETE," + variant + ",manager=" +
                               (manager ? "installed" : "none") + ",vertices=5000,edges=10000");
        } finally {
            if (cluster != null) cluster.close();
            if (server != null) server.stop().get(30, TimeUnit.SECONDS);
            graph.close();
            HugeFactory.shutdown(10L);
            FileUtils.deleteDirectory(directory.toFile());
        }
    }
    private static void request(Client client, RequestOptions aliases, String script, long expected) throws Exception {
        var result = client.submit(script, aliases).all().get(15, TimeUnit.SECONDS);
        if (result.size() != 1 || result.get(0).getLong() != expected) {
            throw new IllegalStateException("Result mismatch " + script + " " + result);
        }
    }
    private static void report(String variant, String name, long[] samples, long elapsed) {
        Arrays.sort(samples);
        System.out.printf(Locale.ROOT, "BENCHMARK,%s,%s,%d,%.3f,%.3f,%.3f,%.3f%n", variant, name,
                          samples.length, samples[samples.length / 2] / 1000.0,
                          samples[(int) (samples.length * .95)] / 1000.0,
                          samples[(int) (samples.length * .99)] / 1000.0,
                          samples.length * 1000000000.0 / elapsed);
    }
}
