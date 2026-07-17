# Scheduled Warm And Hot-Set Reconciliation

Turkish version: [../tr/docs/periodik-warm.md](../tr/docs/periodik-warm.md)

Use `@CacheScheduledWarm` when an existing SQL data set must be refreshed into
Redis on a bounded schedule and rows that no longer match the active-data policy
must be removed from Redis incrementally.

This feature is a repair loop, not an unbounded database synchronization engine.
Each annotated method returns one explicit, indexed, bounded `CacheWarmPlan`.

## What It Guarantees

| Event | CacheDB behavior |
| --- | --- |
| A write goes through CacheDB | Redis is updated first, then write-behind persists the command to SQL. A scheduled warm is not required for that write. |
| Another application writes directly to SQL | Scheduled warm sees the row on its next successful cycle if the query selects it and the hot policy admits it. Use outbox/CDC when one schedule interval of lag is unacceptable. |
| A cached row ages beyond a `TIME_WINDOW` | Repository reads evaluate the policy again and do not serve the stale row. Reconciliation removes its entity, indexes, and projection payloads from Redis incrementally. |
| A pod dies while warming | Its Redis lease expires. Another pod can execute a later trigger. Hydration version fences keep a replay from overwriting newer Redis state. |
| Two or more pods trigger together | One pod owns the lease. Other pods wait for at most `lockWaitTimeoutString`, then either observe the completion marker or skip that cycle. |

Periodic execution cannot provide zero-lag knowledge of direct SQL writes. For
that requirement, use the [Outbox and CDC Apply Runner](outbox-cdc-apply-runner.md)
and keep scheduled warm as a reconciliation safety net.

## Copy-Paste Example: Recent Or Active Orders

The named query must use the same business window as the entity hot policy. It
must also have an index that starts with its filter/sort columns.

```java
@CacheNamedQuery("activeOrderWindow")
public static QuerySpec activeOrderWindowQuery(long cutoffEpochSeconds, int limit) {
    return QuerySpec.anyOf(
                    QueryFilter.gte("order_date", cutoffEpochSeconds),
                    QueryFilter.in(
                            "status",
                            List.<Object>of("NEW", "PAID", "PICKING", "OPEN", "PENDING")
                    )
            )
            .orderBy(QuerySort.desc("order_date"), QuerySort.desc("order_id"))
            .limitTo(limit);
}
```

The scheduled method is declarative. It takes no arguments and returns a
`CacheWarmPlan`; CacheDB owns scheduling, locking, heartbeat, completion
deduplication, and incremental cleanup.

```java
@Component
public final class ScheduledWarmPlans {

    private final GeneratedCacheModule.Scope domain;
    private final int maxRows;

    public ScheduledWarmPlans(
            GeneratedCacheModule.Scope domain,
            @Value("${app.warm.orders.max-rows:1000}") int maxRows
    ) {
        this.domain = domain;
        this.maxRows = maxRows;
    }

    @CacheScheduledWarm(
            name = "active-order-window",
            enabledString = "${app.warm.orders.enabled:true}",
            fixedDelayString = "${app.warm.orders.fixed-delay:PT15M}",
            initialDelayString = "${app.warm.orders.initial-delay:PT30S}",
            lockAtMostForString = "${app.warm.orders.lock-at-most-for:PT2M}",
            lockWaitTimeoutString = "${app.warm.orders.lock-wait-timeout:PT20S}",
            minimumIntervalString = "${app.warm.orders.minimum-interval:PT15M}",
            reconcileHotSet = true,
            reconcileMaxRowsPerRunString = "${app.warm.orders.reconcile-max-rows:10000}",
            reconcileScanCountString = "${app.warm.orders.reconcile-scan-count:500}"
    )
    public CacheWarmPlan activeOrderWindow() {
        long cutoff = Instant.now().minus(Duration.ofDays(90)).getEpochSecond();
        return domain.orders().warmPlan(
                "active-order-window",
                domain.orders().queries().activeOrderWindowQuery(cutoff, maxRows),
                maxRows
        );
    }
}
```

The matching policy in `application.yml` is:

```yaml
cachedb:
  registration:
    source: jdbc
    entities:
      OrderEntity:
        hot-entity-limit: 100000
        entity-ttl-seconds: 0
        hot-policy:
          mode: COMPOSITE
          composite-operator: ANY
          children:
            - mode: TIME_WINDOW
              time-column: order_date
              hot-for-seconds: 7776000
            - mode: STATE_WINDOW
              state-column: status
              state-values: [NEW, PAID, PICKING, OPEN, PENDING]
  scheduled-warm:
    enabled: true
    scheduler-pool-size: 2
    heartbeat-threads: 1
```

This example means **last 90 days OR an active status**. If Redis must contain
only the last 90 days, use a single `TIME_WINDOW` policy and remove the status
branch from both the policy and the query.

## Multi-Pod Execution

All pods register the same job name and schedule. At trigger time:

1. Each pod checks the Redis completion marker.
2. One pod obtains `<keyPrefix>:coordination:scheduled-warm:<job>:lock` with `SET NX PX`.
3. The owner renews the lease on a dedicated heartbeat executor while JDBC warm is running.
4. Other pods wait in bounded intervals; they do not call the JDBC loader while the lease is held.
5. The owner warms Redis, runs one incremental reconciliation segment, verifies that it still owns the lease, and writes the completion marker.
6. A waiting pod rechecks the marker and skips duplicate work.

The lease is a coordination fence, not a distributed transaction. If Redis is
partitioned or the JVM pauses longer than the lease TTL, the current run is
marked `LEASE_LOST` and no completion marker is written. A later run may replay
the same bounded plan. Warm hydration is version-fenced and must remain
idempotent.

## Annotation Parameters

| Parameter | Default | Rule |
| --- | --- | --- |
| `name` | class and method name | Must be unique across the application and stable across deployments. A name is permanently bound to one entity in the Redis coordination keys. |
| `cron`, `fixedDelayString`, `fixedRateString` | empty | Configure exactly one. Duration values accept ISO-8601 (`PT15M`) or `ms`, `s`, `m`, `h`, `d`. |
| `zone` | system zone | Used only with `cron`. Prefer an explicit zone for calendar schedules. |
| `enabledString` | `true` | Supports Spring property placeholders and is useful for environment-specific rollout. |
| `mode` | `ENTITY_AND_PROJECTIONS` | Other modes are `PROJECTIONS_ONLY` and `DRY_RUN`. Reconciliation requires `ENTITY_AND_PROJECTIONS`. |
| `lockAtMostForString` | `PT5M` | Lease TTL if the owner disappears. It is renewed while the run is alive and must be at least one second. Set it above expected Redis/network pauses, not above the whole job duration. |
| `lockWaitTimeoutString` | `PT30S` | Maximum time a losing pod waits before it skips the current trigger. |
| `lockRetryIntervalString` | `PT0.25S` | Delay between lease attempts while waiting. |
| `minimumIntervalString` | schedule interval | Cluster-wide deduplication window after a successful run. Set it explicitly for cron jobs. |
| `reconcileHotSet` | `false` | Re-evaluates cached entity payloads against the current hot policy and removes rejected entity/projection data without touching SQL. |
| `reconcileMaxRowsPerRunString` | `10000` | Bounded reconciliation target for one trigger. Redis scan count is a hint, so a scan batch can make the observed count slightly larger. |
| `reconcileScanCountString` | `500` | Redis `ZSCAN COUNT` hint. Keep it bounded to avoid long background Redis calls. |

## Capacity And Lag Calculation

Warm input is bounded by both `CacheWarmPlan.maxRows` and the JDBC loader/read
guardrails. Do not set the scheduled plan above `maxQueryLoadRows`.

For cleanup planning:

```text
estimated full cleanup cycle = ceil(hot-set rows / reconcile rows per run) * schedule interval
```

Example: `100,000 / 10,000 * 15 minutes` is approximately 150 minutes for a
full scan. This is a capacity estimate, not a hard SLA, because Redis `SCAN`
family commands are cursor-based and the set can change while it is scanned.

If a 15-minute physical-eviction SLA is required, either inspect the whole
bounded hot set per run or use a shorter interval. Measure Redis CPU, SQL
latency, connection-pool saturation, and GC before increasing either value.

## Status And Failure Handling

Inject `CacheScheduledWarmRegistry` to expose local runtime snapshots:

```java
@GetMapping("/ops/cache-warm")
List<CacheScheduledWarmSnapshot> scheduledWarm() {
    return registry.snapshots();
}
```

States are `REGISTERED`, `RUNNING`, `COMPLETED`, `SKIPPED_NOT_DUE`,
`SKIPPED_LOCK_TIMEOUT`, `FAILED`, and `LEASE_LOST`. The registry is pod-local;
central alerting should collect logs/metrics from every pod. The sample projects
expose `GET /api/warm/schedules` for this purpose.

For reconciliation, inspect `lastInspectedRows`, `lastEvictedRows`,
`lastMissingRows`, `lastInvalidRows`, `lastReconciliationCursor`, and
`lastReconciliationCycleCompleted`. Missing or unreadable Redis payloads are
evicted cache-only so one poisoned entry cannot block cursor progress. A full
cycle is complete only when the cursor returns to `0`.

## Production Classification

- **BEST:** bounded indexed query, matching hot policy, projection-first list route, Redis lease, reconciliation, metrics, and outbox/CDC when direct SQL writes need low lag.
- **ACCEPTABLE:** scheduled warm plus reconciliation when one schedule interval of source-data lag is allowed.
- **ANTI-PATTERN:** unbounded full-table warm, one job per tenant without quotas, a scheduler pool larger than the SQL pool, or treating periodic warm as a zero-lag CDC mechanism.
