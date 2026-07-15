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

Use this path for most Spring Boot applications.

```xml
<properties>
    <cachedb.version>0.4.1</cachedb.version>
</properties>

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

JDBC rule:

- Add `spring-boot-starter-jdbc` if the application does not already create a
  Spring `DataSource`.
- If `spring-boot-starter-data-jpa` or another starter already creates a
  `DataSource`, do not add the JDBC starter again.
- Keep the JDBC driver for your selected SQL provider as a runtime dependency.
- Configure `cachedb-processor` as an annotation processor.
- The dependency examples use PostgreSQL. For MSSQL, add
  `cachedb-storage-mssql`, the Microsoft SQL Server JDBC driver, and select the
  provider with `cachedb.sql.provider=mssql` or
  `MssqlWriteBehindFlusher.factory(...)`; see [Database Provider SPI](database-provider-spi.md).

## 3. Plain Java Dependencies

Use this path when you do not use Spring Boot.

```xml
<properties>
    <cachedb.version>0.4.1</cachedb.version>
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

Plain Java:

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

Expose one generated package scope. Do not create a Spring bean for every
entity repository and projection:

```java
@Configuration(proxyBeanMethods = false)
public class CacheDbDomainConfig {
    @Bean
    GeneratedCacheModule.Scope domain(CacheDatabase cacheDatabase) {
        return GeneratedCacheModule.using(cacheDatabase);
    }
}
```

## 6. First Save And Read

```java
CustomerEntity customer = new CustomerEntity();
customer.customerId = 1001L;
customer.taxNumber = "1234567890";
customer.customerType = "RETAIL";
customer.status = "ACTIVE";

domain.customers().save(customer);

CustomerEntity loaded = domain.customers().findById(1001L).orElseThrow();
```

Expected behavior:

- `save` writes the entity to Redis when its policy admits it.
- Durable persistence enters the selected SQL write-behind path.
- `findById` reads the active entity from Redis.
- The entity may be rejected from Redis if it does not satisfy the active-data policy.

## 7. First Delete

```java
domain.customers().deleteById(1001L);
```

Behavior:

- Redis entity and index entries are removed.
- Tombstone behavior prevents stale cached values from being served.
- The delete is sent to the selected SQL write-behind path.
- Repeating the delete should be idempotent.

## 8. First Query

Small bounded query:

```java
List<CustomerEntity> activeCustomers = domain.customers().query(
        QuerySpec.where(QueryFilter.eq("status", "ACTIVE"))
                .orderBy(QuerySort.asc("customer_id"))
                .limitTo(100)
);
```

Rules:

- Keep queries bounded.
- Use projections for large list screens.
- Design sort and filter fields deliberately.

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

Declare the loader and a bounded fetch preset on the parent entity:

```java
@CacheEntity(
        table = "customers",
        redisNamespace = "customers",
        relationLoader = CustomerOrdersRelationBatchLoader.class
)
public class CustomerEntity {
    @CacheRelation(
            targetEntity = "OrderEntity",
            // OrderEntity.customerId maps to orders.customer_id.
            mappedBy = "customerId",
            kind = CacheRelation.RelationKind.ONE_TO_MANY,
            batchLoadOnly = true
    )
    public List<OrderEntity> orders;

    @CacheFetchPreset("ordersPreview")
    public static FetchPlan ordersPreviewFetchPlan(int limit) {
        return FetchPlan.of("orders").withRelationLimit("orders", Math.max(1, limit));
    }
}
```

This annotation is CacheDB metadata. It is not a database foreign key. If the
database has `orders.customer_id -> customers.customer_id` but this annotation
and a registered `RelationBatchLoader` are missing, CacheDB will not preload the
relation. If the annotation and loader exist but the database has no foreign
key, CacheDB can still load the relation through `mappedBy`, but durable
integrity is now your responsibility.

Read:

```java
CustomerEntity customer = domain.customers().fetches()
        .ordersPreview(10)
        .findById(customerId)
        .orElseThrow();
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
