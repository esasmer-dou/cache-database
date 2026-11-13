# Redis Performance Benchmarks

The Redis JMH tool measures the framework's public repository paths. It is not a
claim about an application's end-to-end latency or SQL commit time. Benchmark
results apply only to the recorded JVM, CPU limits, Redis deployment and network.

## 0.19.0 Candidate Evidence

Read coalescing compares explicit enabled/disabled settings in the SAME candidate, not 0.19.0 versus
0.18.1. The retained 16-cell matrix used three forks, five 3-second warmups and five 3-second
measurements in throughput and sample-time modes. Both JVMs used a 512 MiB heap,
`ActiveProcessorCount=1` and single-core affinity; the pod CPU quota was two cores, not one.
The connection pool was fair with 32 connections, AUTO max-in-flight was 16, and coalescing defaulted
to `false`.

| Read-coalescing case | Recorded enabled/disabled ratio | Criterion |
| --- | ---: | --- |
| 64 platform workers, throughput | 2.2748x | PASS: >=2x for opt-in use |
| 1000 virtual workers, throughput | 3.8490x | PASS: >=2x for opt-in use |
| 1 platform worker, point p50 | 1.4775x | FAIL_DEFAULT_ENABLED: above <=1.03 |

The point1 result is a 47.75% p50 regression, not a pass of the 3% criterion. The result is
**opt-in numeric PASS only**: coalescing must remain `false` by default and
`FAIL_DEFAULT_ENABLED` remains recorded. This is not release or provider acceptance.

| Sampled p99 case | Recorded enabled/disabled ratio |
| --- | ---: |
| 1 platform worker | 1.2409x |
| 16 platform workers | 22.2543x |
| 64 platform workers | 2.5753x |
| 1000 virtual workers | 0.5163x |

The throughput gains do not erase the platform-worker p99 regressions. These p99 ratios compare
enabled with disabled coalescing in the same candidate; they are not comparisons with 0.18.1 or
release-latency acceptance.

> **Artifact scope:** The effective candidate is the raw tool JAR plus the qualified SDK class
> overlay and qualified JMH sample-compaction helper. The raw JAR alone does
> not qualify this candidate.
> Raw JAR SHA-256: `9e1347946bd2b7eed0b971fdcdfef228c210fee380c2d942b7d21ee800c1f7a3`.
> Helper SHA-256: `9483f3b96d0b4c7445485e5999720d06c73ce9e58accd6a51b80cd4787c14305`.
> Evidence input manifest SHA-256:
> `aafc9f94947bd65743cb31f6b09276af4474fa59f1f3b9aa61461192579a8f07`.
> Keep the raw matrix, artifact bindings and source/resource manifests together. The retained
> evidence uses schema V3; official V4 qualification requires a new measurement and cannot be
> applied retroactively. Requalify the effective artifacts after any change.

### Raw T4 Release Acceptance

As of November 9, 2026, the release owner accepts the raw owned/unowned COUNT/FACET
item as **PASS BY ACCEPTANCE EXCEPTION** at its current implementation. The mandatory
2x throughput target is waived for 0.19.0; it was not measured as achieved, and technical
impossibility was not proved. Historical results, raw evidence, checker thresholds and
exit codes below are preserved. They describe the criteria in force when each run occurred,
not the current release decision. Do not rerun unchanged matrices solely to pursue this
waived target, or relabel an old numeric FAIL as a measured PASS.

Authoritative ownership, liveness/tombstone checks, bounded reads, failure propagation
and ordinary final-artifact regression checks remain required. Other performance gates,
including read-coalescing and observation thresholds, are unchanged. Read-model freshness
remains a separate API contract. This exception is not final release approval or qualification
of an unmeasured final COUNT artifact.

The owned and unowned aggregateCount10k and facet10k20Groups >=2x throughput comparison
against the preserved 0.18.1 baseline has a historical numeric result of **FAIL**.
The historical paired run and the later diagnostic below both failed their numeric gates.
Functional equivalence and fewer client round trips are not evidence of
higher throughput. Authoritative ownership checks, metadata bounds and Redis execution still cost CPU.
Do not remove those correctness checks to reach a target. Any changed artifact requires a new complete
paired comparison. Preserve versioned side-by-side Function libraries; do not replace
an existing body or reuse another artifact's results. See [Function upgrade contracts](redis-functions-upgrade.md).

