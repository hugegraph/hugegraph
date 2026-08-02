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
import static org.junit.Assert.assertSame;
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
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.apache.hugegraph.store.client.query.QueryV2Client;
import org.apache.hugegraph.store.grpc.query.QueryServiceGrpc;
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
 * Verifies that Store address changes replace channels and cached stubs safely, and that the
 * refresh never resolves or creates channels on the thread that asked for a stub.
 */
public class AbstractGrpcClientTest {

    private static final String MAINTENANCE_THREAD_PREFIX = "channel-maintenance";
    private static final AtomicInteger TARGET_SEQ = new AtomicInteger();

    private static String uniqueTarget(String prefix) {
        return prefix + "-" + TARGET_SEQ.incrementAndGet() + ":8500";
    }

    private static boolean belongsToPool(Channel channel, ManagedChannel[] channels) {
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

    /**
     * Refresh runs on the maintenance thread, so a replacement pool becomes visible some time
     * after the address changes rather than on the call that observes the change. Retirement
     * deliberately trails publication, so waiting for both is what marks a refresh complete.
     */
    private static ManagedChannel[] awaitPoolReplacement(RecordingGrpcClient client,
                                                         String target,
                                                         ManagedChannel[] staleChannels)
            throws Exception {
        awaitCondition("refresh must publish a replacement pool",
                       () -> client.getChannels(target) != staleChannels);
        awaitCondition("refresh must retire the previous pool after publishing",
                       () -> allChannelsAreShutdown(staleChannels));
        return client.getChannels(target);
    }

    @SuppressWarnings("unchecked")
    private static boolean refreshIsIdle(AbstractGrpcClient client, String target)
            throws Exception {
        Field field = AbstractGrpcClient.class.getDeclaredField("refreshTasks");
        field.setAccessible(true);
        return !((Map<String, ?>) field.get(client)).containsKey(target);
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

    private static ThreadPoolExecutor channelCreationExecutor(AbstractGrpcClient client)
            throws Exception {
        Field field = AbstractGrpcClient.class.getDeclaredField("executor");
        field.setAccessible(true);
        return (ThreadPoolExecutor) field.get(client);
    }

    @Test
    public void testAddressChangeReplacesChannelAndStubPools() throws Exception {
        String target = uniqueTarget("address-change");
        RecordingGrpcClient client = new RecordingGrpcClient();
        ManagedChannel[] oldChannels = client.getChannels(target);
        assertNotNull(client.getBlockingStub(target));
        assertNotNull(client.getAsyncStub(target));

        client.resolvedTarget = "10.0.0.2";
        ManagedChannel[] newChannels = awaitPoolReplacement(client, target, oldChannels);
        assertTrue("every stale channel must be gracefully shut down",
                   allChannelsAreShutdown(oldChannels));
        assertFalse("refresh must not force close stale channels immediately",
                    fakeChannels(oldChannels).stream()
                                             .anyMatch(FakeManagedChannel::isForceShutdown));

        client.blockingStubChannels.clear();
        client.asyncStubChannels.clear();
        assertNotNull(client.getBlockingStub(target));
        assertNotNull(client.getAsyncStub(target));
        assertCachedChannelsCurrentAndLive("the blocking stub pool must be rebuilt on the new pool",
                                           client.blockingStubChannels, newChannels);
        assertUsesEveryChannel("blocking stubs must be spread across the pool",
                               client.blockingStubChannels, newChannels);
        assertCachedChannelsCurrentAndLive("the async stub pool must be rebuilt on the new pool",
                                           client.asyncStubChannels, newChannels);
        assertUsesEveryChannel("async stubs must be spread across the pool",
                               client.asyncStubChannels, newChannels);
    }

    @Test
    public void testStubPoolsCoverEveryChannelOnFirstBuild() {
        String target = uniqueTarget("initial-stub-spread");
        RecordingGrpcClient client = new RecordingGrpcClient();
        ManagedChannel[] channels = client.getChannels(target);

        assertNotNull(client.getBlockingStub(target));
        assertNotNull(client.getAsyncStub(target));
        assertUsesEveryChannel("the first blocking stub pool must cover every channel",
                               client.blockingStubChannels, channels);
        assertUsesEveryChannel("the first async stub pool must cover every channel",
                               client.asyncStubChannels, channels);
    }

    @Test
    public void testFirstPoolIsBuiltAfterItsAddressIsKnown() throws Exception {
        String target = uniqueTarget("initial-resolution");
        RecordingGrpcClient client = new RecordingGrpcClient();
        ManagedChannel[] initialChannels = client.getChannels(target);

        assertEquals("the first pool must be built once its address is known",
                     1, client.resolutionCount.get());
        // Let every refresh settle first, otherwise the assertions below race the swap.
        awaitCondition("the refresh must settle", () -> refreshIsIdle(client, target));
        assertSame("a pool built with a known address must not be replaced",
                   initialChannels, client.getChannels(target));
        awaitCondition("the settled refresh must leave no further work",
                       () -> refreshIsIdle(client, target));
        assertTrue("the first pool must not be retired by its own resolution",
                   allChannelsAreLive(initialChannels));
    }

    @Test
    public void testUnknownAddressPoolIsReplacedOnFirstSuccessfulResolution() throws Exception {
        String target = uniqueTarget("first-successful-resolution");
        RecordingGrpcClient client = new RecordingGrpcClient();
        client.resolvedTarget = "";
        ManagedChannel[] unknownChannels = client.getChannels(target);

        client.resolvedTarget = "10.0.0.1";
        ManagedChannel[] resolvedChannels = awaitPoolReplacement(client, target, unknownChannels);
        assertTrue("every channel from the unknown pool must be gracefully shut down",
                   allChannelsAreShutdown(unknownChannels));
        assertFalse("unknown channels must not be force closed immediately",
                    fakeChannels(unknownChannels).stream()
                                                 .anyMatch(FakeManagedChannel::isForceShutdown));
        assertTrue("the resolved channel pool must remain live",
                   allChannelsAreLive(resolvedChannels));
    }

    /**
     * HugeSecurityManager denies socket connection and thread creation on Gremlin worker stacks,
     * and InetAddress.getAllByName() performs exactly the checkConnect(host, -1) simulated here.
     * A refresh triggered by such a caller must therefore resolve somewhere else entirely.
     */
    @Test
    public void testRefreshSucceedsWhenTheCallerThreadIsDeniedSocketAccess() throws Exception {
        String target = uniqueTarget("denied-caller");
        HostCapturingGrpcClient client = new HostCapturingGrpcClient();
        client.checkSocketPermission = true;
        ManagedChannel[] oldChannels = client.getChannels(target);
        assertNotNull(client.getBlockingStub(target));

        SecurityManager previous = System.getSecurityManager();
        System.setSecurityManager(new DenyingWorkerSecurityManager());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<AbstractBlockingStub> stub = new AtomicReference<>();
        try {
            client.resolvedAddress = "10.0.0.2";
            // The name is what HugeSecurityManager keys its Gremlin worker check on.
            Thread worker = new Thread(() -> {
                try {
                    stub.set(client.getBlockingStub(target));
                } catch (Throwable e) {
                    failure.set(e);
                }
            }, "gremlin-server-exec-1");
            worker.start();
            worker.join(TimeUnit.SECONDS.toMillis(10L));

            assertFalse("the denied caller must finish", worker.isAlive());
            assertNotNull("a denied caller must still receive a stub", stub.get());
            assertTrue("a denied caller must not observe a security failure: " + failure.get(),
                       failure.get() == null);
            awaitCondition("the refresh must still publish a replacement pool",
                           () -> client.getChannels(target) != oldChannels);
            assertFalse("the assertion below is vacuous unless something resolved",
                        client.resolutionThreads.isEmpty());
            assertTrue("every resolution must run on the channel maintenance thread",
                       client.resolutionThreads.stream()
                                               .allMatch(name -> name.startsWith(
                                                       MAINTENANCE_THREAD_PREFIX)));
        } finally {
            System.setSecurityManager(previous);
        }
    }

    @Test
    public void testRetirementDoesNotBlockWhenCreationExecutorIsSaturated() throws Exception {
        String target = uniqueTarget("saturated-retirement");
        RecordingGrpcClient client = new RecordingGrpcClient();
        client.activeCallsFinished = new CountDownLatch(1);
        client.drainTimeoutNanos = TimeUnit.SECONDS.toNanos(5L);
        ManagedChannel[] oldChannels = client.getChannels(target);
        ThreadPoolExecutor channelExecutor = channelCreationExecutor(client);
        CountDownLatch workersStarted = new CountDownLatch(AbstractGrpcClient.concurrency);
        CountDownLatch releaseWorkers = new CountDownLatch(1);

        try {
            for (int i = 0; i < AbstractGrpcClient.concurrency; i++) {
                channelExecutor.execute(() -> {
                    workersStarted.countDown();
                    try {
                        releaseWorkers.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            assertTrue("the shared creation executor must be fully occupied",
                       workersStarted.await(5, TimeUnit.SECONDS));

            client.resolvedTarget = "10.0.0.2";
            ManagedChannel[] newChannels = awaitPoolReplacement(client, target, oldChannels);

            assertNotSame("refresh must publish the replacement pool", oldChannels, newChannels);
            assertTrue("the retired pool must be shut down once refresh completes",
                       allChannelsAreShutdown(oldChannels));
            assertFalse("the scheduled cleanup must preserve the drain window",
                        fakeChannels(oldChannels).stream()
                                                 .anyMatch(FakeManagedChannel::isForceShutdown));
        } finally {
            releaseWorkers.countDown();
            client.activeCallsFinished.countDown();
        }
    }

    @Test
    public void testPartialChannelsAreRetiredAfterMixedCreationFailure() {
        String target = uniqueTarget("partial-creation-failure");
        CreationControlGrpcClient client = new CreationControlGrpcClient();
        client.failedAttempt = 5;

        try {
            client.getChannels(target);
            assertTrue("channel creation must propagate the injected failure", false);
        } catch (RuntimeException ignored) {
            // Expected.
        }

        assertEquals("all creation tasks must converge before failure is returned",
                     AbstractGrpcClient.concurrency - 1, client.createdChannels.size());
        assertTrue("every partial channel must be force terminated before failure returns",
                   client.createdChannels.stream().allMatch(channel ->
                           channel.isTerminated() &&
                           ((FakeManagedChannel) channel).isForceShutdown()));
    }

    @Test
    public void testInterruptedCreationWaitsAndRetiresPartialChannels() throws Exception {
        String target = uniqueTarget("interrupted-creation");
        CreationControlGrpcClient client = new CreationControlGrpcClient();
        client.releaseCreation = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread caller = new Thread(() -> {
            try {
                client.getChannels(target);
            } catch (Throwable e) {
                failure.set(e);
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });

        caller.start();
        assertTrue("all channel creation tasks must start",
                   client.creationStarted.await(5, TimeUnit.SECONDS));
        caller.interrupt();
        try {
            Thread.sleep(100L);
            assertTrue("an interrupted caller must wait for creation tasks to converge",
                       caller.isAlive());
        } finally {
            client.releaseCreation.countDown();
        }
        caller.join(TimeUnit.SECONDS.toMillis(5L));

        assertFalse("the interrupted creation call must finish", caller.isAlive());
        assertTrue("interruption must be reported as a runtime failure",
                   failure.get() instanceof RuntimeException);
        assertTrue("the caller interrupt status must be restored", interrupted.get());
        assertEquals("every creation task must finish before interruption is reported",
                     AbstractGrpcClient.concurrency, client.createdChannels.size());
        assertTrue("all partial channels must be force terminated before interruption returns",
                   client.createdChannels.stream().allMatch(channel ->
                           channel.isTerminated() &&
                           ((FakeManagedChannel) channel).isForceShutdown()));
    }

    @Test
    public void testDrainDeadlineForceTerminatesRetiredChannels() throws Exception {
        String target = uniqueTarget("drain-deadline");
        RecordingGrpcClient client = new RecordingGrpcClient();
        client.activeCallsFinished = new CountDownLatch(1);
        client.drainTimeoutNanos = 0L;
        ManagedChannel[] oldChannels = client.getChannels(target);

        client.resolvedTarget = "10.0.0.2";
        ManagedChannel[] newChannels = awaitPoolReplacement(client, target, oldChannels);

        awaitCondition("expired drain deadline must force terminate the retired pool",
                       () -> fakeChannels(oldChannels).stream().allMatch(channel ->
                               channel.isTerminated() && channel.isForceShutdown()));
        assertTrue("the replacement pool must remain live", allChannelsAreLive(newChannels));
    }

    @Test
    public void testStubAcquisitionReusesResolutionWithinRefreshInterval() throws Exception {
        String target = uniqueTarget("throttled-refresh");
        RecordingGrpcClient client = new RecordingGrpcClient();
        client.refreshIntervalNanos = TimeUnit.HOURS.toNanos(1L);

        assertNotNull(client.getBlockingStub(target));
        assertNotNull(client.getAsyncStub(target));
        for (int i = 0; i < 10; i++) {
            assertNotNull(client.getBlockingStub(target));
            assertNotNull(client.getAsyncStub(target));
        }

        Thread.sleep(100L);
        assertEquals("stub acquisition must not resolve again inside the refresh interval",
                     1, client.resolutionCount.get());
    }

    @Test
    public void testConcurrentStubAcquisitionRetainsHealthyPoolDuringDelayedRefresh()
            throws Exception {
        String target = uniqueTarget("delayed-refresh");
        RecordingGrpcClient client = new RecordingGrpcClient();
        ManagedChannel[] oldChannels = client.getChannels(target);
        assertNotNull(client.getBlockingStub(target));
        // Let any refresh already in flight settle, so the resolution count below is stable.
        awaitCondition("the initial refresh must settle before the count is captured",
                       () -> refreshIsIdle(client, target));
        int resolutionsBeforeConcurrentCalls = client.resolutionCount.get();

        client.resolvedTarget = "10.0.0.2";
        client.delayResolution = true;
        client.refreshIntervalNanos = TimeUnit.HOURS.toNanos(1L);

        ExecutorService executor = Executors.newFixedThreadPool(6);
        List<Future<AbstractBlockingStub>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 6; i++) {
                futures.add(executor.submit(() -> client.getBlockingStub(target)));
            }
            assertTrue("one refresh should be waiting in the delayed resolver",
                       client.delayedResolutionStarted.await(5, TimeUnit.SECONDS));
            for (Future<AbstractBlockingStub> future : futures) {
                assertNotNull("no caller may block on the delayed refresh",
                              future.get(5, TimeUnit.SECONDS));
            }
            assertTrue("the existing healthy pool must stay live during refresh",
                       allChannelsAreLive(oldChannels));

            client.releaseDelayedResolution.countDown();
            ManagedChannel[] currentChannels =
                    awaitPoolReplacement(client, target, oldChannels);
            assertTrue("the previous pool must be retired after replacement is published",
                       allChannelsAreShutdown(oldChannels));
            assertTrue("the replacement pool must be live", allChannelsAreLive(currentChannels));
            assertEquals("concurrent callers must share a single refresh resolution",
                         resolutionsBeforeConcurrentCalls + 1, client.resolutionCount.get());
        } finally {
            client.releaseDelayedResolution.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void testRefreshGracefullyRetiresActiveStreamChannels() throws Exception {
        String target = uniqueTarget("active-stream-refresh");
        RecordingGrpcClient client = new RecordingGrpcClient();
        client.activeCallsFinished = new CountDownLatch(1);
        client.drainTimeoutNanos = TimeUnit.SECONDS.toNanos(5L);
        ManagedChannel[] oldChannels = client.getChannels(target);
        AbstractAsyncStub activeStreamStub = client.getAsyncStub(target);
        assertTrue("the simulated active stream must be on the old pool",
                   belongsToPool(activeStreamStub.getChannel(), oldChannels));

        client.resolvedTarget = "10.0.0.2";
        ManagedChannel[] newChannels = awaitPoolReplacement(client, target, oldChannels);
        List<FakeManagedChannel> retiredChannels = fakeChannels(oldChannels);
        assertTrue("the retired pool must receive graceful shutdown",
                   retiredChannels.stream().allMatch(FakeManagedChannel::isShutdown));
        assertFalse("active streams must not be force closed immediately",
                    retiredChannels.stream().anyMatch(FakeManagedChannel::isForceShutdown));
        client.activeCallsFinished.countDown();
        awaitCondition("retired channels should terminate after active calls finish",
                       () -> retiredChannels.stream().allMatch(FakeManagedChannel::isTerminated));
        assertFalse("drained channels must not need forced shutdown",
                    retiredChannels.stream().anyMatch(FakeManagedChannel::isForceShutdown));
        assertTrue("the replacement pool must remain live", allChannelsAreLive(newChannels));
    }

    /**
     * Holds a stub pool build open, refreshes the pool underneath it, and asserts that both the
     * interleaved build and a concurrent one return stubs bound to the published pool. Blocking
     * and asynchronous acquisition share one implementation, so the asynchronous path stands in
     * for both; it is the one that also publishes a stub cache worth asserting on.
     */
    @Test
    public void testStubBuildRetriesAfterChannelRefresh() throws Exception {
        String target = uniqueTarget("concurrent-stub-refresh");
        StubInterleavingGrpcClient client = new StubInterleavingGrpcClient();
        ManagedChannel[] oldChannels = client.getChannels(target);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<AbstractAsyncStub> staleStub =
                    executor.submit(() -> client.getAsyncStub(target));
            assertTrue("the old stub pool build must be in flight",
                       client.staleStubBuildStarted.await(5, TimeUnit.SECONDS));
            client.resolvedTarget = "10.0.0.2";
            Future<AbstractAsyncStub> freshStub =
                    executor.submit(() -> client.getAsyncStub(target));
            awaitCondition("refresh must retire the old channel pool",
                           () -> allChannelsAreShutdown(oldChannels));
            client.releaseStaleStubBuild.countDown();

            ManagedChannel[] currentChannels = client.getChannels(target);
            assertTrue("the stale build must retry against the current pool",
                       belongsToPool(staleStub.get(5, TimeUnit.SECONDS).getChannel(),
                                     currentChannels));
            assertTrue("the concurrent build must use the current pool",
                       belongsToPool(freshStub.get(5, TimeUnit.SECONDS).getChannel(),
                                     currentChannels));
            assertTrue("the current channel pool must remain live",
                       allChannelsAreLive(currentChannels));
            assertCachedChannelsCurrentAndLive(
                    "the final stub cache must only reference current live channels",
                    cachedAsyncStubChannels(client, target), currentChannels);
        } finally {
            client.releaseStaleStubBuild.countDown();
            executor.shutdownNow();
        }
    }

    /**
     * QueryV2 used to take a channel straight from the pool and build its stub afterwards, which
     * let a refresh retire that channel in between. It must now go through the guarded path.
     */
    @Test
    public void testQueryV2StubFollowsPublishedPoolAcrossRefresh() throws Exception {
        String target = uniqueTarget("query-v2-refresh");
        QueryV2TestClient client = new QueryV2TestClient();
        ManagedChannel[] oldChannels = client.getChannels(target);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            /*
             * Interleave a refresh with the stub build. Taking a channel from the pool and
             * building the stub afterwards would bind it to a channel retired in between.
             */
            Future<QueryServiceGrpc.QueryServiceStub> stub =
                    executor.submit(() -> client.getQueryServiceStub(target));
            assertTrue("the QueryV2 stub build must be in flight",
                       client.stubBuildStarted.await(5, TimeUnit.SECONDS));
            client.resolvedTarget = "10.0.0.2";
            // getChannels is what triggers a refresh, and the blocked build cannot call it.
            awaitCondition("refresh must retire the old channel pool",
                           () -> client.getChannels(target) != oldChannels &&
                                 allChannelsAreShutdown(oldChannels));
            client.releaseStubBuild.countDown();

            ManagedChannel[] newChannels = client.getChannels(target);
            Channel channel = stub.get(5, TimeUnit.SECONDS).getChannel();
            assertTrue("QueryV2 must never return a stub bound to a retired channel",
                       belongsToPool(channel, newChannels));
            assertFalse("QueryV2 must never return a stub on a shut down channel",
                        ((ManagedChannel) channel).isShutdown());

            List<ManagedChannel> stubChannels = new ArrayList<>();
            for (int i = 0; i < AbstractGrpcClient.concurrency; i++) {
                stubChannels.add((ManagedChannel) client.getQueryServiceStub(target).getChannel());
            }
            assertCachedChannelsCurrentAndLive("QueryV2 stubs must stay on the published pool",
                                               stubChannels, newChannels);
            assertUsesEveryChannel("QueryV2 stubs must still spread across the pool",
                                   stubChannels, newChannels);
        } finally {
            client.releaseStubBuild.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void testResolveTargetSupportsDnsUriAndBracketedIpv6Targets() {
        HostCapturingGrpcClient client = new HostCapturingGrpcClient();
        assertEquals("10.0.0.1", client.resolveTarget("store.example.com:8500"));
        assertEquals("store.example.com", client.capturedHost);

        assertEquals("10.0.0.1", client.resolveTarget("dns:///store.example.com:8500"));
        assertEquals("store.example.com", client.capturedHost);

        // The scheme-only spelling is a legal gRPC dns target too.
        assertEquals("10.0.0.1", client.resolveTarget("dns:store.example.com:8500"));
        assertEquals("store.example.com", client.capturedHost);

        assertEquals("10.0.0.1", client.resolveTarget("dns://8.8.8.8/store.example.com:8500"));
        assertEquals("store.example.com", client.capturedHost);

        assertEquals("10.0.0.1", client.resolveTarget("[2001:db8::1]:8500"));
        assertEquals("2001:db8::1", client.capturedHost);
    }

    @Test
    public void testResolveTargetSkipsUnsupportedGrpcSchemes() {
        HostCapturingGrpcClient client = new HostCapturingGrpcClient();
        assertEquals("", client.resolveTarget("unix:///var/run/store.sock"));
        // The single-slash spelling is legal too, and must not resolve the literal host "unix".
        assertEquals("", client.resolveTarget("unix:/var/run/store.sock"));
        assertEquals("", client.resolveTarget("xds:///store.example.com"));
        assertEquals("unsupported schemes must not invoke DNS resolution",
                     0, client.hostResolutionCount.get());
    }

    private interface Condition {

        boolean isTrue() throws Exception;
    }

    private static class RecordingGrpcClient extends AbstractGrpcClient {

        private final AtomicInteger channelSeq = new AtomicInteger();
        protected final AtomicInteger resolutionCount = new AtomicInteger();
        protected final List<String> resolutionThreads =
                Collections.synchronizedList(new ArrayList<>());
        protected final CountDownLatch delayedResolutionStarted = new CountDownLatch(1);
        protected final CountDownLatch releaseDelayedResolution = new CountDownLatch(1);
        protected volatile String resolvedTarget = "10.0.0.1";
        protected volatile long refreshIntervalNanos = 0L;
        protected volatile boolean delayResolution;
        /** Resolves through the real implementation instead of returning resolvedTarget. */
        protected volatile boolean useRealResolution;
        /** Null keeps the inherited drain deadline. */
        protected volatile Long drainTimeoutNanos;
        /** Null makes channels terminate as soon as they are shut down. */
        protected volatile CountDownLatch activeCallsFinished;
        protected final List<ManagedChannel> blockingStubChannels =
                Collections.synchronizedList(new ArrayList<>());
        protected final List<ManagedChannel> asyncStubChannels =
                Collections.synchronizedList(new ArrayList<>());

        @Override
        protected long channelRefreshIntervalNanos() {
            return this.refreshIntervalNanos;
        }

        @Override
        protected long channelDrainTimeoutNanos() {
            Long timeout = this.drainTimeoutNanos;
            return timeout == null ? super.channelDrainTimeoutNanos() : timeout;
        }

        @Override
        protected ManagedChannel createChannel(String target) {
            return new FakeManagedChannel(target + "#" + this.channelSeq.getAndIncrement(),
                                          this.activeCallsFinished);
        }

        @Override
        protected String resolveTarget(String target) {
            this.resolutionCount.incrementAndGet();
            this.resolutionThreads.add(Thread.currentThread().getName());
            if (this.delayResolution) {
                this.delayedResolutionStarted.countDown();
                try {
                    assertTrue("the delayed resolution must be released",
                               this.releaseDelayedResolution.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }
            return this.useRealResolution ? super.resolveTarget(target) : this.resolvedTarget;
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

    private static class CreationControlGrpcClient extends RecordingGrpcClient {

        private final AtomicInteger attempt = new AtomicInteger();
        private final CountDownLatch creationStarted =
                new CountDownLatch(AbstractGrpcClient.concurrency);
        private final List<ManagedChannel> createdChannels =
                Collections.synchronizedList(new ArrayList<>());
        /** Negative never fails. */
        private volatile int failedAttempt = -1;
        /** Null creates channels without delay. */
        private volatile CountDownLatch releaseCreation;

        @Override
        protected ManagedChannel createChannel(String target) {
            int current = this.attempt.getAndIncrement();
            this.creationStarted.countDown();
            if (current == this.failedAttempt) {
                throw new IllegalStateException("injected channel creation failure");
            }
            CountDownLatch release = this.releaseCreation;
            if (release != null) {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }
            ManagedChannel channel = new FakeManagedChannel(target + "#" + current);
            this.createdChannels.add(channel);
            return channel;
        }
    }

    private static class StubInterleavingGrpcClient extends RecordingGrpcClient {

        private final AtomicInteger stubSeq = new AtomicInteger();
        private final CountDownLatch staleStubBuildStarted = new CountDownLatch(1);
        private final CountDownLatch releaseStaleStubBuild = new CountDownLatch(1);

        @Override
        public AbstractAsyncStub getAsyncStub(ManagedChannel channel) {
            if (this.stubSeq.incrementAndGet() == 1) {
                this.staleStubBuildStarted.countDown();
                try {
                    assertTrue("the stale stub build must be released",
                               this.releaseStaleStubBuild.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }
            return super.getAsyncStub(channel);
        }
    }

    private static class QueryV2TestClient extends QueryV2Client {

        private final AtomicInteger channelSeq = new AtomicInteger();
        private final AtomicInteger stubSeq = new AtomicInteger();
        private final CountDownLatch stubBuildStarted = new CountDownLatch(1);
        private final CountDownLatch releaseStubBuild = new CountDownLatch(1);
        private volatile String resolvedTarget = "10.0.0.1";

        @Override
        protected long channelRefreshIntervalNanos() {
            return 0L;
        }

        @Override
        public AbstractAsyncStub<?> getAsyncStub(ManagedChannel channel) {
            if (this.stubSeq.incrementAndGet() == 1) {
                this.stubBuildStarted.countDown();
                try {
                    assertTrue("the QueryV2 stub build must be released",
                               this.releaseStubBuild.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }
            return super.getAsyncStub(channel);
        }

        @Override
        protected ManagedChannel createChannel(String target) {
            return new FakeManagedChannel(target + "#" + this.channelSeq.getAndIncrement());
        }

        @Override
        protected String resolveTarget(String target) {
            return this.resolvedTarget;
        }
    }

    /**
     * Resolves through the inherited implementation, optionally reproducing the security check
     * that InetAddress.getAllByName() performs on whichever thread resolution runs on.
     */
    private static class HostCapturingGrpcClient extends RecordingGrpcClient {

        private final AtomicInteger hostResolutionCount = new AtomicInteger();
        private volatile String capturedHost;
        private volatile String resolvedAddress = "10.0.0.1";
        private volatile boolean checkSocketPermission;

        HostCapturingGrpcClient() {
            this.useRealResolution = true;
        }

        @Override
        protected InetAddress[] resolveHost(String host) throws UnknownHostException {
            SecurityManager security = System.getSecurityManager();
            if (this.checkSocketPermission && security != null) {
                security.checkConnect(host, -1);
            }
            this.hostResolutionCount.incrementAndGet();
            this.capturedHost = host;
            return new InetAddress[]{InetAddress.getByName(this.resolvedAddress)};
        }
    }

    /**
     * Denies a deliberately narrow subset of what HugeSecurityManager denies on a Gremlin worker
     * stack: the two checks this fix is about, opening sockets and creating threads. Everything
     * else stays permitted so the test JVM keeps working, including restoring the previous
     * manager. Keys on the thread name alone, where HugeSecurityManager also requires a Gremlin
     * script engine frame on the stack. Relies on System.setSecurityManager, which is a no-op
     * from JDK 18 and removed in JDK 24; this module builds and runs on Java 11.
     */
    private static class DenyingWorkerSecurityManager extends SecurityManager {

        private static boolean isDeniedWorker() {
            return Thread.currentThread().getName().startsWith("gremlin-server-exec");
        }

        @Override
        public void checkConnect(String host, int port) {
            if (isDeniedWorker()) {
                throw new SecurityException("Not allowed to connect socket via Gremlin");
            }
        }

        @Override
        public void checkAccess(ThreadGroup threadGroup) {
            if (isDeniedWorker()) {
                throw new SecurityException("Not allowed to access thread group via Gremlin");
            }
        }

        @Override
        public void checkPermission(java.security.Permission permission) {
            // Everything else stays permitted, including restoring the previous manager.
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
            if (this.shutdown && this.activeCallsFinished != null &&
                this.activeCallsFinished.getCount() == 0L) {
                this.terminated = true;
            }
            return this.terminated;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return this.isTerminated();
        }
    }
}
