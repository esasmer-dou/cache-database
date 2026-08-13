# Production Readiness Contract

Turkish version: [tr/docs/production-olgunlugu.md](tr/docs/production-olgunlugu.md)

This is the authoritative maturity and go/no-go document for CacheDB. Other
readiness pages link here and must not maintain a second status list.

## Product Decision

CacheDB is a stable, production-capable Redis-first persistence and read-model
framework for explicitly bounded routes. It is not a transparent cache and it
is not a general SQL fallback layer.

Framework release readiness and application cutover readiness are separate
decisions:

| Decision | Owner | Required result |
| --- | --- | --- |
| Publish a stable CacheDB release | CacheDB maintainers | Every framework gate below passes on the exact immutable tag. |
| Send an application route to CacheDB | Consuming application team | `cachedb:certify` passes with that application's staging evidence. |
| Claim a managed Redis or SQL HA topology | Consuming application/platform team | Failover is triggered and verified in the actual staging topology. |

Passing the framework release gate never creates application-specific route,
capacity, failover, canary, or rollback evidence.

## Stable Framework Release Gates

| Gate | Mandatory evidence |
| --- | --- |
| Correctness and compatibility | Full reactor tests, public API baseline, generated-code compilation, provider parity, stale-write and replay checks pass. |
| Redis coordination | Multi-pod consumer identity, leader lease, pending claim, outage recovery, retry and DLQ evidence pass. |
| PostgreSQL provider | Write-behind, source route, warm, outbox/checkpoint and sample-provider evidence pass. |
| SQL Server provider | Version-guarded batch write, throughput threshold, restart/reconnect, lock classification, outbox/checkpoint, migration and multi-pod apply evidence pass. |
| Read performance | Projection-first, partitioned relation top-N and ranked-window benchmark thresholds pass. |
| Migration tooling | Discovery produces compile-time projection records and partitioned relation loaders; warm, parity, memory and report tests pass. |
| Operations | Admin exposure is opt-in; metrics, backlog, retry, DLQ, projection lag, memory pressure and reconciliation state remain observable. |
| Distribution | Binary, source, Javadoc, POM, BOM, checksums and release bundle are immutable; anonymous Maven resolution and GitHub Release verification pass. |
| Documentation | English and Turkish entry points, release notes, examples and public-link checks pass. |

The `Framework Readiness`, `Production Evidence`, `Public Maven Repository
Publish`, and `Production GA Release Readiness` workflows enforce these gates.

## Application Production Certificate

Every consuming application keeps a `cachedb-certification` directory and runs:

```bash
mvn verify -Pproduction-certification
```

The command fails unless all of the following are present and consistent:

- complete route inventory covering screens, APIs, batches, workers and reports
- an explicit CacheDB shape for every route
- warm and source-vs-CacheDB parity evidence
- Redis memory-budget evidence
- Redis and selected SQL provider failover evidence
- canary evidence
- tested rollback evidence
- no unresolved blocker
- manifest route count equal to the coverage CSV route count

See [Production Certification](docs/production-certification.md) for the
copy-paste layout and Maven configuration.

## Deployment Boundaries

These are deployment responsibilities, not unfinished framework features:

- CacheDB cannot trigger or certify a customer's managed Redis failover, SQL
  Server Always On failover, PostgreSQL HA failover, gateway policy, VPN or
  Kubernetes topology from the library repository.
- External writes to SQL require outbox/CDC or an explicitly measured
  reconciliation route.
- Redis acceptance and durable SQL completion are separate observable events.
- Archive, reporting, export and unbounded history remain explicit SQL routes.
- A materially different workload, payload, network path or resource limit
  requires a new application certificate.

## Go/No-Go Rule

**GO for a framework release:** every stable framework release gate passes on
the exact tag and all published artifacts resolve anonymously with matching
checksums.

**GO for an application cutover:** the framework version is stable,
`cachedb:certify` passes against representative staging evidence, and the team
has approved the generated report.

**NO-GO:** any skipped mandatory framework gate, incomplete route inventory,
missing evidence file, parity mismatch, exceeded Redis budget, unresolved
blocker, failed failover, or untested rollback.
