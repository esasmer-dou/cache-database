package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.config.*;
import com.reactor.cachedb.core.crypto.EncryptedStorageChecks;
import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.*;
import com.reactor.cachedb.core.registry.EntityBinding;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.jdbc.*;
import com.reactor.cachedb.redis.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.reactor.cachedb.core.protection.ProtectionException.Code.*;

/** A first-only administrative root binding; not a Redis-absence freshness inference or recovery API. */
public final class CacheInitialNamespace<T,ID> implements AutoCloseable {
    private final CacheDatabase database;
    private final DefaultCacheSession session;
    private final EntityBinding<T,ID> registered;
    private final JdbcTenantPurgeGuard guard;
    private final RedisClientOperations redis;
    private final RedisKeyStrategy keys;
    private final InitialSourceHotWindow<T,ID> window;
    private final JdbcNamespaceBootstrapGate.Request request;
    private final String rosterKey;
    private final int maxInFlight;
    private final AtomicBoolean attempted=new AtomicBoolean();
    private final AtomicBoolean stopped=new AtomicBoolean();
    private final java.util.concurrent.locks.ReentrantLock serviceBinding=new java.util.concurrent.locks.ReentrantLock();
    private CacheInitialNativeBridge<T,ID> nativeBridge;

    @SuppressWarnings("unchecked")
    static <T,ID> CacheInitialNamespace<T,ID> prepare(CacheDatabase database, DefaultCacheSession session,
            EntityMetadata<T,ID> supplied, Binding binding, JdbcTenantPurgeGuard guard,
            JdbcSourceOwnerOnboarding.JdbcIdCodec<ID> ids, ID first, ID last,
            int maxRows, int pageBytes, int rowBytes, long residentBytes, int maxInFlight) {
        admin();Objects.requireNonNull(guard);Objects.requireNonNull(supplied);
        CacheInitialProfile.requireSupported(database);
        if (maxInFlight<1 || maxInFlight>4096 || !guard.belongsTo(database.dataSource())) fail(UNWIRED_PROTOCOL);
        var raw=database.entityRegistry().find(supplied.entityName()).orElseThrow(() -> new ProtectionException(UNWIRED_PROTOCOL));
        EncryptedStorageChecks.sameMapping(supplied,raw.metadata());
        if(raw.metadata().entityType()!=supplied.entityType())fail(UNWIRED_PROTOCOL);
        var actual=(EntityBinding<T,ID>)raw;
        RedisInitialSourceLayout.requireBinding(actual.metadata(),database.redisKeyStrategy(),database.config().queryIndex(),binding);
        var codec=database.runtimeCodec(actual.metadata(),actual.codec());
        if(codec!=actual.codec())fail(UNWIRED_PROTOCOL);
        var window=new InitialSourceHotWindow<>(binding,actual.metadata(),codec,ids,ids.formatToken(),
                first,last,maxRows,pageBytes,rowBytes,residentBytes,InitialSourceHotWindow.Profile.POINT_NO_QUOTA);
        return new CacheInitialNamespace<>(database,session,actual,guard,window,maxInFlight);
    }

    private CacheInitialNamespace(CacheDatabase database,DefaultCacheSession session,EntityBinding<T,ID> registered,
            JdbcTenantPurgeGuard guard,InitialSourceHotWindow<T,ID> window,int maxInFlight) {
        this.database=database;this.session=session;this.registered=registered;this.guard=guard;this.window=window;
        this.maxInFlight=maxInFlight;redis=database.redisOperations();keys=database.redisKeyStrategy();
        rosterKey=RedisTenantRuntimeControl.rosterKey(keys,window.metadata().redisNamespace());
        requireProfile();redis.requireSameSlot(List.of(rosterKey));
        request=new JdbcNamespaceBootstrapGate.Request(ProtectionKey.hash(rosterKey),window.binding().fingerprint(),
                window.fingerprint(),readRun(),UUID.randomUUID());
    }

    public InitialSourceHotWindow<T,ID> window(){return window;}
    /** Inspectable identity for explicit administrator review; this request is NOT creation authority. */
    public JdbcNamespaceBootstrapGate.Request descriptor(){admin();requireProfile();return request;}

