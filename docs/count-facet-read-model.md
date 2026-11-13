# COUNT/FACET Read Models

Turkish version: [../tr/docs/count-facet-read-model.md](../tr/docs/count-facet-read-model.md)

Use an explicit read model for a dashboard that can accept a declared observation
age. A refresh computes one bounded `CountFacetValues`; Redis retains that
published generation so readers do not enumerate entities or rerun the source
query. This is a separate API, not a transparent replacement for repository
`COUNT` or `FACET`.

```java
CountFacetReadModel model = database.countFacetReadModel(ticketMetadata,
        new CountFacetDefinition("support-ticket-status-v1",
                Duration.ofSeconds(15), Duration.ofMinutes(5), Duration.ofSeconds(30)));
```

Pass the actual, already registered `EntityMetadata<?, ?>` for the source.
The factory uses the database's Redis connection and key strategy. Metadata
binding does **not** generate or authorize the source SQL, nor does it reproduce
entity TTL, ownership, tombstone, or row-level access rules for the supplier.
The application must explicitly implement its aggregate's selection and security
contract. There is no hidden source-TTL interpretation or live-query fallback.

## Lifetime And Results

The API exposes `definition()`, `inspect()`,
`refresh(Supplier<CountFacetValues>)`, and `requireFresh()`.

| Inspection | Meaning |
| --- | --- |
| `MISSING` | No retained snapshot; `snapshot()` is empty. |
| `FRESH` | Redis check time minus `observedFrom` is strictly less than `maxAge`. |
| `STALE` | A snapshot exists but its observation age is at least `maxAge`. |

With this definition, **age equal to 15 seconds is `STALE`**. Freshness starts
at Redis server time when the refresh lease is acquired, before the supplier
runs, not when the result is published. The five-minute retention starts at
publication; keeping a stale snapshot does not authorize serving it as fresh.
`inspect()` neither refreshes nor queries SQL. Storage, ACL, malformed-state,
and clock-consistency errors propagate; they are not reported as `MISSING`.

| Refresh | Meaning |
| --- | --- |
| `PUBLISHED` | This call published a new generation; `snapshot()` contains its receipt. |
| `BUSY` | Another refresh holds the lease; this call did not invoke the supplier. |
| `LEASE_LOST` | The supplier ran, but this call no longer owned the lease and did not publish its result. |

`requireFresh()` returns a `CountFacetSnapshot` only after a fresh inspection;
otherwise it throws `IllegalStateException`. It does not refresh. A published
result is not necessarily fresh: source execution can consume the entire
`maxAge` while still finishing within the longer lease. A snapshot records
`generation`, `observedFrom`, `observedThrough`, `publishedAt`, and `values`.
These timestamps bound an observation window; they do not establish a
transaction spanning SQL and Redis or a source-wide transactional snapshot.

## Copy-Paste Example: Support-Ticket Dashboard

This application class accepts the real metadata instead of assuming a generated
binding constant. It expects a registered, tenant-scoped, **non-purge-protected**
ticket entity and its matching SQL `DataSource`. The example table is
`support_ticket(tenant_id, status, deleted_at, ...)`. All authorized readers of
one tenant must be entitled to the same status totals. Authenticate and authorize
the tenant before calling these methods; `CacheTenantContext` is not an
authorization service.

The single query returns at most four groups and derives the total from those
same rows. Adapt the fixed SQL to the application's real schema, access rules,
and expiry policy. On SQL Server, use `COUNT_BIG(*)` instead of `COUNT(*)` when
the selected cardinality can exceed the SQL Server `COUNT` integer range.

