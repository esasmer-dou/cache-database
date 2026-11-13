# Bounded Retention

`RetentionSweeper` executes finite administrative passes over registered retention targets.
The native `RedisRetentionAdapter` selects at most 128 candidates per sorted-ZSET window and
examines at most 10,000 candidates per call. It owns no scheduler, thread pool or SQL connection.

## Coverage and Actions

**The ZSET sweep covers the indexed resident set only, including stale index memberships.
It does not discover or enforce retention over all cold SQL history.** Cold writes that were
never admitted to the cache, evicted source rows and unindexed rows require a separate,
indexed source-retention process. This API is not tenant purge or a full SQL-erasure certificate.

`EVICT_CACHE` removes cache payload, exact/sort/prefix/token/composite/partition memberships,
index metadata, hot-set membership and owned tenant-admission accounting. SQL, persistent tenant
owners, versions, tombstones, durability markers and queued writes are retained. Read-through
can admit a source row again unless the same retention policy also gates read admission.

`DELETE` uses the same explicit canonical Lua DELETE resource as the normal Redis producer.
It preserves version, tombstone, stream/compaction, tenant and configured trace behavior.
`deleteAccepted` counts Redis acceptance, not SQL commit or individual-write durability.
The existing latest-state durability contract is not strengthened by a sweep.

## Configuration

```java
@CacheRetention(field = "createdAt", days = 90, action = RetentionAction.EVICT_CACHE)
```

The generated `EntityMetadata.retentionPolicy()` descriptor is required; annotations are SOURCE
only and are not discovered by reflection. The field must be persisted and indexed, with range
indexing enabled. Days are 1..36,500. `Instant`, `LocalDateTime` and `OffsetDateTime` use epoch
milliseconds; `LocalDate` uses UTC epoch days.

Construct the adapter with the actual metadata, payload codec, typed ID decoder, `CachePolicy`,
key strategy, index/function/guardrail/write-behind settings, configured
`RedisFunctionArgsMapper`, producer guard, optional actual local L1 store, clock and settings.
The Redis client is borrowed; its owner supplies finite pool/socket/command timeouts and closes it.

Configure the same mapper used by normal writes with
`withNearInvalidationPublisher(coordinator::publishLegacyInvalidations)`.
Both actions consult that live decision during argument construction. Legacy publication defaults
on. Suppression requires validated SERVER_TRACKING opt-out and healthy complete tracking; unhealthy
tracking restores compatibility publication while L1 admission stays disabled. A suppressed publish
keeps empty wire slots, preserving tenant and expected-version positions. DELETE publishes once,
inside the shared producer; EVICT_CACHE uses the same configured channel/message decision.

The actual local L1 store is invalidated before mutation I/O and again in a finally block after
I/O exits, including unknown outcomes. This advances per-ID/segment stamps and the compatibility
global epoch, rejecting late fills from older reads. Remote invalidation is asynchronous.

## Concurrency and Limits

Payload age is checked after decoding. The exact payload, sorted score, version, owner, index
snapshot, admission lock and lease are rechecked atomically before deletion. With
`generationGuarded=true`, the OPEN generation is captured in the same snapshot and must still
match during mutation. Close/re-enable between snapshot and mutation therefore skips the row.
Generation-stamped DELETE uses separate protocol fields, never trace metadata or business columns.
Generation safety requires the same protocol on every producer, replay and transactional SQL flusher.
Protected DELETE also requires a verified, durably reserved pair-scoped version range from
`JdbcTenantVersionAllocator`, installed by `RedisTenantVersionAllocator`. Allocation uses the
shared explicit helper resource, never per-ID INCR fallback. The full batch is preflighted against
remaining range capacity before any deletion. Exhaustion fails closed. All producer/replay paths
must share this allocator before receipt/causal monotonicity across purge/re-enable can be certified.

Missing payload cannot authorize DELETE; there is no SQL lookup or foreground SQL reservation.
Unknown layouts fail closed. Projection bindings are unsupported. Native DELETE requires the
canonical function template; custom function sources/templates are rejected instead of parsed
or silently replaced by a different write protocol.

Bounds: 4096 command keys, 16 KiB..8 MiB command budget, 15..120 second renewable lease, 128
compaction shards, 1024-byte IDs, 256-KiB payloads, 160 metadata fields of at most 4096 bytes,
and a 4-MiB aggregate payload/metadata snapshot budget. Oversized input fails closed.
Each pass also has 256 windows and a 30-second admission budget; finite client I/O timeouts remain
necessary. A small command budget can end a pass early without dropping identities.

`examined`, `indexCleaned`, `evicted`, `deleteAccepted` and `skipped` are distinct.
`exhausted` describes the current indexed window, not a concurrent snapshot or all-history coverage.
An error or timeout can leave acceptance unknown; Redis script errors are not rollback.
Do not blindly retry writes or treat cancellation as rollback.

`RetentionJobAdapter` is optional and disabled by default. It submits at most 16 jobs per tick
through an existing distributed-job executor and explicit bounded argument codec; it creates no
scheduler. Native distributed leases exclude overlapping passes. It never schedules tenant purge
or re-enable. Use bounded attempts/backoff and low-cardinality metrics without tenant/ID/payload labels.

## Separation From Purge

Retention DELETE intentionally enqueues the normal guarded SQL write-behind operation.
It must not be used to implement Redis-only tenant purge: `PurgeOptions.includeSource=false`
forbids SQL DELETE enqueue and retains source rows. Tenant purge closes durable admission and uses
separate Redis cleanup; optional source erasure uses explicit bounded JDBC DELETE.
