package com.reactor.cachedb.jdbc;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.*;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class JdbcTenantPurgeDialectTest {
    @Test void everyProviderUsesLockedGuardAndBoundedTenantPredicatedDelete() {
        var source = new SourcePlan("orders", "tenant_id", "id");
        for (var dialect : JdbcTenantPurgeDialect.values()) {
            String sql = dialect.deleteSourceSql(source, 128);
            assertTrue(sql.contains("tenant_id") || sql.contains("TENANT_ID"));
            assertTrue(sql.contains("128")); assertTrue(sql.contains("?"));
            assertFalse(sql.contains("cachedb_write_fence"));
            assertFalse(sql.contains("cachedb_tenant_guard"));
            assertTrue(dialect.lockSql().contains("FOR UPDATE") || dialect.lockSql().contains("UPDLOCK,HOLDLOCK"));
            assertThrows(IllegalArgumentException.class, () -> dialect.deleteSourceSql(source, 129));
            assertThrows(IllegalArgumentException.class, () -> dialect.expires(121));
        }
        assertTrue(JdbcTenantPurgeDialect.ORACLE.deleteSourceSql(source, 128).contains("ROWNUM<=128"));
        assertTrue(JdbcTenantPurgeDialect.MSSQL.deleteSourceSql(source, 128).startsWith("DELETE TOP (128)"));
        assertTrue(JdbcTenantPurgeDialect.POSTGRES.deleteSourceSql(source, 128).contains("LIMIT 128 FOR UPDATE"));
        assertTrue(JdbcTenantPurgeDialect.POSTGRES.deleteSourceSql(source, 128).contains("(tableoid,ctid)"));
    }
    @Test void packagedMigrationsPreserveGuardAndDurableBoundedDiscoveryWithoutPerWriteLedger() throws Exception {
        for (var dialect : JdbcTenantPurgeDialect.values()) {
            String path = "/com/reactor/cachedb/jdbc/migrations/" + dialect.name().toLowerCase(java.util.Locale.ROOT)
                    + "/V019__tenant_protection.sql";
            try (var input = getClass().getResourceAsStream(path)) {
                assertNotNull(input);
                String ddl = new String(input.readNBytes(65537), StandardCharsets.UTF_8);
                assertTrue(ddl.length() <= 65536);
                assertTrue(ddl.contains("CREATE TABLE cachedb_purge_scan"));
                assertTrue(ddl.contains("CREATE TABLE cachedb_purge_work_budget"));
                assertTrue(ddl.contains("CREATE TABLE cachedb_purge_work"));
                assertFalse(ddl.contains("CREATE TABLE cachedb_tenant_row_fence"));
                assertTrue(ddl.contains("CREATE TABLE cachedb_tenant_guard"));
                assertTrue(ddl.contains("CREATE TABLE cachedb_tenant_allocator"));
                assertTrue(ddl.contains("9007199254740991"));assertTrue(ddl.contains("inventory_total"));
                assertTrue(ddl.contains("CREATE TABLE cachedb_purge_progress"));
                assertFalse(ddl.matches("(?s).*\\n\\s*(DELETE|TRUNCATE|DROP)\\s+.*"));
            }
        }
    }
    @Test void incrementalScanKindMigrationIncludesEveryNativeKindAndRetainsAllOtherConstraints() throws Exception {
        for(var dialect:JdbcTenantPurgeDialect.values()){
            String path="/com/reactor/cachedb/jdbc/migrations/"+dialect.name().toLowerCase(java.util.Locale.ROOT)
                    +"/V019_2__purge_list_scan_kind.sql";
            try(var input=getClass().getResourceAsStream(path)){
                assertNotNull(input);String ddl=new String(input.readNBytes(8193),StandardCharsets.UTF_8);
                assertTrue(ddl.length()<=8192);
                assertTrue(ddl.contains("ADD CONSTRAINT ck_scan_kind_v019_2"));
                assertTrue(ddl.contains("DROP CONSTRAINT ck_scan_kind;"));
                assertTrue(ddl.indexOf("ADD CONSTRAINT")<ddl.indexOf("DROP CONSTRAINT"));
                for(var kind:JdbcPurgeScanJournal.Kind.values())assertTrue(ddl.contains("'"+kind.name()+"'"));
                assertFalse(ddl.contains("cachedb_tenant_guard"));assertFalse(ddl.contains("cachedb_tenant_allocator"));
                assertFalse(ddl.contains("cachedb_write_fence"));assertFalse(ddl.contains("DROP TABLE"));
            }
        }
    }
    @Test void emptyActivationIsAdditiveRetainedPairIndexedMetadataOnEveryProvider() throws Exception {
        for (var dialect : JdbcTenantPurgeDialect.values()) {
            String path = "/com/reactor/cachedb/jdbc/migrations/" + dialect.name().toLowerCase(java.util.Locale.ROOT)
                    + "/V019_6__empty_tenant_activation.sql";
            try (var input = getClass().getResourceAsStream(path)) {
                assertNotNull(input); String ddl = new String(input.readNBytes(8193), StandardCharsets.UTF_8);
                assertTrue(ddl.length() <= 8192); assertTrue(ddl.contains("CREATE TABLE cachedb_empty_activation"));
                assertTrue(ddl.contains("PRIMARY KEY (scope_token, tenant_token, operation_id)"));
                assertTrue(ddl.contains("UNIQUE (scope_token, tenant_token, activation_id)"));
                assertFalse(ddl.matches("(?is).*\\n\\s*(DELETE|TRUNCATE|DROP|ALTER)\\s+.*"));
            }
        }
    }
    @Test void autocommitBusinessGuardIsRejectedBeforeAnyDialectOrDatabaseQuery() throws Exception {
        var source = new JdbcDataSource(); source.setURL("jdbc:h2:mem:purge_guard_contract");
        var guard = new JdbcTenantPurgeGuard(source, JdbcTenantPurgeDialect.POSTGRES, 1, 1);
        try (var connection = source.getConnection()) {
            var failure = assertThrows(SQLException.class, () -> guard.authorizeWrite(connection,
                    new Generation(ProtectionKey.of("orders", "a"), 1)));
            assertEquals("CDBGP", failure.getSQLState());
        }
        assertThrows(ProtectionException.class, () -> new JdbcTenantPurgeGuard(source, JdbcTenantPurgeDialect.POSTGRES, 0, 1));
    }
}
