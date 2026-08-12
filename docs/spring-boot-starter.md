# Spring Boot Starter

This project can be used in two ways:

- as a standalone demo/runtime from `cachedb-examples`
- as a library embedded into another Java application

This document covers the second path, with emphasis on Spring Boot and same-port admin UI hosting.

For production surface selection and decision guidance, also see [Production Recipes](./production-recipes.md).
For higher-level positioning against traditional ORM usage, also see [CacheDB As An ORM Alternative](./orm-alternative.md).
For existing SQL database + ORM migrations, also see [Migration Planner](./migration-planner.md).
The planner can now run a dry-run or real staging warm execution for the Redis
working set after it computes the recommended shape. It can also inspect the
connected source-database schema, generate binding-ready scaffolds, and run a
side-by-side source database vs CacheDB comparison before cutover.
For release prep and production boundaries, also see [Production GA Criteria](../PRODUCTION_GA_CRITERIA.md) and [Release Checklist](./release-checklist.md).

## Recommended Production Start

For most teams, the recommended default is:

1. let Spring Boot auto-create `CacheDatabase`
2. let generated registrars auto-register entities
3. declare application routes on `@CacheRepository` interfaces
4. inject those interfaces into application services
5. use low-level bindings or provider repositories only for framework and operational infrastructure

That gives you the easiest startup path without giving up the project's first priority of keeping runtime overhead low.

### Declarative Application Surface

Use configuration for per-entity policy and declarative repository interfaces
for application behavior:

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
          mode: TIME_WINDOW
          time-column: order_date
          hot-for-seconds: 7776000
```

Add this to the entity package's `package-info.java`:

```java
@com.reactor.cachedb.annotations.CacheDomain
package com.acme.orders.domain;
```

The entity processor generates the package registrar. The repository processor
generates a reflection-free implementation and Spring configuration for every
`@CacheRepository(springBean = true)` interface; `springBean` is enabled by
default.

```java
@CacheRepository(entity = OrderEntity.class)
public interface OrderRepository extends CacheDbRepository<OrderEntity, Long> {

    @CacheLookup(idParameter = "orderId", relation = "lines",
            relationLimitParameter = "linePreview", maxRelationRows = 50)
    HotLookup<OrderEntity> detail(Long orderId, int linePreview);
}

@Service
public final class OrderService {
    private final OrderRepository orders;

    public OrderService(OrderRepository orders) {
        this.orders = orders;
    }
}
```

The default generated bean name is the decapitalized repository name, such as
`orderRepository`. If two packages contain repositories with the same simple
name, make the ownership explicit:

```java
@CacheRepository(entity = SalesOrderEntity.class, springBeanName = "salesOrders")
public interface Orders extends CacheDbRepository<SalesOrderEntity, Long> {
}
```

The processor rejects duplicate generated repository bean names in the same
compilation instead of leaving the collision for Spring startup. Generated
route-catalog beans always use package-qualified names.

Spring registration is deliberately two-phase. All generated entities receive
their own policy and JDBC source first; relation/page loaders are wired only
after every child repository is known. This avoids policy leakage through
constructor-injected relation loaders. `fail-on-unknown-entity=true` turns a
misspelled policy key into a startup failure instead of silently using the
default policy.

## Fastest Paths

### Plain Java, low-level bootstrap

```java
JedisPooled jedis = new JedisPooled("redis://127.0.0.1:6379");
DataSource dataSource = ...;

try (CacheDatabase cacheDatabase = CacheDatabase.bootstrap(jedis, dataSource)
        .production()
        .keyPrefix("app-cache")
        .register(com.reactor.cachedb.examples.entity.GeneratedCacheModule::registerJdbcBacked)
        .start()) {
    OrderRepository orders = new OrderRepositoryCacheDbImplementation(cacheDatabase);
}
```

The implementation name is generated at compile time. Spring Boot users should
inject the interface instead of constructing it. The package registrar in this
plain Java example remains the low-level registration bridge.

Generated binding classes now support a lower-ceremony path too:

```java
UserEntityCacheBinding.register(cacheDatabase);
OrderEntityCacheBinding.register(cacheDatabase);

