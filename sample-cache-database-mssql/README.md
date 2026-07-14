# CacheDB MSSQL REST API Sample

English | [Türkçe](README.tr.md)

This is a standalone Spring Boot REST API that shows how to use CacheDB with Redis and Microsoft SQL Server. It uses the same domain and API flow as the PostgreSQL sample, but selects the MSSQL storage provider explicitly.

The sample models a commerce support system:

- Customers place many orders.
- Orders have many order lines.
- Products are read frequently by category.
- Support tickets feed a small operational dashboard.
- Customer order timelines are served from a projection/read-model instead of hydrating full aggregates.

## Product Positioning In This Sample

This sample does not present CacheDB as a transparent cache in front of SQL
Server. The API is intentionally split into active Redis routes and explicit
SQL Server routes.

| Route type | Example | Data path | Contract |
|---|---|---|---|
| Operational entity write | `POST /api/orders` | Redis first, SQL Server write-behind | The write is accepted through CacheDB and later flushed durably. Redis residency still depends on hot policy. |
| Operational list | `GET /api/customers/{id}/orders` | Redis projection: `OrderSummary` | The list reads a bounded read model, not the full order aggregate. |
| Selected detail | `GET /api/orders/{id}` | Redis entity + bounded relation preview | Works for active-set data. If the order is outside the active set, use an explicit cold-detail route. |
| Archive/history | `GET /api/orders/archive` | Direct SQL Server query | Old history, export, and audit reads must not pollute Redis by default. |
| Dashboard | `GET /api/dashboard/commerce` | Redis projection and bounded entity query | Dashboard rows are pre-shaped for the screen. |

BEST: design the active route first, define the hot policy, serve lists from
projection repositories, and keep archive/history reads as explicit SQL Server
queries.

ANTI-PATTERN: expect a broad dynamic entity query to miss Redis, scan SQL
Server, fill Redis, and still remain predictable under production memory
limits.

## Dependency Model

This project intentionally consumes CacheDB as an external Maven package:

```xml
<properties>
  <java.version>21</java.version>
  <cachedb.version>0.3.2</cachedb.version>
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
    <groupId>com.reactor.cachedb</groupId>
    <artifactId>cachedb-storage-mssql</artifactId>
    <version>${cachedb.version}</version>
  </dependency>

  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-jdbc</artifactId>
  </dependency>
  <dependency>
    <groupId>com.microsoft.sqlserver</groupId>
    <artifactId>mssql-jdbc</artifactId>
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

Users should not build the parent repository first. CacheDB `0.3.2` is published from the main repository to GitHub Packages.
The annotation dependency and `cachedb-processor` are required for generated bindings such as `OrderEntityCacheBinding`.
The `cachedb-storage-mssql` dependency is required when the application selects the MSSQL provider explicitly.

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

## 0.3.2 Verified Path

This sample is wired for CacheDB `0.3.2`. The important runtime contract is:

1. Writes go through CacheDB and are flushed to SQL Server by write-behind.
2. Existing SQL Server rows are not magically loaded into Redis at startup.
3. Routes that must be fast need either a bounded entity active set or a projection.
4. Warm/backfill uses the registered JDBC loader and then hydrates Redis/projection rows.
5. Load tests must run after seed data is durable in SQL Server and after the warm step finishes.

The sample code makes that contract explicit.

### Production contracts added in 0.3.2

- Command endpoints return `202 Accepted`; this means Redis accepted the command, not that SQL durability is complete.
- Child writes perform one indexed SQL `EXISTS` check. A non-durable parent returns `409 Conflict` with `Retry-After` instead of polling a request thread.
- Aggregate deletion is not implicit. The order endpoint rejects deletion while line rows exist; a real aggregate delete needs an explicit transactional command.
- Every entity has a route-specific admission policy. Order, catalog, support, logistics, reporting and audit data no longer share one misleading default policy.
- Monetary fields use `BigDecimal` and `DECIMAL(19,4)`. Redis ranking scores remain non-monetary `double` values.
- Relation loaders use bounded `IN (...)` batches rather than one query per parent.
- Warm/backfill is an asynchronous bounded job (`1` worker, queue capacity `8`). Submit with `POST`, then poll `/api/warm/jobs/{jobId}`.
- `/api/health/live` reports process liveness. `/api/health/ready` checks Redis, SQL Server and write-behind telemetry.
- Oversized route limits return `400 Bad Request`; they are never silently clamped.

Version `0.3.2` also keeps JDBC reads and writes bounded: registered JDBC warm
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

`registerJdbcBacked(...)` supplies the bounded SQL Server loader used by explicit warm/backfill. This sample does not enable query read-through:

```java
@Bean
EntityRepository<OrderEntity, Long> orderRepository(CacheDatabase cacheDatabase) {
    CachePolicy policy = SampleCachePolicies.orderTimelinePolicy();
    OrderEntityCacheBinding.registerJdbcBacked(cacheDatabase, policy);
    return OrderEntityCacheBinding.repository(cacheDatabase, policy);
}
```

The warm endpoint then uses a route-shaped query, not a full table scan:

```java
QuerySpec querySpec = QuerySpec.where(QueryFilter.eq("customer_id", customerId))
        .orderBy(QuerySort.desc("order_date"), QuerySort.desc("order_id"))
        .limitTo(limit);

CacheWarmResult result = cacheDatabase.warm(CacheWarmPlan.builder(OrderEntityCacheBinding.METADATA.entityName())
        .name("sample-customer-order-window-" + customerId)
        .querySpec(querySpec)
        .maxRows(limit)
        .forceImmediateProjectionRefresh(true)
        .reindexQueryIndexes(true)
        .build());
```

Run the local load gate after the app is ready:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run-load-test.ps1 `
  -RouteProfile hot-timeline `
  -Concurrency 4 `
  -DurationSeconds 10 `
  -SeedCustomers 5 `
  -OrdersPerCustomer 10 `
  -LinesPerOrder 3 `
  -WarmCustomers 5 `
  -WarmLimit 50 `
  -MaxP95Millis 1000
