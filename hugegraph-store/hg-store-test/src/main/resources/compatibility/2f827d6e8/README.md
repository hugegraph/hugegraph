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

# Legacy core rows for Store ingress tests

Source baseline: `hugegraph/hugegraph` commit
`2f827d6e8c9c62ae858f2fc122b3a192d015e2f4`, frozen core jar SHA-256
`e53a15206fcf2f9b6ee4ba30146a1fb9814d3bf9275bdfe3f861796706fb7ce4`.
Producer uses Java 11, UTF-8 and UTC.

## Complete BinarySerializer row fixtures

`core-rows.properties` stores 15 complete rows emitted by the original
`BinarySerializer` from the same frozen core jar. Names, values and row IDs
are hex; `type`, `ttl` and `columns` record the real BackendEntry metadata.
These are not manually assembled from scalar fixtures.

`LegacyRowFixtureWriter.java.txt` records the exact producer and schema.
A dynamic proxy supplies only schema lookup and a fixed clock; it throws on
unexpected graph calls. The real baseline HugeVertex/HugeEdge/HugeIndex,
BinarySerializer, and property implementations build each row. The producer
also validates every row using the original baseline reader. For OLAP it
exposes the protected original `parseVertexOlap` via a reader-only subclass;
it does not override any encoding methods.

Schema and expected values:

| Schema | Definition |
|---|---|
| Property 1 | `age`, INT SINGLE, value 7 |
| Property 2 | `scores`, INT LIST, value `[7, -1, 7]` |
| Property 3 | `groups`, INT SET, values `{-1, 7}` |
| Property 9 | `rank`, INT SINGLE OLAP_COMMON, value 42 |
| Property 16 | `weight`, LONG SINGLE, range-long index field |
| Property 17 | `name`, TEXT SINGLE, secondary index field |
| Vertex label 4 | `person`, TTL 60000, properties 1/2/3 |
| Vertex label 10 | `timeless`, no TTL, properties 1/2/3 |
| Edge label 5 | `knows`, TTL 60000, link 4→4, properties 1/2/3 |
| Edge label 11 | `untimed`, no TTL, link 4→4, properties 1/2/3 |
| Index labels 6/7/8 | secondary/range-int/range-long, base label 4 |
| Index labels 12/13/14 | secondary/range-int/range-long, base label 10 |
| Index label 15 | secondary, base label 4, hashed long string key |

Timed rows expire at `1609459260000`, with the producer clock fixed at
`1609459200000`. The vertex ID is 1 (untimed vertex 3). Outgoing/incoming
edges connect 1 and 2, with sort value `2026!x` (untimed edge has an empty
sort value). Index field values are `alice`, integer 7, long 7, or
`"long-field-".repeat(20)` for the hashed row. `index.system` uses the
original system vertex-label index with field value 4 and no persisted TTL.
`index.system.edge` similarly uses field value 5 and the full edge ID. Both
original Core writers receive a positive expiry yet persist no expiry suffix
or value. The old Store writer persists the same key plus the 13-byte value
envelope for both label index kinds.

The writer uses default BinarySerializer flags: key and index ID prefixes
enabled, partition prefix disabled. The OLAP column name is the single
vertex ID, and its value is the property ID followed by the untagged scalar.
The shared merged-row test supplies the existing vertex, matching the legacy
reader's first main row plus subsequent OLAP columns convention.

Compile the row producer against the isolated baseline core runtime classpath
and invoke `LegacyRowFixtureWriter <output-directory> <producer-source-file>`.
Use Java 11, UTF-8 and UTC as above. The captured full-row file SHA-256 is
`d90cfe0b349edb832bf7425b6b8e2ddb06b3eff3f29e834cf8363b0d2fb84662`.
The exact same licensed fixture and producer are provided to Store's test
resources so ingress tests consume the original bytes directly.

These complete row checks cover schema-driven property-group framing, TTL,
OLAP merging, ordinary/range/hashed/system indexes and their direct TTL
readers. They still do not prove every partition layout, storage transaction,
or RocksDB/HStore runtime path.

`property.OBJECT_UNSUPPORTED_COLLECTION` retains those original bytes only
for the skip-path test. Skipping must preserve the trailing marker and TTL
without invoking Kryo instantiation. It is not used as a readable property
compatibility assertion.


## Original Store index TTL rows

`store-index-rows.properties` comes from the frozen baseline struct jar's
original `BinaryElementSerializer.writeIndex`, using
`LegacyStoreIndexFixtureWriter.java.txt`. The producer also decodes each row
with that same original implementation and verifies expiration time.
It emits secondary/range-int/range-long user indexes and the system
vertex-label and edge-label indexes, each with expiration `1609459260000`.
The index schemas and IDs match the complete core-row fixtures above.

This original Store format places no expiration suffix in the key. Its
value is the exact 13-byte envelope `0x00 + Base64(big-endian long)`.
The shared schema reader must continue reading these old rows while the
new schema writer retains the pinned Server key/hash/TTL layout. The
still-supported tagged writer is separately checked against the original
Store bytes. The original system index persists TTL in this value envelope;
the original core system-index fixture does not persist TTL.

Compile and run this producer with the frozen struct jar first, no core jar,
and the module's runtime dependencies; invoke
`LegacyStoreIndexFixtureWriter <output-directory> <producer-source-file>`.
The captured fixture SHA-256 is
`e9e9c967884d0d91b6cf6d5eabf2050a9bad0fe7898d1c47b58535ebdf1b9431`.


## Existing IndexBuilder/query mismatch


`index-builder-contract.properties` records two actual pinned execution
paths. Run `LegacyStoreIndexBuilderProbe` against the original struct jar
first; then run `LegacyServerIndexQueryProbe` against the original core jar
in a separate process. The second appends the Server prefix to the same
licensed resource. The Store probe requires the pinned IndexBuilder's
analyzer dependencies even though this example builds a secondary index.

For TEXT property 17, index label 15, base vertex label 4, and
`"long-field-".repeat(20)`, Store's real `buildIndex` truncates the 220-character
value to `long-field-long-fiel` (20 characters). Its emitted key starts with
that shortened literal. Server's real `BinarySerializer.writeQuery`, using
`ConditionQuery.concatValues` over the full value, emits the hashed prefix
`53493a463a323234303534613200`. The old Store key does not match it.
This is an existing builder/query contract defect, not a fixture assembled
from separate guessed helper outputs. New writer/query alignment tests use
the captured original Server prefix, while retaining old Store row decoding.

The fixture SHA-256 is
`3be36ba02c4afd58aab383549b041368fab4b9e8e32474122457b5608970161b`.

The label TTL regression tests exercise actual Core `readIndex`, recovered
expiration, `expiredElementIds`, clone/reset and deletion encoding, then Core
append and re-read. Both deletion and append retain the exact original Store
key; Core's primitive encoder still emits the original empty value. The
shared storage writer retains the old envelope on that same key, keeping
label expiry available without introducing an expiry suffix or duplicate
candidate key.
