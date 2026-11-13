package com.reactor.cachedb.jdbc;

import static com.reactor.cachedb.jdbc.CacheInitialNativeAmbiguityRedisTest.bind;
import static com.reactor.cachedb.jdbc.CacheInitialNativeAmbiguityRedisTest.state;
import static com.reactor.cachedb.jdbc.CacheInitialNativeBridgeProvidersRedisTest.controlPlan;
import static org.junit.jupiter.api.Assertions.*;

import com.reactor.cachedb.core.protection.InitialTenantBootstrap.Activation;
import com.reactor.cachedb.core.protection.InitialTenantBootstrap.Phase;
import com.reactor.cachedb.core.protection.InitialTenantBootstrap.Progress;
import com.reactor.cachedb.core.protection.InitialTenantBootstrap.Request;
import com.reactor.cachedb.core.protection.ProtectionException;
import com.reactor.cachedb.core.protection.ProtectionKey;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.Generation;
import com.reactor.cachedb.core.protection.PurgeOptions;
import com.reactor.cachedb.core.protection.PurgeScope;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.Stage;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.redis.RedisTenantVersionAllocator;
import com.reactor.cachedb.starter.CacheDataProtection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.function.Executable;

/**
 * First protected namespace qualification over existing, owned provider fixtures.
 * This is not a legacy migration, arbitrary owner adoption, or reopening API.
 * Runtime qualification requires the separately coordinated Kubernetes run.
 */
@EnabledIfSystemProperty(named = "cachedb.snapshot.providers", matches = "true")
@EnabledIfSystemProperty(named = "cachedb.snapshot.redis", matches = "true")
@EnabledIfSystemProperty(named = "cachedb.initial.isolatedSql", matches = "true")
@Timeout(120)
class CacheInitialSourceBootstrapRedisTest {
    private static final String NS = "native_rows";
    private static final String ENTITY = "NativeInitialRow";
    private static final int INITIAL_CALLS = 512;
    private static final int PURGE_CALLS = 1024;

    @Test void postgresFirstNamespaceAndPurge() throws Exception {
        firstProtectedNamespaceResumesCapturedPageAndStaysClosedAfterPurge("postgres");
    }

    @Test void mssqlFirstNamespaceAndPurge() throws Exception {
        firstProtectedNamespaceResumesCapturedPageAndStaysClosedAfterPurge("mssql");
    }

    @Test void oracleFirstNamespaceAndPurge() throws Exception {
        firstProtectedNamespaceResumesCapturedPageAndStaysClosedAfterPurge("oracle");
    }

    @Test void postgresSqlOpenCommitUncertainty() throws Exception {
        sqlOpenCommitUncertaintyCannotPublishOrReserveAnotherActivation("postgres");
    }

    @Test void mssqlSqlOpenCommitUncertainty() throws Exception {
        sqlOpenCommitUncertaintyCannotPublishOrReserveAnotherActivation("mssql");
    }

    @Test void oracleSqlOpenCommitUncertainty() throws Exception {
        sqlOpenCommitUncertaintyCannotPublishOrReserveAnotherActivation("oracle");
    }

