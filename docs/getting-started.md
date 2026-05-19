# Getting Started

This guide takes you from a new project or an existing PostgreSQL app to a
working CacheDB integration.

If you are already running a production ORM route, do not start by rewriting
everything. Start by discovering one route, warming it in staging, and comparing
PostgreSQL vs CacheDB before cutover.

## 1. Pick The Right Entry Point

| Your situation | Use | Notes |
| --- | --- | --- |
| New Spring Boot app | `cachedb-spring-boot-starter` | Recommended default for most teams |
| Existing Spring Boot app with JPA | `cachedb-spring-boot-starter` plus existing `DataSource` | Do not duplicate JDBC auto-config if JPA already creates the `DataSource` |
| Plain Java service | `cachedb-starter` | You own bootstrap and lifecycle |
| Existing PostgreSQL + ORM app | Migration Planner first | Discover schema, plan hot routes, warm Redis, compare results |
| Relation-heavy screen | Projection/read-model | Avoid loading full aggregates for the first page |

## 2. Add Dependencies

### Spring Boot

Use this when Spring Boot should create the `CacheDatabase` bean and publish the
admin UI on the same application port.

```xml
<properties>
    <cachedb.version>0.1.0-beta.2</cachedb.version>
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

Dependency rule:

- add `spring-boot-starter-jdbc` if your app does not already create a Spring `DataSource`
- skip it if `spring-boot-starter-data-jpa` or another starter already creates the `DataSource`
- always keep `cachedb-annotations` and the `cachedb-processor` annotation processor

### Plain Java

Use this when you want direct bootstrap control.

```xml
<properties>
    <cachedb.version>0.1.0-beta.2</cachedb.version>
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

## 3. Configure Redis And PostgreSQL

### Spring Boot `application.yml`

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

### Plain Java Bootstrap

```java
JedisPooled jedis = new JedisPooled("redis://127.0.0.1:6379");
DataSource dataSource = ...;

try (CacheDatabase cacheDatabase = CacheDatabase.bootstrap(jedis, dataSource)
        .production()
        .keyPrefix("app-cache")
        .register(com.reactor.cachedb.examples.entity.GeneratedCacheBindings::register)
        .start()) {
    // application code
}
```

## 4. Model Your First Entity

Start with one entity that is actually hot in the application. Do not start by
mapping the whole database.

```java
@CacheEntity(table = "customers", redisNamespace = "customers")
public class CustomerEntity {
    private Long customerId;
    private String taxNumber;
    private String customerType;
}
```

Then compile the project. The annotation processor creates generated binding
classes that your application can use without runtime reflection.

## 5. Use The Recommended API First

The default application surface is:

```java
var domain = GeneratedCacheModule.using(session);
```

Use this for normal service code because it keeps onboarding simple while still
staying close to the low-overhead repository path.

Drop lower only when profiling justifies it:

- `GeneratedCacheModule.using(session)...` for normal business code
- `*CacheBinding.using(session)...` for measured hot endpoints
- direct repository for workers, replay, repair, or proven hotspots

## 6. Handle Relation-Heavy Screens Deliberately

Example: a customer has many orders and the UI needs the latest 1,000 orders by
`order_date DESC`.

Use this design:

1. keep `CustomerEntity` as the root
2. keep the full order history durable in PostgreSQL
3. keep only the bounded hot order window in Redis
4. use an order summary projection for the list
5. fetch full order detail only when the user opens one order

Avoid this design:

- loading the full customer aggregate for every list render
- loading all order lines just to show a list row
- sorting a large entity payload in memory after the first page is requested

## 7. Existing PostgreSQL + ORM Migration Flow

If you already have tables and an ORM, start in the admin UI:

1. open `/cachedb-admin/migration-planner`
2. click PostgreSQL schema discovery
3. choose one suggested route from the discovered root/child candidates
4. apply it to the form
5. generate the plan
6. generate scaffold if you need entity/projection skeletons
7. run dry-run warm and inspect the SQL
8. run real staging warm
9. run side-by-side comparison
10. download the migration report

For 100% migration coverage, repeat this for every production route. The planner
models one route at a time on purpose; full-system coverage comes from a route
inventory, not from one global button.

## 8. Add A Root `.gitignore`

This repository ships a ready-to-use root [.gitignore](../.gitignore) that
covers:

- Maven and Java outputs
- module `target/` directories
- IDE files
- local logs and temp output
- `tools/tmp` evidence files
- local secret files

Use the same baseline in application repositories that embed CacheDB if you do
not already have an equivalent Java/Maven ignore policy.

## 9. Verify Locally

Run at least:

```powershell
mvn -q -DskipTests package
```

For the demo application:

```powershell
./tools/ops/demo/run-spring-boot-load-demo.ps1
```

Then open:

- demo load UI: `http://127.0.0.1:8090/demo-load`
- admin dashboard: `http://127.0.0.1:8090/cachedb-admin`
- migration planner: `http://127.0.0.1:8090/cachedb-admin/migration-planner`

## 10. Read Next

- [Spring Boot Starter](./spring-boot-starter.md)
- [Migration Planner](./migration-planner.md)
- [Production Recipes](./production-recipes.md)
- [Tuning Parameters](./tuning-parameters.md)
- [ORM Alternative](./orm-alternative.md)
