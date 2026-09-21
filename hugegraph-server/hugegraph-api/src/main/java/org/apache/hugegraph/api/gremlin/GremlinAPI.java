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

package org.apache.hugegraph.api.gremlin;

import java.io.IOException;
import java.util.Set;

import org.apache.hugegraph.api.filter.CompressInterceptor.Compress;
import org.apache.hugegraph.config.HugeConfig;
import org.apache.hugegraph.config.ServerOptions;
import org.apache.hugegraph.core.GraphManager;
import org.apache.hugegraph.metrics.MetricsUtil;
import org.apache.hugegraph.util.E;
import org.apache.tinkerpop.shaded.jackson.core.JsonParser;
import org.apache.tinkerpop.shaded.jackson.core.JsonToken;
import org.apache.tinkerpop.shaded.jackson.databind.ObjectMapper;

import com.codahale.metrics.Histogram;
import com.codahale.metrics.annotation.Timed;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Singleton;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

@Path("gremlin")
@Singleton
@Tag(name = "GremlinAPI")
public class GremlinAPI extends GremlinQueryAPI {

    private static final ObjectMapper REQUEST_MAPPER = new ObjectMapper();

    private static final Histogram GREMLIN_INPUT_HISTOGRAM =
            MetricsUtil.registerHistogram(GremlinAPI.class, "gremlin-input");
    private static final Histogram GREMLIN_OUTPUT_HISTOGRAM =
            MetricsUtil.registerHistogram(GremlinAPI.class, "gremlin-output");

    @POST
    @Timed
    @Compress
    @Consumes(APPLICATION_JSON)
    @Produces(APPLICATION_JSON_WITH_CHARSET)
    public Response post(@Context HugeConfig conf,
                         @Context GraphManager manager,
                         @Context HttpHeaders headers,
                         @Parameter(description = "The Gremlin query request body")
                         String request) throws IOException {
        /* The following code is reserved for forwarding request */
        // context.getRequestDispatcher(location).forward(request, response);
        // return Response.seeOther(UriBuilder.fromUri(location).build())
        // .build();
        // Response.temporaryRedirect(UriBuilder.fromUri(location).build())
        // .build();
        String auth = headers.getHeaderString(HttpHeaders.AUTHORIZATION);
        String forwarded = normalizeLegacyAliases(
                request, conf.get(ServerOptions.PATH_GRAPH_SPACE), manager.graphs());
        Response response = this.client().doPostRequest(auth, forwarded);
        GREMLIN_INPUT_HISTOGRAM.update(request.length());
        GREMLIN_OUTPUT_HISTOGRAM.update(response.getLength());
        return transformResponseIfNeeded(response);
    }

    private static String normalizeLegacyAliases(String request, String graphSpace,
                                                 Set<String> graphs) throws IOException {
        StringBuilder forwarded = new StringBuilder();
        int copied = 0;
        try (JsonParser parser = REQUEST_MAPPER.createParser(request)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                return request;
            }
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String field = parser.currentName();
                JsonToken value = parser.nextToken();
                if (!"aliases".equals(field) || value != JsonToken.START_OBJECT) {
                    parser.skipChildren();
                    continue;
                }
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    if (parser.nextToken() != JsonToken.VALUE_STRING) {
                        parser.skipChildren();
                        continue;
                    }
                    int start = (int) parser.currentTokenLocation().getCharOffset();
                    String target = parser.getText();
                    int end = (int) parser.currentLocation().getCharOffset();
                    String prefix = target.startsWith("__g_") ? "__g_" : "";
                    String graph = target.substring(prefix.length());
                    // Explicit names take precedence over legacy names in the default space.
                    String qualified = graphSpace + "-" + graph;
                    if (!graphs.contains(graph) && graphs.contains(qualified)) {
                        // Replace only the alias string token. Re-encoding the request can
                        // change binding types, decimal precision and floating-point -0.0.
                        forwarded.append(request, copied, start);
                        forwarded.append(REQUEST_MAPPER.writeValueAsString(prefix + qualified));
                        copied = end;
                    }
                }
            }
        } catch (IOException e) {
            // Keep malformed-request validation and error responses in Gremlin Server.
            return request;
        }
        return copied == 0 ? request : forwarded.append(request, copied, request.length()).toString();
    }

    @GET
    @Timed
    @Compress(buffer = (1024 * 40))
    @Produces(APPLICATION_JSON_WITH_CHARSET)
    public Response get(@Context HugeConfig conf,
                        @Context HttpHeaders headers,
                        @Context UriInfo uriInfo) {
        String auth = headers.getHeaderString(HttpHeaders.AUTHORIZATION);
        String query = uriInfo.getRequestUri().getRawQuery();
        E.checkArgumentNotNull(query, "The request query can't be empty");
        MultivaluedMap<String, String> params = uriInfo.getQueryParameters();
        Response response = this.client().doGetRequest(auth, params);
        GREMLIN_INPUT_HISTOGRAM.update(query.length());
        GREMLIN_OUTPUT_HISTOGRAM.update(response.getLength());
        return transformResponseIfNeeded(response);
    }
}
