package com.reactor.cachedb.jdbc;

import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.Lease;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

/** Purge-only durable pending frames/worklist. NO normal-write or foreground reservation API. */
public final class JdbcPurgeScanJournal {
    public enum Kind { OWNER,NAMESPACE,SET,ZSET,LIST,STREAM }
    public record Source(String token,Kind kind,String locator) {
        public Source {
            ProtectionKey.token(token);
            if(kind==null || locator==null || locator.length()>1024 || locator.getBytes(StandardCharsets.UTF_8).length>1024)
                throw new IllegalArgumentException("Invalid purge scan source");
        }
        @Override public String toString(){return "PurgeScanSource[redacted]";}
    }
    public record Pending(Source source,String cursor,UUID pageId,PurgeScanFrame frame,int offset,boolean complete,boolean failed) {
        @Override public String toString(){return "PurgeScanPending[redacted]";}
    }
    private final JdbcTenantPurgeGuard guard;
    private com.reactor.cachedb.core.protection.InitialTenantBootstrap.Session initialPurpose;
    private final JdbcTenantPurgeDialect dialect;
    private final int timeout,maxSources,maxWorkItems,maxWorkBytes;
    public JdbcPurgeScanJournal(JdbcTenantPurgeGuard guard,JdbcTenantPurgeDialect dialect,int timeout,
            int maxSources,int maxWorkItems,int maxWorkBytes) {
        if(timeout<1 || timeout>5 || maxSources<1 || maxSources>10000 || maxWorkItems<1 || maxWorkItems>1000000
                || maxWorkBytes<1024 || maxWorkBytes>100*1024*1024)throw new IllegalArgumentException("Invalid purge journal bounds");
        this.guard=Objects.requireNonNull(guard);this.dialect=Objects.requireNonNull(dialect);this.timeout=timeout;
        this.maxSources=maxSources;this.maxWorkItems=maxWorkItems;this.maxWorkBytes=maxWorkBytes;
    }
    public void register(Lease lease,List<Source> sources) {
        if(sources.size()>128)throw new IllegalArgumentException("At most 128 scan sources");
        purposeTransaction(lease,c->{register(c,lease,sources);return null;});
    }
    private void register(Connection c,Lease lease,List<Source> sources)throws SQLException {
        discoveryPhase(c,lease);
        if(sources.isEmpty())return;
        var requested=new LinkedHashMap<String,Source>();
        for(Source source:sources){Source prior=requested.putIfAbsent(source.token(),source);if(prior!=null&&!prior.equals(source))throw invalid();}
        var existing=new HashSet<String>();
        try(var q=prepare(c,"SELECT source_token,scan_kind,locator FROM cachedb_purge_scan WHERE "+where()
                +" AND source_token IN ("+String.join(",",Collections.nCopies(requested.size(),fixedToken()))+")")){
            bind(q,lease);int p=4;for(String token:requested.keySet())q.setString(p++,token);q.setFetchSize(128);
            try(var r=q.executeQuery()){while(r.next()){
                Source expected=requested.get(r.getString(1));
                if(expected==null || !expected.kind().name().equals(r.getString(2)) || !expected.locator().equals(text(r,3,1024)))throw invalid();
                existing.add(expected.token());
            }}
        }
        var missing=requested.values().stream().filter(x->!existing.contains(x.token())).toList();
        if(count(c,lease,"cachedb_purge_scan")+missing.size()>maxSources)
            throw new ProtectionException(ProtectionException.Code.INCOMPLETE_INVENTORY);
        try(var q=prepare(c,"INSERT INTO cachedb_purge_scan(scope_token,tenant_token,operation_id,source_token,"
                +"scan_kind,locator,scan_cursor,pending_offset,item_count,is_complete,is_failed) VALUES ("+fixedToken()+","+fixedToken()+","+ascii(36)+","+fixedToken()+","+ascii(24)+",?,'0',0,0,0,0)")){
            for(Source source:missing){
                bind(q,lease);q.setString(4,source.token());q.setString(5,source.kind().name());
                binary(q,6,source.locator().getBytes(StandardCharsets.UTF_8));q.addBatch();
            }
            if(!missing.isEmpty())batch(q.executeBatch(),missing.size());
        }
    }

