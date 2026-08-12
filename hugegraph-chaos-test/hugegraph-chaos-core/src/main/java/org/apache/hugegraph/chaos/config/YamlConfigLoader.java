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

package org.apache.hugegraph.chaos.config;

import org.apache.hugegraph.chaos.model.ChaosConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

public class YamlConfigLoader {

    private static final Logger LOG = LogManager.getLogger(YamlConfigLoader.class);

    public static ChaosConfig load(String path) {
        LOG.info("Loading chaos config from: {}", path);
        LoaderOptions loaderOptions = new LoaderOptions();
        Yaml yaml = new Yaml(new Constructor(ChaosConfig.class, loaderOptions));
        try (InputStream is = openStream(path)) {
            ChaosConfig config = yaml.load(is);
            if (config == null) {
                throw new IllegalArgumentException("Failed to load config from: " + path);
            }
            LOG.info("Loaded scenario: {}", config.getScenarioName());
            return config;
        } catch (IOException e) {
            throw new RuntimeException("Failed to load config: " + path, e);
        }
    }

    private static InputStream openStream(String path) throws IOException {
        if (path.startsWith("classpath:")) {
            String resourcePath = path.substring("classpath:".length());
            InputStream is = YamlConfigLoader.class.getClassLoader()
                             .getResourceAsStream(resourcePath);
            if (is == null) {
                throw new IOException("Resource not found: " + resourcePath);
            }
            return is;
        }
        return new FileInputStream(path);
    }
}
