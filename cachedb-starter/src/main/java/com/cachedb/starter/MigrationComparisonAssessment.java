package com.reactor.cachedb.starter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

final class MigrationComparisonAssessment {

    private MigrationComparisonAssessment() {
    }

    static Result evaluate(MigrationComparisonRunner.Result comparison) {
        Objects.requireNonNull(comparison, "comparison");
        long sampleCount = comparison.sampleComparisons().size();
        long exactMatchCount = comparison.sampleComparisons().stream()
                .filter(MigrationComparisonRunner.SampleComparison::exactMatch)
                .count();
        double exactMatchRatio = sampleCount == 0L ? 0.0d : exactMatchCount / (double) sampleCount;
        double averageLatencyRatio = ratio(
                comparison.cacheMetrics().averageLatencyNanos(),
                comparison.baselineMetrics().averageLatencyNanos()
        );
        double p95LatencyRatio = ratio(
                comparison.cacheMetrics().p95LatencyNanos(),
                comparison.baselineMetrics().p95LatencyNanos()
        );

        ParityStatus parityStatus = parityStatus(sampleCount, exactMatchCount);
        RouteStatus routeStatus = routeStatus(comparison);
        PerformanceStatus performanceStatus = performanceStatus(comparison, averageLatencyRatio, p95LatencyRatio);

        ArrayList<String> strengths = new ArrayList<>();
        ArrayList<String> blockers = new ArrayList<>();
        ArrayList<String> nextSteps = new ArrayList<>();

        if (parityStatus == ParityStatus.EXACT) {
            strengths.add("Compared PostgreSQL and CacheDB samples returned the same first-page membership and order.");
        }
        if (routeStatus == RouteStatus.RANKED_PROJECTION) {
            strengths.add("The comparison used the ranked projection route selected by the planner.");
        } else if (routeStatus == RouteStatus.PROJECTION) {
            strengths.add("The comparison used a registered projection route instead of a full entity fallback.");
        }
        if (performanceStatus == PerformanceStatus.FASTER || performanceStatus == PerformanceStatus.COMPARABLE) {
            strengths.add("CacheDB stayed within the expected latency envelope for the measured route.");
        }
        if (comparison.warmResult() != null) {
            strengths.add("The hot set was warmed before the comparison, so the numbers reflect a warmed staging path.");
        }

        if (parityStatus == ParityStatus.NO_SAMPLES) {
            blockers.add("No representative sample routes were compared, so cutover readiness cannot be judged yet.");
            nextSteps.add("Provide a representative root id or seed enough child rows to produce comparison samples.");
        } else if (parityStatus != ParityStatus.EXACT) {
            blockers.add("CacheDB did not fully match PostgreSQL for the compared first-page route membership/order.");
            nextSteps.add("Inspect the sample mismatch payloads, tighten the projection contract, and rerun the side-by-side comparison.");
        }

        boolean projectionFallback = routeStatus == RouteStatus.ENTITY_FALLBACK && comparison.plan().projectionRequired();
        if (projectionFallback) {
            blockers.add("The planner requires a projection route, but the comparison fell back to an entity query.");
            nextSteps.add("Register the summary projection surfaced by the planner and rerun the comparison.");
        } else if (routeStatus == RouteStatus.ENTITY_FALLBACK) {
            nextSteps.add("Register a projection route for this workload before treating the comparison as production-ready.");
        }

        boolean rankedRouteMissing = comparison.plan().rankedProjectionRequired()
                && routeStatus != RouteStatus.RANKED_PROJECTION;
        if (rankedRouteMissing) {
            blockers.add("This route needs a ranked projection, but the comparison is not using the ranked projection surface.");
            nextSteps.add("Register the ranked projection suggested by the planner so top-window reads avoid wide scans.");
        }

        if (performanceStatus == PerformanceStatus.BLOCKER) {
            blockers.add("CacheDB p95 latency is materially slower than the PostgreSQL baseline for this route.");
            nextSteps.add("Tighten the hot window, ranking surface, or preview contract before attempting cutover.");
        } else if (performanceStatus == PerformanceStatus.SLOWER) {
            nextSteps.add("CacheDB is slower than the current PostgreSQL baseline. Review the projection shape and rerun the warm plus comparison cycle.");
        }

        if (containsWarning(comparison.warnings(), "threshold/range routes")) {
            nextSteps.add("Provide an explicit baseline SQL override for threshold/range routes before making a cutover decision.");
        }
        if (comparison.warmResult() == null) {
            nextSteps.add("Run the staging warm execution before the next comparison so CacheDB is measured with a representative hot set.");
        }

        Readiness readiness = readiness(
                parityStatus,
                performanceStatus,
                projectionFallback,
                rankedRouteMissing,
                comparison.warnings()
        );
        if (readiness == Readiness.READY) {
            nextSteps.add("Run one more staging rehearsal with representative traffic and then walk through the cutover checklist.");
        } else if (readiness == Readiness.NEEDS_REVIEW && blockers.isEmpty()) {
            nextSteps.add("Review the warnings, rerun the comparison with representative traffic, and confirm the route before cutover.");
        }

        return new Result(
                readiness,
                parityStatus,
                performanceStatus,
                routeStatus,
                summary(readiness, routeStatus, parityStatus, performanceStatus),
                decision(readiness),
                exactMatchCount,
                sampleCount,
                exactMatchRatio,
                averageLatencyRatio,
                p95LatencyRatio,
                List.copyOf(strengths),
                List.copyOf(blockers),
                List.copyOf(deduplicate(nextSteps))
        );
    }