The indexed tenant COUNT path in the 0.18.1 source trusts tenant-index membership. The candidate also
checks authoritative row ownership. That additional check is required: a stale or injected membership
must not authorize another tenant's row. Removing it would change the security contract, not merely
optimize the query. This source-level difference did not itself waive the historic target
and is not proof about a benchmark artifact's provenance; the later release exception above
is a separate acceptance decision.

The multi-window path already pipelines its Redis commands. A lower command count does not imply
fewer network round trips or less server work. In particular, bounded metadata length checks must
precede value reads, and tenant-owner reads must remain bounded even for malformed stored values.
Measure client allocation, Redis command time and CPU throttling separately before choosing an
optimization. A successful functional test or a shorter Lua script does not establish a speedup.

### T4 Pair508: Artifact-Specific FAIL

The paired run on November 5, 2026, from 12:28:01 to 12:57:14 UTC covered 16 JMH cells and 48 forks.
Each cell used three forks, five 3-second warmups, five 3-second measurements, one worker,
512 MiB heap, `ActiveProcessorCount=1` and the GC profiler. This is evidence for the artifacts
identified below, not a general version comparison or release acceptance.

Ratios are candidate/baseline. Every case must reach throughput >=2x and sampled p99 <=1.15x.
The original throughput unit is `ops/us`; sampled latency, including p99, is `us/op`.
The p99 ratios use the sample-time 99th percentiles, not mean latency or throughput percentiles.

| Case | Throughput ratio, >=2x | Sampled p99 ratio, <=1.15x | Result |
| --- | ---: | ---: | --- |
| COUNT, unowned | 2.661406x | 0.471400x | PASS for this case only |
| COUNT, owned | 0.683083x | 1.526615x | FAIL |
| FACET, unowned | 0.617836x | 1.579139x | FAIL |
| FACET, owned | 0.715524x | 1.378820x | FAIL |

Original `count.exit`, `facet.exit` and `preservation.exit` are 0; `numeric-gate.exit` and `run.exit`
remain 1. Successful benchmark execution does not convert the numeric FAIL into acceptance.
Independent local custody verification rehashed all 22 retained originals, including all four raw
JSON files, and matched 21 members against the 50-member original publication. The other 29 members
and the benchmark JAR bytes were not available in this subset for independent rehashing.

- Genuine 0.18.1 baseline JAR receipt SHA-256: `ae359de4220b0ccfedd126769ac40192421f98bf79380943fd9dc66d106c6066`.
- Candidate508 JAR receipt SHA-256: `aa40be02feaf5895f68056740336e1d97d221b2bb3e094841d5e22523891b5b8`.
- Source508 manifest SHA-256: `309808c664c39e926a1d5e86ee0539d7148c44fc42f0cb994a10a593c6730de7`.
- SDK508 published92 manifest SHA-256: `3029d7d28d5b067f13659e5c9b2118f63b0991b489dd2dfe32a3d6b9d765b7f0`.
- Original Pair508 publication SHA-256: `4db712757072af8cf14417fdd850a78fefeee5867c5e379d90f48a852fe31c03`.
- Retained subset archive SHA-256: `417537460187aadcfd9bb59ab3f84e076e0ee84450be6f1c95637658f8c62376`.

### T4 Diagnostic: November 6, 2026

This comparison used the genuine published 0.18.1 baseline and a diagnostic candidate with the
ASCII key-construction allocation optimization. It does **not** qualify the final SDK. The baseline
differs from the candidate in more than this optimization; these ratios cannot isolate its effect.
The completed evidence covers 16 cells and 48 forks across two execution segments with identical
binaries and settings, not an uninterrupted release run. Each cell used three forks, five 3-second
warmups, five 3-second measurements, one worker, single-core affinity, `ActiveProcessorCount=1`,
512 MiB heap and the GC profiler; Redis had a 500m CPU limit. COUNT used 10,000 rows; FACET used
the same row count and 20 groups.

Ratios are candidate/baseline: throughput must be >=2x and sample-time p99 must be <=1.15x in
**all four cases**. Raw score units remain `ops/us` and `us/op`; ratios are dimensionless.

