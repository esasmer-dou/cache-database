# CacheDB

Turkish version: [tr/README.md](tr/README.md)

CacheDB is a Redis-first Java data-layer framework that keeps the selected SQL
database as the durable source of truth. PostgreSQL and SQL Server are explicit,
first-class providers with separate starters and provider-specific evidence
lanes. CacheDB is built for teams that want ORM-like developer ergonomics
without hiding operational read, write, warm, or archive behavior behind
runtime magic.

The core design rule is simple:

- do not move the whole database into Redis
- explicitly define what is hot
- keep the selected SQL provider responsible for durable history
- use projections/read models for relation-heavy and globally sorted screens
- generate metadata at compile time instead of discovering it with runtime
  reflection

Both providers cover the same CacheDB application model: generated
repositories, bounded active routes, projections, warm/backfill, write-behind,
outbox integration, and explicit source routes. Database-specific connection,
locking, timeout, indexing, and HA behavior still has to be proven in the
application's own staging topology.

| Current line | Value |
| --- | --- |
| Latest published release | `v0.9.0` |
| Repository version | `0.9.0` |
| Library bytecode | Java 17 |
| Runnable samples | Java 21 |
| Local evidence topology | Redis 8.2.1, PostgreSQL 16, SQL Server 2022 |
| Application API | Compile-time generated `@CacheRepository` interfaces |

## What Is New In 0.9.0

- The annotation processor infers unambiguous query, lookup, window, and warm
  parameter roles while rejecting ambiguous declarations at compilation.
- Strict HOT and bounded SOURCE routes may return `CursorPage<T>` directly;
  coverage checks and cursor completion remain inside generated code.
- Generated `RepositoryRouteRef` values connect repository declarations to
  warm, coverage, and test APIs without raw route-name strings or reflection.
- Coverage scopes must constrain the same field with `EQ` in every query group.
- Actuator and Micrometer expose aggregate HOT route budgets, projection use,
  and unbudgeted route counts without route-name metric cardinality.
- Explicit timeout-bounded durability helpers simplify single commands while
  bulk imports continue to use bounded batch writing and backpressure.

Read the complete [v0.9.0 release notes](docs/releases/v0.9.0.md) and the
[fifth ten-iteration report](docs/framework-ux-fifth-10-iteration-report.md)
before upgrading.

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
| "How do I declare and operate repositories safely?" | [Declarative Repositories](docs/declarative-repositories.md) |
| "What changed in the second framework UX cycle?" | [Second Ten-Iteration Engineering Report](docs/framework-ux-second-10-iteration-report.md) |
| "What changed in the third framework UX cycle?" | [Third Ten-Iteration Engineering Report](docs/framework-ux-third-10-iteration-report.md) |
| "What changed in the fourth framework UX cycle?" | [Fourth Ten-Iteration Engineering Report](docs/framework-ux-fourth-10-iteration-report.md) |
| "What changed in the fifth framework UX cycle?" | [Fifth Ten-Iteration Engineering Report](docs/framework-ux-fifth-10-iteration-report.md) |
| "Where is a runnable REST API sample?" | [PostgreSQL Sample](sample-cache-database-postgresql/README.md) or [MSSQL Sample](sample-cache-database-mssql/README.md) |
| "Which Spring Boot dependency do I need?" | [Spring Boot Starter](docs/spring-boot-starter.md) |
| "How do multiple pods refresh and clean a hot set periodically?" | [Scheduled Warm and Hot-Set Reconciliation](docs/scheduled-warm.md) |
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
| New Spring Boot service | `cachedb-spring-boot-starter-postgres` or `cachedb-spring-boot-starter-mssql` | Explicit provider selection and Spring `DataSource` integration |
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

## Ten-Minute Learning Path

1. Run either the [PostgreSQL sample](sample-cache-database-postgresql/README.md)
   or the [SQL Server sample](sample-cache-database-mssql/README.md) with its
   `demo` profile.
