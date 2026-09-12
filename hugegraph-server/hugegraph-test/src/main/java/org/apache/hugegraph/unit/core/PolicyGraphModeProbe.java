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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import javax.script.ScriptException;
import javax.script.SimpleBindings;

import org.apache.commons.configuration2.BaseConfiguration;
import org.apache.commons.io.FileUtils;
import org.apache.hugegraph.HugeFactory;
import org.apache.hugegraph.HugeGraph;
import org.apache.hugegraph.auth.HugeAuthenticator;
import org.apache.hugegraph.auth.HugeGraphAuthProxy;
import org.apache.hugegraph.auth.RolePermission;
import org.apache.hugegraph.backend.id.IdGenerator;
import org.apache.hugegraph.config.HugeConfig;
import org.apache.hugegraph.core.GraphManager;
import org.apache.hugegraph.dist.RegisterUtil;
import org.apache.hugegraph.job.GremlinJob;
import org.apache.hugegraph.security.script.PolicyScriptEngine;
import org.apache.hugegraph.security.script.PolicyScriptEngines;
import org.apache.hugegraph.security.script.ScriptExecutionProfile;
import org.apache.hugegraph.security.script.ScriptPolicyRuntime;
import org.apache.hugegraph.task.HugeTask;
import org.apache.hugegraph.task.TaskManager;
import org.apache.hugegraph.traversal.optimize.HugeScriptTraversal;
import org.apache.hugegraph.util.JsonUtil;
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
        HugeGraph graph = HugeFactory.open(new HugeConfig(config));
        try {
            graph.initBackend();
            GraphManager.prepareSchema(graph,
                    "schema.propertyKey('name').asText().ifNotExist().create(); " +
                    "schema.vertexLabel('person').properties('name').useCustomizeStringId().ifNotExist().create()");
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
            System.out.println("GRAPH_POLICY_VERIFIED " + ScriptPolicyRuntime.mode().configValue());
        } finally {
            PolicyScriptEngines.close();
            graph.close();
            HugeFactory.shutdown(10L);
            FileUtils.deleteDirectory(directory.toFile());
        }
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
