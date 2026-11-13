package com.reactor.cachedb.jdbc;

import static com.reactor.cachedb.jdbc.CacheInitialNativeBridgeProvidersRedisTest.controlPlan;
import static com.reactor.cachedb.core.protection.ProtectionException.Code.*;
import static org.junit.jupiter.api.Assertions.*;

import com.reactor.cachedb.core.model.WriteReceipt;
import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.InitialTenantBootstrap.Phase;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.*;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.jdbc.CacheInitialNativeBridgeProvidersRedisTest.Row;
import com.reactor.cachedb.redis.RedisTenantRuntimeControl;
import com.reactor.cachedb.starter.CacheDataProtection;
import com.reactor.cachedb.starter.CacheTenantRuntimeEnrollment;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real root INITIAL, native Redis and source purge; SQL reservations are not entity-write grants. */
@EnabledIfSystemProperty(named = "cachedb.snapshot.providers", matches = "true")
@EnabledIfSystemProperty(named = "cachedb.snapshot.redis", matches = "true")
@EnabledIfSystemProperty(named = "cachedb.initial.isolatedSql", matches = "true")
@Timeout(180)
class FreshIdentityNativeLifecycleProvidersRedisTest {
    private static final String NS = "native_rows", ENTITY = "NativeInitialRow";

