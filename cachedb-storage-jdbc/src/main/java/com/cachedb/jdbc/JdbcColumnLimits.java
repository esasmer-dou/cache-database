package com.reactor.cachedb.jdbc;

import com.reactor.cachedb.core.codec.LengthPrefixedPayloadCodec;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;

/** JDBC capability resolution only: never scans tables or issues catalog SQL. */
public final class JdbcColumnLimits {
    private JdbcColumnLimits() {}

    public static int resolve(DatabaseMetaData metaData, int fallback) throws SQLException {
        if (fallback < 1 || fallback > LengthPrefixedPayloadCodec.MAX_FIELDS) {
            throw new IllegalArgumentException("Invalid default table column limit: " + fallback);
        }
        if (metaData == null) return fallback;
        int reported;
        try {
            reported = metaData.getMaxColumnsInTable();
        } catch (SQLFeatureNotSupportedException unsupported) {
            return fallback;
        }
        return reported > 0 ? Math.min(reported, LengthPrefixedPayloadCodec.MAX_FIELDS) : fallback;
    }

    public static void validate(int count, int limit, String dialect) {
        int effective = Math.min(limit, LengthPrefixedPayloadCodec.MAX_FIELDS);
        if (effective < 1 || count < 0 || count > effective) {
            throw new IllegalArgumentException("Table column count " + count
                    + " exceeds database dictionary limit " + effective + " for dialect " + dialect);
        }
    }
}
