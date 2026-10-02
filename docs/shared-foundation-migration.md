# Shared foundations: 1.8.0 migration

The consolidation tracked in [#257](https://github.com/hugegraph/hugegraph/issues/257)
removes duplicated shared implementations from Server core. Core remains the
graph engine; struct becomes the canonical shared foundation. The 4.0 fork is
a reference for composition and delegation, not a replacement for community
behavior. Differences must be resolved from current callers, persisted data and
tests rather than choosing whichever implementation is easiest to transplant.

## Ownership and dependencies

```text
Server core ──> struct ──> common
Store core  ──> struct
PD service  ──> common
```

These arrows describe the shared-foundation boundary, not the complete Maven
dependency tree. Core still needs PD/Store clients for HStore execution; Store
still needs its network, PD client and storage libraries. Struct retains shared
index/analyzer and codec dependencies, including shaded Kryo where required.

| Capability | Canonical owner | Caller behavior |
|------------|-----------------|-----------------|
| IDs, schema metadata, type codes | struct | Import shared types |
| Queries, base elements, byte and property codecs | struct | Reuse implementation; adapt engine/backend behavior |
| Index construction and analyzers | struct | Preserve configured analysis and index results |
| Graph transactions, traversal, tasks, schema mutation | core | Operate on shared metadata and elements |
| PD-backed schema access, listeners and cache lifecycle | Store | Own `SchemaGraph`/`SchemaDriver` resources |
| JWT signing/verification and shared auth constants | common | Supply configuration; translate failures locally |
| RPC client/server configuration interfaces | common | Preserve existing RPC implementations and signatures |

`HugeGraphSupplier` is the shared access boundary. Core and Store implement it
without injecting the graph engine into struct. It currently includes schema,
configuration and clock capabilities; narrowing this contract is a follow-up,
not permission to duplicate its consumers.

Core elements wrap shared base elements. Property mutation, cloning, removal,
expiration and loading state propagate through that shared state. `BaseVertex`
owns adjacency; the engine keeps an identity cache of edge wrappers over the
same base edges, not another adjacency collection. Core serializers retain
engine/backend adapters and delegate shared encoding.

Shared index construction and OLAP selection accept caller-supplied schema
candidates and result sinks. Core retains transaction writes, backend capability
checks and index update orchestration. These execution responsibilities do not
justify a second index model or selection algorithm.

## Java API changes

Compile applications and internal integrations against the migrated artifacts.
There is no general compatibility package containing the removed core classes.

| Previous core entry | Shared entry or migration |
|---------------------|---------------------------|
| `org.apache.hugegraph.backend.id.*` | `org.apache.hugegraph.id.*` |
| `org.apache.hugegraph.schema.*` metadata | `org.apache.hugegraph.struct.schema.*` |
| `org.apache.hugegraph.backend.query.*` | `org.apache.hugegraph.query.*` |
| `org.apache.hugegraph.backend.store.Shard` | `org.apache.hugegraph.backend.Shard` |
| `org.apache.hugegraph.backend.store.BackendEntry.BackendColumn` | `org.apache.hugegraph.backend.BackendColumn` |
| `org.apache.hugegraph.structure.HugeIndex` | `org.apache.hugegraph.structure.Index` |
| `HugeGraph.sameAs(HugeGraph)` | `HugeGraph.sameAs(HugeGraphSupplier)` |
| Shared bytes/encoding in backend serializers | `org.apache.hugegraph.serializer.*` |
| Core `HugeException` | `org.apache.hugegraph.exception.HugeException` |
| `org.apache.hugegraph.SchemaGraph`/`SchemaDriver` | `org.apache.hugegraph.store.schema.*` |

This table identifies ownership changes; it is not a blanket package replacement.
Relocated types also change method descriptors that expose IDs, schema, queries
and indexes. Update implementations and call sites, then recompile every affected
integration against the matching 1.8.0 artifacts; import changes alone do not make
old compiled clients binary-compatible.

String IDs use Java's UTF-16 string order consistently, including IDs loaded
from UTF-8 bytes. Decoding an ID lazily no longer changes its comparison result;
this fixes the inherited Core comparator's inconsistent ordering for supplementary
Unicode characters. Persisted ID bytes are unchanged.

External implementations of `org.apache.hugegraph.HugeGraph` must change their
`sameAs` override parameter from `HugeGraph` to
`org.apache.hugegraph.HugeGraphSupplier`. Shared schema and queries receive this
supplier contract without needing a concrete graph engine, so identity comparison
must accept it too. `StandardHugeGraph` and `HugeGraphAuthProxy` use the same
signature. There is no compatibility overload for the old descriptor.
Custom `HugeElement` subclasses must provide shared `BaseElement` state through
`element()` and adapt shared properties through `wrapProperty`; do not restore
the removed protected engine state fields or maintain a second adjacency store.

Schema mutation builders and backend-specific serializers remain in core.
The shared `Shard` keeps its existing GraphSON type tag and fields; query decoding
also preserves the legacy fully qualified name. Toolchain
`org.apache.hugegraph.structure.graph.Shard` remains a separate REST DTO.
`BackendColumn` retains its mutable `name`/`value` byte-array fields. Its shared
implementation hashes array contents consistently with equality, replacing the
old core array-identity hash. Historical Kryo names need decoder aliases and
fixture verification; the byte fields themselves are unchanged. Store
`RocksDBSession.BackendColumn` remains a backend-specific adapter.
`GraphSerializer.writeIndex`/`readIndex` now accept/return shared
`org.apache.hugegraph.structure.Index`. Migrate implementations of this Java SPI
and their callers; no long-lived `HugeIndex` compatibility wrapper is retained.
Index hash-ID generation also belongs to the shared implementation.
`org.apache.hugegraph.rpc.RpcServiceConfig4Client` and
`org.apache.hugegraph.rpc.RpcServiceConfig4Server` now have one owner in common.
Their fully qualified names and signatures are unchanged; RPC implementations
and configuration values do not need a source migration. The duplicate core and
commons-rpc definitions are removed without adding a dependency edge.
`HugeVertex` and `HugeEdge` remain engine adapters; Store uses base elements.
`TokenGenerator` resides in common under `org.apache.hugegraph.auth`; callers
supply the signing secret and translate JWT failures into their existing service
responses. JWT dependencies are optional in common, so direct JWT consumers
must declare their required JWT libraries explicitly.

### Downstream classpath and exception migration

Distinct downstream behaviors also need distinct Java class names. The local
Toolchain handoff addresses these collisions without replacing client REST DTOs:

| Previous downstream entry | Replacement |
|---------------------------|-------------|
| Hubble `org.apache.hugegraph.exception.HugeException` | `org.apache.hugegraph.exception.HubbleException` |
| Client `org.apache.hugegraph.exception.NotSupportException` | `org.apache.hugegraph.exception.ClientNotSupportException` |
| Hubble's copy of `org.apache.hugegraph.license.MachineInfo` | Same fully qualified name supplied by `hugegraph-common` |

Update imports, constructor calls and exception handlers using these downstream
classes. `HubbleException` remains a `RuntimeException` with its string constructor;
`ClientNotSupportException` remains a `ClientException` with the existing
message/cause and message/arguments constructors. Do not redirect their catches
to struct's `HugeException` or `NotSupportException`: those are different
contracts. `MachineInfo` callers retain their imports and use the common
implementation instead of carrying another class with the same name.

Computer's `HgkvDirImpl` is an affected client-exception caller and must migrate
to `org.apache.hugegraph.exception.ClientNotSupportException`. These Toolchain
and Computer changes are local handoffs in this delivery, not published downstream
PRs. Their builds and packaged-classpath validation remain required before
claiming migration readiness. A source scan showing no duplicate fully qualified
names cannot prove the built jars have no stale or transitive collisions.

Loader's client-based REST boundary and independent client DTOs do not need a
wholesale conversion to struct. Migrate only the actual affected Java calls.

### Gremlin configuration imports

Replace `org.apache.hugegraph.backend.id.IdGenerator` with
`org.apache.hugegraph.id.IdGenerator` in custom Gremlin `classImports` and
scripts. The bundled default and Raft example configurations use the shared
class. Java compilation does not validate configuration-only class names, so
verify the packaged Gremlin service as well as application source imports.

## Data and runtime compatibility

### Persisted rows and transient query results

Server-persisted property rows use schema-driven values. Temporary Store shuffle
and aggregation results use tagged values. Shared readers select this mode from
the known producer/request plan; they do not guess from value bytes. Both modes
share the scalar codec and must preserve historical samples. Ordinary schema
index writers use the existing Server key/name-TTL layout. Store SYSTEM-label
indexes with positive expiry retain their historical stable key plus 13-byte
value envelope; the core SYSTEM-label writer retains its existing bytes. The
label-index reader recognizes that precisely bounded legacy expiry envelope.
This does not add a TTL suffix to those keys or change prefix deletion behavior.
Shared `Index.hasTtl()` follows the existing engine contract for system indexes;
the Store envelope is handled at the serializer boundary. Existing unsupported
index-only element reconstruction is not enabled by this consolidation.

Schema map decoding preserves the existing wire field names and ID identity.
Two decoder defects are corrected separately from type relocation: primary-key
and edge sort-key lists retain their order and duplicate entries rather than
passing through a set; redundant edge endpoint metadata is resolved consistently
regardless of map field order. Matching `links` and source/target fields are
accepted, legacy source/target-only maps remain readable, and conflicting or
malformed endpoint metadata is rejected. Ordered-schema and endpoint-map tests
cover these corrections, including generated vertex/edge IDs and query values.
These focused tests do not prove all distributed or packaged release gates.

### Metadata namespace

Store accepts the existing `pd.cluster` metadata namespace setting, defaulting
to `hg`. In Store `application.yml`, configure it as:

```yaml
pd:
  cluster: hg
```

The environment equivalent is `PD_CLUSTER`. It must match Server's `cluster`
when `usePD=true`, or the graph's `pd.cluster` when `usePD=false`. The namespace
applies to schema, graph configuration, cache watches and TTL-cleaner metadata.
One Store process cannot reuse its schema driver for conflicting namespaces.

Backend graph names keep the existing `graphspace/store/table` components, for
example `DEFAULT/hugegraph/g`; REST identity `DEFAULT-hugegraph` is not a metadata
key. Preserve these names and configure the namespace consistently on both sides.

### Upgrade contracts

Upgrade Server, PD and Store together to the matching release artifacts.
Mixed-version rolling upgrades are not a supported acceptance target for this
change. Back up data and configuration using the existing deployment procedure
before upgrading; this consolidation introduces no data-rewrite operation.

Preserve existing type codes, ID and property bytes, index keys, TTL/OLAP layout,
query semantics and configuration defaults. Java API changes do not authorize
changes to storage tables or wire contracts.

`BaseVertex.TypeContext` makes the legacy classification context explicit:

- `STORAGE` retains Store's treatment of `~variables` as task data.
- `ENGINE` retains the engine's treatment of `~server` and `~role_data` as server
  data, and `~variables` as ordinary vertex data.
- Both retain `~task` and `~taskresult` as task data.

Engine wrappers must select `ENGINE`; standalone storage elements retain
`STORAGE`. This shared classifier preserves existing readers/table routing
instead of silently changing where system vertices are read or written.

Query JSON still contains Java names for some relation values. Decoder mappings
must read the historical names after type relocation. Replacing those names with
stable value tags is tracked in [#259](https://github.com/hugegraph/hugegraph/issues/259)
and requires an explicit compatibility policy; it is not part of a mechanical
import migration. Keep existing Kryo decoding where historical values require it.

## Validation and release prerequisites

The ownership description above is not a claim that all release gates have
passed. Review the actual commit, test reports and built distribution before
releasing. Required evidence includes:

- Canonical shared tests plus frozen pre-migration byte/JSON fixtures, including
  IDs, schema, properties, indexes, pagination, TTL/OLAP and supported Kryo data.
- RocksDB and HStore execution, Store filtering/schema access, authentication,
  element state propagation and affected downstream tests.
- Resolved dependency trees excluding core from PD/Store and PD client/core from
  struct; built distribution checks for duplicate HugeGraph classes.
- Startup of the packaged services, not just source compilation, with no classpath
  ordering workaround for duplicate classes.
- Independent review and CI checks preventing reintroduction of duplicate owners
  or forbidden dependencies.

Build and test commands and service prerequisites are maintained in
[BUILDING](BUILDING.md), module guidance and CI. Ensure tests actually run;
`-DskipTests` is not compatibility evidence.

## Follow-up work and website documentation handoff

[Narrow supplier capabilities](https://github.com/hugegraph/hugegraph/issues/258)
and [stable query value tags](https://github.com/hugegraph/hugegraph/issues/259)
have concrete source evidence and acceptance criteria. Code TODOs must reference
an unambiguous issue and an actionable remaining improvement. They cannot defer
required de-duplication or historical-data validation.

Website upgrade/developer documentation needs the Java migration table,
coordinated Server/PD/Store upgrade requirement, historical-data compatibility
checks and ownership summary above. This document is the local source for that
paired update. Publication in this round is restricted to `hugegraph/hugegraph`;
no `apache/hugegraph-doc` PR is published here. The required paired website PR
and coordinated merge remain unmet prerequisites for an upstream release/merge,
even if the fork PR passes its own tests. Do not label it upstream release-ready
until that documentation gate is satisfied.
