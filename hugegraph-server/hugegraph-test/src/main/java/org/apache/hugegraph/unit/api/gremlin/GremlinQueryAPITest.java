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

package org.apache.hugegraph.unit.api.gremlin;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;

import org.apache.hugegraph.api.gremlin.GremlinAPI;
import org.apache.hugegraph.api.gremlin.GremlinQueryAPI;
import org.apache.tinkerpop.gremlin.server.handler.HttpHandlerUtil;
import org.apache.tinkerpop.shaded.jackson.databind.JsonNode;
import org.apache.tinkerpop.shaded.jackson.databind.ObjectMapper;
import org.apache.hugegraph.testutil.Assert;
import org.apache.hugegraph.unit.BaseUnitTest;
import org.junit.Test;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;

public class GremlinQueryAPITest extends BaseUnitTest {

    private static boolean matchBadRequest(String exClass) throws Exception {
        Method m = GremlinQueryAPI.class.getDeclaredMethod(
                "matchBadRequestException", String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, exClass);
    }

    @Test
    public void testMatchBadRequestExceptionWithTinkerpop() throws Exception {
        Assert.assertTrue(matchBadRequest(
                "org.apache.tinkerpop.gremlin.process.traversal.util.FastNoSuchElementException"));
    }

    @Test
    public void testMatchBadRequestExceptionWithAuthExceptions() throws Exception {
        Assert.assertFalse(matchBadRequest(
                "org.apache.tinkerpop.gremlin.server.auth.AuthenticationException"));
        Assert.assertFalse(matchBadRequest(
                "org.apache.tinkerpop.gremlin.server.authz.AuthorizationException"));
    }

    @Test
    public void testMatchBadRequestExceptionWithHugegraph() throws Exception {
        Assert.assertTrue(matchBadRequest("org.apache.hugegraph.exception.NotFoundException"));
        Assert.assertTrue(matchBadRequest("java.lang.IllegalArgumentException"));
        Assert.assertTrue(matchBadRequest("groovy.lang.MissingPropertyException"));
    }

    @Test
    public void testMatchBadRequestExceptionWithOther() throws Exception {
        Assert.assertFalse(matchBadRequest(null));
        Assert.assertFalse(matchBadRequest("java.lang.NullPointerException"));
        Assert.assertFalse(matchBadRequest("java.io.IOException"));
    }
    private static String normalizeAliases(String request, String space,
                                           Set<String> graphs) throws Exception {
        Method method = GremlinAPI.class.getDeclaredMethod(
                "normalizeLegacyAliases", String.class, String.class, Set.class);
        method.setAccessible(true);
        return (String) method.invoke(null, request, space, graphs);
    }

    @Test
    public void testLegacyClientAliasesPreserveQueryAndBindings() throws Exception {
        String request = "{\"gremlin\":\"g.V(id)\",\"language\":\"gremlin-groovy\"," +
                         "\"bindings\":{\"id\":9223372036854775807," +
                         "\"text\":\"hugegraph __g_hugegraph\"}," +
                         "\"aliases\":{\"graph\":\"hugegraph\",\"g\":\"__g_hugegraph\"}}";
        JsonNode actual = new ObjectMapper().readTree(normalizeAliases(
                request, "DEFAULT", Set.of("DEFAULT-hugegraph")));
        Assert.assertEquals("DEFAULT-hugegraph", actual.at("/aliases/graph").asText());
        Assert.assertEquals("__g_DEFAULT-hugegraph", actual.at("/aliases/g").asText());
        Assert.assertEquals("g.V(id)", actual.get("gremlin").asText());
        Assert.assertEquals(Long.MAX_VALUE, actual.at("/bindings/id").longValue());
        Assert.assertEquals("hugegraph __g_hugegraph", actual.at("/bindings/text").asText());
    }

    @Test
    public void testQualifiedAndUnknownAliasesRemainUnchanged() throws Exception {
        String request = "{\"aliases\":{\"g\":\"__g_OTHER-hugegraph\"," +
                         "\"missing\":\"__g_missing\",\"invalid\":12}}";
        Assert.assertEquals(request, normalizeAliases(request, "DEFAULT",
                            Set.of("DEFAULT-hugegraph", "OTHER-hugegraph")));
    }

    @Test
    public void testLegacyAliasesOnlyUseConfiguredDefaultSpace() throws Exception {
        String request = "{\"aliases\":{\"g\":\"__g_hugegraph\"}}";
        Assert.assertEquals(request, normalizeAliases(request, "DEFAULT",
                            Set.of("OTHER-hugegraph")));
        JsonNode actual = new ObjectMapper().readTree(normalizeAliases(
                request, "OTHER", Set.of("DEFAULT-hugegraph", "OTHER-hugegraph")));
        Assert.assertEquals("__g_OTHER-hugegraph", actual.at("/aliases/g").asText());
    }

