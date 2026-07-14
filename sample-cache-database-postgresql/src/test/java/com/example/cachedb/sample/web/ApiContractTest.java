package com.example.cachedb.sample.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApiContractTest {

    @Test
    void routeLimitRejectsOversizedRequestsInsteadOfSilentlyClamping() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> ApiLimits.requireInRange("limit", 1_001, 1, 1_000)
        );

        assertEquals("limit must be between 1 and 1000; received 1001", exception.getMessage());
    }

    @Test
    void writeResponseDoesNotClaimSqlDurability() {
        WriteAccepted<String> response = WriteAccepted.of("CREATE", "CustomerEntity", 10L, "entity");

        assertEquals("WRITE_BEHIND_ACCEPTED", response.status());
        assertEquals("CREATE", response.operation());
        assertEquals(10L, response.entityId());
    }
}
