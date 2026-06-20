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
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.hugegraph.testutil.Assert;
import org.apache.hugegraph.unit.BaseUnitTest;
import org.apache.tinkerpop.gremlin.server.Settings;
import org.junit.Test;

public class GremlinConfigCompatibilityTest extends BaseUnitTest {

    private static final Pattern CLASS_NAME =
            Pattern.compile("className:\\s*([^,}\\s]+)");
    private static final String SERIALIZER_PACKAGE =
            "org.apache.tinkerpop.gremlin.util.ser.";
    private static final String IO_REGISTRY =
            "org.apache.hugegraph.io.HugeGraphIoRegistry";
    private static final String GREMLIN_SERVER_CONFIG = "gremlin-server.yaml";
    private static final List<String> REMOTE_CONFIGS = Arrays.asList(
            "gremlin-driver-settings.yaml",
            "remote.yaml",
            "remote-objects.yaml"
    );
    private static final List<String> ALL_CONFIGS = Arrays.asList(
            GREMLIN_SERVER_CONFIG,
            "gremlin-driver-settings.yaml",
            "remote.yaml",
            "remote-objects.yaml"
    );
    private static final List<String> TYPED_FALLBACK_SERIALIZERS =
            Arrays.asList(
                    SERIALIZER_PACKAGE + "GraphSONMessageSerializerV1",
                    SERIALIZER_PACKAGE + "GraphSONMessageSerializerV2",
                    SERIALIZER_PACKAGE + "GraphSONMessageSerializerV3"
            );

    @Test
    public void testGremlinServerSerializersUseTinkerPopUtilPackage() throws IOException {
        String content = readConfig(GREMLIN_SERVER_CONFIG);

        assertUsesHugeGraphIoRegistry(GREMLIN_SERVER_CONFIG, content);
        assertSerializerClassNamesUseUtilPackage(GREMLIN_SERVER_CONFIG,
                                                 content);
    }

    @Test
    public void testRemoteSerializersUseTinkerPopUtilPackage() throws IOException {
        for (String file : REMOTE_CONFIGS) {
            String content = readConfig(file);

            assertUsesHugeGraphIoRegistry(file, content);
            assertSerializerClassNamesUseUtilPackage(file, content);
        }
    }

    @Test
    public void testGremlinServerSettingsCanBeParsed() throws Exception {
        Settings settings = Settings.read(configPath(GREMLIN_SERVER_CONFIG)
                                                  .toString());

        Assert.assertNotNull(settings);
        Assert.assertFalse(settings.serializers.isEmpty());
    }

    @Test
    public void testConfiguredSerializerClassesAreLoadable() throws Exception {
        for (String file : ALL_CONFIGS) {
            assertConfiguredSerializerClassesAreLoadable(file,
                                                         readConfig(file));
        }
    }

    @Test
    public void testTypedFallbackSerializerClassesAreLoadable()
            throws Exception {
        for (String serializer : TYPED_FALLBACK_SERIALIZERS) {
            Class.forName(serializer);
        }
    }

    private static String readConfig(String fileName) throws IOException {
        return Files.readString(configPath(fileName), StandardCharsets.UTF_8);
    }

    private static Path configPath(String fileName) {
        return findConfDir().resolve(fileName);
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

    private static void assertConfiguredSerializerClassesAreLoadable(
            String fileName, String content) throws ClassNotFoundException {
        Matcher matcher = CLASS_NAME.matcher(content);
        boolean found = false;
        while (matcher.find()) {
            found = true;
            Class.forName(matcher.group(1));
        }
        Assert.assertTrue("No serializer className found in " + fileName,
                          found);
    }
}
