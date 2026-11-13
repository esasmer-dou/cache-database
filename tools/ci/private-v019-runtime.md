# Optional Private-Owned Runtime Import

This manual-only template verifies an already approved artifact in its **own
private, nonfork GitHub repository** using that job's ephemeral `GITHUB_TOKEN`
with only `actions: read` and `contents: read`. It does not configure a mirror,
produce evidence, publish reports, or replace any public workflow gate or its
dedicated read secret. Nothing is installed automatically.

The repository administrator must approve these repository variables separately
from the artifact: `CACHEDB_PRIVATE_REPOSITORY`, `CACHEDB_PRIVATE_REPOSITORY_ID`,
`CACHEDB_PUBLIC_SOURCE_REPOSITORY`, `CACHEDB_PUBLIC_SOURCE_REPOSITORY_ID`, and
`CACHEDB_APPROVED_SOURCE_SHA` (a full lowercase commit SHA, **S**). Dispatch inputs
are the artifact ID, ZIP SHA256, and root receipt SHA256, all independently pinned.
The artifact's completed successful manual producer must belong to the private
repository, including its head repository, and have `head_sha == S`.

`private-v019-runtime.workflow.yml.template` is an inactive template. Any future
installation requires review in the approved private repository. The workflow's
own commit **D** must contain the installed workflow and wrapper. `D == S` is
possible only if the installed workflow is also present in candidate commit S.
Otherwise D and approved public S remain explicitly distinct: the wrapper checks
both actual clean checkout HEADs and records both. It never replaces `GITHUB_SHA`
or relabels an artifact produced at D as one produced at S.

The wrapper loads the seven original import functions directly from the clean
approved source checkout's public workflow without changing them. It adds exact
private/public API identity and nonfork admission, then uses the unchanged
`check-v019-sample-coverage.mjs` / `verify-v019-sample-runtime.mjs` from that checkout.
Only trusted checkout code executes; extracted source, JARs and reports are data.
The existing final source, SDK92, nine-cell and report requirements still apply.
When requalification is necessary, supply both receipt-relative path/SHA256
pairs through the template's `sourceEquivalencePath`, `sourceEquivalenceSha256`,
`toolingOverlayPath`, and `toolingOverlaySha256` inputs. All four or none are
accepted. The wrapper forwards them without rewriting, keeps the complete
qualification chain in private `qualification.json`, and requires a successful
qualified result bound to the original receipt. The sanitized summary is written
under the approved checkout's ignored `target/`, not outside the CLI's boundary.

Raw artifacts, checker output and `import-proof.json` stay in a new mode-700
`RUNNER_TEMP/v019-private-owned-runtime` directory outside both checkouts. They
are not uploaded or printed. API calls have 30-second, no-redirect limits; the
original bounded download/extraction limits remain, and the checker has a
120-second timeout. Failure is final for that invocation, with no automatic retry.

`test-v019-private-owned-runtime.ps1` exercises synthetic admission and transport
guards only. A synthetic pass is not live private transport, sample runtime,
public CI, or release acceptance. Public CI secret binding, no-fallback policy,
source/artifact pins and qualification gates are unchanged; its shared private
repository guard additionally requires a real boolean `fork: false`.

## Metadata-Only Dispatch And Separate CI Gates

The two manual modes in `framework-readiness.yml` never admit the ordinary
build/test jobs. `private-upload` runs only the existing private retained-evidence
stager. On a public repository, `public-import` runs only `public-evidence-import`:
exact source/workflow S admission, credential-free exact-S checkout, the single
canonical seven-function import block, retained-evidence qualification, and the
sanitized R07 summary. It does not set up Java, invoke Maven, run `test-*` controls,
start providers, or regenerate product runtime evidence. Original receipt and
qualification pins, the approved private repository, and
`CACHEDB_EVIDENCE_READ_TOKEN` remain mandatory; there is no token fallback.

Only `target/v019-sample-coverage.md` may be uploaded by the public import job.
Raw evidence and the full qualification result remain private in `RUNNER_TEMP`.
R07 import success is a separate gate: it is not ordinary CI, Java/provider test
acceptance, or release approval. A skipped R07 job is not an imported artifact.

Publication remains blocked pending a separately owned release-gate correction:
`check-ga-release-readiness.ps1` currently selects overall `Framework Readiness`
success without requiring its ordinary CI jobs. A successful metadata-only run
must never satisfy that requirement. The release gate must independently require
successful ordinary readiness/provider jobs from an authorized CI event and
successful `public-evidence-import` from the explicit manual import at the same S.
The workflow split alone does not repair that downstream selector.

The existing `framework-readiness` and `sample-provider-integration` jobs retain
their ordinary checks, but admit only public `push` or `pull_request` events.
Their path filters and main/master push branches remain unchanged. No manual
mode admits them, and private repositories do not run them. Preserving these CI
definitions does not authorize a matching push or PR under the current policy:
all builds, tests and product runtime must run only in the approved EmaTech
Kubernetes context and namespace. Resolve that execution routing separately
before activating ordinary CI; a metadata-only dispatch does not waive it.

Any routing/control/documentation edit changes final-source identity. Review the
exact new deltas, verify affected controls only in separately authorized EmaTech
Kubernetes work, and append newly approved qualification sidecars before freezing
the next S. Do not overwrite original E evidence or accepted earlier exports.

## Retained Evidence Upload At Final Source S

