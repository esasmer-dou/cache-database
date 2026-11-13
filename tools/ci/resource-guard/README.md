# Pod Resource Guard

Standalone Java 21 CI tooling for non-secret Kubernetes performance-fixture
resource evidence. It has no CacheDB SDK runtime dependency and is not a published
SDK module. It uses the existing Gson dependency for bounded streaming JSON, not
reflection-based object binding. The Java CLI needs neither jq nor PowerShell.

## Build And Controls

Use an existing approved Java 21 and Maven installation. These commands do not
install SDK artifacts, provision a Pod or change Kubernetes resources:

```sh
mvn -B -ntp -f tools/ci/resource-guard/pom.xml \
  -Dcachedb.resource.evidence=/absolute/new/resource-controls verify
```

The evidence directory must be unique for each verification attempt. Preserve its
Java control summary, raw Maven log and Surefire reports, including failures.
Synthetic controls qualify the validator, not a benchmark, live cluster or release.

The executable artifact is `tools/ci/resource-guard/target/resource-guard.jar`.
The optional PowerShell compatibility entry point is
`tools/ci/test-performance-resource-contract.ps1`. It invokes this module's Maven
`verify`, requires a fresh successful Java control summary and executed, unskipped
JUnit reports, and retains raw stdout/stderr and reports. It does not implement a
second resource parser. GitHub Framework Readiness runs that wrapper with its
existing PowerShell; an approved Kubernetes workspace can run Maven directly.

## Qualify An Attachment

Read Pod JSON on standard input. Never export the complete Pod document, which
can contain environment values or credentials:

```sh
kubectl --context kubernetes-admin@EmaTech --request-timeout=30s \
  -n "$NAMESPACE" get pod "$POD" -o json |
  java -Xmx64m -jar tools/ci/resource-guard/target/resource-guard.jar \
    qualify --container maven --redis redis \
    --container-cpu 1 --pod-cpu 2 --child 1 --memory 2Gi --fresh
```

The Java/Maven container's CPU limit and request are separate from the sum of
regular-container CPU limits and from the child's `ActiveProcessorCount`. The
example requires Maven limit/request 1 CPU, Redis limit 1 CPU and request 200m,
and a regular-container limit sum of 2 CPU. It does not claim a shared Pod CPU
quota or change any benchmark JVM controls.

Qualification requires exactly the approved two regular containers, no additional
init-container, overhead or Pod-level resource budget, the explicit memory limit
of at least 2 GiB, `/workspace` backed by an `emptyDir` with a size limit of at least
2 GiB, and a Java-container ephemeral-storage limit of at least 3 GiB. Supported
CPU quantities are integer cores or millicores; storage uses Mi/Gi. Unknown,
missing or insufficient quantities fail closed, without increasing capacity.
This fixture profile requires disk-backed workspace storage: RAM-backed emptyDir
and mounts nested under `/workspace` are rejected. Descendant mounts could hide
an unapproved backing volume beneath an otherwise valid workspace mount. These
are explicit storage-policy checks, not claims about a benchmark result.

With `--fresh`, the Pod must be Running, have `activeDeadlineSeconds=21600`,
and have a start time between now and 1800 seconds ago. Future timestamps fail.
This precondition is checked before building or running the benchmark SDK.

For GitHub Actions, add `--github-output "$GITHUB_OUTPUT"`. Only after successful
validation, the tool appends framed `poduid`, `resources`, `containercpu`,
`containercpurequest`, `podcpusum`, `rediscpu` and `rediscpurequest` outputs.
The compact projected snapshot is also written to stdout.

## Before And After

Keep the qualified compact snapshot as `EXPECTED`. Immediately before starting
the performance run, compare a new Pod projection with it:

```sh
kubectl --context kubernetes-admin@EmaTech --request-timeout=30s \
  -n "$NAMESPACE" get pod "$POD" -o json |
  java -Xmx64m -jar tools/ci/resource-guard/target/resource-guard.jar \
    same --container maven --redis redis --expected "$EXPECTED"
```

At final collection, project once with `snapshot --container maven --redis redis`.
Retain that sanitized stdout as the final resource JSON, then pass that same JSON
on stdin to `compare --expected "$EXPECTED"`. There is no second full-Pod query
between final projection and comparison. `snapshot` alone is not qualification.
Do not discard final facts when comparison rejects UID or allocation drift.

The performance action retains its initial/final projected facts, final validator
stderr and validator JAR hash, and still attempts to collect raw benchmark evidence
after validation failure. Unavailable or malformed final facts fail qualification;
they do not turn a failed run into a pass. The action never changes CPU, memory,
storage, freshness, benchmark thresholds or the existing paired measurement matrix.

## Bounds

The parser rejects duplicate keys, invalid UTF-8, trailing documents and malformed
JSON. Input is limited to 1 MiB, output to 64 KiB, nesting to 32 levels and parsed
nodes to 20000. The action bounds each validator invocation to 30 seconds with a
64 MiB Java heap and its standalone tooling build to five minutes. SDK builds and
benchmark Java still execute only inside the approved benchmark container; the
small resource validator is runner-side tooling.

Use normal read-only Kubernetes access to the existing approved Pod. No runtime
download, alternate image, privileged helper, token mounting or provisioning is
part of this tool or its compatibility wrapper. Tool output is not remote CI,
performance or release acceptance on its own.
