package com.reactor.cachedb.spring.data;

import com.reactor.cachedb.core.api.*;
import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.core.query.*;
import com.reactor.cachedb.core.repository.*;
import com.reactor.cachedb.core.tenant.TenantAccess;
import java.util.*;
import org.springframework.data.domain.*;

/** Shared bounded execution; generated subclasses contain only statically resolved query assembly. */
public abstract class AbstractCacheDbSpringDataRepository<T, ID> implements CacheDbSpringDataRepository<T, ID> {
    public static final int MAX_OFFSET = 10_000;
    public static final int MAX_DELETE = 10_000;
    public static final int MAX_SAVE_BATCH = 1_000;
    private static final String CURSOR = "$cachedb:cursor";
    private final CacheSession session;
    private final EntityRepository<T, ID> delegate;
    private final EntityMetadata<T, ID> metadata;
    private final EntityCodec<T> codec;
    private final Map<String, String> properties;

    protected AbstractCacheDbSpringDataRepository(CacheSession session, EntityMetadata<T, ID> metadata,
            EntityCodec<T> codec, Map<String, String> properties) {
        this.session = Objects.requireNonNull(session, "session");
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        this.codec = runtimeCodec(session, metadata, codec);
        if (properties.size() > 256) throw new IllegalArgumentException("At most 256 Spring Data properties");
        this.properties = Map.copyOf(properties);
        this.delegate = session.repository(metadata, this.codec);
    }

    private static <T, ID> EntityCodec<T> runtimeCodec(CacheSession session, EntityMetadata<T, ID> metadata, EntityCodec<T> requested) {
        try {
            var bound = Objects.requireNonNull(session.runtimeCodec(metadata, Objects.requireNonNull(requested, "codec")), "runtime codec");
            com.reactor.cachedb.core.crypto.EncryptedStorageChecks.requireBound(metadata, bound);
            return bound;
        } catch (com.reactor.cachedb.core.crypto.CacheEncryptionException safe) {
            throw new com.reactor.cachedb.core.crypto.CacheEncryptionException(safe.code());
        } catch (RuntimeException unsafe) {
            if (com.reactor.cachedb.core.crypto.EncryptedStorageChecks.protectedEntity(metadata))
                throw com.reactor.cachedb.core.crypto.EncryptedCodecSupport.unbound();
            throw unsafe;
        }
    }

    protected final CacheSession session() { return session; }
    protected static CacheSession requirePrefixIndexes(CacheSession session, boolean enabled) {
        if (!enabled) throw new IllegalArgumentException("StartingWith requires enabled Redis prefix indexes");
        return Objects.requireNonNull(session, "session");
    }
    protected T prepareSave(T entity) { return entity; }
    private void scope() { TenantAccess.active(metadata); }
    private void verifyEntity(T entity) {
        Objects.requireNonNull(entity, "entity");
        if (entity.getClass() != metadata.entityType())
            throw new IllegalArgumentException("Use the exact generated entity type, not an entity subclass/proxy");
        TenantAccess.verify(metadata, entity);
    }
    private ID id(T entity) {
        verifyEntity(entity);
        return Objects.requireNonNull(metadata.idAccessor().apply(entity), "entity id");
    }

    @Override @SuppressWarnings("unchecked")
    public final <S extends T> S save(S entity) {
        scope(); verifyEntity(entity);
        T prepared = prepareSave(entity); id(prepared);
        return (S) delegate.save(prepared);
    }

    @Override @SuppressWarnings("unchecked")
    public final <S extends T> List<S> saveAll(Iterable<S> entities) {
        scope();
        var input = copy(entities, MAX_SAVE_BATCH);
        for (S entity : input) verifyEntity(entity);
        var prepared = new ArrayList<T>(input.size());
        for (S entity : input) { T row = prepareSave(entity); id(row); prepared.add(row); }
        if (prepared.isEmpty()) return List.of();
        var receipts = delegate.saveAll(prepared);
        if (receipts.size() != prepared.size()) throw new IllegalStateException("Bulk save receipt count mismatch");
        var saved = new ArrayList<S>(receipts.size());
        for (var receipt : receipts) saved.add((S) Objects.requireNonNull(receipt.entity(), "saved entity"));
        return List.copyOf(saved);
    }

    @Override public final Optional<T> findById(ID id) { scope(); return delegate.findById(Objects.requireNonNull(id, "id")); }
    @Override public final boolean existsById(ID id) { return findById(id).isPresent(); }

    @Override public final List<T> findAllById(Iterable<ID> ids) {
        scope(); var input = copy(ids, MAX_DELETE); var rows = new ArrayList<T>();
        for (int start = 0; start < input.size(); start += 512)
            rows.addAll(delegate.findAll(input.subList(start, Math.min(input.size(), start + 512))));
        return List.copyOf(rows);
    }

