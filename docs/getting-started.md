# Getting Started

Turkish version: [../tr/docs/getting-started.md](../tr/docs/getting-started.md)

This guide takes a new project, or an existing SQL-database application, to a
working CacheDB integration. PostgreSQL is the default provider in the starter;
MSSQL is an explicit provider with its own SQL Server evidence lane.

The day-one goal is to:

- add dependencies correctly
- configure Redis and the SQL `DataSource`
- model the first entity with compile-time generated bindings
- run the first save/read/delete flow
- avoid starting relation-heavy screens with the wrong shape
- use the Migration Planner when an existing ORM system is already in place

## 1. Choose The Entry Point

| Situation | Start with |
| --- | --- |
| New Spring Boot service | `cachedb-spring-boot-starter` |
| Existing Spring Boot service with JPA | Starter plus the existing `DataSource` |
| Non-Spring Java service | `cachedb-starter` |
| Existing SQL database + ORM system | Migration Planner first |
| A few known hot endpoints | One-route pilot first |
| Relation-heavy dashboard or list | Projection/read-model design first |

BEST: prove one hot route before expanding.

ANTI-PATTERN: model every table and move every route to CacheDB at once.

## 2. Spring Boot Dependencies

Use this path for most Spring Boot applications. Version `0.10.1` is published
as an immutable package through the anonymous CacheDB Maven repository.

```xml
<properties>
    <cachedb.version>0.10.1</cachedb.version>
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

If your company parent POM does not already provide it, add the same anonymous
URL under both dependency and plugin repositories:

```xml
<repositories>
    <repository>
        <id>cachedb-public</id>
        <url>https://esasmer-dou.github.io/cache-database/maven2</url>
    </repository>
</repositories>
<pluginRepositories>
    <pluginRepository>
        <id>cachedb-public</id>
        <url>https://esasmer-dou.github.io/cache-database/maven2</url>
    </pluginRepository>
</pluginRepositories>
```

No token or CacheDB source checkout is required.

JDBC rule:

- Add `spring-boot-starter-jdbc` if the application does not already create a
  Spring `DataSource`.
- If `spring-boot-starter-data-jpa` or another starter already creates a
  `DataSource`, do not add the JDBC starter again.
- Keep the JDBC driver for your selected SQL provider as a runtime dependency.
- Configure `cachedb-processor` as an annotation processor.
- The example uses the PostgreSQL provider starter. For SQL Server, replace it
  with `cachedb-spring-boot-starter-mssql`.
- Add exactly one provider starter. `AUTO` resolves one provider and fails fast
  when the classpath is ambiguous.
- Add `cachedb-spring-boot-starter-admin` separately only when the operations UI
  is required.

## 3. Plain Java Dependencies

Use this path when you do not use Spring Boot.

```xml
<properties>
    <cachedb.version>0.10.1</cachedb.version>
</properties>

<dependencies>
    <dependency>
        <groupId>com.reactor.cachedb</groupId>
        <artifactId>cachedb-starter</artifactId>
        <version>${cachedb.version}</version>
    </dependency>
    <dependency>
        <groupId>com.reactor.cachedb</groupId>
        <artifactId>cachedb-annotations</artifactId>
        <version>${cachedb.version}</version>
    </dependency>
    <dependency>
        <groupId>redis.clients</groupId>
        <artifactId>jedis</artifactId>
        <version>5.2.0</version>
    </dependency>
    <dependency>
        <groupId>org.postgresql</groupId>
        <artifactId>postgresql</artifactId>
        <version>42.7.4</version>
    </dependency>
</dependencies>
```

In Plain Java mode, you own the `CacheDatabase` lifecycle.

## 4. Configure Connections

Spring Boot:

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
      CustomerEntity:
        hot-entity-limit: 50000
        page-size: 100
        entity-ttl-seconds: 0
        page-ttl-seconds: 60
        hot-policy:
          mode: STATE_WINDOW
          state-column: status
          state-values: [ACTIVE]
  admin:
    http-enabled: true
```

