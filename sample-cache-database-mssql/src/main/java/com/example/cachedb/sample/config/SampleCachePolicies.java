package com.example.cachedb.sample.config;

import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.cache.EntityHotPolicy;
import com.reactor.cachedb.core.cache.EntityHotPolicyCompositeOperator;
import com.reactor.cachedb.core.cache.EntityHotPolicyMode;

import java.util.List;

public final class SampleCachePolicies {

    private static final long DAY = 86_400L;

    private SampleCachePolicies() {
    }

    public static CachePolicy platformDefaultPolicy() {
        return CachePolicy.builder()
                .hotEntityLimit(25_000)
                .pageSize(100)
                .entityTtlSeconds(0)
                .pageTtlSeconds(90)
                .compositeHotPolicy(EntityHotPolicyCompositeOperator.ANY, List.of(
                        EntityHotPolicy.timeWindow("order_date", 90L * DAY),
                        EntityHotPolicy.timeWindow("updated_at", 30L * DAY),
                        EntityHotPolicy.timeWindow("created_at", 1L * DAY),
                        EntityHotPolicy.stateWindow("status", List.of(
                                "ACTIVE",
                                "NEW",
                                "PAID",
                                "PICKING",
                                "OPEN",
                                "PENDING",
                                "ESCALATED",
                                "SLA_BREACH",
                                "QUEUED",
                                "RUNNING",
                                "FAILED"
                        )),
                        EntityHotPolicy.stateWindow("active_status", List.of("ACTIVE")),
                        EntityHotPolicy.stateWindow("shipment_status", List.of(
                                "IN_TRANSIT",
                                "OUT_FOR_DELIVERY",
                                "DELAYED",
                                "EXCEPTION"
                        )),
                        EntityHotPolicy.stateWindow("stock_status", List.of("IN_STOCK", "LOW_STOCK", "RESERVED"))
                ))
                .build();
    }

    public static CachePolicy commerceTimelinePolicy() {
        return CachePolicy.builder()
                .hotEntityLimit(100_000)
                .pageSize(100)
                .entityTtlSeconds(0)
                .pageTtlSeconds(60)
                .compositeHotPolicy(EntityHotPolicyCompositeOperator.ANY, List.of(
                        EntityHotPolicy.timeWindow("order_date", 90L * DAY),
                        EntityHotPolicy.stateWindow("status", List.of("NEW", "PAID", "PICKING", "OPEN", "PENDING")),
                        EntityHotPolicy.stateWindow("segment", List.of("VIP"))
                ))
                .build();
    }

    public static CachePolicy catalogAvailabilityPolicy() {
        return CachePolicy.builder()
                .hotEntityLimit(25_000)
                .pageSize(100)
                .entityTtlSeconds(0)
                .pageTtlSeconds(15)
                .compositeHotPolicy(EntityHotPolicyCompositeOperator.ANY, List.of(
                        EntityHotPolicy.stateWindow("active_status", List.of("ACTIVE")),
                        EntityHotPolicy.stateWindow("stock_status", List.of("IN_STOCK", "LOW_STOCK", "RESERVED")),
                        EntityHotPolicy.timeWindow("updated_at", 7L * DAY)
                ))
                .build();
    }

    public static CachePolicy supportOperationsPolicy() {
        return CachePolicy.builder()
                .hotEntityLimit(50_000)
                .pageSize(50)
                .entityTtlSeconds(0)
                .pageTtlSeconds(20)
                .compositeHotPolicy(EntityHotPolicyCompositeOperator.ANY, List.of(
                        EntityHotPolicy.timeWindow("updated_at", 30L * DAY),
                        EntityHotPolicy.stateWindow("status", List.of("OPEN", "PENDING", "ESCALATED", "SLA_BREACH"))
                ))
                .build();
    }

