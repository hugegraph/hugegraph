/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.hugegraph.traversal.optimize;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import javax.script.Bindings;
import javax.script.ScriptException;

import org.apache.hugegraph.HugeException;
import org.apache.hugegraph.security.HugeGraphGremlinLangScriptEngine;
import org.apache.hugegraph.security.HugeGraphGremlinLangScriptEngineFactory;
import org.apache.tinkerpop.gremlin.jsr223.Customizer;
import org.apache.tinkerpop.gremlin.jsr223.GremlinLangPlugin;
import org.apache.tinkerpop.gremlin.jsr223.VariableResolverPlugin;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.util.DefaultTraversal;

/**
 * HugeScriptTraversal parses a GremlinLang query into a {@link Traversal} at
 * {@link Admin#applyStrategies()} through HugeGraph's protected engine.
 * <p>
 * This is useful for serializing traversals as the compilation can happen on
 * the remote end where the traversal will ultimately be processed.
 *
 * @author Marko A. Rodriguez (http://markorodriguez.com)
 */
public final class HugeScriptTraversal<S, E> extends DefaultTraversal<S, E> {

    private static final long serialVersionUID = 4617322697747299673L;

    private final GraphTraversalSource traversalSource;
    private final String script;
    private final Map<String, Object> bindings;
    private final Map<String, String> aliases;

    private Object result;

    public HugeScriptTraversal(TraversalSource traversalSource, String script,
                               Map<String, Object> bindings,
                               Map<String, String> aliases) {
        org.apache.hugegraph.util.E.checkNotNull(traversalSource,
                                                 "traversal source");
        org.apache.hugegraph.util.E.checkNotNull(script, "gremlin script");
        org.apache.hugegraph.util.E.checkNotNull(bindings,
                                                 "gremlin bindings");
        org.apache.hugegraph.util.E.checkNotNull(aliases, "gremlin aliases");
        org.apache.hugegraph.util.E.checkArgument(
                traversalSource instanceof GraphTraversalSource,
                "The traversal source must be a GraphTraversalSource");
        this.graph = traversalSource.getGraph();
        this.traversalSource = (GraphTraversalSource) traversalSource;
        this.strategies = traversalSource.getStrategies().clone();
        this.script = script;
        this.bindings = bindings;
        this.aliases = aliases;
        this.result = null;
    }

    /**
     * Kept for callers compiled against the former language-selecting API.
     * The language can no longer select an arbitrary JSR-223 engine.
     */
    @Deprecated
    public HugeScriptTraversal(TraversalSource traversalSource, String language,
                               String script, Map<String, Object> bindings,
                               Map<String, String> aliases) {
        this(traversalSource, script, bindings, aliases);
        org.apache.hugegraph.util.E.checkArgument(
                HugeGraphGremlinLangScriptEngineFactory.ENGINE_NAME.equals(
                        language),
                "The language must be 'gremlin-lang', but got '%s'", language);
    }

    public Object result() {
        return this.result;
    }

    public String script() {
        return this.script;
    }

    @Override
    public void applyStrategies() throws IllegalStateException {
        HugeGraphGremlinLangScriptEngineFactory factory =
                new HugeGraphGremlinLangScriptEngineFactory(customizers());
        HugeGraphGremlinLangScriptEngine engine = factory.getScriptEngine();

        try {
            Bindings bindings = engine.createBindings();
            bindings.putAll(this.bindings);

            GraphTraversalSource protectedSource =
                    engine.add(this.traversalSource.clone());
            bindings.put("g", protectedSource);
            bindings.put("graph", protectedSource);

            for (Map.Entry<String, String> entry : this.aliases.entrySet()) {
                Object value = bindings.get(entry.getValue());
                if (value == null) {
                    throw new IllegalArgumentException(String.format(
                              "Invalid alias '%s':'%s'", entry.getKey(),
                              entry.getValue()));
                }
                bindings.put(entry.getKey(), value);
            }

            Object result = engine.eval(this.script, bindings);

            if (result instanceof Admin) {
                @SuppressWarnings({"unchecked"})
                Admin<S, E> traversal = (Admin<S, E>) result;
                traversal.getSideEffects().mergeInto(this.sideEffects);
                traversal.getSteps().forEach(this::addStep);
                this.strategies = traversal.getStrategies();
            } else {
                this.result = result;
            }
            super.applyStrategies();
        } catch (ScriptException e) {
            throw new HugeException(e.getMessage(), e);
        } finally {
            engine.clear();
        }
    }

    private static Customizer[] customizers() {
        List<Customizer> customizers = new ArrayList<>();
        GremlinLangPlugin gremlinLang = GremlinLangPlugin.build()
                                                          .cacheEnabled(false)
                                                          .create();
        customizers.addAll(Arrays.asList(
                gremlinLang.getCustomizers(
                        HugeGraphGremlinLangScriptEngineFactory.ENGINE_NAME)
                           .get()));
        VariableResolverPlugin variables =
                VariableResolverPlugin.build()
                                      .resolver("DefaultVariableResolver")
                                      .create();
        customizers.addAll(Arrays.asList(
                variables.getCustomizers(
                        HugeGraphGremlinLangScriptEngineFactory.ENGINE_NAME)
                         .get()));
        return customizers.toArray(new Customizer[0]);
    }
}
