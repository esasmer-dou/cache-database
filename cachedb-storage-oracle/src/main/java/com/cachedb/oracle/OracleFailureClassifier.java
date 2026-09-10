package com.reactor.cachedb.oracle;

import com.reactor.cachedb.core.queue.StaleWriteRejectedException;
import com.reactor.cachedb.core.queue.WriteFailureCategory;
import com.reactor.cachedb.core.queue.WriteFailureDetails;
import com.reactor.cachedb.jdbc.SqlFailureClassifierSupport;

import java.sql.SQLException;

public final class OracleFailureClassifier {
    public WriteFailureDetails classify(Exception exception) {
        if (SqlFailureClassifierSupport.rootCause(exception) instanceof StaleWriteRejectedException staleWrite) {
            return new WriteFailureDetails(
                    WriteFailureCategory.STALE_WRITE,
                    staleWrite.getSQLState(),
                    staleWrite.getErrorCode(),
                    false,
                    staleWrite.getClass().getName(),
                    SqlFailureClassifierSupport.blankToEmpty(staleWrite.getMessage())
            );
        }
        SQLException sqlException = SqlFailureClassifierSupport.rootSqlException(exception);
        if (sqlException == null) {
            return WriteFailureDetails.unknown(SqlFailureClassifierSupport.rootCause(exception));
        }
        String sqlState = SqlFailureClassifierSupport.blankToEmpty(sqlException.getSQLState());
        int vendorCode = Math.abs(sqlException.getErrorCode());
        WriteFailureCategory category = categoryFor(sqlState, vendorCode);
        return new WriteFailureDetails(
                category,
                sqlState,
                sqlException.getErrorCode(),
                retryable(category),
                sqlException.getClass().getName(),
                SqlFailureClassifierSupport.blankToEmpty(sqlException.getMessage())
        );
    }

    private WriteFailureCategory categoryFor(String sqlState, int vendorCode) {
        WriteFailureCategory byVendorCode = switch (vendorCode) {
            case 1, 1400, 1407, 2291, 2292 -> WriteFailureCategory.CONSTRAINT;
            case 54 -> WriteFailureCategory.LOCK_CONFLICT;
            case 60 -> WriteFailureCategory.DEADLOCK;
            case 1013 -> WriteFailureCategory.TIMEOUT;
            case 8177 -> WriteFailureCategory.SERIALIZATION;
            case 3113, 3114, 3135, 12514, 12516, 12518, 12520, 12537, 12541, 12545, 17002, 17410 ->
                    WriteFailureCategory.AVAILABILITY;
            case 904, 907, 917, 923, 933, 942 -> WriteFailureCategory.SCHEMA;
            case 1031, 1017 -> WriteFailureCategory.PERMISSION;
            case 1438, 1722, 12899, 6502 -> WriteFailureCategory.DATA;
            default -> WriteFailureCategory.UNKNOWN;
        };
        if (byVendorCode != WriteFailureCategory.UNKNOWN) {
            return byVendorCode;
        }
        if (sqlState.startsWith("08")) {
            return WriteFailureCategory.CONNECTION;
        }
        if ("40001".equals(sqlState)) {
            return WriteFailureCategory.SERIALIZATION;
        }
        if ("HYT00".equals(sqlState) || "HYT01".equals(sqlState)) {
            return WriteFailureCategory.TIMEOUT;
        }
        return WriteFailureCategory.UNKNOWN;
    }

    private boolean retryable(WriteFailureCategory category) {
        return switch (category) {
            case CONNECTION, AVAILABILITY, TIMEOUT, SERIALIZATION, DEADLOCK, LOCK_CONFLICT -> true;
            case CONSTRAINT, DATA, SCHEMA, PERMISSION, STALE_WRITE, UNKNOWN -> false;
        };
    }
}
