# Changelog

All notable changes to `cache-database` will be tracked here.

The format is intentionally simple and release-focused.

## Unreleased

## 0.3.1 - 2026-07-14

### Fixed

- version-guarded SQL flushers now treat an already persisted equal or newer
  version as a durable idempotent/stale no-op instead of a write failure
- MSSQL concurrent same-id upserts and high-volume stale batches no longer
  create poison/retry work when the database already contains newer state
- zero-row outcomes still fail when the durable database state is older than
  the incoming command or otherwise does not satisfy the requested operation

### Validation

- local Docker Redis outage/recovery evidence passed
- live SQL Server provider evidence passed before and after container restart
- PostgreSQL and MSSQL sample consumer builds pull the published package through
  GitHub Packages on clean GitHub runners

## 0.3.0 - 2026-07-14

### Added

- version-aware JDBC source loaders and atomic Redis hydration for warm, read-through, and external-change paths
- bounded query candidate materialization, JDBC read/write timeouts, bounded admin queues, and cross-pod Redis job status
- explicit stale-write and Redis backpressure failure categories
- focused Docker integration coverage for stale hydration, tenant admission races, function deployment, and admin body limits

### Changed

- durable compaction state and entity version fences no longer expire; the durable compaction stream is not trimmed before acknowledgement
- prefix and token indexes are opt-in; broad indexed and degraded-scan candidate sets fail before large JVM materialization
- tenant row and payload admission is serialized per tenant across pods, while cache contention does not reject the durable business write
- repository graphs and owned Redis clients are reused and closed deterministically
- Redis Function deployment is lease-protected, versioned, and rejects same-version source drift or downgrade
- public API additions are backward-compatible: previous config, registry, Redis function, and repository signatures remain as delegating overloads

### Fixed

- stale warm or CDC events cannot overwrite newer Redis state or resurrect a newer tombstone
- zero-row SQL outcomes are no longer acknowledged as successful without checking the persisted version
- partial page/index payload loss no longer returns incomplete query results silently
- rejected admin jobs no longer remain indefinitely visible as queued
- concurrent tenant admission can no longer re-add an entity after its reservation was evicted

## 0.1.0 - 2026-06-14

### Release Status

- first non-beta CacheDB framework release
- core Redis-first data-layer model and default PostgreSQL provider path are
  positioned as stable with explicit production boundaries
- MSSQL remains an explicitly selected beta provider, not a GA provider claim

### Added

- stable release notes at `docs/releases/v0.1.0.md`
- stable release launch kit in English and Turkish
- GitHub Release artifact packaging with non-beta `github-release` label

### Changed

- root and module Maven versions moved from `0.1.0-beta.4` to `0.1.0`
- main onboarding docs now use stable version examples
- release guidance now treats GitHub Release artifact as the selected official
  distribution channel for `v0.1.0`

### Notes

- consuming applications still need route inventory, warm-up, side-by-side
  comparison, Redis memory budget, rollback planning, and environment-specific
  HA evidence before production cutover

## 0.1.0-beta.4 - 2026-06-14

### Added

- explicit storage-provider SPI with shared JDBC support plus PostgreSQL and MSSQL provider modules
- `cachedb-storage-mssql` beta provider with `MssqlWriteBehindFlusher`, `MssqlDatabaseDialect`, `MssqlFailureClassifier`, and `MssqlOutboxExternalChangeFeedAdapter`
- live MSSQL provider evidence lane covering write-behind, outbox checkpointing, migration SQL smoke, multi-pod apply-runner locking, and SQL Server restart/reconnect behavior
- release bundle support for `cachedb-storage-jdbc` and `cachedb-storage-mssql` artifacts

### Changed

- documentation now describes CacheDB as Redis-first with a selected durable SQL provider instead of PostgreSQL-only
- public beta release guidance now positions PostgreSQL as the default provider and MSSQL as an explicit beta provider
- publish tooling defaults to `main` instead of creating a `codex/*` release branch
- root Maven description now uses provider-neutral durable SQL wording

### Fixed

- package generation no longer omits the JDBC/MSSQL storage artifacts needed by MSSQL beta users
- remaining PostgreSQL-only wording in English and Turkish user documentation was replaced with provider-aware language

## 0.1.0-beta.3 - 2026-06-04

### Added

- production scenario certification lane for strict projection contracts, tenant quota, payload-level memory accounting, and PostgreSQL outbox polling
- concrete `PostgresOutboxExternalChangeFeedAdapter` with checkpointed polling for outbox/CDC migration paths
- route-level cache contracts with tenant quota context for production hot routes
- composite hot policy model for count, time, state, and custom predicate admission rules
- migration warm checkpoint/resume support and Redis memory calibration output
- cache admission telemetry surfaced through storage performance snapshots

### Changed

- tenant memory budget now counts measured Redis entity payload bytes instead of only tenant tracking keys
- production evidence workflow now runs against explicit Redis/PostgreSQL service containers
- read-shape benchmark gates now keep structural materialization checks authoritative and avoid failing on unstable microbenchmark fastest-shape noise
- English and Turkish production recipes now document route contracts, payload-level tenant budget, and concrete outbox adapter usage

### Fixed

- tenant quota accounting no longer double-counts repeated writes of the same entity
- warm batch hydration now carries tenant columns and payload estimates into admission tracking
- release bundle script now copies release notes for the selected version instead of a hard-coded beta note

## 0.1.0-beta.2 - 2026-05-19

### Added

- migration planner schema discovery for PostgreSQL tables, primary keys, foreign keys, sort candidates, and route suggestions
- migration planner scaffold generation for `@CacheEntity`, relation loader, projection support, and generated binding usage
- dry-run and staging warm execution for selected Redis hot windows
- side-by-side PostgreSQL vs CacheDB comparison with parity, latency, route-label, and readiness assessment
- downloadable migration report content with cutover action plan, blockers, rollback notes, and coverage guidance
- admin UI category map with simpler navigation for health, operations, migrations, projections, runtime, and evidence
- richer English and Turkish onboarding docs with use-case driven setup guidance

### Changed

- migration planner UX now favors discovery-first route selection and clearer step-by-step execution
- relation-heavy and global sorted guidance now points users toward projection/read-model and ranked projection paths earlier
- demo documentation now describes exact button order for load testing, demo schema bootstrap, warm, compare, and report download
- Spring Boot starter docs now clarify when `spring-boot-starter-jdbc` is required and when an existing JPA `DataSource` is enough

### Fixed

- migration comparison can use registered projection routes instead of falling back to full entity routes for projection-required shapes
- migration warm and comparison paths surface clearer errors when selected entities are not registered
- Turkish docs were cleaned across the main onboarding path for clearer wording and correct Turkish characters

## 0.1.0-beta.1 - 2026-04-11

### Added

- official production evidence workflow and coordination evidence lane
- compile-time generated domain-module ergonomics and zero-glue starter registration
- ranked projection benchmark and multi-instance coordination smoke evidence
- public-beta repo hygiene package, release checklist, and community templates

### Changed

- relation-heavy read recipes now favor summary/detail and ranked projection paths
- Kubernetes-style runtime coordination now uses instance-aware consumer names and leader leases for singleton-style loops

### Notes

- `cache-database` is currently positioned for public beta, not GA
- projection/read-model discipline remains part of the intended production design
