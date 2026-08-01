package com.reactor.cachedb.core.query;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Describes a bounded top-N query for multiple independent partition values.
 * The limit is applied to every partition, never to the combined candidate set.
 */
public record PartitionedQuerySpec<K>(
        String partitionColumn,
        List<K> partitionValues,
        List<QuerySort> sorts,
        int limitPerPartition
) {
    public PartitionedQuerySpec {
        if (partitionColumn == null || partitionColumn.isBlank()) {
            throw new IllegalArgumentException("partitionColumn must not be blank");
        }
        partitionColumn = partitionColumn.trim();
        partitionValues = partitionValues == null
                ? List.of()
                : List.copyOf(new LinkedHashSet<>(partitionValues));
        if (partitionValues.size() > 100) {
            throw new IllegalArgumentException("Partitioned query supports at most 100 partition values per call");
        }
        sorts = sorts == null ? List.of() : List.copyOf(sorts);
        if (sorts.isEmpty()) {
            throw new IllegalArgumentException("Partitioned query requires at least one sort");
        }
        if (limitPerPartition <= 0) {
            throw new IllegalArgumentException("limitPerPartition must be greater than zero");
        }
        if (limitPerPartition > 10_000) {
            throw new IllegalArgumentException("limitPerPartition must not exceed 10000 rows");
        }
    }

    public static <K> PartitionedQuerySpec<K> top(
            String partitionColumn,
            Collection<K> partitionValues,
            int limitPerPartition,
            QuerySort... sorts
    ) {
        return new PartitionedQuerySpec<>(
                partitionColumn,
                partitionValues == null ? List.of() : List.copyOf(partitionValues),
                sorts == null ? List.of() : List.of(sorts),
                limitPerPartition
        );
    }

    public int requestedRowCount() {
        return Math.multiplyExact(partitionValues.size(), limitPerPartition);
    }
}
