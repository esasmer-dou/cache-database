# Declarative Data Controls

[Türkçe](../tr/docs/deklaratif-veri-kontrolleri.md) · [Release contracts](releases/v0.17.0.md)

Use these features for a declared Redis active set. They do not populate all SQL
history automatically. Provision the SQL schema, start the configured CacheDatabase
and worker, and warm existing rows before using a cache-only route.

## Configuration

| Override | Default | Purpose |
| --- | --- | --- |
| `cachedb.config.resourceLimits.maxWatchersPerEntity` | `64` | Maximum local listeners per repository; allowed range 1..64. |
| `cachedb.config.resourceLimits.defaultCachePolicy.compressionThresholdBytes` | `0` | Compression threshold for the default entity policy; zero disables its compression setting. |
| `cachedb.config.mutations.maxPatchAttempts` | `4` | Bounded compare-and-set attempts, at most 16. |
| `cachedb.config.queryIndex.maxMaterializedCandidateIds` | `10000` | Candidate materialization budget; exceeding it fails rather than returning a truncated aggregate. |

Apply overrides at bootstrap. An explicit per-entity policy can replace the default
policy. When both the entity annotation and its policy specify compression, the
larger threshold applies. To disable new compressed writes, set both to zero;
existing compressed values remain readable. Keep callbacks short, handle conflicts
at the business boundary, and monitor watcher drops, latency and Redis memory.

## Inventory: Replace or Add?

An absolute PATCH means “set stock to 10.” A delta means “subtract 3 from the
currently accepted stock, but never go below zero.” Do not implement the latter
as an unlocked Java read followed by an absolute replacement.

```java
package example.inventory;

import com.reactor.cachedb.annotations.*;

@CacheEntity(table = "inventory", redisNamespace = "inventory",
        indexedColumns = {"sku_id", "status", "available_units"})
public record Inventory(
        @CacheId(column = "sku_id") Long skuId,
        @CacheColumn("available_units") Integer availableUnits,
        @CacheColumn("status") String status) {}
```

```java
package example.inventory;

import com.reactor.cachedb.annotations.*;
import com.reactor.cachedb.core.query.AggregateFunction;
import com.reactor.cachedb.core.repository.CacheDbRepository;
import java.math.BigDecimal;

@CacheRepository(entity = Inventory.class)
public interface InventoryRepository extends CacheDbRepository<Inventory, Long> {
    @CachePatch
    boolean reserve(@CacheId Long skuId,
            @CacheDelta(value = "available_units", min = 0) Integer change);

    long countByStatus(String status);

    @CacheAggregate(function = AggregateFunction.SUM, field = "available_units")
    BigDecimal sumUnitsByStatus(String status);
}
```

With the SQL table and running database already configured:

```java
database.registerJdbcBacked(InventoryCacheBinding.METADATA,
        InventoryCacheBinding.CODEC);
var inventory = new InventoryRepositoryCacheDbImplementation(database);
var receipt = inventory.save(new Inventory(101L, 10, "ACTIVE"));
boolean accepted = inventory.reserve(101L, -3); // current stock becomes 7
long activeRows = inventory.countByStatus("ACTIVE");
BigDecimal units = inventory.sumUnitsByStatus("ACTIVE"); // 7 in this active set
```

`false` from the generated boolean delta method means no complete hot target.
`CacheDeltaBoundsException` means insufficient stock/balance for the declared
bounds. Optimistic conflicts are separate: PATCH retries are limited by
`cachedb.config.mutations.maxPatchAttempts` (default 4, maximum 16). Do not retry
bounds failures or blindly replay a delta after an ambiguous network timeout:
this command has no business idempotency key. Redis acceptance is not SQL commit.
Wait for the relevant receipt's durability when the business boundary requires it.

The SQL queue carries the computed absolute changed value, not a second increment.
Hot Redis payloads still require full decode/encode; this is not a zero-copy update.
An entity with multiple numeric deltas is accepted only if all checks succeed.
Delta and aggregate arithmetic reject precision above 1,024 digits or an absolute
scale above 1,024 before aligning decimals, preventing oversized intermediate allocations.

## Dashboard: Count and Sum the Active Set

COUNT and numeric aggregates do not mean “all database rows.” With a 90-day hot
policy they describe the currently available active population. Use an explicit
SQL reporting route for complete historical financial totals.

Covered predicates can resolve IDs without reading entity bodies. COUNT validates
live/tombstone membership without payload GET/MGET. Covered numeric fields use
the original numeric value in index metadata, preserving decimal precision beyond
the exact integer range of a Redis double score. Unsupported predicates, missing
numeric indexes and dynamic admission policies use bounded payload work instead.
Empty SUM is zero; empty MIN/MAX are null; empty AVG is `OptionalDouble.empty()`.
COUNT includes matching rows; AVG ignores null numeric values.

## SaaS: Open an Authenticated Tenant Scope

Mark one persisted String field/component with `@CacheTenantId`. The processor
adds its SQL column to the exact-index contract, even when sparse indexes are used.

```java
try (var scope = com.reactor.cachedb.core.tenant.CacheTenantContext.withTenant(
        authenticatedTenantId)) {
    // Use a repository whose entity declares @CacheTenantId.
    tenantRepository.findHotById(recordId);
}
```

