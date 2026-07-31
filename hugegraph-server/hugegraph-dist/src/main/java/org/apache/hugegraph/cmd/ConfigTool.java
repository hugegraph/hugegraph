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

package org.apache.hugegraph.cmd;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.Objects;

import org.apache.commons.configuration2.PropertiesConfiguration;
import org.apache.commons.configuration2.PropertiesConfiguration.IOFactory;
import org.apache.commons.configuration2.PropertiesConfiguration.PropertiesReader;
import org.apache.commons.configuration2.PropertiesConfiguration.PropertiesWriter;
import org.apache.commons.configuration2.builder.fluent.Configurations;
import org.apache.commons.configuration2.convert.ListDelimiterHandler;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.commons.configuration2.io.FileHandler;
import org.apache.commons.text.StringEscapeUtils;
import org.apache.hugegraph.config.HugeConfig;
import org.apache.hugegraph.config.ServerOptions;
import org.apache.hugegraph.dist.RegisterUtil;
import org.apache.hugegraph.util.E;

public final class ConfigTool {

    private static final String GET = "get";
    private static final String HAS = "has";
    private static final String SET = "set";
    private static final String SET_STDIN = "set-stdin";
    private static final String REQUIRES_LOCAL_ADMIN =
            "requires-local-admin";
    private static final String VALIDATE_SKIP = "validate-skip";
    private static final IOFactory EXACT_PROPERTIES_IO_FACTORY =
            new IOFactory() {

                @Override
                public PropertiesReader createPropertiesReader(Reader reader) {
                    return new PropertiesReader(reader);
                }

                @Override
                public PropertiesWriter createPropertiesWriter(
                                        Writer writer,
                                        ListDelimiterHandler delimiterHandler) {
                    return new PropertiesWriter(writer, delimiterHandler,
                                                ConfigTool::escapePropertyValue);
                }
            };

    private ConfigTool() {
    }

    public static void main(String[] args) throws Exception {
        E.checkArgument(args.length >= 2, "Usage: ConfigTool <command> ...");

        String command = args[0];
        String file = args[1];
        switch (command) {
            case GET:
                E.checkArgument(args.length == 3,
                                "Usage: ConfigTool get <file> <key>");
                String value = getProperty(file, args[2]);
                if (value != null) {
                    // CHECKSTYLE:OFF
                    System.out.print(value);
                    // CHECKSTYLE:ON
                }
                break;
            case HAS:
                E.checkArgument(args.length == 3,
                                "Usage: ConfigTool has <file> <key>");
                if (!hasProperty(file, args[2])) {
                    System.exit(1);
                }
                break;
            case SET:
                E.checkArgument(args.length == 4,
                                "Usage: ConfigTool set <file> <key> <value>");
                setProperty(file, args[2], args[3]);
                break;
            case SET_STDIN:
                E.checkArgument(args.length == 3,
                                "Usage: ConfigTool set-stdin <file> <key>");
                String stdinValue = new String(System.in.readAllBytes(),
                                               StandardCharsets.UTF_8);
                setProperty(file, args[2], stdinValue);
                break;
            case REQUIRES_LOCAL_ADMIN:
                E.checkArgument(args.length == 2,
                                "Usage: ConfigTool requires-local-admin " +
                                "<rest-server.properties>");
                if (!requiresLocalAdmin(file)) {
                    System.exit(1);
                }
                break;
            case VALIDATE_SKIP:
                E.checkArgument(args.length == 2,
                                "Usage: ConfigTool validate-skip " +
                                "<rest-server.properties>");
                validateSkip(file);
                break;
            default:
                throw new IllegalArgumentException("Unknown command: " + command);
        }
    }

    static String getProperty(String file, String key)
                              throws ConfigurationException {
        Object value = load(file).getProperty(key);
        if (value == null) {
            return null;
        }
        E.checkArgument(!(value instanceof Collection),
                        "Property '%s' must contain one value, got '%s'",
                        key, value);
        return value.toString();
    }

    static boolean hasProperty(String file, String key)
                               throws ConfigurationException {
        return load(file).containsKey(key);
    }

