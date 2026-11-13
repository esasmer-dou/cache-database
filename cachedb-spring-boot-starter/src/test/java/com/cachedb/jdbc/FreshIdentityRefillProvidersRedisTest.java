package com.reactor.cachedb.jdbc;

import static com.reactor.cachedb.core.protection.ProtectionException.Code.*;
import static com.reactor.cachedb.jdbc.CacheInitialNativeBridgeProvidersRedisTest.controlPlan;
import static org.junit.jupiter.api.Assertions.*;

import com.reactor.cachedb.core.model.OperationType;
import com.reactor.cachedb.core.model.OptimisticWriteConflictException;
import com.reactor.cachedb.core.model.WriteReceipt;
import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.*;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.jdbc.CacheInitialNativeBridgeProvidersRedisTest.Fixture;
import com.reactor.cachedb.jdbc.CacheInitialNativeBridgeProvidersRedisTest.Row;
import com.reactor.cachedb.redis.RedisTenantRuntimeControl;
import com.reactor.cachedb.redis.RedisTenantVersionAllocator;
import com.reactor.cachedb.redis.BoundedRedisWire;
import com.reactor.cachedb.starter.CacheDataProtection;
import com.reactor.cachedb.starter.CacheDatabase;
import com.reactor.cachedb.starter.CacheInitialTenantBootstrap;
import com.reactor.cachedb.starter.CacheTenantRuntimeEnrollment;
import com.reactor.cachedb.starter.RedisTenantPurgePlan;
import java.net.InetSocketAddress;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Genuine root activation and public refill/admission paths; no fabricated generation or reservation. */
@EnabledIfSystemProperty(named = "cachedb.snapshot.providers", matches = "true")
@EnabledIfSystemProperty(named = "cachedb.snapshot.redis", matches = "true")
@EnabledIfSystemProperty(named = "cachedb.initial.isolatedSql", matches = "true")
@Timeout(180)
class FreshIdentityRefillProvidersRedisTest {
    private static final String NS = "native_rows", ENTITY = "NativeInitialRow";

    @ParameterizedTest(name = "{0}: INITIAL OPEN refill retains activation proof and permits an ordinary write")
    @ValueSource(strings = {"postgres", "mssql", "oracle"})
    void initialOpenRefillRetainsActivationProofAndAdmitsOrdinaryWrite(String provider) throws Exception {
        try (var admin = CacheTenantContext.administrative(); var f = new Fixture(provider)) {
            var active = activate(f);
            var original = open(active, f.generation);
            var proof = initialProof(f);
            var identities = ledger(f);
            var nativeBefore = nativeState(active, f.generation);
            long nativeEpoch = activationEpoch(original.grant());
            assertEquals(nativeEpoch, original.grant().range().controlEpoch());
            assertTrue(original.control() > nativeEpoch,
                    "Actual INITIAL finalization advances SQL control, not the immutable activation epoch");
            assertNativeTuple(active, f.generation, original.grant());

            var refill = active.versions().reserveOpen(f.generation, 8, UUID.randomUUID(),
                    original.grant().range().reservationEpoch(), original.grant().range().runId());
            var current = open(active, f.generation);
            assertRange(refill, current.grant().range());
            assertEquals(original.control(), refill.controlEpoch());
            assertTrue(refill.controlEpoch() > nativeEpoch);
            assertTrue(refill.first() > original.grant().range().last());
            assertEquals(original.grant().generationFields(), current.grant().generationFields());
            assertEquals(proof, initialProof(f));
            assertEquals(identities, ledger(f), "Version refill must not reserve entity identities");
            assertEquals(nativeBefore, nativeState(active, f.generation), "SQL reserve alone must not touch Redis");

            // Retry the committed SQL request through the real root API, without restamping its control.
            var status = active.versions().statusOpen(f.generation);
            var installedRange = active.service().refillVersionRange("a", f.generation, 8, refill.request(),
                    original.grant().range().reservationEpoch());
            assertRange(refill, installedRange);
            assertEquals(status, active.versions().statusOpen(f.generation), "Installation retry must not reserve again");
            assertNativeTuple(active, f.generation, current.grant());
            assertEquals(Long.toString(refill.controlEpoch()), nativeState(active, f.generation).allocator()
                    .get("reservation_control_epoch"));
            assertEquals(Long.toString(refill.first() - 1), counter(active, f.generation));
            var installed = nativeState(active, f.generation);
            assertCode(RECOVERY_REQUIRED, () -> active.service().installOpenRefill("a", original.grant().range()));
            assertEquals(installed, nativeState(active, f.generation), "Stale installation must not rewind native state");
            assertEquals(identities, ledger(f));
            assertAbsent(f, active.domain().first(), active.domain().first() + 3);

            var request = request(active, f.generation, 0);
            var admitted = active.service().reserveAndAdmitFreshRange("a", request);
            assertReservation(f, request, admitted, active.domain().first(), 1, refill.controlEpoch());
            assertAdmissionMarkers(f, admitted);
            assertEquals(installed, nativeState(active, f.generation), "Admission must not allocate a version");
            var accepted = ordinaryWrite(active, f.generation, admitted.first(), "initial-refill-" + f.run);
            assertTrue(accepted.version() >= refill.first() && accepted.version() <= refill.last());
            assertEquals(refill.first(), accepted.version());
            assertEquals(Long.toString(accepted.version()), counter(active, f.generation));
            assertQueuedWrite(active, accepted, f.generation);
            var afterWrite = nativeState(active, f.generation);
            active.service().installOpenRefill("a", refill);
            assertEquals(afterWrite, nativeState(active, f.generation), "Exact installation retry must not rewind a consumed counter");
            assertRange(refill, active.service().refillVersionRange("a", f.generation, 8, refill.request(),
                    original.grant().range().reservationEpoch()));
            assertEquals(afterWrite, nativeState(active, f.generation));
            var committed = ledger(f);
            assertCode(RESERVATION_CONFLICT, () -> active.service().reserveAndAdmitFreshRange("a", request));
            assertEquals(committed, ledger(f));
            assertEquals(proof, initialProof(f));
            assertEquals(f.originalSource, f.sourceSnapshot(), "No worker flush belongs to this foreground regression");
        }
    }

