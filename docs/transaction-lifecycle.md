# Request transactions and Store shutdown

Server releases the current thread's graph transactions when a REST request
finishes and when an authenticated context task exits. Cleanup visits every
registered graph even when one graph fails to close, and reports failures.
Applications must still finish their own transactions; this cleanup is not a
commit guarantee for an unfinished request.

The cleanup also applies when schema caches have become cold. Deleting an auth
project or user must remove its associated access or belong edges while
preserving unrelated relationships. The special OLAP vertex path remains
separate; negative schema IDs alone do not identify edges that can be skipped.

Store shutdown refuses new RPCs, cancels active RPCs and scans, and waits for
their callbacks and scan/TTL workers before Spring destroys the Store engine
and its databases. Cancellation may fail in-flight requests; stop writes and
check their outcomes before planned maintenance. This is not a guarantee that
every in-flight request drains successfully or that a leader transfers without
a failover interval.

If a callback cannot finish, shutdown remains pending rather than closing its
database underneath it. The distribution stop script waits up to 30 seconds,
returns a nonzero status on timeout, and retains the PID file for diagnosis.
Inspect logs and thread dumps before retrying. Do not add a concurrent shutdown
hook that closes the same databases.

See the [Store shutdown instructions](../hugegraph-store/README.md#stopping-a-store-node)
and the Server [module test guidance](../hugegraph-server/AGENTS.md#tests).
