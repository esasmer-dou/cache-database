package com.reactor.cachedb.spring.boot;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.SourceOwnerOnboarding.*;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.jdbc.*;
import java.sql.SQLException;
import java.util.*;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Requires explicitly installed V019/V019_1/V019_2/V019_3 fixture migrations. No runtime DDL. */
@EnabledIfSystemProperty(named = "cachedb.snapshot.providers", matches = "true")
@Timeout(60)
class SourceOwnerOnboardingProvidersTest {
    @ParameterizedTest @ValueSource(strings = {"postgres", "mssql", "oracle"})
    void closedIndexedReaderKeepsCaseDistinctTenantsAndResumesPendingPage(String provider) throws Exception {
        // Administrative context precedes every administrative fixture/API call.
        try (var admin = CacheTenantContext.administrative(); var f = new TenantPurgeProvidersTest.Fixture(provider)) {
            f.guard.provisionClosed(f.generation(1));
            f.seed("a", 2); f.seed("A", 1);
            var codec = JdbcSourceOwnerOnboarding.stringIds();
            var plan = new Plan(f.binding, "entity_version", codec.formatToken());
            var request = new Request(plan, f.generation(1), "a", UUID.randomUUID(), ProtectionKey.hash("operator"), 30);
            var store = new JdbcSourceOwnerOnboarding<>(f.guard, plan, codec, 2, Set.of(plan.fingerprint()));
            Session first = store.acquire(request);
            try {
                var initial = store.load(first);
                var running = store.save(first, initial, initial.fenced(ProtectionKey.hash("provider-journal-test")));
                var page = new Page(UUID.randomUUID(), "-", true,
                        List.of(ProtectionKey.hash("a") + "0", ProtectionKey.hash("A") + "0", "absent-id"));
                var pending = store.save(first, running, running.staged(page));
                var proofs = store.resolve(first, pending);
                assertEquals(3, proofs.size());
                assertEquals(Ownership.SOURCE_OWNED, proofs.get(0).ownership()); assertEquals(1, proofs.get(0).sourceVersion());
                assertEquals(Ownership.UNKNOWN, proofs.get(1).ownership()); assertEquals(0, proofs.get(1).sourceVersion());
                assertEquals(Ownership.UNKNOWN, proofs.get(2).ownership());
                assertEquals(2, f.rows("a")); assertEquals(1, f.rows("A"));
                assertFalse(proofs.toString().contains("absent-id"));

                store.release(first);
                var resumed = new JdbcSourceOwnerOnboarding<>(f.guard, plan, codec, 2, Set.of(plan.fingerprint()));
                Session second = resumed.acquire(request);
                try {
                    assertTrue(second.lease().epoch() > first.lease().epoch());
                    assertEquals(ProtectionException.Code.LEASE_LOST,
                            assertThrows(ProtectionException.class, () -> store.load(first)).code());
                    assertEquals(pending, resumed.load(second));
                    assertEquals(proofs, resumed.resolve(second, resumed.load(second)));
                    resumed.renew(second);
                    assertThrows(ProtectionException.class, () -> f.guard.openInitialGeneration(f.generation(1)));
                    try (var c = f.source.getConnection()) {
                        c.setAutoCommit(false);
                        assertEquals("CDBGC", assertThrows(SQLException.class,
                                () -> f.guard.authorizeWrite(c, f.generation(1))).getSQLState());
                        c.rollback();
                    }
                } finally { resumed.release(second); }
            } finally { store.release(first); }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"postgres", "mssql", "oracle"})
    void openTenantCannotBeSilentlyAdoptedByOwnershipBootstrap(String provider) throws Exception {
        try (var admin = CacheTenantContext.administrative(); var f = new TenantPurgeProvidersTest.Fixture(provider)) {
            f.provision(); var codec = JdbcSourceOwnerOnboarding.stringIds();
            var plan = new Plan(f.binding, "entity_version", codec.formatToken());
            var store = new JdbcSourceOwnerOnboarding<>(f.guard, plan, codec, 2, Set.of(plan.fingerprint()));
            var request = new Request(plan, f.generation(1), "a", UUID.randomUUID(), ProtectionKey.hash("operator"), 30);
            assertEquals(ProtectionException.Code.MIGRATION_REQUIRED,
                    assertThrows(ProtectionException.class, () -> store.acquire(request)).code());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"postgres", "mssql", "oracle"})
    void existingPurgeHistoryCannotBeRepurposedAsInitialEnrollmentOrRedisOnlyReenable(String provider) throws Exception {
        try (var admin = CacheTenantContext.administrative(); var f = new TenantPurgeProvidersTest.Fixture(provider)) {
            f.provision(); f.seed("a", 1);
            var purge = f.guard.acquire(f.key, f.options(false), false, f.binding.fingerprint());
            f.guard.release(purge);
            var codec = JdbcSourceOwnerOnboarding.stringIds();
            var plan = new Plan(f.binding, "entity_version", codec.formatToken());
            var store = new JdbcSourceOwnerOnboarding<>(f.guard, plan, codec, 2, Set.of(plan.fingerprint()));
            var request = new Request(plan, f.generation(1), "a", UUID.randomUUID(), ProtectionKey.hash("operator"), 30);
            assertEquals(ProtectionException.Code.MIGRATION_REQUIRED,
                    assertThrows(ProtectionException.class, () -> store.acquire(request)).code());
            assertEquals(1, f.rows("a"));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"postgres", "mssql", "oracle"})
    void completedSourcePurgeCannotBeRepurposedAsOwnershipPreparationOrReopening(String provider) throws Exception {
        try (var admin = CacheTenantContext.administrative(); var f = new TenantPurgeProvidersTest.Fixture(provider)) {
            f.provision(); f.seed("a", 1); f.seed("A", 1);
            f.guard.validateSource(f.binding);
            var lease = f.guard.acquire(f.key, f.options(true), false, f.binding.fingerprint());
            try {
                // Component fixture advances native work; only the JDBC rejection contract is tested here.
                var progress = f.redisStage(lease);
                progress = f.guard.save(lease, progress, progress.advance(TenantPurgeProtocol.Stage.SOURCE,
                        progress.inventoryToken(), "-", null, 0, 0, 0, 0, 0, null));
                progress = f.guard.deleteSource(lease, f.binding, "a", 128);
                assertEquals(TenantPurgeProtocol.Stage.SOURCE, progress.stage());
                assertEquals(1, progress.sourceRows());
                assertEquals(0, f.rows("a")); assertEquals(1, f.rows("A"));
                // A zero-row batch confirms exhaustion without assuming a full page is final.
                progress = f.guard.deleteSource(lease, f.binding, "a", 128);
                assertEquals(TenantPurgeProtocol.Stage.VERIFYING, progress.stage());
                assertEquals(1, progress.sourceRows());
                progress = f.guard.save(lease, progress, progress.advance(TenantPurgeProtocol.Stage.COMPLETE,
                        progress.inventoryToken(), "-", null, 0, progress.redisRows(), progress.redisKeys(),
                        progress.sourceRows(), 0, java.time.Instant.now()));
                var codec = JdbcSourceOwnerOnboarding.stringIds();
                var plan = new Plan(f.binding, "entity_version", codec.formatToken());
                var store = new JdbcSourceOwnerOnboarding<>(f.guard, plan, codec, 2, Set.of(plan.fingerprint()));
                for (long generation = 1; generation <= 2; generation++) {
                    if (generation == 2) progress = f.guard.prepareReenable(lease);
                    var current = f.generation(generation);
                    var request = new Request(plan, current, "a", UUID.randomUUID(), ProtectionKey.hash("operator"), 30);
                    assertEquals(ProtectionException.Code.MIGRATION_REQUIRED,
                            assertThrows(ProtectionException.class, () -> store.acquire(request)).code());
                    assertEquals(progress, f.guard.load(lease));
                    try (var c = f.source.getConnection()) {
                        try (var q = c.prepareStatement("SELECT COUNT(*) FROM cachedb_owner_onboarding"
                                + " WHERE scope_token=? AND tenant_token=?")) {
                            q.setQueryTimeout(2); q.setString(1, f.key.scopeToken()); q.setString(2, f.key.tenantToken());
                            try (var rows = q.executeQuery()) { assertTrue(rows.next()); assertEquals(0, rows.getLong(1)); }
                        }
                        c.setAutoCommit(false);
                        assertEquals("CDBGC", assertThrows(SQLException.class,
                                () -> f.guard.authorizeWrite(c, current)).getSQLState());
                        c.rollback();
                    }
                    assertEquals(0, f.rows("a")); assertEquals(1, f.rows("A"));
                }
                assertEquals(ProtectionException.Code.MIGRATION_REQUIRED,
                        assertThrows(ProtectionException.class, () -> f.guard.openPreparedGeneration(lease)).code());
                assertEquals(progress, f.guard.load(lease));
            } finally { f.guard.release(lease); }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"postgres", "mssql", "oracle"})
    void nativeNumericReaderRejectsFractionAndOutOfRangeWithoutDriverTruncation(String provider) throws Exception {
        try (var admin = CacheTenantContext.administrative(); var f = new TenantPurgeProvidersTest.Fixture(provider);
                var c = f.source.getConnection()) {
            var ids = JdbcSourceOwnerOnboarding.longIds();
            for (String value : List.of("1.5", "-1.5", "9223372036854775808", "-9223372036854775809")) {
                int scale = value.contains(".") ? 1 : 0;
                String numeric = provider.equals("oracle") ? "NUMBER" : "DECIMAL";
                String sql = "SELECT CAST(? AS " + numeric + "(19," + scale + "))"
                        + (provider.equals("oracle") ? " FROM DUAL" : "");
                try (var q = c.prepareStatement(sql)) {
                    q.setQueryTimeout(2); q.setBigDecimal(1, new java.math.BigDecimal(value));
                    try (var rows = q.executeQuery()) {
                        assertTrue(rows.next());
                        var failure = assertThrows(ProtectionException.class, () -> ids.read(rows, 1));
                        assertEquals(ProtectionException.Code.INCOMPLETE_INVENTORY, failure.code());
                        assertNull(failure.getCause()); assertFalse(failure.getMessage().contains(value));
                    }
                }
            }
            for (long value : List.of(Long.MIN_VALUE, -1L, 0L, Long.MAX_VALUE)) {
                String numeric = provider.equals("oracle") ? "NUMBER" : "DECIMAL";
                try (var q = c.prepareStatement("SELECT CAST(? AS " + numeric + "(19,0))"
                        + (provider.equals("oracle") ? " FROM DUAL" : ""))) {
                    q.setQueryTimeout(2); q.setBigDecimal(1, java.math.BigDecimal.valueOf(value));
                    try (var rows = q.executeQuery()) {
                        assertTrue(rows.next()); assertEquals(value, ids.read(rows, 1).longValue());
                    }
                }
            }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"postgres", "mssql", "oracle"})
    void fractionalIdentityStorageIsRejectedBeforeAnyPendingPageOrOwnershipProof(String provider) throws Exception {
        try (var admin = CacheTenantContext.administrative(); var f = new NumericSourceFixture(provider, 1)) {
            f.seed("1.5");
            f.guard.provisionClosed(f.generation());
            var request = f.request();
            assertEquals(ProtectionException.Code.INVALID_REQUEST,
                    assertThrows(ProtectionException.class, () -> f.store().acquire(request)).code());
            try (var c = f.resources.source.getConnection();
                    var q = c.prepareStatement("SELECT payload_bytes FROM cachedb_owner_onboarding WHERE scope_token=? AND tenant_token=?")) {
                q.setQueryTimeout(2);
                q.setString(1, request.generation().key().scopeToken());
                q.setString(2, request.generation().key().tenantToken());
                try (var rows = q.executeQuery()) { assertFalse(rows.next()); }
            }
            f.assertClosed();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"postgres", "mssql", "oracle"})
    void decimalNineteenDigitStorageDoesNotMakeOutOfRangeCallerIdsOrRowsLegitimate(String provider) throws Exception {
        try (var admin = CacheTenantContext.administrative(); var f = new NumericSourceFixture(provider, 0)) {
            f.seed(Long.toString(Long.MIN_VALUE)); f.seed(Long.toString(Long.MAX_VALUE));
            f.seed("9223372036854775808"); f.seed("-9223372036854775809");
            f.guard.provisionClosed(f.generation());
            var request = f.request(); var store = f.store(); var session = store.acquire(request);
            try {
                var initial = store.load(session);
                var running = store.save(session, initial, initial.fenced(ProtectionKey.hash("numeric-journal-test")));
                for (String outside : List.of("9223372036854775808", "-9223372036854775809")) {
                    var rejected = running.staged(new Page(UUID.randomUUID(), "-", true, List.of(outside)));
                    assertEquals(ProtectionException.Code.INVALID_REQUEST,
                            assertThrows(ProtectionException.class, () -> store.save(session, running, rejected)).code());
                    assertEquals(running, store.load(session));
                }
                var pending = store.save(session, running, running.staged(new Page(UUID.randomUUID(), "-", true,
                        List.of(Long.toString(Long.MIN_VALUE), Long.toString(Long.MAX_VALUE), "0"))));
                var proofs = store.resolve(session, pending);
                assertEquals(3, proofs.size());
                assertEquals(Long.MIN_VALUE, proofs.get(0).id().longValue());
                assertEquals(Long.MAX_VALUE, proofs.get(1).id().longValue());
                assertEquals(Ownership.SOURCE_OWNED, proofs.get(0).ownership());
                assertEquals(Ownership.SOURCE_OWNED, proofs.get(1).ownership());
                assertEquals(Ownership.UNKNOWN, proofs.get(2).ownership());
                assertEquals(0, proofs.get(2).sourceVersion());
                f.assertClosed();
            } finally { store.release(session); }
        }
    }