    @ParameterizedTest(name = "{0}: INITIAL, purge, EMPTY activation and namespace nonreuse")
    @ValueSource(strings = {"postgres", "mssql", "oracle"})
    void initialActivePurgeRetainsNamespaceNonreuseAndFencesOldAuthority(String provider) throws Exception {
        try (var admin = CacheTenantContext.administrative();
                var f = new CacheInitialNativeBridgeProvidersRedisTest.Fixture(provider)) {
            var namespace = f.database.prepareInitialNamespace(f.metadata, f.binding, f.guard, f.ids,
                    1L, 2L, 2, 65536, 16384, 1048576, 1);
            namespace.bootstrap(new JdbcNamespaceBootstrapGate(f.guard,
                    Set.of(namespace.descriptor().namespaceFingerprint())), f.generation);
            var enrollment = f.database.prepareTenantProtection(1);
            var plan = controlPlan(f, f.database, enrollment, namespace.window());
            var descriptor = enrollment.descriptor(ENTITY);
            var approval = new RedisTenantRuntimeControl.ApprovedRoster(
                    ProtectionKey.hash("fresh-lifecycle-review:" + f.run), plan.manifestToken(),
                    descriptor.coverageToken(), List.of(descriptor.runtimeId()), List.of(f.generation.key()));
            var service = namespace.createBootstrap(enrollment, plan, approval, 64,
                    Set.of(namespace.window().fingerprint()));
            var initial = new InitialTenantBootstrap.Request(f.generation, "a", UUID.randomUUID(),
                    ProtectionKey.hash("fresh-lifecycle-operator:" + f.run), 120);
            // Only this isolated fixture owns this interval; no source-MAX/external-history claim.
            var domain = FreshIdentityDomain.exclusivelyReserved(f.binding, UUID.randomUUID(),
                    1_000_000, 1_000_999, ProtectionKey.hash("isolated-exclusive-domain:" + f.run));
            var allocator = new JdbcFreshIdentityAllocator(f.guard, f.sandbox.dialect, 2);
            var firstRequest = new FreshIdentityRange.Request(domain, f.generation, 8, UUID.randomUUID(), 0);

            var provisioned = service.provisionIdentityDomain(initial, domain);
            assertEquals(new JdbcFreshIdentityAllocator.Status(domain.first() - 1, 0), provisioned);
            var unallocated = ledger(f);
            assertEquals("999999", unallocated.get(6));
            assertEquals("0", unallocated.get(7));
            assertEquals(provisioned, service.provisionIdentityDomain(initial, domain));
            assertEquals(unallocated, ledger(f), "Provision retry must not advance or replace the domain");
            assertEquals("CLOSED", f.redis.hget(enrollment.barrier(ENTITY)
                    .generationKey(f.generation.key()), "state"));
            assertCode(WRITE_FENCED, () -> allocator.reserveOpen(firstRequest));
            assertEquals(f.originalSource, f.sourceSnapshot());

            boolean active = false;
            for (int call = 0; call < 512; call++) {
                var progress = service.step(initial);
                assertNotEquals(Phase.INCOMPLETE, progress.phase());
                if (progress.phase() == Phase.ACTIVE) {
                    assertEquals(2, progress.warmedRows());
                    active = true;
                    break;
                }
            }
            assertTrue(active, "Actual root INITIAL must reach ACTIVE within its finite continuation budget");
            assertEquals("OPEN", f.redis.hget(enrollment.barrier(ENTITY)
                    .generationKey(f.generation.key()), "state"));
            assertEquals(f.originalSource, f.sourceSnapshot());
            var callback = new AtomicBoolean();
            String wrongBinding = ProtectionKey.hash("not-the-attested-binding:" + f.run);
            assertCode(OPERATION_CONFLICT, () -> f.guard.inOpenVersionTransaction(f.generation,
                    wrongBinding, (connection, epoch) -> { callback.set(true); return null; }));
            assertFalse(callback.get(), "OPEN binding rejection must happen before allocator work");

            var reserved = allocator.reserveOpen(firstRequest);
            assertEquals(1_000_000, reserved.first());
            assertEquals(1_000_007, reserved.last());
            assertEquals(1, reserved.reservationEpoch());
            assertTrue(reserved.controlEpoch() > 0);
            assertEquals(reserved, allocator.reserveOpen(firstRequest), "Only the exact latest retry may replay");
            var retained = new JdbcFreshIdentityAllocator.Status(reserved.last(), reserved.reservationEpoch());
            assertEquals(retained, allocator.statusOpen(f.generation, domain));
            var wrongGeneration = new Generation(f.generation.key(), f.generation.value() + 1);
            assertCode(WRITE_FENCED, () -> allocator.statusOpen(wrongGeneration, domain));
            var beforePurge = ledger(f);

            var facade = f.database.configureDataProtection(List.of(plan), 1);
            var options = options("a", f.run);
            CacheDataProtection.Continuation continuation = null;
            CacheDataProtection.Result result = null;
            Lease purgeLease = null;
            try {
                for (int call = 0; call < 1024; call++) {
                    result = facade.purgeTenant(CacheInitialNativeBridgeProvidersRedisTest.Row.class,
                            "a", options, continuation);
                    continuation = result.continuation();
                    assertNotEquals(CacheDataProtection.Status.INCOMPLETE, result.status());
                    assertNotEquals(CacheDataProtection.Status.MIGRATION_REQUIRED, result.status());
                    if (purgeLease == null && continuation != null) {
                        purgeLease = continuation.lease();
                        assertTrue(purgeLease.epoch() > reserved.controlEpoch());
                        assertEquals(retained, allocator.statusClosed(purgeLease, 1, domain));
                        assertCode(WRITE_FENCED, () -> allocator.reserveOpen(firstRequest));
                        var currentLease = purgeLease;
                        assertCode(RESERVATION_CONFLICT, () -> allocator.reserveClosed(currentLease, firstRequest));
                        assertCode(WRITE_FENCED, () -> allocator.reserveClosed(currentLease,
                                new FreshIdentityRange.Request(domain, wrongGeneration, 8, UUID.randomUUID(), 1)));
                        assertCode(OPERATION_CONFLICT, () -> f.guard.inClosedVersionTransaction(currentLease, 1,
                                wrongBinding, (connection, epoch) -> { callback.set(true); return null; }));
                        assertFalse(callback.get(), "CLOSED binding rejection must precede allocator work");
                    }
                    assertEquals(beforePurge, ledger(f), "No purge phase may mutate the global nonreuse ledger");
                    if (result.status() == CacheDataProtection.Status.COMPLETE) break;
                }
                assertNotNull(result);
                assertNotNull(purgeLease, "Purge must expose its genuine bounded continuation lease");
                assertEquals(CacheDataProtection.Status.COMPLETE, result.status(), "Bounded real purge did not finish");
                assertEquals(Stage.COMPLETE, result.receipt().stage());
                assertEquals(3, result.receipt().sourceRows(), "Both hot rows and cold source row 99 must be erased");
                assertEquals(2, result.receipt().redisRows());
                assertNotNull(result.evidence());
            } finally {
                if (continuation != null) facade.abandon(continuation);
            }

            assertEquals(Map.of(3L, f.originalSource.get(3L)), f.sourceSnapshot());
            var keys = f.database.redisKeyStrategy();
            for (long id : List.of(1L, 2L, 99L)) {
                for (String key : List.of(keys.entityKey(NS, id), keys.versionKey(NS, id),
                        keys.versionKey(NS, id) + ":tenant", keys.indexMetaKey(NS, id)))
                    assertFalse(f.redis.exists(key), "Actual purge must erase owned payload/version/owner/index state");
            }
            assertEquals("CLOSED", f.redis.hget(enrollment.barrier(ENTITY)
                    .generationKey(f.generation.key()), "state"));
            assertEquals(beforePurge, ledger(f));
            assertCode(WRITE_FENCED, () -> allocator.reserveOpen(firstRequest));

            // Reacquire the actual completed operation, not a fabricated purge progress row.
            var reacquired = f.guard.acquireForTenant(f.binding, "a", options, false);
            try {
                assertTrue(reacquired.epoch() > purgeLease.epoch());
                var staleLease = purgeLease;
                assertCode(LEASE_LOST, () -> allocator.statusClosed(staleLease, 1, domain));
                assertEquals(retained, allocator.statusClosed(reacquired, 1, domain));
                assertCode(RESERVATION_CONFLICT, () -> allocator.reserveClosed(reacquired, firstRequest));
                var changedAuthority = FreshIdentityDomain.exclusivelyReserved(f.binding, UUID.randomUUID(),
                        domain.first(), domain.last(), domain.exclusiveReservationToken());
                assertCode(OPERATION_CONFLICT, () -> allocator.provisionClosed(reacquired, 1, changedAuthority));
                assertEquals(beforePurge, ledger(f), "Neither lease handoff nor reprovision may rewind the ledger");
            } finally { f.guard.release(reacquired); }

            var emptyRequest = new EmptyTenantActivation.Request(UUID.randomUUID(), EmptyTenantActivation.Mode.EMPTY,
                    options, result.evidence(), plan.manifestToken());
            EmptyTenantActivation.Checkpoint activated = null;
            CacheDataProtection.Continuation activationContinuation = null;
            try {
                for (int call = 0; call < 64; call++) {
                    var activation = facade.reenableEmptyTenant(CacheInitialNativeBridgeProvidersRedisTest.Row.class,
                            "a", emptyRequest, activationContinuation);
                    activationContinuation = activation.continuation();
                    var checkpoint = activation.checkpoint();
                    assertEquals(f.generation.value(), checkpoint.identity().fromGeneration());
                    assertEquals(f.generation.value() + 1, checkpoint.identity().toGeneration());
                    assertEquals(beforePurge, ledger(f), "EMPTY activation must not consume or reset fresh identities");
                    if (checkpoint.phase() == EmptyTenantActivation.Phase.ACTIVE) {
                        activated = checkpoint;
                        break;
                    }
                    assertNotNull(activationContinuation);
                    var preparing = new FreshIdentityRange.Request(domain,
                            new Generation(checkpoint.identity().key(), checkpoint.identity().toGeneration()),
                            8, UUID.randomUUID(), reserved.reservationEpoch());
                    assertCode(checkpoint.phase() == EmptyTenantActivation.Phase.PREPARED
                            ? WRITE_FENCED : RECOVERY_REQUIRED, () -> allocator.reserveOpen(preparing));
                    // The actual enrolled root must ACK this new generation, not reuse its g1 ACK.
                    enrollment.pollControl(1, 1000);
                }
                assertNotNull(activated, "Explicit EMPTY must reach ACTIVE within the fixed continuation budget");
                assertNull(activationContinuation);
            } finally {
                if (activationContinuation != null) facade.abandon(activationContinuation);
            }

            var reopened = new Generation(activated.identity().key(), activated.identity().toGeneration());
            assertTrue(activated.attemptEpoch() > reacquired.epoch());
            assertEquals("OPEN", f.redis.hget(enrollment.barrier(ENTITY).generationKey(reopened.key()), "state"));
            assertEquals(Long.toString(reopened.value()),
                    f.redis.hget(enrollment.barrier(ENTITY).generationKey(reopened.key()), "generation"));
            assertCode(WRITE_FENCED, () -> allocator.reserveOpen(firstRequest));
            assertCode(LEASE_LOST, () -> allocator.statusClosed(reacquired, f.generation.value(), domain));
            assertEquals(retained, allocator.statusOpen(reopened, domain));
            var reboundOldRequest = new FreshIdentityRange.Request(domain, reopened, firstRequest.count(),
                    firstRequest.requestId(), firstRequest.expectedReservationEpoch());
            assertCode(RESERVATION_CONFLICT, () -> allocator.reserveOpen(reboundOldRequest));
            assertEquals(beforePurge, ledger(f), "Old reservation identity cannot be rebound to g2");

            var reopenedRequest = new FreshIdentityRange.Request(domain, reopened, 8, UUID.randomUUID(),
                    reserved.reservationEpoch());
            var reopenedRange = allocator.reserveOpen(reopenedRequest);
            assertEquals(reserved.last() + 1, reopenedRange.first());
            assertEquals(reopenedRange.first() + 7, reopenedRange.last());
            assertEquals(activated.attemptEpoch(), reopenedRange.controlEpoch());
            assertEquals(reserved.reservationEpoch() + 1, reopenedRange.reservationEpoch());
            var afterReopen = ledger(f);
            assertEquals(reopenedRange, allocator.reserveOpen(reopenedRequest));
            assertEquals(afterReopen, ledger(f), "Exact g2 retry must preserve its committed receipt");
            var replay = facade.reenableEmptyTenant(CacheInitialNativeBridgeProvidersRedisTest.Row.class,
                    "a", emptyRequest);
            assertCheckpointEquivalent(activated, replay.checkpoint());
            assertNull(replay.continuation());
            assertEquals(afterReopen, ledger(f), "Completed EMPTY replay must not rewind the fresh range");
            assertEquals(beforePurge.subList(0, 6), afterReopen.subList(0, 6));

            // A second tenant uses a real CLOSED administrative lease; this is not native writer enrollment.
            var other = new Generation(f.binding.key("fresh-b"), 1);
            f.guard.provisionClosed(other);
            var otherLease = f.guard.acquireForTenant(f.binding, "fresh-b", options("fresh-b", f.run), false);
            try {
                assertEquals(new JdbcFreshIdentityAllocator.Status(reopenedRange.last(), reopenedRange.reservationEpoch()),
                        allocator.provisionClosed(otherLease, 1, domain));
                var next = allocator.reserveClosed(otherLease, new FreshIdentityRange.Request(domain, other,
                        8, UUID.randomUUID(), reopenedRange.reservationEpoch()));
                assertEquals(reopenedRange.last() + 1, next.first());
                assertEquals(next.first() + 7, next.last());
                assertEquals(3, next.reservationEpoch());
                assertEquals(new JdbcFreshIdentityAllocator.Status(next.last(), 3),
                        allocator.statusClosed(otherLease, 1, domain));
                assertEquals(new JdbcFreshIdentityAllocator.Status(next.last(), 3), allocator.statusOpen(reopened, domain));
                assertEquals(beforePurge.subList(0, 6), ledger(f).subList(0, 6), "The namespace domain is immutable");
            } finally { f.guard.release(otherLease); }
            assertEquals(Map.of(3L, f.originalSource.get(3L)), f.sourceSnapshot());
            f.evidence("PASS freshIdentity=ROOT_INITIAL_ACTIVE_PURGE_EMPTY realRootProvision=true openReservation=true"
                    + " globalLedgerRetained=true staleControlFenced=true staleLeaseFenced=true"
                    + " emptyReenable=true newGenerationReservationRetry=true newTenantNonoverlap=true entityWriteGrant=false");
        }
    }

