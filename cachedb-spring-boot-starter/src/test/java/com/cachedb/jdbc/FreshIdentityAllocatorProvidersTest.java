package com.reactor.cachedb.jdbc;

import static com.reactor.cachedb.jdbc.CacheInitialNativeBridgeProvidersRedisTest.controlPlan;
import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.FreshIdentityRange.Request;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.*;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.redis.RedisTenantRuntimeControl;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real SQL regressions; INITIAL additionally requires the actual native namespace bootstrap. */
@EnabledIfSystemProperty(named = "cachedb.snapshot.providers", matches = "true")
@Timeout(180)
class FreshIdentityAllocatorProvidersTest {
    @ParameterizedTest @ValueSource(strings = {"postgres", "mssql", "oracle"})
    void namespaceReservationIsImmutableBoundedAndFencedAcrossTenantsAndLeaseHandoff(String provider) throws Exception {
        try (var admin = CacheTenantContext.administrative(); var f = new InitialSqlSandbox(provider)) {
            var binding = new Binding(String.class, "fresh-" + f.name, new SourcePlan("orders", "tenant_id", "id"),
                    ProtectionKey.hash("layout"), 128, 16, 4096, true);
            var guard = new JdbcTenantPurgeGuard(f.source, f.dialect, 2, 2);
            var allocator = new JdbcFreshIdentityAllocator(guard, f.dialect, 2);
            var domain = FreshIdentityDomain.exclusivelyReserved(binding, UUID.randomUUID(), 100, 199,
                    ProtectionKey.hash("fixture-exclusive-domain-no-external-writers"));
            var a = new Generation(binding.key("a"), 1);
            var b = new Generation(binding.key("b"), 1);
            guard.provisionClosed(a); guard.provisionClosed(b);
            var optionsA = options("a");
            var leaseA = guard.acquireForTenant(binding, "a", optionsA, false);
            var leaseB = guard.acquireForTenant(binding, "b", options("b"), false);
            var request = new Request(domain, a, 8, UUID.randomUUID(), 0);
            assertCode(ProtectionException.Code.MIGRATION_REQUIRED, () -> allocator.reserveClosed(leaseA, request));
            assertEquals(new JdbcFreshIdentityAllocator.Status(99, 0), allocator.provisionClosed(leaseA, 1, domain));
            assertEquals(new JdbcFreshIdentityAllocator.Status(99, 0), allocator.provisionClosed(leaseB, 1, domain));
            var first = allocator.reserveClosed(leaseA, request);
            assertEquals(100, first.first()); assertEquals(107, first.last());
            // Simulated lost response: reconstruct a consumer, not a range or owner marker.
            var independent = new JdbcFreshIdentityAllocator(new JdbcTenantPurgeGuard(f.source, f.dialect, 2, 2), f.dialect, 2);
            assertEquals(first, independent.reserveClosed(leaseA, request));
            assertCode(ProtectionException.Code.RESERVATION_CONFLICT,
                    () -> allocator.reserveClosed(leaseA, new Request(domain, a, 7, request.requestId(), 0)));
            assertCode(ProtectionException.Code.RESERVATION_CONFLICT,
                    () -> allocator.reserveClosed(leaseB, new Request(domain, b, 8, request.requestId(), 0)));
            assertCode(ProtectionException.Code.WRITE_FENCED, () -> allocator.reserveOpen(request));
            assertCode(ProtectionException.Code.WRITE_FENCED, () -> allocator.reserveClosed(leaseA,
                    new Request(domain, new Generation(a.key(), 2), 8, UUID.randomUUID(), 1)));
            guard.release(leaseA);
            var renewed = guard.acquireForTenant(binding, "a", optionsA, false);
            assertTrue(renewed.epoch() > first.controlEpoch());
            assertCode(ProtectionException.Code.LEASE_LOST, () -> allocator.reserveClosed(leaseA, request));
            assertCode(ProtectionException.Code.RESERVATION_CONFLICT, () -> independent.reserveClosed(renewed, request));
            assertEquals(new JdbcFreshIdentityAllocator.Status(107, 1), allocator.provisionClosed(renewed, 1, domain));
            var second = independent.reserveClosed(renewed, new Request(domain, a, 8, UUID.randomUUID(), 1));
            var third = allocator.reserveClosed(leaseB, new Request(domain, b, 8, UUID.randomUUID(), 2));
            assertEquals(first.last() + 1, second.first()); assertEquals(second.last() + 1, third.first());
            assertCode(ProtectionException.Code.RESERVATION_CONFLICT, () -> allocator.reserveClosed(renewed, request));
            var changedDomain = new FreshIdentityDomain(domain.scopeToken(), domain.bindingFingerprint(),
                    domain.authorityId(), domain.first(), domain.last() + 1, domain.exclusiveReservationToken());
            assertCode(ProtectionException.Code.OPERATION_CONFLICT, () -> allocator.provisionClosed(leaseB, 1, changedDomain));

            // Two pods with different tenant locks still compete on ONE namespace row/CAS position.
            var pool = Executors.newFixedThreadPool(2);
            var start = new CountDownLatch(1);
            try {
                var left = pool.submit(() -> compete(start, independent, renewed, new Request(domain, a, 4, UUID.randomUUID(), 3)));
                var right = pool.submit(() -> compete(start, allocator, leaseB, new Request(domain, b, 4, UUID.randomUUID(), 3)));
                start.countDown();
                var results = List.of(left.get(15, TimeUnit.SECONDS), right.get(15, TimeUnit.SECONDS));
                assertEquals(1, results.stream().filter(FreshIdentityRange.class::isInstance).count());
                assertEquals(1, results.stream().filter(ProtectionException.Code.RESERVATION_CONFLICT::equals).count());
                var winner = (FreshIdentityRange) results.stream().filter(FreshIdentityRange.class::isInstance).findFirst().orElseThrow();
                assertEquals(third.last() + 1, winner.first()); assertEquals(127, winner.last());
            } finally {
                pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
            }
            // Reprovision/closure/reacquisition never resets global highwater or returns burned numbers.
            assertEquals(new JdbcFreshIdentityAllocator.Status(127, 4), allocator.provisionClosed(renewed, 1, domain));
            assertEquals(new JdbcFreshIdentityAllocator.Status(127, 4), allocator.statusClosed(leaseB, 1, domain));
            assertThrows(ProtectionException.class, () -> new SourcePlan("cachedb_identity_allocator", "last_tenant", "highwater"));
            var rolledBack = new Request(domain, a, 4, UUID.randomUUID(), 4);
            var beforeCommitFailure = commitFault(f, false);
            assertCode(ProtectionException.Code.STORAGE_UNAVAILABLE, () -> beforeCommitFailure.reserveClosed(renewed, rolledBack));
            assertEquals(new JdbcFreshIdentityAllocator.Status(127, 4), allocator.statusClosed(renewed, 1, domain));
            assertEquals(128, allocator.reserveClosed(renewed, rolledBack).first());
            var lostReply = new Request(domain, a, 4, UUID.randomUUID(), 5);
            var afterCommitFailure = commitFault(f, true);
            assertCode(ProtectionException.Code.STORAGE_UNAVAILABLE, () -> afterCommitFailure.reserveClosed(renewed, lostReply));
            var recovered = independent.reserveClosed(renewed, lostReply);
            assertEquals(132, recovered.first()); assertEquals(135, recovered.last());
            assertEquals(recovered, allocator.reserveClosed(renewed, lostReply));
            assertExhaustion(f, guard, allocator);
            assertEquals(new JdbcFreshIdentityAllocator.Status(135, 6), allocator.statusClosed(renewed, 1, domain));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"postgres", "mssql", "oracle"})
    void closedBindingMismatchIsRejectedBeforeInsertOrAllocation(String provider) throws Exception {
        try (var admin = CacheTenantContext.administrative(); var f = new InitialSqlSandbox(provider)) {
            var binding = new Binding(String.class, "fresh-binding-" + f.name,
                    new SourcePlan("orders", "tenant_id", "id"), ProtectionKey.hash("layout"), 128, 16, 4096, true);
            var guard = new JdbcTenantPurgeGuard(f.source, f.dialect, 2, 2);
            var allocator = new JdbcFreshIdentityAllocator(guard, f.dialect, 2);
            var domain = FreshIdentityDomain.exclusivelyReserved(binding, UUID.randomUUID(), 100, 199,
                    ProtectionKey.hash("fixture-exclusive-domain-no-external-writers"));
            var generation = new Generation(binding.key("a"), 1);
            guard.provisionClosed(generation);
            var lease = guard.acquireForTenant(binding, "a", options("a"), false);
            var mismatches = mismatchedDomains(binding, domain);
            for (var mismatch : mismatches) {
                assertClosedBindingRejected(allocator, lease, generation, mismatch, 0);
                assertAllocatorRows(f, domain, 0);
            }
            var empty = new JdbcFreshIdentityAllocator.Status(99, 0);
            assertEquals(empty, allocator.provisionClosed(lease, 1, domain));
            for (var mismatch : mismatches) {
                assertClosedBindingRejected(allocator, lease, generation, mismatch, 0);
                assertEquals(empty, allocator.statusClosed(lease, 1, domain));
                assertAllocatorRows(f, domain, 1);
            }
            var first = allocator.reserveClosed(lease, new Request(domain, generation, 8, UUID.randomUUID(), 0));
            assertEquals(100, first.first()); assertEquals(107, first.last());
            var allocated = new JdbcFreshIdentityAllocator.Status(107, 1);
            for (var mismatch : mismatches) {
                assertClosedBindingRejected(allocator, lease, generation, mismatch, 1);
                assertEquals(allocated, allocator.statusClosed(lease, 1, domain));
                assertAllocatorRows(f, domain, 1);
            }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"postgres", "mssql", "oracle"})
    @EnabledIfSystemProperty(named = "cachedb.snapshot.redis", matches = "true")
    @EnabledIfSystemProperty(named = "cachedb.initial.isolatedSql", matches = "true")
    void initialBindingMismatchIsRejectedBeforeInsert(String provider) throws Exception {
        try (var admin = CacheTenantContext.administrative();
                var f = new CacheInitialNativeBridgeProvidersRedisTest.Fixture(provider)) {
            var namespace = f.database.prepareInitialNamespace(f.metadata, f.binding, f.guard, f.ids,
                    1L, 2L, 2, 65536, 16384, 1048576, 1);
            var descriptor = namespace.descriptor();
            var producer = namespace.bootstrap(new JdbcNamespaceBootstrapGate(f.guard,
                    Set.of(descriptor.namespaceFingerprint())), f.generation);
            assertEquals(descriptor.incarnationId(), producer.runtimeId());
            var versions = new JdbcTenantVersionAllocator(f.guard, f.sandbox.dialect, 2);
            var sql = new JdbcInitialTenantBootstrap<>(f.guard, versions, namespace.window(), f.ids,
                    ProtectionKey.hash("fresh-identity-initial-fixture"), 2, Set.of(namespace.window().fingerprint()));
            var request = new InitialTenantBootstrap.Request(f.generation, "a", UUID.randomUUID(),
                    ProtectionKey.hash("fresh-identity-initial-operator"), 120);
            var session = sql.acquire(request);
            try {
                assertEquals(InitialTenantBootstrap.Phase.CLOSURE, sql.phase(session));
                var allocator = new JdbcFreshIdentityAllocator(f.guard, f.sandbox.dialect, 2);
                var domain = FreshIdentityDomain.exclusivelyReserved(f.binding, UUID.randomUUID(), 100, 199,
                        ProtectionKey.hash("fixture-exclusive-domain-no-external-writers"));
                var mismatches = mismatchedDomains(f.binding, domain);
                for (var mismatch : mismatches) {
                    assertCode(ProtectionException.Code.OPERATION_CONFLICT,
                            () -> allocator.provisionInitial(session, mismatch));
                    assertAllocatorRows(f.sandbox, domain, 0);
                }
                var empty = new JdbcFreshIdentityAllocator.Status(99, 0);
                assertEquals(empty, allocator.provisionInitial(session, domain));
                for (var mismatch : mismatches) {
                    assertCode(ProtectionException.Code.OPERATION_CONFLICT,
                            () -> allocator.provisionInitial(session, mismatch));
                    assertEquals(empty, allocator.provisionInitial(session, domain));
                    assertAllocatorRows(f.sandbox, domain, 1);
                }
                assertEquals(InitialTenantBootstrap.Phase.CLOSURE, sql.phase(session));
                assertEquals(0, f.guard.inInitialBootstrapTransaction(session, c -> {
                    try (var q = c.prepareStatement("SELECT COUNT(*) FROM cachedb_purge_progress WHERE scope_token=?")) {
                        q.setQueryTimeout(2); q.setString(1, domain.scopeToken());
                        try (var rows = q.executeQuery()) { assertTrue(rows.next()); return rows.getInt(1); }
                    }
                }).intValue());
            } finally {
                sql.release(session);
            }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"postgres", "mssql", "oracle"})
    void epochZeroReceiptCorruptionRequiresRecovery(String provider) throws Exception {
        try (var admin = CacheTenantContext.administrative(); var f = new InitialSqlSandbox(provider)) {
            var binding = new Binding(String.class, "fresh-corrupt-" + f.name,
                    new SourcePlan("orders", "tenant_id", "id"), ProtectionKey.hash("layout"), 128, 16, 4096, true);
            var guard = new JdbcTenantPurgeGuard(f.source, f.dialect, 2, 2);
            var allocator = new JdbcFreshIdentityAllocator(guard, f.dialect, 2);
            var domain = FreshIdentityDomain.exclusivelyReserved(binding, UUID.randomUUID(), 100, 199,
                    ProtectionKey.hash("fixture-exclusive-domain-no-external-writers"));
            var generation = new Generation(binding.key("a"), 1);
            guard.provisionClosed(generation);
            var lease = guard.acquireForTenant(binding, "a", options("a"), false);
            var empty = new JdbcFreshIdentityAllocator.Status(99, 0);
            assertEquals(empty, allocator.provisionClosed(lease, 1, domain));
            String constraint = receiptConstraint(provider);
            ownedDdl(f, "ALTER TABLE cachedb_identity_allocator DROP CONSTRAINT ck_cdb_identity_receipt");
            try {
                // Zero is populated, not SQL NULL: catch accidental primitive JDBC coercion too.
                var populated = new LinkedHashMap<String, Object>();
                populated.put("last_request", UUID.randomUUID().toString());
                populated.put("last_tenant", generation.key().tenantToken());
                populated.put("last_generation", 0L); populated.put("last_control", 0L);
                populated.put("last_first", 0L); populated.put("last_count", 0);
                for (var field : populated.entrySet()) {
                    try {
                        corruptReceipt(f, domain, field.getKey(), field.getValue());
                        assertCode(ProtectionException.Code.RECOVERY_REQUIRED,
                                () -> allocator.statusClosed(lease, 1, domain));
                    } finally {
                        clearReceipt(f, domain);
                    }
                    assertEquals(empty, allocator.statusClosed(lease, 1, domain));
                }
            } finally {
                clearReceipt(f, domain);
                ownedDdl(f, "ALTER TABLE cachedb_identity_allocator ADD " + constraint);
            }
            assertEquals(empty, allocator.statusClosed(lease, 1, domain));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"postgres", "mssql", "oracle"})
    @EnabledIfSystemProperty(named = "cachedb.snapshot.redis", matches = "true")
    @EnabledIfSystemProperty(named = "cachedb.initial.isolatedSql", matches = "true")
    void strictOpenReservationsNeverReplayCommittedRequestsAcrossIndependentAllocators(String provider) throws Exception {
        try (var admin = CacheTenantContext.administrative();
                var f = new CacheInitialNativeBridgeProvidersRedisTest.Fixture(provider)) {
            var namespace = f.database.prepareInitialNamespace(f.metadata, f.binding, f.guard, f.ids,
                    1L, 2L, 2, 65536, 16384, 1048576, 1);
            namespace.bootstrap(new JdbcNamespaceBootstrapGate(f.guard,
                    Set.of(namespace.descriptor().namespaceFingerprint())), f.generation);
            var enrollment = f.database.prepareTenantProtection(1);
            var plan = controlPlan(f, f.database, enrollment, namespace.window());
            var descriptor = enrollment.descriptor("NativeInitialRow");
            var approval = new RedisTenantRuntimeControl.ApprovedRoster(
                    ProtectionKey.hash("fresh-strict-review:" + f.run), plan.manifestToken(),
                    descriptor.coverageToken(), List.of(descriptor.runtimeId()), List.of(f.generation.key()));
            var service = namespace.createBootstrap(enrollment, plan, approval, 64,
                    Set.of(namespace.window().fingerprint()));
            var initial = new InitialTenantBootstrap.Request(f.generation, "a", UUID.randomUUID(),
                    ProtectionKey.hash("fresh-strict-operator:" + f.run), 120);
            var domain = FreshIdentityDomain.exclusivelyReserved(f.binding, UUID.randomUUID(),
                    1_000_000, 1_000_999, ProtectionKey.hash("isolated-exclusive-domain:" + f.run));
            var allocator = new JdbcFreshIdentityAllocator(f.guard, f.sandbox.dialect, 2);
            var independent = new JdbcFreshIdentityAllocator(
                    new JdbcTenantPurgeGuard(f.source, f.sandbox.dialect, 2, 2), f.sandbox.dialect, 2);
            var firstRequest = new Request(domain, f.generation, 8, UUID.randomUUID(), 0);
            service.provisionIdentityDomain(initial, domain);
            assertCode(ProtectionException.Code.WRITE_FENCED, () -> allocator.reserveOnceOpen(firstRequest));
            boolean active = false;
            for (int call = 0; call < 512; call++) {
                var progress = service.step(initial);
                assertNotEquals(InitialTenantBootstrap.Phase.INCOMPLETE, progress.phase());
                if (progress.phase() == InitialTenantBootstrap.Phase.ACTIVE) { active = true; break; }
            }
            assertTrue(active, "Actual root INITIAL must reach ACTIVE within its finite continuation budget");

            var first = allocator.reserveOnceOpen(firstRequest);
            assertEquals(1_000_000, first.first()); assertEquals(1_000_007, first.last());
            assertEquals(1, first.reservationEpoch()); assertTrue(first.controlEpoch() > 0);
            assertCode(ProtectionException.Code.RESERVATION_CONFLICT, () -> independent.reserveOnceOpen(firstRequest));
            assertCode(ProtectionException.Code.RESERVATION_CONFLICT, () -> independent.reserveOnceOpen(
                    new Request(domain, f.generation, 8, firstRequest.requestId(), 1)));
            assertEquals(new JdbcFreshIdentityAllocator.Status(first.last(), 1), allocator.statusOpen(f.generation, domain));
            assertEquals(first, independent.reserveOpen(firstRequest), "Ordinary exact replay remains unchanged");

            var replayableRequest = new Request(domain, f.generation, 8, UUID.randomUUID(), 1);
            var replayable = allocator.reserveOpen(replayableRequest);
            assertEquals(first.last() + 1, replayable.first()); assertEquals(1_000_015, replayable.last());
            assertEquals(replayable, independent.reserveOpen(replayableRequest));
            assertCode(ProtectionException.Code.RESERVATION_CONFLICT, () -> allocator.reserveOnceOpen(replayableRequest));
            assertCode(ProtectionException.Code.RESERVATION_CONFLICT, () -> allocator.reserveOnceOpen(firstRequest));
            var wrongGeneration = new Generation(f.generation.key(), f.generation.value() + 1);
            assertCode(ProtectionException.Code.WRITE_FENCED, () -> allocator.reserveOnceOpen(
                    new Request(domain, wrongGeneration, 8, UUID.randomUUID(), 2)));
            for (var mismatch : mismatchedDomains(f.binding, domain)) {
                assertCode(ProtectionException.Code.OPERATION_CONFLICT, () -> allocator.reserveOnceOpen(
                        new Request(mismatch, f.generation, 8, UUID.randomUUID(), 2)));
            }
            assertEquals(new JdbcFreshIdentityAllocator.Status(replayable.last(), 2), allocator.statusOpen(f.generation, domain));

            var rolledBack = new Request(domain, f.generation, 8, UUID.randomUUID(), 2);
            assertCode(ProtectionException.Code.STORAGE_UNAVAILABLE, () -> commitFault(f.sandbox, false).reserveOnceOpen(rolledBack));
            assertEquals(new JdbcFreshIdentityAllocator.Status(replayable.last(), 2), allocator.statusOpen(f.generation, domain));
            var committed = allocator.reserveOnceOpen(rolledBack);
            assertEquals(replayable.last() + 1, committed.first()); assertEquals(1_000_023, committed.last());
            assertEquals(3, committed.reservationEpoch());
            assertCode(ProtectionException.Code.RESERVATION_CONFLICT, () -> independent.reserveOnceOpen(rolledBack));

            var lostReply = new Request(domain, f.generation, 8, UUID.randomUUID(), 3);
            assertCode(ProtectionException.Code.STORAGE_UNAVAILABLE, () -> commitFault(f.sandbox, true).reserveOnceOpen(lostReply));
            assertEquals(new JdbcFreshIdentityAllocator.Status(1_000_031, 4), allocator.statusOpen(f.generation, domain));
            assertCode(ProtectionException.Code.RESERVATION_CONFLICT, () -> independent.reserveOnceOpen(lostReply));
            assertCode(ProtectionException.Code.RESERVATION_CONFLICT, () -> independent.reserveOnceOpen(
                    new Request(domain, f.generation, 8, lostReply.requestId(), 4)));
            var historical = independent.reserveOpen(lostReply);
            assertEquals(committed.last() + 1, historical.first()); assertEquals(1_000_031, historical.last());
            assertEquals(4, historical.reservationEpoch());

            var concurrent = new Request(domain, f.generation, 8, UUID.randomUUID(), 4);
            var pool = Executors.newFixedThreadPool(2);
            try {
                var start = new CountDownLatch(1);
                var left = pool.submit(() -> competeOnceOpen(start, allocator, concurrent));
                var right = pool.submit(() -> competeOnceOpen(start, independent, concurrent));
                start.countDown();
                var results = List.of(left.get(15, TimeUnit.SECONDS), right.get(15, TimeUnit.SECONDS));
                assertEquals(1, results.stream().filter(FreshIdentityRange.class::isInstance).count());
                assertEquals(1, results.stream().filter(ProtectionException.Code.RESERVATION_CONFLICT::equals).count());
                var winner = (FreshIdentityRange) results.stream().filter(FreshIdentityRange.class::isInstance).findFirst().orElseThrow();
                assertEquals(historical.last() + 1, winner.first()); assertEquals(1_000_039, winner.last());
                assertEquals(5, winner.reservationEpoch()); assertEquals(first.controlEpoch(), winner.controlEpoch());
                assertEquals(winner, independent.reserveOpen(concurrent));
            } finally {
                pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
            }
            assertCode(ProtectionException.Code.RESERVATION_CONFLICT, () -> allocator.reserveOnceOpen(concurrent));
            assertCode(ProtectionException.Code.RESERVATION_CONFLICT, () -> independent.reserveOnceOpen(concurrent));
            assertEquals(new JdbcFreshIdentityAllocator.Status(1_000_039, 5), allocator.statusOpen(f.generation, domain));
        }
    }

