package com.reactor.cachedb.jdbc;

import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.*;
import java.sql.*;
import java.util.*;
import static com.reactor.cachedb.core.protection.ProtectionException.Code.*;

/** Durable pair allocator. All methods are administrative/background; NONE belongs on foreground acceptance. */
public final class JdbcTenantVersionAllocator {
    public record Status(long highwater,long reservationEpoch,long verifiedGeneration,String inventory,
            String durableCursor,boolean durableComplete,long redisMaximum,long sqlMaximum) {}
    private record Row(long high,long epoch,long verified,String inventory,String binding,String operation,
            long redis,long sql,String cursor,boolean complete,String request,long generation,long control,
            long first,long last,String runId) {}
    private final JdbcTenantPurgeGuard guard;
    private final JdbcTenantPurgeDialect dialect;
    private final int timeout;
    public JdbcTenantVersionAllocator(JdbcTenantPurgeGuard guard,JdbcTenantPurgeDialect dialect,int timeoutSeconds) {
        if(timeoutSeconds<1 || timeoutSeconds>5)throw new ProtectionException(INVALID_REQUEST);
        this.guard=Objects.requireNonNull(guard);this.dialect=Objects.requireNonNull(dialect);timeout=timeoutSeconds;
    }
    /** Caller has frozen legacy Redis producers/replays and holds the durable CLOSED maintenance lease. */
    public Status beginInventory(Lease lease,Binding binding,long generation) {
        if(!binding.tenantPurgeEnabled() || !binding.token().equals(lease.key().scopeToken()))throw new ProtectionException(INVALID_REQUEST);
        return guard.inClosedVersionTransaction(lease,generation,(c,epoch)->{
            return beginIn(c,lease,binding);
        });
    }
    public Optional<Status> findStatusClosed(Lease lease,long generation) {
        return guard.inClosedVersionTransaction(lease,generation,(c,epoch)->{
            Row row=read(c,lease.key(),false);return row==null?Optional.empty():Optional.of(status(row));
        });
    }
    public Status statusClosed(Lease lease,long generation) {
        return guard.inClosedVersionTransaction(lease,generation,(c,epoch)->status(read(c,lease.key(),true)));
    }
    public Status statusOpen(Generation generation) {
        return guard.inOpenVersionTransaction(generation,(c,epoch)->status(read(c,generation.key(),true)));
    }
    /** Used only inside the journal's SAME transaction that consumes the corresponding pending frame. */
    static void observeRedisMaximum(Connection c,Lease lease,long maximum,int timeout)throws SQLException {
        observeRedisMaximum(c,lease,maximum,timeout,null);
    }
    static void observeRedisMaximum(Connection c,Lease lease,long maximum,int timeout,JdbcTenantPurgeDialect dialect)throws SQLException {
        bounded(maximum);
        try(var q=c.prepareStatement("UPDATE cachedb_tenant_allocator SET redis_max=CASE WHEN redis_max<? THEN ? ELSE redis_max END"
                +" WHERE "+InitialSqlTokens.pair(dialect,true)+" AND inventory_operation="+ InitialSqlTokens.varchar(dialect,36) +" AND verified_generation=0")){
            q.setQueryTimeout(timeout);q.setLong(1,maximum);q.setLong(2,maximum);key(q,3,lease.key());
            q.setString(5,lease.operationId().toString());one(q.executeUpdate());
        }
    }
    /** One-time conservative scan of ALL durable SQL fence high waters, including physical-delete tombstones. */
    public Status scanDurableFences(Lease lease,long generation,int limit) {
        if(limit<1 || limit>128)throw new ProtectionException(INVALID_REQUEST);
        return guard.inClosedVersionTransaction(lease,generation,(c,epoch)->{
            return scanDurableIn(c,lease,limit);
        });
    }
    /** Requires complete owner+namespace discovery, no unknowns, and a version check for EVERY consumed item. */
    public Status sealInventory(Lease lease,Binding binding,long generation,String versionColumn) {
        identifier(versionColumn);
        return guard.inClosedVersionTransaction(lease,generation,(c,epoch)->{
            return sealIn(c,lease,binding,generation,versionColumn);
        });
    }
    /** Indexed administrative source maximum, including cold rows; no payloads or IDs are materialized. */
    public Status probeSourceMaximum(Lease lease,Binding binding,long generation,String rawTenant,String versionColumn) {
        if(!binding.key(rawTenant).equals(lease.key()))throw new ProtectionException(CONFIRMATION_REQUIRED);
        identifier(versionColumn);
        return guard.inClosedVersionTransaction(lease,generation,(c,epoch)->{
            Row row=inventory(c,lease);
            if(!row.binding.equals(binding.fingerprint()))throw new ProtectionException(OPERATION_CONFLICT);
            requireVersionIndex(c,binding.source(),versionColumn);
            String sql=limited(quote(versionColumn),quote(binding.source().table()),
                    " WHERE "+quote(binding.source().tenantColumn())+"=?",quote(versionColumn)+" DESC",1);
            long maximum=row.sql;
            try(var q=prepare(c,sql)){
                if(dialect==JdbcTenantPurgeDialect.MSSQL)q.setNString(1,rawTenant);else q.setString(1,rawTenant);
                try(var r=q.executeQuery()){
                    if(r.next()){long v=r.getLong(1);if(r.wasNull())throw new ProtectionException(MIGRATION_REQUIRED);
                        bounded(v);maximum=Math.max(maximum,v);}
                }
            }
            try(var q=prepare(c,"UPDATE cachedb_tenant_allocator SET sql_max=?,source_complete=1 WHERE "+InitialSqlTokens.pair(dialect,true)+"")){
                q.setLong(1,maximum);key(q,2,lease.key());one(q.executeUpdate());
            }
            return status(read(c,lease.key(),true));
        });
    }
    private boolean sourceMaximumVerified(Connection c,Lease lease)throws SQLException{
        try(var q=prepare(c,"SELECT source_complete FROM cachedb_tenant_allocator WHERE "+InitialSqlTokens.pair(dialect,true)+"")){
            key(q,1,lease.key());try(var r=q.executeQuery()){return r.next()&&r.getInt(1)==1;}
        }
    }
    public TenantVersionRange reserveClosed(Lease lease,long generation,int count,UUID request,long expectedEpoch,String runId) {
        return guard.inClosedVersionTransaction(lease,generation,(c,control)->
                reserve(c,lease.key(),generation,control,count,request,expectedEpoch,runId));
    }
    public TenantVersionRange reserveOpen(Generation generation,int count,UUID request,long expectedEpoch,String runId) {
        return guard.inOpenVersionTransaction(generation,(c,control)->
                reserve(c,generation.key(),generation.value(),control,count,request,expectedEpoch,runId));
    }
    /** A pending SQL_OPEN activation must not lose its frozen reservation to an administrative refill. */
    public TenantVersionRange reserveOpenActivated(Generation generation,String bindingFingerprint,
            String windowFingerprint,String manifestToken,int count,UUID request,long expectedEpoch,String runId) {
        return guard.inOpenVersionTransaction(generation,bindingFingerprint,(c,control)->{
            var grant=guard.readOpenActivationGrant(c,generation,bindingFingerprint,windowFingerprint,manifestToken);
            if(!grant.range().runId().equals(runId)) throw new ProtectionException(RECOVERY_REQUIRED);
            return reserve(c,generation.key(),generation.value(),control,count,request,expectedEpoch,runId);
        });
    }
    TenantVersionRange reserveEmpty(Connection c, Lease lease, Binding binding,
            EmptyTenantActivation.Identity identity, int count, String runId) throws SQLException {
        Row row = read(c, lease.key(), true);
        if (row.verified != identity.fromGeneration() || !identity.inventoryToken().equals(row.inventory)
                || !lease.operationId().toString().equals(row.operation)
                || !binding.fingerprint().equals(row.binding)) throw new ProtectionException(MIGRATION_REQUIRED);
        return reserve(c, lease.key(), identity.toGeneration(), lease.epoch(), count,
                identity.activationId(), row.epoch, runId);
    }

