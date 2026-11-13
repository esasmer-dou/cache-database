package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.*;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.jdbc.JdbcPurgeFacadeJournal.Checkpoint;
import com.reactor.cachedb.redis.*;
import java.nio.charset.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Semaphore;

/**
 * Bounded, resumable administrative purge for ONE registered entity namespace + tenant.
 * Every invocation performs at most one durable work step; repeat with the same operation UUID.
 * All normal write/read paths remain Redis-first. This facade alone performs administrative SQL.
 * Completion never opens tenant admission. Undeclared arbitrary Spring Cache values are out of scope.
 */
public final class CacheDataProtection {
    public enum Status { RUNNING, COMPLETE, INCOMPLETE, MIGRATION_REQUIRED }
    public record Result(Status status,PurgeReceipt receipt,long queueEntries,String evidence,Continuation continuation) {
        public Result { Objects.requireNonNull(status);Objects.requireNonNull(receipt);if(queueEntries<0)throw invalid();if(evidence!=null)ProtectionKey.token(evidence); }
    }
    /** Explicit expiring administrative ownership, not a worker/thread or a persistent tenant executor. */
    public record Continuation(Class<?> entityClass,Lease lease) {
        public Continuation { Objects.requireNonNull(entityClass);Objects.requireNonNull(lease); }
        @Override public String toString(){return "PurgeContinuation[redacted]";}
    }
    public record EmptyActivationResult(EmptyTenantActivation.Checkpoint checkpoint, Continuation continuation) {
        public EmptyActivationResult { Objects.requireNonNull(checkpoint); }
    }
    private enum Phase {
        DRAIN,CLOSE_PARTICIPANTS,INVENTORY,DISCOVER_PARTICIPANTS,DURABLE_VERSIONS,SOURCE_MAX,QUEUES,COMPACTION,IDENTITIES,RESIDUAL,
        PARTICIPANTS,SOURCE,FRESH_AUDIT,VERIFY_IDENTITIES,VERIFY_PARTICIPANTS,RECEIPT_GC,
        DISPOSE_INVENTORY,DISPOSE_JOURNAL,CERTIFY,COMPLETE,REDIS_ONLY_DONE,INCOMPLETE
    }
    private final Map<Class<?>,RedisTenantPurgePlan> plans;
    private final com.reactor.cachedb.core.registry.EntityRegistry registry;
    private final Semaphore controls;

    /** No static executor, hidden scheduling, automatic source discovery or unbounded per-tenant resource. */
    public CacheDataProtection(com.reactor.cachedb.core.registry.EntityRegistry registry,
            Collection<RedisTenantPurgePlan> registrations,int maxConcurrentAdministrators){
        this.registry=Objects.requireNonNull(registry);
        if(registrations==null||registrations.isEmpty()||registrations.size()>64
                ||maxConcurrentAdministrators<1||maxConcurrentAdministrators>8)throw invalid();
        var map=new HashMap<Class<?>,RedisTenantPurgePlan>();var namespaces=new HashSet<String>();var tables=new HashSet<String>();
        for(var p:registrations){
            if(map.putIfAbsent(p.entityType(),p)!=null||!namespaces.add(p.scope())
                    ||!tables.add(p.binding.source().table().toLowerCase(Locale.ROOT)))throw invalid();
            // A source table shared by entity classes needs a separately reviewed discriminator adapter.
        }
        plans=Map.copyOf(map);controls=new Semaphore(maxConcurrentAdministrators,true);
    }

    public EmptyActivationResult reenableEmptyTenant(Class<?> entityClass, String tenant,
            EmptyTenantActivation.Request request) {
        return reenableEmptyTenant(entityClass, tenant, request, null);
    }

