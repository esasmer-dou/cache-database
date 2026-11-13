# Entity-Scoped Tenant Data Protection

`CacheDataProtection.purgeTenant(entityClass, tenant, options)` is an administrative, bounded, restartable operation for one registered entity namespace and one tenant. It is not a tenant-wide keyspace eraser. The native plan explicitly lists the source table, Redis entity/page/index/projection stores, and every write/retry/outbox/recovery/DLQ stream that can carry that entity. Required additional stores use `PurgeParticipant`; a missing or unverifiable participant produces `INCOMPLETE`.

## Database Setup

Install the matching provider's `V019__tenant_protection.sql`, `V019_1__purge_facade.sql` and `V019_2__purge_list_scan_kind.sql`, in that order, through your database migration system before enabling tenant protection. Runtime code does not install DDL. The incremental migration adds the native `LIST` scan kind while retaining rejection of unknown kinds and all other scan constraints. Do not edit an already applied migration or delete retained security fences.

For the current protected-runtime APIs, install the provider's canonical migrations through `V019_6__empty_tenant_activation.sql`, including the intervening owner/bootstrap migrations. The new `cachedb_empty_activation` table retains administrative activation decisions; it is not application data and must not be deleted during tenant cleanup.

## Contract

The durable SQL guard closes first. Existing business transactions holding its row lock finish before closure commits. Flushers must acquire that same guard lock in their business JDBC transaction before filtering or applying DML. Ordinary reads/writes do not query SQL for a generation or reserve an identity.

Redis namespace admission and same-slot queue mirrors close next. `TenantReadFence` must close and drain all registered producer, read, L1, delayed-fill and projection-refresh routes across runtimes. It must supply live, lease/generation/manifest-bound evidence, not a Pub/Sub acknowledgement or a local CLOSED flag. There is no permissive default implementation.

Discovery scans persistent owner markers and the entire declared namespace, including cold/evicted identities absent from the hot index, then all discovered shared memberships and declared pages. It persists each complete bounded wire reply before consuming at most 128 items. Queue snapshots retain their upper ID and server identity; original generation envelopes are inspected without replacing them with the current generation. A stable durable worklist is sealed before owner/index membership is erased.

Queue erasure uses exact immutable entry comparison and deletes only wholly owned entries. Mixed atomic transactions, legacy envelopes, unknown owners, unsupported layouts and unverifiable history remain closed and return `INCOMPLETE`. Compaction payloads are erased before ownership markers. Native identity cleanup uses actual page/index/projection descriptors and invalidates local state. A second independently checkpointed namespace/owner/shared/page/stream sweep, source probe and participant verification must finish before certification. Temporary raw IDs, scan locators and queue payloads are then removed in bounded SQL transactions.

## Source Choice

- `includeSource=false` performs Redis/projection/index/owned-queue cleanup only. It never issues or enqueues a canonical SQL DELETE. Source rows remain, `sourceRows` is zero, and the final status is `MIGRATION_REQUIRED`; admission stays closed.
- `includeSource=true` physically deletes exact-tenant source rows in indexed batches of at most 128 after closure. The source DELETE and its durable progress/count commit share the guard-locked transaction. `COMPLETE` still leaves admission closed.

Reopening is never implied by purge completion. Retained source/history must not silently become new-generation data. Migration must inventory Redis and durable version/tombstone maxima, preserve the pair-scoped security highwater, reserve bounded ranges administratively/background-only, and prove every replay/external event retains its original generation. Unverified reopening fails closed. Versions are bounded by 9007199254740991; exhaustion and recovery uncertainty do not rewind or fabricate receipts.

### Explicit Empty Reopening

`reenableEmptyTenant` is a separate administrative decision after an `includeSource=true` purge reaches `COMPLETE`. It supports a reviewed single-entity namespace whose registered runtime routes can prove activation coverage. It does not restore backups, migrate legacy ownership, adopt retained rows or automatically reopen dependent namespaces.

`ACTIVE` certifies the admission lifecycle, not ownership of caller-supplied IDs. Purge removes erased identities' ownership markers; reopening does not restore them. `save` and `saveWithReceipt` reject an unowned ID with `MIGRATION_REQUIRED`, even when SQL and Redis are OPEN. For the supported standalone Long-ID INITIAL profile, explicitly prepare a new, exclusively reserved range through [bounded new-record admission](tenant-protection-components.md#prepare-a-bounded-new-record-range). This is a separate administrative step, not a general permission to create arbitrary or previously erased IDs. Do not create Redis owner markers manually or query SQL on the foreground write path. Ordinary unprotected repositories are not subject to this protected-profile restriction.