    @ParameterizedTest(name = "{0}: native refill mismatch burns only committed identity reservations")
    @ValueSource(strings = {"postgres", "mssql", "oracle"})
    void uninstalledRefillAndStaleRequestsNeverClaimOwnersOrReissueIds(String provider) throws Exception {
        try (var admin = CacheTenantContext.administrative(); var f = new Fixture(provider)) {
            var active = activate(f);
            var original = open(active, f.generation);
            var proof = initialProof(f);
            var untouched = ledger(f);
            var nativeBefore = nativeState(active, f.generation);
            var stale = request(active, f.generation, 1);
            assertCode(RESERVATION_CONFLICT, () -> active.service().reserveAndAdmitFreshRange("a", stale));
            assertEquals(untouched, ledger(f), "Rejected CAS must not burn an unreserved interval");
            assertAbsent(f, active.domain().first(), active.domain().first() + 7);

            var refill = active.versions().reserveOpen(f.generation, 8, UUID.randomUUID(),
                    original.grant().range().reservationEpoch(), original.grant().range().runId());
            var current = open(active, f.generation);
            assertRange(refill, current.grant().range());
            assertEquals(original.grant().generationFields(), current.grant().generationFields());
            assertNotEquals(refill.request().toString(), nativeBefore.allocator().get("request"));
            assertEquals(nativeBefore, nativeState(active, f.generation));
            assertEquals(untouched, ledger(f));

            var first = request(active, f.generation, 0);
            assertCode(RECOVERY_REQUIRED, () -> active.service().reserveAndAdmitFreshRange("a", first));
            assertCommitted(f, first, active.domain().first(), 1, current.control());
            var burned = ledger(f);
            assertAbsent(f, active.domain().first(), active.domain().first() + 7);
            assertEquals(nativeBefore, nativeState(active, f.generation));
            assertTrue(queuedWrites(f).isEmpty());
            assertCode(RESERVATION_CONFLICT, () -> active.service().reserveAndAdmitFreshRange("a", first));
            assertCode(RESERVATION_CONFLICT, () -> active.service().reserveAndAdmitFreshRange("a",
                    new FreshIdentityRange.Request(active.domain(), f.generation, 4, first.requestId(), 1)));
            assertCode(RESERVATION_CONFLICT, () -> active.service().reserveAndAdmitFreshRange("a", request(active, f.generation, 0)));
            assertEquals(burned, ledger(f), "Replays and stale epochs must not burn a second interval");

            var next = request(active, f.generation, 1);
            assertCode(RECOVERY_REQUIRED, () -> active.service().reserveAndAdmitFreshRange("a", next));
            assertCommitted(f, next, active.domain().first() + 4, 2, current.control());
            assertEquals(untouched.subList(0, 6), ledger(f).subList(0, 6));
            assertAbsent(f, active.domain().first(), active.domain().first() + 7);
            assertEquals(nativeBefore, nativeState(active, f.generation));
            assertTrue(queuedWrites(f).isEmpty());
            assertEquals(proof, initialProof(f));
            assertEquals(f.originalSource, f.sourceSnapshot());
        }
    }

