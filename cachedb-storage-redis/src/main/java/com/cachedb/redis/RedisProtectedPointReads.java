package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.api.ProjectionRepository;
import com.reactor.cachedb.core.model.EntityCodec;
import com.reactor.cachedb.core.model.EntityMetadata;
import com.reactor.cachedb.core.page.VersionedEntity;
import com.reactor.cachedb.core.projection.EntityProjection;
import com.reactor.cachedb.core.protection.ProtectionException;
import com.reactor.cachedb.core.protection.ProtectionKey;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.Generation;
import com.reactor.cachedb.core.query.QuerySpec;
import com.reactor.cachedb.core.repository.HotLookup;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

/** Protected-only point path. It deliberately never invokes legacy hydration or refresh. */
public final class RedisProtectedPointReads<T, ID> {
    @FunctionalInterface public interface Source<T, ID> {
        /** Existing source loader only; no new guard SQL. Preserve original through all work. */
        Optional<VersionedEntity<T>> load(Generation original, ID id);
    }
    @FunctionalInterface public interface Fill<T> {
        /** Pure planning, using the canonical producer's version/index protocol. No I/O. */
        RedisTenantReadFence.Publication plan(Generation original, T entity, long sourceVersion);
    }
    @FunctionalInterface public interface ProjectionFill<P> {
        List<RedisTenantReadFence.Publication> plan(Generation original, List<P> rows);
    }

    private static final int MAX_ROWS = 128;
    private static final long MAX_RESULT_BYTES = 8L * 1024 * 1024;
    private static final String POINT = """
        for i=3,5 do
          local kind=redis.call('TYPE',KEYS[i]).ok
          if kind~='none' and kind~='string' then return redis.error_reply('PROTECTED_READ_SCHEMA') end
        end
        if redis.call('STRLEN',KEYS[3])>1048576 or redis.call('STRLEN',KEYS[4])>19
          or redis.call('STRLEN',KEYS[5])>19 then return redis.error_reply('PROTECTED_READ_LIMIT') end
        return {redis.call('GET',KEYS[3]) or false,redis.call('GET',KEYS[4]) or false,
          redis.call('GET',KEYS[5]) or false,tostring(redis.call('PTTL',KEYS[3]))}
        """;

    private final RedisTenantReadFence fence;
    private final EntityMetadata<T, ID> metadata;
    private final EntityCodec<T> codec;
    private final RedisKeyStrategy keys;
    private final RedisNearCacheCoordinator.Store l1;
    private final Predicate<T> policy;
    private final Source<T, ID> source;
    private final Fill<T> fill;
    private final Set<String> reviewedProjections;

    public RedisProtectedPointReads(RedisTenantReadFence fence, EntityMetadata<T, ID> metadata,
            EntityCodec<T> codec, RedisKeyStrategy keys, RedisNearCacheCoordinator.Store l1,
            Predicate<T> policy, Source<T, ID> source, Fill<T> fill, Set<String> reviewedProjections) {
        this.fence = Objects.requireNonNull(fence);
        this.metadata = Objects.requireNonNull(metadata);
        this.codec = Objects.requireNonNull(codec);
        this.keys = Objects.requireNonNull(keys);
        this.l1 = l1;
        this.policy = Objects.requireNonNull(policy);
        this.source = source;
        this.fill = fill;
        if (!metadata.tenantPurgeEnabled() || metadata.tenantFieldName() == null
                || reviewedProjections == null || reviewedProjections.size() > 128)
            throw rejected(ProtectionException.Code.INCOMPLETE_INVENTORY);
        com.reactor.cachedb.core.crypto.EncryptedStorageChecks.requireBound(metadata, codec);
        this.reviewedProjections = Set.copyOf(reviewedProjections);
    }

    public RedisTenantReadFence fence() { return fence; }

    private ProtectionKey key(String tenant) { return ProtectionKey.of(metadata.redisNamespace(), tenant); }

    public Optional<T> findById(String tenant, ID id, long minimumVersion) {
        try (var ticket = fence.begin(key(tenant))) {
            Budget budget = new Budget();
            return ticket.complete(resolve(ticket, tenant, id, minimumVersion, true, budget).optional());
        }
    }

