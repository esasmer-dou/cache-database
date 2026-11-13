# Standalone Redis Index Snapshots

## When It Applies

A standalone JedisPooledOperations adapter can execute bounded aggregate and facet index reads
with native Redis commands on one reserved pooled connection. No dependency or public configuration
record change is required. Cluster adapters and custom implementations of RedisClientOperations
retain their existing Lua behavior.

Repository eligibility is unchanged: complete index candidate resolution, indexed fields, the
supported hot-set policy, tenant validation and aggregate limits still apply. Unsupported query
shapes retain the existing payload path. Relevant active causal requirements remain unsupported
for non-version-certified aggregates; use version-verified point/bulk reads instead.

## Request-Local Binary COUNT Keys

The binary COUNT(*) key path is internal to the exact built-in `JedisPooledOperations` adapter
on standalone Redis. It requires the default UTF-8 Jedis charset and an exact `RedisKeyStrategy`
or `ClusterHashTagKeyStrategy` instance; using the latter on standalone does not enable Cluster
execution. Existing complete-index, COUNT_WINDOW policy and query eligibility checks still apply.
COUNT(field), numeric aggregates and facet dispatch are not changed by this COUNT key path.

Candidate identities are frozen per request. Built-in prefixes are encoded once, each identity
is encoded once per window, and final entity, tombstone and optional owner keys are assembled
directly as bytes. The successful owned EVAL path does not first materialize full String key lists.
Custom adapters, strategy subclasses, Cluster adapters and non-UTF-8 Jedis charsets retain the
existing String-based path. Custom Connection subclasses retain independent EVAL script buffers
and the existing String-based native command construction. No cross-request key or ACL cache is added.

Eligible unowned requests with 513..10,000 candidates remain native-first: each 512-row window
uses MULTI, vector EXISTS for entities, vector EXISTS for tombstones, and EXEC. Mixed liveness or
a verified native capability rejection can select the existing bounded Lua phase after draining
and validating every native window. Above that native candidate bound, the existing bounded EVAL
phase remains available under its own limits. Owned multiwindow requests remain EVAL-first;
an owned final window of 1..32 rows still declines the optional phase and uses the existing
sequential routing. Only a fully validated direct EVAL command/key-selector rejection permits
the owned native fallback. A declined fallback preserves the first actual ACL error; an authorized
zero remains a value. Other errors and cancellation are not converted into capability absence.

Binary preparation preserves the 512-row windows, 16,384 UTF-16-unit key limit, 4,096-unit tenant
limit and conservative per-window 16 MiB key budget before any connection is borrowed. Complete
phases still charge actual encoded arguments and framing against their existing 16 MiB budget;
native requests also retain command and bounded-reply reserves. Cancellation checks, canonical
Lua, declared keys, owner equality and all reply validation remain unchanged. String key lists
needed for an owned native fallback are constructed only after a verified EVAL rejection.

This changes Java key preparation, not the Lua algorithm or snapshot guarantees. It establishes
neither a 2x speedup nor facet performance acceptance; measure allocation, end-to-end latency and
Redis CPU under the same bounded workload before making a performance claim.

## Consistency And Ownership

The native COUNT(*) path queues two vector EXISTS reads and, for tenant reads, one bounded GETRANGE per owner
inside one read-only MULTI/EXEC. Single tenant windows above 32 rows prefer bounded Lua instead.
Eligible unowned COUNT(*) queries with 513..10,000 candidates first pipeline native 512-row windows
on one reserved connection, including small final windows; their bounded Lua fallback passes null
tenant and owner keys. COUNT(*) does not read entity payloads or index metadata. Owned small-tail routing is unchanged.
Owned multiwindow queries try the bounded COUNT(*) Lua windows first. They do not send a native
MULTI/EXEC or per-owner read before a successful Lua result. Only a verified direct EVAL command
or key-selector rejection permits a native fallback, after all Lua replies have been drained and
validated. If that fallback cannot complete, the original permission error remains an error;
it is not converted into an empty optimization result.
Each Lua window retains its existing wire budget, ACL handling and independent snapshot boundary;
unsupported phases retry the original sequential windows in full. No query-wide Lua is introduced.

Custom adapters retain their existing tenant-scoped phase calls unless they explicitly opt in
with `supportsUnownedIndexCounts()`. Opting in allows a null tenant with null owner keys;
declining an optional phase retries the original bounded windows without resolving unused metadata.
Measure the complete query in your workload; pipelining alone does not establish a speedup.
Every window still declares its actual entity, tombstone, metadata and owner keys. The native result is accepted only when every candidate entity
exists and no candidate tombstone exists. A mixed window selects the existing Lua implementation
once; it is not reported as zero.

