package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.InitialTenantBootstrap.*;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.jdbc.*;
import com.reactor.cachedb.jdbc.InitialBootstrapJournalCodec.Frame;
import java.sql.*;
import java.util.*;
import static com.reactor.cachedb.core.protection.ProtectionException.Code.*;

/** Root-private authority. SQL descriptors/codecs remain data; only a concrete OPEN observation enables GC. */
final class CacheInitialActivationCommit {
    private final CacheDatabase root;
    private final JdbcTenantPurgeGuard guard;
    private final CacheInitialNativeBridge<?,?> bridge;
    private final String plan,manifest;
    private final JdbcTenantPurgeDialect dialect;
    private static final int TIMEOUT=2;

    CacheInitialActivationCommit(CacheDatabase root,JdbcTenantPurgeGuard guard,CacheInitialNativeBridge<?,?> bridge,
            JdbcInitialTenantBootstrap<?,?> sql) {
        this.root=Objects.requireNonNull(root);this.guard=Objects.requireNonNull(guard);this.bridge=Objects.requireNonNull(bridge);
        if(!guard.belongsTo(root.dataSource()) || !sql.belongsTo(guard))fail(UNWIRED_PROTOCOL);
        plan=sql.window().fingerprint();manifest=bridge.manifestToken();dialect=guard.ownerBootstrapDialect();
    }
    void finish(Session session,Activation activation,CacheInitialNativeBridge.OpenObservation observation) {
        if(!CacheTenantContext.isAdministrative() || observation==null)fail(UNWIRED_PROTOCOL);
        guard.inInitialBootstrapTransaction(session,c->{
            var frame=read(c,session);
            if(frame.phase()!=Phase.SQL_OPEN || !frame.operation().equals(session.request().operationId())
                    || frame.generation()!=session.request().generation().value()
                    || !frame.plan().equals(plan) || !frame.manifest().equals(manifest)
                    || !session.planToken().equals(plan) || !session.manifestToken().equals(manifest)
                    || frame.activation()==null || !InitialBootstrapJournalCodec.activationFields(frame.activation())
                            .equals(InitialBootstrapJournalCodec.activationFields(activation)))fail(OPERATION_CONFLICT);
            // CPU-only consumption AFTER actual guard-row/lease validation, BEFORE the first destructive statement.
            observation.consume(bridge,root,guard,session,activation);
            unknown(c,session);
            boolean removed=removeOne(c,session);
            var next=new Frame(frame.operation(),frame.generation(),Math.addExact(frame.revision(),1),frame.probes(),
                    frame.warmed(),removed?frame.residentBytes():0,frame.plan(),frame.manifest(),frame.operator(),
                    frame.runId(),frame.roster(),frame.inventory(),frame.runtimeProof(),removed?frame.cursor():null,
                    frame.windowDigest(),removed?frame.pages():0,removed?frame.verifiedPages():0,
                    removed?Phase.SQL_OPEN:Phase.ACTIVE,frame.activation(),true);
            byte[] bytes=InitialBootstrapJournalCodec.encode(next,session.lease().key());
            try(var q=prepare(c,"UPDATE cachedb_initial_bootstrap SET phase_name="+token(32)+",activation_token="+token(64)
                    +",revision=?,payload=?,payload_bytes=? WHERE "+pair(false)+" AND operation_id="+token(36)+" AND revision=?")) {
                q.setString(1,next.phase().name());q.setString(2,activation.token());q.setLong(3,next.revision());
                q.setBytes(4,bytes);q.setInt(5,bytes.length);key(q,6,session);q.setString(8,frame.operation().toString());
                q.setLong(9,frame.revision());one(q.executeUpdate());
            }
            // The guard revalidates this exact live lease before committing the still-locked transaction.
            return null;
        });
    }
    private Frame read(Connection c,Session session)throws SQLException {
        int bytes;
        try(var q=prepare(c,"SELECT payload_bytes FROM cachedb_initial_bootstrap WHERE "+pair(false)+" AND operation_id="+token(36))) {
            bind(q,session);try(var r=q.executeQuery()){if(!r.next())fail(INVALID_PROGRESS);bytes=r.getInt(1);}
        }
        if(bytes<1 || bytes>InitialBootstrapJournalCodec.MAX_BYTES)fail(INVALID_PROGRESS);
        try(var q=prepare(c,"SELECT payload FROM cachedb_initial_bootstrap WHERE "+pair(false)+" AND operation_id="+token(36))) {
            bind(q,session);try(var r=q.executeQuery()) {
                if(!r.next())fail(INVALID_PROGRESS);
                try(var in=r.getBinaryStream(1)) {
                    if(in==null)throw new ProtectionException(INVALID_PROGRESS);
                    byte[] value=in.readNBytes(bytes+1);if(value.length!=bytes)fail(INVALID_PROGRESS);
                    return InitialBootstrapJournalCodec.decode(value,session.lease().key());
                }catch(java.io.IOException malformed){throw new ProtectionException(INVALID_PROGRESS);}
            }
        }
    }
    private void unknown(Connection c,Session session)throws SQLException {
        for(var store:InitialSqlCleanupPlan.stores()) {
            String after=null;
            for(int seen=0;seen<3;seen++) {
                String found;
                try(var q=prepare(c,store.selectOperation(dialect,after!=null))) {
                    key(q,1,session);if(after!=null)q.setString(3,after);
                    try(var r=q.executeQuery()){found=r.next()?r.getString(1):null;}
                }
                if(found==null)break;
                UUID operation;
                try{operation=UUID.fromString(found);}catch(IllegalArgumentException malformed){throw new ProtectionException(INVALID_PROGRESS);}
                if(!operation.toString().equals(found))fail(INVALID_PROGRESS);
                if(!operation.equals(session.request().operationId()))fail(MIGRATION_REQUIRED);
                after=found;
            }
        }
    }
    private boolean removeOne(Connection c,Session session)throws SQLException {
        for(var store:InitialSqlCleanupPlan.stores()) {
            List<Object> values;
            try(var q=prepare(c,store.selectFirst(dialect))) {
                bind(q,session);try(var r=q.executeQuery()) {
                    if(!r.next())continue;
                    values=new ArrayList<>(store.columns().size());
                    for(int i=0;i<store.columns().size();i++) {
                        int width=store.columns().get(i).width();
                        if(width==0) {
                            int page=r.getInt(i+1);if(r.wasNull() || page<0 || page>=InitialSourceHotWindow.MAX_PAGES)fail(INVALID_PROGRESS);
                            values.add(page);
                        }else {
                            String value=r.getString(i+1);
                            if(value==null || value.isEmpty() || value.length()>Math.abs(width) || !value.matches("[A-Za-z0-9._-]+"))fail(INVALID_PROGRESS);
                            values.add(value);
                        }
                    }
                }
            }
            try(var q=prepare(c,store.deleteSelected(dialect))) {
                bind(q,session);
                for(int i=0;i<values.size();i++) {
                    if(store.columns().get(i).width()==0)q.setInt(i+4,(Integer)values.get(i));else q.setString(i+4,(String)values.get(i));
                }
                one(q.executeUpdate());
            }
            return true;
        }
        return false;
    }
    private PreparedStatement prepare(Connection c,String sql)throws SQLException {
        var q=c.prepareStatement(sql);
        try{q.setQueryTimeout(TIMEOUT);q.setMaxRows(1);q.setFetchSize(1);return q;}catch(SQLException failure){q.close();throw failure;}
    }
    private String pair(boolean fixed){return InitialSqlCleanupPlan.pair(dialect,fixed);}
    private String token(int width){return InitialSqlCleanupPlan.token(dialect,width);}
    private static void bind(PreparedStatement q,Session s)throws SQLException {key(q,1,s);q.setString(3,s.request().operationId().toString());}
    private static void key(PreparedStatement q,int index,Session s)throws SQLException {q.setString(index,s.lease().key().scopeToken());q.setString(index+1,s.lease().key().tenantToken());}
    private static void one(int rows){if(rows!=1)fail(OPERATION_CONFLICT);}
    private static void fail(ProtectionException.Code code){throw new ProtectionException(code);}
}
