# CacheDB MSSQL REST API Sample

English | [Türkçe](README.tr.md)

This is a standalone Spring Boot REST API that shows how to use CacheDB with Redis and Microsoft SQL Server. It uses the same domain and API flow as the PostgreSQL sample, but selects the MSSQL storage provider explicitly.

The sample models a commerce support system:

- Customers place many orders.
- Orders have many order lines.
- Products are read frequently by category.
- Support tickets feed a small operational dashboard.
- Customer order timelines are served from a projection/read-model instead of hydrating full aggregates.

## Dependency Model

This project intentionally consumes CacheDB as an external Maven package:

```xml
<repository>
  <id>cache-database-github-packages</id>
  <url>https://maven.pkg.github.com/esasmer-dou/cache-database</url>
</repository>

<dependency>
  <groupId>com.reactor.cachedb</groupId>
  <artifactId>cachedb-spring-boot-starter</artifactId>
  <version>0.1.0</version>
</dependency>

<dependency>
  <groupId>com.reactor.cachedb</groupId>
  <artifactId>cachedb-storage-mssql</artifactId>
  <version>0.1.0</version>
</dependency>
```

Users should not build the parent repository first. CacheDB `0.1.0` is published from the main repository to GitHub Packages.

GitHub Packages Maven access requires credentials. The `<id>` in `pom.xml` must match the `<server><id>` in Maven `settings.xml`:

```xml
<settings>
  <servers>
    <server>
      <id>cache-database-github-packages</id>
      <username>${env.GITHUB_ACTOR}</username>
      <password>${env.GITHUB_TOKEN}</password>
    </server>
  </servers>
</settings>
```

Use a token with `read:packages` access, then run:

```bash
export GITHUB_ACTOR=your-github-user
export GITHUB_TOKEN=your-read-packages-token
mvn clean package
```

If you do not configure credentials, Maven will usually fail with `401 Unauthorized` even though the repository URL is correct.

## Run Locally

1. Start Redis and SQL Server:

```bash
docker compose up -d
```

2. Start the API:

```bash
mvn spring-boot:run
```

3. Check readiness:

```bash
curl http://127.0.0.1:8092/api/health/ready
```

4. Seed realistic demo data:

```bash
curl -X POST "http://127.0.0.1:8092/api/demo/seed?customers=20&ordersPerCustomer=40&linesPerOrder=4"
```

5. Open the CacheDB admin UI:

```text
http://127.0.0.1:8092/cachedb-admin
```

## Main API Flow

| Step | Endpoint | What it demonstrates |
|---|---|---|
| Health | `GET /api/health/ready` | Redis connectivity and write-behind health summary |
| Seed | `POST /api/demo/seed` | CacheDB write path, SQL Server persistence, projection refresh |
| Customer detail | `GET /api/customers/1?orderPreview=5` | Entity detail with bounded relation preview |
| Timeline | `GET /api/customers/1/orders?limit=20` | Projection/read-model list route |
| Order detail | `GET /api/orders/10001?linePreview=5` | Explicit detail load with bounded child relation |
| High value list | `GET /api/orders/high-value?minimumAmount=500&limit=25` | Global sorted projection query |
| Dashboard | `GET /api/dashboard/commerce?limit=25` | Small dashboard from projections and ticket entity query |
| Tuning | `GET /api/tuning` | Active CacheDB policy and guardrail summary |

## Core Terms Used in This README

Read this section first if you are new to CacheDB. The sample uses a few CacheDB-specific terms that are easier to understand before walking through the code.

