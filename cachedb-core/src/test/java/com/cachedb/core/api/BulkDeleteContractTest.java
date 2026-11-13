package com.reactor.cachedb.core.api;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.cache.PageWindow;
import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.core.plan.FetchPlan;
import com.reactor.cachedb.core.query.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class BulkDeleteContractTest {
    private static final class Legacy implements EntityRepository<String,Long> {
        int deletes;
        public Optional<String> findById(Long id) { throw new AssertionError(); }
        public List<String> findAll(Collection<Long> ids) { throw new AssertionError(); }
        public List<String> findPage(PageWindow page) { throw new AssertionError(); }
        public QueryExplainPlan explain(QuerySpec query) { throw new AssertionError(); }
        public List<String> query(QuerySpec query) { throw new AssertionError(); }
        public String save(String row) { throw new AssertionError(); }
        public void deleteById(Long id) { deletes++; }
        public EntityRepository<String,Long> withFetchPlan(FetchPlan plan) { return this; }
    }
    @Test void legacyDefaultFailsBeforeMutationInsteadOfInventingSerialBulkWork() {
        var backend=new Legacy();
        var failure=assertThrows(RepositoryCapabilityUnavailableException.class,()->backend.bulkDelete(List.of(1L,2L)));
        assertEquals(RepositoryCapability.BULK_DELETE,failure.capability());
        assertEquals(0,backend.deletes);
        assertFalse(backend.capabilities().supports(RepositoryCapability.BULK_DELETE));
    }
    @Test void partialFailureKeepsImmutableAcknowledgementAndDoesNotLeakBusinessDataInItsMessage() {
        var receipt=new WriteReceipt<String,Long>(null,1L,"rows",OperationType.DELETE,3,Instant.now());
        var accepted=new ArrayList<WriteReceipt<?,?>>();accepted.add(receipt);
        var uncertain=new ArrayList<Long>();uncertain.add(2L);
        var failure=new BulkDeleteFailureException(BulkDeleteFailureException.Phase.MUTATION,
                accepted,List.of(4L),uncertain,List.of(1L),7);
        accepted.clear();uncertain.clear();
        assertEquals(List.of(receipt),failure.accepted());
        assertEquals(List.of(2L),failure.uncertain());
        assertEquals(List.of(4L),failure.rejected());
        assertEquals(List.of(1L),failure.cleanupPending());
        assertEquals(7,failure.notAttempted());assertNull(failure.getCause());
        assertThrows(UnsupportedOperationException.class,()->failure.accepted().clear());
        assertFalse(failure.getMessage().contains("rows"));
    }
}
