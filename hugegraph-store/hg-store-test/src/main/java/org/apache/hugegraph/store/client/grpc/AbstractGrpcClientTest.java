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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.apache.hugegraph.store.term.HgPair;
import org.junit.Test;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.stub.AbstractAsyncStub;
import io.grpc.stub.AbstractBlockingStub;

/**
 * Verifies that Store address changes replace channels and cached stubs safely.
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

    private static boolean allChannelsAreShutdown(ManagedChannel[] channels) {
        return Arrays.stream(channels).allMatch(ManagedChannel::isShutdown);
    }

    private static boolean allChannelsAreLive(ManagedChannel[] channels) {
        return Arrays.stream(channels).noneMatch(ManagedChannel::isShutdown);
    }

    private static List<FakeManagedChannel> fakeChannels(ManagedChannel[] channels) {
        return Arrays.stream(channels)
                     .map(channel -> (FakeManagedChannel) channel)
                     .collect(Collectors.toList());
    }

    private static void assertUsesEveryChannel(String message,
                                               List<ManagedChannel> stubChannels,
                                               ManagedChannel[] channels) {
        assertEquals(message, channels.length, new HashSet<>(stubChannels).size());
    }

    private static void assertCachedChannelsCurrentAndLive(String message,
                                                           List<ManagedChannel> cached,
                                                           ManagedChannel[] current) {
        assertEquals(message, current.length, cached.size());
        assertTrue(message, cached.stream().allMatch(channel ->
                   belongsToPool(channel, current) && !channel.isShutdown()));
    }

    private static void awaitCondition(String message, Condition condition) throws Exception {
        for (int i = 0; i < 500; i++) {
            if (condition.isTrue()) {
                return;
            }
            Thread.sleep(10L);
        }
        assertTrue(message, condition.isTrue());
    }

    @SuppressWarnings("unchecked")
    private static List<ManagedChannel> cachedAsyncStubChannels(AbstractGrpcClient client,
                                                                String target)
            throws Exception {
        Field field = AbstractGrpcClient.class.getDeclaredField("asyncStubs");
        field.setAccessible(true);
        Map<String, HgPair<ManagedChannel, AbstractAsyncStub>[]> stubs =
                (Map<String, HgPair<ManagedChannel, AbstractAsyncStub>[]>) field.get(client);
        HgPair<ManagedChannel, AbstractAsyncStub>[] pairs = stubs.get(target);
        assertNotNull("the async stub cache must exist", pairs);
        return Arrays.stream(pairs).map(HgPair::getKey).collect(Collectors.toList());
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
        assertTrue("every stale channel must be gracefully shut down",
                   allChannelsAreShutdown(oldChannels));
        assertFalse("refresh must not force close stale channels immediately",
                    fakeChannels(oldChannels).stream()
                                             .anyMatch(FakeManagedChannel::isForceShutdown));

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
        assertUsesEveryChannel("blocking stubs must be spread across the pool",
                               client.blockingStubChannels, newChannels);
        assertEquals("the async stub pool must be rebuilt",
                     newChannels.length, client.asyncStubChannels.size());
        assertTrue("replacement async stubs must use the new channel pool",
                   client.asyncStubChannels.stream()
                                           .allMatch(channel ->
                                                     belongsToPool(channel, newChannels)));
        assertUsesEveryChannel("async stubs must be spread across the pool",
                               client.asyncStubChannels, newChannels);
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
        assertTrue("every channel from the unknown pool must be gracefully shut down",
                   allChannelsAreShutdown(unknownChannels));
        assertFalse("unknown channels must not be force closed immediately",
                    fakeChannels(unknownChannels).stream()
                                                 .anyMatch(FakeManagedChannel::isForceShutdown));
        assertTrue("the resolved channel pool must remain live",
                   allChannelsAreLive(resolvedChannels));
    }

    @Test
    public void testStubAcquisitionReusesResolutionWithinRefreshInterval() {
        String target = uniqueTarget("throttled-refresh");
        CountingResolverGrpcClient client = new CountingResolverGrpcClient();
        client.refreshIntervalNanos = TimeUnit.HOURS.toNanos(1L);

        assertNotNull(client.getBlockingStub(target));
        assertNotNull(client.getAsyncStub(target));
        for (int i = 0; i < 10; i++) {
            assertNotNull(client.getBlockingStub(target));
            assertNotNull(client.getAsyncStub(target));
        }

        assertEquals("stub acquisition must not resolve again inside the refresh interval",
                     1, client.resolutionCount.get());
    }

    @Test
    public void testConcurrentStubAcquisitionRetainsHealthyPoolDuringDelayedRefresh()
            throws Exception {
        String target = uniqueTarget("delayed-refresh");
        DelayedResolverGrpcClient client = new DelayedResolverGrpcClient();
        client.refreshIntervalNanos = 0L;
        ManagedChannel[] oldChannels = client.getChannels(target);
        assertNotNull(client.getBlockingStub(target));
        int resolutionsBeforeConcurrentCalls = client.resolutionCount.get();

        client.resolvedTarget = "10.0.0.2";
        client.delayChangedResolution = true;
        client.refreshIntervalNanos = TimeUnit.HOURS.toNanos(1L);

        ExecutorService executor = Executors.newFixedThreadPool(6);
        List<Future<AbstractBlockingStub>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 6; i++) {
                futures.add(executor.submit(() -> client.getBlockingStub(target)));
            }
            assertTrue("one refresh should be waiting in the delayed resolver",
                       client.delayedResolutionStarted.await(5, TimeUnit.SECONDS));
            awaitCondition("callers that miss the refresh lock must keep using the cache",
                           () -> futures.stream().anyMatch(Future::isDone));
            assertTrue("the existing healthy pool must stay live during refresh",
                       allChannelsAreLive(oldChannels));

            client.releaseDelayedResolution.countDown();
            for (Future<AbstractBlockingStub> future : futures) {
                assertNotNull(future.get(5, TimeUnit.SECONDS));
            }

            ManagedChannel[] currentChannels = client.getChannels(target);
            assertNotSame("the completed refresh must publish a new channel pool",
                          oldChannels, currentChannels);
            assertTrue("the previous pool must be retired after replacement is published",
                       allChannelsAreShutdown(oldChannels));
            assertEquals("concurrent callers must share a single refresh resolution",
                         resolutionsBeforeConcurrentCalls + 1,
                         client.resolutionCount.get());
        } finally {
            client.releaseDelayedResolution.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void testRefreshGracefullyRetiresActiveStreamChannels() throws Exception {
        String target = uniqueTarget("active-stream-refresh");
        ActiveRetirementGrpcClient client = new ActiveRetirementGrpcClient();
        ManagedChannel[] oldChannels = client.getChannels(target);
        AbstractAsyncStub activeStreamStub = client.getAsyncStub(target);
        assertTrue("the simulated active stream must be on the old pool",
                   belongsToPool(activeStreamStub.getChannel(), oldChannels));

        client.resolvedTarget = "10.0.0.2";
        ManagedChannel[] newChannels = client.getChannels(target);
        assertNotSame("an address change must publish a replacement pool first",
                      oldChannels, newChannels);
        List<FakeManagedChannel> retiredChannels = fakeChannels(oldChannels);
        assertTrue("the retired pool must receive graceful shutdown",
                   retiredChannels.stream().allMatch(FakeManagedChannel::isShutdown));
        assertFalse("active streams must not be force closed immediately",
                    retiredChannels.stream().anyMatch(FakeManagedChannel::isForceShutdown));
        assertTrue("retirement should wait for in-flight calls to drain",
                   retiredChannels.get(0)
                                  .awaitTerminationStarted(5, TimeUnit.SECONDS));

        client.finishActiveCalls();
        awaitCondition("retired channels should terminate after active calls finish",
                       () -> retiredChannels.stream().allMatch(FakeManagedChannel::isTerminated));
        assertFalse("drained channels must not need forced shutdown",
                    retiredChannels.stream().anyMatch(FakeManagedChannel::isForceShutdown));
        assertTrue("the replacement pool must remain live",
                   allChannelsAreLive(newChannels));
    }

    @Test
    public void testBlockingStubBuildRetriesAfterChannelRefresh() throws Exception {
        String target = uniqueTarget("concurrent-blocking-stub-refresh");
        StubInterleavingGrpcClient client = new StubInterleavingGrpcClient();
        ManagedChannel[] oldChannels = client.getChannels(target);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<AbstractBlockingStub> staleStub =
                    executor.submit(() -> client.getBlockingStub(target));
            assertTrue("the old blocking stub pool build must be in flight",
                       client.staleBlockingStubBuildStarted.await(5, TimeUnit.SECONDS));
            client.resolvedTarget = "10.0.0.2";
            Future<AbstractBlockingStub> freshStub =
                    executor.submit(() -> client.getBlockingStub(target));
            awaitCondition("refresh must retire the old channel pool",
                           () -> allChannelsAreShutdown(oldChannels));
            client.releaseStaleBlockingStubBuild.countDown();

            AbstractBlockingStub staleResult = staleStub.get(5, TimeUnit.SECONDS);
            AbstractBlockingStub freshResult = freshStub.get(5, TimeUnit.SECONDS);
            ManagedChannel[] currentChannels = client.getChannels(target);
            assertTrue("the stale build must retry against the current pool",
                       belongsToPool(staleResult.getChannel(), currentChannels));
            assertTrue("the concurrent build must use the current pool",
                       belongsToPool(freshResult.getChannel(), currentChannels));
            assertTrue("the current channel pool must remain live",
                       allChannelsAreLive(currentChannels));
        } finally {
            client.releaseStaleBlockingStubBuild.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void testAsyncStubBuildRetriesAfterChannelRefresh() throws Exception {
        String target = uniqueTarget("concurrent-async-stub-refresh");
        StubInterleavingGrpcClient client = new StubInterleavingGrpcClient();
        ManagedChannel[] oldChannels = client.getChannels(target);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<AbstractAsyncStub> staleStub =
                    executor.submit(() -> client.getAsyncStub(target));
            assertTrue("the old async stub pool build must be in flight",
                       client.staleAsyncStubBuildStarted.await(5, TimeUnit.SECONDS));
            client.resolvedTarget = "10.0.0.2";
            Future<AbstractAsyncStub> freshStub =
                    executor.submit(() -> client.getAsyncStub(target));
            awaitCondition("refresh must retire the old channel pool",
                           () -> allChannelsAreShutdown(oldChannels));
            client.releaseStaleAsyncStubBuild.countDown();

            AbstractAsyncStub staleResult = staleStub.get(5, TimeUnit.SECONDS);
            AbstractAsyncStub freshResult = freshStub.get(5, TimeUnit.SECONDS);
            ManagedChannel[] currentChannels = client.getChannels(target);
            assertTrue("the stale async build must retry against the current pool",
                       belongsToPool(staleResult.getChannel(), currentChannels));
            assertTrue("the concurrent async build must use the current pool",
                       belongsToPool(freshResult.getChannel(), currentChannels));
            assertCachedChannelsCurrentAndLive(
                    "the final async cache must only reference current live channels",
                    cachedAsyncStubChannels(client, target), currentChannels);
        } finally {
            client.releaseStaleAsyncStubBuild.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void testResolveTargetSupportsDnsUriAndBracketedIpv6Targets() {
        HostCapturingGrpcClient dnsClient = new HostCapturingGrpcClient();
        assertEquals("10.0.0.1", dnsClient.resolveTarget("dns:///store.example.com:8500"));
        assertEquals("store.example.com", dnsClient.capturedHost);

        HostCapturingGrpcClient ipv6Client = new HostCapturingGrpcClient();
        assertEquals("10.0.0.1", ipv6Client.resolveTarget("[2001:db8::1]:8500"));
        assertEquals("2001:db8::1", ipv6Client.capturedHost);
    }

    @Test
    public void testResolveTargetSkipsUnsupportedGrpcSchemes() {
        HostCapturingGrpcClient client = new HostCapturingGrpcClient();
        assertEquals("", client.resolveTarget("unix:///var/run/store.sock"));
        assertEquals("unsupported schemes must not invoke DNS resolution",
                     0, client.resolutionCount.get());
    }

    private interface Condition {

        boolean isTrue();
    }

    private static class RecordingGrpcClient extends AbstractGrpcClient {

        private final AtomicInteger channelSeq = new AtomicInteger();
        protected volatile String resolvedTarget = "10.0.0.1";
        protected volatile long refreshIntervalNanos = 0L;
        protected final List<ManagedChannel> blockingStubChannels =
                Collections.synchronizedList(new ArrayList<>());
        protected final List<ManagedChannel> asyncStubChannels =
                Collections.synchronizedList(new ArrayList<>());

        @Override
        protected long channelRefreshIntervalNanos() {
            return this.refreshIntervalNanos;
        }

        @Override
        protected ManagedChannel createChannel(String target) {
            return this.newFakeChannel(target + "#" + this.channelSeq.getAndIncrement());
        }

        protected FakeManagedChannel newFakeChannel(String authority) {
            return new FakeManagedChannel(authority);
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

    private static class CountingResolverGrpcClient extends RecordingGrpcClient {

        protected final AtomicInteger resolutionCount = new AtomicInteger();

        @Override
        protected String resolveTarget(String target) {
            this.resolutionCount.incrementAndGet();
            return super.resolveTarget(target);
        }
    }

    private static class DelayedResolverGrpcClient extends CountingResolverGrpcClient {

        private final CountDownLatch delayedResolutionStarted = new CountDownLatch(1);
        private final CountDownLatch releaseDelayedResolution = new CountDownLatch(1);
        private volatile boolean delayChangedResolution;

        @Override
        protected String resolveTarget(String target) {
            this.resolutionCount.incrementAndGet();
            if (this.delayChangedResolution) {
                this.delayedResolutionStarted.countDown();
                try {
                    assertTrue("the delayed resolution must be released",
                               this.releaseDelayedResolution.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }
            return this.resolvedTarget;
        }
    }

    private static class ActiveRetirementGrpcClient extends RecordingGrpcClient {

        private final CountDownLatch activeCallsFinished = new CountDownLatch(1);

        @Override
        protected long channelDrainTimeoutNanos() {
            return TimeUnit.SECONDS.toNanos(5L);
        }

        @Override
        protected FakeManagedChannel newFakeChannel(String authority) {
            return new FakeManagedChannel(authority, this.activeCallsFinished);
        }

        private void finishActiveCalls() {
            this.activeCallsFinished.countDown();
        }
    }

    private static class StubInterleavingGrpcClient extends RecordingGrpcClient {

        private final AtomicInteger blockingStubSeq = new AtomicInteger();
        private final AtomicInteger asyncStubSeq = new AtomicInteger();
        private final CountDownLatch staleBlockingStubBuildStarted = new CountDownLatch(1);
        private final CountDownLatch releaseStaleBlockingStubBuild = new CountDownLatch(1);
        private final CountDownLatch staleAsyncStubBuildStarted = new CountDownLatch(1);
        private final CountDownLatch releaseStaleAsyncStubBuild = new CountDownLatch(1);

        @Override
        public AbstractBlockingStub getBlockingStub(ManagedChannel channel) {
            if (this.blockingStubSeq.incrementAndGet() == 1) {
                this.staleBlockingStubBuildStarted.countDown();
                try {
                    assertTrue("the stale blocking stub build must be released",
                               this.releaseStaleBlockingStubBuild.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }
            return super.getBlockingStub(channel);
        }

        @Override
        public AbstractAsyncStub getAsyncStub(ManagedChannel channel) {
            if (this.asyncStubSeq.incrementAndGet() == 1) {
                this.staleAsyncStubBuildStarted.countDown();
                try {
                    assertTrue("the stale async stub build must be released",
                               this.releaseStaleAsyncStubBuild.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }
            return super.getAsyncStub(channel);
        }
    }

    private static class HostCapturingGrpcClient extends AbstractGrpcClient {

        private final AtomicInteger resolutionCount = new AtomicInteger();
        private volatile String capturedHost;

        @Override
        protected ManagedChannel createChannel(String target) {
            return new FakeManagedChannel(target);
        }

        @Override
        public AbstractBlockingStub getBlockingStub(ManagedChannel channel) {
            return new FakeBlockingStub(channel, CallOptions.DEFAULT);
        }

        @Override
        public AbstractAsyncStub getAsyncStub(ManagedChannel channel) {
            return new FakeAsyncStub(channel, CallOptions.DEFAULT);
        }

        @Override
        protected InetAddress[] resolveHost(String host) throws UnknownHostException {
            this.resolutionCount.incrementAndGet();
            this.capturedHost = host;
            return new InetAddress[]{InetAddress.getByName("10.0.0.1")};
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
        private final CountDownLatch activeCallsFinished;
        private final CountDownLatch awaitTerminationStarted = new CountDownLatch(1);
        private volatile boolean shutdown;
        private volatile boolean forceShutdown;
        private volatile boolean terminated;

        FakeManagedChannel(String authority) {
            this(authority, null);
        }

        FakeManagedChannel(String authority, CountDownLatch activeCallsFinished) {
            this.authority = authority;
            this.activeCallsFinished = activeCallsFinished;
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
            if (this.activeCallsFinished == null) {
                this.terminated = true;
            }
            return this;
        }

        @Override
        public ManagedChannel shutdownNow() {
            this.shutdown = true;
            this.forceShutdown = true;
            this.terminated = true;
            return this;
        }

        @Override
        public boolean isShutdown() {
            return this.shutdown;
        }

        boolean isForceShutdown() {
            return this.forceShutdown;
        }

        @Override
        public boolean isTerminated() {
            return this.terminated;
        }

        boolean awaitTerminationStarted(long timeout, TimeUnit unit)
                throws InterruptedException {
            return this.awaitTerminationStarted.await(timeout, unit);
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit)
                throws InterruptedException {
            this.awaitTerminationStarted.countDown();
            if (this.terminated) {
                return true;
            }
            if (this.activeCallsFinished == null) {
                this.terminated = this.shutdown;
                return this.terminated;
            }
            if (this.activeCallsFinished.await(timeout, unit)) {
                this.terminated = true;
                return true;
            }
            return false;
        }
    }
}
