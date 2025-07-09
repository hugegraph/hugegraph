/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.apache.hugegraph.api.space;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.security.RolesAllowed;

import org.apache.commons.lang.StringUtils;
import org.apache.hugegraph.HugeException;
import org.apache.hugegraph.HugeGraph;
import org.apache.hugegraph.api.API;
import org.apache.hugegraph.api.filter.StatusFilter.Status;
import org.apache.hugegraph.auth.AuthManager;
import org.apache.hugegraph.auth.HugeDefaultRole;
import org.apache.hugegraph.core.GraphManager;
import org.apache.hugegraph.define.Checkable;
import org.apache.hugegraph.exception.NotFoundException;
import org.apache.hugegraph.server.RestServer;
import org.apache.hugegraph.space.GraphSpace;
import org.apache.hugegraph.util.E;
import org.apache.hugegraph.util.Log;
import org.apache.logging.log4j.util.Strings;
import org.slf4j.Logger;

import com.codahale.metrics.annotation.Timed;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableMap;

import jakarta.inject.Singleton;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.SecurityContext;

@Path("graphspaces")
@Singleton
public class GraphSpaceAPI extends API {

    private static final Logger LOGGER = Log.logger(RestServer.class);

    private static final String GRAPH_SPACE_ACTION = "action";
    private static final String UPDATE = "update";
    private static final String GRAPH_SPACE_ACTION_CLEAR = "clear";

    @GET
    @Timed
    @Produces(APPLICATION_JSON_WITH_CHARSET)
    public Object list(@Context GraphManager manager,
                       @Context SecurityContext sc) {
        LOGGER.debug("List all graph spaces");

        Set<String> spaces = manager.graphSpaces();
        return ImmutableMap.of("graphSpaces", spaces);
    }

    @GET
    @Timed
    @Path("{graphspace}")
    @Produces(APPLICATION_JSON_WITH_CHARSET)
    @RolesAllowed({"analyst"})
    public Object get(@Context GraphManager manager,
                      @PathParam("graphspace") String graphSpace) {
        LOGGER.debug("Get graph space by name '{}'", graphSpace);
        manager.getStorageInfo(graphSpace);
        return manager.serializer().writeGraphSpace(space(manager, graphSpace));
    }

    @POST
    @Timed
    @Status(Status.CREATED)
    @Consumes(APPLICATION_JSON)
    @Produces(APPLICATION_JSON_WITH_CHARSET)
    @RolesAllowed("admin")
    public String create(@Context GraphManager manager,
                         JsonGraphSpace jsonGraphSpace) {
        LOGGER.debug("Create graph space: '{}'", jsonGraphSpace);

        jsonGraphSpace.checkCreate(false);

        String creator = manager.authManager().username();

        GraphSpace exist = manager.graphSpace(jsonGraphSpace.name);
        E.checkArgument(exist == null, "The graph space '%s' has existed",
                        jsonGraphSpace.name);
        GraphSpace space = manager.createGraphSpace(
                jsonGraphSpace.toGraphSpace(creator));
        if (space.auth()) {
            manager.authManager().initSpaceDefaultRoles(space.name());
        }
        // TODO: duplicated log
        //LOGGER.getAuditLogger().logCreateTenant(space.name(), creator);
        return manager.serializer().writeGraphSpace(space);
    }

    @POST
    @Timed
    @Status(Status.CREATED)
    @Consumes(APPLICATION_JSON)
    @Produces(APPLICATION_JSON_WITH_CHARSET)
    @Path("{graphspace}/role")
    @RolesAllowed({"analyst"})
    public String setDefaultRole(@Context GraphManager manager,
                                 @PathParam("graphspace") String name,
                                 JsonDefaultRole jsonRole) {
        String user = jsonRole.user;
        String graph = jsonRole.graph;
        HugeDefaultRole role =
                HugeDefaultRole.valueOf(jsonRole.role.toUpperCase());
        LOGGER.debug("Create default role: {} {} {}", user, role, name);
        AuthManager authManager = manager.authManager();
        E.checkArgument(authManager.findUser(user, false) != null ||
                        authManager.findGroup(user, false) != null,
                        "The user or group is not exist");
        // only admin can set space admin
        if (!authManager.isAdminManager(authManager.username()) &&
            role.equals(HugeDefaultRole.SPACE)) {
            throw new HugeException("Forbidden to set role %s", role.toString());
        }

        boolean hasGraph = role.equals(HugeDefaultRole.OBSERVER);

        E.checkArgument(!hasGraph || StringUtils.isNotEmpty(graph),
                        "Must set a graph for observer");

        Map<String, String> result = new HashMap<>();
        result.put("user", user);
        result.put("role", jsonRole.role);
        result.put("graphSpace", name);

        if (hasGraph) {
            authManager.createDefaultRole(name, user, role, graph);
            result.put("graph", graph);
        } else {
            authManager.createSpaceDefaultRole(name, user, role);
        }

        return manager.serializer().writeMap(result);
    }

