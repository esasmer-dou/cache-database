# Declarative Repositories

Turkish version: [../tr/docs/deklaratif-repositoryler.md](../tr/docs/deklaratif-repositoryler.md)

This is the preferred application API for CacheDB. You declare the route
contract on an interface; the annotation processor validates it and generates a
reflection-free implementation plus an optional Spring bean.

`GeneratedCacheModule` remains available for compatibility and low-level work.
New application code should normally start with `@CacheRepository`.

## 1. Install The Provider Starter

Import the BOM once and choose exactly one SQL provider starter. The current
release uses the immutable `0.7.1` package published through GitHub Packages.

```xml
<properties>
    <cachedb.version>0.7.1</cachedb.version>
</properties>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>com.reactor.cachedb</groupId>
            <artifactId>cachedb-bom</artifactId>
            <version>${cachedb.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>com.reactor.cachedb</groupId>
        <artifactId>cachedb-spring-boot-starter-postgres</artifactId>
    </dependency>
    <dependency>
        <groupId>com.reactor.cachedb</groupId>
        <artifactId>cachedb-annotations</artifactId>
    </dependency>
</dependencies>
```

For SQL Server, replace `cachedb-spring-boot-starter-postgres` with
`cachedb-spring-boot-starter-mssql`. Do not add both provider starters. With one
provider on the classpath, `cachedb.sql.provider=AUTO` selects it. Multiple
providers fail startup instead of choosing silently.

Keep `spring-boot-starter-jdbc` only when no other dependency already creates a
Spring `DataSource`. Keep the selected JDBC driver as a runtime dependency.

Configure `cachedb-processor` in `maven-compiler-plugin.annotationProcessorPaths`.
The sample projects contain the complete POM.

## 2. Declare One Repository Per Aggregate Or Route Group

```java
@CacheRepository(entity = OrderEntity.class)
public interface OrderRepository extends CacheDbRepository<OrderEntity, Long> {

    @CacheLookup(idParameter = "orderId", relation = "lines",
            relationLimitParameter = "linePreview", maxRelationRows = 50)
    HotLookup<OrderEntity> detail(Long orderId, int linePreview);

    @HotRoute(
            value = "customer-order-timeline",
            projection = OrderSummary.class,
            pageSize = 100,
            hotWindow = 1_000,
            memoryBudgetBytes = 16_777_216,
            coverageScopeParameter = "customerId"
    )
    @CacheRouteQuery(
            predicates = {
                    @CachePredicate(field = "customerId", parameter = "customerId"),
                    @CachePredicate(field = "status", operator = CachePredicate.Operator.NE,
                            constants = "DELETED")
            },
            orderBy = {
                    @CacheOrder(field = "orderDate", direction = CacheOrder.Direction.DESC),
                    @CacheOrder(field = "orderId", direction = CacheOrder.Direction.DESC)
            },
            windowParameter = "window"
    )
    HotWindow<OrderSummary> timeline(long customerId, WindowRequest window);

    @SourceRoute(value = "customer-order-archive", projection = OrderSummary.class,
            maxRows = 500, timeoutSeconds = 15)
    @CacheRouteQuery(
            predicates = @CachePredicate(field = "customerId", parameter = "customerId"),
            orderBy = {
                    @CacheOrder(field = "orderDate", direction = CacheOrder.Direction.DESC),
                    @CacheOrder(field = "orderId", direction = CacheOrder.Direction.DESC)
            },
            windowParameter = "window"
    )
    SourceWindow<OrderSummary> archive(long customerId, WindowRequest window);

    @WarmRoute(value = "warm-customer-order-timeline", from = "timeline",
            maxRows = 1_000, maxRowsParameter = "maxRows",
            coverageScopeParameter = "customerId", projectionsOnly = true)
    CacheWarmPlan warmTimeline(long customerId, int maxRows);
}
```

The processor rejects invalid fields, incompatible parameter types, duplicate
route names, unused parameters, unsafe limits, invalid warm scopes, and
unsupported abstract methods during compilation.

## 3. Understand The Return Types

| Return type | Store used | Contract |
| --- | --- | --- |
| `HotLookup<T>` | Redis only | `NOT_CACHED` does not mean the SQL row is absent |
| `HotWindow<T>` | Redis only | Includes route coverage and a keyset cursor |
| `SourceWindow<T>` | SQL only | Bounded durable read; it does not populate Redis implicitly |
| `WriteReceipt<T, ID>` | Redis plus write-behind | Exposes accepted version and durability state |
| `CacheWarmPlan` | No I/O until executed | Reuses the declared route query for deterministic warm |

Never translate `HotLookup.NOT_CACHED` into HTTP 404. Use an explicit SQL detail
route, warm the required scope, or return an availability response. A tombstone
can represent a known delete; a cache miss cannot prove durable absence.

```java
OrderEntity order = orders.detail(id, 20).orElseThrow(status -> switch (status) {
    case TOMBSTONED -> new OrderNotFoundException(id);
    case NOT_CACHED, OUTSIDE_HOT_POLICY -> new HotDataUnavailableException(id, status);
    case HIT -> new IllegalStateException("unreachable");
});
```

## 4. Warm Before Moving A Hot Route

```java
CacheWarmResult result = cacheDatabase.warm(
        orders.warmTimeline(customerId, 1_000)
);

HotWindow<OrderSummary> firstPage = orders.timeline(
        customerId,
        WindowRequest.first(100)
);

List<OrderSummary> rows = firstPage.completeItems();
```

Warm is a controlled preload, not a hidden fallback. A projection-only warm
stores the named projection and route coverage without hydrating full entity
payloads. Use full-entity warm only when the detail route needs those payloads.

