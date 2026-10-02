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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.hugegraph.security.script.ScriptExecutionBudget;
import org.apache.tinkerpop.gremlin.process.traversal.step.sideEffect.InjectStep;
import org.apache.tinkerpop.gremlin.process.traversal.util.DefaultTraversal;
import org.apache.tinkerpop.gremlin.structure.util.CloseableIterator;
import org.junit.Assert;
import org.junit.Test;

public class ScriptResultPreparationTest {

    @Test
    public void testExpiredPreparationClosesUnownedIterator() throws Exception {
        TrackingIterator cursor = new TrackingIterator();
        Throwable failure = preparationFailure(cursor, System.nanoTime() - 1L);
        Assert.assertEquals("SCRIPT_EXECUTION_TIMEOUT", failure.getMessage());
        Assert.assertEquals(1, cursor.closes);
        Assert.assertEquals(0, cursor.reads);
        discarded(Map.of("cursor", cursor), cursor);
        Assert.assertEquals("session cleanup must not close the rejected result twice", 1, cursor.closes);
    }

    @Test
    public void testInterruptedPreparationClosesUnownedIterator() throws Exception {
        TrackingIterator cursor = new TrackingIterator();
        Thread.currentThread().interrupt();
        try {
            Throwable failure = preparationFailure(cursor, ScriptExecutionBudget.deadline());
            Assert.assertEquals("SCRIPT_EXECUTION_TIMEOUT", failure.getMessage());
            Assert.assertTrue(Thread.currentThread().isInterrupted());
            Assert.assertEquals(1, cursor.closes);
            Assert.assertEquals(0, cursor.reads);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void testExpiredPreparationClosesMaterializedTraversal() throws Exception {
        TrackingTraversal traversal = new TrackingTraversal();
        Assert.assertTrue(traversal.hasNext());
        Assert.assertTrue(traversal.isLocked());
        Throwable failure = preparationFailure(traversal, System.nanoTime() - 1L);
        Assert.assertEquals("SCRIPT_EXECUTION_TIMEOUT", failure.getMessage());
        Assert.assertEquals(1, traversal.closes);
    }

    @Test
    public void testExpiredPreparationClosesNestedCursorsAfterCloseFailure() throws Exception {
        TrackingIterator first = new TrackingIterator();
        first.closeFailure = new IllegalStateException("close failed");
        TrackingIterator second = new TrackingIterator();
        Throwable failure = preparationFailure(List.of(first, Map.of("cursor", second)),
                                               System.nanoTime() - 1L);
        Assert.assertEquals("SCRIPT_EXECUTION_TIMEOUT", failure.getMessage());
        Assert.assertEquals(1, failure.getSuppressed().length);
        Assert.assertSame(first.closeFailure, failure.getSuppressed()[0].getCause());
        Assert.assertEquals(1, first.closes);
        Assert.assertEquals(1, second.closes);
        Assert.assertEquals(0, first.reads + second.reads);
    }

    @Test
    public void testSuccessfulPreparationTransfersCursorOwnership() throws Exception {
        TrackingIterator cursor = new TrackingIterator();
        Iterator<?> prepared = (Iterator<?>) resultMethod("prepare", Object.class, long.class)
                .invoke(null, cursor, ScriptExecutionBudget.deadline());
        Assert.assertEquals(0, cursor.closes);
        Assert.assertEquals(1, prepared.next());
        CloseableIterator.closeIterator(prepared);
        CloseableIterator.closeIterator(prepared);
        Assert.assertEquals(1, cursor.closes);
        Assert.assertEquals(1, cursor.reads);
    }

    private static Throwable preparationFailure(Object value, long deadline) throws Exception {
        Method prepare = resultMethod("prepare", Object.class, long.class);
        InvocationTargetException failure = Assert.assertThrows(InvocationTargetException.class,
                () -> prepare.invoke(null, value, deadline));
        return failure.getCause();
    }

    private static void discarded(Object candidate, Object result) throws Exception {
        resultMethod("closeDiscarded", Object.class, Object.class).invoke(null, candidate, result);
    }

    private static Method resultMethod(String name, Class<?>... types) throws Exception {
        Class<?> results = Class.forName("org.apache.hugegraph.security.script.ScriptResults");
        Method method = results.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method;
    }

    private static final class TrackingIterator implements CloseableIterator<Integer> {

        private int closes;
        private int reads;
        private RuntimeException closeFailure;

        @Override
        public boolean hasNext() {
            this.reads++;
            return true;
        }

        @Override
        public Integer next() {
            this.reads++;
            return 1;
        }

        @Override
        public void close() {
            this.closes++;
            if (this.closeFailure != null) {
                throw this.closeFailure;
            }
        }
    }

    private static final class TrackingTraversal extends DefaultTraversal<Integer, Integer> {

        private static final long serialVersionUID = 1L;
        private int closes;

        private TrackingTraversal() {
            this.addStep(new InjectStep<>(this, 1));
        }

        @Override
        public void close() throws Exception {
            this.closes++;
            super.close();
        }
    }
}