    @GET
    @Timed
    @Path("{graphspace}/role")
    @Consumes(APPLICATION_JSON)
    @RolesAllowed("analyst")
    public String checkDefaultRole(@Context GraphManager manager,
                                   @PathParam("graphspace") String name,
                                   @QueryParam("user") String user,
                                   @QueryParam("role") String role,
                                   @QueryParam("graph") String graph) {
        LOGGER.debug("Check space role: {} {} {}", user, role, name);
        AuthManager authManager = manager.authManager();

        HugeDefaultRole defaultRole =
                HugeDefaultRole.valueOf(role.toUpperCase());
        boolean hasGraph = defaultRole.equals(HugeDefaultRole.OBSERVER);
        E.checkArgument(!hasGraph || StringUtils.isNotEmpty(graph),
                        "Must set a graph for observer");

        boolean result;
        if (hasGraph) {
            result = authManager.isDefaultRole(name, graph, user,
                                               defaultRole);
        } else {
            result = authManager.isDefaultRole(name, user,
                                               defaultRole);
        }
        return manager.serializer().writeMap(ImmutableMap.of("check", result));
    }

    @DELETE
    @Timed
    @Path("{graphspace}/role")
    @Consumes(APPLICATION_JSON)
    @RolesAllowed("analyst")
    public void deleteDefaultRole(@Context GraphManager manager,
                                  @PathParam("graphspace") String name,
                                  @QueryParam("user") String user,
                                  @QueryParam("role") String role,
                                  @QueryParam("graph") String graph) {
        LOGGER.debug("Delete space role: {} {} {}", user, role, name);

        AuthManager authManager = manager.authManager();
        E.checkArgument(authManager.findUser(user, false) != null ||
                        authManager.findGroup(user, false) != null,
                        "The user or group is not exist");

        if (!authManager.isAdminManager(authManager.username()) &&
            role.equals(HugeDefaultRole.SPACE)) {
            throw new HugeException("Forbidden to delete role %s", role);
        }

        HugeDefaultRole defaultRole =
                HugeDefaultRole.valueOf(role.toUpperCase());
        boolean hasGraph = defaultRole.equals(HugeDefaultRole.OBSERVER);
        E.checkArgument(!hasGraph || StringUtils.isNotEmpty(graph),
                        "Must set a graph for observer");
        HugeGraph hg = manager.graph(name, graph);
        if (hasGraph) {
            authManager.deleteDefaultRole(name, user, defaultRole,
                                          hg.nickname());
        } else {
            authManager.deleteDefaultRole(name, user, defaultRole);
        }
    }

    @GET
    @Timed
    @Path("profile")
    @Produces(APPLICATION_JSON_WITH_CHARSET)
    public Object listProfile(@Context GraphManager manager,
                              @QueryParam("prefix") String prefix,
                              @Context SecurityContext sc) {
        LOGGER.debug("List all spaces profile");

        Set<String> spaces = manager.graphSpaces();
        List<Map<String, Object>> spaceList = new ArrayList<>();
        List<Map<String, Object>> result = new ArrayList<>();
        AuthManager authManager = manager.authManager();
        String user = authManager.username();
        String defaultSpace = authManager.getDefaultSpace(user);
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss");
        for (String sp : spaces) {
            manager.getStorageInfo(sp);
            GraphSpace gs = space(manager, sp);
            boolean isManager = authManager.isAdminManager(user) ||
                                authManager.isSpaceManager(sp, user) ||
                                authManager.isDefaultRole(sp, user,
                                                          HugeDefaultRole.ANALYST);
            if (gs.auth() && !isManager) {
                continue;
            }
            Map<String, Object> gsProfile = gs.info();
            gsProfile.put("create_time", format.format(gs.createTime()));
            gsProfile.put("update_time", format.format(gs.updateTime()));
            if (!isPrefix(gsProfile, prefix)) {
                continue;
            }

            boolean defaulted = StringUtils.equals(sp, defaultSpace);
            gsProfile.put("default", defaulted);
            if (defaulted) {
                result.add(gsProfile);
            } else {
                spaceList.add(gsProfile);
            }
        }
        result.addAll(spaceList);
        return result;
    }

