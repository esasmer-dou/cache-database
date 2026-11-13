# Tenant Read Fencing

Read fencing is opt-in for a registered tenant-protected entity. Field encryption alone does
not enable tenant purge protection.

A runtime captures an OPEN generation before read I/O. The same original generation must
survive source loading, cache filling, projection work and result delivery. Closed, changed,
unknown or unavailable admission fails closed. Reads never query SQL to discover a generation.

Protected point and bulk reads use bounded admission. The default bridge permits at most
128 IDs, 1 MiB per encoded row and 8 MiB per result. Native asynchronous reads use the existing
bounded worker executor until their native transport can certify the same generation.
Unprotected native reads retain their normal transport.

L1 entries retain ordinary per-ID invalidation and carry an in-memory generation frame.
A frame is not a Redis entity format. Soft-expiry does not start an unfenced refresh.
A mutation invalidation occurring during read I/O prevents a late L1 insertion.

A source loader is explicitly supplied; the bridge does not discover or create a SQL loader.
Its result may be returned only after exact-generation validation. An optional fill planner
must use the canonical version/index producer protocol and return a same-slot guarded
publication carrying the original generation. Planning is CPU-only. The bridge does not
allocate write versions, infer a current stamp, enqueue a business write, or retry an
unknown publication outcome with a new generation.

Projection point reads require an explicitly reviewed identity-preserving projection.
They derive from the guarded base entity rather than trusting an arbitrary persisted
projection or shared query cache. Identity changes and unknown ownership are incomplete.
Persisted projection refresh requires a separately reviewed canonical publication plan.

This bridge does not certify entity query/page/aggregate/cursor routes, relation fetch plans,
watch delivery, arbitrary Spring Cache entries, or legacy direct hydration. Protected routes
without the corresponding protocol fail before reading or filling through a legacy path.
Explicit registration does not substitute for a complete ownership/store manifest.

Closing admission and cancelling a future are not proof that I/O has drained. Every registered
runtime must acknowledge physical completion and clear its declared L1 stores before erasure
and verification. A missing runtime or projection participant is incomplete, never success.
Reopening requires the complete tenant protection protocol and a verified new generation.