    private TenantVersionRange reserve(Connection c,ProtectionKey key,long generation,long control,int count,
            UUID request,long expectedEpoch,String runId)throws SQLException {
        if(count<1 || count>TenantVersionRange.MAX_RANGE || request==null || runId==null || !runId.matches("[0-9a-f]{40}"))
            throw new ProtectionException(INVALID_REQUEST);
        Row row=read(c,key,true);
        if(row.verified<1 || row.inventory==null || generation<row.verified || generation-row.verified>1)
            throw new ProtectionException(MIGRATION_REQUIRED);
        if(request.toString().equals(row.request)) {
            if(row.generation!=generation || row.control!=control || row.last-row.first+1!=count
                    || row.epoch-1!=expectedEpoch || row.high!=row.last || !runId.equals(row.runId))
                throw new ProtectionException(RESERVATION_CONFLICT);
            return grant(key,row);
        }
        if(expectedEpoch!=row.epoch)throw new ProtectionException(RESERVATION_CONFLICT);
        if(row.high>TenantVersionRange.MAX_VERSION-count || row.epoch==Long.MAX_VALUE)throw new ProtectionException(VERSION_LIMIT);
        long first=row.high+1,last=row.high+count,next=row.epoch+1;
        try(var q=prepare(c,"UPDATE cachedb_tenant_allocator SET highwater=?,reservation_epoch=?,verified_generation=?,"
                +"last_request="+ InitialSqlTokens.varchar(dialect,36) +",last_generation=?,last_control_epoch=?,last_first=?,last_last=?,last_run_id="+ InitialSqlTokens.varchar(dialect,40) +" WHERE "+InitialSqlTokens.pair(dialect,true)+"")){
            q.setLong(1,last);q.setLong(2,next);q.setLong(3,generation);q.setString(4,request.toString());q.setLong(5,generation);
            q.setLong(6,control);q.setLong(7,first);q.setLong(8,last);q.setString(9,runId);key(q,10,key);one(q.executeUpdate());
        }
        return new TenantVersionRange(key,generation,control,next,first,last,request,runId,row.inventory);
    }
    private TenantVersionRange grant(ProtectionKey key,Row r){
        return new TenantVersionRange(key,r.generation,r.control,r.epoch,r.first,r.last,UUID.fromString(r.request),r.runId,r.inventory);
    }
    private Row inventory(Connection c,Lease lease)throws SQLException{
        Row row=read(c,lease.key(),true);
        if(!row.operation.equals(lease.operationId().toString()) || row.verified!=0)throw new ProtectionException(MIGRATION_REQUIRED);
        return row;
    }
    private Row read(Connection c,ProtectionKey key,boolean required)throws SQLException {
        try(var q=prepare(c,"SELECT highwater,reservation_epoch,verified_generation,inventory_token,binding_token,inventory_operation,"
                +"redis_max,sql_max,durable_cursor,durable_complete,last_request,last_generation,last_control_epoch,last_first,last_last,last_run_id"
                +" FROM cachedb_tenant_allocator WHERE "+InitialSqlTokens.pair(dialect,true)+"")){
            key(q,1,key);try(var r=q.executeQuery()){
                if(!r.next()){if(required)throw new ProtectionException(MIGRATION_REQUIRED);return null;}
                Row row=new Row(r.getLong(1),r.getLong(2),r.getLong(3),r.getString(4),r.getString(5),r.getString(6),
                        r.getLong(7),r.getLong(8),r.getString(9),r.getInt(10)==1,r.getString(11),
                        r.getLong(12),r.getLong(13),r.getLong(14),r.getLong(15),r.getString(16));
                bounded(row.high);bounded(row.redis);bounded(row.sql);return row;
            }
        }
    }
    private void requireVersionIndex(Connection c,SourcePlan plan,String version)throws SQLException {
        String[] parts=plan.table().split("\\.");String schema=parts.length==2?parts[0]:c.getSchema(),table=parts[parts.length-1];
        var meta=c.getMetaData();if(meta.storesUpperCaseIdentifiers()){table=table.toUpperCase(Locale.ROOT);if(schema!=null)schema=schema.toUpperCase(Locale.ROOT);}
        boolean numeric=false;int columns=0;
        try(var r=meta.getColumns(c.getCatalog(),schema,table,null)){
            while(r.next()){
                if(++columns>4096)throw new ProtectionException(INVALID_REQUEST);
                if(!version.equalsIgnoreCase(r.getString("COLUMN_NAME")))continue;
                int type=r.getInt("DATA_TYPE"),scale=r.getInt("DECIMAL_DIGITS");
                numeric=type==Types.BIGINT || type==Types.INTEGER || type==Types.SMALLINT
                        || (type==Types.NUMERIC || type==Types.DECIMAL) && scale==0;
            }
        }
        if(!numeric)throw new ProtectionException(INVALID_REQUEST);
        var leading=new HashMap<String,Map<Integer,String>>();int rows=0;
        try(var r=meta.getIndexInfo(c.getCatalog(),schema,table,false,false)){
            while(r.next()){
                if(++rows>4096)throw new ProtectionException(INDEX_REQUIRED);
                String name=r.getString("INDEX_NAME"),column=r.getString("COLUMN_NAME");int ordinal=r.getInt("ORDINAL_POSITION");
                if(name!=null && column!=null && ordinal>0 && ordinal<=2)
                    leading.computeIfAbsent(name,k->new HashMap<>()).put(ordinal,column);
            }
        }
        boolean found=leading.values().stream().anyMatch(x->plan.tenantColumn().equalsIgnoreCase(x.get(1))&&version.equalsIgnoreCase(x.get(2)));
        if(!found)throw new ProtectionException(INDEX_REQUIRED);
    }
    private String quote(String name){
        var result=new StringJoiner(".");for(String part:name.split("\\.")){
            identifier(part);String folded=dialect==JdbcTenantPurgeDialect.ORACLE?part.toUpperCase(Locale.ROOT)
                    :dialect==JdbcTenantPurgeDialect.POSTGRES?part.toLowerCase(Locale.ROOT):part;
            result.add(dialect==JdbcTenantPurgeDialect.MSSQL?"["+folded+"]":"\""+folded+"\"");
        }return result.toString();
    }
    private String limited(String cols,String table,String where,String order,int n){
        return "SELECT "+(dialect==JdbcTenantPurgeDialect.MSSQL?"TOP ("+n+") ":"")+cols+" FROM "+table+where+" ORDER BY "+order
                +(dialect==JdbcTenantPurgeDialect.POSTGRES?" LIMIT "+n:dialect==JdbcTenantPurgeDialect.ORACLE?" FETCH FIRST "+n+" ROWS ONLY":"");
    }
    private PreparedStatement prepare(Connection c,String sql)throws SQLException{
        var q=c.prepareStatement(sql);try{q.setQueryTimeout(timeout);return q;}catch(SQLException e){q.close();throw e;}
    }
    private static Status status(Row r){return new Status(r.high,r.epoch,r.verified,r.inventory,r.cursor,r.complete,r.redis,r.sql);}
    private static void identifier(String x){if(x==null||!x.matches("[A-Za-z_][A-Za-z0-9_]{0,62}"))throw new ProtectionException(INVALID_REQUEST);}
    private static void bounded(long n){if(n<0||n>TenantVersionRange.MAX_VERSION)throw new ProtectionException(VERSION_LIMIT);}
    private static void key(PreparedStatement q,int i,ProtectionKey key)throws SQLException{q.setString(i,key.scopeToken());q.setString(i+1,key.tenantToken());}
    private static void one(int n){if(n!=1)throw new ProtectionException(MIGRATION_REQUIRED);}
    private Status beginIn(Connection c,Lease lease,Binding binding)throws SQLException {
        Row old=read(c,lease.key(),false);
        if(old==null) {
            try(var q=prepare(c,"INSERT INTO cachedb_tenant_allocator(scope_token,tenant_token,binding_token,inventory_operation)"
                    +" VALUES ("+InitialSqlTokens.char64(dialect)+","+InitialSqlTokens.char64(dialect)+","+InitialSqlTokens.char64(dialect)+","+InitialSqlTokens.varchar(dialect,36)+")")){
                key(q,1,lease.key());q.setString(3,binding.fingerprint());q.setString(4,lease.operationId().toString());one(q.executeUpdate());
            }
        } else if(!old.operation.equals(lease.operationId().toString())) {
            try(var q=prepare(c,"UPDATE cachedb_tenant_allocator SET verified_generation=0,inventory_token=NULL,"
                    +"binding_token="+ InitialSqlTokens.char64(dialect) +",inventory_operation="+ InitialSqlTokens.varchar(dialect,36) +",redis_max=0,sql_max=0,durable_cursor='-',durable_complete=0,source_complete=0"
                    +" WHERE "+InitialSqlTokens.pair(dialect,true)+"")){
                q.setString(1,binding.fingerprint());q.setString(2,lease.operationId().toString());key(q,3,lease.key());one(q.executeUpdate());
            }
        } else if(!old.binding.equals(binding.fingerprint()))throw new ProtectionException(OPERATION_CONFLICT);
        return status(read(c,lease.key(),true));
    }

