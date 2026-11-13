package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.core.config.CacheRuntimeRole;
import com.reactor.cachedb.core.config.ReadThroughMode;
import com.reactor.cachedb.core.registry.EntityBinding;
import com.reactor.cachedb.core.crypto.EncryptedStorageChecks;
import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.InitialTenantBootstrap.*;
import com.reactor.cachedb.core.protection.InitialTenantBootstrap.Page;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.redis.*;
import com.reactor.cachedb.redis.RedisTenantRuntimeControl.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import com.reactor.cachedb.jdbc.InitialBootstrapJournalCodec;
import com.reactor.cachedb.jdbc.JdbcTenantPurgeGuard;
import static com.reactor.cachedb.core.protection.ProtectionException.Code.*;

/** Package-private actual-root adapter. Only the concrete SQL coordinator receives its activation capability. */
final class CacheInitialNativeBridge<T,ID> implements NativeBridge<T,ID> {
    private final CacheDatabase database;
    private final CacheTenantRuntimeEnrollment enrollment;
    private final RedisTenantPurgePlan plan;
    private final RedisInitialSourceHydrator<T,ID> stores;
    private final InitialSourceHotWindow<T,ID> window;
    private final EntityBinding<?,?> registered;
    private final ApprovedRoster approval;
    private final String rosterToken;
    private final AtomicReference<AcknowledgedPages<T,ID>> acknowledged=new AtomicReference<>();
    private volatile ClosedPair pending;