    /** Explicit administrator call per step; no scheduler, restore, source-owner or legacy conversion path. */
    public EmptyActivationResult reenableEmptyTenant(Class<?> entityClass, String tenant,
            EmptyTenantActivation.Request request, Continuation continuation) {
        if (!CacheTenantContext.isAdministrative()) throw new ProtectionException(ProtectionException.Code.ADMIN_REQUIRED);
        Objects.requireNonNull(request); ProtectionKey.checkTenant(tenant);
        PurgeOptions options = request.purge();
        if (!tenant.equals(options.confirmation())) throw new ProtectionException(ProtectionException.Code.CONFIRMATION_REQUIRED);
        var p = plans.get(entityClass);
        if (p == null) throw new ProtectionException(ProtectionException.Code.UNWIRED_PROTOCOL);
        if (!p.fingerprint.equals(request.manifestToken())) throw new ProtectionException(ProtectionException.Code.OPERATION_CONFLICT);
        // Dependent namespaces require a separately reviewed activation contract, not inferred reopen permission.
        if (p.targets.size() != 1 || p.namespaces.size() != 1) throw new ProtectionException(ProtectionException.Code.UNWIRED_PROTOCOL);
        if (!controls.tryAcquire()) throw new ProtectionException(ProtectionException.Code.BUSY);
        Lease lease = null; boolean retain = false; Throwable primary = null;
        try {
            validateRegistration(p, true);
            p.readFence.requireActivationCoverage(p.binding.key(tenant), p.fingerprint);
            var journal = new com.reactor.cachedb.jdbc.JdbcEmptyTenantActivation(p.guard, p.allocator,
                    p.binding, p.fingerprint, p.queues.size());
            var completed = journal.completed(tenant, request);
            if (completed.isPresent()) {
                p.barrier.observeEmptyActivation(completed.get());
                // Recheck current SQL generation after native observation; a subsequent purge is not this activation.
                if (journal.completed(tenant, request).isEmpty()) throw new ProtectionException(ProtectionException.Code.OPERATION_CONFLICT);
                return new EmptyActivationResult(completed.get(), null);
            }
            if (continuation == null) lease = p.guard.acquireForTenant(p.binding, tenant, options, true);
            else {
                lease = continuation.lease();
                if (continuation.entityClass() != entityClass || !lease.key().equals(p.binding.key(tenant))
                        || !lease.operationId().equals(options.operationId()) || lease.seconds() != options.leaseSeconds()) throw invalid();
                p.guard.renew(lease);
            }
            var cp = journal.prepare(lease, tenant, request);
            String run = p.barrier.prepareEmptyActivation(lease, cp);
            if (cp.phase() == EmptyTenantActivation.Phase.PREPARED) {
                if (cp.queueCursor() < p.queues.size()) {
                    p.queues.get(cp.queueCursor()).fence().prepareEmptyActivation(lease, cp);
                    cp = journal.queueAcknowledged(lease, cp);
                } else {
                    var evidence = p.readFence.closeAndDrain(lease, cp.identity().toGeneration(), p.fingerprint,
                            "", 128, Math.min(5000, options.maxCallMillis()));
                    if (evidence.state() == TenantReadFence.State.INCOMPLETE) throw incomplete();
                    if (evidence.state() == TenantReadFence.State.CLOSED) {
                        p.readFence.requireClosed(lease, cp.identity().toGeneration(), p.fingerprint, evidence);
                        p.guard.validateSource(p.binding);
                        cp = journal.openSql(lease, tenant, cp, run, TenantVersionRange.MAX_RANGE);
                    }
                }
            } else if (cp.queueCursor() < p.queues.size()) {
                p.queues.get(cp.queueCursor()).fence().openEmptyActivation(lease, cp);
                cp = journal.queueAcknowledged(lease, cp);
            } else {
                p.readFence.requireActivationCoverage(lease.key(), p.fingerprint);
                p.guard.renew(lease);
                var receipt = p.barrier.activateEmpty(lease, cp);
                cp = journal.complete(lease, cp, receipt);
            }
            retain = cp.phase() != EmptyTenantActivation.Phase.ACTIVE;
            return new EmptyActivationResult(cp, retain ? new Continuation(entityClass, lease) : null);
        } catch (RuntimeException | Error failure) {
            primary = failure; throw failure;
        } finally {
            Throwable cleanup = null;
            if (lease != null && !retain) {
                try { p.readFence.release(lease); } catch (RuntimeException | Error failure) { cleanup = cleanupFailure(cleanup, failure); }
                try { p.barrier.release(lease); } catch (RuntimeException | Error failure) { cleanup = cleanupFailure(cleanup, failure); }
                try { p.guard.release(lease); } catch (RuntimeException | Error failure) { cleanup = cleanupFailure(cleanup, failure); }
            }
            controls.release();
            if (cleanup != null && primary == null) throw new ProtectionException(ProtectionException.Code.STORAGE_UNAVAILABLE);
        }
    }