```

Historical local load baseline retained from `0.2.0`:

| Provider | Version | Route profile | Result |
|---|---:|---|---|
| SQL Server | `0.2.0` | `hot-timeline` | `ok=6606`, `fail=0`, `p95=11.4 ms` |

This is a sample-machine smoke gate, not a production benchmark. In staging,
increase data volume, run longer duration tests, and watch Redis memory,
projection lag, write-behind backlog, SQL Server lock waits, SQL latency, and JVM GC.

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

| Step | Endpoint | Main data path | What it demonstrates |
|---|---|---|---|
| Health | `GET /api/health/ready` | Runtime checks | Redis connectivity and write-behind health summary |
| Seed | `POST /api/demo/seed` | Redis write, SQL Server write-behind | CacheDB write path, SQL Server persistence, projection refresh |
| Customer detail | `GET /api/customers/1?orderPreview=5` | Redis entity + bounded relation preview | Entity detail with bounded relation preview |
| Timeline | `GET /api/customers/1/orders?limit=20` | Redis projection: `OrderSummary` | Customer order list without full aggregate hydration |
| Warm customer orders | `POST /api/warm/orders/customer/1?limit=100` | SQL Server read -> Redis hydrate | Explicit warm/backfill for active-set and projection routes |
| Order detail | `GET /api/orders/10001?linePreview=5` | Redis entity + bounded order lines | Explicit detail load with bounded child relation |
| High value list | `GET /api/orders/high-value?minimumAmount=500&limit=25` | Redis ranked projection: `OrderSummary` | Global sorted projection query |
| Archive orders | `GET /api/orders/archive?customerId=1&limit=20` | Direct SQL Server query | Archive read using the same `OrderSummary` response shape |
| Dashboard | `GET /api/dashboard/commerce?limit=25` | Redis projection + Redis entity query | Small dashboard from projections and ticket entity query |
| Tuning | `GET /api/tuning` | Runtime config | Active CacheDB policy and guardrail summary |

The sample is intentionally not "Redis for everything". The production rule is:

| Need | BEST path | Why |
|---|---|---|
| Create or update an operational entity | CacheDB entity repository | The write is accepted through Redis and flushed to SQL Server by write-behind |
| Render the first page of a growing list | Projection repository with `OrderSummary` | The UI reads a compact, ranked row instead of loading full order entities |
| Render a selected detail screen | Entity repository with a bounded fetch preset | Only the selected aggregate and limited child preview are loaded |
| Read old history or export data | Explicit SQL route | Archive and reporting reads should not pollute Redis or bypass SQL Server query planning |

## Copy-Paste Active-Set Route Playbook

Use this section to see the intended model end to end. The commands assume the
sample is running on `8092` and you are in the `sample-cache-database-mssql`
directory.

### Scenario 1: Existing SQL Server Rows Do Not Automatically Appear In Redis

Seed data through CacheDB first so SQL Server has durable rows:

```bash
curl.exe -X POST "http://127.0.0.1:8092/api/demo/seed?customers=20&ordersPerCustomer=40&linesPerOrder=4"
```

Now clear Redis to simulate an existing SQL Server database with an empty active
set:

```bash
docker compose exec redis redis-cli FLUSHDB
```

This Redis projection route may now return an empty list because the projection
active set is empty:

```bash
curl.exe "http://127.0.0.1:8092/api/customers/1/orders?limit=5"
```

The explicit SQL Server archive route still sees the durable rows:

```bash
curl.exe "http://127.0.0.1:8092/api/orders/archive?customerId=1&limit=5"
```

Production meaning: entity/projection reads are active-set reads. Existing SQL
Server rows need an explicit warm/backfill path before Redis routes can serve
them.

### Scenario 2: Warm The Redis Projection For A Route

Run a dry-run first. It reads SQL Server and reports how many rows would be
warmed, but it does not mutate Redis:

```bash
curl.exe -X POST "http://127.0.0.1:8092/api/warm/orders/customer/1?limit=100&dryRun=true"
```

Every warm `POST` returns `202` with a `jobId`. Do not run the target route until
the job reaches `COMPLETED`:

```powershell
$job = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8092/api/warm/orders/customer/1?limit=100&projectionOnly=true"
do {
    Start-Sleep -Milliseconds 250
    $state = Invoke-RestMethod "http://127.0.0.1:8092/api/warm/jobs/$($job.jobId)"
} while ($state.status -in @("QUEUED", "RUNNING"))
if ($state.status -ne "COMPLETED") { throw ($state.error | ConvertTo-Json -Compress) }
```

The submitted job warms only the `OrderSummary` projection for the customer timeline route:

```bash
curl.exe -X POST "http://127.0.0.1:8092/api/warm/orders/customer/1?limit=100&projectionOnly=true"
```

Now the timeline route reads Redis projection rows:

```bash
curl.exe "http://127.0.0.1:8092/api/customers/1/orders?limit=5"
```

Projection-only warm is the right shape when the screen is a list or dashboard
and does not need full `OrderEntity` payloads.

### Scenario 3: Warm Full Entity Payloads For A Detail Route

If the next screen needs selected order details, warm the full active entity
window as well:

```bash
curl.exe -X POST "http://127.0.0.1:8092/api/warm/orders/customer/1?limit=100&projectionOnly=false"
```

Then a selected detail route can read Redis entity data:

```bash
curl.exe "http://127.0.0.1:8092/api/orders/10001?linePreview=5"
```

Production rule: do not warm full entities just because a list exists. Warm full
entities only for selected detail or command routes that actually need them.

### Scenario 4: New Writes Enter Redis Immediately

Create a customer through CacheDB:

```bash
curl.exe -X POST "http://127.0.0.1:8092/api/customers" -H "Content-Type: application/json" -d '{"customerId":9001,"taxNumber":"TAX-9001","customerType":"RETAIL","segment":"VIP","status":"ACTIVE"}'
```

Create an order through CacheDB:

```bash
curl.exe -X POST "http://127.0.0.1:8092/api/orders" -H "Content-Type: application/json" -d '{"orderId":90010001,"customerId":9001,"orderAmount":725.50,"currencyCode":"USD","orderType":"EXPRESS","status":"PAID","lineCount":0}'
```

Read the Redis projection route:

```bash
curl.exe "http://127.0.0.1:8092/api/customers/9001/orders?limit=5"
```

Read the selected Redis entity detail:

```bash
curl.exe "http://127.0.0.1:8092/api/orders/90010001?linePreview=5"
```

Production meaning: CacheDB write paths populate Redis first and flush the
durable row to SQL Server through write-behind. Existing SQL Server rows use
warm; new CacheDB writes do not need a separate warm step.

### Scenario 5: Archive And Export Stay On SQL Server

Use the explicit SQL Server route for old history, export, audit, and one-off
lookups:

```bash
curl.exe "http://127.0.0.1:8092/api/orders/archive?customerId=1&beforeOrderDate=9999999999999&beforeOrderId=9999999999999&limit=20"
```

Do not implement archive by widening the Redis hot set until it contains the
whole table. That turns Redis into a second archive database.

### Copy-Paste Java Implementation Kit

The previous commands prove the route behavior. The code below is the part you
copy into a real service and adapt table/column names to your own schema.

#### 1. Make the route contract explicit

Do not let every controller invent its own page size or Redis window. Put the
contract in code:

```java
// src/main/java/com/example/orders/CustomerOrdersRouteContract.java
package com.example.orders;