    @ParameterizedTest(name = "{0}: admitted fresh owners, ordinary receipts, purge and EMPTY writes")
    @ValueSource(strings = {"postgres", "mssql", "oracle"})
    void admittedFreshOwnersEnableOrdinaryWritesAndArePurgedBeforeEmptyGenerationWrites(String provider) throws Exception {
        try (var admin = CacheTenantContext.administrative();
                var f = new CacheInitialNativeBridgeProvidersRedisTest.Fixture(provider)) {
            var namespace = f.database.prepareInitialNamespace(f.metadata, f.binding, f.guard, f.ids,
                    1L, 2L, 2, 65536, 16384, 1048576, 1);
            namespace.bootstrap(new JdbcNamespaceBootstrapGate(f.guard,
                    Set.of(namespace.descriptor().namespaceFingerprint())), f.generation);
            var enrollment = f.database.prepareTenantProtection(1);
            var plan = controlPlan(f, f.database, enrollment, namespace.window());
            var descriptor = enrollment.descriptor(ENTITY);
            var approval = new RedisTenantRuntimeControl.ApprovedRoster(
                    ProtectionKey.hash("fresh-writer-review:" + f.run), plan.manifestToken(),
                    descriptor.coverageToken(), List.of(descriptor.runtimeId()), List.of(f.generation.key()));
            var service = namespace.createBootstrap(enrollment, plan, approval, 64,
                    Set.of(namespace.window().fingerprint()));
            var initial = new InitialTenantBootstrap.Request(f.generation, "a", UUID.randomUUID(),
                    ProtectionKey.hash("fresh-writer-operator:" + f.run), 120);
            var domain = FreshIdentityDomain.exclusivelyReserved(f.binding, UUID.randomUUID(),
                    2_000_000, 2_000_999, ProtectionKey.hash("isolated-exclusive-writer-domain:" + f.run));
            service.provisionIdentityDomain(initial, domain);
            boolean active = false;
            for (int call = 0; call < 512; call++) {
                var progress = service.step(initial);
                assertNotEquals(Phase.INCOMPLETE, progress.phase());
                if (progress.phase() == Phase.ACTIVE) { active = true; break; }
            }
            assertTrue(active, "Actual root INITIAL must reach ACTIVE before fresh owner admission");
            f.guard.inOpenVersionTransaction(f.generation, f.binding.fingerprint(), (connection, control) -> {
                var grant = f.guard.readOpenActivationGrant(connection, f.generation, f.binding.fingerprint(),
                        service.window().fingerprint(), plan.manifestToken());
                assertTrue(control > grant.range().controlEpoch(), "Genuine INITIAL finalization advances SQL control beyond its immutable native grant");
                assertNativeGrantTuple(f, enrollment, grant, f.generation);
                return null;
            });
            var allocator = new JdbcFreshIdentityAllocator(f.guard, f.sandbox.dialect, 2);
            var request = new FreshIdentityRange.Request(domain, f.generation, 4, UUID.randomUUID(), 0);
            int beforeInvalid = f.source.calls.get();
            assertCode(INVALID_REQUEST, () -> service.reserveAndAdmitFreshRange("b", request));
            assertCode(INVALID_REQUEST, () -> service.reserveAndAdmitFreshRange("a",
                    new FreshIdentityRange.Request(domain, f.generation, 129, UUID.randomUUID(), 0)));
            assertEquals(beforeInvalid, f.source.calls.get(), "Invalid tenant/count must fail before borrowing SQL");
            assertEquals(new JdbcFreshIdentityAllocator.Status(domain.first() - 1, 0), allocator.statusOpen(f.generation, domain));
            assertTrue(queuedWrites(f).isEmpty());

            String versionCounter = enrollment.barrier(ENTITY).versionAllocatorKey(f.generation.key()) + ":next";
            String beforeAdmission = f.redis.get(versionCounter);
            var admitted = service.reserveAndAdmitFreshRange("a", request);
            assertEquals(request, admitted.request());
            assertEquals(domain.first(), admitted.first()); assertEquals(domain.first() + 3, admitted.last());
            assertEquals(1, admitted.reservationEpoch()); assertTrue(admitted.controlEpoch() > 0);
            var keys = f.database.redisKeyStrategy();
            for (long id = admitted.first(); id <= admitted.last(); id++) {
                String owner = keys.versionKey(NS, id) + ":tenant";
                assertEquals("a", f.redis.get(owner)); assertEquals(-1, f.redis.pttl(owner));
                String version = keys.versionKey(NS, id);
                assertEquals("0", f.redis.get(version)); assertEquals(-1, f.redis.pttl(version));
                for (String key : identityKeys(f, id))
                    if (!key.equals(owner) && !key.equals(version))
                        assertFalse(f.redis.exists(key), "Admission creates only persistent owner/zero-version pairs");
            }
            assertTrue(queuedWrites(f).isEmpty(), "Admission is not a write receipt or queue operation");
            assertEquals(beforeAdmission, f.redis.get(versionCounter), "Zero markers do not consume native versions");
            int beforeUnwrittenRead = f.source.calls.get();
            enrollment.deliver(f.metadata, "a", repository -> repository.findById(admitted.first()),
                    found -> assertTrue(found.isEmpty(), "A zero-version marker is not an entity"));
            assertEquals(beforeUnwrittenRead, f.source.calls.get(), "Unwritten admitted IDs do not read through to SQL");
            var firstWrite = saveAndReadWithoutSql(f, enrollment, new Row(admitted.first(), "a", "fresh-g1-" + f.run));
            assertEquals(Long.parseLong(beforeAdmission) + 1, firstWrite.version());
            assertQueuedWrite(f, firstWrite, f.generation);
            assertEquals(f.originalSource, f.sourceSnapshot(), "Without a worker, accepted Redis writes must not alter SQL source");
            var afterAdmission = ledger(f);
            assertCode(RESERVATION_CONFLICT, () -> service.reserveAndAdmitFreshRange("a", request));
            assertEquals(afterAdmission, ledger(f), "A committed admission must never replay its SQL reservation");

            var collisionRequest = new FreshIdentityRange.Request(domain, f.generation, 4, UUID.randomUUID(), 1);
            long burnedFirst = admitted.last() + 1, burnedLast = burnedFirst + 3;
            String collision = keys.entityKey(NS, burnedFirst + 1), sentinel = "adversarial-existing-payload:" + f.run;
            // Deliberate collision input, never an owner or a substitute for native admission.
            f.redis.set(collision, sentinel);
            try {
                assertCode(RECOVERY_REQUIRED, () -> service.reserveAndAdmitFreshRange("a", collisionRequest));
                assertEquals(sentinel, f.redis.get(collision));
                for (long id = burnedFirst; id <= burnedLast; id++)
                    for (String key : identityKeys(f, id))
                        if (!key.equals(collision)) assertFalse(f.redis.exists(key), "One collision must prevent every owner claim");
                assertEquals(new JdbcFreshIdentityAllocator.Status(burnedLast, 2), allocator.statusOpen(f.generation, domain));
                var burnedReceipt = ledger(f);
                assertCode(RESERVATION_CONFLICT, () -> service.reserveAndAdmitFreshRange("a", collisionRequest));
                assertEquals(burnedReceipt, ledger(f), "Ambiguous/rejected native admission cannot reissue a committed range");
                assertQueuedWrite(f, firstWrite, f.generation);
            } finally { f.redis.del(collision); }
            var retained = ledger(f);
            for (long id = admitted.first() + 1; id <= admitted.last(); id++) {
                String owner = keys.versionKey(NS, id) + ":tenant";
                assertEquals("a", f.redis.get(owner));
                String version = keys.versionKey(NS, id);
                assertEquals("0", f.redis.get(version)); assertEquals(-1, f.redis.pttl(version));
                for (String key : identityKeys(f, id))
                    if (!key.equals(owner) && !key.equals(version))
                        assertFalse(f.redis.exists(key), "Unused IDs must retain only owner/zero-version pairs before purge");
            }

            var facade = f.database.configureDataProtection(List.of(plan), 1);
            var purge = options("a", f.run);
            CacheDataProtection.Continuation continuation = null;
            CacheDataProtection.Result result = null;
            try {
                for (int call = 0; call < 1024; call++) {
                    result = facade.purgeTenant(Row.class, "a", purge, continuation);
                    continuation = result.continuation();
                    assertNotEquals(CacheDataProtection.Status.INCOMPLETE, result.status());
                    assertNotEquals(CacheDataProtection.Status.MIGRATION_REQUIRED, result.status());
                    if (result.status() == CacheDataProtection.Status.COMPLETE) break;
                }
                assertNotNull(result); assertEquals(CacheDataProtection.Status.COMPLETE, result.status());
                assertEquals(Stage.COMPLETE, result.receipt().stage()); assertNotNull(result.evidence());
                assertEquals(3, result.receipt().sourceRows());
                assertEquals(6, result.receipt().redisRows(), "Two initial hot rows plus all four admitted identities, including three unwritten IDs");
            } finally { if (continuation != null) facade.abandon(continuation); }
            for (long id = admitted.first(); id <= admitted.last(); id++)
                for (String key : identityKeys(f, id)) assertFalse(f.redis.exists(key), "Real purge must discover unused owner/zero-version identities");
            assertTrue(queuedWrites(f).isEmpty(), "Actual purge must remove the old-generation queued write");
            assertEquals(Map.of(3L, f.originalSource.get(3L)), f.sourceSnapshot());
            assertEquals(retained, ledger(f), "Neither used nor unused identities may be returned to the global allocator");
            assertEquals("CLOSED", f.redis.hget(enrollment.barrier(ENTITY).generationKey(f.generation.key()), "state"));
            assertCode(WRITE_FENCED, () -> service.reserveAndAdmitFreshRange("a", request));

            var empty = new EmptyTenantActivation.Request(UUID.randomUUID(), EmptyTenantActivation.Mode.EMPTY,
                    purge, result.evidence(), plan.manifestToken());
            EmptyTenantActivation.Checkpoint activated = null;
            CacheDataProtection.Continuation activating = null;
            try {
                for (int call = 0; call < 64; call++) {
                    var activation = facade.reenableEmptyTenant(Row.class, "a", empty, activating);
                    activating = activation.continuation();
                    if (activation.checkpoint().phase() == EmptyTenantActivation.Phase.ACTIVE) {
                        activated = activation.checkpoint(); break;
                    }
                    assertNotNull(activating); enrollment.pollControl(1, 1000);
                }
                assertNotNull(activated, "Explicit EMPTY must reach ACTIVE before a new admission");
                assertNull(activating);
            } finally { if (activating != null) facade.abandon(activating); }
            var reopened = new Generation(activated.identity().key(), activated.identity().toGeneration());
            assertEquals(f.generation.value() + 1, reopened.value());
            assertEquals(retained, ledger(f));
            assertCode(WRITE_FENCED, () -> service.reserveAndAdmitFreshRange("a", request));
            int beforeOldWrite = f.source.calls.get();
            assertCode(MIGRATION_REQUIRED, () -> enrollment.deliver(f.metadata, "a",
                    repository -> repository.saveWithReceipt(firstWrite.entity()), receipt -> fail("Purged owner must not be recreated by save")));
            assertEquals(beforeOldWrite, f.source.calls.get());
            assertTrue(queuedWrites(f).isEmpty());
            var reopenedRequest = new FreshIdentityRange.Request(domain, reopened, 4, UUID.randomUUID(), 2);
            f.guard.inOpenVersionTransaction(reopened, f.binding.fingerprint(), (connection, control) -> {
                var grant = f.guard.readOpenActivationGrant(connection, reopened, f.binding.fingerprint(),
                        service.window().fingerprint(), plan.manifestToken());
                assertEquals(activatedEpoch(grant), control);
                assertNativeGrantTuple(f, enrollment, grant, reopened);
                return null;
            });
            var next = service.reserveAndAdmitFreshRange("a", reopenedRequest);
            assertEquals(burnedLast + 1, next.first()); assertEquals(next.first() + 3, next.last());
            assertEquals(3, next.reservationEpoch()); assertEquals(activated.attemptEpoch(), next.controlEpoch());
            var secondWrite = saveAndReadWithoutSql(f, enrollment, new Row(next.first(), "a", "fresh-g2-" + f.run));
            assertTrue(secondWrite.version() > firstWrite.version(), "EMPTY must preserve the tenant version highwater");
            assertQueuedWrite(f, secondWrite, reopened);
            assertEquals(Map.of(3L, f.originalSource.get(3L)), f.sourceSnapshot(), "g2 acceptance remains Redis-only until an explicit worker flush");
            assertCode(RESERVATION_CONFLICT, () -> service.reserveAndAdmitFreshRange("a", reopenedRequest));
            assertEquals(new JdbcFreshIdentityAllocator.Status(next.last(), 3), allocator.statusOpen(reopened, domain));
            f.evidence("PASS freshIdentity=ROOT_ADMIT_ORDINARY_WRITE_PURGE_EMPTY_WRITE provider=" + provider
                    + " foregroundSqlBorrows=0 nativeQueueOriginalGeneration=true unusedOwnersPurged=3"
                    + " collisionAtomic=true committedCollisionRangeBurned=true strictReadmissionRejected=true"
                    + " actualInitial=true actualEmpty=true workerFlush=false");
        }
    }