    public boolean isPrefix(Map<String, Object> profile, String prefix) {
        if (StringUtils.isEmpty(prefix)) {
            return true;
        }
        // graph name or nickname is not empty
        String name = profile.get("name").toString();
        String nickname = profile.get("nickname").toString();
        return name.startsWith(prefix) || nickname.startsWith(prefix);
    }

    @GET
    @Timed
    @Path("{name}/default")
    @Produces(APPLICATION_JSON_WITH_CHARSET)
    public Object setDefault(@Context GraphManager manager,
                             @PathParam("name") String name) {
        LOGGER.debug("Set default space by graph space {}", name);
        AuthManager authManager = manager.authManager();
        String user = authManager.username();
        String originSpace = authManager.getDefaultSpace(user);
        if (StringUtils.isNotEmpty(originSpace)) {
            authManager.unsetDefaultSpace(originSpace, user);
        }
        authManager.setDefaultSpace(name, user);
        return ImmutableMap.of("name", user, "default_space", name);
    }

    @GET
    @Timed
    @Path("/default")
    @Produces(APPLICATION_JSON_WITH_CHARSET)
    public String getDefault(@Context GraphManager manager) {
        LOGGER.debug("Get default space if exists, return null otherwise");
        AuthManager authManager = manager.authManager();
        String user = authManager.username();
        String result = authManager.getDefaultSpace(user);
        if (StringUtils.isNotEmpty(result)) {
            GraphSpace exist = manager.graphSpace(result);
            if (exist == null) {
                authManager.unsetDefaultSpace(result, user);
                result = null;
            }
        }

        result = (result == null) ? "" : result;
        return manager.serializer().writeMap(
                ImmutableMap.of("name", user, "default_space", result));
    }

