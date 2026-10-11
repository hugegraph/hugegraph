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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import org.apache.hugegraph.api.gremlin.GremlinQueryAPI;
import org.apache.hugegraph.exception.HugeGremlinException;
import org.apache.hugegraph.meta.SchemaSyncClient;
import org.apache.hugegraph.testutil.Assert;
import org.apache.hugegraph.unit.BaseUnitTest;
import org.junit.Test;
import org.mockito.Mockito;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;

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
    public void testGremlinServerErrorOfAGraphNotSyncedIs503() throws Exception {
        String syncing = SchemaSyncClient.SyncingException.class.getName();
        // The Gremlin Server answers 500 for the failed script
        Assert.assertEquals(503, transformedStatus(Map.of(
                "message", "The schema of graph 'DEFAULT/hugegraph' is syncing with PD",
                "Exception-Class", syncing,
                "exceptions", List.of(syncing))));
        // Wrapped in another exception
        Assert.assertEquals(503, transformedStatus(Map.of(
                "message", "wrapped",
                "Exception-Class", "java.util.concurrent.CompletionException",
                "exceptions", List.of("java.util.concurrent.CompletionException", syncing))));
        // Other failures keep their status
        Assert.assertEquals(400, transformedStatus(Map.of(
                "message", "bad", "Exception-Class", "java.lang.IllegalArgumentException")));
        Assert.assertEquals(500, transformedStatus(Map.of(
                "message", "npe", "Exception-Class", "java.lang.NullPointerException",
                "exceptions", List.of("java.lang.NullPointerException"))));
    }

    private static int transformedStatus(Map<String, Object> body) throws Exception {
        Response response = Mockito.mock(Response.class);
        Mockito.when(response.getMediaType()).thenReturn(MediaType.APPLICATION_JSON_TYPE);
        Mockito.when(response.getHeaders()).thenReturn(new MultivaluedHashMap<>());
        Mockito.when(response.getStatusInfo())
               .thenReturn(Response.Status.INTERNAL_SERVER_ERROR);
        Mockito.when(response.readEntity(Map.class)).thenReturn(body);
        Method m = GremlinQueryAPI.class.getDeclaredMethod("transformResponseIfNeeded",
                                                           Response.class);
        m.setAccessible(true);
        try {
            m.invoke(null, response);
        } catch (InvocationTargetException e) {
            return ((HugeGremlinException) e.getCause()).statusCode();
        }
        throw new AssertionError("An error response must throw");
    }

    @Test
    public void testMatchBadRequestExceptionWithOther() throws Exception {
        Assert.assertFalse(matchBadRequest(null));
        Assert.assertFalse(matchBadRequest("java.lang.NullPointerException"));
        Assert.assertFalse(matchBadRequest("java.io.IOException"));
    }
}
