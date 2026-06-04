# Production Recipes

This guide answers one practical question:

Which CacheDB surface should a production team use for a given workload?

If you first need the higher-level positioning story, read [CacheDB As An ORM Alternative](./orm-alternative.md).
If you are preparing the repository itself for outside users, also read
[Public Beta Readiness](./public-beta-readiness.md) and the
[Release Checklist](./release-checklist.md).

The answer is intentionally tied to the project's first priority:

- keep production runtime overhead low
- keep the library easy enough to feel like a serious ORM alternative

## 30-Second Choice

If you want the shortest possible recommendation:

- start new production services with `GeneratedCacheModule.using(session)...`
- move a proven hot endpoint down to `*CacheBinding.using(session)...`
- drop only measured hotspots, workers, or infra flows to direct repository usage
- use projections plus `withRelationLimit(...)` for relation-heavy list and dashboard screens

## Use-Case Cookbook

| Use case | Recommended design | Avoid |
| --- | --- | --- |
| Customer detail page with 1,000+ orders | Customer root entity, order summary projection, bounded per-customer hot window | Loading the full customer aggregate and every order line for first paint |
| Order detail page with many lines | Load order detail explicitly, preload only a small `orderLines` preview | Fetching hundreds or thousands of lines before the user asks for them |
| Global "highest value orders" dashboard | Ranked projection with a precomputed business rank field | Wide entity scan followed by in-memory sort |
| Admin CRUD screen | Generated module or binding | Premature direct repository code for non-hot admin paths |
| Write-behind repair or replay worker | Direct repository with explicit limits and retries | Hiding operational work behind high-level domain helpers |
| Existing ORM route migration | Migration Planner, dry-run warm, staging warm, side-by-side compare | Blind cutover based only on Redis latency |

The most common mistake is treating Redis as a magic full-aggregate cache.
CacheDB is faster when the read model is intentionally smaller than the durable
history stored in PostgreSQL.

## When Projections Are Required

Treat projections as required, not optional, when the screen shape matches one of these patterns:

- global sorted or range-driven list screens
- list or dashboard first paint that does not need the full aggregate
- relation preview rows where only a small child slice is visible
- screens that need a stable business ranking across large candidate sets

For the last case, prefer a projection-specific ranked field such as `rank_score` and query that field with a single sorted index. This is the production-friendly way to avoid expensive multi-sort tie boundaries on large global lists.

If you are defining a reusable projection in application code, make that intent explicit on the projection itself:

```java
EntityProjection<DemoOrderEntity, HighLineOrderSummaryReadModel, Long> projection =
        EntityProjection.<DemoOrderEntity, HighLineOrderSummaryReadModel, Long>builder(...)
                .rankedBy("rank_score")
                .asyncRefresh()
                .build();
```

That tells CacheDB the projection owns a pre-ranked business field and lets the projection repository take the ranked top-window fast path before it considers a wider candidate scan.

## Decision Flow

```mermaid
flowchart TD
    A["Are you building normal service endpoints?"] -->|Yes| B["Start with GeneratedCacheModule.using(session)"]
    A -->|No| C["Is this a proven hot endpoint or batch/infra flow?"]
    C -->|Yes| D["Use *CacheBinding.using(session) or direct repository"]
    C -->|No| E["Is the screen relation-heavy or list-heavy?"]
    E -->|Yes| F["Use projections plus withRelationLimit(...)"]
    E -->|No| G["Stay on GeneratedCacheModule until profiling says otherwise"]
```

## Decision Table

| Situation | Recommended surface | Why | Runtime overhead profile | When to move lower |
| --- | --- | --- | --- | --- |
| Typical business CRUD, service-layer apps, fast onboarding | `GeneratedCacheModule.using(session)...` | Lowest glue, most ORM-like, safest onboarding path | Low | Move lower only after a real hotspot is proven |
| Teams that want compile-time helpers without package-level grouping | `*CacheBinding.using(session)...` | Slightly more explicit, still generated, still low ceremony | Very low | Move lower when a single endpoint becomes latency-sensitive |
| Known hot read/write endpoints, batch jobs, infra services | direct `EntityRepository` / `ProjectionRepository` | Smallest wrapper surface and full control | Lowest | Stay here only for measured hotspots |
| Relation-heavy read screens | generated binding or minimal repository + projections + relation limits | Lets you keep ergonomics while avoiding wide object graphs | Low to very low | Move to minimal repository only if summary/detail still misses latency targets |
| Internal admin/reporting flows | generated module or binding | Developer speed usually matters more than shaving nanoseconds | Low | Usually not worth dropping lower |
| Replay/recovery/workers | minimal repository | Operational code should stay explicit and unsurprising | Lowest | Rarely needs more abstraction |

