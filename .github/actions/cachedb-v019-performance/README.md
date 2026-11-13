# EmaTech Capability Performance CI

The manual workflow performance-features-ematech.yml runs complete read-coalescing and 0.19.0 feature
matrices and retains their evidence; workflow availability does not imply a completed or accepted run.
It does not replace root paired qualification, provider/Cluster/failure/JFR evidence or the separate
snapshot/real OpenTelemetry/PostgreSQL workflow.
The separate `Redis Performance Gate` now also uses an approved EmaTech pod through
`.github/actions/cachedb-redis-performance/action.yml`; neither workflow's presence proves execution
or acceptance.

## Prerequisites

An operator must approve an IDLE, existing EmaTech benchmark pod before dispatch. Pod activeDeadlineSeconds
must be exactly 21600 and its start age at validation must be at most 30 minutes (with synchronized clocks). This action never
creates pods, installs a runner, changes cluster context, tunes Redis, changes pod resources or retries
failed measurements. Do not point it at an active qualification pod or a shared Redis server.

The existing trusted Linux control runner needs labels self-hosted, linux and cachedb-ematech-control,
Java 21/kubectl/GNU timeout/GNU date/git/tar/jq, and preconfigured access to context kubernetes-admin@EmaTech. No kubeconfig
or credentials are accepted as workflow inputs or uploaded. Configure reviewer protection on the
cachedb-performance-ematech environment; merely naming an environment does not configure reviewers.

Use a fresh source checkout with no root target/ directory; the action refuses to clean earlier
qualification output. Keep the preserved baseline outside EVERY Maven target/ directory. The action
stores its own diagnostics outside Maven-cleaned directories under /workspace/.cachedb-ci-evidence.

The pod needs Java 21, Maven, PowerShell 7, GNU timeout, flock, pgrep, realpath, sha256sum, tar and ordinary shell
utilities. Its clean source checkout must match the workflow SHA. The candidate SDK is clean-installed from
those sources before both isolated tool-profile builds, not resolved as an unmodified old public SDK.
A pre-existing Java process or an owned measurement lock makes the action fail before building.

`tools/ci/prepare-ematech-resource-guard.sh` compiles the standalone allocation guard only in the
approved pod, before measurement. It binds the workflow SHA, helper source-tree hash, pod UID and
returned JAR hash; the controller verifies the fetched JAR before using it. The controller needs
Java only for the bounded 64MiB-heap allocation/identity validator, not a compiler, Maven or test runner.
Build logs and binding/hash records accompany available exported evidence, including failed builds.

Both runtime-version defaults are 0.19.0 for final-artifact CI. The checkout POM version must actually
match that input; this does not relabel a 0.18.1 checkout or prove that 0.19.0 is published.
A manually synchronized workspace is not a clean matching Git checkout and does not satisfy this CI
attachment contract. Preserve its separately recorded source hashes without manufacturing a workflow
SHA, clean Git status or a claim that this workflow ran.

Provide independent CPU controls. cpus is the child JVM ActiveProcessorCount (1 or 2).
container-cpu-limit is the approved Java/Maven container CPU limit and request (default 1).
pod-cpu-limit is the sum of the two regular-container CPU limits, not a shared cgroup quota.
The co-located fixture has Maven limit/request 1/1 CPU and Redis limit/request 1/200m CPU:
the limit sum is 2 CPU and the request sum is 1.2 CPU. Use cpus=1, container-cpu-limit=1,
pod-cpu-limit=2 and memory-limit=2Gi for that allocation. The workflow fields are respectively
cpus, container_cpu_limit and pod_cpu_limit. redis-container defaults to redis.
The controller (1GiB heap) and fork (512MiB heap) share Maven's ONE-CPU container; Redis has a
separate one-CPU limit. The pod's limit sum must never be reported as Maven's cgroup allocation.

The preflight rejects additional containers, init-container or pod-level CPU budgets. Memory must
exactly match the approved Mi/Gi value and be at least 2Gi. Before any build, the emptyDir actually
mounted at /workspace must declare sizeLimit >=2Gi, and Maven must declare an ephemeral-storage
limit >=3Gi. Storage quantities accept only integer Mi/Gi; missing, unknown or insufficient values
fail as capacity preconditions, not measured runtime regressions. The action never changes quotas,
limits or heap sizes. These declared budgets are not a guarantee of free node disk or immunity to eviction.

