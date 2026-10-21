package com.reactor.cachedb.core.cache;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class CachePolicyValueSemanticsTest {
    @Test
    void directDefaultFactoriesRetainBuilderDefaultsAndFreshValueInstances() {
        var defaults = CachePolicy.defaults();
        var builderDefaults = CachePolicy.builder().build();
        assertEquals(builderDefaults, defaults);
        assertEquals(builderDefaults.hashCode(), defaults.hashCode());
        assertNotSame(defaults, CachePolicy.defaults());
        var count = EntityHotPolicy.countWindow();
        var builderCount = EntityHotPolicy.builder().mode(EntityHotPolicyMode.COUNT_WINDOW).build();
        assertEquals(builderCount, count);
        assertEquals(builderCount.hashCode(), count.hashCode());
        assertNotSame(count, EntityHotPolicy.countWindow());
        assertEquals(builderCount.children().contains(null), count.children().contains(null));
        assertThrows(UnsupportedOperationException.class, () -> count.children().add(count));
        for (var source : CacheAdmissionSource.values()) {
            assertEquals(
                    builderCount.shouldAdmit(Map.of(), source),
                    count.shouldAdmit(Map.of(), source));
        }
    }

    record PolicyShape(
            int limit, int page, boolean lru, long entityTtl, long pageTtl, EntityHotPolicy hot) {}

    record HotShape(
            EntityHotPolicyMode mode,
            String time,
            long seconds,
            String state,
            List<String> states,
            boolean write,
            boolean read,
            boolean warm,
            boolean evict,
            CacheAdmissionPredicate predicate,
            EntityHotPolicyCompositeOperator operator,
            List<EntityHotPolicy> children) {}

    private static HotShape shape(EntityHotPolicy value) {
        return new HotShape(
                value.mode(),
                value.timeColumn(),
                value.hotForSeconds(),
                value.stateColumn(),
                value.stateValues(),
                value.admitOnWrite(),
                value.admitOnRead(),
                value.admitOnWarm(),
                value.evictWhenRejected(),
                value.customPredicate(),
                value.compositeOperator(),
                value.children());
    }

    @Test
    void preservesRecordEqualityHashAndImmutableChildOrder() {
        var random = new Random(217_501);
        for (int i = 0; i < 300; i++) {
            var hot =
                    new EntityHotPolicy(
                            EntityHotPolicyMode.values()[i % EntityHotPolicyMode.values().length],
                            i % 2 == 0 ? "created_at" : null,
                            random.nextLong(),
                            "status",
                            List.of("OPEN", "PENDING"),
                            random.nextBoolean(),
                            random.nextBoolean(),
                            random.nextBoolean(),
                            random.nextBoolean(),
                            null,
                            i % 2 == 0
                                    ? EntityHotPolicyCompositeOperator.ALL
                                    : EntityHotPolicyCompositeOperator.ANY,
                            List.of(EntityHotPolicy.countWindow()));
            var s = shape(hot);
            var copy =
                    new EntityHotPolicy(
                            s.mode(),
                            s.time(),
                            s.seconds(),
                            s.state(),
                            s.states(),
                            s.write(),
                            s.read(),
                            s.warm(),
                            s.evict(),
                            s.predicate(),
                            s.operator(),
                            s.children());
            assertEquals(hot, copy);
            assertEquals(s.hashCode(), hot.hashCode());
            assertNotEquals(hot, EntityHotPolicy.countWindow());
            assertNotEquals(hot, s);
            var policy =
                    new CachePolicy(
                            random.nextInt(1000) - 1,
                            random.nextInt(),
                            random.nextBoolean(),
                            random.nextLong(),
                            random.nextLong(),
                            hot);
            var mirror =
                    new PolicyShape(
                            policy.hotEntityLimit(),
                            policy.pageSize(),
                            policy.lruEvictionEnabled(),
                            policy.entityTtlSeconds(),
                            policy.pageTtlSeconds(),
                            policy.hotPolicy());
            assertEquals(mirror.hashCode(), policy.hashCode());
            assertEquals(
                    policy,
                    new CachePolicy(
                            mirror.limit(),
                            mirror.page(),
                            mirror.lru(),
                            mirror.entityTtl(),
                            mirror.pageTtl(),
                            mirror.hot()));
        }
        var input = new ArrayList<>(Arrays.asList(EntityHotPolicy.countWindow(), null));
        var policy = EntityHotPolicy.allOf(input);
        input.clear();
        assertEquals(1, policy.children().size());
        assertFalse(policy.children().contains(null));
        assertThrows(UnsupportedOperationException.class, () -> policy.children().clear());
        assertFalse(EntityHotPolicy.countWindow().children().contains(null));
    }
}
