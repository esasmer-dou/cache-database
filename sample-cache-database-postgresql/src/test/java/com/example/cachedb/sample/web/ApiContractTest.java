package com.example.cachedb.sample.web;

import com.reactor.cachedb.core.model.OperationType;
import com.reactor.cachedb.core.model.WriteReceipt;
import com.reactor.cachedb.core.repository.WindowRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApiContractTest {

    @Test
    void expiredSnapshotCursorIsAnInputErrorButOtherRedisFailuresAreNotHidden() {
        var handler = new ApiExceptionHandler();
        var stale = new redis.clients.jedis.exceptions.JedisDataException("Snapshot cursor expired; restart pagination");
        assertEquals(400, handler.expiredSnapshotCursor(stale).getStatusCode().value());
        var corruption = new redis.clients.jedis.exceptions.JedisDataException("Invalid stored snapshot payload");
        assertThrows(redis.clients.jedis.exceptions.JedisDataException.class,
                () -> handler.expiredSnapshotCursor(corruption));
    }

    @Test
    void cursorWindowRejectsOversizedRequestsInsteadOfSilentlyClamping() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> WindowRequest.first(1_001)
        );

        assertEquals("limit must be between 1 and 1000", exception.getMessage());
    }

    @Test
    void writeResponseDoesNotClaimSqlDurability() {
        WriteAccepted<String> response = WriteAccepted.from(
                "CREATE",
                "CustomerEntity",
                new WriteReceipt<>("entity", 10L, "customers", OperationType.UPSERT, 7L, Instant.EPOCH)
        );

        assertEquals("WRITE_BEHIND_ACCEPTED", response.status());
        assertEquals("CREATE", response.operation());
        assertEquals(10L, response.entityId());
        assertEquals(7L, response.version());
    }
}
