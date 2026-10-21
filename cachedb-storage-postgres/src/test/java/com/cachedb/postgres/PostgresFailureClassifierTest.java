package com.reactor.cachedb.postgres;

import static org.junit.jupiter.api.Assertions.*;

import com.reactor.cachedb.core.queue.WriteFailureCategory;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;

class PostgresFailureClassifierTest {
    @Test
    void writeFenceSchemaFailureKeepsDeadLetterTaxonomy() {
        var details =
                new PostgresFailureClassifier()
                        .classify(new SQLException("Missing write fence", "CDB03"));
        assertEquals(WriteFailureCategory.SCHEMA, details.category());
        assertFalse(details.retryable());
    }
}