    public HotLookup<T> findHotById(String tenant, ID id, long minimumVersion) {
        try (var ticket = fence.begin(key(tenant))) {
            return ticket.complete(resolve(ticket, tenant, id, minimumVersion, false, new Budget()));
        }
    }

    public List<T> findAll(String tenant, Collection<ID> input, ToLongFunction<ID> minimumVersion) {
        List<ID> ids = boundedIds(input);
        try (var ticket = fence.begin(key(tenant))) {
            var rows = new ArrayList<T>(ids.size());
            Budget budget = new Budget();
            for (ID id : ids) {
                // Preserve findAll's hot-only semantics and duplicate order.
                HotLookup<T> row = resolve(ticket, tenant, id, minimumVersion.applyAsLong(id), false, budget);
                if (row.isHit()) rows.add(row.value());
            }
            return ticket.complete(List.copyOf(rows));
        }
    }

    public Optional<VersionedEntity<T>> findVersionedById(String tenant, ID id, long minimumVersion) {
        try (var ticket = fence.begin(key(tenant))) {
            long[] version = new long[1];
            var row = resolve(ticket, tenant, id, minimumVersion, false, new Budget(), version);
            return ticket.complete(row.optional().map(entity -> new VersionedEntity<>(entity, version[0])));
        }
    }

    private HotLookup<T> resolve(RedisTenantReadFence.Ticket ticket, String tenant, ID id,
            long minimumVersion, boolean allowSource, Budget budget) {
        return resolve(ticket, tenant, id, minimumVersion, allowSource, budget, null);
    }

