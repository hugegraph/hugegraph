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

package org.apache.hugegraph.chaos.model;

import java.util.Map;

public class Step {

    private String name;
    private StepType type;
    private StepAction action;
    private String duration;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public StepType getType() {
        return type;
    }

    public void setType(StepType type) {
        this.type = type;
    }

    public StepAction getAction() {
        return action;
    }

    public void setAction(StepAction action) {
        this.action = action;
    }

    public String getDuration() {
        return duration;
    }

    public void setDuration(String duration) {
        this.duration = duration;
    }

    public static class StepAction {

        private String type;
        private String target;
        private String url;
        private Integer expectedStatus;
        private String duration;
        private RecoveryPolicy recovery;
        private Map<String, Object> params;
        private String query;

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getTarget() {
            return target;
        }

        public void setTarget(String target) {
            this.target = target;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public Integer getExpectedStatus() {
            return expectedStatus;
        }

        public void setExpectedStatus(Integer expectedStatus) {
            this.expectedStatus = expectedStatus;
        }

        public String getDuration() {
            return duration;
        }

        public void setDuration(String duration) {
            this.duration = duration;
        }

        public RecoveryPolicy getRecovery() {
            return recovery;
        }

        public void setRecovery(RecoveryPolicy recovery) {
            this.recovery = recovery;
        }

        public Map<String, Object> getParams() {
            return params;
        }

        public void setParams(Map<String, Object> params) {
            this.params = params;
        }

        public String getQuery() {
            return query;
        }

        public void setQuery(String query) {
            this.query = query;
        }
    }
}
