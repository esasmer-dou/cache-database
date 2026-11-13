package com.reactor.cachedb.redis;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CancellationException;
import redis.clients.jedis.CommandArguments;
import redis.clients.jedis.Connection;
import redis.clients.jedis.Protocol.Command;
import redis.clients.jedis.args.Rawable;
import redis.clients.jedis.csc.CacheConnection;
import redis.clients.jedis.exceptions.JedisAccessControlException;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.util.Pool;
import redis.clients.jedis.util.SafeEncoder;

/** Bounded read-only snapshots on one reserved standalone connection. */
final class RedisStandaloneIndexSnapshot {
    static final int MAX_ROWS = 512;
    static final int MAX_FIELDS = 512;
    static final int MAX_COMMANDS = MAX_ROWS + 4;
    static final long MAX_BYTES = 16L * 1024 * 1024;
    private static final int MAX_NATIVE_COUNT_ROWS = 10_000;
    private static final int MAX_NATIVE_COUNT_COMMANDS = MAX_NATIVE_COUNT_ROWS
            + 4 * ((MAX_NATIVE_COUNT_ROWS + MAX_ROWS - 1) / MAX_ROWS);
    private static final Duration DEFAULT_BORROW_WAIT = Duration.ofSeconds(2);
    private static final int DEFAULT_READ_TIMEOUT_MILLIS = 2000;
    private static final byte[] NULL_SENTINEL = {95, 95, 78, 85, 76, 76, 95, 95};
    private static final byte[] OK = {79, 75};
    private static final byte[] QUEUED = {81, 85, 69, 85, 69, 68};

    private RedisStandaloneIndexSnapshot() {}

    static Optional<RedisClientOperations.IndexAggregateBatch> aggregate(Pool<Connection> pool,
            List<String> entities, List<String> tombstones, List<String> metadata, List<String> owners,
            String tenant, String field, boolean countOnly) {
        if (field != null && field.length() > 4096)
            throw new IllegalArgumentException("Aggregate field exceeds 4096 characters");
        boolean hasField = field != null && !field.isBlank() && !field.equals("*");
        if (!countOnly && !hasField) throw new IllegalArgumentException("Numeric aggregation requires a field");
        Batch batch = Batch.create(entities, tombstones, metadata, owners, tenant,
                hasField ? List.of(field) : List.of());
        try (Lease lease = Lease.open(pool)) {
            try {
                if (!lease.supported()) return Optional.empty();
                if (!hasField) {
                    List<Object> replies = lease.execProof(batch);
                    boolean[] admitted = admission(replies, batch);
                    if (admitted == null) return Optional.empty();
                    long count = 0;
                    for (boolean allowed : admitted) if (allowed) count++;
                    return Optional.of(new RedisClientOperations.IndexAggregateBatch(count, List.of(), List.of()));
                }
                Optional<Rows> snapshot = rows(lease, batch);
                if (snapshot.isEmpty()) return Optional.empty();
                var values = new ArrayList<String>();
                var nulls = new ArrayList<Integer>();
                long count = 0, javaBytes = 0;
                for (int i = 0; i < snapshot.get().positions().size(); i++) {
                    byte[] raw = (byte[]) snapshot.get().values().get(i);
                    boolean isNull = Arrays.equals(raw, NULL_SENTINEL);
                    if (countOnly) {
                        if (isNull) nulls.add(snapshot.get().positions().get(i));
                        else count++;
                    } else {
                        count++;
                        if (isNull) continue;
                        String value = text(raw);
                        javaBytes = Math.addExact(javaBytes, 2L * value.length());
                        if (javaBytes > MAX_BYTES) throw new IllegalStateException("Aggregate byte budget exceeded");
                        values.add(value);
                    }
                }
                return Optional.of(new RedisClientOperations.IndexAggregateBatch(count, List.copyOf(values), List.copyOf(nulls)));
            } catch (NativeBudgetUnavailable | NativePermissionUnavailable unavailable) {
                return Optional.empty();
            }
        }
    }

    static Optional<RedisClientOperations.FacetIndexBatch> facet(Pool<Connection> pool,
            List<String> entities, List<String> tombstones, List<String> metadata, List<String> owners,
            String tenant, String groupField, String valueField) {
        requireField(groupField);
        if (valueField != null) requireField(valueField);
        Batch batch = Batch.create(entities, tombstones, metadata, owners, tenant,
                valueField == null ? List.of(groupField) : List.of(groupField, valueField));
        // A two-column request needs smaller repository batches, not an unbounded native transaction.
        if ((long) batch.size() * batch.fields().size() > MAX_FIELDS) return Optional.empty();
        try (Lease lease = Lease.open(pool)) {
            try {
                if (!lease.supported()) return Optional.empty();
                Optional<Rows> snapshot = rows(lease, batch);
                if (snapshot.isEmpty()) return Optional.empty();
                var result = new ArrayList<RedisClientOperations.FacetIndexRow>(snapshot.get().positions().size());
                long javaBytes = 0;
                int stride = batch.fields().size();
                for (int i = 0; i < snapshot.get().positions().size(); i++) {
                    String group = text(snapshot.get().values().get(i * stride));
                    String value = stride == 1 ? null : text(snapshot.get().values().get(i * stride + 1));
                    javaBytes = Math.addExact(javaBytes, 2L * group.length() + (value == null ? 0 : 2L * value.length()));
                    if (javaBytes > MAX_BYTES) throw new IllegalStateException("Facet byte budget exceeded");
                    result.add(new RedisClientOperations.FacetIndexRow(snapshot.get().positions().get(i), group, value));
                }
                return Optional.of(new RedisClientOperations.FacetIndexBatch(result));
            } catch (NativeBudgetUnavailable | NativePermissionUnavailable unavailable) {
                return Optional.empty();
            }
        }
    }

    private record Batch(List<String> entities, List<String> tombstones, List<String> metadata,
            List<String> owners, byte[] tenant, List<String> fields, List<String> watchKeys) {
        int size() { return entities.size(); }
        static Batch create(List<String> entities, List<String> tombstones, List<String> metadata,
                List<String> owners, String tenant, List<String> fields) {
            Objects.requireNonNull(entities, "entities"); Objects.requireNonNull(tombstones, "tombstones");
            Objects.requireNonNull(metadata, "indexMetadata");
            int size = entities.size();
            if (size < 1 || size > MAX_ROWS || tombstones.size() != size || metadata.size() != size
                    || owners != null && owners.size() != size)
                throw new IllegalArgumentException("Index batch requires 1..512 aligned identities");
            if ((owners == null) != (tenant == null) || tenant != null && (tenant.isBlank() || tenant.length() > 4096))
                throw new IllegalArgumentException("Owner keys require a tenant of 1..4096 characters");
            var keys = new ArrayList<String>(size * (2 + (fields.isEmpty() ? 0 : 1) + (owners == null ? 0 : 1)));
            keys.addAll(entities); keys.addAll(tombstones);
            if (!fields.isEmpty()) keys.addAll(metadata);
            if (owners != null) keys.addAll(owners);
            long bytes = 3L * (8192 + (tenant == null ? 0 : tenant.length()));
            for (String key : keys) {
                if (key == null || key.length() > 16384) throw new IllegalArgumentException("Invalid index batch key");
                bytes = Math.addExact(bytes, 3L * key.length());
                if (bytes > MAX_BYTES) throw new IllegalArgumentException("Index batch key byte budget exceeded");
            }
            return new Batch(List.copyOf(entities), List.copyOf(tombstones), fields.isEmpty() ? List.of() : List.copyOf(metadata),
                    owners == null ? null : List.copyOf(owners),
                    tenant == null ? null : tenant.getBytes(StandardCharsets.UTF_8), List.copyOf(fields), Collections.unmodifiableList(keys));
        }
    }

