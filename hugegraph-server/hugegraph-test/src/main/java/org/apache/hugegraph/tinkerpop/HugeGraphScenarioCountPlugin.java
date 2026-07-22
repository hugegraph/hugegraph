/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
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

package org.apache.hugegraph.tinkerpop;

import java.util.concurrent.atomic.AtomicInteger;

import io.cucumber.plugin.ConcurrentEventListener;
import io.cucumber.plugin.event.EventPublisher;
import io.cucumber.plugin.event.TestCaseStarted;
import io.cucumber.plugin.event.TestRunFinished;

public final class HugeGraphScenarioCountPlugin
        implements ConcurrentEventListener {

    private static final int MIN_EXPECTED_SCENARIOS = 300;

    private final AtomicInteger scenarioCount = new AtomicInteger();

    @Override
    public void setEventPublisher(EventPublisher publisher) {
        publisher.registerHandlerFor(TestCaseStarted.class,
                                     event -> this.scenarioCount.incrementAndGet());
        publisher.registerHandlerFor(TestRunFinished.class,
                                     event -> this.assertScenariosExecuted());
    }

    private void assertScenariosExecuted() {
        /*
         * The current TAGS expression matches ~345 scenarios. Assert a lower
         * bound rather than >0 so that a single broken tag (which silently
         * drops an entire feature's scenarios) fails the run.
         */
        if (this.scenarioCount.get() < MIN_EXPECTED_SCENARIOS) {
            throw new AssertionError(
                    "Only " + this.scenarioCount.get() + " TinkerPop Gherkin " +
                    "scenarios were executed, expected at least " +
                    MIN_EXPECTED_SCENARIOS +
                    " (check the TAGS/NAMES filters for typos or renames)");
        }
    }
}
