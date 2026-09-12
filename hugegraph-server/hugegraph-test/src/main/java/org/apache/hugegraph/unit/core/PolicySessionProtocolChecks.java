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

import java.util.List;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.apache.tinkerpop.gremlin.driver.Client;
import org.apache.tinkerpop.gremlin.driver.Cluster;
import org.apache.tinkerpop.gremlin.driver.RequestOptions;
import org.apache.tinkerpop.gremlin.driver.Result;
import org.apache.tinkerpop.gremlin.driver.exception.ResponseException;
import org.apache.tinkerpop.gremlin.util.message.ResponseStatusCode;
import org.apache.tinkerpop.gremlin.util.message.ResponseMessage;
import org.apache.tinkerpop.gremlin.util.ser.GraphBinaryMessageSerializerV1;
import org.apache.tinkerpop.gremlin.util.ser.SerializationException;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import org.junit.Assert;

/** Real WebSocket checks reused by the isolated combined/policy-only server probes. */
public final class PolicySessionProtocolChecks {

    private static final String SERIALIZATION_FAILURE = "__policy_test_serialization_failure__";

    public static final class FailingResultSerializer extends GraphBinaryMessageSerializerV1 {

        @Override
        public ByteBuf serializeResponseAsBinary(ResponseMessage response, ByteBufAllocator allocator)
                throws SerializationException {
            Object data = response.getResult().getData();
            if (data instanceof Collection && ((Collection<?>) data).contains(SERIALIZATION_FAILURE)) {
                throw new SerializationException("test result serialization failure");
            }
            return super.serializeResponseAsBinary(response, allocator);
        }
    }

    private PolicySessionProtocolChecks() {
    }

    public static void verify(Cluster cluster) throws Exception {
        String id = "policy-stream-" + UUID.randomUUID();
        Client session = cluster.connect(id);
        try {
            Assert.assertEquals(List.of(1), values(session, "counter = 0; values = []; 1"));
            Assert.assertEquals(List.of(1, 2), values(session, "[1, 2].iterator()"));
            Assert.assertEquals(List.of(1, 2, 3), values(session,
                    "g.inject(1, 2, 3).map { counter += 1; values.add(it.get()); it.get() }"));
            Assert.assertEquals(List.of(3), values(session, "counter"));
            Assert.assertEquals(List.of(1, 2, 3), values(session, "values"));
            Assert.assertEquals(List.of(3, 4), values(session, "g.inject(1, 2).map { counter++ }"));
            Assert.assertEquals(List.of(5), values(session, "counter"));
            Assert.assertEquals(List.of(6), values(session, "g.inject(1).map { ++counter }"));
            Assert.assertEquals(List.of(6), values(session, "counter"));

            ExecutionException compilation = Assert.assertThrows(ExecutionException.class,
                    () -> session.submit("System.getProperty('java.version')", Map.of("counter", 99)).all()
                            .get(10, TimeUnit.SECONDS));
            Assert.assertTrue(compilation.getCause() instanceof ResponseException);
            Assert.assertEquals(List.of(6), values(session, "counter"));
            Assert.assertThrows(ExecutionException.class,
                    () -> session.submit("int zero = 0; 1 / zero", Map.of("counter", 88)).all()
                            .get(10, TimeUnit.SECONDS));
            Assert.assertEquals(List.of(6), values(session, "counter"));
            RequestOptions originalAlias = RequestOptions.build().addAlias("chosen", "g").create();
            Assert.assertEquals(1, session.submit("chosen.inject(1).cap('marker').next()", originalAlias)
                    .all().get(10, TimeUnit.SECONDS).get(0).getInt());
            RequestOptions failedAlias = RequestOptions.build().addAlias("chosen", "gOther").create();
            Assert.assertThrows(ExecutionException.class,
                    () -> session.submit("System.getProperty('java.version')", failedAlias).all()
                            .get(10, TimeUnit.SECONDS));
            Assert.assertEquals(List.of(1), values(session, "chosen.inject(1).cap('marker').next()"));
            Assert.assertThrows(ExecutionException.class,
                    () -> session.submit("int zero = 0; 1 / zero", failedAlias).all().get(10, TimeUnit.SECONDS));
            Assert.assertEquals(List.of(1), values(session, "chosen.inject(1).cap('marker').next()"));

            ExecutionException serialization = Assert.assertThrows(ExecutionException.class,
                    () -> values(session, "counter += 10; values.add(99); '" + SERIALIZATION_FAILURE + "'"));
            Assert.assertTrue(serialization.getCause() instanceof ResponseException);
            Assert.assertEquals(ResponseStatusCode.SERVER_ERROR_SERIALIZATION,
                    ((ResponseException) serialization.getCause()).getResponseStatusCode());
            Assert.assertEquals(List.of(6), values(session, "counter"));
            Assert.assertEquals(List.of(1, 2, 3), values(session, "values"));

            ExecutionException invalid = Assert.assertThrows(ExecutionException.class,
                    () -> values(session, "g.inject(1).map { values.add(g); it.get() }"));
            Assert.assertTrue(invalid.getCause() instanceof ResponseException);
            Assert.assertEquals(List.of(1, 2, 3), values(session, "values"));
            ExecutionException failed = Assert.assertThrows(ExecutionException.class,
                    () -> values(session, "g.inject(1).map { counter += 1; values.add(9); int zero = 0; 1 / zero }"));
            Assert.assertTrue(failed.getCause() instanceof ResponseException);
            Assert.assertEquals(List.of(6), values(session, "counter"));
            Assert.assertEquals(List.of(1, 2, 3), values(session, "values"));

            ExecutionException timedOut = Assert.assertThrows(ExecutionException.class,
                    () -> session.submit("g.inject(1).map { while (true) { counter++ }; 1 }",
                            RequestOptions.build().timeout(200).addParameter("counter", 99)
                                    .addParameter("transientValue", 7).addAlias("chosen", "gOther").create())
                            .all().get(10, TimeUnit.SECONDS));
            Assert.assertTrue(timedOut.getCause() instanceof ResponseException);
            Assert.assertEquals(ResponseStatusCode.SERVER_ERROR_TIMEOUT,
                    ((ResponseException) timedOut.getCause()).getResponseStatusCode());
            Assert.assertEquals(List.of(6), values(session, "counter"));
            Assert.assertEquals(List.of(1), values(session, "chosen.inject(1).cap('marker').next()"));
            Assert.assertThrows(ExecutionException.class, () -> values(session, "transientValue"));
            Client differentChannel = cluster.connect(id);
            try {
                Assert.assertThrows(ExecutionException.class, () -> values(differentChannel, "counter"));
            } finally {
                differentChannel.close();
            }
            Assert.assertEquals(List.of(6), values(session, "counter"));
        } finally {
            session.close();
        }
    }

    private static List<Object> values(Client client, String script) throws Exception {
        return client.submit(script, RequestOptions.build().batchSize(1).create()).all()
                .get(10, TimeUnit.SECONDS).stream().map(Result::getObject).toList();
    }
}
