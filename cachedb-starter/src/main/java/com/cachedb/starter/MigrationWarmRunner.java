package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.api.CacheSession;
import com.reactor.cachedb.core.api.EntityRepository;
import com.reactor.cachedb.core.registry.EntityBinding;
import com.reactor.cachedb.core.registry.EntityRegistry;
import com.reactor.cachedb.redis.RedisEntityRepository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

final class MigrationWarmRunner {

    private final DataSource dataSource;
    private final WarmEntityHydratorFactory hydratorFactory;
    private final MigrationPlanner planner = new MigrationPlanner();

    MigrationWarmRunner(DataSource dataSource, WarmEntityHydratorFactory hydratorFactory) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.hydratorFactory = Objects.requireNonNull(hydratorFactory, "hydratorFactory");
    }

    Result execute(Request request) {
        Request normalized = Objects.requireNonNull(request, "request").normalize();
        MigrationPlanner.Result plan = planner.plan(normalized.plannerRequest());
        WarmEntityHydrator childHydrator = hydratorFactory.resolve(plan.request().childTableOrEntity())
                .orElseThrow(() -> new IllegalArgumentException("No registered CacheDB entity found for child surface: " + plan.request().childTableOrEntity()));
        WarmEntityHydrator rootHydrator = normalized.warmRootRows()
                ? hydratorFactory.resolve(plan.request().rootTableOrEntity())
                .orElseThrow(() -> new IllegalArgumentException("No registered CacheDB entity found for root surface: " + plan.request().rootTableOrEntity()))
                : null;

        String childWarmSql = MigrationPlanner.buildChildWarmSql(
                plan.request(),
                plan.recommendedHotWindowPerRoot(),
                plan.rankedProjectionRequired(),
                childHydrator.tableName()
        );
        String rootWarmSql = rootHydrator == null
                ? ""
                : MigrationPlanner.buildRootWarmSqlTemplate(plan.request(), rootHydrator.tableName());

        Instant startedAt = Instant.now();
        long startedAtNanos = System.nanoTime();
        LinkedHashSet<Object> referencedRootIds = new LinkedHashSet<>();
        WarmCounters childCounters;
        WarmCounters rootCounters = WarmCounters.empty();
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            boolean forceImmediateProjectionRefresh = !normalized.dryRun() && plan.projectionRequired();
            boolean reindexQueryIndexes = !normalized.dryRun() && !plan.projectionRequired();
            boolean projectionOnlyChildWarm = !normalized.dryRun()
                    && plan.projectionRequired()
                    && childHydrator.supportsProjectionOnlyWarm();
            childCounters = warmChildRows(
                    connection,
                    childWarmSql,
                    normalized.childFetchSize(),
                    childHydrator,
                    plan.request().relationColumn(),
                    normalized.dryRun(),
                    projectionOnlyChildWarm,
                    forceImmediateProjectionRefresh,
                    reindexQueryIndexes,
                    referencedRootIds
            );
            if (rootHydrator != null && !referencedRootIds.isEmpty()) {
                rootCounters = warmRootRows(
                        connection,
                        rootHydrator,
                        referencedRootIds,
                        normalized.rootFetchSize(),
                        normalized.rootBatchSize(),
                        normalized.dryRun(),
                        forceImmediateProjectionRefresh,
                        reindexQueryIndexes
                );
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Migration warm execution failed: " + exception.getMessage(), exception);
        }

        long durationMillis = Math.max(0L, (System.nanoTime() - startedAtNanos) / 1_000_000L);
        Instant completedAt = Instant.now();
        ArrayList<String> notes = new ArrayList<>();
        if (normalized.dryRun()) {
            notes.add("Dry run completed. Redis was not mutated.");
        } else {
            notes.add("Redis hot entities were hydrated directly without enqueueing PostgreSQL write-behind.");
            notes.add("Warm hydration skips eager query-index rebuilds and page-cache touches so the hot set can be loaded faster.");
            if (plan.projectionRequired()) {
                if (childHydrator.supportsProjectionOnlyWarm()) {
                    notes.add("Projection-required child rows were warmed directly into the projection surface; wide child entity payload hydration was skipped.");
                }
                notes.add("The warmed route requires a projection, so projection payloads and projection query indexes were rebuilt inline before returning.");
                notes.add("Entity query indexes were deferred because this warm path is optimizing the projection route, not the wide entity route.");
            } else {
                notes.add("The warmed route does not require a projection, so entity query indexes were rebuilt inline before returning.");
            }
        }
        if (normalized.warmRootRows()) {
            notes.add("Root hydration used referenced root ids from the warmed child window.");
        } else {
            notes.add("Root hydration was skipped. Warm the root surface separately if the route also needs a hot root list.");
        }
        if (plan.rankedProjectionRequired()) {
            notes.add("Ranked / global-sorted routes used a single top-window warm query instead of a per-parent partitioned warm query.");
        }
        long missingReferencedRoots = Math.max(0L, referencedRootIds.size() - rootCounters.hydratedRows());
        if (missingReferencedRoots > 0L) {
            notes.add("Some referenced root ids were not found in PostgreSQL during warm execution: " + missingReferencedRoots);
        }

        return new Result(
                normalized,
                plan,
                normalized.dryRun(),
                rootHydrator == null ? "" : rootHydrator.entityName(),
                childHydrator.entityName(),
                childCounters.readRows(),
                childCounters.hydratedRows(),
                rootCounters.readRows(),
                rootCounters.hydratedRows(),
                childCounters.skippedDeletedRows() + rootCounters.skippedDeletedRows(),
                referencedRootIds.size(),
                missingReferencedRoots,
                childWarmSql,
                rootWarmSql,
                List.copyOf(notes),
                startedAt,
                completedAt,
                durationMillis
        );
    }

    private WarmCounters warmChildRows(
            Connection connection,
            String childWarmSql,
            int fetchSize,
            WarmEntityHydrator hydrator,
            String relationColumn,
            boolean dryRun,
            boolean projectionOnlyWarm,
            boolean forceImmediateProjectionRefresh,
            boolean reindexQueryIndexes,
            LinkedHashSet<Object> referencedRootIds
    ) throws SQLException {
        long readRows = 0L;
        long hydratedRows = 0L;
        long skippedDeletedRows = 0L;
        int hydrateBatchSize = Math.max(32, Math.min(fetchSize, 512));
        ArrayList<Map<String, Object>> pendingRows = dryRun ? null : new ArrayList<>(hydrateBatchSize);
        ArrayList<Long> pendingVersions = dryRun ? null : new ArrayList<>(hydrateBatchSize);
        try (PreparedStatement statement = connection.prepareStatement(childWarmSql)) {
            statement.setFetchSize(Math.max(1, fetchSize));
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    Map<String, Object> row = readRow(resultSet);
                    readRows++;
                    if (isDeleted(row, hydrator)) {
                        skippedDeletedRows++;
                        continue;
                    }
                    Object relationValue = columnValue(row, relationColumn);
                    if (relationValue != null) {
                        referencedRootIds.add(relationValue);
                    }
                    if (!dryRun) {
                        pendingRows.add(row);
                        pendingVersions.add(versionValue(row, hydrator));
                        if (pendingRows.size() >= hydrateBatchSize) {
                            hydrateBatch(
                                    hydrator,
                                    pendingRows,
                                    pendingVersions,
                                    projectionOnlyWarm,
                                    forceImmediateProjectionRefresh,
                                    reindexQueryIndexes
                            );
                            pendingRows.clear();
                            pendingVersions.clear();
                        }
                    }
                    hydratedRows++;
                }
            }
        }
        if (!dryRun && pendingRows != null && !pendingRows.isEmpty()) {
            hydrateBatch(
                    hydrator,
                    pendingRows,
                    pendingVersions,
                    projectionOnlyWarm,
                    forceImmediateProjectionRefresh,
                    reindexQueryIndexes
            );
        }
        return new WarmCounters(readRows, hydratedRows, skippedDeletedRows);
    }

    private void hydrateBatch(
            WarmEntityHydrator hydrator,
            List<Map<String, Object>> rows,
            List<Long> versions,
            boolean projectionOnlyWarm,
            boolean forceImmediateProjectionRefresh,
            boolean reindexQueryIndexes
    ) {
        if (projectionOnlyWarm) {
            hydrator.hydrateProjectionBatch(rows, versions);
            return;
        }
        hydrator.hydrateBatch(rows, versions, forceImmediateProjectionRefresh, reindexQueryIndexes);
    }

    private WarmCounters warmRootRows(
            Connection connection,
            WarmEntityHydrator hydrator,
            Collection<Object> rootIds,
            int fetchSize,
            int batchSize,
            boolean dryRun,
            boolean forceImmediateProjectionRefresh,
            boolean reindexQueryIndexes
    ) throws SQLException {
        if (rootIds.isEmpty()) {
            return WarmCounters.empty();
        }
        long readRows = 0L;
        long hydratedRows = 0L;
        long skippedDeletedRows = 0L;
        List<Object> ids = new ArrayList<>(rootIds);
        int effectiveBatchSize = Math.max(1, batchSize);
        for (int start = 0; start < ids.size(); start += effectiveBatchSize) {
            List<Object> chunk = ids.subList(start, Math.min(ids.size(), start + effectiveBatchSize));
            String sql = buildRootChunkSql(hydrator.tableName(), hydrator.idColumn(), chunk.size());
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setFetchSize(Math.max(1, fetchSize));
                ArrayList<Map<String, Object>> pendingRows = dryRun ? null : new ArrayList<>(chunk.size());
                ArrayList<Long> pendingVersions = dryRun ? null : new ArrayList<>(chunk.size());
                for (int index = 0; index < chunk.size(); index++) {
                    statement.setObject(index + 1, chunk.get(index));
                }
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        Map<String, Object> row = readRow(resultSet);
                        readRows++;
                        if (isDeleted(row, hydrator)) {
                            skippedDeletedRows++;
                            continue;
                        }
                        if (!dryRun) {
                            pendingRows.add(row);
                            pendingVersions.add(versionValue(row, hydrator));
                        }
                        hydratedRows++;
                    }
                }
                if (!dryRun && pendingRows != null && !pendingRows.isEmpty()) {
                    hydrator.hydrateBatch(
                            pendingRows,
                            pendingVersions,
                            forceImmediateProjectionRefresh,
                            reindexQueryIndexes
                    );
                }
            }
        }
        return new WarmCounters(readRows, hydratedRows, skippedDeletedRows);
    }

    private String buildRootChunkSql(String tableName, String rootPrimaryKeyColumn, int parameterCount) {
        StringBuilder builder = new StringBuilder("SELECT * FROM ")
                .append(tableName)
                .append(" WHERE ")
                .append(rootPrimaryKeyColumn)
                .append(" IN (");
        for (int index = 0; index < parameterCount; index++) {
            if (index > 0) {
                builder.append(", ");
            }
            builder.append('?');
        }
        builder.append(") ORDER BY ").append(rootPrimaryKeyColumn).append(" ASC");
        return builder.toString();
    }

    private Map<String, Object> readRow(ResultSet resultSet) throws SQLException {
        ResultSetMetaData metadata = resultSet.getMetaData();
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        for (int index = 1; index <= metadata.getColumnCount(); index++) {
            String label = metadata.getColumnLabel(index);
            if (label == null || label.isBlank()) {
                label = metadata.getColumnName(index);
            }
            row.put(label, resultSet.getObject(index));
        }
        return row;
    }

    private boolean isDeleted(Map<String, Object> row, WarmEntityHydrator hydrator) {
        if (hydrator.deletedColumn() == null || hydrator.deletedColumn().isBlank()) {
            return false;
        }
        Object raw = columnValue(row, hydrator.deletedColumn());
        if (raw == null) {
            return false;
        }
        return String.valueOf(raw).equalsIgnoreCase(hydrator.deletedMarkerValue());
    }

    private long versionValue(Map<String, Object> row, WarmEntityHydrator hydrator) {
        String versionColumn = hydrator.versionColumn();
        if (versionColumn == null || versionColumn.isBlank()) {
            return 1L;
        }
        Object raw = columnValue(row, versionColumn);
        if (raw == null) {
            return 1L;
        }
        if (raw instanceof Number number) {
            return Math.max(1L, number.longValue());
        }
        try {
            return Math.max(1L, Long.parseLong(String.valueOf(raw)));
        } catch (NumberFormatException ignored) {
            return 1L;
        }
    }

    private Object columnValue(Map<String, Object> row, String columnName) {
        if (row.containsKey(columnName)) {
            return row.get(columnName);
        }
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(columnName)) {
                return entry.getValue();
            }
        }
        return null;
    }

    static WarmEntityHydratorFactory using(EntityRegistry entityRegistry, CacheSession cacheSession) {
        return new RegistryWarmEntityHydratorFactory(entityRegistry, cacheSession);
    }

    interface WarmEntityHydratorFactory {
        Optional<WarmEntityHydrator> resolve(String entityOrTableName);
    }

    interface WarmEntityHydrator {
        String entityName();
        String tableName();
        String idColumn();
        String versionColumn();
        String deletedColumn();
        String deletedMarkerValue();
        void hydrate(Map<String, Object> row, long version);
        default void hydrateBatch(List<Map<String, Object>> rows, List<Long> versions) {
            hydrateBatch(rows, versions, false);
        }
        default void hydrateBatch(List<Map<String, Object>> rows, List<Long> versions, boolean forceImmediateProjectionRefresh) {
            hydrateBatch(rows, versions, forceImmediateProjectionRefresh, false);
        }
        default boolean supportsProjectionOnlyWarm() {
            return false;
        }
        default void hydrateProjectionBatch(List<Map<String, Object>> rows, List<Long> versions) {
            hydrateBatch(rows, versions, true, false);
        }
        default void hydrateBatch(
                List<Map<String, Object>> rows,
                List<Long> versions,
                boolean forceImmediateProjectionRefresh,
                boolean reindexQueryIndexes
        ) {
            for (int index = 0; index < rows.size(); index++) {
                hydrate(rows.get(index), versions.get(index));
            }
        }
    }

    record Request(
            MigrationPlanner.Request plannerRequest,
            boolean warmRootRows,
            boolean dryRun,
            int childFetchSize,
            int rootFetchSize,
            int rootBatchSize
    ) {
        Request normalize() {
            return new Request(
                    plannerRequest == null ? MigrationPlanner.Request.defaults() : plannerRequest.normalize(),
                    warmRootRows,
                    dryRun,
                    Math.max(1, childFetchSize),
                    Math.max(1, rootFetchSize),
                    Math.max(1, rootBatchSize)
            );
        }
    }

    record Result(
            Request request,
            MigrationPlanner.Result plan,
            boolean dryRun,
            String rootEntityName,
            String childEntityName,
            long childRowsRead,
            long childRowsHydrated,
            long rootRowsRead,
            long rootRowsHydrated,
            long skippedDeletedRows,
            long distinctReferencedRootIds,
            long missingReferencedRootIds,
            String childWarmSql,
            String rootWarmSql,
            List<String> notes,
            Instant startedAt,
            Instant completedAt,
            long durationMillis
    ) {
    }

    private record WarmCounters(
            long readRows,
            long hydratedRows,
            long skippedDeletedRows
    ) {
        private static WarmCounters empty() {
            return new WarmCounters(0L, 0L, 0L);
        }
    }

    private static final class RegistryWarmEntityHydratorFactory implements WarmEntityHydratorFactory {
        private final EntityRegistry entityRegistry;
        private final CacheSession cacheSession;

        private RegistryWarmEntityHydratorFactory(EntityRegistry entityRegistry, CacheSession cacheSession) {
            this.entityRegistry = Objects.requireNonNull(entityRegistry, "entityRegistry");
            this.cacheSession = Objects.requireNonNull(cacheSession, "cacheSession");
        }

        @Override
        public Optional<WarmEntityHydrator> resolve(String entityOrTableName) {
            if (entityOrTableName == null || entityOrTableName.isBlank()) {
                return Optional.empty();
            }
            String normalized = entityOrTableName.trim();
            return entityRegistry.all().stream()
                    .filter(binding -> matches(binding, normalized))
                    .findFirst()
                    .map(binding -> createHydrator(binding, cacheSession));
        }

        private boolean matches(EntityBinding<?, ?> binding, String value) {
            return binding.metadata().entityName().equalsIgnoreCase(value)
                    || binding.metadata().tableName().equalsIgnoreCase(value);
        }

        @SuppressWarnings("unchecked")
        private <T, ID> WarmEntityHydrator createHydrator(EntityBinding<?, ?> rawBinding, CacheSession cacheSession) {
            EntityBinding<T, ID> binding = (EntityBinding<T, ID>) rawBinding;
            EntityRepository<T, ID> repository = cacheSession.repository(binding);
            if (!(repository instanceof RedisEntityRepository<?, ?> rawRedisRepository)) {
                throw new IllegalStateException("Warm execution requires a RedisEntityRepository for " + binding.metadata().entityName());
            }
            RedisEntityRepository<T, ID> redisRepository = (RedisEntityRepository<T, ID>) rawRedisRepository;
            return new WarmEntityHydrator() {
                @Override
                public String entityName() {
                    return binding.metadata().entityName();
                }

                @Override
                public String tableName() {
                    return binding.metadata().tableName();
                }

                @Override
                public String idColumn() {
                    return binding.metadata().idColumn();
                }

                @Override
                public String versionColumn() {
                    return binding.metadata().versionColumn();
                }

                @Override
                public String deletedColumn() {
                    return binding.metadata().deletedColumn();
                }

                @Override
                public String deletedMarkerValue() {
                    return binding.metadata().deletedMarkerValue();
                }

                @Override
                public void hydrate(Map<String, Object> row, long version) {
                    T entity = binding.codec().fromColumns(row);
                    redisRepository.hydrateWarm(entity, version);
                }

                @Override
                public void hydrateBatch(List<Map<String, Object>> rows, List<Long> versions) {
                    hydrateBatch(rows, versions, false);
                }

                @Override
                public void hydrateBatch(List<Map<String, Object>> rows, List<Long> versions, boolean forceImmediateProjectionRefresh) {
                    hydrateBatch(rows, versions, forceImmediateProjectionRefresh, false);
                }

                @Override
                public void hydrateBatch(
                        List<Map<String, Object>> rows,
                        List<Long> versions,
                        boolean forceImmediateProjectionRefresh,
                        boolean reindexQueryIndexes
                ) {
                    ArrayList<T> entities = new ArrayList<>(rows.size());
                    for (Map<String, Object> row : rows) {
                        entities.add(binding.codec().fromColumns(row));
                    }
                    redisRepository.hydrateWarmBatch(entities, versions, forceImmediateProjectionRefresh, reindexQueryIndexes);
                }

                @Override
                public boolean supportsProjectionOnlyWarm() {
                    return true;
                }

                @Override
                public void hydrateProjectionBatch(List<Map<String, Object>> rows, List<Long> versions) {
                    ArrayList<T> entities = new ArrayList<>(rows.size());
                    for (Map<String, Object> row : rows) {
                        entities.add(binding.codec().fromColumns(row));
                    }
                    redisRepository.hydrateProjectionWarmBatch(entities);
                }
            };
        }
    }
}