    private static Object competeOnceOpen(CountDownLatch start, JdbcFreshIdentityAllocator allocator, Request request) throws Exception {
        try (var admin = CacheTenantContext.administrative()) {
            if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("Bounded contention start expired");
            try { return allocator.reserveOnceOpen(request); }
            catch (ProtectionException failure) {
                assertEquals(ProtectionException.Code.RESERVATION_CONFLICT, failure.code());
                return failure.code();
            }
        }
    }

    private static List<FreshIdentityDomain> mismatchedDomains(Binding binding, FreshIdentityDomain domain) {
        var source = new Binding(binding.entityType(), binding.scope(),
                new SourcePlan("other_orders", binding.source().tenantColumn(), binding.source().idColumn()),
                binding.layoutToken(), binding.maxWorkItems(), binding.maxKeysPerObject(), binding.maxBatchBytes(), true);
        var layout = new Binding(binding.entityType(), binding.scope(), binding.source(),
                ProtectionKey.hash("different-layout"), binding.maxWorkItems(), binding.maxKeysPerObject(), binding.maxBatchBytes(), true);
        return List.of(source, layout).stream().map(other -> {
            assertEquals(binding.token(), other.token());
            assertNotEquals(binding.fingerprint(), other.fingerprint());
            return FreshIdentityDomain.exclusivelyReserved(other, domain.authorityId(), domain.first(), domain.last(),
                    domain.exclusiveReservationToken());
        }).toList();
    }

