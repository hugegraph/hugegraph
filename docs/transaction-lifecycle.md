# Server transaction lifecycle

Server releases the current thread's graph transactions when a REST request
finishes and when an authenticated context task exits. Cleanup visits every
registered graph even when one graph fails to close, and reports failures.
Cleanup explicitly rolls back unfinished writes, including when `onClose(COMMIT)`
was configured, then releases backend transactions and resets thread-local
transaction behavior and listeners. Applications must commit successful writes
before leaving the request/task boundary. Closing an OLTP traverser preserves
the caller's transaction so that the caller can still commit or roll it back.

The cleanup also applies when schema caches have become cold. Deleting an auth
project or user must remove its associated access or belong edges while
preserving unrelated relationships. The special OLAP vertex path remains
separate; negative schema IDs alone do not identify edges that can be skipped.

Shared schema and element caches retain their invalidation listeners until the graph closes. Request cleanup releases backend leases while preserving those graph caches and their schema identity.