| Case | Throughput ratio, >=2x | Sampled p99 ratio, <=1.15x | Result |
| --- | ---: | ---: | --- |
| COUNT, unowned | 4.296776x | 0.253859x | Meets both criteria for this case only |
| COUNT, owned | 1.139831x | 1.016218x | FAIL: throughput |
| FACET, unowned | 0.852170x | 1.102839x | FAIL: throughput |
| FACET, owned | 0.770940x | 1.414515x | FAIL: throughput and p99 |

This is a whole-version comparison of the measured artifacts, not an isolated ASCII optimization:
the implementations do not enforce equivalent safety contracts. The old indexed owned COUNT
trusts tenant-index membership; the candidate also verifies authoritative ownership. Unlike the
old facet path, the candidate checks metadata lengths before reading values. These differences
do not waive the acceptance criteria or change the three failed cases.

**Three of four cases failed the criteria in force for this run.** Allocation fell in all four comparisons,
but this does not establish throughput or tail-latency acceptance, or an ASCII-only causal gain.
These results do not prove that >=2x is impossible. The later release exception above is an
explicit acceptance decision, not a conclusion that the measured results passed.

Measured artifact SHA-256 bindings:

- Baseline benchmark JAR: `ae359de4220b0ccfedd126769ac40192421f98bf79380943fd9dc66d106c6066`.
- Candidate benchmark JAR: `797f05360a34636afb62204e1af7355712103f3730343993ce173d9dad787386`.
- Tested Redis runtime JAR, first on the candidate classpath: `8f7644c441a493e26a7f3701900513acee5145588cc7595e50da308424f3da9a`.

### T4 Execution Profile: November 7, 2026

A short EmaTech Kubernetes diagnostic used the canonical owned facet fixture: 10,000 rows,
20 groups, one fork, one two-second warmup and two two-second measurements. The score was
`2.8161030559605606 ops/s`. This is **not** a paired baseline comparison, a complete JMH
qualification or a release-gate result. The benchmark JAR SHA-256 is
`332ea44993020134fb9cdf506b44f6a0780d34ea0554500c80f4d60d2ac322c8`;
the retained diagnostic archive SHA-256 is
`8b2a980be5d0994a406108a068b41c17e9c04384975d91f3a44bf4657147e869`.

JFR recorded 99 socket-read events totalling about 4.001 seconds in the facet exchange.
Over the enclosing capture interval, the 500m-limited Redis container recorded 76 additional
CPU-throttle events and about 3.655 seconds of additional throttled time. The capture includes
fixture creation, validation, warmup, measurement and cleanup: neither number isolates the
measured iterations or establishes CPU throttling as the sole cause. The small CPU stack sample
is insufficient for a reliable execution-time percentage.

This directs investigation toward Redis command execution and response waiting, rather than
another allocation-only change. It does not justify increasing capacity for only the candidate,
removing row-level safety checks or rerunning the full matrix without a changed implementation.
The >=2x throughput and p99 criteria were unchanged and open at the time of this diagnostic;
see the later release exception above for the current raw-T4 acceptance status.

### Architecture Options: Not Acceptance Evidence

The **current safe choice** retains authoritative per-row liveness, tombstone and tenant-owner
checks, bounded reads and observable ACL/error ordering. Multi-window requests already share a
pipeline. Further protocol or allocation changes need a quantified removable-work hypothesis;
neither fewer replies nor a shorter Lua script proves lower Redis CPU cost.

A **future, explicit opt-in** could preaggregate declared facet/filter shapes in an exclusively
write-owned namespace. Atomic entity/index updates and fenced rebuilds with atomic generation
publication would need to cover updates, deletes, tombstones and tenant moves. All writers must follow that protocol;
an index generation alone cannot detect external mutations. This could move eligible query work
from O(rows) toward O(groups), at the cost of write amplification, memory and contention. It is
a proposed capability, not implemented functionality or a measured speedup.

Writer isolation must enforce eligibility; an index marker is not sufficient. Eligibility must
exclude untracked writes and independent TTL expiry, unless an exact expiry
protocol is provided; asynchronous expiry notifications are insufficient. Aggregate-key access
does not prove permission to read every underlying key. Authorization must therefore be explicit
for this new capability; callers requiring existing per-row ACL/error semantics are ineligible.
Tenant isolation, null/missing-group semantics and output budgets remain required. Unknown or
ineligible generations must reject the shortcut or use the existing authoritative path.

