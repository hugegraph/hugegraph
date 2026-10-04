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

package org.apache.hugegraph.pd.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Stops owned producers without closing resources underneath an unfinished task. */
public final class ShutdownUtil {

    public static final long DRAIN_SECONDS = 30L;

    private ShutdownUtil() {
    }

    public static void awaitTermination(ExecutorService executor, String name) {
        awaitTermination(executor, name, DRAIN_SECONDS, TimeUnit.SECONDS);
    }

    public static void awaitTermination(ExecutorService executor, String name,
                                        long timeout, TimeUnit unit) {
        if (executor == null) {
            return;
        }
        try {
            if (!executor.awaitTermination(timeout, unit)) {
                throw new IllegalStateException("PD shutdown did not drain " + name);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("PD shutdown interrupted while draining " + name, e);
        }
    }

    public static void stopScheduler(ExecutorService executor, String name) {
        if (executor != null) {
            executor.shutdownNow();
            awaitTermination(executor, name);
        }
    }

    public static void finishExecutor(ExecutorService executor, String name) {
        if (executor != null) {
            executor.shutdown();
            awaitTermination(executor, name);
        }
    }
}
