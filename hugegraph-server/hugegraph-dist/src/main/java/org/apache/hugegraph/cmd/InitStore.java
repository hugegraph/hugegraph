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

package org.apache.hugegraph.cmd;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.hugegraph.HugeFactory;
import org.apache.hugegraph.HugeGraph;
import org.apache.hugegraph.auth.StandardAuthenticator;
import org.apache.hugegraph.backend.store.BackendStoreInfo;
import org.apache.hugegraph.config.CoreOptions;
import org.apache.hugegraph.config.HugeConfig;
import org.apache.hugegraph.config.ServerOptions;
import org.apache.hugegraph.constant.ServiceConstant;
import org.apache.hugegraph.dist.RegisterUtil;
import org.apache.hugegraph.meta.PdMetaDriver.PDAuthConfig;
import org.apache.hugegraph.util.ConfigUtil;
import org.apache.hugegraph.util.E;
import org.apache.hugegraph.util.Log;
import org.apache.tinkerpop.gremlin.structure.util.GraphFactory;
import org.slf4j.Logger;

public class InitStore {

    private static final Logger LOG = Log.logger(InitStore.class);
    private static final String USE_CONFIGURED_ADMIN_PASSWORD =
            "--use-configured-admin-password";

    public static void main(String[] args) throws Exception {
        E.checkArgument(args.length == 1 ||
                        args.length == 2 &&
                        USE_CONFIGURED_ADMIN_PASSWORD.equals(args[1]),
                        "HugeGraph init-store need to pass the config file " +
                        "of RestServer, like: conf/rest-server.properties, " +
                        "with an optional %s flag",
                        USE_CONFIGURED_ADMIN_PASSWORD);
        E.checkArgument(args[0].endsWith(".properties"),
                        "Expect the parameter is properties config file.");

        String restConf = args[0];
        boolean useConfiguredAdminPassword = args.length == 2;

        /*
         * Only the server options are needed to read the gate below. Backend
         * and plugin registration is deferred to the enabled path:
         * registerPlugins() invokes every discovered plugin's register() and
         * propagates their failures, which must not happen on a path that is
         * documented to be a no-op.
         */
        RegisterUtil.registerServer();

        HugeConfig restServerConfig = new HugeConfig(restConf);

        /*
         * Distributed deployments (PD/HStore) let the storage side own the
         * metadata, so there is nothing for init-store to do. The option
         * defaults to true, keeping standalone/tarball installs on the full
         * init path.
         *
         * The loop below already skips hstore backends, so what this gate
         * additionally avoids is scanning the graphs directory (which must
         * otherwise exist), and, when auth is configured, opening the auth
         * graph store in initAdminUserIfNeeded(). On Kubernetes that ran on
         * every Server pod restart, since the entrypoint's init flag file does
         * not survive one.
         *
         * NOTE: skipping also means the built-in admin account is not created
         * here. The PD startup path can replace that work only when usePD is
         * true and the configured auth graph uses HStore, whose auth manager
         * reads the same PD metadata. checkAdminBootstrapReachable() rejects
         * every other local built-in-auth configuration before returning.
         */
        if (!restServerConfig.get(ServerOptions.INIT_STORE_ENABLED)) {
            LOG.warn("Skipping init-store: '{}' is false in '{}'. Local " +
                     "backend and admin initialization are not performed.",
                     ServerOptions.INIT_STORE_ENABLED.name(), restConf);
            checkAdminBootstrapReachable(restServerConfig, restConf);
            return;
        }

        RegisterUtil.registerBackends();
        RegisterUtil.registerPlugins();

        PDAuthConfig.setAuthority(
                ServiceConstant.SERVICE_NAME,
                ServiceConstant.AUTHORITY);

        String graphsDir = restServerConfig.get(ServerOptions.GRAPHS);
        Map<String, String> graph2ConfigPaths = ConfigUtil.scanGraphsDir(graphsDir);

        List<HugeGraph> graphs = new ArrayList<>(graph2ConfigPaths.size());
        try {
            for (Map.Entry<String, String> entry : graph2ConfigPaths.entrySet()) {
                String configPath = entry.getValue();
                HugeConfig config = new HugeConfig(configPath);
                if (Objects.equals(config.get(CoreOptions.BACKEND), "hstore")) {
                    // skip initializing hstore backend
                    continue;
                }
                graphs.add(initGraph(configPath));
            }
            if (requiresBuiltinAdmin(
                    restServerConfig.get(ServerOptions.AUTHENTICATOR))) {
                if (useConfiguredAdminPassword) {
                    StandardAuthenticator.initAdminUserIfNeeded(
                            restConf,
                            restServerConfig.get(ServerOptions.ADMIN_PA));
                } else {
                    StandardAuthenticator.initAdminUserIfNeeded(restConf);
                }
            }
        } finally {
            for (HugeGraph graph : graphs) {
                graph.close();
            }
            HugeFactory.shutdown(30L, true);
        }
    }

