package com.reactor.cachedb.spring.data;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.api.*;
import com.reactor.cachedb.core.cache.*;
import com.reactor.cachedb.core.crypto.*;
import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.core.plan.FetchPlan;
import com.reactor.cachedb.core.query.*;
import com.reactor.cachedb.core.testing.InMemoryCacheDatabase;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.spring.data.cryptofixture.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.*;

class SpringDataProtectedWindowTest {
    private static final String SECRET="PII_WINDOW_LOW_ENTROPY_9472";
    private static final EntityMetadata<EncryptedWindowRow,Long> META=EncryptedWindowRowCacheBinding.METADATA;
    private static final List<QuerySort> SORTS=List.of(QuerySort.asc("ranking"),QuerySort.asc("id"));
    private static KeyMaterial key(String id,int aes,int blind){
        byte[] encryption=new byte[32],hmac=new byte[32];Arrays.fill(encryption,(byte)aes);Arrays.fill(hmac,(byte)blind);
        return new KeyMaterial(id,encryption,hmac);
    }
    private static CacheKeyProvider provider(String current,KeyMaterial... keys){
        return new StaticKeyProvider(Map.of("default",List.of(keys)),Map.of("default",current));
    }
    private static CacheKeyProvider oldKeys(){return provider("k1",key("k1",1,70));}
    private static CacheKeyProvider rotated(){return provider("k2",key("k1",1,70),key("k2",2,70));}
    private static EntityCodec<EncryptedWindowRow> codec(CacheKeyProvider keys){return EncryptedWindowRowCacheBinding.CODEC.bindEncryption(keys);}
    private static QuerySpec query(){return QuerySpec.where(QueryFilter.eq("contact",SECRET));}
    private static String contract(EntityMetadata<?,?> metadata,EntityCodec<?> codec,QuerySpec query,String route,String scope,List<QuerySort> sorts){
        return SpringDataQueryFingerprint.contract(query,route,scope,sorts,metadata,codec);
    }
    private static void safe(CacheEncryptionException failure){
        assertNull(failure.getCause());assertEquals(0,failure.getSuppressed().length);assertFalse(failure.toString().contains(SECRET));
    }

    private static final class RuntimeSession implements CacheSession {
        final InMemoryCacheDatabase data;final CacheKeyProvider provider;final EntityCodec<EncryptedWindowRow> explicit;
        int reads,registrations;QuerySpec last;EntityCodec<?> received;
        RuntimeSession(InMemoryCacheDatabase data,CacheKeyProvider provider){this(data,provider,null);}
        RuntimeSession(InMemoryCacheDatabase data,CacheKeyProvider provider,EntityCodec<EncryptedWindowRow> explicit){
            this.data=data;this.provider=provider;this.explicit=explicit;
        }
        @Override @SuppressWarnings("unchecked")
        public <T,ID> EntityCodec<T> runtimeCodec(EntityMetadata<T,ID> metadata,EntityCodec<T> requested){
            EncryptedStorageChecks.sameMapping(metadata,META);
            return EncryptedStorageChecks.bind(metadata,explicit==null?requested:(EntityCodec<T>)explicit,provider);
        }
        public <T,ID> EntityRepository<T,ID> repository(EntityMetadata<T,ID> metadata,EntityCodec<T> codec){
            registrations++;received=codec;EncryptedStorageChecks.requireBound(metadata,codec);
            var delegate=data.repository(metadata,codec);
            return new EntityRepository<>(){
                public Optional<T> findById(ID id){return delegate.findById(id);}
                public List<T> findAll(Collection<ID> ids){return delegate.findAll(ids);}
                public List<T> findPage(PageWindow page){return delegate.findPage(page);}
                public QueryExplainPlan explain(QuerySpec query){return delegate.explain(query);}
                public List<T> query(QuerySpec query){reads++;last=query;return delegate.query(query);}
                public T save(T row){return delegate.save(row);}
                public List<WriteReceipt<T,ID>> saveAll(Collection<T> rows){return delegate.saveAll(rows);}
                public void deleteById(ID id){delegate.deleteById(id);}
                public EntityRepository<T,ID> withFetchPlan(FetchPlan plan){return this;}
            };
        }
        public <T,ID> EntityRepository<T,ID> repository(EntityMetadata<T,ID> metadata,EntityCodec<T> codec,CachePolicy policy){
            return repository(metadata,codec);
        }
    }

