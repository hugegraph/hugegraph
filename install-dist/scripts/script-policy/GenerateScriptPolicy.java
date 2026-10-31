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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Offline candidate generation only; public runtime policy constructors continue to verify locks. */
public final class GenerateScriptPolicy {

    public static void main(String[] args) throws Exception {
        Path destination = Path.of(args[0]);
        Path baseline = Path.of(args[1]);
        StringBuilder header = new StringBuilder();
        for (String line : Files.readAllLines(baseline.resolve("common-methods.txt"))) {
            if (!line.isEmpty() && !line.startsWith("#")) {
                break;
            }
            header.append(line).append('\n');
        }
        Set<String> query = new TreeSet<>(
                new ScriptMethodPolicy(ScriptExecutionProfile.QUERY, false, false).signatures());
        Set<String> store = new TreeSet<>(
                new ScriptMethodPolicy(ScriptExecutionProfile.STORE_FILTER, false, false).signatures());
        Set<String> common = new TreeSet<>(query);
        common.retainAll(store);
        Set<String> session = new TreeSet<>(
                new ScriptMethodPolicy(ScriptExecutionProfile.QUERY, true, false).signatures());
        session.removeAll(query);
        query.removeAll(common);
        store.removeAll(common);
        Files.createDirectories(destination);
        for (Map.Entry<String, Set<String>> entry : Map.of("common", common, "query", query,
                "store_filter", store, "session", session).entrySet()) {
            Files.writeString(destination.resolve(entry.getKey() + "-methods.txt"),
                    header + String.join("\n", entry.getValue()) + "\n", StandardCharsets.UTF_8);
        }
    }
}
