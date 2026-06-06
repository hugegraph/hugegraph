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

package org.apache.hugegraph.unit.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.hugegraph.testutil.Assert;
import org.apache.hugegraph.unit.BaseUnitTest;
import org.junit.Test;

public class GremlinConfigCompatibilityTest extends BaseUnitTest {

    private static final Pattern CLASS_NAME =
            Pattern.compile("className:\\s*([^,}\\s]+)");
    private static final String SERIALIZER_PACKAGE =
            "org.apache.tinkerpop.gremlin.util.ser.";
    private static final String IO_REGISTRY =
            "org.apache.hugegraph.io.HugeGraphIoRegistry";

    @Test
    public void testGremlinServerSerializersUseTinkerPopUtilPackage() throws IOException {
        String content = readConfig("gremlin-server.yaml");

        assertUsesHugeGraphIoRegistry("gremlin-server.yaml", content);
        assertSerializerClassNamesUseUtilPackage("gremlin-server.yaml",
                                                 content);
    }

    @Test
    public void testRemoteSerializersUseTinkerPopUtilPackage() throws IOException {
        for (String file : new String[]{
            "gremlin-driver-settings.yaml",
            "remote.yaml",
            "remote-objects.yaml"
        }) {
            String content = readConfig(file);

            assertUsesHugeGraphIoRegistry(file, content);
            assertSerializerClassNamesUseUtilPackage(file, content);
        }
    }

    private static String readConfig(String fileName) throws IOException {
        Path config = findConfDir().resolve(fileName);
        return Files.readString(config, StandardCharsets.UTF_8);
    }

    private static Path findConfDir() {
        String configuredDir = System.getProperty("hugegraph.conf.dir");
        Path configuredPath = resolveConfiguredDir(configuredDir);
        if (configuredPath != null) {
            return configuredPath;
        }

        String envDir = System.getenv("HUGEGRAPH_CONF_DIR");
        Path envPath = resolveConfiguredDir(envDir);
        if (envPath != null) {
            return envPath;
        }

        Path userDir = Paths.get(System.getProperty("user.dir"));
        List<Path> candidates = new ArrayList<>();

        Path parent = userDir.getParent();
        if (parent != null) {
            candidates.add(parent.resolve("hugegraph-dist")
                                 .resolve("src")
                                 .resolve("assembly")
                                 .resolve("static")
                                 .resolve("conf"));
        }
        candidates.add(userDir.resolve("hugegraph-server")
                              .resolve("hugegraph-dist")
                              .resolve("src")
                              .resolve("assembly")
                              .resolve("static")
                              .resolve("conf"));

        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }

        Assert.fail(String.format("Can't find hugegraph-dist static conf from" +
                                  " %s (hugegraph.conf.dir=%s," +
                                  " HUGEGRAPH_CONF_DIR=%s, candidates=%s)",
                                  userDir, configuredDir, envDir, candidates));
        return userDir;
    }

    private static Path resolveConfiguredDir(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        Path configured = Paths.get(path);
        if (Files.isDirectory(configured)) {
            return configured;
        }
        return null;
    }

    private static void assertUsesHugeGraphIoRegistry(String fileName,
                                                      String content) {
        Assert.assertTrue(fileName + " should keep HugeGraphIoRegistry",
                          content.contains(IO_REGISTRY));
    }

    private static void assertSerializerClassNamesUseUtilPackage(
            String fileName, String content) {
        Assert.assertFalse(content.contains(
                "org.apache.tinkerpop.gremlin.driver.ser."));
        Assert.assertFalse(content.contains(
                "org.apache.tinkerpop.gremlin.server.ser."));

        Matcher matcher = CLASS_NAME.matcher(content);
        boolean found = false;
        while (matcher.find()) {
            found = true;
            String className = matcher.group(1);
            Assert.assertTrue(fileName + " has outdated serializer " +
                              className,
                              className.startsWith(SERIALIZER_PACKAGE));
        }
        Assert.assertTrue("No serializer className found in " + fileName,
                          found);
    }
}
