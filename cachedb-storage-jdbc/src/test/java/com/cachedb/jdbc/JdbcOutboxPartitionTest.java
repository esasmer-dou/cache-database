package com.reactor.cachedb.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class JdbcOutboxPartitionTest {
    @Test
    void routingAndCheckpointNamesAreStableAndDisjoint() {
        var first = new JdbcOutboxPartition(8, 0, "partition_key");
        var second = new JdbcOutboxPartition(8, 1, "partition_key");
        assertEquals(first.partitionFor("Order", 123L), second.partitionFor("Order", 123L));
        assertNotEquals(first.checkpointName("feed"), second.checkpointName("feed"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new JdbcOutboxPartition(8, 8, "partition_key"));
        assertThrows(IllegalArgumentException.class, () -> new JdbcOutboxPartition(8, 0, "x;drop"));
    }
}
