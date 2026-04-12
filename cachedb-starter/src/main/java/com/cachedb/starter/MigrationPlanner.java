package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.registry.EntityBinding;
import com.reactor.cachedb.core.registry.EntityRegistry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class MigrationPlanner {

    Template template(EntityRegistry entityRegistry) {
        ArrayList<EntityOption> entityOptions = new ArrayList<>();
        for (EntityBinding<?, ?> binding : entityRegistry.all()) {
            entityOptions.add(new EntityOption(
                    binding.metadata().entityName(),
                    binding.metadata().tableName(),
                    binding.metadata().idColumn(),
                    List.copyOf(binding.metadata().columns())
            ));
        }
        entityOptions.sort(Comparator.comparing(EntityOption::entityName, String.CASE_INSENSITIVE_ORDER));
        return new Template(
                List.copyOf(entityOptions),
                Request.defaults()
        );
    }

    Result plan(Request request) {
        Request normalized = request.normalize();
        long typicalChildren = Math.max(normalized.typicalChildrenPerRoot(), 0);
        long maxChildren = Math.max(normalized.maxChildrenPerRoot(), typicalChildren);
        int firstPageSize = Math.max(1, normalized.firstPageSize());
        int requestedHotWindow = Math.max(normalized.hotWindowPerRoot(), firstPageSize);
        int recommendedHotWindow = Math.max(requestedHotWindow, Math.max(firstPageSize * 8, 250));

        boolean fanoutHeavy = typicalChildren >= Math.max(10, firstPageSize / 2)
                || maxChildren >= Math.max(50, firstPageSize * 2L);
        boolean growthHeavy = normalized.childRowCount() >= Math.max(normalized.rootRowCount() * 4L, 50_000L);
        boolean projectionRequired = normalized.listScreen()
                && (fanoutHeavy
                || growthHeavy
                || !normalized.firstPaintNeedsFullAggregate()
                || normalized.globalSortedScreen()
                || normalized.thresholdOrRangeScreen());
        boolean rankedProjectionRequired = normalized.globalSortedScreen() || normalized.thresholdOrRangeScreen();
        boolean summaryFirstRequired = projectionRequired || maxChildren > firstPageSize;
        boolean boundedRedisWindowRequired = !normalized.fullHistoryMustStayHot()
                || normalized.archiveHistoryRequired()
                || maxChildren > recommendedHotWindow;
        boolean fullEntityHotWindowRecommended = normalized.detailLookupIsHot()
                && !normalized.archiveHistoryRequired()
                && maxChildren <= Math.max(recommendedHotWindow, 2_000)
                && normalized.childRowCount() <= 2_000_000L;

        String rootPascal = pascalCase(normalized.rootTableOrEntity());
        String childPascal = pascalCase(normalized.childTableOrEntity());
        String relationLabel = normalized.relationColumn();
        String summaryProjectionName = rootPascal + childPascal + "SummaryHot";
        String previewProjectionName = rootPascal + childPascal + "Preview";
        String rankedProjectionName = rankedProjectionRequired ? rootPascal + childPascal + "RankedHot" : "";
        String rankFieldName = rankedProjectionRequired ? "rank_score" : "";
        String recommendedSurface = projectionRequired
                ? "Use GeneratedCacheModule for normal CRUD and a ProjectionRepository for the hot list screen."
                : "Start on GeneratedCacheModule.using(session) and keep the route on entity reads until profiling says otherwise.";
        String redisPlacement = boundedRedisWindowRequired
                ? "Keep " + normalized.rootTableOrEntity() + " hot in Redis and keep a bounded per-parent " + summaryProjectionName
                + " window in Redis. Leave full " + normalized.childTableOrEntity() + " history in PostgreSQL."
                : "Keep both " + normalized.rootTableOrEntity() + " and " + normalized.childTableOrEntity()
                + " hot in Redis, but still prefer " + summaryProjectionName + " for the first paint list.";
        String postgresPlacement = "PostgreSQL remains the durable source of truth for the full "
                + normalized.childTableOrEntity() + " history and all archive or replay reads.";

        ArrayList<String> reasoning = new ArrayList<>();
        if (projectionRequired) {
            reasoning.add("The route is relation-heavy enough that the first paint should not hydrate the full aggregate.");
        } else {
            reasoning.add("The route can start on direct entity reads because the current fan-out looks manageable.");
        }
        if (fanoutHeavy) {
            reasoning.add("Per-parent child fan-out is high enough that eager relation loading will keep getting more expensive as data grows.");
        }
        if (growthHeavy) {
            reasoning.add("Child growth is materially faster than root growth, so Redis should carry a bounded hot set instead of the full history.");
        }
        if (rankedProjectionRequired) {
            reasoning.add("Global sorted or threshold-driven screens should use a pre-ranked projection field and a single sorted index fast path.");
        }
        if (summaryFirstRequired) {
            reasoning.add("The list route should be summary-first: list in projection, detail in an explicit follow-up fetch.");
        }

        ArrayList<String> redisArtifacts = new ArrayList<>();
        redisArtifacts.add(normalized.rootTableOrEntity() + " entity hot set");
        redisArtifacts.add(summaryProjectionName + " projection keyed by " + relationLabel);
        redisArtifacts.add("Single sorted index on " + relationLabel + " + " + normalized.sortColumn() + " " + normalized.sortDirection());
        if (rankedProjectionRequired) {
            redisArtifacts.add(rankedProjectionName + " projection with precomputed " + rankFieldName + " field");
            redisArtifacts.add("Single sorted ranked index on " + rankFieldName + " for top-window reads");
        }
        if (fullEntityHotWindowRecommended) {
            redisArtifacts.add(normalized.childTableOrEntity() + " full entity hot window for direct detail lookups");
        }

        ArrayList<String> postgresArtifacts = new ArrayList<>();
        postgresArtifacts.add(normalized.rootTableOrEntity() + " base table");
        postgresArtifacts.add(normalized.childTableOrEntity() + " full history table");
        postgresArtifacts.add("Archive/history reads after Redis hot window");
        postgresArtifacts.add("Migration backfill source for " + summaryProjectionName);

        ArrayList<String> apiShapes = new ArrayList<>();
        apiShapes.add("List route: filter on " + relationLabel + ", sort by " + normalized.sortColumn()
                + " " + normalized.sortDirection() + ", tie-break on " + normalized.childPrimaryKeyColumn());
        if (summaryFirstRequired) {
            apiShapes.add("First paint: " + summaryProjectionName + " only");
            apiShapes.add("Follow-up detail: explicit " + normalized.childTableOrEntity() + " fetch by " + normalized.childPrimaryKeyColumn());
        } else {
            apiShapes.add("Entity list route is still acceptable, but keep the shape narrow and measured.");
        }
        if (rankedProjectionRequired) {
            apiShapes.add("Use " + rankFieldName + " on " + rankedProjectionName + " for global sorted / threshold list screens.");
        }

        ArrayList<WarmStep> warmSteps = new ArrayList<>();
        warmSteps.add(new WarmStep(
                "Baseline the current route",
                "Measure the existing ORM/PostgreSQL route before introducing CacheDB.",
                List.of(
                        "Capture p50/p95 for the list route and the detail route separately.",
                        "Capture current PostgreSQL query count, average rows scanned, and connection pool pressure.",
                        "Capture the typical first page size and the deepest page real users hit."
                )
        ));
        warmSteps.add(new WarmStep(
                "Register entities and projections",
                "Map the current tables into CacheDB entities, then define the hot list projection before warming data.",
                List.of(
                        "Register " + normalized.rootTableOrEntity() + " as a root entity.",
                        "Register " + normalized.childTableOrEntity() + " as a separate root entity.",
                        "Define " + summaryProjectionName + " for the list route."
                                + (rankedProjectionRequired ? " Define " + rankedProjectionName + " for ranked list screens." : "")
                )
        ));
        warmSteps.add(new WarmStep(
                "Warm the Redis hot window",
                "Backfill only the Redis working set you actually want hot.",
                List.of(
                        "Warm all hot " + normalized.rootTableOrEntity() + " rows needed by the target service.",
                        "Warm per-parent " + summaryProjectionName + " rows up to " + recommendedHotWindow + " items.",
                        boundedRedisWindowRequired
                                ? "Leave older " + normalized.childTableOrEntity() + " rows in PostgreSQL archive/history path."
                                : "Keep full child rows hot only if Redis memory budget and growth rate still support it."
                )
        ));
        warmSteps.add(new WarmStep(
                "Switch the list route in staging",
                "Move the staging route to the projection shape first and keep detail explicit.",
                List.of(
                        "List route -> " + summaryProjectionName,
                        "Detail route -> " + normalized.childTableOrEntity() + " by " + normalized.childPrimaryKeyColumn(),
                        "Do not preload the full child collection on the parent entity."
                )
        ));
        warmSteps.add(new WarmStep(
                "Compare and decide production cutover",
                "Use side-by-side results before a full production move.",
                List.of(
                        "Compare Redis read latency against the baseline PostgreSQL route.",
                        "Confirm PostgreSQL load dropped on the hot path instead of just moving cost elsewhere.",
                        "Confirm Redis memory stays inside the planned hot window."
                )
        ));

        ArrayList<ComparisonCheck> comparisonChecks = new ArrayList<>();
        comparisonChecks.add(new ComparisonCheck(
                "Hot list latency",
                "Compare baseline ORM/PostgreSQL list p50/p95 against CacheDB summary projection p50/p95.",
                "CacheDB summary route should materially reduce p95 without widening Redis memory beyond the planned window."
        ));
        comparisonChecks.add(new ComparisonCheck(
                "Database pressure",
                "Compare PostgreSQL query count and rows scanned before and after the CacheDB route is enabled.",
                "Hot list traffic should move to Redis, while PostgreSQL remains mainly durability, detail, and archive path."
        ));
        comparisonChecks.add(new ComparisonCheck(
                "First paint object count",
                "Compare how many child objects the route touches on first paint before and after the migration.",
                "The CacheDB route should touch only summary rows on first paint, not the full child collection."
        ));
        comparisonChecks.add(new ComparisonCheck(
                "Redis memory discipline",
                "Watch Redis used memory and projection window growth while the staging route is under production-like load.",
                boundedRedisWindowRequired
                        ? "Per-parent hot window should stay bounded around " + recommendedHotWindow + " rows."
                        : "Full-history hot mode is allowed here, but only if growth rate and memory budget still stay inside your envelope."
        ));
        comparisonChecks.add(new ComparisonCheck(
                "Cutover safety",
                "Run side-by-side reads in staging and compare list membership, sort order, and detail correctness.",
                "The new route should preserve ordering, pagination semantics, and detail consistency before production cutover."
        ));

        ArrayList<String> warnings = new ArrayList<>();
        if (normalized.fullHistoryMustStayHot() && normalized.childRowCount() > 500_000L) {
            warnings.add("Full child history hot in Redis is likely to create unbounded memory growth; prefer a bounded per-parent hot window.");
        }
        if (normalized.firstPaintNeedsFullAggregate() && projectionRequired) {
            warnings.add("The request says first paint wants the full aggregate, but the fan-out says that shape will get progressively more expensive.");
        }
        if (rankedProjectionRequired) {
            warnings.add("Do not ship global sorted screens on a wide entity scan; make the ranked field part of the projection contract.");
        }
        if (normalized.currentOrmUsesEagerLoading()) {
            warnings.add("The current ORM route appears eager-loading heavy; baseline carefully because CacheDB should remove that cost from first paint.");
        }

        return new Result(
                normalized,
                recommendedSurface,
                projectionRequired,
                rankedProjectionRequired,
                summaryFirstRequired,
                boundedRedisWindowRequired,
                fullEntityHotWindowRecommended,
                recommendedHotWindow,
                summaryProjectionName,
                previewProjectionName,
                rankedProjectionName,
                rankFieldName,
                redisPlacement,
                postgresPlacement,
                List.copyOf(reasoning),
                List.copyOf(redisArtifacts),
                List.copyOf(postgresArtifacts),
                List.copyOf(apiShapes),
                List.copyOf(warmSteps),
                List.copyOf(comparisonChecks),
                List.copyOf(warnings),
                buildChildWarmSql(normalized, recommendedHotWindow, rankedProjectionRequired, normalized.childTableOrEntity()),
                buildRootWarmSqlTemplate(normalized, normalized.rootTableOrEntity()),
                buildComparisonSummary(projectionRequired, rankedProjectionRequired, recommendedHotWindow)
        );
    }

    static String buildChildWarmSql(
            Request request,
            int hotWindowPerRoot,
            boolean rankedProjectionRequired,
            String childTableName
    ) {
        String sortDirection = "ASC".equalsIgnoreCase(request.sortDirection()) ? "ASC" : "DESC";
        if (rankedProjectionRequired) {
            return """
                    SELECT *
                    FROM %s source
                    ORDER BY %s %s, %s DESC
                    LIMIT %d;
                    """.formatted(
                    childTableName,
                    request.sortColumn(),
                    sortDirection,
                    request.childPrimaryKeyColumn(),
                    hotWindowPerRoot
            ).trim();
        }
        return """
                WITH ranked_source AS (
                    SELECT source.*,
                           ROW_NUMBER() OVER (
                               PARTITION BY %s
                               ORDER BY %s %s, %s DESC
                       ) AS cachedb_hot_rank
                    FROM %s source
                )
                SELECT *
                FROM ranked_source
                WHERE cachedb_hot_rank <= %d;
                """.formatted(
                request.relationColumn(),
                request.sortColumn(),
                sortDirection,
                request.childPrimaryKeyColumn(),
                childTableName,
                hotWindowPerRoot
        ).trim();
    }

    static String buildRootWarmSqlTemplate(Request request, String rootTableName) {
        return """
                SELECT *
                FROM %s
                WHERE %s IN (:referenced_root_ids)
                ORDER BY %s ASC;
                """.formatted(
                rootTableName,
                request.rootPrimaryKeyColumn(),
                request.rootPrimaryKeyColumn()
        ).trim();
    }

    private String buildComparisonSummary(boolean projectionRequired, boolean rankedProjectionRequired, int hotWindowPerRoot) {
        if (rankedProjectionRequired) {
            return "Use a ranked projection for the hot list route, keep the hot window at " + hotWindowPerRoot
                    + " rows per parent, and compare the staged route against the baseline before cutover.";
        }
        if (projectionRequired) {
            return "Use a summary projection for the hot list route, keep the hot window at " + hotWindowPerRoot
                    + " rows per parent, and validate first-paint latency plus PostgreSQL load before cutover.";
        }
        return "This route can start on direct entity reads, but still baseline p95 and move to a summary projection as soon as fan-out or growth makes the first paint expensive.";
    }

    private String pascalCase(String raw) {
        String normalized = raw == null ? "" : raw.trim();
        if (normalized.isBlank()) {
            return "Entity";
        }
        String[] parts = normalized.split("[^A-Za-z0-9]+");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            String lower = part.toLowerCase(Locale.ROOT);
            builder.append(Character.toUpperCase(lower.charAt(0)));
            if (lower.length() > 1) {
                builder.append(lower.substring(1));
            }
        }
        return builder.isEmpty() ? "Entity" : builder.toString();
    }

    public record Template(
            List<EntityOption> entityOptions,
            Request defaults
    ) {
    }

    public record EntityOption(
            String entityName,
            String tableName,
            String idColumn,
            List<String> columns
    ) {
    }

    public record Request(
            String workloadName,
            String rootTableOrEntity,
            String rootPrimaryKeyColumn,
            String childTableOrEntity,
            String childPrimaryKeyColumn,
            String relationColumn,
            String sortColumn,
            String sortDirection,
            long rootRowCount,
            long childRowCount,
            long typicalChildrenPerRoot,
            long maxChildrenPerRoot,
            int firstPageSize,
            int hotWindowPerRoot,
            boolean listScreen,
            boolean firstPaintNeedsFullAggregate,
            boolean globalSortedScreen,
            boolean thresholdOrRangeScreen,
            boolean archiveHistoryRequired,
            boolean fullHistoryMustStayHot,
            boolean currentOrmUsesEagerLoading,
            boolean detailLookupIsHot,
            boolean sideBySideComparisonRequired
    ) {
        public static Request defaults() {
            return new Request(
                    "customer-orders",
                    "customer",
                    "customer_id",
                    "orders",
                    "order_id",
                    "customer_id",
                    "order_date",
                    "DESC",
                    100_000L,
                    5_000_000L,
                    40L,
                    2_000L,
                    100,
                    1_000,
                    true,
                    false,
                    false,
                    false,
                    true,
                    false,
                    true,
                    true,
                    true
            );
        }

        Request normalize() {
            return new Request(
                    defaultString(workloadName, "workload"),
                    defaultString(rootTableOrEntity, "parent"),
                    defaultString(rootPrimaryKeyColumn, "id"),
                    defaultString(childTableOrEntity, "child"),
                    defaultString(childPrimaryKeyColumn, "id"),
                    defaultString(relationColumn, "parent_id"),
                    defaultString(sortColumn, "created_at"),
                    normalizeSort(sortDirection),
                    Math.max(0L, rootRowCount),
                    Math.max(0L, childRowCount),
                    Math.max(0L, typicalChildrenPerRoot),
                    Math.max(0L, maxChildrenPerRoot),
                    Math.max(1, firstPageSize),
                    Math.max(1, hotWindowPerRoot),
                    listScreen,
                    firstPaintNeedsFullAggregate,
                    globalSortedScreen,
                    thresholdOrRangeScreen,
                    archiveHistoryRequired,
                    fullHistoryMustStayHot,
                    currentOrmUsesEagerLoading,
                    detailLookupIsHot,
                    sideBySideComparisonRequired
            );
        }

        private static String defaultString(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value.trim();
        }

        private static String normalizeSort(String value) {
            return "ASC".equalsIgnoreCase(value) ? "ASC" : "DESC";
        }
    }

    public record Result(
            Request request,
            String recommendedSurface,
            boolean projectionRequired,
            boolean rankedProjectionRequired,
            boolean summaryFirstRequired,
            boolean boundedRedisWindowRequired,
            boolean fullEntityHotWindowRecommended,
            int recommendedHotWindowPerRoot,
            String summaryProjectionName,
            String previewProjectionName,
            String rankedProjectionName,
            String rankFieldName,
            String redisPlacement,
            String postgresPlacement,
            List<String> reasoning,
            List<String> recommendedRedisArtifacts,
            List<String> recommendedPostgresArtifacts,
            List<String> recommendedApiShapes,
            List<WarmStep> warmSteps,
            List<ComparisonCheck> comparisonChecks,
            List<String> warnings,
            String sampleWarmSql,
            String sampleRootWarmSql,
            String comparisonSummary
    ) {
    }

    public record WarmStep(
            String title,
            String summary,
            List<String> tasks
    ) {
    }

    public record ComparisonCheck(
            String title,
            String baseline,
            String target
    ) {
    }
}