    private record Rows(List<Integer> positions, List<Object> values) {}

    private static Optional<Rows> rows(Lease lease, Batch batch) {
        Phase proof = proof(batch);
        Phase first = new Phase();
        first.add(new CommandArguments(Command.WATCH).addObjects(batch.watchKeys()));
        first.append(proof);
        lease.dirty = true;
        List<Object> observed = lease.exchange(first);
        boolean watchDenied = commandDenied(observed.get(0), Command.WATCH);
        if (!watchDenied) status(observed.get(0), OK);
        List<Object> proofReplies = observed.subList(1, observed.size());
        if (watchDenied) {
            verifyDeniedProof(proofReplies, batch);
            throw lease.permissionUnavailable();
        }
        boolean[] admitted;
        try { admitted = admission(proofReplies, batch); }
        catch (JedisAccessControlException rejection) {
            if (!commandDenied(rejection, Command.GETRANGE)) throw rejection;
            verifyDeniedProof(proofReplies, batch);
            throw lease.permissionUnavailable();
        }
        if (admitted == null) {
            lease.unwatch();
            return Optional.empty();
        }
        var positions = new ArrayList<Integer>();
        Phase lengths = new Phase();
        Phase gets = new Phase();
        for (int i = 0; i < batch.size(); i++) {
            if (!admitted[i]) continue;
            positions.add(i);
            for (String field : batch.fields()) {
                lengths.add(new CommandArguments(Command.HSTRLEN).add(batch.metadata().get(i)).add(field));
                gets.add(new CommandArguments(Command.HGET).add(batch.metadata().get(i)).add(field));
            }
        }
        if (lengths.size() > MAX_FIELDS) {
            lease.unwatch();
            return Optional.empty();
        }
        // MULTI must be acknowledged before any HGET is sent: ACL rejection must not expose an unbounded read.
        List<Object> sizes = lease.begin(lengths);
        for (Object size : sizes) verifyMetadataPermission(lease, size, Command.HSTRLEN);
        verifyMetadataSizes(sizes);
        // WATCH fences data and expiry, not ACL changes. Certify the complete proof and byte bounds in EXEC too.
        List<Object> committed = lease.execMetadata(proof, lengths, gets);
        if (committed == null) return Optional.empty();
        List<Object> committedProof = committed.subList(0, proof.size());
        for (int i = 0; i < proof.size(); i++)
            verifyMetadataPermission(lease, committedProof.get(i), (Command) proof.commands.get(i).getCommand());
        if (!Arrays.equals(admitted, admission(committedProof, batch)))
            throw new IllegalStateException("Native admission changed without a WATCH abort");
        List<Object> committedSizes = committed.subList(proof.size(), proof.size() + lengths.size());
        for (Object size : committedSizes) verifyMetadataPermission(lease, size, Command.HSTRLEN);
        verifyMetadataSizes(committedSizes);
        for (int i = 0; i < sizes.size(); i++)
            if (number(sizes.get(i)) != number(committedSizes.get(i)))
                throw new IllegalStateException("Native metadata length changed without a WATCH abort");
        List<Object> values = committed.subList(proof.size() + lengths.size(), committed.size());
        if (values.size() != sizes.size()) throw new IllegalStateException("Invalid native metadata reply count");
        for (int i = 0; i < values.size(); i++) {
            verifyMetadataPermission(lease, values.get(i), Command.HGET);
            Object value = checked(values.get(i));
            if (value == null) throw new JedisDataException("Incomplete index metadata; rebuild indexes");
            if (!(value instanceof byte[] content) || content.length != number(sizes.get(i)))
                throw new IllegalStateException("Native metadata length changed without a WATCH abort");
        }
        return Optional.of(new Rows(List.copyOf(positions), values));
    }

    private static Phase proof(Batch batch) {
        Phase phase = new Phase();
        phase.add(new CommandArguments(Command.EXISTS).addObjects(batch.entities()));
        phase.add(new CommandArguments(Command.EXISTS).addObjects(batch.tombstones()));
        if (batch.owners() != null)
            for (String owner : batch.owners())
                // Inclusive end includes one extra byte: matching prefixes cannot authorize a longer owner.
                phase.add(new CommandArguments(Command.GETRANGE).add(owner).add(0).add(batch.tenant().length));
        return phase;
    }

    private static Phase countProof(Batch batch) {
        if (batch.owners() == null) return proof(batch);
        Phase phase = new Phase();
        phase.add(new CommandArguments(Command.EXISTS).addObjects(batch.entities()));
        phase.add(new CommandArguments(Command.EXISTS).addObjects(batch.tombstones()));
        phase.addBoundedOwners(batch.owners(), batch.tenant().length);
        return phase;
    }

    /** Null requires Lua revalidation, never a certified empty result. */
    private static boolean[] admission(List<Object> observed, Batch batch) {
        return admission(observed, batch, null);
    }

    private static boolean[] admission(List<Object> observed, Batch batch, CountFallback fallback) {
        int expected = 2 + (batch.owners() == null ? 0 : batch.size());
        if (observed.size() != expected) throw new IllegalStateException("Invalid native liveness reply count");
        long live = number(observed.get(0)), deleted = number(observed.get(1));
        if (live > batch.size() || deleted > batch.size()) throw new IllegalStateException("Invalid native liveness count");
        if (live != batch.size() || deleted != 0) return null;
        boolean[] admitted = new boolean[batch.size()];
        boolean requiresLua = false;
        for (int i = 0; i < batch.size(); i++) {
            if (batch.owners() == null) admitted[i] = true;
            else {
                Object reply = observed.get(i + 2);
                if (fallback != null && fallback.rowRevalidation && ownerWrongtype(reply)) {
                    fallback.retainOwnerFailure(reply);
                    requiresLua = true;
                    continue;
                }
                Object value = checked(reply);
                if (!(value instanceof byte[] owner) || owner.length > batch.tenant().length + 1)
                    throw new IllegalStateException("Invalid bounded owner reply");
                admitted[i] = Arrays.equals(owner, batch.tenant());
            }
        }
        return requiresLua ? null : admitted;
    }

    private static Object checked(Object value) {
        if (value instanceof JedisDataException error) throw error;
        return value;
    }
    private static long number(Object value) {
        Object checked = checked(value);
        if (!(checked instanceof Long number) || number < 0)
            throw new IllegalStateException("Invalid native integer response");
        return number;
    }
    private static String text(Object value) {
        Object checked = checked(value);
        if (!(checked instanceof byte[] bytes)) throw new IllegalStateException("Invalid native metadata value");
        return new String(bytes, StandardCharsets.UTF_8);
    }
    private static void status(Object value, byte[] expected) {
        Object checked = checked(value);
        if (!(checked instanceof byte[] bytes) || !Arrays.equals(bytes, expected))
            throw new IllegalStateException("Invalid native transaction status");
    }
    private static void requireField(String field) {
        if (field == null || field.isBlank() || field.length() > 4096)
            throw new IllegalArgumentException("Index batch field requires 1..4096 characters");
    }

