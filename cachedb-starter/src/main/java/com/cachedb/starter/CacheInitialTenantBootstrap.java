package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.config.CacheRuntimeRole;
import com.reactor.cachedb.core.config.ReadThroughMode;
import com.reactor.cachedb.core.crypto.CacheEncryptionException;
import com.reactor.cachedb.core.crypto.EncryptedStorageChecks;
import com.reactor.cachedb.core.model.EntityMetadata;
import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.InitialTenantBootstrap.*;
import com.reactor.cachedb.core.registry.EntityBinding;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.jdbc.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import static com.reactor.cachedb.core.protection.ProtectionException.Code.*;

/**
 * Actual registered-root initial population coordinator. Borrowed transports/codecs/guard;
 * no provider invention, hidden executor/pool, automatic OPEN or foreground SQL.
 */
public final class CacheInitialTenantBootstrap<T, ID> implements AutoCloseable {
    private final CacheDatabase database;
    private final EntityBinding<T, ID> binding;
    private final RedisTenantPurgePlan plan;
    private final JdbcInitialTenantBootstrap<T, ID> sql;
    private final CacheInitialNativeBridge<T, ID> nativeBridge;
    private final CacheInitialActivationCommit activationCommit;
    private final int rangeSize;
    private final JdbcFreshIdentityAllocator freshIdentities;
    private final Semaphore work = new Semaphore(1);
    private final AtomicBoolean stopped = new AtomicBoolean();

    private CacheInitialTenantBootstrap(CacheDatabase database, EntityBinding<T, ID> binding, RedisTenantPurgePlan plan,
            JdbcInitialTenantBootstrap<T, ID> sql, CacheInitialNativeBridge<T, ID> nativeBridge, int rangeSize) {
        this.database = database; this.binding = binding; this.plan = plan; this.sql = sql; this.nativeBridge = nativeBridge;
        if (rangeSize < 1 || rangeSize > TenantVersionRange.MAX_RANGE) fail(INVALID_REQUEST);
        this.rangeSize = rangeSize;
        freshIdentities = new JdbcFreshIdentityAllocator(plan.guard, plan.guard.ownerBootstrapDialect(), 2);
        activationCommit=new CacheInitialActivationCommit(database,plan.guard,nativeBridge,sql);
    }

    /** Internal factory: only the actual root-owned adapter can authorize native progress. */
    @SuppressWarnings("unchecked")
    static <T, ID> CacheInitialTenantBootstrap<T, ID> create(CacheDatabase database, EntityMetadata<T, ID> metadata,
            RedisTenantPurgePlan plan, CacheInitialNativeBridge<T, ID> nativeBridge, JdbcSourceOwnerOnboarding.JdbcIdCodec<ID> ids,
            ID firstInclusive, ID lastInclusive, int maxRows, int pageBytes, int rowBytes, long residentBytes, int rangeSize,
            Set<String> reviewedWindowFingerprints) {
        admin(); Objects.requireNonNull(database); Objects.requireNonNull(metadata); Objects.requireNonNull(nativeBridge);
        CacheInitialProfile.requireSupported(database);
        var raw = database.entityRegistry().find(metadata.entityName()).orElseThrow(() -> new ProtectionException(UNWIRED_PROTOCOL));
        EncryptedStorageChecks.sameMapping(metadata, raw.metadata());
        var binding = (EntityBinding<T, ID>) raw;
        var actual = binding.metadata();
        if (actual.entityType() != metadata.entityType() || actual.entityType() != plan.entityType()
                || plan.redis != database.redisOperations() || plan.keys != database.redisKeyStrategy()
                || !plan.guard.belongsTo(database.dataSource()) || !plan.allocator.belongsTo(plan.guard)
                || !actual.redisNamespace().equals(plan.scope()) || !actual.entityName().equals(plan.entityName)
                || !plan.manifestToken().equals(nativeBridge.manifestToken())) fail(UNWIRED_PROTOCOL);
        var participants = plan.manifest.requireAll();
        if (participants.stream().filter(p -> p instanceof JdbcInitialBootstrapPurgeParticipant j
                && j.belongsTo(plan.guard,plan.binding,plan.entityName)).count() != 1) fail(INCOMPLETE_INVENTORY);
        var codec = database.runtimeCodec(actual, binding.codec());
        if (codec != binding.codec()) fail(UNWIRED_PROTOCOL);
        var window = new InitialSourceHotWindow<>(plan.binding, actual, codec, ids, ids.formatToken(),
                firstInclusive, lastInclusive, maxRows, pageBytes, rowBytes, residentBytes,
                InitialSourceHotWindow.Profile.POINT_NO_QUOTA);
        nativeBridge.requireBinding(window);
        var sql = new JdbcInitialTenantBootstrap<>(plan.guard, plan.allocator, window, ids,
                plan.manifestToken(), 2, reviewedWindowFingerprints);
        nativeBridge.bindAcknowledgedPages(sql.acknowledgedPages());
        var service = new CacheInitialTenantBootstrap<>(database, binding, plan, sql, nativeBridge, rangeSize);
        service.requireProfile(); return service;
    }
    public InitialSourceHotWindow<T, ID> window() { return sql.window(); }

