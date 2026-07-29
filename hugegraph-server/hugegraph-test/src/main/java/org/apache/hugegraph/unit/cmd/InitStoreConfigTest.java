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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.apache.commons.configuration2.PropertiesConfiguration;
import org.apache.hugegraph.HugeGraph;
import org.apache.hugegraph.auth.HugeUser;
import org.apache.hugegraph.auth.StandardAuthManager;
import org.apache.hugegraph.auth.StandardAuthenticator;
import org.apache.hugegraph.backend.store.BackendFeatures;
import org.apache.hugegraph.cmd.InitStore;
import org.apache.hugegraph.config.CoreOptions;
import org.apache.hugegraph.config.HugeConfig;
import org.apache.hugegraph.config.OptionSpace;
import org.apache.hugegraph.config.ServerOptions;
import org.apache.hugegraph.dist.RegisterUtil;
import org.apache.hugegraph.testutil.Assert;
import org.apache.hugegraph.testutil.Whitebox;
import org.apache.hugegraph.util.ConfigUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * {@code init_store.enabled} controls whether init-store performs local
 * backend and admin initialization. It defaults to true, so standalone and
 * tarball installs keep the full init path; distributed (PD/HStore)
 * deployments set it to false.
 */
public class InitStoreConfigTest {

    private static final String MISSING_GRAPHS_DIR = "no-such-graphs-dir";
    // Registered by RegisterUtil.registerBackends(), not by registerServer()
    private static final String ROCKSDB_OPTION = "rocksdb.data_path";

