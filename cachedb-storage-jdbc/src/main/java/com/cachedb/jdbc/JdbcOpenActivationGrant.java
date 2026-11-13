package com.reactor.cachedb.jdbc;

import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.*;
import java.io.*;
import java.sql.*;
import java.util.*;
import static com.reactor.cachedb.core.protection.ProtectionException.Code.*;

/** Current SQL range and retained activation evidence, read through the locked guard; not native authority. */
public record JdbcOpenActivationGrant(TenantVersionRange range, Map<String,String> generationFields) {
    public JdbcOpenActivationGrant { Objects.requireNonNull(range); generationFields=Map.copyOf(generationFields); }
    @Override public String toString(){return "OpenActivationGrant[redacted]";}
    static JdbcOpenActivationGrant read(Connection c,JdbcTenantPurgeDialect d,int timeout,Generation g,
            String binding,String window,String manifest,Progress progress,long controlEpoch)throws SQLException {
        ProtectionKey.token(binding);ProtectionKey.token(window);ProtectionKey.token(manifest);
        var fields=new TreeMap<String,String>();TenantVersionRange activated;long activatedEpoch;
        if(progress==null) {
            try(var q=query(c,timeout,"SELECT operation_id,generation,plan_token,manifest_token,phase_name,activation_token,revision,payload,payload_bytes FROM cachedb_initial_bootstrap WHERE "+InitialSqlTokens.pair(d,false),g.key())) {
                try(var r=q.executeQuery()) {
                    if(!r.next()) throw new ProtectionException(RECOVERY_REQUIRED);
                    int size=r.getInt(9);if(size<1 || size>InitialBootstrapJournalCodec.MAX_BYTES) invalid();
                    byte[] bytes=payload(r,8,size);if(bytes.length!=size) invalid();
                    var f=InitialBootstrapJournalCodec.decode(bytes,g.key());var a=f.activation();
                    if(f.phase()!=InitialTenantBootstrap.Phase.ACTIVE || !f.finalizing() || a==null
                            || f.generation()!=g.value() || f.pages()!=f.verifiedPages()
                            || !window.equals(f.plan()) || !manifest.equals(f.manifest())
                            || !f.operation().toString().equals(r.getString(1)) || r.getLong(2)!=g.value()
                            || !window.equals(r.getString(3)) || !manifest.equals(r.getString(4))
                            || !"ACTIVE".equals(r.getString(5)) || !a.token().equals(r.getString(6)) || f.revision()!=r.getLong(7)
                            || !a.planToken().equals(window) || !a.manifestToken().equals(manifest)
                            || !a.window().digest().equals(f.windowDigest()) || a.window().rows()!=f.warmed()
                            || !a.inventoryToken().equals(f.inventory()) || !a.runtimeProof().equals(f.runtimeProof())
                            || !a.range().runId().equals(f.runId())) invalid();
                    activated=a.range();
                    activatedEpoch=activated.controlEpoch();
                    fields.put("operation",f.operation().toString());fields.put("i:operation",f.operation().toString());
                    fields.put("i:plan",window);fields.put("i:manifest",manifest);fields.put("i:active",a.token());
                    fields.put("i:window",a.window().digest());fields.put("i:verified",a.runtimeProof());
                    fields.put("i:active_range",rangeDigest(activated));
                }
            }
        } else {
            try(var q=query(c,timeout,"SELECT activation_id,payload FROM cachedb_empty_activation WHERE "+InitialSqlTokens.pair(d,true)
                    +" AND operation_id="+InitialSqlTokens.varchar(d,36),g.key())) {
                q.setString(3,progress.operationId().toString());
                try(var r=q.executeQuery()) {
                    if(!r.next()) throw new ProtectionException(RECOVERY_REQUIRED);
                    var cp=JdbcEmptyTenantActivation.decode(payload(r,2,4096));var id=cp.identity();
                    if(cp.phase()!=EmptyTenantActivation.Phase.ACTIVE || cp.queueCursor()<1
                            || !id.key().equals(g.key()) || id.toGeneration()!=g.value()
                            || id.fromGeneration()!=progress.closedGeneration() || !id.operationId().equals(progress.operationId())
                            || !id.activationId().toString().equals(r.getString(1)) || !id.manifestToken().equals(manifest)
                            || !id.inventoryToken().equals(progress.inventoryToken())) invalid();
                    activated=cp.reservation();
                    fields.put("operation",id.operationId().toString());fields.put("owner",cp.attemptOwner().toString());
                    fields.put("e:operation",id.operationId().toString());fields.put("e:identity",id.token());
                    fields.put("e:to",Long.toString(id.toGeneration()));fields.put("e:receipt",cp.receiptToken());
                    fields.put("e:install",cp.receiptToken());
                    // Recovery may re-fence native admission without rewriting the immutable reservation.
                    activatedEpoch=cp.attemptEpoch();
                }
            }
        }
        if(!activated.key().equals(g.key()) || activated.generation()!=g.value()
                || activated.controlEpoch()>activatedEpoch || activatedEpoch>controlEpoch) invalid();
        fields.put("epoch",Long.toString(activatedEpoch));
        try(var q=query(c,timeout,"SELECT verified_generation,binding_token,inventory_token,highwater,reservation_epoch,last_request,last_generation,last_control_epoch,last_first,last_last,last_run_id FROM cachedb_tenant_allocator WHERE "+InitialSqlTokens.pair(d,true),g.key())) {
            try(var r=q.executeQuery()) {
                if(!r.next() || r.getLong(1)!=g.value() || !binding.equals(r.getString(2))
                        || !activated.inventory().equals(r.getString(3)) || r.getLong(7)!=g.value()) invalid();
                TenantVersionRange current;
                try {
                    current=new TenantVersionRange(g.key(),g.value(),r.getLong(8),r.getLong(5),
                            r.getLong(9),r.getLong(10),UUID.fromString(r.getString(6)),r.getString(11),r.getString(3));
                } catch(IllegalArgumentException | NullPointerException | ProtectionException malformed) {
                    throw new ProtectionException(RECOVERY_REQUIRED);
                }
                if(r.getLong(4)!=current.last() || !current.request().toString().equals(r.getString(6))
                        || !activated.runId().equals(current.runId()) || current.controlEpoch()>controlEpoch
                        || current.controlEpoch()<activated.controlEpoch()
                        || current.reservationEpoch()<activated.reservationEpoch()) invalid();
                // The activation proof stays immutable; a later SQL reservation must advance without overlap.
                if(current.reservationEpoch()==activated.reservationEpoch()) {
                    if(!current.request().equals(activated.request()) || current.controlEpoch()!=activated.controlEpoch()
                            || current.first()!=activated.first() || current.last()!=activated.last()) invalid();
                } else if(current.first()<=activated.last() || current.request().equals(activated.request())) invalid();
                return new JdbcOpenActivationGrant(current,fields);
            }
        }
    }
    private static PreparedStatement query(Connection c,int timeout,String sql,ProtectionKey key)throws SQLException {
        var q=c.prepareStatement(sql);
        try {q.setQueryTimeout(timeout);q.setMaxRows(1);q.setString(1,key.scopeToken());q.setString(2,key.tenantToken());return q;}
        catch(SQLException e){q.close();throw e;}
    }
    private static byte[] payload(ResultSet r,int column,int limit)throws SQLException {
        try(var in=r.getBinaryStream(column)) {
            if(in==null) throw new ProtectionException(RECOVERY_REQUIRED);
            byte[] bytes=in.readNBytes(limit+1);if(bytes.length>limit) invalid();return bytes;
        } catch(IOException e){throw new ProtectionException(RECOVERY_REQUIRED);}
    }
    private static void invalid(){throw new ProtectionException(RECOVERY_REQUIRED);}
    private static String rangeDigest(TenantVersionRange r) {
        try {
            var hash=java.security.MessageDigest.getInstance("SHA-256");
            for(String field:List.of(r.request().toString(),Long.toString(r.reservationEpoch()),Long.toString(r.first()),
                    Long.toString(r.last()),r.inventory(),r.runId())) {
                byte[] bytes=field.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                hash.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());hash.update(bytes);
            }
            return HexFormat.of().formatHex(hash.digest());
        }catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
}
