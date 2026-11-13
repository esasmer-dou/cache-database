# Optional 0.19.0 Feature Benchmarks

[Türkçe](../../../../../tr/tools/benchmarks/redis/src/features/README.md)

This opt-in development-tool profile targets CacheDB 0.19.0 native APIs. It does not
change the baseline-compatible source set, the read-coalescing profile, SDK dependencies or runtime defaults.
The default tool build remains compilable against unmodified v0.18.1 without reflection or API emulation.

## Build and Run

Execute only in the approved EmaTech Kubernetes benchmark environment, after installing the matching
0.19.0 candidate SDK artifacts in that environment's Maven repository. The example uses the intended
0.19.0 coordinate; it does not assert that the release is published or qualified. The old 0.18.1 SDK
cannot compile this profile. Build and measure the exact candidate sources and artifacts together.
Do not activate both benchmark profiles together. Preserve the original baseline JAR before building.

From the repository root, inside the fixed-resource benchmark pod:

~~~sh
mvn -f tools/benchmarks/redis/pom.xml -Predis-benchmarks-v019 \
  -Dcachedb.benchmark.runtime.version=0.19.0 -DskipTests package

export CACHEDB_BENCH_CPUS=1
export CACHEDB_BENCH_REDIS=redis://127.0.0.1:6379
export CACHEDB_BENCH_BASELINE_JAR=/workspace/preserved/v0.18.1/benchmarks.jar

timeout --signal=TERM --kill-after=15s 5h \
  sh tools/benchmarks/redis/run-features-paired.sh \
  tools/benchmarks/redis/target/features-v019/benchmarks-v019-features.jar \
  /workspace/evidence/t9-features-attempt1 features
~~~

Use a new directory for EVERY attempt. Never resume, select successful cells from a failed run, or
overwrite the preserved JAR. The runner refuses an existing output directory and verifies candidate,
baseline and source hashes at exit. A completed run is not acceptance.

Run the independent checker with PowerShell 7 in the approved environment, outside the measured JVMs:

~~~powershell
$baseline = (Get-FileHash /workspace/preserved/v0.18.1/benchmarks.jar -Algorithm SHA256).Hash
./tools/ci/check-t9-feature-evidence.ps1 -Path /workspace/evidence/t9-features-attempt1 -CandidateJar ./tools/benchmarks/redis/target/features-v019/benchmarks-v019-features.jar -ExpectedBaselineSha256 $baseline -ProcessorCount 1 -Group features
~~~

The baseline SHA must come from the independently preserved baseline, not from the candidate manifest.
The candidate SDK and tool JAR must be rebuilt after changing a runtime default. Actual default values
are read from the hashed candidate by each preflight, not inferred from checkout source or an environment variable.

The complete groups are:

| Group | Required raw result cells |
| --- | ---: |
| observation | 12: L1 hit / L1 miss, disabled / NOOP / real no-op-handler, throughput / SampleTime |
| async | 4: native L1 miss, coalescing false / true, throughput / SampleTime |
| tracking | 8: L1 hit / write, PUBSUB / SERVER_TRACKING, throughput / SampleTime |
| features | All 24 new feature cells |
| all | All 24 plus the complete 16-cell read-coalescing matrix in coalescing/ |

Select a complete group using the runner's third argument and the same checker's -Group argument.
Group all delegates to the read-coalescing runner/checker and requires T1_COALESCING_V4 evidence,
including actual artifact-default proof. Every worker must progress; point1 retains its <=3% criterion
and both high-concurrency cases require >=2x throughput. Only an artifact-proved default=false permits
the documented opt-in point1 exception, never a high-concurrency exception.
Allow a fresh six-hour pod deadline and a full fresh matrix; the outer five-hour timeout bounds the attempt.
Before `all`, run the packaged-helper preparation in the [T1 instructions](../coalescing/README.md)
against this feature JAR and export its helper-only directory. The nested T1 controller is 512 MiB;
only its two 1000-worker SampleTime cells use the helper before GC. The 24 feature cells below
remain GC-only with their original 1 GiB controller.

## Fixed Controls

Every measured cell uses JMH 1.37, three forks, five 3-second warmups and five 3-second measurements,
both modes, GC profiling, one platform JMH caller, a 512 MiB fork and a 1 GiB controller.
The child CPU count is explicit and checked in the JMH arguments and actual child runtime proof.
Use the same fixed CPU/memory limits, Redis process, network placement and JDK for every pair.
Cgroup/OS context is retained but is not substituted for deployment-resource review.

