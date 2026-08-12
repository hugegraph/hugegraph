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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.hugegraph.ct.base.HGTestLogger;
import org.apache.hugegraph.ct.config.ClusterConfig;
import org.apache.hugegraph.ct.config.GraphConfig;
import org.apache.hugegraph.ct.config.PDConfig;
import org.apache.hugegraph.ct.config.ServerConfig;
import org.apache.hugegraph.ct.config.StoreConfig;
import org.apache.hugegraph.ct.node.BaseNodeWrapper;
import org.apache.hugegraph.ct.node.PDNodeWrapper;
import org.apache.hugegraph.ct.node.ServerNodeWrapper;
import org.apache.hugegraph.ct.node.StoreNodeWrapper;
import org.slf4j.Logger;

import lombok.Setter;

public abstract class AbstractEnv implements BaseEnv {

    private static final Logger LOG = HGTestLogger.ENV_LOG;

    protected ClusterConfig clusterConfig;
    protected List<PDNodeWrapper> pdNodeWrappers;
    protected List<ServerNodeWrapper> serverNodeWrappers;
    protected List<StoreNodeWrapper> storeNodeWrappers;
    protected final AtomicInteger nextPdIndex = new AtomicInteger(0);
    protected final AtomicInteger nextStoreIndex = new AtomicInteger(0);
    protected final AtomicInteger nextServerIndex = new AtomicInteger(0);

    @Setter
    protected int cluster_id = 0;

    protected AbstractEnv() {
        this.pdNodeWrappers = new ArrayList<>();
        this.serverNodeWrappers = new ArrayList<>();
        this.storeNodeWrappers = new ArrayList<>();
    }

