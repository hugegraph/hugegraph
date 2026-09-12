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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;

import org.codehaus.groovy.control.CompilerConfiguration;

/**
 * Creates the early compilation gate for a fixed, deployment-owned classpath.
 * Method policy and static compilation must still be added before this
 * configuration can be used to execute untrusted scripts.
 */
public final class ScriptCompilerConfiguration {

    private static final String GLOBAL_TRANSFORMS =
            "META-INF/services/org.codehaus.groovy.transform.ASTTransformation";

    private ScriptCompilerConfiguration() {
    }

    public static CompilerConfiguration create(ClassLoader loader)
            throws IOException {
        Set<String> disabled = new HashSet<>();
        Enumeration<URL> resources = loader.getResources(GLOBAL_TRANSFORMS);
        while (resources.hasMoreElements()) {
            URL resource = resources.nextElement();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resource.openStream(),
                                          StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    int comment = line.indexOf('#');
                    String name = (comment < 0 ? line : line.substring(0, comment)).trim();
                    if (!name.isEmpty()) {
                        disabled.add(name);
                    }
                }
            }
        }
        CompilerConfiguration configuration = new CompilerConfiguration();
        configuration.setDisabledGlobalASTTransformations(disabled);
        configuration.addCompilationCustomizers(new ScriptSyntaxGuard());
        return configuration;
    }
}
