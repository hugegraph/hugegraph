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

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.script.AbstractScriptEngine;
import javax.script.Bindings;
import javax.script.Compilable;
import javax.script.CompiledScript;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptException;
import javax.script.SimpleBindings;

import org.apache.tinkerpop.gremlin.groovy.jsr223.GremlinGroovyScriptEngineFactory;
import org.apache.tinkerpop.gremlin.jsr223.GremlinScriptChecker;
import org.apache.tinkerpop.gremlin.jsr223.GremlinScriptEngine;
import org.apache.tinkerpop.gremlin.jsr223.GremlinScriptEngineFactory;
import org.apache.tinkerpop.gremlin.jsr223.JavaTranslator;
import org.apache.tinkerpop.gremlin.process.traversal.Bytecode;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.util.TraversalInterruptedException;
import org.apache.tinkerpop.gremlin.structure.util.CloseableIterator;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.customizers.ASTTransformationCustomizer;
import org.codehaus.groovy.control.customizers.ImportCustomizer;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import groovy.lang.Binding;
import groovy.lang.GroovyClassLoader;
import groovy.lang.Script;
import groovy.transform.CompileStatic;

/** A bounded compiler cache with fresh bindings and a fresh Script per evaluation. */
public final class PolicyScriptEngine extends AbstractScriptEngine
        implements GremlinScriptEngine, Compilable, AutoCloseable {

    private static final ThreadPoolExecutor COMPILERS = new ThreadPoolExecutor(
            2, 2, 30L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32), runnable -> {
                Thread thread = new Thread(runnable, "hugegraph-policy-compile");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    static {
        COMPILERS.allowCoreThreadTimeOut(true);
    }

    private final ScriptPolicyMetrics metrics = new ScriptPolicyMetrics();
    private final Object lifecycle = new Object();
    private final ScriptExecutionProfile profile;
    private final boolean session;
    private final ScriptMethodPolicy methods;
    private final Cache<CompilationKey, CompletableFuture<CompiledUnit>> cache;
    private volatile boolean closed;
    private boolean deferredSession;
    private final ThreadLocal<PendingSession> pendingSession = new ThreadLocal<>();

    public PolicyScriptEngine(ScriptExecutionProfile profile) {
        this(profile, false);
    }

    public PolicyScriptEngine(ScriptExecutionProfile profile, boolean session) {
        if (session && profile != ScriptExecutionProfile.QUERY) {
            throw new IllegalArgumentException("Sessions require the query profile");
        }
        this.session = session;
        this.profile = profile;
        this.methods = new ScriptMethodPolicy(profile, session);
        this.cache = Caffeine.newBuilder().maximumSize(256)
                             .expireAfterAccess(30, TimeUnit.MINUTES).executor(Runnable::run).recordStats()
                             .<CompilationKey, CompletableFuture<CompiledUnit>>removalListener(
                                     (key, future, cause) -> {
                                         if (future != null) {
                                             future.thenAccept(CompiledUnit::close);
                                         }
                                     }).build();
        ScriptPolicyMonitor.register(profile, this);
    }

    @Override
    public Object eval(String source, ScriptContext context) throws ScriptException {
        return this.evaluate(source, context, null, null);
    }

    private Object evaluate(String source, ScriptContext context, CompiledUnit compiled,
                            Map<String, Class<?>> types) throws ScriptException {
        this.metrics.evaluated();
        try {
            Bindings bindings = new SimpleBindings();
            Bindings globals = context.getBindings(ScriptContext.GLOBAL_SCOPE);
            if (globals != null) {
                bindings.putAll(globals);
            }
            bindings.putAll(context.getBindings(ScriptContext.ENGINE_SCOPE));
            Bindings isolated = ScriptBindings.execution(bindings, this.profile);
            if (this.closed) {
                throw new ScriptException("SCRIPT_ENGINE_CLOSED");
            }
            if (types != null && !types.equals(ScriptBindings.types(isolated))) {
                throw new ScriptException("SCRIPT_BINDING_TYPE_CHANGED");
            }
            CompiledUnit unit = compiled == null ? this.prepare(source, isolated) : compiled;
            Script script = (Script) unit.type.getDeclaredConstructor().newInstance();
            script.setBinding(new Binding(isolated));
            ScriptExecutionBudget.check(ScriptExecutionBudget.deadline());
            Object result = script.run();
            if (this.profile == ScriptExecutionProfile.STORE_FILTER && !(result instanceof Boolean)) {
                throw new IllegalArgumentException("SCRIPT_RESULT_DENIED: condition must return Boolean");
            }
            Object prepared = ScriptResults.prepare(result);
            if (this.session) {
                PendingSession pending = new PendingSession(bindings, isolated,
                        context.getBindings(ScriptContext.ENGINE_SCOPE));
                pending.validate();
                if (this.deferredSession) {
                    this.pendingSession.set(pending);
                    if (prepared instanceof Iterator) {
                        return new SessionResultIterator((Iterator<?>) prepared, pending);
                    }
                } else {
                    pending.publish();
                }
            }
            return prepared;
        } catch (ScriptException e) {
            this.metrics.rejected();
            if (this.session && Thread.currentThread().isInterrupted()) {
                throw new ScriptException(new InterruptedException("SCRIPT_EXECUTION_TIMEOUT"));
            }
            throw e;
        } catch (ScriptExecutionBudget.ExecutionTimeoutException e) {
            this.metrics.rejected();
            this.metrics.executionTimeout();
            if (this.session) {
                throw new ScriptException(new InterruptedException("SCRIPT_EXECUTION_TIMEOUT"));
            }
            throw new ScriptException("SCRIPT_EXECUTION_TIMEOUT");
        } catch (Exception e) {
            this.metrics.rejected();
            throw new ScriptException("SCRIPT_EXECUTION_FAILED: " + e.getClass().getSimpleName());
        } catch (StackOverflowError error) {
            throw new ScriptException("SCRIPT_EXECUTION_LIMIT");
        }
    }

    /** Enabled only by the session adapter that owns the GremlinExecutor lifecycle. */
    public void deferSessionPublication() {
        if (!this.session) {
            throw new IllegalStateException("Not a session engine");
        }
        // Load the streaming adapter before entering a SecurityManager-restricted worker.
        new SessionResultIterator(Collections.emptyIterator(),
                new PendingSession(new SimpleBindings(), new SimpleBindings(), new SimpleBindings()));
        this.deferredSession = true;
    }

    /** Called after TP consumes and serializes results; validation happened before each result was delivered. */
    public void publishSession(Bindings bindings) {
        PendingSession pending = this.pendingSession.get();
        if (pending != null) {
            if (pending.target != bindings || pending.validated == null) {
                throw new IllegalStateException("SCRIPT_SESSION_LIFECYCLE_MISMATCH");
            }
            pending.publish();
            this.pendingSession.remove();
        }
    }

    /** Always called on the session worker, including failure, cancellation, and timeout. */
    public void abortSession() {
        this.pendingSession.remove();
    }

    private final class SessionResultIterator implements Iterator<Object>, AutoCloseable {

        private final Iterator<?> original;
        private final PendingSession pending;

        SessionResultIterator(Iterator<?> original, PendingSession pending) {
            this.original = original;
            this.pending = pending;
        }

        @Override
        public boolean hasNext() {
            try {
                boolean more = this.original.hasNext();
                // hasNext may execute filtering/side-effect closures, including on an empty traversal.
                this.pending.validate();
                return more;
            } catch (ScriptExecutionBudget.ExecutionTimeoutException timeout) {
                throw new TraversalInterruptedException();
            }
        }

        @Override
        public Object next() {
            try {
                Object value = this.original.next();
                this.pending.validate();
                return value;
            } catch (ScriptExecutionBudget.ExecutionTimeoutException timeout) {
                throw new TraversalInterruptedException();
            }
        }

        @Override
        public void close() {
            CloseableIterator.closeIterator(this.original);
        }
    }

    private final class PendingSession {

        private final Bindings original;
        private final Bindings candidate;
        private final Bindings target;
        private Bindings validated;

        PendingSession(Bindings original, Bindings candidate, Bindings target) {
            this.original = original;
            this.candidate = candidate;
            this.target = target;
        }

        void validate() {
            Bindings retained = ScriptBindings.execution(this.candidate, profile);
            for (Map.Entry<String, Object> entry : retained.entrySet()) {
                if ((entry.getValue() instanceof GraphTraversalSource ||
                     entry.getValue() instanceof org.apache.hugegraph.HugeGraph) &&
                    this.original.get(entry.getKey()) != entry.getValue()) {
                    throw new IllegalArgumentException("SCRIPT_SESSION_OBJECT_DENIED");
                }
            }
            this.validated = retained;
        }

        void publish() {
            this.target.clear();
            this.target.putAll(this.validated);
        }
    }

    private CompiledUnit prepare(String source, Bindings bindings) throws ScriptException {
        if (this.closed) {
            throw new ScriptException("SCRIPT_ENGINE_CLOSED");
        }
        if (source == null || source.length() > ScriptBindings.MAX_SOURCE_BYTES ||
            source.getBytes(StandardCharsets.UTF_8).length > ScriptBindings.MAX_SOURCE_BYTES) {
            throw new ScriptException("SCRIPT_SOURCE_LIMIT");
        }
        // TinkerPop scans source text (including string literals) for timeout overrides
        // before evaluating it. Reject every override it recognizes, even inert text.
        if (GremlinScriptChecker.parse(source).getTimeout().isPresent()) {
            throw new ScriptException("SCRIPT_TIMEOUT_OVERRIDE_DENIED");
        }
        CompilationKey key = new CompilationKey(source, ScriptBindings.types(bindings));
        CompletableFuture<CompiledUnit> future;
        try {
            synchronized (this.lifecycle) {
                if (this.closed) {
                    throw new ScriptException("SCRIPT_ENGINE_CLOSED");
                }
                future = this.cache.get(key, compilation -> {
                    CompletableFuture<CompiledUnit> pending = new CompletableFuture<>();
                    long queued = System.nanoTime();
                    COMPILERS.execute(() -> {
                        this.metrics.queued(System.nanoTime() - queued);
                        if (pending.isDone()) {
                            return;
                        }
                        long started = System.nanoTime();
                        try {
                            CompiledUnit unit = this.compileUnit(compilation);
                            if (!pending.complete(unit)) {
                                unit.close();
                            }
                        } catch (Throwable error) {
                            pending.completeExceptionally(error);
                        } finally {
                            this.metrics.compiled(System.nanoTime() - started);
                        }
                    });
                    return pending;
                });
            }
        } catch (RejectedExecutionException e) {
            throw new ScriptException("SCRIPT_COMPILE_OVERLOADED");
        }
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            this.metrics.timeout();
            // Keep a running compilation cached and occupying its worker slot.
            throw new ScriptException("SCRIPT_COMPILE_TIMEOUT");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ScriptException("SCRIPT_COMPILE_INTERRUPTED");
        } catch (ExecutionException e) {
            this.cache.invalidate(key);
            throw new ScriptException("SCRIPT_COMPILE_DENIED");
        }
    }

    private CompiledUnit compileUnit(CompilationKey key) throws Exception {
        StringBuilder prelude = new StringBuilder();
        prelude.append("final long __hgDeadline = ")
               .append(ScriptExecutionBudget.class.getName()).append(".deadline();\n");
        key.types.forEach((name, type) -> prelude.append(this.session ? "" : "final ")
                .append(type.getCanonicalName()).append(' ').append(name).append(" = (")
                .append(type.getCanonicalName()).append(") getBinding().getVariable('")
                .append(name).append("');\n"));
        int lines = key.types.size() + 1;
        ClassLoader parent = PolicyScriptEngine.class.getClassLoader();
        CompilerConfiguration configuration = ScriptCompilerConfiguration.create(parent);
        if (this.session) {
            configuration.addCompilationCustomizers(new ScriptSessionCustomizer(lines, key.types.keySet()));
        }
        ImportCustomizer imports = new ImportCustomizer();
        imports.addImports("org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__",
                           "org.apache.tinkerpop.gremlin.process.traversal.P",
                           "org.apache.tinkerpop.gremlin.process.traversal.TextP",
                           "org.apache.tinkerpop.gremlin.process.traversal.Order",
                           "org.apache.tinkerpop.gremlin.process.traversal.Scope",
                           "org.apache.tinkerpop.gremlin.process.traversal.Pop",
                           "org.apache.tinkerpop.gremlin.structure.Column",
                           "org.apache.tinkerpop.gremlin.process.traversal.Pick",
                           "org.apache.tinkerpop.gremlin.process.traversal.Traverser",
                           "org.apache.tinkerpop.gremlin.structure.T",
                           "org.apache.tinkerpop.gremlin.structure.Vertex",
                           "org.apache.tinkerpop.gremlin.structure.Edge",
                           "org.apache.tinkerpop.gremlin.structure.VertexProperty",
                           "org.apache.tinkerpop.gremlin.structure.Direction");
        imports.addStaticStars("org.apache.tinkerpop.gremlin.process.traversal.P",
                               "org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__",
                               "org.apache.tinkerpop.gremlin.process.traversal.Order",
                               "org.apache.tinkerpop.gremlin.process.traversal.Scope",
                               "org.apache.tinkerpop.gremlin.structure.T");
        configuration.addCompilationCustomizers(imports, new ScriptLoopCustomizer(),
                new ASTTransformationCustomizer(Map.of("extensions",
                        ScriptTypeCheckingExtension.class.getName()), CompileStatic.class),
                new ScriptExpressionGuard(lines, this.profile));
        ScriptTypeCheckingExtension.POLICY.set(
                new ScriptTypeCheckingExtension.CompilationPolicy(lines, this.methods));
        GroovyClassLoader loader = new GroovyClassLoader(parent, configuration);
        try {
            Class<?> type = loader.parseClass(prelude + key.source, "HugeGraphPolicyScript.groovy");
            return new CompiledUnit(type, loader);
        } catch (Throwable error) {
            loader.close();
            throw error;
        } finally {
            ScriptTypeCheckingExtension.POLICY.remove();
        }
    }

    public CompiledScript compile(String source, Bindings prototype) throws ScriptException {
        Bindings bindings = ScriptBindings.execution(prototype, this.profile);
        CompiledUnit unit = this.prepare(source, bindings);
        Map<String, Class<?>> types = ScriptBindings.types(bindings);
        return new CompiledScript() {
            @Override
            public Object eval(ScriptContext context) throws ScriptException {
                return PolicyScriptEngine.this.evaluate(source, context, unit, types);
            }

            @Override
            public ScriptEngine getEngine() {
                return PolicyScriptEngine.this;
            }
        };
    }

    @Override
    public CompiledScript compile(String source) {
        return new CompiledScript() {
            @Override
            public Object eval(ScriptContext context) throws ScriptException {
                return PolicyScriptEngine.this.eval(source, context);
            }

            @Override
            public ScriptEngine getEngine() {
                return PolicyScriptEngine.this;
            }
        };
    }

    @Override
    public CompiledScript compile(Reader reader) throws ScriptException {
        return this.compile(read(reader));
    }

    @Override
    public Object eval(Reader reader, ScriptContext context) throws ScriptException {
        return this.eval(read(reader), context);
    }

    @Override
    public Traversal.Admin eval(Bytecode bytecode, Bindings bindings, String traversalSource)
            throws ScriptException {
        if (this.closed) {
            throw new ScriptException("SCRIPT_ENGINE_CLOSED");
        }
        if (this.profile != ScriptExecutionProfile.QUERY) {
            throw new ScriptException("SCRIPT_BYTECODE_PROFILE_DENIED");
        }
        try {
            ScriptBytecodePolicy.validate(bytecode);
            Bindings isolated = ScriptBindings.execution(bindings, this.profile);
            Object source = isolated.get(traversalSource);
            if (!(source instanceof GraphTraversalSource)) {
                throw new IllegalArgumentException("SCRIPT_TRAVERSAL_SOURCE_DENIED");
            }
            Traversal.Admin<?, ?> traversal = JavaTranslator.of((GraphTraversalSource) source)
                    .translate(bytecode);
            return (Traversal.Admin<?, ?>) ScriptResults.prepare(traversal);
        } catch (IllegalArgumentException error) {
            throw new ScriptException(error.getMessage() == null ?
                                      "SCRIPT_BYTECODE_DENIED" : error.getMessage());
        } catch (Exception error) {
            throw new ScriptException("SCRIPT_BYTECODE_DENIED");
        }
    }

    @Override
    public Bindings createBindings() {
        return new SimpleBindings();
    }

    @Override
    public GremlinScriptEngineFactory getFactory() {
        return new GremlinGroovyScriptEngineFactory();
    }

    public Map<String, Long> metrics() {
        Map<String, Long> result = new LinkedHashMap<>(this.metrics.snapshot());
        result.put("cacheHits", this.cache.stats().hitCount());
        result.put("cacheMisses", this.cache.stats().missCount());
        result.put("cacheEntries", this.cache.estimatedSize());
        return Collections.unmodifiableMap(result);
    }

    public long compilationCount() {
        return this.cache.stats().missCount();
    }

    @Override
    public void close() {
        synchronized (this.lifecycle) {
            this.closed = true;
            this.cache.asMap().values().forEach(future -> future.completeExceptionally(
                    new IllegalStateException("SCRIPT_ENGINE_CLOSED")));
            this.cache.invalidateAll();
            this.cache.cleanUp();
            ScriptPolicyMonitor.unregister(this.profile, this);
        }
    }

    private static String read(Reader reader) throws ScriptException {
        try {
            StringBuilder source = new StringBuilder();
            char[] buffer = new char[1024];
            int count;
            while ((count = reader.read(buffer)) != -1) {
                source.append(buffer, 0, count);
                if (source.length() > ScriptBindings.MAX_SOURCE_BYTES) {
                    throw new ScriptException("SCRIPT_SOURCE_LIMIT");
                }
            }
            return source.toString();
        } catch (IOException e) {
            throw new ScriptException(e);
        }
    }

    private static final class CompiledUnit {

        private final Class<?> type;
        private final GroovyClassLoader loader;

        CompiledUnit(Class<?> type, GroovyClassLoader loader) {
            this.type = type;
            this.loader = loader;
        }

        void close() {
            try {
                this.loader.close();
            } catch (IOException ignored) {
                // There is no open script input; closing releases classpath resources.
            }
        }
    }

    private static final class CompilationKey {

        private final String source;
        private final Map<String, Class<?>> types;

        CompilationKey(String source, Map<String, Class<?>> types) {
            this.source = source;
            this.types = types;
        }

        @Override
        public int hashCode() {
            return 31 * this.source.hashCode() + this.types.hashCode();
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof CompilationKey)) {
                return false;
            }
            CompilationKey key = (CompilationKey) other;
            return this.source.equals(key.source) && this.types.equals(key.types);
        }
    }
}
