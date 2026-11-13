package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.cache.*;
import com.reactor.cachedb.core.codec.Utf8Length;
import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.InitialTenantBootstrap.*;
import com.reactor.cachedb.core.protection.InitialTenantBootstrap.Page;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.*;
import java.util.*;
import static com.reactor.cachedb.core.protection.ProtectionException.Code.*;

/** Actual-manager CLOSED maintenance. No enqueue, SQL write, owner-only claim or business version allocation. */
public final class RedisInitialSourceHydrator<T,ID> {
    private final RedisClientOperations redis;
    private final RedisKeyStrategy keys;
    private final EntityMetadata<T,ID> metadata;
    private final EntityCodec<T> codec;
    private final CachePolicy policy;
    private final RedisQueryIndexManager<T,ID> indexes;
    private final RedisPageCacheManager<T,ID> pages;
    private final RedisNearCacheCoordinator.Store local;
    private final RedisTenantProducer producer;
    private final RedisTenantRuntimeControl control;
    private final InitialSourceHotWindow<T,ID> window;
    private final PurgeParticipant participant;
    public record Verified(long now,long earliestExpiry,int rows,long payloadBytes) {}

    RedisInitialSourceHydrator(RedisClientOperations redis,RedisKeyStrategy keys,EntityMetadata<T,ID> metadata,
            EntityCodec<T> codec,CachePolicy policy,RedisQueryIndexManager<T,ID> indexes,
            RedisPageCacheManager<T,ID> pages,RedisNearCacheCoordinator.Store local,
            RedisTenantProducer producer,RedisTenantRuntimeControl control,InitialSourceHotWindow<T,ID> window) {
        this.redis=redis;this.keys=keys;this.metadata=metadata;this.codec=codec;this.policy=policy;this.indexes=indexes;
        this.pages=pages;this.local=local;this.producer=producer;this.control=control;this.window=window;
        if(!producer.belongsToRuntime(redis,keys) || !producer.runtimeId().equals(producer.readFence().runtimeId())
                || !control.rosterKey().equals(RedisTenantRuntimeControl.rosterKey(keys,metadata.redisNamespace())))fail(UNWIRED_PROTOCOL);
        participant=createParticipant();
    }
    public RedisPurgeTarget target(){return pages.purgeTarget(List.of());}
    public InitialSourceHotWindow<T,ID> window(){return window;}
    public UUID runtimeId(){return producer.runtimeId();}
    public String runId(){
        return new RedisTenantVersionAllocator(redis,keys,producer.barrier(),metadata.redisNamespace())
                .runId(ProtectionKey.of(metadata.redisNamespace(),"initial-layout"));
    }
    public void hydrate(Session session,Page<T,ID> page){execute(session,page,false);}
    public Verified verify(Session session,Page<T,ID> page){return execute(session,page,true);}

