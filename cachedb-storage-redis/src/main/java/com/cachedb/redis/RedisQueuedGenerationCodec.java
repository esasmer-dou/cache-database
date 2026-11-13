package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.Generation;
import com.reactor.cachedb.core.queue.QueuedWriteOperation;
import java.util.*;

/** First-class immutable wire fields, not trace metadata, ciphertext or business columns. */
final class RedisQueuedGenerationCodec {
    static QueuedGenerationEnvelope decode(QueuedWriteOperation operation,Map<String,String> body) {
        if(body.size()>32768)invalid();
        var stamps=new LinkedHashMap<QueuedGenerationEnvelope.Identity,Generation>();
        if(operation.atomicBatch()){
            if(operation.batchOperations().size()>1000)invalid();
            for(int i=0;i<operation.batchOperations().size();i++)read(operation.batchOperations().get(i),body,"b"+i+":",stamps);
        }else read(operation,body,"",stamps);
        return new QueuedGenerationEnvelope(operation,stamps);
    }
    private static void read(QueuedWriteOperation operation,Map<String,String> body,String prefix,
            Map<QueuedGenerationEnvelope.Identity,Generation> stamps) {
        String generation=body.get(prefix+"tenantGeneration"),tenant=body.get(prefix+"tenantToken"),scope=body.get(prefix+"generationScope");
        if(generation==null && tenant==null && scope==null)return;
        if(generation==null || !generation.matches("[1-9][0-9]{0,18}") || tenant==null || scope==null
                || !ProtectionKey.hash(operation.redisNamespace()).equals(scope))invalid();
        long number;
        try{number=Long.parseLong(generation);}catch(NumberFormatException bad){throw new ProtectionException(ProtectionException.Code.INVALID_REQUEST);}
        var stamp=new Generation(new ProtectionKey(scope,tenant),number);
        var old=stamps.putIfAbsent(QueuedGenerationEnvelope.identity(operation),stamp);
        if(old!=null && !old.equals(stamp) || stamps.size()>128)invalid();
    }
    static void append(Map<String,String> fields,QueuedWriteOperation operation) {
        Generation stamp=TenantWriteStampContext.current(operation);
        if(stamp==null)return;
        fields.put("tenantGeneration",Long.toString(stamp.value()));
        fields.put("tenantToken",stamp.key().tenantToken());fields.put("generationScope",stamp.key().scopeToken());
    }
    private static void invalid(){throw new ProtectionException(ProtectionException.Code.INVALID_REQUEST);}
    private RedisQueuedGenerationCodec(){}
}
