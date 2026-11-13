package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.*;
import com.reactor.cachedb.jdbc.*;
import com.reactor.cachedb.redis.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Concrete native discovery and independent post-erasure audit. No deletion while scanning membership. */
final class RedisTenantPurgeDiscovery {
    private final RedisTenantPurgePlan p;
    private final JdbcPurgeScanJournal inventory;
    private final JdbcPurgeFacadeJournal journal;
    private final boolean initialPurpose;
    private final Lease lease;
    private final long generation;
    private final String tenant;
    private final Map<String,RedisPurgeScanner> scanners=new HashMap<>();
    private final List<JdbcPurgeScanJournal.Source> seeds=new ArrayList<>();
    private final Map<String,RedisTenantPurgePlan.QueueRoute> queues=new HashMap<>();
    private final RedisVersionInventory versions;
    RedisTenantPurgeDiscovery(RedisTenantPurgePlan p,Lease lease,long generation,String tenant){
        this(p,lease,generation,tenant,p.inventory,p.journal,false);
    }
    RedisTenantPurgeDiscovery(RedisTenantPurgePlan p,Lease lease,long generation,String tenant,
            JdbcPurgeScanJournal inventory,JdbcPurgeFacadeJournal journal,boolean initialPurpose){
        this.inventory=inventory;this.journal=journal;this.initialPurpose=initialPurpose;
        this.p=p;this.lease=lease;this.generation=generation;this.tenant=tenant;
        versions=new RedisVersionInventory(p.redis,p.keys,p.barrier,p.binding.scope());
        for(var q:p.queues)queues.put(q.token(),q);
        var covered=p.queues.stream().map(RedisTenantPurgePlan.QueueRoute::stream).toList();
        for(var target:p.targets){
            var scanner=new RedisPurgeScanner(p.wire,p.redis,p.keys,p.barrier,target.namespace(),
                    RedisPurgeRouteLayout.tenantColumn(target),covered,p.binding.scope(),RedisPurgeRouteLayout.pages(target),p.namespaces);
            scanners.put(target.namespace(),scanner);
            if(target.namespace().equals(p.binding.scope()))seeds.add(source(target.namespace(),RedisPurgeScanner.Kind.OWNER,""));
            seeds.add(source(target.namespace(),RedisPurgeScanner.Kind.NAMESPACE,""));
            RedisPurgeRouteLayout.pages(target).entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(e->seeds.add(source(target.namespace(),e.getValue(),e.getKey())));
        }
        if(seeds.size()>1100)throw incomplete();
    }
    /** One seed/queue snapshot per invocation; snapshot is committed BEFORE scan registration. */
    boolean seed(boolean audit){
        String id=audit?"audit-seed":"inventory-seed";
        var checkpoint=journal.create(lease,id,"0",new byte[0],p.fingerprint);
        if(checkpoint.done())return true;
        int index=Integer.parseInt(checkpoint.cursor()),total=seeds.size()+p.queues.size();
        if(index<0||index>total)throw incomplete();
        if(index==total){
            journal.commit(lease,checkpoint,checkpoint.cursor(),new byte[0],true,p.fingerprint,0,0,0);return true;
        }
        JdbcPurgeScanJournal.Source source;
        if(index<seeds.size())source=seeds.get(index);
        else{
            var route=p.queues.get(index-seeds.size());
            String qid=(audit?"audit-q-":"inventory-q-")+route.token();
            var snapshot=journal.get(lease,qid);
            if(snapshot==null){
                var captured=route.eraser().snapshot(lease,generation);
                snapshot=journal.create(lease,qid,captured.upper()+"|"+captured.runId(),new byte[0],p.fingerprint);
            }
            source=new JdbcPurgeScanJournal.Source(ProtectionKey.hash("stream:"+route.token()),
                    JdbcPurgeScanJournal.Kind.STREAM,route.token()+"|"+snapshot.cursor());
        }
        if(audit)createAudit(source);
        else inventory.register(lease,List.of(source));
        journal.commit(lease,checkpoint,Integer.toString(index+1),new byte[0],false,p.fingerprint,0,0,0);
        return false;
    }
    /** Whole native wire reply is durable before <=128 items are resolved or cursor advances. */
    boolean discover(){
        if(!seed(false))return false;
        var pending=inventory.next(lease);
        if(pending==null)return inventory.discoveryComplete(lease);
        if(pending.failed())throw incomplete();
        if(pending.frame()==null){
            inventory.stage(lease,pending.source(),capture(pending.source(),pending.cursor(),false));return false;
        }
        var frame=pending.frame();
        if(pending.source().kind()==JdbcPurgeScanJournal.Kind.STREAM){
            var route=queue(pending.source());
            var entries=RedisTenantQueueEraser.decodePending(frame.encoded());
            int end=Math.min(entries.size(),pending.offset()+128),unknown=0;
            var work=new ArrayList<PurgeWorkItem>();long bytes=0;
            for(int i=pending.offset();i<end;i++){
                var entry=entries.get(i);var ownership=route.eraser().ownership(entry,lease.key(),generation);
                if(ownership==RedisTenantQueueEraser.Ownership.INCOMPLETE){unknown++;continue;}
                if(ownership==RedisTenantQueueEraser.Ownership.OWNED){
                    // Initial activation is non-destructive: unresolved accepted history is NOT erased or restamped.
                    if(initialPurpose){unknown++;continue;}
                    byte[] payload=PurgeQueueWorkCodec.encode(route.token(),entry);
                    // A complete pending frame remains in SQL. Stop BEFORE exceeding the work-append byte budget.
                    if(bytes+payload.length>1048576){end=i;break;}
                    bytes+=payload.length;
                    work.add(new PurgeWorkItem(ProtectionKey.hash(route.token()+":"+entry.id()),PurgeWorkItem.Kind.ENTRY,payload));
                }
            }
            if(end==pending.offset()&&!entries.isEmpty())throw incomplete();
            inventory.consumeVersionInventory(lease,pending,end-pending.offset(),initialPurpose?List.of():work,List.of(),unknown,0);
            if(unknown>0)throw incomplete();
            return false;
        }
        var decoded=decode(pending.source(),frame);
        int end=Math.min(decoded.items().size(),pending.offset()+128);
        var chunk=decoded.items().subList(pending.offset(),end);
        var resolved=scanner(pending.source()).resolve(lease,generation,kind(pending.source()),chunk,tenant);
        var work=new LinkedHashMap<String,PurgeWorkItem>();
        for(String id:resolved.ownedIds())put(work,new PurgeWorkItem(lease.key().objectToken(id),PurgeWorkItem.Kind.ID,id.getBytes(StandardCharsets.UTF_8)));
        for(String key:resolved.ownedKeys())put(work,new PurgeWorkItem(ProtectionKey.hash("key:"+key),PurgeWorkItem.Kind.KEY,key.getBytes(StandardCharsets.UTF_8)));
        // A reply can expose 128 IDs plus 128 owned quota keys; consume smaller chunks rather than overflow.
        if(work.size()>128)throw incomplete();
        var spawned=new ArrayList<JdbcPurgeScanJournal.Source>();
        String ns=namespace(pending.source());
        for(var shared:resolved.shared())spawned.add(source(ns,shared.kind(),shared.key()));
        long maximum=ns.equals(p.binding.scope())?versions.maximum(lease,generation,kind(pending.source())==RedisPurgeScanner.Kind.LIST
                ?RedisPurgeScanner.Kind.SET:kind(pending.source()),chunk):0;
        inventory.consumeVersionInventory(lease,pending,chunk.size(),initialPurpose?List.of():List.copyOf(work.values()),spawned,resolved.unknown(),maximum);
        if(resolved.unknown()>0)throw incomplete();
        return false;
    }
    /** Separate durable audit jobs, rebuilt from current namespace/owner/page/stream state, not sealed work. */
    boolean audit(){
        if(initialPurpose)throw incomplete(); // Erasure audit is not an initial-population certificate.
        if(!seed(true))return false;
        var row=journal.next(lease,"audit-source-");
        if(row==null)return true;
        var job=AuditJob.decode(row.payload());
        if(job.frame==null){
            var frame=capture(job.source,job.cursor,true);
            journal.commit(lease,row,row.cursor(),new AuditJob(job.source,job.cursor,frame,0).encode(),false,p.fingerprint,0,0,0);
            return false;
        }
        int consumed;
        if(job.source.kind()==JdbcPurgeScanJournal.Kind.STREAM){
            var route=queue(job.source);var entries=RedisTenantQueueEraser.decodePending(job.frame.encoded());
            int end=Math.min(entries.size(),job.offset+128);consumed=end-job.offset;
            for(int i=job.offset;i<end;i++)
                if(route.eraser().ownership(entries.get(i),lease.key(),generation)!=RedisTenantQueueEraser.Ownership.FOREIGN)throw incomplete();
        }else{
            var decoded=decode(job.source,job.frame);int end=Math.min(decoded.items().size(),job.offset+128);
            var chunk=decoded.items().subList(job.offset,end);consumed=chunk.size();
            var owners=scanner(job.source).resolve(lease,generation,kind(job.source),chunk,tenant);
            if(owners.unknown()!=0||!owners.ownedIds().isEmpty()||!owners.ownedKeys().isEmpty())throw incomplete();
            for(var shared:owners.shared())createAudit(source(namespace(job.source),shared.kind(),shared.key()));
        }
        int offset=job.offset+consumed;boolean frameDone=offset==job.frame.items();
        boolean finished=frameDone&&job.frame.finished();
        var next=frameDone?new AuditJob(job.source,job.frame.nextCursor(),null,0):new AuditJob(job.source,job.cursor,job.frame,offset);
        journal.commit(lease,row,row.cursor(),next.encode(),finished,p.fingerprint,consumed,0,0);
        return false;
    }
    private void createAudit(JdbcPurgeScanJournal.Source source){
        String id="audit-source-"+source.token();
        var row=journal.create(lease,id,"",new AuditJob(source,"0",null,0).encode(),p.fingerprint);
        if(!p.fingerprint.equals(row.evidence()))throw incomplete();
    }
    private PurgeScanFrame capture(JdbcPurgeScanJournal.Source source,String cursor,boolean audit){
        if(source.kind()==JdbcPurgeScanJournal.Kind.STREAM){
            var route=queue(source);String[] snapshot=source.locator().split("\\|",-1);
            var frame=route.eraser().scan(lease,generation,new RedisTenantQueueEraser.Snapshot(snapshot[1],snapshot[2]),cursor.equals("0")?"0-0":cursor);
            return new PurgeScanFrame(cursor,frame.next(),frame.finished(),frame.entries().size(),ProtectionKey.hash(snapshot[2]),frame.encoded());
        }
        var scanner=scanner(source);
        String topologyId=(audit?"audit-topology-":"inventory-topology-")+ProtectionKey.hash(namespace(source));
        var prior=journal.get(lease,topologyId);
        String topology=prior==null?scanner.captureTopology():prior.evidence();
        if(prior==null)journal.create(lease,topologyId,"",new byte[0],topology);
        String sourceKey=source.locator().substring(source.locator().indexOf('|')+1);
        var frame=scanner.scan(lease,generation,kind(source),sourceKey,cursor,topology);
        return new PurgeScanFrame(cursor,frame.nextCursor(),frame.last(),frame.items().size(),topology,frame.wire());
    }
    private RedisPurgeScanner.Frame decode(JdbcPurgeScanJournal.Source source,PurgeScanFrame frame){
        return RedisPurgeScanner.decode(kind(source),frame.inputCursor(),
                BoundedRedisWire.decode(frame.encoded(),BoundedRedisWire.Limits.scan()),frame.topology());
    }
    private RedisTenantPurgePlan.QueueRoute queue(JdbcPurgeScanJournal.Source source){
        String[] parts=source.locator().split("\\|",-1);
        if(parts.length!=3||!parts[2].matches("[0-9a-f]{40}"))throw incomplete();
        var q=queues.get(parts[0]);if(q==null)throw incomplete();return q;
    }
    private static JdbcPurgeScanJournal.Source source(String ns,RedisPurgeScanner.Kind kind,String key){
        String locator=ns+"|"+key;
        return new JdbcPurgeScanJournal.Source(ProtectionKey.hash(kind+":"+locator),JdbcPurgeScanJournal.Kind.valueOf(kind.name()),locator);
    }
    private RedisPurgeScanner scanner(JdbcPurgeScanJournal.Source source){
        var result=scanners.get(namespace(source));if(result==null)throw incomplete();return result;
    }
    private static String namespace(JdbcPurgeScanJournal.Source source){
        int separator=source.locator().indexOf('|');if(separator<1)throw incomplete();return source.locator().substring(0,separator);
    }
    private static RedisPurgeScanner.Kind kind(JdbcPurgeScanJournal.Source source){return RedisPurgeScanner.Kind.valueOf(source.kind().name());}
    private static void put(Map<String,PurgeWorkItem> work,PurgeWorkItem value){
        var prior=work.putIfAbsent(value.token(),value);
        if(prior!=null&&(prior.kind()!=value.kind()||!Arrays.equals(prior.locator(),value.locator())))throw incomplete();
    }
    private static ProtectionException incomplete(){return new ProtectionException(ProtectionException.Code.INCOMPLETE_INVENTORY);}

