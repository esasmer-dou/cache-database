# Bounded Redis Resident Re-encryption

AES rotation changes the current key; it does not rewrite previously stored values.
`RedisReencryptionRunner<T, ID>` performs an explicit, resumable maintenance pass over
Redis entity payloads. Reads use the bound codec's known old keys; replacement values
use the runner's immutable snapshot of current keys. Keep the independent HMAC key
unchanged so blind equality indexes remain usable.

## Scope

The supported scope is `REDIS_RESIDENT`: canonical entity payload keys in one registered
namespace, for one explicitly authorized tenant. The entity must have encrypted columns,
an indexed tenant field and `tenantPurgeEnabled=true`, with the actual runtime's enrolled
`RedisTenantReadFence` and generation allocator initialized. Missing capabilities fail
closed; a private fence is not an acceptable runtime binding.

This operation never calls a source loader, performs SQL, advances business versions,
enqueues write-behind work, or rewrites stream, compaction, DLQ, L1, projection or historical
payloads. In particular, `REDIS_AND_SOURCE` SQL ciphertext remains under its existing key.
`Scope.SOURCE` is rejected before any maintenance I/O. Source-wide rotation needs a separate,
explicitly verified source migration protocol, not a save loop or hidden SQL trigger.
Unprotected/non-tenant entities are not supported by this generation-fenced runner.

A completed SCAN pass is not a snapshot: concurrent inserts and evictions may be missed;
Redis may repeat keys. Counts describe examined occurrences, not unique entities.
`COMPLETE` applies only to owned resident payloads encountered in that pass, not to old-key
retirement. Do not remove old keys until all other retained stores and source rows have
been migrated or expired by their own verified protocols.

## Invocation

Construct one runner per registered entity/runtime, using the runtime's codec (including
payload framing/compression), key provider, Redis operations, key strategy, barrier,
enrolled fence and authenticated bounded transport. No static provider or thread-local
codec binding is used.

```java
var runner = new RedisReencryptionRunner<>(
    metadata, runtimeCodec, keyProvider, redisOperations, keyStrategy,
    generationBarrier, enrolledReadFence, boundedRedisWire);

try (var admin = CacheTenantContext.administrative()) {
    RedisReencryptionRunner.Step step;
    do {
        step = runner.reencrypt(Customer.class, "tenant-a", 250);
        // Schedule another bounded worker invocation when step.status() == MORE.
    } while (step.status() == RedisReencryptionRunner.Status.MORE);
}
```

A tenant-scoped caller can use `reencrypt(Customer.class, maxRows)` inside
`CacheTenantContext.withTenant(...)`. An explicit tenant must match that lexical scope
unless the caller is administrative. Do not expose the administrative scope to request data.
Run maintenance on a bounded worker, not on foreground request/event-loop threads.

Each call accepts 1..10,000 examined rows, at most 32 new scan frames and approximately
five seconds of scheduling work, plus the current finite socket/codec operation.
One runner admits one call without waiting. The Redis cursor additionally has a 30-second
lease to exclude competing runtime instances. Timeouts/unknown outcomes require a later
resume, not a blind new save.

## Safety And Progress

The original generation is captured before I/O. Publication verifies that exact OPEN
generation, the allocator/run-ID witness, raw tenant owner, read version, raw old payload,
hot-owner record and tombstone absence in one same-slot Lua command.
`SET XX KEEPTTL` preserves expiry. Payload replacement and row progress are committed
together; restart does not need a Java object or caller-held cursor.

The complete bounded SCAN frame and offset are saved in a Redis hash before processing.
The hash stores existing key names, configuration fingerprints and counters, never decoded
business field values. Apply the same access controls as the entity keyspace.
Progress expires after seven days without use; expiry restarts traversal rather than
asserting completion. Redis rollback/restart or topology changes reject the cursor.
Generation, mapping or current-key changes require explicit `startNewPass(entityClass, tenant)`
after the previous lease has expired/released. Never reuse an old cursor under a new stamp.

The native transport must resolve the same primary, TLS/authentication and Redis database
as the registered runtime. Redis Cluster requires the existing explicitly tagged same-slot
layout. MOVED/ASK, malformed replies, unknown schema and over-budget frames fail closed;
no guessed redirection or truncated SCAN advancement is allowed. A rejected frame retries
the same cursor with lower COUNT; COUNT=1 over budget requires operational remediation.

## Limits And Gaps

The resident encoded payload cap is 256 KiB per row. A malformed envelope, unknown old
key, mismatched row identity/tenant, missing version or non-round-tripping codec leaves the
row unchanged and increments `blocked`. Concurrent CAS failures increment `conflicts`.
Either produces `COMPLETE_WITH_GAPS`, not a complete rotation. Foreign owners are never
decoded or changed. Tombstoned/absent payloads are counted separately.

Existing tenant quota accounting is read through the canonical page-manager owner reader.
The pass does not change reservations or admission locks. An accounted row can be replaced
only when its encoded byte size does not grow; its existing conservative charge remains.
Larger accounted envelopes are deferred as blocked rows. Use the ordinary verified admission
protocol to change those reservations before retrying; do not bypass quotas.

The runner delegates serialization to the runtime-bound codec, verifies logical and index
round trips, and never rebuilds indexes from plaintext SQL columns. Compression/custom
field codecs remain the registered codec's responsibility. Nulls follow that codec's
existing contract. Legacy plaintext in an encrypted field is not a migration fallback.

Export Step counters and status through maintenance monitoring without tenant IDs, entity
IDs, payloads or provider exception causes. Alert on gaps, cursor mismatch, repeated timeouts
and lease contention. Resume with bounded backoff after transient errors; investigate schema,
provider or generation failures instead of automatically resetting progress.

## Purge Store Binding

Register the progress hash as an explicit owned maintenance store. Its exact key is
`runner.cursorKey(ProtectionKey.of(namespace, tenant))`. After approved global read/producer
closure, the purge coordinator can call `discardCursorAfterClose(lease, closedGeneration)`.
`DRAINING` is not a remote acknowledgement; `INCOMPLETE` is not deletion permission.
The hook checks the canonical closed generation, run ID, purge lease and local physical drain,
and removes only that tenant's recognized progress schema. Repeated cleanup is safe.
Keep unknown store/schema coverage incomplete until the actual manifest covers it; never
hide this key with a broad scanner exemption or treat this hook as a durable count journal.