## Official Recommendation Ladder

Use these surfaces in this order:

1. Start with `GeneratedCacheModule.using(session)...`
2. Move hot endpoints to `*CacheBinding.using(session)...` if you need more explicit control
3. Drop only the proven hotspots to direct repository/projection usage

That keeps most application code ergonomic while preserving a clear escape hatch for the few paths that truly need it.

## Multi-Pod Coordination Smoke

Before you trust a new Kubernetes recipe, run the local multi-instance coordination smoke once against the same Redis/PostgreSQL pair:

```powershell
.\tools\ops\cluster\run-multi-instance-coordination-smoke.ps1 `
  -RedisUri "redis://default:welcome1@127.0.0.1:56379" `
  -PostgresUrl "jdbc:postgresql://127.0.0.1:55432/postgres"
```

What it verifies:

- pod-unique consumer names while consumer groups stay shared
- Redis leader-lease failover for singleton cleanup/history/report loops
- abandoned write-behind pending work being claimed and drained by another instance

Why it matters:

- correctness in a shared Redis stream model depends on unique consumer identity
- cluster noise stays lower when singleton ops loops really stay singleton
- this is the fastest way to catch local coordination regressions before a real multi-pod deploy

Local note:

- on one workstation, `HOSTNAME` is usually shared by every process
- Kubernetes pods do not have that problem because pod hostnames are already unique
- if you launch multiple local processes by hand, set explicit `cachedb.runtime.instance-id` values or use the smoke runner above

## What The Benchmark Means

The official recipe benchmark compares three CacheDB usage styles on top of the same repository path:

- `JPA-style domain module`
- `Generated entity binding`
- `Minimal repository`

Run it with:

```powershell
mvn -q -f cachedb-production-tests/pom.xml exec:java `
  "-Dexec.mainClass=com.reactor.cachedb.prodtest.scenario.RepositoryRecipeBenchmarkMain"
```

Output:

- `target/cachedb-prodtest-reports/repository-recipe-comparison.md`
- `target/cachedb-prodtest-reports/repository-recipe-comparison.json`

Important:

- this benchmark measures CacheDB API-surface overhead
- it does not measure external Hibernate/JPA runtime
- it does not replace the end-to-end Redis/PostgreSQL production scenario runs

Latest local benchmark snapshot after generated-surface caching:

- `Generated entity binding`: fastest average in the current local run
- `Minimal repository`: lowest p95 in the current local run
- `JPA-style domain module`: grouped ergonomic surface with modest wrapper cost

This is the key takeaway:

- the ergonomic surfaces are not free
- but their cost stays in the same low-overhead band as direct repository usage, so most business code should not be forced into minimal-repository style
- the real production latency drivers remain query shape, relation hydration, Redis contention, and write-behind pressure

## Quick Picks By Team Type

### Product service teams

Start with `GeneratedCacheModule.using(session)...`.

This gives you:

- the easiest onboarding path
- zero-glue startup in Spring Boot
- compile-time generated ergonomics
- low enough wrapper cost for normal production APIs

### Teams with a few hot endpoints

Keep most code on the generated domain module, but move the measured hotspot to `*CacheBinding.using(session)...`.

This is usually the best middle ground because:

- the rest of the code stays readable
- the hot endpoint gets a smaller wrapper surface
- you avoid prematurely dropping the whole codebase to low-level repository style

### Platform, worker, and operational teams

Use direct `EntityRepository` / `ProjectionRepository`.

This is the right tradeoff when:

- the code is operational rather than product-facing
- clarity matters more than helper ergonomics
- you want the smallest abstraction surface in replay, repair, or batch logic

## Migration Path From JPA/Hibernate

If a team is coming from JPA/Hibernate habits, do not force them to jump straight to minimal repositories.

Use this migration path instead:

1. Start on `GeneratedCacheModule.using(session)...`
2. Replace wide eager reads with projections and explicit detail fetch
3. Add `withRelationLimit(...)` to preview screens
4. Move only proven hotspots to `*CacheBinding.using(session)...`
5. Use direct repository style only for the few places where profiling says it still matters

This path keeps the mental model familiar while steering teams toward lower-overhead query shapes.

## Recipes

### Recipe 1: Default Service Team

Use this when:

- you want quick onboarding
- your team is coming from JPA/Hibernate-style habits
- most endpoints are normal CRUD or filtered list pages

Recommended surface:

```java
var domain = com.reactor.cachedb.examples.entity.GeneratedCacheModule.using(session);
List<UserEntity> activeUsers = domain.users().queries().activeUsers(25);
```

Why this is the default:

- compile-time generated
- no reflection scans
- no runtime metadata discovery
- low enough wrapper overhead to stay production-safe for most endpoints

### Recipe 2: Hot Endpoint With Explicit Entity Focus

Use this when:

- one screen or API becomes latency-sensitive
- you still want generated helpers
- you want less indirection than the package-level module

Recommended surface:

```java
var users = UserEntityCacheBinding.using(session);
List<UserEntity> activeUsers = users.queries().activeUsers(25);
```

Why:

- one less grouping layer
- clearer ownership of the entity contract
- still compile-time generated and low ceremony

### Recipe 3: Relation-Heavy Read Model

Use this when:

- you need order summaries, preview lines, or dashboard rows
- full entity hydration is too expensive
- the screen does not need the whole aggregate at first paint

Recommended pattern:

1. Query summaries through a projection repository
2. Load detail explicitly only when needed
3. Keep relation previews bounded with `withRelationLimit(...)`
4. For global top-N or threshold-driven screens, prefer a projection-specific ranked field over a wide multi-sort entity query
5. Mark that ranked field with `rankedBy(...)` so the projection repository can use the projection-specific top-window path
6. Keep full-entity page/result sizes below `hotEntityLimit`; if the window must be large, make it a projection window instead

Example:

```java
ProjectionRepository<OrderSummaryReadModel, Long> summaries =
        DemoOrderEntityCacheBinding.using(session).projections().orderSummary();

