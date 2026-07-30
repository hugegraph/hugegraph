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

package org.apache.hugegraph.store.client.grpc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.stub.AbstractAsyncStub;
import io.grpc.stub.AbstractBlockingStub;

/**
 * Verifies that Store address changes replace channels and their cached stubs safely.
 */
public class AbstractGrpcClientTest {

    private static final AtomicInteger TARGET_SEQ = new AtomicInteger();

    private static String uniqueTarget(String prefix) {
        return prefix + "-" + TARGET_SEQ.incrementAndGet() + ":8500";
    }

    private static boolean belongsToPool(Channel channel,
                                         ManagedChannel[] channels) {
        return Arrays.stream(channels).anyMatch(current -> current == channel);
    }

    @Test
    public void testAddressChangeReplacesChannelAndStubPools() {
        String target = uniqueTarget("address-change");
        RecordingGrpcClient client = new RecordingGrpcClient();
        ManagedChannel[] oldChannels = client.getChannels(target);
        assertNotNull(client.getBlockingStub(target));
        assertNotNull(client.getAsyncStub(target));

        client.resolvedTarget = "10.0.0.2";
        ManagedChannel[] newChannels = client.getChannels(target);
        assertNotSame("an address change must replace the channel pool",
                      oldChannels, newChannels);
        assertTrue("every stale channel must be shut down",
                   Arrays.stream(oldChannels).allMatch(ManagedChannel::isShutdown));

        client.blockingStubChannels.clear();
        client.asyncStubChannels.clear();
        assertNotNull(client.getBlockingStub(target));
        assertNotNull(client.getAsyncStub(target));
        assertEquals("the blocking stub pool must be rebuilt",
                     newChannels.length, client.blockingStubChannels.size());
        assertTrue("replacement blocking stubs must use the new channel pool",
                   client.blockingStubChannels.stream()
                                              .allMatch(channel ->
                                                        belongsToPool(channel, newChannels)));
        assertEquals("the async stub pool must be rebuilt",
                     newChannels.length, client.asyncStubChannels.size());
        assertTrue("replacement async stubs must use the new channel pool",
                   client.asyncStubChannels.stream()
                                           .allMatch(channel ->
                                                     belongsToPool(channel, newChannels)));
    }

    @Test
    public void testFirstSuccessfulResolutionReplacesUnknownChannels() {
        String target = uniqueTarget("first-successful-resolution");
        RecordingGrpcClient client = new RecordingGrpcClient();
        client.resolvedTarget = "";
        ManagedChannel[] unknownChannels = client.getChannels(target);

        client.resolvedTarget = "10.0.0.1";
        ManagedChannel[] resolvedChannels = client.getChannels(target);
        assertNotSame("a pool with unknown addresses must be replaced",
                      unknownChannels, resolvedChannels);
        assertTrue("every channel from the unknown pool must be shut down",
                   Arrays.stream(unknownChannels).allMatch(ManagedChannel::isShutdown));
        assertTrue("the resolved channel pool must remain live",
                   Arrays.stream(resolvedChannels).noneMatch(ManagedChannel::isShutdown));
    }

    @Test
    public void testOlderResolutionCannotReplaceNewerChannels() throws Exception {
        String target = uniqueTarget("concurrent-address-change");
        OutOfOrderResolverGrpcClient client = new OutOfOrderResolverGrpcClient();
        ManagedChannel[] oldChannels = client.getChannels(target);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<ManagedChannel[]> staleResolution =
                    executor.submit(() -> client.getChannels(target));
            assertTrue("the stale resolution must be in flight",
                       client.staleResolutionStarted.await(5, TimeUnit.SECONDS));
            Future<ManagedChannel[]> freshResolution =
                    executor.submit(() -> client.getChannels(target));
            ManagedChannel[] freshChannels = freshResolution.get(5, TimeUnit.SECONDS);

            assertNotSame("the newer address must replace the old channel pool",
                          oldChannels, freshChannels);
            client.releaseStaleResolution.countDown();
            assertSame("the late stale result must retain the newer channel pool",
                       freshChannels, staleResolution.get(5, TimeUnit.SECONDS));
            assertTrue("the replaced channel pool must be shut down",
                       Arrays.stream(oldChannels).allMatch(ManagedChannel::isShutdown));
            assertTrue("the newer channel pool must remain live",
                       Arrays.stream(freshChannels).noneMatch(ManagedChannel::isShutdown));
        } finally {
            client.releaseStaleResolution.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void testStubBuildRetriesAfterChannelRefresh() throws Exception {
        String target = uniqueTarget("concurrent-stub-refresh");
        StubInterleavingGrpcClient client = new StubInterleavingGrpcClient();
        ManagedChannel[] oldChannels = client.getChannels(target);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<AbstractBlockingStub> staleStub =
                    executor.submit(() -> client.getBlockingStub(target));
            assertTrue("the old stub pool build must be in flight",
                       client.staleStubBuildStarted.await(5, TimeUnit.SECONDS));
            client.resolvedTarget = "10.0.0.2";
            Future<AbstractBlockingStub> freshStub =
                    executor.submit(() -> client.getBlockingStub(target));
            for (int i = 0; i < 500 &&
                            Arrays.stream(oldChannels).anyMatch(channel -> !channel.isShutdown());
                 i++) {
                Thread.sleep(10L);
            }
            assertTrue("refresh must shut down the old channel pool",
                       Arrays.stream(oldChannels).allMatch(ManagedChannel::isShutdown));
            client.releaseStaleStubBuild.countDown();

            AbstractBlockingStub staleResult = staleStub.get(5, TimeUnit.SECONDS);
            AbstractBlockingStub freshResult = freshStub.get(5, TimeUnit.SECONDS);
            ManagedChannel[] currentChannels = client.getChannels(target);
            assertTrue("the stale build must retry against the current pool",
                       belongsToPool(staleResult.getChannel(), currentChannels));
            assertTrue("the concurrent build must use the current pool",
                       belongsToPool(freshResult.getChannel(), currentChannels));
            assertTrue("the current channel pool must remain live",
                       Arrays.stream(currentChannels).noneMatch(ManagedChannel::isShutdown));
        } finally {
            client.releaseStaleStubBuild.countDown();
            executor.shutdownNow();
        }
    }

