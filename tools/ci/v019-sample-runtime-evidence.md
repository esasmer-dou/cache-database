# Final v0.19 sample runtime evidence

## Qualification Boundary

The source manifests deliberately keep four features at
`SOURCE_IMPLEMENTED_RUNTIME_UNQUALIFIED` with null evidence. Do not promote those
declarations to PASS. The checker can instead bind a separate, coordinator-pinned
final receipt after validating raw evidence. No receipt means the existing static
coverage and twelve deferred provider/feature rows remain unchanged; exit is 1.
Invalid evidence also exits 1 and cannot publish partial runtime qualification.

Qualification covers only these independent lanes, on each of PostgreSQL, MSSQL
and Oracle. Audit is shared by bulk-delete and retention, not run twice.

| Lane | Exact JUnit suites and cases per provider | Independent HTTP |
| --- | --- | --- |
| audit-derived-delete | AuditContractTest 8; AuditHttpContractTest 6; AuditExternalIT 5; DerivedDeleteRangeExternalIT 2 | audit collection: 42 requests |
| encrypted-notes | EncryptedNoteContractTest 5; EncryptedNoteExternalIT 2 | encryption collection: 10 requests |
| tenant-purge | TenantPurgeOptInTest 7; TenantPurgeContractTest 10; TenantPurgeExternalIT 1 | tenant-purge collection: 8 requests, 36 assertions |

Required totals: **9 provider/lane cells, 27 JUnit reports, 138 Java tests, 180
independent HTTP requests**. Exact Java method names and collection assertion
membership come from the frozen sources, not totals supplied by a receipt. No
failure, error, skip, flaky or rerun evidence is accepted. HTTP order and assertions
must match the saved collection. Audit/encryption require Newman JSON **and** JUnit;
purge uses its existing JUnit-only reporter, without inventing an absent JSON file.

This is not baseline business/range HTTP acceptance, performance qualification,
protected-source encryption qualification, EMPTY/recovery/HA proof or full release
approval. Encryption remains the sample's REDIS_ONLY scope with intentional SQL
plaintext. Purge retains CLOSED. The checker reports `releaseAccepted: false` even
when these four features qualify.

## Immutable Receipt Schema 1

The receipt's containing directory is the **evidence root**. Every `ref` below is
exactly `{ "path": "relative/under/root", "sha256": "lowercase-64-hex" }`.
Absolute paths, backslashes, drive paths, dot segments, symlinks, reused phase
files/hardlinks, missing files and digest mismatches are rejected. Do not modify
the retained evidence to satisfy the checker; construct a new final receipt and
pin its hash independently. No PASS/runtimeQualified boolean is supported.