    private static void verifyMetadataSizes(List<Object> sizes) {
        long bytes = 0;
        for (Object value : sizes) {
            bytes = Math.addExact(bytes, number(value));
            if (bytes > MAX_BYTES) throw new JedisDataException("Index metadata byte budget exceeded");
        }
    }

    static OptionalLong indexCounts(Pool<Connection> pool, int candidates,
            java.util.function.IntFunction<RedisClientOperations.IndexReadKeys> windows, String tenant) {
        if (candidates <= MAX_ROWS || candidates > MAX_NATIVE_COUNT_ROWS)
            return indexCountEvals(pool, candidates, windows, tenant);
        var batches = new ArrayList<Batch>();
        for (int start = 0; start < candidates; start += MAX_ROWS) {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException("Native snapshot interrupted");
            var window = Objects.requireNonNull(windows.apply(start), "count window");
            if (window.entities().size() != Math.min(MAX_ROWS, candidates - start)
                    || (window.owners() == null) != (tenant == null))
                throw new IllegalArgumentException("Invalid count window");
            batches.add(Batch.create(window.entities(), window.tombstones(), window.indexMetadata(),
                    window.owners(), tenant, List.of()));
        }
        java.util.function.IntFunction<RedisClientOperations.IndexReadKeys> frozen = start -> {
            Batch batch = batches.get(start / MAX_ROWS);
            return new RedisClientOperations.IndexReadKeys(batch.entities(), batch.tombstones(),
                    Collections.nCopies(batch.size(), null), batch.owners());
        };
        if (tenant != null) {
            // Keep each native window atomic; a declined phase is discarded before Lua revalidates every row.
            var fallback = new CountFallback();
            var nativeCount = StandardCharsets.UTF_8.equals(SafeEncoder.DEFAULT_CHARSET)
                    ? nativeIndexCounts(pool, batches, null, fallback) : OptionalLong.empty();
            if (nativeCount.isPresent()) return nativeCount;
            var evaluated = indexCountEvalResult(pool, candidates, frozen, tenant);
            return fallback.evaluated(evaluated);
        }
        var nativeCount = nativeIndexCounts(pool, batches);
        return nativeCount.isPresent() ? nativeCount
                : indexCountEvals(pool, candidates, frozen, tenant);
    }

    static OptionalLong indexCounts(Pool<Connection> pool, RedisBinaryCountKeys keys, String tenant) {
        int candidates = keys.size();
        if (candidates <= MAX_ROWS || (candidates - 1) / MAX_ROWS + 1 > MAX_COMMANDS) return OptionalLong.empty();
        keys.requireTenant(tenant);
        var binary = keys.prepare();
        if (candidates > MAX_NATIVE_COUNT_ROWS) {
            var result = indexCountEvalResult(pool, candidates, keys::window, tenant, binary);
            return result.state() == CountEvalState.VALUE ? OptionalLong.of(result.value()) : OptionalLong.empty();
        }
        if (tenant != null) {
            var fallback = new CountFallback();
            var nativeCount = nativeIndexCounts(pool, binaryCountBatches(keys, tenant), binary, fallback);
            if (nativeCount.isPresent()) return nativeCount;
            var evaluated = indexCountEvalResult(pool, candidates, keys::window, tenant, binary);
            return fallback.evaluated(evaluated);
        }
        var nativeCount = nativeIndexCounts(pool, binaryCountBatches(keys, null), binary);
        if (nativeCount.isPresent()) return nativeCount;
        var result = indexCountEvalResult(pool, candidates, keys::window, null, binary);
        return result.state() == CountEvalState.VALUE ? OptionalLong.of(result.value()) : OptionalLong.empty();
    }

    private static List<Batch> binaryCountBatches(RedisBinaryCountKeys keys, String tenant) {
        var batches = new ArrayList<Batch>();
        byte[] encodedTenant = tenant == null ? null : tenant.getBytes(StandardCharsets.UTF_8);
        for (int start = 0; start < keys.size(); start += MAX_ROWS) {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException("Native snapshot interrupted");
            var window = keys.window(start);
            // prepare() has validated every key. Native COUNT only needs the immutable lazy identity lists;
            // custom connections can still materialize their original String commands from those lists.
            batches.add(new Batch(window.entities(), window.tombstones(), List.of(), window.owners(), encodedTenant,
                    List.of(), List.of()));
        }
        return batches;
    }

    private static OptionalLong nativeIndexCounts(Pool<Connection> pool, List<Batch> batches) {
        return nativeIndexCounts(pool, batches, null);
    }

    private static OptionalLong nativeIndexCounts(Pool<Connection> pool,
            List<Batch> batches, RedisBinaryCountKeys.Prepared binary) {
        return nativeIndexCounts(pool, batches, binary, new CountFallback());
    }

    private static OptionalLong nativeIndexCounts(Pool<Connection> pool,
            List<Batch> batches, RedisBinaryCountKeys.Prepared binary, CountFallback fallback) {
        var transactions = new ArrayList<Phase>(batches.size());
        long bytes = 0;
        int commands = 0;
        try {
            for (int i = 0; i < batches.size(); i++) {
                if (Thread.currentThread().isInterrupted()) throw new CancellationException("Native snapshot interrupted");
                Batch batch = batches.get(i);
                Phase transaction;
                if (binary == null) transaction = countTransaction(batch, true);
                else transaction = binary.nativeCommands(i * MAX_ROWS, batch.size());
                // Include every queued status, EXEC array/integer header and bounded owner bulk, not just owner data.
                // Unowned windows omit owner bulks but retain the conservative framing reserve.
                long replyBytes = 64L + 16L * (batch.size() + 3L)
                        + (batch.owners() == null ? 0L : batch.size() * (batch.tenant().length + 33L));
                bytes = Math.addExact(bytes, Math.addExact(transaction.bytes, replyBytes));
                commands = Math.addExact(commands, transaction.size());
                if (bytes > MAX_BYTES || commands > MAX_NATIVE_COUNT_COMMANDS)
                    throw new NativeBudgetUnavailable();
                transactions.add(transaction);
            }
        } catch (NativeBudgetUnavailable unsupported) {
            return OptionalLong.empty();
        }
        try (Lease lease = Lease.open(pool)) {
            if (!lease.supported()) return OptionalLong.empty();
            if (lease.connection.getClass() != Connection.class) {
                for (int i = 0; i < batches.size(); i++)
                    transactions.set(i, countTransaction(batches.get(i), false));
            }
            try {
                var replies = lease.exchangeCounts(transactions);
                boolean unavailable = false;
                boolean priorExecDenied = false;
                long count = 0;
                for (int i = 0; i < batches.size(); i++) {
                    if (Thread.currentThread().isInterrupted()) throw new CancellationException("Native snapshot interrupted");
                    Batch batch = batches.get(i);
                    try {
                        Phase transaction = transactions.get(i);
                        var observed = decodeExec(replies.get(i), transaction, false, batch, true,
                                priorExecDenied, fallback);
                        boolean[] admitted = admission(observed, batch, fallback);
                        if (admitted == null) {
                            unavailable = true;
                            fallback.rowRevalidation = true;
                        }
                        else for (boolean allowed : admitted) if (allowed) count++;
                    } catch (NativePermissionUnavailable denied) {
                        unavailable = true;
                        fallback.rowRevalidation |= denied.countRowRevalidation;
                    }
                    priorExecDenied = execCommandDenied(replies.get(i).getLast());
                }
                lease.dirty = false;
                if (unavailable) {
                    lease.discardAfterVerifiedRejection();
                    return OptionalLong.empty();
                }
                return OptionalLong.of(count);
            } catch (RuntimeException | Error failure) {
                lease.failed = true;
                throw failure;
            }
        }
    }