EntityRepository<UserEntity, Long> users = UserEntityCacheBinding.repository(cacheDatabase);
List<UserEntity> activeUsers = users.query(
        QueryFilter.eq("status", "ACTIVE"),
        50,
        QuerySort.asc("username")
);
```

The standard relation loader is generated from typed, bounded metadata. Custom
page loaders can still be declared directly on the entity:

```java
@CacheEntity(
        table = "cachedb_example_users",
        redisNamespace = "users",
        pageLoader = UserPageLoader.class
)
public class UserEntity {
    @CacheRelation(
            target = OrderEntity.class,
            mappedBy = "userId",
            kind = CacheRelation.RelationKind.ONE_TO_MANY,
            maxRowsPerParent = 50,
            parentBatchSize = 32,
            orderBy = {"createdAt DESC", "orderId DESC"}
    )
    public List<OrderEntity> orders;
}
```

After that, generated bindings self-wire the generated relation loader and the
custom page loader:

```java
UserEntityCacheBinding.register(cacheDatabase);
```

This removes manual relation-loader construction and `new UserPageLoader()`
ceremony from application code. Use `@CacheEntity.relationLoader` only for an
exceptional relation strategy; generated bindings can constructor-inject its
repository dependencies.

The processor now also generates a package-level registrar:

```java
CacheDatabase cacheDatabase = CacheDatabase.bootstrap(jedis, dataSource)
        .production()
        .keyPrefix("app-cache")
        .register(com.reactor.cachedb.examples.entity.GeneratedCacheModule::registerJdbcBacked)
        .start();
```

That means most applications do not need a hand-written `ExampleBindings`-style collector anymore.

This is the preferred entry point when you want:

- a small surface area
- production-oriented defaults
- an easy migration path to full config later

### Spring Boot, lowest ceremony

```yaml
spring:
  datasource:
    url: jdbc:postgresql://127.0.0.1:5432/app
    username: app
    password: app

cachedb:
  enabled: true
  profile: production
  redis:
    uri: redis://127.0.0.1:6379
  registration:
    source: jdbc
    fail-on-unknown-entity: true
    entities:
      UserEntity:
        hot-entity-limit: 50000
        page-size: 100
        hot-policy:
          mode: COUNT_WINDOW
```

This is enough to get:

- a `CacheDatabase` bean
- split foreground/background Redis pools
- same-port admin UI
- production-oriented write-behind and guardrail defaults
- automatic runtime `instanceId` resolution for worker consumer names
- Redis leader leasing for cleanup/report/history-style singleton loops

### Multi-Pod Kubernetes Defaults

The Spring Boot starter now hardens the normal multi-pod case by default:

- write-behind, DLQ replay, projection refresh, and incident-delivery DLQ workers keep shared consumer groups
- consumer names automatically gain a pod-unique `instanceId` suffix
- cleanup/report/history-style loops use a Redis leader lease so only one pod performs those singleton tasks at a time

The default `instanceId` resolution order is:

1. `cachedb.runtime.instance-id`
2. `CACHE_DB_INSTANCE_ID`
3. `HOSTNAME`
4. `POD_NAME`
5. `COMPUTERNAME`
6. generated UUID

Recommended Kubernetes baseline:

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
    leader-lease-segment: coordination:leader
```

Use `cachedb.runtime.instance-id` only when you need an explicit application-level identity. In Kubernetes, the default hostname/pod-name resolution is usually the right answer.

For same-host local multi-instance smoke, do the opposite: set explicit `cachedb.runtime.instance-id` values per process or use [../tools/ops/cluster/run-multi-instance-coordination-smoke.ps1](../tools/ops/cluster/run-multi-instance-coordination-smoke.ps1). A single workstation usually shares one `HOSTNAME`, so hostname resolution alone can hide consumer-identity issues that would not happen in real pods.

