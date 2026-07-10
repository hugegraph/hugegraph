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

package org.apache.hugegraph.unit.config;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.hugegraph.backend.id.EdgeId;
import org.apache.hugegraph.backend.id.IdGenerator;
import org.apache.hugegraph.testutil.Assert;
import org.apache.hugegraph.traversal.optimize.ConditionP;
import org.apache.hugegraph.unit.BaseUnitTest;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.MutablePath;
import org.apache.tinkerpop.gremlin.server.Settings;
import org.apache.tinkerpop.gremlin.util.MessageSerializer;
import org.apache.tinkerpop.gremlin.util.message.ResponseMessage;
import org.apache.tinkerpop.gremlin.util.message.ResponseStatusCode;
import org.apache.tinkerpop.gremlin.util.ser.MessageTextSerializer;
import org.junit.Test;
import org.yaml.snakeyaml.Yaml;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

public class GremlinConfigCompatibilityTest extends BaseUnitTest {

    private static final Pattern CLASS_NAME =
            Pattern.compile("className:\\s*([^,}\\s]+)");
    private static final String SERIALIZER_PACKAGE =
            "org.apache.tinkerpop.gremlin.util.ser.";
    private static final String GRAPHSON_UNTYPED_V1 =
            SERIALIZER_PACKAGE + "GraphSONUntypedMessageSerializerV1";
    private static final String IO_REGISTRY =
            "org.apache.hugegraph.io.HugeGraphIoRegistry";
    private static final String GREMLIN_SERVER_CONFIG = "gremlin-server.yaml";
    private static final String REMOTE_OBJECTS_CONFIG = "remote-objects.yaml";
    private static final List<String> GREMLIN_SERVER_CONFIG_VARIANTS =
            Arrays.asList(
                    "static/conf/gremlin-server.yaml",
                    "travis/conf-raft1/gremlin-server.yaml",
                    "travis/conf-raft2/gremlin-server.yaml",
                    "travis/conf-raft3/gremlin-server.yaml"
            );
    private static final List<String> REMOTE_CONFIGS = Arrays.asList(
            "gremlin-driver-settings.yaml",
            "remote.yaml",
            REMOTE_OBJECTS_CONFIG
    );
    private static final List<String> TYPED_FALLBACK_SERIALIZERS =
            Arrays.asList(
                    SERIALIZER_PACKAGE + "GraphSONMessageSerializerV1",
                    SERIALIZER_PACKAGE + "GraphSONMessageSerializerV2",
                    SERIALIZER_PACKAGE + "GraphSONMessageSerializerV3"
            );
    private static final List<String> TYPED_GRAPHSON_MIME_TYPES =
            Arrays.asList(
                    "application/vnd.gremlin-v1.0+json",
                    "application/vnd.gremlin-v2.0+json",
                    "application/vnd.gremlin-v3.0+json"
            );
    private static final List<String> UNTYPED_GRAPHSON_MIME_TYPES =
            Arrays.asList(
                    "application/vnd.gremlin-v1.0+json;types=false",
                    "application/vnd.gremlin-v2.0+json;types=false",
                    "application/vnd.gremlin-v3.0+json;types=false"
            );

    @Test
    public void testGremlinServerSerializersUseTinkerPopUtilPackage() throws IOException {
        String content = readConfig(GREMLIN_SERVER_CONFIG);

        assertUsesHugeGraphIoRegistry(GREMLIN_SERVER_CONFIG, content);
        assertSerializerClassNamesUseUtilPackage(GREMLIN_SERVER_CONFIG,
                                                 content);
    }

    @Test
    public void testRemoteSerializersUseTinkerPopUtilPackage() throws IOException {
        for (String file : REMOTE_CONFIGS) {
            String content = readConfig(file);

            assertUsesHugeGraphIoRegistry(file, content);
            assertSerializerClassNamesUseUtilPackage(file, content);
        }
    }