    @ParameterizedTest(name = "{0}: EMPTY native OPEN, failed SQL completion and genuine lease recovery")
    @ValueSource(strings = {"postgres", "mssql", "oracle"})
    void recoveredEmptyActivationAdmitsFreshIdentitiesWithoutReplayingOldAuthority(String provider) throws Exception {
        try (var admin = CacheTenantContext.administrative();
                var f = new CacheInitialNativeBridgeProvidersRedisTest.Fixture(provider, false, true)) {
            var namespace = f.database.prepareInitialNamespace(f.metadata, f.binding, f.guard, f.ids,
                    1L, 2L, 2, 65536, 16384, 1048576, 1);
            namespace.bootstrap(new JdbcNamespaceBootstrapGate(f.guard,
                    Set.of(namespace.descriptor().namespaceFingerprint())), f.generation);
            var enrollment = f.database.prepareTenantProtection(1);
            var plan = controlPlan(f, f.database, enrollment, namespace.window());
            var descriptor = enrollment.descriptor(ENTITY);
            var approval = new RedisTenantRuntimeControl.ApprovedRoster(
                    ProtectionKey.hash("fresh-recovery-review:" + f.run), plan.manifestToken(),
                    descriptor.coverageToken(), List.of(descriptor.runtimeId()), List.of(f.generation.key()));
            var service = namespace.createBootstrap(enrollment, plan, approval, 64,
                    Set.of(namespace.window().fingerprint()));
            var initial = new InitialTenantBootstrap.Request(f.generation, "a", UUID.randomUUID(),
                    ProtectionKey.hash("fresh-recovery-operator:" + f.run), 120);
            var domain = FreshIdentityDomain.exclusivelyReserved(f.binding, UUID.randomUUID(),
                    3_000_000, 3_000_999, ProtectionKey.hash("isolated-exclusive-recovery-domain:" + f.run));
            service.provisionIdentityDomain(initial, domain);
            boolean initialActive = false;
            for (int call = 0; call < 512; call++) {
                var progress = service.step(initial);
                assertNotEquals(Phase.INCOMPLETE, progress.phase());
                if (progress.phase() == Phase.ACTIVE) { initialActive = true; break; }
            }
            assertTrue(initialActive, "Actual INITIAL must reach ACTIVE before admitting identities");
            var allocator = new JdbcFreshIdentityAllocator(f.guard, f.sandbox.dialect, 2);
            var firstRequest = new FreshIdentityRange.Request(domain, f.generation, 2, UUID.randomUUID(), 0);
            var first = service.reserveAndAdmitFreshRange("a", firstRequest);
            var firstWrite = saveAndReadWithoutSql(f, enrollment,
                    new Row(first.first(), "a", "fresh-recovery-g1-" + f.run));
            assertQueuedWrite(f, firstWrite, f.generation);
            String unusedVersion = f.database.redisKeyStrategy().versionKey(NS, first.last());
            assertEquals("0", f.redis.get(unusedVersion));
            assertEquals(-1, f.redis.pttl(unusedVersion));
            var retained = ledger(f);

            var facade = f.database.configureDataProtection(List.of(plan), 1);
            var purge = options("a", f.run);
            CacheDataProtection.Continuation purging = null;
            CacheDataProtection.Result erased = null;
            try {
                for (int call = 0; call < 1024; call++) {
                    erased = facade.purgeTenant(Row.class, "a", purge, purging);
                    purging = erased.continuation();
                    assertNotEquals(CacheDataProtection.Status.INCOMPLETE, erased.status());
                    assertNotEquals(CacheDataProtection.Status.MIGRATION_REQUIRED, erased.status());
                    if (erased.status() == CacheDataProtection.Status.COMPLETE) break;
                }
                assertNotNull(erased); assertEquals(CacheDataProtection.Status.COMPLETE, erased.status());
                assertEquals(Stage.COMPLETE, erased.receipt().stage()); assertNotNull(erased.evidence());
                assertEquals(3, erased.receipt().sourceRows());
                assertEquals(4, erased.receipt().redisRows(), "Two original hot and two admitted identities");
            } finally { if (purging != null) facade.abandon(purging); }
            for (long id = first.first(); id <= first.last(); id++)
                for (String key : identityKeys(f, id)) assertFalse(f.redis.exists(key));
            assertTrue(queuedWrites(f).isEmpty());
            assertEquals(Map.of(3L, f.originalSource.get(3L)), f.sourceSnapshot());
            assertEquals(retained, ledger(f), "Purge must retain the namespace-global nonreuse ledger");
            assertCode(WRITE_FENCED, () -> service.reserveAndAdmitFreshRange("a", firstRequest));

            var write = f.database.config().writeBehind();
            var routes = new LinkedHashSet<String>();
            routes.add(write.streamKey()); routes.add(write.deadLetterStreamKey());
            routes.add(write.compactionStreamKey()); routes.addAll(write.activeStreamKeys());
            assertTrue(routes.size() >= 1 && routes.size() <= 128);
            var empty = new EmptyTenantActivation.Request(UUID.randomUUID(), EmptyTenantActivation.Mode.EMPTY,
                    purge, erased.evidence(), plan.manifestToken());
            EmptyTenantActivation.Checkpoint prepared = null, activated = null;
            CacheDataProtection.Continuation activating = null;
            String nativeKey = enrollment.barrier(ENTITY).generationKey(f.generation.key());
            String versionKey = enrollment.barrier(ENTITY).versionAllocatorKey(f.generation.key());
            try {
                for (int call = 0; call < 64; call++) {
                    var step = facade.reenableEmptyTenant(Row.class, "a", empty, activating);
                    activating = step.continuation();
                    assertNotEquals(EmptyTenantActivation.Phase.ACTIVE, step.checkpoint().phase());
                    if (step.checkpoint().phase() == EmptyTenantActivation.Phase.SQL_OPEN
                            && step.checkpoint().queueCursor() == routes.size()) {
                        prepared = step.checkpoint(); break;
                    }
                    enrollment.pollControl(1, 1000);
                }
                assertNotNull(prepared, "Intercept SQL_OPEN only after all genuine native queue acknowledgements");
                assertNotNull(activating);
                assertEquals("CLOSED", f.redis.hget(nativeKey, "state"));
                assertEquals(prepared.attemptEpoch(), prepared.reservation().controlEpoch());
                assertCheckpointEquivalent(prepared, emptyCheckpoint(f, prepared.identity()));
                var interrupted = activating;
                var oldLease = interrupted.lease();
                // Native OPEN succeeds; only the following real SQL completion transaction rolls back.
                f.faults.arm("UPDATE cachedb_empty_activation SET payload=", false);
                assertCode(STORAGE_UNAVAILABLE,
                        () -> facade.reenableEmptyTenant(Row.class, "a", empty, interrupted));
                activating = null;
                f.faults.requireConsumed(); assertEquals(1, f.faults.fired.get());
                assertCheckpointEquivalent(prepared, emptyCheckpoint(f, prepared.identity()));
                assertEquals("OPEN", f.redis.hget(nativeKey, "state"));
                assertEquals(prepared.receiptToken(), f.redis.hget(nativeKey, "e:receipt"));
                assertEquals(prepared.receiptToken(), f.redis.hget(nativeKey, "e:install"));
                String beforeRecovery = f.redis.get(versionKey + ":next");
                assertCode(LEASE_LOST, () -> f.guard.renew(oldLease));
                assertEquals(retained, ledger(f));

                var reacquired = facade.reenableEmptyTenant(Row.class, "a", empty, null);
                activating = reacquired.continuation();
                var adopted = reacquired.checkpoint();
                assertNotNull(activating, "Recovery must reacquire the actual SQL lease, not fabricate ACTIVE");
                assertTrue(adopted.attemptEpoch() > prepared.attemptEpoch());
                assertNotEquals(prepared.attemptOwner(), adopted.attemptOwner());
                assertReservationEquivalent(prepared, adopted);
                assertTrue(adopted.reservation().controlEpoch() < adopted.attemptEpoch(),
                        "Immutable range epoch must precede the genuinely reacquired attempt epoch");
                assertCode(LEASE_LOST, () -> f.guard.renew(oldLease));
                for (int call = 0; call < 64; call++) {
                    enrollment.pollControl(1, 1000);
                    var step = facade.reenableEmptyTenant(Row.class, "a", empty, activating);
                    activating = step.continuation();
                    if (step.checkpoint().phase() == EmptyTenantActivation.Phase.ACTIVE) {
                        activated = step.checkpoint(); break;
                    }
                    assertNotNull(activating);
                }
                assertNotNull(activated, "Real native observation and SQL completion must finish recovery");
                assertEquals(adopted.attemptEpoch(), activated.attemptEpoch());
                assertEquals(adopted.attemptOwner(), activated.attemptOwner());
                assertReservationEquivalent(prepared, activated);
                assertCheckpointEquivalent(activated, emptyCheckpoint(f, activated.identity()));
                assertEquals(beforeRecovery, f.redis.get(versionKey + ":next"), "Recovery cannot reset the counter");
            } finally { if (activating != null) facade.abandon(activating); }

            var recovered = activated;
            var reopened = new Generation(recovered.identity().key(), recovered.identity().toGeneration());
            assertEquals(f.generation.value() + 1, reopened.value());
            f.guard.inOpenVersionTransaction(reopened, f.binding.fingerprint(), (connection, control) -> {
                var grant = f.guard.readOpenActivationGrant(connection, reopened, f.binding.fingerprint(),
                        service.window().fingerprint(), plan.manifestToken());
                assertTrue(grant.range().controlEpoch() < control);
                assertEquals(recovered.attemptEpoch(), control);
                assertEquals(control, activatedEpoch(grant));
                assertNativeGrantTuple(f, enrollment, grant, reopened);
                return null;
            });
            assertEquals(retained, ledger(f));
            assertCode(WRITE_FENCED, () -> service.reserveAndAdmitFreshRange("a", firstRequest));
            int beforeRejected = f.source.calls.get();
            assertCode(MIGRATION_REQUIRED, () -> enrollment.deliver(f.metadata, "a",
                    repository -> repository.saveWithReceipt(firstWrite.entity()), receipt -> fail("Purged ID cannot return")));
            assertEquals(beforeRejected, f.source.calls.get());
            assertTrue(queuedWrites(f).isEmpty());
            for (long id = first.first(); id <= first.last(); id++)
                for (String key : identityKeys(f, id)) assertFalse(f.redis.exists(key));

            var nextRequest = new FreshIdentityRange.Request(domain, reopened, 2, UUID.randomUUID(), 1);
            var next = service.reserveAndAdmitFreshRange("a", nextRequest);
            assertEquals(first.last() + 1, next.first()); assertEquals(next.first() + 1, next.last());
            assertEquals(2, next.reservationEpoch()); assertEquals(recovered.attemptEpoch(), next.controlEpoch());
            var secondWrite = saveAndReadWithoutSql(f, enrollment,
                    new Row(next.first(), "a", "fresh-recovery-g2-" + f.run));
            assertTrue(secondWrite.version() > firstWrite.version());
            assertQueuedWrite(f, secondWrite, reopened);
            assertEquals(Map.of(3L, f.originalSource.get(3L)), f.sourceSnapshot());
            var finalLedger = ledger(f);
            String finalCounter = f.redis.get(versionKey + ":next");
            assertCode(RESERVATION_CONFLICT, () -> service.reserveAndAdmitFreshRange("a", nextRequest));
            var replay = facade.reenableEmptyTenant(Row.class, "a", empty, null);
            assertNull(replay.continuation()); assertCheckpointEquivalent(recovered, replay.checkpoint());
            assertEquals(finalLedger, ledger(f)); assertEquals(finalCounter, f.redis.get(versionKey + ":next"));
            assertEquals(new JdbcFreshIdentityAllocator.Status(next.last(), 2), allocator.statusOpen(reopened, domain));
            assertQueuedWrite(f, secondWrite, reopened);
            f.evidence("PASS freshIdentity=ROOT_EMPTY_SQL_COMPLETION_FAILURE_LEASE_RECOVERY_ADMIT_WRITE provider=" + provider
                    + " immutableRangePreserved=true recoveredEpochGreater=true foregroundSqlBorrows=0"
                    + " counterNotReset=true strictReadmissionRejected=true actualInitial=true actualEmpty=true"
                    + " ttlExpiry=false processKill=false workerFlush=false");
        }
    }

