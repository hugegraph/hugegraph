# Request transactions and Store shutdown

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

Store shutdown refuses new RPCs, cancels active RPCs and scans, and waits for their callbacks and scan/TTL workers before Spring destroys the Store engine and its databases. A failed aggregate-query response callback is logged without skipping cancellation or cleanup waits for other queries. Cancellation may fail in-flight requests; stop writes and check their outcomes before planned maintenance. This is not a guarantee that every in-flight request drains successfully or that a leader transfers without a failover interval.

If a callback cannot finish, or an ordinary scan or bidirectional aggregate query fails to release its plan or partition iterators, shutdown remains pending rather than closing its database underneath it. This also includes iterators discarded while advancing past empty partitions, initializing a sequential scan, or counting rows. RocksDB iterators retain the first failure during automatic or explicit close so that later cleanup cannot hide it; concurrent close calls wait for the release attempt to finish. All scan entry points, including one-shot scans, retain failed cleanup independently of RPC termination and executor shutdown. Failed iterator releases are attempted once and remain diagnostic shutdown blockers. Bidirectional aggregate queries report cleanup failures to the client and log that shutdown is blocked; their final success batch is sent only after cleanup succeeds. The distribution stop script waits up to 30 seconds, returns a nonzero status on timeout, and retains the PID file for diagnosis. Inspect logs and thread dumps before retrying. Do not add a concurrent shutdown hook that closes the same databases.

A normal aggregate-query request half-close ends feedback without cancelling already permitted work. If the remaining feedback credit cannot finish the query, the server returns an explicit query error. Scan task rejection reports `UNAVAILABLE` during shutdown and `RESOURCE_EXHAUSTED` when the running scan pool is full.

See the [Store shutdown instructions](../hugegraph-store/README.md#stopping-a-store-node)
and the Server [module test guidance](../hugegraph-server/AGENTS.md#tests).