    private static Phase countTransaction(Batch batch, boolean reusable) {
        Phase transaction = new Phase();
        transaction.add(new CommandArguments(Command.MULTI));
        if (reusable) {
            var entities = new CommandArguments(Command.EXISTS);
            for (String key : batch.entities()) entities.add(ownedUtf8(key));
            transaction.add(entities);
            var tombstones = new CommandArguments(Command.EXISTS);
            for (String key : batch.tombstones()) tombstones.add(ownedUtf8(key));
            transaction.add(tombstones);
        } else {
            transaction.add(new CommandArguments(Command.EXISTS).addObjects(batch.entities()));
            transaction.add(new CommandArguments(Command.EXISTS).addObjects(batch.tombstones()));
        }
        if (batch.owners() != null) {
            if (reusable) transaction.addBoundedOwners(batch.owners(), batch.tenant().length);
            else for (String owner : batch.owners())
                transaction.add(new CommandArguments(Command.GETRANGE).add(owner).add(0).add(batch.tenant().length));
        }
        transaction.add(new CommandArguments(Command.EXEC));
        return transaction;
    }

    enum CountEvalState { VALUE, EVAL_DENIED, UNSUPPORTED }

    record CountEvalResult(CountEvalState state, long value, JedisAccessControlException failure) {}

    static OptionalLong indexCountEvals(Pool<Connection> pool, int candidates,
            java.util.function.IntFunction<RedisClientOperations.IndexReadKeys> windows, String tenant) {
        var result = indexCountEvalResult(pool, candidates, windows, tenant);
        return result.state() == CountEvalState.VALUE ? OptionalLong.of(result.value()) : OptionalLong.empty();
    }

    /** Retain direct EVAL denial separately from capability absence after validating every reply. */
    static CountEvalResult indexCountEvalResult(Pool<Connection> pool, int candidates,
            java.util.function.IntFunction<RedisClientOperations.IndexReadKeys> windows, String tenant) {
        return indexCountEvalResult(pool, candidates, windows, tenant, null);
    }

    private static CountEvalResult indexCountEvalResult(Pool<Connection> pool, int candidates,
            java.util.function.IntFunction<RedisClientOperations.IndexReadKeys> windows, String tenant,
            RedisBinaryCountKeys.Prepared binary) {
        if (candidates <= MAX_ROWS || (candidates - 1) / MAX_ROWS + 1 > MAX_COMMANDS)
            return new CountEvalResult(CountEvalState.UNSUPPORTED, 0, null);
        Phase phase = new Phase();
        try {
            for (int start = 0; start < candidates; start += MAX_ROWS) {
                if (Thread.currentThread().isInterrupted()) throw new CancellationException("Native snapshot interrupted");
                if (binary != null) {
                    phase.add(binary.evalCommand(start, Math.min(MAX_ROWS, candidates - start)));
                    continue;
                }
                var window = Objects.requireNonNull(windows.apply(start), "count window");
                int expected = Math.min(MAX_ROWS, candidates - start);
                if (window.entities().size() != expected || (window.owners() == null) != (tenant == null))
                    throw new IllegalArgumentException("Invalid count window");
                var command = RedisClientOperations.indexAggregateCommand(window.entities(), window.tombstones(),
                        window.indexMetadata(), window.owners(), tenant, null, true);
                phase.addIndexEval(command.script(), command.keys(), command.arguments());
            }
        } catch (NativeBudgetUnavailable unsupported) {
            return new CountEvalResult(CountEvalState.UNSUPPORTED, 0, null);
        }
        long total = 0;
        JedisAccessControlException denied = null;
        try (Lease lease = Lease.open(pool)) {
            if (!lease.supported()) return new CountEvalResult(CountEvalState.UNSUPPORTED, 0, null);
            try {
                if (lease.connection.getClass() != Connection.class) {
                    if (binary == null) phase.independentIndexEvalScripts();
                    else binary.independentScripts(phase.commands);
                }
                List<Object> replies = lease.exchange(phase);
                for (int i = 0; i < replies.size(); i++) {
                    if (Thread.currentThread().isInterrupted()) throw new CancellationException("Native snapshot interrupted");
                    Object reply = replies.get(i);
                    if (commandDenied(reply, Command.EVAL) || (tenant != null && ownerKeyDenied(reply))) {
                        if (denied == null) denied = (JedisAccessControlException) reply;
                        continue;
                    }
                    Object value = redis.clients.jedis.BuilderFactory.AGGRESSIVE_ENCODED_OBJECT.build(checked(reply));
                    int size = Math.min(MAX_ROWS, candidates - i * MAX_ROWS);
                    var batch = RedisClientOperations.parseIndexAggregateResponse(value, size, true).orElseThrow();
                    if (!batch.nullCandidates().isEmpty()) throw new IllegalStateException("Invalid COUNT(*) null candidates");
                    total = Math.addExact(total, batch.count());
                }
                if (denied != null) lease.discardAfterVerifiedRejection();
            } catch (RuntimeException | Error failure) {
                lease.failed = true;
                throw failure;
            }
        }
        return new CountEvalResult(denied != null ? CountEvalState.EVAL_DENIED : CountEvalState.VALUE,
                denied != null ? 0 : total, denied);
    }

    static Optional<List<RedisClientOperations.FacetIndexBatch>> facetIndexEvals(Pool<Connection> pool, int candidates,
            java.util.function.IntFunction<RedisClientOperations.IndexReadKeys> windows,
            String tenant, String groupField, String valueField) {
        return facetIndexPhase(pool, candidates, windows, tenant, groupField, valueField, false,
                (reply, size, budget) -> RedisClientOperations.parseFacetIndexResponse(reply, size, valueField, budget).orElseThrow(), null).batches();
    }

    static Optional<List<RedisClientOperations.FacetIndexCounts>> facetCountEvals(Pool<Connection> pool, int candidates,
            java.util.function.IntFunction<RedisClientOperations.IndexReadKeys> windows, String tenant, String groupField) {
        return facetCountPhase(pool, candidates, windows, tenant, groupField).batches();
    }

    static RedisClientOperations.FacetPhaseResult<RedisClientOperations.FacetIndexCounts> facetCountPhase(
            Pool<Connection> pool, int candidates,
            java.util.function.IntFunction<RedisClientOperations.IndexReadKeys> windows, String tenant, String groupField) {
        return facetIndexPhase(pool, candidates, windows, tenant, groupField, null, true,
                RedisCompactFacetReplyDecoder::parse, null);
    }

