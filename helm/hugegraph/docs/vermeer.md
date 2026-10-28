# Deploy Vermeer with HugeGraph and Hubble

Vermeer is optional and disabled by default, including with `values-single.yaml`
and `values-cluster.yaml`. Enabling it deploys the native Master/Worker engine,
configures Hubble automatically, and exposes the authenticated native UI/API.
It needs no Computer Operator, CRD, etcd or external coordination service.

## Choose a topology

| Setting | Result |
|---|---|
| `values-single.yaml` | HugeGraph: 1 PD + 1 Store + 1 Server, plus Hubble |
| `values-cluster.yaml` | HugeGraph: 3 PD + 3 Store + 3 Server, plus Hubble |
| `vermeer.mode: standalone` | One Pod containing separate Master and Worker containers |
| `vermeer.mode: cluster` | One Master Pod plus `vermeer.worker.replicas` Worker Pods; default 3 |
| `mode: easy` / `strict` | HTTP entry and network-isolation defaults; independent of both topologies |

Both Vermeer modes run the same two native process roles. Standalone co-locates
them for a smaller deployment; cluster mode distributes the Workers. A cluster
still has one Master: adding Workers does not provide Master high availability.
Hubble's native loader requires HStore, PD discovery and `hubble.mode=pd`; the
HugeGraph single-node preset meets these requirements. A RocksDB-only Server
does not provide the same direct Store-loading path.