    private static final class SensitiveProbe extends AbstractCacheDbSpringDataRepository<EncryptedWindowRow,Long> {
        SensitiveProbe(CacheSession session){super(session,META,EncryptedWindowRowCacheBinding.CODEC,
                Map.of("id","id","ranking","ranking","contact","contact","tenant","tenant_id"));}
        Window<EncryptedWindowRow> scroll(String literal,ScrollPosition position){
            return window(QuerySpec.where(QueryFilter.eq("contact",literal)),position,Sort.by("ranking"),Limit.of(2),0,"explicit-sensitive-route");
        }
        Window<EncryptedWindowRow> sorted(String literal,Sort sort){
            return window(QuerySpec.where(QueryFilter.eq("contact",literal)),ScrollPosition.keyset(),sort,Limit.of(2),0,"explicit-sensitive-route");
        }
    }

    @Test void generatedAdapterUsesBoundCodecAndAesRotationPreservesPositionsWithoutRewritingLogicalFilters(){
        var data=new InMemoryCacheDatabase();var firstSession=new RuntimeSession(data,oldKeys());
        var generated=new EncryptedWindowsCacheDbImplementation(firstSession);
        var first=new SensitiveProbe(firstSession);
        assertTrue(firstSession.received.encryptionBound());assertFalse(EncryptedWindowRowCacheBinding.CODEC.encryptionBound());
        try(var tenant=CacheTenantContext.withTenant("a")){
            generated.saveAll(List.of(new EncryptedWindowRow(1L,"a",10,SECRET),new EncryptedWindowRow(2L,"a",10,SECRET),
                    new EncryptedWindowRow(3L,"a",10,SECRET)));
            assertEquals(List.of(1L,2L),generated.findFirst2ByRankingOrderByIdAsc(10,ScrollPosition.keyset()).getContent().stream().map(EncryptedWindowRow::id).toList());
            var page=first.scroll(SECRET,ScrollPosition.keyset());
            assertEquals(List.of(1L,2L),page.getContent().stream().map(EncryptedWindowRow::id).toList());
            var after=(KeysetScrollPosition)page.positionAt(1);
            assertFalse(after.getKeys().toString().contains(SECRET));
            assertEquals(SECRET,firstSession.last.filters().stream().filter(f->f.column().equals("contact")).findFirst().orElseThrow().value());
            var nextSession=new RuntimeSession(data,rotated());var next=new SensitiveProbe(nextSession);
            assertEquals(List.of(3L),next.scroll(SECRET,after).getContent().stream().map(EncryptedWindowRow::id).toList());
            assertEquals(SECRET,nextSession.last.filters().stream().filter(f->f.column().equals("contact")).findFirst().orElseThrow().value());
            int reads=nextSession.reads;
            assertThrows(IllegalArgumentException.class,()->next.scroll("changed",after));
            assertEquals(reads,nextSession.reads);
        }
    }

    @Test void changedHmacMaterialChangesContractAndRejectsExistingPositionBeforeDelegateQuery(){
        var data=new InMemoryCacheDatabase();var original=new RuntimeSession(data,oldKeys());
        var repository=new SensitiveProbe(original);
        try(var tenant=CacheTenantContext.withTenant("a")){
            repository.save(new EncryptedWindowRow(1L,"a",10,SECRET));
            var after=(KeysetScrollPosition)repository.scroll(SECRET,ScrollPosition.keyset()).positionAt(0);
            var changedKeys=provider("k2",key("k1",1,71),key("k2",2,71));
            assertNotEquals(contract(META,codec(oldKeys()),query(),"route","tenant:a",SORTS),
                    contract(META,codec(changedKeys),query(),"route","tenant:a",SORTS));
            var changed=new RuntimeSession(data,changedKeys);var adapter=new SensitiveProbe(changed);
            assertThrows(IllegalArgumentException.class,()->adapter.scroll(SECRET,after));
            assertEquals(0,changed.reads);
        }
    }