```json
{
  "schema": 1,
  "kind": "cachedb-v019-sample-runtime",
  "source": { "root": "source", "manifest": { "path": "source.sha256", "sha256": "<64 hex>" } },
  "sdk": {
    "root": "sdk",
    "manifest": { "path": "sdk.sha256", "sha256": "<64 hex>" },
    "sourceManifestSha256": "<same source manifest hash>"
  },
  "execution": {
    "runId": "<unique coordinator run ID, 8..128 letters/digits/_/->",
    "namespace": "cachedb-<owned namespace>",
    "runnerPodUid": "<original UUID>",
    "startedAt": "2026-11-07T00:00:00Z",
    "finishedAt": "2026-11-07T01:00:00Z",
    "cutoffAt": "2026-11-07T01:00:01Z",
    "cpuMillicores": 2000,
    "memoryBytes": 4294967296
  },
  "cells": [
    {
      "provider": "postgresql",
      "lane": "audit-derived-delete",
      "sourceManifestSha256": "<source manifest hash>",
      "sdkManifestSha256": "<SDK manifest hash>",
      "tests": {
        "fixtureId": "<SHA256 of opaque fresh fixture identity>",
        "startedAt": "2026-11-07T00:00:01Z",
        "finishedAt": "2026-11-07T00:01:01Z",
        "exit": { "path": "postgresql/audit/tests/run.exit", "sha256": "<64 hex>" },
        "preservationExit": { "path": "postgresql/audit/tests/preservation.exit", "sha256": "<64 hex>" },
        "sourceBefore": { "path": "postgresql/audit/tests/source-before.sha256", "sha256": "<source manifest hash>" },
        "sourceAfter": { "path": "postgresql/audit/tests/source-after.sha256", "sha256": "<source manifest hash>" },
        "sdkBefore": { "path": "postgresql/audit/tests/sdk-before.sha256", "sha256": "<SDK manifest hash>" },
        "sdkAfter": { "path": "postgresql/audit/tests/sdk-after.sha256", "sha256": "<SDK manifest hash>" },
        "reports": [ { "path": "postgresql/audit/tests/TEST-com.example.cachedb.sample.audit.AuditContractTest.xml", "sha256": "<64 hex>" } ]
      },
      "http": {
        "fixtureId": "<different fresh fixture identity SHA256>",
        "startedAt": "2026-11-07T00:01:02Z",
        "finishedAt": "2026-11-07T00:02:02Z",
        "exit": { "path": "postgresql/audit/http/run.exit", "sha256": "<64 hex>" },
        "preservationExit": { "path": "postgresql/audit/http/preservation.exit", "sha256": "<64 hex>" },
        "sourceBefore": { "path": "postgresql/audit/http/source-before.sha256", "sha256": "<source manifest hash>" },
        "sourceAfter": { "path": "postgresql/audit/http/source-after.sha256", "sha256": "<source manifest hash>" },
        "sdkBefore": { "path": "postgresql/audit/http/sdk-before.sha256", "sha256": "<SDK manifest hash>" },
        "sdkAfter": { "path": "postgresql/audit/http/sdk-after.sha256", "sha256": "<SDK manifest hash>" },
        "junit": { "path": "postgresql/audit/http/newman.xml", "sha256": "<64 hex>" },
        "newman": { "path": "postgresql/audit/http/newman.json", "sha256": "<64 hex>" }
      }
    }
  ]
}
```

The example intentionally abbreviates the nine cells and four audit JUnit refs;
it is a schema illustration, not executable acceptance evidence. No extra keys
are permitted in the receipt, cell, phase or reference objects. The `http` object
for `tenant-purge` must **omit** `newman`. Every TEST/HTTP phase has a distinct
fixture identity, including across providers and lanes (18 identities). Each phase
must finish within 900 seconds and inside the original execution/cutoff. Both exit
files must contain only `0` with an optional line ending.

Both manifests use byte-exact `sha256sum` format: `hash  relative/path` (or binary
`hash *relative/path`). Source entries are relative to `source.root`, must match
the current `--root` checkout, and must include all reactor POMs and module `src`
trees; all three sample `src`, `scripts`, `postman` trees; the checker, PowerShell
wrapper, verifier, both checker test scripts; and every additional input read by
static validation. Every executable module must have a non-symlink `src` directory
containing at least one regular file, including in nested directories.
For `packaging=pom` modules only (for example `cachedb-bom`), `src` may be absent;
if present, its files must still be retained. Sample `src`, `scripts` and `postman`
directories are always mandatory and must each contain at least one regular file.
A source tree cannot be omitted from both the
checkout and its manifest to bypass the completeness check.
The source and SDK root directories must be disjoint. Unknown or missing SDK
members fail: the complete v0.19.0 Maven roster is derived from root reactor POMs
(POMs, main JARs, sources and Javadoc; integration-tests has no classifiers).
`sdk.sourceManifestSha256` binds that SDK build to the frozen source manifest.
Manifests before/after every phase must be byte-identical to the final manifests,
but retained at distinct file paths. All referenced bytes and live source bytes
are checked again before qualification returns.

Budgets: receipt 1 MiB; manifests 2 MiB/10,000 members; each XML report 16 MiB;
Newman JSON 32 MiB; individual source/SDK members 64 MiB; at most 20,000 evidence
files and 2 GiB combined. XML is bounded and rejects DTD/external entities.

