# Production Evidence Guide

Turkish version: [../tr/docs/production-test-report.md](../tr/docs/production-test-report.md)

This document explains how CacheDB production evidence is produced and how to
interpret it. It does not publish a second readiness verdict. The current
decision source is the [Production Readiness Contract](../PRODUCTION_GA_CRITERIA.md).

## Evidence Lanes

| Lane | Proves | Does not prove |
| --- | --- | --- |
| Full Maven reactor | Compile-time generation, unit and integration contracts, module compatibility | A customer's workload capacity |
| Framework Readiness | Public API, reflection-free rules, docs, package shape, provider and sample parity | Managed infrastructure failover |
| Production Evidence | Redis outage recovery, multi-instance coordination, projection/ranking benchmarks, provider smokes | Application route completeness |
| SQL Server provider evidence | Versioned writes, batching, throughput threshold, restart/reconnect, lock classification, outbox and migration behavior | Every Always On topology |
| Oracle provider evidence | Version-guarded MERGE/delete, query bounds, outbox, schema/migration behavior, delayed network, throughput, restart/reconnect | Every RAC or Data Guard topology |
| Oracle physical Data Guard evidence | Redo transport/apply, broker switchover, forced primary loss, multi-address service-name Hikari recovery, provider behavior after both transitions, and former-primary reinstatement with final no-gap/zero-lag readiness | RAC/SCAN/FAN/FCF, production network policy, zero-RPO, or a consuming application's RTO/RPO |
| Public Maven Repository Publish | Immutable anonymous artifact resolution and checksums | Application cutover readiness |
| `cachedb:certify` | One application's route, parity, memory, failover, canary, and rollback evidence | Another application or environment |

## Local Framework Commands

Use Java 21 through the repository wrapper:

```powershell
pwsh ./tools/build/invoke-maven-semeru.ps1 `
  -WorkingDirectory . `
  -MavenArgs @('-B', 'clean', 'verify')

pwsh ./tools/ci/run-local-docker-ha-preflight.ps1

pwsh ./tools/ci/run-oracle-provider-evidence.ps1 -RestartOracleContainer

pwsh ./tools/ci/run-local-oracle-dataguard-evidence.ps1 `
  -MavenExecutable C:\apache-maven-3.9.9\bin\mvn.cmd
```

The Docker HA preflight starts isolated Redis 8, PostgreSQL 16, and SQL Server
2022 containers. The Oracle command runs the pinned Oracle Database Free 23
provider lane and verifies reconnect after restart. The Data Guard command
requires a preloaded licensed Oracle 19c Enterprise image, 14 GiB of Docker
memory, 6 CPUs and about 30 GiB of free Docker disk. It creates a real physical
standby and runs planned and unplanned
role transitions through a stable HAProxy endpoint. These commands write reports
under `target`; temporary containers are removed unless the corresponding keep
or existing-container option is supplied.

## Performance Gates

- Projection-first, partitioned relation, ranked-window, and summary/detail
  benchmarks use explicit thresholds in CI.
- SQL Server high-volume write evidence reports row count, operation count,
  elapsed time, operations per second, required threshold, and result.
- Oracle version-guarded batch evidence applies the same explicit throughput
  floor and also verifies delayed-network and 1,000-expression-limit safety.
- A threshold result is valid only for its commit, runner, payload, database,
  and configuration. Compare trends on equivalent environments.
- Lowering a threshold to make CI green is not a fix. Investigate SQL round
  trips, lock waits, allocation, connection pool pressure, and batch shape.

## Report Locations

| Report | Location |
| --- | --- |
| Production evidence | `target/cachedb-prodtest-reports/` |
| Redis failover | `target/cachedb-redis-failover-reports/` |
| SQL Server provider | `target/cachedb-mssql-provider-reports/` |
| Oracle provider | `target/cachedb-oracle-provider-reports/` |
| Oracle physical Data Guard | `target/cachedb-local-oracle-dataguard-reports/` |
| Local Docker HA | `target/cachedb-local-docker-ha-reports/` |
| Public Maven resolution | `target/public-maven-repository-summary.md` |
| Application certificate | `target/cachedb-production-certification.md` |

CI artifacts are retained for a limited period. Copy the immutable summaries
used for a release decision into the release evidence location or the consuming
application's certification directory.

## Application Evidence

Framework evidence cannot enumerate an application's screens, APIs, batches,
workers, and reports. Each consuming application must run:

```bash
mvn verify -Pproduction-certification
```

The evidence format and copy-paste Maven profile are documented in
[Production Certification](production-certification.md). Every evidence file
is bound to the exact application commit and staging environment.

## Decision Rule

- Publish a stable framework release only when all mandatory framework lanes
  pass on the exact tag and public artifacts resolve anonymously.
- Cut over an application only when its own certification gate passes with no
  unresolved blocker.
- Re-run evidence after a material workload, payload, network, resource-limit,
  provider, Redis topology, or SQL topology change.
