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

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class ChaosReport {

    private String scenarioName;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private boolean passed;
    private Throwable error;
    private List<StepResult> stepResults = new ArrayList<>();

    public ChaosReport(String scenarioName) {
        this.scenarioName = scenarioName;
    }

    public String getScenarioName() {
        return scenarioName;
    }

    public void setScenarioName(String scenarioName) {
        this.scenarioName = scenarioName;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalDateTime startTime) {
        this.startTime = startTime;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }

    public void setEndTime(LocalDateTime endTime) {
        this.endTime = endTime;
    }

    public boolean isPassed() {
        return passed;
    }

    public void setPassed(boolean passed) {
        this.passed = passed;
    }

    public Throwable getError() {
        return error;
    }

    public void setError(Throwable error) {
        this.error = error;
    }

    public List<StepResult> getStepResults() {
        return stepResults;
    }

    public void setStepResults(List<StepResult> stepResults) {
        this.stepResults = stepResults;
    }

    public void addStepResult(StepResult result) {
        this.stepResults.add(result);
    }

    public Duration getDuration() {
        if (startTime == null || endTime == null) {
            return Duration.ZERO;
        }
        return Duration.between(startTime, endTime);
    }

    public static class StepResult {

        private String stepName;
        private StepType stepType;
        private boolean passed;
        private boolean skipped;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        private String message;
        private Throwable error;

        public String getStepName() {
            return stepName;
        }

        public void setStepName(String stepName) {
            this.stepName = stepName;
        }

        public StepType getStepType() {
            return stepType;
        }

        public void setStepType(StepType stepType) {
            this.stepType = stepType;
        }

        public boolean isPassed() {
            return passed;
        }

        public void setPassed(boolean passed) {
            this.passed = passed;
        }

        public boolean isSkipped() {
            return skipped;
        }

        public void setSkipped(boolean skipped) {
            this.skipped = skipped;
        }

        public LocalDateTime getStartTime() {
            return startTime;
        }

        public void setStartTime(LocalDateTime startTime) {
            this.startTime = startTime;
        }

        public LocalDateTime getEndTime() {
            return endTime;
        }

        public void setEndTime(LocalDateTime endTime) {
            this.endTime = endTime;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public Throwable getError() {
            return error;
        }

        public void setError(Throwable error) {
            this.error = error;
        }

        public Duration getDuration() {
            if (startTime == null || endTime == null) {
                return Duration.ZERO;
            }
            return Duration.between(startTime, endTime);
        }
    }
}
