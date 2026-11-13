# Bounded Redis Point Reads

Redis point-read coalescing combines eligible cache misses without an artificial batching delay.
It requires Java 21. Near-cache hits do not enter the Redis transport.
Coalescing is disabled by default to preserve low-concurrency latency. Enable it explicitly
only after comparing enabled and disabled behavior under your own concurrent workload.

## Measured Trade-Offs

The November 10, 2026 candidate comparison used the same SDK with merging enabled and disabled,
three forks per cell, a one-CPU / 4 GiB runner and Redis limited to 100m CPU / 384 MiB.
Ratios below are enabled divided by disabled; these are not comparisons with version 0.18.1.
Higher throughput is better; lower p50 latency is better.

| Workload | Throughput Ratio | p50 Latency Ratio | Interpretation |
| --- | ---: | ---: | --- |
| One caller | 0.7132x | 1.3528x | Merging adds overhead at low concurrency. |
| 16 callers | 0.4693x | 2.2374x | Enabling merging is not justified for this measured workload. |
| 64 callers | 1.4439x | 0.2745x | Faster, but below the required 2x candidate acceptance target. |
| 1,000 virtual callers | 2.2486x | 0.4648x | Beneficial under this specific high-concurrency workload. |

These results do not approve the release, change its thresholds, or promise the same gains in your
deployment. Keep merging disabled unless an equivalent application workload benefits within its
latency and memory budgets. Do not increase concurrency just to obtain a larger benchmark ratio.

## Supported Reads

Entity `findById`, `findHotById` and their async equivalents can use the shared bounded point-read runtime.
Native async `findAll` uses bounded windows of point reads and preserves input order and duplicates;
its total returned encoded content is limited to 16 MiB. It is not an unlimited export API.
Reads with a fetch plan, source fallback or a positive causal version requirement may use a different bounded path.
Async operations within an active unit of work are rejected without moving that unit of work to another thread.

Causal point reads and `findAll` retain version verification. Propagating context alone does not certify a query,
page, aggregate, partition or projection. Such operations reject active relevant causal requirements when no
applied-version protocol exists; a receipt does not make an arbitrary derived query read-your-writes certified.

The low-level `RedisReadRuntime` returns raw payload, tombstone, TTL and observation time.
It neither decodes entities nor decides tenant ownership. Same-slot entity/tombstone/TTL reads are atomic;
different slots are separate observations, not a distributed snapshot.
Missing strings are null. TTL values follow Redis: -1 persistent, -2 missing, and -3 not requested.
The observation timestamp precedes network I/O, so elapsed time must be subtracted before using a positive TTL.

## Configuration

New runtime options are separate from the existing `CacheDatabaseConfig` record.

```java
var reads = RedisReadCoalescingConfig.builder()
        .enabled(true)
        .maxInFlightBatches(16)
        .maxReplyBytes(16L * 1024 * 1024)
        .maxLiveReplyBytes(64L * 1024 * 1024)
        .timeoutMillis(5000)
        .build();
var options = CacheRuntimeOptions.builder().redisReadCoalescing(reads).build();
```

A 64 MiB global content budget allows at most four simultaneously reserved 16 MiB replies; actual retained
content may use less. Choose limits for pod memory and Redis CPU, not merely the connection-pool size.

| Setting | Default | Limit / Meaning |
| --- | --- | --- |
| enabled | false | Opts into merging, not unbounded transport |
| maxBatchKeys | 512 | Identities; each identity is an entity/tombstone pair, up to 1024 physical keys |
| maxQueuedRequests | 4096 | 0..4096 queue-origin admission reservations, including selected work and budget retries |
| maxQueuedBytes | 16 MiB | At most 16 MiB of queue-origin admission charges, retained through physical completion |
| maxInFlightBatches | 0 | 0 derives max(2, poolMaxTotal/2); explicit 1..1024 |
| maxReplyBytes | 16 MiB | 1..16 MiB of UTF-8 payload/tombstone content per attempted batch |
| maxLiveReplyBytes | 256 MiB | Global reserved plus retained raw content; at least maxReplyBytes, at most 256 MiB |
| timeoutMillis | 5000 | 1..300000, async delivery deadline and queued sync wait |
| maxAsyncDirect | 16 | 0..1024 bounded overflow batches when merging is enabled |