    @Test
    public void testExactGraphNameTakesPrecedence() throws Exception {
        String request = "{\"aliases\":{\"g\":\"__g_DEFAULT-hugegraph\"}}";
        Assert.assertEquals(request, normalizeAliases(request, "DEFAULT",
                            Set.of("DEFAULT-hugegraph", "DEFAULT-DEFAULT-hugegraph")));
    }

    @Test
    public void testRequestsWithoutAliasMapRemainUnchanged() throws Exception {
        for (String request : new String[]{"{\"gremlin\":\"1+2\"}",
                                           "{\"aliases\":null}", "{\"aliases\":[]}", "{invalid"}) {
            Assert.assertEquals(request, normalizeAliases(request, "DEFAULT",
                                Set.of("DEFAULT-hugegraph")));
        }
    }

    @Test
    public void testAliasNormalizationPreservesDecimalPrecision() throws Exception {
        String request = "{\"aliases\":{\"g\":\"__g_hugegraph\"}," +
                         "\"bindings\":{\"value\":0.12345678901234567890123456789}}";
        String actual = normalizeAliases(request, "DEFAULT", Set.of("DEFAULT-hugegraph"));
        Assert.assertTrue(actual.contains("0.12345678901234567890123456789"));
    }

    @Test
    public void testAliasNormalizationPreservesDecodedBindingTypes() throws Exception {
        String numbers = "[1.0,0.0,-0.0,1e0,-0e0,1.5,1,9223372036854775807," +
                         "0.12345678901234567890123456789]";
        String request = "{\"gremlin\":\"x\",\"language\":\"gremlin-groovy\"," +
                         "\"aliases\":{\"g\":\"__g_hugegraph\"}," +
                         "\"bindings\":{\"x\":1.0,\"zero\":-0.0,\"list\":" + numbers +
                         ",\"nested\":{\"values\":" + numbers + "}}}";
        String actual = normalizeAliases(request, "DEFAULT", Set.of("DEFAULT-hugegraph"));
        Assert.assertEquals(request.replace("__g_hugegraph", "__g_DEFAULT-hugegraph"), actual);
        Map<String, Object> expected = decodeBindings(request);
        Map<String, Object> bindings = decodeBindings(actual);
        Assert.assertEquals(Double.class, bindings.get("x").getClass());
        Assert.assertEquals(Double.doubleToRawLongBits(-0.0),
                            Double.doubleToRawLongBits((Double) bindings.get("zero")));
        Assert.assertEquals(expected, bindings);
    }

    @Test
    public void testAliasTokenReplacementPreservesOtherRequestText() throws Exception {
        String request = " { \"bindings\": {\"aliases\": {\"g\": \"__g_hugegraph\"}," +
                         "\"text\":\"你好 __g_hugegraph\",\"n\":-0.0}, " +
                         "\"aliases\": {\"graph\":\"hugegraph\", \"g\":\"__g_hugegraph\"," +
                         "\"invalid\":{\"nested\":\"hugegraph\"}} } ";
        String expected = request.replace("\"graph\":\"hugegraph\", \"g\":\"__g_hugegraph\"",
                                          "\"graph\":\"DEFAULT-hugegraph\", " +
                                          "\"g\":\"__g_DEFAULT-hugegraph\"");
        Assert.assertEquals(expected, normalizeAliases(request, "DEFAULT",
                                                       Set.of("DEFAULT-hugegraph")));
        String escaped = "{\"aliases\":{\"g\":\"__g_" + "\\u0068ugegraph\"}}";
        Assert.assertEquals("{\"aliases\":{\"g\":\"__g_DEFAULT-hugegraph\"}}",
                            normalizeAliases(escaped, "DEFAULT", Set.of("DEFAULT-hugegraph")));
        String malformed = "{\"aliases\":{\"g\":\"__g_hugegraph\"},\"bindings\": [";
        Assert.assertEquals(malformed, normalizeAliases(malformed, "DEFAULT",
                                                        Set.of("DEFAULT-hugegraph")));
    }

    private static Map<String, Object> decodeBindings(String body) throws Exception {
        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, "/gremlin",
                Unpooled.copiedBuffer(body, CharsetUtil.UTF_8));
        try {
            return HttpHandlerUtil.getRequestMessageFromHttpRequest(request).getArg("bindings");
        } finally {
            request.release();
        }
    }

}
