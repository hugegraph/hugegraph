# Groovy script policies

Groovy policies are opt-in. Set the JVM system property
`hugegraph.script.security.mode` in the deployment's Java options before startup.
The mode remains fixed for the lifetime of the JVM.

| Mode | Behavior |
| --- | --- |
| `legacy` (default) | Keep the existing Groovy execution paths and authorization. |
| `policy` | Apply script policies without a SecurityManager; startup rejects an installed SecurityManager. |

Only these two values are accepted. The earlier development names `combined` and
`policy-only` are rejected at startup. To enable policies, set
`-Dhugegraph.script.security.mode=policy` on Server and on Store nodes that evaluate
scan filters. A missing property selects `legacy`; changing modes requires a restart.

The policies cover three execution profiles:

- `QUERY`: Gremlin requests and jobs, including authorized schema operations.
- `SCHEMA`: schema template execution through the existing SchemaManager builders.
- `STORE_FILTER`: Store scan conditions with a restricted `element` view and local data processing.

Policy checks cover compilation, permitted method signatures, bindings, results,
and execution budgets. Ordinary graph authorization remains active. Session
requests retain their transaction handling and supported data values. Unsupported
constructs fail with a script policy error; enabling a policy mode can require
changes to existing scripts. The policy mode has not been qualified as a
replacement for every deployment security control.

## Shared runtime

The shared runtime is packaged in the existing `hugegraph-struct` artifact. Java package names remain
`org.apache.hugegraph.security.script`.

```text
Server core ─┐
             ├─> Struct (types + script policies) ─> Commons
Store core ──┘
```

Struct declares `gremlin-groovy` as an optional dependency. Server core and Store
core explicitly supply it through their existing dependencies; other Struct
consumers do not inherit the Groovy runtime.

Store does not depend on Server core. The shared runtime resolves the fixed
Server graph and SchemaManager types only when those classes are present on the
deployment classpath. Store filters run without them. Server bindings keep the
existing concrete-type and class-loader checks; client scripts cannot select
additional host types.

The method manifests use the current `org.apache.hugegraph.struct.schema`
builder interfaces. Server and Store use the same Groovy policy implementation
and approved method manifests. QUERY and SCHEMA share the same method set,
while retaining separate binding permissions. The shared signatures live in
`common-methods.txt`; `query-methods.txt` and `store_filter-methods.txt` contain
profile additions, and `session-methods.txt` contains the session additions.
Public runtime constructors verify the union against the resolved dependencies.
An unreviewed new overload still prevents policy initialization.

## Pure Gremlin fast path

Non-session QUERY string evaluation can use TinkerPop's Gremlin language parser
without compiling Groovy. It accepts a complete, single traversal rooted at `g`,
with nested traversals, supported parameters and optional zero-argument
`next`, `hasNext`, `tryNext`, `toList`, `toSet`, `toBulkSet` or `iterate`.

```text
Complete string + validated request bindings
    ├─ Supported Gremlin syntax → parse tree → fresh traversal from current authorized g
    │                                         → validate its Bytecode → execute → guarded results
    └─ Other supported scripts → existing Groovy compiler and policy checks
```

The bounded parse cache stores syntax only. Each request resolves its own
parameters and graph, creates a new traversal and retains the existing binding,
authorization, execution-budget and result checks. TinkerPop's root visitor builds
the traversal once, without calling its terminal or applying strategies. Its
existing Bytecode is validated before iteration; no intermediate traversal,
Bytecode copy or JavaTranslator pass is needed. Server-configured source state
and authorization proxies are preserved. Only the trusted source's existing
source instructions are excluded from query validation; all instructions from
native client Bytecode remain checked.

Once execution starts, failures propagate without retrying through Groovy.
Queries exceeding the Bytecode structural budget discard the unexecuted traversal
and fall back before iteration or graph mutation, retaining the existing Groovy
limits. Source suppliers and strategies are not executed by this preflight.
The parser consumes the entire input; a `g.` prefix alone never grants access.

Groovy closures, declarations, multiple statements, non-`g` aliases, session and
explicit `compile()` calls retain the Groovy path. Language-sensitive literals
such as decimals, interpolation, ranges, escaped strings and conflicting
parameter names also use Groovy, preserving its types and name resolution.
Null parameters/literals, array bindings and GString bindings use Groovy to
preserve overload selection, argument shape and string conversion.
Unsupported parameter positions in the upstream grammar fall back to Groovy.
`withSack` and `withSideEffect` keep their existing Groovy path because they can
carry richer source state than the Bytecode data policy permits.
SCHEMA and STORE_FILTER do not use this fast path. Legacy mode is unchanged.

The grammar route uses Gremlin language syntax and TinkerPop's typed builders;
explicit `compile()` retains Groovy syntax and static generic checks. Some
Gremlin expressions accepted by `eval()` can therefore still be rejected when
explicitly compiled.

Engine metrics include `gremlinExecutions`, `gremlinParseHits`,
`gremlinParseMisses` and `gremlinParseEntries`; `compilations` continues to count
Groovy compilation. These counters describe routing, not latency or throughput.
The manual `ScriptPolicyBenchmark` includes a pure-query workload and a compiled
Groovy comparison; measure each mode in a separate JVM with representative data
before drawing deployment performance conclusions.

## Reviewing method manifest changes

Use Java 17 and run from the repository root:

```bash
python3 install-dist/scripts/script-policy/manifest_tool.py check
python3 -m unittest discover -s install-dist/scripts/script-policy -p 'test_*.py'
```

The tool packages the Core reactor and generates candidates under
`target/script-policy-candidates/`. It reports additions/removals for each
effective profile, grouping overloads by declaring type and method in
`changes.txt`, with exact signatures in `changes.json`. `check` exits with status
1 on policy drift, 2 on tool/build failure. `generate` writes the same candidates
and reports without treating drift as a command failure. Neither command changes
the checked-in approval files or disables runtime verification.

After a dependency upgrade, review both the declarative rules in
`ScriptMethodPolicy` and the generated changes, exercise affected compatibility
and denial tests, then explicitly copy approved candidate manifests into
`hugegraph-struct/src/main/resources/org/apache/hugegraph/security/script/`.
Sharing signatures between files does not count as an effective policy change.
The optional `--classpath-file` reuses a resolved Core runtime classpath and
requires a current reactor build; omit it for the normal build-and-check flow.

## Store scan lifecycle

Store filters decode the same schema-based persisted rows as ordinary scans.

Closing a scan request stream signals a half-close. The server can still deliver
results within the response credit already granted. Cancel the RPC to stop an
active filter. Filter failures terminate the response and release its iterator;
the current Store lifecycle preserves the original failure and cleanup failures.

## Validation

Build the complete reactor with Java 17, then select the relevant policy tests:

```bash
mvn clean compile -Dmaven.javadoc.skip=true
mvn test -pl hugegraph-server/hugegraph-test -am -P unit-test,memory \
  -Dtest=GremlinQueryFastPathTest,PolicyScriptEngineTest,PolicyCompatibilityTest,PolicySessionEngineTest,PolicyGraphModeTest,PolicyServerModeTest
mvn test -pl hugegraph-store/hg-store-node,hugegraph-store/hg-store-test -am \
  -P store-core-test -Dtest=ScanPolicyFailureTest,StorePolicyModeTest,GraphPartitionScanLifecycleTest
```

These checks do not establish production authentication, concurrent-load
performance, or policy deployment isolation qualification.
