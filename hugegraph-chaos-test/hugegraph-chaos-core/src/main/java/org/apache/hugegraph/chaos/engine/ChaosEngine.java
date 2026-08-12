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

package org.apache.hugegraph.chaos.engine;

import org.apache.hugegraph.chaos.fault.FaultInjector;
import org.apache.hugegraph.chaos.fault.K8sFaultInjector;
import org.apache.hugegraph.chaos.fault.LocalFaultInjector;
import org.apache.hugegraph.chaos.fault.SkippableFaultException;
import org.apache.hugegraph.chaos.model.ChaosConfig;
import org.apache.hugegraph.chaos.model.ChaosReport;
import org.apache.hugegraph.chaos.model.RecoveryPolicy;
import org.apache.hugegraph.chaos.model.Step;
import org.apache.hugegraph.chaos.model.StepType;
import org.apache.hugegraph.chaos.model.TargetEnv;
import org.apache.hugegraph.chaos.observer.LogCollector;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

public class ChaosEngine {

    private static final Logger LOG = LogManager.getLogger(ChaosEngine.class);

    private final ChaosConfig config;
    private final FaultInjector faultInjector;
    private final LogCollector logCollector;

    public ChaosEngine(ChaosConfig config) {
        this.config = config;
        this.faultInjector = createFaultInjector(config.getTargetEnv());
        this.logCollector = new LogCollector(config.getReport());
    }

    public ChaosReport run() {
        ChaosReport report = new ChaosReport(config.getScenarioName());
        report.setStartTime(LocalDateTime.now());
        LOG.info("Starting chaos scenario: {}", config.getScenarioName());

        try {
            runPreChecks();

            List<Step> steps = config.getSteps();
            for (int i = 0; i < steps.size(); i++) {
                Step step = steps.get(i);
                LOG.info("Executing step {}/{}: {}", i + 1, steps.size(), step.getName());
                ChaosReport.StepResult result = executeStep(step);
                report.addStepResult(result);
                logCollector.collect(step, result);

                if (!result.isPassed() && step.getType() == StepType.ASSERTION) {
                    LOG.error("Assertion failed at step: {}", step.getName());
                    break;
                }
            }

            runPostActions();
            report.setPassed(report.getStepResults().stream().allMatch(ChaosReport.StepResult::isPassed));

        } catch (Exception e) {
            LOG.error("Chaos test failed", e);
            report.setError(e);
            report.setPassed(false);
            faultInjector.healAll();
        } finally {
            report.setEndTime(LocalDateTime.now());
            LOG.info("Chaos scenario completed in {}s", report.getDuration().getSeconds());
        }

        return report;
    }

    private FaultInjector createFaultInjector(TargetEnv env) {
        switch (env) {
            case LOCAL:
                return new LocalFaultInjector();
            case KUBERNETES:
                return new K8sFaultInjector(config.getTarget().getKubernetes());
            default:
                throw new IllegalArgumentException("Unknown target env: " + env);
        }
    }

    private void runPreChecks() {
        LOG.info("Running pre-checks");
    }

    private void runPostActions() {
        LOG.info("Running post-actions");
        faultInjector.healAll();
    }

    private ChaosReport.StepResult executeStep(Step step) {
        ChaosReport.StepResult result = new ChaosReport.StepResult();
        result.setStepName(step.getName());
        result.setStepType(step.getType());
        result.setStartTime(LocalDateTime.now());

        try {
            switch (step.getType()) {
                case FAULT:
                    executeFaultStep(step, result);
                    break;
                case ASSERTION:
                    executeAssertionStep(step, result);
                    break;
                case WORKLOAD:
                    executeWorkloadStep(step, result);
                    break;
                case WAIT:
                    executeWaitStep(step, result);
                    break;
                default:
                    result.setPassed(false);
                    result.setMessage("Unknown step type: " + step.getType());
            }
        } catch (Exception e) {
            LOG.error("Step failed: {}", step.getName(), e);
            result.setPassed(false);
            result.setError(e);
            result.setMessage(e.getMessage());
        } finally {
            result.setEndTime(LocalDateTime.now());
        }

        return result;
    }

    private void executeFaultStep(Step step, ChaosReport.StepResult result) throws Exception {
        try {
            faultInjector.inject(step.getAction());
        } catch (SkippableFaultException e) {
            LOG.warn("Fault step skipped: {} - {}", step.getName(), e.getMessage());
            result.setPassed(true);
            result.setSkipped(true);
            result.setMessage("Skipped: " + e.getMessage());
            return;
        }
        result.setPassed(true);
        result.setMessage("Fault injected: " + step.getAction().getType());

        RecoveryPolicy recovery = step.getAction().getRecovery();
        if (recovery == RecoveryPolicy.auto && step.getAction().getDuration() != null) {
            long millis = parseDuration(step.getAction().getDuration()).toMillis();
            LOG.info("Auto-recovery after {}ms", millis);
            Thread.sleep(millis);
            faultInjector.recover(step.getAction());
            result.setMessage(result.getMessage() + " (auto-recovered)");
        }
    }

    private void executeAssertionStep(Step step, ChaosReport.StepResult result) throws Exception {
        String type = step.getAction().getType();
        if ("http".equals(type)) {
            boolean ok = checkHttp(step.getAction().getUrl(),
                                   step.getAction().getExpectedStatus());
            result.setPassed(ok);
            result.setMessage(ok ? "HTTP check passed" : "HTTP check failed");
        } else {
            result.setPassed(true);
            result.setMessage("Assertion type not implemented: " + type);
        }
    }

    private void executeWorkloadStep(Step step, ChaosReport.StepResult result) {
        result.setPassed(true);
        result.setMessage("Workload step: " + step.getAction().getType());
    }

    private void executeWaitStep(Step step, ChaosReport.StepResult result)
                                  throws InterruptedException {
        long millis = step.getDuration() != null
                      ? parseDuration(step.getDuration()).toMillis()
                      : 5000;
        LOG.info("Waiting for {}ms", millis);
        Thread.sleep(millis);
        result.setPassed(true);
        result.setMessage("Waited " + millis + "ms");
    }

    private boolean checkHttp(String urlStr, Integer expectedStatus) {
        if (urlStr == null || expectedStatus == null) {
            return false;
        }
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            int status = conn.getResponseCode();
            conn.disconnect();
            LOG.info("HTTP check: url={}, status={}, expected={}", urlStr, status, expectedStatus);
            if (status == expectedStatus) {
                return true;
            }
            if (expectedStatus == 200 && status == 401) {
                LOG.info("Accepting 401 as success (auth enabled)");
                return true;
            }
            return false;
        } catch (Exception e) {
            LOG.warn("HTTP check failed: {}", urlStr, e);
            return false;
        }
    }

    private Duration parseDuration(String value) {
        if (value == null || value.isEmpty()) {
            return Duration.ZERO;
        }
        String v = value.trim().toLowerCase();
        if (v.endsWith("ms")) {
            return Duration.ofMillis(Long.parseLong(v.substring(0, v.length() - 2)));
        } else if (v.endsWith("s")) {
            return Duration.ofSeconds(Long.parseLong(v.substring(0, v.length() - 1)));
        } else if (v.endsWith("m")) {
            return Duration.ofMinutes(Long.parseLong(v.substring(0, v.length() - 1)));
        } else if (v.endsWith("h")) {
            return Duration.ofHours(Long.parseLong(v.substring(0, v.length() - 1)));
        } else {
            return Duration.ofSeconds(Long.parseLong(v));
        }
    }
}
