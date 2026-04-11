# Production Test Report

This document summarizes the current production-like smoke test results for the `cache-database` e-commerce DAO module and outlines the next full benchmark phase.

## Current Smoke Results

Environment used for validation:

- Redis: `redis://default:welcome1@127.0.0.1:56379`
- PostgreSQL: `jdbc:postgresql://127.0.0.1:55432/postgres`
- PostgreSQL credentials: `postgres / postgresql`
- Test class: `EcommerceProductionScenarioSmokeTest`

Executed scenarios:

| Scenario | Kind | Ops | Reads | Writes | Failures | Avg Latency | Max Latency | Write-Behind Backlog | DLQ | Health |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---|
| `campaign-push-spike-smoke` | LOAD | 72 | 38 | 34 | 0 | 27,224 us | 107,732 us | 434 | 0 | `DEGRADED` |
| `write-behind-backpressure-breaker-smoke` | BREAK | 42 | 10 | 32 | 0 | 48,305 us | 117,131 us | 432 | 0 | `DEGRADED` |

## What These Results Mean

Positive signals:

- The DAO layer handled mixed read/write traffic without runtime failures.
- The Redis-first path remained functional under both balanced and write-heavy traffic.
- No dead-letter events were produced in either smoke run.
- The production test module generated machine-readable and human-readable reports successfully.

Observed bottleneck:

- Both runs ended with a non-trivial write-behind backlog.
- This indicates the primary near-term throughput limit is not Redis mutation speed but PostgreSQL flush capacity and drain rate.
- The `BREAK` scenario showed materially worse latency than the mixed-load scenario, which is consistent with inventory/order write pressure.

Current interpretation:

- The architecture is functioning correctly at smoke scale.
- Production readiness for burst traffic is not proven yet.
- The system is currently more likely to degrade by accumulating asynchronous flush backlog than by failing fast.

## Main Risks Before Production

1. Write-behind saturation.
   PostgreSQL flush workers may fall behind during campaign spikes, creating sustained backlog and delayed persistence.

2. Health degradation under sustained bursts.
   Even without DLQ growth, the system can remain alive but degraded for too long if backlog drain time is unacceptable.

3. Hot SKU contention.
   Flash-sale patterns with concentrated inventory writes can amplify latency and cause unfairness across workers.

4. Cache churn under wide catalog scans.
   Large browse storms with low hot-set budgets may reduce Redis efficiency and increase read-through pressure.

5. Benchmark gap.
   Current validation is intentionally reduced-scale smoke testing. It does not yet prove behavior at 10k, 25k, or 50k TPS classes.

## Guardrail Alerts And Runbooks

Recommended production alert set:

| Alert | Warning Trigger | Critical Trigger | Primary Risk | First Operator Action |
| --- | --- | --- | --- | --- |
| `WRITE_BEHIND_BACKLOG` | backlog above warning threshold | backlog above critical threshold | PostgreSQL drain saturation | slow producers, inspect flush throughput, check drain completion |
| `COMPACTION_PENDING_BACKLOG` | pending compaction above warning threshold | pending compaction above critical threshold | Redis memory growth and stale flush lag | confirm compaction worker health and watch runtime profile switching |
| `REDIS_MEMORY_PRESSURE` | used memory above warning budget | used memory above critical budget | Redis eviction pressure and degraded serving | move to stricter guardrail profile, reduce hot-set/page-cache budgets |
| `REDIS_HARD_REJECTIONS` | n/a | any rejected write | bounded-memory protection is dropping writes | pause campaign traffic and restore drain capacity immediately |
| `QUERY_INDEX_DEGRADED` | namespace marked degraded | degraded namespace not rebuilt within SLA | query latency rises due to full-scan fallback | trigger query-index rebuild and verify hard-limit conditions have cleared |
| `TOMBSTONE_BUILDUP` | tombstone count rising faster than delete baseline | tombstone TTL not draining | delete-heavy churn or drain lag | inspect delete traffic, confirm stale resurrection protection, verify TTL cleanup |

Suggested operator runbook:

1. If backlog and memory are both rising, treat PostgreSQL drain capacity as the primary suspect before touching Redis sizing.
2. If hard rejections appear, stop non-critical campaign traffic first; the system is protecting bounded memory at the cost of availability.
3. If a namespace is in degraded query-index mode, confirm hard-limit pressure is gone and trigger `/api/query-index/rebuild`.
4. If tombstones remain high after pressure falls, inspect delete-heavy workloads and verify write-behind drain plus tombstone TTL settings.
5. Do not disable tombstones to recover throughput; that reintroduces stale resurrection risk.

## Admin Metrics Export

Admin HTTP now exposes a Prometheus-compatible scrape endpoint:

```text
GET /api/prometheus
```

Exported metrics include:

- write-behind, DLQ, reconciliation, and diagnostics stream lengths
- write-behind worker flush/coalescing/dead-letter counters
- dead-letter recovery replay/failure counters
- planner statistics key counts
- Redis used memory, peak memory, compaction pending, payload count, and hard rejections
- runtime profile label and switch count

This allows direct scrape integration for production alerting without relying only on the built-in dashboard.

## Production Certification Runner

The production test module now includes a certification entry point:

```powershell
mvn -q -pl cachedb-production-tests -am exec:java `
  "-Dexec.mainClass=com.reactor.cachedb.prodtest.scenario.ProductionCertificationMain"