Plain Java low-level registration (Spring Boot users should inject the generated
repository instead):

```java
JedisPooled jedis = new JedisPooled("redis://127.0.0.1:6379");
DataSource dataSource = createDataSource();

try (CacheDatabase cacheDatabase = CacheDatabase.bootstrap(jedis, dataSource)
        .production()
        .keyPrefix("app-cache")
        .register(com.example.cache.GeneratedCacheModule::registerJdbcBacked)
        .start()) {
    // application code
}
```

Production note: if the admin UI is enabled, `/cachedb-admin/**` must not be
exposed directly to the public internet. Put it behind a gateway, reverse proxy,
or CacheDB token auth.

## 5. Model The First Entity

Example table:

```sql
CREATE TABLE customers (
    customer_id BIGINT PRIMARY KEY,
    tax_number VARCHAR(32) NOT NULL,
    customer_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL
);
```

Entity:

```java
@CacheEntity(table = "customers", redisNamespace = "customers")
public class CustomerEntity {
    @CacheId(column = "customer_id")
    public Long customerId;

    @CacheColumn("tax_number")
    public String taxNumber;

    @CacheColumn("customer_type")
    public String customerType;

    @CacheColumn("status")
    public String status;

    public CustomerEntity() {
    }
}
```

Important:

- Persisted fields must not be `private` or `final`.
- Table and column names should be explicit.
- Keep the entity small.
- Add relation fields only when there is a clear read requirement.

Declare one application-facing repository. Do not hand-wire entity bindings or
projection repositories in application services:

```java
@CacheRepository(entity = CustomerEntity.class)
public interface CustomerRepository extends CacheDbRepository<CustomerEntity, Long> {
    @CacheLookup(idParameter = "customerId")
    HotLookup<CustomerEntity> detail(Long customerId);

    @HotRoute(value = "active-customers", pageSize = 100, hotWindow = 50_000)
    @CacheRouteQuery(
            predicates = @CachePredicate(field = "status", constants = "ACTIVE"),
            orderBy = @CacheOrder(field = "customerId"),
            windowParameter = "window"
    )
    HotWindow<CustomerEntity> active(WindowRequest window);
}
```

The processor validates the contract and generates the Spring bean. Inject the
`CustomerRepository` interface directly.

## 6. First Save And Read

```java
CustomerEntity customer = new CustomerEntity();
customer.customerId = 1001L;
customer.taxNumber = "1234567890";
customer.customerType = "RETAIL";
customer.status = "ACTIVE";

WriteReceipt<CustomerEntity, Long> receipt = customers.save(customer);

CustomerEntity loaded = customers.detail(1001L).orElseThrow(status ->
        new IllegalStateException("Customer is not available in Redis: " + status)
);
```

Expected behavior:

- `save` writes the entity to Redis when its policy admits it.
- Durable persistence enters the selected SQL write-behind path.
- `detail` reads Redis only. `NOT_CACHED` does not prove SQL absence.
- The entity may be rejected from Redis if it does not satisfy the active-data policy.

## 7. First Delete

```java
WriteReceipt<CustomerEntity, Long> receipt = customers.deleteById(1001L);
```

Behavior:

- Redis entity and index entries are removed.
- Tombstone behavior prevents stale cached values from being served.
- The delete is sent to the selected SQL write-behind path.
- Repeating the delete should be idempotent.

## 8. First Query

Small bounded query:

```java
HotWindow<CustomerEntity> activeCustomers = customers.active(WindowRequest.first(100));
```

Rules:

- Keep routes bounded and use cursor windows.
- Use projections for large list screens.
- Treat `activeCustomers.coverage()` as part of the production contract.

For archive or active-set-external reads, declare a bounded `@SourceRoute`.
For a route that must be preloaded, derive a `@WarmRoute` from the same query.
The complete pattern is in [Declarative Repositories](declarative-repositories.md).

## 9. Start Relations Correctly

Example: customer and orders.

