package com.reactor.cachedb.jdbc;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.codec.LengthPrefixedPayloadCodec;
import com.reactor.cachedb.core.config.ResourceLimits;
import com.reactor.cachedb.core.crypto.*;
import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.core.queue.*;
import com.reactor.cachedb.core.registry.*;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

/** H2 verifies column conversion/schema only; provider fence SQL has separate integration gates. */
class EncryptedJdbcBoundaryTest {
    private static final String SECRET="PII_JDBC_MARKER_A6E2";
    private static final Metadata META=new Metadata();
    record Row(String id,String contact,String source) {}

    static final class Metadata implements EntityMetadata<Row,String> {
        public String entityName(){return "EncryptedJdbcRow";}
        public String tableName(){return "encrypted_rows";}
        public String redisNamespace(){return "encrypted_rows";}
        public String idColumn(){return "id";}
        public Class<Row> entityType(){return Row.class;}
        public Function<Row,String> idAccessor(){return Row::id;}
        public List<String> columns(){return List.of("id","contact","source_value");}
        public List<RelationDefinition> relations(){return List.of();}
        public Set<String> indexedColumns(){return Set.of("id","contact","source_value");}
        public Map<String,String> columnTypes(){return Map.of("id","java.lang.String","contact","java.lang.String","source_value","java.lang.String");}
        public Map<String,EncryptedColumnDescriptor> encryptedColumns(){return Map.of(
                "contact",new EncryptedColumnDescriptor("default",EncryptedColumnDescriptor.Scope.REDIS_ONLY,true),
                "source_value",new EncryptedColumnDescriptor("default",EncryptedColumnDescriptor.Scope.REDIS_AND_SOURCE,true));}
    }

    static CacheKeyProvider keys(){
        byte[] aes=new byte[32],hmac=new byte[32];Arrays.fill(aes,(byte)1);Arrays.fill(hmac,(byte)2);
        return new StaticKeyProvider(Map.of("default",List.of(new KeyMaterial("k1",aes,hmac))),Map.of("default","k1"));
    }

    static final class Codec implements EntityCodec<Row> {
        private final JcaFieldEncryption engine;
        Codec(CacheKeyProvider provider){engine=EncryptedCodecSupport.bind(provider,Set.of("default"));}
        public boolean encryptionBound(){return true;}
        public EntityCodec<Row> bindEncryption(CacheKeyProvider provider){return new Codec(provider);}
        private String encrypt(Row row,String column,String value){return engine.encrypt("default",META.redisNamespace(),row.id(),column,value);}
        private String decrypt(String id,String column,String value){return engine.decrypt("default",META.redisNamespace(),id,column,value);}
        public Map<String,Object> toColumns(Row row){var map=new LinkedHashMap<String,Object>();map.put("id",row.id());map.put("contact",row.contact());map.put("source_value",row.source());return map;}
        public Row fromLogicalColumns(Map<String,Object> map){return new Row((String)map.get("id"),(String)map.get("contact"),(String)map.get("source_value"));}
        public Map<String,Object> toSourceColumns(Row row){var map=toColumns(row);map.put("source_value",encrypt(row,"source_value",row.source()));return map;}
        public Row fromColumns(Map<String,Object> map){String id=(String)columnValue(map,"id");return new Row(id,(String)columnValue(map,"contact"),decrypt(id,"source_value",EncryptedCodecSupport.sourceEnvelope(columnValue(map,"source_value"))));}
        public Map<String,Object> toQueuedColumns(Row row){var map=toColumns(row);map.put("contact",encrypt(row,"contact",row.contact()));map.put("source_value",encrypt(row,"source_value",row.source()));map.put(EncryptedCodecSupport.QUEUE_FORMAT,EncryptedCodecSupport.QUEUE_VERSION);return map;}
        public Map<String,String> sourceColumnsFromQueued(String id,Map<String,String> queued){
            EncryptedStorageChecks.validateQueuedColumns(META,queued);var map=new LinkedHashMap<>(queued);
            map.put("contact",decrypt(id,"contact",queued.get("contact")));decrypt(id,"source_value",queued.get("source_value"));
            map.remove(EncryptedCodecSupport.QUEUE_FORMAT);return map;
        }
        public String toRedisValue(Row row){var map=new LinkedHashMap<String,String>();toQueuedColumns(row).forEach((key,value)->map.put(key,value==null?null:value.toString()));map.remove(EncryptedCodecSupport.QUEUE_FORMAT);return LengthPrefixedPayloadCodec.encode(map);}
        public Row fromRedisValue(String payload){var map=LengthPrefixedPayloadCodec.decode(payload);String id=map.get("id");return new Row(id,decrypt(id,"contact",map.get("contact")),decrypt(id,"source_value",map.get("source_value")));}
        public Map<String,Object> toIndexColumns(Row row){var map=new LinkedHashMap<String,Object>();map.put("id",row.id());map.put("contact",engine.blindIndex("default",META.redisNamespace(),"contact",row.contact()));map.put("source_value",engine.blindIndex("default",META.redisNamespace(),"source_value",row.source()));return map;}
        public Object indexQueryValue(String column,Object value){return column.equals("contact")||column.equals("source_value")?engine.blindIndex("default",META.redisNamespace(),column,(String)value):value;}
    }

