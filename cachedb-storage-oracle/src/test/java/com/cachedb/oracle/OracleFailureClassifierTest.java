package com.reactor.cachedb.oracle;

import com.reactor.cachedb.core.queue.WriteFailureCategory;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OracleFailureClassifierTest {
    private final OracleFailureClassifier classifier = new OracleFailureClassifier();

    @Test
    void shouldClassifyDeadlockAndSerializationAsRetryable() {
        var deadlock = classifier.classify(new SQLException("deadlock", "61000", 60));
        var serialization = classifier.classify(new SQLException("cannot serialize", "72000", 8177));

        assertEquals(WriteFailureCategory.DEADLOCK, deadlock.category());
        assertEquals(WriteFailureCategory.SERIALIZATION, serialization.category());
        assertTrue(deadlock.retryable());
        assertTrue(serialization.retryable());
    }

    @Test
    void shouldClassifyConstraintAndSchemaAsNonRetryable() {
        var duplicate = classifier.classify(new SQLException("unique", "23000", 1));
        var tableMissing = classifier.classify(new SQLException("missing", "42000", 942));

        assertEquals(WriteFailureCategory.CONSTRAINT, duplicate.category());
        assertEquals(WriteFailureCategory.SCHEMA, tableMissing.category());
        assertFalse(duplicate.retryable());
        assertFalse(tableMissing.retryable());
    }

    @Test
    void shouldClassifyListenerAndSocketFailuresAsAvailability() {
        var listener = classifier.classify(new SQLException("listener", "08006", 12514));

        assertEquals(WriteFailureCategory.AVAILABILITY, listener.category());
        assertTrue(listener.retryable());
    }
}
