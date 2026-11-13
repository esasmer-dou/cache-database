# Production GA Release Runbook

This runbook is the mandatory full Production GA release flow.
CacheDB framework GA means the library release is safe to consume with clear
boundaries, green CI evidence, and a documented distribution channel. It does
not mean every consuming application's production topology has already been
certified.

## Non-Negotiable Rule

Numbered framework packages may be distributed without claiming full GA approval.
Use `check-ga-release-readiness.ps1 -EvidenceScope FrameworkPackage` for that
distribution check and disclose missing topology evidence in the release notes.
The default `ProductionGA` scope is stricter: exact-commit physical Data Guard
workflow evidence remains mandatory. A package check does not waive that GA gate.

Do not publish or announce a framework GA release when any of these are still
missing:

- local Docker or CI outage/restart evidence for Redis coordination and SQL
  provider reconnect behavior
- self-hosted physical Data Guard evidence when Oracle support is part of the release
- a documented official distribution path. Current selected path: GitHub
  Release artifact
- signed Maven Central publish with source and javadoc artifacts when Maven
  Central is the selected distribution channel
- green public API compatibility and benchmark regression gates
- explicit admin exposure decision behind gateway auth or CacheDB token auth

For a consuming application, also require full route coverage and real staging
HA evidence before cutting production traffic over to CacheDB.

## Required Repository Secrets

Configure these GitHub repository secrets only for the optional gates you use.
For Maven Central:

```text
CENTRAL_USERNAME
CENTRAL_PASSWORD
GPG_PRIVATE_KEY
GPG_PASSPHRASE
```

For managed staging HA evidence:

```text
STAGING_REDIS_URI
STAGING_POSTGRES_URL
STAGING_POSTGRES_USER
STAGING_POSTGRES_PASSWORD
STAGING_MSSQL_URL
STAGING_MSSQL_USER
STAGING_MSSQL_PASSWORD
STAGING_ORACLE_URL
STAGING_ORACLE_USER
STAGING_ORACLE_PASSWORD
```

Provider staging secrets are mandatory only when the release claim includes the
corresponding managed HA topology. The local physical Data Guard lane may be
reported as representative framework evidence; it is not PostgreSQL HA, SQL
Server Always On, Oracle RAC, or customer-topology certification.

## Staging HA Evidence Boundary

`run-staging-redis-ha-evidence.ps1` and `run-staging-mssql-ha-evidence.ps1` run
two smoke phases separated by `ExternalFailoverWaitSeconds`. They do not collect
or validate an independent witness of a live failover. Neither a positive wait
nor a zero wait proves a topology transition or connection recovery.

The default invocation retains both smoke phases but fails HA qualification,
even if both pass. Every run reports `liveFailoverVerified=false` and
`haQualified=false` in its JSON and summary. The `Production GA Staging Evidence`
workflow deliberately uses this fail-closed default; it cannot currently satisfy
`-RequireManagedStagingHa`. A real topology-specific independent witness and its
validator remain required before this lane can qualify HA. There is no operator
checkbox or caller-supplied success flag that substitutes for that proof.

For explicitly non-qualifying diagnostics only, add `-ReconnectSmokeOnly` to
either script. It succeeds only when both smoke phases pass; it still emits
both HA flags as false and does not establish that a reconnect occurred. Do not
add this switch to an HA-gating caller or treat smoke-only success as GA evidence.

Reports are retained under unique `run-*` directories in
`target/cachedb-staging-ha-reports/` or
`target/cachedb-staging-mssql-ha-reports/`. Earlier reports are not cleared, and
partial smoke outcomes are retained on failure. The workflow publishes summaries
from `run-*/staging-*-ha-evidence.md` and attempts artifact upload with `always()`
after failure. Missing evidence is not an HA pass; artifact transport success is
not HA qualification either.

## Step-by-Step GA Flow