| Term | What it means in this sample | Where to look |
|---|---|---|
| CacheDB entity | A Java class annotated with `@CacheEntity`. It maps one SQL Server table to one Redis namespace and one generated repository surface. | `domain/CustomerEntity.java`, `domain/OrderEntity.java` |
| Generated binding | A build-time generated class such as `OrderEntityCacheBinding`. It exposes type-safe repository creation, named queries, fetch presets, and projection repositories. This is the practical ORM API used by the app. | `SampleRepositories.java`, controller calls like `OrderEntityCacheBinding.customerTimeline(...)` |
| Entity repository | The CRUD and bounded-query API for full entity objects. Use it for create, update, delete, and detail reads. | `EntityRepository<OrderEntity, Long>` |
| Projection | A compact read model derived from an entity. It is designed for list, dashboard, and sorted screens where loading full entities would be too expensive. | `OrderReadModels.OrderSummary` |
| Read model | The user-facing shape of a read screen. In this sample, `OrderSummary` is the read model for timelines and high-value order lists. | `readmodel/OrderReadModels.java` |
| Projection repository | The repository used to query projection rows instead of full entity rows. | `ProjectionRepository<OrderSummary, Long>` |
| Named query | A predefined `QuerySpec` declared on the entity and exposed through the generated binding. It avoids ad-hoc, unbounded query shapes in controllers. | `customerTimelineQuery`, `recentHighValueOrdersQuery` |
| Fetch preset | A named fetch plan for a detail route. It decides which relation may be loaded and how many child rows are allowed. | `ordersPreviewFetchPlan`, `linePreviewFetchPlan` |
| Relation loader | Explicit code that fills child collections only when a fetch preset requests them. It prevents accidental `N+1` style loading. | `CustomerOrdersRelationBatchLoader`, `OrderLinesRelationBatchLoader` |
| Active data set | The subset of records allowed to stay in Redis according to policy, such as recent orders or operationally active rows. | `SampleCacheDbTuningConfig` |
| Write-behind | The write model where CacheDB accepts the write through Redis first and flushes the durable row to SQL Server asynchronously. | Seed flow, create endpoints, admin UI write-behind panel |
| Guardrail | A safety limit that prevents expensive production mistakes, such as unbounded entity scans or Redis memory pressure. | `ReadShapeGuardrailConfig`, `RedisGuardrailConfig` |

When the README says "learn the generated binding model", it means: inspect how `@CacheEntity`, named queries, fetch presets, and projections become generated methods such as `repository(...)`, `customerTimeline(...)`, `ordersPreviewRepository(...)`, and `orderSummary(...)`. Those generated methods are the application-facing ORM surface.

## Layer-by-Layer Walkthrough

This sample is intentionally small, but it follows the same shape a production service should use.

| Layer | Main files | Responsibility | Production rule |
|---|---|---|---|
| API | `web/*Controller.java` | Validate request shape, clamp limits, expose bounded endpoints | Never expose unbounded list endpoints |
| Service | `SampleSeedService.java`, controller methods | Apply business flow, ordering, retry-aware behavior | Keep write and relation ordering explicit |
| CacheDB repository | `SampleRepositories.java` | Creates generated `EntityRepository` and `ProjectionRepository` beans | Treat generated bindings as the ORM surface |
| Entity mapping | `domain/*Entity.java` | Maps Java fields to SQL Server columns and Redis namespaces | Keep table, id, column, relation definitions explicit |
| Relation loading | `relation/*BatchLoader.java` | Loads child collections only when a fetch preset asks for them | Use bounded previews instead of full aggregate hydration |
| Read model | `readmodel/OrderReadModels.java` | Stores timeline/dashboard rows as compact projection payloads | Use projections for growing lists and global sorted screens |
| Durable storage | `schema.sql` | Owns primary keys, foreign keys, and route indexes | SQL Server remains the source of truth for full history |
| Runtime tuning | `SampleCacheDbTuningConfig.java`, `application.yml` | Sets hot-data policy, guardrails, MSSQL lock timeout, and write-behind batches | Tune by route, SQL Server behavior, and memory budget |

### API Layer: Bound the Request Before It Reaches the ORM

`CustomerController` does not pass arbitrary user limits into CacheDB. It clamps the request first:

```java
@GetMapping("/{customerId}/orders")
public List<OrderReadModels.OrderSummary> orderTimeline(
        @PathVariable long customerId,
        @RequestParam(defaultValue = "20") int limit
) {
    return OrderEntityCacheBinding.customerTimeline(
            orderSummaryRepository,
            customerId,
            clamp(limit, 1, 1_000)
    );
}
```

