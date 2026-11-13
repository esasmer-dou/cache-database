package com.reactor.cachedb.core.protection;

import com.reactor.cachedb.core.protection.SourceOwnerOnboarding.*;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Semaphore;

/**
 * One bounded administrative page per step. No executor, no foreground SQL, no OPEN transition.
 * WARMED covers the sealed existing-identity worklist only; callers still need independent
 * replay-history, allocator/highwater, distributed visibility and activation verification.
 */
public final class SourceOwnerBootstrap<I> {
    private final Store<I> store;
    private final ClosedRuntime<I> runtime;
    private final Semaphore calls;

    public SourceOwnerBootstrap(Store<I> store, ClosedRuntime<I> runtime, int maxConcurrentSteps) {
        this.store = Objects.requireNonNull(store); this.runtime = Objects.requireNonNull(runtime);
        if (maxConcurrentSteps < 1 || maxConcurrentSteps > 64)
            SourceOwnerOnboarding.fail(ProtectionException.Code.INVALID_REQUEST);
        calls = new Semaphore(maxConcurrentSteps);
    }

    public Checkpoint step(Request request) {
        if (!CacheTenantContext.isAdministrative()) SourceOwnerOnboarding.fail(ProtectionException.Code.ADMIN_REQUIRED);
        Objects.requireNonNull(request);
        if (!calls.tryAcquire()) SourceOwnerOnboarding.fail(ProtectionException.Code.BUSY);
        Session session = null;
        Throwable primary = null;
        try {
            session = store.acquire(request);
            Checkpoint current = store.load(session);
            if (current.state() == State.INCOMPLETE || current.state() == State.WARMED) return current;
            if (current.state() == State.FENCING) {
                String inventory = ProtectionKey.token(runtime.closeAndDrain(session));
                current = store.save(session, current, current.fenced(inventory));
            }
            store.renew(session);
            runtime.requireClosed(session, current.inventoryToken());
            if (!current.last() && current.pending() == null) {
                Page page = runtime.existingIdentities(session, current.inventoryToken(), current.cursor(),
                        SourceOwnerOnboarding.CHUNK, Math.min(SourceOwnerOnboarding.MAX_PAGE_BYTES,
                                request.plan().binding().maxBatchBytes()));
                if (Math.addExact(current.examined(), page.ids().size()) > request.plan().binding().maxWorkItems())
                    SourceOwnerOnboarding.fail(ProtectionException.Code.INCOMPLETE_INVENTORY);
                current = store.save(session, current, current.staged(page));
            }
            if (current.pending() != null) {
                List<OwnerProof<I>> proofs = List.copyOf(store.resolve(session, current));
                if (proofs.size() != current.pending().ids().size())
                    SourceOwnerOnboarding.fail(ProtectionException.Code.INVALID_PROGRESS);
                int unresolved = 0;
                for (int i = 0; i < proofs.size(); i++) {
                    if (!proofs.get(i).canonicalId().equals(current.pending().ids().get(i)))
                        SourceOwnerOnboarding.fail(ProtectionException.Code.INVALID_PROGRESS);
                    if (proofs.get(i).ownership() == Ownership.UNKNOWN) unresolved++;
                }
                store.renew(session);
                runtime.requireClosed(session, current.inventoryToken());
                Installed installed = runtime.installExisting(session, current.inventoryToken(), current.pending(), proofs);
                if (installed.unknown() < unresolved)
                    SourceOwnerOnboarding.fail(ProtectionException.Code.INVALID_PROGRESS);
                current = store.save(session, current, current.acknowledged(installed));
            }
            if (current.state() == State.RUNNING && current.last()) {
                store.renew(session);
                runtime.requireClosed(session, current.inventoryToken());
                current = store.save(session, current, runtime.verify(session, current.inventoryToken())
                        ? current.verified() : current.incomplete());
            }
            return current;
        } catch (RuntimeException | Error failure) {
            primary = failure; throw failure;
        } finally {
            try {
                if (session != null) {
                    try { store.release(session); }
                    catch (RuntimeException | Error cleanup) {
                        // Preserve the primary failure. Cleanup diagnostics never retain adapter bind text.
                        Throwable safe = cleanup instanceof Error
                                ? new Error("CacheDB source-owner cleanup failed")
                                : new ProtectionException(ProtectionException.Code.STORAGE_UNAVAILABLE);
                        if (primary != null) primary.addSuppressed(safe);
                        else if (safe instanceof Error fatal) throw fatal;
                        else throw (RuntimeException) safe;
                    }
                }
            } finally { calls.release(); }
        }
    }
}
