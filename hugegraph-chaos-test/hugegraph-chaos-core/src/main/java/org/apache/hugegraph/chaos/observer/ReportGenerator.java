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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.hugegraph.chaos.model.ChaosConfig;
import org.apache.hugegraph.chaos.model.ChaosReport;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class ReportGenerator {

    private static final Logger LOG = LogManager.getLogger(ReportGenerator.class);
    private static final DateTimeFormatter FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static void generate(ChaosReport report, ChaosConfig.ReportConfig config) {
        if (config == null) {
            config = defaultConfig();
        }
        String outputDir = config.getOutputDir() != null
                           ? config.getOutputDir()
                           : "target/chaos-reports";
        new File(outputDir).mkdirs();

        List<String> formats = config.getFormat();
        if (formats == null || formats.isEmpty()) {
            formats = List.of("html", "json");
        }

        for (String format : formats) {
            switch (format.toLowerCase()) {
                case "html":
                    generateHtml(report, outputDir);
                    break;
                case "json":
                    generateJson(report, outputDir);
                    break;
                case "markdown":
                    generateMarkdown(report, outputDir);
                    break;
                default:
                    LOG.warn("Unknown report format: {}", format);
            }
        }
    }

    private static void generateHtml(ChaosReport report, String outputDir) {
        String path = outputDir + "/report.html";
        try (FileWriter writer = new FileWriter(path)) {
            writer.write(HtmlTemplate.render(report));
            LOG.info("HTML report generated: {}", path);
        } catch (IOException e) {
            LOG.error("Failed to generate HTML report", e);
        }
    }

    private static void generateJson(ChaosReport report, String outputDir) {
        String path = outputDir + "/report.json";
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        try {
            mapper.writeValue(new File(path), report);
            LOG.info("JSON report generated: {}", path);
        } catch (IOException e) {
            LOG.error("Failed to generate JSON report", e);
        }
    }

    private static void generateMarkdown(ChaosReport report, String outputDir) {
        String path = outputDir + "/report.md";
        try (FileWriter writer = new FileWriter(path)) {
            writer.append("# Chaos Test Report\n\n");
            writer.append("## Summary\n");
            writer.append("- **Scenario**: ").append(report.getScenarioName()).append("\n");
            writer.append("- **Status**: ")
                  .append(report.isPassed() ? "PASSED" : "FAILED")
                  .append("\n");
            long skippedCount = report.getStepResults().stream()
                                      .filter(ChaosReport.StepResult::isSkipped).count();
            if (skippedCount > 0) {
                writer.append("- **Skipped**: ")
                      .append(String.valueOf(skippedCount))
                      .append(" step(s) skipped (environment limitations)\n");
            }
            if (report.getStartTime() != null) {
                writer.append("- **Start**: ")
                      .append(report.getStartTime().format(FMT))
                      .append("\n");
            }
            if (report.getEndTime() != null) {
                writer.append("- **End**: ")
                      .append(report.getEndTime().format(FMT))
                      .append("\n");
            }
            writer.append("- **Duration**: ")
                  .append(String.valueOf(report.getDuration().getSeconds()))
                  .append("s\n\n");

            if (report.getError() != null) {
                writer.append("## Error\n");
                writer.append("```\n");
                writer.append(report.getError().getMessage()).append("\n");
                writer.append("```\n\n");
            }

            writer.append("## Steps\n");
            for (ChaosReport.StepResult result : report.getStepResults()) {
                writer.append("### ").append(result.getStepName()).append("\n");
                writer.append("- **Type**: ").append(String.valueOf(result.getStepType()))
                      .append("\n");
                writer.append("- **Status**: ")
                      .append(result.isSkipped() ? "SKIPPED"
                             : result.isPassed() ? "PASSED" : "FAILED")
                      .append("\n");
                writer.append("- **Duration**: ")
                      .append(String.valueOf(result.getDuration().getSeconds()))
                      .append("s\n");
                if (result.getMessage() != null) {
                    writer.append("- **Message**: ").append(result.getMessage()).append("\n");
                }
                writer.append("\n");
            }
            LOG.info("Markdown report generated: {}", path);
        } catch (IOException e) {
            LOG.error("Failed to generate Markdown report", e);
        }
    }

    private static ChaosConfig.ReportConfig defaultConfig() {
        ChaosConfig.ReportConfig config = new ChaosConfig.ReportConfig();
        config.setFormat(List.of("html", "json"));
        config.setOutputDir("target/chaos-reports");
        config.setIncludeLogs(true);
        return config;
    }
}
