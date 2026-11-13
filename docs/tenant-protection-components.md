# Tenant Protection Components

`JdbcTenantPurgeGuard`, `RedisGenerationBarrier`, `RedisTenantVersionAllocator`,
`JdbcTenantVersionAllocator`, `RedisVersionInventory`, `RedisPurgeScanner` and
`JdbcPurgeScanJournal` implement low-level admission, range allocation and resumable discovery.
They are **not an end-to-end tenant-erasure service**. Discovery does not delete identities,
projections, shared memberships, queues/DLQs or L1 copies. Do not expose a complete-erasure endpoint
merely because these components are installed.

## Scope and Source Data

Scope is one **(Redis namespace, tenant)** pair, not the tenant across the whole keyspace.
Other tenants and namespaces are untouched. Purge leaves the pair CLOSED. Only a separately
authorized, verified new-generation activation may reopen it.

`PurgeOptions.includeSource=false` means Redis-only cleanup: remove owned Redis data, projections,
index/owner state and wholly owned old-generation queued entries through a qualified cleanup engine.
It MUST NOT call the canonical write-behind DELETE or enqueue any SQL DELETE. Source rows remain,
the durable SQL guard still closes, and `sourceRows` stays zero. These components do not yet
supply the destructive Redis/projection/queue engine.

`includeSource=true` permits explicit, bounded JDBC business-row erasure **after guard closure**,
not canonical DELETE enqueue. `deleteSource` removes at most 128 exact-tenant physical rows and
commits the physical-row count with progress under the same guard lock/transaction.
The SOURCE stage is forbidden when false; when true it must finish through `deleteSource`
before verification. Counts distinguish Redis identities, physical keys and direct source rows.
Unknown Redis mutation outcomes are not fabricated zero counts.

Redis-only purge must not silently rehydrate retained SQL/history or old external payloads into
a new generation. `prepareReenable` and `openPreparedGeneration` reject such an operation with
`MIGRATION_REQUIRED`. There is no bypass or automatic readmission API; a separately verified
source/history migration contract is required. Retention `DELETE` is a different operation and
intentionally uses ordinary SQL write-behind.

## Compatibility and Admission

Existing `WriteOperation`, `QueuedWriteOperation`, `WriteReceipt`, `CacheDatabaseConfig`
and `ExternalChangeEvent` record components stay unchanged. `TenantGenerationEnvelope<T>`
carries an original generation separately; never put it in trace metadata or business columns.
Redis acceptance is not an individual SQL commit. Existing latest-state durability semantics
are unchanged; rejection of an old generation cannot count as that old write becoming durable.

Install provider-specific V019 migrations through the application's migration tool. No runtime
DDL is executed. Missing guards/generation fail closed. Administrative closure and every flusher
call to `authorizeWrite(s)` lock the same durable guard row. The flusher uses the **same physical
JDBC connection and business transaction**, before any DML/fence filtering, holding the lock
through commit/rollback. In-flight guarded commits finish before durable closure.

Foreground admission remains Redis-first: generation, owner, version allocation and mutation
must be one atomic command. No foreground SQL lookup, identity reservation or SQL ledger exists.
Close SQL first, then Redis. Prepare a new generation durably, install Redis CLOSED and a verified
range, commit SQL OPEN, then open Redis. Lease renewal and fenced ownership are explicit;
lease release never opens admission.

Source erasure is disabled by the default guard constructor. Explicit reviewed binding
fingerprints (at most 64) attest bounded same-tenant trigger/RLS effects. `validateSource`
also checks tenant-leading indexes, types/encoding and restrictive foreign keys. Revalidate
after DDL. PostgreSQL uses bounded tableoid/ctid selection, SQL Server DELETE TOP and Oracle
ROWID selection with exact tenant comparison. Confirmation must match the requested tenant.

## Retained Version Ranges

Removing per-ID versions and restarting INCR at one breaks existing receipt/causal comparisons.
One retained SQL allocator/highwater exists per protected pair, not per entity. Administrative
or bounded background jobs durably reserve ranges of at most 1,000,000 versions; foreground
Lua consumes them with INCR followed by GET of the exact decimal string. The initial maximum is
**9,007,199,254,740,991**. Exhaustion, an unverified inventory or recovery mismatch fails closed;
there is no fallback to per-ID INCR for protected writes.

