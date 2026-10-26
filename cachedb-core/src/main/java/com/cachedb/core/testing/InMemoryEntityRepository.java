package com.reactor.cachedb.core.testing;

import com.reactor.cachedb.core.api.*;
import com.reactor.cachedb.core.cache.*;
import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.core.page.VersionedEntity;
import com.reactor.cachedb.core.plan.FetchPlan;
import com.reactor.cachedb.core.projection.EntityProjection;
import com.reactor.cachedb.core.query.*;
import com.reactor.cachedb.core.repository.*;

import java.time.Clock;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** Consumer test double, not a durable store or a Redis performance simulator. */
public final class InMemoryEntityRepository<T, ID> implements EntityRepository<T, ID> {
    private record Stored(String payload, long expiresAt) {}

    private record QueryRow<V>(V value, Map<String, Object> columns, String id) {}

    final EntityMetadata<T, ID> metadata;
    final EntityCodec<T> codec;
    final CachePolicy policy;
    private final Clock clock;
    final ReentrantLock lock;
    private final Map<ID, Stored> hot = new ConcurrentHashMap<>();
    private final Map<ID, Long> versions = new ConcurrentHashMap<>();
    private final Map<ID, String> tenantOwners = new ConcurrentHashMap<>();
    private final Set<ID> tombstones = ConcurrentHashMap.newKeySet();
    private final Set<ID> rejected = ConcurrentHashMap.newKeySet();
    private final LinkedHashSet<ID> order = new LinkedHashSet<>();
    private final QueryEvaluator evaluator = new QueryEvaluator();

    public InMemoryEntityRepository(
            EntityMetadata<T, ID> metadata, EntityCodec<T> codec, CachePolicy policy) {
        this(metadata, codec, policy, Clock.systemUTC(), new ReentrantLock());
    }

    InMemoryEntityRepository(
            EntityMetadata<T, ID> metadata,
            EntityCodec<T> codec,
            CachePolicy policy,
            Clock clock,
            ReentrantLock lock) {
        this.metadata = Objects.requireNonNull(metadata);
        this.policy = Objects.requireNonNull(policy);
        this.codec = com.reactor.cachedb.core.codec.PayloadEntityCodec.wrap(Objects.requireNonNull(codec), metadata,
                Math.max(metadata.compressionThresholdBytes(), policy.compressionThresholdBytes()));
        this.clock = clock;
        this.lock = lock;
    }

    public RepositoryCapabilities capabilities() {
        return RepositoryCapabilities.all();
    }

    public Optional<T> findById(ID id) {
        return findHotById(id).optional();
    }

    public HotLookup<T> findHotById(ID id) {
        lock.lock();
        try {
            Objects.requireNonNull(id, "id");
            long required = com.reactor.cachedb.core.causal.CacheCausalContext.requiredVersion(metadata.redisNamespace(), id);
            if (required > version(id)) throw new IllegalStateException("Causal version is not available for " + metadata.entityName());
            String tenant = com.reactor.cachedb.core.tenant.TenantAccess.active(metadata);
            String owner = tenantOwners.get(id);
            if (tenant != null && owner != null && !tenant.equals(owner))
                throw new com.reactor.cachedb.core.exception.CacheTenantAccessDeniedException(metadata.entityName());
            if (tombstones.contains(id)) return HotLookup.tombstoned();
            if (rejected.contains(id)) return HotLookup.outsidePolicy();
            Stored stored = hot.get(id);
            if (stored == null) return HotLookup.notCached();
            if (stored.expiresAt() > 0 && clock.millis() >= stored.expiresAt()) {
                hot.remove(id);
                order.remove(id);
                return HotLookup.notCached();
            }
            T value = codec.fromRedisValue(stored.payload());
            com.reactor.cachedb.core.tenant.TenantAccess.verify(metadata, value);
            if (!admit(value, CacheAdmissionSource.SERVE)) {
                hot.remove(id);
                order.remove(id);
                rejected.add(id);
                return HotLookup.outsidePolicy();
            }
            if (policy.lruEvictionEnabled()) {
                order.remove(id);
                order.add(id);
            }
            return HotLookup.hit(value);
        } finally {
            lock.unlock();
        }
    }