public record CustomerOrdersRouteContract(
        int firstPageSize,
        int hotWindowPerCustomer,
        int maxSqlArchivePageSize,
        boolean projectionRequired
) {
    public static CustomerOrdersRouteContract timeline() {
        return new CustomerOrdersRouteContract(20, 1_000, 500, true);
    }

    public int requireTimelineLimit(int requested) {
        return requireRange("limit", requested, 1, hotWindowPerCustomer);
    }

    public int requireArchiveLimit(int requested) {
        return requireRange("limit", requested, 1, maxSqlArchivePageSize);
    }

    private int requireRange(String name, int value, int min, int max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + " must be between " + min + " and " + max);
        }
        return value;
    }
}
```

#### 2. Define the entity, projection and named query

The named query is the Redis route contract. The projection is the compact row
the UI reads. The full entity is reserved for selected detail screens.

```java
// src/main/java/com/example/orders/domain/OrderEntity.java
package com.example.orders.domain;

import com.example.orders.readmodel.OrderReadModels;
import com.reactor.cachedb.annotations.CacheColumn;
import com.reactor.cachedb.annotations.CacheEntity;
import com.reactor.cachedb.annotations.CacheId;
import com.reactor.cachedb.annotations.CacheNamedQuery;
import com.reactor.cachedb.annotations.CacheProjectionDefinition;
import com.reactor.cachedb.core.projection.EntityProjection;
import com.reactor.cachedb.core.query.QueryFilter;
import com.reactor.cachedb.core.query.QuerySort;
import com.reactor.cachedb.core.query.QuerySpec;

import java.math.BigDecimal;

@CacheEntity(table = "sample_orders", redisNamespace = "sample-orders")
public class OrderEntity {
    @CacheId(column = "order_id")
    public Long orderId;

    @CacheColumn("customer_id")
    public Long customerId;

    @CacheColumn("order_date")
    public Long orderDate;

    @CacheColumn("order_amount")
    public BigDecimal orderAmount;

    @CacheColumn("currency_code")
    public String currencyCode;

    @CacheColumn("order_type")
    public String orderType;

    @CacheColumn("status")
    public String status;

    @CacheColumn("line_count")
    public Integer lineCount;

    @CacheColumn("priority_score")
    public Double priorityScore;

    @CacheProjectionDefinition("orderSummary")
    public static EntityProjection<OrderEntity, OrderReadModels.OrderSummary, Long> orderSummaryProjection() {
        return OrderReadModels.ORDER_SUMMARY_PROJECTION;
    }

    @CacheNamedQuery("customerTimeline")
    public static QuerySpec customerTimelineQuery(long customerId, int limit) {
        return QuerySpec.where(QueryFilter.eq("customer_id", customerId))
                .orderBy(QuerySort.desc("order_date"), QuerySort.desc("order_id"))
                .limitTo(limit);
    }
}
```

```java
// src/main/java/com/example/orders/readmodel/OrderReadModels.java
package com.example.orders.readmodel;

import com.example.orders.domain.OrderEntity;
import com.reactor.cachedb.core.codec.LengthPrefixedPayloadCodec;
import com.reactor.cachedb.core.projection.EntityProjection;
import com.reactor.cachedb.core.projection.ProjectionCodec;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class OrderReadModels {
    public static final EntityProjection<OrderEntity, OrderSummary, Long> ORDER_SUMMARY_PROJECTION =
            EntityProjection.of(
                    "order-summary",
                    new ProjectionCodec<>() {
                        @Override
                        public String toRedisValue(OrderSummary projection) {
                            LinkedHashMap<String, String> values = new LinkedHashMap<>();
                            values.put("order_id", String.valueOf(projection.orderId()));
                            values.put("customer_id", String.valueOf(projection.customerId()));
                            values.put("order_date", String.valueOf(projection.orderDate()));
                            values.put("order_amount", String.valueOf(projection.orderAmount()));
                            values.put("currency_code", projection.currencyCode());
                            values.put("status", projection.status());
                            values.put("priority_score", String.valueOf(projection.priorityScore()));
                            return LengthPrefixedPayloadCodec.encode(values);
                        }

                        @Override
                        public OrderSummary fromRedisValue(String encoded) {
                            Map<String, String> values = LengthPrefixedPayloadCodec.decode(encoded);
                            return new OrderSummary(
                                    Long.valueOf(values.get("order_id")),
                                    Long.valueOf(values.get("customer_id")),
                                    Long.valueOf(values.get("order_date")),
                                    new BigDecimal(values.get("order_amount")),
                                    values.get("currency_code"),
                                    values.get("status"),
                                    Double.valueOf(values.get("priority_score"))
                            );
                        }
                    },
                    OrderSummary::orderId,
                    List.of("order_id", "customer_id", "order_date", "order_amount", "currency_code", "status", "priority_score"),
                    projection -> {
                        LinkedHashMap<String, Object> columns = new LinkedHashMap<>();
                        columns.put("order_id", projection.orderId());
                        columns.put("customer_id", projection.customerId());
                        columns.put("order_date", projection.orderDate());
                        columns.put("order_amount", projection.orderAmount());
                        columns.put("currency_code", projection.currencyCode());
                        columns.put("status", projection.status());
                        columns.put("priority_score", projection.priorityScore());
                        return columns;
                    },
                    order -> new OrderSummary(
                            order.orderId,
                            order.customerId,
                            order.orderDate,
                            order.orderAmount,
                            order.currencyCode,
                            order.status,
                            order.priorityScore
                    )
            ).rankedBy("order_date", "priority_score").asyncRefresh();

    private OrderReadModels() {
    }

    public record OrderSummary(
            Long orderId,
            Long customerId,
            Long orderDate,
            BigDecimal orderAmount,
            String currencyCode,
            String status,
            Double priorityScore
    ) {
    }
}
```

#### 3. Register entity and projection repositories

After annotation processing, `OrderEntityCacheBinding` is generated by CacheDB.
Your application code should use that generated binding instead of reflection or
string-based repository lookup.

```java
// src/main/java/com/example/orders/OrderCacheDbConfig.java
package com.example.orders;

