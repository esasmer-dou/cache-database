package com.reactor.cachedb.jdbc;

/** Parameter-side types for fixed ASCII security metadata only. Never business values or SQL parsing. */
final class InitialSqlTokens {
    private InitialSqlTokens() {}
    static String varchar(JdbcTenantPurgeDialect dialect, int width) {
        if (width != 6 && width != 16 && width != 20 && width != 24 && width != 32
                && width != 36 && width != 40 && width != 64 && width != 96)
            throw new IllegalArgumentException("Invalid metadata token width");
        return dialect == JdbcTenantPurgeDialect.MSSQL ? "CAST(? AS VARCHAR(" + width + "))" : "?";
    }
    static String char64(JdbcTenantPurgeDialect dialect) {
        return dialect == JdbcTenantPurgeDialect.MSSQL ? "CAST(? AS CHAR(64))" : "?";
    }
    static String pair(JdbcTenantPurgeDialect dialect, boolean fixed) {
        String p = fixed ? char64(dialect) : varchar(dialect, 64);
        return "scope_token=" + p + " AND tenant_token=" + p;
    }
}
