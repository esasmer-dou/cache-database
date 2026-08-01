package com.reactor.cachedb.core.relation;

import com.reactor.cachedb.core.api.EntityRepository;
import com.reactor.cachedb.core.query.PartitionedQuerySpec;
import com.reactor.cachedb.core.query.QuerySort;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Reusable relation loader backed by a per-partition sorted Redis index.
 */
public class PartitionedRelationBatchLoader<P, C, K> implements RelationBatchLoader<P> {

    private final String relationName;
    private final int maxRelationLimit;
    private final int maxParentsPerBatch;
    private final EntityRepository<C, ?> childRepository;
    private final Function<P, K> parentIdAccessor;
    private final BiConsumer<P, List<C>> relationSetter;
    private final String partitionColumn;
    private final List<QuerySort> sorts;

    public PartitionedRelationBatchLoader(
            String relationName,
            int maxRelationLimit,
            int maxParentsPerBatch,
            EntityRepository<C, ?> childRepository,
            Function<P, K> parentIdAccessor,
            BiConsumer<P, List<C>> relationSetter,
            String partitionColumn,
            QuerySort... sorts
    ) {
        if (relationName == null || relationName.isBlank()) {
            throw new IllegalArgumentException("relationName must not be blank");
        }
        if (maxRelationLimit <= 0 || maxParentsPerBatch <= 0) {
            throw new IllegalArgumentException("Relation limits must be greater than zero");
        }
        this.relationName = relationName.trim();
        this.maxRelationLimit = maxRelationLimit;
        this.maxParentsPerBatch = maxParentsPerBatch;
        this.childRepository = Objects.requireNonNull(childRepository, "childRepository");
        this.parentIdAccessor = Objects.requireNonNull(parentIdAccessor, "parentIdAccessor");
        this.relationSetter = Objects.requireNonNull(relationSetter, "relationSetter");
        this.partitionColumn = Objects.requireNonNull(partitionColumn, "partitionColumn");
        this.sorts = List.of(sorts);
        if (this.sorts.isEmpty()) {
            throw new IllegalArgumentException("At least one relation sort is required");
        }
    }

    @Override
    public void preload(List<P> parents, RelationBatchContext context) {
        if (parents == null || parents.isEmpty() || !context.fetchPlan().includes(relationName)) {
            return;
        }
        int relationLimit = Math.max(1, Math.min(context.relationLimit(relationName), maxRelationLimit));
        LinkedHashMap<K, P> parentsById = new LinkedHashMap<>();
        for (P parent : parents) {
            if (parent == null) {
                continue;
            }
            K parentId = parentIdAccessor.apply(parent);
            if (parentId != null) {
                parentsById.put(parentId, parent);
            }
        }
        List<K> parentIds = new ArrayList<>(parentsById.keySet());
        LinkedHashMap<K, List<C>> childrenByParent = new LinkedHashMap<>(parentIds.size());
        for (int start = 0; start < parentIds.size(); start += maxParentsPerBatch) {
            List<K> chunk = parentIds.subList(start, Math.min(parentIds.size(), start + maxParentsPerBatch));
            Map<K, List<C>> loaded = childRepository.queryPartitions(new PartitionedQuerySpec<>(
                    partitionColumn,
                    chunk,
                    sorts,
                    relationLimit
            ));
            childrenByParent.putAll(loaded);
        }
        parentsById.forEach((parentId, parent) ->
                relationSetter.accept(parent, childrenByParent.getOrDefault(parentId, List.of())));
    }
}
