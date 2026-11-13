package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.config.QueryIndexConfig;
import com.reactor.cachedb.core.config.RelationConfig;
import com.reactor.cachedb.core.model.EntityCodec;
import com.reactor.cachedb.core.model.EntityMetadata;
import com.reactor.cachedb.core.model.RelationDefinition;
import com.reactor.cachedb.core.plan.FetchPlan;
import com.reactor.cachedb.core.projection.ProjectionEntityMetadata;
import com.reactor.cachedb.core.query.HardLimitQueryClass;
import com.reactor.cachedb.core.query.PartitionedQuerySpec;
import com.reactor.cachedb.core.query.QueryCardinalityEstimate;
import com.reactor.cachedb.core.query.QueryEvaluator;
import com.reactor.cachedb.core.query.QueryExplainPlan;
import com.reactor.cachedb.core.query.QueryExplainRelationState;
import com.reactor.cachedb.core.query.QueryExplainStep;
import com.reactor.cachedb.core.query.QueryFilter;
import com.reactor.cachedb.core.query.QueryGroup;
import com.reactor.cachedb.core.query.QueryGroupOperator;
import com.reactor.cachedb.core.query.QueryHistogramBucket;
import com.reactor.cachedb.core.query.QueryNode;
import com.reactor.cachedb.core.query.QueryOperator;
import com.reactor.cachedb.core.query.QuerySort;
import com.reactor.cachedb.core.query.QuerySpec;
import com.reactor.cachedb.core.queue.QueryIndexRebuildResult;
import com.reactor.cachedb.core.registry.EntityBinding;
import com.reactor.cachedb.core.registry.EntityRegistry;
import com.reactor.cachedb.core.relation.NoOpRelationBatchLoader;
import com.reactor.cachedb.core.route.RouteCacheContext;
import com.reactor.cachedb.core.route.RouteCacheContract;
import com.reactor.cachedb.core.route.RouteCacheStrictMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import redis.clients.jedis.AbstractPipeline;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.Response;
import redis.clients.jedis.params.SetParams;
import redis.clients.jedis.resps.Tuple;

public final class RedisQueryIndexManager<T, ID> {
    private final java.util.concurrent.locks.ReentrantLock indexLock = new java.util.concurrent.locks.ReentrantLock();
    public enum CompositeIndexState { VERIFIED, PENDING_REBUILD, NONE }
    private final String compositeSignature;
    private volatile CompositeIndexState compositeState = CompositeIndexState.NONE;
    private volatile long compositeCheckAt;
    private boolean compositeWarningLogged;

    /** Signature checks are bounded to one per five seconds, except after a rebuild. */
    public CompositeIndexState compositeIndexState() {
        if (metadata.compositeExactIndexes().isEmpty()) return CompositeIndexState.NONE;
        if (hasCompositeSortIndexes() && indexFeaturesDegraded()) return CompositeIndexState.PENDING_REBUILD;
        if (compositeCheckAt == 0 || System.nanoTime() - compositeCheckAt >= 0) checkCompositeSignature();
        return compositeState;
    }