For example, purging customer A's order 42 and completing EMPTY activation permits an empty, generation-fenced read. It does not authorize recreating order 42 or inserting order 43. Do not use EMPTY activation alone as a customer delete-and-recreate workflow. [Initial source bootstrap](initial-source-bootstrap.md) and [existing-identity ownership preparation](source-owner-onboarding.md) also do not provide a general new-ID allocator.

Persist one activation UUID, the original purge options, completion certificate and exact plan manifest before the first call. The following continues one bounded step; keep its continuation for the next administrative job invocation, rather than looping in an HTTP request:

```java
var request = new EmptyTenantActivation.Request(
        persistedActivationId, EmptyTenantActivation.Mode.EMPTY,
        options, completedPurge.evidence(), registeredPlan.manifestToken());
try (var admin = CacheTenantContext.administrative()) {
    var next = protection.reenableEmptyTenant(
            Order.class, tenant, request, savedContinuation);
    savedContinuation = next.continuation();
    // ACTIVE is required for admission; writes also require verified ID ownership.
}
```

Use the same request after an unknown outcome. If administrative ownership expires, reacquire it with the same request rather than inventing another activation UUID. A conflicting certificate, manifest or activation is rejected. Keep external database writers quiescent during the reviewed purge/activation procedure; this API cannot intercept writes outside CacheDB.

| Interruption | Recovery contract |
| --- | --- |
| SQL records `PREPARED` | The next generation stays closed; resume the same activation. |
| SQL records `SQL_OPEN`, but native OPEN is absent | Retain the original reserved range and continue native activation; do not reserve a replacement. |
| Native OPEN succeeds but its reply is lost | Observe the matching native receipt; do not erase new rows or reset the counter. |
| A completed request is retried | Observe the same `ACTIVE` decision without taking a new control epoch or reinstalling its range. |

SQL lease expiry is not instantaneous cancellation of an already dispatched Redis operation. Such delivery may advance only the same irreversible `SQL_OPEN` decision. SQL completion still requires a live lease; native stale epochs are rejected after the newer epoch reaches that guard. Do not use lease expiry as a rollback mechanism.

## First Protected Namespace

`CacheInitialNamespace` and `CacheInitialTenantBootstrap` implement explicit first-time population of a protected namespace. This is separate from reopening a purged tenant or adopting an existing CacheDB namespace. An existing source table is eligible only when its ownership and source-version contract are reviewed; the absence of Redis keys is not proof of a clean history.

The bounded profile requires standalone Redis, disabled L1, `CLIENT_ONLY`, `REDIS_ONLY`, canonical `Long` IDs, and an explicitly bounded point-read window. Relations, projections, quotas, Redis Cluster and arbitrary legacy stores are not covered by this initial-population profile. Source rows must have exact tenant ownership and positive, supported integer versions. Both `(tenant, id)` and `(tenant, version)` leading indexes are required; source versions outside the selected window still contribute to the tenant's version highwater.

Before using this profile, install the matching provider's canonical `V014` and `V019` migrations through `V019_6__empty_tenant_activation.sql`, in order, through your migration system. Do not synthesize owner markers, OPEN flags, generation grants or version ranges.

The explicit setup order is:

1. Register the actual metadata and codec and review the source table, tenant column, version column, ID bounds, row/page/resident-byte budgets and Redis layout.
2. Provision the initial SQL generation as CLOSED. Prepare the namespace and review its descriptor. The SQL-backed namespace gate consumes its first-use grant before creating the native runtime roster; it is not an automatic fallback when a roster is missing.
3. Enroll every participating runtime. Register the real runtime-control participant, initial native-window participant and `JdbcInitialBootstrapPurgeParticipant` in the same reviewed purge plan. Seal the exact runtime roster and manifest; no participant may stand in for an unregistered store.
4. Create the bootstrap service from that namespace, enrollment and approved plan. Call `step(request)` administratively, preserving the same operation UUID and request across bounded continuations.
5. Wait for native/queue closure and physical read/fill draining, actual owner/namespace and durable-version inventory, exact-tenant source-version inventory, captured-page hydration and verification. Start normal application traffic only after `step` reports `ACTIVE`; an interrupted finalization can retain native OPEN before that final status is recorded.