    static void setProperty(String file, String key, String value)
                            throws ConfigurationException, IOException {
        PropertiesConfiguration config = load(file);
        Object current = config.getProperty(key);
        if (!(current instanceof Collection) &&
            Objects.equals(current, value)) {
            return;
        }

        Path target = new File(file).toPath().toAbsolutePath();
        rejectIncludes(target);
        config.setProperty(key, value);
        config.setIOFactory(EXACT_PROPERTIES_IO_FACTORY);
        Path parent = target.getParent();
        E.checkState(parent != null, "Config file has no parent: %s", file);
        Path scratch = Files.createTempFile(parent,
                                            target.getFileName() + ".tmp.",
                                            null);
        try {
            FileHandler handler = new FileHandler(config);
            handler.save(scratch.toFile());
            replaceInPlace(target, scratch, ConfigTool::copyFile);
        } finally {
            Files.deleteIfExists(scratch);
        }
    }

    private static void rejectIncludes(Path target) throws IOException {
        String include = PropertiesConfiguration.getInclude();
        String optional = PropertiesConfiguration.getIncludeOptional();
        try (Reader input = Files.newBufferedReader(target,
                                                     StandardCharsets.UTF_8)) {
            PropertiesReader reader = new PropertiesReader(input);
            while (reader.nextProperty()) {
                String key = reader.getPropertyName();
                if (key.equalsIgnoreCase(include) ||
                    key.equalsIgnoreCase(optional)) {
                    throw new IllegalStateException(String.format(
                            "Refusing to rewrite '%s': '%s' directives would " +
                            "be flattened; update the included file instead",
                            target, key));
                }
            }
        }
    }

    private static void replaceInPlace(Path target, Path replacement,
                                       FileCopier copier) throws IOException {
        Path parent = target.getParent();
        Path backup = Files.createTempFile(parent,
                                           target.getFileName() + ".bak.",
                                           null);
        boolean retainBackup = false;
        try {
            Files.copy(target, backup,
                       java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            try {
                copier.copy(replacement, target);
            } catch (IOException writeFailure) {
                try {
                    copyFile(backup, target);
                } catch (IOException restoreFailure) {
                    retainBackup = true;
                    writeFailure.addSuppressed(restoreFailure);
                    throw new IOException(String.format(
                            "Failed to rewrite '%s'; recovery also failed, " +
                            "backup retained at '%s'", target, backup),
                                          writeFailure);
                }
                throw writeFailure;
            }
        } finally {
            if (!retainBackup) {
                Files.deleteIfExists(backup);
            }
        }
    }

    private static void copyFile(Path source, Path target) throws IOException {
        try (InputStream input = Files.newInputStream(source);
             OutputStream output = Files.newOutputStream(
                     target, StandardOpenOption.WRITE,
                     StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buffer = new byte[8192];
            int length;
            while ((length = input.read(buffer)) != -1) {
                output.write(buffer, 0, length);
            }
        }
    }

    @FunctionalInterface
    private interface FileCopier {

        void copy(Path source, Path target) throws IOException;
    }

    static boolean requiresLocalAdmin(String file) {
        RegisterUtil.registerServer();
        HugeConfig config = new HugeConfig(file);
        return InitStore.requiresLocalBuiltinAdmin(config);
    }

    static void validateSkip(String file) {
        RegisterUtil.registerServer();
        HugeConfig config = new HugeConfig(file);
        E.checkArgument(!config.get(ServerOptions.INIT_STORE_ENABLED),
                        "'%s' must be false for skip validation",
                        ServerOptions.INIT_STORE_ENABLED.name());
        InitStore.checkAdminBootstrapReachable(config, file, false);
    }

    private static PropertiesConfiguration load(String file)
                                                throws ConfigurationException {
        return new Configurations().properties(new File(file));
    }

    private static Object escapePropertyValue(Object value) {
        String escaped = StringEscapeUtils.escapeJava(String.valueOf(value));
        int leadingSpaces = 0;
        while (leadingSpaces < escaped.length() &&
               escaped.charAt(leadingSpaces) == ' ') {
            leadingSpaces++;
        }
        if (leadingSpaces == 0) {
            return escaped;
        }

        StringBuilder result = new StringBuilder(escaped.length() +
                                                 leadingSpaces * 5);
        for (int i = 0; i < leadingSpaces; i++) {
            result.append("\\u0020");
        }
        return result.append(escaped.substring(leadingSpaces)).toString();
    }
}