Tenant owner comparison is byte-exact UTF-8. GETRANGE uses start zero and an **inclusive** end
equal to the tenant byte length, reading at most one extra byte. A longer owner that shares the
tenant prefix cannot authorize a row. Missing and foreign owner markers exclude the row.
No owner marker is created or repaired by a read. COUNT(*) Lua uses bounded GETRANGE owner reads;
only a verified GETRANGE ACL rejection permits the legacy STRLEN/GET path for that owner.
Permitted-command errors, including WRONGTYPE, remain errors. In the native fallback, a stock
Jedis Connection uses a request-local encoded
GETRANGE argument template; each key is serialized before the template advances. Command count,
reply order, inclusive owner bounds and atomic MULTI/EXEC semantics are unchanged. Custom
Connection subclasses retain independent argument objects rather than a shared template.

Numeric aggregates, COUNT(field) and facets use three application protocol phases only when
the complete WATCH set fits within 32 keys (at most ten global or
eight tenant rows):

1. WATCH every candidate entity, tombstone, owner and required metadata hash, then read vector
   liveness and bounded owners.
2. Read HSTRLEN only for owner-qualified rows and acknowledge MULTI, then validate the metadata
   byte budget. No HGET is sent unless MULTI succeeded, including under ACL restrictions.
3. Queue the liveness and owner proof again, followed by HSTRLEN, the required HGET commands
   and EXEC. Accept values only when this single transaction confirms the same admission and
   field lengths. A WATCH abort discards their execution and selects the existing Lua path once.

WATCH includes the whole metadata hash, not only requested fields. Concurrent unrelated hash
updates can therefore cause conservative fallback. WATCH does not cover ACL changes, so the
proof and byte bounds are also checked inside EXEC. Even an all-foreign-owner result replays
the proof inside MULTI/EXEC before returning zero. This prevents publishing values or a false
zero using permissions that were never valid together. Bounded pipelined phases retain the
existing one-field and two-field native limits, including when EVAL is denied.

Larger metadata windows directly use the existing atomic,
bounded read-only Lua algorithm. There is no native attempt or WATCH registration first.
Owner lengths are checked before owner values are materialized; admitted metadata is sized
with HSTRLEN before HGET. This path adds no owner attestation, writer epoch or repair authority.
The Lua path uses the configured adapter connection/pool timeouts, not the native attempt's
temporary two-second defaults. Configure finite pool, connect and socket timeouts.

For eligible standalone COUNT(*) facets above 512 candidates, the adapter can return bounded
group/count pairs instead of one pair per row. Ambiguous `__NULL__` groups retain their original
candidate positions for targeted payload verification. Weighted counts consume the same operation-wide
candidate, group and byte budgets; COUNT(field) and numeric facets keep their existing value path.
Custom and Cluster adapters may decline this optional optimization. Facet owner admission uses
length-checked GET and does not introduce an MGET permission requirement.

For built-in standalone adapters and key layouts with the default UTF-8 Jedis charset, facet
keys can be encoded directly without constructing each full key as a Java String. Custom adapters,
custom key layouts, Cluster and other Jedis charsets retain the String-based path. Compact reply
decoding retains Jedis character-decoding behavior and the existing UTF-16 weighted byte budget.
These internal changes do not relax ACL, candidate, group, null-sentinel or wire-size limits.

A successful native batch observes one consistent liveness/owner/metadata state. Multiple
repository batches do **not** form a global snapshot. Candidate enumeration and targeted sentinel
payload revalidation are also separate observations. This does not introduce snapshot, gap-free
pagination or concurrent-insertion guarantees.

## Values And Limits

Raw decimal text is unchanged. SUM, AVG, MIN and MAX continue to use existing Java numeric
semantics and bounded BigDecimal parsing, not Lua floating-point arithmetic. Empty metadata values
remain valid. Missing required fields fail with a rebuild-indexes error. Existing __NULL__
ambiguity handling and targeted live payload revalidation remain unchanged.

- Each call admits 1..512 aligned candidate identities.
- The native metadata path admits at most 512 field reads: one field for up to 512 rows, or two
  fields for up to 256 rows. The repository uses 256-row batches for index-eligible two-field facets;
  one-field and payload-only batches remain at 512. A direct larger two-field adapter call still
  uses the existing bounded Lua path. Operation-wide limits do not reset between batches.