Pool32 derives 16 in-flight batches; pool64 derives 32, without a hidden cap of sixteen.
A derived value above 1024 is rejected. The global reply-memory budget can impose a lower effective concurrency.

With `enabled=false`, eligible native async misses still use bounded FIFO admission, but every transport batch
contains exactly one identity. A full count/byte queue rejects rather than bypassing older requests.
Synchronous repository reads retain their non-coalesced compatibility path. Low-level `RedisReadRuntime`
calls remain guarded even when merging is disabled.
Use a fair connection pool; borrowed clients may be unfair, and admission cannot guarantee progress against
unrelated users of the same pool. Socket, connect and pool-wait timeouts must all be finite.

## Backpressure And Memory

Async I/O uses one virtual thread per admitted batch, not a parked worker per queued request.
Below the concurrency and memory limits, a synchronous raw read starts immediately on its caller.
Queues use a lock only for bookkeeping; network I/O, waiting and completion callbacks never run under it.

Every batch reserves `maxReplyBytes` from the global reply-memory budget BEFORE I/O can materialize a value.
On return, its reservation shrinks to actual raw UTF-8 content and remains held through mapping and inline
completion-callback exit. Physical I/O permits are released and eligible successors dispatched before callbacks.
A blocked callback therefore cannot accumulate an unlimited number of large finished replies.

When reply memory is exhausted, async reads remain queued subject to their deadlines.
Normal synchronous raw reads use the same bounded FIFO and timed waiting when reply memory is temporarily full.
Only an inline reentrant read from a completion callback currently owning a batch's reply credit fails immediately
with `RejectedExecutionException`: waiting on that callback's own credit could deadlock.
After normal FIFO admission is exhausted, an ordinary synchronous caller may wait for reply credit in a SEPARATE
overflow lane: at most 8192 caller threads and 16 MiB of request/key admission charges per runtime.
This lane does not create or dispatch another worker. Only the existing caller waits on a condition using the
same request deadline; while waiting it owns neither a physical I/O counter nor a reply reservation.
On admission it reserves the full reply budget and executes the direct read on that caller.
Direct/overflow reads never bypass the reply-memory budget. Reentrant completion callbacks cannot enter this wait.
This is overload rejection, not a claim that the entity itself exceeds the per-point size limit.
Avoid blocking callbacks; move substantial application work to a separately bounded application executor.

The default global raw-content cap is 256 MiB, independent of the pool-derived concurrency.
It is NOT a 256 MiB JVM-heap promise. Decoded strings may require up to twice their source byte count;
binary transport buffers temporarily coexist with them. RESP metadata, retained keys, queued requests, futures
and decoded application entities add overhead. Reserve materially more heap or lower the content budget.
Application-retained completed futures are outside the runtime's ownership after mapping/callback exit.
Outstanding async admission is separately bounded by queue capacity + resolved in-flight limit + async-direct
limit, at most 6144. Timeout delivery retains that admission until physical I/O and callbacks actually exit.

## Allocation Safety And Cluster Behavior

A Lua script checks STRLEN for every key before internal MGET materializes values, then reads requested PTTL.
The client also validates binary shape, TTL sentinels and actual lengths.
The 16 MiB per-attempt content budget is cumulative across all slots, not 16 MiB per slot.
Split slots are read sequentially with the remaining budget; there is no static per-slot quota.

A multi-identity content-budget error discards the bounded partial attempt and retries at most 512 singleton
identities. Each singleton receives the full configured per-point budget. Valid individual rows are not rejected
just because their combined batch is too large. Only an individually oversized entity/tombstone pair receives
the deterministic size error. Debatching does not accumulate 512 large replies before delivering them.
Admission pressure can still cause a queued read to time out.

