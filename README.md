# CacheDB

Turkish version: [tr/README.md](tr/README.md)

CacheDB is a Redis-first Java data-layer library that keeps a durable SQL
database as the source of truth. PostgreSQL is the default provider today;
MSSQL is available as an explicit provider with its own SQL Server evidence
lane. CacheDB is built for teams that want ORM-like developer ergonomics
without hiding the hot read/write path behind runtime magic.

The core design rule is simple:

- do not move the whole database into Redis
- explicitly define what is hot
- keep the selected SQL provider responsible for durable history
- use projections/read models for relation-heavy and globally sorted screens
- generate metadata at compile time instead of discovering it with runtime
  reflection

Short status: CacheDB core and the default PostgreSQL provider are ready for a
stable framework release with explicit production boundaries. Teams should
still prove every hot route with staging warm-up, side-by-side comparison,
route contracts, Redis memory limits, and rollback planning before cutover.
MSSQL has provider-specific CI coverage for write-behind, outbox, migration
planner, concurrency, lock handling, and restart/reconnect behavior; SQL Server
HA or Always On remains an application-environment certification item.

## Product Positioning: What CacheDB Is And Is Not

CacheDB is not a transparent read-through cache that sits between the
application and SQL. A Redis miss does not mean CacheDB will automatically scan
the database, fill Redis, and return the result for every query shape.

CacheDB is also not a drop-in Hibernate/JPA replacement for arbitrary dynamic
queries. It is a Redis-first active-data persistence and read-model layer for
bounded operational routes.

| Statement | Runtime meaning |
| --- | --- |
| Redis is the online read path | Entity and projection repositories read the active Redis data set. They do not automatically scan SQL on every miss. |
| SQL is the durable source of truth | PostgreSQL or MSSQL keeps the durable history through write-behind. Archive, export, audit, and full-history reads should use explicit SQL routes. |
| Hot policy is a contract | If a row is outside the active policy, an entity or projection read may return empty. That is expected behavior, not data loss. |
| Projection is part of the model | Relation-heavy lists, dashboards, timelines, top-N, and globally sorted screens should use compact read models. |
| Cold paths must be explicit | Use a bounded SQL endpoint, registered page loader, warm/backfill job, or migration route for data outside the active set. |

| Classification | Use CacheDB this way |
| --- | --- |
| BEST | Active-set ORM/read-model layer for high-throughput operational reads and controlled write-behind durability. |
| ACCEPTABLE | Redis-first persistence with explicit SQL cold paths and route-level guardrails. |
| ANTI-PATTERN | Put Redis in front of the database and expect every broad ORM query to miss Redis, scan SQL, refill Redis, and stay memory-safe. |

The design burden is intentional: before a route goes live, decide what belongs
in Redis, what stays only in SQL, which projection serves the screen, and what
happens when the requested data is outside the active set.

## What It Solves

| Problem | CacheDB approach |
| --- | --- |
| Low-latency reads for hot entities | Redis-first entity repositories |
| Durable writes | SQL write-behind flush |
| Growing relation fan-out | Relation limits, projections, and summary-first reads |
| Global top-N dashboards | Ranked projections and route contracts |
| Migration from existing SQL database/ORM systems | Migration Planner, warm-up, dry-run, side-by-side comparison |
| Redis memory growth | Hot policies, tenant quotas, payload budgets, admission telemetry |
| Multi-pod Kubernetes operation | Pod-unique consumers, Redis leader leases, coordination evidence |

## Documentation Map

