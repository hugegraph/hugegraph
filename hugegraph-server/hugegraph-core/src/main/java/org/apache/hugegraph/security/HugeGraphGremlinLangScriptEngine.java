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

package org.apache.hugegraph.security;

import java.io.Reader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.script.AbstractScriptEngine;
import javax.script.Bindings;
import javax.script.ScriptContext;
import javax.script.ScriptException;
import javax.script.SimpleBindings;
import javax.script.SimpleScriptContext;

import org.apache.tinkerpop.gremlin.jsr223.Customizer;
import org.apache.tinkerpop.gremlin.jsr223.GremlinLangScriptEngine;
import org.apache.tinkerpop.gremlin.jsr223.GremlinScriptEngine;
import org.apache.tinkerpop.gremlin.process.traversal.Bytecode;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;

public class HugeGraphGremlinLangScriptEngine extends AbstractScriptEngine
       implements GremlinScriptEngine {

    private static final String TRAVERSAL_SOURCE = "g";
    private static final String TINKERPOP_INITIALIZATION_PROBE = "1+1";

    private final HugeGraphGremlinLangScriptEngineFactory factory;
    private final Customizer[] customizers;
    private final ConcurrentMap<GraphTraversalSource, Delegate>
            delegates;
    private final AtomicBoolean initializationProbeAllowed;

    HugeGraphGremlinLangScriptEngine(
            HugeGraphGremlinLangScriptEngineFactory factory,
            Customizer... customizers) {
        this.factory = factory;
        this.customizers = customizers.clone();
        this.delegates = new ConcurrentHashMap<>();
        this.initializationProbeAllowed = new AtomicBoolean(true);
    }

    @Override
    public Object eval(String script, ScriptContext context)
            throws ScriptException {
        /*
         * Gremlin Server evaluates this fixed expression once for every
         * configured engine whose registration name is not "gremlin-lang".
         * HugeGraph uses a private registration name to avoid colliding with
         * TinkerPop's factory, and the probe runs before traversal sources are
         * injected. The fixed expression needs no graph access.
         */
        if (TINKERPOP_INITIALIZATION_PROBE.equals(script) &&
            context.getAttribute(TRAVERSAL_SOURCE) == null &&
            this.initializationProbeAllowed.compareAndSet(true, false)) {
            return 2;
        }
        Delegate delegate = this.delegate(traversalSource(context));
        try {
            return verify(delegate.engine.eval(
                    script,
                    guardedContext(context, delegate.traversalSource)));
        } catch (ScriptException e) {
            rethrowSecurityException(e);
            throw e;
        }
    }

    @Override
    public Object eval(Reader reader, ScriptContext context)
            throws ScriptException {
        Delegate delegate = this.delegate(traversalSource(context));
        try {
            return verify(delegate.engine.eval(
                    reader,
                    guardedContext(context, delegate.traversalSource)));
        } catch (ScriptException e) {
            rethrowSecurityException(e);
            throw e;
        }
    }

    @Override
    public Traversal.Admin<?, ?> eval(Bytecode bytecode, Bindings bindings,
                                      String traversalSource)
            throws ScriptException {
        Object source = bindings.get(traversalSource);
        if (!(source instanceof GraphTraversalSource)) {
            throw new IllegalArgumentException(String.format(
                    "The binding '%s' must be a GraphTraversalSource",
                    traversalSource));
        }
        Delegate delegate = this.delegate((GraphTraversalSource) source);
        Bindings guardedBindings = new SimpleBindings(bindings);
        guardedBindings.put(traversalSource, delegate.traversalSource);
        Traversal.Admin<?, ?> traversal = delegate.engine.eval(
                bytecode, guardedBindings, traversalSource);
        GremlinLangTraversalVerifier.verify(traversal);
        return traversal;
    }

    @Override
    public Bindings createBindings() {
        return new SimpleBindings();
    }

    @Override
    public HugeGraphGremlinLangScriptEngineFactory getFactory() {
        return this.factory;
    }

    public void add(GraphTraversalSource traversalSource) {
        if (traversalSource == null) {
            throw new IllegalArgumentException(
                    "The traversal source can't be null");
        }
        this.delegates.computeIfAbsent(traversalSource, this::newDelegate);
    }

    public void remove(GraphTraversalSource traversalSource) {
        if (traversalSource != null) {
            this.delegates.remove(traversalSource);
        }
    }

    public void clear() {
        this.delegates.clear();
    }

    public int traversalSourceCount() {
        return this.delegates.size();
    }

    private Delegate delegate(GraphTraversalSource traversalSource) {
        Delegate delegate = this.delegates.get(traversalSource);
        if (delegate == null) {
            throw new IllegalArgumentException(
                    "The 'g' binding must be a registered " +
                    "GraphTraversalSource");
        }
        return delegate;
    }

    private Delegate newDelegate(GraphTraversalSource traversalSource) {
        return new Delegate(
                new GremlinLangScriptEngine(this.customizers),
                traversalSource.withStrategies(
                        GremlinLangRestrictionStrategy.instance()));
    }

    private static GraphTraversalSource traversalSource(
            ScriptContext context) {
        Object source = context.getAttribute(TRAVERSAL_SOURCE);
        if (!(source instanceof GraphTraversalSource)) {
            throw new IllegalArgumentException(
                    "The 'g' binding must be a GraphTraversalSource");
        }
        return (GraphTraversalSource) source;
    }

    private static Object verify(Object result) {
        if (result instanceof Traversal) {
            GremlinLangTraversalVerifier.verify((Traversal<?, ?>) result);
        }
        return result;
    }

    private static void rethrowSecurityException(ScriptException exception) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof SecurityException) {
                throw (SecurityException) cause;
            }
            cause = cause.getCause();
        }
    }

    private static ScriptContext guardedContext(
            ScriptContext context,
            GraphTraversalSource traversalSource) {
        SimpleScriptContext guarded = new SimpleScriptContext();
        guarded.setReader(context.getReader());
        guarded.setWriter(context.getWriter());
        guarded.setErrorWriter(context.getErrorWriter());

        Bindings engineBindings = new SimpleBindings();
        Bindings original = context.getBindings(ScriptContext.ENGINE_SCOPE);
        if (original != null) {
            engineBindings.putAll(original);
        }
        engineBindings.put(TRAVERSAL_SOURCE, traversalSource);
        guarded.setBindings(engineBindings, ScriptContext.ENGINE_SCOPE);

        Bindings global = context.getBindings(ScriptContext.GLOBAL_SCOPE);
        if (global != null) {
            guarded.setBindings(global, ScriptContext.GLOBAL_SCOPE);
        }
        return guarded;
    }

    private static final class Delegate {

        private final GremlinLangScriptEngine engine;
        private final GraphTraversalSource traversalSource;

        private Delegate(GremlinLangScriptEngine engine,
                         GraphTraversalSource traversalSource) {
            this.engine = engine;
            this.traversalSource = traversalSource;
        }
    }
}
