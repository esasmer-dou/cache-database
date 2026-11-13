package com.reactor.cachedb.jdbc;

import com.reactor.cachedb.core.protection.SourceOwnerOnboarding.Plan;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.SourcePlan;
import java.util.Collections;
import java.util.Locale;
import java.util.StringJoiner;

/** Identifiers are validated by Plan/SourcePlan. Values are always bound. */
final class JdbcSourceOwnerSql {
    private JdbcSourceOwnerSql() {}
    static String quote(JdbcTenantPurgeDialect dialect, String identifier) {
        var names = new StringJoiner(".");
        for (String part : identifier.split("\\.", -1)) {
            String folded = dialect == JdbcTenantPurgeDialect.POSTGRES ? part.toLowerCase(Locale.ROOT)
                    : dialect == JdbcTenantPurgeDialect.ORACLE ? part.toUpperCase(Locale.ROOT) : part;
            names.add(dialect == JdbcTenantPurgeDialect.MSSQL ? "[" + folded + "]" : "\"" + folded + "\"");
        }
        return names.toString();
    }
    private static String boundedTenant(JdbcTenantPurgeDialect dialect, SourcePlan source) {
        String tenant = quote(dialect, source.tenantColumn());
        // Bound the physical projected field, including NVARCHAR(MAX), before driver buffering.
        String bytes = dialect == JdbcTenantPurgeDialect.POSTGRES ? "octet_length(" + tenant + ")"
                : dialect == JdbcTenantPurgeDialect.MSSQL ? "DATALENGTH(" + tenant + ")" : "LENGTHB(" + tenant + ")";
        int limit = dialect == JdbcTenantPurgeDialect.MSSQL ? 512 : 1024;
        return "CASE WHEN " + bytes + "<=" + limit + " THEN " + tenant + " ELSE NULL END";
    }
    static String read(JdbcTenantPurgeDialect dialect, Plan plan, int ids) {
        if (ids < 1 || ids > 128) throw new IllegalArgumentException("Invalid ownership chunk");
        SourcePlan source = plan.binding().source();
        String sql = "SELECT " + (dialect == JdbcTenantPurgeDialect.MSSQL ? "TOP (129) " : "")
                + quote(dialect, source.idColumn()) + "," + boundedTenant(dialect, source)
                + "," + quote(dialect, plan.versionColumn()) + " FROM " + quote(dialect, source.table())
                + " WHERE " + dialect.tenantPredicate(source) + " AND " + quote(dialect, source.idColumn())
                + " IN (" + String.join(",", Collections.nCopies(ids, "?")) + ")";
        return sql + (dialect == JdbcTenantPurgeDialect.POSTGRES ? " LIMIT 129"
                : dialect == JdbcTenantPurgeDialect.ORACLE ? " FETCH FIRST 129 ROWS ONLY" : "");
    }
}
