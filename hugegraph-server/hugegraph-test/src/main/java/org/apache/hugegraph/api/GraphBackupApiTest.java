/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership.
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

package org.apache.hugegraph.api;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.apache.commons.io.FileUtils;
import org.apache.hugegraph.util.JsonUtil;
import org.junit.Assert;
import org.junit.Test;

import com.google.common.collect.ImmutableMap;

import jakarta.ws.rs.core.Response;

public class GraphBackupApiTest extends BaseApiTest {

    private static final long TASK_TIMEOUT_MILLIS = 120000L;

    @Test
    public void testCreateAndRestoreSelectedGraphVersions() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String graph = "backup" + suffix.substring(0, 12);
        Path root = Files.createTempDirectory("hugegraph-backup-e2e-");
        String graphPath = "graphspaces/DEFAULT/graphs/" + graph;
        String backupPath = graphPath + "/backups";

        try {
            Map<String, Object> configMap = new LinkedHashMap<>();
            configMap.put("backend", "rocksdb");
            configMap.put("serializer", "binary");
            configMap.put("store", graph);
            configMap.put("rocksdb.data_path", root.resolve("data").toString());
            configMap.put("rocksdb.wal_path", root.resolve("wal").toString());
            configMap.put("backup.repository_root", root.resolve("repo").toString());
            String config = JsonUtil.toJson(configMap);
            assertResponseStatus(201, client().post(graphPath, config));

            String propertyKeys = graphPath + "/schema/propertykeys";
            assertResponseStatus(201, client().post(propertyKeys,
                    "{\"name\":\"name\",\"data_type\":\"TEXT\","
                    + "\"cardinality\":\"SINGLE\"}"));
            assertResponseStatus(201, client().post(propertyKeys,
                    "{\"name\":\"age\",\"data_type\":\"INT\","
                    + "\"cardinality\":\"SINGLE\"}"));

            String vertexLabels = graphPath + "/schema/vertexlabels";
            assertResponseStatus(201, client().post(vertexLabels,
                    "{\"name\":\"person\",\"id_strategy\":\"PRIMARY_KEY\","
                    + "\"properties\":[\"name\",\"age\"],"
                    + "\"primary_keys\":[\"name\"]}"));

            String vertices = graphPath + "/graph/vertices";
            String firstId = createVertex(vertices, "first");
            String firstVersion = createBackup(backupPath, "e2e");

            String secondId = createVertex(vertices, "second");
            String secondVersion = createBackup(backupPath, "e2e");

            assertResponseStatus(204, client().delete(vertices, quoteId(firstId)));
            assertStatus(404, client().get(vertices, quoteId(firstId)));

            restore(backupPath, "e2e", firstVersion);
            assertResponseStatus(200, client().get(vertices, quoteId(firstId)));
            assertStatus(404, client().get(vertices, quoteId(secondId)));

            restore(backupPath, "e2e", secondVersion);
            assertResponseStatus(200, client().get(vertices, quoteId(firstId)));
            assertResponseStatus(200, client().get(vertices, quoteId(secondId)));

            Response schema = client().get(graphPath + "/schema/vertexlabels", "person");
            String schemaBody = assertResponseStatus(200, schema);
            Assert.assertTrue(schemaBody, schemaBody.contains("age"));

            Response list = client().get(backupPath, ImmutableMap.of("repository", "e2e"));
            String listBody = assertResponseStatus(200, list);
            Assert.assertTrue(listBody, listBody.contains(firstVersion));
            Assert.assertTrue(listBody, listBody.contains(secondVersion));
        } finally {
            client().delete(graphPath, ImmutableMap.of(
                    "confirm_message", "I'm sure to drop the graph"));
            FileUtils.deleteDirectory(root.toFile());
        }
    }

    private String createVertex(String path, String name) {
        String body = "{\"label\":\"person\",\"properties\":{"
                      + "\"name\":\"" + name + "\",\"age\":30}}";
        String response = assertResponseStatus(201, client().post(path, body));
        Map<?, ?> result = JsonUtil.fromJson(response, Map.class);
        return String.valueOf(result.get("id"));
    }

    private String createBackup(String path, String repository) {
        String response = assertResponseStatus(200, client().post(
                path, "{\"repository\":\"" + repository + "\",\"keep_num\":0}"));
        long taskId = taskId(response);
        String graphPath = path.substring(0, path.length() - "/backups".length());
        waitBackupTask(graphPath, taskId);

        Response task = client().get(graphPath + "/tasks",
                                     String.valueOf(taskId));
        String taskBody = assertResponseStatus(200, task);
        Object taskResult = assertJsonContains(taskBody, "task_result");
        Map<?, ?> resultMap = taskResult instanceof String ?
                               JsonUtil.fromJson((String) taskResult, Map.class) :
                               (Map<?, ?>) taskResult;
        return String.valueOf(resultMap.get("backup_id"));
    }

    private void restore(String path, String repository, String backupId) {
        String response = assertResponseStatus(200, client().post(
                path + "/restore", "{\"repository\":\"" + repository
                                   + "\",\"backup_id\":\"" + backupId
                                   + "\",\"confirm\":true}"));
        String graphPath = path.substring(0, path.length() - "/backups".length());
        waitBackupTask(graphPath, taskId(response));
    }

    private void waitBackupTask(String graphPath, long taskId) {
        long deadline = System.currentTimeMillis() + TASK_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            Response response = client().get(graphPath + "/tasks",
                                             String.valueOf(taskId));
            String body = assertResponseStatus(200, response);
            String status = assertJsonContains(body, "task_status");
            if ("success".equals(status)) {
                return;
            }
            if ("failed".equals(status) || "cancelled".equals(status)) {
                Assert.fail("Graph backup task " + taskId + " ended as " + status
                            + ": " + body);
            }
            try {
                Thread.sleep(100L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for backup task",
                                         e);
            }
        }
        Assert.fail("Graph backup task " + taskId + " timed out");
    }

    private static long taskId(String response) {
        Map<?, ?> result = JsonUtil.fromJson(response, Map.class);
        return ((Number) result.get("task_id")).longValue();
    }

    private static String quoteId(String id) {
        return "\"" + id + "\"";
    }

    private static void assertStatus(int status, Response response) {
        String body = response.readEntity(String.class);
        Assert.assertEquals("Response body: " + body, status, response.getStatus());
    }
}
