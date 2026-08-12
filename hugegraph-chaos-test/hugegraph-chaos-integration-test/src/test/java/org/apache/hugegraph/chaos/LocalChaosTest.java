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
import org.apache.hugegraph.chaos.observer.ReportGenerator;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class LocalChaosTest {

    @Test
    public void testLocalStressScenario() {
        String configPath = System.getProperty("chaos.config",
            "classpath:scenarios/local-stress-test.yaml");
        ChaosConfig config = YamlConfigLoader.load(configPath);
        ChaosEngine engine = new ChaosEngine(config);
        ChaosReport report = engine.run();
        ReportGenerator.generate(report, config.getReport());
        assertNotNull(report);
        assertTrue("Local stress scenario should pass", report.isPassed());
        assertEquals(4, report.getStepResults().size());
    }

    @Test
    public void testFrameworkValidationScenario() {
        String configPath = System.getProperty("chaos.config",
            "classpath:scenarios/local-framework-validation.yaml");
        ChaosConfig config = YamlConfigLoader.load(configPath);
        ChaosEngine engine = new ChaosEngine(config);
        ChaosReport report = engine.run();
        ReportGenerator.generate(report, config.getReport());
        assertNotNull(report);
        assertTrue("Framework validation scenario should pass", report.isPassed());
    }

    @Test
    public void testCpuStressScenario() {
        String configPath = System.getProperty("chaos.config",
            "classpath:scenarios/cpu-stress.yaml");
        ChaosConfig config = YamlConfigLoader.load(configPath);
        ChaosEngine engine = new ChaosEngine(config);
        ChaosReport report = engine.run();
        ReportGenerator.generate(report, config.getReport());
        assertNotNull(report);
        assertTrue("CPU stress injection step should pass",
                   report.getStepResults().get(0).isPassed());
    }

    @Test
    public void testReportGeneration() {
        String configPath = System.getProperty("chaos.config",
            "classpath:scenarios/local-stress-test.yaml");
        ChaosConfig config = YamlConfigLoader.load(configPath);
        ChaosEngine engine = new ChaosEngine(config);
        ChaosReport report = engine.run();
        ReportGenerator.generate(report, config.getReport());
        assertNotNull(report.getScenarioName());
        assertNotNull(report.getStartTime());
        assertNotNull(report.getEndTime());
        assertNotNull(report.getStepResults());
        assertTrue(report.getDuration().getSeconds() > 0);
    }
}