```java
import com.reactor.cachedb.core.model.EntityMetadata;
import com.reactor.cachedb.core.readmodel.CountFacetDefinition;
import com.reactor.cachedb.core.readmodel.CountFacetRead;
import com.reactor.cachedb.core.readmodel.CountFacetReadModel;
import com.reactor.cachedb.core.readmodel.CountFacetRefresh;
import com.reactor.cachedb.core.readmodel.CountFacetSnapshot;
import com.reactor.cachedb.core.readmodel.CountFacetValues;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.starter.CacheDatabase;
import java.sql.SQLException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.sql.DataSource;

public final class SupportTicketDashboard {
    private static final System.Logger LOG =
            System.getLogger(SupportTicketDashboard.class.getName());
    private static final Set<String> GROUPS =
            Set.of("OPEN", "PENDING", "RESOLVED", "OTHER");
    private static final CountFacetDefinition DEFINITION = new CountFacetDefinition(
            "support-ticket-status-v1", Duration.ofSeconds(15),
            Duration.ofMinutes(5), Duration.ofSeconds(30));
    private static final String SQL = """
            SELECT dashboard.status_group, COUNT(*) AS ticket_count
            FROM (
                SELECT CASE status
                    WHEN 'OPEN' THEN 'OPEN'
                    WHEN 'PENDING' THEN 'PENDING'
                    WHEN 'RESOLVED' THEN 'RESOLVED'
                    ELSE 'OTHER'
                END AS status_group
                FROM support_ticket
                WHERE tenant_id = ? AND deleted_at IS NULL
            ) dashboard
            GROUP BY dashboard.status_group
            ORDER BY dashboard.status_group
            """;

    private final DataSource sourceDataSource;
    private final CountFacetReadModel model;
    private final Supplier<CountFacetValues> source = this::loadValues;

    public SupportTicketDashboard(CacheDatabase database,
            EntityMetadata<?, ?> ticketMetadata, DataSource sourceDataSource) {
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(ticketMetadata, "ticketMetadata");
        if (ticketMetadata.tenantFieldName() == null
                || ticketMetadata.tenantFieldName().isBlank()) {
            throw new IllegalArgumentException("A tenant-scoped ticket binding is required");
        }
        this.sourceDataSource = Objects.requireNonNull(sourceDataSource, "sourceDataSource");
        this.model = database.countFacetReadModel(ticketMetadata, DEFINITION);
    }

    public CountFacetRefresh refreshTenant(String authorizedTenant) {
        try (var scope = CacheTenantContext.withTenant(authorizedTenant)) {
            return model.refresh(source);
        }
    }

    public CountFacetRead inspectTenant(String authorizedTenant) {
        try (var scope = CacheTenantContext.withTenant(authorizedTenant)) {
            return model.inspect();
        }
    }

    public CountFacetSnapshot requireFreshTenant(String authorizedTenant) {
        try (var scope = CacheTenantContext.withTenant(authorizedTenant)) {
            return model.requireFresh();
        }
    }

    public ScheduledFuture<?> scheduleTenant(String authorizedTenant,
            ScheduledExecutorService scheduler) {
        Objects.requireNonNull(scheduler, "scheduler");
        return scheduler.scheduleWithFixedDelay(() -> {
            try {
                CountFacetRefresh result = refreshTenant(authorizedTenant);
                LOG.log(System.Logger.Level.DEBUG,
                        "Ticket dashboard refresh: {0}", result.status());
            } catch (RuntimeException failure) {
                // Do not log tenant ids, SQL values, or exception messages here.
                LOG.log(System.Logger.Level.WARNING,
                        "Ticket dashboard refresh failed: {0}",
                        failure.getClass().getSimpleName());
            }
        }, 0, 5, TimeUnit.SECONDS);
    }

    private CountFacetValues loadValues() {
        String tenant = CacheTenantContext.currentTenant().orElseThrow();
        var groups = new LinkedHashMap<String, Long>();
        long total = 0;
        try (var connection = sourceDataSource.getConnection();
                var statement = connection.prepareStatement(SQL)) {
            statement.setString(1, tenant);
            statement.setQueryTimeout(5);
            statement.setFetchSize(4);
            statement.setMaxRows(5);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    String group = rows.getString(1);
                    long count = rows.getLong(2);
                    if (rows.wasNull() || count <= 0 || !GROUPS.contains(group)
                            || groups.size() == 4 || groups.putIfAbsent(group, count) != null) {
                        throw new IllegalStateException("Invalid ticket aggregate result");
                    }
                    total = Math.addExact(total, count);
                }
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Ticket dashboard source query failed", failure);
        }
        return new CountFacetValues(total, groups);
    }
}
```

Call `refreshTenant(authorizedTenant)` for an explicit initial refresh, then
`requireFreshTenant(authorizedTenant).values()` to serve a fresh result. The
scheduled method uses the **same supplier**, establishing tenant context on the
worker thread. Supply a lifecycle-managed, bounded shared scheduler; retain and
cancel its returned task on shutdown. Schedule only a bounded, trusted tenant
roster, not one new task or executor per incoming request.

For a fresh-only HTTP endpoint, return `200` only for `FRESH`; map `MISSING` and
`STALE` to `503 Service Unavailable` with the explicit status and no aggregate
values. Map storage/ACL failures to a separate unavailable response, not a
fabricated `MISSING`. If the product deliberately offers a stale-display
endpoint, it may return retained values with an explicit `STALE` label and
observation timestamps, but it must not present them as a fresh response or
trigger an implicit source query. The example's `requireFreshTenant` is for the
fresh-only contract; the HTTP layer must map its unavailable outcome explicitly.