Silently trusting index membership, skipping metadata length checks, or caching mutable owner
proofs is **not an optimization of the existing contract**. A future opt-in needs its own
correctness and performance qualification; it cannot close or redefine the current T4 gate.

The [read-coalescing profile](../tools/benchmarks/redis/src/coalescing/README.md) and
[optional feature profile](../tools/benchmarks/redis/src/features/README.md) document isolated builds,
fixed controls and independent checkers. Script availability, synthetic checker self-tests and functional
tests do not establish measured observation/async/tracking performance. The actual Micrometer NOOP versus
disabled point-p50 criterion remains <=3%; real-handler overhead is reported separately.
Track final evidence in the [release checklist](release-checklist.md), not as a release PASS.

### Optional Feature Measurements

The completed 24-cell feature matrix used the tool JAR with SHA-256
`9ac273ac590bd162dc25e4ddfabe0e20e42ac7287b763f25ec9ad6c71e835b45`.
The independent checker verified all fork/iteration controls and the numeric gates below against
the unchanged raw results. Its console parser was corrected to recognize JMH's iteration prefix;
the earlier checker failure remains recorded, rather than being overwritten.

| Feature | Measured comparison | Interpretation |
| --- | --- | --- |
| Observation, L1 hit | NOOP/disabled p50: 1.017013x | PASS: 1.70% overhead, limit 3% |
| Observation, L1 miss | NOOP/disabled p50: 0.902072x | PASS: within the 3% limit |
| Observation, L1 hit with a registered handler | Handler/disabled p50: 2.045369x | Report only; not the NOOP result |
| Native async miss | Complete 1000-operation windows measured | Report only; window latency divided by 1000 is not a per-request percentile |
| Server tracking versus Pub/Sub | Read/write cells, actual modes and PUBLISH deltas verified | Numeric comparison is report only, not a general speedup claim |

Review confidence intervals before applying these numbers to capacity planning. These results do
not close the coalescing or aggregation gates and do not replace correctness, Cluster, failover,
native-memory or deployment acceptance.

## Reproduce

Use a dedicated Redis instance. The tool creates a unique key prefix for every
fixture and removes only that prefix; it never runs `FLUSHDB` or `FLUSHALL`.
Do not point it at production. SQL access fails immediately in these fixtures.

```bash
mvn -B -ntp -Predis-benchmarks -pl tools/benchmarks/redis -am -DskipTests package
java -Dcachedb.bench.redis=redis:6379 \
  -Dcachedb.bench.output=target/jmh-result.json \
  -jar tools/benchmarks/redis/target/benchmarks.jar
pwsh tools/ci/compare-benchmarks.ps1 -SelfTest
pwsh tools/ci/check-jmh-evidence.ps1 -Path target/jmh-result.json
```

The original committed reference uses three forks and five one-second warm-up
and measurement iterations. The paired release gate uses the same three forks
and five iterations, with **three seconds per iteration on both versions**, both
throughput and sampled latency, and the GC profiler. Each fork uses a 512 MiB heap
and, by default, two active processors. The
foreground Jedis pool is bounded to 32 connections. Both paired binaries use an
explicit **fair** pool, with a ten-second borrow timeout and unchanged connection
and read limits. The published 0.18.1 runtime remains unmodified: its supported
Jedis client injection accepts that same configuration. This is a configured
reference, not a measurement of its original unfair factory default. Use an isolated validation
environment with fixed CPU and memory limits, and record those limits with the results.

At high virtual-thread concurrency, an unfair pool can let a few fast callers
repeatedly overtake waiting callers. Aggregate throughput alone can conceal
starvation and borrow timeouts. Qualify both total throughput and progress of
every caller; do not increase pool size or timeout merely to conceal starvation.
Retain the original committed reference and unsuccessful diagnostic runs with
their original settings. Do not compare those scores directly with the fair,
longer paired run.

## Paired Release Gate

Build the published baseline tool first and retain its JAR before building the
candidate. Do not run `clean` between the two builds: it removes root evidence.

