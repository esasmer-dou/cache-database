# CacheDB PostgreSQL REST API Sample

English | [Türkçe](README.tr.md)

This is a standalone Spring Boot REST API that shows how a normal application can use CacheDB with Redis and PostgreSQL without building the main CacheDB repository locally.

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

1. Start Redis and PostgreSQL:

```bash
docker compose up -d
```

2. Start the API:

```bash
mvn spring-boot:run
```

3. Check readiness:

```bash
curl http://127.0.0.1:8091/api/health/ready
```

4. Seed realistic demo data:

```bash
curl -X POST "http://127.0.0.1:8091/api/demo/seed?customers=20&ordersPerCustomer=40&linesPerOrder=4"
```

5. Open the CacheDB admin UI:

```text
http://127.0.0.1:8091/cachedb-admin
```

## Main API Flow

| Step | Endpoint | Main data path | What it demonstrates |
|---|---|---|---|
| Health | `GET /api/health/ready` | Runtime checks | Redis connectivity and write-behind health summary |
| Seed | `POST /api/demo/seed` | Redis write, PostgreSQL write-behind | CacheDB write path, SQL persistence, projection refresh |
| Customer detail | `GET /api/customers/1?orderPreview=5` | Redis entity + bounded relation preview | Entity detail with bounded relation preview |
| Timeline | `GET /api/customers/1/orders?limit=20` | Redis projection: `OrderSummary` | Customer order list without full aggregate hydration |
| Order detail | `GET /api/orders/10001?linePreview=5` | Redis entity + bounded order lines | Explicit detail load with bounded child relation |
| High value list | `GET /api/orders/high-value?minimumAmount=500&limit=25` | Redis ranked projection: `OrderSummary` | Global sorted projection query |
| Archive orders | `GET /api/orders/archive?customerId=1&limit=20` | Direct PostgreSQL query | Archive read using the same `OrderSummary` response shape |
| Dashboard | `GET /api/dashboard/commerce?limit=25` | Redis projection + Redis entity query | Small dashboard from projections and ticket entity query |
| Tuning | `GET /api/tuning` | Runtime config | Active CacheDB policy and guardrail summary |

The sample is intentionally not "Redis for everything". The production rule is:

| Need | BEST path | Why |
|---|---|---|
| Create or update an operational entity | CacheDB entity repository | The write is accepted through Redis and flushed to PostgreSQL by write-behind |
| Render the first page of a growing list | Projection repository with `OrderSummary` | The UI reads a compact, ranked row instead of loading full order entities |
| Render a selected detail screen | Entity repository with a bounded fetch preset | Only the selected aggregate and limited child preview are loaded |
| Read old history or export data | Explicit SQL route | Archive and reporting reads should not pollute Redis or bypass SQL query planning |

## Core Terms Used in This README

Read this section first if you are new to CacheDB. The sample uses a few CacheDB-specific terms that are easier to understand before walking through the code.

| Term | What it means in this sample | Where to look |
|---|---|---|
| CacheDB entity | A Java class annotated with `@CacheEntity`. It maps one SQL table to one Redis namespace and one generated repository surface. | `domain/CustomerEntity.java`, `domain/OrderEntity.java` |
| Generated binding | A build-time generated class such as `OrderEntityCacheBinding`. It exposes type-safe repository creation, named queries, fetch presets, and projection repositories. This is the practical ORM API used by the app. | `SampleRepositories.java`, controller calls like `OrderEntityCacheBinding.customerTimeline(...)` |
| Entity repository | The CRUD and bounded-query API for full entity objects. Use it for create, update, delete, and detail reads. | `EntityRepository<OrderEntity, Long>` |
| Projection | A compact read model derived from an entity. It is designed for list, dashboard, and sorted screens where loading full entities would be too expensive. | `OrderReadModels.OrderSummary` |
| Read model | The user-facing shape of a read screen. In this sample, `OrderSummary` is the read model for timelines and high-value order lists. | `readmodel/OrderReadModels.java` |
| Projection repository | The repository used to query projection rows instead of full entity rows. | `ProjectionRepository<OrderSummary, Long>` |
| Named query | A predefined `QuerySpec` declared on the entity and exposed through the generated binding. It avoids ad-hoc, unbounded query shapes in controllers. | `customerTimelineQuery`, `recentHighValueOrdersQuery` |
| Fetch preset | A named fetch plan for a detail route. It decides which relation may be loaded and how many child rows are allowed. | `ordersPreviewFetchPlan`, `linePreviewFetchPlan` |
| Relation loader | Explicit code that fills child collections only when a fetch preset requests them. It prevents accidental `N+1` style loading. | `CustomerOrdersRelationBatchLoader`, `OrderLinesRelationBatchLoader` |
| Active data set | The subset of records allowed to stay in Redis according to policy, such as recent orders or operationally active rows. | `SampleCacheDbTuningConfig` |
| Write-behind | The write model where CacheDB accepts the write through Redis first and flushes the durable row to SQL asynchronously. | Seed flow, create endpoints, admin UI write-behind panel |
| Guardrail | A safety limit that prevents expensive production mistakes, such as unbounded entity scans or Redis memory pressure. | `ReadShapeGuardrailConfig`, `RedisGuardrailConfig` |

