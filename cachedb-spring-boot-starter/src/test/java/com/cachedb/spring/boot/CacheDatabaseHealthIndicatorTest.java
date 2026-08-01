package com.reactor.cachedb.spring.boot;

import com.reactor.cachedb.core.config.CacheDatabaseConfig;
import com.reactor.cachedb.core.config.RedisGuardrailConfig;
import com.reactor.cachedb.core.queue.WriteBehindWorkerSnapshot;
import com.reactor.cachedb.starter.CacheDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import redis.clients.jedis.JedisPooled;

import javax.sql.DataSource;
import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CacheDatabaseHealthIndicatorTest {

    private final CacheDatabase cacheDatabase = mock(CacheDatabase.class);
    private final JedisPooled jedis = mock(JedisPooled.class);
    private final DataSource dataSource = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);

    @BeforeEach
    void prepareHealthyDependencies() throws Exception {
        when(cacheDatabase.instanceId()).thenReturn("pod-a");
        when(jedis.ping()).thenReturn("PONG");
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(1)).thenReturn(true);
    }

    @Test
    void reportsDownWhenRedisIsUnavailable() {
        when(jedis.ping()).thenThrow(new IllegalStateException("redis unavailable"));

        Health health = indicator().health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("redis", health.getDetails().get("dependency"));
    }

    @Test
    void reportsDownWhenSqlConnectionValidationFails() throws Exception {
        when(connection.isValid(1)).thenReturn(false);

        Health health = indicator().health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("sql", health.getDetails().get("dependency"));
    }

    @Test
    void historicalWorkerEventsRemainVisibleWithoutFailingReadiness() {
        configureWorker(100L, snapshot(7L, 2L, 4L, "TransientSqlException"));

        Health health = indicator().health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("ATTENTION", health.getDetails().get("writeBehindStatus"));
        assertEquals("TransientSqlException", health.getDetails().get("lastWriteBehindError"));
    }

    @Test
    void reportsDownWhenCurrentBacklogReachesConfiguredHardLimit() {
        configureWorker(100L, snapshot(100L, 0L, 0L, null));

        Health health = indicator().health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("write-behind", health.getDetails().get("dependency"));
        assertEquals("SATURATED", health.getDetails().get("writeBehindStatus"));
    }

    private CacheDatabaseHealthIndicator indicator() {
        return new CacheDatabaseHealthIndicator(cacheDatabase, jedis, dataSource);
    }

    private void configureWorker(long hardLimit, WriteBehindWorkerSnapshot snapshot) {
        when(cacheDatabase.config()).thenReturn(CacheDatabaseConfig.builder()
                .redisGuardrail(RedisGuardrailConfig.builder()
                        .writeBehindBacklogHardLimit(hardLimit)
                        .build())
                .build());
        when(cacheDatabase.workerSnapshot()).thenReturn(snapshot);
    }

    private WriteBehindWorkerSnapshot snapshot(
            long backlog,
            long deadLetters,
            long pendingRecovery,
            String lastErrorType
    ) {
        return new WriteBehindWorkerSnapshot(
                25L,
                3L,
                25L,
                backlog,
                32,
                0L,
                0,
                0L,
                1L,
                deadLetters,
                1L,
                pendingRecovery,
                1L,
                lastErrorType,
                lastErrorType == null ? null : "temporary failure",
                lastErrorType,
                lastErrorType == null ? null : "temporary failure",
                lastErrorType == null ? null : "worker",
                null
        );
    }
}
