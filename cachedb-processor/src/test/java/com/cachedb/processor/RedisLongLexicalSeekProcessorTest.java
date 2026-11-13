package com.reactor.cachedb.processor;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.repository.*;
import com.reactor.cachedb.core.config.*;
import java.nio.file.*;
import java.util.*;
import javax.tools.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import redis.clients.jedis.JedisPooled;

class RedisLongLexicalSeekProcessorTest {
    @TempDir Path temp;
    private static String entity(String type) {
        return """
                package sample;
                import com.reactor.cachedb.annotations.*;
                @CacheEntity(table="sample_orders",redisNamespace="sample-orders",indexedColumns={"customer_id","status","order_date"})
                @CacheCompositeIndex(name="customer_status_date",columns={"customerId","status"},sortBy="orderDate")
                public class OrderEntity {
                    @CacheId(column="order_id") public Long orderId;
                    @CacheColumn("customer_id") public Long customerId;
                    @CacheColumn("status") public String status;
                    @CacheColumn("order_date") public %s orderDate;
                    @CacheColumn("note") public String note;
                    public OrderEntity() {}
                }
                """.formatted(type);
    }
    private static String repository(String parameterType, boolean descending) {
        String direction=descending ? "DESC" : "ASC", seek=descending ? "LT" : "GT";
        return """
                package sample;
                import com.reactor.cachedb.annotations.*;
                import com.reactor.cachedb.core.repository.*;
                @CacheRepository(entity=OrderEntity.class,springBean=false)
                public interface Orders extends CacheDbRepository<OrderEntity,Long> {
                    @HotRoute(value="timeline",coverageScopeParameter="customerId")
                    @CacheRouteQuery(redisLexicalIdTie=true,redisCompositeIndex="customer_status_date",
                        predicates={@CachePredicate(field="customerId"),@CachePredicate(field="status",constants="OPEN"),
                            @CachePredicate(field="orderDate",parameter="minimum",operator=CachePredicate.Operator.GTE)},
                        orderBy=@CacheOrder(field="orderDate",direction=CacheOrder.Direction.%s))
                    HotWindow<OrderEntity> timeline(Long customerId,Long minimum,WindowRequest window);
                    @HotRoute(value="timeline-seek",coverageScopeParameter="customerId")
                    @CacheRouteQuery(redisLexicalIdTie=true,redisCompositeIndex="customer_status_date",
                        predicates={@CachePredicate(field="customerId"),@CachePredicate(field="status",constants="OPEN"),
                            @CachePredicate(field="orderDate",parameter="minimum",operator=CachePredicate.Operator.GTE)},
                        orderBy=@CacheOrder(field="orderDate",direction=CacheOrder.Direction.%s),
                        seek={@CacheSeek(field="orderDate",param="afterValue",direction=CachePredicate.Operator.%s),
                              @CacheSeek(field="orderId",param="afterId",direction=CachePredicate.Operator.%s)})
                    HotWindow<OrderEntity> seek(Long customerId,Long minimum,WindowRequest window,%s afterValue,String afterId);
                }
                """.formatted(direction,direction,seek,seek,parameterType);
    }

    @Test void realSampleLongAliasesGenerateExactNativeDispatchForBothDirections() throws Exception {
        for(boolean descending:List.of(false,true)) {
            var result=compile(Map.of("OrderEntity.java",entity("Long"),"Orders.java",repository("Long",descending)));
            assertTrue(result.success(),result.diagnostics());
            String generated=Files.readString(result.generated().resolve("sample/OrdersCacheDbImplementation.java"));
            assertTrue(generated.contains("RedisLexicalWindowRepository.readLong("),generated);
            assertFalse(generated.contains("RedisLexicalWindowRepository.read("),generated);
            assertFalse(generated.contains("Double.valueOf"),generated);
            assertFalse(generated.contains("KeysetPagination.apply("),generated);
            assertTrue(generated.contains("afterValue, afterId)"),generated);
            assertTrue(generated.contains("\"order_date\""),generated);
        }
    }

