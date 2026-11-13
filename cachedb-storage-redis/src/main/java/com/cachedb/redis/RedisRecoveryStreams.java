package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.config.DeadLetterRecoveryConfig;

import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.params.XAddParams;
import redis.clients.jedis.resps.StreamEntry;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RedisRecoveryStreams {

    private RedisRecoveryStreams() {}

    public static void publishReconciliation(
            JedisPooled jedis, DeadLetterRecoveryConfig config, Map<String, String> fields) {
        publishReconciliation(JedisPooledOperations.of(jedis), config, fields);
    }

    public static void publishReconciliation(
            RedisClientOperations jedis,
            DeadLetterRecoveryConfig config,
            Map<String, String> fields) {
        requireLegacyFields(fields);
        jedis.commands().xadd(config.reconciliationStreamKey(), XAddParams.xAddParams(), fields);
        trimIfConfigured(jedis, config.reconciliationStreamKey(), config.reconciliationMaxLength());
    }

    /** Reconciliation is a producer too: preserve and atomically fence every original pair. */
    public static void publishReconciliation(RedisClientOperations jedis,DeadLetterRecoveryConfig config,
            Map<String,String> fields,com.reactor.cachedb.core.protection.QueuedGenerationEnvelope original){
        if(!original.protectedEnvelope()){publishReconciliation(jedis,config,fields);return;}
        var stamped=new LinkedHashMap<>(fields);appendOriginalIdentity(stamped,original);
        RedisQueueGenerationFence.publish(jedis,config.reconciliationStreamKey(),stamped,original);
        // Never trim a shared stream as a side effect of a protected tenant operation.
    }

    public static void archiveResolved(RedisClientOperations jedis,DeadLetterRecoveryConfig config,
            Map<String,String> fields,String id,String status,String note,
            com.reactor.cachedb.core.protection.QueuedGenerationEnvelope original){
        if(!config.archiveResolvedEntries())return;
        if(!original.protectedEnvelope()){archiveResolved(jedis,config,fields,id,status,note);return;}
        var stamped=new LinkedHashMap<>(fields);appendOriginalIdentity(stamped,original);
        stamped.put("sourceEntryId",id);stamped.put("status",status);stamped.put("archivedAt",Instant.now().toString());
        stamped.put("note",note==null?"":note);
        RedisQueueGenerationFence.publish(jedis,config.archiveStreamKey(),stamped,original);
    }

    private static void appendOriginalIdentity(Map<String,String> fields,
            com.reactor.cachedb.core.protection.QueuedGenerationEnvelope envelope){
        var operation=envelope.operation();
        var members=operation.atomicBatch()?operation.batchOperations():java.util.List.of(operation);
        if(operation.atomicBatch())fields.put("batchCount",Integer.toString(members.size()));
        for(int i=0;i<members.size();i++){
            var member=members.get(i);var stamp=envelope.generation(member);
            String prefix=operation.atomicBatch()?"b"+i+":":"";
            fields.put(prefix+"namespace",member.redisNamespace());fields.put(prefix+"id",member.id());
            fields.put(prefix+"entity",member.entityName());fields.put(prefix+"version",Long.toString(member.version()));
            if(stamp!=null){fields.put(prefix+"tenantGeneration",Long.toString(stamp.value()));
                fields.put(prefix+"tenantToken",stamp.key().tenantToken());fields.put(prefix+"generationScope",stamp.key().scopeToken());}
        }
    }

    public static void archiveResolved(
            JedisPooled jedis,
            DeadLetterRecoveryConfig config,
            Map<String, String> sourceFields,
            String sourceEntryId,
            String status,
            String note) {
        archiveResolved(
                JedisPooledOperations.of(jedis), config, sourceFields, sourceEntryId, status, note);
    }

    public static void archiveResolved(
            RedisClientOperations jedis,
            DeadLetterRecoveryConfig config,
            Map<String, String> sourceFields,
            String sourceEntryId,
            String status,
            String note) {
        if (!config.archiveResolvedEntries()) {
            return;
        }
        requireLegacyFields(sourceFields);
        LinkedHashMap<String, String> archiveFields = new LinkedHashMap<>(sourceFields);
        archiveFields.put("sourceEntryId", sourceEntryId);
        archiveFields.put("status", status);
        archiveFields.put("archivedAt", Instant.now().toString());
        archiveFields.put("note", note == null ? "" : note);
        jedis.commands().xadd(config.archiveStreamKey(), XAddParams.xAddParams(), archiveFields);
        trimIfConfigured(jedis, config.archiveStreamKey(), config.archiveMaxLength());
    }

    public static void trimDeadLetter(
            JedisPooled jedis, DeadLetterRecoveryConfig config, String deadLetterStreamKey) {
        trimDeadLetter(JedisPooledOperations.of(jedis), config, deadLetterStreamKey);
    }

    public static void trimDeadLetter(
            RedisClientOperations jedis,
            DeadLetterRecoveryConfig config,
            String deadLetterStreamKey) {
        trimIfConfigured(jedis, deadLetterStreamKey, config.deadLetterMaxLength());
    }

    public static long pruneBefore(
            JedisPooled jedis, String streamKey, long cutoffEpochMillis, int batchSize) {
        return pruneBefore(
                JedisPooledOperations.of(jedis), streamKey, cutoffEpochMillis, batchSize);
    }

    public static long pruneBefore(
            RedisClientOperations jedis, String streamKey, long cutoffEpochMillis, int batchSize) {
        String maxId = cutoffEpochMillis + "-999999";
        long deleted = 0;
        while (true) {
            List<StreamEntry> entries = jedis.commands().xrange(streamKey, "-", maxId, batchSize);
            if (entries == null || entries.isEmpty()) {
                return deleted;
            }
            StreamEntryID[] ids =
                    entries.stream().map(StreamEntry::getID).toArray(StreamEntryID[]::new);
            deleted += jedis.commands().xdel(streamKey, ids);
            if (entries.size() < batchSize) {
                return deleted;
            }
        }
    }

    private static void requireLegacyFields(Map<String,String> fields){
        if(fields.size()>32768)throw new com.reactor.cachedb.core.protection.ProtectionException(
                com.reactor.cachedb.core.protection.ProtectionException.Code.INVALID_REQUEST);
        for(String key:fields.keySet())if(key.equals("tenantGeneration")||key.endsWith(":tenantGeneration"))
            throw new com.reactor.cachedb.core.protection.ProtectionException(
                    com.reactor.cachedb.core.protection.ProtectionException.Code.MIGRATION_REQUIRED);
    }

    private static void trimIfConfigured(JedisPooled jedis, String streamKey, long maxLength) {
        trimIfConfigured(JedisPooledOperations.of(jedis), streamKey, maxLength);
    }

    private static void trimIfConfigured(
            RedisClientOperations jedis, String streamKey, long maxLength) {
        if (maxLength > 0) {
            jedis.commands().xtrim(streamKey, maxLength, true);
        }
    }
}