    @ParameterizedTest(name = "{0}: real flusher across INITIAL, purge, EMPTY and OPEN refill rejects g1 replay")
    @ValueSource(strings = {"postgres", "mssql", "oracle"})
    void reopenedFreshIdentityFlushesWithOriginalGenerationAndRejectsPrePurgeReplay(String provider) throws Exception {
        try (var admin = CacheTenantContext.administrative();
                var f = new CacheInitialNativeBridgeProvidersRedisTest.Fixture(provider)) {
            var namespace = f.database.prepareInitialNamespace(f.metadata, f.binding, f.guard, f.ids,
                    1L, 2L, 2, 65536, 16384, 1048576, 1);
            namespace.bootstrap(new JdbcNamespaceBootstrapGate(f.guard,
                    Set.of(namespace.descriptor().namespaceFingerprint())), f.generation);
            var enrollment = f.database.prepareTenantProtection(1);
            var plan = controlPlan(f, f.database, enrollment, namespace.window());
            var descriptor = enrollment.descriptor(ENTITY);
            var approval = new RedisTenantRuntimeControl.ApprovedRoster(
                    ProtectionKey.hash("fresh-flush-review:" + f.run), plan.manifestToken(),
                    descriptor.coverageToken(), List.of(descriptor.runtimeId()), List.of(f.generation.key()));
            var service = namespace.createBootstrap(enrollment, plan, approval, 64,
                    Set.of(namespace.window().fingerprint()));
            var initial = new InitialTenantBootstrap.Request(f.generation, "a", UUID.randomUUID(),
                    ProtectionKey.hash("fresh-flush-operator:" + f.run), 120);
            var domain = FreshIdentityDomain.exclusivelyReserved(f.binding, UUID.randomUUID(),
                    4_000_000, 4_000_999, ProtectionKey.hash("isolated-exclusive-flush-domain:" + f.run));
            service.provisionIdentityDomain(initial, domain);
            boolean active = false;
            for (int call = 0; call < 512; call++) {
                var progress = service.step(initial);
                assertNotEquals(Phase.INCOMPLETE, progress.phase());
                if (progress.phase() == Phase.ACTIVE) { active = true; break; }
            }
            assertTrue(active, "Actual INITIAL must reach ACTIVE");
            var first = service.reserveAndAdmitFreshRange("a",
                    new FreshIdentityRange.Request(domain, f.generation, 1, UUID.randomUUID(), 0));
            var g1 = saveAndReadWithoutSql(f, enrollment, new Row(first.first(), "a", "flush-g1-" + f.run));
            assertQueuedWrite(f, g1, f.generation);
            var originalFrame = Map.copyOf(queuedWrites(f).getFirst());
            var originalBody = Map.copyOf(f.database.config().writeBehind().compactionActive()
                    ? f.redis.hgetAll(f.database.redisKeyStrategy().compactionPayloadKey(NS, g1.id())) : originalFrame);
            var mapper = new com.reactor.cachedb.redis.RedisWriteOperationMapper(
                    com.reactor.cachedb.core.trace.TraceContexts.threadLocal(), f.database.entityRegistry());
            var retained = mapper.fromStampedBody(originalBody);
            assertEquals(f.generation, retained.generation(retained.operation()));
            var config = com.reactor.cachedb.core.config.WriteBehindConfig.builder().statementTimeoutSeconds(2).build();
            com.reactor.cachedb.core.queue.WriteBehindFlusher flusher = switch (provider) {
                case "postgres" -> new com.reactor.cachedb.postgres.PostgresWriteBehindFlusher(f.source, f.database.entityRegistry(), config);
                case "mssql" -> new com.reactor.cachedb.mssql.MssqlWriteBehindFlusher(f.source, f.database.entityRegistry(), config);
                case "oracle" -> new com.reactor.cachedb.oracle.OracleWriteBehindFlusher(f.source, f.database.entityRegistry(), config);
                default -> throw new IllegalArgumentException("Unknown provider");
            };
            try (var stamps = TenantWriteStampContext.restore(List.of(retained))) {
                flusher.flush(retained.operation());
            }
            assertNull(TenantWriteStampContext.current(retained.operation()));
            var withG1 = new LinkedHashMap<>(f.originalSource);
            withG1.put(g1.id(), "a|" + originalBody.get("col:secret") + "|" + g1.version());
            assertEquals(withG1, f.sourceSnapshot(), "A separate SQL connection must observe the committed original ciphertext/version");
            var retainedLedger = ledger(f);
            var facade = f.database.configureDataProtection(List.of(plan), 1);
            var purge = options("a", f.run);
            CacheDataProtection.Continuation continuation = null;
            CacheDataProtection.Result result = null;
            try {
                for (int call = 0; call < 1024; call++) {
                    result = facade.purgeTenant(Row.class, "a", purge, continuation);
                    continuation = result.continuation();
                    assertNotEquals(CacheDataProtection.Status.INCOMPLETE, result.status());
                    assertNotEquals(CacheDataProtection.Status.MIGRATION_REQUIRED, result.status());
                    if (result.status() == CacheDataProtection.Status.COMPLETE) break;
                }
                assertNotNull(result); assertEquals(CacheDataProtection.Status.COMPLETE, result.status());
                assertEquals(Stage.COMPLETE, result.receipt().stage()); assertEquals(4, result.receipt().sourceRows());
                assertNotNull(result.evidence());
            } finally { if (continuation != null) facade.abandon(continuation); }
            assertEquals(Map.of(3L, f.originalSource.get(3L)), f.sourceSnapshot());
            assertTrue(queuedWrites(f).isEmpty()); assertEquals(retainedLedger, ledger(f));
            for (String key : identityKeys(f, g1.id())) assertFalse(f.redis.exists(key));
            var empty = new EmptyTenantActivation.Request(UUID.randomUUID(), EmptyTenantActivation.Mode.EMPTY,
                    purge, result.evidence(), plan.manifestToken());
            EmptyTenantActivation.Checkpoint activated = null;
            CacheDataProtection.Continuation activating = null;
            try {
                for (int call = 0; call < 64; call++) {
                    var step = facade.reenableEmptyTenant(Row.class, "a", empty, activating);
                    activating = step.continuation();
                    if (step.checkpoint().phase() == EmptyTenantActivation.Phase.ACTIVE) {
                        activated = step.checkpoint(); break;
                    }
                    assertNotNull(activating); enrollment.pollControl(1, 1000);
                }
                assertNotNull(activated); assertNull(activating);
            } finally { if (activating != null) facade.abandon(activating); }
            var reopened = new Generation(activated.identity().key(), activated.identity().toGeneration());
            assertEquals(f.generation.value() + 1, reopened.value());
            var next = service.reserveAndAdmitFreshRange("a",
                    new FreshIdentityRange.Request(domain, reopened, 1, UUID.randomUUID(), 1));
            assertEquals(first.last() + 1, next.first());
            var refill = service.refillVersionRange("a", reopened, 8, UUID.randomUUID(),
                    activated.reservation().reservationEpoch());
            assertTrue(refill.first() > activated.reservation().last());
            var g2 = saveAndReadWithoutSql(f, enrollment, new Row(next.first(), "a", "flush-g2-" + f.run));
            assertEquals(refill.first(), g2.version()); assertTrue(g2.version() > g1.version());
            assertQueuedWrite(f, g2, reopened);
            var nextFrame = Map.copyOf(queuedWrites(f).getFirst());
            var nextBody = Map.copyOf(f.database.config().writeBehind().compactionActive()
                    ? f.redis.hgetAll(f.database.redisKeyStrategy().compactionPayloadKey(NS, g2.id())) : nextFrame);
            var current = mapper.fromStampedBody(nextBody);
            assertEquals(reopened, current.generation(current.operation()));
            try (var stamps = TenantWriteStampContext.restore(List.of(current))) {
                flusher.flush(current.operation());
            }
            assertNull(TenantWriteStampContext.current(current.operation()));
            var expected = Map.of(3L, f.originalSource.get(3L),
                    g2.id(), "a|" + nextBody.get("col:secret") + "|" + g2.version());
            assertEquals(expected, f.sourceSnapshot());
            var afterG2Ledger = ledger(f);
            var beforeReplayQueue = queuedWrites(f);
            String beforeReplayPayload = f.redis.get(f.database.redisKeyStrategy().entityKey(NS, g2.id()));
            try (var stamps = TenantWriteStampContext.restore(List.of(retained))) {
                assertEquals("CDBGG", assertThrows(SQLException.class, () -> flusher.flush(retained.operation())).getSQLState());
            }
            assertNull(TenantWriteStampContext.current(retained.operation()));
            for (String stream : f.database.config().writeBehind().activeStreamKeys()) {
                var denied = assertThrows(redis.clients.jedis.exceptions.JedisDataException.class,
                        () -> com.reactor.cachedb.redis.RedisQueueGenerationFence.publish(
                                f.database.redisOperations(), stream, originalFrame, retained));
                assertTrue(denied.getMessage().contains("TENANT_QUEUE_GENERATION_REJECTED"));
            }
            assertEquals(expected, f.sourceSnapshot()); assertEquals(afterG2Ledger, ledger(f));
            assertEquals(beforeReplayQueue, queuedWrites(f));
            assertEquals(beforeReplayPayload, f.redis.get(f.database.redisKeyStrategy().entityKey(NS, g2.id())));
            assertQueuedWrite(f, g2, reopened);
            for (String key : identityKeys(f, g1.id())) assertFalse(f.redis.exists(key), "Retained g1 must not resurrect purged native state");
            f.evidence("PASS freshIdentity=ROOT_INITIAL_FLUSH_PURGE_EMPTY_REFILL_FLUSH provider=" + provider
                    + " originalGenerationPreserved=true staleSqlReplayRejected=true staleNativeReplayRejected=true"
                    + " actualInitial=true actualEmpty=true backgroundWorker=false deploymentClosure=false restore=false");
        }
    }

