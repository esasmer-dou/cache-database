package com.example.cachedb.sample.web;

import com.reactor.cachedb.starter.CacheDatabase;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import redis.clients.jedis.JedisPooled;

@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final CacheDatabase cacheDatabase;
    private final JedisPooled jedis;
    private final JdbcTemplate jdbcTemplate;

    public HealthController(CacheDatabase cacheDatabase, JedisPooled jedis, JdbcTemplate jdbcTemplate) {
        this.cacheDatabase = cacheDatabase;
        this.jedis = jedis;
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/live")
    public StatusResponse live() {
        return new StatusResponse("UP");
    }

    @GetMapping("/ready")
    public ResponseEntity<ReadyResponse> ready() {
        var worker = cacheDatabase.workerSnapshot();
        DependencyStatus redis = redisStatus();
        DependencyStatus database = databaseStatus();
        boolean dependenciesReady = redis.available() && database.available();
        boolean writeBehindHealthy = worker.lastErrorType() == null
                && worker.deadLetterCount() == 0
                && worker.pendingRecoveryCount() == 0;
        String status = dependenciesReady
                ? (writeBehindHealthy ? "UP" : "DEGRADED")
                : "DOWN";
        ReadyResponse response = new ReadyResponse(
                status,
                dependenciesReady,
                redis,
                database,
                writeBehindHealthy,
                worker.flushedCount(),
                worker.lastObservedBacklog(),
                worker.deadLetterCount(),
                worker.pendingRecoveryCount(),
                worker.lastErrorType(),
                worker.lastErrorRootType()
        );
        return ResponseEntity.status(dependenciesReady ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .body(response);
    }

    private DependencyStatus redisStatus() {
        try {
            return new DependencyStatus(true, jedis.ping(), null);
        } catch (RuntimeException exception) {
            return new DependencyStatus(false, null, exception.getClass().getSimpleName());
        }
    }

    private DependencyStatus databaseStatus() {
        try {
            Integer value = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return new DependencyStatus(value != null && value == 1, value == null ? null : value.toString(), null);
        } catch (RuntimeException exception) {
            return new DependencyStatus(false, null, exception.getClass().getSimpleName());
        }
    }

    public record ReadyResponse(
            String status,
            boolean ready,
            DependencyStatus redis,
            DependencyStatus database,
            boolean writeBehindHealthy,
            long flushedWrites,
            long lastObservedBacklog,
            long deadLetterCount,
            long pendingRecoveryCount,
            String lastErrorType,
            String lastErrorRootType
    ) {
    }

    public record DependencyStatus(boolean available, String response, String errorType) {
    }

    public record StatusResponse(String status) {
    }
}
