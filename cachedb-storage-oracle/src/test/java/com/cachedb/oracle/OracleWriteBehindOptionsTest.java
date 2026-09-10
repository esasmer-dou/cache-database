package com.reactor.cachedb.oracle;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OracleWriteBehindOptionsTest {
    @Test
    void shouldApplyProviderOptions() {
        OracleStorageProvider provider = new OracleStorageProvider();

        provider.writeBehindFlusherFactory(Map.of(
                OracleStorageProvider.QUERY_TIMEOUT_SECONDS, "15",
                OracleStorageProvider.TRANSACTION_ISOLATION, String.valueOf(Connection.TRANSACTION_SERIALIZABLE),
                OracleStorageProvider.DUPLICATE_RACE_RETRIES, "4",
                OracleStorageProvider.EMPTY_STRING_POLICY, "NORMALIZE_TO_NULL"
        ));

        assertEquals("oracle", provider.id());
        assertEquals("oracle", provider.queryDialect().name());
    }

    @Test
    void shouldRejectUnsafeOptions() {
        assertThrows(IllegalArgumentException.class, () -> OracleWriteBehindOptions.builder()
                .transactionIsolation(Connection.TRANSACTION_REPEATABLE_READ)
                .build());
        assertThrows(IllegalArgumentException.class, () -> OracleWriteBehindOptions.builder()
                .duplicateRaceRetries(11)
                .build());
        assertThrows(IllegalArgumentException.class, () -> new OracleStorageProvider()
                .writeBehindFlusherFactory(Map.of("unknown", "1")));
    }
}
