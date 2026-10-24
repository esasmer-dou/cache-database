package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.queue.*;
import com.reactor.cachedb.redis.RedisClientOperations;
import com.reactor.cachedb.redis.RedisKeyStrategy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.LongSupplier;

/** Operational reads and bounded export collection, without HTTP or rendering concerns. */
final class AdminWriteBehindInspector implements DeadLetterManagement {
    static final int MAX_EXPORT_ROWS = 50_000;
    static final long MAX_EXPORT_CHARS = 16L * 1024 * 1024;
    private final DeadLetterManagement management;
    private final RedisClientOperations redis;
    private final String compactionStats;

    AdminWriteBehindInspector(DeadLetterManagement management, RedisClientOperations redis, String prefix) {
        this.management = management;
        this.redis = redis;
        this.compactionStats = new RedisKeyStrategy(prefix).compactionStatsKey();
    }

    public StreamPage<DeadLetterEntry> queryDeadLetters(DeadLetterQuery query) { return management.queryDeadLetters(query); }
    public StreamPage<ReconciliationRecord> queryReconciliation(ReconciliationQuery query) { return management.queryReconciliation(query); }
    public StreamPage<DeadLetterArchiveRecord> queryArchive(ReconciliationQuery query) { return management.queryArchive(query); }
    public java.util.Optional<DeadLetterEntry> getDeadLetter(String id) { return management.getDeadLetter(id); }
    public DeadLetterReplayPreview dryRunReplay(String id) { return management.dryRunReplay(id); }
    public DeadLetterActionResult replay(String id,String note) { return management.replay(id,note); }
    public DeadLetterActionResult skip(String id,String note) { return management.skip(id,note); }
    public DeadLetterActionResult close(String id,String note) { return management.close(id,note); }
    public BulkDeadLetterActionResult bulkReplay(List<String> ids,String note) { return management.bulkReplay(ids,note); }
    public BulkDeadLetterActionResult bulkSkip(List<String> ids,String note) { return management.bulkSkip(ids,note); }
    public BulkDeadLetterActionResult bulkClose(List<String> ids,String note) { return management.bulkClose(ids,note); }

    long streamLength(String key) {
        try { return redis.commands().xlen(key); }
        catch (RuntimeException failure) { return 0L; }
    }
    long backlog(LongSupplier runtimeBacklog, List<String> streamKeys) {
        if (runtimeBacklog != null) return runtimeBacklog.getAsLong();
        try {
            Map<String, String> stats = redis.commands().hgetAll(compactionStats);
            if (!stats.isEmpty()) return Math.max(0L, Long.parseLong(stats.getOrDefault("pendingCount", "0")));
        } catch (RuntimeException failure) {
            // Backlog remains observable if compaction telemetry is missing or malformed.
        }
        long total = 0;
        for (String key : streamKeys) total = Math.addExact(total, streamLength(key));
        return total;
    }
    List<DeadLetterEntry> collectDeadLetters(DeadLetterQuery query) {
        return collect(query.cursor(), cursor -> management.queryDeadLetters(new DeadLetterQuery(
                query.limit(), cursor, query.entityName(), query.operationType(), query.entityId(), query.errorType())),
                DeadLetterEntry::fields);
    }
    List<ReconciliationRecord> collectReconciliation(ReconciliationQuery query) {
        return collect(query.cursor(), cursor -> management.queryReconciliation(withCursor(query, cursor)),
                ReconciliationRecord::fields);
    }
    List<DeadLetterArchiveRecord> collectArchive(ReconciliationQuery query) {
        return collect(query.cursor(), cursor -> management.queryArchive(withCursor(query, cursor)),
                DeadLetterArchiveRecord::fields);
    }
    private static ReconciliationQuery withCursor(ReconciliationQuery query, String cursor) {
        return new ReconciliationQuery(query.limit(), cursor, query.status(), query.entityName(), query.operationType(), query.entityId());
    }

    static <T> List<T> collect(String cursor, Function<String, StreamPage<T>> load,
            Function<T, Map<String, String>> fields) {
        List<T> entries = new ArrayList<>();
        long chars = 0;
        for (int pages = 0; pages < 2000; pages++) {
            StreamPage<T> page = Objects.requireNonNull(load.apply(cursor), "export page");
            if (page.items().size() > MAX_EXPORT_ROWS - entries.size()) throw tooLarge();
            for (T entry : page.items()) {
                Map<String, String> values = fields.apply(entry);
                if (values != null) {
                    if (values.size() > 1024) throw tooLarge();
                    for (var value : values.entrySet()) {
                        chars += (value.getKey() == null ? 0 : value.getKey().length());
                        chars += (value.getValue() == null ? 0 : value.getValue().length());
                        if (chars > MAX_EXPORT_CHARS) throw tooLarge();
                    }
                }
                entries.add(entry);
            }
            if (!page.hasMore() || page.nextCursor() == null) return List.copyOf(entries);
            if (Objects.equals(cursor, page.nextCursor())) throw new IllegalStateException("Export cursor did not advance");
            cursor = page.nextCursor();
        }
        throw tooLarge();
    }
    private static IllegalStateException tooLarge() {
        return new IllegalStateException("Admin export exceeds its bounded row/payload/page budget; narrow the filters");
    }
}