| Question | Read |
| --- | --- |
| "Where is the full documentation map?" | [Documentation Map](DOCUMENTATION_MAP.md) |
| "Is CacheDB the right fit?" | [ORM Alternative Guide](docs/orm-alternative.md) |
| "How do I start from zero?" | [Getting Started](docs/getting-started.md) |
| "Where is a runnable REST API sample?" | [PostgreSQL Sample](sample-cache-database-postgresql/README.md) or [MSSQL Sample](sample-cache-database-mssql/README.md) |
| "Which Spring Boot dependency do I need?" | [Spring Boot Starter](docs/spring-boot-starter.md) |
| "What are entity, relation, projection, and route contract?" | [Concepts and Assumptions](docs/concepts-and-assumptions.md) |
| "How do I model real production cases?" | [Use Case Examples](docs/use-case-examples.md) |
| "How should I tune Redis memory and performance?" | [Production Tuning Guide](docs/production-tuning-guide.md) |
| "Where are all properties and defaults?" | [Tuning Parameters](docs/tuning-parameters.md) |
| "How do I migrate an existing SQL database system?" | [Migration Planner](docs/migration-planner.md) |
| "What must be proven before production?" | [Production Recipes](docs/production-recipes.md) |
| "What is still missing for GA?" | [Production GA Criteria](PRODUCTION_GA_CRITERIA.md) |
| "How do I decide whether a GA release can ship?" | [Production GA Release Runbook](docs/production-ga-release-runbook.md) |

## Choose Your Starting Path

| Situation | Recommended path | Why |
| --- | --- | --- |
| I want to run a complete sample first | [PostgreSQL Sample](sample-cache-database-postgresql/README.md) or [MSSQL Sample](sample-cache-database-mssql/README.md) | REST API, Docker Compose, schema, seed data, Postman collection |
| New Spring Boot service | `cachedb-spring-boot-starter` | Minimal setup, same-port admin UI, Spring `DataSource` integration |
| Existing Spring Boot app with JPA | Starter plus existing `DataSource` | JPA usually already creates the `DataSource`; do not duplicate JDBC setup |
| Plain Java service | `cachedb-starter` | You own bootstrap, shutdown, and connection lifecycle |
| Existing SQL database + ORM system | Migration Planner | Discover schema, warm Redis, compare the source database vs CacheDB, generate a cutover report |
| Relation-heavy list screen | Projection/read model | Avoid loading the full object graph on first paint |
| Internal worker, replay, repair, or batch job | Direct repository | Lower abstraction and more predictable operational behavior |

BEST: choose one hot route, define the Redis hot-set decision, warm it in
staging, compare it against the source database, and cut over only when parity
and latency are proven.

ANTI-PATTERN: mark every table as an entity and expect Redis to automatically
make every dynamic query fast.

## Install In 5 Minutes: Spring Boot

Keep `cachedb.version` aligned with the release you use.

```xml
<properties>
    <cachedb.version>0.2.0</cachedb.version>
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

- Add `spring-boot-starter-jdbc` if your application does not already create a
  Spring `DataSource`.
- If your app already uses `spring-boot-starter-data-jpa` or another starter
  that creates a `DataSource`, do not add JDBC again only for CacheDB.
- CacheDB needs a working Spring `DataSource` bean.
- `cachedb-annotations` and the `cachedb-processor` annotation processor are
  still required.
- The example below uses PostgreSQL because it is the default provider. For
  MSSQL, add `cachedb-storage-mssql`, the Microsoft JDBC driver, and wire the
  provider explicitly with `cachedb.sql.provider=mssql` or
  `MssqlWriteBehindFlusher.factory(...)` as described in
  [Database Provider SPI](docs/database-provider-spi.md).

Minimal `application.yml`:

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
  admin:
    http-enabled: true
```

Admin UI:

- dashboard: `/cachedb-admin`
- migration planner: `/cachedb-admin/migration-planner`
- health API: `/cachedb-admin/api/health`

Production rule: do not expose `/cachedb-admin/**` directly to the public
internet. Put it behind a gateway or reverse proxy, and use gateway auth or
CacheDB token auth.

## First Entity

CacheDB entities use explicit field metadata. The important rule for new users:
persisted fields must not be `private` or `final`.

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

After compilation, the annotation processor generates binding classes. CacheDB
does not rely on runtime reflection to discover persisted fields.

## First Read And Write

Start normal service code from the generated surface:

```java
var domain = GeneratedCacheModule.using(session);

CustomerEntity customer = new CustomerEntity();
customer.customerId = 42L;
customer.taxNumber = "1234567890";
customer.customerType = "RETAIL";
customer.status = "ACTIVE";

domain.customers().save(customer);

CustomerEntity loaded = domain.customers()
        .findById(42L)
        .orElseThrow();
```

