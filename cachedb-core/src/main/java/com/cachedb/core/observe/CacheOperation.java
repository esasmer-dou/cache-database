package com.reactor.cachedb.core.observe;

public enum CacheOperation {
    FIND_BY_ID("findById"), FIND_HOT_BY_ID("findHotById"), FIND_VERSIONED_BY_ID("findVersionedById"),
    FIND_ALL("findAll"), FIND_PAGE("findPage"), QUERY("query"), QUERY_PARTITIONS("queryPartitions"),
    EXPLAIN("explain"), COUNT("count"), AGGREGATE("aggregate"), AGGREGATE_BY("aggregateBy"),
    SAVE("save"), SAVE_ALL("saveAll"), PATCH("patch"), DELETE("delete"),
    FLUSH_BATCH("flushBatch"), REBUILD_INDEXES("rebuildIndexes"), PURGE("purge");
    private final String tag;
    CacheOperation(String tag) { this.tag = tag; }
    public String tag() { return tag; }
}
