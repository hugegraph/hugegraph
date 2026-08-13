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
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.concurrent.locks.ReentrantReadWriteLock;

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
public abstract class AbstractGrpcClient implements AutoCloseable {

    protected static Map<String, ManagedChannel[]> channels = new ConcurrentHashMap<>();
    private static final Map<String, String> resolvedTargets = new ConcurrentHashMap<>();
    // A null deadline is the explicit "never scheduled" state; every long is a valid clock value.
    private static final Map<String, AtomicReference<Long>> nextResolutions =
            new ConcurrentHashMap<>();
    private static final Map<String, CompletableFuture<Void>> refreshTasks =
            new ConcurrentHashMap<>();
    private static final Map<String, ReentrantReadWriteLock> channelLocks =
            new ConcurrentHashMap<>();
    private static final Map<AbstractGrpcClient, Boolean> clients =
            Collections.synchronizedMap(new WeakHashMap<>());
    /*
     * DNS refresh and first-pool creation run on prestarted threads because a caller of
     * getChannels() may hold a Gremlin worker stack that HugeSecurityManager restricts.
     */
    private static final ScheduledThreadPoolExecutor CHANNEL_MAINTENANCE_EXECUTOR =
            new ScheduledThreadPoolExecutor(
                    4, ExecutorPool.newThreadFactory("channel-maintenance"));
    private static final ScheduledThreadPoolExecutor CHANNEL_INITIALIZATION_EXECUTOR =
            new ScheduledThreadPoolExecutor(
                    4, ExecutorPool.newThreadFactory("channel-initialization"));
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
        prestartExecutor(CHANNEL_INITIALIZATION_EXECUTOR, "channel initialization");
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
    private final Set<String> targets = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor executor;
    private volatile boolean closed;

    {
        executor = ExecutorPool.createExecutor("common", 60, concurrency, concurrency);
    }

    public AbstractGrpcClient() {
        clients.put(this, Boolean.TRUE);
    }

    public ManagedChannel[] getChannels(String target) {
        return this.getChannels(target, () -> true);
    }

    private ManagedChannel[] getChannels(String target, BooleanSupplier targetAvailable) {
        CompletableFuture<Void> refresh =
                this.registerTargetAndTriggerRefresh(target, targetAvailable);
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
        ReentrantReadWriteLock targetLock = acquireTargetWriteLock(target);
        try {
            if (this.closed || !this.targets.contains(target) ||
                !targetAvailable.getAsBoolean()) {
                throw new IllegalStateException("The gRPC target is closed");
            }
            if ((tc = channels.get(target)) == null) {
                CompletableFuture<ManagedChannel[]> creation = new CompletableFuture<>();
                try {
                    this.submitChannelInitialization(() -> {
                        try {
                            creation.complete(this.createChannels(target));
                        } catch (Throwable e) {
                            creation.completeExceptionally(e);
                        }
                    });
                    InterruptedException interruption = null;
                    for (;;) {
                        try {
                            tc = creation.get();
                            break;
                        } catch (InterruptedException e) {
                            interruption = e;
                        } catch (ExecutionException e) {
                            if (interruption != null) {
                                Thread.currentThread().interrupt();
                            }
                            throw propagate(e.getCause());
                        }
                    }
                    if (interruption != null) {
                        forceTerminateChannels(tc);
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(interruption);
                    }
                } catch (Throwable e) {
                    throw propagate(e);
                }
                channels.put(target, tc);
            }
        } finally {
            targetLock.writeLock().unlock();
        }
        return tc;
    }

    public abstract AbstractBlockingStub getBlockingStub(ManagedChannel channel);

    public AbstractBlockingStub getBlockingStub(String target) {
        return this.getBlockingStub(target, () -> true);
    }

    protected AbstractBlockingStub getBlockingStub(String target,
                                                   BooleanSupplier targetAvailable) {
        return this.acquireStub(target, this.blockingStubs, this::getBlockingStub,
                                stub -> (AbstractBlockingStub) this.setBlockingStubOption(stub),
                                targetAvailable);
    }

