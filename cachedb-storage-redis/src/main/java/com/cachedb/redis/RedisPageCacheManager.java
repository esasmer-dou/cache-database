package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.cache.CacheAdmissionSource;
import com.reactor.cachedb.core.cache.PageWindow;
import com.reactor.cachedb.core.config.ReadShapeGuardrailConfig;
import com.reactor.cachedb.core.guardrail.ReadShapeGuardrails;
import com.reactor.cachedb.core.model.EntityCodec;
import com.reactor.cachedb.core.model.EntityMetadata;
import com.reactor.cachedb.core.queue.StoragePerformanceCollector;
import com.reactor.cachedb.core.route.RouteCacheContext;
import com.reactor.cachedb.core.route.RouteCacheContract;
import com.reactor.cachedb.core.route.TenantCacheQuota;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.params.SetParams;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.nio.charset.StandardCharsets;

public final class RedisPageCacheManager<T, ID> {

    private static final long TENANT_ADMISSION_LOCK_MILLIS = 30_000L;
    private static final String RELEASE_LOCK_SCRIPT = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """;

    private final JedisPooled jedis;
    private final EntityMetadata<T, ID> metadata;
    private final EntityCodec<T> codec;
    private final CachePolicy cachePolicy;
    private final RedisKeyStrategy keyStrategy;
    private final RedisHotSetManager hotSetManager;
    private final RedisQueryIndexManager<T, ID> queryIndexManager;
    private final RedisProducerGuard producerGuard;
    private final ReadShapeGuardrailConfig readShapeGuardrailConfig;
    private final StoragePerformanceCollector performanceCollector;
    private final ThreadLocal<Set<String>> heldTenantLocks = ThreadLocal.withInitial(HashSet::new);

    public RedisPageCacheManager(
            JedisPooled jedis,
            EntityMetadata<T, ID> metadata,
            EntityCodec<T> codec,
            CachePolicy cachePolicy,
            RedisKeyStrategy keyStrategy,
            RedisHotSetManager hotSetManager,
            RedisQueryIndexManager<T, ID> queryIndexManager,
            RedisProducerGuard producerGuard,
            ReadShapeGuardrailConfig readShapeGuardrailConfig
    ) {
        this(
                jedis,
                metadata,
                codec,
                cachePolicy,
                keyStrategy,
                hotSetManager,
                queryIndexManager,
                producerGuard,
                readShapeGuardrailConfig,
                null
        );
    }

    public RedisPageCacheManager(
            JedisPooled jedis,
            EntityMetadata<T, ID> metadata,
            EntityCodec<T> codec,
            CachePolicy cachePolicy,
            RedisKeyStrategy keyStrategy,
            RedisHotSetManager hotSetManager,
            RedisQueryIndexManager<T, ID> queryIndexManager,
            RedisProducerGuard producerGuard,
            ReadShapeGuardrailConfig readShapeGuardrailConfig,
            StoragePerformanceCollector performanceCollector
    ) {
        this.jedis = jedis;
        this.metadata = metadata;
        this.codec = codec;
        this.cachePolicy = cachePolicy;
        this.keyStrategy = keyStrategy;
        this.hotSetManager = hotSetManager;
        this.queryIndexManager = queryIndexManager;
        this.producerGuard = producerGuard;
        this.readShapeGuardrailConfig = readShapeGuardrailConfig;
        this.performanceCollector = performanceCollector;
    }

    public void recordEntityAccess(ID id) {
        recordEntityAccess(id, null, 0L);
    }

    public void recordEntityAccess(ID id, Map<String, Object> columns) {
        recordEntityAccess(id, columns, 0L);
    }

    public void recordEntityAccess(ID id, Map<String, Object> columns, long payloadBytesFallback) {
        if (producerGuard != null && producerGuard.shouldShedHotSetTracking(metadata.redisNamespace())) {
            return;
        }
        List<String> evictedIds = hotSetManager.recordAccess(metadata.redisNamespace(), id, effectiveCachePolicy());
        recordEviction(evictedIds.size());
        for (String evictedId : evictedIds) {
            removeEntity((ID) evictedId);
        }
        if (columns != null && !columns.isEmpty()) {
            recordTenantAccess(id, columns, measuredPayloadBytes(id, payloadBytesFallback));
        }
    }

    public void cacheEntity(T entity, String encoded) {
        cacheEntity(entity, encoded, CacheAdmissionSource.WRITE);
    }

    public void cacheEntity(T entity, String encoded, CacheAdmissionSource source) {
        CachePolicy effectiveCachePolicy = effectiveCachePolicy();
        ID id = metadata.idAccessor().apply(entity);
        Map<String, Object> columns = codec.toColumns(entity);
        long estimatedPayloadBytes = estimatePayloadBytes(encoded);
        if (!shouldCacheEntity(columns, source, effectiveCachePolicy)
                || !allowTenantQuota(id, columns, estimatedPayloadBytes)) {
            if (effectiveCachePolicy.hotPolicy().evictWhenRejected()) {
                removeEntity(id);
            }
            return;
        }
        String entityKey = keyStrategy.entityKey(metadata.redisNamespace(), id);
        if (effectiveCachePolicy.entityTtlSeconds() > 0) {
            jedis.setex(entityKey, effectiveCachePolicy.entityTtlSeconds(), encoded);
        } else {
            jedis.set(entityKey, encoded);
        }
        queryIndexManager.reindex(entity);
        recordEntityAccess(id, columns, estimatedPayloadBytes);
    }

    public void removeEntity(ID id) {
        jedis.del(keyStrategy.entityKey(metadata.redisNamespace(), id));
        hotSetManager.remove(metadata.redisNamespace(), id);
        removeTenantMembership(id);
        queryIndexManager.removeById(id);
    }

    public Optional<List<T>> getCachedPage(PageWindow pageWindow) {
        List<String> ids = jedis.lrange(keyStrategy.pageKey(metadata.redisNamespace(), pageWindow.pageNumber()), 0, -1);
        if (ids == null || ids.isEmpty()) {
            return Optional.empty();
        }

        List<String> keys = ids.stream()
                .map(id -> keyStrategy.entityKey(metadata.redisNamespace(), id))
                .toList();
        List<String> tombstoneKeys = ids.stream()
                .map(id -> keyStrategy.tombstoneKey(metadata.redisNamespace(), id))
                .toList();
        List<String> encodedValues = jedis.mget(keys.toArray(String[]::new));
        List<String> tombstones = jedis.mget(tombstoneKeys.toArray(String[]::new));
        List<T> entities = new ArrayList<>(encodedValues.size());
        boolean complete = true;
        for (int index = 0; index < encodedValues.size(); index++) {
            String encoded = encodedValues.get(index);
            if (encoded != null && tombstones.get(index) == null) {
                T entity = codec.fromRedisValue(encoded);
                ID id = (ID) ids.get(index);
                if (shouldServeCachedEntity(entity)) {
                    entities.add(entity);
                    recordEntityAccess(id);
                } else if (effectiveCachePolicy().hotPolicy().evictWhenRejected()) {
                    removeEntity(id);
                    complete = false;
                }
            } else {
                queryIndexManager.removeById(ids.get(index));
                complete = false;
            }
        }
        if (!complete || entities.size() != ids.size()) {
            jedis.del(keyStrategy.pageKey(metadata.redisNamespace(), pageWindow.pageNumber()));
            return Optional.empty();
        }
        return Optional.of(entities);
    }

    public void cachePage(PageWindow pageWindow, List<T> entities) {
        if (producerGuard != null && producerGuard.shouldShedPageCacheWrites(metadata.redisNamespace())) {
            return;
        }
        CachePolicy effectiveCachePolicy = effectiveCachePolicy();
        ReadShapeGuardrails.validateLoadedPage(metadata.entityName(), entities.size(), effectiveCachePolicy, readShapeGuardrailConfig);
        if (!ReadShapeGuardrails.shouldCacheLoadedPage(entities.size(), effectiveCachePolicy, readShapeGuardrailConfig)) {
            return;
        }
        for (T entity : entities) {
            ID id = metadata.idAccessor().apply(entity);
            Map<String, Object> columns = codec.toColumns(entity);
            String encoded = codec.toRedisValue(entity);
            if (!shouldCacheEntity(columns, CacheAdmissionSource.READ, effectiveCachePolicy)
                    || !allowTenantQuota(id, columns, estimatePayloadBytes(encoded))) {
                if (effectiveCachePolicy.hotPolicy().evictWhenRejected()) {
                    removeEntity(id);
                }
                return;
            }
        }
        String pageKey = keyStrategy.pageKey(metadata.redisNamespace(), pageWindow.pageNumber());
        jedis.del(pageKey);
        if (entities.isEmpty()) {
            return;
        }

        List<String> ids = new ArrayList<>(entities.size());
        for (T entity : entities) {
            ID id = metadata.idAccessor().apply(entity);
            ids.add(String.valueOf(id));
            cacheEntity(entity, codec.toRedisValue(entity), CacheAdmissionSource.READ);
        }

        jedis.rpush(pageKey, ids.toArray(String[]::new));
        if (effectiveCachePolicy.pageTtlSeconds() > 0) {
            jedis.expire(pageKey, effectiveCachePolicy.pageTtlSeconds());
        }
    }

    public void cachePageMembership(PageWindow pageWindow, List<T> entities) {
        if (producerGuard != null && producerGuard.shouldShedPageCacheWrites(metadata.redisNamespace())) {
            return;
        }
        String pageKey = keyStrategy.pageKey(metadata.redisNamespace(), pageWindow.pageNumber());
        jedis.del(pageKey);
        if (entities == null || entities.isEmpty()) {
            return;
        }
        String[] ids = entities.stream()
                .map(metadata.idAccessor())
                .map(String::valueOf)
                .toArray(String[]::new);
        jedis.rpush(pageKey, ids);
        CachePolicy effectiveCachePolicy = effectiveCachePolicy();
        if (effectiveCachePolicy.pageTtlSeconds() > 0) {
            jedis.expire(pageKey, effectiveCachePolicy.pageTtlSeconds());
        }
    }

    public List<String> hotEntityIds() {
        CachePolicy effectiveCachePolicy = effectiveCachePolicy();
        return jedis.zrevrange(keyStrategy.hotSetKey(metadata.redisNamespace()), 0, Math.max(0, effectiveCachePolicy.hotEntityLimit() - 1));
    }

    private CachePolicy effectiveCachePolicy() {
        return producerGuard == null ? cachePolicy : producerGuard.effectiveCachePolicy(cachePolicy);
    }

    public boolean shouldServeCachedEntity(T entity) {
        return shouldCacheEntity(codec.toColumns(entity), CacheAdmissionSource.SERVE, effectiveCachePolicy());
    }

    boolean allowTenantQuota(ID id, Map<String, Object> columns) {
        return allowTenantQuota(id, columns, 0L);
    }

    boolean allowTenantQuota(ID id, Map<String, Object> columns, long estimatedPayloadBytes) {
        RouteCacheContract contract = RouteCacheContext.currentContract();
        if (contract == null || !contract.tenantQuota().bounded()) {
            return true;
        }
        TenantCacheQuota quota = contract.tenantQuota();
        Object tenantValue = columnValue(columns, quota.tenantColumn());
        if (tenantValue == null) {
            recordTenantAdmission(contract, "missing-tenant", false);
            return false;
        }
        String tenantSetKey = keyStrategy.tenantHotSetKey(metadata.redisNamespace(), quota.tenantColumn(), tenantValue);
        String payloadBytesKey = keyStrategy.tenantHotPayloadBytesKey(metadata.redisNamespace(), quota.tenantColumn(), tenantValue);
        try {
            return withTenantAdmissionLock(tenantSetKey, () -> allowTenantQuotaLocked(
                    id,
                    contract,
                    quota,
                    tenantValue,
                    tenantSetKey,
                    payloadBytesKey,
                    estimatedPayloadBytes
            ));
        } catch (TenantAdmissionBusyException exception) {
            recordTenantAdmission(contract, String.valueOf(tenantValue), false);
            return false;
        }
    }

    private boolean allowTenantQuotaLocked(
            ID id,
            RouteCacheContract contract,
            TenantCacheQuota quota,
            Object tenantValue,
            String tenantSetKey,
            String payloadBytesKey,
            long estimatedPayloadBytes
    ) {
        String idValue = String.valueOf(id);
        TenantOwnerRecord previousOwner = readTenantOwner(id);
        long replacedPayloadBytes = tenantSetKey.equals(previousOwner.tenantSetKey()) ? previousOwner.payloadBytes() : 0L;
        boolean alreadyMember = jedis.zscore(tenantSetKey, idValue) != null;
        if (!alreadyMember && quota.maxHotRows() > 0L) {
            long tenantSize = jedis.zcard(tenantSetKey);
            if (tenantSize >= quota.maxHotRows()) {
                if (!quota.evictOnBreach()) {
                    recordTenantAdmission(contract, String.valueOf(tenantValue), false);
                    return false;
                }
                long evictCount = tenantSize - quota.maxHotRows() + 1L;
                List<String> evictedIds = jedis.zrange(tenantSetKey, 0, Math.max(0L, evictCount - 1L));
                for (String evictedId : evictedIds) {
                    removeEntity((ID) evictedId);
                }
                recordEviction(evictedIds.size());
            }
        }
        if (quota.memoryBudgetBytes() > 0L
                && !allowTenantMemoryBudget(tenantSetKey, payloadBytesKey, idValue, quota, estimatedPayloadBytes, replacedPayloadBytes)) {
            recordTenantAdmission(contract, String.valueOf(tenantValue), false);
            return false;
        }
        reserveTenantMembershipLocked(id, tenantSetKey, payloadBytesKey, Math.max(1L, estimatedPayloadBytes));
        recordTenantAdmission(contract, String.valueOf(tenantValue), true);
        return true;
    }

    private boolean shouldCacheEntity(Map<String, Object> columns, CacheAdmissionSource source, CachePolicy effectiveCachePolicy) {
        return effectiveCachePolicy.hotPolicy().shouldAdmit(columns, source);
    }

    private void recordTenantAccess(ID id, Map<String, Object> columns, long payloadBytes) {
        RouteCacheContract contract = RouteCacheContext.currentContract();
        if (contract == null || !contract.tenantQuota().bounded()) {
            return;
        }
        TenantCacheQuota quota = contract.tenantQuota();
        Object tenantValue = columnValue(columns, quota.tenantColumn());
        if (tenantValue == null) {
            return;
        }
        String tenantSetKey = keyStrategy.tenantHotSetKey(metadata.redisNamespace(), quota.tenantColumn(), tenantValue);
        String payloadBytesKey = keyStrategy.tenantHotPayloadBytesKey(metadata.redisNamespace(), quota.tenantColumn(), tenantValue);
        try {
            withTenantAdmissionLock(tenantSetKey, () -> {
                recordTenantAccessLocked(id, contract, quota, tenantValue, tenantSetKey, payloadBytesKey, payloadBytes);
                return null;
            });
        } catch (TenantAdmissionBusyException exception) {
            recordTenantAdmission(contract, String.valueOf(tenantValue), false);
        }
    }

    private void recordTenantAccessLocked(
            ID id,
            RouteCacheContract contract,
            TenantCacheQuota quota,
            Object tenantValue,
            String tenantSetKey,
            String payloadBytesKey,
            long payloadBytes
    ) {
        TenantOwnerRecord reservation = readTenantOwner(id);
        if (!tenantSetKey.equals(reservation.tenantSetKey())) {
            jedis.del(keyStrategy.entityKey(metadata.redisNamespace(), id));
            hotSetManager.remove(metadata.redisNamespace(), id);
            queryIndexManager.removeById(id);
            recordTenantAdmission(contract, String.valueOf(tenantValue), false);
            return;
        }
        reserveTenantMembershipLocked(id, tenantSetKey, payloadBytesKey, payloadBytes);
        String idValue = String.valueOf(id);
        jedis.zadd(tenantSetKey, System.currentTimeMillis(), idValue);
        if (quota.memoryBudgetBytes() > 0L && !tenantWithinMemoryBudget(tenantSetKey, payloadBytesKey, quota)) {
            if (quota.evictOnBreach()) {
                evictUntilTenantMemoryFits(tenantSetKey, payloadBytesKey, idValue, quota, 0L, 0L);
            }
            if (!tenantWithinMemoryBudget(tenantSetKey, payloadBytesKey, quota)) {
                removeEntity(id);
                recordTenantAdmission(contract, String.valueOf(tenantValue), false);
            }
        }
    }

    private void reserveTenantMembershipLocked(ID id, String tenantSetKey, String payloadBytesKey, long payloadBytes) {
        String ownerKey = keyStrategy.tenantHotOwnerKey(metadata.redisNamespace(), id);
        String idValue = String.valueOf(id);
        TenantOwnerRecord previousOwner = readTenantOwner(id);
        if (previousOwner.tenantSetKey() != null && !previousOwner.tenantSetKey().equals(tenantSetKey)) {
            jedis.zrem(previousOwner.tenantSetKey(), idValue);
            decrementPayloadBytes(previousOwner.payloadBytesKey(), previousOwner.payloadBytes());
        } else if (previousOwner.tenantSetKey() != null) {
            decrementPayloadBytes(previousOwner.payloadBytesKey(), previousOwner.payloadBytes());
        }
        jedis.zadd(tenantSetKey, System.currentTimeMillis(), idValue);
        incrementPayloadBytes(payloadBytesKey, payloadBytes);
        jedis.set(ownerKey, TenantOwnerRecord.encode(tenantSetKey, payloadBytesKey, payloadBytes));
    }

    private void removeTenantMembership(ID id) {
        String ownerKey = keyStrategy.tenantHotOwnerKey(metadata.redisNamespace(), id);
        TenantOwnerRecord ownerRecord = TenantOwnerRecord.decode(jedis.get(ownerKey));
        if (ownerRecord.tenantSetKey() == null) {
            jedis.del(ownerKey);
            return;
        }
        withTenantAdmissionLock(ownerRecord.tenantSetKey(), () -> {
            TenantOwnerRecord currentOwner = TenantOwnerRecord.decode(jedis.get(ownerKey));
            if (currentOwner.tenantSetKey() != null) {
                jedis.zrem(currentOwner.tenantSetKey(), String.valueOf(id));
                decrementPayloadBytes(currentOwner.payloadBytesKey(), currentOwner.payloadBytes());
            }
            jedis.del(ownerKey);
            return null;
        });
    }

    private boolean allowTenantMemoryBudget(
            String tenantSetKey,
            String payloadBytesKey,
            String idValue,
            TenantCacheQuota quota,
            long estimatedPayloadBytes,
            long replacedPayloadBytes
    ) {
        long incomingPayloadBytes = Math.max(1L, estimatedPayloadBytes);
        if (tenantProjectedMemoryBytes(tenantSetKey, payloadBytesKey, incomingPayloadBytes, replacedPayloadBytes)
                <= quota.memoryBudgetBytes()) {
            return true;
        }
        if (!quota.evictOnBreach()) {
            return false;
        }
        evictUntilTenantMemoryFits(tenantSetKey, payloadBytesKey, idValue, quota, incomingPayloadBytes, replacedPayloadBytes);
        return tenantProjectedMemoryBytes(tenantSetKey, payloadBytesKey, incomingPayloadBytes, replacedPayloadBytes)
                <= quota.memoryBudgetBytes();
    }

    private void evictUntilTenantMemoryFits(
            String tenantSetKey,
            String payloadBytesKey,
            String protectedId,
            TenantCacheQuota quota,
            long incomingPayloadBytes,
            long replacedPayloadBytes
    ) {
        int attempts = 0;
        while (tenantProjectedMemoryBytes(tenantSetKey, payloadBytesKey, incomingPayloadBytes, replacedPayloadBytes)
                > quota.memoryBudgetBytes()) {
            List<String> candidates = jedis.zrange(tenantSetKey, 0, 15);
            if (candidates == null || candidates.isEmpty()) {
                return;
            }
            boolean evicted = false;
            for (String candidate : candidates) {
                if (candidate.equals(protectedId)) {
                    continue;
                }
                removeEntity((ID) candidate);
                recordEviction(1);
                evicted = true;
                break;
            }
            attempts++;
            if (!evicted || attempts > 1_024) {
                return;
            }
        }
    }

    private boolean tenantWithinMemoryBudget(String tenantSetKey, String payloadBytesKey, TenantCacheQuota quota) {
        return tenantProjectedMemoryBytes(tenantSetKey, payloadBytesKey, 0L, 0L) <= quota.memoryBudgetBytes();
    }

    private long tenantProjectedMemoryBytes(String tenantSetKey, String payloadBytesKey, long incomingPayloadBytes, long replacedPayloadBytes) {
        long currentPayloadBytes = Math.max(0L, parseLong(jedis.get(payloadBytesKey), 0L) - Math.max(0L, replacedPayloadBytes));
        return currentPayloadBytes
                + Math.max(0L, incomingPayloadBytes)
                + redisMemoryUsage(tenantSetKey)
                + redisMemoryUsage(payloadBytesKey);
    }

    private long redisMemoryUsage(String key) {
        try {
            Long bytes = jedis.memoryUsage(key);
            return bytes == null ? 0L : Math.max(0L, bytes);
        } catch (RuntimeException ignored) {
            return 0L;
        }
    }

    private long measuredPayloadBytes(ID id, long fallback) {
        long redisBytes = redisMemoryUsage(keyStrategy.entityKey(metadata.redisNamespace(), id));
        return redisBytes > 0L ? redisBytes : Math.max(1L, fallback);
    }

    private long estimatePayloadBytes(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return 1L;
        }
        return encoded.getBytes(StandardCharsets.UTF_8).length + 128L;
    }

    private TenantOwnerRecord readTenantOwner(ID id) {
        return TenantOwnerRecord.decode(jedis.get(keyStrategy.tenantHotOwnerKey(metadata.redisNamespace(), id)));
    }

    private void incrementPayloadBytes(String payloadBytesKey, long bytes) {
        if (payloadBytesKey == null || bytes <= 0L) {
            return;
        }
        jedis.incrBy(payloadBytesKey, bytes);
    }

    private void decrementPayloadBytes(String payloadBytesKey, long bytes) {
        if (payloadBytesKey == null || bytes <= 0L) {
            return;
        }
        long remaining = jedis.decrBy(payloadBytesKey, bytes);
        if (remaining <= 0L) {
            jedis.del(payloadBytesKey);
        }
    }

    private Object columnValue(Map<String, Object> columns, String columnName) {
        if (columns == null || columnName == null) {
            return null;
        }
        if (columns.containsKey(columnName)) {
            return columns.get(columnName);
        }
        for (Map.Entry<String, Object> entry : columns.entrySet()) {
            String key = entry.getKey();
            if (key != null && key.equalsIgnoreCase(columnName)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private void recordTenantAdmission(RouteCacheContract contract, String tenantValue, boolean admitted) {
        if (performanceCollector == null) {
            return;
        }
        performanceCollector.recordCacheAdmission(
                "tenant-quota:" + contract.routeName() + ":" + metadata.entityName() + ":" + tenantValue,
                admitted
        );
    }

    private void recordEviction(int evictedCount) {
        if (performanceCollector == null || evictedCount <= 0) {
            return;
        }
        performanceCollector.recordCacheEviction("entity:" + metadata.entityName(), evictedCount);
    }

    private <R> R withTenantAdmissionLock(String tenantSetKey, Supplier<R> action) {
        String lockKey = tenantSetKey + ":admission-lock";
        Set<String> heldLocks = heldTenantLocks.get();
        if (heldLocks.contains(lockKey)) {
            return action.get();
        }
        String owner = UUID.randomUUID().toString();
        boolean acquired = false;
        for (int attempt = 0; attempt < 100 && !acquired; attempt++) {
            acquired = jedis.set(
                    lockKey,
                    owner,
                    SetParams.setParams().nx().px(TENANT_ADMISSION_LOCK_MILLIS)
            ) != null;
            if (!acquired) {
                sleepTenantLockRetry();
            }
        }
        if (!acquired) {
            throw new TenantAdmissionBusyException();
        }
        heldLocks.add(lockKey);
        try {
            return action.get();
        } finally {
            heldLocks.remove(lockKey);
            if (heldLocks.isEmpty()) {
                heldTenantLocks.remove();
            }
            jedis.eval(RELEASE_LOCK_SCRIPT, List.of(lockKey), List.of(owner));
        }
    }

    private void sleepTenantLockRetry() {
        try {
            Thread.sleep(10L);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for tenant cache admission", exception);
        }
    }

    private static final class TenantAdmissionBusyException extends IllegalStateException {
        private TenantAdmissionBusyException() {
            super("Tenant cache admission is busy");
        }
    }

    private long parseLong(String value, long fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private record TenantOwnerRecord(String tenantSetKey, String payloadBytesKey, long payloadBytes) {
        private static TenantOwnerRecord decode(String encoded) {
            if (encoded == null || encoded.isBlank()) {
                return new TenantOwnerRecord(null, null, 0L);
            }
            String[] parts = encoded.split("\\n", -1);
            if (parts.length == 1) {
                return new TenantOwnerRecord(parts[0], null, 0L);
            }
            return new TenantOwnerRecord(
                    blankToNull(parts[0]),
                    blankToNull(parts.length > 1 ? parts[1] : null),
                    parsePayloadBytes(parts.length > 2 ? parts[2] : null)
            );
        }

        private static String encode(String tenantSetKey, String payloadBytesKey, long payloadBytes) {
            return tenantSetKey + "\n" + payloadBytesKey + "\n" + Math.max(0L, payloadBytes);
        }

        private static String blankToNull(String value) {
            return value == null || value.isBlank() ? null : value;
        }

        private static long parsePayloadBytes(String value) {
            if (value == null || value.isBlank()) {
                return 0L;
            }
            try {
                return Math.max(0L, Long.parseLong(value.trim()));
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
    }
}
