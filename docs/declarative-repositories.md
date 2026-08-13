# Declarative Repositories

Turkish version: [../tr/docs/deklaratif-repositoryler.md](../tr/docs/deklaratif-repositoryler.md)

This is the preferred application API for CacheDB. You declare the route
contract on an interface; the annotation processor validates it and generates a
reflection-free implementation plus an optional Spring bean.

`GeneratedCacheModule` remains available for compatibility and low-level work.
New application code should normally start with `@CacheRepository`.

## 1. Install The Provider Starter

Import the BOM once and choose exactly one SQL provider starter. The current
release uses the immutable `0.9.0` package published through GitHub Packages.

```xml
<properties>
    <cachedb.version>0.9.0</cachedb.version>
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
            population = HotRoute.Population.DECLARED_WARM,
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
            coverageScopeParameter = "customerId", targetParameter = "target")
    CacheWarmPlan warmTimeline(long customerId, int maxRows, CacheWarmTarget target);
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
        orders.warmTimeline(customerId, 1_000, CacheWarmTarget.PROJECTIONS_ONLY)
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

## 10. Make Every OR Predicate Explicit

Predicates in the same group are ANDed. Different groups are ORed. Because a
new group widens route membership, the processor requires an explicit opt-in:

```java
@CacheRouteQuery(
        predicates = {
                @CachePredicate(field = "orderDate", operator = CachePredicate.Operator.GTE,
                        parameter = "cutoff", group = 0),
                @CachePredicate(field = "status", operator = CachePredicate.Operator.IN,
                        constants = {"NEW", "PAID", "PICKING"}, group = 1)
        },
        explicitDisjunction = true,
        orderBy = @CacheOrder(field = "orderDate", direction = CacheOrder.Direction.DESC),
        windowParameter = "window"
)
HotWindow<OrderSummary> recentOrActive(long cutoff, WindowRequest window);
```

Without `explicitDisjunction = true`, a multi-group repository does not compile.
Do not add a second group merely to format annotations. Keep predicates in the
same group when the business rule is AND.

## 11. Let The Plan Decide What Warm Loads

Select the entity or projection payload through the generated method's typed
target. The resulting plan owns that decision; execution selects only dry-run
or apply:

```java
CacheWarmTarget target = projectionOnly
        ? CacheWarmTarget.PROJECTIONS_ONLY
        : CacheWarmTarget.ENTITY_AND_PROJECTIONS;
CacheWarmPlan plan = orders.warmTimeline(customerId, 1_000, target);

CacheWarmExecution execution = cacheDatabase.executeWarm(
        plan,
        dryRun ? CacheWarmExecutionMode.DRY_RUN : CacheWarmExecutionMode.APPLY
);
CacheWarmSummary summary = execution.summary("customer-orders");

log.info("route={} scope={} read={} submitted={} target={}",
        summary.routeName(), summary.scope(), summary.rowsReadFromSource(),
        summary.rowsSubmittedToRedis(), summary.target());
```

Do not define separate projection/entity warm methods for the same route and do
not call `warmProjections(plan)` through a second branch. A typed target creates
one generated plan contract without string flags. Dry-run does not mutate Redis.

For a command that must prove SQL durability, preserve receipt evidence instead
of reducing the result to a boolean:

```java
List<WriteReceipt<OrderEntity, Long>> receipts = orders.saveAll(batch);
cacheDatabase.awaitDurableOrThrow(
        receipts,
        Duration.ofSeconds(5),
        "order import/batch-42"
);
```

On timeout, `WriteBatchDurabilityTimeoutException` contains the receipt list,
timeout, and operation label. The rows may already be accepted in Redis; handle
the exception as an unknown SQL durability outcome, not as permission to issue
blind duplicate writes.

## 12. Reuse Window And Lookup Semantics Without Hiding The Store

`HotWindow` and `SourceWindow` implement `WindowSlice`. Shared cursor code can
therefore use `hasNext()` and `nextRequest(limit)` while hot coverage remains
available only on `HotWindow`:

```java
HotWindow<OrderSummary> page = orders.timeline(customerId, WindowRequest.first(100))
        .requireComplete();

