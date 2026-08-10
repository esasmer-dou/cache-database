# CacheDB Examples and Operations Demo

[Türkçe](../tr/cachedb-examples/README.md)

This module is the framework-maintainer demo: it exercises load profiles,
operations screens, migration planning, and low-level compatibility surfaces.
Application teams should begin with one of the standalone REST API samples.

## Choose the Right Sample

| You want to... | Start here |
| --- | --- |
| Build a PostgreSQL application | [PostgreSQL REST API sample](../sample-cache-database-postgresql/README.md) |
| Build a SQL Server application | [SQL Server REST API sample](../sample-cache-database-mssql/README.md) |
| Learn generated repositories | [Declarative repository guide](../docs/declarative-repositories.md) |
| Operate the admin UI and load profiles | Continue with this module |
| Rehearse an existing-system migration | [Migration Planner Demo Flow](#migration-planner-demo-flow) |
| Inspect legacy/generated binding compatibility | [Low-Level Compatibility Examples](#low-level-compatibility-examples) |

Use it for two purposes:

- observe Redis-first runtime behavior under demo load
- rehearse the SQL migration planner flow against a real PostgreSQL demo schema

## Product Positioning For The Demo

The demo is not a transparent database-cache benchmark. It is a bounded
active-set and projection demo:

- Redis serves the online entity and projection paths.
- PostgreSQL stays responsible for durable history and migration source data.
- Relation-heavy screens should be read through projections or limited relation previews.
- Archive, full-history, export, and repair flows should use explicit SQL routes.

If a demo route returns no Redis data, that does not prove the durable row is
lost. It usually means the route is outside the active data set, the projection
was not warmed, or the screen needs an explicit SQL path.

## Spring Boot Demo

Start the recommended demo with:

```powershell
./tools/ops/demo/run-spring-boot-load-demo.ps1
```

The script is the supported local entrypoint because it prepares the expected
Redis/PostgreSQL topology and starts the correct Spring Boot profile. Do not
reconstruct the topology from individual Maven commands unless you are testing
the standalone mode deliberately.

Open:

- demo load UI: `http://127.0.0.1:8090/demo-load`
- admin dashboard: `http://127.0.0.1:8090/cachedb-admin?lang=tr`
- migration planner: `http://127.0.0.1:8090/cachedb-admin/migration-planner?lang=tr`

The load UI and admin dashboard use the same Spring Boot application port. There
is no second public admin server in this mode.

## Load Scenario Workspace

The load workspace includes:

- a Bootstrap + AJAX control UI for seeding data and starting load profiles
- CacheDB admin dashboard pages for backlog, incidents, memory, routing, and migration planning

Demo domain:

- `DemoCustomerEntity`
- `DemoProductEntity`
- `DemoCartEntity`
- `DemoOrderEntity`
- `DemoOrderLineEntity`

Default seeded volume:

- customers: `1,800`
- products: `1,400`
- carts: `4,500`
- orders: `3,600`
- order lines: `54,000`
- total: `65,300`

The volume is intentionally large enough to show relation-heavy behavior, but
small enough to keep repeated local demo runs practical.

## First Successful Run

For a normal load demo:

1. Open `http://127.0.0.1:8090/demo-load`.
2. Click `Seed Demo Data`.
3. Start `LOW` and watch admin metrics.
4. Move to `MEDIUM`.
5. Move to `HIGH` only after the previous profile is stable.
6. Watch write-behind backlog, Redis memory, incidents, and runtime profile.
7. Stop when backlog grows continuously, readiness degrades, or Redis reaches
   its warning threshold; a higher profile is not useful evidence in that state.

If `LOW / MEDIUM / HIGH` fails because data is missing, seed first. The demo no
longer silently starts seed work behind a load button.

Load profiles:

- `LOW`: daytime traffic with catalog browsing, full-customer sweeps, and light bulk cart/product updates
- `MEDIUM`: evening shopping traffic with larger reads, top-customer order lookups, and balanced bulk writes
- `HIGH`: campaign-hour spike with full customer scans, high-line-count order reads, and dense stock/cart/order bursts

## Migration Planner Demo Flow

Use this flow when you want to test the existing SQL-database migration behavior
with the bundled PostgreSQL demo dataset:

1. Open `http://127.0.0.1:8090/cachedb-admin/migration-planner?lang=tr`.
2. Click `Create and seed the demo schema`.
3. Run schema discovery against the PostgreSQL demo dataset.
4. Pick a suggested route such as customer to orders.
5. Click `Apply to form`.
6. Click `Generate plan`.
7. Generate scaffold if you want Java skeleton output.
8. Run dry-run warm.
9. Run real staging warm.
10. Run side-by-side compare.
11. Download the migration report.

Prepared demo objects:

- `cachedb_migration_demo_customers`
- `cachedb_migration_demo_orders`
- `cachedb_migration_demo_customer_order_timeline_v`
- `cachedb_migration_demo_customer_metrics_v`
- `cachedb_migration_demo_ranked_orders_v`

If comparison says the route is not ready, inspect the report before changing
the route. A fast CacheDB number is not enough; first-page membership and order
must also match the source-database baseline for the selected route.

## Standalone Demo

Use the standalone mode only when you explicitly want to run outside Spring
Boot:

```powershell
mvn -q -pl cachedb-examples -am exec:java `
  "-Dexec.mainClass=com.reactor.cachedb.examples.demo.DemoLoadMain" `
  "-Dcachedb.demo.redisUri=redis://default:welcome1@127.0.0.1:56379" `
  "-Dcachedb.demo.jdbcUrl=jdbc:postgresql://127.0.0.1:55432/postgres" `
  "-Dcachedb.demo.jdbcUser=postgres" `
  "-Dcachedb.demo.jdbcPassword=postgresql"
```

Default standalone URLs:

- demo load UI: `http://127.0.0.1:8090`
- admin dashboard: `http://127.0.0.1:8080/dashboard`

## Preferred Application API

For new application code, follow the repository-first sample projects:

- [PostgreSQL sample](../sample-cache-database-postgresql/README.md)
- [SQL Server sample](../sample-cache-database-mssql/README.md)
- [Declarative repository guide](../docs/declarative-repositories.md)

Those applications put mapping on entities, route contracts on
`@CacheRepository` interfaces, and inject the generated repositories into
services.

## Low-Level Compatibility Examples

For production-style relation-heavy screens, see:

- [src/main/java/com/cachedb/examples/demo/DemoOrderReadModelPatterns.java](src/main/java/com/cachedb/examples/demo/DemoOrderReadModelPatterns.java)

This example represents the common "customer has many orders" problem:

- query summaries first
- fetch detail explicitly when the user opens a row
- limit relation preload when showing previews
- use projection-specific Redis indexes instead of decoding wide base entities
- move read-model maintenance out of the foreground write path with `EntityProjection.asyncRefresh()`

This module also keeps the lower-level generated-binding examples below. They
exist to test compatibility, benchmark wrapper surfaces, and demonstrate
framework internals; they are not the preferred application API:

- `DemoOrderEntityCacheBinding.orderSummary(orderRepository)`
- `DemoOrderEntityCacheBinding.topCustomerOrders(orderSummaryRepository, customerId, 24)`
- `DemoOrderEntityCacheBinding.orderLinesPreviewRepository(orderRepository, 8)`
- `UserEntityCacheBinding.usersPage(session, 0, 25)`
- `UserEntityCacheBinding.activateUser(session, 41L, "alice")`
- `UserEntityCacheBinding.using(session).queries().activeUsers(25)`
- `com.reactor.cachedb.examples.entity.GeneratedCacheModule.using(session).users().queries().activeUsers(25)`

Important consistency note:

- async projection refresh is Redis Stream-backed and durable
- refresh events survive process restarts
- projection reads are eventually consistent by design
- cutover decisions still need side-by-side parity checks for migrated routes

## Evidence Boundaries

This demo can prove that a route shape, guardrail, migration plan, or operations
screen behaves correctly in the local topology. It does not establish a
production capacity number. Production evidence needs the real network path,
container limits, Redis topology, database connection budget, dataset shape,
and expected concurrency.

## Runtime Tuning

Common demo knobs:

- demo Redis connection and pool: `cachedb.demo.redis.*`
- demo PostgreSQL connection: `cachedb.demo.postgres.*`
- demo-scoped core overrides: `cachedb.demo.config.*`
- global core overrides: `cachedb.config.*`
- demo cache policy and seeded row counts: `cachedb.demo.cache.*`, `cachedb.demo.seed.*`
- demo view and stop/error behavior: `cachedb.demo.view.*`, `cachedb.demo.stop.*`, `cachedb.demo.error.*`
- demo load profiles: `cachedb.demo.load.low.*`, `cachedb.demo.load.medium.*`, `cachedb.demo.load.high.*`
- demo UI worker/refresh controls: `cachedb.demo.ui.*`

Examples:

```powershell
-Dcachedb.demo.redis.pool.maxTotal=96
-Dcachedb.demo.postgres.connectTimeoutSeconds=15
-Dcachedb.demo.config.writeBehind.workerThreads=8
-Dcachedb.config.redisGuardrail.usedMemoryWarnBytes=2147483648
```

Full tuning catalog:

- [../docs/tuning-parameters.md](../docs/tuning-parameters.md)

## Troubleshooting

| Symptom | Action |
| --- | --- |
| A load profile says data is missing | Run `Seed Demo Data` and wait for completion before starting load |
| The migration planner returns no route candidates | Create/seed the demo schema, then run discovery again |
| CacheDB is fast but comparison is not ready | Fix membership/order parity; latency alone is not a cutover signal |
| Backlog keeps growing | Stop increasing load and inspect SQL latency, worker capacity, retries, and Redis pressure |
| The application port is unavailable | Stop the previous demo process or change the configured demo port |
