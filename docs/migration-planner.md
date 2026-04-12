# Migration Planner

The admin UI now includes a migration planner wizard for teams coming from an
existing PostgreSQL plus ORM stack.

Use it when you need help answering questions like:

- should this route stay on full entities or move to a projection?
- which rows should stay hot in Redis?
- where should the cold/history boundary live?
- how should we warm the first Redis working set?
- what should we compare in staging before cutover?

## What It Can Discover Now

The planner can now inspect the connected PostgreSQL schema and seed the wizard
interactively.

It can discover:

- user tables from the configured `DataSource`
- primary keys
- single-column foreign key relationships
- temporal and ranking-like sort candidates
- suggested root/child route pairs you can apply directly to the planner form
- scaffold-friendly class names and hot-route defaults for the discovered pair

This means the user no longer has to type every table and column name by hand
before planning a route.

## Interactive Demo Bootstrap

The Spring Boot demo now includes a one-click PostgreSQL migration dataset for
the planner wizard itself.

From `/cachedb-admin/migration-planner` you can now:

1. create a demo customer/order schema
2. seed realistic customer and order history
3. apply explicit PK/FK and supporting indexes
4. create ready-made SQL views for manual inspection
5. refresh discovery and continue directly into scaffold, warm, and compare

The prepared demo objects are:

- `cachedb_migration_demo_customers`
- `cachedb_migration_demo_orders`
- `cachedb_migration_demo_customer_order_timeline_v`
- `cachedb_migration_demo_customer_metrics_v`
- `cachedb_migration_demo_ranked_orders_v`

This makes it easy to rehearse the full route:

- discover
- scaffold
- dry-run warm
- real warm
- side-by-side compare

## Where It Lives

On the same admin host and port:

- Spring Boot: `/cachedb-admin/migration-planner`
- Native admin server: `/migration-planner`

## What It Asks

The first version models one hot route at a time.

You can still describe a route manually, but the preferred flow is now:

1. discover the PostgreSQL schema
2. apply a suggested root/child route to the form
3. adjust only the route-specific behavior flags that discovery cannot know

When you continue manually, the planner still asks for:

- root table/entity
- child table/entity
- relation column
- sort column and sort direction
- current root and child row counts
- typical and worst-case child fan-out per root
- first page size
- desired hot window per root
- whether the route is list-heavy, globally sorted, threshold/range driven, or eager-loading heavy
- whether full history must stay hot

## What It Produces

The wizard returns a concrete migration plan and a staging warm execution shape:

- recommended CacheDB surface
- whether a projection is required
- whether a ranked projection is required
- bounded Redis hot-window recommendation
- Redis placement guidance
- PostgreSQL placement guidance
- warm-up steps
- staging comparison checklist
- sample child warm SQL for building the first hot window from PostgreSQL
- sample root warm SQL template for referenced root rows

## What It Can Generate Now

The planner can now generate a binding-ready scaffold from the discovered route.

That scaffold includes:

- root `@CacheEntity` skeleton
- child `@CacheEntity` skeleton with a hot-list named query
- optional relation loader skeleton
- optional projection support skeleton
- a usage snippet that shows the generated binding surface expected after compilation

The output is intentionally conservative. It helps a team start from a real
route shape instead of hand-writing entity metadata from scratch.

## What It Can Execute Now

The planner can now run a real staging warm execution.

That warm path:

- reads the selected hot window from PostgreSQL
- hydrates Redis entities directly without enqueueing PostgreSQL write-behind
- refreshes registered projections inline so the warmed route is readable immediately
- optionally warms the referenced root rows for the same hot child window
- supports dry-run mode before any Redis mutation

This is meant for staging and migration rehearsal. It is not a production cutover
button.

The planner can also run a side-by-side comparison against the current
PostgreSQL route.

That comparison can:

- measure baseline PostgreSQL list latency
- measure the resolved CacheDB route latency
- compare the first-page membership/order on representative sample roots
- optionally warm the Redis working set immediately before the comparison
- keep the baseline SQL explicit so the team can inspect or override it

The comparison result now also includes an automatic migration assessment. That
assessment summarizes:

- whether the route is ready, needs review, or is not ready yet
- whether the sample pages matched PostgreSQL exactly
- whether CacheDB stayed within the expected latency envelope
- which blockers still need to be resolved before cutover
- which next steps the team should take in staging

## Recommended Use

Use it as part of a staged migration:

1. baseline the current ORM route
2. model the route in the planner
3. build the recommended projection/hot window in staging
4. generate the entity/projection scaffold for the route
5. run a dry-run warm and inspect the generated SQL
6. run the real staging warm for the recommended Redis working set
7. run the side-by-side comparison in staging
8. cut over only after ordering, latency, and load shape look correct

## Current Scope

The current planner is intentionally conservative even though it can now execute
the staging warm.

It does:

- produce the target shape
- produce a warm-up plan
- produce a comparison checklist
- generate sample SQL backfill queries
- generate binding-ready entity/relation/projection scaffolds
- execute a real staging warm into Redis
- execute a side-by-side PostgreSQL vs CacheDB comparison
- produce an automatic migration assessment from the comparison result
- support dry-run validation before warming Redis

It does not yet:

- mutate PostgreSQL
- mutate production data
- import existing ORM source classes automatically
- perform a one-click production cutover

That boundary is intentional. The goal is to help teams make the right
architecture decision, rehearse the move in staging, and only then automate the
rest of the migration.
