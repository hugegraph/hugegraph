/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hugegraph.api.graphs;

import java.util.Map;

import org.apache.hugegraph.HugeGraph;
import org.apache.hugegraph.api.API;
import org.apache.hugegraph.core.GraphManager;
import org.apache.hugegraph.job.EphemeralJob;
import org.apache.hugegraph.job.EphemeralJobBuilder;
import org.apache.hugegraph.task.HugeTask;
import org.apache.hugegraph.util.E;
import org.apache.hugegraph.util.Log;
import org.slf4j.Logger;

import com.codahale.metrics.annotation.Timed;
import com.google.common.collect.ImmutableMap;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Singleton;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;

@Path("graphspaces/{graphspace}/graphs/{graph}/backups")
@Singleton
@Tag(name = "GraphBackupsAPI")
public class GraphBackupsAPI extends API {

    private static final Logger LOG = Log.logger(GraphBackupsAPI.class);
    private static final String REPOSITORY_PATTERN =
            "[A-Za-z0-9][A-Za-z0-9._-]{0,62}";
    private static final String VERSION_PATTERN =
            "v-[0-9]{13}-[0-9a-f]{8}";

    @POST
    @Timed
    @Consumes(APPLICATION_JSON)
    @Produces(APPLICATION_JSON_WITH_CHARSET)
    @RolesAllowed({"space", "$owner=$graph"})
    public Map<String, Object> create(@Context GraphManager manager,
                                      @Parameter(description = "The graph space name")
                                      @PathParam("graphspace") String graphSpace,
                                      @PathParam("graph") String graph,
                                      Map<String, Object> request) {
        String repository = repository(request);
        int keepNum = integer(request, "keep_num", 0);
        E.checkArgument(keepNum >= 0, "Keep number must be non-negative");
        HugeGraph target = graph(manager, graphSpace, graph);
        LOG.info("Schedule graph backup for '{}' in repository '{}'", graph,
                 repository);
        HugeTask<Map<String, Object>> task = EphemeralJobBuilder
                .<Map<String, Object>>of(target)
                .name("graph-backup:" + repository)
                .input(request == null ? null : request.toString())
                .job(new EphemeralJob<Map<String, Object>>() {
                    @Override
                    public String type() {
                        return "graph_backup";
                    }

                    @Override
                    public Map<String, Object> execute() {
                        return target.backupService().create(repository, keepNum);
                    }
                }).schedule();
        return ImmutableMap.of("task_id", task.id().asLong());
    }

    @POST
    @Timed
    @Path("restore")
    @Consumes(APPLICATION_JSON)
    @Produces(APPLICATION_JSON_WITH_CHARSET)
    @RolesAllowed({"space", "$owner=$graph"})
    public Map<String, Object> restore(@Context GraphManager manager,
                                       @PathParam("graphspace") String graphSpace,
                                       @PathParam("graph") String graph,
                                       Map<String, Object> request) {
        E.checkArgument(request != null && Boolean.TRUE.equals(request.get("confirm")),
                        "Restore requires confirm=true");
        String repository = repository(request);
        Object value = request.get("backup_id");
        E.checkArgument(value == null || value instanceof String,
                        "Backup id must be a string");
        String version = (String) value;
        if (version != null) {
            E.checkArgument(version.matches(VERSION_PATTERN),
                            "Invalid backup id '%s'", version);
        }
        HugeGraph target = graph(manager, graphSpace, graph);
        HugeTask<Map<String, Object>> task = EphemeralJobBuilder
                .<Map<String, Object>>of(target)
                .name("graph-restore:" + repository)
                .input(request.toString())
                .job(new EphemeralJob<Map<String, Object>>() {
                    @Override
                    public String type() {
                        return "graph_restore";
                    }

                    @Override
                    public Map<String, Object> execute() {
                        return target.backupService().restore(repository, version);
                    }
                }).schedule();
        return ImmutableMap.of("task_id", task.id().asLong());
    }

    @GET
    @Timed
    @Produces(APPLICATION_JSON_WITH_CHARSET)
    @RolesAllowed({"space_member", "$dynamic"})
    public Map<String, Object> list(@Context GraphManager manager,
                                    @PathParam("graphspace") String graphSpace,
                                    @PathParam("graph") String graph,
                                    @jakarta.ws.rs.QueryParam("repository")
                                    String repository) {
        HugeGraph target = graph(manager, graphSpace, graph);
        String name = repository == null ? "default" : repository;
        return ImmutableMap.of("backups", target.backupService().list(name));
    }

    @GET
    @Timed
    @Path("{backupId}")
    @Produces(APPLICATION_JSON_WITH_CHARSET)
    @RolesAllowed({"space_member", "$dynamic"})
    public Map<String, Object> get(@Context GraphManager manager,
                                   @PathParam("graphspace") String graphSpace,
                                   @PathParam("graph") String graph,
                                   @PathParam("backupId") String backupId,
                                   @jakarta.ws.rs.QueryParam("repository")
                                   String repository) {
        HugeGraph target = graph(manager, graphSpace, graph);
        String name = repository == null ? "default" : repository;
        return target.backupService().get(name, backupId);
    }

    private static String repository(Map<String, Object> request) {
        E.checkArgument(request != null && request.get("repository") instanceof String,
                        "Request must contain a repository string");
        String repository = (String) request.get("repository");
        E.checkArgument(repository.matches(REPOSITORY_PATTERN),
                        "Invalid repository name '%s'", repository);
        return repository;
    }

    private static int integer(Map<String, Object> request, String key,
                               int defaultValue) {
        if (request == null || request.get(key) == null) {
            return defaultValue;
        }
        Object value = request.get(key);
        E.checkArgument(value instanceof Number, "'%s' must be numeric", key);
        return ((Number) value).intValue();
    }
}
