# 0.19.0 Read-Coalescing Benchmarks

This opt-in tool capability measures the real synchronous CacheDatabase repository path with
coalescing disabled and enabled in the SAME candidate JAR. It is not a v0.18.1-versus-candidate
comparison and does not qualify server tracking, native asynchronous reads, observation or providers.

## Build Isolation

The default tool source set and dependencies remain compatible with the unmodified 0.18.1 SDK.
The optional profile alone adds src/coalescing/java; no reflection or old-API emulation is used.
The shared fixture retains its original four-argument constructor and behavior. Its additional
six-argument constructor injects DatabaseFactory and CoordinatorFactory outside measurement.
This capability uses near=false; the coordinator callback throws if invoked and does not start
an invalidation consumer.

Build only in the approved EmaTech environment, using the 0.19.0 candidate artifacts installed
from the exact source snapshot to be measured. No new pods or services are created by these scripts.

~~~sh
# Refuse reuse before the first isolated build; never clean the baseline target directory.
test ! -e tools/benchmarks/redis/target/coalescing
mvn -f tools/benchmarks/redis/pom.xml -Predis-benchmarks-t1 \
  -Dcachedb.benchmark.runtime.version=0.19.0 -DskipTests package
~~~

The example uses the intended 0.19.0 coordinate, not a claim of publication or release acceptance.
The runtime version property selects candidate artifacts already installed under that coordinate;
it is not proof of binary identity. Archive the candidate SDK/tool JAR hashes and
source snapshot independently. Use the candidate's actual installed version if it differs.

Output is tools/benchmarks/redis/target/coalescing/benchmarks-t1-coalescing.jar. Compilation,
generated JMH sources and ordinary/shaded artifacts all stay under target/coalescing.
The default target/benchmarks.jar and its immutable old-JAR SHA are not overwritten.
Do not enable this profile together with the optional redis-benchmarks-v019 feature profile.

## Fixed Matrix

- Platform point reads: 1, 16 and 64 workers; virtual point reads: exactly 1000 workers.
- Each cell uses @Param coalescing=false/true through actual CacheRuntimeOptions and CacheDatabase.
- Both Throughput and SampleTime: 3 forks, 5 warmup plus 5 measured iterations, 3 seconds each.
- GC profiler, fixed 512 MiB fork and controller heaps, with explicit one/two active processors.
- The same actual fair max32 pool, maxWait10s, connect5s, ordinary socket10s, blocking socket15s.
- The existing fixture guard configuration is checked against defaults, never disabled or retuned.
- L1 is disabled; read-through is REDIS_ONLY and the existing JDBC DataSource throws on access.
- Each virtual JMH run uses VirtualBenchmarkExecutor and -si false. No carrier-starving spin barrier.
- Each measured operation consumes its result. Only a caller-local long progress increment is added
  to both toggles; no measured atomic counter, logging, histogram, source I/O or synthetic delay.

Actual fairness/capacity/wait and ordinary socket timeout are checked through public SDK getters.
Connection/blocking timeout construction is retained verbatim in the frozen fixture source and
hashed with the evidence; no reflective access or claim of unsupported timeout introspection is made.

## Run and Check

Use the dedicated fixture only. Supply its endpoint explicitly; credentials and ambient JVM options
are rejected. An evidence directory must be NEW, including after an interrupted attempt.

~~~sh
sh tools/benchmarks/redis/prepare-jmh-compactor.sh \
  tools/benchmarks/redis/target/coalescing/benchmarks-t1-coalescing.jar \
  tools/benchmarks/redis/target/compactor-selfcheck
export CACHEDB_BENCH_COMPACTOR_DIR="$PWD/tools/benchmarks/redis/target/compactor-selfcheck/helper-classes"

CACHEDB_BENCH_REDIS=127.0.0.1:16383 CACHEDB_BENCH_CPUS=1 \
  sh tools/benchmarks/redis/run-coalescing-paired.sh \
  tools/benchmarks/redis/target/coalescing/benchmarks-t1-coalescing.jar \
  tools/benchmarks/redis/target/coalescing-evidence-cpu1
~~~

