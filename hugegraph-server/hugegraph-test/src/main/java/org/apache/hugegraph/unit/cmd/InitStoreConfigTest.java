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

import org.apache.commons.configuration2.PropertiesConfiguration;
import org.apache.hugegraph.cmd.InitStore;
import org.apache.hugegraph.config.HugeConfig;
import org.apache.hugegraph.config.ServerOptions;
import org.apache.hugegraph.dist.RegisterUtil;
import org.apache.hugegraph.testutil.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * InitStore skips local init only when {@code graph.load_from_local_config}
 * is explicitly false (Helm / HStore). Unset keeps master standalone init.
 */
public class InitStoreConfigTest {

    @BeforeClass
    public static void init() {
        RegisterUtil.registerServer();
    }

    @Test
    public void testUnsetDoesNotSkipInit() {
        PropertiesConfiguration conf = new PropertiesConfiguration();
        HugeConfig config = new HugeConfig(conf);
        // ServerOptions default is false, but InitStore must not treat missing
        // key as skip — same as master bare init-store for standalone users.
        Assert.assertFalse(config.get(ServerOptions.GRAPH_LOAD_FROM_LOCAL_CONFIG));
        Assert.assertFalse(InitStore.shouldSkipLocalInit(config));
    }

    @Test
    public void testExplicitFalseSkipsInit() {
        PropertiesConfiguration conf = new PropertiesConfiguration();
        conf.setProperty(ServerOptions.GRAPH_LOAD_FROM_LOCAL_CONFIG.name(), "false");
        HugeConfig config = new HugeConfig(conf);
        Assert.assertTrue(InitStore.shouldSkipLocalInit(config));
    }

    @Test
    public void testExplicitTrueDoesNotSkipInit() {
        PropertiesConfiguration conf = new PropertiesConfiguration();
        conf.setProperty(ServerOptions.GRAPH_LOAD_FROM_LOCAL_CONFIG.name(), "true");
        HugeConfig config = new HugeConfig(conf);
        Assert.assertFalse(InitStore.shouldSkipLocalInit(config));
    }
}
