package com.reactor.cachedb.core.protection;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.protection.SourceOwnerOnboarding.*;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.Binding;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.Generation;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.Lease;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.SourcePlan;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import java.util.*;
import org.junit.jupiter.api.Test;

class SourceOwnerBootstrapTest {
    static Request request() {
        var binding = new Binding(String.class, "orders", new SourcePlan("orders", "tenant_id", "id"),
                ProtectionKey.hash("layout"), 1000, 128, 131072, true);
        var plan = new Plan(binding, "entity_version", ProtectionKey.hash("strings"));
        return new Request(plan, new Generation(binding.key("a"), 1), "a", UUID.randomUUID(),
                ProtectionKey.hash("operator"), 30);
    }
    @Test void noAdministrativeAuthorityMeansNoJournalOrRuntimeAccess() {
        var request = request(); var store = new MemoryStore(request); var runtime = new Runtime(store);
        var service = new SourceOwnerBootstrap<>(store, runtime, 1);
        assertEquals(ProtectionException.Code.ADMIN_REQUIRED,
                assertThrows(ProtectionException.class, () -> service.step(request)).code());
        assertEquals(0, store.acquisitions); assertEquals(0, runtime.installCalls);
    }
    @Test void pendingPageIsCommittedBeforeAnyInstallationAndCrashReplaysItWithoutRefetch() {
        var request = request(); var store = new MemoryStore(request); var runtime = new Runtime(store);
        var service = new SourceOwnerBootstrap<>(store, runtime, 1);
        runtime.failAfterInstall = true;
        try (var admin = CacheTenantContext.administrative()) {
            assertThrows(ProtectionException.class, () -> service.step(request));
            assertNotNull(store.checkpoint.pending()); UUID page = store.checkpoint.pending().id();
            assertEquals(0, store.checkpoint.examined()); assertEquals(1, runtime.pageCalls);
            runtime.failAfterInstall = false;
            // A new coordinator instance resumes the same durable page; no in-memory recovery state.
            var result = new SourceOwnerBootstrap<>(store, runtime, 1).step(request);
            assertEquals(State.WARMED, result.state()); assertEquals(1, result.owned()); assertEquals(1, result.examined());
            assertEquals(page, runtime.lastPage); assertEquals(1, runtime.pageCalls); assertEquals(2, runtime.installCalls);
            assertEquals(1, runtime.owners.size()); assertEquals(2, store.releases);
            assertFalse(result.toString().contains("private-id"));
        }
    }
    @Test void unknownSourceOwnershipCannotBeTurnedIntoAClaimAndNeverWarmsTheBatch() {
        var request = request(); var store = new MemoryStore(request); store.unknown = true;
        var runtime = new Runtime(store);
        try (var admin = CacheTenantContext.administrative()) {
            var result = new SourceOwnerBootstrap<>(store, runtime, 1).step(request);
            assertEquals(State.INCOMPLETE, result.state()); assertEquals(1, result.unknown());
            assertEquals(0, result.owned()); assertTrue(runtime.owners.isEmpty());
            assertEquals(0, runtime.verifyCalls);
        }
    }
    @Test void malformedInstallerCannotHideUnknownProofsOrAdvanceDurablePendingPage() {
        var request = request(); var store = new MemoryStore(request); store.unknown = true;
        var runtime = new Runtime(store); runtime.lieAboutUnknown = true;
        try (var admin = CacheTenantContext.administrative()) {
            var result = assertThrows(ProtectionException.class,
                    () -> new SourceOwnerBootstrap<>(store, runtime, 1).step(request));
            assertEquals(ProtectionException.Code.INVALID_PROGRESS, result.code());
            assertNotNull(store.checkpoint.pending()); assertEquals(0, store.checkpoint.examined());
        }
    }
    @Test void closureAndFreshVerificationAreNotInferredFromAnEmptyWorklist() {
        var request = request(); var store = new MemoryStore(request); var runtime = new Runtime(store);
        runtime.empty = true; runtime.verified = false;
        try (var admin = CacheTenantContext.administrative()) {
            var result = new SourceOwnerBootstrap<>(store, runtime, 1).step(request);
            assertEquals(State.INCOMPLETE, result.state()); assertEquals(0, result.examined());
            assertEquals(1, runtime.closeCalls); assertEquals(1, runtime.verifyCalls);
            assertTrue(runtime.closedChecks >= 2);
        }
    }
    @Test void closureFailurePreservesDurablePendingAndReleaseFailureCannotMaskPrimary() {
        var request = request(); var store = new MemoryStore(request); var runtime = new Runtime(store);
        runtime.failAfterInstall = true; store.failRelease = true;
        try (var admin = CacheTenantContext.administrative()) {
            var failure = assertThrows(ProtectionException.class,
                    () -> new SourceOwnerBootstrap<>(store, runtime, 1).step(request));
            assertEquals(ProtectionException.Code.RECOVERY_REQUIRED, failure.code());
            assertEquals(1, failure.getSuppressed().length);
            assertInstanceOf(ProtectionException.class, failure.getSuppressed()[0]);
            assertFalse(failure.getSuppressed()[0].getMessage().contains("secret"));
            assertNotNull(store.checkpoint.pending());
        }
    }
    @Test void releaseErrorDoesNotReplaceThePrimaryRuntimeFailureOrExposeItsText() {
        var request = request(); var store = new MemoryStore(request); var runtime = new Runtime(store);
        runtime.failAfterInstall = true; store.releaseError = new AssertionError("secret cleanup bind");
        try (var admin = CacheTenantContext.administrative()) {
            var failure = assertThrows(ProtectionException.class,
                    () -> new SourceOwnerBootstrap<>(store, runtime, 1).step(request));
            assertEquals(ProtectionException.Code.RECOVERY_REQUIRED, failure.code());
            assertEquals(1, failure.getSuppressed().length);
            Throwable safe = failure.getSuppressed()[0];
            assertInstanceOf(Error.class, safe); assertNotSame(store.releaseError, safe);
            assertNull(safe.getCause()); assertFalse(safe.getMessage().contains("secret"));
            assertNotNull(store.checkpoint.pending());
        }
    }
    @Test void primaryErrorsSurviveRuntimeAndErrorCleanupFailuresWithSafeSuppression() {
        for (boolean errorCleanup : List.of(false, true)) {
            var request = request(); var store = new MemoryStore(request); var runtime = new Runtime(store);
            Error primary = new AssertionError("primary marker");
            runtime.installError = primary;
            store.failRelease = !errorCleanup;
            if (errorCleanup) store.releaseError = new AssertionError("secret cleanup bind");
            try (var admin = CacheTenantContext.administrative()) {
                var failure = assertThrows(Error.class,
                        () -> new SourceOwnerBootstrap<>(store, runtime, 1).step(request));
                assertSame(primary, failure); assertEquals(1, failure.getSuppressed().length);
                Throwable safe = failure.getSuppressed()[0];
                assertNull(safe.getCause()); assertFalse(safe.getMessage().contains("secret"));
                if (errorCleanup) assertInstanceOf(Error.class, safe);
                else assertInstanceOf(ProtectionException.class, safe);
                assertNotNull(store.checkpoint.pending());
            }
        }
    }
    @Test void loneCleanupFailureRemainsFatalWhenAnErrorAndAlwaysReturnsTheControlPermit() {
        for (boolean errorCleanup : List.of(false, true)) {
            var request = request(); var store = new MemoryStore(request); var runtime = new Runtime(store);
            var service = new SourceOwnerBootstrap<>(store, runtime, 1);
            store.failRelease = !errorCleanup;
            if (errorCleanup) store.releaseError = new AssertionError("secret cleanup bind");
            try (var admin = CacheTenantContext.administrative()) {
                Throwable failure = errorCleanup ? assertThrows(Error.class, () -> service.step(request))
                        : assertThrows(ProtectionException.class, () -> service.step(request));
                assertNull(failure.getCause()); assertFalse(failure.getMessage().contains("secret"));
                assertEquals(State.WARMED, store.checkpoint.state());
                store.failRelease = false; store.releaseError = null;
                assertEquals(State.WARMED, service.step(request).state());
                assertEquals(2, store.acquisitions); assertEquals(2, store.releases);
            }
        }
    }
    @Test void buffersIdsAndBatchesAreBoundedAndRequestsDoNotPrintTenantOrIds() {
        var request = request();
        assertFalse(request.toString().contains("tenant="));
        assertThrows(ProtectionException.class, () -> new Page(UUID.randomUUID(), "-", true,
                java.util.stream.IntStream.range(0, 129).mapToObj(Integer::toString).toList()));
        assertThrows(ProtectionException.class, () -> new Page(UUID.randomUUID(), "-", true, List.of("x", "x")));
        assertThrows(ProtectionException.class, () -> SourceOwnerOnboarding.canonicalId("x".repeat(1025)));
        assertThrows(ProtectionException.class, () -> SourceOwnerOnboarding.canonicalId("\uD800"));
        assertThrows(ProtectionException.class, () -> SourceOwnerOnboarding.canonicalId("private\nid"));
        assertThrows(ProtectionException.class, () -> new Installed(Integer.MAX_VALUE, Integer.MAX_VALUE, 2));
    }

