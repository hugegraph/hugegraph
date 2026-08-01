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

    public static void main(String[] args) throws Exception {
        E.checkArgument(args.length == 1,
                        "HugeGraph init-store need to pass the config file " +
                        "of RestServer, like: conf/rest-server.properties");
        E.checkArgument(args[0].endsWith(".properties"),
                        "Expect the parameter is properties config file.");

        String restConf = args[0];

        // Server options alone can answer the gate below. Backend and plugin
        // registration waits for the enabled path, because registerPlugins()
        // runs every plugin's register() and propagates its failures.
        RegisterUtil.registerServer();

        HugeConfig restServerConfig = new HugeConfig(restConf);

        /*
         * PD/HStore deployments let the storage side own the metadata, so
         * init-store has nothing to do; on Kubernetes it re-ran on every Server
         * pod restart, since the entrypoint's flag file does not survive one.
         * Skipping also skips creating the built-in admin, which only the PD
         * startup path can replace, and only for a PD-backed HStore auth graph.
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
            StandardAuthenticator.initAdminUserIfNeeded(restConf);
        } finally {
            for (HugeGraph graph : graphs) {
                graph.close();
            }
            HugeFactory.shutdown(30L, true);
        }
    }

    /**
     * Skipping leaves the built-in admin to GraphManager.initAdminUserIfNeeded()
     * on the PD startup path, which writes it to PD metadata. Only an HStore
     * auth graph reads that metadata back, so every other local built-in-auth
     * configuration would start a server nobody can log in to. Remote auth and
     * custom authenticators keep their identities elsewhere and are exempt.
     */
    private static void checkAdminBootstrapReachable(HugeConfig conf,
                                                     String restConf) {
        if (!requiresLocalBuiltinAdmin(conf)) {
            return;
        }
        if (!conf.get(ServerOptions.USE_PD)) {
            throw unreachableAdmin(restConf, ServerOptions.USE_PD.name() +
                                             " is false");
        }

        String name = conf.get(ServerOptions.AUTH_GRAPH_STORE);
        String path = ConfigUtil.scanGraphsDir(
                conf.get(ServerOptions.GRAPHS)).get(name);
        if (path == null) {
            throw unreachableAdmin(restConf, "auth graph '" + name +
                                             "' has no local configuration");
        }
        String backend = new HugeConfig(path).get(CoreOptions.BACKEND);
        if (!"hstore".equals(backend)) {
            throw unreachableAdmin(restConf, "auth graph '" + name +
                                             "' uses backend '" + backend +
                                             "', not 'hstore'");
        }
    }

    private static IllegalStateException unreachableAdmin(String restConf,
                                                          String reason) {
        return new IllegalStateException(String.format(
                "Refusing to skip init-store: '%s' configures the built-in " +
                "authenticator but %s, so the admin created on the PD startup " +
                "path would be unreachable. See docker/README.md.",
                restConf, reason));
    }

    /**
     * HugeAuthenticator.loadAuthenticator() accepts any implementation class,
     * and only StandardAuthenticator bootstraps HugeGraph's built-in admin
     * account. A custom one (LDAP, OIDC, a plugin) manages its identities
     * elsewhere, so it must not be held to the requirement above. The class is
     * resolved without initializing it, and one that is not on the init-store
     * classpath is by definition not the built-in authenticator.
     */
    private static boolean requiresLocalBuiltinAdmin(HugeConfig conf) {
        if (!conf.get(ServerOptions.AUTH_REMOTE_URL).isEmpty()) {
            return false;
        }
        String authClass = conf.get(ServerOptions.AUTHENTICATOR);
        if (authClass.isEmpty()) {
            return false;
        }
        try {
            Class<?> clazz = Class.forName(authClass, false,
                                           InitStore.class.getClassLoader());
            return StandardAuthenticator.class.isAssignableFrom(clazz);
        } catch (ClassNotFoundException | LinkageError e) {
            LOG.info("Authenticator '{}' is not on the init-store classpath, " +
                     "so it is not the built-in one", authClass);
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