1. Prepare a stable version such as `0.1.0` or `1.0.0`. Do not use `beta`, `alpha`,
   `rc`, `preview`, or `SNAPSHOT` in a GA version.
2. Run the local Docker HA preflight:

   ```powershell
   pwsh ./tools/ci/run-local-docker-ha-preflight.ps1
   ```

   This starts Redis, PostgreSQL, and SQL Server containers, runs Redis
   outage/recovery evidence, and runs SQL Server restart/reconnect evidence.
3. Run the Oracle provider evidence lane with a container restart:

   ```powershell
   pwsh ./tools/ci/run-oracle-provider-evidence.ps1 -RestartOracleContainer
   ```

   This verifies the Oracle JDBC Thin path, version-guarded writes, outbox,
   migration discovery, bounded reads, delayed-network behavior, throughput,
   and reconnect after a single-instance restart. It does not claim RAC or Data
   Guard certification.
4. Run the physical Data Guard lane on a Windows self-hosted runner with the
   licensed Oracle 19c Enterprise image already present:

   ```powershell
   pwsh ./tools/ci/run-local-oracle-dataguard-evidence.ps1 `
     -MavenExecutable C:\apache-maven-3.9.9\bin\mvn.cmd
   ```

   This proves real redo transport/apply, planned broker switchover, forced
   primary loss, stale-connection rejection, Hikari recovery through a
   multi-address Oracle JDBC service-name descriptor, full provider behavior
   after both transitions, and reinstatement of the former primary with final
   no-gap/zero-lag readiness. It does not prove RAC/SCAN/FAN/FCF, zero-RPO, or a
   customer's production network and service policy.
5. If the release includes MSSQL listener/failover claims but the shared staging
   Always On environment cannot be failed over on demand, run the local listener
   preflight:

   ```powershell
   pwsh ./tools/ci/run-local-mssql-listener-failover-evidence.ps1
   ```

   This proves stale JDBC connection invalidation and new-connection recovery
   through a stable listener endpoint. It does not replace a real Always On
   topology test for replication, quorum, or managed failover policy.
6. Push the release commit to `main` and wait for `Framework Readiness` and
   `Production Evidence` to pass on that exact commit.
7. Build the official GitHub Release artifact from the intended commit:

   ```powershell
   pwsh ./tools/release/build-release-package.ps1 `
     -Version 0.1.0 `
     -PackageLabel github-release
   ```

   For stable releases, use a non-beta package label such as `github-release`.
8. Create and push the stable tag, for example `v0.1.0`.
9. If Maven Central is the selected distribution channel, run `Maven Central
   Publish` manually on the stable tag with
   `gaRelease=true`. The workflow runs the GA preflight before deploying signed
   artifacts.
10. Run `Production GA Release Readiness` for the same tag. Enable
   `requireManagedStagingHa`, `requireApplicationMigrationCoverage`, or
   `requireMavenCentralPublish` only when that release claim includes those
   optional gates.
11. Publish the GitHub release only after the readiness summary is `PASS` and
   attach the official release artifact.

## Local Preflight

After the tag exists, an operator can run:

```powershell
pwsh ./tools/ci/check-ga-release-readiness.ps1 `
  -Repository esasmer-dou/cache-database `
  -TargetRef main `
  -ReleaseTag v0.1.0
```

This command checks framework-level GA readiness. Add the optional flags below
only when the release claim requires them:

```powershell
-RequireMavenCentralPublish
-RequireManagedStagingHa
-RequireApplicationMigrationCoverage -CoverageCsvPath docs/ga-migration-coverage.csv
```

To publish signed artifacts after every non-Maven GA gate is green and Maven
Central is the selected distribution channel:

```powershell
gh workflow run maven-central-publish.yml `
  --repo esasmer-dou/cache-database `
  --ref v0.1.0 `
  -f gaRelease=true `
  -f releaseTag=v0.1.0 `
  -f targetRef=main `
  -f migrationCoverageCsvPath=docs/ga-migration-coverage.csv
```

