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

import org.apache.hugegraph.auth.ScriptRequestGuard;
import org.apache.hugegraph.security.script.ScriptBytecodePolicy;
import org.apache.tinkerpop.gremlin.process.traversal.Bytecode;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.util.function.Lambda;
import org.apache.tinkerpop.gremlin.util.message.RequestMessage;
import org.apache.tinkerpop.gremlin.util.message.ResponseMessage;
import org.apache.tinkerpop.gremlin.util.message.ResponseStatusCode;
import org.junit.Assert;
import org.junit.Test;

import io.netty.channel.embedded.EmbeddedChannel;

public class ScriptRequestGuardTest {

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
    public void testRejectsSessionAndReservedBindings() {
        EmbeddedChannel channel = new EmbeddedChannel(new ScriptRequestGuard(1000L));
        try {
            for (RequestMessage request : new RequestMessage[]{
                    RequestMessage.build("eval").processor("session").addArg("gremlin", "1+1").create(),
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
