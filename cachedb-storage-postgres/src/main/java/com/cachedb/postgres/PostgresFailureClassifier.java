package com.reactor.cachedb.postgres;

import com.reactor.cachedb.core.queue.WriteFailureCategory;
import com.reactor.cachedb.core.queue.WriteFailureDetails;

import java.sql.SQLException;

public final class PostgresFailureClassifier {

    public WriteFailureDetails classify(Exception exception) {
        Throwable root = rootCause(exception);
        if (root instanceof SQLException sqlException) {
            return classifySqlException(sqlException);
        }
        return WriteFailureDetails.unknown(root);
    }

    private WriteFailureDetails classifySqlException(SQLException exception) {
        String sqlState = blankToEmpty(exception.getSQLState());
        int vendorCode = exception.getErrorCode();
        WriteFailureCategory category = categoryFor(sqlState);
        boolean retryable = isRetryable(category, sqlState);
        return new WriteFailureDetails(
                category,
                sqlState,
                vendorCode,
                retryable,
                exception.getClass().getName(),
                blankToEmpty(exception.getMessage())
        );
    }

    private WriteFailureCategory categoryFor(String sqlState) {
        if (sqlState.startsWith("08")) {
            return WriteFailureCategory.CONNECTION;
        }
        if (sqlState.startsWith("53") || sqlState.startsWith("57")) {
            return WriteFailureCategory.AVAILABILITY;
        }
        if ("57014".equals(sqlState)) {
            return WriteFailureCategory.TIMEOUT;
        }
        if ("40001".equals(sqlState)) {
            return WriteFailureCategory.SERIALIZATION;
        }
        if ("40P01".equals(sqlState)) {
            return WriteFailureCategory.DEADLOCK;
        }
        if ("55P03".equals(sqlState)) {
            return WriteFailureCategory.LOCK_CONFLICT;
        }
        if (sqlState.startsWith("23")) {
            return WriteFailureCategory.CONSTRAINT;
        }
        if (sqlState.startsWith("22")) {
            return WriteFailureCategory.DATA;
        }
        if ("42501".equals(sqlState)) {
            return WriteFailureCategory.PERMISSION;
        }
        if (sqlState.startsWith("42")) {
            return WriteFailureCategory.SCHEMA;
        }
        return WriteFailureCategory.UNKNOWN;
    }

    private boolean isRetryable(WriteFailureCategory category, String sqlState) {
        return switch (category) {
            case CONNECTION, AVAILABILITY, TIMEOUT, SERIALIZATION, DEADLOCK, LOCK_CONFLICT -> true;
            case CONSTRAINT, DATA, SCHEMA, PERMISSION, UNKNOWN -> false;
        };
    }

    private Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private String blankToEmpty(String value) {
        return value == null ? "" : value;
    }
}