    /** Bounded persistent audit reply: never truncate a SCAN reply then advance its cursor. */
    private record AuditJob(JdbcPurgeScanJournal.Source source,String cursor,PurgeScanFrame frame,int offset){
        byte[] encode(){
            try{
                var buffer=new ByteArrayOutputStream(4096);var out=new DataOutputStream(buffer);
                out.writeUTF(source.token());out.writeUTF(source.kind().name());out.writeUTF(source.locator());out.writeUTF(cursor);
                out.writeInt(offset);out.writeBoolean(frame!=null);
                if(frame!=null){
                    byte[] bytes=frame.encoded();if(bytes.length>1040000)throw incomplete();
                    out.writeUTF(frame.inputCursor());out.writeUTF(frame.nextCursor());out.writeBoolean(frame.finished());
                    out.writeInt(frame.items());out.writeUTF(frame.topology());out.writeInt(bytes.length);out.write(bytes);
                }
                out.flush();if(buffer.size()>1048576)throw incomplete();return buffer.toByteArray();
            }catch(IOException invalid){throw incomplete();}
        }
        static AuditJob decode(byte[] bytes){
            if(bytes.length>1048576)throw incomplete();
            try(var in=new DataInputStream(new ByteArrayInputStream(bytes))){
                var source=new JdbcPurgeScanJournal.Source(in.readUTF(),JdbcPurgeScanJournal.Kind.valueOf(in.readUTF()),in.readUTF());
                String cursor=in.readUTF();int offset=in.readInt();PurgeScanFrame frame=null;
                if(in.readBoolean()){
                    String input=in.readUTF(),next=in.readUTF();boolean finished=in.readBoolean();
                    int count=in.readInt();String topology=in.readUTF();int size=in.readInt();
                    if(size<0||size>1040000||size>in.available())throw incomplete();
                    frame=new PurgeScanFrame(input,next,finished,count,topology,in.readNBytes(size));
                }
                if(in.available()!=0||offset<0||frame==null&&offset!=0||frame!=null&&offset>frame.items())throw incomplete();
                return new AuditJob(source,cursor,frame,offset);
            }catch(IOException|IllegalArgumentException invalid){throw incomplete();}
        }
    }
}