import com.example.orders.domain.OrderEntity;
import com.example.orders.domain.OrderEntityCacheBinding;
import com.example.orders.readmodel.OrderReadModels;
import com.reactor.cachedb.core.api.EntityRepository;
import com.reactor.cachedb.core.api.ProjectionRepository;
import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.starter.CacheDatabase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OrderCacheDbConfig {
    @Bean
    EntityRepository<OrderEntity, Long> orderRepository(CacheDatabase cacheDatabase) {
        CachePolicy policy = CachePolicy.builder()
                .hotEntityLimit(100_000)
                .pageSize(100)
                .entityTtlSeconds(0)
                .pageTtlSeconds(60)
                .build();
        OrderEntityCacheBinding.register(cacheDatabase, policy);
        return OrderEntityCacheBinding.repository(cacheDatabase, policy);
    }

    @Bean
    ProjectionRepository<OrderReadModels.OrderSummary, Long> orderSummaryRepository(
            EntityRepository<OrderEntity, Long> orderRepository
    ) {
        return OrderEntityCacheBinding.orderSummary(orderRepository);
    }

    @Bean
    CustomerOrdersRouteContract customerOrdersRouteContract() {
        return CustomerOrdersRouteContract.timeline();
    }
}
```

#### 4. Write the Redis projection route

This route does not scan SQL Server when Redis is empty. It is an active-set
route. If data is missing, run warm/backfill or use the explicit SQL Server
route.

```java
// src/main/java/com/example/orders/CustomerOrdersController.java
package com.example.orders;

import com.example.orders.domain.OrderEntityCacheBinding;
import com.example.orders.readmodel.OrderReadModels;
import com.reactor.cachedb.core.api.ProjectionRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/customers")
public class CustomerOrdersController {
    private final ProjectionRepository<OrderReadModels.OrderSummary, Long> orderSummaryRepository;
    private final CustomerOrdersRouteContract routeContract;

    public CustomerOrdersController(
            ProjectionRepository<OrderReadModels.OrderSummary, Long> orderSummaryRepository,
            CustomerOrdersRouteContract routeContract
    ) {
        this.orderSummaryRepository = orderSummaryRepository;
        this.routeContract = routeContract;
    }

    @GetMapping("/{customerId}/orders")
    public List<OrderReadModels.OrderSummary> timeline(
            @PathVariable long customerId,
            @RequestParam(defaultValue = "20") int limit
    ) {
        return OrderEntityCacheBinding.customerTimeline(
                orderSummaryRepository,
                customerId,
                routeContract.requireTimelineLimit(limit)
        );
    }
}
```

#### 5. Add a warm/backfill service

This is the critical migration operation. It reads SQL Server, then hydrates Redis
without calling `save(...)`, so it does not enqueue duplicate write-behind work.
Run this operation only behind the bounded asynchronous job service shown below;
never execute a large warm operation on the HTTP request thread.

```java
// src/main/java/com/example/orders/CustomerOrdersWarmBackfillService.java
package com.example.orders;

import com.example.orders.domain.OrderEntity;
import com.reactor.cachedb.core.api.EntityRepository;
import com.reactor.cachedb.redis.RedisEntityRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

@Service
public class CustomerOrdersWarmBackfillService {
    private static final String CUSTOMER_ORDER_WINDOW_SQL = """
            SELECT order_id, customer_id, order_date, order_amount, currency_code, order_type, status, line_count, priority_score
            FROM sample_orders
            WHERE customer_id = ?
            ORDER BY order_date DESC, order_id DESC
            OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY
            """;

    private final JdbcTemplate jdbcTemplate;
    private final EntityRepository<OrderEntity, Long> orderRepository;
    private final CustomerOrdersRouteContract routeContract;

    public CustomerOrdersWarmBackfillService(
            JdbcTemplate jdbcTemplate,
            EntityRepository<OrderEntity, Long> orderRepository,
            CustomerOrdersRouteContract routeContract
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.orderRepository = orderRepository;
        this.routeContract = routeContract;
    }

    public WarmResult warm(long customerId, int requestedLimit, boolean projectionOnly, boolean dryRun) {
        int limit = routeContract.requireTimelineLimit(requestedLimit);
        List<OrderEntity> orders = jdbcTemplate.query(
                CUSTOMER_ORDER_WINDOW_SQL,
                (rs, rowNumber) -> {
                    OrderEntity order = new OrderEntity();
                    order.orderId = rs.getLong("order_id");
                    order.customerId = rs.getLong("customer_id");
                    order.orderDate = rs.getLong("order_date");
                    order.orderAmount = rs.getBigDecimal("order_amount");
                    order.currencyCode = rs.getString("currency_code");
                    order.orderType = rs.getString("order_type");
                    order.status = rs.getString("status");
                    order.lineCount = rs.getInt("line_count");
                    order.priorityScore = rs.getDouble("priority_score");
                    return order;
                },
                customerId,
                limit
        );

        if (!dryRun && !orders.isEmpty()) {
            RedisEntityRepository<OrderEntity, Long> redisRepository = redisOrderRepository();
            if (projectionOnly) {
                redisRepository.hydrateProjectionWarmBatch(orders);
            } else {
                redisRepository.hydrateWarmBatch(orders, Collections.nCopies(orders.size(), 1L), true, false);
            }
        }

        return new WarmResult(customerId, limit, orders.size(), projectionOnly, dryRun);
    }

    @SuppressWarnings("unchecked")
    private RedisEntityRepository<OrderEntity, Long> redisOrderRepository() {
        if (orderRepository instanceof RedisEntityRepository<?, ?> redisRepository) {
            return (RedisEntityRepository<OrderEntity, Long>) redisRepository;
        }
        throw new IllegalStateException("Warm/backfill requires RedisEntityRepository");
    }