    CacheInitialNativeBridge(CacheDatabase database,CacheTenantRuntimeEnrollment enrollment,RedisTenantPurgePlan plan,
            RedisInitialSourceHydrator<T,ID> stores,InitialSourceHotWindow<T,ID> window,ApprovedRoster approval) {
        this.database=Objects.requireNonNull(database);this.enrollment=Objects.requireNonNull(enrollment);
        this.plan=Objects.requireNonNull(plan);this.stores=Objects.requireNonNull(stores);this.window=window;this.approval=approval;
        registered=database.entityRegistry().find(window.metadata().entityName()).orElseThrow(() -> new ProtectionException(UNWIRED_PROTOCOL));
        var descriptor=enrollment.descriptor(window.metadata().entityName());
        if(plan.redis!=database.redisOperations() || plan.keys!=database.redisKeyStrategy()
                || !plan.guard.belongsTo(database.dataSource()) || !plan.binding.equals(window.binding())
                || plan.barrier!=enrollment.barrier(plan.entityName) || plan.readFence!=enrollment.readFence(plan.entityName)
                || plan.targets.size()!=1 || plan.targets.getFirst()!=enrollment.pointProfileTarget(plan.entityName)
                || !plan.targets.getFirst().fingerprint().equals(stores.target().fingerprint())
                || !approval.manifestToken().equals(plan.manifestToken()) || !approval.coverageToken().equals(descriptor.coverageToken())
                || !approval.members().contains(stores.runtimeId()) || !stores.runtimeId().equals(descriptor.runtimeId())
                || approval.pairs().size()!=1 || !approval.pairs().getFirst().scopeToken().equals(window.binding().token())
                || !plan.manifest.requireAll().contains(stores.participant())
                || !plan.manifest.requireAll().contains(enrollment.controlParticipant(plan.entityName)))fail(UNWIRED_PROTOCOL);
        var memberDigest=InitialBootstrapProof.bytes(String.join(":",approval.members().stream().map(UUID::toString).toList())
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        rosterToken=ProtectionKey.hash("initial-roster-v1:"+approval.approvalToken()+":"+approval.manifestToken()+":"+memberDigest);
        requireBinding(window);
    }
    public String manifestToken(){return plan.manifestToken();}
    CacheFreshIdentityAdmission freshIdentityAdmission(String tenant, FreshIdentityRange.Request request) {
        requireBinding(window);
        return new CacheFreshIdentityAdmission(plan, window, approval, stores.runtimeId(), tenant, request);
    }
    CacheOpenVersionRefill openVersionRefill(String tenant,TenantPurgeProtocol.Generation generation) {
        requireBinding(window);
        return new CacheOpenVersionRefill(plan,approval,stores.runtimeId(),tenant,generation);
    }
    public void requireBinding(InitialSourceHotWindow<T,ID> actual) {
        admin();
        CacheInitialProfile.requireSupported(database);
        if(actual==null || actual.metadata()!=window.metadata() || actual.codec()!=window.codec() || actual.ids()!=window.ids()
                || !actual.fingerprint().equals(window.fingerprint()) || stores.window()!=window
                || database.config().runtimeRole()!=CacheRuntimeRole.CLIENT_ONLY
                || database.config().readThrough().mode()!=ReadThroughMode.REDIS_ONLY
                || database.entityRegistry().find(plan.entityName).orElse(null)!=registered
                || registered.metadata()!=window.metadata() || registered.codec()!=window.codec()
                || database.runtimeCodec(window.metadata(),window.codec())!=window.codec()
                || !database.entityRegistry().projections(plan.entityName).isEmpty()
                || !window.metadata().relations().isEmpty() || com.reactor.cachedb.core.route.RouteCacheContext.currentContract()!=null)fail(UNWIRED_PROTOCOL);
        if(EncryptedStorageChecks.protectedEntity(window.metadata()) && database.runtimeOptions().keyProvider()==null)fail(UNWIRED_PROTOCOL);
        for(var other:database.entityRegistry().all())
            if(other!=registered && other.metadata().redisNamespace().equals(window.metadata().redisNamespace()))fail(UNWIRED_PROTOCOL);
        RedisInitialSourceLayout.requireBinding(window.metadata(),database.redisKeyStrategy(),database.config().queryIndex(),window.binding());
        var fresh=new InitialSourceHotWindow<>(window.binding(),window.metadata(),window.codec(),window.ids(),window.idFormatToken(),
                window.ids().decode(window.firstCanonical()),window.ids().decode(window.lastCanonical()),window.maxRows(),window.pageBytes(),
                window.rowBytes(),window.residentBytes(),window.profile());
        if(!fresh.fingerprint().equals(window.fingerprint()))fail(UNWIRED_PROTOCOL);
    }
    public void bindAcknowledgedPages(AcknowledgedPages<T,ID> reader) {
        requireBinding(window);Objects.requireNonNull(reader);
        if(!reader.planToken().equals(window.fingerprint()) || !reader.manifestToken().equals(manifestToken())
                || !acknowledged.compareAndSet(null,reader))fail(UNWIRED_PROTOCOL);
    }
    public Closure closeAndDrain(Session session) {
        requireSession(session);
        plan.barrier.close(session.lease(),session.request().generation().value());
        // The real inventory snapshots stream-slot fences, not the entity-slot barrier.
        for(var route:plan.queues)safe(() -> {
            route.fence().close(session.lease(),session.request().generation().value());return null;
        });
        pending=new ClosedPair(session.lease(),session.request().generation().value());
        enrollment.sealClosed(plan,approval,List.of(pending));
        // Keep this physical SQL lease while peers observe its closure request; a new step has a new epoch.
        var evidence=awaitClosure(() -> plan.readFence.closeAndDrain(session.lease(),session.request().generation().value(),
                manifestToken(),"",128,5000),System::nanoTime,java.util.concurrent.locks.LockSupport::parkNanos);
        if(evidence.state()!=TenantReadFence.State.CLOSED)fail(INCOMPLETE_INVENTORY);
        var closure=new Closure(evidence,stores.runId(),rosterToken);requireClosed(session,closure);return closure;
    }
    Optional<ClosedPair> pendingClosure(){admin();return Optional.ofNullable(pending);}
    static TenantReadFence.Evidence awaitClosure(java.util.function.Supplier<TenantReadFence.Evidence> poll,
            java.util.function.LongSupplier clock,java.util.function.LongConsumer pause) {
        long deadline=clock.getAsLong()+5_000_000_000L;
        for(int round=0;round<32;round++) {
            if(deadline-clock.getAsLong()<=0 || Thread.currentThread().isInterrupted())fail(BUSY);
            var evidence=poll.get();
            long remaining=deadline-clock.getAsLong();
            if(remaining<=0 || Thread.currentThread().isInterrupted())fail(BUSY);
            if(evidence.state()!=TenantReadFence.State.DRAINING)return evidence;
            if(round==31)fail(BUSY);
            // Preserve two fast probes, then leave healthy peer loops time to ACK this SAME lease.
            long interval=round<2?Math.min(10_000_000L,remaining):Math.max(1L,remaining/(32-round));
            pause.accept(interval);
        }
        throw new AssertionError("Bounded closure rounds exhausted");
    }
    public void requireClosed(Session session,Closure closure) {
        requireSession(session);Objects.requireNonNull(closure);
        if(!closure.rosterToken().equals(rosterToken) || !closure.runId().equals(stores.runId()))fail(RECOVERY_REQUIRED);
        plan.readFence.requireClosed(session.lease(),session.request().generation().value(),manifestToken(),closure.evidence());
    }
    public void hydrate(Session session,Closure closure,Page<T,ID> page) {
        requireClosed(session,closure);safe(() -> {stores.hydrate(session,page);return null;});requireClosed(session,closure);
    }
    public void verifyPage(Session session,Closure closure,Page<T,ID> page) {
        requireClosed(session,closure);safe(() -> stores.verify(session,page));requireClosed(session,closure);
    }
    public String verifyWindow(Session session,Closure closure,WindowProof summary) {
        requireClosed(session,closure);var reader=acknowledged.get();if(reader==null)throw new ProtectionException(UNWIRED_PROTOCOL);
        String digest=ProtectionKey.hash("initial-window-v1:"+window.fingerprint());
        long payloadBytes=0,encodedBytes=0,expiry=0,rows=0;int cursor=0,pageCount=0;var identities=new HashSet<String>();
        while(true) {
            if(++pageCount>InitialSourceHotWindow.MAX_PAGES)fail(INCOMPLETE_INVENTORY);
            final int pageCursor=cursor;
            var captured=safe(() -> reader.read(session,pageCursor,window.pageBytes()));
            if(captured.pageNumber()!=cursor || !captured.verified() || !captured.window().equals(summary)
                    || captured.encodedBytes()>window.pageBytes() || captured.page().last()!=(captured.nextPage()==-1))fail(INCOMPLETE_INVENTORY);
            encodedBytes+=captured.encodedBytes();if(encodedBytes>window.residentBytes())fail(INVALID_REQUEST);
            var verified=safe(() -> stores.verify(session,captured.page()));
            payloadBytes+=verified.payloadBytes();if(payloadBytes>window.residentBytes())fail(INVALID_REQUEST);
            if(verified.earliestExpiry()>0 && (expiry==0 || verified.earliestExpiry()<expiry))expiry=verified.earliestExpiry();
            for(var row:captured.page().rows()) {
                if(!identities.add(row.proof().canonicalId()) || ++rows>window.maxRows())fail(INCOMPLETE_INVENTORY);
                digest=ProtectionKey.hash(digest+":"+row.proof().digest());
            }
            requireClosed(session,closure);
            if(captured.nextPage()==-1)break;
            if(captured.nextPage()!=cursor+1)fail(INCOMPLETE_INVENTORY);
            cursor=captured.nextPage();
        }
        if(rows!=summary.rows() || !digest.equals(summary.digest()))fail(INCOMPLETE_INVENTORY);
        String proof=ProtectionKey.hash("initial-native-verify-v1:"+session.planToken()+":"+manifestToken()+":"+summary.digest()
                +":"+closure.evidence().token()+":"+rows+":"+payloadBytes+":"+expiry);
        final long sealedBytes=payloadBytes,sealedExpiry=expiry;final int sealedPages=pageCount;
        var result=safe(() -> stores.seal(session,summary,sealedPages,sealedBytes,sealedExpiry,proof));
        requireClosed(session,closure);return result;
    }
    public void installRange(Session session,Closure closure,TenantVersionRange range) {
        requireClosed(session,closure);safe(() -> {stores.installRange(session,range);return null;});requireClosed(session,closure);
    }
    public RuntimeState observeActivation(Session session,Activation activation) {
        requireSession(session);
        try{return stores.observe(session,activation);}catch(RuntimeException rejected){return RuntimeState.INCOMPLETE;}
    }
    /** No caller-supplied observer or receipt: this concrete bridge performs the physical read. */
    OpenObservation observeOpenForFinalization(Session session,Activation activation) {
        requireSession(session);
        try {
            if(stores.observeForFinalization(session,activation)!=RuntimeState.OPEN)fail(RECOVERY_REQUIRED);
        }catch(RuntimeException rejected){throw new ProtectionException(RECOVERY_REQUIRED);}
        return new OpenObservation(this,database,plan.guard,session,activation);
    }
    static final class OpenObservation {
        private final CacheInitialNativeBridge<?,?> issuer;
        private final CacheDatabase root;
        private final javax.sql.DataSource source;
        private final JdbcTenantPurgeGuard guard;
        private final Session session;
        private final List<String> activation;
        private final Thread owner=Thread.currentThread();
        private final AtomicBoolean consumed=new AtomicBoolean();
        private OpenObservation(CacheInitialNativeBridge<?,?> issuer,CacheDatabase root,JdbcTenantPurgeGuard guard,
                Session session,Activation activation) {
            this.issuer=issuer;this.root=root;this.source=root.dataSource();this.guard=guard;
            this.session=session;this.activation=InitialBootstrapJournalCodec.activationFields(activation);
        }
        void consume(CacheInitialNativeBridge<?,?> expected,CacheDatabase actual,JdbcTenantPurgeGuard actualGuard,
                Session actualSession,Activation actualActivation) {
            if(owner!=Thread.currentThread() || issuer!=expected || root!=actual || source!=actual.dataSource()
                    || guard!=actualGuard || !guard.belongsTo(source) || !session.equals(actualSession)
                    || !activation.equals(InitialBootstrapJournalCodec.activationFields(actualActivation))
                    || !consumed.compareAndSet(false,true))fail(RECOVERY_REQUIRED);
        }
        @Override public String toString(){return "NativeOpenObservation[redacted]";}
    }
    public void activate(Session session,Closure closure,Activation activation) {
        requireClosed(session,closure);safe(() -> {stores.activate(session,activation);return null;});
    }
    private void requireSession(Session session) {
        requireBinding(window);Objects.requireNonNull(session);
        if(!session.planToken().equals(window.fingerprint()) || !session.manifestToken().equals(manifestToken())
                || !window.binding().key(session.request().tenant()).equals(session.lease().key())
                || !approval.pairs().contains(session.lease().key()))fail(UNWIRED_PROTOCOL);
    }
    private static <V> V safe(java.util.function.Supplier<V> action) {
        try{return action.get();}catch(ProtectionException known){throw known;}
        catch(RuntimeException unsafe){throw new ProtectionException(STORAGE_UNAVAILABLE);}
    }
    private static void admin(){if(!CacheTenantContext.isAdministrative())fail(ADMIN_REQUIRED);}
    private static void fail(ProtectionException.Code code){throw new ProtectionException(code);}
}