    private Status scanDurableIn(Connection c,Lease lease,int limit)throws SQLException {
        Row row=inventory(c,lease);if(row.complete)return status(row);
        String where=row.cursor.equals("-")?"":" WHERE fence_key>"+InitialSqlTokens.char64(dialect);
        String sql=limited("fence_key,entity_version","cachedb_write_fence",where,"fence_key",limit);
        long maximum=row.sql;String cursor=row.cursor;int count=0;
        try(var q=prepare(c,sql)){
            if(!row.cursor.equals("-"))q.setString(1,row.cursor);q.setFetchSize(128);
            try(var r=q.executeQuery()){
                while(r.next()){
                    cursor=ProtectionKey.token(r.getString(1));java.math.BigDecimal raw=r.getBigDecimal(2);long v;
                    try{if(raw==null)throw new ArithmeticException();v=raw.longValueExact();}
                    catch(ArithmeticException invalid){throw new ProtectionException(MIGRATION_REQUIRED);}
                    bounded(v);maximum=Math.max(maximum,v);count++;
                }
            }
        }
        try(var q=prepare(c,"UPDATE cachedb_tenant_allocator SET sql_max=?,durable_cursor="+ InitialSqlTokens.varchar(dialect,64) +",durable_complete=? WHERE "+InitialSqlTokens.pair(dialect,true)+"")){
            q.setLong(1,maximum);q.setString(2,cursor);q.setInt(3,count<limit?1:0);key(q,4,lease.key());one(q.executeUpdate());
        }
        return status(read(c,lease.key(),true));
    }