    private void firstProtectedNamespaceResumesCapturedPageAndStaysClosedAfterPurge(String provider) throws Exception {
        try (var admin = CacheTenantContext.administrative();
                var f = new CacheInitialNativeBridgeProvidersRedisTest.Fixture(provider, false, true)) {
            var b = bind(f);
            var phases = EnumSet.noneOf(Phase.class);
            var wrongGeneration = new Request(new Generation(f.generation.key(), 2), "a",
                    b.request().operationId(), b.request().operatorToken(), b.request().leaseSeconds());
            assertSafe(ProtectionException.Code.MIGRATION_REQUIRED, () -> b.service().step(wrongGeneration));
            assertEquals("CLOSED", sqlGate(f));
            assertEquals(0, count(f, "cachedb_initial_bootstrap"));
            assertFalse(f.redis.exists(f.database.redisKeyStrategy().entityKey(NS, 1L)));

            advance(f, b, Phase.HOT_WINDOW, phases);
            assertTrue(phases.containsAll(Set.of(Phase.NATIVE_INVENTORY, Phase.DURABLE_VERSIONS,
                    Phase.SOURCE_VERSIONS, Phase.HOT_WINDOW)));
            assertEquals("CLOSED", sqlGate(f));
            assertEquals("CLOSED", nativeGate(f, b));
            assertEquals(f.originalSource, f.sourceSnapshot());

            // The real JDBC commit succeeds; only its acknowledgement is lost.
            f.faults.arm("UPDATE cachedb_initial_page SET is_applied=1", true);
            assertSafe(ProtectionException.Code.STORAGE_UNAVAILABLE, () -> b.service().step(b.request()));
            f.faults.requireConsumed();
            assertEquals(1, f.faults.fired.get());
            var captured = state(f, b.request());
            assertEquals(b.request().operationId(), captured.operation);
            assertEquals(1, captured.pages);
            assertNull(captured.activation);
            var storedPage = pages(f);
            assertEquals(1, storedPage.size());
            var payloads = payloads(f);
            assertEquals("CLOSED", nativeGate(f, b));
            assertEquals("CLOSED", sqlGate(f));

            advance(f, b, Phase.READY, phases);
            assertEquals(storedPage, pages(f), "Continuation must reuse the committed encrypted page");
            assertEquals(payloads, payloads(f), "Continuation must not re-encrypt captured rows");
            var active = advance(f, b, Phase.ACTIVE, phases);
            assertEquals(2, active.warmedRows());
            assertTrue(active.highwater() >= 9000, "The cold tenant-a source row must affect version inventory");
            assertTrue(active.highwater() < 18000, "Tenant-b source versions must not enter tenant-a inventory");
            assertEquals("OPEN", nativeGate(f, b));
            assertEquals("OPEN", sqlGate(f));
            assertEquals(f.generation, b.enrollment().barrier(ENTITY).captureOpen(f.generation.key()));
            assertEquals(payloads, payloads(f));
            assertEquals(f.originalSource, f.sourceSnapshot(), "Bootstrap must not mutate source ciphertext or versions");
            assertEquals(0, count(f, "cachedb_initial_page"));
            assertEquals(1, count(f, "cachedb_namespace_bootstrap"));
            var keys = f.database.redisKeyStrategy();
            for (long id : List.of(1L, 2L)) {
                assertEquals(id == 1 ? "7" : "8", f.redis.get(keys.versionKey(NS, id)));
                assertEquals("a", f.redis.get(keys.versionKey(NS, id) + ":tenant"));
            }
            for (long id : List.of(3L, 99L)) assertFalse(f.redis.exists(keys.entityKey(NS, id)));

            int beforeReads = f.source.calls.get();
            var deliveries = new AtomicInteger();
            for (long id : List.of(1L, 2L)) {
                b.enrollment().deliver(f.metadata, "a", repo -> repo.findById(id), row -> {
                    assertEquals(new CacheInitialNativeBridgeProvidersRedisTest.Row(id, "a",
                            CacheInitialNativeBridgeProvidersRedisTest.SECRET + id), row.orElseThrow());
                    deliveries.incrementAndGet();
                });
            }
            assertEquals(2, deliveries.get());
            assertEquals(beforeReads, f.source.calls.get(), "Protected foreground reads must not borrow SQL");
            assertEquals(Phase.ACTIVE, b.service().step(b.request()).phase());

            var plan = controlPlan(f, f.database, b.enrollment(), b.namespace().window());
            var facade = f.database.configureDataProtection(List.of(plan), 1);
            var options = new PurgeOptions(UUID.randomUUID(), "a", ProtectionKey.hash("onboarding-purge:" + f.run),
                    true, 128, 120, 5000, PurgeScope.ACTIVE_STORES_RETAINING_SECURITY_METADATA);
            CacheDataProtection.Continuation continuation = null;
            try {
                CacheDataProtection.Result result = null;
                for (int call = 0; call < PURGE_CALLS; call++) {
                    result = facade.purgeTenant(CacheInitialNativeBridgeProvidersRedisTest.Row.class, "a", options, continuation);
                    continuation = result.continuation();
                    assertTrue(result.status() == CacheDataProtection.Status.RUNNING
                            || result.status() == CacheDataProtection.Status.COMPLETE,
                            "Purge must not hide an incomplete inventory behind success");
                    if (result.status() == CacheDataProtection.Status.COMPLETE) break;
                }
                assertNotNull(result);
                assertEquals(CacheDataProtection.Status.COMPLETE, result.status());
                assertEquals(Stage.COMPLETE, result.receipt().stage());
                assertEquals(3, result.receipt().sourceRows());
                assertEquals(2, result.receipt().redisRows());
                assertNotNull(result.evidence());
                assertEquals(Map.of(3L, f.originalSource.get(3L)), f.sourceSnapshot());
                assertEquals("CLOSED", nativeGate(f, b));
                assertEquals("CLOSED", sqlGate(f));
                for (long id : List.of(1L, 2L, 99L)) {
                    assertFalse(f.redis.exists(keys.entityKey(NS, id)));
                    assertFalse(f.redis.exists(keys.versionKey(NS, id)));
                    assertFalse(f.redis.exists(keys.versionKey(NS, id) + ":tenant"));
                    assertFalse(f.redis.exists(keys.indexMetaKey(NS, id)));
                }
                assertEquals(0, count(f, "cachedb_initial_bootstrap"));
                assertEquals(0, count(f, "cachedb_initial_page"));
                assertEquals(1, count(f, "cachedb_namespace_bootstrap"), "Purge must retain the consumed birth grant");
                assertSafe(ProtectionException.Code.MIGRATION_REQUIRED, () -> b.service().step(b.request()));
                var newOperation = new Request(f.generation, "a", UUID.randomUUID(),
                        b.request().operatorToken(), b.request().leaseSeconds());
                assertSafe(ProtectionException.Code.MIGRATION_REQUIRED, () -> b.service().step(newOperation));
                try (var c = f.source.getConnection()) {
                    c.setAutoCommit(false);
                    assertThrows(SQLException.class, () -> f.guard.authorizeWrite(c, f.generation));
                    c.rollback();
                }
                assertEquals("CLOSED", nativeGate(f, b));
                assertEquals("CLOSED", sqlGate(f));
                assertEquals(Map.of(3L, f.originalSource.get(3L)), f.sourceSnapshot());
                f.evidence("PASS onboarding=first-protected-namespace provider=" + provider
                        + " capturedPageCommitLossResumed=true actualInitialActive=true originalVersions=true"
                        + " sourceUnchangedBeforePurge=true foregroundSqlBorrows=0 purgeComplete=true"
                        + " foreignSourcePreserved=true staleRequestRejected=true newOperationRejected=true retainedBirth=1");
            } finally {
                if (continuation != null) facade.abandon(continuation);
            }
        }
    }