    @Override public final List<T> findAll() { return list(QuerySpec.allOf(), null, null, null, 0); }
    @Override public final List<T> findAll(Sort sort) { return list(QuerySpec.allOf(), null, Objects.requireNonNull(sort), null, 0); }
    @Override public final Page<T> findAll(Pageable pageable) { return page(QuerySpec.allOf(), Objects.requireNonNull(pageable), null, null, 0); }
    @Override public final long count() { return countMatching(QuerySpec.allOf()); }

    @Override public final void deleteById(ID id) { scope(); delegate.deleteById(Objects.requireNonNull(id, "id")); }
    @Override public final void delete(T entity) { delegate.deleteById(id(entity)); }

    @Override public final void deleteAllById(Iterable<? extends ID> ids) {
        scope(); List<ID> input = copy(ids, MAX_DELETE);
        deleteIds(input);
    }

    @Override public final void deleteAll(Iterable<? extends T> entities) {
        scope(); var input = copy(entities, MAX_DELETE); var ids = new ArrayList<ID>(input.size());
        for (T entity : input) ids.add(id(entity));
        deleteIds(ids);
    }

    private void deleteIds(List<ID> input) {
        var unique = new LinkedHashSet<ID>(input);
        if (unique.isEmpty()) return;
        if (delegate.capabilities().supports(RepositoryCapability.BULK_DELETE)) {
            delegate.bulkDelete(List.copyOf(unique));
            return;
        }
        // Explicit compatibility for one legacy point delete, never a hidden serial multi-delete.
        if (unique.size() == 1) { delegate.deleteById(unique.iterator().next()); return; }
        throw new RepositoryCapabilityUnavailableException(RepositoryCapability.BULK_DELETE, delegate.getClass());
    }

    @Override public final void deleteAll() { deleteMatching(QuerySpec.allOf()); }

    protected final long countMatching(QuerySpec query) { scope(); return delegate.count(checked(query)); }
    protected final boolean existsMatching(QuerySpec query) {
        scope(); return !read(checked(query.limitTo(1))).isEmpty();
    }

    protected final List<T> deleteMatching(QuerySpec query) {
        scope();
        int limit = completeSelectionLimit(MAX_DELETE + 1, "Derived delete");
        var rows = List.copyOf(read(checked(ordered(query, Sort.unsorted()).limitTo(limit))));
        if (rows.size() >= limit)
            throw new IllegalStateException("Derived delete reached its complete-selection capacity (" + limit
                    + "); the whole match set is unproven. No deletes were attempted; use explicit known IDs or a bounded administrative job");
        var ids = new ArrayList<ID>(rows.size());
        for (T row : rows) ids.add(id(row));
        deleteIds(ids);
        return rows;
    }

    protected final T single(QuerySpec query, Sort sort, boolean first) {
        scope();
        var rows = read(checked(ordered(query, sort).limitTo(first ? 1 : 2)));
        if (!first && rows.size() > 1) throw new IllegalStateException("Expected at most one Spring Data result");
        return rows.isEmpty() ? null : rows.get(0);
    }

    protected final List<T> list(QuerySpec query, Pageable pageable, Sort sort, Limit limit, int top) {
        scope(); var selection = select(query, pageable, sort, limit, top, false);
        var rows = fetch(selection);
        requireComplete(selection, rows);
        return List.copyOf(rows);
    }

    protected final Page<T> page(QuerySpec query, Pageable pageable, Sort sort, Limit limit, int top) {
        scope(); Objects.requireNonNull(pageable, "Page requires Pageable");
        var selection = select(query, pageable, sort, limit, top, false);
        var rows = fetch(selection);
        requireComplete(selection, rows);
        long total = countMatching(query);
        if (top > 0) total = Math.min(total, top);
        return new PageImpl<>(rows, pageable, total);
    }

    protected final Slice<T> slice(QuerySpec query, Pageable pageable, Sort sort, Limit limit, int top) {
        scope(); Objects.requireNonNull(pageable, "Slice requires Pageable");
        var selection = select(query, pageable, sort, limit, top, true);
        var rows = fetch(selection);
        boolean more = rows.size() > selection.size();
        return new SliceImpl<>(List.copyOf(rows.subList(0, Math.min(rows.size(), selection.size()))), pageable, more);
    }

