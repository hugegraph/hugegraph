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

package org.apache.hugegraph.ct.env;

import static org.apache.hugegraph.ct.base.ClusterConstant.CONF_DIR;
import static org.apache.hugegraph.ct.base.ClusterConstant.NODE_START_TIMEOUT_MS;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.hugegraph.ct.base.EnvUtil;
import org.apache.hugegraph.ct.base.HGTestLogger;
import org.apache.hugegraph.ct.config.ClusterConfig;
import org.apache.hugegraph.ct.config.GraphConfig;
import org.apache.hugegraph.ct.config.PDConfig;
import org.apache.hugegraph.ct.config.ServerConfig;
import org.apache.hugegraph.ct.config.StoreConfig;
import org.apache.hugegraph.ct.node.BaseNodeWrapper;
import org.apache.hugegraph.ct.node.MiniStoreProxy;
import org.apache.hugegraph.ct.node.PDNodeWrapper;
import org.apache.hugegraph.ct.node.ServerNodeWrapper;
import org.apache.hugegraph.ct.node.StoreNodeWrapper;
import org.slf4j.Logger;

public class HybridEnv extends AbstractEnv {

    private static final Logger LOG = HGTestLogger.ENV_LOG;

    protected List<MiniStoreProxy> miniStoreProxies;
    private final int realStoreCount;
    private final int miniStoreCount;
    private final AtomicInteger nextMiniStoreIndex = new AtomicInteger(0);

    public HybridEnv(int pdCount, int realStoreCount, int miniStoreCount, int serverCount) {
        super();
        this.realStoreCount = realStoreCount;
        this.miniStoreCount = miniStoreCount;
        this.miniStoreProxies = new ArrayList<>();
        init(pdCount, realStoreCount, miniStoreCount, serverCount);
    }

    protected void init(int pdCnt, int realStoreCnt, int miniStoreCnt, int serverCnt) {
        int totalStoreCnt = realStoreCnt + miniStoreCnt;
        this.nextPdIndex.set(pdCnt);
        this.nextStoreIndex.set(totalStoreCnt);
        this.nextServerIndex.set(serverCnt);
        this.nextMiniStoreIndex.set(miniStoreCnt);
        // PD only manages real Store nodes for Raft; MiniStoreProxy is a lightweight REST proxy
        this.clusterConfig = new ClusterConfig(pdCnt, realStoreCnt, serverCnt);

        for (int i = 0; i < pdCnt; i++) {
            PDNodeWrapper pdNodeWrapper = new PDNodeWrapper(cluster_id, i);
            PDConfig pdConfig = clusterConfig.getPDConfig(i);
            pdNodeWrappers.add(pdNodeWrapper);
            pdConfig.writeConfig(pdNodeWrapper.getNodePath() + CONF_DIR);
            pdNodeWrapper.bindConfig(pdConfig);
        }

        for (int i = 0; i < realStoreCnt; i++) {
            StoreNodeWrapper storeNodeWrapper = new StoreNodeWrapper(cluster_id, i);
            StoreConfig storeConfig = clusterConfig.getStoreConfig(i);
            storeNodeWrappers.add(storeNodeWrapper);
            storeConfig.writeConfig(storeNodeWrapper.getNodePath() + CONF_DIR);
            storeNodeWrapper.bindConfig(storeConfig);
        }

        for (int i = 0; i < miniStoreCnt; i++) {
            int configIndex = realStoreCnt + i;
            int grpcPort = EnvUtil.getAvailablePort();
            int restPort = EnvUtil.getAvailablePort();
            MiniStoreProxy proxy = new MiniStoreProxy(cluster_id, configIndex, grpcPort, restPort);
            if (!pdNodeWrappers.isEmpty()) {
                PDConfig pdConfig = clusterConfig.getPDConfig(0);
                proxy.setPdAddress("127.0.0.1:" + pdConfig.getRestPort());
            }
            miniStoreProxies.add(proxy);
        }

        for (int i = 0; i < serverCnt; i++) {
            ServerNodeWrapper serverNodeWrapper = new ServerNodeWrapper(cluster_id, i);
            serverNodeWrappers.add(serverNodeWrapper);
            ServerConfig serverConfig = clusterConfig.getServerConfig(i);
            serverConfig.setServerID(serverNodeWrapper.getID());
            GraphConfig graphConfig = clusterConfig.getGraphConfig(i);
            if (i == 0) {
                serverConfig.setRole("master");
            } else {
                serverConfig.setRole("worker");
            }
            serverConfig.writeConfig(serverNodeWrapper.getNodePath() + CONF_DIR);
            graphConfig.writeConfig(serverNodeWrapper.getNodePath() + CONF_DIR);
            serverNodeWrapper.bindConfig(serverConfig);
        }
    }