2. Seed durable rows and wait for the distributed seed job to complete.
3. Call an archive endpoint to prove the SQL source route.
4. Run a projection-only warm job and wait for route coverage.
5. Call the matching Redis active route and compare membership and ordering.
6. Inspect `/api/tuning`, readiness, and the admin UI before changing any
   limits.

That sequence teaches the product contract more accurately than beginning with
unbounded CRUD methods.

## Install In 5 Minutes: Spring Boot

Keep `cachedb.version` aligned with the release you use. Version `0.9.0` is an
immutable release distributed through GitHub Packages and the GitHub Release
bundle.

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
    <!-- Optional: operations UI and migration planner -->
    <dependency>
        <groupId>com.reactor.cachedb</groupId>
        <artifactId>cachedb-spring-boot-starter-admin</artifactId>
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

Published artifacts are served through GitHub Packages. Add the repository to
the consumer POM when it is not inherited from a company parent:

```xml
<repositories>
    <repository>
        <id>cache-database-github-packages</id>
        <url>https://maven.pkg.github.com/esasmer-dou/cache-database</url>
    </repository>
</repositories>

<pluginRepositories>
    <pluginRepository>
        <id>cache-database-github-packages</id>
        <url>https://maven.pkg.github.com/esasmer-dou/cache-database</url>
        <releases>
            <enabled>true</enabled>
        </releases>
        <snapshots>
            <enabled>false</enabled>
        </snapshots>
    </pluginRepository>
</pluginRepositories>
```

`repositories` resolves CacheDB dependencies and `pluginRepositories` resolves
`cachedb-maven-plugin`. Both IDs must match the Maven server ID:

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

Use a token with `read:packages`. Version `0.9.0` is published as an immutable
package; a consumer build does not need the CacheDB source repository.

JDBC rule:

| SQL provider | Provider starter | JDBC driver | Runnable sample |
| --- | --- | --- | --- |
| PostgreSQL | `cachedb-spring-boot-starter-postgres` | `org.postgresql:postgresql` | [PostgreSQL sample](sample-cache-database-postgresql/README.md) |
| SQL Server | `cachedb-spring-boot-starter-mssql` | `com.microsoft.sqlserver:mssql-jdbc` | [SQL Server sample](sample-cache-database-mssql/README.md) |

- Add `spring-boot-starter-jdbc` if your application does not already create a
  Spring `DataSource`.
- If your app already uses `spring-boot-starter-data-jpa` or another starter
  that creates a `DataSource`, do not add JDBC again only for CacheDB.
- CacheDB needs a working Spring `DataSource` bean.
- `cachedb-annotations` and the `cachedb-processor` annotation processor are
  still required.
- Choose exactly one provider starter. Use
  `cachedb-spring-boot-starter-postgres` for PostgreSQL or
  `cachedb-spring-boot-starter-mssql` for SQL Server.
- With one provider on the classpath, `cachedb.sql.provider=AUTO` selects it.
  Multiple providers fail startup instead of being resolved silently.
- Add `cachedb-spring-boot-starter-admin` only when the operations console is
  required. It is not part of the core runtime starter.
- See [Declarative Repositories](docs/declarative-repositories.md) for the
  preferred application API and [Database Provider SPI](docs/database-provider-spi.md)
  for provider-specific tuning.

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
  registration:
    source: jdbc
    fail-on-unknown-entity: true
    entities:
      CustomerEntity:
        hot-entity-limit: 50000
        page-size: 100
        hot-policy:
          mode: STATE_WINDOW
          state-column: status
          state-values: [ACTIVE]
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

Declare the repository contract. The processor validates route fields,
parameters, limits, and return types, then generates the Spring bean and
reflection-free implementation:

