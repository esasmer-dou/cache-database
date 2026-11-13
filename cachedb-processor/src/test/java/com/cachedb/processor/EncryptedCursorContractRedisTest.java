package com.reactor.cachedb.processor;

import static com.reactor.cachedb.processor.EncryptionCompilerFixture.*;
import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.config.*;
import com.reactor.cachedb.core.crypto.*;
import com.reactor.cachedb.core.model.EntityCodec;
import com.reactor.cachedb.core.query.*;
import com.reactor.cachedb.redis.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.params.ScanParams;

@EnabledIfSystemProperty(named="cachedb.snapshot.redis",matches="true")
class EncryptedCursorContractRedisTest {
    @TempDir Path temp;

    private Map<String,String> sources(){
        return Map.of("sample/CursorRow.java","""
                package sample;
                import com.reactor.cachedb.annotations.*;
                @CacheEntity(table="cursor_rows",redisNamespace="cursor_rows",indexedColumns={"id","status","score"})
                @CacheCompositeIndex(name="status_score",columns={"status"},sortBy="score")
                public record CursorRow(@CacheId(column="id") String id,
                    @CacheColumn("status") String status,@CacheColumn("score") int score,
                    @CacheColumn("contact") @CacheEncrypted(blindIndex=true) String contact) {}
                ""","sample/Scenario.java","""
                package sample;
                public final class Scenario implements java.util.function.Supplier<Object[]> {
                    public Object[] get(){return new Object[]{new CursorRow("1","OPEN",10,"%s"),
                        CursorRowCacheBinding.METADATA,CursorRowCacheBinding.CODEC};}
                }
                """.formatted(SECRET));
    }

    private RedisQueryIndexManager<Object,Object> manager(JedisPooled redis,Loaded fixture,EntityCodec<Object> delegate,
            RedisKeyStrategy keys,AtomicInteger calls,AtomicReference<Object> seen){
        EntityCodec<Object> spy=new EntityCodec<>(){
            public boolean encryptionBound(){return true;}
            public String toRedisValue(Object row){return delegate.toRedisValue(row);}
            public Object fromRedisValue(String value){return delegate.fromRedisValue(value);}
            public Map<String,Object> toColumns(Object row){return delegate.toColumns(row);}
            public Map<String,Object> toIndexColumns(Object row){return delegate.toIndexColumns(row);}
            public Object indexQueryValue(String column,Object value){
                assertEquals("contact",column);calls.incrementAndGet();seen.set(value);
                return delegate.indexQueryValue(column,value);
            }
        };
        return new RedisQueryIndexManager<>(redis,fixture.metadata(),spy,keys,null,
                QueryIndexConfig.defaults(),RelationConfig.defaults(),new QueryEvaluator(),null);
    }

    @Test @Timeout(40) void cursorUsesKeyedLiteralOnlyAndPreservesLogicalResidualAcrossAesRotation() throws Exception {
        String prefix="f3-cursor-"+UUID.randomUUID();
        try(var fixture=load(compile(temp,sources()));var redis=new JedisPooled(
                System.getProperty("cachedb.snapshot.redis.host","127.0.0.1"),Integer.getInteger("cachedb.snapshot.redis.port",16383))){
            var keys=new RedisKeyStrategy(prefix);var eq=QueryFilter.eq("contact",SECRET);
            var query=QuerySpec.where(QueryFilter.eq("status","OPEN")).and(eq).orderBy(QuerySort.asc("score"),QuerySort.asc("id"));
            String first=null;
            try {
                for(var provider:List.of(keys("k1",key("k1",1)),keys("k2",key("k1",1),key("k2",2)))){
                    var codec=fixture.unbound().bindEncryption(provider);var calls=new AtomicInteger();var seen=new AtomicReference<Object>();
                    String contract=manager(redis,fixture,codec,keys,calls,seen).redisLexicalCursorContract(query,"status_score","route","scope");
                    assertEquals(1,calls.get());assertEquals(SECRET,seen.get());assertEquals(SECRET,eq.value());
                    assertTrue(new QueryEvaluator().matches(codec.toColumns(fixture.entity()),query.rootGroup()));
                    assertFalse(contract.contains(SECRET));
                    if(first==null)first=contract;else assertEquals(first,contract);
                }
                byte[] aes=new byte[32],differentBlind=new byte[32];Arrays.fill(aes,(byte)1);Arrays.fill(differentBlind,(byte)0x33);
                var changedKey=new KeyMaterial("k1",aes,differentBlind);
                var codec=fixture.unbound().bindEncryption(new StaticKeyProvider(Map.of("default",List.of(changedKey)),Map.of("default","k1")));
                String changed=manager(redis,fixture,codec,keys,new AtomicInteger(),new AtomicReference<>())
                        .redisLexicalCursorContract(query,"status_score","route","scope");
                assertNotEquals(first,changed,"An unkeyed raw-literal hash would ignore the changed HMAC material");
                assertEquals(SECRET,eq.value());
            } finally {cleanup(redis,prefix);}
        }
    }