```bash
mkdir -p target
mvn -B -ntp -f tools/benchmarks/redis/pom.xml \
  -Dcachedb.benchmark.runtime.version=0.18.1 -DskipTests package
cp tools/benchmarks/redis/target/benchmarks.jar target/baseline-benchmarks.jar
mvn -B -ntp -Predis-benchmarks -pl tools/benchmarks/redis -am -DskipTests package
sh tools/benchmarks/redis/prepare-jmh-compactor.sh \
  tools/benchmarks/redis/target/benchmarks.jar target/compactor-selfcheck
export CACHEDB_BENCH_COMPACTOR_DIR="$PWD/target/compactor-selfcheck/helper-classes"
CACHEDB_BENCH_CASE=all-comparable CACHEDB_BENCH_REDIS=127.0.0.1:16383 \
  sh tools/benchmarks/redis/run-paired.sh \
  target/baseline-benchmarks.jar tools/benchmarks/redis/target/benchmarks.jar target/paired
CACHEDB_BENCH_CASE=virtual CACHEDB_BENCH_REDIS=127.0.0.1:16383 \
  sh tools/benchmarks/redis/run-paired.sh \
  tools/benchmarks/redis/target/benchmarks.jar tools/benchmarks/redis/target/benchmarks.jar target/candidate-virtual
pwsh tools/ci/merge-paired-benchmarks.ps1 -Directory target/paired \
  -LegacyVirtualLivenessEvidence tools/benchmarks/baseline/v0.18.1-virtual-liveness/failure.json \
  -CandidateVirtualResult target/candidate-virtual/virtual-candidate.json
```

Every scenario is measured on both binaries before moving to the next scenario;
pair order alternates deterministically. Outputs include every raw run and log.
Existing output directories are rejected rather than overwritten. A selected
diagnostic case (`CACHEDB_BENCH_CASE=miss1`) cannot satisfy the full 22-case merge.
One-CPU fixtures may set `CACHEDB_BENCH_CPUS=1` and pass `-ProcessorCount 1` to the
merger. Never compare their absolute scores with two-CPU results.

Virtual paired runs use the identical external one-class JMH sample-compaction overlay on both
JARs; neither immutable baseline nor JMH/SDK artifacts are modified. Preparation runs the five-check
assert-main against the packaged helper, not a recompiled shadow source. It registers before GC;
the helper is a no-op outside 1000-worker SampleTime, so the paired runner's existing combined
Throughput/SampleTime invocation is unchanged. Keep the preparation proof, helper class and its
before/after hashes with both versions' complete controller arguments and raw results.
This controller-result fix does not resolve or exempt the legacy SDK's virtual-worker liveness failure.

Before the release matrix, CI runs a point-read A/A control using the same old
JAR for both sides. The normal 10% throughput / 15% p99 gates apply to that
control too. A failed control means the environment is not qualified to attribute
a difference to code; it is not permission to raise thresholds or select a lucky
repeat. A passing control qualifies that case, not every possible workload.

For code-level comparisons, co-locating Redis and the benchmark client removes
cross-node overlay variability. It does not certify production network latency.
CPU requests and limits are not proof of exclusive physical cores; record
topology, throttling and concurrent-load limitations explicitly. Keep failed raw
evidence and do not combine selectively passing cases into a release result.

### Legacy Virtual-Worker Liveness Exception

The published 0.18.1 runtime stalled with 1,000 virtual workers on the recorded
one-CPU fixture, even with a fair 32-connection pool. A virtual-thread dump shows
carrier pinning in `RedisProducerGuard.sampleSafely` while borrowing a Redis
connection. The stalled fork was terminated **after** diagnostic capture; its
exit 143 is that intervention, not a spontaneous crash. See the
[hashed failure evidence](../tools/benchmarks/baseline/v0.18.1-virtual-liveness/failure.json).

This attempt has no valid numeric legacy virtual-worker baseline. It is never
represented as zero throughput, a latency percentile or a successful comparison.
The 20 comparable cases still use the unchanged regression thresholds. The
candidate must independently complete all 22 measurements, including both virtual
worker modes with three forks and five warmup/measurement iterations.

