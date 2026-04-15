package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.config.ProjectionRefreshConfig;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.params.XAddParams;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class RedisProjectionRefreshQueue {

    private final JedisPooled jedis;
    private final ProjectionRefreshConfig config;

    public RedisProjectionRefreshQueue(JedisPooled jedis, ProjectionRefreshConfig config) {
        this.jedis = Objects.requireNonNull(jedis, "jedis");
        this.config = Objects.requireNonNull(config, "config");
    }

    public void enqueueUpsert(String entityName, String projectionName, Object id) {
        enqueue("UPSERT", entityName, projectionName, id);
    }

    public void enqueueDelete(String entityName, String projectionName, Object id) {
        enqueue("DELETE", entityName, projectionName, id);
    }

    public void enqueueUpsertBatch(String entityName, Collection<String> projectionNames, Collection<?> ids) {
        enqueueBatch("UPSERT", entityName, projectionNames, ids);
    }

    public void enqueueDeleteBatch(String entityName, Collection<String> projectionNames, Collection<?> ids) {
        enqueueBatch("DELETE", entityName, projectionNames, ids);
    }

    String enqueueRetry(String operation, String entityName, String projectionName, Object id, int attempt, String originalEntryId) {
        return enqueue(operation, entityName, projectionName, id, attempt, originalEntryId, "");
    }

    String enqueueReplay(String operation, String entityName, String projectionName, Object id, String originalEntryId, String replayedFromEntryId) {
        return enqueue(operation, entityName, projectionName, id, 1, originalEntryId, replayedFromEntryId);
    }

    private void enqueue(String operation, String entityName, String projectionName, Object id) {
        enqueue(operation, entityName, projectionName, id, 1, "", "");
    }

    private String enqueue(
            String operation,
            String entityName,
            String projectionName,
            Object id,
            int attempt,
            String originalEntryId,
            String replayedFromEntryId
    ) {
        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        fields.put("operation", operation);
        fields.put("entity", entityName);
        fields.put("projection", projectionName);
        fields.put("id", String.valueOf(id));
        fields.put("attempt", String.valueOf(Math.max(1, attempt)));
        if (originalEntryId != null && !originalEntryId.isBlank()) {
            fields.put("originalEntryId", originalEntryId);
        }
        if (replayedFromEntryId != null && !replayedFromEntryId.isBlank()) {
            fields.put("replayedFromEntryId", replayedFromEntryId);
        }
        String entryId = jedis.xadd(
                config.streamKey(),
                XAddParams.xAddParams(),
                fields
        ).toString();
        if (config.maxStreamLength() > 0) {
            jedis.xtrim(config.streamKey(), config.maxStreamLength(), true);
        }
        return entryId;
    }

    private void enqueueBatch(String operation, String entityName, Collection<String> projectionNames, Collection<?> ids) {
        if (projectionNames == null || projectionNames.isEmpty() || ids == null || ids.isEmpty()) {
            return;
        }
        try (Pipeline pipeline = jedis.pipelined()) {
            for (Object id : ids) {
                for (String projectionName : projectionNames) {
                    pipeline.xadd(
                            config.streamKey(),
                            XAddParams.xAddParams(),
                            newEventFields(operation, entityName, projectionName, id, 1, "", "")
                    );
                }
            }
            if (config.maxStreamLength() > 0) {
                pipeline.xtrim(config.streamKey(), config.maxStreamLength(), true);
            }
            pipeline.sync();
        }
    }

    private LinkedHashMap<String, String> newEventFields(
            String operation,
            String entityName,
            String projectionName,
            Object id,
            int attempt,
            String originalEntryId,
            String replayedFromEntryId
    ) {
        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        fields.put("operation", operation);
        fields.put("entity", entityName);
        fields.put("projection", projectionName);
        fields.put("id", String.valueOf(id));
        fields.put("attempt", String.valueOf(Math.max(1, attempt)));
        if (originalEntryId != null && !originalEntryId.isBlank()) {
            fields.put("originalEntryId", originalEntryId);
        }
        if (replayedFromEntryId != null && !replayedFromEntryId.isBlank()) {
            fields.put("replayedFromEntryId", replayedFromEntryId);
        }
        return fields;
    }
}