    @Test
    public void testConfiguredSerializerClassesAreLoadable() throws Exception {
        assertConfiguredSerializerClassesAreLoadable(
                GREMLIN_SERVER_CONFIG, readConfig(GREMLIN_SERVER_CONFIG));
        for (String file : REMOTE_CONFIGS) {
            assertConfiguredSerializerClassesAreLoadable(file,
                                                         readConfig(file));
        }
    }

    @Test
    public void testGremlinServerConfigVariantsSupportGraphSONMimeTypes()
            throws Exception {
        Path assembly = serverAssemblyPath();

        for (String variant : GREMLIN_SERVER_CONFIG_VARIANTS) {
            Settings settings = Settings.read(assembly.resolve(variant)
                                                   .toString());

            assertSupportsTypedAndUntypedGraphSONMimeTypes(variant,
                                                           graphSONMimeTypes(settings));
        }
    }

    private static void assertSupportsTypedAndUntypedGraphSONMimeTypes(
            String fileName, Map<String, String> graphSONMimeTypes) {
        for (String mimeType : UNTYPED_GRAPHSON_MIME_TYPES) {
            Assert.assertTrue(fileName + " should support untyped " +
                              "GraphSON MIME " + mimeType,
                              graphSONMimeTypes.containsKey(mimeType));
        }
        for (String mimeType : TYPED_GRAPHSON_MIME_TYPES) {
            Assert.assertTrue(fileName + " should support typed GraphSON " +
                              "MIME " + mimeType,
                              graphSONMimeTypes.containsKey(mimeType));
        }
        Assert.assertEquals(fileName + " should keep application/json " +
                            "mapped to the untyped V1 serializer",
                            GRAPHSON_UNTYPED_V1,
                            graphSONMimeTypes.get("application/json"));
    }

    @Test
    public void testConfiguredGraphSONSerializersCanSerializeHugeGraphTypes()
            throws Exception {
        Settings settings = readGremlinServerSettings();
        List<String> typedSerializers = new ArrayList<>();
        boolean foundUntyped = false;

        for (Settings.SerializerSettings serializerSettings :
             settings.serializers) {
            if (!serializerSettings.className.startsWith(SERIALIZER_PACKAGE +
                                                         "GraphSON")) {
                continue;
            }

            MessageTextSerializer<?> serializer =
                    newTextSerializer(serializerSettings.className);
            serializer.configure(config(serializerSettings.config),
                                 Collections.emptyMap());
            boolean typed = !serializerSettings.className.startsWith(
                    SERIALIZER_PACKAGE + "GraphSONUntyped");
            if (typed) {
                typedSerializers.add(serializerSettings.className);
            } else {
                foundUntyped = true;
            }
            assertCanSerializeHugeGraphTypes(
                    serializer,
                    typed && usesStableGraphSONTypes(
                            serializerSettings.className));
        }

        Assert.assertTrue("No untyped GraphSON serializer settings found in " +
                          GREMLIN_SERVER_CONFIG, foundUntyped);
        Assert.assertEquals("Configured typed GraphSON serializers should " +
                            "match the fallback set",
                            TYPED_FALLBACK_SERIALIZERS, typedSerializers);
    }

    @Test
    public void testConfiguredGraphBinarySerializersCanSerializeConditionPPredicates()
            throws Exception {
        Settings settings = readGremlinServerSettings();
        boolean found = false;

        for (Settings.SerializerSettings serializerSettings :
             settings.serializers) {
            if (!serializerSettings.className.startsWith(SERIALIZER_PACKAGE +
                                                         "GraphBinary")) {
                continue;
            }

            MessageSerializer<?> serializer =
                    newMessageSerializer(serializerSettings.className);
            serializer.configure(config(serializerSettings.config),
                                 Collections.emptyMap());
            assertCanSerializeBinaryConditionPredicates(serializer);
            found = true;
        }

        Assert.assertTrue("No GraphBinary serializer settings found in " +
                          GREMLIN_SERVER_CONFIG, found);
    }

