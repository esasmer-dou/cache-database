package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.config.CacheRuntimeRole;
import com.reactor.cachedb.core.config.ReadThroughMode;
import com.reactor.cachedb.core.model.EntityMetadata;
import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.SourceOwnerOnboarding.*;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.jdbc.JdbcSourceOwnerOnboarding;
import com.reactor.cachedb.redis.*;
import com.reactor.cachedb.redis.RedisTenantRuntimeControl.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.reactor.cachedb.core.protection.ProtectionException.Code.*;

/** Actual-root administrative existing-identity adapter. Never activates a generation or inventories SQL history. */
public final class CacheSourceOwnerOnboarding<I> {
    private final Plan plan;
    private final Bridge<I> bridge;
    private final SourceOwnerBootstrap<I> coordinator;

    private CacheSourceOwnerOnboarding(Plan plan, Bridge<I> bridge, JdbcSourceOwnerOnboarding<I> store) {
        this.plan=plan; this.bridge=bridge; coordinator=new SourceOwnerBootstrap<>(store,bridge,1);
    }

    static <T,I> CacheSourceOwnerOnboarding<I> prepare(CacheDatabase database, EntityMetadata<T,I> metadata,
            CacheTenantRuntimeEnrollment enrollment, RedisTenantPurgePlan nativePlan, ApprovedRoster approval,
            JdbcSourceOwnerOnboarding.JdbcIdCodec<I> ids, List<I> candidates, Set<String> reviewedPlans) {
        admin(); Objects.requireNonNull(ids); Objects.requireNonNull(nativePlan); Objects.requireNonNull(approval);
        ids=nativeIds(ids.formatToken());
        Plan plan=new Plan(nativePlan.binding,nativePlan.versionColumn,ids.formatToken());
        var pages=pages(candidates,ids,Math.min(plan.binding().maxBatchBytes(),SourceOwnerOnboarding.MAX_PAGE_BYTES),
                plan.binding().maxWorkItems());
        var bridge=new Bridge<>(database,metadata,enrollment,nativePlan,approval,ids,plan,pages);
        bridge.requireBinding();
        var store=new JdbcSourceOwnerOnboarding<>(nativePlan.guard,plan,ids,2,reviewedPlans);
        return new CacheSourceOwnerOnboarding<>(plan,bridge,store);
    }

    @SuppressWarnings("unchecked")
    static <I> JdbcSourceOwnerOnboarding.JdbcIdCodec<I> nativeIds(String token) {
        if (JdbcSourceOwnerOnboarding.stringIds().formatToken().equals(token))
            return (JdbcSourceOwnerOnboarding.JdbcIdCodec<I>)JdbcSourceOwnerOnboarding.stringIds();
        if (JdbcSourceOwnerOnboarding.longIds().formatToken().equals(token))
            return (JdbcSourceOwnerOnboarding.JdbcIdCodec<I>)JdbcSourceOwnerOnboarding.longIds();
        throw new ProtectionException(UNWIRED_PROTOCOL);
    }

    public Plan plan() { return plan; }
    /** Candidate configuration only; the actual native inventory seal is created under FENCING. */
    public String worklistToken() { return bridge.worklist; }
    public Checkpoint step(Request request) {
        bridge.requireBinding();
        if (request==null || !request.plan().equals(plan)) fail(INVALID_REQUEST);
        Checkpoint result=coordinator.step(request);
        if (result.inventoryToken()!=null && !bridge.token(request).equals(result.inventoryToken())) fail(INVALID_PROGRESS);
        return result;
    }
    /** Peers must seal/poll this SAME lease using their real enrollment while this step drains. */
    public Optional<ClosedPair> pendingClosure() { admin(); return Optional.ofNullable(bridge.pending); }

    static <I> List<Page> pages(List<I> candidates, IdCodec<I> ids, int maxBytes, int maxItems) {
        if (candidates==null || candidates.isEmpty() || candidates.size()>Math.min(4096,maxItems)) fail(INVALID_REQUEST);
        var canonical=new ArrayList<String>(); var seen=new HashSet<String>(); int bytes=0;
        String digest=ProtectionKey.hash("source-owner-worklist-v1");
        for (I id:candidates) {
            String value=SourceOwnerOnboarding.canonicalId(ids.encode(id));
            if (!(id instanceof String || id instanceof Long) || !value.equals(id.toString())
                    || !Objects.equals(id,ids.decode(value)) || !seen.add(value)) fail(INVALID_REQUEST);
            bytes=Math.addExact(bytes,value.getBytes(StandardCharsets.UTF_8).length);
            if (bytes>1_048_576 || value.getBytes(StandardCharsets.UTF_8).length>maxBytes) fail(INVALID_REQUEST);
            canonical.add(value); digest=ProtectionKey.hash(digest+":"+ProtectionKey.hash(value));
        }
        var chunks=new ArrayList<List<String>>(); var chunk=new ArrayList<String>(); int used=0;
        for (String value:canonical) {
            int size=value.getBytes(StandardCharsets.UTF_8).length;
            if (!chunk.isEmpty() && (chunk.size()==SourceOwnerOnboarding.CHUNK || used+size>maxBytes)) {
                chunks.add(List.copyOf(chunk)); chunk.clear(); used=0;
            }
            chunk.add(value); used+=size;
        }
        chunks.add(List.copyOf(chunk)); if (chunks.size()>128) fail(INVALID_REQUEST);
        var result=new ArrayList<Page>();
        for (int i=0;i<chunks.size();i++) {
            String seal=ProtectionKey.hash(digest+":"+i);
            UUID page=UUID.nameUUIDFromBytes(seal.getBytes(StandardCharsets.US_ASCII));
            result.add(new Page(page,i==chunks.size()-1?"-":seal,i==chunks.size()-1,chunks.get(i)));
        }
        return List.copyOf(result);
    }

