package com.reactor.cachedb.jdbc;

import java.sql.SQLException;

public final class SqlFailureClassifierSupport {

    private SqlFailureClassifierSupport() {
    }

    public static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    public static SQLException rootSqlException(Exception exception) {
        Throwable root = rootCause(exception);
        return root instanceof SQLException sqlException ? sqlException : null;
    }

    public static String blankToEmpty(String value) {
        return value == null ? "" : value;
    }
}
