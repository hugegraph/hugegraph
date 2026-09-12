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

package org.apache.hugegraph.benchmark;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.script.Bindings;
import javax.script.Compilable;
import javax.script.CompiledScript;
import javax.script.ScriptEngine;
import javax.script.SimpleBindings;

import org.apache.hugegraph.security.HugeSecurityManager;
import org.apache.hugegraph.security.script.PolicyScriptEngine;
import org.apache.hugegraph.security.script.ScriptElementView;
import org.apache.hugegraph.security.script.ScriptExecutionProfile;
import org.apache.hugegraph.security.script.ScriptPolicyRuntime;
import org.apache.tinkerpop.gremlin.groovy.jsr223.GremlinGroovyScriptEngine;
import org.apache.tinkerpop.gremlin.structure.util.empty.EmptyGraph;
import org.codehaus.groovy.jsr223.GroovyScriptEngineImpl;

/** Manual engine microbenchmark. Run each variant in a separate JVM; never as a CI test. */
public final class ScriptPolicyBenchmark {

    private static volatile int consumed;

    public static void main(String[] args) throws Exception {
        String variant = args[0];
        boolean policy = variant.equals("C") || variant.equals("E");
        boolean manager = variant.equals("B") || variant.equals("C");
        if (ScriptPolicyRuntime.enabled() != policy) {
            throw new IllegalArgumentException("Benchmark variant and JVM mode disagree");
        }
        ScriptEngine query = policy ? new PolicyScriptEngine(ScriptExecutionProfile.QUERY) :
                             new GremlinGroovyScriptEngine();
        ScriptEngine store = policy ? new PolicyScriptEngine(ScriptExecutionProfile.STORE_FILTER) :
                             new GroovyScriptEngineImpl();
        Bindings bindings = new SimpleBindings(Map.of("g", EmptyGraph.instance().traversal(), "benchmarkValue", 2));
        String source = "g.inject(benchmarkValue, 2, 3).map { it.get() + 1 }.toList()";
        String condition = "(int) element.property('age') > 18";
        Bindings element = new SimpleBindings(Map.of("element",
                new ScriptElementView("v1", "person", Map.of("age", 20))));
        if (!List.of(3, 3, 4).equals(query.eval(source, bindings))) {
            throw new IllegalStateException("Unexpected query result");
        }
        CompiledScript filter = policy ? ((PolicyScriptEngine) store).compile(condition, element) :
                                ((Compilable) store).compile(condition);
        if (!Boolean.TRUE.equals(filter.eval(element))) {
            throw new IllegalStateException("Unexpected filter result");
        }
        if (manager) {
            System.setSecurityManager(new HugeSecurityManager());
        }
        Thread.currentThread().setName("gremlin-server-exec-policy-benchmark");
        try {
            measure(variant, "warm-closure-query", () -> query.eval(source, bindings));
            measure(variant, "warm-store-condition", () -> filter.eval(element));
            long[] cold = new long[12];
            for (int i = 0; i < cold.length; i++) {
                long begin = System.nanoTime();
                query.eval("benchmarkValue + 1 // cold " + i, bindings);
                cold[i] = System.nanoTime() - begin;
            }
            report(variant, "cold-compile", cold, Arrays.stream(cold).sum());
            System.out.printf("BENCHMARK_COMPLETE,%s,manager=%s,consumed=%d%n", variant,
                    System.getSecurityManager() == null ? "none" : "installed", consumed);
        } finally {
            Thread.currentThread().setName("main");
            if (query instanceof PolicyScriptEngine) {
                ((PolicyScriptEngine) query).close();
                ((PolicyScriptEngine) store).close();
            }
        }
    }

    private static void measure(String variant, String name, Operation operation) throws Exception {
        long warm = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < warm) {
            consumed ^= operation.run().hashCode();
        }
        long[] samples = new long[10000];
        long begin = System.nanoTime();
        for (int i = 0; i < samples.length; i++) {
            long start = System.nanoTime();
            consumed ^= operation.run().hashCode();
            samples[i] = System.nanoTime() - start;
        }
        report(variant, name, samples, System.nanoTime() - begin);
    }

    private static void report(String variant, String name, long[] samples, long elapsed) {
        Arrays.sort(samples);
        System.out.printf(Locale.ROOT, "BENCHMARK,%s,%s,%d,%.3f,%.3f,%.3f,%.3f%n",
                variant, name, samples.length, samples[samples.length / 2] / 1000.0,
                samples[(int) (samples.length * 0.95)] / 1000.0,
                samples[(int) (samples.length * 0.99)] / 1000.0,
                samples.length * 1000000000.0 / elapsed);
    }

    @FunctionalInterface
    private interface Operation {
        Object run() throws Exception;
    }
}
