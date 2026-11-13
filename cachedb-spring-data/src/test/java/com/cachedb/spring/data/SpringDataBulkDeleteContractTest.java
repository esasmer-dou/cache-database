package com.reactor.cachedb.spring.data;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.api.*;
import com.reactor.cachedb.core.cache.*;
import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.core.plan.FetchPlan;
import com.reactor.cachedb.core.query.*;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.spring.data.fixture.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class SpringDataBulkDeleteContractTest {
    private static final class Backend implements EntityRepository<Customer,Long> {
        public EntityQueryCapacity entityQueryCapacity(){return EntityQueryCapacity.unrestricted();}
        boolean supported=true;int singles,bulkCalls;List<Customer> selected=List.of();List<Long> attempted=List.of();
        BulkDeleteFailureException failure;
        public RepositoryCapabilities capabilities(){return supported?RepositoryCapabilities.of(RepositoryCapability.BULK_DELETE):RepositoryCapabilities.none();}
        public Optional<Customer> findById(Long id){throw new AssertionError("No ownership point reads");}
        public List<Customer> findAll(Collection<Long> ids){throw new AssertionError("No per-ID read before delete");}
        public List<Customer> findPage(PageWindow page){throw new AssertionError();}
        public QueryExplainPlan explain(QuerySpec query){throw new AssertionError();}
        public List<Customer> query(QuerySpec query){return selected.subList(0,Math.min(selected.size(),query.limit()));}
        public Customer save(Customer row){throw new AssertionError();}
        public void deleteById(Long id){singles++;}
        public List<WriteReceipt<Customer,Long>> bulkDelete(Collection<Long> ids){
            bulkCalls++;attempted=List.copyOf(ids);
            if(failure!=null)throw failure;
            return ids.stream().map(id->new WriteReceipt<Customer,Long>(null,id,"f2-customers",OperationType.DELETE,1,Instant.now())).toList();
        }
        public EntityRepository<Customer,Long> withFetchPlan(FetchPlan plan){return this;}
    }
    @SuppressWarnings("unchecked")
    private Customers repository(Backend backend){
        CacheSession session=new CacheSession(){
            public <T,ID> EntityRepository<T,ID> repository(EntityMetadata<T,ID> metadata,EntityCodec<T> codec){
                return (EntityRepository<T,ID>)backend;
            }
            public <T,ID> EntityRepository<T,ID> repository(EntityMetadata<T,ID> metadata,EntityCodec<T> codec,CachePolicy policy){
                return repository(metadata,codec);
            }
        };
        return new CustomersCacheDbImplementation(session,true);
    }
    @Test void tenThousandDerivedDeletesUseOneBoundedCapabilityAndZeroSerialPointDeletes(){
        var backend=new Backend();
        backend.selected=java.util.stream.LongStream.rangeClosed(1,10_000)
                .mapToObj(id->new Customer(id,"a","same",20,true,null)).toList();
        var repo=repository(backend);
        try(var tenant=CacheTenantContext.withTenant("a")){
            assertEquals(10_000,repo.deleteByName("same"));
            assertEquals(1,backend.bulkCalls);assertEquals(10_000,backend.attempted.size());assertEquals(0,backend.singles);
        }
    }
    @Test void overLimitInputAndSelectedResultBothFailBeforeAnyMutation(){
        var backend=new Backend();var repo=repository(backend);
        backend.selected=java.util.stream.LongStream.rangeClosed(1,10_001)
                .mapToObj(id->new Customer(id,"a","same",20,true,null)).toList();
        try(var tenant=CacheTenantContext.withTenant("a")){
            assertThrows(IllegalStateException.class,()->repo.deleteByName("same"));
            assertThrows(IllegalArgumentException.class,()->repo.deleteAllById(Collections.nCopies(10_001,1L)));
            assertEquals(0,backend.bulkCalls);assertEquals(0,backend.singles);
        }
    }
    @Test void legacyMultiDeleteFailsClosedButSingletonCompatibilityIsPreserved(){
        var backend=new Backend();backend.supported=false;var repo=repository(backend);
        try(var tenant=CacheTenantContext.withTenant("a")){
            assertThrows(RepositoryCapabilityUnavailableException.class,()->repo.deleteAllById(List.of(1L,2L)));
            assertEquals(0,backend.singles);assertEquals(0,backend.bulkCalls);
            repo.deleteAllById(List.of(1L));assertEquals(1,backend.singles);
        }
    }
    @Test void partialFailureIsPropagatedUnchangedWithoutRetryOrSerialFallback(){
        var backend=new Backend();var repo=repository(backend);
        var receipt=new WriteReceipt<Customer,Long>(null,1L,"f2-customers",OperationType.DELETE,3,Instant.now());
        backend.failure=new BulkDeleteFailureException(BulkDeleteFailureException.Phase.MUTATION,
                List.of(receipt),List.of(),List.of(2L),List.of(),1);
        try(var tenant=CacheTenantContext.withTenant("a")){
            assertSame(backend.failure,assertThrows(BulkDeleteFailureException.class,()->repo.deleteAllById(List.of(1L,2L,3L))));
            assertEquals(List.of(receipt),backend.failure.accepted());
            assertEquals(1,backend.bulkCalls);assertEquals(0,backend.singles);
        }
    }
    @Test void allEntityTenantsAreVerifiedBeforeTheBulkCapabilityIsCalled(){
        var backend=new Backend();var repo=repository(backend);
        try(var tenant=CacheTenantContext.withTenant("a")){
            assertThrows(RuntimeException.class,()->repo.deleteAll(List.of(
                    new Customer(1L,"a","safe",20,true,null),new Customer(2L,"b","foreign",20,true,null))));
            assertEquals(0,backend.bulkCalls);assertEquals(0,backend.singles);
        }
    }
}