    private Status sealIn(Connection c,Lease lease,Binding binding,long generation,String versionColumn)throws SQLException {
        Row row=read(c,lease.key(),true);
        if(row.verified==generation && row.inventory!=null && row.operation.equals(lease.operationId().toString())
                && row.binding.equals(binding.fingerprint()))return status(row);
        if(row.verified!=0 || !row.operation.equals(lease.operationId().toString()))
            throw new ProtectionException(MIGRATION_REQUIRED);
        if(!row.binding.equals(binding.fingerprint()) || !row.complete)throw new ProtectionException(MIGRATION_REQUIRED);
        long total=0,owners=0,namespaces=0;
        try(var q=prepare(c,"SELECT scan_kind,is_complete,is_failed,pending_id,consumed_total,inventory_total"
                +" FROM cachedb_purge_scan WHERE "+InitialSqlTokens.pair(dialect,true)+" AND operation_id="+InitialSqlTokens.varchar(dialect,36)+"")){
            key(q,1,lease.key());q.setString(3,lease.operationId().toString());q.setFetchSize(128);
            try(var r=q.executeQuery()){
                while(r.next()){
                    if(++total>10000 || r.getInt(2)!=1 || r.getInt(3)!=0 || r.getString(4)!=null
                            || r.getLong(5)!=r.getLong(6))throw new ProtectionException(MIGRATION_REQUIRED);
                    if("OWNER".equals(r.getString(1)))owners++;
                    if("NAMESPACE".equals(r.getString(1)))namespaces++;
                }
            }
        }
        if(owners<1 || namespaces<1)throw new ProtectionException(MIGRATION_REQUIRED);
        requireVersionIndex(c,binding.source(),versionColumn);
        if(!sourceMaximumVerified(c,lease))throw new ProtectionException(MIGRATION_REQUIRED);
        long high=Math.max(row.high,Math.max(row.redis,row.sql));bounded(high);
        String token=ProtectionKey.hash(row.binding+":"+lease.operationId()+":"+generation+":"+high);
        try(var q=prepare(c,"UPDATE cachedb_tenant_allocator SET highwater=?,verified_generation=?,inventory_token="+ InitialSqlTokens.char64(dialect) +" WHERE "+InitialSqlTokens.pair(dialect,true)+"")){
            q.setLong(1,high);q.setLong(2,generation);q.setString(3,token);key(q,4,lease.key());one(q.executeUpdate());
        }
        return status(read(c,lease.key(),true));
    }