```java
@CacheRepository(entity = CustomerEntity.class)
public interface CustomerRepository extends CacheDbRepository<CustomerEntity, Long> {
    @CacheLookup(idParameter = "customerId")
    HotLookup<CustomerEntity> detail(Long customerId);
}
```

Inject the generated repository into the application service:

```java
CustomerEntity customer = new CustomerEntity();
customer.customerId = 42L;
customer.taxNumber = "1234567890";
customer.customerType = "RETAIL";
customer.status = "ACTIVE";

WriteReceipt<CustomerEntity, Long> receipt = customers.save(customer);

CustomerEntity loaded = customers.detail(42L).orElseThrow(status ->
        new IllegalStateException("Customer is not available in Redis: " + status)
);
```

Behavior:

- `save` writes the entity to Redis when its policy admits it.
- Durable persistence is sent to the selected SQL write-behind path.
- `detail` is Redis-only; `NOT_CACHED` does not mean the SQL row is absent.
- If the entity does not satisfy the hot policy, it may be rejected or evicted
  from Redis.
- Archive or out-of-window reads must use an explicit bounded `@SourceRoute`.
- Routes that require preloaded Redis coverage should declare a `@WarmRoute`
  and use `HotWindow.completeItems()` at application endpoints after cutover.

`GeneratedCacheModule` remains supported for compatibility and low-level jobs.
New service code should normally use generated repositories. Continue with
[Declarative Repositories](docs/declarative-repositories.md).

## Relation Model

CacheDB relations are not Hibernate-style transparent lazy loading. Relations
are loaded only when requested explicitly.

Think about relation in three separate layers:

| Layer | What it does | Required for CacheDB preload? |
| --- | --- | --- |
| Source database primary/foreign key | Protects durable data integrity and prevents orphan rows | Recommended, but not enough by itself |
| `@CacheRelation` metadata | Tells CacheDB that a parent field represents a relation and which target field joins it | Yes |
| Generated/custom loader + `@CacheLookup` | Executes the bounded batch load when the caller asks for the relation | Yes |

So the rule is precise:

- A database foreign key does not automatically create a CacheDB relation.
- `@CacheRelation` does not create a database constraint.
- `kind = ONE_TO_MANY` is relation-shape metadata, not a DDL declaration.
- `mappedBy` points to the target entity field that carries the parent id.
- A typed target plus bounded ordering lets the processor generate the standard
  partitioned loader. Use `@CacheEntity.relationLoader` only for custom loading.
- The repository contract must request the relation through a bounded
  `@CacheLookup`.

```java
@CacheEntity(table = "customers", redisNamespace = "customers")
public class CustomerEntity {
    @CacheId(column = "customer_id")
    public Long customerId;

    @CacheRelation(
            target = OrderEntity.class,
            // OrderEntity.customerId, mapped to the order table's customer_id column.
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

Read with a bounded preview:

```java
@CacheLookup(idParameter = "customerId", relation = "orders",
        relationLimitParameter = "orderPreview", maxRelationRows = 25)
HotLookup<CustomerEntity> detail(Long customerId, int orderPreview);

CustomerEntity customer = customers.detail(customerId, 20)
        .orElseThrow(status -> mapHotLookupFailure(customerId, status));
```

What happens in common cases:

| Database FK | `@CacheRelation` | Generated/custom loader | Result |
| --- | --- | --- | --- |
| Yes | No | No | The database is consistent, but CacheDB has no relation path to preload. |
| No | Yes | Yes | CacheDB can preload if `mappedBy` is queryable, but orphan or inconsistent rows are your risk. Use only for legacy or soft relations. |
| Yes | Yes | No | A batch-only relation without generated or custom loading information is rejected at compile time. |
| Yes | Yes | Yes | BEST: durable integrity, explicit metadata, and bounded batch preload. |

BEST: use a bounded `@CacheLookup` for a small detail-page preview.

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
| Order-line preview | `@CacheLookup` with `linePreview=8` |
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
| Primary active-data read path | Redis | Database |
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
