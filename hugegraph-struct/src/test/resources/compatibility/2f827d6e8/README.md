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

# Pre-consolidation core codec fixtures

These fixed payloads were generated from `hugegraph/hugegraph` commit
`2f827d6e8c9c62ae858f2fc122b3a192d015e2f4`, using Java 11 and the original
`org.apache.hugegraph.backend.*` core implementations. They are not generated
by the canonical struct writer during a test run. The test checks both reading
old bytes and retaining writer byte compatibility.

`core-codecs.hex` contains 65 named byte payloads. Comment lines carry the ASF
license; remaining entries are `key=hex`, using standard properties line
continuations for large IDs. The four `.json.txt` files contain an ASF
comment prolog and final file newline around the exact UTF-8 output from the legacy
`ConditionQuery.bytes()`. The test removes those container boundaries before decoding.
`LegacyCodecFixtureWriter.java.txt` is the producer source; its extension keeps
legacy imports outside the test compilation unit.

## Reproduction

Use an isolated checkout of the pinned commit, never the migration worktree:

```sh
git clone https://github.com/hugegraph/hugegraph.git legacy-core
cd legacy-core
git checkout --detach 2f827d6e8c9c62ae858f2fc122b3a192d015e2f4
mvn install -pl hugegraph-server/hugegraph-core -am -DskipTests
mvn dependency:build-classpath -pl hugegraph-server/hugegraph-core \
    -Dmdep.outputFile=/tmp/legacy-core-classpath.txt
```

Copy the producer from this fixture directory to a temporary
`LegacyCodecFixtureWriter.java` outside that checkout. Compile with
`javac -proc:none -encoding UTF-8`, using the baseline core jar plus its Maven
runtime classpath. Run `LegacyCodecFixtureWriter <output-directory>` with
`java -Dfile.encoding=UTF-8 -Duser.timezone=UTC`. The output must reproduce the
fixture payloads. Keep the core jar first in the producer classpath; do not
include canonical struct classes or another HugeGraph core version.

For the tagged property fixtures, also build the pinned `hugegraph-struct`
module with `-am` and generate its Maven runtime classpath. Compile and run
`LegacyStructFixtureWriter` with the frozen struct jar first and no core jar.
It accepts `<output-directory> <producer-source-file>` and creates
`struct-tagged-properties.hex` with 12 original tagged payloads. Both tagged
and schema-driven tests compare the common scalar suffix to independently
captured core bytes.

The captured run used frozen baseline artifacts and logged the writer's
code source as `/tmp/hugegraph-baseline-codec-2f827d6e8/hugegraph-core-1.7.0.jar`.
The four relevant source files (`IdGenerator`, `EdgeId`, `BytesBuffer`, and
`ConditionQuery`) were byte-identical to `git show` of the pinned commit before
capture. Observed artifact SHA-256 values were:

- core: `e53a15206fcf2f9b6ee4ba30146a1fb9814d3bf9275bdfe3f861796706fb7ce4`
- common: `564059eaa40ab70025d0a16490167cf28c615060db3f5ccbaee27f85345158d1`
- struct: `27a2cc1265a91883a5195ca315f7eaaa47ed3e880005f234603ea9cbb7567350`

Jar hashes describe this captured build; Maven archive timestamps can change
on a fresh build. Payload reproduction, source commit, and producer source
are the durable provenance checks.

## Coverage and limits

Covered: signed varint/varlong limits and width boundaries; long, Unicode
string and UUID IDs, including string-header width changes and the 16 KiB
limit; incoming/outgoing edge IDs with parent and sublabel;
all scalar property data types including the original shaded Kryo fallback;
schema-driven SINGLE/LIST/SET properties; string and 4/8-byte range index IDs;
vertex query labels, numeric ranges, typed IN lists, date values, OR conditions,
limit/offset; edge query ownership, direction and page tokens; executable
COUNT(*)/AVG(age) strategies and their JSON aggregate shape; and original
Store tagged scalar/LIST/SET metadata recovery.