    protected void init(int pdCnt, int storeCnt, int serverCnt) {
        this.nextPdIndex.set(pdCnt);
        this.nextStoreIndex.set(storeCnt);
        this.nextServerIndex.set(serverCnt);
        this.clusterConfig = new ClusterConfig(pdCnt, storeCnt, serverCnt);
        for (int i = 0; i < pdCnt; i++) {
            PDNodeWrapper pdNodeWrapper = new PDNodeWrapper(cluster_id, i);
            PDConfig pdConfig = clusterConfig.getPDConfig(i);
            pdNodeWrappers.add(pdNodeWrapper);
            pdConfig.writeConfig(pdNodeWrapper.getNodePath() + CONF_DIR);
            pdNodeWrapper.bindConfig(pdConfig);
        }

        for (int i = 0; i < storeCnt; i++) {
            StoreNodeWrapper storeNodeWrapper = new StoreNodeWrapper(cluster_id, i);
            StoreConfig storeConfig = clusterConfig.getStoreConfig(i);
            storeNodeWrappers.add(storeNodeWrapper);
            storeConfig.writeConfig(storeNodeWrapper.getNodePath() + CONF_DIR);
            storeNodeWrapper.bindConfig(storeConfig);
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
    public void startCluster() {
        startNodesParallel(pdNodeWrappers);
        startNodesParallel(storeNodeWrappers);
        startNodesParallel(serverNodeWrappers);
    }

    protected <T extends BaseNodeWrapper> void startNodesParallel(List<T> nodes) {
        if (nodes.isEmpty()) {
            return;
        }

        int threadCount = Math.min(nodes.size(), Runtime.getRuntime().availableProcessors());
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        try {
            List<Future<?>> startFutures = new ArrayList<>();
            for (T node : nodes) {
                startFutures.add(executor.submit(node::start));
            }
            for (int i = 0; i < startFutures.size(); i++) {
                try {
                    startFutures.get(i).get();
                } catch (Exception e) {
                    for (int j = 0; j < i; j++) {
                        try { nodes.get(j).stop(); } catch (Exception ignored) {}
                    }
                    throw new RuntimeException("Failed to start node " + nodes.get(i).getID(), e);
                }
            }

            List<Future<Boolean>> readyFutures = new ArrayList<>();
            for (T node : nodes) {
                readyFutures.add(executor.submit(
                    () -> node.waitForReady(NODE_START_TIMEOUT_MS)));
            }
            for (int i = 0; i < readyFutures.size(); i++) {
                try {
                    if (!readyFutures.get(i).get()) {
                        for (T node : nodes) {
                            try { node.stop(); } catch (Exception ignored) {}
                        }
                        throw new RuntimeException(
                            "Node " + nodes.get(i).getID() +
                            " failed to start within " + NODE_START_TIMEOUT_MS + "ms");
                    }
                } catch (Exception e) {
                    for (T node : nodes) {
                        try { node.stop(); } catch (Exception ignored) {}
                    }
                    throw new RuntimeException(
                        "Failed to wait for node " + nodes.get(i).getID(), e);
                }
            }
        } finally {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(NODE_START_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void stopCluster() {
        for (ServerNodeWrapper serverNodeWrapper : serverNodeWrappers) {
            serverNodeWrapper.stop();
        }
        for (StoreNodeWrapper storeNodeWrapper : storeNodeWrappers) {
            storeNodeWrapper.stop();
        }
        for (PDNodeWrapper pdNodeWrapper : pdNodeWrappers) {
            pdNodeWrapper.stop();
        }
    }

    public ClusterConfig getConf() {
        return this.clusterConfig;
    }

    public List<String> getPDRestAddrs() {
        return clusterConfig.getPDRestAddrs();
    }

    public List<String> getPDGrpcAddrs() {
        return clusterConfig.getPDGrpcAddrs();
    }

    public List<String> getStoreRestAddrs() {
        return clusterConfig.getStoreRestAddrs();
    }

    public List<String> getStoreGrpcAddrs() {
        return clusterConfig.getStoreGrpcAddrs();
    }

    public List<String> getServerRestAddrs() {
        return clusterConfig.getServerRestAddrs();
    }

    public List<String> getPDNodeDir() {
        List<String> nodeDirs = new ArrayList<>();
        for (PDNodeWrapper pdNodeWrapper : pdNodeWrappers) {
            nodeDirs.add(pdNodeWrapper.getNodePath());
        }
        return nodeDirs;
    }

    public List<String> getStoreNodeDir() {
        List<String> nodeDirs = new ArrayList<>();
        for (StoreNodeWrapper storeNodeWrapper : storeNodeWrappers) {
            nodeDirs.add(storeNodeWrapper.getNodePath());
        }
        return nodeDirs;
    }

    public List<String> getServerNodeDir() {
        List<String> nodeDirs = new ArrayList<>();
        for (ServerNodeWrapper serverNodeWrapper : serverNodeWrappers) {
            nodeDirs.add(serverNodeWrapper.getNodePath());
        }
        return nodeDirs;
    }

    @Override
    public int addPDNode() {
        int newIndex = nextPdIndex.getAndIncrement();
        PDConfig pdConfig = clusterConfig.addPDConfig();
        PDNodeWrapper pdNode = new PDNodeWrapper(cluster_id, newIndex);
        pdConfig.writeConfig(pdNode.getNodePath() + CONF_DIR);
        pdNode.bindConfig(pdConfig);
        pdNodeWrappers.add(pdNode);
        pdNode.start();
        if (!pdNode.waitForReady(NODE_START_TIMEOUT_MS)) {
            throw new RuntimeException("New PD node " + newIndex + " failed to start");
        }
        return newIndex;
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

    @Override
    public int addServerNode() {
        int newIndex = nextServerIndex.getAndIncrement();
        ServerConfig serverConfig = clusterConfig.addServerConfig();
        GraphConfig graphConfig = clusterConfig.getGraphConfig(
            clusterConfig.getServerConfigCount() - 1);
        ServerNodeWrapper serverNode = new ServerNodeWrapper(cluster_id, newIndex);
        serverConfig.setServerID(serverNode.getID());
        serverConfig.setRole("worker");
        serverConfig.writeConfig(serverNode.getNodePath() + CONF_DIR);
        graphConfig.writeConfig(serverNode.getNodePath() + CONF_DIR);
        serverNode.bindConfig(serverConfig);
        serverNodeWrappers.add(serverNode);
        serverNode.start();
        if (!serverNode.waitForReady(NODE_START_TIMEOUT_MS)) {
            throw new RuntimeException("New Server node " + newIndex + " failed to start");
        }
        return newIndex;
    }

    @Override
    public void removePDNode(int index) {
        int listPos = -1;
        for (int i = 0; i < pdNodeWrappers.size(); i++) {
            if (pdNodeWrappers.get(i).getIndex() == index) {
                listPos = i;
                break;
            }
        }
        if (listPos < 0) {
            throw new IllegalArgumentException("PD node with index " + index + " not found");
        }
        if (pdNodeWrappers.size() <= 1) {
            throw new IllegalStateException("Cannot remove the last PD node");
        }
        PDNodeWrapper target = pdNodeWrappers.get(listPos);
        target.stop();
        pdNodeWrappers.remove(listPos);
        clusterConfig.removePDConfig(listPos);
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
        if (listPos < 0) {
            throw new IllegalArgumentException("Store node with index " + index + " not found");
        }
        if (storeNodeWrappers.size() <= 1) {
            throw new IllegalStateException("Cannot remove the last Store node");
        }
        StoreNodeWrapper target = storeNodeWrappers.get(listPos);
        target.stop();
        storeNodeWrappers.remove(listPos);
        clusterConfig.removeStoreConfig(listPos);
    }

    @Override
    public void removeServerNode(int index) {
        int listPos = -1;
        for (int i = 0; i < serverNodeWrappers.size(); i++) {
            if (serverNodeWrappers.get(i).getIndex() == index) {
                listPos = i;
                break;
            }
        }
        if (listPos < 0) {
            throw new IllegalArgumentException("Server node with index " + index + " not found");
        }
        if (serverNodeWrappers.size() <= 1) {
            throw new IllegalStateException("Cannot remove the last Server node");
        }
        ServerNodeWrapper target = serverNodeWrappers.get(listPos);
        target.stop();
        serverNodeWrappers.remove(listPos);
        clusterConfig.removeServerConfig(listPos);
    }

    @Override
    public int getAlivePDNodeCount() {
        return (int) pdNodeWrappers.stream().filter(BaseNodeWrapper::isAlive).count();
    }

    @Override
    public int getAliveStoreNodeCount() {
        return (int) storeNodeWrappers.stream().filter(BaseNodeWrapper::isAlive).count();
    }

    @Override
    public int getAliveServerNodeCount() {
        return (int) serverNodeWrappers.stream().filter(BaseNodeWrapper::isAlive).count();
    }

    @Override
    public List<? extends BaseNodeWrapper> getPDNodeWrappers() {
        return pdNodeWrappers;
    }

    @Override
    public List<? extends BaseNodeWrapper> getStoreNodeWrappers() {
        return storeNodeWrappers;
    }

    @Override
    public List<? extends BaseNodeWrapper> getServerNodeWrappers() {
        return serverNodeWrappers;
    }

}