    /** Test double only; production closure evidence must come from the native enrolled control bridge. */
    static final class Runtime implements ClosedRuntime<String> {
        final MemoryStore store;
        final Set<String> owners = new HashSet<>();
        int installCalls, pageCalls, closeCalls, closedChecks, verifyCalls;
        boolean failAfterInstall, empty, verified = true, lieAboutUnknown;
        Error installError;
        UUID lastPage;
        Runtime(MemoryStore store) { this.store = store; }
        public String closeAndDrain(Session session) { closeCalls++; return ProtectionKey.hash("sealed-worklist"); }
        public void requireClosed(Session session, String token) { closedChecks++; assertEquals(State.RUNNING, store.checkpoint.state()); }
        public Page existingIdentities(Session session, String token, String cursor, int limit, int maxBytes) {
            pageCalls++; assertEquals(128, limit); assertTrue(maxBytes <= SourceOwnerOnboarding.MAX_PAGE_BYTES);
            return new Page(UUID.randomUUID(), "-", true, empty ? List.of() : List.of("private-id"));
        }
        public Installed installExisting(Session session, String token, Page page, List<OwnerProof<String>> proofs) {
            installCalls++; lastPage = page.id(); assertEquals(page, store.checkpoint.pending());
            int owned = 0, unknown = 0;
            for (var proof : proofs) {
                if (proof.ownership() == Ownership.UNKNOWN) unknown++;
                else { owners.add(proof.canonicalId()); owned++; }
            }
            if (installError != null) throw installError;
            if (failAfterInstall) throw new ProtectionException(ProtectionException.Code.RECOVERY_REQUIRED);
            return lieAboutUnknown ? new Installed(proofs.size(), 0, 0) : new Installed(owned, 0, unknown);
        }
        public boolean verify(Session session, String token) { verifyCalls++; return verified; }
    }
    static final class MemoryStore implements SourceOwnerOnboarding.Store<String> {
        Checkpoint checkpoint; final Request request; int acquisitions, releases; long epoch; boolean unknown, failRelease;
        Error releaseError;
        MemoryStore(Request request) { this.request = request; checkpoint = Checkpoint.initial(request); }
        public Session acquire(Request value) {
            acquisitions++; assertEquals(request, value);
            return new Session(value, new Lease(value.generation().key(), value.operationId(), UUID.randomUUID(), ++epoch, 30));
        }
        public void renew(Session session) {}
        public Checkpoint load(Session session) { return checkpoint; }
        public Checkpoint save(Session session, Checkpoint before, Checkpoint after) {
            assertEquals(checkpoint, before); checkpoint = after; return after;
        }
        public List<OwnerProof<String>> resolve(Session session, Checkpoint pending) {
            return pending.pending().ids().stream().map(id -> new OwnerProof<>(id, id,
                    unknown ? Ownership.UNKNOWN : Ownership.SOURCE_OWNED, unknown ? 0 : 7)).toList();
        }
        public void release(Session session) {
            releases++; if (releaseError != null) throw releaseError;
            if (failRelease) throw new IllegalStateException("secret vendor bind");
        }
    }
}