The important point is not the number `1_000`; the important point is that the route has a contract. A list endpoint must have a maximum result size before it touches Redis or SQL Server.

### Repository Layer: Generated Bindings Are the ORM Surface

`SampleRepositories` is where the application turns CacheDB generated bindings into Spring beans:

```java
@Bean
EntityRepository<OrderEntity, Long> orderRepository(CacheDatabase cacheDatabase) {
    return OrderEntityCacheBinding.repository(cacheDatabase);
}

@Bean
ProjectionRepository<OrderReadModels.OrderSummary, Long> orderSummaryRepository(
        EntityRepository<OrderEntity, Long> orderRepository
) {
    return OrderEntityCacheBinding.orderSummary(orderRepository);
}
```

Controllers use these repositories like an ORM, but the generated methods are stricter than a typical dynamic ORM query layer. Named queries, fetch presets, projection definitions, and relation loaders are declared in code and generated at build time.

### Entity Layer: SQL Mapping and Cache Mapping Are Explicit

`OrderEntity` maps the table, Redis namespace, primary key, columns, relation, named queries, and projection:

```java
@CacheEntity(
        table = "sample_orders",
        redisNamespace = "sample-orders",
        relationLoader = OrderLinesRelationBatchLoader.class
)
public class OrderEntity {
    @CacheId(column = "order_id")
    public Long orderId;

    @CacheColumn("customer_id")
    public Long customerId;

    @CacheRelation(
            targetEntity = "OrderLineEntity",
            mappedBy = "orderId",
            kind = CacheRelation.RelationKind.ONE_TO_MANY,
            batchLoadOnly = true
    )
    public List<OrderLineEntity> lines;
}
```

This is not hidden magic. The table and column mapping tells CacheDB how to persist and hydrate the entity. The relation metadata tells CacheDB how the Java object graph should be connected when a fetch plan asks for it.

### Relation Model: Foreign Key and `@CacheRelation` Solve Different Problems

The database foreign key protects data integrity:

```sql
CONSTRAINT fk_sample_orders_customer
FOREIGN KEY (customer_id) REFERENCES sample_customers(customer_id)
```

`@CacheRelation` protects application read shape:

```java
@CacheRelation(
        targetEntity = "OrderEntity",
        mappedBy = "customerId",
        kind = CacheRelation.RelationKind.ONE_TO_MANY,
        batchLoadOnly = true
)
public List<OrderEntity> orders;
```

How to reason about it:

| Case | What happens |
|---|---|
| FK exists and `@CacheRelation` exists | SQL Server enforces integrity, CacheDB can load the object graph when requested |
| FK exists but `@CacheRelation` is missing | SQL Server is still correct, but CacheDB will not auto-fill the Java child collection |
| `@CacheRelation` exists but FK is missing | CacheDB can query by matching columns, but SQL Server will not stop orphan rows |
| Neither exists | Use explicit child queries only; do not expect ORM-style relation loading |

Production recommendation: keep both. Use foreign keys for correctness, and use `@CacheRelation` plus fetch presets for controlled object graph loading.

### Fetch Presets: Detail Screens Get a Preview, Not the Whole Aggregate

`CustomerEntity` exposes a small order preview:

```java
@CacheFetchPreset("ordersPreview")
public static FetchPlan ordersPreviewFetchPlan(int orderLimit) {
    return FetchPlan.of("orders").withRelationLimit("orders", Math.max(1, orderLimit));
}
```

`CustomerController.detail` uses that preset:

```java
return CustomerEntityCacheBinding
        .ordersPreviewRepository(customerRepository, clamp(orderPreview, 1, 25))
        .findById(customerId)
        .orElseThrow(...);
```

That means a detail screen may show "latest 5 orders", but it does not pull every historical order and every line item into the response.

### Projection Layer: Growing Lists Use `OrderSummary`

