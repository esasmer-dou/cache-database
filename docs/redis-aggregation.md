# Redis Aggregation And Facets

## Eligibility And Isolation

`aggregate(AggregateSpec)` and `aggregateBy(group, AggregateSpec)` operate on the bounded active Redis set, not SQL history.
The single-call index paths require complete index-only candidate resolution, indexed aggregate/group columns,
`COUNT_WINDOW`, and no filter containing the ambiguous literal `__NULL__`. Other shapes retain the payload/pipeline path.

For tenant entities an explicit tenant context is required unless the caller has administrative scope.
Tenant-scoped candidate resolution is not an authorization boundary: the selected native transaction or Lua script checks entity existence,
absence of a tombstone, and equality of `<versionKey>:tenant` with the active tenant in one atomic execution.
Missing or foreign owner markers exclude the row before its metadata is read. Administrative/global reads do not add an owner filter.
No owner marker is initialized or repaired by a read.

Eligible standalone tenant COUNT(*) operations above 512 and up to 10,000 candidates can pipeline
bounded native transactions through one reserved connection. Each window has at most 512 rows and
516 commands; existence, tombstone and bounded owner reads are atomic within that window. The
whole operation is not a snapshot. Native request bytes plus conservatively framed expected replies
must fit 16 MiB before the connection is borrowed. Small-tail compatibility rules, larger operations,
unsupported native capabilities and mixed liveness retain the existing fallback paths. Native replies
are drained and validated before the lease is reused; cancellation or uncertain protocol state discards it.

All script keys, including owner keys, must share a Cluster slot. Unsupported placement returns `Optional.empty()`
before EVAL; the repository retains its bounded pipeline fallback. A tenant fallback decodes the payload and applies
the scoped predicate and tenant verification rather than trusting index membership. This is the existing payload-based
isolation contract, not a claim that independent slots provide an atomic owner/payload snapshot.
No Redis key layout or hash tags are changed. Use the existing hash-tag key strategy when co-location is wanted.

## API And Semantics

The original `aggregateIndexBatch(entities, tombstones, indexMetadata, field, countOnly)` remains available.
The new overload adds `ownerKeys, tenant` before `field, countOnly`. Owner keys and tenant must both be null
for global reads or both be present with aligned lengths for tenant reads.

`facetIndexBatch(entities, tombstones, indexMetadata, ownerKeys, tenant, groupField, valueField)`
returns raw `[groupRaw, valueRaw|false]` pairs plus their original candidate positions.
`FacetIndexBatch.rows()` exposes immutable `FacetIndexRow(candidate, groupRaw, valueRaw)` entries.
An unrequested value field is represented as Java null; a stored empty string is valid, not missing metadata.
Absent required hash fields fail deterministically with a rebuild-indexes error.

On eligible standalone COUNT(*) facets above 512 candidates, optional `facetIndexCounts` returns
bounded group/count maps and original positions for ambiguous null markers. The repository adds
weighted counts without constructing a row result for every candidate. Owner and liveness checks
still run for every admitted row. Operation-wide limits count original rows, not compact groups.
COUNT(field), numeric facets, custom adapters and Cluster retain their existing paths.

COUNT(*) counts live admitted rows. COUNT(field) ignores actual nulls, but counts a literal `__NULL__` string.
SUM, MIN, MAX and AVG retain the existing Java numeric/count semantics, including live rows with null numeric fields.
Raw decimal text is never converted to a Lua number: Java bounded BigDecimal parsing and DECIMAL128 averaging remain authoritative.

`__NULL__` is intentionally not a new wire format. A sentinel group, or a sentinel value for COUNT(field),
triggers a targeted payload re-read. `liveIndexPayloadBatch` rechecks live/tombstone/owner state atomically with that fetch.
The repository then re-applies the scoped predicate, serving policy and tenant verification.
True null groups remain omitted by `FacetAccumulator`; a literal `__NULL__` group remains a real group.
Non-COUNT null numeric values continue to use the existing null marker semantics.
A multi-batch operation is not a snapshot, and the metadata read and sentinel re-read are separate observations.

## Hard Limits And Failures

- Each index or sentinel batch has 1..512 aligned candidates; larger batches are rejected, not silently truncated.
- Keys have at most 16,384 UTF-16 units; fields and the low-level tenant argument have at most 4,096 units.
- Script key/argument data is admitted with a conservative three-bytes-per-UTF-16-unit bound of 16 MiB.
- Lua uses HSTRLEN/STRLEN before HGET/GET, rejecting more than 16 MiB of admitted metadata/payload bytes before fetching the offending field.
- Java additionally checks a 16 MiB two-bytes-per-UTF-16-unit content budget. This is not a total JVM heap or RESP framing guarantee.
- Facets retain the operation-wide 16 MiB accounting, 256 groups, 4,096-unit group keys and 100,000 candidates. The configured index materialization cap may be lower.

Budget overflow, incomplete metadata, malformed replies and numeric overflow are failures, never partial results.
Topology/shape unavailability or an explicitly recognized native capability/owner-read permission rejection can select
the existing Lua fallback. Unrelated ACL, key-type, storage and malformed-reply errors remain failures, never empty success.
Read-only scripts add no retry loop, lock, thread, executor, dependency or runtime reflection.
Existing Jedis connection/socket timeouts and caller failure handling remain in force. Do not retry budget/schema errors.
Observe request latency, Redis commandstats/EVAL time, errors and Cluster fallback frequency through existing operational tooling.
Bound caller concurrency and pool capacity; a bounded Lua batch still occupies Redis' command execution thread.

## Causal Reads

These operations do not certify an applied entity version. Relevant active causal requirements are rejected
with CacheCausalReadUnsupportedException rather than returning an uncertified aggregate.
Use version-verified point/bulk reads for causal guarantees; context propagation alone is not certification.
Compare index and payload results and measure Lua cost on representative tenant/Cluster workloads before rollout.

## Performance Qualification

The >=2x throughput criterion for facet10k20Groups and tenant aggregateCount10k versus the preserved
0.18.1 baseline remains FAIL / OPEN. Diagnostic runs have not met it. Functional equivalence does not
close this gate, and fewer round trips do not eliminate Redis CPU work. Keep ownership, liveness,
tombstone and byte-budget checks intact. Use the [versioned Function upgrade contract](redis-functions-upgrade.md)
and retain a complete paired comparison of the exact final artifacts before claiming a gain.
See [evidence scope](performance-benchmarks.md).
