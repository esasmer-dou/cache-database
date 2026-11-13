package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;
import static com.reactor.cachedb.redis.RedisCompositeRangeTest.*;
import com.reactor.cachedb.core.api.EntityQueryCapacity;
import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.config.*;
import com.reactor.cachedb.core.plan.FetchPlan;
import com.reactor.cachedb.core.query.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class RedisEntityQueryCapacityTest {
    private RedisEntityRepository<Row,String> repository(CachePolicy policy, ReadShapeGuardrailConfig shape) {
        return repository(policy, shape, false);
    }

    private RedisEntityRepository<Row,String> repository(CachePolicy policy, ReadShapeGuardrailConfig shape, boolean guarded) {
        RedisClientOperations unused = new RedisClientOperations() {
            public redis.clients.jedis.UnifiedJedis commands() { throw new AssertionError("No transport for capacity/preflight rejection"); }
        };
        var metadata = metadata(false, Map.of());
        var keys = new RedisKeyStrategy("query_capacity_unit");
        var guard = guarded ? new RedisProducerGuard(unused, RedisGuardrailConfig.defaults(),
                WriteBehindConfig.defaults(), keys, "unused_diagnostics", 64) : null;
        var pages = new RedisPageCacheManager<>(unused, metadata, CODEC, policy, keys, null, null, guard, shape);
        return new RedisEntityRepository<>(unused, metadata, CODEC, null, keys, FetchPlan.empty(), policy,
                null, guard, RedisGuardrailConfig.defaults(), "unused", "unused_compact", 1, null, null,
                RelationConfig.defaults(), QueryIndexConfig.defaults(), null, null, null, PageCacheConfig.defaults(),
                ReadThroughConfig.defaults(), shape, pages, new QueryEvaluator(), null, null, unused, null, null);
    }

    @Test void nativeZeroAndFiniteCapacitiesMatchRealGuardRejectionsWithoutRedisOrSql() {
        var policy = CachePolicy.builder().hotEntityLimit(8).pageSize(8).build();
        var zero = repository(policy, ReadShapeGuardrailConfig.builder().hotSetHeadroom(10).build());
        assertEquals(EntityQueryCapacity.finite(0), zero.entityQueryCapacity());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> zero.query(QuerySpec.allOf().limitTo(1)))
                .getMessage().contains("configured safe limit is 0"));
        var finite = repository(policy, ReadShapeGuardrailConfig.builder().hotSetHeadroom(2).maxEntityQueryLimit(3).build());
        assertEquals(EntityQueryCapacity.finite(3), finite.entityQueryCapacity());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> finite.query(QuerySpec.allOf().limitTo(4)))
                .getMessage().contains("configured safe limit is 3"));
    }

    @Test void fetchPlanViewsPreserveTheEffectiveCapacity() {
        var repository = repository(CachePolicy.builder().hotEntityLimit(8).pageSize(8).build(),
                ReadShapeGuardrailConfig.builder().hotSetHeadroom(10).build());
        assertEquals(EntityQueryCapacity.finite(0), repository.withFetchPlan(FetchPlan.empty()).entityQueryCapacity());
    }

    @Test void actuallyUnrestrictedNativePolicyIsNotConfusedWithZero() {
        var repository = repository(CachePolicy.builder().hotEntityLimit(CachePolicy.UNBOUNDED).pageSize(0).build(),
                ReadShapeGuardrailConfig.defaults());
        assertEquals(EntityQueryCapacity.unrestricted(), repository.entityQueryCapacity());
    }

    @Test void freshEnabledProducerGuardAndItsViewsNeverSampleRedisDuringCapacityInspection() {
        var repository = repository(CachePolicy.builder().hotEntityLimit(8).pageSize(8).build(),
                ReadShapeGuardrailConfig.builder().hotSetHeadroom(2).build(), true);
        assertEquals(EntityQueryCapacity.finite(6), repository.entityQueryCapacity());
        assertEquals(EntityQueryCapacity.finite(6), repository.withFetchPlan(FetchPlan.empty()).entityQueryCapacity());
    }
}
