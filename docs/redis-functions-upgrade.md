# Redis Functions Upgrade To 0.19.0

[Türkçe](../tr/docs/redis-functions-upgrade.md)

## Versioned Names

CacheDB 0.19.0 loads a side-by-side Redis Function library rather than replacing the legacy default.
The entity, version, tombstone, stream and index key layout is unchanged by this naming change.

| Setting | 0.18.1 default | 0.19.0 default |
| --- | --- | --- |
| libraryName | cachedb | cachedb_v019 |
| libraryVersion | 0.18.0 | 0.19.0 |
| upsertFunctionName | entity_upsert | entity_upsert_v019 |
| deleteFunctionName | entity_delete | entity_delete_v019 |
| compactionCompleteFunctionName | compaction_complete | compaction_complete_v019 |

The bounded delete entry point is derived from deleteFunctionName: entity_delete_v019_bulk.
Keep that derived name disjoint too. Redis function names must be unique across libraries, not merely
within each library; changing only libraryName or libraryVersion is insufficient.
See the [Redis FUNCTION LOAD contract](https://redis.io/docs/latest/commands/function-load/).

The loader stores version and rendered-source SHA-256 in
cachedb:function-library:<libraryName>:marker and coordinates loading through that marker's :lease key.
A distinct library name therefore has a distinct marker and loading lease. Normal startup and a definite
missing-function reload preserve the other library and its marker. Same-version content drift and
same-library downgrades remain errors; do not disable strict loading to bypass them.

## Default Upgrade And Rollback

Use the 0.19.0 names together. Retain the old library, its marker and its three function names while
0.18.1 instances or rollback binaries may still use them. Do not issue FUNCTION FLUSH, delete the old
marker, or globally replace the old library. Do not reset entity/version counters during this upgrade.
Inventory and verify the required library on every Redis primary before serving traffic; a standalone
coexistence test is not Cluster or failover qualification.

Side-by-side names preserve access to the legacy unprotected write contract. They do not make old
producers participate in tenant-generation fencing, protected purge, encrypted storage or new queue
protocols. Follow each feature's all-writer migration contract before enabling it on shared data.
Library coexistence alone is not application rollback certification.

## Explicitly Pinned Custom Names

Custom names are not automatically suffixed. If configuration pins cachedb, entity_upsert,
entity_delete or compaction_complete, the new defaults cannot isolate that deployment.

Choose a new library name and all three function names for the new canonical source, including the
derived delete suffix. Set libraryVersion=0.19.0 explicitly and update every corresponding caller;
keep the old names/source/version intact for legacy callers. Verify ACLs and startup registration
before admitting writes. A custom template or sourceOverride remains responsible for registering all
configured entry points and for its own wire contract.

The ten-argument RedisFunctionsConfig constructor now falls back to libraryVersion=0.19.0 while
preserving the names passed to it. It does NOT automatically rename them. To deliberately deploy the
legacy template, use the eleven-argument constructor or builder with the exact legacy source and
an explicit 0.18.0 version pin. Do not attach a new body to an unchanged legacy version.

If a custom naming contract cannot coexist, use separately approved, isolated Redis instances for
old/new verification. Preserve identical resources, data and workload controls and record the isolation
in the evidence; a different endpoint is not silently interchangeable with the original paired fixture.
Do not alter the preserved baseline JAR or mask a startup error as a numeric benchmark.

## Verification Scope

RedisFunctionsVersionContractTest checks defaults, constructor pins and the historical fixture digest.
RedisFunctionLibraryCoexistenceRedisTest uses unique fixture-owned libraries/function names and the
exact 0.18.1 Lua from commit 3a212461fd835c783cf84d3c6b656b375df40a19. It exercises both write
contracts, compaction/completion, missing-library reload and marker/code stability, while retaining
negative drift/downgrade checks. Cleanup deletes only its two libraries and explicitly owned keys.
These are test contracts, not a statement that a release, benchmark or mixed application fleet passed.

The 0.19.0 aggregation/facet throughput gate remains open. A library-content-drift startup failure
produces no numeric result. Earlier read-coalescing measurements apply only to their recorded artifact;
see [performance evidence boundaries](performance-benchmarks.md).
