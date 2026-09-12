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

package org.apache.hugegraph.auth;

import java.util.List;
import java.util.HashMap;
import java.util.Map;

import javax.script.Bindings;
import javax.script.ScriptContext;
import javax.script.ScriptException;
import javax.script.SimpleBindings;

import org.apache.hugegraph.security.script.PolicyScriptEngine;
import org.apache.hugegraph.security.script.ScriptExecutionProfile;
import org.apache.tinkerpop.gremlin.jsr223.CachedGremlinScriptEngineManager;
import org.apache.tinkerpop.gremlin.jsr223.GremlinScriptEngine;
import org.apache.tinkerpop.gremlin.server.util.LifeCycleHook;

/** Installed only after deployment-owned bootstrap scripts have finished. */
public final class PolicyGremlinScriptEngineManager extends CachedGremlinScriptEngineManager
        implements AutoCloseable {

    private final PolicyScriptEngine engine;

    public PolicyGremlinScriptEngineManager(Bindings globals) throws ScriptException {
        this(globals, null);
    }

    public PolicyGremlinScriptEngineManager(Bindings globals, Bindings sessionBindings)
            throws ScriptException {
        this.engine = new PolicyScriptEngine(ScriptExecutionProfile.QUERY, sessionBindings != null);
        boolean initialized = false;
        try {
            this.setBindings(globals);
            // Trusted lifecycle hooks have already been collected by ServerGremlinExecutor.
            globals.entrySet().stream().filter(entry -> entry.getValue() instanceof LifeCycleHook)
                   .map(Map.Entry::getKey).toList().forEach(globals::remove);
            // Initialize the binding validator's data paths before a restricted worker evaluates scripts.
            String probe = sessionBindings == null ? "probe + 1" : "warmup = probe + 1; warmup";
            Object result = this.engine.eval(probe, new SimpleBindings(new HashMap<>(Map.of(
                    "probe", 1, "items", List.of(1), "options", Map.of("limit", 1)))));
            if (!Integer.valueOf(2).equals(result)) {
                throw new IllegalStateException("Script policy startup check failed");
            }
            try {
                this.engine.eval("System.getProperty('java.version')", new SimpleBindings());
                throw new IllegalStateException("Script policy rejection check failed");
            } catch (ScriptException expected) {
                // The harmless probe must fail at compilation.
            }
            this.engine.setBindings(globals, ScriptContext.GLOBAL_SCOPE);
            if (sessionBindings != null) {
                this.engine.setBindings(sessionBindings, ScriptContext.ENGINE_SCOPE);
            }
            initialized = true;
        } finally {
            if (!initialized) {
                this.engine.close();
            }
        }
    }

    @Override
    public GremlinScriptEngine getEngineByName(String name) {
        if (!"gremlin-groovy".equals(name)) {
            throw new IllegalArgumentException("SCRIPT_LANGUAGE_DENIED");
        }
        return this.engine;
    }

    @Override
    public GremlinScriptEngine getEngineByExtension(String extension) {
        if (!"groovy".equals(extension)) {
            throw new IllegalArgumentException("SCRIPT_LANGUAGE_DENIED");
        }
        return this.engine;
    }

    @Override
    public GremlinScriptEngine getEngineByMimeType(String mime) {
        throw new IllegalArgumentException("SCRIPT_LANGUAGE_DENIED");
    }

    @Override
    public void close() {
        this.engine.close();
    }
}