    private static void assertClosedBindingRejected(JdbcFreshIdentityAllocator allocator, Lease lease,
            Generation generation, FreshIdentityDomain domain, long epoch) {
        assertCode(ProtectionException.Code.OPERATION_CONFLICT,
                () -> allocator.provisionClosed(lease, generation.value(), domain));
        assertCode(ProtectionException.Code.OPERATION_CONFLICT,
                () -> allocator.statusClosed(lease, generation.value(), domain));
        assertCode(ProtectionException.Code.OPERATION_CONFLICT,
                () -> allocator.reserveClosed(lease, new Request(domain, generation, 1, UUID.randomUUID(), epoch)));
    }

    private static void assertAllocatorRows(InitialSqlSandbox f, FreshIdentityDomain domain, int expected) throws SQLException {
        try (var c = f.source.getConnection()) {
            f.assertScope(c);
            try (var q = c.prepareStatement("SELECT COUNT(*) FROM cachedb_identity_allocator WHERE scope_token=?")) {
                q.setQueryTimeout(2); q.setString(1, domain.scopeToken());
                try (var rows = q.executeQuery()) { assertTrue(rows.next()); assertEquals(expected, rows.getInt(1)); }
            }
        }
    }

    private static void ownedDdl(InitialSqlSandbox f, String sql) throws SQLException {
        try (var c = f.source.getConnection()) {
            f.assertScope(c);
            try (var q = c.createStatement()) { q.setQueryTimeout(5); q.executeUpdate(sql); }
        }
    }

