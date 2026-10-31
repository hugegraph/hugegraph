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

import java.util.Map;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.apache.hugegraph.auth.ScriptRequestGuard;
import org.apache.hugegraph.security.script.ScriptBytecodePolicy;
import org.apache.tinkerpop.gremlin.process.traversal.Bytecode;
import org.apache.tinkerpop.gremlin.process.traversal.GraphOp;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.structure.util.detached.DetachedFactory;
import org.apache.tinkerpop.gremlin.structure.util.detached.DetachedProperty;
import org.apache.tinkerpop.gremlin.structure.util.detached.DetachedVertex;
import org.apache.tinkerpop.gremlin.structure.util.detached.DetachedVertexProperty;
import org.apache.tinkerpop.gremlin.structure.util.reference.ReferenceFactory;
import org.apache.tinkerpop.gremlin.structure.util.reference.ReferenceVertex;
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerFactory;
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph;
import org.apache.tinkerpop.gremlin.util.function.Lambda;
import org.apache.tinkerpop.gremlin.util.message.RequestMessage;
import org.apache.tinkerpop.gremlin.util.message.ResponseMessage;
import org.apache.tinkerpop.gremlin.util.message.ResponseStatusCode;
import org.junit.Assert;
import org.junit.Test;

import io.netty.channel.embedded.EmbeddedChannel;

public class ScriptRequestGuardTest {