    @Test
    public void testTypedFallbackSerializersPreserveConditionPPredicateNames()
            throws Exception {
        Map<String, Object> config = graphSONV1Config(readGremlinServerSettings());

        for (String serializer : TYPED_FALLBACK_SERIALIZERS) {
            MessageTextSerializer<?> textSerializer = newTextSerializer(serializer);

            textSerializer.configure(config(config), Collections.emptyMap());
            assertSerializesConditionPredicateNames(textSerializer);
        }
    }

    @Test
    public void testRemoteObjectsSerializerCanSerializePathShape()
            throws Exception {
        RemoteSerializerSettings settings =
                readRemoteSerializerSettings(REMOTE_OBJECTS_CONFIG);
        MessageTextSerializer<?> serializer =
                newTextSerializer(settings.className);

        serializer.configure(config(settings.config), Collections.emptyMap());

        String json = serializeResponse(serializer, testPath());

        Assert.assertContains("\"labels\"", json);
        Assert.assertContains("\"objects\"", json);
        Assert.assertContains("marko", json);
        Assert.assertContains("lop", json);
        Assert.assertContains("\"a\"", json);
        Assert.assertContains("\"b\"", json);
        Assert.assertContains("\"software\"", json);
    }

    private static Settings readGremlinServerSettings() throws Exception {
        return Settings.read(configPath(GREMLIN_SERVER_CONFIG).toString());
    }

    private static String readConfig(String fileName) throws IOException {
        return Files.readString(configPath(fileName), StandardCharsets.UTF_8);
    }

    private static Path configPath(String fileName) {
        return findConfDir().resolve(fileName);
    }

    private static Path serverAssemblyPath() {
        return findConfDir().getParent().getParent();
    }

    private static Path findConfDir() {
        String configuredDir = System.getProperty("hugegraph.conf.dir");
        Path configuredPath = resolveConfiguredDir(configuredDir);
        if (configuredPath != null) {
            return configuredPath;
        }

        String envDir = System.getenv("HUGEGRAPH_CONF_DIR");
        Path envPath = resolveConfiguredDir(envDir);
        if (envPath != null) {
            return envPath;
        }

        Path userDir = Paths.get(System.getProperty("user.dir"));
        List<Path> candidates = new ArrayList<>();

        Path parent = userDir.getParent();
        if (parent != null) {
            candidates.add(parent.resolve("hugegraph-dist")
                                 .resolve("src")
                                 .resolve("assembly")
                                 .resolve("static")
                                 .resolve("conf"));
        }
        candidates.add(userDir.resolve("hugegraph-server")
                              .resolve("hugegraph-dist")
                              .resolve("src")
                              .resolve("assembly")
                              .resolve("static")
                              .resolve("conf"));

        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }

