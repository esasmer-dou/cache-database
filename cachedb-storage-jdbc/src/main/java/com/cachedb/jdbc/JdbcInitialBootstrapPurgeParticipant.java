package com.reactor.cachedb.jdbc;

import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.*;
import java.sql.*;
import java.util.*;
import static com.reactor.cachedb.core.protection.ProtectionException.Code.*;

/** Erases the actual pair's staged INITIAL journals; retained namespace/allocator fences are untouched. */
public final class JdbcInitialBootstrapPurgeParticipant implements PurgeParticipant {
    private final JdbcTenantPurgeGuard guard;
    private final Binding binding;
    private final String entity;
    private final int timeout;
    private final Declaration declaration;
    private final InitialSqlJournalCleanup cleanup;
    public JdbcInitialBootstrapPurgeParticipant(JdbcTenantPurgeGuard guard, Binding binding, String entityName, int timeoutSeconds) {
        this.guard=Objects.requireNonNull(guard);this.binding=Objects.requireNonNull(binding);entity=Objects.requireNonNull(entityName);
        if(!binding.tenantPurgeEnabled()||entityName.length()>128||timeoutSeconds<1||timeoutSeconds>5)fail(INVALID_REQUEST);
        timeout=timeoutSeconds;
        cleanup=new InitialSqlJournalCleanup(guard.ownerBootstrapDialect(),timeout);
        declaration=new Declaration("initial-source-journal",ProtectionKey.hash("initial-source-journal-v2:"+binding.fingerprint()),
                Set.of(entityName),false);
    }
    public boolean belongsTo(JdbcTenantPurgeGuard expected,Binding expectedBinding,String expectedEntity) {
        return guard==expected&&binding.equals(expectedBinding)&&entity.equals(expectedEntity);
    }
    public Declaration declaration(){return declaration;}
    public Result step(Request request){
        if(!request.lease().key().scopeToken().equals(binding.token()))fail(INVALID_REQUEST);
        return guard.inPurgeTransaction(request.lease(),c->{
            requireGeneration(c,request);
            UUID header=header(c,request);
            Cursor anchor=null;
            if(request.phase()==Phase.ERASE||request.phase()==Phase.VERIFY){
                var persisted=eraseAnchor(c,request);
                if(persisted==null){
                    if(request.phase()==Phase.VERIFY)return incomplete(request);
                    fail(OPERATION_CONFLICT);
                }
                anchor=persisted.cursor();
                if(request.phase()==Phase.ERASE&&!Objects.equals(cursor(request.checkpoint()),anchor))fail(OPERATION_CONFLICT);
            }
            UUID old=header!=null?header:anchor==null?null:anchor.operation();
            if(header!=null&&anchor!=null&&!header.equals(anchor.operation()))fail(OPERATION_CONFLICT);
            // Current purge journals and unknown/legacy operations must never be attributed to INITIAL.
            if(Objects.equals(old,request.lease().operationId())
                    ||cleanup.hasUnknownOperation(c,request.lease().key(),old,request.lease().operationId()))return incomplete(request);
            if(request.phase()==Phase.CLOSE||request.phase()==Phase.DISCOVER)return verified(request,0);
            if(request.phase()==Phase.VERIFY){
                if(header!=null||hasOwnerState(c,request)
                        ||old!=null&&!cleanup.empty(c,request.lease().key(),old))return incomplete(request);
                return verified(request,0);
            }
            if(old!=null&&anchor==null){
                // Persist the old operation in the ordinary participant journal BEFORE any destructive step.
                // A later lost response/header removal cannot lose the operation needed by independent VERIFY.
                return more(new Cursor(old,0),0);
            }
            if(old!=null&&cleanup.removeOne(c,request.lease().key(),old))return more(anchor.next(),1);
            if(deleteOwner(c,request))return old==null?verified(request,1):more(anchor.next(),1);
            if(header!=null){
                try(var q=prepare(c,"DELETE FROM cachedb_initial_bootstrap WHERE "+pairWhere(false)
                        +" AND operation_id="+varchar(36))){
                    pair(q,request);q.setString(3,header.toString());one(q.executeUpdate());
                }
                return more(anchor.next(),1);
            }
            return verified(request,0);
        });
    }
    private record Cursor(UUID operation,long steps){
        Cursor next(){return new Cursor(operation,Math.addExact(steps,1));}
        String token(){return "i:"+operation+":"+steps;}
    }
    private static Cursor cursor(String token){
        if(token==null||token.isEmpty())return null;
        if(!token.matches("i:[0-9a-f-]{36}:[0-9]{1,19}"))fail(INVALID_PROGRESS);
        try{
            UUID operation=UUID.fromString(token.substring(2,38));
            long steps=Long.parseLong(token.substring(39));
            var result=new Cursor(operation,steps);
            if(steps<0||!result.token().equals(token))fail(INVALID_PROGRESS);
            return result;
        }catch(IllegalArgumentException invalid){fail(INVALID_PROGRESS);return null;}
    }
    private record EraseCheckpoint(Cursor cursor){}
    private EraseCheckpoint eraseAnchor(Connection c,Request request)throws SQLException{
        try(var q=prepare(c,"SELECT declaration_token,checkpoint_value FROM cachedb_purge_participant WHERE "+pairWhere(false)
                +" AND operation_id="+varchar(36)+" AND participant_id="+varchar(64)+" AND phase_name="+varchar(16))){
            pair(q,request);q.setString(3,request.lease().operationId().toString());
            q.setString(4,declaration.id());q.setString(5,Phase.ERASE.name());
            try(var r=q.executeQuery()){
                if(!r.next())return null;
                if(!declaration.ownershipFingerprint().equals(r.getString(1)))fail(MIGRATION_REQUIRED);
                return new EraseCheckpoint(cursor(r.getString(2)));
            }
        }
    }
    private UUID header(Connection c,Request request)throws SQLException{
        try(var q=prepare(c,"SELECT operation_id,generation,payload_bytes FROM cachedb_initial_bootstrap WHERE "+pairWhere(false))){
            pair(q,request);try(var r=q.executeQuery()){
                if(!r.next())return null;
                String operation=r.getString(1);long generation=r.getLong(2),bytes=r.getLong(3);
                if(generation<1||generation>request.generation())fail(GENERATION_MISMATCH);
                if(bytes<1||bytes>InitialBootstrapState.MAX_BYTES)fail(INVALID_PROGRESS);
                try{
                    UUID value=UUID.fromString(operation);
                    if(!value.toString().equals(operation))fail(INVALID_PROGRESS);return value;
                }catch(IllegalArgumentException bad){fail(INVALID_PROGRESS);return null;}
            }
        }
    }
    private boolean hasOwnerState(Connection c,Request request)throws SQLException{
        try(var q=prepare(c,"SELECT payload_bytes FROM cachedb_owner_onboarding WHERE "+pairWhere(false))){
            pair(q,request);try(var r=q.executeQuery()){
                if(!r.next())return false;long bytes=r.getLong(1);
                if(bytes<1||bytes>SourceOwnerCheckpointCodec.MAX_BYTES)fail(INVALID_PROGRESS);return true;
            }
        }
    }
    private boolean deleteOwner(Connection c,Request request)throws SQLException{
        if(!hasOwnerState(c,request))return false;
        try(var q=prepare(c,"DELETE FROM cachedb_owner_onboarding WHERE "+pairWhere(false))){
            pair(q,request);one(q.executeUpdate());return true;
        }
    }
    private void requireGeneration(Connection c,Request request)throws SQLException{
        try(var q=prepare(c,"SELECT generation,gate_state FROM cachedb_tenant_guard WHERE "+pairWhere(true))){
            pair(q,request);try(var r=q.executeQuery()){
                if(!r.next()||r.getLong(1)!=request.generation()||!"CLOSED".equals(r.getString(2)))fail(GENERATION_MISMATCH);
            }
        }
    }
    private Result incomplete(Request request){return new Result(State.INCOMPLETE,request.checkpoint(),0,0,"");}
    private Result more(Cursor cursor,long removed){return new Result(State.MORE,cursor.token(),removed,removed,"");}
    private Result verified(Request request,long removed){
        return new Result(State.VERIFIED,request.checkpoint(),removed,removed,ProtectionKey.hash(declaration.ownershipFingerprint()
                +":"+request.lease().operationId()+":"+request.generation()+":"+request.phase()));
    }
    private String varchar(int width){return InitialSqlTokens.varchar(guard.ownerBootstrapDialect(),width);}
    private String pairWhere(boolean fixed){return InitialSqlTokens.pair(guard.ownerBootstrapDialect(),fixed);}
    private PreparedStatement prepare(Connection c,String sql)throws SQLException{
        var q=c.prepareStatement(sql);
        try{q.setQueryTimeout(timeout);q.setMaxRows(1);return q;}catch(SQLException failure){q.close();throw failure;}
    }
    private static void pair(PreparedStatement q,Request request)throws SQLException{
        q.setString(1,request.lease().key().scopeToken());q.setString(2,request.lease().key().tenantToken());
    }
    private static void one(int rows){if(rows!=1)fail(OPERATION_CONFLICT);}
    private static void fail(ProtectionException.Code code){throw new ProtectionException(code);}
}
