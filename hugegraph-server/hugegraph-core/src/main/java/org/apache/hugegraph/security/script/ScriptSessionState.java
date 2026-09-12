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

import groovy.lang.Binding;

/** Compiler-only assignment bookkeeping; user calls remain outside the method policy. */
public final class ScriptSessionState {

    private ScriptSessionState() {
    }

    public static <T> T recordPostfix(Binding binding, String name, T result, Object current) {
        binding.setVariable(name, current);
        return result;
    }

    public static <T> T record(Binding binding, String name, T value) {
        binding.setVariable(name, value);
        return value;
    }
}