    public static CachePolicy logisticsTrackingPolicy() {
        return CachePolicy.builder()
                .hotEntityLimit(150_000)
                .pageSize(100)
                .entityTtlSeconds(0)
                .pageTtlSeconds(30)
                .compositeHotPolicy(EntityHotPolicyCompositeOperator.ANY, List.of(
                        EntityHotPolicy.timeWindow("updated_at", 14L * DAY),
                        EntityHotPolicy.stateWindow("shipment_status", List.of(
                                "IN_TRANSIT",
                                "OUT_FOR_DELIVERY",
                                "DELAYED",
                                "EXCEPTION"
                        )),
                        EntityHotPolicy.stateWindow("event_type", List.of("DELAY", "EXCEPTION", "DELIVERED"))
                ))
                .build();
    }

    public static CachePolicy reportingLivePolicy() {
        return CachePolicy.builder()
                .hotEntityLimit(5_000)
                .pageSize(50)
                .entityTtlSeconds(0)
                .pageTtlSeconds(30)
                .compositeHotPolicy(EntityHotPolicyCompositeOperator.ANY, List.of(
                        EntityHotPolicy.timeWindow("created_at", DAY),
                        EntityHotPolicy.stateWindow("status", List.of("QUEUED", "RUNNING", "FAILED"))
                ))
                .build();
    }

    public static CachePolicy auditArchivePolicy() {
        return CachePolicy.builder()
                .hotEntityLimit(2_000)
                .pageSize(50)
                .entityTtlSeconds(0)
                .pageTtlSeconds(10)
                .hotPolicy(EntityHotPolicy.builder()
                        .mode(EntityHotPolicyMode.COMPOSITE)
                        .compositeOperator(EntityHotPolicyCompositeOperator.ANY)
                        .admitOnRead(false)
                        .children(List.of(
                                EntityHotPolicy.timeWindow("created_at", DAY),
                                EntityHotPolicy.stateWindow("severity", List.of("WARN", "ERROR", "SECURITY"))
                        ))
                        .build())
                .build();
    }

    public static List<PolicyProfile> profiles() {
        return List.of(
                new PolicyProfile(
                        "commerceTimeline",
                        "Customer order timeline and high-value order screens",
                        "OrderSummary projection; full entity only for explicit detail",
                        commerceTimelinePolicy().hotEntityLimit(),
                        commerceTimelinePolicy().pageSize(),
                        1_000
                ),
                new PolicyProfile(
                        "catalogAvailability",
                        "Product list, category page, and low-stock operational screens",
                        "ProductAvailability projection; SQL route for inactive catalog archive",
                        catalogAvailabilityPolicy().hotEntityLimit(),
                        catalogAvailabilityPolicy().pageSize(),
                        2_000
                ),
                new PolicyProfile(
                        "supportOperations",
                        "Open ticket queue, escalations, and customer support detail",
                        "Open queue in Redis; closed ticket history from SQL",
                        supportOperationsPolicy().hotEntityLimit(),
                        supportOperationsPolicy().pageSize(),
                        1_000
                ),
                new PolicyProfile(
                        "logisticsTracking",
                        "Active shipment tracking and exception dashboard",
                        "ShipmentSummary projection; event preview only on detail",
                        logisticsTrackingPolicy().hotEntityLimit(),
                        logisticsTrackingPolicy().pageSize(),
                        2_000
                ),
                new PolicyProfile(
                        "reportingLive",
                        "Live report jobs and small run summaries",
                        "Only live jobs in Redis; audit/export queries stay SQL-first",
                        reportingLivePolicy().hotEntityLimit(),
                        reportingLivePolicy().pageSize(),
                        500
                ),
                new PolicyProfile(
                        "auditArchive",
                        "Audit archive and one-off old-history reads",
                        "Do not promote one-off archive reads into Redis",
                        auditArchivePolicy().hotEntityLimit(),
                        auditArchivePolicy().pageSize(),
                        100
                )
        );
    }

    public record PolicyProfile(
            String name,
            String useCase,
            String routeContract,
            int hotEntityLimit,
            int pageSize,
            int suggestedRouteLimit
    ) {
    }
}