## Kubernetes Acceptance Handoff

For the Kubernetes acceptance lane, run workloads in the approved Kubernetes
namespace. The public `Framework Readiness` job verifies their reports; it does
not rerun the workloads or infer success from an artifact upload.

The contract in `tools/ci/k8s-acceptance-contract.psd1` currently requires 52
acceptance obligations: one SDK build, standalone tests, Cluster tests, sample feature
qualification, three complete provider suites, three negative certification
checks, twelve API baseline cells and thirty repository controls. A feature smoke
test does not replace a provider suite or the API baseline. Performance and HA
qualification remain separate gates.

The package control (`control/17`) may reference the original `sdk-build`
execution instead of requiring a second build. That reference contains only
`id`, `executionRef` and `canonicalCommand`; it cannot override process evidence.
All other obligations require separate executions. Preserve the actual command
and environment evidence: a canonical command describes the obligation, not the
full invocation. An offline build needs separately approved, sealed invocation
evidence; matching artifact hashes alone do not approve a different command.

1. Retain each process's original command, working directory, log, exit file,
   timestamps and pod UID. Record the source SHA and SDK manifest used by that
   process. Preserve the before/after source and SDK manifests. Do not fill gaps
   with another package's results or extend an expired run's cutoff.
2. Supply an explicit `cachedb.k8s-acceptance-input/v1` inventory with `source`,
   `sdk`, `producer`, `runs`, `executions` and `files`. Each file entry contains
   its relative path, byte length and SHA-256. Producer IDs must come from the
   real protected workflow, not an example or an invented identifier.
3. Assemble a new output directory from the retained evidence:

   ```powershell
   $inventory = './target/runtime-evidence/execution-inventory.json'
   $pin = (Get-FileHash -LiteralPath $inventory -Algorithm SHA256).Hash.ToLowerInvariant()
   pwsh ./tools/ci/new-k8s-acceptance-receipt.ps1 `
     -InventoryPath $inventory `
     -InventorySha256 $pin `
     -EvidenceRoot ./target/runtime-evidence `
     -OutputDirectory ./target/k8s-acceptance
   ```

   The inventory must already exist. This command neither executes tests nor
   creates missing evidence. It rejects missing executions, changed bytes,
   reused process logs and invalid deadlines. It preserves the original
   inventory in `origin/execution-inventory.json`. Its successful status is
   `ASSEMBLED_NOT_RUNTIME_ACCEPTANCE`, not release approval. Hashing a local
   inventory seals its bytes; it does not authenticate the producer.
4. Publish the assembled evidence through the approved private producer and
   configure the public repository's `CACHEDB_K8S_ACCEPTANCE_ARTIFACT_ID`,
   `CACHEDB_K8S_ACCEPTANCE_ARCHIVE_SHA256`,
   `CACHEDB_K8S_ACCEPTANCE_RECEIPT_SHA256`,
   `CACHEDB_K8S_ACCEPTANCE_SDK_SHA256` and
   `CACHEDB_K8S_ACCEPTANCE_PRODUCER_WORKFLOW` variables with the actual values.
   Keep the repository identity and read-only credential configured separately.
5. Let the protected public workflow run `check-k8s-acceptance.ps1`. It verifies
   producer authority, source/SDK identity and the required report contents.
   `K8S_ACCEPTANCE_VERIFIED` closes this gate only; it does not publish a release
   or waive the remaining GA checks. A missing report, failed process or
   untrusted producer keeps the gate closed.

## Production Decision

BEST: release GA only after `Production GA Release Readiness` is green and the
GitHub Release artifact is attached as the official distribution package.

ACCEPTABLE: release framework GA with Docker/CI outage evidence and clear
boundaries, while individual applications still run route-level coverage,
rollback, and staging HA evidence before cutover.

ANTI-PATTERN: rename a beta build to GA because the unit tests, local Docker
tests, or the framework readiness workflow passed.
