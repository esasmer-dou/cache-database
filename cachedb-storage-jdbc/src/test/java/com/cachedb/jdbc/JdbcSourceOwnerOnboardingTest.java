package com.reactor.cachedb.jdbc;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.SourceOwnerOnboarding.*;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.Binding;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.Generation;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.Lease;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.SourcePlan;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class JdbcSourceOwnerOnboardingTest {
    static Plan plan() {
        var binding = new Binding(String.class, "orders", new SourcePlan("orders", "tenant_id", "id"),
                ProtectionKey.hash("layout"), 1000, 128, 131072, true);
        return new Plan(binding, "entity_version", JdbcSourceOwnerOnboarding.stringIds().formatToken());
    }
    @Test void readerReusesExactProviderPredicateAndPhysical129RowOverflowProbe() {
        var plan = plan();
        for (var dialect : JdbcTenantPurgeDialect.values()) {
            String sql = JdbcSourceOwnerSql.read(dialect, plan, 128);
            assertTrue(sql.contains(dialect.tenantPredicate(plan.binding().source())));
            assertTrue(sql.contains("129")); assertTrue(sql.contains("entity_version") || sql.contains("ENTITY_VERSION"));
            assertTrue(sql.contains("tenant_id") || sql.contains("TENANT_ID"));
            assertTrue(sql.startsWith("SELECT ")); assertFalse(sql.contains("DELETE")); assertFalse(sql.contains("UPDATE"));
            long parameters = sql.chars().filter(c -> c == '?').count();
            assertEquals(128 + dialect.tenantParameterCount(), parameters);
            assertThrows(IllegalArgumentException.class, () -> JdbcSourceOwnerSql.read(dialect, plan, 129));
        }
        String mssql = JdbcSourceOwnerSql.read(JdbcTenantPurgeDialect.MSSQL, plan, 2);
        assertTrue(mssql.contains("CONVERT(VARBINARY(MAX),[tenant_id])"));
        assertTrue(mssql.contains("DATALENGTH")); assertTrue(mssql.contains("NVARCHAR(256)"));
    }
    @Test void returnedTenantMustMatchJavaExactlyRegardlessOfDatabaseCollation() {
        assertTrue(JdbcSourceOwnerOnboarding.exactTenantAndId("a", "a", "1", "1"));
        assertFalse(JdbcSourceOwnerOnboarding.exactTenantAndId("a", "A", "1", "1"));
        assertFalse(JdbcSourceOwnerOnboarding.exactTenantAndId("a", "a ", "1", "1"));
        assertFalse(JdbcSourceOwnerOnboarding.exactTenantAndId("a", null, "1", "1"));
        assertFalse(JdbcSourceOwnerOnboarding.exactTenantAndId("a", "a", "id", "ID"));
    }
    @Test void codecBoundsPayloadAndRestoresPendingBeforeInstallation() {
        var plan = plan();
        var request = new Request(plan, new Generation(plan.binding().key("a"), 1), "a", UUID.randomUUID(),
                ProtectionKey.hash("operator"), 30);
        var pending = Checkpoint.initial(request).fenced(ProtectionKey.hash("sealed"))
                .staged(new Page(UUID.randomUUID(), ProtectionKey.hash("next"), false, List.of("1", "İstanbul")));
        byte[] encoded = SourceOwnerCheckpointCodec.encode(pending);
        assertEquals(pending, SourceOwnerCheckpointCodec.decode(encoded));
        assertThrows(ProtectionException.class, () -> SourceOwnerCheckpointCodec.decode(Arrays.copyOf(encoded, encoded.length - 1)));
        assertThrows(ProtectionException.class, () -> SourceOwnerCheckpointCodec.decode(Arrays.copyOf(encoded, encoded.length + 1)));
        assertThrows(ProtectionException.class, () -> SourceOwnerCheckpointCodec.decode(new byte[SourceOwnerCheckpointCodec.MAX_BYTES + 1]));
        var page = new Page(UUID.randomUUID(), "-", true, java.util.stream.IntStream.range(0, 128)
                .mapToObj(i -> i + "x".repeat(1000)).toList());
        assertTrue(SourceOwnerCheckpointCodec.encode(Checkpoint.initial(request).fenced(ProtectionKey.hash("sealed")).staged(page)).length
                <= SourceOwnerCheckpointCodec.MAX_BYTES);
    }
    @Test void nativeLongIdsAreCanonicalAndNeverSilentlyNormalizeAnUnknownCallerId() {
        var codec = JdbcSourceOwnerOnboarding.longIds();
        assertEquals("12", codec.encode(12L)); assertEquals(12L, codec.decode("12").longValue());
        for (String id : List.of("012", "+12", "12 ", "9223372036854775808", "private-id"))
            assertThrows(ProtectionException.class, () -> codec.decode(id));
    }
    @Test void nativeLongReaderRejectsFractionAndOverflowWithoutDriverCoercion() throws Exception {
        var source = new org.h2.jdbcx.JdbcDataSource();
        source.setURL("jdbc:h2:mem:owner_long_" + UUID.randomUUID().toString().replace("-", ""));
        var ids = JdbcSourceOwnerOnboarding.longIds();
        try (var c = source.getConnection()) {
            for (String raw : List.of("1.5", "-1.5", "9223372036854775808", "-9223372036854775809")) {
                int scale = raw.contains(".") ? 1 : 0;
                try (var q = c.prepareStatement("SELECT CAST(? AS DECIMAL(19," + scale + "))")) {
                    q.setBigDecimal(1, new java.math.BigDecimal(raw)); q.setQueryTimeout(2);
                    try (var rows = q.executeQuery()) {
                        assertTrue(rows.next());
                        var failure = assertThrows(ProtectionException.class, () -> ids.read(rows, 1));
                        assertEquals(ProtectionException.Code.INCOMPLETE_INVENTORY, failure.code());
                        assertNull(failure.getCause()); assertFalse(failure.getMessage().contains(raw));
                    }
                }
            }
            for (long value : List.of(Long.MIN_VALUE, -1L, 0L, Long.MAX_VALUE)) {
                try (var q = c.prepareStatement("SELECT CAST(? AS DECIMAL(19,0))")) {
                    q.setBigDecimal(1, java.math.BigDecimal.valueOf(value)); q.setQueryTimeout(2);
                    try (var rows = q.executeQuery()) {
                        assertTrue(rows.next()); assertEquals(value, ids.read(rows, 1).longValue());
                    }
                }
            }
            try (var q = c.prepareStatement("SELECT CAST(NULL AS DECIMAL(19,0))"); var rows = q.executeQuery()) {
                assertTrue(rows.next()); assertThrows(ProtectionException.class, () -> ids.read(rows, 1));
            }
        }
    }
    @Test void migrationIsOneBoundedAdministrativeRowPerPairWithoutAHistoryLedgerOrFenceDeletion() throws Exception {
        for (var dialect : JdbcTenantPurgeDialect.values()) {
            String path = "/com/reactor/cachedb/jdbc/migrations/" + dialect.name().toLowerCase(Locale.ROOT)
                    + "/V019_3__source_owner_onboarding.sql";
            try (var stream = getClass().getResourceAsStream(path)) {
                assertNotNull(stream); byte[] bytes = stream.readNBytes(8193); assertTrue(bytes.length <= 8192);
                String sql = new String(bytes, StandardCharsets.UTF_8);
                assertTrue(sql.contains("CREATE TABLE cachedb_owner_onboarding"));
                assertTrue(sql.contains("PRIMARY KEY(scope_token,tenant_token)")); assertTrue(sql.contains("262144"));
                assertFalse(sql.contains("cachedb_write_fence")); assertFalse(sql.contains("cachedb_tenant_allocator"));
                assertFalse(sql.contains("DELETE ")); assertFalse(sql.contains("DROP ")); assertFalse(sql.contains("identity_id"));
            }
        }
    }
}
