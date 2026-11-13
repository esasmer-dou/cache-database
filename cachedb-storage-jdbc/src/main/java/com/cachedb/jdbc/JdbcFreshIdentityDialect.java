package com.reactor.cachedb.jdbc;

/** Provider-explicit SQL for retained namespace metadata, never tenant business data. */
final class JdbcFreshIdentityDialect {
    private JdbcFreshIdentityDialect() {}

    static String lock(JdbcTenantPurgeDialect dialect) {
        return "SELECT binding_token,authority_id,domain_first,domain_last,exclusive_token,highwater,"
                + "reservation_epoch,last_request,last_tenant,last_generation,last_control,last_first,last_count"
                + " FROM cachedb_identity_allocator"
                + (dialect == JdbcTenantPurgeDialect.MSSQL ? " WITH (UPDLOCK,HOLDLOCK,ROWLOCK)" : "")
                + " WHERE scope_token=" + InitialSqlTokens.char64(dialect)
                + (dialect == JdbcTenantPurgeDialect.MSSQL ? ""
                    : dialect == JdbcTenantPurgeDialect.ORACLE ? " FOR UPDATE WAIT 5" : " FOR UPDATE");
    }

    static String provision(JdbcTenantPurgeDialect dialect) {
        String insert = "INSERT INTO cachedb_identity_allocator"
                + " (scope_token,binding_token,authority_id,domain_first,domain_last,exclusive_token,"
                + "highwater,reservation_epoch)";
        return switch (dialect) {
            case POSTGRES -> insert + " VALUES (?,?,?,?,?,?,?,0) ON CONFLICT (scope_token) DO NOTHING";
            case MSSQL -> insert + " SELECT " + InitialSqlTokens.char64(dialect) + ","
                    + InitialSqlTokens.char64(dialect) + "," + InitialSqlTokens.varchar(dialect, 36)
                    + ",?,?," + InitialSqlTokens.char64(dialect)
                    + ",?,0 WHERE NOT EXISTS (SELECT 1 FROM cachedb_identity_allocator"
                    + " WITH (UPDLOCK,HOLDLOCK) WHERE scope_token=" + InitialSqlTokens.char64(dialect) + ")";
            case ORACLE -> "BEGIN " + insert
                    + " VALUES (?,?,?,?,?,?,?,0); EXCEPTION WHEN DUP_VAL_ON_INDEX THEN NULL; END;";
        };
    }

    static String update(JdbcTenantPurgeDialect dialect) {
        return "UPDATE cachedb_identity_allocator SET highwater=?,reservation_epoch=?,last_request="
                + InitialSqlTokens.varchar(dialect, 36) + ",last_tenant=" + InitialSqlTokens.char64(dialect)
                + ",last_generation=?,last_control=?,last_first=?,last_count=? WHERE scope_token="
                + InitialSqlTokens.char64(dialect) + " AND reservation_epoch=?";
    }
}
