# CacheDB PostgreSQL REST API Sample

English | [Türkçe](README.tr.md)

This is a standalone Spring Boot REST API that shows how a normal application can use CacheDB with Redis and PostgreSQL without building the main CacheDB repository locally.

The sample models a commerce support system:

- Customers place many orders.
- Orders have many order lines.
- Products are read frequently by category.
- Support tickets feed a small operational dashboard.
- Customer order timelines are served from a projection/read-model instead of hydrating full aggregates.

## Product Positioning In This Sample

This sample does not present CacheDB as a transparent cache in front of
PostgreSQL. The API is intentionally split into active Redis routes and explicit
PostgreSQL routes.

| Route type | Example | Data path | Contract |
|---|---|---|---|
| Operational entity write | `POST /api/orders` | Redis first, PostgreSQL write-behind | The write is accepted through CacheDB and later flushed durably. Redis residency still depends on hot policy. |
| Operational list | `GET /api/customers/{id}/orders` | Redis projection: `OrderSummary` | The list reads a bounded read model, not the full order aggregate. |
| Selected detail | `GET /api/orders/{id}` | Redis entity + bounded relation preview | Works for active-set data. If the order is outside the active set, use an explicit cold-detail route. |
| Archive/history | `GET /api/orders/archive` | Direct PostgreSQL query | Old history, export, and audit reads must not pollute Redis by default. |
| Dashboard | `GET /api/dashboard/commerce` | Redis projection and bounded entity query | Dashboard rows are pre-shaped for the screen. |

BEST: design the active route first, define the hot policy, serve lists from
projection repositories, and keep archive/history reads as explicit PostgreSQL
queries.

ANTI-PATTERN: expect a broad dynamic entity query to miss Redis, scan
PostgreSQL, fill Redis, and still remain predictable under production memory
limits.

## Dependency Model

This project intentionally consumes CacheDB as an external Maven package:

```xml
<properties>
  <java.version>21</java.version>
  <cachedb.version>0.6.0</cachedb.version>
</properties>

<repositories>
  <repository>
    <id>cache-database-github-packages</id>
    <name>CacheDB GitHub Packages</name>
    <url>https://maven.pkg.github.com/esasmer-dou/cache-database</url>
  </repository>
</repositories>

<dependencies>
  <dependency>
    <groupId>com.reactor.cachedb</groupId>
    <artifactId>cachedb-spring-boot-starter</artifactId>
    <version>${cachedb.version}</version>
  </dependency>
  <dependency>
    <groupId>com.reactor.cachedb</groupId>
    <artifactId>cachedb-annotations</artifactId>
    <version>${cachedb.version}</version>
  </dependency>

  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-jdbc</artifactId>
  </dependency>
  <dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
  </dependency>
</dependencies>

<build>
  <plugins>
    <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-compiler-plugin</artifactId>
      <configuration>
        <release>${java.version}</release>
        <annotationProcessorPaths>
          <path>
            <groupId>com.reactor.cachedb</groupId>
            <artifactId>cachedb-processor</artifactId>
            <version>${cachedb.version}</version>
          </path>
        </annotationProcessorPaths>
      </configuration>
    </plugin>
  </plugins>
</build>
```

Users should not build the parent repository first. CacheDB `0.6.0` is published from the main repository to GitHub Packages.
The annotation dependency and `cachedb-processor` are required for generated bindings such as `OrderEntityCacheBinding`.

Runtime and build requirement: use JDK 21. The sample `pom.xml` sets
`<java.version>21</java.version>` and compiles with Java release 21.

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

Use a GitHub personal access token with `read:packages` access, then run:

```bash
export GITHUB_ACTOR=your-github-user
export GITHUB_TOKEN=your-read-packages-token
mvn clean package
```

If you do not configure credentials, Maven will usually fail with `401 Unauthorized` even though the repository URL is correct.

## 0.6.0 Verified Declarative Path

This sample is wired for CacheDB `0.6.0`. The important runtime contract is:

1. Writes go through CacheDB and are flushed to PostgreSQL by write-behind.
2. Existing PostgreSQL rows are not magically loaded into Redis at startup.
3. Routes that must be fast need either a bounded entity active set or a projection.
4. Warm/backfill uses the registered JDBC loader and then hydrates Redis/projection rows.
5. Load tests must run after seed data is durable in PostgreSQL and after the warm step finishes.

The sample code makes that contract explicit.

### Production contracts validated in 0.6.0

- Command endpoints return `202 Accepted`; this means Redis accepted the command, not that SQL durability is complete.
- Child writes first reuse a pending durability receipt when possible; otherwise they perform one indexed SQL existence check. A non-durable parent returns `409 Conflict` with `Retry-After` instead of polling a request thread.
- `DELETE /api/orders/{id}` is an explicit soft delete that changes status to `DELETED`. Physical aggregate deletion is intentionally not exposed through asynchronous write-behind.
- Every entity has a route-specific admission policy. Order, catalog, support, logistics, reporting and audit data no longer share one misleading default policy.
- Monetary fields use `BigDecimal` and `NUMERIC(19,4)`. Redis ranking scores remain non-monetary `double` values.
- Relation loaders are generated from typed `@CacheRelation` metadata and use bounded `IN (...)` batches rather than one query per parent.
- Warm/backfill is a durable Redis Stream job (`1` worker, queue capacity `8`). Another pod can claim abandoned work and resume from its checkpoint.
- `/actuator/health/liveness` reports process liveness. `/actuator/health/readiness` checks Redis, SQL and write-behind telemetry.
- Oversized route limits return `400 Bad Request`; they are never silently clamped.
- Application services inject one generated `GeneratedCacheModule.Scope`; they do not construct repositories or bindings manually.
- Per-entity admission policies come from `application.yml`, while `cachedb.registration.source: jdbc` makes the database registration source explicit.
- Named queries, fetch plans, projections and typed warm plans are generated at compile time. `ProjectionSchema` keeps projection serialization and field order explicit.

