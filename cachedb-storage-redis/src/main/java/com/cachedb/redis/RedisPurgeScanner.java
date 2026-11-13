package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.*;
import java.util.*;

/** Actual bounded owner/namespace/member scanner. It never deletes during discovery. */
public final class RedisPurgeScanner {
    public enum Kind { OWNER, NAMESPACE, SET, ZSET, LIST }
    public record Frame(String inputCursor,String nextCursor,List<String> items,byte[] wire,String topology) {
        public Frame { items=List.copyOf(items);wire=wire.clone(); }
        @Override public byte[] wire(){return wire.clone();}
        public boolean last(){return nextCursor.equals("0");}
        @Override public String toString(){return "PurgeScanFrame[items="+items.size()+"]";}
    }
    public record Shared(String key,Kind kind) {
        @Override public String toString(){return "PurgeShared[redacted]";}
    }
    public record Owners(List<String> ownedIds,List<String> ownedKeys,List<Shared> shared,int examined,int unknown) {
        public Owners { ownedIds=List.copyOf(ownedIds);ownedKeys=List.copyOf(ownedKeys);shared=List.copyOf(shared); }
        @Override public String toString(){return "PurgeOwners[examined="+examined+",unknown="+unknown+"]";}
    }
    private final BoundedRedisWire wire;
    private final RedisClientOperations redis;
    private final RedisKeyStrategy keys;
    private final RedisGenerationBarrier barrier;
    private final String namespace;
    private final boolean cluster;
    private final String tenantColumn;
    private final String ownerNamespace;
    private final Map<String,Kind> declaredPages;
    private final Set<String> descendants;
    private final Set<String> coveredQueues;
    public RedisPurgeScanner(BoundedRedisWire wire,RedisClientOperations redis,RedisKeyStrategy keys,
            RedisGenerationBarrier barrier,String namespace) {
        this(wire,redis,keys,barrier,namespace,null,List.of());
    }
    public RedisPurgeScanner(BoundedRedisWire wire,RedisClientOperations redis,RedisKeyStrategy keys,
            RedisGenerationBarrier barrier,String namespace,String indexedTenantColumn,Collection<String> coveredQueues) {
        this(wire,redis,keys,barrier,namespace,indexedTenantColumn,coveredQueues,namespace,Map.of());
    }
    public RedisPurgeScanner(BoundedRedisWire wire,RedisClientOperations redis,RedisKeyStrategy keys,
            RedisGenerationBarrier barrier,String namespace,String indexedTenantColumn,Collection<String> coveredQueues,
            String ownerNamespace,Map<String,Kind> declaredPages) {
        this(wire,redis,keys,barrier,namespace,indexedTenantColumn,coveredQueues,ownerNamespace,declaredPages,Set.of());
    }
    public RedisPurgeScanner(BoundedRedisWire wire,RedisClientOperations redis,RedisKeyStrategy keys,
            RedisGenerationBarrier barrier,String namespace,String indexedTenantColumn,Collection<String> coveredQueues,
            String ownerNamespace,Map<String,Kind> declaredPages,Set<String> declaredNamespaces) {
        if(declaredNamespaces==null||declaredNamespaces.size()>8)throw new IllegalArgumentException("Invalid namespace manifest");
        var childPrefixes=new HashSet<String>();
        for(String ns:declaredNamespaces){
            String prefix=keys.namespacePrefix(ns)+":";
            if(!ns.equals(namespace)&&prefix.startsWith(keys.namespacePrefix(namespace)+":"))childPrefixes.add(prefix);
        }
        this.descendants=Set.copyOf(childPrefixes);
        if(declaredPages==null||declaredPages.size()>128||declaredPages.values().stream().anyMatch(k->k!=Kind.LIST&&k!=Kind.ZSET))
            throw new IllegalArgumentException("Invalid declared pages");
        this.ownerNamespace=Objects.requireNonNull(ownerNamespace);this.declaredPages=Map.copyOf(declaredPages);
        if(coveredQueues.size()>128)throw new IllegalArgumentException("At most 128 queue sources");
        this.tenantColumn=indexedTenantColumn;this.coveredQueues=Set.copyOf(coveredQueues);
        this.wire=Objects.requireNonNull(wire);this.redis=Objects.requireNonNull(redis);this.keys=Objects.requireNonNull(keys);
        this.barrier=Objects.requireNonNull(barrier);this.namespace=Objects.requireNonNull(namespace);cluster=redis.clustered();
        RedisProtectionArguments.layout(barrier,keys,ownerNamespace);
        if(cluster) {
            String tag=hashTag(keys.namespacePrefix(namespace));
            for(String key:List.of(keys.entityKey(namespace,"probe"),keys.versionKey(namespace,"probe"),
                    keys.tombstoneKey(namespace,"probe"),keys.durabilityKey(namespace,"probe"),
                    keys.indexMetaKey(namespace,"probe"),keys.hotSetKey(namespace),
                    barrier.generationKey(ProtectionKey.of(ownerNamespace,"layout-probe"))))
                if(!tag.equals(hashTag(key)) || !key.startsWith(keys.namespacePrefix(namespace)+":"))
                    throw new IllegalArgumentException("Purge requires one explicitly tagged namespace layout");
        }
    }
    public String captureTopology() {
        try(var session=wire.connect()){return topology(session);}
    }
    private String topology(BoundedRedisWire.Session session) {
        List<?> role=list(session.call(BoundedRedisWire.Limits.entries(),"ROLE").value());
        if(role.isEmpty() || !"master".equals(role.get(0)))throw new BoundedRedisWire.Failure(BoundedRedisWire.Code.TOPOLOGY_CHANGED);
        String info=text(session.call(BoundedRedisWire.Limits.entries(),"INFO","SERVER").value());
        if(info.length()>262144)throw new BoundedRedisWire.Failure(BoundedRedisWire.Code.INPUT_LIMIT);
        String runId=null;
        for(String line:info.split("\\r?\\n"))if(line.startsWith("run_id:"))runId=line.substring(7);
        if(runId==null || !runId.matches("[0-9a-f]{40}"))throw new BoundedRedisWire.Failure(BoundedRedisWire.Code.TOPOLOGY_CHANGED);
        if(!cluster)return ProtectionKey.hash("standalone-master:"+runId);
        String nodes=text(session.call(BoundedRedisWire.Limits.entries(),"CLUSTER","NODES").value());
        if(nodes.length()>262144 || nodes.contains("["))throw new BoundedRedisWire.Failure(BoundedRedisWire.Code.TOPOLOGY_CHANGED);
        var stable=new ArrayList<String>();
        String self=null; boolean owns=false;
        long slot=((Number)session.call(BoundedRedisWire.Limits.scan(),"CLUSTER","KEYSLOT",keys.versionKey(namespace,"probe")).value()).longValue();
        for(String line:nodes.split("\n")) {
            if(stable.size()==128)throw new BoundedRedisWire.Failure(BoundedRedisWire.Code.INPUT_LIMIT);
            String[] fields=line.trim().split(" +");
            if(fields.length<8)throw new BoundedRedisWire.Failure(BoundedRedisWire.Code.TOPOLOGY_CHANGED);
            String flags=fields[2];
            if(flags.contains("fail") || flags.contains("handshake") || flags.contains("noaddr"))
                throw new BoundedRedisWire.Failure(BoundedRedisWire.Code.TOPOLOGY_CHANGED);
            var slots=new ArrayList<String>();
            for(int i=8;i<fields.length;i++) {
                String range=fields[i]; if(!range.matches("[0-9]{1,5}(-[0-9]{1,5})?"))throw new BoundedRedisWire.Failure(BoundedRedisWire.Code.TOPOLOGY_CHANGED);
                slots.add(range);
                if(flags.contains("myself") && flags.contains("master")) {
                    String[] ends=range.split("-");long lo=Long.parseLong(ends[0]),hi=Long.parseLong(ends[ends.length-1]);
                    if(slot>=lo && slot<=hi)owns=true;
                }
            }
            if(flags.contains("myself"))self=fields[0];
            stable.add(fields[0]+":"+fields[1]+":"+flags+":"+fields[6]+":"+String.join(",",slots));
        }
        if(self==null || !owns)throw new BoundedRedisWire.Failure(BoundedRedisWire.Code.TOPOLOGY_CHANGED);
        stable.sort(String::compareTo);
        // Hash incrementally: topology text may exceed the ordinary 4096-character token input cap.
        try {
            var digest=java.security.MessageDigest.getInstance("SHA-256");
            digest.update(runId.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            for(String line:stable)digest.update((line+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException("SHA-256 unavailable");}
    }
    public Frame scan(Lease lease,long generation,Kind kind,String sourceKey,String cursor,String expectedTopology) {
        if(cursor==null || !cursor.matches("[0-9]{1,20}"))throw new IllegalArgumentException("Invalid Redis cursor");
        ProtectionKey.token(expectedTopology);barrier.assertClosed(lease,generation);
        BoundedRedisWire.Reply reply;
        try(var session=wire.connect()) {
            if(!expectedTopology.equals(topology(session)))throw new BoundedRedisWire.Failure(BoundedRedisWire.Code.TOPOLOGY_CHANGED);
            if(kind==Kind.OWNER || kind==Kind.NAMESPACE) {
                String pattern=glob(kind==Kind.OWNER?keys.versionKey(namespace,""):keys.namespacePrefix(namespace)+":")
                        +(kind==Kind.OWNER?"*:tenant":"*");
                reply=session.call(BoundedRedisWire.Limits.scan(),"SCAN",cursor,"MATCH",pattern,"COUNT","500");
            } else {
                if(sourceKey==null || !sourceKey.startsWith(keys.namespacePrefix(namespace)+":") || sourceKey.length()>1024)
                    throw new IllegalArgumentException("Foreign scan source");
                redis.requireSameSlot(List.of(barrier.generationKey(lease.key()),sourceKey));
                if(kind==Kind.LIST) {
                    long offset=Long.parseLong(cursor);
                    if(offset>10_000_000)throw new BoundedRedisWire.Failure(BoundedRedisWire.Code.INPUT_LIMIT);
                    reply=session.call(BoundedRedisWire.Limits.scan(),"LRANGE",sourceKey,cursor,Long.toString(offset+127));
                } else reply=session.call(BoundedRedisWire.Limits.scan(),kind==Kind.SET?"SSCAN":"ZSCAN",sourceKey,cursor,"COUNT","500");
            }
            if(!expectedTopology.equals(topology(session)))throw new BoundedRedisWire.Failure(BoundedRedisWire.Code.TOPOLOGY_CHANGED);
        }
        barrier.assertClosed(lease,generation);
        return decode(kind,cursor,reply,expectedTopology);
    }
    public static Frame decode(Kind kind,String input,BoundedRedisWire.Reply reply,String topology) {
        if(kind==Kind.LIST) {
            List<?> values=list(reply.value());
            if(values.size()>128)throw new BoundedRedisWire.Failure(BoundedRedisWire.Code.INPUT_LIMIT);
            var ids=new ArrayList<String>(values.size());
            for(Object value:values){String id=text(value);if(id.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>1024)
                throw new BoundedRedisWire.Failure(BoundedRedisWire.Code.INPUT_LIMIT);ids.add(id);}
            String next=ids.isEmpty()?"0":Long.toString(Math.addExact(Long.parseLong(input),ids.size()));
            return new Frame(input,next,ids,reply.encoded(),topology);
        }
        List<?> parts=list(reply.value());if(parts.size()!=2)throw new BoundedRedisWire.Failure(BoundedRedisWire.Code.PROTOCOL);
        String next=text(parts.get(0));if(!next.matches("[0-9]{1,20}"))throw new BoundedRedisWire.Failure(BoundedRedisWire.Code.PROTOCOL);
        List<?> values=list(parts.get(1));int stride=kind==Kind.ZSET?2:1;
        if(values.size()>4096 || values.size()%stride!=0)throw new BoundedRedisWire.Failure(BoundedRedisWire.Code.INPUT_LIMIT);
        var items=new ArrayList<String>();
        for(int i=0;i<values.size();i+=stride) {
            String value=text(values.get(i));
            if(value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>1024)throw new BoundedRedisWire.Failure(BoundedRedisWire.Code.INPUT_LIMIT);
            items.add(value);
        }
        return new Frame(input,next,items,reply.encoded(),topology);
    }
    /** Resolve <=128 persisted pending keys/members. Exact ownership, no SQL/bootstrap fallback. */
    public Owners resolve(Lease lease,long generation,Kind kind,List<String> pending,String tenant) {
        if(pending.size()>128 || !barrier.key(tenant).equals(lease.key()))throw new IllegalArgumentException("Invalid ownership chunk");
        var ids=new ArrayList<String>();var rawOwned=new ArrayList<String>();var shared=new ArrayList<Shared>();int unknown=0;
        for(String value:pending) {
            if(kind==Kind.SET || kind==Kind.ZSET || kind==Kind.LIST) {
                if(safeId(value))ids.add(value);else unknown++;
                continue;
            }
            String id=identity(value);
            if(id!=null){if(safeId(id))ids.add(id);else unknown++;continue;}
            Shared candidate=shared(value);
            if(candidate!=null){shared.add(candidate);continue;}
            int quota=quotaOwner(value,tenant);
            if(quota==1){rawOwned.add(value);continue;}
            if(quota==2)continue;
            if(!control(value))unknown++;
        }
        var ownerKeys=new ArrayList<String>(barrier.fencedKeys(lease));
        for(String id:ids)ownerKeys.add(keys.versionKey(ownerNamespace,id)+":tenant");
        var args=new ArrayList<String>(barrier.fencedArgs(lease,generation));args.add(Integer.toString(ids.size()));
        Object raw=redis.eval(RedisGenerationBarrier.closedCheckLua()+"""
            local n=tonumber(ARGV[6]);local result={}
            for i=1,n do
              local t=redis.call('TYPE',KEYS[2+i]).ok
              if t~='none' and t~='string' then return redis.error_reply('PURGE_OWNER_LAYOUT') end
              if redis.call('STRLEN',KEYS[2+i])>1024 then return redis.error_reply('PURGE_INPUT_LIMIT') end
            end
            for i=1,n do result[i]=redis.call('GET',KEYS[2+i]) or false end
            return result
            """,ownerKeys,args);
        List<?> owners=list(raw);if(owners.size()!=ids.size())throw new BoundedRedisWire.Failure(BoundedRedisWire.Code.PROTOCOL);
        var owned=new LinkedHashSet<String>();
        for(int i=0;i<ids.size();i++) {
            if(owners.get(i)==null || Boolean.FALSE.equals(owners.get(i)))unknown++;
            else if(tenant.equals(text(owners.get(i))))owned.add(ids.get(i));
        }
        return new Owners(List.copyOf(owned),rawOwned,shared,pending.size(),unknown);
    }
    private String identity(String key) {
        String version=keys.versionKey(namespace,"");
        if(key.startsWith(version) && key.endsWith(":tenant"))return key.substring(version.length(),key.length()-7);
        for(String prefix:List.of(keys.entityKey(namespace,""),version,keys.tombstoneKey(namespace,""),
                keys.durabilityKey(namespace,""),keys.indexMetaKey(namespace,""),keys.tenantHotOwnerKey(namespace,""),
                keys.compactionPayloadKey(namespace,""),keys.compactionPendingKey(namespace,"")))
            if(key.startsWith(prefix))return key.substring(prefix.length());
        return null;
    }
    private Shared shared(String key) {
        String prefix=keys.namespacePrefix(namespace)+":";
        if(!key.startsWith(prefix))return null;
        Kind pageKind=declaredPages.get(key);if(pageKind!=null)return new Shared(key,pageKind);
        if(key.equals(keys.hotSetKey(namespace)) || key.startsWith(keys.hotSetKey(namespace)+":tenant:") && !key.endsWith(":payload-bytes") && !key.endsWith(":admission-lock"))
            return new Shared(key,Kind.ZSET);
        if(key.equals(keys.indexAllKey(namespace)))return new Shared(key,Kind.SET);
        String exact=keys.indexExactKey(namespace,"","");exact=exact.substring(0,exact.length()-1);
        if(key.startsWith(exact))return new Shared(key,key.endsWith(":sort")?Kind.ZSET:Kind.SET);
        String partition=keys.indexPartitionSortKey(namespace,"","","");partition=partition.substring(0,partition.length()-2);
        if(key.startsWith(keys.indexSortKey(namespace,""))||key.startsWith(partition))return new Shared(key,Kind.ZSET);
        String text=keys.indexPrefixKey(namespace,"",""),token=keys.indexTokenKey(namespace,"","");
        if(key.startsWith(text.substring(0,text.length()-1))||key.startsWith(token.substring(0,token.length()-1)))return new Shared(key,Kind.SET);
        return null;
    }
    private boolean control(String key) {
        String prefix=keys.namespacePrefix(namespace)+":";
        for(String child:descendants)if(key.startsWith(child))return true; // separately swept declared native projection
        return key.matches(java.util.regex.Pattern.quote(prefix+"generation:")+"[0-9a-f]{64}(:purge-lease|:compaction-result|:residual-result|:read-closure)?")
                || key.matches(java.util.regex.Pattern.quote(prefix+"retention:")+"[0-9a-f]{64}:(lease|epoch)")
                || key.matches(java.util.regex.Pattern.quote(prefix+"purge:")+"[0-9a-f]{64}:[0-9a-f-]{36}:native:[0-9a-f]{64}")
                || key.equals(keys.compactionStatsKey(namespace)) || key.equals(keys.compositeIndexSignatureKey(namespace))
                || key.equals(prefix+"security:runtime-roster")
                || key.equals(keys.indexPlannerEpochKey(namespace)) || key.equals(keys.indexDegradedKey(namespace))
                || key.equals(keys.indexRebuildLockKey(namespace))
                || key.matches(java.util.regex.Pattern.quote(prefix+"security:version:")+"[0-9a-f]{64}(:next)?")
                || coveredQueues.contains(key);
    }
    private int quotaOwner(String key,String tenant) {
        if(tenantColumn==null)return 0;
        String prefix=keys.tenantHotSetKey(namespace,tenantColumn,"");
        if(!key.startsWith(prefix))return 0;
        String suffix=key.substring(prefix.length());
        if(suffix.endsWith(":payload-bytes"))suffix=suffix.substring(0,suffix.length()-14);
        else if(suffix.endsWith(":admission-lock"))suffix=suffix.substring(0,suffix.length()-15);
        else return 0;
        try {
            byte[] raw=Base64.getUrlDecoder().decode(suffix);
            if(raw.length>1024)return 0;
            String owner=java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(raw)).toString();
            return tenant.equals(owner)?1:2;
        } catch(IllegalArgumentException | java.nio.charset.CharacterCodingException invalid){return 0;}
    }
    private static String hashTag(String key) {
        int open=key.indexOf('{'),close=open<0?-1:key.indexOf('}',open+1);
        if(open<0 || close<=open+1)throw new IllegalArgumentException("Missing stable namespace hash tag");
        return key.substring(open+1,close);
    }
    private static boolean safeId(String id){return id.matches("[A-Za-z0-9._-]{1,1024}");}
    private static String glob(String value) {
        var result=new StringBuilder();
        for(char c:value.toCharArray()){if(c=='*'||c=='?'||c=='['||c==']'||c=='\\')result.append('\\');result.append(c);}
        return result.toString();
    }
    private static List<?> list(Object value){if(value instanceof List<?> list)return list;throw new BoundedRedisWire.Failure(BoundedRedisWire.Code.PROTOCOL);}
    private static String text(Object value){if(value instanceof String text)return text;throw new BoundedRedisWire.Failure(BoundedRedisWire.Code.PROTOCOL);}
}