    static Optional<List<RedisClientOperations.FacetIndexBatch>> facetIndexEvals(Pool<Connection> pool,
            RedisBinaryFacetKeys keys, String tenant, String groupField, String valueField) {
        return facetIndexPhase(pool, keys.size(), null, tenant, groupField, valueField, false,
                (reply, size, budget) -> RedisClientOperations.parseFacetIndexResponse(reply, size, valueField, budget).orElseThrow(), keys).batches();
    }

    static Optional<List<RedisClientOperations.FacetIndexCounts>> facetCountEvals(Pool<Connection> pool,
            RedisBinaryFacetKeys keys, String tenant, String groupField) {
        return facetCountPhase(pool, keys, tenant, groupField).batches();
    }

    static RedisClientOperations.FacetPhaseResult<RedisClientOperations.FacetIndexCounts> facetCountPhase(
            Pool<Connection> pool, RedisBinaryFacetKeys keys, String tenant, String groupField) {
        return facetIndexPhase(pool, keys.size(), null, tenant, groupField, null, true,
                RedisCompactFacetReplyDecoder::parse, keys);
    }

    @FunctionalInterface
    private interface FacetParser<T> { T parse(Object reply, int size, long budget); }

    private static <T> RedisClientOperations.FacetPhaseResult<T> facetIndexPhase(Pool<Connection> pool, int candidates,
            java.util.function.IntFunction<RedisClientOperations.IndexReadKeys> windows,
            String tenant, String groupField, String valueField, boolean compactCounts, FacetParser<T> parser,
            RedisBinaryFacetKeys binaryKeys) {
        int rows = valueField == null ? MAX_ROWS : MAX_ROWS / 2;
        if (candidates <= rows || (candidates - 1) / rows + 1 > MAX_COMMANDS)
            return RedisClientOperations.FacetPhaseResult.declined(RedisClientOperations.FacetPhaseDecline.UNSUPPORTED);
        int count = (candidates - 1) / rows + 1;
        // Reserve framing/row overhead, then bound raw fields plus worst-case UTF-16 decoding.
        long reserve = 64L * (candidates + count);
        long budget = (MAX_BYTES - reserve) / (3L * count);
        if (budget < 1)
            return RedisClientOperations.FacetPhaseResult.declined(RedisClientOperations.FacetPhaseDecline.UNSUPPORTED);
        Phase phase = new Phase();
        if (binaryKeys != null) binaryKeys.requireTenant(tenant);
        var binary = binaryKeys == null ? null : binaryKeys.prepare(rows, groupField, valueField, budget, compactCounts);
        try {
            for (int start = 0; start < candidates; start += rows) {
                if (Thread.currentThread().isInterrupted()) throw new CancellationException("Native snapshot interrupted");
                if (binary != null) {
                    phase.add(binary.command(start, Math.min(rows, candidates - start)));
                    continue;
                }
                var window = Objects.requireNonNull(windows.apply(start), "facet window");
                if (window.entities().size() != Math.min(rows, candidates - start))
                    throw new IllegalArgumentException("Invalid facet window");
                var command = RedisClientOperations.facetIndexCommand(window.entities(), window.tombstones(),
                        window.indexMetadata(), window.owners(), tenant, groupField, valueField, budget, compactCounts);
                phase.addIndexEval(command.script(), command.keys(), command.arguments());
            }
        } catch (NativeBudgetUnavailable unsupported) {
            return RedisClientOperations.FacetPhaseResult.declined(RedisClientOperations.FacetPhaseDecline.UNSUPPORTED);
        }
        var batches = new ArrayList<T>(count);
        boolean denied = false, exceeded = false;
        try (Lease lease = Lease.open(pool)) {
            if (!lease.supported())
                return RedisClientOperations.FacetPhaseResult.declined(RedisClientOperations.FacetPhaseDecline.UNSUPPORTED);
            try {
                if (lease.connection.getClass() != Connection.class) {
                    if (binary == null) phase.independentIndexEvalScripts();
                    else binary.independentScripts(phase.commands);
                }
                List<Object> replies = lease.exchange(phase);
                for (int i = 0; i < replies.size(); i++) {
                    if (Thread.currentThread().isInterrupted()) throw new CancellationException("Native snapshot interrupted");
                    Object reply = replies.get(i);
                    if (commandDenied(reply, Command.EVAL)) { denied = true; continue; }
                    Object value = checked(reply);
                    if (!compactCounts) value = redis.clients.jedis.BuilderFactory.AGGRESSIVE_ENCODED_OBJECT.build(value);
                    int size = Math.min(rows, candidates - i * rows);
                    if (value instanceof List<?> marker && marker.size() == 3
                            && (compactCounts ? RedisCompactFacetReplyDecoder.isBudgetMarker(marker.get(0))
                                    : "cachedb-facet-phase-budget-v1".equals(marker.get(0)))
                            && marker.get(1) instanceof Long observedSize && observedSize == size
                            && marker.get(2) instanceof Long observedBudget && observedBudget == budget) {
                        exceeded = true;
                        continue;
                    }
                    batches.add(parser.parse(value, size, 2L * budget + (compactCounts ? 16L * size : 0)));
                }
                // Every non-rejected reply is checked even after a quota marker or direct EVAL denial.
                if (denied) lease.discardAfterVerifiedRejection();
            } catch (RuntimeException | Error failure) {
                lease.failed = true;
                throw failure;
            }
        }
        // Closing the lease before either result also leaves payload sentinel resolution pool-safe.
        if (denied) return RedisClientOperations.FacetPhaseResult.declined(RedisClientOperations.FacetPhaseDecline.EVAL_COMMAND_DENIED);
        if (exceeded) return RedisClientOperations.FacetPhaseResult.declined(RedisClientOperations.FacetPhaseDecline.SOFT_BYTE_BUDGET);
        return RedisClientOperations.FacetPhaseResult.available(batches);
    }

    static Object indexEval(redis.clients.jedis.JedisPooled client, String script,
            List<String> keys, List<String> arguments) {
        Phase phase = new Phase();
        try {
            phase.add(evalCommand(script, keys, arguments));
        } catch (NativeBudgetUnavailable unsupported) {
            // The original compact Lua input bounds were already checked by RedisClientOperations.
            return client.eval(script, keys, arguments);
        }
        Object value = null;
        JedisAccessControlException denied = null;
        boolean legacy = false;
        try (Lease lease = Lease.open(client.getPool())) {
            if (!lease.supported()) legacy = true;
            else {
                Object reply = lease.exchange(phase).getFirst();
                if (commandDenied(reply, Command.EVAL)) {
                    lease.discardAfterVerifiedRejection();
                    denied = (JedisAccessControlException) reply;
                } else value = redis.clients.jedis.BuilderFactory.AGGRESSIVE_ENCODED_OBJECT.build(checked(reply));
            }
        }
        if (legacy) return client.eval(script, keys, arguments);
        // Construct the marker only after close: a failed pool return must propagate instead.
        if (denied != null) throw new EvalPermissionUnavailable(denied);
        return value;
    }

    static CommandArguments evalCommand(String script, List<String> keys, List<String> arguments) {
        return evalCommandWithScript(ownedUtf8(script), keys, arguments);
    }