    private void sqlOpenCommitUncertaintyCannotPublishOrReserveAnotherActivation(String provider) throws Exception {
        try (var admin = CacheTenantContext.administrative();
                var f = new CacheInitialNativeBridgeProvidersRedisTest.Fixture(provider, false, true)) {
            var b = bind(f);
            advance(f, b, Phase.READY, EnumSet.noneOf(Phase.class));
            f.faults.arm("UPDATE cachedb_tenant_guard SET generation=?,gate_state=?", true);
            assertSafe(ProtectionException.Code.STORAGE_UNAVAILABLE, () -> b.service().step(b.request()));
            f.faults.requireConsumed();
            assertEquals(1, f.faults.fired.get());
            var committed = state(f, b.request());
            assertEquals(Phase.SQL_OPEN, committed.phase);
            assertNotNull(committed.activation);
            assertFalse(committed.finalizing);
            assertEquals("OPEN", sqlGate(f));
            assertEquals("CLOSED", nativeGate(f, b));
            var activation = committed.activation;
            var allocator = new RedisTenantVersionAllocator(f.database.redisOperations(),
                    f.database.redisKeyStrategy(), b.enrollment().barrier(ENTITY), NS);
            var range = f.redis.hgetAll(allocator.allocatorKey(f.generation.key()));
            String counter = f.redis.get(allocator.counterKey(f.generation.key()));
            var nativeFence = f.redis.hgetAll(b.enrollment().barrier(ENTITY).generationKey(f.generation.key()));
            var capturedPayloads = payloads(f);
            var capturedPages = pages(f);
            var sqlReservation = reservations(f);

            // Replacement administrative leases must not turn an uncertain commit into new authority.
            for (int retry = 0; retry < 2; retry++) {
                assertSafe(ProtectionException.Code.RECOVERY_REQUIRED, () -> b.service().step(b.request()));
                var observed = state(f, b.request());
                assertEquals(Phase.SQL_OPEN, observed.phase);
                assertActivationUnchanged(activation, observed.activation);
                assertEquals(range, f.redis.hgetAll(allocator.allocatorKey(f.generation.key())));
                assertEquals(counter, f.redis.get(allocator.counterKey(f.generation.key())));
                assertEquals(nativeFence, f.redis.hgetAll(b.enrollment().barrier(ENTITY).generationKey(f.generation.key())));
                assertEquals(capturedPayloads, payloads(f));
                assertEquals(capturedPages, pages(f));
                assertEquals(sqlReservation, reservations(f));
                assertEquals("OPEN", sqlGate(f));
                assertEquals("CLOSED", nativeGate(f, b));
            }
            assertEquals(f.originalSource, f.sourceSnapshot());
            f.evidence("PASS onboarding=SQL_OPEN-commit-uncertainty provider=" + provider
                    + " originalActivationRetained=true originalRangeRetained=true repeatedRecoveryRequired=true"
                    + " nativeClosed=true noReopen=true noNewReservation=true sourceUnchanged=true");
        }
    }

