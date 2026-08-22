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

import org.apache.tinkerpop.gremlin.util.Tokens;
import org.apache.tinkerpop.gremlin.util.message.RequestMessage;

public final class GremlinLangRequestGuard {

    public static final String GREMLIN_LANG = "gremlin-lang";

    private static final String CYPHER_PROCESSOR = "cypher";
    private static final String SESSION_PROCESSOR = "session";

    private GremlinLangRequestGuard() {
    }

    public static String rejection(RequestMessage request) {
        String op = request.getOp();
        if (Tokens.OPS_BYTECODE.equals(op)) {
            return "Remote bytecode requests are disabled; submit a " +
                   "gremlin-lang script instead";
        }

        String processor = request.getProcessor();
        if (CYPHER_PROCESSOR.equals(processor)) {
            if (Tokens.OPS_EVAL.equals(op) || op.isEmpty()) {
                return null;
            }
            return "The cypher processor only accepts text eval requests";
        }
        if (!Tokens.OPS_EVAL.equals(op) && !op.isEmpty()) {
            return null;
        }
        if (SESSION_PROCESSOR.equals(processor)) {
            return "The session processor is disabled for remote Gremlin " +
                   "requests";
        }
        if (processor != null && !processor.isEmpty()) {
            return String.format("The '%s' processor is not allowed for " +
                                 "remote Gremlin requests", processor);
        }

        String language = request.getArg(Tokens.ARGS_LANGUAGE);
        if (!GREMLIN_LANG.equals(language)) {
            return String.format("Remote Gremlin requests must use %s; " +
                                 "received '%s'", GREMLIN_LANG, language);
        }
        return null;
    }
}