    private static CommandArguments evalCommandWithScript(Rawable script, List<String> keys, List<String> arguments) {
        var command = new CommandArguments(Command.EVAL).add(script).add(keys.size());
        for (String key : keys) command.add(key == null ? (Object) null : ownedUtf8(key));
        for (String argument : arguments) command.add(argument == null ? (Object) null : ownedUtf8(argument));
        return command;
    }

    private static Rawable ownedUtf8(String value) {
        // Keep UTF-8 bytes request-local instead of letting Jedis Raw copy them again.
        byte[] bytes = SafeEncoder.encode(value);
        return () -> bytes;
    }

    static final class EvalPermissionUnavailable extends RuntimeException {
        final JedisAccessControlException failure;
        private EvalPermissionUnavailable(JedisAccessControlException failure) {
            super(failure.getMessage(), failure, false, false); this.failure = failure;
        }
    }

    /** Only direct, command-aligned Redis ACL rejections qualify; key/transaction-change errors do not. */
    private static boolean commandDenied(Object reply, Command command) {
        if (!(reply instanceof JedisAccessControlException error) || error.getCause() != null
                || error.getSuppressed().length != 0) return false;
        return commandDenied(error.getMessage(), command);
    }

    private static boolean commandDenied(String message, Command command) {
        String name = switch (command) {
            case WATCH -> "watch";
            case MULTI -> "multi";
            case EXEC -> "exec";
            case GETRANGE -> "getrange";
            case EVAL -> "eval";
            default -> null;
        };
        if (name == null) return false;
        return commandDenied(message, name);
    }

    private static boolean commandDenied(String message, String name) {
        if (message == null) return false;
        String suffix = " has no permissions to run the '" + name + "' command";
        return message.startsWith("NOPERM User ")
                && message.length() > "NOPERM User ".length() + suffix.length() && message.endsWith(suffix)
                || message.equals("NOPERM this user" + suffix);
    }

    private static boolean metadataPermissionDenied(Object reply, Command command) {
        if (!(reply instanceof JedisAccessControlException error) || error.getCause() != null
                || error.getSuppressed().length != 0) return false;
        return ownerKeyDenied(reply)
                || commandDenied(error.getMessage(), new String(command.getRaw(), StandardCharsets.US_ASCII)
                        .toLowerCase(Locale.ROOT));
    }

    private static void verifyMetadataPermission(Lease lease, Object reply, Command command) {
        if (!(reply instanceof JedisAccessControlException error) || error.getCause() != null
                || error.getSuppressed().length != 0) return;
        String prefix = "NOPERM ACLs rules changed between the moment the transaction was accumulated and the EXEC call. "
                + "This command is no longer allowed for the following reason: ";
        if (metadataPermissionDenied(reply, command)
                || (prefix + "no permission to execute the command or subcommand").equals(error.getMessage())
                || (prefix + "no permission to touch the specified keys").equals(error.getMessage()))
            throw lease.permissionUnavailable();
    }

    /** Redis wraps a rejected EXEC in EXECABORT; only its fully drained, command-aligned tail qualifies. */
    private static boolean execCommandDenied(Object reply) {
        if (commandDenied(reply, Command.EXEC)) return true;
        if (reply == null || reply.getClass() != JedisDataException.class) return false;
        JedisDataException error = (JedisDataException) reply;
        if (error.getCause() != null || error.getSuppressed().length != 0) return false;
        String message = error.getMessage();
        String prefix = "EXECABORT Transaction discarded because of: ";
        return message != null && message.startsWith(prefix)
                && commandDenied(message.substring(prefix.length()), Command.EXEC);
    }

    private static boolean ownerKeyDenied(Object reply) {
        if (!(reply instanceof JedisAccessControlException error) || error.getCause() != null
                || error.getSuppressed().length != 0) return false;
        return "NOPERM No permissions to access a key".equals(error.getMessage())
                || "NOPERM this user has no permissions to access one of the keys used as arguments".equals(error.getMessage());
    }

    private static void verifyDeniedProof(List<Object> replies, Batch batch) {
        verifyDeniedProof(replies, batch, false, null);
    }

    private static boolean ownerWrongtype(Object reply) {
        return reply != null && reply.getClass() == JedisDataException.class
                && ((JedisDataException) reply).getCause() == null
                && ((JedisDataException) reply).getSuppressed().length == 0
                && "WRONGTYPE Operation against a key holding the wrong kind of value"
                        .equals(((JedisDataException) reply).getMessage());
    }

    private static boolean verifyDeniedProof(List<Object> replies, Batch batch, boolean ownerAclFallback,
            CountFallback fallback) {
        int expected = 2 + (batch.owners() == null ? 0 : batch.size());
        if (replies.size() != expected) throw new IllegalStateException("Invalid denied native proof count");
        long live = number(replies.get(0));
        // COUNT probes every tombstone, but Lua only probes those whose entity exists.
        // A direct key denial leaves liveness unknown; it must never certify an empty count.
        long deleted = ownerAclFallback && ownerKeyDenied(replies.get(1)) ? -1 : number(replies.get(1));
        if (live > batch.size() || deleted > batch.size())
            throw new IllegalStateException("Invalid denied native liveness count");
        boolean completeLiveness = live == batch.size() && deleted == 0;
        boolean countRowRevalidation = ownerAclFallback && !completeLiveness;
        if (batch.owners() == null) return countRowRevalidation;
        for (int i = 0; i < batch.size(); i++) {
            Object reply = replies.get(i + 2);
            if (ownerAclFallback && ownerKeyDenied(reply)) {
                countRowRevalidation = true;
                continue;
            }
            if (commandDenied(reply, Command.GETRANGE)) continue;
            // Lua resolves earlier key denials in this window and prior windows before owner type errors.
            if (ownerAclFallback && (countRowRevalidation || fallback != null && fallback.rowRevalidation)
                    && ownerWrongtype(reply)) {
                if (completeLiveness) fallback.retainOwnerFailure(reply);
                continue;
            }
            Object value = checked(reply);
            if (!(value instanceof byte[] owner) || owner.length > batch.tenant().length + 1)
                throw new IllegalStateException("Invalid denied native owner response");
        }
        return countRowRevalidation;
    }

    private static void expectedRejection(Object reply, String message) {
        if (reply == null || reply.getClass() != JedisDataException.class
                || !message.equals(((JedisDataException) reply).getMessage())
                || ((JedisDataException) reply).getCause() != null || ((JedisDataException) reply).getSuppressed().length != 0) {
            checked(reply);
            throw new IllegalStateException("Invalid native transaction rejection");
        }
    }

    private static List<Object> decodeExec(List<Object> outer, Phase transaction, boolean watched,
            Batch batch, boolean ownerAclFallback, boolean priorExecDenied, CountFallback fallback) {
        return decodeExec(outer, transaction.commands, watched, batch, ownerAclFallback, priorExecDenied, fallback);
    }