    /**
     * Skipping means init-store does not create the built-in admin account,
     * and the only other component that creates it is
     * GraphManager.initAdminUserIfNeeded(), reached from loadMetaFromPD(). That
     * creates the user in PD metadata, so it is reachable by the authenticator
     * only when usePD is true and the auth graph uses HStore's
     * StandardAuthManagerV2. Failing here rather than returning zero keeps
     * tarball and init-job callers from continuing into an unusable server.
     * <p>
     * Remote auth is exempt: the auth manager is then an RPC client, and
     * StandardAuthenticator only bootstraps an admin for a local one. So is
     * any authenticator other than the built-in one, which keeps its
     * identities outside HugeGraph and needs no such account.
     */
    private static void checkAdminBootstrapReachable(HugeConfig conf,
                                                     String restConf) {
        if (!requiresBuiltinAdmin(conf.get(ServerOptions.AUTHENTICATOR)) ||
            !conf.get(ServerOptions.AUTH_REMOTE_URL).isEmpty()) {
            return;
        }

        if (!conf.get(ServerOptions.USE_PD)) {
            throw adminBootstrapUnavailable(restConf, String.format(
                    "'%s' is false", ServerOptions.USE_PD.name()), null);
        }

        String graphName = conf.get(ServerOptions.AUTH_GRAPH_STORE);
        String graphPath;
        try {
            Map<String, String> graphConfs = ConfigUtil.scanGraphsDir(
                    conf.get(ServerOptions.GRAPHS));
            graphPath = graphConfs.get(graphName);
        } catch (RuntimeException e) {
            throw adminBootstrapUnavailable(
                    restConf, "the auth graph configuration cannot be read", e);
        }
        if (graphPath == null) {
            throw adminBootstrapUnavailable(restConf, String.format(
                    "auth graph '%s' has no local configuration", graphName),
                    null);
        }

        HugeConfig graphConfig = new HugeConfig(graphPath);
        String backend = graphConfig.get(CoreOptions.BACKEND);
        if (!Objects.equals(backend, "hstore")) {
            throw adminBootstrapUnavailable(restConf, String.format(
                    "auth graph '%s' uses backend '%s', not 'hstore'",
                    graphName, backend), null);
        }
    }

    private static IllegalStateException adminBootstrapUnavailable(
                                                   String restConf,
                                                   String reason,
                                                   RuntimeException cause) {
        String message = String.format(
                "Refusing to skip init-store: the built-in authenticator is " +
                "configured in '%s', but %s, so the admin created on the PD " +
                "startup path would not be available to that authenticator. " +
                "Set '%s' to true with an HStore auth graph, set '%s' to " +
                "delegate auth, configure an external authenticator, or leave " +
                "'%s' enabled so init-store creates the account.",
                restConf, reason, ServerOptions.USE_PD.name(),
                ServerOptions.AUTH_REMOTE_URL.name(),
                ServerOptions.INIT_STORE_ENABLED.name());
        return cause == null ? new IllegalStateException(message) :
               new IllegalStateException(message, cause);
    }

    /**
     * HugeAuthenticator.loadAuthenticator() accepts any implementation class,
     * and only StandardAuthenticator bootstraps HugeGraph's built-in admin
     * account. A custom one (LDAP, OIDC, a plugin) manages its identities
     * elsewhere, so it must not be held to the requirement above. The class is
     * resolved without initializing it, and one that is not on the init-store
     * classpath is by definition not the built-in authenticator.
     */
    private static boolean requiresBuiltinAdmin(String authClass) {
        if (authClass.isEmpty()) {
            return false;
        }
        try {
            Class<?> clazz = Class.forName(authClass, false,
                                           InitStore.class.getClassLoader());
            return StandardAuthenticator.class.isAssignableFrom(clazz);
        } catch (ClassNotFoundException | LinkageError e) {
            LOG.info("Authenticator '{}' is not resolvable here, assuming it " +
                     "does not need the built-in admin account", authClass);
            return false;
        }
    }

    private static HugeGraph initGraph(String configPath) throws Exception {
        LOG.info("Init graph with config file: {}", configPath);
        HugeConfig config = new HugeConfig(configPath);
        // Forced set RAFT_MODE to false when initializing backend
        config.setProperty(CoreOptions.RAFT_MODE.name(), "false");
        HugeGraph graph = (HugeGraph) GraphFactory.open(config);

        try {
            BackendStoreInfo backendStoreInfo = graph.backendStoreInfo();
            if (backendStoreInfo.exists()) {
                backendStoreInfo.checkVersion();
                /*
                 * Init the required information for creating the admin account
                 * (when switch from non-auth mode to auth mode)
                 */
                graph.initSystemInfo();
                LOG.info("Skip init-store due to the backend store of '{}' " +
                         "had been initialized", graph.name());
            } else {
                initBackend(graph);
            }
        } catch (Throwable e) {
            graph.close();
            throw e;
        }
        return graph;
    }

    private static void initBackend(final HugeGraph graph) {
        // Only explicitly transient backend failures can be retried safely
        graph.initBackend();
    }
}
