# CacheDB Project Memory

Last updated: 2026-08-10

Purpose: durable engineering handoff for future work in
`E:\ReactorRepository\cache-database`. Revalidate Git, CI, package, release,
Docker, and runtime state before treating historical evidence as current.

## Repository Family

- Framework: `E:\ReactorRepository\cache-database`
- PostgreSQL sample: `E:\ReactorRepository\sample-cache-database-postgresql`
- MSSQL sample: `E:\ReactorRepository\sample-cache-database-mssql`
- Branch: `main` in all three repositories
- Framework Maven coordinates: `com.reactor.cachedb:*`
- Current source release: `0.7.1`
- Intended stable tag: `v0.7.1`
- Official distribution: GitHub Packages plus the GitHub Release bundle
  `cache-database-0.7.1-github-release.zip`

Do not mix this repository family with
`E:\ReactorRepository\rust-spring-performance` or NMC repositories.

## Product Contract

CacheDB is a Redis-first active-data and read-model framework. The selected SQL
provider remains the durable source of truth.

- Redis serves explicitly bounded operational entity and projection routes.
- Redis misses do not trigger arbitrary hidden SQL scans.
- SQL archive, reporting, audit, export, and full-history reads use explicit
  bounded source routes.
- Existing SQL data enters Redis through explicit warm/backfill plans.
- External SQL writes require outbox/CDC or an explicit reconciliation plan.
- Relation-heavy and global sorted screens use compact projections/read models.
- Hot-set size, page size, source-read limit, relation fan-out, payload budget,
  tenant quota, and queue capacity remain bounded.
- Redis acceptance and durable SQL completion are separate observable events.
- Multi-pod singleton work uses Redis leases; handlers remain idempotent because
  distributed execution is at least once.

## Non-Negotiable Engineering Principles

- No runtime entity discovery through reflection.
- No `Method.invoke` for scheduled warm or repository dispatch.
- Generate codecs, repositories, route contracts, metadata, and Spring adapters
  at compile time.
- Do not introduce N+1 relation loading or unbounded source/cache scans.
- Do not hide SQL fallback behind an entity/projection Redis miss.
- Do not automatically merge partial updates when the current entity is absent
  from Redis.
- Prefer explicit backpressure, retry limits, timeouts, failure classification,
  health, metrics, and operator-visible state.
- Preserve PostgreSQL and MSSQL provider parity at the application contract;
  keep dialect, locking, retry, and topology behavior provider specific.

## 0.7.1 Functional Surface

- Compile-time `@CacheRepository` implementations for hot lookup/window,
  bounded source query, warm route, command, delete, generated ID, projection,
  keyset pagination, and optimistic writes.
- `HotWindow.completeItems()` exposes data only when requested route coverage is
  complete.
- Required but unavailable hot coverage is represented by
  `HotRouteUnavailableException`; samples map it to HTTP 503.
- `CacheDbRepository.updateHot` refuses unsafe partial merge through
  `HotUpdateUnavailableException` when Redis lacks the current row.
- `@CacheScheduledWarm` has source retention and a compile-time processor that
  generates typed direct-call Spring tasks.
- Generic repository fragments are supported without forcing application code
  to depend on generated implementation classes.
- Spring properties fail fast for invalid pool, timeout, lease, retry, queue,
  admin-security, input, and MSSQL timeout settings.
- `cachedb-spring-boot-test` provides assertions, typed durability waiting,
  fault injection, and warm-plus-coverage probes.
- Explicit PostgreSQL and MSSQL provider starters, optional admin starter,
  CacheDB BOM, Maven doctor plugin, and OpenRewrite migration recipes are public
  release artifacts.
- Legacy SQL rows with null or zero entity version normalize to initial version
  1 during first warm; negative or malformed versions fail.

## Sample Contract

Both standalone samples use Java 21 and the published `0.7.1` Maven artifacts.
Their provider-neutral Java surfaces are kept equivalent by CI.

