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

package org.apache.hugegraph.unit.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.script.ScriptException;
import javax.script.SimpleBindings;

import org.apache.hugegraph.security.script.PolicyScriptEngine;
import org.apache.hugegraph.security.script.ScriptBindings;
import org.apache.hugegraph.security.script.ScriptElementView;
import org.apache.hugegraph.security.script.ScriptExecutionProfile;
import org.junit.Assert;
import org.junit.Test;

public class StorePropertySnapshotTest {

    @Test
    public void testStorePropertiesSupportJsonAndStringProcessing() throws Exception {
        ScriptElementView element = new ScriptElementView("v1", "person",
                Map.of("age", 20, "tags", List.of("one", "two")));
        try (PolicyScriptEngine engine = new PolicyScriptEngine(ScriptExecutionProfile.STORE_FILTER)) {
            SimpleBindings bindings = new SimpleBindings(Map.of("element", element));
            Assert.assertEquals(true, engine.eval(
                    "groovy.json.JsonOutput.toJson(element.properties()).contains('\"age\":20')", bindings));
            Assert.assertEquals(true, engine.eval(
                    "('properties=' + element.properties()).contains('age')", bindings));
            Assert.assertEquals(true, engine.eval(
                    "groovy.json.JsonOutput.toJson([record: element.properties()]).contains('two')", bindings));
            Assert.assertThrows(ScriptException.class,
                                () -> engine.eval("element.properties().put('age', 1); true", bindings));
            Assert.assertEquals(20, element.property("age"));
        }
    }

    @Test
    public void testReadOnlyMapsRemainRejectedAsClientAndSessionData() throws Exception {
        AtomicBoolean called = new AtomicBoolean();
        Map<String, Object> custom = new LinkedHashMap<>() {
            @Override
            public Set<Map.Entry<String, Object>> entrySet() {
                called.set(true);
                return super.entrySet();
            }
        };
        Map<String, Object> wrapped = Collections.unmodifiableMap(custom);
        Assert.assertThrows(IllegalArgumentException.class,
                            () -> ScriptBindings.client(Map.of("value", wrapped)));
        Assert.assertFalse(called.get());
        try (PolicyScriptEngine engine = new PolicyScriptEngine(ScriptExecutionProfile.QUERY, true)) {
            Assert.assertThrows(ScriptException.class, () -> engine.eval(
                    "value", new SimpleBindings(Map.of("value", wrapped))));
        }
        Assert.assertFalse(called.get());
    }
}