    protected final Window<T> window(QuerySpec query, ScrollPosition position, Sort sort, Limit limit, int top, String route) {
        scope(); Objects.requireNonNull(position, "position");
        if (!(position instanceof KeysetScrollPosition keyset)) throw new IllegalArgumentException("Use keyset ScrollPosition, not offsets");
        int size = limit == null || limit.isUnlimited() ? (top == 0 ? 100 : top) : positiveLimit(limit.max());
        if (top > 0) size = Math.min(size, top);
        if (size < 1 || size > WindowRequest.MAX_LIMIT) throw new IllegalArgumentException("Window supports 1..1000 rows");
        var ordered = checked(ordered(query, sort));
        var stable = ordered.sorts();
        if (stable.size() > 16) throw new IllegalArgumentException("Window supports at most 16 sort fields");
        String tenant = TenantAccess.active(metadata);
        String scope = tenant == null ? (com.reactor.cachedb.core.tenant.CacheTenantContext.isAdministrative() ? "administrative" : "global") : "tenant:" + tenant;
        String expected = SpringDataQueryFingerprint.contract(query, route, scope, stable, metadata, codec);
        String after = null;
        if (!keyset.isInitial()) {
            Object token = keyset.getKeys().get(CURSOR);
            if (!(token instanceof String text) || keyset.getKeys().size() != 1)
                throw new IllegalArgumentException("Use the keyset position returned by this CacheDB Window");
            var values = WindowCursor.decode(text, expected);
            if (!values.keySet().equals(new LinkedHashSet<>(stable.stream().map(QuerySort::column).toList())))
                throw new IllegalArgumentException("Cursor sort fields differ");
            after = WindowCursor.encode(values);
        }
        var scanSorts = keyset.scrollsBackward() ? stable.stream().map(order ->
                new QuerySort(order.column(), order.direction() == QuerySortDirection.ASC ? QuerySortDirection.DESC : QuerySortDirection.ASC)).toList() : stable;
        // KeysetPagination adds the cursor's lexicographic filter. Do not duplicate existing ORDER BY.
        var base = QuerySpec.where(query.rootGroup()).withQueryTimeoutSeconds(30);
        var request = WindowRequest.of(size, after);
        var raw = read(checked(KeysetPagination.apply(base, request, scanSorts)));
        boolean more = raw.size() > size;
        var content = new ArrayList<T>(raw.subList(0, Math.min(size, raw.size())));
        if (keyset.scrollsBackward()) Collections.reverse(content);
        var positions = new ArrayList<ScrollPosition>(content.size()); long bytes = 0;
        for (T row : content) {
            var values = new LinkedHashMap<String, Object>();
            for (var order : stable) {
                Object value = codec.entityColumnValue(row, order.column());
                if (value == null) throw new IllegalStateException("Null keyset value: " + order.column());
                if (!(value instanceof String || value instanceof Integer || value instanceof Long
                        || value instanceof Short || value instanceof Byte || value instanceof Double
                        || value instanceof Float || value instanceof java.math.BigDecimal || value instanceof Boolean
                        || value instanceof java.time.Instant || value instanceof java.time.LocalDate
                        || value instanceof java.time.LocalDateTime || value instanceof java.time.OffsetDateTime
                        || value instanceof java.util.UUID))
                    throw new IllegalArgumentException("Sort value is not reversibly supported by WindowCursor: " + order.column());
                values.put(order.column(), value);
            }
            String token = WindowCursor.encode(values, expected);
            bytes += 2L * token.length();
            if (bytes > 16L * 1024 * 1024) throw new IllegalStateException("Window position budget exceeds 16 MiB");
            positions.add(ScrollPosition.of(Map.of(CURSOR, token), keyset.getDirection()));
        }
        return Window.from(List.copyOf(content), positions::get, more);
    }

    private record Selection(QuerySpec query, int size, boolean unbounded) {}
    private Selection select(QuerySpec query, Pageable pageable, Sort sort, Limit limit, int top, boolean lookahead) {
        boolean paged = pageable != null && pageable.isPaged();
        long offset = paged ? pageable.getOffset() : 0;
        if (offset < 0 || offset > MAX_OFFSET) throw new IllegalArgumentException("Offset exceeds 10000; use Window keyset scrolling");
        if (top < 0 || top > QuerySpec.MAX_LIMIT) throw new IllegalArgumentException("Top/First exceeds QuerySpec.MAX_LIMIT");
        int size = paged ? positiveLimit(pageable.getPageSize())
                : limit != null && limit.isLimited() ? positiveLimit(limit.max()) : top > 0 ? top : lookahead ? 100 : QuerySpec.MAX_LIMIT;
        if (top > 0) size = Math.min(size, Math.max(0, top - (int) offset));
        boolean unbounded = !paged && (limit == null || limit.isUnlimited()) && top == 0;
        if (unbounded && !lookahead) size = completeSelectionLimit(QuerySpec.MAX_LIMIT, "Unpaged result");
        if (lookahead && size == QuerySpec.MAX_LIMIT) throw new IllegalArgumentException("Slice requires room for one lookahead row");
        Sort dynamic = pageable == null ? sort : pageable.getSort();
        var result = ordered(query, dynamic).offsetBy((int) offset).limitTo(Math.max(1, size + (lookahead && size > 0 ? 1 : 0)));
        if (lookahead && top > 0 && offset + size >= top) result = result.limitTo(Math.max(1, size));
        return new Selection(checked(result), size, unbounded);
    }