List<OrderSummaryReadModel> topOrders =
        DemoOrderEntityCacheBinding.topCustomerOrders(summaries, customerId, 24);

EntityRepository<DemoOrderEntity, Long> previewRepository =
        DemoOrderEntityCacheBinding.using(session).fetches().orderLinesPreview(8);
```

If the screen is something like "top orders by line count, then revenue" across the whole dataset, do not keep pushing the full entity query harder. Add a projection-specific rank field and query that projection through a single sorted index.

For customer timelines, a production-safe shape is:

- Redis keeps the customer root entity hot
- Redis keeps a bounded per-customer order-summary projection window, for example the latest 1,000 summaries
- PostgreSQL remains the durable home for full order history and archive reads
- detail screens fetch one explicit order or a small preview relation, not the whole customer aggregate

For entity hot sets, choose the admission rule explicitly:

- `COUNT_WINDOW` is the default and keeps the most recently accessed/written entity ids up to `hotEntityLimit`
- `TIME_WINDOW` is the right fit when the business rule is "orders from the last 90 days are hot"
- `STATE_WINDOW` is the right fit when only states such as `OPEN` and `PENDING` should stay hot
- `COMPOSITE` is the production fit when hotness is a combination, for example "last 90 days and `OPEN/PENDING`"
- `CUSTOM_PREDICATE` is reserved for explicit Java predicates, for example VIP customers or tenant-specific rules

Example:

```java
CachePolicy recentOrders = CachePolicy.builder()
        .hotEntityLimit(100_000)
        .pageSize(100)
        .hotPolicy(EntityHotPolicy.builder()
                .mode(EntityHotPolicyMode.TIME_WINDOW)
                .timeColumn("order_date")
                .hotForDays(90)
                .admitOnRead(false)
                .evictWhenRejected(true)
                .build())
        .build();
```

This is different from `entityTtlSeconds`. TTL is based on when the row is written to Redis. `TIME_WINDOW` is based on the business column in the entity payload.

Production hot sets are often multi-factor. Model that explicitly instead of
encoding a hidden if/else in application code:

```java
CachePolicy activeRecentOrders = CachePolicy.builder()
        .hotEntityLimit(100_000)
        .hotPolicy(EntityHotPolicy.allOf(List.of(
                EntityHotPolicy.timeWindow("order_date", Duration.ofDays(90).toSeconds()),
                EntityHotPolicy.stateWindow("status", List.of("OPEN", "PENDING"))
        )))
        .build();
```

If one of several routes can make a row hot, use `EntityHotPolicy.anyOf(...)`.
For example: "recent order" OR "VIP customer's order" OR "manual support hold".
Keep `CUSTOM_PREDICATE` narrow and deterministic; it should not call a database,
remote service, or allocate large helper objects.

### Route-Level Cache Contract

Entity policy tells CacheDB which row may enter Redis. It does not describe the
route. Production routes need an explicit contract:

```java
RouteCacheContract customerOrdersTimeline = RouteCacheContract.builder()
        .routeName("CustomerOrdersTimelineRoute")
        .entityName("OrderEntity")
        .projectionName("CustomerOrderSummaryHot")
        .pageSize(100)
        .hotWindow(1_000)
        .projectionRequired(true)
        .maxColdReadSize(100)
        .memoryBudgetBytes(256L * 1024L * 1024L)
        .strictMode(RouteCacheStrictMode.FAIL_FAST)
        .tenantQuota(new TenantCacheQuota("tenant_id", 50_000, 128L * 1024L * 1024L, true))
        .build();
