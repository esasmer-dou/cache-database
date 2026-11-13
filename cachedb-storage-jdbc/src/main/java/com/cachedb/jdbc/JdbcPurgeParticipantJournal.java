package com.reactor.cachedb.jdbc;

import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.*;
import java.io.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.Semaphore;

/**
 * Administrative participant checkpoint/result and receipt counters in ONE guard-locked SQL commit.
 * Native Redis I/O runs outside SQL transactions. No foreground identity reservation or source DML.
 */
public final class JdbcPurgeParticipantJournal {
    public record Checkpoint(long revision,PurgeParticipant.State state,String cursor,long examined,long removed,String evidence) {}
    public record Applied(Checkpoint checkpoint,Progress progress) {}
    private final JdbcTenantPurgeGuard guard;private final int timeout;private final Semaphore controls;
    public JdbcPurgeParticipantJournal(JdbcTenantPurgeGuard guard,int timeout,int maxConcurrentSteps){
        this.guard=Objects.requireNonNull(guard);
        if(timeout<1||timeout>5||maxConcurrentSteps<1||maxConcurrentSteps>64)throw invalid();
        this.timeout=timeout;controls=new Semaphore(maxConcurrentSteps,true);
    }
    /** Retry the same durable cursor after a lost Redis response or SQL commit failure. */
    public Applied step(Lease lease,long generation,PurgeParticipant participant,PurgeParticipant.Phase phase,int maxBytes){
        if(maxBytes<1024||maxBytes>1048576||generation<1)throw invalid();
        if(!controls.tryAcquire())throw new ProtectionException(ProtectionException.Code.BUSY);
        try{
            var declaration=participant.declaration();
            Checkpoint before=guard.inPurgeTransaction(lease,c->{
                requireProgress(c,lease,generation,phase);
                var current=read(c,lease,declaration,phase);
                if(current!=null)return current;
                try(var q=prepare(c,"SELECT COUNT(*) FROM cachedb_purge_participant WHERE "+where())){
                    bind(q,lease);try(var r=q.executeQuery()){r.next();if(r.getLong(1)>=256)throw invalid();}
                }
                try(var q=prepare(c,"INSERT INTO cachedb_purge_participant(scope_token,tenant_token,operation_id,"
                        +"participant_id,phase_name,declaration_token,revision,step_state,examined_count,removed_count)"
                        +" VALUES (?,?,?,?,?,?,0,'MORE',0,0)")){
                    bind(q,lease);q.setString(4,declaration.id());q.setString(5,phase.name());q.setString(6,declaration.ownershipFingerprint());
                    one(q.executeUpdate());
                }
                return new Checkpoint(0,PurgeParticipant.State.MORE,"",0,0,"");
            });
            guard.renew(lease);
            // Never hold a JDBC connection/row lock or Java monitor during this native Redis operation.
            var result=participant.step(new PurgeParticipant.Request(lease,generation,phase,before.cursor(),128,maxBytes));
            if(result.state()==PurgeParticipant.State.INCOMPLETE
                    && (result.examined()!=0||result.removed()!=0||!result.checkpoint().equals(before.cursor())))throw invalid();
            if(result.state()==PurgeParticipant.State.MORE && result.checkpoint().equals(before.cursor()))throw invalid();
            opaque(result.checkpoint());
            return guard.inPurgeTransaction(lease,c->{
                Progress progress=requireProgress(c,lease,generation,phase);
                var current=read(c,lease,declaration,phase);
                if(!before.equals(current))throw new ProtectionException(ProtectionException.Code.OPERATION_CONFLICT);
                var next=new Checkpoint(Math.addExact(before.revision(),1),result.state(),result.checkpoint(),
                        Math.addExact(before.examined(),result.examined()),Math.addExact(before.removed(),result.removed()),
                        result.evidence()==null?"":result.evidence());
                try(var q=prepare(c,"UPDATE cachedb_purge_participant SET revision=?,step_state=?,checkpoint_value=?,"
                        +"examined_count=?,removed_count=?,evidence_token=? WHERE "+where()+" AND participant_id=? AND phase_name=? AND revision=?")){
                    q.setLong(1,next.revision());q.setString(2,next.state().name());q.setString(3,next.cursor());
                    q.setLong(4,next.examined());q.setLong(5,next.removed());q.setString(6,next.evidence());
                    bind(q,7,lease);q.setString(10,declaration.id());q.setString(11,phase.name());q.setLong(12,before.revision());one(q.executeUpdate());
                }
                boolean nativeErase=phase==PurgeParticipant.Phase.ERASE&&declaration.id().equals("native-redis-identities");
                // Only the native identity participant's removed count means physical Redis keys.
                long rows=nativeErase?Math.addExact(progress.redisRows(),result.examined()):progress.redisRows();
                long keys=nativeErase?Math.addExact(progress.redisKeys(),result.removed()):progress.redisKeys();
                var after=progress.advance(progress.stage(),progress.inventoryToken(),progress.cursor(),null,0,
                        rows,keys,progress.sourceRows(),0,null);
                byte[] encoded=PurgeProgressCodec.encode(after);
                try(var q=prepare(c,"UPDATE cachedb_purge_progress SET payload=? WHERE scope_token=? AND tenant_token=?")){
                    q.setBinaryStream(1,new ByteArrayInputStream(encoded),encoded.length);
                    q.setString(2,lease.key().scopeToken());q.setString(3,lease.key().tenantToken());one(q.executeUpdate());
                }
                return new Applied(next,after);
            });
        }finally{controls.release();}
    }
    public Checkpoint load(Lease lease,PurgeParticipant participant,PurgeParticipant.Phase phase){
        return guard.inPurgeTransaction(lease,c->read(c,lease,participant.declaration(),phase));
    }
    private Progress requireProgress(Connection c,Lease lease,long generation,PurgeParticipant.Phase phase)throws SQLException{
        try(var q=prepare(c,"SELECT payload FROM cachedb_purge_progress WHERE scope_token=? AND tenant_token=?")){
            q.setString(1,lease.key().scopeToken());q.setString(2,lease.key().tenantToken());
            try(var r=q.executeQuery()){
                if(!r.next())throw invalid();
                try(var input=r.getBinaryStream(1)){
                    if(input==null)throw invalid();var progress=PurgeProgressCodec.decode(input.readNBytes(PurgeProgressCodec.MAX_BYTES+1));
                    if(!progress.operationId().equals(lease.operationId())||progress.closedGeneration()!=generation
                            ||!allowed(progress.stage(),phase)||progress.pending()!=null)throw invalid();
                    return progress;
                }catch(IOException failure){throw invalid();}
            }
        }
    }
    private static boolean allowed(Stage stage,PurgeParticipant.Phase phase){
        return stage==Stage.REDIS||stage==Stage.VERIFYING&&phase==PurgeParticipant.Phase.VERIFY
                ||stage==Stage.FENCING&&(phase==PurgeParticipant.Phase.CLOSE||phase==PurgeParticipant.Phase.DISCOVER);
    }
    private Checkpoint read(Connection c,Lease lease,PurgeParticipant.Declaration declaration,PurgeParticipant.Phase phase)throws SQLException{
        try(var q=prepare(c,"SELECT declaration_token,revision,step_state,checkpoint_value,examined_count,removed_count,evidence_token"
                +" FROM cachedb_purge_participant WHERE "+where()+" AND participant_id=? AND phase_name=?")){
            bind(q,lease);q.setString(4,declaration.id());q.setString(5,phase.name());q.setMaxRows(1);
            try(var r=q.executeQuery()){
                if(!r.next())return null;
                if(!declaration.ownershipFingerprint().equals(r.getString(1)))throw new ProtectionException(ProtectionException.Code.MIGRATION_REQUIRED);
                String cursor=Objects.requireNonNullElse(r.getString(4),"");opaque(cursor);
                String evidence=Objects.requireNonNullElse(r.getString(7),"");if(!evidence.isEmpty())ProtectionKey.token(evidence);
                long revision=r.getLong(2),examined=r.getLong(5),removed=r.getLong(6);
                if(revision<0||examined<0||removed<0)throw invalid();
                return new Checkpoint(revision,PurgeParticipant.State.valueOf(r.getString(3)),cursor,examined,removed,evidence);
            }
        }
    }
    private PreparedStatement prepare(Connection c,String sql)throws SQLException{
        var q=c.prepareStatement(sql);try{q.setQueryTimeout(timeout);return q;}catch(SQLException e){q.close();throw e;}
    }
    private static void opaque(String cursor){
        if(cursor==null||!cursor.matches("[A-Za-z0-9:._-]{0,1024}"))throw invalid();
    }
    private static String where(){return "scope_token=? AND tenant_token=? AND operation_id=?";}
    private static void bind(PreparedStatement q,Lease lease)throws SQLException{bind(q,1,lease);}
    private static void bind(PreparedStatement q,int i,Lease lease)throws SQLException{
        q.setString(i,lease.key().scopeToken());q.setString(i+1,lease.key().tenantToken());q.setString(i+2,lease.operationId().toString());
    }
    private static void one(int count){if(count!=1)throw invalid();}
    private static ProtectionException invalid(){return new ProtectionException(ProtectionException.Code.INVALID_PROGRESS);}
}
