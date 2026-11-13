package com.reactor.cachedb.core.protection;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.config.CacheDatabaseConfig;
import com.reactor.cachedb.core.change.ExternalChangeEvent;
import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.core.queue.QueuedWriteOperation;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Compilation guard: compatibility constructors cannot preserve a changed record-pattern arity. */
class ExistingRecordPatternArityTest {
    static int historicalArity(Object value) {
        return switch(value) {
            case WriteOperation(var type,var metadata,var id,var columns,var payload,var version,var created,var dependency,var trace) -> 9;
            case QueuedWriteOperation(var type,var entity,var table,var namespace,var observation,
                    var idColumn,var versionColumn,var deletedColumn,var id,var columns,var version,var created,
                    var dependencyNamespace,var dependencyId,var dependencyVersion,var transactionGroup,var batch,var trace) -> 18;
            case WriteReceipt(var entity,var id,var namespace,var type,var version,var accepted) -> 6;
            case CacheDatabaseConfig(var writeBehind,var flusher,var resources,var keyspace,var coordination,
                    var functions,var relations,var pages,var readThrough,var readShapes,var indexes,
                    var projections,var redisGuard,var recovery,var monitoring,var reportJob,var http,
                    var schema,var role,var mutations,var trace) -> 21;
            case ExternalChangeEvent(var entity,var id,var type,var columns,var version,var occurred,var source) -> 7;
            default -> 0;
        };
    }
    @Test void additiveEnvelopeLeavesTheOriginalReceiptAndPatternUnchanged() {
        var receipt=new WriteReceipt<String,String>("value","id","orders",OperationType.UPSERT,501,Instant.EPOCH);
        var stamp=new TenantPurgeProtocol.Generation(ProtectionKey.of("orders","private-tenant"),2);
        var envelope=new TenantGenerationEnvelope<>(receipt,stamp);
        assertSame(receipt,envelope.payload());assertSame(stamp,envelope.generation());
        assertEquals(6,historicalArity(receipt));assertFalse(envelope.toString().contains("private-tenant"));
        assertEquals(501,envelope.payload().version());
    }
}