## Integration Modes

### 1. Plain Java library

Use this when you want to bootstrap `CacheDatabase` yourself.

Minimum pieces:

- `cachedb-annotations`
- `cachedb-processor`
- `cachedb-starter`
- your own `DataSource`
- your own `JedisPooled`

### 2. Spring Boot starter

Use this when you want Spring Boot to:

- create the `CacheDatabase` bean
- create `JedisPooled` from configuration
- reuse your Spring `DataSource`
- publish the CacheDB admin UI through the same Spring Boot server port
- render the admin dashboard page through Thymeleaf

The admin UI is exposed under a base path such as:

- `/cachedb-admin`
- `/cachedb-admin/migration-planner`

This means the UI uses the same host and same port as your Spring Boot app. There is no second public admin port.

## Minimum Dependencies

### Which DataSource Dependency Should I Add?

CacheDB's Spring Boot starter does not replace Spring JDBC or JPA
auto-configuration. It reuses the `DataSource` that your application already
has.

| Your application already has | Add `spring-boot-starter-jdbc`? | Why |
| --- | --- | --- |
| `spring-boot-starter-data-jpa` | No | JPA already creates the Spring `DataSource` path |
| `spring-boot-starter-jdbc` | No | The required `DataSource` path already exists |
| A manually defined `DataSource` bean | No | CacheDB can reuse that bean |
| No JDBC/JPA/DataSource setup | Yes | Spring needs a JDBC path to create a `DataSource` |

The required contract is simple: by the time CacheDB autoconfiguration runs,
there must be exactly one usable Spring `DataSource` or an explicitly selected
one.

### Plain Java

```xml
<dependencies>
    <dependency>
        <groupId>com.reactor.cachedb</groupId>
        <artifactId>cachedb-annotations</artifactId>
        <version>${cachedb.version}</version>
    </dependency>
    <dependency>
        <groupId>com.reactor.cachedb</groupId>
        <artifactId>cachedb-starter</artifactId>
        <version>${cachedb.version}</version>
    </dependency>
    <dependency>
        <groupId>redis.clients</groupId>
        <artifactId>jedis</artifactId>
    </dependency>
    <dependency>
        <groupId>org.postgresql</groupId>
        <artifactId>postgresql</artifactId>
    </dependency>
</dependencies>

<build>
    <plugins>
        <plugin>
            <artifactId>maven-compiler-plugin</artifactId>
            <configuration>
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

### Spring Boot

```xml
<dependencies>
    <dependency>
        <groupId>com.reactor.cachedb</groupId>
        <artifactId>cachedb-annotations</artifactId>
        <version>${cachedb.version}</version>
    </dependency>
    <dependency>
        <groupId>com.reactor.cachedb</groupId>
        <artifactId>cachedb-spring-boot-starter</artifactId>
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
            <artifactId>maven-compiler-plugin</artifactId>
            <configuration>
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

Notes:

- `cachedb-spring-boot-starter` does not replace your JDBC starter.
- A Spring `DataSource` is still required.
- The dependency snippets use PostgreSQL because it is the default provider. If
  you choose MSSQL, add `cachedb-storage-mssql`, use the Microsoft SQL Server
  JDBC driver, and set `cachedb.sql.provider=mssql`.
- If you do not provide a `JedisPooled` bean, the starter creates one from `cachedb.redis.uri`.
- The legacy alias `cachedb.redis-uri` still works.
- `cachedb.profile` accepts `default`, `development`, `production`, `benchmark`, `memory-constrained`, or `minimal-overhead`.
- generated package registrars are discovered automatically through `ServiceLoader`, so entity bindings do not need a manual `register(...)` call in the normal Spring Boot path
- set `cachedb.registration.enabled=false` only if you want to opt back into fully manual binding registration
- `cachedb.registration.source=metadata-only` preserves the backward-compatible default; select `jdbc` explicitly to register bounded JDBC source loaders for read-through and warm/backfill
- `cachedb.registration.entities.<EntityName>` configures each generated entity independently; `fail-on-unknown-entity=true` rejects stale or misspelled names at startup
- `cachedb.runtime.append-instance-id-to-consumer-names=true` is the safe multi-pod default; it keeps consumer groups shared but makes consumer names pod-unique
- `cachedb.runtime.leader-lease-enabled=true` turns on Redis leader leasing for cleanup/report/history loops so those singleton tasks do not fan out across every pod