HotWindow<OrderRow> response = page.map(OrderRow::from);
Optional<WindowRequest> next = response.nextRequest(100);
```

`HotLookup.map` also preserves `NOT_CACHED`, `TOMBSTONED`, and
`OUTSIDE_HOT_POLICY`. Mapping a payload must never erase availability state.

## 13. Inspect The Generated Route Inventory

Every generated repository publishes a reflection-free route catalog. In a
Spring Boot application the starter aggregates these catalogs automatically.
Expose the `cachedb` Actuator endpoint only on the internal operations network:

Repository beans keep their existing decapitalized default name. If two
packages use the same repository interface name, set a distinct
`@CacheRepository.springBeanName`; the processor rejects unresolved collisions.
Route-catalog bean names are package-qualified automatically.

```properties
management.endpoints.web.exposure.include=health,info,metrics,cachedb
```

The endpoint includes declared repository/route counts, route kinds, bounded
route details, HOT-route population strategies, scheduled-warm summaries, and
truncation flags. Route details are capped at 250 and scheduled-warm details at
100. Duplicate global HOT route names and a `DECLARED_WARM` route without a
generated warm route fail startup.

Aggregate Micrometer meters avoid route-name and tenant cardinality:

- `cachedb.repositories.declared`
- `cachedb.routes.declared`
- `cachedb.routes.hot.population{strategy=...}`
- `cachedb.scheduled.warm.running`
- `cachedb.scheduled.warm.failures`
- `cachedb.scheduled.warm.skipped`

The catalog proves what was compiled into the application. It does not prove
that Redis coverage is complete. Keep route coverage, parity, latency, memory,
and durability checks as separate production gates.

In integration tests, require the operational declaration explicitly:

```java
cacheDb.requireDeclaredWarmRoute("customer-order-timeline");
cacheDb.warmAndRequireCoverage(
        orders.warmTimeline(42L, 1_000, CacheWarmTarget.PROJECTIONS_ONLY),
        Duration.ofMinutes(5)
);
```

## 14. Put Repeated Route Policy At Repository Level

`@CacheRepositoryDefaults` removes repeated policy values without hiding route
behavior. The annotation processor resolves every value at compile time. An
explicit method-level value always wins.

```java
@CacheRepository(entity = OrderEntity.class)
@CacheRepositoryDefaults(
        hotPopulation = HotRoute.Population.DECLARED_WARM,
        sourceMaxRows = 500,
        sourceTimeoutSeconds = 15
)
public interface OrderRepository extends CacheDbRepository<OrderEntity, Long> {

    @HotRoute(
            value = "customer-order-timeline",
            projection = OrderSummary.class,
            hotWindow = 1_000,
            memoryBudgetBytes = CacheMemoryBudget.MIB_16,
            coverageScopeParameter = "customerId"
    )
    // @CacheRouteQuery omitted here for brevity
    HotWindow<OrderSummary> timeline(long customerId, WindowRequest window);
}
```

Use named `CacheMemoryBudget.MIB_*` constants instead of raw byte literals.
They remain Java compile-time constants and therefore work in annotations.
Repository defaults are not global configuration and do not weaken method-level
review: route shape, projection, hot window, scope, and exceptional overrides
remain visible on the method.

## 15. Preserve The Cursor At The HTTP Boundary

Do not convert a pageable window to a bare list. `CursorPage<T>` keeps the
opaque continuation token and is transport-friendly:

```java
public CursorPage<OrderSummary> timeline(long customerId, int limit, String after) {
    return orders.timeline(customerId, WindowRequest.of(limit, after)).completePage();
}
```

```json
{
  "items": [{ "orderId": 10042, "status": "PAID" }],
  "nextCursor": "opaque-token"
}
```

New cursors are bound to the generated route name, coverage scope, and ordered
sort contract. Reusing a customer-42 cursor for customer 43 or another route
throws `CursorContractMismatchException`. Legacy cursors remain readable for
compatibility, but newly emitted tokens carry the stronger contract.

## 16. Use The Framework Batch Writer For Durable Imports

`CacheDurableBatchWriter` owns batch size, pending-receipt backpressure, and SQL
durability waiting. It does not change Redis-first writes; `finish()` only
returns after every pending receipt is durable in the selected SQL provider.

```java
try (var batch = cacheDatabase.durableBatchWriter(
        "catalog import",
        128,
        1_024,
        Duration.ofSeconds(30),
        productRepository::saveAll
)) {
    sourceRows.forEach(batch::add);
}
```

Keep the operation idempotent. A durability timeout is an unknown SQL outcome,
not permission for an unguarded duplicate replay.

## 17. Prove The Complete Warm Journey In Integration Tests

The test starter can execute dry-run, apply, and coverage validation as one
production-shaped journey:

```java
CacheDbWarmRouteEvidence evidence = cacheDb.dryRunApplyAndRequireCoverage(
        orders.warmTimeline(42L, 1_000, CacheWarmTarget.PROJECTIONS_ONLY),
        Duration.ofMinutes(5)
);

assertThat(evidence.dryRun().result().submittedRows()).isZero();
assertThat(evidence.coverage().complete()).isTrue();
```

This proves that dry-run did not mutate Redis, apply used the same plan, and
the exact route/scope has fresh complete coverage. It does not replace parity,
latency, memory, or failover tests.
