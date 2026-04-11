package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.api.ProjectionRepository;
import com.reactor.cachedb.core.projection.EntityProjection;
import com.reactor.cachedb.core.query.QueryFilter;
import com.reactor.cachedb.core.query.QueryEvaluator;
import com.reactor.cachedb.core.query.QueryOperator;
import com.reactor.cachedb.core.query.QuerySort;
import com.reactor.cachedb.core.query.QuerySortDirection;
import com.reactor.cachedb.core.query.QuerySpec;
import com.reactor.cachedb.core.queue.StoragePerformanceCollector;
import redis.clients.jedis.resps.Tuple;

import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

public final class RedisProjectionRepository<T, ID, P> implements ProjectionRepository<P, ID> {

    private final RedisProjectionRuntime<P, ID> readRuntime;
    private final RedisProjectionRuntime<P, ID> refreshRuntime;
    private final QueryEvaluator queryEvaluator;
    private final Function<String, Optional<P>> missingByRawIdLoader;
    private final Function<Collection<String>, Map<String, P>> missingByRawIdsLoader;
    private final Function<QuerySpec, List<P>> queryWarmupLoader;
    private final StoragePerformanceCollector performanceCollector;

    public RedisProjectionRepository(
            RedisProjectionRuntime<P, ID> readRuntime,
            RedisProjectionRuntime<P, ID> refreshRuntime,
            QueryEvaluator queryEvaluator,
            Function<String, Optional<P>> missingByRawIdLoader,
            Function<Collection<String>, Map<String, P>> missingByRawIdsLoader,
            Function<QuerySpec, List<P>> queryWarmupLoader,
            StoragePerformanceCollector performanceCollector
    ) {
        this.readRuntime = Objects.requireNonNull(readRuntime, "readRuntime");
        this.refreshRuntime = Objects.requireNonNull(refreshRuntime, "refreshRuntime");
        this.queryEvaluator = Objects.requireNonNull(queryEvaluator, "queryEvaluator");
        this.missingByRawIdLoader = Objects.requireNonNull(missingByRawIdLoader, "missingByRawIdLoader");
        this.missingByRawIdsLoader = Objects.requireNonNull(missingByRawIdsLoader, "missingByRawIdsLoader");
        this.queryWarmupLoader = Objects.requireNonNull(queryWarmupLoader, "queryWarmupLoader");
        this.performanceCollector = performanceCollector;
    }

    @Override
    public Optional<P> findById(ID id) {
        long startedAt = System.nanoTime();
        try {
            String rawId = String.valueOf(id);
            List<String> values = readRuntime.jedis().mget(readRuntime.payloadKey(id), readRuntime.tombstoneKey(id));
            if (values.get(1) != null) {
                return Optional.empty();
            }
            String encodedProjection = values.get(0);
            if (encodedProjection != null) {
                return Optional.of(readRuntime.codec().fromRedisValue(encodedProjection));
            }
            Optional<P> missing = missingByRawIdLoader.apply(rawId);
            missing.ifPresent(this::warmProjection);
            return missing;
        } finally {
            recordRedisRead(startedAt);
        }
    }

    @Override
    public List<P> findAll(Collection<ID> ids) {
        long startedAt = System.nanoTime();
        try {
            if (ids.isEmpty()) {
                return List.of();
            }
            List<ID> idsList = new ArrayList<>(ids);
            List<String> rawIds = idsList.stream().map(String::valueOf).toList();
            LinkedHashMap<String, P> resolved = loadProjectionMap(rawIds);
            ArrayList<P> ordered = new ArrayList<>(resolved.size());
            for (ID id : idsList) {
                P projection = resolved.get(String.valueOf(id));
                if (projection != null) {
                    ordered.add(projection);
                }
            }
            return List.copyOf(ordered);
        } finally {
            recordRedisRead(startedAt);
        }
    }