    private static void corruptReceipt(InitialSqlSandbox f, FreshIdentityDomain domain, String column, Object value) throws SQLException {
        if (!Set.of("last_request", "last_tenant", "last_generation", "last_control", "last_first", "last_count").contains(column))
            throw new AssertionError("Unexpected receipt column");
        try (var c = f.source.getConnection()) {
            f.assertScope(c);
            try (var q = c.prepareStatement("UPDATE cachedb_identity_allocator SET " + column + "=? WHERE scope_token=?")) {
                q.setQueryTimeout(2); q.setObject(1, value); q.setString(2, domain.scopeToken()); assertEquals(1, q.executeUpdate());
            }
        }
    }

    private static void clearReceipt(InitialSqlSandbox f, FreshIdentityDomain domain) throws SQLException {
        try (var c = f.source.getConnection()) {
            f.assertScope(c);
            try (var q = c.prepareStatement("UPDATE cachedb_identity_allocator SET last_request=NULL,last_tenant=NULL,"
                    + "last_generation=NULL,last_control=NULL,last_first=NULL,last_count=NULL WHERE scope_token=?")) {
                q.setQueryTimeout(2); q.setString(1, domain.scopeToken()); assertEquals(1, q.executeUpdate());
            }
        }
    }

    private static String receiptConstraint(String provider) throws Exception {
        String resource = "/com/reactor/cachedb/jdbc/migrations/" + provider + "/V019_7__fresh_identity_allocator.sql";
        try (var input = FreshIdentityAllocatorProvidersTest.class.getResourceAsStream(resource)) {
            assertNotNull(input);
            byte[] bytes = input.readNBytes(8193); assertTrue(bytes.length <= 8192);
            String migration = new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n");
            int start = migration.indexOf("CONSTRAINT ck_cdb_identity_receipt CHECK (");
            int end = migration.indexOf("\n);", start);
            assertTrue(start >= 0 && end > start);
            return migration.substring(start, end).strip();
        }
    }