The range-index fixture input is an explicit type/label/value byte layout,
not the output of a full index builder. Scalar/query fixtures alone do not prove complete storage rows. The added
row fixtures below cover specific complete layouts; they do not prove
RocksDB/HStore transactions or query-runtime compatibility. Those require integration and storage fixtures.
The old JSON date representation has no time zone; capture and date assertions
use UTC without changing that protocol.

Core schema-driven properties are untagged; Store/struct properties use a
cardinality/type tag. They are separate wire entry points into the shared
scalar codec. This suite verifies both formats against their respective old writers and
does not interpret legacy core bytes as Store's tagged property format.

Kryo OBJECT fixture uses a mutable `ArrayList`. The original baseline writer
can encode `Arrays.asList`, but its own reader fails to instantiate
`java.util.Arrays$ArrayList`; this existing unsupported case is not a promise
of compatibility created by the migration. The baseline UNKNOWN String and
mutable ArrayList fixtures were also independently read by the frozen core
reader before accepting them.

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

The query date fixture preserves the legacy wall-time contract. A frozen
baseline reader probe produced epoch `1609459200123` in UTC and
`1609430400123` in Asia/Singapore from the same JSON date string
`2021-01-01 00:00:00.123`. The test computes expected local wall time without
changing the JVM default time zone after adapters have initialized.

## Relocated Kryo OBJECT descriptors

`core-kryo-objects.properties` captures 36 original core OBJECT payloads
without replacing any names or bytes. Each includes an explicit
`baseline_readable` receipt from the frozen original default reader.
`LegacyKryoFixtureWriter.java.txt` regenerates payloads and receipts using
the frozen core jar with its original shaded Kryo dependency.

Twenty-four payloads were readable before migration: relocated query and ID
enums, Userdata with string/int/date entries, Class literals, an actual
BackendColumn with byte-array fields, its populated array, moved-type
null-element arrays and matrices, and nested mutable maps/lists containing
these values. Tests verify canonical runtime types and values from the exact
old bytes, plus semantic new-writer roundtrips. New writes may emit canonical
class names; they are not required to preserve deprecated Java API names.

Twelve payloads could be written but the original default reader rejected
missing no-arg constructors: LongId/StringId/UuidId/EdgeId/BinaryId/Shard,
four schema instances, IdWithExpiredTime, and Arrays$ArrayList. Receipts retain those original
errors. These cases do not justify changing the general instantiation policy
or claiming that previously unreadable model instances were readable.
Tests instead verify that exact legacy class resolution reaches the same
constructor policy, without a ClassNotFound regression. Empty/null-element
arrays and Class literals of these types were genuinely readable and must
remain supported.

The fixture SHA-256 is
`45c10201a1f88b38771fd5bd4e609f461729ac1d5e8fb94304fa176bfd5cf369`.

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

Schema nested-class fixtures include the exact original TaskWithSchema and
all five P class literals, all four Builder interface literals, and typed
null-element arrays. Index
fixtures include original HugeIndex/IdWithExpiredTime class literals and
arrays. Actual IdWithExpiredTime decoding fails in the original default
reader and remains a recorded unsupported case. A live-graph HugeIndex
OBJECT instance is not claimed readable by this suite.

Descriptor lookup tests independently cover the JVM 255-dimension boundary
and reject 256/5000 dimensions before allocation; unknown and primitive
array descriptors remain delegated to the normal resolver. These checks
allocate no populated multidimensional arrays.

The label TTL regression tests exercise actual Core `readIndex`, recovered
expiration, `expiredElementIds`, clone/reset and deletion encoding, then Core
append and re-read. Both deletion and append retain the exact original Store
key; Core's primitive encoder still emits the original empty value. The
shared storage writer retains the old envelope on that same key, keeping
label expiry available without introducing an expiry suffix or duplicate
candidate key.