    static DefaultEntityRegistry registry(){
        var registry=new DefaultEntityRegistry(ResourceLimits.defaults());
        registry.register(META,new Codec(keys()),CachePolicy.defaults(),null,null);return registry;
    }
    static QueuedWriteOperation operation(Codec codec,Row row){
        var columns=new LinkedHashMap<String,String>();codec.toQueuedColumns(row).forEach((key,value)->columns.put(key,value==null?null:value.toString()));
        return new QueuedWriteOperation(OperationType.UPSERT,META.entityName(),META.tableName(),META.redisNamespace(),"",
                "id","entity_version",null,row.id(),columns,1,Instant.EPOCH);
    }
    static JdbcDataSource database(String type)throws Exception{
        var source=new JdbcDataSource();source.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1");
        try(var c=source.getConnection();var sql=c.createStatement()){
            sql.execute("CREATE TABLE encrypted_rows(id VARCHAR(128),contact VARCHAR(65536),source_value "+type+",entity_version BIGINT)");
        }
        return source;
    }

    @Test void queuedScopesRoundTripThroughRealJdbcColumnBindingWithoutMutatingOriginal() throws Exception {
        var source=database("VARCHAR(65536)");var codec=new Codec(keys());var registry=registry();
        for(String value:Arrays.asList(null,"__NULL__","cachedb:__NULL__","~P:N",SECRET)){
            Row row=new Row(UUID.randomUUID().toString(),value,SECRET);
            var original=operation(codec,row);var before=new LinkedHashMap<>(original.columns());
            var sql=EncryptedQueuedWrites.sourceColumns(registry,List.of(original)).get(0);
            try(var c=source.getConnection();var insert=c.prepareStatement("INSERT INTO encrypted_rows(id,contact,source_value) VALUES(?,?,?)")){
                insert.setString(1,row.id());insert.setObject(2,JdbcWriteBehindSupport.convertValue(sql.columns().get("contact"),"java.lang.String"));
                insert.setObject(3,JdbcWriteBehindSupport.convertValue(sql.columns().get("source_value"),"java.lang.String"));insert.executeUpdate();
                try(var read=c.prepareStatement("SELECT id,contact,source_value FROM encrypted_rows WHERE id=?")){
                    read.setString(1,row.id());try(var rows=read.executeQuery()){
                        assertTrue(rows.next());assertEquals(value,rows.getString(2));assertTrue(rows.getString(3).startsWith("~E1:"));
                        var columns=new LinkedHashMap<String,Object>();columns.put("id",rows.getString(1));columns.put("contact",rows.getString(2));columns.put("source_value",rows.getString(3));
                        assertEquals(row,codec.fromColumns(columns));
                    }
                }
            }
            assertEquals(before,original.columns());assertFalse(original.columns().toString().contains(SECRET));
        }
    }

