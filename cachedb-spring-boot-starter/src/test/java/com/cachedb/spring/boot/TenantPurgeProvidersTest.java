package com.reactor.cachedb.spring.boot;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.*;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.jdbc.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Real provider guard/journal tests, NOT proof that the existing flusher/Redis protocol is wired.
 * V019 migrations must already exist in disposable provider fixtures; missing schema fails.
 */
@EnabledIfSystemProperty(named="cachedb.snapshot.providers", matches="true")
@Timeout(60)
class TenantPurgeProvidersTest {
    @ParameterizedTest @ValueSource(strings={"postgres","mssql","oracle"})
    void businessTransactionLockBlocksPurgeUntilWriterCommits(String provider) throws Exception {
        var runner = Executors.newSingleThreadExecutor();
        try (var fixture = new Fixture(provider); var admin = CacheTenantContext.administrative();
                var writer = fixture.source.getConnection()) {
            fixture.provision();
            writer.setAutoCommit(false);
            fixture.guard.authorizeWrite(writer, fixture.generation(1));
            var attempted = new CountDownLatch(1);
            Future<Lease> closing = runner.submit(() -> {
                try (var authority = CacheTenantContext.administrative()) {
                    attempted.countDown(); return fixture.guard.acquire(fixture.key, fixture.options(false), false, fixture.binding.fingerprint());
                }
            });
            assertTrue(attempted.await(5, TimeUnit.SECONDS));
            try {
                assertThrows(TimeoutException.class, () -> closing.get(100, TimeUnit.MILLISECONDS));
            } finally { writer.commit(); }
            Lease lease = closing.get(5, TimeUnit.SECONDS);
            assertEquals(Stage.FENCING, fixture.guard.load(lease).stage());
            assertEquals("CDBGC", assertThrows(SQLException.class,
                    () -> fixture.guard.authorizeWrite(writer, fixture.generation(1))).getSQLState());
            writer.rollback(); fixture.guard.release(lease);
        } finally {
            runner.shutdownNow(); assertTrue(runner.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest @ValueSource(strings={"postgres","mssql","oracle"})
    void reenableRemainsClosedUntilMigrationAndRangeAreVerified(String provider) throws Exception {
        try (var f = new Fixture(provider); var admin = CacheTenantContext.administrative()) {
            f.provision();
            Lease lease = f.guard.acquire(f.key, f.options(true), false, f.binding.fingerprint());
            Progress p = f.redisStage(lease);
            f.guard.validateSource(f.binding);
            p = f.guard.save(lease,p,p.advance(Stage.SOURCE,p.inventoryToken(),"-",null,0,0,0,0,0,null));
            p = f.guard.deleteSource(lease,f.binding,"a",128);assertEquals(Stage.VERIFYING,p.stage());
            p = f.guard.save(lease, p, p.advance(Stage.COMPLETE, p.inventoryToken(), "-", null, 0, 0, 0, 0, 0, Instant.now()));
            p = f.guard.prepareReenable(lease); assertEquals(2, p.newGeneration());
            assertEquals(2, f.guard.prepareReenable(lease).newGeneration());
            assertEquals(ProtectionException.Code.MIGRATION_REQUIRED,
                    assertThrows(ProtectionException.class,()->f.guard.openPreparedGeneration(lease)).code());
            try(var connection=f.source.getConnection()){
                connection.setAutoCommit(false);
                assertEquals("CDBGG",assertThrows(SQLException.class,
                        ()->f.guard.authorizeWrite(connection,f.generation(1))).getSQLState());
                connection.rollback();
                assertEquals("CDBGC",assertThrows(SQLException.class,
                        ()->f.guard.authorizeWrite(connection,f.generation(2))).getSQLState());
                connection.rollback();
            }
            f.guard.release(lease);
        }
    }

    @ParameterizedTest @ValueSource(strings={"postgres","mssql","oracle"})
    void indexedSourceDeleteIsBoundedAndOtherTenantRowsAndGuardMetadataSurvive(String provider) throws Exception {
        try (var f = new Fixture(provider); var admin = CacheTenantContext.administrative()) {
            f.provision(); f.seed("a", 8); f.seed("A", 2); f.seed("b", 2); f.guard.validateSource(f.binding);
            Lease lease = f.guard.acquire(f.key, f.options(true), false, f.binding.fingerprint());
            Progress p = f.redisStage(lease);
            p = f.guard.save(lease, p, p.advance(Stage.SOURCE, p.inventoryToken(), "-", null, 0, 0, 0, 0, 0, null));
            p = f.guard.deleteSource(lease, f.binding, "a", 3);
            assertEquals(3, p.sourceRows()); assertEquals(5, f.rows("a")); assertEquals(2, f.rows("b")); assertEquals(2, f.rows("A"));
            for (int i = 0; i < 4 && p.stage() == Stage.SOURCE; i++)
                p = f.guard.deleteSource(lease, f.binding, "a", 3);
            assertEquals(Stage.VERIFYING, p.stage()); assertEquals(8, p.sourceRows());
            assertEquals(0, f.rows("a")); assertEquals(2, f.rows("b"));
            // The permanent guard remains CLOSED; no metadata fence DELETE occurred.
            try (var c = f.source.getConnection()) {
                c.setAutoCommit(false);
                assertEquals("CDBGC", assertThrows(SQLException.class,
                        () -> f.guard.authorizeWrite(c, f.generation(1))).getSQLState());
                c.rollback();
            }
            f.guard.release(lease);
        }
    }

    @ParameterizedTest @ValueSource(strings={"postgres","mssql","oracle"})
    void expiredLeaseTakeoverRejectsStaleOwnerAndStaleReleaseCannotClearNewLease(String provider) throws Exception {
        try (var f = new Fixture(provider); var admin = CacheTenantContext.administrative()) {
            f.provision();
            Lease old = f.guard.acquire(f.key, f.options(false), false, f.binding.fingerprint());
            // Disposable fixture fault injection: expiry only, NEVER generation/fence deletion.
            try (var c = f.source.getConnection(); var q = c.prepareStatement(
                    "UPDATE cachedb_tenant_guard SET lease_until=NULL WHERE scope_token=? AND tenant_token=?")) {
                q.setString(1, f.key.scopeToken()); q.setString(2, f.key.tenantToken()); assertEquals(1, q.executeUpdate());
            }
            Lease current = f.guard.acquire(f.key, f.options(false), false, f.binding.fingerprint());
            assertTrue(current.epoch() > old.epoch());
            assertEquals(ProtectionException.Code.LEASE_LOST,
                    assertThrows(ProtectionException.class, () -> f.guard.load(old)).code());
            f.guard.release(old); f.guard.renew(current); f.guard.release(current);
        }
    }

    @ParameterizedTest @ValueSource(strings={"postgres","mssql","oracle"})
    void resumeRejectsSourceOrLayoutDriftBeforeAnyNewMutation(String provider) throws Exception {
        try (var f = new Fixture(provider); var admin = CacheTenantContext.administrative()) {
            f.provision();
            Lease lease = f.guard.acquire(f.key, f.options(false), false, f.binding.fingerprint());
            f.guard.release(lease);
            assertEquals(ProtectionException.Code.OPERATION_CONFLICT, assertThrows(ProtectionException.class,
                    () -> f.guard.acquire(f.key, f.options(false), false, ProtectionKey.hash("different-layout"))).code());
        }
    }

    @ParameterizedTest @ValueSource(strings={"postgres","mssql","oracle"})
    void pendingFrameIsDurableBeforeCursorAdvanceAndUnknownOwnershipStaysIncomplete(String provider) throws Exception {
        try(var f=new Fixture(provider);var admin=CacheTenantContext.administrative()) {
            f.provision();Lease lease=f.guard.acquire(f.key,f.options(false),false,f.binding.fingerprint());
            var journal=new JdbcPurgeScanJournal(f.guard,f.dialect,2,32,1000,1024*1024);
            var source=new JdbcPurgeScanJournal.Source(ProtectionKey.hash("owners"),JdbcPurgeScanJournal.Kind.OWNER,"owner-scan");
            journal.register(lease,List.of(source));
            byte[] raw="*2\r\n$1\r\n0\r\n*2\r\n$1\r\na\r\n$1\r\nb\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            var frame=new PurgeScanFrame("0","0",true,2,ProtectionKey.hash("topology"),raw);
            journal.stage(lease,source,frame);var pending=journal.next(lease);
            assertEquals(0,pending.offset());assertEquals("0",pending.cursor());assertArrayEquals(raw,pending.frame().encoded());
            journal.consume(lease,pending,1,List.of(),List.of(),1);
            // A new helper instance reloads the committed frame, cursor and partial offset.
            var resumed=new JdbcPurgeScanJournal(f.guard,f.dialect,2,32,1000,1024*1024);
            var remaining=resumed.next(lease);assertEquals(1,remaining.offset());assertTrue(remaining.failed());
            assertEquals(pending.pageId(),remaining.pageId());assertArrayEquals(raw,remaining.frame().encoded());
            resumed.consume(lease,remaining,1,List.of(),List.of(),0);
            assertFalse(resumed.discoveryComplete(lease));assertTrue(resumed.next(lease).failed());
            f.guard.markRequiresMigration(lease);assertTrue(f.guard.load(lease).receipt(f.key).requiresMigration());
            f.guard.release(lease);
        }
    }

    @ParameterizedTest @ValueSource(strings={"postgres","mssql","oracle"})
    void redisOnlyPurgeCannotEnterSqlEraseOrReopenRetainedHistory(String provider) throws Exception {
        try(var f=new Fixture(provider);var admin=CacheTenantContext.administrative()) {
            f.provision();f.seed("a",2);f.guard.validateSource(f.binding);
            Lease lease=f.guard.acquire(f.key,f.options(false),false,f.binding.fingerprint());
            Progress p=f.redisStage(lease);
            Progress snapshot=p;
            assertThrows(ProtectionException.class,()->f.guard.deleteSource(lease,f.binding,"a",128));
            assertThrows(ProtectionException.class,()->f.guard.save(lease,snapshot,
                    snapshot.advance(Stage.SOURCE,snapshot.inventoryToken(),"-",null,0,0,0,0,0,null)));
            p=f.guard.save(lease,p,p.advance(Stage.VERIFYING,p.inventoryToken(),"-",null,0,0,0,0,0,null));
            p=f.guard.save(lease,p,p.advance(Stage.COMPLETE,p.inventoryToken(),"-",null,0,0,0,0,0,Instant.now()));
            assertEquals(ProtectionException.Code.MIGRATION_REQUIRED,
                    assertThrows(ProtectionException.class,()->f.guard.prepareReenable(lease)).code());
            assertEquals(2,f.rows("a"));assertEquals(0,f.guard.load(lease).sourceRows());
            f.guard.release(lease);
        }
    }


    @ParameterizedTest @ValueSource(strings={"postgres","mssql","oracle"})
    void inventoryAndReservationsAreDurableBoundedAndNeverReuseBurnedNumbers(String provider) throws Exception {
        try(var f=new Fixture(provider);var admin=CacheTenantContext.administrative()) {
            f.provision();f.seed("a",1);
            try(var c=f.source.getConnection();var q=c.prepareStatement("UPDATE "+f.base.table+" SET entity_version=700 WHERE tenant_id=?")){
                q.setString(1,"a");assertEquals(1,q.executeUpdate());
            }
            Lease lease=f.guard.acquire(f.key,f.options(true),false,f.binding.fingerprint());
            var allocator=new JdbcTenantVersionAllocator(f.guard,f.dialect,2);
            allocator.beginInventory(lease,f.binding,1);
            assertEquals(ProtectionException.Code.MIGRATION_REQUIRED,assertThrows(ProtectionException.class,
                    ()->allocator.reserveClosed(lease,1,4,UUID.randomUUID(),0,"a".repeat(40))).code());
            var journal=new JdbcPurgeScanJournal(f.guard,f.dialect,2,16,128,4096);
            for(var kind:List.of(JdbcPurgeScanJournal.Kind.OWNER,JdbcPurgeScanJournal.Kind.NAMESPACE)){
                var source=new JdbcPurgeScanJournal.Source(ProtectionKey.hash(kind.name()),kind,"fixture");
                journal.register(lease,List.of(source));
                byte[] raw="*2\r\n$1\r\n0\r\n*1\r\n$1\r\nx\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                journal.stage(lease,source,new PurgeScanFrame("0","0",true,1,ProtectionKey.hash("fixture topology"),raw));
                var pending=journal.next(lease);assertNotNull(pending);
                journal.consumeVersionInventory(lease,pending,1,List.of(),List.of(),0,900);
            }
            assertTrue(journal.discoveryComplete(lease));
            JdbcTenantVersionAllocator.Status state=null;
            for(int page=0;page<10000;page++){
                state=allocator.scanDurableFences(lease,1,128);if(state.durableComplete())break;
            }
            assertNotNull(state);assertTrue(state.durableComplete());
            state=allocator.probeSourceMaximum(lease,f.binding,1,"a","entity_version");
            assertTrue(state.sqlMaximum()>=700);
            state=allocator.sealInventory(lease,f.binding,1,"entity_version");assertTrue(state.highwater()>=900);
            long high=state.highwater();var request=UUID.randomUUID();
            var first=allocator.reserveClosed(lease,1,4,request,0,"a".repeat(40));
            assertEquals(high+1,first.first());assertEquals(high+4,first.last());
            assertEquals(first.first(),allocator.reserveClosed(lease,1,4,request,0,"a".repeat(40)).first());
            assertEquals(ProtectionException.Code.RESERVATION_CONFLICT,assertThrows(ProtectionException.class,
                    ()->allocator.reserveClosed(lease,1,4,UUID.randomUUID(),0,"a".repeat(40))).code());
            var next=allocator.reserveClosed(lease,1,4,UUID.randomUUID(),1,"a".repeat(40));
            assertEquals(first.last()+1,next.first());
            assertEquals(ProtectionException.Code.RESERVATION_CONFLICT,assertThrows(ProtectionException.class,
                    ()->allocator.reserveClosed(lease,1,4,request,0,"a".repeat(40))).code());
            assertEquals(next.last(),new JdbcTenantVersionAllocator(f.guard,f.dialect,2).statusClosed(lease,1).highwater());
            assertEquals(1,f.rows("a"));
            // Disposable boundary fault injection; never a production highwater rewrite.
            f.guard.inClosedVersionTransaction(lease,1,(c,epoch)->{
                try(var q=c.prepareStatement("UPDATE cachedb_tenant_allocator SET highwater=? WHERE scope_token=? AND tenant_token=?")){
                    q.setQueryTimeout(2);q.setLong(1,TenantVersionRange.MAX_VERSION-1);
                    q.setString(2,f.key.scopeToken());q.setString(3,f.key.tenantToken());assertEquals(1,q.executeUpdate());
                }return null;
            });
            var last=allocator.reserveClosed(lease,1,1,UUID.randomUUID(),2,"a".repeat(40));
            assertEquals(TenantVersionRange.MAX_VERSION,last.last());
            assertEquals(ProtectionException.Code.VERSION_LIMIT,assertThrows(ProtectionException.class,
                    ()->allocator.reserveClosed(lease,1,1,UUID.randomUUID(),3,"a".repeat(40))).code());
            f.guard.release(lease);
        }
    }


    @ParameterizedTest @ValueSource(strings={"postgres","mssql","oracle"})
    void realFlusherRequiresOriginalStampAndSameBusinessTransactionGuard(String provider)throws Exception {
        try(var f=new Fixture(provider);var admin=CacheTenantContext.administrative()){
            f.provision();
            var metadata=new com.reactor.cachedb.core.model.EntityMetadata<Map<String,String>,String>(){
                public String entityName(){return f.base.table;}public String tableName(){return f.base.table;}
                public String redisNamespace(){return f.base.table;}public String idColumn(){return "id";}
                @SuppressWarnings("unchecked") public Class<Map<String,String>> entityType(){return (Class<Map<String,String>>)(Class<?>)Map.class;}
                public java.util.function.Function<Map<String,String>,String> idAccessor(){return row->row.get("id");}
                public List<String> columns(){return List.of("id","tenant_id","value_text","entity_version");}
                public List<com.reactor.cachedb.core.model.RelationDefinition> relations(){return List.of();}
                public String tenantFieldName(){return "tenant_id";}public String extractTenantId(Map<String,String> row){return row.get("tenant_id");}
                public boolean tenantPurgeEnabled(){return true;}
            };
            var codec=new com.reactor.cachedb.core.model.EntityCodec<Map<String,String>>(){
                public String toRedisValue(Map<String,String> row){return "{}";}
                public Map<String,String> fromRedisValue(String value){throw new UnsupportedOperationException();}
                public Map<String,Object> toColumns(Map<String,String> row){return new LinkedHashMap<>(row);}
            };
            f.base.registry.register(metadata,codec,com.reactor.cachedb.core.cache.CachePolicy.defaults(),null,null);
            var flusher=f.base.flusher();
            var op=new com.reactor.cachedb.core.queue.QueuedWriteOperation(com.reactor.cachedb.core.model.OperationType.UPSERT,
                    f.base.table,f.base.table,f.base.table,null,"id","entity_version",null,"protected-id",
                    Map.of("id","protected-id","tenant_id","a","value_text","value","entity_version","100"),100,Instant.EPOCH,null,null,0);
            assertEquals("CDBGM",assertThrows(SQLException.class,()->flusher.flush(op)).getSQLState());assertEquals(0,f.rows("a"));
            var envelope=new QueuedGenerationEnvelope(op,Map.of(QueuedGenerationEnvelope.identity(op),f.generation(1)));
            try(var scope=TenantWriteStampContext.restore(List.of(envelope))){flusher.flush(op);}
            assertEquals(1,f.rows("a"));assertNull(TenantWriteStampContext.current(op));
            try(var c=f.source.getConnection();var q=c.prepareStatement(
                    "SELECT entity_version FROM "+f.base.table+" WHERE id=?")){
                q.setString(1,"protected-id");q.setQueryTimeout(5);
                try(var rows=q.executeQuery()){
                    assertTrue(rows.next());assertEquals(100,rows.getLong(1));assertFalse(rows.next());
                }
            }
            Lease lease=f.guard.acquire(f.key,f.options(false),false,f.binding.fingerprint());
            try(var scope=TenantWriteStampContext.restore(List.of(envelope))){
                assertEquals("CDBGC",assertThrows(SQLException.class,()->flusher.flush(op)).getSQLState());
            }
            // Component-only fault injection simulates a later durable generation, NOT the Redis-only reenable API.
            try(var c=f.source.getConnection();var q=c.prepareStatement(
                    "UPDATE cachedb_tenant_guard SET generation=2,gate_state='OPEN' WHERE scope_token=? AND tenant_token=?")){
                q.setString(1,f.key.scopeToken());q.setString(2,f.key.tenantToken());assertEquals(1,q.executeUpdate());
            }
            try(var scope=TenantWriteStampContext.restore(List.of(envelope))){
                assertEquals("CDBGG",assertThrows(SQLException.class,()->flusher.flush(op)).getSQLState());
            }
            assertEquals(1,f.rows("a"));f.guard.release(lease);
        }
    }

    @ParameterizedTest @ValueSource(strings={"postgres","mssql","oracle"})
    void participantCheckpointAndNativeKeyCountsAreOneCommitAndVerifiedIsReattested(String provider)throws Exception {
        try(var f=new Fixture(provider);var admin=CacheTenantContext.administrative()){
            f.provision();f.seed("a",2);Lease lease=f.guard.acquire(f.key,f.options(false),false,f.binding.fingerprint());
            f.redisStage(lease);
            var calls=new java.util.concurrent.atomic.AtomicInteger();
            String token=ProtectionKey.hash("identity position"),layout=ProtectionKey.hash("reviewed native layout");
            var participant=new PurgeParticipant(){
                public Declaration declaration(){return new Declaration("native-redis-identities",layout,Set.of("Row"),false);}
                public Result step(Request request){
                    calls.incrementAndGet();
                    if(request.checkpoint().isEmpty())return new Result(State.MORE,"e:"+token,1,7,"");
                    assertEquals("e:"+token,request.checkpoint());
                    return new Result(State.VERIFIED,request.checkpoint(),0,0,ProtectionKey.hash("live closure attestation"));
                }
            };
            var journal=new JdbcPurgeParticipantJournal(f.guard,2,1);
            var first=journal.step(lease,1,participant,PurgeParticipant.Phase.ERASE,1048576);
            assertEquals("e:"+token,first.checkpoint().cursor());assertEquals(1,first.progress().redisRows());assertEquals(7,first.progress().redisKeys());
            var resumed=new JdbcPurgeParticipantJournal(f.guard,2,1);
            var verified=resumed.step(lease,1,participant,PurgeParticipant.Phase.ERASE,1048576);
            assertEquals(PurgeParticipant.State.VERIFIED,verified.checkpoint().state());assertEquals(7,verified.checkpoint().removed());
            var again=resumed.step(lease,1,participant,PurgeParticipant.Phase.ERASE,1048576);
            assertEquals(3,calls.get());assertEquals(1,again.progress().redisRows());assertEquals(7,again.progress().redisKeys());
            assertEquals(0,again.progress().sourceRows());assertEquals(2,f.rows("a"));
            assertThrows(ProtectionException.class,()->resumed.step(lease,2,participant,PurgeParticipant.Phase.ERASE,1048576));
            assertEquals(3,calls.get());f.guard.release(lease);
        }
    }

    static final class Fixture implements AutoCloseable {
        final WriteFenceProvidersIntegrationTest.Fixture base;
        final com.zaxxer.hikari.HikariDataSource source;
        final JdbcTenantPurgeGuard guard;
        final JdbcTenantPurgeDialect dialect;
        final Binding binding;
        final ProtectionKey key;
        final UUID operation = UUID.randomUUID();
        Fixture(String provider) throws Exception {
            base = new WriteFenceProvidersIntegrationTest.Fixture(provider);
            try {
                source = base.source;
                dialect = JdbcTenantPurgeDialect.valueOf(provider.toUpperCase(Locale.ROOT));
                binding = new Binding(String.class, base.table, new SourcePlan(base.table, "tenant_id", "id"),
                        ProtectionKey.hash("SQL-guard-fixture-layout"), 1_000_000, 128, 2 * 1024 * 1024, true);
                guard = new JdbcTenantPurgeGuard(source, dialect, 2, 4, Set.of(binding.fingerprint()));
                key = binding.key("a");
                try (var c = source.getConnection(); var q = c.createStatement()) {
                    q.setQueryTimeout(5);
                    q.executeUpdate("ALTER TABLE " + base.table + " ADD tenant_id " + (provider.equals("mssql") ? "NVARCHAR(64)" : "VARCHAR(64)"));
                    q.executeUpdate("CREATE INDEX ti_" + base.table.substring(3) + " ON " + base.table + " (tenant_id,id)");
                    q.executeUpdate("CREATE INDEX tv_" + base.table.substring(3) + " ON " + base.table + " (tenant_id,entity_version)");
                }
            } catch (Exception | Error failure) {
                FixtureResources.closeOnFailure(failure, base.sourceOwner);
                throw failure;
            }
        }
        void provision() { guard.provisionClosed(generation(1)); guard.openInitialGeneration(generation(1)); }
        Generation generation(long value) { return new Generation(key, value); }
        PurgeOptions options(boolean includeSource) {
            return new PurgeOptions(operation, "a", ProtectionKey.hash("operator"), includeSource, 128, 120, 30_000, PurgeScope.ACTIVE_STORES_RETAINING_SECURITY_METADATA);
        }
        Progress redisStage(Lease lease) {
            Progress p = guard.load(lease);
            return guard.save(lease, p, p.advance(Stage.REDIS, ProtectionKey.hash("fixture-inventory"),
                    "-", null, 0, 0, 0, 0, 0, null));
        }
        void seed(String tenant, int count) throws SQLException {
            try (var c = source.getConnection(); var q = c.prepareStatement("INSERT INTO " + base.table
                    + " (id,value_text,entity_version,tenant_id) VALUES (?,?,1,?)")) {
                q.setQueryTimeout(5);
                for (int i = 0; i < count; i++) {
                    q.setString(1, ProtectionKey.hash(tenant) + i); q.setString(2, "value"); q.setString(3, tenant); q.addBatch();
                }
                q.executeBatch();
            }
        }
        long rows(String tenant) throws SQLException {
            try (var c = source.getConnection(); var q = c.prepareStatement(
                    "SELECT COUNT(*) FROM " + base.table + " WHERE " + dialect.tenantPredicate(binding.source()))) {
                q.setQueryTimeout(5); dialect.bindTenant(q, tenant, 1);
                try (var r = q.executeQuery()) { assertTrue(r.next()); return r.getLong(1); }
            }
        }
        @Override public void close() throws Exception {
            if (!base.table.matches("wf_[0-9a-f]{20}")) throw new IllegalStateException("Unsafe fixture table");
            try (base.sourceOwner; var c = source.getConnection(); var q = c.createStatement()) {
                q.setQueryTimeout(5); q.executeUpdate("DROP TABLE " + base.table);
                // Do NOT call legacy fixture cleanup: all durable metadata fences remain untouched.
            }
        }
    }
}
