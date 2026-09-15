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

import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/** Fixed profile labels; no source text, binding values or user identity are recorded. */
public final class ScriptPolicyMetrics {

    private final LongAdder compilations = new LongAdder();
    private final LongAdder compileNanos = new LongAdder();
    private final LongAdder queueNanos = new LongAdder();
    private final LongAdder evaluations = new LongAdder();
    private final LongAdder rejected = new LongAdder();
    private final LongAdder timeouts = new LongAdder();
    private final LongAdder executionTimeouts = new LongAdder();

    public void compiled(long elapsed) {
        this.compilations.increment();
        this.compileNanos.add(elapsed);
    }

    public void queued(long elapsed) {
        this.queueNanos.add(elapsed);
    }

    public void evaluated() {
        this.evaluations.increment();
    }

    public void rejected() {
        this.rejected.increment();
    }

    public void timeout() {
        this.timeouts.increment();
    }

    public void executionTimeout() {
        this.executionTimeouts.increment();
    }

    public Map<String, Long> snapshot() {
        return Map.of("compilations", this.compilations.sum(),
                      "compileNanos", this.compileNanos.sum(), "queueNanos", this.queueNanos.sum(),
                      "evaluations", this.evaluations.sum(), "rejected", this.rejected.sum(),
                      "timeouts", this.timeouts.sum(), "executionTimeouts", this.executionTimeouts.sum());
    }
}
