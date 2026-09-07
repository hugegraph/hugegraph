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

import org.apache.tinkerpop.gremlin.server.AbstractChannelizer;
import org.apache.tinkerpop.gremlin.server.handler.WsAndHttpChannelizerHandler;
import org.apache.tinkerpop.gremlin.server.util.ServerGremlinExecutor;

import io.netty.channel.ChannelPipeline;

public class HugeGraphWsAndHttpChannelizer extends AbstractChannelizer {

    private static final String PIPELINE_PROTOCOL_SELECTOR =
            "hugegraph-ws-http-selector";
    private static final String PIPELINE_GREMLIN_LANG_GUARD =
            "hugegraph-gremlin-lang-guard";

    private WsAndHttpChannelizerHandler handler;

    @Override
    public void init(ServerGremlinExecutor serverGremlinExecutor) {
        super.init(serverGremlinExecutor);
        this.handler = new WsAndHttpChannelizerHandler();
        this.handler.init(serverGremlinExecutor,
                          new GremlinLangHttpHandler(this.serializers,
                                                     this.gremlinExecutor,
                                                     this.graphManager,
                                                     this.settings));
    }

    @Override
    public void configure(ChannelPipeline pipeline) {
        this.handler.configure(pipeline);
        pipeline.addAfter(PIPELINE_HTTP_REQUEST_DECODER,
                          PIPELINE_PROTOCOL_SELECTOR, this.handler);
    }

    @Override
    public void finalize(ChannelPipeline pipeline) {
        pipeline.addBefore(PIPELINE_OP_SELECTOR,
                           PIPELINE_GREMLIN_LANG_GUARD,
                           new GremlinLangRequestHandler());
    }

    @Override
    public boolean supportsIdleMonitor() {
        return true;
    }

    @Override
    public Object createIdleDetectionMessage() {
        return this.handler.getWsChannelizer().createIdleDetectionMessage();
    }
}