    /** One physically completed bounded phase/page. Reuse the same request after interruption. */
    public Progress step(Request request) {
        return inSession(request, session -> advance(request, session));
    }

    /** Administrative setup only; never creates an owner, reserves an ID or accepts an entity write. */
    public JdbcFreshIdentityAllocator.Status provisionIdentityDomain(Request request, FreshIdentityDomain domain) {
        admin(); requireProfile(); Objects.requireNonNull(domain);
        if (!plan.binding.fingerprint().equals(domain.bindingFingerprint())
                || !plan.binding.token().equals(domain.scopeToken())) fail(OPERATION_CONFLICT);
        return inSession(request, session -> {
            Phase phase = sql.phase(session);
            if (phase == Phase.SQL_OPEN || phase == Phase.ACTIVE || phase == Phase.INCOMPLETE) fail(WRITE_FENCED);
            Closure closure = nativeBridge.closeAndDrain(session);
            nativeBridge.requireClosed(session, closure);
            if (phase != Phase.CLOSURE) sql.checkClosure(session, closure);
            return freshIdentities.provisionInitial(session, domain);
        });
    }

    /**
     * Finite administrative provisioning, not a foreground refill or a replayable write receipt.
     * A committed range is consumed even if native admission or its response is lost. Never retry
     * by presenting an earlier reservation; use a new request and the next durable allocator epoch.
     * Persistent owner/zero-version markers consume Redis capacity until genuine tenant purge.
     * Zero is an unwritten baseline, not an allocated write version, entity or durability receipt.
     */
    public FreshIdentityRange reserveAndAdmitFreshRange(String tenant, FreshIdentityRange.Request request) {
        admin(); requireProfile(); Objects.requireNonNull(request);
        var admission = nativeBridge.freshIdentityAdmission(tenant, request);
        if (!work.tryAcquire()) fail(BUSY);
        boolean reserved = false;
        try (var physical = plan.wire.connect()) {
            requireProfile(); nativeBridge.requireBinding(sql.window());
            String run = admission.capture(physical);
            var range = freshIdentities.reserveOnceOpen(request);
            reserved = true;
            return plan.guard.inOpenVersionTransaction(request.generation(), request.domain().bindingFingerprint(),
                    (connection, control) -> {
                        if (control != range.controlEpoch()) throw new ProtectionException(RECOVERY_REQUIRED);
                        requireProfile(); nativeBridge.requireBinding(sql.window());
                        var grant = plan.guard.readOpenActivationGrant(connection, request.generation(),
                                request.domain().bindingFingerprint(), sql.window().fingerprint(), plan.manifestToken());
                        admission.admit(physical, run, range, grant);
                        return range;
                    });
        } catch (ProtectionException rejected) {
            throw rejected;
        } catch (RuntimeException unavailable) {
            throw new ProtectionException(reserved ? RECOVERY_REQUIRED : STORAGE_UNAVAILABLE);
        } finally { work.release(); }
    }