    @Override
    public void init() {
        init(1, 1, 0, 1);
    }

    @Override
    public void startCluster() {
        startNodesParallel(pdNodeWrappers);
        startNodesParallel(storeNodeWrappers);
        startNodesParallel(miniStoreProxies);
        startNodesParallel(serverNodeWrappers);
    }

    @Override
    public void stopCluster() {
        for (ServerNodeWrapper serverNodeWrapper : serverNodeWrappers) {
            serverNodeWrapper.stop();
        }
        for (MiniStoreProxy proxy : miniStoreProxies) {
            proxy.stop();
        }
        for (StoreNodeWrapper storeNodeWrapper : storeNodeWrappers) {
            storeNodeWrapper.stop();
        }
        for (PDNodeWrapper pdNodeWrapper : pdNodeWrappers) {
            pdNodeWrapper.stop();
        }
    }

    @Override
    public int getAliveStoreNodeCount() {
        int count = (int) storeNodeWrappers.stream().filter(BaseNodeWrapper::isAlive).count();
        count += (int) miniStoreProxies.stream().filter(BaseNodeWrapper::isAlive).count();
        return count;
    }

    @Override
    public List<String> getStoreNodeDir() {
        List<String> nodeDirs = new ArrayList<>();
        for (StoreNodeWrapper storeNodeWrapper : storeNodeWrappers) {
            nodeDirs.add(storeNodeWrapper.getNodePath());
        }
        for (MiniStoreProxy proxy : miniStoreProxies) {
            nodeDirs.add(proxy.getNodePath());
        }
        return nodeDirs;
    }

    @Override
    public List<? extends BaseNodeWrapper> getStoreNodeWrappers() {
        List<BaseNodeWrapper> all = new ArrayList<>();
        all.addAll(storeNodeWrappers);
        all.addAll(miniStoreProxies);
        return all;
    }

    @Override
    public int addStoreNode() {
        int newIndex = nextStoreIndex.getAndIncrement();
        StoreConfig storeConfig = clusterConfig.addStoreConfig();
        StoreNodeWrapper storeNode = new StoreNodeWrapper(cluster_id, newIndex);
        storeConfig.writeConfig(storeNode.getNodePath() + CONF_DIR);
        storeNode.bindConfig(storeConfig);
        storeNodeWrappers.add(storeNode);
        storeNode.start();
        if (!storeNode.waitForReady(NODE_START_TIMEOUT_MS)) {
            throw new RuntimeException("New Store node " + newIndex + " failed to start");
        }
        return newIndex;
    }

    public int addMiniStoreNode() {
        int newIndex = nextMiniStoreIndex.getAndIncrement();
        int grpcPort = EnvUtil.getAvailablePort();
        int restPort = EnvUtil.getAvailablePort();
        MiniStoreProxy proxy = new MiniStoreProxy(cluster_id, newIndex, grpcPort, restPort);
        if (!pdNodeWrappers.isEmpty()) {
            PDConfig pdConfig = clusterConfig.getPDConfig(0);
            proxy.setPdAddress("127.0.0.1:" + pdConfig.getRestPort());
        }
        miniStoreProxies.add(proxy);
        proxy.start();
        if (!proxy.waitForReady(NODE_START_TIMEOUT_MS)) {
            throw new RuntimeException("New MiniStore node " + newIndex + " failed to start");
        }
        return newIndex;
    }

    @Override
    public void removeStoreNode(int index) {
        int listPos = -1;
        for (int i = 0; i < storeNodeWrappers.size(); i++) {
            if (storeNodeWrappers.get(i).getIndex() == index) {
                listPos = i;
                break;
            }
        }
        if (listPos >= 0) {
            if (storeNodeWrappers.size() <= 1 && miniStoreProxies.isEmpty()) {
                throw new IllegalStateException("Cannot remove the last Store node");
            }
            StoreNodeWrapper target = storeNodeWrappers.get(listPos);
            target.stop();
            storeNodeWrappers.remove(listPos);
            clusterConfig.removeStoreConfig(listPos);
            return;
        }

        int miniPos = -1;
        for (int i = 0; i < miniStoreProxies.size(); i++) {
            if (miniStoreProxies.get(i).getIndex() == index) {
                miniPos = i;
                break;
            }
        }
        if (miniPos >= 0) {
            MiniStoreProxy target = miniStoreProxies.get(miniPos);
            target.stop();
            miniStoreProxies.remove(miniPos);
            return;
        }

        throw new IllegalArgumentException("Store node with index " + index + " not found");
    }
}
