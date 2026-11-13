# Release Checklist

Use this checklist before every public release.

## Repository Hygiene

- confirm [LICENSE](../LICENSE) matches the intended public licensing choice
- review [SECURITY.md](../SECURITY.md), [CONTRIBUTING.md](../CONTRIBUTING.md), [CODE_OF_CONDUCT.md](../CODE_OF_CONDUCT.md), and [SUPPORT.md](../SUPPORT.md)
- confirm issue templates and pull request template still match the current process

## Metadata

- replace placeholder repository metadata in [../pom.xml](../pom.xml):
  - `cachedb.release.projectUrl`
  - `cachedb.release.scmConnection`
  - `cachedb.release.scmDeveloperConnection`
  - `cachedb.release.issueUrl`
  - `cachedb.release.ciUrl`
  - maintainer identity fields
- confirm artifact coordinates and public package names

## Versioning

- move from `-SNAPSHOT` to the intended release version
- update [../CHANGELOG.md](../CHANGELOG.md)
- confirm release status wording in docs: stable core, provider-specific limits,
  and any beta/experimental provider boundaries
- after the release, reopen `main` to the next `-SNAPSHOT` version with [release-flow.md](release-flow.md)

## Evidence

- production evidence workflow is green
- coordination evidence workflow is green
- local Docker or CI outage/restart evidence is green for framework GA
- staging Redis HA workflow is green only when the release claim includes a
  managed Redis topology
- full migration route coverage CSV is required for consuming-application
  cutover, not for the generic framework release
- any new runtime hotspot changes have fresh benchmark or smoke evidence

## 0.19.0 Release Evidence

**UNRELEASED draft: bounded F3 candidate acceptance, November 10, 2026; raw T4 PASS BY ACCEPTANCE EXCEPTION, November 9, 2026.**
Only the raw COUNT/FACET mandatory 2x throughput target (`rawT4target`) is waived. Historical numeric
failures are preserved; correctness, isolation, bounds and other performance gates remain required.
The [release notes](releases/v0.19.0.md) and [upgrade guide](upgrading-to-0.19.md) do not announce
publication or authorize production migration. Keep this status until the exact final-artifact gates close.

Artifact-bound evidence qualifies the declared bounded F3 lifecycle for candidate
`long-acceptance-20261110e` (17 suites, 188/188 tests). The declared four-feature sample scope
(`spring-data-bulk-delete`, `field-encryption`, `retention`, `tenant-purge`) is qualified across
PostgreSQL/MSSQL/Oracle in nine execution cells (27 suites, 141 Java tests, 180 HTTP requests,
381 HTTP assertions; zero failures/skips). The three lanes per provider are `audit-derived-delete`
(including retention), `encrypted-notes` and `tenant-purge`.
This qualification is candidate-bound; final-artifact matching, publication and private evidence
import remain pending.

| Candidate identity | SHA-256 |
| --- | --- |
| Frozen E source manifest | `95bd490a15e4f17f89965667d31635c4a343a671e45ecd1b790c2766a95a6d34` |
| SDK manifest | `7e934d3f27a586104341765752454874a87eb4a04077a124a9acac092620eed2` |
| Independently qualified sample receipt | `03ff2f520571af3ef57e554a793fc589039e0c1fec37c655d5148e4096397f13` |

The native-thread fixture defect was orphaned BusyBox `timeout` watchdogs becoming zombies
under Redis PID 1. Fork-free TCP readiness replaced the exec loop; authenticated Service PING
remains mandatory. NPROC stayed 256/256. The 35m 02.749s F3-plus-sample chain ended with zero
zombies in all three containers and no OOM/PID-limit events; this is not an arbitrary soak guarantee.

E's original wrapper/verifier exit 1 remains recorded: its report path was outside `target`.
A separate frozen-verifier qualification accepted the immutable evidence without replaying tests.
The independent report-path qualification covered `tools/k8s/SampleRuntimeEvidence.java` and
`tools/ci/test-v019-sample-matrix.mjs`; product runtime/SDK bytes did not change in that qualification.
Later documentation, test-fixture and release-tool changes require their own explicit reviewed
source qualification. The earlier two-file qualification does not identify the current tree.
Qualification reports `runtimeQualified=true`, `structurallyComplete=true`, `releaseAccepted=false`.
Frozen candidate hashes do not identify the whole current dirty checkout or a final release commit.

The release-wide requirements below remain open; candidate qualification is not release acceptance.
Candidate-only numeric evidence is scoped in [performance benchmarks](performance-benchmarks.md).

