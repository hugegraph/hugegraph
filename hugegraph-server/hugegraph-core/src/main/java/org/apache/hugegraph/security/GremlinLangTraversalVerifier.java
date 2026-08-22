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

import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.step.TraversalParent;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.CallStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.sideEffect.IoStep;

public final class GremlinLangTraversalVerifier {

    private GremlinLangTraversalVerifier() {
    }

    public static void verify(Traversal<?, ?> traversal) {
        verify(traversal.asAdmin());
    }

    static void verify(Traversal.Admin<?, ?> traversal) {
        for (Step<?, ?> step : traversal.getSteps()) {
            if (step instanceof IoStep || step instanceof CallStep) {
                throw new SecurityException(String.format(
                        "The traversal step '%s' is not allowed for remote " +
                        "Gremlin requests", step.getClass().getSimpleName()));
            }
            if (step instanceof TraversalParent) {
                TraversalParent parent = (TraversalParent) step;
                for (Traversal.Admin<?, ?> child : parent.getLocalChildren()) {
                    verify(child);
                }
                for (Traversal.Admin<?, ?> child : parent.getGlobalChildren()) {
                    verify(child);
                }
            }
        }
    }
}
