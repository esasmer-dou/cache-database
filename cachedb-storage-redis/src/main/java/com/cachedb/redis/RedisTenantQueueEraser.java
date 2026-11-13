package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.*;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import java.nio.charset.*;
import java.util.*;

/**
 * Native stream/DLQ erasure of wholly owned original-generation entries only.
 * Discovery is stable to an upper ID, physically byte bounded, and never deletes membership.
 * Persist each returned frame BEFORE consuming it into the durable worklist.
 */
public final class RedisTenantQueueEraser {
    public enum Ownership { OWNED, FOREIGN, INCOMPLETE }
    public record Snapshot(String upper,String runId) {
        public Snapshot { streamId(upper);if(runId==null||!runId.matches("[0-9a-f]{40}"))throw invalid(); }
    }
    public record Entry(String id,List<String> fields) {
        public Entry {
            streamId(id);if(fields==null||fields.isEmpty()||fields.size()>8192||(fields.size()&1)!=0)throw invalid();
            long bytes=0;for(String field:fields){
                if(field==null||field.length()>262144)throw invalid();
                bytes+=3L*field.length()+32;if(bytes>1048576)throw invalid();
            }
            fields=List.copyOf(fields);
        }
        @Override public String toString(){return "PurgeQueueEntry[redacted]";}
    }
    public record Frame(String input,String next,boolean finished,byte[] encoded,List<Entry> entries) {
        public Frame {
            streamId(input);streamId(next);
            if(encoded==null||encoded.length>1048576||entries==null||entries.size()>128)throw invalid();
            encoded=encoded.clone();entries=List.copyOf(entries);
        }
        @Override public byte[] encoded(){return encoded.clone();}
        @Override public String toString(){return "PurgeQueueFrame[entries="+entries.size()+"]";}
    }
    private final RedisClientOperations redis;private final BoundedRedisWire wire;
    private final RedisQueueGenerationFence mirror;private final String stream,namespace;
    private static final String CLOSED="""
        if redis.call('HGET',KEYS[2],'state')~='CLOSED'
            or redis.call('HGET',KEYS[2],'generation')~=ARGV[1]
            or redis.call('HGET',KEYS[2],'epoch')~=ARGV[2]
            or redis.call('HGET',KEYS[2],'operation')~=ARGV[3]
            or redis.call('HGET',KEYS[2],'owner')~=ARGV[4]
            or redis.call('HGET',KEYS[2],'run_id')~=cachedb_version_run_id()
            or redis.call('GET',KEYS[3])~=ARGV[5]
            or redis.call('PTTL',KEYS[3])<=0 then return redis.error_reply('PURGE_QUEUE_FENCE_LOST') end
        """;
    public RedisTenantQueueEraser(RedisClientOperations redis,BoundedRedisWire wire,String stream,String namespace) {
        if(namespace==null||namespace.length()>128||stream==null||stream.length()>8192)throw invalid();
        this.redis=Objects.requireNonNull(redis);this.wire=Objects.requireNonNull(wire);
        this.stream=stream;this.namespace=namespace;mirror=new RedisQueueGenerationFence(redis,stream);
    }
    /** The wire endpoint must be the actual current primary owning this stream's hash slot. */
    public Snapshot snapshot(Lease lease,long generation) {
        admin();String expected=closedRun(lease,generation);
        try(var s=wire.connect()){
            String run=primary(s);if(!run.equals(expected))throw recovery();
            var reply=s.call(BoundedRedisWire.Limits.entries(),"XREVRANGE",stream,"+","-","COUNT","1");
            var entries=entries(reply.value());return new Snapshot(entries.isEmpty()?"0-0":entries.get(0).id(),run);
        }
    }
    public Frame scan(Lease lease,long generation,Snapshot snapshot,String after) {
        admin();streamId(after);String expected=closedRun(lease,generation);
        if(compare(after,snapshot.upper())>0)throw invalid();
        // A reply cap is physical, not COUNT. Retry the SAME cursor with smaller bounded COUNT.
        long stop=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        for(int count=128;count>=1;count/=2){
            try(var session=wire.connect()){
                String run=primary(session);if(!snapshot.runId().equals(run)||!expected.equals(run))throw recovery();
                var reply=session.call(BoundedRedisWire.Limits.entries(),"XRANGE",stream,"("+after,snapshot.upper(),"COUNT",Integer.toString(count));
                var values=entries(reply.value());String next=values.isEmpty()?snapshot.upper():values.get(values.size()-1).id();
                return new Frame(after,next,values.isEmpty()||next.equals(snapshot.upper()),reply.encoded(),values);
            }catch(BoundedRedisWire.Failure failure){
                if(failure.code()!=BoundedRedisWire.Code.INPUT_LIMIT||count==1||System.nanoTime()>=stop)throw failure;
            }
        }
        throw new ProtectionException(ProtectionException.Code.INCOMPLETE_INVENTORY);
    }
    /** Durable reply decoding uses the SAME preallocation caps as socket replies. */
    public static List<Entry> decodePending(byte[] frame) {
        return entries(BoundedRedisWire.decode(frame,BoundedRedisWire.Limits.entries()).value());
    }
    public Ownership ownership(Entry entry,ProtectionKey target,long closedGeneration) {
        if(!target.scopeToken().equals(ProtectionKey.hash(namespace))||closedGeneration<1)throw invalid();
        var body=new LinkedHashMap<String,String>();
        for(int i=0;i<entry.fields().size();i+=2)
            if(body.putIfAbsent(entry.fields().get(i),entry.fields().get(i+1))!=null)return Ownership.INCOMPLETE;
        if(body.containsKey("batchCount")){
            int size;try{size=Integer.parseInt(body.getOrDefault("batchCount","0"));}catch(NumberFormatException bad){return Ownership.INCOMPLETE;}
            if(size<1||size>128)return Ownership.INCOMPLETE;
            for(String name:body.keySet())if(name.startsWith("b")){
                int colon=name.indexOf(':');
                if(colon>1 && name.substring(1,colon).matches("[0-9]+")){
                    int index;try{index=Integer.parseInt(name.substring(1,colon));}catch(NumberFormatException bad){return Ownership.INCOMPLETE;}
                    if(index>=size||!name.substring(1,colon).equals(Integer.toString(index))||name.endsWith(":batchCount"))return Ownership.INCOMPLETE;
                }
            }
            boolean own=false,foreign=false;
            for(int i=0;i<size;i++){
                Ownership leaf=leaf(body,"b"+i+":",target,closedGeneration);
                if(leaf==Ownership.INCOMPLETE)return leaf;
                own|=leaf==Ownership.OWNED;foreign|=leaf==Ownership.FOREIGN;
            }
            return own&&foreign?Ownership.INCOMPLETE:own?Ownership.OWNED:Ownership.FOREIGN;
        }
        for(String name:body.keySet())if(name.matches("b[0-9]+:.*"))return Ownership.INCOMPLETE;
        return leaf(body,"",target,closedGeneration);
    }
    private Ownership leaf(Map<String,String> body,String prefix,ProtectionKey target,long closedGeneration) {
        String ns=body.get(prefix+"namespace");
        if(ns==null||ns.length()>128)return Ownership.INCOMPLETE;
        if(!ns.equals(namespace))return Ownership.FOREIGN;
        String token=body.get(prefix+"tenantToken"),scope=body.get(prefix+"generationScope"),gen=body.get(prefix+"tenantGeneration");
        if(token==null||scope==null||gen==null||!scope.equals(target.scopeToken())||!token.matches("[0-9a-f]{64}")
                ||!gen.matches("[1-9][0-9]{0,18}"))return Ownership.INCOMPLETE;
        long value;try{value=Long.parseLong(gen);}catch(NumberFormatException bad){return Ownership.INCOMPLETE;}
        if(!token.equals(target.tenantToken()))return Ownership.FOREIGN;
        return value<=closedGeneration?Ownership.OWNED:Ownership.INCOMPLETE;
    }
    /**
     * One durable request/result per stream+pair; retry after a lost response returns the SAME count.
     * Commit result+work offset in the SQL purge transaction before acknowledgeResult().
     * No XTRIM/DEL stream, no canonical DELETE, no SQL enqueue, and no foreign/mixed-entry splitting.
     */
    public long erase(Lease lease,long generation,Entry entry,String request) {
        admin();ProtectionKey.token(request);
        if(ownership(entry,lease.key(),generation)!=Ownership.OWNED)
            throw new ProtectionException(ProtectionException.Code.INCOMPLETE_INVENTORY);
        var a=new ArrayList<>(args(lease,generation));a.add(request);a.add(entry.id());a.add(Integer.toString(entry.fields().size()));a.addAll(entry.fields());
        String script=RedisTenantVersionAllocator.library()+CLOSED+"""
            local previous=redis.call('HGET',KEYS[2],'cleanup_request')
            if previous then
                if previous~=ARGV[6] or redis.call('HGET',KEYS[2],'cleanup_id')~=ARGV[7]
                    then return redis.error_reply('PURGE_QUEUE_RESULT_PENDING') end
                return tonumber(redis.call('HGET',KEYS[2],'cleanup_removed'))
            end
            local current=redis.call('XRANGE',KEYS[1],ARGV[7],ARGV[7],'COUNT',1)
            if #current==0 then return redis.error_reply('PURGE_QUEUE_OUTCOME_UNKNOWN') end
            local fields=current[1][2]
            if #fields~=tonumber(ARGV[8]) then return redis.error_reply('PURGE_QUEUE_ENTRY_CHANGED') end
            for i=1,#fields do if fields[i]~=ARGV[i+8] then return redis.error_reply('PURGE_QUEUE_ENTRY_CHANGED') end end
            if redis.acl_check_cmd and (not redis.acl_check_cmd('XDEL',KEYS[1],ARGV[7])
                or not redis.acl_check_cmd('HSET',KEYS[2],'cleanup_request',ARGV[6],'cleanup_id',ARGV[7],'cleanup_removed','1'))
                then return redis.error_reply('PURGE_QUEUE_ACL') end
            redis.call('HSET',KEYS[2],'cleanup_request',ARGV[6],'cleanup_id',ARGV[7],'cleanup_removed','1')
            redis.call('XDEL',KEYS[1],ARGV[7]);return 1
            """;
        var k=keys(lease);RedisProtectionArguments.validate(script,k,a);redis.requireSameSlot(k);
        return ((Number)evaluate(script,k,a)).longValue();
    }
    public void acknowledgeResult(Lease lease,long generation,String request) {
        admin();ProtectionKey.token(request);var a=new ArrayList<>(args(lease,generation));a.add(request);
        evaluate(RedisTenantVersionAllocator.library()+CLOSED+"""
            if redis.call('HGET',KEYS[2],'cleanup_request')==ARGV[6] then
                return redis.call('HDEL',KEYS[2],'cleanup_request','cleanup_id','cleanup_removed')
            end
            return 0
            """,keys(lease),a);
    }
    public void assertClosed(Lease lease,long generation){closedRun(lease,generation);}
    private String closedRun(Lease lease,long generation){
        admin();redis.requireSameSlot(keys(lease));
        return (String)evaluate(RedisTenantVersionAllocator.library()+CLOSED+"return cachedb_version_run_id()",keys(lease),args(lease,generation));
    }
    private Object evaluate(String script,List<String> keys,List<String> args){
        RedisProtectionArguments.validate(script,keys,args);redis.requireSameSlot(keys);
        try{return redis.eval(script,keys,args);}
        catch(ProtectionException safe){throw safe;}
        catch(RuntimeException failure){throw new ProtectionException(ProtectionException.Code.STORAGE_UNAVAILABLE);}
    }
    private List<String> keys(Lease lease){return List.of(stream,mirror.guardKey(lease.key()),mirror.leaseKey(lease.key()));}
    private List<String> args(Lease lease,long generation){
        if(generation<1||!lease.key().scopeToken().equals(ProtectionKey.hash(namespace)))throw invalid();
        return List.of(Long.toString(generation),Long.toString(lease.epoch()),lease.operationId().toString(),lease.owner().toString(),lease.operationId()+":"+lease.owner()+":"+lease.epoch());
    }
    private String primary(BoundedRedisWire.Session s){
        var role=list(s.call(BoundedRedisWire.Limits.entries(),"ROLE").value());
        if(role.isEmpty()||!"master".equals(role.get(0)))throw recovery();
        String info=text(s.call(BoundedRedisWire.Limits.entries(),"INFO","SERVER").value()),run=null;
        for(String line:info.split("\\r?\\n"))if(line.startsWith("run_id:"))run=line.substring(7);
        if(run==null||!run.matches("[0-9a-f]{40}"))throw recovery();
        if(redis.clustered()){
            long slot=((Number)s.call(BoundedRedisWire.Limits.scan(),"CLUSTER","KEYSLOT",stream).value()).longValue();
            String nodes=text(s.call(BoundedRedisWire.Limits.entries(),"CLUSTER","NODES").value());boolean owned=false;int count=0;
            for(String line:nodes.split("\\n")){
                if(++count>128)throw recovery();String[] p=line.trim().split(" +");if(p.length<8)throw recovery();
                if(p[2].contains("fail")||p[2].contains("handshake")||p[2].contains("noaddr"))throw recovery();
                for(int i=8;i<p.length;i++){
                    if(!p[i].matches("[0-9]{1,5}(-[0-9]{1,5})?"))throw recovery();
                    String[] ends=p[i].split("-");long lo=Long.parseLong(ends[0]),hi=Long.parseLong(ends[ends.length-1]);
                    if(p[2].contains("myself")&&p[2].contains("master")&&slot>=lo&&slot<=hi)owned=true;
                }
            }
            if(!owned)throw recovery();
        }
        return run;
    }
    private static List<Entry> entries(Object reply) {
        var rows=list(reply);if(rows.size()>128)throw invalid();var result=new ArrayList<Entry>(rows.size());
        for(Object value:rows){
            var row=list(value);if(row.size()!=2)throw invalid();var pairs=list(row.get(1));var fields=new ArrayList<String>(pairs.size());
            for(Object field:pairs)fields.add(text(field));result.add(new Entry(text(row.get(0)),fields));
        }
        return List.copyOf(result);
    }
    private static int compare(String a,String b){
        var x=a.split("-");var y=b.split("-");
        for(int i=0;i<2;i++){int n=Integer.compare(x[i].length(),y[i].length());if(n!=0)return n;n=x[i].compareTo(y[i]);if(n!=0)return n;}
        return 0;
    }
    private static void streamId(String value){
        if(value==null||!value.matches("(0|[1-9][0-9]{0,19})-(0|[1-9][0-9]{0,19})"))throw invalid();
    }
    private static List<?> list(Object value){if(!(value instanceof List<?> list))throw invalid();return list;}
    private static String text(Object value){if(!(value instanceof String text))throw invalid();return text;}
    private static void admin(){if(!CacheTenantContext.isAdministrative())throw new ProtectionException(ProtectionException.Code.ADMIN_REQUIRED);}
    private static ProtectionException recovery(){return new ProtectionException(ProtectionException.Code.RECOVERY_REQUIRED);}
    private static ProtectionException invalid(){return new ProtectionException(ProtectionException.Code.INVALID_REQUEST);}
}