    private static void assertActivationUnchanged(Activation expected, Activation actual) {
        assertNotNull(actual);
        assertEquals(expected.token(), actual.token());
        assertEquals(expected.planToken(), actual.planToken());
        assertEquals(expected.manifestToken(), actual.manifestToken());
        assertEquals(expected.inventoryToken(), actual.inventoryToken());
        assertEquals(expected.window(), actual.window());
        assertEquals(expected.runtimeProof(), actual.runtimeProof());
        // JDBC restores a fresh range object; compare every persisted field, not object identity.
        var before = expected.range();
        var after = actual.range();
        assertEquals(before.key(), after.key());
        assertEquals(before.generation(), after.generation());
        assertEquals(before.controlEpoch(), after.controlEpoch());
        assertEquals(before.reservationEpoch(), after.reservationEpoch());
        assertEquals(before.first(), after.first());
        assertEquals(before.last(), after.last());
        assertEquals(before.request(), after.request());
        assertEquals(before.runId(), after.runId());
        assertEquals(before.inventory(), after.inventory());
    }

    private static Progress advance(CacheInitialNativeBridgeProvidersRedisTest.Fixture f,
            CacheInitialNativeAmbiguityRedisTest.Bound b, Phase expected, Set<Phase> phases) throws Exception {
        for (int call = 0; call < INITIAL_CALLS; call++) {
            var progress = b.service().step(b.request());
            phases.add(progress.phase());
            assertNotEquals(Phase.INCOMPLETE, progress.phase());
            if (progress.phase() == expected) return progress;
        }
        throw new AssertionError("INITIAL did not reach the requested phase within its fixed call budget: " + expected);
    }