    private static EmptyTenantActivation.Checkpoint emptyCheckpoint(CacheInitialNativeBridgeProvidersRedisTest.Fixture f,
            EmptyTenantActivation.Identity identity) throws Exception {
        try (var connection = f.source.getConnection();
                var query = connection.prepareStatement("SELECT activation_id,payload FROM cachedb_empty_activation WHERE "
                        + InitialSqlTokens.pair(f.sandbox.dialect, true) + " AND operation_id="
                        + InitialSqlTokens.varchar(f.sandbox.dialect, 36))) {
            query.setQueryTimeout(2); query.setMaxRows(2);
            query.setString(1, identity.key().scopeToken()); query.setString(2, identity.key().tenantToken());
            query.setString(3, identity.operationId().toString());
            try (var rows = query.executeQuery()) {
                assertTrue(rows.next()); assertEquals(identity.activationId().toString(), rows.getString(1));
                EmptyTenantActivation.Checkpoint checkpoint;
                try (var input = rows.getBinaryStream(2)) {
                    assertNotNull(input); checkpoint = JdbcEmptyTenantActivation.decode(input.readNBytes(4097));
                }
                assertEquals(identity, checkpoint.identity()); assertFalse(rows.next());
                return checkpoint;
            }
        }
    }

    private static void assertReservationEquivalent(EmptyTenantActivation.Checkpoint expected,
            EmptyTenantActivation.Checkpoint actual) {
        assertEquals(expected.identity(), actual.identity());
        assertEquals(expected.receiptToken(), actual.receiptToken());
        var range = expected.reservation(); var replayed = actual.reservation();
        assertNotNull(range); assertNotNull(replayed);
        assertEquals(range.key(), replayed.key()); assertEquals(range.generation(), replayed.generation());
        assertEquals(range.controlEpoch(), replayed.controlEpoch());
        assertEquals(range.reservationEpoch(), replayed.reservationEpoch());
        assertEquals(range.first(), replayed.first()); assertEquals(range.last(), replayed.last());
        assertEquals(range.request(), replayed.request()); assertEquals(range.runId(), replayed.runId());
        assertEquals(range.inventory(), replayed.inventory());
    }