    private static Readiness readiness(
            ParityStatus parityStatus,
            PerformanceStatus performanceStatus,
            boolean projectionFallback,
            boolean rankedRouteMissing,
            List<String> warnings
    ) {
        if (parityStatus == ParityStatus.NO_SAMPLES
                || parityStatus == ParityStatus.PARTIAL
                || parityStatus == ParityStatus.MISMATCH
                || projectionFallback
                || rankedRouteMissing
                || performanceStatus == PerformanceStatus.BLOCKER) {
            return Readiness.NOT_READY;
        }
        if (performanceStatus == PerformanceStatus.SLOWER || !warnings.isEmpty()) {
            return Readiness.NEEDS_REVIEW;
        }
        return Readiness.READY;
    }

    private static String summary(
            Readiness readiness,
            RouteStatus routeStatus,
            ParityStatus parityStatus,
            PerformanceStatus performanceStatus
    ) {
        if (readiness == Readiness.NOT_READY) {
            return "This route is not ready for cutover yet because data parity or route-shape blockers are still present.";
        }
        if (readiness == Readiness.NEEDS_REVIEW) {
            return "The route is close, but it still needs review before cutover because warnings or latency gaps remain.";
        }
        String routePhrase = routeStatus == RouteStatus.RANKED_PROJECTION
                ? "ranked projection"
                : routeStatus == RouteStatus.PROJECTION ? "projection" : "resolved";
        String performancePhrase = performanceStatus == PerformanceStatus.FASTER
                ? "at or below"
                : "within";
        if (parityStatus == ParityStatus.EXACT) {
            return "Compared samples matched PostgreSQL and the " + routePhrase + " route stayed " + performancePhrase + " the expected latency envelope.";
        }
        return "The measured route looks healthy, but it still needs another validation pass before cutover.";
    }

    private static String decision(Readiness readiness) {
        return switch (readiness) {
            case READY -> "Ready for staged cutover rehearsal.";
            case NEEDS_REVIEW -> "Keep the route in staging and review the findings before cutover.";
            case NOT_READY -> "Do not cut over this route yet.";
        };
    }

    private static ParityStatus parityStatus(long sampleCount, long exactMatchCount) {
        if (sampleCount <= 0L) {
            return ParityStatus.NO_SAMPLES;
        }
        if (exactMatchCount == sampleCount) {
            return ParityStatus.EXACT;
        }
        if (exactMatchCount <= 0L) {
            return ParityStatus.MISMATCH;
        }
        return ParityStatus.PARTIAL;
    }

    private static RouteStatus routeStatus(MigrationComparisonRunner.Result comparison) {
        String routeLabel = comparison.cacheRouteLabel() == null ? "" : comparison.cacheRouteLabel().trim();
        if (routeLabel.isBlank()) {
            return RouteStatus.UNKNOWN;
        }
        if (!routeLabel.startsWith("projection:")) {
            return RouteStatus.ENTITY_FALLBACK;
        }
        String rankedName = comparison.plan().rankedProjectionName();
        if (rankedName != null && !rankedName.isBlank() && routeLabel.equals("projection:" + rankedName)) {
            return RouteStatus.RANKED_PROJECTION;
        }
        return RouteStatus.PROJECTION;
    }

    private static PerformanceStatus performanceStatus(
            MigrationComparisonRunner.Result comparison,
            double averageLatencyRatio,
            double p95LatencyRatio
    ) {
        long baselineP95 = comparison.baselineMetrics().p95LatencyNanos();
        long cacheP95 = comparison.cacheMetrics().p95LatencyNanos();
        long deltaP95 = Math.max(0L, cacheP95 - baselineP95);
        if (p95LatencyRatio > 2.50d && deltaP95 > 1_000_000L) {
            return PerformanceStatus.BLOCKER;
        }
        if (p95LatencyRatio > 1.25d && deltaP95 > 250_000L) {
            return PerformanceStatus.SLOWER;
        }
        if (p95LatencyRatio <= 0.95d && averageLatencyRatio <= 0.95d) {
            return PerformanceStatus.FASTER;
        }
        return PerformanceStatus.COMPARABLE;
    }

    private static boolean containsWarning(List<String> warnings, String needle) {
        String normalizedNeedle = needle.toLowerCase(Locale.ROOT);
        return warnings.stream()
                .filter(Objects::nonNull)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(value -> value.contains(normalizedNeedle));
    }

    private static double ratio(long numerator, long denominator) {
        if (denominator <= 0L) {
            return numerator <= 0L ? 1.0d : 999.0d;
        }
        return numerator / (double) denominator;
    }

    private static List<String> deduplicate(List<String> values) {
        ArrayList<String> result = new ArrayList<>();
        for (String value : values) {
            if (value == null || value.isBlank() || result.contains(value)) {
                continue;
            }
            result.add(value);
        }
        return result;
    }

    enum Readiness {
        READY,
        NEEDS_REVIEW,
        NOT_READY
    }

    enum ParityStatus {
        EXACT,
        PARTIAL,
        MISMATCH,
        NO_SAMPLES
    }

    enum PerformanceStatus {
        FASTER,
        COMPARABLE,
        SLOWER,
        BLOCKER
    }

    enum RouteStatus {
        RANKED_PROJECTION,
        PROJECTION,
        ENTITY_FALLBACK,
        UNKNOWN
    }

    record Result(
            Readiness readiness,
            ParityStatus parityStatus,
            PerformanceStatus performanceStatus,
            RouteStatus routeStatus,
            String summary,
            String decision,
            long exactMatchCount,
            long sampleCount,
            double exactMatchRatio,
            double averageLatencyRatio,
            double p95LatencyRatio,
            List<String> strengths,
            List<String> blockers,
            List<String> nextSteps
    ) {
    }
}
