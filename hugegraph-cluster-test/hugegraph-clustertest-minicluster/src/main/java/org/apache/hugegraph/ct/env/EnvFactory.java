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

import org.apache.hugegraph.ct.base.EnvType;
import org.apache.hugegraph.ct.base.HGTestLogger;
import org.apache.hugegraph.ct.config.ClusterTestConfig;
import org.slf4j.Logger;

public class EnvFactory {

    private static final Logger LOG = HGTestLogger.ENV_LOG;
    private static volatile BaseEnv env;

    public static synchronized BaseEnv getEnv() {
        if (env == null) {
            EnvType envType = EnvType.getSystemEnvType();
            switch (envType) {
                case SingleNode:
                    env = new SimpleEnv();
                    break;
                case MultiNode:
                    env = new MultiNodeEnv();
                    break;
                default:
                    throw new IllegalArgumentException("Unknown env type: " + envType);
            }
        }
        return env;
    }

    public static BaseEnv getEnv(int pdCnt, int storeCnt, int serverCnt) {
        if (pdCnt == 1 && storeCnt == 1 && serverCnt == 1) {
            return new SimpleEnv();
        }
        return new DynamicEnv(pdCnt, storeCnt, serverCnt);
    }

    public static BaseEnv getEnv(ClusterTestConfig config) {
        switch (config.getMode()) {
            case "simple":
                return new SimpleEnv();
            case "hybrid":
                return new HybridEnv(config.getPd(), config.getRealStore(),
                                     config.getMiniStore(), config.getServer());
            case "multi":
                return EnvFactory.getEnv(config.getPd(),
                                         config.getEffectiveStoreCount(),
                                         config.getServer());
            default:
                return EnvFactory.getEnv(config.getPd(),
                                         config.getEffectiveStoreCount(),
                                         config.getServer());
        }
    }

}
