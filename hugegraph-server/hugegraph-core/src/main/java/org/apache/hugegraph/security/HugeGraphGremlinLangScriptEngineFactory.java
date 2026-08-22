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

package org.apache.hugegraph.security;

import java.util.List;

import org.apache.tinkerpop.gremlin.jsr223.AbstractGremlinScriptEngineFactory;
import org.apache.tinkerpop.gremlin.jsr223.Customizer;
import org.apache.tinkerpop.gremlin.jsr223.GremlinLangScriptEngineFactory;

public class HugeGraphGremlinLangScriptEngineFactory
       extends AbstractGremlinScriptEngineFactory {

    public static final String ENGINE_NAME = "gremlin-lang";
    public static final String INTERNAL_ENGINE_NAME =
            "hugegraph-gremlin-lang";

    private static final GremlinLangScriptEngineFactory BASE_FACTORY =
            new GremlinLangScriptEngineFactory();

    private final Customizer[] fixedCustomizers;
    private volatile HugeGraphGremlinLangScriptEngine engine;

    public HugeGraphGremlinLangScriptEngineFactory() {
        super(INTERNAL_ENGINE_NAME, BASE_FACTORY.getLanguageName(),
              BASE_FACTORY.getExtensions(), BASE_FACTORY.getMimeTypes());
        this.fixedCustomizers = null;
    }

    public HugeGraphGremlinLangScriptEngineFactory(
            Customizer... customizers) {
        super(INTERNAL_ENGINE_NAME, BASE_FACTORY.getLanguageName(),
              BASE_FACTORY.getExtensions(), BASE_FACTORY.getMimeTypes());
        this.fixedCustomizers = customizers.clone();
    }

    @Override
    public synchronized HugeGraphGremlinLangScriptEngine getScriptEngine() {
        if (this.engine == null) {
            Customizer[] customizers = this.customizers();
            this.engine = new HugeGraphGremlinLangScriptEngine(this,
                                                                customizers);
        }
        return this.engine;
    }

    @Override
    public List<String> getNames() {
        return List.of(INTERNAL_ENGINE_NAME);
    }

    @Override
    public String getMethodCallSyntax(String object, String method,
                                      String... args) {
        return BASE_FACTORY.getMethodCallSyntax(object, method, args);
    }

    @Override
    public String getOutputStatement(String value) {
        return BASE_FACTORY.getOutputStatement(value);
    }

    private Customizer[] customizers() {
        if (this.fixedCustomizers != null) {
            return this.fixedCustomizers.clone();
        }
        if (this.manager == null) {
            return new Customizer[0];
        }
        List<Customizer> customizers = this.manager.getCustomizers(
                ENGINE_NAME);
        return customizers.toArray(new Customizer[0]);
    }
}
