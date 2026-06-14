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

For `v0.1.0`, the official distribution channel is the GitHub Release asset:

```text
cache-database-0.1.0-github-release.zip
```

The bundle contains the Maven module jars, source jars, javadocs, README,
security/community files, English docs, and Turkish docs. Maven Central is not
required for this release because GitHub Release is the selected official
package distribution channel.

## Release Positioning

`cache-database v0.1.0`

CacheDB `v0.1.0` is the first non-beta framework release. It stabilizes the
core Redis-first data-layer model, compile-time generated APIs, bounded hot-set
policies, projection/read-model guidance, PostgreSQL default provider path, and
production evidence gates.

This release does not claim that every consuming application can cut production
traffic over without its own validation. Before cutover, each application still
needs route inventory, warm-up, side-by-side comparison, Redis memory budgets,
rollback planning, and environment-specific HA evidence.

MSSQL remains an explicitly selected beta provider. It has live SQL Server
evidence, restart/reconnect checks, outbox/checkpoint support, and migration
planner coverage, but it is not yet a GA provider claim for SQL Server HA or
Always On topologies.

## Release Notes Template

```markdown
## cache-database v0.1.0

This is the first non-beta CacheDB framework release.

### What is stable

- Redis-first entity repositories with bounded hot-set policies.
- Compile-time generated metadata and ORM-like APIs.
- PostgreSQL as the default durable SQL provider.
- Projection/read-model recipes for relation-heavy and globally ranked routes.
- Migration Planner flow for schema discovery, warm-up, comparison, and report generation.
- Multi-pod coordination, leader lease, and local Docker HA preflight evidence.
- GitHub Release asset as the official package distribution channel.

### Provider boundaries

- PostgreSQL is the default stable provider path.
- MSSQL is available as an explicitly selected beta provider.
- Maven Central is optional for this release because GitHub Release is the selected official distribution channel.

### Production use

Use this release for production-oriented pilots and controlled cutovers only
after every hot route has a route contract, warm-up evidence, side-by-side
comparison, Redis memory budget, and rollback plan.
```

## Publication Checklist

- `pom.xml` and all module parent versions use the stable version.
- Release notes exist at `docs/releases/v0.1.0.md`.
- `mvn -DskipTests package` passes.
- Public API compatibility check passes.
- Turkish documentation quality check passes.
- Local Docker HA preflight passes or the latest CI evidence is green.
- `Public Beta Readiness` and `Production Evidence` are green for the release
  commit.
- `Production GA Release Readiness` is green for `v0.1.0`.
- GitHub Release is not marked as prerelease.
- GitHub Release asset `cache-database-0.1.0-github-release.zip` is attached.