    private List<T> fetch(Selection selection) {
        return selection.size() == 0 ? List.of() : read(selection.query());
    }

    private int completeSelectionLimit(int maximum, String shape) {
        var capacity = Objects.requireNonNull(delegate.entityQueryCapacity(), "entity query capacity");
        return switch (capacity.kind()) {
            case UNKNOWN -> throw new IllegalStateException(shape
                    + " requires a known entity query capacity; the backend reports UNKNOWN. Use Pageable/Limit for reads or explicit known IDs for deletes");
            case UNRESTRICTED -> maximum;
            case FINITE -> {
                if (capacity.maxRows() == 0)
                    throw new IllegalStateException(shape
                            + " has zero effective entity query capacity; no query or delete was attempted. Use explicit known IDs for deletion or an authorized bounded administrative job");
                yield Math.min(maximum, capacity.maxRows());
            }
        };
    }

    private void requireComplete(Selection selection, List<T> rows) {
        if (selection.unbounded() && rows.size() >= selection.query().limit())
            throw new IllegalStateException("Unpaged result reached its complete-selection capacity ("
                    + selection.query().limit() + "); completeness is unproven. Use Pageable or Limit; results are not silently truncated");
    }

    private List<T> read(QuerySpec query) {
        var capacity = Objects.requireNonNull(delegate.entityQueryCapacity(), "entity query capacity");
        if (capacity.kind() == EntityQueryCapacity.Kind.FINITE && query.limit() > capacity.maxRows())
            throw new IllegalArgumentException("Entity query requested " + query.limit()
                    + " rows but the effective entity query capacity is " + capacity.maxRows()
                    + ". Use a smaller Pageable/Limit, including any Slice/Window lookahead row");
        var rows = delegate.query(query);
        if (rows.size() > query.limit()) throw new IllegalStateException("Repository exceeded the requested entity query limit");
        return rows;
    }

    private int positiveLimit(int size) {
        if (size < 1 || size > QuerySpec.MAX_LIMIT) throw new IllegalArgumentException("Result size must be 1..100000");
        return size;
    }

    private QuerySpec ordered(QuerySpec query, Sort dynamic) {
        var orders = new LinkedHashMap<String, QuerySortDirection>();
        for (var order : query.sorts()) addSort(orders, order.column(), order.direction());
        if (dynamic != null) for (var order : dynamic) {
            if (order.isIgnoreCase() || order.getNullHandling() != Sort.NullHandling.NATIVE)
                throw new IllegalArgumentException("IgnoreCase and explicit null ordering are not supported");
            String column = properties.get(order.getProperty());
            if (column == null) throw new IllegalArgumentException("Unknown Spring Data sort property");
            addSort(orders, column, order.isAscending() ? QuerySortDirection.ASC : QuerySortDirection.DESC);
        }
        if (!orders.containsKey(metadata.idColumn())) addSort(orders, metadata.idColumn(), QuerySortDirection.ASC);
        var builder = QuerySpec.builder().node(query.rootGroup()).queryTimeoutSeconds(30);
        orders.forEach((column, direction) -> builder.sort(new QuerySort(column, direction)));
        return builder.build();
    }

    private void addSort(Map<String, QuerySortDirection> orders, String column, QuerySortDirection direction) {
        var old = orders.putIfAbsent(column, direction);
        if (old != null && old != direction) throw new IllegalArgumentException("Conflicting sort direction: " + column);
        if (orders.size() > 16) throw new IllegalArgumentException("At most 16 sort fields");
    }

    private QuerySpec checked(QuerySpec query) {
        com.reactor.cachedb.core.crypto.EncryptedStorageChecks.validateQuery(metadata, query);
        return SpringDataQueries.bounded(query.withQueryTimeoutSeconds(30));
    }

    private static <V> List<V> copy(Iterable<? extends V> input, int max) {
        Objects.requireNonNull(input, "input"); var copy = new ArrayList<V>();
        for (V value : input) {
            if (copy.size() == max) throw new IllegalArgumentException("Bulk input exceeds " + max);
            copy.add(Objects.requireNonNull(value, "bulk item"));
        }
        return copy;
    }
}