    public Result purgeTenant(Class<?> entityClass,String tenant,PurgeOptions options){
        return purgeTenant(entityClass,tenant,options,null);
    }
    /** Resume the SAME lease so remote runtime acknowledgements survive between bounded calls. */
    public Result purgeTenant(Class<?> entityClass,String tenant,PurgeOptions options,Continuation continuation){
        if(!CacheTenantContext.isAdministrative())throw new ProtectionException(ProtectionException.Code.ADMIN_REQUIRED);
        Objects.requireNonNull(options);ProtectionKey.checkTenant(tenant);
        if(!tenant.equals(options.confirmation()))throw new ProtectionException(ProtectionException.Code.CONFIRMATION_REQUIRED);
        var p=plans.get(entityClass);if(p==null)throw new ProtectionException(ProtectionException.Code.UNWIRED_PROTOCOL);
        if(!controls.tryAcquire())throw new ProtectionException(ProtectionException.Code.BUSY);
        Lease lease=null;boolean retain=continuation!=null,claimed=false;UUID call=UUID.randomUUID();
        Throwable primary=null;
        try{
            // SQL CLOSED commits before any Redis closure or destructive command.
            if(continuation==null)lease=p.guard.acquireForTenant(p.binding,tenant,options,false);
            else{
                lease=continuation.lease();
                if(continuation.entityClass()!=entityClass||!lease.key().equals(p.binding.key(tenant))
                        ||!lease.operationId().equals(options.operationId())||lease.seconds()!=options.leaseSeconds())throw invalid();
                p.guard.renew(lease);
            }
            p.journal.enterCall(lease,call);claimed=true;retain=false;
            var progress=p.guard.load(lease);
            if(progress.includeSource()!=options.includeSource()||!progress.bindingFingerprint().equals(p.binding.fingerprint()))throw invalid();
            var control=p.journal.create(lease,"control",Phase.DRAIN.name(),ascii(p.fingerprint),p.fingerprint);
            if(!Arrays.equals(control.payload(),ascii(p.fingerprint)))throw new ProtectionException(ProtectionException.Code.MIGRATION_REQUIRED);
            Phase phase=Phase.valueOf(control.cursor());
            if(phase==Phase.COMPLETE||phase==Phase.REDIS_ONLY_DONE||phase==Phase.INCOMPLETE)return result(p,lease,control);
            if(progress.stage()==Stage.REQUIRES_MIGRATION&&phase!=Phase.CERTIFY)
                return new Result(Status.INCOMPLETE,progress.receipt(lease.key()),control.removed(),control.evidence(),null);

            p.guard.renew(lease);p.barrier.close(lease,progress.closedGeneration());
            for(var route:p.queues)route.fence().close(lease,progress.closedGeneration());
            var drain=p.journal.create(lease,"read-drain","",new byte[0],p.fingerprint);
            var evidence=p.readFence.closeAndDrain(lease,progress.closedGeneration(),p.fingerprint,
                    drain.cursor(),128,options.maxCallMillis());
            if(evidence.state()==TenantReadFence.State.INCOMPLETE)return incomplete(p,lease,control);
            p.journal.commit(lease,drain,evidence.checkpoint(),new byte[0],
                    evidence.state()==TenantReadFence.State.CLOSED,evidence.token(),0,0,0);
            if(evidence.state()!=TenantReadFence.State.CLOSED){
                retain=true;
                return new Result(Status.RUNNING,progress.receipt(lease.key()),control.removed(),null,new Continuation(entityClass,lease));
            }
            try{
                closed(p,lease,progress.closedGeneration(),evidence);
                var run=new Run(p,lease,tenant,progress.closedGeneration(),evidence);
                step(run,phase,control,Math.min(128,options.maxRows()));
                closed(p,lease,progress.closedGeneration(),evidence);
                var result=result(p,lease,p.journal.get(lease,"control"));
                retain=result.status()==Status.RUNNING;
                return result;
            }catch(ProtectionException failure){
                if(failure.code()==ProtectionException.Code.INCOMPLETE_INVENTORY
                        ||failure.code()==ProtectionException.Code.MIGRATION_REQUIRED
                        ||failure.code()==ProtectionException.Code.UNWIRED_PROTOCOL)
                    return incomplete(p,lease,p.journal.get(lease,"control"));
                throw failure;
            }catch(BoundedRedisWire.Failure failure){
                if(failure.code()==BoundedRedisWire.Code.INPUT_LIMIT||failure.code()==BoundedRedisWire.Code.TOPOLOGY_CHANGED)
                    return incomplete(p,lease,p.journal.get(lease,"control"));
                throw new ProtectionException(ProtectionException.Code.STORAGE_UNAVAILABLE);
            }
        }catch(RuntimeException|Error failure){
            primary=failure;throw failure;
        }finally{
            Throwable cleanup=null;
            try{if(claimed)p.journal.leaveCall(lease,call);}
            catch(RuntimeException|Error failure){cleanup=cleanupFailure(cleanup,failure);}
            if(lease!=null&&!retain){
                // Physical I/O has exited; attempt every release even when another cleanup failed.
                try{p.readFence.release(lease);}catch(RuntimeException|Error failure){cleanup=cleanupFailure(cleanup,failure);}
                try{p.barrier.release(lease);}catch(RuntimeException|Error failure){cleanup=cleanupFailure(cleanup,failure);}
                try{p.guard.release(lease);}catch(RuntimeException|Error failure){cleanup=cleanupFailure(cleanup,failure);}
            }
            controls.release();
            if(cleanup!=null){
                if(primary!=null)primary.addSuppressed(cleanup);
                else if(cleanup instanceof RuntimeException failure)throw failure;
                else throw (Error)cleanup;
            }
        }
    }