Server and Store also need the same PD metadata prefix. The shared
`metadataCluster` defaults to the existing Server namespace `hg-test` and wires
both components consistently. Retain the initialized value on upgrade; see
[namespace adoption](operations.md#pd-metadata-namespace) for older custom images.

## Image compatibility

Use a Vermeer build that contains `/go/bin/vermeer-token`, versioned HMAC-SHA256
file-based token-factor configuration, deployment PD/Server credential files,
and space-aware Worker availability. Hubble needs a build containing its native
Vermeer adapter and nine algorithm forms. Source support does not establish that
an existing `latest` image contains these changes.

Select a matching published build, or build the corresponding source revisions
with their repository Dockerfiles before installation. Record the tested image
digests. Configure `vermeer.image.repository/tag/digest` and `hubble.image` to use
those builds; an explicit digest wins over the tag. Images inherit
`global.imageRegistry` and `imagePullSecrets`. Master, Worker and token
initialization reuse the same Vermeer image; no runtime package download is needed.

## Install and enable

Commands run from the HugeGraph repository root. Choose one installation after
selecting compatible images in `my-images.yaml`:

```bash
# Small HugeGraph topology, plus one Pod with Master and Worker containers
helm install hugegraph ./helm/hugegraph -n hugegraph --create-namespace \
  -f helm/hugegraph/values-single.yaml -f my-images.yaml \
  --set vermeer.enabled=true --set vermeer.mode=standalone \
  --wait --timeout 20m
```

```bash
# Multi-node HugeGraph topology, plus one Master and three Worker Pods
helm install hugegraph ./helm/hugegraph -n hugegraph --create-namespace \
  -f helm/hugegraph/values-cluster.yaml -f my-images.yaml \
  --set vermeer.enabled=true --set vermeer.mode=cluster \
  --set vermeer.worker.replicas=3 --wait --timeout 20m
```

The two choices are independent: a small HugeGraph deployment can also use
multiple Vermeer Workers. To enable Vermeer on an existing release, preserve its
complete reviewed values and use `helm upgrade` with the same `vermeer` settings.
Neither installation creates a sample graph, loads data or runs an algorithm.

For a release created before Vermeer was added, `--reuse-values` cannot import
the new component defaults. Export its effective values to a private file, review
them, then pass the file without `--reuse-values`:

```bash
umask 077
helm get values hugegraph -n hugegraph --all -o yaml > existing-values.yaml
helm upgrade hugegraph ./helm/hugegraph -n hugegraph \
  -f existing-values.yaml -f my-images.yaml --set vermeer.enabled=true \
  --wait --timeout 20m
```

Resource names honor `fullnameOverride`. With the default release name, the
Master StatefulSet and HTTP Service are `hugegraph-vermeer-master`; its Pod is
`hugegraph-vermeer-master-0`. Cluster Workers use StatefulSet
`hugegraph-vermeer-worker`. Standalone has no separate Worker StatefulSet.

## Use Hubble

Keep Hubble enabled in its default PD mode and sign in using the existing
HugeGraph administrator account. The chart sets `vermeer.url` to the internal
Master Service and provides a service-token file. No address or token entry is
required in Hubble. Select a graph and open batch analysis:

1. Load or reload the selected graph into Vermeer.
2. Choose one of Hubble's nine supported algorithms and submit it.
3. Inspect asynchronous task status, cancel an unfinished task when needed,
   and open its paginated vertex results.
4. Unload the Vermeer copy when it is no longer needed.

Results remain queryable through the native task API while their cache exists;
they are not written into HugeGraph vertex properties. A successful historical
task does not guarantee its in-memory results survive a restart. Direct Store
scans bypass Server label permissions, so Hubble restricts this flow to HugeGraph
super administrators. Ordinary graph readers retain their existing Server task
view; the Vermeer service token does not grant them this administrator access.

Setting `hubble.enabled=false` keeps Vermeer available through its native entry.
`hubble.mode=direct` cannot be combined with an enabled native Vermeer integration.

## Native UI and API access

The Master HTTP listener defaults to 6688. Easy mode assigns a NodePort; strict
mode uses ClusterIP. Override `vermeer.service.type/nodePort` when needed. Find
the assigned entry with:

```bash
kubectl -n hugegraph get svc hugegraph-vermeer-master
kubectl get nodes -o wide
```

Use the NodePort on a reachable node, or forward the Service locally:

```bash
kubectl -n hugegraph port-forward svc/hugegraph-vermeer-master 6688:6688
```

Open <http://127.0.0.1:6688/>. Native Vermeer login uses its own administrator
token, not the HugeGraph password. Generate the token inside the Master container
without putting the signing factor in shell arguments:

```bash
kubectl -n hugegraph exec hugegraph-vermeer-master-0 -c master -- \
  /go/bin/vermeer-token -factor-file /var/run/vermeer/auth/token-factor \
  -user admin -space '$DEFAULT' -client helm -i -quiet
```

Paste that token into the native login form. The same native token can authorize
API requests using `Authorization: Bearer <token>`. Keep it private; it carries
Vermeer administrator access. Hubble creates its service token automatically and
does not require this manual step or a separate native browser login.

Only Master HTTP is exposed. Master gRPC 6689 and Worker HTTP/gRPC 6788/6789 stay
inside the release. The chart restricts internal gRPC callers even in easy mode.
Strict mode also isolates egress and admits the required Hubble, PD, Store,
Server and Worker traffic automatically. Add native
`networkPolicy.vermeer.extraIngress/extraEgress` rules for actual external
clients or data sources. NetworkPolicy enforcement requires a supporting CNI.

## Deployment credentials and GitOps

Vermeer authentication uses a separate signing factor in the kept
`<release>-vermeer-auth` Secret, key `token-factor` by default. Online Helm
upgrades reuse it. Configure `vermeer.auth.existingSecret/key` for an external
Secret, or a controlled `vermeer.auth.value` for reproducible offline rendering;
external Secret takes priority over the fixed value and auto-generation.
Never place a real factor in a public values file.

Master reads the factor from its mounted file. Hubble's init container reads the
same factor and writes a long-lived native administrator service token to a
private temporary volume; the Hubble application mounts the token, not the factor.
The native CLI's `-factor-file` mode creates the matching versioned token. Do not
replace it with the legacy `-factor` option: inline configuration retains the
older signing protocol and its tokens are rejected by file-backed deployments.
Worker group `$` provides the common
pool, so newly created graph spaces do not require manual Worker allocation.

The chart also wires both process roles to the release's PD peers and shared
PD credential file. Worker Schema reads default to the existing deployment
Server administrator Secret; no extra account is created. This deployment
identity is separate from the user's Hubble session. To use an existing account
with narrower Schema access, configure:

```yaml
vermeer:
  hugegraphCredentials:
    existingSecret: vermeer-schema-reader
    key: credentials.json
```

That Secret key must contain JSON `{"username":"schema-reader","password":"..."}`.
The account and required Schema-read permissions must already exist for every
graph space and graph it will load. The chart does not reconcile future account
memberships. A configured missing or invalid credential file fails the load.
These credentials do not configure the separate native HugeGraph result writer.

For a custom non-root Hubble image, set an explicit numeric `runAsUser` in
`hubble.securityContext` or `hubble.podSecurityContext`. The token init container
uses that same UID so Hubble can read its private `0600` file. Setting
`runAsNonRoot=true` without a positive UID is rejected before installation.

For GitOps, pin all credential sources before rendering; offline `helm template`
cannot reuse live generated Secrets through `lookup`. Preserve the existing
factor when adopting GitOps. Rotate it deliberately through its source values
or secret manager, then roll the dependent workloads and Hubble as directed by
Helm NOTES. An external Secret change alone does not roll Pods or regenerate
Hubble's token. Server password rotation also needs the existing account/Secret
synchronization procedure in [operations](operations.md#credentials).

## Storage and recovery

Master and Worker use separate writable data directories, including when they
share one Pod. Each role mounts its own `/go/bin/vermeer_data` directory;
logs use `/go/bin/vermeer_data/vermeer.log`. Never share a Pebble directory
between process roles. Each process also works from that directory, so relative
local output files under `./data/hubble` use its corresponding data volume.
Keeping those files does not recreate the in-memory result-query cache.

| Setting | Default |
|---|---|
| `vermeer.master.storage.enabled` / `worker.storage.enabled` | Inherit `persistence.enabled` |
| `vermeer.master.storage.size` | `10Gi` |
| `vermeer.worker.storage.size` | `20Gi` per Worker |
| `vermeer.master.storage.storageClassName` / `worker.storage.storageClassName` | Cluster default when empty |

Standalone adds two claims: the Master StatefulSet claim and the kept
`<fullname>-vermeer-standalone-worker-data` claim. Cluster adds one Master claim
and one Worker claim per replica. Thus the default HugeGraph installation needs
9 volumes with standalone Vermeer or 11 with its default three-Worker cluster;
the small HugeGraph preset with standalone Vermeer needs 5. The seven-volume
local-PV example covers only the base installation; prepare additional matching
volumes before enabling Vermeer there.

Claims are retained when disabling/uninstalling the engine or scaling Workers
down. Explicitly disabled storage uses `emptyDir`; PVC failures never fall back
to temporary data. Persistent Master metadata, Worker files and task history do
not guarantee restoration of in-memory graphs, results or running tasks. After a
restart, inspect state and reload graphs before resubmitting work as needed.

Before changing topology or Worker count, stop submissions, finish/cancel tasks,
and unload loaded compute graphs. Back up required metadata and retained files.
Switching standalone/cluster does not migrate Worker-local data between the kept
standalone claim and cluster Worker claims. Recheck Worker availability and reload
graphs after the change. Do not promise uninterrupted mode switching, Master
failover or automatic job recovery.

## Verify and troubleshoot

Run `helm test hugegraph -n hugegraph --logs` for the chart's enabled checks, then
validate the actual Hubble flow with a dedicated graph and known expected
algorithm results. Pod Ready and `/healthcheck` alone do not establish usable
Worker allocation. Space-aware `/api/v1/workers/availability` checks whether the
service token's space can obtain Workers.

For queued loads, verify Worker registration, common-pool membership, internal
gRPC reachability, PD discovery, Store endpoints and Server Schema credentials.
If Store reports `no such vertex label` or `no such edge label`, verify that its
`PD_CLUSTER` matches the Server's `HG_SERVER_CLUSTER` and recorded metadata prefix.
For Hubble configuration errors, check matching image capabilities, PD mode,
the native Master origin and the generated token file. For lost result pages
after restart, rerun the graph load/computation; a retained successful task record
does not recreate its result cache. Configure `vermeer.master.resources` and
`worker.resources` for the real graph size and algorithm workspace; disk space
does not replace sufficient Worker memory.
