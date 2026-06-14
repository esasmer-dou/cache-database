package com.reactor.cachedb.mssql;

import com.reactor.cachedb.core.queue.WriteFailureCategory;
import com.reactor.cachedb.core.queue.WriteFailureDetails;
import com.reactor.cachedb.jdbc.SqlFailureClassifierSupport;

import java.sql.SQLException;

public final class MssqlFailureClassifier {

    public WriteFailureDetails classify(Exception exception) {
        SQLException sqlException = SqlFailureClassifierSupport.rootSqlException(exception);
        if (sqlException == null) {
            return WriteFailureDetails.unknown(SqlFailureClassifierSupport.rootCause(exception));
        }
        return classifySqlException(sqlException);
    }

    private WriteFailureDetails classifySqlException(SQLException exception) {
        String sqlState = SqlFailureClassifierSupport.blankToEmpty(exception.getSQLState());
        int vendorCode = exception.getErrorCode();
        WriteFailureCategory category = categoryFor(sqlState, vendorCode);
        return new WriteFailureDetails(
                category,
                sqlState,
                vendorCode,
                isRetryable(category, vendorCode),
                exception.getClass().getName(),
                SqlFailureClassifierSupport.blankToEmpty(exception.getMessage())
        );
    }

    private WriteFailureCategory categoryFor(String sqlState, int vendorCode) {
        WriteFailureCategory byVendorCode = switch (vendorCode) {
            case -2 -> WriteFailureCategory.TIMEOUT;
            case 1205 -> WriteFailureCategory.DEADLOCK;
            case 1222 -> WriteFailureCategory.LOCK_CONFLICT;
            case 40197, 40501, 40613, 10928, 10929 -> WriteFailureCategory.AVAILABILITY;
            case 2627, 2601, 547 -> WriteFailureCategory.CONSTRAINT;
            case 245, 2628, 8152, 8114 -> WriteFailureCategory.DATA;
            case 102, 156, 207, 208, 2812 -> WriteFailureCategory.SCHEMA;
            case 229, 18456 -> WriteFailureCategory.PERMISSION;
            default -> WriteFailureCategory.UNKNOWN;
        };
        if (byVendorCode != WriteFailureCategory.UNKNOWN) {
            return byVendorCode;
        }
        if (sqlState.startsWith("08")) {
            return WriteFailureCategory.CONNECTION;
        }
        if ("HYT00".equals(sqlState) || "HYT01".equals(sqlState)) {
            return WriteFailureCategory.TIMEOUT;
        }
        if ("40001".equals(sqlState)) {
            return WriteFailureCategory.SERIALIZATION;
        }
        return WriteFailureCategory.UNKNOWN;
    }

    private boolean isRetryable(WriteFailureCategory category, int vendorCode) {
        return switch (category) {
            case CONNECTION, AVAILABILITY, TIMEOUT, SERIALIZATION, DEADLOCK, LOCK_CONFLICT -> true;
            case CONSTRAINT, DATA, SCHEMA, PERMISSION, UNKNOWN -> false;
        };
    }
}