    /**
     * Explicit administrative SQL reservation followed by bounded native installation; never foreground SQL.
     * SQL commits first. On an ambiguous result retry the SAME request/count/expected epoch, or install
     * that exact latest range with installOpenRefill. Unused versions are never returned to the ledger.
     */
    public TenantVersionRange refillVersionRange(String tenant,TenantPurgeProtocol.Generation generation,
            int count,UUID request,long expectedReservationEpoch) {
        admin(); requireProfile(); Objects.requireNonNull(generation); Objects.requireNonNull(request);
        if(count<1 || count>TenantVersionRange.MAX_RANGE || expectedReservationEpoch<1) fail(INVALID_REQUEST);
        var refill=nativeBridge.openVersionRefill(tenant,generation);
        if(!work.tryAcquire()) fail(BUSY);
        boolean reserving=false;
        try(var physical=plan.wire.connect()) {
            requireProfile(); nativeBridge.requireBinding(sql.window());
            String run=refill.capture(physical);
            reserving=true;
            var range=plan.allocator.reserveOpenActivated(generation,plan.binding.fingerprint(),
                    sql.window().fingerprint(),plan.manifestToken(),count,request,expectedReservationEpoch,run);
            installOpenRefill(refill,physical,run,range);
            return range;
        } catch(ProtectionException rejected) { throw rejected; }
        catch(RuntimeException unavailable) {
            throw new ProtectionException(reserving?RECOVERY_REQUIRED:STORAGE_UNAVAILABLE);
        } finally { work.release(); }
    }

    /** Install only an exact, already committed latest SQL reservation under the locked OPEN guard. */
    public void installOpenRefill(String tenant,TenantVersionRange range) {
        admin(); requireProfile(); Objects.requireNonNull(range);
        var generation=new TenantPurgeProtocol.Generation(range.key(),range.generation());
        var refill=nativeBridge.openVersionRefill(tenant,generation);
        if(!work.tryAcquire()) fail(BUSY);
        try(var physical=plan.wire.connect()) {
            requireProfile(); nativeBridge.requireBinding(sql.window());
            installOpenRefill(refill,physical,refill.capture(physical),range);
        } catch(ProtectionException rejected) { throw rejected; }
        catch(RuntimeException unavailable) { throw new ProtectionException(RECOVERY_REQUIRED); }
        finally { work.release(); }
    }

    private void installOpenRefill(CacheOpenVersionRefill refill,com.reactor.cachedb.redis.BoundedRedisWire.Session physical,
            String run,TenantVersionRange range) {
        var generation=new TenantPurgeProtocol.Generation(range.key(),range.generation());
        plan.guard.inOpenVersionTransaction(generation,plan.binding.fingerprint(),(connection,control)->{
            if(control!=range.controlEpoch()) throw new ProtectionException(RECOVERY_REQUIRED);
            requireProfile(); nativeBridge.requireBinding(sql.window());
            var grant=plan.guard.readOpenActivationGrant(connection,generation,plan.binding.fingerprint(),
                    sql.window().fingerprint(),plan.manifestToken());
            refill.install(physical,run,range,grant);
            return null;
        });
    }

    private Progress advance(Request request, Session session) {
        Phase phase = sql.phase(session);
        if (phase == Phase.ACTIVE || phase == Phase.INCOMPLETE) return sql.progress(session);
        if (phase == Phase.SQL_OPEN) {
            recoverActivation(session);
            return sql.progress(session);
        }
        Closure closure = nativeBridge.closeAndDrain(session);
        nativeBridge.requireClosed(session, closure);
        if (phase != Phase.CLOSURE) sql.checkClosure(session, closure);
        sql.renew(session);
        switch (phase) {
            case CLOSURE -> sql.closed(session, closure);
            case NATIVE_INVENTORY -> {
                var inventory = plan.inventory.forInitial(session);
                var journal = plan.journal.forInitial(session);
                var discovery = new RedisTenantPurgeDiscovery(plan, session.lease(), request.generation().value(),
                        request.tenant(), inventory, journal, true);
                if (discovery.discover()) sql.nativeInventoryComplete(session);
            }
            case DURABLE_VERSIONS -> sql.scanDurableVersions(session);
            case SOURCE_VERSIONS -> sql.sourceInventory(session);
            case HOT_WINDOW -> {
                var page = sql.nextHotPage(session);
                nativeBridge.requireClosed(session, closure);
                nativeBridge.hydrate(session, closure, page);
                nativeBridge.requireClosed(session, closure);
                sql.acknowledgeHotPage(session, page);
            }
            case VERIFY_WINDOW -> {
                var page = sql.nextVerificationPage(session);
                if (page.isPresent()) {
                    nativeBridge.verifyPage(session, closure, page.get());
                    nativeBridge.requireClosed(session, closure);
                    sql.acknowledgeVerificationPage(session, page.get());
                } else {
                    String proof = nativeBridge.verifyWindow(session, closure, sql.windowProof(session));
                    nativeBridge.requireClosed(session, closure);
                    sql.verified(session, proof);
                }
            }
            case READY -> activate(session, closure);
            default -> throw new ProtectionException(INVALID_PROGRESS);
        }
        return sql.progress(session);
    }