CI uses `CACHEDB_BENCH_CASE=all-comparable` for the paired matrix, then runs
`CACHEDB_BENCH_CASE=virtual` with the candidate JAR on both passes. Its merger
requires both the verified legacy evidence and the complete candidate result:

```bash
pwsh tools/ci/merge-paired-benchmarks.ps1 -Directory target/paired \
  -LegacyVirtualLivenessEvidence tools/benchmarks/baseline/v0.18.1-virtual-liveness/failure.json \
  -CandidateVirtualResult target/candidate-virtual/virtual-candidate.json
```

Missing, changed or unrelated failure evidence is rejected. This exception applies
only to that recorded 0.18.1 case, not to candidate failures or other missing data.
The elapsed no-progress interval is explicitly operator-attested; thread stacks
establish the pinned path, not a formal deadlock graph or a production SLA.

## Cases and Boundaries

| Case | Measured work |
| --- | --- |
| `l1HitFindById` | One immutable entity read from a prepared L1 entry |
| `l1MissFindById` | Redis-backed point read at 1, 16 and 64 platform threads |
| `l1MissFindByIdVirtual1000` | Individual Redis-backed reads from 1,000 virtual workers |
| `findAllMixed100` | One 100-ID call, with 50 L1 entries invalidated before each invocation |
| `aggregateCount10k` | Count over 10,000 live rows, with and without tenant scope |
| `facet10k20Groups` | 20-group facet over the same 10,000 rows |
| `saveSingle` | Redis write acceptance and stream append, without SQL flush |

The mixed case includes a small deterministic, in-memory invocation reset. Compare
it only with the same fixture; it is not a pure network round-trip measurement.
The legacy invalidation subscriber uses the client's finite blocking-read timeout.
An idle disconnect clears L1 and temporarily takes the correct Redis fallback;
retain its warnings in the raw log. The hit workload is not evidence that every
invocation used L1 or that invalidation remained uninterrupted.
The virtual executor creates a virtual thread for each JMH worker task, with a
hard 1,000-worker semaphore; it does not pool virtual threads. Sampled percentiles
are per repository call, not batch time divided by the number of requests.

For the virtual-worker case, JMH's optional spin-based iteration rendezvous is
disabled: JMH 1.37 does not yield in that rendezvous for a custom executor, which
can starve oversubscribed virtual threads. Setup/teardown barriers remain enabled.
All baseline and candidate virtual runs use this same setting. This is a harness
setting, not a change to repository concurrency or a removed measurement phase.

For the write case, a separate daemon trims the benchmark-only stream to
approximately 8,192 entries every 100 ms. This maintenance cost shares Redis with
the measured write path in both versions. A dedicated Redis `maxmemory` limit
with `noeviction` remains the hard memory bound: exhausted memory fails the run.
Default write backpressure remains enabled and can activate because this fixture
does not run SQL workers. Therefore `saveSingle` is not an isolated `XADD` latency
test. Fixture rows are seeded through bounded 128-row `saveAll` calls before timing.

## Read the Results

### Measurement Contract

The load generator preserves the configured worker count. A mixed workload with
one worker uses one mixed worker; disabling read/write pool separation does not
turn reads into writes. Pure-read and pure-write profiles keep their own modes.

Observer cost is not assumed to be zero. To probe it separately on the approved
Linux test environment, prepare the resource helper as described in
`tools/benchmarks/redis/RESOURCE-COLLECTION.md`, then run:

```sh
export CACHEDB_BENCH_REDIS=127.0.0.1:6379
sh tools/benchmarks/redis/calibrate-jmh-resources.sh \
  /workspace/benchmarks.jar /workspace/evidence/observer-calibration-NEW
```

This short probe uses the same real L1 read benchmark and artifact in four
conditions: no profiler, GC only, resource collection only, and both. It reverses
the condition order and retains every observation. Its short runs diagnose
observer interference, not production capacity, zero overhead, or the full
performance acceptance matrix. Never subtract observer cost from product latency.

Preserve each raw JSON together with its `<result.json>.instrumentation` companion.
Comparison verifies the raw-file hash and requires matching ordered profilers,
helper hashes and sampler settings for corresponding cases. Missing or incompatible
measurement contracts fail closed. Strict matrix acceptance requires at least
three real forks and five 3-second warmup and measurement iterations per fork.
An older 1-second reference may remain valid historical evidence without meeting
that contract. Its metadata must not be rewritten to make it pass.