    public Optional<VersionedEntity<T>> findVersionedById(ID id) {
        lock.lock();
        try {
            Optional<T> value = findById(id);
            return value.isPresent()
                    ? Optional.of(new VersionedEntity<>(value.get(), version(id)))
                    : Optional.empty();
        } finally {
            lock.unlock();
        }
    }

    public List<T> findAll(Collection<ID> ids) {
        lock.lock();
        try {
            List<T> result = new ArrayList<>();
            for (ID id : ids) {
                if (id == null) continue;
                Optional<T> value = findById(id);
                if (value.isPresent()) result.add(value.get());
            }
            return List.copyOf(result);
        } finally {
            lock.unlock();
        }
    }

    public List<T> findPage(PageWindow window) {
        if (window.pageNumber() < 0 || window.pageSize() < 1 || window.pageSize() > 10000)
            throw new IllegalArgumentException("Invalid page window");
        return query(
                QuerySpec.builder()
                        .offset(Math.multiplyExact(window.pageNumber(), window.pageSize()))
                        .limit(window.pageSize())
                        .build());
    }

    public QueryExplainPlan explain(QuerySpec query) {
        return new QueryExplainPlan(
                metadata.entityName(),
                query.filters().size(),
                query.sorts().size(),
                query.offset(),
                query.limit(),
                false,
                hot.size(),
                hot.size(),
                "IN_MEMORY_TEST_SCAN",
                "IN_MEMORY",
                0,
                List.of(),
                List.of(),
                List.of(),
                List.of("Test double; not a production planner estimate"));
    }

    public List<T> query(QuerySpec requestedQuery) {
        QuerySpec query = com.reactor.cachedb.core.tenant.TenantAccess.scoped(metadata, requestedQuery);
        String tenant = com.reactor.cachedb.core.tenant.TenantAccess.active(metadata);
        requireNoRelations(query.fetchPlan());
        lock.lock();
        try {
            List<QueryRow<T>> values = new ArrayList<>();
            for (ID id : new ArrayList<>(order)) {
                if (tenant != null && !tenant.equals(tenantOwners.get(id))) continue;
                T value = findById(id).orElse(null);
                if (value == null) continue;
                Map<String, Object> columns =
                        query.rootGroup().nodes().isEmpty() && query.sorts().isEmpty()
                                ? Map.of()
                                : codec.toColumns(value);
                if (evaluator.matches(columns, query.rootGroup()))
                    values.add(
                            new QueryRow<>(
                                    value, columns, metadata.idAccessor().apply(value).toString()));
            }
            return sortedWindow(values, query);
        } finally {
            lock.unlock();
        }
    }

    public CursorPage<T> findCursorPage(QuerySpec query, WindowRequest window) {
        lock.lock();
        try {
            List<T> values = query(query.offsetBy(0).limitTo(QuerySpec.MAX_LIMIT));
            int start = 0;
            if (window.after() != null) {
                String after =
                        new String(
                                Base64.getUrlDecoder().decode(window.after()),
                                java.nio.charset.StandardCharsets.UTF_8);
                String scope = metadata.redisNamespace() + "\n";
                if (!after.startsWith(scope))
                    throw new IllegalArgumentException("Cursor belongs to another repository");
                String id = after.substring(scope.length());
                while (start < values.size()
                        && !metadata.idAccessor().apply(values.get(start)).toString().equals(id))
                    start++;
                if (start == values.size())
                    throw new IllegalArgumentException("Cursor member missing");
                start++;
            }
            int end = Math.min(values.size(), start + window.limit());
            String next =
                    end < values.size()
                            ? Base64.getUrlEncoder()
                                    .withoutPadding()
                                    .encodeToString(
                                            (metadata.redisNamespace()
                                                            + "\n"
                                                            + metadata.idAccessor()
                                                                    .apply(values.get(end - 1)))
                                                    .getBytes(
                                                            java.nio.charset.StandardCharsets
                                                                    .UTF_8))
                            : null;
            return new CursorPage<>(values.subList(start, end), next);
        } finally {
            lock.unlock();
        }
    }

    public T save(T entity) {
        return saveWithReceipt(entity).entity();
    }

    public WriteReceipt<T, ID> saveWithReceipt(T entity) {
        return save(entity, -1);
    }