    private static Throwable cleanupFailure(Throwable before,Throwable failure){
        Throwable safe=failure instanceof Error?failure:new ProtectionException(
                failure instanceof ProtectionException known?known.code():ProtectionException.Code.STORAGE_UNAVAILABLE);
        if(before==null)return safe;
        before.addSuppressed(safe);return before;
    }

    /** Abandon administrative ownership only; original-generation gates remain permanently CLOSED. */
    public void abandon(Continuation continuation){
        if(!CacheTenantContext.isAdministrative())throw new ProtectionException(ProtectionException.Code.ADMIN_REQUIRED);
        Objects.requireNonNull(continuation);
        var p=plans.get(continuation.entityClass());if(p==null)throw invalid();
        var lease=continuation.lease();
        if(!lease.key().scopeToken().equals(p.binding.token()))throw invalid();
        try{p.readFence.release(lease);}
        finally{try{p.barrier.release(lease);}finally{p.guard.release(lease);}}
    }

    private void step(Run r,Phase phase,Checkpoint control,int limit){
        var p=r.p;var lease=r.lease;var before=p.guard.load(lease);
        switch(phase){
            case DRAIN -> {
                validateRegistration(p,before.includeSource());
                p.manifest.requireAll();
                p.guard.validateSource(p.binding);
                var priorHistory=p.allocator.findStatusClosed(lease,r.generation).orElse(null);
                if(priorHistory!=null&&priorHistory.verifiedGeneration()==r.generation&&priorHistory.inventory()!=null){
                    var prior=p.journal.create(lease,"accepted-history",Long.toString(r.generation),
                            ascii(priorHistory.inventory()),ProtectionKey.hash(p.fingerprint+":"+r.generation+":"+priorHistory.inventory()));
                    if(!prior.done())p.journal.commit(lease,prior,prior.cursor(),prior.payload(),true,prior.evidence(),0,0,0);
                }
                p.allocator.beginInventory(lease,p.binding,r.generation);
                advance(r,control,Phase.CLOSE_PARTICIPANTS);
            }
            case CLOSE_PARTICIPANTS -> {if(r.external(PurgeParticipant.Phase.CLOSE))advance(r,control,Phase.INVENTORY);}
            case INVENTORY -> {if(r.discovery.discover())advance(r,control,Phase.DISCOVER_PARTICIPANTS);}
            case DISCOVER_PARTICIPANTS -> {if(r.external(PurgeParticipant.Phase.DISCOVER))advance(r,control,Phase.DURABLE_VERSIONS);}
            case DURABLE_VERSIONS -> {
                if(p.allocator.scanDurableFences(lease,r.generation,limit).durableComplete())advance(r,control,Phase.SOURCE_MAX);
            }
            case SOURCE_MAX -> {
                if(before.stage()==Stage.REDIS){advance(r,control,Phase.QUEUES);break;}
                p.allocator.probeSourceMaximum(lease,p.binding,r.generation,r.tenant,p.versionColumn);
                var sealed=p.allocator.sealInventory(lease,p.binding,r.generation,p.versionColumn);
                // Crash after global transition is resumed without resealing or repeating source mutations.
                if(before.stage()==Stage.FENCING){
                    var after=before.advance(Stage.REDIS,sealed.inventory(),TenantPurgeProtocol.START,null,0,
                            before.redisRows(),before.redisKeys(),before.sourceRows(),0,null);
                    p.guard.save(lease,before,after);
                }
                advance(r,control,Phase.QUEUES);
            }
            case QUEUES -> {if(r.eraseQueue())advance(r,control,Phase.COMPACTION);}
            case COMPACTION -> {if(r.eraseCompaction())advance(r,control,Phase.IDENTITIES);}
            case IDENTITIES -> {
                var applied=p.participants.step(lease,r.generation,r.identity,PurgeParticipant.Phase.ERASE,1048576);
                requireParticipant(applied.checkpoint().state());
                if(applied.checkpoint().state()==PurgeParticipant.State.VERIFIED)advance(r,control,Phase.RESIDUAL);
            }
            case RESIDUAL -> {if(r.eraseResidual())advance(r,control,Phase.PARTICIPANTS);}
            case PARTICIPANTS -> {if(r.external(PurgeParticipant.Phase.ERASE))advance(r,control,Phase.SOURCE);}
            case SOURCE -> {
                if(before.stage()==Stage.VERIFYING){advance(r,control,Phase.FRESH_AUDIT);break;}
                if(before.stage()==Stage.REDIS){
                    p.guard.save(lease,before,before.advance(before.includeSource()?Stage.SOURCE:Stage.VERIFYING,
                            before.inventoryToken(),before.cursor(),null,0,before.redisRows(),before.redisKeys(),before.sourceRows(),0,null));
                }
                if(before.includeSource()){
                    p.guard.validateSource(p.binding);
                    var deleted=p.guard.deleteSource(lease,p.binding,r.tenant,limit);
                    if(deleted.stage()==Stage.VERIFYING)advance(r,control,Phase.FRESH_AUDIT);
                }else advance(r,control,Phase.FRESH_AUDIT);
            }
            case FRESH_AUDIT -> {
                if(r.discovery.audit()){
                    if(before.includeSource()){
                        p.guard.validateSource(p.binding);
                        if(!p.guard.verifySourceEmpty(lease,p.binding,r.tenant))throw incomplete();
                    }
                    var proof=p.journal.create(lease,"fresh-audit","",new byte[0],p.fingerprint);
                    if(!proof.done())p.journal.commit(lease,proof,"",new byte[0],true,p.fingerprint,0,0,0);
                    advance(r,control,Phase.VERIFY_IDENTITIES);
                }
            }
            case VERIFY_IDENTITIES -> {
                var verified=p.participants.step(lease,r.generation,r.identity,PurgeParticipant.Phase.VERIFY,1048576);
                requireParticipant(verified.checkpoint().state());
                if(verified.checkpoint().state()==PurgeParticipant.State.VERIFIED)advance(r,control,Phase.VERIFY_PARTICIPANTS);
            }
            case VERIFY_PARTICIPANTS -> {if(r.external(PurgeParticipant.Phase.VERIFY))advance(r,control,Phase.RECEIPT_GC);}
            case RECEIPT_GC -> {
                if(r.collectReceipt()){
                    var queues=p.journal.get(lease,"erase-queue");
                    p.journal.commit(lease,control,Phase.DISPOSE_INVENTORY.name(),control.payload(),false,
                            control.evidence(),0,queues==null?0:queues.removed(),0);
                }
            }
            case DISPOSE_INVENTORY -> {if(p.inventory.dispose(lease,limit)==0)advance(r,control,Phase.DISPOSE_JOURNAL);}
            case DISPOSE_JOURNAL -> {if(p.journal.dispose(lease,limit)==0)advance(r,control,Phase.CERTIFY);}
            case CERTIFY -> {
                // All temporary IDs/queue payloads are gone; only hashed scope/security/progress/audit remain.
                String proof=ProtectionKey.hash(p.fingerprint+":"+lease.operationId()+":"+r.generation+":"+before.revision()
                        +":"+before.redisRows()+":"+before.redisKeys()+":"+before.sourceRows());
                emit(p,lease,before,"PURGE_VERIFIED",null);
                if(before.includeSource()){
                    if(before.stage()!=Stage.COMPLETE)p.guard.save(lease,before,before.advance(Stage.COMPLETE,before.inventoryToken(),
                            before.cursor(),null,0,before.redisRows(),before.redisKeys(),before.sourceRows(),0,Instant.now()));
                    p.journal.commit(lease,control,Phase.COMPLETE.name(),control.payload(),true,proof,0,0,0);
                }else{
                    // No source DELETE/enqueue has run. Retained source/history forbids automatic reopening.
                    if(before.stage()!=Stage.REQUIRES_MIGRATION)p.guard.markRequiresMigration(lease);
                    p.journal.commit(lease,control,Phase.REDIS_ONLY_DONE.name(),control.payload(),true,proof,0,0,0);
                }
            }
            default -> throw invalid();
        }
    }