    private void checkCompositeSignature() {
        indexLock.lock();
        try {
            if (compositeCheckAt != 0 && System.nanoTime() - compositeCheckAt < 0) return;
            String namespace = metadata.redisNamespace();
            String signatureKey = keyStrategy.compositeIndexSignatureKey(namespace);
            String stored = jedis.commands().get(signatureKey);
            if (!compositeSignature.equals(stored) && !hasCompositeSortIndexes()) {
                var keys = List.of(signatureKey, keyStrategy.indexAllKey(namespace), keyStrategy.hotSetKey(namespace));
                boolean atomic = true;
                try { jedis.requireSameSlot(keys); } catch (IllegalArgumentException crossSlot) { atomic = false; }
                if (atomic) {
                    Object result = jedis.eval("""
                            if redis.call('SCARD',KEYS[2]) == 0 and redis.call('ZCARD',KEYS[3]) == 0 then
                              redis.call('SET',KEYS[1],ARGV[1]); return ARGV[1]
                            end
                            return redis.call('GET',KEYS[1])
                            """, keys, List.of(compositeSignature));
                    stored = result instanceof String value ? value : null;
                }
            }
            compositeState = compositeSignature.equals(stored)
                    ? CompositeIndexState.VERIFIED : CompositeIndexState.PENDING_REBUILD;
            compositeCheckAt = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            if (compositeState == CompositeIndexState.PENDING_REBUILD && !compositeWarningLogged) {
                compositeWarningLogged = true;
                System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING,
                        "Composite indexes pending rebuild for " + metadata.entityName() + "; stored=" + shortSignature(stored)
                        + "; local=" + shortSignature(compositeSignature)
                        + "; run rebuildIndexes after all writers are on the new bindings");
            }
        } finally {
            indexLock.unlock();
        }
    }

    private static String shortSignature(String signature) {
        return signature == null ? "<missing>" : signature.substring(0, Math.min(512, signature.length()));
    }
    static final String PROJECTION_TENANT_MARKER = "$cachedb:projection-tenant";

    private String projectionOwnerNamespace() {
        return metadata instanceof ProjectionEntityMetadata<?,?> projection ? projection.tenantOwnerNamespace() : null;
    }

    List<String> projectionTenantCandidates(String tenant) {
        if (projectionOwnerNamespace() == null) throw new IllegalStateException("Projection has no tenant ownership contract");
        maybeRecoverDegradedIndexes(classifyQueryClasses(QuerySpec.allOf()));
        if (indexFeaturesDegraded()) throw new IllegalStateException("Tenant projection indexes require recovery");
        String key = keyStrategy.indexExactKey(metadata.redisNamespace(),PROJECTION_TENANT_MARKER,encodeKeyPart(tenant));
        return new ArrayList<>(jedis.smembersBatch(List.of(key),config.maxMaterializedCandidateIds()).get(key));
    }

    public static final int COMPOSITE_MAX_ROUNDS = 4;
    public static final int COMPOSITE_MAX_EXAMINED_IDS = 10_000;
    private static final long COMPOSITE_MAX_BYTES = 16L * 1024 * 1024;
    private final java.util.concurrent.atomic.LongAdder compositeBoundedFallbacks = new java.util.concurrent.atomic.LongAdder();

    public long compositeBoundedFallbacks() { return compositeBoundedFallbacks.sum(); }

    public record CompositePosition(double score, String id) {
        public CompositePosition {
            if (!Double.isFinite(score)) throw new IllegalArgumentException("Invalid composite cursor score");
            RedisCompositeIndexes.validateMember(id);
            if (score == 0d) score = 0d;
        }
    }
    public record CompositeRow<E>(E entity, CompositePosition position) {}
    public record CompositeWindow<E>(List<CompositeRow<E>> rows, boolean hasMore,
            CompositePosition continuation, int rounds, int examinedIds) {
        public CompositeWindow { rows = List.copyOf(rows); }
    }
    public record CompositeLongPosition(long sortValue, String id) {
        public CompositeLongPosition { RedisCompositeIndexes.validateMember(id); }
    }
    public record CompositeLongRow<E>(E entity, CompositeLongPosition position) {}
    public record CompositeLongWindow<E>(List<CompositeLongRow<E>> rows, boolean hasMore,
            CompositeLongPosition continuation, int rounds, int examinedIds) {
        public CompositeLongWindow { rows = List.copyOf(rows); }
    }
    private record CompositeMaterialization<E>(List<E> rows, long bytes) {}

    private record CompositePlan(String exactKey, String sortKey, String sortColumn,
            String minimum, String maximum, boolean descending, QuerySpec prefixQuery, String long53Key) {}
    private record CompositeBatch(List<CompositePosition> positions, boolean hasMore, boolean blocked) {}

    /*
     * Single-slot, read-only continuation. Fence/coverage checks and the ZSET window share one Lua call. Binary search uses Redis byte-lexical member
     * comparisons and never requires the cursor member still to exist. Each command
     * examines at most 64 boundary probes plus 2500 candidate IDs, not an entire tie.
     */
    private static final String COMPOSITE_RANGE = """
            if redis.call('EXISTS',KEYS[3])~=0 or redis.call('HLEN',KEYS[4])~=0
              or redis.call('EXISTS',KEYS[5])~=0 or redis.call('GET',KEYS[6])~=ARGV[9] then
              return {-1,{}} end
            if #KEYS==7 then
              if redis.call('TYPE',KEYS[7]).ok~='string' or redis.call('GET',KEYS[7])~=ARGV[10] then return {-1,{}} end
            end
            local total=redis.call('ZCARD',KEYS[1])
            if redis.call('SCARD',KEYS[2])~=total then return {-1,{}} end
            local first=0
            if ARGV[1]~='-inf' then first=redis.call('ZCOUNT',KEYS[1],'-inf','('..ARGV[1]) end
            local last=total-1
            if ARGV[2]~='+inf' then last=redis.call('ZCOUNT',KEYS[1],'-inf',ARGV[2])-1 end
            local reverse=ARGV[6]=='1'
            if ARGV[3]=='1' then
              local score=tonumber(ARGV[4])
              local member=ARGV[5]
              local lo=first
              local hi=last+1
              local probes=0
              while lo<hi do
                probes=probes+1
                if probes>64 then return redis.error_reply('Composite seek probe budget exceeded') end
                local mid=math.floor(lo+(hi-lo)/2)
                local tuple=redis.call('ZRANGE',KEYS[1],mid,mid,'WITHSCORES')
                local value=tonumber(tuple[2])
                local before=value<score or (value==score and
                    (tuple[1]<member or (not reverse and tuple[1]==member)))
                if before then lo=mid+1 else hi=mid end
              end
              if reverse then last=lo-1 else first=lo end
            end
            if first>last then return {0,{}} end
            local count=tonumber(ARGV[7])
            local available=last-first+1
            local values
            if reverse then
              local start=total-1-last
              values=redis.call('ZREVRANGE',KEYS[1],start,start+math.min(count,available)-1,'WITHSCORES')
            else
              values=redis.call('ZRANGE',KEYS[1],first,first+math.min(count,available)-1,'WITHSCORES')
            end
            local bytes=0
            for i=1,#values do bytes=bytes+#values[i] end
            if bytes>tonumber(ARGV[8]) then return redis.error_reply('Composite ID reply byte budget exceeded') end
            return {available>count and 1 or 0,values}
            """;

    /**
     * Additive repository integration point. Empty means NOT a result: discard all
     * partial work and execute the complete legacy/fail-closed fallback. Admission
     * must be side-effect-free; payloads are already fetched and must not be re-read.
     */
    public java.util.Optional<CompositeWindow<T>> resolveCompositeWindow(QuerySpec requested,
            CompositePosition after, java.util.function.Predicate<T> admission) {
        validateProtectedQuery(requested);
        return resolveCompositeWindowForIndex(requested, after, admission, null);
    }

    public java.util.Optional<CompositeWindow<T>> resolveCompositeWindowForIndex(QuerySpec requested,
            CompositePosition after, java.util.function.Predicate<T> admission, String indexName) {
        return resolveCompositeWindowForIndex(requested, after, admission, indexName, false);
    }

    public java.util.Optional<CompositeWindow<T>> resolveCompositeWindowForIndex(QuerySpec requested,
            CompositePosition after, java.util.function.Predicate<T> admission, String indexName, boolean requirePayloads) {
        return resolveCompositeWindowInternal(requested, after, admission, indexName, requirePayloads, false);
    }

    private java.util.Optional<CompositeWindow<T>> resolveCompositeWindowInternal(QuerySpec requested,
            CompositePosition after, java.util.function.Predicate<T> admission, String indexName,
            boolean requirePayloads, boolean exactLongContinuation) {
        java.util.Objects.requireNonNull(admission, "admission");
        if (requested == null) return java.util.Optional.empty();
        QuerySpec query = com.reactor.cachedb.core.tenant.TenantAccess.scoped(metadata, requested);
        if (com.reactor.cachedb.core.causal.CacheCausalContext.hasRequirements(metadata.redisNamespace()))
            return java.util.Optional.empty();
        if (shouldBypassIndexes(query)) return java.util.Optional.empty();
        CompositePlan plan = compositePlan(query, true, indexName);
        if (plan == null || !com.reactor.cachedb.core.query.RedisLexicalScoreContract.attestableType(
                metadata.columnTypes().get(plan.sortColumn()))) return java.util.Optional.empty();
        // Only the v4 adapter supplies a range-checked exact Long continuation.
        if (after != null && plan.long53Key() != null && !exactLongContinuation) return java.util.Optional.empty();
        long targetLong = (long) query.offset() + query.limit();
        if (targetLong >= COMPOSITE_MAX_EXAMINED_IDS || after != null && query.offset() != 0)
            return compositeFallback();
        int target = (int) targetLong;
        long started = System.nanoTime();
        String tenant = com.reactor.cachedb.core.tenant.TenantAccess.active(metadata);
        var accepted = new ArrayList<CompositeRow<T>>(target + 1);
        int examined = 0;
        long[] bytes = {0};
        CompositePosition position = after;
        for (int round = 1; round <= COMPOSITE_MAX_ROUNDS; round++) {
            compositeDeadline(query, started);
            int count = Math.min(2500, COMPOSITE_MAX_EXAMINED_IDS - examined);
            count = Math.min(count, Math.max(64, (target + 1 - accepted.size()) * 2));
            if (count < 1) return compositeFallback();
            CompositeBatch batch = compositeBatch(plan, position, count, COMPOSITE_MAX_BYTES - bytes[0]);
            if (batch.blocked()) return compositeFallback();
            examined += batch.positions().size();
            for (CompositePosition id : batch.positions()) bytes[0] += id.id().getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 32L;
            if (bytes[0] >= COMPOSITE_MAX_BYTES) return compositeFallback();
            for (int base = 0; base < batch.positions().size(); base += 128) {
                compositeDeadline(query, started);
                int end = Math.min(base + 128, batch.positions().size());
                var readKeys = new ArrayList<String>((end - base) * (tenant == null ? 2 : 3));
                for (int i = base; i < end; i++) {
                    String id = batch.positions().get(i).id();
                    readKeys.add(keyStrategy.entityKey(metadata.redisNamespace(), id));
                    readKeys.add(keyStrategy.tombstoneKey(metadata.redisNamespace(), id));
                    if (tenant != null) readKeys.add(keyStrategy.versionKey(metadata.redisNamespace(), id) + ":tenant");
                }
                if (bytes[0] >= COMPOSITE_MAX_BYTES) return compositeFallback();
                List<String> values = jedis.boundedMget(readKeys, COMPOSITE_MAX_BYTES - bytes[0]);
                int stride = tenant == null ? 2 : 3;
                for (String value : values) if (value != null) bytes[0] += value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                if (bytes[0] > COMPOSITE_MAX_BYTES) return compositeFallback();
                for (int i = base; i < end; i++) {
                    CompositePosition observed = batch.positions().get(i);
                    int offset = (i - base) * stride;
                    String payload = values.get(offset);
                    position = observed;
                    if (payload == null || values.get(offset + 1) != null) {
                        if (requirePayloads) throw new CompositePayloadDivergenceException();
                        continue;
                    }
                    if (tenant != null && !tenant.equals(values.get(offset + 2))) continue;
                    T entity = codec.fromRedisValue(payload);
                    if (!observed.id().equals(String.valueOf(metadata.idAccessor().apply(entity))))
                        return compositeFallback();
                    if (tenant != null && !tenant.equals(metadata.extractTenantId(entity))) continue;
                    Map<String, Object> columns = codec.toColumns(entity);
                    if (!com.reactor.cachedb.core.query.RedisLexicalScoreContract.matchesRuntimeType(
                            metadata.columnTypes().get(plan.sortColumn()), columns.get(plan.sortColumn())))
                        return compositeFallback();
                    Double score = RedisScoreSupport.toScore(metadata.columnTypes(), plan.sortColumn(), columns.get(plan.sortColumn()));
                    if (score == null || !Double.isFinite(score) || Double.compare(score == 0d ? 0d : score, observed.score()) != 0)
                        return compositeFallback();
                    // Widened score bounds avoid dropping values rounded to the same double.
                    if (!queryEvaluator.matches(columns, query.rootGroup()) || !admission.test(entity)) continue;
                    accepted.add(new CompositeRow<>(entity, observed));
                    if (accepted.size() > target) return compositeWindow(accepted, query, true, round, examined);
                }
            }
            if (!batch.hasMore()) return compositeWindow(accepted, query, false, round, examined);
        }
        return compositeFallback();
    }

    public static int compareCompositePositions(CompositePosition left, CompositePosition right, boolean descending) {
        int order = Double.compare(left.score(), right.score());
        if (order == 0) order = RedisCompositeIndexes.compareMembers(left.id(), right.id());
        return descending ? -Integer.signum(order) : order;
    }

    /**
     * Complete bounded fallback for an explicitly opted-in score/member cursor.
     * Uses only prefix EQ indexes for candidate selection: range predicates are
     * re-evaluated on payloads, avoiding double-rounded range false negatives.
     */
    public CompositeWindow<T> resolveCompositeWindowFallback(QuerySpec requested,
            CompositePosition after, java.util.function.Predicate<T> admission) {
        validateProtectedQuery(requested);
        java.util.Objects.requireNonNull(admission, "admission");
        QuerySpec query = com.reactor.cachedb.core.tenant.TenantAccess.scoped(metadata,
                java.util.Objects.requireNonNull(requested, "query"));
        if (com.reactor.cachedb.core.causal.CacheCausalContext.hasRequirements(metadata.redisNamespace()))
            throw new IllegalStateException("Composite cursor fallback requires the repository causal-read path");
        CompositePlan plan = compositePlan(query, false);
        if (plan == null || !losslessCompositeScore(plan.sortColumn()) || after != null && query.offset() != 0)
            throw new IllegalArgumentException("Composite cursor needs a lossless byte/short/int/LocalDate score; use logical sorting otherwise");
        long started = System.nanoTime();
        if (jedis.commands().scard(plan.exactKey()) > Math.min(QuerySpec.MAX_LIMIT, config.maxMaterializedCandidateIds()))
            throw new IllegalStateException("Composite fallback candidate budget exceeded");
        List<String> ids = resolveCandidateIds(plan.prefixQuery());
        if (ids.size() > Math.min(QuerySpec.MAX_LIMIT, config.maxMaterializedCandidateIds()))
            throw new IllegalStateException("Composite fallback candidate budget exceeded");
        String tenant = com.reactor.cachedb.core.tenant.TenantAccess.active(metadata);
        var matches = new ArrayList<CompositeRow<T>>(Math.min(ids.size(), 10_000));
        long bytes = 0;
        for (int base = 0; base < ids.size(); base += 128) {
            compositeDeadline(query, started);
            int end = Math.min(base + 128, ids.size());
            var readKeys = new ArrayList<String>((end - base) * (tenant == null ? 2 : 3));
            for (int i = base; i < end; i++) {
                readKeys.add(keyStrategy.entityKey(metadata.redisNamespace(), ids.get(i)));
                readKeys.add(keyStrategy.tombstoneKey(metadata.redisNamespace(), ids.get(i)));
                if (tenant != null) readKeys.add(keyStrategy.versionKey(metadata.redisNamespace(), ids.get(i)) + ":tenant");
            }
            if (bytes >= COMPOSITE_MAX_BYTES) throw new IllegalStateException("Composite fallback byte budget exceeded");
            List<String> values = jedis.boundedMget(readKeys, COMPOSITE_MAX_BYTES - bytes);
            for (String value : values) if (value != null) bytes += value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (bytes > COMPOSITE_MAX_BYTES) throw new IllegalStateException("Composite fallback byte budget exceeded");
            int stride = tenant == null ? 2 : 3;
            for (int i = base; i < end; i++) {
                int offset = (i - base) * stride;
                if (values.get(offset) == null || values.get(offset + 1) != null) continue;
                if (tenant != null && !tenant.equals(values.get(offset + 2))) continue;
                T entity = codec.fromRedisValue(values.get(offset));
                if (!ids.get(i).equals(String.valueOf(metadata.idAccessor().apply(entity))))
                    throw new IllegalStateException("Composite fallback payload identity mismatch");
                if (tenant != null && !tenant.equals(metadata.extractTenantId(entity))) continue;
                Map<String, Object> columns = codec.toColumns(entity);
                if (!queryEvaluator.matches(columns, query.rootGroup()) || !admission.test(entity)) continue;
                Double score = RedisScoreSupport.toScore(metadata.columnTypes(), plan.sortColumn(), columns.get(plan.sortColumn()));
                if (score == null || !Double.isFinite(score))
                    throw new IllegalStateException("Null/non-finite composite cursor scores are unsupported; use a non-cursor query");
                CompositePosition position = new CompositePosition(score, ids.get(i));
                if (after == null || compareCompositePositions(position, after, plan.descending()) > 0)
                    matches.add(new CompositeRow<>(entity, position));
            }
        }
        compositeDeadline(query, started);
        matches.sort((left, right) -> compareCompositePositions(left.position(), right.position(), plan.descending()));
        long end = (long) query.offset() + query.limit();
        return compositeWindow(matches, query, matches.size() > end, 0, ids.size()).orElseThrow();
    }

    private boolean losslessCompositeScore(String column) {
        return com.reactor.cachedb.core.query.RedisLexicalScoreContract.losslessType(metadata.columnTypes().get(column));
    }

    /** Candidate selection never applies rounded score bounds or typed-ID continuation. */
    public java.util.Optional<List<String>> resolveCompositeRangeCandidates(QuerySpec requested) {
        com.reactor.cachedb.core.crypto.EncryptedStorageChecks.validateQuery(metadata, requested);
        if (requested == null || metadata.compositeSortColumns().isEmpty()) return java.util.Optional.empty();
        QuerySpec query = com.reactor.cachedb.core.tenant.TenantAccess.scoped(metadata, requested);
        var filters = new ArrayList<QueryFilter>();
        Map<String, Object> equalities = mandatoryCompositeEqualities(query.rootGroup(), filters, new int[1], 0);
        QuerySpec shape = QuerySpec.allOf().limitTo(query.limit()).withQueryTimeoutSeconds(query.queryTimeoutSeconds());
        for (var equality : equalities.entrySet()) shape = shape.and(QueryFilter.eq(equality.getKey(), equality.getValue()));
        var names = new ArrayList<>(metadata.compositeSortColumns().keySet());
        names.sort(java.util.Comparator.<String>comparingInt(name -> -metadata.compositeExactIndexes().get(name).size())
                .thenComparing(java.util.Comparator.naturalOrder()));
        for (String name : names) {
            CompositePlan plan = compositePlan(shape, false, name);
            if (plan == null) continue;
            boolean relevant = filters.stream().anyMatch(filter -> filter.column().equals(plan.sortColumn())
                    && isRangeOperator(filter.operator()))
                    || query.sorts().stream().anyMatch(sort -> sort.column().equals(plan.sortColumn()));
            if (relevant) return java.util.Optional.of(completeCompositeCandidates(plan, query));
        }
        return java.util.Optional.empty();
    }

    // OR retains only equalities shared by every branch; payload residuals retain typed seek semantics.
    private Map<String, Object> mandatoryCompositeEqualities(QueryNode node, List<QueryFilter> filters,
            int[] nodes, int depth) {
        if (++nodes[0] > 128 || depth > 32)
            throw new IllegalArgumentException("Composite predicate tree exceeds 128 nodes or depth 32");
        if (node instanceof QueryFilter filter) {
            filters.add(filter);
            return filter.operator() == QueryOperator.EQ && filter.value() != null && !filter.column().contains(".")
                    ? Map.of(filter.column(), filter.value()) : Map.of();
        }
        QueryGroup group = (QueryGroup) node;
        var result = new LinkedHashMap<String, Object>();
        boolean first = true;
        for (var child : group.nodes()) {
            Map<String, Object> equalities = mandatoryCompositeEqualities(child, filters, nodes, depth + 1);
            if (group.operator() == QueryGroupOperator.AND || first) equalities.forEach(result::putIfAbsent);
            else result.entrySet().removeIf(entry -> !java.util.Objects.equals(equalities.get(entry.getKey()), entry.getValue()));
            first = false;
        }
        return result;
    }

    private List<String> completeCompositeCandidates(CompositePlan plan, QuerySpec query) {
        if (com.reactor.cachedb.core.causal.CacheCausalContext.hasRequirements(metadata.redisNamespace()))
            throw new IllegalStateException("Composite materialization cannot certify causal absence; use causal entity reads");
        if (compositeIndexState() == CompositeIndexState.VERIFIED && !shouldBypassIndexes(query)
                && !com.reactor.cachedb.core.causal.CacheCausalContext.hasRequirements(metadata.redisNamespace())) {
            List<String> ids = resolveCandidateIds(plan.prefixQuery());
            if (ids.size() > Math.min(10_000, config.maxMaterializedCandidateIds()))
                throw new IllegalStateException("Composite equality candidate budget exceeded");
            return ids;
        }
        rejectStrictRouteFullScan("composite signature, migration, causal or degraded state needs bounded payload evaluation");
        return scanAllEntityIds();
    }

    public void requireRedisLexicalContract(QuerySpec query, String indexName) {
        com.reactor.cachedb.core.crypto.EncryptedStorageChecks.validateQuery(metadata, query);
        CompositePlan plan = compositePlan(query, false, indexName);
        if (indexName == null || indexName.isBlank() || plan == null)
            throw new IllegalArgumentException("Redis lexical window requires its named sortBy composite and complete AND/EQ prefix");
        if (!losslessCompositeScore(plan.sortColumn()))
            throw new IllegalArgumentException("Redis lexical window requires lossless byte/short/int/LocalDate scores; "
                    + "long/float/double/BigDecimal and sub-millisecond/offset times retain logical sorting");
        if (query.sorts().isEmpty()) throw new IllegalArgumentException("Redis lexical windows require explicit sortBy order");
        if (query.offset() != 0) throw new IllegalArgumentException("Redis lexical windows do not accept an offset");
    }

    public void requireRedisLongLexicalContract(QuerySpec query, String indexName) {
        com.reactor.cachedb.core.crypto.EncryptedStorageChecks.validateQuery(metadata, query);
        CompositePlan plan = compositePlan(query, false, indexName);
        if (indexName == null || indexName.isBlank() || plan == null
                || !com.reactor.cachedb.core.query.RedisLexicalScoreContract.long53Type(metadata.columnTypes().get(plan.sortColumn())))
            throw new IllegalArgumentException("Redis Long lexical window requires a named Long sortBy composite and complete AND/EQ prefix");
        if (query.sorts().isEmpty() || query.offset() != 0)
            throw new IllegalArgumentException("Redis Long lexical windows require explicit order and no offset");
        var filters = new ArrayList<QueryFilter>();
        if (!compositeFilters(query.rootGroup(), filters, new int[1], 0))
            throw new IllegalArgumentException("Redis Long lexical cursor requires a bounded AND tree");
        for (var filter : filters) {
            if (!indexedColumns.contains(filter.column()))
                throw new IllegalArgumentException("Redis Long lexical predicate is not indexed: " + filter.column());
            if (filter.column().equals(plan.sortColumn())) {
                if (!(filter.value() instanceof Long) || filter.operator() != QueryOperator.EQ
                        && !isRangeOperator(filter.operator()))
                    throw new IllegalArgumentException("Redis Long lexical sort predicates require exact Long EQ/GT/GTE/LT/LTE values");
            } else if (filter.operator() != QueryOperator.EQ)
                throw new IllegalArgumentException("Redis Long lexical prefix/residual predicates require indexed EQ");
        }
    }

    public String redisLongLexicalCursorContract(QuerySpec query, String indexName, String route, String scope) {
        requireRedisLongLexicalContract(query, indexName);
        return redisLexicalCursorContractParts(query, indexName, route, scope, true);
    }

    public String redisLexicalCursorContract(QuerySpec query, String indexName, String route, String scope) {
        requireRedisLexicalContract(query, indexName);
        return redisLexicalCursorContractParts(query, indexName, route, scope, false);
    }

    private String redisLexicalCursorContractParts(QuerySpec query, String indexName, String route, String scope,
            boolean exactLong) {
        if (route == null || route.isBlank() || route.length() > 256 || scope != null && scope.length() > 4096)
            throw new IllegalArgumentException("Invalid lexical route/scope");
        var filters = new ArrayList<QueryFilter>();
        if (!compositeFilters(query.rootGroup(), filters, new int[1], 0))
            throw new IllegalArgumentException("Redis lexical cursor requires a bounded AND tree");
        var parts = new java.util.TreeSet<String>();
        int values = 0, characters = 0;
        for (var filter : filters) {
            Object contractValue = com.reactor.cachedb.core.crypto.EncryptedStorageChecks.indexLiteral(
                    metadata, codec, filter.column(), filter.value());
            String value = contractValue == null ? "null"
                    : com.reactor.cachedb.core.repository.WindowCursor.encode(Map.of("value", contractValue));
            var in = new ArrayList<String>();
            for (Object item : filter.values()) {
                if (++values > 1024) throw new IllegalArgumentException("Cursor predicate value budget exceeded");
                Object contractItem = com.reactor.cachedb.core.crypto.EncryptedStorageChecks.indexLiteral(
                        metadata, codec, filter.column(), item);
                in.add(contractItem == null ? "null" : com.reactor.cachedb.core.repository.WindowCursor.encode(Map.of("value", contractItem)));
            }
            Collections.sort(in);
            String part = filter.column().length() + ":" + filter.column() + "|" + filter.operator() + "|" + value + "|" + in;
            characters += part.length();
            if (characters > 65_536) throw new IllegalArgumentException("Cursor predicate contract exceeds 64KiB");
            parts.add(part);
        }
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            var contract = new ArrayList<String>();
            String wire = exactLong ? "redis-long-lex-id-v4" : "redis-score-lex-id-v3";
            contract.add(wire); contract.add(route); contract.add(scope == null ? "global" : scope);
            if (exactLong) contract.add("java.lang.Long:" + metadata.compositeSortColumns().get(indexName)
                    + ":unsigned-utf8-id");
            contract.add(metadata.redisNamespace()); contract.add(keyStrategy.entityKey(metadata.redisNamespace(), ""));
            String tenant = com.reactor.cachedb.core.tenant.TenantAccess.active(metadata);
            contract.add(tenant == null ? "administrative-or-global" : "tenant:" + tenant);
            contract.add(compositeSignature); contract.add(indexName);
            for (var sort : query.sorts()) contract.add(sort.column() + ":" + sort.direction());
            contract.addAll(parts);
            for (String part : contract) {
                byte[] encoded = part.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                digest.update((byte) (encoded.length >>> 24)); digest.update((byte) (encoded.length >>> 16));
                digest.update((byte) (encoded.length >>> 8)); digest.update((byte) encoded.length); digest.update(encoded);
            }
            return (exactLong ? "redis-long-lex-id-v4:" : "redis-score-lex-id-v3:")
                    + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new ExceptionInInitializerError(impossible); }
    }

    /** Non-cursor score ordering has no implicit ID comparator. Explicit ID sorts stay logical. */
    public java.util.Optional<CompositeWindow<T>> resolveCompositeQueryWindow(QuerySpec query,
            java.util.function.Predicate<T> admission) {
        if (query.sorts().size() > 1) return java.util.Optional.empty();
        var names = new ArrayList<>(metadata.compositeSortColumns().keySet());
        names.sort(java.util.Comparator.<String>comparingInt(name -> -metadata.compositeExactIndexes().get(name).size())
                .thenComparing(java.util.Comparator.naturalOrder()));
        for (String name : names) {
            if (!com.reactor.cachedb.core.query.RedisLexicalScoreContract.attestableType(
                    metadata.columnTypes().get(metadata.compositeSortColumns().get(name)))) continue;
            CompositePlan plan = compositePlan(query, false, name);
            if (plan != null) return resolveCompositeWindowForIndex(query, null, admission, name, true);
        }
        return java.util.Optional.empty();
    }

    /** Complete payload fallback, never an index-limited partial cursor page. */
    public static final class CompositePayloadDivergenceException extends IllegalStateException {
        CompositePayloadDivergenceException() { super("Composite index/payload divergence requires source fallback"); }
    }

    public int compositeMaterializationLimit() { return Math.min(10_000, config.maxMaterializedCandidateIds()); }

    public CompositeWindow<T> resolveRedisLexicalFallback(QuerySpec query, CompositePosition after,
            java.util.function.Predicate<T> admission, String indexName, boolean requirePayloads) {
        requireRedisLexicalContract(query, indexName);
        if (com.reactor.cachedb.core.causal.CacheCausalContext.hasRequirements(metadata.redisNamespace()))
            throw new IllegalStateException("Redis lexical cursor cannot certify a causal scope; use causal entity reads");
        var materialized = compositePayloadRows(query, compositePlan(query, false, indexName), requirePayloads);
        return redisLexicalWindowFromRows(query, after, materialized.rows(), admission, indexName, materialized.bytes(), false);
    }

    private CompositeMaterialization<T> compositePayloadRows(QuerySpec query, CompositePlan plan, boolean requirePayloads) {
        List<String> ids = completeCompositeCandidates(plan, query);
        String tenant = com.reactor.cachedb.core.tenant.TenantAccess.active(metadata);
        var rows = new ArrayList<T>(Math.min(ids.size(), 10_000));
        long bytes = 0, started = System.nanoTime();
        for (String id : ids) {
            RedisCompositeIndexes.validateMember(id);
            bytes += id.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        }
        for (int base = 0; base < ids.size(); base += 128) {
            compositeDeadline(query, started);
            int end = Math.min(base + 128, ids.size());
            var keys = new ArrayList<String>((end - base) * (tenant == null ? 2 : 3));
            for (int i = base; i < end; i++) {
                keys.add(keyStrategy.entityKey(metadata.redisNamespace(), ids.get(i)));
                keys.add(keyStrategy.tombstoneKey(metadata.redisNamespace(), ids.get(i)));
                if (tenant != null) keys.add(keyStrategy.versionKey(metadata.redisNamespace(), ids.get(i)) + ":tenant");
            }
            if (bytes >= COMPOSITE_MAX_BYTES) throw new IllegalStateException("Composite fallback byte budget exceeded");
            var values = jedis.boundedMget(keys, COMPOSITE_MAX_BYTES - bytes);
            for (String value : values) if (value != null) bytes += value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            int stride = tenant == null ? 2 : 3;
            for (int i = base; i < end; i++) {
                int offset = (i - base) * stride;
                if (values.get(offset) == null || values.get(offset + 1) != null) {
                    if (requirePayloads) throw new CompositePayloadDivergenceException();
                    continue;
                }
                if (tenant != null && !tenant.equals(values.get(offset + 2))) continue;
                T row = codec.fromRedisValue(values.get(offset));
                if (!ids.get(i).equals(String.valueOf(metadata.idAccessor().apply(row))))
                    throw new IllegalStateException("Composite payload identity mismatch");
                if (tenant != null && !tenant.equals(metadata.extractTenantId(row))) continue;
                rows.add(row);
            }
        }
        compositeDeadline(query, started);
        return new CompositeMaterialization<>(rows, bytes);
    }

    public CompositeWindow<T> redisLexicalWindowFromRows(QuerySpec query, CompositePosition after, List<T> input,
            java.util.function.Predicate<T> admission, String indexName) {
        return redisLexicalWindowFromRows(query, after, input, admission, indexName, 0, true);
    }

    CompositeWindow<T> redisLexicalWindowFromRows(QuerySpec query, CompositePosition after, List<T> input,
            java.util.function.Predicate<T> admission, String indexName, long initialBytes, boolean countPayloads) {
        requireRedisLexicalContract(query, indexName);
        if (input.size() > Math.min(10_000, config.maxMaterializedCandidateIds()))
            throw new IllegalStateException("Composite materialized row budget exceeded");
        CompositePlan plan = compositePlan(query, false, indexName);
        var rows = new ArrayList<CompositeRow<T>>();
        long bytes = initialBytes, started = System.nanoTime();
        var identities = new java.util.HashSet<String>();
        for (T row : input) {
            String id = String.valueOf(metadata.idAccessor().apply(row));
            RedisCompositeIndexes.validateMember(id);
            if (!identities.add(id)) throw new IllegalStateException("Duplicate source identity cannot certify lexical pagination");
            compositeDeadline(query, started);
            if (countPayloads) {
                bytes += id.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 32L;
                String payload = codec.toRedisValue(row);
                bytes += payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            }
            if (bytes > COMPOSITE_MAX_BYTES) throw new IllegalStateException("Composite materialized byte budget exceeded");
            var columns = codec.toColumns(row);
            if (!queryEvaluator.matches(columns, query.rootGroup()) || !admission.test(row)) continue;
            Object value = columns.get(plan.sortColumn());
            if (!com.reactor.cachedb.core.query.RedisLexicalScoreContract.matchesRuntimeType(
                    metadata.columnTypes().get(plan.sortColumn()), value))
                throw new IllegalStateException("Null or mismatched runtime score type is not a lexical cursor value");
            Double score = RedisScoreSupport.toScore(metadata.columnTypes(), plan.sortColumn(), value);
            var position = new CompositePosition(score, String.valueOf(metadata.idAccessor().apply(row)));
            if (after == null || compareCompositePositions(position, after, plan.descending()) > 0)
                rows.add(new CompositeRow<>(row, position));
        }
        rows.sort((a, b) -> compareCompositePositions(a.position(), b.position(), plan.descending()));
        return compositeWindow(rows, query, rows.size() > query.limit(), 0, input.size()).orElseThrow();
    }

    public java.util.Optional<CompositeLongWindow<T>> resolveRedisLongLexicalWindow(QuerySpec requested,
            CompositeLongPosition after, java.util.function.Predicate<T> admission, String indexName, boolean requirePayloads) {
        QuerySpec query = com.reactor.cachedb.core.tenant.TenantAccess.scoped(metadata, requested);
        requireRedisLongLexicalContract(query, indexName);
        if (after != null && !com.reactor.cachedb.core.query.RedisLexicalScoreContract.long53Value(after.sortValue()))
            return java.util.Optional.empty();
        CompositePosition scoreAfter = after == null ? null : new CompositePosition((double) after.sortValue(), after.id());
        // The existing range Lua checks the SAME Long53 marker atomically with every ZSET window.
        var fast = resolveCompositeWindowInternal(query, scoreAfter, admission, indexName, requirePayloads, true);
        if (fast.isEmpty()) return java.util.Optional.empty();
        CompositePlan plan = compositePlan(query, false, indexName);
        var rows = new ArrayList<CompositeLongRow<T>>(fast.get().rows().size());
        for (var row : fast.get().rows()) {
            Object value = codec.toColumns(row.entity()).get(plan.sortColumn());
            if (!com.reactor.cachedb.core.query.RedisLexicalScoreContract.long53Value(value)
                    || Double.compare((double) (Long) value, row.position().score()) != 0)
                return java.util.Optional.empty();
            rows.add(new CompositeLongRow<>(row.entity(), new CompositeLongPosition((Long) value, row.position().id())));
        }
        CompositeLongPosition continuation = fast.get().hasMore() && !rows.isEmpty() ? rows.get(rows.size() - 1).position() : null;
        return java.util.Optional.of(new CompositeLongWindow<>(rows, fast.get().hasMore(), continuation,
                fast.get().rounds(), fast.get().examinedIds()));
    }

    public CompositeLongWindow<T> resolveRedisLongLexicalFallback(QuerySpec query, CompositeLongPosition after,
            java.util.function.Predicate<T> admission, String indexName, boolean requirePayloads) {
        requireRedisLongLexicalContract(query, indexName);
        if (com.reactor.cachedb.core.causal.CacheCausalContext.hasRequirements(metadata.redisNamespace()))
            throw new IllegalStateException("Redis Long lexical cursor cannot certify a causal scope; use causal entity reads");
        var materialized = compositePayloadRows(query, compositePlan(query, false, indexName), requirePayloads);
        return redisLongLexicalWindowFromRows(query, after, materialized.rows(), admission, indexName, materialized.bytes(), false);
    }

    public CompositeLongWindow<T> redisLongLexicalWindowFromRows(QuerySpec query, CompositeLongPosition after,
            List<T> input, java.util.function.Predicate<T> admission, String indexName) {
        return redisLongLexicalWindowFromRows(query, after, input, admission, indexName, 0, true);
    }

    CompositeLongWindow<T> redisLongLexicalWindowFromRows(QuerySpec query, CompositeLongPosition after,
            List<T> input, java.util.function.Predicate<T> admission, String indexName, long initialBytes, boolean countPayloads) {
        requireRedisLongLexicalContract(query, indexName);
        if (input.size() > compositeMaterializationLimit())
            throw new IllegalStateException("Composite materialized row budget exceeded");
        CompositePlan plan = compositePlan(query, false, indexName);
        var rows = new ArrayList<CompositeLongRow<T>>();
        var identities = new java.util.HashSet<String>();
        long bytes = initialBytes, started = System.nanoTime();
        for (T row : input) {
            String id = String.valueOf(metadata.idAccessor().apply(row));
            RedisCompositeIndexes.validateMember(id);
            if (!identities.add(id)) throw new IllegalStateException("Duplicate source identity cannot certify lexical pagination");
            compositeDeadline(query, started);
            if (countPayloads) bytes += id.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 32L
                    + codec.toRedisValue(row).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (bytes > COMPOSITE_MAX_BYTES) throw new IllegalStateException("Composite materialized byte budget exceeded");
            var columns = codec.toColumns(row);
            if (!queryEvaluator.matches(columns, query.rootGroup()) || !admission.test(row)) continue;
            if (!(columns.get(plan.sortColumn()) instanceof Long value))
                throw new IllegalStateException("Redis Long lexical cursor requires a non-null exact Long payload value");
            var position = new CompositeLongPosition(value, id);
            if (after == null || compareCompositeLongPositions(position, after, plan.descending()) > 0)
                rows.add(new CompositeLongRow<>(row, position));
        }
        rows.sort((a, b) -> compareCompositeLongPositions(a.position(), b.position(), plan.descending()));
        int to = Math.min(query.limit(), rows.size());
        boolean more = rows.size() > to;
        List<CompositeLongRow<T>> page = List.copyOf(rows.subList(0, to));
        CompositeLongPosition continuation = more && !page.isEmpty() ? page.get(page.size() - 1).position() : null;
        return new CompositeLongWindow<>(page, more, continuation, 0, input.size());
    }

    private static int compareCompositeLongPositions(CompositeLongPosition a, CompositeLongPosition b, boolean descending) {
        int order = Long.compare(a.sortValue(), b.sortValue());
        if (order == 0) order = RedisCompositeIndexes.compareMembers(a.id(), b.id());
        return descending ? -order : order;
    }

    private static void compositeDeadline(QuerySpec query, long started) {
        if (query.queryTimeoutSeconds() > 0 && System.nanoTime() - started
                >= java.util.concurrent.TimeUnit.SECONDS.toNanos(query.queryTimeoutSeconds()))
            throw new IllegalStateException("Composite query deadline exceeded");
    }

    private java.util.Optional<CompositeWindow<T>> compositeFallback() {
        compositeBoundedFallbacks.increment();
        return java.util.Optional.empty();
    }

    private java.util.Optional<CompositeWindow<T>> compositeWindow(List<CompositeRow<T>> accepted,
            QuerySpec query, boolean more, int rounds, int examined) {
        int from = Math.min(query.offset(), accepted.size());
        int to = Math.min(from + query.limit(), accepted.size());
        List<CompositeRow<T>> rows = List.copyOf(accepted.subList(from, to));
        CompositePosition continuation = more && !rows.isEmpty() ? rows.get(rows.size() - 1).position() : null;
        return java.util.Optional.of(new CompositeWindow<>(rows, more, continuation, rounds, examined));
    }

    private CompositeBatch compositeBatch(CompositePlan plan, CompositePosition after, int count, long remainingBytes) {
        if (remainingBytes < 1) throw new IllegalStateException("Composite read byte budget exhausted");
        var fenceKeys = compositeFence.identityKeys();
        List<String> keys;
        List<String> args;
        if (plan.long53Key() == null) {
            keys = List.of(plan.sortKey(), plan.exactKey(), fenceKeys.get(0), fenceKeys.get(1),
                    keyStrategy.indexDegradedKey(metadata.redisNamespace()),
                    keyStrategy.compositeIndexSignatureKey(metadata.redisNamespace()));
            args = List.of(plan.minimum(), plan.maximum(), after == null ? "0" : "1",
                    after == null ? "0" : Double.toString(after.score()), after == null ? "" : after.id(),
                    plan.descending() ? "1" : "0", Integer.toString(count), Long.toString(remainingBytes), compositeSignature);
        } else {
            keys = List.of(plan.sortKey(), plan.exactKey(), fenceKeys.get(0), fenceKeys.get(1),
                    keyStrategy.indexDegradedKey(metadata.redisNamespace()),
                    keyStrategy.compositeIndexSignatureKey(metadata.redisNamespace()), plan.long53Key());
            args = List.of(plan.minimum(), plan.maximum(), after == null ? "0" : "1",
                    after == null ? "0" : Double.toString(after.score()), after == null ? "" : after.id(),
                    plan.descending() ? "1" : "0", Integer.toString(count), Long.toString(remainingBytes),
                    compositeSignature, RedisCompositeMigrationFence.LONG53_VERIFIED);
        }
        jedis.requireSameSlot(keys);
        Object response = jedis.eval(COMPOSITE_RANGE, keys, args);
        List<?> result = (List<?>) response;
        long status = ((Number) result.get(0)).longValue();
        if (status == -1) return new CompositeBatch(List.of(), false, true);
        if (status != 0 && status != 1) throw new IllegalStateException("Invalid composite window status");
        List<?> values = (List<?>) result.get(1);
        if (values.size() > count * 2 || values.size() % 2 != 0)
            throw new IllegalStateException("Invalid bounded composite response");
        var positions = new ArrayList<CompositePosition>(values.size() / 2);
        for (int i = 0; i < values.size(); i += 2)
            positions.add(new CompositePosition(Double.parseDouble(values.get(i + 1).toString()), values.get(i).toString()));
        return new CompositeBatch(List.copyOf(positions), status != 0, false);
    }

    private CompositePlan compositePlan(QuerySpec query) { return compositePlan(query, true); }

    private CompositePlan compositePlan(QuerySpec query, boolean requireVerified) {
        return compositePlan(query, requireVerified, null);
    }

    private CompositePlan compositePlan(QuerySpec query, boolean requireVerified, String requiredIndex) {
        if (!config.exactIndexEnabled() || !config.rangeIndexEnabled()
                || metadata.compositeSortColumns().isEmpty()
                || requireVerified && compositeIndexState() != CompositeIndexState.VERIFIED || query.sorts().size() > 2)
            return null;
        if (query.sorts().size() == 2 && (!query.sorts().get(1).column().equals(metadata.idColumn())
                || query.sorts().get(0).direction() != query.sorts().get(1).direction())) return null;
        var filters = new ArrayList<QueryFilter>();
        if (!compositeFilters(query.rootGroup(), filters, new int[1], 0)) return null;
        var equals = new LinkedHashMap<String, Object>();
        for (QueryFilter filter : filters) {
            if (filter.operator() == QueryOperator.EQ && filter.value() != null) {
                Object previous = equals.putIfAbsent(filter.column(), filter.value());
                if (previous != null && !previous.equals(filter.value())) return null;
            }
        }
        var indexes = new ArrayList<>(metadata.compositeExactIndexes().entrySet());
        indexes.sort(java.util.Comparator.<Map.Entry<String, List<String>>>comparingInt(index -> -index.getValue().size())
                .thenComparing(Map.Entry::getKey));
        for (var index : indexes) {
            if (requiredIndex != null && !requiredIndex.equals(index.getKey())) continue;
            String sort = metadata.compositeSortColumns().get(index.getKey());
            if (sort == null || !equals.keySet().containsAll(index.getValue())
                    || !query.sorts().isEmpty() && !query.sorts().get(0).column().equals(sort)) continue;
            String tenantColumn = metadata.tenantFieldName();
            if (tenantColumn != null && !index.getValue().contains(tenantColumn)) continue;
            Double minimum = null, maximum = null;
            for (QueryFilter filter : filters) {
                if (!filter.column().equals(sort) || !isRangeOperator(filter.operator())) continue;
                if (com.reactor.cachedb.core.query.RedisLexicalScoreContract.long53Type(metadata.columnTypes().get(sort))
                        && !(filter.value() instanceof Long)) return null;
                Double score = RedisScoreSupport.toScore(metadata.columnTypes(), sort, filter.value());
                if (score == null || !Double.isFinite(score)) return null;
                if (score == 0d) score = 0d;
                // Do not spend the bounded window rejecting a large equal-score boundary group.
                // Widened bounds remain mandatory for lossy/untyped filter literals.
                if (com.reactor.cachedb.core.query.RedisLexicalScoreContract.matchesRuntimeType(
                        metadata.columnTypes().get(sort), filter.value())) {
                    if (filter.operator() == QueryOperator.GT) score = Math.nextUp(score);
                    else if (filter.operator() == QueryOperator.LT) score = Math.nextDown(score);
                }
                switch (filter.operator()) {
                    case GT, GTE -> minimum = minimum == null ? score : Math.max(minimum, score);
                    case LT, LTE -> maximum = maximum == null ? score : Math.min(maximum, score);
                    default -> { }
                }
            }
            String exact = RedisCompositeIndexes.key(keyStrategy, metadata.redisNamespace(), index.getKey(), index.getValue(), equals);
            boolean descending = !query.sorts().isEmpty()
                    && query.sorts().get(0).direction() == com.reactor.cachedb.core.query.QuerySortDirection.DESC;
            QuerySpec prefixQuery = QuerySpec.allOf();
            for (String field : index.getValue()) prefixQuery = prefixQuery.and(QueryFilter.eq(field, equals.get(field)));
            return new CompositePlan(exact, exact + ":sort", sort,
                    minimum == null ? "-inf" : minimum.toString(), maximum == null ? "+inf" : maximum.toString(), descending, prefixQuery, long53Key(index.getKey()));
        }
        return null;
    }

    private boolean compositeFilters(QueryNode node, List<QueryFilter> filters, int[] count, int depth) {
        if (++count[0] > 128 || depth > 32) return false;
        if (node instanceof QueryFilter filter) {
            if (filter.column().contains(".")) return false;
            filters.add(filter);
            return true;
        }
        QueryGroup group = (QueryGroup) node;
        if (group.operator() != QueryGroupOperator.AND) return false;
        for (QueryNode child : group.nodes()) if (!compositeFilters(child, filters, count, depth + 1)) return false;
        return true;
    }

    private static final String NULL_SENTINEL = "__NULL__";

    private final RedisClientOperations jedis;
    private final EntityMetadata<T, ID> metadata;
    private final EntityCodec<T> codec;
    private final RedisKeyStrategy keyStrategy;
    private final EntityRegistry entityRegistry;
    private final QueryIndexConfig config;
    private final RelationConfig relationConfig;
    private final QueryEvaluator queryEvaluator;
    private final RedisProducerGuard producerGuard;
    private record Long53Domain(String name, String column, String key) {}
    private final List<Long53Domain> long53Domains;
    private String long53Key(String name) {
        for (var domain : long53Domains) if (domain.name().equals(name)) return domain.key();
        return null;
    }
    private void attestLong53(Map<String, Object> columns) {
        if (long53Domains.isEmpty()) return;
        int unsafe = 0;
        for (int i = 0; i < long53Domains.size(); i++)
            if (!com.reactor.cachedb.core.query.RedisLexicalScoreContract.long53Value(columns.get(long53Domains.get(i).column())))
                unsafe |= 1 << i;
        compositeFence.attestLong53(unsafe);
    }
    private final RedisCompositeMigrationFence compositeFence;
    private final boolean compositeSortedEnabled;
    private final Set<String> indexedColumns;
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
            RedisProducerGuard producerGuard) {
        this(
                JedisPooledOperations.of(jedis),
                metadata,
                codec,
                keyStrategy,
                entityRegistry,
                config,
                relationConfig,
                queryEvaluator,
                producerGuard);
    }

    public RedisQueryIndexManager(
            RedisClientOperations jedis,
            EntityMetadata<T, ID> metadata,
            EntityCodec<T> codec,
            RedisKeyStrategy keyStrategy,
            EntityRegistry entityRegistry,
            QueryIndexConfig config,
            RelationConfig relationConfig,
            QueryEvaluator queryEvaluator,
            RedisProducerGuard producerGuard) {
        this.jedis = jedis;
        this.metadata = metadata;
        com.reactor.cachedb.core.crypto.EncryptedStorageChecks.validateMetadata(metadata);
        com.reactor.cachedb.core.crypto.EncryptedStorageChecks.requireBound(metadata, codec);
        this.codec = com.reactor.cachedb.core.codec.PayloadEntityCodec.wrap(codec, metadata, metadata.compressionThresholdBytes());
        this.keyStrategy = keyStrategy;
        this.entityRegistry = entityRegistry;
        this.config = config;
        this.relationConfig = relationConfig;
        this.queryEvaluator = queryEvaluator;
        this.producerGuard = producerGuard;
        this.indexedColumns = (config.sparseIndexMetadata() ? metadata.indexedColumns() : metadata.columns()).stream()
                .filter(column -> !metadata.encryptedColumns().containsKey(column) || metadata.encryptedColumns().get(column).blindIndex())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!metadata.columns().containsAll(indexedColumns))
            throw new IllegalArgumentException("Indexed columns must be persisted columns");
        metadata.partitionedSortIndexes().forEach((partition, sorts) -> {
            if (!indexedColumns.contains(partition) || !indexedColumns.containsAll(sorts))
                throw new IllegalArgumentException("Partitioned index columns must be present in indexedColumns");
        });
        RedisCompositeIndexes.validate(metadata);
        this.compositeSortedEnabled = !metadata.compositeSortColumns().isEmpty();
        this.compositeSignature = RedisCompositeIndexes.signature(metadata);
        this.long53Domains = metadata.compositeSortColumns().entrySet().stream()
                .filter(entry -> com.reactor.cachedb.core.query.RedisLexicalScoreContract.long53Type(
                        metadata.columnTypes().get(entry.getValue())))
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new Long53Domain(entry.getKey(), entry.getValue(),
                        keyStrategy.compositeIndexSignatureKey(metadata.redisNamespace()) + ":long53:" + entry.getKey())).toList();
        this.compositeFence = new RedisCompositeMigrationFence(jedis, keyStrategy, metadata.redisNamespace(),
                long53Domains.stream().map(Long53Domain::key).toList());
        if (!metadata.compositeExactIndexes().isEmpty()) checkCompositeSignature();
        if (metadata.columns().contains(PROJECTION_TENANT_MARKER)) throw new IllegalArgumentException("Reserved projection tenant metadata column");
    }

    public boolean hasCompositeSortIndexes() { return compositeSortedEnabled; }

    public <V> V withCompositeMutation(java.util.function.Supplier<V> body) {
        return hasCompositeSortIndexes() ? compositeFence.write(body) : body.get();
    }

    public void reindex(T entity) {
        if (!hasCompositeSortIndexes()) { reindexUnfenced(entity); return; }
        withCompositeMutation(() -> { reindexUnfenced(entity); return null; });
    }

    private void reindexUnfenced(T entity) {
        try {
            reindexEntity(entity, true);
        } catch (RuntimeException exception) {
            markIndexFeaturesDegradedSafely();
        }
    }

    public void reindexBatch(Collection<T> entities) {
        if (!hasCompositeSortIndexes()) { reindexBatchUnfenced(entities); return; }
        withCompositeMutation(() -> { reindexBatchUnfenced(entities); return null; });
    }

    private void reindexBatchUnfenced(Collection<T> entities) {
        if (entities == null || entities.isEmpty()) {
            return;
        }
        try {
            reindexEntities(entities, true);
        } catch (RuntimeException exception) {
            markIndexFeaturesDegradedSafely();
        }
    }

    public void reindexBatchWithColumns(Map<ID, T> entities, Map<ID, Map<String, Object>> precomputedColumns) {
        if (!hasCompositeSortIndexes()) { reindexBatchWithColumnsUnfenced(entities, precomputedColumns); return; }
        withCompositeMutation(() -> { reindexBatchWithColumnsUnfenced(entities, precomputedColumns); return null; });
    }

    private void reindexBatchWithColumnsUnfenced(Map<ID, T> entities, Map<ID, Map<String, Object>> precomputedColumns) {
        if (entities == null || precomputedColumns == null || !precomputedColumns.keySet().containsAll(entities.keySet()))
            throw new IllegalArgumentException("Precomputed columns must cover every indexed entity");
        if (entities.size() > 10_000) throw new IllegalArgumentException("At most 10000 index rows per batch");
        for (Map<String, Object> columns : precomputedColumns.values())
            if (columns == null || !columns.keySet().containsAll(indexedColumns))
                throw new IllegalArgumentException("Precomputed columns omit an indexed column");
        try {
            reindexEntities(entities.values(), true, precomputedColumns);
        } catch (RuntimeException exception) {
            markIndexFeaturesDegradedSafely();
        }
    }

    public void reindexProjectionWarmBatch(Collection<T> entities) {
        if (!hasCompositeSortIndexes()) { reindexProjectionWarmBatchUnfenced(entities); return; }
        withCompositeMutation(() -> { reindexProjectionWarmBatchUnfenced(entities); return null; });
    }

    private void reindexProjectionWarmBatchUnfenced(Collection<T> entities) {
        if (entities == null || entities.isEmpty()) {
            return;
        }
        if (!(metadata instanceof ProjectionEntityMetadata<?, ?>)) {
            reindexBatch(entities);
            return;
        }
        if (projectionOwnerNamespace() != null) { reindexBatch(entities); return; }
        try {
            reindexProjectionWarmEntities(entities);
        } catch (RuntimeException exception) {
            markIndexFeaturesDegradedSafely();
        }
    }

    public QueryIndexRebuildResult rebuildFromEntityStore(boolean automatic, String note) {
        if (hasCompositeSortIndexes())
            return new QueryIndexRebuildResult(metadata.entityName(), metadata.redisNamespace(), 0, 0, false,
                    automatic, Instant.now(), "Sorted composite rebuild requires explicit cooperative migration fencing");
        return rebuildFromEntityStoreInternal(automatic, note, null);
    }

    /** Requires every writer upgraded to the cooperative protocol; not an online legacy-writer migration. */
    public QueryIndexRebuildResult rebuildCompositeIndexesFenced(boolean automatic, String note) {
        if (!hasCompositeSortIndexes()) return rebuildFromEntityStore(automatic, note);
        if (automatic && !acquireRebuildSlot(keyStrategy.indexRebuildLockKey(metadata.redisNamespace())))
            return new QueryIndexRebuildResult(metadata.entityName(), metadata.redisNamespace(), 0, 0,
                    !indexFeaturesDegraded(), true, Instant.now(), "Skipped fenced rebuild because cooldown is active");
        try (var migration = compositeFence.exclusive()) {
            return rebuildFromEntityStoreInternal(automatic, note, migration);
        }
    }

    private QueryIndexRebuildResult rebuildFromEntityStoreInternal(boolean automatic, String note,
            RedisCompositeMigrationFence.Migration migration) {
        indexLock.lock();
        try {
            String namespace = metadata.redisNamespace();
            String lockKey = keyStrategy.indexRebuildLockKey(namespace);
            if (automatic && migration == null && !acquireRebuildSlot(lockKey)) {
                return new QueryIndexRebuildResult(
                        metadata.entityName(),
                        namespace,
                        0L,
                        0L,
                        !indexFeaturesDegraded(),
                        true,
                        Instant.now(),
                        "Skipped automatic rebuild because cooldown is active");
            }

            if (migration == null) markIndexFeaturesDegraded();
            if (migration == null) jedis.commands().del(keyStrategy.compositeIndexSignatureKey(namespace));
            compositeState = metadata.compositeExactIndexes().isEmpty() ? CompositeIndexState.NONE : CompositeIndexState.PENDING_REBUILD;
            compositeCheckAt = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            long clearedKeyCount = clearIndexKeys(lockKey);
            long rebuiltEntityCount = 0L;
            String cursor = "0";
            int iterations = 0;
            do {
                if (++iterations > 100_000) throw new IllegalStateException("Index rebuild scan exceeds 100000 iterations; indexes remain degraded");
                redis.clients.jedis.resps.ScanResult<String> scan =
                        jedis.scan(
                                cursor,
                                new redis.clients.jedis.params.ScanParams()
                                        .match(keyStrategy.entityPattern(namespace))
                                        .count(Math.min(1000, Math.max(64, config.plannerStatisticsSampleSize() * 4))));
                for (String entityKey : scan.getResult()) {
                String id = entityKey.substring(keyStrategy.entityPattern(namespace).length() - 1);
                    if (jedis.commands().exists(keyStrategy.tombstoneKey(namespace, id))) {
                        continue;
                    }
                    String payload = jedis.commands().get(entityKey);
                    if (payload == null || payload.isBlank()) {
                        continue;
                    }
                    T entity = codec.fromRedisValue(payload);
                    reindexEntity(entity, false);
                    rebuiltEntityCount++;
                }
                cursor = scan.getCursor();
            } while (!"0".equals(cursor));
            if (migration == null) jedis.commands().set(keyStrategy.compositeIndexSignatureKey(namespace), compositeSignature);
            else migration.publish(keyStrategy.compositeIndexSignatureKey(namespace), compositeSignature);
            compositeState = metadata.compositeExactIndexes().isEmpty() ? CompositeIndexState.NONE : CompositeIndexState.VERIFIED;
            compositeCheckAt = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            if (migration == null) jedis.commands().del(keyStrategy.indexDegradedKey(namespace));
            invalidatePlannerStatistics();
            return new QueryIndexRebuildResult(
                    metadata.entityName(),
                    namespace,
                    rebuiltEntityCount,
                    clearedKeyCount,
                    true,
                    automatic,
                    Instant.now(),
                    note == null ? "" : note);
        } finally {
            indexLock.unlock();
        }
    }

    private void reindexEntity(T entity, boolean respectShedding) {
        reindexEntities(List.of(entity), respectShedding);
    }

    private void reindexEntities(Collection<T> entities, boolean respectShedding) {
        reindexEntities(entities, respectShedding, null);
    }

    private void reindexEntities(Collection<T> entities, boolean respectShedding,
            Map<ID, Map<String, Object>> precomputedColumns) {
        if (entities.size() > 10000) throw new IllegalArgumentException("At most 10000 index rows per batch");
        Map<ID, T> unique = new LinkedHashMap<>();
        for (T entity : entities) if (entity != null) unique.put(metadata.idAccessor().apply(entity), entity);
        if (unique.isEmpty()) return;
        if (respectShedding && shouldShedIndexWrites()) {
            for (ID id : unique.keySet()) removeById(id);
            markIndexFeaturesDegraded();
            return;
        }
        String namespace = metadata.redisNamespace();
        List<ID> ids = new ArrayList<>(unique.keySet());
        List<Map<String, Object>> columns = new ArrayList<>(ids.size());
        List<Response<Map<String, String>>> responses = new ArrayList<>(ids.size());
        List<Response<String>> owners = new ArrayList<>(ids.size());
        try (AbstractPipeline read = jedis.pipeline()) {
            for (ID id : ids) {
                Map<String, Object> values = precomputedColumns == null || com.reactor.cachedb.core.crypto.EncryptedStorageChecks.protectedEntity(metadata)
                        ? codec.toIndexColumns(unique.get(id)) : precomputedColumns.get(id);
                attestLong53(values);
                columns.add(values);
                responses.add(read.hgetAll(keyStrategy.indexMetaKey(namespace, id)));
                if (projectionOwnerNamespace() != null) owners.add(read.get(keyStrategy.versionKey(projectionOwnerNamespace(),id) + ":tenant"));
            }
            read.sync();
        }
        List<RedisIndexMutationPlanner.Plan> plans = new ArrayList<>(ids.size());
        boolean changed = false;
        for (int i = 0; i < ids.size(); i++) {
            Map<String, String> next = new LinkedHashMap<>();
            for (String column : indexedColumns) next.put(column, serializeValue(columns.get(i).get(column)));
            RedisCompositeIndexes.append(keyStrategy, metadata, columns.get(i), next);
            if (!owners.isEmpty()) {
                String owner = owners.get(i).get();
                if (owner == null || owner.isBlank() || owner.length() > 256) throw new IllegalStateException("Projection requires established base tenant ownership");
                next.put(PROJECTION_TENANT_MARKER,owner);
            }
            com.reactor.cachedb.core.crypto.EncryptedStorageChecks.validateIndexColumns(metadata, next);
            com.reactor.cachedb.core.crypto.EncryptedStorageChecks.validateIndexColumns(metadata, responses.get(i).get());
            var plan = RedisIndexMutationPlanner.plan(responses.get(i).get(), next, !respectShedding);
            plans.add(plan);
            changed |= !plan.unchanged();
        }
        if (!changed) return;
        cardinalityCache.clear();
        histogramCache.clear();
        learnedStatsCache.clear();
        Map<String,List<String>> partitions = new LinkedHashMap<>();
        if (supportsPartitionedExactSortIndexes()) {
            for (String partition : partitionedExactSortColumns()) partitions.put(partition,partitionedSortColumns(partition));
        }
        try (AbstractPipeline write = jedis.pipeline()) {
            for (int i = 0; i < ids.size(); i++) {
                var plan = plans.get(i);
                if (plan.unchanged()) continue;
                RedisIndexMutationPlanner.apply(write,keyStrategy,metadata,config,
                        String.valueOf(ids.get(i)),columns.get(i),plan,partitions);
            }
            if (config.plannerStatisticsPersisted()) write.incr(keyStrategy.indexPlannerEpochKey(namespace));
            write.sync();
        }
    }

    private void reindexProjectionWarmEntities(Collection<T> entities) {
        ArrayList<T> candidates = new ArrayList<>(entities.size());
        for (T entity : entities) {
            if (entity != null) {
                candidates.add(entity);
            }
        }
        if (candidates.isEmpty()) {
            return;
        }

        invalidatePlannerStatistics();
        ArrayList<String> idValues = new ArrayList<>(candidates.size());
        ArrayList<Map<String, Object>> entityColumns = new ArrayList<>(candidates.size());
        ArrayList<Response<Map<String, String>>> priorMetaResponses =
                new ArrayList<>(candidates.size());
        String namespace = metadata.redisNamespace();
        try (AbstractPipeline readPipeline = jedis.pipeline()) {
            for (T entity : candidates) {
                ID id = metadata.idAccessor().apply(entity);
                idValues.add(String.valueOf(id));
                Map<String, Object> values = codec.toIndexColumns(entity);
                attestLong53(values);
                entityColumns.add(values);
                priorMetaResponses.add(
                        readPipeline.hgetAll(keyStrategy.indexMetaKey(namespace, id)));
            }
            readPipeline.sync();
        }

        try (AbstractPipeline writePipeline = jedis.pipeline()) {
            for (int index = 0; index < candidates.size(); index++) {
                String idValue = idValues.get(index);
                Map<String, String> priorMetaValues = priorMetaResponses.get(index).get();
                String metaKey = keyStrategy.indexMetaKey(namespace, idValue);
                if (priorMetaValues != null && !priorMetaValues.isEmpty()) {
                    removePartitionedSortIndexes(writePipeline, idValue, priorMetaValues);
                    writePipeline.del(metaKey);
                }
            }

            for (int index = 0; index < candidates.size(); index++) {
                String idValue = idValues.get(index);
                Map<String, Object> columns = entityColumns.get(index);
                LinkedHashMap<String, String> metaValues = new LinkedHashMap<>();
                for (String column : indexedColumns) {
                    metaValues.put(column, serializeValue(columns.get(column)));
                }
                upsertPartitionedSortIndexes(writePipeline, idValue, columns);
                if (!metaValues.isEmpty()) {
                    writePipeline.hset(keyStrategy.indexMetaKey(namespace, idValue), metaValues);
                }
            }
            writePipeline.sync();
        }
    }

    RedisPurgeIndexLayout purgeLayout() {
        var partitions = new LinkedHashMap<String, List<String>>();
        for (String column : partitionedExactSortColumns())
            partitions.put(column, partitionedSortColumns(column));
        return new RedisPurgeIndexLayout(metadata, keyStrategy, config, partitions, compositeFence.identityKeys());
    }

    void purgeInvalidateLocalStatistics() {
        cardinalityCache.clear();
        histogramCache.clear();
        learnedStatsCache.clear();
    }

    public void removeById(Object id) {
        if (!hasCompositeSortIndexes()) { removeByIdUnfenced(id); return; }
        withCompositeMutation(() -> { removeByIdUnfenced(id); return null; });
    }

    private void removeByIdUnfenced(Object id) {
        invalidatePlannerStatistics();
        String idValue = String.valueOf(id);
        Map<String, String> metaValues = jedis.commands().hgetAll(keyStrategy.indexMetaKey(metadata.redisNamespace(), idValue));
        try (AbstractPipeline pipeline = jedis.pipeline()) {
            appendDeleteCommands(pipeline, idValue, metaValues);
            pipeline.sync();
        }
    }

    private void appendDeleteCommands(AbstractPipeline pipeline, String idValue, Map<String, String> metaValues) {
        String metaKey = keyStrategy.indexMetaKey(metadata.redisNamespace(), idValue);
        if (metaValues != null) {
            com.reactor.cachedb.core.crypto.EncryptedStorageChecks.validateIndexColumns(metadata, metaValues);
            RedisCompositeIndexes.validateSortMeta(metaValues);
        }
        if (metaValues != null && !metaValues.isEmpty()) {
            for (Map.Entry<String, String> entry : metaValues.entrySet()) {
                if (entry.getKey().equals(PROJECTION_TENANT_MARKER)) {
                    pipeline.srem(keyStrategy.indexExactKey(metadata.redisNamespace(),PROJECTION_TENANT_MARKER,encodeKeyPart(entry.getValue())),idValue); continue;
                }
                if (entry.getKey().startsWith(RedisCompositeIndexes.MARKER)) {
                    pipeline.srem(entry.getValue(), idValue); continue;
                }
                if (entry.getKey().startsWith(RedisCompositeIndexes.SORT_MARKER)) {
                    pipeline.zrem(RedisCompositeIndexes.sortEntry(entry.getValue()).key(), idValue); continue;
                }
                if (config.exactIndexEnabled()) {
                    pipeline.srem(
                            keyStrategy.indexExactKey(
                                    metadata.redisNamespace(),
                                    entry.getKey(),
                                    encodeKeyPart(entry.getValue())),
                            idValue);
                }
                removeStringValueIndex(
                        pipeline, idValue, entry.getKey(), decodeValue(entry.getValue()));
                pipeline.zrem(
                        keyStrategy.indexSortKey(metadata.redisNamespace(), entry.getKey()),
                        idValue);
            }
            removePartitionedSortIndexes(pipeline, idValue, metaValues);
            pipeline.del(metaKey);
        } else {
            if (!metadata.compositeSortColumns().isEmpty()) markIndexFeaturesDegraded();
            for (String column : metadata.columns()) {
                pipeline.zrem(
                        keyStrategy.indexSortKey(metadata.redisNamespace(), column), idValue);
            }
        }
        pipeline.srem(keyStrategy.indexAllKey(metadata.redisNamespace()), idValue);
    }

    List<List<String>> projectionUpsertCommands(T entity, Map<String,String> prior, String owner) {
        if (!(metadata instanceof ProjectionEntityMetadata<?,?>))
            throw new IllegalStateException("A fenced projection planner requires projection metadata");
        return capturedUpsertCommands(entity,prior,owner);
    }

    List<List<String>> initialUpsertCommands(T entity) {
        if (metadata instanceof ProjectionEntityMetadata<?,?> || !metadata.compositeExactIndexes().isEmpty()
                || !metadata.compositeSortColumns().isEmpty() || !metadata.partitionedSortIndexes().isEmpty())
            throw new com.reactor.cachedb.core.protection.ProtectionException(
                    com.reactor.cachedb.core.protection.ProtectionException.Code.MIGRATION_REQUIRED);
        return capturedUpsertCommands(entity,Map.of(),null);
    }

    private List<List<String>> capturedUpsertCommands(T entity,Map<String,String> prior,String owner) {
        if (shouldShedIndexWrites()) throw new java.util.concurrent.RejectedExecutionException("Projection index writes are being shed");
        var values = codec.toIndexColumns(entity);
        if (values.size()>1024) throw new IllegalArgumentException("Projection has too many index values");
        long contentBytes=0;
        for (Object value:values.values()) {
            if (value instanceof String text) contentBytes+=com.reactor.cachedb.core.codec.Utf8Length.of(text);
            if (contentBytes>RedisProjectionCommandBuffer.MAX_BYTES)
                throw new IllegalArgumentException("Projection index values exceed 16 MiB before encoding");
            if (value instanceof java.math.BigInteger integer && integer.bitLength()>16384
                    || value instanceof java.math.BigDecimal decimal && (decimal.precision()>4096 || Math.abs((long)decimal.scale())>4096))
                throw new IllegalArgumentException("Projection numeric index value exceeds bounded representation");
        }
        attestLong53(values);
        var next = new LinkedHashMap<String,String>();
        for (String column : indexedColumns) next.put(column, serializeValue(values.get(column)));
        RedisCompositeIndexes.append(keyStrategy, metadata, values, next);
        if (projectionOwnerNamespace() != null) {
            if (owner == null || owner.isBlank() || owner.length() > 256)
                throw new IllegalStateException("Projection requires established base tenant ownership");
            next.put(PROJECTION_TENANT_MARKER, owner);
        }
        var partitions = new LinkedHashMap<String,List<String>>();
        if (supportsPartitionedExactSortIndexes())
            for (String partition : partitionedExactSortColumns()) partitions.put(partition, partitionedSortColumns(partition));
        var plan = RedisIndexMutationPlanner.plan(prior, next, true);
        try (var buffer = new RedisProjectionCommandBuffer()) {
            RedisIndexMutationPlanner.apply(buffer,keyStrategy,metadata,config,
                    String.valueOf(metadata.idAccessor().apply(entity)),values,plan,partitions);
            return buffer.commands();
        }
    }

    List<List<String>> bulkDeleteCommands(Object id, Map<String, String> metaValues) {
        if ((metaValues == null || metaValues.isEmpty()) && hasCompositeSortIndexes())
            throw new IllegalStateException("Bulk delete requires verified composite metadata");
        try (var buffer = new RedisDeleteCommandBuffer()) {
            appendDeleteCommands(buffer, String.valueOf(id), metaValues);
            var commands = buffer.commands();
            RedisEncryptedBulkDeleteChecks.validateOwnedIndexCommands(metadata, keyStrategy, commands);
            return commands;
        }
    }

    void bulkDeleteObserved() { invalidatePlannerStatistics(); }

    public List<String> resolveCandidateIds(QuerySpec querySpec) {
        validateProtectedQuery(querySpec);
        maybeRecoverDegradedIndexes(classifyQueryClasses(querySpec));
        if (shouldBypassIndexes(querySpec)) {
            rejectStrictRouteFullScan("query indexes are degraded or reads are being shed");
            return scanAllEntityIds();
        }
        return resolveIndexedAndResidualCandidates(querySpec);
    }

    public java.util.Optional<List<String>> indexOnlyCandidates(QuerySpec querySpec) {
        validateProtectedQuery(querySpec);
        if (metadata.compositeExactIndexes().isEmpty() && metadata.compositeSortColumns().isEmpty()
                && !com.reactor.cachedb.core.crypto.EncryptedStorageChecks.protectedEntity(metadata)) {
            QueryNode root = querySpec.rootGroup();
            while (root instanceof QueryGroup group && group.operator() != null && group.nodes().size() == 1)
                root = group.nodes().getFirst();
            boolean emptyRoot = root instanceof QueryGroup group
                    && group.operator() == QueryGroupOperator.AND && group.nodes().isEmpty();
            String membershipKey = emptyRoot ? keyStrategy.indexAllKey(metadata.redisNamespace()) : exactKey(root);
            if (membershipKey != null) {
                if (producerGuard != null
                        && (producerGuard.shouldShedQueryIndexReads(metadata.redisNamespace(), classifyQueryClasses(querySpec))
                        || (!emptyRoot && producerGuard.shouldShedQueryIndexReads(metadata.redisNamespace(), List.of()))))
                    return java.util.Optional.empty();
                var fetched = jedis.guardedSetMembers(keyStrategy.indexDegradedKey(metadata.redisNamespace()),
                        membershipKey, config.maxMaterializedCandidateIds());
                if (fetched.isPresent()) {
                    if (fetched.get().degraded()) return java.util.Optional.empty();
                    enforceCandidateLimit(fetched.get().members(), "guarded candidate set");
                    return java.util.Optional.of(fetched.get().members());
                }
            }
        }
        if (shouldBypassIndexes(querySpec)) return java.util.Optional.empty();
        if (!querySpec.rootGroup().nodes().isEmpty() && !fullyIndexed(querySpec.rootGroup()))
            return java.util.Optional.empty();
        Set<String> ids = resolveIndexedCandidates(querySpec.rootGroup()).candidates();
        if (ids == null) ids = boundedSetMembers(keyStrategy.indexAllKey(metadata.redisNamespace()), "aggregate active set");
        return java.util.Optional.of(new ArrayList<>(ids));
    }

    public boolean hasIndexedColumn(String column) { return indexedColumns.contains(column); }

    void validateCompositeValues(Map<String, Object> columns) {
        if (!metadata.compositeExactIndexes().isEmpty()) RedisCompositeIndexes.append(keyStrategy,metadata,columns,new LinkedHashMap<>());
        attestLong53(columns);
    }

    private void rejectStrictRouteFullScan(String reason) {
        RouteCacheContract contract = RouteCacheContext.currentContract();
        if (contract == null || contract.strictMode() != RouteCacheStrictMode.FAIL_FAST) {
            return;
        }
        throw new IllegalStateException(
                "Route "
                        + contract.routeName()
                        + " cannot fall back to a Redis full-scan because "
                        + reason
                        + ". Rebuild query indexes, use a registered projection route, or lower the"
                        + " route contract before serving production traffic.");
    }

    public void warm(QuerySpec querySpec) {
        validateProtectedQuery(querySpec);
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
        validateProtectedQuery(querySpec);
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
        validateProtectedQuery(querySpec);
        if (!supportsSortedIndexScan(querySpec)) {
            return resolveCandidateIds(querySpec);
        }
        List<String> partitionedExactSorted = resolveSortedIdsFromPartitionedExactSort(querySpec);
        if (partitionedExactSorted != null) {
            return partitionedExactSorted;
        }
        List<String> candidateScoreOrdered = resolveSortedIdsFromCandidateScores(querySpec);
        if (candidateScoreOrdered != null) {
            return candidateScoreOrdered;
        }
        if (canUseLazySortedScan(querySpec)) {
            return resolveSortedIdsLazily(querySpec);
        }

        QuerySort sort = querySpec.sorts().get(0);
        LinkedHashSet<String> eligibleIds =
                new LinkedHashSet<>(resolveIndexedAndResidualCandidates(querySpec));
        if (eligibleIds.isEmpty()) {
            return List.of();
        }

        int targetCount = querySpec.offset() + querySpec.limit();
        ArrayList<String> ordered = new ArrayList<>(Math.min(targetCount, eligibleIds.size()));
        int rank = 0;
        int chunkSize = Math.max(querySpec.limit() * 4, 64);
        String sortKey = keyStrategy.indexSortKey(metadata.redisNamespace(), sort.column());
        while (ordered.size() < targetCount) {
            List<String> batch =
                    sort.direction() == com.reactor.cachedb.core.query.QuerySortDirection.DESC
                            ? jedis.commands().zrevrange(sortKey, rank, rank + chunkSize - 1)
                            : jedis.commands().zrange(sortKey, rank, rank + chunkSize - 1);
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
        validateProtectedQuery(querySpec);
        if (!shouldUsePrimarySortedScan(querySpec) || querySpec.sorts().isEmpty()) {
            return resolveCandidateIds(querySpec);
        }
        if (querySpec.sorts().size() == 1) {
            return resolveSortedIds(querySpec);
        }
        List<String> partitionedExactSorted =
                resolvePrimarySortedIdsFromPartitionedExactSort(querySpec);
        if (partitionedExactSorted != null) {
            return partitionedExactSorted;
        }
        List<String> candidateScoreOrdered = resolvePrimarySortedIdsFromCandidateScores(querySpec);
        if (candidateScoreOrdered != null) {
            return candidateScoreOrdered;
        }
        if (canUseLazySortedScan(querySpec)) {
            return resolvePrimarySortedIdsLazily(querySpec);
        }

        QuerySort primarySort = querySpec.sorts().get(0);
        LinkedHashSet<String> eligibleIds =
                new LinkedHashSet<>(resolveIndexedAndResidualCandidates(querySpec));
        if (eligibleIds.isEmpty()) {
            return List.of();
        }

        int targetCount = querySpec.offset() + querySpec.limit();
        if (targetCount <= 0) {
            return List.of();
        }

        ArrayList<String> ordered =
                new ArrayList<>(
                        Math.min(
                                targetCount + Math.max(querySpec.limit(), 16), eligibleIds.size()));
        String sortKey = keyStrategy.indexSortKey(metadata.redisNamespace(), primarySort.column());
        int rank = 0;
        int chunkSize = Math.max(querySpec.limit() * 4, 64);
        Double boundaryScore = null;
        while (true) {
            List<Tuple> batch =
                    primarySort.direction()
                                    == com.reactor.cachedb.core.query.QuerySortDirection.DESC
                            ? jedis.commands()
                                    .zrevrangeWithScores(sortKey, rank, rank + chunkSize - 1)
                            : jedis.commands()
                                    .zrangeWithScores(sortKey, rank, rank + chunkSize - 1);
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
                if (boundaryScore != null
                        && Double.compare(score, boundaryScore) != 0
                        && ordered.size() >= targetCount) {
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
            List<Tuple> batch =
                    sort.direction() == com.reactor.cachedb.core.query.QuerySortDirection.DESC
                            ? jedis.commands()
                                    .zrevrangeWithScores(sortKey, rank, rank + chunkSize - 1)
                            : jedis.commands()
                                    .zrangeWithScores(sortKey, rank, rank + chunkSize - 1);
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
            List<Tuple> batch =
                    primarySort.direction()
                                    == com.reactor.cachedb.core.query.QuerySortDirection.DESC
                            ? jedis.commands()
                                    .zrevrangeWithScores(sortKey, rank, rank + chunkSize - 1)
                            : jedis.commands()
                                    .zrangeWithScores(sortKey, rank, rank + chunkSize - 1);
            if (batch == null || batch.isEmpty()) {
                break;
            }

            boolean completedBoundaryCoverage = false;
            BatchFilterResult filtered = filterOrderedBatch(batch, querySpec.rootGroup(), plan);
            List<String> matchedIds = filtered.matchedIds();
            Set<String> matchedIdSet =
                    matchedIds.isEmpty() ? Set.of() : new LinkedHashSet<>(matchedIds);
            for (Tuple tuple : batch) {
                String id = tuple.getElement();
                if (!matchedIdSet.contains(id)) {
                    continue;
                }

                double score = tuple.getScore();
                if (boundaryScore != null
                        && Double.compare(score, boundaryScore) != 0
                        && ordered.size() >= targetCount) {
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

    private List<String> resolveSortedIdsFromPartitionedExactSort(QuerySpec querySpec) {
        PartitionedExactSortPlan plan = buildPartitionedExactSortPlan(querySpec);
        if (plan == null || querySpec.sorts().size() != 1) {
            return null;
        }
        int targetCount = querySpec.offset() + querySpec.limit();
        if (targetCount <= 0) {
            return List.of();
        }
        return plan.sort().direction() == com.reactor.cachedb.core.query.QuerySortDirection.DESC
                ? jedis.commands()
                        .zrevrange(
                                plan.sortKey(),
                                querySpec.offset(),
                                querySpec.offset() + querySpec.limit() - 1)
                : jedis.commands()
                        .zrange(
                                plan.sortKey(),
                                querySpec.offset(),
                                querySpec.offset() + querySpec.limit() - 1);
    }

    public java.util.Optional<List<String>> resolveOrderedPayloads(QuerySpec querySpec) {
        validateProtectedQuery(querySpec);
        return resolveOrderedPayloadWindow(querySpec)
                .map(values -> values.stream().map(RedisClientOperations.OrderedPayload::payload).toList());
    }

    public java.util.Optional<List<RedisClientOperations.OrderedPayload>> resolveOrderedPayloadWindow(QuerySpec querySpec) {
        validateProtectedQuery(querySpec);
        if (querySpec == null || querySpec.sorts().size() != 1 || shouldBypassIndexes(querySpec))
            return java.util.Optional.empty();
        var plan = buildPartitionedExactSortPlan(querySpec);
        String key = plan == null ? null : plan.sortKey();
        if (key == null && querySpec.rootGroup().nodes().isEmpty() && supportsSortedIndexScan(querySpec))
            key = keyStrategy.indexSortKey(metadata.redisNamespace(), querySpec.sorts().get(0).column());
        if (key == null) return java.util.Optional.empty();
        return jedis.orderedPayloadWindow(key, keyStrategy.tombstoneKey(metadata.redisNamespace(), ""),
                keyStrategy.entityKey(metadata.redisNamespace(), ""),
                querySpec.sorts().get(0).direction() == com.reactor.cachedb.core.query.QuerySortDirection.DESC,
                querySpec.offset(), querySpec.limit());
    }

    private List<String> resolvePrimarySortedIdsFromPartitionedExactSort(QuerySpec querySpec) {
        PartitionedExactSortPlan plan = buildPartitionedExactSortPlan(querySpec);
        if (plan == null || querySpec.sorts().size() <= 1) {
            return null;
        }
        int targetCount = querySpec.offset() + querySpec.limit();
        if (targetCount <= 0) {
            return List.of();
        }

        ArrayList<String> ordered = new ArrayList<>(targetCount + Math.max(querySpec.limit(), 16));
        int rank = 0;
        int chunkSize = Math.max(querySpec.limit() * 4, 64);
        Double boundaryScore = null;
        while (true) {
            List<Tuple> batch =
                    plan.sort().direction()
                                    == com.reactor.cachedb.core.query.QuerySortDirection.DESC
                            ? jedis.commands()
                                    .zrevrangeWithScores(plan.sortKey(), rank, rank + chunkSize - 1)
                            : jedis.commands()
                                    .zrangeWithScores(plan.sortKey(), rank, rank + chunkSize - 1);
            if (batch == null || batch.isEmpty()) {
                break;
            }

            boolean completedBoundaryCoverage = false;
            for (Tuple tuple : batch) {
                double score = tuple.getScore();
                if (boundaryScore != null
                        && Double.compare(score, boundaryScore) != 0
                        && ordered.size() >= targetCount) {
                    completedBoundaryCoverage = true;
                    break;
                }
                ordered.add(tuple.getElement());
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

    private PartitionedExactSortPlan buildPartitionedExactSortPlan(QuerySpec querySpec) {
        if (querySpec == null || querySpec.sorts().isEmpty() || querySpec.filters().size() != 1) {
            return null;
        }
        if (querySpec.rootGroup().operator() != QueryGroupOperator.AND) {
            return null;
        }
        QuerySort primarySort = querySpec.sorts().get(0);
        if (!supportsScoreOrdering(primarySort.column())) {
            return null;
        }
        QueryFilter filter = querySpec.filters().get(0);
        if (filter.column().contains(".") || filter.operator() != QueryOperator.EQ) {
            return null;
        }
        if (!supportsPartitionedPair(filter.column(), primarySort.column())) {
            return null;
        }
        return new PartitionedExactSortPlan(
                filter.column(),
                filter.value(),
                primarySort,
                keyStrategy.indexPartitionSortKey(
                        metadata.redisNamespace(),
                        filter.column(),
                        encodeKeyPart(serializeValue(com.reactor.cachedb.core.crypto.EncryptedStorageChecks.indexLiteral(metadata, codec, filter.column(), filter.value()))),
                        primarySort.column()));
    }

    public <K> Map<K, List<String>> resolvePartitionedIds(
            PartitionedQuerySpec<K> partitionedQuery) {
        if (partitionedQuery != null) {
            com.reactor.cachedb.core.crypto.EncryptedStorageChecks.rejectProtectedRole(metadata, partitionedQuery.partitionColumn());
            for (var sort : partitionedQuery.sorts()) com.reactor.cachedb.core.crypto.EncryptedStorageChecks.rejectProtectedRole(metadata, sort.column());
        }
        if (partitionedQuery == null || partitionedQuery.partitionValues().isEmpty()) {
            return Map.of();
        }
        QuerySort primarySort = partitionedQuery.sorts().get(0);
        if (!supportsPartitionedPair(partitionedQuery.partitionColumn(), primarySort.column())) {
            throw new IllegalStateException(
                    "No partitioned sorted index is declared for "
                            + metadata.entityName()
                            + " on "
                            + partitionedQuery.partitionColumn()
                            + " ordered by "
                            + primarySort.column());
        }
        LinkedHashMap<K, String> sortKeys =
                new LinkedHashMap<>(partitionedQuery.partitionValues().size());
        for (K partitionValue : partitionedQuery.partitionValues()) {
            sortKeys.put(
                    partitionValue,
                    keyStrategy.indexPartitionSortKey(
                            metadata.redisNamespace(),
                            partitionedQuery.partitionColumn(),
                            encodeKeyPart(serializeValue(partitionValue)),
                            primarySort.column()));
        }
        return partitionedQuery.sorts().size() == 1
                ? resolveSingleSortPartitionedIds(
                        sortKeys, primarySort, partitionedQuery.limitPerPartition())
                : resolveMultiSortPartitionedIds(
                        sortKeys, primarySort, partitionedQuery.limitPerPartition());
    }

    private <K> Map<K, List<String>> resolveSingleSortPartitionedIds(
            Map<K, String> sortKeys, QuerySort primarySort, int limit) {
        LinkedHashMap<K, Response<List<String>>> responses = new LinkedHashMap<>(sortKeys.size());
        try (AbstractPipeline pipeline = jedis.pipeline()) {
            sortKeys.forEach(
                    (partition, sortKey) ->
                            responses.put(
                                    partition,
                                    primarySort.direction()
                                                    == com.reactor.cachedb.core.query
                                                            .QuerySortDirection.DESC
                                            ? pipeline.zrevrange(sortKey, 0, limit - 1L)
                                            : pipeline.zrange(sortKey, 0, limit - 1L)));
            pipeline.sync();
        }
        LinkedHashMap<K, List<String>> resolved = new LinkedHashMap<>(responses.size());
        responses.forEach(
                (partition, response) -> resolved.put(partition, List.copyOf(response.get())));
        return Collections.unmodifiableMap(resolved);
    }

    private <K> Map<K, List<String>> resolveMultiSortPartitionedIds(
            Map<K, String> sortKeys, QuerySort primarySort, int limit) {
        LinkedHashMap<K, Response<List<Tuple>>> headResponses =
                new LinkedHashMap<>(sortKeys.size());
        try (AbstractPipeline pipeline = jedis.pipeline()) {
            sortKeys.forEach(
                    (partition, sortKey) ->
                            headResponses.put(
                                    partition,
                                    primarySort.direction()
                                                    == com.reactor.cachedb.core.query
                                                            .QuerySortDirection.DESC
                                            ? pipeline.zrevrangeWithScores(sortKey, 0, limit - 1L)
                                            : pipeline.zrangeWithScores(sortKey, 0, limit - 1L)));
            pipeline.sync();
        }

        LinkedHashMap<K, List<Tuple>> heads = new LinkedHashMap<>(headResponses.size());
        headResponses.forEach(
                (partition, response) -> heads.put(partition, List.copyOf(response.get())));
        LinkedHashMap<K, Response<List<String>>> boundaryResponses = new LinkedHashMap<>();
        try (AbstractPipeline pipeline = jedis.pipeline()) {
            heads.forEach(
                    (partition, tuples) -> {
                        if (tuples.size() >= limit) {
                            double boundaryScore = tuples.get(tuples.size() - 1).getScore();
                            boundaryResponses.put(
                                    partition,
                                    pipeline.zrangeByScore(
                                            sortKeys.get(partition), boundaryScore, boundaryScore));
                        }
                    });
            pipeline.sync();
        }

        LinkedHashMap<K, List<String>> resolved = new LinkedHashMap<>(heads.size());
        heads.forEach(
                (partition, tuples) -> {
                    LinkedHashSet<String> candidates =
                            tuples.stream()
                                    .map(Tuple::getElement)
                                    .collect(
                                            java.util.stream.Collectors.toCollection(
                                                    LinkedHashSet::new));
                    Response<List<String>> boundary = boundaryResponses.get(partition);
                    if (boundary != null) {
                        candidates.addAll(boundary.get());
                    }
                    resolved.put(partition, List.copyOf(candidates));
                });
        return Collections.unmodifiableMap(resolved);
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
        int baseLimit = Math.max(128, Math.min(512, targetCount * 8));
        if (metadata instanceof ProjectionEntityMetadata<?, ?>) {
            return Math.max(baseLimit, Math.max(1024, Math.min(4096, targetCount * 16)));
        }
        return baseLimit;
    }

    private List<String> orderCandidateIdsByScores(
            Collection<String> candidateIds, List<QuerySort> sorts) {
        ArrayList<String> orderedIds = new ArrayList<>(new LinkedHashSet<>(candidateIds));
        if (orderedIds.size() <= 1) {
            return List.copyOf(orderedIds);
        }

        LinkedHashMap<String, double[]> scoresById = loadCandidateScores(orderedIds, sorts);
        orderedIds.sort((left, right) -> compareCandidateScores(left, right, scoresById, sorts));
        return List.copyOf(orderedIds);
    }

    private LinkedHashMap<String, double[]> loadCandidateScores(
            List<String> orderedIds, List<QuerySort> sorts) {
        ArrayList<List<Response<Double>>> responses = new ArrayList<>(sorts.size());
        try (AbstractPipeline pipeline = jedis.pipeline()) {
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
                scoresById.get(orderedIds.get(idIndex))[sortIndex] =
                        score == null ? missingSortScore(sort) : score;
            }
        }
        return scoresById;
    }

    private int compareCandidateScores(
            String left, String right, Map<String, double[]> scoresById, List<QuerySort> sorts) {
        double[] leftScores = scoresById.get(left);
        double[] rightScores = scoresById.get(right);
        for (int index = 0; index < sorts.size(); index++) {
            int result = Double.compare(leftScores[index], rightScores[index]);
            if (sorts.get(index).direction()
                    == com.reactor.cachedb.core.query.QuerySortDirection.DESC) {
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

    private BatchFilterResult filterOrderedBatch(
            List<Tuple> batch, QueryNode filterNode, LazySortedScanPlan plan) {
        if (batch.isEmpty()) {
            return new BatchFilterResult(List.of(), false);
        }
        if (!plan.requiresDecode()) {
            ArrayList<String> matches = new ArrayList<>(batch.size());
            boolean stopScanning = false;
            for (Tuple tuple : batch) {
                double score = tuple.getScore();
                if (plan.scoreRange() != null
                        && plan.scoreRange().canStop(score, plan.direction())) {
                    stopScanning = true;
                    break;
                }
                if (plan.scoreRange() != null && !plan.scoreRange().matches(score)) {
                    continue;
                }
                if (plan.membershipIds() != null
                        && !plan.membershipIds().contains(tuple.getElement())) {
                    continue;
                }
                matches.add(tuple.getElement());
            }
            return new BatchFilterResult(List.copyOf(matches), stopScanning);
        }

        List<String> orderedIds = batch.stream().map(Tuple::getElement).toList();
        List<String> keys =
                orderedIds.stream()
                        .map(id -> keyStrategy.entityKey(metadata.redisNamespace(), id))
                        .toList();
        List<String> tombstoneKeys =
                orderedIds.stream()
                        .map(id -> keyStrategy.tombstoneKey(metadata.redisNamespace(), id))
                        .toList();
        List<List<String>> values = mgetBatchEntityAndTombstones(keys, tombstoneKeys);
        List<String> payloads = values.get(0);
        List<String> tombstones = values.get(1);
        ArrayList<String> matches = new ArrayList<>(orderedIds.size());
        boolean stopScanning = false;
        for (int index = 0; index < orderedIds.size(); index++) {
            Tuple tuple = batch.get(index);
            if (plan.scoreRange() != null
                    && plan.scoreRange().canStop(tuple.getScore(), plan.direction())) {
                stopScanning = true;
                break;
            }
            String payload = payloads.get(index);
            if (payload == null || tombstones.get(index) != null) {
                continue;
            }
            T entity = codec.fromRedisValue(payload);
            if (queryEvaluator.matches(entity, codec, filterNode)) {
                matches.add(orderedIds.get(index));
            }
        }
        return new BatchFilterResult(List.copyOf(matches), stopScanning);
    }

    private List<List<String>> mgetBatchEntityAndTombstones(
            List<String> keys, List<String> tombstoneKeys) {
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
            if (filter.column().equals(primarySort.column())
                    && isRangeOperator(filter.operator())
                    && supportsScoreOrdering(filter.column())) {
                Double score =
                        RedisScoreSupport.toScore(
                                metadata.columnTypes(), filter.column(), filter.value());
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
        ScoreRange base =
                current == null
                        ? new ScoreRange(
                                Double.NEGATIVE_INFINITY, true, Double.POSITIVE_INFINITY, true)
                        : current;
        return switch (operator) {
            case GT ->
                    new ScoreRange(
                            Math.max(base.min(), score), false, base.max(), base.maxInclusive());
            case GTE ->
                    new ScoreRange(
                            Math.max(base.min(), score),
                            base.min() == score ? base.minInclusive() : true,
                            base.max(),
                            base.maxInclusive());
            case LT ->
                    new ScoreRange(
                            base.min(), base.minInclusive(), Math.min(base.max(), score), false);
            case LTE ->
                    new ScoreRange(
                            base.min(),
                            base.minInclusive(),
                            Math.min(base.max(), score),
                            base.max() == score ? base.maxInclusive() : true);
            default -> base;
        };
    }

    public QueryExplainPlan explain(QuerySpec querySpec) {
        validateProtectedQuery(querySpec);
        maybeRecoverDegradedIndexes(classifyQueryClasses(querySpec));
        List<QueryExplainRelationState> relationStates = collectRelationStates(querySpec);
        List<String> warnings = collectExplainWarnings(relationStates);
        if (shouldBypassIndexes(querySpec)) {
            List<String> candidates = scanAllEntityIds();
            List<QueryExplainStep> steps =
                    List.of(
                            new QueryExplainStep(
                                    "FILTER",
                                    querySpec.rootGroup().nodes().isEmpty()
                                            ? "<none>"
                                            : describeNode(querySpec.rootGroup()),
                                    "DEGRADED_FULL_SCAN",
                                    false,
                                    candidates.size(),
                                    Math.max(1, candidates.size()),
                                    "Query index features are shed or marked degraded; repository"
                                            + " falls back to entity key scan and residual"
                                            + " evaluation"));
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
                    querySpec.sorts().isEmpty()
                            ? "NONE"
                            : (querySpec.sorts().size() > 1
                                    ? "IN_MEMORY_MULTI_SORT"
                                    : "IN_MEMORY_SORT"),
                    0,
                    List.copyOf(querySpec.fetchPlan().includes()),
                    steps,
                    relationStates,
                    warnings);
        }
        warm(querySpec);
        List<QueryExplainStep> steps = new ArrayList<>();
        Resolution resolution = resolveIndexedCandidates(querySpec.rootGroup());
        boolean fullyIndexed = fullyIndexed(querySpec.rootGroup());
        Set<String> candidates = resolution.candidates();
        SortPlan sortPlan = chooseSortPlan(querySpec);

        if (querySpec.rootGroup().nodes().isEmpty()) {
            steps.add(
                    new QueryExplainStep(
                            "FILTER",
                            "<none>",
                            "FULL_SET_SCAN",
                            false,
                            Math.toIntExact(
                                    jedis.commands()
                                            .scard(
                                                    keyStrategy.indexAllKey(
                                                            metadata.redisNamespace()))),
                            Math.toIntExact(
                                    jedis.commands()
                                            .scard(
                                                    keyStrategy.indexAllKey(
                                                            metadata.redisNamespace()))),
                            "No filters were supplied; candidate set starts from the full entity"
                                    + " index"));
            fullyIndexed = false;
        }

        appendExplainSteps(querySpec.rootGroup(), steps, candidates);

        if (!querySpec.sorts().isEmpty()) {
            steps.add(
                    new QueryExplainStep(
                            "SORT",
                            querySpec.sorts().stream()
                                    .map(this::describeSort)
                                    .collect(java.util.stream.Collectors.joining(", ")),
                            sortPlan.strategy(),
                            sortPlan.usesSortedIndex(),
                            candidates == null ? 0 : candidates.size(),
                            sortPlan.estimatedCost(),
                            sortPlan.usesSortedIndex()
                                    ? "Redis sorted index scan will page through the sort zset"
                                            + " instead of sorting materialized entities"
                                    : querySpec.sorts().size() > 1
                                            ? "Multiple sort keys require an in-memory comparator"
                                                    + " after candidate materialization"
                                            : "Current implementation materializes matched entities"
                                                    + " and sorts them in memory"));
        }

        if (!querySpec.fetchPlan().includes().isEmpty()) {
            for (String relationName : querySpec.fetchPlan().includes()) {
                QueryExplainRelationState relationState =
                        describeFetchRelationState(relationName, querySpec.fetchPlan());
                steps.add(
                        new QueryExplainStep(
                                "FETCH",
                                relationState.relationName(),
                                relationState.status(),
                                relationState.indexed(),
                                candidates == null ? 0 : candidates.size(),
                                Math.max(1, candidates == null ? 0 : candidates.size()),
                                relationState.detail()));
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
                warnings);
    }

    public boolean fullyIndexed(QueryFilter filter) {
        validateProtectedFilter(filter);
        if (shouldBypassIndexes()) {
            return false;
        }
        if (filter.column().contains(".")) {
            return metadata.relations().stream()
                    .anyMatch(
                            candidate ->
                                    candidate.name().equals(filter.column().split("\\.", 2)[0]));
        }
        if (!indexedColumns.contains(filter.column())) return false;
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
        validateProtectedQuery(querySpec);
        maybeRecoverDegradedIndexes(classifyQueryClasses(querySpec));
        if (shouldBypassIndexes(querySpec)) {
            return false;
        }
        if (buildPartitionedExactSortPlan(querySpec) != null && querySpec.sorts().size() == 1) {
            return true;
        }
        SortPlan sortPlan = chooseSortPlan(querySpec);
        return sortPlan.usesSortedIndex() && querySpec.sorts().size() == 1;
    }

    public boolean shouldUsePrimarySortedScan(QuerySpec querySpec) {
        validateProtectedQuery(querySpec);
        if (buildPartitionedExactSortPlan(querySpec) != null) {
            return true;
        }
        SortPlan sortPlan = chooseSortPlan(querySpec);
        return sortPlan.usesSortedIndex();
    }

    public boolean resolvesCompleteSortOrder(QuerySpec querySpec, int candidateCount) {
        validateProtectedQuery(querySpec);
        return canUseCandidateScoreSort(querySpec)
                && candidateCount <= maxCandidateScoreSortSize(querySpec);
    }

    public SortPlan chooseSortPlan(QuerySpec querySpec) {
        validateProtectedQuery(querySpec);
        maybeRecoverDegradedIndexes(classifyQueryClasses(querySpec));
        if (querySpec.sorts().isEmpty()) {
            return new SortPlan("NONE", 0, false);
        }
        if (shouldBypassIndexes(querySpec)) {
            return new SortPlan(
                    querySpec.sorts().size() > 1 ? "IN_MEMORY_MULTI_SORT" : "IN_MEMORY_SORT",
                    estimateSortCost(querySpec),
                    false);
        }
        PartitionedExactSortPlan partitionedExactSortPlan =
                buildPartitionedExactSortPlan(querySpec);
        if (partitionedExactSortPlan != null) {
            return new SortPlan(
                    querySpec.sorts().size() == 1
                            ? "PARTITIONED_SORTED_INDEX_SCAN"
                            : "PARTITIONED_PRIMARY_SORT_WITH_TIEBREAK",
                    Math.max(1L, estimateSortCost(querySpec) / 3L),
                    true);
        }
        int sortablePrefixLength = sortablePrefixLength(querySpec.sorts());
        if (sortablePrefixLength == 0) {
            return new SortPlan(
                    querySpec.sorts().size() > 1 ? "IN_MEMORY_MULTI_SORT" : "IN_MEMORY_SORT",
                    estimateSortCost(querySpec),
                    false);
        }
        if (querySpec.sorts().size() == 1) {
            return new SortPlan("SORTED_INDEX_SCAN", estimateSortCost(querySpec) / 2L + 1L, true);
        }
        return new SortPlan("PRIMARY_SORT_INDEX_WITH_TIEBREAK", estimateSortCost(querySpec), true);
    }

    public QueryCardinalityEstimate estimate(QueryFilter filter) {
        validateProtectedFilter(filter);
        return estimateFilter(filter);
    }

    private int sortablePrefixLength(List<QuerySort> sorts) {
        if (sorts.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (QuerySort sort : sorts) {
            if (!indexedColumns.contains(sort.column())
                    || !config.rangeIndexEnabled()
                    || !supportsScoreOrdering(sort.column())) {
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
        if (!indexedColumns.contains(filter.column())) return null;
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
            candidates =
                    boundedSetMembers(
                            keyStrategy.indexAllKey(metadata.redisNamespace()),
                            "full entity index");
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
        boolean fullyIndexed = fullyIndexed(group);
        if (config.exactIndexEnabled() && compositeIndexState() == CompositeIndexState.VERIFIED) {
            var equals = new LinkedHashMap<String, Object>();
            for (var node : group.nodes()) if (node instanceof QueryFilter filter && filter.operator() == QueryOperator.EQ && filter.value() != null)
                equals.putIfAbsent(filter.column(), filter.value());
            var best = metadata.compositeExactIndexes().entrySet().stream()
                    .filter(index -> equals.keySet().containsAll(index.getValue()))
                    .sorted(java.util.Comparator.<Map.Entry<String, List<String>>>comparingInt(index -> -index.getValue().size()).thenComparing(Map.Entry::getKey))
                    .findFirst();
            if (best.isPresent()) {
                var index = best.get();
                String key = RedisCompositeIndexes.key(keyStrategy, metadata.redisNamespace(), index.getKey(), index.getValue(), equals);
                Set<String> candidates = jedis.smembersBatch(List.of(key), config.maxMaterializedCandidateIds()).get(key);
                for (var node : group.nodes()) {
                    if (candidates.isEmpty()) break;
                    if (node instanceof QueryFilter filter && filter.operator() == QueryOperator.EQ
                            && index.getValue().contains(filter.column()) && java.util.Objects.equals(equals.get(filter.column()), filter.value())) continue;
                    var resolution = resolveIndexedCandidates(node);
                    if (resolution.candidates() != null) candidates = intersect(candidates, resolution.candidates());
                }
                return new Resolution(candidates, fullyIndexed);
            }
        }
        ArrayList<QueryNode> orderedChildren = new ArrayList<>(group.nodes());
        orderedChildren.sort(java.util.Comparator.comparingLong(this::estimateNodeCardinality));
        Set<String> candidates = null;
        int start = 0;
        if (orderedChildren.size() >= 2 && exactKey(orderedChildren.get(0)) != null
                && estimateNodeCardinality(orderedChildren.get(0)) > 128) {
            var keys = new ArrayList<String>();
            while (start < orderedChildren.size() && keys.size() < 64) {
                String key = exactKey(orderedChildren.get(start));
                if (key == null) break;
                keys.add(key); start++;
            }
            var intersection = jedis.intersectSets(keys, config.maxMaterializedCandidateIds());
            if (intersection.isPresent()) candidates = intersection.get();
            else start = 0;
        }
        for (int i = start; i < orderedChildren.size(); i++) {
            if (candidates != null && candidates.isEmpty()) return new Resolution(Set.of(), fullyIndexed);
            QueryNode child = orderedChildren.get(i);
            String key = exactKey(child);
            if (candidates != null && candidates.size() <= 128 && key != null) {
                candidates = jedis.probeMembers(key, candidates);
                continue;
            }
            Resolution resolution = resolveIndexedCandidates(child);
            if (resolution.candidates() != null) candidates = intersect(candidates, resolution.candidates());
        }
        return new Resolution(candidates, fullyIndexed);
    }

    private String exactKey(QueryNode node) {
        if (!(node instanceof QueryFilter filter) || filter.operator() != QueryOperator.EQ
                || !config.exactIndexEnabled() || !indexedColumns.contains(filter.column())
                || filter.column().contains(".")) return null;
        return keyStrategy.indexExactKey(metadata.redisNamespace(), filter.column(),
                encodeKeyPart(serializeValue(com.reactor.cachedb.core.crypto.EncryptedStorageChecks.indexLiteral(metadata, codec, filter.column(), filter.value()))));
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
            enforceCandidateLimit(candidates, "OR query union");
        }
        return new Resolution(candidates, fullyIndexed);
    }

    private String describeFilter(QueryFilter filter) {
        if (com.reactor.cachedb.core.crypto.EncryptedStorageChecks.descriptor(metadata, filter.column()) != null)
            return filter.column() + " EQ " + com.reactor.cachedb.core.crypto.EncryptedStorageChecks.indexLiteral(metadata, codec, filter.column(), filter.value());
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
            return "Relation filter delegates to target entity indexes before mapping owner ids,"
                    + " selectivity="
                    + estimate.selectivityRatio()
                    + ", sample="
                    + estimate.sampledCardinality()
                    + ", hops="
                    + Math.max(1, hopCount);
        }
        if (isRangeOperator(filter.operator())) {
            if (!estimate.histogram().isEmpty()) {
                return "Redis range estimate "
                        + estimate.estimatedCardinality()
                        + " selectivity="
                        + estimate.selectivityRatio()
                        + " sample="
                        + estimate.sampledCardinality()
                        + " with histogram buckets "
                        + estimate.histogram();
            }
        }
        return "Redis index produced "
                + indexedMatches.size()
                + " direct candidate ids, selectivity="
                + estimate.selectivityRatio()
                + ", sample="
                + estimate.sampledCardinality();
    }

    private String describeSort(QuerySort sort) {
        return sort.column() + " " + sort.direction().name();
    }

    private String sortStrategy(List<QuerySort> sorts) {
        boolean indexedSorts =
                sorts.stream()
                        .allMatch(
                                sort ->
                                        metadata.columns().contains(sort.column())
                                                && config.rangeIndexEnabled()
                                                && supportsScoreOrdering(sort.column()));
        return indexedSorts && sorts.size() == 1
                ? "SORTED_INDEX_SCAN"
                : indexedSorts ? "IN_MEMORY_SORT_WITH_RANGE_INDEX_AVAILABLE" : "IN_MEMORY_SORT";
    }

    private void validateProtectedFilter(QueryFilter filter) {
        com.reactor.cachedb.core.crypto.EncryptedStorageChecks.validateFilter(metadata, filter);
        if (filter != null) rejectEncryptedRelationPath(filter.column());
    }

    private void rejectEncryptedRelationPath(String column) {
        if (column == null || !column.contains(".")) return;
        String relationName = column.substring(0, column.indexOf('.'));
        if (entityRegistry != null) for (var relation : metadata.relations()) if (relation.name().equals(relationName)) {
            var target = entityRegistry.find(relation.targetEntity());
            if (target.isPresent() && com.reactor.cachedb.core.crypto.EncryptedStorageChecks.protectedEntity(target.get().metadata()))
                throw com.reactor.cachedb.core.crypto.EncryptedCodecSupport.invalidValue();
        }
    }

    private void validateProtectedQuery(QuerySpec query) {
        com.reactor.cachedb.core.crypto.EncryptedStorageChecks.validateQuery(metadata, query);
        if (query != null) {
            for (var filter : query.filters()) validateProtectedFilter(filter);
            for (var sort : query.sorts()) rejectEncryptedRelationPath(sort.column());
        }
    }

    private Set<String> membersForExact(String column, Object value) {
        value = com.reactor.cachedb.core.crypto.EncryptedStorageChecks.indexLiteral(metadata, codec, column, value);
        if (!config.exactIndexEnabled()) {
            return null;
        }
        return boundedSetMembers(
                keyStrategy.indexExactKey(
                        metadata.redisNamespace(), column, encodeKeyPart(serializeValue(value))),
                "exact index " + column);
    }

    private Set<String> membersForIn(String column, List<Object> values) {
        if (!config.exactIndexEnabled()) return null;
        if (values.size() > 4096) throw new IllegalArgumentException("IN query exceeds 4096 keys");
        var keys = new LinkedHashSet<String>();
        for (Object value : values) keys.add(keyStrategy.indexExactKey(metadata.redisNamespace(), column,
                encodeKeyPart(serializeValue(value))));
        if (keys.size() == 1) return boundedSetMembers(keys.iterator().next(), "IN query " + column);
        LinkedHashSet<String> matches = new LinkedHashSet<>();
        for (var members : jedis.smembersBatch(keys, config.maxMaterializedCandidateIds()).values()) {
            matches.addAll(members);
            enforceCandidateLimit(matches, "IN query " + column);
        }
        return matches;
    }

    private Set<String> membersForRange(QueryFilter filter) {
        if (!config.rangeIndexEnabled()) {
            return null;
        }
        Double score =
                RedisScoreSupport.toScore(metadata.columnTypes(), filter.column(), filter.value());
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
        String indexKey = keyStrategy.indexSortKey(metadata.redisNamespace(), filter.column());
        long cardinality = jedis.commands().zcount(indexKey, min, max);
        enforceCandidateLimit(cardinality, "range index " + filter.column());
        return new LinkedHashSet<>(jedis.commands().zrangeByScore(indexKey, min, max));
    }

    private Set<String> membersForPrefix(String column, Object value) {
        if (!config.prefixIndexEnabled() || value == null) {
            return null;
        }
        return boundedSetMembers(
                keyStrategy.indexPrefixKey(
                        metadata.redisNamespace(),
                        column,
                        encodeKeyPart(normalizeText(String.valueOf(value)))),
                "prefix index " + column);
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
            LinkedHashSet<String> tokenMatches =
                    boundedSetMembers(
                            keyStrategy.indexTokenKey(
                                    metadata.redisNamespace(), column, encodeKeyPart(token)),
                            "text token index " + column);
            candidates =
                    candidates == null
                            ? tokenMatches
                            : new LinkedHashSet<>(intersect(candidates, tokenMatches));
            if (candidates.isEmpty()) {
                return candidates;
            }
        }
        return candidates;
    }

    private LinkedHashSet<String> filterResidualCandidates(
            Collection<String> candidateIds, QueryNode residualNode) {
        List<String> orderedIds = new ArrayList<>(candidateIds);
        List<String> keys =
                orderedIds.stream()
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
            if (queryEvaluator.matches(entity, codec, residualNode)) {
                matches.add(orderedIds.get(index));
            }
            index++;
        }
        return matches;
    }

    private void appendExplainSteps(
            QueryNode node, List<QueryExplainStep> steps, Set<String> currentCandidates) {
        if (node instanceof QueryFilter filter) {
            Set<String> indexedMatches = resolveIndexedMatches(filter);
            int candidateCount =
                    indexedMatches == null
                            ? currentCandidates == null
                                    ? Math.toIntExact(
                                            jedis.commands()
                                                    .scard(
                                                            keyStrategy.indexAllKey(
                                                                    metadata.redisNamespace())))
                                    : currentCandidates.size()
                            : currentCandidates == null
                                    ? indexedMatches.size()
                                    : intersect(
                                                    new LinkedHashSet<>(currentCandidates),
                                                    new LinkedHashSet<>(indexedMatches))
                                            .size();
            steps.add(
                    new QueryExplainStep(
                            "FILTER",
                            describeFilter(filter),
                            filterStrategy(filter, indexedMatches != null),
                            fullyIndexed(filter),
                            candidateCount,
                            estimateFilterCost(filter, indexedMatches),
                            filterDetail(filter, indexedMatches)));
            return;
        }

        QueryGroup group = (QueryGroup) node;
        steps.add(
                new QueryExplainStep(
                        "GROUP",
                        group.nodes().stream()
                                .map(this::describeNode)
                                .collect(Collectors.joining(" " + group.operator().name() + " ")),
                        group.operator().name() + "_GROUP",
                        fullyIndexed(group),
                        currentCandidates == null ? 0 : currentCandidates.size(),
                        Math.max(1, currentCandidates == null ? 0 : currentCandidates.size()),
                        "Compound query group with " + group.nodes().size() + " child nodes"));
        for (QueryNode child : group.nodes()) {
            appendExplainSteps(child, steps, currentCandidates);
        }
    }

    private String describeNode(QueryNode node) {
        if (node instanceof QueryFilter filter) {
            return describeFilter(filter);
        }
        QueryGroup group = (QueryGroup) node;
        return "("
                + group.nodes().stream()
                        .map(this::describeNode)
                        .collect(Collectors.joining(" " + group.operator().name() + " "))
                + ")";
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
            if (!"RESOLVED".equals(relationState.status())
                    && !"NO_MATCHES".equals(relationState.status())
                    && !"BATCH_PRELOAD".equals(relationState.status())) {
                warnings.add(
                        relationState.usage()
                                + ":"
                                + relationState.relationName()
                                + ":"
                                + relationState.status());
            }
        }
        return List.copyOf(warnings);
    }

    private Set<String> resolveRelationMatches(QueryFilter filter) {
        String[] parts = filter.column().split("\\.", 2);
        if (parts.length != 2) {
            return null;
        }

        RelationDefinition relation =
                metadata.relations().stream()
                        .filter(candidate -> candidate.name().equals(parts[0]))
                        .findFirst()
                        .orElse(null);
        if (relation == null) {
            return null;
        }

        EntityBinding<?, ?> targetBinding =
                entityRegistry.find(relation.targetEntity()).orElse(null);
        if (targetBinding == null) {
            return null;
        }

        QueryFilter delegatedFilter =
                new QueryFilter(parts[1], filter.operator(), filter.value(), filter.values());
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
                    "Relation filter path must be in the form relation.column");
        }

        RelationDefinition relation =
                metadata.relations().stream()
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
                    "No declared relation matches filter path " + filter.column());
        }

        EntityBinding<?, ?> targetBinding =
                entityRegistry.find(relation.targetEntity()).orElse(null);
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
                    "Target entity binding " + relation.targetEntity() + " is not registered");
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
                    "Target metadata for "
                            + relation.targetEntity()
                            + " does not expose mappedBy column "
                            + relation.mappedBy());
        }

        QueryFilter delegatedFilter =
                new QueryFilter(parts[1], filter.operator(), filter.value(), filter.values());
        Set<String> targetIds = resolveTargetIds(targetBinding, delegatedFilter);
        Set<String> sourceIds =
                targetIds == null || targetIds.isEmpty()
                        ? new LinkedHashSet<>()
                        : mapTargetIdsToSourceIds(targetBinding, relation, targetIds);
        String mappedByDetail =
                mappedByColumn.equals(relation.mappedBy())
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
                "Delegated filter "
                        + parts[1]
                        + " on "
                        + relation.targetEntity()
                        + ", mappedBy="
                        + mappedByDetail
                        + ", targetMatches="
                        + (targetIds == null ? 0 : targetIds.size()));
    }

    private QueryExplainRelationState describeFetchRelationState(
            String relationName, FetchPlan fetchPlan) {
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
                    "Fetch path depth "
                            + requestedDepth
                            + " exceeds configured maxFetchDepth="
                            + configuredRelationMaxDepth());
        }
        String topLevelRelationName = topLevelRelationName(relationName);
        RelationDefinition relation =
                metadata.relations().stream()
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
                    "Requested fetch relation is not declared in metadata");
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
                    "Source entity binding "
                            + metadata.entityName()
                            + " is not registered, so fetch path cannot be verified");
        }
        if (sourceBinding.relationBatchLoader() == null
                || sourceBinding.relationBatchLoader() instanceof NoOpRelationBatchLoader) {
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
                    "Fetch plan requests "
                            + relation.name()
                            + " but no concrete RelationBatchLoader is registered for "
                            + metadata.entityName());
        }
        EntityBinding<?, ?> targetBinding =
                entityRegistry.find(relation.targetEntity()).orElse(null);
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
                    "A batch loader is registered, but target entity binding "
                            + relation.targetEntity()
                            + " is missing so mappedBy validation could not be confirmed");
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
                    "A batch loader is registered, but target metadata for "
                            + relation.targetEntity()
                            + " does not expose mappedBy column "
                            + relation.mappedBy());
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
                "Fetch plan will use the registered batch loader path for "
                        + relation.kind()
                        + " via mappedBy="
                        + mappedByColumn
                        + (requestedDepth > 1 ? " (requested depth=" + requestedDepth + ")" : ""));
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
    private Set<String> resolveTargetIds(
            EntityBinding<?, ?> targetBinding, QueryFilter delegatedFilter) {
        RedisQueryIndexManager targetManager =
                new RedisQueryIndexManager(
                        jedis,
                        targetBinding.metadata(),
                        targetBinding.codec(),
                        keyStrategy,
                        entityRegistry,
                        config,
                        relationConfig,
                        queryEvaluator,
                        producerGuard);
        return new LinkedHashSet<>(
                targetManager.resolveCandidateIds(
                        QuerySpec.builder().filter(delegatedFilter).build()));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Set<String> mapTargetIdsToSourceIds(
            EntityBinding<?, ?> targetBinding,
            RelationDefinition relation,
            Collection<String> targetIds) {
        EntityBinding rawBinding = targetBinding;
        List<String> keys =
                targetIds.stream()
                        .map(
                                id ->
                                        keyStrategy.entityKey(
                                                rawBinding.metadata().redisNamespace(), id))
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

    private String relationMappedByColumn(
            EntityBinding<?, ?> targetBinding, RelationDefinition relation) {
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
        LinkedHashSet<String> allIds =
                boundedSetMembers(
                        keyStrategy.indexAllKey(metadata.redisNamespace()),
                        "not-equal full entity index");
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

    private String serializeValue(Object value) { return RedisIndexKeySpace.serializeValue(value); }
    private String decodeValue(String value) { return RedisIndexKeySpace.decodeValue(value); }
    private String encodeKeyPart(String value) { return RedisIndexKeySpace.encodeKeyPart(value); }

    private void indexStringValue(AbstractPipeline pipeline, String id, String column, String value) {
        if (metadata.encryptedColumns().containsKey(column)) return;
        RedisIndexMutationPlanner.stringValue(pipeline,keyStrategy,metadata.redisNamespace(),config,id,column,value,true);
    }
    private void removeStringValueIndex(AbstractPipeline pipeline, String id, String column, String value) {
        if (metadata.encryptedColumns().containsKey(column)) return;
        RedisIndexMutationPlanner.stringValue(pipeline,keyStrategy,metadata.redisNamespace(),config,id,column,value,false);
    }

    private void upsertPartitionedSortIndexes(
            AbstractPipeline pipeline, String idValue, Map<String, Object> columns) {
        if (!supportsPartitionedExactSortIndexes() || columns == null || columns.isEmpty()) {
            return;
        }
        for (String exactColumn : partitionedExactSortColumns()) {
            Object exactValue = columns.get(exactColumn);
            if (exactValue == null && !columns.containsKey(exactColumn)) {
                continue;
            }
            String encodedExactValue = encodeKeyPart(serializeValue(exactValue));
            for (String sortColumn : partitionedSortColumns(exactColumn)) {
                Double score =
                        RedisScoreSupport.toScore(
                                metadata.columnTypes(), sortColumn, columns.get(sortColumn));
                if (score == null) {
                    continue;
                }
                pipeline.zadd(
                        keyStrategy.indexPartitionSortKey(
                                metadata.redisNamespace(),
                                exactColumn,
                                encodedExactValue,
                                sortColumn),
                        score,
                        idValue);
            }
        }
    }

    private void removePartitionedSortIndexes(
            AbstractPipeline pipeline, String idValue, Map<String, String> priorMetaValues) {
        if (!supportsPartitionedExactSortIndexes()
                || priorMetaValues == null
                || priorMetaValues.isEmpty()) {
            return;
        }
        for (String exactColumn : partitionedExactSortColumns()) {
            String serializedExactValue = priorMetaValues.get(exactColumn);
            if (serializedExactValue == null) {
                continue;
            }
            String encodedExactValue = encodeKeyPart(serializedExactValue);
            for (String sortColumn : partitionedSortColumns(exactColumn)) {
                pipeline.zrem(
                        keyStrategy.indexPartitionSortKey(
                                metadata.redisNamespace(),
                                exactColumn,
                                encodedExactValue,
                                sortColumn),
                        idValue);
            }
        }
    }

    private boolean supportsPartitionedExactSortIndexes() {
        return (metadata instanceof ProjectionEntityMetadata<?, ?>
                        || !metadata.partitionedSortIndexes().isEmpty())
                && config.exactIndexEnabled()
                && config.rangeIndexEnabled();
    }

    private List<String> partitionedExactSortColumns() {
        if (!supportsPartitionedExactSortIndexes()) {
            return List.of();
        }
        if (!(metadata instanceof ProjectionEntityMetadata<?, ?>)) {
            return List.copyOf(metadata.partitionedSortIndexes().keySet());
        }
        return metadata.columns().stream().filter(this::isPartitionedExactSortColumn).toList();
    }

    private boolean isPartitionedExactSortColumn(String column) {
        if (column == null || column.isBlank()) {
            return false;
        }
        if (column.equalsIgnoreCase(metadata.idColumn())) {
            return false;
        }
        return column.toLowerCase(Locale.ROOT).endsWith("_id");
    }

    private List<String> partitionedSortColumns(String partitionColumn) {
        if (!supportsPartitionedExactSortIndexes()) {
            return List.of();
        }
        if (!(metadata instanceof ProjectionEntityMetadata<?, ?>)) {
            return metadata
                    .partitionedSortIndexes()
                    .getOrDefault(partitionColumn, List.of())
                    .stream()
                    .filter(this::supportsScoreOrdering)
                    .toList();
        }
        return metadata.columns().stream().filter(this::supportsScoreOrdering).toList();
    }

    private boolean supportsPartitionedPair(String partitionColumn, String sortColumn) {
        if (!supportsPartitionedExactSortIndexes() || !supportsScoreOrdering(sortColumn)) {
            return false;
        }
        if (metadata instanceof ProjectionEntityMetadata<?, ?>) {
            return isPartitionedExactSortColumn(partitionColumn);
        }
        return metadata.partitionedSortIndexes()
                .getOrDefault(partitionColumn, List.of())
                .contains(sortColumn);
    }

    private List<String> tokenize(String rawValue) { return RedisIndexKeySpace.tokenize(rawValue, config); }
    private String normalizeText(String value) { return RedisIndexKeySpace.normalizeText(value); }
    private String toSnakeCase(String value) { return RedisIndexKeySpace.toSnakeCase(value); }

    private boolean supportsScoreOrdering(String column) {
        if (com.reactor.cachedb.core.crypto.EncryptedStorageChecks.descriptor(metadata, column) != null) return false;
        if (!indexedColumns.contains(column)) return false;
        String declaredType = metadata.columnTypes().get(column);
        if (declaredType == null || declaredType.isBlank()) {
            return false;
        }
        return switch (declaredType) {
            case "int",
                            "java.lang.Integer",
                            "long",
                            "java.lang.Long",
                            "double",
                            "java.lang.Double",
                            "float",
                            "java.lang.Float",
                            "short",
                            "java.lang.Short",
                            "byte",
                            "java.lang.Byte",
                            "java.time.Instant",
                            "java.time.LocalDate",
                            "java.time.LocalDateTime",
                            "java.time.OffsetDateTime" ->
                    true;
            default -> false;
        };
    }

    private QueryCardinalityEstimate estimateFilter(QueryFilter filter) {
        long fullSetCardinality =
                Math.max(
                        1,
                        jedis.commands().scard(keyStrategy.indexAllKey(metadata.redisNamespace())));
        if (!config.plannerStatisticsEnabled()) {
            return new QueryCardinalityEstimate(
                    describeFilter(filter),
                    fullSetCardinality,
                    fullSetCardinality,
                    fullSetCardinality,
                    1.0d,
                    List.of());
        }

        String cacheKey = metadata.redisNamespace() + "|" + describeFilter(filter);
        CachedEstimate cachedEstimate = cardinalityCache.get(cacheKey);
        long now = System.currentTimeMillis();
        if (cachedEstimate != null
                && now - cachedEstimate.createdAtEpochMillis()
                        <= config.plannerStatisticsTtlMillis()) {
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
            long estimatedCardinality =
                    relationMatches == null ? fullSetCardinality : relationMatches.size();
            estimatedCardinality =
                    applyLearnedAdjustment(
                            describeFilter(filter), estimatedCardinality, fullSetCardinality);
            double selectivityRatio =
                    Math.min(
                            1.0d,
                            Math.max(
                                    0.0d,
                                    fullSetCardinality == 0
                                            ? 0.0d
                                            : (double) estimatedCardinality / fullSetCardinality));
            return new QueryCardinalityEstimate(
                    describeFilter(filter),
                    estimatedCardinality,
                    Math.max(
                            1L,
                            Math.round(
                                    Math.max(
                                            1.0d,
                                            estimatedCardinality
                                                    * Math.max(0.05d, selectivityRatio)))),
                    estimatedCardinality,
                    selectivityRatio,
                    List.of());
        }
        long estimatedCardinality =
                switch (filter.operator()) {
                    case EQ ->
                            cardinalityOfSet(
                                    keyStrategy.indexExactKey(
                                            metadata.redisNamespace(),
                                            filter.column(),
                                            encodeKeyPart(serializeValue(com.reactor.cachedb.core.crypto.EncryptedStorageChecks.indexLiteral(metadata, codec, filter.column(), filter.value())))));
                    case IN ->
                            filter.values().stream()
                                    .mapToLong(
                                            value ->
                                                    cardinalityOfSet(
                                                            keyStrategy.indexExactKey(
                                                                    metadata.redisNamespace(),
                                                                    filter.column(),
                                                                    encodeKeyPart(
                                                                            serializeValue(
                                                                                    value)))))
                                    .sum();
                    case GT, GTE, LT, LTE -> cardinalityOfRange(filter);
                    case NE ->
                            Math.max(
                                    0L,
                                    fullSetCardinality
                                            - cardinalityOfSet(
                                                    keyStrategy.indexExactKey(
                                                            metadata.redisNamespace(),
                                                            filter.column(),
                                                            encodeKeyPart(
                                                                    serializeValue(com.reactor.cachedb.core.crypto.EncryptedStorageChecks.indexLiteral(metadata, codec, filter.column(), filter.value()))))));
                    case CONTAINS -> cardinalityOfContains(filter);
                    case STARTS_WITH ->
                            cardinalityOfSet(
                                    keyStrategy.indexPrefixKey(
                                            metadata.redisNamespace(),
                                            filter.column(),
                                            encodeKeyPart(
                                                    normalizeText(
                                                            String.valueOf(filter.value())))));
                };
        estimatedCardinality = Math.min(fullSetCardinality, Math.max(0L, estimatedCardinality));
        estimatedCardinality =
                applyLearnedAdjustment(
                        describeFilter(filter), estimatedCardinality, fullSetCardinality);

        List<QueryHistogramBucket> histogram =
                isRangeOperator(filter.operator())
                        ? histogramForColumn(filter.column())
                        : List.of();
        long sampledCardinality = determineSampledCardinality(filter, fullSetCardinality);
        double selectivityRatio =
                Math.min(
                        1.0d,
                        Math.max(
                                0.0d,
                                fullSetCardinality == 0
                                        ? 0.0d
                                        : (double) estimatedCardinality / fullSetCardinality));
        return new QueryCardinalityEstimate(
                describeFilter(filter),
                estimatedCardinality,
                Math.max(
                        1L,
                        Math.round(
                                Math.max(
                                        1.0d,
                                        estimatedCardinality * Math.max(0.05d, selectivityRatio)))),
                sampledCardinality,
                selectivityRatio,
                histogram);
    }

    private long estimateNodeCardinality(QueryNode node) {
        long fullSetCardinality =
                Math.max(
                        1,
                        jedis.commands().scard(keyStrategy.indexAllKey(metadata.redisNamespace())));
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
            combinedRatio *=
                    Math.min(1.0d, Math.max(0.0d, (double) childEstimate / fullSetCardinality));
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
            nonMatchRatio *=
                    (1.0d
                            - Math.min(
                                    1.0d,
                                    Math.max(0.0d, (double) childEstimate / fullSetCardinality)));
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
        if (cachedHistogram != null
                && now - cachedHistogram.createdAtEpochMillis()
                        <= config.plannerStatisticsTtlMillis()) {
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
        long cardinality = jedis.commands().zcard(sortKey);
        if (cardinality == 0) {
            return List.of();
        }
        int bucketCount = Math.max(1, Math.min(config.rangeHistogramBuckets(), (int) cardinality));
        ArrayList<Double> boundaries = new ArrayList<>(bucketCount + 1);
        for (int index = 0; index <= bucketCount; index++) {
            long rank =
                    Math.min(
                            cardinality - 1,
                            Math.round((double) index * (cardinality - 1) / bucketCount));
            List<String> ids = jedis.commands().zrange(sortKey, rank, rank);
            if (ids.isEmpty()) {
                continue;
            }
            Double score = jedis.commands().zscore(sortKey, ids.get(0));
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
            long count = jedis.commands().zcount(sortKey, String.valueOf(lower), max);
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
        long fullSet =
                Math.max(
                        1,
                        jedis.commands().scard(keyStrategy.indexAllKey(metadata.redisNamespace())));
        long minCount = Long.MAX_VALUE;
        double productRatio = 1.0d;
        for (String token : tokens) {
            long cardinality =
                    cardinalityOfSet(
                            keyStrategy.indexTokenKey(
                                    metadata.redisNamespace(),
                                    filter.column(),
                                    encodeKeyPart(token)));
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
            return Math.min(
                    jedis.commands().zcard(sortKey),
                    Math.max(1, config.plannerStatisticsSampleSize()));
        }
        return Math.min(fullSetCardinality, Math.max(1, config.plannerStatisticsSampleSize()));
    }

    private long applyLearnedAdjustment(
            String expression, long estimatedCardinality, long fullSetCardinality) {
        if (!config.learnedStatisticsEnabled()) {
            return estimatedCardinality;
        }
        LearnedStats learnedStats = loadLearnedStats(expression);
        if (learnedStats == null || learnedStats.sampleCount() <= 0) {
            return estimatedCardinality;
        }
        double weight = Math.max(0.0d, Math.min(1.0d, config.learnedStatisticsWeight()));
        long blended =
                Math.round(
                        (estimatedCardinality * (1.0d - weight))
                                + (learnedStats.averageCardinality() * weight));
        return Math.max(0L, Math.min(fullSetCardinality, blended));
    }

    private LearnedStats loadLearnedStats(String expression) {
        String cacheKey = metadata.redisNamespace() + "|learned|" + expression;
        CachedLearnedStats cached = learnedStatsCache.get(cacheKey);
        long now = System.currentTimeMillis();
        if (cached != null
                && now - cached.createdAtEpochMillis() <= config.plannerStatisticsTtlMillis()) {
            return cached.stats();
        }

        if (!config.plannerStatisticsPersisted()) {
            return cached == null ? null : cached.stats();
        }

        Map<String, String> fields = loadLearnedStatsFieldsSafely(expression);
        if (fields == null || fields.isEmpty()) {
            return null;
        }
        LearnedStats learnedStats =
                new LearnedStats(
                        parseLong(fields.get("averageCardinality"), 0L),
                        parseLong(fields.get("sampleCount"), 0L));
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
        long newAverage =
                sampleCount == 0L
                        ? actualCardinality
                        : Math.round(
                                ((double) averageCardinality * sampleCount + actualCardinality)
                                        / newSampleCount);
        LearnedStats learnedStats = new LearnedStats(newAverage, newSampleCount);
        learnedStatsCache.put(
                cacheKey, new CachedLearnedStats(learnedStats, System.currentTimeMillis()));
        if (config.plannerStatisticsPersisted()) {
            storeLearnedStatsSafely(expression, learnedStats);
        }
    }

    private long cardinalityOfRange(QueryFilter filter) {
        Double score =
                RedisScoreSupport.toScore(metadata.columnTypes(), filter.column(), filter.value());
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
        return jedis.commands().zcount(sortKey, min, max);
    }

    private long cardinalityOfSet(String key) {
        return jedis.commands().scard(key);
    }

    private boolean shouldShedIndexWrites() {
        return producerGuard != null
                && producerGuard.shouldShedQueryIndexWrites(metadata.redisNamespace());
    }

    private boolean shouldShedPlannerLearning() {
        return producerGuard != null
                && producerGuard.shouldShedPlannerLearning(metadata.redisNamespace(), List.of());
    }

    private boolean shouldBypassIndexes() {
        return (producerGuard != null
                        && producerGuard.shouldShedQueryIndexReads(
                                metadata.redisNamespace(), List.of()))
                || indexFeaturesDegraded();
    }

    private boolean shouldBypassIndexes(QuerySpec querySpec) {
        return (producerGuard != null
                        && producerGuard.shouldShedQueryIndexReads(
                                metadata.redisNamespace(), classifyQueryClasses(querySpec)))
                || indexFeaturesDegraded();
    }

    private boolean shouldShedPlannerLearning(QuerySpec querySpec) {
        return producerGuard != null
                && producerGuard.shouldShedPlannerLearning(
                        metadata.redisNamespace(), classifyQueryClasses(querySpec));
    }

    private boolean indexFeaturesDegraded() {
        return jedis.commands().exists(keyStrategy.indexDegradedKey(metadata.redisNamespace()));
    }

    private void markIndexFeaturesDegraded() {
        jedis.commands().set(keyStrategy.indexDegradedKey(metadata.redisNamespace()), "1");
    }

    private void maybeRecoverDegradedIndexes(Collection<HardLimitQueryClass> queryClasses) {
        if (!indexFeaturesDegraded()) {
            return;
        }
        if (producerGuard == null
                || !producerGuard.shouldAutoRecoverDegradedIndexes(metadata.redisNamespace())) {
            return;
        }
        if (producerGuard.shouldShedQueryIndexWrites(metadata.redisNamespace())
                || producerGuard.shouldShedQueryIndexReads(
                        metadata.redisNamespace(), queryClasses)) {
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
        return jedis.commands()
                        .set(
                                lockKey,
                                String.valueOf(System.currentTimeMillis()),
                                SetParams.setParams().nx().px(30_000L))
                != null;
    }

    private long clearIndexKeys(String lockKey) {
        long clearedCount = 0L;
        String cursor = "0";
        int iterations = 0;
        do {
            if (++iterations > 100_000) throw new IllegalStateException("Index cleanup scan exceeds 100000 iterations; indexes remain degraded");
            redis.clients.jedis.resps.ScanResult<String> scan =
                    jedis.scan(
                            cursor,
                            new redis.clients.jedis.params.ScanParams()
                                    .match(keyStrategy.indexPattern(metadata.redisNamespace()))
                                    .count(256));
            for (String key : scan.getResult()) {
                if (lockKey.equals(key) || compositeFence.reserved(key)
                        || keyStrategy.compositeIndexSignatureKey(metadata.redisNamespace()).equals(key)
                        || keyStrategy.indexDegradedKey(metadata.redisNamespace()).equals(key)) {
                    continue;
                }
                clearedCount += jedis.commands().del(key);
            }
            cursor = scan.getCursor();
        } while (!"0".equals(cursor));
        return clearedCount;
    }

    private List<String> scanAllEntityIds() {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        String cursor = "0";
        String prefix = keyStrategy.entityPattern(metadata.redisNamespace()).replace("*", "");
        int iterations = 0;
        long idBytes = 0;
        do {
            if (hasCompositeSortIndexes() && ++iterations > 128)
                throw new IllegalStateException("Bounded entity fallback exceeds 128 scan rounds");
            redis.clients.jedis.resps.ScanResult<String> scan =
                    jedis.scan(
                            cursor,
                            new redis.clients.jedis.params.ScanParams()
                                    .match(keyStrategy.entityPattern(metadata.redisNamespace()))
                                    .count(Math.max(64, config.plannerStatisticsSampleSize() * 4)));
            for (String key : scan.getResult()) {
                if (key.startsWith(prefix)) {
                    String id = key.substring(prefix.length());
                    if (hasCompositeSortIndexes()) RedisCompositeIndexes.validateMember(id);
                    if (ids.add(id) && hasCompositeSortIndexes())
                        idBytes += id.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                    if (hasCompositeSortIndexes() && (ids.size() > Math.min(10_000, config.maxMaterializedCandidateIds())
                            || idBytes > COMPOSITE_MAX_BYTES))
                        throw new IllegalStateException("Bounded entity fallback capacity exceeded");
                    enforceCandidateLimit(ids, "degraded entity key scan");
                }
            }
            cursor = scan.getCursor();
        } while (!"0".equals(cursor));
        return List.copyOf(ids);
    }

    private LinkedHashSet<String> boundedSetMembers(String key, String shape) {
        enforceCandidateLimit(jedis.commands().scard(key), shape);
        return new LinkedHashSet<>(jedis.commands().smembers(key));
    }

    private void enforceCandidateLimit(Collection<String> candidates, String shape) {
        enforceCandidateLimit(candidates == null ? 0L : candidates.size(), shape);
    }

    private void enforceCandidateLimit(long candidateCount, String shape) {
        if (candidateCount > config.maxMaterializedCandidateIds()) {
            throw new IllegalStateException(
                    "Query candidate materialization rejected for "
                            + metadata.entityName()
                            + ": shape="
                            + shape
                            + ", candidates="
                            + candidateCount
                            + ", maxMaterializedCandidateIds="
                            + config.maxMaterializedCandidateIds()
                            + ". Use a sorted-window route, a projection, or a narrower indexed"
                            + " predicate.");
        }
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
                jedis.commands().incr(keyStrategy.indexPlannerEpochKey(metadata.redisNamespace()));
            } catch (RuntimeException exception) {
                markIndexFeaturesDegradedSafely();
            }
        }
    }

    private QueryCardinalityEstimate loadEstimateFromRedis(QueryFilter filter) {
        Map<String, String> fields = jedis.commands().hgetAll(plannerEstimateRedisKey(filter));
        if (fields == null || fields.isEmpty()) {
            return null;
        }
        long estimatedCardinality = parseLong(fields.get("estimatedCardinality"), 0L);
        long estimatedCost =
                parseLong(fields.get("estimatedCost"), Math.max(1L, estimatedCardinality));
        List<QueryHistogramBucket> histogram =
                isRangeOperator(filter.operator())
                        ? loadHistogramFromRedis(filter.column())
                        : List.of();
        return new QueryCardinalityEstimate(
                fields.getOrDefault("expression", describeFilter(filter)),
                estimatedCardinality,
                estimatedCost,
                parseLong(fields.get("sampledCardinality"), estimatedCardinality),
                parseDouble(fields.get("selectivityRatio"), 1.0d),
                histogram);
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
        jedis.commands()
                .hset(
                        key,
                        Map.of(
                                "expression", estimate.expression(),
                                "estimatedCardinality",
                                        String.valueOf(estimate.estimatedCardinality()),
                                "estimatedCost", String.valueOf(estimate.estimatedCost()),
                                "sampledCardinality", String.valueOf(estimate.sampledCardinality()),
                                "selectivityRatio", String.valueOf(estimate.selectivityRatio())));
        jedis.commands().pexpire(key, config.plannerStatisticsTtlMillis());
    }

    private void storeEstimateInRedisSafely(QueryFilter filter, QueryCardinalityEstimate estimate) {
        try {
            storeEstimateInRedis(filter, estimate);
        } catch (RuntimeException exception) {
            markIndexFeaturesDegradedSafely();
        }
    }

    private List<QueryHistogramBucket> loadHistogramFromRedis(String column) {
        String payload = jedis.commands().get(plannerHistogramRedisKey(column));
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
            buckets.add(
                    new QueryHistogramBucket(
                            Double.parseDouble(parts[0]),
                            Double.parseDouble(parts[1]),
                            Long.parseLong(parts[2])));
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
        String payload =
                buckets.stream()
                        .map(
                                bucket ->
                                        bucket.lowerBoundInclusive()
                                                + ":"
                                                + bucket.upperBoundExclusive()
                                                + ":"
                                                + bucket.count())
                        .collect(Collectors.joining("|"));
        String key = plannerHistogramRedisKey(column);
        jedis.commands().set(key, payload);
        jedis.commands().pexpire(key, config.plannerStatisticsTtlMillis());
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
            return jedis.commands().hgetAll(plannerLearnedRedisKey(expression));
        } catch (RuntimeException exception) {
            markIndexFeaturesDegradedSafely();
            return Map.of();
        }
    }

    private void storeLearnedStatsSafely(String expression, LearnedStats learnedStats) {
        try {
            String key = plannerLearnedRedisKey(expression);
            jedis.commands()
                    .hset(
                            key,
                            Map.of(
                                    "averageCardinality",
                                            String.valueOf(learnedStats.averageCardinality()),
                                    "sampleCount", String.valueOf(learnedStats.sampleCount())));
            jedis.commands().pexpire(key, config.plannerStatisticsTtlMillis());
        } catch (RuntimeException exception) {
            markIndexFeaturesDegradedSafely();
        }
    }

    private void markIndexFeaturesDegradedSafely() {
        try {
            markIndexFeaturesDegraded();
        } catch (RuntimeException ignored) {
            // Index degradation is advisory; never fail the main repository path on fallback
            // marking.
        }
    }

    private String plannerEstimateRedisKey(QueryFilter filter) {
        return keyStrategy.indexPlannerEstimateKey(
                metadata.redisNamespace(),
                encodeKeyPart(currentPlannerEpoch() + "|" + describeFilter(filter)));
    }

    private String plannerHistogramRedisKey(String column) {
        return keyStrategy.indexPlannerHistogramKey(
                metadata.redisNamespace(), encodeKeyPart(currentPlannerEpoch() + "|" + column));
    }

    private String plannerLearnedRedisKey(String expression) {
        return keyStrategy.indexPlannerLearnedKey(
                metadata.redisNamespace(), encodeKeyPart(currentPlannerEpoch() + "|" + expression));
    }

    private String currentPlannerEpoch() {
        String epoch =
                jedis.commands().get(keyStrategy.indexPlannerEpochKey(metadata.redisNamespace()));
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
        return Math.max(1, candidateCount)
                + sortPlan.estimatedCost()
                + Math.max(0, querySpec.fetchPlan().includes().size());
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

    private record Resolution(Set<String> candidates, boolean fullyIndexed) {}

    private record ResolutionWithNode(QueryNode node, Resolution resolution) {}

    private record BatchFilterResult(List<String> matchedIds, boolean stopScanning) {}

    private record LazySortedScanPlan(
            Set<String> membershipIds,
            ScoreRange scoreRange,
            com.reactor.cachedb.core.query.QuerySortDirection direction,
            boolean requiresDecode) {}

    private record PartitionedExactSortPlan(
            String exactColumn, Object exactValue, QuerySort sort, String sortKey) {}

    private record ScoreRange(double min, boolean minInclusive, double max, boolean maxInclusive) {
        private boolean matches(double score) {
            boolean lower = minInclusive ? score >= min : score > min;
            boolean upper = maxInclusive ? score <= max : score < max;
            return lower && upper;
        }

        private boolean canStop(
                double score, com.reactor.cachedb.core.query.QuerySortDirection direction) {
            if (direction == com.reactor.cachedb.core.query.QuerySortDirection.DESC) {
                return minInclusive ? score < min : score <= min;
            }
            return maxInclusive ? score > max : score >= max;
        }
    }

    public record SortPlan(String strategy, long estimatedCost, boolean usesSortedIndex) {}

    private record CachedEstimate(QueryCardinalityEstimate estimate, long createdAtEpochMillis) {}

    private record CachedHistogram(List<QueryHistogramBucket> buckets, long createdAtEpochMillis) {}

    private record CachedLearnedStats(LearnedStats stats, long createdAtEpochMillis) {}

    private record LearnedStats(long averageCardinality, long sampleCount) {}
}