    @Test void redisOnlyBlindEqualityKeepsLogicalSqlParametersAndIdReadsDecryptSourceColumns() throws Exception {
        var source=database("VARCHAR(65536)");var codec=new Codec(keys());var row=new Row("sql-id",SECRET,SECRET);
        var stored=codec.toSourceColumns(row);
        try(var c=source.getConnection();var insert=c.prepareStatement("INSERT INTO encrypted_rows(id,contact,source_value,entity_version) VALUES(?,?,?,?)")){
            insert.setString(1,row.id());insert.setObject(2,stored.get("contact"));insert.setObject(3,stored.get("source_value"));
            insert.setLong(4,7);insert.executeUpdate();
        }
        var loader=new JdbcEntitySourceLoader<>(source,META,codec,10,2,JdbcQueryDialects.standard("h2",Set.of("h2"),1000,true));
        var eq=com.reactor.cachedb.core.query.QueryFilter.eq("contact",SECRET);
        var query=com.reactor.cachedb.core.query.QuerySpec.where(eq).limitTo(10);
        var rows=loader.loadVersionedQuery(query);
        assertEquals(1,rows.size());assertEquals(row,rows.get(0).entity());assertEquals(SECRET,eq.value());
        assertNotEquals(SECRET,codec.indexQueryValue("contact",SECRET));
        assertThrows(IllegalArgumentException.class, () -> loader.loadVersionedQuery(
                com.reactor.cachedb.core.query.QuerySpec.where(eq)));
        assertEquals(row,loader.loadVersionedById(row.id()).orElseThrow().entity());
    }

