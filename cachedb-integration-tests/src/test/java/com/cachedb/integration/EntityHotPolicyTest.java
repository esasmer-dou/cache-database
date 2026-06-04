package com.reactor.cachedb.integration;

import com.reactor.cachedb.core.cache.CacheAdmissionSource;
import com.reactor.cachedb.core.cache.EntityHotPolicy;
import com.reactor.cachedb.core.cache.EntityHotPolicyCompositeOperator;
import com.reactor.cachedb.core.cache.EntityHotPolicyMode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityHotPolicyTest {

    @Test
    void shouldAdmitRowsInsideTimeWindow() {
        Instant now = Instant.parse("2026-05-21T12:00:00Z");
        EntityHotPolicy policy = EntityHotPolicy.builder()
                .mode(EntityHotPolicyMode.TIME_WINDOW)
                .timeColumn("order_date")
                .hotForDays(90)
                .build();

        assertTrue(policy.shouldAdmit(
                Map.of("order_date", now.minus(89, ChronoUnit.DAYS)),
                CacheAdmissionSource.WRITE,
                now
        ));
        assertFalse(policy.shouldAdmit(
                Map.of("order_date", now.minus(91, ChronoUnit.DAYS)),
                CacheAdmissionSource.WRITE,
                now
        ));
    }

    @Test
    void shouldRespectReadAdmissionGate() {
        Instant now = Instant.parse("2026-05-21T12:00:00Z");
        EntityHotPolicy policy = EntityHotPolicy.builder()
                .mode(EntityHotPolicyMode.TIME_WINDOW)
                .timeColumn("created_at")
                .hotForDays(10)
                .admitOnRead(false)
                .build();

        assertTrue(policy.shouldAdmit(
                Map.of("created_at", now.minus(1, ChronoUnit.DAYS)),
                CacheAdmissionSource.WRITE,
                now
        ));
        assertFalse(policy.shouldAdmit(
                Map.of("created_at", now.minus(1, ChronoUnit.DAYS)),
                CacheAdmissionSource.READ,
                now
        ));
        assertTrue(policy.shouldAdmit(
                Map.of("created_at", now.minus(1, ChronoUnit.DAYS)),
                CacheAdmissionSource.SERVE,
                now
        ));
    }

    @Test
    void shouldAdmitConfiguredStatesOnly() {
        EntityHotPolicy policy = EntityHotPolicy.stateWindow("status", java.util.List.of("OPEN", "PENDING"));

        assertTrue(policy.shouldAdmit(Map.of("status", "OPEN"), CacheAdmissionSource.WRITE));
        assertTrue(policy.shouldAdmit(Map.of("STATUS", "PENDING"), CacheAdmissionSource.WRITE));
        assertFalse(policy.shouldAdmit(Map.of("status", "CLOSED"), CacheAdmissionSource.WRITE));
    }

    @Test
    void shouldSupportCustomPredicate() {
        EntityHotPolicy policy = EntityHotPolicy.customPredicate(columns -> "VIP".equals(columns.get("customer_type")));

        assertTrue(policy.shouldAdmit(Map.of("customer_type", "VIP"), CacheAdmissionSource.WRITE));
        assertFalse(policy.shouldAdmit(Map.of("customer_type", "STANDARD"), CacheAdmissionSource.WRITE));
    }

    @Test
    void shouldRequireAllCompositeChildrenWhenOperatorIsAll() {
        Instant now = Instant.parse("2026-05-21T12:00:00Z");
        EntityHotPolicy policy = EntityHotPolicy.builder()
                .mode(EntityHotPolicyMode.COMPOSITE)
                .compositeOperator(EntityHotPolicyCompositeOperator.ALL)
                .children(java.util.List.of(
                        EntityHotPolicy.timeWindow("order_date", 90L * 86_400L),
                        EntityHotPolicy.stateWindow("status", java.util.List.of("OPEN", "PENDING")),
                        EntityHotPolicy.customPredicate(columns -> "VIP".equals(columns.get("customer_type")))
                ))
                .build();

        assertTrue(policy.shouldAdmit(
                Map.of("order_date", now.minus(5, ChronoUnit.DAYS), "status", "OPEN", "customer_type", "VIP"),
                CacheAdmissionSource.WARM,
                now
        ));
        assertFalse(policy.shouldAdmit(
                Map.of("order_date", now.minus(5, ChronoUnit.DAYS), "status", "CLOSED", "customer_type", "VIP"),
                CacheAdmissionSource.WARM,
                now
        ));
    }

    @Test
    void shouldAdmitAnyCompositeChildWhenOperatorIsAny() {
        EntityHotPolicy policy = EntityHotPolicy.anyOf(java.util.List.of(
                EntityHotPolicy.stateWindow("status", java.util.List.of("OPEN")),
                EntityHotPolicy.customPredicate(columns -> Boolean.TRUE.equals(columns.get("vip")))
        ));

        assertTrue(policy.shouldAdmit(Map.of("status", "CLOSED", "vip", true), CacheAdmissionSource.WRITE));
        assertTrue(policy.shouldAdmit(Map.of("status", "OPEN", "vip", false), CacheAdmissionSource.WRITE));
        assertFalse(policy.shouldAdmit(Map.of("status", "CLOSED", "vip", false), CacheAdmissionSource.WRITE));
    }
}