    /** Fresh source table only. Never invokes the legacy write-fence fixture's cleanup. */
    static final class NumericSourceFixture implements AutoCloseable {
        final TenantPurgeProvidersTest.Fixture resources;
        final String table = "oi_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        final com.reactor.cachedb.core.protection.TenantPurgeProtocol.Binding binding;
        final JdbcTenantPurgeGuard guard;
        final Plan plan;
        final UUID operation = UUID.randomUUID();
        NumericSourceFixture(String provider, int scale) throws Exception {
            if (scale != 0 && scale != 1) throw new IllegalArgumentException("Invalid fixture scale");
            resources = new TenantPurgeProvidersTest.Fixture(provider);
            binding = new com.reactor.cachedb.core.protection.TenantPurgeProtocol.Binding(Long.class, table,
                    new com.reactor.cachedb.core.protection.TenantPurgeProtocol.SourcePlan(table, "tenant_id", "id"),
                    ProtectionKey.hash("numeric-owner-layout"), 128, 128, 131072, true);
            plan = new Plan(binding, "entity_version", JdbcSourceOwnerOnboarding.longIds().formatToken());
            guard = new JdbcTenantPurgeGuard(resources.source, resources.dialect, 2, 2, Set.of(binding.fingerprint()));
            boolean created = false;
            try (var c = resources.source.getConnection(); var q = c.createStatement()) {
                q.setQueryTimeout(5);
                String decimal = provider.equals("oracle") ? "NUMBER" : "DECIMAL";
                String version = provider.equals("oracle") ? "NUMBER(19)" : "BIGINT";
                String tenant = provider.equals("mssql") ? "NVARCHAR(64)" : "VARCHAR(64)";
                q.executeUpdate("CREATE TABLE " + table + " (id " + decimal + "(19," + scale
                        + ") NOT NULL PRIMARY KEY,tenant_id " + tenant + " NOT NULL,entity_version " + version + " NOT NULL)");
                created = true;
                q.executeUpdate("CREATE INDEX oit_" + table.substring(3) + " ON " + table + " (tenant_id,id)");
            } catch (Exception | Error failure) {
                if (created) {
                    try (var c = resources.source.getConnection(); var q = c.createStatement()) {
                        q.setQueryTimeout(5); q.executeUpdate("DROP TABLE " + table);
                    } catch (Exception | Error cleanup) { failure.addSuppressed(cleanup); }
                }
                try { resources.close(); } catch (Exception | Error cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
        com.reactor.cachedb.core.protection.TenantPurgeProtocol.Generation generation() {
            return new com.reactor.cachedb.core.protection.TenantPurgeProtocol.Generation(binding.key("a"), 1);
        }
        Request request() { return new Request(plan, generation(), "a", operation, ProtectionKey.hash("operator"), 30); }
        JdbcSourceOwnerOnboarding<Long> store() {
            return new JdbcSourceOwnerOnboarding<>(guard, plan, JdbcSourceOwnerOnboarding.longIds(), 2, Set.of(plan.fingerprint()));
        }
        void seed(String value) throws Exception {
            try (var c = resources.source.getConnection(); var q = c.prepareStatement(
                    "INSERT INTO " + table + " (id,tenant_id,entity_version) VALUES (?, ?, 1)")) {
                q.setQueryTimeout(2); q.setBigDecimal(1, new java.math.BigDecimal(value));
                if (resources.dialect == JdbcTenantPurgeDialect.MSSQL) q.setNString(2, "a"); else q.setString(2, "a");
                assertEquals(1, q.executeUpdate());
            }
        }
        void assertClosed() throws Exception {
            try (var c = resources.source.getConnection()) {
                c.setAutoCommit(false);
                assertEquals("CDBGC", assertThrows(SQLException.class, () -> guard.authorizeWrite(c, generation())).getSQLState());
                c.rollback();
            }
        }
        public void close() throws Exception {
            if (!table.matches("oi_[0-9a-f]{20}")) throw new IllegalStateException("Unsafe fixture table");
            try (resources; var c = resources.source.getConnection(); var q = c.createStatement()) {
                q.setQueryTimeout(5); q.executeUpdate("DROP TABLE " + table);
            }
        }
    }
}
