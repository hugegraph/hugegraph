/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.apache.hugegraph.dist;

import static org.apache.hugegraph.core.GraphManager.NAME_REGEX;
import static org.apache.hugegraph.space.GraphSpace.DEFAULT_GRAPH_SPACE_SERVICE_NAME;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.apache.commons.configuration2.MapConfiguration;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.hugegraph.HugeException;
import org.apache.hugegraph.HugeFactory;
import org.apache.hugegraph.auth.StandardAuthenticator;
import org.apache.hugegraph.config.HugeConfig;
import org.apache.hugegraph.config.ServerOptions;
import org.apache.hugegraph.event.EventHub;
import org.apache.hugegraph.meta.MetaManager;
import org.apache.hugegraph.server.RestServer;
import org.apache.hugegraph.space.GraphSpace;
import org.apache.hugegraph.task.TaskManager;
import org.apache.hugegraph.util.ConfigUtil;
import org.apache.hugegraph.util.E;
import org.apache.hugegraph.util.Log;
import org.apache.tinkerpop.gremlin.server.GremlinServer;
import org.slf4j.Logger;

import com.google.common.base.Strings;

public class HugeGraphServer {

    private static final Logger LOG = Log.logger(HugeGraphServer.class);

    private final GremlinServer gremlinServer;
    private final RestServer restServer;
    private final MetaManager metaManager = MetaManager.instance();

    public HugeGraphServer(String gremlinServerConf, String restServerConf,
                           String graphSpace, String serviceId,
                           List<String> metaEndpoints, String cluster,
                           String pdPeers, Boolean withCa, String caFile,
                           String clientCaFile, String clientKeyFile)
            throws Exception {
        // Only switch on security manager after HugeGremlinServer started
        SecurityManager securityManager = System.getSecurityManager();
        System.setSecurityManager(null);

        E.checkArgument(metaEndpoints.size() > 0,
                        "The meta endpoints could not be null");
        E.checkArgument(StringUtils.isNotEmpty(cluster),
                        "The cluster could not be null");
        if (StringUtils.isEmpty(graphSpace)) {
            LOG.info("Start service with 'DEFAULT' graph space");
            graphSpace = "DEFAULT";
        }
        checkName(graphSpace, "graph space");
        if (StringUtils.isEmpty(serviceId)) {
            LOG.info("Start service with 'DEFAULT' graph space");
            serviceId = "DEFAULT";
        }
        checkName(serviceId, "service");

        // try to fetch rest server config and gremlin config from etcd
        if (!withCa) {
            caFile = null;
            clientCaFile = null;
            clientKeyFile = null;
        }
        this.metaManager.connect(cluster, MetaManager.MetaDriverType.PD,
                                 caFile, clientCaFile, clientKeyFile,
                                 metaEndpoints);

        HugeConfig restServerConfig;
        Map<String, Object> restMap = this.metaManager.restProperties(graphSpace, serviceId);
        if (restMap == null || restMap.isEmpty()) {
            restServerConfig = new HugeConfig(restServerConf);
        } else {
            restServerConfig = new HugeConfig(new MapConfiguration(restMap));
        }
        if (StringUtils.isEmpty(restServerConfig.get(ServerOptions.AUTHENTICATOR))) {
            restServerConfig.addProperty(ServerOptions.AUTHENTICATOR.name(),
                                         "org.apache.hugegraph.auth.StandardAuthenticator");
        }
        restServerConfig.addProperty(ServerOptions.SERVICE_ID.name(),
                                     serviceId);
        restServerConfig.addProperty(ServerOptions.SERVICE_GRAPH_SPACE.name(),
                                     graphSpace);
        restServerConfig.setProperty(ServerOptions.CLUSTER.name(), cluster);
        restServerConfig.addProperty(ServerOptions.META_ENDPOINTS.name(),
                                     "[" + String.join(",", metaEndpoints) + "]");
        restServerConfig.addProperty(ServerOptions.META_USE_CA.name(), withCa.toString());
        if (withCa) {
            restServerConfig.addProperty(ServerOptions.META_CA.name(), caFile);
            restServerConfig.addProperty(ServerOptions.META_CLIENT_CA.name(),
                                         clientCaFile);
            restServerConfig.addProperty(ServerOptions.META_CLIENT_KEY.name(),
                                         clientKeyFile);
        }
        String metaPDPeers = this.metaManager.hstorePDPeers();
        if (StringUtils.isNotEmpty(metaPDPeers)) {
            restServerConfig.addProperty(ServerOptions.PD_PEERS.name(),
                                         metaPDPeers);
        } else {
            restServerConfig.addProperty(ServerOptions.PD_PEERS.name(),
                                         pdPeers);
        }
        int threads = restServerConfig.get(
                ServerOptions.SERVER_EVENT_HUB_THREADS);
        EventHub.init(threads);
        EventHub hub = new EventHub("gremlin=>hub<=rest");
        ConfigUtil.checkGremlinConfig(gremlinServerConf);

        String graphsDir = null;
        if (restServerConfig.get(ServerOptions.GRAPH_LOAD_FROM_LOCAL_CONFIG)) {
            graphsDir = restServerConfig.get(ServerOptions.GRAPHS);
        }

        TaskManager.instance(restServerConfig.get(ServerOptions.TASK_THREADS));
        TaskManager.instance().serviceGraphSpace(graphSpace);
        StandardAuthenticator.initAdminUserIfNeeded(restServerConfig,
                                                    metaEndpoints, cluster, withCa, caFile,
                                                    clientCaFile, clientKeyFile);

        GraphSpace gs = this.metaManager.graphSpace(graphSpace);
        String olapNamespace = (null != gs
                                && !Strings.isNullOrEmpty(gs.olapNamespace())
                                && !"null".equals(gs.olapNamespace()))
                               ? gs.olapNamespace()
                               : ServerOptions.SERVER_DEFAULT_OLAP_K8S_NAMESPACE.defaultValue();

        // Use olapNamespace of graph space when:
        // 1. It's not default graph space.
        // 2. Or it's default graph space, and olapNamespace has already been set a not 'null'
        // value.
        // Otherwise, use k8s.namespace in conf
        restServerConfig.setProperty(ServerOptions.K8S_NAMESPACE.name(), olapNamespace);

        try {
            // Start GremlinServer
            String gsText = this.metaManager.gremlinYaml(graphSpace,
                                                         serviceId);
            if (StringUtils.isEmpty(gsText)) {
                this.gremlinServer = HugeGremlinServer.start(gremlinServerConf,
                                                             graphsDir, hub);
            } else {
                InputStream is = IOUtils.toInputStream(gsText,
                                                       StandardCharsets.UTF_8);
                this.gremlinServer = HugeGremlinServer.start(is, graphsDir,
                                                             hub);
            }
        } catch (Throwable e) {
            LOG.error("HugeGremlinServer start error: ", e);
            HugeFactory.shutdown(30L);
            throw e;
        } finally {
            System.setSecurityManager(securityManager);
        }

        try {
            // Start HugeRestServer
            this.restServer = HugeRestServer.start(restServerConfig, hub);
        } catch (Throwable e) {
            LOG.error("HugeRestServer start error: ", e);
            try {
                this.gremlinServer.stop().get();
            } catch (Throwable t) {
                LOG.error("GremlinServer stop error: ", t);
            }
            HugeFactory.shutdown(30L);
            throw e;
        }
    }