Version `0.6.0` also keeps JDBC reads and writes bounded: registered JDBC warm
queries time out after 15 seconds, write-behind statements after 20 seconds, and the
admin request/background queues have explicit capacities in `application.yml`.
Version-aware hydration prevents an older warm result from
overwriting newer Redis state.

```java
@Bean
CacheDatabaseConfigCustomizer sampleCacheDbTuning() {
    return (builder, properties) -> builder
            .readThrough(ReadThroughConfig.builder()
                    .mode(ReadThroughMode.REDIS_ONLY)
                    .failOnMissingLoader(false)
                    .hydrateLoadedEntities(false)
                    .maxQueryLoadRows(1_000)
                    .queryTimeoutSeconds(15)
                    .build())
            .writeBehind(WriteBehindConfig.builder()
                    .workerThreads(2)
                    .batchSize(128)
                    .statementTimeoutSeconds(20)
                    .build());
}
```

Spring Boot performs JDBC registration automatically from the generated registrar and the per-entity policy catalog in `application.yml`. Application code must not call `registerJdbcBacked(...)` or create one repository bean per entity.

```yaml
cachedb:
  registration:
    source: jdbc
    fail-on-unknown-entity: true
```

The warm endpoint uses the typed generated scope and a route-shaped query, never a full table scan:

```java
CacheWarmPlan plan = domain.orders().queries()
        .customerTimelineWarmPlan(customerId, limit);
CacheWarmResult result = cacheDatabase.warmProjections(plan);
```
Run the local load gate after the app is ready:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run-load-test.ps1 `
  -RouteProfile hot-timeline `
  -Concurrency 4 `
  -DurationSeconds 10 `
  -SeedCustomers 10 `
  -OrdersPerCustomer 20 `
  -LinesPerOrder 4 `
  -WarmCustomers 10 `
  -WarmLimit 100 `
  -MaxP95Millis 500
```

Historical local load baseline retained from `0.2.0`:

| Provider | Version | Route profile | Result |
|---|---:|---|---|
| PostgreSQL | `0.2.0` | `hot-timeline` | `ok=605`, `fail=0`, `p95=184 ms` |

This is a sample-machine smoke gate, not a production benchmark. In staging,
increase data volume, run longer duration tests, and watch Redis memory,
projection lag, write-behind backlog, PostgreSQL latency, and JVM GC.

## Declarative Periodic Warm And Reconciliation

[`SampleScheduledWarmPlans.java`](src/main/java/com/example/cachedb/sample/config/SampleScheduledWarmPlans.java)
keeps the active order route refreshed without putting `@Scheduled`, Redis lock
code, or repository wiring in application services.

```java
@CacheScheduledWarm(
        name = "sample-active-order-window",
        enabledString = "${sample.scheduled-warm.enabled:true}",
        fixedDelayString = "${sample.scheduled-warm.orders.fixed-delay:PT15M}",
        initialDelayString = "${sample.scheduled-warm.orders.initial-delay:PT30S}",
        lockAtMostForString = "${sample.scheduled-warm.orders.lock-at-most-for:PT2M}",
        lockWaitTimeoutString = "${sample.scheduled-warm.orders.lock-wait-timeout:PT20S}",
        minimumIntervalString = "${sample.scheduled-warm.orders.minimum-interval:PT15M}",
        reconcileHotSet = true,
        reconcileMaxRowsPerRunString = "${sample.scheduled-warm.orders.reconcile-max-rows:10000}",
        reconcileScanCountString = "${sample.scheduled-warm.orders.reconcile-scan-count:500}"
)
public CacheWarmPlan activeOrderWindow() {
    long cutoff = Instant.now().minus(Duration.ofDays(90)).getEpochSecond();
    return domain.orders().queries()
            .activeOrderWindowWarmPlan(cutoff, orderWarmMaxRows);
}
```

The sample policy is **last 90 days OR an active order status**, not strictly
only 90 days. This is intentional: an old order still in `OPEN` or `PENDING`
remains part of the active business set. Use a single `TIME_WINDOW` policy and a
matching query when the requirement is strictly the last 90 days.

Runtime flow:

1. Every app pod triggers the same annotation.
2. One pod obtains and renews the Redis lease; other pods wait for at most 20 seconds.
3. The owner reads at most `warm-max-rows` through the registered, bounded PostgreSQL JDBC loader.
4. Version-fenced hydration admits matching rows into Redis and refreshes projections.
5. Reconciliation removes rows that no longer match the policy from Redis without mutating PostgreSQL.
6. The owner writes a completion marker; waiting pods skip the duplicate cycle.

Inspect pod-local status with:

```powershell
Invoke-RestMethod "http://127.0.0.1:8091/api/warm/schedules"
```

`COMPLETED` includes loaded/submitted counts plus reconciliation cursor,
full-cycle, inspected, evicted, missing, and invalid-payload counters.
`SKIPPED_LOCK_TIMEOUT` means another pod still owned the lease after the bounded
wait. `LEASE_LOST` means no completion marker was committed and a later cycle
will retry.

- `warm-max-rows` must not exceed `cachedb.config.readThrough.maxQueryLoadRows`.
- With 100,000 hot rows, 10,000 reconciliation rows, and a 15-minute interval, a full cleanup scan is approximately 150 minutes.
- Direct PostgreSQL writes appear on the next successful schedule. Use outbox/CDC when that lag is unacceptable.
- CacheDB writes enter Redis immediately and do not wait for this scheduler.