    private static Object compete(CountDownLatch start, JdbcFreshIdentityAllocator allocator, Lease lease, Request request) throws Exception {
        try (var admin = CacheTenantContext.administrative()) {
            if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("Bounded contention start expired");
            try { return allocator.reserveClosed(lease, request); }
            catch (ProtectionException failure) {
                assertEquals(ProtectionException.Code.RESERVATION_CONFLICT, failure.code());
                return failure.code();
            }
        }
    }

    private static void assertExhaustion(InitialSqlSandbox f, JdbcTenantPurgeGuard guard, JdbcFreshIdentityAllocator allocator) {
        var binding = new Binding(String.class, "small-" + f.name, new SourcePlan("orders", "tenant_id", "id"),
                ProtectionKey.hash("layout"), 128, 16, 4096, true);
        var domain = FreshIdentityDomain.exclusivelyReserved(binding, UUID.randomUUID(),
                FreshIdentityDomain.MAX_IDENTITY - 2, FreshIdentityDomain.MAX_IDENTITY, ProtectionKey.hash("exclusive"));
        var generation = new Generation(binding.key("a"), 1);
        guard.provisionClosed(generation);
        var lease = guard.acquireForTenant(binding, "a", options("a"), false);
        allocator.provisionClosed(lease, 1, domain);
        var request = new Request(domain, generation, 3, UUID.randomUUID(), 0);
        var full = allocator.reserveClosed(lease, request);
        assertEquals(FreshIdentityDomain.MAX_IDENTITY, full.last());
        assertCode(ProtectionException.Code.VERSION_LIMIT,
                () -> allocator.reserveClosed(lease, new Request(domain, generation, 1, UUID.randomUUID(), 1)));
        assertEquals(full, allocator.reserveClosed(lease, request));
        assertEquals(new JdbcFreshIdentityAllocator.Status(full.last(), 1), allocator.statusClosed(lease, 1, domain));
    }