- Application code depends on repository interfaces, not generated internals.
- Every `HotWindow` route has a matching bounded `@WarmRoute`.
- Seed creates durable SQL data but does not claim hot-route coverage.
- Before exact warm coverage, required active routes return HTTP 503.
- Warm jobs are polled to `COMPLETED`; the matching active route then succeeds.
- Postman collections contain ordered seed, archive, warm, status, active-read,
  tuning, and health journeys.
- PostgreSQL and MSSQL sample executable JARs are release assets, not framework
  dependencies.

## 0.7.1 Local Release Evidence

- Semeru OpenJ9 JDK: `D:\Dropbox\java64\Semeru\jdk-21.0.2.13-openj9`
- Clean 20-project reactor: 283 tests, 0 failures, 0 errors, 3 explicit
  topology-gated skips.
- `cachedb-integration-tests`: 90 tests passed.
- `cachedb-production-tests`: 27 tests passed.
- PostgreSQL standalone sample: 8 unit tests plus 1 live provider integration
  test passed.
- MSSQL standalone sample: 8 unit tests plus 1 live provider integration test
  passed.
- OSS packaging produced binary, source, and Javadoc JARs for 16 public modules
  plus the BOM.
- Release ZIP inspection: 276 entries, 48 JARs, 18 POMs, and 16 public artifact
  module directories.
- Public API compatibility, benchmark thresholds, provider parity, Postman,
  English/Turkish documentation, release metadata, framework-principle, sample
  boundary, and whitespace checks passed.

Remote CI, package workflow, tag, release assets, and sample consumer workflows
must still be revalidated live for every release operation.

## Release Order

1. Verify the framework release commit locally.
2. Push framework `main` and wait for Public Beta Readiness and Production
   Evidence on the exact commit.
3. Publish `0.7.1` through GitHub Packages from the exact release ref.
4. Create and push annotated `v0.7.1`.
5. Create the non-prerelease GitHub Release and attach ZIP, public binary JARs,
   BOM POM, and SHA-256 checksums.
6. Build standalone samples against the published remote package.
7. Commit, push, tag, and release both sample repositories with their executable
   JAR and checksum assets.
8. Verify final tag, release, package, workflow, and clean-worktree state.

## Production Boundary

Framework release evidence is not an application cutover certificate. Every
consumer must still prove:

- complete route inventory and coverage
- representative warm/reconciliation behavior
- source-vs-CacheDB membership and ordering parity
- Redis memory and payload budget
- SQL/Redis timeout and pool tuning under its Kubernetes limits
- external-write freshness through outbox/CDC when applicable
- canary, rollback, failure recovery, and application-specific HA topology
- admin UI/API exposure behind gateway authentication or CacheDB token auth

Do not claim generic certification for every managed Redis, PostgreSQL HA, SQL
Server Always On, network, or Kubernetes topology.

## First Files For Future Work

- `README.md`
- `tr/README.md`
- `CHANGELOG.md`
- `docs/releases/v0.7.1.md`
- `tr/docs/releases/v0.7.1.md`
- `docs/framework-ux-10-iteration-report.md`
- `tr/docs/framework-ux-10-iterasyon-raporu.md`
- `PRODUCTION_GA_CRITERIA.md`
- `tools/ci/check-framework-principles.ps1`
- `tools/ci/check-sample-framework-usage.ps1`
- `tools/ci/check-sample-provider-parity.ps1`
- `tools/ci/check-public-api-compatibility.ps1`
- `tools/release/build-release-package.ps1`

## Communication Rules

- Lead with the direct production judgment, then evidence and boundaries.
- Classify alternatives as BEST, ACCEPTABLE, or ANTI-PATTERN when useful.
- Preserve natural Turkish and correct Turkish characters in Turkish docs.
- Do not claim production readiness from compilation or unit tests alone.
- Never overwrite user changes or mix repository families during release work.
