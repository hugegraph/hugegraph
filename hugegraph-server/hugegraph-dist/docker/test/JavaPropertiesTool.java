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

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Properties;

public final class JavaPropertiesTool {

    private JavaPropertiesTool() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            throw new IllegalArgumentException(
                    "Expected <get|has|set> <file> <key> [value]");
        }

        String command = args[0];
        Path file = Paths.get(args[1]);
        String key = args[2];
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
        }

        switch (command) {
            case "get":
                String value = properties.getProperty(key);
                if (value != null) {
                    System.out.print(value);
                }
                return;
            case "has":
                if (!properties.containsKey(key)) {
                    System.exit(1);
                }
                return;
            case "set":
                if (args.length != 4) {
                    throw new IllegalArgumentException(
                            "Expected set <file> <key> <value>");
                }
                if (Objects.equals(properties.getProperty(key), args[3])) {
                    return;
                }
                properties.setProperty(key, args[3]);
                Path scratch = Files.createTempFile(
                        file.toAbsolutePath().getParent(),
                        file.getFileName() + ".tmp.", null);
                try {
                    try (OutputStream output = Files.newOutputStream(scratch)) {
                        properties.store(output, null);
                    }
                    try (InputStream input = Files.newInputStream(scratch);
                         OutputStream output = Files.newOutputStream(
                                 file, StandardOpenOption.WRITE,
                                 StandardOpenOption.TRUNCATE_EXISTING)) {
                        byte[] buffer = new byte[8192];
                        int length;
                        while ((length = input.read(buffer)) != -1) {
                            output.write(buffer, 0, length);
                        }
                    }
                } finally {
                    Files.deleteIfExists(scratch);
                }
                return;
            default:
                throw new IllegalArgumentException("Unknown command: " + command);
        }
    }
}
