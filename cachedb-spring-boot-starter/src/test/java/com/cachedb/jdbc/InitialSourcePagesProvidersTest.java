package com.reactor.cachedb.jdbc;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.config.ResourceLimits;
import com.reactor.cachedb.core.crypto.*;
import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.InitialTenantBootstrap.*;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.*;
import com.reactor.cachedb.core.registry.DefaultEntityRegistry;
import com.reactor.cachedb.core.queue.*;
import com.reactor.cachedb.spring.boot.TestFixtureSettings;
import com.zaxxer.hikari.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Actual JDBC component proof, NOT a runtime closure/activation certificate.
 * Uses only its own random source table and existing retained read-only security metadata.
 * No shared migration install or legacy fixture/global fence cleanup.
 */
@EnabledIfSystemProperty(named="cachedb.snapshot.providers", matches="true")
@Timeout(60)
class InitialSourcePagesProvidersTest {
    record Row(Long id, String tenant, String secret) {}

    @ParameterizedTest @ValueSource(strings={"postgres","mssql","oracle"})
    void sourcePageCapturesCiphertextOnceAndPreservesAuthenticatedNoncesOnRetry(String provider) throws Exception {
        try (var f = new Fixture(provider, "0", false); var c = f.source.getConnection()) {
            f.seed(1, "a", 7, "private-one"); f.seed(2, "a", 8, "private-two");
            f.seed(3, "A", 9000, "foreign"); f.seed(99, "a", 5000, "cold");
            c.setAutoCommit(false); var window = f.window(1L, 2L, 2, 65536);
            var pages = f.pages(window); pages.validate(c); var session = f.session(window);
            assertArrayEquals(new long[]{5000,2}, pages.sourceExtremes(c,session));
            var page = pages.capture(c,session,null,0);
            assertEquals(List.of(1L,2L),page.rows().stream().map(InitialTenantBootstrap.Row::id).toList());
            assertTrue(page.last()); assertEquals(2,f.encodes.get());
            byte[] encoded = InitialBootstrapPageCodec.encode(window,page);
            String journal = new String(encoded,StandardCharsets.ISO_8859_1);
            assertFalse(journal.contains("private-one")); assertFalse(journal.contains("private-two"));
            assertFalse(journal.contains(ProtectionKey.hash("private-one")));
            var retry = InitialBootstrapPageCodec.decode(window,session,encoded);
            assertEquals(page,retry);
            pages.revalidate(c,session,retry);
            assertEquals(2,f.encodes.get(),"Retry must not encrypt again or change random GCM nonces");
            assertArrayEquals(encoded,InitialBootstrapPageCodec.encode(window,retry));
            byte[] altered = encoded.clone(); altered[altered.length-1] ^= 1;
            assertThrows(RuntimeException.class,()->InitialBootstrapPageCodec.decode(window,session,altered));
            assertFalse(page.toString().contains("private-one")); assertFalse(session.toString().contains("tenant"));
            c.rollback();
        }
    }

    @ParameterizedTest @ValueSource(strings={"postgres","mssql","oracle"})
    void physicalNumericOrderIsKeysetNotLexicographicAndPagesNeverExceed128(String provider) throws Exception {
        try (var f = new Fixture(provider,"0",false); var c = f.source.getConnection()) {
            for (int i=1;i<=140;i++) f.seed(i,"a",i,"v");
            c.setAutoCommit(false); var window=f.window(1L,140L,140,2*1048576);
            var pages=f.pages(window);pages.validate(c);var session=f.session(window);
            var first=pages.capture(c,session,null,0);assertEquals(128,first.rows().size());assertFalse(first.last());
            for(int i=0;i<128;i++)assertEquals(i+1,first.rows().get(i).id().longValue());
            var last=pages.capture(c,session,first.rows().getLast().proof().canonicalId(),128);
            assertEquals(12,last.rows().size());assertEquals(129,last.rows().getFirst().id().longValue());assertTrue(last.last());
            assertTrue(InitialBootstrapPageCodec.encode(window,first).length<=window.pageBytes());
            c.rollback();
        }
    }

    @ParameterizedTest @ValueSource(strings={"postgres","mssql","oracle"})
    void invalidFractionalIdStorageFailsBeforeCapture(String provider) throws Exception {
        try (var f = new Fixture(provider,"1",false); var c=f.source.getConnection()) {
            var window=f.window(1L,10L,10,65536);var pages=f.pages(window);
            assertThrows(ProtectionException.class,()->pages.validate(c));assertEquals(0,f.encodes.get());
        }
    }