    public boolean belongsTo(JdbcTenantPurgeGuard expected) { return guard == expected && dialect == expected.ownerBootstrapDialect(); }

    /** INITIAL-purpose reuse of the same durable allocator; no purge progress is invented. */
    public Status beginInitialInventory(com.reactor.cachedb.core.protection.InitialTenantBootstrap.Session session,Binding binding) {
        if(!binding.token().equals(session.lease().key().scopeToken()))throw new ProtectionException(INVALID_REQUEST);
        return guard.inInitialBootstrapTransaction(session,c->beginIn(c,session.lease(),binding));
    }
    public Status scanInitialDurableFences(com.reactor.cachedb.core.protection.InitialTenantBootstrap.Session session,int limit) {
        if(limit<1||limit>128)throw new ProtectionException(INVALID_REQUEST);
        return guard.inInitialBootstrapTransaction(session,c->scanDurableIn(c,session.lease(),limit));
    }
    public Status sealInitialInventory(com.reactor.cachedb.core.protection.InitialTenantBootstrap.Session session,
            Binding binding,String versionColumn) {
        identifier(versionColumn);
        return guard.inInitialBootstrapTransaction(session,c->sealIn(c,session.lease(),binding,
                session.request().generation().value(),versionColumn));
    }
    Status sealInitialIn(Connection c,com.reactor.cachedb.core.protection.InitialTenantBootstrap.Session session,
            Binding binding,String versionColumn)throws SQLException {
        return sealIn(c,session.lease(),binding,session.request().generation().value(),versionColumn);
    }
    public Status initialStatus(com.reactor.cachedb.core.protection.InitialTenantBootstrap.Session session) {
        return guard.inInitialBootstrapTransaction(session,c->status(read(c,session.lease().key(),true)));
    }
    Status initialStatusIn(Connection c,com.reactor.cachedb.core.protection.InitialTenantBootstrap.Session session)throws SQLException {
        return status(read(c,session.lease().key(),true));
    }
    void observeInitialSourceMaximum(Connection c,com.reactor.cachedb.core.protection.InitialTenantBootstrap.Session session,
            long maximum)throws SQLException {
        bounded(maximum);inventory(c,session.lease());
        try(var q=prepare(c,"UPDATE cachedb_tenant_allocator SET sql_max=CASE WHEN sql_max<? THEN ? ELSE sql_max END,"
                +"source_complete=1 WHERE "+InitialSqlTokens.pair(dialect,true)+" AND inventory_operation="+ InitialSqlTokens.varchar(dialect,36) +"")){
            q.setLong(1,maximum);q.setLong(2,maximum);key(q,3,session.lease().key());
            q.setString(5,session.lease().operationId().toString());one(q.executeUpdate());
        }
    }
    TenantVersionRange reserveInitialIn(Connection c,com.reactor.cachedb.core.protection.InitialTenantBootstrap.Session session,
            int count,UUID request,String runId)throws SQLException {
        Row row=read(c,session.lease().key(),true);
        return reserve(c,session.lease().key(),session.request().generation().value(),session.lease().epoch(),
                count,request,row.epoch,runId);
    }

}
