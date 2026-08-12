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

import java.util.List;

import org.apache.hugegraph.ct.config.ClusterConfig;
import org.apache.hugegraph.ct.node.BaseNodeWrapper;

public interface BaseEnv {

    /* init the cluster environment with simple mode */
    void startCluster();

    /* clear the cluster env and all config*/
    void stopCluster();

    ClusterConfig getConf();

    void init();

    List<String> getPDRestAddrs();

    List<String> getPDGrpcAddrs();

    List<String> getStoreRestAddrs();

    List<String> getStoreGrpcAddrs();

    List<String> getServerRestAddrs();

    List<String> getPDNodeDir();

    List<String> getStoreNodeDir();

    List<String> getServerNodeDir();

    int addPDNode();

    int addStoreNode();

    int addServerNode();

    void removePDNode(int index);

    void removeStoreNode(int index);

    void removeServerNode(int index);

    int getAlivePDNodeCount();

    int getAliveStoreNodeCount();

    int getAliveServerNodeCount();

    List<? extends BaseNodeWrapper> getPDNodeWrappers();

    List<? extends BaseNodeWrapper> getStoreNodeWrappers();

    List<? extends BaseNodeWrapper> getServerNodeWrappers();
}