    public Pending next(Lease lease) {
        return purposeTransaction(lease,c->{
            String sql=limited("source_token,scan_kind,locator,scan_cursor,pending_id,pending_frame,next_cursor,"
                    +"item_count,pending_offset,is_complete,is_failed,topology_token,frame_finished","cachedb_purge_scan",
                    where()+" AND (is_complete=0 OR is_failed=1)","source_token",1);
            try(var q=prepare(c,sql)){bind(q,lease);try(var r=q.executeQuery()){return r.next()?pending(r):null;}}
        });
    }
    public void stage(Lease lease,Source source,PurgeScanFrame frame) {
        purposeTransaction(lease,c->{
            discoveryPhase(c,lease);
            try(var q=prepare(c,"SELECT scan_cursor,pending_id,is_complete,is_failed FROM cachedb_purge_scan WHERE "+where()+" AND source_token="+fixedToken()+"")){
                bind(q,lease);q.setString(4,source.token());
                try(var r=q.executeQuery()){
                    if(!r.next() || !frame.inputCursor().equals(r.getString(1)) || r.getString(2)!=null || r.getInt(3)!=0 || r.getInt(4)!=0)throw invalid();
                }
            }
            try(var q=prepare(c,"UPDATE cachedb_purge_scan SET pending_id="+ascii(36)+",pending_frame=?,next_cursor="+ascii(20)+",item_count=?,"
                    +"pending_offset=0,topology_token="+fixedToken()+",frame_finished=? WHERE "+where()+" AND source_token="+fixedToken()+"")){
                q.setString(1,UUID.randomUUID().toString());binary(q,2,frame.encoded());q.setString(3,frame.nextCursor());
                q.setInt(4,frame.items());q.setString(5,frame.topology());q.setInt(6,frame.finished()?1:0);
                bind(q,7,lease);q.setString(10,source.token());one(q.executeUpdate());
            }
            return null;
        });
    }
    /** Append work and consume pending offset in ONE transaction; cursor advances only at full reply end. */
    public void consume(Lease lease,Pending expected,int consumed,List<PurgeWorkItem> owned,List<Source> spawned,int unknown) {
        consume(lease,expected,consumed,owned,spawned,unknown,0,false);
    }
    public void consumeVersionInventory(Lease lease,Pending expected,int consumed,List<PurgeWorkItem> owned,
            List<Source> spawned,int unknown,long observedMaximum) {
        consume(lease,expected,consumed,owned,spawned,unknown,observedMaximum,true);
    }
    private void consume(Lease lease,Pending expected,int consumed,List<PurgeWorkItem> owned,List<Source> spawned,
            int unknown,long observedMaximum,boolean inventory) {
        if(consumed<0 || consumed>128 || owned.size()>128 || spawned.size()>128 || unknown<0 || unknown>consumed)
            throw new IllegalArgumentException("Invalid scan chunk");
        purposeTransaction(lease,c->{
            discoveryPhase(c,lease);
            Pending current;
            try(var q=prepare(c,"SELECT source_token,scan_kind,locator,scan_cursor,pending_id,pending_frame,next_cursor,"
                    +"item_count,pending_offset,is_complete,is_failed,topology_token,frame_finished FROM cachedb_purge_scan WHERE "+where()+" AND source_token="+fixedToken()+"")){
                bind(q,lease);q.setString(4,expected.source().token());try(var r=q.executeQuery()){if(!r.next())throw invalid();current=pending(r);}
            }
            if(current.frame()==null || !Objects.equals(current.pageId(),expected.pageId()) || current.offset()!=expected.offset()
                    || consumed>current.frame().items()-current.offset() || consumed==0 && current.frame().items()!=0)throw invalid();
            append(c,lease,owned);register(c,lease,spawned);
            if(inventory)JdbcTenantVersionAllocator.observeRedisMaximum(c,lease,observedMaximum,timeout,dialect);
            int offset=current.offset()+consumed;boolean done=offset==current.frame().items();
            String sql=done
                    ?"UPDATE cachedb_purge_scan SET scan_cursor=next_cursor,pending_id=NULL,pending_frame=NULL,next_cursor=NULL,"
                      +"item_count=0,pending_offset=0,is_complete=frame_finished,is_failed=?,consumed_total=consumed_total+?,inventory_total=inventory_total+? WHERE "+where()+" AND source_token="+fixedToken()+""
                    :"UPDATE cachedb_purge_scan SET pending_offset=?,is_failed=?,consumed_total=consumed_total+?,inventory_total=inventory_total+? WHERE "+where()+" AND source_token="+fixedToken()+"";
            try(var q=prepare(c,sql)){
                int p=1;if(!done)q.setInt(p++,offset);q.setInt(p++,current.failed() || unknown>0?1:0);q.setInt(p++,consumed);q.setInt(p++,inventory?consumed:0);bind(q,p,lease);q.setString(p+3,current.source().token());one(q.executeUpdate());
            }
            return null;
        });
    }
    public boolean discoveryComplete(Lease lease) {
        return purposeTransaction(lease,c->{
            if(count(c,lease,"cachedb_purge_scan")==0)return false;
            try(var q=prepare(c,"SELECT COUNT(*) FROM cachedb_purge_scan WHERE "+where()+" AND (is_complete=0 OR is_failed=1)")){
                bind(q,lease);try(var r=q.executeQuery()){r.next();return r.getLong(1)==0;}
            }
        });
    }
    private void append(Connection c,Lease lease,List<PurgeWorkItem> items)throws SQLException {
        long bytes=0;for(var item:items)bytes+=item.locator().length;
        if(bytes>1024*1024)throw new ProtectionException(ProtectionException.Code.INVALID_REQUEST);
        if(items.isEmpty())return;
        var requested=new LinkedHashMap<String,PurgeWorkItem>();
        for(var item:items){var prior=requested.putIfAbsent(item.token(),item);if(prior!=null&&!prior.equals(item))throw invalid();}
        var existing=new HashSet<String>();
        try(var q=prepare(c,"SELECT work_token,work_kind,locator,payload_bytes FROM cachedb_purge_work WHERE "+where()
                +" AND work_token IN ("+String.join(",",Collections.nCopies(requested.size(),fixedToken()))+")")){
            bind(q,lease);int p=4;for(String token:requested.keySet())q.setString(p++,token);q.setFetchSize(1);
            try(var r=q.executeQuery()){while(r.next()){
                var expected=requested.get(r.getString(1));
                if(expected==null || !expected.kind().name().equals(r.getString(2))
                        || expected.locator().length!=r.getInt(4) || !Arrays.equals(expected.locator(),bytes(r,3,1024*1024)))throw invalid();
                existing.add(expected.token());
            }}
        }
        var missing=requested.values().stream().filter(x->!existing.contains(x.token())).toList();
        if(missing.isEmpty())return;
        long addedBytes=0;for(var item:missing)addedBytes=Math.addExact(addedBytes,item.locator().length);
        long count=0,total=0;boolean known=false;
        try(var q=prepare(c,"SELECT work_count,work_bytes FROM cachedb_purge_work_budget WHERE "+where())){
            bind(q,lease);try(var r=q.executeQuery()){if(r.next()){count=r.getLong(1);total=r.getLong(2);known=true;}}
        }
        if(count<0 || total<0 || count+missing.size()>maxWorkItems || total+addedBytes>maxWorkBytes)
            throw new ProtectionException(ProtectionException.Code.INCOMPLETE_INVENTORY);
        if(!known){
            try(var q=prepare(c,"INSERT INTO cachedb_purge_work_budget(scope_token,tenant_token,operation_id,work_count,work_bytes) VALUES ("+fixedToken()+","+fixedToken()+","+ascii(36)+",0,0)")){
                bind(q,lease);one(q.executeUpdate());
            }
        }
        try(var q=prepare(c,"UPDATE cachedb_purge_work_budget SET work_count=?,work_bytes=? WHERE "+where())){
            q.setLong(1,Math.addExact(count,missing.size()));q.setLong(2,Math.addExact(total,addedBytes));bind(q,3,lease);one(q.executeUpdate());
        }
        try(var q=prepare(c,"INSERT INTO cachedb_purge_work(scope_token,tenant_token,operation_id,work_token,work_kind,locator,payload_bytes)"
                +" VALUES ("+fixedToken()+","+fixedToken()+","+ascii(36)+","+fixedToken()+","+ascii(24)+",?,?)")){
            for(var item:missing){
                bind(q,lease);q.setString(4,item.token());q.setString(5,item.kind().name());byte[] value=item.locator();
                binary(q,6,value);q.setInt(7,value.length);q.addBatch();
            }
            if(!missing.isEmpty())batch(q.executeBatch(),missing.size());
        }
    }