When the README says "learn the generated binding model", it means: inspect how `@CacheEntity`, named queries, fetch presets, and projections become generated methods such as `repository(...)`, `customerTimeline(...)`, `ordersPreviewRepository(...)`, and `orderSummary(...)`. Those generated methods are the application-facing ORM surface.

## Layer-by-Layer Walkthrough

This sample is intentionally small, but it follows the same shape a production service should use.

| Layer | Main files | Responsibility | Production rule |
|---|---|---|---|
| API | `web/*Controller.java` | Validate request shape, clamp limits, expose bounded endpoints | Never expose unbounded list endpoints |
| Service | `SampleSeedService.java`, controller methods | Apply business flow, ordering, retry-aware behavior | Keep write and relation ordering explicit |
| CacheDB repository | `SampleRepositories.java` | Creates generated `EntityRepository` and `ProjectionRepository` beans | Treat generated bindings as the ORM surface |
| Entity mapping | `domain/*Entity.java` | Maps Java fields to SQL columns and Redis namespaces | Keep table, id, column, relation definitions explicit |
| Relation loading | `relation/*BatchLoader.java` | Loads child collections only when a fetch preset asks for them | Use bounded previews instead of full aggregate hydration |
| Read model | `readmodel/OrderReadModels.java` | Stores timeline/dashboard rows as compact projection payloads | Use projections for growing lists and global sorted screens |
| Durable storage | `schema.sql` | Owns primary keys, foreign keys, and route indexes | SQL remains the source of truth for full history |
| Runtime tuning | `SampleCacheDbTuningConfig.java` | Sets hot-data policy, guardrails, limits, write-behind batches | Tune by route and memory budget, not by guesswork |

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

The important point is not the number `1_000`; the important point is that the route has a contract. A list endpoint must have a maximum result size before it touches Redis or PostgreSQL.

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
customer_id BIGINT NOT NULL REFERENCES sample_customers(customer_id)
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
| FK exists and `@CacheRelation` exists | SQL enforces integrity, CacheDB can load the object graph when requested |
| FK exists but `@CacheRelation` is missing | SQL is still correct, but CacheDB will not auto-fill the Java child collection |
| `@CacheRelation` exists but FK is missing | CacheDB can query by matching columns, but SQL will not stop orphan rows |
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

## OrderSummary End-to-End Example

`OrderSummary` is the concrete example behind the recommendation "use `OrderSummary` for customer order lists and high-value order lists." It is not a placeholder.

### 1. The read model shape

`OrderSummary` contains only the columns a list screen needs:

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

It intentionally does not contain order lines, product details, customer details, or audit history. Those belong to detail screens or cold archive flows.

### 2. The projection maps full entity to summary

`OrderReadModels.ORDER_SUMMARY_PROJECTION` tells CacheDB how to build and store the compact row:

```java
(OrderEntity order) -> new OrderSummary(
        order.orderId,
        order.customerId,
        order.orderDate,
        order.orderAmount,
        order.currencyCode,
        order.orderType,
        order.status,
        order.lineCount,
        order.priorityScore
)
```