    @Test void exactNullableLongSeekAndUnsupportedDomainsFailPrecisely() throws Exception {
        for(String type:List.of("Double","long")) {
            var result=compile(Map.of("OrderEntity.java",entity("Long"),"Orders.java",repository(type,false)));
            assertFalse(result.success());
            assertTrue(result.diagnostics().contains("nullable Long value and String ID"),result.diagnostics());
        }
        for(String type:List.of("Double","Float","java.time.Instant","java.time.LocalDateTime","java.time.OffsetDateTime")) {
            String routes=repository("Long",false).replace("@CachePredicate(field=\"orderDate\",parameter=\"minimum\",operator=CachePredicate.Operator.GTE)",
                    "@CachePredicate(field=\"status\",constants=\"OPEN\")");
            var result=compile(Map.of("OrderEntity.java",entity(type),"Orders.java",routes));
            assertFalse(result.success(),type);
            assertTrue(result.diagnostics().contains("lossless score type"),result.diagnostics());
        }
    }

    @Test void unindexedAndUnsupportedLongPredicatesNeverBecomeSilentScanRoutes() throws Exception {
        for(String predicate:List.of("@CachePredicate(field=\"note\",constants=\"x\")",
                "@CachePredicate(field=\"orderDate\",operator=CachePredicate.Operator.NE,constants=\"1\",constantType=CachePredicate.ConstantType.LONG)")) {
            String repository=repository("Long",false).replace("@CachePredicate(field=\"orderDate\",parameter=\"minimum\",operator=CachePredicate.Operator.GTE)",predicate);
            var result=compile(Map.of("OrderEntity.java",entity("Long"),"Orders.java",repository));
            assertFalse(result.success());
            assertTrue(result.diagnostics().contains("predicate is not indexed") ||
                    result.diagnostics().contains("typed ranges on sortBy only"),result.diagnostics());
        }
    }

    @Test @Timeout(60) @EnabledIfSystemProperty(named="cachedb.snapshot.redis",matches="true")
    @SuppressWarnings("unchecked")
    void generatedSampleOrderRouteExecutesNativeRedisLongRangeSeekAndUnsafeFallback() throws Exception {
        String scenario="""
                package sample;
                import com.reactor.cachedb.core.cache.*;
                import com.reactor.cachedb.core.config.*;
                import com.reactor.cachedb.core.repository.*;
                import com.reactor.cachedb.redis.*;
                import com.reactor.cachedb.starter.CacheDatabase;
                public final class Scenario implements java.util.function.BiFunction<redis.clients.jedis.JedisPooled,String,java.util.List<String>> {
                    private OrderEntity row(long id,long value) {
                        var row=new OrderEntity();row.orderId=id;row.customerId=7L;row.status="OPEN";row.orderDate=value;row.note="n";return row;
                    }
                    public java.util.List<String> apply(redis.clients.jedis.JedisPooled redis,String prefix) {
                        var config=CacheDatabaseConfig.builder().runtimeRole(CacheRuntimeRole.CLIENT_ONLY)
                            .keyspace(KeyspaceConfig.builder().keyPrefix(prefix).build())
                            .redisGuardrail(RedisGuardrailConfig.builder().enabled(false).build())
                            .writeBehind(WriteBehindConfig.builder().streamKey(prefix+":writes")
                                .deadLetterStreamKey(prefix+":dead").consumerGroup(prefix).build()).build();
                        javax.sql.DataSource source=(javax.sql.DataSource)java.lang.reflect.Proxy.newProxyInstance(
                            getClass().getClassLoader(),new Class<?>[]{javax.sql.DataSource.class},(proxy,method,args)->{
                                throw new AssertionError("Redis-only route must not acquire a JDBC connection: "+method.getName());
                            });
                        try(var database=new CacheDatabase(redis,source,config)) {
                            var policy=CachePolicy.builder().hotEntityLimit(CachePolicy.UNBOUNDED).lruEvictionEnabled(false).build();
                            database.register(OrderEntityCacheBinding.METADATA,OrderEntityCacheBinding.CODEC,policy);
                            var raw=database.repository(OrderEntityCacheBinding.METADATA,OrderEntityCacheBinding.CODEC,policy);
                            if(!(raw instanceof RedisEntityRepository<?,?>))throw new AssertionError("Native Redis capability lost");
                            ((RedisEntityRepository<?,?>)raw).rebuildCompositeIndexesFenced(false,"empty");
                            raw.save(row(1,1800000000000L));raw.save(row(10,1800000000000L));raw.save(row(2,1800000000001L));
                            raw.save(row(99,1799999999999L));
                            var routes=new OrdersCacheDbImplementation(database);
                            var first=routes.timeline(7L,1800000000000L,WindowRequest.first(1));
                            var second=routes.timeline(7L,1800000000000L,WindowRequest.after(first.nextCursor(),1));
                            var seek=routes.seek(7L,1800000000000L,WindowRequest.first(10),1800000000000L,"1");
                            raw.save(row(8,9007199254740992L));raw.save(row(3,9007199254740993L));
                            var rest=routes.timeline(7L,1800000000000L,WindowRequest.after(second.nextCursor(),10));
                            return java.util.List.of(first.items().getFirst().orderId.toString(),second.items().getFirst().orderId.toString(),
                                seek.items().stream().map(r->r.orderId.toString()).collect(java.util.stream.Collectors.joining(",")),
                                rest.items().stream().map(r->r.orderId.toString()).collect(java.util.stream.Collectors.joining(",")));
                        }
                    }
                }
                """;
        var result=compile(Map.of("OrderEntity.java",entity("Long"),"Orders.java",repository("Long",false),"Scenario.java",scenario));
        assertTrue(result.success(),result.diagnostics());
        String prefix="generated-long-"+UUID.randomUUID();
        try(var loader=new java.net.URLClassLoader(new java.net.URL[]{result.classes().toUri().toURL()},getClass().getClassLoader());
                var redis=new JedisPooled("redis://"+System.getProperty("cachedb.snapshot.redis.host","127.0.0.1")+":"
                        +Integer.getInteger("cachedb.snapshot.redis.port",16383))) {
            var run=(java.util.function.BiFunction<JedisPooled,String,List<String>>)loader.loadClass("sample.Scenario").getConstructor().newInstance();
            assertEquals(List.of("1","10","10,2","2,8,3"),run.apply(redis,prefix));
        } finally {cleanup(prefix);}
    }

