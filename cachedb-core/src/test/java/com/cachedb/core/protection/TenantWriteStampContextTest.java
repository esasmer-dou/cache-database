package com.reactor.cachedb.core.protection;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.model.OperationType;
import com.reactor.cachedb.core.queue.QueuedWriteOperation;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.Generation;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class TenantWriteStampContextTest {
    static QueuedWriteOperation operation(String id,long version){
        return new QueuedWriteOperation(OperationType.UPSERT,"Row","rows","rows",null,"id","version",null,id,
                Map.of("id",id),version,Instant.EPOCH,null,null,0);
    }
    @Test void nestedWorkerScopesRestoreAndNeverRelabelAnOldOperation(){
        var op=operation("a",1);var old=new Generation(ProtectionKey.of("rows","tenant"),1);
        var envelope=new QueuedGenerationEnvelope(op,Map.of(QueuedGenerationEnvelope.identity(op),old));
        assertNull(TenantWriteStampContext.current(op));
        try(var first=TenantWriteStampContext.restore(List.of(envelope))){
            assertEquals(1,TenantWriteStampContext.require(op).value());
            try(var nested=TenantWriteStampContext.restore(List.of())){assertNull(TenantWriteStampContext.current(op));}
            assertEquals(old,TenantWriteStampContext.require(op));
        }
        assertNull(TenantWriteStampContext.current(op));
        assertEquals(ProtectionException.Code.MIGRATION_REQUIRED,assertThrows(ProtectionException.class,
                ()->TenantWriteStampContext.require(op)).code());
    }
    @Test void outOfOrderScopeCloseCannotOverwriteAnActiveWorkerStamp(){
        var outer=TenantWriteStampContext.restore(List.of());var inner=TenantWriteStampContext.restore(List.of());
        assertThrows(ProtectionException.class,outer::close);inner.close();outer.close();
    }
    @Test void conflictingOrOversizedOriginalStampSetsAreRejected(){
        var op=operation("a",1);var key=ProtectionKey.of("rows","tenant");
        var one=new QueuedGenerationEnvelope(op,Map.of(QueuedGenerationEnvelope.identity(op),new Generation(key,1)));
        var two=new QueuedGenerationEnvelope(op,Map.of(QueuedGenerationEnvelope.identity(op),new Generation(key,2)));
        assertThrows(ProtectionException.class,()->TenantWriteStampContext.restore(List.of(one,two)));
        assertNull(TenantWriteStampContext.current(op));
    }
}
