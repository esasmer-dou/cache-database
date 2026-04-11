# Spring Boot Starter

This project can be used in two ways:

- as a standalone demo/runtime from `cachedb-examples`
- as a library embedded into another Java application

This document covers the second path, with emphasis on Spring Boot and same-port admin UI hosting.

For production surface selection and decision guidance, also see [Production Recipes](./production-recipes.md).
For higher-level positioning against traditional ORM usage, also see [CacheDB As An ORM Alternative](./orm-alternative.md).
For public-beta repo hygiene and release prep, also see [Public Beta Readiness](./public-beta-readiness.md) and [Release Checklist](./release-checklist.md).

## Recommended Production Start

For most teams, the recommended default is:

1. let Spring Boot auto-create `CacheDatabase`
2. let generated registrars auto-register entities
3. use `GeneratedCacheModule.using(session)...` in application code
4. drop only measured hotspots to `*CacheBinding.using(session)...` or direct repositories

That gives you the easiest startup path without giving up the project's first priority of keeping runtime overhead low.

## Fastest Paths

### Plain Java, lowest ceremony

```java
JedisPooled jedis = new JedisPooled("redis://127.0.0.1:6379");
DataSource dataSource = ...;

try (CacheDatabase cacheDatabase = CacheDatabase.bootstrap(jedis, dataSource)
        .production()
        .keyPrefix("app-cache")
        .register(com.reactor.cachedb.examples.entity.GeneratedCacheBindings::register)
        .start()) {
    // use repositories here
}
```

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

Relation and page loaders can now be declared directly on the entity:

```java
@CacheEntity(
        table = "cachedb_example_users",
        redisNamespace = "users",
        relationLoader = UserOrdersRelationBatchLoader.class,
        pageLoader = UserPageLoader.class
)
public class UserEntity {
}
```

After that, generated bindings self-wire those loaders:

```java
UserEntityCacheBinding.register(cacheDatabase);
```

This removes the old `new UserOrdersRelationBatchLoader()` / `new UserPageLoader()` ceremony from most application code. The generated binding can also constructor-inject repository dependencies for loader classes such as `DemoOrderLinesRelationBatchLoader(EntityRepository<DemoOrderLineEntity, Long>)`.

The processor now also generates a package-level registrar:

```java
CacheDatabase cacheDatabase = CacheDatabase.bootstrap(jedis, dataSource)
        .production()
        .keyPrefix("app-cache")
        .register(com.reactor.cachedb.examples.entity.GeneratedCacheBindings::register)
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

This means the UI uses the same host and same port as your Spring Boot app. There is no second public admin port.

## Minimum Dependencies

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
- If you do not provide a `JedisPooled` bean, the starter creates one from `cachedb.redis.uri`.
- The legacy alias `cachedb.redis-uri` still works.
- `cachedb.profile` accepts `default`, `development`, `production`, `benchmark`, `memory-constrained`, or `minimal-overhead`.
- generated package registrars are discovered automatically through `ServiceLoader`, so entity bindings do not need a manual `register(...)` call in the normal Spring Boot path
- set `cachedb.registration.enabled=false` only if you want to opt back into fully manual binding registration
- `cachedb.runtime.append-instance-id-to-consumer-names=true` is the safe multi-pod default; it keeps consumer groups shared but makes consumer names pod-unique
- `cachedb.runtime.leader-lease-enabled=true` turns on Redis leader leasing for cleanup/report/history loops so those singleton tasks do not fan out across every pod

## First Working Plain Java Example

```java
JedisPooled jedis = new JedisPooled("redis://127.0.0.1:6379");
DataSource dataSource = ...;

CacheDatabase cacheDatabase = CacheDatabase.bootstrap(jedis, dataSource)
        .development()
        .keyPrefix("app-cache")
        .register(com.reactor.cachedb.examples.entity.GeneratedCacheBindings::register)
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

## ORM-Style Query And Fetch Ergonomics

The public read API now supports a more natural flow without forcing `QuerySpec.builder()` everywhere:

```java
List<OrderEntity> orders = OrderEntityCacheBinding.repository(cacheDatabase)
        .withRelationLimit("orderLines", 8)
        .query(
                QuerySpec.where(QueryFilter.eq("customer_id", customerId))
                        .orderBy(QuerySort.desc("total_amount"), QuerySort.desc("line_item_count"))
                        .limitTo(24)
        );
```

Key ergonomics upgrades:

- generated `*CacheBinding` classes can register with defaults
- generated `*CacheBinding.repository(session)` no longer forces an explicit cache policy
- `EntityRepository` and `ProjectionRepository` expose shorter `query(...)` overloads
- `QuerySpec.where(...).orderBy(...).limitTo(...).fetching(...)` gives an immutable fluent path
- static entity methods annotated with `@CacheNamedQuery` generate helpers such as `UserEntityCacheBinding.activeUsers(...)`
- static entity methods annotated with `@CacheFetchPreset` generate helpers such as `DemoOrderEntityCacheBinding.orderLinesPreviewRepository(...)`
- static entity methods annotated with `@CachePagePreset` generate helpers such as `UserEntityCacheBinding.usersPage(...)`
- static entity methods annotated with `@CacheSaveCommand` / `@CacheDeleteCommand` generate helpers such as `UserEntityCacheBinding.activateUser(...)` and `UserEntityCacheBinding.deleteUser(...)`

Example:

```java
@CacheEntity(table = "cachedb_example_users", redisNamespace = "users")
public class UserEntity {

    @CacheNamedQuery("activeUsers")
    public static QuerySpec activeUsersQuery(int limit) {
        return QuerySpec.where(QueryFilter.eq("status", "ACTIVE"))
                .orderBy(QuerySort.asc("username"))
                .limitTo(limit);
    }

    @CacheFetchPreset("ordersPreview")
    public static FetchPlan ordersPreviewFetchPlan(int relationLimit) {
        return FetchPlan.of("orders").withRelationLimit("orders", relationLimit);
    }
}

List<UserEntity> activeUsers = UserEntityCacheBinding.activeUsers(session, 25);
EntityRepository<UserEntity, Long> previewRepository =
        UserEntityCacheBinding.ordersPreviewRepository(session, 8);
List<UserEntity> users = UserEntityCacheBinding.usersPage(session, 0, 25);
UserEntity activated = UserEntityCacheBinding.activateUser(session, 41L, "alice");
UserEntityCacheBinding.deleteUser(session, 41L);

var userOps = UserEntityCacheBinding.using(session);
List<UserEntity> groupedUsers = userOps.queries().activeUsers(25);
EntityRepository<UserEntity, Long> groupedPreviewRepository = userOps.fetches().ordersPreview(8);
List<UserEntity> groupedPage = userOps.pages().usersPage(0, 25);
UserEntity groupedActivated = userOps.commands().activateUser(41L, "alice");
userOps.deletes().deleteUser(41L);

var domain = com.reactor.cachedb.examples.entity.GeneratedCacheModule.using(session);
List<UserEntity> domainUsers = domain.users().queries().activeUsers(25);
```

For relation-heavy screens, prefer:

- summary query first
- explicit detail fetch second
- `withRelationLimit(...)` when you want a preview instead of the full object graph

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
    base-path: /cachedb-admin
    dashboard-enabled: true
    title: My CacheDB Admin
```

With this setup:

- your Spring Boot app still serves on `server.port`
- CacheDB admin UI is available on the same port
- public dashboard URL becomes:
  - `http://127.0.0.1:8080/cachedb-admin`

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
- a native admin servlet that serves CacheDB admin APIs through your Spring Boot server
- a Thymeleaf-backed dashboard page exposed from the same base path

This design keeps the external surface same-port without starting a second internal admin HTTP listener.

## Admin UI Through The Spring Boot Port

Behavior:

- external users access admin pages through the Boot app path
- dashboard JS resolves its API calls relative to the configured base path
- admin routes are dispatched inside the Spring Boot servlet container
- the base path root and `/dashboard` are rendered through Thymeleaf
- `/dashboard-v3` remains as a legacy redirect for older bookmarks
- `/api/*` stays on the same port and is served by the native admin servlet

Default external URLs:

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

- [list-projection-refresh-failures.ps1](/E:/ReactorRepository/cache-database/tools/ops/projection/list-projection-refresh-failures.ps1)
- [replay-projection-refresh-failure.ps1](/E:/ReactorRepository/cache-database/tools/ops/projection/replay-projection-refresh-failure.ps1)

## Production Read Pattern

For relation-heavy screens, prefer `summary query + explicit detail fetch` over large eager graph loading.

Good pattern:

1. query orders without loading `orderLines`
2. render the list from summary fields
3. load order detail on demand
4. if you still want preload, cap the relation with `FetchPlan.withRelationLimit(...)`

Example:

```java
ProjectionRepository<DemoOrderReadModelPatterns.OrderSummaryReadModel, Long> summaryRepository =
        DemoOrderEntityCacheBinding.orderSummary(orderRepository);

List<DemoOrderReadModelPatterns.OrderSummaryReadModel> summaries =
        readPatterns.findCustomerOrderSummaries(customerId, 24);

DemoOrderReadModelPatterns.OrderDetailReadModel detail =
        readPatterns.loadOrderDetail(orderId, 12);

List<DemoOrderReadModelPatterns.OrderLinePreviewReadModel> nextPage =
        readPatterns.loadRemainingOrderLines(orderId, 12, 50);
```

Limited preload example:

```java
DemoOrderEntity order = orderRepository
        .withFetchPlan(FetchPlan.of("orderLines").withRelationLimit("orderLines", 8))
        .findById(orderId)
        .orElseThrow();
```

Projection repository example:

```java
ProjectionRepository<DemoOrderReadModelPatterns.OrderLinePreviewReadModel, Long> linePreviewRepository =
        DemoOrderLineEntityCacheBinding.orderLinePreview(orderLineRepository);
```

Projection-specific indexes and refresh:

- each projection uses its own Redis namespace and query indexes
- projection reads no longer have to decode the full base entity payload when the projection cache is warm
- `EntityProjection.asyncRefresh()` moves projection maintenance out of the foreground write path
- async refresh now uses a Redis Stream-backed durable worker
- refresh events survive process restarts and can be consumed by multiple application nodes through the Redis consumer group
- the model is still eventually consistent by design
- this is not yet a full projection platform with poison-queue management, replay tooling, or dedicated admin telemetry
- `@CacheProjectionDefinition` on a static entity method lets generated bindings expose projection helpers such as `DemoOrderEntityCacheBinding.orderSummary(...)`
- `@CacheNamedQuery` on a static entity method lets the same generated binding expose query helpers for both entity repositories and projection repositories
- `@CacheFetchPreset` on a static entity method lets generated bindings expose preview/detail repository helpers without hand-written `withFetchPlan(...)` glue
- `@CachePagePreset` on a static entity method lets generated bindings expose reusable page/window helpers without hand-written `new PageWindow(...)` glue
- `@CacheSaveCommand` and `@CacheDeleteCommand` on static entity methods let generated bindings expose declarative write helpers while keeping command construction compile-time and explicit

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
