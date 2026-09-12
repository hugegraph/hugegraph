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

package org.apache.hugegraph.unit.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.Assert;
import org.junit.Test;

public class PolicyServerModeTest {

    @Test
    public void testLegacyHttpWebSocketAndBytecode() throws Exception {
        run("legacy", false, true);
    }

    @Test
    public void testCombinedHttpWebSocketAndBytecode() throws Exception {
        run("combined", false, true);
    }

    @Test
    public void testPolicyOnlyHttpWebSocketAndBytecode() throws Exception {
        run("policy-only", false, true);
    }

    @Test
    public void testInvalidModeAndInstalledManagerFailStartup() throws Exception {
        run("unknown", false, false);
        run("policy-only", true, false);
    }

    private static void run(String mode, boolean installed, boolean success) throws Exception {
        Path output = Files.createTempFile("hg-policy-mode-", ".log");
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("hugegraph-server/hugegraph-dist"))) {
            root = root.getParent();
        }
        Assert.assertNotNull("repository root", root);
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "@" + root.resolve("hugegraph-server/hugegraph-dist/src/assembly/static/bin/jvm-module.options"),
                "-Dhugegraph.script.security.mode=" + mode,
                "-cp", System.getProperty("java.class.path"), PolicyServerModeProbe.class.getName()));
        if (installed) {
            command.add("installed");
        }
        Process process = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(output.toFile()).start();
        try {
            Assert.assertTrue("mode probe timeout", process.waitFor(75, TimeUnit.SECONDS));
            String log = Files.readString(output, StandardCharsets.UTF_8);
            if (success) {
                Assert.assertEquals(log, 0, process.exitValue());
                Assert.assertTrue(log, log.contains("POLICY_MODE_VERIFIED " + mode));
            } else {
                Assert.assertNotEquals(log, 0, process.exitValue());
            }
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            }
            Files.deleteIfExists(output);
        }
    }
}
