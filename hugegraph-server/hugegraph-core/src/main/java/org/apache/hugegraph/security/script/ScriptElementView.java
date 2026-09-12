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

import java.util.Collections;
import java.util.Map;

/** Read-only Store filter data, with no reference to the backing element. */
public final class ScriptElementView {

    private final String id;
    private final String label;
    private final Map<String, Object> properties;

    @SuppressWarnings("unchecked")
    public ScriptElementView(String id, String label, Map<String, Object> properties) {
        this.id = id;
        this.label = label;
        this.properties = Collections.unmodifiableMap((Map<String, Object>) ScriptBindings.data(properties));
    }

    public String id() {
        return this.id;
    }

    public String label() {
        return this.label;
    }

    public Object property(String key) {
        return this.properties.get(key);
    }

    public Map<String, Object> properties() {
        return this.properties;
    }
}
