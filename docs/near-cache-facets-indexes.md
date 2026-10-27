# Near Cache, Facets and Composite Indexes

[Türkçe](../tr/docs/yakin-onbellek-facet-bilesik-indeksler.md)

These controls keep bounded operational reads in Redis. They do not transparently
cache arbitrary SQL or extend aggregates to database history. Library bytecode
remains Java 17 compatible; build and sample applications use Java 21.

## Product Stock: Fast Repeated Reads

```java
import com.reactor.cachedb.annotations.*;

@CacheEntity(table = "products", indexedColumns = {"tenant_id", "status", "stock"})
@CacheCompositeIndex(columns = {"tenant_id", "status"})
@CacheNearPolicy(maxEntries = 2048, ttlSeconds = 10,
        softTtlSeconds = 2, maxBytes = 4 * 1024 * 1024)
public record Product(
        @CacheId(column = "id") Long id,
        @CacheTenantId @CacheColumn("tenant_id") String tenant,
        @CacheColumn("status") String status,
        @CacheColumn("stock") int stock) {}
```

After warming the declared Redis set, `findAll(ids)` reads L1 first and fetches
only unique missing IDs. Result order and duplicate IDs retain their existing
contract; null IDs are ignored. Mutable results are not shared across calls.
Generated deeply immutable records can reuse decoded L1 objects.

Soft TTL is optional and defaults to zero. Between soft and hard TTL, a read can
return the cached value while a Redis-only refresh runs asynchronously. A shared
executor allows at most two refresh workers and 256 queued tasks. Duplicate
refreshes are suppressed; queue rejection releases its reservation. Hard expiry,
Redis remaining TTL, disconnect invalidation and byte budgets still apply.
`rejectedRefreshes()` and `failedRefreshes()` expose refresh pressure/failure.
This is eventual consistency, not a stock-reservation or payment guarantee.
Keep Redis command and pool-wait timeouts finite. Refreshes share the configured
Redis pool; reserve capacity for at most two background workers and the runtime's
invalidation subscription. A bounded queue does not fix an indefinitely blocked connection.

The sampled four-bit frequency sketch gives frequently read keys bounded second
chances during cold scans. Entry count and payload reservations remain bounded.
The sketch has at most 4096 longs per segment, in addition to entry reservations;
the runtime also caps registered entry count and store count.

## Support Dashboard: Groups Without Full Aggregates

```java
import com.reactor.cachedb.annotations.*;
import com.reactor.cachedb.core.query.*;
import com.reactor.cachedb.core.repository.CacheDbRepository;
import java.util.Map;

@CacheRepository(entity = Product.class)
public interface Products extends CacheDbRepository<Product, Long> {
    @CacheFacet(groupBy = "status")
    Map<String, Long> totals();

    @CacheFacet(groupBy = "status", function = AggregateFunction.SUM, field = "stock")
    Map<String, AggregateResult> stockByTenant(String tenant);
}
```

These methods generate direct calls, not reflection or a runtime dynamic proxy.
Filters derive from `ByFieldAndField` parameters; boolean `True`/`False` suffixes
are supported. Unfiltered methods need no parameters. Grouping must use a declared
index. A facet has at most 256 non-null groups, a bounded candidate set and a
16 MiB processing budget. Group keys are ordered deterministically. Excess limits
fail rather than silently truncating dashboard totals.

`aggregateBy` returns count, sum, min, max and average together. `decimalAverage()`
retains DECIMAL128 arithmetic; the existing `average()` remains OptionalDouble.
Generated facets may return `Map<String, Long>`, `Map<String, BigDecimal>` or
`Map<String, AggregateResult>`. Long conversion is exact and rejects fractional
or overflowing results instead of silently changing financial totals.

Eligible indexed scalar aggregates use one read-only Lua call per batch of at most
512 candidates. Numeric values are accumulated in Java, never as Lua floating-point
amounts. Expired and tombstoned rows are excluded. Multi-slot Cluster shapes fall
back to bounded pipelines. Predicate/index resolution and bookkeeping can add
calls; this is not a one-call guarantee for an entire HTTP request.

## Multi-Tenant Requests

```java
import com.reactor.cachedb.core.tenant.CacheTenantContext;

try (var scope = CacheTenantContext.withTenant(authenticatedTenant)) {
    var counts = products.totals();
    var receipt = session.inTransaction(tx -> {
        tx.put(firstProduct);
        tx.put(secondProduct);
    });
}
```

`authenticatedTenant`, repositories, session and domain values are supplied by the
application; never trust a raw caller header as authorization. Transaction commands
return real acceptance receipts only at commit. Ownership is checked before any
mutation and again inside the atomic Redis script. Deletes require an existing
owner marker; new-row ownership bootstrap requires a trusted complete SQL ownership
loader. An unknown target is not automatically treated as safe to overwrite.

