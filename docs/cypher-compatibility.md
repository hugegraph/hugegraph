# Cypher compatibility

This note records the Cypher behavior verified for this change. It does not claim full openCypher or Neo4j
compatibility. The runtime used Java 17.0.20.1, TinkerPop 3.8.1, `org.opencypher.gremlin:translation:1.0.4`,
and RocksDB, rebased onto the Apache master that includes the Java 17 / TinkerPop 3.8.1 upgrade.

## Request forms

The endpoint is `/graphspaces/{graphspace}/graphs/{graph}/cypher`.

| Method and content type | Request | Verified behavior |
| --- | --- | --- |
| GET | `?cypher=<URL-encoded statement>` | Query-string form works: `testGet` |
| POST `application/json` | Raw Cypher text | Legacy raw-body form works: `testPost` |
| POST `text/plain` | Any body | Rejected with HTTP 415: `testRejectPlainTextPost` |
| POST `application/json` | JSON object below | Separate bindings work: `testParameters` |

```json
{
  "cypher": "MATCH (n:person) WHERE n.name = $name RETURN n.name",
  "parameters": {"name": "marko"}
}
```

POST bodies containing JSON arrays, strings, numbers, booleans, or null are rejected with HTTP 400.
Malformed JSON objects and trailing tokens are also request errors and do not fall back to raw Cypher.

For the JSON-object form, `cypher` must be a nonblank string and `parameters`, when present, must be an
object. Omitting `parameters` means an empty map. Missing bindings produce an execution error; an explicit
null value is accepted. Tests also cover bindings named `id` and `label`, and the default limit of 16
parameters. Values containing quotes and newlines remain bound data rather than changing query text.
The translator's reserved null-marker string `"  cypher.null"` (two leading spaces) is rejected in binding
values, including nested maps and lists, to avoid silently converting a user string to null. Actual JSON
null remains accepted. The same marker in query literals or stored properties is an existing translator
limitation and is outside this compatibility target.
Check the HTTP status and body `status.code`: execution failures retain HTTP 200 with `status.code` 400
and null result data.

Traversal iteration failures, including Java errors, attempt rollback on the execution thread and return
a terminal failure response. A rollback exception is attached to the original failure.

## Verified behavior

The API fixture uses a strong schema with a `SECONDARY` index on `city` and a `RANGE` index on `age`.
Acceptance tests verified:

| Area | Verified cases and test methods |
| --- | --- |
| Reads | Label scans, equality and range predicates, computed regex full-string matching, boolean combinations, directed one- and two-hop patterns, empty results: `testGet`, `testExactReadsAndPredicates`, `testComputedRegexExecutesExtensionPredicate`, `testComputedRegexRequiresWholeStringMatch`, `testRelationQuery` |
| Results | Aliases, scalar and node values, nested maps/lists, relationship ids, path shape, and null/missing-property semantics: `testReturnNodeIdAsPrimitiveValue`, `testReturnNodeDoesNotLeakInternalIdTypes`, `testReturnNestedIdDoesNotLeakInternalIdTypes`, `testReturnRelationIdDoesNotLeakInternalIdTypes`, `testReturnPathShape`, `testNullAndMissingProperty` |
| Aggregation and pagination | `DISTINCT`, `count`/`sum`/`min`/`max`/`avg`, `ORDER BY`, `SKIP`, and `LIMIT`: `testDuplicatesDistinctAndPagination`, `testAggregates` |
| Parameters | String, number, boolean, null, empty and missing bindings; quoting/newline safety: `testParameters`, `testRequestPreservesQueryAndParameterValues`, `testRequestAcceptsEmptyParameters`, `testAcceptCypherParameterNamesAndNullValues`, `testEnforceParameterCountBoundary` |
| Writes | Vertex and edge `CREATE`, property `SET`, edge and vertex `DELETE`; state checked through native REST reads: `testCreate`, `testCreateSetAndDeleteWithNativeReadback` |
| Failures and routing | Invalid syntax, request shape, binding keys, schema values, authentication, and graph routing: `testInvalidRequests`, `testRejectInvalidQuery`, `testRejectInvalidBindingShapeAndKeys`, `testAuthenticationAndGraphRouting`, `testSpecifiedGraphRouting` |
| Failed write | A rejected multi-vertex create left no residue in native reads or in 32 later query/native-read checks: `testFailedWriteDoesNotLeakIntoLaterRequests` |

Write verification describes how acceptance was checked; clients are not required to issue native REST reads
after each write. A successful Cypher response alone was not used as proof of persisted state.

## Verification record

The final verification covers the rebased tree, including non-object JSON request rejection, the
unsupported `text/plain` media type, computed regex fixture cases, and fatal-error rollback and response.
`CypherApiTest` passed all 23 cases, `CypherClientTest` all seven, and `CypherOpProcessorTest` all eight,
with no Cypher skips. The related predicate and Gremlin HTTP ownership/context tests also passed.
`AbstractRestClientTest` passed all nine cases, including charset serialization and an ASCII-default JVM.
Gremlin API tests passed ten cases with one existing non-shared-backend skip; Login API tests passed all
three. EditorConfig formatting, the root clean compile, and reactor installation passed on Java 17.
The live RocksDB server reported Gremlin 3.8.1; its packaged API JAR and compiled target JAR were
byte-identical before the API run.

Test sources: [`CypherApiTest`](../hugegraph-server/hugegraph-test/src/main/java/org/apache/hugegraph/api/CypherApiTest.java),
[`CypherClientTest`](../hugegraph-server/hugegraph-test/src/main/java/org/apache/hugegraph/api/cypher/CypherClientTest.java),
and [`CypherOpProcessorTest`](../hugegraph-server/hugegraph-test/src/main/java/org/apache/hugegraph/opencypher/CypherOpProcessorTest.java).

## Outside this compatibility target

Advanced constructs including `MERGE`, `OPTIONAL MATCH`, `WITH`, `UNWIND`, `UNION`, and variable-length paths,
as well as HStore, Bolt, cross-request transactions, and performance, were not verified by this change.
