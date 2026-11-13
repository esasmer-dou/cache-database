package com.reactor.cachedb.starter;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.config.*;
import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.*;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.jdbc.*;
import com.reactor.cachedb.redis.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.JedisPooled;

/** Actual-root pre-consumption negatives only. No synthetic SQL grant or native OPEN certificate. */
@EnabledIfSystemProperty(named="cachedb.snapshot.redis", matches="true")
@Timeout(60)
class CacheInitialNamespaceRedisTest {
    private static final String PRIVATE="namespace-fixture-private-provider-detail";
    private static final String ENTITY="NamespaceRow", NS="namespace_rows";
    record Row(Long id,String tenant,String secret) {}
    private static final EntityCodec<Row> CODEC=new EntityCodec<>() {
        public String toRedisValue(Row row){return row.id()+"|"+row.tenant()+"|"+row.secret();}
        public Row fromRedisValue(String value){var p=value.split("\\|",-1);return new Row(Long.valueOf(p[0]),p[1],p[2]);}
        public Map<String,Object> toColumns(Row row){return Map.of("id",row.id(),"tenant_id",row.tenant(),"secret",row.secret());}
    };

    @Test void descriptorBindsActualRegisteredWindowCanonicalRosterRunAndOneProspectiveIncarnation() {
        try(var f=new Fixture();var admin=CacheTenantContext.administrative()) {
            var first=f.prepare();var second=f.prepare();var request=first.descriptor();
            assertEquals(ProtectionKey.hash(f.rosterKey()),request.rosterKeyToken());
            assertEquals(f.binding.fingerprint(),request.bindingToken());
            assertEquals(first.window().fingerprint(),request.mappingToken());
            String serverRun=(String)f.client.eval("return string.match(redis.call('INFO','SERVER'),'run_id:([0-9a-f]+)')",
                    List.of(f.rosterKey()),List.of());
            assertEquals(serverRun,request.redisRunId());assertNotEquals(request.incarnationId(),second.descriptor().incarnationId());
            assertEquals(0,f.source.calls.get());assertFalse(f.client.exists(f.rosterKey()));
        }
    }

    @Test void foreignGuardAndForeignDataSourceFailBeforeAnySqlConsumptionOrRedisMutation() {
        try(var f=new Fixture();var admin=CacheTenantContext.administrative()) {
            var namespace=f.prepare();var otherSource=new NoSql();
            for(var wrong:List.of(new JdbcTenantPurgeGuard(f.source,JdbcTenantPurgeDialect.POSTGRES,2,1),
                    new JdbcTenantPurgeGuard(otherSource,JdbcTenantPurgeDialect.POSTGRES,2,1))) {
                var gate=new JdbcNamespaceBootstrapGate(wrong,Set.of(namespace.descriptor().namespaceFingerprint()));
                assertSafe(ProtectionException.Code.UNWIRED_PROTOCOL,() -> namespace.bootstrap(gate,f.generation()));
            }
            assertEquals(0,f.source.calls.get());assertEquals(0,otherSource.calls.get());assertFalse(f.client.exists(f.rosterKey()));
        }
    }

    @Test void wrongScopeFailsBeforeSqlAndDoesNotCreateAnEnrollmentRoster() {
        try(var f=new Fixture();var admin=CacheTenantContext.administrative()) {
            var namespace=f.prepare();var gate=f.gate(namespace);
            assertSafe(ProtectionException.Code.UNWIRED_PROTOCOL,() -> namespace.bootstrap(gate,
                    new Generation(ProtectionKey.of("another_namespace","tenant"),1)));
            assertEquals(0,f.source.calls.get());assertFalse(f.client.exists(f.rosterKey()));
        }
    }

    @Test void anExistingUnknownRosterIsNeverAdoptedOrOverwrittenByBootstrap() {
        try(var f=new Fixture();var admin=CacheTenantContext.administrative()) {
            var namespace=f.prepare();f.client.set(f.rosterKey(),PRIVATE);
            assertSafe(ProtectionException.Code.MIGRATION_REQUIRED,() -> namespace.bootstrap(f.gate(namespace),f.generation()));
            assertEquals(PRIVATE,f.client.get(f.rosterKey()));assertEquals(0,f.source.calls.get());
        }
    }

