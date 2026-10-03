# Cypher compatibility

This note records the Cypher behavior verified for this change. It does not claim full openCypher or Neo4j
compatibility. The runtime used Java 17.0.20.1, TinkerPop 3.8.1, `org.opencypher.gremlin:translation:1.0.4`,
and RocksDB, based on `hugegraph/hugegraph@27a7c9b42274d6d4f95eabed6d2051d393ae0eaf`.

## Request forms

The endpoint is `/graphspaces/{graphspace}/graphs/{graph}/cypher`.

| Method and content type | Request | Verified behavior |
| --- | --- | --- |
| GET | `?cypher=<URL-encoded statement>` | Query-string form works: `testGet` |
| POST `application/json` | Raw Cypher text | Legacy raw-body form works: `testPost` |
| POST `text/plain` | Raw Cypher text | Raw text form works: `testPlainTextPost` |
| POST `application/json` | JSON object below | Separate bindings work: `testParameters` |

```json
{
  "cypher": "MATCH (n:person) WHERE n.name = $name RETURN n.name",
  "parameters": {"name": "marko"}
}
```

For the JSON-object form, `cypher` must be a nonblank string and `parameters`, when present, must be an
object. Omitting `parameters` means an empty map. Missing bindings produce an execution error; an explicit
null value is accepted. Tests also cover bindings named `id` and `label`, and the default limit of 16
parameters. Values containing quotes and newlines remain bound data rather than changing query text.
Check the HTTP status and body `status.code`: execution failures retain HTTP 200 with `status.code` 400
and null result data.

## Verified behavior

The API fixture uses a strong schema with a `SECONDARY` index on `city` and a `RANGE` index on `age`.
Acceptance tests verified:

| Area | Verified cases and test methods |
| --- | --- |
| Reads | Label scans, equality and range predicates, boolean combinations, directed one- and two-hop patterns, empty results: `testGet`, `testExactReadsAndPredicates`, `testRelationQuery` |
| Results | Aliases, scalar and node values, nested maps/lists, relationship ids, path shape, and null/missing-property semantics: `testReturnNodeIdAsPrimitiveValue`, `testReturnNodeDoesNotLeakInternalIdTypes`, `testReturnNestedIdDoesNotLeakInternalIdTypes`, `testReturnRelationIdDoesNotLeakInternalIdTypes`, `testReturnPathShape`, `testNullAndMissingProperty` |
| Aggregation and pagination | `DISTINCT`, `count`/`sum`/`min`/`max`/`avg`, `ORDER BY`, `SKIP`, and `LIMIT`: `testDuplicatesDistinctAndPagination`, `testAggregates` |
| Parameters | String, number, boolean, null, empty and missing bindings; quoting/newline safety: `testParameters`, `testRequestPreservesQueryAndParameterValues`, `testRequestAcceptsEmptyParameters`, `testAcceptCypherParameterNamesAndNullValues`, `testEnforceParameterCountBoundary` |
| Writes | Vertex and edge `CREATE`, property `SET`, edge and vertex `DELETE`; state checked through native REST reads: `testCreate`, `testCreateSetAndDeleteWithNativeReadback` |
| Failures and routing | Invalid syntax, request shape, binding keys, schema values, authentication, and graph routing: `testInvalidRequests`, `testRejectInvalidQuery`, `testRejectInvalidBindingShapeAndKeys`, `testAuthenticationAndGraphRouting`, `testSpecifiedGraphRouting` |
| Failed write | A rejected multi-vertex create left no residue in native reads or in 32 later query/native-read checks: `testFailedWriteDoesNotLeakIntoLaterRequests` |

Write verification describes how acceptance was checked; clients are not required to issue native REST reads
after each write. A successful Cypher response alone was not used as proof of persisted state.

## Verification record

`CypherApiTest` passed 20/20, `CypherClientTest` 7/7, and `CypherOpProcessorTest` 4/4, with no skips.
Related Gremlin tests passed 10 cases with one inapplicable `testClearAndInit` skip for the non-shared
backend; Login tests passed 3/3. EditorConfig formatting and the root clean compile also passed. The runtime
API JAR used for verification had SHA-256
`cc99f670438306ae0df3ea902c4d4d98a1f5d27c989b081e2a8b0724b42555e2`.

Test sources: [`CypherApiTest`](../hugegraph-server/hugegraph-test/src/main/java/org/apache/hugegraph/api/CypherApiTest.java),
[`CypherClientTest`](../hugegraph-server/hugegraph-test/src/main/java/org/apache/hugegraph/api/cypher/CypherClientTest.java),
and [`CypherOpProcessorTest`](../hugegraph-server/hugegraph-test/src/main/java/org/apache/hugegraph/opencypher/CypherOpProcessorTest.java).

## Outside this compatibility target

Advanced constructs including `MERGE`, `OPTIONAL MATCH`, `WITH`, `UNWIND`, `UNION`, and variable-length paths,
as well as HStore, Bolt, cross-request transactions, and performance, were not verified by this change.
