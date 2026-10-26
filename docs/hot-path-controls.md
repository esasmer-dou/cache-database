# Bounded Read and Write Controls

[Türkçe](../tr/docs/aktif-veri-performans-kontrolleri.md)

For 0.17.0 numeric changes, aggregates, tenant scopes, local watchers and payload
evolution, start with [declarative data controls](declarative-data-controls.md).

These controls extend the Redis-first model. They do not turn arbitrary SQL into
an automatically complete Redis query. Keep route coverage, cold-read limits,
provider timeouts, and durable write-fence migrations in place.

## Choose the Right Control

| Situation | Control | Boundary |
| --- | --- | --- |
| Wide rows, few searchable fields | Explicit sparse indexes | Unindexed filters remain bounded residual queries |
| Many identical cold reads | Local single-flight | One leader per repository/JVM, not a distributed SQL cache |
| Very frequent point reads | Touch debounce; optional L1 | Approximate LRU; opt-in bounded staleness |
| Change two stock fields | `@CachePatch` | Absolute values, not SQL arithmetic or automatic merge of a missing row |
| SQL-backed product catalog | Auto-dirty snapshot | Marks roots after SQL durability; scheduling must be enabled |
| Multiple related writes | Unit of work | One bounded acceptance envelope, then one SQL transaction |

## Sparse Indexes and Typed Columns

```java
@CacheEntity(table = "products", redisNamespace = "products",
        indexedColumns = {"product_id", "category", "stock_quantity"})
public class Product {
    @CacheId(column = "product_id") public Long productId;
    @CacheColumn("category") public String category;
    @CacheColumn("stock_quantity") public Integer stock;
    @CacheColumn("description") public String description;
}
```

The processor generates `ProductCacheBinding.Columns.PRODUCT_ID`, `CATEGORY`,
`STOCK_QUANTITY`, and `DESCRIPTION`. Each column carries its entity, SQL name,
Java field name, and Java type. Generated codecs expose direct column access, so
residual filters and in-memory ordering need no per-row column map. Legacy codecs
continue through the existing map-based fallback.

```java
var source = SnapshotSource.entity("products", ProductCacheBinding.SOURCE)
        .where(SnapshotPredicate.gte(ProductCacheBinding.Columns.STOCK_QUANTITY, 1));
```

The default `indexedColumns = {"*"}` preserves old indexes. Explicitly narrowing
the set is a query-design decision: check every route, rebuild indexes with the
existing maintenance tools, and compare membership/order before cutover. Partition
and sort-index columns are included. Use `QueryIndexConfig.sparseIndexMetadata`
to disable sparse metadata without changing entity definitions.

## Source Load Protection and Touch Tracking

```java
var reads = ReadThroughConfig.builder()
        .mode(ReadThroughMode.READ_THROUGH_QUERY)
        .singleFlight(new SingleFlightConfig(true, 1024, 2048, 10_000))
        .build();
var policy = CachePolicy.builder()
        .hotEntityLimit(10_000)
        .accessDebounceMillis(500)
        .recentTouchSlots(8192)
        .build();
```

Identical ID/page/query misses share one bounded source load. Waiters receive
decoded copies, not a shared mutable entity. Failure, timeout, and interruption
release waiter permits; completed flights are removed. A waiting caller's timeout
does not cancel the leader's JDBC operation. Configure a JDBC query timeout too.
The default result budget is 16 Mi characters per flight; choose smaller row and
query limits for small pods. Different fetch plans and route contracts are not
coalesced accidentally.

Touch debounce is 500 ms by default; `0` retains every-read LRU tracking. A fixed
slot array never suppresses an unrelated colliding ID. Tenant byte-accounting
continues to use authoritative admission ownership. Normal tenant batch touches
are grouped and applied atomically where slots allow it; pressure/ownership
changes retain the conservative locked eviction path.

## Partial Updates

```java
@CacheRepository(entity = Product.class)
public interface Products extends CacheDbRepository<Product, Long> {
    @CachePatch
    WriteReceipt<Product, Long> replaceStock(
            @CacheId Long id, @CacheColumn("stock_quantity") Integer stock);
}
```

Unknown columns, wrong types, and attempts to patch ID/version/deletion fields
fail compilation. The queued payload and parameterized SQL UPDATE contain only
the changed columns. Redis Functions use compare-and-set for the cached full
payload; Unicode and the existing payload format are preserved. This path still
decodes/re-encodes a complete Redis entity when one is present. It is not a
zero-copy byte merge.

When Redis has no entity, receipt-returning patch commands can update an existing
SQL row without constructing a partial cached entity. A narrow version probe is
required when no Redis version exists. The receipt may have `entity == null`.
An entity-returning patch requires a complete cached entity. Deletes cannot be
recreated by PATCH. The SQL fence rejects missing predecessors and stale replay;
delta operations are never state-compacted. Cold projection refresh waits for SQL
durability and remains asynchronous.

## Automatic Catalog Refresh

Add these to a generated-entity snapshot plan:

```java
plan.scopedBy(source, ProductCacheBinding.Columns.PRODUCT_ID)
        .autoDirtyOnRootEntity();
```