    @PUT
    @Timed
    @Path("{name}")
    @Consumes(APPLICATION_JSON)
    @Produces(APPLICATION_JSON_WITH_CHARSET)
    @RolesAllowed("admin")
    public Map<String, Object> manage(@Context GraphManager manager,
                                      @PathParam("name") String name,
                                      Map<String, Object> actionMap) {
        LOGGER.debug("Manage graph space with action {}", actionMap);

        E.checkArgument(actionMap != null && actionMap.size() == 2 &&
                        actionMap.containsKey(GRAPH_SPACE_ACTION),
                        "Invalid request body '%s'", actionMap);
        Object value = actionMap.get(GRAPH_SPACE_ACTION);
        E.checkArgument(value instanceof String,
                        "Invalid action type '%s', must be string",
                        value.getClass());
        String action = (String) value;
        switch (action) {
            case "update":
                LOGGER.debug("Update graph space: '{}'", name);

                E.checkArgument(actionMap.containsKey(UPDATE),
                                "Please pass '%s' for graph space update",
                                UPDATE);
                value = actionMap.get(UPDATE);
                E.checkArgument(value instanceof Map,
                                "The '%s' must be map, but got %s",
                                UPDATE, value.getClass());
                @SuppressWarnings("unchecked")
                Map<String, Object> graphSpaceMap = (Map<String, Object>) value;
                String gsName = (String) graphSpaceMap.get("name");
                E.checkArgument(gsName.equals(name),
                                "Different name in update body with in path");
                GraphSpace exist = manager.graphSpace(name);
                if (exist == null) {
                    throw new NotFoundException(
                            "Can't find graph space with name '%s'", gsName);
                }

                String nickname = (String) graphSpaceMap.get("nickname");
                if (!Strings.isEmpty(nickname)) {
                    GraphManager.checkNickname(nickname);
                    exist.nickname(nickname);
                }

                String description = (String) graphSpaceMap.get("description");
                if (!Strings.isEmpty(description)) {
                    exist.description(description);
                }

                int maxGraphNumber =
                        (int) graphSpaceMap.get("max_graph_number");
                if (maxGraphNumber != 0) {
                    exist.maxGraphNumber(maxGraphNumber);
                }
                int maxRoleNumber = (int) graphSpaceMap.get("max_role_number");
                if (maxRoleNumber != 0) {
                    exist.maxRoleNumber(maxRoleNumber);
                }

                int cpuLimit = (int) graphSpaceMap.get("cpu_limit");
                if (cpuLimit != 0) {
                    exist.cpuLimit(cpuLimit);
                }
                int memoryLimit = (int) graphSpaceMap.get("memory_limit");
                if (memoryLimit != 0) {
                    exist.memoryLimit(memoryLimit);
                }
                int storageLimit = (int) graphSpaceMap.get("storage_limit");
                if (storageLimit != 0) {
                    exist.storageLimit = storageLimit;
                }

                int computeCpuLimit = (int) graphSpaceMap
                        .getOrDefault("compute_cpu_limit", 0);
                if (computeCpuLimit != 0) {
                    exist.computeCpuLimit(computeCpuLimit);
                }
                int computeMemoryLimit = (int) graphSpaceMap
                        .getOrDefault("compute_memory_limit", 0);
                if (computeMemoryLimit != 0) {
                    exist.computeMemoryLimit(computeMemoryLimit);
                }

                String oltpNamespace =
                        (String) graphSpaceMap.get("oltp_namespace");
                if (oltpNamespace != null &&
                    !Strings.isEmpty(oltpNamespace)) {
                    exist.oltpNamespace(oltpNamespace);
                }
                String olapNamespace =
                        (String) graphSpaceMap.get("olap_namespace");
                if (olapNamespace != null &&
                    !Strings.isEmpty(olapNamespace)) {
                    exist.olapNamespace(olapNamespace);
                }
                String storageNamespace =
                        (String) graphSpaceMap.get("storage_namespace");
                if (storageNamespace != null &&
                    !Strings.isEmpty(storageNamespace)) {
                    exist.storageNamespace(storageNamespace);
                }

                String operatorImagePath = (String) graphSpaceMap
                        .getOrDefault("operator_image_path", "");
                if (!Strings.isEmpty(operatorImagePath)) {
                    exist.operatorImagePath(operatorImagePath);
                }

                String internalAlgorithmImageUrl = (String) graphSpaceMap
                        .getOrDefault("internal_algorithm_image_url", "");
                if (!Strings.isEmpty(internalAlgorithmImageUrl)) {
                    exist.internalAlgorithmImageUrl(internalAlgorithmImageUrl);
                }

                @SuppressWarnings("unchecked")
                Map<String, Object> configs =
                        (Map<String, Object>) graphSpaceMap.get("configs");
                if (configs != null && !configs.isEmpty()) {
                    exist.configs(configs);
                }
                exist.refreshUpdate();
                GraphSpace space = manager.createGraphSpace(exist);
                // TODO: duplicated log
                //LOGGER.getAuditLogger().logUpdateTenant(exist.name(),
                //                                        manager.authManager().username());
                return space.info();
            case GRAPH_SPACE_ACTION_CLEAR:
                LOGGER.debug("Clear graph space: '{}'", name);

                manager.clearGraphSpace(name);
                // TODO: duplicated log
                //LOGGER.getAuditLogger().logUpdateTenant(name,
                //                                        manager.authManager().username());
                return ImmutableMap.of(name, "cleared");
            default:
                throw new AssertionError(String.format("Invalid action: '%s'",
                                                       action));
        }
    }

    @DELETE
    @Timed
    @Path("{name}")
    @Produces(APPLICATION_JSON_WITH_CHARSET)
    @RolesAllowed({"admin"})
    public void delete(@Context GraphManager manager,
                       @PathParam("name") String name) {
        LOGGER.debug("Remove graph space by name '{}'", name);
        // TODO: duplicated log
        //LOGGER.getAuditLogger().logRemoveTenant(name, manager.authManager().username());
        manager.dropGraphSpace(name);
    }


    private static class JsonGraphSpace implements Checkable {