    /** Standard root path owns the concrete bridge; callers never receive its native activation capability. */
    @SuppressWarnings("unchecked")
    public CacheInitialTenantBootstrap<T,ID> createBootstrap(CacheTenantRuntimeEnrollment enrollment,RedisTenantPurgePlan plan,
            RedisTenantRuntimeControl.ApprovedRoster approval,int rangeSize,Set<String> reviewedWindowFingerprints) {
        admin();requireProfile();serviceBinding.lock();
        try {
            if(nativeBridge!=null || plan.guard!=guard || !plan.binding.equals(window.binding())
                    || !plan.manifestToken().equals(approval.manifestToken()))fail(UNWIRED_PROTOCOL);
            var stores=enrollment.initialSourceHydrator(registered.metadata(),window);
            nativeBridge=new CacheInitialNativeBridge<>(database,enrollment,plan,stores,window,approval);
            var service=CacheInitialTenantBootstrap.create(database,registered.metadata(),plan,nativeBridge,
                    (JdbcSourceOwnerOnboarding.JdbcIdCodec<ID>)window.ids(),window.ids().decode(window.firstCanonical()),
                    window.ids().decode(window.lastCanonical()),window.maxRows(),window.pageBytes(),window.rowBytes(),window.residentBytes(),
                    rangeSize,reviewedWindowFingerprints);
            database.ownResource(service);return service;
        } finally {serviceBinding.unlock();}
    }
    /** Read-only real lease installed by the SQL INITIAL coordinator, for explicit peer seal/bootstrap admission. */
    public Optional<RedisTenantRuntimeControl.ClosedPair> pendingClosure() {
        admin();serviceBinding.lock();
        try{return nativeBridge==null?Optional.empty():nativeBridge.pendingClosure();}
        finally{serviceBinding.unlock();}
    }

    /** Invoke BEFORE INITIAL.acquire. No ordinary registration path can invoke this private initializer. */
    public RedisTenantProducer bootstrap(JdbcNamespaceBootstrapGate gate,Generation generation) {
        admin();requireProfile();Objects.requireNonNull(gate);Objects.requireNonNull(generation);
        // Every resource/mapping check precedes both SQL consumption and the reservation callback.
        if(!gate.belongsTo(database.dataSource(),guard) || !window.binding().token().equals(generation.key().scopeToken())
                || redis!=database.redisOperations() || keys!=database.redisKeyStrategy()
                || !rosterKey.equals(RedisTenantRuntimeControl.rosterKey(keys,window.metadata().redisNamespace()))
                || !request.rosterKeyToken().equals(ProtectionKey.hash(rosterKey))
                || !request.bindingToken().equals(window.binding().fingerprint())
                || !request.mappingToken().equals(window.fingerprint()) || !request.redisRunId().equals(readRun())) fail(UNWIRED_PROTOCOL);
        requireAbsent();
        if(!attempted.compareAndSet(false,true))fail(MIGRATION_REQUIRED);
        var initializer=new OnceInitializer();
        try {
            var producer=session.configureTenantProducer(registered.metadata(),maxInFlight,request.incarnationId(),
                    () -> gate.consumeOnce(window,generation,request,initializer));
            if(!initializer.dispatched.get() || !request.incarnationId().equals(producer.runtimeId())) {
                producer.close();fail(RECOVERY_REQUIRED);
            }
            database.ownResource(producer);return producer;
        }catch(ProtectionException known){throw known;}
        catch(RuntimeException unsafe){throw new ProtectionException(STORAGE_UNAVAILABLE);}
    }

