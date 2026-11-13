# Generated Repository Async Access

CacheRepositoryProcessor generates `asyncExecutor()` by delegating to the underlying EntityRepository.
The generated CacheDbRepository's no-argument `async()` and `asyncWithReceipt()` therefore use the runtime-owned
gate, without creating an executor or admission counter per repository or call.
The AsyncCacheDbRepository API preserves mutation receipts; it does not silently map save/delete receipts to entities.

Custom repositories without a runtime gate fail explicitly. Supply an explicitly owned CacheAsyncExecutor to
`async(executor)` or `asyncWithReceipt(executor)` when using such an implementation.
Close an application-owned gate after its operations drain; the generated repository does not own it.

Recompile entity/repository sources to regenerate bindings after upgrading. Already compiled generated classes
do not gain this delegation retroactively. Generated implementations use direct calls, not query proxies,
reflective property access or runtime method-name parsing.

This receipt-oriented facade can use bounded worker offloading. Native RedisEntityRepository read eligibility
is described in [native async reads](redis-native-async.md); a shared gate alone does not promise a no-offload path.

Tenant, route and causal checks remain the backend's responsibility.
Causal point/bulk reads preserve native version verification; query/aggregate operations without a certified
applied-version protocol reject relevant causal requirements rather than return uncertified results.
