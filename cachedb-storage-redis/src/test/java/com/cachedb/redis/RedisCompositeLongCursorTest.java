package com.reactor.cachedb.redis;

import static com.reactor.cachedb.redis.RedisCompositeLong53Test.*;
import static com.reactor.cachedb.redis.RedisCompositePlannerIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.cache.*;
import com.reactor.cachedb.core.config.*;
import com.reactor.cachedb.core.causal.CacheCausalContext;
import com.reactor.cachedb.core.plan.FetchPlan;
import com.reactor.cachedb.core.query.*;
import com.reactor.cachedb.core.repository.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named="cachedb.snapshot.redis",matches="true")
class RedisCompositeLongCursorTest {
    static QuerySpec ordered(boolean descending, QueryFilter... filters) {
        QuerySpec query = QuerySpec.allOf(QueryFilter.eq("status", "OPEN"));
        for (var filter : filters) query = query.and(filter);
        return query.orderBy(descending ? QuerySort.desc("score") : QuerySort.asc("score"),
                descending ? QuerySort.desc("id") : QuerySort.asc("id")).limitTo(10);
    }
    static CursorPage<TypedRow> page(TypedFixture f, QuerySpec query, WindowRequest window) {
        return f.repo.queryRedisLongLexicalWindow(query, window, "status_score", "timeline", "OPEN", null, null);
    }
    static List<String> ids(CursorPage<TypedRow> page) { return page.items().stream().map(TypedRow::id).toList(); }
    static List<String> scroll(TypedFixture f, QuerySpec query, int size) {
        var ids = new ArrayList<String>(); String token = null; int pages = 0;
        do {
            var result = page(f, query, token == null ? WindowRequest.first(size) : WindowRequest.after(token,size));
            ids.addAll(ids(result)); token = result.nextCursor();
            if (++pages > 20) throw new AssertionError("Cursor did not terminate");
        } while (token != null);
        return ids;
    }

    @Test void epochMillisTiesUseIndexedAscAndDescExactLongContinuation() {
        for (boolean descending : List.of(false,true)) {
            try (var f = new TypedFixture("java.lang.Long", Long::valueOf)) {
                long time = 1800000000000L;
                put(f,"1",time); put(f,"10",time); put(f,"2",time); put(f,"late",time+1);
                QuerySpec query = ordered(descending,QueryFilter.gte("score",time),QueryFilter.lt("score",time+2));
                var fast = f.manager.resolveRedisLongLexicalWindow(query.limitTo(1), null, r->true, "status_score", false).orElseThrow();
                assertEquals(1,fast.rounds()); assertTrue(fast.hasMore());
                assertTrue(f.manager.resolveRedisLongLexicalWindow(query.limitTo(1),fast.continuation(),
                        r->true,"status_score",false).isPresent());
                assertEquals(descending ? List.of("late","2","10","1") : List.of("1","10","2","late"),scroll(f,query,1));
                assertEquals(List.of("late"),ids(page(f,ordered(descending,QueryFilter.gt("score",time)),WindowRequest.first(10))));
                var first = page(f,query,WindowRequest.first(1));
                String contract = f.manager.redisLongLexicalCursorContract(query,"status_score","timeline","OPEN");
                assertEquals(first.items().getFirst().score(),WindowCursor.decodeRedisLongLexical(first.nextCursor(),contract).sortValue());
                assertThrows(IllegalArgumentException.class,()->f.repo.queryRedisLexicalWindow(
                        query,WindowRequest.first(1),"status_score","timeline","OPEN",null,null));
            }
        }
    }

    @Test void deletedAnchorAndInsertsFollowKeysetNotSnapshotSemanticsInBothDirections() {
        long time = 1800000000000L;
        for (boolean descending : List.of(false,true)) {
            try (var f = new TypedFixture("java.lang.Long",Long::valueOf)) {
                for (String id : List.of("10","20","30")) put(f,id,time);
                QuerySpec query = ordered(descending);
                var first = page(f,query,WindowRequest.first(1));
                String anchor = first.items().getFirst().id();
                f.manager.withCompositeMutation(()->{
                    f.manager.removeById(anchor); f.redis.del(f.keys.entityKey("rows",anchor)); return null;
                });
                put(f,descending ? "40" : "05",time);
                put(f,descending ? "25" : "15",time);
                var next = page(f,query,WindowRequest.after(first.nextCursor(),10));
                assertEquals(descending ? List.of("25","20","10") : List.of("15","20","30"),ids(next));
                assertNull(next.nextCursor());
                put(f,descending ? "01" : "99",time);
                assertNull(next.nextCursor(),"A completed page is not a subscription");
            }
        }
    }

