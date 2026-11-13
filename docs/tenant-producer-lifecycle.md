# Tenant Producer Lifecycle

Tenant protection is scoped to one registered entity namespace and one tenant. It does not erase other entities, generic Spring Cache method results, backups, or undeclared external stores.

## Configuration

Use generated metadata with `@CacheEntity(tenantPurgeEnabled = true)`, a persisted indexed tenant field, and the canonical Redis function library. Configure encryption keys before registering encrypted entities.

```java
database.register(CustomerCacheBinding.METADATA, CustomerCacheBinding.CODEC, policy);
var producer = database.configureTenantProducer(CustomerCacheBinding.METADATA, 256);
var customers = database.repository(CustomerCacheBinding.METADATA, CustomerCacheBinding.CODEC, policy);
```

Configure every protected namespace before obtaining the first repository. The database owns producer shutdown. A standalone `DefaultCacheSession` caller must close the returned producer. Admission is bounded to 1..4096 physical operations per namespace, with at most 128 configured namespaces. Repository views share the same producer and local read fence. A protected namespace uses one cache policy.

Configuration does not provision or open a tenant generation. Admission requires a verified migration, durable SQL guard, complete ownership inventory and an installed Redis version range. Unknown ownership returns `MIGRATION_REQUIRED`; normal writes never query SQL to establish ownership. Do not create ownership markers manually to bypass this check.

## Writes And Reads

`save`, `saveWithReceipt`, optimistic save, `saveAfter`, `saveAll`, PATCH and single/bulk DELETE use the original admission generation. Batches are limited to 10000 rows, prepared in chunks of at most 128; protected function frames have an aggregate 8 MiB chunk limit. Bulk calls retain independent row acceptance semantics, not whole-call atomicity.

Redis atomically checks generation and ownership and allocates the accepted version from a retained namespace/tenant range. Versions are monotonic across explicit reopen, with an initial maximum of 9007199254740991. Range exhaustion and inconsistent recovery state fail closed. Range reservation is administrative/background SQL, never foreground write SQL. Existing public receipt and queue record shapes are unchanged.

A positive receipt version means Redis acceptance, not SQL durability. Use `awaitDurable(receipt, timeout)` to observe the existing latest-state durability contract; it does not promise preservation of every intermediate write.

Protected point reads use original-generation tickets and generation-framed L1 entries. Source reads require an existing versioned loader, a by-ID read-through mode and `hydrateLoadedEntities(false)`. They return without a Redis/L1 fill. Source hydration, query/page/aggregate/cursor routes, persisted projections, watchers, atomic unit-of-work and unstamped external events are rejected on this binding. They must not fall back to unguarded legacy paths.

## Closure And Failure

Each producer ticket is registered before Redis admission I/O and remains live through synchronous index, hot-set and invalidation work. Cancelling a caller future does not release a worker's ticket. A transport-uncertain outcome poisons a bounded slot and prevents closure acknowledgement. Restart alone is not proof that old writer connections or historical stores were drained.

Distributed purge requires the actual approved runtime/store inventory and fresh closure evidence from every participating runtime. A local drained counter is not distributed proof. `configureDataProtection` validates explicit plans against the database's actual mapping and Redis/key strategy; it does not generate approvals or replace missing evidence. Missing routes, unknown legacy ownership and unattested history remain `INCOMPLETE`.

Purge keeps tenant admission closed. With `includeSource=false`, source rows remain and no SQL DELETE is enqueued; reopen remains blocked pending an explicitly verified migration. With `includeSource=true`, only the declared bounded tenant source deletion is in scope. Durable generation/highwater security metadata is intentionally retained. No regulatory certification or universal personal-data erasure is implied.

Function-library drift protection remains enabled. Deploy a separately versioned compatible function library and coordinate all participating producers; never force a different body over an existing version or use a legacy function as fallback.
