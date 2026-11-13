package com.reactor.cachedb.jdbc;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.*;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Real SQL guard/journal controls in disposable schemas. No native Redis bridge, closure approval,
 * source bootstrap activation or facade COMPLETE is claimed by these tests.
 */
@EnabledIfSystemProperty(named="cachedb.snapshot.providers",matches="true")
@EnabledIfSystemProperty(named="cachedb.initial.isolatedSql",matches="true")
@Timeout(120)
class InitialJournalCleanupProvidersTest {
    @ParameterizedTest @ValueSource(strings={"postgres","mssql","oracle"})
    void interruptedInitialRawFramesAreRemovedByANewPurgeOperationWithoutTouchingOtherPairs(String provider)throws Exception{
        try(var admin=CacheTenantContext.administrative();var f=new Fixture(provider)){
            var a=f.initial("a");var b=f.initial("b");
            byte[] foreign=f.pendingBytes(b);UUID old=a.request().operationId();
            f.guard.release(a.lease());f.guard.release(b.lease());
            var purge=f.purge("a");
            try{
                assertNotEquals(old,purge.operationId());
                var participant=new JdbcInitialBootstrapPurgeParticipant(f.guard,f.binding,"JournalRow",2);
                var journal=new JdbcPurgeParticipantJournal(f.guard,2,1);
                assertEquals(PurgeParticipant.State.VERIFIED,journal.step(purge,1,participant,PurgeParticipant.Phase.CLOSE,1048576).checkpoint().state());
                assertEquals(PurgeParticipant.State.VERIFIED,journal.step(purge,1,participant,PurgeParticipant.Phase.DISCOVER,1048576).checkpoint().state());
                f.redisPhaseSqlControl(purge);
                var first=journal.step(purge,1,participant,PurgeParticipant.Phase.ERASE,1048576);
                assertEquals(PurgeParticipant.State.MORE,first.checkpoint().state());
                assertTrue(first.checkpoint().cursor().startsWith("i:"+old+":"));
                assertTrue(f.count("cachedb_purge_scan",a.lease().key(),old)>0,"Anchor must commit before raw deletion");
                boolean finished=false;
                for(int i=0;i<32;i++){
                    var result=journal.step(purge,1,participant,PurgeParticipant.Phase.ERASE,1048576).checkpoint();
                    if(result.state()==PurgeParticipant.State.VERIFIED){finished=true;break;}
                    assertEquals(PurgeParticipant.State.MORE,result.state());
                }
                assertTrue(finished,"Fixed fixture cleanup must finish within the bounded continuation budget");
                assertEquals(PurgeParticipant.State.VERIFIED,journal.step(purge,1,participant,PurgeParticipant.Phase.VERIFY,1048576).checkpoint().state());
                for(String table:Fixture.TEMP)assertEquals(0,f.count(table,a.lease().key(),old),table);
                assertEquals(0,f.pairCount("cachedb_initial_bootstrap",a.lease().key()));
                assertEquals(0,f.pairCount("cachedb_owner_onboarding",a.lease().key()));
                assertArrayEquals(foreign,f.pendingBytes(b));
                assertEquals(1,f.pairCount("cachedb_initial_bootstrap",b.lease().key()));
                assertEquals(1,f.pairCount("cachedb_tenant_guard",a.lease().key()));
                assertEquals(1,f.pairCount("cachedb_tenant_allocator",a.lease().key()));
                assertEquals(1,f.pairCount("cachedb_namespace_bootstrap",a.lease().key()));
                assertEquals(1,f.scalar("SELECT COUNT(*) FROM cachedb_write_fence WHERE entity_version=9000"));
                assertEquals(2,f.scalar("SELECT COUNT(*) FROM fixture_source"),"Redis-only administrative cleanup must not erase source");
                assertEquals(Stage.REDIS,f.guard.load(purge).stage(),"SQL controls must not manufacture facade COMPLETE");
            }finally{f.guard.release(purge);}
        }
    }

