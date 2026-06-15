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
}