The external coordinator's independently supplied receipt hash is the provenance
trust anchor. It must attest the real pinned SDK was built/consumed and the
recorded fresh fixtures, namespace, pod UID, resources and **original** cutoff
belong to the completed run. A self-hashed folder cannot prove Kubernetes origin,
freshness or execution of its reports. The verifier checks artifacts and their
binding; it does not create, renew or adopt execution authority. Preserve raw
reports privately: Newman reports may contain credentials or application data.

## Coordinator Commands (K8s Only)

Run the bounded verifier fixtures on the approved EmaTech runner; no product
services are contacted. Linux runs both symlink and hardlink rejection cases.
The fixture suite creates a temporary **synthetic** evidence tree, removes it on
exit and cannot provide release evidence. It copies actual required source bytes
(including all module/sample source trees), not fabricated source placeholders.
Fixture input limits are 4,096 source files, 32 MiB combined and 2 MiB per file,
with a 20,000-node/depth-24 directory walk bound. SDK/report bytes remain synthetic.
Whole-tree omission and empty-tree fixtures remove both retained and checkout
contents and update all source-manifest bindings; mandatory tree checks must still
reject them. Static PowerShell source-contract assertions cover explicitly bound
option checks and literal argument-array forwarding; they do not execute PowerShell.

```sh
timeout 300s node tools/ci/test-v019-sample-matrix.mjs
timeout 300s node tools/ci/test-v019-sample-runtime.mjs
```

To exercise the actual PowerShell entry point as well as the Node verifier,
use the consolidated K8s runner with PowerShell 7.3+ on `PATH`:

```sh
CACHEDB_TEST_PWSH_CONTRACT=true timeout 300s node tools/ci/test-v019-sample-runtime.mjs
pwsh -NoLogo -NoProfile -NonInteractive -File tools/ci/test-v019-postman-contract.ps1
```

These are tooling checks, not application API calls. The first command checks
paired receipt arguments, pin rejection, source-only failure and literal path
forwarding against synthetic evidence. The second also exercises the three real
purge wrappers' parameter binding and fixture-URI guards without running their
network/process bodies. Neither command can qualify live sample coverage.

Collect the real per-provider lanes using the existing lane-specific runners and
the exact profiles/Surefire/Failsafe selectors in
`tools/samples/v019-scenarios.json:qualificationLanes`. TEST and independent HTTP
must use different fresh SQL identities. Audit/retention share one audit fixture
pair; encrypted-notes and tenant-purge each need their own pairs. Keep all raw
reports and phase exits, record source/SDK snapshots before and after each phase,
then create the receipt and independently pin its SHA256. Never synthesize PASS
reports, copy old green XML or reinterpret a failed/partial phase as complete.

```sh
timeout 300s node tools/ci/check-v019-sample-coverage.mjs \
  --root /workspace/source \
  --summary target/v019-final-sample-coverage.md \
  --runtime-evidence /workspace/final-sample-evidence/receipt.json \
  --runtime-evidence-sha256 "$COORDINATOR_RECEIPT_SHA256"
```

Equivalent PowerShell 7.3+ invocation on the approved K8s runner:

```powershell
./tools/ci/check-v019-sample-coverage.ps1 `
  -RepositoryRoot /workspace/source `
  -SummaryPath target/v019-final-sample-coverage.md `
  -RuntimeEvidencePath /workspace/final-sample-evidence/receipt.json `
  -RuntimeEvidenceSha256 $env:COORDINATOR_RECEIPT_SHA256
```

The wrapper requires both evidence arguments together, rejects empty paths or
non-lowercase SHA256 pins and passes them as separate process arguments. Omitting
both retains the existing source-only, fail-closed result. These consumer commands
do not provision fixtures, execute sample lanes or produce a real runtime receipt;
the coordinator invokes the separate execution adapter documented below.

