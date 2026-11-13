# Phase Resource Collection Setup

All three supported runners (`run-paired.sh`, `run-coalescing-paired.sh` and
`run-features-paired.sh`, including its nested `all` invocation) require an
explicitly prepared helper. They do not compile helpers during measurement or
silently fall back to unobserved results. Older direct invocation snippets need
the following preparation and export first, in the same shell.

Run only in the approved EmaTech Linux environment. Requirements: JDK 21
(`java` and `javac`), Python 3 standard library, POSIX shell and GNU coreutils.
No Maven build, SDK rebuild, Redis connection or paired matrix is needed for
these collector contracts. Use a cached `jmh-core-1.37.jar` or a current shaded
benchmark JAR containing JMH 1.37; preserve its hash. Evidence paths must be new.

```sh
RESOURCE_EVIDENCE=/workspace/evidence/resource-contract-NEW
sh tools/benchmarks/redis/prepare-jmh-resources.sh "$JMH_JAR" "$RESOURCE_EVIDENCE"
export CACHEDB_BENCH_RESOURCE_CLASSES="$RESOURCE_EVIDENCE/classes"
```

Preparation compiles only the profiler and its standalone contract main, then
runs the Python fixture suite. It records input/output hashes and bounded logs.
The same exported classes directory is used on both sides of every pair.
`CACHEDB_BENCH_PYTHON` may select a specific Python 3 executable.

For the minimal direct sampler fixture check, without compiling Java:

```sh
python3 -B tools/benchmarks/redis/test-jmh-resources.py
```

Run fixtures from the original checkout: their source-wiring checks read the
current runners and both CI entry points. They use synthetic proc/cgroup data
and one short owned-process cleanup control, not a benchmark or resource-guard
Maven verification. Observer isolation and an I/O-read budget do not prove zero
overhead, exact RSS peaks or improved product performance.

The performance workflow prepares the helper before its A/A and paired runs
and exports it through `GITHUB_ENV`. The EmaTech composite action prepares it
inside the existing benchmark container and exports it before both matrices.
Both retain preparation evidence. New manual runs must do the same explicitly.

`/proc/PID/status` VmRSS and VmHWM are approximate. Periodic sampled maxima,
boundary observations, lifetime HWM and cgroup lifetime counters are distinct.
No smaps reads or peak-counter resets occur. Missing Redis PID/start-time
targets remain unavailable; the observer cgroup is never substituted for Redis.

## Paired Measurement Identity

Each supported runner writes a `<result.json>.instrumentation` companion after
the measured process exits. Keep it with the original JSON. Its raw-file SHA-256,
ordered controller profilers, helper hashes, sampler settings and case identities
are checked before comparison. Merging produces a new bound companion for the
merged file; it never edits the original raw files. Missing, stale or mismatched
companions fail the comparison rather than enabling a legacy fallback.

Strict matrix acceptance requires five 3-second warmup and measurement iterations
in at least three real forks. A retained 1-second reference can be format-valid
without qualifying as the current acceptance baseline. Do not change its metadata
or relabel it as an equivalent 3-second measurement.

Use `calibrate-jmh-resources.sh BENCHMARK_JAR NEW_OUTPUT_DIRECTORY` for a short
four-condition observer probe after preparation. It reverses the order of plain,
GC-only, resource-only and combined runs. The same L1 read benchmark and binary
are used throughout. This diagnostic deliberately has fewer forks and shorter
iterations than the acceptance matrix; it does not qualify product performance
or prove zero overhead, and its cost is never subtracted from product timing.
