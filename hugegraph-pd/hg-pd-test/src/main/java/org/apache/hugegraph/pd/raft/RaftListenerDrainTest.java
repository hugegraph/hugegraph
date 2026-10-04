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

package org.apache.hugegraph.pd.raft;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.hugegraph.testutil.Whitebox;
import org.junit.Assert;
import org.mockito.Mockito;
import com.alipay.sofa.jraft.Node;
import org.junit.Test;

public class RaftListenerDrainTest {

    @Test
    public void testEngineShutdownDoesNotHoldListenerMonitor() throws Exception {
        RaftEngine engine = new RaftEngine();
        Node node = Mockito.mock(Node.class);
        Whitebox.setInternalState(engine, "raftNode", node);
        RaftStateMachine machine = Whitebox.getInternalState(engine, "stateMachine");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch shutdownStarted = new CountDownLatch(1);
        CountDownLatch listenerFinished = new CountDownLatch(1);
        machine.addStateListener(() -> {
            entered.countDown();
            try {
                Assert.assertTrue(shutdownStarted.await(5, TimeUnit.SECONDS));
                synchronized (engine) {
                    listenerFinished.countDown();
                }
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        });
        Mockito.doAnswer(call -> {
            shutdownStarted.countDown();
            return null;
        }).when(node).shutdown();
        machine.onLeaderStart(1);
        Assert.assertTrue(entered.await(5, TimeUnit.SECONDS));
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread stop = new Thread(() -> {
            try {
                engine.shutDown();
            } catch (Throwable t) {
                error.set(t);
            }
        });
        try {
            stop.start();
            Assert.assertTrue(listenerFinished.await(5, TimeUnit.SECONDS));
            stop.join(5000);
            Assert.assertFalse(stop.isAlive());
            Assert.assertNull(error.get());
        } finally {
            shutdownStarted.countDown();
            if (stop.isAlive()) {
                stop.interrupt();
                stop.join(5000);
            }
        }
    }

    @Test
    public void testAcceptedListenerCannotOutliveDrain() throws Exception {
        RaftStateMachine machine = new RaftStateMachine();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        machine.addStateListener(() -> {
            entered.countDown();
            try {
                Assert.assertTrue(release.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        });
        machine.onLeaderStart(1);
        Assert.assertTrue(entered.await(5, TimeUnit.SECONDS));
        CountDownLatch drained = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread stop = new Thread(() -> {
            try {
                machine.drainListeners();
                drained.countDown();
            } catch (Throwable t) {
                error.set(t);
            }
        });
        try {
            stop.start();
            Assert.assertFalse(drained.await(100, TimeUnit.MILLISECONDS));
        } finally {
            release.countDown();
            stop.join(5000);
        }
        Assert.assertFalse(stop.isAlive());
        Assert.assertNull(error.get());
        Assert.assertEquals(0, drained.getCount());
        // Reinitialization is allowed only once the prior listener executor has terminated.
        machine.prepareListeners();
        CountDownLatch restarted = new CountDownLatch(1);
        machine.addStateListener(restarted::countDown);
        machine.onLeaderStart(2);
        Assert.assertTrue(restarted.await(5, TimeUnit.SECONDS));
        machine.drainListeners();
    }
}