Behavior:

- `save` writes the hot entity to Redis.
- Durable persistence is sent to the selected SQL write-behind path.
- `findById` reads the hot entity from Redis.
- If the entity does not satisfy the hot policy, it may be rejected or evicted
  from Redis.

## Relation Model

CacheDB relations are not Hibernate-style transparent lazy loading. Relations
are loaded only when requested explicitly.

Think about relation in three separate layers:

| Layer | What it does | Required for CacheDB preload? |
| --- | --- | --- |
| Source database primary/foreign key | Protects durable data integrity and prevents orphan rows | Recommended, but not enough by itself |
| `@CacheRelation` metadata | Tells CacheDB that a parent field represents a relation and which target field joins it | Yes |
| `RelationBatchLoader` + `FetchPlan` | Executes the bounded batch load when the caller asks for the relation | Yes |

So the rule is precise:

- A database foreign key does not automatically create a CacheDB relation.
- `@CacheRelation` does not create a database constraint.
- `kind = ONE_TO_MANY` is relation-shape metadata, not a DDL declaration.
- `mappedBy` points to the target entity field that carries the parent id.
- Relation preload works only when the source entity is registered with a
  concrete `RelationBatchLoader` and the caller requests the relation through
  `FetchPlan` or `withRelationLimit(...)`.

```java
@CacheEntity(
        table = "customers",
        redisNamespace = "customers",
        relationLoader = CustomerOrdersRelationBatchLoader.class
)
public class CustomerEntity {
    @CacheId(column = "customer_id")
    public Long customerId;

    @CacheRelation(
            targetEntity = "OrderEntity",
            // OrderEntity.customerId, mapped to the order table's customer_id column.
            mappedBy = "customerId",
            kind = CacheRelation.RelationKind.ONE_TO_MANY,
            batchLoadOnly = true
    )
    public List<OrderEntity> orders;
}
```

Read with a bounded preview:

```java
CustomerEntity customer = customerRepository
        .withRelationLimit("orders", 20)
        .findById(customerId)
        .orElseThrow();
```

What happens in common cases:

| Database FK | `@CacheRelation` | Loader | Result |
| --- | --- | --- | --- |
| Yes | No | No | The database is consistent, but CacheDB has no relation path to preload. |
| No | Yes | Yes | CacheDB can preload if `mappedBy` is queryable, but orphan or inconsistent rows are your risk. Use only for legacy or soft relations. |
| Yes | Yes | No | Metadata exists, but fetch preload cannot run; strict relation config can fail fast. |
| Yes | Yes | Yes | BEST: durable integrity, explicit metadata, and bounded batch preload. |

BEST: use `withRelationLimit(...)` for a small detail-page preview.

ANTI-PATTERN: load a customer's full order history as a relation on a list
screen.

## When Projection Is Required

A projection stores the small, stable read model required by a screen instead of
hydrating the full entity payload.

Use projections for:

- customer-level lists such as latest 1,000 orders
- dashboard top-N cards
- global business-priority rankings
- first-paint summary screens
- flows where full entity details are fetched only after the user opens a row

Example decision:

| Screen | Model |
| --- | --- |
| Customer card | `CustomerEntity` |
| Latest customer orders | `CustomerOrderSummaryProjection` |
| Order detail | `OrderEntity` |
| Order-line preview | `withRelationLimit("orderLines", 8)` |
| Global highest-risk orders | Ranked projection |

## Redis Memory Discipline

CacheDB should not be operated as "set a TTL and hope Redis stays small."
Production memory control needs four layers:

- entity hot policy: which rows may enter Redis?
- route contract: how many rows may this endpoint read?
- tenant quota: can one tenant or customer consume the memory budget?
- Redis `maxmemory` and eviction policy: what is the infrastructure limit?

Hot policy examples:

