# Server-Managed Graph Backup

The graph backup API is a server-owned operation. Tools submits a request and
polls the returned task; it must not inspect or copy the live RocksDB data
directory.

## Configuration

Set `backup.repository_root` in the graph configuration to a persistent path.
The default is `./backups`. The server creates a graph-scoped directory below
that root, then creates the named repository below the graph directory:

```
<backup.repository_root>/<graph-scope>/<repository>/
    databases/<stable-database-id>/  # RocksDB BackupEngine data
    manifests/<backup-id>.json       # published graph-level versions
    stages/                          # temporary restore data
```

Repository names must match `[A-Za-z0-9][A-Za-z0-9._-]{0,62}`. The configured
root should be on persistent storage and must not be shared by unrelated
server instances unless their graph data is also isolated.

## API Contract

Create a backup with `POST .../backups`:

```json
{
  "repository": "daily",
  "keep_num": 3,
  "request_id": "client-generated-id"
}
```

Restore with `POST .../backups/restore` and `confirm: true`. Omitting
`backup_id` selects the latest committed version. An explicit `backup_id` must
be a non-blank server-generated id such as `v-1757930000000-1a2b3c4d`;
empty or whitespace-only values are rejected.

Both endpoints return a durable task id. A non-blank `request_id` is optional,
but when supplied the same id and identical payload return the original task.
Reusing it with a different payload is rejected. The request is stored as task
input, so pending backup and restore tasks can be reconstructed after a server
restart.

`keep_num=0` means retain all committed graph versions. A positive value keeps
that many newest committed versions and removes native backup data that no
longer has a manifest reference.

## Consistency and Restore Lifecycle

The server fences graph mutation commits while it captures all opened RocksDB
stores. A version becomes visible only after every store has a verified native
backup and the manifest is atomically published. A manifest is valid only when
it contains exactly the current graph store identities.

Restore performs all manifest and native-backup validation before closing the
provider. It restores every store into a temporary stage, closes the provider,
switches all data directories, reopens the provider, and removes the old data
only after reopening succeeds. If preparation or switching fails, the old
directories are retained and the server attempts to roll back the stores.

The current implementation supports the RocksDB backend. Other backends must
provide a `GraphBackupService` implementation before these endpoints are
enabled for them.

## Verification

The focused native-backup tests exercise multiple backup versions and restore
selected versions:

```bash
mvn -pl hugegraph-server/hugegraph-rocksdb -am \
    -Dtest=RocksDbBackupExecutorTest,GraphBackupManifestTest test
```

The remaining release gate is a real server E2E run that mutates a graph
between two versions, restores both versions, checks schema/index and element
identity, and verifies task recovery after a server restart.
