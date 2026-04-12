# cachedb-examples

This module contains runnable examples for `cache-database`.

## Load Scenario Workspace

The load scenario workspace starts:

- a Bootstrap + AJAX control UI for seeding data and starting load profiles
- the existing CacheDB admin dashboard for monitoring backlog, incidents, memory, and routing

Demo domain:

- `DemoCustomerEntity`
- `DemoProductEntity`
- `DemoCartEntity`
- `DemoOrderEntity`
- `DemoOrderLineEntity`

UI views:

- customers
- products
- carts
- orders
- order lines

Default seeded volume:

- customers: `1,800`
- products: `1,400`
- carts: `4,500`
- orders: `3,600`
- order lines: `54,000`
- total: `65,300`

This default profile is meant to stay interactive in the Spring Boot demo while still feeling like a believable commerce slice. Most of the physical volume still sits in order lines, but the overall footprint is smaller so `Seed`, `Clear`, `Fresh Start`, and `LOW / MEDIUM / HIGH` transitions remain practical during repeated observation runs.

Load profiles:

- `LOW`: daytime traffic with catalog browsing, full-customer sweeps, and light bulk cart/product updates
- `MEDIUM`: evening shopping traffic with large catalog reads, top-customer order lookups, and balanced bulk writes
- `HIGH`: campaign-hour spike with full customer scans, high-line-count order reads, and dense stock/cart/order bursts

Run standalone demo:

```powershell
mvn -q -pl cachedb-examples -am exec:java `
  "-Dexec.mainClass=com.reactor.cachedb.examples.demo.DemoLoadMain" `
  "-Dcachedb.demo.redisUri=redis://default:welcome1@127.0.0.1:56379" `
  "-Dcachedb.demo.jdbcUrl=jdbc:postgresql://127.0.0.1:55432/postgres" `
  "-Dcachedb.demo.jdbcUser=postgres" `
  "-Dcachedb.demo.jdbcPassword=postgresql"
```

Default URLs:

- demo load UI: `http://127.0.0.1:8090`
- admin dashboard: `http://127.0.0.1:8080/dashboard`

Run Spring Boot demo:

```powershell
./tools/ops/demo/run-spring-boot-load-demo.ps1
```

Spring Boot URLs:

- demo load UI: `http://127.0.0.1:8090/demo-load`
- admin dashboard: `http://127.0.0.1:8090/cachedb-admin?lang=tr`
- migration planner wizard: `http://127.0.0.1:8090/cachedb-admin/migration-planner?lang=tr`

Spring Boot notes:

- the load UI and admin dashboard share the same application port
- there is no second internal admin HTTP server in Spring Boot mode
- the same seed volume and LOW / MEDIUM / HIGH scenarios are reused
- the demo Redis pool is widened by default to match the heavier standalone load behavior
- foreground repository Redis traffic and background worker/admin traffic use separate pools in the Spring Boot demo
- `Start LOW / MEDIUM / HIGH` does not auto-seed; if data is not ready, the UI returns a direct error and asks you to seed first
- Spring Boot demo now uses zero-glue generated registrar discovery, so bindings are auto-registered without an explicit `GeneratedCacheBindings.register(...)` call
- the migration planner page can now bootstrap its own PostgreSQL customer/order demo schema with PK/FK, indexes, seeded history, and inspection views

Migration planner demo flow:

1. open `http://127.0.0.1:8090/cachedb-admin/migration-planner?lang=tr`
2. click `Create and seed the demo schema`
3. review discovered tables plus views
4. generate scaffold
5. run dry-run warm, then real warm
6. run side-by-side compare and download the migration report

## Read-Model Example

For production-style relation-heavy screens, see:

- [src/main/java/com/cachedb/examples/demo/DemoOrderReadModelPatterns.java](src/main/java/com/cachedb/examples/demo/DemoOrderReadModelPatterns.java)

This example shows the preferred pattern:

- summary query first
- explicit detail fetch second
- limited relation preload only when it is intentional
- generated binding classes and fluent `QuerySpec.where(...).orderBy(...).limitTo(...)` usage
- generated projection helpers such as `DemoOrderEntityCacheBinding.orderSummary(orderRepository)`
- generated named query helpers such as `DemoOrderEntityCacheBinding.topCustomerOrders(orderSummaryRepository, customerId, 24)`
- generated fetch preset helpers such as `DemoOrderEntityCacheBinding.orderLinesPreviewRepository(orderRepository, 8)`
- generated page preset helpers such as `UserEntityCacheBinding.usersPage(session, 0, 25)`
- generated write command helpers such as `UserEntityCacheBinding.activateUser(session, 41L, "alice")`
- session-bound use-case groups such as `UserEntityCacheBinding.using(session).queries().activeUsers(25)`
- package-level domain modules such as `com.reactor.cachedb.examples.entity.GeneratedCacheModule.using(session).users().queries().activeUsers(25)`

The example uses:

- `FetchPlan.withRelationLimit("orderLines", 8)`
- a separate summary read model instead of rendering directly from a large eager object graph
- projection-specific Redis indexes instead of reusing only the base entity payload path
- `EntityProjection.asyncRefresh()` so read-model maintenance can move off the foreground write path

Important note:

- the current async projection refresh is Redis Stream-backed durable eventual consistency
- it improves production write overhead and read payload size
- refresh events survive process restarts and can be consumed through a Redis consumer group
- it is still not a full projection platform with poison-queue handling or dedicated replay tooling

Suggested flow:

1. Open the demo load UI.
2. Click `Seed Demo Data`.
3. Start `LOW`, then `MEDIUM`, then `HIGH`.
4. Keep the admin dashboard open in parallel and watch:
   - write-behind backlog
   - Redis memory
   - incidents
   - runtime profile
   - alert routing
   - incident severity trends

Runtime tuning:

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
