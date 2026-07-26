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

package org.apache.hugegraph.unit.cmd;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.stream.Stream;

import org.apache.commons.configuration2.PropertiesConfiguration;
import org.apache.hugegraph.cmd.InitStore;
import org.apache.hugegraph.config.HugeConfig;
import org.apache.hugegraph.config.ServerOptions;
import org.apache.hugegraph.dist.RegisterUtil;
import org.apache.hugegraph.testutil.Assert;
import org.apache.hugegraph.util.ConfigUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * {@code init_store.enabled} controls whether init-store performs local
 * backend and admin initialization. It defaults to true, so standalone and
 * tarball installs keep the full init path; distributed (PD/HStore)
 * deployments set it to false.
 */
public class InitStoreConfigTest {

    private static final String MISSING_GRAPHS_DIR = "no-such-graphs-dir";

    private Path workDir;

    @BeforeClass
    public static void registerOptions() {
        RegisterUtil.registerServer();
    }

    @Before
    public void setup() throws IOException {
        this.workDir = Files.createTempDirectory("init-store-config-test");
    }

    @After
    public void teardown() throws IOException {
        try (Stream<Path> paths = Files.walk(this.workDir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder())
                                  .toArray(Path[]::new)) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    public void testInitStoreEnabledByDefault() {
        HugeConfig config = new HugeConfig(new PropertiesConfiguration());
        Assert.assertTrue(config.get(ServerOptions.INIT_STORE_ENABLED));
    }

    @Test
    public void testExplicitTrueKeepsInitStoreEnabled() {
        PropertiesConfiguration properties = new PropertiesConfiguration();
        properties.setProperty(ServerOptions.INIT_STORE_ENABLED.name(), "true");
        HugeConfig config = new HugeConfig(properties);
        Assert.assertTrue(config.get(ServerOptions.INIT_STORE_ENABLED));
    }

    @Test
    public void testExplicitFalseDisablesInitStore() {
        PropertiesConfiguration properties = new PropertiesConfiguration();
        properties.setProperty(ServerOptions.INIT_STORE_ENABLED.name(), "false");
        HugeConfig config = new HugeConfig(properties);
        Assert.assertFalse(config.get(ServerOptions.INIT_STORE_ENABLED));
    }

    /**
     * The graphs directory referenced by the temporary config does not exist,
     * so every code path that reaches graph scanning fails. That is what makes
     * {@link #testDisabledInitStoreExitsBeforeGraphInit()} a real assertion
     * rather than a tautology.
     */
    @Test
    public void testMissingGraphsDirFailsScan() {
        String graphsDir = this.workDir.resolve(MISSING_GRAPHS_DIR).toString();
        Assert.assertThrows(IllegalArgumentException.class, () -> {
            ConfigUtil.scanGraphsDir(graphsDir);
        });
    }

    /*
     * NOTE: only one test may call InitStore.main(), because it calls
     * RegisterUtil.registerBackends() and a backend provider can be
     * registered only once per JVM.
     */
    @Test
    public void testDisabledInitStoreExitsBeforeGraphInit() throws Exception {
        String restConf = this.writeDisabledRestServerConf();
        // Completes instead of failing on the missing graphs directory, which
        // proves the gate is applied before any graph or admin initialization
        InitStore.main(new String[]{restConf});
    }

    private String writeDisabledRestServerConf() throws IOException {
        Path restConf = this.workDir.resolve("rest-server.properties");
        String graphsDir = this.workDir.resolve(MISSING_GRAPHS_DIR).toString();
        Files.write(restConf,
                    Arrays.asList(ServerOptions.GRAPHS.name() + "=" + graphsDir,
                                  ServerOptions.INIT_STORE_ENABLED.name() +
                                  "=false"),
                    StandardCharsets.UTF_8);
        return restConf.toString();
    }
}
