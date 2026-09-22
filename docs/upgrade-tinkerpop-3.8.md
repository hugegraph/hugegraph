# TinkerPop 3.8.1 and Groovy 4 upgrade

This upgrade moves Server and the distributed modules from TinkerPop 3.5.1 to
3.8.1 and uses Groovy 4.0.25. Build and run every module with Java 17.

## Configuration and clients

Use the Gremlin configuration files shipped with the upgraded Server. Serializer
classes move from `org.apache.tinkerpop.gremlin.driver.ser` to
`org.apache.tinkerpop.gremlin.util.ser`. The supplied remote configurations
include the corresponding HugeGraph registries.

Untyped GraphSON remains first in the serializer list so ordinary JSON requests
keep their existing response shape. Typed GraphSON is available as a fallback
for the supported simple values and IDs; it is not a replacement for untyped
serialization of every HugeGraph internal schema or graph object.

GraphBinary uses `HugeGraphTypeSerializerRegistryBuilder`. Cypher results normalize
HugeGraph IDs to their underlying values, including values nested in collections,
maps and paths. Cyclic results and nesting deeper than 32 levels are rejected.

The default script engine remains Gremlin Groovy. This upgrade does not switch
remote requests to GremlinLang. Validate application scripts against the full
TinkerPop 3.5.1 to 3.8.1 upgrade interval before deploying.

## Queries

See [query count and filter behavior](query-semantics.md) for transaction counts
and local predicate filtering. Version-specific handling retains negated
predicates and filtering barriers where pushdown would change their meaning.
The new TinkerPop step APIs are adapted without discarding property metadata.

## Compatibility verification

The upgrade CI explicitly selects Structure and Process tests for Memory,
RocksDB and HStore. HStore has separate standard-process and feature-test steps.
A green build with those steps skipped is not compatibility evidence. Review
reports from the actual PR revision before deploying.