    @Test void domainPoisonAfterSafeCursorRetainsExactWideLongOrderAndRange() {
        try (var f = new TypedFixture("java.lang.Long",Long::valueOf)) {
            long edge=RedisLexicalScoreContract.MAX_LONG53;
            put(f,"safe-1",edge-1); put(f,"safe-2",edge);
            var first=page(f,ordered(false),WindowRequest.first(1));
            var writer=writer(f);
            writer.save(new TypedRow("z",edge+1)); writer.save(new TypedRow("a",edge+2));
            writer.save(new TypedRow("max",Long.MAX_VALUE)); writer.save(new TypedRow("min",Long.MIN_VALUE));
            assertEquals((double)(edge+1),(double)(edge+2));
            assertEquals(RedisCompositeMigrationFence.LONG53_UNSAFE,f.redis.get(flag(f)));
            assertEquals(List.of("safe-2","z","a","max"),ids(page(f,ordered(false),
                    WindowRequest.after(first.nextCursor(),10))));
            assertEquals(List.of("min","safe-1","safe-2","z","a","max"),scroll(f,ordered(false),1));
            assertEquals(List.of("max","a","z","safe-2","safe-1","min"),scroll(f,ordered(true),1));
            assertEquals(List.of("a"),ids(page(f,ordered(false,QueryFilter.gt("score",edge+1),
                    QueryFilter.lt("score",Long.MAX_VALUE)),WindowRequest.first(10))));
            assertEquals(List.of("z"),ids(page(f,ordered(true,QueryFilter.lt("score",edge+2),
                    QueryFilter.gt("score",edge)),WindowRequest.first(10))));
            assertTrue(f.manager.resolveRedisLongLexicalWindow(ordered(false),
                    new RedisQueryIndexManager.CompositeLongPosition(edge+1,"z"),r->true,"status_score",false).isEmpty());
            assertEquals(List.of("a","max"),ids(f.repo.queryRedisLongLexicalWindow(ordered(false),
                    WindowRequest.first(10),"status_score","timeline","OPEN",edge+1,"z")));
        }
    }

    @Test void missingMarkerAndUnsafeNegativeAdjacentValuesUseCompleteExactFallback() {
        try (var f=new TypedFixture("java.lang.Long",Long::valueOf)) {
            long low=-9007199254740993L, high=low+1;
            put(f,"z",low);put(f,"a",high);put(f,"b",high);put(f,"safe",0);
            f.redis.del(flag(f));
            assertEquals(List.of("z","a","b","safe"),scroll(f,ordered(false),1));
            assertEquals(List.of("safe","b","a","z"),scroll(f,ordered(true),1));
            assertEquals(List.of("a","b","safe"),ids(page(f,ordered(false,QueryFilter.gt("score",low)),WindowRequest.first(10))));
            assertNull(f.redis.get(flag(f)));
        }
    }