    @Test void providerFailuresDuringDescriptorRunAndPreSqlAbsenceChecksAreRedacted() {
        try(var f=new Fixture(true);var admin=CacheTenantContext.administrative()) {
            f.deny("info");assertSafe(ProtectionException.Code.STORAGE_UNAVAILABLE,f::prepare);
            assertEquals(0,f.source.calls.get());assertFalse(f.administrator.exists(f.rosterKey()));
        }
        try(var f=new Fixture(true);var admin=CacheTenantContext.administrative()) {
            var namespace=f.prepare();var gate=f.gate(namespace);f.deny("info");
            assertSafe(ProtectionException.Code.STORAGE_UNAVAILABLE,() -> namespace.bootstrap(gate,f.generation()));
            assertEquals(0,f.source.calls.get());assertFalse(f.administrator.exists(f.rosterKey()));
        }
        try(var f=new Fixture(true);var admin=CacheTenantContext.administrative()) {
            var namespace=f.prepare();var gate=f.gate(namespace);f.deny("type");
            assertSafe(ProtectionException.Code.STORAGE_UNAVAILABLE,() -> namespace.bootstrap(gate,f.generation()));
            assertEquals(0,f.source.calls.get());assertFalse(f.administrator.exists(f.rosterKey()));
        }
    }

    @Test void changedPayloadMappingAndReplacedRegistryBindingInvalidatePreparedCapabilityBeforeSql() {
        try(var f=new Fixture();var admin=CacheTenantContext.administrative()) {
            var namespace=f.prepare();var gate=f.gate(namespace);f.changedMapping.set(true);
            assertSafe(ProtectionException.Code.UNWIRED_PROTOCOL,() -> namespace.bootstrap(gate,f.generation()));
            f.changedMapping.set(false);f.database.register(f.metadata,CODEC,CachePolicy.defaults());
            assertSafe(ProtectionException.Code.UNWIRED_PROTOCOL,() -> namespace.bootstrap(gate,f.generation()));
            assertEquals(0,f.source.calls.get());assertFalse(f.client.exists(f.rosterKey()));
        }
    }

    @Test void ordinaryPublicConfigureAndRawRepositoryCannotBootstrapAMissingRoster() {
        try(var f=new Fixture()) {
            assertSafe(ProtectionException.Code.MIGRATION_REQUIRED,() -> f.database.configureTenantProducer(f.metadata,1));
            var repository=f.database.repository(f.metadata,CODEC);
            try(var tenant=CacheTenantContext.withTenant("tenant")) {
                assertSafe(ProtectionException.Code.UNWIRED_PROTOCOL,() -> repository.findById(1L));
                assertSafe(ProtectionException.Code.UNWIRED_PROTOCOL,() -> repository.findAll(List.of(1L,2L)));
                assertSafe(ProtectionException.Code.UNWIRED_PROTOCOL,() -> repository.saveWithReceipt(new Row(1L,"tenant",PRIVATE)));
            }
            var keys=f.database.redisKeyStrategy();
            for(var key:List.of(keys.entityKey(NS,1L),keys.versionKey(NS,1L),keys.versionKey(NS,1L)+":tenant",
                    keys.indexMetaKey(NS,1L)))assertFalse(f.client.exists(key));
            assertEquals(0,f.client.xlen(f.database.config().writeBehind().streamKey()));
            assertEquals(0,f.client.xlen(f.database.config().writeBehind().deadLetterStreamKey()));
            assertEquals(0,f.client.xlen(f.database.config().writeBehind().compactionStreamKey()));
            assertEquals(0,f.source.calls.get());assertFalse(f.client.exists(f.rosterKey()));
        }
    }

