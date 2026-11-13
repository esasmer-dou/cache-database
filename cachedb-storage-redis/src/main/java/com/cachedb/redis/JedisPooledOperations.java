package com.reactor.cachedb.redis;

import redis.clients.jedis.JedisPooled;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class JedisPooledOperations implements RedisClientOperations, RedisTrackingConnectionProvider {
    private final JedisPooled client;
    private final RedisClientOperations indexLua;
    private static final int MAX_NATIVE_FACET_WATCH_KEYS = 32;
    private static final int MAX_NATIVE_COUNT_OWNER_READS = 32;

    public JedisPooledOperations(JedisPooled client) {
        this.client = Objects.requireNonNull(client, "client");
        this.indexLua = new RedisClientOperations() {
            @Override public JedisPooled commands() { return client; }
            @Override public Object eval(String script, java.util.List<String> keys, java.util.List<String> arguments) {
                try { return RedisStandaloneIndexSnapshot.indexEval(client, script, keys, arguments); }
                catch (RuntimeException failure) {
                    RedisTopologyChangeSource.failure(JedisPooledOperations.this, failure); throw failure;
                }
            }
        };
    }

    public static RedisClientOperations of(JedisPooled client) {
        return client == null ? null : new JedisPooledOperations(client);
    }

    @Override public Optional<GuardedSetMembers> guardedSetMembers(
            String degradedKey, String membershipKey, int maxMembers) {
        return guardedSetMembers(indexLua, degradedKey, membershipKey, maxMembers);
    }

    static Optional<GuardedSetMembers> guardedSetMembers(RedisClientOperations evaluator,
            String degradedKey, String membershipKey, int maxMembers) {
        if (maxMembers < 1) throw new IllegalArgumentException("Invalid candidate membership budget");
        Object raw;
        try {
            raw = evaluator.eval(
                    "if redis.call('EXISTS',KEYS[1])~=0 then return {0,{}} end; "
                            + "local n=redis.call('SCARD',KEYS[2]); if n>tonumber(ARGV[1]) then "
                            + "return redis.error_reply('CacheDB materialized membership budget exceeded') end; "
                            + "return {1,redis.call('SMEMBERS',KEYS[2])}",
                    List.of(degradedKey, membershipKey), List.of(String.valueOf(maxMembers)));
        } catch (RedisStandaloneIndexSnapshot.EvalPermissionUnavailable denied) {
            return Optional.empty();
        }
        if (!(raw instanceof List<?> reply) || reply.size() != 2
                || !(reply.get(0) instanceof Long state) || (state != 0L && state != 1L)
                || !(reply.get(1) instanceof List<?> values)
                || values.size() > maxMembers || (state == 0L && !values.isEmpty()))
            throw new IllegalStateException("Invalid guarded candidate response");
        var members = LinkedHashSet.<String>newLinkedHashSet(values.size());
        for (Object value : values) {
            if (!(value instanceof String member) || !members.add(member))
                throw new IllegalStateException("Invalid guarded candidate member");
        }
        return Optional.of(new GuardedSetMembers(state == 0L, List.copyOf(members)));
    }

    @Override public int physicalPoolCapacity() {
        int maximum = client.getPool().getMaxTotal();
        if (maximum <= 0) throw new IllegalStateException("Point reads require a finite positive Redis pool maxTotal");
        return maximum;
    }

    @Override public java.util.List<RedisTrackingNode> trackingNodes(int maximumNodes) {
        if (maximumNodes < 1 || maximumNodes > 64) throw new IllegalArgumentException("Node limit must be 1..64");
        return java.util.List.of(new RedisTrackingNode("standalone", client.getPool().getFactory()));
    }

    public JedisPooled commands() {
        return client;
    }

    @Override public java.util.Optional<java.util.List<String>> zrangeWithPayloads(String indexKey,
            String tombstonePrefix, String entityPrefix, boolean reverse, int offset, int limit) {
        return fetchOrderedPayloads(indexKey, tombstonePrefix, entityPrefix, reverse, offset, limit);
    }

    @Override public boolean supportsUnownedIndexCounts() { return true; }

    @Override public java.util.OptionalLong aggregateIndexCounts(int candidates,
            java.util.function.IntFunction<IndexReadKeys> windows, String tenant) {
        int tail = candidates % RedisStandaloneIndexSnapshot.MAX_ROWS;
        // Small owned tails currently use the native path and can have different ACL support.
        if (tenant != null && tail > 0 && tail <= MAX_NATIVE_COUNT_OWNER_READS) return java.util.OptionalLong.empty();
        try { return RedisStandaloneIndexSnapshot.indexCounts(client.getPool(), candidates, windows, tenant); }
        catch (RuntimeException failure) {
            RedisTopologyChangeSource.failure(this, failure); throw failure;
        }
    }

    java.util.OptionalLong binaryIndexCounts(RedisBinaryCountKeys keys, String tenant) {
        int tail = keys.size() % RedisStandaloneIndexSnapshot.MAX_ROWS;
        if (tenant != null && tail > 0 && tail <= MAX_NATIVE_COUNT_OWNER_READS) return java.util.OptionalLong.empty();
        try { return RedisStandaloneIndexSnapshot.indexCounts(client.getPool(), keys, tenant); }
        catch (RuntimeException failure) { RedisTopologyChangeSource.failure(this, failure); throw failure; }
    }

    @Override public java.util.Optional<java.util.List<FacetIndexBatch>> facetIndexBatches(int candidates,
            java.util.function.IntFunction<IndexReadKeys> windows, String tenant, String groupField, String valueField) {
        int rows = valueField == null ? RedisStandaloneIndexSnapshot.MAX_ROWS : RedisStandaloneIndexSnapshot.MAX_ROWS / 2;
        int tail = candidates % rows;
        if (tail > 0 && tail <= MAX_NATIVE_FACET_WATCH_KEYS / (tenant == null ? 3 : 4))
            return java.util.Optional.empty();
        try { return RedisStandaloneIndexSnapshot.facetIndexEvals(client.getPool(), candidates, windows, tenant, groupField, valueField); }
        catch (RuntimeException failure) {
            RedisTopologyChangeSource.failure(this, failure); throw failure;
        }
    }

    @Override public Optional<List<FacetIndexCounts>> facetIndexCounts(int candidates,
            java.util.function.IntFunction<IndexReadKeys> windows, String tenant, String groupField) {
        return facetIndexCountsPhase(candidates, windows, tenant, groupField).batches();
    }

    @Override public FacetPhaseResult<FacetIndexCounts> facetIndexCountsPhase(int candidates,
            java.util.function.IntFunction<IndexReadKeys> windows, String tenant, String groupField) {
        int tail = candidates % RedisStandaloneIndexSnapshot.MAX_ROWS;
        if (tail > 0 && tail <= MAX_NATIVE_FACET_WATCH_KEYS / (tenant == null ? 3 : 4))
            return FacetPhaseResult.declined(FacetPhaseDecline.UNSUPPORTED);
        try { return RedisStandaloneIndexSnapshot.facetCountPhase(client.getPool(), candidates, windows, tenant, groupField); }
        catch (RuntimeException failure) { RedisTopologyChangeSource.failure(this, failure); throw failure; }
    }

    Optional<List<FacetIndexCounts>> binaryFacetIndexCounts(RedisBinaryFacetKeys keys, String tenant, String groupField) {
        return binaryFacetIndexCountsPhase(keys, tenant, groupField).batches();
    }

    FacetPhaseResult<FacetIndexCounts> binaryFacetIndexCountsPhase(RedisBinaryFacetKeys keys, String tenant, String groupField) {
        int tail = keys.size() % RedisStandaloneIndexSnapshot.MAX_ROWS;
        if (tail > 0 && tail <= MAX_NATIVE_FACET_WATCH_KEYS / (tenant == null ? 3 : 4))
            return FacetPhaseResult.declined(FacetPhaseDecline.UNSUPPORTED);
        try { return RedisStandaloneIndexSnapshot.facetCountPhase(client.getPool(), keys, tenant, groupField); }
        catch (RuntimeException failure) { RedisTopologyChangeSource.failure(this, failure); throw failure; }
    }

    Optional<List<FacetIndexBatch>> binaryFacetIndexBatches(RedisBinaryFacetKeys keys,
            String tenant, String groupField, String valueField) {
        int rows = valueField == null ? RedisStandaloneIndexSnapshot.MAX_ROWS : RedisStandaloneIndexSnapshot.MAX_ROWS / 2;
        int tail = keys.size() % rows;
        if (tail > 0 && tail <= MAX_NATIVE_FACET_WATCH_KEYS / (tenant == null ? 3 : 4)) return Optional.empty();
        try { return RedisStandaloneIndexSnapshot.facetIndexEvals(client.getPool(), keys, tenant, groupField, valueField); }
        catch (RuntimeException failure) { RedisTopologyChangeSource.failure(this, failure); throw failure; }
    }

    @Override public java.util.Optional<IndexAggregateBatch> aggregateIndexBatch(
            java.util.List<String> entities, java.util.List<String> tombstones, java.util.List<String> indexMetadata,
            java.util.List<String> owners, String tenant, String field, boolean countOnly) {
        boolean hasField = field != null && !field.isBlank() && !field.equals("*");
        boolean preferLua = countOnly && !hasField && owners != null && entities.size() > MAX_NATIVE_COUNT_OWNER_READS
                || hasField && entities.size() > MAX_NATIVE_FACET_WATCH_KEYS / (owners == null ? 3 : 4);
        if (preferLua) {
            // Bound metadata WATCH sets and avoid a native owner command/queued reply per large COUNT row.
            try {
                return indexLua.aggregateIndexBatch(
                        entities, tombstones, indexMetadata, owners, tenant, field, countOnly);
            } catch (RedisStandaloneIndexSnapshot.EvalPermissionUnavailable denied) {
                var nativeResult = RedisStandaloneIndexSnapshot.aggregate(client.getPool(), entities, tombstones,
                        indexMetadata, owners, tenant, field, countOnly);
                if (nativeResult.isPresent()) return nativeResult;
                throw denied.failure;
            }
        }
        var snapshot = RedisStandaloneIndexSnapshot.aggregate(client.getPool(), entities, tombstones,
                indexMetadata, owners, tenant, field, countOnly);
        return snapshot.isPresent() ? snapshot : RedisClientOperations.super.aggregateIndexBatch(
                entities, tombstones, indexMetadata, owners, tenant, field, countOnly);
    }

    @Override public java.util.Optional<FacetIndexBatch> facetIndexBatch(
            java.util.List<String> entities, java.util.List<String> tombstones, java.util.List<String> indexMetadata,
            java.util.List<String> owners, String tenant, String groupField, String valueField) {
        // Redis WATCH scans the accumulated client watch list for every new key.
        int keysPerRow = owners == null ? 3 : 4;
        if (entities.size() > MAX_NATIVE_FACET_WATCH_KEYS / keysPerRow) {
            try {
                return indexLua.facetIndexBatch(
                        entities, tombstones, indexMetadata, owners, tenant, groupField, valueField);
            } catch (RedisStandaloneIndexSnapshot.EvalPermissionUnavailable denied) {
                var nativeResult = RedisStandaloneIndexSnapshot.facet(client.getPool(), entities, tombstones,
                        indexMetadata, owners, tenant, groupField, valueField);
                if (nativeResult.isPresent()) return nativeResult;
                throw denied.failure;
            }
        }
        var snapshot = RedisStandaloneIndexSnapshot.facet(client.getPool(), entities, tombstones,
                indexMetadata, owners, tenant, groupField, valueField);
        return snapshot.isPresent() ? snapshot : RedisClientOperations.super.facetIndexBatch(
                entities, tombstones, indexMetadata, owners, tenant, groupField, valueField);
    }

    public JedisPooled pooledClient() {
        return client;
    }

    @Override public java.util.Optional<java.util.List<OrderedPayload>> orderedPayloadWindow(String indexKey,
            String tombstonePrefix, String entityPrefix, boolean reverse, int offset, int limit) {
        return fetchOrderedPayloadWindow(indexKey, tombstonePrefix, entityPrefix, reverse, offset, limit);
    }
}