- A native facet WATCH set has at most 32 keys; numeric aggregates retain the existing maximum
  of 2,048 keys in WATCH. A protocol phase has at most 516 commands, including
  control commands. The largest owned COUNT has 514 reads plus MULTI and EXEC.
- Existing 16,384-unit key and 4,096-unit field/tenant bounds remain in force.
- Each native request phase has a conservative 16 MiB encoded-argument/framing budget. If native
  key/field repetition cannot fit, the compact Lua request remains available under its own guards.
- Admitted raw metadata totals at most 16 MiB, checked with HSTRLEN before HGET can execute.
  A changed hash causes EXEC abort before any queued HGET runs.
- Native owner replies and COUNT(*) Lua GETRANGE reads are bounded by tenant UTF-8 byte length
  plus one. Other Lua owner reads retain their length guard; oversized stored owners are not materialized.
- The existing Java two-bytes-per-UTF-16-unit content budget remains 16 MiB. These are bounded
  content/phase budgets, not a promise that total JVM heap or all RESP framing fits in 16 MiB.
- Existing operation-wide candidate, facet group and byte limits are not increased.

At the adapter API, the original aggregate overload and the owner-aware overload keep their
signatures and immutable result types. For example, an aligned low-level count request remains:

~~~java
RedisClientOperations operations = JedisPooledOperations.of(redis);
var batch = operations.aggregateIndexBatch(
        entityKeys, tombstoneKeys, indexMetadataKeys, ownerKeys,
        tenantId, null, true).orElseThrow();
long liveOwnedRows = batch.count();
~~~

Keys and owner markers must come from the application's existing CacheDB namespace/key strategy;
index membership alone is never authorization.

## Failure And Capacity

Each attempt reserves one connection for its whole native protocol. Healthy sockets return to
the same pool only after EXEC or UNWATCH has cleared connection state. Pending replies, dirty
transactions, uncertain WATCH state and transport failures cause public-SDK broken-resource disposal.
Jedis client-side-cache connections retain the Lua path rather than bypassing their cache protocol.

A finite configured pool wait and socket timeout are preserved. An unlimited pool wait is bounded
to two seconds for the native borrow; an unlimited socket read timeout is temporarily bounded to two
seconds and restored only on a healthy return. Configure a finite **connect timeout** too: a pool
borrow timeout alone does not bound connection-factory creation, authentication or initialization.
There is no new whole-operation deadline. Caller cancellation does not replace socket timeouts.

Lua fallback is a single compatibility attempt after native shape unavailability, mixed liveness or
WATCH abort. Budget/schema errors and IO failures are not retried. IO failure does not start a Lua
request on another connection. Bound caller concurrency and pool size; more reserved connections
are not free Redis capacity.

This path exchanges more native protocol phases for less per-row Lua dispatch. It is not an
automatic throughput improvement. Compare result parity, latency, allocation, server CPU,
WATCH aborts, EVAL frequency and pool waits under representative tenant and concurrent-write load.

## Restricted ACL Compatibility

A fully drained native reply can select the existing Lua algorithm once when Redis explicitly
rejects a native-only command: WATCH, MULTI, EXEC or GETRANGE. The command-denial response must match the command actually sent: a direct Jedis access-control
error, or the exact Redis EXECABORT wrapper around an EXEC command denial at the aligned EXEC reply. Key permissions are not command
capability detection. For the native metadata path, an exact, fully drained HSTRLEN/HGET
permission rejection, including Redis's execution-time ACL-change reply, can select one
canonical Lua attempt. It does not authorize a value: Lua must independently succeed under
the current ACL. Unrecognized permission errors remain failures.

Before selecting Lua, the native connection is marked broken and discarded. All companion replies
must be structurally valid; accompanying errors cannot be hidden by a permission rejection.
A failed disposal prevents fallback. The Lua attempt uses the same credentials and key permissions;
its errors propagate without another attempt. No ACL is widened or repaired.

NOAUTH, WRONGPASS, WRONGTYPE, malformed replies, IO faults, unverified WATCH state and UNWATCH
cleanup rejection remain failures. An acknowledged EXEC returning the normal WATCH-abort result
retains the existing concurrency fallback; that is distinct from an uncertain protocol state.

There is no cached permission decision. Repeated calls under an EVAL-compatible restricted ACL
can incur a rejected native attempt, connection disposal and Lua execution each time. Grant the
native commands explicitly if that algorithm's performance is wanted; Lua compatibility alone
does not imply native performance.

Smaller two-field repository windows also apply when an eligible Cluster/custom adapter uses Lua.
This can increase round trips; neither smaller windows nor native execution imply better throughput.
