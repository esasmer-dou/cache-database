# Stable Release Launch Kit

Turkish version: [../tr/docs/stable-release-launch-kit.md](../tr/docs/stable-release-launch-kit.md)

Use this page when publishing a non-beta CacheDB release through GitHub
Releases or another official package channel.

## Repository About

```text
Redis-first Java data layer with bounded hot sets, projections, compile-time generated APIs, and durable SQL write-behind.
```

## Suggested Topics

```text
java, redis, sql, postgresql, mssql, cache, cqrs, projections, orm-alternative, spring-boot
```

## Official Distribution Channel

For `v0.9.0`, the official distribution channels are GitHub Packages and the
GitHub Release asset:

```text
cache-database-0.9.0-github-release.zip
```

The bundle contains binary, source, javadoc, and POM artifacts for 16 public
modules plus the CacheDB BOM, README, security/community files, English docs,
and Turkish docs. Maven Central is not required because GitHub Packages and the
GitHub Release bundle are the selected official distribution channels.

## Release Positioning

`cache-database v0.9.0`

CacheDB `v0.9.0` reduces declarative repository wiring while preserving explicit
production contracts. The processor infers only unambiguous parameter roles,
strict HOT and bounded SOURCE routes can return cursor pages directly, generated
route references connect declarations to warm/coverage/test APIs, and coverage
scope validation prevents cross-scope completeness claims. HOT route budgets
are visible through Actuator and Micrometer. PostgreSQL and MSSQL samples expose
the same application model and provider-specific runtime path.

This release does not claim that every consuming application can cut production
traffic over without its own validation. Before cutover, each application still
needs route inventory, warm-up, side-by-side comparison, Redis memory budgets,
rollback planning, and environment-specific HA evidence.

MSSQL is an explicitly selected provider with live SQL Server evidence,
restart/reconnect checks, concurrency and lock-classification coverage,
outbox/checkpoint support, and migration planner coverage. This is still not a
blanket claim that every SQL Server HA or Always On topology is certified; those
topologies must be proven in the consuming application's staging environment.

## Release Notes Template

```markdown
## cache-database v0.9.0

This stable release improves the practical migration path for existing SQL-backed applications.

### What is stable

- Route/scope/sort-bound keyset cursors and typed `CursorPage<T>` responses.
- Compile-time repository defaults, route capabilities, route catalogs, and
  bounded operational inventory.
- Typed warm execution, distributed job definitions, structured progress, and
  dry-run/apply/coverage test evidence.
- Framework-owned durable batch writing with bounded receipt backpressure.
- Compile-time inference for unambiguous query, lookup, window, and warm roles.
- Generated typed route references for warm, coverage, and integration tests.
- Strict coverage-scope validation and aggregate HOT route capacity evidence.
- Explicit timeout-bounded single-command SQL durability helpers.
- Redis-first entity repositories with bounded hot-set policies.
- Compile-time generated `@CacheRepository` implementations for typed commands, hot/source routes, relations, projections, and warm plans.
- Declarative per-entity policy configuration with explicit JDBC registration.
- PostgreSQL and SQL Server durable provider paths selected through exactly one provider starter.
- Two-phase generated JDBC source and relation-loader registration.
- Explicit bounded source routes plus route-derived warm/backfill; no hidden SQL fallback behind Redis misses.
- Projection/read-model recipes for relation-heavy and globally ranked routes.
- Migration Planner flow for schema discovery, warm-up, comparison, and report generation.
- Multi-pod coordination, leader lease, and local Docker HA preflight evidence.
- Declarative periodic warm plans with Redis lease, heartbeat, bounded waiting, and cluster-wide deduplication.
- Incremental policy reconciliation that removes stale, missing, or invalid cache payloads without mutating SQL.
- Generated bounded relation loaders, partitioned sorted indexes, projection records, and strict route contracts.
- Optimistic write receipts, durable parent dependencies, and explicit SQL durability tracking.
- Typed Redis Stream jobs with pod failover, abandoned-work claiming, bounded retries, and idempotent-handler contracts.
- Spring Boot Actuator health for Redis, SQL, write-behind backlog, dead letters, and recovery state.
- PostgreSQL and MSSQL REST samples with Docker Compose, Postman collections, and local hot-route load scripts.
- GitHub Release asset as the official package distribution channel.

### Provider boundaries

- PostgreSQL is the default provider path.
- MSSQL is available as an explicitly selected provider with SQL Server sample and integration evidence.
- SQL Server HA or Always On readiness must be proven in the consuming application's staging topology when that topology is part of the production claim.
- Maven Central is optional for this release because GitHub Release is the selected official distribution channel.

### Production use

Use this release for production-oriented pilots and controlled cutovers only
after every hot route has a route contract, warm-up evidence, side-by-side
comparison, Redis memory budget, and rollback plan.
```

## Publication Checklist

- `pom.xml` and all module parent versions use the stable version.
- Release notes exist at `docs/releases/v0.9.0.md`.
- `mvn -DskipTests package` passes.
- Public API compatibility check passes.
- Turkish documentation quality check passes.
- Local Docker HA preflight passes or the latest CI evidence is green.
- `Public Beta Readiness` and `Production Evidence` are green for the release
  commit.
- `Production GA Release Readiness` is green for `v0.9.0`.
- GitHub Release is not marked as prerelease.
- GitHub Release asset `cache-database-0.9.0-github-release.zip` is attached.