    @Test void contractsRejectChangedFilterRouteScopeDirectionAndWrongTypedSeek() {
        try(var f=new TypedFixture("java.lang.Long",Long::valueOf)) {
            put(f,"1",1);put(f,"2",2);
            var first=page(f,ordered(false),WindowRequest.first(1));
            var window=WindowRequest.after(first.nextCursor(),1);
            assertThrows(CursorContractMismatchException.class,()->page(f,ordered(true),window));
            assertThrows(CursorContractMismatchException.class,()->page(f,ordered(false,QueryFilter.gt("score",0L)),window));
            assertThrows(CursorContractMismatchException.class,()->f.repo.queryRedisLongLexicalWindow(
                    ordered(false),window,"status_score","different","OPEN",null,null));
            assertThrows(CursorContractMismatchException.class,()->f.repo.queryRedisLongLexicalWindow(
                    ordered(false),window,"status_score","timeline","different",null,null));
            assertThrows(IllegalArgumentException.class,()->f.repo.queryRedisLongLexicalWindow(
                    ordered(false),window,"status_score","timeline","OPEN",1L,"1"));
            assertThrows(IllegalArgumentException.class,()->f.repo.queryRedisLongLexicalWindow(
                    ordered(false),WindowRequest.first(1),"status_score","timeline","OPEN",1L,null));
            for(Object bad:List.of(1,1d,new java.math.BigDecimal("1")))
                assertThrows(IllegalArgumentException.class,()->page(f,ordered(false,QueryFilter.gt("score",bad)),WindowRequest.first(1)));
            assertThrows(IllegalArgumentException.class,()->page(f,ordered(false,QueryFilter.ne("score",1L)),WindowRequest.first(1)));
            try(var causal=CacheCausalContext.withMinVersion("rows","absent",1)) {
                var failure=assertThrows(IllegalStateException.class,()->page(f,ordered(false),WindowRequest.first(1)));
                assertTrue(failure.getMessage().contains("causal"));
            }
        }
    }

    @Test void domainMarkerIsCheckedBySameRangeLuaOnContinuationWithoutNewAuthority() {
        try(var f=new TypedFixture("java.lang.Long",Long::valueOf)) {
            put(f,"1",1);put(f,"2",2);put(f,"3",3);
            var first=page(f,ordered(false),WindowRequest.first(1));
            var calls=new AtomicInteger();
            var client=new RedisClientOperations() {
                public redis.clients.jedis.UnifiedJedis commands(){return f.redis;}
                public Object eval(String script,List<String> keys,List<String> args) {
                    if(script.contains("local probes=0")) {
                        calls.incrementAndGet();assertEquals(7,keys.size());assertEquals(flag(f),keys.get(6));
                        f.redis.set(flag(f),RedisCompositeMigrationFence.LONG53_UNSAFE);
                    }
                    return RedisClientOperations.super.eval(script,keys,args);
                }
            };
            var manager=manager(f,client);
            var repo=repository(client,f.metadata,f.codec,f.keys,manager,CachePolicy.builder()
                    .hotEntityLimit(CachePolicy.UNBOUNDED).lruEvictionEnabled(false).build(),
                    null,ReadThroughConfig.defaults(),(r,c)->{});
            assertEquals(List.of("2","3"),ids(repo.queryRedisLongLexicalWindow(ordered(false),
                    WindowRequest.after(first.nextCursor(),10),"status_score","timeline","OPEN",null,null)));
            assertEquals(1,calls.get());
        }
    }

    @Test void boundedPolicyRefillDiscardsPartialWorkAndKeepsFetchPlanAndTombstones() {
        try(var f=new TypedFixture("java.lang.Long",Long::valueOf)) {
            for(int i=0;i<301;i++)put(f,Integer.toString(i),1800000000000L+i);
            f.redis.set(f.keys.tombstoneKey("rows","299"),"1");
            var hot=EntityHotPolicy.builder().mode(EntityHotPolicyMode.CUSTOM_PREDICATE)
                    .customPredicate(columns->(Long)columns.get("score")>=1800000000299L)
                    .evictWhenRejected(false).build();
            var fetches=new AtomicInteger();
            var repo=repository(JedisPooledOperations.of(f.redis),f.metadata,f.codec,f.keys,f.manager,
                    CachePolicy.builder().hotEntityLimit(CachePolicy.UNBOUNDED).lruEvictionEnabled(false).hotPolicy(hot).build(),
                    null,ReadThroughConfig.defaults(),(rows,context)->{
                        assertEquals(List.of("300"),rows.stream().map(TypedRow::id).toList());fetches.incrementAndGet();
                    });
            assertEquals(List.of("300"),ids(repo.queryRedisLongLexicalWindow(ordered(false).fetching(FetchPlan.of("children")),
                    WindowRequest.first(1),"status_score","timeline","OPEN",null,null)));
            assertEquals(1,fetches.get());assertTrue(f.manager.compositeBoundedFallbacks()>0);
            assertEquals(4,RedisQueryIndexManager.COMPOSITE_MAX_ROUNDS);
            assertEquals(10000,RedisQueryIndexManager.COMPOSITE_MAX_EXAMINED_IDS);
        }
    }

