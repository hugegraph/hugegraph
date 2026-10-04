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

package org.apache.hugegraph.pd.boot;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

public class PDRequestGateTest {

    @Test
    public void testRejectedRequestDoesNotEnterHandler() throws Exception {
        PDRequestGate gate = new PDRequestGate();
        gate.stopAndDrain();
        HttpServletResponse response = Mockito.mock(HttpServletResponse.class);
        gate.doFilterInternal(Mockito.mock(HttpServletRequest.class), response,
                              (req, res) -> Assert.fail("closed handler entered"));
        Mockito.verify(response).sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "PD is stopping");
    }

    @Test
    public void testAcceptedHandlerMustReturnBeforeDrain() throws Exception {
        PDRequestGate gate = new PDRequestGate();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread request = new Thread(() -> {
            try {
                gate.doFilterInternal(Mockito.mock(HttpServletRequest.class),
                                      Mockito.mock(HttpServletResponse.class), (req, res) -> {
                    entered.countDown();
                    try {
                        Assert.assertTrue(release.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        throw new AssertionError(e);
                    }
                });
            } catch (Throwable t) {
                error.set(t);
            }
        });
        CountDownLatch drained = new CountDownLatch(1);
        Thread stop = new Thread(() -> {
            try {
                gate.stopAndDrain();
                drained.countDown();
            } catch (Throwable t) {
                error.set(t);
            }
        });
        try {
            request.start();
            Assert.assertTrue(entered.await(5, TimeUnit.SECONDS));
            stop.start();
            Assert.assertFalse(drained.await(100, TimeUnit.MILLISECONDS));
        } finally {
            release.countDown();
            request.join(5000);
            stop.join(5000);
        }
        Assert.assertFalse(request.isAlive());
        Assert.assertFalse(stop.isAlive());
        Assert.assertEquals(0, drained.getCount());
        Assert.assertNull(error.get());
    }
}
