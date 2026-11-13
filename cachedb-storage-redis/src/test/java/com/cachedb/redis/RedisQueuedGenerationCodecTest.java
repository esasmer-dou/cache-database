package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.Generation;
import com.reactor.cachedb.core.model.OperationType;
import com.reactor.cachedb.core.queue.QueuedWriteOperation;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class RedisQueuedGenerationCodecTest {
    static QueuedWriteOperation op(){return new QueuedWriteOperation(OperationType.DELETE,"Row","rows","rows",null,
            "id","version",null,"id",Map.of("id","id"),999,Instant.EPOCH,null,null,0);}
    @Test void queueRoundTripAndRetryKeepTheOriginalGenerationOutsidePublicRecords(){
        var mapper=new RedisWriteOperationMapper();var op=op();var stamp=new Generation(ProtectionKey.of("rows","a"),7);
        var envelope=new QueuedGenerationEnvelope(op,Map.of(QueuedGenerationEnvelope.identity(op),stamp));
        Map<String,String> body;
        try(var scope=TenantWriteStampContext.restore(List.of(envelope))){body=mapper.toBody(op);}
        assertEquals("7",body.get("tenantGeneration"));assertFalse(body.containsKey("col:tenantGeneration"));
        var decoded=mapper.fromStampedBody(body);assertEquals(stamp,decoded.generation(op));
        assertFalse(decoded.operation().traceMetadata().containsKey("tenantGeneration"));
        try(var scope=TenantWriteStampContext.restore(List.of(decoded))){assertEquals(body,mapper.toBody(decoded.operation()));}
        assertNull(TenantWriteStampContext.current(op));
    }
    @Test void missingLegacyGenerationIsNotManufacturedAndMalformedPartialStampFails(){
        var mapper=new RedisWriteOperationMapper();var body=mapper.toBody(op());
        assertFalse(mapper.fromStampedBody(body).protectedEnvelope());
        body.put("tenantGeneration","2");
        assertThrows(ProtectionException.class,()->mapper.fromStampedBody(body));
    }
}