        Assert.fail(String.format("Can't find hugegraph-dist static conf from" +
                                  " %s (hugegraph.conf.dir=%s," +
                                  " HUGEGRAPH_CONF_DIR=%s, candidates=%s)",
                                  userDir, configuredDir, envDir, candidates));
        return userDir;
    }

    private static Path resolveConfiguredDir(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        Path configured = Paths.get(path);
        if (Files.isDirectory(configured)) {
            return configured;
        }
        return null;
    }

    private static void assertUsesHugeGraphIoRegistry(String fileName,
                                                      String content) {
        Assert.assertTrue(fileName + " should keep HugeGraphIoRegistry",
                          content.contains(IO_REGISTRY));
    }

    private static void assertSerializerClassNamesUseUtilPackage(
            String fileName, String content) {
        Assert.assertFalse(content.contains(
                "org.apache.tinkerpop.gremlin.driver.ser."));
        Assert.assertFalse(content.contains(
                "org.apache.tinkerpop.gremlin.server.ser."));

        Matcher matcher = CLASS_NAME.matcher(content);
        boolean found = false;
        while (matcher.find()) {
            found = true;
            String className = matcher.group(1);
            Assert.assertTrue(fileName + " has outdated serializer " +
                              className,
                              className.startsWith(SERIALIZER_PACKAGE));
        }
        Assert.assertTrue("No serializer className found in " + fileName,
                          found);
    }

    private static void assertConfiguredSerializerClassesAreLoadable(
            String fileName, String content) throws ClassNotFoundException {
        Matcher matcher = CLASS_NAME.matcher(content);
        boolean found = false;
        while (matcher.find()) {
            found = true;
            Class.forName(matcher.group(1));
        }
        Assert.assertTrue("No serializer className found in " + fileName,
                          found);
    }

    private static Map<String, String> graphSONMimeTypes(Settings settings)
            throws Exception {
        Map<String, String> mimeTypes = new HashMap<>();

        for (Settings.SerializerSettings serializerSettings :
             settings.serializers) {
            if (!serializerSettings.className.startsWith(SERIALIZER_PACKAGE +
                                                         "GraphSON")) {
                continue;
            }

            MessageSerializer<?> serializer =
                    newMessageSerializer(serializerSettings.className);
            for (String mimeType : serializer.mimeTypesSupported()) {
                mimeTypes.putIfAbsent(mimeType, serializerSettings.className);
            }
        }

        return mimeTypes;
    }

    private static MessageTextSerializer<?> newTextSerializer(String className)
            throws Exception {
        MessageSerializer<?> serializer = newMessageSerializer(className);

        Assert.assertTrue(className + " should be a MessageTextSerializer",
                          serializer instanceof MessageTextSerializer);
        return (MessageTextSerializer<?>) serializer;
    }

    private static MessageSerializer<?> newMessageSerializer(String className)
            throws Exception {
        Object serializer = Class.forName(className)
                                 .getDeclaredConstructor()
                                 .newInstance();

        Assert.assertTrue(className + " should be a MessageSerializer",
                          serializer instanceof MessageSerializer);
        return (MessageSerializer<?>) serializer;
    }

    private static String serializeResponse(MessageTextSerializer<?> serializer,
                                            Object result)
            throws Exception {
        ResponseMessage response = ResponseMessage.build(UUID.randomUUID())
                                                  .code(ResponseStatusCode.SUCCESS)
                                                  .result(result)
                                                  .create();

        return serializer.serializeResponseAsString(response,
                                                    ByteBufAllocator.DEFAULT);
    }

    private static byte[] serializeBinaryResponse(
            MessageSerializer<?> serializer, Object result)
            throws Exception {
        ResponseMessage response = ResponseMessage.build(UUID.randomUUID())
                                                  .code(ResponseStatusCode.SUCCESS)
                                                  .result(result)
                                                  .create();
        ByteBuf buffer = serializer.serializeResponseAsBinary(
                response, ByteBufAllocator.DEFAULT);
        try {
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            return bytes;
        } finally {
            buffer.release();
        }
    }

    @SuppressWarnings("unchecked")
    private static RemoteSerializerSettings readRemoteSerializerSettings(
            String fileName) throws IOException {
        try (InputStream input = Files.newInputStream(configPath(fileName))) {
            Map<String, Object> root = new Yaml().load(input);
            Map<String, Object> serializer =
                    (Map<String, Object>) root.get("serializer");

            Assert.assertNotNull("No serializer in " + fileName, serializer);
            String className = (String) serializer.get("className");
            Map<String, Object> config =
                    (Map<String, Object>) serializer.get("config");

            Assert.assertNotNull("No serializer className in " + fileName,
                                 className);
            Assert.assertNotNull("No serializer config in " + fileName,
                                 config);
            return new RemoteSerializerSettings(className, config);
        }
    }

    private static Map<String, Object> graphSONV1Config(Settings settings) {
        for (Settings.SerializerSettings serializer : settings.serializers) {
            if (GRAPHSON_UNTYPED_V1.equals(serializer.className)) {
                Assert.assertNotNull(serializer.config);
                return serializer.config;
            }
        }

        Assert.fail("No " + GRAPHSON_UNTYPED_V1 + " found in " +
                    GREMLIN_SERVER_CONFIG);
        return Collections.emptyMap();
    }

    private static Map<String, Object> config(Map<String, Object> config) {
        if (config == null) {
            return Collections.emptyMap();
        }
        return new HashMap<>(config);
    }

    private static org.apache.tinkerpop.gremlin.process.traversal.Path testPath() {
        return MutablePath.make()
                          .extend(IdGenerator.of("marko"), Set.of("a"))
                          .extend(IdGenerator.of("lop"),
                                  Set.of("b", "software"));
    }

    private static void assertCanSerializeHugeGraphTypes(
            MessageTextSerializer<?> serializer, boolean typed)
            throws Exception {
        Object id = IdGenerator.of("marko");
        Object uuidId = IdGenerator.of(
                UUID.fromString("3cfcafc8-7906-4ab7-a207-4ded056f58de"));
        Object edgeId = EdgeId.parse("S1>2>3>4>L6");
        String fileJson = serializeResponse(serializer, new File("test.text"));
        String idJson = serializeResponse(serializer, id);
        String uuidJson = serializeResponse(serializer, uuidId);
        String edgeJson = serializeResponse(serializer, edgeId);

        Assert.assertContains("\"file\"", fileJson);
        Assert.assertContains("test.text", fileJson);
        Assert.assertContains("marko", idJson);
        Assert.assertContains("3cfcafc8-7906-4ab7-a207-4ded056f58de",
                              uuidJson);
        Assert.assertContains("S1>2>3>4>L6", edgeJson);

        if (typed) {
            assertContainsGraphSONType(fileJson, "hugegraph:File");
            assertContainsGraphSONType(idJson, "hugegraph:StringId");
            assertContainsGraphSONType(uuidJson, "hugegraph:UuidId");
            assertContainsGraphSONType(edgeJson, "hugegraph:EdgeId");
        }
    }

    private static boolean usesStableGraphSONTypes(String serializer) {
        // GraphSON V1 uses legacy @class wrapping; assert stable
        // hugegraph:* @type names for V2/V3 typed fallback serializers.
        return !serializer.endsWith("GraphSONMessageSerializerV1");
    }

    private static void assertCanSerializeBinaryConditionPredicates(
            MessageSerializer<?> serializer) throws Exception {
        for (P<Object> predicate : conditionPredicates()) {
            Assert.assertTrue(serializeBinaryResponse(serializer,
                                                     predicate).length > 0);
        }
    }

    private static void assertSerializesConditionPredicateNames(
            MessageTextSerializer<?> serializer) throws Exception {
        String textContains = serializeResponse(serializer,
                                                conditionP(
                                                        ConditionP.textContains("ark")));
        String contains = serializeResponse(serializer,
                                            conditionP(
                                                    ConditionP.contains("marko")));
        String containsKey = serializeResponse(serializer,
                                               conditionP(
                                                       ConditionP.containsK("name")));
        String containsValue = serializeResponse(serializer,
                                                 conditionP(
                                                         ConditionP.containsV("marko")));
        String eq = serializeResponse(serializer,
                                      conditionP(ConditionP.eq("marko")));

        Assert.assertContains("textcontains", textContains);
        Assert.assertContains("contains", contains);
        Assert.assertContains("containsk", containsKey);
        Assert.assertContains("containsv", containsValue);
        Assert.assertContains("==", eq);
    }

    private static List<P<Object>> conditionPredicates() {
        return Arrays.asList(
                conditionP(ConditionP.textContains("ark")),
                conditionP(ConditionP.contains("marko")),
                conditionP(ConditionP.containsK("name")),
                conditionP(ConditionP.containsV("marko")),
                conditionP(ConditionP.eq("marko"))
        );
    }

    @SuppressWarnings("unchecked")
    private static P<Object> conditionP(ConditionP predicate) {
        return P.test(predicate.getBiPredicate(), predicate.getValue());
    }

    private static void assertContainsGraphSONType(String json,
                                                   String graphSONType) {
        Assert.assertContains("\"@type\"", json);
        Assert.assertContains(graphSONType, json);
    }

    private static final class RemoteSerializerSettings {

        private final String className;
        private final Map<String, Object> config;

        private RemoteSerializerSettings(String className,
                                         Map<String, Object> config) {
            this.className = className;
            this.config = config;
        }
    }
}