    private static void cleanup(String prefix) {
        try(var redis=new JedisPooled("redis://"+System.getProperty("cachedb.snapshot.redis.host","127.0.0.1")+":"
                +Integer.getInteger("cachedb.snapshot.redis.port",16383))) {
            String cursor="0";int scans=0;
            do {
                var scan=redis.scan(cursor,new redis.clients.jedis.params.ScanParams().match(prefix+":*").count(256));
                for(String key:scan.getResult())redis.unlink(key);cursor=scan.getCursor();
                if(++scans>10000)throw new IllegalStateException("Cleanup bound exceeded");
            } while(!cursor.equals("0"));
        }
    }
    private Result compile(Map<String,String> sources) throws Exception {
        Path root=Files.createTempDirectory(temp,"long-seek-"),input=Files.createDirectories(root.resolve("sources/sample"));
        Path generated=Files.createDirectories(root.resolve("generated")),classes=Files.createDirectories(root.resolve("classes"));
        var paths=new ArrayList<java.io.File>();
        for(var source:sources.entrySet()) {
            Path path=input.resolve(source.getKey());Files.writeString(path,source.getValue());paths.add(path.toFile());
        }
        var compiler=ToolProvider.getSystemJavaCompiler();var diagnostics=new DiagnosticCollector<JavaFileObject>();
        try(var manager=compiler.getStandardFileManager(diagnostics,Locale.ROOT,null)) {
            var task=compiler.getTask(null,manager,diagnostics,List.of("--release","21","-proc:full","-classpath",
                    System.getProperty("java.class.path"),"-s",generated.toString(),"-d",classes.toString()),null,
                    manager.getJavaFileObjectsFromFiles(paths));
            task.setProcessors(List.of(new CacheEntityProcessor(),new CacheRepositoryProcessor()));
            boolean success=Boolean.TRUE.equals(task.call());
            return new Result(success,generated,classes,diagnostics.getDiagnostics().stream().map(d->d.getMessage(Locale.ROOT))
                    .reduce("",(a,b)->a+"\n"+b));
        }
    }
    private record Result(boolean success,Path generated,Path classes,String diagnostics) {}
}