    private HotLookup<T> resolve(RedisTenantReadFence.Ticket ticket, String tenant, ID id,
            long minimumVersion, boolean allowSource, Budget budget, long[] resultVersion) {
        if (id == null || minimumVersion < 0) throw rejected(ProtectionException.Code.INVALID_REQUEST);
        String identity = String.valueOf(id);
        if (identity.length() > 1024) throw rejected(ProtectionException.Code.INVALID_REQUEST);
        var localStamp = l1 == null ? null : l1.capture(identity);
        long startedAt = System.nanoTime();
        if (l1 != null) {
            var cached = fence.lookup(ticket, l1, identity, minimumVersion);
            if (cached != null) {
                budget.charge(cached.payload());
                T row = ticket.compute(() -> codec.fromRedisValue(cached.payload()));
                ticket.compute(() -> { verifyTenant(tenant, row); return null; });
                if (!Objects.equals(id, ticket.compute(() -> metadata.idAccessor().apply(row))))
                    throw rejected(ProtectionException.Code.GENERATION_MISMATCH);
                if (resultVersion != null) resultVersion[0] = cached.version();
                return ticket.compute(() -> policy.test(row)) ? HotLookup.hit(row) : HotLookup.outsidePolicy();
            }
        }
        String owner = keys.versionKey(metadata.redisNamespace(), id) + ":tenant";
        var plan = new RedisTenantReadFence.Publication(ticket.generation(), owner, tenant, POINT,
                List.of(keys.entityKey(metadata.redisNamespace(), id), keys.tombstoneKey(metadata.redisNamespace(), id),
                        keys.versionKey(metadata.redisNamespace(), id)), List.of());
        Object reply = ticket.publish(plan);
        if (!(reply instanceof List<?> values) || values.size() != 4)
            throw rejected(ProtectionException.Code.INCOMPLETE_INVENTORY);
        String payload = text(values.get(0)), tombstone = text(values.get(1)), version = text(values.get(2));
        if ("0".equals(version)) {
            if (payload != null || tombstone != null) throw rejected(ProtectionException.Code.MIGRATION_REQUIRED);
            if (minimumVersion > 0) throw rejected(ProtectionException.Code.GENERATION_MISMATCH);
            // Atomic fresh admission installs owner/zero-version markers, not a source-read miss.
            return HotLookup.notCached();
        }
        long observed = version == null ? 0 : positive(version);
        if (observed < minimumVersion) throw rejected(ProtectionException.Code.GENERATION_MISMATCH);
        if (tombstone != null) return HotLookup.tombstoned();
        if (payload == null) {
            if (minimumVersion > 0) throw rejected(ProtectionException.Code.GENERATION_MISMATCH);
            if (!allowSource || source == null) return HotLookup.notCached();
            Optional<VersionedEntity<T>> loaded = ticket.read(() -> source.load(ticket.generation(), id));
            if (loaded == null) throw rejected(ProtectionException.Code.INCOMPLETE_INVENTORY);
            if (loaded.isEmpty()) return HotLookup.notCached();
            VersionedEntity<T> sourced = loaded.get();
            ticket.compute(() -> { verifyTenant(tenant, sourced.entity()); return null; });
            if (!Objects.equals(id, ticket.compute(() -> metadata.idAccessor().apply(sourced.entity()))))
                throw rejected(ProtectionException.Code.GENERATION_MISMATCH);
            if (sourced.version() <= 0 || sourced.version() < minimumVersion)
                throw rejected(ProtectionException.Code.GENERATION_MISMATCH);
            String encoded = ticket.compute(() -> codec.toRedisValue(sourced.entity()));
            budget.charge(encoded);
            if (fill != null) {
                var mutation = ticket.compute(() -> fill.plan(ticket.generation(), sourced.entity(), sourced.version()));
                if (mutation == null) throw rejected(ProtectionException.Code.UNWIRED_PROTOCOL);
                ticket.publish(mutation);
            }
            // A source result is not cached in L1 until its canonical publication is certified.
            if (resultVersion != null) resultVersion[0] = sourced.version();
            return HotLookup.hit(sourced.entity());
        }
        if (observed <= 0) throw rejected(ProtectionException.Code.MIGRATION_REQUIRED);
        budget.charge(payload);
        T row = ticket.compute(() -> codec.fromRedisValue(payload));
        ticket.compute(() -> { verifyTenant(tenant, row); return null; });
        if (!Objects.equals(id, ticket.compute(() -> metadata.idAccessor().apply(row))))
            throw rejected(ProtectionException.Code.GENERATION_MISMATCH);
        if (!ticket.compute(() -> policy.test(row))) return HotLookup.outsidePolicy();
        long ttl = signed(text(values.get(3)));
        if (ttl > 0) ttl = Math.max(0, ttl - (System.nanoTime() - startedAt + 999999L) / 1000000L);
        if (l1 != null) fence.cache(ticket, l1, identity, payload, ttl, observed, localStamp);
        if (resultVersion != null) resultVersion[0] = observed;
        return HotLookup.hit(row);
    }

    /**
     * A reviewed identity-preserving derived view. It does not consult an unowned persisted
     * projection, shared query cache, Spring Cache, or invoke the old asynchronous refresh.
     */
    public <P> ProjectionRepository<P, ID> projected(String tenant, EntityProjection<T, P, ID> projection) {
        requireProjection(projection);
        return new ProjectionRepository<>() {
            public Optional<P> findById(ID id) {
                projectionReadAllowed();
                try (var ticket = fence.begin(key(tenant))) {
                    var row = resolve(ticket, tenant, id, 0, true, new Budget()).optional();
                    var result = row.map(entity -> project(ticket, projection, entity));
                    return ticket.complete(result);
                }
            }
            public List<P> findAll(Collection<ID> input) {
                projectionReadAllowed();
                List<ID> ids = boundedIds(input);
                try (var ticket = fence.begin(key(tenant))) {
                    var result = new ArrayList<P>(ids.size());
                    Budget budget = new Budget();
                    for (ID id : ids) {
                        var row = resolve(ticket, tenant, id, 0, false, budget).optional();
                        row.ifPresent(entity -> result.add(project(ticket, projection, entity)));
                    }
                    return ticket.complete(List.copyOf(result));
                }
            }
            public List<P> query(QuerySpec query) {
                projectionReadAllowed();
                throw rejected(ProtectionException.Code.INCOMPLETE_INVENTORY);
            }
        };
    }