    @ParameterizedTest(name = "{0}: actual EMPTY lease handoff keeps its frozen range and accepts an OPEN refill")
    @ValueSource(strings = {"postgres", "mssql", "oracle"})
    void reacquiredEmptyActivationKeepsFrozenRangeAndAdmitsAfterOpenRefill(String provider) throws Exception {
        try (var admin = CacheTenantContext.administrative(); var f = new Fixture(provider)) {
            var active = activate(f);
            var identities = ledger(f);
            var facade = f.database.configureDataProtection(List.of(active.plan()), 1);
            var purge = new PurgeOptions(UUID.randomUUID(), "a", ProtectionKey.hash("refill-purge:" + f.run),
                    true, 128, 120, 5000, PurgeScope.ACTIVE_STORES_RETAINING_SECURITY_METADATA);
            var result = purge(facade, active, purge);
            assertEquals(identities, ledger(f));
            var request = new EmptyTenantActivation.Request(UUID.randomUUID(), EmptyTenantActivation.Mode.EMPTY,
                    purge, result.evidence(), active.plan().manifestToken());
            CacheDataProtection.Continuation continuation = null;
            EmptyTenantActivation.Checkpoint interrupted = null, activated = null;
            Lease abandoned = null;
            try {
                for (int call = 0; call < 64; call++) {
                    var step = facade.reenableEmptyTenant(Row.class, "a", request, continuation);
                    continuation = step.continuation();
                    var checkpoint = step.checkpoint();
                    if (checkpoint.phase() == EmptyTenantActivation.Phase.ACTIVE) {
                        activated = checkpoint;
                        break;
                    }
                    assertNotNull(continuation);
                    if (interrupted == null && checkpoint.phase() == EmptyTenantActivation.Phase.SQL_OPEN) {
                        interrupted = checkpoint;
                        abandoned = continuation.lease();
                        facade.abandon(continuation);
                        continuation = null;
                    }
                    active.enrollment().pollControl(1, 1000);
                }
                assertNotNull(interrupted, "The real SQL_OPEN crash boundary must be reached");
                assertNotNull(activated, "Public EMPTY recovery must reach ACTIVE within its finite budget");
                assertNull(continuation);
            } finally {
                if (continuation != null) facade.abandon(continuation);
            }
            assertNotNull(abandoned);
            var staleLease = abandoned;
            assertCode(LEASE_LOST, () -> f.guard.load(staleLease));
            assertTrue(activated.attemptEpoch() > interrupted.attemptEpoch());
            assertEquals(interrupted.identity(), activated.identity());
            assertEquals(interrupted.receiptToken(), activated.receiptToken());
            assertRange(interrupted.reservation(), activated.reservation());
            assertEquals(identities, ledger(f));
            var generation = new Generation(activated.identity().key(), activated.identity().toGeneration());
            var frozen = open(active, generation);
            assertRange(activated.reservation(), frozen.grant().range());
            assertEquals(activated.attemptEpoch(), frozen.control());
            assertEquals(activated.attemptEpoch(), activationEpoch(frozen.grant()));
            assertTrue(frozen.grant().range().controlEpoch() < activationEpoch(frozen.grant()),
                    "Recovered EMPTY keeps the original SQL reservation while re-fencing native admission");
            assertNativeTuple(active, generation, frozen.grant());
            var beforeAdmission = nativeState(active, generation);
            var firstRequest = request(active, generation, 0);
            var first = active.service().reserveAndAdmitFreshRange("a", firstRequest);
            assertReservation(f, firstRequest, first, active.domain().first(), 1, activated.attemptEpoch());
            assertAdmissionMarkers(f, first);
            assertEquals(beforeAdmission, nativeState(active, generation));
            var identityReceipt = ledger(f);

            var refill = active.versions().reserveOpen(generation, 8, UUID.randomUUID(),
                    frozen.grant().range().reservationEpoch(), frozen.grant().range().runId());
            var current = open(active, generation);
            assertRange(refill, current.grant().range());
            assertEquals(activated.attemptEpoch(), refill.controlEpoch());
            assertTrue(refill.first() > frozen.grant().range().last());
            assertEquals(frozen.grant().generationFields(), current.grant().generationFields());
            active.service().installOpenRefill("a", refill);
            assertNativeTuple(active, generation, current.grant());
            assertEquals(Long.toString(refill.first() - 1), counter(active, generation));
            assertEquals(identityReceipt, ledger(f));
            var installed = nativeState(active, generation);
            assertCode(RECOVERY_REQUIRED, () -> active.service().installOpenRefill("a", frozen.grant().range()));
            assertEquals(installed, nativeState(active, generation));

            var nextRequest = request(active, generation, 1);
            var next = active.service().reserveAndAdmitFreshRange("a", nextRequest);
            assertReservation(f, nextRequest, next, first.last() + 1, 2, activated.attemptEpoch());
            assertAdmissionMarkers(f, next);
            assertEquals(installed, nativeState(active, generation));
            var accepted = ordinaryWrite(active, generation, next.first(), "empty-refill-" + f.run);
            assertEquals(refill.first(), accepted.version());
            assertQueuedWrite(active, accepted, generation);
            var afterWrite = nativeState(active, generation);
            active.service().installOpenRefill("a", refill);
            assertEquals(afterWrite, nativeState(active, generation));
            var replay = facade.reenableEmptyTenant(Row.class, "a", request);
            assertNull(replay.continuation());
            assertEquals(activated.identity(), replay.checkpoint().identity());
            assertEquals(activated.receiptToken(), replay.checkpoint().receiptToken());
            assertEquals(activated.attemptEpoch(), replay.checkpoint().attemptEpoch());
            assertEquals(activated.attemptOwner(), replay.checkpoint().attemptOwner());
            assertRange(activated.reservation(), replay.checkpoint().reservation());
            assertEquals(afterWrite, nativeState(active, generation));
            assertCommitted(f, nextRequest, next.first(), 2, activated.attemptEpoch());
            assertEquals(Map.of(3L, f.originalSource.get(3L)), f.sourceSnapshot());
        }
    }