    private static long activatedEpoch(JdbcOpenActivationGrant grant) {
        return Long.parseLong(grant.generationFields().get("epoch"));
    }

    private static void assertNativeGrantTuple(CacheInitialNativeBridgeProvidersRedisTest.Fixture f,
            CacheTenantRuntimeEnrollment enrollment, JdbcOpenActivationGrant grant, Generation generation) {
        var barrier=enrollment.barrier(ENTITY);var range=grant.range();
        String nativeKey=barrier.generationKey(generation.key()), allocator=barrier.versionAllocatorKey(generation.key());
        grant.generationFields().forEach((field,value)->assertEquals(value,f.redis.hget(nativeKey,field)));
        assertEquals(range.runId(),f.redis.hget(nativeKey,"run_id"));
        assertEquals(Long.toString(generation.value()),f.redis.hget(nativeKey,"generation"));
        assertEquals(grant.generationFields().get("epoch"),f.redis.hget(allocator,"control_epoch"));
        assertEquals(range.runId(),f.redis.hget(allocator,"run_id"));
        assertEquals(range.inventory(),f.redis.hget(allocator,"inventory"));
        assertEquals(Long.toString(range.first()-1),f.redis.hget(allocator,"floor"));
        assertEquals(Long.toString(range.last()),f.redis.hget(allocator,"ceiling"));
        assertEquals(range.request().toString(),f.redis.hget(allocator,"request"));
        assertEquals(Long.toString(range.reservationEpoch()),f.redis.hget(allocator,"reservation_epoch"));
    }

