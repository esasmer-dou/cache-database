package com.reactor.cachedb.redis;

public final class RedisKeyStrategy {
    private final String keyPrefix;
    private final String entitySegment;
    private final String pageSegment;
    private final String versionSegment;
    private final String tombstoneSegment;
    private final String hotSetSegment;
    private final String indexSegment;
    private final String compactionSegment;

    public RedisKeyStrategy() {
        this("cachedb", "entity", "page", "version", "tombstone", "hotset", "index", "compaction");
    }

    public RedisKeyStrategy(String keyPrefix) {
        this(keyPrefix, "entity", "page", "version", "tombstone", "hotset", "index", "compaction");
    }

    public RedisKeyStrategy(String keyPrefix, String entitySegment, String pageSegment, String versionSegment, String hotSetSegment, String indexSegment) {
        this(keyPrefix, entitySegment, pageSegment, versionSegment, "tombstone", hotSetSegment, indexSegment, "compaction");
    }

    public RedisKeyStrategy(String keyPrefix, String entitySegment, String pageSegment, String versionSegment, String hotSetSegment, String indexSegment, String compactionSegment) {
        this(keyPrefix, entitySegment, pageSegment, versionSegment, "tombstone", hotSetSegment, indexSegment, compactionSegment);
    }

    public RedisKeyStrategy(
            String keyPrefix,
            String entitySegment,
            String pageSegment,
            String versionSegment,
            String tombstoneSegment,
            String hotSetSegment,
            String indexSegment,
            String compactionSegment
    ) {
        this.keyPrefix = keyPrefix;
        this.entitySegment = entitySegment;
        this.pageSegment = pageSegment;
        this.versionSegment = versionSegment;
        this.tombstoneSegment = tombstoneSegment;
        this.hotSetSegment = hotSetSegment;
        this.indexSegment = indexSegment;
        this.compactionSegment = compactionSegment;
    }

    public String entityKey(String namespace, Object id) {
        return keyPrefix + ":" + namespace + ":" + entitySegment + ":" + id;
    }

    public String projectionKey(String namespace, String projectionName, Object id) {
        return keyPrefix + ":" + namespace + ":" + entitySegment + ":projection:" + projectionName + ":" + id;
    }

    public String pageKey(String namespace, int pageNumber) {
        return keyPrefix + ":" + namespace + ":" + pageSegment + ":" + pageNumber;
    }

    public String versionKey(String namespace, Object id) {
        return keyPrefix + ":" + namespace + ":" + versionSegment + ":" + id;
    }

    public String tombstoneKey(String namespace, Object id) {
        return keyPrefix + ":" + namespace + ":" + tombstoneSegment + ":" + id;
    }

    public String entityPattern(String namespace) {
        return keyPrefix + ":" + namespace + ":" + entitySegment + ":*";
    }

    public String hotSetKey(String namespace) {
        return keyPrefix + ":" + namespace + ":" + hotSetSegment;
    }

    public String indexAllKey(String namespace) {
        return keyPrefix + ":" + namespace + ":" + indexSegment + ":all";
    }

    public String indexMetaKey(String namespace, Object id) {
        return keyPrefix + ":" + namespace + ":" + indexSegment + ":meta:" + id;
    }

    public String indexDegradedKey(String namespace) {
        return keyPrefix + ":" + namespace + ":" + indexSegment + ":state:degraded";
    }

    public String indexRebuildLockKey(String namespace) {
        return keyPrefix + ":" + namespace + ":" + indexSegment + ":state:rebuild-lock";
    }

    public String indexPattern(String namespace) {
        return keyPrefix + ":" + namespace + ":" + indexSegment + ":*";
    }

    public String indexExactKey(String namespace, String column, String encodedValue) {
        return keyPrefix + ":" + namespace + ":" + indexSegment + ":eq:" + column + ":" + encodedValue;
    }

    public String indexSortKey(String namespace, String column) {
        return keyPrefix + ":" + namespace + ":" + indexSegment + ":sort:" + column;
    }

    public String indexPartitionSortKey(String namespace, String exactColumn, String encodedExactValue, String sortColumn) {
        return keyPrefix + ":" + namespace + ":" + indexSegment + ":partition-sort:" + exactColumn + ":" + encodedExactValue + ":" + sortColumn;
    }

    public String indexPrefixKey(String namespace, String column, String encodedPrefix) {
        return keyPrefix + ":" + namespace + ":" + indexSegment + ":prefix:" + column + ":" + encodedPrefix;
    }

    public String indexTokenKey(String namespace, String column, String encodedToken) {
        return keyPrefix + ":" + namespace + ":" + indexSegment + ":token:" + column + ":" + encodedToken;
    }

    public String indexPlannerEstimateKey(String namespace, String encodedExpression) {
        return keyPrefix + ":" + namespace + ":" + indexSegment + ":stats:estimate:" + encodedExpression;
    }

    public String indexPlannerHistogramKey(String namespace, String encodedColumn) {
        return keyPrefix + ":" + namespace + ":" + indexSegment + ":stats:histogram:" + encodedColumn;
    }

    public String indexPlannerLearnedKey(String namespace, String encodedExpression) {
        return keyPrefix + ":" + namespace + ":" + indexSegment + ":stats:learned:" + encodedExpression;
    }

    public String indexPlannerStatsPattern(String namespace) {
        return keyPrefix + ":" + namespace + ":" + indexSegment + ":stats:*";
    }

    public String indexPlannerEpochKey(String namespace) {
        return keyPrefix + ":" + namespace + ":" + indexSegment + ":stats:epoch";
    }

    public String compactionPayloadKey(String namespace, Object id) {
        return keyPrefix + ":" + namespace + ":" + compactionSegment + ":payload:" + id;
    }

    public String compactionPendingKey(String namespace, Object id) {
        return keyPrefix + ":" + namespace + ":" + compactionSegment + ":pending:" + id;
    }

    public String compactionStatsKey() {
        return keyPrefix + ":" + compactionSegment + ":stats";
    }

    public int compactionShard(String namespace, Object id, int shardCount) {
        if (shardCount <= 1) {
            return 0;
        }
        int hash = java.util.Objects.hash(namespace, String.valueOf(id));
        return Math.floorMod(hash, shardCount);
    }

    public String compactionStreamKey(String baseStreamKey, String namespace, Object id, int shardCount) {
        if (shardCount <= 1) {
            return baseStreamKey;
        }
        return baseStreamKey + ":" + compactionShard(namespace, id, shardCount);
    }
}
