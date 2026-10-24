package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.config.QueryIndexConfig;
import com.reactor.cachedb.core.model.EntityMetadata;
import java.util.*;
import redis.clients.jedis.AbstractPipeline;

/** Pure bounded diff; command execution stays on the caller's single pipeline. */
final class RedisIndexMutationPlanner {
    static void apply(AbstractPipeline pipeline, RedisKeyStrategy keys, EntityMetadata<?, ?> metadata,
            QueryIndexConfig config, String id, Map<String,Object> columns, Plan plan,
            Map<String,List<String>> partitions) {
        String namespace = metadata.redisNamespace();
        for (String column : plan.changed()) {
            if (plan.prior().containsKey(column)) {
                String old = plan.prior().get(column);
                if (config.exactIndexEnabled()) pipeline.srem(keys.indexExactKey(namespace,column,RedisIndexKeySpace.encodeKeyPart(old)),id);
                stringValue(pipeline,keys,namespace,config,id,column,RedisIndexKeySpace.decodeValue(old),false);
                pipeline.zrem(keys.indexSortKey(namespace,column),id);
            }
            if (plan.next().containsKey(column)) {
                Object value = columns.get(column);
                String next = plan.next().get(column);
                if (config.exactIndexEnabled()) pipeline.sadd(keys.indexExactKey(namespace,column,RedisIndexKeySpace.encodeKeyPart(next)),id);
                if (value instanceof String text) stringValue(pipeline,keys,namespace,config,id,column,text,true);
                if (config.rangeIndexEnabled()) {
                    Double score = RedisScoreSupport.toScore(metadata.columnTypes(),column,value);
                    if (score != null) pipeline.zadd(keys.indexSortKey(namespace,column),score,id);
                }
            }
        }
        for (var partition : partitions.entrySet()) {
            for (String sort : partition.getValue()) {
                String column = partition.getKey();
                if (!plan.changed().contains(column) && !plan.changed().contains(sort)) continue;
                if (plan.prior().containsKey(column)) pipeline.zrem(keys.indexPartitionSortKey(namespace,column,
                        RedisIndexKeySpace.encodeKeyPart(plan.prior().get(column)),sort),id);
                Double score = RedisScoreSupport.toScore(metadata.columnTypes(),sort,columns.get(sort));
                if (score != null) pipeline.zadd(keys.indexPartitionSortKey(namespace,column,
                        RedisIndexKeySpace.encodeKeyPart(plan.next().get(column)),sort),score,id);
            }
        }
        pipeline.sadd(keys.indexAllKey(namespace),id);
        String meta = keys.indexMetaKey(namespace,id);
        pipeline.del(meta);
        if (!plan.next().isEmpty()) pipeline.hset(meta,plan.next());
    }

    static void stringValue(AbstractPipeline pipeline, RedisKeyStrategy keys, String namespace,
            QueryIndexConfig config, String id, String column, String value, boolean add) {
        if (value == null) return;
        String normalized = RedisIndexKeySpace.normalizeText(value);
        if (config.prefixIndexEnabled() && !normalized.isBlank()) {
            int length = Math.min(config.prefixMaxLength(),normalized.length());
            for (int i=1; i<=length; i++) {
                String key = keys.indexPrefixKey(namespace,column,RedisIndexKeySpace.encodeKeyPart(normalized.substring(0,i)));
                if (add) pipeline.sadd(key,id); else pipeline.srem(key,id);
            }
        }
        if (config.textIndexEnabled()) {
            for (String token : RedisIndexKeySpace.tokenize(value,config)) {
                String key = keys.indexTokenKey(namespace,column,RedisIndexKeySpace.encodeKeyPart(token));
                if (add) pipeline.sadd(key,id); else pipeline.srem(key,id);
            }
        }
    }
    record Plan(Map<String, String> prior, Map<String, String> next, Set<String> changed) {
        boolean unchanged() { return changed.isEmpty(); }
    }

    static Plan plan(Map<String, String> prior, Map<String, String> next, boolean force) {
        if (prior == null) prior = Map.of();
        if (prior.size() > 1024 || next.size() > 1024) throw new IllegalArgumentException("Too many index fields");
        Set<String> changed = new LinkedHashSet<>();
        for (String column : prior.keySet())
            if (force || !next.containsKey(column) || !Objects.equals(prior.get(column), next.get(column))) changed.add(column);
        for (String column : next.keySet())
            if (force || !prior.containsKey(column) || !Objects.equals(prior.get(column), next.get(column))) changed.add(column);
        return new Plan(prior, next, Collections.unmodifiableSet(changed));
    }
}