### MSSQL With Spring Boot

For SQL Server, keep your normal Spring `DataSource` setup and add the provider
module plus Microsoft JDBC driver:

```xml
<dependency>
    <groupId>com.reactor.cachedb</groupId>
    <artifactId>cachedb-storage-mssql</artifactId>
    <version>${cachedb.version}</version>
</dependency>
<dependency>
    <groupId>com.microsoft.sqlserver</groupId>
    <artifactId>mssql-jdbc</artifactId>
    <scope>runtime</scope>
</dependency>
```

Then select the provider explicitly:

```yaml
spring:
  datasource:
    url: jdbc:sqlserver://sqlserver:1433;databaseName=app;encrypt=true;trustServerCertificate=false
    username: app
    password: app

cachedb:
  enabled: true
  profile: production
  sql:
    provider: mssql
    mssql:
      lock-timeout-millis: 5000
      query-timeout-seconds: 10
      transaction-isolation: serializable
      restore-lock-timeout-after-transaction: true
  redis:
    uri: redis://redis:6379
```

BEST: give CacheDB write-behind a dedicated SQL Server pool in high-write
services and size that pool from total cluster worker concurrency. If you use a
shared application pool, keep `restore-lock-timeout-after-transaction=true` so
CacheDB does not leak a changed `LOCK_TIMEOUT` into unrelated SQL code.

## First Working Plain Java Example

```java
JedisPooled jedis = new JedisPooled("redis://127.0.0.1:6379");
DataSource dataSource = ...;

CacheDatabase cacheDatabase = CacheDatabase.bootstrap(jedis, dataSource)
        .development()
        .keyPrefix("app-cache")
        .register(com.reactor.cachedb.examples.entity.GeneratedCacheModule::registerJdbcBacked)
        .start();
```

This gives you:

- Redis-first repository/session runtime
- write-behind workers
- optional standalone admin UI

Drop to `CacheDatabaseConfig.builder()` only when you really need full control over:

- schema bootstrap
- write-behind internals
- guardrails
- page cache
- projection refresh

## Declarative Query And Lookup Ergonomics

Keep query shape out of service code. The repository interface owns filtering,
sorting, limits, Redis coverage, and the source-database boundary:

```java
@CacheRepository(entity = OrderEntity.class)
public interface OrderRepository extends CacheDbRepository<OrderEntity, Long> {

    @HotRoute(value = "customer-order-timeline",
            population = HotRoute.Population.DECLARED_WARM,
            projection = OrderSummary.class,
            pageSize = 100, hotWindow = 1_000,
            memoryBudgetBytes = 16_777_216L,
            coverageScopeParameter = "customerId")
    @CacheRouteQuery(
            predicates = @CachePredicate(field = "customerId", parameter = "customerId"),
            orderBy = {
                    @CacheOrder(field = "orderDate", direction = CacheOrder.Direction.DESC),
                    @CacheOrder(field = "orderId", direction = CacheOrder.Direction.DESC)
            },
            windowParameter = "window"
    )
    HotWindow<OrderSummary> timeline(long customerId, WindowRequest window);

    @CacheLookup(idParameter = "orderId", relation = "lines",
            relationLimitParameter = "linePreview", maxRelationRows = 50)
    HotLookup<OrderEntity> detail(Long orderId, int linePreview);

    @WarmRoute(value = "warm-customer-order-timeline", from = "timeline",
            maxRows = 1_000, maxRowsParameter = "maxRows",
            coverageScopeParameter = "customerId", targetParameter = "target")
    CacheWarmPlan warmTimeline(long customerId, int maxRows, CacheWarmTarget target);
}
```