    /** Descriptor preflight caps aggregate LOB input BEFORE fetching it. No unbounded LOB prefetch. */
    public List<PurgeWorkItem> page(Lease lease,String after,int limit,int maxBytes) {
        return page(lease,null,after,limit,maxBytes);
    }
    /** Typed sealed substream; no KEY/ENTRY is silently skipped by the identity participant. */
    public List<PurgeWorkItem> page(Lease lease,PurgeWorkItem.Kind kind,String after,int limit,int maxBytes) {
        if(limit<1 || limit>128 || maxBytes<1024 || maxBytes>1024*1024)throw new IllegalArgumentException("Invalid work page");
        if(after!=null)ProtectionKey.token(after);
        return purposeTransaction(lease,c->{
            requireWorkPhase(c,lease);
            if(!discoveryCompleteIn(c,lease))throw new ProtectionException(ProtectionException.Code.INCOMPLETE_INVENTORY);
            var ids=new ArrayList<String>();int total=0;
            String sql=limited("work_token,payload_bytes","cachedb_purge_work",where()+(kind==null?"":" AND work_kind='"+kind.name()+"'")+(after==null?"":" AND work_token>"+fixedToken()+""),"work_token",limit);
            try(var q=prepare(c,sql)){bind(q,lease);if(after!=null)q.setString(4,after);
                try(var r=q.executeQuery()){while(r.next()){int bytes=r.getInt(2);if(bytes<1 || bytes>maxBytes)throw invalid();
                    if(total+bytes>maxBytes)break;ids.add(r.getString(1));total+=bytes;}}
            }
            if(ids.isEmpty())return List.of();
            var result=new ArrayList<PurgeWorkItem>();
            try(var q=prepare(c,"SELECT work_token,work_kind,locator FROM cachedb_purge_work WHERE "+where()
                    +" AND work_token IN ("+String.join(",",Collections.nCopies(ids.size(),fixedToken()))+") ORDER BY work_token")){
                bind(q,lease);int i=4;for(String id:ids)q.setString(i++,id);q.setFetchSize(1);
                try(var r=q.executeQuery()){while(r.next())result.add(new PurgeWorkItem(r.getString(1),PurgeWorkItem.Kind.valueOf(r.getString(2)),bytes(r,3,maxBytes)));}
            }
            if(result.size()!=ids.size())throw invalid();return List.copyOf(result);
        });
    }
    /** Dispose temporary raw IDs/queue payloads/scan locators after verified receipt GC; <=128/transaction. */
    public int dispose(Lease lease,int limit){
        if(initialPurpose!=null)throw invalid();
        if(limit<1||limit>128)throw invalid();
        return purposeTransaction(lease,c->{
            try(var q=prepare(c,"SELECT payload FROM cachedb_purge_progress WHERE scope_token=? AND tenant_token=?")){
                q.setString(1,lease.key().scopeToken());q.setString(2,lease.key().tenantToken());
                try(var r=q.executeQuery()){if(!r.next()||PurgeProgressCodec.decode(bytes(r,1,32768)).stage()!=TenantPurgeProtocol.Stage.VERIFYING)throw invalid();}
            }
            for(String table:List.of("cachedb_purge_work","cachedb_purge_scan")){
                String column=table.equals("cachedb_purge_work")?"work_token":"source_token";
                var tokens=new ArrayList<String>();
                try(var q=prepare(c,limited(column,table,where(),column,limit))){
                    bind(q,lease);try(var r=q.executeQuery()){while(r.next())tokens.add(r.getString(1));}
                }
                if(tokens.isEmpty())continue;
                try(var q=prepare(c,"DELETE FROM "+table+" WHERE "+where()+" AND "+column+" IN ("
                        +String.join(",",Collections.nCopies(tokens.size(),fixedToken()))+")")){
                    bind(q,lease);int i=4;for(String token:tokens)q.setString(i++,token);
                    int n=q.executeUpdate();if(n!=tokens.size())throw invalid();return n;
                }
            }
            return 0;
        });
    }
    private boolean discoveryCompleteIn(Connection c,Lease lease)throws SQLException{
        if(count(c,lease,"cachedb_purge_scan")==0)return false;
        try(var q=prepare(c,"SELECT COUNT(*) FROM cachedb_purge_scan WHERE "+where()+" AND (is_complete=0 OR is_failed=1)")){
            bind(q,lease);try(var r=q.executeQuery()){r.next();return r.getLong(1)==0;}}
    }
    private void requireWorkPhase(Connection c,Lease lease)throws SQLException{
        if(initialPurpose!=null)throw invalid(); // INITIAL inventories never authorize erasure.
        try(var q=prepare(c,"SELECT payload FROM cachedb_purge_progress WHERE scope_token=? AND tenant_token=?")){
            q.setString(1,lease.key().scopeToken());q.setString(2,lease.key().tenantToken());
            try(var r=q.executeQuery()){
                if(!r.next())throw invalid();
                var stage=PurgeProgressCodec.decode(bytes(r,1,32768)).stage();
                if(stage!=TenantPurgeProtocol.Stage.REDIS&&stage!=TenantPurgeProtocol.Stage.SOURCE&&stage!=TenantPurgeProtocol.Stage.VERIFYING)throw invalid();
            }
        }
    }
    private void discoveryPhase(Connection c,Lease lease)throws SQLException{
        if(initialPurpose!=null){
            try(var q=prepare(c,"SELECT phase_name FROM cachedb_initial_bootstrap WHERE "+InitialSqlTokens.pair(dialect,false)+"")){
                q.setString(1,lease.key().scopeToken());q.setString(2,lease.key().tenantToken());
                try(var r=q.executeQuery()){
                    if(!r.next()||!"NATIVE_INVENTORY".equals(r.getString(1)))throw invalid();
                }
            }
            return;
        }
        try(var q=prepare(c,"SELECT payload FROM cachedb_purge_progress WHERE scope_token=? AND tenant_token=?")){
            q.setString(1,lease.key().scopeToken());q.setString(2,lease.key().tenantToken());
            try(var r=q.executeQuery()){
                if(!r.next() || PurgeProgressCodec.decode(bytes(r,1,32768)).stage()!=TenantPurgeProtocol.Stage.FENCING)throw invalid();
            }
        }
    }
    private Pending pending(ResultSet r)throws SQLException {
        Source source=new Source(r.getString(1),Kind.valueOf(r.getString(2)),text(r,3,1024));
        String page=r.getString(5);PurgeScanFrame frame=null;
        if(page!=null)frame=new PurgeScanFrame(r.getString(4),r.getString(7),r.getInt(13)!=0,r.getInt(8),r.getString(12),bytes(r,6,1024*1024));
        return new Pending(source,r.getString(4),page==null?null:UUID.fromString(page),frame,r.getInt(9),r.getInt(10)!=0,r.getInt(11)!=0);
    }
    private String limited(String columns,String table,String predicate,String order,int limit){
        return "SELECT "+(dialect==JdbcTenantPurgeDialect.MSSQL?"TOP ("+limit+") ":"")+columns+" FROM "+table+" WHERE "+predicate+" ORDER BY "+order
                +(dialect==JdbcTenantPurgeDialect.POSTGRES?" LIMIT "+limit:dialect==JdbcTenantPurgeDialect.ORACLE?" FETCH FIRST "+limit+" ROWS ONLY":"");
    }
    private long count(Connection c,Lease lease,String table)throws SQLException{
        try(var q=prepare(c,"SELECT COUNT(*) FROM "+table+" WHERE "+where())){bind(q,lease);try(var r=q.executeQuery()){r.next();return r.getLong(1);}}
    }
    private PreparedStatement prepare(Connection c,String sql)throws SQLException{var q=c.prepareStatement(sql);try{q.setQueryTimeout(timeout);return q;}catch(SQLException e){q.close();throw e;}}
    private String ascii(int width){return initialPurpose==null?"?":InitialSqlTokens.varchar(dialect,width);}
    private String fixedToken(){return initialPurpose==null?"?":InitialSqlTokens.char64(dialect);}
    private String where(){return "scope_token="+fixedToken()+" AND tenant_token="+fixedToken()+" AND operation_id="+ascii(36);}
    private static void bind(PreparedStatement q,Lease lease)throws SQLException{bind(q,1,lease);}
    private static void bind(PreparedStatement q,int start,Lease lease)throws SQLException{
        q.setString(start,lease.key().scopeToken());q.setString(start+1,lease.key().tenantToken());q.setString(start+2,lease.operationId().toString());
    }
    private static void binary(PreparedStatement q,int index,byte[] value)throws SQLException{
        // Already byte-bounded; retain batch bytes instead of one-shot streams reused by error rendering.
        q.setBytes(index,value);
    }
    private static byte[] bytes(ResultSet r,int index,int max)throws SQLException{
        try(var input=r.getBinaryStream(index)){if(input==null)throw invalid();byte[] value=input.readNBytes(max+1);if(value.length>max)throw invalid();return value;}
        catch(IOException invalid){throw new ProtectionException(ProtectionException.Code.INVALID_PROGRESS);}
    }
    private static String text(ResultSet r,int index,int max)throws SQLException{
        byte[] value=bytes(r,index,max);
        try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(value)).toString();}
        catch(java.nio.charset.CharacterCodingException invalid){throw invalid();}
    }
    private static void batch(int[] counts,int expected){
        if(counts.length!=expected)throw invalid();
        for(int n:counts)if(n!=1 && n!=Statement.SUCCESS_NO_INFO)throw invalid();
    }
    private static void one(int n){if(n!=1)throw invalid();}
    private static ProtectionException invalid(){return new ProtectionException(ProtectionException.Code.INVALID_PROGRESS);}
    /** Borrowed INITIAL-purpose view: same real guard/pool/bounds, no synthetic purge progress. */
    public JdbcPurgeScanJournal forInitial(com.reactor.cachedb.core.protection.InitialTenantBootstrap.Session session) {
        var view=new JdbcPurgeScanJournal(guard,dialect,timeout,maxSources,maxWorkItems,maxWorkBytes);view.initialPurpose=Objects.requireNonNull(session);return view;
    }
    private <T>T purposeTransaction(Lease lease,JdbcTenantPurgeGuard.PurgeSqlWork<T> work) {
        if(initialPurpose==null)return guard.inPurgeTransaction(lease,work);
        if(!initialPurpose.lease().equals(lease))throw new ProtectionException(ProtectionException.Code.LEASE_LOST);
        return guard.inInitialBootstrapTransaction(initialPurpose,work);
    }

}