    @Test void completeSourceFallbackUsesLongOrderNotSqlOrDoubleOrder() {
        try(var f=new TypedFixture("java.lang.Long",Long::valueOf)) {
            long edge=9007199254740992L;
            put(f,"z",edge);put(f,"a",edge+1);
            f.redis.del(f.keys.entityKey("rows","a"));
            var loads=new AtomicInteger();
            var read=ReadThroughConfig.builder().mode(ReadThroughMode.READ_THROUGH_QUERY)
                    .hydrateLoadedEntities(false).maxQueryLoadRows(5).build();
            var repo=repository(JedisPooledOperations.of(f.redis),f.metadata,f.codec,f.keys,f.manager,
                    CachePolicy.builder().hotEntityLimit(CachePolicy.UNBOUNDED).lruEvictionEnabled(false).build(),
                    query->{loads.incrementAndGet();return List.of(new TypedRow("a",edge+1),new TypedRow("z",edge));},
                    read,(rows,context)->{});
            var first=repo.queryRedisLongLexicalWindow(ordered(false),WindowRequest.first(1),
                    "status_score","timeline","OPEN",null,null);
            assertEquals(List.of("z"),ids(first));assertNotNull(first.nextCursor());
            assertEquals(List.of("a"),ids(repo.queryRedisLongLexicalWindow(ordered(false),WindowRequest.after(first.nextCursor(),1),
                    "status_score","timeline","OPEN",null,null)));
            assertEquals(2,loads.get());
        }
    }
    @Test void tenantContractAndStoredOwnerStayEnforcedOnIndexedAndPoisonedPages() {
        try(var f=new TypedFixture("java.lang.Long",Long::valueOf)) {
            var metadata=new com.reactor.cachedb.core.model.EntityMetadata<TypedRow,String>() {
                public String entityName(){return "TenantLong";}
                public String tableName(){return "rows";}
                public String redisNamespace(){return "rows";}
                public String idColumn(){return "id";}
                public Class<TypedRow> entityType(){return TypedRow.class;}
                public java.util.function.Function<TypedRow,String> idAccessor(){return TypedRow::id;}
                public List<String> columns(){return List.of("id","tenant","status","score");}
                public Map<String,String> columnTypes(){return Map.of("id","java.lang.String","tenant","java.lang.String",
                        "status","java.lang.String","score","java.lang.Long");}
                public List<com.reactor.cachedb.core.model.RelationDefinition> relations(){return List.of();}
                public Map<String,List<String>> compositeExactIndexes(){return Map.of("tenant_status",List.of("tenant","status"));}
                public Map<String,String> compositeSortColumns(){return Map.of("tenant_status","score");}
                public String tenantFieldName(){return "tenant";}
                public String extractTenantId(TypedRow row){return row.id().substring(0,1);}
            };
            var codec=new com.reactor.cachedb.core.model.EntityCodec<TypedRow>() {
                public String toRedisValue(TypedRow row){return f.codec.toRedisValue(row);}
                public TypedRow fromRedisValue(String value){return f.codec.fromRedisValue(value);}
                public Map<String,Object> toColumns(TypedRow row){return Map.of("id",row.id(),"tenant",metadata.extractTenantId(row),
                        "status","OPEN","score",row.score());}
            };
            var client=JedisPooledOperations.of(f.redis);
            var manager=new RedisQueryIndexManager<>(client,metadata,codec,f.keys,null,QueryIndexConfig.defaults(),
                    RelationConfig.defaults(),new QueryEvaluator(),null);
            assertTrue(manager.rebuildCompositeIndexesFenced(false,"empty").degradedCleared());
            for(var row:List.of(new TypedRow("a1",1L),new TypedRow("a2",2L),new TypedRow("a3",3L),new TypedRow("b1",1L))) {
                manager.withCompositeMutation(()->{
                    manager.validateCompositeValues(codec.toColumns(row));
                    f.redis.set(f.keys.entityKey("rows",row.id()),codec.toRedisValue(row));
                    f.redis.set(f.keys.versionKey("rows",row.id())+":tenant",metadata.extractTenantId(row));
                    manager.reindex(row);return null;
                });
            }
            var repo=repository(client,metadata,codec,f.keys,manager,CachePolicy.builder()
                    .hotEntityLimit(CachePolicy.UNBOUNDED).lruEvictionEnabled(false).build(),
                    null,ReadThroughConfig.defaults(),(rows,context)->{});
            String cursor;
            try(var tenant=com.reactor.cachedb.core.tenant.CacheTenantContext.withTenant("a")) {
                var first=repo.queryRedisLongLexicalWindow(ordered(false),WindowRequest.first(1),
                        "tenant_status","timeline","same",null,null);
                assertEquals(List.of("a1"),ids(first));cursor=first.nextCursor();assertNotNull(cursor);
                f.redis.set(f.keys.versionKey("rows","a2")+":tenant","b");
                assertEquals(List.of("a3"),ids(repo.queryRedisLongLexicalWindow(ordered(false),WindowRequest.after(cursor,10),
                        "tenant_status","timeline","same",null,null)));
                f.redis.set(f.keys.compositeIndexSignatureKey("rows")+":long53:tenant_status",
                        RedisCompositeMigrationFence.LONG53_UNSAFE);
                assertEquals(List.of("a3"),ids(repo.queryRedisLongLexicalWindow(ordered(false),WindowRequest.after(cursor,10),
                        "tenant_status","timeline","same",null,null)));
                assertTrue(f.redis.exists(f.keys.entityKey("rows","a2")),"A foreign owner must never cause destructive admission");
            }
            try(var tenant=com.reactor.cachedb.core.tenant.CacheTenantContext.withTenant("b")) {
                String saved=cursor;
                assertThrows(CursorContractMismatchException.class,()->repo.queryRedisLongLexicalWindow(ordered(false),
                        WindowRequest.after(saved,10),"tenant_status","timeline","same",null,null));
            }
        }
    }