    /**
     * Returns a cached stub bound to a channel of the target's current pool, rebuilding the
     * cache when the pool has been replaced. The stub comes from the pool that was published at
     * the last check; a refresh landing immediately afterwards can still retire that pool, so
     * callers are not shielded from an in-flight replacement.
     *
     * <p>The final pool check and cache publication happen under the target's read lock, so a
     * replacement cannot publish a new pool and retire the previous one in between them.
     */
    @SuppressWarnings("unchecked")
    private <S> S acquireStub(String target,
                              Map<String, HgPair<ManagedChannel, S>[]> stubCache,
                              Function<ManagedChannel, S> stubFactory,
                              Function<S, S> stubOption,
                              BooleanSupplier targetAvailable) {
        while (true) {
            ManagedChannel[] targetChannels = this.getChannels(target, targetAvailable);
            HgPair<ManagedChannel, S>[] pairs = stubCache.get(target);
            int index = nextStubIndex();
            S configuredStub;
            HgPair<ManagedChannel, S>[] value = null;
            if (!usesChannels(pairs, targetChannels)) {
                synchronized (stubCache) {
                    pairs = stubCache.get(target);
                    if (!usesChannels(pairs, targetChannels)) {
                        final HgPair<ManagedChannel, S>[] newValue = new HgPair[concurrency];
                        IntStream.range(0, concurrency).forEach(i -> {
                            ManagedChannel channel = targetChannels[i];
                            newValue[i] = new HgPair<>(channel, stubFactory.apply(channel));
                        });
                        value = newValue;
                        configuredStub = stubOption.apply(newValue[index].getValue());
                    } else {
                        configuredStub = stubOption.apply(pairs[index].getValue());
                    }
                }
            } else {
                configuredStub = stubOption.apply(pairs[index].getValue());
            }

            ReentrantReadWriteLock targetLock = acquireTargetReadLock(target);
            try {
                if (channels.get(target) != targetChannels) {
                    continue;
                }
                if (value != null) {
                    stubCache.put(target, value);
                }
                return configuredStub;
            } finally {
                targetLock.readLock().unlock();
            }
        }
    }

    private static ReentrantReadWriteLock channelLock(String target) {
        return channelLocks.computeIfAbsent(target, key -> new ReentrantReadWriteLock());
    }

    private static ReentrantReadWriteLock acquireTargetReadLock(String target) {
        while (true) {
            ReentrantReadWriteLock lock = channelLock(target);
            lock.readLock().lock();
            if (channelLocks.get(target) == lock) {
                return lock;
            }
            lock.readLock().unlock();
        }
    }

    private static ReentrantReadWriteLock acquireTargetWriteLock(String target) {
        while (true) {
            ReentrantReadWriteLock lock = channelLock(target);
            lock.writeLock().lock();
            if (channelLocks.get(target) == lock) {
                return lock;
            }
            lock.writeLock().unlock();
        }
    }

    private static RuntimeException propagate(Throwable cause) {
        if (cause instanceof Error) {
            throw (Error) cause;
        }
        return cause instanceof RuntimeException ? (RuntimeException) cause :
               new RuntimeException(cause);
    }