    @Override
    public List<P> query(QuerySpec querySpec) {
        long startedAt = System.nanoTime();
        try {
            readRuntime.queryIndexManager().warm(querySpec);
            Optional<List<P>> rankedWindowResult = queryViaRankedProjectionWindow(querySpec);
            if (rankedWindowResult.isPresent()) {
                return rankedWindowResult.get();
            }
            boolean sortedIndexScan = readRuntime.queryIndexManager().supportsSortedIndexScan(querySpec);
            boolean primarySortedScan = !sortedIndexScan && readRuntime.queryIndexManager().shouldUsePrimarySortedScan(querySpec);
            List<String> candidateIds = sortedIndexScan
                    ? readRuntime.queryIndexManager().resolveSortedIds(querySpec)
                    : (primarySortedScan
                    ? readRuntime.queryIndexManager().resolvePrimarySortedIds(querySpec)
                    : readRuntime.queryIndexManager().resolveCandidateIds(querySpec));
            boolean completeSortOrderResolved = primarySortedScan
                    && readRuntime.queryIndexManager().resolvesCompleteSortOrder(querySpec, candidateIds.size());
            boolean requiresResidualEvaluation = !readRuntime.queryIndexManager().fullyIndexed(querySpec.rootGroup());
            boolean requiresInMemorySort = !querySpec.sorts().isEmpty() && !sortedIndexScan && !completeSortOrderResolved;

            if (candidateIds.isEmpty()) {
                List<P> warmed = Optional.ofNullable(queryWarmupLoader.apply(querySpec)).orElseGet(List::of);
                warmProjections(warmed);
                return List.copyOf(warmed);
            }

            if (!requiresResidualEvaluation && !requiresInMemorySort) {
                List<String> payloadIds = windowResolvedCandidateIds(querySpec, candidateIds, sortedIndexScan, completeSortOrderResolved);
                LinkedHashMap<String, P> resolved = loadProjectionMap(payloadIds);
                readRuntime.queryIndexManager().observe(querySpec, resolved.size());
                List<P> ordered = new ArrayList<>(resolved.values());
                if (sortedIndexScan || completeSortOrderResolved) {
                    return List.copyOf(ordered);
                }
                int fromIndex = Math.min(querySpec.offset(), ordered.size());
                int toIndex = Math.min(fromIndex + querySpec.limit(), ordered.size());
                return List.copyOf(ordered.subList(fromIndex, toIndex));
            }

            return queryViaProjectionPayload(querySpec, candidateIds, sortedIndexScan);
        } finally {
            recordRedisRead(startedAt);
        }
    }

    private Optional<List<P>> queryViaRankedProjectionWindow(QuerySpec querySpec) {
        RankedProjectionPlan plan = buildRankedProjectionPlan(querySpec);
        if (plan == null) {
            return Optional.empty();
        }

        int targetCount = querySpec.offset() + querySpec.limit();
        if (targetCount <= 0) {
            return Optional.of(List.of());
        }

        ArrayList<P> matched = new ArrayList<>(Math.max(querySpec.limit(), 16));
        int rank = 0;
        int chunkSize = Math.max(Math.max(querySpec.limit(), 1) * 4, 64);
        boolean stopScanning = false;
        while (!stopScanning && matched.size() < targetCount) {
            List<Tuple> batch = plan.sort().direction() == QuerySortDirection.DESC
                    ? readRuntime.jedis().zrevrangeWithScores(plan.sortKey(), rank, rank + chunkSize - 1)
                    : readRuntime.jedis().zrangeWithScores(plan.sortKey(), rank, rank + chunkSize - 1);
            if (batch == null || batch.isEmpty()) {
                break;
            }

            ArrayList<String> rawIds = new ArrayList<>(batch.size());
            for (Tuple tuple : batch) {
                if (plan.scoreRange() != null) {
                    if (!plan.scoreRange().matches(tuple.getScore())) {
                        if (plan.scoreRange().canStop(tuple.getScore(), plan.sort().direction())) {
                            stopScanning = true;
                            break;
                        }
                        continue;
                    }
                }
                rawIds.add(tuple.getElement());
            }

            if (!rawIds.isEmpty()) {
                LinkedHashMap<String, P> resolved = loadProjectionMap(rawIds);
                for (String rawId : rawIds) {
                    P projection = resolved.get(rawId);
                    if (projection == null) {
                        continue;
                    }
                    Map<String, Object> columns = plan.projection().columnExtractor().apply(projection);
                    if (queryEvaluator.matches(columns, querySpec.rootGroup())) {
                        matched.add(projection);
                        if (matched.size() >= targetCount) {
                            break;
                        }
                    }
                }
            }

            rank += chunkSize;
        }

        if (matched.isEmpty()) {
            List<P> warmed = Optional.ofNullable(queryWarmupLoader.apply(querySpec)).orElseGet(List::of);
            warmProjections(warmed);
            return Optional.of(List.copyOf(warmed));
        }

        int fromIndex = Math.min(querySpec.offset(), matched.size());
        int toIndex = Math.min(fromIndex + querySpec.limit(), matched.size());
        return Optional.of(List.copyOf(matched.subList(fromIndex, toIndex)));
    }