    public static void register() {
        RegisterUtil.registerBackends();
        RegisterUtil.registerPlugins();
        RegisterUtil.registerServer();
    }

    private static void checkName(String name, String type) {
        if (DEFAULT_GRAPH_SPACE_SERVICE_NAME.equals(name)) {
            return;
        }
        E.checkArgument(name.matches(NAME_REGEX),
                        "Invalid name '%s' for %s, valid name is up to 128 " +
                        "alpha-numeric characters and underscores and only " +
                        "letters are supported as first letter. " +
                        "Note: letter is lower case", name, type);
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 11) {
            String msg = "Start HugeGraphServer need to pass parameter's " +
                         "length >= 11, they are the config files of " +
                         "GremlinServer and RestServer, for example: " +
                         "conf/gremlin-server.yaml " +
                         "conf/rest-server.properties ......";
            LOG.error(msg);
            throw new HugeException(msg);
        }
        if (!"hg".equals(args[5])) {
            args[5] = "hg";
            LOG.warn("cluster not allowed to be set");
        }

        HugeGraphServer.register();

        List<String> metaEndpoints = Arrays.asList(args[4].split(","));
        Boolean withCa = args[7].equals("true");
        HugeGraphServer server = new HugeGraphServer(args[0], args[1],
                                                     args[2], args[3],
                                                     metaEndpoints, args[5],
                                                     args[6], withCa,
                                                     args[8], args[9],
                                                     args[10]);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("HugeGraphServer stopping");
            server.stop();
            LOG.info("HugeGraphServer stopped");
        }, "hugegraph-server-shutdown"));
    }

    public void stop() {
        try {
            this.restServer.shutdown().get();
            LOG.info("HugeRestServer stopped");
        } catch (Throwable e) {
            LOG.error("HugeRestServer stop error: ", e);
        }

        try {
            this.gremlinServer.stop().get();
            LOG.info("HugeGremlinServer stopped");
        } catch (Throwable e) {
            LOG.error("HugeGremlinServer stop error: ", e);
        }

        try {
            HugeFactory.shutdown(30L);
            LOG.info("HugeGraph stopped");
        } catch (Throwable e) {
            LOG.error("Failed to stop HugeGraph: ", e);
        }
    }
}