    private static PurgeOptions options(String tenant) {
        return new PurgeOptions(UUID.randomUUID(), tenant, ProtectionKey.hash("operator"), false, 128, 120, 5000,
                PurgeScope.ACTIVE_STORES_RETAINING_SECURITY_METADATA);
    }

    /** Real SQL transaction fault injection; never supplies a fence, owner, generation or result. */
    private static JdbcFreshIdentityAllocator commitFault(InitialSqlSandbox f, boolean afterCommit) {
        var once = new AtomicBoolean();
        var source = (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    Object value = invoke(f.source, method, args);
                    if (!(value instanceof Connection connection)) return value;
                    return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                            (p, operation, parameters) -> {
                                if (operation.getName().equals("commit") && once.compareAndSet(false, true)) {
                                    if (afterCommit) invoke(connection, operation, parameters);
                                    throw new SQLException("Injected commit outcome failure", "CDBTF");
                                }
                                return invoke(connection, operation, parameters);
                            });
                });
        return new JdbcFreshIdentityAllocator(new JdbcTenantPurgeGuard(source, f.dialect, 2, 2), f.dialect, 2);
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }

    private static void assertCode(ProtectionException.Code code, org.junit.jupiter.api.function.Executable work) {
        assertEquals(code, assertThrows(ProtectionException.class, work).code());
    }
}
