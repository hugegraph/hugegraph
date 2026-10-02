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

/**
 * Deployment modes for script policy integration. Parsing a mode does not
 * install or remove a JVM security manager.
 */
public enum ScriptSecurityMode {

    LEGACY("legacy", false, false),
    COMBINED("combined", true, false),
    POLICY_ONLY("policy-only", true, true);

    private final String configValue;
    private final boolean policyEnabled;
    private final boolean experimental;

    ScriptSecurityMode(String configValue, boolean policyEnabled,
                       boolean experimental) {
        this.configValue = configValue;
        this.policyEnabled = policyEnabled;
        this.experimental = experimental;
    }

    public boolean policyEnabled() {
        return this.policyEnabled;
    }

    public boolean experimental() {
        return this.experimental;
    }

    public String configValue() {
        return this.configValue;
    }

    public static ScriptSecurityMode parse(String value) {
        for (ScriptSecurityMode mode : values()) {
            if (mode.configValue.equals(value)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unknown script security mode");
    }
}
