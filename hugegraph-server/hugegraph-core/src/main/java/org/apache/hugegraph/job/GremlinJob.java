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

package org.apache.hugegraph.job;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.apache.hugegraph.backend.query.Query;
import org.apache.hugegraph.exception.LimitExceedException;
import org.apache.hugegraph.security.script.ScriptBindings;
import org.apache.hugegraph.security.script.ScriptJobContext;
import org.apache.hugegraph.security.script.ScriptPolicyRuntime;
import org.apache.hugegraph.traversal.optimize.HugeScriptTraversal;
import org.apache.hugegraph.util.E;
import org.apache.hugegraph.util.JsonUtil;

public class GremlinJob extends UserJob<Object> {

    public static final String TASK_TYPE = "gremlin";
    public static final String TASK_BIND_NAME = "gremlinJob";
    public static final int TASK_RESULTS_MAX_SIZE = (int) Query.DEFAULT_CAPACITY;

    @Override
    public String type() {
        return TASK_TYPE;
    }

    @Override
    public Object execute() throws Exception {
        String input = this.task().input();
        E.checkArgumentNotNull(input, "The input can't be null");
        @SuppressWarnings("unchecked")
        Map<String, Object> map = JsonUtil.fromJson(input, Map.class);

        Object value = map.get("gremlin");
        E.checkArgument(value instanceof String,
                        "Invalid gremlin value '%s'", value);
        String gremlin = (String) value;

        value = map.get("bindings");
        E.checkArgument(value instanceof Map,
                        "Invalid bindings value '%s'", value);
        @SuppressWarnings("unchecked")
        Map<String, Object> bindings = (Map<String, Object>) value;

        value = map.get("language");
        E.checkArgument(value instanceof String,
                        "Invalid language value '%s'", value);
        String language = (String) value;

        value = map.get("aliases");
        E.checkArgument(value instanceof Map,
                        "Invalid aliases value '%s'", value);
        @SuppressWarnings("unchecked")
        Map<String, String> aliases = (Map<String, String>) value;

        if (ScriptPolicyRuntime.enabled()) {
            bindings = ScriptBindings.client(bindings);
        }
        bindings.put(TASK_BIND_NAME, new GremlinJobProxy());

        HugeScriptTraversal<?, ?> traversal = new HugeScriptTraversal<>(
                this.graph().traversal(),
                language, gremlin,
                bindings, aliases);
        List<Object> results = new ArrayList<>();
        long capacity = Query.defaultCapacity(Query.NO_CAPACITY);
        boolean succeeded = false;
        try {
            while (traversal.hasNext()) {
                Object result = traversal.next();
                results.add(result);
                checkResultsSize(results);
                Thread.yield();
            }
            if (ScriptPolicyRuntime.enabled() && traversal.result() != null) {
                checkResultsSize(traversal.result());
            }
            succeeded = true;
        } finally {
            Query.defaultCapacity(capacity);
            if (!ScriptPolicyRuntime.enabled()) {
                traversal.close();
                this.graph().tx().commit();
            } else {
                try {
                    traversal.close();
                } catch (Exception error) {
                    succeeded = false;
                    throw error;
                } finally {
                    if (succeeded) {
                        this.graph().tx().commit();
                    } else {
                        this.graph().tx().rollback();
                    }
                }
            }
        }

        Object result = traversal.result();
        if (result != null) {
            checkResultsSize(result);
            return result;
        } else {
            return results;
        }
    }

    private void checkResultsSize(Object results) {
        int size = 0;
        if (results instanceof Collection) {
            size = ((Collection<?>) results).size();
        }
        if (size > TASK_RESULTS_MAX_SIZE) {
            throw new LimitExceedException(
                    "Job results size %s has exceeded the max limit %s",
                    size, TASK_RESULTS_MAX_SIZE);
        }
    }

    /**
     * Used by gremlin script
     */
    @SuppressWarnings("unused")
    private class GremlinJobProxy implements ScriptJobContext {

        public void setMinSaveInterval(long seconds) {
            GremlinJob.this.setMinSaveInterval(seconds);
        }

        public void updateProgress(int progress) {
            GremlinJob.this.updateProgress(progress);
        }

        public int progress() {
            return GremlinJob.this.progress();
        }
    }
}
