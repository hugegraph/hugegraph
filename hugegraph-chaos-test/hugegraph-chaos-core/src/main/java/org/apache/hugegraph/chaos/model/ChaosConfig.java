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

import java.util.ArrayList;
import java.util.List;

public class ChaosConfig {

    private ScenarioConfig scenario;
    private TargetConfig target;
    private List<Step> steps = new ArrayList<>();
    private ReportConfig report;

    public String getScenarioName() {
        return scenario != null ? scenario.getName() : "unknown";
    }

    public TargetEnv getTargetEnv() {
        return target != null ? target.getEnv() : TargetEnv.LOCAL;
    }

    public ScenarioConfig getScenario() {
        return scenario;
    }

    public void setScenario(ScenarioConfig scenario) {
        this.scenario = scenario;
    }

    public TargetConfig getTarget() {
        return target;
    }

    public void setTarget(TargetConfig target) {
        this.target = target;
    }

    public List<Step> getSteps() {
        return steps;
    }

    public void setSteps(List<Step> steps) {
        this.steps = steps;
    }

    public ReportConfig getReport() {
        return report;
    }

    public void setReport(ReportConfig report) {
        this.report = report;
    }

    public static class ScenarioConfig {

        private String name;
        private String description;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }
    }

    public static class TargetConfig {

        private TargetEnv env;
        private LocalTarget local;
        private K8sTarget kubernetes;

        public TargetEnv getEnv() {
            return env;
        }

        public void setEnv(TargetEnv env) {
            this.env = env;
        }

        public LocalTarget getLocal() {
            return local;
        }

        public void setLocal(LocalTarget local) {
            this.local = local;
        }

        public K8sTarget getKubernetes() {
            return kubernetes;
        }

        public void setKubernetes(K8sTarget kubernetes) {
            this.kubernetes = kubernetes;
        }
    }

    public static class LocalTarget {

        private String clusterConfig;

        public String getClusterConfig() {
            return clusterConfig;
        }

        public void setClusterConfig(String clusterConfig) {
            this.clusterConfig = clusterConfig;
        }
    }

    public static class K8sTarget {

        private String kubeconfig;
        private String namespace;
        private String labelSelector;

        public String getKubeconfig() {
            return kubeconfig;
        }

        public void setKubeconfig(String kubeconfig) {
            this.kubeconfig = kubeconfig;
        }

        public String getNamespace() {
            return namespace;
        }

        public void setNamespace(String namespace) {
            this.namespace = namespace;
        }

        public String getLabelSelector() {
            return labelSelector;
        }

        public void setLabelSelector(String labelSelector) {
            this.labelSelector = labelSelector;
        }
    }

    public static class ReportConfig {

        private List<String> format = new ArrayList<>();
        private String outputDir;
        private boolean includeLogs = true;

        public List<String> getFormat() {
            return format;
        }

        public void setFormat(List<String> format) {
            this.format = format;
        }

        public String getOutputDir() {
            return outputDir;
        }

        public void setOutputDir(String outputDir) {
            this.outputDir = outputDir;
        }

        public boolean isIncludeLogs() {
            return includeLogs;
        }

        public void setIncludeLogs(boolean includeLogs) {
            this.includeLogs = includeLogs;
        }
    }
}
