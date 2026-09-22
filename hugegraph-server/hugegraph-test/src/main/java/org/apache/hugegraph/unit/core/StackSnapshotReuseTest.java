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

package org.apache.hugegraph.unit.core;

import org.apache.hugegraph.security.HugeSecurityManager;
import org.apache.hugegraph.testutil.Whitebox;
import org.junit.Assert;
import org.junit.Test;

public class StackSnapshotReuseTest {

    @Test
    public void testUnrelatedPermissionDoesNotCaptureStack() {
        resetCaptures();
        new HugeSecurityManager().checkPermission(new RuntimePermission("setIO"));
        Assert.assertEquals(0, captures());
    }

    @Test
    public void testNonGremlinWorkerDoesNotCaptureStack() {
        String previous = Thread.currentThread().getName();
        resetCaptures();
        try {
            Thread.currentThread().setName("main");
            new HugeSecurityManager().checkAccess(Thread.currentThread());
            Assert.assertEquals(0, captures());
        } finally {
            Thread.currentThread().setName(previous);
        }
    }

    @Test
    public void testGremlinWorkerReusesOneSnapshotForAllHelpers() {
        String previous = Thread.currentThread().getName();
        try {
            Thread.currentThread().setName("gremlin-server-exec-reuse");
            int count = Whitebox.invokeStatic(HugeSecurityManager.class,
                                              "captureCountRunningAllHelpers");
            Assert.assertEquals(1, count);
        } finally {
            Thread.currentThread().setName(previous);
        }
    }

    private static void resetCaptures() {
        Whitebox.invokeStatic(HugeSecurityManager.class, "resetStackCapturesForTest");
    }

    private static int captures() {
        return Whitebox.invokeStatic(HugeSecurityManager.class, "stackCapturesForTest");
    }
}
