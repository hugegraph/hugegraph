# Server-Managed Graph Backup

> Development branch: not release-ready. See the [handoff](graph-backup-handoff.md)
> for known blockers, verification status and next steps.

The server owns capture and restore of a RocksDB graph. Clients submit requests
and poll tasks; they must not copy the live RocksDB directory. Other backends
need their own `GraphBackupService` implementation.

## Repository

Set `backup.repository_root` in the graph configuration (default: `./backups`,
relative to the server working directory). Use persistent, server-writable storage.

```text
<backup.repository_root>/<graph-scope>/<repository>/
    databases/<stable-database-id>/  # RocksDB BackupEngine files
    manifests/<backup-id>.json       # published graph versions
    stages/                         # temporary restore data
```

The graph scope is URL-safe Base64 of the graph identity supplied by the provider.
Repository names match `[A-Za-z0-9][A-Za-z0-9._-]{0,62}`. Do not share repositories
between independently running instances. Keep repository storage separate from
live data and WAL directories; there is no capacity admission control.

## REST API

Paths below are relative to `/graphspaces/{graphspace}/graphs/{graph}`.
Use the graph's normal authentication. Backup/restore require space or graph-owner
roles; list/detail use the existing space-member/dynamic authorization rules.

| Method | Path | Request / result |
| --- | --- | --- |
| POST | `/backups` | Required `repository`; optional `keep_num` (default `0`) and `request_id` |
| POST | `/backups/restore` | Required `repository`, `confirm: true`; optional `backup_id`, `request_id` |
| GET | `/backups?repository=daily` | `{"backups": [...]}`; repository defaults to `default` |
| GET | `/backups/{backup_id}?repository=daily` | One manifest summary |
| GET | `/tasks/{task_id}` | Poll `task_status`; read `task_result` on success |

Backup request:

```json
{"repository":"daily","keep_num":3,"request_id":"daily-2026-10-13"}
```

Restore request (destructive: replaces the current graph data):

```json
{"repository":"daily","backup_id":"v-1757930000000-1a2b3c4d","confirm":true}
```

Both POST endpoints return `{"task_id":123}` plus `request_id` if supplied.
Submission is not completion: wait for task success and inspect failure details.
Successful results and GET summaries contain `backup_id`, `repository`,
`created_at` (epoch milliseconds) and `databases` (database identity → native id).

- `keep_num=0` retains all published versions; a positive integer keeps the newest
  N versions. Retention runs after publishing a new backup, not on a timer.
- Omitted or null `backup_id` selects the latest published version at execution
  time. Explicit ids match `v-[0-9]{13}-[0-9a-f]{8}`; blank ids are rejected.
- `request_id` is trimmed, non-blank and at most 128 characters. While its task
  record exists, an identical normalized request returns the original task;
  reuse with a different operation/payload is rejected. This is not permanent
  deduplication after task deletion, nor a cross-process coordination mechanism.
- Task input is persisted and job classes can be reconstructed. Restart recovery
  and exactly-once effects have **not** been established by the current tests.

## Consistency and operational limits

The intended capture boundary fences mutation commits across the provider's
opened RocksDB stores. Native backups are verified before publishing a graph
manifest whose database identities must match the current stores. Publication
uses an atomic move when supported, with a non-atomic fallback; this is not a
power-loss durability guarantee.

Restore validates the manifest/native backups, stages restored data, closes
sessions, switches live directories, reopens the provider and then removes old
data. Runtime-exception rollback exists, but crash recovery and failure paths
remain release gates. Do not rely on this branch for production recovery.
Quiesce application traffic for restore: the write fence is not a read-drain or
maintenance-mode mechanism. Validate in a disposable graph before use.

For build commands, test coverage and outstanding work, continue with the
[engineering handoff](graph-backup-handoff.md).
