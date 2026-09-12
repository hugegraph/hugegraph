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

public class PolicyGraphModeTest {

    @Test
    public void testGraphJobsSchemaAndPermissionsInCombinedMode() throws Exception {
        run("combined", "memory");
    }

    @Test
    public void testGraphJobsSchemaAndPermissionsWithoutSecurityManager() throws Exception {
        run("policy-only", "memory");
    }

    @Test
    public void testRocksdbJobsSchemaAndPermissionsInCombinedMode() throws Exception {
        run("combined", "rocksdb");
    }

    @Test
    public void testRocksdbJobsSchemaAndPermissionsWithoutSecurityManager() throws Exception {
        run("policy-only", "rocksdb");
    }

    private static void run(String mode, String backend) throws Exception {
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
                "-cp", System.getProperty("java.class.path"), PolicyGraphModeProbe.class.getName()));
        command.add(backend);
        // HSM's existing class-loading exception is relative to the launch directory.
        Process process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true)
                .redirectOutput(output.toFile()).start();
        try {
            Assert.assertTrue("mode probe timeout", process.waitFor(120, TimeUnit.SECONDS));
            String log = Files.readString(output, StandardCharsets.UTF_8);
            Assert.assertEquals(log, 0, process.exitValue());
            Assert.assertTrue(log, log.contains("GRAPH_POLICY_VERIFIED " + mode));
            Assert.assertTrue(log, log.contains("combined".equals(mode) ?
                    "manager=installed" : "manager=none"));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            }
            Files.deleteIfExists(output);
        }
    }
}
