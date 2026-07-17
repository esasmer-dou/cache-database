# Changelog

All notable changes to `cache-database` will be tracked here.

The format is intentionally simple and release-focused.

## Unreleased

## 0.5.0 - 2026-07-17

### Added

- declarative `@CacheScheduledWarm` methods for cron, fixed-delay, and fixed-rate warm plans
- Redis lease ownership, heartbeat renewal, bounded waiter behavior, and cluster-wide successful-cycle deduplication for multi-pod deployments
- incremental cursor-based hot-set reconciliation that physically removes rows and projections rejected by the current active-data policy
- pod-local scheduled-warm telemetry for execution, skip, failure, cursor, full-cycle, missing-payload, and invalid-payload state
- PostgreSQL and MSSQL sample periodic-warm plans plus `/api/warm/schedules` operational endpoints

### Changed

- production coordination evidence now verifies scheduled-warm lease and heartbeat behavior alongside existing multi-instance workers
- stable release bundles now include the Spring Boot, scheduled-warm, and tuning guides in English and Turkish
- public API baseline includes the additive scheduled-warm and reconciliation surfaces

### Fixed

- invalid Redis entity payloads no longer block reconciliation cursor progress
- a failed lease owner does not commit a successful warm marker; another pod can retry the cycle
- reconciliation removes cache state without creating SQL mutations, tombstones, or write-behind commands

### Validation

- full 13-module reactor: 250 tests, 0 failures, 0 errors, and 12 environment-gated skips; the two Redis-gated coordination tests passed in a dedicated live Redis run, while the remaining 10 tests require the live SQL Server profile
- PostgreSQL and MSSQL samples: 8 tests each, including real Redis 8.2.1 and provider Testcontainers integration
- multi-instance production evidence, public API compatibility, Turkish documentation quality, Postman JSON, and release-package checks passed

## 0.4.1 - 2026-07-15

### Fixed

- restored the pre-0.4.0 Spring auto-configuration `cacheDatabase(...)` factory signature as a delegating compatibility overload
- refreshed the committed public API baseline only after proving that the 0.4.x API delta is additive and removes no prior signature

### Validation

- public API comparison reports 66 additive signature lines and zero removed signature lines
- Spring Boot starter and all upstream modules pass after the compatibility overload is restored

## 0.4.0 - 2026-07-15

### Added

- package-level `GeneratedCacheModule.Scope` as the single typed application surface for entity CRUD, named queries, fetch presets, projections, commands, deletes, and warm plans
- declarative per-entity `CachePolicyCatalog` configuration with composite hot policies, customizers, and unknown-entity validation
- `ProjectionSchema` and typed projection rows/codecs so one schema defines Redis serialization and indexed columns
- explicit dry-run, projection-only, and full warm execution helpers

### Changed

- generated package scopes create entity scopes lazily and safely reuse them across concurrent application requests
- generated JDBC registration runs in two phases so every entity keeps its own policy before relation and page loaders are connected
- PostgreSQL and MSSQL samples use one generated domain bean instead of manual repository and policy factories
- sample cache policies are configured in YAML and the JDBC registration source is selected explicitly
- planner key telemetry uses bounded incremental Redis scans instead of full keyspace scans during startup and health sampling
- Spring Boot keeps `metadata-only` as the backward-compatible registration default; JDBC read-through and warm loading require an explicit `source: jdbc`

### Fixed

- package-level generated modules no longer initialize unrelated repositories when a consumer uses only one entity surface
- repository benchmark adapters can use partial/focused sessions without failing on unrelated generated entities
- monitoring history startup no longer blocks on repeated full Redis keyspace scans
- relation loader construction no longer leaks a parent entity policy into a child repository

### Validation

- full 13-module CacheDB reactor passed on Java 21
- 83 integration tests passed against isolated Redis 8 and PostgreSQL, with two explicitly profile-gated MSSQL tests skipped
- all 27 production benchmark, recovery, coordination, fault-injection, and certification tests passed
- PostgreSQL and MSSQL samples passed unit tests plus live provider integration tests against Redis 8 and their selected SQL provider

## 0.3.2 - 2026-07-14

### Added

- `BigDecimal` support in generated entity codecs and JDBC write conversion
- production-contract PostgreSQL and MSSQL samples with entity-specific hot policies, bounded relation batching, asynchronous warm jobs, and strict route limits
- real Redis 8 plus PostgreSQL/SQL Server provider integration tests for the sample command, readiness, warm, foreign-key, and delete contracts
- CI parity gate that prevents provider-neutral sample Java code from drifting between PostgreSQL and MSSQL variants

### Changed

- sample command endpoints return `202 Accepted` and distinguish Redis command acceptance from SQL durability
- child writes use one indexed durable-parent check and return retryable `409` instead of polling request threads
- sample money fields use exact SQL decimal types and `BigDecimal`; archive pagination uses a stable date-and-id cursor
- load tests wait for asynchronous warm jobs to complete before measuring active routes

### Fixed

- relation preview loaders no longer issue one query per parent
- warm jobs can no longer consume unbounded HTTP worker time or queue capacity
- sample delete no longer implies unsafe aggregate cascade behavior
- sample documentation and Postman flows now match the runtime write, warm, cursor, and projection contracts

### Validation

- full CacheDB reactor tests pass on Java 21
- PostgreSQL sample passes unit tests and real Redis/PostgreSQL integration tests
- MSSQL sample passes unit tests and real Redis/SQL Server integration tests

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
