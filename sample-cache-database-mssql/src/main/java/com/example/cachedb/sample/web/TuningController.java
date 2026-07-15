package com.example.cachedb.sample.web;

import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.config.CacheDatabaseConfig;
import com.reactor.cachedb.starter.CacheDatabase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/tuning")
public class TuningController {

    private final CacheDatabase cacheDatabase;

    public TuningController(CacheDatabase cacheDatabase) {
        this.cacheDatabase = cacheDatabase;
    }

    @GetMapping
    public TuningResponse current() {
        CacheDatabaseConfig config = cacheDatabase.config();
        CachePolicy policy = config.resourceLimits().defaultCachePolicy();
        return new TuningResponse(
                cacheDatabase.instanceId(),
                config.keyspace().keyPrefix(),
                policy.hotEntityLimit(),
                policy.pageSize(),
                policy.entityTtlSeconds(),
                policy.pageTtlSeconds(),
                policy.hotPolicy().mode().name(),
                config.readShapeGuardrail().maxEntityQueryLimit(),
                config.readShapeGuardrail().maxProjectionQueryLimit(),
                config.redisGuardrail().usedMemoryWarnMaxmemoryPercent(),
                config.redisGuardrail().usedMemoryCriticalMaxmemoryPercent(),
                List.of(
                        "Keep customer order timelines on the orderSummary projection.",
                        "Use entity detail only after the user selects one order.",
                        "Keep API limit <= projection hot window; do not expose unbounded list endpoints.",
                        "Set Redis maxmemory and keep maxmemory-policy=noeviction for this sample."
                )
        );
    }

    @GetMapping("/profiles")
    public List<PolicyProfile> profiles() {
        return List.of(
                profile("commerceTimeline", "OrderEntity", "Customer order timeline and high-value order screens",
                        "OrderSummary projection; full entity only for explicit detail", 1_000),
                profile("catalogAvailability", "ProductEntity", "Product list, category page, and low-stock operational screens",
                        "ProductAvailability projection; SQL route for inactive catalog archive", 1_000),
                profile("supportOperations", "SupportTicketEntity", "Open ticket queue, escalations, and customer support detail",
                        "Open queue in Redis; closed ticket history from SQL", 1_000),
                profile("logisticsTracking", "ShipmentEntity", "Active shipment tracking and exception dashboard",
                        "ShipmentSummary projection; event preview only on detail", 1_000),
                profile("reportingLive", "ReportJobEntity", "Live report jobs and small run summaries",
                        "Only live jobs in Redis; audit/export queries stay SQL-first", 500),
                profile("auditArchive", "AuditEventEntity", "Audit archive and one-off old-history reads",
                        "Do not promote one-off archive reads into Redis", 100)
        );
    }

    private PolicyProfile profile(
            String name,
            String entityName,
            String useCase,
            String routeContract,
            int suggestedRouteLimit
    ) {
        CachePolicy policy = cacheDatabase.registeredPolicy(entityName)
                .orElseThrow(() -> new IllegalStateException("CacheDB entity is not registered: " + entityName));
        return new PolicyProfile(
                name,
                useCase,
                routeContract,
                policy.hotEntityLimit(),
                policy.pageSize(),
                suggestedRouteLimit
        );
    }

    public record TuningResponse(
            String instanceId,
            String keyPrefix,
            int hotEntityLimit,
            int pageSize,
            long entityTtlSeconds,
            long pageTtlSeconds,
            String hotPolicyMode,
            int maxEntityQueryLimit,
            int maxProjectionQueryLimit,
            int redisWarnPercent,
            int redisCriticalPercent,
            List<String> notes
    ) {
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
