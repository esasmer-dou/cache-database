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

    private RedisRecoveryStreams() {
    }

    public static void publishReconciliation(
            JedisPooled jedis,
            DeadLetterRecoveryConfig config,
            Map<String, String> fields
    ) {
        jedis.xadd(config.reconciliationStreamKey(), XAddParams.xAddParams(), fields);
        trimIfConfigured(jedis, config.reconciliationStreamKey(), config.reconciliationMaxLength());
    }

    public static void archiveResolved(
            JedisPooled jedis,
            DeadLetterRecoveryConfig config,
            Map<String, String> sourceFields,
            String sourceEntryId,
            String status,
            String note
    ) {
        if (!config.archiveResolvedEntries()) {
            return;
        }
        LinkedHashMap<String, String> archiveFields = new LinkedHashMap<>(sourceFields);
        archiveFields.put("sourceEntryId", sourceEntryId);
        archiveFields.put("status", status);
        archiveFields.put("archivedAt", Instant.now().toString());
        archiveFields.put("note", note == null ? "" : note);
        jedis.xadd(config.archiveStreamKey(), XAddParams.xAddParams(), archiveFields);
        trimIfConfigured(jedis, config.archiveStreamKey(), config.archiveMaxLength());
    }

    public static void trimDeadLetter(JedisPooled jedis, DeadLetterRecoveryConfig config, String deadLetterStreamKey) {
        trimIfConfigured(jedis, deadLetterStreamKey, config.deadLetterMaxLength());
    }

    public static long pruneBefore(
            JedisPooled jedis,
            String streamKey,
            long cutoffEpochMillis,
            int batchSize
    ) {
        String maxId = cutoffEpochMillis + "-999999";
        long deleted = 0;
        while (true) {
            List<StreamEntry> entries = jedis.xrange(streamKey, "-", maxId, batchSize);
            if (entries == null || entries.isEmpty()) {
                return deleted;
            }
            StreamEntryID[] ids = entries.stream()
                    .map(StreamEntry::getID)
                    .toArray(StreamEntryID[]::new);
            deleted += jedis.xdel(streamKey, ids);
            if (entries.size() < batchSize) {
                return deleted;
            }
        }
    }

    private static void trimIfConfigured(JedisPooled jedis, String streamKey, long maxLength) {
        if (maxLength > 0) {
            jedis.xtrim(streamKey, maxLength, true);
        }
    }
}
