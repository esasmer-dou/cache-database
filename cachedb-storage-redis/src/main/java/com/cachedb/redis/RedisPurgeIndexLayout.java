package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.config.QueryIndexConfig;
import com.reactor.cachedb.core.model.EntityMetadata;
import com.reactor.cachedb.core.protection.ProtectionKey;
import java.util.*;

/** Pure removal plan using the same encodings and declarations as index writes. */
final class RedisPurgeIndexLayout {
    final EntityMetadata<?, ?> metadata;
    final RedisKeyStrategy keys;
    final QueryIndexConfig config;
    final Map<String, List<String>> partitions;
    final List<String> migrationKeys;
    final List<String> fields;
    final String fingerprint;

    RedisPurgeIndexLayout(EntityMetadata<?, ?> metadata, RedisKeyStrategy keys, QueryIndexConfig config,
            Map<String, List<String>> partitions, List<String> migrationKeys) {
        this.metadata = freeze(metadata); metadata = this.metadata; this.keys = keys; this.config = config;
        var copy = new TreeMap<String, List<String>>();
        partitions.forEach((key, value) -> copy.put(key, List.copyOf(value)));
        this.partitions = Map.copyOf(copy);
        this.migrationKeys = List.copyOf(migrationKeys);
        var names = new TreeSet<>(config.sparseIndexMetadata() ? metadata.indexedColumns() : metadata.columns());
        metadata.compositeExactIndexes().keySet().forEach(name -> names.add(RedisCompositeIndexes.MARKER + name));
        metadata.compositeSortColumns().keySet().forEach(name -> names.add(RedisCompositeIndexes.SORT_MARKER + name));
        names.add(RedisQueryIndexManager.PROJECTION_TENANT_MARKER);
        if (names.size() > 512) throw RedisIdentityPurgeParticipant.incomplete();
        for (String name : names)
            if (name == null || name.isEmpty() || name.length() > 256) throw RedisIdentityPurgeParticipant.incomplete();
        fields = List.copyOf(names);
        RedisCompositeIndexes.validate(metadata);
        var schema = new StringBuilder("native-purge-index-v1");
        add(schema, metadata.entityName()); add(schema, metadata.redisNamespace()); add(schema, Integer.toString(metadata.schemaVersion()));
        add(schema, keys.getClass().getName()); add(schema, keys.namespacePrefix(metadata.redisNamespace()));
        String ns = metadata.redisNamespace();
        for (String physical : List.of(keys.entityKey(ns, "layout"), keys.versionKey(ns, "layout"),
                keys.tombstoneKey(ns, "layout"), keys.durabilityKey(ns, "layout"), keys.indexMetaKey(ns, "layout"),
                keys.hotSetKey(ns), keys.tenantHotOwnerKey(ns, "layout"), keys.pageKey(ns, 0),
                keys.indexAllKey(ns), keys.indexExactKey(ns, "layout", "layout"),
                keys.indexSortKey(ns, "layout"), keys.indexPrefixKey(ns, "layout", "layout"),
                keys.indexTokenKey(ns, "layout", "layout"), keys.indexPartitionSortKey(ns, "layout", "layout", "layout")))
            add(schema, physical);
        add(schema, config.toString()); add(schema, RedisCompositeIndexes.signature(metadata));
        for (String field : fields) {
            add(schema, field); add(schema, metadata.columnTypes().getOrDefault(field, ""));
            var encrypted = metadata.encryptedColumns().get(field);
            if (encrypted != null) {
                add(schema, encrypted.keyRing()); add(schema, encrypted.scope().name());
                add(schema, Boolean.toString(encrypted.blindIndex()));
            } else add(schema, "not-encrypted");
        }
        copy.forEach((column, sorts) -> { add(schema, column); sorts.forEach(sort -> add(schema, sort)); });
        fingerprint = ProtectionKey.hash(schema.toString());
    }


    private static EntityMetadata<?, ?> freeze(EntityMetadata<?, ?> source) {
        if (source.columns().size() > 512 || source.indexedColumns().size() > 512
                || source.columnTypes().size() > 512 || source.compositeExactIndexes().size() > 32
                || source.compositeSortColumns().size() > 32 || source.encryptedColumns().size() > 32
                || source.schemaVersion() < 1) throw RedisIdentityPurgeParticipant.incomplete();
        var composites = new TreeMap<String, List<String>>();
        source.compositeExactIndexes().forEach((name, columns) -> {
            if (columns.size() > 4) throw RedisIdentityPurgeParticipant.incomplete();
            composites.put(name, List.copyOf(columns));
        });
        return new FrozenMetadata(source.entityName(), source.tableName(), source.redisNamespace(),
                source.idColumn(), source.tenantFieldName(), List.copyOf(source.columns()), source.schemaVersion(),
                Set.copyOf(source.indexedColumns()), Map.copyOf(source.columnTypes()), Map.copyOf(composites),
                Map.copyOf(source.compositeSortColumns()), Map.copyOf(source.encryptedColumns()), source.nearCachePolicy());
    }