    @ParameterizedTest @ValueSource(strings={"postgres","mssql","oracle"})
    void lostParticipantCheckpointCommitRetainsThePriorInitialOperationForResume(String provider)throws Exception{
        try(var admin=CacheTenantContext.administrative();var f=new Fixture(provider)){
            var a=f.initial("a");f.guard.release(a.lease());var purge=f.purge("a");
            try{
                var participant=new JdbcInitialBootstrapPurgeParticipant(f.guard,f.binding,"JournalRow",2);
                var journal=new JdbcPurgeParticipantJournal(f.guard,2,1);
                f.redisPhaseSqlControl(purge);
                journal.step(purge,1,participant,PurgeParticipant.Phase.ERASE,1048576); // Durable old-operation anchor.
                f.faults.arm("UPDATE cachedb_purge_participant",false);
                assertEquals(ProtectionException.Code.STORAGE_UNAVAILABLE,assertThrows(ProtectionException.class,
                        ()->journal.step(purge,1,participant,PurgeParticipant.Phase.ERASE,1048576)).code());
                f.faults.requireConsumed();
                for(int i=0;i<32;i++){
                    var result=journal.step(purge,1,participant,PurgeParticipant.Phase.ERASE,1048576).checkpoint();
                    if(result.state()==PurgeParticipant.State.VERIFIED)break;
                    if(i==31)fail("Cleanup did not resume within its bounded fixture budget");
                }
                assertEquals(PurgeParticipant.State.VERIFIED,journal.step(purge,1,participant,PurgeParticipant.Phase.VERIFY,1048576).checkpoint().state());
                for(String table:Fixture.TEMP)assertEquals(0,f.count(table,a.lease().key(),a.request().operationId()),table);
                assertEquals(1,f.faults.fired.get());
            }finally{f.guard.release(purge);}
        }
    }

    @ParameterizedTest @ValueSource(strings={"postgres","mssql","oracle"})
    void unrecognizedOldOperationIsIncompleteAndItsRawDataIsNeverSilentlyDeleted(String provider)throws Exception{
        try(var admin=CacheTenantContext.administrative();var f=new Fixture(provider)){
            var a=f.initial("a");f.guard.release(a.lease());var purge=f.purge("a");
            UUID unknown=UUID.randomUUID();
            try{
                f.addUnknownFacade(a.lease().key(),unknown);
                var participant=new JdbcInitialBootstrapPurgeParticipant(f.guard,f.binding,"JournalRow",2);
                var journal=new JdbcPurgeParticipantJournal(f.guard,2,1);
                assertEquals(PurgeParticipant.State.INCOMPLETE,journal.step(purge,1,participant,PurgeParticipant.Phase.CLOSE,1048576).checkpoint().state());
                assertEquals(1,f.count("cachedb_purge_facade",a.lease().key(),unknown));
                assertEquals(1,f.pairCount("cachedb_initial_bootstrap",a.lease().key()));
            }finally{f.guard.release(purge);}
        }
    }