    private static final class Bridge<I> implements ClosedRuntime<I> {
        final CacheDatabase database;
        final EntityMetadata<?,I> metadata;
        final CacheTenantRuntimeEnrollment enrollment;
        final RedisTenantPurgePlan nativePlan;
        final ApprovedRoster approval;
        final JdbcSourceOwnerOnboarding.JdbcIdCodec<I> ids;
        final Plan plan;
        final List<Page> pages;
        final Map<String,Page> cursors;
        final String worklist;
        final Object registered;
        volatile ClosedPair pending;
        private Session current;
        private TenantReadFence.Evidence evidence;

        Bridge(CacheDatabase database, EntityMetadata<?,I> metadata, CacheTenantRuntimeEnrollment enrollment,
                RedisTenantPurgePlan nativePlan, ApprovedRoster approval, JdbcSourceOwnerOnboarding.JdbcIdCodec<I> ids,
                Plan plan, List<Page> pages) {
            this.database=Objects.requireNonNull(database); this.metadata=Objects.requireNonNull(metadata);
            this.enrollment=Objects.requireNonNull(enrollment); this.nativePlan=nativePlan; this.approval=approval;
            this.ids=ids; this.plan=plan; this.pages=pages;
            registered=database.entityRegistry().find(metadata.entityName()).orElseThrow(()->new ProtectionException(UNWIRED_PROTOCOL));
            var map=new LinkedHashMap<String,Page>(); String cursor="-", seal=plan.fingerprint();
            for (Page page:pages) {
                map.put(cursor,page); cursor=page.nextCursor();
                seal=ProtectionKey.hash(seal+":"+page.id());
                for (String id:page.ids()) seal=ProtectionKey.hash(seal+":"+ProtectionKey.hash(id));
            }
            cursors=Map.copyOf(map);
            seal=ProtectionKey.hash(seal+":"+approval.coverageToken());
            for (UUID member:approval.members()) seal=ProtectionKey.hash(seal+":"+member);
            for (ProtectionKey pair:approval.pairs()) seal=ProtectionKey.hash(seal+":"+pair.scopeToken()+":"+pair.tenantToken());
            worklist=ProtectionKey.hash("source-owner-native-v1:"+seal+":"+nativePlan.manifestToken()+":"+approval.approvalToken());
        }

