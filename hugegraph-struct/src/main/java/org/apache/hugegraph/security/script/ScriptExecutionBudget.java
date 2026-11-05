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

public final class ScriptExecutionBudget {

    public static final long TIMEOUT_MILLIS = 30000L;

    private ScriptExecutionBudget() {
    }

    public static long deadline() {
        return System.nanoTime() + TIMEOUT_MILLIS * 1000000L;
    }

    public static void check(long deadline) {
        if (Thread.currentThread().isInterrupted() || System.nanoTime() - deadline >= 0) {
            throw new ExecutionTimeoutException();
        }
    }

    static final class ExecutionTimeoutException extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        ExecutionTimeoutException() {
            super("SCRIPT_EXECUTION_TIMEOUT");
        }
    }
}