See [Scheduled Warm and Hot-Set Reconciliation](../docs/scheduled-warm.md) for
the full parameter, failure, and capacity contract.

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
curl http://127.0.0.1:8091/actuator/health/readiness
```

4. Seed realistic demo data:

```bash
curl -X POST "http://127.0.0.1:8091/api/demo/seed?customers=20&ordersPerCustomer=40&linesPerOrder=4"
```

The endpoint returns `202 Accepted` with a `jobId`; it does not keep the HTTP
connection open while thousands of rows are written. Poll the shared Redis job
status and continue only after `COMPLETED`:

```bash
curl "http://127.0.0.1:8091/api/warm/jobs/<jobId>"
```

5. Open the CacheDB admin UI:

```text
http://127.0.0.1:8091/cachedb-admin
```

## Main API Flow

| Step | Endpoint | Main data path | What it demonstrates |
|---|---|---|---|
| Health | `GET /actuator/health/readiness` | Runtime checks | Redis, SQL and write-behind readiness summary |
| Seed | `POST /api/demo/seed` | Bounded background job, batched Redis writes, PostgreSQL write-behind | Backpressure, SQL persistence, projection refresh, cross-pod job status |
| Customer detail | `GET /api/customers/1?orderPreview=5` | Redis entity + bounded relation preview | Entity detail with bounded relation preview |
| Timeline | `GET /api/customers/1/orders?limit=20` | Redis projection: `OrderSummary` | Customer order list without full aggregate hydration |
| Warm customer orders | `POST /api/warm/orders/customer/1?limit=100` | PostgreSQL read -> Redis hydrate | Explicit warm/backfill for active-set and projection routes |
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

## Copy-Paste Active-Set Route Playbook

Use this section to see the intended model end to end. The commands assume the
sample is running on `8091` and you are in the `sample-cache-database-postgresql`
directory.

### Scenario 1: Existing SQL Rows Do Not Automatically Appear In Redis

Seed data through CacheDB first so PostgreSQL has durable rows:

```bash
curl.exe -X POST "http://127.0.0.1:8091/api/demo/seed?customers=20&ordersPerCustomer=40&linesPerOrder=4"
```

Copy the returned `jobId`, poll `GET /api/warm/jobs/{jobId}`, and wait for
`"status":"COMPLETED"` before clearing Redis. Otherwise the seed worker and
`FLUSHDB` race with each other.

Now clear Redis to simulate an existing PostgreSQL database with an empty active set:

```bash
docker compose exec redis redis-cli FLUSHDB
```

This Redis projection route may now return an empty list because the projection
active set is empty:

```bash
curl.exe "http://127.0.0.1:8091/api/customers/1/orders?limit=5"
```

The explicit SQL archive route still sees the durable rows:

```bash
curl.exe "http://127.0.0.1:8091/api/orders/archive?customerId=1&limit=5"
```

Production meaning: entity/projection reads are active-set reads. Existing SQL
rows need an explicit warm/backfill path before Redis routes can serve them.

### Scenario 2: Warm The Redis Projection For A Route

Run a dry-run first. It reads PostgreSQL and reports how many rows would be
warmed, but it does not mutate Redis:

```bash
curl.exe -X POST "http://127.0.0.1:8091/api/warm/orders/customer/1?limit=100&dryRun=true"
```

Every warm `POST` returns `202` with a `jobId`. Do not run the target route until
the job reaches `COMPLETED`:

```powershell
$job = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8091/api/warm/orders/customer/1?limit=100&projectionOnly=true"
do {
    Start-Sleep -Milliseconds 250
    $state = Invoke-RestMethod "http://127.0.0.1:8091/api/warm/jobs/$($job.jobId)"
} while ($state.status -in @("QUEUED", "RUNNING"))
if ($state.status -ne "COMPLETED") { throw ($state.error | ConvertTo-Json -Compress) }
```

The submitted job warms only the `OrderSummary` projection for the customer timeline route:

```bash
curl.exe -X POST "http://127.0.0.1:8091/api/warm/orders/customer/1?limit=100&projectionOnly=true"
```

Now the timeline route reads Redis projection rows:

```bash
curl.exe "http://127.0.0.1:8091/api/customers/1/orders?limit=5"
```

Projection-only warm is the right shape when the screen is a list or dashboard
and does not need full `OrderEntity` payloads.

### Scenario 3: Warm Full Entity Payloads For A Detail Route

If the next screen needs selected order details, warm the full active entity
window as well:

```bash
curl.exe -X POST "http://127.0.0.1:8091/api/warm/orders/customer/1?limit=100&projectionOnly=false"
```

Then a selected detail route can read Redis entity data:

```bash
curl.exe "http://127.0.0.1:8091/api/orders/10001?linePreview=5"
```

Production rule: do not warm full entities just because a list exists. Warm full
entities only for selected detail or command routes that actually need them.

### Scenario 4: New Writes Enter Redis Immediately

Create a customer through CacheDB:

```bash
curl.exe -X POST "http://127.0.0.1:8091/api/customers" -H "Content-Type: application/json" -d '{"customerId":9001,"taxNumber":"TAX-9001","customerType":"RETAIL","segment":"VIP","status":"ACTIVE"}'
```

Create an order through CacheDB:

```bash
curl.exe -X POST "http://127.0.0.1:8091/api/orders" -H "Content-Type: application/json" -d '{"orderId":90010001,"customerId":9001,"orderAmount":725.50,"currencyCode":"USD","orderType":"EXPRESS","status":"PAID","lineCount":0}'
```

Read the Redis projection route:

```bash
curl.exe "http://127.0.0.1:8091/api/customers/9001/orders?limit=5"
```

Read the selected Redis entity detail:

```bash
curl.exe "http://127.0.0.1:8091/api/orders/90010001?linePreview=5"
```

Production meaning: CacheDB write paths populate Redis first and flush the
durable row to PostgreSQL through write-behind. Existing SQL rows use warm;
new CacheDB writes do not need a separate warm step.

### Scenario 5: Archive And Export Stay On SQL

Use the explicit SQL route for old history, export, audit, and one-off lookups:

```bash
curl.exe "http://127.0.0.1:8091/api/orders/archive?customerId=1&beforeOrderDate=9999999999999&beforeOrderId=9999999999999&limit=20"
```

Do not implement archive by widening the Redis hot set until it contains the
whole table. That turns Redis into a second archive database.

### Copy-Paste Declarative Implementation

The application declares model and route contracts. CacheDB generates the typed access surface and Spring Boot registers JDBC loaders automatically.

#### 1. Declare the entity and bounded query

```java
@CacheEntity(table = "sample_orders", redisNamespace = "sample-orders")
@CachePartitionedIndex(partitionBy = "customer_id", sortBy = "order_date")
public class OrderEntity {
    @CacheId(column = "order_id")
    public Long orderId;