    /** Only the immutable physical schema is needed; no entity accessor or provider is invoked. */
    private record FrozenMetadata(String entityName, String tableName, String redisNamespace, String idColumn,
            String tenantFieldName, List<String> columns, int schemaVersion, Set<String> indexedColumns,
            Map<String, String> columnTypes, Map<String, List<String>> compositeExactIndexes,
            Map<String, String> compositeSortColumns,
            Map<String, com.reactor.cachedb.core.crypto.EncryptedColumnDescriptor> encryptedColumns,
            com.reactor.cachedb.core.cache.NearCachePolicy nearCachePolicy) implements EntityMetadata<Object, Object> {
        public Class<Object> entityType() { return Object.class; }
        public java.util.function.Function<Object, Object> idAccessor() { return java.util.function.Function.identity(); }
        public List<com.reactor.cachedb.core.model.RelationDefinition> relations() { return List.of(); }
    }

    static void add(StringBuilder target, String value) { target.append(value.length()).append(':').append(value); }

    List<RedisPurgeTarget.Action> removals(String id, String tenant, Map<String, String> prior, boolean projection) {
        if (prior.size() > fields.size() || !fields.containsAll(prior.keySet()))
            throw RedisIdentityPurgeParticipant.incomplete();
        com.reactor.cachedb.core.crypto.EncryptedStorageChecks.validateIndexColumns(metadata, prior);
        RedisCompositeIndexes.validateSortMeta(prior);
        if (projection && !prior.isEmpty() && !tenant.equals(prior.get(RedisQueryIndexManager.PROJECTION_TENANT_MARKER)))
            throw RedisIdentityPurgeParticipant.incomplete();
        Collection<String> ordinary = config.sparseIndexMetadata() ? metadata.indexedColumns() : metadata.columns();
        if (!prior.isEmpty() && !prior.keySet().containsAll(ordinary)) throw RedisIdentityPurgeParticipant.incomplete();
        String ns = metadata.redisNamespace();
        long[] keyBytes = {0};
        var actions = new LinkedHashMap<String, RedisPurgeTarget.Action>();
        java.util.function.BiConsumer<String, String> put = (key, type) -> {
            var action = new RedisPurgeTarget.Action(key, type);
            var old = actions.putIfAbsent(key, action);
            if (old != null && !old.equals(action)) throw RedisIdentityPurgeParticipant.incomplete();
            if (old == null) keyBytes[0] += key.length() * 3L + 64;
            if (keyBytes[0] > 256 * 1024) throw RedisIdentityPurgeParticipant.incomplete();
            if (actions.size() > 2048) throw RedisIdentityPurgeParticipant.incomplete();
        };
        for (var entry : prior.entrySet()) {
            String column = entry.getKey(), value = entry.getValue();
            if (column.equals(RedisQueryIndexManager.PROJECTION_TENANT_MARKER)) {
                if (!projection || !tenant.equals(value)) throw RedisIdentityPurgeParticipant.incomplete();
                put.accept(keys.indexExactKey(ns, column, RedisIndexKeySpace.encodeKeyPart(value)), "set");
                continue;
            }
            if (column.startsWith(RedisCompositeIndexes.MARKER)
                    || column.startsWith(RedisCompositeIndexes.SORT_MARKER)) continue;
            if (config.exactIndexEnabled())
                put.accept(keys.indexExactKey(ns, column, RedisIndexKeySpace.encodeKeyPart(value)), "set");
            if (metadata.encryptedColumns().containsKey(column)) continue;
            RedisIndexMutationPlanner.stringKeys(keys, ns, config, column, RedisIndexKeySpace.decodeValue(value),
                    key -> put.accept(key, "set"));
            put.accept(keys.indexSortKey(ns, column), "zset");
        }
        for (var index : metadata.compositeExactIndexes().entrySet()) {
            String marker = RedisCompositeIndexes.MARKER + index.getKey();
            // Literal "__NULL__" is distinct from null in composite keys. Never parse a stored key.
            var values = new LinkedHashMap<String, Object>();
            index.getValue().forEach(column -> values.put(column, prior.get(column)));
            String expected = RedisCompositeIndexes.key(keys, ns, index.getKey(), index.getValue(), values);
            String observed = prior.get(marker);
            if (observed != null) {
                if (!observed.equals(expected)) throw RedisIdentityPurgeParticipant.incomplete();
                put.accept(expected, "set");
            }
            String sort = prior.get(RedisCompositeIndexes.SORT_MARKER + index.getKey());
            if (sort != null) {
                if (observed == null || !RedisCompositeIndexes.sortEntry(sort).key().equals(expected + ":sort"))
                    throw RedisIdentityPurgeParticipant.incomplete();
                put.accept(RedisCompositeIndexes.sortEntry(sort).key(), "zset");
            }
        }
        for (var partition : partitions.entrySet()) {
            String value = prior.get(partition.getKey());
            if (value == null) continue;
            for (String sort : partition.getValue())
                put.accept(keys.indexPartitionSortKey(ns, partition.getKey(),
                        RedisIndexKeySpace.encodeKeyPart(value), sort), "zset");
        }
        // Sorted memberships are safe to remove even for evicted rows lacking index metadata.
        for (String column : metadata.indexedColumns())
            if (!metadata.encryptedColumns().containsKey(column)) put.accept(keys.indexSortKey(ns, column), "zset");
        put.accept(keys.indexAllKey(ns), "set");
        return List.copyOf(actions.values());
    }
}
