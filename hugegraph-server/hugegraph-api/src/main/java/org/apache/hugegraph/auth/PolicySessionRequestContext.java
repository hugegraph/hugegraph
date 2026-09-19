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

package org.apache.hugegraph.auth;

import java.util.List;
import java.util.concurrent.ScheduledFuture;

import org.apache.tinkerpop.gremlin.server.Context;
import org.apache.tinkerpop.gremlin.util.message.ResponseStatusCode;

/** Observes terminal script responses while the original Context owns transport and timeout state. */
final class PolicySessionRequestContext extends Context {

    private final Context original;
    private volatile ResponseStatusCode terminal;

    PolicySessionRequestContext(Context original) {
        super(original.getRequestMessage(), original.getChannelHandlerContext(), original.getSettings(),
              original.getGraphManager(), original.getGremlinExecutor(), original.getScheduledExecutorService());
        this.original = original;
    }

    boolean succeeded() {
        return this.terminal == ResponseStatusCode.SUCCESS || this.terminal == ResponseStatusCode.NO_CONTENT;
    }

    @Override
    public synchronized void writeAndFlush(ResponseStatusCode code, Object message) {
        boolean alreadyFinal = this.original.isFinalResponseWritten();
        this.original.writeAndFlush(code, message);
        if (!alreadyFinal && code.isFinalResponse() && this.original.isFinalResponseWritten()) {
            this.terminal = code;
        }
    }

    @Override
    public synchronized void write(ResponseStatusCode code, Object message) {
        boolean alreadyFinal = this.original.isFinalResponseWritten();
        this.original.write(code, message);
        if (!alreadyFinal && code.isFinalResponse() && this.original.isFinalResponseWritten()) {
            this.terminal = code;
        }
    }

    @Override
    public void flush() {
        this.original.flush();
    }

    @Override
    public boolean isFinalResponseWritten() {
        return this.original.isFinalResponseWritten();
    }

    @Override
    public boolean getStartedResponse() {
        return this.original.getStartedResponse();
    }

    @Override
    public void setStartedResponse() {
        this.original.setStartedResponse();
    }

    @Override
    public void setTimeoutExecutor(ScheduledFuture<?> timeout) {
        this.original.setTimeoutExecutor(timeout);
    }

    @Override
    public ScheduledFuture<?> getTimeoutExecutor() {
        return this.original.getTimeoutExecutor();
    }

    @Override
    public long getRequestTimeout() {
        return this.original.getRequestTimeout();
    }

    @Override
    public void handleDetachment(List<Object> aggregate) {
        this.original.handleDetachment(aggregate);
    }
}
