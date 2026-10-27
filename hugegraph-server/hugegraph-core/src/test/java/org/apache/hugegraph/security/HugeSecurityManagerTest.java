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

import java.lang.reflect.Field;
import java.util.Set;

import javax.script.ScriptException;

import org.apache.tinkerpop.gremlin.groovy.jsr223.GremlinGroovyScriptEngine;
import org.junit.Assert;
import org.junit.Test;

public class HugeSecurityManagerTest {

    @Test
    public void testWhiteSystemPropertiesExcludeRemovedBackends() throws Exception {
        Field field = HugeSecurityManager.class.getDeclaredField(
                "WHITE_SYSTEM_PROPERTIES");
        field.setAccessible(true);

        @SuppressWarnings("unchecked")
        Set<String> properties = (Set<String>) field.get(null);
        Assert.assertFalse(properties.contains("socksProxyHost"));
        Assert.assertFalse(properties.contains("file.encoding"));
        Assert.assertTrue(properties.contains("java.specification.version"));
    }

    @Test
    public void testGrpcAllocatorPropertyIsReadOnlyFromGremlin() throws Exception {
        assertReadOnlyProperty("io.grpc.netty.shaded.io.grpc.netty.useCustomAllocator", "true");
    }

    @Test
    public void testDnsCacheTtlPropertyIsReadOnlyFromGremlin() throws Exception {
        assertReadOnlyProperty("networkaddress.cache.ttl", "30");
    }

    private static void assertReadOnlyProperty(String key, String fallback) throws Exception {
        String originalValue = System.getProperty(key);
        SecurityManager originalManager = System.getSecurityManager();
        Thread thread = Thread.currentThread();
        String originalName = thread.getName();
        GremlinGroovyScriptEngine engine = new GremlinGroovyScriptEngine();
        // Server initializes logging before accepting sandboxed Gremlin requests.
        org.apache.hugegraph.util.Log.logger(HugeSecurityManager.class);
        try {
            engine.eval("1 + 1");
            System.setSecurityManager(new HugeSecurityManager());
            thread.setName("gremlin-server-exec-security-test");

            Assert.assertEquals(originalValue,
                                engine.eval("System.getProperty('" + key + "')"));
            Assert.assertEquals(originalValue == null ? fallback : originalValue,
                                engine.eval("System.getProperty('" + key + "', '" + fallback + "')"));
            assertGremlinDenied(engine, "System.getProperty('user.home')",
                                "Not allowed to access system property(user.home) via Gremlin");
            assertGremlinDenied(engine, "System.getProperty('file.encoding')",
                                "Not allowed to access system property(file.encoding) via Gremlin");
            assertGremlinDenied(engine, "System.setProperty('" + key + "', 'false')",
                                "Not allowed to modify system property(" + key + ") via Gremlin");
            assertGremlinDenied(engine, "System.clearProperty('" + key + "')",
                                "Not allowed to modify system property(" + key + ") via Gremlin");
            Assert.assertEquals(originalValue, System.getProperty(key));
        } finally {
            thread.setName(originalName);
            System.setSecurityManager(originalManager);
        }
    }

    private static void assertGremlinDenied(GremlinGroovyScriptEngine engine,
                                            String script, String message) throws Exception {
        try {
            engine.eval(script);
            Assert.fail("Expected Gremlin access to be denied: " + script);
        } catch (ScriptException e) {
            Assert.assertTrue(e.toString(), e.toString().contains(message));
        }
    }
}
