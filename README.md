# cache-database

Turkish version: [tr/README.md](tr/README.md)

`cache-database` is a Redis-first Java persistence library for teams that want
low runtime overhead without giving up an ORM-like developer experience.

It is designed around one clear rule: keep the hot read/write path explicit,
bounded, and measurable.

## What You Get

- Redis-first reads and writes for hot application routes.
- PostgreSQL durability through async write-behind.
- Compile-time generated metadata instead of runtime reflection.
- ORM-like generated APIs for normal service code.
- Explicit relation loading, projections, and read models for expensive screens.
- Escape hatches to lower-level repositories when profiling proves a hotspot.
- Same-port Spring Boot admin UI with migration planning, warm-up, and comparison flows.

## Current Status

CacheDB is suitable for public beta evaluation and staging pilots. It is not
positioned as a no-caveats GA release yet.

Use it with production discipline:

- Redis must be treated as a real production dependency.
- Relation-heavy list screens should use projection/read-model design.
- Global sorted or ranked business screens should use ranked projections.
- Cutover from an existing ORM should be rehearsed with warm-up and side-by-side comparison first.

## Choose Your Starting Path

| Situation | Start Here | Why |
| --- | --- | --- |
| New Spring Boot service | `cachedb-spring-boot-starter` | Fastest setup, same-port admin UI, production defaults |
| Plain Java service | `cachedb-starter` | Full bootstrap control without Spring Boot |
| Existing PostgreSQL + ORM app | Admin UI Migration Planner | Discover schema, choose hot routes, warm Redis, compare results |
| Relation-heavy list screen | Projection/read-model | Avoid hydrating full aggregates on first paint |
| One proven latency hotspot | `*CacheBinding` or direct repository | Lower ceremony and tighter control only where measured |
| Internal worker/replay path | Direct repository | Predictable, explicit, low-allocation operational code |

## Install In 5 Minutes

### Maven: Spring Boot

Use this when your application already uses Spring Boot and has or can create a
Spring `DataSource`.

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

If your app already brings a `DataSource` through `spring-boot-starter-data-jpa`
or another starter, do not add `spring-boot-starter-jdbc` again just for
CacheDB. CacheDB needs a `DataSource`; it does not require duplicate JDBC
auto-configuration.

### Maven: Plain Java

Use this when you want to bootstrap `CacheDatabase` yourself.

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

### Minimal Spring Boot Configuration

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

After startup, the admin UI is available on the same application port:

- dashboard: `/cachedb-admin`
- migration planner: `/cachedb-admin/migration-planner`
- health API: `/cachedb-admin/api/health`

## Day-One Implementation Flow

1. Add the Maven dependencies.
2. Configure PostgreSQL and Redis.
3. Add `@CacheEntity` to your first hot entity.
4. Let the annotation processor generate bindings.
5. Start application code with `GeneratedCacheModule.using(session)...`.
6. Add projections only for list screens that should not hydrate full aggregates.
7. Use `withRelationLimit(...)` for preview rows such as "latest 8 order lines".
8. Move only measured hotspots to direct repository usage.

## Common Use Cases

### Use Case 1: Customer Has Many Orders

If the user opens a customer detail page and wants the latest 1,000 orders by
date, do not load the full customer aggregate with every order line.

Use this shape:

- `CustomerEntity` stays the root entity.
- `OrderEntity` stays durable in PostgreSQL and hot in Redis only for the needed window.
- A customer-order summary projection stores the list fields needed by the screen.
- The list query sorts by `order_date DESC, order_id DESC`.
- The full order detail is fetched only when the user opens one order.

Result: first paint stays bounded even as the customer history keeps growing.

### Use Case 2: Dashboard Shows Top Business Rows

If the screen is globally sorted by business priority, revenue, risk, or a
compound score, use a ranked projection.

Use this shape:

- compute a stable `rank_score` or equivalent projection field
- index/query the projection by that ranking field
- keep the first page/window in Redis
- avoid scanning wide entity payloads only to sort them later

### Use Case 3: Existing ORM Route Migration

If your app already runs on PostgreSQL and another ORM, do not migrate blindly.

Use the Migration Planner:

