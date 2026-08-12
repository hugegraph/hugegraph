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

package org.apache.hugegraph.chaos;

import org.apache.hugegraph.chaos.config.YamlConfigLoader;
import org.apache.hugegraph.chaos.engine.ChaosEngine;
import org.apache.hugegraph.chaos.model.ChaosConfig;
import org.apache.hugegraph.chaos.model.ChaosReport;
import org.apache.hugegraph.chaos.model.TargetEnv;
import org.apache.hugegraph.chaos.observer.ReportGenerator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class ChaosTestRunner {

    private static final Logger LOG = LogManager.getLogger(ChaosTestRunner.class);

    public static void main(String[] args) {
        String configPath = System.getProperty("chaos.config",
                                                "classpath:scenarios/default.yaml");
        LOG.info("ChaosTestRunner starting with config: {}", configPath);

        ChaosConfig config = YamlConfigLoader.load(configPath);
        applySystemPropertyOverrides(config);
        ChaosEngine engine = new ChaosEngine(config);
        ChaosReport report = engine.run();

        ReportGenerator.generate(report, config.getReport());

        int exitCode = report.isPassed() ? 0 : 1;
        LOG.info("Chaos test finished. Passed={}, exitCode={}",
                 report.isPassed(), exitCode);
        System.exit(exitCode);
    }

    private static void applySystemPropertyOverrides(ChaosConfig config) {
        String reportFormat = System.getProperty("chaos.report.format");
        if (reportFormat != null && !reportFormat.isEmpty()) {
            List<String> formats = Arrays.stream(reportFormat.split(","))
                                         .map(String::trim)
                                         .filter(s -> !s.isEmpty())
                                         .collect(Collectors.toList());
            if (!formats.isEmpty()) {
                if (config.getReport() == null) {
                    config.setReport(new ChaosConfig.ReportConfig());
                }
                config.getReport().setFormat(formats);
                LOG.info("Overriding report format from system property: {}", formats);
            }
        }

        String targetEnv = System.getProperty("chaos.target");
        if (targetEnv != null && !targetEnv.isEmpty()) {
            try {
                TargetEnv env = TargetEnv.valueOf(targetEnv.toUpperCase());
                if (config.getTarget() == null) {
                    config.setTarget(new ChaosConfig.TargetConfig());
                }
                config.getTarget().setEnv(env);
                LOG.info("Overriding target env from system property: {}", env);
            } catch (IllegalArgumentException e) {
                LOG.warn("Unknown target env: {}", targetEnv);
            }
        }

        String outputDir = System.getProperty("chaos.report.outputDir");
        if (outputDir != null && !outputDir.isEmpty()) {
            if (config.getReport() == null) {
                config.setReport(new ChaosConfig.ReportConfig());
            }
            config.getReport().setOutputDir(outputDir);
            LOG.info("Overriding report output dir from system property: {}", outputDir);
        }
    }
}