CPU limits/requests, memory/storage resources, emptyDir sizes and Pod UID are rechecked before
execution and at collection. Initial/final resource JSON retains the separate Maven, Redis and
pod-sum facts; ci-attachment.txt records CPU controls without conflating them. Drift rejects
qualification while retaining available raw evidence. No input changes resources or CPU affinity.
Use the SAME qualified limits/placement/JDK and child processor setting as the root paired matrix.

Use a fresh six-hour pod deadline and preserve its workspace/data until all raw evidence is collected.
The action attempts collection even on failure, but cannot guarantee collection after pod/storage or
control-plane loss. It never deletes failed attempts; durable workspace storage is recommended when
evidence must survive pod loss.

## Dispatch

Run the manual EmaTech T1 and v019 Feature Performance Gates workflow on main with the existing approved
namespace/pod/container, checkout path at or below /workspace, exclusive Redis endpoint, the installed 0.19.0 candidate's
Maven version, and the preserved baseline JAR path plus its independently recorded SHA256.
The baseline must live below /workspace. It is never rebuilt or overwritten, and must not be under a Maven target/ directory.

The job has a HARD 240-minute outer budget, not a deadline waiver. The capability action has independent
165-minute host and 160-minute remote bounds, 20-minute combined SDK/tool build, 5-minute checker
self-test, 60-minute complete T1 matrix, 60-minute complete feature matrix and 5-minute checker bounds.
It also bounds evidence collection. Exceeding any bound fails the run; no forks/iterations/cells or
thresholds are reduced, and no failed run can be resumed.

The feature workflow and root paired workflow remain separate jobs with separate budgets and
qualification evidence. Moving their execution into EmaTech does not establish a completed run.

## Root Paired Action

`performance-gate.yml` retains the `Redis Performance Gate` workflow and `redis-performance` job,
but is manual-only on the protected EmaTech control runner. Its separate paired action requires
explicit namespace, pod, approved pod UID, absolute deadline, container, workspace, loopback Redis
endpoint, CPU/memory controls and independently preserved 0.18.1 baseline JAR/SHA256 inputs.
Missing authority fails closed; the action does not create, replace or delete pods or rebuild the baseline.

The controller records its immutable 180-minute cap before checkout. Work ends at the earlier of
that cap and the approved absolute deadline, less a 15-minute export/upload reserve; the last five
minutes are reserved for upload. The paired worker is `tools/ci/run-ematech-redis-performance.sh`.
Guard/helper builds, comparator self-tests, Maven and JMH execute in the approved pod, not on the
controller. The AA control, helper equality, legacy virtual exception and merge thresholds are unchanged.

The paired action sets the exclusive Redis fixture to 256MiB/noeviction. Do not use a shared Redis
server. Each attempt uses `/workspace/.cachedb-ci-evidence/target/ci-redis-RUNID-ATTEMPT` remotely.
Export preserves the archive member root `target/ci-redis-RUNID-ATTEMPT` beneath the controller's
`target/ci-redis-RUNID-ATTEMPT/raw`, so the workflow summary intentionally reads
`$EVIDENCE/raw/$EVIDENCE/paired/comparison.md`. No retry, resume or retained-proof bypass is provided.

## Evidence and Gates

Each attempt uses /workspace/.cachedb-ci-evidence/target/ci-t9-RUNID-ATTEMPT inside the pod and
target/ci-t9-RUNID-ATTEMPT on the control runner. Existing output
directories are rejected. Artifacts contain raw JSON/logs, candidate and original JAR copies with SHA256,
source snapshots, checker self-test logs/fixtures and non-secret resource/identity evidence.

T1 retains the exact 3% point1 criterion and both mandatory 2x point64/VT throughput gates. A proved
artifact default=false can permit opt-in acceptance while retaining FAIL_DEFAULT_ENABLED.
An artifact default=true cannot take that exception.

T9 requires the complete 24-cell matrix, disabled versus actual Micrometer NOOP <=3% point p50,
native bounded async/physical-accounting proof, stable actual tracking readiness/generation, strict
PUBLISH suppression and its real positive control. Enabled real no-op-handler overhead, async speed
and tracking speed remain report-only. Synthetic self-tests are NOT provider/performance evidence.

No compilation, Maven, Redis test, JMH or checker self-test runs on the control runner. These run
in the named approved EmaTech pod, sequentially before/during qualification. The controller's small
Java allocation/identity validator is separate from measurement. Do not run any compiler or other
load in the measured CPU allocation concurrently.

Contracts:
- [GitHub composite action metadata](https://docs.github.com/en/actions/reference/workflows-and-actions/metadata-syntax)
- [GitHub workflow syntax](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax)
