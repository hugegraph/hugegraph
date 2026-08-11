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

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.apache.hugegraph.store.client.util.ExecutorPool;
import org.apache.hugegraph.store.client.util.HgStoreClientConfig;
import org.apache.hugegraph.store.term.HgPair;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.AbstractAsyncStub;
import io.grpc.stub.AbstractBlockingStub;
import io.grpc.stub.AbstractStub;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class AbstractGrpcClient {

    protected static Map<String, ManagedChannel[]> channels = new ConcurrentHashMap<>();
    private static final Map<String, String> resolvedTargets = new ConcurrentHashMap<>();
    // A null deadline is the explicit "never scheduled" state; every long is a valid clock value.
    private static final Map<String, AtomicReference<Long>> nextResolutions =
            new ConcurrentHashMap<>();
    private static final Map<String, CompletableFuture<Void>> refreshTasks =
            new ConcurrentHashMap<>();
    /*
     * Refresh runs here rather than on a request thread: a caller of getChannels() may hold a
     * Gremlin worker stack, which HugeSecurityManager denies socket access to. Creating the very
     * first pool for a target is still done by the caller, so that path stays exposed.
     */
    private static final ScheduledThreadPoolExecutor CHANNEL_MAINTENANCE_EXECUTOR =
            new ScheduledThreadPoolExecutor(
                    2, ExecutorPool.newThreadFactory("channel-maintenance"));
    private static final ScheduledThreadPoolExecutor CHANNEL_RETIREMENT_EXECUTOR =
            new ScheduledThreadPoolExecutor(
                    1, ExecutorPool.newThreadFactory("channel-retirement"));
    private static final long DEFAULT_CHANNEL_REFRESH_INTERVAL_NANOS =
            TimeUnit.SECONDS.toNanos(5L);
    private static final long DEFAULT_INITIAL_RESOLUTION_TIMEOUT_NANOS =
            TimeUnit.SECONDS.toNanos(1L);
    private static final String DNS_SCHEME = "dns:";

    static {
        prestartExecutor(CHANNEL_MAINTENANCE_EXECUTOR, "channel maintenance");
        prestartExecutor(CHANNEL_RETIREMENT_EXECUTOR, "channel retirement");
    }

    private static void prestartExecutor(ScheduledThreadPoolExecutor executor,
                                         String executorName) {
        try {
            // Create maintenance threads eagerly, so no request thread ever creates one.
            executor.prestartAllCoreThreads();
        } catch (Throwable e) {
            // A denied prestart must not leave this class permanently uninitializable, but a
            // request thread has to create the thread instead, where it may be denied again.
            log.warn("Failed to start the {} threads eagerly; work may be delayed until a " +
                     "permitted thread submits it", executorName, e);
        }
    }

    private static final int n = 5;
    protected static int concurrency = 1 << n;
    private static final AtomicLong counter = new AtomicLong(0);
    private static final long limit = Long.MAX_VALUE >> 1;
    protected static final HgStoreClientConfig config = HgStoreClientConfig.of();
    private final Map<String, HgPair<ManagedChannel, AbstractBlockingStub>[]> blockingStubs =
            new ConcurrentHashMap<>();
    private final Map<String, HgPair<ManagedChannel, AbstractAsyncStub>[]> asyncStubs =
            new ConcurrentHashMap<>();
    private final ThreadPoolExecutor executor;

    {
        executor = ExecutorPool.createExecutor("common", 60, concurrency, concurrency);
    }

    public AbstractGrpcClient() {

    }

    public ManagedChannel[] getChannels(String target) {
        CompletableFuture<Void> refresh = this.triggerChannelRefresh(target);
        ManagedChannel[] tc = channels.get(target);
        if (tc != null) {
            return tc;
        }

        /*
         * Only the very first pool for a target waits, and only for a bounded time: building it
         * before its address is known makes the resolution that lands next rebuild it. Waiting
         * avoids that in the common case; if the wait expires the rebuild still happens.
         */
        this.awaitInitialResolution(refresh);
        synchronized (channels) {
            if ((tc = channels.get(target)) == null) {
                channels.put(target, tc = this.createChannels(target));
            }
        }
        return tc;
    }

    public abstract AbstractBlockingStub getBlockingStub(ManagedChannel channel);

    public AbstractBlockingStub getBlockingStub(String target) {
        return this.acquireStub(target, this.blockingStubs, this::getBlockingStub,
                                stub -> (AbstractBlockingStub) this.setBlockingStubOption(stub));
    }

    /**
     * Returns a cached stub bound to a channel of the target's current pool, rebuilding the
     * cache when the pool has been replaced. The stub comes from the pool that was published at
     * the last check; a refresh landing immediately afterwards can still retire that pool, so
     * callers are not shielded from an in-flight replacement.
     *
     * <p>The pool check needs no lock: the pool is published before the previous one is retired,
     * so reading the current pool from the map is enough to know retirement has not started.
     */
    @SuppressWarnings("unchecked")
    private <S> S acquireStub(String target,
                              Map<String, HgPair<ManagedChannel, S>[]> stubCache,
                              Function<ManagedChannel, S> stubFactory,
                              Function<S, S> stubOption) {
        while (true) {
            ManagedChannel[] targetChannels = this.getChannels(target);
            HgPair<ManagedChannel, S>[] pairs = stubCache.get(target);
            int index = nextStubIndex();
            if (!usesChannels(pairs, targetChannels)) {
                synchronized (stubCache) {
                    pairs = stubCache.get(target);
                    if (!usesChannels(pairs, targetChannels)) {
                        HgPair<ManagedChannel, S>[] value = new HgPair[concurrency];
                        IntStream.range(0, concurrency).forEach(i -> {
                            ManagedChannel channel = targetChannels[i];
                            value[i] = new HgPair<>(channel, stubFactory.apply(channel));
                        });
                        S configuredStub = stubOption.apply(value[index].getValue());
                        if (channels.get(target) != targetChannels) {
                            continue;
                        }
                        stubCache.put(target, value);
                        return configuredStub;
                    }
                }
            }
            S configuredStub = stubOption.apply(pairs[index].getValue());
            if (channels.get(target) != targetChannels) {
                continue;
            }
            return configuredStub;
        }
    }

    private static int nextStubIndex() {
        long l = counter.getAndIncrement();
        if (l >= limit) {
            counter.set(0);
        }
        return (int) (l & (concurrency - 1));
    }

    private AbstractStub setBlockingStubOption(AbstractBlockingStub stub) {
        return stub.withDeadlineAfter(config.getGrpcTimeoutSeconds(), TimeUnit.SECONDS)
                   .withMaxInboundMessageSize(
                           config.getGrpcMaxInboundMessageSize())
                   .withMaxOutboundMessageSize(
                           config.getGrpcMaxOutboundMessageSize());
    }

    public AbstractAsyncStub getAsyncStub(ManagedChannel channel) {
        return null;
    }

    public AbstractAsyncStub getAsyncStub(String target) {
        return this.acquireStub(target, this.asyncStubs, this::getAsyncStub,
                                stub -> (AbstractAsyncStub) this.setStubOption(stub));
    }

    protected AbstractStub setStubOption(AbstractStub value) {
        return value.withMaxInboundMessageSize(
                            config.getGrpcMaxInboundMessageSize())
                    .withMaxOutboundMessageSize(
                            config.getGrpcMaxOutboundMessageSize());
    }

    private static boolean usesChannels(HgPair<ManagedChannel, ?>[] pairs,
                                        ManagedChannel[] channels) {
        if (pairs == null || pairs.length != channels.length) {
            return false;
        }
        for (int i = 0; i < pairs.length; i++) {
            HgPair<ManagedChannel, ?> pair = pairs[i];
            if (pair == null || pair.getKey() != channels[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Submits a refresh for the target unless one is already in flight or the refresh interval
     * has not elapsed. Returns the in-flight refresh, or null when none is running.
     */
    private CompletableFuture<Void> triggerChannelRefresh(String target) {
        CompletableFuture<Void> inFlight = refreshTasks.get(target);
        if (inFlight != null) {
            return inFlight;
        }
        if (!this.shouldRefreshChannels(target)) {
            return null;
        }

        CompletableFuture<Void> refresh = new CompletableFuture<>();
        CompletableFuture<Void> running = refreshTasks.putIfAbsent(target, refresh);
        if (running != null) {
            return running;
        }

        // Throttle before submitting, so that a failing resolver cannot be retried in a loop.
        this.postponeNextRefresh(target);
        try {
            this.submitChannelRefresh(() -> {
                try {
                    this.refreshChannelsIfAddressChanged(target);
                } catch (Throwable e) {
                    // The executor discards what a task throws, so report it here.
                    log.warn("Failed to refresh channels of target {}", target, e);
                } finally {
                    this.completeRefresh(target, refresh);
                }
            });
        } catch (Throwable e) {
            // Includes a thread creation denied on this thread; never leave the entry behind.
            log.warn("Failed to submit a channel refresh for target {}", target, e);
            this.completeRefresh(target, refresh);
        }
        return refresh;
    }

    private void completeRefresh(String target, CompletableFuture<Void> refresh) {
        /*
         * Throttle from completion as well as from submission: a resolver that is slow rather
         * than failing can outlast its own interval, which would let every later call queue
         * another lookup behind it.
         */
        this.postponeNextRefresh(target);
        refreshTasks.remove(target, refresh);
        refresh.complete(null);
    }

    void submitChannelRefresh(Runnable task) {
        CHANNEL_MAINTENANCE_EXECUTOR.execute(task);
    }

    private void awaitInitialResolution(CompletableFuture<Void> refresh) {
        if (refresh == null) {
            return;
        }
        try {
            refresh.get(Math.max(0L, this.initialResolutionTimeoutNanos()),
                        TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // A slow or failing resolver must not delay the first pool any further.
        }
    }

    /**
     * Runs on a maintenance thread, never on a request thread. At most one runs per target at a
     * time — that comes from the refreshTasks entry, not from the size of the executor. Replaces
     * the target's pool when its resolved address set has changed, publishing the replacement
     * before retiring the previous pool.
     */
    private void refreshChannelsIfAddressChanged(String target) {
        String resolvedTarget = this.resolveTarget(target);
        if (resolvedTarget.isEmpty()) {
            return;
        }

        ManagedChannel[] staleChannels = channels.get(target);
        String previousTarget = resolvedTargets.get(target);
        if (resolvedTarget.equals(previousTarget)) {
            return;
        }
        if (staleChannels == null) {
            /*
             * Nothing to replace yet. Recording the address here is what lets the common path
             * build its first pool already knowing the address, instead of rebuilding it.
             */
            resolvedTargets.put(target, resolvedTarget);
            return;
        }

        ManagedChannel[] replacementChannels;
        try {
            replacementChannels = this.createChannels(target);
        } catch (RuntimeException e) {
            // Keep serving from the last healthy pool.
            log.warn("Failed to create replacement channels of target {}, " +
                     "keeping the current pool", target, e);
            return;
        }

        boolean replaced = false;
        synchronized (channels) {
            if (channels.get(target) == staleChannels) {
                channels.put(target, replacementChannels);
                resolvedTargets.put(target, resolvedTarget);
                replaced = true;
            }
        }
        if (replaced) {
            log.info("Replaced the channel pool of target {}, address changed from {} to {}",
                     target, previousTarget, resolvedTarget);
        }

        this.retireChannels(replaced ? staleChannels : replacementChannels);
    }

    private boolean shouldRefreshChannels(String target) {
        AtomicReference<Long> nextResolution =
                nextResolutions.computeIfAbsent(target, key -> new AtomicReference<>());
        Long deadline = nextResolution.get();
        return deadline == null || this.nanoTime() - deadline >= 0L;
    }

    private void postponeNextRefresh(String target) {
        long interval = Math.max(0L, this.channelRefreshIntervalNanos());
        nextResolutions.computeIfAbsent(target, key -> new AtomicReference<>())
                       .set(this.nanoTime() + interval);
    }

    protected long nanoTime() {
        return System.nanoTime();
    }

    protected long channelRefreshIntervalNanos() {
        return DEFAULT_CHANNEL_REFRESH_INTERVAL_NANOS;
    }

    private long initialResolutionTimeoutNanos() {
        return DEFAULT_INITIAL_RESOLUTION_TIMEOUT_NANOS;
    }

    protected long channelDrainTimeoutNanos() {
        return TimeUnit.SECONDS.toNanos(config.getGrpcTimeoutSeconds());
    }

    private ManagedChannel[] createChannels(String target) {
        ManagedChannel[] value = new ManagedChannel[concurrency];
        CountDownLatch latch = new CountDownLatch(concurrency);
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        for (int i = 0; i < concurrency; i++) {
            int fi = i;
            try {
                this.submitChannelCreation(() -> {
                    try {
                        value[fi] = createChannel(target);
                    } catch (Exception e) {
                        failure.compareAndSet(null, new RuntimeException(e));
                    } finally {
                        latch.countDown();
                    }
                });
            } catch (RuntimeException e) {
                failure.compareAndSet(null, e);
                latch.countDown();
            }
        }

        InterruptedException interruption = null;
        while (latch.getCount() > 0L) {
            try {
                latch.await();
            } catch (InterruptedException e) {
                interruption = e;
            }
        }

        if (failure.get() != null || interruption != null) {
            forceTerminateChannels(value);
        }
        if (interruption != null) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(interruption);
        }
        if (failure.get() != null) {
            throw failure.get();
        }
        return value;
    }

    void submitChannelCreation(Runnable task) {
        this.executor.execute(task);
    }

    private void retireChannels(ManagedChannel[] retiredChannels) {
        Arrays.stream(retiredChannels)
              .filter(channel -> channel != null && !channel.isShutdown())
              .forEach(ManagedChannel::shutdown);

        long timeout = Math.max(0L, this.channelDrainTimeoutNanos());
        try {
            this.scheduleChannelRetirement(() -> forceTerminateChannels(retiredChannels), timeout);
        } catch (RuntimeException e) {
            log.warn("Failed to schedule forced retirement, forcing channels immediately", e);
            forceTerminateChannels(retiredChannels);
        }
    }

    void scheduleChannelRetirement(Runnable task, long timeoutNanos) {
        CHANNEL_RETIREMENT_EXECUTOR.schedule(task, timeoutNanos, TimeUnit.NANOSECONDS);
    }

    private void forceTerminateChannels(ManagedChannel[] retiredChannels) {
        for (ManagedChannel channel : retiredChannels) {
            if (channel != null && !channel.isTerminated()) {
                channel.shutdownNow();
            }
        }
    }

    /**
     * Extracts the host that a gRPC target resolves through, covering the plain {@code host:port}
     * form and the {@code dns:} scheme in both its {@code dns:host:port} and
     * {@code dns://authority/host:port} spellings. Any other resolver scheme returns an empty
     * host, leaving that target to gRPC instead of monitoring the wrong endpoint.
     */
    private static String targetHost(String target) {
        if (target == null || target.isEmpty()) {
            return "";
        }

        String endpoint = target;
        if (target.regionMatches(true, 0, DNS_SCHEME, 0, DNS_SCHEME.length())) {
            endpoint = target.substring(DNS_SCHEME.length());
            while (endpoint.startsWith("/")) {
                endpoint = endpoint.substring(1);
            }
            int pathStart = endpoint.indexOf('/');
            if (pathStart >= 0) {
                endpoint = endpoint.substring(pathStart + 1);
            }
        } else if (hasResolverScheme(target)) {
            return "";
        }

        try {
            // The authority parser handles ports and bracketed IPv6 literals.
            String host = new URI("//" + endpoint).getHost();
            if (host == null) {
                return "";
            }
            return host.startsWith("[") ? host.substring(1, host.length() - 1) : host;
        } catch (URISyntaxException ignored) {
            return "";
        }
    }

    /**
     * Tells a resolver scheme from the port of a plain {@code host:port} target: a scheme is
     * followed by a path, so {@code unix:/var/run/store.sock} is a scheme while
     * {@code store:8500} is not.
     */
    private static boolean hasResolverScheme(String target) {
        int scheme = target.indexOf(':');
        return scheme >= 0 && scheme + 1 < target.length() && target.charAt(scheme + 1) == '/';
    }

    protected InetAddress[] resolveHost(String host) throws UnknownHostException {
        return InetAddress.getAllByName(host);
    }

    protected String resolveTarget(String target) {
        String host = targetHost(target);
        if (host.isEmpty()) {
            return "";
        }
        try {
            return Arrays.stream(this.resolveHost(host))
                         .map(InetAddress::getHostAddress)
                         .distinct()
                         .sorted()
                         .collect(Collectors.joining(","));
        } catch (UnknownHostException ignored) {
            return "";
        }
    }

    protected ManagedChannel createChannel(String target) {
        return ManagedChannelBuilder.forTarget(target).usePlaintext().build();
    }

}
