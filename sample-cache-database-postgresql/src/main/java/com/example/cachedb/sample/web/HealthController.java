package com.example.cachedb.sample.web;

import com.reactor.cachedb.starter.CacheDatabase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import redis.clients.jedis.JedisPooled;

@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final CacheDatabase cacheDatabase;
    private final JedisPooled jedis;

    public HealthController(CacheDatabase cacheDatabase, JedisPooled jedis) {
        this.cacheDatabase = cacheDatabase;
        this.jedis = jedis;
    }

    @GetMapping("/ready")
    public ReadyResponse ready() {
        var worker = cacheDatabase.workerSnapshot();
        return new ReadyResponse(
                "UP",
                jedis.ping(),
                worker.lastErrorType() == null,
                worker.flushedCount(),
                worker.lastObservedBacklog(),
                worker.deadLetterCount(),
                worker.pendingRecoveryCount(),
                worker.lastErrorType(),
                worker.lastErrorRootType()
        );
    }

    public record ReadyResponse(
            String status,
            String redis,
            boolean writeBehindHealthy,
            long flushedWrites,
            long lastObservedBacklog,
            long deadLetterCount,
            long pendingRecoveryCount,
            String lastErrorType,
            String lastErrorRootType
    ) {
    }
}
