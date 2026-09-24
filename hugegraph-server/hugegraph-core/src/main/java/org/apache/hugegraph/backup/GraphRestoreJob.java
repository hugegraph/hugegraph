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

import java.util.Map;

import org.apache.hugegraph.util.E;

public class GraphRestoreJob extends GraphBackupJob {

    @Override
    public String type() {
        return RESTORE_TASK_TYPE;
    }

    @Override
    public Map<String, Object> execute() {
        Map<String, Object> request = this.request();
        String operation = string(request, "operation");
        E.checkArgument(RESTORE.equals(operation),
                        "Unsupported graph backup operation '%s'", operation);
        String repository = string(request, "repository");
        Object value = request.get("backup_id");
        E.checkArgument(value == null || value instanceof String,
                        "Backup id must be a string");
        return this.graph().backupService().restore(repository, (String) value);
    }
}