The shared fixture is unchanged: fair max32 pool, 10-second max wait, 5-second connect, 10-second socket,
15-second blocking socket, REDIS_ONLY and the unchanged producer guard configuration/source.
There are no timing sleeps, synthetic transport latencies, reflection, source fallback, guard opt-outs
or extra hidden consumer coordinators. Warmup, seed-drain waiting, INFO, parity checks and metrics
snapshots are outside timing. Native async full-row verification and a bounded completion wait remain
inside timing so abandoned/unconsumed requests cannot inflate throughput.

## Meaning of Results

Observation uses the actual native recorder hook. Disabled uses the core singleton; NOOP uses the real
Micrometer recorder with ObservationRegistry.NOOP. Both point p50 NOOP/disabled ratios must be <=1.03.
A separate registry with a real, no-op lifecycle handler exercises enabled instrumentation; its overhead
is reported separately, not included in the 3% NOOP gate. A separate counting-handler preflight requires
exactly one real native findById observation and the correct L1/REDIS tier, without a wrapper emulation.
No-op handler timings do not represent an exporter, tracing backend or arbitrary application handler.

Async submits 1000 native logical point reads from one platform caller, consumes all results, then
starts the next bounded window. There is no per-request VT offload: a configured rejecting executor and
real admission/transport/physical-completion counters enforce that. Disabled merging must still use
the bounded singleton FIFO. Every iteration must release permits and native result/delivery credits.
OperationsPerInvocation(1000) normalizes throughput/allocation per logical request. SampleTime's
percentiles are WINDOW duration / 1000, NOT individual request response-time percentiles.
The separate read-coalescing group measures 1000 actual virtual JMH workers.

Tracking borrows database.nearCacheCoordinator(); no second manual consumer is created.
PUBSUB retains legacy publication. Strict SERVER_TRACKING opts out only within this isolated fixture's
all-consumer upgrade contract. AUTO plus legacy=false must be rejected. Readiness, mode, generation,
flush/reconnect counters and INFO PUBLISH deltas are checked per iteration. A raw Jedis write must
invalidate the real tracking L1 within one second. Each mode's preflight verifies 1000 actual CacheDB
writes and all 1000 versions, with a positive PUBSUB counter control and zero strict-tracking delta.

INFO counters are server-wide. The Redis server MUST be exclusive to this run, not merely a private
key prefix on a busy/shared server. Otherwise the publication proof must fail, not be ignored.
These fixtures use standalone JedisPooled; they do NOT replace real Cluster/all-master/failover tests.

The checker requires every raw fork and measured iteration, all warmup/measurement proof records,
paired order, exact resources/arguments, GC data, full preflights and source digests.
Keep the complete `sources/` tree and `sources.sha256` when archiving or copying evidence. It includes
both checkers, their shared histogram helper and all three checker selftests in `sources/tools/ci/`,
plus the fixed source files required by the feature selftest. Copying only the checker scripts loses
their dependency closure. These files remain external verification tools, not SDK/JAR dependencies.
Inputs are bounded: 64 MiB per JSON, 16 MiB per log, 768 MiB total checker input.
Primary SampleTime percentiles must agree bit-for-bit with the weighted merge of all 15 raw
iteration histograms. This check supports only JMH 1.37 with `us/op`, three forks and five measured
iterations. It validates all ten serialized percentiles using JMH's interpolation and binary64
round-trip semantics, without an epsilon, sample expansion or output rewriting. The merge is bounded
to 55,296 unique values and 829,440 input buckets; aggregate int32 overflow is unsupported evidence.
Consistency does not authenticate a coherently modified histogram or qualify a failed/evicted run.
Async/tracking speed is report-only; no speed gate is invented. Confidence intervals, p99 and B/op are
reported. Raw/gate success is not provider, liveness, cancellation, failure, JFR or production acceptance.

## Result Scope

See the [recorded coalescing ratios and artifact boundary](../../../../../docs/performance-benchmarks.md).
Those numbers belong to the measured a9604544-prefix JAR and legacy Function body, not this profile
or a later/final 0.19.0 binary. The aggregate/facet >=2x baseline gate remains OPEN; a Function-library
startup failure has no numeric result. Feature availability and checker success alone are not a
completed benchmark claim. Keep every final-artifact requirement unchecked in the
[release checklist](../../../../../docs/release-checklist.md) until its own evidence is reviewed.

Primary format/contracts:
- [JMH 1.37 JSON writer](https://github.com/openjdk/jmh/blob/1.37/jmh-core/src/main/java/org/openjdk/jmh/results/format/JSONResultFormat.java)
- [Micrometer 1.13.6 ObservationHandler](https://github.com/micrometer-metrics/micrometer/blob/v1.13.6/micrometer-observation/src/main/java/io/micrometer/observation/ObservationHandler.java)
