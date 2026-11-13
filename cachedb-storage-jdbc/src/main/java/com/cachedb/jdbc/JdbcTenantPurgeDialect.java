package com.reactor.cachedb.jdbc;

import com.reactor.cachedb.core.protection.TenantPurgeProtocol.SourcePlan;

/** SQL is provider-explicit. All runtime user data is bound, never interpolated. DDL is migration-only. */
public enum JdbcTenantPurgeDialect {
    POSTGRES, MSSQL, ORACLE;

    public String now() {
        return switch (this) {
            case POSTGRES -> "clock_timestamp()";
            case MSSQL -> "SYSUTCDATETIME()";
            case ORACLE -> "SYSTIMESTAMP";
        };
    }
    public String expires(int seconds) {
        if (seconds < 15 || seconds > 120) throw new IllegalArgumentException("Invalid lease duration");
        return switch (this) {
            case POSTGRES -> "clock_timestamp() + INTERVAL '" + seconds + " seconds'";
            case MSSQL -> "DATEADD(second," + seconds + ",SYSUTCDATETIME())";
            case ORACLE -> "SYSTIMESTAMP + NUMTODSINTERVAL(" + seconds + ",'SECOND')";
        };
    }
    public String lockSql() {
        return "SELECT generation,gate_state,operation_id,lease_epoch,lease_owner,"
                + "CASE WHEN lease_until > " + now() + " THEN 1 ELSE 0 END AS lease_live"
                + " FROM cachedb_tenant_guard" + (this == MSSQL ? " WITH (UPDLOCK,HOLDLOCK,ROWLOCK)" : "")
                + " WHERE scope_token=? AND tenant_token=?"
                + (this == MSSQL ? "" : this == ORACLE ? " FOR UPDATE WAIT 5" : " FOR UPDATE");
    }
    public String provisionSql() {
        String columns = " (scope_token,tenant_token,generation,gate_state,lease_epoch)";
        return switch (this) {
            case POSTGRES -> "INSERT INTO cachedb_tenant_guard" + columns
                    + " VALUES (?,?,?,'CLOSED',0) ON CONFLICT (scope_token,tenant_token) DO NOTHING";
            case MSSQL -> "INSERT INTO cachedb_tenant_guard" + columns
                    + " SELECT ?,?,?,'CLOSED',0 WHERE NOT EXISTS (SELECT 1 FROM cachedb_tenant_guard"
                    + " WITH (UPDLOCK,HOLDLOCK) WHERE scope_token=? AND tenant_token=?)";
            case ORACLE -> "BEGIN INSERT INTO cachedb_tenant_guard" + columns
                    + " VALUES (?,?,?,'CLOSED',0); EXCEPTION WHEN DUP_VAL_ON_INDEX THEN NULL; END;";
        };
    }
    private String quote(String identifier) {
        String[] parts = identifier.split("\\.", -1);
        var joined = new java.util.StringJoiner(".");
        for (String part : parts) {
            String folded = this == ORACLE ? part.toUpperCase(java.util.Locale.ROOT)
                    : this == POSTGRES ? part.toLowerCase(java.util.Locale.ROOT) : part;
            joined.add(this == MSSQL ? "[" + folded + "]" : "\"" + folded + "\"");
        }
        return joined.toString();
    }
    /** Indexed logical predicate PLUS exact bytes: case/padding-insensitive collations cannot erase a peer tenant. */
    public String tenantPredicate(SourcePlan plan) {
        String tenant = quote(plan.tenantColumn());
        return switch (this) {
            case POSTGRES -> tenant + "=? AND " + tenant + " COLLATE \"C\"=CAST(? AS TEXT) COLLATE \"C\"";
            case MSSQL -> tenant + "=? AND CONVERT(VARBINARY(MAX)," + tenant + ")"
                    + "=CONVERT(VARBINARY(MAX),CAST(? AS NVARCHAR(256)))"
                    + " AND DATALENGTH(" + tenant + ")=DATALENGTH(CAST(? AS NVARCHAR(256)))";
            case ORACLE -> tenant + "=? AND UTL_RAW.CAST_TO_RAW(" + tenant + ")=UTL_RAW.CAST_TO_RAW(?)";
        };
    }
    public int tenantParameterCount() { return this == MSSQL ? 3 : 2; }
    public void bindTenant(java.sql.PreparedStatement statement, String tenant, int predicates) throws java.sql.SQLException {
        if (predicates < 1 || predicates > 2) throw new IllegalArgumentException("Invalid tenant predicate count");
        for (int i = 1; i <= tenantParameterCount() * predicates; i++) {
            if (this == MSSQL) statement.setNString(i, tenant); else statement.setString(i, tenant);
        }
    }
    /** Existence only, uses the reviewed leading tenant index plus exact-byte predicate. */
    public String sourceProbeSql(SourcePlan plan) {
        String sql="SELECT "+(this==MSSQL?"TOP (1) ":"")+"1 FROM "+quote(plan.table())+" WHERE "+tenantPredicate(plan);
        return sql+(this==POSTGRES?" LIMIT 1":this==ORACLE?" FETCH FIRST 1 ROWS ONLY":"");
    }
    /** Constant limit is validated 1..128; guard row is locked in the same transaction first. */
    public String deleteSourceSql(SourcePlan plan, int limit) {
        if (limit < 1 || limit > 128) throw new IllegalArgumentException("Invalid source chunk");
        String table = quote(plan.table()), id = quote(plan.idColumn()), where = tenantPredicate(plan);
        return switch (this) {
            case POSTGRES -> "DELETE FROM " + table + " WHERE " + where + " AND (tableoid,ctid)"
                    + " IN (SELECT tableoid,ctid FROM " + table + " WHERE " + where + " ORDER BY " + id
                    + " LIMIT " + limit + " FOR UPDATE)";
            case MSSQL -> "DELETE TOP (" + limit + ") FROM " + table + " WITH (ROWLOCK) WHERE " + where;
            case ORACLE -> "DELETE FROM " + table + " WHERE " + where + " AND ROWID IN"
                    + " (SELECT ROWID FROM " + table + " WHERE " + where + " AND ROWNUM<=" + limit + ")";
        };
    }
}