    public String seal(Session session,WindowProof summary,int pageCount,long bytes,long expiry,String proof) {
        requireSession(session);ProtectionKey.token(proof);
        Object result=control.initialClosed(session.lease(),session.request().generation().value(),STAGE+"""
            local stage=KEYS[1];local r,b,p=ledger(stage)
            if redis.call('HGET',stage,'i:operation')~=ARGV[1] or redis.call('HGET',stage,'i:plan')~=ARGV[2]
              or redis.call('HGET',stage,'i:manifest')~=ARGV[3] or r~=tonumber(ARGV[5])
              or b~=tonumber(ARGV[6]) or p~=tonumber(ARGV[7]) then error('CDB_INITIAL_INCOMPLETE') end
            local time=redis.call('TIME');local now=tonumber(time[1])*1000+math.floor(tonumber(time[2])/1000)
            if tonumber(ARGV[8])~=0 and tonumber(ARGV[8])<=now then error('CDB_INITIAL_EXPIRED') end
            redis.call('HSET',stage,'i:verified',ARGV[9],'i:deadline',ARGV[8],'i:window',ARGV[4]);return 1
            """,List.of(producer.barrier().generationKey(session.lease().key())),
                List.of(session.request().operationId().toString(),session.planToken(),session.manifestToken(),summary.digest(),
                        Long.toString(summary.rows()),Long.toString(bytes),Integer.toString(pageCount),Long.toString(expiry),proof));
        if(number(result)!=1)fail(INCOMPLETE_INVENTORY);return proof;
    }
    public void installRange(Session session,TenantVersionRange range) {
        requireSession(session);new RedisTenantVersionAllocator(redis,keys,producer.barrier(),metadata.redisNamespace())
                .installClosed(range,session.lease(),control);
    }
    public void activate(Session session,Activation activation) {
        requireSession(session);requireActivation(session,activation);
        Object result=control.initialClosed(session.lease(),session.request().generation().value(),STAGE+"""
            local stage=KEYS[1];local r,b,p=ledger(stage)
            if redis.call('HGET',stage,'i:operation')~=ARGV[1] or redis.call('HGET',stage,'i:plan')~=ARGV[2]
              or redis.call('HGET',stage,'i:manifest')~=ARGV[3] or redis.call('HGET',stage,'i:verified')~=ARGV[5]
              or redis.call('HGET',stage,'i:window')~=ARGV[6] or r~=tonumber(ARGV[7]) then error('CDB_INITIAL_INCOMPLETE') end
            local time=redis.call('TIME');local now=tonumber(time[1])*1000+math.floor(tonumber(time[2])/1000)
            local deadline=tonumber(redis.call('HGET',stage,'i:deadline'))
            if not deadline or deadline~=0 and deadline<=now then error('CDB_INITIAL_EXPIRED') end
            """+RANGE_CHECK+"""
            if not redis.acl_check_cmd('HSET',stage,'state','OPEN') then error('CDB_INITIAL_INCOMPLETE') end
            local fields={}
            for _,field in ipairs(redis.call('HKEYS',stage)) do if string.sub(field,1,4)=='i:p:' then fields[#fields+1]=field end end
            if #fields>0 and not redis.acl_check_cmd('HDEL',stage,unpack(fields)) then error('CDB_INITIAL_INCOMPLETE') end
            redis.call('HSET',stage,'state','OPEN','i:active',ARGV[4],'i:active_range',ARGV[16],'i:pages','0')
            if #fields>0 then redis.call('HDEL',stage,unpack(fields)) end
            return 1
            """,rangeKeys(session),activationArguments(session,activation));
        if(number(result)!=1)fail(RECOVERY_REQUIRED);
    }
    public RuntimeState observe(Session session,Activation activation) {
        return observe(session,activation,false);
    }
    /** Fences delayed older close commands; does not alter the committed activation or version grant. */
    public RuntimeState observeForFinalization(Session session,Activation activation) {
        return observe(session,activation,true);
    }
    private RuntimeState observe(Session session,Activation activation,boolean finalizing) {
        requireSession(session);requireActivation(session,activation);
        var args=new ArrayList<>(activationArguments(session,activation));
        args.add(finalizing?"1":"0");args.add(Long.toString(session.lease().epoch()));
        Object result=control.initialObserve(RedisInitialGenerationSchema.LUA+"""
            local stage=KEYS[1]
            if redis.call('HGET',stage,'generation')~=ARGV[8]
              or redis.call('HGET',stage,'operation')~=ARGV[1]
              or redis.call('HGET',stage,'run_id')~=cachedb_version_run_id() then error('CDB_INITIAL_INCOMPLETE') end
            if redis.call('HGET',stage,'state')=='CLOSED' then return 0 end
            if redis.call('HGET',stage,'state')~='OPEN' or redis.call('HGET',stage,'i:active')~=ARGV[4]
              or redis.call('HGET',stage,'i:active_range')~=ARGV[16]
              or redis.call('HGET',stage,'i:plan')~=ARGV[2] or redis.call('HGET',stage,'i:manifest')~=ARGV[3]
              then error('CDB_INITIAL_INCOMPLETE') end
            """+RANGE_CHECK+"""
            if ARGV[17]=='1' then
              local function less(a,b) if #a~=#b then return #a<#b end return a<b end
              if not cachedb_initial_decimal(ARGV[18],'9007199254740991',false)
                or redis.call('HSTRLEN',stage,'i:finalize_epoch')>16 then error('CDB_INITIAL_INCOMPLETE') end
              local prior=redis.call('HGET',stage,'i:finalize_epoch')
              if prior and (not cachedb_initial_decimal(prior,'9007199254740991',false) or less(ARGV[18],prior))
                then error('CDB_INITIAL_INCOMPLETE') end
              local nativeEpoch=redis.call('HGET',stage,'epoch')
              if not nativeEpoch or less(ARGV[18],nativeEpoch) then error('CDB_INITIAL_INCOMPLETE') end
              redis.call('HSET',stage,'i:finalize_epoch',ARGV[18])
            end
            return 1
            """,rangeKeys(session),args);
        return number(result)==1?RuntimeState.OPEN:RuntimeState.CLOSED;
    }
    private List<String> rangeKeys(Session session) {
        var allocator=new RedisTenantVersionAllocator(redis,keys,producer.barrier(),metadata.redisNamespace());
        return List.of(producer.barrier().generationKey(session.lease().key()),allocator.allocatorKey(session.lease().key()),
                allocator.counterKey(session.lease().key()));
    }
    private List<String> activationArguments(Session session,Activation activation) {
        var range=activation.range();
        return List.of(session.request().operationId().toString(),session.planToken(),session.manifestToken(),activation.token(),
                activation.runtimeProof(),activation.window().digest(),Long.toString(activation.window().rows()),
                Long.toString(range.generation()),Long.toString(range.controlEpoch()),Long.toString(range.reservationEpoch()),
                range.request().toString(),Long.toString(range.first()-1),Long.toString(range.last()),range.runId(),range.inventory(),
                RedisTenantRuntimeControl.digest(List.of(range.request().toString(),Long.toString(range.reservationEpoch()),
                        Long.toString(range.first()),Long.toString(range.last()),range.inventory(),range.runId())));
    }
    private void requireActivation(Session session,Activation activation) {
        if(!activation.planToken().equals(session.planToken()) || !activation.manifestToken().equals(session.manifestToken())
                || !activation.range().key().equals(session.lease().key())
                || activation.range().generation()!=session.request().generation().value())fail(UNWIRED_PROTOCOL);
    }
    public PurgeParticipant participant(){return participant;}
    private PurgeParticipant createParticipant() {
        var declaration=new PurgeParticipant.Declaration("initial-native-window",
                ProtectionKey.hash("initial-native-window-v1:"+window.fingerprint()+":"+target().fingerprint()),
                Set.of(metadata.entityName()),false);
        return new PurgeParticipant() {
            public Declaration declaration(){return declaration;}
            public Result step(Request request) {
                Object value=control.initialClosed(request.lease(),request.generation(),STAGE+"""
                    local r,b,p=ledger(KEYS[1]);local fields={}
                    for _,field in ipairs(redis.call('HKEYS',KEYS[1])) do
                      if string.sub(field,1,2)=='i:' then fields[#fields+1]=field end
                    end
                    if #fields>0 and redis.call('HGET',KEYS[1],'i:plan')~=ARGV[1] then error('CDB_INITIAL_INCOMPLETE') end
                    if ARGV[2]=='VERIFY' and #fields>0 then return 0 end
                    if ARGV[2]=='ERASE' and #fields>0 then redis.call('HDEL',KEYS[1],unpack(fields)) end
                    return 1
                    """,List.of(producer.barrier().generationKey(request.lease().key())),
                        List.of(window.fingerprint(),request.phase().name()));
                if(number(value)!=1)return new Result(State.INCOMPLETE,request.checkpoint(),0,0,null);
                return new Result(State.VERIFIED,request.checkpoint(),1,0,ProtectionKey.hash(declaration.ownershipFingerprint()
                        +":"+request.lease().operationId()+":"+request.generation()+":"+request.phase()));
            }
        };
    }