```

The certification report combines:

- one representative benchmark run
- restart/recover verification using the real Redis/PostgreSQL stack
- explicit gates for throughput, backlog, failures, hard rejections, rebuild success, and restart recovery

Generated files:

- `target/cachedb-prodtest-reports/production-certification-report.json`
- `target/cachedb-prodtest-reports/production-certification-report.md`

## Semantics And Shedding Matrix

| Entity / Query Class | Persistence Semantics | Hard-Limit Behavior | Recovery Path |
| --- | --- | --- | --- |
| `customer` | `LATEST_STATE` | cache, index, and learning may shed aggressively | auto or admin query-index rebuild |
| `inventory` | `LATEST_STATE` | cache and index paths may shed aggressively to preserve latest stock truth | auto or admin query-index rebuild |
| `cart` | `LATEST_STATE` | mutable session state, aggressive shedding allowed | auto or admin query-index rebuild |
| `order` | `EXACT_SEQUENCE` | query indexes can stay more conservative; write ordering is preserved | rebuild indexes without changing persistence ordering |
| exact lookup queries | indexed when healthy | can remain enabled if namespace policy allows | no rebuild required if not degraded |
| text / relation / sort-heavy queries | indexed when healthy | preferred shed candidates under hard-limit | full-scan fallback until rebuild completes |

## Recommended Full Benchmark Plan

The repository now includes a dedicated full-scale suite entry point:

```powershell
mvn -q -pl cachedb-production-tests -am exec:java `
  "-Dexec.mainClass=com.reactor.cachedb.prodtest.scenario.FullScaleBenchmarkMain" `
  "-Dcachedb.prod.scaleFactor=1.0" `
  "-Dcachedb.prod.redis.uri=redis://default:welcome1@127.0.0.1:6379" `
  "-Dcachedb.prod.postgres.url=jdbc:postgresql://127.0.0.1:5432/postgres"
```

The full-scale suite currently covers eight distinct `50k TPS` scenarios across browse-heavy, checkout-heavy, contention-heavy, cache-thrash, and backpressure cases.

There is also a scale-ladder entry point for staged runs:

```powershell
mvn -q -pl cachedb-production-tests -am exec:java `
  "-Dexec.mainClass=com.reactor.cachedb.prodtest.scenario.ScaleLadderBenchmarkMain" `
  "-Dcachedb.prod.scaleLadder=0.10,0.25,0.50,1.0" `
  "-Dcachedb.prod.redis.uri=redis://default:welcome1@127.0.0.1:6379" `
  "-Dcachedb.prod.postgres.url=jdbc:postgresql://127.0.0.1:5432/postgres"
```

### Phase 1: Controlled Load Ramp

Goal:

- establish safe throughput envelopes
- find the first sustained degradation point

Recommended steps:

1. Run `campaign-push-spike` at `scaleFactor` values `0.05`, `0.10`, `0.20`.
2. Record:
   - ops/sec
   - average and p95 latency
   - write-behind backlog growth
   - drain time after load stops
   - PostgreSQL CPU and IO
3. Stop increasing when backlog no longer drains within the defined recovery window.

Success criteria:

- no DLQ growth
- backlog drains within target window
- health returns from `DEGRADED` to `UP`

### Phase 2: Write-Heavy Failure Threshold

Goal:

- locate the write-behind collapse point

Recommended scenarios:

- `write-behind-backpressure-breaker`
- `flash-sale-hot-sku-contention`

Measure:

- queue length slope
- DLQ creation
- recovery worker claims
- order/inventory persistence lag

Success criteria:

- no permanent DLQ accumulation
- recovery remains bounded
- stale writes do not overwrite newer state

### Phase 3: Cache Efficiency Under Browse Storms

Goal:

- understand Redis hot-set and page-cache behavior during catalog-heavy periods

Recommended scenario:

- `weekend-browse-storm`

Measure:

- hot-set eviction rate
- page cache hit ratio
- planner learned-stat growth
- Redis memory footprint

Success criteria:

- eviction remains controlled
- Redis memory stays inside the budget
- read latency does not climb sharply during long scans

### Phase 4: Deliberate Break Tests

Goal:

- verify recovery behavior when the system is pushed beyond its comfortable range

Recommended break tests:

- reduce write-behind workers to `1`
- reduce batch size aggressively
- shrink hot-set limits
- raise checkout and inventory mix
- pause PostgreSQL briefly during load

Expected outcomes:

- backlog grows
- health degrades
- recovery path remains observable
- data consistency protections still hold

## Production Gate Proposal

Before calling the DAO layer production-ready for a real e-commerce campaign path, the following should be true:

- sustained burst tests have been executed at multiple scales
- write-behind drain time is measured and accepted
- Redis memory budget is validated under browse storms
- DLQ remains empty or operationally recoverable
- hot inventory contention does not cause unsafe persistence lag
- operations team has thresholds for `DEGRADED` and `DOWN`

## Next Suggested Deliverables

1. Add long-running benchmark profiles with fixed durations such as `5m`, `15m`, and `30m`.
2. Export p95/p99 latency and backlog slope into benchmark reports.
3. Add optional PostgreSQL pause/fault injection for resilience testing.
4. Add a benchmark comparison document to track runs over time.