```

Use a route contract when:

- the endpoint has a known first-page size
- Redis should keep a bounded hot window, not the whole table
- falling back from projection to entity scan would be unsafe
- one tenant or customer can otherwise dominate Redis memory

In production strict mode, a route that requires projection must fail fast if
the resolved path is `entity:...`. "It worked but used the expensive path" is a
staging-only behavior, not a safe production default.

Apply the contract around the route execution when you want runtime quota and
admission telemetry to be attributed to that route:

```java
List<OrderSummaryReadModel> page = RouteCacheContext.supplyWithContract(
        customerOrdersTimeline,
        () -> summaries.query(customerOrdersQuery(customerId, 100))
);
```

When `tenantQuota(...)` is bounded, CacheDB tracks tenant hot-set membership for
the current route context. If the tenant row limit is exceeded, the oldest
tenant member is evicted when `evictOnBreach=true`; otherwise the new row is not
admitted into Redis. The memory budget includes tenant tracking keys and the
measured Redis payload bytes for cached entities. CacheDB measures the entity key
with Redis `MEMORY USAGE` after admission and maintains a tenant payload counter
so repeated updates do not double-count the same entity.

Use the memory budget as a route guardrail, not as the only capacity plan. After
staging warm, still compare planner estimates with actual Redis usage because
Redis object overhead, index keys, projections, and page-cache keys also consume
memory.

Why:

- avoids wide object graphs on first read
- reduces Redis payload size and decode cost
- keeps the API natural for app teams

Measured support:

- use `ReadShapeBenchmarkMain` in `cachedb-production-tests` when you want a repo-local comparison of summary list, preview list, and full aggregate list materialization cost
- use `RankedProjectionBenchmarkMain` when you want a repo-local comparison of a ranked projection top-window path versus a wide candidate scan
- this benchmark is intentionally application-side, so it complements rather than replaces end-to-end Redis/PostgreSQL scenario runs

### PostgreSQL Outbox / CDC Adapter Example

If PostgreSQL can be changed outside CacheDB, do not rely on Redis staying fresh
by luck. Use a real feed: outbox, Debezium, Kafka, or another CDC source. The
starter now includes a concrete PostgreSQL outbox adapter for the simplest
production migration path.

Expected outbox shape:

```sql
CREATE TABLE cachedb_outbox (
    id BIGSERIAL PRIMARY KEY,
    entity_name TEXT NOT NULL,
    entity_id TEXT NOT NULL,
    event_type TEXT NOT NULL,
    payload_json TEXT,
    entity_version BIGINT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    event_source TEXT NOT NULL
);
```

Adapter usage:

```java
PostgresOutboxExternalChangeFeedAdapter adapter =
        PostgresOutboxExternalChangeFeedAdapter.builder(dataSource)
                .adapterName("orders-cachedb-feed")
                .outboxTable("cachedb_outbox")
                .checkpointTable("cachedb_outbox_adapter_checkpoint")
                .batchSize(500)
                .pollIntervalMillis(1_000)
                .build();