    private Path workDir;
    private int confSeq;

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
    public void testAdminPasswordDefaultsToPa() {
        HugeConfig config = new HugeConfig(new PropertiesConfiguration());
        Assert.assertEquals("pa", config.get(ServerOptions.ADMIN_PA));
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
     * A key defined twice collects both values into a list, which fails the
     * scalar type check while the config is still being loaded. Both
     * init-store and server startup would therefore fail outright. That is why
     * docker-entrypoint.sh collapses any existing definition into one
     * canonical line instead of appending a second one when it maps an
     * environment variable onto the property.
     */
    @Test
    public void testDuplicateDefinitionFailsToLoad() throws IOException {
        Path conf = this.workDir.resolve("duplicate.properties");
        String key = ServerOptions.INIT_STORE_ENABLED.name();
        Files.write(conf, Arrays.asList(key + "=false", key + "=true"),
                    StandardCharsets.UTF_8);

        Assert.assertThrows(IllegalArgumentException.class, () -> {
            new HugeConfig(conf.toString());
        }, e -> Assert.assertContains("[false, true]", e.getMessage()));
    }

    @Test
    public void testUnicodeEscapedAdminPasswordRoundTrips() throws IOException {
        String password = " \tmeta:=#!\\\r\f\np\u00e4ss\u96ea";
        StringBuilder serialized = new StringBuilder(
                ServerOptions.ADMIN_PA.name() + "=");
        for (char item : password.toCharArray()) {
            serialized.append(String.format("\\u%04x", (int) item));
        }

        Path conf = this.workDir.resolve("escaped-password.properties");
        Files.write(conf, Arrays.asList(serialized.toString()),
                    StandardCharsets.UTF_8);

        HugeConfig config = new HugeConfig(conf.toString());
        Assert.assertEquals(password, config.get(ServerOptions.ADMIN_PA));
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

    @Test
    public void testDisabledInitStoreExitsBeforeGraphInit() throws Exception {
        String restConf = this.writeDisabledRestServerConf();
        // Completes instead of failing on the missing graphs directory, which
        // proves the gate is applied before any graph or admin initialization
        InitStore.main(new String[]{restConf});
    }

    @Test
    public void testConfiguredAdminPasswordFlagAccepted() throws Exception {
        String restConf = this.writeDisabledRestServerConf();
        InitStore.main(new String[]{restConf,
                                   "--use-configured-admin-password"});
    }

    @Test
    public void testConfiguredAdminPasswordRejectsNullOrEmptyValue()
                                                        throws Exception {
        HugeConfig config = Mockito.mock(HugeConfig.class);
        HugeGraph graph = Mockito.mock(HugeGraph.class);
        BackendFeatures features = Mockito.mock(BackendFeatures.class);
        StandardAuthManager authManager = Mockito.mock(StandardAuthManager.class);
        Mockito.when(graph.hugegraph()).thenReturn(graph);
        Mockito.when(graph.authManager()).thenReturn(authManager);
        Mockito.when(graph.backendStoreFeatures()).thenReturn(features);
        Mockito.when(features.supportsPersistence()).thenReturn(true);

        StandardAuthenticator authenticator =
                Mockito.spy(new StandardAuthenticator());
        Whitebox.setInternalState(authenticator, "graph", graph);
        Mockito.doNothing().when(authenticator).setup(config);

        for (String password : new String[]{null, ""}) {
            Assert.assertThrows(IllegalArgumentException.class, () -> {
                Whitebox.invoke(StandardAuthenticator.class,
                                new Class<?>[]{HugeConfig.class, String.class,
                                               boolean.class},
                                "initAdminUser", authenticator, config,
                                password, true);
            }, e -> Assert.assertContains("can't be null or empty",
                                          e.getMessage()));
        }
        Mockito.verify(graph, Mockito.times(2)).close();
    }

    @Test
    public void testAdminGraphClosedForNonPersistentBackend() throws Exception {
        HugeConfig config = Mockito.mock(HugeConfig.class);
        HugeGraph graph = Mockito.mock(HugeGraph.class);
        BackendFeatures features = Mockito.mock(BackendFeatures.class);
        Mockito.when(graph.backendStoreFeatures()).thenReturn(features);
        Mockito.when(features.supportsPersistence()).thenReturn(false);

        StandardAuthenticator authenticator =
                Mockito.spy(new StandardAuthenticator());
        Whitebox.setInternalState(authenticator, "graph", graph);
        Mockito.doNothing().when(authenticator).setup(config);

        Whitebox.invoke(StandardAuthenticator.class,
                        new Class<?>[]{HugeConfig.class, String.class,
                                       boolean.class},
                        "initAdminUser", authenticator, config, null, false);

        Mockito.verify(graph).close();
    }

    @Test
    public void testAdminGraphClosedAfterSetupFailure() throws Exception {
        HugeConfig config = Mockito.mock(HugeConfig.class);
        HugeGraph graph = Mockito.mock(HugeGraph.class);
        StandardAuthenticator authenticator =
                Mockito.spy(new StandardAuthenticator());
        Whitebox.setInternalState(authenticator, "graph", graph);
        Mockito.doThrow(new IllegalStateException("setup failed"))
               .when(authenticator).setup(config);

        Assert.assertThrows(IllegalStateException.class, () -> {
            Whitebox.invoke(StandardAuthenticator.class,
                            new Class<?>[]{HugeConfig.class, String.class,
                                           boolean.class},
                            "initAdminUser", authenticator, config, null, false);
        }, e -> Assert.assertContains("setup failed", e.getMessage()));

        Mockito.verify(graph).close();
    }

    @Test
    public void testSetupFailureWithoutGraphPreservesOriginalException()
                                                              throws Exception {
        HugeConfig config = Mockito.mock(HugeConfig.class);
        StandardAuthenticator authenticator =
                Mockito.spy(new StandardAuthenticator());
        Mockito.doThrow(new IllegalStateException("setup failed before open"))
               .when(authenticator).setup(config);

        Assert.assertThrows(IllegalStateException.class, () -> {
            Whitebox.invoke(StandardAuthenticator.class,
                            new Class<?>[]{HugeConfig.class, String.class,
                                           boolean.class},
                            "initAdminUser", authenticator, config, null, false);
        }, e -> Assert.assertContains("setup failed before open",
                                      e.getMessage()));
    }

    @Test
    public void testAdminGraphClosedAfterFeatureInspectionFailure()
                                                              throws Exception {
        HugeConfig config = Mockito.mock(HugeConfig.class);
        HugeGraph graph = Mockito.mock(HugeGraph.class);
        Mockito.when(graph.backendStoreFeatures())
               .thenThrow(new IllegalStateException("feature check failed"));

        StandardAuthenticator authenticator =
                Mockito.spy(new StandardAuthenticator());
        Whitebox.setInternalState(authenticator, "graph", graph);
        Mockito.doNothing().when(authenticator).setup(config);

        Assert.assertThrows(IllegalStateException.class, () -> {
            Whitebox.invoke(StandardAuthenticator.class,
                            new Class<?>[]{HugeConfig.class, String.class,
                                           boolean.class},
                            "initAdminUser", authenticator, config, null, false);
        }, e -> Assert.assertContains("feature check failed", e.getMessage()));

        Mockito.verify(graph).close();
    }

    @Test
    public void testAdminGraphClosedWhenAdminAlreadyExists() throws Exception {
        HugeConfig config = Mockito.mock(HugeConfig.class);
        HugeGraph graph = Mockito.mock(HugeGraph.class);
        BackendFeatures features = Mockito.mock(BackendFeatures.class);
        StandardAuthManager authManager = Mockito.mock(StandardAuthManager.class);
        Mockito.when(graph.backendStoreFeatures()).thenReturn(features);
        Mockito.when(features.supportsPersistence()).thenReturn(true);
        Mockito.when(graph.hugegraph()).thenReturn(graph);
        Mockito.when(graph.authManager()).thenReturn(authManager);
        Mockito.when(authManager.findUser("admin"))
               .thenReturn(Mockito.mock(HugeUser.class));

        StandardAuthenticator authenticator =
                Mockito.spy(new StandardAuthenticator());
        Whitebox.setInternalState(authenticator, "graph", graph);
        Mockito.doNothing().when(authenticator).setup(config);

        Whitebox.invoke(StandardAuthenticator.class,
                        new Class<?>[]{HugeConfig.class, String.class,
                                       boolean.class},
                        "initAdminUser", authenticator, config, null, true);

        Mockito.verify(authManager, Mockito.never())
               .createUser(Mockito.any(HugeUser.class));
        Mockito.verify(graph).close();
    }

    @Test
    public void testAdminGraphClosedAfterCreateUserFailure() throws Exception {
        HugeConfig config = Mockito.mock(HugeConfig.class);
        HugeGraph graph = Mockito.mock(HugeGraph.class);
        BackendFeatures features = Mockito.mock(BackendFeatures.class);
        StandardAuthManager authManager = Mockito.mock(StandardAuthManager.class);
        Mockito.when(graph.backendStoreFeatures()).thenReturn(features);
        Mockito.when(features.supportsPersistence()).thenReturn(true);
        Mockito.when(graph.hugegraph()).thenReturn(graph);
        Mockito.when(graph.authManager()).thenReturn(authManager);
        Mockito.when(authManager.createUser(Mockito.any(HugeUser.class)))
               .thenThrow(new IllegalStateException("create user failed"));

        StandardAuthenticator authenticator =
                Mockito.spy(new StandardAuthenticator());
        Whitebox.setInternalState(authenticator, "graph", graph);
        Mockito.doNothing().when(authenticator).setup(config);

        Assert.assertThrows(IllegalStateException.class, () -> {
            Whitebox.invoke(StandardAuthenticator.class,
                            new Class<?>[]{HugeConfig.class, String.class,
                                           boolean.class},
                            "initAdminUser", authenticator, config,
                            "secret", true);
        }, e -> Assert.assertContains("create user failed", e.getMessage()));

        Mockito.verify(graph).close();
    }

    @Test
    public void testUnknownInitStoreFlagRejected() throws IOException {
        String restConf = this.writeDisabledRestServerConf();
        Assert.assertThrows(IllegalArgumentException.class, () -> {
            InitStore.main(new String[]{restConf, "--unknown"});
        }, e -> Assert.assertContains("optional", e.getMessage()));
    }

    /**
     * Disabled mode must not reach RegisterUtil.registerBackends() or
     * registerPlugins(): plugin registration runs every discovered plugin's
     * register() and propagates its failures, which a documented no-op path
     * must not do. Backend options land in OptionSpace only via
     * registerBackends(), so their registration state shows whether it ran.
     * OptionSpace is process-wide, so the assertion is that main() leaves that
     * state unchanged rather than that it starts out empty.
     */
    @Test
    public void testDisabledInitStoreSkipsBackendAndPluginRegistration()
                                                            throws Exception {
        boolean backendsRegistered = OptionSpace.containKey(ROCKSDB_OPTION);

        InitStore.main(new String[]{this.writeDisabledRestServerConf()});

        Assert.assertEquals(backendsRegistered,
                            OptionSpace.containKey(ROCKSDB_OPTION));
        // Server options are still registered, since the gate is read from them
        Assert.assertTrue(OptionSpace.containKey(
                          ServerOptions.INIT_STORE_ENABLED.name()));
    }

    /**
     * The CLI must not report success for a configuration that would start an
     * auth-enabled server with no admin account, since tarball and init-job
     * callers only see the exit status.
     */
    @Test
    public void testDisabledInitStoreFailsWhenAdminCannotBeCreated()
                                                        throws IOException {
        String restConf = this.writeDisabledRestServerConf(
                ServerOptions.AUTHENTICATOR.name() +
                "=" + StandardAuthenticator.class.getName());

        Assert.assertThrows(IllegalStateException.class, () -> {
            InitStore.main(new String[]{restConf});
        }, e -> Assert.assertContains("Refusing to skip init-store",
                                      e.getMessage()));
    }

    @Test
    public void testDisabledInitStoreFailsWithNonHstoreAuthGraph()
                                                        throws IOException {
        Path graphsDir = this.writeAuthGraphConfig("memory");
        String restConf = this.writeDisabledRestServerConfForGraphs(
                graphsDir,
                ServerOptions.AUTHENTICATOR.name() +
                "=" + StandardAuthenticator.class.getName(),
                ServerOptions.AUTH_GRAPH_STORE.name() + "=hugegraph",
                ServerOptions.USE_PD.name() + "=true");

        Assert.assertThrows(IllegalStateException.class, () -> {
            InitStore.main(new String[]{restConf});
        }, e -> Assert.assertContains("uses backend 'memory', not 'hstore'",
                                      e.getMessage()));
    }

    @Test
    public void testDisabledInitStoreAllowsPdBackedHstoreAuthGraph()
                                                        throws Exception {
        Path graphsDir = this.writeAuthGraphConfig("hstore");
        String restConf = this.writeDisabledRestServerConfForGraphs(
                graphsDir,
                ServerOptions.AUTHENTICATOR.name() +
                "=" + StandardAuthenticator.class.getName(),
                ServerOptions.AUTH_GRAPH_STORE.name() + "=hugegraph",
                ServerOptions.USE_PD.name() + "=true");

        InitStore.main(new String[]{restConf});
    }

    /**
     * Remote auth delegates to another service, so there is no local admin to
     * create and the check above must not fire.
     */
    @Test
    public void testDisabledInitStoreAllowsRemoteAuth() throws Exception {
        String restConf = this.writeDisabledRestServerConf(
                ServerOptions.AUTHENTICATOR.name() +
                "=" + StandardAuthenticator.class.getName(),
                ServerOptions.AUTH_REMOTE_URL.name() + "=127.0.0.1:8899");

        InitStore.main(new String[]{restConf});
    }

    /**
     * auth.authenticator takes any implementation class, and only the built-in
     * one bootstraps HugeGraph's admin account. A custom authenticator keeps
     * its identities elsewhere, so the check must not reject it; a subclass of
     * the built-in one still relies on the same bootstrap.
     */
    @Test
    public void testDisabledInitStoreAllowsCustomAuthenticator()
                                                        throws Exception {
        String restConf = this.writeDisabledRestServerConf(
                ServerOptions.AUTHENTICATOR.name() +
                "=org.example.auth.LdapAuthenticator");

        InitStore.main(new String[]{restConf});

        String subclassConf = this.writeDisabledRestServerConf(
                ServerOptions.AUTHENTICATOR.name() + "=" +
                DerivedAuthenticator.class.getName());

        Assert.assertThrows(IllegalStateException.class, () -> {
            InitStore.main(new String[]{subclassConf});
        }, e -> Assert.assertContains("Refusing to skip init-store",
                                      e.getMessage()));
    }

    private String writeDisabledRestServerConf(String... extraLines)
                                               throws IOException {
        return this.writeDisabledRestServerConfForGraphs(
                this.workDir.resolve(MISSING_GRAPHS_DIR), extraLines);
    }

    private String writeDisabledRestServerConfForGraphs(Path graphsDir,
                                                        String... extraLines)
                                                        throws IOException {
        // A distinct file per call, so two configs written by one test do not
        // collide inside its temporary directory
        Path restConf = this.workDir.resolve(
                "rest-server-" + this.confSeq++ + ".properties");
        List<String> lines = new ArrayList<>();
        lines.add(ServerOptions.GRAPHS.name() + "=" + graphsDir);
        lines.add(ServerOptions.INIT_STORE_ENABLED.name() + "=false");
        lines.addAll(Arrays.asList(extraLines));
        Files.write(restConf, lines, StandardCharsets.UTF_8);
        return restConf.toString();
    }

    private Path writeAuthGraphConfig(String backend) throws IOException {
        Path graphsDir = this.workDir.resolve("graphs-" + this.confSeq++);
        Files.createDirectories(graphsDir);
        Files.write(graphsDir.resolve("hugegraph.properties"),
                    Arrays.asList(CoreOptions.BACKEND.name() + "=" + backend),
                    StandardCharsets.UTF_8);
        return graphsDir;
    }

    /**
     * Stands in for a deployment that subclasses the built-in authenticator,
     * which still depends on the admin account it creates.
     */
    public static class DerivedAuthenticator extends StandardAuthenticator {
    }
}