The projection is ranked by the columns used by the hot screens:

```java
).rankedBy("order_date", "priority_score").asyncRefresh();
```

That is why customer timelines can sort by `order_date`, and high-value lists can sort by `priority_score`, without loading full `OrderEntity` payloads.

### 3. The entity exposes the projection

`OrderEntity` registers the projection with CacheDB:

```java
@CacheProjectionDefinition("orderSummary")
public static EntityProjection<OrderEntity, OrderReadModels.OrderSummary, Long> orderSummaryProjection() {
    return OrderReadModels.ORDER_SUMMARY_PROJECTION;
}
```

The generated binding then exposes:

```java
OrderEntityCacheBinding.orderSummary(orderRepository)
OrderEntityCacheBinding.customerTimeline(orderSummaryRepository, customerId, limit)
OrderEntityCacheBinding.recentHighValueOrders(orderSummaryRepository, minimumAmount, limit)
```

### 4. The API route uses the projection repository

The hot customer timeline endpoint is intentionally a projection route:

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

The response shape is already the screen shape. The UI does not receive hidden full order entities.

## Query Flow: Redis vs PostgreSQL

This sample now shows both paths explicitly. Hot operational reads go through CacheDB/Redis. Archive reads go directly to PostgreSQL.

| Route | First runtime path | When PostgreSQL is used | Redis behavior | Why |
|---|---|---|---|---|
| `POST /api/customers` | `EntityRepository.save` | Write-behind persists the row asynchronously | Entity enters Redis if the hot policy admits it | Normal command path |
| `POST /api/orders` | `JdbcTemplate` FK readiness check, then `EntityRepository.save` | PostgreSQL is checked only to make sure the parent customer is durable before inserting a child row | Order is saved through Redis and queued for write-behind | Avoids FK violation while keeping Redis-first write path |
| `GET /api/customers/{id}/orders` | `ProjectionRepository<OrderSummary>` | Not used for the hot list route | Reads Redis projection payload/index; may warm missing projection rows from Redis base entity payloads | Fast customer timeline |
| `GET /api/orders/high-value` | `ProjectionRepository<OrderSummary>` | Not used for the hot list route | Reads ranked Redis projection data | Fast global sorted business list |
| `GET /api/orders/{id}` | `EntityRepository.findById` with `linePreview` fetch preset | Not used by this sample endpoint on a cache miss | Reads Redis entity payload, then relation loader queries Redis for bounded lines | Detail screen for hot orders |
| `GET /api/orders/archive` | `JdbcTemplate.query` | Direct PostgreSQL read | Does not mutate Redis | Cold/archive history path |
| `GET /api/products/active` | `EntityRepository.query` | Not used by this sample endpoint | Bounded Redis entity query | Small catalog list |
| `GET /api/tickets/open` | `EntityRepository.query` | Not used by this sample endpoint | Bounded Redis entity query | Operational queue |
| `GET /api/dashboard/commerce` | Projection query plus ticket entity query | Not used by this sample endpoint | Combines Redis projection and Redis entity query | Dashboard first paint |

The important rule: CacheDB repository reads are not a license to scan the database on every miss. In this sample, `EntityRepository.findById` and normal `query(...)` routes are Redis/hot-set routes. If you need archive or full-history reads, expose that as an explicit SQL route, as this sample does with:

```java
@GetMapping("/archive")
public List<OrderReadModels.OrderSummary> archiveFromSql(
        @RequestParam long customerId,
        @RequestParam(required = false) Long beforeOrderDate,
        @RequestParam(defaultValue = "100") int limit
) {
    return jdbcTemplate.query(ARCHIVE_SQL, rowMapper, customerId, upperBound, safeLimit);
}
```

The SQL route uses the same response model, but a different source:

```sql
SELECT order_id, customer_id, order_date, order_amount, currency_code,
       order_type, status, line_count, priority_score
FROM sample_orders
WHERE customer_id = ? AND order_date < ?
ORDER BY order_date DESC, order_id DESC
OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY
```

This is the production pattern:

| Screen type | Use Redis/CacheDB | Use PostgreSQL |
|---|---|---|
| First page timeline | Yes, projection | No |
| High-value sorted list | Yes, ranked projection | No |
| Detail for selected hot order | Yes, entity detail with preview relation | Only if you build an explicit cold detail route |
| Old archive/history page | No, unless intentionally warmed | Yes, bounded SQL query |
| Export/reporting job | Usually no | Yes, batch/reporting path |
| Migration warm/backfill | Writes selected hot set to Redis | Reads source rows from PostgreSQL |

## Real-World Use Cases in This Sample

| Use case | Endpoint | CacheDB shape | Why this shape |
|---|---|---|---|
| Customer opens order history | `GET /api/customers/{id}/orders?limit=20` | Projection query on `OrderSummary` | Growing timeline, compact payload, stable sort |
| User opens one order | `GET /api/orders/{id}?linePreview=5` | Entity detail plus bounded relation preview | Detail needs more data, but still not all lines unless requested |
| Operations checks high-value orders | `GET /api/orders/high-value?minimumAmount=500&limit=25` | Ranked projection query | Global sorted screen should not scan full entities |
| User opens older history | `GET /api/orders/archive?customerId=1&limit=20` | Direct PostgreSQL query returning `OrderSummary` | Archive reads should not pollute Redis unless intentionally warmed |
| Product category list | `GET /api/products/active?category=electronics&limit=20` | Named entity query | Small bounded catalog route is acceptable as entity query |
| Support queue | `GET /api/tickets/open?limit=25` | Named entity query with status index | Operational queue is bounded and status-filtered |
| Commerce dashboard | `GET /api/dashboard/commerce?limit=25` | Projection plus ticket entity query | Dashboard combines small pre-shaped read models |
| Create customer | `POST /api/customers` | Entity save | Redis-first write, SQL write-behind |
| Create order | `POST /api/orders` | Entity save with FK readiness check | Child write waits until parent is durable in SQL |
| Update order status | `PATCH /api/orders/{id}/status` | Read entity, mutate full object, save | Partial update is implemented as explicit full-entity save |
| Delete order | `DELETE /api/orders/{id}` | Repository delete | Removes active cache record and schedules durable delete |

## Day 1 to Production Load

| Stage | Data shape | What to do |
|---|---|---|
| Day 1 local demo | 20 customers, 40 orders each, 4 lines each | Run seed, inspect API responses, open admin UI, understand the generated bindings |
| First staging run | Thousands of customers, real-like order distribution | Keep API limits below projection window, verify SQL indexes, check projection lag |
| Growing traffic | Many customers repeatedly opening timelines | Increase Redis `maxmemory`, size `hotEntityLimit`, keep timelines on projection, watch hit/miss and write-behind backlog |
| Large customer fan-out | Some customers have thousands of orders | Never hydrate `Customer -> all Orders`; use `OrderSummary` timeline plus explicit order detail |
| Dashboard growth | More global sorted and KPI screens | Add route-specific projections instead of reusing full entities |
| Multi-pod runtime | Several application containers | Keep unique consumer names enabled and use leader lease for singleton maintenance loops |

## Tuning Playbook

Start with the sample values, then tune by evidence:

| Signal | Where to look | Action |
|---|---|---|
| Redis memory grows too fast | Admin UI, Redis `INFO memory`, `/api/tuning` | Lower hot window, add stricter hot policy, reduce projection payload |
| Timeline query is slow | API latency and projection route label | Confirm the route uses `projection:order-summary`, not entity fallback |
| SQL gets too much read traffic | SQL metrics, slow query log | Move repeated list screens to projection, add route indexes |
| Write-behind backlog grows | Admin UI write-behind section | Increase worker/batch carefully, inspect SQL locks, add backpressure |
| Projection lag grows | Admin UI projection telemetry | Reduce refresh batch pressure or split projections by route |
| Large responses hit clients | API payload size | Lower controller clamp and use detail follow-up endpoints |

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

## SampleCacheDbTuningConfig Reference

`SampleCacheDbTuningConfig` is the complete tuning entry point used by this sample. It is intentionally small enough to read in one pass, but every value has production impact.