```java
@CacheEntity(table = "orders", redisNamespace = "orders")
public class OrderEntity {
    @CacheId(column = "order_id")
    public Long orderId;

    @CacheColumn("customer_id")
    public Long customerId;

    @CacheColumn("order_date")
    public Instant orderDate;

    @CacheColumn("order_amount")
    public BigDecimal orderAmount;
}
```

Declare typed, bounded relation metadata on the parent entity. The processor
generates the standard partitioned loader:

```java
@CacheEntity(table = "customers", redisNamespace = "customers")
public class CustomerEntity {
    @CacheRelation(
            target = OrderEntity.class,
            // OrderEntity.customerId maps to orders.customer_id.
            mappedBy = "customerId",
            kind = CacheRelation.RelationKind.ONE_TO_MANY,
            batchLoadOnly = true,
            maxRowsPerParent = 100,
            parentBatchSize = 32,
            orderBy = {"orderDate DESC", "orderId DESC"}
    )
    public List<OrderEntity> orders;
}
```

This annotation is CacheDB metadata. It is not a database foreign key. If the
database has `orders.customer_id -> customers.customer_id` but this annotation
is missing, CacheDB will not preload the relation. If the annotation-generated
loader exists but the database has no foreign key, CacheDB can still load the
relation through `mappedBy`, but durable integrity is now your responsibility.
Set `@CacheEntity.relationLoader` only when custom loading logic is required.

Put the preview limit on the repository contract and read through the injected
interface:

```java
@CacheLookup(idParameter = "customerId", relation = "orders",
        relationLimitParameter = "orderPreview", maxRelationRows = 25)
HotLookup<CustomerEntity> detail(Long customerId, int orderPreview);

CustomerEntity customer = customers.detail(customerId, 10)
        .orElseThrow(status -> mapHotLookupFailure(customerId, status));
```

This is acceptable for a small preview. Use a projection when hundreds or
thousands of orders may be shown per customer.

## 10. First Projection Decision

Example requirement:

- customer detail shows the latest 10 orders
- Redis keeps the latest 1,000 order summaries per customer
- full `OrderEntity` is fetched only after the user opens an order

Use this read model:

```text
CustomerOrderSummary
- order_id
- customer_id
- order_date
- order_amount
- currency_code
- status
```

BEST: list reads from projection, detail reads from entity.

ANTI-PATTERN: load every order and every order line for the first screen.

## 11. First Trial In An Existing SQL Database + ORM App

Use the Migration Planner before writing integration code:

1. Start the application with admin UI enabled.
2. Open `/cachedb-admin/migration-planner`.
3. Run source-database schema discovery.
4. Pick one route candidate.
5. Apply it to the form.
6. Generate the plan.
7. Generate scaffold.
8. Run dry-run warm.
9. Run staging warm.
10. Run side-by-side comparison.
11. Download the report.

Decision rule:

- Do not cut over if data does not match.
- Do not cut over if a projection-required route falls back to entity scanning.
- Do not cut over if CacheDB p95 does not meet the route target.

## 12. Verify Locally

Run at least:

```powershell
mvn -q -DskipTests package
```

Turkish documentation quality check:

```powershell
pwsh tools\ci\check-tr-docs.ps1
```

Production evidence:

```powershell
pwsh tools\ci\run-production-evidence.ps1
pwsh tools\ci\run-production-scenario-certification.ps1
```

## 13. Day-One Exit Criteria

At the end of the first day you should have:

- one entity compiling
- generated bindings produced
- Redis and SQL `DataSource` connections working
- save/read/delete tested
- first hot route selected
- projection need identified for any large list screen
- Migration Planner report captured if this is an existing system
- production notes for admin exposure, Redis HA, and route contract decisions

## Read Next

- [Concepts and Assumptions](concepts-and-assumptions.md)
- [Use Case Examples](use-case-examples.md)
- [Spring Boot Starter](spring-boot-starter.md)
- [Production Tuning Guide](production-tuning-guide.md)
- [Migration Planner](migration-planner.md)
- [Production Recipes](production-recipes.md)