    public record WarmResult(long customerId, int requestedWindow, int rowsReadFromSql, boolean projectionOnly, boolean dryRun) {
    }
}
```

```java
// src/main/java/com/example/orders/WarmBackfillController.java
package com.example.orders;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ResponseEntity;

@RestController
@RequestMapping("/api/warm")
public class WarmBackfillController {
    private final CustomerOrdersWarmBackfillService warmBackfillService;
    private final WarmJobService warmJobService;

    public WarmBackfillController(
            CustomerOrdersWarmBackfillService warmBackfillService,
            WarmJobService warmJobService
    ) {
        this.warmBackfillService = warmBackfillService;
        this.warmJobService = warmJobService;
    }

    @PostMapping("/orders/customer/{customerId}")
    public ResponseEntity<WarmJobService.WarmJob> warmCustomerOrders(
            @PathVariable long customerId,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "true") boolean projectionOnly,
            @RequestParam(defaultValue = "false") boolean dryRun
    ) {
        int safeLimit = CustomerOrdersRouteContract.timeline().requireTimelineLimit(limit);
        return ResponseEntity.accepted().body(warmJobService.submit(
                "customer-orders",
                () -> warmBackfillService.warm(customerId, safeLimit, projectionOnly, dryRun)
        ));
    }
}
```

#### 6. Keep archive/history as explicit SQL

This endpoint is deliberately not a CacheDB entity query. It is the safe path
for active-set misses, old history, export and audit screens.

```java
// src/main/java/com/example/orders/OrderArchiveController.java
package com.example.orders;

import com.example.orders.readmodel.OrderReadModels;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
public class OrderArchiveController {
    private static final String ARCHIVE_SQL = """
            SELECT order_id, customer_id, order_date, order_amount, currency_code, status, priority_score
            FROM sample_orders
            WHERE customer_id = ?
              AND (order_date < ? OR (order_date = ? AND order_id < ?))
            ORDER BY order_date DESC, order_id DESC
            OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY
            """;

    private final JdbcTemplate jdbcTemplate;
    private final CustomerOrdersRouteContract routeContract;

    public OrderArchiveController(JdbcTemplate jdbcTemplate, CustomerOrdersRouteContract routeContract) {
        this.jdbcTemplate = jdbcTemplate;
        this.routeContract = routeContract;
    }

