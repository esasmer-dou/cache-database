package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.api.CacheSession;
import com.reactor.cachedb.core.api.EntityRepository;
import com.reactor.cachedb.core.api.ProjectionRepository;
import com.reactor.cachedb.core.projection.EntityProjectionBinding;
import com.reactor.cachedb.core.query.QueryFilter;
import com.reactor.cachedb.core.query.QuerySort;
import com.reactor.cachedb.core.query.QuerySpec;
import com.reactor.cachedb.core.registry.EntityBinding;
import com.reactor.cachedb.core.registry.EntityRegistry;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class MigrationComparisonRunner {

    private static final Pattern TEMPLATE_PARAMETER_PATTERN = Pattern.compile(":sample_root_id|:page_size");

    private final DataSource dataSource;
    private final MigrationSchemaDiscovery discovery;
    private final MigrationWarmRunner warmRunner;
    private final CacheRouteExecutorFactory cacheRouteExecutorFactory;
    private final MigrationPlanner planner = new MigrationPlanner();

    MigrationComparisonRunner(
            DataSource dataSource,
            MigrationSchemaDiscovery discovery,
            MigrationWarmRunner warmRunner,
            CacheRouteExecutorFactory cacheRouteExecutorFactory
    ) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.discovery = Objects.requireNonNull(discovery, "discovery");
        this.warmRunner = Objects.requireNonNull(warmRunner, "warmRunner");
        this.cacheRouteExecutorFactory = Objects.requireNonNull(cacheRouteExecutorFactory, "cacheRouteExecutorFactory");
    }

    Result execute(Request request) {
        Request normalized = Objects.requireNonNull(request, "request").normalize();
        MigrationPlanner.Result plan = planner.plan(normalized.plannerRequest());
        MigrationSchemaDiscovery.Result discoveryResult = discovery.discover();
        MigrationSchemaDiscovery.TableInfo childTable = resolveTable(discoveryResult.tables(), plan.request().childTableOrEntity())
                .orElseThrow(() -> new IllegalArgumentException("Could not find child table/entity in discovered schema: " + plan.request().childTableOrEntity()));
        MigrationSchemaDiscovery.ColumnInfo relationColumn = childTable.columns().stream()
                .filter(column -> normalize(column.name()).equals(normalize(plan.request().relationColumn())))
                .findFirst()
                .orElse(null);

        MigrationWarmRunner.Request warmRequest = new MigrationWarmRunner.Request(
                plan.request(),
                normalized.warmRootRows(),
                false,
                normalized.childFetchSize(),
                normalized.rootFetchSize(),
                normalized.rootBatchSize()
        );
        MigrationWarmRunner.Result warmResult = null;
        if (normalized.warmBeforeCompare()) {
            warmResult = warmRunner.execute(warmRequest);
        }

        CacheRouteExecutor cacheRouteExecutor = cacheRouteExecutorFactory.resolve(plan, normalized)
                .orElseThrow(() -> new IllegalArgumentException("Could not resolve a registered CacheDB route for child surface: " + plan.request().childTableOrEntity()));

        String baselineSqlTemplate = normalized.baselineSqlOverride().isBlank()
                ? buildDerivedBaselineSql(plan, childTable.qualifiedTableName())
                : normalized.baselineSqlOverride().trim();

        Instant startedAt = Instant.now();
        long startedAtNanos = System.nanoTime();
        ArrayList<String> notes = new ArrayList<>();
        ArrayList<String> warnings = new ArrayList<>();
        if (plan.request().thresholdOrRangeScreen()) {
            warnings.add("Threshold/range routes are compared with a top-window baseline unless you provide an explicit baseline SQL override.");
        }
        if (!normalized.baselineSqlOverride().isBlank()) {
            notes.add("Baseline comparison used the explicit SQL override supplied from the planner form.");
        } else {
            notes.add("Baseline comparison used a derived PostgreSQL list SQL built from the planner request.");
        }

        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            List<SampleRoute> samples = resolveSamples(connection, childTable, relationColumn, normalized, plan);
            if (samples.isEmpty()) {
                throw new IllegalStateException("No sample root ids were available for the requested route. Provide comparisonSampleRootId or seed representative child rows first.");
            }

            List<SampleComparison> sampleComparisons = compareSamples(
                    connection,
                    baselineSqlTemplate,
                    cacheRouteExecutor,
                    samples,
                    normalized.pageSize()
            );
            if (shouldAutoWarmRetry(cacheRouteExecutor, warmResult, sampleComparisons)) {
                warmResult = warmRunner.execute(warmRequest);
                notes.add("The initial CacheDB comparison route returned an empty cold page. The runner executed a staging warm automatically and retried the comparison.");
                sampleComparisons = compareSamples(
                        connection,
                        baselineSqlTemplate,
                        cacheRouteExecutor,
                        samples,
                        normalized.pageSize()
                );
            }
            Metrics baselineMetrics = measureBaseline(connection, baselineSqlTemplate, samples, normalized.pageSize(), normalized.warmupIterations(), normalized.measuredIterations(), plan.request().childPrimaryKeyColumn());
            Metrics cacheMetrics = measureCache(cacheRouteExecutor, samples, normalized.pageSize(), normalized.warmupIterations(), normalized.measuredIterations());

            long durationMillis = Math.max(0L, (System.nanoTime() - startedAtNanos) / 1_000_000L);
            Instant completedAt = Instant.now();
            long exactMatchCount = sampleComparisons.stream().filter(SampleComparison::exactMatch).count();
            if (exactMatchCount != sampleComparisons.size()) {
                warnings.add("At least one sample route returned a different first-page membership/order between PostgreSQL and CacheDB. Inspect the baseline SQL and projection contract before cutover.");
            }
            if (cacheRouteExecutor.usesProjection()) {
                notes.add("CacheDB comparison route used projection surface: " + cacheRouteExecutor.routeLabel());
            } else {
                warnings.add("CacheDB comparison fell back to an entity query because no matching projection was registered.");
            }
            if (warmResult != null) {
                notes.add("The comparison runner warmed the Redis hot set immediately before measuring the CacheDB route.");
            }

            Result result = new Result(
                    normalized,
                    plan,
                    cacheRouteExecutor.routeLabel(),
                    baselineSqlTemplate,
                    warmResult,
                    baselineMetrics,
                    cacheMetrics,
                    sampleComparisons,
                    List.copyOf(notes),
                    List.copyOf(warnings),
                    startedAt,
                    completedAt,
                    durationMillis,
                    null
            );
            return new Result(
                    result.request(),
                    result.plan(),
                    result.cacheRouteLabel(),
                    result.baselineSqlTemplate(),
                    result.warmResult(),
                    result.baselineMetrics(),
                    result.cacheMetrics(),
                    result.sampleComparisons(),
                    result.notes(),
                    result.warnings(),
                    result.startedAt(),
                    result.completedAt(),
                    result.durationMillis(),
                    MigrationComparisonAssessment.evaluate(result)
            );
        } catch (SQLException exception) {
            throw new IllegalStateException("Migration comparison failed: " + exception.getMessage(), exception);
        }
    }

    private Optional<MigrationSchemaDiscovery.TableInfo> resolveTable(
            List<MigrationSchemaDiscovery.TableInfo> tables,
            String surface
    ) {
        if (surface == null || surface.isBlank()) {
            return Optional.empty();
        }
        String normalized = normalize(surface);
        return tables.stream()
                .filter(table -> normalize(table.tableName()).equals(normalized)
                        || normalize(table.qualifiedTableName()).equals(normalized)
                        || normalize(table.registeredEntityName()).equals(normalized))
                .findFirst();
    }

    private String buildDerivedBaselineSql(MigrationPlanner.Result plan, String childTableName) {
        String sortDirection = "ASC".equalsIgnoreCase(plan.request().sortDirection()) ? "ASC" : "DESC";
        if (plan.rankedProjectionRequired()) {
            return """
                    SELECT *
                    FROM %s source
                    ORDER BY %s %s, %s DESC
                    LIMIT :page_size
                    """.formatted(
                    childTableName,
                    plan.request().sortColumn(),
                    sortDirection,
                    plan.request().childPrimaryKeyColumn()
            ).trim();
        }
        return """
                SELECT *
                FROM %s source
                WHERE %s = :sample_root_id
                ORDER BY %s %s, %s DESC
                LIMIT :page_size
                """.formatted(
                childTableName,
                plan.request().relationColumn(),
                plan.request().sortColumn(),
                sortDirection,
                plan.request().childPrimaryKeyColumn()
        ).trim();
    }

    private List<SampleRoute> resolveSamples(
            Connection connection,
            MigrationSchemaDiscovery.TableInfo childTable,
            MigrationSchemaDiscovery.ColumnInfo relationColumn,
            Request request,
            MigrationPlanner.Result plan
    ) throws SQLException {
        if (plan.rankedProjectionRequired()) {
            return List.of(new SampleRoute("global-top-window", null));
        }
        if (request.sampleRootId() != null && !request.sampleRootId().isBlank()) {
            Object coerced = coerceLiteral(request.sampleRootId().trim(), relationColumn);
            return List.of(new SampleRoute(String.valueOf(coerced), coerced));
        }
        String sql = """
                SELECT %s AS cachedb_sample_root_id, COUNT(*) AS cachedb_child_count
                FROM %s
                WHERE %s IS NOT NULL
                GROUP BY %s
                ORDER BY COUNT(*) DESC, %s ASC
                LIMIT ?
                """.formatted(
                plan.request().relationColumn(),
                childTable.qualifiedTableName(),
                plan.request().relationColumn(),
                plan.request().relationColumn(),
                plan.request().relationColumn()
        );
        ArrayList<SampleRoute> samples = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, Math.max(1, request.sampleRootCount()));
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    Object rootId = resultSet.getObject("cachedb_sample_root_id");
                    samples.add(new SampleRoute(String.valueOf(rootId), rootId));
                }
            }
        }
        return List.copyOf(samples);
    }

    private Object coerceLiteral(String raw, MigrationSchemaDiscovery.ColumnInfo column) {
        if (column == null) {
            return raw;
        }
        String type = normalize(column.jdbcTypeName());
        try {
            if (type.contains("bigint") || type.contains("bigserial")) {
                return Long.valueOf(raw);
            }
            if (type.contains("int") || type.contains("serial")) {
                return Integer.valueOf(raw);
            }
            if (type.contains("smallint")) {
                return Short.valueOf(raw);
            }
            if (type.contains("decimal") || type.contains("numeric")) {
                return new java.math.BigDecimal(raw);
            }
            if (type.contains("double")) {
                return Double.valueOf(raw);
            }
            if (type.contains("float") || type.contains("real")) {
                return Float.valueOf(raw);
            }
            if (type.contains("bool")) {
                return Boolean.valueOf(raw);
            }
        } catch (NumberFormatException ignored) {
            return raw;
        }
        return raw;
    }

    private List<SampleComparison> compareSamples(
            Connection connection,
            String baselineSqlTemplate,
            CacheRouteExecutor cacheRouteExecutor,
            List<SampleRoute> samples,
            int pageSize
    ) throws SQLException {
        ArrayList<SampleComparison> comparisons = new ArrayList<>();
        for (SampleRoute sample : samples) {
            RoutePage baselinePage = executeBaselinePage(connection, baselineSqlTemplate, sample, pageSize, cacheRouteExecutor.idColumn());
            RoutePage cachePage = cacheRouteExecutor.execute(sample.rootId(), pageSize);
            comparisons.add(new SampleComparison(
                    sample.label(),
                    baselinePage.rowCount(),
                    cachePage.rowCount(),
                    baselinePage.ids(),
                    cachePage.ids(),
                    baselinePage.ids().equals(cachePage.ids())
            ));
        }
        return List.copyOf(comparisons);
    }

    private boolean shouldAutoWarmRetry(
            CacheRouteExecutor cacheRouteExecutor,
            MigrationWarmRunner.Result warmResult,
            List<SampleComparison> sampleComparisons
    ) {
        if (warmResult != null || !cacheRouteExecutor.usesProjection() || sampleComparisons.isEmpty()) {
            return false;
        }
        boolean anyBaselineRows = sampleComparisons.stream().anyMatch(sample -> sample.baselineRowCount() > 0);
        boolean allCacheRowsEmpty = sampleComparisons.stream().allMatch(sample -> sample.cacheRowCount() == 0);
        return anyBaselineRows && allCacheRowsEmpty;
    }

    private Metrics measureBaseline(
            Connection connection,
            String baselineSqlTemplate,
            List<SampleRoute> samples,
            int pageSize,
            int warmupIterations,
            int measuredIterations,
            String idColumn
    ) throws SQLException {
        long[] latencies = new long[measuredIterations];
        long totalRows = 0L;
        for (int iteration = 0; iteration < warmupIterations; iteration++) {
            executeBaselinePage(connection, baselineSqlTemplate, samples.get(iteration % samples.size()), pageSize, idColumn);
        }
        for (int iteration = 0; iteration < measuredIterations; iteration++) {
            SampleRoute sample = samples.get(iteration % samples.size());
            long startedAt = System.nanoTime();
            RoutePage page = executeBaselinePage(connection, baselineSqlTemplate, sample, pageSize, idColumn);
            latencies[iteration] = System.nanoTime() - startedAt;
            totalRows += page.rowCount();
        }
        return Metrics.from(latencies, totalRows, measuredIterations);
    }

    private Metrics measureCache(
            CacheRouteExecutor cacheRouteExecutor,
            List<SampleRoute> samples,
            int pageSize,
            int warmupIterations,
            int measuredIterations
    ) {
        long[] latencies = new long[measuredIterations];
        long totalRows = 0L;
        for (int iteration = 0; iteration < warmupIterations; iteration++) {
            cacheRouteExecutor.execute(samples.get(iteration % samples.size()).rootId(), pageSize);
        }
        for (int iteration = 0; iteration < measuredIterations; iteration++) {
            SampleRoute sample = samples.get(iteration % samples.size());
            long startedAt = System.nanoTime();
            RoutePage page = cacheRouteExecutor.execute(sample.rootId(), pageSize);
            latencies[iteration] = System.nanoTime() - startedAt;
            totalRows += page.rowCount();
        }
        return Metrics.from(latencies, totalRows, measuredIterations);
    }

    private RoutePage executeBaselinePage(
            Connection connection,
            String sqlTemplate,
            SampleRoute sample,
            int pageSize,
            String idColumn
    ) throws SQLException {
        PreparedSql preparedSql = prepareSql(sqlTemplate);
        ArrayList<String> ids = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(preparedSql.sql())) {
            int parameterIndex = 1;
            for (String parameter : preparedSql.parameterOrder()) {
                if ("sample_root_id".equals(parameter)) {
                    statement.setObject(parameterIndex++, sample.rootId());
                } else if ("page_size".equals(parameter)) {
                    statement.setInt(parameterIndex++, Math.max(1, pageSize));
                }
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    Object id = columnValue(resultSet, idColumn);
                    ids.add(String.valueOf(id));
                }
            }
        }
        return new RoutePage(ids.size(), List.copyOf(ids));
    }

    private PreparedSql prepareSql(String template) {
        Matcher matcher = TEMPLATE_PARAMETER_PATTERN.matcher(template);
        StringBuffer buffer = new StringBuffer();
        ArrayList<String> parameters = new ArrayList<>();
        while (matcher.find()) {
            String token = matcher.group();
            parameters.add(token.substring(1));
            matcher.appendReplacement(buffer, "?");
        }
        matcher.appendTail(buffer);
        return new PreparedSql(buffer.toString(), List.copyOf(parameters));
    }

    private Object columnValue(ResultSet resultSet, String requestedColumn) throws SQLException {
        ResultSetMetaData metadata = resultSet.getMetaData();
        for (int index = 1; index <= metadata.getColumnCount(); index++) {
            String label = metadata.getColumnLabel(index);
            if (label != null && label.equalsIgnoreCase(requestedColumn)) {
                return resultSet.getObject(index);
            }
            String columnName = metadata.getColumnName(index);
            if (columnName != null && columnName.equalsIgnoreCase(requestedColumn)) {
                return resultSet.getObject(index);
            }
        }
        throw new IllegalStateException("Baseline SQL did not return the expected id column: " + requestedColumn);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    interface CacheRouteExecutorFactory {
        Optional<CacheRouteExecutor> resolve(MigrationPlanner.Result plan, Request request);
    }

    interface CacheRouteExecutor {
        String routeLabel();
        String idColumn();
        boolean usesProjection();
        RoutePage execute(Object sampleRootId, int pageSize);
    }

    static CacheRouteExecutorFactory using(EntityRegistry entityRegistry, CacheSession cacheSession) {
        return new RegistryCacheRouteExecutorFactory(entityRegistry, cacheSession);
    }

    record Request(
            MigrationPlanner.Request plannerRequest,
            boolean warmBeforeCompare,
            boolean warmRootRows,
            int childFetchSize,
            int rootFetchSize,
            int rootBatchSize,
            String sampleRootId,
            int sampleRootCount,
            int warmupIterations,
            int measuredIterations,
            int pageSize,
            String projectionNameOverride,
            String baselineSqlOverride
    ) {
        Request normalize() {
            MigrationPlanner.Request normalizedPlanner = plannerRequest == null
                    ? MigrationPlanner.Request.defaults()
                    : plannerRequest.normalize();
            return new Request(
                    normalizedPlanner,
                    warmBeforeCompare,
                    warmRootRows,
                    Math.max(1, childFetchSize),
                    Math.max(1, rootFetchSize),
                    Math.max(1, rootBatchSize),
                    sampleRootId == null ? "" : sampleRootId.trim(),
                    Math.max(1, sampleRootCount),
                    Math.max(0, warmupIterations),
                    Math.max(1, measuredIterations),
                    Math.max(1, pageSize > 0 ? pageSize : normalizedPlanner.firstPageSize()),
                    projectionNameOverride == null ? "" : projectionNameOverride.trim(),
                    baselineSqlOverride == null ? "" : baselineSqlOverride.trim()
            );
        }
    }

    record Result(
            Request request,
            MigrationPlanner.Result plan,
            String cacheRouteLabel,
            String baselineSqlTemplate,
            MigrationWarmRunner.Result warmResult,
            Metrics baselineMetrics,
            Metrics cacheMetrics,
            List<SampleComparison> sampleComparisons,
            List<String> notes,
            List<String> warnings,
            Instant startedAt,
            Instant completedAt,
            long durationMillis,
            MigrationComparisonAssessment.Result assessment
    ) {
    }

    record SampleComparison(
            String sampleLabel,
            int baselineRowCount,
            int cacheRowCount,
            List<String> baselineIds,
            List<String> cacheIds,
            boolean exactMatch
    ) {
    }

    record Metrics(
            long operations,
            long averageLatencyNanos,
            long p95LatencyNanos,
            long p99LatencyNanos,
            double averageRowsPerOperation
    ) {
        private static Metrics from(long[] latencies, long totalRows, int operations) {
            long[] sorted = latencies.clone();
            java.util.Arrays.sort(sorted);
            long averageLatency = operations <= 0 ? 0L : java.util.Arrays.stream(latencies).sum() / operations;
            return new Metrics(
                    operations,
                    averageLatency,
                    percentile(sorted, 0.95d),
                    percentile(sorted, 0.99d),
                    operations <= 0 ? 0.0d : totalRows / (double) operations
            );
        }

        private static long percentile(long[] values, double percentile) {
            if (values.length == 0) {
                return 0L;
            }
            int index = (int) Math.ceil(percentile * values.length) - 1;
            index = Math.max(0, Math.min(index, values.length - 1));
            return values[index];
        }
    }

    private record PreparedSql(String sql, List<String> parameterOrder) {
    }

    private record SampleRoute(String label, Object rootId) {
    }

    record RoutePage(int rowCount, List<String> ids) {
    }

    private static final class RegistryCacheRouteExecutorFactory implements CacheRouteExecutorFactory {
        private final EntityRegistry entityRegistry;
        private final CacheSession cacheSession;

        private RegistryCacheRouteExecutorFactory(EntityRegistry entityRegistry, CacheSession cacheSession) {
            this.entityRegistry = Objects.requireNonNull(entityRegistry, "entityRegistry");
            this.cacheSession = Objects.requireNonNull(cacheSession, "cacheSession");
        }

        @Override
        public Optional<CacheRouteExecutor> resolve(MigrationPlanner.Result plan, Request request) {
            Optional<EntityBinding<?, ?>> bindingOptional = entityRegistry.all().stream()
                    .filter(binding -> matches(binding, plan.request().childTableOrEntity()))
                    .findFirst();
            if (bindingOptional.isEmpty()) {
                return Optional.empty();
            }
            EntityBinding<?, ?> binding = bindingOptional.get();
            Optional<EntityProjectionBinding<?, ?, ?>> projectionBinding = resolveProjection(binding, plan, request.projectionNameOverride());
            if (projectionBinding.isPresent()) {
                return Optional.of(projectionExecutor(plan, binding, projectionBinding.get(), cacheSession));
            }
            return Optional.of(entityExecutor(plan, binding, cacheSession));
        }

        private boolean matches(EntityBinding<?, ?> binding, String surface) {
            return binding.metadata().entityName().equalsIgnoreCase(surface)
                    || binding.metadata().tableName().equalsIgnoreCase(surface);
        }

        private Optional<EntityProjectionBinding<?, ?, ?>> resolveProjection(
                EntityBinding<?, ?> binding,
                MigrationPlanner.Result plan,
                String override
        ) {
            ArrayList<String> candidates = new ArrayList<>();
            if (override != null && !override.isBlank()) {
                candidates.add(override);
            }
            if (plan.rankedProjectionName() != null && !plan.rankedProjectionName().isBlank()) {
                candidates.add(plan.rankedProjectionName());
            }
            if (plan.summaryProjectionName() != null && !plan.summaryProjectionName().isBlank()) {
                candidates.add(plan.summaryProjectionName());
            }
            for (String candidate : candidates) {
                Optional<EntityProjectionBinding<?, ?, ?>> projection = entityRegistry.findProjection(binding.metadata().entityName(), candidate);
                if (projection.isPresent()) {
                    return projection;
                }
                Optional<EntityProjectionBinding<?, ?, ?>> caseInsensitiveProjection = entityRegistry.projections(binding.metadata().entityName()).stream()
                        .filter(item -> item.projection().name().equalsIgnoreCase(candidate))
                        .findFirst();
                if (caseInsensitiveProjection.isPresent()) {
                    return caseInsensitiveProjection;
                }
            }
            return entityRegistry.projections(binding.metadata().entityName()).stream().findFirst();
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        private CacheRouteExecutor projectionExecutor(
                MigrationPlanner.Result plan,
                EntityBinding binding,
                EntityProjectionBinding projectionBinding,
                CacheSession cacheSession
        ) {
            EntityRepository repository = cacheSession.repository(binding);
            ProjectionRepository projectionRepository = repository.projected(projectionBinding.projection());
            return new CacheRouteExecutor() {
                @Override
                public String routeLabel() {
                    return "projection:" + projectionBinding.projection().name();
                }

                @Override
                public String idColumn() {
                    return plan.request().childPrimaryKeyColumn();
                }

                @Override
                public boolean usesProjection() {
                    return true;
                }

                @Override
                public RoutePage execute(Object sampleRootId, int pageSize) {
                    QuerySpec querySpec = buildQuerySpec(plan, sampleRootId, pageSize);
                    List<?> items = projectionRepository.query(querySpec);
                    List<String> ids = items.stream()
                            .map(item -> projectionBinding.projection().idAccessor().apply(item))
                            .map(String::valueOf)
                            .toList();
                    return new RoutePage(items.size(), ids);
                }
            };
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        private CacheRouteExecutor entityExecutor(
                MigrationPlanner.Result plan,
                EntityBinding binding,
                CacheSession cacheSession
        ) {
            EntityRepository repository = cacheSession.repository(binding);
            return new CacheRouteExecutor() {
                @Override
                public String routeLabel() {
                    return "entity:" + binding.metadata().entityName();
                }

                @Override
                public String idColumn() {
                    return plan.request().childPrimaryKeyColumn();
                }

                @Override
                public boolean usesProjection() {
                    return false;
                }

                @Override
                public RoutePage execute(Object sampleRootId, int pageSize) {
                    QuerySpec querySpec = buildQuerySpec(plan, sampleRootId, pageSize);
                    List<?> items = repository.query(querySpec);
                    List<String> ids = items.stream()
                            .map(item -> binding.metadata().idAccessor().apply(item))
                            .map(String::valueOf)
                            .toList();
                    return new RoutePage(items.size(), ids);
                }
            };
        }

        private QuerySpec buildQuerySpec(MigrationPlanner.Result plan, Object sampleRootId, int pageSize) {
            QuerySpec querySpec = plan.rankedProjectionRequired()
                    ? QuerySpec.builder().build()
                    : QuerySpec.where(QueryFilter.eq(plan.request().relationColumn(), sampleRootId));
            QuerySpec ordered = querySpec.orderBy(
                    "ASC".equalsIgnoreCase(plan.request().sortDirection())
                            ? QuerySort.asc(plan.request().sortColumn())
                            : QuerySort.desc(plan.request().sortColumn()),
                    QuerySort.desc(plan.request().childPrimaryKeyColumn())
            );
            return ordered.limitTo(Math.max(1, pageSize));
        }
    }
}