The receipt pin must come from the coordinator, not a hash freshly computed from
untrusted input by the same invocation. Successful ingestion writes separate
`qualifiedFeatures` (12 bindings), leaves `deferred` empty and sets `complete` and
`runtimeQualified` true **only for the declared sample coverage scope**. Source
manifests stay unchanged. Missing or rejected evidence keeps runtime qualification
false and the source/deferred rows intact.

## Framework Readiness Operator Handoff

The `Framework Readiness` workflow imports completed, real producer evidence; it
does not run the producer, allocate fixtures or qualify an SDK. Freeze the final
source first, qualify its exact SDK92 and tools, then execute all nine lanes with
18 fresh SQL scopes and the exclusive Redis fixture described below. Retain the
matching source/SDK manifests and all raw reports before handing off the receipt.
An older SDK, reused fixture or synthetic control result is not a substitute.

Configure the dispatch repository with an explicit private evidence source:

- `vars.CACHEDB_EVIDENCE_REPOSITORY`: the allowlisted private source mirror's exact `owner/repository` name.
- `vars.CACHEDB_EVIDENCE_REPOSITORY_ID`: that mirror's exact numeric GitHub repository ID.
- `secrets.CACHEDB_EVIDENCE_READ_TOKEN`: a read token authorized to inspect that mirror's metadata and download its Actions artifacts.

Repository metadata must confirm PRIVATE visibility and the exact allowlisted
name and ID. The token is mandatory: there is no fallback to `github.token`, the
public repository or another evidence source. Missing configuration, credentials
or an identity/visibility mismatch keeps the gate closed. Provisioning this
mirror and its credentials is an operator prerequisite, not an availability or
completed-handoff claim made by this guide.

Supply all three `workflow_dispatch` inputs together, independently pinned by the
coordinator:

| Input | Required value |
| --- | --- |
| `sampleRuntimeArtifactId` | Positive decimal ID of the unexpired Actions artifact in the allowlisted PRIVATE source mirror. |
| `sampleRuntimeArtifactSha256` | Lowercase 64-hex SHA256 of the artifact ZIP (`upload-artifact`'s `artifact-digest`), not a source archive, SDK JAR or receipt digest. |
| `sampleRuntimeReceiptSha256` | Lowercase 64-hex SHA256 of the actual, unmodified producer `receipt.json`. |

The ZIP must contain the complete evidence-root contents, with `receipt.json`
directly at its root and no wrapping directory. Include every referenced source,
SDK, manifest and raw report; the verifier checks their bytes and exact suite,
method and HTTP membership, not a receipt's declared success flag. The artifact
must originate from the allowlisted private mirror, and its producing run's
`head_sha` must equal the public dispatch run's `GITHUB_SHA`. This requires a
source mirror retaining the exact same Git commit, not an unrelated evidence
repository commit that merely stores an archive. A matching version number,
copied source tree or evidence from another commit/fork is insufficient. Resolve
the public dispatch ref to that exact frozen commit before invoking:

```sh
gh workflow run framework-readiness.yml --ref "$FINAL_REF" \
  -f sampleRuntimeArtifactId="$ARTIFACT_ID" \
  -f sampleRuntimeArtifactSha256="$ARTIFACT_ZIP_SHA256" \
  -f sampleRuntimeReceiptSha256="$RECEIPT_SHA256"
```

Although the UI inputs are optional, missing, partial or invalid pins keep the
runtime gate closed. Push/pull-request checks without this explicit handoff do
not qualify runtime coverage. Synthetic verifier fixtures and
`test-v019-runtime-workflow.ps1` test rejection/import controls only; they neither
produce real lane evidence nor authorize changing source feature statuses.

**Never upload private raw logs/reports to public repository artifacts.** Keep
the original evidence and artifact in the allowlisted private mirror. Private
metadata validation does not replace access control or secret handling by the
operator. If the private mirror, exact commit, read token or real evidence is
unavailable, keep the gate closed; do not substitute synthetic controls or a
public artifact. Imported raw evidence stays outside the checkout in
`RUNNER_TEMP` and is not re-uploaded by this workflow; only readiness
summaries/control outputs are uploaded. Successful sample qualification still
does not mean full release acceptance.

## Real Producer (K8s Only)

`tools/k8s/run-sample-runtime-evidence.sh` compiles just the execution adapter and
the existing `InitialSqlSandbox` test helper against a parent-qualified SDK and
explicit third-party classpath. It does not build or install reactor production
modules. `InitialSqlSandbox.empty(provider)` creates a fresh isolated scope and
performs a one-row, ten-second emptiness check without installing migrations.
The existing public constructor continues to install and verify all migrations.
The sample itself initializes each empty scope with its actual profile.

The parent must first provide a **fresh, independently qualified SDK92** built
from the frozen source, its complete offline sample Maven/plugin dependency
closure, and the pinned tools. Ended runs/pods are not execution authority or an
implicit SDK source. Missing pins or SDK members stop before SQL allocation.
Do not invoke the full producer merely to check compilation. The first parent
gate is JDK 21 compilation of the two harness files plus the existing starter
`InitialSqlSandboxCleanupTest` (eight methods, including two new empty-scope
checks). These checks mock JDBC; they do not assert native provider execution.
The full producer is a later, explicitly scheduled run.

The parent supplies a fresh `admission.properties` and independently pins its
SHA256. The file contains literal `key=value` lines (UTF-8, no escapes, duplicate
keys or continuation lines). All paths are absolute and canonical; use fresh,
non-overlapping work/evidence directories with existing parent directories.
The following is a template, not evidence or a command to provision anything:

```properties
qualification=PARENT_QUALIFIED_SOURCE_SDK92_TOOLS_SETTINGS
sourceRoot=/workspace/sample-input/source
sourceManifest=/workspace/sample-input/source.sha256
sourceManifestSha256=<parent source manifest SHA256>
sdkRoot=/workspace/sample-input/sdk
sdkManifest=/workspace/sample-input/sdk.sha256
sdkManifestSha256=<parent SDK92 manifest SHA256>
sdkSourceManifestSha256=<same source manifest SHA256>
classpathRoot=/workspace/sample-input
classpathManifest=/workspace/sample-input/classpath.sha256
classpathManifestSha256=<parent classpath manifest SHA256>
toolsManifest=/workspace/sample-input/tools.sha256
toolsManifestSha256=<parent tools manifest SHA256>
settings=/workspace/sample-input/settings.xml
globalSettings=/workspace/sample-input/maven-global.xml
repository=/workspace/repository
npmCache=/workspace/newman-cache
java=/opt/jdk/bin/java
javac=/opt/jdk/bin/javac
maven=/opt/maven/bin/mvn
pwsh=/opt/pwsh/pwsh
node=/opt/node/bin/node
npx=/opt/node/bin/npx
workRoot=/workspace/sample-runtime-work
evidenceRoot=/workspace/sample-runtime-evidence
runId=<new parent run ID>
namespace=cachedb-v019-validation
runnerPodUid=<current runner UUID>
serviceRunnerPodUid=<same current runner UUID>
originalCutoff=<original parent-authorized UTC cutoff>
cpuMillicores=2000
memoryBytes=4294967296
serviceOrigin=http://<parent-owned-service>.cachedb-v019-validation.svc.cluster.local:8080/
applicationPort=8080
redisOwnership=FRESH_EXCLUSIVE_NO_FLUSH
redisServiceName=<fresh private Redis Service DNS label>
redisServiceUid=<parent-verified Service UUID>
redisPodUid=<parent-verified Redis Pod UUID>
redisUri=redis://<same Redis Service DNS label>.cachedb-v019-validation.svc.cluster.local:6379/0
redisPasswordFile=/var/run/cachedb-fixture/password
postgresAdminUrl=jdbc:postgresql://postgres.cachedb-v019-validation.svc.cluster.local:5432/postgres?connectTimeout=5&socketTimeout=15
postgresAdminUser=cachedb
postgresPasswordFile=/var/run/cachedb-fixture/postgres-password
mssqlAdminUrl=jdbc:sqlserver://mssql.cachedb-v019-validation.svc.cluster.local:1433;databaseName=master;encrypt=false;trustServerCertificate=true;loginTimeout=5;socketTimeout=15000
mssqlAdminUser=sa
mssqlPasswordFile=/var/run/cachedb-fixture/mssql-password
oracleAdminUrl=jdbc:oracle:thin:@//oracle.cachedb-v019-validation.svc.cluster.local:1521/FREEPDB1
oracleAdminUser=system
oraclePasswordFile=/var/run/cachedb-fixture/oracle-password
```

Use the parent's actual consolidated tool paths, not the illustrative `/opt`
paths. All manifests use lowercase SHA256, **two spaces**, relative member path
and LF. Source/SDK/classpath manifests are relative to their configured roots.
`tools.sha256` is relative to `/` (for example `hash  opt/jdk/bin/java`, no leading
slash) and must pin the actual tool targets, settings, and the complete selected
Newman 6.2.1 offline cache/tool closure. Tool launch symlinks are allowed only when
their resolved targets are pinned; source/SDK/classpath members cannot be symlinks.
Paths with backslashes, colon, dot segments or newlines are not supported.

The classpath manifest must list the **23 exact main SDK JARs** from the curated
SDK92 view, plus only the required third-party JARs under the classpath root.
Include Jackson databind/core/annotations, Jedis and its dependencies, HikariCP,
SLF4J, the three JDBC drivers, and JUnit Jupiter API plus its dependencies for
the sandbox assertions. No sources/Javadoc or arbitrary repository-wide JAR list.
The adapter checks the exact SDK coordinate/classifier roster, matching live
Maven SDK bytes, and rejects CacheDB classes hidden in third-party JARs.
`javac -proc:none` compiles only `SampleRuntimeEvidence.java` and
`cachedb-spring-boot-starter/src/test/java/com/cachedb/jdbc/InitialSqlSandbox.java`;
the latter retains its real `com.reactor.cachedb.jdbc` package.

The runner must expose `POD_UID` and `POD_NAMESPACE` through the Downward API,
have finite cgroup-v2 `cpu.max`/`memory.max` matching admission (at most 2 CPU/4
GiB), and mount the native fixture passwords. Password values are read into
environment only, never put in arguments or generated files. The parent must
verify that the explicit HTTP Service routes **only this runner pod**, to the
specified application port. The parent must also verify and retain the Redis
Service UID and its EndpointSlice/Pod evidence: every endpoint must resolve
exclusively to the admitted `redisPodUid`, in the fixed namespace, at port 6379.
The Service and pod must belong to a fresh private fixture with no other users;
an empty shared Redis is not sufficient. Pin these exact names/UIDs in admission
and stop if the binding changes; do not adopt a replacement pod or Service.
The adapter validates the parent-pinned name/UID syntax and exact derived URI,
not the Kubernetes API or endpoint ownership. The parent-owned Services must
already exist; this producer creates no Kubernetes resources.

All three purge wrappers accept optional `-RedisServiceName`, defaulting to
`redis` for existing callers. Only a lowercase DNS single label of 1..63 characters
is accepted, in `cachedb-v019-validation.svc.cluster.local`, at port 6379; the
adapter explicitly passes its admitted name. Other hosts, namespaces, URI
credentials, schemes and ports remain rejected. SQL and HTTP guards are unchanged.
The private Redis keeps authentication enabled. Mount its password at the exact
`redisPasswordFile` path above: 16..512 printable ASCII characters, optionally
followed by one LF or CRLF. The bounded reader also accepts Kubernetes Secret
projection symlinks, but rejects empty, oversized, multiline and control-character
values. Never put the password in admission, command arguments, source manifests
or a generated configuration file. Preflight uses Jedis client authentication.
Sample TEST phases receive `CACHEDB_TEST_REDIS_PASSWORD`; HTTP phases use an encoded
authenticated `REDIS_URI` only in the child environment. Purge keeps its guarded,
credential-free `SAMPLE_PURGE_REDIS_URI` and receives a separate
`SAMPLE_PURGE_REDIS_PASSWORD` for both Jedis and the bounded RESP transport. These
variables use Redis's default ACL user. Existing standalone no-auth sample calls
remain supported when their optional password variable is absent. Never change
the shared Redis or bypass authentication to make a fixture pass. Treat all raw
child logs and reports as private, as required above; do not publish credentials.

The adapter requires Redis DB0..12 initially empty, without deleting anything.
Audit TEST/HTTP use DB1/2, 5/6, 9/10 for PostgreSQL/MSSQL/Oracle; encrypted TEST/HTTP
use DB3/4, 7/8, 11/12. All purge phases use DB0 with actual independently generated
run prefixes and function-library names. No FLUSH, shared-key cleanup, fixture
adoption, or failed-phase replay occurs. SQL requires 18 fresh identities: each
provider's audit, encryption and purge TEST/HTTP pair. PostgreSQL owns a fresh
schema, MSSQL a bounded fresh database, Oracle a bounded fresh user/tablespace.
Cleanup targets only the helper's actual allocated scope, including on failures.

```sh
bash /workspace/sample-input/source/tools/k8s/run-sample-runtime-evidence.sh \
  /workspace/sample-input/admission.properties "$PARENT_ADMISSION_SHA256"
```

Execution holds `/workspace/current-run.lock` and each fresh sample workdir's
lock, uses `mvn -o` with the exact lane profiles/selectors and strict zero-rerun
flags above, and never downloads. The existing PowerShell lane scripts launch
the offline pinned Newman. TEST packages the real sample JAR; independent HTTP
starts that JAR on the same specified port sequentially against another empty
fixture. Bounded Service readiness polling is not a lane retry or accepted HTTP
evidence. Each TEST/HTTP phase is capped below 900 seconds and the original cutoff,
with termination reserve; the cutoff cannot be renewed. Limits: harness 192 MiB,
Maven 384 MiB, test/app 512 MiB, Node 256 MiB, PowerShell GC heap 128 MiB; one Maven
fork/APC=1; 32 descendant processes, 256 user tasks, no core dumps, 20,000 work/
evidence entries and 2 GiB combined (128 MiB per generated file, reports 32 MiB).

The adapter checks PowerShell 7.3+ and the pinned offline Newman version before
allocating SQL fixtures. On Linux, CoreCLR 8 reserves a sparse 2 TiB anonymous
`memfd` for write/execute-separated code mapping. `RLIMIT_FSIZE` also applies to
this logical reservation: a 128 MiB process limit terminates PowerShell with
`SIGXFSZ` (exit 153), often before it can write a log. The launcher therefore
sets that process limit to 2 TiB, not unlimited. This is not a request for 2 TiB
of disk or RAM. Generated files retain the separate 128 MiB-per-file and 2 GiB
combined audit budgets; these are periodic checks, not an equivalent kernel
file-size limit. The coordinator must retain its bounded volumes, container
memory/ephemeral-storage limits and original deadline. Do not disable CoreCLR's
write/execute separation to work around the limit.

Raw Maven/Newman/app logs and reports, phase exits, distinct before/after source
and SDK manifests, fixture identity hashes and completed-cell records stay under
the fresh evidence root on failure. Do not overwrite it or rerun unchanged inputs.
On complete execution, the existing checker validates a candidate receipt against
all raw reports; only a successful checker and final preservation check publish
`receipt.json` and `receipt.sha256`. These files do not create external provenance:
the parent must inspect/export the private evidence and independently pin the
receipt before final ingestion. No release/PASS declaration is fabricated, source
feature statuses remain unchanged, and full release acceptance remains separate.