    @Test void initialBudgetAndCanonicalLayoutMismatchAreRejectedBeforeRedisOrSql() {
        try(var f=new Fixture();var admin=CacheTenantContext.administrative()) {
            long calls=f.client.getPool().getBorrowedCount();
            assertSafe(ProtectionException.Code.UNWIRED_PROTOCOL,() -> f.database.prepareInitialNamespace(f.metadata,
                    new Binding(Row.class,NS,f.binding.source(),ProtectionKey.hash("wrong-layout"),10000,128,1048576,true),
                    f.guard,JdbcSourceOwnerOnboarding.longIds(),1L,2L,2,65536,4096,1048576,1));
            assertSafe(ProtectionException.Code.UNWIRED_PROTOCOL,() -> f.database.prepareInitialNamespace(f.metadata,f.binding,
                    f.guard,JdbcSourceOwnerOnboarding.longIds(),1L,2L,2,65536,4096,1048576,0));
            assertEquals(calls,f.client.getPool().getBorrowedCount());assertEquals(0,f.source.calls.get());
        }
    }

    private static void assertSafe(ProtectionException.Code code,org.junit.jupiter.api.function.Executable action) {
        var failure=assertThrows(ProtectionException.class,action);assertEquals(code,failure.code());
        assertFalse(failure.toString().contains(PRIVATE));assertNull(failure.getCause());assertEquals(0,failure.getSuppressed().length);
    }