    private CompletableFuture<Void> registerTargetAndTriggerRefresh(
            String target, BooleanSupplier targetAvailable) {
        ReentrantReadWriteLock lock = acquireTargetReadLock(target);
        try {
            synchronized (clients) {
                if (this.closed || !targetAvailable.getAsBoolean()) {
                    throw new IllegalStateException("The gRPC client is closed");
                }
                this.targets.add(target);
            }
            return this.triggerChannelRefresh(target);
        } finally {
            lock.readLock().unlock();
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
        return this.getAsyncStub(target, () -> true);
    }

    protected AbstractAsyncStub getAsyncStub(String target,
                                             BooleanSupplier targetAvailable) {
        return this.acquireStub(target, this.asyncStubs, this::getAsyncStub,
                                stub -> (AbstractAsyncStub) this.setStubOption(stub),
                                targetAvailable);
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
                    this.refreshChannelsIfAddressChanged(target, refresh);
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
        if (refreshTasks.remove(target, refresh)) {
            this.postponeNextRefresh(target);
        }
        refresh.complete(null);
    }

    void submitChannelRefresh(Runnable task) {
        CHANNEL_MAINTENANCE_EXECUTOR.execute(task);
    }

    void submitChannelInitialization(Runnable task) {
        CHANNEL_INITIALIZATION_EXECUTOR.execute(task);
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
    private void refreshChannelsIfAddressChanged(String target,
                                                  CompletableFuture<Void> refresh) {
        String resolvedTarget = this.resolveTarget(target);
        if (resolvedTarget.isEmpty() || refreshTasks.get(target) != refresh) {
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
            ReentrantReadWriteLock targetLock = acquireTargetWriteLock(target);
            try {
                if (refreshTasks.get(target) == refresh && channels.get(target) == null) {
                    resolvedTargets.put(target, resolvedTarget);
                }
            } finally {
                targetLock.writeLock().unlock();
            }
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

        ReentrantReadWriteLock targetLock = acquireTargetWriteLock(target);
        try {
            boolean replaced = false;
            synchronized (channels) {
                if (refreshTasks.get(target) == refresh &&
                    channels.get(target) == staleChannels) {
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
        } finally {
            targetLock.writeLock().unlock();
        }
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
        AtomicReference<Throwable> failure = new AtomicReference<>();
        for (int i = 0; i < concurrency; i++) {
            int fi = i;
            try {
                this.submitChannelCreation(() -> {
                    try {
                        value[fi] = createChannel(target);
                    } catch (Throwable e) {
                        failure.compareAndSet(null, e);
                    } finally {
                        latch.countDown();
                    }
                });
            } catch (Throwable e) {
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
        if (failure.get() == null && Arrays.stream(value).anyMatch(channel -> channel == null)) {
            failure.set(new IllegalStateException("Channel creation returned a null channel"));
            forceTerminateChannels(value);
        }
        if (interruption != null) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(interruption);
        }
        if (failure.get() != null) {
            Throwable cause = failure.get();
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw propagate(cause);
        }
        return value;
    }

    @Override
    public void close() {
        String[] ownedTargets;
        synchronized (clients) {
            this.closed = true;
            ownedTargets = this.targets.toArray(new String[0]);
        }
        for (String target : ownedTargets) {
            ReentrantReadWriteLock lock = acquireTargetWriteLock(target);
            try {
                synchronized (clients) {
                    this.targets.remove(target);
                    this.blockingStubs.remove(target);
                    this.asyncStubs.remove(target);
                    if (!isTargetInUse(target)) {
                        closeChannelLocked(target, lock);
                    }
                }
            } finally {
                lock.writeLock().unlock();
            }
        }
        clients.remove(this);
        synchronized (this.executor) {
            this.executor.shutdown();
        }
    }

    protected static void closeAllChannels() {
        Set<String> targets = ConcurrentHashMap.newKeySet();
        synchronized (clients) {
            targets.addAll(channels.keySet());
            targets.addAll(resolvedTargets.keySet());
            targets.addAll(nextResolutions.keySet());
            targets.addAll(refreshTasks.keySet());
            targets.addAll(channelLocks.keySet());
            for (AbstractGrpcClient client : clients.keySet()) {
                targets.addAll(client.targets);
                client.targets.clear();
                client.blockingStubs.clear();
                client.asyncStubs.clear();
            }
        }
        for (String target : targets) {
            closeChannel(target);
        }
    }

    private static boolean isTargetInUse(String target) {
        return clients.keySet().stream().anyMatch(client -> client.targets.contains(target));
    }

    public static void closeChannel(String target) {
        ReentrantReadWriteLock lock = acquireTargetWriteLock(target);
        try {
            closeChannelLocked(target, lock);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private static void closeChannelLocked(String target, ReentrantReadWriteLock lock) {
        CompletableFuture<Void> refresh = refreshTasks.remove(target);
        if (refresh != null) {
            refresh.complete(null);
        }
        ManagedChannel[] retiredChannels = channels.remove(target);
        resolvedTargets.remove(target);
        nextResolutions.remove(target);
        synchronized (clients) {
            for (AbstractGrpcClient client : clients.keySet()) {
                client.targets.remove(target);
                client.blockingStubs.remove(target);
                client.asyncStubs.remove(target);
            }
        }
        if (retiredChannels != null) {
            forceTerminateChannels(retiredChannels);
        }
        channelLocks.remove(target, lock);
    }

    void submitChannelCreation(Runnable task) {
        synchronized (this.executor) {
            if (this.executor.isShutdown()) {
                throw new RejectedExecutionException("The gRPC client is closed");
            }
            this.executor.execute(task);
        }
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

    private static void forceTerminateChannels(ManagedChannel[] retiredChannels) {
        for (ManagedChannel channel : retiredChannels) {
            if (channel != null && !channel.isTerminated()) {
                channel.shutdownNow();
            }
        }
    }

    /**
     * Extracts the host that a gRPC target resolves through, covering the plain {@code host:port}
     * form and slash-prefixed {@code dns:///host:port} and
     * {@code dns://authority/host:port} spellings. The opaque {@code dns:host:port} spelling and
     * any other resolver scheme return an empty host, leaving that target to gRPC.
     */
    private static String targetHost(String target) {
        if (target == null || target.isEmpty()) {
            return "";
        }

        String endpoint = target;
        if (target.startsWith(DNS_SCHEME)) {
            endpoint = target.substring(DNS_SCHEME.length());
            if (!endpoint.startsWith("/")) {
                return "";
            }
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
