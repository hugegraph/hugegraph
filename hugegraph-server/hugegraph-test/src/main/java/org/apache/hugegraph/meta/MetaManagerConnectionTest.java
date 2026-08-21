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

package org.apache.hugegraph.meta;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

import org.apache.hugegraph.meta.managers.GraphMetaManager;
import org.apache.hugegraph.testutil.Assert;
import org.junit.Test;
import org.mockito.Mockito;

public class MetaManagerConnectionTest {

    @Test
    public void testReconnectKeepsOriginalClusterAndManagers() throws Exception {
        MetaManager manager = MetaManager.instance();
        Map<Field, Object> original = mutableState(manager);
        MetaDriver driver = Mockito.mock(MetaDriver.class);
        GraphMetaManager graphManager = new GraphMetaManager(driver,
                                                             "configured");
        try {
            setField(manager, "metaDriver", driver);
            setField(manager, "cluster", "configured");
            setField(manager, "graphMetaManager", graphManager);

            manager.connect("legacy-default", MetaManager.MetaDriverType.PD,
                            null, null, null, "unused:8686");

            Assert.assertEquals("configured", manager.cluster());
            Assert.assertSame(graphManager,
                              field(manager, "graphMetaManager").get(manager));
        } finally {
            restoreState(manager, original);
        }
    }

    private static Map<Field, Object> mutableState(MetaManager manager)
            throws IllegalAccessException {
        Map<Field, Object> state = new HashMap<>();
        for (Field field : MetaManager.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) ||
                Modifier.isFinal(field.getModifiers())) {
                continue;
            }
            field.setAccessible(true);
            state.put(field, field.get(manager));
        }
        return state;
    }

    private static void restoreState(MetaManager manager,
                                     Map<Field, Object> state)
            throws IllegalAccessException {
        for (Map.Entry<Field, Object> entry : state.entrySet()) {
            entry.getKey().set(manager, entry.getValue());
        }
    }

    private static void setField(MetaManager manager, String name, Object value)
            throws ReflectiveOperationException {
        field(manager, name).set(manager, value);
    }

    private static Field field(MetaManager manager, String name)
            throws NoSuchFieldException {
        Field field = manager.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
