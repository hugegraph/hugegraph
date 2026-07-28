<!-- Body below is shared by both PRs. Prepend per-repo before posting:
     apache#3119 : "Fixes #3118" + sync link to hugegraph/hugegraph#171
     hugegraph#171: full-URL issue link + sync link to apache/hugegraph#3119 -->

# Add `init_store.enabled` to skip init-store in distributed deployments

## Problem

`InitStore` always runs full local initialization: scan `./conf/graphs`, init
non-hstore backends, create the built-in admin account. That is correct for
**standalone / tarball** users and must remain the default.

In **PD / HStore** deployments the storage side already owns metadata, so there
is nothing for init-store to do.

### Why the Docker guard is not enough

`docker-entrypoint.sh` guards init with a flag file, `docker/init_complete`.
That file lives in the container's writable layer at
`/hugegraph-server/docker/init_complete`. No compose file mounts a volume over
it, and the server services declare no volumes at all.

That guard works for Docker and does not work for Kubernetes:

| | Writable layer after a restart | Effect |
|---|---|---|
| `docker restart` | preserved | flag survives, init-store runs once ever |
| K8s container restart / pod reschedule | new container from the image | flag is gone, **full init-store runs on every restart** |

So on Helm the flag file never accumulates: every Server pod restart re-runs
the complete local init against a PD/HStore cluster that already owns its
metadata. Working around that is what pushed the chart toward one-shot init
Jobs and `HG_SERVER_SKIP_INIT`-style switches. A file-based guard cannot fix
this, because the problem is precisely that the file does not persist. The
decision has to come from configuration instead.

This is the whole motivation for the change: it is a distributed-deployment
concern, not a Docker one.

## Solution

A dedicated option, `init_store.enabled`, defaulting to `true`:

| `init_store.enabled` in `rest-server.properties` | `init-store` |
|--------------------------------------------------|--------------|
| **Unset** (shipped defaults) | **Full init**, same as master, standalone safe |
| `true` | Full init |
| `false` | No-op, WARN, exit 0, the distributed opt-out |

A dedicated option rather than reusing `graph.load_from_local_config`: that
property already means "whether `GraphManager` loads graph definitions from the
local directory", its declared default is `false`, and existing configs may
materialize that default explicitly. Giving it a second contract would make an
explicit `false` silently skip backend and admin init for standalone
RocksDB/HBase installs.

Docker maps `HG_SERVER_INIT_STORE_ENABLED` onto the property in
`rest-server.properties`, the file `init-store.sh` passes to `InitStore`.

### Auth bootstrap in skip mode

When init-store is skipped, `StandardAuthenticator.initAdminUserIfNeeded()`
does not run, so a Docker `PASSWORD` piped into `init-store.sh` would be read
and discarded, so the server would then come up with the `auth.admin_pa` default
of `pa` instead of the requested credential.

The entrypoint therefore writes `PASSWORD` to `auth.admin_pa` in skip mode
instead of piping it to `init-store.sh`, so the account the server creates on
startup uses the requested password.

That startup path has a precondition. `GraphManager.initAdminUserIfNeeded()` is
reached only from `loadMetaFromPD()`, which runs only when `usePD` is true, and
`usePD` defaults to false. With init-store skipped and `usePD` false, no
component creates the built-in admin at all, so enabling auth would start a
server that enforces authentication against an empty account list. The
entrypoint refuses that combination and exits with an explanation, and
`InitStore` logs the same condition for non-Docker installs.

`usePD=true` is the intended setting for distributed deployments: the
cluster-test `rest-server.properties.template` already pairs it with
`auth.authenticator` and `auth.admin_pa`. The shipped compose files not setting
it is a pre-existing gap, and is left to a follow-up because enabling it also
switches graph loading from local config to PD metadata.

The entrypoint also does not write `docker/init_complete` in skip mode: nothing
was initialized, so a later run with init-store enabled must still be able to
perform the real initialization.

## Files changed

| File | Change |
|------|--------|
| `.../config/ServerOptions.java` | New `INIT_STORE_ENABLED` option, default `true` |
| `.../cmd/InitStore.java` | Early exit when `init_store.enabled=false` |
| `.../docker/docker-entrypoint.sh` | Env → property; `PASSWORD` → `auth.admin_pa` in skip mode; refuse auth + skip without `usePD`; no init flag when nothing was initialized |
| `.../unit/cmd/InitStoreConfigTest.java` | Option defaults + `main()`-level proof of the early exit |
| `.../unit/UnitTestSuite.java` | Suite entry |
| `.../docker/test/test-docker-entrypoint.sh` | Entrypoint lifecycle smoke tests |
| `.github/workflows/docker-build-ci.yml` | Run the entrypoint tests, and Docker CI on `hugegraph-dist/docker/**` |
| `docker/README.md` | Server env var reference |

## Test plan

```bash
mvn test -pl hugegraph-server/hugegraph-test -am -P unit-test -Dtest=InitStoreConfigTest
mvn test -pl hugegraph-server/hugegraph-test -am -P unit-test
```

`InitStoreConfigTest` points the temporary `rest-server.properties` at a
`graphs` directory that does not exist, then calls `InitStore.main()` with
`init_store.enabled=false`. A companion test asserts that the same directory
does make `ConfigUtil.scanGraphsDir` fail, so the skip test proves the gate is
applied before any graph or admin work rather than being a tautology.

The entrypoint lifecycle is covered separately, with no JVM, backend or Docker
needed. The entrypoint runs against a throwaway install tree whose `./bin`
scripts are stubs that record their own invocation:

```bash
hugegraph-server/hugegraph-dist/docker/test/test-docker-entrypoint.sh
```

Cases: default init, default + `PASSWORD`, skip via env, **skip + `PASSWORD`**
(asserts the password reaches `auth.admin_pa` and never `init-store.sh` stdin),
skip via a mounted property with no env var, env overriding a conflicting
property, `false → true` restart, flag-file suppression of re-init, boolean
parsing parity with the server (`FALSE`, `off`, `no`, and fail-fast on
non-booleans), the `:` property separator, a backslash password round trip, and
refusal of auth plus skip without `usePD`.

**Manual smoke (optional):**

1. Shipped `conf/rest-server.properties` (no flag): `bin/init-store.sh` → full
   init, same as master.
2. Set `init_store.enabled=false`, run again → WARN and exit 0 without backend
   init.

## Out of scope

- `util.sh` / `check_port` / lsof
- Helm chart Job / `SKIP_INIT` removal (the chart sets the env, and `usePD=true` when it enables auth)
- Setting `usePD=true` in the shipped compose files, which also switches graph
  loading from local config to PD metadata
- `GraphManager` behavior and the `graph.load_from_local_config` default

## Compatibility

**No breaking change for tarball / bare init-store.** The option defaults to
`true`, so unset config keeps the full init path.

Distributed deployments set `init_store.enabled=false` (or
`HG_SERVER_INIT_STORE_ENABLED=false`).