    @Test void missingIntentCapabilityFailsClosedForInitialAndContinuationWindows(){
        var delegate=codec(oldKeys());
        var missing=new EntityCodec<EncryptedWindowRow>(){
            public boolean encryptionBound(){return true;}
            public String toRedisValue(EncryptedWindowRow row){return delegate.toRedisValue(row);}
            public EncryptedWindowRow fromRedisValue(String value){return delegate.fromRedisValue(value);}
            public Map<String,Object> toColumns(EncryptedWindowRow row){return delegate.toColumns(row);}
            public Object indexQueryValue(String column,Object value){return delegate.indexQueryValue(column,value);}
        };
        var session=new RuntimeSession(new InMemoryCacheDatabase(),oldKeys(),missing);
        var repository=new SensitiveProbe(session);
        try(var tenant=CacheTenantContext.withTenant("a")){
            for(ScrollPosition position:List.of(ScrollPosition.keyset(),ScrollPosition.forward(Map.of("$cachedb:cursor","untrusted")))){
                var failure=assertThrows(CacheEncryptionException.class,()->repository.scroll(SECRET,position));
                assertEquals(CacheEncryptionException.Code.INTENT_KEY_UNAVAILABLE,failure.code());safe(failure);
            }
            assertEquals(0,session.reads);
        }
        var unconfigured=new RuntimeSession(new InMemoryCacheDatabase(),null);
        safe(assertThrows(CacheEncryptionException.class,()->new EncryptedWindowsCacheDbImplementation(unconfigured)));
        assertEquals(0,unconfigured.registrations);
    }

    @Test void protectedContractBindsMappingRouteScopeSortAndTypedStructureButNeverMutatesQuery(){
        var bound=codec(oldKeys());var eq=QueryFilter.eq("contact",SECRET);var logical=QuerySpec.where(eq);
        String first=contract(META,bound,logical,"route","tenant:a",SORTS);
        assertTrue(first.startsWith("~I1:"));assertEquals(47,first.length());
        assertNotEquals(SpringDataQueryFingerprint.contract(logical,"route","tenant:a",SORTS),first);
        assertEquals(first,contract(META,codec(rotated()),logical,"route","tenant:a",SORTS));
        assertNotEquals(first,contract(META,bound,logical,"other-route","tenant:a",SORTS));
        assertNotEquals(first,contract(META,bound,logical,"route","tenant:b",SORTS));
        assertNotEquals(first,contract(META,bound,logical,"route","tenant:a",List.of(QuerySort.desc("ranking"),QuerySort.asc("id"))));
        var other=new EntityMetadata<EncryptedWindowRow,Long>(){
            public String entityName(){return META.entityName();}
            public String tableName(){return "other_physical_mapping";}
            public String redisNamespace(){return META.redisNamespace();}
            public String idColumn(){return META.idColumn();}
            public Class<EncryptedWindowRow> entityType(){return EncryptedWindowRow.class;}
            public java.util.function.Function<EncryptedWindowRow,Long> idAccessor(){return EncryptedWindowRow::id;}
            public List<String> columns(){return META.columns();}
            public List<RelationDefinition> relations(){return List.of();}
            public String tenantFieldName(){return META.tenantFieldName();}
            public Map<String,String> columnTypes(){return META.columnTypes();}
            public Set<String> indexedColumns(){return META.indexedColumns();}
            public Map<String,EncryptedColumnDescriptor> encryptedColumns(){return META.encryptedColumns();}
        };
        assertNotEquals(first,contract(other,bound,logical,"route","tenant:a",SORTS));
        assertNotEquals(contract(META,bound,logical.and(QueryFilter.eq("ranking",1)),"route","tenant:a",SORTS),
                contract(META,bound,logical.and(QueryFilter.eq("ranking",1L)),"route","tenant:a",SORTS));
        assertNotEquals(contract(META,bound,QuerySpec.allOf(eq,QueryFilter.eq("ranking",1)),"route","tenant:a",SORTS),
                contract(META,bound,QuerySpec.anyOf(eq,QueryFilter.eq("ranking",1)),"route","tenant:a",SORTS));
        assertSame(eq,logical.filters().get(0));assertEquals(SECRET,eq.value());
    }

