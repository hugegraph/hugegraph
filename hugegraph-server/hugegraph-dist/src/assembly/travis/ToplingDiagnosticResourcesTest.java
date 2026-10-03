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

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

public final class ToplingDiagnosticResourcesTest {

    public static void main(String[] args) throws Exception {
        testIdentityRemoval();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        IllegalStateException expected = new IllegalStateException("injected close failure");
        boolean[] nextCleanupRan = {false};
        try (PrintStream capture = new PrintStream(output, true, StandardCharsets.UTF_8.name())) {
            System.setOut(capture);
            System.setErr(capture);
            try (RocksDBRuntimeSmokeTest.DiagnosticResources resources =
                         new RocksDBRuntimeSmokeTest.DiagnosticResources()) {
                resources.add(() -> {
                    String log = output.toString(StandardCharsets.UTF_8.name());
                    if (!log.contains("Topling diagnostic phase: failed") ||
                        !log.contains("injected close failure")) {
                        throw new AssertionError("failure was hidden before subsequent cleanup");
                    }
                    nextCleanupRan[0] = true;
                });
                resources.add(() -> {
                    throw expected;
                });
            } catch (IllegalStateException failure) {
                if (failure != expected || failure.getSuppressed().length != 0) {
                    throw new AssertionError("unexpected cleanup failure", failure);
                }
            }
            if (!nextCleanupRan[0]) {
                throw new AssertionError("remaining resource was not closed");
            }
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        System.out.println("PASS: Java close failure recorded before remaining cleanup");
    }

    private static void testIdentityRemoval() throws Exception {
        IdentityOnlyResource remaining = new IdentityOnlyResource();
        IdentityOnlyResource removed = new IdentityOnlyResource();
        try (RocksDBRuntimeSmokeTest.DiagnosticResources resources =
                     new RocksDBRuntimeSmokeTest.DiagnosticResources()) {
            resources.add(remaining);
            resources.add(removed);
            removed.close();
            resources.remove(removed);
        }
        if (!remaining.closed) {
            throw new AssertionError("remaining resource was not closed");
        }
        System.out.println("PASS: closed resources removed by identity without equals");
    }

    private static final class IdentityOnlyResource implements AutoCloseable {

        private boolean closed;

        @Override
        public boolean equals(Object other) {
            throw new AssertionError("native-backed equals must not be called");
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(this);
        }

        @Override
        public void close() {
            if (this.closed) {
                throw new AssertionError("resource was closed twice");
            }
            this.closed = true;
        }
    }

    private ToplingDiagnosticResourcesTest() {
    }
}
