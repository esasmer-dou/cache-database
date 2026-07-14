package com.example.cachedb.sample.config;

import com.reactor.cachedb.core.cache.CacheAdmissionSource;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SampleCachePoliciesTest {

    private static final Instant NOW = Instant.parse("2026-07-14T09:00:00Z");

    @Test
    void activeCustomerIsAdmittedWithoutVipSegment() {
        boolean admitted = SampleCachePolicies.customerDirectoryPolicy().hotPolicy().shouldAdmit(
                Map.of("status", "ACTIVE", "segment", "STANDARD", "updated_at", NOW.minusSeconds(60).getEpochSecond()),
                CacheAdmissionSource.WRITE,
                NOW
        );

        assertTrue(admitted);
    }

    @Test
    void orderLineUsesBoundedCountWindowInsteadOfUnrelatedOrderColumns() {
        boolean admitted = SampleCachePolicies.orderLinePreviewPolicy().hotPolicy().shouldAdmit(
                Map.of("status", "ACTIVE", "order_id", 10L),
                CacheAdmissionSource.WRITE,
                NOW
        );

        assertTrue(admitted);
    }

    @Test
    void completedOrderOutsideTimeWindowIsNotPromoted() {
        boolean admitted = SampleCachePolicies.orderTimelinePolicy().hotPolicy().shouldAdmit(
                Map.of("status", "COMPLETED", "order_date", NOW.minusSeconds(120L * 86_400L).getEpochSecond()),
                CacheAdmissionSource.READ,
                NOW
        );

        assertFalse(admitted);
    }

    @Test
    void recentShipmentEventIsAdmittedByItsOwnTimestamp() {
        boolean admitted = SampleCachePolicies.shipmentEventTimelinePolicy().hotPolicy().shouldAdmit(
                Map.of("event_time", NOW.minusSeconds(300).getEpochSecond(), "severity", "INFO"),
                CacheAdmissionSource.WARM,
                NOW
        );

        assertTrue(admitted);
    }
}