```java
List<OrderSummary> firstPage = orders.timeline(
        customerId,
        WindowRequest.first(24)
).completeItems();

OrderEntity detail = orders.detail(orderId, 8)
        .orElseThrow(status -> mapHotLookupFailure(orderId, status));
```

The processor rejects misspelled fields, incompatible parameters, unused
arguments, unsafe windows, duplicate route names, and invalid warm contracts at
compile time. For relation-heavy screens, use a summary projection first and a
bounded explicit detail lookup second. `HotLookup.NOT_CACHED` is not a durable
404 and must not trigger hidden SQL fallback.

`population` states how a Redis-only route becomes representative:

| Strategy | Use it when |
| --- | --- |
| `ON_DEMAND` | Traffic or explicit application code admits the bounded set |
| `DECLARED_WARM` | A generated `@WarmRoute` must exist before startup succeeds |
| `WRITE_FED` | CacheDB commands or a change feed continuously maintain the route |
| `EXTERNAL` | An external, monitored process owns population and coverage |

The starter rejects global HOT route-name collisions because coverage keys
would otherwise overlap. `/actuator/cachedb` publishes the bounded generated
route inventory and `hotRoutePopulation`; Micrometer publishes
`cachedb.routes.hot.population{strategy=...}` with only four stable strategy
values. Tests can assert the contract without reflection:

```java
cacheDb.requireDeclaredWarmRoute("customer-order-timeline");
cacheDb.warmAndRequireCoverage(
        orders.warmTimeline(42L, 1_000, CacheWarmTarget.PROJECTIONS_ONLY),
        Duration.ofMinutes(5)
);
```

## Minimal Overhead Mode

If you embed CacheDB as a library and do not need admin UI or admin telemetry, prefer an explicit minimal-overhead profile.

Plain Java:

```java
CacheDatabaseConfig config = CacheDatabaseProfiles.minimalOverhead();

CacheDatabase cacheDatabase = new CacheDatabase(jedis, dataSource, config);
cacheDatabase.start();
```

What this turns off:

- admin monitoring workers
- monitoring history buffers
- alert route history buffers
- performance history buffers
- incident delivery manager
- admin report worker
- standalone admin HTTP server

What stays on:

- Redis-first repositories
- write-behind
- dead-letter recovery
- schema bootstrap/validation flow

Measured baseline with Semeru JDK 21:

- benchmark script: `tools/ops/benchmark/measure-admin-monitoring-overhead.ps1`
- `disabledThreadDelta=0`
- `enabledIncidentThreadDelta=1`
- `activeMinusNoopBytes=4259840`

This is not a full application benchmark. It is a focused verification that admin-disabled mode does not create extra admin threads and that the active performance collector retains materially more heap than the no-op path.

## First Working Spring Boot Example

```java
@SpringBootApplication
public class DemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
```

`application.yml`:

```yaml
server:
  port: 8080

spring:
  datasource:
    url: jdbc:postgresql://127.0.0.1:5432/app
    username: app
    password: app

cachedb:
  enabled: true
  profile: production
  redis:
    uri: redis://127.0.0.1:6379
  admin:
    enabled: true
    http-enabled: true
    base-path: /cachedb-admin
    dashboard-enabled: true
    title: My CacheDB Admin
    request-queue-capacity: 128
    background-worker-threads: 2
    background-queue-capacity: 32
    max-request-body-bytes: 1048576
    job-status-ttl-seconds: 86400
```

With this setup:

- your Spring Boot app still serves on `server.port`
- CacheDB admin UI is available on the same port because `http-enabled` is explicit
- public dashboard URL becomes:
  - `http://127.0.0.1:8080/cachedb-admin`

Production exposure rule:

- keep `/cachedb-admin/**` behind your gateway or operations network
- terminate TLS at the gateway or reverse proxy when that is your platform standard
- use gateway authentication, or enable CacheDB token auth with `cachedb.admin.auth-enabled=true`
- keep warm/comparison workers below the capacity of their dedicated SQL connection pool
- keep request and background queues bounded; overload must return an explicit error instead of growing heap usage
- job status is stored in Redis, so another pod can read the result while the configured TTL is active

## Production Redis Topology Default

The Spring Boot starter now treats split Redis pools as the default production recipe.

Out of the box:

- foreground repository traffic uses `cachedb.redis.pool.*`
- background worker/admin/telemetry traffic uses `cachedb.redis.background.pool.*`
- background Redis URI falls back to the foreground URI unless you override it

Default pool sizes:

- foreground: `maxTotal=64`, `maxIdle=16`, `minIdle=4`
- background: `maxTotal=24`, `maxIdle=8`, `minIdle=2`
- foreground timeouts: `connection=2000ms`, `read=5000ms`, `blockingRead=15000ms`
- background timeouts: `connection=2000ms`, `read=10000ms`, `blockingRead=30000ms`

Default configuration:

```yaml
cachedb:
  redis:
    uri: redis://127.0.0.1:6379
    pool:
      max-total: 64
      max-idle: 16
      min-idle: 4
    background:
      enabled: true
      uri: redis://127.0.0.1:6379
      pool:
        max-total: 24
        max-idle: 8
        min-idle: 2
```

Why this matters:

- repository reads no longer compete as directly with write-behind/recovery/admin traffic
- p95 read latency is much more stable under mixed load
- the application keeps a cleaner separation between foreground SLA and background maintenance
- worker stream reads no longer sit on the same short read timeout as foreground calls, which reduces false `SocketTimeoutException: Read timed out` noise on blocking Redis operations

If you want the legacy single-pool behavior:

```yaml
cachedb:
  redis:
    background:
      enabled: false
```

If you provide your own foreground `JedisPooled` bean and still want split pools, also expose a bean named `cacheDbBackgroundJedisPooled`.

## Declarative Scheduled Warm Across Pods

Use `@CacheScheduledWarm` for a bounded route that must be refreshed from SQL
at a fixed delay, fixed rate, or cron schedule. Every pod registers the same
method, but a Redis lease allows only one pod to call the JDBC loader for a
cluster-wide cycle. The owner renews the lease while it runs; losing pods wait
for a bounded time and then skip duplicate work.

```java
@CacheScheduledWarm(
        name = "active-order-window",
        fixedDelayString = "${app.warm.orders.fixed-delay:PT15M}",
        lockAtMostForString = "PT2M",
        lockWaitTimeoutString = "PT20S",
        minimumIntervalString = "PT15M",
        reconcileHotSet = true
)
public CacheWarmPlan activeOrders() {
    long cutoff = Instant.now().minus(Duration.ofDays(90)).getEpochSecond();
    return domain.orders().warmPlan(
            "active-order-window",
            domain.orders().queries().activeOrderWindowQuery(cutoff, 1000),
            1000
    );
}
```

The method must take no arguments and return a bounded `CacheWarmPlan`.
Reconciliation removes Redis entity/projection data that no longer matches the
current hot policy; it never deletes or updates the SQL row. Scheduled warm is
not zero-lag CDC for direct SQL writes. See [Scheduled Warm and Hot-Set
Reconciliation](scheduled-warm.md) for the full contract and capacity model.

## Minimal Overhead In Spring Boot

If you want the Spring integration but do not want admin UI or admin monitoring overhead:

```yaml
cachedb:
  enabled: true
  redis:
    uri: redis://127.0.0.1:6379
  admin:
    enabled: false
```

With this setting:

- the CacheDB runtime still starts
- Spring Boot does not publish `/cachedb-admin/*`
- admin monitoring is disabled inside `CacheDatabase`
- performance collection switches to the no-op collector path
- admin-side history and delivery workers are not started

## What The Starter Creates

If missing, the starter creates:

- `JedisPooled`
- `CacheDatabaseConfig`
- `CacheDatabase`
- `CacheScheduledWarmRegistry`, scheduler, and Redis lease coordinator when `cachedb.scheduled-warm.enabled=true`
- a native admin servlet when `cachedb.admin.http-enabled=true`
- a Thymeleaf-backed dashboard page when `cachedb.admin.http-enabled=true`

This design keeps the external surface same-port without starting a second internal admin HTTP listener.

## Admin UI Through The Spring Boot Port

Behavior:

- external users access admin pages through the Boot app path
- dashboard JS resolves its API calls relative to the configured base path
- admin routes are dispatched inside the Spring Boot servlet container
- the base path root and `/dashboard` are rendered through Thymeleaf
- `/dashboard-v3` remains as a legacy redirect for older bookmarks
- `/api/*` stays on the same port and is served by the native admin servlet

External URLs when admin HTTP is explicitly enabled:

- dashboard: `/cachedb-admin`
- health JSON: `/cachedb-admin/api/health`
- metrics JSON: `/cachedb-admin/api/metrics`

## Customizing CacheDatabaseConfig In Spring Boot

Add a bean:

```java
@Bean
CacheDatabaseConfigCustomizer cacheDatabaseConfigCustomizer() {
    return (builder, properties) -> builder
            .relations(RelationConfig.builder()
                    .batchSize(1000)
                    .maxFetchDepth(2)
                    .failOnMissingPreloader(true)
                    .build())
            .writeBehind(WriteBehindConfig.builder()
                    .workerThreads(4)
                    .batchSize(250)
                    .build());
}
```

Use this when defaults are not enough and you still want Boot autoconfiguration.

Projection refresh example:

```java
@Bean
CacheDatabaseConfigCustomizer cacheDatabaseProjectionCustomizer() {
    return (builder, properties) -> builder
            .projectionRefresh(ProjectionRefreshConfig.builder()
                    .enabled(true)
                    .streamKey("cachedb:stream:projection-refresh")
                    .consumerGroup("cachedb-projection-refresh")
                    .batchSize(250)
                    .claimIdleMillis(45_000)
                    .build());
}
```

Use this when you want durable Redis Stream-backed projection refresh with application-local defaults instead of relying only on `-Dcachedb.config.projectionRefresh.*` flags.

Operational hooks:

- `GET /cachedb-admin/api/projection-refresh`
- `GET /cachedb-admin/api/projection-refresh/failed?limit=20`
- `POST /cachedb-admin/api/projection-refresh/replay?entryId=<dead-letter-entry-id>`

Bundled tooling:

- [list-projection-refresh-failures.ps1](../tools/ops/projection/list-projection-refresh-failures.ps1)
- [replay-projection-refresh-failure.ps1](../tools/ops/projection/replay-projection-refresh-failure.ps1)

## Production Read Pattern

For relation-heavy screens, prefer `summary query + explicit detail fetch` over large eager graph loading.

Good pattern:

1. query orders without loading `orderLines`
2. render the list from summary fields
3. load order detail on demand
4. cap preview relations with the repository's `@CacheLookup` contract

Example:

```java
List<OrderSummary> summaries = orders.customerTimeline(
        customerId,
        WindowRequest.first(24)
).items();

OrderEntity detail = orders.detail(orderId, 12)
        .orElseThrow(status -> mapHotLookupFailure(orderId, status));

SourceWindow<OrderLineSummary> nextPage = orderLines.archive(
        orderId,
        WindowRequest.after(cursor, 50)
);
```

Limited preload example:

```java
OrderEntity order = orders.detail(orderId, 8)
        .orElseThrow(status -> mapHotLookupFailure(orderId, status));
```

Projection-specific indexes and refresh:

- each projection uses its own Redis namespace and query indexes
- projection reads no longer have to decode the full base entity payload when the projection cache is warm
- `EntityProjection.asyncRefresh()` moves projection maintenance out of the foreground write path
- async refresh now uses a Redis Stream-backed durable worker
- refresh events survive process restarts and can be consumed by multiple application nodes through the Redis consumer group
- the model is still eventually consistent by design
- this is not yet a full projection platform with poison-queue management, replay tooling, or dedicated admin telemetry
- `@CacheProjectionRecord` declares the generated projection mapping; `factoryMethod` supports computed fields without reflection
- `@HotRoute` selects the projection and binds page, hot-window, coverage, and memory limits to one application method
- `@WarmRoute` reuses that exact route contract instead of duplicating query code
- `@CacheLookup` owns bounded preview/detail relations
- `@CacheCommand` makes acknowledgement, durability timeout, batch limits, and idempotency explicit
- legacy static entity query/fetch helpers remain compatibility APIs, not the preferred application surface

Example:

```java
public static final EntityProjection<DemoOrderEntity, OrderSummaryReadModel, Long> ORDER_SUMMARY_PROJECTION =
        EntityProjection.of(
                "order-summary",
                codec,
                OrderSummaryReadModel::id,
                List.of("id", "customer_id", "status", "line_item_count", "total_amount"),
                projection -> Map.of(
                        "id", projection.id(),
                        "customer_id", projection.customerId(),
                        "status", projection.status(),
                        "line_item_count", projection.lineItemCount(),
                        "total_amount", projection.totalAmount()
                ),
                order -> new OrderSummaryReadModel(...)
        ).asyncRefresh();
```

Reference example:

- [DemoOrderReadModelPatterns.java](../cachedb-examples/src/main/java/com/cachedb/examples/demo/DemoOrderReadModelPatterns.java)

Why this matters in production:

- Redis is fast at key/value access, but relation-heavy queries still pay for candidate filtering, decode, sort, and object graph materialization
- the expensive part is usually not a single `GET`; it is how much object graph you decide to hydrate
- smaller summary queries keep p95 much closer to the real repository hot path

Projection refresh tuning lives under `cachedb.config.projectionRefresh.*`.

Most important defaults:

- `enabled=true`
- `streamKey=cachedb:stream:projection-refresh`
- `consumerGroup=cachedb-projection-refresh`
- `batchSize=100`
- `recoverPendingEntries=true`
- `claimIdleMillis=30000`

See the full table in:

- [tuning-parameters.md](./tuning-parameters.md)

## Recommended Next Step

After wiring the starter, the usual next pieces are:

- register your entities and relation loaders
- define page loaders for expensive list endpoints
- verify fetch plans with the admin explain UI
- confirm the admin UI is reachable from `/cachedb-admin`

## Definition-First Distributed Jobs

Use `CacheDistributedJobHandler.Typed<A>` so the producer and every Kubernetes
pod share one route/type definition. Do not repeat route strings in the handler.

```java
@Component
final class CatalogWarmHandler
        implements CacheDistributedJobHandler.Typed<CatalogWarmCommand> {

    static final CacheDistributedJobDefinition<CatalogWarmCommand> DEFINITION =
            CacheDistributedJobDefinition.of("catalog.warm", CatalogWarmCommand.class);

    @Override
    public CacheDistributedJobDefinition<CatalogWarmCommand> definition() {
        return DEFINITION;
    }

    @Override
    public Object execute(CatalogWarmCommand command, CacheDistributedJobContext context) {
        context.checkpoint(CacheDistributedJobProgress.phase("WARMING", context.attempt())
                .withAttribute("route", command.route()));
        // Execute one bounded, resumable unit of work.
        return result;
    }
}
```

Submit through the same definition:

```java
jobs.submit(CatalogWarmHandler.DEFINITION, new CatalogWarmCommand("products"));
```

`CacheDistributedJobProgress` bounds phase, percent, message, and attribute
count/size before checkpoint serialization. Arbitrary checkpoint objects remain
available for compatibility, but structured progress is the production default.
Every pod must register the same handler set. Checkpoints make retries resumable;
they do not make a non-idempotent operation safe automatically.
