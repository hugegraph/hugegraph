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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.apache.hugegraph.pd.meta.MetadataFactory;
import org.apache.hugegraph.pd.pulse.PDPulseSubject;
import org.apache.hugegraph.pd.raft.RaftEngine;
import org.apache.hugegraph.pd.service.MetadataService;
import org.apache.hugegraph.pd.util.HgExecutorUtil;
import org.apache.hugegraph.pd.util.ShutdownUtil;
import org.apache.hugegraph.pd.util.grpc.GRpcServerConfig;
import org.lognet.springboot.grpc.context.GRpcServerInitializedEvent;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.ApplicationListener;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import io.grpc.Server;

/** One owner for PD metadata and every producer that can still use it during shutdown. */
@Component("pdLifecycle")
public class PDLifecycle implements SmartLifecycle, DisposableBean,
                                    ApplicationListener<GRpcServerInitializedEvent> {

    private final PDRequestGate requests;
    private final List<Server> servers = new CopyOnWriteArrayList<>();
    private final List<Runnable> producerStops = new CopyOnWriteArrayList<>();
    private volatile boolean running;
    private boolean closed;
    private boolean stopping;

    public PDLifecycle(PDRequestGate requests) {
        this.requests = requests;
    }

    // Register before initialization starts background work, so failed startup is covered too.
    public synchronized void registerProducer(Runnable stop) {
        if (this.stopping) {
            throw new IllegalStateException("PD metadata owner is closed");
        }
        this.producerStops.add(stop);
    }

    @Override
    public void onApplicationEvent(GRpcServerInitializedEvent event) {
        this.servers.add(event.getServer());
    }

    @Override
    public void start() {
        this.running = true;
    }

    @Override
    public boolean isRunning() {
        return this.running;
    }

    @Override
    public int getPhase() {
        // Web/gRPC entrypoints have higher phases and stop accepting first.
        return 0;
    }

    @Override
    public synchronized void stop() {
        if (this.closed) {
            return;
        }
        this.stopping = true;
        this.requests.stopAndDrain();
        // Cancel idle watch/pulse streams, then wait for transports AND owned callbacks.
        for (Server server : this.servers) {
            server.shutdownNow();
        }
        for (Server server : this.servers) {
            try {
                if (!server.awaitTermination(ShutdownUtil.DRAIN_SECONDS, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("PD shutdown did not drain gRPC transports");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("PD shutdown interrupted while draining gRPC", e);
            }
        }
        ShutdownUtil.finishExecutor(HgExecutorUtil.getThreadPoolExecutor(
                GRpcServerConfig.EXECUTOR_NAME), "gRPC callbacks");
        this.producerStops.forEach(Runnable::run);
        PDPulseSubject.shutdown();
        // Snapshot jobs must remain available until Raft has stopped producing them.
        RaftEngine.getInstance().shutDown();
        ShutdownUtil.finishExecutor(MetadataService.getUninterruptibleJobs(), "snapshot jobs");
        MetadataFactory.closeStore();
        this.closed = true;
        this.running = false;
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    @Override
    public void destroy() {
        // Also covers refresh failure before SmartLifecycle.start(). Failure leaves DB open.
        stop();
    }
}
