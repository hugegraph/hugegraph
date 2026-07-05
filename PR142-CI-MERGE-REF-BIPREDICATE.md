<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
-->

# PR142 CI merge-ref BiPredicate failure note

> Date: 2026-07-06
> Branch: `task/gsoc-phase1-tp37` / PR head `test-gsoc-phase1-tp37`
> PR head before fix: `a24da1d289e827f6381a185030d9a4f1634bb52e`

## Finding

The large CI failure set was mainly one merge-ref compilation failure, not many
independent test regressions.

GitHub Actions `pull_request` runs check out `refs/pull/142/merge`, so the code
compiled by CI is the PR head auto-merged with the current `master`. The PR head
already used TinkerPop 3.7 `PBiPredicate` in `TraversalUtil`, but the generated
merge ref pulled in newer `master` code that still declared two local variables
as `BiPredicate<?, ?>`:

- `TraversalUtil.addPositiveLabelValues(...)`
- `TraversalUtil.isPositiveLabelContainer(...)`

Under TinkerPop 3.7, `P#getBiPredicate()` returns `PBiPredicate`, so those two
merge-ref-only declarations caused compile errors:

```text
TraversalUtil.java:[321,9] cannot find symbol
  symbol:   class BiPredicate
TraversalUtil.java:[393,9] cannot find symbol
  symbol:   class BiPredicate
```

## Affected Checks

The following red checks were all blocked by the same `hugegraph-core`
compilation error:

- `build-server`
- `build-commons`
- `cluster-test`
- `dependency-check`
- `pd`
- `store`
- `hstore`
- `CodeQL` autobuild

In this run, `dependency-check` did not reach the dependency/license metadata
comparison phase. It failed during its prerequisite `mvn install` because the
same core compilation error stopped the reactor.

## Resolution

Sync the branch with latest `master`, then update the two newly merged
`TraversalUtil` local variable declarations from `BiPredicate<?, ?>` to
`PBiPredicate<?, ?>`.

This keeps the branch aligned with `master` and makes local verification cover
the same code shape used by PR `pull_request` CI.

## Prevention

When PR CI fails after a master sync or after master has moved, compare both:

- `refs/pull/<pr>/head`
- `refs/pull/<pr>/merge`

If the PR head looks correct but CI still fails, inspect the merge ref before
assuming a stale CI run or a test-only regression.