    @CacheColumn("customer_id")
    public Long customerId;

    @CacheColumn("order_date")
    public Long orderDate;

    @CacheColumn("order_amount")
    public BigDecimal orderAmount;

    @CacheColumn("status")
    public String status;

    @CacheProjectionDefinition("orderSummary")
    public static EntityProjection<OrderEntity, OrderSummary, Long> orderSummaryProjection() {
        return OrderSummaryProjection.PROJECTION;
    }

    @CacheNamedQuery("customerTimeline")
    @CacheRoute(
            value = "customer-order-timeline",
            projection = "orderSummary",
            pageSize = 100,
            hotWindow = 1_000,
            maxColdReadSize = 500,
            memoryBudgetBytes = 16_777_216
    )
    public static QuerySpec customerTimelineQuery(long customerId, int limit) {
        return QuerySpec.where(QueryFilter.eq("customer_id", customerId))
                .orderBy(QuerySort.desc("order_date"), QuerySort.desc("order_id"))
                .limitTo(limit);
    }
}
```

The query is named, bounded, partition-indexed, and attached to an explicit
route contract. Controllers do not assemble arbitrary scans or choose an entity
fallback for a projection-required route.

#### 2. Generate projection serialization from the record

```java
@CacheProjectionRecord(
        source = OrderEntity.class,
        id = "orderId",
        name = "order-summary",
        rankedBy = {"order_date", "priority_score"},
        refresh = CacheProjectionRecord.Refresh.ASYNC
)
public record OrderSummary(
        Long orderId,
        Long customerId,
        Long orderDate,
        BigDecimal orderAmount,
        String currencyCode,
        String orderType,
        String status,
        Integer lineCount,
        Double priorityScore
) {
}
```

The processor generates both `OrderSummaryProjectionSchema` and
`OrderSummaryProjection`, including entity mapping, ranking and refresh mode.
The record remains the single source for Redis encoding, decoding and query-index
column extraction.

#### 3. Put per-entity policy in configuration

```yaml
cachedb:
  registration:
    source: jdbc
    fail-on-unknown-entity: true
    entities:
      OrderEntity:
        hot-entity-limit: 100000
        page-size: 100
        entity-ttl-seconds: 0
        page-ttl-seconds: 60
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
```

At startup CacheDB first registers every entity with its own policy and JDBC source, then wires relation/page loaders. A parent relation therefore cannot create a child repository with the parent policy. A misspelled entity name fails startup.

#### 4. Generate one Spring domain bean

```java
@CacheDomain(spring = true)
package com.example.cachedb.sample.domain;
```

Put this in `domain/package-info.java`. The processor generates the Spring
configuration and one immutable `GeneratedCacheModule.Scope`; no repository or
projection bean wiring is required.

#### 5. Use the generated DSL

```java
@Service
public final class CustomerApplicationService {
    private final GeneratedCacheModule.Scope domain;

    public CustomerApplicationService(GeneratedCacheModule.Scope domain) {
        this.domain = domain;
    }

    public List<OrderSummary> orderTimeline(long customerId, int limit) {
        return domain.orders().queries().customerTimelineProjection(customerId, limit);
    }
}
```

The controller validates HTTP input and delegates to this service. Neither layer
knows `EntityRegistry`, Redis keys, codecs, JDBC loaders or projection
implementation details.

#### 6. Warm with a typed plan

```java
@Service
public class CustomerOrderWarmService {
    private final CacheDatabase cacheDatabase;
    private final GeneratedCacheModule.Scope domain;

    public CustomerOrderWarmService(CacheDatabase cacheDatabase, GeneratedCacheModule.Scope domain) {
        this.cacheDatabase = cacheDatabase;
        this.domain = domain;
    }

    public CacheWarmResult dryRun(long customerId, int limit) {
        return cacheDatabase.dryRun(plan(customerId, limit));
    }

    public CacheWarmResult warmProjection(long customerId, int limit) {
        return cacheDatabase.warmProjections(plan(customerId, limit));
    }

    public CacheWarmResult warmEntityAndProjection(long customerId, int limit) {
        return cacheDatabase.warm(plan(customerId, limit));
    }