    private static List<Object> decodeExec(List<Object> outer, List<CommandArguments> commands, boolean watched,
            Batch batch, boolean ownerAclFallback, boolean priorExecDenied, CountFallback fallback) {
        if (outer.size() != commands.size()) throw new IllegalStateException("Invalid native phase reply count");
        Object end = outer.get(outer.size() - 1);
        boolean execDenied = execCommandDenied(end);
        if (!watched && commandDenied(outer.get(0), Command.MULTI) && batch != null) {
            boolean countRowRevalidation = verifyDeniedProof(outer.subList(1, outer.size() - 1), batch,
                    ownerAclFallback, fallback);
            if (!execDenied) expectedRejection(end, "ERR EXEC without MULTI");
            throw new NativePermissionUnavailable(countRowRevalidation);
        }
        if (!watched) {
            if (priorExecDenied && execDenied && outer.get(0) instanceof JedisDataException)
                expectedRejection(outer.get(0), "ERR MULTI calls can not be nested");
            else status(outer.get(0), OK);
        }
        boolean queuedDenial = false;
        boolean countRowRevalidation = false;
        int firstRead = watched ? 0 : 1;
        for (int i = firstRead; i < outer.size() - 1; i++) {
            try { status(outer.get(i), QUEUED); }
            catch (JedisAccessControlException rejection) {
                Command command = (Command) commands.get(i).getCommand();
                // Only COUNT's speculative tombstone EXISTS may defer a liveness-key denial to Lua.
                boolean countKeyFallback = ownerAclFallback && ownerKeyDenied(rejection)
                        && (command == Command.GETRANGE || command == Command.EXISTS && i == firstRead + 1);
                if (!commandDenied(rejection, command) && !countKeyFallback
                        && !(watched && metadataPermissionDenied(rejection, command)))
                    throw rejection;
                queuedDenial = true;
                countRowRevalidation |= countKeyFallback;
            }
        }
        if (queuedDenial) {
            if (!execDenied) expectedRejection(end, "EXECABORT Transaction discarded because of previous errors.");
            throw new NativePermissionUnavailable(countRowRevalidation);
        }
        if (execDenied) throw new NativePermissionUnavailable();
        Object result = checked(end);
        if (result == null) {
            if (!watched) throw new IllegalStateException("Unwatched native transaction aborted");
            return null;
        }
        if (!(result instanceof List<?> values) || values.size() != outer.size() - firstRead - 1)
            throw new IllegalStateException("Invalid native EXEC reply count");
        return new ArrayList<>(values);
    }

    private static final class CountFallback {
        private boolean rowRevalidation;
        private JedisDataException deferredOwnerFailure;
        void retainOwnerFailure(Object reply) {
            if (deferredOwnerFailure == null) deferredOwnerFailure = (JedisDataException) reply;
        }
        OptionalLong evaluated(CountEvalResult result) {
            if (result.state() == CountEvalState.VALUE) return OptionalLong.of(result.value());
            // A denied/unavailable Lua phase cannot erase an already proven live-owner type failure.
            if (deferredOwnerFailure != null) throw deferredOwnerFailure;
            if (result.state() == CountEvalState.EVAL_DENIED) throw result.failure();
            return OptionalLong.empty();
        }
    }

    private static final class NativePermissionUnavailable extends RuntimeException {
        private final boolean countRowRevalidation;
        private NativePermissionUnavailable() { this(false); }
        private NativePermissionUnavailable(boolean countRowRevalidation) {
            super(null, null, false, false);
            this.countRowRevalidation = countRowRevalidation;
        }
    }

    // A native request repeats keys/fields on the wire; Lua can still admit the original compact layout.
    private static final class NativeBudgetUnavailable extends RuntimeException {
        private NativeBudgetUnavailable() { super(null, null, false, false); }
    }

    // The lease is already standalone-routed; send every key but skip Jedis's unused routing registry.
    static final class Phase {
        final List<CommandArguments> commands = new ArrayList<>();
        private long bytes;
        private OwnerArgument ownerArgument;
        private byte[][] ownerKeys;
        private int ownerStart;
        private String indexEvalScript;
        private Rawable indexEvalScriptBytes;
        void addIndexEval(String script, List<String> keys, List<String> arguments) {
            if (indexEvalScriptBytes == null) {
                indexEvalScriptBytes = ownedUtf8(script);
                indexEvalScript = script;
            }
            Rawable encoded = Objects.equals(script, indexEvalScript) ? indexEvalScriptBytes : ownedUtf8(script);
            // Reuse only this phase's script bytes; add still charges every complete wire command.
            add(evalCommandWithScript(encoded, keys, arguments));
        }
        void independentIndexEvalScripts() {
            if (indexEvalScriptBytes == null) return;
            // Custom connections may defer serialization or retain mutable argument buffers.
            for (int i = 0; i < commands.size(); i++) {
                var arguments = commands.get(i).iterator(); arguments.next();
                byte[] script = arguments.next().getRaw().clone();
                var independent = new CommandArguments(Command.EVAL).add((Rawable) () -> script);
                while (arguments.hasNext()) independent.add(arguments.next());
                commands.set(i, independent);
            }
            indexEvalScript = null;
            indexEvalScriptBytes = null;
        }
        void add(CommandArguments command) {
            if (commands.size() == MAX_COMMANDS) throw new NativeBudgetUnavailable();
            long encodedBytes = RedisBinaryFacetKeys.admissionBytes(command);
            if (encodedBytes >= 0) {
                bytes = Math.addExact(bytes, encodedBytes);
                if (bytes > MAX_BYTES) throw new NativeBudgetUnavailable();
            } else {
                for (var argument : command) {
                    bytes = Math.addExact(bytes, argument.getRaw().length + 16L);
                    if (bytes > MAX_BYTES) throw new NativeBudgetUnavailable();
                }
            }
            commands.add(command);
        }
        void addBoundedOwners(List<String> keys, int inclusiveEnd) {
            if (ownerArgument != null) throw new IllegalStateException("Owner template already present");
            ownerStart = commands.size();
            ownerKeys = new byte[keys.size()][];
            ownerArgument = new OwnerArgument();
            CommandArguments command = new CommandArguments(Command.GETRANGE).add(ownerArgument).add(0).add(inclusiveEnd);
            for (int i = 0; i < keys.size(); i++) {
                ownerKeys[i] = keys.get(i).getBytes(StandardCharsets.UTF_8);
                ownerArgument.value = ownerKeys[i];
                add(command);
            }
        }
        void addBoundedOwners(byte[][] keys, int inclusiveEnd) {
            if (ownerArgument != null) throw new IllegalStateException("Owner template already present");
            ownerStart = commands.size();
            ownerKeys = keys;
            ownerArgument = new OwnerArgument();
            CommandArguments command = new CommandArguments(Command.GETRANGE).add(ownerArgument).add(0).add(inclusiveEnd);
            for (byte[] key : keys) {
                ownerArgument.value = key;
                add(command);
            }
        }
        void append(Phase source) {
            if (ownerArgument != null && source.ownerArgument != null)
                throw new IllegalStateException("Multiple owner templates in a phase");
            int offset = commands.size();
            for (int i = 0; i < source.size(); i++) add(source.commandAt(i));
            if (source.ownerArgument != null) {
                ownerArgument = source.ownerArgument;
                ownerKeys = source.ownerKeys;
                ownerStart = offset + source.ownerStart;
            }
        }
        CommandArguments commandAt(int index) {
            if (ownerArgument != null && index >= ownerStart && index - ownerStart < ownerKeys.length)
                ownerArgument.value = ownerKeys[index - ownerStart];
            return commands.get(index);
        }
        int size() { return commands.size(); }
    }

    // Request-local only. Jedis sendCommand serializes every byte before it returns.
    private static final class OwnerArgument implements Rawable {
        private byte[] value;
        @Override public byte[] getRaw() { return value; }
    }