    @Test void randomizedSourcePredicatesProtectedSortAndOpaqueGroupRejectBeforeAnyJdbcAccess() {
        var calls=new AtomicInteger();
        DataSource forbidden=(DataSource)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{DataSource.class},
                (proxy,method,args)->{calls.incrementAndGet();throw new AssertionError("No unsupported SQL access");});
        var codec=new Codec(keys());
        var loader=new JdbcEntitySourceLoader<>(forbidden,META,codec,10,2,JdbcQueryDialects.standard("h2",Set.of("h2"),1000,true));
        for(Object literal:List.of(SECRET,"~B1:logical-token")){
            var sourceQuery=com.reactor.cachedb.core.query.QuerySpec.where(com.reactor.cachedb.core.query.QueryFilter.eq("source_value",literal));
            var failure=assertThrows(CacheEncryptionException.class,()->loader.loadVersionedQuery(sourceQuery));
            assertEquals(CacheEncryptionException.Code.UNSUPPORTED_SOURCE_OPERATION,failure.code());
            assertNull(failure.getCause());assertFalse(failure.toString().contains(SECRET));
        }
        for(String column:List.of("contact","source_value"))assertThrows(CacheEncryptionException.class,()->loader.loadVersionedQuery(
                com.reactor.cachedb.core.query.QuerySpec.allOf().orderBy(com.reactor.cachedb.core.query.QuerySort.asc(column))));
        assertThrows(CacheEncryptionException.class,()->loader.loadVersionedQuery(
                com.reactor.cachedb.core.query.QuerySpec.anyOf(
                        com.reactor.cachedb.core.query.QueryFilter.eq("id","1"),
                        com.reactor.cachedb.core.query.QueryGroup.and(
                                com.reactor.cachedb.core.query.QueryFilter.eq("source_value",SECRET)))));
        var opaque=assertThrows(CacheEncryptionException.class,()->new JdbcSourceSqlRepository<>(forbidden,codec));
        assertEquals(CacheEncryptionException.Code.UNSUPPORTED_SOURCE_OPERATION,opaque.code());
        assertEquals(0,calls.get());
    }

    @Test void sourceSchemaChecksActualEnvelopeCapacityAndRejectsNumericAndClobMappings() throws Exception {
        var codec=new Codec(keys());
        var values=EncryptedQueuedWrites.sourceColumns(registry(),List.of(operation(codec,new Row("1",SECRET,SECRET)))).getFirst().columns();
        int size=values.get("source_value").length();
        for(String type:List.of("VARCHAR(65536)","VARCHAR(1024)","NVARCHAR(1024)","VARCHAR("+size+")")){
            var source=database(type);try(var c=source.getConnection()){
                var guard=new EncryptedJdbcSchemaGuard();
                guard.verify(c.getMetaData(),META,values);guard.verify(c.getMetaData(),META,values);
            }
        }
        var shortSource=database("VARCHAR("+(size-1)+")");
        try(var c=shortSource.getConnection()){
            assertEquals(CacheEncryptionException.Code.FIELD_LIMIT,assertThrows(CacheEncryptionException.class,
                    ()->new EncryptedJdbcSchemaGuard().verify(c.getMetaData(),META,values)).code());
        }
        for(String type:List.of("DECIMAL(18,2)","CLOB")){
            var source=database(type);try(var c=source.getConnection()){
                assertEquals(CacheEncryptionException.Code.INVALID_CONFIGURATION,assertThrows(CacheEncryptionException.class,
                        ()->new EncryptedJdbcSchemaGuard().verify(c.getMetaData(),META,values)).code());
            }
        }
    }

    @Test void cachedCapacityRechecksEveryEnvelopeAndRejectsNonAsciiAliasesAndGlobalOverflow() throws Exception {
        var source=database("VARCHAR(65536)");var guard=new EncryptedJdbcSchemaGuard();
        var codec=new Codec(keys());
        var values=EncryptedQueuedWrites.sourceColumns(registry(),List.of(operation(codec,new Row("1",SECRET,SECRET)))).getFirst().columns();
        try(var c=source.getConnection()){guard.verify(c.getMetaData(),META,values);}
        DatabaseMetaData forbidden=(DatabaseMetaData)Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{DatabaseMetaData.class},(proxy,method,args)->{throw new AssertionError("Cached schema must not query metadata");});
        guard.verify(forbidden,META,values);
        guard.verify(forbidden,META,Map.of()); // An unchanged PATCH field has no value to validate.
        guard.verify(forbidden,META,Collections.singletonMap("source_value",null));
        for(String bad:List.of("~E1:k1:"+"A".repeat(JcaFieldEncryption.MAX_FIELD_BYTES),"~E1:k1:\u0100","~E1:k1:\n")){
            var failure=assertThrows(CacheEncryptionException.class,
                    ()->guard.verify(forbidden,META,Map.of("source_value",bad)));
            assertEquals(bad.length()>JcaFieldEncryption.MAX_FIELD_BYTES?CacheEncryptionException.Code.FIELD_LIMIT
                    :CacheEncryptionException.Code.INVALID_TEXT,failure.code());
            assertNull(failure.getCause());assertFalse(failure.toString().contains(bad));
        }
        assertEquals(CacheEncryptionException.Code.INVALID_TEXT,assertThrows(CacheEncryptionException.class,
                ()->guard.verify(forbidden,META,Map.of("SOURCE_VALUE",values.get("source_value")))).code());
    }

    @Test void cachedCapacityCannotBeReusedAfterTheMetadataTableChanges() throws Exception {
        var source=database("VARCHAR(1024)");var guard=new EncryptedJdbcSchemaGuard();
        var table=new java.util.concurrent.atomic.AtomicReference<>(META.tableName());
        EntityMetadata<?,?> metadata=(EntityMetadata<?,?>)Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{EntityMetadata.class},(proxy,method,args)->{
                    if(method.getName().equals("tableName"))return table.get();
                    if(method.getName().equals("hashCode"))return System.identityHashCode(proxy);
                    if(method.getName().equals("equals"))return proxy==args[0];
                    return method.invoke(META,args);
                });
        try(var c=source.getConnection()){
            guard.verify(c.getMetaData(),metadata,Map.of());
            table.set("different_physical_table");
            assertEquals(CacheEncryptionException.Code.INVALID_CONFIGURATION,assertThrows(CacheEncryptionException.class,
                    ()->guard.verify(c.getMetaData(),metadata,Map.of())).code());
        }
    }

    @Test void cachedShortCapacityRejectsLargerAuthenticatedSecondWrite() throws Exception {
        var source=database("VARCHAR(128)");var guard=new EncryptedJdbcSchemaGuard();var codec=new Codec(keys());
        var small=EncryptedQueuedWrites.sourceColumns(registry(),List.of(operation(codec,new Row("1",null,"small")))).getFirst().columns();
        var large=EncryptedQueuedWrites.sourceColumns(registry(),List.of(operation(codec,new Row("2",null,"A".repeat(256))))).getFirst().columns();
        try(var c=source.getConnection()){
            guard.verify(c.getMetaData(),META,small);
            var failure=assertThrows(CacheEncryptionException.class,()->guard.verify(c.getMetaData(),META,large));
            assertEquals(CacheEncryptionException.Code.FIELD_LIMIT,failure.code());
            guard.verify(c.getMetaData(),META,small);
            guard.verify(c.getMetaData(),META,Collections.singletonMap("source_value",null));
        }
    }

    @Test void missingDuplicateNullAndNonpositiveCapacityMetadataFailClosed() throws Exception {
        var source=database("VARCHAR(1024)");
        try(var c=source.getConnection()){
            var actual=c.getMetaData();
            for(String fault:List.of("missing","duplicate","null","zero","negative")){
                DatabaseMetaData metadata=(DatabaseMetaData)Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[]{DatabaseMetaData.class},(proxy,method,args)->{
                            if(!method.getName().equals("getColumns"))return method.invoke(actual,args);
                            var rows=(ResultSet)method.invoke(actual,args);
                            return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{ResultSet.class},
                                    (rowProxy,rowMethod,rowArgs)->{
                                        if(rowMethod.getName().equals("getString")&&"COLUMN_NAME".equals(rowArgs[0])){
                                            if(fault.equals("missing"))return "not_the_encrypted_column";
                                            if(fault.equals("duplicate"))return "source_value";
                                        }
                                        if(rowMethod.getName().equals("getLong")&&"COLUMN_SIZE".equals(rowArgs[0])){
                                            if(fault.equals("null"))return 1024L;
                                            if(fault.equals("zero"))return 0L;
                                            if(fault.equals("negative"))return -1L;
                                        }
                                        if(rowMethod.getName().equals("wasNull")&&fault.equals("null"))return true;
                                        return rowMethod.invoke(rows,rowArgs);
                                    });
                        });
                assertEquals(CacheEncryptionException.Code.INVALID_CONFIGURATION,assertThrows(CacheEncryptionException.class,
                        ()->new EncryptedJdbcSchemaGuard().verify(metadata,META,Map.of())).code(),fault);
            }
        }
    }

    @Test void mixedBatchCapacityPreflightRejectsBeforeFenceTransactionOrDmlIncludingCachedRetry() throws Exception {
        var source=database("VARCHAR(128)");var borrows=new AtomicInteger();var forbiddenCalls=new AtomicInteger();
        DataSource metadataOnly=(DataSource)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{DataSource.class},
                (proxy,method,args)->{
                    if(!method.getName().equals("getConnection"))throw new AssertionError("Unexpected source method");
                    borrows.incrementAndGet();var connection=source.getConnection();
                    return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},
                            (connectionProxy,connectionMethod,connectionArgs)->{
                                if(connectionMethod.getName().equals("getMetaData"))return connection.getMetaData();
                                if(connectionMethod.getName().equals("close")){connection.close();return null;}
                                forbiddenCalls.incrementAndGet();throw new AssertionError("No fence, transaction or DML before complete preflight");
                            });
                });
        var codec=new Codec(keys());var small=operation(codec,new Row("1",null,"small"));
        var large=operation(codec,new Row("2",null,"A".repeat(256)));
        var nullable=operation(codec,new Row("3",null,null));
        var deleted=new QueuedWriteOperation(OperationType.DELETE,META.entityName(),META.tableName(),META.redisNamespace(),"",
                "id","entity_version",null,"4",Map.of("source_value","not_a_written_envelope"),2,Instant.EPOCH);
        var plain=new QueuedWriteOperation(OperationType.UPSERT,"Plain","plain_rows","plain_rows","",
                "id","entity_version",null,"5",Map.of("value","plain"),1,Instant.EPOCH);
        var patchColumns=new LinkedHashMap<>(small.columns());patchColumns.remove("id");
        var patch=new QueuedWriteOperation(OperationType.PATCH,META.entityName(),META.tableName(),META.redisNamespace(),"",
                "id","entity_version",null,small.id(),patchColumns,1,Instant.EPOCH);
        var nullPatchColumns=new LinkedHashMap<>(nullable.columns());nullPatchColumns.remove("id");
        var nullPatch=new QueuedWriteOperation(OperationType.PATCH,META.entityName(),META.tableName(),META.redisNamespace(),"",
                "id","entity_version",null,nullable.id(),nullPatchColumns,1,Instant.EPOCH);
        var absentPatchColumns=new LinkedHashMap<>(patchColumns);absentPatchColumns.remove("source_value");
        var absentPatch=new QueuedWriteOperation(OperationType.PATCH,META.entityName(),META.tableName(),META.redisNamespace(),"",
                "id","entity_version",null,small.id(),absentPatchColumns,1,Instant.EPOCH);
        var probe=new Probe(metadataOnly,registry());
        var operations=List.of(plain,small,nullable,deleted,patch,nullPatch,absentPatch,large);
        assertEquals(CacheEncryptionException.Code.FIELD_LIMIT,assertThrows(CacheEncryptionException.class,
                ()->probe.preflight(operations)).code());
        assertEquals(1,borrows.get());assertEquals(0,forbiddenCalls.get());
        assertEquals(CacheEncryptionException.Code.FIELD_LIMIT,assertThrows(CacheEncryptionException.class,
                ()->probe.preflight(operations)).code());
        assertEquals(1,borrows.get(),"Cached oversized write must fail before another connection or fence initialization");
        var oversizedPatch=new QueuedWriteOperation(OperationType.PATCH,META.entityName(),META.tableName(),META.redisNamespace(),"",
                "id","entity_version",null,large.id(),large.columns(),1,Instant.EPOCH);
        assertEquals(CacheEncryptionException.Code.FIELD_LIMIT,assertThrows(CacheEncryptionException.class,
                ()->probe.preflight(List.of(small,oversizedPatch))).code());
        assertEquals(1,borrows.get());
        assertEquals(0,forbiddenCalls.get());assertNull(nullable.columns().get("source_value"));
        assertEquals("not_a_written_envelope",deleted.columns().get("source_value"));
        assertTrue(small.columns().get("source_value").startsWith("~E1:"));
    }

    static final class Probe extends AbstractJdbcWriteBehindFlusher {
        Probe(DataSource source,EntityRegistry registry){super(source,registry,JdbcWriteFence.Provider.POSTGRES,2);}
        public WriteFailureDetails classify(Exception error){return WriteFailureDetails.unknown(error);}
        public void flush(QueuedWriteOperation operation)throws SQLException{
            var prepared=prepareWrites(List.of(operation));
            initializeWriteFence(prepared);
            try(var c=dataSource.getConnection()){inTransaction(c,prepared,null,null,accepted->{throw new AssertionError("Unexpected business DML");});}
        }
        void run(Connection connection,List<QueuedWriteOperation> operations)throws SQLException{
            inTransaction(connection,operations,null,null,accepted->{throw new AssertionError("Unexpected business DML");});
        }
        void preflight(List<QueuedWriteOperation> operations)throws SQLException{
            initializeWriteFence(prepareWrites(operations));
        }
    }

    @Test void malformedLastMemberFailsBeforeAnyFenceInitializationOrConnectionAccess(){
        var touched=new AtomicInteger();
        DataSource forbidden=(DataSource)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{DataSource.class},
                (proxy,method,args)->{touched.incrementAndGet();throw new AssertionError("No JDBC before decrypt validation");});
        var codec=new Codec(keys());var valid=operation(codec,new Row("1",SECRET,SECRET));
        var bad=new LinkedHashMap<>(valid.columns());bad.put("contact",SECRET);
        var invalid=new QueuedWriteOperation(valid.type(),valid.entityName(),valid.tableName(),valid.redisNamespace(),"",
                valid.idColumn(),valid.versionColumn(),null,valid.id(),bad,2,Instant.EPOCH);
        var probe=new Probe(forbidden,registry());
        var connection=(Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},
                (proxy,method,args)->{touched.incrementAndGet();throw new AssertionError("No connection method before all members validate");});
        assertThrows(CacheEncryptionException.class,()->probe.run(connection,List.of(valid,invalid)));
        assertThrows(CacheEncryptionException.class,()->probe.flush(invalid));assertEquals(0,touched.get());
    }

    @Test void postDecryptionDriverFailureIsRedactedBeforeTransactionOrDml(){
        var codec=new Codec(keys());var original=operation(codec,new Row("1",SECRET,SECRET));
        var touched=new AtomicInteger();
        DataSource unused=(DataSource)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{DataSource.class},
                (proxy,method,args)->{throw new AssertionError("Unexpected acquisition");});
        var connection=(Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},
                (proxy,method,args)->{
                    if(method.getName().equals("getMetaData"))throw new SQLTransientException("driver echoed "+SECRET,"40001",17,new SQLException(SECRET));
                    touched.incrementAndGet();throw new AssertionError("No transaction/fence/DML expected");
                });
        var failure=assertThrows(SQLException.class,()->new Probe(unused,registry()).run(connection,List.of(original)));
        assertInstanceOf(SQLTransientException.class,failure);assertEquals("40001",failure.getSQLState());assertEquals(17,failure.getErrorCode());
        assertNull(failure.getCause());assertNull(failure.getNextException());assertEquals(0,failure.getSuppressed().length);
        assertFalse(failure.toString().contains(SECRET));assertEquals(0,touched.get());
        assertTrue(original.columns().get("contact").startsWith("~E1:"));
    }

    @Test void observedDriverFailureIsRedactedBeforeTheExistingNativeRecorderReceivesIt() {
        var codec = new Codec(keys());
        var original = operation(codec, new Row("observed-id", SECRET, SECRET));
        var untouched = new LinkedHashMap<>(original.columns());
        DataSource unused = (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
                    throw new AssertionError("No source acquisition for a supplied connection");
                });
        var otherJdbcCalls = new AtomicInteger();
        var connection = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getMetaData"))
                        throw new SQLTransientException("driver echoed " + SECRET, "40001", 23, new SQLException(SECRET));
                    otherJdbcCalls.incrementAndGet();
                    throw new AssertionError("No transaction, fence or DML after metadata failure");
                });
        var recorder = new JdbcNativeObservationTest.Recorder();
        var probe = new Probe(unused, registry());
        assertSame(probe, probe.withObservationRecorder(recorder));
        probe.withObservationOriginLinksEnabled(true);
        var failure = assertThrows(SQLException.class, () -> probe.run(connection, List.of(original)));
        assertInstanceOf(SQLTransientException.class, failure);
        assertEquals("40001", failure.getSQLState()); assertEquals(23, failure.getErrorCode());
        assertNull(failure.getCause()); assertNull(failure.getNextException());
        assertEquals(0, failure.getSuppressed().length); assertFalse(failure.toString().contains(SECRET));
        assertEquals(1, recorder.scopes.size());
        var observed = recorder.scopes.getFirst();
        assertSame(failure, observed.failure, "Instrumentation must not see the unredacted driver exception");
        assertEquals(com.reactor.cachedb.core.observe.CacheOperation.FLUSH_BATCH, observed.operation);
        assertEquals(com.reactor.cachedb.core.observe.Tier.SOURCE, observed.tier);
        assertEquals(com.reactor.cachedb.core.observe.Outcome.ERROR, observed.outcome);
        assertEquals(1, observed.bindings); assertEquals(1, observed.closes);
        assertEquals("postgresql", observed.database); assertEquals(0, otherJdbcCalls.get());
        assertEquals(untouched, original.columns());
    }
}