    private static class RecordingGrpcClient extends AbstractGrpcClient {

        private final AtomicInteger channelSeq = new AtomicInteger();
        protected volatile String resolvedTarget = "10.0.0.1";
        private final List<ManagedChannel> blockingStubChannels =
                Collections.synchronizedList(new ArrayList<>());
        private final List<ManagedChannel> asyncStubChannels =
                Collections.synchronizedList(new ArrayList<>());

        @Override
        protected ManagedChannel createChannel(String target) {
            return new FakeManagedChannel(target + "#" + this.channelSeq.getAndIncrement());
        }

        @Override
        protected String resolveTarget(String target) {
            return this.resolvedTarget;
        }

        @Override
        public AbstractBlockingStub getBlockingStub(ManagedChannel channel) {
            this.blockingStubChannels.add(channel);
            return new FakeBlockingStub(channel, CallOptions.DEFAULT);
        }

        @Override
        public AbstractAsyncStub getAsyncStub(ManagedChannel channel) {
            this.asyncStubChannels.add(channel);
            return new FakeAsyncStub(channel, CallOptions.DEFAULT);
        }
    }

    private static class OutOfOrderResolverGrpcClient extends RecordingGrpcClient {

        private final AtomicInteger resolutionSeq = new AtomicInteger();
        private final CountDownLatch staleResolutionStarted = new CountDownLatch(1);
        private final CountDownLatch releaseStaleResolution = new CountDownLatch(1);

        @Override
        protected String resolveTarget(String target) {
            int sequence = this.resolutionSeq.incrementAndGet();
            if (sequence == 1) {
                return "10.0.0.1";
            }
            if (sequence == 2) {
                this.staleResolutionStarted.countDown();
                try {
                    assertTrue("the stale resolution must be released",
                               this.releaseStaleResolution.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
                return "10.0.0.1";
            }
            return "10.0.0.2";
        }
    }

    private static class StubInterleavingGrpcClient extends RecordingGrpcClient {

        private final AtomicInteger blockingStubSeq = new AtomicInteger();
        private final CountDownLatch staleStubBuildStarted = new CountDownLatch(1);
        private final CountDownLatch releaseStaleStubBuild = new CountDownLatch(1);

        @Override
        public AbstractBlockingStub getBlockingStub(ManagedChannel channel) {
            if (this.blockingStubSeq.incrementAndGet() == 1) {
                this.staleStubBuildStarted.countDown();
                try {
                    assertTrue("the stale stub build must be released",
                               this.releaseStaleStubBuild.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }
            return super.getBlockingStub(channel);
        }
    }

    private static class FakeBlockingStub extends AbstractBlockingStub<FakeBlockingStub> {

        FakeBlockingStub(Channel channel, CallOptions callOptions) {
            super(channel, callOptions);
        }

        @Override
        protected FakeBlockingStub build(Channel channel, CallOptions callOptions) {
            return new FakeBlockingStub(channel, callOptions);
        }
    }

    private static class FakeAsyncStub extends AbstractAsyncStub<FakeAsyncStub> {

        FakeAsyncStub(Channel channel, CallOptions callOptions) {
            super(channel, callOptions);
        }

        @Override
        protected FakeAsyncStub build(Channel channel, CallOptions callOptions) {
            return new FakeAsyncStub(channel, callOptions);
        }
    }

    private static class FakeManagedChannel extends ManagedChannel {

        private final String authority;
        private volatile boolean shutdown;

        FakeManagedChannel(String authority) {
            this.authority = authority;
        }

        @Override
        public String authority() {
            return this.authority;
        }

        @Override
        public <Q, S> ClientCall<Q, S> newCall(MethodDescriptor<Q, S> method,
                                               CallOptions callOptions) {
            throw new UnsupportedOperationException("no call is issued by this test");
        }

        @Override
        public ManagedChannel shutdown() {
            this.shutdown = true;
            return this;
        }

        @Override
        public ManagedChannel shutdownNow() {
            return this.shutdown();
        }

        @Override
        public boolean isShutdown() {
            return this.shutdown;
        }

        @Override
        public boolean isTerminated() {
            return this.shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return this.shutdown;
        }
    }
}