    /** Reviewed refresh uses one original ticket for base reads, projection, planning and publication. */
    public <P> int refreshProjection(String tenant, EntityProjection<T, P, ID> projection,
            Collection<ID> input, ProjectionFill<P> planner) {
        requireProjection(projection);
        Objects.requireNonNull(planner);
        List<ID> ids = boundedIds(input);
        try (var ticket = fence.begin(key(tenant))) {
            var rows = new ArrayList<P>();
            Budget budget = new Budget();
            for (ID id : ids) {
                var row = resolve(ticket, tenant, id, 0, true, budget).optional();
                row.ifPresent(entity -> rows.add(project(ticket, projection, entity)));
            }
            var plans = ticket.compute(() -> planner.plan(ticket.generation(), List.copyOf(rows)));
            if (plans == null || plans.size() > MAX_ROWS)
                throw rejected(ProtectionException.Code.INCOMPLETE_INVENTORY);
            for (var plan : plans) ticket.publish(plan);
            return ticket.complete(rows.size());
        }
    }

    private <P> P project(RedisTenantReadFence.Ticket ticket, EntityProjection<T, P, ID> projection, T row) {
        P result = ticket.compute(() -> projection.projector().apply(row));
        if (result == null || !Objects.equals(ticket.compute(() -> metadata.idAccessor().apply(row)), ticket.compute(() -> projection.idAccessor().apply(result))))
            throw rejected(ProtectionException.Code.INCOMPLETE_INVENTORY);
        return result;
    }

    private static void projectionReadAllowed() {
        if (com.reactor.cachedb.core.causal.CacheCausalContext.hasRequirements())
            throw new com.reactor.cachedb.core.exception.CacheCausalReadUnsupportedException();
    }

    private void requireProjection(EntityProjection<T, ?, ID> projection) {
        if (projection == null || !reviewedProjections.contains(projection.name()))
            throw rejected(ProtectionException.Code.INCOMPLETE_INVENTORY);
    }

    private void verifyTenant(String tenant, T row) {
        if (row == null || !tenant.equals(metadata.extractTenantId(row)))
            throw rejected(ProtectionException.Code.GENERATION_MISMATCH);
    }

    private static <ID> List<ID> boundedIds(Collection<ID> ids) {
        if (ids == null || ids.size() > MAX_ROWS) throw rejected(ProtectionException.Code.INVALID_REQUEST);
        var bounded = new ArrayList<ID>(ids.size());
        for (ID id : ids) {
            if (id == null || bounded.size() == MAX_ROWS) throw rejected(ProtectionException.Code.INVALID_REQUEST);
            bounded.add(id);
        }
        return List.copyOf(bounded);
    }

    private static String text(Object value) {
        if (value == null) return null;
        if (value instanceof String text) return text;
        throw rejected(ProtectionException.Code.INCOMPLETE_INVENTORY);
    }

    private static long signed(String value) {
        try { return Long.parseLong(value); }
        catch (RuntimeException invalid) { throw rejected(ProtectionException.Code.INCOMPLETE_INVENTORY); }
    }

    private static long positive(String value) {
        if (value.isEmpty() || value.charAt(0) < '1' || value.charAt(0) > '9')
            throw rejected(ProtectionException.Code.MIGRATION_REQUIRED);
        for (int i = 1; i < value.length(); i++)
            if (value.charAt(i) < '0' || value.charAt(i) > '9')
                throw rejected(ProtectionException.Code.MIGRATION_REQUIRED);
        try { return Long.parseLong(value); }
        catch (NumberFormatException invalid) { throw rejected(ProtectionException.Code.MIGRATION_REQUIRED); }
    }

    private static final class Budget {
        long used;
        void charge(String payload) {
            if (payload == null) throw rejected(ProtectionException.Code.INCOMPLETE_INVENTORY);
            long bytes = com.reactor.cachedb.core.codec.Utf8Length.of(payload);
            if (bytes > 1048576) throw rejected(ProtectionException.Code.INVALID_REQUEST);
            used += bytes;
            if (used > MAX_RESULT_BYTES) throw rejected(ProtectionException.Code.INVALID_REQUEST);
        }
    }

    private static ProtectionException rejected(ProtectionException.Code code) {
        return RedisTenantReadFence.failure(code);
    }
}
