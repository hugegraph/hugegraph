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

/** Deployment-owned configuration, fixed for the lifetime of this JVM. */
public final class ScriptPolicyRuntime {

    public static final String MODE_PROPERTY = "hugegraph.script.security.mode";
    private static final ScriptSecurityMode MODE = ScriptSecurityMode.parse(
            System.getProperty(MODE_PROPERTY, "legacy"));

    private ScriptPolicyRuntime() {
    }

    public static ScriptSecurityMode mode() {
        return MODE;
    }

    public static boolean enabled() {
        return MODE.policyEnabled();
    }

    public static void validateSecurityManager() {
        if (MODE == ScriptSecurityMode.POLICY_ONLY &&
            System.getSecurityManager() != null) {
            throw new IllegalStateException(
                    "policy-only requires a JVM without a SecurityManager");
        }
    }
}
