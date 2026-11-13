package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named = "cachedb.redis.cluster", matches = "true")
class RedisReadPhysicalCapacityClusterTest {
    @Test void clusterReportsTheSmallestPerNodeCapacityAndRejectsKnownUnboundedPools() {
        try (var client = RedisReadCoalescingClusterTest.connect()) {
            var pools = new ArrayList<>(client.commands().getClusterNodes().values());
            assertTrue(pools.size() >= 3, "A real multi-node cluster is required");
            var original = pools.stream().map(pool -> pool.getMaxTotal()).toList();
            try {
                for (int i = 0; i < pools.size(); i++) pools.get(i).setMaxTotal(3 + i);
                assertEquals(3, client.physicalPoolCapacity(), "Cluster capacity must not sum per-node pools");
                pools.getFirst().setMaxTotal(-1);
                assertThrows(IllegalStateException.class, client::physicalPoolCapacity);
            } finally { for (int i = 0; i < pools.size(); i++) pools.get(i).setMaxTotal(original.get(i)); }
        }
    }
}