Before exposing a range, freeze all legacy producers/replays and inventory persistent Redis
owner markers AND the supported namespace, including cold versions/tombstones. Persist scan
frames before processing. Every consumed item must go through `RedisVersionInventory.maximum`
and `consumeVersionInventory` in addition to ownership classification; ordinary consumption
does not certify a numeric maximum. Seal only complete, unknown-free discovery. Unknown legacy
ownership prevents certification, even if some known IDs can be processed safely.

`scanDurableFences` scans the existing SQL write-fence primary key in pages of at most 128,
including deleted-row highwaters. This one-time conservative scan covers all fence rows because
that table has no tenant-leading version index. It is administrative, resumable and time-bounded
per page, never a foreground scan. `probeSourceMaximum` additionally requires a leading
(tenant, version) numeric index to include cold business rows. SQL fences are retained, never deleted.
`sealInventory` records the verified maximum and fingerprint before any reservation.

Reserve through `reserveClosed` or background-only `reserveOpen`, then install the committed
`TenantVersionRange` using `RedisTenantVersionAllocator.install`. The expected reservation
epoch prevents stale/interleaved retries. Only the latest identical request is idempotent.
A fresh range burns every unused value from previous ranges; installation never rewinds a counter.
Keep grants, guard/highwater and progress durable and unexpired. At Redis restart, reserve a fresh
range above the durable highwater; old grants are bound to the former process run ID and fail closed.

