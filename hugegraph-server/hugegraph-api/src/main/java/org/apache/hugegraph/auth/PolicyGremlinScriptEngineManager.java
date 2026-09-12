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

import javax.script.Bindings;
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

    private final PolicyScriptEngine engine = new PolicyScriptEngine(ScriptExecutionProfile.QUERY);

    public PolicyGremlinScriptEngineManager(Bindings globals) throws ScriptException {
        this.setBindings(globals);
        // Trusted lifecycle hooks have already been collected by ServerGremlinExecutor.
        globals.entrySet().removeIf(entry -> entry.getValue() instanceof
                LifeCycleHook);
        Object result = this.engine.eval("1 + 1", new SimpleBindings());
        if (!Integer.valueOf(2).equals(result)) {
            throw new IllegalStateException("Script policy startup check failed");
        }
        try {
            this.engine.eval("System.getProperty('java.version')", new SimpleBindings());
            throw new IllegalStateException("Script policy rejection check failed");
        } catch (ScriptException expected) {
            // The harmless probe must fail at compilation.
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
