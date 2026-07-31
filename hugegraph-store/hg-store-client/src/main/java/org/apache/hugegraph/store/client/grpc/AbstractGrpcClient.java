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
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
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

public abstract class AbstractGrpcClient {

    protected static Map<String, ManagedChannel[]> channels = new ConcurrentHashMap<>();
    private static final Map<String, String> resolvedTargets = new ConcurrentHashMap<>();
    private static final Map<String, AtomicLong> nextResolutions = new ConcurrentHashMap<>();
    private static final Map<String, ReentrantLock> refreshLocks = new ConcurrentHashMap<>();
    private static final ScheduledThreadPoolExecutor CHANNEL_CLEANUP_EXECUTOR =
            new ScheduledThreadPoolExecutor(
                    1, ExecutorPool.newThreadFactory("channel-cleanup"));
    private static final long DEFAULT_CHANNEL_REFRESH_INTERVAL_NANOS =
            TimeUnit.SECONDS.toNanos(5L);
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
        this.refreshChannelsIfAddressChanged(target);
        ManagedChannel[] tc;
        if ((tc = channels.get(target)) == null) {
            synchronized (channels) {
                if ((tc = channels.get(target)) == null) {
                    channels.put(target, tc = this.createChannels(target));
                }
            }
        }
        return tc;
    }

    public abstract AbstractBlockingStub getBlockingStub(ManagedChannel channel);

    public AbstractBlockingStub getBlockingStub(String target) {
        while (true) {
            ManagedChannel[] targetChannels = getChannels(target);
            HgPair<ManagedChannel, AbstractBlockingStub>[] pairs = blockingStubs.get(target);
            long l = counter.getAndIncrement();
            if (l >= limit) {
                counter.set(0);
            }
            int index = (int) (l & (concurrency - 1));
            if (!usesChannels(pairs, targetChannels)) {
                synchronized (blockingStubs) {
                    pairs = blockingStubs.get(target);
                    if (!usesChannels(pairs, targetChannels)) {
                        HgPair<ManagedChannel, AbstractBlockingStub>[] value =
                                new HgPair[concurrency];
                        IntStream.range(0, concurrency).forEach(i -> {
                            ManagedChannel channel = targetChannels[i];
                            AbstractBlockingStub stub = getBlockingStub(channel);
                            value[i] = new HgPair<>(channel, stub);
                            // log.info("create channel for {}",target);
                        });
                        synchronized (channels) {
                            if (channels.get(target) != targetChannels) {
                                continue;
                            }
                            blockingStubs.put(target, value);
                            AbstractBlockingStub stub = value[index].getValue();
                            return (AbstractBlockingStub) setBlockingStubOption(stub);
                        }
                    }
                }
            }
            synchronized (channels) {
                if (channels.get(target) != targetChannels) {
                    continue;
                }
                return (AbstractBlockingStub) setBlockingStubOption(pairs[index].getValue());
            }
        }
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
        while (true) {
            ManagedChannel[] targetChannels = getChannels(target);
            HgPair<ManagedChannel, AbstractAsyncStub>[] pairs = asyncStubs.get(target);
            long l = counter.getAndIncrement();
            if (l >= limit) {
                counter.set(0);
            }
            int index = (int) (l & (concurrency - 1));
            if (!usesChannels(pairs, targetChannels)) {
                synchronized (asyncStubs) {
                    pairs = asyncStubs.get(target);
                    if (!usesChannels(pairs, targetChannels)) {
                        HgPair<ManagedChannel, AbstractAsyncStub>[] value =
                                new HgPair[concurrency];
                        IntStream.range(0, concurrency).parallel().forEach(i -> {
                            ManagedChannel channel = targetChannels[i];
                            AbstractAsyncStub stub = getAsyncStub(channel);
                            // stub.withMaxInboundMessageSize(
                            //         config.getGrpcMaxInboundMessageSize())
                            //     .withMaxOutboundMessageSize(
                            //         config.getGrpcMaxOutboundMessageSize());
                            value[i] = new HgPair<>(channel, stub);
                            // log.info("create channel for {}",target);
                        });
                        synchronized (channels) {
                            if (channels.get(target) != targetChannels) {
                                continue;
                            }
                            asyncStubs.put(target, value);
                            AbstractAsyncStub stub =
                                    (AbstractAsyncStub) setStubOption(value[index].getValue());
                            return stub;
                        }
                    }
                }
            }
            synchronized (channels) {
                if (channels.get(target) != targetChannels) {
                    continue;
                }
                return (AbstractAsyncStub) setStubOption(pairs[index].getValue());
            }
        }
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

    private void refreshChannelsIfAddressChanged(String target) {
        if (!this.shouldRefreshChannels(target)) {
            return;
        }

        ReentrantLock refreshLock = refreshLocks.computeIfAbsent(target,
                                                                 key -> new ReentrantLock());
        if (!refreshLock.tryLock()) {
            return;
        }

        try {
            if (!this.shouldRefreshChannels(target)) {
                return;
            }

            String resolvedTarget = this.resolveTarget(target);
            this.postponeNextRefresh(target);
            if (resolvedTarget.isEmpty()) {
                return;
            }

            ManagedChannel[] staleChannels = channels.get(target);
            String previousTarget = resolvedTargets.get(target);
            if (previousTarget == null && staleChannels == null) {
                resolvedTargets.put(target, resolvedTarget);
                return;
            }
            if (resolvedTarget.equals(previousTarget)) {
                return;
            }
            if (staleChannels == null) {
                resolvedTargets.put(target, resolvedTarget);
                return;
            }

            ManagedChannel[] replacementChannels;
            try {
                replacementChannels = this.createChannels(target);
            } catch (RuntimeException ignored) {
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
                this.retireChannels(staleChannels);
            } else {
                this.retireChannels(replacementChannels);
            }
        } finally {
            refreshLock.unlock();
        }
    }

    private boolean shouldRefreshChannels(String target) {
        AtomicLong nextResolution = nextResolutions.computeIfAbsent(target,
                                                                    key -> new AtomicLong());
        return System.nanoTime() - nextResolution.get() >= 0L;
    }

    private void postponeNextRefresh(String target) {
        long interval = Math.max(0L, this.channelRefreshIntervalNanos());
        nextResolutions.computeIfAbsent(target, key -> new AtomicLong())
                       .set(System.nanoTime() + interval);
    }

    protected long channelRefreshIntervalNanos() {
        return DEFAULT_CHANNEL_REFRESH_INTERVAL_NANOS;
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
            executor.execute(() -> {
                try {
                    value[fi] = createChannel(target);
                } catch (Exception e) {
                    failure.compareAndSet(null, new RuntimeException(e));
                } finally {
                    latch.countDown();
                }
            });
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

    private void retireChannels(ManagedChannel[] retiredChannels) {
        Arrays.stream(retiredChannels)
              .filter(channel -> channel != null && !channel.isShutdown())
              .forEach(ManagedChannel::shutdown);

        long timeout = Math.max(0L, this.channelDrainTimeoutNanos());
        CHANNEL_CLEANUP_EXECUTOR.schedule(
                () -> forceTerminateChannels(retiredChannels), timeout,
                TimeUnit.NANOSECONDS);
    }

    private void forceTerminateChannels(ManagedChannel[] retiredChannels) {
        for (ManagedChannel channel : retiredChannels) {
            if (channel != null && !channel.isTerminated()) {
                channel.shutdownNow();
            }
        }
    }

    private static String targetHost(String target) {
        if (target == null || target.isEmpty()) {
            return "";
        }

        String endpoint = target;
        if (target.startsWith("dns://")) {
            endpoint = target.substring("dns://".length());
            while (endpoint.startsWith("/")) {
                endpoint = endpoint.substring(1);
            }
            int pathStart = endpoint.indexOf('/');
            if (pathStart >= 0) {
                endpoint = endpoint.substring(pathStart + 1);
            }
        } else if (target.contains("://")) {
            return "";
        }

        return endpointHost(endpoint);
    }

    private static String endpointHost(String endpoint) {
        if (endpoint == null || endpoint.isEmpty()) {
            return "";
        }

        if (endpoint.charAt(0) == '[') {
            int hostEnd = endpoint.indexOf(']');
            if (hostEnd <= 1) {
                return "";
            }
            return endpoint.substring(1, hostEnd);
        }

        int lastColon = endpoint.lastIndexOf(':');
        if (lastColon < 0) {
            return endpoint;
        }
        if (endpoint.indexOf(':') != lastColon) {
            return endpoint;
        }
        return endpoint.substring(0, lastColon);
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
