package com.reactor.cachedb.starter;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;

final class MigrationComparisonReportRenderer {

    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC);

    private MigrationComparisonReportRenderer() {
    }

    static Report render(MigrationComparisonRunner.Result comparison) {
        Objects.requireNonNull(comparison, "comparison");
        MigrationComparisonAssessment.Result assessment = comparison.assessment();
        String workload = sanitizeSegment(comparison.plan().request().workloadName());
        String timestamp = FILE_TIMESTAMP.format(comparison.completedAt());
        String fileName = workload + "-migration-report-" + timestamp + ".md";

        StringBuilder markdown = new StringBuilder();
        markdown.append("# Migration Readiness Report").append("\n\n");
        markdown.append("- Workload: `").append(safe(comparison.plan().request().workloadName())).append("`\n");
        markdown.append("- Generated at: `").append(comparison.completedAt()).append("`\n");
        markdown.append("- CacheDB route: `").append(safe(comparison.cacheRouteLabel())).append("`\n");
        markdown.append("- Recommended surface: ").append(safe(comparison.plan().recommendedSurface())).append("\n");
        markdown.append("- Summary projection: `").append(safe(comparison.plan().summaryProjectionName())).append("`\n");
        if (comparison.plan().rankedProjectionRequired()) {
            markdown.append("- Ranked projection: `").append(safe(comparison.plan().rankedProjectionName())).append("`\n");
        }
        markdown.append("\n");

        markdown.append("## Assessment\n\n");
        markdown.append("- Readiness: `").append(assessment.readiness().name()).append("`\n");
        markdown.append("- Decision: ").append(safe(assessment.decision())).append("\n");
        markdown.append("- Summary: ").append(safe(assessment.summary())).append("\n");
        markdown.append("- Parity status: `").append(assessment.parityStatus().name()).append("`\n");
        markdown.append("- Performance status: `").append(assessment.performanceStatus().name()).append("`\n");
        markdown.append("- Route status: `").append(assessment.routeStatus().name()).append("`\n");
        markdown.append("- Exact sample matches: `").append(assessment.exactMatchCount()).append(" / ").append(assessment.sampleCount()).append("`\n");
        markdown.append("- Avg latency ratio (CacheDB / PostgreSQL): `").append(formatRatio(assessment.averageLatencyRatio())).append("`\n");
        markdown.append("- p95 latency ratio (CacheDB / PostgreSQL): `").append(formatRatio(assessment.p95LatencyRatio())).append("`\n\n");

        appendList(markdown, "Strengths", assessment.strengths(), "No strong positive signal was recorded in this run.");
        appendList(markdown, "Cutover Blockers", assessment.blockers(), "No direct blocker was recorded in this run.");
        appendList(markdown, "Next Steps", assessment.nextSteps(), "No additional next step was generated.");
        appendCutoverActionPlan(markdown, comparison);
        appendFullCoveragePlan(markdown, comparison);

        markdown.append("## Latency Snapshot\n\n");
        markdown.append("| Surface | Avg latency | p95 | p99 | Avg rows/op |\n");
        markdown.append("| --- | ---: | ---: | ---: | ---: |\n");
        appendMetricsRow(markdown, "PostgreSQL baseline", comparison.baselineMetrics());
        appendMetricsRow(markdown, "CacheDB", comparison.cacheMetrics());
        markdown.append("\n");

        markdown.append("## Sample Parity\n\n");
        markdown.append("| Sample | Baseline rows | Cache rows | Exact match |\n");
        markdown.append("| --- | ---: | ---: | --- |\n");
        for (MigrationComparisonRunner.SampleComparison sample : comparison.sampleComparisons()) {
            markdown.append("| `").append(safe(sample.sampleLabel())).append("` | ")
                    .append(sample.baselineRowCount()).append(" | ")
                    .append(sample.cacheRowCount()).append(" | ")
                    .append(sample.exactMatch() ? "yes" : "no")
                    .append(" |\n");
        }
        markdown.append("\n");

        markdown.append("## Baseline SQL\n\n```sql\n")
                .append(safe(comparison.baselineSqlTemplate()))
                .append("\n```\n\n");

        if (comparison.warmResult() != null) {
            MigrationWarmRunner.Result warm = comparison.warmResult();
            markdown.append("## Warm Execution\n\n");
            markdown.append("- Dry run: `").append(warm.dryRun()).append("`\n");
            markdown.append("- Child rows hydrated: `").append(warm.childRowsHydrated()).append(" / ").append(warm.childRowsRead()).append("`\n");
            markdown.append("- Root rows hydrated: `").append(warm.rootRowsHydrated()).append(" / ").append(warm.rootRowsRead()).append("`\n");
            markdown.append("- Missing referenced root ids: `").append(warm.missingReferencedRootIds()).append("`\n");
            markdown.append("- Duration: `").append(warm.durationMillis()).append(" ms`\n\n");
        }

        appendList(markdown, "Planner Notes", comparison.notes(), "No extra planner note was recorded.");
        appendList(markdown, "Warnings", comparison.warnings(), "No warning was recorded.");

        return new Report(fileName, markdown.toString());
    }

    private static void appendCutoverActionPlan(StringBuilder markdown, MigrationComparisonRunner.Result comparison) {
        MigrationPlanner.Result plan = comparison.plan();
        MigrationPlanner.Request request = plan.request();
        MigrationComparisonAssessment.Result assessment = comparison.assessment();
        String root = safe(request.rootTableOrEntity());
        String child = safe(request.childTableOrEntity());
        String relation = safe(request.relationColumn());
        String sort = safe(request.sortColumn()) + " " + safe(request.sortDirection());
        String projection = safe(plan.summaryProjectionName());
        String route = safe(comparison.cacheRouteLabel());

        markdown.append("## Cutover Action Plan\n\n");
        markdown.append("This action plan is scoped to the selected route only: `")
                .append(root).append(" -> ").append(child)
                .append("` via `").append(relation).append("`, sorted by `").append(sort).append("`.\n\n");
        markdown.append("| Phase | Required action | Exit gate |\n");
        markdown.append("| --- | --- | --- |\n");
        markdown.append("| Pre-checks | Freeze the route contract: root `").append(root)
                .append("`, child `").append(child)
                .append("`, relation `").append(relation)
                .append("`, sort `").append(sort)
                .append("`, page size `").append(request.firstPageSize())
                .append("`, hot window `").append(plan.recommendedHotWindowPerRoot())
                .append("`. | Contract is reviewed and no endpoint uses a wider first-paint aggregate. |\n");
        markdown.append("| Schema/index | Verify PostgreSQL has a covering list index for `")
                .append(relation).append(", ").append(sort)
                .append("` and a deterministic tie-breaker on `").append(safe(request.childPrimaryKeyColumn()))
                .append("`. | Baseline query plan avoids full scans for the measured route. |\n");
        markdown.append("| CacheDB binding | Register the selected entity bindings, relation loader, and projection `")
                .append(projection).append("`. | Cache route label is `").append(route)
                .append("` and does not fall back to `entity:*` when projection is required. |\n");
        markdown.append("| Dry run | Run warm dry-run before mutating Redis. | Child/root row counts are expected and missing referenced roots are `0`. |\n");
        markdown.append("| Warm | Execute staging warm for the bounded hot window. | Hydrated rows match read rows and warm completes without retry/poison warnings. |\n");
        markdown.append("| Compare | Run side-by-side compare with representative samples. | Exact parity is `")
                .append(assessment.exactMatchCount()).append(" / ").append(assessment.sampleCount())
                .append("` in this report and must remain exact after increasing sample coverage. |\n");
        markdown.append("| Full cutover rehearsal | Because hybrid runtime is not the target, rehearse the full switch in staging with the old stack stopped or isolated. | New stack serves the route from CacheDB with exact parity and accepted p95/p99 latency. |\n");
        markdown.append("| Production switch | Take the agreed maintenance/freeze window if writes cannot be dual-controlled, warm Redis, deploy the CacheDB-backed application, then route traffic to the new stack. | Health, Redis memory, projection lag, and route latency stay inside the production gate. |\n");
        markdown.append("| Rollback | Keep PostgreSQL as durable source of truth and keep the previous deploy artifact/config ready until the go/no-go window closes. | Rollback path is tested before production switch; Redis hot data can be discarded and rebuilt. |\n");
        markdown.append("| Go/No-Go | Use this report plus the full coverage matrix below. | Go only when readiness is `READY`, parity is exact, route status is projection/ranked projection when required, and there are no blockers. |\n\n");
    }

    private static void appendFullCoveragePlan(StringBuilder markdown, MigrationComparisonRunner.Result comparison) {
        MigrationPlanner.Result plan = comparison.plan();
        MigrationPlanner.Request request = plan.request();
        markdown.append("## Full Conversion Coverage Plan\n\n");
        markdown.append("This report is not a whole-system conversion certificate. It certifies one selected route. ")
                .append("For a 100% conversion, every production read path, write path, background job, report, and integration must be represented in the migration inventory and must produce its own ready evidence.\n\n");
        markdown.append("### Current Route Coverage\n\n");
        markdown.append("| Item | Value |\n");
        markdown.append("| --- | --- |\n");
        markdown.append("| Root surface | `").append(safe(request.rootTableOrEntity())).append("` |\n");
        markdown.append("| Child surface | `").append(safe(request.childTableOrEntity())).append("` |\n");
        markdown.append("| Relation column | `").append(safe(request.relationColumn())).append("` |\n");
        markdown.append("| Sort contract | `").append(safe(request.sortColumn())).append(" ").append(safe(request.sortDirection())).append("` |\n");
        markdown.append("| Required Redis artifacts | `").append(plan.recommendedRedisArtifacts().size()).append("` artifact(s) |\n");
        markdown.append("| Required PostgreSQL artifacts | `").append(plan.recommendedPostgresArtifacts().size()).append("` artifact(s) |\n");
        markdown.append("| API shapes covered | `").append(plan.recommendedApiShapes().size()).append("` shape(s) |\n\n");
        markdown.append("### 100% Coverage Gate\n\n");
        markdown.append("The following checklist is mandatory for a full-system conversion. A single unchecked item keeps the route out of GA cutover scope.\n\n");
        markdown.append("| Coverage item | Required evidence | Gate |\n");
        markdown.append("| --- | --- | --- |\n");
        markdown.append("| Route inventory | Every endpoint, service method, scheduled job, report query, external callback, and admin screen is listed. | No unknown route remains. |\n");
        markdown.append("| Table/view ownership | Every PostgreSQL table and view has one owner route and one migration decision. | No table/view is orphaned. |\n");
        markdown.append("| Read-shape classification | Each route is classified as CRUD entity, bounded relation projection, ranked/global projection, detail lookup, aggregate/report path, or archive-only path. | No unclassified route remains. |\n");
        markdown.append("| Projection contract | Every relation-heavy or sorted/range route has an explicit projection/read-model contract. | No required projection falls back to `entity:*`. |\n");
        markdown.append("| Warm evidence | Dry-run and warm execution exist for each Redis hot set. | Hydrated rows match read rows; missing referenced roots are `0`. |\n");
        markdown.append("| Side-by-side parity | PostgreSQL baseline and CacheDB route are compared with representative samples. | Exact parity is required for membership and order. |\n");
        markdown.append("| Performance budget | p95/p99 budgets are documented per route and checked after warm. | CacheDB latency stays inside the accepted route budget. |\n");
        markdown.append("| Write semantics | Every write path proves idempotency, primary-key ownership, Redis mutation, PostgreSQL durability, retry, poison visibility, and rollback behavior. | No write path is undocumented. |\n");
        markdown.append("| Staging rehearsal | The full conversion is rehearsed with the old stack stopped or isolated because hybrid runtime is not the target. | Final rehearsal is green before production switch. |\n\n");
        markdown.append("Do not declare 100% coverage until every inventory item has a ready report, no route falls back unexpectedly, and the final staging rehearsal passes with representative production data.\n\n");
    }

    private static void appendMetricsRow(StringBuilder markdown, String label, MigrationComparisonRunner.Metrics metrics) {
        markdown.append("| ").append(label).append(" | `")
                .append(formatLatency(metrics.averageLatencyNanos())).append("` | `")
                .append(formatLatency(metrics.p95LatencyNanos())).append("` | `")
                .append(formatLatency(metrics.p99LatencyNanos())).append("` | `")
                .append(String.format(Locale.ROOT, "%.2f", metrics.averageRowsPerOperation())).append("` |\n");
    }

    private static void appendList(StringBuilder markdown, String title, java.util.List<String> items, String emptyMessage) {
        markdown.append("## ").append(title).append("\n\n");
        if (items == null || items.isEmpty()) {
            markdown.append("- ").append(emptyMessage).append("\n\n");
            return;
        }
        for (String item : items) {
            markdown.append("- ").append(safe(item)).append("\n");
        }
        markdown.append("\n");
    }

    private static String formatLatency(long nanos) {
        if (nanos >= 1_000_000L) {
            return String.format(Locale.ROOT, "%.2f ms", nanos / 1_000_000.0d);
        }
        if (nanos >= 1_000L) {
            return String.format(Locale.ROOT, "%.2f µs", nanos / 1_000.0d);
        }
        return nanos + " ns";
    }

    private static String formatRatio(double value) {
        if (!Double.isFinite(value) || value <= 0.0d) {
            return "-";
        }
        return String.format(Locale.ROOT, "%.2fx", value);
    }

    private static String sanitizeSegment(String value) {
        String normalized = value == null ? "workload" : value.trim().toLowerCase(Locale.ROOT);
        normalized = normalized.replaceAll("[^a-z0-9]+", "-");
        normalized = normalized.replaceAll("(^-+|-+$)", "");
        return normalized.isBlank() ? "workload" : normalized;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    record Report(String fileName, String markdown) {
    }
}