Protected operations check Redis process identity using INFO SERVER once per command (once per
retention batch). ACL and latency qualification must allow this overhead.
See [INFO SERVER](https://redis.io/docs/latest/commands/info/) and
[INCR](https://redis.io/docs/latest/commands/incr/). An in-place restore within the same Redis
process cannot be detected from run ID alone: close admission before restore, reconcile durable
state and burn a fresh range. SQL security-state rollback requires explicit recovery; it must
not auto-open. These components do not automatically recover or certify restored deployments.

## Identity Reservations Are Not Write Permits

`JdbcFreshIdentityAllocator` reserves numeric identities separately from write versions. This is an
administrative SQL component, not a new-record writer. Before using it, explicitly install the
provider's `V019_7__fresh_identity_allocator.sql` after the preceding V019 migrations. It creates
`cachedb_identity_allocator` and indexes the namespace bootstrap binding lookup; runtime code does
not install either object. Neither object belongs in tenant cleanup.

An administrator must independently reserve the entire identity domain exclusively for the namespace,
across all tenants, historical data and external writers. An empty Redis, a source-table `MAX(id)` or
a tenant purge cannot prove this exclusivity. Keep the authority ID, bounds and exclusivity token
stable; an existing domain cannot be replaced or widened.

For first-time setup, `CacheInitialTenantBootstrap.provisionIdentityDomain(request, domain)` uses the
real, still-closed INITIAL session and native closure checks. It does not fabricate purge progress,
advance bootstrap phases, allocate identities or open admission. Background `reserveOpen` and
maintenance `reserveClosed` allocate at most 1,024 identities per request, serialized by the durable
tenant guard and the namespace-global ledger. Ordinary entity writes do not query this ledger.

For `reserveOpen` and `reserveClosed`, only the exact latest request, including its expected reservation epoch, may be retried. Do not
change that epoch to retry an old request. Unused identities are consumed permanently; purge,
tenant reactivation and process restart never make them available again. Back up and restore this
ledger together with retained security metadata. A missing or rolled-back ledger needs explicit
recovery, not re-provisioning over a previously used interval.

**A SQL reservation alone never creates Redis ownership or authorizes `save`.** Never turn a
reservation into a manually inserted owner marker or a foreground SQL lookup.

### Prepare a Bounded New-Record Range

For the registered standalone `POINT_NO_QUOTA` INITIAL profile with canonical `Long` IDs,
`CacheInitialTenantBootstrap.reserveAndAdmitFreshRange(tenant, request)` combines a new durable
reservation with atomic native owner preparation. First provision the exclusive domain during
INITIAL, complete activation, and then call this method from an administrative job:

```java
// domain was explicitly provisioned; generation is the currently activated generation.
// expectedReservationEpoch is the durable allocator position, not the entity write version.
var request = new FreshIdentityRange.Request(
        domain, generation, 32, UUID.randomUUID(), expectedReservationEpoch);
FreshIdentityRange admitted;
try (var admin = CacheTenantContext.administrative()) {
    admitted = bootstrap.reserveAndAdmitFreshRange("tenant-a", request);
}
// Assign each returned ID once, then use the existing enrolled repository saveWithReceipt path.
// This result is not a WriteReceipt and does not mean an entity or SQL row was written.
```

The maximum is 128 IDs per call. The operation checks the actual registered binding, approved
runtime roster, OPEN generation/control epoch, primary process and every identity-local key.
One existing key rejects the entire range; it never overwrites an old or foreign identity.
Only one newly committed reservation may reach native preparation. A repeated committed request
is rejected, including after a lost response; do not retry it through `reserveOpen` or reconstruct
admission from a saved `FreshIdentityRange`. Inspect the durable allocator position and submit
a new UUID for a later range. Uncertain or rejected committed ranges remain consumed.

Normal enrolled writes keep their existing Redis receipt and asynchronous SQL durability contract;
they do not query the identity ledger. EMPTY activation still does not reclaim erased IDs: prepare
a later, non-overlapping range against the new generation. Admission atomically installs persistent
owner and version `0` markers, but no entity or queue entry. Zero is an unwritten baseline, not an
allocated write version, write receipt or SQL durability claim; the first ordinary write allocates
a positive native version. Purge discovers unused owner/zero-version identities
and erases their markers, but does not rewind the SQL identity ledger. Unused markers consume Redis
memory until purge. Bound the total preallocated capacity; this is not an automatic refill loop.
Other ID codecs, clustered Redis, quotas and projection-dependent creation are not supported by
this administrative path.

### Refill Write Versions Explicitly

Record IDs and write versions have separate durable allocators. Preparing IDs does not replenish
the native write-version range. Before that range runs out, an administrative job may reserve
and install a later range after INITIAL or EMPTY activation has completed:

```java
// generation is ACTIVE; expectedVersionEpoch is the current SQL version-reservation epoch.
// This is not the identity allocator epoch used by reserveAndAdmitFreshRange.
UUID refillRequest = UUID.randomUUID(); // retain for an exact retry after an uncertain response
TenantVersionRange installed;
try (var admin = CacheTenantContext.administrative()) {
    installed = bootstrap.refillVersionRange(
            "tenant-a", generation, 4096, refillRequest, expectedVersionEpoch);
}
// Normal repository writes continue without consulting SQL for each write.
```

The SQL reservation commits before native installation. An uncertain result must be reconciled
with the **same UUID, count and expected epoch**; do not invent a new request to replay the old
range. If the exact latest committed range is already known, use
`bootstrap.installOpenRefill("tenant-a", range)` in an administrative scope. Installation checks
that complete range against the locked SQL guard, retained activation proof, runtime roster and
the same Redis primary process. An exact retry never rewinds a consumed counter. A superseded
range, changed generation or changed process is rejected rather than silently repaired.

Each reservation is bounded by `TenantVersionRange.MAX_RANGE`. Moving to a new range burns any
unused versions in the old one. No scheduler or foreground SQL refill is installed automatically.
An exhausted range rejects writes until an explicit refill succeeds; it does not claim a receipt.
This API has the same standalone protected-profile limits as new-record admission above.

## Original Envelopes and Reads

`evalStamped` rejects a missing envelope and checks its original pair/generation atomically
with owner and allocator readiness. External events must be stamped at original capture,
never by inferring the current generation at dequeue. An external version domain must also
be verified against the allocator or explicitly mapped; a generation stamp alone is insufficient.

`captureOpen` captures a generation **before** an asynchronous source read. Pass that same
stamp to `evalOpen` for delayed fills; close/reopen must reject the old fill. Mutation-key
positions in `evalOpen` start at KEYS[3]; its internal allocator keys are appended afterward.
All payload reads, projections, refreshes and hydration must use atomic admission checks.
A separate guard GET followed by an unguarded operation is unsafe.

L1 hits bypass Redis unless admission is integrated. Keep protected-namespace L1 disabled until
a bounded, fenced admission/invalidation protocol covers reads and purge acknowledgements.
The supplied helpers do not modify repository, external-change runner or L1 caller paths.
Every producer/CAS/PATCH/batch/replay/flusher must carry the original generation and the shared
allocator version. Component tests are not proof of that complete integration.

## Bounded Discovery and Resume

Hot tenant indexes contain resident IDs only. Scan persistent version:tenant owners plus the
supported namespace and shared set/ZSET memberships. Resolve exact ownership; unknown owners,
ambiguous IDs or unsupported layouts produce an incomplete/migration result, not foreign deletion.
Complete and seal stable discovery before deleting ownership evidence or memberships.

Cluster scanning supports explicitly tagged co-located namespace layouts on the owning primary.
Role, slot ownership, topology and process run ID are checked before/after each page.
Restart, resharding or topology changes invalidate old cursors. Custom out-of-namespace layouts,
projection stores and global queues require separate qualified coverage.

`BoundedRedisWire` owns an explicitly closeable RESP2 transport and a single deadline thread.
Replies are at most 1 MiB, 8192 decoded nodes and six nesting levels; SCAN bulk items are at most
1024 bytes. Declared lengths are checked before allocation. COUNT 500 is a hint, not a reply cap.
Configure pre-resolved endpoints, authentication/TLS, finite connect/read/call deadlines and at
most eight admitted connections. Deadlines close physical sockets; admission releases only
after physical I/O exits. Bound the number of transports and close them explicitly.

Persist the entire bounded frame/input-next cursors/topology/page ID first. Consume at most
128 items per transaction, atomically committing spawned work, numeric observations and pending
offset. Cursor advancement waits for full-frame consumption. Unknown ownership remains sticky.
The purge-only worklist has source/item/byte bounds and transactional counters; it is not a
normal-write identity ledger or an unbounded Redis history set.

## Erasure and Recovery Limits

A complete-erasure claim still requires destructive identity/index/projection/L1 cleanup,
same-slot fenced queue/DLQ handling, replay/flusher integration, unknown-outcome reconciliation,
audit delivery and recovery qualification. Only wholly owned immutable old-generation queue entries
may be removed. Foreign entries remain; mixed-tenant atomic batches must not be partly erased.
Unknown legacy envelopes require migration. Drain/fence late retry/DLQ copies before completion.

`ACTIVE_STORES_RETAINING_SECURITY_METADATA` acknowledges retained security fences/highwaters,
progress/audit and tombstones, which can contain IDs and reversible locators. Completion is not
their erasure. Raw confirmation is request-local; new progress keys use SHA-256 pseudonyms.
Do not log tenant IDs, payloads or locators; use bounded opaque operator tokens and low-cardinality
metrics. This is not KVKK/GDPR certification, backup/external-system erasure or all-history retention.
The separate retention ZSET sweep covers the indexed resident set only.

The generic guarded command helpers cap inputs at 4096 total keys, 8192 arguments, 8192 UTF-8
bytes per key, 256 KiB per argument/script resource and 8 MiB aggregate command budget.
Malformed UTF-16 is rejected before encoding; byte accounting does not allocate encoded copies.
These helpers borrow the configured Redis client and require finite client/pool deadlines.
The bounded shared Lua source is cached as immutable data, not reopened on each foreground call.

## Entity-Owned Scope

An entity purge covers only the registered entity namespace and the requested tenant, together
with its explicitly declared entity-owned stores. It is not a tenant-wide closure across other
entities or an automatic search for personal data. General Spring Cache method values cannot be
attributed to an entity class automatically and are outside this scope unless an ownership
participant is explicitly registered and included in the required manifest. A declared required
participant that is absent or cannot verify ownership makes the result incomplete.

Mixed projections and atomic queue entries must preserve other entities and tenants. Unsupported
ownership, unknown legacy entries or incomplete bounded verification must not be reported as
successful erasure. This is not a universal PII erasure, KVKK/GDPR certification, or backup,
export, log or external-system erasure guarantee.
