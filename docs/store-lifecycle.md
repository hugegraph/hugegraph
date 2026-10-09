# Store RPC and Scan lifecycle

Client and server changes keep request half-close separate from transport cancellation.
An aggregate query finishes work already supported by feedback credit after half-close;
a later transport cancellation or deadline still cancels its workers. Graph partition
scans deliver their permitted pages and final batch, or return an explicit error when
half-close leaves insufficient feedback credit. Batch-scan RPCs accept one initial query.

Response parsing runs outside the client state lock. Accepted final batches remain
visible across response completion; errors and iterator close discard unpublished data.
Early query close cancels the transport without waiting for a blocked request send.
Normal half-close stays serialized with sends. Closing a composite iterator closes
all started children once.

Each scan/query owns its workers and iterators until cleanup finishes. Iterator cleanup
failures remain visible, including automatic native iterator close and empty-partition
selection. Final aggregate success is sent only after plan and iterator cleanup succeeds.

The RPC boundary stops admission to every Store RPC before cancelling active scans
and queries. It runs queued request cleanup and waits for terminal application
callbacks before Spring destroys their databases. This reuses the same per-call
owners; it does not create a second shutdown registry. Interrupted cleanup waits
and native teardown preserve interruption without abandoning these owners.

The subsequent Store shutdown change adds sticky TTL cleanup failures and defines
distribution stop timeout handling; those guarantees are not introduced by this
request-lifecycle change.

Batch-scan half-close delivers its already permitted pages; insufficient receipt credit
returns an explicit error. Early iterator close sends the cancellation request. Unary
query and count requests share the query admission, worker and cleanup owner registry;
count keeps partition work parallel on the service executor. Iterator cleanup failure
prevents successful completion and remains visible to request drain.

The gRPC application callback queue is always unbounded (`Integer.MAX_VALUE`), matching
its existing default. `thread.pool.grpc.queue` remains accepted for configuration
compatibility but no longer limits this callback queue. Bounded callback dispatch can
reject cancellation or completion callbacks in gRPC 1.55.3, leaving request cleanup
registered after the transport has closed. `thread.pool.grpc.core` and `.max` still
configure the same executor; an unbounded queue normally keeps dispatch at the core
thread count. Bound expensive scan/query work and admission at their worker queues;
sustained overload can otherwise increase callback backlog and memory use.