    @ParameterizedTest(name = "{0}: OPEN refill rejects forged tuples and changed native activation proof")
    @ValueSource(strings = {"postgres", "mssql", "oracle"})
    void openRefillRejectsForgedRangesAndNativeProofChangesWithoutMutation(String provider) throws Exception {
        try (var admin = CacheTenantContext.administrative(); var f = new Fixture(provider);
                var acl = new RefillAcl(f)) {
            var active = activate(f, acl.wire);
            var original = open(active, f.generation);
            var refill = active.versions().reserveOpen(f.generation, 8, UUID.randomUUID(),
                    original.grant().range().reservationEpoch(), original.grant().range().runId());
            var identities = ledger(f);
            var proof = initialProof(f);
            var status = active.versions().statusOpen(f.generation);
            var before = nativeState(active, f.generation);
            var restamped = new TenantVersionRange(refill.key(), refill.generation(), activationEpoch(original.grant()),
                    refill.reservationEpoch(), refill.first(), refill.last(), refill.request(), refill.runId(), refill.inventory());
            assertCode(RECOVERY_REQUIRED, () -> active.service().installOpenRefill("a", restamped));
            var forged = new TenantVersionRange(refill.key(), refill.generation(), refill.controlEpoch(),
                    refill.reservationEpoch(), refill.first() + 1, refill.last(), refill.request(), refill.runId(), refill.inventory());
            assertCode(RECOVERY_REQUIRED, () -> active.service().installOpenRefill("a", forged));
            assertEquals(before, nativeState(active, f.generation));
            assertEquals(status, active.versions().statusOpen(f.generation));
            assertEquals(identities, ledger(f));
            assertCode(RECOVERY_REQUIRED, () -> active.versions().reserveOpenActivated(f.generation,
                    f.binding.fingerprint(), active.service().window().fingerprint(), ProtectionKey.hash("wrong-refill-manifest:" + f.run),
                    8, UUID.randomUUID(), refill.reservationEpoch(), refill.runId()));
            String wrongRun = (refill.runId().charAt(0) == 'a' ? "b" : "a") + refill.runId().substring(1);
            assertCode(RECOVERY_REQUIRED, () -> active.versions().reserveOpenActivated(f.generation,
                    f.binding.fingerprint(), active.service().window().fingerprint(), active.plan().manifestToken(),
                    8, UUID.randomUUID(), refill.reservationEpoch(), wrongRun));
            assertEquals(status, active.versions().statusOpen(f.generation),
                    "Retained ACTIVE proof must be validated before any reservation advances SQL high-water");

            String guard = active.enrollment().barrier(ENTITY).generationKey(f.generation.key());
            String allocator = active.nativeVersions().allocatorKey(f.generation.key());
            try {
                // Even making both native controls equal to SQL must not replace the retained activation epoch.
                f.redis.hset(guard, "epoch", Long.toString(refill.controlEpoch()));
                f.redis.hset(allocator, "control_epoch", Long.toString(refill.controlEpoch()));
                var changed = nativeState(active, f.generation);
                assertCode(RECOVERY_REQUIRED, () -> active.service().installOpenRefill("a", refill));
                assertEquals(changed, nativeState(active, f.generation));
            } finally {
                f.redis.hset(guard, "epoch", before.generation().get("epoch"));
                f.redis.hset(allocator, "control_epoch", before.allocator().get("control_epoch"));
            }
            try {
                f.redis.hset(guard, "i:active_range", "0".repeat(64));
                var changed = nativeState(active, f.generation);
                assertCode(RECOVERY_REQUIRED, () -> active.service().installOpenRefill("a", refill));
                assertEquals(changed, nativeState(active, f.generation));
            } finally { f.redis.hset(guard, "i:active_range", before.generation().get("i:active_range")); }
            String roster = RedisTenantRuntimeControl.rosterKey(f.database.redisKeyStrategy(), NS);
            var sealed = f.redis.hgetAll(roster);
            String peer = sealed.keySet().stream().filter(field -> field.startsWith("m:")).findFirst().orElseThrow();
            try {
                f.redis.hdel(roster, peer);
                var changed = Map.copyOf(f.redis.hgetAll(roster));
                assertCode(RECOVERY_REQUIRED, () -> active.service().installOpenRefill("a", refill));
                assertEquals(changed, f.redis.hgetAll(roster));
                assertEquals(before, nativeState(active, f.generation));
            } finally { f.redis.hset(roster, peer, sealed.get(peer)); }
            assertEquals(sealed, f.redis.hgetAll(roster));
            assertEquals(before, nativeState(active, f.generation));
            assertEquals(status, active.versions().statusOpen(f.generation));
            assertEquals(identities, ledger(f));
            assertEquals(proof, initialProof(f));
            assertAbsent(f, active.domain().first(), active.domain().first() + 7);

            for (String denied : List.of("hset", "set")) {
                acl.permission("-" + denied);
                try {
                    assertCode(RECOVERY_REQUIRED, () -> active.service().installOpenRefill("a", refill));
                    assertEquals(before, nativeState(active, f.generation), "ACL denial must not change header or counter");
                    assertEquals(sealed, f.redis.hgetAll(roster));
                    assertEquals(status, active.versions().statusOpen(f.generation));
                    assertEquals(identities, ledger(f));
                    assertEquals(proof, initialProof(f));
                    assertTrue(queuedWrites(f).isEmpty());
                } finally { acl.permission("+" + denied); }
            }
            active.service().installOpenRefill("a", refill);
            assertNativeTuple(active, f.generation, open(active, f.generation).grant());
            assertEquals(Long.toString(refill.first() - 1), counter(active, f.generation));
            var installed = nativeState(active, f.generation);
            active.service().installOpenRefill("a", refill);
            assertEquals(installed, nativeState(active, f.generation), "Exact retry after ACL recovery must not rewind state");
            assertCode(RESERVATION_CONFLICT, () -> active.service().refillVersionRange("a", f.generation, 8,
                    UUID.randomUUID(), original.grant().range().reservationEpoch()));
            assertEquals(installed, nativeState(active, f.generation));
            assertEquals(status, active.versions().statusOpen(f.generation));
            assertEquals(identities, ledger(f));
            assertTrue(queuedWrites(f).isEmpty());
            UUID nextRequest = UUID.randomUUID();
            var next = active.service().refillVersionRange("a", f.generation, 8, nextRequest, refill.reservationEpoch());
            assertEquals(refill.last() + 1, next.first());
            assertEquals(refill.reservationEpoch() + 1, next.reservationEpoch());
            assertEquals(refill.controlEpoch(), next.controlEpoch());
            var current = open(active, f.generation);
            assertRange(next, current.grant().range());
            assertEquals(original.grant().generationFields(), current.grant().generationFields());
            assertNativeTuple(active, f.generation, current.grant());
            assertEquals(Long.toString(next.first() - 1), counter(active, f.generation));
            var nextInstalled = nativeState(active, f.generation);
            assertRange(next, active.service().refillVersionRange("a", f.generation, 8, nextRequest, refill.reservationEpoch()));
            assertEquals(nextInstalled, nativeState(active, f.generation));
            assertCode(RECOVERY_REQUIRED, () -> active.service().installOpenRefill("a", refill));
            assertEquals(nextInstalled, nativeState(active, f.generation));
            assertEquals(identities, ledger(f));
            assertEquals(proof, initialProof(f));
        }
    }

