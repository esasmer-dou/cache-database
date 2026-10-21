package com.reactor.cachedb.mssql;

import com.reactor.cachedb.core.queue.WriteFailureCategory;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MssqlFailureClassifierTest {
    @org.junit.jupiter.api.Test
    void writeFenceSchemaFailureKeepsDeadLetterTaxonomy() {
        var details = new MssqlFailureClassifier().classify(new java.sql.SQLException("Missing write fence", "CDB03"));
        org.junit.jupiter.api.Assertions.assertEquals(com.reactor.cachedb.core.queue.WriteFailureCategory.SCHEMA, details.category());
        org.junit.jupiter.api.Assertions.assertFalse(details.retryable());
    }

    private final MssqlFailureClassifier classifier = new MssqlFailureClassifier();

    @Test
    void shouldClassifyDeadlockAsRetryable() {
        var details = classifier.classify(new SQLException("deadlock victim", "40001", 1205));

        assertEquals(WriteFailureCategory.DEADLOCK, details.category());
        assertTrue(details.retryable());
    }

    @Test
    void shouldClassifyDuplicateKeyAsNonRetryableConstraint() {
        var details = classifier.classify(new SQLException("duplicate key", "23000", 2627));

        assertEquals(WriteFailureCategory.CONSTRAINT, details.category());
        assertFalse(details.retryable());
    }

    @Test
    void shouldClassifyAzureThrottlingAsAvailability() {
        var details = classifier.classify(new SQLException("service busy", "S0001", 40501));

        assertEquals(WriteFailureCategory.AVAILABILITY, details.category());
        assertTrue(details.retryable());
    }

    @Test
    void shouldClassifySqlServerQueryTimeoutAsRetryableTimeout() {
        var details = classifier.classify(new SQLException("query timeout", "HYT00", -2));

        assertEquals(WriteFailureCategory.TIMEOUT, details.category());
        assertTrue(details.retryable());
    }

    @Test
    void shouldClassifyLockConflictAsRetryable() {
        var details = classifier.classify(new SQLException("lock request timeout", "S0001", 1222));

        assertEquals(WriteFailureCategory.LOCK_CONFLICT, details.category());
        assertTrue(details.retryable());
    }
}