Debatched waiters use the SAME count/byte queue, not a second continuation backlog.
A request admitted through that queue keeps its original reservation while selected for I/O and throughout
budget-only retries; selecting a batch does not create room for another full queue behind its retry backlog.
Singleton retries preserve original order and cannot merge again. Cancellation of a waiting retry removes
its exact reservation; cancellation or timeout during physical I/O retains that reservation until I/O exits.
This intentionally conservative accounting may reject new work even when the deque itself is temporarily empty.
The normal FIFO/debatch count never exceeds 4096 and its charged bytes never exceed 16 MiB.
Caller-owned sync overflow is separate, bounded by 8192 waiters and another 16 MiB of admission charges.
Do not interpret 4096 as a bound on ALL waiting callers across both lanes.
An overflow lane already in use preserves FIFO order among synchronous overflow callers; newer synchronous
arrivals cannot barge ahead of its head. Existing normal FIFO work drains first when physical/reply credit allows.
Continuous async FIFO traffic can delay overflow until its finite deadline; this is not a global fairness guarantee.
Each credit event signals only the overflow head, chaining to the next only while whole-reply credit remains.
Timeout/interruption removes that caller's exact overflow charge. Shutdown wakes all overflow callers to reject;
it never pretends a blocked socket or callback released physical/result resources.

Standalone transport uses binary EVAL in a pipeline. Cluster transport uses Jedis' normal public binary EVAL
executor, including MOVED slot refresh and connection-local ASKING, with the driver's configured bounded attempts.
No additional transport retry is added. ACL, type, malformed-reply and socket failures are not silently retried
or downgraded to unguarded GETs. Different slots and redirects can require additional exchanges.
EVAL includes STRLEN, internal MGET and optional PTTL; one logical batch is not one total Redis command.
Redis ACLs must permit EVAL, STRLEN, MGET and PTTL.

## Timeouts, Cancellation And Shutdown

There is no batching timer. Each admitted async request has a bounded delivery alarm.
The alarm thread performs no I/O and never completes futures or runs callbacks; expiry delivery is dispatched
separately. An active read can time out while its socket is still blocked. Physical permits and retained-result
credit are never released merely because its future timed out or was cancelled.
JVM pauses, CPU starvation and thread-creation failure prevent hard real-time guarantees.

Queued cancellation removes the request and its exact queue charge before callbacks.
Cancelling one waiter never interrupts shared I/O or cancels another request.
Queued sync waits use timed future waiting outside monitors. Sync-overflow condition waiting releases the bookkeeping
lock, and interruption (including caller-task cancellation with interruption) removes that waiter's reservation
without cancelling another request. Cancelling an external caller future without interruption does not stop that
caller's synchronous operation. Immediate caller-owned sync I/O is still limited
by the configured transport timeouts. Wait timeouts use `JedisConnectionException` with a `TimeoutException` cause.
A raw deadline is not an end-to-end deadline for arbitrary application mapping, source or fetch-plan work.

Closing rejects new work, detaches queued work and waits at most two seconds for actual I/O.
Active deadlines remain effective after that drain interval. Live I/O and blocked callbacks may finish later;
their counters and memory credit are not reset to pretend shutdown completed.
Close the read runtime before its borrowed client; it does not close the Redis client itself.

## Metrics And Troubleshooting

`metrics()` exposes batches, coalescedRequests, averageBatchSize and bypassed per runtime.
Batches count logical transport attempts, including content-budget failures. Coalesced requests count extra
identities in attempted batches, not proven saved network commands.
Bypassed counts disabled/direct admissions and identities separated after a budget error.

`deliveryMemory()` exposes reservedBytes, limitBytes and batches. Reserved bytes include worst-case replies
for active I/O plus actual raw content retained by unfinished mapping/callbacks; batches can include callbacks
whose I/O already finished. The four transport counters alone do not measure retained-result memory.
`syncOverflowWaits()` exposes waiters, retainedBytes, limit and byteLimit for the separate caller-owned lane.
Waiting overflow callers are not `syncDirect` I/O and do not consume raw async delivery admission.

For rising rejections or deadlines, inspect queue pressure, deliveryMemory, slow callbacks, Redis latency and
pool wait before increasing limits. For a size error, inspect the combined entity/tombstone size.
For ACL errors, grant only the required script/command permissions; do not replace the guarded path with GET.
For Cluster errors, inspect topology, redirects and configured retry duration. Local-cache topology uncertainty
requires fail-closed admission; successful driver-internal redirects do not prove immediate local invalidation.
Measure Lua/STRLEN cost and split-slot latency in the target environment before enabling merging for performance.

