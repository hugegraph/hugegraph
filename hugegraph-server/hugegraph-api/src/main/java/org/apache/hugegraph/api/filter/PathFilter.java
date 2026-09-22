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

package org.apache.hugegraph.api.filter;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Set;

import org.apache.hugegraph.config.HugeConfig;
import org.apache.hugegraph.config.ServerOptions;
import org.apache.hugegraph.core.GraphManager;
import org.apache.hugegraph.util.E;
import org.apache.hugegraph.util.Log;
import org.slf4j.Logger;

import com.google.common.collect.ImmutableSet;

import jakarta.annotation.Priority;
import jakarta.inject.Singleton;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.PathSegment;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.Provider;

@Provider
@Singleton
@PreMatching
@Priority(Priorities.AUTHENTICATION - 100)
public class PathFilter implements ContainerRequestFilter {

    private static final Logger LOG = Log.logger(PathFilter.class);

    private static final String GRAPH_SPACE = "graphspaces";
    private static final String ARTHAS_START = "arthas";

    public static final String REQUEST_TIME = "request_time";

    private static final String DELIMITER = "/";
    private static final Set<String> WHITE_API_LIST = ImmutableSet.of(
            "",
            "apis",
            "metrics",
            "versions",
            "health",
            "gremlin",
            "graphs/auth",
            "graphs/auth/users",
            "auth/users",
            "auth/managers",
            "auth",
            "hstore",
            "pd",
            "kafka",
            "whiteiplist",
            "vermeer",
            "store",
            "expiredclear",
            "department",
            "saas",
            "trade",
            "kvstore",
            "openapi.json"
    );

    @Context
    private jakarta.inject.Provider<HugeConfig> configProvider;

    @Context
    private jakarta.inject.Provider<GraphManager> managerProvider;

    public static boolean isWhiteAPI(String rootPath) {

        return WHITE_API_LIST.contains(rootPath);
    }

    @Override
    public void filter(ContainerRequestContext context)
            throws IOException {
        context.setProperty(REQUEST_TIME, System.currentTimeMillis());

        List<PathSegment> segments = context.getUriInfo().getPathSegments();
        E.checkArgument(segments.size() > 0, "Invalid request uri '%s'",
                        context.getUriInfo().getPath());
        String rootPath = segments.get(0).getPath();

        if (isWhiteAPI(rootPath) || GRAPH_SPACE.equals(rootPath) ||
            ARTHAS_START.equals(rootPath)) {
            return;
        }

        UriInfo uriInfo = context.getUriInfo();
        String defaultPathSpace =
                this.configProvider.get().get(ServerOptions.PATH_GRAPH_SPACE);
        String path = uriInfo.getBaseUri().getPath() +
                      String.join(DELIMITER, GRAPH_SPACE, defaultPathSpace);
        int start = 0;
        // The 1.5 client scopes auth endpoints by graph. Most auth resources now
        // belong to a graph space; login/logout/verify remain global.
        if (segments.size() >= 4 && "graphs".equals(rootPath) &&
            "auth".equals(segments.get(2).getPath())) {
            String resource = segments.get(3).getPath();
            boolean globalGroup = false;
            if ("groups".equals(resource)) {
                GraphManager manager = this.managerProvider.get();
                globalGroup = !manager.requireAuthentication() ||
                              !manager.authManager().supportsGraphSpaceAuth();
            }
            if (ImmutableSet.of("login", "logout", "verify").contains(resource) || globalGroup) {
                path = uriInfo.getBaseUri().getPath() + "auth";
                start = 3;
            } else if (ImmutableSet.of("users", "targets", "belongs",
                                       "accesses", "projects", "groups").contains(resource)) {
                start = 2;
            }
        }
        for (int i = start; i < segments.size(); i++) {
            path = String.join(DELIMITER, path, segments.get(i).getPath());
        }
        LOG.debug("Redirect request uri from {} to {}",
                  uriInfo.getRequestUri().getPath(), path);
        URI requestUri = uriInfo.getRequestUriBuilder().uri(path).build();
        context.setRequestUri(uriInfo.getBaseUri(), requestUri);
    }
}