    private record Active(Fixture fixture, CacheTenantRuntimeEnrollment enrollment, RedisTenantPurgePlan plan,
            CacheInitialTenantBootstrap<Row, Long> service, FreshIdentityDomain domain,
            JdbcTenantVersionAllocator versions, RedisTenantVersionAllocator nativeVersions) {}
    private record Open(long control, JdbcOpenActivationGrant grant) {}
    private record NativeState(Map<String, String> generation, Map<String, String> allocator,
            String counter, long generationTtl, long allocatorTtl, long counterTtl) {}

    private static Active activate(Fixture f) throws Exception {
        return activate(f, f.wire);
    }

    private static Active activate(Fixture f, BoundedRedisWire wire) throws Exception {
        var namespace = f.database.prepareInitialNamespace(f.metadata, f.binding, f.guard, f.ids,
                1L, 2L, 2, 65536, 16384, 1048576, 1);
        namespace.bootstrap(new JdbcNamespaceBootstrapGate(f.guard,
                Set.of(namespace.descriptor().namespaceFingerprint())), f.generation);
        var enrollment = f.database.prepareTenantProtection(1);
        var plan = wire == f.wire ? controlPlan(f, f.database, enrollment, namespace.window())
                : controlPlanWithWire(f, f.database, enrollment, namespace.window(), wire);
        var descriptor = enrollment.descriptor(ENTITY);
        var approval = new RedisTenantRuntimeControl.ApprovedRoster(
                ProtectionKey.hash("refill-review:" + f.run), plan.manifestToken(), descriptor.coverageToken(),
                List.of(descriptor.runtimeId()), List.of(f.generation.key()));
        var service = namespace.createBootstrap(enrollment, plan, approval, 64,
                Set.of(namespace.window().fingerprint()));
        var request = new InitialTenantBootstrap.Request(f.generation, "a", UUID.randomUUID(),
                ProtectionKey.hash("refill-operator:" + f.run), 120);
        // The fixture exclusively owns this interval; it is not inferred from a source MAX.
        var domain = FreshIdentityDomain.exclusivelyReserved(f.binding, UUID.randomUUID(),
                3_000_000, 3_000_999, ProtectionKey.hash("refill-exclusive-domain:" + f.run));
        assertEquals(new JdbcFreshIdentityAllocator.Status(domain.first() - 1, 0),
                service.provisionIdentityDomain(request, domain));
        boolean activated = false;
        for (int call = 0; call < 512; call++) {
            var progress = service.step(request);
            assertNotEquals(InitialTenantBootstrap.Phase.INCOMPLETE, progress.phase());
            if (progress.phase() == InitialTenantBootstrap.Phase.ACTIVE) {
                assertEquals(2, progress.warmedRows());
                activated = true;
                break;
            }
        }
        assertTrue(activated, "The genuine registered-root INITIAL must reach ACTIVE before refill");
        assertEquals("OPEN", f.redis.hget(enrollment.barrier(ENTITY).generationKey(f.generation.key()), "state"));
        assertEquals(f.originalSource, f.sourceSnapshot());
        assertTrue(queuedWrites(f).isEmpty());
        return new Active(f, enrollment, plan, service, domain,
                new JdbcTenantVersionAllocator(f.guard, f.sandbox.dialect, 2),
                new RedisTenantVersionAllocator(f.database.redisOperations(), f.database.redisKeyStrategy(),
                        enrollment.barrier(ENTITY), NS));
    }

    private static RedisTenantPurgePlan controlPlanWithWire(Fixture f, CacheDatabase root,
            CacheTenantRuntimeEnrollment enrollment, InitialSourceHotWindow<Row, Long> window, BoundedRedisWire wire) {
        var participants = new PurgeParticipantManifest(ENTITY,
                Set.of("runtime-control-roster", "initial-native-window", "initial-source-journal"),
                List.of(enrollment.controlParticipant(ENTITY), enrollment.initialSourceParticipant(f.metadata, window),
                        new JdbcInitialBootstrapPurgeParticipant(f.guard, f.binding, ENTITY, 2)));
        var routes = new LinkedHashMap<String, BoundedRedisWire>();
        var write = root.config().writeBehind();
        routes.put(write.streamKey(), wire); routes.put(write.deadLetterStreamKey(), wire);
        routes.put(write.compactionStreamKey(), wire);
        for (String stream : write.activeStreamKeys()) routes.put(stream, wire);
        return new RedisTenantPurgePlan(f.binding, ENTITY, "entity_version", f.guard,
                new JdbcTenantVersionAllocator(f.guard, f.sandbox.dialect, 2),
                new JdbcPurgeScanJournal(f.guard, f.sandbox.dialect, 2, 128, 10000, 10 * 1048576),
                new JdbcPurgeFacadeJournal(f.guard, f.sandbox.dialect, 2, 256, 10 * 1048576),
                new JdbcPurgeParticipantJournal(f.guard, 2, 1), wire, root.redisOperations(), root.redisKeyStrategy(),
                enrollment.barrier(ENTITY), enrollment.readFence(ENTITY), List.of(enrollment.pointProfileTarget(ENTITY)),
                Set.of(NS), Map.of(), routes, participants, event -> {});
    }