The order timeline and high-value order list use `OrderSummary`, not full `OrderEntity`:

```java
public record OrderSummary(
        Long orderId,
        Long customerId,
        Long orderDate,
        Double orderAmount,
        String currencyCode,
        String orderType,
        String status,
        Integer lineCount,
        Double priorityScore
) {
}
```

The projection is ranked by fields used by real screens:

```java
).rankedBy("order_date", "priority_score").asyncRefresh();
```

This keeps list rows compact in Redis. The full entity is still available for detail screens, but list screens do not pay the cost of full aggregate hydration.

## Real-World Use Cases in This Sample

| Use case | Endpoint | CacheDB shape | Why this shape |
|---|---|---|---|
| Customer opens order history | `GET /api/customers/{id}/orders?limit=20` | Projection query on `OrderSummary` | Growing timeline, compact payload, stable sort |
| User opens one order | `GET /api/orders/{id}?linePreview=5` | Entity detail plus bounded relation preview | Detail needs more data, but still not all lines unless requested |
| Operations checks high-value orders | `GET /api/orders/high-value?minimumAmount=500&limit=25` | Ranked projection query | Global sorted screen should not scan full entities |
| Product category list | `GET /api/products/active?category=electronics&limit=20` | Named entity query | Small bounded catalog route is acceptable as entity query |
| Support queue | `GET /api/tickets/open?limit=25` | Named entity query with status index | Operational queue is bounded and status-filtered |
| Commerce dashboard | `GET /api/dashboard/commerce?limit=25` | Projection plus ticket entity query | Dashboard combines small pre-shaped read models |
| Create customer | `POST /api/customers` | Entity save | Redis-first write, SQL Server write-behind |
| Create order | `POST /api/orders` | Entity save with FK readiness check | Child write waits until parent is durable in SQL Server |
| Update order status | `PATCH /api/orders/{id}/status` | Read entity, mutate full object, save | Partial update is implemented as explicit full-entity save |
| Delete order | `DELETE /api/orders/{id}` | Repository delete | Removes active cache record and schedules durable delete |

## Day 1 to Production Load

| Stage | Data shape | What to do |
|---|---|---|
| Day 1 local demo | 20 customers, 40 orders each, 4 lines each | Run seed, inspect API responses, open admin UI, understand the generated bindings |
| First staging run | Thousands of customers, real-like order distribution | Keep API limits below projection window, verify SQL Server indexes, check projection lag |
| Growing traffic | Many customers repeatedly opening timelines | Increase Redis `maxmemory`, size `hotEntityLimit`, keep timelines on projection, watch hit/miss and write-behind backlog |
| Large customer fan-out | Some customers have thousands of orders | Never hydrate `Customer -> all Orders`; use `OrderSummary` timeline plus explicit order detail |
| Dashboard growth | More global sorted and KPI screens | Add route-specific projections instead of reusing full entities |
| SQL Server contention | Lock waits or pool pressure appears | Tune Hikari pool, lock timeout, query timeout, and batch size together |
| Multi-pod runtime | Several application containers | Keep unique consumer names enabled and use leader lease for singleton maintenance loops |

## Tuning Playbook

Start with the sample values, then tune by evidence:

| Signal | Where to look | Action |
|---|---|---|
| Redis memory grows too fast | Admin UI, Redis `INFO memory`, `/api/tuning` | Lower hot window, add stricter hot policy, reduce projection payload |
| Timeline query is slow | API latency and projection route label | Confirm the route uses `projection:order-summary`, not entity fallback |
| SQL Server gets too much read traffic | SQL Server metrics, slow query evidence | Move repeated list screens to projection, add route indexes |
| Write-behind backlog grows | Admin UI write-behind section | Increase worker/batch carefully, inspect SQL Server locks, add backpressure |
| Projection lag grows | Admin UI projection telemetry | Reduce refresh batch pressure or split projections by route |
| Large responses hit clients | API payload size | Lower controller clamp and use detail follow-up endpoints |
| Lock waits increase | SQL Server wait stats and app timeouts | Lower batch size, tune isolation, keep transactions short |