### Resource Limits

| Parameter | Sample value | What it means | When to change |
|---|---:|---|---|
| `defaultCachePolicy` | custom `CachePolicy` | The default Redis admission, retention, TTL, and page behavior for entities that do not override policy. | Change only after defining route contracts and Redis memory budget. |
| `maxRegisteredEntities` | `64` | Maximum entity metadata registrations allowed in this CacheDB runtime. It protects startup/runtime from accidentally loading an unexpectedly large model surface. | Increase when the application legitimately has more CacheDB entities. Do not raise it to hide uncontrolled package scanning. |
| `maxColumnsPerOperation` | `64` | Upper bound for columns handled by one generated operation. It protects write/read paths from very wide entities and accidental payload explosion. | Increase only for a measured wide entity. Prefer splitting bloated tables into explicit read models or projections. |

### Default Cache Policy

| Parameter | Sample value | What it means | Production guidance |
|---|---:|---|---|
| `hotEntityLimit` | `5_000` | Coarse maximum active entity count for the default policy. It bounds how many full entity rows can stay in Redis before policy pressure starts. | Estimate from Redis memory: average entity size plus indexes times active row count. Do not use this as the limit for large list screens; use projections. |
| `pageSize` | `100` | Default page/query size when the caller does not provide a tighter limit. | Keep close to UI page size. If a screen needs more than this repeatedly, design a projection route. |
| `entityTtlSeconds` | `0` | Full entity keys do not expire by TTL. Retention is policy-driven instead of time-expiry-driven. | Good for durable business entities. Use a positive TTL only for ephemeral data that is safe to rebuild. |
| `pageTtlSeconds` | `120` | Cached page/query result TTL is two minutes. | Keep short because page ordering can become stale. Projection rows can live longer than page caches. |
| `compositeHotPolicy(ANY, ...)` | `ANY` | A record is admitted if any child policy matches. In this sample: recent order OR operational status OR active product/status field. | `ANY` is useful for operational systems but can admit more data. Use staging memory reports before tightening to `ALL`. |
| `timeWindow("order_date", 90 days)` | `90 days` | Orders with `order_date` inside the last 90 days may stay in Redis. | Tune to the business working set, not arbitrary time. For example, billing may need 13 months; support queues may need days. |
| `stateWindow("status", ACTIVE/NEW/PAID/PICKING/OPEN/PENDING)` | active operational states | Records in active workflow states may stay in Redis even if they are older than the time window. | Keep the state list small. Large status buckets can over-admit old data. |
| `stateWindow("active_status", ACTIVE)` | `ACTIVE` | Product/catalog-style records with active status may stay in Redis. | Use for small active catalogs. Do not put archive states here. |

### Read Shape Guardrail

| Parameter | Sample value | What it means | Production guidance |
|---|---:|---|---|
| `enabled` | `true` | Enables read-shape protection. Oversized query shapes are rejected instead of silently becoming expensive. | Keep enabled in production. Disabling it turns accidental large reads into runtime risk. |
| `maxEntityQueryLimit` | `250` | Maximum rows allowed for full entity query surfaces. | Keep low. Entity rows are larger and can trigger relation work. |
| `maxProjectionQueryLimit` | `1_000` | Maximum rows allowed for projection query surfaces. | Can be higher than entity limit because projection rows are compact. Still keep bounded. |
| `hotSetHeadroom` | `10` | Safety margin between requested windows and active-set boundaries. | Increase when routes frequently hit the edge of the active data set. |

### Redis Guardrail