`measurementVersion: 2` production reports count only successful operations in
`achievedTransactionsPerSecond`. `executedOperations` remains the number of
attempts, including failures. The additional attempt rate and separate success/error
latencies prevent fast failures from appearing as useful throughput. Elapsed time
uses a monotonic clock. An accepted asynchronous write is not a durable SQL commit;
the existing drain result is a separate gate.

Legacy latency fields retain the all-attempt execution population. Pacing and
post-load drain are excluded. Scheduled-start lag is reported separately: these
bounded workers are not an open-loop arrival generator. Histogram percentiles are
bucket upper bounds, not exact quantiles. Above the last 5-second bucket, the
observed maximum is returned conservatively with an overflow flag and count; a
12-second delay can no longer appear as a 5-second p99. Worker-local counters merge
once per worker, avoiding shared per-operation atomic accounting.

The Kubernetes HTTP smoke reports both attempted and successful wall-clock RPS.
Its latency includes the client request, JSON decoding and result validation.
Old `requestsPerSecond` and percentile fields retain their attempt-based meaning;
separate success/error populations and ordered raw samples are also written.
This is an HTTP smoke, not a repository microbenchmark or a capacity certificate.

The five-sample COUNT/FACET diagnostic labels inverse mean serial read time as
`service_time_derived_ops_per_second`, not concurrent RPS. It also records wall time
including verification, verification time and ordered raw durations. Its p95 is the
largest of five observations. Redis-wide network deltas include observer traffic.
Neither that diagnostic nor a snapshot read replaces raw-query T4 acceptance.

| Memory metric | Meaning | Not interchangeable with |
| --- | --- | --- |
| JMH `gc.alloc.rate.norm` | Allocated bytes per benchmark operation | Live heap or RSS |
| Process RSS / VmHWM | Resident process pages / lifetime resident high-water mark | Measurement-only peak or Redis payload |
| Cgroup current / peak / events | Container or group usage, including applicable cache and descendants | A particular JVM's RSS |
| Redis `used_memory` / `used_memory_rss` | Redis allocator accounting / Redis resident pages | Client JVM memory |
| Redis key `MEMORY USAGE` | One key's sampled Redis footprint | Full route, indexes, leases or total server RSS |

Keep controller, forked JVM and Redis memory identities separate. Missing metrics
must be marked unavailable, never zero. Sampled measurement-phase maxima are lower
bounds on the real peak; process VmHWM and cgroup peak may include setup. Do not run
recursive filesystem size scans, forced GC, JFR or NMT inside timed phases without
declaring a distinct instrumented experiment. Resource requests and limits are not
dedicated cores. Capture CPU throttling separately for the actual container and
phase before attributing changes to code.

For `findAllMixed100`, JMH excludes `Level.Invocation` preparation from primary
timing, while iteration-wide allocation accounting can include that preparation.
The allocation number is not exclusively the repository method's allocation.
An operation remains one 100-ID call, never 100 independent requests.

JMH comparison thresholds are point-estimate regression checks, not significance
tests. Review raw forks, confidence intervals and sample populations separately;
the mean's confidence interval is not the p99 confidence interval. Historical
results are not recalculated or promoted to PASS by this measurement contract.

JMH JSON contains raw measurements, confidence intervals, percentiles and GC
allocation rates. Compare the same benchmark, mode, parameters, thread count,
units and environment. The gate rejects a throughput decrease above 10% or a p99
increase above 15%; missing cases or invalid numbers also fail. Confidence
intervals and pod CPU throttling still need review before attributing a change to
code. Never call a result an improvement solely because its mean is lower.

The 0.18.1 baseline precedes runtime changes. Features absent from 0.18.1 receive
their first baseline when introduced; they are not emulated or assigned invented
historical results. The benchmark tool is not published or included in the BOM.

The CI performance lane reruns the published 0.18.1 artifacts and the candidate on
the same runner, sequentially, before applying these thresholds. The committed
JSON is a historical reference, not a substitute for matching hardware. Comparing
Kubernetes measurements directly with a different GitHub-hosted machine would
confuse infrastructure differences with a framework regression.
