package com.reactor.cachedb.jdbc;

import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.*;
import java.io.*;
import java.sql.*;
import java.util.*;

/**
 * Administrative bounded durable orchestrator checkpoints. Redis calls never run in these callbacks.
 * Revisions, physical-key counts and work offsets commit together under the durable tenant guard.
 */
public final class JdbcPurgeFacadeJournal {
    public static final int MAX_BYTES=1048576;
    public record Checkpoint(String id,long revision,String cursor,byte[] payload,boolean done,String evidence,
            long examined,long removed) {
        public Checkpoint {
            if(id==null||!id.matches("[a-z0-9._-]{1,96}")||revision<0||cursor==null||cursor.length()>1024
                    ||payload==null||payload.length>MAX_BYTES||examined<0||removed<0)throw invalid();
            if(evidence!=null)ProtectionKey.token(evidence);
            payload=payload.clone();
        }
        @Override public byte[] payload(){return payload.clone();}
        @Override public String toString(){return "PurgeCheckpoint[redacted]";}
    }
    private final JdbcTenantPurgeGuard guard;
    private com.reactor.cachedb.core.protection.InitialTenantBootstrap.Session initialPurpose;
    private final JdbcTenantPurgeDialect dialect;
    private final int timeout,maxRows;
    private final long maxBytes;
    public JdbcPurgeFacadeJournal(JdbcTenantPurgeGuard guard,JdbcTenantPurgeDialect dialect,int timeout,
            int maxRows,long maxBytes){
        this.guard=Objects.requireNonNull(guard);this.dialect=Objects.requireNonNull(dialect);
        if(timeout<1||timeout>5||maxRows<16||maxRows>10000||maxBytes<MAX_BYTES||maxBytes>104857600)throw invalid();
        this.timeout=timeout;this.maxRows=maxRows;this.maxBytes=maxBytes;
    }
    /** One physical administrative step per continuation, also across facade/process instances. */
    public void enterCall(Lease lease,UUID call){
        Objects.requireNonNull(call);
        purposeTransaction(lease,c->{
            boolean exists=false;
            try(var q=prepare(c,"SELECT lease_epoch,CASE WHEN call_until>"+dialect.now()+" THEN 1 ELSE 0 END"
                    +" FROM cachedb_purge_call WHERE "+where())){
                bind(q,lease);try(var r=q.executeQuery()){
                    if(r.next()){exists=true;if(r.getLong(1)==lease.epoch()&&r.getInt(2)==1)
                        throw new ProtectionException(ProtectionException.Code.BUSY);}
                }
            }
            String sql=exists?"UPDATE cachedb_purge_call SET lease_epoch=?,call_id="+ascii(36)+",call_until="+dialect.expires(lease.seconds())+" WHERE "+where()
                    :"INSERT INTO cachedb_purge_call(lease_epoch,call_id,scope_token,tenant_token,operation_id,call_until)"
                     +" VALUES (?,"+ascii(36)+","+ascii(64)+","+ascii(64)+","+ascii(36)+","+dialect.expires(lease.seconds())+")";
            try(var q=prepare(c,sql)){q.setLong(1,lease.epoch());q.setString(2,call.toString());bind(q,3,lease);one(q.executeUpdate());}
            return null;
        });
    }
    /** Called only after physical I/O exits; cannot clear a replacement epoch/call. */
    public void leaveCall(Lease lease,UUID call){
        try{purposeTransaction(lease,c->{
            try(var q=prepare(c,"DELETE FROM cachedb_purge_call WHERE "+where()+" AND lease_epoch=? AND call_id="+ascii(36)+"")){
                bind(q,lease);q.setLong(4,lease.epoch());q.setString(5,call.toString());q.executeUpdate();return null;
            }
        });}catch(ProtectionException failure){if(failure.code()!=ProtectionException.Code.LEASE_LOST)throw failure;}
    }
    public Checkpoint get(Lease lease,String id){
        return purposeTransaction(lease,c->read(c,lease,id));
    }
    public Checkpoint create(Lease lease,String id,String cursor,byte[] payload,String evidence){
        var initial=new Checkpoint(id,0,cursor,payload,false,evidence,0,0);
        return purposeTransaction(lease,c->{
            var prior=read(c,lease,id);if(prior!=null)return prior;
            budget(c,lease,payload.length,1);
            try(var q=prepare(c,"INSERT INTO cachedb_purge_facade(scope_token,tenant_token,operation_id,checkpoint_id,"
                    +"revision,cursor_value,payload,payload_bytes,is_done,evidence_token,examined_count,removed_count)"
                    +" VALUES ("+ascii(64)+","+ascii(64)+","+ascii(36)+","+ascii(96)+",0,?,?,?,0,"+ascii(64)+",0,0)")){
                bind(q,lease);q.setString(4,id);q.setString(5,cursor);binary(q,6,payload);
                q.setInt(7,payload.length);q.setString(8,evidence);one(q.executeUpdate());
            }
            return initial;
        });
    }
    /** Expected revision is sufficient: callers hold a fenced distributed lease, not a process mutex. */
    public Checkpoint commit(Lease lease,Checkpoint before,String cursor,byte[] payload,boolean done,
            String evidence,long examined,long removed,long physicalKeys){
        var next=new Checkpoint(before.id(),Math.addExact(before.revision(),1),cursor,payload,done,evidence,
                Math.addExact(before.examined(),examined),Math.addExact(before.removed(),removed));
        if(examined<0||examined>128||removed<0||physicalKeys<0||physicalKeys>1048576)throw invalid();
        return purposeTransaction(lease,c->{
            var actual=read(c,lease,before.id());
            if(actual==null||actual.revision()!=before.revision())throw conflict();
            budget(c,lease,payload.length-actual.payload().length,0);
            try(var q=prepare(c,"UPDATE cachedb_purge_facade SET revision=?,cursor_value=?,payload=?,payload_bytes=?,"
                    +"is_done=?,evidence_token="+ascii(64)+",examined_count=?,removed_count=? WHERE "+where()+" AND checkpoint_id="+ascii(96)+" AND revision=?")){
                q.setLong(1,next.revision());q.setString(2,cursor);binary(q,3,payload);q.setInt(4,payload.length);
                q.setInt(5,done?1:0);q.setString(6,evidence);q.setLong(7,next.examined());q.setLong(8,next.removed());
                bind(q,9,lease);q.setString(12,before.id());q.setLong(13,before.revision());one(q.executeUpdate());
            }
            if(initialPurpose!=null&&physicalKeys!=0)throw invalid();
            if(physicalKeys>0){
                Progress progress=progress(c,lease);
                if(progress.stage()!=Stage.REDIS)throw invalid();
                var updated=progress.advance(progress.stage(),progress.inventoryToken(),progress.cursor(),null,0,
                        progress.redisRows(),Math.addExact(progress.redisKeys(),physicalKeys),progress.sourceRows(),0,null);
                byte[] encoded=PurgeProgressCodec.encode(updated);
                try(var q=prepare(c,"UPDATE cachedb_purge_progress SET payload=? WHERE scope_token=? AND tenant_token=?")){
                    binary(q,1,encoded);q.setString(2,lease.key().scopeToken());q.setString(3,lease.key().tenantToken());one(q.executeUpdate());
                }
            }
            return next;
        });
    }
    /** First unfinished job; keyset operations are index-backed and <=1 row, never load the whole journal. */
    public Checkpoint next(Lease lease,String prefix){
        if(prefix==null||!prefix.matches("[a-z0-9._-]{1,30}"))throw invalid();
        return purposeTransaction(lease,c->{
            String sql="SELECT "+(dialect==JdbcTenantPurgeDialect.MSSQL?"TOP (1) ":"")+"checkpoint_id FROM cachedb_purge_facade"
                    +" WHERE "+where()+" AND checkpoint_id LIKE "+ascii(96)+" ESCAPE '!' AND is_done=0 ORDER BY checkpoint_id"
                    +(dialect==JdbcTenantPurgeDialect.POSTGRES?" LIMIT 1":dialect==JdbcTenantPurgeDialect.ORACLE?" FETCH FIRST 1 ROWS ONLY":"");
            // A '~' range end is not a portable prefix bound under database linguistic collations.
            try(var q=prepare(c,sql)){bind(q,lease);q.setString(4,prefix.replace("_","!_")+"%");
                try(var r=q.executeQuery()){return r.next()?read(c,lease,r.getString(1)):null;}}
        });
    }
    /** Temporary payloads/cursors may contain IDs. Delete <=128 rows/transaction only after certification. */
    public int dispose(Lease lease,int limit){
        if(initialPurpose!=null)throw invalid();
        if(limit<1||limit>128)throw invalid();
        return purposeTransaction(lease,c->{
            if(progress(c,lease).stage()!=Stage.VERIFYING)throw invalid();
            var ids=new ArrayList<String>();
            String sql="SELECT "+(dialect==JdbcTenantPurgeDialect.MSSQL?"TOP ("+limit+") ":"")+"checkpoint_id FROM cachedb_purge_facade WHERE "
                    +where()+" AND checkpoint_id NOT IN ('control','read-drain','accepted-history') ORDER BY checkpoint_id"
                    +(dialect==JdbcTenantPurgeDialect.POSTGRES?" LIMIT "+limit:dialect==JdbcTenantPurgeDialect.ORACLE?" FETCH FIRST "+limit+" ROWS ONLY":"");
            try(var q=prepare(c,sql)){bind(q,lease);try(var r=q.executeQuery()){while(r.next())ids.add(r.getString(1));}}
            if(ids.isEmpty())return 0;
            try(var q=prepare(c,"DELETE FROM cachedb_purge_facade WHERE "+where()+" AND checkpoint_id IN ("
                    +String.join(",",Collections.nCopies(ids.size(),ascii(96)))+")")){
                bind(q,lease);int i=4;for(String id:ids)q.setString(i++,id);int n=q.executeUpdate();if(n!=ids.size())throw invalid();return n;
            }
        });
    }
    private void budget(Connection c,Lease lease,long added,int rows)throws SQLException{
        try(var q=prepare(c,"SELECT COUNT(*),COALESCE(SUM(payload_bytes),0) FROM cachedb_purge_facade WHERE "+where())){
            bind(q,lease);try(var r=q.executeQuery()){r.next();if(r.getLong(1)+rows>maxRows||r.getLong(2)+added>maxBytes)
                throw new ProtectionException(ProtectionException.Code.INCOMPLETE_INVENTORY);}}
    }
    private Checkpoint read(Connection c,Lease lease,String id)throws SQLException{
        if(id==null||!id.matches("[a-z0-9._-]{1,96}"))throw invalid();
        // Scalar length is read BEFORE the LOB, also when a corrupt row/provider ignores prefetch settings.
        int payloadSize;
        try(var q=prepare(c,"SELECT payload_bytes FROM cachedb_purge_facade WHERE "+where()+" AND checkpoint_id="+ascii(96)+"")){
            bind(q,lease);q.setString(4,id);try(var r=q.executeQuery()){if(!r.next())return null;payloadSize=r.getInt(1);if(payloadSize<0||payloadSize>MAX_BYTES)throw invalid();}
        }
        try(var q=prepare(c,"SELECT revision,cursor_value,payload,is_done,evidence_token,examined_count,removed_count FROM cachedb_purge_facade WHERE "+where()+" AND checkpoint_id="+ascii(96)+"")){
            bind(q,lease);q.setString(4,id);q.setFetchSize(1);
            try(var r=q.executeQuery()){if(!r.next())throw invalid();return new Checkpoint(id,r.getLong(1),Objects.requireNonNullElse(r.getString(2),""),
                    payload(r,3,payloadSize),r.getInt(4)!=0,r.getString(5),r.getLong(6),r.getLong(7));}
        }
    }
    private Progress progress(Connection c,Lease lease)throws SQLException{
        try(var q=prepare(c,"SELECT payload FROM cachedb_purge_progress WHERE scope_token=? AND tenant_token=?")){
            q.setString(1,lease.key().scopeToken());q.setString(2,lease.key().tenantToken());
            try(var r=q.executeQuery()){if(!r.next())throw invalid();var p=PurgeProgressCodec.decode(bytes(r,1));if(!p.operationId().equals(lease.operationId()))throw conflict();return p;}
        }
    }
    private PreparedStatement prepare(Connection c,String sql)throws SQLException{
        var q=c.prepareStatement(sql);try{q.setQueryTimeout(timeout);return q;}catch(SQLException failure){q.close();throw failure;}
    }
    private String ascii(int width){return initialPurpose==null?"?":InitialSqlTokens.varchar(dialect,width);}
    private String where(){return "scope_token="+ascii(64)+" AND tenant_token="+ascii(64)+" AND operation_id="+ascii(36);}
    private static void bind(PreparedStatement q,Lease lease)throws SQLException{bind(q,1,lease);}
    private static void bind(PreparedStatement q,int i,Lease lease)throws SQLException{
        q.setString(i,lease.key().scopeToken());q.setString(i+1,lease.key().tenantToken());q.setString(i+2,lease.operationId().toString());
    }
    private static void binary(PreparedStatement q,int i,byte[] value)throws SQLException{
        // Oracle treats a zero-length binary stream as NULL; logical length remains in payload_bytes.
        q.setBytes(i,value.length==0?new byte[]{0}:value);
    }
    private static byte[] payload(ResultSet r,int i,int size)throws SQLException{
        byte[] value=bytes(r,i);
        if(size==0){
            if(value.length==0||value.length==1&&value[0]==0)return new byte[0];
            throw invalid();
        }
        if(value.length!=size)throw invalid();
        return value;
    }
    private static byte[] bytes(ResultSet r,int i)throws SQLException{
        try(var input=r.getBinaryStream(i)){if(input==null)throw invalid();byte[] value=input.readNBytes(MAX_BYTES+1);if(value.length>MAX_BYTES)throw invalid();return value;}
        catch(IOException failure){throw invalid();}
    }
    private static void one(int count){if(count!=1)throw conflict();}
    private static ProtectionException invalid(){return new ProtectionException(ProtectionException.Code.INVALID_PROGRESS);}
    private static ProtectionException conflict(){return new ProtectionException(ProtectionException.Code.OPERATION_CONFLICT);}
    /** Borrowed INITIAL-purpose view: same real guard/pool/bounds, no synthetic purge progress. */
    public JdbcPurgeFacadeJournal forInitial(com.reactor.cachedb.core.protection.InitialTenantBootstrap.Session session) {
        var view=new JdbcPurgeFacadeJournal(guard,dialect,timeout,maxRows,maxBytes);view.initialPurpose=Objects.requireNonNull(session);return view;
    }
    private <T>T purposeTransaction(Lease lease,JdbcTenantPurgeGuard.PurgeSqlWork<T> work) {
        if(initialPurpose==null)return guard.inPurgeTransaction(lease,work);
        if(!initialPurpose.lease().equals(lease))throw new ProtectionException(ProtectionException.Code.LEASE_LOST);
        return guard.inInitialBootstrapTransaction(initialPurpose,work);
    }

}
