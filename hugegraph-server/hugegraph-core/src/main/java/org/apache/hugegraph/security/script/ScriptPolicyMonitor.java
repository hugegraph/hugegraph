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

import java.lang.management.ManagementFactory;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.management.ObjectName;

/** One local JMX bean per profile, sharing the existing JVM management access controls. */
public final class ScriptPolicyMonitor implements ScriptPolicyMonitorMXBean {

    public static final String VERSION = "groovy-policy-v1";
    private static final Map<ScriptExecutionProfile, ScriptPolicyMonitor> MONITORS =
            new EnumMap<>(ScriptExecutionProfile.class);
    private final Set<PolicyScriptEngine> engines = ConcurrentHashMap.newKeySet();

    private ScriptPolicyMonitor() {
    }

    static synchronized void register(ScriptExecutionProfile profile, PolicyScriptEngine engine) {
        ScriptPolicyMonitor monitor = MONITORS.get(profile);
        if (monitor == null) {
            monitor = new ScriptPolicyMonitor();
            try {
                ManagementFactory.getPlatformMBeanServer().registerMBean(monitor,
                        new ObjectName("org.apache.hugegraph:type=ScriptPolicy,profile=" + profile.name()));
            } catch (Exception error) {
                throw new IllegalStateException("Failed to register script policy monitoring", error);
            }
            MONITORS.put(profile, monitor);
        }
        monitor.engines.add(engine);
    }

    static synchronized void unregister(ScriptExecutionProfile profile, PolicyScriptEngine engine) {
        ScriptPolicyMonitor monitor = MONITORS.get(profile);
        if (monitor != null) {
            monitor.engines.remove(engine);
        }
    }

    @Override
    public String getMode() {
        return ScriptPolicyRuntime.mode().configValue();
    }

    @Override
    public String getPolicyVersion() {
        return VERSION;
    }

    @Override
    public int getActiveEngines() {
        return this.engines.size();
    }

    @Override
    public Map<String, Long> getCounters() {
        Map<String, Long> counters = new LinkedHashMap<>();
        for (PolicyScriptEngine engine : this.engines) {
            engine.metrics().forEach((name, value) -> counters.merge(name, value, Long::sum));
        }
        return counters;
    }
}
