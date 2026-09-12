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

package org.apache.hugegraph.unit.core;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.hugegraph.security.script.ScriptCompilerConfiguration;
import org.apache.hugegraph.security.script.ScriptSecurityMode;
import org.apache.hugegraph.security.script.ScriptSyntaxGuard;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.control.CompilePhase;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.transform.ASTTransformation;
import org.codehaus.groovy.transform.GroovyASTTransformation;
import org.junit.Assert;
import org.junit.Test;

import groovy.lang.GroovyClassLoader;
import groovy.lang.Script;

public class ScriptPolicyCompilationTest {

    @Test
    public void testModeParsingDoesNotFallBack() {
        Assert.assertEquals(ScriptSecurityMode.LEGACY,
                            ScriptSecurityMode.parse("legacy"));
        Assert.assertTrue(ScriptSecurityMode.parse("combined").policyEnabled());
        Assert.assertTrue(ScriptSecurityMode.parse("policy-only").experimental());
        for (String invalid : new String[]{null, "", "off", "enforce", "LEGACY"}) {
            Assert.assertThrows(IllegalArgumentException.class,
                                () -> ScriptSecurityMode.parse(invalid));
        }
    }

    @Test
    public void testRejectsAstTestBeforeItsSideEffect() throws Exception {
        String key = "hugegraph.script.policy.ast-test";
        String old = System.getProperty(key);
        try {
            System.clearProperty(key);
            assertRejected("@groovy.transform.ASTTest(value={ " +
                           "System.setProperty('" + key + "', 'executed') }) " +
                           "def value = 1; value");
            Assert.assertNull(System.getProperty(key));
        } finally {
            if (old == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, old);
            }
        }
    }

    @Test
    public void testRejectsDeclarationsAndAnnotations() throws Exception {
        assertRejected("class Hidden { static { throw new Error() } }; 1");
        assertRejected("def helper() { 1 }; helper()");
        assertRejected("@groovy.transform.Field int value = 1; value");
        assertRejected("@groovy.transform.CompileStatic class Hidden {}; 1");
        assertRejected("package hidden; 1");
        assertRejected("import static java.lang.System.exit; 1");
    }

    @Test
    public void testRetainsLocalControlFlowAndClosures() throws Exception {
        try (GroovyClassLoader loader = loader()) {
            Class<?> compiled = loader.parseClass(
                    "def values = []; for (int i = 0; i < 3; i++) { " +
                    "values.add(i) }; values.collect { it + 1 }.sum()");
            Script script = (Script) compiled.getDeclaredConstructor().newInstance();
            Assert.assertEquals(6, script.run());
        }
    }

    @Test
    public void testDisablesClasspathTransformsBeforeTheyExecute() throws Exception {
        Path directory = Files.createTempDirectory("hg-script-transform-");
        Path services = Files.createDirectories(directory.resolve("META-INF/services"));
        Path registration = services.resolve(
                "org.codehaus.groovy.transform.ASTTransformation");
        try {
            Files.writeString(registration, MarkerTransform.class.getName() +
                              "\n", StandardCharsets.UTF_8);
            try (URLClassLoader parent = new URLClassLoader(
                    new URL[]{directory.toUri().toURL()}, getClass().getClassLoader())) {
                MarkerTransform.EXECUTED.set(false);
                // Establish that this classpath really discovers the transform.
                try (GroovyClassLoader baseline = new GroovyClassLoader(parent)) {
                    baseline.parseClass("1 + 1");
                }
                Assert.assertTrue(MarkerTransform.EXECUTED.get());
                MarkerTransform.EXECUTED.set(false);
                CompilerConfiguration configuration = ScriptCompilerConfiguration.create(parent);
                try (GroovyClassLoader guarded = new GroovyClassLoader(parent, configuration)) {
                    guarded.parseClass("1 + 1");
                }
                Assert.assertFalse(MarkerTransform.EXECUTED.get());
            }
        } finally {
            MarkerTransform.EXECUTED.set(false);
            Files.deleteIfExists(registration);
            Files.deleteIfExists(services);
            Files.deleteIfExists(directory.resolve("META-INF"));
            Files.deleteIfExists(directory);
        }
    }

    @GroovyASTTransformation(phase = CompilePhase.CONVERSION)
    public static class MarkerTransform implements ASTTransformation {

        private static final AtomicBoolean EXECUTED = new AtomicBoolean();

        @Override
        public void visit(ASTNode[] nodes, SourceUnit source) {
            EXECUTED.set(true);
        }
    }

    private static void assertRejected(String source) throws Exception {
        try (GroovyClassLoader loader = loader()) {
            Exception error = Assert.assertThrows(Exception.class,
                                                  () -> loader.parseClass(source));
            Assert.assertTrue(error.toString(),
                              error.toString().contains("SCRIPT_SYNTAX_DENIED"));
        }
    }

    private static GroovyClassLoader loader() {
        CompilerConfiguration configuration = new CompilerConfiguration();
        configuration.addCompilationCustomizers(new ScriptSyntaxGuard());
        return new GroovyClassLoader(ScriptPolicyCompilationTest.class.getClassLoader(),
                                     configuration);
    }
}