    private static final class RefillAcl implements AutoCloseable {
        private final Fixture fixture;
        private final String user = "refill_" + UUID.randomUUID().toString().replace("-", "");
        final BoundedRedisWire wire;
        RefillAcl(Fixture fixture) {
            this.fixture = fixture;
            char[] password = UUID.randomUUID().toString().toCharArray();
            wire = new BoundedRedisWire(new InetSocketAddress(
                    System.getProperty("cachedb.snapshot.redis.host", "redis"),
                    Integer.getInteger("cachedb.snapshot.redis.port", 6379)),
                    null, null, user, password, 0, 2000, 2000, 1);
            try {
                fixture.redis.sendCommand(redis.clients.jedis.Protocol.Command.ACL, "SETUSER", user,
                        "reset", "on", ">" + new String(password), "~" + fixture.prefix + "*", "+@all");
            } catch (RuntimeException failed) { wire.close(); throw failed; }
            finally { Arrays.fill(password, '\0'); }
        }
        void permission(String rule) {
            fixture.redis.sendCommand(redis.clients.jedis.Protocol.Command.ACL, "SETUSER", user, rule);
        }
        @Override public void close() {
            try { wire.close(); }
            finally { fixture.redis.sendCommand(redis.clients.jedis.Protocol.Command.ACL, "DELUSER", user); }
        }
    }

    private static Open open(Active active, Generation generation) {
        var f = active.fixture();
        return f.guard.inOpenVersionTransaction(generation, f.binding.fingerprint(), (connection, control) ->
                new Open(control, f.guard.readOpenActivationGrant(connection, generation, f.binding.fingerprint(),
                        active.service().window().fingerprint(), active.plan().manifestToken())));
    }

    private static long activationEpoch(JdbcOpenActivationGrant grant) {
        return Long.parseLong(grant.generationFields().get("epoch"));
    }

    private static FreshIdentityRange.Request request(Active active, Generation generation, long epoch) {
        return new FreshIdentityRange.Request(active.domain(), generation, 4, UUID.randomUUID(), epoch);
    }

    private static String counter(Active active, Generation generation) {
        return active.fixture().redis.get(active.nativeVersions().counterKey(generation.key()));
    }

    private static NativeState nativeState(Active active, Generation generation) {
        var f = active.fixture();
        String guard = active.enrollment().barrier(ENTITY).generationKey(generation.key());
        String allocator = active.nativeVersions().allocatorKey(generation.key());
        String counter = active.nativeVersions().counterKey(generation.key());
        return new NativeState(Map.copyOf(f.redis.hgetAll(guard)), Map.copyOf(f.redis.hgetAll(allocator)),
                f.redis.get(counter), f.redis.pttl(guard), f.redis.pttl(allocator), f.redis.pttl(counter));
    }

    private static void assertNativeTuple(Active active, Generation generation, JdbcOpenActivationGrant grant) {
        var state = nativeState(active, generation);
        var range = grant.range();
        grant.generationFields().forEach((field, value) -> assertEquals(value, state.generation().get(field)));
        assertEquals("OPEN", state.generation().get("state"));
        assertEquals(range.runId(), state.generation().get("run_id"));
        assertEquals(Long.toString(generation.value()), state.generation().get("generation"));
        assertEquals(Long.toString(generation.value()), state.allocator().get("generation"));
        assertEquals(grant.generationFields().get("epoch"), state.allocator().get("control_epoch"));
        if (state.allocator().containsKey("reservation_control_epoch"))
            assertEquals(Long.toString(range.controlEpoch()), state.allocator().get("reservation_control_epoch"));
        assertEquals(range.runId(), state.allocator().get("run_id"));
        assertEquals(range.inventory(), state.allocator().get("inventory"));
        assertEquals(range.request().toString(), state.allocator().get("request"));
        assertEquals(Long.toString(range.reservationEpoch()), state.allocator().get("reservation_epoch"));
        assertEquals(Long.toString(range.first() - 1), state.allocator().get("floor"));
        assertEquals(Long.toString(range.last()), state.allocator().get("ceiling"));
        assertEquals(-1L, state.generationTtl());
        assertEquals(-1L, state.allocatorTtl());
        assertEquals(-1L, state.counterTtl());
    }

    private static void assertRange(TenantVersionRange expected, TenantVersionRange actual) {
        assertNotNull(actual);
        assertEquals(expected.key(), actual.key());
        assertEquals(expected.generation(), actual.generation());
        assertEquals(expected.controlEpoch(), actual.controlEpoch());
        assertEquals(expected.reservationEpoch(), actual.reservationEpoch());
        assertEquals(expected.first(), actual.first());
        assertEquals(expected.last(), actual.last());
        assertEquals(expected.request(), actual.request());
        assertEquals(expected.runId(), actual.runId());
        assertEquals(expected.inventory(), actual.inventory());
    }