    private <R> R inSession(Request request, Function<Session, R> operation) {
        admin(); requireProfile();
        if (!work.tryAcquire()) fail(BUSY);
        Session session = null; Throwable primary = null;
        try {
            requireProfile(); nativeBridge.requireBinding(sql.window());
            session = sql.acquire(request);
            return operation.apply(session);
        } catch (ProtectionException failure) {
            primary = failure;
            if (session != null && Set.of(INCOMPLETE_INVENTORY, MIGRATION_REQUIRED, GENERATION_MISMATCH, RECOVERY_REQUIRED).contains(failure.code())) {
                try { sql.incomplete(session); } catch (RuntimeException | Error cleanup) { suppress(primary, cleanup); }
            }
            throw failure;
        } catch (CacheEncryptionException failure) {
            primary = failure; throw failure;
        } catch (RuntimeException unsafe) {
            var safe = new ProtectionException(STORAGE_UNAVAILABLE); primary = safe; throw safe;
        } catch (Error fatal) {
            primary = fatal; throw fatal;
        } finally {
            try {
                if (session != null) {
                    try { sql.release(session); }
                    catch (RuntimeException | Error cleanup) {
                        Throwable safe = sanitized(cleanup);
                        if (primary != null) primary.addSuppressed(safe);
                        else if (safe instanceof Error fatal) throw fatal;
                        else throw (RuntimeException) safe;
                    }
                }
            } finally { work.release(); }
        }
    }
    private void recoverActivation(Session session) {
        Activation committed = sql.activation(session);
        // Every GC continuation observes the exact original OPEN; never re-close/reinstall/reserve.
        activationCommit.finish(session,committed,nativeBridge.observeOpenForFinalization(session,committed));
    }
    private void activate(Session session, Closure closure) {
        String proof = nativeBridge.verifyWindow(session, closure, sql.windowProof(session));
        nativeBridge.requireClosed(session, closure);
        var activation = sql.prepareActivation(session, closure, proof, rangeSize);
        nativeBridge.installRange(session, closure, activation.range());
        nativeBridge.requireClosed(session, closure);
        sql.openSql(session, activation);
        // A failure here is deliberately SQL_OPEN + Redis CLOSED, never a falsely ACTIVE result.
        nativeBridge.activate(session, closure, activation);
        activationCommit.finish(session,activation,nativeBridge.observeOpenForFinalization(session,activation));
    }
    private void requireProfile() {
        CacheInitialProfile.requireSupported(database);
        if (com.reactor.cachedb.core.route.RouteCacheContext.currentContract() != null
                || stopped.get() || database.config().runtimeRole() != CacheRuntimeRole.CLIENT_ONLY
                || database.config().readThrough().mode() != ReadThroughMode.REDIS_ONLY
                || !binding.metadata().tenantPurgeEnabled() || !binding.metadata().relations().isEmpty()
                || !database.entityRegistry().projections(binding.metadata().entityName()).isEmpty()
                || database.entityRegistry().find(binding.metadata().entityName()).orElse(null) != binding)
            fail(UNWIRED_PROTOCOL);
        for (var candidate : database.entityRegistry().all())
            if (candidate != binding && candidate.metadata().redisNamespace().equals(binding.metadata().redisNamespace()))
                fail(UNWIRED_PROTOCOL);
        plan.manifest.requireAll();
    }
    private static Throwable sanitized(Throwable cleanup) {
        return cleanup instanceof Error ? new Error("CacheDB initial bootstrap cleanup failed")
                : new ProtectionException(STORAGE_UNAVAILABLE);
    }
    private static void suppress(Throwable primary, Throwable cleanup) { primary.addSuppressed(sanitized(cleanup)); }
    private static void admin() { if (!CacheTenantContext.isAdministrative()) fail(ADMIN_REQUIRED); }
    private static void fail(ProtectionException.Code code) { throw new ProtectionException(code); }

    /**
     * Stops new calls only. A step admitted before close may commit its current bounded chunk;
     * its SQL lease/guard and physical permit remain owned until that synchronous call exits.
     * This is not a drain acknowledgement, rollback, or permission to discard recovery state.
     */
    @Override public void close() { stopped.set(true); }
}
