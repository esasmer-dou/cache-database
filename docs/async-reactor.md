# Bounded Async and Reactor APIs

## API contracts

Java 21 or later is required. The asynchronous core API has no Reactor or Spring dependency.
EntityRepository.async(executor) returns AsyncEntityRepository with findByIdAsync,
findHotByIdAsync, findAllAsync, saveAsync, deleteAsync, queryAsync, aggregateAsync and aggregateByAsync.
saveAsync returns the accepted entity T; deleteAsync returns Void, matching EntityRepository.
Completion is NOT evidence of a committed source-database transaction.

CacheDbRepository.asyncWithReceipt(executor) returns the distinct AsyncCacheDbRepository.
Its async(executor) overload also preserves this receipt-bearing contract.
saveAsync/deleteAsync return the REAL WriteReceipt<T, ID>, including entity, namespace, positive
version and acceptedAt. Receipt.entity() is the accepted entity; never fabricate a version or drop
the receipt to imitate EntityRepository. Redis acceptance is not SQL durability.
Hot-cache lookup and explicit source lookup remain separate; no hidden source fallback is added.

CacheSession supplies asyncEntityRepository(metadata, codec[, policy][, executor]).
The no-executor overloads require a session/repository with a configured asyncExecutor();
otherwise they fail explicitly. They do not create a global or per-call executor.

## Executor ownership and backpressure

Create one lifecycle-managed gate per runtime, using the configured trace propagator:

```java
try (var gate = new CacheAsyncExecutor(1024, config.traceContextPropagator())) {
    var async = repository.async(gate);
    var pending = async.findByIdAsync(id);
    // Compose the future; do not block an event-loop thread with get() or join().
}
```

Close the gate before closing the Redis/JDBC pools it uses. A shared default gate is safe only with
explicit ownership/lifecycle. A CacheAsyncExecutor owns its default virtual-thread executor;
an explicitly supplied Executor remains caller-owned and is never closed by the gate.
The configured propagator is essential: the standalone thread-local trace default does not replace
a custom application propagator.

Default concurrency is 1024, absolute maximum 65,536. Each admitted task gets its own virtual
thread, not a pooled virtual thread. Admission uses nonblocking tryAcquire: saturation raises
CacheAsyncRejectedException rather than waiting or growing a queue. The semaphore is fair, but
tryAcquire does not promise FIFO. A borrowed executor must accept promptly or reject, never silently
drop work or block the submitter. Caller-runs execution is rejected before repository I/O starts.

Two capacities are bounded: actual operation permits and unconsumed dispatch records.
Cancellation before start releases the operation permit and prevents repository invocation, but
a cancelled record still queued in a borrowed executor retains its dispatch slot until consumed.
Thus availablePermits can be positive while dispatch admission is saturated. Cancellation storms
cannot accumulate an unbounded queue of dead records. Silent task loss by a borrowed executor is
a contract violation, not a reason to reset capacity while physical work might still run.

Once work starts, cancel(true) suppresses result delivery only: it does not interrupt driver I/O,
undo acceptance, roll back a write, or release a permit early. Capacity is released only after the
actual I/O and context cleanup exit; the future result is then published. A timeout/orTimeout also
does not free running capacity. Keep independent, finite Redis/JDBC/network/pool timeouts.

close rejects new work and waits two seconds by default, configurable up to 30 seconds.
On timeout it requests interruption only of owned workers and reports failure; still-running I/O
remains counted. awaitIdle tracks physical operations, not consumption of cancelled external queue
records. There is no hidden timer or automatic retry. Use a bounded application retry budget/jitter
only where idempotency is established; a timed-out write may already have been accepted.

Future continuations run outside the captured operation context. Expensive or blocking user
continuations need a separately bounded application executor, not repository capacity.

## Inputs and context

ID collections are limited to QuerySpec.MAX_LIMIT (100,000), receipt-bearing saveAll to 1000 entities,
and aggregate facets to 256 groups. QuerySpec limits still apply. Oversized results fail rather
than being silently truncated. Lists are copied at call/assembly time; null IDs/elements fail.
Entity graphs are not cloned: use immutable entities or leave them unchanged until physical I/O
finishes, even if the future was cancelled.

CacheAsyncContext.wrap(configuredPropagator, callback) captures tenant/admin, causal and bounded
configured trace/route state at registration time. Empty contexts are applied too. All decoding,
tenant/owner checks, routing and L1 admission run in that scope. Previously pooled worker state is
restored in finally; trace scopes must restore their own mutations even on failure.
Do not capture on a foreign worker after dispatch. Mutable atomic-batch/unit-of-work state cannot
be shared across threads.

Export snapshot() gate counters through bounded low-cardinality metrics; never tag tenant, ID or
payload. Its accepted counter measures gate admission, not Redis write acceptance or SQL durability.

## Optional Reactor adapter

Add com.reactor.cachedb:cachedb-reactor at the same version as the other CacheDB artifacts.
It uses reactor-core; Spring is optional. Its Spring factory registration is stateless and creates
no executor. Applications without Spring can instantiate the factory directly.

```java
var factory = new ReactiveEntityRepositoryFactory();
var reactive = factory.from(repository, gate);
var result = reactive.findById(id)
    .contextWrite(context -> context.put(CacheReactorContext.TENANT, authenticatedTenant));
```

Publishers are cold: each subscription creates fresh work using deferContextual and a future
supplier. Optional.empty maps to an empty Mono; finite query/findAll lists become Flux items.
Cancellation propagates to the per-subscription future with the physical-I/O rules above.
No internal scheduler, boundedElastic offload, global Reactor hook or automatic retry is installed.
from(asyncRepository) preserves a native asynchronous implementation. The fallback adapter still
offloads blocking calls and does not itself implement read coalescing or a zero-offload fast path.

CacheReactorContext.TENANT is a trusted String of at most 256 characters; CAUSAL_TOKEN contains
CacheCausalContext.encodeToken(). Invalid types/tokens fail before dispatch. Missing keys mean
EMPTY tenant/causal state; ambient worker/admin privileges are not inherited. A causal token is
not authentication. Trace uses the actual configured TraceContextPropagator, not these two keys.
The subscriber thread's prior state is restored immediately after registration.

Do not block event loops with get/join or wrap a shared hot future. Do not assume cancellation is
rollback, entity completion is SQL durability, or offloading is a native batched Redis operation.
