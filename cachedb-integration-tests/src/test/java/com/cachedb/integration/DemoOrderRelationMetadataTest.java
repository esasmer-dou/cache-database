package com.reactor.cachedb.integration;

import com.reactor.cachedb.examples.demo.entity.DemoOrderEntityCacheBinding;
import com.reactor.cachedb.examples.demo.entity.DemoOrderLineEntityCacheBinding;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DemoOrderRelationMetadataTest {

    @Test
    void shouldDeclareOrderLinesRelationForDemoOrders() {
        var relation = DemoOrderEntityCacheBinding.METADATA.relations().stream()
                .filter(candidate -> "orderLines".equals(candidate.name()))
                .findFirst()
                .orElse(null);

        assertNotNull(relation);
        assertEquals("DemoOrderLineEntity", relation.targetEntity());
        assertEquals("orderId", relation.mappedBy());
        assertEquals("ONE_TO_MANY", relation.kind().name());
        assertTrue(relation.batchLoadOnly());
        assertEquals("cachedb_demo_order_lines", DemoOrderLineEntityCacheBinding.METADATA.tableName());
    }
}
