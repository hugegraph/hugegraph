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

The request services stop scan/query admission, cancel their active calls and run queued
cleanup before Spring destroys their databases. This scoped cleanup reuses the same
per-call owners; it does not create a second shutdown registry. Interrupted cleanup waits
and native teardown preserve interruption without abandoning these owners.

The subsequent Store-wide shutdown change extends this boundary to ordinary RPC
callbacks and sticky TTL cleanup failures. It also defines distribution stop timeout
handling; those guarantees are not introduced by this request-lifecycle change.