    public WriteReceipt<T, ID> save(T entity, long expected) {
        lock.lock();
        try {
            ID id = Objects.requireNonNull(metadata.idAccessor().apply(entity), "entity ID");
            com.reactor.cachedb.core.tenant.TenantAccess.verify(metadata, entity);
            String tenant = metadata.tenantFieldName() == null ? null : Objects.requireNonNull(metadata.extractTenantId(entity), "tenant ID");
            if (tenant != null && tenantOwners.containsKey(id) && !tenant.equals(tenantOwners.get(id)))
                throw new com.reactor.cachedb.core.exception.CacheTenantAccessDeniedException(metadata.entityName());
            checkVersion(id, expected);
            // Validate serialization before mutation; null is never a persisted entity value.
            String payload =
                    Objects.requireNonNull(codec.toRedisValue(entity), "Serialized entity payload");
            T copy = codec.fromRedisValue(payload);
            boolean admit = admit(entity, CacheAdmissionSource.WRITE);
            long expiry =
                    !admit || policy.entityTtlSeconds() <= 0
                            ? 0
                            : Math.addExact(
                                    clock.millis(),
                                    Math.multiplyExact(policy.entityTtlSeconds(), 1000));
            long version = Math.addExact(version(id), 1);
            if (tenant != null) tenantOwners.put(id, tenant);
            versions.put(id, version);
            tombstones.remove(id);
            rejected.remove(id);
            if (admit) {
                hot.put(id, new Stored(payload, expiry));
                order.remove(id);
                order.add(id);
                while (policy.lruEvictionEnabled()
                        && policy.hotEntityLimit() > 0
                        && hot.size() > policy.hotEntityLimit()) {
                    ID oldest = order.iterator().next();
                    order.remove(oldest);
                    hot.remove(oldest);
                }
            } else {
                hot.remove(id);
                order.remove(id);
                rejected.add(id);
            }
            return new WriteReceipt<>(
                    copy,
                    id,
                    metadata.redisNamespace(),
                    OperationType.UPSERT,
                    version,
                    clock.instant());
        } finally {
            lock.unlock();
        }
    }

    public WriteReceipt<T, ID> saveAfter(T entity, WriteDependency dependency) {
        Objects.requireNonNull(dependency, "dependency");
        // No simulated SQL ordering: this kit records acceptance only.
        return saveWithReceipt(entity);
    }

    public List<WriteReceipt<T, ID>> saveAll(Collection<T> values) {
        List<WriteReceipt<T, ID>> result = new ArrayList<>();
        for (T value : values) result.add(saveWithReceipt(value));
        return List.copyOf(result);
    }

    @Override public T patch(ID id, Map<String, Object> updates) { return patchWithReceipt(id, updates).entity(); }
    @Override public Object aggregateFieldValue(T entity, String column) { return codec.entityColumnValue(entity, column); }

    @Override public Map<String, AggregateResult> aggregateBy(String group, AggregateSpec spec) {
        try {
            return aggregateByScoped(group, spec);
        } catch (com.reactor.cachedb.core.exception.CacheTenantAccessDeniedException denied) {
            throw new IllegalStateException("Tenant facet access denied", denied);
        }
    }

    private Map<String, AggregateResult> aggregateByScoped(String group, AggregateSpec spec) {
        if (group == null || group.isBlank() || !metadata.indexedColumns().contains(group))
            throw new IllegalArgumentException("Facet group must be an indexed column");
        Objects.requireNonNull(spec);
        if (spec.fieldName() != null && !spec.fieldName().isBlank() && !spec.fieldName().equals("*")
                && !metadata.columns().contains(spec.fieldName())) throw new IllegalArgumentException("Unknown aggregate column");
        var rows = query(QuerySpec.where(spec.filter()).limitTo(QuerySpec.MAX_LIMIT));
        if (rows.size() == QuerySpec.MAX_LIMIT) throw new IllegalStateException("Facet fallback candidate capacity reached");
        var result = new FacetAccumulator();
        boolean fieldCount = spec.function() == AggregateFunction.COUNT && spec.fieldName() != null && !spec.fieldName().isBlank() && !spec.fieldName().equals("*");
        for (T row : rows) result.add(codec.entityColumnValue(row, group),
                spec.fieldName() == null || spec.fieldName().isBlank() || spec.fieldName().equals("*") ? null : codec.entityColumnValue(row, spec.fieldName()), fieldCount);
        return result.result();
    }