Use `wrap`, `wrapCallable` or `wrapSupplier` to transfer a captured scope to a task;
ThreadLocal state is not inherited automatically. Tasks restore the prior scope
even when they fail. Do not trust MDC or an incoming HTTP header as authorization.

Queries/counts/aggregates receive the tenant predicate automatically. Foreign IDs
and foreign writes are rejected. The tenant field itself cannot be patched.
Ownership bootstrap consults the trusted by-ID source once when ownership is
unknown; the Redis mutation also checks persistent ownership metadata. Global
entity IDs cannot be reused by another tenant after deletion.

The trusted source must implement `EntityOwnershipLoader`, including soft-deleted
rows. `registerJdbcBacked` supplies it. Bulk writes check unknown IDs in SQL groups
of at most 500 instead of one SQL request per entity.

An absent scope is denied for a tenant entity. Trusted maintenance must explicitly
open `CacheTenantContext.administrative()` and close it with try-with-resources.
Enforce a scope at your ingress and service boundary. Generic raw SQL, custom source callbacks and snapshot source
definitions remain explicit application contracts; do not assume arbitrary SQL is
rewritten safely. Unimplemented tenant batch/partition/projection combinations fail
closed rather than silently bypassing isolation.

## Operations: Observe Local Predicate Transitions

```java
var rows = database.repository(InventoryCacheBinding.METADATA,
        InventoryCacheBinding.CODEC);
try (var registration = rows.registerWatcher(
        com.reactor.cachedb.core.query.QuerySpec.where(
                InventoryCacheBinding.Columns.STATUS.eq("LOW_STOCK")),
        event -> System.getLogger("inventory").log(
                System.Logger.Level.INFO, event.changeType().name()))) {
    // Keep this registration alive for the required application lifecycle.
}
```

For a Spring bean, a public `void` method accepting
`CacheChangeEvent<Inventory, Long>` can declare
`@CacheWatcher(entity = Inventory.class, filterField = "status", filterEquals = "LOW_STOCK")`.
The processor generates a direct method-reference adapter, without reflective
event invocation. Closing the registration disables future callbacks.

Watchers report local cache acceptance, not SQL durability or every pod's changes.
They have no initial result replay, durable replay, exactly-once delivery or
transactional sorted-window semantics. Dispatch is bounded; monitor drops and keep
callbacks nonblocking. Use outbox/CDC for reliable business events.

## Deployment: Read Old Data Without Flushing Redis

`@CacheSchemaVersion(version = 2, upcaster = InventoryV2Upcaster.class)` selects a
pure upcaster. With generated entity codecs, transform the length-prefixed body,
not arbitrary JSON text. For example, an explicit rename can preserve every other
column:

```java
public final class InventoryV2Upcaster
        implements com.reactor.cachedb.core.codec.CachePayloadUpcaster {
    public int targetVersion() { return 2; }
    public String upcast(String body, int fromVersion, int toVersion) {
        if (fromVersion != 1 || toVersion != 2)
            throw new IllegalArgumentException("Unsupported schema transition");
        var fields = com.reactor.cachedb.core.codec.LengthPrefixedPayloadCodec.decode(body);
        String old = fields.remove("quantity");
        fields.putIfAbsent("available_units", old == null ? "0" : old);
        return com.reactor.cachedb.core.codec.LengthPrefixedPayloadCodec.encode(fields);
    }
}
```

The example's default zero is a business decision, not an inferred framework rule.
New readers upcast old bodies; old readers cannot automatically read a future
schema. Coordinate reader/producer rollout and SQL DDL separately. Read upcasting
does not itself rewrite the Redis value.

## Large Documents: Enable Compression Selectively

```java
var policy = com.reactor.cachedb.core.cache.CachePolicy.builder()
        .hotEntityLimit(5_000)
        .compressionThresholdBytes(4096)
        .build();
```

Alternatively set `compressionThresholdBytes = 4096` on `@CacheEntity`.
Compression uses UTF-8 bytes, not Java character count; Base64 overhead is included
when deciding whether a frame is smaller. Default zero means disabled. Readers
continue to decompress existing frames after disabling compression. Output is
bounded to 16 MiB and the underlying codec can be stricter. Measure Redis bytes,
CPU and tail latency with representative data; no compression percentage is promised.

L1's decoded-object sharing is deliberately disabled for compressed/upcast paths:
a small wire frame must not conceal an unbudgeted large decoded object.

### Snapshot Temporary Files

Snapshot plans have a separate opt-in because JSON snapshot responses are not
entity wire frames:

```java
var compactPlan = existingPlan.withSpoolCompression(4096);
// Register compactPlan in the same way as the original SnapshotPlan.
```

Only beneficial temporary-disk blocks up to 16 MiB are compressed. Larger roots
retain the existing streaming disk path. Published JSON, content digests, logical
byte counts and publication batch limits stay unchanged. This option saves
temporary disk space, not snapshot Redis RAM or HTTP bytes. It adds per-root flush,
read and compression work; enable it only when disk pressure justifies that cost.

## Ordered Reads and Local Cache

For supported COUNT_WINDOW routes, the common-slot server call returns the sorted
payloads and their TTLs together. L1 population subtracts the time spent receiving
and decoding the response and uses the existing invalidation epoch check. It does
not invent a TTL or prolong a Redis entry's lifetime. Custom client adapters that
do not supply TTLs cannot seed L1 through this fast path. Access bookkeeping remains
separate; one data-fetch call is not a promise that the whole route uses one command.