    @Test
    public void testPreservesCypherProcessor() {
        EmbeddedChannel channel = new EmbeddedChannel(new ScriptRequestGuard(1000L));
        try {
            RequestMessage request = RequestMessage.build("eval").processor("cypher")
                    .addArg("gremlin", "RETURN 1 AS value").create();
            Assert.assertTrue(channel.writeInbound(request));
            RequestMessage forwarded = channel.readInbound();
            Assert.assertEquals("cypher", forwarded.getProcessor());
            Assert.assertEquals(request.getRequestId(), forwarded.getRequestId());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void testPreservesRequestAndCapsTimeout() {
        EmbeddedChannel channel = new EmbeddedChannel(new ScriptRequestGuard(1000L));
        try {
            RequestMessage request = RequestMessage.build("eval")
                    .addArg("gremlin", "x + 1").addArg("bindings", Map.of("x", 1))
                    .addArg("evaluationTimeout", 0L).create();
            Assert.assertTrue(channel.writeInbound(request));
            RequestMessage forwarded = channel.readInbound();
            Assert.assertEquals(request.getRequestId(), forwarded.getRequestId());
            Assert.assertEquals(1000L, forwarded.getArgs().get("evaluationTimeout"));
            Assert.assertEquals(request.getArgs().get("bindings"), forwarded.getArgs().get("bindings"));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void testRejectsMalformedSessionAndReservedBindings() {
        EmbeddedChannel channel = new EmbeddedChannel(new ScriptRequestGuard(1000L));
        try {
            for (RequestMessage request : new RequestMessage[]{
                    RequestMessage.build("eval").processor("session").addArg("gremlin", "1+1").create(),
                    RequestMessage.build("eval").processor("policy-session")
                                  .addArg("session", "s1").addArg("gremlin", "1+1").create(),
                    RequestMessage.build("bytecode").processor("traversal")
                                  .addArg("gremlin", "{\"step\":[[\"io\",\"/tmp/private\"]]}").create(),
                    RequestMessage.build("eval").addArg("gremlin", "1+1")
                                  .addArg("bindings", Map.of("g", "override")).create()}) {
                Assert.assertFalse(channel.writeInbound(request));
                ResponseMessage response = channel.readOutbound();
                Assert.assertEquals(request.getRequestId(), response.getRequestId());
                Assert.assertEquals(ResponseStatusCode.REQUEST_ERROR_INVALID_REQUEST_ARGUMENTS,
                                    response.getStatus().getCode());
            }
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void testRejectsInvalidClientDataStructures() {
        List<Object> cyclic = new ArrayList<>();
        cyclic.add(cyclic);
        Object deep = "leaf";
        for (int i = 0; i < 18; i++) {
            deep = List.of(deep);
        }
        Map<String, Object> many = new HashMap<>();
        for (int i = 0; i < 257; i++) {
            many.put("x" + i, i);
        }
        EmbeddedChannel channel = new EmbeddedChannel(new ScriptRequestGuard(1000L));
        try {
            for (Map<?, ?> bindings : List.of(Map.of(1, "invalid key"), Map.of("graph", 1),
                    Map.of("this", 1), Map.of("x", cyclic), Map.of("x", deep),
                    Map.of("x", new byte[4097]), Map.of("x", "a".repeat(32769)),
                    Map.of("x", new java.util.concurrent.ConcurrentHashMap<>()), many)) {
                RequestMessage request = RequestMessage.build("eval").addArg("gremlin", "1")
                        .addArg("bindings", bindings).create();
                Assert.assertFalse(channel.writeInbound(request));
                ResponseMessage denied = channel.readOutbound();
                Assert.assertEquals(ResponseStatusCode.REQUEST_ERROR_INVALID_REQUEST_ARGUMENTS,
                                    denied.getStatus().getCode());
            }
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void testRoutesSessionsAndPreservesTransactionOperations() {
        EmbeddedChannel channel = new EmbeddedChannel(new ScriptRequestGuard(1000L));
        try {
            for (RequestMessage request : new RequestMessage[]{
                    RequestMessage.build("eval").processor("session").addArg("session", "s1")
                                  .addArg("gremlin", "x = 1; x").create(),
                    RequestMessage.build("bytecode").processor("session").addArg("session", "s1")
                                  .addArg("gremlin", GraphOp.TX_COMMIT.getBytecode()).create(),
                    RequestMessage.build("bytecode").processor("session").addArg("session", "s1")
                                  .addArg("gremlin", GraphOp.TX_ROLLBACK.getBytecode()).create(),
                    RequestMessage.build("close").processor("session").addArg("session", "s1").create()}) {
                Assert.assertTrue(channel.writeInbound(request));
                RequestMessage forwarded = channel.readInbound();
                Assert.assertEquals("policy-session", forwarded.getProcessor());
                Assert.assertEquals(request.getRequestId(), forwarded.getRequestId());
                Assert.assertEquals(request.getArgs().get("session"), forwarded.getArgs().get("session"));
            }
            Assert.assertFalse(channel.writeInbound(RequestMessage.build("bytecode").processor("traversal")
                    .addArg("gremlin", GraphOp.TX_COMMIT.getBytecode()).create()));
            ResponseMessage denied = channel.readOutbound();
            Assert.assertEquals(ResponseStatusCode.REQUEST_ERROR_INVALID_REQUEST_ARGUMENTS,
                                denied.getStatus().getCode());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void testBytecodeBudgetCountsStepsWithoutArguments() {
        Bytecode bytecode = new Bytecode();
        for (int i = 0; i < 4095; i++) {
            bytecode.addStep("identity");
        }
        ScriptBytecodePolicy.validate(bytecode);
        bytecode.addStep("identity");
        IllegalArgumentException error = Assert.assertThrows(IllegalArgumentException.class,
                () -> ScriptBytecodePolicy.validate(bytecode));
        Assert.assertEquals("SCRIPT_BYTECODE_LIMIT", error.getMessage());
    }

    @Test
    public void testBytecodeScalarChecksKeepSizeAndTypeLimits() {
        Bytecode safe = new Bytecode();
        safe.addStep("inject", "x".repeat(32768), 1, true);
        ScriptBytecodePolicy.validate(safe);
        for (Object value : java.util.List.of("x".repeat(32769), P.eq("x".repeat(32769)),
                                              new java.util.concurrent.atomic.AtomicInteger(1))) {
            Bytecode denied = new Bytecode();
            denied.addStep("inject", value);
            Assert.assertThrows(IllegalArgumentException.class, () -> ScriptBytecodePolicy.validate(denied));
        }
    }

    @Test
    public void testNativeElementArgumentsReachTraversalProcessor() throws Exception {
        try (TinkerGraph graph = TinkerFactory.createModern()) {
            org.apache.tinkerpop.gremlin.structure.Vertex vertex = graph.vertices(1).next();
            org.apache.tinkerpop.gremlin.structure.Edge edge = graph.edges().next();
            for (Object value : List.of(ReferenceFactory.detach(vertex), DetachedFactory.detach(vertex, true),
                    ReferenceFactory.detach(edge), DetachedFactory.detach(edge, true),
                    ReferenceFactory.detach(vertex.property("name")),
                    DetachedFactory.detach(vertex.property("name"), true), ReferenceFactory.detach(edge.property("weight")),
                    DetachedFactory.detach(edge.property("weight")))) {
                Bytecode code = new Bytecode();
                code.addStep("inject", value);
                EmbeddedChannel channel = new EmbeddedChannel(new ScriptRequestGuard(1000L));
                try {
                    RequestMessage request = RequestMessage.build("bytecode").processor("traversal")
                            .addArg("gremlin", code).create();
                    Assert.assertTrue(value.getClass().getName(), channel.writeInbound(request));
                    RequestMessage forwarded = channel.readInbound();
                    Assert.assertEquals(request.getRequestId(), forwarded.getRequestId());
                    Assert.assertSame(code, forwarded.getArgs().get("gremlin"));
                } finally {
                    channel.finishAndReleaseAll();
                }
            }
            Bytecode addEdge = new Bytecode();
            addEdge.addStep("addE", "knows");
            addEdge.addStep("from", ReferenceFactory.detach(vertex));
            addEdge.addStep("to", DetachedFactory.detach(graph.vertices(2).next(), true));
            ScriptBytecodePolicy.validate(addEdge);
        }
    }

    @Test
    public void testNativeElementPayloadsKeepDataAndStructureChecks() {
        Object unsupported = new java.util.concurrent.atomic.AtomicInteger(1);
        List<Object> cyclic = new ArrayList<>();
        cyclic.add(cyclic);
        for (Object element : List.of(
                new ReferenceVertex(unsupported, "person"),
                new DetachedProperty<>("bad", unsupported),
                ReferenceFactory.detach(new DetachedProperty<>("bad", unsupported)),
                new DetachedVertexProperty<>(1, "bad", cyclic, Map.of()),
                new DetachedVertexProperty<>(1, "name", "ok", Map.of("meta", unsupported)),
                new DetachedVertex(1, "person", List.of(new DetachedVertexProperty<>(2, "bad", unsupported, Map.of()))),
                new DetachedProperty<>("bad", "x".repeat(32769)))) {
            Bytecode code = new Bytecode();
            code.addStep("inject", List.of(element));
            Assert.assertThrows(element.getClass().getName(), IllegalArgumentException.class,
                                () -> ScriptBytecodePolicy.validate(code));
        }
        java.util.concurrent.atomic.AtomicBoolean called = new java.util.concurrent.atomic.AtomicBoolean();
        Bytecode custom = new Bytecode();
        custom.addStep("inject", new ReferenceVertex(1, "person") {
            @Override
            public Object id() {
                called.set(true);
                return 1;
            }
        });
        Assert.assertThrows(IllegalArgumentException.class, () -> ScriptBytecodePolicy.validate(custom));
        Assert.assertFalse(called.get());
    }

    @Test
    public void testDataOnlyBytecodeSourceOptions() {
        Bytecode safe = new Bytecode();
        safe.addSource("withBulk", false);
        safe.addSource("withPath");
        safe.addSource("withSideEffect", "names", java.util.List.of("a", "b"));
        safe.addSource("withSack", 1);
        safe.addStep("inject", 2);
        ScriptBytecodePolicy.validate(safe);
        try (org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph graph =
                     org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph.open()) {
            org.apache.tinkerpop.gremlin.process.traversal.Traversal.Admin<?, ?> traversal =
                    org.apache.tinkerpop.gremlin.jsr223.JavaTranslator.of(graph.traversal()).translate(safe);
            Assert.assertEquals(2, traversal.next());
            traversal.close();
        } catch (Exception error) {
            throw new AssertionError(error);
        }
        Bytecode strategy = new Bytecode();
        strategy.addSource("withStrategies",
                org.apache.tinkerpop.gremlin.process.traversal.strategy.verification.ReadOnlyStrategy.instance());
        Assert.assertThrows(IllegalArgumentException.class, () -> ScriptBytecodePolicy.validate(strategy));
        Bytecode callback = new Bytecode();
        callback.addSource("withSack", Lambda.supplier("1"));
        Assert.assertThrows(IllegalArgumentException.class, () -> ScriptBytecodePolicy.validate(callback));
        Bytecode wrongType = new Bytecode();
        wrongType.addSource("withBulk", "false");
        Assert.assertThrows(IllegalArgumentException.class, () -> ScriptBytecodePolicy.validate(wrongType));
    }

    @Test
    public void testNativeBytecodeCannotUseIoOrLambda() {
        Bytecode safe = new Bytecode();
        safe.addStep("V");
        safe.addStep("has", "age", P.gt(18));
        safe.addStep("count");
        ScriptBytecodePolicy.validate(safe);
        Bytecode io = new Bytecode();
        io.addStep("io", "/tmp/private");
        Assert.assertThrows(IllegalArgumentException.class, () -> ScriptBytecodePolicy.validate(io));
        Bytecode lambda = new Bytecode();
        lambda.addStep("map", Lambda.function("it.get()"));
        Assert.assertThrows(IllegalArgumentException.class, () -> ScriptBytecodePolicy.validate(lambda));
        Bytecode callback = new Bytecode();
        callback.addStep("is", new P<>((a, b) -> true, 1));
        Assert.assertThrows(IllegalArgumentException.class, () -> ScriptBytecodePolicy.validate(callback));
    }
}