| Need | Approach |
| --- | --- |
| Keep latest 100,000 rows hot | `COUNT_WINDOW` |
| Keep last 90 days of orders hot | `TIME_WINDOW` on `order_date` |
| Keep only `OPEN/PENDING` work hot | `STATE_WINDOW` |
| Last 90 days plus open state plus tenant quota | `COMPOSITE` plus tenant quota |

Read [Production Tuning Guide](docs/production-tuning-guide.md) together with
[Tuning Parameters](docs/tuning-parameters.md) for configuration details.

## Existing SQL Database + ORM Migration

The Migration Planner is not a one-click production cutover tool. Its job is to
prove the route shape before cutover:

- Should this route use entity, projection, or ranked projection?
- Which Redis hot window should be warmed?
- Which data stays in the durable SQL database as full history?
- How many rows does warm-up read?
- Does CacheDB return the same IDs and ordering as the source database?
- Is p95 latency acceptable?
- What is the rollback plan?

Recommended flow:

1. Open `/cachedb-admin/migration-planner`.
2. Discover the source database schema.
3. Select a route candidate and apply it to the form.
4. Generate the plan.
5. Generate scaffold.
6. Run dry-run warm; Redis must not change.
7. Run staging warm; Redis hot set should be filled.
8. Run side-by-side comparison.
9. Download the report.
10. Repeat for every production screen, API, batch, and report route.

Full conversion coverage comes from a route inventory, not from one selected
table.

## Production Checklist

- Is Redis HA/failover planned and tested?
- Does the selected SQL provider remain the durable source of truth?
- If external systems mutate the source database, is outbox/CDC configured?
- Does every hot route have a route contract?
- Can projection-required routes fail fast instead of falling back to entity
  scans?
- Do hot policy and tenant quota protect memory?
- Can warm-up resume from checkpoints?
- Has side-by-side comparison proven data membership and ordering?
- Is the admin UI behind a trusted operations network or gateway?
- Do benchmark thresholds and public API compatibility checks run in CI?

## Quick Comparison

| Topic | CacheDB | Traditional ORM |
| --- | --- | --- |
| Primary hot read path | Redis | Database |
| Durable source | SQL database | Database |
| Metadata | Compile-time generated | Usually runtime metadata/reflection |
| Relation behavior | Explicit `FetchPlan`, loaders, projections | Often lazy/eager object graph behavior |
| Large list screens | Projection/read-model | Often entity graph or SQL join first |
| Best fit | Low-latency hot routes | SQL-centric relational workloads |
| Main risk | Poor hot-set or projection design | N+1, wide joins, runtime ORM cost |

## How To Read Benchmarks

Benchmark results should not be read as "CacheDB is always faster." The right
reading is:

- generated bindings can stay in a low-overhead band
- direct repositories give more control on critical hot paths
- production cost usually comes from query shape, relation hydration, Redis
  contention, and write-behind pressure
- relation-heavy screens need projection design before measurement

Re-run the local recipe benchmark with:

```powershell
mvn -q -f cachedb-production-tests/pom.xml exec:java `
  "-Dexec.mainClass=com.reactor.cachedb.prodtest.scenario.RepositoryRecipeBenchmarkMain"
```

## Read Next

- [Getting Started](docs/getting-started.md)
- [Concepts and Assumptions](docs/concepts-and-assumptions.md)
- [Spring Boot Starter](docs/spring-boot-starter.md)
- [Migration Planner](docs/migration-planner.md)
- [Use Case Examples](docs/use-case-examples.md)
- [Production Tuning Guide](docs/production-tuning-guide.md)
- [Tuning Parameters](docs/tuning-parameters.md)
- [Production Recipes](docs/production-recipes.md)
- [Production Tests](cachedb-production-tests/README.md)
- [Examples](cachedb-examples/README.md)
- [Architecture](docs/architecture.md)
- [Production GA Criteria](PRODUCTION_GA_CRITERIA.md)
- [Release Checklist](docs/release-checklist.md)

## Community

- [License](LICENSE)
- [Contributing](CONTRIBUTING.md)
- [Security Policy](SECURITY.md)
- [Code of Conduct](CODE_OF_CONDUCT.md)
- [Support](SUPPORT.md)
- [Changelog](CHANGELOG.md)
