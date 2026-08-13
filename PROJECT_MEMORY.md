# CacheDB Project Memory

Last updated: 2026-08-13

This is the durable handoff for `E:\ReactorRepository\cache-database` and its
two standalone sample repositories. Revalidate Git, CI, release, Docker, and
runtime state before treating a previous run as current evidence.

## Repository Family

- Framework: `E:\ReactorRepository\cache-database`
- PostgreSQL sample: `E:\ReactorRepository\sample-cache-database-postgresql`
- MSSQL sample: `E:\ReactorRepository\sample-cache-database-mssql`
- Branch: `main` in all three repositories
- Framework coordinates: `com.reactor.cachedb:*`
- Current stable source version: `0.10.1`
- Official distribution: anonymous public Maven repository and GitHub Release
- Public Maven URL: `https://esasmer-dou.github.io/cache-database/maven2`
- GitHub Packages is an optional authenticated compatibility mirror

Do not mix this repository family with NMC or
`E:\ReactorRepository\rust-spring-performance` work.

## Product Contract

CacheDB is a Redis-first active-data and read-model framework. PostgreSQL or
SQL Server remains the durable source of truth.

- Redis serves explicitly bounded operational entity and projection routes.
- A Redis miss never hides an arbitrary SQL scan.
- Archive, reporting, audit, export, and complete-history queries use explicit,
  bounded source routes.
- Existing SQL data enters Redis through warm/backfill and reconciliation.
- External SQL writes require outbox/CDC or an explicit reconciliation plan.
- Relation-heavy and globally sorted screens use compact projections.
- Active windows, pages, source reads, fan-out, payloads, tenant quotas, pools,
  queues, retries, and timeouts remain bounded.
- Redis command acceptance and durable SQL completion are separate observable
  states.
- Multi-pod singleton work uses Redis leases and idempotent handlers.

## Non-Negotiable Engineering Principles

- No runtime entity discovery through reflection or `Method.invoke` dispatch.
- Generate codecs, repositories, route contracts, metadata, and Spring
  adapters at compile time.
- Do not introduce N+1 relation loading or unbounded source/cache scans.
- Do not automatically merge partial updates when Redis lacks the current row.
- Preserve explicit backpressure, retries, timeouts, health, metrics, failure
  classification, and operator-visible state.
- Preserve PostgreSQL and MSSQL application-contract parity while keeping
  dialect, locking, batching, and topology behavior provider-specific.

## 0.10.1 Functional Surface

- Compile-time generated repositories, codecs, indexes, route contracts,
  projection bindings, Spring beans, and scheduled warm tasks.
- Bounded active routes, explicit source routes, typed warm/backfill,
  reconciliation, route coverage, and cursor-safe pagination.
- Compile-ready migration scaffolds with actual projection records, generated
  bindings, and partitioned relation loaders.
- PostgreSQL and MSSQL provider starters with provider-specific retry, timeout,
  locking, idempotency, and write-behind behavior.
- MSSQL same-shape upserts use batch update, one bounded locked version probe,
  and batch insert for missing rows; the SQL Server parameter limit is enforced.
- Redis leases coordinate scheduled jobs across pods.
- `cachedb-maven-plugin:certify` fails a consuming build when route inventory,
  parity, memory, failover, canary, or rollback evidence is missing or stale.
- `cachedb-spring-boot-test` provides durability, warm coverage, and fault
  injection helpers.
- The Maven BOM, provider starters, Maven plugin, migration recipes, sources,
  Javadocs, checksums, and release bundle are public release artifacts.

## Sample Contract

Both standalone samples use Java 21 and immutable `0.10.1` artifacts from the
anonymous public Maven repository.

- Application code depends on repository interfaces, not generated internals.
- Every required active route has a matching bounded warm route.
- Seed creates durable SQL data but does not claim active-route coverage.
- Required routes return HTTP 503 until exact warm coverage is complete.
- Postman flows cover seed, source read, warm, status, active read, tuning, and
  health.
- The `production-certification` Maven profile is present but accepts only real
  application staging evidence; sample placeholders are never treated as proof.

## Production Evidence Boundary

Framework CI and Docker tests certify framework behavior, not every customer
topology. Every consuming application must run `mvn verify
-Pproduction-certification` with evidence bound to the exact application
commit and staging environment. The required contract is authoritative in:

- `PRODUCTION_GA_CRITERIA.md`
- `docs/production-certification.md`
- `tr/docs/production-sertifikasi.md`
- `docs/production-test-report.md`
- `tr/docs/production-test-report.md`

The application evidence must cover every screen, API, batch, worker, and
report route; warm/reconciliation; SQL-to-CacheDB membership and ordering
parity; Redis memory; SQL/Redis failover; canary; rollback; and recovery.

## Release Order

1. Run the complete reactor, documentation, artifact, compatibility, sample,
   Docker Redis outage, PostgreSQL, and MSSQL evidence gates.
2. Push the exact framework release commit and require successful Framework
   Readiness and Production Evidence workflows for that SHA.
3. Create and push the stable `v0.10.1` tag.
4. Publish the immutable anonymous Maven2 repository and verify clean anonymous
   resolution of BOM, provider starters, Maven plugin, and core JAR.
5. Publish the non-prerelease GitHub Release and compatibility package mirror.
6. Build both standalone samples from the remote anonymous repository, then
   push, tag, and release them.
7. Verify tags, releases, assets, workflows, anonymous dependency resolution,
   and clean worktrees in all three repositories.

## First Files For Future Work

- `README.md`
- `tr/README.md`
- `CHANGELOG.md`
- `PRODUCTION_GA_CRITERIA.md`
- `docs/releases/v0.10.1.md`
- `tr/docs/releases/v0.10.1.md`
- `docs/production-certification.md`
- `tr/docs/production-sertifikasi.md`
- `tools/ci/check-ga-release-readiness.ps1`
- `tools/ci/run-local-docker-ha-preflight.ps1`
- `tools/release/build-public-maven-repository.ps1`
- `tools/release/build-release-package.ps1`

## Communication Rules

- Lead with the production judgment, then evidence and boundaries.
- Use natural Turkish with correct Turkish characters in Turkish documents.
- Do not claim production readiness from compilation or unit tests alone.
- Never overwrite user changes or mix repository families.