    private RankedProjectionPlan buildRankedProjectionPlan(QuerySpec querySpec) {
        if (querySpec == null || querySpec.sorts().size() != 1) {
            return null;
        }
        EntityProjection<?, P, ID> projection = readRuntime.binding().projection();
        QuerySort sort = querySpec.sorts().get(0);
        if (!projection.supportsRankedColumn(sort.column())) {
            return null;
        }
        Set<String> availableColumns = projectionAvailableColumns(projection);
        boolean filtersSupported = querySpec.filters().stream()
                .allMatch(filter -> availableColumns.contains(filter.column()));
        if (!filtersSupported) {
            return null;
        }
        boolean sortsSupported = querySpec.sorts().stream()
                .allMatch(candidateSort -> availableColumns.contains(candidateSort.column()));
        if (!sortsSupported) {
            return null;
        }
        return new RankedProjectionPlan(
                projection,
                sort,
                readRuntime.sortedIndexKey(sort.column()),
                scoreRangeForRankedProjection(querySpec.filters(), sort.column())
        );
    }

    private Set<String> projectionAvailableColumns(EntityProjection<?, P, ID> projection) {
        LinkedHashSet<String> columns = new LinkedHashSet<>(projection.columns());
        columns.add(readRuntime.metadata().idColumn());
        return columns;
    }

    private ScoreRange scoreRangeForRankedProjection(List<QueryFilter> filters, String rankedColumn) {
        double min = Double.NEGATIVE_INFINITY;
        boolean minInclusive = true;
        double max = Double.POSITIVE_INFINITY;
        boolean maxInclusive = true;
        boolean constrained = false;
        for (QueryFilter filter : filters) {
            if (!rankedColumn.equals(filter.column())) {
                continue;
            }
            Double score = RedisScoreSupport.toScore(readRuntime.metadata().columnTypes(), rankedColumn, filter.value());
            if (score == null) {
                continue;
            }
            switch (filter.operator()) {
                case GT -> {
                    if (score > min || (score == min && minInclusive)) {
                        min = score;
                        minInclusive = false;
                        constrained = true;
                    }
                }
                case GTE -> {
                    if (score > min || (score == min && !minInclusive)) {
                        min = score;
                        minInclusive = true;
                        constrained = true;
                    }
                }
                case LT -> {
                    if (score < max || (score == max && maxInclusive)) {
                        max = score;
                        maxInclusive = false;
                        constrained = true;
                    }
                }
                case LTE -> {
                    if (score < max || (score == max && !maxInclusive)) {
                        max = score;
                        maxInclusive = true;
                        constrained = true;
                    }
                }
                case EQ -> {
                    min = score;
                    minInclusive = true;
                    max = score;
                    maxInclusive = true;
                    constrained = true;
                }
                default -> {
                }
            }
        }
        if (!constrained) {
            return null;
        }
        return new ScoreRange(min, minInclusive, max, maxInclusive);
    }

    private List<P> queryViaProjectionPayload(QuerySpec querySpec, List<String> candidateIds, boolean sortedIndexScan) {
        LinkedHashMap<String, P> resolved = loadProjectionMap(candidateIds);
        ArrayList<P> projections = new ArrayList<>(resolved.size());
        Map<P, Map<String, Object>> resolvedColumns = new IdentityHashMap<>(Math.max(16, resolved.size()));
        boolean requiresResidualEvaluation = !readRuntime.queryIndexManager().fullyIndexed(querySpec.rootGroup());
        for (String candidateId : candidateIds) {
            P projection = resolved.get(candidateId);
            if (projection == null) {
                continue;
            }
            boolean matches = true;
            if (requiresResidualEvaluation) {
                Map<String, Object> columns = readRuntime.codec().toColumns(projection);
                resolvedColumns.put(projection, columns);
                matches = queryEvaluator.matches(columns, querySpec.rootGroup());
            }
            if (matches) {
                projections.add(projection);
            }
        }

        readRuntime.queryIndexManager().observe(querySpec, projections.size());

        if (!querySpec.sorts().isEmpty() && !sortedIndexScan) {
            Map<P, Map<String, Object>> sortColumns = new IdentityHashMap<>(Math.max(projections.size(), resolvedColumns.size()));
            sortColumns.putAll(resolvedColumns);
            for (P projection : projections) {
                sortColumns.computeIfAbsent(projection, readRuntime.codec()::toColumns);
            }
            projections.sort((left, right) -> compareBySorts(sortColumns.get(left), sortColumns.get(right), querySpec.sorts()));
        }

        if (sortedIndexScan) {
            return List.copyOf(projections);
        }

        int fromIndex = Math.min(querySpec.offset(), projections.size());
        int toIndex = Math.min(fromIndex + querySpec.limit(), projections.size());
        return List.copyOf(projections.subList(fromIndex, toIndex));
    }

