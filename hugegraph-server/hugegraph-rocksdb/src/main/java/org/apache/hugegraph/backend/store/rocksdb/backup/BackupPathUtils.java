/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hugegraph.backend.store.rocksdb.backup;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

public final class BackupPathUtils {

    private BackupPathUtils() {
    }

    public static String requireComponent(String value, String description) {
        if (value == null || value.isEmpty() || value.indexOf('\u0000') >= 0 ||
            value.indexOf('/') >= 0 || value.indexOf('\\') >= 0 ||
            value.indexOf(':') >= 0 || value.equals(".") || value.equals("..")) {
            throw new IllegalArgumentException("Invalid " + description + ": " + value);
        }
        try {
            Path path = Path.of(value);
            if (path.isAbsolute() || path.getNameCount() != 1 ||
                !path.normalize().equals(path)) {
                throw new IllegalArgumentException("Invalid " + description +
                                                   ": " + value);
            }
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("Invalid " + description +
                                               ": " + value, e);
        }
        return value;
    }

    public static Path resolve(Path parent, String child, String description) {
        String component = requireComponent(child, description);
        Path normalizedParent = parent.toAbsolutePath().normalize();
        Path resolved = normalizedParent.resolve(component).normalize();
        if (!resolved.startsWith(normalizedParent)) {
            throw new IllegalArgumentException("Invalid " + description +
                                               ": " + child);
        }
        return resolved;
    }
}