adapter.start(event -> {
    // Map ExternalChangeEvent to an explicit CacheDB upsert/delete or projection refresh.
    // Keep the sink idempotent because CDC feeds can replay after failures.
});
```

Production rules:

- keep `id` monotonically increasing
- keep the sink idempotent
- use a stable `adapterName` per logical consumer
- use a singleton/leader-elected runner if duplicate feed processing is not safe
- keep `payload_json` flat for the built-in parser, or carry the raw payload under `_payload`
- do not mutate domain tables from the adapter; it only reads the outbox and writes its checkpoint table

### Recipe 4: Proven Hotspot Or Batch Loop

Use this only when:

- profiling says the endpoint is still hot after query/projection fixes
- you need full control over the query and fetch plan
- the code is infra-facing or operational

Recommended surface:

```java
List<UserEntity> activeUsers = userRepository.query(
        QuerySpec.where(QueryFilter.eq("status", "ACTIVE"))
                .orderBy(QuerySort.asc("username"))
                .limitTo(25)
);
```

Why:

- smallest abstraction surface
- easiest place to control allocations, limits, and query shape

Tradeoff:

- more ceremony
- more repeated query/fetch glue in app code

## Production Guardrails

No matter which recipe you choose, these remain the production defaults we recommend:

- keep foreground repository Redis traffic separate from background worker/admin Redis traffic
- prefer projections for list pages and dashboards
- prefer summary query + explicit detail fetch over eager wide relations
- use `withRelationLimit(...)` on preview screens
- treat global sorted/range list screens as projection-first, and prefer pre-ranked projection fields when exact business ranking matters
- keep page/result size below the entity hot window; the default read-shape guardrail enforces this with `hotSetHeadroom`
- use `hotPolicy.mode=TIME_WINDOW` or `STATE_WINDOW` when hotness is driven by business time or state, not just by recent access count
- use `hotPolicy.mode=COMPOSITE` when business hotness combines time, state, tenant, or custom predicates
- define route-level cache contracts for critical screens: page size, hot window, projection requirement, cold-read cap, memory budget, and tenant quota
- run warm/backfill jobs with checkpoint, resume, batch-size, fetch-size, and row-rate limits when the hot set is large
- calibrate planner estimates after staging warm with Redis `MEMORY USAGE` and key-prefix breakdown
- use a CDC, Debezium, Kafka, or outbox adapter when PostgreSQL can be mutated outside CacheDB
- use `readShapeGuardrail.maxProjectionQueryLimit` for bounded projection windows such as "latest 1,000 orders per customer"
- set Redis `maxmemory` and keep `maxmemory-policy=noeviction` for a CacheDB-owned Redis; let CacheDB guardrails shed page-cache, read-through, hot-set, and query-index writes intentionally
- keep generated ergonomics for normal code, and reserve minimal repository style for measured hotspots
- treat admin UI as secondary; it should observe the system, not shape the primary runtime path

## What To Avoid

Avoid these patterns in production:

- full aggregate hydration for every list endpoint
- one-shot loading of hundreds of relation children into the first query
- increasing `hotEntityLimit` to hide a bad list shape instead of introducing a projection/read-model
- treating `entityTtlSeconds` as "last 90 business days"; use `TIME_WINDOW` for that rule
- relying on Redis random or all-keys eviction to control CacheDB memory
- sharing one Redis pool between foreground repository traffic and background workers
- dropping directly to minimal repository style everywhere before measuring
- assuming Redis latency is only about Redis itself; query shape and hydration cost usually dominate

## Spring Boot Recipe

For most production services, start here:

```yaml
cachedb:
  enabled: true
  profile: production
  redis:
    uri: redis://127.0.0.1:6379
    background:
      enabled: true
```

Then let generated registrars auto-register entities, and use generated module or binding surfaces in service code.

## Multi-Pod Kubernetes Recipe

When multiple application pods share one Redis and one PostgreSQL instance, keep these rules explicit:

- keep consumer groups shared across pods
- let CacheDB auto-append the resolved instance id to consumer names
- keep Redis leader leasing on for cleanup/report/history-style singleton loops
- count worker threads and flush parallelism at the cluster total, not only per pod
- treat Redis as a coordination-plane dependency and run it with durability and failover

Recommended baseline:

```yaml
cachedb:
  enabled: true
  profile: production
  redis:
    uri: redis://redis:6379
    background:
      enabled: true
  runtime:
    append-instance-id-to-consumer-names: true
    leader-lease-enabled: true
```

What now happens by default:

- write-behind, DLQ replay, projection refresh, and incident-delivery DLQ workers still scale out through shared consumer groups
- consumer names become pod-unique automatically through the resolved instance id
- cleanup/report/history loops stay singleton through Redis leases
- a pod crash does not by itself imply data loss, because pending stream work can be claimed by another pod

What does not change:

- Redis is still the main coordination dependency
- async projection refresh is still eventually consistent
- `at-least-once` delivery still means PostgreSQL version guards remain part of correctness

## Recommended Defaults

If you want a short production rule set to copy into an engineering playbook, use this:

- default application code: `GeneratedCacheModule.using(session)...`
- hot endpoint escape hatch: `*CacheBinding.using(session)...`
- worker and replay code: direct repositories
- list and dashboard reads: projections first, full aggregate second
- relation previews: always consider `withRelationLimit(...)`
- only move lower after profiling, not by habit

Related docs:

- [Spring Boot Starter](./spring-boot-starter.md)
- [Tuning Parameters](./tuning-parameters.md)
- [Use Case Examples](./use-case-examples.md)
- [Production Tests](../cachedb-production-tests/README.md)
- [CI production evidence workflow](../.github/workflows/production-evidence.yml)
- [CI local runner](../tools/ci/run-production-evidence.ps1)