The default tuning code is here:

```java
CachePolicy.builder()
        .hotEntityLimit(5_000)
        .pageSize(100)
        .entityTtlSeconds(0)
        .pageTtlSeconds(120)
        .compositeHotPolicy(EntityHotPolicyCompositeOperator.ANY, List.of(
                EntityHotPolicy.timeWindow("order_date", 90L * 24L * 60L * 60L),
                EntityHotPolicy.stateWindow("status", List.of("ACTIVE", "NEW", "PAID", "PICKING", "OPEN", "PENDING")),
                EntityHotPolicy.stateWindow("active_status", List.of("ACTIVE"))
        ))
        .build()
```

This policy means: keep records in the active data set when they are recent enough or operationally active. It is closer to real systems than "cache whatever was read last".

## Cache Policy Parameter Tuning

Tune cache policy in this order. Do not start by increasing memory.

1. Define the route contract: endpoint limit, sort order, detail/preview split, and whether projection is required.
2. Define the Redis budget: `maxmemory`, `maxmemory-policy=noeviction`, warning threshold, critical threshold.
3. Define admission: which records are allowed into the active data set.
4. Define retention: count window, time window, state window, and TTL behavior.
5. Define write pressure: write-behind worker count, batch size, retry, and backlog thresholds.

### CachePolicy Parameters

| Parameter | What it controls | Increase when | Decrease when | Production guidance |
|---|---|---|---|---|
| `hotEntityLimit` | Maximum active entity window for the default cache policy. It is the first coarse bound against Redis growth. | Redis has headroom and hot-read misses are high for bounded routes. | Redis memory grows too fast or one route dominates memory. | Size it from memory budget: average entity payload plus index overhead times expected hot rows. Do not use it as a substitute for projection windows. |
| `pageSize` | Default page size used by cache/query surfaces when the caller does not provide a tighter route limit. | Normal screens need slightly larger first pages and payload size is small. | API responses are large or UI only renders a small first page. | Keep it close to real UI page size, usually `50-100`. Large exports should not use entity page cache. |
| `lruEvictionEnabled` | Allows older active records to be pushed out when the count window is exceeded. | You run a broad working set and can tolerate normal cache churn. | You need strict hot policy behavior and want rejection instead of silent churn. | Keep enabled for most online workloads, but still enforce Redis `maxmemory` and route limits. |
| `entityTtlSeconds` | Optional TTL for full entity records. `0` means no TTL-based entity expiration. | Data is temporary, naturally refreshable, or safe to cold-load again. | Business detail reads must stay stable and hot-set eviction should be policy-driven. | For durable business entities, prefer `0` plus explicit hot policy. Use TTL for ephemeral views, sessions, or short-lived operational records. |
| `pageTtlSeconds` | TTL for cached page/query results. | List results are reused and can tolerate short staleness. | Lists change frequently or stale ordering is visible to users. | Keep short, usually `30-120s`. Projection rows can live longer than cached page results. |
| `hotPolicy` | Admission rule deciding whether a row can enter or stay in Redis. | You need business-aware caching instead of plain LRU. | The rule admits too many records or misses important reads. | Prefer composite policy: recent time window OR active state OR custom business predicate. |

### Hot Policy Modes

| Mode | Use when | Example | Risk |
|---|---|---|---|
| `COUNT_WINDOW` | You only need a simple bounded hot set. | Keep latest `N` product/category records. | It does not know business importance; noisy routes can crowd out useful rows. |
| `TIME_WINDOW` | Recency is the real business rule. | Keep orders from the last 90 days using `order_date`. | Old but operationally active rows may be rejected unless combined with state policy. |
| `STATE_WINDOW` | A status makes a record hot. | Keep `OPEN`, `PENDING`, `PAID`, `ACTIVE` records. | A large status bucket can over-admit data if not combined with count or time bounds. |
| `CUSTOM_PREDICATE` | Hotness depends on domain logic. | Admit VIP customer orders or tenant-specific premium routes. | Harder to reason about; document and test it with real distributions. |
| `COMPOSITE` | Real systems need more than one rule. | Last 90 days OR active status OR active product. | `ANY` can admit too much; `ALL` can admit too little. Validate with staging memory numbers. |