    static final class Lease implements AutoCloseable {
        final Pool<Connection> pool;
        final Connection connection;
        private final int originalTimeout;
        private boolean dirty;
        private boolean transactionAcknowledged;
        private boolean pending;
        private boolean failed;
        private boolean closed;

        private Lease(Pool<Connection> pool, Connection connection) {
            this.pool = pool; this.connection = connection; this.originalTimeout = connection.getSoTimeout();
        }
        static Lease open(Pool<Connection> pool) {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException("Native snapshot interrupted");
            Connection connection;
            try {
                Duration wait = pool.getMaxWaitDuration();
                connection = pool.borrowObject(wait.isNegative() ? DEFAULT_BORROW_WAIT : wait);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new JedisConnectionException("Native snapshot pool wait interrupted", interrupted);
            } catch (Exception failure) {
                throw new JedisConnectionException("Native snapshot connection acquisition failed", failure);
            }
            connection.setHandlingPool(null);
            Lease lease = new Lease(pool, connection);
            try {
                if (connection.isBroken() || !connection.isConnected())
                    throw new JedisConnectionException("Native snapshot requires a healthy reserved connection");
                if (lease.supported() && lease.originalTimeout <= 0)
                    connection.setSoTimeout(DEFAULT_READ_TIMEOUT_MILLIS);
                return lease;
            } catch (RuntimeException | Error failure) {
                lease.failed = true;
                try { lease.close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
        boolean supported() { return !(connection instanceof CacheConnection); }

        List<Object> exchange(Phase phase) {
            if (phase.size() == 0) return List.of();
            if (Thread.currentThread().isInterrupted()) throw new CancellationException("Native snapshot interrupted");
            try {
                pending = true;
                send(phase);
                List<Object> replies = connection.getMany(phase.size());
                if (replies.size() != phase.size()) throw new IllegalStateException("Invalid native phase reply count");
                pending = false;
                return replies;
            } catch (RuntimeException | Error failure) {
                failed = true;
                throw failure;
            }
        }

        private void send(Phase phase) {
            for (int i = 0; i < phase.size(); i++) {
                if (Thread.currentThread().isInterrupted()) throw new CancellationException("Native snapshot interrupted");
                // The stock SDK can reconnect on send; never start a new socket inside WATCH/MULTI.
                if (connection.isBroken() || !connection.isConnected())
                    throw new JedisConnectionException("Reserved native snapshot connection was lost");
                connection.sendCommand(phase.commandAt(i));
            }
        }

        private List<List<Object>> exchangeCounts(List<Phase> transactions) {
            try {
                pending = true;
                dirty = true;
                for (Phase transaction : transactions) send(transaction);
                var replies = new ArrayList<List<Object>>(transactions.size());
                for (Phase transaction : transactions) {
                    if (Thread.currentThread().isInterrupted()) throw new CancellationException("Native snapshot interrupted");
                    replies.add(connection.getMany(transaction.size()));
                }
                pending = false;
                return replies;
            } catch (RuntimeException | Error failure) {
                failed = true;
                throw failure;
            }
        }

        List<Object> begin(Phase lengths) {
            if (transactionAcknowledged) throw new IllegalStateException("Native transaction already started");
            Phase first = new Phase();
            first.append(lengths);
            first.add(new CommandArguments(Command.MULTI));
            dirty = true;
            List<Object> replies = exchange(first);
            Object acknowledgement = replies.get(replies.size() - 1);
            if (commandDenied(acknowledgement, Command.MULTI)) {
                verifyMetadataSizes(replies.subList(0, replies.size() - 1));
                throw permissionUnavailable();
            }
            status(acknowledgement, OK);
            transactionAcknowledged = true;
            return replies.subList(0, replies.size() - 1);
        }

        List<Object> execMetadata(Phase proof, Phase lengths, Phase gets) {
            if (!transactionAcknowledged)
                throw new IllegalStateException("Native metadata reads require acknowledged MULTI");
            Phase last = new Phase();
            last.append(gets);
            last.add(new CommandArguments(Command.EXEC));
            // One transaction, bounded protocol phases: EVAL-denied callers retain the full native field budget.
            List<Phase> phases = lengths.size() == 0 ? List.of(proof, last) : List.of(proof, lengths, last);
            var commands = new ArrayList<CommandArguments>();
            for (Phase phase : phases) commands.addAll(phase.commands);
            List<List<Object>> replies = exchangeCounts(phases);
            var outer = new ArrayList<Object>(commands.size());
            for (int i = 0; i < phases.size(); i++) {
                if (replies.get(i).size() != phases.get(i).size())
                    throw new IllegalStateException("Invalid native phase reply count");
                outer.addAll(replies.get(i));
            }
            try {
                List<Object> values = decodeExec(outer, commands, true, null, false, false, null);
                dirty = false;
                transactionAcknowledged = false;
                return values;
            } catch (NativePermissionUnavailable denied) {
                throw permissionUnavailable();
            }
        }

        List<Object> execProof(Batch batch) {
            // Custom Connection subclasses may defer serialization; do not share their argument objects.
            boolean reusable = batch.owners() != null && connection.getClass() == Connection.class;
            return exec(reusable ? countProof(batch) : proof(batch), false, batch);
        }

        List<Object> exec(Phase reads, boolean watched) { return exec(reads, watched, null); }

        private List<Object> exec(Phase reads, boolean watched, Batch batch) {
            if (watched != transactionAcknowledged)
                throw new IllegalStateException("Native metadata reads require acknowledged MULTI");
            Phase transaction = new Phase();
            if (!watched) transaction.add(new CommandArguments(Command.MULTI));
            transaction.append(reads);
            transaction.add(new CommandArguments(Command.EXEC));
            dirty = true;
            List<Object> outer = exchange(transaction);
            try {
                var values = decodeExec(outer, transaction, watched, batch, false, false, null);
                dirty = false;
                transactionAcknowledged = false;
                return values;
            } catch (NativePermissionUnavailable denied) {
                throw permissionUnavailable();
            }
        }

        private void discardAfterVerifiedRejection() {
            if (pending || failed || connection.isBroken() || !connection.isConnected())
                throw new JedisConnectionException("Native ACL rejection has uncertain connection state");
            failed = true;
        }

        NativePermissionUnavailable permissionUnavailable() {
            discardAfterVerifiedRejection();
            return new NativePermissionUnavailable();
        }

        void unwatch() {
            if (transactionAcknowledged) throw new IllegalStateException("Cannot UNWATCH an active transaction");
            Phase phase = new Phase(); phase.add(new CommandArguments(Command.UNWATCH));
            List<Object> replies = exchange(phase);
            status(replies.get(0), OK);
            dirty = false;
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            boolean broken = failed || pending || dirty || connection.isBroken() || !connection.isConnected();
            if (!broken && connection.getSoTimeout() != originalTimeout) {
                try { connection.setSoTimeout(originalTimeout); }
                catch (RuntimeException failure) {
                    connection.setBroken();
                    try { pool.returnBrokenResource(connection); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
                    throw failure;
                }
            }
            if (broken) { connection.setBroken(); pool.returnBrokenResource(connection); }
            else pool.returnResource(connection);
        }
    }
}
