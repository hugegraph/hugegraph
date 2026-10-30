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

package org.apache.hugegraph.security.script;

/** Optional, fixed Server capabilities; Store must not depend on Server core. */
final class ScriptHostTypes {

    private static final Class<?> GRAPH = load("org.apache.hugegraph.HugeGraph");
    private static final Class<?> SCHEMA_MANAGER = load("org.apache.hugegraph.schema.SchemaManager");

    private ScriptHostTypes() {
    }

    static Class<?> graph() {
        return GRAPH;
    }

    static Class<?> schemaManager() {
        return SCHEMA_MANAGER;
    }

    static boolean isGraph(Object value) {
        return GRAPH != null && GRAPH.isInstance(value);
    }

    static boolean isSchemaManager(Object value) {
        return SCHEMA_MANAGER != null && SCHEMA_MANAGER.isInstance(value);
    }

    private static Class<?> load(String name) {
        try {
            return Class.forName(name, false, ScriptHostTypes.class.getClassLoader());
        } catch (ClassNotFoundException absent) {
            return null;
        }
    }
}
