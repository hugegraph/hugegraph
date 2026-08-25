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

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;
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
    private static final SourceRegistry SOURCES = new SourceRegistry();

    private final HugeGraphGremlinLangScriptEngineFactory factory;
    private final Customizer[] customizers;
    private final GremlinLangTextPredicateAdapter textPredicateAdapter;
    private final ConcurrentMap<GraphTraversalSource, Delegate>
            delegates;
    private final ConcurrentMap<GraphTraversalSource, Delegate>
            protectedDelegates;
    private final AtomicBoolean explicitRegistration;
    private final AtomicBoolean initializationProbeAllowed;

    HugeGraphGremlinLangScriptEngine(
            HugeGraphGremlinLangScriptEngineFactory factory,
            Customizer... customizers) {
        this.factory = factory;
        this.customizers = customizers.clone();
        this.textPredicateAdapter =
                new GremlinLangTextPredicateAdapter(this.customizers);
        this.delegates = new ConcurrentHashMap<>();
        this.protectedDelegates = new ConcurrentHashMap<>();
        this.explicitRegistration = new AtomicBoolean(false);
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
        GremlinLangTextPredicateAdapter.AdaptedScript adapted =
                this.textPredicateAdapter.adapt(script, context);
        try {
            return verify(delegate.engine.eval(
                    adapted.script(),
                    guardedContext(context, delegate.traversalSource,
                                   adapted.bindings())));
        } catch (ScriptException e) {
            rethrowSecurityException(e);
            throw e;
        }
    }

    @Override
    public Object eval(Reader reader, ScriptContext context)
            throws ScriptException {
        try {
            return this.eval(readFully(reader), context);
        } catch (IOException e) {
            throw new ScriptException(e);
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

    public synchronized GraphTraversalSource add(
            GraphTraversalSource traversalSource) {
        if (traversalSource == null) {
            throw new IllegalArgumentException(
                    "The traversal source can't be null");
        }
        this.explicitRegistration.set(true);
        Delegate delegate = this.delegates.computeIfAbsent(
                traversalSource, this::newDelegate);
        this.protectedDelegates.putIfAbsent(delegate.traversalSource,
                                            delegate);
        SOURCES.register(delegate.traversalSource, this);
        return delegate.traversalSource;
    }

    public synchronized void remove(GraphTraversalSource traversalSource) {
        if (traversalSource == null) {
            return;
        }
        Delegate delegate = this.delegates.get(traversalSource);
        if (delegate == null) {
            delegate = this.protectedDelegates.get(traversalSource);
        }
        if (delegate != null) {
            if (this.explicitRegistration.get()) {
                SOURCES.retire(delegate.traversalSource);
            } else {
                SOURCES.detach(delegate.traversalSource, this);
                this.removeLocal(delegate.traversalSource);
            }
        }
    }

    public synchronized void clear() {
        if (this.explicitRegistration.get()) {
            for (GraphTraversalSource source :
                 new ArrayList<>(this.protectedDelegates.keySet())) {
                SOURCES.retire(source);
            }
        } else {
            for (GraphTraversalSource source :
                 new ArrayList<>(this.protectedDelegates.keySet())) {
                SOURCES.detach(source, this);
            }
        }
        this.delegates.clear();
        this.protectedDelegates.clear();
    }

    public int traversalSourceCount() {
        return this.delegates.size();
    }

    private Delegate delegate(GraphTraversalSource traversalSource) {
        Delegate delegate = this.delegates.get(traversalSource);
        if (delegate == null) {
            delegate = this.protectedDelegates.get(traversalSource);
        }
        if (delegate == null && !this.explicitRegistration.get()) {
            delegate = SOURCES.attach(traversalSource, this);
            if (delegate == null &&
                (SOURCES.isRetired(traversalSource) ||
                 isProtected(traversalSource))) {
                throw new IllegalArgumentException(
                        "The protected 'g' binding must reference an active " +
                        "GraphTraversalSource");
            }
        }
        if (delegate == null) {
            String requirement = this.explicitRegistration.get() ?
                                 "registered" : "protected";
            throw new IllegalArgumentException(
                    "The 'g' binding must be a " + requirement + " " +
                    "GraphTraversalSource");
        }
        return delegate;
    }

    private Delegate attachLocal(GraphTraversalSource traversalSource) {
        Delegate delegate = this.delegates.computeIfAbsent(
                traversalSource, this::newProtectedDelegate);
        this.protectedDelegates.putIfAbsent(delegate.traversalSource,
                                            delegate);
        return delegate;
    }

    private void removeLocal(GraphTraversalSource traversalSource) {
        Delegate delegate = this.protectedDelegates.get(traversalSource);
        if (delegate == null) {
            delegate = this.delegates.get(traversalSource);
        }
        if (delegate != null) {
            this.delegates.remove(delegate.registrationSource, delegate);
            this.protectedDelegates.remove(delegate.traversalSource,
                                           delegate);
        }
    }

    private Delegate newDelegate(GraphTraversalSource traversalSource) {
        GraphTraversalSource protectedSource = traversalSource;
        if (!isProtected(protectedSource)) {
            protectedSource = traversalSource.withStrategies(
                    GremlinLangRestrictionStrategy.instance());
        }
        return new Delegate(
                new GremlinLangScriptEngine(this.customizers),
                traversalSource, protectedSource);
    }

    private Delegate newProtectedDelegate(
            GraphTraversalSource traversalSource) {
        return new Delegate(
                new GremlinLangScriptEngine(this.customizers),
                traversalSource, traversalSource);
    }

    private static boolean isProtected(
            GraphTraversalSource traversalSource) {
        return traversalSource.getStrategies()
                              .getStrategy(
                                      GremlinLangRestrictionStrategy.class)
                              .isPresent();
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
            GraphTraversalSource traversalSource,
            Map<String, Object> additionalBindings) {
        SimpleScriptContext guarded = new SimpleScriptContext();
        guarded.setReader(context.getReader());
        guarded.setWriter(context.getWriter());
        guarded.setErrorWriter(context.getErrorWriter());

        Bindings engineBindings = new SimpleBindings();
        Bindings original = context.getBindings(ScriptContext.ENGINE_SCOPE);
        if (original != null) {
            engineBindings.putAll(original);
        }
        engineBindings.putAll(additionalBindings);
        engineBindings.put(TRAVERSAL_SOURCE, traversalSource);
        guarded.setBindings(engineBindings, ScriptContext.ENGINE_SCOPE);

        Bindings global = context.getBindings(ScriptContext.GLOBAL_SCOPE);
        if (global != null) {
            guarded.setBindings(global, ScriptContext.GLOBAL_SCOPE);
        }
        return guarded;
    }

    private static String readFully(Reader reader) throws IOException {
        StringBuilder script = new StringBuilder();
        char[] buffer = new char[8192];
        int length;
        while ((length = reader.read(buffer)) != -1) {
            script.append(buffer, 0, length);
        }
        return script.toString();
    }

    private static final class Delegate {

        private final GremlinLangScriptEngine engine;
        private final GraphTraversalSource registrationSource;
        private final GraphTraversalSource traversalSource;

        private Delegate(GremlinLangScriptEngine engine,
                         GraphTraversalSource registrationSource,
                         GraphTraversalSource traversalSource) {
            this.engine = engine;
            this.registrationSource = registrationSource;
            this.traversalSource = traversalSource;
        }
    }

    private static final class SourceRegistry {

        private final Map<GraphTraversalSource, SourceEntry> sources;
        private final Map<GraphTraversalSource, Boolean> retiredSources;

        private SourceRegistry() {
            this.sources = new WeakHashMap<>();
            this.retiredSources = new WeakHashMap<>();
        }

        private synchronized void register(GraphTraversalSource source,
                                           HugeGraphGremlinLangScriptEngine
                                                   engine) {
            SourceEntry entry = this.sources.computeIfAbsent(
                    source, key -> new SourceEntry());
            this.retiredSources.remove(source);
            entry.engines.put(engine, Boolean.TRUE);
        }

        private synchronized Delegate attach(
                GraphTraversalSource source,
                HugeGraphGremlinLangScriptEngine engine) {
            SourceEntry entry = this.sources.get(source);
            if (entry == null) {
                return null;
            }
            Delegate delegate = engine.attachLocal(source);
            entry.engines.put(engine, Boolean.TRUE);
            return delegate;
        }

        private synchronized boolean isRetired(
                GraphTraversalSource source) {
            return this.retiredSources.containsKey(source);
        }

        private synchronized void detach(
                GraphTraversalSource source,
                HugeGraphGremlinLangScriptEngine engine) {
            SourceEntry entry = this.sources.get(source);
            if (entry != null) {
                entry.engines.remove(engine);
            }
        }

        private synchronized void retire(GraphTraversalSource source) {
            SourceEntry entry = this.sources.remove(source);
            this.retiredSources.put(source, Boolean.TRUE);
            if (entry == null) {
                return;
            }
            for (HugeGraphGremlinLangScriptEngine engine :
                 new ArrayList<>(entry.engines.keySet())) {
                engine.removeLocal(source);
            }
            entry.engines.clear();
        }
    }

    private static final class SourceEntry {

        private final Map<HugeGraphGremlinLangScriptEngine, Boolean> engines;

        private SourceEntry() {
            this.engines = new WeakHashMap<>();
        }
    }
}
