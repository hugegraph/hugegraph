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

package org.apache.hugegraph.backup;

import java.math.BigDecimal;
import java.util.Map;

import org.apache.hugegraph.job.SysJob;
import org.apache.hugegraph.util.E;
import org.apache.hugegraph.util.JsonUtil;

/**
 * Durable task used by the graph backup API. The request is kept in the task
 * input so a pending operation can be reconstructed after a server restart.
 */
public class GraphBackupJob extends SysJob<Map<String, Object>> {

    public static final String BACKUP = "backup";
    public static final String RESTORE = "restore";
    public static final String BACKUP_TASK_TYPE = "graph_backup";
    public static final String RESTORE_TASK_TYPE = "graph_restore";

    @Override
    public String type() {
        return BACKUP_TASK_TYPE;
    }

    @Override
    public Map<String, Object> execute() {
        Map<String, Object> request = this.request();
        String operation = string(request, "operation");
        String repository = string(request, "repository");
        E.checkArgument(BACKUP.equals(operation),
                        "Unsupported graph backup operation '%s'", operation);
        return this.graph().backupService().create(repository,
                                                   integer(request, "keep_num"));
    }

    @SuppressWarnings("unchecked")
    protected Map<String, Object> request() {
        String input = this.task().input();
        E.checkArgumentNotNull(input, "The graph backup task input can't be null");
        return JsonUtil.fromJson(input, Map.class);
    }

    protected static String string(Map<String, Object> request, String key) {
        Object value = request.get(key);
        E.checkArgument(value instanceof String &&
                        !((String) value).trim().isEmpty(),
                        "Graph backup request must contain a non-blank '%s'", key);
        return ((String) value).trim();
    }

    private static int integer(Map<String, Object> request, String key) {
        Object value = request.get(key);
        E.checkArgument(value instanceof Number, "'%s' must be numeric", key);
        BigDecimal decimal = new BigDecimal(value.toString());
        E.checkArgument(decimal.scale() <= 0,
                        "'%s' must be an integer", key);
        try {
            return decimal.intValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(String.format(
                    "'%s' is outside the supported integer range", key), e);
        }
    }
}
