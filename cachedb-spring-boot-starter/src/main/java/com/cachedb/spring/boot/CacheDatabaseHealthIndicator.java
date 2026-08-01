package com.reactor.cachedb.spring.boot;

import com.reactor.cachedb.starter.CacheDatabase;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import redis.clients.jedis.JedisPooled;

import javax.sql.DataSource;
import java.sql.Connection;

/** Readiness signal for Redis, durable SQL, and the write-behind worker. */
public final class CacheDatabaseHealthIndicator implements HealthIndicator {

    private final CacheDatabase cacheDatabase;
    private final JedisPooled jedis;
    private final DataSource dataSource;

    public CacheDatabaseHealthIndicator(CacheDatabase cacheDatabase, JedisPooled jedis, DataSource dataSource) {
        this.cacheDatabase = cacheDatabase;
        this.jedis = jedis;
        this.dataSource = dataSource;
    }

    @Override
    public Health health() {
        Health.Builder health = Health.up().withDetail("instanceId", cacheDatabase.instanceId());
        try {
            health.withDetail("redis", jedis.ping());
        } catch (RuntimeException exception) {
            return Health.down(exception).withDetail("dependency", "redis").build();
        }
        try (Connection connection = dataSource.getConnection()) {
            if (!connection.isValid(1)) {
                return Health.down().withDetail("dependency", "sql").withDetail("reason", "validation-failed").build();
            }
            health.withDetail("sql", "UP");
        } catch (Exception exception) {
            return Health.down(exception).withDetail("dependency", "sql").build();
        }

        var worker = cacheDatabase.workerSnapshot();
        long backlogHardLimit = cacheDatabase.config().redisGuardrail().writeBehindBacklogHardLimit();
        boolean backlogSaturated = backlogHardLimit > 0
                && worker.lastObservedBacklog() >= backlogHardLimit;
        boolean historicalAttention = worker.lastErrorType() != null
                || worker.deadLetterCount() > 0
                || worker.pendingRecoveryCount() > 0;
        health.withDetail("writeBehindBacklog", worker.lastObservedBacklog())
                .withDetail("writeBehindBacklogHardLimit", backlogHardLimit)
                .withDetail("flushedWrites", worker.flushedCount())
                .withDetail("deadLetters", worker.deadLetterCount())
                .withDetail("pendingRecovery", worker.pendingRecoveryCount())
                .withDetail("writeBehindStatus", backlogSaturated
                        ? "SATURATED"
                        : historicalAttention ? "ATTENTION" : "UP");
        if (worker.lastErrorType() != null) {
            health.withDetail("lastWriteBehindError", worker.lastErrorType());
        }
        if (worker.lastErrorRootType() != null) {
            health.withDetail("lastWriteBehindRootError", worker.lastErrorRootType());
        }
        if (backlogSaturated) {
            return health.down()
                    .withDetail("dependency", "write-behind")
                    .withDetail("reason", "backlog-hard-limit-reached")
                    .build();
        }
        return health.build();
    }
}