    private LinkedHashMap<String, P> loadProjectionMap(List<String> rawIds) {
        if (rawIds.isEmpty()) {
            return new LinkedHashMap<>();
        }
        List<String> payloadKeys = rawIds.stream()
                .map(this::projectionPayloadKey)
                .toList();
        List<String> tombstoneKeys = rawIds.stream()
                .map(this::projectionTombstoneKey)
                .toList();
        List<String> values = mgetPayloadAndTombstones(payloadKeys, tombstoneKeys);
        LinkedHashMap<String, P> resolved = new LinkedHashMap<>();
        ArrayList<String> missingIds = new ArrayList<>();
        for (int index = 0; index < rawIds.size(); index++) {
            if (values.get(rawIds.size() + index) != null) {
                continue;
            }
            String encodedProjection = values.get(index);
            if (encodedProjection != null) {
                resolved.put(rawIds.get(index), readRuntime.codec().fromRedisValue(encodedProjection));
            } else {
                missingIds.add(rawIds.get(index));
            }
        }

        if (!missingIds.isEmpty()) {
            Map<String, P> warmed = Optional.ofNullable(missingByRawIdsLoader.apply(missingIds)).orElseGet(Map::of);
            warmProjections(warmed.values());
            resolved.putAll(warmed);
        }

        LinkedHashMap<String, P> ordered = new LinkedHashMap<>();
        for (String rawId : rawIds) {
            P projection = resolved.get(rawId);
            if (projection != null) {
                ordered.put(rawId, projection);
            }
        }
        return ordered;
    }

    private List<String> windowResolvedCandidateIds(
            QuerySpec querySpec,
            List<String> candidateIds,
            boolean sortedIndexScan,
            boolean completeSortOrderResolved
    ) {
        if (candidateIds.isEmpty()) {
            return List.of();
        }
        if (sortedIndexScan || !completeSortOrderResolved) {
            return candidateIds;
        }
        int fromIndex = Math.min(querySpec.offset(), candidateIds.size());
        int toIndex = Math.min(fromIndex + querySpec.limit(), candidateIds.size());
        return candidateIds.subList(fromIndex, toIndex);
    }

    private String projectionPayloadKey(String rawId) {
        return readRuntime.payloadKey(rawId);
    }

    private String projectionTombstoneKey(String rawId) {
        return readRuntime.tombstoneKey(rawId);
    }

    private List<String> mgetPayloadAndTombstones(List<String> payloadKeys, List<String> tombstoneKeys) {
        int payloadCount = payloadKeys.size();
        String[] lookupKeys = new String[payloadCount + tombstoneKeys.size()];
        for (int index = 0; index < payloadCount; index++) {
            lookupKeys[index] = payloadKeys.get(index);
            lookupKeys[payloadCount + index] = tombstoneKeys.get(index);
        }
        return readRuntime.jedis().mget(lookupKeys);
    }

    private void warmProjection(P projection) {
        if (projection == null) {
            return;
        }
        try {
            refreshRuntime.upsert(projection);
        } catch (RuntimeException ignored) {
        }
    }

    private void warmProjections(Collection<P> projections) {
        if (projections == null || projections.isEmpty()) {
            return;
        }
        for (P projection : projections) {
            warmProjection(projection);
        }
    }

    private int compareBySorts(Map<String, Object> leftColumns, Map<String, Object> rightColumns, List<QuerySort> sorts) {
        for (QuerySort sort : sorts) {
            int result = queryEvaluator.comparator(sort).compare(leftColumns, rightColumns);
            if (result != 0) {
                return result;
            }
        }
        return 0;
    }

    private void recordRedisRead(long startedAtNanos) {
        if (performanceCollector == null) {
            return;
        }
        performanceCollector.recordRedisRead((System.nanoTime() - startedAtNanos) / 1_000L);
    }

    private final class RankedProjectionPlan {
        private final EntityProjection<?, P, ID> projection;
        private final QuerySort sort;
        private final String sortKey;
        private final ScoreRange scoreRange;

        private RankedProjectionPlan(
                EntityProjection<?, P, ID> projection,
                QuerySort sort,
                String sortKey,
                ScoreRange scoreRange
        ) {
            this.projection = projection;
            this.sort = sort;
            this.sortKey = sortKey;
            this.scoreRange = scoreRange;
        }

        private EntityProjection<?, P, ID> projection() {
            return projection;
        }

        private QuerySort sort() {
            return sort;
        }

        private String sortKey() {
            return sortKey;
        }

        private ScoreRange scoreRange() {
            return scoreRange;
        }
    }

    private record ScoreRange(
            double min,
            boolean minInclusive,
            double max,
            boolean maxInclusive
    ) {
        private boolean matches(double score) {
            boolean lower = minInclusive ? score >= min : score > min;
            boolean upper = maxInclusive ? score <= max : score < max;
            return lower && upper;
        }

        private boolean canStop(double score, QuerySortDirection direction) {
            if (direction == QuerySortDirection.DESC) {
                return minInclusive ? score < min : score <= min;
            }
            return maxInclusive ? score > max : score >= max;
        }
    }
}