| Parameter | Sample value | What it means | Production guidance |
|---|---:|---|---|
| `enabled` | `true` | Enables Redis pressure checks and runtime safety behavior. | Keep enabled. Redis is a bounded runtime dependency, not unlimited heap. |
| `producerBackpressureEnabled` | `true` | Slows producers when Redis/write-behind pressure is high. | Keep enabled to avoid amplifying outages under burst writes. |
| `usedMemoryWarnMaxmemoryPercent` | `75` | Warning threshold based on Redis `used_memory / maxmemory`. | Alert and reduce admission before memory becomes critical. |
| `usedMemoryCriticalMaxmemoryPercent` | `88` | Critical memory pressure threshold. | At this level, reduce admission, inspect large prefixes, and avoid expanding hot windows. |
| `expectedMaxmemoryPolicy` | `noeviction` | Expected Redis eviction policy. CacheDB wants deterministic admission/eviction decisions, not Redis evicting arbitrary keys. | Use `noeviction`. If Redis evicts keys behind CacheDB, projections and indexes can become inconsistent. |
| `warnOnMissingMaxmemory` | `true` | Emits a warning when Redis has no `maxmemory` configured. | Keep enabled. A Redis without memory limit makes local demos easy but production behavior unsafe. |
| `writeBehindBacklogWarnThreshold` | `500` | Warning threshold for pending durable writes. | Lower for strict durability windows; raise only after measuring SQL throughput. |
| `writeBehindBacklogCriticalThreshold` | `2_000` | Critical backlog threshold for durable writes. | Treat as incident-level pressure: check SQL locks, pool saturation, batch size, and Redis memory. |
| `automaticRuntimeProfileSwitchingEnabled` | `true` | Allows CacheDB to switch runtime behavior when guardrails detect pressure. | Keep enabled for samples and production unless you have a separate operational controller. |

### Write-Behind

| Parameter | Sample value | What it means | Production guidance |
|---|---:|---|---|
| `workerThreads` | `2` | Number of background workers flushing Redis-accepted writes to SQL. | Increase only when SQL has spare connections/CPU and backlog is growing. |
| `batchSize` | `128` | Target number of queued writes grouped for a flush cycle. | Increase for throughput after measuring lock duration and flush latency. |
| `maxFlushBatchSize` | `128` | Hard cap for one flush batch. | Keep aligned with `batchSize` until load tests show SQL benefits from larger batches. |
| `tableAwareBatchingEnabled` | `true` | Groups writes by table to improve SQL batch behavior and reduce mixed-shape flushes. | Keep enabled for most production workloads. |
| `batchFlushEnabled` | `true` | Enables batch flushing instead of one-row-at-a-time durable writes. | Keep enabled unless diagnosing provider-specific behavior. |
| `coalescingEnabled` | `true` | Combines repeated writes for the same entity before flushing where safe. | Keep enabled for update-heavy workloads. Disable only if every intermediate state must be durable. |
| `maxFlushRetries` | `5` | Retry count for transient SQL flush failures. | Increase only with bounded backoff and visibility. Permanent failures should move to failure handling, not retry forever. |
| `retryBackoffMillis` | `500` | Delay between flush retry attempts. | Tune with SQL failover behavior. Too low creates retry storms; too high increases durability lag. |

### Values Mentioned In The README But Not Set In This Class

| Parameter | Why it still appears |
|---|---|
| `lruEvictionEnabled` | Supported by `CachePolicy`; not explicitly set here, so the framework default applies. It is documented because production teams often tune count-window behavior. |
| `admitOnWrite`, `admitOnRead`, `admitOnWarm`, `evictWhenRejected` | Supported by `EntityHotPolicy`; this sample uses helper constructors with default admission behavior. They are documented because migration, archive, and warm-up flows commonly need these switches. |
| `rejectEntityQueryOverLimit`, `rejectProjectionQueryOverLimit` | Supported by read guardrails; this sample relies on default rejection behavior after setting query limits. |

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
| `writeBehindBacklogWarnThreshold` | Alert before SQL flush lag becomes user-visible. Lower it for strict consistency windows. |
| `writeBehindBacklogCriticalThreshold` | Critical backlog level. At this point inspect SQL locks, pool saturation, and batch size. |

### Write-Behind Parameters

Write-behind tuning is separate from cache admission. Do not fix slow SQL by admitting less data unless Redis memory is also the problem.

