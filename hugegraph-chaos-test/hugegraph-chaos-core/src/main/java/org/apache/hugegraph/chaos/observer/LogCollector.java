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

package org.apache.hugegraph.chaos.observer;

import org.apache.hugegraph.chaos.model.ChaosConfig;
import org.apache.hugegraph.chaos.model.ChaosReport;
import org.apache.hugegraph.chaos.model.Step;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.format.DateTimeFormatter;

public class LogCollector {

    private static final Logger LOG = LogManager.getLogger(LogCollector.class);
    private static final DateTimeFormatter FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final String logDir;
    private final boolean enabled;

    public LogCollector(ChaosConfig.ReportConfig reportConfig) {
        if (reportConfig != null && reportConfig.isIncludeLogs()) {
            this.logDir = reportConfig.getOutputDir() != null
                          ? reportConfig.getOutputDir() + "/logs"
                          : "target/chaos-reports/logs";
            this.enabled = true;
            new File(this.logDir).mkdirs();
        } else {
            this.logDir = null;
            this.enabled = false;
        }
    }

    public void collect(Step step, ChaosReport.StepResult result) {
        if (!enabled) {
            return;
        }
        String fileName = String.format("%s/%s-%d.log",
                                        logDir, step.getName(),
                                        System.currentTimeMillis());
        try (PrintWriter writer = new PrintWriter(new FileWriter(fileName))) {
            writer.println("Step: " + step.getName());
            writer.println("Type: " + step.getType());
            if (result.getStartTime() != null) {
                writer.println("Start: " + result.getStartTime().format(FMT));
            }
            if (result.getEndTime() != null) {
                writer.println("End: " + result.getEndTime().format(FMT));
            }
            writer.println("DurationMs: " + result.getDuration().toMillis());
            writer.println("Status: " + (result.isPassed() ? "PASSED" : "FAILED"));
            if (result.getMessage() != null) {
                writer.println("Message: " + result.getMessage());
            }
            if (result.getError() != null) {
                writer.println("Error: " + result.getError().getMessage());
                result.getError().printStackTrace(writer);
            }
            LOG.debug("Collected log for step {} to {}", step.getName(), fileName);
        } catch (IOException e) {
            LOG.error("Failed to write log for step: {}", step.getName(), e);
        }
    }
}