The existing `framework-readiness.yml` has an explicit `private-upload` manual
mode. It is **not a new runtime producer**: it downloads an existing sealed ZIP
from a release asset in the same approved private repository, validates it with
the final source's verifier, and uploads the validated tree privately. It never
recreates the root `receipt.json`, source/SDK manifests, or runtime reports.
Every original archive member is hashed again after validation; only a fresh
`private-upload-qualification/` directory may be added. This directory records
the real qualification chain and transport provenance, not regenerated tests.

An operator must first review and qualify **all final changed paths**. Documentation
and tooling require their own exact reviewed inventories; test fixture changes
require separate exact-path/hash authority. No wildcard `src/test` exemption is
permitted. A qualification frozen before subsequent changes is not final-source
proof. Keep the original receipt, source and SDK immutable throughout.

1. Review this mode and its helper/controls before freezing the final verifier.
   Commit final source **S** only after all authorized edits are complete. Mirror
   the **same Git objects** to the approved private nonfork repository; copying
   files or making a different private commit does not satisfy `head_sha == S`.
   The reviewed workflow must be available on its default branch, and the
   dispatched branch must point exactly to S. No private workflow edits after S.
2. Approve repository variables `CACHEDB_EVIDENCE_REPOSITORY`,
   `CACHEDB_EVIDENCE_REPOSITORY_ID`, `CACHEDB_PUBLIC_SOURCE_REPOSITORY`,
   `CACHEDB_PUBLIC_SOURCE_REPOSITORY_ID`, and `CACHEDB_APPROVED_SOURCE_SHA=S`.
   Prepare an existing evidence ZIP with the unchanged original receipt at its
   root, all original evidence/source/SDK files and approved qualification
   sidecars. There must be no enclosing directory or pre-existing
   `private-upload-qualification/`. Independently pin its complete ZIP SHA256,
   original receipt SHA256, and both qualification path/SHA256 pairs. Upload
   that ZIP as an access-controlled release asset in the same private repository;
   pin the returned numeric asset ID. Asset API digest and byte length must agree.
3. Dispatch the reviewed workflow at S using the operator's approved refs:

```powershell
gh workflow run framework-readiness.yml --repo $PrivateRepository --ref $FinalBranch `
  -f evidenceMode=private-upload `
  -f privateEvidenceAssetId=$AssetId -f privateEvidenceAssetSha256=$InputZipSha256 `
  -f sampleRuntimeReceiptSha256=$OriginalReceiptSha256 `
  -f sampleRuntimeSourceEquivalencePath=$DocumentationPath `
  -f sampleRuntimeSourceEquivalenceSha256=$DocumentationSha256 `
  -f sampleRuntimeToolingOverlayPath=$ToolingPath `
  -f sampleRuntimeToolingOverlaySha256=$ToolingSha256
```

The helper checks manual/private/nonfork identity through trusted context and
GitHub API, actual clean checkout HEAD, workflow SHA, public S presence, fixed
same-repository asset endpoint, mandatory qualification pairs, archive safety,
receipt pin, current source and SDK identity, and full runtime evidence. The
download uses only that job's read-only ephemeral `GITHUB_TOKEN`, with no PAT or
secret fallback. API/transfer/checker timeouts are bounded; failure stops the
invocation without retries or an upload. Existing build/test jobs are restricted
to public push and pull requests only: mirroring S cannot start them in the
private evidence repository. All other jobs are also skipped
in private-upload mode: no Maven, workload, cluster, packaging, or test execution
occurs.

4. Independently record the resulting successful Actions artifact ID and ZIP
   digest from upload metadata, and verify its repository/head repository and
   `workflow_run.head_sha == S`. This new transport ZIP is different from the
   input release asset ZIP; the original receipt SHA256 stays identical. Hidden
   source files are included. No raw evidence is uploaded in public mode.
5. Dispatch the public workflow at the **same S**, with `evidenceMode=public-import`,
   `sampleRuntimeArtifactId`, `sampleRuntimeArtifactSha256`, the unchanged
   `sampleRuntimeReceiptSha256`, and the same four qualification inputs. Do not
   pass private asset inputs. Existing private repository approval and dedicated
   public importer read-secret binding remain required and unchanged. This runs
   only the metadata-only public import job; ordinary CI remains a separate gate.

The optional private import template above remains import-only and does not
create an artifact. Neither successful controls nor a private upload closes
genuine public GitHub import: that gate stays open until the actual public run
at S validates the pinned private artifact. None of these steps grants release
publication, release-wide test acceptance, or changes an UNRELEASED state.

Focused controls for separately authorized EmaTech Kubernetes work only (not a
local command or authorization to create a new run):

```powershell
pwsh -NoProfile -File tools/ci/test-v019-private-owned-runtime.ps1 `
  -RepositoryRoot $ReviewedSourceRoot -EvidenceDirectory $FreshPrivateControlDirectory
```

The control summary explicitly marks fixtures as non-runtime, non-live-transport
checks. Negative cases cover source/workflow authority, mixed modes, partial or
unsafe qualification references, asset identity/hash/size, lost private proof
references, and mutation/deletion or unapproved additions to sealed members.
The routing controls exercise the actual job conditions across public/private
repositories, manual/push/PR and forbidden events, and empty/import/upload/unknown
modes. They retain ordinary CI workloads while rejecting their admission on every
manual mode. The shared runtime-workflow controls enforce separate exact public
summary allowlists and reject private-result or raw-evidence upload paths.