    @GetMapping("/archive")
    public List<OrderReadModels.OrderSummary> archive(
            @RequestParam long customerId,
            @RequestParam(required = false) Long beforeOrderDate,
            @RequestParam(required = false) Long beforeOrderId,
            @RequestParam(defaultValue = "100") int limit
    ) {
        long upperBound = beforeOrderDate == null ? Long.MAX_VALUE : beforeOrderDate;
        long upperId = beforeOrderId == null ? Long.MAX_VALUE : beforeOrderId;
        int safeLimit = routeContract.requireArchiveLimit(limit);
        return jdbcTemplate.query(
                ARCHIVE_SQL,
                (rs, rowNumber) -> new OrderReadModels.OrderSummary(
                        rs.getLong("order_id"),
                        rs.getLong("customer_id"),
                        rs.getLong("order_date"),
                        rs.getBigDecimal("order_amount"),
                        rs.getString("currency_code"),
                        rs.getString("status"),
                        rs.getDouble("priority_score")
                ),
                customerId,
                upperBound,
                upperBound,
                upperId,
                safeLimit
        );
    }
}
```

Route decision summary:

| Route | Redis entity | Redis projection | SQL Server |
|---|---:|---:|---:|
| Customer order timeline | No | Yes | Only during warm/backfill |
| Selected order detail | Yes | Optional | Only explicit cold-detail route |
| Create/update order | Yes, if policy admits | Yes, if projection exists | Write-behind flush |
| Archive/export | No | No | Yes |

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
| API | `web/*Controller.java` | Validate request shape, reject invalid limits, expose bounded endpoints | Never expose unbounded list endpoints |
| Service | `SampleSeedService.java`, controller methods | Apply business flow, ordering, retry-aware behavior | Keep write and relation ordering explicit |
| CacheDB repository | `SampleRepositories.java` | Creates generated `EntityRepository` and `ProjectionRepository` beans | Treat generated bindings as the ORM surface |
| Entity mapping | `domain/*Entity.java` | Maps Java fields to SQL Server columns and Redis namespaces | Keep table, id, column, relation definitions explicit |
| Relation loading | `relation/*BatchLoader.java` | Loads child collections only when a fetch preset asks for them | Use bounded previews instead of full aggregate hydration |
| Read model | `readmodel/OrderReadModels.java` | Stores timeline/dashboard rows as compact projection payloads | Use projections for growing lists and global sorted screens |
| Durable storage | `schema.sql` | Owns primary keys, foreign keys, and route indexes | SQL Server remains the source of truth for full history |
| Runtime tuning | `SampleCacheDbTuningConfig.java`, `application.yml` | Sets hot-data policy, guardrails, MSSQL lock timeout, and write-behind batches | Tune by route, SQL Server behavior, and memory budget |

### API Layer: Bound the Request Before It Reaches the ORM

`CustomerController` does not pass arbitrary user limits into CacheDB. It rejects an out-of-contract request before repository execution:

```java
@GetMapping("/{customerId}/orders")
public List<OrderReadModels.OrderSummary> orderTimeline(
        @PathVariable long customerId,
        @RequestParam(defaultValue = "20") int limit
) {
    return OrderEntityCacheBinding.customerTimeline(
            orderSummaryRepository,
            customerId,
            ApiLimits.requireInRange("limit", limit, 1, 1_000)
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
        .ordersPreviewRepository(
                customerRepository,
                ApiLimits.requireInRange("orderPreview", orderPreview, 1, 25)
        )
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
        BigDecimal orderAmount,
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
        BigDecimal orderAmount,
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
            ApiLimits.requireInRange("limit", limit, 1, 1_000)
    );
}
```

The response shape is already the screen shape. The UI does not receive hidden full order entities.

## Query Flow: Redis vs SQL Server

This sample now shows both paths explicitly. Hot operational reads go through CacheDB/Redis. Archive reads go directly to SQL Server.

| Route | First runtime path | When SQL Server is used | Redis behavior | Why |
|---|---|---|---|---|
| `POST /api/customers` | `EntityRepository.save` | Write-behind persists the row asynchronously | Entity enters Redis if the hot policy admits it | Normal command path |
| `POST /api/orders` | `JdbcTemplate` FK readiness check, then `EntityRepository.save` | SQL Server is checked only to make sure the parent customer is durable before inserting a child row | Order is saved through Redis and queued for write-behind | Avoids FK violation while keeping Redis-first write path |
| `GET /api/customers/{id}/orders` | `ProjectionRepository<OrderSummary>` | Not used for the hot list route | Reads Redis projection payload/index; may warm missing projection rows from Redis base entity payloads | Fast customer timeline |
| `GET /api/orders/high-value` | `ProjectionRepository<OrderSummary>` | Not used for the hot list route | Reads ranked Redis projection data | Fast global sorted business list |
| `GET /api/orders/{id}` | `EntityRepository.findById` with `linePreview` fetch preset | Not used by this sample endpoint on a cache miss | Reads Redis entity payload, then relation loader queries Redis for bounded lines | Detail screen for hot orders |
| `GET /api/orders/archive` | `JdbcTemplate.query` | Direct SQL Server read | Does not mutate Redis | Cold/archive history path |
| `GET /api/products/active` | `ProjectionRepository<ProductAvailability>` | Not used by this sample endpoint | Bounded Redis projection query | Compact catalog list |
| `GET /api/tickets/open` | `EntityRepository.query` | Not used by this sample endpoint | Bounded Redis entity query | Operational queue |
| `GET /api/dashboard/commerce` | Projection query plus ticket entity query | Not used by this sample endpoint | Combines Redis projection and Redis entity query | Dashboard first paint |

The important rule: CacheDB repository reads are not a license to scan the database on every miss. In this sample, `EntityRepository.findById` and normal `query(...)` routes are Redis/hot-set routes. If you need archive or full-history reads, expose that as an explicit SQL route, as this sample does with:

```java
@GetMapping("/archive")
public List<OrderReadModels.OrderSummary> archiveFromSql(
        @RequestParam long customerId,
        @RequestParam(required = false) Long beforeOrderDate,
        @RequestParam(required = false) Long beforeOrderId,
        @RequestParam(defaultValue = "100") int limit
) {
    return jdbcTemplate.query(ARCHIVE_SQL, rowMapper, customerId, upperBound, upperBound, upperId, safeLimit);
}
```

The SQL route uses the same response model, but a different source:

```sql
SELECT order_id, customer_id, order_date, order_amount, currency_code,
       order_type, status, line_count, priority_score
FROM sample_orders
WHERE customer_id = ?
  AND (order_date < ? OR (order_date = ? AND order_id < ?))
ORDER BY order_date DESC, order_id DESC
OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY
```

This is the production pattern:

| Screen type | Use Redis/CacheDB | Use SQL Server |
|---|---|---|
| First page timeline | Yes, projection | No |
| High-value sorted list | Yes, ranked projection | No |
| Detail for selected hot order | Yes, entity detail with preview relation | Only if you build an explicit cold detail route |
| Old archive/history page | No, unless intentionally warmed | Yes, bounded SQL query |
| Export/reporting job | Usually no | Yes, batch/reporting path |
| Migration warm/backfill | Writes selected hot set to Redis | Reads source rows from SQL Server |

## Real-World Use Cases in This Sample

| Use case | Endpoint | CacheDB shape | Why this shape |
|---|---|---|---|
| Customer opens order history | `GET /api/customers/{id}/orders?limit=20` | Projection query on `OrderSummary` | Growing timeline, compact payload, stable sort |
| User opens one order | `GET /api/orders/{id}?linePreview=5` | Entity detail plus bounded relation preview | Detail needs more data, but still not all lines unless requested |
| Operations checks high-value orders | `GET /api/orders/high-value?minimumAmount=500&limit=25` | Ranked projection query | Global sorted screen should not scan full entities |
| User opens older history | `GET /api/orders/archive?customerId=1&limit=20` | Direct SQL Server query returning `OrderSummary` | Archive reads should not pollute Redis unless intentionally warmed |
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
| `writeBehindBacklogWarnThreshold` | `500` | Warning threshold for pending durable writes. | Lower for strict durability windows; raise only after measuring SQL Server throughput. |
| `writeBehindBacklogCriticalThreshold` | `2_000` | Critical backlog threshold for durable writes. | Treat as incident-level pressure: check SQL Server locks, pool saturation, batch size, and Redis memory. |
| `automaticRuntimeProfileSwitchingEnabled` | `true` | Allows CacheDB to switch runtime behavior when guardrails detect pressure. | Keep enabled for samples and production unless you have a separate operational controller. |

### Write-Behind

| Parameter | Sample value | What it means | Production guidance |
|---|---:|---|---|
| `workerThreads` | `2` | Number of background workers flushing Redis-accepted writes to SQL Server. | Increase only when SQL Server has spare connections/CPU and backlog is growing. |
| `batchSize` | `128` | Target number of queued writes grouped for a flush cycle. | Increase for throughput after measuring SQL Server lock duration and flush latency. |
| `maxFlushBatchSize` | `128` | Hard cap for one flush batch. | Keep aligned with `batchSize` until load tests show SQL Server benefits from larger batches. |
| `tableAwareBatchingEnabled` | `true` | Groups writes by table to improve SQL Server batch behavior and reduce mixed-shape flushes. | Keep enabled for most production workloads. |
| `batchFlushEnabled` | `true` | Enables batch flushing instead of one-row-at-a-time durable writes. | Keep enabled unless diagnosing provider-specific behavior. |
| `coalescingEnabled` | `true` | Combines repeated writes for the same entity before flushing where safe. | Keep enabled for update-heavy workloads. Disable only if every intermediate state must be durable. |
| `maxFlushRetries` | `5` | Retry count for transient SQL Server flush failures. | Increase only with bounded backoff and visibility. Permanent failures should move to failure handling, not retry forever. |
| `retryBackoffMillis` | `500` | Delay between flush retry attempts. | Tune with SQL Server failover behavior. Too low creates retry storms; too high increases durability lag. |

### Values Mentioned In The README But Not Set In This Class

| Parameter | Why it still appears |
|---|---|
| `lruEvictionEnabled` | Supported by `CachePolicy`; not explicitly set here, so the framework default applies. It is documented because production teams often tune count-window behavior. |
| `admitOnWrite`, `admitOnRead`, `admitOnWarm`, `evictWhenRejected` | Supported by `EntityHotPolicy`; this sample uses helper constructors with default admission behavior. They are documented because migration, archive, and warm-up flows commonly need these switches. |
| `rejectEntityQueryOverLimit`, `rejectProjectionQueryOverLimit` | Supported by read guardrails; this sample relies on default rejection behavior after setting query limits. |

## Real-World Tuning Recipes

These recipes are starting profiles, not universal defaults. Use them to decide the first `SampleCacheDbTuningConfig` values, then validate with staging data, Redis memory reports, SQL Server latency, projection lag, lock waits, and write-behind backlog.

| Scenario | Entities and read models | Suggested values | Why these values |
|---|---|---|---|
| E-commerce customer order timeline | `CustomerEntity`, `OrderEntity`, `OrderLineEntity`, `OrderSummary` projection | `hotEntityLimit=100_000`, `pageSize=100`, `entityTtlSeconds=0`, `pageTtlSeconds=60`, `compositeHotPolicy=ANY`, `timeWindow("order_date", 90 days)`, `stateWindow("status", NEW/PAID/PICKING/OPEN/PENDING)`, `maxEntityQueryLimit=250`, `maxProjectionQueryLimit=1_000`, Redis warn/critical `75/88`, `workerThreads=4`, `batchSize=256`, `maxFlushBatchSize=256` | The list screen must read `OrderSummary` from Redis projection, while selected order detail can read full entity with a bounded line preview. The 90-day window keeps recent commerce traffic hot, and active statuses keep operational orders hot even when they are older. |
| Logistics shipment tracking | `ShipmentEntity`, `ShipmentEventEntity`, `RouteStopEntity`, `ShipmentTimelineSummary` projection | `hotEntityLimit=150_000`, `pageSize=100`, `entityTtlSeconds=0`, `pageTtlSeconds=30`, `compositeHotPolicy=ANY`, `timeWindow("updated_at", 14 days)`, `stateWindow("shipment_status", IN_TRANSIT/OUT_FOR_DELIVERY/DELAYED/EXCEPTION)`, `maxEntityQueryLimit=200`, `maxProjectionQueryLimit=1_000`, Redis warn/critical `70/85`, `workerThreads=4`, `batchSize=256`, `maxFlushRetries=8`, `retryBackoffMillis=1_000` | Logistics data changes frequently and users repeatedly open the same active shipments. Keep active and exception shipments hot, keep event timelines as projections, and keep retry/backoff less aggressive to survive transient database or network pressure. |
| Reporting and audit archive | `ReportJobEntity`, `AuditEventEntity`, `LedgerEntryEntity`, `ReportRunSummary` projection | `hotEntityLimit=5_000`, `pageSize=50`, `entityTtlSeconds=0`, `pageTtlSeconds=30`, `compositeHotPolicy=ANY`, `timeWindow("created_at", 1 day)`, `stateWindow("status", QUEUED/RUNNING/FAILED)`, `maxEntityQueryLimit=100`, `maxProjectionQueryLimit=500`, Redis warn/critical `70/80`, `workerThreads=2`, `batchSize=64`, use `admitOnRead=false` if you customize archive policies | Reporting should be SQL Server-first for large scans and exports. Redis should hold only live report jobs and small run summaries; old audit and ledger history should stay in SQL Server and be read through explicit SQL/reporting routes. |
| Support operations queue | `SupportTicketEntity`, `TicketMessageEntity`, `CustomerEntity`, `OpenTicketSummary` projection | `hotEntityLimit=50_000`, `pageSize=50`, `entityTtlSeconds=0`, `pageTtlSeconds=20`, `compositeHotPolicy=ANY`, `timeWindow("updated_at", 30 days)`, `stateWindow("status", OPEN/PENDING/ESCALATED/SLA_BREACH)`, `maxEntityQueryLimit=200`, `maxProjectionQueryLimit=1_000`, Redis warn/critical `75/88`, `workerThreads=3`, `batchSize=128`, `coalescingEnabled=true` | Agents reopen the same tickets and queues many times. Keep open/escalated tickets hot, keep queue rows as compact projections, and load full messages only on explicit detail screens. |
| Product catalog and inventory availability | `ProductEntity`, `WarehouseStockEntity`, `InventoryReservationEntity`, `ProductAvailabilitySummary` projection | `hotEntityLimit=25_000`, `pageSize=100`, `entityTtlSeconds=0`, `pageTtlSeconds=15`, `compositeHotPolicy=ANY`, `stateWindow("active_status", ACTIVE)`, `stateWindow("stock_status", IN_STOCK/LOW_STOCK)`, `timeWindow("updated_at", 7 days)`, `maxEntityQueryLimit=250`, `maxProjectionQueryLimit=1_000`, Redis warn/critical `70/85`, `workerThreads=3`, `batchSize=256`, `coalescingEnabled=true` | Catalog pages need fast availability reads, but stock changes can be noisy. Active products and low-stock items stay hot, projection rows serve list/category pages, and coalescing reduces repeated stock-update flushes for the same item. |

BEST: start from the closest recipe, then run a staging warm-up and compare estimated Redis memory with actual `MEMORY USAGE` by key prefix. For SQL Server, also check lock waits before increasing `workerThreads` or `batchSize`. ANTI-PATTERN: copy the largest `hotEntityLimit` into every service and hope Redis absorbs the model.

## Outside The Active Data Set: Read And Write Behavior

CacheDB does not behave like a dynamic ORM that scans SQL Server whenever an entity is missing from Redis. Entity repository reads are bounded active-set reads. SQL Server remains the durable source of truth, but archive, export, old-history, and cold-detail screens must use explicit SQL routes or a controlled warm/backfill flow.

One important exception is `findPage(PageWindow)`: it can call an `EntityPageLoader` only if read-through is enabled and a page loader is registered. Do not treat that as a generic fallback for every entity query.

| Operation | If the row is inside the active data set | If the row is outside the active data set | SQL Server behavior | Redis/cache behavior |
|---|---|---|---|---|
| `findById(id)` | Returns the Redis entity and applies the requested fetch preset. | Returns empty; sample controllers usually map this to `404`. | The repository read does not call SQL Server. | Missing, tombstoned, or policy-rejected entities are not served. If `evictWhenRejected=true`, stale active-set entries can be removed. |
| `query(QuerySpec)` | Uses Redis indexes and Redis payloads, then returns only matching admitted rows. | Returns fewer rows or an empty list. | SQL Server is not scanned to fill the gap. | Only Redis-indexed and policy-admitted rows can appear. |
| `findPage(PageWindow)` | Returns the cached page if present. | If read-through and `EntityPageLoader` are configured, the loader may read SQL Server; otherwise it returns empty or fails depending on page-cache config. | Only the explicitly registered page loader may call SQL Server. | Loaded pages are cached only when guardrails allow the loaded size. |
| Projection query | Returns compact projection rows from Redis projection indexes. | Returns only projection rows that exist inside the projection window. | The projection query does not scan SQL Server. | Projection is the correct shape for timelines, dashboards, and top-N screens. |
| Explicit archive/reporting route | Usually not needed for normal first-paint screens. | Use this for old history, export, audit, and cold detail screens. | Reads SQL Server with bounded, indexed SQL. | Should not mutate Redis unless the route intentionally warms a hot set. |
| `save(entity)` | Queues the durable write and keeps/reindexes Redis state. | Queues the durable write, but the full entity can be rejected or evicted from Redis. | Write-behind persists the change. | Hot policy decides whether the entity remains cache-resident. A just-written cold row may not be visible through Redis entity reads. |
| `deleteById(id)` | Removes/tombstones Redis state and queues the durable delete. | The durable delete still belongs to the write-behind flow if the application issues it. | SQL Server delete is flushed by write-behind. | Future Redis reads return empty and projection payloads are removed. |
| Warm/backfill | Policy-admitted rows are hydrated into Redis. | Rejected rows stay SQL Server-only. | Source rows are read from SQL Server. | `admitOnWarm` and `hotPolicy` decide Redis admission. |

| Scenario | Configuration shape | Read outside the active data set | Write outside the active data set | Correct route design |
|---|---|---|---|---|
| E-commerce customer order timeline | Last 90 days OR `NEW/PAID/PICKING/OPEN/PENDING`, `OrderSummary` projection, `maxProjectionQueryLimit=1_000`. | A 2-year-old completed order is not returned by the Redis timeline query. `findById` can return empty if the order is not admitted. | A corrected old completed order is still written to SQL Server, but Redis may reject or evict the full entity. | Timeline uses `OrderSummary` projection; order archive/detail history uses an indexed SQL Server route such as `WHERE customer_id=? ORDER BY order_date DESC OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY`. |
| Logistics shipment tracking | Last 14 days OR `IN_TRANSIT/OUT_FOR_DELIVERY/DELAYED/EXCEPTION`, shipment timeline projection. | A delivered shipment from last month is not expected in Redis tracking lists. | A late status correction is durable in SQL Server; Redis admits it only if the new status/time matches policy. | Active tracking screen uses projection; delivered shipment history uses SQL Server archive route. |
| Reporting and audit archive | Live report jobs only, small `ReportRunSummary`, often `admitOnRead=false` for archives. | Old audit and ledger rows are intentionally not promoted by one-off reads. | New audit rows are durable; old archive rows should not pollute Redis. | Dashboard reads live summaries from Redis; audit/export reads SQL Server directly with report-specific indexes. |
| Support operations queue | Last 30 days OR `OPEN/PENDING/ESCALATED/SLA_BREACH`, `OpenTicketSummary` projection. | A closed ticket older than 30 days may not be returned by entity repository reads. | Reopening the ticket changes state; after write, policy can admit it back into Redis. | Queue screen reads projection; closed ticket search reads SQL Server history; active detail reads Redis when admitted. |
| Product catalog and inventory | `ACTIVE` products OR `IN_STOCK/LOW_STOCK` OR updated in last 7 days, availability projection. | A discontinued SKU is omitted from public catalog projection. | Admin changes to discontinued products persist, but Redis keeps them only if policy admits them. | Public category pages use projection; admin cold detail and bulk catalog export use SQL Server. |

BEST: design one route for the active user experience and a separate explicit route for cold history. ANTI-PATTERN: expecting a broad entity query to miss Redis, scan SQL Server, fill Redis, and still stay within a bounded memory budget.

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

## Expanded API Scenarios

The sample now covers several production-style route shapes. Use this table before opening Postman so each request has a clear purpose.

| Scenario group | Main endpoints | What it demonstrates |
|---|---|---|
| Commerce timeline | `/api/customers/{id}/orders`, `/api/orders/high-value`, `/api/orders/archive` | Projection-first order timeline, explicit detail fetch, and SQL Server archive route for full history |
| Catalog and inventory | `/api/products/active`, `/api/products/low-stock`, `/api/products/{id}/stock` | Product availability projection, state-based active set, and stock update admission |
| Support operations | `/api/tickets/open`, `/api/tickets/{id}`, `/api/tickets/{id}/status` | Open queue reads from Redis; reopened/escalated tickets re-enter the active set |
| Logistics tracking | `/api/shipments/active`, `/api/shipments/exceptions`, `/api/shipments/{id}` | Shipment summary projection plus bounded shipment-event preview |
| Reporting and audit | `/api/reports/jobs/live`, `/api/reports/audit/security`, `/api/reports/audit/archive` | Live report jobs in Redis; audit/archive reads through explicit SQL Server |
| Dashboards and tuning | `/api/dashboard/commerce`, `/api/dashboard/operations`, `/api/tuning/profiles` | Multi-projection dashboard reads and route-level tuning profiles |

## Postman

Import:

```text
postman/cache-database-mssql-sample.postman_collection.json
```

The collection is grouped by scenario: platform readiness, commerce, catalog/inventory, support, logistics, reporting/audit, dashboards, and tuning profiles.

## SQL Server Notes

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

SQL Server can take longer than Redis to become ready. If the app fails on first start, wait for the SQL Server healthcheck and run `mvn spring-boot:run` again.

If dependency resolution fails, verify access to the package repository configured in `pom.xml`.

If timeline results are empty immediately after seed, wait a few seconds and retry. Projection refresh is asynchronous.
