# Store RPC, scan and shutdown lifecycle

Store shutdown refuses new RPCs, cancels active RPCs and scans, and waits for their callbacks and scan/TTL workers before Spring destroys the Store engine and its databases. A failed aggregate-query response callback is logged without skipping cancellation or cleanup waits for other queries. Cancellation may fail in-flight requests; stop writes and check their outcomes before planned maintenance. This is not a guarantee that every in-flight request drains successfully or that a leader transfers without a failover interval.

If a callback cannot finish, or an ordinary scan or bidirectional aggregate query fails to release its plan or partition iterators, shutdown remains pending rather than closing its database underneath it. This also includes iterators discarded while advancing past empty partitions, initializing a sequential scan, or counting rows. RocksDB iterators retain the first failure during automatic or explicit close so that later cleanup cannot hide it; concurrent close calls wait for the release attempt to finish. All scan entry points, including one-shot scans and TTL cleanup, retain failed cleanup independently of RPC termination and executor shutdown. Failed iterator releases are attempted once and remain diagnostic shutdown blockers. Bidirectional aggregate queries report cleanup failures to the client and log that shutdown is blocked; their final success batch is sent only after cleanup succeeds. The distribution stop script waits up to 30 seconds, returns a nonzero status on timeout, and retains the PID file for diagnosis. Inspect logs and thread dumps before retrying. Do not add a concurrent shutdown hook that closes the same databases.

If shutdown cancellation wins while a one-shot scan releases its iterator, the
response terminates with `CANCELLED`; it does not report successful completion
without its result. Iterator cleanup still finishes before the scan unregisters.

A normal aggregate-query request half-close ends feedback without cancelling already permitted work. A subsequent transport cancellation or deadline still interrupts the workers and releases their resources. A batch-scan RPC accepts one initial query; repeated query requests are ignored before allocating another iterator, including after its final batch. If the remaining feedback credit cannot finish the query, the server returns an explicit query error. Scan task rejection reports `UNAVAILABLE` during shutdown and `RESOURCE_EXHAUSTED` when the running scan pool is full.

See the [Store shutdown instructions](../hugegraph-store/README.md#stopping-a-store-node)
and the Server [module test guidance](../hugegraph-server/AGENTS.md#tests).


Client response parsing runs outside the short state lock. Accepted final batches remain visible across response completion; errors and iterator close discard in-flight parsing results. Early query close cancels the transport without waiting for a blocked request send. Normal request half-close remains serialized with sends.

An interrupted shutdown thread still waits for heartbeat producers and Raft groups to finish. The Store engine restores that interrupt only after partition and native database teardown.