Before timing, BOTH toggles must complete 1000 real virtual workers, 16 reads per worker, with
identical full-row parity/checksum and no failures. Enabled must report real coalesced requests;
disabled must report the original direct-path policy bypasses. This is a liveness/wiring control,
not a benchmark speed result. It has one shared 60-second worker deadline and bounded cancellation.

The runner alternates off/on order across every scenario/mode pair, preserves 16 individual raw
JSON/log files, and bounds each process with GNU timeout. Every original failed attempt is retained.
It fingerprints the candidate JAR, frozen fixture/guard source and any existing default baseline JAR,
and fails if they change during measurement. No automatic retry, cherry-picking, output replacement,
result merge or shortened qualification mode is available.

Every JMH fork reports all-worker progress and real transport deltas OUTSIDE timing for all five
warmup and five measured iterations. Any zero-progress worker, wrong actual worker type, unexpected
causal/UOW/overflow bypass, missing transport or absent concurrent coalescing fails the run.

~~~powershell
pwsh tools/ci/check-t1-coalescing-evidence.ps1 -Path tools/benchmarks/redis/target/coalescing-evidence-cpu1 -CandidateJar tools/benchmarks/redis/target/coalescing/benchmarks-t1-coalescing.jar -ProcessorCount 1
~~~

If the preserved old JAR is present, also pass -ExpectedBaselineSha256 with its previously recorded
immutable hash. The checker verifies fixed settings, raw 3-by-5 fork data (sampled histograms included),
GC evidence, alternating order, 1000-worker parity and every fork's progress/counters. Input files and
total evidence bytes are bounded. It does not execute Java or issue Redis/SQL commands.

New runs include a portable six-file kit: four PowerShell checker/selftest files under
`checker-tools/tools/ci/` and the exact qualified compactor/helper-test Java sources under
`checker-tools/tools/benchmarks/redis/`. Preserve the entire `checker-tools/` directory and
`checker-tools.sha256` when copying or archiving evidence. The runner checks source and copied-tool
hashes at exit. These PowerShell files are external verification tools, not SDK or benchmark-JAR
dependencies.

The preparation step extracts only `JmhSampleCompactionProfiler.class` from the packaged tool JAR.
It compiles only the five-check assert-main against that JAR with an empty source path; no shadow
helper is compiled. Preserve its input/helper/proof hashes and selfcheck output. The runner adds this
one-class overlay only for `virtual1000-sample-false` and `virtual1000-sample-true`, before `-prof gc`.
JMH 1.37 dispatches internal profilers in reverse order: GC observes the original per-worker results,
then the helper joins the same sample multiset before serialization. Samples are not discarded.
The other 14 cells remain GC-only. Keep `compactor/`, all 16 `*.controller.args` files and
`controller-paths.txt` with the raw evidence; original paths allow checking a relocated archive.
Older V3 evidence is not rewritten or accepted as V4; use its preserved checker for historical review.

## Numeric Gate, Not a Release Claim

The thresholds compare enabled/disabled in the SAME candidate: point1 p50 ratio <=1.03 for
enabled-by-default; point64 and virtual1000 throughput ratios >=2.0 in all cases. Boundaries are
inclusive. Point16 and all p99/allocation data remain reportable, not hidden.

The T1_COALESCING_V4 manifest and both preflights must agree on the actual hashed artifact's
RedisReadCoalescingConfig defaults(), builder and CacheRuntimeOptions defaults. A checkout setting
or a manifest assertion alone is not default proof. If point1 exceeds 3%, FAIL_DEFAULT_ENABLED stays
visible and only a proved default=false permits opt-in numeric acceptance. Missing progress, either
high-concurrency ratio below 2x or any other strict control failure remains blocking.

The checker emits NUMERIC_T1_GATE_PASS_NOT_RELEASE_ACCEPTANCE when all numeric criteria pass, or
NUMERIC_T1_OPT_IN_GATE_PASS_DEFAULT_DISABLED_NOT_RELEASE_ACCEPTANCE for that narrow false-default
exception. Neither token certifies a release. See [recorded results and final-artifact scope](../../../../../docs/performance-benchmarks.md).

Review raw fork distributions/confidence intervals, repeatability, deployment parity and separate
functional/JFR gates before acceptance. The tool never changes a runtime default. Functional pass
counts, zero pinned events and preflight completion are NOT performance acceptance.
