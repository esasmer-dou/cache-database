package com.reactor.cachedb.core.queue;

import java.util.List;
import java.util.Optional;

public interface DeadLetterManagement {
    default List<DeadLetterEntry> listDeadLetters(int limit) {
        return queryDeadLetters(DeadLetterQuery.builder().limit(limit).build()).items();
    }

    default List<ReconciliationRecord> listReconciliation(int limit) {
        return queryReconciliation(ReconciliationQuery.builder().limit(limit).build()).items();
    }

    default List<DeadLetterArchiveRecord> listArchive(int limit) {
        return queryArchive(ReconciliationQuery.builder().limit(limit).build()).items();
    }

    StreamPage<DeadLetterEntry> queryDeadLetters(DeadLetterQuery query);
    StreamPage<ReconciliationRecord> queryReconciliation(ReconciliationQuery query);
    StreamPage<DeadLetterArchiveRecord> queryArchive(ReconciliationQuery query);
    Optional<DeadLetterEntry> getDeadLetter(String entryId);
    DeadLetterReplayPreview dryRunReplay(String entryId);
    DeadLetterActionResult replay(String entryId, String note);
    DeadLetterActionResult skip(String entryId, String note);
    DeadLetterActionResult close(String entryId, String note);

    default BulkDeadLetterActionResult bulkReplay(List<String> entryIds, String note) {
        return applyBulk("replay", entryIds, entryId -> replay(entryId, note));
    }

    default BulkDeadLetterActionResult bulkSkip(List<String> entryIds, String note) {
        return applyBulk("skip", entryIds, entryId -> skip(entryId, note));
    }

    default BulkDeadLetterActionResult bulkClose(List<String> entryIds, String note) {
        return applyBulk("close", entryIds, entryId -> close(entryId, note));
    }

    private BulkDeadLetterActionResult applyBulk(
            String action,
            List<String> entryIds,
            java.util.function.Function<String, DeadLetterActionResult> handler
    ) {
        java.util.ArrayList<DeadLetterActionResult> results = new java.util.ArrayList<>(entryIds.size());
        int appliedCount = 0;
        for (String entryId : entryIds) {
            DeadLetterActionResult result = handler.apply(entryId);
            results.add(result);
            if (result.applied()) {
                appliedCount++;
            }
        }
        return new BulkDeadLetterActionResult(
                action,
                entryIds.size(),
                appliedCount,
                entryIds.size() - appliedCount,
                List.copyOf(results)
        );
    }
}