The sample uses `ANY` because an order can be important either because it is recent or because it is operationally active. For a strict memory profile, switch to `ALL` only if a record must satisfy every child rule.

### Admission Source Flags

`EntityHotPolicy` also supports source-level admission:

| Flag | Meaning | When to change |
|---|---|---|
| `admitOnWrite` | Newly written records may enter Redis. | Turn off only for write-heavy cold data that should not pollute Redis. |
| `admitOnRead` | Cold reads may promote records into Redis. | Turn off for archive routes where one-off reads should not become hot. |
| `admitOnWarm` | Warm/backfill jobs may populate Redis. | Turn off when warm jobs are only validating SQL and should not mutate Redis. |
| `evictWhenRejected` | If a record no longer satisfies policy, CacheDB may evict it from the active set. | Turn off only when you need a softer transition during policy rollout. |

### Read Guardrail Parameters

Cache policy controls what may live in Redis. Read guardrails control what callers are allowed to ask for.

| Parameter | Production use |
|---|---|
| `maxEntityQueryLimit` | Keep low. Entity queries hydrate full objects and can trigger relation work. Use `100-250` for normal screens. |
| `maxProjectionQueryLimit` | Can be higher because projection payloads are compact. Use it for timeline windows and dashboards. |
| `hotSetHeadroom` | Leaves room between requested window and hot-set boundary. Increase when queries often sit near the edge of the hot window. |
| `rejectEntityQueryOverLimit` | Keep enabled. If a route needs more rows, create a projection route instead of raising this globally. |
| `rejectProjectionQueryOverLimit` | Keep enabled. Raise only for a known projection route with measured payload size. |

### Redis Guardrail Parameters

Redis must be treated as a bounded runtime dependency, not as an unlimited heap.

| Parameter | Production use |
|---|---|
| `usedMemoryWarnMaxmemoryPercent` | First signal to slow down cache growth. `70-80` is a reasonable start. |
| `usedMemoryCriticalMaxmemoryPercent` | Critical pressure. `85-90` is a reasonable start; above that you are close to write/read shedding. |
| `expectedMaxmemoryPolicy` | Use `noeviction` for this model. CacheDB should decide what to keep; Redis should not randomly evict required keys. |
| `producerBackpressureEnabled` | Keep enabled so write producers slow down when Redis is under pressure. |
| `writeBehindBacklogWarnThreshold` | Alert before SQL Server flush lag becomes user-visible. Lower it for strict consistency windows. |
| `writeBehindBacklogCriticalThreshold` | Critical backlog level. At this point inspect SQL Server locks, pool saturation, and batch size. |

### Write-Behind Parameters

Write-behind tuning is separate from cache admission. Do not fix slow SQL Server by admitting less data unless Redis memory is also the problem.

| Parameter | Increase when | Decrease when |
|---|---|---|
| `workerThreads` | Backlog grows and SQL Server has spare connections/CPU. | SQL Server pool is saturated or lock waits increase. |
| `batchSize` / `maxFlushBatchSize` | Backlog grows and SQL Server handles batch writes well. | Latency spikes, locks grow, or transactions become too large. |
| `tableAwareBatchingEnabled` | Usually keep enabled; groups writes by table for better flush behavior. | Rarely disabled; only for debugging or provider-specific issues. |
| `coalescingEnabled` | Usually keep enabled; repeated updates to the same entity can collapse. | Disable only when every intermediate state must be durably visible. |
| `maxFlushRetries` / `retryBackoffMillis` | Transient SQL Server failures occur during deploy/failover. | Retries hide permanent failures and DLQ grows. |

### MSSQL-Specific Notes

For SQL Server, cache policy and write-behind tuning must be validated with lock behavior:

| Setting | Guidance |
|---|---|
| Hikari `maximum-pool-size` | Do not simply raise it. Match it with SQL Server CPU, worker threads, and lock waits. |
| `lock-timeout-millis` | Keep finite so blocked flushes fail and retry instead of hanging worker threads. |
| `query-timeout-seconds` | Keep finite for list and warm routes. Long queries should become indexed or projected. |
| `transaction-isolation` | Start conservative, then validate with your own contention profile. Higher isolation can increase blocking. |
| Batch size | SQL Server may react differently than PostgreSQL. Increase only after checking wait stats and flush latency. |

### Practical Profiles

| Profile | When to use | Example direction |
|---|---|---|
| Small admin app | Low traffic, bounded lists | `hotEntityLimit=1_000`, `pageSize=50`, short page TTL, low query limits |
| Commerce timeline | Customer has growing order history | Projection required, `maxProjectionQueryLimit=1_000`, entity detail limit `100-250`, 90-day or active-state hot policy |
| Dashboard/KPI | Repeated global sorted reads | Dedicated projection, short page TTL, strict entity query limit, ranked projection fields |
| Archive lookup | Old records are rarely read | Do not admit on read, keep entity TTL `0` or very short, serve from SQL Server cold path |
| High write traffic | Writes are bursty | Tune write-behind workers and batch size, keep Redis memory guardrails strict, watch backlog |

## MSSQL Provider Settings

The important provider switch is in `application.yml`:

```yaml
cachedb:
  sql:
    provider: mssql
```

The sample also sets MSSQL-specific lock and timeout behavior:

```yaml
cachedb:
  sql:
    mssql:
      lock-timeout-millis: 5000
      query-timeout-seconds: 10
      transaction-isolation: serializable
```

For a real environment, use a dedicated database instead of `tempdb`, tune Hikari pool size, and validate lock behavior with your SQL Server topology.

The sample active-data policy admits records when they are inside the 90-day order window or carry an active operational state such as `ACTIVE`, `NEW`, `PAID`, `OPEN`, or `PENDING`.

## Why Projection Is Used

The customer order timeline can grow without bound. Loading `Customer -> all Orders -> all Lines` on every list screen is not production-safe. This sample keeps the list screen on `OrderSummary`, then loads full order details only after the user selects one order.

BEST:

- Use `OrderSummary` projection for customer timelines and high-value lists.
- Keep API `limit` inside the configured projection window.
- Load `OrderEntity` with `linePreview` only for a detail screen.
- Keep SQL Server as the durable source for full history.

ANTI-PATTERN:

- Returning a customer with thousands of full orders and lines in one response.
- Exposing unbounded `findAll` list endpoints.
- Assuming PostgreSQL and SQL Server have identical lock, isolation, and batch behavior.

## Postman

Import:

```text
postman/cache-database-mssql-sample.postman_collection.json
```

The collection contains the normal flow: readiness, seed, customer timeline, detail, dashboard, update, delete, and tuning.

## SQL Server Notes

The schema is created by `src/main/resources/schema.sql`. It includes primary keys, foreign keys, and indexes for the hot routes:

- `sample_orders(customer_id, order_date DESC, order_id DESC)`
- `sample_orders(priority_score DESC, order_date DESC)`
- `sample_order_lines(order_id, line_number)`
- `sample_support_tickets(status, priority, updated_at DESC)`

The seed endpoint writes through CacheDB and waits for parent rows before writing dependent child rows. That is intentional because the database has foreign keys.

`POST /api/orders` also waits briefly until the customer row is durable in SQL Server. If the parent customer was just created and write-behind has not flushed it yet, the endpoint returns `409` and the client should retry.

## Troubleshooting

SQL Server can take longer than Redis to become ready. If the app fails on first start, wait for the SQL Server healthcheck and run `mvn spring-boot:run` again.

If dependency resolution fails, verify access to the package repository configured in `pom.xml`.

If timeline results are empty immediately after seed, wait a few seconds and retry. Projection refresh is asynchronous.