    @ParameterizedTest @ValueSource(strings={"postgres","mssql","oracle"})
    void realSqlOpenCommitAmbiguityCannotFinalizeNativeStateOrReplaceTheCommittedGrant(String provider)throws Exception{
        try(var admin=CacheTenantContext.administrative();var f=new Fixture(provider)){
            var control=f.readySqlControl("a");var sql=control.sql();var session=control.session();
            try{
                var activation=sql.prepareActivation(session,control.closure(),ProtectionKey.hash("SQL-UNIT-ONLY-runtime-proof"),10);
                assertEquals(9001,activation.range().first(),"Actual durable tombstone inventory exceeds cold source highwater");
                f.faults.arm("UPDATE cachedb_initial_bootstrap",false);
                assertEquals(ProtectionException.Code.STORAGE_UNAVAILABLE,assertThrows(ProtectionException.class,
                        ()->sql.openSql(session,activation)).code());
                f.faults.requireConsumed();
                assertEquals(com.reactor.cachedb.core.protection.InitialTenantBootstrap.Phase.READY,sql.phase(session));
                try(var c=f.sandbox.source.getConnection()){
                    c.setAutoCommit(false);
                    assertEquals("CDBGC",assertThrows(SQLException.class,()->f.guard.authorizeWrite(c,session.request().generation())).getSQLState());
                    c.rollback();
                }
                f.faults.arm("UPDATE cachedb_initial_bootstrap",true);
                assertEquals(ProtectionException.Code.STORAGE_UNAVAILABLE,assertThrows(ProtectionException.class,
                        ()->sql.openSql(session,activation)).code());
                f.faults.requireConsumed();
                assertEquals(com.reactor.cachedb.core.protection.InitialTenantBootstrap.Phase.SQL_OPEN,sql.phase(session));
                assertEquals(activationValues(activation),activationValues(sql.activation(session)));
                try(var c=f.sandbox.source.getConnection()){
                    c.setAutoCommit(false);f.guard.authorizeWrite(c,session.request().generation());c.rollback();
                }
                // SQL-only data operations cannot execute native finalization or GC anymore.
                assertEquals(activationValues(activation),activationValues(sql.prepareActivation(session,control.closure(),
                        ProtectionKey.hash("SQL-UNIT-ONLY-runtime-proof"),10)));
                assertFalse(sql.finalizing(session));
                assertEquals(com.reactor.cachedb.core.protection.InitialTenantBootstrap.Phase.SQL_OPEN,sql.phase(session));
                assertTrue(f.count("cachedb_initial_page",session.lease().key(),session.request().operationId())>0);
                assertEquals(activation.range().last(),f.allocator.initialStatus(session).highwater());
                assertEquals(1,f.scalar("SELECT COUNT(*) FROM cachedb_write_fence WHERE entity_version=9000"));
                assertEquals(3,f.scalar("SELECT COUNT(*) FROM fixture_source"));
                assertEquals(2,f.faults.fired.get());
            }finally{sql.release(session);}
        }
    }

    @ParameterizedTest @ValueSource(strings={"postgres","mssql","oracle"})
    void callerSuppliedCursorCannotAttributeAndEraseAnUnknownOperation(String provider)throws Exception{
        try(var admin=CacheTenantContext.administrative();var f=new Fixture(provider)){
            var key=f.binding.key("a");f.guard.provisionClosed(new Generation(key,1));
            var purge=f.purge("a");UUID unknown=UUID.randomUUID();
            try{
                f.addUnknownFacade(key,unknown);
                var participant=new JdbcInitialBootstrapPurgeParticipant(f.guard,f.binding,"JournalRow",2);
                assertEquals(ProtectionException.Code.OPERATION_CONFLICT,assertThrows(ProtectionException.class,
                        ()->participant.step(new PurgeParticipant.Request(purge,1,PurgeParticipant.Phase.ERASE,
                                "i:"+unknown+":0",128,1048576))).code());
                assertEquals(1,f.count("cachedb_purge_facade",key,unknown));
            }finally{f.guard.release(purge);}
        }
    }

