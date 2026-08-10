# CacheDB As An ORM Alternative

This page is the short external-facing answer to one question:

When should a team choose CacheDB instead of a traditional JPA/Hibernate-style ORM?

The answer must stay aligned with the project's first priority:

- keep production runtime overhead low
- make the library easy enough to be a serious ORM alternative

## Short Answer

Choose CacheDB when:

- Redis is part of the real runtime plan, not just an afterthought
- low-latency reads matter more than transparent relational abstraction
- the team is willing to be explicit about relation loading, projections, and hot paths
- production overhead, startup simplicity, and escape hatches matter

Stay with Hibernate/JPA when:

- your application depends heavily on relational joins as the primary read model
- you want transparent ORM behavior more than explicit runtime control
- the team expects lazy loading and entity graph behavior to stay mostly invisible
- most production bottlenecks are in SQL modeling, not read-path latency

## What CacheDB Is

CacheDB is not trying to be a drop-in clone of Hibernate.

It is an explicit Redis-first persistence library where:

- writes are accepted by Redis first and persisted through write-behind
- hot reads use Redis-only route contracts; archive/history reads use bounded SQL routes
- the selected SQL provider remains the durable persistence layer
- metadata is compile-time generated
- relation loading is explicit
- write-behind moves database durability off the foreground path

That means CacheDB should be evaluated as:

- a low-overhead ORM alternative for teams that want explicit control
- a production-oriented persistence library for Redis-first applications
- a library that keeps an escape hatch for true hotspots

## Comparison At A Glance

| Topic | CacheDB | Traditional JPA / Hibernate |
| --- | --- | --- |
| Primary read path | Redis-first | Database-first |
| Metadata model | Compile-time generated | Usually runtime reflection and ORM metadata |
| Default philosophy | Explicit control | Transparent abstraction |
| Relation loading | Explicit bounded lookup or projection | Often implicit lazy/eager graph behavior |
| Application API | Compile-time generated `@CacheRepository` implementation | Runtime ORM repository/session |
| Hotspot escape hatch | Provider repository or adapter for measured infrastructure work | Usually stays inside ORM abstractions or custom SQL |
| Best fit | Low-latency services, read-heavy APIs, Redis-centric systems | Relational domains, SQL-centric systems, join-heavy apps |
| Runtime overhead goal | Very low | Often acceptable, but not the primary design goal |

## Where CacheDB Fits Best

CacheDB is a strong fit for:

- product services with hot read paths
- dashboard and list-heavy applications that benefit from projections
- systems that already operate Redis as a first-class production dependency
- teams that want generated ergonomics without reflection-heavy runtime behavior
- services that need clear separation between normal code and measured hotspots

## Where CacheDB Is A Worse Fit

CacheDB is a worse fit if the team wants:

- a mostly invisible ORM that hides read-model shape
- wide relational joins as the default way to build screens
- automatic graph traversal without thinking about payload size
- highly relational reporting workloads as the primary application pattern

In those cases, Hibernate/JPA may still be the more natural tool.

That is not a weakness in the message. It makes the positioning more credible.

## What Production Teams Should Expect

If a team adopts CacheDB well, production should usually look like this:

- default business code injects declarative repositories
- hot paths use `@HotRoute`, projections, explicit windows, and coverage
- durable archive/history reads use bounded `@SourceRoute` methods
- global sorted/range read screens use projection-specific ranked fields instead of wide multi-sort entity queries
- those ranked projection fields are declared with `rankedBy(...)` so the projection repository can use a top-window fast path
- only measured infrastructure paths drop to provider repositories
- foreground repository traffic is isolated from background worker/admin traffic

If a team adopts CacheDB badly, the failure pattern is usually this:

- it hydrates wide aggregates for list pages
- it treats Redis as magically free
- it avoids projections
- it shares Redis pools between foreground and background paths
- it bypasses route contracts with low-level repositories throughout application code

CacheDB rewards explicitness. It does not reward pretending object graphs are free.

## Recommended Adoption Path

Use this migration path if a team is coming from JPA/Hibernate:

1. Keep table mapping and relation metadata on the entity.
2. Add an `@CacheRepository` interface for each aggregate or route group.
3. Declare detail reads as `@CacheLookup` and Redis list reads as `@HotRoute`.
4. Move list screens to projections and summary/detail patterns.
5. Declare archive/history reads as bounded `@SourceRoute` or reviewed `@SourceSql` methods.
6. Derive `@WarmRoute` methods from hot routes and prove route coverage before cutover.
7. Keep the old ORM route until parity, latency, memory, and rollback checks pass.

This path keeps onboarding smooth while preserving the low-overhead goal.

## Surface Selection

Use this as the default team rule:

| Team or workload | Recommended surface |
| --- | --- |
| Normal product service code | injected `@CacheRepository` |
| Redis-only detail and list endpoints | `@CacheLookup` / `@HotRoute` |
| Durable archive/history | bounded `@SourceRoute` / reviewed `@SourceSql` |
| Worker, replay, recovery, infrastructure code | low-level repository or provider adapter |
| Relation-heavy list or dashboard reads | projection-returning `@HotRoute` plus `@WarmRoute` |

## Benchmark Honesty

The repository recipe benchmark in this repo is intentionally narrow.

It proves one useful thing:

- generated ergonomics stay in the same low-overhead band as direct repository usage

It does **not** prove:

- that CacheDB is universally faster than Hibernate in all workloads
- that Redis latency disappears
- that relation-heavy screens are cheap without read-model discipline

Use the benchmark for API-surface honesty, not marketing fiction.

## Current Evidence Inside This Repo

Latest recipe benchmark snapshot:

- `Generated entity binding`: fastest average in the current local run
- `Minimal repository`: lowest p95 in the current local run
- `JPA-style domain module`: grouped ergonomic surface with modest wrapper cost

That is the important outcome:

- the ergonomic surface is not free
- but it stays close enough to the direct repository path that most teams should not sacrifice readability prematurely

## Read This Next

- [Production Recipes](./production-recipes.md)
- [Spring Boot Starter](./spring-boot-starter.md)
- [Tuning Parameters](./tuning-parameters.md)
- [Production Tests](../cachedb-production-tests/README.md)
