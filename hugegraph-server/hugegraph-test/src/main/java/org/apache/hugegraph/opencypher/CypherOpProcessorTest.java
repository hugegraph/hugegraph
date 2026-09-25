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

package org.apache.hugegraph.opencypher;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.apache.hugegraph.testutil.Assert;
import org.apache.tinkerpop.gremlin.server.op.AbstractEvalOpProcessor;
import org.apache.tinkerpop.gremlin.server.op.OpProcessorException;
import org.apache.tinkerpop.gremlin.util.Tokens;
import org.apache.tinkerpop.gremlin.util.message.RequestMessage;
import org.apache.tinkerpop.gremlin.util.message.ResponseStatusCode;
import org.junit.Test;

public class CypherOpProcessorTest {

    private final CypherOpProcessor processor = new CypherOpProcessor();

    @Test
    public void testAcceptCypherParameterNamesAndNullValues() throws Exception {
        Map<String, Object> bindings = new HashMap<>();
        bindings.put("value", null);
        bindings.put("id", 1);
        bindings.put("label", "person");
        Assert.assertFalse(this.processor.validateEvalMessage(request("RETURN $value", bindings)).isPresent());
        Assert.assertFalse(this.processor.validateEvalMessage(RequestMessage.build(Tokens.OPS_EVAL)
                .addArg(Tokens.ARGS_GREMLIN, "RETURN 1").create()).isPresent());
    }

    @Test
    public void testRejectInvalidQuery() {
        assertInvalid(request(1, Collections.emptyMap()));
        assertInvalid(request(" \n", Collections.emptyMap()));
    }

    @Test
    public void testRejectInvalidBindingShapeAndKeys() {
        assertInvalid(request("RETURN 1", Collections.emptyList()));
        assertInvalid(request("RETURN 1", Collections.singletonMap(1, "value")));
        assertInvalid(request("RETURN 1", Collections.singletonMap(null, "value")));
    }

    @Test
    public void testEnforceParameterCountBoundary() throws Exception {
        Map<String, Object> bindings = new HashMap<>();
        for (int i = 0; i < AbstractEvalOpProcessor.DEFAULT_MAX_PARAMETERS; i++) {
            bindings.put("parameter" + i, i);
        }
        Assert.assertFalse(this.processor.validateEvalMessage(request("RETURN 1", bindings)).isPresent());
        bindings.put("oneTooMany", 1);
        assertInvalid(request("RETURN 1", bindings));
    }

    private void assertInvalid(RequestMessage message) {
        OpProcessorException error = Assert.assertThrows(OpProcessorException.class,
                () -> this.processor.validateEvalMessage(message));
        Assert.assertEquals(ResponseStatusCode.REQUEST_ERROR_INVALID_REQUEST_ARGUMENTS,
                            error.getResponseMessage().getStatus().getCode());
    }

    private static RequestMessage request(Object query, Object bindings) {
        return RequestMessage.build(Tokens.OPS_EVAL)
                             .addArg(Tokens.ARGS_GREMLIN, query)
                             .addArg(Tokens.ARGS_BINDINGS, bindings).create();
    }
}
