# Redis Performance Benchmarks

The Redis JMH tool measures the framework's public repository paths. It is not a
claim about an application's end-to-end latency or SQL commit time. Benchmark
results apply only to the recorded JVM, CPU limits, Redis deployment and network.

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
pwsh tools/ci/compare-benchmarks.ps1 \
  -Baseline tools/benchmarks/baseline/v0.18.1.json \
  -Current target/jmh-result.json -Summary target/benchmark-summary.md
```

The fixed acceptance run uses three forks, five one-second warm-up iterations,
five one-second measurement iterations, both throughput and sampled latency, and
the GC profiler. Each fork uses a 512 MiB heap and two active processors. The
foreground Jedis pool is bounded to 32 connections. Use an isolated validation
environment with fixed CPU and memory limits, and record those limits with the results.

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