        @JsonProperty("name")
        public String name;
        @JsonProperty("nickname")
        public String nickname;
        @JsonProperty("description")
        public String description;

        @JsonProperty("cpu_limit")
        public int cpuLimit;
        @JsonProperty("memory_limit")
        public int memoryLimit;
        @JsonProperty("storage_limit")
        public int storageLimit;

        @JsonProperty("compute_cpu_limit")
        public int computeCpuLimit = 0;
        @JsonProperty("compute_memory_limit")
        public int computeMemoryLimit = 0;

        @JsonProperty("oltp_namespace")
        public String oltpNamespace = "";
        @JsonProperty("olap_namespace")
        public String olapNamespace = "";
        @JsonProperty("storage_namespace")
        public String storageNamespace = "";

        @JsonProperty("max_graph_number")
        public int maxGraphNumber;
        @JsonProperty("max_role_number")
        public int maxRoleNumber;

        @JsonProperty("auth")
        public boolean auth = false;

        @JsonProperty("configs")
        public Map<String, Object> configs;

        @JsonProperty("operator_image_path")
        public String operatorImagePath = "";

        @JsonProperty("internal_algorithm_image_url")
        public String internalAlgorithmImageUrl = "";

        @Override
        public void checkCreate(boolean isBatch) {
            E.checkArgument(!StringUtils.isEmpty(this.name),
                            "The name of graph space can't be null or empty");
            E.checkArgument(this.maxGraphNumber > 0,
                            "The max graph number must > 0");

            E.checkArgument(this.cpuLimit > 0,
                            "The cpu limit must be > 0, but got: %s",
                            this.cpuLimit);
            E.checkArgument(this.memoryLimit > 0,
                            "The memory limit must be > 0, but got: %s",
                            this.memoryLimit);
            E.checkArgument(this.storageLimit > 0,
                            "The storage limit must be > 0, but got: %s",
                            this.storageLimit);
            if (this.oltpNamespace == null) {
                this.oltpNamespace = "";
            }
            if (this.olapNamespace == null) {
                this.olapNamespace = "";
            }
            if (this.storageNamespace == null) {
                this.storageNamespace = "";
            }
        }

        public GraphSpace toGraphSpace(String creator) {
            GraphSpace graphSpace = new GraphSpace(this.name,
                                                   this.nickname,
                                                   this.description,
                                                   this.cpuLimit,
                                                   this.memoryLimit,
                                                   this.storageLimit,
                                                   this.maxGraphNumber,
                                                   this.maxRoleNumber,
                                                   this.auth,
                                                   creator,
                                                   this.configs);
            graphSpace.oltpNamespace(this.oltpNamespace);
            graphSpace.olapNamespace(this.olapNamespace);
            graphSpace.storageNamespace(this.storageNamespace);
            graphSpace.computeCpuLimit(this.computeCpuLimit);
            graphSpace.computeMemoryLimit(this.computeMemoryLimit);
            graphSpace.operatorImagePath(this.operatorImagePath);
            graphSpace.internalAlgorithmImageUrl(this.internalAlgorithmImageUrl);

            graphSpace.configs(this.configs);

            return graphSpace;
        }

        public String toString() {
            return String.format("JsonGraphSpace{name=%s, description=%s, " +
                                 "cpuLimit=%s, memoryLimit=%s, " +
                                 "storageLimit=%s, oltpNamespace=%s" +
                                 "olapNamespace=%s, storageNamespace=%s" +
                                 "maxGraphNumber=%s, maxRoleNumber=%s, " +
                                 "configs=%s, operatorImagePath=%s, " +
                                 "internalAlgorithmImageUrl=%s}", this.name,
                                 this.description, this.cpuLimit,
                                 this.memoryLimit, this.storageLimit,
                                 this.oltpNamespace, this.olapNamespace,
                                 this.storageLimit, this.maxGraphNumber,
                                 this.maxRoleNumber, this.configs,
                                 this.operatorImagePath,
                                 this.internalAlgorithmImageUrl);
        }
    }

    private static class JsonDefaultRole implements Checkable {

        @JsonProperty("user")
        private String user;
        @JsonProperty("role")
        private String role;
        @JsonProperty("graph")
        private String graph;

        @Override
        public void checkCreate(boolean isBatch) {
        }

        @Override
        public void checkUpdate() {
        }
    }
}