    @ParameterizedTest @ValueSource(strings={"postgres","mssql","oracle"})
    void versionNullAndZeroAreNeverNormalizedAndSourceChangeInvalidatesCapturedRow(String provider) throws Exception {
        try(var f=new Fixture(provider,"0",false);var c=f.source.getConnection()){
            f.seed(1,"a",0,"private");c.setAutoCommit(false);
            var w=f.window(1L,2L,2,65536);var p=f.pages(w);p.validate(c);var s=f.session(w);
            assertThrows(ProtectionException.class,()->p.sourceExtremes(c,s));
            assertThrows(ProtectionException.class,()->p.capture(c,s,null,0));assertEquals(0,f.encodes.get());
            c.rollback();
        }
        try(var f=new Fixture(provider,"0",true);var c=f.source.getConnection()){
            f.seedNullVersion();var w=f.window(1L,2L,2,65536);
            assertThrows(ProtectionException.class,()->f.pages(w).validate(c));assertEquals(0,f.encodes.get());
        }
        try(var f=new Fixture(provider,"0",false);var c=f.source.getConnection()){
            f.seed(1,"a",5,"before");c.setAutoCommit(false);var w=f.window(1L,2L,2,65536);var p=f.pages(w);p.validate(c);var s=f.session(w);
            var captured=p.capture(c,s,null,0);c.commit();
            f.replaceSecret(1,"after");
            assertThrows(ProtectionException.class,()->p.revalidate(c,s,captured));assertEquals(1,f.encodes.get());c.rollback();
        }
    }

    @ParameterizedTest @ValueSource(strings={"postgres","mssql","oracle"})
    void canonicalDurableDeleteFenceRejectsHydrationAndIsNotOverwritten(String provider) throws Exception {
        try(var f=new Fixture(provider,"0",false)){
            f.seed(1,"a",5,"private");
            var registry=new DefaultEntityRegistry(ResourceLimits.defaults());
            registry.register(f.metadata,f.codec,CachePolicy.defaults(),new com.reactor.cachedb.core.relation.NoOpRelationBatchLoader<>(),
                    new com.reactor.cachedb.core.page.NoOpEntityPageLoader<>());
            var fence=new JdbcWriteFence(f.source,registry,JdbcWriteFence.Provider.valueOf(f.dialect.name()),2);
            fence.initialize(); // Validates the already provisioned canonical fence table.
            var op=new QueuedWriteOperation(OperationType.DELETE,"InitialRow",f.table,f.table,"","id","entity_version",null,
                    "1",Map.of("id","1","entity_version","9"),9,Instant.EPOCH);
            try(var c=f.source.getConnection()){
                c.setAutoCommit(false);assertEquals(1,fence.filter(c,List.of(op)).size());c.commit();
                var w=f.window(1L,2L,2,65536);var p=f.pages(w);p.validate(c);
                assertThrows(ProtectionException.class,()->p.capture(c,f.session(w),null,0));assertEquals(0,f.encodes.get());
                c.rollback();
                assertThrows(ProtectionException.class,()->JdbcWriteFence.requireInitialRows(c,
                        JdbcWriteFence.Provider.valueOf(f.dialect.name()),f.table,Map.of(1L,5L),2));
                c.rollback();
            }
            // Security fence intentionally remains; fixture only drops its OWN source table.
        }
    }