| Parameter | Increase when | Decrease when |
|---|---|---|
| `workerThreads` | Backlog grows and SQL has spare connections/CPU. | SQL pool is saturated or lock waits increase. |
| `batchSize` / `maxFlushBatchSize` | Backlog grows and SQL handles batch writes well. | Latency spikes, locks grow, or transactions become too large. |
| `tableAwareBatchingEnabled` | Usually keep enabled; groups writes by table for better flush behavior. | Rarely disabled; only for debugging or provider-specific issues. |
| `coalescingEnabled` | Usually keep enabled; repeated updates to the same entity can collapse. | Disable only when every intermediate state must be durably visible. |
| `maxFlushRetries` / `retryBackoffMillis` | Transient SQL failures occur during deploy/failover. | Retries hide permanent failures and DLQ grows. |

### Practical Profiles

| Profile | When to use | Example direction |
|---|---|---|
| Small admin app | Low traffic, bounded lists | `hotEntityLimit=1_000`, `pageSize=50`, short page TTL, low query limits |
| Commerce timeline | Customer has growing order history | Projection required, `maxProjectionQueryLimit=1_000`, entity detail limit `100-250`, 90-day or active-state hot policy |
| Dashboard/KPI | Repeated global sorted reads | Dedicated projection, short page TTL, strict entity query limit, ranked projection fields |
| Archive lookup | Old records are rarely read | Do not admit on read, keep entity TTL `0` or very short, serve from SQL cold path |
| High write traffic | Writes are bursty | Tune write-behind workers and batch size, keep Redis memory guardrails strict, watch backlog |

## Why Projection Is Used

The customer order timeline can grow without bound. Loading `Customer -> all Orders -> all Lines` on every list screen is not production-safe. This sample keeps the list screen on `OrderSummary`, then loads full order details only after the user selects one order.

BEST:

- Use `OrderSummary` projection for customer timelines and high-value lists.
- Keep API `limit` inside the configured projection window.
- Load `OrderEntity` with `linePreview` only for a detail screen.
- Keep PostgreSQL as the durable source for full history.

ANTI-PATTERN:

- Returning a customer with thousands of full orders and lines in one response.
- Exposing unbounded `findAll` list endpoints.
- Treating Redis memory as unlimited.

## Tuning Points

The sample configures a realistic starting point in `SampleCacheDbTuningConfig`:

| Area | Sample setting | Why |
|---|---|---|
| Active data window | `hotEntityLimit=5000` | Keeps Redis bounded for local testing |
| Entity TTL | `0` | Entity records are not removed by TTL in this sample |
| Page TTL | `120s` | Short-lived page cache |
| Active data policy | last 90 days or active operational states | Closer to real operational screens than plain LRU |
| Entity query limit | `250` | Prevents accidental large entity scans |
| Projection query limit | `1000` | Allows timeline windows but blocks unbounded reads |
| Redis guardrails | warning 75%, critical 88% | Makes memory pressure visible early |

For production, also set Redis `maxmemory`, keep `maxmemory-policy=noeviction`, size the connection pools, and keep admin UI behind your gateway/auth layer.

## Postman

Import:

```text
postman/cache-database-postgresql-sample.postman_collection.json
```

The collection contains the normal flow: readiness, seed, customer timeline, detail, high-value projection, archive SQL read, dashboard, update, delete, and tuning.

## PostgreSQL Notes

The schema is created by `src/main/resources/schema.sql`. It includes primary keys, foreign keys, and indexes for the hot routes:

- `sample_orders(customer_id, order_date DESC, order_id DESC)`
- `sample_orders(priority_score DESC, order_date DESC)`
- `sample_order_lines(order_id, line_number)`
- `sample_support_tickets(status, priority, updated_at DESC)`

The seed endpoint writes through CacheDB and waits for parent rows before writing dependent child rows. That is intentional because the database has foreign keys.

`POST /api/orders` also waits briefly until the customer row is durable in PostgreSQL. If the parent customer was just created and write-behind has not flushed it yet, the endpoint returns `409` and the client should retry.

## Troubleshooting

If the API cannot resolve CacheDB dependencies, verify access to the package repository configured in `pom.xml`.

If the seed endpoint is slow, check `GET /api/health/ready` and the admin UI write-behind section. The seed flow waits for SQL rows so foreign keys are not violated.

If timeline results are empty immediately after seed, wait a few seconds and retry. Projection refresh is asynchronous.