    record SqlRow(Long id,String tenant){}
    record SqlControl(JdbcInitialTenantBootstrap<SqlRow,Long> sql,
            com.reactor.cachedb.core.protection.InitialTenantBootstrap.Session session,
            com.reactor.cachedb.core.protection.InitialTenantBootstrap.Closure closure){}
    static final class Fixture implements AutoCloseable{
        static final List<String> TEMP=List.of("cachedb_initial_page","cachedb_purge_scan","cachedb_purge_work",
                "cachedb_purge_work_budget","cachedb_purge_facade","cachedb_purge_call","cachedb_purge_participant");
        final InitialSqlSandbox sandbox;
        final InitialSqlFaults faults;
        final JdbcTenantPurgeGuard guard;
        final Binding binding;
        final JdbcTenantVersionAllocator allocator;
        Fixture(String provider)throws Exception{
            sandbox=new InitialSqlSandbox(provider);
            try{
                faults=new InitialSqlFaults(sandbox.source);
                try(var c=sandbox.source.getConnection();var q=c.createStatement()){
                    q.setQueryTimeout(5);
                    q.executeUpdate("CREATE TABLE fixture_source(id DECIMAL(19,0) NOT NULL PRIMARY KEY,tenant_id "
                            +(provider.equals("mssql")?"NVARCHAR(64)":"VARCHAR(64)")+" NOT NULL,entity_version DECIMAL(19,0) NOT NULL)");
                    q.executeUpdate("CREATE INDEX fixture_tenant_idx ON fixture_source(tenant_id,id)");
                    q.executeUpdate("CREATE INDEX fixture_version_idx ON fixture_source(tenant_id,entity_version)");
                    q.executeUpdate("INSERT INTO fixture_source(id,tenant_id,entity_version) VALUES (1,'a',5)");
                    q.executeUpdate("INSERT INTO fixture_source(id,tenant_id,entity_version) VALUES (2,'b',6)");
                }
                binding=new Binding(SqlRow.class,"initial-journal-fixture",new SourcePlan("fixture_source","tenant_id","id"),
                        ProtectionKey.hash("private-journal-layout"),1000,128,1048576,true);
                guard=new JdbcTenantPurgeGuard(faults,sandbox.dialect,2,2,Set.of(binding.fingerprint()));
                allocator=new JdbcTenantVersionAllocator(guard,sandbox.dialect,2);
                guard.validateSource(binding);
                try(var c=sandbox.source.getConnection();var q=c.prepareStatement(
                        "INSERT INTO cachedb_write_fence(fence_key,identity_value,entity_version,deleted) VALUES (?, ?,9000,1)")){
                    q.setQueryTimeout(2);q.setString(1,ProtectionKey.hash("retained-sql-test-sentinel"));q.setString(2,"private-fixture");q.executeUpdate();
                }
            }catch(Exception|Error failure){
                try{sandbox.close();}catch(Exception|Error cleanup){failure.addSuppressed(new IllegalStateException("Private fixture cleanup failed"));}
                throw failure;
            }
        }
        com.reactor.cachedb.core.protection.InitialTenantBootstrap.Session initial(String tenant)throws Exception{
            var gen=new Generation(binding.key(tenant),1);guard.provisionClosed(gen);
            var request=new com.reactor.cachedb.core.protection.InitialTenantBootstrap.Request(gen,tenant,UUID.randomUUID(),
                    ProtectionKey.hash("test-operator"),120);
            String plan=ProtectionKey.hash("sql-unit-control-plan"),manifest=ProtectionKey.hash("sql-unit-control-manifest");
            var state=InitialBootstrapState.initial(request,plan,manifest);
            state.phase=com.reactor.cachedb.core.protection.InitialTenantBootstrap.Phase.NATIVE_INVENTORY;
            byte[] encoded=state.encode();
            var lease=guard.acquireInitialBootstrap(gen,binding,request.operationId(),120,c->{
                try(var q=c.prepareStatement("INSERT INTO cachedb_initial_bootstrap(scope_token,tenant_token,operation_id,generation,"
                        +"plan_token,manifest_token,phase_name,revision,payload,payload_bytes) VALUES (?,?,?,1,?,?,'NATIVE_INVENTORY',0,?,?)")){
                    q.setQueryTimeout(2);pair(q,gen.key());q.setString(3,request.operationId().toString());
                    q.setString(4,plan);q.setString(5,manifest);q.setBytes(6,encoded);q.setInt(7,encoded.length);q.executeUpdate();
                }
                return state.phase;
            });
            var session=new com.reactor.cachedb.core.protection.InitialTenantBootstrap.Session(request,lease,plan,manifest);
            allocator.beginInitialInventory(session,binding);
            var journal=new JdbcPurgeScanJournal(guard,sandbox.dialect,2,16,128,1048576).forInitial(session);
            var consumed=new JdbcPurgeScanJournal.Source(ProtectionKey.hash("consumed"),JdbcPurgeScanJournal.Kind.OWNER,"private-owned-locator-"+tenant);
            var pending=new JdbcPurgeScanJournal.Source(ProtectionKey.hash("pending"),JdbcPurgeScanJournal.Kind.NAMESPACE,"private-namespace-"+tenant);
            journal.register(lease,List.of(consumed));
            journal.stage(lease,consumed,new PurgeScanFrame("0","0",true,1,ProtectionKey.hash("sql-control-topology"),"*1\r\n$4\r\nitem\r\n".getBytes(StandardCharsets.UTF_8)));
            journal.consumeVersionInventory(lease,journal.next(lease),1,List.of(new PurgeWorkItem(ProtectionKey.hash("owned-work"),
                    PurgeWorkItem.Kind.ID,("private-id-"+tenant).getBytes(StandardCharsets.UTF_8))),List.of(),0,9000);
            journal.register(lease,List.of(pending));
            journal.stage(lease,pending,new PurgeScanFrame("0","0",true,0,ProtectionKey.hash("sql-control-topology"),"*0\r\n".getBytes(StandardCharsets.UTF_8)));
            var facade=new JdbcPurgeFacadeJournal(guard,sandbox.dialect,2,16,1048576).forInitial(session);
            facade.create(lease,"initial-inventory","private-cursor-"+tenant,("private-payload-"+tenant).getBytes(StandardCharsets.UTF_8),null);
            facade.enterCall(lease,UUID.randomUUID()); // Simulates a process killed before leaveCall.
            guard.inInitialBootstrapTransaction(session,c->{
                try(var q=c.prepareStatement("INSERT INTO cachedb_initial_page(scope_token,tenant_token,operation_id,page_no,payload,payload_bytes,is_applied,is_verified) VALUES (?,?,?,0,?,1,0,0)")){
                    pair(q,gen.key());q.setString(3,request.operationId().toString());q.setBytes(4,new byte[]{1});q.executeUpdate();
                }
                try(var q=c.prepareStatement("INSERT INTO cachedb_owner_onboarding(scope_token,tenant_token,payload,payload_bytes) VALUES (?,?,?,1)")){
                    pair(q,gen.key());q.setBytes(3,new byte[]{1});q.executeUpdate();
                }
                try(var q=c.prepareStatement("INSERT INTO cachedb_namespace_bootstrap(roster_token,binding_token,mapping_token,scope_token,tenant_token,generation,redis_run_id,incarnation_id) VALUES (?,?,?,?,?,1,?,?)")){
                    q.setString(1,ProtectionKey.hash("retained-roster-"+tenant));q.setString(2,binding.fingerprint());q.setString(3,plan);
                    q.setString(4,gen.key().scopeToken());q.setString(5,gen.key().tenantToken());
                    q.setString(6,"1".repeat(40));q.setString(7,UUID.randomUUID().toString());q.executeUpdate();
                }
                return null;
            });
            return session;
        }
        SqlControl readySqlControl(String tenant)throws Exception{
            try(var c=sandbox.source.getConnection();var q=c.createStatement()){
                q.setQueryTimeout(2);q.executeUpdate("INSERT INTO fixture_source(id,tenant_id,entity_version) VALUES (99,'a',5000)");
            }
            var metadata=new com.reactor.cachedb.core.model.EntityMetadata<SqlRow,Long>(){
                public String entityName(){return "JournalRow";}public String tableName(){return "fixture_source";}
                public String redisNamespace(){return binding.scope();}public String idColumn(){return "id";}
                public Class<SqlRow> entityType(){return SqlRow.class;}public java.util.function.Function<SqlRow,Long> idAccessor(){return SqlRow::id;}
                public boolean tenantPurgeEnabled(){return true;}public String tenantFieldName(){return "tenant_id";}
                public String extractTenantId(SqlRow row){return row.tenant();}
                public List<String> columns(){return List.of("id","tenant_id");}
                public Set<String> indexedColumns(){return Set.of("tenant_id");}
                public Map<String,String> columnTypes(){return Map.of("id","java.lang.Long","tenant_id","java.lang.String");}
                public List<com.reactor.cachedb.core.model.RelationDefinition> relations(){return List.of();}
            };
            var codec=new com.reactor.cachedb.core.model.EntityCodec<SqlRow>(){
                public String toRedisValue(SqlRow row){return row.id()+"|"+row.tenant();}
                public SqlRow fromRedisValue(String encoded){int delimiter=encoded.indexOf('|');return new SqlRow(Long.valueOf(encoded.substring(0,delimiter)),encoded.substring(delimiter+1));}
                public Map<String,Object> toColumns(SqlRow row){return Map.of("id",row.id(),"tenant_id",row.tenant());}
                public SqlRow fromColumns(Map<String,Object> values){return new SqlRow(new java.math.BigDecimal(values.get("id").toString()).longValueExact(),(String)values.get("tenant_id"));}
            };
            var ids=JdbcSourceOwnerOnboarding.longIds();
            var window=new InitialSourceHotWindow<>(binding,metadata,codec,ids,ids.formatToken(),1L,1L,1,65536,8192,131072,
                    InitialSourceHotWindow.Profile.POINT_NO_QUOTA);
            var sql=new JdbcInitialTenantBootstrap<>(guard,allocator,window,ids,ProtectionKey.hash("SQL-UNIT-ONLY-manifest"),2,Set.of(window.fingerprint()));
            var generation=new Generation(binding.key(tenant),1);guard.provisionClosed(generation);
            var request=new com.reactor.cachedb.core.protection.InitialTenantBootstrap.Request(generation,tenant,UUID.randomUUID(),ProtectionKey.hash("operator"),120);
            var session=sql.acquire(request);
            var closure=new com.reactor.cachedb.core.protection.InitialTenantBootstrap.Closure(
                    new TenantReadFence.Evidence(TenantReadFence.State.CLOSED,"SQL-UNIT-CONTROL-NOT-NATIVE",
                            ProtectionKey.hash("SQL-UNIT-CONTROL-NOT-NATIVE"),1,1),"1".repeat(40),ProtectionKey.hash("SQL-UNIT-ONLY-roster"));
            sql.closed(session,closure);
            var journal=new JdbcPurgeScanJournal(guard,sandbox.dialect,2,16,128,1048576).forInitial(session);
            for(var kind:List.of(JdbcPurgeScanJournal.Kind.OWNER,JdbcPurgeScanJournal.Kind.NAMESPACE)){
                var source=new JdbcPurgeScanJournal.Source(ProtectionKey.hash(kind.name()),kind,"private-inventory-locator");
                journal.register(session.lease(),List.of(source));
                journal.stage(session.lease(),source,new PurgeScanFrame("0","0",true,0,ProtectionKey.hash("SQL-UNIT-ONLY-topology"),"*0\r\n".getBytes(StandardCharsets.UTF_8)));
                journal.consumeVersionInventory(session.lease(),journal.next(session.lease()),0,List.of(),List.of(),0,0);
            }
            sql.nativeInventoryComplete(session);
            for(int n=0;n<4&&sql.phase(session)==com.reactor.cachedb.core.protection.InitialTenantBootstrap.Phase.DURABLE_VERSIONS;n++)
                sql.scanDurableVersions(session);
            assertEquals(com.reactor.cachedb.core.protection.InitialTenantBootstrap.Phase.SOURCE_VERSIONS,sql.phase(session));
            sql.sourceInventory(session);
            var page=sql.nextHotPage(session);assertEquals(1,page.rows().size());sql.acknowledgeHotPage(session,page);
            var verification=sql.nextVerificationPage(session).orElseThrow();sql.acknowledgeVerificationPage(session,verification);
            assertTrue(sql.nextVerificationPage(session).isEmpty());
            sql.verified(session,ProtectionKey.hash("SQL-UNIT-ONLY-runtime-proof"));
            new JdbcPurgeFacadeJournal(guard,sandbox.dialect,2,16,1048576).forInitial(session)
                    .create(session.lease(),"initial-inventory","private-cursor",new byte[]{1},null);
            return new SqlControl(sql,session,closure);
        }
        Lease purge(String tenant){return guard.acquireForTenant(binding,tenant,new PurgeOptions(UUID.randomUUID(),tenant,
                ProtectionKey.hash("test-operator"),false,128,120,5000,PurgeScope.ACTIVE_STORES_RETAINING_SECURITY_METADATA),false);}
        void redisPhaseSqlControl(Lease lease){
            var before=guard.load(lease);
            guard.save(lease,before,before.advance(Stage.REDIS,ProtectionKey.hash("SQL-UNIT-CONTROL-NOT-NATIVE-CERTIFICATE"),
                    TenantPurgeProtocol.START,null,0,0,0,0,0,null));
        }
        void addUnknownFacade(ProtectionKey key,UUID operation)throws Exception{
            try(var c=sandbox.source.getConnection();var q=c.prepareStatement("INSERT INTO cachedb_purge_facade(scope_token,tenant_token,operation_id,checkpoint_id,revision,payload,payload_bytes,is_done,examined_count,removed_count) VALUES (?,?,?,'unknown-legacy',0,?,1,0,0,0)")){
                pair(q,key);q.setString(3,operation.toString());q.setBytes(4,new byte[]{7});q.executeUpdate();
            }
        }
        byte[] pendingBytes(com.reactor.cachedb.core.protection.InitialTenantBootstrap.Session session)throws Exception{
            try(var c=sandbox.source.getConnection();var q=c.prepareStatement("SELECT pending_frame FROM cachedb_purge_scan WHERE scope_token=? AND tenant_token=? AND operation_id=? AND source_token=?")){
                pair(q,session.lease().key());q.setString(3,session.request().operationId().toString());q.setString(4,ProtectionKey.hash("pending"));
                try(var r=q.executeQuery()){assertTrue(r.next());return r.getBytes(1);}
            }
        }
        long count(String table,ProtectionKey key,UUID operation)throws Exception{
            if(!TEMP.contains(table))throw new IllegalArgumentException("Fixture table rejected");
            try(var c=sandbox.source.getConnection();var q=c.prepareStatement("SELECT COUNT(*) FROM "+table+" WHERE scope_token=? AND tenant_token=? AND operation_id=?")){
                q.setQueryTimeout(2);pair(q,key);q.setString(3,operation.toString());try(var r=q.executeQuery()){r.next();return r.getLong(1);}
            }
        }
        long pairCount(String table,ProtectionKey key)throws Exception{
            if(!Set.of("cachedb_initial_bootstrap","cachedb_owner_onboarding","cachedb_tenant_guard","cachedb_tenant_allocator","cachedb_namespace_bootstrap").contains(table))
                throw new IllegalArgumentException("Fixture table rejected");
            try(var c=sandbox.source.getConnection();var q=c.prepareStatement("SELECT COUNT(*) FROM "+table+" WHERE scope_token=? AND tenant_token=?")){
                q.setQueryTimeout(2);pair(q,key);try(var r=q.executeQuery()){r.next();return r.getLong(1);}
            }
        }
        long scalar(String sql)throws Exception{
            try(var c=sandbox.source.getConnection();var q=c.prepareStatement(sql)){q.setQueryTimeout(2);try(var r=q.executeQuery()){r.next();return r.getLong(1);}}
        }
        static void pair(PreparedStatement q,ProtectionKey key)throws SQLException{q.setString(1,key.scopeToken());q.setString(2,key.tenantToken());}
        public void close()throws Exception{sandbox.close();}
    }
    private static List<Object> activationValues(com.reactor.cachedb.core.protection.InitialTenantBootstrap.Activation a){
        var r=a.range();
        return List.of(a.token(),a.planToken(),a.manifestToken(),a.inventoryToken(),a.window(),a.runtimeProof(),
                r.key(),r.generation(),r.controlEpoch(),r.reservationEpoch(),r.first(),r.last(),r.request(),r.runId(),r.inventory());
    }
}
