package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class RedisIndexMutationPlannerTest {
    @Test void identicalMetadataDoesNotPlanWrites() {
        assertTrue(RedisIndexMutationPlanner.plan(Map.of("a", "1"), Map.of("a", "1"), false).unchanged());
    }
    @Test void onlyChangedAddedAndRemovedFieldsAreTouched() {
        var plan = RedisIndexMutationPlanner.plan(Map.of("a", "1", "b", "2", "same", "x"),
                Map.of("a", "3", "c", "4", "same", "x"), false);
        assertEquals(Set.of("a", "b", "c"), plan.changed());
        assertThrows(UnsupportedOperationException.class, () -> plan.changed().add("other"));
    }
    @Test void rebuildForcesAllColumnsAndRejectsOversizedMetadata() {
        assertEquals(Set.of("a"), RedisIndexMutationPlanner.plan(Map.of("a", "1"), Map.of("a", "1"), true).changed());
        Map<String, String> huge = new HashMap<>();
        for (int i=0; i<com.reactor.cachedb.core.codec.LengthPrefixedPayloadCodec.MAX_FIELDS; i++) huge.put("field"+i, "value");
        assertEquals(4096, RedisIndexMutationPlanner.plan(Map.of(), huge, false).changed().size());
        huge.put("overflow", "value");
        assertThrows(IllegalArgumentException.class, () -> RedisIndexMutationPlanner.plan(Map.of(), huge, false));
    }
}
