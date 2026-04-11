package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.config.QueryIndexConfig;
import com.reactor.cachedb.core.config.RelationConfig;
import com.reactor.cachedb.core.model.EntityCodec;
import com.reactor.cachedb.core.model.EntityMetadata;
import com.reactor.cachedb.core.model.RelationDefinition;
import com.reactor.cachedb.core.plan.FetchPlan;
import com.reactor.cachedb.core.query.HardLimitQueryClass;
import com.reactor.cachedb.core.query.QueryCardinalityEstimate;
import com.reactor.cachedb.core.query.QueryFilter;
import com.reactor.cachedb.core.query.QueryGroup;
import com.reactor.cachedb.core.query.QueryGroupOperator;
import com.reactor.cachedb.core.query.QueryNode;
import com.reactor.cachedb.core.query.QueryOperator;
import com.reactor.cachedb.core.query.QueryEvaluator;
import com.reactor.cachedb.core.query.QueryExplainPlan;
import com.reactor.cachedb.core.query.QueryExplainRelationState;
import com.reactor.cachedb.core.query.QueryExplainStep;
import com.reactor.cachedb.core.query.QueryHistogramBucket;
import com.reactor.cachedb.core.query.QuerySpec;
import com.reactor.cachedb.core.query.QuerySort;
import com.reactor.cachedb.core.queue.QueryIndexRebuildResult;
import com.reactor.cachedb.core.registry.EntityBinding;
import com.reactor.cachedb.core.registry.EntityRegistry;
import com.reactor.cachedb.core.relation.NoOpRelationBatchLoader;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;
import redis.clients.jedis.params.SetParams;
import redis.clients.jedis.resps.Tuple;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public final class RedisQueryIndexManager<T, ID> {

    private static final String NULL_SENTINEL = "__NULL__";

    private final JedisPooled jedis;
    private final EntityMetadata<T, ID> metadata;
    private final EntityCodec<T> codec;
    private final RedisKeyStrategy keyStrategy;
    private final EntityRegistry entityRegistry;
    private final QueryIndexConfig config;
    private final RelationConfig relationConfig;
    private final QueryEvaluator queryEvaluator;
    private final RedisProducerGuard producerGuard;
    private final Map<String, CachedEstimate> cardinalityCache = new ConcurrentHashMap<>();
    private final Map<String, CachedHistogram> histogramCache = new ConcurrentHashMap<>();
    private final Map<String, CachedLearnedStats> learnedStatsCache = new ConcurrentHashMap<>();

    public RedisQueryIndexManager(
            JedisPooled jedis,
            EntityMetadata<T, ID> metadata,
            EntityCodec<T> codec,
            RedisKeyStrategy keyStrategy,
            EntityRegistry entityRegistry,
            QueryIndexConfig config,
            RelationConfig relationConfig,
            QueryEvaluator queryEvaluator,
            RedisProducerGuard producerGuard
    ) {
        this.jedis = jedis;
        this.metadata = metadata;
        this.codec = codec;
        this.keyStrategy = keyStrategy;
        this.entityRegistry = entityRegistry;
        this.config = config;
        this.relationConfig = relationConfig;
        this.queryEvaluator = queryEvaluator;
        this.producerGuard = producerGuard;
    }

    public void reindex(T entity) {
        try {
            reindexEntity(entity, true);
        } catch (RuntimeException exception) {
            markIndexFeaturesDegradedSafely();
        }
    }

    public QueryIndexRebuildResult rebuildFromEntityStore(boolean automatic, String note) {
        String namespace = metadata.redisNamespace();
        String lockKey = keyStrategy.indexRebuildLockKey(namespace);
        if (automatic && !acquireRebuildSlot(lockKey)) {
            return new QueryIndexRebuildResult(
                    metadata.entityName(),
                    namespace,
                    0L,
                    0L,
                    !indexFeaturesDegraded(),
                    true,
                    Instant.now(),
                    "Skipped automatic rebuild because cooldown is active"
            );
        }

        long clearedKeyCount = clearIndexKeys(lockKey);
        long rebuiltEntityCount = 0L;
        String cursor = "0";
        do {
            redis.clients.jedis.resps.ScanResult<String> scan = jedis.scan(cursor, new redis.clients.jedis.params.ScanParams()
                    .match(keyStrategy.entityPattern(namespace))
                    .count(Math.max(64, config.plannerStatisticsSampleSize() * 4)));
            for (String entityKey : scan.getResult()) {
                String id = entityKey.substring(entityKey.lastIndexOf(':') + 1);
                if (jedis.exists(keyStrategy.tombstoneKey(namespace, id))) {
                    continue;
                }
                String payload = jedis.get(entityKey);
                if (payload == null || payload.isBlank()) {
                    continue;
                }
                T entity = codec.fromRedisValue(payload);
                reindexEntity(entity, false);
                rebuiltEntityCount++;
            }
            cursor = scan.getCursor();
        } while (!"0".equals(cursor));
        jedis.del(keyStrategy.indexDegradedKey(namespace));
        invalidatePlannerStatistics();
        return new QueryIndexRebuildResult(
                metadata.entityName(),
                namespace,
                rebuiltEntityCount,
                clearedKeyCount,
                true,
                automatic,
                Instant.now(),
                note == null ? "" : note
        );
    }

    private void reindexEntity(T entity, boolean respectShedding) {
        ID id = metadata.idAccessor().apply(entity);
        Map<String, Object> columns = codec.toColumns(entity);
        if (respectShedding && shouldShedIndexWrites()) {
            removeById(id);
            markIndexFeaturesDegraded();
            return;
        }
        removeById(id);
        String idValue = String.valueOf(id);
        Map<String, String> metaValues = new LinkedHashMap<>();
        try (Pipeline pipeline = jedis.pipelined()) {
            pipeline.sadd(keyStrategy.indexAllKey(metadata.redisNamespace()), idValue);
            for (String column : metadata.columns()) {
                Object value = columns.get(column);
                String serialized = serializeValue(value);
                metaValues.put(column, serialized);
                if (config.exactIndexEnabled()) {
                    pipeline.sadd(keyStrategy.indexExactKey(metadata.redisNamespace(), column, encodeKeyPart(serialized)), idValue);
                }
                if (value instanceof String stringValue) {
                    indexStringValue(pipeline, idValue, column, stringValue);
                }
                if (config.rangeIndexEnabled()) {
                    Double score = RedisScoreSupport.toScore(metadata.columnTypes(), column, value);
                    if (score != null) {
                        pipeline.zadd(keyStrategy.indexSortKey(metadata.redisNamespace(), column), score, idValue);
                    }
                }
            }
            if (!metaValues.isEmpty()) {
                pipeline.hset(keyStrategy.indexMetaKey(metadata.redisNamespace(), id), metaValues);
            }
            pipeline.sync();
        }
    }

    public void removeById(Object id) {
        invalidatePlannerStatistics();
        String idValue = String.valueOf(id);
        String metaKey = keyStrategy.indexMetaKey(metadata.redisNamespace(), idValue);
        Map<String, String> metaValues = jedis.hgetAll(metaKey);
        try (Pipeline pipeline = jedis.pipelined()) {
            if (metaValues != null && !metaValues.isEmpty()) {
                for (Map.Entry<String, String> entry : metaValues.entrySet()) {
                    if (config.exactIndexEnabled()) {
                        pipeline.srem(
                                keyStrategy.indexExactKey(
                                        metadata.redisNamespace(),
                                        entry.getKey(),
                                        encodeKeyPart(entry.getValue())
                                ),
                                idValue
                        );
                    }
                    removeStringValueIndex(pipeline, idValue, entry.getKey(), decodeValue(entry.getValue()));
                    pipeline.zrem(keyStrategy.indexSortKey(metadata.redisNamespace(), entry.getKey()), idValue);
                }
                pipeline.del(metaKey);
            } else {
                for (String column : metadata.columns()) {
                    pipeline.zrem(keyStrategy.indexSortKey(metadata.redisNamespace(), column), idValue);
                }
            }
            pipeline.srem(keyStrategy.indexAllKey(metadata.redisNamespace()), idValue);
            pipeline.sync();
        }
    }

    public List<String> resolveCandidateIds(QuerySpec querySpec) {
        maybeRecoverDegradedIndexes(classifyQueryClasses(querySpec));
        if (shouldBypassIndexes(querySpec)) {
            return scanAllEntityIds();
        }
        return resolveIndexedAndResidualCandidates(querySpec);
    }

    public void warm(QuerySpec querySpec) {
        maybeRecoverDegradedIndexes(classifyQueryClasses(querySpec));
        if (shouldShedPlannerLearning(querySpec) || shouldBypassIndexes(querySpec)) {
            return;
        }
        if (!config.cacheWarmingEnabled()) {
            return;
        }
        try {
            warmNode(querySpec.rootGroup());
            for (QuerySort sort : querySpec.sorts()) {
                if (supportsScoreOrdering(sort.column())) {
                    histogramForColumn(sort.column());
                }
            }
        } catch (RuntimeException exception) {
            markIndexFeaturesDegradedSafely();
        }
    }

    public void observe(QuerySpec querySpec, long actualCardinality) {
        maybeRecoverDegradedIndexes(classifyQueryClasses(querySpec));
        if (shouldShedPlannerLearning(querySpec) || shouldBypassIndexes(querySpec)) {
            return;
        }
        if (!config.learnedStatisticsEnabled()) {
            return;
        }
        observeNode(querySpec.rootGroup(), actualCardinality);
    }

    public List<String> resolveSortedIds(QuerySpec querySpec) {
        if (!supportsSortedIndexScan(querySpec)) {
            return resolveCandidateIds(querySpec);
        }
        List<String> candidateScoreOrdered = resolveSortedIdsFromCandidateScores(querySpec);
        if (candidateScoreOrdered != null) {
            return candidateScoreOrdered;
        }
        if (canUseLazySortedScan(querySpec)) {
            return resolveSortedIdsLazily(querySpec);
        }

        QuerySort sort = querySpec.sorts().get(0);
        LinkedHashSet<String> eligibleIds = new LinkedHashSet<>(resolveIndexedAndResidualCandidates(querySpec));
        if (eligibleIds.isEmpty()) {
            return List.of();
        }

        int targetCount = querySpec.offset() + querySpec.limit();
        ArrayList<String> ordered = new ArrayList<>(Math.min(targetCount, eligibleIds.size()));
        int rank = 0;
        int chunkSize = Math.max(querySpec.limit() * 4, 64);
        String sortKey = keyStrategy.indexSortKey(metadata.redisNamespace(), sort.column());
        while (ordered.size() < targetCount) {
            List<String> batch = sort.direction() == com.reactor.cachedb.core.query.QuerySortDirection.DESC
                    ? jedis.zrevrange(sortKey, rank, rank + chunkSize - 1)
                    : jedis.zrange(sortKey, rank, rank + chunkSize - 1);
            if (batch == null || batch.isEmpty()) {
                break;
            }
            for (String id : batch) {
                if (eligibleIds.contains(id)) {
                    ordered.add(id);
                    if (ordered.size() >= targetCount) {
                        break;
                    }
                }
            }
            rank += chunkSize;
        }

        if (ordered.isEmpty()) {
            return List.of();
        }
        int fromIndex = Math.min(querySpec.offset(), ordered.size());
        int toIndex = Math.min(fromIndex + querySpec.limit(), ordered.size());
        return List.copyOf(ordered.subList(fromIndex, toIndex));
    }

    public List<String> resolvePrimarySortedIds(QuerySpec querySpec) {
        if (!shouldUsePrimarySortedScan(querySpec) || querySpec.sorts().isEmpty()) {
            return resolveCandidateIds(querySpec);
        }
        if (querySpec.sorts().size() == 1) {
            return resolveSortedIds(querySpec);
        }
        List<String> candidateScoreOrdered = resolvePrimarySortedIdsFromCandidateScores(querySpec);
        if (candidateScoreOrdered != null) {
            return candidateScoreOrdered;
        }
        if (canUseLazySortedScan(querySpec)) {
            return resolvePrimarySortedIdsLazily(querySpec);
        }

        QuerySort primarySort = querySpec.sorts().get(0);
        LinkedHashSet<String> eligibleIds = new LinkedHashSet<>(resolveIndexedAndResidualCandidates(querySpec));
        if (eligibleIds.isEmpty()) {
            return List.of();
        }

        int targetCount = querySpec.offset() + querySpec.limit();
        if (targetCount <= 0) {
            return List.of();
        }

        ArrayList<String> ordered = new ArrayList<>(Math.min(targetCount + Math.max(querySpec.limit(), 16), eligibleIds.size()));
        String sortKey = keyStrategy.indexSortKey(metadata.redisNamespace(), primarySort.column());
        int rank = 0;
        int chunkSize = Math.max(querySpec.limit() * 4, 64);
        Double boundaryScore = null;
        while (true) {
            List<Tuple> batch = primarySort.direction() == com.reactor.cachedb.core.query.QuerySortDirection.DESC
                    ? jedis.zrevrangeWithScores(sortKey, rank, rank + chunkSize - 1)
                    : jedis.zrangeWithScores(sortKey, rank, rank + chunkSize - 1);
            if (batch == null || batch.isEmpty()) {
                break;
            }

            boolean completedBoundaryCoverage = false;
            for (Tuple tuple : batch) {
                String id = tuple.getElement();
                if (!eligibleIds.contains(id)) {
                    continue;
                }

                double score = tuple.getScore();
                if (boundaryScore != null && Double.compare(score, boundaryScore) != 0 && ordered.size() >= targetCount) {
                    completedBoundaryCoverage = true;
                    break;
                }

                ordered.add(id);
                if (boundaryScore == null && ordered.size() >= targetCount) {
                    boundaryScore = score;
                }
            }

            if (completedBoundaryCoverage) {
                break;
            }
            rank += chunkSize;
        }

        return ordered.isEmpty() ? List.of() : List.copyOf(ordered);
    }

    private List<String> resolveSortedIdsLazily(QuerySpec querySpec) {
        QuerySort sort = querySpec.sorts().get(0);
        LazySortedScanPlan plan = buildLazySortedScanPlan(querySpec, sort);
        int targetCount = querySpec.offset() + querySpec.limit();
        if (targetCount <= 0) {
            return List.of();
        }

        ArrayList<String> ordered = new ArrayList<>(targetCount);
        int rank = 0;
        int chunkSize = Math.max(querySpec.limit() * 4, 64);
        String sortKey = keyStrategy.indexSortKey(metadata.redisNamespace(), sort.column());
        while (ordered.size() < targetCount) {
            List<Tuple> batch = sort.direction() == com.reactor.cachedb.core.query.QuerySortDirection.DESC
                    ? jedis.zrevrangeWithScores(sortKey, rank, rank + chunkSize - 1)
                    : jedis.zrangeWithScores(sortKey, rank, rank + chunkSize - 1);
            if (batch == null || batch.isEmpty()) {
                break;
            }
            BatchFilterResult filtered = filterOrderedBatch(batch, querySpec.rootGroup(), plan);
            ordered.addAll(filtered.matchedIds());
            if (filtered.stopScanning()) {
                break;
            }
            rank += chunkSize;
        }

        if (ordered.isEmpty()) {
            return List.of();
        }
        int fromIndex = Math.min(querySpec.offset(), ordered.size());
        int toIndex = Math.min(fromIndex + querySpec.limit(), ordered.size());
        return List.copyOf(ordered.subList(fromIndex, toIndex));
    }

    private List<String> resolvePrimarySortedIdsLazily(QuerySpec querySpec) {
        QuerySort primarySort = querySpec.sorts().get(0);
        LazySortedScanPlan plan = buildLazySortedScanPlan(querySpec, primarySort);
        int targetCount = querySpec.offset() + querySpec.limit();
        if (targetCount <= 0) {
            return List.of();
        }

        ArrayList<String> ordered = new ArrayList<>(targetCount + Math.max(querySpec.limit(), 16));
        String sortKey = keyStrategy.indexSortKey(metadata.redisNamespace(), primarySort.column());
        int rank = 0;
        int chunkSize = Math.max(querySpec.limit() * 4, 64);
        Double boundaryScore = null;
        while (true) {
            List<Tuple> batch = primarySort.direction() == com.reactor.cachedb.core.query.QuerySortDirection.DESC
                    ? jedis.zrevrangeWithScores(sortKey, rank, rank + chunkSize - 1)
                    : jedis.zrangeWithScores(sortKey, rank, rank + chunkSize - 1);
            if (batch == null || batch.isEmpty()) {
                break;
            }

            boolean completedBoundaryCoverage = false;
            BatchFilterResult filtered = filterOrderedBatch(batch, querySpec.rootGroup(), plan);
            List<String> matchedIds = filtered.matchedIds();
            Set<String> matchedIdSet = matchedIds.isEmpty()
                    ? Set.of()
                    : new LinkedHashSet<>(matchedIds);
            for (Tuple tuple : batch) {
                String id = tuple.getElement();
                if (!matchedIdSet.contains(id)) {
                    continue;
                }

                double score = tuple.getScore();
                if (boundaryScore != null && Double.compare(score, boundaryScore) != 0 && ordered.size() >= targetCount) {
                    completedBoundaryCoverage = true;
                    break;
                }

                ordered.add(id);
                if (boundaryScore == null && ordered.size() >= targetCount) {
                    boundaryScore = score;
                }
            }

            if (completedBoundaryCoverage) {
                break;
            }
            if (filtered.stopScanning()) {
                break;
            }
            rank += chunkSize;
        }
        return ordered.isEmpty() ? List.of() : List.copyOf(ordered);
    }

    private boolean canUseLazySortedScan(QuerySpec querySpec) {
        if (querySpec.sorts().isEmpty() || querySpec.filters().isEmpty()) {
            return false;
        }
        if (querySpec.rootGroup().operator() != QueryGroupOperator.AND) {
            return false;
        }
        QuerySort primarySort = querySpec.sorts().get(0);
        boolean hasPrimaryRangeFilter = false;
        for (QueryFilter filter : querySpec.filters()) {
            if (filter.column().contains(".")) {
                return false;
            }
            if (filter.column().equals(primarySort.column())
                    && isRangeOperator(filter.operator())
                    && supportsScoreOrdering(filter.column())) {
                hasPrimaryRangeFilter = true;
                continue;
            }
            if (filter.operator() == QueryOperator.EQ
                    || filter.operator() == QueryOperator.IN
                    || filter.operator() == QueryOperator.CONTAINS
                    || filter.operator() == QueryOperator.STARTS_WITH) {
                continue;
            }
            return false;
        }
        return hasPrimaryRangeFilter;
    }

    private List<String> resolveSortedIdsFromCandidateScores(QuerySpec querySpec) {
        if (!canUseCandidateScoreSort(querySpec)) {
            return null;
        }
        List<String> candidateIds = resolveIndexedAndResidualCandidates(querySpec);
        if (candidateIds.isEmpty()) {
            return List.of();
        }
        if (candidateIds.size() > maxCandidateScoreSortSize(querySpec)) {
            return null;
        }
        List<String> ordered = orderCandidateIdsByScores(candidateIds, querySpec.sorts());
        int fromIndex = Math.min(querySpec.offset(), ordered.size());
        int toIndex = Math.min(fromIndex + querySpec.limit(), ordered.size());
        return List.copyOf(ordered.subList(fromIndex, toIndex));
    }

    private List<String> resolvePrimarySortedIdsFromCandidateScores(QuerySpec querySpec) {
        if (!canUseCandidateScoreSort(querySpec)) {
            return null;
        }
        List<String> candidateIds = resolveIndexedAndResidualCandidates(querySpec);
        if (candidateIds.isEmpty()) {
            return List.of();
        }
        if (candidateIds.size() > maxCandidateScoreSortSize(querySpec)) {
            return null;
        }
        List<String> ordered = orderCandidateIdsByScores(candidateIds, querySpec.sorts());
        int targetCount = querySpec.offset() + querySpec.limit();
        int toIndex = Math.min(targetCount, ordered.size());
        return List.copyOf(ordered.subList(0, toIndex));
    }

    private boolean canUseCandidateScoreSort(QuerySpec querySpec) {
        if (querySpec.sorts().isEmpty()) {
            return false;
        }
        if (querySpec.rootGroup().operator() != QueryGroupOperator.AND) {
            return false;
        }
        if (querySpec.filters().stream().anyMatch(filter -> filter.column().contains("."))) {
            return false;
        }
        if (canUseLazySortedScan(querySpec)) {
            return false;
        }
        return querySpec.sorts().stream().allMatch(sort -> supportsScoreOrdering(sort.column()));
    }

    private int maxCandidateScoreSortSize(QuerySpec querySpec) {
        int targetCount = Math.max(1, querySpec.offset() + querySpec.limit());
        return Math.max(128, Math.min(512, targetCount * 8));
    }

    private List<String> orderCandidateIdsByScores(Collection<String> candidateIds, List<QuerySort> sorts) {
        ArrayList<String> orderedIds = new ArrayList<>(new LinkedHashSet<>(candidateIds));
        if (orderedIds.size() <= 1) {
            return List.copyOf(orderedIds);
        }

        LinkedHashMap<String, double[]> scoresById = loadCandidateScores(orderedIds, sorts);
        orderedIds.sort((left, right) -> compareCandidateScores(left, right, scoresById, sorts));
        return List.copyOf(orderedIds);
    }

    private LinkedHashMap<String, double[]> loadCandidateScores(List<String> orderedIds, List<QuerySort> sorts) {
        ArrayList<List<Response<Double>>> responses = new ArrayList<>(sorts.size());
        try (Pipeline pipeline = jedis.pipelined()) {
            for (QuerySort sort : sorts) {
                ArrayList<Response<Double>> scoreResponses = new ArrayList<>(orderedIds.size());
                String sortKey = keyStrategy.indexSortKey(metadata.redisNamespace(), sort.column());
                for (String id : orderedIds) {
                    scoreResponses.add(pipeline.zscore(sortKey, id));
                }
                responses.add(scoreResponses);
            }
            pipeline.sync();
        }

        LinkedHashMap<String, double[]> scoresById = new LinkedHashMap<>(orderedIds.size());
        for (String id : orderedIds) {
            scoresById.put(id, new double[sorts.size()]);
        }
        for (int sortIndex = 0; sortIndex < sorts.size(); sortIndex++) {
            QuerySort sort = sorts.get(sortIndex);
            List<Response<Double>> scoreResponses = responses.get(sortIndex);
            for (int idIndex = 0; idIndex < orderedIds.size(); idIndex++) {
                Double score = scoreResponses.get(idIndex).get();
                scoresById.get(orderedIds.get(idIndex))[sortIndex] = score == null ? missingSortScore(sort) : score;
            }
        }
        return scoresById;
    }

    private int compareCandidateScores(
            String left,
            String right,
            Map<String, double[]> scoresById,
            List<QuerySort> sorts
    ) {
        double[] leftScores = scoresById.get(left);
        double[] rightScores = scoresById.get(right);
        for (int index = 0; index < sorts.size(); index++) {
            int result = Double.compare(leftScores[index], rightScores[index]);
            if (sorts.get(index).direction() == com.reactor.cachedb.core.query.QuerySortDirection.DESC) {
                result = -result;
            }
            if (result != 0) {
                return result;
            }
        }
        return left.compareTo(right);
    }

    private double missingSortScore(QuerySort sort) {
        return sort.direction() == com.reactor.cachedb.core.query.QuerySortDirection.DESC
                ? Double.NEGATIVE_INFINITY
                : Double.POSITIVE_INFINITY;
    }

    private BatchFilterResult filterOrderedBatch(List<Tuple> batch, QueryNode filterNode, LazySortedScanPlan plan) {
        if (batch.isEmpty()) {
            return new BatchFilterResult(List.of(), false);
        }
        if (!plan.requiresDecode()) {
            ArrayList<String> matches = new ArrayList<>(batch.size());
            boolean stopScanning = false;
            for (Tuple tuple : batch) {
                double score = tuple.getScore();
                if (plan.scoreRange() != null && plan.scoreRange().canStop(score, plan.direction())) {
                    stopScanning = true;
                    break;
                }
                if (plan.scoreRange() != null && !plan.scoreRange().matches(score)) {
                    continue;
                }
                if (plan.membershipIds() != null && !plan.membershipIds().contains(tuple.getElement())) {
                    continue;
                }
                matches.add(tuple.getElement());
            }
            return new BatchFilterResult(List.copyOf(matches), stopScanning);
        }

        List<String> orderedIds = batch.stream().map(Tuple::getElement).toList();
        List<String> keys = orderedIds.stream()
                .map(id -> keyStrategy.entityKey(metadata.redisNamespace(), id))
                .toList();
        List<String> tombstoneKeys = orderedIds.stream()
                .map(id -> keyStrategy.tombstoneKey(metadata.redisNamespace(), id))
                .toList();
        List<List<String>> values = mgetBatchEntityAndTombstones(keys, tombstoneKeys);
        List<String> payloads = values.get(0);
        List<String> tombstones = values.get(1);
        ArrayList<String> matches = new ArrayList<>(orderedIds.size());
        boolean stopScanning = false;
        for (int index = 0; index < orderedIds.size(); index++) {
            Tuple tuple = batch.get(index);
            if (plan.scoreRange() != null && plan.scoreRange().canStop(tuple.getScore(), plan.direction())) {
                stopScanning = true;
                break;
            }
            String payload = payloads.get(index);
            if (payload == null || tombstones.get(index) != null) {
                continue;
            }
            T entity = codec.fromRedisValue(payload);
            Map<String, Object> columns = codec.toColumns(entity);
            if (queryEvaluator.matches(columns, filterNode)) {
                matches.add(orderedIds.get(index));
            }
        }
        return new BatchFilterResult(List.copyOf(matches), stopScanning);
    }

    private List<List<String>> mgetBatchEntityAndTombstones(List<String> keys, List<String> tombstoneKeys) {
        if (keys.isEmpty()) {
            return List.of(List.of(), List.of());
        }
        List<String> payloads = jedis.mget(keys.toArray(String[]::new));
        List<String> tombstones = jedis.mget(tombstoneKeys.toArray(String[]::new));
        return List.of(payloads, tombstones);
    }

    private LazySortedScanPlan buildLazySortedScanPlan(QuerySpec querySpec, QuerySort primarySort) {
        QueryGroup group = querySpec.rootGroup();
        if (!canUseLazySortedScan(querySpec) || group.operator() != QueryGroupOperator.AND) {
            return new LazySortedScanPlan(null, null, primarySort.direction(), true);
        }

        Set<String> membershipIds = null;
        ScoreRange scoreRange = null;
        for (QueryFilter filter : querySpec.filters()) {
            if (filter.column().contains(".")) {
                return new LazySortedScanPlan(null, null, primarySort.direction(), true);
            }
            if (filter.column().equals(primarySort.column()) && isRangeOperator(filter.operator()) && supportsScoreOrdering(filter.column())) {
                Double score = RedisScoreSupport.toScore(metadata.columnTypes(), filter.column(), filter.value());
                if (score == null) {
                    return new LazySortedScanPlan(null, null, primarySort.direction(), true);
                }
                scoreRange = mergeScoreRange(scoreRange, filter.operator(), score);
                continue;
            }
            if (filter.operator() == QueryOperator.EQ
                    || filter.operator() == QueryOperator.IN
                    || filter.operator() == QueryOperator.CONTAINS
                    || filter.operator() == QueryOperator.STARTS_WITH) {
                Set<String> matches = resolveIndexedMatches(filter);
                if (matches == null) {
                    return new LazySortedScanPlan(null, null, primarySort.direction(), true);
                }
                membershipIds = intersect(membershipIds, matches);
                continue;
            }
            return new LazySortedScanPlan(null, null, primarySort.direction(), true);
        }
        return new LazySortedScanPlan(membershipIds, scoreRange, primarySort.direction(), false);
    }

    private ScoreRange mergeScoreRange(ScoreRange current, QueryOperator operator, double score) {
        ScoreRange base = current == null
                ? new ScoreRange(Double.NEGATIVE_INFINITY, true, Double.POSITIVE_INFINITY, true)
                : current;
        return switch (operator) {
            case GT -> new ScoreRange(Math.max(base.min(), score), false, base.max(), base.maxInclusive());
            case GTE -> new ScoreRange(Math.max(base.min(), score), base.min() == score ? base.minInclusive() : true, base.max(), base.maxInclusive());
            case LT -> new ScoreRange(base.min(), base.minInclusive(), Math.min(base.max(), score), false);
            case LTE -> new ScoreRange(base.min(), base.minInclusive(), Math.min(base.max(), score), base.max() == score ? base.maxInclusive() : true);
            default -> base;
        };
    }

    public QueryExplainPlan explain(QuerySpec querySpec) {
        maybeRecoverDegradedIndexes(classifyQueryClasses(querySpec));
        List<QueryExplainRelationState> relationStates = collectRelationStates(querySpec);
        List<String> warnings = collectExplainWarnings(relationStates);
        if (shouldBypassIndexes(querySpec)) {
            List<String> candidates = scanAllEntityIds();
            List<QueryExplainStep> steps = List.of(new QueryExplainStep(
                    "FILTER",
                    querySpec.rootGroup().nodes().isEmpty() ? "<none>" : describeNode(querySpec.rootGroup()),
                    "DEGRADED_FULL_SCAN",
                    false,
                    candidates.size(),
                    Math.max(1, candidates.size()),
                    "Query index features are shed or marked degraded; repository falls back to entity key scan and residual evaluation"
            ));
            return new QueryExplainPlan(
                    metadata.entityName(),
                    querySpec.filters().size(),
                    querySpec.sorts().size(),
                    querySpec.offset(),
                    querySpec.limit(),
                    false,
                    candidates.size(),
                    Math.max(1, candidates.size() + querySpec.sorts().size()),
                    "DEGRADED_FULL_SCAN",
                    querySpec.sorts().isEmpty() ? "NONE" : (querySpec.sorts().size() > 1 ? "IN_MEMORY_MULTI_SORT" : "IN_MEMORY_SORT"),
                    0,
                    List.copyOf(querySpec.fetchPlan().includes()),
                    steps,
                    relationStates,
                    warnings
            );
        }
        warm(querySpec);
        List<QueryExplainStep> steps = new ArrayList<>();
        Resolution resolution = resolveIndexedCandidates(querySpec.rootGroup());
        boolean fullyIndexed = fullyIndexed(querySpec.rootGroup());
        Set<String> candidates = resolution.candidates();
        SortPlan sortPlan = chooseSortPlan(querySpec);

        if (querySpec.rootGroup().nodes().isEmpty()) {
            steps.add(new QueryExplainStep(
                    "FILTER",
                    "<none>",
                    "FULL_SET_SCAN",
                    false,
                    Math.toIntExact(jedis.scard(keyStrategy.indexAllKey(metadata.redisNamespace()))),
                    Math.toIntExact(jedis.scard(keyStrategy.indexAllKey(metadata.redisNamespace()))),
                    "No filters were supplied; candidate set starts from the full entity index"
            ));
            fullyIndexed = false;
        }

        appendExplainSteps(querySpec.rootGroup(), steps, candidates);

        if (!querySpec.sorts().isEmpty()) {
            steps.add(new QueryExplainStep(
                    "SORT",
                    querySpec.sorts().stream().map(this::describeSort).collect(java.util.stream.Collectors.joining(", ")),
                    sortPlan.strategy(),
                    sortPlan.usesSortedIndex(),
                    candidates == null ? 0 : candidates.size(),
                    sortPlan.estimatedCost(),
                    sortPlan.usesSortedIndex()
                            ? "Redis sorted index scan will page through the sort zset instead of sorting materialized entities"
                            : querySpec.sorts().size() > 1
                            ? "Multiple sort keys require an in-memory comparator after candidate materialization"
                            : "Current implementation materializes matched entities and sorts them in memory"
            ));
        }

        if (!querySpec.fetchPlan().includes().isEmpty()) {
            for (String relationName : querySpec.fetchPlan().includes()) {
                QueryExplainRelationState relationState = describeFetchRelationState(relationName, querySpec.fetchPlan());
                steps.add(new QueryExplainStep(
                        "FETCH",
                        relationState.relationName(),
                        relationState.status(),
                        relationState.indexed(),
                        candidates == null ? 0 : candidates.size(),
                        Math.max(1, candidates == null ? 0 : candidates.size()),
                        relationState.detail()
                ));
            }
        }

        List<String> resolvedCandidates = resolveCandidateIds(querySpec);
        return new QueryExplainPlan(
                metadata.entityName(),
                querySpec.filters().size(),
                querySpec.sorts().size(),
                querySpec.offset(),
                querySpec.limit(),
                fullyIndexed,
                resolvedCandidates.size(),
                estimatePlanCost(querySpec, resolvedCandidates.size(), sortPlan),
                plannerStrategy(querySpec, sortPlan, fullyIndexed),
                querySpec.sorts().isEmpty() ? "NONE" : sortPlan.strategy(),
                sortablePrefixLength(querySpec.sorts()),
                List.copyOf(querySpec.fetchPlan().includes()),
                List.copyOf(steps),
                relationStates,
                warnings
        );
    }

    public boolean fullyIndexed(QueryFilter filter) {
        if (shouldBypassIndexes()) {
            return false;
        }
        if (filter.column().contains(".")) {
            return metadata.relations().stream().anyMatch(candidate -> candidate.name().equals(filter.column().split("\\.", 2)[0]));
        }
        return switch (filter.operator()) {
            case EQ, IN -> config.exactIndexEnabled();
            case GT, GTE, LT, LTE -> config.rangeIndexEnabled();
            case NE -> config.exactIndexEnabled();
            case CONTAINS -> config.textIndexEnabled();
            case STARTS_WITH -> config.prefixIndexEnabled();
        };
    }

    public boolean fullyIndexed(QueryNode node) {
        if (shouldBypassIndexes()) {
            return false;
        }
        if (node instanceof QueryFilter filter) {
            return fullyIndexed(filter);
        }
        QueryGroup group = (QueryGroup) node;
        return group.nodes().stream().allMatch(this::fullyIndexed);
    }

    public boolean supportsSortedIndexScan(QuerySpec querySpec) {
        maybeRecoverDegradedIndexes(classifyQueryClasses(querySpec));
        if (shouldBypassIndexes(querySpec)) {
            return false;
        }
        SortPlan sortPlan = chooseSortPlan(querySpec);
        return sortPlan.usesSortedIndex() && querySpec.sorts().size() == 1;
    }

    public boolean shouldUsePrimarySortedScan(QuerySpec querySpec) {
        SortPlan sortPlan = chooseSortPlan(querySpec);
        return sortPlan.usesSortedIndex();
    }

    public boolean resolvesCompleteSortOrder(QuerySpec querySpec, int candidateCount) {
        return canUseCandidateScoreSort(querySpec) && candidateCount <= maxCandidateScoreSortSize(querySpec);
    }

    public SortPlan chooseSortPlan(QuerySpec querySpec) {
        maybeRecoverDegradedIndexes(classifyQueryClasses(querySpec));
        if (querySpec.sorts().isEmpty()) {
            return new SortPlan("NONE", 0, false);
        }
        if (shouldBypassIndexes(querySpec)) {
            return new SortPlan(querySpec.sorts().size() > 1 ? "IN_MEMORY_MULTI_SORT" : "IN_MEMORY_SORT", estimateSortCost(querySpec), false);
        }
        int sortablePrefixLength = sortablePrefixLength(querySpec.sorts());
        if (sortablePrefixLength == 0) {
            return new SortPlan(querySpec.sorts().size() > 1 ? "IN_MEMORY_MULTI_SORT" : "IN_MEMORY_SORT", estimateSortCost(querySpec), false);
        }
        if (querySpec.sorts().size() == 1) {
            return new SortPlan("SORTED_INDEX_SCAN", estimateSortCost(querySpec) / 2L + 1L, true);
        }
        return new SortPlan("PRIMARY_SORT_INDEX_WITH_TIEBREAK", estimateSortCost(querySpec), true);
    }

    public QueryCardinalityEstimate estimate(QueryFilter filter) {
        return estimateFilter(filter);
    }

    private int sortablePrefixLength(List<QuerySort> sorts) {
        if (sorts.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (QuerySort sort : sorts) {
            if (!metadata.columns().contains(sort.column()) || !config.rangeIndexEnabled() || !supportsScoreOrdering(sort.column())) {
                break;
            }
            count++;
        }
        return count;
    }

    private void warmNode(QueryNode node) {
        if (node instanceof QueryFilter filter) {
            estimateFilter(filter);
            if (isRangeOperator(filter.operator()) && !filter.column().contains(".")) {
                histogramForColumn(filter.column());
            }
            return;
        }
        QueryGroup group = (QueryGroup) node;
        for (QueryNode child : group.nodes()) {
            warmNode(child);
        }
    }

    private void observeNode(QueryNode node, long actualCardinality) {
        storeLearnedStats(describeNode(node), actualCardinality);
    }

    private Set<String> resolveIndexedMatches(QueryFilter filter) {
        if (filter.column().contains(".")) {
            return resolveRelationMatches(filter);
        }
        return switch (filter.operator()) {
            case EQ -> membersForExact(filter.column(), filter.value());
            case IN -> membersForIn(filter.column(), filter.values());
            case GT, GTE, LT, LTE -> membersForRange(filter);
            case NE -> subtractAll(membersForExact(filter.column(), filter.value()));
            case CONTAINS -> membersForContains(filter.column(), filter.value());
            case STARTS_WITH -> membersForPrefix(filter.column(), filter.value());
        };
    }

    private List<String> resolveIndexedAndResidualCandidates(QuerySpec querySpec) {
        Set<String> candidates = resolveIndexedCandidates(querySpec.rootGroup()).candidates();
        if (candidates == null) {
            candidates = new LinkedHashSet<>(jedis.smembers(keyStrategy.indexAllKey(metadata.redisNamespace())));
        }

        if (!fullyIndexed(querySpec.rootGroup()) && !candidates.isEmpty()) {
            candidates = filterResidualCandidates(candidates, querySpec.rootGroup());
        }
        return new ArrayList<>(candidates);
    }

    private Resolution resolveIndexedCandidates(QueryNode node) {
        if (node instanceof QueryFilter filter) {
            return new Resolution(resolveIndexedMatches(filter), fullyIndexed(filter));
        }

        QueryGroup group = (QueryGroup) node;
        if (group.nodes().isEmpty()) {
            return new Resolution(null, false);
        }

        return switch (group.operator()) {
            case AND -> resolveAndGroup(group);
            case OR -> resolveOrGroup(group);
        };
    }

    private Resolution resolveAndGroup(QueryGroup group) {
        boolean fullyIndexed = true;
        ArrayList<ResolutionWithNode> indexedChildren = new ArrayList<>();
        boolean hasIndexedChild = false;
        ArrayList<QueryNode> orderedChildren = new ArrayList<>(group.nodes());
        orderedChildren.sort(java.util.Comparator.comparingLong(this::estimateNodeCardinality));
        for (QueryNode child : orderedChildren) {
            Resolution resolution = resolveIndexedCandidates(child);
            fullyIndexed &= resolution.fullyIndexed();
            if (resolution.candidates() == null) {
                continue;
            }
            hasIndexedChild = true;
            indexedChildren.add(new ResolutionWithNode(child, resolution));
        }

        Set<String> candidates = null;
        for (ResolutionWithNode child : indexedChildren) {
            candidates = intersect(candidates, child.resolution().candidates());
            if (candidates.isEmpty()) {
                return new Resolution(candidates, fullyIndexed);
            }
        }
        return new Resolution(hasIndexedChild ? candidates : null, fullyIndexed);
    }

    private Resolution resolveOrGroup(QueryGroup group) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        boolean fullyIndexed = true;
        for (QueryNode child : group.nodes()) {
            Resolution resolution = resolveIndexedCandidates(child);
            fullyIndexed &= resolution.fullyIndexed();
            if (resolution.candidates() == null) {
                return new Resolution(null, false);
            }
            candidates.addAll(resolution.candidates());
        }
        return new Resolution(candidates, fullyIndexed);
    }

    private String describeFilter(QueryFilter filter) {
        if (filter.operator() == QueryOperator.IN) {
            return filter.column() + " IN " + filter.values();
        }
        return filter.column() + " " + filter.operator().name() + " " + filter.value();
    }

    private String filterStrategy(QueryFilter filter, boolean indexed) {
        if (filter.column().contains(".")) {
            return indexed ? "RELATION_INDEX_TRAVERSAL" : "RELATION_RESIDUAL_SCAN";
        }
        return switch (filter.operator()) {
            case EQ, IN, NE -> indexed ? "EXACT_INDEX" : "RESIDUAL_SCAN";
            case GT, GTE, LT, LTE -> indexed ? "RANGE_INDEX" : "RESIDUAL_SCAN";
            case CONTAINS -> indexed ? "TOKEN_INDEX" : "RESIDUAL_SCAN";
            case STARTS_WITH -> indexed ? "PREFIX_INDEX" : "RESIDUAL_SCAN";
        };
    }

    private String filterDetail(QueryFilter filter, Set<String> indexedMatches) {
        if (indexedMatches == null) {
            return "No dedicated Redis index path is available; filter will be evaluated in memory";
        }
        QueryCardinalityEstimate estimate = estimateFilter(filter);
        if (filter.column().contains(".")) {
            int hopCount = filter.column().split("\\.").length - 1;
            return "Relation filter delegates to target entity indexes before mapping owner ids, selectivity="
                    + estimate.selectivityRatio()
                    + ", sample=" + estimate.sampledCardinality()
                    + ", hops=" + Math.max(1, hopCount);
        }
        if (isRangeOperator(filter.operator())) {
            if (!estimate.histogram().isEmpty()) {
                return "Redis range estimate " + estimate.estimatedCardinality()
                        + " selectivity=" + estimate.selectivityRatio()
                        + " sample=" + estimate.sampledCardinality()
                        + " with histogram buckets " + estimate.histogram();
            }
        }
        return "Redis index produced " + indexedMatches.size()
                + " direct candidate ids, selectivity=" + estimate.selectivityRatio()
                + ", sample=" + estimate.sampledCardinality();
    }

    private String describeSort(QuerySort sort) {
        return sort.column() + " " + sort.direction().name();
    }

    private String sortStrategy(List<QuerySort> sorts) {
        boolean indexedSorts = sorts.stream()
                .allMatch(sort -> metadata.columns().contains(sort.column()) && config.rangeIndexEnabled() && supportsScoreOrdering(sort.column()));
        return indexedSorts && sorts.size() == 1 ? "SORTED_INDEX_SCAN" : indexedSorts ? "IN_MEMORY_SORT_WITH_RANGE_INDEX_AVAILABLE" : "IN_MEMORY_SORT";
    }

    private Set<String> membersForExact(String column, Object value) {
        if (!config.exactIndexEnabled()) {
            return null;
        }
        return new LinkedHashSet<>(
                jedis.smembers(keyStrategy.indexExactKey(
                        metadata.redisNamespace(),
                        column,
                        encodeKeyPart(serializeValue(value))
                ))
        );
    }

    private Set<String> membersForIn(String column, List<Object> values) {
        LinkedHashSet<String> matches = new LinkedHashSet<>();
        for (Object value : values) {
            matches.addAll(membersForExact(column, value));
        }
        return matches;
    }

    private Set<String> membersForRange(QueryFilter filter) {
        if (!config.rangeIndexEnabled()) {
            return null;
        }
        Double score = RedisScoreSupport.toScore(metadata.columnTypes(), filter.column(), filter.value());
        if (score == null) {
            return null;
        }

        String min = "-inf";
        String max = "+inf";
        switch (filter.operator()) {
            case GT -> min = "(" + score;
            case GTE -> min = String.valueOf(score);
            case LT -> max = "(" + score;
            case LTE -> max = String.valueOf(score);
            default -> {
                return null;
            }
        }
        return new LinkedHashSet<>(
                jedis.zrangeByScore(keyStrategy.indexSortKey(metadata.redisNamespace(), filter.column()), min, max)
        );
    }

    private Set<String> membersForPrefix(String column, Object value) {
        if (!config.prefixIndexEnabled() || value == null) {
            return null;
        }
        return new LinkedHashSet<>(
                jedis.smembers(keyStrategy.indexPrefixKey(
                        metadata.redisNamespace(),
                        column,
                        encodeKeyPart(normalizeText(String.valueOf(value)))
                ))
        );
    }

    private Set<String> membersForContains(String column, Object value) {
        if (!config.textIndexEnabled() || value == null) {
            return null;
        }
        List<String> tokens = tokenize(String.valueOf(value));
        if (tokens.isEmpty()) {
            return null;
        }

        LinkedHashSet<String> candidates = null;
        for (String token : tokens) {
            LinkedHashSet<String> tokenMatches = new LinkedHashSet<>(
                    jedis.smembers(keyStrategy.indexTokenKey(
                            metadata.redisNamespace(),
                            column,
                            encodeKeyPart(token)
                    ))
            );
            candidates = candidates == null ? tokenMatches : new LinkedHashSet<>(intersect(candidates, tokenMatches));
            if (candidates.isEmpty()) {
                return candidates;
            }
        }
        return candidates;
    }

    private LinkedHashSet<String> filterResidualCandidates(Collection<String> candidateIds, QueryNode residualNode) {
        List<String> orderedIds = new ArrayList<>(candidateIds);
        List<String> keys = orderedIds.stream()
                .map(id -> keyStrategy.entityKey(metadata.redisNamespace(), id))
                .toList();
        List<String> payloads = jedis.mget(keys.toArray(String[]::new));
        LinkedHashSet<String> matches = new LinkedHashSet<>();
        int index = 0;
        for (String payload : payloads) {
            if (payload == null) {
                index++;
                continue;
            }
            T entity = codec.fromRedisValue(payload);
            Map<String, Object> columns = codec.toColumns(entity);
            if (queryEvaluator.matches(columns, residualNode)) {
                matches.add(orderedIds.get(index));
            }
            index++;
        }
        return matches;
    }

    private void appendExplainSteps(QueryNode node, List<QueryExplainStep> steps, Set<String> currentCandidates) {
        if (node instanceof QueryFilter filter) {
            Set<String> indexedMatches = resolveIndexedMatches(filter);
            int candidateCount = indexedMatches == null
                    ? currentCandidates == null ? Math.toIntExact(jedis.scard(keyStrategy.indexAllKey(metadata.redisNamespace()))) : currentCandidates.size()
                    : currentCandidates == null ? indexedMatches.size() : intersect(new LinkedHashSet<>(currentCandidates), new LinkedHashSet<>(indexedMatches)).size();
            steps.add(new QueryExplainStep(
                    "FILTER",
                    describeFilter(filter),
                    filterStrategy(filter, indexedMatches != null),
                    fullyIndexed(filter),
                    candidateCount,
                    estimateFilterCost(filter, indexedMatches),
                    filterDetail(filter, indexedMatches)
            ));
            return;
        }

        QueryGroup group = (QueryGroup) node;
        steps.add(new QueryExplainStep(
                "GROUP",
            group.nodes().stream().map(this::describeNode).collect(Collectors.joining(" " + group.operator().name() + " ")),
            group.operator().name() + "_GROUP",
            fullyIndexed(group),
            currentCandidates == null ? 0 : currentCandidates.size(),
            Math.max(1, currentCandidates == null ? 0 : currentCandidates.size()),
            "Compound query group with " + group.nodes().size() + " child nodes"
        ));
        for (QueryNode child : group.nodes()) {
            appendExplainSteps(child, steps, currentCandidates);
        }
    }

    private String describeNode(QueryNode node) {
        if (node instanceof QueryFilter filter) {
            return describeFilter(filter);
        }
        QueryGroup group = (QueryGroup) node;
        return "(" + group.nodes().stream().map(this::describeNode).collect(Collectors.joining(" " + group.operator().name() + " ")) + ")";
    }

    private List<QueryExplainRelationState> collectRelationStates(QuerySpec querySpec) {
        ArrayList<QueryExplainRelationState> states = new ArrayList<>();
        for (QueryFilter filter : querySpec.filters()) {
            QueryExplainRelationState state = describeRelationFilterState(filter);
            if (state != null) {
                states.add(state);
            }
        }
        for (String relationName : querySpec.fetchPlan().includes()) {
            states.add(describeFetchRelationState(relationName, querySpec.fetchPlan()));
        }
        return List.copyOf(states);
    }

    private List<String> collectExplainWarnings(List<QueryExplainRelationState> relationStates) {
        ArrayList<String> warnings = new ArrayList<>();
        for (QueryExplainRelationState relationState : relationStates) {
            if (relationState == null) {
                continue;
            }
            if (!"RESOLVED".equals(relationState.status()) && !"NO_MATCHES".equals(relationState.status()) && !"BATCH_PRELOAD".equals(relationState.status())) {
                warnings.add(relationState.usage() + ":" + relationState.relationName() + ":" + relationState.status());
            }
        }
        return List.copyOf(warnings);
    }

    private Set<String> resolveRelationMatches(QueryFilter filter) {
        String[] parts = filter.column().split("\\.", 2);
        if (parts.length != 2) {
            return null;
        }

        RelationDefinition relation = metadata.relations().stream()
                .filter(candidate -> candidate.name().equals(parts[0]))
                .findFirst()
                .orElse(null);
        if (relation == null) {
            return null;
        }

        EntityBinding<?, ?> targetBinding = entityRegistry.find(relation.targetEntity()).orElse(null);
        if (targetBinding == null) {
            return null;
        }

        QueryFilter delegatedFilter = new QueryFilter(parts[1], filter.operator(), filter.value(), filter.values());
        Set<String> targetIds = resolveTargetIds(targetBinding, delegatedFilter);
        if (targetIds == null || targetIds.isEmpty()) {
            return new LinkedHashSet<>();
        }
        return mapTargetIdsToSourceIds(targetBinding, relation, targetIds);
    }

    private QueryExplainRelationState describeRelationFilterState(QueryFilter filter) {
        if (!filter.column().contains(".")) {
            return null;
        }
        String[] parts = filter.column().split("\\.", 2);
        if (parts.length != 2) {
            return new QueryExplainRelationState(
                    filter.column(),
                    "FILTER",
                    "INVALID_PATH",
                    "UNKNOWN",
                    "UNKNOWN",
                    "UNKNOWN",
                    false,
                    false,
                    0,
                    "Relation filter path must be in the form relation.column"
            );
        }

        RelationDefinition relation = metadata.relations().stream()
                .filter(candidate -> candidate.name().equals(parts[0]))
                .findFirst()
                .orElse(null);
        if (relation == null) {
            return new QueryExplainRelationState(
                    parts[0],
                    "FILTER",
                    "UNKNOWN_RELATION",
                    "UNKNOWN",
                    "UNKNOWN",
                    "UNKNOWN",
                    false,
                    false,
                    0,
                    "No declared relation matches filter path " + filter.column()
            );
        }

        EntityBinding<?, ?> targetBinding = entityRegistry.find(relation.targetEntity()).orElse(null);
        if (targetBinding == null) {
            return new QueryExplainRelationState(
                    relation.name(),
                    "FILTER",
                    "TARGET_BINDING_MISSING",
                    relation.kind().name(),
                    relation.targetEntity(),
                    relation.mappedBy(),
                    relation.batchLoadOnly(),
                    false,
                    0,
                    "Target entity binding " + relation.targetEntity() + " is not registered"
            );
        }

        String mappedByColumn = relationMappedByColumn(targetBinding, relation);
        if (mappedByColumn == null) {
            return new QueryExplainRelationState(
                    relation.name(),
                    "FILTER",
                    "MAPPED_BY_UNRESOLVED",
                    relation.kind().name(),
                    relation.targetEntity(),
                    relation.mappedBy(),
                    relation.batchLoadOnly(),
                    false,
                    0,
                    "Target metadata for " + relation.targetEntity() + " does not expose mappedBy column " + relation.mappedBy()
            );
        }

        QueryFilter delegatedFilter = new QueryFilter(parts[1], filter.operator(), filter.value(), filter.values());
        Set<String> targetIds = resolveTargetIds(targetBinding, delegatedFilter);
        Set<String> sourceIds = targetIds == null || targetIds.isEmpty()
                ? new LinkedHashSet<>()
                : mapTargetIdsToSourceIds(targetBinding, relation, targetIds);
        String mappedByDetail = mappedByColumn.equals(relation.mappedBy())
                ? mappedByColumn
                : relation.mappedBy() + " -> " + mappedByColumn;
        return new QueryExplainRelationState(
                relation.name(),
                "FILTER",
                sourceIds.isEmpty() ? "NO_MATCHES" : "RESOLVED",
                relation.kind().name(),
                relation.targetEntity(),
                relation.mappedBy(),
                relation.batchLoadOnly(),
                true,
                sourceIds.size(),
                "Delegated filter " + parts[1] + " on " + relation.targetEntity() + ", mappedBy=" + mappedByDetail + ", targetMatches=" + (targetIds == null ? 0 : targetIds.size())
        );
    }

    private QueryExplainRelationState describeFetchRelationState(String relationName, FetchPlan fetchPlan) {
        int requestedDepth = FetchPlan.relationDepth(relationName);
        if (relationConfigMaxFetchDepthExceeded(requestedDepth)) {
            return new QueryExplainRelationState(
                    relationName,
                    "FETCH",
                    "FETCH_DEPTH_EXCEEDED",
                    "UNKNOWN",
                    "UNKNOWN",
                    "UNKNOWN",
                    false,
                    false,
                    0,
                    "Fetch path depth " + requestedDepth + " exceeds configured maxFetchDepth=" + configuredRelationMaxDepth()
            );
        }
        String topLevelRelationName = topLevelRelationName(relationName);
        RelationDefinition relation = metadata.relations().stream()
                .filter(candidate -> candidate.name().equals(topLevelRelationName))
                .findFirst()
                .orElse(null);
        if (relation == null) {
            return new QueryExplainRelationState(
                    relationName,
                    "FETCH",
                    "UNKNOWN_RELATION",
                    "UNKNOWN",
                    "UNKNOWN",
                    "UNKNOWN",
                    false,
                    false,
                    0,
                "Requested fetch relation is not declared in metadata"
            );
        }
        EntityBinding<?, ?> sourceBinding = entityRegistry.find(metadata.entityName()).orElse(null);
        if (sourceBinding == null) {
            return new QueryExplainRelationState(
                    relationName,
                    "FETCH",
                    "FETCH_PATH_UNVERIFIED",
                    relation.kind().name(),
                    relation.targetEntity(),
                    relation.mappedBy(),
                    relation.batchLoadOnly(),
                    false,
                    0,
                    "Source entity binding " + metadata.entityName() + " is not registered, so fetch path cannot be verified"
            );
        }
        if (sourceBinding.relationBatchLoader() == null || sourceBinding.relationBatchLoader() instanceof NoOpRelationBatchLoader) {
            return new QueryExplainRelationState(
                    relationName,
                    "FETCH",
                    "FETCH_LOADER_MISSING",
                    relation.kind().name(),
                    relation.targetEntity(),
                    relation.mappedBy(),
                    relation.batchLoadOnly(),
                    false,
                    0,
                    "Fetch plan requests " + relation.name() + " but no concrete RelationBatchLoader is registered for " + metadata.entityName()
            );
        }
        EntityBinding<?, ?> targetBinding = entityRegistry.find(relation.targetEntity()).orElse(null);
        if (targetBinding == null) {
            return new QueryExplainRelationState(
                    relationName,
                    "FETCH",
                    "FETCH_PATH_UNVERIFIED",
                    relation.kind().name(),
                    relation.targetEntity(),
                    relation.mappedBy(),
                    relation.batchLoadOnly(),
                    false,
                    0,
                    "A batch loader is registered, but target entity binding " + relation.targetEntity() + " is missing so mappedBy validation could not be confirmed"
            );
        }
        String mappedByColumn = relationMappedByColumn(targetBinding, relation);
        if (mappedByColumn == null) {
            return new QueryExplainRelationState(
                    relationName,
                    "FETCH",
                    "FETCH_PATH_UNVERIFIED",
                    relation.kind().name(),
                    relation.targetEntity(),
                    relation.mappedBy(),
                    relation.batchLoadOnly(),
                    false,
                    0,
                    "A batch loader is registered, but target metadata for " + relation.targetEntity() + " does not expose mappedBy column " + relation.mappedBy()
            );
        }
        return new QueryExplainRelationState(
                relationName,
                "FETCH",
                "BATCH_PRELOAD",
                relation.kind().name(),
                relation.targetEntity(),
                relation.mappedBy(),
                relation.batchLoadOnly(),
                true,
                0,
                "Fetch plan will use the registered batch loader path for " + relation.kind() + " via mappedBy=" + mappedByColumn
                        + (requestedDepth > 1 ? " (requested depth=" + requestedDepth + ")" : "")
        );
    }

    private boolean relationConfigMaxFetchDepthExceeded(int requestedDepth) {
        return requestedDepth > configuredRelationMaxDepth();
    }

    private int configuredRelationMaxDepth() {
        return Math.max(1, relationConfig.maxFetchDepth());
    }

    private String topLevelRelationName(String relationName) {
        int dotIndex = relationName.indexOf('.');
        return dotIndex < 0 ? relationName : relationName.substring(0, dotIndex);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Set<String> resolveTargetIds(EntityBinding<?, ?> targetBinding, QueryFilter delegatedFilter) {
        RedisQueryIndexManager targetManager = new RedisQueryIndexManager(
                jedis,
                targetBinding.metadata(),
                targetBinding.codec(),
                keyStrategy,
                entityRegistry,
                config,
                relationConfig,
                queryEvaluator,
                producerGuard
        );
        return new LinkedHashSet<>(targetManager.resolveCandidateIds(
                QuerySpec.builder().filter(delegatedFilter).limit(Integer.MAX_VALUE).build()
        ));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Set<String> mapTargetIdsToSourceIds(EntityBinding<?, ?> targetBinding, RelationDefinition relation, Collection<String> targetIds) {
        EntityBinding rawBinding = targetBinding;
        List<String> keys = targetIds.stream()
                .map(id -> keyStrategy.entityKey(rawBinding.metadata().redisNamespace(), id))
                .toList();
        List<String> payloads = jedis.mget(keys.toArray(String[]::new));
        LinkedHashSet<String> mappedIds = new LinkedHashSet<>();
        String mappedByColumn = relationMappedByColumn(targetBinding, relation);
        if (mappedByColumn == null) {
            return mappedIds;
        }

        for (String payload : payloads) {
            if (payload == null) {
                continue;
            }
            Object targetEntity = rawBinding.codec().fromRedisValue(payload);
            Map<String, Object> targetColumns = rawBinding.codec().toColumns(targetEntity);
            Object ownerId = targetColumns.get(mappedByColumn);
            if (ownerId != null) {
                mappedIds.add(String.valueOf(ownerId));
            }
        }
        return mappedIds;
    }

    private String relationMappedByColumn(EntityBinding<?, ?> targetBinding, RelationDefinition relation) {
        if (targetBinding.metadata().columns().contains(relation.mappedBy())) {
            return relation.mappedBy();
        }
        String snakeCase = toSnakeCase(relation.mappedBy());
        if (targetBinding.metadata().columns().contains(snakeCase)) {
            return snakeCase;
        }
        return null;
    }

    private Set<String> subtractAll(Set<String> excluded) {
        LinkedHashSet<String> allIds = new LinkedHashSet<>(jedis.smembers(keyStrategy.indexAllKey(metadata.redisNamespace())));
        allIds.removeAll(excluded);
        return allIds;
    }

    private Set<String> intersect(Set<String> left, Set<String> right) {
        if (left == null) {
            return new LinkedHashSet<>(right);
        }
        left.retainAll(right);
        return left;
    }

    private String serializeValue(Object value) {
        return value == null ? NULL_SENTINEL : String.valueOf(value);
    }

    private String decodeValue(String value) {
        return NULL_SENTINEL.equals(value) ? null : value;
    }

    private String encodeKeyPart(String value) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private void indexStringValue(Pipeline pipeline, String idValue, String column, String rawValue) {
        String normalized = normalizeText(rawValue);
        if (normalized.isBlank()) {
            return;
        }

        if (config.prefixIndexEnabled()) {
            int prefixLength = Math.min(config.prefixMaxLength(), normalized.length());
            for (int index = 1; index <= prefixLength; index++) {
                pipeline.sadd(
                        keyStrategy.indexPrefixKey(metadata.redisNamespace(), column, encodeKeyPart(normalized.substring(0, index))),
                        idValue
                );
            }
        }

        if (config.textIndexEnabled()) {
            for (String token : tokenize(rawValue)) {
                pipeline.sadd(
                        keyStrategy.indexTokenKey(metadata.redisNamespace(), column, encodeKeyPart(token)),
                        idValue
                );
            }
        }
    }

    private void removeStringValueIndex(Pipeline pipeline, String idValue, String column, String rawValue) {
        if (rawValue == null) {
            return;
        }
        String normalized = normalizeText(rawValue);
        if (config.prefixIndexEnabled() && !normalized.isBlank()) {
            int prefixLength = Math.min(config.prefixMaxLength(), normalized.length());
            for (int index = 1; index <= prefixLength; index++) {
                pipeline.srem(
                        keyStrategy.indexPrefixKey(metadata.redisNamespace(), column, encodeKeyPart(normalized.substring(0, index))),
                        idValue
                );
            }
        }
        if (config.textIndexEnabled()) {
            for (String token : tokenize(rawValue)) {
                pipeline.srem(
                        keyStrategy.indexTokenKey(metadata.redisNamespace(), column, encodeKeyPart(token)),
                        idValue
                );
            }
        }
    }

    private List<String> tokenize(String rawValue) {
        String normalized = normalizeText(rawValue);
        if (normalized.isBlank()) {
            return List.of();
        }

        String[] parts = normalized.split("[^\\p{IsAlphabetic}\\p{IsDigit}]+");
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        for (String part : parts) {
            if (part.length() < config.textTokenMinLength()) {
                continue;
            }
            String token = part.length() > config.textTokenMaxLength()
                    ? part.substring(0, config.textTokenMaxLength())
                    : part;
            tokens.add(token);
            if (tokens.size() >= config.textMaxTokensPerValue()) {
                break;
            }
        }
        return new ArrayList<>(tokens);
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private String toSnakeCase(String value) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isUpperCase(current) && index > 0) {
                builder.append('_');
            }
            builder.append(Character.toLowerCase(current));
        }
        return builder.toString();
    }

    private boolean supportsScoreOrdering(String column) {
        String declaredType = metadata.columnTypes().get(column);
        if (declaredType == null || declaredType.isBlank()) {
            return false;
        }
        return switch (declaredType) {
            case "int", "java.lang.Integer",
                 "long", "java.lang.Long",
                 "double", "java.lang.Double",
                 "float", "java.lang.Float",
                 "short", "java.lang.Short",
                 "byte", "java.lang.Byte",
                 "java.time.Instant",
                 "java.time.LocalDate",
                 "java.time.LocalDateTime",
                 "java.time.OffsetDateTime" -> true;
            default -> false;
        };
    }

    private QueryCardinalityEstimate estimateFilter(QueryFilter filter) {
        long fullSetCardinality = Math.max(1, jedis.scard(keyStrategy.indexAllKey(metadata.redisNamespace())));
        if (!config.plannerStatisticsEnabled()) {
            return new QueryCardinalityEstimate(describeFilter(filter), fullSetCardinality, fullSetCardinality, fullSetCardinality, 1.0d, List.of());
        }

        String cacheKey = metadata.redisNamespace() + "|" + describeFilter(filter);
        CachedEstimate cachedEstimate = cardinalityCache.get(cacheKey);
        long now = System.currentTimeMillis();
        if (cachedEstimate != null && now - cachedEstimate.createdAtEpochMillis() <= config.plannerStatisticsTtlMillis()) {
            return cachedEstimate.estimate();
        }

        if (config.plannerStatisticsPersisted()) {
            QueryCardinalityEstimate persisted = loadEstimateFromRedisSafely(filter);
            if (persisted != null) {
                cardinalityCache.put(cacheKey, new CachedEstimate(persisted, now));
                return persisted;
            }
        }

        QueryCardinalityEstimate estimate = computeEstimate(filter, fullSetCardinality);
        cardinalityCache.put(cacheKey, new CachedEstimate(estimate, now));
        if (config.plannerStatisticsPersisted()) {
            storeEstimateInRedisSafely(filter, estimate);
        }
        return estimate;
    }

    private QueryCardinalityEstimate computeEstimate(QueryFilter filter, long fullSetCardinality) {
        if (filter.column().contains(".")) {
            Set<String> relationMatches = resolveRelationMatches(filter);
            long estimatedCardinality = relationMatches == null ? fullSetCardinality : relationMatches.size();
            estimatedCardinality = applyLearnedAdjustment(describeFilter(filter), estimatedCardinality, fullSetCardinality);
            double selectivityRatio = Math.min(1.0d, Math.max(0.0d, fullSetCardinality == 0 ? 0.0d : (double) estimatedCardinality / fullSetCardinality));
            return new QueryCardinalityEstimate(
                    describeFilter(filter),
                    estimatedCardinality,
                    Math.max(1L, Math.round(Math.max(1.0d, estimatedCardinality * Math.max(0.05d, selectivityRatio)))),
                    estimatedCardinality,
                    selectivityRatio,
                    List.of()
            );
        }
        long estimatedCardinality = switch (filter.operator()) {
            case EQ -> cardinalityOfSet(keyStrategy.indexExactKey(metadata.redisNamespace(), filter.column(), encodeKeyPart(serializeValue(filter.value()))));
            case IN -> filter.values().stream()
                    .mapToLong(value -> cardinalityOfSet(keyStrategy.indexExactKey(metadata.redisNamespace(), filter.column(), encodeKeyPart(serializeValue(value)))))
                    .sum();
            case GT, GTE, LT, LTE -> cardinalityOfRange(filter);
            case NE -> Math.max(0L, fullSetCardinality - cardinalityOfSet(keyStrategy.indexExactKey(metadata.redisNamespace(), filter.column(), encodeKeyPart(serializeValue(filter.value())))));
            case CONTAINS -> cardinalityOfContains(filter);
            case STARTS_WITH -> cardinalityOfSet(keyStrategy.indexPrefixKey(metadata.redisNamespace(), filter.column(), encodeKeyPart(normalizeText(String.valueOf(filter.value())))));
        };
        estimatedCardinality = Math.min(fullSetCardinality, Math.max(0L, estimatedCardinality));
        estimatedCardinality = applyLearnedAdjustment(describeFilter(filter), estimatedCardinality, fullSetCardinality);

        List<QueryHistogramBucket> histogram = isRangeOperator(filter.operator()) ? histogramForColumn(filter.column()) : List.of();
        long sampledCardinality = determineSampledCardinality(filter, fullSetCardinality);
        double selectivityRatio = Math.min(1.0d, Math.max(0.0d, fullSetCardinality == 0 ? 0.0d : (double) estimatedCardinality / fullSetCardinality));
        return new QueryCardinalityEstimate(
                describeFilter(filter),
                estimatedCardinality,
                Math.max(1L, Math.round(Math.max(1.0d, estimatedCardinality * Math.max(0.05d, selectivityRatio)))),
                sampledCardinality,
                selectivityRatio,
                histogram
        );
    }

    private long estimateNodeCardinality(QueryNode node) {
        long fullSetCardinality = Math.max(1, jedis.scard(keyStrategy.indexAllKey(metadata.redisNamespace())));
        if (node instanceof QueryFilter filter) {
            return estimateFilter(filter).estimatedCardinality();
        }
        QueryGroup group = (QueryGroup) node;
        return switch (group.operator()) {
            case AND -> estimateAndGroupCardinality(group, fullSetCardinality);
            case OR -> estimateOrGroupCardinality(group, fullSetCardinality);
        };
    }

    private long estimateAndGroupCardinality(QueryGroup group, long fullSetCardinality) {
        long minChild = fullSetCardinality;
        double combinedRatio = 1.0d;
        for (QueryNode child : group.nodes()) {
            long childEstimate = estimateNodeCardinality(child);
            minChild = Math.min(minChild, childEstimate);
            combinedRatio *= Math.min(1.0d, Math.max(0.0d, (double) childEstimate / fullSetCardinality));
        }
        long independenceEstimate = Math.round(fullSetCardinality * combinedRatio);
        long estimated = Math.min(minChild, Math.max(0L, independenceEstimate));
        return applyLearnedAdjustment(describeNode(group), estimated, fullSetCardinality);
    }

    private long estimateOrGroupCardinality(QueryGroup group, long fullSetCardinality) {
        long maxChild = 0L;
        double nonMatchRatio = 1.0d;
        for (QueryNode child : group.nodes()) {
            long childEstimate = estimateNodeCardinality(child);
            maxChild = Math.max(maxChild, childEstimate);
            nonMatchRatio *= (1.0d - Math.min(1.0d, Math.max(0.0d, (double) childEstimate / fullSetCardinality)));
        }
        long unionEstimate = Math.round(fullSetCardinality * (1.0d - nonMatchRatio));
        long estimated = Math.min(fullSetCardinality, Math.max(maxChild, unionEstimate));
        return applyLearnedAdjustment(describeNode(group), estimated, fullSetCardinality);
    }

    private List<QueryHistogramBucket> histogramForColumn(String column) {
        if (!config.plannerStatisticsEnabled() || !supportsScoreOrdering(column)) {
            return List.of();
        }
        String cacheKey = metadata.redisNamespace() + "|" + column;
        CachedHistogram cachedHistogram = histogramCache.get(cacheKey);
        long now = System.currentTimeMillis();
        if (cachedHistogram != null && now - cachedHistogram.createdAtEpochMillis() <= config.plannerStatisticsTtlMillis()) {
            return cachedHistogram.buckets();
        }

        if (config.plannerStatisticsPersisted()) {
            List<QueryHistogramBucket> persisted = loadHistogramFromRedisSafely(column);
            if (!persisted.isEmpty()) {
                histogramCache.put(cacheKey, new CachedHistogram(persisted, now));
                return persisted;
            }
        }

        String sortKey = keyStrategy.indexSortKey(metadata.redisNamespace(), column);
        long cardinality = jedis.zcard(sortKey);
        if (cardinality == 0) {
            return List.of();
        }
        int bucketCount = Math.max(1, Math.min(config.rangeHistogramBuckets(), (int) cardinality));
        ArrayList<Double> boundaries = new ArrayList<>(bucketCount + 1);
        for (int index = 0; index <= bucketCount; index++) {
            long rank = Math.min(cardinality - 1, Math.round((double) index * (cardinality - 1) / bucketCount));
            List<String> ids = jedis.zrange(sortKey, rank, rank);
            if (ids.isEmpty()) {
                continue;
            }
            Double score = jedis.zscore(sortKey, ids.get(0));
            if (score != null) {
                boundaries.add(score);
            }
        }
        if (boundaries.size() < 2) {
            return List.of();
        }

        ArrayList<QueryHistogramBucket> buckets = new ArrayList<>(bucketCount);
        for (int index = 0; index < boundaries.size() - 1; index++) {
            double lower = boundaries.get(index);
            double upper = boundaries.get(index + 1);
            boolean lastBucket = index == boundaries.size() - 2;
            String max = lastBucket ? String.valueOf(upper) : "(" + upper;
            long count = jedis.zcount(sortKey, String.valueOf(lower), max);
            buckets.add(new QueryHistogramBucket(lower, upper, count));
        }
        List<QueryHistogramBucket> immutableBuckets = List.copyOf(buckets);
        histogramCache.put(cacheKey, new CachedHistogram(immutableBuckets, now));
        if (config.plannerStatisticsPersisted()) {
            storeHistogramInRedisSafely(column, immutableBuckets);
        }
        return immutableBuckets;
    }

    private long cardinalityOfContains(QueryFilter filter) {
        List<String> tokens = tokenize(String.valueOf(filter.value()));
        if (tokens.isEmpty()) {
            return 0L;
        }
        long fullSet = Math.max(1, jedis.scard(keyStrategy.indexAllKey(metadata.redisNamespace())));
        long minCount = Long.MAX_VALUE;
        double productRatio = 1.0d;
        for (String token : tokens) {
            long cardinality = cardinalityOfSet(keyStrategy.indexTokenKey(metadata.redisNamespace(), filter.column(), encodeKeyPart(token)));
            minCount = Math.min(minCount, cardinality);
            productRatio *= Math.min(1.0d, Math.max(0.0d, (double) cardinality / fullSet));
        }
        if (minCount == Long.MAX_VALUE) {
            return 0L;
        }
        long independenceEstimate = Math.round(fullSet * productRatio);
        return Math.min(minCount, Math.max(0L, independenceEstimate));
    }

    private long determineSampledCardinality(QueryFilter filter, long fullSetCardinality) {
        if (isRangeOperator(filter.operator())) {
            String sortKey = keyStrategy.indexSortKey(metadata.redisNamespace(), filter.column());
            return Math.min(jedis.zcard(sortKey), Math.max(1, config.plannerStatisticsSampleSize()));
        }
        return Math.min(fullSetCardinality, Math.max(1, config.plannerStatisticsSampleSize()));
    }

    private long applyLearnedAdjustment(String expression, long estimatedCardinality, long fullSetCardinality) {
        if (!config.learnedStatisticsEnabled()) {
            return estimatedCardinality;
        }
        LearnedStats learnedStats = loadLearnedStats(expression);
        if (learnedStats == null || learnedStats.sampleCount() <= 0) {
            return estimatedCardinality;
        }
        double weight = Math.max(0.0d, Math.min(1.0d, config.learnedStatisticsWeight()));
        long blended = Math.round((estimatedCardinality * (1.0d - weight)) + (learnedStats.averageCardinality() * weight));
        return Math.max(0L, Math.min(fullSetCardinality, blended));
    }

    private LearnedStats loadLearnedStats(String expression) {
        String cacheKey = metadata.redisNamespace() + "|learned|" + expression;
        CachedLearnedStats cached = learnedStatsCache.get(cacheKey);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.createdAtEpochMillis() <= config.plannerStatisticsTtlMillis()) {
            return cached.stats();
        }

        if (!config.plannerStatisticsPersisted()) {
            return cached == null ? null : cached.stats();
        }

        Map<String, String> fields = loadLearnedStatsFieldsSafely(expression);
        if (fields == null || fields.isEmpty()) {
            return null;
        }
        LearnedStats learnedStats = new LearnedStats(
                parseLong(fields.get("averageCardinality"), 0L),
                parseLong(fields.get("sampleCount"), 0L)
        );
        learnedStatsCache.put(cacheKey, new CachedLearnedStats(learnedStats, now));
        return learnedStats;
    }

    private void storeLearnedStats(String expression, long actualCardinality) {
        if (actualCardinality < 0) {
            return;
        }
        String cacheKey = metadata.redisNamespace() + "|learned|" + expression;
        LearnedStats current = loadLearnedStats(expression);
        long sampleCount = current == null ? 0L : current.sampleCount();
        long averageCardinality = current == null ? 0L : current.averageCardinality();
        long newSampleCount = sampleCount + 1L;
        long newAverage = sampleCount == 0L
                ? actualCardinality
                : Math.round(((double) averageCardinality * sampleCount + actualCardinality) / newSampleCount);
        LearnedStats learnedStats = new LearnedStats(newAverage, newSampleCount);
        learnedStatsCache.put(cacheKey, new CachedLearnedStats(learnedStats, System.currentTimeMillis()));
        if (config.plannerStatisticsPersisted()) {
            storeLearnedStatsSafely(expression, learnedStats);
        }
    }

    private long cardinalityOfRange(QueryFilter filter) {
        Double score = RedisScoreSupport.toScore(metadata.columnTypes(), filter.column(), filter.value());
        if (score == null) {
            return 0L;
        }

        String sortKey = keyStrategy.indexSortKey(metadata.redisNamespace(), filter.column());
        String min = "-inf";
        String max = "+inf";
        switch (filter.operator()) {
            case GT -> min = "(" + score;
            case GTE -> min = String.valueOf(score);
            case LT -> max = "(" + score;
            case LTE -> max = String.valueOf(score);
            default -> {
                return 0L;
            }
        }
        return jedis.zcount(sortKey, min, max);
    }

    private long cardinalityOfSet(String key) {
        return jedis.scard(key);
    }

    private boolean shouldShedIndexWrites() {
        return producerGuard != null && producerGuard.shouldShedQueryIndexWrites(metadata.redisNamespace());
    }

    private boolean shouldShedPlannerLearning() {
        return producerGuard != null && producerGuard.shouldShedPlannerLearning(metadata.redisNamespace(), List.of());
    }

    private boolean shouldBypassIndexes() {
        return (producerGuard != null && producerGuard.shouldShedQueryIndexReads(metadata.redisNamespace(), List.of())) || indexFeaturesDegraded();
    }

    private boolean shouldBypassIndexes(QuerySpec querySpec) {
        return (producerGuard != null && producerGuard.shouldShedQueryIndexReads(metadata.redisNamespace(), classifyQueryClasses(querySpec)))
                || indexFeaturesDegraded();
    }

    private boolean shouldShedPlannerLearning(QuerySpec querySpec) {
        return producerGuard != null && producerGuard.shouldShedPlannerLearning(metadata.redisNamespace(), classifyQueryClasses(querySpec));
    }

    private boolean indexFeaturesDegraded() {
        return jedis.exists(keyStrategy.indexDegradedKey(metadata.redisNamespace()));
    }

    private void markIndexFeaturesDegraded() {
        jedis.set(keyStrategy.indexDegradedKey(metadata.redisNamespace()), "1");
    }

    private void maybeRecoverDegradedIndexes(Collection<HardLimitQueryClass> queryClasses) {
        if (!indexFeaturesDegraded()) {
            return;
        }
        if (producerGuard == null || !producerGuard.shouldAutoRecoverDegradedIndexes(metadata.redisNamespace())) {
            return;
        }
        if (producerGuard.shouldShedQueryIndexWrites(metadata.redisNamespace())
                || producerGuard.shouldShedQueryIndexReads(metadata.redisNamespace(), queryClasses)) {
            return;
        }
        rebuildFromEntityStore(true, "automatic-recovery");
    }

    private List<HardLimitQueryClass> classifyQueryClasses(QuerySpec querySpec) {
        LinkedHashSet<HardLimitQueryClass> classes = new LinkedHashSet<>();
        if (querySpec == null) {
            return List.of();
        }
        for (QueryFilter filter : querySpec.filters()) {
            if (filter.column().contains(".")) {
                classes.add(HardLimitQueryClass.RELATION);
            }
            switch (filter.operator()) {
                case GT, GTE, LT, LTE -> classes.add(HardLimitQueryClass.RANGE);
                case STARTS_WITH -> classes.add(HardLimitQueryClass.PREFIX);
                case CONTAINS -> classes.add(HardLimitQueryClass.TEXT);
                default -> classes.add(HardLimitQueryClass.EXACT);
            }
        }
        if (!querySpec.sorts().isEmpty()) {
            classes.add(HardLimitQueryClass.SORT);
        }
        classes.add(HardLimitQueryClass.EXPLAIN);
        return List.copyOf(classes);
    }

    private boolean acquireRebuildSlot(String lockKey) {
        return jedis.set(
                lockKey,
                String.valueOf(System.currentTimeMillis()),
                SetParams.setParams().nx().px(30_000L)
        ) != null;
    }

    private long clearIndexKeys(String lockKey) {
        long clearedCount = 0L;
        String cursor = "0";
        do {
            redis.clients.jedis.resps.ScanResult<String> scan = jedis.scan(cursor, new redis.clients.jedis.params.ScanParams()
                    .match(keyStrategy.indexPattern(metadata.redisNamespace()))
                    .count(256));
            for (String key : scan.getResult()) {
                if (lockKey.equals(key)) {
                    continue;
                }
                clearedCount += jedis.del(key);
            }
            cursor = scan.getCursor();
        } while (!"0".equals(cursor));
        return clearedCount;
    }

    private List<String> scanAllEntityIds() {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        String cursor = "0";
        String prefix = keyStrategy.entityPattern(metadata.redisNamespace()).replace("*", "");
        do {
            redis.clients.jedis.resps.ScanResult<String> scan = jedis.scan(cursor, new redis.clients.jedis.params.ScanParams()
                    .match(keyStrategy.entityPattern(metadata.redisNamespace()))
                    .count(Math.max(64, config.plannerStatisticsSampleSize() * 4)));
            for (String key : scan.getResult()) {
                if (key.startsWith(prefix)) {
                    ids.add(key.substring(prefix.length()));
                }
            }
            cursor = scan.getCursor();
        } while (!"0".equals(cursor));
        return List.copyOf(ids);
    }

    private boolean isRangeOperator(QueryOperator operator) {
        return operator == QueryOperator.GT
                || operator == QueryOperator.GTE
                || operator == QueryOperator.LT
                || operator == QueryOperator.LTE;
    }

    private void invalidatePlannerStatistics() {
        cardinalityCache.clear();
        histogramCache.clear();
        learnedStatsCache.clear();
        if (config.plannerStatisticsPersisted()) {
            try {
                jedis.incr(keyStrategy.indexPlannerEpochKey(metadata.redisNamespace()));
            } catch (RuntimeException exception) {
                markIndexFeaturesDegradedSafely();
            }
        }
    }

    private QueryCardinalityEstimate loadEstimateFromRedis(QueryFilter filter) {
        Map<String, String> fields = jedis.hgetAll(plannerEstimateRedisKey(filter));
        if (fields == null || fields.isEmpty()) {
            return null;
        }
        long estimatedCardinality = parseLong(fields.get("estimatedCardinality"), 0L);
        long estimatedCost = parseLong(fields.get("estimatedCost"), Math.max(1L, estimatedCardinality));
        List<QueryHistogramBucket> histogram = isRangeOperator(filter.operator())
                ? loadHistogramFromRedis(filter.column())
                : List.of();
        return new QueryCardinalityEstimate(
                fields.getOrDefault("expression", describeFilter(filter)),
                estimatedCardinality,
                estimatedCost,
                parseLong(fields.get("sampledCardinality"), estimatedCardinality),
                parseDouble(fields.get("selectivityRatio"), 1.0d),
                histogram
        );
    }

    private QueryCardinalityEstimate loadEstimateFromRedisSafely(QueryFilter filter) {
        try {
            return loadEstimateFromRedis(filter);
        } catch (RuntimeException exception) {
            markIndexFeaturesDegradedSafely();
            return null;
        }
    }

    private void storeEstimateInRedis(QueryFilter filter, QueryCardinalityEstimate estimate) {
        String key = plannerEstimateRedisKey(filter);
        jedis.hset(key, Map.of(
                "expression", estimate.expression(),
                "estimatedCardinality", String.valueOf(estimate.estimatedCardinality()),
                "estimatedCost", String.valueOf(estimate.estimatedCost()),
                "sampledCardinality", String.valueOf(estimate.sampledCardinality()),
                "selectivityRatio", String.valueOf(estimate.selectivityRatio())
        ));
        jedis.pexpire(key, config.plannerStatisticsTtlMillis());
    }

    private void storeEstimateInRedisSafely(QueryFilter filter, QueryCardinalityEstimate estimate) {
        try {
            storeEstimateInRedis(filter, estimate);
        } catch (RuntimeException exception) {
            markIndexFeaturesDegradedSafely();
        }
    }

    private List<QueryHistogramBucket> loadHistogramFromRedis(String column) {
        String payload = jedis.get(plannerHistogramRedisKey(column));
        if (payload == null || payload.isBlank()) {
            return List.of();
        }
        ArrayList<QueryHistogramBucket> buckets = new ArrayList<>();
        for (String rawBucket : payload.split("\\|")) {
            if (rawBucket.isBlank()) {
                continue;
            }
            String[] parts = rawBucket.split(":", 3);
            if (parts.length != 3) {
                continue;
            }
            buckets.add(new QueryHistogramBucket(
                    Double.parseDouble(parts[0]),
                    Double.parseDouble(parts[1]),
                    Long.parseLong(parts[2])
            ));
        }
        return List.copyOf(buckets);
    }

    private List<QueryHistogramBucket> loadHistogramFromRedisSafely(String column) {
        try {
            return loadHistogramFromRedis(column);
        } catch (RuntimeException exception) {
            markIndexFeaturesDegradedSafely();
            return List.of();
        }
    }

    private void storeHistogramInRedis(String column, List<QueryHistogramBucket> buckets) {
        String payload = buckets.stream()
                .map(bucket -> bucket.lowerBoundInclusive() + ":" + bucket.upperBoundExclusive() + ":" + bucket.count())
                .collect(Collectors.joining("|"));
        String key = plannerHistogramRedisKey(column);
        jedis.set(key, payload);
        jedis.pexpire(key, config.plannerStatisticsTtlMillis());
    }

    private void storeHistogramInRedisSafely(String column, List<QueryHistogramBucket> buckets) {
        try {
            storeHistogramInRedis(column, buckets);
        } catch (RuntimeException exception) {
            markIndexFeaturesDegradedSafely();
        }
    }

    private Map<String, String> loadLearnedStatsFieldsSafely(String expression) {
        try {
            return jedis.hgetAll(plannerLearnedRedisKey(expression));
        } catch (RuntimeException exception) {
            markIndexFeaturesDegradedSafely();
            return Map.of();
        }
    }

    private void storeLearnedStatsSafely(String expression, LearnedStats learnedStats) {
        try {
            String key = plannerLearnedRedisKey(expression);
            jedis.hset(key, Map.of(
                    "averageCardinality", String.valueOf(learnedStats.averageCardinality()),
                    "sampleCount", String.valueOf(learnedStats.sampleCount())
            ));
            jedis.pexpire(key, config.plannerStatisticsTtlMillis());
        } catch (RuntimeException exception) {
            markIndexFeaturesDegradedSafely();
        }
    }

    private void markIndexFeaturesDegradedSafely() {
        try {
            markIndexFeaturesDegraded();
        } catch (RuntimeException ignored) {
            // Index degradation is advisory; never fail the main repository path on fallback marking.
        }
    }

    private String plannerEstimateRedisKey(QueryFilter filter) {
        return keyStrategy.indexPlannerEstimateKey(
                metadata.redisNamespace(),
                encodeKeyPart(currentPlannerEpoch() + "|" + describeFilter(filter))
        );
    }

    private String plannerHistogramRedisKey(String column) {
        return keyStrategy.indexPlannerHistogramKey(
                metadata.redisNamespace(),
                encodeKeyPart(currentPlannerEpoch() + "|" + column)
        );
    }

    private String plannerLearnedRedisKey(String expression) {
        return keyStrategy.indexPlannerLearnedKey(
                metadata.redisNamespace(),
                encodeKeyPart(currentPlannerEpoch() + "|" + expression)
        );
    }

    private String currentPlannerEpoch() {
        String epoch = jedis.get(keyStrategy.indexPlannerEpochKey(metadata.redisNamespace()));
        return epoch == null || epoch.isBlank() ? "0" : epoch;
    }

    private long parseLong(String value, long defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return defaultValue;
        }
    }

    private double parseDouble(String value, double defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException exception) {
            return defaultValue;
        }
    }

    private long estimateFilterCost(QueryFilter filter, Set<String> indexedMatches) {
        return estimateFilter(filter).estimatedCost();
    }

    private long estimateSortCost(QuerySpec querySpec) {
        return Math.max(1, (long) querySpec.limit() * Math.max(1, querySpec.sorts().size()));
    }

    private long estimatePlanCost(QuerySpec querySpec, int candidateCount, SortPlan sortPlan) {
        return Math.max(1, candidateCount) + sortPlan.estimatedCost() + Math.max(0, querySpec.fetchPlan().includes().size());
    }

    private String plannerStrategy(QuerySpec querySpec, SortPlan sortPlan, boolean fullyIndexed) {
        if (!fullyIndexed) {
            return "INDEX_ASSISTED_WITH_RESIDUAL_EVALUATION";
        }
        if (querySpec.rootGroup().operator() == QueryGroupOperator.OR) {
            return "COST_BASED_OR_INDEX_PLAN:" + sortPlan.strategy();
        }
        return "COST_BASED_AND_INDEX_PLAN:" + sortPlan.strategy();
    }

    private record Resolution(
            Set<String> candidates,
            boolean fullyIndexed
    ) {
    }

    private record ResolutionWithNode(
            QueryNode node,
            Resolution resolution
    ) {
    }

    private record BatchFilterResult(
            List<String> matchedIds,
            boolean stopScanning
    ) {
    }

    private record LazySortedScanPlan(
            Set<String> membershipIds,
            ScoreRange scoreRange,
            com.reactor.cachedb.core.query.QuerySortDirection direction,
            boolean requiresDecode
    ) {
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

        private boolean canStop(double score, com.reactor.cachedb.core.query.QuerySortDirection direction) {
            if (direction == com.reactor.cachedb.core.query.QuerySortDirection.DESC) {
                return minInclusive ? score < min : score <= min;
            }
            return maxInclusive ? score > max : score >= max;
        }
    }

    public record SortPlan(
            String strategy,
            long estimatedCost,
            boolean usesSortedIndex
    ) {
    }

    private record CachedEstimate(
            QueryCardinalityEstimate estimate,
            long createdAtEpochMillis
    ) {
    }

    private record CachedHistogram(
            List<QueryHistogramBucket> buckets,
            long createdAtEpochMillis
    ) {
    }

    private record CachedLearnedStats(
            LearnedStats stats,
            long createdAtEpochMillis
    ) {
    }

    private record LearnedStats(
            long averageCardinality,
            long sampleCount
    ) {
    }
}
