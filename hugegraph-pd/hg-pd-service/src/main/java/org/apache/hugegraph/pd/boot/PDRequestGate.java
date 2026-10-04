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

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.hugegraph.pd.util.ShutdownUtil;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** PD REST handlers are synchronous; retain ownership until their filter chain returns. */
@Component
public class PDRequestGate extends OncePerRequestFilter {

    private final AtomicBoolean closing = new AtomicBoolean();
    private final ReentrantReadWriteLock requests = new ReentrantReadWriteLock(true);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        this.requests.readLock().lock();
        try {
            if (this.closing.get()) {
                response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "PD is stopping");
                return;
            }
            chain.doFilter(request, response);
        } finally {
            this.requests.readLock().unlock();
        }
    }

    public void stopAndDrain() {
        this.closing.set(true);
        try {
            if (!this.requests.writeLock().tryLock(ShutdownUtil.DRAIN_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("PD shutdown did not drain REST requests");
            }
            this.requests.writeLock().unlock();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("PD shutdown interrupted while draining REST requests", e);
        }
    }
}