    @Override public WriteReceipt<T, ID> patchWithReceipt(ID id, Map<String, Object> updates) {
        lock.lock();
        try {
            if (updates == null || updates.isEmpty()) throw new IllegalArgumentException("Empty PATCH");
            if (metadata.tenantFieldName() != null && updates.containsKey(metadata.tenantFieldName()))
                throw new IllegalArgumentException("PATCH cannot change tenant ownership");
            for (String column : updates.keySet())
                if (!metadata.columns().contains(column) || column.equals(metadata.idColumn())
                        || column.equals(metadata.versionColumn()) || column.equals(metadata.deletedColumn()))
                    throw new IllegalArgumentException("Invalid PATCH column: " + column);
            T previous = findById(id).orElseThrow(() -> new IllegalStateException("No hot PATCH target"));
            var values = new LinkedHashMap<>(codec.toColumns(previous));
            var replacements = new LinkedHashMap<String, Object>();
            updates.forEach((column, value) -> {
                if (value instanceof PatchDeltaSpec delta) {
                    if (!column.equals(delta.columnName())) throw new IllegalArgumentException("Delta column mismatch");
                    value = NumericDeltas.apply(metadata.entityName(), id, metadata.columnTypes().get(column), delta, values.get(column));
                }
                replacements.put(column, value);
            });
            values.putAll(codec.normalizePatchColumns(replacements));
            var receipt = saveWithReceipt(codec.fromColumns(values));
            return new WriteReceipt<>(receipt.entity(), id, receipt.redisNamespace(), OperationType.PATCH,
                    receipt.version(), receipt.acceptedAt());
        } finally { lock.unlock(); }
    }

    public void deleteById(ID id) {
        deleteWithReceipt(id);
    }

    public WriteReceipt<T, ID> deleteWithReceipt(ID id) {
        lock.lock();
        try {
            Objects.requireNonNull(id);
            String tenant = com.reactor.cachedb.core.tenant.TenantAccess.active(metadata);
            if (tenant != null && !tenant.equals(tenantOwners.get(id)))
                throw new com.reactor.cachedb.core.exception.CacheTenantAccessDeniedException(metadata.entityName());
            long version = Math.addExact(version(id), 1);
            versions.put(id, version);
            tombstones.add(id);
            rejected.remove(id);
            hot.remove(id);
            order.remove(id);
            return new WriteReceipt<>(
                    null,
                    id,
                    metadata.redisNamespace(),
                    OperationType.DELETE,
                    version,
                    clock.instant());
        } finally {
            lock.unlock();
        }
    }

    public <K> Map<K, List<T>> queryPartitions(PartitionedQuerySpec<K> spec) {
        Map<K, List<T>> result = new LinkedHashMap<>();
        for (K key : spec.partitionValues())
            result.put(
                    key,
                    query(
                            QuerySpec.where(QueryFilter.eq(spec.partitionColumn(), key))
                                    .orderBy(spec.sorts().toArray(QuerySort[]::new))
                                    .limitTo(spec.limitPerPartition())));
        return Collections.unmodifiableMap(result);
    }

    public EntityRepository<T, ID> withFetchPlan(FetchPlan plan) {
        requireNoRelations(plan);
        return this;
    }

    private void requireNoRelations(FetchPlan plan) {
        if (plan != null && !plan.includes().isEmpty())
            throw new UnsupportedOperationException(
                    "Register a relation fixture explicitly; this test repository does not simulate"
                            + " relation hydration");
    }

    public <P> ProjectionRepository<P, ID> projected(EntityProjection<T, P, ID> projection) {
        return new ProjectionRepository<>() {
            public Optional<P> findById(ID id) {
                return InMemoryEntityRepository.this.findById(id).map(projection.projector());
            }

            public List<P> findAll(Collection<ID> ids) {
                return InMemoryEntityRepository.this.findAll(ids).stream()
                        .map(projection.projector())
                        .toList();
            }

            public List<P> query(QuerySpec spec) {
                List<QueryRow<P>> values = new ArrayList<>();
                for (T entity :
                        InMemoryEntityRepository.this.query(
                                QuerySpec.builder().limit(QuerySpec.MAX_LIMIT).build())) {
                    P value = projection.projector().apply(entity);
                    Map<String, Object> columns = projection.columnExtractor().apply(value);
                    if (evaluator.matches(columns, spec.rootGroup()))
                        values.add(
                                new QueryRow<>(
                                        value,
                                        columns,
                                        projection.idAccessor().apply(value).toString()));
                }
                return sortedWindow(values, spec);
            }
        };
    }

