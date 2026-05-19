# cachedb-examples

This module contains runnable examples for `cache-database`.

Use it for two purposes:

- observe Redis-first runtime behavior under demo load
- rehearse the migration planner flow against a real PostgreSQL demo schema

## Spring Boot Demo

Start the recommended demo with:

```powershell
./tools/ops/demo/run-spring-boot-load-demo.ps1
```

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

## Which Button Should I Press?

For a normal load demo:

1. Open `http://127.0.0.1:8090/demo-load`.
2. Click `Seed Demo Data`.
3. Start `LOW` and watch admin metrics.
4. Move to `MEDIUM`.
5. Move to `HIGH` only after the previous profile is stable.
6. Watch write-behind backlog, Redis memory, incidents, and runtime profile.

If `LOW / MEDIUM / HIGH` fails because data is missing, seed first. The demo no
longer silently starts seed work behind a load button.

Load profiles:

- `LOW`: daytime traffic with catalog browsing, full-customer sweeps, and light bulk cart/product updates
- `MEDIUM`: evening shopping traffic with larger reads, top-customer order lookups, and balanced bulk writes
- `HIGH`: campaign-hour spike with full customer scans, high-line-count order reads, and dense stock/cart/order bursts

## Migration Planner Demo Flow

Use this flow when you want to test existing-PostgreSQL migration behavior:

1. Open `http://127.0.0.1:8090/cachedb-admin/migration-planner?lang=tr`.
2. Click `Create and seed the demo schema`.
3. Run PostgreSQL schema discovery.
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
must also match PostgreSQL for the selected route.

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

## Read-Model Example

For production-style relation-heavy screens, see:

- [src/main/java/com/cachedb/examples/demo/DemoOrderReadModelPatterns.java](src/main/java/com/cachedb/examples/demo/DemoOrderReadModelPatterns.java)

This example represents the common "customer has many orders" problem:

- query summaries first
- fetch detail explicitly when the user opens a row
- limit relation preload when showing previews
- use projection-specific Redis indexes instead of decoding wide base entities
- move read-model maintenance out of the foreground write path with `EntityProjection.asyncRefresh()`

Generated helpers shown by the example:

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
