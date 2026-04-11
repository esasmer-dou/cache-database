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

Why:

- avoids wide object graphs on first read
- reduces Redis payload size and decode cost
- keeps the API natural for app teams

Measured support:

- use `ReadShapeBenchmarkMain` in `cachedb-production-tests` when you want a repo-local comparison of summary list, preview list, and full aggregate list materialization cost
- use `RankedProjectionBenchmarkMain` when you want a repo-local comparison of a ranked projection top-window path versus a wide candidate scan
- this benchmark is intentionally application-side, so it complements rather than replaces end-to-end Redis/PostgreSQL scenario runs

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
- keep generated ergonomics for normal code, and reserve minimal repository style for measured hotspots
- treat admin UI as secondary; it should observe the system, not shape the primary runtime path

## What To Avoid

Avoid these patterns in production:

- full aggregate hydration for every list endpoint
- one-shot loading of hundreds of relation children into the first query
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
- [Production Tests](../cachedb-production-tests/README.md)
- [CI production evidence workflow](../.github/workflows/production-evidence.yml)
- [CI local runner](../tools/ci/run-production-evidence.ps1)
