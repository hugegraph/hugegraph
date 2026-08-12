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

package org.apache.hugegraph.ClusterTest;

import java.io.IOException;
import java.util.List;

import org.apache.hugegraph.ct.client.ClusterRestClient;
import org.apache.hugegraph.pd.client.PDClient;
import org.apache.hugegraph.pd.client.PDConfig;
import org.apache.hugegraph.pd.common.PDException;
import org.junit.Assert;
import org.junit.Test;

import jakarta.ws.rs.core.Response;

public class ClusterDeployTest extends BaseClusterTest {

    @Test
    public void testPDNodesDeployment() {
        List<String> addrs = env.getPDGrpcAddrs();
        for (String addr : addrs) {
            try {
                PDConfig pdConfig = PDConfig.of(addr);
                PDClient pdClient = PDClient.create(pdConfig);
                pdClient.dbCompaction();
            } catch (PDException e) {
                Assert.fail("PD compaction failed for " + addr + ": " + e.getMessage());
            }
        }
    }

    @Test
    public void testStoreNodesDeployment() throws IOException {
        List<String> addrs = env.getStoreRestAddrs();
        for (String addr : addrs) {
            String[] cmds = {"curl", addr};
            String responseMsg = execCmd(cmds);
            Assert.assertTrue(responseMsg.startsWith("{"));
        }
    }

    @Test
    public void testServerNodesDeployment() {
        for (ClusterRestClient c : clients) {
            Response r = c.createPropertyKey(
                    "{\"name\":\"deploy_name\",\"data_type\":\"TEXT\"," +
                    "\"cardinality\":\"SINGLE\",\"check_exist\":false}");
            Assert.assertTrue("Expected 2xx, got " + r.getStatus(),
                              r.getStatus() >= 200 && r.getStatus() < 300);
        }
    }
}