- [ ] Document Java 21 as the minimum supported runtime; verify the existing public config-record shapes remain compatible and new options use CacheRuntimeOptions.
- [ ] Bind final SDK/tool JAR hashes, source/resource manifests, complete raw matrices and reviewed CPU/memory/JVM/Redis controls to the exact release artifact; do not inherit an older candidate's result.
- [ ] Prove read coalescing defaults to false in the final hashed artifact. Retain point1 FAIL_DEFAULT_ENABLED and its measured ratio; require both 64-worker and 1000-VT enabled/disabled throughput gains >=2x with full progress and raw evidence.
- [x] Accept the raw owned/unowned aggregateCount10k and facet10k20Groups item by an explicit release exception to the >=2x throughput target. Preserve all failed measurements; do not claim measured 2x speedup or impossibility. This does not waive final-artifact regression checks or other feature gates. See [performance acceptance](performance-benchmarks.md#raw-t4-release-acceptance).
- [ ] Verify the complete optional-feature matrix: actual Micrometer NOOP versus disabled point p50 <=3%, separately reported real-handler overhead, native async accounting and tracking publication controls. Async/tracking speed is report-only.
- [ ] Verify side-by-side Function registration, old reload and unchanged markers, drift/downgrade rejection and custom-name migration against the [upgrade contract](redis-functions-upgrade.md); never flush shared functions or replace the preserved baseline JAR.
- [ ] Review required Java 21 standalone/Cluster/provider/async/observation/protection suites, skips and capability-qualified JFR evidence separately; a script self-test or numeric pass does not close these requirements.

- [x] Candidate E bounded F3 acceptance: supported CLOSED ownership-preparation and INITIAL profiles, ownership/version inventory, interrupted-page recovery and exact approved runtime enrollment. Preparation does not authorize arbitrary legacy-data migration or open admission.
- [x] Candidate E bounded F3 acceptance: original-generation producer, read/fill, worker and declared-store closure. Missing participants, outstanding deliveries, lost runtime rosters and unverifiable stores remain closed/incomplete; no acknowledgement or inventory may be fabricated.
- [x] Candidate E bounded F3 acceptance: composed PostgreSQL/MSSQL/Oracle INITIAL, real generation-1 SQL flush, source-inclusive purge, explicit EMPTY reopening, fresh exclusive-domain ID admission, separate OPEN version refill and real generation-2 SQL flush. Retained generation-1 replay cannot resurrect SQL/native state; allocators cannot rewind or reuse consumed ranges.
- [x] Candidate E bounded F3 acceptance: supported activation/refill crash recovery, lease handoff and uncertain SQL/native outcomes. Immutable activation proofs and current range/counter checks remain required; missing or rolled-back security metadata requires explicit recovery, not automatic adoption or reopening.
- [ ] Bind this candidate's F3/sample acceptance and every explicitly reviewed subsequent source change to the exact final distribution. Reuse matching immutable evidence; do not reopen unchanged bounded F3 acceptance merely because release-wide gates remain open.

These F3 checks cover the declared bounded standalone profiles, not universal backup restore,
RAC or arbitrary HA topologies. Application-specific restore/cutover qualification remains
separate; unsupported paths stay closed and explicitly documented.
Universal restore and additional topology implementations are outside this release contract,
not pending F3 features.

- [ ] Qualify all eleven release feature areas, Spring Data unknown/zero/exact-cap behavior and declared synchronous Spring Cache limitations against the matching artifacts. Partial sample/provider evidence is not full acceptance.
- [ ] Keep EN/TR release notes, upgrade guides, READMEs, changelog and release history consistent; preserve historical Java requirements and do not substitute targets or older scores for final measured results.
- [ ] Remove UNRELEASED and add a release date/download reference only after release approval and verification of the actual matching published artifacts. Do not guess a future release URL.

## Sample Runtime Evidence Handoff

Use the [operator handoff](../tools/ci/v019-sample-runtime-evidence.md#framework-readiness-operator-handoff)
for `Framework Readiness`. Dispatch with all three coordinator-pinned inputs:
`sampleRuntimeArtifactId` (Actions artifact ID), `sampleRuntimeArtifactSha256`
(artifact ZIP digest) and `sampleRuntimeReceiptSha256` (real producer receipt
digest). Configure `vars.CACHEDB_EVIDENCE_REPOSITORY` (exact `owner/repository`),
`vars.CACHEDB_EVIDENCE_REPOSITORY_ID` and
`secrets.CACHEDB_EVIDENCE_READ_TOKEN`. Repository metadata must confirm the exact
allowlisted name/ID and PRIVATE visibility; the token has no fallback.

The artifact must come from that private source mirror, with its producing
`head_sha` exactly equal to the public dispatch run's `GITHUB_SHA`. An unrelated
evidence repository commit, copied source or matching version is insufficient.
Missing configuration, credentials or valid proof keeps the runtime gate closed;
this checklist does not attest that a mirror or live handoff is available.

Require the final frozen source, its qualified SDK, matching manifests, nine
real lanes and 18 fresh SQL scopes with an exclusive Redis fixture. Synthetic
tooling/control tests are not sample acceptance. Keep raw reports/logs private:
never upload them to public repository artifacts. Store evidence only in the
access-controlled private mirror; metadata checks do not replace operator access
controls. The importer does not re-upload raw reports. Sample coverage
qualification alone is not release approval; keep source feature statuses intact.

## Publishing

- verify the `oss-release` Maven profile builds sources and javadocs
- verify the Linux release package path passes, not only the local developer machine
- confirm the GitHub Release artifact is built from the intended commit and is
  the official distribution package for this release
- confirm signing configuration and secrets for the chosen release channel
- confirm the target release workflow and credentials
- confirm signed publish succeeds before a GA announcement only when Maven
  Central is the selected distribution channel

## GitHub / Repo Settings

- enable private vulnerability reporting
- enable branch protection for the default branch
- enable required CI checks for evidence workflows
- confirm issue labels and milestones are ready for outside contributors

## Documentation

- README and Turkish README are current
- production recipe guidance is current
- any new tuning knobs are documented
- release limitations are explicit and do not overstate provider or topology
  certification
