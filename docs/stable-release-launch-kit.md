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

For `v0.2.0`, the official distribution channel is the GitHub Release asset:

```text
cache-database-0.2.0-github-release.zip
```

The bundle contains the Maven module jars, source jars, javadocs, README,
security/community files, English docs, and Turkish docs. Maven Central is not
required for this release because GitHub Release is the selected official
package distribution channel.

## Release Positioning

`cache-database v0.2.0`

CacheDB `v0.2.0` is a stable framework release focused on explicit active-route
adoption, JDBC-backed warm/read-through, PostgreSQL and MSSQL sample coverage,
and deterministic sample load gates. It keeps the Redis-first data-layer model,
compile-time generated APIs, bounded hot-set policies, and projection/read-model
guidance from `v0.1.0`, then tightens the SQL provider path used by migration
and sample projects.

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
## cache-database v0.2.0

This stable release improves the practical migration path for existing SQL-backed applications.

### What is stable

- Redis-first entity repositories with bounded hot-set policies.
- Compile-time generated metadata and ORM-like APIs.
- PostgreSQL and explicitly selected MSSQL durable SQL provider paths.
- JDBC-backed generated bindings through `registerJdbcBacked(...)`.
- Controlled read-through and warm/backfill through route-shaped query loaders.
- Projection/read-model recipes for relation-heavy and globally ranked routes.
- Migration Planner flow for schema discovery, warm-up, comparison, and report generation.
- Multi-pod coordination, leader lease, and local Docker HA preflight evidence.
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
- Release notes exist at `docs/releases/v0.2.0.md`.
- `mvn -DskipTests package` passes.
- Public API compatibility check passes.
- Turkish documentation quality check passes.
- Local Docker HA preflight passes or the latest CI evidence is green.
- `Public Beta Readiness` and `Production Evidence` are green for the release
  commit.
- `Production GA Release Readiness` is green for `v0.2.0`.
- GitHub Release is not marked as prerelease.
- GitHub Release asset `cache-database-0.2.0-github-release.zip` is attached.
