package com.reactor.cachedb.mssql;

import com.reactor.cachedb.core.queue.WriteFailureCategory;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MssqlFailureClassifierTest {

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
}