    private Verified execute(Session session,Page<T,ID> page,boolean verify) {
        requireSession(session);Objects.requireNonNull(page);
        var nativeKeys=new ArrayList<String>();nativeKeys.add(producer.barrier().generationKey(session.lease().key()));
        nativeKeys.add(keys.hotSetKey(metadata.redisNamespace()));
        var body=new ArrayList<String>();var proofs=new ArrayList<String>();var ids=new HashSet<String>();long bytes=0;
        for(var row:page.rows()) {
            String id=window.canonical(row.id());
            if(!ids.add(id) || !id.equals(row.proof().canonicalId()) || !id.equals(window.canonical(metadata.idAccessor().apply(row.entity())))
                    || !session.request().tenant().equals(metadata.extractTenantId(row.entity()))
                    || row.payloadBytes()>window.rowBytes()
                    || !row.proof().digest().equals(InitialBootstrapProof.row(window,session,id,row.proof().sourceVersion(),row.redisPayload())))fail(MIGRATION_REQUIRED);
            InitialBootstrapProof.sameLogicalSource(window,row.entity(),codec.fromRedisValue(row.redisPayload()));
            if(!policy.shouldAdmit(codec.toColumns(row.entity()),CacheAdmissionSource.WARM))fail(INCOMPLETE_INVENTORY);
            bytes+=row.payloadBytes();if(bytes>window.pageBytes())fail(INVALID_REQUEST);
            proofs.add(row.proof().digest());
            nativeKeys.addAll(List.of(keys.entityKey(metadata.redisNamespace(),id),keys.versionKey(metadata.redisNamespace(),id),
                    keys.versionKey(metadata.redisNamespace(),id)+":tenant",keys.tombstoneKey(metadata.redisNamespace(),id),
                    keys.indexMetaKey(metadata.redisNamespace(),id),keys.tenantHotOwnerKey(metadata.redisNamespace(),id)));
        }
        var positions=new HashMap<String,Integer>();for(int i=0;i<nativeKeys.size();i++)
            if(positions.put(nativeKeys.get(i),i+1)!=null)fail(UNWIRED_PROTOCOL);
        for(var row:page.rows()) {
            var commands=indexes.initialUpsertCommands(row.entity());
            body.add(window.canonical(row.id()));body.add(Long.toString(row.proof().sourceVersion()));body.add(row.redisPayload());
            body.add(Integer.toString(commands.size()));
            for(var command:commands) {
                int position=positions.computeIfAbsent(command.get(1),key -> {nativeKeys.add(key);return nativeKeys.size();});
                body.add(command.getFirst());body.add(Integer.toString(position));body.add(Integer.toString(command.size()-2));
                body.addAll(command.subList(2,command.size()));
            }
        }
        String digest=InitialBootstrapProof.bytes(String.join("",proofs).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        var args=new ArrayList<>(List.of(session.request().operationId().toString(),session.planToken(),session.manifestToken(),
                "i:p:"+page.id(),digest,Integer.toString(page.rows().size()),Long.toString(bytes),Integer.toString(window.maxRows()),
                Long.toString(window.residentBytes()),Long.toString(ttlMillis()),Integer.toString(policy.hotEntityLimit()),
                session.request().tenant(),verify?"V":"H"));args.addAll(body);
        Object value=control.initialClosed(session.lease(),session.request().generation().value(),PAGE,nativeKeys,args);
        if(!(value instanceof List<?> values) || values.size()!=4)throw new ProtectionException(INCOMPLETE_INVENTORY);
        var verified=new Verified(number(values.get(0)),number(values.get(1)),Math.toIntExact(number(values.get(2))),number(values.get(3)));
        if(verified.rows()!=page.rows().size() || verified.payloadBytes()!=bytes)fail(INCOMPLETE_INVENTORY);
        if(!verify) {
            if(local!=null)page.rows().forEach(row -> local.invalidate(window.canonical(row.id())));
            indexes.purgeInvalidateLocalStatistics();
        }
        return verified;
    }
    private long ttlMillis(){
        long seconds=policy.entityTtlSeconds();if(seconds<0 || seconds>Integer.MAX_VALUE)fail(INVALID_REQUEST);
        return Math.multiplyExact(seconds,1000L);
    }
    private void requireSession(Session session){
        RedisTenantRuntimeControl.admin();
        if(!session.planToken().equals(window.fingerprint()) || !window.binding().key(session.request().tenant()).equals(session.lease().key())
                || window.metadata()!=metadata || window.codec()!=codec || !control.approval().manifestToken().equals(session.manifestToken())
                || com.reactor.cachedb.core.route.RouteCacheContext.currentContract()!=null)fail(UNWIRED_PROTOCOL);
    }
    private static long number(Object value){if(value instanceof Number n)return n.longValue();throw new ProtectionException(INCOMPLETE_INVENTORY);}
    private static void fail(ProtectionException.Code code){throw new ProtectionException(code);}

    private static final String RANGE_CHECK="""
        if redis.call('HGET',KEYS[2],'generation')~=ARGV[8] or redis.call('HGET',KEYS[2],'control_epoch')~=ARGV[9]
          or redis.call('HGET',KEYS[2],'reservation_epoch')~=ARGV[10] or redis.call('HGET',KEYS[2],'request')~=ARGV[11]
          or redis.call('HGET',KEYS[2],'floor')~=ARGV[12] or redis.call('HGET',KEYS[2],'ceiling')~=ARGV[13]
          or redis.call('HGET',KEYS[2],'run_id')~=ARGV[14] or redis.call('HGET',KEYS[2],'inventory')~=ARGV[15]
          then error('CDB_INITIAL_RANGE_CHANGED') end
        cachedb_version_assert(KEYS[1],KEYS[2],KEYS[3],ARGV[8],ARGV[14])
        """;
    static final String STAGE=RedisInitialGenerationSchema.LUA+"""
        local function ledger(stage)
          local rows,bytes,pages=cachedb_initial_ledger(stage)
          if rows==nil then error('CDB_INITIAL_INCOMPLETE') end
          return rows,bytes,pages
        end
        """;
    static final String PAGE=STAGE+"""
        local function reject() error('CDB_INITIAL_INCOMPLETE') end
        local function kind(key,expected)
          local t=redis.call('TYPE',key).ok
          if t~='none' and t~=expected then reject() end
          return t
        end
        local function allowed(name,key,values)
          if not redis.acl_check_cmd(name,key,unpack(values)) then reject() end
        end
        local stage,hot=KEYS[1],KEYS[2]
        kind(stage,'hash');kind(hot,'zset');ledger(stage)
        if redis.call('HEXISTS',stage,'i:active')~=0 then reject() end
        local count=tonumber(ARGV[6]);if not count or count<0 or count>128 then reject() end
        local oldPlan=redis.call('HGET',stage,'i:plan')
        if oldPlan and (oldPlan~=ARGV[2] or redis.call('HGET',stage,'i:manifest')~=ARGV[3]
          or redis.call('HGET',stage,'i:operation')~=ARGV[1]) then reject() end
        local receipt=ARGV[5]..':'..ARGV[6]..':'..ARGV[7]
        if redis.call('HSTRLEN',stage,ARGV[4])>#receipt then reject() end
        local oldPage=redis.call('HGET',stage,ARGV[4])
        if oldPage and oldPage~=receipt then reject() end
        if ARGV[13]=='V' and (not oldPlan or oldPage~=receipt) then reject() end
        local rows,at,newRows={},14,0
        local time=redis.call('TIME');local now=tonumber(time[1])*1000+math.floor(tonumber(time[2])/1000)
        local expiry=0
        for i=1,count do
          local first=3+(i-1)*6
          local payload,version,owner,tomb,meta,quota=KEYS[first],KEYS[first+1],KEYS[first+2],KEYS[first+3],KEYS[first+4],KEYS[first+5]
          kind(payload,'string');kind(version,'string');kind(owner,'string');kind(tomb,'string');kind(meta,'hash')
          if redis.call('EXISTS',quota)~=0 or redis.call('EXISTS',tomb)~=0 then reject() end
          local id,v,body,n=ARGV[at],ARGV[at+1],ARGV[at+2],tonumber(ARGV[at+3]);at=at+4
          if not n or n<1 or n>8192 then reject() end
          -- Check ALL three lengths before any GET; a conflicting resident value stays unread.
          if redis.call('STRLEN',owner)>#ARGV[12] or redis.call('STRLEN',version)>#v
            or redis.call('STRLEN',payload)>#body then reject() end
          local oldOwner,oldVersion,oldPayload=redis.call('GET',owner),redis.call('GET',version),redis.call('GET',payload)
          local fresh=not oldOwner and not oldVersion and not oldPayload
          if not fresh and (oldOwner~=ARGV[12] or oldVersion~=v or oldPayload~=body) then reject() end
          if ARGV[13]=='V' and fresh then reject() end
          if fresh then newRows=newRows+1;if redis.call('ZSCORE',hot,id) then reject() end
          elseif not redis.call('ZSCORE',hot,id) then reject() end
          local commands,expected={},{}
          for c=1,n do
            local name,key,nargs=ARGV[at],KEYS[tonumber(ARGV[at+1])],tonumber(ARGV[at+2]);at=at+3
            if not key or not nargs or nargs<0 or nargs>2048 then reject() end
            local values={};for j=1,nargs do if not ARGV[at] then reject() end;values[j]=ARGV[at];at=at+1 end
            if name=='SADD' and nargs==1 and values[1]==id then
              kind(key,'set');if redis.call('SISMEMBER',key,id)~=(fresh and 0 or 1) then reject() end
            elseif name=='ZADD' and nargs==2 and values[2]==id then
              kind(key,'zset');local score=redis.call('ZSCORE',key,id)
              if fresh and score or not fresh and (not score or tonumber(score)~=tonumber(values[1])) then reject() end
            elseif name=='DEL' and key==meta and nargs==0 then
            elseif name=='HSET' and key==meta and nargs>0 and nargs%2==0 then
              for j=1,nargs,2 do if expected[values[j]] then reject() end;expected[values[j]]=values[j+1] end
            else reject() end
            allowed(name,key,values);commands[#commands+1]={name=name,key=key,values=values}
          end
          local fields=0;for field,value in pairs(expected) do
            fields=fields+1
            if not fresh and (redis.call('HSTRLEN',meta,field)~=#value
              or redis.call('HGET',meta,field)~=value) then reject() end
          end
          if redis.call('HLEN',meta)~=(fresh and 0 or fields) then reject() end
          allowed('SET',version,{v});allowed('SET',owner,{ARGV[12]});allowed('ZADD',hot,{tostring(now),id})
          if tonumber(ARGV[10])>0 then allowed('SET',payload,{body,'PX',ARGV[10]}) else allowed('SET',payload,{body}) end
          if not fresh then
            local ttl=redis.call('PTTL',payload)
            if ttl~=-1 and ttl<=0 then reject() end
            if ttl>0 and (expiry==0 or now+ttl<expiry) then expiry=now+ttl end
          end
          rows[#rows+1]={id=id,v=v,body=body,payload=payload,version=version,owner=owner,commands=commands}
        end
        if at~=#ARGV+1 then reject() end
        local totalRows=tonumber(redis.call('HGET',stage,'i:rows') or '0')
        local totalBytes=tonumber(redis.call('HGET',stage,'i:bytes') or '0')
        local pageCount=tonumber(redis.call('HGET',stage,'i:pages') or '0')
        if not totalRows or not totalBytes or not pageCount or totalRows<0 or totalBytes<0 or pageCount<0 or pageCount>128 then reject() end
        if ARGV[13]=='V' then return {now,expiry,count,tonumber(ARGV[7])} end
        if not oldPage then
          totalRows=totalRows+count;totalBytes=totalBytes+tonumber(ARGV[7]);pageCount=pageCount+1
        end
        if totalRows>tonumber(ARGV[8]) or totalBytes>tonumber(ARGV[9]) or pageCount>128
          or tonumber(ARGV[11])>0 and redis.call('ZCARD',hot)+newRows>tonumber(ARGV[11]) then reject() end
        allowed('HSET',stage,{'i:operation',ARGV[1]});allowed('HDEL',stage,{'i:verified'})
        for _,row in ipairs(rows) do
          for _,command in ipairs(row.commands) do redis.call(command.name,command.key,unpack(command.values)) end
          if tonumber(ARGV[10])>0 then redis.call('SET',row.payload,row.body,'PX',ARGV[10])
          else redis.call('SET',row.payload,row.body) end
          redis.call('SET',row.version,row.v);redis.call('SET',row.owner,ARGV[12]);redis.call('ZADD',hot,now,row.id)
        end
        redis.call('HSET',stage,'i:operation',ARGV[1],'i:plan',ARGV[2],'i:manifest',ARGV[3],
          'i:rows',tostring(totalRows),'i:bytes',tostring(totalBytes),'i:pages',tostring(pageCount),ARGV[4],receipt)
        redis.call('HDEL',stage,'i:verified','i:deadline','i:window')
        return {now,expiry,count,tonumber(ARGV[7])}
        """;
}
