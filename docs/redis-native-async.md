# Redis Native Async Reads

Redis entity repositories provide native asynchronous point and ID-list reads through the runtime-owned
`CacheAsyncExecutor`. The raw transport queues work without parking one executor worker per request.
Java 21 is required. An async API does not make Jedis nonblocking: bounded virtual-thread batches still perform socket I/O.

## Usage And Configuration

```java
var options = CacheRuntimeOptions.builder()
        .asyncMaxConcurrent(1024)
        .redisReadCoalescing(RedisReadCoalescingConfig.builder()
                .maxLiveReplyBytes(64L * 1024 * 1024)
                .build())
        .build();
// Supply options to the CacheDatabase constructor or runtime bootstrap.
var async = repository.async();
CompletableFuture<Optional<Customer>> row = async.findByIdAsync(id);
```

The runtime shares one gate and raw-read runtime across its repositories.
Options are immutable and support builder/toBuilder; the existing base configuration record is unchanged.
`asyncMaxConcurrent` defaults to 1024 and accepts 1..65536. This is an admission ceiling, not a throughput
recommendation or a JVM-memory budget. Core admission covers native operations and worker-based fallbacks.

Spring properties include `cachedb.async.max-concurrent`, `cachedb.redis.read-coalescing.enabled`,
and `cachedb.redis.read-coalescing.max-live-reply-bytes`.
A unique or primary Executor bean is borrowed for fallback work; ambiguity fails startup rather than choosing
arbitrarily. A CacheRuntimeOptions bean or ordered CacheRuntimeOptionsCustomizer can select it explicitly.
Without an external executor, the runtime owns a bounded virtual-thread executor.
The configured base `traceContextPropagator` is preserved.

The generated receipt-oriented repository facade also delegates `asyncExecutor()` to its underlying repository,
so its async methods use the runtime gate. Receipt facade methods may still use bounded worker offloading;
native no-offload eligibility described here applies to RedisEntityRepository's AsyncEntityRepository API.

## Supported Paths

An admitted L1 hit without a fetch plan can return an already-completed future.
Eligible `findHotByIdAsync` misses enqueue directly in the raw runtime and preserve hit, tombstone,
outside-policy and not-cached outcomes. `findByIdAsync` uses the same path; a miss with source loading disabled
or no registered loader does not dispatch a worker merely to return empty.
A real source fallback runs only on the bounded executor. Missing-loader failure follows the configured policy.

`findAllAsync` admits at most 100000 non-null input IDs, deduplicates raw work, and preserves input order and
duplicate results. Mutable duplicate entities are decoded into distinct instances.
At most 512 unique point operations are open in one window. Subsequent windows wait for both data and actual
physical completion. Unique results and final duplicate-expanded encoded output each have a 16 MiB bound.
Missing/tombstoned/outside-policy rows are omitted according to the native bulk contract; it does not add SQL source fallback.
Route limits and any lower backend bounds still apply.

Causal bulk reads use bounded 128-ID worker batches, verify versions BEFORE payload observations and never
substitute an uncertified SQL result. Only script-owned content-budget errors may debatch into bounded single reads.
Async bulk fetch plans reject explicitly: relation loaders currently lack an enforceable result-size contract.
Point fetch-plan/source fallbacks use bounded workers; their loader/codec must obey application size/time contracts. Active synchronous unit-of-work operations are rejected
on the caller; no unit of work is transferred to another thread.
Save/delete/query/aggregate methods also use bounded worker offloading, not the native raw-read path.

Query/page/count/aggregate/partition reads reject relevant active causal requirements with
`CacheCausalReadUnsupportedException` when they cannot certify an applied version.
Projections reject any active causal requirement without an explicit applied-version protocol.
Capturing a ThreadLocal or passing a receipt does not certify these result sets. Use versioned point/bulk reads
for causal guarantees; do not silently suppress the requirement to obtain a query result.

