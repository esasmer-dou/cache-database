package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.SourceOwnerOnboarding.*;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded administrative native primitive. The actual-root coordinator supplies CLOSED SQL ownership proofs. */
public final class RedisSourceOwnerInstaller<I> {
    private final RedisTenantRuntimeControl control;
    private final RedisNativeTenantReadFence fence;
    private final RedisGenerationBarrier barrier;
    private final RedisKeyStrategy keys;
    private final String namespace, manifest;
    private final List<String> queues;
    private final IdCodec<I> ids;
    private final List<String> fields;
    private final List<Page> pages;
    private final String worklist;
    private final int maxBytes;

    public RedisSourceOwnerInstaller(RedisTenantRuntimeControl control, RedisNativeTenantReadFence fence,
            RedisGenerationBarrier barrier, RedisKeyStrategy keys, String namespace, String manifest,
            List<String> queues, IdCodec<I> ids, List<String> fields,
            List<Page> pages, String worklist, int maxBytes) {
        this.control=Objects.requireNonNull(control); this.fence=Objects.requireNonNull(fence);
        this.barrier=Objects.requireNonNull(barrier); this.keys=Objects.requireNonNull(keys);
        this.namespace=Objects.requireNonNull(namespace); this.manifest=ProtectionKey.token(manifest);
        this.ids=Objects.requireNonNull(ids);
        this.worklist=ProtectionKey.token(worklist);
        if (queues==null || queues.isEmpty() || queues.size()>128 || new HashSet<>(queues).size()!=queues.size())
            throw invalid();
        this.queues=List.copyOf(queues);
        if (fields==null || fields.size()>128 || new HashSet<>(fields).size()!=fields.size()
                || pages==null || pages.isEmpty() || pages.size()>128 || maxBytes<1024 || maxBytes>1_048_576)
            throw invalid();
        for (String field:fields) if (field==null || !field.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")) throw invalid();
        this.fields=fields.stream().sorted().toList(); this.pages=List.copyOf(pages); this.maxBytes=maxBytes;
        var identities=new HashSet<String>(); var pageIds=new HashSet<UUID>(); int bytes=0;
        for (int i=0;i<pages.size();i++) {
            Page page=pages.get(i);
            if (page.ids().isEmpty() || page.last()!=(i==pages.size()-1) || !pageIds.add(page.id())) throw invalid();
            for (String id:page.ids()) {
                if (!identities.add(id) || identities.size()>4096) throw invalid();
                bytes=Math.addExact(bytes,id.getBytes(StandardCharsets.UTF_8).length);
                if (bytes>1_048_576) throw invalid();
            }
        }
    }

    /** FENCING only: seal actual bounded artifact states, never just the supplied candidate IDs. */
    public String seal(Session session) {
        for (Page page:pages) {
            Object result=execute(session,page,"-",List.of(),"S");
            if (!(result instanceof Number number) || number.longValue()!=1) throw incomplete();
        }
        return inventoryToken(session.request());
    }

    /** Historical worklist binding only. This read is NOT a fresh closure/activation certificate. */
    public String inventoryToken(Request request) {
        admin();
        var args=List.of(configuration(request),Integer.toString(pages.size()),rootField(request));
        String script="""
            if redis.call('TYPE',KEYS[1]).ok~='hash' or redis.call('PTTL',KEYS[1])~=-1
              or redis.call('HSTRLEN',KEYS[1],ARGV[3])>256 then return redis.error_reply('OWNER_SEAL_UNKNOWN') end
            local value=redis.call('HGET',KEYS[1],ARGV[3])
            if not value then return redis.error_reply('OWNER_SEAL_MISSING') end
            local config,total,done,chain,run=string.match(value,'^([0-9a-f]+):(%d+):(%d+):([0-9a-f]+):([0-9a-f]+)$')
            local count=tonumber(total)
            if not config or #config~=64 or not count or count<1 or count>128 or tostring(count)~=total
              or done~=total or not chain or #chain~=40 or not run or #run~=40
              or run~=redis.call('HGET',KEYS[1],'run_id') then return redis.error_reply('OWNER_SEAL_CHANGED') end
            return value
            """;
        var nativeKeys=List.of(barrier.generationKey(request.generation().key()));
        Object value;
        try { value=control.initialObserve(script,nativeKeys,args); }
        catch (RuntimeException failure) { throw incomplete(); }
        if (!(value instanceof String seal) || seal.length()>256) throw incomplete();
        // A valid retained seal for another worklist is a checkpoint conflict, not missing evidence.
        if (!seal.startsWith(args.get(0)+":"+args.get(1)+":"))
            throw new ProtectionException(ProtectionException.Code.INVALID_PROGRESS);
        return ProtectionKey.hash("source-owner-seal-v2:"+seal);
    }

    private String configuration(Request request) {
        if (!request.plan().binding().scope().equals(namespace)) throw invalid();
        String digest=ProtectionKey.hash(worklist+":"+request.plan().fingerprint()+":"+request.operationId()+":"
                +request.generation().key().tenantToken()+":"+request.generation().value()+":"+maxBytes);
        for (String field:fields) digest=ProtectionKey.hash(digest+":"+field);
        for (Page page:pages) digest=ProtectionKey.hash(digest+":"+pageToken(page));
        return digest;
    }
    private static String rootField(Request request) { return "onboard:"+request.operationId()+":seal"; }

    public Installed install(Session session, Page page, List<OwnerProof<I>> proofs) {
        if (proofs==null || proofs.size()!=page.ids().size()) throw invalid();
        String digest=ProtectionKey.hash("source-owner-proof-v1:"+session.request().plan().fingerprint());
        var values=new ArrayList<String>();
        for (int i=0;i<proofs.size();i++) {
            var proof=proofs.get(i);
            if (!proof.canonicalId().equals(page.ids().get(i)) || !ids.encode(proof.id()).equals(proof.canonicalId()))
                throw invalid();
            String owned=proof.ownership()==Ownership.SOURCE_OWNED?"1":"0";
            values.add(owned); values.add(Long.toString(proof.sourceVersion()));
            digest=ProtectionKey.hash(digest+":"+ProtectionKey.hash(proof.canonicalId())+":"+owned+":"+proof.sourceVersion());
        }
        return counts(execute(session,page,digest,values,"I"),page);
    }

    /** Does not recreate a missing receipt/owner or trust a previously cached verification result. */
    public boolean verify(Session session, Page page) {
        return counts(execute(session,page,"-",List.of(),"V"),page).unknown()==0;
    }

    private Object execute(Session session, Page page, String digest, List<String> proofs, String mode) {
        admin();
        if (!session.request().plan().binding().scope().equals(namespace)) throw invalid();
        int index=pages.indexOf(page); if (index<0) throw invalid();
        var nativeKeys=new ArrayList<String>();
        nativeKeys.add(barrier.generationKey(session.lease().key())); nativeKeys.addAll(queues);
        for (String canonical:page.ids()) {
            I id=ids.decode(canonical);
            if (!ids.encode(id).equals(canonical)) throw invalid();
            nativeKeys.add(keys.entityKey(namespace,id)); nativeKeys.add(keys.tombstoneKey(namespace,id));
            nativeKeys.add(keys.indexMetaKey(namespace,id)); nativeKeys.add(keys.versionKey(namespace,id));
            nativeKeys.add(keys.durabilityKey(namespace,id)); nativeKeys.add(keys.tenantHotOwnerKey(namespace,id));
            nativeKeys.add(keys.versionKey(namespace,id)+":tenant");
            nativeKeys.add(keys.compactionPayloadKey(namespace,id)); nativeKeys.add(keys.compactionPendingKey(namespace,id));
        }
        if (new HashSet<>(nativeKeys).size()!=nativeKeys.size()) throw incomplete();
        var args=new ArrayList<>(List.of(session.request().tenant(),
                "onboard:"+session.lease().operationId()+":"+page.id(),digest,mode,
                Integer.toString(queues.size()),Integer.toString(page.ids().size()),
                rootField(session.request()),configuration(session.request()),Integer.toString(index+1),
                Integer.toString(pages.size()),Integer.toString(maxBytes),Integer.toString(fields.size()),
                pageToken(page)));
        args.addAll(fields);
        args.addAll(proofs);
        return fence.ownerClosed(control,session.lease(),session.request().generation().value(),
                manifest,INSTALL,nativeKeys,args);
    }

    private static String pageToken(Page page) {
        String digest=ProtectionKey.hash(page.id()+":"+page.nextCursor()+":"+page.last());
        for (String id:page.ids()) digest=ProtectionKey.hash(digest+":"+ProtectionKey.hash(id));
        return digest;
    }

    private Installed counts(Object reply,Page page) {
        if (!(reply instanceof List<?> result) || result.size()!=3
                || !(result.get(0) instanceof Number owned) || !(result.get(1) instanceof Number absent)
                || !(result.get(2) instanceof Number unknown)) throw incomplete();
        var installed=new Installed(owned.intValue(),absent.intValue(),unknown.intValue());
        if (installed.total()!=page.ids().size()) throw incomplete();
        return installed;
    }

    private static final String INSTALL="""
        local q=tonumber(ARGV[5]); local n=tonumber(ARGV[6]); local mode=ARGV[4]
        local index=tonumber(ARGV[9]); local total=tonumber(ARGV[10]); local budget=tonumber(ARGV[11])
        local nf=tonumber(ARGV[12]); local proofOffset=13+nf; local sealing=mode=='S'; local verify=mode=='V'
        if n<1 or n>128 or #KEYS~=1+q+9*n or #ARGV~=proofOffset+(mode=='I' and 2*n or 0)
          or index<1 or index>total or total>128 or nf>128 or budget<1024 or budget>1048576
          then return redis.error_reply('OWNER_INPUT_UNKNOWN') end
        for k=2,1+q do
          local t=redis.call('TYPE',KEYS[k]).ok
          if t~='none' and (t~='stream' or redis.call('XLEN',KEYS[k])~=0)
            then return redis.error_reply('OWNER_REPLAY_HISTORY_UNKNOWN') end
        end
        if redis.call('PTTL',KEYS[1])~=-1 or redis.call('HLEN',KEYS[1])>512
          then return redis.error_reply('OWNER_SEAL_UNKNOWN') end
        local sealField=ARGV[2]..':snapshot'
        local function retained(field)
          if redis.call('HSTRLEN',KEYS[1],field)>256 then error('OWNER_SEAL_UNBOUNDED') end
          return redis.call('HGET',KEYS[1],field)
        end
        local root=retained(ARGV[7]); local done=0; local chain=redis.sha1hex(ARGV[8]); local run=redis.call('HGET',KEYS[1],'run_id')
        if root then
          local config,expected,completed,digest,producer=string.match(root,'^([0-9a-f]+):(%d+):(%d+):([0-9a-f]+):([0-9a-f]+)$')
          if config~=ARGV[8] or expected~=ARGV[10] or not completed or not digest or #digest~=40 or producer~=run
            then return redis.error_reply('OWNER_SEAL_CHANGED') end
          done=tonumber(completed); chain=digest
          if done<0 or done>total then return redis.error_reply('OWNER_SEAL_CHANGED') end
        elseif not sealing then return redis.error_reply('OWNER_SEAL_MISSING') end
        if (sealing and index>done+1) or (not sealing and done~=total) then return redis.error_reply('OWNER_SEAL_INCOMPLETE') end
        local sealed=retained(sealField)
        if index<=done and not sealed then return redis.error_reply('OWNER_SEAL_MISSING') end
        if index>done and sealed then return redis.error_reply('OWNER_SEAL_CHANGED') end
        local sealedPage,sealedState
        if sealed then
          sealedPage,sealedState=string.match(sealed,'^([0-9a-f]+):([0-9a-f]+)$')
          if sealedPage~=ARGV[13] or not sealedState or #sealedState~=40 then return redis.error_reply('OWNER_PAGE_CHANGED') end
        end
        if not sealing and not sealed then return redis.error_reply('OWNER_SEAL_MISSING') end
        local receipt=retained(ARGV[2])
        if receipt and (not sealed or #receipt>256) then return redis.error_reply('OWNER_RECEIPT_UNKNOWN') end
        if verify and not receipt then return redis.error_reply('OWNER_RECEIPT_MISSING') end
        local priorDigest,priorStatus,priorState
        if receipt then
          priorDigest,priorStatus,priorState=string.match(receipt,'^([0-9a-f]+):([OAU]+):([0-9a-f]+)$')
          if not priorDigest or #priorDigest~=64 or not priorStatus or #priorStatus~=n or not priorState or #priorState~=40
            or (mode=='I' and priorDigest~=ARGV[3]) then return redis.error_reply('OWNER_RECEIPT_CHANGED') end
        end
        local used=0; local added=0
        local function admit(length)
          used=used+length
          if length>budget or used>budget then error('OWNER_VALUE_UNBOUNDED') end
        end
        local function bounded(k,max)
          local size=redis.call('STRLEN',k)
          if size>max then error('OWNER_VALUE_UNBOUNDED') end
          admit(size)
          return redis.call('GET',k)
        end
        local function version(k)
          local v=bounded(k,16)
          if v and (not string.match(v,'^%d+$') or (#v>1 and string.sub(v,1,1)=='0')
            or #v>16 or (#v==16 and v>'9007199254740991')) then error('OWNER_VERSION_UNKNOWN') end
          return v
        end
        local status={}; local state={}; local post={}; local writes={}; local owned=0; local absent=0; local unknown=0
        local clock=redis.call('TIME'); local now=tonumber(clock[1])*1000+math.floor(tonumber(clock[2])/1000)
        local expected={'string','string','hash','string','string','string','string'}
        for i=1,n do
          local b=1+q+9*(i-1); local present=false
          local types={}
          for role=1,7 do
            local t=redis.call('TYPE',KEYS[b+role]).ok
            if t~='none' and t~=expected[role] then return redis.error_reply('OWNER_ARTIFACT_TYPE_UNKNOWN') end
            types[role]=t
            if role~=7 and t~='none' then present=true end
          end
          if redis.call('EXISTS',KEYS[b+8],KEYS[b+9])~=0 then return redis.error_reply('OWNER_COMPACTION_HISTORY_UNKNOWN') end
          local tomb=version(KEYS[b+2]); local v=version(KEYS[b+4])
          local hot=bounded(KEYS[b+6],1024); local owner=bounded(KEYS[b+7],1024)
          if (owner and (owner~=ARGV[1] or redis.call('PTTL',KEYS[b+7])~=-1))
            or (hot and hot~=ARGV[1]) then return redis.error_reply('OWNER_CONFLICT') end
          local row={}
          for role=1,7 do
            row[#row+1]=types[role]
            local ttl=redis.call('PTTL',KEYS[b+role])
            row[#row+1]=ttl>=0 and string.format('%.0f',now+ttl) or tostring(ttl)
            if role==3 then
              local count=redis.call('HLEN',KEYS[b+role]); local known=0
              if count>nf then return redis.error_reply('OWNER_METADATA_UNKNOWN') end
              for f=1,nf do
                local field=ARGV[13+f]
                if redis.call('HEXISTS',KEYS[b+role],field)==1 then
                  known=known+1; local size=redis.call('HSTRLEN',KEYS[b+role],field); admit(size)
                  row[#row+1]=field; row[#row+1]=redis.sha1hex(redis.call('HGET',KEYS[b+role],field))
                end
              end
              if known~=count then return redis.error_reply('OWNER_METADATA_UNKNOWN') end
            else
              local value
              if role==2 then value=tomb elseif role==4 then value=v elseif role==6 then value=hot
              elseif role==7 then value=owner else value=bounded(KEYS[b+role],budget) end
              row[#row+1]=value and redis.sha1hex(value) or '-'
            end
          end
          state[#state+1]=redis.sha1hex(table.concat(row,':'))
          local outcome
          if receipt then
            outcome=string.sub(priorStatus,i,i)
            if (outcome=='O' and (not present or not owner)) or (outcome=='A' and (present or owner))
              then return redis.error_reply('OWNER_ARTIFACT_CHANGED') end
          elseif sealing then outcome='U'
          elseif ARGV[proofOffset+2*i-1]~='1' then outcome='U'
          elseif not present then
            if owner then return redis.error_reply('OWNER_ARTIFACT_CHANGED') end
            outcome='A'
          else
            local source=ARGV[proofOffset+2*i]
            if (v and tonumber(v)>tonumber(source)) or (tomb and tonumber(tomb)>tonumber(source))
              then return redis.error_reply('OWNER_SOURCE_VERSION_BEHIND') end
            outcome='O'
            if not owner then
              if not redis.acl_check_cmd('SET',KEYS[b+7],ARGV[1]) then return redis.error_reply('OWNER_ACL_DENIED') end
              writes[#writes+1]=KEYS[b+7]
              added=added+#ARGV[1]
              -- Owner is the final role; every other artifact/absolute expiry stays byte-identical.
              row[#row-2]='string'; row[#row-1]='-1'; row[#row]=redis.sha1hex(ARGV[1])
            end
          end
          status[#status+1]=outcome
          if outcome=='O' then owned=owned+1 elseif outcome=='A' then absent=absent+1 else unknown=unknown+1 end
          post[#post+1]=redis.sha1hex(table.concat(row,':'))
        end
        local snapshot=redis.sha1hex(table.concat(state,':'))
        if used+added>budget then return redis.error_reply('OWNER_VALUE_UNBOUNDED') end
        if sealed and snapshot~=(receipt and priorState or sealedState) then return redis.error_reply('OWNER_ARTIFACT_CHANGED') end
        if sealing then
          if sealed then return 1 end
          if redis.call('HLEN',KEYS[1])>510 then return redis.error_reply('OWNER_RECEIPT_CAPACITY') end
          local value=ARGV[13]..':'..snapshot
          local nextChain=redis.sha1hex(chain..':'..value)
          local nextRoot=ARGV[8]..':'..total..':'..index..':'..nextChain..':'..run
          if not redis.acl_check_cmd('HSET',KEYS[1],sealField,value,ARGV[7],nextRoot)
            then return redis.error_reply('OWNER_ACL_DENIED') end
          redis.call('HSET',KEYS[1],sealField,value,ARGV[7],nextRoot)
          return 1
        end
        if receipt then
          if snapshot~=priorState then return redis.error_reply('OWNER_ARTIFACT_CHANGED') end
        else
          if redis.call('HLEN',KEYS[1])>=512 then return redis.error_reply('OWNER_RECEIPT_CAPACITY') end
          if not redis.acl_check_cmd('HSET',KEYS[1],ARGV[2],'receipt') then return redis.error_reply('OWNER_ACL_DENIED') end
          for _,k in ipairs(writes) do redis.call('SET',k,ARGV[1]) end
          redis.call('HSET',KEYS[1],ARGV[2],ARGV[3]..':'..table.concat(status)..':'..redis.sha1hex(table.concat(post,':')))
        end
        return {owned,absent,unknown}
        """;

    private static ProtectionException invalid() { return new ProtectionException(ProtectionException.Code.INVALID_REQUEST); }
    private static ProtectionException incomplete() { return new ProtectionException(ProtectionException.Code.INCOMPLETE_INVENTORY); }
    private static void admin() {
        if (!CacheTenantContext.isAdministrative()) throw new ProtectionException(ProtectionException.Code.ADMIN_REQUIRED);
    }
}