    static final class Fixture implements AutoCloseable {
        final HikariDataSource source;
        final JdbcTenantPurgeDialect dialect;
        final String table="ih_"+UUID.randomUUID().toString().replace("-","").substring(0,20);
        final AtomicInteger encodes=new AtomicInteger();
        final Binding binding;
        final EntityMetadata<Row,Long> metadata;
        final EntityCodec<Row> codec;
        final JdbcTenantPurgeGuard guard;
        Fixture(String provider,String scale,boolean nullableVersion) throws Exception {
            this(provider,scale,nullableVersion,HikariDataSource::new);
        }
        Fixture(String provider,String scale,boolean nullableVersion,
                Function<HikariConfig,HikariDataSource> sourceFactory) throws Exception {
            if(!Set.of("0","1").contains(scale))throw new IllegalArgumentException("Fixture scale");
            dialect=JdbcTenantPurgeDialect.valueOf(provider.toUpperCase(Locale.ROOT));
            var config=new HikariConfig();
            config.setJdbcUrl(TestFixtureSettings.value("cachedb.snapshot."+provider+".url",switch(provider){
                case "postgres"->"jdbc:postgresql://postgres:5432/postgres";
                case "mssql"->"jdbc:sqlserver://mssql:1433;databaseName=cachedb_snapshot;encrypt=false;trustServerCertificate=true";
                default->"jdbc:oracle:thin:@//oracle:1521/FREEPDB1";}));
            config.setUsername(TestFixtureSettings.value("cachedb.snapshot."+provider+".user",switch(provider){case "postgres"->"postgres";case "mssql"->"sa";default->"cachedb";}));
            config.setPassword(TestFixtureSettings.value("cachedb.snapshot."+provider+".password",switch(provider){case "postgres"->"LocalFixture_7p92_test";case "mssql"->"CacheDbSnapshot123";default->"CacheDbOracle123";}));
            config.setMaximumPoolSize(2);config.setMinimumIdle(0);config.setConnectionTimeout(10000);
            source=Objects.requireNonNull(sourceFactory.apply(config),"Fixture datasource");
            try {
                try(var c=source.getConnection();var q=c.createStatement()){
                    q.setQueryTimeout(5);String decimal=provider.equals("oracle")?"NUMBER":"DECIMAL";
                    String tenant=provider.equals("mssql")?"NVARCHAR(64)":"VARCHAR(64)";
                    String text=provider.equals("mssql")?"NVARCHAR(1024)":"VARCHAR(1024)";
                    q.executeUpdate("CREATE TABLE "+table+"(id "+decimal+"(19,"+scale+") NOT NULL PRIMARY KEY,tenant_id "+tenant
                            +" NOT NULL,secret "+text+" NOT NULL,entity_version "+decimal+"(19,0)"+(nullableVersion?"":" NOT NULL")+")");
                    q.executeUpdate("CREATE INDEX iht_"+table.substring(3)+" ON "+table+"(tenant_id,id)");
                    q.executeUpdate("CREATE INDEX ihv_"+table.substring(3)+" ON "+table+"(tenant_id,entity_version)");
                }
                binding=new Binding(Row.class,table,new SourcePlan(table,"tenant_id","id"),ProtectionKey.hash("initial-source-test-layout"),10000,128,1048576,true);
                var descriptor=new EncryptedColumnDescriptor("default",EncryptedColumnDescriptor.Scope.REDIS_AND_SOURCE,false);
                metadata=new EntityMetadata<>(){
                    public String entityName(){return "InitialRow";}public String tableName(){return table;}public String redisNamespace(){return table;}
                    public String idColumn(){return "id";}public Class<Row> entityType(){return Row.class;}public Function<Row,Long> idAccessor(){return Row::id;}
                    public boolean tenantPurgeEnabled(){return true;}public String tenantFieldName(){return "tenant_id";}public String extractTenantId(Row r){return r.tenant();}
                    public List<String> columns(){return List.of("id","tenant_id","secret");}
                    public Set<String> indexedColumns(){return Set.of("tenant_id");}
                    public Map<String,String> columnTypes(){return Map.of("id","java.lang.Long","tenant_id","java.lang.String","secret","java.lang.String");}
                    public Map<String,EncryptedColumnDescriptor> encryptedColumns(){return Map.of("secret",descriptor);}
                    public List<RelationDefinition> relations(){return List.of();}
                };
                byte[] aes=new byte[32],hmac=new byte[32];Arrays.fill(aes,(byte)7);Arrays.fill(hmac,(byte)13);
                var key=new KeyMaterial("k1",aes,hmac);
                var engine=new JcaFieldEncryption(new CacheKeyProvider(){
                    public KeyMaterial current(String ring){return key;}public Optional<KeyMaterial> byId(String ring,String id){return "k1".equals(id)?Optional.of(key):Optional.empty();}
                });
                codec=new EntityCodec<>(){
                    public boolean encryptedMapping(){return true;}public boolean encryptionBound(){return true;}
                    public String toRedisValue(Row r){encodes.incrementAndGet();return r.id()+"|"+r.tenant()+"|"+engine.encrypt("default",table,r.id().toString(),"secret",r.secret());}
                    public Row fromRedisValue(String value){var p=value.split("\\|",-1);Long id=Long.valueOf(p[0]);return new Row(id,p[1],engine.decrypt("default",table,id.toString(),"secret",p[2]));}
                    public Map<String,Object> toColumns(Row r){return Map.of("id",r.id(),"tenant_id",r.tenant(),"secret",r.secret());}
                    public Map<String,Object> toSourceColumns(Row r){return Map.of("id",r.id(),"tenant_id",r.tenant(),"secret",engine.encrypt("default",table,r.id().toString(),"secret",r.secret()));}
                    public String encryptionIntentFingerprint(List<String> parts){return engine.intentMac("default",table,"atomic-intent",parts);}
                    public Row fromColumns(Map<String,Object> values){Long id=new BigDecimal(values.get("id").toString()).longValueExact();return new Row(id,
                            (String)values.get("tenant_id"),engine.decrypt("default",table,id.toString(),"secret",(String)values.get("secret")));}
                };
                guard=new JdbcTenantPurgeGuard(source,dialect,2,2,Set.of(binding.fingerprint()));
            } catch(Exception|Error failure) {
                try { source.close(); }
                catch(Exception|Error cleanup) { if(cleanup!=failure)failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
        InitialSourceHotWindow<Row,Long> window(Long first,Long last,int count,int bytes){
            return new InitialSourceHotWindow<>(binding,metadata,codec,JdbcSourceOwnerOnboarding.longIds(),
                    JdbcSourceOwnerOnboarding.longIds().formatToken(),first,last,count,bytes,16384,8*1048576,InitialSourceHotWindow.Profile.POINT_NO_QUOTA);
        }
        JdbcInitialSourcePages<Row,Long> pages(InitialSourceHotWindow<Row,Long> window){
            return new JdbcInitialSourcePages<>(guard,window,JdbcSourceOwnerOnboarding.longIds(),2);
        }
        Session session(InitialSourceHotWindow<Row,Long> window){
            // Component proof context only; never used to approve distributed closure or OPEN.
            UUID operation=UUID.randomUUID();var generation=new Generation(binding.key("a"),1);
            return new Session(new Request(generation,"a",operation,ProtectionKey.hash("operator"),30),
                    new Lease(generation.key(),operation,UUID.randomUUID(),1,30),window.fingerprint(),ProtectionKey.hash("component-only"));
        }
        void seed(long id,String tenant,long version,String secret)throws Exception{
            var row=new Row(id,tenant,secret);String stored=(String)codec.toSourceColumns(row).get("secret");
            try(var c=source.getConnection();var q=c.prepareStatement("INSERT INTO "+table+"(id,tenant_id,secret,entity_version) VALUES (?,?,?,?)")){
                q.setQueryTimeout(2);q.setLong(1,id);text(q,2,tenant);text(q,3,stored);q.setLong(4,version);assertEquals(1,q.executeUpdate());
            }
        }
        void seedNullVersion()throws Exception{
            try(var c=source.getConnection();var q=c.prepareStatement("INSERT INTO "+table+"(id,tenant_id,secret,entity_version) VALUES (1,?,?,NULL)")){
                q.setQueryTimeout(2);text(q,1,"a");text(q,2,(String)codec.toSourceColumns(new Row(1L,"a","private")).get("secret"));q.executeUpdate();
            }
        }
        void replaceSecret(long id,String secret)throws Exception{
            try(var c=source.getConnection();var q=c.prepareStatement("UPDATE "+table+" SET secret=? WHERE id=?")){
                q.setQueryTimeout(2);text(q,1,(String)codec.toSourceColumns(new Row(id,"a",secret)).get("secret"));q.setLong(2,id);assertEquals(1,q.executeUpdate());
            }
        }
        void text(PreparedStatement q,int p,String value)throws SQLException{if(dialect==JdbcTenantPurgeDialect.MSSQL)q.setNString(p,value);else q.setString(p,value);}
        public void close()throws Exception{
            if(!table.matches("ih_[0-9a-f]{20}"))throw new IllegalStateException("Unsafe fixture table");
            try(source;var c=source.getConnection();var q=c.createStatement()){q.setQueryTimeout(5);q.executeUpdate("DROP TABLE "+table);}
        }
    }
}
