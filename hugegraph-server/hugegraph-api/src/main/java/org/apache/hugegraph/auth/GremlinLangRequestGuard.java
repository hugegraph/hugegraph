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

import org.apache.hugegraph.security.HugeGraphGremlinLangScriptEngineFactory;
import org.apache.tinkerpop.gremlin.process.traversal.Bytecode;
import org.apache.tinkerpop.gremlin.process.traversal.util.BytecodeHelper;
import org.apache.tinkerpop.gremlin.util.Tokens;
import org.apache.tinkerpop.gremlin.util.message.RequestMessage;

public final class GremlinLangRequestGuard {

    public static final String GREMLIN_LANG = "gremlin-lang";

    private static final String CYPHER_PROCESSOR = "cypher";
    private static final String SESSION_PROCESSOR = "session";
    private static final String TRAVERSAL_PROCESSOR = "traversal";

    private GremlinLangRequestGuard() {
    }

    public static String rejection(RequestMessage request) {
        String op = request.getOp();
        String processor = request.getProcessor();
        if (op == null) {
            op = "";
        }
        if (processor == null) {
            processor = "";
        }

        if (Tokens.OPS_AUTHENTICATION.equals(op)) {
            return processor.isEmpty() ? null : unsupported(processor, op);
        }
        if (CYPHER_PROCESSOR.equals(processor)) {
            if (Tokens.OPS_EVAL.equals(op) || op.isEmpty()) {
                return textPayloadRejection(request, false);
            }
            return "The cypher processor only accepts text eval requests";
        }
        if (TRAVERSAL_PROCESSOR.equals(processor)) {
            if (Tokens.OPS_BYTECODE.equals(op)) {
                return bytecodeRejection(request);
            }
            return unsupported(processor, op);
        }
        if (SESSION_PROCESSOR.equals(processor)) {
            String rejection = sessionRejection(request);
            if (rejection != null) {
                return rejection;
            }
            if (Tokens.OPS_EVAL.equals(op)) {
                return textPayloadRejection(request, true);
            }
            if (Tokens.OPS_BYTECODE.equals(op)) {
                return bytecodeRejection(request);
            }
            if (Tokens.OPS_CLOSE.equals(op)) {
                return null;
            }
            return unsupported(processor, op);
        }
        if (!processor.isEmpty()) {
            return unsupported(processor, op);
        }
        if (Tokens.OPS_EVAL.equals(op) || op.isEmpty()) {
            return textPayloadRejection(request, true);
        }
        return unsupported(processor, op);
    }

    public static RequestMessage normalize(RequestMessage request) {
        String rejection = rejection(request);
        if (rejection != null) {
            throw new IllegalArgumentException(rejection);
        }

        String processor = request.getProcessor();
        String op = request.getOp();
        boolean gremlinText = (processor == null || processor.isEmpty() ||
                               SESSION_PROCESSOR.equals(processor)) &&
                              (Tokens.OPS_EVAL.equals(op) || op.isEmpty());
        if (!gremlinText) {
            return request;
        }
        return RequestMessage.from(request)
                             .addArg(Tokens.ARGS_LANGUAGE,
                                     HugeGraphGremlinLangScriptEngineFactory.
                                     INTERNAL_ENGINE_NAME)
                             .create();
    }

    private static String textPayloadRejection(RequestMessage request,
                                               boolean checkLanguage) {
        Object gremlin = request.getArgs().get(Tokens.ARGS_GREMLIN);
        if (!(gremlin instanceof String)) {
            return "The gremlin argument for a text eval request must be " +
                   "a string";
        }
        if (!checkLanguage) {
            return null;
        }

        if (!request.getArgs().containsKey(Tokens.ARGS_LANGUAGE)) {
            return null;
        }
        Object language = request.getArgs().get(Tokens.ARGS_LANGUAGE);
        if (!(language instanceof String)) {
            return "The language argument must be a string when provided";
        }
        if (!GREMLIN_LANG.equals(language)) {
            return String.format("Remote Gremlin requests must use %s; " +
                                 "received '%s'", GREMLIN_LANG, language);
        }
        return null;
    }

    private static String sessionRejection(RequestMessage request) {
        Object session = request.getArgs().get(Tokens.ARGS_SESSION);
        if (!(session instanceof String)) {
            return "The session argument must be a string";
        }
        return null;
    }

    private static String bytecodeRejection(RequestMessage request) {
        Object gremlin = request.getArgs().get(Tokens.ARGS_GREMLIN);
        if (!(gremlin instanceof Bytecode)) {
            return "The gremlin argument for a bytecode request must be " +
                   "Bytecode";
        }
        Bytecode bytecode = (Bytecode) gremlin;
        if (BytecodeHelper.getLambdaLanguage(bytecode).isPresent()) {
            return "Remote Bytecode requests containing a Lambda are not " +
                   "allowed";
        }
        for (Bytecode.Instruction instruction :
             bytecode.getSourceInstructions()) {
            if ("withoutStrategies".equals(instruction.getOperator())) {
                return "Remote Bytecode requests cannot use " +
                       "withoutStrategies";
            }
        }
        return null;
    }

    private static String unsupported(String processor, String op) {
        String name = processor.isEmpty() ? "standard" : processor;
        return String.format("The '%s' processor does not allow operation " +
                             "'%s' for remote requests", name, op);
    }
}
