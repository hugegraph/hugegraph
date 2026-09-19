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

package org.apache.hugegraph.security.script;

import java.util.EnumMap;
import java.util.Map;

import javax.script.ScriptException;
import javax.script.SimpleBindings;

public final class PolicyScriptEngines {

    private static final Map<ScriptExecutionProfile, PolicyScriptEngine> ENGINES =
            new EnumMap<>(ScriptExecutionProfile.class);

    private PolicyScriptEngines() {
    }

    public static synchronized PolicyScriptEngine get(ScriptExecutionProfile profile) {
        return ENGINES.computeIfAbsent(profile, PolicyScriptEngine::new);
    }

    public static synchronized void initializeServer() {
        if (!ScriptPolicyRuntime.enabled()) {
            return;
        }
        // Load manifests and validation classes before restricted job workers run.
        Map<ScriptExecutionProfile, PolicyScriptEngine> initialized =
                new EnumMap<>(ScriptExecutionProfile.class);
        try {
            for (ScriptExecutionProfile profile : new ScriptExecutionProfile[]{
                    ScriptExecutionProfile.QUERY, ScriptExecutionProfile.SCHEMA}) {
                if (ENGINES.containsKey(profile)) {
                    continue;
                }
                PolicyScriptEngine engine = new PolicyScriptEngine(profile);
                initialized.put(profile, engine);
                engine.eval("probe + 1", new SimpleBindings(Map.of("probe", 1)));
            }
            ENGINES.putAll(initialized);
        } catch (ScriptException | RuntimeException | Error failure) {
            initialized.values().forEach(PolicyScriptEngine::close);
            throw new IllegalStateException("Script policy initialization failed", failure);
        }
    }

    public static synchronized void close() {
        ENGINES.values().forEach(PolicyScriptEngine::close);
        ENGINES.clear();
    }
}