    private static String nativeGate(CacheInitialNativeBridgeProvidersRedisTest.Fixture f,
            CacheInitialNativeAmbiguityRedisTest.Bound b) {
        return f.redis.hget(b.enrollment().barrier(ENTITY).generationKey(f.generation.key()), "state");
    }

    private static String sqlGate(CacheInitialNativeBridgeProvidersRedisTest.Fixture f) throws SQLException {
        try (var c = f.source.getConnection(); var q = c.prepareStatement("SELECT gate_state FROM cachedb_tenant_guard")) {
            q.setQueryTimeout(2);
            q.setMaxRows(2);
            try (var rows = q.executeQuery()) {
                assertTrue(rows.next());
                String gate = rows.getString(1);
                assertFalse(rows.next());
                return gate;
            }
        }
    }

    private static List<String> payloads(CacheInitialNativeBridgeProvidersRedisTest.Fixture f) {
        var keys = f.database.redisKeyStrategy();
        var payloads = List.of(f.redis.get(keys.entityKey(NS, 1L)), f.redis.get(keys.entityKey(NS, 2L)));
        for (String payload : payloads) {
            assertTrue(payload.contains("~E1:k2:"));
            assertFalse(payload.contains(CacheInitialNativeBridgeProvidersRedisTest.SECRET));
        }
        return payloads;
    }

    private static List<String> pages(CacheInitialNativeBridgeProvidersRedisTest.Fixture f) throws SQLException {
        var result = new ArrayList<String>();
        try (var c = f.source.getConnection(); var q = c.prepareStatement(
                "SELECT page_no,payload,is_applied FROM cachedb_initial_page ORDER BY page_no")) {
            q.setQueryTimeout(2);
            q.setMaxRows(129);
            try (var rows = q.executeQuery()) {
                while (rows.next()) {
                    assertTrue(result.size() < 128);
                    byte[] payload = rows.getBytes(2);
                    assertTrue(payload.length <= 65536);
                    result.add(rows.getInt(1) + ":" + rows.getInt(3) + ":" + java.util.Base64.getEncoder().encodeToString(payload));
                }
            }
        }
        return List.copyOf(result);
    }

    private static List<String> reservations(CacheInitialNativeBridgeProvidersRedisTest.Fixture f) throws SQLException {
        try (var c = f.source.getConnection(); var q = c.prepareStatement(
                "SELECT highwater,reservation_epoch,last_first,last_last,last_request,last_run_id FROM cachedb_tenant_allocator")) {
            q.setQueryTimeout(2);
            q.setMaxRows(2);
            try (var rows = q.executeQuery()) {
                assertTrue(rows.next());
                var result = new ArrayList<String>();
                for (int column = 1; column <= 6; column++) result.add(rows.getString(column));
                assertFalse(rows.next());
                return List.copyOf(result);
            }
        }
    }

    private static long count(CacheInitialNativeBridgeProvidersRedisTest.Fixture f, String table) throws SQLException {
        if (!Set.of("cachedb_initial_bootstrap", "cachedb_initial_page", "cachedb_namespace_bootstrap").contains(table))
            throw new AssertionError("Unreviewed fixture table");
        try (var c = f.source.getConnection(); var q = c.prepareStatement("SELECT COUNT(*) FROM " + table)) {
            q.setQueryTimeout(2);
            try (var rows = q.executeQuery()) {
                assertTrue(rows.next());
                long count = rows.getLong(1);
                assertFalse(rows.next());
                return count;
            }
        }
    }

    private static void assertSafe(ProtectionException.Code expected, Executable action) {
        var failure = assertThrows(ProtectionException.class, action);
        assertEquals(expected, failure.code());
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
        assertFalse(failure.toString().contains(CacheInitialNativeBridgeProvidersRedisTest.SECRET));
    }
}