    private void validateRegistration(RedisTenantPurgePlan p,boolean includeSource){
        var binding=registry.find(p.entityName).orElseThrow(CacheDataProtection::incomplete);
        var metadata=binding.metadata();
        if(metadata.entityType()!=p.entityType()||!metadata.redisNamespace().equals(p.scope())
                ||!metadata.tableName().equals(p.binding.source().table())||!metadata.idColumn().equals(p.binding.source().idColumn())
                ||!Objects.equals(metadata.tenantFieldName(),p.binding.source().tenantColumn())
                ||!metadata.versionColumn().equals(p.versionColumn)||!metadata.indexedColumns().contains(metadata.tenantFieldName())
                ||metadata.encryptedColumns().containsKey(metadata.tenantFieldName()))throw incomplete();
        PurgeEntityScope.validate(metadata,registry.all(),p.manifest,includeSource);
        var expected=new HashSet<String>();expected.add(metadata.redisNamespace());int count=0;
        for(var projection:registry.projections(p.entityName)){
            if(++count>7)throw incomplete();
            expected.add(projectionNamespace(metadata,projection.projection()));
        }
        if(!expected.equals(p.namespaces))throw incomplete();
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    private static String projectionNamespace(com.reactor.cachedb.core.model.EntityMetadata metadata,
            com.reactor.cachedb.core.projection.EntityProjection projection){
        return new com.reactor.cachedb.core.projection.ProjectionEntityMetadata(metadata,projection).redisNamespace();
    }

    private static final class Run {
        final RedisTenantPurgePlan p;final Lease lease;final String tenant;final long generation;
        final TenantReadFence.Evidence evidence;final RedisTenantPurgeDiscovery discovery;
        final RedisIdentityPurgeParticipant identity;final RedisPurgeMaintenance maintenance;
        Run(RedisTenantPurgePlan p,Lease lease,String tenant,long generation,TenantReadFence.Evidence evidence){
            this.p=p;this.lease=lease;this.tenant=tenant;this.generation=generation;this.evidence=evidence;
            discovery=new RedisTenantPurgeDiscovery(p,lease,generation,tenant);
            maintenance=new RedisPurgeMaintenance(p.redis,p.keys,p.barrier);
            identity=new RedisIdentityPurgeParticipant(p.wire,p.redis,p.keys,p.barrier,p.binding.scope(),tenant,p.entityName,
                    p.binding.layoutToken(),p.namespaces,p.targets,new RedisIdentityPurgeParticipant.Inventory(){
                        public boolean sealed(Lease current){return current.equals(lease)&&p.inventory.discoveryComplete(lease);}
                        public List<PurgeWorkItem> page(Lease current,String after,int limit,int bytes){
                            if(!current.equals(lease))throw invalid();return p.inventory.page(lease,PurgeWorkItem.Kind.ID,after,limit,bytes);
                        }
                    },(request,layout)->{
                        closed(p,lease,generation,evidence);
                        var progress=p.guard.load(lease);
                        var fresh=p.journal.get(lease,"fresh-audit");
                        return request.lease().equals(lease)&&request.generation()==generation&&layout.equals(p.binding.layoutToken())
                                &&progress.closedGeneration()==generation&&p.inventory.discoveryComplete(lease)
                                &&(request.phase()!=PurgeParticipant.Phase.VERIFY||fresh!=null&&fresh.done()
                                    &&p.fingerprint.equals(fresh.evidence()));
                    },p.localL1);
        }
        boolean eraseQueue(){
            var checkpoint=p.journal.create(lease,"erase-queue","",new byte[0],p.fingerprint);
            if(acknowledge(checkpoint,true))return false;
            var work=page(PurgeWorkItem.Kind.ENTRY,checkpoint);
            if(work==null)return true;
            var decoded=PurgeQueueWorkCodec.decode(work.locator());
            var route=queue(decoded.route());route.fence().close(lease,generation);
            String request=request(work.token());
            long removed=route.eraser().erase(lease,generation,decoded.entry(),request);
            // durable offset+result BEFORE ACK; an unknown Redis outcome always retries the SAME work token.
            p.journal.commit(lease,checkpoint,work.token(),ascii(decoded.route()+":"+request),false,p.fingerprint,1,removed,0);
            return false;
        }
        boolean eraseCompaction(){
            var checkpoint=p.journal.create(lease,"erase-compaction","",new byte[0],p.fingerprint);
            if(acknowledge(checkpoint,false))return false;
            var work=page(PurgeWorkItem.Kind.ID,checkpoint);if(work==null)return true;
            String id=identity(work);var cleaner=new RedisCompactionPurgeCleanup(p.redis,p.keys,p.barrier,p.binding.scope());
            String request=request(work.token());
            long removed=cleaner.erase(lease,generation,tenant,id,request);
            p.journal.commit(lease,checkpoint,work.token(),ascii(request),false,p.fingerprint,1,removed,removed);
            return false;
        }
        private boolean acknowledge(Checkpoint checkpoint,boolean queue){
            byte[] bytes=checkpoint.payload();if(bytes.length==0)return false;
            String pending=new String(bytes,StandardCharsets.US_ASCII);
            if(queue){
                String[] parts=pending.split(":",-1);if(parts.length!=2)throw invalid();
                var route=queue(parts[0]);route.fence().close(lease,generation);
                route.eraser().acknowledgeResult(lease,generation,parts[1]);
            }else new RedisCompactionPurgeCleanup(p.redis,p.keys,p.barrier,p.binding.scope()).acknowledge(lease,generation,pending);
            p.journal.commit(lease,checkpoint,checkpoint.cursor(),new byte[0],false,p.fingerprint,0,0,0);
            return true;
        }
        boolean eraseResidual(){
            var checkpoint=p.journal.create(lease,"erase-residual","",new byte[0],p.fingerprint);
            if(checkpoint.payload().length>0){
                maintenance.acknowledgeResidual(lease,generation,new String(checkpoint.payload(),StandardCharsets.US_ASCII));
                p.journal.commit(lease,checkpoint,checkpoint.cursor(),new byte[0],false,p.fingerprint,0,0,0);return false;
            }
            var work=page(PurgeWorkItem.Kind.KEY,checkpoint);if(work==null)return true;
            String key=decode(work.locator()),request=request(work.token());
            long removed=maintenance.residual(lease,generation,tenant,key,p.targets,request);
            p.journal.commit(lease,checkpoint,work.token(),ascii(request),false,p.fingerprint,1,removed,removed);return false;
        }
        boolean external(PurgeParticipant.Phase phase){
            var declared=p.manifest.requireAll();
            String id="participants-"+phase.name().toLowerCase(Locale.ROOT);
            var checkpoint=p.journal.create(lease,id,"0",new byte[0],p.fingerprint);
            int offset=Integer.parseInt(checkpoint.cursor());if(offset==declared.size())return true;
            if(offset<0||offset>declared.size())throw invalid();
            var result=p.participants.step(lease,generation,declared.get(offset),phase,1048576).checkpoint();
            requireParticipant(result.state());
            if(result.state()==PurgeParticipant.State.VERIFIED)
                p.journal.commit(lease,checkpoint,Integer.toString(offset+1),new byte[0],false,p.fingerprint,0,0,0);
            return false;
        }
        boolean collectReceipt(){
            var checkpoint=p.journal.create(lease,"receipt-gc","",new byte[0],p.fingerprint);
            var work=page(PurgeWorkItem.Kind.ID,checkpoint);if(work==null)return true;
            maintenance.collectReceipt(lease,generation,identity.receiptKey(lease,work.token()));
            p.journal.commit(lease,checkpoint,work.token(),new byte[0],false,p.fingerprint,1,0,0);return false;
        }
        private String request(String work){return ProtectionKey.hash(lease.operationId()+":"+work);}
        private PurgeWorkItem page(PurgeWorkItem.Kind kind,Checkpoint checkpoint){
            var work=p.inventory.page(lease,kind,checkpoint.cursor().isEmpty()?null:checkpoint.cursor(),1,1048576);
            return work.isEmpty()?null:work.getFirst();
        }
        private String identity(PurgeWorkItem work){
            String id=decode(work.locator());if(!lease.key().objectToken(id).equals(work.token()))throw invalid();return id;
        }
        private RedisTenantPurgePlan.QueueRoute queue(String token){
            ProtectionKey.token(token);return p.queues.stream().filter(q->q.token().equals(token)).findFirst().orElseThrow(CacheDataProtection::incomplete);
        }
    }

    private static void closed(RedisTenantPurgePlan p,Lease lease,long generation,TenantReadFence.Evidence evidence){
        p.guard.renew(lease);p.barrier.assertClosed(lease,generation);
        p.readFence.requireClosed(lease,generation,p.fingerprint,evidence);
    }
    private static void advance(Run r,Checkpoint before,Phase next){
        r.p.journal.commit(r.lease,before,next.name(),before.payload(),false,before.evidence(),0,0,0);
    }
    private static Result result(RedisTenantPurgePlan p,Lease lease,Checkpoint control){
        var receipt=p.guard.load(lease).receipt(lease.key());
        Status status=switch(Phase.valueOf(control.cursor())){
            case COMPLETE -> receipt.stage()==Stage.COMPLETE?Status.COMPLETE:Status.INCOMPLETE;
            case REDIS_ONLY_DONE -> Status.MIGRATION_REQUIRED;
            case INCOMPLETE -> Status.INCOMPLETE;
            default -> Status.RUNNING;
        };
        var queues=p.journal.get(lease,"erase-queue");
        long queueEntries=Math.max(control.removed(),queues==null?0:queues.removed());
        return new Result(status,receipt,queueEntries,control.done()?control.evidence():null,
                status==Status.RUNNING?new Continuation(p.entityType(),lease):null);
    }
    private static Result incomplete(RedisTenantPurgePlan p,Lease lease,Checkpoint control){
        p.guard.markRequiresMigration(lease);
        var after=p.journal.commit(lease,control,Phase.INCOMPLETE.name(),control.payload(),true,p.fingerprint,0,0,0);
        emit(p,lease,p.guard.load(lease),"PURGE_INCOMPLETE",ProtectionException.Code.INCOMPLETE_INVENTORY);
        return result(p,lease,after);
    }
    private static void emit(RedisTenantPurgePlan p,Lease lease,Progress progress,String event,ProtectionException.Code failure){
        try{p.audit.onEvent(new CachePurgeAuditListener.Event(lease.operationId(),lease.key(),progress.revision(),event,
                progress.operatorToken(),Instant.now(),failure,progress.redisRows(),progress.redisKeys(),progress.sourceRows()));}
        catch(RuntimeException rejected){throw new ProtectionException(ProtectionException.Code.AUDIT_FAILED);}
    }
    private static void requireParticipant(PurgeParticipant.State state){if(state==PurgeParticipant.State.INCOMPLETE)throw incomplete();}
    private static byte[] ascii(String value){return value.getBytes(StandardCharsets.US_ASCII);}
    private static String decode(byte[] value){
        try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(value)).toString();}
        catch(CharacterCodingException invalid){throw invalid();}
    }
    private static ProtectionException incomplete(){return new ProtectionException(ProtectionException.Code.INCOMPLETE_INVENTORY);}
    private static ProtectionException invalid(){return new ProtectionException(ProtectionException.Code.INVALID_REQUEST);}
}
