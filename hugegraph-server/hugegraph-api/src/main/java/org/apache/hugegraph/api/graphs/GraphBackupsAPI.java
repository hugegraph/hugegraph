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

import java.math.BigDecimal;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.apache.hugegraph.HugeGraph;
import org.apache.hugegraph.api.API;
import org.apache.hugegraph.backup.GraphBackupJob;
import org.apache.hugegraph.backup.GraphRestoreJob;
import org.apache.hugegraph.core.GraphManager;
import org.apache.hugegraph.job.Job;
import org.apache.hugegraph.job.JobBuilder;
import org.apache.hugegraph.task.HugeTask;
import org.apache.hugegraph.task.TaskScheduler;
import org.apache.hugegraph.util.E;
import org.apache.hugegraph.util.JsonUtil;
import org.apache.hugegraph.util.Log;
import org.slf4j.Logger;

import com.codahale.metrics.annotation.Timed;

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
    private static final String REQUEST_ID = "request_id";
    private static final int MAX_REQUEST_ID_LENGTH = 128;
    private static final ConcurrentMap<String, Object> REQUEST_LOCKS =
            new ConcurrentHashMap<>();

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
        String requestId = requestId(request);
        HugeGraph target = graph(manager, graphSpace, graph);
        target.backupService();
        Map<String, Object> input = backupInput(repository, keepNum, requestId);
        HugeTask<?> task = submit(target, GraphBackupJob.BACKUP,
                                  requestId, input, new GraphBackupJob());
        return taskResult(task, requestId);
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
        String version = backupId(request);
        String requestId = requestId(request);
        HugeGraph target = graph(manager, graphSpace, graph);
        target.backupService();
        Map<String, Object> input = restoreInput(repository, version, requestId);
        HugeTask<?> task = submit(target, GraphRestoreJob.RESTORE,
                                  requestId, input, new GraphRestoreJob());
        return taskResult(task, requestId);
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
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("backups", target.backupService().list(name));
        return result;
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
        BigDecimal decimal = new BigDecimal(value.toString()).stripTrailingZeros();
        E.checkArgument(decimal.scale() <= 0, "'%s' must be an integer", key);
        try {
            return decimal.intValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(String.format(
                    "'%s' is outside the supported integer range", key), e);
        }
    }

    private static String backupId(Map<String, Object> request) {
        if (request == null || !request.containsKey("backup_id") ||
            request.get("backup_id") == null) {
            return null;
        }
        Object value = request.get("backup_id");
        E.checkArgument(value instanceof String, "Backup id must be a string");
        String version = ((String) value).trim();
        E.checkArgument(!version.isEmpty(), "Backup id must not be blank");
        E.checkArgument(version.matches(VERSION_PATTERN),
                        "Invalid backup id '%s'", version);
        return version;
    }

    private static String requestId(Map<String, Object> request) {
        if (request == null || !request.containsKey(REQUEST_ID) ||
            request.get(REQUEST_ID) == null) {
            return null;
        }
        Object value = request.get(REQUEST_ID);
        E.checkArgument(value instanceof String,
                        "Request id must be a string");
        String id = ((String) value).trim();
        E.checkArgument(!id.isEmpty() && id.length() <= MAX_REQUEST_ID_LENGTH,
                        "Request id must be non-blank and at most %s characters",
                        MAX_REQUEST_ID_LENGTH);
        return id;
    }

    private static Map<String, Object> backupInput(String repository,
                                                   int keepNum,
                                                   String requestId) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("operation", GraphBackupJob.BACKUP);
        input.put("repository", repository);
        input.put("keep_num", keepNum);
        input.put(REQUEST_ID, requestId);
        return input;
    }

    private static Map<String, Object> restoreInput(String repository,
                                                    String version,
                                                    String requestId) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("operation", GraphBackupJob.RESTORE);
        input.put("repository", repository);
        input.put("backup_id", version);
        input.put(REQUEST_ID, requestId);
        return input;
    }

    private static HugeTask<?> submit(HugeGraph graph, String operation,
                                      String requestId,
                                      Map<String, Object> input,
                                      Job<Map<String, Object>> job) {
        String payload = JsonUtil.toJson(input);
        Object lock = REQUEST_LOCKS.computeIfAbsent(graph.spaceGraphName(),
                                                    key -> new Object());
        synchronized (lock) {
            if (requestId != null) {
                HugeTask<?> existing = findRequestTask(graph.taskScheduler(),
                                                       requestId);
                if (existing != null) {
                    E.checkArgument(payload.equals(existing.input()),
                                    "Request id '%s' was already used with a " +
                                    "different graph backup request", requestId);
                    return existing;
                }
            }
            LOG.info("Schedule durable graph {} task for '{}'", operation,
                     graph.spaceGraphName());
            return JobBuilder.<Map<String, Object>>of(graph)
                             .name("graph-" + operation + ":" + input.get("repository"))
                             .input(payload)
                             .job(job)
                             .schedule();
        }
    }

    private static HugeTask<?> findRequestTask(TaskScheduler scheduler,
                                               String requestId) {
        Iterator<HugeTask<Object>> tasks = scheduler.tasks(
                null, TaskScheduler.NO_LIMIT, null, false);
        while (tasks.hasNext()) {
            HugeTask<?> task = tasks.next();
            if (!isGraphBackupTask(task) || task.input() == null) {
                continue;
            }
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> input = JsonUtil.fromJson(task.input(),
                                                              Map.class);
                if (requestId.equals(input.get(REQUEST_ID))) {
                    return task;
                }
            } catch (RuntimeException e) {
                LOG.warn("Skip graph backup task '{}' with invalid input",
                         task.id(), e);
            }
        }
        return null;
    }

    private static boolean isGraphBackupTask(HugeTask<?> task) {
        return GraphBackupJob.BACKUP_TASK_TYPE.equals(task.type()) ||
               GraphBackupJob.RESTORE_TASK_TYPE.equals(task.type());
    }

    private static Map<String, Object> taskResult(HugeTask<?> task,
                                                   String requestId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("task_id", task.id().asLong());
        if (requestId != null) {
            result.put(REQUEST_ID, requestId);
        }
        return result;
    }
}
