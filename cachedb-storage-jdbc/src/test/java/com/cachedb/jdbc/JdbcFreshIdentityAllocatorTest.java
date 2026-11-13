package com.reactor.cachedb.jdbc;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.FreshIdentityRange.Request;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class JdbcFreshIdentityAllocatorTest {
    @Test void allEntryPointsRequireAdminAndTimeoutsAreBoundedBeforeSql() {
        // No URL: any unexpected connection attempt fails this boundary test.
        var source = new JdbcDataSource();
        var guard = new JdbcTenantPurgeGuard(source, JdbcTenantPurgeDialect.POSTGRES, 1, 1);
        assertThrows(ProtectionException.class, () -> new JdbcFreshIdentityAllocator(guard, JdbcTenantPurgeDialect.POSTGRES, 0));
        assertThrows(ProtectionException.class, () -> new JdbcFreshIdentityAllocator(guard, JdbcTenantPurgeDialect.POSTGRES, 6));
        assertThrows(ProtectionException.class, () -> new JdbcFreshIdentityAllocator(guard, JdbcTenantPurgeDialect.ORACLE, 1));
        var allocator = new JdbcFreshIdentityAllocator(guard, JdbcTenantPurgeDialect.POSTGRES, 5);
        var key = ProtectionKey.of("fresh", "a");
        var domain = new FreshIdentityDomain(key.scopeToken(), ProtectionKey.hash("binding"), UUID.randomUUID(),
                100, 199, ProtectionKey.hash("exclusive-pre-reservation"));
        var generation = new Generation(key, 1);
        var lease = new Lease(key, UUID.randomUUID(), UUID.randomUUID(), 1, 30);
        var request = new Request(domain, generation, 16, UUID.randomUUID(), 0);
        var initialRequest = new InitialTenantBootstrap.Request(generation, "a", lease.operationId(),
                ProtectionKey.hash("operator"), 30);
        var initialSession = new InitialTenantBootstrap.Session(initialRequest, lease,
                ProtectionKey.hash("plan"), ProtectionKey.hash("manifest"));
        assertAdmin(() -> allocator.provisionClosed(lease, 1, domain));
        assertAdmin(() -> allocator.provisionInitial(initialSession, domain));
        assertAdmin(() -> allocator.statusClosed(lease, 1, domain));
        assertAdmin(() -> allocator.statusOpen(generation, domain));
        assertAdmin(() -> allocator.reserveClosed(lease, request));
        assertAdmin(() -> allocator.reserveOpen(request));
        assertAdmin(() -> allocator.reserveOnceOpen(request));
    }

    @Test void everyDialectLocksNamespaceGloballyAndMigrationsRetainOnlyBoundedMetadata() throws Exception {
        for (var dialect : JdbcTenantPurgeDialect.values()) {
            String lock = JdbcFreshIdentityDialect.lock(dialect);
            assertTrue(lock.contains("FOR UPDATE") || lock.contains("UPDLOCK,HOLDLOCK"));
            assertFalse(lock.contains("AND tenant_token="));
            assertFalse(lock.contains("cachedb_tenant_allocator"));
            assertTrue(JdbcFreshIdentityDialect.update(dialect).endsWith("AND reservation_epoch=?"));
            String path = "/com/reactor/cachedb/jdbc/migrations/" + dialect.name().toLowerCase(Locale.ROOT)
                    + "/V019_7__fresh_identity_allocator.sql";
            try (var in = getClass().getResourceAsStream(path)) {
                assertNotNull(in);
                byte[] bytes = in.readNBytes(8193);
                assertTrue(bytes.length <= 8192);
                String ddl = new String(bytes, StandardCharsets.UTF_8);
                assertTrue(ddl.contains("PRIMARY KEY (scope_token)"));
                assertTrue(ddl.contains("CREATE INDEX ix_cdb_namespace_scope ON cachedb_namespace_bootstrap (scope_token)"));
                assertTrue(ddl.contains("9007199254740991"));
                assertTrue(ddl.contains("last_count BETWEEN 1 AND 1024"));
                assertTrue(ddl.contains("last_control IS NOT NULL"));
                assertFalse(ddl.contains("FOREIGN KEY"));
                assertFalse(ddl.contains("tenant_token"));
                assertFalse(ddl.matches("(?is).*\\n\\s*(DELETE|TRUNCATE|DROP|ALTER)\\s+.*"));
            }
        }
    }

    @Test void retainedAllocatorCannotBeDeclaredAsPurgeBusinessSource() {
        assertThrows(ProtectionException.class, () -> new SourcePlan("cachedb_identity_allocator", "last_tenant", "highwater"));
        assertThrows(ProtectionException.class, () -> new SourcePlan("public.CACHEDB_IDENTITY_ALLOCATOR", "last_tenant", "highwater"));
    }

    private static void assertAdmin(org.junit.jupiter.api.function.Executable work) {
        assertEquals(ProtectionException.Code.ADMIN_REQUIRED, assertThrows(ProtectionException.class, work).code());
    }
}