Captured-page and hydration-acknowledgement interruptions resume the same persisted work and ciphertext. The coordinator does not reread an uncertain result as permission to create a different operation or allocate a replacement version range. Foreground protected reads remain Redis-only; they do not silently populate missing cold rows from SQL.

### Interruption Boundaries

| Interruption | Required behavior |
| --- | --- |
| Captured page or applied-page acknowledgement is lost | Resume the same request and committed page; do not duplicate or re-encrypt its payload. Admission stays closed until activation. |
| SQL activation commits, but native OPEN is not established | Keep Redis CLOSED and the original activation/range intact; return `RECOVERY_REQUIRED`. Automatic reopening is deliberately unsupported. |
| Native OPEN was established and only finalization was interrupted | Finalization may continue only after observing that exact original OPEN; it must not close it again or reserve/install another range. |
| An unknown generation, consumed birth grant or completed purge is encountered | Fail closed. A new operation UUID, a missing Redis roster or retained source rows do not authorize another initial bootstrap. |

`CacheInitialSourceBootstrapRedisTest` covers first-namespace population, committed-page acknowledgement loss, protected delivery, source/native purge and fail-closed activation uncertainty across PostgreSQL, MSSQL and Oracle. This bounded profile is not a qualification of arbitrary legacy migration, backup restore or a consumer application's deployment topology.

## Usage

The runtime supplies a reviewed `RedisTenantPurgePlan`, an entity registry, owned transports with finite timeouts, and a qualified distributed `TenantReadFence`.

```java
var protection = new CacheDataProtection(registry, List.of(registeredPlan), 2);
CacheDataProtection.Continuation continuation = null;
var options = new PurgeOptions(operationId, tenant,
        ProtectionKey.hash(operatorReference), true, 128, 120, 30_000,
        PurgeScope.ACTIVE_STORES_RETAINING_SECURITY_METADATA);
try (var admin = CacheTenantContext.administrative()) {
    CacheDataProtection.Result result =
            protection.purgeTenant(Order.class, tenant, options, continuation);
    continuation = result.continuation(); // null at a terminal state
    // Resume while RUNNING with the SAME operationId and continuation.
}
```

A RUNNING result retains an explicit expiring administrative lease, not a thread/connection. Pass its continuation on the next bounded call so distributed acknowledgements retain the same owner/epoch. Concurrent use of one continuation is rejected by a durable call claim. Lost continuations require lease expiry/takeover with the same operation UUID. Call `abandon(continuation)` to release administrative ownership without reopening admission.

Do not spin in a request thread. Apply bounded retry/backoff for `BUSY` or transient storage failure and resume the same operation UUID. Unknown outcomes retry the same durable work position; counts/offsets commit before Redis result acknowledgement. Lease loss or cancellation never reopens admission. The facade has no executor or hidden scheduler and rejects excess concurrent administrators. `maxCallMillis` bounds cooperative drain work, not a forced abort of already-running physical I/O; JDBC/socket deadlines remain mandatory, and capacity is released only after physical I/O exits.

`Result.queueEntries` counts wholly owned stream entries separately from physical Redis keys and source rows. Receipts/audits contain hashed scope/tenant/operator tokens, not raw tenant IDs, payloads or scan cursors. Confirmation uses the exact caller-provided tenant only for that request.

## Limits

Native plans are bounded to 64 entities, eight identity-preserving Redis targets per entity, 128 explicitly routed streams, 128 declared pages per target, 128 items per chunk and 1 MiB pending replies. SCAN COUNT 500 is only a hint: oversized physical replies fail closed instead of being truncated. SQL journals have explicit row and aggregate-byte budgets. Cluster namespace/identity cleanup requires the reviewed co-slotted layout; queue fencing uses each stream's actual slot and primary.

Unattributable shared planner caches, mixed projections, unknown legacy stores or unattested historical layouts require an explicit ownership/verification adapter or yield `INCOMPLETE`. Other tenants and other entities are never inferred as owned. Arbitrary Spring Cache method values are outside this entity-scoped operation unless an explicit owned participant is registered.

Retained SQL write fences, security generation/highwater and bounded progress/audit tombstones are intentional; security metadata can retain identifiers. This API does not erase backups, database redo/WAL, external exports, or unregistered stores and is not a KVKK/GDPR certification. Resident-index retention sweeping is a separate feature and does not cover all cold SQL history.