    @Test void protectedSortOperatorsAndOversizedFingerprintFailBeforeQueryAndDoNotEchoOperands(){
        var session=new RuntimeSession(new InMemoryCacheDatabase(),oldKeys());
        var adapter=new SensitiveProbe(session);
        try(var tenant=CacheTenantContext.withTenant("a")){
            safe(assertThrows(CacheEncryptionException.class,()->adapter.sorted(SECRET,Sort.by("contact"))));
            assertEquals(0,session.reads);
        }
        var bound=codec(oldKeys());
        safe(assertThrows(CacheEncryptionException.class,()->contract(META,bound,QuerySpec.where(QueryFilter.ne("contact",SECRET)),"route","scope",SORTS)));
        var filters=new QueryNode[1000];Arrays.fill(filters,QueryFilter.eq("contact","x".repeat(4096)));
        safe(assertThrows(CacheEncryptionException.class,()->contract(META,bound,QuerySpec.allOf(filters),"route","scope",SORTS)));
        Object hostile=new Object(){public String toString(){throw new AssertionError("No operand stringification");}};
        safe(assertThrows(CacheEncryptionException.class,()->contract(META,bound,QuerySpec.where(QueryFilter.eq("contact",hostile)),"route","scope",SORTS)));
    }


    @Test void referencedBlindKeyChangeInvalidatesContractEvenWhenOuterSigningCapabilityIsUnchanged(){
        var outer=codec(oldKeys());var seen=new java.util.concurrent.atomic.AtomicReference<Object>();
        java.util.function.Function<CacheKeyProvider,EntityCodec<EncryptedWindowRow>> capability=fieldKeys->new EntityCodec<>(){
            final JcaFieldEncryption field=new JcaFieldEncryption(fieldKeys);
            public boolean encryptionBound(){return true;}
            public String toRedisValue(EncryptedWindowRow row){return outer.toRedisValue(row);}
            public EncryptedWindowRow fromRedisValue(String value){return outer.fromRedisValue(value);}
            public Map<String,Object> toColumns(EncryptedWindowRow row){return outer.toColumns(row);}
            public String encryptionIntentFingerprint(List<String> parts){return outer.encryptionIntentFingerprint(parts);}
            public Object indexQueryValue(String column,Object original){
                seen.set(original);return field.blindIndex("default",META.redisNamespace(),column,(String)original);
            }
        };
        String first=contract(META,capability.apply(oldKeys()),query(),"route","tenant:a",SORTS);
        assertEquals(SECRET,seen.get());
        String changed=contract(META,capability.apply(provider("k1",key("k1",1,71))),query(),"route","tenant:a",SORTS);
        assertNotEquals(first,changed);assertEquals(SECRET,seen.get());
    }

    @Test void unencryptedContractFormatRemainsUnchangedAndMismatchedEncryptedCodecCannotDowngradeMetadata(){
        var metadata=new EntityMetadata<String,String>(){
            public String entityName(){return "Plain";}
            public String tableName(){return "plain";}
            public String redisNamespace(){return "plain";}
            public String idColumn(){return "id";}
            public Class<String> entityType(){return String.class;}
            public java.util.function.Function<String,String> idAccessor(){return value->value;}
            public List<String> columns(){return List.of("id");}
            public List<RelationDefinition> relations(){return List.of();}
        };
        var plain=new EntityCodec<String>(){
            public String toRedisValue(String row){return row;}
            public String fromRedisValue(String row){return row;}
            public Map<String,Object> toColumns(String row){return Map.of("id",row);}
        };
        var logical=QuerySpec.where(QueryFilter.eq("id",SECRET));var sorts=List.of(QuerySort.asc("id"));
        assertEquals(SpringDataQueryFingerprint.contract(logical,"route","global",sorts),
                contract(metadata,plain,logical,"route","global",sorts));
        safe(assertThrows(CacheEncryptionException.class,()->contract(metadata,codec(oldKeys()),logical,"route","global",sorts)));
    }
}