        void requireBinding() {
            admin(); CacheInitialProfile.requireSupported(database);
            var actual=database.entityRegistry().find(metadata.entityName()).orElse(null);
            var descriptor=enrollment.descriptor(metadata.entityName());
            if (actual!=registered || actual==null || actual.metadata()!=metadata
                    || database.config().runtimeRole()!=CacheRuntimeRole.CLIENT_ONLY
                    || database.config().readThrough().mode()!=ReadThroughMode.REDIS_ONLY
                    || !metadata.relations().isEmpty() || !database.entityRegistry().projections(metadata.entityName()).isEmpty()
                    || com.reactor.cachedb.core.route.RouteCacheContext.currentContract()!=null
                    || nativePlan.redis!=database.redisOperations() || nativePlan.keys!=database.redisKeyStrategy()
                    || !nativePlan.guard.belongsTo(database.dataSource()) || nativePlan.entityType()!=metadata.entityType()
                    || !metadata.tenantPurgeEnabled() || !nativePlan.binding.source().table().equals(metadata.tableName())
                    || !nativePlan.binding.source().idColumn().equals(metadata.idColumn())
                    || !nativePlan.binding.source().tenantColumn().equals(metadata.tenantFieldName())
                    || !nativePlan.entityName.equals(metadata.entityName()) || !nativePlan.versionColumn.equals(metadata.versionColumn())
                    || nativePlan.barrier!=enrollment.barrier(metadata.entityName()) || nativePlan.readFence!=enrollment.readFence(metadata.entityName())
                    || nativePlan.targets.size()!=1 || nativePlan.targets.getFirst()!=enrollment.pointProfileTarget(metadata.entityName())
                    || !nativePlan.localL1.isEmpty() || !nativePlan.namespaces.equals(Set.of(metadata.redisNamespace()))
                    || !nativePlan.manifest.requireAll().equals(List.of(enrollment.controlParticipant(metadata.entityName())))
                    || metadata.columns().size()>128 || new HashSet<>(metadata.columns()).size()!=metadata.columns().size()
                    || nativePlan.binding.maxKeysPerObject()<9
                    || !approval.manifestToken().equals(nativePlan.manifestToken()) || !approval.coverageToken().equals(descriptor.coverageToken())
                    || !approval.members().contains(descriptor.runtimeId()) || approval.pairs().size()!=1) fail(UNWIRED_PROTOCOL);
            for (String field:metadata.columns())
                if (field==null || !field.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")) fail(UNWIRED_PROTOCOL);
            RedisInitialSourceLayout.requireBinding(metadata,database.redisKeyStrategy(),database.config().queryIndex(),nativePlan.binding);
            Set<String> declared=new HashSet<>(); for (var route:nativePlan.queues) declared.add(route.stream());
            var write=database.config().writeBehind();
            if (!declared.containsAll(write.activeStreamKeys()) || !declared.contains(write.streamKey())
                    || !declared.contains(write.deadLetterStreamKey()) || !declared.contains(write.compactionStreamKey())) fail(UNWIRED_PROTOCOL);
            for (var other:database.entityRegistry().all())
                if (other!=registered && other.metadata().redisNamespace().equals(metadata.redisNamespace())) fail(UNWIRED_PROTOCOL);
        }

        void requireSession(Session session) {
            requireBinding();
            if (session==null || !session.request().plan().equals(plan)
                    || !approval.pairs().contains(session.request().generation().key())) fail(INVALID_REQUEST);
        }
        String token(Request request) {
            return installer(request).inventoryToken(request);
        }
        public String closeAndDrain(Session session) {
            drain(session);
            return installer(session.request()).seal(session);
        }
        private void drain(Session session) {
            requireSession(session); CacheInitialProfile.requireStandaloneServer(database);
            current=null; evidence=null;
            nativePlan.barrier.close(session.lease(),session.request().generation().value());
            for (var route:nativePlan.queues) route.fence().close(session.lease(),session.request().generation().value());
            pending=new ClosedPair(session.lease(),session.request().generation().value());
            enrollment.sealClosed(nativePlan,approval,List.of(pending));
            var drained=CacheInitialNativeBridge.awaitClosure(()->nativePlan.readFence.closeAndDrain(session.lease(),
                    session.request().generation().value(),nativePlan.manifestToken(),"",128,5000),
                    System::nanoTime,java.util.concurrent.locks.LockSupport::parkNanos);
            if (drained.state()!=TenantReadFence.State.CLOSED) fail(INCOMPLETE_INVENTORY);
            evidence=drained; current=session;
        }
        public void requireClosed(Session session,String inventory) {
            requireSession(session);
            // A JDBC step acquires a new physical lease; never reuse a prior epoch's ACKs.
            if (!session.equals(current)) drain(session);
            if (!token(session.request()).equals(inventory)) fail(INVALID_PROGRESS);
            nativePlan.readFence.requireClosed(session.lease(),session.request().generation().value(),nativePlan.manifestToken(),evidence);
        }
        public Page existingIdentities(Session session,String inventory,String cursor,int limit,int maxBytes) {
            requireClosed(session,inventory); Page page=cursors.get(cursor);
            if (page==null || page.ids().size()>limit || page.ids().stream()
                    .mapToInt(id->id.getBytes(StandardCharsets.UTF_8).length).sum()>maxBytes) fail(INVALID_PROGRESS);
            return page;
        }
        public Installed installExisting(Session session,String inventory,Page page,List<OwnerProof<I>> proofs) {
            requireClosed(session,inventory); if (!pages.contains(page)) fail(INVALID_PROGRESS);
            var installed=installer(session.request()).install(session,page,proofs); requireClosed(session,inventory); return installed;
        }
        public boolean verify(Session session,String inventory) {
            for (Page page:pages) {
                requireClosed(session,inventory);
                if (!installer(session.request()).verify(session,page)) return false;
            }
            requireClosed(session,inventory); return true;
        }
        RedisSourceOwnerInstaller<I> installer(Request request) {
            return enrollment.sourceOwnerInstaller(database,nativePlan,request.generation().key(),ids,pages,worklist);
        }
    }
    private static void admin() {
        if (!CacheTenantContext.isAdministrative()) fail(ADMIN_REQUIRED);
    }
    private static void fail(ProtectionException.Code code) { throw new ProtectionException(code); }
}