    private static void assertReservation(Fixture f, FreshIdentityRange.Request request, FreshIdentityRange range,
            long first, long epoch, long control) throws SQLException {
        assertEquals(request, range.request());
        assertEquals(first, range.first());
        assertEquals(first + request.count() - 1, range.last());
        assertEquals(epoch, range.reservationEpoch());
        assertEquals(control, range.controlEpoch());
        assertCommitted(f, request, first, epoch, control);
    }

    private static void assertCommitted(Fixture f, FreshIdentityRange.Request request, long first,
            long epoch, long control) throws SQLException {
        var row = ledger(f);
        assertEquals(Long.toString(first + request.count() - 1), row.get(6));
        assertEquals(Long.toString(epoch), row.get(7));
        assertEquals(request.requestId().toString(), row.get(8));
        assertEquals(request.generation().key().tenantToken(), row.get(9));
        assertEquals(Long.toString(request.generation().value()), row.get(10));
        assertEquals(Long.toString(control), row.get(11));
        assertEquals(Long.toString(first), row.get(12));
        assertEquals(Integer.toString(request.count()), row.get(13));
    }

    private static List<String> identityKeys(Fixture f, long id) {
        var keys = f.database.redisKeyStrategy();
        return List.of(keys.entityKey(NS, id), keys.versionKey(NS, id), keys.versionKey(NS, id) + ":tenant",
                keys.tombstoneKey(NS, id), keys.indexMetaKey(NS, id), keys.durabilityKey(NS, id),
                keys.tenantHotOwnerKey(NS, id), keys.compactionPayloadKey(NS, id), keys.compactionPendingKey(NS, id));
    }

    private static void assertAbsent(Fixture f, long first, long last) {
        for (long id = first; id <= last; id++)
            for (String key : identityKeys(f, id)) assertFalse(f.redis.exists(key), "Rejected admission must not create any identity key");
    }

    private static void assertAdmissionMarkers(Fixture f, FreshIdentityRange range) {
        for (long id = range.first(); id <= range.last(); id++) {
            String version = f.database.redisKeyStrategy().versionKey(NS, id);
            String owner = version + ":tenant";
            assertEquals("a", f.redis.get(owner));
            assertEquals("0", f.redis.get(version));
            assertEquals(-1L, f.redis.pttl(owner));
            assertEquals(-1L, f.redis.pttl(version));
            for (String key : identityKeys(f, id))
                if (!key.equals(owner) && !key.equals(version))
                    assertFalse(f.redis.exists(key), "Admission creates owner/zero-version markers, not entity writes");
        }
    }

    private static WriteReceipt<Row, Long> ordinaryWrite(Active active, Generation generation, long id, String secret) {
        var f = active.fixture();
        var row = new Row(id, "a", secret);
        var accepted = new AtomicReference<WriteReceipt<Row, Long>>();
        int before = f.source.calls.get();
        active.enrollment().deliver(f.metadata, "a", repository -> repository.save(row, 0), accepted::set);
        var receipt = accepted.get();
        assertNotNull(receipt);
        assertEquals(row, receipt.entity());
        assertEquals(row.id(), receipt.id());
        assertEquals(OperationType.UPSERT, receipt.operationType());
        assertEquals(NS, receipt.redisNamespace());
        active.enrollment().deliver(f.metadata, "a", repository -> repository.findById(id),
                found -> assertEquals(row, found.orElseThrow()));
        assertEquals(before, f.source.calls.get(), "An ordinary enrolled write/read must not borrow foreground SQL");
        var keys = f.database.redisKeyStrategy();
        assertEquals("a", f.redis.get(keys.versionKey(NS, id) + ":tenant"));
        assertEquals(Long.toString(receipt.version()), f.redis.get(keys.versionKey(NS, id)));
        String payload = f.redis.get(keys.entityKey(NS, id));
        assertNotNull(payload);
        assertEquals(row, f.bound.fromRedisValue(payload));
        assertFalse(payload.contains(secret));
        var nativeBeforeRetry = nativeState(active, generation);
        var queueBeforeRetry = queuedWrites(f);
        var compactionBeforeRetry = f.redis.hgetAll(keys.compactionPayloadKey(NS, id));
        var compactionStatsBeforeRetry = f.redis.hgetAll(keys.compactionStatsKey(NS));
        String pendingBeforeRetry = f.redis.get(keys.compactionPendingKey(NS, id));
        var conflict = assertThrows(OptimisticWriteConflictException.class, () -> active.enrollment().deliver(
                f.metadata, "a", repository -> repository.save(row, 0),
                ignored -> fail("Repeated zero-CAS must not deliver a receipt")));
        assertEquals(0, conflict.expectedVersion());
        assertEquals(receipt.version(), conflict.actualVersion());
        assertEquals(nativeBeforeRetry, nativeState(active, generation), "Rejected zero-CAS must not consume a version");
        assertEquals(queueBeforeRetry, queuedWrites(f), "Rejected zero-CAS must not enqueue a write");
        assertEquals(compactionBeforeRetry, f.redis.hgetAll(keys.compactionPayloadKey(NS, id)));
        assertEquals(compactionStatsBeforeRetry, f.redis.hgetAll(keys.compactionStatsKey(NS)));
        assertEquals(pendingBeforeRetry, f.redis.get(keys.compactionPendingKey(NS, id)));
        assertEquals(payload, f.redis.get(keys.entityKey(NS, id)));
        assertEquals("a", f.redis.get(keys.versionKey(NS, id) + ":tenant"));
        assertEquals(Long.toString(receipt.version()), f.redis.get(keys.versionKey(NS, id)));
        assertEquals(before, f.source.calls.get(), "Rejected zero-CAS must not borrow foreground SQL");
        return receipt;
    }