```yaml
cachedb:
  snapshots:
    jobs:
      stock-catalog:
        enabled: true
        dirty-flush-interval: PT1S
        dirty-flush-threshold: 20
```

Spring wires the database bridge automatically. Programmatic `SnapshotJobs`
instances use `.withDatabase(database)` before `start()`. Only WORKER/FULL nodes
schedule work; shared Redis leases coordinate publication. Register the same
snapshot plans on worker nodes; a client-only plan cannot observe a different
application's SQL commit by itself. Producer acceptance does not dirty a SQL-backed
catalog before the row is durable. Root changes are
buffered with fixed capacity; overload or an ambiguous child delete/reparent
requests full reconciliation instead of guessing a parent. Periodic full refresh
and freshness guards remain necessary: the local notification bridge is not a
durable change-data-capture log.

## Atomic Unit of Work

```java
var receipt = database.inTransaction(tx -> {
    tx.put(order);
    tx.putAll(lines);
});
boolean durable = database.awaitDurable(receipt, Duration.ofSeconds(5));
```

For Spring services, `@CacheTransactional(awaitDurable = true)` opens the same
synchronous boundary; use `CacheUnitOfWork.current()` for explicit commands.
Plain entity-returning `save` and void `deleteById` participate. Receipt-returning
methods, including `saveAll`, intentionally fail before acceptance inside the
scope: use `tx.putAll` rather than fabricating a receipt before commit.

Limits remain 100 distinct entity identities, 4 MiB of encoded arguments, and
2000 stream fields. Atomic PATCH requires a complete cached versioned entity.
Nested/cross-database scopes, asynchronous methods, and bounded-tenant admission
contracts are rejected rather than silently weakened. Redis Cluster members must
share a hash-tag slot. An exception before commit discards staged writes. A
durability timeout **after acceptance is not rollback**; keep the returned receipt
from `CacheTransactionDurabilityTimeoutException` and inspect write-behind health.
Spring proxy rules apply: call a public, nonfinal service through its bean proxy,
not self-invocation. This is not a distributed XA transaction.

## Trace, Metrics, and Durability Waiting

`TraceContextPropagator` carries at most eight entries of at most 512 characters
per key/value. The optional Spring MDC bridge allowlists W3C context and correlation
IDs. It restores and closes scopes around worker SQL, recovery, and job handlers.
It does not create OpenTelemetry spans or install an exporter. Batched SQL uses
the first operation's scope without splitting the batch; other contexts remain
in the envelope. Do not put secrets or user-controlled unbounded baggage in it.

`CacheDatabaseMetricsMeterBinder` exposes local backlog/write counters and Redis/SQL
function timers. Latency timers require storage performance collection to be enabled;
disabling that collection leaves them at zero. Prometheus adds `_total` to counter names. Local durability waiters
are signaled immediately after marker advancement. Across processes, bounded
2/4/8/15/25 ms polling remains the fallback; network latency and scheduling still
affect end-to-end latency.

## Optional L1 Cache

```java
@CacheNearPolicy(maxEntries = 1024, ttlSeconds = 2, maxBytes = 4 * 1024 * 1024)
```

Apply this to an entity, or use `CachePolicy.Builder.nearCachePolicy`. L1 is
disabled unless enabled explicitly. Mutable values and records with mutable members
are decoded defensively. Generated deeply immutable records may share a decoded
instance when no schema upcasting, compression or relation processing is involved.
Segments evict by recent access. Limits are 64000
entries and an estimated 64 MiB reservation per runtime, split across registered
entities. The TTL cannot exceed 60 seconds or the observed Redis payload TTL.
Segmented eviction can retain fewer entries than the configured maximum.

One background subscription per runtime invalidates peer entries. Disconnect and
reconnect clear local state; reads fall back to Redis while disconnected. A fill
that races invalidation is rejected by an epoch check. Pub/sub is not durable:
this is TTL-bounded eventual consistency, not linearizability or a replacement
for fencing. Direct external Redis/SQL writes must use the supported apply or
reconciliation paths. Bounded-tenant routes bypass L1. Do not enable it for reads
that require observing every remote write immediately.

## Upgrade Safely

Align the BOM, processor, starters, workers, and samples at the same version and
regenerate bindings. Existing constructors and smaller Redis payloads remain
supported. Drain old workers before enabling PATCH producers: old workers cannot
decode the new operation kind. The default Redis Function library version advances
to `0.17.0`; upgrade the library before admitting new producers. Custom function
deployments must also use the matching source/version, not bypass drift protection.
Trace fields are optional for old envelopes. Keep
the durable fence metadata table and Redis version counters; do not reset them
as part of an upgrade. Validate provider-specific HA behavior in your own topology.

L1 Redis credentials need channel-scoped `SUBSCRIBE` and `PUBLISH` permission.
Publication permission is checked before a near-cached mutation starts; a Lua
runtime error after writes would not be a rollback. See the
[Redis Lua ACL API](https://redis.io/docs/latest/develop/programmability/lua-api/#redisacl_check_cmdcommand-arg).