    private static final class Fixture implements AutoCloseable {
        final String prefix="initial_binding_"+UUID.randomUUID().toString().replace("-","");
        final JedisPooled administrator=new JedisPooled(System.getProperty("cachedb.snapshot.redis.host","127.0.0.1"),
                Integer.getInteger("cachedb.snapshot.redis.port",16383));
        final JedisPooled client;
        final String aclUser;
        final NoSql source=new NoSql();final AtomicBoolean changedMapping=new AtomicBoolean();
        final EntityMetadata<Row,Long> metadata=new EntityMetadata<>() {
            public String entityName(){return ENTITY;}public String tableName(){return NS;}public String redisNamespace(){return NS;}
            public String idColumn(){return "id";}public Class<Row> entityType(){return Row.class;}public Function<Row,Long> idAccessor(){return Row::id;}
            public String tenantFieldName(){return "tenant_id";}public String extractTenantId(Row row){return row.tenant();}
            public boolean tenantPurgeEnabled(){return true;}public List<String> columns(){return List.of("id","tenant_id","secret");}
            public Set<String> indexedColumns(){return Set.of("tenant_id");}
            public Map<String,String> columnTypes(){return Map.of("id","java.lang.Long","tenant_id","java.lang.String",
                    "secret",changedMapping.get()?"java.lang.CharSequence":"java.lang.String");}
            public List<RelationDefinition> relations(){return List.of();}
        };
        final CacheDatabase database;final Binding binding;final JdbcTenantPurgeGuard guard;
        Fixture() {this(false);}
        Fixture(boolean aclFaults) {
            var library=prefix+"_functions";
            aclUser=aclFaults?prefix+"_acl":null;
            if(aclFaults) {
                String password=UUID.randomUUID().toString().replace("-","");
                aclStatus("SETUSER",aclUser,"reset","on",">"+password,"+@all",
                        "~"+prefix+"*","~cachedb:function-library:"+library+":marker",
                        "~cachedb:function-library:"+library+":marker:lease","&*");
                client=new JedisPooled(new redis.clients.jedis.HostAndPort(
                        System.getProperty("cachedb.snapshot.redis.host","127.0.0.1"),Integer.getInteger("cachedb.snapshot.redis.port",16383)),
                        redis.clients.jedis.DefaultJedisClientConfig.builder().user(aclUser).password(password)
                                .connectionTimeoutMillis(2000).socketTimeoutMillis(2000).build());
            }else client=administrator;
            var config=CacheDatabaseConfig.builder().runtimeRole(CacheRuntimeRole.CLIENT_ONLY)
                    .readThrough(ReadThroughConfig.builder().mode(ReadThroughMode.REDIS_ONLY).build())
                    .keyspace(KeyspaceConfig.builder().keyPrefix(prefix).build())
                    .redisGuardrail(RedisGuardrailConfig.builder().enabled(false).build())
                    .redisFunctions(RedisFunctionsConfig.builder().libraryName(library).upsertFunctionName(library+"_upsert")
                            .deleteFunctionName(library+"_delete").compactionCompleteFunctionName(library+"_complete").build())
                    .writeBehind(WriteBehindConfig.builder().enabled(false).streamKey(prefix+":write")
                            .deadLetterStreamKey(prefix+":dlq").compactionStreamKey(prefix+":compact").build()).build();
            database=new CacheDatabase(client,source,config);database.start();database.register(metadata,CODEC,CachePolicy.defaults());
            assertSame(client,assertInstanceOf(JedisPooledOperations.class,database.redisOperations()).pooledClient());
            assertSame(client,assertInstanceOf(JedisPooledOperations.class,database.backgroundRedisOperations()).pooledClient());
            binding=new Binding(Row.class,NS,new SourcePlan(NS,"tenant_id","id"),
                    RedisInitialSourceLayout.layoutToken(metadata,database.redisKeyStrategy(),config.queryIndex()),10000,128,1048576,true);
            guard=new JdbcTenantPurgeGuard(source,JdbcTenantPurgeDialect.POSTGRES,2,1);
        }
        String rosterKey(){return RedisTenantRuntimeControl.rosterKey(database.redisKeyStrategy(),NS);}
        CacheInitialNamespace<Row,Long> prepare(){return database.prepareInitialNamespace(metadata,binding,guard,
                JdbcSourceOwnerOnboarding.longIds(),1L,2L,2,65536,4096,1048576,1);}
        Generation generation(){return new Generation(binding.key("tenant"),1);}
        JdbcNamespaceBootstrapGate gate(CacheInitialNamespace<Row,Long> namespace){
            return new JdbcNamespaceBootstrapGate(guard,Set.of(namespace.descriptor().namespaceFingerprint()));
        }
        void deny(String command) {
            if(aclUser==null||!Set.of("info","type").contains(command))throw new AssertionError("Unsupported owned ACL fault");
            aclStatus("SETUSER",aclUser,"-"+command);
        }
        private void aclStatus(String... arguments) {
            Object result=administrator.sendCommand(redis.clients.jedis.Protocol.Command.ACL,arguments);
            if(result instanceof byte[] bytes)
                assertArrayEquals(new byte[]{'O','K'},bytes);
            else assertEquals("OK",assertInstanceOf(String.class,result));
        }
        public void close() {
            try{database.close();}
            finally {
                try {
                    administrator.del(rosterKey(),"cachedb:function-library:"+prefix+"_functions:marker");
                    administrator.functionDelete(prefix+"_functions");
                } finally{
                    try{if(client!=administrator)client.close();}
                    finally{
                        try{if(aclUser!=null)assertEquals(Long.valueOf(1),
                                administrator.sendCommand(redis.clients.jedis.Protocol.Command.ACL,"DELUSER",aclUser));}
                        finally{administrator.close();}
                    }
                }
            }
        }
    }
    private static final class NoSql implements DataSource {
        final AtomicInteger calls=new AtomicInteger();
        public Connection getConnection(){calls.incrementAndGet();throw new AssertionError("Pre-consumption negative must not reach SQL");}
        public Connection getConnection(String user,String password){return getConnection();}
        public java.io.PrintWriter getLogWriter(){return null;}public void setLogWriter(java.io.PrintWriter writer){}
        public int getLoginTimeout(){return 1;}public void setLoginTimeout(int seconds){}
        public java.util.logging.Logger getParentLogger(){return java.util.logging.Logger.getLogger("initial-binding-test");}
        public <T>T unwrap(Class<T> type)throws SQLException{throw new SQLException("Not a wrapper");}
        public boolean isWrapperFor(Class<?> type){return false;}
    }
}
