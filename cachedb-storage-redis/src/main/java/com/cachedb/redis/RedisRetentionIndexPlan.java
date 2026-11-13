package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.config.QueryIndexConfig;
import com.reactor.cachedb.core.model.EntityMetadata;
import java.util.*;

final class RedisRetentionIndexPlan {
    record Plan(List<String> sets, List<String> sorted, String tenantSet, String tenantBytes,
            String tenantLock, long bytes) {}
    static List<String> fields(EntityMetadata<?, ?> metadata, QueryIndexConfig config) {
        if (metadata.columns().size() > 128 || metadata.compositeExactIndexes().size() > 32
                || config.prefixMaxLength() > 64 || config.textMaxTokensPerValue() > 128)
            throw new IllegalArgumentException("Retention index schema exceeds bounded layout");
        RedisCompositeIndexes.validate(metadata);
        var fields = new ArrayList<String>(config.sparseIndexMetadata() ? metadata.indexedColumns() : metadata.columns());
        fields.sort(String::compareTo);
        metadata.compositeExactIndexes().keySet().stream().sorted().forEach(name -> fields.add(RedisCompositeIndexes.MARKER + name));
        if (fields.size() > 160) throw new IllegalArgumentException("Too many retention index fields");
        for (String field : fields) if (field.length() > 256) throw new IllegalArgumentException("Invalid index field");
        return List.copyOf(fields);
    }
    static Plan plan(RedisKeyStrategy keys, EntityMetadata<?, ?> metadata, QueryIndexConfig config,
            Map<String,String> values, String hotOwner) {
        String ns = metadata.redisNamespace();
        var sets = new LinkedHashSet<String>(); var sorted = new LinkedHashSet<String>();
        sets.add(keys.indexAllKey(ns)); sorted.add(keys.hotSetKey(ns));
        for (var entry : values.entrySet()) {
            String field = entry.getKey(), value = entry.getValue();
            if (field.startsWith(RedisCompositeIndexes.MARKER)) {
                if (!value.startsWith(keys.indexExactKey(ns, field, ""))) throw new IllegalArgumentException("Invalid composite locator");
                sets.add(value); continue;
            }
            if (config.exactIndexEnabled()) sets.add(keys.indexExactKey(ns, field, RedisIndexKeySpace.encodeKeyPart(value)));
            sorted.add(keys.indexSortKey(ns, field));
            String decoded = RedisIndexKeySpace.decodeValue(value);
            if (decoded != null) {
                String text = RedisIndexKeySpace.normalizeText(decoded);
                if (config.prefixIndexEnabled())
                    for (int i=1; i<=Math.min(text.length(),config.prefixMaxLength()); i++)
                        sets.add(keys.indexPrefixKey(ns,field,RedisIndexKeySpace.encodeKeyPart(text.substring(0,i))));
                if (config.textIndexEnabled())
                    for (String token : RedisIndexKeySpace.tokenize(decoded,config))
                        sets.add(keys.indexTokenKey(ns,field,RedisIndexKeySpace.encodeKeyPart(token)));
            }
        }
        for (var partition : metadata.partitionedSortIndexes().entrySet()) {
            String value = values.get(partition.getKey());
            if (value != null) for (String sort : partition.getValue())
                sorted.add(keys.indexPartitionSortKey(ns,partition.getKey(),RedisIndexKeySpace.encodeKeyPart(value),sort));
        }
        String tenantSet="", tenantBytes="", tenantLock=""; long bytes=0;
        if (hotOwner != null && !hotOwner.isEmpty()) {
            if (hotOwner.length() > 8192) throw new IllegalArgumentException("Invalid tenant admission record");
            String[] parts=hotOwner.split("\\n",-1);
            if (parts.length!=1 && parts.length!=3) throw new IllegalArgumentException("Unsupported tenant admission record");
            tenantSet=parts[0];
            if (!tenantSet.startsWith(keys.hotSetKey(ns)+":tenant:")) throw new IllegalArgumentException("Foreign admission record");
            tenantLock=tenantSet+":admission-lock"; sorted.add(tenantSet);
            if (parts.length==3) {
                tenantBytes=parts[1]; bytes=Long.parseLong(parts[2]);
                if (!tenantBytes.equals(tenantSet+":payload-bytes") || bytes<0 || bytes>16L*1024*1024)
                    throw new IllegalArgumentException("Invalid tenant byte record");
            }
        }
        if (sets.size()+sorted.size()>2048) throw new IllegalArgumentException("Retention cleanup exceeds 2048 memberships");
        return new Plan(List.copyOf(sets),List.copyOf(sorted),tenantSet,tenantBytes,tenantLock,bytes);
    }
    private RedisRetentionIndexPlan() {}
}