## Context, Cancellation And Shutdown

Tenant, causal, configured trace and route context are captured at submission and restored around mapping,
policy, decoding, source fallback and local-cache admission. The completion thread's previous context is restored
before public callbacks. The raw batch itself does not decide tenant ownership or interpret GlobalRaw payloads.
Near-cache admission checks the pre-I/O invalidation state; a late read must not refill an invalidated entry.

Cancellation detaches only the operation's own raw waiters or fallback future. Shared batch I/O is never interrupted.
A delivery timeout can fail the public future before socket exit. Physical admission remains held until actual
I/O and contextual completion processing exit; future cancellation alone is not physical completion.
No extra worker or dispatch permit is consumed merely to await a native raw future.

The runtime stops accepting new async work before draining raw reads and closing the executor and clients.
A borrowed application executor is never shut down by CacheDB.
Native read delivery uses the configured read timeout, including raw-to-entity mapping and bounded worker fallbacks.
A bounded per-runtime alarm dispatches expiry independently; it never runs I/O or callbacks on the timer thread.
The public future can fail while a decoder, loader or socket still runs. That work is not interrupted or declared
physically finished, and its result/delivery admission stays held through actual producer and callback exit.
No scheduler can preempt arbitrary user code or bound time spent in caller-supplied collection iteration,
codec/context capture before API return, JVM pauses or a user callback which blocks its own thread.
Configure finite pool, socket and source timeouts. Do not block inline completion callbacks.

## Capacity And Observability

Raw reply memory has separate admission from physical I/O: reservations precede allocation and remain charged
through mapping/callback exit. See [bounded point reads](redis-read-coalescing.md).
A lower global reply budget may reduce effective I/O concurrency even when the pool has spare connections.
Merging disabled still means singleton FIFO native async admission, not 1000 unbounded pool waiters.

A separate native result budget is shared by ALL repository views of one runtime. It is the smaller of
maxLiveReplyBytes and 128 MiB, independent of the raw reply budget. Each bulk reserves 48 MiB BEFORE input iteration:
16 MiB unique encoded/decode work, 16 MiB duplicate-expanded output work, and 16 MiB transient read workspace.
The default therefore admits two concurrent bulks; a 64 MiB setting admits one. Less than 48 MiB rejects bulk
as capacity pressure, not an entity oversize verdict. Each worker fallback reserves 32 MiB. Raw-backed point
misses need no extra content reservation, but all native delivery tokens/alarms are capped at 6144 per runtime.
Core admission can impose a smaller limit.

Every unique payload is charged before its codec runs. Duplicate output is prevalidated, and mutable duplicates
decode from the already bounded original encoded value instead of an unbounded re-serialization.
Result credit remains held through input setup, real producer exit and inline completion/cancellation callbacks.
Cancellation/deadline does not recycle memory capacity while framework work can still retain that result.

These are encoded-work/admission bounds, NOT exact JVM-heap limits. Arbitrary custom codecs, source loaders and
relation implementations need their own maximum expansion/result-size contract; the SDK cannot inspect or stop
an arbitrary object graph. Strings, RESP buffers, indexes, futures, thread stacks and application-retained results
add overhead. At defaults raw and native reservations have separate ceilings of 256 MiB and 128 MiB; size the pod
for both plus overhead, or lower the setting. This API is not an unbounded export path.

Executor `snapshot()` retains worker/offload counters; a native enqueue does not count as an executor dispatch.
`externalSnapshot()` reports native external admission, outstanding operations and terminal outcomes.
Global available permits include both paths. Transport metrics count logical attempts, not wire commands.
`CacheDatabase.nativeReadMemory()` exposes reservedBytes, limitBytes, deliveries and expiryWorkers separately
from raw deliveryMemory. Inspect both budgets, queue pressure, pool wait, Redis/source latency and callback duration.
No automatic transport/source retry loop is added; recover from mutations with explicit identity/receipt rules.