    private CacheWarmPlan plan(long customerId, int limit) {
        return domain.orders().queries().customerTimelineWarmPlan(customerId, limit);
    }
}
```

Use `dryRun` before mutation, `warmProjections` for list/dashboard routes, and `warm` only when the same window needs full-entity detail reads. HTTP warm execution remains asynchronous and bounded.

#### 7. Keep archive/history explicit

CacheDB does not turn every miss into an unbounded database query. Old history, exports, and audit searches remain explicit indexed SQL routes. Use keyset pagination and a hard page limit.

| Route | Redis entity | Redis projection | PostgreSQL |
|---|---:|---:|---:|
| Customer order timeline | No | Yes | Warm/backfill only |
| Selected hot order detail | Yes | Optional | Explicit cold-detail route only |
| Create/update order | Policy dependent | Refreshed if declared | Write-behind flush |
| Archive/export | No | No | Yes |

## Core Terms Used in This README

| Term | Meaning in this sample | Concrete surface |
|---|---|---|
| Entity | Full command/detail model mapped to one SQL table and Redis namespace | `OrderEntity` |
| Generated binding | Build-time metadata, named queries, fetch presets, commands, and projections | `OrderEntityCacheBinding` |
| Generated domain module | One package-level, typed entry point for application services | `GeneratedCacheModule.Scope` |
| Projection | Compact list/dashboard model that avoids full aggregate loading | `OrderSummary` |
| Projection schema | Generated reflection-free payload and index-column contract | `OrderSummaryProjectionSchema.SCHEMA` |
| Named route | Reusable bounded query plus projection/source constraints | `customerTimelineProjection(...)` |
| Fetch preset | Explicit relation preview for a detail route | `linePreview(...)` |
| Policy catalog | YAML map assigning each entity its own active-data and size policy | `cachedb.registration.entities` |
| Warm plan | Bounded JDBC-to-Redis hydration contract generated from `@CacheRoute` | `customerTimelineWarmPlan(...)` |
| Write-behind | Redis accepts a command first; SQL durability follows asynchronously | `202 Accepted`, worker telemetry |
| Guardrail | Hard limit rejecting unsafe result sizes or memory pressure | API, read-shape, and Redis limits |

## Layer-by-Layer Walkthrough

| Layer | Main files | Responsibility | Rule |
|---|---|---|---|
| API | `web/*Controller.java` | Validate HTTP input and delegate to application services | Never expose an unbounded list |
| Application | `application/*Service.java` | Own use-case flow, source choice and write receipts | Keep Redis/SQL decisions out of controllers |
| Domain declaration | `domain/*Entity.java` | SQL mapping, routes, relations, fetch presets, commands | Keep contracts explicit and compile-time generated |
| Read model | `readmodel/*.java` | Compact projection records and generated entity mapping | Use projection for growing or globally sorted lists |
| Domain access | `domain/package-info.java` | Generate one package-level Spring scope | Use `@CacheDomain(spring = true)` |
| Warm service | `SampleWarmBackfillService.java` | Use generated route plans and select dry-run/projection/full mode | Bound jobs and keep HTTP execution asynchronous |
| Durable query | Generated `...Source(...)` route methods | Read old history and exports from indexed SQL | Use keyset pagination and hard limits |
| Runtime policy | `application.yml` | Per-entity active-data policy and JDBC registration | Fail startup on unknown entity names |
| Platform tuning | `SampleCacheDbTuningConfig.java` | Thread, queue, timeout, memory, write-behind limits | Tune from measured load |

### API Layer: Bound the Request Before It Reaches the ORM

```java
int safeLimit = ApiLimits.requireInRange("limit", limit, 1, 1_000);
return customers.orderTimeline(customerId, safeLimit);
```

### Declarative Domain Access: One Bean, No Repository Wiring

```java
@CacheDomain(spring = true)
package com.example.cachedb.sample.domain;
```

Spring Boot discovers generated registrars and applies YAML policies before the application bean is created. Application code does not register bindings manually.

### Entity Layer: SQL Mapping and Cache Mapping Are Explicit

`OrderEntity` maps the table, Redis namespace, primary key, columns, relation, named queries, and projection:

```java
@CacheEntity(table = "sample_orders", redisNamespace = "sample-orders")
public class OrderEntity {
    @CacheId(column = "order_id")
    public Long orderId;

    @CacheColumn("customer_id")
    public Long customerId;

    @CacheRelation(
            target = OrderLineEntity.class,
            mappedBy = "orderId",
            kind = CacheRelation.RelationKind.ONE_TO_MANY,
            batchLoadOnly = true,
            maxRowsPerParent = 50,
            parentBatchSize = 16,
            orderBy = "lineNumber ASC"
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
        target = OrderEntity.class,
        mappedBy = "customerId",
        kind = CacheRelation.RelationKind.ONE_TO_MANY,
        batchLoadOnly = true,
        maxRowsPerParent = 25,
        parentBatchSize = 16,
        orderBy = "orderDate DESC, orderId DESC"
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

`CustomerController.detail` uses the generated fetch facade:

```java
return domain.customers().fetches()
        .ordersPreview(ApiLimits.requireInRange("orderPreview", orderPreview, 1, 25))
        .findById(customerId)
        .orElseThrow(...);
```

That means a detail screen may show "latest 5 orders", but it does not pull every historical order and every line item into the response.

### Projection Layer: Growing Lists Use `OrderSummary`

The order timeline and high-value order list use `OrderSummary`, not full `OrderEntity`:

```java
@CacheProjectionRecord(
        source = OrderEntity.class,
        id = "orderId",
        name = "order-summary",
        rankedBy = {"order_date", "priority_score"},
        refresh = CacheProjectionRecord.Refresh.ASYNC
)
public record OrderSummary(
        Long orderId,
        Long customerId,
        Long orderDate,
        BigDecimal orderAmount,
        String currencyCode,
        String orderType,
        String status,
        Integer lineCount,
        Double priorityScore
) {
}
```

This keeps list rows compact in Redis. The full entity is still available for detail screens, but list screens do not pay the cost of full aggregate hydration.

## OrderSummary End-to-End Example

`OrderSummary` is the list shape for customer timelines and ranked order screens. It excludes lines, customer details, and audit history by design.

1. `OrderEntity` declares `@CacheProjectionDefinition("orderSummary")`.
2. `@CacheProjectionRecord` generates `OrderSummaryProjectionSchema` and `OrderSummaryProjection` at compile time.
3. The annotation declares source entity, identifier, ranking fields and refresh mode.
4. Warm code uses the generated `customerTimelineWarmPlan(...)` method.
5. The processor generates a strict route method that binds the query to its projection.

```java
return domain.orders().queries().recentHighValueOrdersProjection(minimumAmount, safeLimit);
```

The response is already the screen shape. No hidden full-entity hydration occurs, and the same schema drives Redis serialization and index extraction.

## Query Flow: Redis vs PostgreSQL

This sample now shows both paths explicitly. Hot operational reads go through CacheDB/Redis. Archive reads go directly to PostgreSQL.

| Route | First runtime path | When PostgreSQL is used | Redis behavior | Why |
|---|---|---|---|---|
| `POST /api/customers` | `domain.customers().saveWithReceipt(...)` | Write-behind persists the row asynchronously | Entity enters Redis if the hot policy admits it; the receipt exposes the accepted version | Normal command path with explicit durability tracking |
| `POST /api/orders` | Application service obtains a durable customer reference, then calls `saveAfter(...)` | A pending receipt is reused or one indexed source check confirms the parent; the worker preserves dependency order | The admitted order enters Redis and dependency metadata travels with the write | Preserves SQL foreign-key order without request-thread polling |
| `PATCH /api/orders/{id}/status` | `findVersionedById`, then `save(entity, expectedVersion)` | The winning version is persisted asynchronously | Redis compare-and-set rejects a stale concurrent update | Prevents lost updates |
| `GET /api/customers/{id}/orders` | `customerTimelineProjection(...)` | Not used for the active list route | Reads the partitioned Redis projection index | Fast customer timeline without entity fallback |
| `GET /api/orders/high-value` | `recentHighValueOrdersProjection(...)` | Not used for the active list route | Reads ranked Redis projection data | Fast global sorted business list |
| `GET /api/orders/{id}` | `domain.<entity>().findById(...)` with `linePreview` fetch preset | Not used by this sample endpoint on a cache miss | Reads Redis entity payload, then relation loader queries Redis for bounded lines | Detail screen for hot orders |
| `GET /api/orders/archive` | `customerOrderArchiveSource(...)` | Generated bounded source query reads PostgreSQL directly | Does not mutate Redis | Cold/archive history path without controller SQL |
| `GET /api/products/active` | `domain.products().projections().productAvailability()` | Not used by this sample endpoint | Bounded Redis projection query | Compact catalog list |
| `GET /api/tickets/open` | `domain.<entity>().queries()` | Not used by this sample endpoint | Bounded Redis entity query | Operational queue |
| `GET /api/dashboard/commerce` | Projection query plus ticket entity query | Not used by this sample endpoint | Combines Redis projection and Redis entity query | Dashboard first paint |

The important rule: CacheDB repository reads are not a license to scan the database on every miss. In this sample, `domain.<entity>().findById(...)` and normal `query(...)` routes are Redis/hot-set routes. If you need archive or full-history reads, expose that as an explicit SQL route, as this sample does with:

```java
@GetMapping("/archive")
public List<OrderSummary> archiveFromSql(
        @RequestParam long customerId,
        @RequestParam(required = false) Long beforeOrderDate,
        @RequestParam(required = false) Long beforeOrderId,
        @RequestParam(defaultValue = "100") int limit
) {
    return domain.orders().queries()
            .customerOrderArchiveSource(customerId, upperBound, upperId, safeLimit)
            .stream()
            .map(OrderSummaryProjection::fromEntity)
            .toList();
}
```

The generated source route compiles the same bounded `QuerySpec` through the
PostgreSQL dialect. The controller keeps the same response shape without owning
SQL text, a row mapper, or Redis hydration side effects.

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

## Real-World Tuning Recipes

These recipes are starting profiles, not universal defaults. Use them to decide the first `SampleCacheDbTuningConfig` values, then validate with staging data, Redis memory reports, SQL latency, projection lag, and write-behind backlog.

| Scenario | Entities and read models | Suggested values | Why these values |
|---|---|---|---|
| E-commerce customer order timeline | `CustomerEntity`, `OrderEntity`, `OrderLineEntity`, `OrderSummary` projection | `hotEntityLimit=100_000`, `pageSize=100`, `entityTtlSeconds=0`, `pageTtlSeconds=60`, `compositeHotPolicy=ANY`, `timeWindow("order_date", 90 days)`, `stateWindow("status", NEW/PAID/PICKING/OPEN/PENDING)`, `maxEntityQueryLimit=250`, `maxProjectionQueryLimit=1_000`, Redis warn/critical `75/88`, `workerThreads=4`, `batchSize=256`, `maxFlushBatchSize=256` | The list screen must read `OrderSummary` from Redis projection, while selected order detail can read full entity with a bounded line preview. The 90-day window keeps recent commerce traffic hot, and active statuses keep operational orders hot even when they are older. |
| Logistics shipment tracking | `ShipmentEntity`, `ShipmentEventEntity`, `RouteStopEntity`, `ShipmentTimelineSummary` projection | `hotEntityLimit=150_000`, `pageSize=100`, `entityTtlSeconds=0`, `pageTtlSeconds=30`, `compositeHotPolicy=ANY`, `timeWindow("updated_at", 14 days)`, `stateWindow("shipment_status", IN_TRANSIT/OUT_FOR_DELIVERY/DELAYED/EXCEPTION)`, `maxEntityQueryLimit=200`, `maxProjectionQueryLimit=1_000`, Redis warn/critical `70/85`, `workerThreads=4`, `batchSize=256`, `maxFlushRetries=8`, `retryBackoffMillis=1_000` | Logistics data changes frequently and users repeatedly open the same active shipments. Keep active and exception shipments hot, keep event timelines as projections, and keep retry/backoff less aggressive to survive transient database or network pressure. |
| Reporting and audit archive | `ReportJobEntity`, `AuditEventEntity`, `LedgerEntryEntity`, `ReportRunSummary` projection | `hotEntityLimit=5_000`, `pageSize=50`, `entityTtlSeconds=0`, `pageTtlSeconds=30`, `compositeHotPolicy=ANY`, `timeWindow("created_at", 1 day)`, `stateWindow("status", QUEUED/RUNNING/FAILED)`, `maxEntityQueryLimit=100`, `maxProjectionQueryLimit=500`, Redis warn/critical `70/80`, `workerThreads=2`, `batchSize=64`, use `admitOnRead=false` if you customize archive policies | Reporting should be SQL-first for large scans and exports. Redis should hold only live report jobs and small run summaries; old audit and ledger history should stay in PostgreSQL and be read through explicit SQL/reporting routes. |
| Support operations queue | `SupportTicketEntity`, `TicketMessageEntity`, `CustomerEntity`, `OpenTicketSummary` projection | `hotEntityLimit=50_000`, `pageSize=50`, `entityTtlSeconds=0`, `pageTtlSeconds=20`, `compositeHotPolicy=ANY`, `timeWindow("updated_at", 30 days)`, `stateWindow("status", OPEN/PENDING/ESCALATED/SLA_BREACH)`, `maxEntityQueryLimit=200`, `maxProjectionQueryLimit=1_000`, Redis warn/critical `75/88`, `workerThreads=3`, `batchSize=128`, `coalescingEnabled=true` | Agents reopen the same tickets and queues many times. Keep open/escalated tickets hot, keep queue rows as compact projections, and load full messages only on explicit detail screens. |
| Product catalog and inventory availability | `ProductEntity`, `WarehouseStockEntity`, `InventoryReservationEntity`, `ProductAvailabilitySummary` projection | `hotEntityLimit=25_000`, `pageSize=100`, `entityTtlSeconds=0`, `pageTtlSeconds=15`, `compositeHotPolicy=ANY`, `stateWindow("active_status", ACTIVE)`, `stateWindow("stock_status", IN_STOCK/LOW_STOCK)`, `timeWindow("updated_at", 7 days)`, `maxEntityQueryLimit=250`, `maxProjectionQueryLimit=1_000`, Redis warn/critical `70/85`, `workerThreads=3`, `batchSize=256`, `coalescingEnabled=true` | Catalog pages need fast availability reads, but stock changes can be noisy. Active products and low-stock items stay hot, projection rows serve list/category pages, and coalescing reduces repeated stock-update flushes for the same item. |

BEST: start from the closest recipe, then run a staging warm-up and compare estimated Redis memory with actual `MEMORY USAGE` by key prefix. ANTI-PATTERN: copy the largest `hotEntityLimit` into every service and hope Redis absorbs the model.

## Outside The Active Data Set: Read And Write Behavior

CacheDB does not behave like a dynamic ORM that scans PostgreSQL whenever an entity is missing from Redis. Entity repository reads are bounded active-set reads. PostgreSQL remains the durable source of truth, but archive, export, old-history, and cold-detail screens must use explicit SQL routes or a controlled warm/backfill flow.

One important exception is `findPage(PageWindow)`: it can call an `EntityPageLoader` only if read-through is enabled and a page loader is registered. Do not treat that as a generic fallback for every entity query.

| Operation | If the row is inside the active data set | If the row is outside the active data set | PostgreSQL behavior | Redis/cache behavior |
|---|---|---|---|---|
| `findById(id)` | Returns the Redis entity and applies the requested fetch preset. | Returns empty; sample controllers usually map this to `404`. | The repository read does not call PostgreSQL. | Missing, tombstoned, or policy-rejected entities are not served. If `evictWhenRejected=true`, stale active-set entries can be removed. |
| `query(QuerySpec)` | Uses Redis indexes and Redis payloads, then returns only matching admitted rows. | Returns fewer rows or an empty list. | PostgreSQL is not scanned to fill the gap. | Only Redis-indexed and policy-admitted rows can appear. |
| `findPage(PageWindow)` | Returns the cached page if present. | If read-through and `EntityPageLoader` are configured, the loader may read PostgreSQL; otherwise it returns empty or fails depending on page-cache config. | Only the explicitly registered page loader may call PostgreSQL. | Loaded pages are cached only when guardrails allow the loaded size. |
| Projection query | Returns compact projection rows from Redis projection indexes. | Returns only projection rows that exist inside the projection window. | The projection query does not scan PostgreSQL. | Projection is the correct shape for timelines, dashboards, and top-N screens. |
| Explicit archive/reporting route | Usually not needed for normal first-paint screens. | Use this for old history, export, audit, and cold detail screens. | Reads PostgreSQL with bounded, indexed SQL. | Should not mutate Redis unless the route intentionally warms a hot set. |
| `save(entity)` | Queues the durable write and keeps/reindexes Redis state. | Queues the durable write, but the full entity can be rejected or evicted from Redis. | Write-behind persists the change. | Hot policy decides whether the entity remains cache-resident. A just-written cold row may not be visible through Redis entity reads. |
| `deleteById(id)` | Removes/tombstones Redis state and queues the durable delete. | The durable delete still belongs to the write-behind flow if the application issues it. | PostgreSQL delete is flushed by write-behind. | Future Redis reads return empty and projection payloads are removed. |
| Warm/backfill | Policy-admitted rows are hydrated into Redis. | Rejected rows stay PostgreSQL-only. | Source rows are read from PostgreSQL. | `admitOnWarm` and `hotPolicy` decide Redis admission. |

| Scenario | Configuration shape | Read outside the active data set | Write outside the active data set | Correct route design |
|---|---|---|---|---|
| E-commerce customer order timeline | Last 90 days OR `NEW/PAID/PICKING/OPEN/PENDING`, `OrderSummary` projection, `maxProjectionQueryLimit=1_000`. | A 2-year-old completed order is not returned by the Redis timeline query. `findById` can return empty if the order is not admitted. | A corrected old completed order is still written to PostgreSQL, but Redis may reject or evict the full entity. | Timeline uses `OrderSummary` projection; order archive/detail history uses an indexed PostgreSQL route such as `WHERE customer_id=? ORDER BY order_date DESC LIMIT ?`. |
| Logistics shipment tracking | Last 14 days OR `IN_TRANSIT/OUT_FOR_DELIVERY/DELAYED/EXCEPTION`, shipment timeline projection. | A delivered shipment from last month is not expected in Redis tracking lists. | A late status correction is durable in PostgreSQL; Redis admits it only if the new status/time matches policy. | Active tracking screen uses projection; delivered shipment history uses PostgreSQL archive route. |
| Reporting and audit archive | Live report jobs only, small `ReportRunSummary`, often `admitOnRead=false` for archives. | Old audit and ledger rows are intentionally not promoted by one-off reads. | New audit rows are durable; old archive rows should not pollute Redis. | Dashboard reads live summaries from Redis; audit/export reads PostgreSQL directly with report-specific indexes. |
| Support operations queue | Last 30 days OR `OPEN/PENDING/ESCALATED/SLA_BREACH`, `OpenTicketSummary` projection. | A closed ticket older than 30 days may not be returned by entity repository reads. | Reopening the ticket changes state; after write, policy can admit it back into Redis. | Queue screen reads projection; closed ticket search reads PostgreSQL history; active detail reads Redis when admitted. |
| Product catalog and inventory | `ACTIVE` products OR `IN_STOCK/LOW_STOCK` OR updated in last 7 days, availability projection. | A discontinued SKU is omitted from public catalog projection. | Admin changes to discontinued products persist, but Redis keeps them only if policy admits them. | Public category pages use projection; admin cold detail and bulk catalog export use PostgreSQL. |

BEST: design one route for the active user experience and a separate explicit route for cold history. ANTI-PATTERN: expecting a broad entity query to miss Redis, scan PostgreSQL, fill Redis, and still stay within a bounded memory budget.

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
| Active data window | `hotEntityLimit=25_000` default, route profiles up to `150_000` | Keeps Redis bounded while showing different real-world route contracts |
| Entity TTL | `0` | Entity records are not removed by TTL in this sample |
| Page TTL | `90s` default; lower for catalog and support profiles | Short-lived page cache |
| Active data policy | route-specific time/state policies | Commerce, catalog, support, logistics, reporting, and audit use different admission profiles |
| Entity query limit | `250` | Prevents accidental large entity scans |
| Projection query limit | `1000` | Allows timeline windows but blocks unbounded reads |
| Redis guardrails | warning 75%, critical 88% | Makes memory pressure visible early |

For production, also set Redis `maxmemory`, keep `maxmemory-policy=noeviction`, size the connection pools, and keep admin UI behind your gateway/auth layer.

## Expanded API Scenarios

The sample now covers several production-style route shapes. Use this table before opening Postman so each request has a clear purpose.

| Scenario group | Main endpoints | What it demonstrates |
|---|---|---|
| Commerce timeline | `/api/customers/{id}/orders`, `/api/orders/high-value`, `/api/orders/archive` | Projection-first order timeline, explicit detail fetch, and SQL archive route for full history |
| Catalog and inventory | `/api/products/active`, `/api/products/low-stock`, `/api/products/{id}/stock` | Product availability projection, state-based active set, and stock update admission |
| Support operations | `/api/tickets/open`, `/api/tickets/{id}`, `/api/tickets/{id}/status` | Open queue reads from Redis; reopened/escalated tickets re-enter the active set |
| Logistics tracking | `/api/shipments/active`, `/api/shipments/exceptions`, `/api/shipments/{id}` | Shipment summary projection plus bounded shipment-event preview |
| Reporting and audit | `/api/reports/jobs/live`, `/api/reports/audit/security`, `/api/reports/audit/archive` | Live report jobs in Redis; audit/archive reads through explicit SQL |
| Dashboards and tuning | `/api/dashboard/commerce`, `/api/dashboard/operations`, `/api/tuning/profiles` | Multi-projection dashboard reads and route-level tuning profiles |

## Postman

Import:

```text
postman/cache-database-postgresql-sample.postman_collection.json
```

The collection is grouped by scenario: platform readiness, commerce, catalog/inventory, support, logistics, reporting/audit, dashboards, and tuning profiles.

## PostgreSQL Notes

The schema is created by `src/main/resources/schema.sql`. It includes primary keys, foreign keys, and indexes for the hot routes:

- `sample_orders(customer_id, order_date DESC, order_id DESC)`
- `sample_orders(priority_score DESC, order_date DESC)`
- `sample_order_lines(order_id, line_number)`
- `sample_products(category, active_status, stock_status, updated_at DESC)`
- `sample_support_tickets(status, priority, updated_at DESC)`
- `sample_shipments(shipment_status, risk_score DESC, updated_at DESC)`
- `sample_shipments(customer_id, updated_at DESC, shipment_id DESC)`
- `sample_shipment_events(shipment_id, event_time DESC, event_id DESC)`
- `sample_report_jobs(status, updated_at DESC, report_job_id DESC)`
- `sample_audit_events(entity_name, entity_id, created_at DESC)`

The seed endpoint writes through CacheDB and waits for parent rows before writing dependent child rows. That is intentional because the database has foreign keys.

`POST /api/orders` performs one indexed SQL existence check. If the parent customer is not durable yet, the endpoint immediately returns `409` with `Retry-After: 1`; it never blocks a request thread by polling.

## Troubleshooting

If the API cannot resolve CacheDB dependencies, verify access to the package repository configured in `pom.xml`.

If the seed endpoint is slow, check `GET /actuator/health/readiness` and the admin UI write-behind section. The seed flow waits for SQL rows so foreign keys are not violated.

If timeline results are empty immediately after seed, wait a few seconds and retry. Projection refresh is asynchronous.