1. Open `/cachedb-admin/migration-planner`.
2. Discover the PostgreSQL schema.
3. Pick a suggested root/child route.
4. Generate the recommended entity/projection scaffold.
5. Run dry-run warm to inspect SQL without changing Redis.
6. Run staging warm to fill the Redis hot set.
7. Run side-by-side comparison.
8. Cut over only if data parity, ordering, and latency are acceptable.

For full-system migration, repeat this route by route until every production
screen and API is classified as one of:

- generated CRUD route
- projection/read-model route
- ranked projection route
- direct repository/worker route
- intentionally left on PostgreSQL cold path

## Why CacheDB

Choose CacheDB when:

- low-latency reads matter
- Redis is already a real production dependency
- you want explicit control over read-model shape
- relation fan-out can grow over time
- you want generated ergonomics without runtime reflection
- you need a staged path from PostgreSQL/ORM routes into Redis-first hot paths

## Why Not CacheDB

Stay with a traditional JPA/Hibernate-style stack when:

- your application is mainly SQL join and reporting driven
- implicit ORM behavior is a product requirement
- the team does not want to own projection/read-model design
- Redis is not part of the intended production runtime

This tradeoff is intentional. CacheDB optimizes for explicit control,
bounded hot paths, and predictable runtime overhead.

## Quick Comparison

| Topic | CacheDB | Traditional ORM |
| --- | --- | --- |
| Primary read path | Redis-first | Database-first |
| Durability | PostgreSQL through write-behind | Database transaction path |
| Metadata | Compile-time generated | Usually runtime reflection and ORM metadata |
| Relation model | Explicit fetch plans, loaders, projections | Mostly implicit lazy/eager behavior |
| Hot list screens | Projection/read-model first | Often entity graph first |
| Best fit | Low-latency services, Redis-centric systems | SQL-centric relational applications |

## Measured Evidence

The claim is not that ergonomics are free. The practical claim is that generated
ergonomics stay in the same low-overhead band as the minimal repository path,
while the larger production cost usually comes from query shape, relation
hydration, Redis contention, and write-behind pressure.

Latest local recipe benchmark snapshot:

| Surface | Avg ns | p95 ns | Read it as |
| --- | ---: | ---: | --- |
| Generated entity binding | 6005 | 13400 | Fastest average in this local run |
| JPA-style domain module | 8059 | 20300 | Grouped ergonomic surface with modest wrapper cost |
| Minimal repository | 15075 | 9600 | Lowest p95 in this local run |

![Repository recipe benchmark](docs/assets/repository-recipe-benchmark.svg)

Re-run the report with:

```powershell
mvn -q -f cachedb-production-tests/pom.xml exec:java `
  "-Dexec.mainClass=com.reactor.cachedb.prodtest.scenario.RepositoryRecipeBenchmarkMain"
```

## Production Recipe Ladder

![Production recipe ladder](docs/assets/production-recipe-ladder.svg)

Rule of thumb:

1. Start with `GeneratedCacheModule.using(session)...`.
2. Move hot endpoints to `*CacheBinding.using(session)...` only when measured.
3. Drop proven hotspots, replay paths, or workers to direct repositories.
4. Use projection/read-models for relation-heavy and globally sorted screens.

## Read Next

- [Getting Started](docs/getting-started.md)
- [Spring Boot Starter](docs/spring-boot-starter.md)
- [Migration Planner](docs/migration-planner.md)
- [Production Recipes](docs/production-recipes.md)
- [ORM Alternative Guide](docs/orm-alternative.md)
- [Tuning Parameters](docs/tuning-parameters.md)
- [Production Tests](cachedb-production-tests/README.md)
- [Examples](cachedb-examples/README.md)
- [Architecture](docs/architecture.md)
- [Public Beta Readiness](docs/public-beta-readiness.md)
- [Release Checklist](docs/release-checklist.md)

## Community

- [License](LICENSE)
- [Contributing](CONTRIBUTING.md)
- [Security Policy](SECURITY.md)
- [Code of Conduct](CODE_OF_CONDUCT.md)
- [Support](SUPPORT.md)
- [Changelog](CHANGELOG.md)