    @Test void wideContinuationAfterSafeRebuildUsesExactFallbackAndCapacityNeverTruncates() {
        try(var f=new TypedFixture("java.lang.Long",Long::valueOf)) {
            put(f,"1",1);put(f,"2",2);
            assertEquals(RedisCompositeMigrationFence.LONG53_VERIFIED,f.redis.get(flag(f)));
            assertTrue(f.manager.resolveRedisLongLexicalWindow(ordered(false),
                    new RedisQueryIndexManager.CompositeLongPosition(Long.MIN_VALUE,"absent"),r->true,"status_score",false).isEmpty());
            assertEquals(List.of("1","2"),ids(f.repo.queryRedisLongLexicalWindow(ordered(false),WindowRequest.first(10),
                    "status_score","timeline","OPEN",Long.MIN_VALUE,"absent")));
            assertTrue(f.repo.queryRedisLongLexicalWindow(ordered(false),WindowRequest.first(10),
                    "status_score","timeline","OPEN",Long.MAX_VALUE,"absent").items().isEmpty());
            assertThrows(IllegalStateException.class,()->f.manager.redisLongLexicalWindowFromRows(ordered(false),null,
                    Collections.nCopies(10001,new TypedRow("x",1L)),r->true,"status_score"));
            assertThrows(IllegalStateException.class,()->f.manager.redisLongLexicalWindowFromRows(ordered(false),null,
                    List.of(new TypedRow("x",1d)),r->true,"status_score"));
        }
    }

    @Test void unicodeMemberTiesUseRedisBytesOnFastAndExactFallbackPaths() {
        for(boolean unsafe:List.of(false,true)) {
            try(var f=new TypedFixture("java.lang.Long",Long::valueOf)) {
                long value=unsafe ? 9007199254740993L : 1800000000000L;
                put(f,"\uE000",value);put(f,"\uD83D\uDE00",value);put(f,"a",value);
                assertEquals(List.of("a","\uE000","\uD83D\uDE00"),scroll(f,ordered(false),1));
                assertEquals(List.of("\uD83D\uDE00","\uE000","a"),scroll(f,ordered(true),1));
            }
        }
    }

}