    private final class OnceInitializer implements JdbcNamespaceBootstrapGate.NativeInitializer {
        private final AtomicBoolean dispatched=new AtomicBoolean();
        private OnceInitializer(){}
        public void initialize(JdbcNamespaceBootstrapGate.Request committed) {
            admin();requireProfile();
            if(!request.equals(committed) || !request.incarnationId().equals(committed.incarnationId())
                    || !request.rosterKeyToken().equals(ProtectionKey.hash(rosterKey))
                    || !request.mappingToken().equals(window.fingerprint())
                    || redis!=database.redisOperations() || keys!=database.redisKeyStrategy())fail(UNWIRED_PROTOCOL);
            // Consumed BEFORE dispatch, including an unknown outcome/lost response. Never reset it.
            if(!dispatched.compareAndSet(false,true))fail(MIGRATION_REQUIRED);
            Object value=safe(() -> redis.eval("""
                local run=string.match(redis.call('INFO','SERVER'),'run_id:([0-9a-f]+)')
                if not run or run~=ARGV[1] then return redis.error_reply('CDB_RUNTIME_RECOVERY_REQUIRED') end
                if redis.call('TYPE',KEYS[1]).ok~='none' then return redis.error_reply('CDB_RUNTIME_MIGRATION_REQUIRED') end
                redis.call('HSET',KEYS[1],'schema','2','state','COLLECTING','enrolled','1',
                    'required','0','pairs','0','run_id',run,'approval','','manifest','','coverage','','e:'..ARGV[2],'1')
                return 1
                """,List.of(rosterKey),List.of(request.redisRunId(),request.incarnationId().toString())));
            if(!(value instanceof Number count) || count.longValue()!=1)fail(RECOVERY_REQUIRED);
        }
    }

    private void requireAbsent(){
        Object value=safe(() -> redis.eval("return redis.call('TYPE',KEYS[1]).ok",List.of(rosterKey),List.of()));
        if(!"none".equals(value))fail(MIGRATION_REQUIRED);
    }
    private String readRun(){
        CacheInitialProfile.requireStandaloneServer(database);
        Object value=safe(() -> redis.eval("return string.match(redis.call('INFO','SERVER'),'run_id:([0-9a-f]+)')",List.of(rosterKey),List.of()));
        if(!(value instanceof String run) || !run.matches("[0-9a-f]{40}"))throw new ProtectionException(RECOVERY_REQUIRED);
        return (String)value;
    }
    private void requireProfile(){
        CacheInitialProfile.requireSupported(database);
        if(stopped.get() || database.config().runtimeRole()!=CacheRuntimeRole.CLIENT_ONLY
                || database.config().readThrough().mode()!=ReadThroughMode.REDIS_ONLY
                || com.reactor.cachedb.core.route.RouteCacheContext.currentContract()!=null
                || database.entityRegistry().find(registered.metadata().entityName()).orElse(null)!=registered
                || !database.entityRegistry().projections(registered.metadata().entityName()).isEmpty()
                || !registered.metadata().relations().isEmpty() || !guard.belongsTo(database.dataSource()))fail(UNWIRED_PROTOCOL);
        if(EncryptedStorageChecks.protectedEntity(registered.metadata()) && database.runtimeOptions().keyProvider()==null)
            fail(UNWIRED_PROTOCOL);
        for(var other:database.entityRegistry().all())
            if(other!=registered && other.metadata().redisNamespace().equals(registered.metadata().redisNamespace()))fail(UNWIRED_PROTOCOL);
        RedisInitialSourceLayout.requireBinding(registered.metadata(),keys,database.config().queryIndex(),window.binding());
        var policy=registered.cachePolicy()==null?database.config().resourceLimits().defaultCachePolicy():registered.cachePolicy();
        if(!policy.admissionEnabled() || policy.hotEntityLimit()>0 && policy.hotEntityLimit()<window.maxRows())fail(UNWIRED_PROTOCOL);
        var fresh=new InitialSourceHotWindow<>(window.binding(),registered.metadata(),registered.codec(),window.ids(),window.idFormatToken(),
                window.ids().decode(window.firstCanonical()),window.ids().decode(window.lastCanonical()),window.maxRows(),window.pageBytes(),
                window.rowBytes(),window.residentBytes(),window.profile());
        if(!window.fingerprint().equals(fresh.fingerprint()) || database.runtimeCodec(registered.metadata(),registered.codec())!=registered.codec())
            fail(UNWIRED_PROTOCOL);
    }
    private static <V> V safe(java.util.function.Supplier<V> action) {
        try{return action.get();}catch(ProtectionException known){throw known;}
        catch(RuntimeException unsafe){throw new ProtectionException(STORAGE_UNAVAILABLE);}
    }
    private static void admin(){if(!CacheTenantContext.isAdministrative())fail(ADMIN_REQUIRED);}
    private static void fail(ProtectionException.Code code){throw new ProtectionException(code);}
    @Override public void close(){stopped.set(true);}
}