    private static WriteReceipt<Row, Long> saveAndReadWithoutSql(CacheInitialNativeBridgeProvidersRedisTest.Fixture f,
            CacheTenantRuntimeEnrollment enrollment, Row row) {
        var accepted = new AtomicReference<WriteReceipt<Row, Long>>();
        int before = f.source.calls.get();
        enrollment.deliver(f.metadata, "a", repository -> repository.saveWithReceipt(row), accepted::set);
        var receipt = accepted.get(); assertNotNull(receipt);
        assertEquals(row, receipt.entity()); assertEquals(row.id(), receipt.id());
        assertEquals(NS, receipt.redisNamespace()); assertEquals(com.reactor.cachedb.core.model.OperationType.UPSERT, receipt.operationType());
        assertTrue(receipt.version() > 0);
        enrollment.deliver(f.metadata, "a", repository -> repository.findById(row.id()),
                found -> assertEquals(row, found.orElseThrow()));
        assertEquals(before, f.source.calls.get(), "Ordinary enrolled save/read must not borrow foreground SQL");
        var keys = f.database.redisKeyStrategy();
        assertEquals("a", f.redis.get(keys.versionKey(NS, row.id()) + ":tenant"));
        assertEquals(Long.toString(receipt.version()), f.redis.get(keys.versionKey(NS, row.id())));
        String payload = f.redis.get(keys.entityKey(NS, row.id())); assertNotNull(payload);
        assertEquals(row, f.bound.fromRedisValue(payload)); assertFalse(payload.contains(row.secret()));
        return receipt;
    }

    private static List<Map<String, String>> queuedWrites(CacheInitialNativeBridgeProvidersRedisTest.Fixture f) {
        var queued = new ArrayList<Map<String, String>>();
        for (String stream : f.database.config().writeBehind().activeStreamKeys()) {
            assertTrue(f.redis.xlen(stream) <= 16, "Fresh writer fixture must retain a bounded queue");
            for (var entry : f.redis.xrange(stream, new redis.clients.jedis.StreamEntryID("0-0"),
                    new redis.clients.jedis.StreamEntryID(Long.MAX_VALUE, Long.MAX_VALUE), 16)) queued.add(entry.getFields());
        }
        return List.copyOf(queued);
    }

    private static void assertQueuedWrite(CacheInitialNativeBridgeProvidersRedisTest.Fixture f,
            WriteReceipt<Row, Long> receipt, Generation generation) {
        var queued = queuedWrites(f); assertEquals(1, queued.size());
        var frame = queued.getFirst();
        assertEquals(receipt.id().toString(), frame.get("id")); assertEquals(NS, frame.get("namespace"));
        assertEquals(Long.toString(receipt.version()), frame.get("version"));
        assertEquals(Long.toString(generation.value()), frame.get("tenantGeneration"));
        assertEquals(generation.key().tenantToken(), frame.get("tenantToken"));
        assertEquals(generation.key().scopeToken(), frame.get("generationScope"));
        var body = f.database.config().writeBehind().compactionActive()
                ? f.redis.hgetAll(f.database.redisKeyStrategy().compactionPayloadKey(NS, receipt.id())) : frame;
        assertEquals("UPSERT", body.get("type")); assertEquals(ENTITY, body.get("entity"));
        assertEquals(Long.toString(receipt.version()), body.get("version"));
        assertEquals(Long.toString(generation.value()), body.get("tenantGeneration"));
        assertEquals("a", body.get("col:tenant_id"));
        String secret = body.get("col:secret"); assertNotNull(secret);
        assertTrue(secret.contains("~E1:k2:")); assertFalse(secret.contains(receipt.entity().secret()));
    }

    private static List<String> identityKeys(CacheInitialNativeBridgeProvidersRedisTest.Fixture f, long id) {
        var keys = f.database.redisKeyStrategy();
        return List.of(keys.entityKey(NS, id), keys.versionKey(NS, id), keys.versionKey(NS, id) + ":tenant",
                keys.tombstoneKey(NS, id), keys.indexMetaKey(NS, id), keys.durabilityKey(NS, id),
                keys.tenantHotOwnerKey(NS, id), keys.compactionPayloadKey(NS, id), keys.compactionPendingKey(NS, id));
    }

    private static void assertCheckpointEquivalent(EmptyTenantActivation.Checkpoint expected,
            EmptyTenantActivation.Checkpoint actual) {
        // SQL replay decodes a new range object; every durable component, not reference identity, must match.
        assertEquals(expected.identity(), actual.identity());
        assertEquals(expected.phase(), actual.phase());
        assertEquals(expected.attemptEpoch(), actual.attemptEpoch());
        assertEquals(expected.attemptOwner(), actual.attemptOwner());
        assertEquals(expected.queueCursor(), actual.queueCursor());
        assertEquals(expected.receiptToken(), actual.receiptToken());
        var range = expected.reservation();
        var replayed = actual.reservation();
        assertNotNull(replayed);
        assertEquals(range.key(), replayed.key());
        assertEquals(range.generation(), replayed.generation());
        assertEquals(range.controlEpoch(), replayed.controlEpoch());
        assertEquals(range.reservationEpoch(), replayed.reservationEpoch());
        assertEquals(range.first(), replayed.first());
        assertEquals(range.last(), replayed.last());
        assertEquals(range.request(), replayed.request());
        assertEquals(range.runId(), replayed.runId());
        assertEquals(range.inventory(), replayed.inventory());
    }

    private static PurgeOptions options(String tenant, String run) {
        return new PurgeOptions(UUID.randomUUID(), tenant, ProtectionKey.hash("fresh-identity-purge:" + run),
                true, 128, 120, 5000, PurgeScope.ACTIVE_STORES_RETAINING_SECURITY_METADATA);
    }

    /** Read only, PK-scoped exact retained receipt; nulls are meaningful before first allocation. */
    private static List<String> ledger(CacheInitialNativeBridgeProvidersRedisTest.Fixture f) throws SQLException {
        try (var connection = f.source.getConnection();
                var query = connection.prepareStatement("SELECT scope_token,binding_token,authority_id,domain_first,"
                        + "domain_last,exclusive_token,highwater,reservation_epoch,last_request,last_tenant,"
                        + "last_generation,last_control,last_first,last_count FROM cachedb_identity_allocator WHERE scope_token="
                        + InitialSqlTokens.char64(f.sandbox.dialect))) {
            query.setQueryTimeout(2);
            query.setString(1, f.generation.key().scopeToken());
            try (var rows = query.executeQuery()) {
                assertTrue(rows.next(), "The durable namespace allocator must exist");
                var snapshot = new ArrayList<String>(14);
                for (int column = 1; column <= 14; column++) snapshot.add(rows.getString(column));
                assertFalse(rows.next(), "One namespace has exactly one nonreuse ledger");
                return Collections.unmodifiableList(snapshot);
            }
        }
    }

    private static void assertCode(ProtectionException.Code expected, Executable work) {
        assertEquals(expected, assertThrows(ProtectionException.class, work).code());
    }
}
