<!--
Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See the NOTICE file distributed with
this work for additional information regarding copyright ownership.
The ASF licenses this file to You under the Apache License, Version 2.0
(the "License"); you may not use this file except in compliance with
 the License. You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
-->

# Label index compatibility fixtures

The licensed `core-rows.properties` and `store-index-rows.properties` are
byte-identical copies of the canonical fixtures under
`hugegraph-struct/src/test/resources/compatibility/2f827d6e8`, whose README
contains full provenance and whose producers preserve the original pinned
`2f827d6e8c9c62ae858f2fc122b3a192d015e2f4` writers and readers.

Core fixture SHA-256: `d90cfe0b349edb832bf7425b6b8e2ddb06b3eff3f29e834cf8363b0d2fb84662`.
Store fixture SHA-256: `e9e9c967884d0d91b6cf6d5eabf2050a9bad0fe7898d1c47b58535ebdf1b9431`.

Core system vertex/edge label writers receive a positive element expiry but
emit empty values and stable keys. Original Store writers use those same
keys and the exact 13-byte `0x00 + Base64(big-endian long)` value envelope.
`BinarySerializerTest.testLegacyStoredLabelExpiryReadEliminateAndAppendKeys`
verifies reading those old Store values, recovering expired candidates,
clone/reset isolation, and eliminating/appending the exact original key.