Atomic tenant batches require an explicit tenant scope, not a broad administrative
scope. A batch cannot be reused after switching tenants. Cluster atomic batches
must share a hash slot. Cross-tenant writes remain denied even if an L1 ownership
memo is stale. The memo is bounded to 16,384 entries and 4 MiB logical reservation
per repository; owner markers remain immutable and persistent in Redis.

Tenant partition windows filter before selecting each partition's top rows.
Tenant projection queries check base ownership before pagination and bypass the
shared projection query cache. An internal per-tenant projection index avoids
scanning other tenants' global memberships and is maintained during index rebuilds.
A projection need not expose tenant information to
the caller. These compatibility paths cap candidate sets at 10,000 and processing
at 16 MiB; use an explicit tenant-indexed read model for larger screens. They do
not hydrate SQL history or issue a separate SQL call per partition. Base owner
markers must be established before serving tenant projections.

## Read Your Write Across Pods

```java
import com.reactor.cachedb.core.causal.CacheCausalContext;

String token;
try (var scope = CacheCausalContext.withReceipt(receipt.redisNamespace(), receipt)) {
    token = CacheCausalContext.encodeToken();
}
// On the subsequent request, after authentication and tenant scope setup:
try (var scope = CacheCausalContext.withToken(token)) {
    var product = entityRepository.findById(receipt.id());
}
```

A stale or unknown-version L1 entry is bypassed. The Redis read must meet the
minimum version; if it cannot, the read fails rather than returning older data.
The token is bounded to 128 identities and 4096 characters. It is not signed,
not an authentication credential, not proof of SQL durability and not a projection
freshness certificate. Bound transport headers and authenticate the caller first.
Scopes close in reverse order and do not propagate automatically to executors.

Since 0.18.1, `CacheCausalContext.wrap` captures the current requirements:

```java
Runnable task = CacheCausalContext.wrap((Runnable) () -> repository.findById(id));
executor.execute(task);
java.util.function.Supplier<Order> read = () -> repository.findById(id).orElseThrow();
var future = java.util.concurrent.CompletableFuture.supplyAsync(
        CacheCausalContext.wrap(read), executor);
```

Runnable, Callable and Supplier overloads restore the worker's prior scope even
on failure. An empty capture masks any worker scope for the call. Use a typed
task or cast to disambiguate Callable/Supplier. Existing size/nesting limits apply.
Cluster causal bulk reads group at most 512 IDs by slot. Co-located triples use
atomic MGET; split triples verify the version before reading payload/tombstone.
This is a minimum entity-version contract, not a multi-row snapshot. Atomic
writes still require a shared hash tag. Propagate authenticated tenant scope
separately; the causal wrapper does not grant or transfer authorization.

## Composite Index and JDBC Upgrade Safety

A composite declaration contains 2–4 distinct persisted columns and automatically
adds its columns to generated index metadata. At most 32 declarations are allowed.
Tuples use length-prefixed URL-encoded components, skip null components and cap
encoded tuple length at 4096 characters. Updates move membership from the old
tuple to the new one. Index rebuild/clear includes composite sets.

Regenerate bindings, deploy all writers, then run `rebuildIndexes`. From 0.18.1,
a deterministic namespace signature enforces this requirement: a missing or
different signature on non-empty data means `PENDING_REBUILD`. Reads use the
existing single-column/intersection path, not an incomplete composite SET.
Writes continue maintaining composites. Successful rebuild publishes the signature
and enables `VERIFIED`; signature checks occur at most once per five seconds.
An empty namespace initializes atomically when signature/index/hot-set keys share
a slot; otherwise it remains pending until rebuild. Any declaration change,
including removal, requires rebuild. Bounded index cleanup removes stale SETs;
a scan-budget failure leaves indexes degraded. Do not rebuild while old bindings
still write: the signature is not a distributed writer-version registry.
Composite indexes follow the framework's
existing index-maintenance/degraded-index recovery contract; they do not turn a
multi-key query into a transactionally consistent snapshot or make all Cluster
slots atomic. Atomic write batches mark indexes for recovery before serving them.

JDBC mixed-operation compaction is opt-in through
`@CacheEntity(..., independentWriteReordering = true)`. Use it only when foreign
keys, unique constraints, triggers and business rules permit independent writes
across distinct IDs. Default behavior preserves order. Same-key UPSERT/PATCH/DELETE
chains retain causal barriers even after opt-in. Do not enable this to hide
transaction dependencies between different rows or tables.

Soft-TTL refresh workers are lazy and coordinator-owned: at most two daemon
threads, 256 queued tasks and 258 reservations. `close()` cancels queued work,
interrupts workers and waits up to two seconds; later refresh calls are ignored.
Java cannot forcibly terminate application tasks that ignore interruption;
retain finite Redis/I/O timeouts. No static executor or budget spans runtimes.

Tenant aggregate and facet Lua optimization is **Deferred to 0.19.0**. These
routes retain the bounded, correct pipeline implementation in 0.18.1.