Multiple pods may schedule the same model. The Redis `SET NX PX` lease with a
unique token excludes overlapping refresh owners, and publication checks that
same token atomically. It does not deduplicate sequential refreshes from
different pods or coordinate different model keys. Budget and stagger the total
source-query rate across the fleet.

## Source And Failure Limits

- **The 30-second lease is not a source timeout.** Expiry does not cancel SQL;
  a delayed supplier cannot publish after losing its lease. The example sets a
  five-second JDBC query timeout; also bound connection-pool acquisition,
  driver/network waits, scheduler concurrency, and Redis operations. Verify the
  driver's cancellation behavior rather than relying on the lease to stop work.
- Four result rows do not mean four source rows examined. Use a selective tenant
  predicate and an appropriate source index/query plan; measure and cap source
  cost. This API does not make an unbounded scan inexpensive.
- `CountFacetValues` allows a nonnegative total and at most 256 groups. Keys must
  be well-formed UTF-16, at most 4,096 UTF-16 units each; weights must be positive
  and their sum must not exceed the total. An empty result is count zero and an
  empty map. The Redis implementation also caps encoded values at 4 MiB and
  checks hash field lengths before loading their values. A raw-query snapshot's
  16 MiB budget is not this API's payload allowance.
- Snapshot group names and counts are ordinary, **unencrypted Redis values**;
  entity field-encryption annotations do not encrypt this payload. Do not use
  secrets or personal-data dimensions. Prefer fixed, non-sensitive group labels
  as in the example and restrict Redis access accordingly.
- A supplier exception or invalid result does not replace the prior snapshot;
  there is no automatic source retry. A later scheduled invocation may try again.
  Handle `BUSY`, `LEASE_LOST`, stale/missing results, and thrown failures explicitly.
  If publication's network response is lost, do not assume that nothing was
  published: inspect retained state before deciding on a bounded retry.
- Monitor refresh outcomes, source latency, observation age, and stale/missing
  reads without logging payloads or tenant secrets. Map unavailable data to an
  explicit application response; never quietly switch to raw aggregates or
  serve a stale value through a fresh-only endpoint.

Raw entity/index, ownership, tombstone, expiry, and direct SQL changes do not
automatically invalidate this snapshot. `FRESH` means the declared observation
age is satisfied, not that every underlying row is still unchanged. If the
application requires immediate revocation or exact current Redis membership,
this observation-based read model is not that contract.

## Identity And Purge Restrictions

The configured key strategy scopes a model by namespace, definition name, and
captured tenant (or explicit global scope). A tenant-scoped model requires tenant
context on every operation; a global model rejects an active tenant context.
The supplier runs synchronously in the captured scope and must not switch it.

The key does not fingerprint a SQL provider, `DataSource`, SQL text, principal,
or filter. Use separate namespaces/key prefixes for independently sourced
PostgreSQL, SQL Server, or Oracle datasets sharing Redis. All callers sharing
one identity must use the same definition and authorization audience. Change
the model name when its schema, grouping, filter, or security meaning changes;
do not let one caller overwrite another audience's aggregate. Use the configured
strategy rather than hand-building keys; it also supplies namespace routing for
Redis Cluster.

Each declared model/scope retains one persistent sequence key, including one
per tenant for tenant-scoped models. The retention TTL expires the snapshot
after publication, not that sequence. Budget the number of declared identities;
there is no automatic sequence cleanup or `TenantPurge` integration. Namespace
retirement requires explicit, coordinated administrative cleanup after stopping
all relevant readers and refreshers; deleting counters under live handles is
not a supported lifecycle operation.

**Sources with `tenantPurgeEnabled()` are rejected with
`UnsupportedOperationException`.** Existing entity purge participants do not
inventory aggregate hashes or fence their delayed publication. A refresh lease
and retention TTL are not purge barriers; group names and counts cannot safely
remain or reappear after a protected purge. Do not disable source protection to
enable this API. Before converting an unprotected source to protected operation,
stop its refreshers and explicitly remove and verify its retained model data;
there is no automatic protected-model migration.

This opt-in API does not change raw aggregate semantics or close the separate
[T4 performance gate](performance-benchmarks.md). It makes no twofold speedup
or release-readiness claim.
