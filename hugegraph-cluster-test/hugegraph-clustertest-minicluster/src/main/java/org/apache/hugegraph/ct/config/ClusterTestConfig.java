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

package org.apache.hugegraph.ct.config;

import java.io.InputStream;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ClusterTestConfig {

    private static final Logger LOG = LoggerFactory.getLogger(ClusterTestConfig.class);

    private int pd = 3;
    private int store = 3;
    private int server = 3;
    private long timeoutMs = 360000;
    private String graphName = "hugegraphapi";
    private String backend = "hstore";
    private String serializer = "binary";
    private String mode = "multi";
    private int realStore = 0;
    private int miniStore = 0;
    private boolean lightweight = false;

    public static ClusterTestConfig load() {
        String configFile = System.getProperty("cluster.config", "cluster-test-multi.yaml");
        ClusterTestConfig config = new ClusterTestConfig();

        try (InputStream is = ClusterTestConfig.class.getClassLoader()
                .getResourceAsStream(configFile)) {
            if (is == null) {
                LOG.warn("Config file '{}' not found on classpath, " +
                         "using defaults (3+3+3)", configFile);
            } else {
                Yaml yaml = new Yaml();
                Map<String, Object> data = yaml.load(is);
                if (data != null) {
                    applyYaml(config, data);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to load config: " + configFile, e);
        }

        applySystemProperties(config);
        applyTimeoutToSystemProperties(config);
        return config;
    }

    @SuppressWarnings("unchecked")
    private static void applyYaml(ClusterTestConfig config, Map<String, Object> data) {
        if (data.containsKey("pd")) config.setPd(((Number) data.get("pd")).intValue());
        if (data.containsKey("store")) config.setStore(((Number) data.get("store")).intValue());
        if (data.containsKey("server")) config.setServer(((Number) data.get("server")).intValue());
        if (data.containsKey("timeoutMs")) config.setTimeoutMs(((Number) data.get("timeoutMs")).longValue());
        if (data.containsKey("graphName")) config.setGraphName((String) data.get("graphName"));
        if (data.containsKey("backend")) config.setBackend((String) data.get("backend"));
        if (data.containsKey("serializer")) config.setSerializer((String) data.get("serializer"));
        if (data.containsKey("mode")) config.setMode((String) data.get("mode"));
        if (data.containsKey("realStore")) config.setRealStore(((Number) data.get("realStore")).intValue());
        if (data.containsKey("miniStore")) config.setMiniStore(((Number) data.get("miniStore")).intValue());
        if (data.containsKey("lightweight")) config.setLightweight((Boolean) data.get("lightweight"));
    }

    private static void applySystemProperties(ClusterTestConfig config) {
        String pdProp = System.getProperty("cluster.pd");
        if (pdProp != null) config.setPd(Integer.parseInt(pdProp));

        String storeProp = System.getProperty("cluster.store");
        if (storeProp != null) config.setStore(Integer.parseInt(storeProp));

        String serverProp = System.getProperty("cluster.server");
        if (serverProp != null) config.setServer(Integer.parseInt(serverProp));

        String timeoutProp = System.getProperty("cluster.timeout");
        if (timeoutProp != null) config.setTimeoutMs(Long.parseLong(timeoutProp));
    }

    private static void applyTimeoutToSystemProperties(ClusterTestConfig config) {
        // Apply timeoutMs from config file to ClusterConstant
        org.apache.hugegraph.ct.base.ClusterConstant.applyTimeoutFromConfig(
            config.getTimeoutMs());
    }

    public int getEffectiveStoreCount() {
        if ("hybrid".equals(mode)) {
            return realStore + miniStore;
        }
        return store;
    }
}
