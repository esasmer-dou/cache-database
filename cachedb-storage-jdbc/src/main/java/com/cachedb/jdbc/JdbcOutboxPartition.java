package com.reactor.cachedb.jdbc;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.zip.CRC32;

/** Producer-assigned partition; count and routing must stay fixed for the outbox lifetime. */
public record JdbcOutboxPartition(int count, int index, String column) {
    public JdbcOutboxPartition {
        if (count < 1 || count > 1024 || index < 0 || index >= count)
            throw new IllegalArgumentException("Invalid outbox partition count/index");
        if (column == null || !column.matches("[A-Za-z_][A-Za-z0-9_]*"))
            throw new IllegalArgumentException("Invalid partition column");
    }

    public int partitionFor(String entityName, Object entityId) {
        Objects.requireNonNull(entityName, "entityName");
        Objects.requireNonNull(entityId, "entityId");
        String id = entityId.toString();
        CRC32 hash = new CRC32();
        hash.update((entityName.length() + ":" + entityName + id).getBytes(StandardCharsets.UTF_8));
        return (int) (hash.getValue() % count);
    }

    String checkpointName(String base) {
        String result = base + "#partitions-" + count + "-shard-" + index;
        if (result.length() > 200)
            throw new IllegalArgumentException("Partition checkpoint name exceeds 200 characters");
        return result;
    }
}