    @Test @Timeout(40) void allNewCursorCandidateAndWindowEntriesRejectForbiddenProtectedShapes() throws Exception {
        String prefix="f3-cursor-guard-"+UUID.randomUUID();
        try(var fixture=load(compile(temp,sources()));var redis=new JedisPooled(
                System.getProperty("cachedb.snapshot.redis.host","127.0.0.1"),Integer.getInteger("cachedb.snapshot.redis.port",16383))){
            try {
                var manager=manager(redis,fixture,fixture.codec(),new RedisKeyStrategy(prefix),new AtomicInteger(),new AtomicReference<>());
                var bad=QuerySpec.where(QueryFilter.eq("status","OPEN")).and(QueryFilter.ne("contact",SECRET))
                        .orderBy(QuerySort.asc("score"),QuerySort.asc("id"));
                assertThrows(CacheEncryptionException.class,()->manager.resolveCompositeRangeCandidates(bad));
                assertThrows(CacheEncryptionException.class,()->manager.requireRedisLexicalContract(bad,"status_score"));
                assertThrows(CacheEncryptionException.class,()->manager.redisLexicalCursorContract(bad,"status_score","route","scope"));
                assertThrows(CacheEncryptionException.class,()->manager.redisLexicalWindowFromRows(bad,null,List.of(fixture.entity()),row->true,"status_score"));
                var sort=QuerySpec.where(QueryFilter.eq("status","OPEN")).orderBy(QuerySort.asc("contact"));
                assertThrows(CacheEncryptionException.class,()->manager.redisLexicalCursorContract(sort,"status_score","route","scope"));
            } finally {cleanup(redis,prefix);}
        }
    }

    @Test @Timeout(40) void exactLongV4ContractKeepsProtectedLiteralsKeyedAcrossRotation() throws Exception {
        var longSources=new LinkedHashMap<String,String>();
        sources().forEach((path,source)->longSources.put(path,source.replace("int score","Long score")
                .replace("\"OPEN\",10,","\"OPEN\",10L,")
                .replace("indexedColumns={\"id\",\"status\",\"score\"}","indexedColumns={\"id\",\"status\",\"score\",\"contact\"}")));
        String prefix="f3-long-cursor-"+UUID.randomUUID();
        try(var fixture=load(compile(temp,longSources));var redis=new JedisPooled(
                System.getProperty("cachedb.snapshot.redis.host","127.0.0.1"),Integer.getInteger("cachedb.snapshot.redis.port",16383))) {
            try {
                var keys=new RedisKeyStrategy(prefix);var literal=QueryFilter.eq("contact",SECRET);
                var query=QuerySpec.where(QueryFilter.eq("status","OPEN")).and(literal)
                        .orderBy(QuerySort.asc("score"),QuerySort.asc("id"));
                String first=null;
                for(var provider:List.of(keys("k1",key("k1",1)),keys("k2",key("k1",1),key("k2",2)))) {
                    var calls=new AtomicInteger();var seen=new AtomicReference<Object>();
                    var manager=manager(redis,fixture,fixture.unbound().bindEncryption(provider),keys,calls,seen);
                    String contract=manager.redisLongLexicalCursorContract(query,"status_score","route","scope");
                    assertEquals(1,calls.get());assertEquals(SECRET,seen.get());assertEquals(SECRET,literal.value());
                    assertFalse(contract.contains(SECRET));
                    var cursor=com.reactor.cachedb.core.repository.WindowCursor.encodeRedisLongLexical(9007199254740993L,"1",contract);
                    assertFalse(new String(java.util.Base64.getUrlDecoder().decode(cursor),java.nio.charset.StandardCharsets.UTF_8).contains(SECRET));
                    assertEquals(9007199254740993L,com.reactor.cachedb.core.repository.WindowCursor.decodeRedisLongLexical(cursor,contract).sortValue());
                    if(first==null)first=contract;else assertEquals(first,contract);
                }
                byte[] aes=new byte[32],blind=new byte[32];Arrays.fill(aes,(byte)1);Arrays.fill(blind,(byte)0x33);
                var changed=fixture.unbound().bindEncryption(new StaticKeyProvider(
                        Map.of("default",List.of(new KeyMaterial("k1",aes,blind))),Map.of("default","k1")));
                assertNotEquals(first,manager(redis,fixture,changed,keys,new AtomicInteger(),new AtomicReference<>())
                        .redisLongLexicalCursorContract(query,"status_score","route","scope"));
                var forbidden=QuerySpec.where(QueryFilter.eq("status","OPEN")).and(QueryFilter.ne("contact",SECRET))
                        .orderBy(QuerySort.asc("score"),QuerySort.asc("id"));
                assertThrows(CacheEncryptionException.class,()->manager(redis,fixture,fixture.codec(),keys,
                        new AtomicInteger(),new AtomicReference<>()).requireRedisLongLexicalContract(forbidden,"status_score"));
            } finally {cleanup(redis,prefix);}
        }
    }

    private static void cleanup(JedisPooled redis,String prefix){
        String cursor="0";
        do{var scan=redis.scan(cursor,new ScanParams().match(prefix+"*").count(100));
            if(!scan.getResult().isEmpty())redis.del(scan.getResult().toArray(String[]::new));cursor=scan.getCursor();
        }while(!cursor.equals("0"));
    }
}