    private static List<Map<String, String>> queuedWrites(Fixture f) {
        var queued = new ArrayList<Map<String, String>>();
        for (String stream : f.database.config().writeBehind().activeStreamKeys()) {
            assertTrue(f.redis.xlen(stream) <= 16);
            for (var entry : f.redis.xrange(stream, new redis.clients.jedis.StreamEntryID("0-0"),
                    new redis.clients.jedis.StreamEntryID(Long.MAX_VALUE, Long.MAX_VALUE), 16)) queued.add(entry.getFields());
        }
        return List.copyOf(queued);
    }

    private static void assertQueuedWrite(Active active, WriteReceipt<Row, Long> receipt, Generation generation) {
        var f = active.fixture();
        var queued = queuedWrites(f);
        assertEquals(1, queued.size());
        var frame = queued.getFirst();
        assertEquals(receipt.id().toString(), frame.get("id"));
        assertEquals(NS, frame.get("namespace"));
        assertEquals(Long.toString(receipt.version()), frame.get("version"));
        assertEquals(Long.toString(generation.value()), frame.get("tenantGeneration"));
        assertEquals(generation.key().tenantToken(), frame.get("tenantToken"));
        assertEquals(generation.key().scopeToken(), frame.get("generationScope"));
        var body = f.database.config().writeBehind().compactionActive()
                ? f.redis.hgetAll(f.database.redisKeyStrategy().compactionPayloadKey(NS, receipt.id())) : frame;
        assertEquals("UPSERT", body.get("type"));
        assertEquals(ENTITY, body.get("entity"));
        assertEquals(Long.toString(receipt.version()), body.get("version"));
        assertEquals(Long.toString(generation.value()), body.get("tenantGeneration"));
        assertEquals("a", body.get("col:tenant_id"));
        String secret = body.get("col:secret");
        assertNotNull(secret);
        assertTrue(secret.contains("~E1:k2:"));
        assertFalse(secret.contains(receipt.entity().secret()));
    }

    private static CacheDataProtection.Result purge(CacheDataProtection facade, Active active, PurgeOptions options) {
        CacheDataProtection.Continuation continuation = null;
        try {
            for (int call = 0; call < 1024; call++) {
                var result = facade.purgeTenant(Row.class, "a", options, continuation);
                continuation = result.continuation();
                assertNotEquals(CacheDataProtection.Status.INCOMPLETE, result.status());
                assertNotEquals(CacheDataProtection.Status.MIGRATION_REQUIRED, result.status());
                if (result.status() == CacheDataProtection.Status.COMPLETE) {
                    assertEquals(Stage.COMPLETE, result.receipt().stage());
                    assertEquals(3, result.receipt().sourceRows());
                    assertEquals(2, result.receipt().redisRows());
                    assertNotNull(result.evidence());
                    assertTrue(queuedWrites(active.fixture()).isEmpty());
                    return result;
                }
            }
            throw new AssertionError("Actual purge exceeded its bounded continuation budget");
        } finally {
            if (continuation != null) facade.abandon(continuation);
        }
    }

    private static List<String> initialProof(Fixture f) throws Exception {
        try (var connection = f.source.getConnection(); var query = connection.prepareStatement(
                "SELECT payload,payload_bytes,revision,activation_token FROM cachedb_initial_bootstrap WHERE "
                        + InitialSqlTokens.pair(f.sandbox.dialect, false))) {
            query.setQueryTimeout(2);
            query.setString(1, f.generation.key().scopeToken());
            query.setString(2, f.generation.key().tenantToken());
            try (var rows = query.executeQuery()) {
                assertTrue(rows.next());
                try (var input = rows.getBinaryStream(1)) {
                    assertNotNull(input);
                    byte[] payload = input.readNBytes(InitialBootstrapJournalCodec.MAX_BYTES + 1);
                    assertTrue(payload.length <= InitialBootstrapJournalCodec.MAX_BYTES);
                    assertEquals(rows.getInt(2), payload.length);
                    var result = List.of(HexFormat.of().formatHex(payload), rows.getString(2), rows.getString(3), rows.getString(4));
                    assertFalse(rows.next());
                    return result;
                }
            }
        }
    }

    private static List<String> ledger(Fixture f) throws SQLException {
        try (var connection = f.source.getConnection(); var query = connection.prepareStatement(
                "SELECT scope_token,binding_token,authority_id,domain_first,domain_last,exclusive_token,"
                        + "highwater,reservation_epoch,last_request,last_tenant,last_generation,last_control,last_first,last_count"
                        + " FROM cachedb_identity_allocator WHERE scope_token=" + InitialSqlTokens.char64(f.sandbox.dialect))) {
            query.setQueryTimeout(2);
            query.setString(1, f.generation.key().scopeToken());
            try (var rows = query.executeQuery()) {
                assertTrue(rows.next());
                var result = new ArrayList<String>(14);
                for (int column = 1; column <= 14; column++) result.add(rows.getString(column));
                assertFalse(rows.next());
                return Collections.unmodifiableList(result);
            }
        }
    }

    private static void assertCode(ProtectionException.Code code, Executable work) {
        assertEquals(code, assertThrows(ProtectionException.class, work).code());
    }
}