`completeItems()` is the safe endpoint default. It throws
`HotRouteUnavailableException` when coverage is missing, stale, or incomplete,
so an incomplete Redis window cannot look like a successful empty list. Use
`items()` only when the application deliberately exposes coverage and degraded
results together.

`WindowRequest` uses keyset cursors. Hot routes accept at most 1,000 rows per
request, and every route may set a smaller page limit. Large backfills must use
bounded repeated jobs with checkpoints; they must not become one unbounded query.

For a true first-page or top-N route, expose an `int limit` and declare
`limitParameter`; generated code creates `WindowRequest.first(limit)`. Keep an
explicit `WindowRequest` only when callers need `nextCursor` pagination.

```java
@HotRoute(value = "low-stock", projection = ProductAvailability.class)
@CacheRouteQuery(limitParameter = "limit")
HotWindow<ProductAvailability> lowStock(int limit);
```

## 5. Use Projections For Lists

```java
@CacheProjectionRecord(
        source = ProductEntity.class,
        id = "productId",
        name = "product-availability",
        rankedBy = {"stock_status", "updated_at"},
        factoryMethod = "fromEntity",
        refresh = CacheProjectionRecord.Refresh.ASYNC
)
public record ProductAvailability(
        Long productId,
        String sku,
        String stockStatus,
        int availableQuantity,
        long updatedAt
) {
    public static ProductAvailability fromEntity(ProductEntity product) {
        int stock = product.stockQuantity == null ? 0 : product.stockQuantity;
        int reserved = product.reservedQuantity == null ? 0 : product.reservedQuantity;
        return new ProductAvailability(
                product.productId,
                product.sku,
                product.stockStatus,
                Math.max(0, stock - reserved),
                product.updatedAt
        );
    }
}
```

Use `factoryMethod` for computed fields. Mapping remains compile-time and
reflection-free. Relation-heavy lists, top-N screens, global sorting, and
dashboard cards should use projections rather than full aggregates.

## 6. Make Command Acknowledgement Explicit

Inherited `save`, `saveAll`, and `deleteById` return `WriteReceipt`. A repository
may also name business commands and declare the required acknowledgement.

```java
@CacheCommand(
        operation = CacheCommand.Operation.SAVE,
        acknowledgement = CacheCommand.Acknowledgement.SQL_DURABLE,
        durabilityTimeoutMillis = 2_500
)
WriteReceipt<OrderEntity, Long> persistOrder(OrderEntity entity);
```

Use `REDIS_ACCEPTED` for asynchronous APIs that can expose pending durability.
Use `SQL_DURABLE` only when the caller must wait for the durable database.
Batch commands are compile-time bounded.

Generated IDs are declared on the ID field:

```java
@CacheId(column = "job_id")
@CacheGeneratedId(value = CacheGeneratedId.Strategy.SEQUENCE,
        sequence = "report-jobs", allocationSize = 64)
public Long jobId;
```

`UUID`, `ULID`, and Redis-backed `SEQUENCE` are supported. Retry idempotency is
an API/command contract: reuse a stable caller-supplied ID or idempotency key.
CacheDB does not infer deduplication from an annotation flag.

For an optimistic partial command, update the current Redis version and return
a complete entity:

```java
WriteReceipt<OrderEntity, Long> receipt = orders.updateHot(
        orderId,
        current -> current.withStatus("PAID")
);
```

If the entity is not in Redis, `updateHot` throws
`HotUpdateUnavailableException`. It never reads SQL and silently merges a
partial payload. Use an explicit SQL-backed command workflow when the current
durable row must be loaded first.

## 7. Keep Custom SQL Explicit And Read-Only

Use `@SourceSql` only for bounded durable reads that do not fit the route DSL.

```java
@SourceSql(
        value = "SELECT order_id, customer_id, order_date, status "
                + "FROM orders WHERE customer_id = ? ORDER BY order_date DESC",
        parameters = "customerId",
        maxRows = 100,
        queryTimeoutSeconds = 10
)
SourceWindow<OrderEntity> recentSourceOrders(long customerId);
```

The processor and runtime reject statements outside the read-only contract,
including mutating CTEs, comments, multi-statements, and placeholder mismatch.
For dynamic identifiers or vendor-specific write procedures, use a reviewed
provider-owned adapter instead of concatenating SQL in a repository annotation.

## 8. Verify The Contract In Tests And Operations

```java
@SpringBootTest
@Import(CacheDbTestConfiguration.class)
class OrderRouteIT {
    @Autowired OrderRepository orders;
    @Autowired CacheDbTestProbe cacheDb;

    @Test
    void warmedTimelineIsComplete() {
        cacheDb.warm(orders.warmTimeline(42L, 1_000));
        CacheDbAssertions.requireComplete(
                orders.timeline(42L, WindowRequest.first(100))
        );
    }
}
```

Add `cachedb-spring-boot-test` in test scope. Expose the `cachedb` Actuator
endpoint internally to inspect provider identity, backlog, dead letters,
projection lag, and Redis pressure. Bind `cachedb-maven-plugin:doctor` to the
build so missing provider artifacts and ambiguous classpaths fail before deploy.

## 9. Migration From GeneratedCacheModule

1. Keep existing entities and generated bindings.
2. Add one `@CacheRepository` interface for a real application route.
3. Move Redis-only query code to `@HotRoute`.
4. Move archive/history reads to `@SourceRoute` or reviewed `@SourceSql`.
5. Derive a `@WarmRoute` from every route that requires preloaded coverage.
6. Inject the generated repository into the application service.
7. Keep `GeneratedCacheModule` only for compatibility or lower-level jobs.
8. Cut over after parity, coverage, latency, memory, and rollback evidence pass.

Composite primary keys are intentionally not supported by the repository API.
Use a stable surrogate ID and model the business key as validated, indexed
fields. Do not concatenate key parts into an undocumented string at call sites.