    private <V> List<V> sortedWindow(List<QueryRow<V>> values, QuerySpec query) {
        List<Comparator<Map<String, Object>>> comparators = new ArrayList<>(query.sorts().size());
        for (QuerySort sort : query.sorts()) {
            comparators.add(evaluator.comparator(sort));
        }
        values.sort(new QueryRowComparator<>(comparators));
        int from = Math.min(query.offset(), values.size());
        int end = (int) Math.min(values.size(), (long) from + query.limit());
        List<V> result = new ArrayList<>(end - from);
        for (int i = from; i < end; i++) result.add(values.get(i).value());
        return List.copyOf(result);
    }

    private static final class QueryRowComparator<V> implements Comparator<QueryRow<V>> {
        private final List<Comparator<Map<String, Object>>> sorts;

        private QueryRowComparator(List<Comparator<Map<String, Object>>> sorts) {
            this.sorts = sorts;
        }

        @Override
        public int compare(QueryRow<V> left, QueryRow<V> right) {
            for (var comparator : sorts) {
                int compared = comparator.compare(left.columns(), right.columns());
                if (compared != 0) return compared;
            }
            return left.id().compareTo(right.id());
        }
    }

    boolean admit(T value, CacheAdmissionSource source) {
        return policy.admissionEnabled()
                && policy.hotPolicy()
                        .shouldAdmit(
                                policy.hotPolicy().mode() == EntityHotPolicyMode.COUNT_WINDOW
                                        ? Map.of()
                                        : codec.toColumns(value),
                                source,
                                clock.instant());
    }

    long version(ID id) {
        return versions.getOrDefault(id, 0L);
    }

    void checkVersion(ID id, long expected) {
        if (expected < -1)
            throw new IllegalArgumentException("expectedVersion must be -1 or non-negative");
        if (expected >= 0 && version(id) != expected)
            throw new OptimisticWriteConflictException(
                    metadata.entityName(), id, expected, version(id));
    }

    Object checkpoint() {
        return new Object[] {
            new HashMap<>(hot),
            new HashMap<>(versions),
            new HashSet<>(tombstones),
            new HashSet<>(rejected),
            new LinkedHashSet<>(order), new HashMap<>(tenantOwners)
        };
    }

    @SuppressWarnings("unchecked")
    void restore(Object checkpoint) {
        Object[] state = (Object[]) checkpoint;
        hot.clear();
        hot.putAll((Map<ID, Stored>) state[0]);
        versions.clear();
        versions.putAll((Map<ID, Long>) state[1]);
        tombstones.clear();
        tombstones.addAll((Set<ID>) state[2]);
        rejected.clear();
        rejected.addAll((Set<ID>) state[3]);
        order.clear();
        order.addAll((Set<ID>) state[4]);
        tenantOwners.clear(); tenantOwners.putAll((Map<ID, String>) state[5]);
    }

    void verifyBatchTenant(Object id, String payload, String expectedTenant) {
        if (metadata.tenantFieldName() == null) return;
        String active;
        try { active = com.reactor.cachedb.core.tenant.TenantAccess.active(metadata); }
        catch (com.reactor.cachedb.core.exception.CacheTenantAccessDeniedException denied) { throw new IllegalStateException("Atomic batch tenant scope changed", denied); }
        if (active == null || !active.equals(expectedTenant)) throw new IllegalStateException("Atomic batch tenant scope changed");
        @SuppressWarnings("unchecked") ID identity = (ID) id;
        String owner = tenantOwners.get(identity);
        if (owner != null && !owner.equals(active) || payload == null && owner == null)
            throw new IllegalStateException("Atomic batch tenant mismatch");
        if (payload != null) try { com.reactor.cachedb.core.tenant.TenantAccess.verify(metadata, codec.fromRedisValue(payload)); }
        catch (com.reactor.cachedb.core.exception.CacheTenantAccessDeniedException denied) { throw new IllegalStateException("Atomic batch tenant mismatch", denied); }
    }
}
