package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.config.AdminHttpConfig;
import com.reactor.cachedb.core.plan.FetchPlan;
import com.reactor.cachedb.core.query.QueryFilter;
import com.reactor.cachedb.core.query.QueryOperator;
import com.reactor.cachedb.core.query.QuerySort;
import com.reactor.cachedb.core.query.QuerySortDirection;
import com.reactor.cachedb.core.query.QuerySpec;
import com.reactor.cachedb.core.queue.AdminAlertRule;
import com.reactor.cachedb.core.queue.AdminDiagnosticsRecord;
import com.reactor.cachedb.core.queue.AdminExportFormat;
import com.reactor.cachedb.core.queue.AdminIncident;
import com.reactor.cachedb.core.queue.AdminIncidentSeverity;
import com.reactor.cachedb.core.queue.AdminIncidentRecord;
import com.reactor.cachedb.core.queue.LatencyMetricSnapshot;
import com.reactor.cachedb.core.queue.ProjectionRefreshFailureEntry;
import com.reactor.cachedb.core.queue.ProjectionRefreshReplayResult;
import com.reactor.cachedb.core.queue.ProjectionRefreshSnapshot;
import com.reactor.cachedb.core.queue.ReconciliationHealth;
import com.reactor.cachedb.core.queue.ReconciliationMetrics;
import com.reactor.cachedb.core.queue.RuntimeProfileChurnRecord;
import com.reactor.cachedb.core.queue.SchemaMigrationPlan;
import com.reactor.cachedb.core.queue.SchemaMigrationStep;
import com.reactor.cachedb.core.queue.StoragePerformanceSnapshot;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpPrincipal;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class CacheDatabaseAdminHttpServer implements AutoCloseable {

    private final CacheDatabaseAdmin admin;
    private final CacheDatabaseDebug debug;
    private final AdminHttpConfig config;
    private final ThreadLocal<String> dashboardLanguage = ThreadLocal.withInitial(() -> "tr");
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<String> lastGoodHotSnapshotJson = new AtomicReference<>("");
    private final AtomicReference<String> lastHotSnapshotError = new AtomicReference<>("");
    private final AtomicReference<String> lastServedHotSnapshotJson = new AtomicReference<>("");
    private final String dashboardInstanceId = UUID.randomUUID().toString();
    private final AtomicLong lastGoodHotSnapshotAtEpochMillis = new AtomicLong();
    private final AtomicLong lastServedHotSnapshotAtEpochMillis = new AtomicLong();
    private HttpServer server;
    private ExecutorService executor;

    public CacheDatabaseAdminHttpServer(CacheDatabase cacheDatabase, AdminHttpConfig config) {
        this.admin = cacheDatabase.admin();
        this.debug = cacheDatabase.debug();
        this.config = config;
    }

    public void start() throws IOException {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        server = HttpServer.create(new InetSocketAddress(config.host(), config.port()), config.backlog());
        executor = Executors.newFixedThreadPool(Math.max(1, config.workerThreads()), new AdminHttpThreadFactory());
        server.setExecutor(executor);
        server.createContext("/", this::handleRoot);
        server.createContext("/dashboard", this::handleDashboard);
        server.createContext("/dashboard-v3", this::handleLegacyDashboard);
        server.createContext("/migration-planner", this::handleMigrationPlanner);
        server.createContext("/api/health", this::handleHealth);
        server.createContext("/api/metrics", this::handleMetrics);
        server.createContext("/api/dashboard/hot", this::handleDashboardHot);
        server.createContext("/api/performance", this::handlePerformance);
        server.createContext("/api/performance/history", this::handlePerformanceHistory);
        server.createContext("/api/performance/reset", this::handlePerformanceReset);
        server.createContext("/api/prometheus", this::handlePrometheus);
        server.createContext("/api/prometheus/rules", this::handlePrometheusRules);
        server.createContext("/api/alert-rules", this::handleAlertRules);
        server.createContext("/api/incidents", this::handleIncidents);
        server.createContext("/api/incident-history", this::handleIncidentHistory);
        server.createContext("/api/incident-severity/history", this::handleIncidentSeverityHistory);
        server.createContext("/api/failing-signals", this::handleFailingSignals);
        server.createContext("/api/diagnostics", this::handleDiagnostics);
        server.createContext("/api/profile-churn", this::handleProfileChurn);
        server.createContext("/api/query-index/rebuild", this::handleQueryIndexRebuild);
        server.createContext("/api/deployment", this::handleDeployment);
        server.createContext("/api/schema/status", this::handleSchemaStatus);
        server.createContext("/api/registry", this::handleRegistry);
        server.createContext("/api/tuning", this::handleTuning);
        server.createContext("/api/tuning/export", this::handleTuningExport);
        server.createContext("/api/tuning/flags", this::handleTuningFlags);
        server.createContext("/api/runtime-profile", this::handleRuntimeProfile);
        server.createContext("/api/certification", this::handleCertification);
        server.createContext("/api/schema/plan", this::handleSchemaPlan);
        server.createContext("/api/schema/history", this::handleSchemaHistory);
        server.createContext("/api/schema/ddl", this::handleSchemaDdl);
        server.createContext("/api/profiles", this::handleProfiles);
        server.createContext("/api/triage", this::handleTriage);
        server.createContext("/api/services", this::handleServices);
        server.createContext("/api/background-errors", this::handleBackgroundErrors);
        server.createContext("/api/alert-routing", this::handleAlertRouting);
        server.createContext("/api/alert-routing/history", this::handleAlertRouteHistory);
        server.createContext("/api/runbooks", this::handleRunbooks);
        server.createContext("/api/history", this::handleHistory);
        server.createContext("/api/projection-refresh", this::handleProjectionRefresh);
        server.createContext("/api/projection-refresh/failed", this::handleProjectionRefreshFailures);
        server.createContext("/api/projection-refresh/replay", this::handleProjectionRefreshReplay);
        server.createContext("/api/telemetry/reset", this::handleTelemetryReset);
        server.createContext("/api/explain", this::handleExplain);
        server.createContext("/api/explain/note", this::handleExplainNote);
        server.createContext("/api/migration-planner/template", this::handleMigrationPlannerTemplate);
        server.createContext("/api/migration-planner/demo", this::handleMigrationPlannerDemo);
        server.createContext("/api/migration-planner/discovery", this::handleMigrationPlannerDiscovery);
        server.createContext("/api/migration-planner/plan", this::handleMigrationPlannerPlan);
        server.createContext("/api/migration-planner/warm", this::handleMigrationPlannerWarm);
        server.createContext("/api/migration-planner/scaffold", this::handleMigrationPlannerScaffold);
        server.createContext("/api/migration-planner/compare", this::handleMigrationPlannerCompare);
        server.start();
    }

    public int port() {
        return server == null ? -1 : server.getAddress().getPort();
    }

    public URI baseUri() {
        return URI.create("http://" + config.host() + ":" + port());
    }

    public AdminHttpResponse dispatch(String method, URI requestUri, byte[] requestBody) throws IOException {
        InMemoryHttpExchange exchange = new InMemoryHttpExchange(method, requestUri, requestBody);
        handleDispatched(exchange);
        return exchange.toResponse();
    }

    public DashboardTemplateModel renderDashboardTemplateModel(String language, String dashboardPath) {
        String normalizedLanguage = normalizeDashboardLanguage(language);
        String previousLanguage = dashboardLanguage.get();
        dashboardLanguage.set(normalizedLanguage);
        try {
            String html = renderDashboard(normalizedLanguage, dashboardPath);
            return new DashboardTemplateModel(
                    normalizedLanguage,
                    extractBetween(html, "<head>", "</head>"),
                    extractBetween(html, "<body>", "</body>")
            );
        } finally {
            dashboardLanguage.set(previousLanguage);
        }
    }

    public DashboardTemplateModel renderMigrationPlannerTemplateModel(String language, String plannerPath) {
        return renderMigrationPlannerTemplateModel(language, plannerPath, (String) null);
    }

    public DashboardTemplateModel renderMigrationPlannerTemplateModel(String language, String plannerPath, String rawQuery) {
        return renderMigrationPlannerTemplateModel(
                language,
                plannerPath,
                resolveMigrationPlannerPageState(parseQuery(rawQuery), null)
        );
    }

    public DashboardTemplateModel renderMigrationPlannerTemplateModel(
            String language,
            String plannerPath,
            MigrationSchemaDiscovery.Result bootstrapDiscovery
    ) {
        return renderMigrationPlannerTemplateModel(
                language,
                plannerPath,
                resolveMigrationPlannerPageState(Map.of(), bootstrapDiscovery)
        );
    }

    private DashboardTemplateModel renderMigrationPlannerTemplateModel(
            String language,
            String plannerPath,
            MigrationPlannerPageState pageState
    ) {
        String normalizedLanguage = normalizeDashboardLanguage(language);
        String previousLanguage = dashboardLanguage.get();
        dashboardLanguage.set(normalizedLanguage);
        try {
            String html = renderMigrationPlanner(normalizedLanguage, plannerPath, pageState);
            return new DashboardTemplateModel(
                    normalizedLanguage,
                    extractBetween(html, "<head>", "</head>"),
                    extractBetween(html, "<body>", "</body>")
            );
        } finally {
            dashboardLanguage.set(previousLanguage);
        }
    }

    private void handleDispatched(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        switch (path) {
            case "/" -> handleRoot(exchange);
            case "/dashboard" -> handleDashboard(exchange);
            case "/dashboard-v3" -> handleLegacyDashboard(exchange);
            case "/migration-planner" -> handleMigrationPlanner(exchange);
            case "/api/health" -> handleHealth(exchange);
            case "/api/metrics" -> handleMetrics(exchange);
            case "/api/dashboard/hot" -> handleDashboardHot(exchange);
            case "/api/performance" -> handlePerformance(exchange);
            case "/api/performance/history" -> handlePerformanceHistory(exchange);
            case "/api/performance/reset" -> handlePerformanceReset(exchange);
            case "/api/prometheus" -> handlePrometheus(exchange);
            case "/api/prometheus/rules" -> handlePrometheusRules(exchange);
            case "/api/alert-rules" -> handleAlertRules(exchange);
            case "/api/incidents" -> handleIncidents(exchange);
            case "/api/incident-history" -> handleIncidentHistory(exchange);
            case "/api/incident-severity/history" -> handleIncidentSeverityHistory(exchange);
            case "/api/failing-signals" -> handleFailingSignals(exchange);
            case "/api/diagnostics" -> handleDiagnostics(exchange);
            case "/api/profile-churn" -> handleProfileChurn(exchange);
            case "/api/query-index/rebuild" -> handleQueryIndexRebuild(exchange);
            case "/api/deployment" -> handleDeployment(exchange);
            case "/api/schema/status" -> handleSchemaStatus(exchange);
            case "/api/registry" -> handleRegistry(exchange);
            case "/api/tuning" -> handleTuning(exchange);
            case "/api/tuning/export" -> handleTuningExport(exchange);
            case "/api/tuning/flags" -> handleTuningFlags(exchange);
            case "/api/runtime-profile" -> handleRuntimeProfile(exchange);
            case "/api/certification" -> handleCertification(exchange);
            case "/api/schema/plan" -> handleSchemaPlan(exchange);
            case "/api/schema/history" -> handleSchemaHistory(exchange);
            case "/api/schema/ddl" -> handleSchemaDdl(exchange);
            case "/api/profiles" -> handleProfiles(exchange);
            case "/api/triage" -> handleTriage(exchange);
            case "/api/services" -> handleServices(exchange);
            case "/api/background-errors" -> handleBackgroundErrors(exchange);
            case "/api/alert-routing" -> handleAlertRouting(exchange);
            case "/api/alert-routing/history" -> handleAlertRouteHistory(exchange);
            case "/api/runbooks" -> handleRunbooks(exchange);
            case "/api/history" -> handleHistory(exchange);
            case "/api/projection-refresh" -> handleProjectionRefresh(exchange);
            case "/api/projection-refresh/failed" -> handleProjectionRefreshFailures(exchange);
            case "/api/projection-refresh/replay" -> handleProjectionRefreshReplay(exchange);
            case "/api/telemetry/reset" -> handleTelemetryReset(exchange);
            case "/api/explain" -> handleExplain(exchange);
            case "/api/explain/note" -> handleExplainNote(exchange);
            case "/api/migration-planner/template" -> handleMigrationPlannerTemplate(exchange);
            case "/api/migration-planner/demo" -> handleMigrationPlannerDemo(exchange);
            case "/api/migration-planner/discovery" -> handleMigrationPlannerDiscovery(exchange);
            case "/api/migration-planner/plan" -> handleMigrationPlannerPlan(exchange);
            case "/api/migration-planner/warm" -> handleMigrationPlannerWarm(exchange);
            case "/api/migration-planner/scaffold" -> handleMigrationPlannerScaffold(exchange);
            case "/api/migration-planner/compare" -> handleMigrationPlannerCompare(exchange);
            default -> sendText(exchange, 404, "text/plain; charset=utf-8", "Not found");
        }
    }

    private String extractBetween(String html, String startToken, String endToken) {
        int start = html.indexOf(startToken);
        if (start < 0) {
            return "";
        }
        start += startToken.length();
        int end = html.indexOf(endToken, start);
        if (end < 0) {
            return "";
        }
        return html.substring(start, end);
    }

    private void handleRoot(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        if (config.dashboardEnabled()) {
            renderDashboardResponse(exchange);
            return;
        }
        sendJson(exchange, 200, "{\"service\":\"cachedb-admin\",\"status\":\"UP\"}");
    }

    private void handleDashboard(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        renderDashboardResponse(exchange);
    }

    private void handleLegacyDashboard(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        redirect(exchange, appendQuery("/", exchange.getRequestURI().getRawQuery()));
    }

    private void handleMigrationPlanner(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        Map<String, List<String>> query = parseQuery(exchange.getRequestURI().getRawQuery());
        String language = normalizeDashboardLanguage(first(query, "lang"));
        String previousLanguage = dashboardLanguage.get();
        dashboardLanguage.set(language);
        try {
            MigrationPlannerPageState pageState = resolveMigrationPlannerPageState(query, null);
            sendText(
                    exchange,
                    200,
                    "text/html; charset=utf-8",
                    renderMigrationPlanner(
                            language,
                            exchange.getRequestURI().getPath(),
                            pageState
                    )
            );
        } finally {
            dashboardLanguage.set(previousLanguage);
        }
    }

    private void renderDashboardResponse(HttpExchange exchange) throws IOException {
        if (!config.dashboardEnabled()) {
            sendText(exchange, 404, "text/plain; charset=utf-8", "Dashboard is disabled");
            return;
        }
        Map<String, List<String>> query = parseQuery(exchange.getRequestURI().getRawQuery());
        String language = normalizeDashboardLanguage(first(query, "lang"));
        String previousLanguage = dashboardLanguage.get();
        dashboardLanguage.set(language);
        try {
            sendText(exchange, 200, "text/html; charset=utf-8", renderDashboard(language, exchange.getRequestURI().getPath()));
        } finally {
            dashboardLanguage.set(previousLanguage);
        }
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        ReconciliationHealth health = admin.health();
        sendJson(exchange, 200, "{\"status\":\"" + escapeJson(health.status().name()) + "\",\"issues\":"
                + toJsonStringArray(health.issues()) + ",\"metrics\":" + renderMetrics(health.metrics()) + "}");
    }

    private void handleMetrics(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        sendJson(exchange, 200, renderMetrics(admin.metrics()));
    }

    private void handleDashboardHot(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        sendJson(exchange, 200, renderDashboardHotSnapshotSafely());
    }

    private void handlePerformance(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        sendJson(exchange, 200, renderStoragePerformance(admin.storagePerformance()));
    }

    private void handlePerformanceHistory(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        Map<String, List<String>> query = parseQuery(exchange.getRequestURI().getRawQuery());
        sendJson(exchange, 200, renderPerformanceHistory(admin.performanceHistory(parseInt(query.get("limit"), 60))));
    }

    private void handlePerformanceReset(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        sendJson(exchange, 200, renderPerformanceReset(admin.resetPerformanceTelemetry()));
    }

    private void handlePrometheus(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        sendText(exchange, 200, "text/plain; version=0.0.4; charset=utf-8", renderPrometheus(admin.metrics()));
    }

    private void handlePrometheusRules(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        sendText(exchange, 200, "text/yaml; charset=utf-8", admin.exportPrometheusAlertRules());
    }

    private void handleAlertRules(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        List<AdminAlertRule> items = admin.alertRules();
        StringBuilder builder = new StringBuilder("{\"items\":[");
        for (int index = 0; index < items.size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            AdminAlertRule item = items.get(index);
            builder.append("{\"code\":\"").append(escapeJson(item.code())).append("\",")
                    .append("\"description\":\"").append(escapeJson(item.description())).append("\",")
                    .append("\"warningSeverity\":\"").append(item.warningSeverity().name()).append("\",")
                    .append("\"criticalSeverity\":\"").append(item.criticalSeverity().name()).append("\"}");
        }
        builder.append("]}");
        sendJson(exchange, 200, builder.toString());
    }

    private void handleIncidents(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        sendJson(exchange, 200, renderCurrentIncidents(admin.incidents()));
    }

    private void handleIncidentHistory(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        Map<String, List<String>> query = parseQuery(exchange.getRequestURI().getRawQuery());
        sendJson(exchange, 200, renderIncidentHistory(admin.incidentHistory(parseInt(query.get("limit"), 20))));
    }

    private void handleIncidentSeverityHistory(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        Map<String, List<String>> query = parseQuery(exchange.getRequestURI().getRawQuery());
        sendJson(exchange, 200, renderIncidentSeverityHistory(admin.incidentSeverityHistory(parseInt(query.get("limit"), 120))));
    }

    private void handleFailingSignals(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        Map<String, List<String>> query = parseQuery(exchange.getRequestURI().getRawQuery());
        try {
            sendJson(exchange, 200, renderFailingSignals(admin.topFailingSignals(parseInt(query.get("limit"), 6))));
        } catch (RuntimeException exception) {
            sendJson(exchange, 200, renderFailingSignals(List.of()));
        }
    }

    private void handleDiagnostics(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        Map<String, List<String>> query = parseQuery(exchange.getRequestURI().getRawQuery());
        sendJson(exchange, 200, renderDiagnostics(admin.diagnostics(parseInt(query.get("limit"), 20))));
    }

    private void handleProfileChurn(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        Map<String, List<String>> query = parseQuery(exchange.getRequestURI().getRawQuery());
        sendJson(exchange, 200, renderProfileChurn(admin.runtimeProfileChurn(parseInt(query.get("limit"), 24))));
    }

    private void handleQueryIndexRebuild(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod()) && !"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        Map<String, List<String>> query = parseQuery(exchange.getRequestURI().getRawQuery());
        String entity = first(query, "entity");
        String note = defaultString(first(query, "note"));
        if (entity == null || entity.isBlank()) {
            List<com.reactor.cachedb.core.queue.QueryIndexRebuildResult> results = admin.rebuildAllQueryIndexes(note.isBlank() ? "http-rebuild-all" : note);
            StringBuilder builder = new StringBuilder("{\"mode\":\"ALL\",\"items\":[");
            for (int index = 0; index < results.size(); index++) {
                if (index > 0) {
                    builder.append(',');
                }
                builder.append(renderQueryIndexRebuildResult(results.get(index)));
            }
            builder.append("]}");
            sendJson(exchange, 200, builder.toString());
            return;
        }
        sendJson(exchange, 200, renderQueryIndexRebuildResult(admin.rebuildQueryIndexes(entity, note.isBlank() ? "http-rebuild" : note)));
    }

    private void handleDeployment(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        sendJson(exchange, 200, renderDeploymentStatus(admin.deploymentStatus()));
    }

    private void handleSchemaStatus(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        sendJson(exchange, 200, renderSchemaStatus(admin.schemaStatus()));
    }

    private void handleRegistry(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        sendJson(exchange, 200, renderApiProduct(admin.apiProduct()));
    }

    private void handleTuning(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        sendJson(exchange, 200, renderEffectiveTuning(admin.effectiveTuning()));
    }

    private void handleTuningExport(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        Map<String, List<String>> query = parseQuery(exchange.getRequestURI().getRawQuery());
        String format = defaultString(first(query, "format")).trim().toLowerCase(Locale.ROOT);
        EffectiveTuningSnapshot snapshot = admin.effectiveTuning();
        if ("markdown".equals(format) || "md".equals(format)) {
            sendText(exchange, 200, "text/markdown; charset=utf-8", renderEffectiveTuningMarkdown(snapshot));
            return;
        }
        sendJson(exchange, 200, renderEffectiveTuning(snapshot));
    }

    private void handleTuningFlags(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        sendText(exchange, 200, "text/plain; charset=utf-8", renderEffectiveTuningFlags(admin.effectiveTuning()));
    }

    private void handleRuntimeProfile(HttpExchange exchange) throws IOException {
        Map<String, List<String>> query = parseQuery(exchange.getRequestURI().getRawQuery());
        if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 200, renderRuntimeProfileDetails(admin.runtimeProfileDetails()));
            return;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        String profile = defaultString(first(query, "profile")).trim();
        if (profile.isBlank()) {
            sendJson(exchange, 400, "{\"error\":\"Missing required query parameter: profile\"}");
            return;
        }
        RuntimeProfileDetailsSnapshot snapshot = "AUTO".equalsIgnoreCase(profile)
                ? admin.clearRuntimeProfileOverride()
                : admin.setRuntimeProfile(profile);
        sendJson(exchange, 200, renderRuntimeProfileDetails(snapshot));
    }

    private void handleCertification(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        sendJson(exchange, 200, renderProductionReports(admin.productionReports()));
    }

    private void handleSchemaPlan(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod()) && !"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        Map<String, List<String>> query = parseQuery(exchange.getRequestURI().getRawQuery());
        boolean apply = "true".equalsIgnoreCase(first(query, "apply"));
        SchemaMigrationPlan plan = apply ? admin.applySchemaMigrationPlan() : admin.schemaMigrationPlan();
        sendJson(exchange, 200, renderSchemaMigrationPlan(plan, apply));
    }

    private void handleSchemaHistory(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        Map<String, List<String>> query = parseQuery(exchange.getRequestURI().getRawQuery());
        sendJson(exchange, 200, renderSchemaMigrationHistory(admin.schemaMigrationHistory(parseInt(query.get("limit"), 10))));
    }

    private void handleSchemaDdl(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        sendJson(exchange, 200, renderSchemaDdl(admin.schemaDdl()));
    }

    private void handleProfiles(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        sendJson(exchange, 200, renderStarterProfiles(admin.starterProfiles()));
    }

    private void handleTriage(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        sendJson(exchange, 200, renderMonitoringTriage(admin.triage()));
    }

    private void handleServices(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        sendJson(exchange, 200, renderMonitoringServices(admin.monitoringServices()));
    }

    private void handleBackgroundErrors(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        sendJson(exchange, 200, renderBackgroundWorkerErrors(admin.backgroundWorkerErrors()));
    }

    private void handleAlertRouting(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        sendJson(exchange, 200, renderAlertRoutes(admin.alertRoutes()));
    }

    private void handleRunbooks(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        sendJson(exchange, 200, renderRunbooks(admin.runbooks()));
    }

    private void handleAlertRouteHistory(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        Map<String, List<String>> query = parseQuery(exchange.getRequestURI().getRawQuery());
        sendJson(exchange, 200, renderAlertRouteHistory(admin.alertRouteHistory(parseInt(query.get("limit"), 120))));
    }

    private void handleHistory(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        Map<String, List<String>> query = parseQuery(exchange.getRequestURI().getRawQuery());
        sendJson(exchange, 200, renderMonitoringHistory(admin.monitoringHistory(parseInt(query.get("limit"), 60))));
    }

    private void handleProjectionRefresh(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        sendJson(exchange, 200, renderProjectionRefreshSnapshot(admin.projectionRefresh()));
    }

    private void handleProjectionRefreshFailures(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        Map<String, List<String>> query = parseQuery(exchange.getRequestURI().getRawQuery());
        sendJson(exchange, 200, renderProjectionRefreshFailures(admin.projectionRefreshFailures(parseInt(query.get("limit"), 20))));
    }

    private void handleProjectionRefreshReplay(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        Map<String, List<String>> query = parseQuery(exchange.getRequestURI().getRawQuery());
        String entryId = first(query, "entryId");
        if (entryId == null || entryId.isBlank()) {
            sendJson(exchange, 400, "{\"error\":\"Missing required query parameter: entryId\"}");
            return;
        }
        sendJson(exchange, 200, renderProjectionRefreshReplayResult(admin.replayProjectionRefreshFailure(entryId)));
    }

    private void handleTelemetryReset(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        sendJson(exchange, 200, renderTelemetryReset(admin.resetTelemetry()));
    }

    private void handleExplain(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        Map<String, List<String>> query = parseQuery(exchange.getRequestURI().getRawQuery());
        String entityName = first(query, "entity");
        if (entityName == null || entityName.isBlank()) {
            sendJson(exchange, 400, "{\"error\":\"Missing required query parameter: entity\"}");
            return;
        }
        QuerySpec querySpec = parseQuerySpec(query);
        AdminExportFormat format = parseFormat(first(query, "format"));
        var export = debug.exportExplain(entityName, querySpec, format);
        sendText(exchange, 200, export.contentType() + "; charset=utf-8", export.content());
    }

    private void handleExplainNote(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        Map<String, List<String>> query = parseQuery(exchange.getRequestURI().getRawQuery());
        String entityName = first(query, "entity");
        if (entityName == null || entityName.isBlank()) {
            sendJson(exchange, 400, "{\"error\":\"Missing required query parameter: entity\"}");
            return;
        }
        QuerySpec querySpec = parseQuerySpec(query);
        String noteTitle = defaultString(first(query, "title"));
        String noteTitleValue = noteTitle.isBlank() ? "Explain note" : noteTitle;
        var export = debug.exportExplain(entityName, querySpec, AdminExportFormat.MARKDOWN);
        AdminDiagnosticsRecord record = admin.persistDiagnostics(
                "explain-note",
                noteTitleValue + " | entity=" + entityName + "\n\n" + export.content()
        );
        sendJson(exchange, 200, renderExplainNoteResult(record, noteTitleValue));
    }

    private void handleMigrationPlannerTemplate(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        sendJson(exchange, 200, renderMigrationPlannerTemplate(admin.migrationPlannerTemplate()));
    }

    private void handleMigrationPlannerDemo(HttpExchange exchange) throws IOException {
        if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 200, renderMigrationPlannerDemoDescriptor(admin.migrationPlannerDemoDescriptor()));
            return;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        Map<String, List<String>> parameters = parseFormParameters(exchange);
        try {
            sendJson(exchange, 200, renderMigrationPlannerDemoBootstrapResult(
                    admin.bootstrapMigrationPlannerDemo(parseMigrationPlannerDemoBootstrapRequest(parameters))
            ));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            sendJson(exchange, 400, "{\"error\":\"" + escapeJson(exception.getMessage()) + "\"}");
        }
    }

    public String dashboardInstanceId() {
        return dashboardInstanceId;
    }

    public MigrationSchemaDiscovery.Result discoverMigrationSchema() {
        return admin.discoverMigrationSchema();
    }

    private void handleMigrationPlannerDiscovery(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        try {
            sendJson(exchange, 200, renderMigrationSchemaDiscovery(admin.discoverMigrationSchema()));
        } catch (IllegalStateException exception) {
            sendJson(exchange, 500, "{\"error\":\"" + escapeJson(exception.getMessage()) + "\"}");
        }
    }

    private void handleMigrationPlannerPlan(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod()) && !"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        Map<String, List<String>> parameters = "POST".equalsIgnoreCase(exchange.getRequestMethod())
                ? parseFormParameters(exchange)
                : parseQuery(exchange.getRequestURI().getRawQuery());
        sendJson(exchange, 200, renderMigrationPlannerResult(admin.planMigration(parseMigrationPlannerRequest(parameters))));
    }

    private void handleMigrationPlannerWarm(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod()) && !"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        Map<String, List<String>> parameters = "POST".equalsIgnoreCase(exchange.getRequestMethod())
                ? parseFormParameters(exchange)
                : parseQuery(exchange.getRequestURI().getRawQuery());
        try {
            sendJson(exchange, 200, renderMigrationWarmResult(admin.warmMigration(parseMigrationWarmRequest(parameters))));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            sendJson(exchange, 400, "{\"error\":\"" + escapeJson(exception.getMessage()) + "\"}");
        }
    }

    private void handleMigrationPlannerScaffold(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod()) && !"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        Map<String, List<String>> parameters = "POST".equalsIgnoreCase(exchange.getRequestMethod())
                ? parseFormParameters(exchange)
                : parseQuery(exchange.getRequestURI().getRawQuery());
        try {
            sendJson(exchange, 200, renderMigrationScaffoldResult(admin.generateMigrationScaffold(parseMigrationScaffoldRequest(parameters))));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            sendJson(exchange, 400, "{\"error\":\"" + escapeJson(exception.getMessage()) + "\"}");
        }
    }

    private void handleMigrationPlannerCompare(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod()) && !"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        Map<String, List<String>> parameters = "POST".equalsIgnoreCase(exchange.getRequestMethod())
                ? parseFormParameters(exchange)
                : parseQuery(exchange.getRequestURI().getRawQuery());
        try {
            sendJson(exchange, 200, renderMigrationComparisonResult(admin.compareMigration(parseMigrationComparisonRequest(parameters))));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            sendJson(exchange, 400, "{\"error\":\"" + escapeJson(exception.getMessage()) + "\"}");
        }
    }

    private QuerySpec parseQuerySpec(Map<String, List<String>> query) {
        QuerySpec.Builder builder = QuerySpec.builder()
                .offset(parseInt(query.get("offset"), 0))
                .limit(parseInt(query.get("limit"), 100));
        for (String rawFilter : query.getOrDefault("filter", List.of())) {
            builder.filter(parseFilter(rawFilter));
        }
        for (String rawSort : query.getOrDefault("sort", List.of())) {
            builder.sort(parseSort(rawSort));
        }
        List<String> includes = new ArrayList<>();
        for (String include : query.getOrDefault("include", List.of())) {
            for (String part : include.split(",")) {
                String value = part.trim();
                if (!value.isBlank()) {
                    includes.add(value);
                }
            }
        }
        if (!includes.isEmpty()) {
            builder.fetchPlan(FetchPlan.of(includes.toArray(String[]::new)));
        }
        return builder.build();
    }

    private MigrationPlanner.Request parseMigrationPlannerRequest(Map<String, List<String>> parameters) {
        return new MigrationPlanner.Request(
                first(parameters, "workloadName"),
                first(parameters, "rootTableOrEntity"),
                first(parameters, "rootPrimaryKeyColumn"),
                first(parameters, "childTableOrEntity"),
                first(parameters, "childPrimaryKeyColumn"),
                first(parameters, "relationColumn"),
                first(parameters, "sortColumn"),
                first(parameters, "sortDirection"),
                parseLong(parameters.get("rootRowCount"), 0L),
                parseLong(parameters.get("childRowCount"), 0L),
                parseLong(parameters.get("typicalChildrenPerRoot"), 0L),
                parseLong(parameters.get("maxChildrenPerRoot"), 0L),
                parseInt(parameters.get("firstPageSize"), 100),
                parseInt(parameters.get("hotWindowPerRoot"), 1_000),
                parseBoolean(parameters.get("listScreen"), true),
                parseBoolean(parameters.get("firstPaintNeedsFullAggregate"), false),
                parseBoolean(parameters.get("globalSortedScreen"), false),
                parseBoolean(parameters.get("thresholdOrRangeScreen"), false),
                parseBoolean(parameters.get("archiveHistoryRequired"), true),
                parseBoolean(parameters.get("fullHistoryMustStayHot"), false),
                parseBoolean(parameters.get("currentOrmUsesEagerLoading"), true),
                parseBoolean(parameters.get("detailLookupIsHot"), true),
                parseBoolean(parameters.get("sideBySideComparisonRequired"), true)
        );
    }

    private MigrationPlannerDemoSupport.BootstrapRequest parseMigrationPlannerDemoBootstrapRequest(Map<String, List<String>> parameters) {
        return new MigrationPlannerDemoSupport.BootstrapRequest(
                parseInt(parameters.get("demoCustomerCount"), 120),
                parseInt(parameters.get("demoHotCustomerCount"), 12),
                parseInt(parameters.get("demoMaxOrdersPerCustomer"), 1500)
        );
    }

    private MigrationWarmRunner.Request parseMigrationWarmRequest(Map<String, List<String>> parameters) {
        return new MigrationWarmRunner.Request(
                parseMigrationPlannerRequest(parameters),
                parseBoolean(parameters.get("warmRootRows"), true),
                parseBoolean(parameters.get("dryRun"), false),
                parseInt(parameters.get("childFetchSize"), 500),
                parseInt(parameters.get("rootFetchSize"), 500),
                parseInt(parameters.get("rootBatchSize"), 250)
        );
    }

    private MigrationScaffoldGenerator.Request parseMigrationScaffoldRequest(Map<String, List<String>> parameters) {
        return new MigrationScaffoldGenerator.Request(
                parseMigrationPlannerRequest(parameters),
                first(parameters, "basePackage"),
                first(parameters, "rootClassName"),
                first(parameters, "childClassName"),
                first(parameters, "relationLoaderClassName"),
                first(parameters, "projectionSupportClassName"),
                parseBoolean(parameters.get("includeRelationLoader"), true),
                parseBoolean(parameters.get("includeProjectionSkeleton"), true)
        );
    }

    private MigrationComparisonRunner.Request parseMigrationComparisonRequest(Map<String, List<String>> parameters) {
        return new MigrationComparisonRunner.Request(
                parseMigrationPlannerRequest(parameters),
                parseBoolean(parameters.get("warmBeforeCompare"), false),
                parseBoolean(parameters.get("warmRootRows"), true),
                parseInt(parameters.get("childFetchSize"), 500),
                parseInt(parameters.get("rootFetchSize"), 500),
                parseInt(parameters.get("rootBatchSize"), 250),
                first(parameters, "comparisonSampleRootId"),
                parseInt(parameters.get("comparisonSampleRootCount"), 3),
                parseInt(parameters.get("comparisonWarmupIterations"), 2),
                parseInt(parameters.get("comparisonMeasuredIterations"), 8),
                parseInt(parameters.get("comparisonPageSize"), parseInt(parameters.get("firstPageSize"), 100)),
                first(parameters, "comparisonProjectionName"),
                first(parameters, "comparisonBaselineSql")
        );
    }

    private QueryFilter parseFilter(String rawFilter) {
        String[] parts = rawFilter.split(":", 3);
        if (parts.length < 3) {
            throw new IllegalArgumentException("Invalid filter syntax: " + rawFilter);
        }
        String column = parts[0].trim();
        QueryOperator operator = QueryOperator.valueOf(parts[1].trim().toUpperCase(Locale.ROOT));
        String rawValue = parts[2].trim();
        return switch (operator) {
            case EQ -> QueryFilter.eq(column, rawValue);
            case NE -> QueryFilter.ne(column, rawValue);
            case GT -> QueryFilter.gt(column, rawValue);
            case GTE -> QueryFilter.gte(column, rawValue);
            case LT -> QueryFilter.lt(column, rawValue);
            case LTE -> QueryFilter.lte(column, rawValue);
            case CONTAINS -> QueryFilter.contains(column, rawValue);
            case STARTS_WITH -> QueryFilter.startsWith(column, rawValue);
            case IN -> QueryFilter.in(column, java.util.Arrays.stream(rawValue.split("\\|"))
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .map(value -> (Object) value)
                    .toList());
        };
    }

    private QuerySort parseSort(String rawSort) {
        String[] parts = rawSort.split(":", 2);
        QuerySortDirection direction = parts.length == 1 ? QuerySortDirection.ASC
                : QuerySortDirection.valueOf(parts[1].trim().toUpperCase(Locale.ROOT));
        return new QuerySort(parts[0].trim(), direction);
    }

    private Map<String, List<String>> parseQuery(String rawQuery) {
        LinkedHashMap<String, List<String>> values = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return values;
        }
        for (String pair : rawQuery.split("&")) {
            String[] parts = pair.split("=", 2);
            String key = decode(parts[0]);
            String value = parts.length > 1 ? decode(parts[1]) : "";
            values.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value);
        }
        return values;
    }

    private Map<String, List<String>> parseFormParameters(HttpExchange exchange) throws IOException {
        String contentType = defaultString(exchange.getRequestHeaders().getFirst("Content-Type")).toLowerCase(Locale.ROOT);
        if (!contentType.contains("application/x-www-form-urlencoded")) {
            return parseQuery(readRequestBody(exchange));
        }
        return parseQuery(readRequestBody(exchange));
    }

    private String readRequestBody(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readAllBytes();
        if (bytes.length == 0) {
            return "";
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private String renderMetrics(ReconciliationMetrics metrics) {
        return "{"
                + "\"writeBehindStreamLength\":" + metrics.writeBehindStreamLength() + ","
                + "\"deadLetterStreamLength\":" + metrics.deadLetterStreamLength() + ","
                + "\"reconciliationStreamLength\":" + metrics.reconciliationStreamLength() + ","
                + "\"archiveStreamLength\":" + metrics.archiveStreamLength() + ","
                + "\"diagnosticsStreamLength\":" + metrics.diagnosticsStreamLength() + ","
                + "\"projectionRefreshSnapshot\":" + renderProjectionRefreshSnapshot(metrics.projectionRefreshSnapshot()) + ","
                + "\"writeBehindWorkerSnapshot\":" + renderMap(writeBehindWorkerSnapshotMap(metrics)) + ","
                + "\"deadLetterRecoverySnapshot\":" + renderMap(Map.ofEntries(
                        Map.entry("replayedCount", String.valueOf(metrics.deadLetterRecoverySnapshot().replayedCount())),
                        Map.entry("staleSkippedCount", String.valueOf(metrics.deadLetterRecoverySnapshot().staleSkippedCount())),
                        Map.entry("failedCount", String.valueOf(metrics.deadLetterRecoverySnapshot().failedCount())),
                        Map.entry("claimedCount", String.valueOf(metrics.deadLetterRecoverySnapshot().claimedCount())),
                        Map.entry("lastErrorAtEpochMillis", String.valueOf(metrics.deadLetterRecoverySnapshot().lastErrorAtEpochMillis())),
                        Map.entry("lastErrorType", defaultString(metrics.deadLetterRecoverySnapshot().lastErrorType())),
                        Map.entry("lastErrorMessage", defaultString(metrics.deadLetterRecoverySnapshot().lastErrorMessage())),
                        Map.entry("lastErrorRootType", defaultString(metrics.deadLetterRecoverySnapshot().lastErrorRootType())),
                        Map.entry("lastErrorRootMessage", defaultString(metrics.deadLetterRecoverySnapshot().lastErrorRootMessage())),
                        Map.entry("lastErrorOrigin", defaultString(metrics.deadLetterRecoverySnapshot().lastErrorOrigin())),
                        Map.entry("lastErrorStackTrace", defaultString(metrics.deadLetterRecoverySnapshot().lastErrorStackTrace()))
                )) + ","
                + "\"recoveryCleanupSnapshot\":" + renderMap(Map.ofEntries(
                        Map.entry("runCount", String.valueOf(metrics.recoveryCleanupSnapshot().runCount())),
                        Map.entry("prunedDeadLetterCount", String.valueOf(metrics.recoveryCleanupSnapshot().prunedDeadLetterCount())),
                        Map.entry("prunedReconciliationCount", String.valueOf(metrics.recoveryCleanupSnapshot().prunedReconciliationCount())),
                        Map.entry("prunedArchiveCount", String.valueOf(metrics.recoveryCleanupSnapshot().prunedArchiveCount())),
                        Map.entry("lastRunAtEpochMillis", String.valueOf(metrics.recoveryCleanupSnapshot().lastRunAtEpochMillis())),
                        Map.entry("lastErrorAtEpochMillis", String.valueOf(metrics.recoveryCleanupSnapshot().lastErrorAtEpochMillis())),
                        Map.entry("lastErrorType", defaultString(metrics.recoveryCleanupSnapshot().lastErrorType())),
                        Map.entry("lastErrorMessage", defaultString(metrics.recoveryCleanupSnapshot().lastErrorMessage())),
                        Map.entry("lastErrorRootType", defaultString(metrics.recoveryCleanupSnapshot().lastErrorRootType())),
                        Map.entry("lastErrorRootMessage", defaultString(metrics.recoveryCleanupSnapshot().lastErrorRootMessage())),
                        Map.entry("lastErrorOrigin", defaultString(metrics.recoveryCleanupSnapshot().lastErrorOrigin())),
                        Map.entry("lastErrorStackTrace", defaultString(metrics.recoveryCleanupSnapshot().lastErrorStackTrace()))
                )) + ","
                + "\"adminReportJobSnapshot\":" + renderMap(Map.ofEntries(
                        Map.entry("runCount", String.valueOf(metrics.adminReportJobSnapshot().runCount())),
                        Map.entry("writtenFileCount", String.valueOf(metrics.adminReportJobSnapshot().writtenFileCount())),
                        Map.entry("lastRunAtEpochMillis", String.valueOf(metrics.adminReportJobSnapshot().lastRunAtEpochMillis())),
                        Map.entry("lastOutputDirectory", defaultString(metrics.adminReportJobSnapshot().lastOutputDirectory())),
                        Map.entry("lastErrorAtEpochMillis", String.valueOf(metrics.adminReportJobSnapshot().lastErrorAtEpochMillis())),
                        Map.entry("lastErrorType", defaultString(metrics.adminReportJobSnapshot().lastErrorType())),
                        Map.entry("lastErrorMessage", defaultString(metrics.adminReportJobSnapshot().lastErrorMessage())),
                        Map.entry("lastErrorRootType", defaultString(metrics.adminReportJobSnapshot().lastErrorRootType())),
                        Map.entry("lastErrorRootMessage", defaultString(metrics.adminReportJobSnapshot().lastErrorRootMessage())),
                        Map.entry("lastErrorOrigin", defaultString(metrics.adminReportJobSnapshot().lastErrorOrigin())),
                        Map.entry("lastErrorStackTrace", defaultString(metrics.adminReportJobSnapshot().lastErrorStackTrace()))
                )) + ","
                + "\"plannerStatisticsSnapshot\":" + renderMap(Map.of(
                        "estimateKeyCount", String.valueOf(metrics.plannerStatisticsSnapshot().estimateKeyCount()),
                        "histogramKeyCount", String.valueOf(metrics.plannerStatisticsSnapshot().histogramKeyCount()),
                        "learnedStatisticsKeyCount", String.valueOf(metrics.plannerStatisticsSnapshot().learnedStatisticsKeyCount())
                )) + ","
                + "\"redisGuardrailSnapshot\":" + renderMap(Map.ofEntries(
                        Map.entry("usedMemoryBytes", String.valueOf(metrics.redisGuardrailSnapshot().usedMemoryBytes())),
                        Map.entry("usedMemoryPeakBytes", String.valueOf(metrics.redisGuardrailSnapshot().usedMemoryPeakBytes())),
                        Map.entry("maxMemoryBytes", String.valueOf(metrics.redisGuardrailSnapshot().maxMemoryBytes())),
                        Map.entry("writeBehindBacklog", String.valueOf(metrics.redisGuardrailSnapshot().writeBehindBacklog())),
                        Map.entry("compactionPendingCount", String.valueOf(metrics.redisGuardrailSnapshot().compactionPendingCount())),
                        Map.entry("compactionPayloadCount", String.valueOf(metrics.redisGuardrailSnapshot().compactionPayloadCount())),
                        Map.entry("hardRejectedWriteCount", String.valueOf(metrics.redisGuardrailSnapshot().hardRejectedWriteCount())),
                        Map.entry("producerHighPressureDelayCount", String.valueOf(metrics.redisGuardrailSnapshot().producerHighPressureDelayCount())),
                        Map.entry("producerCriticalPressureDelayCount", String.valueOf(metrics.redisGuardrailSnapshot().producerCriticalPressureDelayCount())),
                        Map.entry("lastSampleAtEpochMillis", String.valueOf(metrics.redisGuardrailSnapshot().lastSampleAtEpochMillis())),
                        Map.entry("pressureLevel", defaultString(metrics.redisGuardrailSnapshot().pressureLevel()))
                )) + ","
                + "\"redisRuntimeProfileSnapshot\":" + renderMap(Map.of(
                        "activeProfile", defaultString(metrics.redisRuntimeProfileSnapshot().activeProfile()),
                        "lastObservedPressureLevel", defaultString(metrics.redisRuntimeProfileSnapshot().lastObservedPressureLevel()),
                        "switchCount", String.valueOf(metrics.redisRuntimeProfileSnapshot().switchCount()),
                        "lastSwitchedAtEpochMillis", String.valueOf(metrics.redisRuntimeProfileSnapshot().lastSwitchedAtEpochMillis()),
                        "normalPressureSamples", String.valueOf(metrics.redisRuntimeProfileSnapshot().normalPressureSamples()),
                        "warnPressureSamples", String.valueOf(metrics.redisRuntimeProfileSnapshot().warnPressureSamples()),
                        "criticalPressureSamples", String.valueOf(metrics.redisRuntimeProfileSnapshot().criticalPressureSamples())
                )) + ","
                + "\"incidentDeliverySnapshot\":{"
                + "\"enqueuedCount\":" + metrics.incidentDeliverySnapshot().enqueuedCount() + ","
                + "\"dequeuedCount\":" + metrics.incidentDeliverySnapshot().dequeuedCount() + ","
                + "\"droppedBeforeDeliveryCount\":" + metrics.incidentDeliverySnapshot().droppedBeforeDeliveryCount() + ","
                + "\"lastEnqueuedAtEpochMillis\":" + metrics.incidentDeliverySnapshot().lastEnqueuedAtEpochMillis() + ","
                + "\"channels\":" + renderChannelSnapshots(metrics.incidentDeliverySnapshot().channels()) + ","
                + "\"recovery\":" + renderMap(Map.ofEntries(
                        Map.entry("replayedCount", String.valueOf(metrics.incidentDeliverySnapshot().recovery().replayedCount())),
                        Map.entry("failedReplayCount", String.valueOf(metrics.incidentDeliverySnapshot().recovery().failedReplayCount())),
                        Map.entry("deadLetterCount", String.valueOf(metrics.incidentDeliverySnapshot().recovery().deadLetterCount())),
                        Map.entry("claimedCount", String.valueOf(metrics.incidentDeliverySnapshot().recovery().claimedCount())),
                        Map.entry("lastErrorAtEpochMillis", String.valueOf(metrics.incidentDeliverySnapshot().recovery().lastErrorAtEpochMillis())),
                        Map.entry("lastErrorType", defaultString(metrics.incidentDeliverySnapshot().recovery().lastErrorType())),
                        Map.entry("lastErrorMessage", defaultString(metrics.incidentDeliverySnapshot().recovery().lastErrorMessage())),
                        Map.entry("lastErrorRootType", defaultString(metrics.incidentDeliverySnapshot().recovery().lastErrorRootType())),
                        Map.entry("lastErrorRootMessage", defaultString(metrics.incidentDeliverySnapshot().recovery().lastErrorRootMessage())),
                        Map.entry("lastErrorOrigin", defaultString(metrics.incidentDeliverySnapshot().recovery().lastErrorOrigin())),
                        Map.entry("lastErrorStackTrace", defaultString(metrics.incidentDeliverySnapshot().recovery().lastErrorStackTrace()))
                ))
                + "}"
                + "}";
    }

    private String renderHotMetrics(ReconciliationMetrics metrics) {
        return "{"
                + "\"writeBehindStreamLength\":" + metrics.writeBehindStreamLength() + ","
                + "\"deadLetterStreamLength\":" + metrics.deadLetterStreamLength() + ","
                + "\"diagnosticsStreamLength\":" + metrics.diagnosticsStreamLength() + ","
                + "\"projectionRefreshSnapshot\":" + renderProjectionRefreshSnapshot(metrics.projectionRefreshSnapshot()) + ","
                + "\"plannerStatisticsSnapshot\":" + renderMap(Map.of(
                        "learnedStatisticsKeyCount", String.valueOf(metrics.plannerStatisticsSnapshot().learnedStatisticsKeyCount())
                )) + ","
                + "\"redisGuardrailSnapshot\":" + renderMap(Map.ofEntries(
                        Map.entry("usedMemoryBytes", String.valueOf(metrics.redisGuardrailSnapshot().usedMemoryBytes())),
                        Map.entry("compactionPendingCount", String.valueOf(metrics.redisGuardrailSnapshot().compactionPendingCount())),
                        Map.entry("writeBehindBacklog", String.valueOf(metrics.redisGuardrailSnapshot().writeBehindBacklog())),
                        Map.entry("hardRejectedWriteCount", String.valueOf(metrics.redisGuardrailSnapshot().hardRejectedWriteCount())),
                        Map.entry("pressureLevel", defaultString(metrics.redisGuardrailSnapshot().pressureLevel()))
                )) + ","
                + "\"redisRuntimeProfileSnapshot\":" + renderMap(Map.of(
                        "activeProfile", defaultString(metrics.redisRuntimeProfileSnapshot().activeProfile()),
                        "lastObservedPressureLevel", defaultString(metrics.redisRuntimeProfileSnapshot().lastObservedPressureLevel())
                ))
                + "}";
    }

    private String renderHotSignalSummary() {
        List<AdminIncident> incidents = admin.incidents();
        List<AlertRouteSnapshot> routes = admin.alertRoutes();
        List<BackgroundWorkerErrorSnapshot> backgroundErrors = admin.backgroundWorkerErrors();
        MonitoringTriageSnapshot triage = admin.triage();

        long criticalIncidentCount = incidents.stream()
                .filter(item -> item.severity() == AdminIncidentSeverity.CRITICAL)
                .count();
        long warningIncidentCount = incidents.stream()
                .filter(item -> item.severity() == AdminIncidentSeverity.WARNING)
                .count();
        long deliveredCount = routes.stream().mapToLong(AlertRouteSnapshot::deliveredCount).sum();
        long failedCount = routes.stream().mapToLong(AlertRouteSnapshot::failedCount).sum();
        long droppedCount = routes.stream().mapToLong(AlertRouteSnapshot::droppedCount).sum();
        BackgroundWorkerErrorSnapshot firstRecentError = backgroundErrors.stream()
                .filter(BackgroundWorkerErrorSnapshot::recent)
                .findFirst()
                .orElse(null);

        return "{"
                + "\"incidentCount\":" + incidents.size() + ","
                + "\"criticalIncidentCount\":" + criticalIncidentCount + ","
                + "\"warningIncidentCount\":" + warningIncidentCount + ","
                + "\"alertDeliveredCount\":" + deliveredCount + ","
                + "\"alertFailedCount\":" + failedCount + ","
                + "\"alertDroppedCount\":" + droppedCount + ","
                + "\"alertRouteCount\":" + routes.size() + ","
                + "\"recentBackgroundErrorCount\":"
                + backgroundErrors.stream().filter(BackgroundWorkerErrorSnapshot::recent).count() + ","
                + "\"recentBackgroundErrorWorker\":\""
                + escapeJson(firstRecentError == null ? "" : defaultString(firstRecentError.workerName())) + "\","
                + "\"recentBackgroundErrorType\":\""
                + escapeJson(firstRecentError == null ? "" : defaultString(
                        defaultString(firstRecentError.rootErrorType()).isBlank()
                                ? firstRecentError.errorType()
                                : firstRecentError.rootErrorType()
                )) + "\","
                + "\"recentBackgroundErrorOrigin\":\""
                + escapeJson(firstRecentError == null ? "" : defaultString(firstRecentError.origin())) + "\","
                + "\"triageOverallStatus\":\"" + escapeJson(defaultString(triage.overallStatus())) + "\","
                + "\"triagePrimaryBottleneck\":\"" + escapeJson(defaultString(triage.primaryBottleneck())) + "\","
                + "\"triageSuspectedCause\":\"" + escapeJson(defaultString(triage.suspectedCause())) + "\""
                + "}";
    }

    private String renderDashboardHotSnapshot() {
        ReconciliationHealth health = admin.health();
        ReconciliationMetrics metrics = health.metrics();
        String hotMetrics = renderHotMetrics(metrics);
        String hotSignals = renderHotSignalSummary();
        return "{"
                + "\"h\":{\"status\":\"" + escapeJson(health.status().name()) + "\",\"issues\":"
                + toJsonStringArray(health.issues()) + "},"
                + "\"m\":" + hotMetrics + ","
                + "\"sig\":" + hotSignals + ","
                + "\"perf\":" + renderStoragePerformance(admin.storagePerformance())
                + "}";
    }

    private String renderDashboardHotSnapshotSafely() {
        long now = System.currentTimeMillis();
        String cachedServed = lastServedHotSnapshotJson.get();
        long cachedServedAt = lastServedHotSnapshotAtEpochMillis.get();
        if (cachedServed != null && !cachedServed.isBlank() && (now - cachedServedAt) < 1_500L) {
            return cachedServed;
        }
        try {
            String snapshot = renderDashboardHotSnapshot();
            lastGoodHotSnapshotJson.set(snapshot);
            lastGoodHotSnapshotAtEpochMillis.set(System.currentTimeMillis());
            lastHotSnapshotError.set("");
            String served = appendHotSnapshotMeta(snapshot, false, "", lastGoodHotSnapshotAtEpochMillis.get());
            lastServedHotSnapshotJson.set(served);
            lastServedHotSnapshotAtEpochMillis.set(now);
            return served;
        } catch (RuntimeException exception) {
            String reason = exception.getClass().getSimpleName() + ": " + defaultString(exception.getMessage());
            lastHotSnapshotError.set(reason);
            String cached = lastGoodHotSnapshotJson.get();
            long cachedAt = lastGoodHotSnapshotAtEpochMillis.get();
            if (cached != null && !cached.isBlank()) {
                String served = appendHotSnapshotMeta(cached, true, reason, cachedAt);
                lastServedHotSnapshotJson.set(served);
                lastServedHotSnapshotAtEpochMillis.set(now);
                return served;
            }
            String served = renderMinimalHotSnapshotFallback(reason);
            lastServedHotSnapshotJson.set(served);
            lastServedHotSnapshotAtEpochMillis.set(now);
            return served;
        }
    }

    private String appendHotSnapshotMeta(String snapshot, boolean stale, String fallbackReason, long generatedAtEpochMillis) {
        String normalized = defaultString(snapshot).trim();
        if (!normalized.endsWith("}")) {
            return normalized;
        }
        String reason = escapeJson(defaultString(fallbackReason));
        return normalized.substring(0, normalized.length() - 1)
                + ",\"meta\":{\"stale\":" + stale
                + ",\"schema\":\"hot-lite-v2\""
                + ",\"instanceId\":\"" + escapeJson(dashboardInstanceId) + "\""
                + ",\"generatedAtEpochMillis\":" + generatedAtEpochMillis
                + ",\"fallbackReason\":\"" + reason + "\"}}";
    }

    private String renderMinimalHotSnapshotFallback(String reason) {
        String issue = "Hot dashboard snapshot fallback active";
        return "{"
                + "\"h\":{\"status\":\"DEGRADED\",\"issues\":" + toJsonStringArray(List.of(issue)) + "},"
                + "\"meta\":{\"stale\":true,\"schema\":\"hot-lite-v2\",\"instanceId\":\"" + escapeJson(dashboardInstanceId) + "\",\"generatedAtEpochMillis\":" + System.currentTimeMillis()
                + ",\"fallbackReason\":\"" + escapeJson(defaultString(reason)) + "\"}"
                + "}";
    }

    private String renderStoragePerformance(StoragePerformanceSnapshot snapshot) {
        return "{"
                + "\"redisRead\":" + renderLatencyMetric(snapshot.redisRead()) + ","
                + "\"redisWrite\":" + renderLatencyMetric(snapshot.redisWrite()) + ","
                + "\"postgresRead\":" + renderLatencyMetric(snapshot.postgresRead()) + ","
                + "\"postgresWrite\":" + renderLatencyMetric(snapshot.postgresWrite()) + ","
                + "\"redisReadBreakdown\":" + renderLatencyMetricBreakdown(snapshot.redisReadBreakdown()) + ","
                + "\"redisWriteBreakdown\":" + renderLatencyMetricBreakdown(snapshot.redisWriteBreakdown()) + ","
                + "\"postgresReadBreakdown\":" + renderLatencyMetricBreakdown(snapshot.postgresReadBreakdown()) + ","
                + "\"postgresWriteBreakdown\":" + renderLatencyMetricBreakdown(snapshot.postgresWriteBreakdown())
                + "}";
    }

    private String renderLatencyMetricBreakdown(Map<String, LatencyMetricSnapshot> metrics) {
        StringBuilder builder = new StringBuilder("{\"items\":[");
        int index = 0;
        for (Map.Entry<String, LatencyMetricSnapshot> entry : metrics.entrySet()) {
            if (index++ > 0) {
                builder.append(',');
            }
            builder.append("{\"tag\":\"").append(escapeJson(entry.getKey())).append("\",")
                    .append("\"metric\":").append(renderLatencyMetric(entry.getValue()))
                    .append('}');
        }
        builder.append("]}");
        return builder.toString();
    }

    private String renderLatencyMetric(LatencyMetricSnapshot metric) {
        return "{"
                + "\"operationCount\":" + metric.operationCount() + ","
                + "\"averageMicros\":" + metric.averageMicros() + ","
                + "\"p95Micros\":" + metric.p95Micros() + ","
                + "\"p99Micros\":" + metric.p99Micros() + ","
                + "\"maxMicros\":" + metric.maxMicros() + ","
                + "\"lastMicros\":" + metric.lastMicros() + ","
                + "\"lastObservedAtEpochMillis\":" + metric.lastObservedAtEpochMillis()
                + "}";
    }

    private String renderPrometheus(ReconciliationMetrics metrics) {
        StringBuilder builder = new StringBuilder();
        appendGauge(builder, "cachedb_write_behind_stream_length", "Write-behind stream length", metrics.writeBehindStreamLength());
        appendGauge(builder, "cachedb_dead_letter_stream_length", "Dead-letter stream length", metrics.deadLetterStreamLength());
        appendGauge(builder, "cachedb_reconciliation_stream_length", "Reconciliation stream length", metrics.reconciliationStreamLength());
        appendGauge(builder, "cachedb_archive_stream_length", "Archive stream length", metrics.archiveStreamLength());
        appendGauge(builder, "cachedb_diagnostics_stream_length", "Diagnostics stream length", metrics.diagnosticsStreamLength());
        appendGauge(builder, "cachedb_projection_refresh_stream_length", "Projection refresh stream length", metrics.projectionRefreshSnapshot().streamLength());
        appendGauge(builder, "cachedb_projection_refresh_dead_letter_stream_length", "Projection refresh dead-letter stream length", metrics.projectionRefreshSnapshot().deadLetterStreamLength());
        appendGauge(builder, "cachedb_projection_refresh_processed_total", "Processed projection refresh events", metrics.projectionRefreshSnapshot().processedCount());
        appendGauge(builder, "cachedb_projection_refresh_retried_total", "Retried projection refresh events", metrics.projectionRefreshSnapshot().retriedCount());
        appendGauge(builder, "cachedb_projection_refresh_failed_total", "Failed projection refresh events moved to dead-letter", metrics.projectionRefreshSnapshot().failedCount());
        appendGauge(builder, "cachedb_projection_refresh_replayed_total", "Replayed projection refresh dead-letter events", metrics.projectionRefreshSnapshot().replayedCount());
        appendGauge(builder, "cachedb_projection_refresh_claimed_total", "Claimed projection refresh pending events", metrics.projectionRefreshSnapshot().claimedCount());
        appendGauge(builder, "cachedb_projection_refresh_pending_total", "Pending projection refresh events", metrics.projectionRefreshSnapshot().pendingCount());
        appendGauge(builder, "cachedb_write_behind_flushed_total", "Flushed write-behind operations", metrics.writeBehindWorkerSnapshot().flushedCount());
        appendGauge(builder, "cachedb_write_behind_batch_flush_total", "Batch flush executions", metrics.writeBehindWorkerSnapshot().batchFlushCount());
        appendGauge(builder, "cachedb_write_behind_batch_flushed_operations_total", "Operations flushed through batch executions", metrics.writeBehindWorkerSnapshot().batchFlushedOperationCount());
        appendGauge(builder, "cachedb_write_behind_coalesced_total", "Coalesced write operations", metrics.writeBehindWorkerSnapshot().coalescedCount());
        appendGauge(builder, "cachedb_write_behind_dead_letter_total", "Dead-lettered write operations", metrics.writeBehindWorkerSnapshot().deadLetterCount());
        appendGauge(builder, "cachedb_write_behind_claimed_total", "Claimed write-behind entries", metrics.writeBehindWorkerSnapshot().claimedCount());
        appendGauge(builder, "cachedb_dead_letter_replayed_total", "Replayed dead-letter entries", metrics.deadLetterRecoverySnapshot().replayedCount());
        appendGauge(builder, "cachedb_dead_letter_failed_total", "Failed dead-letter replays", metrics.deadLetterRecoverySnapshot().failedCount());
        appendGauge(builder, "cachedb_dead_letter_claimed_total", "Claimed dead-letter entries", metrics.deadLetterRecoverySnapshot().claimedCount());
        appendGauge(builder, "cachedb_planner_estimate_key_count", "Planner estimate keys", metrics.plannerStatisticsSnapshot().estimateKeyCount());
        appendGauge(builder, "cachedb_planner_histogram_key_count", "Planner histogram keys", metrics.plannerStatisticsSnapshot().histogramKeyCount());
        appendGauge(builder, "cachedb_planner_learned_statistics_key_count", "Planner learned statistics keys", metrics.plannerStatisticsSnapshot().learnedStatisticsKeyCount());
        appendGauge(builder, "cachedb_redis_used_memory_bytes", "Redis used memory bytes", metrics.redisGuardrailSnapshot().usedMemoryBytes());
        appendGauge(builder, "cachedb_redis_used_memory_peak_bytes", "Redis used memory peak bytes", metrics.redisGuardrailSnapshot().usedMemoryPeakBytes());
        appendGauge(builder, "cachedb_redis_max_memory_bytes", "Redis max memory bytes", metrics.redisGuardrailSnapshot().maxMemoryBytes());
        appendGauge(builder, "cachedb_redis_compaction_pending_count", "Redis compaction pending count", metrics.redisGuardrailSnapshot().compactionPendingCount());
        appendGauge(builder, "cachedb_redis_compaction_payload_count", "Redis compaction payload count", metrics.redisGuardrailSnapshot().compactionPayloadCount());
        appendGauge(builder, "cachedb_redis_hard_rejected_writes_total", "Redis hard rejected writes", metrics.redisGuardrailSnapshot().hardRejectedWriteCount());
        appendGauge(builder, "cachedb_redis_producer_high_pressure_delay_total", "Producer high-pressure delay count", metrics.redisGuardrailSnapshot().producerHighPressureDelayCount());
        appendGauge(builder, "cachedb_redis_producer_critical_pressure_delay_total", "Producer critical-pressure delay count", metrics.redisGuardrailSnapshot().producerCriticalPressureDelayCount());
        appendGauge(builder, "cachedb_runtime_profile_switch_total", "Runtime profile switches", metrics.redisRuntimeProfileSnapshot().switchCount());
        appendInfo(builder, "cachedb_runtime_profile_info", Map.of(
                "profile", defaultString(metrics.redisRuntimeProfileSnapshot().activeProfile()),
                "pressure", defaultString(metrics.redisRuntimeProfileSnapshot().lastObservedPressureLevel())
        ));
        return builder.toString();
    }

    private void appendGauge(StringBuilder builder, String metricName, String help, long value) {
        builder.append("# HELP ").append(metricName).append(' ').append(help).append('\n');
        builder.append("# TYPE ").append(metricName).append(" gauge\n");
        builder.append(metricName).append(' ').append(value).append('\n');
    }

    private void appendInfo(StringBuilder builder, String metricName, Map<String, String> labels) {
        builder.append("# HELP ").append(metricName).append(" Runtime profile information\n");
        builder.append("# TYPE ").append(metricName).append(" gauge\n");
        builder.append(metricName).append('{');
        int index = 0;
        for (Map.Entry<String, String> entry : labels.entrySet()) {
            if (index++ > 0) {
                builder.append(',');
            }
            builder.append(entry.getKey()).append("=\"").append(escapeJson(defaultString(entry.getValue()))).append('"');
        }
        builder.append("} 1\n");
    }

    private String renderChannelSnapshots(List<com.reactor.cachedb.core.queue.IncidentChannelSnapshot> channels) {
        StringBuilder builder = new StringBuilder("[");
        for (int index = 0; index < channels.size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            com.reactor.cachedb.core.queue.IncidentChannelSnapshot channel = channels.get(index);
            builder.append("{\"channelName\":\"").append(escapeJson(channel.channelName())).append("\",")
                    .append("\"deliveredCount\":").append(channel.deliveredCount()).append(",")
                    .append("\"failedCount\":").append(channel.failedCount()).append(",")
                    .append("\"droppedCount\":").append(channel.droppedCount()).append(",")
                    .append("\"lastDeliveredAtEpochMillis\":").append(channel.lastDeliveredAtEpochMillis()).append(",")
                    .append("\"lastErrorAtEpochMillis\":").append(channel.lastErrorAtEpochMillis()).append(",")
                    .append("\"lastErrorType\":\"").append(escapeJson(defaultString(channel.lastErrorType()))).append("\",")
                    .append("\"lastErrorMessage\":\"").append(escapeJson(defaultString(channel.lastErrorMessage()))).append("\"}");
        }
        builder.append(']');
        return builder.toString();
    }

    private String renderDiagnostics(List<AdminDiagnosticsRecord> diagnostics) {
        StringBuilder builder = new StringBuilder("{\"items\":[");
        for (int index = 0; index < diagnostics.size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            AdminDiagnosticsRecord record = diagnostics.get(index);
            builder.append("{\"entryId\":\"").append(escapeJson(record.entryId())).append("\",")
                    .append("\"source\":\"").append(escapeJson(record.source())).append("\",")
                    .append("\"note\":\"").append(escapeJson(record.note())).append("\",")
                    .append("\"status\":\"").append(record.status().name()).append("\",")
                    .append("\"recordedAt\":\"").append(record.recordedAt()).append("\",")
                    .append("\"fields\":").append(renderMap(record.fields())).append("}");
        }
        builder.append("]}");
        return builder.toString();
    }

    private String renderProjectionRefreshSnapshot(ProjectionRefreshSnapshot snapshot) {
        return renderMap(projectionRefreshSnapshotMap(snapshot));
    }

    private String renderProjectionRefreshFailures(List<ProjectionRefreshFailureEntry> items) {
        StringBuilder builder = new StringBuilder("{\"items\":[");
        for (int index = 0; index < items.size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            ProjectionRefreshFailureEntry item = items.get(index);
            builder.append("{\"entryId\":\"").append(escapeJson(item.entryId())).append("\",")
                    .append("\"originalEntryId\":\"").append(escapeJson(item.originalEntryId())).append("\",")
                    .append("\"entityName\":\"").append(escapeJson(item.entityName())).append("\",")
                    .append("\"projectionName\":\"").append(escapeJson(item.projectionName())).append("\",")
                    .append("\"rawId\":\"").append(escapeJson(item.rawId())).append("\",")
                    .append("\"operation\":\"").append(escapeJson(item.operation())).append("\",")
                    .append("\"attempt\":").append(item.attempt()).append(",")
                    .append("\"failedAtEpochMillis\":").append(item.failedAtEpochMillis()).append(",")
                    .append("\"errorType\":\"").append(escapeJson(item.errorType())).append("\",")
                    .append("\"errorMessage\":\"").append(escapeJson(item.errorMessage())).append("\",")
                    .append("\"errorRootType\":\"").append(escapeJson(item.errorRootType())).append("\",")
                    .append("\"errorRootMessage\":\"").append(escapeJson(item.errorRootMessage())).append("\",")
                    .append("\"errorOrigin\":\"").append(escapeJson(item.errorOrigin())).append("\"}");
        }
        builder.append("]}");
        return builder.toString();
    }

    private String renderProjectionRefreshReplayResult(ProjectionRefreshReplayResult result) {
        return "{"
                + "\"entryId\":\"" + escapeJson(result.entryId()) + "\","
                + "\"replayed\":" + result.replayed() + ","
                + "\"enqueuedEntryId\":\"" + escapeJson(result.enqueuedEntryId()) + "\","
                + "\"message\":\"" + escapeJson(result.message()) + "\""
                + "}";
    }

    private String renderExplainNoteResult(AdminDiagnosticsRecord record, String title) {
        return "{\"entryId\":\"" + escapeJson(record.entryId()) + "\","
                + "\"source\":\"" + escapeJson(record.source()) + "\","
                + "\"title\":\"" + escapeJson(title) + "\","
                + "\"status\":\"" + record.status().name() + "\","
                + "\"recordedAt\":\"" + escapeJson(record.recordedAt().toString()) + "\"}";
    }

    private String renderProfileChurn(List<RuntimeProfileChurnRecord> churn) {
        StringBuilder builder = new StringBuilder("{\"items\":[");
        for (int index = 0; index < churn.size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            RuntimeProfileChurnRecord record = churn.get(index);
            builder.append("{\"entryId\":\"").append(escapeJson(record.entryId())).append("\",")
                    .append("\"source\":\"").append(escapeJson(record.source())).append("\",")
                    .append("\"note\":\"").append(escapeJson(record.note())).append("\",")
                    .append("\"fromProfile\":\"").append(escapeJson(record.fromProfile())).append("\",")
                    .append("\"toProfile\":\"").append(escapeJson(record.toProfile())).append("\",")
                    .append("\"pressureLevel\":\"").append(escapeJson(record.pressureLevel())).append("\",")
                    .append("\"recordedAt\":\"").append(record.recordedAt()).append("\",")
                    .append("\"fields\":").append(renderMap(record.fields())).append("}");
        }
        builder.append("]}");
        return builder.toString();
    }

    private String renderCurrentIncidents(List<AdminIncident> incidents) {
        StringBuilder builder = new StringBuilder("{\"items\":[");
        for (int index = 0; index < incidents.size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            AdminIncident incident = incidents.get(index);
            builder.append("{\"code\":\"").append(escapeJson(incident.code())).append("\",")
                    .append("\"description\":\"").append(escapeJson(incident.description())).append("\",")
                    .append("\"severity\":\"").append(incident.severity().name()).append("\",")
                    .append("\"detectedAt\":\"").append(incident.detectedAt()).append("\",")
                    .append("\"fields\":").append(renderMap(incident.fields())).append("}");
        }
        builder.append("]}");
        return builder.toString();
    }

    private String renderQueryIndexRebuildResult(com.reactor.cachedb.core.queue.QueryIndexRebuildResult result) {
        return "{\"entityName\":\"" + escapeJson(result.entityName()) + "\","
                + "\"namespace\":\"" + escapeJson(result.namespace()) + "\","
                + "\"rebuiltEntityCount\":" + result.rebuiltEntityCount() + ","
                + "\"clearedKeyCount\":" + result.clearedKeyCount() + ","
                + "\"degradedCleared\":" + result.degradedCleared() + ","
                + "\"automatic\":" + result.automatic() + ","
                + "\"recordedAt\":\"" + result.recordedAt() + "\","
                + "\"note\":\"" + escapeJson(result.note()) + "\"}";
    }

    private String renderSchemaMigrationPlan(SchemaMigrationPlan plan, boolean applied) {
        StringBuilder builder = new StringBuilder("{\"applied\":")
                .append(applied)
                .append(",\"tableCount\":").append(plan.tableCount())
                .append(",\"stepCount\":").append(plan.stepCount())
                .append(",\"recordedAt\":\"").append(plan.recordedAt()).append("\",\"steps\":[");
        for (int index = 0; index < plan.steps().size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            SchemaMigrationStep step = plan.steps().get(index);
            builder.append("{\"entityName\":\"").append(escapeJson(step.entityName())).append("\",")
                    .append("\"tableName\":\"").append(escapeJson(step.tableName())).append("\",")
                    .append("\"reason\":\"").append(escapeJson(step.reason())).append("\",")
                    .append("\"sql\":\"").append(escapeJson(step.sql())).append("\"}");
        }
        builder.append("]}");
        return builder.toString();
    }

    private String renderSchemaMigrationHistory(List<SchemaMigrationHistoryEntry> entries) {
        StringBuilder builder = new StringBuilder("{\"items\":[");
        for (int index = 0; index < entries.size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            SchemaMigrationHistoryEntry entry = entries.get(index);
            builder.append("{\"operation\":\"").append(escapeJson(entry.operation())).append("\",")
                    .append("\"applied\":").append(entry.applied()).append(',')
                    .append("\"success\":").append(entry.success()).append(',')
                    .append("\"stepCount\":").append(entry.stepCount()).append(',')
                    .append("\"executedStepCount\":").append(entry.executedStepCount()).append(',')
                    .append("\"failureMessage\":\"").append(escapeJson(entry.failureMessage())).append("\",")
                    .append("\"recordedAt\":\"").append(entry.recordedAt()).append("\",")
                    .append("\"steps\":[");
            for (int stepIndex = 0; stepIndex < entry.steps().size(); stepIndex++) {
                if (stepIndex > 0) {
                    builder.append(',');
                }
                SchemaMigrationStep step = entry.steps().get(stepIndex);
                builder.append("{\"entityName\":\"").append(escapeJson(step.entityName())).append("\",")
                        .append("\"tableName\":\"").append(escapeJson(step.tableName())).append("\",")
                        .append("\"reason\":\"").append(escapeJson(step.reason())).append("\",")
                        .append("\"sql\":\"").append(escapeJson(step.sql())).append("\"}");
            }
            builder.append("]}");
        }
        builder.append("]}");
        return builder.toString();
    }

    private String renderSchemaDdl(Map<String, String> ddl) {
        StringBuilder builder = new StringBuilder("{\"items\":[");
        int index = 0;
        for (Map.Entry<String, String> entry : ddl.entrySet()) {
            if (index++ > 0) {
                builder.append(',');
            }
            builder.append("{\"entityName\":\"").append(escapeJson(entry.getKey())).append("\",")
                    .append("\"sql\":\"").append(escapeJson(entry.getValue())).append("\"}");
        }
        builder.append("]}");
        return builder.toString();
    }

    private String renderStarterProfiles(List<StarterProfileSnapshot> profiles) {
        StringBuilder builder = new StringBuilder("{\"items\":[");
        for (int index = 0; index < profiles.size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            StarterProfileSnapshot profile = profiles.get(index);
            builder.append("{\"name\":\"").append(escapeJson(profile.name())).append("\",")
                    .append("\"adminEnabled\":").append(profile.adminEnabled()).append(',')
                    .append("\"dashboardEnabled\":").append(profile.dashboardEnabled()).append(',')
                    .append("\"writeBehindEnabled\":").append(profile.writeBehindEnabled()).append(',')
                    .append("\"redisGuardrailsEnabled\":").append(profile.redisGuardrailsEnabled()).append(',')
                    .append("\"schemaBootstrapMode\":\"").append(escapeJson(profile.schemaBootstrapMode())).append("\",")
                    .append("\"schemaAutoApplyOnStart\":").append(profile.schemaAutoApplyOnStart()).append(',')
                    .append("\"note\":\"").append(escapeJson(profile.note())).append("\"}");
        }
        builder.append("]}");
        return builder.toString();
    }

    private String renderEffectiveTuning(EffectiveTuningSnapshot snapshot) {
        StringBuilder builder = new StringBuilder("{\"capturedAt\":\"")
                .append(snapshot.capturedAt())
                .append("\",\"explicitOverrideCount\":")
                .append(snapshot.explicitOverrideCount())
                .append(",\"entryCount\":")
                .append(snapshot.entryCount())
                .append(",\"items\":[");
        for (int index = 0; index < snapshot.items().size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            EffectiveTuningEntry entry = snapshot.items().get(index);
            builder.append("{\"group\":\"").append(escapeJson(entry.group())).append("\",")
                    .append("\"property\":\"").append(escapeJson(entry.property())).append("\",")
                    .append("\"value\":\"").append(escapeJson(entry.value())).append("\",")
                    .append("\"source\":\"").append(escapeJson(entry.source())).append("\",")
                    .append("\"description\":\"").append(escapeJson(entry.description())).append("\"}");
        }
        builder.append("]}");
        return builder.toString();
    }

    private String renderEffectiveTuningMarkdown(EffectiveTuningSnapshot snapshot) {
        StringBuilder builder = new StringBuilder();
        builder.append("# Current Effective Tuning\n\n");
        builder.append("- Captured at: ").append(snapshot.capturedAt()).append('\n');
        builder.append("- Explicit overrides: ").append(snapshot.explicitOverrideCount()).append('\n');
        builder.append("- Visible entries: ").append(snapshot.entryCount()).append("\n\n");
        builder.append("| Group | Property | Value | Source | Description |\n");
        builder.append("| --- | --- | --- | --- | --- |\n");
        for (EffectiveTuningEntry entry : snapshot.items()) {
            builder.append("| ")
                    .append(markdownEscape(entry.group())).append(" | ")
                    .append(markdownEscape(entry.property())).append(" | ")
                    .append(markdownEscape(entry.value())).append(" | ")
                    .append(markdownEscape(entry.source())).append(" | ")
                    .append(markdownEscape(entry.description())).append(" |\n");
        }
        return builder.toString();
    }

    private String renderEffectiveTuningFlags(EffectiveTuningSnapshot snapshot) {
        StringBuilder builder = new StringBuilder();
        for (EffectiveTuningEntry entry : snapshot.items()) {
            builder.append("-D")
                    .append(entry.property())
                    .append("=")
                    .append(shellEscape(entry.value()))
                    .append('\n');
        }
        return builder.toString();
    }

    private String renderRuntimeProfileDetails(RuntimeProfileDetailsSnapshot snapshot) {
        StringBuilder builder = new StringBuilder("{");
        builder.append("\"activeProfile\":\"").append(escapeJson(defaultString(snapshot.activeProfile()))).append("\",")
                .append("\"mode\":\"").append(escapeJson(defaultString(snapshot.mode()))).append("\",")
                .append("\"automaticSwitchingEnabled\":").append(snapshot.automaticSwitchingEnabled()).append(',')
                .append("\"manualOverrideProfile\":\"").append(escapeJson(defaultString(snapshot.manualOverrideProfile()))).append("\",")
                .append("\"lastObservedPressureLevel\":\"").append(escapeJson(defaultString(snapshot.lastObservedPressureLevel()))).append("\",")
                .append("\"switchCount\":").append(snapshot.switchCount()).append(',')
                .append("\"lastSwitchedAtEpochMillis\":").append(snapshot.lastSwitchedAtEpochMillis()).append(',')
                .append("\"properties\":[");
        for (int index = 0; index < snapshot.properties().size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            RuntimeProfilePropertySnapshot property = snapshot.properties().get(index);
            builder.append("{\"property\":\"").append(escapeJson(property.property())).append("\",")
                    .append("\"value\":\"").append(escapeJson(defaultString(property.value()))).append("\",")
                    .append("\"unit\":\"").append(escapeJson(defaultString(property.unit()))).append("\",")
                    .append("\"description\":\"").append(escapeJson(defaultString(property.description()))).append("\"}");
        }
        builder.append("]}");
        return builder.toString();
    }

    private String renderMonitoringTriage(MonitoringTriageSnapshot triage) {
        return "{\"overallStatus\":\"" + escapeJson(triage.overallStatus()) + "\","
                + "\"primaryBottleneck\":\"" + escapeJson(triage.primaryBottleneck()) + "\","
                + "\"suspectedCause\":\"" + escapeJson(triage.suspectedCause()) + "\","
                + "\"evidence\":" + toJsonStringArray(triage.evidence()) + ","
                + "\"recordedAt\":\"" + triage.recordedAt() + "\"}";
    }

    private String renderMonitoringServices(List<MonitoringServiceSnapshot> services) {
        StringBuilder builder = new StringBuilder("{\"items\":[");
        for (int index = 0; index < services.size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            MonitoringServiceSnapshot service = services.get(index);
            builder.append("{\"serviceName\":\"").append(escapeJson(service.serviceName())).append("\",")
                    .append("\"status\":\"").append(escapeJson(service.status())).append("\",")
                    .append("\"keySignal\":\"").append(escapeJson(service.keySignal())).append("\",")
                    .append("\"detail\":\"").append(escapeJson(service.detail())).append("\"}");
        }
        builder.append("]}");
        return builder.toString();
    }

    private String renderBackgroundWorkerErrors(List<BackgroundWorkerErrorSnapshot> errors) {
        StringBuilder builder = new StringBuilder("{\"items\":[");
        for (int index = 0; index < errors.size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            BackgroundWorkerErrorSnapshot error = errors.get(index);
            builder.append("{\"workerName\":\"").append(escapeJson(error.workerName())).append("\",")
                    .append("\"serviceName\":\"").append(escapeJson(error.serviceName())).append("\",")
                    .append("\"status\":\"").append(escapeJson(error.status())).append("\",")
                    .append("\"recent\":").append(error.recent()).append(',')
                    .append("\"lastErrorAtEpochMillis\":").append(error.lastErrorAtEpochMillis()).append(',')
                    .append("\"errorType\":\"").append(escapeJson(error.errorType())).append("\",")
                    .append("\"rootErrorType\":\"").append(escapeJson(error.rootErrorType())).append("\",")
                    .append("\"errorMessage\":\"").append(escapeJson(error.errorMessage())).append("\",")
                    .append("\"rootErrorMessage\":\"").append(escapeJson(error.rootErrorMessage())).append("\",")
                    .append("\"origin\":\"").append(escapeJson(error.origin())).append("\",")
                    .append("\"stackTrace\":\"").append(escapeJson(error.stackTrace())).append("\"}");
        }
        builder.append("]}");
        return builder.toString();
    }

    private String renderAlertRoutes(List<AlertRouteSnapshot> routes) {
        StringBuilder builder = new StringBuilder("{\"items\":[");
        for (int index = 0; index < routes.size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            AlertRouteSnapshot route = routes.get(index);
            builder.append("{\"routeName\":\"").append(escapeJson(route.routeName())).append("\",")
                    .append("\"enabled\":").append(route.enabled()).append(',')
                    .append("\"target\":\"").append(escapeJson(route.target())).append("\",")
                    .append("\"deliveryMode\":\"").append(escapeJson(route.deliveryMode())).append("\",")
                    .append("\"escalationPolicy\":\"").append(escapeJson(route.escalationPolicy())).append("\",")
                    .append("\"escalationLevel\":\"").append(escapeJson(route.escalationLevel())).append("\",")
                    .append("\"triggerScope\":\"").append(escapeJson(route.triggerScope())).append("\",")
                    .append("\"retryPolicy\":\"").append(escapeJson(route.retryPolicy())).append("\",")
                    .append("\"fallbackRoute\":\"").append(escapeJson(route.fallbackRoute())).append("\",")
                    .append("\"status\":\"").append(escapeJson(route.status())).append("\",")
                    .append("\"deliveredCount\":").append(route.deliveredCount()).append(',')
                    .append("\"failedCount\":").append(route.failedCount()).append(',')
                    .append("\"droppedCount\":").append(route.droppedCount()).append(',')
                    .append("\"lastDeliveredAtEpochMillis\":").append(route.lastDeliveredAtEpochMillis()).append(',')
                    .append("\"lastErrorAtEpochMillis\":").append(route.lastErrorAtEpochMillis()).append(',')
                    .append("\"lastErrorType\":\"").append(escapeJson(route.lastErrorType())).append("\"}");
        }
        builder.append("]}");
        return builder.toString();
    }

    private String renderRunbooks(List<RunbookSnapshot> runbooks) {
        StringBuilder builder = new StringBuilder("{\"items\":[");
        for (int index = 0; index < runbooks.size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            RunbookSnapshot runbook = runbooks.get(index);
            builder.append("{\"code\":\"").append(escapeJson(runbook.code())).append("\",")
                    .append("\"title\":\"").append(escapeJson(runbook.title())).append("\",")
                    .append("\"trigger\":\"").append(escapeJson(runbook.trigger())).append("\",")
                    .append("\"firstAction\":\"").append(escapeJson(runbook.firstAction())).append("\",")
                    .append("\"reference\":\"").append(escapeJson(runbook.reference())).append("\"}");
        }
        builder.append("]}");
        return builder.toString();
    }

    private String renderMonitoringHistory(List<MonitoringHistoryPoint> points) {
        StringBuilder builder = new StringBuilder("{\"items\":[");
        for (int index = 0; index < points.size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            MonitoringHistoryPoint point = points.get(index);
            builder.append("{\"recordedAt\":\"").append(point.recordedAt()).append("\",")
                    .append("\"writeBehindBacklog\":").append(point.writeBehindBacklog()).append(',')
                    .append("\"deadLetterBacklog\":").append(point.deadLetterBacklog()).append(',')
                    .append("\"redisUsedMemoryBytes\":").append(point.redisUsedMemoryBytes()).append(',')
                    .append("\"compactionPendingCount\":").append(point.compactionPendingCount()).append(',')
                    .append("\"runtimeProfile\":\"").append(escapeJson(point.runtimeProfile())).append("\",")
                    .append("\"healthStatus\":\"").append(escapeJson(point.healthStatus())).append("\"}");
        }
        builder.append("]}");
        return builder.toString();
    }

    private String renderAlertRouteHistory(List<AlertRouteHistoryPoint> points) {
        StringBuilder builder = new StringBuilder("{\"items\":[");
        for (int index = 0; index < points.size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            AlertRouteHistoryPoint point = points.get(index);
            builder.append("{\"recordedAt\":\"").append(point.recordedAt()).append("\",")
                    .append("\"routeName\":\"").append(escapeJson(point.routeName())).append("\",")
                    .append("\"status\":\"").append(escapeJson(point.status())).append("\",")
                    .append("\"escalationLevel\":\"").append(escapeJson(point.escalationLevel())).append("\",")
                    .append("\"deliveredCount\":").append(point.deliveredCount()).append(',')
                    .append("\"failedCount\":").append(point.failedCount()).append(',')
                    .append("\"droppedCount\":").append(point.droppedCount()).append(',')
                    .append("\"lastErrorType\":\"").append(escapeJson(point.lastErrorType())).append("\"}");
        }
        builder.append("]}");
        return builder.toString();
    }

    private String renderTelemetryReset(AdminTelemetryResetSnapshot snapshot) {
        return "{\"diagnosticsEntriesCleared\":" + snapshot.diagnosticsEntriesCleared()
                + ",\"incidentEntriesCleared\":" + snapshot.incidentEntriesCleared()
                + ",\"monitoringHistorySamplesCleared\":" + snapshot.monitoringHistorySamplesCleared()
                + ",\"alertRouteHistorySamplesCleared\":" + snapshot.alertRouteHistorySamplesCleared()
                + ",\"storagePerformanceOperationsCleared\":" + snapshot.storagePerformanceOperationsCleared()
                + ",\"resetAt\":\"" + snapshot.resetAt() + "\"}";
    }

    private String renderPerformanceHistory(List<PerformanceHistoryPoint> points) {
        StringBuilder builder = new StringBuilder("{\"items\":[");
        for (int index = 0; index < points.size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            PerformanceHistoryPoint point = points.get(index);
            builder.append("{\"recordedAt\":\"").append(point.recordedAt()).append("\",")
                    .append("\"redisReadOperationCount\":").append(point.redisReadOperationCount()).append(',')
                    .append("\"redisReadAverageMicros\":").append(point.redisReadAverageMicros()).append(',')
                    .append("\"redisWriteOperationCount\":").append(point.redisWriteOperationCount()).append(',')
                    .append("\"redisWriteAverageMicros\":").append(point.redisWriteAverageMicros()).append(',')
                    .append("\"postgresReadOperationCount\":").append(point.postgresReadOperationCount()).append(',')
                    .append("\"postgresReadAverageMicros\":").append(point.postgresReadAverageMicros()).append(',')
                    .append("\"postgresWriteOperationCount\":").append(point.postgresWriteOperationCount()).append(',')
                    .append("\"postgresWriteAverageMicros\":").append(point.postgresWriteAverageMicros()).append('}');
        }
        builder.append("]}");
        return builder.toString();
    }

    private String renderPerformanceReset(PerformanceTelemetryResetSnapshot snapshot) {
        return "{\"storagePerformanceOperationsCleared\":" + snapshot.storagePerformanceOperationsCleared()
                + ",\"performanceHistorySamplesCleared\":" + snapshot.performanceHistorySamplesCleared()
                + ",\"resetAt\":\"" + snapshot.resetAt() + "\"}";
    }

    private String renderDeploymentStatus(DeploymentStatusSnapshot deployment) {
        StringBuilder builder = new StringBuilder("{")
                .append("\"adminHttpEnabled\":").append(deployment.adminHttpEnabled()).append(',')
                .append("\"dashboardEnabled\":").append(deployment.dashboardEnabled()).append(',')
                .append("\"writeBehindEnabled\":").append(deployment.writeBehindEnabled()).append(',')
                .append("\"writeBehindWorkerThreads\":").append(deployment.writeBehindWorkerThreads()).append(',')
                .append("\"activeWriteStreamCount\":").append(deployment.activeWriteStreamCount()).append(',')
                .append("\"dedicatedWriteConsumerGroupEnabled\":").append(deployment.dedicatedWriteConsumerGroupEnabled()).append(',')
                .append("\"durableCompactionEnabled\":").append(deployment.durableCompactionEnabled()).append(',')
                .append("\"postgresCopyBulkLoadEnabled\":").append(deployment.postgresCopyBulkLoadEnabled()).append(',')
                .append("\"postgresMultiRowFlushEnabled\":").append(deployment.postgresMultiRowFlushEnabled()).append(',')
                .append("\"redisGuardrailsEnabled\":").append(deployment.redisGuardrailsEnabled()).append(',')
                .append("\"automaticRuntimeProfileSwitchingEnabled\":").append(deployment.automaticRuntimeProfileSwitchingEnabled()).append(',')
                .append("\"schemaBootstrapMode\":\"").append(escapeJson(deployment.schemaBootstrapMode())).append("\",")
                .append("\"schemaAutoApplyOnStart\":").append(deployment.schemaAutoApplyOnStart()).append(',')
                .append("\"queryPlannerLearningEnabled\":").append(deployment.queryPlannerLearningEnabled()).append(',')
                .append("\"queryPlannerPersistenceEnabled\":").append(deployment.queryPlannerPersistenceEnabled()).append(',')
                .append("\"keyPrefix\":\"").append(escapeJson(deployment.keyPrefix())).append("\",")
                .append("\"recordedAt\":\"").append(deployment.recordedAt()).append("\",")
                .append("\"activeStreamKeys\":").append(toJsonStringArray(deployment.activeStreamKeys()))
                .append("}");
        return builder.toString();
    }

    private String renderSchemaStatus(SchemaStatusSnapshot schemaStatus) {
        return "{"
                + "\"bootstrapMode\":\"" + escapeJson(schemaStatus.bootstrapMode()) + "\","
                + "\"autoApplyOnStart\":" + schemaStatus.autoApplyOnStart() + ","
                + "\"includeVersionColumn\":" + schemaStatus.includeVersionColumn() + ","
                + "\"includeDeletedColumn\":" + schemaStatus.includeDeletedColumn() + ","
                + "\"schemaName\":\"" + escapeJson(defaultString(schemaStatus.schemaName())) + "\","
                + "\"validationSucceeded\":" + schemaStatus.validationSucceeded() + ","
                + "\"validationIssueCount\":" + schemaStatus.validationIssueCount() + ","
                + "\"processedEntityCount\":" + schemaStatus.processedEntityCount() + ","
                + "\"validatedTableCount\":" + schemaStatus.validatedTableCount() + ","
                + "\"migrationStepCount\":" + schemaStatus.migrationStepCount() + ","
                + "\"createTableStepCount\":" + schemaStatus.createTableStepCount() + ","
                + "\"addColumnStepCount\":" + schemaStatus.addColumnStepCount() + ","
                + "\"ddlEntityCount\":" + schemaStatus.ddlEntityCount() + ","
                + "\"recordedAt\":\"" + schemaStatus.recordedAt() + "\""
                + "}";
    }

    private String renderApiProduct(ApiProductSnapshot apiProduct) {
        StringBuilder builder = new StringBuilder("{")
                .append("\"registeredEntityCount\":").append(apiProduct.registeredEntityCount()).append(',')
                .append("\"maxRegisteredEntities\":").append(apiProduct.maxRegisteredEntities()).append(',')
                .append("\"maxColumnsPerOperation\":").append(apiProduct.maxColumnsPerOperation()).append(',')
                .append("\"recordedAt\":\"").append(apiProduct.recordedAt()).append("\",")
                .append("\"entities\":[");
        for (int index = 0; index < apiProduct.entities().size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            ApiEntitySnapshot entity = apiProduct.entities().get(index);
            builder.append("{\"entityName\":\"").append(escapeJson(entity.entityName())).append("\",")
                    .append("\"tableName\":\"").append(escapeJson(entity.tableName())).append("\",")
                    .append("\"columnCount\":").append(entity.columnCount()).append(',')
                    .append("\"hotEntityLimit\":").append(entity.hotEntityLimit()).append(',')
                    .append("\"pageSize\":").append(entity.pageSize()).append(',')
                    .append("\"entityTtlSeconds\":").append(entity.entityTtlSeconds()).append(',')
                    .append("\"pageTtlSeconds\":").append(entity.pageTtlSeconds()).append(',')
                    .append("\"relationPreloadEnabled\":").append(entity.relationPreloadEnabled()).append(',')
                    .append("\"pageLoaderEnabled\":").append(entity.pageLoaderEnabled()).append('}');
        }
        builder.append("]}");
        return builder.toString();
    }

    private String renderMigrationPlannerTemplate(MigrationPlanner.Template template) {
        return "{"
                + "\"entityOptions\":" + renderMigrationPlannerEntityOptions(template.entityOptions()) + ","
                + "\"defaults\":" + renderMigrationPlannerRequest(template.defaults())
                + "}";
    }

    private String renderMigrationPlannerDemoDescriptor(MigrationPlannerDemoSupport.Descriptor descriptor) {
        return "{"
                + "\"available\":" + descriptor.available() + ","
                + "\"title\":\"" + escapeJson(descriptor.title()) + "\","
                + "\"summary\":\"" + escapeJson(descriptor.summary()) + "\","
                + "\"defaultCustomerCount\":" + descriptor.defaultCustomerCount() + ","
                + "\"defaultHotCustomerCount\":" + descriptor.defaultHotCustomerCount() + ","
                + "\"defaultMaxOrdersPerCustomer\":" + descriptor.defaultMaxOrdersPerCustomer() + ","
                + "\"plannerDefaults\":" + renderMigrationPlannerRequest(descriptor.plannerDefaults())
                + "}";
    }

    private String renderMigrationPlannerDemoBootstrapResult(MigrationPlannerDemoSupport.BootstrapResult result) {
        return "{"
                + "\"rootSurface\":\"" + escapeJson(result.rootSurface()) + "\","
                + "\"childSurface\":\"" + escapeJson(result.childSurface()) + "\","
                + "\"rootTable\":\"" + escapeJson(result.rootTable()) + "\","
                + "\"childTable\":\"" + escapeJson(result.childTable()) + "\","
                + "\"viewNames\":" + toJsonStringArray(result.viewNames()) + ","
                + "\"customerCount\":" + result.customerCount() + ","
                + "\"orderCount\":" + result.orderCount() + ","
                + "\"hottestCustomerOrderCount\":" + result.hottestCustomerOrderCount() + ","
                + "\"sampleRootIds\":" + toJsonStringArray(result.sampleRootIds()) + ","
                + "\"notes\":" + toJsonStringArray(result.notes()) + ","
                + "\"plannerDefaults\":" + renderMigrationPlannerRequest(result.plannerDefaults())
                + "}";
    }

    private String renderMigrationSchemaDiscovery(MigrationSchemaDiscovery.Result result) {
        return "{"
                + "\"discoveredAt\":\"" + result.discoveredAt() + "\","
                + "\"tableCount\":" + result.tables().size() + ","
                + "\"routeSuggestionCount\":" + result.routeSuggestions().size() + ","
                + "\"warnings\":" + toJsonStringArray(result.warnings()) + ","
                + "\"tables\":" + renderMigrationSchemaTables(result.tables()) + ","
                + "\"routeSuggestions\":" + renderMigrationRouteSuggestions(result.routeSuggestions())
                + "}";
    }

    private String renderMigrationSchemaTables(List<MigrationSchemaDiscovery.TableInfo> tables) {
        StringBuilder builder = new StringBuilder("[");
        for (int index = 0; index < tables.size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            MigrationSchemaDiscovery.TableInfo table = tables.get(index);
            builder.append("{\"schemaName\":\"").append(escapeJson(table.schemaName())).append("\",")
                    .append("\"tableName\":\"").append(escapeJson(table.tableName())).append("\",")
                    .append("\"objectType\":\"").append(escapeJson(table.objectType())).append("\",")
                    .append("\"qualifiedTableName\":\"").append(escapeJson(table.qualifiedTableName())).append("\",")
                    .append("\"registeredEntityName\":\"").append(escapeJson(table.registeredEntityName())).append("\",")
                    .append("\"primaryKeyColumn\":\"").append(escapeJson(table.primaryKeyColumn())).append("\",")
                    .append("\"columnCount\":").append(table.columnCount()).append(',')
                    .append("\"importedKeyCount\":").append(table.importedKeyCount()).append(',')
                    .append("\"temporalColumns\":").append(toJsonStringArray(table.temporalColumns())).append(',')
                    .append("\"foreignKeyColumns\":").append(toJsonStringArray(table.foreignKeyColumns())).append(',')
                    .append("\"columns\":").append(renderMigrationSchemaColumns(table.columns()))
                    .append("}");
        }
        builder.append(']');
        return builder.toString();
    }

    private String renderMigrationSchemaColumns(List<MigrationSchemaDiscovery.ColumnInfo> columns) {
        StringBuilder builder = new StringBuilder("[");
        for (int index = 0; index < columns.size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            MigrationSchemaDiscovery.ColumnInfo column = columns.get(index);
            builder.append("{\"name\":\"").append(escapeJson(column.name())).append("\",")
                    .append("\"jdbcTypeName\":\"").append(escapeJson(column.jdbcTypeName())).append("\",")
                    .append("\"nullable\":").append(column.nullable()).append(',')
                    .append("\"primaryKey\":").append(column.primaryKey()).append(',')
                    .append("\"foreignKey\":").append(column.foreignKey()).append(',')
                    .append("\"temporal\":").append(column.temporal()).append(',')
                    .append("\"numeric\":").append(column.numeric())
                    .append("}");
        }
        builder.append(']');
        return builder.toString();
    }

    private String renderMigrationRouteSuggestions(List<MigrationSchemaDiscovery.RouteSuggestion> suggestions) {
        StringBuilder builder = new StringBuilder("[");
        for (int index = 0; index < suggestions.size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            MigrationSchemaDiscovery.RouteSuggestion suggestion = suggestions.get(index);
            builder.append("{\"label\":\"").append(escapeJson(suggestion.label())).append("\",")
                    .append("\"summary\":\"").append(escapeJson(suggestion.summary())).append("\",")
                    .append("\"rootSurface\":\"").append(escapeJson(suggestion.rootSurface())).append("\",")
                    .append("\"rootEntityName\":\"").append(escapeJson(suggestion.rootEntityName())).append("\",")
                    .append("\"rootPrimaryKeyColumn\":\"").append(escapeJson(suggestion.rootPrimaryKeyColumn())).append("\",")
                    .append("\"childSurface\":\"").append(escapeJson(suggestion.childSurface())).append("\",")
                    .append("\"childEntityName\":\"").append(escapeJson(suggestion.childEntityName())).append("\",")
                    .append("\"childPrimaryKeyColumn\":\"").append(escapeJson(suggestion.childPrimaryKeyColumn())).append("\",")
                    .append("\"relationColumn\":\"").append(escapeJson(suggestion.relationColumn())).append("\",")
                    .append("\"sortColumn\":\"").append(escapeJson(suggestion.sortColumn())).append("\",")
                    .append("\"sortCandidates\":").append(toJsonStringArray(suggestion.sortCandidates())).append(',')
                    .append("\"rankedSortCandidate\":").append(suggestion.rankedSortCandidate()).append(',')
                    .append("\"temporalSortCandidate\":").append(suggestion.temporalSortCandidate()).append(',')
                    .append("\"plannerRequest\":").append(renderMigrationPlannerRequest(suggestion.plannerRequest()))
                    .append("}");
        }
        builder.append(']');
        return builder.toString();
    }

    private String renderMigrationPlannerEntityOptions(List<MigrationPlanner.EntityOption> options) {
        StringBuilder builder = new StringBuilder("[");
        for (int index = 0; index < options.size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            MigrationPlanner.EntityOption option = options.get(index);
            builder.append("{\"entityName\":\"").append(escapeJson(option.entityName())).append("\",")
                    .append("\"tableName\":\"").append(escapeJson(option.tableName())).append("\",")
                    .append("\"idColumn\":\"").append(escapeJson(option.idColumn())).append("\",")
                    .append("\"columns\":").append(toJsonStringArray(option.columns()))
                    .append("}");
        }
        builder.append(']');
        return builder.toString();
    }

    private String renderMigrationPlannerRequest(MigrationPlanner.Request request) {
        return "{"
                + "\"workloadName\":\"" + escapeJson(request.workloadName()) + "\","
                + "\"rootTableOrEntity\":\"" + escapeJson(request.rootTableOrEntity()) + "\","
                + "\"rootPrimaryKeyColumn\":\"" + escapeJson(request.rootPrimaryKeyColumn()) + "\","
                + "\"childTableOrEntity\":\"" + escapeJson(request.childTableOrEntity()) + "\","
                + "\"childPrimaryKeyColumn\":\"" + escapeJson(request.childPrimaryKeyColumn()) + "\","
                + "\"relationColumn\":\"" + escapeJson(request.relationColumn()) + "\","
                + "\"sortColumn\":\"" + escapeJson(request.sortColumn()) + "\","
                + "\"sortDirection\":\"" + escapeJson(request.sortDirection()) + "\","
                + "\"rootRowCount\":" + request.rootRowCount() + ","
                + "\"childRowCount\":" + request.childRowCount() + ","
                + "\"typicalChildrenPerRoot\":" + request.typicalChildrenPerRoot() + ","
                + "\"maxChildrenPerRoot\":" + request.maxChildrenPerRoot() + ","
                + "\"firstPageSize\":" + request.firstPageSize() + ","
                + "\"hotWindowPerRoot\":" + request.hotWindowPerRoot() + ","
                + "\"listScreen\":" + request.listScreen() + ","
                + "\"firstPaintNeedsFullAggregate\":" + request.firstPaintNeedsFullAggregate() + ","
                + "\"globalSortedScreen\":" + request.globalSortedScreen() + ","
                + "\"thresholdOrRangeScreen\":" + request.thresholdOrRangeScreen() + ","
                + "\"archiveHistoryRequired\":" + request.archiveHistoryRequired() + ","
                + "\"fullHistoryMustStayHot\":" + request.fullHistoryMustStayHot() + ","
                + "\"currentOrmUsesEagerLoading\":" + request.currentOrmUsesEagerLoading() + ","
                + "\"detailLookupIsHot\":" + request.detailLookupIsHot() + ","
                + "\"sideBySideComparisonRequired\":" + request.sideBySideComparisonRequired()
                + "}";
    }

    private String renderMigrationPlannerResult(MigrationPlanner.Result result) {
        return "{"
                + "\"request\":" + renderMigrationPlannerRequest(result.request()) + ","
                + "\"recommendedSurface\":\"" + escapeJson(result.recommendedSurface()) + "\","
                + "\"projectionRequired\":" + result.projectionRequired() + ","
                + "\"rankedProjectionRequired\":" + result.rankedProjectionRequired() + ","
                + "\"summaryFirstRequired\":" + result.summaryFirstRequired() + ","
                + "\"boundedRedisWindowRequired\":" + result.boundedRedisWindowRequired() + ","
                + "\"fullEntityHotWindowRecommended\":" + result.fullEntityHotWindowRecommended() + ","
                + "\"recommendedHotWindowPerRoot\":" + result.recommendedHotWindowPerRoot() + ","
                + "\"summaryProjectionName\":\"" + escapeJson(result.summaryProjectionName()) + "\","
                + "\"previewProjectionName\":\"" + escapeJson(result.previewProjectionName()) + "\","
                + "\"rankedProjectionName\":\"" + escapeJson(result.rankedProjectionName()) + "\","
                + "\"rankFieldName\":\"" + escapeJson(result.rankFieldName()) + "\","
                + "\"redisPlacement\":\"" + escapeJson(result.redisPlacement()) + "\","
                + "\"postgresPlacement\":\"" + escapeJson(result.postgresPlacement()) + "\","
                + "\"reasoning\":" + toJsonStringArray(result.reasoning()) + ","
                + "\"recommendedRedisArtifacts\":" + toJsonStringArray(result.recommendedRedisArtifacts()) + ","
                + "\"recommendedPostgresArtifacts\":" + toJsonStringArray(result.recommendedPostgresArtifacts()) + ","
                + "\"recommendedApiShapes\":" + toJsonStringArray(result.recommendedApiShapes()) + ","
                + "\"warmSteps\":" + renderMigrationWarmSteps(result.warmSteps()) + ","
                + "\"comparisonChecks\":" + renderMigrationComparisonChecks(result.comparisonChecks()) + ","
                + "\"warnings\":" + toJsonStringArray(result.warnings()) + ","
                + "\"sampleWarmSql\":\"" + escapeJson(result.sampleWarmSql()) + "\","
                + "\"sampleRootWarmSql\":\"" + escapeJson(result.sampleRootWarmSql()) + "\","
                + "\"comparisonSummary\":\"" + escapeJson(result.comparisonSummary()) + "\""
                + "}";
    }

    private String renderMigrationWarmResult(MigrationWarmRunner.Result result) {
        return "{"
                + "\"dryRun\":" + result.dryRun() + ","
                + "\"rootEntityName\":\"" + escapeJson(result.rootEntityName()) + "\","
                + "\"childEntityName\":\"" + escapeJson(result.childEntityName()) + "\","
                + "\"childRowsRead\":" + result.childRowsRead() + ","
                + "\"childRowsHydrated\":" + result.childRowsHydrated() + ","
                + "\"rootRowsRead\":" + result.rootRowsRead() + ","
                + "\"rootRowsHydrated\":" + result.rootRowsHydrated() + ","
                + "\"skippedDeletedRows\":" + result.skippedDeletedRows() + ","
                + "\"distinctReferencedRootIds\":" + result.distinctReferencedRootIds() + ","
                + "\"missingReferencedRootIds\":" + result.missingReferencedRootIds() + ","
                + "\"childWarmSql\":\"" + escapeJson(result.childWarmSql()) + "\","
                + "\"rootWarmSql\":\"" + escapeJson(result.rootWarmSql()) + "\","
                + "\"notes\":" + toJsonStringArray(result.notes()) + ","
                + "\"startedAt\":\"" + result.startedAt() + "\","
                + "\"completedAt\":\"" + result.completedAt() + "\","
                + "\"durationMillis\":" + result.durationMillis() + ","
                + "\"plan\":" + renderMigrationPlannerResult(result.plan())
                + "}";
    }

    private String renderMigrationScaffoldResult(MigrationScaffoldGenerator.Result result) {
        return "{"
                + "\"request\":" + renderMigrationScaffoldRequest(result.request()) + ","
                + "\"plan\":" + renderMigrationPlannerResult(result.plan()) + ","
                + "\"basePackage\":\"" + escapeJson(result.basePackage()) + "\","
                + "\"fileCount\":" + result.files().size() + ","
                + "\"files\":" + renderMigrationScaffoldFiles(result.files()) + ","
                + "\"notes\":" + toJsonStringArray(result.notes()) + ","
                + "\"warnings\":" + toJsonStringArray(result.warnings())
                + "}";
    }

    private String renderMigrationScaffoldRequest(MigrationScaffoldGenerator.Request request) {
        return "{"
                + "\"plannerRequest\":" + renderMigrationPlannerRequest(request.plannerRequest()) + ","
                + "\"basePackage\":\"" + escapeJson(request.basePackage()) + "\","
                + "\"rootClassName\":\"" + escapeJson(request.rootClassName()) + "\","
                + "\"childClassName\":\"" + escapeJson(request.childClassName()) + "\","
                + "\"relationLoaderClassName\":\"" + escapeJson(request.relationLoaderClassName()) + "\","
                + "\"projectionSupportClassName\":\"" + escapeJson(request.projectionSupportClassName()) + "\","
                + "\"includeRelationLoader\":" + request.includeRelationLoader() + ","
                + "\"includeProjectionSkeleton\":" + request.includeProjectionSkeleton()
                + "}";
    }

    private String renderMigrationScaffoldFiles(List<MigrationScaffoldGenerator.GeneratedFile> files) {
        StringBuilder builder = new StringBuilder("[");
        for (int index = 0; index < files.size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            MigrationScaffoldGenerator.GeneratedFile file = files.get(index);
            builder.append("{\"fileName\":\"").append(escapeJson(file.fileName())).append("\",")
                    .append("\"relativePath\":\"").append(escapeJson(file.relativePath())).append("\",")
                    .append("\"description\":\"").append(escapeJson(file.description())).append("\",")
                    .append("\"language\":\"").append(escapeJson(file.language())).append("\",")
                    .append("\"content\":\"").append(escapeJson(file.content())).append("\"}");
        }
        builder.append(']');
        return builder.toString();
    }

    private String renderMigrationComparisonResult(MigrationComparisonRunner.Result result) {
        MigrationComparisonReportRenderer.Report report = MigrationComparisonReportRenderer.render(result);
        return "{"
                + "\"request\":" + renderMigrationComparisonRequest(result.request()) + ","
                + "\"plan\":" + renderMigrationPlannerResult(result.plan()) + ","
                + "\"cacheRouteLabel\":\"" + escapeJson(result.cacheRouteLabel()) + "\","
                + "\"baselineSqlTemplate\":\"" + escapeJson(result.baselineSqlTemplate()) + "\","
                + "\"warmResult\":" + (result.warmResult() == null ? "null" : renderMigrationWarmResult(result.warmResult())) + ","
                + "\"baselineMetrics\":" + renderMigrationComparisonMetrics(result.baselineMetrics()) + ","
                + "\"cacheMetrics\":" + renderMigrationComparisonMetrics(result.cacheMetrics()) + ","
                + "\"sampleComparisons\":" + renderMigrationComparisonSamples(result.sampleComparisons()) + ","
                + "\"assessment\":" + renderMigrationComparisonAssessment(result.assessment()) + ","
                + "\"report\":" + renderMigrationComparisonReport(report) + ","
                + "\"notes\":" + toJsonStringArray(result.notes()) + ","
                + "\"warnings\":" + toJsonStringArray(result.warnings()) + ","
                + "\"startedAt\":\"" + result.startedAt() + "\","
                + "\"completedAt\":\"" + result.completedAt() + "\","
                + "\"durationMillis\":" + result.durationMillis()
                + "}";
    }

    private String renderMigrationComparisonRequest(MigrationComparisonRunner.Request request) {
        return "{"
                + "\"plannerRequest\":" + renderMigrationPlannerRequest(request.plannerRequest()) + ","
                + "\"warmBeforeCompare\":" + request.warmBeforeCompare() + ","
                + "\"warmRootRows\":" + request.warmRootRows() + ","
                + "\"childFetchSize\":" + request.childFetchSize() + ","
                + "\"rootFetchSize\":" + request.rootFetchSize() + ","
                + "\"rootBatchSize\":" + request.rootBatchSize() + ","
                + "\"sampleRootId\":\"" + escapeJson(request.sampleRootId()) + "\","
                + "\"sampleRootCount\":" + request.sampleRootCount() + ","
                + "\"warmupIterations\":" + request.warmupIterations() + ","
                + "\"measuredIterations\":" + request.measuredIterations() + ","
                + "\"pageSize\":" + request.pageSize() + ","
                + "\"projectionNameOverride\":\"" + escapeJson(request.projectionNameOverride()) + "\","
                + "\"baselineSqlOverride\":\"" + escapeJson(request.baselineSqlOverride()) + "\""
                + "}";
    }

    private String renderMigrationComparisonMetrics(MigrationComparisonRunner.Metrics metrics) {
        return "{"
                + "\"operations\":" + metrics.operations() + ","
                + "\"averageLatencyNanos\":" + metrics.averageLatencyNanos() + ","
                + "\"p95LatencyNanos\":" + metrics.p95LatencyNanos() + ","
                + "\"p99LatencyNanos\":" + metrics.p99LatencyNanos() + ","
                + "\"averageRowsPerOperation\":" + metrics.averageRowsPerOperation()
                + "}";
    }

    private String renderMigrationComparisonSamples(List<MigrationComparisonRunner.SampleComparison> samples) {
        StringBuilder builder = new StringBuilder("[");
        for (int index = 0; index < samples.size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            MigrationComparisonRunner.SampleComparison sample = samples.get(index);
            builder.append("{\"sampleLabel\":\"").append(escapeJson(sample.sampleLabel())).append("\",")
                    .append("\"baselineRowCount\":").append(sample.baselineRowCount()).append(',')
                    .append("\"cacheRowCount\":").append(sample.cacheRowCount()).append(',')
                    .append("\"baselineIds\":").append(toJsonStringArray(sample.baselineIds())).append(',')
                    .append("\"cacheIds\":").append(toJsonStringArray(sample.cacheIds())).append(',')
                    .append("\"exactMatch\":").append(sample.exactMatch())
                    .append("}");
        }
        builder.append(']');
        return builder.toString();
    }

    private String renderMigrationComparisonAssessment(MigrationComparisonAssessment.Result assessment) {
        return "{"
                + "\"readiness\":\"" + assessment.readiness().name() + "\","
                + "\"parityStatus\":\"" + assessment.parityStatus().name() + "\","
                + "\"performanceStatus\":\"" + assessment.performanceStatus().name() + "\","
                + "\"routeStatus\":\"" + assessment.routeStatus().name() + "\","
                + "\"summary\":\"" + escapeJson(assessment.summary()) + "\","
                + "\"decision\":\"" + escapeJson(assessment.decision()) + "\","
                + "\"exactMatchCount\":" + assessment.exactMatchCount() + ","
                + "\"sampleCount\":" + assessment.sampleCount() + ","
                + "\"exactMatchRatio\":" + assessment.exactMatchRatio() + ","
                + "\"averageLatencyRatio\":" + assessment.averageLatencyRatio() + ","
                + "\"p95LatencyRatio\":" + assessment.p95LatencyRatio() + ","
                + "\"strengths\":" + toJsonStringArray(assessment.strengths()) + ","
                + "\"blockers\":" + toJsonStringArray(assessment.blockers()) + ","
                + "\"nextSteps\":" + toJsonStringArray(assessment.nextSteps())
                + "}";
    }

    private String renderMigrationComparisonReport(MigrationComparisonReportRenderer.Report report) {
        return "{"
                + "\"fileName\":\"" + escapeJson(report.fileName()) + "\","
                + "\"markdown\":\"" + escapeJson(report.markdown()) + "\""
                + "}";
    }

    private String renderMigrationWarmSteps(List<MigrationPlanner.WarmStep> steps) {
        StringBuilder builder = new StringBuilder("[");
        for (int index = 0; index < steps.size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            MigrationPlanner.WarmStep step = steps.get(index);
            builder.append("{\"title\":\"").append(escapeJson(step.title())).append("\",")
                    .append("\"summary\":\"").append(escapeJson(step.summary())).append("\",")
                    .append("\"tasks\":").append(toJsonStringArray(step.tasks()))
                    .append("}");
        }
        builder.append(']');
        return builder.toString();
    }

    private String renderMigrationComparisonChecks(List<MigrationPlanner.ComparisonCheck> checks) {
        StringBuilder builder = new StringBuilder("[");
        for (int index = 0; index < checks.size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            MigrationPlanner.ComparisonCheck check = checks.get(index);
            builder.append("{\"title\":\"").append(escapeJson(check.title())).append("\",")
                    .append("\"baseline\":\"").append(escapeJson(check.baseline())).append("\",")
                    .append("\"target\":\"").append(escapeJson(check.target())).append("\"}");
        }
        builder.append(']');
        return builder.toString();
    }

    private String renderProductionReports(List<ProductionReportSnapshot> reports) {
        StringBuilder builder = new StringBuilder("{\"items\":[");
        for (int index = 0; index < reports.size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            ProductionReportSnapshot report = reports.get(index);
            builder.append("{\"reportKey\":\"").append(escapeJson(report.reportKey())).append("\",")
                    .append("\"fileName\":\"").append(escapeJson(report.fileName())).append("\",")
                    .append("\"absolutePath\":\"").append(escapeJson(report.absolutePath())).append("\",")
                    .append("\"lastModifiedAt\":\"").append(report.lastModifiedAt()).append("\",")
                    .append("\"status\":\"").append(escapeJson(report.status())).append("\",")
                    .append("\"headline\":\"").append(escapeJson(report.headline())).append("\"}");
        }
        builder.append("]}");
        return builder.toString();
    }

    private String renderIncidentHistory(List<AdminIncidentRecord> incidents) {
        StringBuilder builder = new StringBuilder("{\"items\":[");
        for (int index = 0; index < incidents.size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            AdminIncidentRecord incident = incidents.get(index);
            builder.append("{\"entryId\":\"").append(escapeJson(incident.entryId())).append("\",")
                    .append("\"code\":\"").append(escapeJson(incident.code())).append("\",")
                    .append("\"description\":\"").append(escapeJson(incident.description())).append("\",")
                    .append("\"severity\":\"").append(incident.severity().name()).append("\",")
                    .append("\"source\":\"").append(escapeJson(incident.source())).append("\",")
                    .append("\"recordedAt\":\"").append(incident.recordedAt()).append("\",")
                    .append("\"fields\":").append(renderMap(incident.fields())).append("}");
        }
        builder.append("]}");
        return builder.toString();
    }

    private String renderIncidentSeverityHistory(List<IncidentSeverityTrendPoint> points) {
        StringBuilder builder = new StringBuilder("{\"items\":[");
        for (int index = 0; index < points.size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            IncidentSeverityTrendPoint point = points.get(index);
            builder.append("{\"recordedAt\":\"").append(point.recordedAt()).append("\",")
                    .append("\"infoCount\":").append(point.infoCount()).append(',')
                    .append("\"warningCount\":").append(point.warningCount()).append(',')
                    .append("\"criticalCount\":").append(point.criticalCount()).append(',')
                    .append("\"totalCount\":").append(point.totalCount()).append('}');
        }
        builder.append("]}");
        return builder.toString();
    }

    private String renderFailingSignals(List<FailingSignalSnapshot> signals) {
        StringBuilder builder = new StringBuilder("{\"items\":[");
        for (int index = 0; index < signals.size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            FailingSignalSnapshot signal = signals.get(index);
            builder.append("{\"signalCode\":\"").append(escapeJson(signal.signalCode())).append("\",")
                    .append("\"severity\":\"").append(escapeJson(signal.severity())).append("\",")
                    .append("\"activeCount\":").append(signal.activeCount()).append(',')
                    .append("\"recentCount\":").append(signal.recentCount()).append(',')
                    .append("\"summary\":\"").append(escapeJson(signal.summary())).append("\",")
                    .append("\"lastSeenAt\":\"").append(signal.lastSeenAt()).append("\"}");
        }
        builder.append("]}");
        return builder.toString();
    }

    private String renderMap(Map<String, String> fields) {
        StringBuilder builder = new StringBuilder("{");
        int index = 0;
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            if (index++ > 0) {
                builder.append(',');
            }
            builder.append("\"").append(escapeJson(entry.getKey())).append("\":\"")
                    .append(escapeJson(defaultString(entry.getValue()))).append("\"");
        }
        builder.append('}');
        return builder.toString();
    }

    private Map<String, String> writeBehindWorkerSnapshotMap(ReconciliationMetrics metrics) {
        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        fields.put("flushedCount", String.valueOf(metrics.writeBehindWorkerSnapshot().flushedCount()));
        fields.put("batchFlushCount", String.valueOf(metrics.writeBehindWorkerSnapshot().batchFlushCount()));
        fields.put("batchFlushedOperationCount", String.valueOf(metrics.writeBehindWorkerSnapshot().batchFlushedOperationCount()));
        fields.put("lastObservedBacklog", String.valueOf(metrics.writeBehindWorkerSnapshot().lastObservedBacklog()));
        fields.put("lastAdaptiveBatchSize", String.valueOf(metrics.writeBehindWorkerSnapshot().lastAdaptiveBatchSize()));
        fields.put("coalescedCount", String.valueOf(metrics.writeBehindWorkerSnapshot().coalescedCount()));
        fields.put("lastInFlightFlushGroups", String.valueOf(metrics.writeBehindWorkerSnapshot().lastInFlightFlushGroups()));
        fields.put("staleSkippedCount", String.valueOf(metrics.writeBehindWorkerSnapshot().staleSkippedCount()));
        fields.put("retriedCount", String.valueOf(metrics.writeBehindWorkerSnapshot().retriedCount()));
        fields.put("deadLetterCount", String.valueOf(metrics.writeBehindWorkerSnapshot().deadLetterCount()));
        fields.put("claimedCount", String.valueOf(metrics.writeBehindWorkerSnapshot().claimedCount()));
        fields.put("pendingRecoveryCount", String.valueOf(metrics.writeBehindWorkerSnapshot().pendingRecoveryCount()));
        fields.put("lastErrorAtEpochMillis", String.valueOf(metrics.writeBehindWorkerSnapshot().lastErrorAtEpochMillis()));
        fields.put("lastErrorType", defaultString(metrics.writeBehindWorkerSnapshot().lastErrorType()));
        fields.put("lastErrorMessage", defaultString(metrics.writeBehindWorkerSnapshot().lastErrorMessage()));
        fields.put("lastErrorRootType", defaultString(metrics.writeBehindWorkerSnapshot().lastErrorRootType()));
        fields.put("lastErrorRootMessage", defaultString(metrics.writeBehindWorkerSnapshot().lastErrorRootMessage()));
        fields.put("lastErrorOrigin", defaultString(metrics.writeBehindWorkerSnapshot().lastErrorOrigin()));
        fields.put("lastErrorStackTrace", defaultString(metrics.writeBehindWorkerSnapshot().lastErrorStackTrace()));
        return fields;
    }

    private Map<String, String> projectionRefreshSnapshotMap(ProjectionRefreshSnapshot snapshot) {
        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        fields.put("streamLength", String.valueOf(snapshot.streamLength()));
        fields.put("deadLetterStreamLength", String.valueOf(snapshot.deadLetterStreamLength()));
        fields.put("processedCount", String.valueOf(snapshot.processedCount()));
        fields.put("retriedCount", String.valueOf(snapshot.retriedCount()));
        fields.put("failedCount", String.valueOf(snapshot.failedCount()));
        fields.put("replayedCount", String.valueOf(snapshot.replayedCount()));
        fields.put("claimedCount", String.valueOf(snapshot.claimedCount()));
        fields.put("pendingCount", String.valueOf(snapshot.pendingCount()));
        fields.put("lagEstimateMillis", String.valueOf(snapshot.lagEstimateMillis()));
        fields.put("backlogPresent", String.valueOf(snapshot.backlogPresent()));
        fields.put("lastProcessedAtEpochMillis", String.valueOf(snapshot.lastProcessedAtEpochMillis()));
        fields.put("lastErrorAtEpochMillis", String.valueOf(snapshot.lastErrorAtEpochMillis()));
        fields.put("lastErrorType", defaultString(snapshot.lastErrorType()));
        fields.put("lastErrorMessage", defaultString(snapshot.lastErrorMessage()));
        fields.put("lastErrorRootType", defaultString(snapshot.lastErrorRootType()));
        fields.put("lastErrorRootMessage", defaultString(snapshot.lastErrorRootMessage()));
        fields.put("lastErrorOrigin", defaultString(snapshot.lastErrorOrigin()));
        fields.put("lastPoisonEntryId", defaultString(snapshot.lastPoisonEntryId()));
        return fields;
    }

    static String resolveDashboardBasePath(String dashboardPath) {
        if (dashboardPath == null || dashboardPath.isBlank()) {
            return "";
        }
        String normalized = dashboardPath.trim();
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.endsWith("/dashboard-v3")) {
            return normalized.substring(0, normalized.length() - "/dashboard-v3".length());
        }
        if (normalized.endsWith("/dashboard")) {
            return normalized.substring(0, normalized.length() - "/dashboard".length());
        }
        if (normalized.endsWith("/migration-planner")) {
            return normalized.substring(0, normalized.length() - "/migration-planner".length());
        }
        if ("/".equals(normalized)) {
            return "";
        }
        return normalized;
    }

    private String renderMigrationPlanner(
            String language,
            String plannerPath,
            MigrationPlannerPageState pageState
    ) {
        String normalizedLanguage = normalizeDashboardLanguage(language);
        MigrationSchemaDiscovery.Result bootstrapDiscovery = pageState == null ? null : pageState.discovery();
        Map<String, String> plannerValues = pageState == null ? defaultMigrationPlannerFormValues() : pageState.formValues();
        MigrationPlannerDemoSupport.BootstrapResult bootstrapDemoResult = pageState == null ? null : pageState.demoBootstrapResult();
        String bootstrapDemoError = pageState == null ? "" : defaultString(pageState.demoBootstrapError());
        MigrationPlanner.Result bootstrapPlanResult = pageState == null ? null : pageState.planResult();
        String bootstrapPlanError = pageState == null ? "" : defaultString(pageState.planError());
        String title = escapeHtml(uiString("migrationPlanner.pageTitle", localized(normalizedLanguage, "CacheDB Geçiş Planlayıcı", "CacheDB Migration Planner")));
        String basePath = resolveDashboardBasePath(plannerPath);
        String dashboardUrl = escapeHtml(appendQuery(
                basePath.isBlank() ? "/" : basePath,
                "lang=" + normalizedLanguage + "&v=" + dashboardInstanceId
        ));
        String apiBasePath = escapeJs(basePath);
        String bootstrapDiscoveryJson = bootstrapDiscovery == null ? "null" : renderMigrationSchemaDiscovery(bootstrapDiscovery);
        String bootstrapDemoJson = bootstrapDemoResult == null ? "null" : renderMigrationPlannerDemoBootstrapResult(bootstrapDemoResult);
        String bootstrapPlanJson = bootstrapPlanResult == null ? "null" : renderMigrationPlannerResult(bootstrapPlanResult);
        String bootstrapPlannerFormJson = renderPlannerFormValues(plannerValues);
        String discoveryFallbackAction = escapeHtml(basePath.isBlank() ? "/cachedb-admin/migration-planner" : basePath + "/migration-planner");
        String demoBootstrapStatus = escapeHtml(resolveDemoBootstrapStatusMessage(
                normalizedLanguage,
                bootstrapDemoResult,
                bootstrapDemoError
        ));
        String plannerStatusText = escapeHtml(resolvePlannerStatusMessage(
                normalizedLanguage,
                bootstrapPlanResult,
                bootstrapPlanError
        ));
        String bootstrapDiscoveryStatus = escapeHtml(localized(
                normalizedLanguage,
                bootstrapDiscovery == null
                        ? "İstersen önce PostgreSQL şemasını keşfet. Uygun root/child route adaylarını ve tablo kolonlarını burada görebilirsin."
                        : "Şema keşfi server-side olarak hazırlandı. İstersen önerileri hemen kullanabilir ya da keşfi yeniden çalıştırabilirsin.",
                bootstrapDiscovery == null
                        ? "Start by discovering the PostgreSQL schema. You can review root/child route candidates and table columns here before filling the planner."
                        : "Schema discovery was prepared server-side. You can use the suggestions right away or run discovery again."
        ));
        String bootstrapCssUrl = escapeHtml(uiString("bootstrapCssUrl", "https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css"));
        String googleFontsUrl = escapeHtml(uiString("fontsUrl", "https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&family=IBM+Plex+Mono:wght@400;500&display=swap"));
        String subtitle = escapeHtml(localized(
                normalizedLanguage,
                "Mevcut PostgreSQL/ORM route’unu CacheDB sıcak pencere, projection, warm-up ve karşılaştırma planına dönüştür.",
                "Turn an existing PostgreSQL/ORM route into a CacheDB hot-window, projection, warm-up, and comparison plan."
        ));
        String intro = escapeHtml(localized(
                normalizedLanguage,
                "Bu sihirbaz, mevcut ekran şekline göre doğru CacheDB mimarisini seçmene yardım eder. Planı ürettikten sonra staging'de gerçek warm execution çalıştırabilir; doğru sıcak veri sınırını, projection kararını ve karşılaştırma planını birlikte görürsün.",
                "This wizard helps choose the right CacheDB architecture for an existing screen shape. After generating the plan, it can run a real staging warm execution so you can see the hot-data boundary, projection decision, and comparison plan together."
        ));
        String plannerThemeCss = "body{margin:0;background:linear-gradient(180deg,#f7f4ed 0%,#eef2f7 100%);color:#102a43;font-family:'Inter','Segoe UI',system-ui,sans-serif}"
                + ".planner-shell{max-width:1400px;margin:0 auto;padding:2rem 1.25rem 3rem}"
                + ".planner-topbar{display:flex;justify-content:space-between;align-items:center;gap:1rem;flex-wrap:wrap;margin-bottom:1.25rem}"
                + ".planner-back{display:inline-flex;align-items:center;gap:.5rem;padding:.72rem 1rem;border-radius:999px;border:1px solid #cbd7e6;background:#fff;color:#17324d;text-decoration:none;font-weight:700;box-shadow:0 .4rem 1rem rgba(15,23,42,.06)}"
                + ".planner-hero{padding:1.5rem 1.6rem;border-radius:1.4rem;background:linear-gradient(135deg,#0f2742,#194b84 60%,#2f6fed);color:#fff;box-shadow:0 1.1rem 2.7rem rgba(15,23,42,.18);margin-bottom:1.25rem}"
                + ".planner-hero h1{font-size:2rem;font-weight:800;margin:0 0 .55rem 0}"
                + ".planner-hero p{margin:0;color:rgba(255,255,255,.9);max-width:70rem;line-height:1.6}"
                + ".planner-guidance{display:grid;grid-template-columns:minmax(280px,360px) minmax(0,1fr);gap:1rem;align-items:start;margin-bottom:1rem}"
                + ".planner-grid{display:grid;grid-template-columns:minmax(360px,480px) minmax(0,1fr);gap:1rem;align-items:start}"
                + ".planner-panel{border:1px solid rgba(124,145,168,.24);border-radius:1.25rem;background:rgba(255,255,255,.96);box-shadow:0 .9rem 2rem rgba(15,23,42,.08);overflow:hidden}"
                + ".planner-panel-header{padding:1rem 1.15rem .25rem 1.15rem;font-size:1rem;font-weight:800;color:#102a43}"
                + ".planner-panel-sub{padding:0 1.15rem 1rem 1.15rem;color:#52606d;font-size:.9rem;line-height:1.5}"
                + ".planner-panel-body{padding:0 1.15rem 1.15rem 1.15rem}"
                + ".planner-mode-switch{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:.7rem}"
                + ".planner-mode-button{display:flex;flex-direction:column;align-items:flex-start;gap:.28rem;padding:.92rem 1rem;border-radius:1rem;border:1px solid #dbe7f3;background:linear-gradient(180deg,#fff,#f8fbff);color:#17324d;text-align:left;transition:transform .18s ease,box-shadow .18s ease,border-color .18s ease}"
                + ".planner-mode-button:hover{transform:translateY(-1px);box-shadow:0 .5rem 1rem rgba(15,23,42,.08)}"
                + ".planner-mode-button.is-active{border-color:#2f6fed;box-shadow:0 .7rem 1.4rem rgba(47,111,237,.16);background:linear-gradient(180deg,#eff6ff,#fff)}"
                + ".planner-mode-title{font-size:.92rem;font-weight:800}"
                + ".planner-mode-copy{font-size:.8rem;line-height:1.45;color:#52606d}"
                + ".planner-progress-shell{padding:1rem 1.05rem;border-radius:1rem;background:linear-gradient(180deg,#ffffff,#f8fbff);border:1px solid #dbe7f3;box-shadow:inset 0 1px 0 rgba(255,255,255,.75)}"
                + ".planner-progress-top{display:flex;justify-content:space-between;align-items:flex-start;gap:1rem;flex-wrap:wrap;margin-bottom:.85rem}"
                + ".planner-progress-title{font-size:1rem;font-weight:800;color:#102a43}"
                + ".planner-progress-copy{font-size:.86rem;line-height:1.5;color:#52606d;margin-top:.2rem;max-width:44rem}"
                + ".planner-progress-percent{font-size:1.1rem;font-weight:800;color:#1d4ed8}"
                + ".planner-progress-track{position:relative;height:.72rem;border-radius:999px;background:#dfe8f3;overflow:hidden}"
                + ".planner-progress-fill{position:absolute;inset:0 auto 0 0;width:0%;border-radius:inherit;background:linear-gradient(90deg,#0ea5e9,#2563eb);transition:width .25s ease}"
                + ".planner-stepper{display:grid;grid-template-columns:repeat(5,minmax(0,1fr));gap:.7rem;margin-top:1rem}"
                + ".planner-step{display:flex;flex-direction:column;gap:.28rem;padding:.9rem .95rem;border-radius:1rem;border:1px solid #dbe7f3;background:#fff;text-align:left;color:#17324d;min-height:100%;transition:transform .18s ease,box-shadow .18s ease,border-color .18s ease,background .18s ease}"
                + ".planner-step:hover{transform:translateY(-1px);box-shadow:0 .55rem 1rem rgba(15,23,42,.08)}"
                + ".planner-step.is-complete{border-color:#bbf7d0;background:#f0fdf4}"
                + ".planner-step.is-active{border-color:#60a5fa;background:#eff6ff;box-shadow:0 .6rem 1.1rem rgba(59,130,246,.14)}"
                + ".planner-step-index{display:inline-flex;align-items:center;justify-content:center;width:1.7rem;height:1.7rem;border-radius:999px;background:#e2e8f0;color:#17324d;font-size:.82rem;font-weight:800}"
                + ".planner-step.is-complete .planner-step-index{background:#16a34a;color:#fff}"
                + ".planner-step.is-active .planner-step-index{background:#2563eb;color:#fff}"
                + ".planner-step-title{font-size:.86rem;font-weight:800}"
                + ".planner-step-copy{font-size:.77rem;line-height:1.4;color:#52606d}"
                + ".planner-quickstart{padding:1rem 1.05rem;border-radius:1rem;background:linear-gradient(180deg,#f8fbff,#fff);border:1px solid #dbe7f3;box-shadow:inset 0 1px 0 rgba(255,255,255,.7)}"
                + ".planner-quickstart-title{font-size:.82rem;letter-spacing:.05em;text-transform:uppercase;font-weight:800;color:#486581;margin-bottom:.45rem}"
                + ".planner-quickstart-copy{font-size:.92rem;line-height:1.55;color:#334e68;margin:0}"
                + ".planner-form-section{padding:1rem 1.05rem;border-radius:1rem;background:linear-gradient(180deg,#ffffff,#f9fbfd);border:1px solid #e4edf5;box-shadow:0 .45rem 1.2rem rgba(15,23,42,.04)}"
                + ".planner-form-section[data-planner-mode-min],.result-card[data-planner-mode-min],.planner-advanced[data-planner-mode-min]{transition:opacity .18s ease}"
                + ".planner-hidden-by-mode{display:none !important}"
                + ".planner-form-section-title{font-size:.93rem;font-weight:800;color:#102a43;margin-bottom:.2rem}"
                + ".planner-form-section-copy{font-size:.86rem;line-height:1.5;color:#52606d;margin-bottom:.8rem}"
                + ".planner-select-help{margin-top:.38rem;font-size:.79rem;line-height:1.4;color:#6b7a89}"
                + ".planner-toggle-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:.7rem}"
                + ".planner-toggle-grid .form-check{padding:.72rem .78rem;border-radius:.9rem;background:#f8fafc;border:1px solid #e4edf5;margin:0}"
                + ".planner-advanced{border:1px solid #dbe7f3;border-radius:1rem;background:#fbfdff;overflow:hidden}"
                + ".planner-advanced > summary{cursor:pointer;list-style:none;padding:.9rem 1rem;font-weight:800;color:#17324d;background:linear-gradient(180deg,#f8fbff,#f2f7fc)}"
                + ".planner-advanced > summary::-webkit-details-marker{display:none}"
                + ".planner-advanced-body{padding:1rem}"
                + ".planner-object-actions{display:flex;gap:.45rem;flex-wrap:wrap;margin-top:.7rem}"
                + ".planner-badge{display:inline-flex;align-items:center;padding:.22rem .52rem;border-radius:999px;background:#eef4ff;border:1px solid #dbe7ff;color:#1d4ed8;font-size:.74rem;font-weight:700;margin-right:.35rem}"
                + ".planner-badge.view{background:#f5f3ff;border-color:#ddd6fe;color:#6d28d9}"
                + ".planner-badge.entity{background:#ecfdf5;border-color:#bbf7d0;color:#166534}"
                + ".planner-form-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:.85rem}"
                + ".planner-form-grid .full{grid-column:1 / -1}"
                + ".planner-actions{display:flex;gap:.7rem;flex-wrap:wrap;margin-top:1rem}"
                + ".planner-chip-row{display:flex;gap:.55rem;flex-wrap:wrap;margin-top:1rem}"
                + ".planner-chip{display:inline-flex;align-items:center;padding:.38rem .72rem;border-radius:999px;background:#eef4ff;border:1px solid #dbe7ff;color:#1d4ed8;font-size:.8rem;font-weight:700}"
                + ".planner-status{margin-top:1rem;padding:.8rem .95rem;border-radius:1rem;background:#f8fbff;border:1px solid #dbe7f3;color:#486581;font-size:.88rem;line-height:1.45}"
                + ".planner-results{display:flex;flex-direction:column;gap:1rem}"
                + ".result-card{border:1px solid rgba(124,145,168,.24);border-radius:1.2rem;background:#fff;box-shadow:0 .8rem 1.8rem rgba(15,23,42,.07);overflow:hidden}"
                + ".result-card-header{padding:1rem 1.1rem;border-bottom:1px solid #e4edf5;font-size:.98rem;font-weight:800;color:#102a43;background:linear-gradient(180deg,#fff,#f8fbff)}"
                + ".result-card-body{padding:1rem 1.1rem}"
                + ".result-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:.8rem}"
                + ".result-metric{padding:.9rem .95rem;border-radius:1rem;background:#f8fafc;border:1px solid #e4edf5}"
                + ".result-metric-label{font-size:.74rem;letter-spacing:.04em;color:#7b8794;font-weight:800;text-transform:uppercase;margin-bottom:.25rem}"
                + ".result-metric-value{font-size:1.02rem;font-weight:800;color:#17324d;line-height:1.35}"
                + ".result-list{margin:0;padding-left:1.15rem;display:flex;flex-direction:column;gap:.45rem;color:#334e68}"
                + ".result-step{padding:.9rem;border-radius:1rem;background:#f8fbff;border:1px solid #dbe7f3;margin-bottom:.75rem}"
                + ".result-step:last-child{margin-bottom:0}"
                + ".result-step-title{font-weight:800;color:#102a43;margin-bottom:.3rem}"
                + ".result-step-summary{color:#52606d;font-size:.9rem;line-height:1.5;margin-bottom:.55rem}"
                + ".result-check{padding:.85rem;border-radius:1rem;background:#fff7ed;border:1px solid #fed7aa;margin-bottom:.75rem}"
                + ".result-check:last-child{margin-bottom:0}"
                + ".result-pre{margin:0;background:#0f172a;color:#ecf3ff;border-radius:1rem;padding:1rem;overflow:auto;border:1px solid rgba(148,163,184,.18);font-family:'IBM Plex Mono','JetBrains Mono',Consolas,monospace;font-size:.85rem;line-height:1.5}"
                + ".planner-empty{padding:1.2rem;border-radius:1rem;border:1px dashed #cbd5e1;background:#fff;color:#52606d;line-height:1.6}"
                + ".planner-warning{padding:.85rem .95rem;border-radius:1rem;background:#fff7ed;border:1px solid #fed7aa;color:#9a3412;font-size:.88rem;line-height:1.5}"
                + "@media (max-width: 1100px){.planner-guidance{grid-template-columns:1fr}.planner-grid{grid-template-columns:1fr}.planner-form-grid{grid-template-columns:1fr}.result-grid{grid-template-columns:1fr}.planner-stepper{grid-template-columns:repeat(2,minmax(0,1fr))}}"
                + "@media (max-width: 720px){.planner-mode-switch{grid-template-columns:1fr}.planner-stepper{grid-template-columns:1fr}}";
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>" + title + "</title>"
                + "<link rel=\"preconnect\" href=\"https://fonts.googleapis.com\">"
                + "<link rel=\"preconnect\" href=\"https://fonts.gstatic.com\" crossorigin>"
                + "<link rel=\"stylesheet\" href=\"" + bootstrapCssUrl + "\">"
                + "<link rel=\"stylesheet\" href=\"" + googleFontsUrl + "\">"
                + "<style>" + plannerThemeCss + "</style>"
                + "</head><body>"
                + "<main class=\"planner-shell\">"
                + "<div class=\"planner-topbar\"><a class=\"planner-back\" href=\"" + dashboardUrl + "\">"
                + escapeHtml(localized(normalizedLanguage, "Yönetim paneline dön", "Back to admin dashboard"))
                + "</a></div>"
                + "<section class=\"planner-hero\"><h1>" + title + "</h1><p><strong>" + subtitle + "</strong><br>" + intro + "</p></section>"
                + renderMigrationPlannerGuidance(normalizedLanguage)
                + "<section class=\"planner-grid\">"
                + "<div class=\"planner-panel\">"
                + "<div class=\"planner-panel-header\">" + escapeHtml(localized(normalizedLanguage, "1. Mevcut route'u anlat", "1. Describe the current route")) + "</div>"
                + "<div class=\"planner-panel-sub\">" + escapeHtml(localized(normalizedLanguage, "Bir sıcak ekranı ya da endpoint'i modelle. Sihirbaz, veriyi tamamen Redis'te taşımak yerine hangi kısmın sıcak çalışma seti olması gerektiğini çıkarır.", "Model one hot screen or endpoint. The wizard decides which part should be the hot working set instead of pushing all history into Redis.")) + "</div>"
                + "<div class=\"planner-panel-body\">"
                + "<div id=\"plannerDemoPanel\" class=\"result-card mb-4\"><div class=\"result-card-header\">" + escapeHtml(localized(normalizedLanguage, "0. Demo PostgreSQL şemasını hazırla", "0. Prepare the demo PostgreSQL schema")) + "</div><div class=\"result-card-body\">"
                + "<div class=\"small text-muted mb-3\">" + escapeHtml(localized(normalizedLanguage, "Bu aksiyon, customer/order odaklı örnek PostgreSQL tablolarını, PK/FK ilişkilerini, indeksleri ve view'leri kurar; ardından migration planner için kullanılabilecek seed verisini yükler.", "This action prepares example customer/order PostgreSQL tables, PK/FK relationships, indexes, and views, then loads seed data that the migration planner can use.")) + "</div>"
                + "<form id=\"plannerDemoBootstrapFallbackForm\" method=\"get\" action=\"" + discoveryFallbackAction + "\">"
                + "<input type=\"hidden\" name=\"lang\" value=\"" + escapeHtml(normalizedLanguage) + "\">"
                + "<input type=\"hidden\" name=\"v\" value=\"" + escapeHtml(dashboardInstanceId) + "\">"
                + "<input type=\"hidden\" name=\"discover\" value=\"true\">"
                + "<input type=\"hidden\" name=\"demoBootstrap\" value=\"true\">"
                + "<div class=\"form-grid\">"
                + fieldInput("demoCustomerCount", localized(normalizedLanguage, "Demo müşteri sayısı", "Demo customer count"), "120", "", plannerValues.get("demoCustomerCount"))
                + fieldInput("demoHotCustomerCount", localized(normalizedLanguage, "Yoğun müşteri sayısı", "Hot customer count"), "12", "", plannerValues.get("demoHotCustomerCount"))
                + fieldInput("demoMaxOrdersPerCustomer", localized(normalizedLanguage, "Müşteri başına maks sipariş", "Max orders per customer"), "1500", "", plannerValues.get("demoMaxOrdersPerCustomer"))
                + "</div>"
                + "<div class=\"planner-actions mt-3\"><button id=\"plannerDemoBootstrapAction\" type=\"submit\" class=\"btn btn-outline-primary\">" + escapeHtml(localized(normalizedLanguage, "Demo şemayı kur ve seed et", "Create and seed the demo schema")) + "</button></div>"
                + "</form>"
                + "<div id=\"plannerDemoStatus\" class=\"planner-status mt-3\">" + demoBootstrapStatus + "</div>"
                + "<div id=\"plannerDemoResults\" class=\"" + (bootstrapDemoResult == null ? "d-none " : "") + "mt-3\">"
                + "<div class=\"result-grid\">"
                + metricShell("plannerDemoCustomers", localized(normalizedLanguage, "Müşteriler", "Customers"), bootstrapDemoResult == null ? "" : String.valueOf(bootstrapDemoResult.customerCount()))
                + metricShell("plannerDemoOrders", localized(normalizedLanguage, "Siparişler", "Orders"), bootstrapDemoResult == null ? "" : String.valueOf(bootstrapDemoResult.orderCount()))
                + metricShell("plannerDemoHottestCustomer", localized(normalizedLanguage, "En yoğun müşteri siparişi", "Hottest customer orders"), bootstrapDemoResult == null ? "" : String.valueOf(bootstrapDemoResult.hottestCustomerOrderCount()))
                + metricShell("plannerDemoViews", localized(normalizedLanguage, "Hazır view", "Prepared views"), bootstrapDemoResult == null ? "" : String.valueOf(bootstrapDemoResult.viewNames().size()))
                + "</div>"
                + "<div class=\"small text-muted fw-semibold mt-3 mb-2\">" + escapeHtml(localized(normalizedLanguage, "Demo notları", "Demo notes")) + "</div><div id=\"plannerDemoNotes\">" + renderPlannerStaticList(
                        bootstrapDemoResult == null ? List.of() : bootstrapDemoResult.notes(),
                        localized(normalizedLanguage, "Henüz demo seed notu üretilmedi.", "No demo seed note was generated yet.")
                ) + "</div>"
                + "</div></div></div>"
                + "<div id=\"plannerDiscoveryPanel\" class=\"result-card mb-4\"><div class=\"result-card-header\">" + escapeHtml(localized(normalizedLanguage, "0. PostgreSQL Şema Keşfi", "0. PostgreSQL Schema Discovery")) + "</div><div class=\"result-card-body\">"
                + "<form id=\"plannerDiscoveryFallbackForm\" method=\"get\" action=\"" + discoveryFallbackAction + "\" class=\"planner-actions\">"
                + "<input type=\"hidden\" name=\"lang\" value=\"" + escapeHtml(normalizedLanguage) + "\">"
                + "<input type=\"hidden\" name=\"v\" value=\"" + escapeHtml(dashboardInstanceId) + "\">"
                + "<input type=\"hidden\" name=\"discover\" value=\"true\">"
                + "<button id=\"plannerDiscoverAction\" type=\"submit\" class=\"btn btn-outline-primary\">" + escapeHtml(localized(normalizedLanguage, "PostgreSQL'den Keşfet", "Discover From PostgreSQL")) + "</button>"
                + "</form>"
                + "<div id=\"plannerDiscoveryStatus\" class=\"planner-status mt-3\">" + bootstrapDiscoveryStatus + "</div>"
                + "<div class=\"small text-muted fw-semibold mt-3 mb-2\">" + escapeHtml(localized(normalizedLanguage, "Önerilen route adayları", "Suggested route candidates")) + "</div><div id=\"plannerDiscoverySuggestions\">" + renderStaticDiscoverySuggestions(bootstrapDiscovery, normalizedLanguage, discoveryFallbackAction, plannerValues) + "</div>"
                + "<div class=\"small text-muted fw-semibold mt-3 mb-2\">" + escapeHtml(localized(normalizedLanguage, "Keşfedilen tablolar", "Discovered tables")) + "</div><div id=\"plannerDiscoveryTables\">" + renderStaticDiscoveryTables(bootstrapDiscovery, normalizedLanguage, discoveryFallbackAction, plannerValues) + "</div>"
                + "</div></div>"
                + "<form id=\"plannerForm\" method=\"get\" action=\"" + discoveryFallbackAction + "\">"
                + "<input type=\"hidden\" name=\"lang\" value=\"" + escapeHtml(normalizedLanguage) + "\">"
                + "<input type=\"hidden\" name=\"v\" value=\"" + escapeHtml(dashboardInstanceId) + "\">"
                + "<input type=\"hidden\" name=\"generatePlan\" value=\"true\">"
                + "<div class=\"planner-form-grid\">"
                + "<div class=\"full planner-quickstart\"><div class=\"planner-quickstart-title\">" + escapeHtml(localized(normalizedLanguage, "Kısa kullanım akışı", "Quick usage flow")) + "</div><p class=\"planner-quickstart-copy\">"
                + escapeHtml(localized(normalizedLanguage, "Önce keşfi çalıştır, sonra kök ve çocuk yüzeyi listeden seç. Ekran tipini belirledikten sonra planı üret; warm, scaffold ve compare adımları onun altında açılır.", "Start with discovery, then choose the root and child surfaces from the list. After selecting the screen type, generate the plan; warm, scaffold, and compare unlock below."))
                + "</p></div>"
                + fieldInput("workloadName", localized(normalizedLanguage, "İş yükü adı", "Workload name"), "customer-orders", "full", plannerValues.get("workloadName"))
                + "<div id=\"plannerConfigurePanel\" class=\"full planner-form-section\"><div class=\"planner-form-section-title\">" + escapeHtml(localized(normalizedLanguage, "1. Ekran tipi", "1. Screen type")) + "</div><div class=\"planner-form-section-copy\">"
                + escapeHtml(localized(normalizedLanguage, "Bu seçim planner'ın temel varsayımlarını hazırlar. İstersen aşağıdaki ileri ayarlarda bunları ayrıca değiştirebilirsin.", "This choice prepares the planner's default assumptions. You can still override them later in the advanced settings."))
                + "</div><div><label class=\"form-label fw-semibold\">" + escapeHtml(localized(normalizedLanguage, "Hazır ekran profili", "Prepared screen profile")) + "</label><select class=\"form-select\" name=\"routePreset\">"
                + selectOption("timeline", localized(normalizedLanguage, "Timeline / müşteri sipariş listesi", "Timeline / customer order list"), plannerValues.get("routePreset"))
                + selectOption("leaderboard", localized(normalizedLanguage, "Top-N / global sıralı ekran", "Top-N / globally sorted screen"), plannerValues.get("routePreset"))
                + selectOption("threshold", localized(normalizedLanguage, "Threshold / aralık odaklı ekran", "Threshold / range-driven screen"), plannerValues.get("routePreset"))
                + selectOption("detail-heavy", localized(normalizedLanguage, "Detail ağırlıklı ekran", "Detail-heavy screen"), plannerValues.get("routePreset"))
                + "</select><div id=\"plannerPresetHelp\" class=\"planner-select-help\">"
                + escapeHtml(localized(normalizedLanguage, "Timeline seçimi, müşteri başına son siparişler gibi bounded sıcak pencere ekranları için en doğru başlangıçtır.", "Timeline is the best starting point for bounded hot-window screens such as recent orders per customer."))
                + "</div></div></div>"
                + "<div class=\"full planner-form-section\"><div class=\"planner-form-section-title\">" + escapeHtml(localized(normalizedLanguage, "2. Veri yüzeyini seç", "2. Choose the data surfaces")) + "</div><div class=\"planner-form-section-copy\">"
                + escapeHtml(localized(normalizedLanguage, "Keşif çalıştıktan sonra aşağıdaki listeler otomatik dolar. İstersen önerilen route kartından tek tıkla formu da doldurabilirsin.", "After discovery runs, the lists below are populated automatically. You can also apply a suggested route card with one click."))
                + "</div><div class=\"planner-form-grid\">"
                + selectInput("rootTableOrEntity", localized(normalizedLanguage, "Kök tablo / entity", "Root table / entity"), localized(normalizedLanguage, "Önce keşif çalıştır", "Run discovery first"), "", discoveredSurfaceOptions(bootstrapDiscovery, false, normalizedLanguage), plannerValues.get("rootTableOrEntity"))
                + selectInput("childTableOrEntity", localized(normalizedLanguage, "Çocuk tablo / entity / view", "Child table / entity / view"), localized(normalizedLanguage, "Önce keşif çalıştır", "Run discovery first"), "", discoveredSurfaceOptions(bootstrapDiscovery, true, normalizedLanguage), plannerValues.get("childTableOrEntity"))
                + selectInput("rootPrimaryKeyColumn", localized(normalizedLanguage, "Kök PK kolonu", "Root PK column"), localized(normalizedLanguage, "Kök seçimine göre dolar", "Filled from the selected root"), "", discoveredColumnOptions(resolveDiscoveryTable(bootstrapDiscovery, plannerValues.get("rootTableOrEntity")), "pk"), plannerValues.get("rootPrimaryKeyColumn"))
                + selectInput("childPrimaryKeyColumn", localized(normalizedLanguage, "Çocuk PK kolonu", "Child PK column"), localized(normalizedLanguage, "Çocuk seçimine göre dolar", "Filled from the selected child"), "", discoveredColumnOptions(resolveDiscoveryTable(bootstrapDiscovery, plannerValues.get("childTableOrEntity")), "pk"), plannerValues.get("childPrimaryKeyColumn"))
                + selectInput("relationColumn", localized(normalizedLanguage, "İlişki kolonu", "Relation column"), localized(normalizedLanguage, "Önerilen FK kolonları", "Suggested FK columns"), "", discoveredRelationOptions(bootstrapDiscovery, plannerValues.get("rootTableOrEntity"), plannerValues.get("childTableOrEntity"), normalizedLanguage), plannerValues.get("relationColumn"))
                + selectInput("sortColumn", localized(normalizedLanguage, "Liste sıralama kolonu", "List sort column"), localized(normalizedLanguage, "Zamansal / sayısal kolonlardan seç", "Choose from temporal / numeric columns"), "", discoveredColumnOptions(resolveDiscoveryTable(bootstrapDiscovery, plannerValues.get("childTableOrEntity")), "sort"), plannerValues.get("sortColumn"))
                + selectInput("sortDirection", localized(normalizedLanguage, "Sıralama yönü", "Sort direction"), localized(normalizedLanguage, "Sıralama yönünü seç", "Choose the sort direction"), "", List.of(new PlannerSelectOption("DESC", "DESC"), new PlannerSelectOption("ASC", "ASC")), plannerValues.get("sortDirection"))
                + "</div></div>"
                + "<div class=\"full planner-form-section\"><div class=\"planner-form-section-title\">" + escapeHtml(localized(normalizedLanguage, "3. Hızlı kapasite kararı", "3. Quick capacity decision")) + "</div><div class=\"planner-form-section-copy\">"
                + escapeHtml(localized(normalizedLanguage, "Buradaki değerler tam hesap olmak zorunda değil. Planner için kaba ölçek ve sıcak pencere hedefini vermen yeterli.", "These values do not need to be exact. A rough scale and hot-window target are enough for the planner."))
                + "</div><div class=\"planner-form-grid\">"
                + fieldInput("rootRowCount", localized(normalizedLanguage, "Kök satır sayısı", "Root row count"), "100000", "", plannerValues.get("rootRowCount"))
                + fieldInput("childRowCount", localized(normalizedLanguage, "Çocuk satır sayısı", "Child row count"), "5000000", "", plannerValues.get("childRowCount"))
                + fieldInput("firstPageSize", localized(normalizedLanguage, "İlk sayfa boyutu", "First page size"), "100", "", plannerValues.get("firstPageSize"))
                + fieldInput("hotWindowPerRoot", localized(normalizedLanguage, "Sıcak pencere / kök", "Hot window / root"), "1000", "", plannerValues.get("hotWindowPerRoot"))
                + "</div></div>"
                + "<div class=\"full planner-form-section\"><div class=\"planner-form-section-title\">" + escapeHtml(localized(normalizedLanguage, "4. Operasyon tercihleri", "4. Operational preferences")) + "</div><div class=\"planner-form-section-copy\">"
                + escapeHtml(localized(normalizedLanguage, "Bunlar doğrudan mimari kararını etkileyen temel seçimler. Geri kalan ayrıntılar aşağıdaki ileri ayarlarda duruyor.", "These are the key preferences that directly shape the architecture decision. The rest stays in the advanced settings below."))
                + "</div><div class=\"planner-toggle-grid\">"
                + checkboxInput("archiveHistoryRequired", localized(normalizedLanguage, "Eski tarihçe archive olarak kalsın", "Keep older history as archive"), parseChecked(plannerValues, "archiveHistoryRequired", true))
                + checkboxInput("detailLookupIsHot", localized(normalizedLanguage, "Tekil detail lookup da sıcak", "Single-item detail lookup is also hot"), parseChecked(plannerValues, "detailLookupIsHot", true))
                + checkboxInput("sideBySideComparisonRequired", localized(normalizedLanguage, "Staging karşılaştırması istiyorum", "I want staging comparison"), parseChecked(plannerValues, "sideBySideComparisonRequired", true))
                + checkboxInput("warmRootRows", localized(normalizedLanguage, "Warm sırasında kök satırları da doldur", "Warm the root rows too"), parseChecked(plannerValues, "warmRootRows", true))
                + "</div></div>"
                + "<details class=\"full planner-advanced\" data-planner-mode-min=\"intermediate\"><summary>" + escapeHtml(localized(normalizedLanguage, "İleri route ayarları", "Advanced route settings")) + "</summary><div class=\"planner-advanced-body\"><div class=\"planner-form-grid\">"
                + fieldInput("typicalChildrenPerRoot", localized(normalizedLanguage, "Tipik çocuk sayısı / kök", "Typical children per root"), "40", "", plannerValues.get("typicalChildrenPerRoot"))
                + fieldInput("maxChildrenPerRoot", localized(normalizedLanguage, "Maks çocuk sayısı / kök", "Max children per root"), "2000", "", plannerValues.get("maxChildrenPerRoot"))
                + checkboxInput("listScreen", localized(normalizedLanguage, "Bu ekran bir liste / timeline ekranı", "This route is a list / timeline screen"), parseChecked(plannerValues, "listScreen", true))
                + checkboxInput("firstPaintNeedsFullAggregate", localized(normalizedLanguage, "İlk boyamada tam aggregate gerekli", "First paint needs the full aggregate"), parseChecked(plannerValues, "firstPaintNeedsFullAggregate", false))
                + checkboxInput("globalSortedScreen", localized(normalizedLanguage, "Global sıralı ekran var", "There is a global sorted screen"), parseChecked(plannerValues, "globalSortedScreen", false))
                + checkboxInput("thresholdOrRangeScreen", localized(normalizedLanguage, "Threshold / range driven ekran var", "There is a threshold / range-driven screen"), parseChecked(plannerValues, "thresholdOrRangeScreen", false))
                + checkboxInput("fullHistoryMustStayHot", localized(normalizedLanguage, "Tüm tarihçe Redis'te sıcak kalmalı", "Full history must stay hot in Redis"), parseChecked(plannerValues, "fullHistoryMustStayHot", false))
                + checkboxInput("currentOrmUsesEagerLoading", localized(normalizedLanguage, "Mevcut ORM yolu eager-loading ağırlıklı", "Current ORM route is eager-loading heavy"), parseChecked(plannerValues, "currentOrmUsesEagerLoading", true))
                + checkboxInput("dryRun", localized(normalizedLanguage, "Önce dry run yap, Redis'i değiştirme", "Start with a dry run and do not mutate Redis"), parseChecked(plannerValues, "dryRun", false))
                + "</div></div></details>"
                + "<details class=\"full planner-advanced\" data-planner-mode-min=\"advanced\"><summary>" + escapeHtml(localized(normalizedLanguage, "İleri scaffold ve comparison ayarları", "Advanced scaffold and comparison settings")) + "</summary><div class=\"planner-advanced-body\"><div class=\"planner-form-grid\">"
                + fieldInput("basePackage", localized(normalizedLanguage, "Üretilecek package", "Generated package"), "com.example.cachedb.migration", "", plannerValues.get("basePackage"))
                + fieldInput("rootClassName", localized(normalizedLanguage, "Kök entity sınıf adı", "Root entity class name"), "CustomerEntity", "", plannerValues.get("rootClassName"))
                + fieldInput("childClassName", localized(normalizedLanguage, "Çocuk entity sınıf adı", "Child entity class name"), "OrderEntity", "", plannerValues.get("childClassName"))
                + fieldInput("relationLoaderClassName", localized(normalizedLanguage, "Relation loader sınıf adı", "Relation loader class name"), "CustomerOrderRelationBatchLoader", "", plannerValues.get("relationLoaderClassName"))
                + fieldInput("projectionSupportClassName", localized(normalizedLanguage, "Projection destek sınıf adı", "Projection support class name"), "CustomerOrderReadModels", "", plannerValues.get("projectionSupportClassName"))
                + fieldInput("comparisonSampleRootId", localized(normalizedLanguage, "Karşılaştırma örnek kök id", "Comparison sample root id"), localized(normalizedLanguage, "Boş bırakırsan sistem seçer", "Leave blank to auto-pick"), "", plannerValues.get("comparisonSampleRootId"))
                + fieldInput("comparisonSampleRootCount", localized(normalizedLanguage, "Karşılaştırılacak örnek kök sayısı", "Comparison sample root count"), "3", "", plannerValues.get("comparisonSampleRootCount"))
                + fieldInput("comparisonPageSize", localized(normalizedLanguage, "Karşılaştırma sayfa boyutu", "Comparison page size"), "100", "", plannerValues.get("comparisonPageSize"))
                + fieldInput("comparisonWarmupIterations", localized(normalizedLanguage, "Warm-up iterasyonu", "Warm-up iterations"), "2", "", plannerValues.get("comparisonWarmupIterations"))
                + fieldInput("comparisonMeasuredIterations", localized(normalizedLanguage, "Ölçüm iterasyonu", "Measured iterations"), "8", "", plannerValues.get("comparisonMeasuredIterations"))
                + fieldInput("comparisonProjectionName", localized(normalizedLanguage, "Projection override adı", "Projection override name"), localized(normalizedLanguage, "Opsiyonel", "Optional"), "", plannerValues.get("comparisonProjectionName"))
                + checkboxInput("includeRelationLoader", localized(normalizedLanguage, "Relation loader iskeletini üret", "Generate relation loader skeleton"), parseChecked(plannerValues, "includeRelationLoader", true))
                + checkboxInput("includeProjectionSkeleton", localized(normalizedLanguage, "Projection iskeletini üret", "Generate projection skeleton"), parseChecked(plannerValues, "includeProjectionSkeleton", true))
                + checkboxInput("warmBeforeCompare", localized(normalizedLanguage, "Karşılaştırmadan önce warm çalıştır", "Run warm before comparison"), parseChecked(plannerValues, "warmBeforeCompare", false))
                + textareaInput("comparisonBaselineSql", localized(normalizedLanguage, "Baseline SQL override", "Baseline SQL override"), localized(normalizedLanguage, "Varsayılan türetilmiş SQL yerine kullanılır", "Used instead of the derived baseline SQL"), "full", plannerValues.get("comparisonBaselineSql"))
                + "</div></div></details>"
                + "</div>"
                + "<div class=\"planner-chip-row\">"
                + "<span class=\"planner-chip\">" + escapeHtml(localized(normalizedLanguage, "Amaç: bounded Redis hot set", "Goal: bounded Redis hot set")) + "</span>"
                + "<span class=\"planner-chip\">" + escapeHtml(localized(normalizedLanguage, "Amaç: projection-first first paint", "Goal: projection-first first paint")) + "</span>"
                + "<span class=\"planner-chip\">" + escapeHtml(localized(normalizedLanguage, "Amaç: staging comparison", "Goal: staging comparison")) + "</span>"
                + "</div>"
                + "<div class=\"planner-actions\"><button type=\"submit\" class=\"btn btn-primary\">" + escapeHtml(localized(normalizedLanguage, "Planı Oluştur", "Generate Plan")) + "</button>"
                + "<button id=\"plannerDefaults\" type=\"button\" class=\"btn btn-outline-secondary\">" + escapeHtml(localized(normalizedLanguage, "Örnek Değerleri Yükle", "Load Example Defaults")) + "</button></div>"
                + "<div id=\"plannerStatus\" class=\"planner-status\">" + plannerStatusText + "</div>"
                + "</form></div></div>"
                + "<div class=\"planner-results\">"
                + "<div id=\"plannerEmpty\" class=\"planner-empty\">" + escapeHtml(localized(normalizedLanguage, "Henüz plan üretilmedi. Bu yüzey sana sıcak pencere sınırını, projection gerekliliğini, warm-up sırasını ve staging karşılaştırma kontrol listesini çıkaracak.", "No plan yet. This surface will produce the hot-window boundary, projection requirement, warm-up sequence, and staging comparison checklist.")) + "</div>"
                + "<div id=\"plannerResults\" class=\"d-none\">"
                + "<div id=\"plannerPlanPanel\" class=\"result-card\"><div class=\"result-card-header\">" + escapeHtml(localized(normalizedLanguage, "2. Hedef mimari kararı", "2. Target architecture decision")) + "</div><div class=\"result-card-body\">"
                + "<div class=\"result-grid\">"
                + metricShell("plannerSurface", localized(normalizedLanguage, "Önerilen yüzey", "Recommended surface"))
                + metricShell("plannerWindow", localized(normalizedLanguage, "Önerilen sıcak pencere", "Recommended hot window"))
                + metricShell("plannerProjection", localized(normalizedLanguage, "Projection kararı", "Projection decision"))
                + metricShell("plannerRanking", localized(normalizedLanguage, "Ranked projection", "Ranked projection"))
                + "</div>"
                + "<div class=\"row g-3 mt-1\"><div class=\"col-12 col-xl-6\"><div class=\"result-metric\"><div class=\"result-metric-label\">" + escapeHtml(localized(normalizedLanguage, "Redis yerleşimi", "Redis placement")) + "</div><div id=\"plannerRedisPlacement\" class=\"result-metric-value\"></div></div></div>"
                + "<div class=\"col-12 col-xl-6\"><div class=\"result-metric\"><div class=\"result-metric-label\">" + escapeHtml(localized(normalizedLanguage, "PostgreSQL yerleşimi", "PostgreSQL placement")) + "</div><div id=\"plannerPostgresPlacement\" class=\"result-metric-value\"></div></div></div></div>"
                + "</div></div>"
                + resultListCard("plannerReasoningCard", localized(normalizedLanguage, "Neden bu karar çıktı?", "Why this decision?"), "plannerReasoning")
                + resultListCard("plannerArtifactsCard", localized(normalizedLanguage, "Hangi yüzeyler oluşmalı?", "Which surfaces should exist?"), "plannerRedisArtifacts")
                + resultListCard("plannerPostgresCard", localized(normalizedLanguage, "PostgreSQL'de ne kalmalı?", "What stays in PostgreSQL?"), "plannerPostgresArtifacts")
                + resultListCard("plannerApiCard", localized(normalizedLanguage, "Route şekli nasıl olmalı?", "What should the route shape look like?"), "plannerApiShapes")
                + "<div class=\"result-card\"><div class=\"result-card-header\">" + escapeHtml(localized(normalizedLanguage, "3. Warm-up planı", "3. Warm-up plan")) + "</div><div class=\"result-card-body\"><div id=\"plannerWarmSteps\"></div></div></div>"
                + "<div class=\"result-card\"><div class=\"result-card-header\">" + escapeHtml(localized(normalizedLanguage, "4. Staging karşılaştırma planı", "4. Staging comparison plan")) + "</div><div class=\"result-card-body\"><div id=\"plannerComparisonSummary\" class=\"planner-status mb-3\"></div><div id=\"plannerComparisonChecks\"></div></div></div>"
                + "<div class=\"result-card\" data-planner-mode-min=\"intermediate\"><div class=\"result-card-header\">" + escapeHtml(localized(normalizedLanguage, "5. Örnek warm SQL", "5. Sample warm SQL")) + "</div><div class=\"result-card-body\">"
                + "<div class=\"small text-muted fw-semibold mb-2\">" + escapeHtml(localized(normalizedLanguage, "Çocuk sıcak pencere SQL'i", "Child hot-window SQL")) + "</div><pre id=\"plannerWarmSql\" class=\"result-pre\"></pre>"
                + "<div class=\"small text-muted fw-semibold mt-3 mb-2\">" + escapeHtml(localized(normalizedLanguage, "İlgili kök satırları için SQL şablonu", "Referenced root SQL template")) + "</div><pre id=\"plannerRootWarmSql\" class=\"result-pre\"></pre>"
                + "</div></div>"
                + "<div id=\"plannerWarningsCard\" class=\"result-card d-none\"><div class=\"result-card-header\">" + escapeHtml(localized(normalizedLanguage, "Dikkat edilmesi gerekenler", "Things to watch")) + "</div><div class=\"result-card-body\"><div id=\"plannerWarnings\"></div></div></div>"
                + "<div id=\"plannerWarmPanel\" class=\"result-card\"><div class=\"result-card-header\">" + escapeHtml(localized(normalizedLanguage, "6. Staging warm execution", "6. Staging warm execution")) + "</div><div class=\"result-card-body\">"
                + "<div class=\"planner-actions\"><button id=\"plannerWarmAction\" type=\"button\" class=\"btn btn-success\" disabled>" + escapeHtml(localized(normalizedLanguage, "Warm'ı Çalıştır", "Run Warm")) + "</button>"
                + "<button id=\"plannerWarmPreviewAction\" type=\"button\" class=\"btn btn-outline-secondary\" disabled>" + escapeHtml(localized(normalizedLanguage, "Dry Run Çalıştır", "Run Dry Run")) + "</button></div>"
                + "<div id=\"plannerWarmStatus\" class=\"planner-status mt-3\">" + escapeHtml(localized(normalizedLanguage, "Önce planı üret. Sonra staging warm çalıştırabilirsin.", "Generate the plan first. Then you can run the staging warm execution.")) + "</div>"
                + "<div id=\"plannerWarmResults\" class=\"d-none mt-3\">"
                + "<div class=\"result-grid\">"
                + metricShell("plannerWarmChildRows", localized(normalizedLanguage, "Çocuk satırları", "Child rows"))
                + metricShell("plannerWarmRootRows", localized(normalizedLanguage, "Kök satırları", "Root rows"))
                + metricShell("plannerWarmSkippedRows", localized(normalizedLanguage, "Atlanan silinmiş satırlar", "Skipped deleted rows"))
                + metricShell("plannerWarmDuration", localized(normalizedLanguage, "Süre", "Duration"))
                + metricShell("plannerWarmReferencedRoots", localized(normalizedLanguage, "İlgili kök id sayısı", "Referenced root ids"))
                + metricShell("plannerWarmMissingRoots", localized(normalizedLanguage, "Eksik kök id sayısı", "Missing root ids"))
                + "</div>"
                + "<div class=\"small text-muted fw-semibold mt-3 mb-2\">" + escapeHtml(localized(normalizedLanguage, "Warm notları", "Warm notes")) + "</div><div id=\"plannerWarmNotes\"></div>"
                + "<div class=\"small text-muted fw-semibold mt-3 mb-2\">" + escapeHtml(localized(normalizedLanguage, "Çalışan çocuk SQL'i", "Executed child SQL")) + "</div><pre id=\"plannerWarmChildSql\" class=\"result-pre\"></pre>"
                + "<div class=\"small text-muted fw-semibold mt-3 mb-2\">" + escapeHtml(localized(normalizedLanguage, "Kök SQL şablonu", "Root SQL template")) + "</div><pre id=\"plannerWarmRootSql\" class=\"result-pre\"></pre>"
                + "</div></div></div>"
                + "<div id=\"plannerScaffoldPanel\" class=\"result-card\" data-planner-mode-min=\"intermediate\"><div class=\"result-card-header\">" + escapeHtml(localized(normalizedLanguage, "7. Generated CacheDB scaffold", "7. Generated CacheDB scaffold")) + "</div><div class=\"result-card-body\">"
                + "<div class=\"planner-actions\"><button id=\"plannerScaffoldAction\" type=\"button\" class=\"btn btn-outline-primary\" disabled>" + escapeHtml(localized(normalizedLanguage, "İskeleti Üret", "Generate Scaffold")) + "</button></div>"
                + "<div id=\"plannerScaffoldStatus\" class=\"planner-status mt-3\">" + escapeHtml(localized(normalizedLanguage, "Planı üretince entity ve binding iskeletlerini bu ekranda görebilirsin.", "After generating the plan, you can preview the entity and binding skeletons here.")) + "</div>"
                + "<div id=\"plannerScaffoldResults\" class=\"d-none mt-3\">"
                + "<div class=\"result-grid\">"
                + metricShell("plannerScaffoldBasePackage", localized(normalizedLanguage, "Base package", "Base package"))
                + metricShell("plannerScaffoldFileCount", localized(normalizedLanguage, "Üretilen dosya", "Generated files"))
                + "</div>"
                + "<div class=\"small text-muted fw-semibold mt-3 mb-2\">" + escapeHtml(localized(normalizedLanguage, "İskelet notları", "Scaffold notes")) + "</div><div id=\"plannerScaffoldNotes\"></div>"
                + "<div class=\"small text-muted fw-semibold mt-3 mb-2\">" + escapeHtml(localized(normalizedLanguage, "Üretilen dosyalar", "Generated files")) + "</div><div id=\"plannerScaffoldFiles\"></div>"
                + "<div class=\"small text-muted fw-semibold mt-3 mb-2\">" + escapeHtml(localized(normalizedLanguage, "Scaffold uyarıları", "Scaffold warnings")) + "</div><div id=\"plannerScaffoldWarnings\"></div>"
                + "</div></div></div>"
                + "<div id=\"plannerComparePanel\" class=\"result-card\"><div class=\"result-card-header\">" + escapeHtml(localized(normalizedLanguage, "8. Side-by-side comparison", "8. Side-by-side comparison")) + "</div><div class=\"result-card-body\">"
                + "<div class=\"planner-actions\"><button id=\"plannerCompareAction\" type=\"button\" class=\"btn btn-outline-dark\" disabled>" + escapeHtml(localized(normalizedLanguage, "Karşılaştırmayı Çalıştır", "Run Comparison")) + "</button>"
                + "<button id=\"plannerCompareDownloadReportAction\" type=\"button\" class=\"btn btn-outline-secondary\" disabled>" + escapeHtml(localized(normalizedLanguage, "Raporu İndir", "Download Report")) + "</button></div>"
                + "<div id=\"plannerCompareStatus\" class=\"planner-status mt-3\">" + escapeHtml(localized(normalizedLanguage, "Planı üretince PostgreSQL ve CacheDB route'unu yan yana ölçebilirsin.", "After generating the plan, you can measure the PostgreSQL and CacheDB routes side by side.")) + "</div>"
                + "<div id=\"plannerCompareResults\" class=\"d-none mt-3\">"
                + "<div class=\"result-grid\">"
                + metricShell("plannerCompareRoute", localized(normalizedLanguage, "CacheDB route", "CacheDB route"))
                + metricShell("plannerCompareExactMatches", localized(normalizedLanguage, "Tam eşleşen örnek", "Exact sample matches"))
                + metricShell("plannerCompareBaselineAvg", localized(normalizedLanguage, "Baseline ort.", "Baseline avg"))
                + metricShell("plannerCompareBaselineP95", localized(normalizedLanguage, "Baseline p95", "Baseline p95"))
                + metricShell("plannerCompareCacheAvg", localized(normalizedLanguage, "Cache avg", "Cache avg"))
                + metricShell("plannerCompareCacheP95", localized(normalizedLanguage, "Cache p95", "Cache p95"))
                + "</div>"
                + "<div class=\"result-card mt-3\"><div class=\"result-card-header\">" + escapeHtml(localized(normalizedLanguage, "Geçiş değerlendirmesi", "Migration assessment")) + "</div><div class=\"result-card-body\">"
                + "<div class=\"result-grid\">"
                + metricShell("plannerCompareAssessmentStatus", localized(normalizedLanguage, "Geçiş kararı", "Cutover decision"))
                + metricShell("plannerCompareAssessmentParity", localized(normalizedLanguage, "Veri eşleşmesi", "Data parity"))
                + metricShell("plannerCompareAssessmentAvgRatio", localized(normalizedLanguage, "Ort. oran", "Avg ratio"))
                + metricShell("plannerCompareAssessmentP95Ratio", localized(normalizedLanguage, "p95 oran", "p95 ratio"))
                + "</div>"
                + "<div id=\"plannerCompareAssessmentSummary\" class=\"planner-status mt-3\"></div>"
                + "<div id=\"plannerCompareAssessmentDecision\" class=\"small text-muted mt-2\"></div>"
                + "<div class=\"small text-muted fw-semibold mt-3 mb-2\">" + escapeHtml(localized(normalizedLanguage, "Güçlü sinyaller", "Positive signals")) + "</div><div id=\"plannerCompareAssessmentStrengths\"></div>"
                + "<div class=\"small text-muted fw-semibold mt-3 mb-2\">" + escapeHtml(localized(normalizedLanguage, "Cutover blokajları", "Cutover blockers")) + "</div><div id=\"plannerCompareAssessmentBlockers\"></div>"
                + "<div class=\"small text-muted fw-semibold mt-3 mb-2\">" + escapeHtml(localized(normalizedLanguage, "Sonraki adımlar", "Next steps")) + "</div><div id=\"plannerCompareAssessmentNextSteps\"></div>"
                + "</div></div>"
                + "<div class=\"small text-muted fw-semibold mt-3 mb-2\">" + escapeHtml(localized(normalizedLanguage, "Baseline SQL", "Baseline SQL")) + "</div><pre id=\"plannerCompareBaselineSql\" class=\"result-pre\"></pre>"
                + "<div class=\"small text-muted fw-semibold mt-3 mb-2\">" + escapeHtml(localized(normalizedLanguage, "Örnek karşılaştırmalar", "Sample comparisons")) + "</div><div id=\"plannerCompareSamples\"></div>"
                + "<div class=\"small text-muted fw-semibold mt-3 mb-2\">" + escapeHtml(localized(normalizedLanguage, "Karşılaştırma notları", "Comparison notes")) + "</div><div id=\"plannerCompareNotes\"></div>"
                + "<div class=\"small text-muted fw-semibold mt-3 mb-2\">" + escapeHtml(localized(normalizedLanguage, "Karşılaştırma uyarıları", "Comparison warnings")) + "</div><div id=\"plannerCompareWarnings\"></div>"
                + "</div></div></div>"
                + "</div></div>"
                + "</section></main>"
                + "<script>"
                + "(function(){"
                + "const apiBase='" + apiBasePath + "';"
                + "const bootstrapDiscovery=" + bootstrapDiscoveryJson + ";"
                + "const bootstrapDemoResult=" + bootstrapDemoJson + ";"
                + "const bootstrapPlanResult=" + bootstrapPlanJson + ";"
                + "const bootstrapPlannerForm=" + bootstrapPlannerFormJson + ";"
                + "const form=document.getElementById('plannerForm');"
                + "const status=document.getElementById('plannerStatus');"
                + "const defaultsButton=document.getElementById('plannerDefaults');"
                + "const emptyState=document.getElementById('plannerEmpty');"
                + "const results=document.getElementById('plannerResults');"
                + "const warmButton=document.getElementById('plannerWarmAction');"
                + "const warmPreviewButton=document.getElementById('plannerWarmPreviewAction');"
                + "const warmStatus=document.getElementById('plannerWarmStatus');"
                + "const warmResults=document.getElementById('plannerWarmResults');"
                + "const scaffoldButton=document.getElementById('plannerScaffoldAction');"
                + "const scaffoldStatus=document.getElementById('plannerScaffoldStatus');"
                + "const scaffoldResults=document.getElementById('plannerScaffoldResults');"
                + "const compareButton=document.getElementById('plannerCompareAction');"
                + "const compareReportButton=document.getElementById('plannerCompareDownloadReportAction');"
                + "const compareStatus=document.getElementById('plannerCompareStatus');"
                + "const compareResults=document.getElementById('plannerCompareResults');"
                + "const demoBootstrapButton=document.getElementById('plannerDemoBootstrapAction');"
                + "const demoStatus=document.getElementById('plannerDemoStatus');"
                + "const demoResults=document.getElementById('plannerDemoResults');"
                + "const discoverButton=document.getElementById('plannerDiscoverAction');"
                + "const discoveryStatus=document.getElementById('plannerDiscoveryStatus');"
                + "const routePresetField=form.elements.namedItem('routePreset');"
                + "const presetHelp=document.getElementById('plannerPresetHelp');"
                + "const modeButtons=Array.from(document.querySelectorAll('[data-planner-mode]'));"
                + "const modeSummary=document.getElementById('plannerModeSummary');"
                + "const progressTitle=document.getElementById('plannerProgressTitle');"
                + "const progressDetail=document.getElementById('plannerProgressDetail');"
                + "const progressPercent=document.getElementById('plannerProgressPercent');"
                + "const progressFill=document.getElementById('plannerProgressFill');"
                + "const stepButtons=Array.from(document.querySelectorAll('[data-planner-step-target]'));"
                + "let demoDescriptor=null;"
                + "let templateDefaults=null;"
                + "let discoveredTables=[];"
                + "let discoveryLookup={};"
                + "let discoverySuggestions=[];"
                + "let lastComparisonResult=null;"
                + "let plannerMode='beginner';"
                + "function escapeHtml(value){return String(value??'').replace(/[&<>\"']/g,function(ch){return({'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;',\"'\":'&#39;'})[ch];});}"
                + "function setStatus(message){status.textContent=message;}"
                + "function setDemoStatus(message){if(demoStatus){demoStatus.textContent=message;}}"
                + "function setDiscoveryStatus(message){if(discoveryStatus){discoveryStatus.textContent=message;}}"
                + "function plannerModeRank(mode){switch(String(mode||'beginner')){case 'advanced':return 2;case 'intermediate':return 1;default:return 0;}}"
                + "function plannerModeSummaryMessage(mode){switch(String(mode||'beginner')){case 'advanced':return '" + escapeJs(localized(normalizedLanguage, "İleri mod açık. Ham SQL, scaffold ve comparison override alanlarının tamamı görünür durumda.", "Advanced mode is active. Raw SQL, scaffold, and comparison override inputs are all visible.")) + "';case 'intermediate':return '" + escapeJs(localized(normalizedLanguage, "Orta mod açık. Warm ve scaffold yüzeyleri daha görünür, ama ekran hâlâ rehberli ilerliyor.", "Intermediate mode is active. Warm and scaffold are more visible while the flow stays guided.")) + "';default:return '" + escapeJs(localized(normalizedLanguage, "Başlangıç modu açık. Sadece gerekli seçimleri gösterip seni keşif → plan → warm → compare akışında tutar.", "Beginner mode is active. It shows only the essential choices and keeps you on the discovery → plan → warm → compare flow.")) + "';}}"
                + "function applyPlannerMode(mode){plannerMode=String(mode||'beginner');modeButtons.forEach(function(button){button.classList.toggle('is-active',button.dataset.plannerMode===plannerMode);});Array.from(document.querySelectorAll('[data-planner-mode-min]')).forEach(function(element){const required=element.dataset.plannerModeMin||'beginner';element.classList.toggle('planner-hidden-by-mode', plannerModeRank(plannerMode)<plannerModeRank(required));});if(modeSummary){modeSummary.textContent=plannerModeSummaryMessage(plannerMode);}updatePlannerProgress();}"
                + "function formatLatency(nanos){const value=Number(nanos||0);if(!Number.isFinite(value)||value<=0){return '0 ns';}if(value>=1000000){return (value/1000000).toFixed(2)+' ms';}if(value>=1000){return (value/1000).toFixed(2)+' µs';}return String(Math.round(value))+' ns';}"
                + "async function fetchJson(url,options){const response=await fetch(url,options);const text=await response.text();let payload=null;try{payload=JSON.parse(text);}catch(ignore){}if(!response.ok){throw new Error((payload&&payload.error)||text||'request failed');}return payload||{};}"
                + "function plannerField(name){return form.elements.namedItem(name);}"
                + "function normalizeValue(value){return String(value||'').trim().toLowerCase();}"
                + "function surfaceValue(item){return item&&((item.registeredEntityName&&String(item.registeredEntityName).trim())||item.qualifiedTableName||item.tableName)||'';}"
                + "function isView(item){return String((item&&item.objectType)||'').toUpperCase()==='VIEW';}"
                + "function rebuildDiscoveryLookup(){discoveryLookup={};(discoveredTables||[]).forEach(function(item,index){const aliases=[surfaceValue(item),item&&item.qualifiedTableName,item&&item.tableName,item&&item.registeredEntityName].filter(Boolean);aliases.forEach(function(alias){discoveryLookup[normalizeValue(alias)]=index;});});}"
                + "function resolveDiscoveredTable(surface){const index=discoveryLookup[normalizeValue(surface)];return index===undefined?null:(discoveredTables[index]||null);}"
                + "function populateSelect(field, options, placeholder, selectedValue){if(!field){return;}const current=selectedValue===undefined?field.value:selectedValue;let entries=(options||[]).filter(function(option){return option&&option.value;});const hasCurrent=current&&entries.some(function(option){return option.value===current;});if(current&&!hasCurrent){entries=[{value:current,label:current+' · " + escapeJs(localized(normalizedLanguage, "manuel giriş", "manual entry")) + "'}].concat(entries);}field.innerHTML='<option value=\"\">'+escapeHtml(placeholder||'')+'</option>'+entries.map(function(option){return '<option value=\"'+escapeHtml(option.value)+'\"'+(option.value===current?' selected':'')+'>'+escapeHtml(option.label||option.value)+'</option>';}).join('');}"
                + "function discoveredSurfaceOptions(includeViews){return (discoveredTables||[]).filter(function(item){return includeViews || !isView(item);}).map(function(item){const badges=[];if(isView(item)){badges.push('" + escapeJs(localized(normalizedLanguage, "view", "view")) + "');}if(item.registeredEntityName){badges.push('" + escapeJs(localized(normalizedLanguage, "entity", "entity")) + ": '+item.registeredEntityName);}return {value:surfaceValue(item),label:item.qualifiedTableName+(badges.length?(' · '+badges.join(' · ')):'')};});}"
                + "function sortColumnPriority(column){if(!column){return 0;}if(column.primaryKey){return 10;}if(column.temporal){return 120;}if(column.numeric){return 70;}return 20;}"
                + "function discoveredColumnOptions(table, mode){if(!table){return [];}const columns=(table.columns||[]).slice();columns.sort(function(left,right){let leftScore=0;let rightScore=0;if(mode==='pk'){leftScore=left&&left.primaryKey?100:0;rightScore=right&&right.primaryKey?100:0;}else if(mode==='relation'){leftScore=left&&left.foreignKey?100:0;rightScore=right&&right.foreignKey?100:0;}else if(mode==='sort'){leftScore=sortColumnPriority(left);rightScore=sortColumnPriority(right);}if(leftScore!==rightScore){return rightScore-leftScore;}return String(left.name||'').localeCompare(String(right.name||''));});return columns.map(function(column){const tags=[];if(column.primaryKey){tags.push('PK');}if(column.foreignKey){tags.push('FK');}if(column.temporal){tags.push('" + escapeJs(localized(normalizedLanguage, "zamansal", "temporal")) + "');}if(column.numeric){tags.push('" + escapeJs(localized(normalizedLanguage, "sayısal", "numeric")) + "');}return {value:column.name,label:column.name+(tags.length?(' · '+tags.join(' · ')):'')};});}"
                + "function discoveredRelationOptions(rootTable, childTable){const rootValue=surfaceValue(rootTable);const childValue=surfaceValue(childTable);const suggestions=(discoverySuggestions||[]).filter(function(item){const rootMatches=[item.rootSurface,item.rootEntityName].filter(Boolean).some(function(value){return normalizeValue(value)===normalizeValue(rootValue);});const childMatches=[item.childSurface,item.childEntityName].filter(Boolean).some(function(value){return normalizeValue(value)===normalizeValue(childValue);});return rootMatches&&childMatches;});const optionMap={};suggestions.forEach(function(item){if(item.relationColumn){optionMap[item.relationColumn]={value:item.relationColumn,label:item.relationColumn+' · " + escapeJs(localized(normalizedLanguage, "önerilen FK", "suggested FK")) + "'};}});(childTable&&childTable.columns||[]).forEach(function(column){if(column&&column.foreignKey&&!optionMap[column.name]){optionMap[column.name]={value:column.name,label:column.name+' · FK'};}});return Object.keys(optionMap).map(function(key){return optionMap[key];});}"
                + "function syncDiscoverySelectors(){const rootField=plannerField('rootTableOrEntity');const childField=plannerField('childTableOrEntity');populateSelect(rootField,discoveredSurfaceOptions(false),'" + escapeJs(localized(normalizedLanguage, "Kök tabloyu keşiften seç", "Choose a root table from discovery")) + "',rootField&&rootField.value);populateSelect(childField,discoveredSurfaceOptions(true),'" + escapeJs(localized(normalizedLanguage, "Çocuk yüzeyi keşiften seç", "Choose a child surface from discovery")) + "',childField&&childField.value);const rootTable=resolveDiscoveredTable(rootField&&rootField.value);const childTable=resolveDiscoveredTable(childField&&childField.value);populateSelect(plannerField('rootPrimaryKeyColumn'),discoveredColumnOptions(rootTable,'pk'),'" + escapeJs(localized(normalizedLanguage, "Kök seçimine göre dolacak", "Filled from the selected root")) + "',plannerField('rootPrimaryKeyColumn')&&plannerField('rootPrimaryKeyColumn').value);populateSelect(plannerField('childPrimaryKeyColumn'),discoveredColumnOptions(childTable,'pk'),'" + escapeJs(localized(normalizedLanguage, "Çocuk seçimine göre dolacak", "Filled from the selected child")) + "',plannerField('childPrimaryKeyColumn')&&plannerField('childPrimaryKeyColumn').value);populateSelect(plannerField('relationColumn'),discoveredRelationOptions(rootTable,childTable),'" + escapeJs(localized(normalizedLanguage, "İlişki kolonu seç", "Choose the relation column")) + "',plannerField('relationColumn')&&plannerField('relationColumn').value);populateSelect(plannerField('sortColumn'),discoveredColumnOptions(childTable,'sort'),'" + escapeJs(localized(normalizedLanguage, "Liste sıralama kolonunu seç", "Choose the list sort column")) + "',plannerField('sortColumn')&&plannerField('sortColumn').value);}"
                + "function plannerFlags(){const rootValue=plannerField('rootTableOrEntity')&&plannerField('rootTableOrEntity').value;const childValue=plannerField('childTableOrEntity')&&plannerField('childTableOrEntity').value;const sortValue=plannerField('sortColumn')&&plannerField('sortColumn').value;return {discovered:(discoveredTables||[]).length>0 || (discoverySuggestions||[]).length>0,configured:!!(rootValue&&childValue&&sortValue),planned:!results.classList.contains('d-none'),warmed:!!(warmResults&&!warmResults.classList.contains('d-none')),compared:!!(compareResults&&!compareResults.classList.contains('d-none'))};}"
                + "function plannerProgressDescriptor(flags){if(!flags.discovered){return {step:1,percent:8,title:'" + escapeJs(localized(normalizedLanguage, "1. adım: Şemayı keşfet", "Step 1: Discover the schema")) + "',detail:'" + escapeJs(localized(normalizedLanguage, "PostgreSQL şemasını keşfedip kök/çocuk adaylarını görünür hale getir.", "Discover the PostgreSQL schema and surface root/child candidates.")) + "'};}if(!flags.configured){return {step:2,percent:28,title:'" + escapeJs(localized(normalizedLanguage, "2. adım: Route'u seç", "Step 2: Select the route")) + "',detail:'" + escapeJs(localized(normalizedLanguage, "Kök tabloyu, çocuk yüzeyi ve ekran tipini seç. Başlangıç modu bunu en az alanla yapar.", "Choose the root table, child surface, and screen profile. Beginner mode keeps this minimal.")) + "'};}if(!flags.planned){return {step:3,percent:52,title:'" + escapeJs(localized(normalizedLanguage, "3. adım: Planı üret", "Step 3: Generate the plan")) + "',detail:'" + escapeJs(localized(normalizedLanguage, "Projection, ranked surface ve bounded sıcak pencere kararı burada netleşir.", "Projection, ranked surface, and bounded hot-window decisions become explicit here.")) + "'};}if(!flags.warmed){return {step:4,percent:76,title:'" + escapeJs(localized(normalizedLanguage, "4. adım: Warm çalıştır", "Step 4: Run warm")) + "',detail:'" + escapeJs(localized(normalizedLanguage, "Dry run ile başlayıp staging sıcak setini PostgreSQL'den besle.", "Start with a dry run, then populate the staging hot set from PostgreSQL.")) + "'};}if(!flags.compared){return {step:5,percent:90,title:'" + escapeJs(localized(normalizedLanguage, "5. adım: Compare ve rapor", "Step 5: Compare and report")) + "',detail:'" + escapeJs(localized(normalizedLanguage, "CacheDB ile mevcut route'u yan yana ölç, assessment'ı oku ve migration raporunu indir.", "Measure CacheDB and the existing route side by side, read the assessment, and download the migration report.")) + "'};}return {step:5,percent:100,title:'" + escapeJs(localized(normalizedLanguage, "Akış tamamlandı", "Flow completed")) + "',detail:'" + escapeJs(localized(normalizedLanguage, "Discovery, plan, warm ve compare zinciri tamam. Cutover readiness raporu hazır.", "The discovery, plan, warm, and compare chain is complete. The cutover readiness report is ready.")) + "'};}"
                + "function updatePlannerProgress(){const flags=plannerFlags();const descriptor=plannerProgressDescriptor(flags);if(progressTitle){progressTitle.textContent=descriptor.title;}if(progressDetail){progressDetail.textContent=descriptor.detail;}if(progressPercent){progressPercent.textContent=String(descriptor.percent)+'%';}if(progressFill){progressFill.style.width=String(descriptor.percent)+'%';}stepButtons.forEach(function(button){const index=Number(button.dataset.plannerStepIndex||'0');button.classList.toggle('is-complete', index<descriptor.step || (descriptor.step===5&&descriptor.percent===100&&index<=descriptor.step));button.classList.toggle('is-active', descriptor.step===index && descriptor.percent<100);});}"
                + "function updatePresetHelp(){if(!presetHelp||!routePresetField){return;}const preset=routePresetField.value||'timeline';const messages={timeline:'" + escapeJs(localized(normalizedLanguage, "Timeline profili, müşteri başına son kayıtları bounded pencere olarak taşımak için doğru başlangıçtır.", "Timeline is the right starting point when you want a bounded hot window per root.")) + "',leaderboard:'" + escapeJs(localized(normalizedLanguage, "Top-N profili, ranked projection ve global sıralı ekran kararlarını öne çeker.", "Top-N emphasizes ranked projection and globally sorted screens.")) + "',threshold:'" + escapeJs(localized(normalizedLanguage, "Threshold profili, aralık / eşik filtreli ekranlarda projection ve dar pencereyi öne çıkarır.", "Threshold emphasizes projection and narrow windows for threshold / range screens.")) + "',detail-heavy:'" + escapeJs(localized(normalizedLanguage, "Detail ağırlıklı profil, ilk boyamada daha zengin aggregate ihtiyacı olan ekranlar içindir.", "Detail-heavy is for screens that need a richer aggregate on first paint.")) + "'};presetHelp.textContent=messages[preset]||messages.timeline;}"
                + "function applyRoutePreset(preset, silent){const listField=plannerField('listScreen');const fullAggregateField=plannerField('firstPaintNeedsFullAggregate');const globalField=plannerField('globalSortedScreen');const thresholdField=plannerField('thresholdOrRangeScreen');const eagerField=plannerField('currentOrmUsesEagerLoading');const archiveField=plannerField('archiveHistoryRequired');const historyField=plannerField('fullHistoryMustStayHot');const pageField=plannerField('firstPageSize');const windowField=plannerField('hotWindowPerRoot');function setNumeric(field,value){if(field&&(!silent||!field.value)){field.value=value;}}switch(String(preset||'timeline')){case 'leaderboard':if(listField){listField.checked=true;}if(fullAggregateField){fullAggregateField.checked=false;}if(globalField){globalField.checked=true;}if(thresholdField){thresholdField.checked=true;}if(eagerField){eagerField.checked=false;}if(archiveField){archiveField.checked=true;}if(historyField){historyField.checked=false;}setNumeric(pageField,'50');setNumeric(windowField,'500');break;case 'threshold':if(listField){listField.checked=true;}if(fullAggregateField){fullAggregateField.checked=false;}if(globalField){globalField.checked=false;}if(thresholdField){thresholdField.checked=true;}if(eagerField){eagerField.checked=false;}if(archiveField){archiveField.checked=true;}if(historyField){historyField.checked=false;}setNumeric(pageField,'50');setNumeric(windowField,'400');break;case 'detail-heavy':if(listField){listField.checked=false;}if(fullAggregateField){fullAggregateField.checked=true;}if(globalField){globalField.checked=false;}if(thresholdField){thresholdField.checked=false;}if(eagerField){eagerField.checked=false;}if(archiveField){archiveField.checked=true;}if(historyField){historyField.checked=false;}setNumeric(pageField,'25');setNumeric(windowField,'100');break;default:if(listField){listField.checked=true;}if(fullAggregateField){fullAggregateField.checked=false;}if(globalField){globalField.checked=false;}if(thresholdField){thresholdField.checked=false;}if(eagerField){eagerField.checked=true;}if(archiveField){archiveField.checked=true;}if(historyField){historyField.checked=false;}setNumeric(pageField,'100');setNumeric(windowField,'1000');}updatePresetHelp();if(!silent){setStatus('" + escapeJs(localized(normalizedLanguage, "Hazır ekran profili güncellendi. İstersen planı hemen üretebilirsin.", "The prepared screen profile was updated. You can generate the plan right away.")) + "');}}"
                + "function syncPresetFromForm(){if(!routePresetField){return;}const listField=plannerField('listScreen');const fullAggregateField=plannerField('firstPaintNeedsFullAggregate');const globalField=plannerField('globalSortedScreen');const thresholdField=plannerField('thresholdOrRangeScreen');if(fullAggregateField&&fullAggregateField.checked&&!listField.checked){routePresetField.value='detail-heavy';}else if(globalField&&globalField.checked){routePresetField.value='leaderboard';}else if(thresholdField&&thresholdField.checked){routePresetField.value='threshold';}else{routePresetField.value='timeline';}updatePresetHelp();}"
                + "function fillForm(data){Object.entries(data||{}).forEach(function(entry){const name=entry[0];const value=entry[1];const field=form.elements.namedItem(name);if(!field){return;}if(field instanceof RadioNodeList){return;}if(field.type==='checkbox'){field.checked=!!value;}else{field.value=value;}});syncDiscoverySelectors();syncPresetFromForm();updatePlannerProgress();}"
                + "function serializeForm(){const params=new URLSearchParams();Array.from(form.elements).forEach(function(field){if(!field.name){return;}if(field.type==='checkbox'){params.set(field.name, field.checked ? 'true' : 'false');return;}params.set(field.name, field.value || '');});return params;}"
                + "function demoFieldValue(name){const field=document.querySelector('[name=\"'+name+'\"]');return field?String(field.value||''):'';}"
                + "function plannerNavigationHref(overrides){const params=new URLSearchParams(window.location.search||'');serializeForm().forEach(function(value,key){params.set(key,value);});['demoCustomerCount','demoHotCustomerCount','demoMaxOrdersPerCustomer'].forEach(function(name){const value=demoFieldValue(name);if(value){params.set(name,value);}});params.set('lang','" + escapeJs(normalizedLanguage) + "');if(!params.get('v')){params.set('v','" + dashboardInstanceId + "');}params.set('discover','true');Object.entries(overrides||{}).forEach(function(entry){const key=entry[0];const value=entry[1];if(value===undefined||value===null||value===''){params.delete(key);return;}params.set(key,String(value));});return window.location.pathname+'?'+params.toString();}"
                + "function renderList(targetId, items, emptyMessage){const root=document.getElementById(targetId);const fallback=emptyMessage||'" + escapeJs(localized(normalizedLanguage, "Bu bölüm için ek madde üretilmedi.", "No extra items were generated for this section.")) + "';if(!items||!items.length){root.innerHTML='<div class=\"planner-empty\">' + escapeHtml(fallback) + '</div>';return;}root.innerHTML='<ul class=\"result-list\">'+items.map(function(item){return '<li>'+escapeHtml(item)+'</li>';}).join('')+'</ul>';}"
                + "function renderWarmSteps(items){const root=document.getElementById('plannerWarmSteps');root.innerHTML=(items||[]).map(function(step,index){return '<div class=\"result-step\"><div class=\"result-step-title\">'+escapeHtml((index+1)+'. '+step.title)+'</div><div class=\"result-step-summary\">'+escapeHtml(step.summary)+'</div><ul class=\"result-list\">'+(step.tasks||[]).map(function(task){return '<li>'+escapeHtml(task)+'</li>';}).join('')+'</ul></div>';}).join('');}"
                + "function renderComparisonChecks(items){const root=document.getElementById('plannerComparisonChecks');root.innerHTML=(items||[]).map(function(check){return '<div class=\"result-check\"><div class=\"result-step-title\">'+escapeHtml(check.title)+'</div><div class=\"small text-muted mb-2\">'+escapeHtml(check.baseline)+'</div><div class=\"fw-semibold\">'+escapeHtml(check.target)+'</div></div>';}).join('');}"
                + "function renderWarnings(items){const wrapper=document.getElementById('plannerWarningsCard');const root=document.getElementById('plannerWarnings');if(!items||!items.length){wrapper.classList.add('d-none');root.innerHTML='';return;}wrapper.classList.remove('d-none');root.innerHTML=items.map(function(item){return '<div class=\"planner-warning mb-2\">'+escapeHtml(item)+'</div>';}).join('');}"
                + "function renderScaffoldFiles(items){const root=document.getElementById('plannerScaffoldFiles');if(!items||!items.length){root.innerHTML='<div class=\"planner-empty\">'+escapeHtml('" + escapeJs(localized(normalizedLanguage, "Dosya üretilmedi.", "No files were generated.")) + "')+'</div>';return;}root.innerHTML=items.map(function(file){return '<div class=\"result-check mb-3\"><div class=\"result-step-title\">'+escapeHtml(file.fileName)+'</div><div class=\"small text-muted mt-1\">'+escapeHtml((file.relativePath||'')+' · '+(file.description||''))+'</div><pre class=\"result-pre mt-3\">'+escapeHtml(file.content||'')+'</pre></div>';}).join('');}"
                + "function renderComparisonSamples(items){const root=document.getElementById('plannerCompareSamples');if(!items||!items.length){root.innerHTML='<div class=\"planner-empty\">'+escapeHtml('" + escapeJs(localized(normalizedLanguage, "Karşılaştırma örneği oluşmadı.", "No comparison samples were produced.")) + "')+'</div>';return;}root.innerHTML=items.map(function(item){const matchLabel=item.exactMatch?'" + escapeJs(localized(normalizedLanguage, "Tam eşleşme", "Exact match")) + "':'" + escapeJs(localized(normalizedLanguage, "Fark var", "Mismatch")) + "';const tone=item.exactMatch?'#147d64':'#b54708';return '<div class=\"result-check mb-3\"><div class=\"d-flex flex-column flex-lg-row justify-content-between gap-3\"><div><div class=\"result-step-title\">'+escapeHtml(item.sampleLabel)+'</div><div class=\"small text-muted mt-1\">'+escapeHtml('" + escapeJs(localized(normalizedLanguage, "Baseline satır", "Baseline rows")) + ": '+item.baselineRowCount+' · " + escapeJs(localized(normalizedLanguage, "Cache satır", "Cache rows")) + ": '+item.cacheRowCount)+'</div></div><div class=\"fw-semibold\" style=\"color:'+tone+'\">'+escapeHtml(matchLabel)+'</div></div><div class=\"small text-muted mt-3\">" + escapeJs(localized(normalizedLanguage, "Baseline id listesi", "Baseline id list")) + "</div><pre class=\"result-pre\">'+escapeHtml((item.baselineIds||[]).join(', '))+'</pre><div class=\"small text-muted mt-3\">" + escapeJs(localized(normalizedLanguage, "Cache id listesi", "Cache id list")) + "</div><pre class=\"result-pre\">'+escapeHtml((item.cacheIds||[]).join(', '))+'</pre></div>';}).join('');}"
                + "function formatRatio(value){const numeric=Number(value||0);if(!Number.isFinite(numeric)||numeric<=0){return '-';}return numeric.toFixed(2)+'x';}"
                + "function formatAssessmentReadiness(value){switch(String(value||'')){case 'READY':return '" + escapeJs(localized(normalizedLanguage, "Hazır", "Ready")) + "';case 'NEEDS_REVIEW':return '" + escapeJs(localized(normalizedLanguage, "Gözden geçir", "Needs review")) + "';case 'NOT_READY':return '" + escapeJs(localized(normalizedLanguage, "Hazır değil", "Not ready")) + "';default:return value||'-';}}"
                + "function formatAssessmentParity(value){switch(String(value||'')){case 'EXACT':return '" + escapeJs(localized(normalizedLanguage, "Tam eşleşme", "Exact parity")) + "';case 'PARTIAL':return '" + escapeJs(localized(normalizedLanguage, "Kısmi eşleşme", "Partial parity")) + "';case 'MISMATCH':return '" + escapeJs(localized(normalizedLanguage, "Uyumsuz", "Mismatch")) + "';case 'NO_SAMPLES':return '" + escapeJs(localized(normalizedLanguage, "Örnek yok", "No samples")) + "';default:return value||'-';}}"
                + "function renderDemoBootstrap(result){if(!demoResults){return;}demoResults.classList.remove('d-none');setMetric('plannerDemoCustomers',String(result.customerCount||0));setMetric('plannerDemoOrders',String(result.orderCount||0));setMetric('plannerDemoHottestCustomer',String(result.hottestCustomerOrderCount||0));setMetric('plannerDemoViews',String((result.viewNames||[]).length));renderList('plannerDemoNotes',result.notes,'" + escapeJs(localized(normalizedLanguage, "Demo seed notu üretilmedi.", "No demo seed note was generated.")) + "');if(result.plannerDefaults){fillForm(result.plannerDefaults);}}"
                + "function renderDiscovery(result){const suggestionRoot=document.getElementById('plannerDiscoverySuggestions');const tableRoot=document.getElementById('plannerDiscoveryTables');const warnings=result.warnings||[];discoverySuggestions=result.routeSuggestions||[];discoveredTables=result.tables||[];rebuildDiscoveryLookup();syncDiscoverySelectors();const warningHtml=warnings.length?warnings.map(function(item){return '<div class=\"planner-warning mb-2\">'+escapeHtml(item)+'</div>';}).join(''):'';if(discoverySuggestions.length){suggestionRoot.innerHTML=warningHtml+discoverySuggestions.map(function(item,index){const surfaces=[item.rootEntityName||item.rootSurface,item.childEntityName||item.childSurface].filter(Boolean).join(' → ');const sortCandidates=(item.sortCandidates||[]).join(', ');const badges=[];if(item.temporalSortCandidate){badges.push('<span class=\"planner-badge\">" + escapeJs(localized(normalizedLanguage, "zamansal sıra", "temporal sort")) + "</span>');}if(item.rankedSortCandidate){badges.push('<span class=\"planner-badge\">" + escapeJs(localized(normalizedLanguage, "rank adayı", "rank candidate")) + "</span>');}const applyHref=plannerNavigationHref({applySuggestion:index});return '<div class=\"result-check mb-3\"><div class=\"d-flex flex-column flex-lg-row justify-content-between gap-3\"><div><div>'+badges.join('')+'</div><div class=\"result-step-title\">'+escapeHtml(item.label)+'</div><div class=\"small text-muted mt-1\">'+escapeHtml(item.summary)+'</div><div class=\"small mt-2\">'+escapeHtml(surfaces||'')+'</div><div class=\"small text-muted mt-1\">'+escapeHtml('" + escapeJs(localized(normalizedLanguage, "İlişki", "Relation")) + ": '+item.relationColumn+' · " + escapeJs(localized(normalizedLanguage, "Önerilen sıralama", "Suggested sort")) + ": '+item.sortColumn+' DESC')+'</div><div class=\"small text-muted mt-1\">'+escapeHtml('" + escapeJs(localized(normalizedLanguage, "Alternatif sıralama kolonları", "Alternative sort columns")) + ": '+(sortCandidates||'-'))+'</div></div><div><a class=\"btn btn-sm btn-outline-primary\" data-planner-suggestion=\"'+index+'\" href=\"'+escapeHtml(applyHref)+'\">" + escapeJs(localized(normalizedLanguage, "Forma Uygula", "Apply To Form")) + "</a></div></div></div>';}).join('');}else{suggestionRoot.innerHTML=warningHtml+'<div class=\"planner-empty\">'+escapeHtml('" + escapeJs(localized(normalizedLanguage, "Keşiften otomatik route adayı çıkmadı. Yine de aşağıdaki listelerden tablo ve kolon seçerek plan üretebilirsin.", "No automatic route suggestion came out of discovery. You can still build the plan by choosing tables and columns from the lists below.")) + "')+'</div>';}if(discoveredTables.length){tableRoot.innerHTML=discoveredTables.map(function(item,index){const temporal=(item.temporalColumns||[]).join(', ');const foreignKeys=(item.foreignKeyColumns||[]).join(', ');const entityBadge=item.registeredEntityName?'<span class=\"planner-badge entity\">" + escapeJs(localized(normalizedLanguage, "entity", "entity")) + ": '+escapeHtml(item.registeredEntityName)+'</span>':'';const typeBadge='<span class=\"planner-badge '+(isView(item)?'view':'')+'\">'+escapeHtml(item.objectType||'TABLE')+'</span>';const rootHref=plannerNavigationHref({plannerRole:'root',selectObject:index});const childHref=plannerNavigationHref({plannerRole:'child',selectObject:index});const actionButtons=isView(item)?'<a class=\"btn btn-sm btn-outline-secondary\" data-planner-object=\"'+index+'\" data-planner-role=\"child\" href=\"'+escapeHtml(childHref)+'\">" + escapeJs(localized(normalizedLanguage, "Çocuk olarak seç", "Use as child")) + "</a>':'<a class=\"btn btn-sm btn-outline-primary\" data-planner-object=\"'+index+'\" data-planner-role=\"root\" href=\"'+escapeHtml(rootHref)+'\">" + escapeJs(localized(normalizedLanguage, "Kök seç", "Use as root")) + "</a><a class=\"btn btn-sm btn-outline-secondary\" data-planner-object=\"'+index+'\" data-planner-role=\"child\" href=\"'+escapeHtml(childHref)+'\">" + escapeJs(localized(normalizedLanguage, "Çocuk seç", "Use as child")) + "</a>';return '<div class=\"result-check mb-2\"><div>'+typeBadge+entityBadge+'</div><div class=\"result-step-title mt-2\">'+escapeHtml(item.qualifiedTableName)+'</div><div class=\"small text-muted mt-1\">'+escapeHtml('PK: '+(item.primaryKeyColumn||'-')+' · " + escapeJs(localized(normalizedLanguage, "Kolon", "Columns")) + ": '+item.columnCount+' · FK: '+item.importedKeyCount)+'</div><div class=\"small text-muted mt-1\">'+escapeHtml('" + escapeJs(localized(normalizedLanguage, "Zamansal kolonlar", "Temporal columns")) + ": '+(temporal||'-')+' · FK kolonları: '+(foreignKeys||'-'))+'</div><div class=\"planner-object-actions\">'+actionButtons+'</div></div>';}).join('');}else{tableRoot.innerHTML='<div class=\"planner-empty\">'+escapeHtml('" + escapeJs(localized(normalizedLanguage, "Kullanıcı tablosu keşfedilemedi.", "No user tables were discovered.")) + "')+'</div>';}updatePlannerProgress();}"
                + "function applyDiscoverySuggestion(index){const suggestion=discoverySuggestions[index];if(!suggestion||!suggestion.plannerRequest){return;}fillForm(suggestion.plannerRequest);syncPresetFromForm();setStatus('" + escapeJs(localized(normalizedLanguage, "Keşif önerisi forma uygulandı. İstersen değerleri kontrol edip planı üret.", "Discovery suggestion applied to the form. Check the values if you want, then generate the plan.")) + "');updatePlannerProgress();window.scrollTo({top:0,behavior:'smooth'});}"
                + "function applyDiscoveredObject(index, role){const item=discoveredTables[index];if(!item){return;}const target=role==='root'?'rootTableOrEntity':'childTableOrEntity';const field=plannerField(target);if(field){field.value=surfaceValue(item);syncDiscoverySelectors();updatePlannerProgress();setStatus('" + escapeJs(localized(normalizedLanguage, "Keşfedilen nesne forma işlendi. Şimdi diğer yüzeyi ve ekran tipini tamamlayabilirsin.", "The discovered object was applied to the form. You can now complete the other surface and the screen type.")) + "');}}"
                + "async function loadDemoDescriptor(){setDemoStatus('" + escapeJs(localized(normalizedLanguage, "Demo şema açıklaması yükleniyor…", "Loading demo schema description…")) + "');try{const payload=await fetchJson(apiBase + '/api/migration-planner/demo');demoDescriptor=payload||null;if(payload&&payload.available){const demoCustomerField=form.elements.namedItem('demoCustomerCount');const demoHotCustomerField=form.elements.namedItem('demoHotCustomerCount');const demoMaxOrdersField=form.elements.namedItem('demoMaxOrdersPerCustomer');if(demoCustomerField){demoCustomerField.value=payload.defaultCustomerCount||120;}if(demoHotCustomerField){demoHotCustomerField.value=payload.defaultHotCustomerCount||12;}if(demoMaxOrdersField){demoMaxOrdersField.value=payload.defaultMaxOrdersPerCustomer||1500;}setDemoStatus(payload.summary||'" + escapeJs(localized(normalizedLanguage, "Demo seed hazır.", "Demo seed is ready.")) + "');}else{if(demoBootstrapButton){demoBootstrapButton.disabled=true;}setDemoStatus((payload&&payload.summary)||'" + escapeJs(localized(normalizedLanguage, "Bu runtime için demo seed desteklenmiyor.", "Demo seed is not available for this runtime.")) + "');}}catch(error){if(demoBootstrapButton){demoBootstrapButton.disabled=true;}setDemoStatus('" + escapeJs(localized(normalizedLanguage, "Demo şema bilgisi alınamadı: ", "Could not load demo schema info: ")) + "'+error.message);}}"
                + "async function seedDemoSchema(){const params=new URLSearchParams();params.set('demoCustomerCount',String((form.elements.namedItem('demoCustomerCount')||{}).value||'120'));params.set('demoHotCustomerCount',String((form.elements.namedItem('demoHotCustomerCount')||{}).value||'12'));params.set('demoMaxOrdersPerCustomer',String((form.elements.namedItem('demoMaxOrdersPerCustomer')||{}).value||'1500'));const startMessage='" + escapeJs(localized(normalizedLanguage, "Demo PostgreSQL şeması hazırlanıyor…", "Preparing the demo PostgreSQL schema…")) + "';setDemoStatus(startMessage);setStatus(startMessage);try{const payload=await fetchJson(apiBase + '/api/migration-planner/demo',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded; charset=UTF-8'},body:params.toString()});renderDemoBootstrap(payload);if(payload.plannerDefaults){fillForm(payload.plannerDefaults);}await loadDiscovery(true);const hotRoots=(payload.sampleRootIds||[]).join(', ');const doneMessage='" + escapeJs(localized(normalizedLanguage, "Demo şema hazır. Discovery güncellendi ve planner formu seed edilen route ile dolduruldu.", "The demo schema is ready. Discovery was refreshed and the planner form was populated with the seeded route.")) + "';setDemoStatus(doneMessage+(hotRoots?(' " + escapeJs(localized(normalizedLanguage, "Örnek kök id", "Sample root ids")) + ": '+hotRoots):''));setStatus(doneMessage);}catch(error){const message='" + escapeJs(localized(normalizedLanguage, "Demo şema kurulamadı: ", "Demo schema bootstrap failed: ")) + "'+error.message;setDemoStatus(message);setStatus(message);}}"
                + "async function loadDiscovery(manual){const startMessage=manual?'" + escapeJs(localized(normalizedLanguage, "PostgreSQL şeması yeniden keşfediliyor…", "Refreshing PostgreSQL schema discovery…")) + "':'" + escapeJs(localized(normalizedLanguage, "PostgreSQL şeması inceleniyor…", "Inspecting PostgreSQL schema…")) + "';setDiscoveryStatus(startMessage);try{const payload=await fetchJson(apiBase + '/api/migration-planner/discovery');renderDiscovery(payload);setDiscoveryStatus('" + escapeJs(localized(normalizedLanguage, "Şema keşfi hazır. İstersen önerilerden birini forma uygula.", "Schema discovery is ready. You can apply one of the suggestions to the form.")) + "');updatePlannerProgress();}catch(error){setDiscoveryStatus('" + escapeJs(localized(normalizedLanguage, "Şema keşfi başarısız oldu: ", "Schema discovery failed: ")) + "'+error.message);updatePlannerProgress();}}"
                + "function setMetric(id,value){document.getElementById(id).innerHTML=escapeHtml(value);}"
                + "function renderPlan(plan){emptyState.classList.add('d-none');results.classList.remove('d-none');warmResults.classList.add('d-none');setMetric('plannerSurface',plan.recommendedSurface);setMetric('plannerWindow',String(plan.recommendedHotWindowPerRoot)+' " + escapeJs(localized(normalizedLanguage, "satır / kök", "rows / root")) + "');setMetric('plannerProjection',plan.projectionRequired ? '" + escapeJs(localized(normalizedLanguage, "Gerekli", "Required")) + " (' + plan.summaryProjectionName + ')' : '" + escapeJs(localized(normalizedLanguage, "İlk aşamada opsiyonel", "Optional at first")) + "');setMetric('plannerRanking',plan.rankedProjectionRequired ? '" + escapeJs(localized(normalizedLanguage, "Gerekli", "Required")) + " (' + (plan.rankedProjectionName || plan.rankFieldName) + ')' : '" + escapeJs(localized(normalizedLanguage, "Gerekli değil", "Not required")) + "');document.getElementById('plannerRedisPlacement').textContent=plan.redisPlacement;document.getElementById('plannerPostgresPlacement').textContent=plan.postgresPlacement;renderList('plannerReasoning',plan.reasoning);renderList('plannerRedisArtifacts',plan.recommendedRedisArtifacts);renderList('plannerPostgresArtifacts',plan.recommendedPostgresArtifacts);renderList('plannerApiShapes',plan.recommendedApiShapes);renderWarmSteps(plan.warmSteps);document.getElementById('plannerComparisonSummary').textContent=plan.comparisonSummary;renderComparisonChecks(plan.comparisonChecks);document.getElementById('plannerWarmSql').textContent=plan.sampleWarmSql||'';document.getElementById('plannerRootWarmSql').textContent=plan.sampleRootWarmSql||'';renderWarnings(plan.warnings);warmButton.disabled=false;warmPreviewButton.disabled=false;if(scaffoldButton){scaffoldButton.disabled=false;}if(compareButton){compareButton.disabled=false;}if(compareReportButton){compareReportButton.disabled=true;}updatePlannerProgress();}"
                + "function renderWarmExecution(result){if(result.plan){renderPlan(result.plan);}warmResults.classList.remove('d-none');setMetric('plannerWarmChildRows',String(result.childRowsHydrated)+' / '+String(result.childRowsRead));setMetric('plannerWarmRootRows',String(result.rootRowsHydrated)+' / '+String(result.rootRowsRead));setMetric('plannerWarmSkippedRows',String(result.skippedDeletedRows));setMetric('plannerWarmDuration',String(result.durationMillis)+' ms');setMetric('plannerWarmReferencedRoots',String(result.distinctReferencedRootIds));setMetric('plannerWarmMissingRoots',String(result.missingReferencedRootIds));renderList('plannerWarmNotes',result.notes);document.getElementById('plannerWarmChildSql').textContent=result.childWarmSql||'';document.getElementById('plannerWarmRootSql').textContent=result.rootWarmSql||'';updatePlannerProgress();}"
                + "function renderScaffold(result){if(result.plan){renderPlan(result.plan);}scaffoldResults.classList.remove('d-none');setMetric('plannerScaffoldBasePackage',result.basePackage||'-');setMetric('plannerScaffoldFileCount',String(result.fileCount||0));renderList('plannerScaffoldNotes',result.notes);renderList('plannerScaffoldWarnings',result.warnings);renderScaffoldFiles(result.files||[]);updatePlannerProgress();}"
                + "function renderComparisonAssessment(assessment){if(!assessment){setMetric('plannerCompareAssessmentStatus','-');setMetric('plannerCompareAssessmentParity','-');setMetric('plannerCompareAssessmentAvgRatio','-');setMetric('plannerCompareAssessmentP95Ratio','-');document.getElementById('plannerCompareAssessmentSummary').textContent='';document.getElementById('plannerCompareAssessmentDecision').textContent='';renderList('plannerCompareAssessmentStrengths',[], '" + escapeJs(localized(normalizedLanguage, "Henüz güçlü sinyal yok.", "No positive signals yet.")) + "');renderList('plannerCompareAssessmentBlockers',[], '" + escapeJs(localized(normalizedLanguage, "Henüz blokaj kaydedilmedi.", "No blockers recorded yet.")) + "');renderList('plannerCompareAssessmentNextSteps',[], '" + escapeJs(localized(normalizedLanguage, "Henüz sonraki adım üretilmedi.", "No next steps generated yet.")) + "');return;}setMetric('plannerCompareAssessmentStatus',formatAssessmentReadiness(assessment.readiness));setMetric('plannerCompareAssessmentParity',formatAssessmentParity(assessment.parityStatus));setMetric('plannerCompareAssessmentAvgRatio',formatRatio(assessment.averageLatencyRatio));setMetric('plannerCompareAssessmentP95Ratio',formatRatio(assessment.p95LatencyRatio));document.getElementById('plannerCompareAssessmentSummary').textContent=assessment.summary||'';document.getElementById('plannerCompareAssessmentDecision').textContent=assessment.decision||'';renderList('plannerCompareAssessmentStrengths',assessment.strengths,'" + escapeJs(localized(normalizedLanguage, "Bu ölçüm turunda öne çıkan güçlü sinyal yok.", "No strong positive signal stood out in this run.")) + "');renderList('plannerCompareAssessmentBlockers',assessment.blockers,'" + escapeJs(localized(normalizedLanguage, "Bu turda doğrudan cutover blokajı görülmedi.", "No direct cutover blocker was found in this run.")) + "');renderList('plannerCompareAssessmentNextSteps',assessment.nextSteps,'" + escapeJs(localized(normalizedLanguage, "Önerilen ek adım üretilmedi.", "No additional next step was generated.")) + "');}"
                + "function renderComparison(result){lastComparisonResult=result||null;if(result.plan){renderPlan(result.plan);}compareResults.classList.remove('d-none');const exactMatches=(result.sampleComparisons||[]).filter(function(item){return !!item.exactMatch;}).length;setMetric('plannerCompareRoute',result.cacheRouteLabel||'-');setMetric('plannerCompareExactMatches',String(exactMatches)+' / '+String((result.sampleComparisons||[]).length));setMetric('plannerCompareBaselineAvg',formatLatency(result.baselineMetrics&&result.baselineMetrics.averageLatencyNanos));setMetric('plannerCompareBaselineP95',formatLatency(result.baselineMetrics&&result.baselineMetrics.p95LatencyNanos));setMetric('plannerCompareCacheAvg',formatLatency(result.cacheMetrics&&result.cacheMetrics.averageLatencyNanos));setMetric('plannerCompareCacheP95',formatLatency(result.cacheMetrics&&result.cacheMetrics.p95LatencyNanos));renderComparisonAssessment(result.assessment);document.getElementById('plannerCompareBaselineSql').textContent=result.baselineSqlTemplate||'';renderComparisonSamples(result.sampleComparisons||[]);renderList('plannerCompareNotes',result.notes);renderList('plannerCompareWarnings',result.warnings);if(compareReportButton){compareReportButton.disabled=!(result.report&&result.report.markdown);}if(result.warmResult){renderWarmExecution(result.warmResult);}updatePlannerProgress();}"
                + "function downloadComparisonReport(){if(!lastComparisonResult||!lastComparisonResult.report||!lastComparisonResult.report.markdown){setStatus('" + escapeJs(localized(normalizedLanguage, "Önce karşılaştırmayı çalıştırıp raporu üret.", "Run the comparison first to generate the report.")) + "');return;}const fileName=lastComparisonResult.report.fileName||'migration-report.md';const blob=new Blob([lastComparisonResult.report.markdown],{type:'text/markdown;charset=utf-8'});const url=URL.createObjectURL(blob);const link=document.createElement('a');link.href=url;link.download=fileName;document.body.appendChild(link);link.click();document.body.removeChild(link);window.setTimeout(function(){URL.revokeObjectURL(url);},1000);setStatus('" + escapeJs(localized(normalizedLanguage, "Migration report indirildi.", "Migration report downloaded.")) + "');}"
                + "async function loadTemplate(){setStatus('" + escapeJs(localized(normalizedLanguage, "Planlayıcı şablonu yükleniyor…", "Loading planner template…")) + "');const payload=await fetchJson(apiBase + '/api/migration-planner/template');templateDefaults=payload.defaults||null;if(templateDefaults){fillForm(templateDefaults);}setStatus('" + escapeJs(localized(normalizedLanguage, "Hazır. Mevcut ekranını anlat ve planı üret.", "Ready. Describe your current route and generate the plan.")) + "');}"
                + "defaultsButton.addEventListener('click',function(){if(templateDefaults){fillForm(templateDefaults);setStatus('" + escapeJs(localized(normalizedLanguage, "Örnek değerler yeniden yüklendi.", "Example defaults reloaded.")) + "');}});"
                + "modeButtons.forEach(function(button){button.addEventListener('click',function(){applyPlannerMode(button.dataset.plannerMode||'beginner');});});"
                + "stepButtons.forEach(function(button){button.addEventListener('click',function(){const target=document.getElementById(button.dataset.plannerStepTarget||'');if(target){target.scrollIntoView({behavior:'smooth',block:'start'});}});});"
                + "if(routePresetField){routePresetField.addEventListener('change',function(){applyRoutePreset(routePresetField.value,false);});updatePresetHelp();}"
                + "['rootTableOrEntity','childTableOrEntity'].forEach(function(name){const field=plannerField(name);if(field){field.addEventListener('change',function(){syncDiscoverySelectors();updatePlannerProgress();});}});"
                + "['sortColumn','sortDirection','rootPrimaryKeyColumn','childPrimaryKeyColumn','relationColumn'].forEach(function(name){const field=plannerField(name);if(field){field.addEventListener('change',function(){updatePlannerProgress();});}});"
                + "if(demoBootstrapButton){demoBootstrapButton.addEventListener('click',function(event){event.preventDefault();seedDemoSchema().catch(function(){});});}"
                + "if(discoverButton){discoverButton.addEventListener('click',function(event){event.preventDefault();loadDiscovery(true).catch(function(){});});}"
                + "async function warmExecution(dryRun){const params=serializeForm();params.set('dryRun',dryRun?'true':'false');const startMessage=dryRun?'" + escapeJs(localized(normalizedLanguage, "Dry run başlatıldı…", "Dry run started…")) + "':'" + escapeJs(localized(normalizedLanguage, "Warm execution başlatıldı…", "Warm execution started…")) + "';warmStatus.textContent=startMessage;setStatus(startMessage);try{const payload=await fetchJson(apiBase + '/api/migration-planner/warm',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded; charset=UTF-8'},body:params.toString()});renderWarmExecution(payload);const doneMessage=payload.dryRun?'" + escapeJs(localized(normalizedLanguage, "Dry run tamamlandı. Redis değiştirilmedi.", "Dry run completed. Redis was not mutated.")) + "':'" + escapeJs(localized(normalizedLanguage, "Warm execution tamamlandı. Staging hot set hazır.", "Warm execution completed. The staging hot set is ready.")) + "';warmStatus.textContent=doneMessage;setStatus(doneMessage);}catch(error){const message='" + escapeJs(localized(normalizedLanguage, "Warm execution başarısız oldu: ", "Warm execution failed: ")) + "'+error.message;warmStatus.textContent=message;setStatus(message);}}"
                + "async function scaffoldGeneration(){const params=serializeForm();const startMessage='" + escapeJs(localized(normalizedLanguage, "Scaffold üretiliyor…", "Generating scaffold…")) + "';if(scaffoldStatus){scaffoldStatus.textContent=startMessage;}setStatus(startMessage);try{const payload=await fetchJson(apiBase + '/api/migration-planner/scaffold',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded; charset=UTF-8'},body:params.toString()});renderScaffold(payload);const doneMessage='" + escapeJs(localized(normalizedLanguage, "Scaffold hazır. Dosyaları kopyalayıp projene ekleyebilirsin.", "Scaffold ready. You can copy the files into your project.")) + "';if(scaffoldStatus){scaffoldStatus.textContent=doneMessage;}setStatus(doneMessage);}catch(error){const message='" + escapeJs(localized(normalizedLanguage, "Scaffold üretimi başarısız oldu: ", "Scaffold generation failed: ")) + "'+error.message;if(scaffoldStatus){scaffoldStatus.textContent=message;}setStatus(message);}}"
                + "async function runComparison(){const params=serializeForm();const startMessage='" + escapeJs(localized(normalizedLanguage, "Side-by-side comparison başlatıldı…", "Side-by-side comparison started…")) + "';if(compareStatus){compareStatus.textContent=startMessage;}setStatus(startMessage);try{const payload=await fetchJson(apiBase + '/api/migration-planner/compare',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded; charset=UTF-8'},body:params.toString()});renderComparison(payload);const doneMessage='" + escapeJs(localized(normalizedLanguage, "Karşılaştırma tamamlandı. PostgreSQL ve CacheDB sonuçları yan yana hazır.", "Comparison completed. PostgreSQL and CacheDB results are ready side by side.")) + "';if(compareStatus){compareStatus.textContent=doneMessage;}setStatus(doneMessage);}catch(error){const message='" + escapeJs(localized(normalizedLanguage, "Karşılaştırma başarısız oldu: ", "Comparison failed: ")) + "'+error.message;if(compareStatus){compareStatus.textContent=message;}setStatus(message);}}"
                + "Array.from(form.elements).forEach(function(field){if(!field||!field.name){return;}field.addEventListener('change',function(){updatePlannerProgress();});});"
                + "form.addEventListener('submit',async function(event){event.preventDefault();setStatus('" + escapeJs(localized(normalizedLanguage, "Plan üretiliyor…", "Generating plan…")) + "');try{const payload=await fetchJson(apiBase + '/api/migration-planner/plan',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded; charset=UTF-8'},body:serializeForm().toString()});renderPlan(payload);setStatus('" + escapeJs(localized(normalizedLanguage, "Plan hazır. İstersen hemen staging warm çalıştırabilirsin.", "Plan ready. You can run the staging warm execution right away.")) + "');warmStatus.textContent='" + escapeJs(localized(normalizedLanguage, "Plan hazır. Dry run ya da gerçek warm seçebilirsin.", "Plan ready. You can choose a dry run or a real warm execution.")) + "';}catch(error){setStatus('" + escapeJs(localized(normalizedLanguage, "Plan üretilemedi. Girişleri kontrol edip tekrar dene.", "Could not generate the plan. Check the inputs and try again.")) + "');warmStatus.textContent='" + escapeJs(localized(normalizedLanguage, "Plan üretimi başarısız oldu.", "Plan generation failed.")) + "';updatePlannerProgress();}});"
                + "warmButton.addEventListener('click',function(){warmExecution(false).catch(function(){});});"
                + "warmPreviewButton.addEventListener('click',function(){warmExecution(true).catch(function(){});});"
                + "if(scaffoldButton){scaffoldButton.addEventListener('click',function(){scaffoldGeneration().catch(function(){});});}"
                + "if(compareButton){compareButton.addEventListener('click',function(){runComparison().catch(function(){});});}"
                + "if(compareReportButton){compareReportButton.addEventListener('click',function(){downloadComparisonReport();});}"
                + "document.addEventListener('click',function(event){const suggestionButton=event.target.closest('[data-planner-suggestion]');if(suggestionButton){event.preventDefault();applyDiscoverySuggestion(Number(suggestionButton.dataset.plannerSuggestion||-1));return;}const objectButton=event.target.closest('[data-planner-object]');if(objectButton){event.preventDefault();applyDiscoveredObject(Number(objectButton.dataset.plannerObject||-1),objectButton.dataset.plannerRole||'child');}});"
                + "async function initializePlanner(){applyPlannerMode('beginner');applyRoutePreset(routePresetField&&routePresetField.value||'timeline',true);await loadTemplate();if(bootstrapPlannerForm){fillForm(bootstrapPlannerForm);}await loadDemoDescriptor();if(bootstrapDemoResult){renderDemoBootstrap(bootstrapDemoResult);}if(bootstrapDiscovery){renderDiscovery(bootstrapDiscovery);setDiscoveryStatus('" + escapeJs(localized(normalizedLanguage, "Şema keşfi hazır. İstersen önerilerden birini forma uygula.", "Schema discovery is ready. You can apply one of the suggestions to the form.")) + "');}else{await loadDiscovery(false);}if(bootstrapPlanResult){renderPlan(bootstrapPlanResult);}updatePlannerProgress();}"
                + "initializePlanner().catch(function(){setStatus('" + escapeJs(localized(normalizedLanguage, "Şablon yüklenemedi. Sayfayı yenileyip tekrar dene.", "Could not load the template. Refresh the page and try again.")) + "');setDiscoveryStatus('" + escapeJs(localized(normalizedLanguage, "Şema keşfi yüklenemedi. Sayfayı yenileyip tekrar dene.", "Schema discovery could not be loaded. Refresh the page and try again.")) + "');});"
                + "})();"
                + "</script></body></html>";
    }

    private MigrationPlannerPageState resolveMigrationPlannerPageState(
            Map<String, List<String>> query,
            MigrationSchemaDiscovery.Result fallbackDiscovery
    ) {
        LinkedHashMap<String, String> values = defaultMigrationPlannerFormValues();
        applyPlannerQueryOverrides(values, query);
        MigrationPlannerDemoSupport.BootstrapResult demoBootstrapResult = null;
        String demoBootstrapError = "";
        MigrationPlanner.Result planResult = null;
        String planError = "";
        if (shouldBootstrapDemo(query)) {
            try {
                demoBootstrapResult = admin.bootstrapMigrationPlannerDemo(parseMigrationPlannerDemoBootstrapRequest(query));
                mergePlannerRequest(values, demoBootstrapResult.plannerDefaults());
            } catch (IllegalArgumentException | IllegalStateException exception) {
                demoBootstrapError = defaultString(exception.getMessage());
            }
        }
        MigrationSchemaDiscovery.Result discovery = shouldBootstrapDiscovery(query)
                ? (fallbackDiscovery == null ? admin.discoverMigrationSchema() : fallbackDiscovery)
                : fallbackDiscovery;
        if (discovery != null) {
            applyDiscoveryActions(values, query, discovery);
            enrichPlannerValuesFromDiscovery(values, discovery);
        }
        if (shouldGeneratePlan(query)) {
            try {
                planResult = admin.planMigration(parseMigrationPlannerRequest(toMultiValueParameters(values)));
            } catch (IllegalArgumentException | IllegalStateException exception) {
                planError = defaultString(exception.getMessage());
            }
        }
        return new MigrationPlannerPageState(
                discovery,
                Collections.unmodifiableMap(values),
                demoBootstrapResult,
                demoBootstrapError,
                planResult,
                planError
        );
    }

    private boolean shouldBootstrapDiscovery(Map<String, List<String>> query) {
        String discover = first(query, "discover");
        return "1".equals(discover)
                || "true".equalsIgnoreCase(discover)
                || shouldBootstrapDemo(query)
                || first(query, "applySuggestion") != null
                || first(query, "selectObject") != null;
    }

    private boolean shouldBootstrapDemo(Map<String, List<String>> query) {
        String demoBootstrap = first(query, "demoBootstrap");
        return "1".equals(demoBootstrap) || "true".equalsIgnoreCase(demoBootstrap);
    }

    private boolean shouldGeneratePlan(Map<String, List<String>> query) {
        String generatePlan = first(query, "generatePlan");
        return "1".equals(generatePlan) || "true".equalsIgnoreCase(generatePlan);
    }

    private Map<String, List<String>> toMultiValueParameters(Map<String, String> values) {
        LinkedHashMap<String, List<String>> parameters = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            parameters.put(entry.getKey(), List.of(defaultString(entry.getValue())));
        }
        return parameters;
    }

    private String resolvePlannerStatusMessage(
            String language,
            MigrationPlanner.Result planResult,
            String planError
    ) {
        if (planResult != null) {
            return localized(
                    language,
                    "Plan hazır. İstersen hemen staging warm çalıştırabilirsin.",
                    "Plan ready. You can run the staging warm execution right away."
            );
        }
        if (planError != null && !planError.isBlank()) {
            return localized(language, "Plan üretilemedi: ", "Could not generate the plan: ") + planError;
        }
        return localized(
                language,
                "Hazır. Mevcut ekranını anlat ve planı üret.",
                "Ready. Describe your current route and generate the plan."
        );
    }

    private LinkedHashMap<String, String> defaultMigrationPlannerFormValues() {
        MigrationPlanner.Request defaults = admin.migrationPlannerTemplate().defaults();
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        mergePlannerRequest(values, defaults);
        values.put("routePreset", deriveRoutePreset(defaults));
        values.putIfAbsent("sortDirection", defaults.sortDirection() == null || defaults.sortDirection().isBlank() ? "DESC" : defaults.sortDirection());
        values.put("demoCustomerCount", "120");
        values.put("demoHotCustomerCount", "12");
        values.put("demoMaxOrdersPerCustomer", "1500");
        values.put("warmRootRows", "true");
        values.put("dryRun", "false");
        values.put("basePackage", "com.example.cachedb.migration");
        values.put("rootClassName", "CustomerEntity");
        values.put("childClassName", "OrderEntity");
        values.put("relationLoaderClassName", "CustomerOrderRelationBatchLoader");
        values.put("projectionSupportClassName", "CustomerOrderReadModels");
        values.put("comparisonSampleRootId", "");
        values.put("comparisonSampleRootCount", "3");
        values.put("comparisonPageSize", String.valueOf(defaults.firstPageSize()));
        values.put("comparisonWarmupIterations", "2");
        values.put("comparisonMeasuredIterations", "8");
        values.put("comparisonProjectionName", "");
        values.put("includeRelationLoader", "true");
        values.put("includeProjectionSkeleton", "true");
        values.put("warmBeforeCompare", "false");
        values.put("comparisonBaselineSql", "");
        return values;
    }

    private void applyPlannerQueryOverrides(Map<String, String> values, Map<String, List<String>> query) {
        for (String key : values.keySet()) {
            String override = first(query, key);
            if (override != null) {
                values.put(key, override);
            }
        }
    }

    private void applyDiscoveryActions(
            Map<String, String> values,
            Map<String, List<String>> query,
            MigrationSchemaDiscovery.Result discovery
    ) {
        String applySuggestion = first(query, "applySuggestion");
        if (applySuggestion != null) {
            try {
                int index = Integer.parseInt(applySuggestion);
                if (index >= 0 && index < discovery.routeSuggestions().size()) {
                    mergePlannerRequest(values, discovery.routeSuggestions().get(index).plannerRequest());
                }
            } catch (NumberFormatException ignored) {
                // Ignore malformed input and keep the current planner state.
            }
        }
        String selectObject = first(query, "selectObject");
        String role = defaultString(first(query, "plannerRole"));
        if (selectObject != null && !role.isBlank()) {
            try {
                int index = Integer.parseInt(selectObject);
                if (index >= 0 && index < discovery.tables().size()) {
                    MigrationSchemaDiscovery.TableInfo table = discovery.tables().get(index);
                    if ("root".equalsIgnoreCase(role)) {
                        values.put("rootTableOrEntity", discoverySurfaceValue(table));
                        values.put("rootPrimaryKeyColumn", defaultString(table.primaryKeyColumn()));
                    } else {
                        values.put("childTableOrEntity", discoverySurfaceValue(table));
                        values.put("childPrimaryKeyColumn", defaultString(table.primaryKeyColumn()));
                    }
                }
            } catch (NumberFormatException ignored) {
                // Ignore malformed input and keep the current planner state.
            }
        }
    }

    private void enrichPlannerValuesFromDiscovery(Map<String, String> values, MigrationSchemaDiscovery.Result discovery) {
        MigrationSchemaDiscovery.TableInfo rootTable = resolveDiscoveryTable(discovery, values.get("rootTableOrEntity"));
        MigrationSchemaDiscovery.TableInfo childTable = resolveDiscoveryTable(discovery, values.get("childTableOrEntity"));
        if (rootTable != null && (values.get("rootPrimaryKeyColumn") == null || values.get("rootPrimaryKeyColumn").isBlank())) {
            values.put("rootPrimaryKeyColumn", defaultString(rootTable.primaryKeyColumn()));
        }
        if (childTable != null && (values.get("childPrimaryKeyColumn") == null || values.get("childPrimaryKeyColumn").isBlank())) {
            values.put("childPrimaryKeyColumn", defaultString(childTable.primaryKeyColumn()));
        }
        MigrationSchemaDiscovery.RouteSuggestion suggestion = findMatchingRouteSuggestion(
                discovery,
                values.get("rootTableOrEntity"),
                values.get("childTableOrEntity")
        );
        if ((values.get("relationColumn") == null || values.get("relationColumn").isBlank()) && suggestion != null) {
            values.put("relationColumn", defaultString(suggestion.relationColumn()));
        } else if ((values.get("relationColumn") == null || values.get("relationColumn").isBlank()) && childTable != null
                && !childTable.foreignKeyColumns().isEmpty()) {
            values.put("relationColumn", childTable.foreignKeyColumns().get(0));
        }
        if ((values.get("sortColumn") == null || values.get("sortColumn").isBlank()) && suggestion != null) {
            values.put("sortColumn", defaultString(suggestion.sortColumn()));
        } else if ((values.get("sortColumn") == null || values.get("sortColumn").isBlank()) && childTable != null
                && !childTable.temporalColumns().isEmpty()) {
            values.put("sortColumn", childTable.temporalColumns().get(0));
        }
    }

    private void mergePlannerRequest(Map<String, String> values, MigrationPlanner.Request request) {
        values.put("workloadName", defaultString(request.workloadName()));
        values.put("rootTableOrEntity", defaultString(request.rootTableOrEntity()));
        values.put("rootPrimaryKeyColumn", defaultString(request.rootPrimaryKeyColumn()));
        values.put("childTableOrEntity", defaultString(request.childTableOrEntity()));
        values.put("childPrimaryKeyColumn", defaultString(request.childPrimaryKeyColumn()));
        values.put("relationColumn", defaultString(request.relationColumn()));
        values.put("sortColumn", defaultString(request.sortColumn()));
        values.put("sortDirection", request.sortDirection() == null || request.sortDirection().isBlank() ? "DESC" : request.sortDirection());
        values.put("rootRowCount", String.valueOf(request.rootRowCount()));
        values.put("childRowCount", String.valueOf(request.childRowCount()));
        values.put("typicalChildrenPerRoot", String.valueOf(request.typicalChildrenPerRoot()));
        values.put("maxChildrenPerRoot", String.valueOf(request.maxChildrenPerRoot()));
        values.put("firstPageSize", String.valueOf(request.firstPageSize()));
        values.put("hotWindowPerRoot", String.valueOf(request.hotWindowPerRoot()));
        values.put("listScreen", String.valueOf(request.listScreen()));
        values.put("firstPaintNeedsFullAggregate", String.valueOf(request.firstPaintNeedsFullAggregate()));
        values.put("globalSortedScreen", String.valueOf(request.globalSortedScreen()));
        values.put("thresholdOrRangeScreen", String.valueOf(request.thresholdOrRangeScreen()));
        values.put("archiveHistoryRequired", String.valueOf(request.archiveHistoryRequired()));
        values.put("fullHistoryMustStayHot", String.valueOf(request.fullHistoryMustStayHot()));
        values.put("currentOrmUsesEagerLoading", String.valueOf(request.currentOrmUsesEagerLoading()));
        values.put("detailLookupIsHot", String.valueOf(request.detailLookupIsHot()));
        values.put("sideBySideComparisonRequired", String.valueOf(request.sideBySideComparisonRequired()));
        values.put("routePreset", deriveRoutePreset(request));
    }

    private String deriveRoutePreset(MigrationPlanner.Request request) {
        if (request.firstPaintNeedsFullAggregate() && !request.listScreen()) {
            return "detail-heavy";
        }
        if (request.globalSortedScreen()) {
            return "leaderboard";
        }
        if (request.thresholdOrRangeScreen()) {
            return "threshold";
        }
        return "timeline";
    }

    private String renderStaticDiscoverySuggestions(
            MigrationSchemaDiscovery.Result result,
            String language,
            String plannerPath,
            Map<String, String> currentValues
    ) {
        if (result == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (String warning : result.warnings()) {
            builder.append("<div class=\"planner-warning mb-2\">")
                    .append(escapeHtml(warning))
                    .append("</div>");
        }
        if (result.routeSuggestions().isEmpty()) {
            builder.append("<div class=\"planner-empty\">")
                    .append(escapeHtml(localized(
                            language,
                            "Keşiften otomatik route adayı çıkmadı. Yine de aşağıdaki listelerden tablo ve kolon seçerek plan üretebilirsin.",
                            "No automatic route suggestion came out of discovery. You can still build the plan by choosing tables and columns from the lists below."
                    )))
                    .append("</div>");
            return builder.toString();
        }
        for (int index = 0; index < result.routeSuggestions().size(); index++) {
            MigrationSchemaDiscovery.RouteSuggestion item = result.routeSuggestions().get(index);
            String surfaces = List.of(item.rootEntityName(), item.rootSurface(), item.childEntityName(), item.childSurface()).stream()
                    .filter(value -> value != null && !value.isBlank())
                    .distinct()
                    .reduce((left, right) -> left + " → " + right)
                    .orElse("");
            String sortCandidates = item.sortCandidates().isEmpty() ? "-" : String.join(", ", item.sortCandidates());
            builder.append("<div class=\"result-check mb-3\"><div class=\"d-flex flex-column flex-lg-row justify-content-between gap-3\"><div><div>");
            if (item.temporalSortCandidate()) {
                builder.append("<span class=\"planner-badge\">")
                        .append(escapeHtml(localized(language, "zamansal sıra", "temporal sort")))
                        .append("</span>");
            }
            if (item.rankedSortCandidate()) {
                builder.append("<span class=\"planner-badge\">")
                        .append(escapeHtml(localized(language, "rank adayı", "rank candidate")))
                        .append("</span>");
            }
            builder.append("</div><div class=\"result-step-title\">")
                    .append(escapeHtml(item.label()))
                    .append("</div><div class=\"small text-muted mt-1\">")
                    .append(escapeHtml(item.summary()))
                    .append("</div><div class=\"small mt-2\">")
                    .append(escapeHtml(surfaces))
                    .append("</div><div class=\"small text-muted mt-1\">")
                    .append(escapeHtml(localized(language, "İlişki", "Relation") + ": " + item.relationColumn()
                            + " · " + localized(language, "Önerilen sıralama", "Suggested sort") + ": " + item.sortColumn() + " DESC"))
                    .append("</div><div class=\"small text-muted mt-1\">")
                    .append(escapeHtml(localized(language, "Alternatif sıralama kolonları", "Alternative sort columns") + ": " + sortCandidates))
                    .append("</div></div><div><a class=\"btn btn-sm btn-outline-primary\" data-planner-suggestion=\"")
                    .append(index)
                    .append("\" href=\"")
                    .append(escapeHtml(buildMigrationPlannerNavigationLink(
                            plannerPath,
                            language,
                            currentValues,
                            Map.of(
                                    "discover", "true",
                                    "applySuggestion", String.valueOf(index)
                            )
                    )))
                    .append("\">")
                    .append(escapeHtml(localized(language, "Forma Uygula", "Apply To Form")))
                    .append("</a></div></div></div>");
        }
        return builder.toString();
    }

    private String renderStaticDiscoveryTables(
            MigrationSchemaDiscovery.Result result,
            String language,
            String plannerPath,
            Map<String, String> currentValues
    ) {
        if (result == null || result.tables().isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < result.tables().size(); index++) {
            MigrationSchemaDiscovery.TableInfo item = result.tables().get(index);
            boolean view = "VIEW".equalsIgnoreCase(item.objectType());
            builder.append("<div class=\"result-check mb-3\"><div class=\"d-flex flex-column flex-lg-row justify-content-between gap-3\"><div><div>")
                    .append("<span class=\"planner-badge ")
                    .append(view ? "view" : "")
                    .append("\">")
                    .append(escapeHtml(item.objectType()))
                    .append("</span>");
            if (item.registeredEntityName() != null && !item.registeredEntityName().isBlank()) {
                builder.append("<span class=\"planner-badge entity\">")
                        .append(escapeHtml(localized(language, "entity", "entity") + ": " + item.registeredEntityName()))
                        .append("</span>");
            }
            builder.append("</div><div class=\"result-step-title\">")
                    .append(escapeHtml(item.qualifiedTableName()))
                    .append("</div><div class=\"small text-muted mt-1\">")
                    .append(escapeHtml(localized(language, "PK", "PK") + ": " + defaultString(item.primaryKeyColumn())))
                    .append(" · ")
                    .append(escapeHtml(localized(language, "Kolon", "Columns") + ": " + item.columnCount()))
                    .append(" · ")
                    .append(escapeHtml(localized(language, "FK", "FK") + ": " + item.importedKeyCount()))
                    .append("</div></div><div>");
            if (view) {
                builder.append("<a class=\"btn btn-sm btn-outline-secondary\" data-planner-object=\"")
                        .append(index)
                        .append("\" data-planner-role=\"child\" href=\"")
                        .append(escapeHtml(buildMigrationPlannerNavigationLink(
                                plannerPath,
                                language,
                                currentValues,
                                Map.of(
                                        "discover", "true",
                                        "selectObject", String.valueOf(index),
                                        "plannerRole", "child"
                                )
                        )))
                        .append("\">")
                        .append(escapeHtml(localized(language, "Çocuk olarak seç", "Use as child")))
                        .append("</a>");
            } else {
                builder.append("<div class=\"d-flex gap-2 flex-wrap\"><a class=\"btn btn-sm btn-outline-secondary\" data-planner-object=\"")
                        .append(index)
                        .append("\" data-planner-role=\"root\" href=\"")
                        .append(escapeHtml(buildMigrationPlannerNavigationLink(
                                plannerPath,
                                language,
                                currentValues,
                                Map.of(
                                        "discover", "true",
                                        "selectObject", String.valueOf(index),
                                        "plannerRole", "root"
                                )
                        )))
                        .append("\">")
                        .append(escapeHtml(localized(language, "Kök seç", "Select root")))
                        .append("</a><a class=\"btn btn-sm btn-outline-secondary\" data-planner-object=\"")
                        .append(index)
                        .append("\" data-planner-role=\"child\" href=\"")
                        .append(escapeHtml(buildMigrationPlannerNavigationLink(
                                plannerPath,
                                language,
                                currentValues,
                                Map.of(
                                        "discover", "true",
                                        "selectObject", String.valueOf(index),
                                        "plannerRole", "child"
                                )
                        )))
                        .append("\">")
                        .append(escapeHtml(localized(language, "Çocuk seç", "Select child")))
                        .append("</a></div>");
            }
            builder.append("</div></div></div>");
        }
        return builder.toString();
    }

    private String fieldInput(String name, String label, String placeholder, String extraClass, String value) {
        String classSuffix = extraClass == null || extraClass.isBlank() ? "" : " " + extraClass.trim();
        return "<div class=\"" + classSuffix.trim() + "\"><label class=\"form-label fw-semibold\">" + escapeHtml(label)
                + "</label><input class=\"form-control\" name=\"" + escapeHtml(name) + "\" placeholder=\""
                + escapeHtml(placeholder) + "\" value=\"" + escapeHtml(defaultString(value)) + "\"></div>";
    }

    private String selectInput(
            String name,
            String label,
            String placeholder,
            String extraClass,
            List<PlannerSelectOption> options,
            String selectedValue
    ) {
        String classSuffix = extraClass == null || extraClass.isBlank() ? "" : " " + extraClass.trim();
        StringBuilder builder = new StringBuilder("<div class=\"")
                .append(classSuffix.trim())
                .append("\"><label class=\"form-label fw-semibold\">")
                .append(escapeHtml(label))
                .append("</label><select class=\"form-select\" name=\"")
                .append(escapeHtml(name))
                .append("\"><option value=\"\">")
                .append(escapeHtml(placeholder))
                .append("</option>");
        List<PlannerSelectOption> safeOptions = options == null ? List.of() : options;
        String normalizedSelected = normalizeSurfaceValue(selectedValue);
        boolean selectedPresent = normalizedSelected.isBlank();
        for (PlannerSelectOption option : safeOptions) {
            if (option == null || option.value() == null || option.value().isBlank()) {
                continue;
            }
            boolean selected = normalizeSurfaceValue(option.value()).equals(normalizedSelected);
            if (selected) {
                selectedPresent = true;
            }
            builder.append("<option value=\"")
                    .append(escapeHtml(option.value()))
                    .append("\"")
                    .append(selected ? " selected" : "")
                    .append(">")
                    .append(escapeHtml(option.label()))
                    .append("</option>");
        }
        if (!selectedPresent && selectedValue != null && !selectedValue.isBlank()) {
            builder.append("<option value=\"")
                    .append(escapeHtml(selectedValue))
                    .append("\" selected>")
                    .append(escapeHtml(selectedValue))
                    .append("</option>");
        }
        builder.append("</select></div>");
        return builder.toString();
    }

    private String selectOption(String value, String label, String selectedValue) {
        return "<option value=\"" + escapeHtml(value) + "\""
                + (Objects.equals(defaultString(value), defaultString(selectedValue)) ? " selected" : "")
                + ">" + escapeHtml(label) + "</option>";
    }

    private String textareaInput(String name, String label, String placeholder, String extraClass, String value) {
        String classSuffix = extraClass == null || extraClass.isBlank() ? "" : " " + extraClass.trim();
        return "<div class=\"" + classSuffix.trim() + "\"><label class=\"form-label fw-semibold\">" + escapeHtml(label)
                + "</label><textarea class=\"form-control\" rows=\"4\" name=\"" + escapeHtml(name)
                + "\" placeholder=\"" + escapeHtml(placeholder) + "\">" + escapeHtml(defaultString(value)) + "</textarea></div>";
    }

    private String checkboxInput(String name, String label, boolean checked) {
        return "<div class=\"full\"><div class=\"form-check form-switch\">"
                + "<input class=\"form-check-input\" type=\"checkbox\" role=\"switch\" name=\"" + escapeHtml(name) + "\""
                + (checked ? " checked" : "") + ">"
                + "<label class=\"form-check-label\">" + escapeHtml(label) + "</label>"
                + "</div></div>";
    }

    private boolean parseChecked(Map<String, String> values, String key, boolean defaultValue) {
        String raw = values == null ? null : values.get(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        return "true".equalsIgnoreCase(raw) || "1".equals(raw) || "yes".equalsIgnoreCase(raw) || "on".equalsIgnoreCase(raw);
    }

    private String renderPlannerFormValues(Map<String, String> values) {
        StringBuilder builder = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!first) {
                builder.append(',');
            }
            first = false;
            builder.append('"').append(escapeJson(entry.getKey())).append("\":\"")
                    .append(escapeJson(defaultString(entry.getValue())))
                    .append('"');
        }
        builder.append('}');
        return builder.toString();
    }

    private List<PlannerSelectOption> discoveredSurfaceOptions(MigrationSchemaDiscovery.Result discovery, boolean includeViews, String language) {
        if (discovery == null) {
            return List.of();
        }
        ArrayList<PlannerSelectOption> options = new ArrayList<>();
        for (MigrationSchemaDiscovery.TableInfo table : discovery.tables()) {
            if (table == null) {
                continue;
            }
            boolean view = isView(table);
            if (view && !includeViews) {
                continue;
            }
            StringBuilder label = new StringBuilder(defaultString(table.qualifiedTableName()));
            ArrayList<String> badges = new ArrayList<>();
            if (view) {
                badges.add(localized(language, "view", "view"));
            }
            if (table.registeredEntityName() != null && !table.registeredEntityName().isBlank()) {
                badges.add(localized(language, "entity", "entity") + ": " + table.registeredEntityName());
            }
            if (!badges.isEmpty()) {
                label.append(" · ").append(String.join(" · ", badges));
            }
            options.add(new PlannerSelectOption(discoverySurfaceValue(table), label.toString()));
        }
        return options;
    }

    private List<PlannerSelectOption> discoveredColumnOptions(MigrationSchemaDiscovery.TableInfo table, String kind) {
        if (table == null) {
            return List.of();
        }
        ArrayList<PlannerSelectOption> options = new ArrayList<>();
        if ("pk".equalsIgnoreCase(kind)) {
            if (table.primaryKeyColumn() != null && !table.primaryKeyColumn().isBlank()) {
                options.add(new PlannerSelectOption(table.primaryKeyColumn(), table.primaryKeyColumn()));
            }
            return options;
        }
        if ("sort".equalsIgnoreCase(kind)) {
            for (String column : table.temporalColumns()) {
                options.add(new PlannerSelectOption(column, column + " · temporal"));
            }
            for (MigrationSchemaDiscovery.ColumnInfo column : table.columns()) {
                if (column == null || column.name() == null || column.name().isBlank()) {
                    continue;
                }
                if (options.stream().noneMatch(option -> Objects.equals(option.value(), column.name()))
                        && isSortableColumnType(column.jdbcTypeName())) {
                    options.add(new PlannerSelectOption(column.name(), column.name() + " · " + defaultString(column.jdbcTypeName())));
                }
            }
            return options;
        }
        return options;
    }

    private List<PlannerSelectOption> discoveredRelationOptions(
            MigrationSchemaDiscovery.Result discovery,
            String rootSurface,
            String childSurface,
            String language
    ) {
        MigrationSchemaDiscovery.TableInfo childTable = resolveDiscoveryTable(discovery, childSurface);
        ArrayList<PlannerSelectOption> options = new ArrayList<>();
        MigrationSchemaDiscovery.RouteSuggestion suggestion = findMatchingRouteSuggestion(discovery, rootSurface, childSurface);
        if (suggestion != null && suggestion.relationColumn() != null && !suggestion.relationColumn().isBlank()) {
            options.add(new PlannerSelectOption(
                    suggestion.relationColumn(),
                    suggestion.relationColumn() + " · " + localized(language, "önerilen FK", "suggested FK")
            ));
        }
        if (childTable != null) {
            for (String column : childTable.foreignKeyColumns()) {
                if (options.stream().noneMatch(option -> Objects.equals(option.value(), column))) {
                    options.add(new PlannerSelectOption(column, column + " · FK"));
                }
            }
        }
        return options;
    }

    private boolean isSortableColumnType(String typeName) {
        String normalized = defaultString(typeName).toUpperCase(Locale.ROOT);
        return normalized.contains("INT")
                || normalized.contains("DEC")
                || normalized.contains("NUM")
                || normalized.contains("REAL")
                || normalized.contains("FLOAT")
                || normalized.contains("DOUBLE")
                || normalized.contains("DATE")
                || normalized.contains("TIME")
                || normalized.contains("STAMP");
    }

    private MigrationSchemaDiscovery.TableInfo resolveDiscoveryTable(MigrationSchemaDiscovery.Result discovery, String surface) {
        if (discovery == null || surface == null || surface.isBlank()) {
            return null;
        }
        String normalizedSurface = normalizeSurfaceValue(surface);
        for (MigrationSchemaDiscovery.TableInfo table : discovery.tables()) {
            if (normalizeSurfaceValue(discoverySurfaceValue(table)).equals(normalizedSurface)
                    || normalizeSurfaceValue(table.qualifiedTableName()).equals(normalizedSurface)
                    || normalizeSurfaceValue(table.tableName()).equals(normalizedSurface)
                    || normalizeSurfaceValue(table.registeredEntityName()).equals(normalizedSurface)) {
                return table;
            }
        }
        return null;
    }

    private MigrationSchemaDiscovery.RouteSuggestion findMatchingRouteSuggestion(
            MigrationSchemaDiscovery.Result discovery,
            String rootSurface,
            String childSurface
    ) {
        if (discovery == null) {
            return null;
        }
        String normalizedRoot = normalizeSurfaceValue(rootSurface);
        String normalizedChild = normalizeSurfaceValue(childSurface);
        for (MigrationSchemaDiscovery.RouteSuggestion suggestion : discovery.routeSuggestions()) {
            boolean rootMatches = normalizedRoot.isBlank() || matchesSuggestionSurface(normalizedRoot, suggestion.rootSurface(), suggestion.rootEntityName());
            boolean childMatches = normalizedChild.isBlank() || matchesSuggestionSurface(normalizedChild, suggestion.childSurface(), suggestion.childEntityName());
            if (rootMatches && childMatches) {
                return suggestion;
            }
        }
        return null;
    }

    private boolean matchesSuggestionSurface(String target, String... values) {
        for (String value : values) {
            if (normalizeSurfaceValue(value).equals(target)) {
                return true;
            }
        }
        return false;
    }

    private boolean isView(MigrationSchemaDiscovery.TableInfo table) {
        return table != null && "VIEW".equalsIgnoreCase(defaultString(table.objectType()));
    }

    private String discoverySurfaceValue(MigrationSchemaDiscovery.TableInfo table) {
        if (table == null) {
            return "";
        }
        if (table.registeredEntityName() != null && !table.registeredEntityName().isBlank()) {
            return table.registeredEntityName();
        }
        if (table.qualifiedTableName() != null && !table.qualifiedTableName().isBlank()) {
            return table.qualifiedTableName();
        }
        return defaultString(table.tableName());
    }

    private String buildMigrationPlannerNavigationLink(
            String plannerPath,
            String language,
            Map<String, String> currentValues,
            Map<String, String> overrides
    ) {
        LinkedHashMap<String, String> query = new LinkedHashMap<>();
        query.put("lang", defaultString(language));
        query.put("v", dashboardInstanceId);
        if (currentValues != null) {
            query.putAll(currentValues);
        }
        if (overrides != null) {
            query.putAll(overrides);
        }
        StringBuilder builder = new StringBuilder(defaultString(plannerPath));
        boolean first = true;
        for (Map.Entry<String, String> entry : query.entrySet()) {
            String key = defaultString(entry.getKey());
            String value = entry.getValue();
            if (key.isBlank() || value == null || value.isBlank()) {
                continue;
            }
            builder.append(first ? '?' : '&')
                    .append(encodeQueryComponent(key))
                    .append('=')
                    .append(encodeQueryComponent(value));
            first = false;
        }
        return builder.toString();
    }

    private String encodeQueryComponent(String value) {
        return URLEncoder.encode(defaultString(value), StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    private String normalizeSurfaceValue(String value) {
        return defaultString(value).trim().toLowerCase(Locale.ROOT);
    }

    private String renderMigrationPlannerGuidance(String language) {
        return "<section class=\"planner-guidance\">"
                + "<div class=\"planner-panel\">"
                + "<div class=\"planner-panel-header\">" + escapeHtml(localized(language, "Çalışma modu", "Working mode")) + "</div>"
                + "<div class=\"planner-panel-sub\">" + escapeHtml(localized(language, "Başlangıç modu daha az alan gösterir. İleri mod, üretim cutover'ı öncesi tüm warm/scaffold/comparison ayarlarını görünür yapar.", "Beginner mode shows fewer inputs. Advanced mode reveals the full warm/scaffold/comparison surface before a production cutover.")) + "</div>"
                + "<div class=\"planner-panel-body\">"
                + "<div class=\"planner-mode-switch\">"
                + plannerModeButton("beginner", localized(language, "Başlangıç", "Beginner"), localized(language, "Önerilen akış ve temel kararlar", "Guided flow and essential decisions"), true)
                + plannerModeButton("intermediate", localized(language, "Orta", "Intermediate"), localized(language, "Warm ve scaffold görünürlüğü artar", "More visibility into warm and scaffold"), false)
                + plannerModeButton("advanced", localized(language, "İleri", "Advanced"), localized(language, "Tüm ayarlar ve ham SQL detayları", "All controls and raw SQL details"), false)
                + "</div>"
                + "<div id=\"plannerModeSummary\" class=\"planner-status mt-3\">"
                + escapeHtml(localized(language, "Başlangıç modu açık. Önce keşif, sonra yüzey seçimi, plan, warm ve compare akışını izle.", "Beginner mode is active. Follow discovery, surface selection, planning, warm, and comparison in order."))
                + "</div>"
                + "</div></div>"
                + "<div class=\"planner-panel\">"
                + "<div class=\"planner-panel-header\">" + escapeHtml(localized(language, "Adım adım ilerleme", "Step-by-step progress")) + "</div>"
                + "<div class=\"planner-panel-sub\">" + escapeHtml(localized(language, "Her adım tamamlandıkça ilerleme otomatik güncellenir. Kartlara tıklayarak ilgili bölüme atlayabilirsin.", "Progress updates automatically as each step completes. Click a card to jump to that section.")) + "</div>"
                + "<div class=\"planner-panel-body\">"
                + "<div class=\"planner-progress-shell\">"
                + "<div class=\"planner-progress-top\">"
                + "<div><div id=\"plannerProgressTitle\" class=\"planner-progress-title\">" + escapeHtml(localized(language, "1. adım: Şemayı keşfet", "Step 1: Discover the schema")) + "</div>"
                + "<div id=\"plannerProgressDetail\" class=\"planner-progress-copy\">" + escapeHtml(localized(language, "PostgreSQL şemasını okuyup önerilen root/child route adaylarını görünür hale getir.", "Read the PostgreSQL schema and surface the suggested root/child route candidates.")) + "</div></div>"
                + "<div id=\"plannerProgressPercent\" class=\"planner-progress-percent\">0%</div>"
                + "</div>"
                + "<div class=\"planner-progress-track\"><div id=\"plannerProgressFill\" class=\"planner-progress-fill\"></div></div>"
                + "<div class=\"planner-stepper\">"
                + plannerStepButton("plannerDiscoveryPanel", 1, localized(language, "Keşif", "Discovery"), localized(language, "Şema ve route adayları", "Schema and route candidates"))
                + plannerStepButton("plannerConfigurePanel", 2, localized(language, "Seçim", "Select"), localized(language, "Kök, çocuk ve ekran tipi", "Root, child, and screen type"))
                + plannerStepButton("plannerPlanPanel", 3, localized(language, "Plan", "Plan"), localized(language, "Projection ve sıcak pencere kararı", "Projection and hot-window decision"))
                + plannerStepButton("plannerWarmPanel", 4, localized(language, "Warm", "Warm"), localized(language, "Staging sıcak seti hazırla", "Prepare the staging hot set"))
                + plannerStepButton("plannerComparePanel", 5, localized(language, "Compare", "Compare"), localized(language, "Yan yana ölç ve raporu indir", "Measure side by side and download the report"))
                + "</div></div></div></section>";
    }

    private String plannerModeButton(String mode, String title, String copy, boolean active) {
        return "<button type=\"button\" class=\"planner-mode-button" + (active ? " is-active" : "")
                + "\" data-planner-mode=\"" + escapeHtml(mode) + "\">"
                + "<span class=\"planner-mode-title\">" + escapeHtml(title) + "</span>"
                + "<span class=\"planner-mode-copy\">" + escapeHtml(copy) + "</span>"
                + "</button>";
    }

    private String plannerStepButton(String targetId, int stepIndex, String title, String copy) {
        return "<button type=\"button\" class=\"planner-step\" data-planner-step-target=\"" + escapeHtml(targetId)
                + "\" data-planner-step-index=\"" + stepIndex + "\">"
                + "<span class=\"planner-step-index\">" + stepIndex + "</span>"
                + "<span class=\"planner-step-title\">" + escapeHtml(title) + "</span>"
                + "<span class=\"planner-step-copy\">" + escapeHtml(copy) + "</span>"
                + "</button>";
    }

    private String metricShell(String id, String label) {
        return metricShell(id, label, "");
    }

    private String metricShell(String id, String label, String value) {
        return "<div class=\"result-metric\"><div class=\"result-metric-label\">" + escapeHtml(label)
                + "</div><div id=\"" + escapeHtml(id) + "\" class=\"result-metric-value\">" + escapeHtml(defaultString(value)) + "</div></div>";
    }

    private String renderPlannerStaticList(List<String> items, String emptyMessage) {
        List<String> safeItems = items == null ? List.of() : items;
        if (safeItems.isEmpty()) {
            return "<div class=\"planner-empty\">" + escapeHtml(emptyMessage) + "</div>";
        }
        StringBuilder builder = new StringBuilder("<ul class=\"result-list\">");
        for (String item : safeItems) {
            builder.append("<li>").append(escapeHtml(defaultString(item))).append("</li>");
        }
        builder.append("</ul>");
        return builder.toString();
    }

    private String resolveDemoBootstrapStatusMessage(
            String language,
            MigrationPlannerDemoSupport.BootstrapResult result,
            String error
    ) {
        if (error != null && !error.isBlank()) {
            return localized(language,
                    "Demo şema kurulamadı: " + error,
                    "Demo schema bootstrap failed: " + error);
        }
        if (result != null) {
            String sampleRoots = result.sampleRootIds().isEmpty()
                    ? ""
                    : localized(language, " Örnek kök id", " Sample root ids") + ": " + String.join(", ", result.sampleRootIds());
            return localized(
                    language,
                    "Demo şema hazırlandı. Discovery güncellendi ve planner formu seed edilen route ile dolduruldu.",
                    "The demo schema is ready. Discovery was refreshed and the planner form was populated with the seeded route."
            ) + sampleRoots;
        }
        return localized(
                language,
                "İstersen hazır demo customer/order şemasını kur. Sonra discovery, scaffold, warm ve compare adımlarını aynı ekrandan deneyebilirsin.",
                "Optionally create the ready-made demo customer/order schema first. Then you can try discovery, scaffold, warm, and compare from the same screen."
        );
    }

    private String resultListCard(String id, String title, String listId) {
        return "<div id=\"" + escapeHtml(id) + "\" class=\"result-card\"><div class=\"result-card-header\">"
                + escapeHtml(title) + "</div><div class=\"result-card-body\"><div id=\"" + escapeHtml(listId)
                + "\"></div></div></div>";
    }

    private String renderDashboard(String language, String dashboardPath) {
        String title = escapeHtml(uiString("dashboardTitle", config.dashboardTitle()));
        String effectiveDashboardPath = escapeHtml(dashboardPath == null || dashboardPath.isBlank() ? "/" : dashboardPath);
        String dashboardBasePath = resolveDashboardBasePath(dashboardPath);
        String adminBasePath = escapeJs(dashboardBasePath);
        String migrationPlannerUrl = escapeHtml(appendQuery(
                dashboardBasePath + "/migration-planner",
                "lang=" + normalizeDashboardLanguage(language) + "&v=" + dashboardInstanceId
        ));
        String bootstrapCssUrl = escapeHtml(uiString("bootstrapCssUrl", "https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css"));
        String googleFontsUrl = escapeHtml(uiString("fontsUrl", "https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&family=IBM+Plex+Mono:wght@400;500&display=swap"));
        String dashboardThemeCss = uiString(
                "themeCss",
                "body{background:linear-gradient(180deg,#f6f4ef 0%,#efe9de 100%);color:#1b2733;font-family:'Inter','Segoe UI',system-ui,sans-serif;line-height:1.55;letter-spacing:-.01em}"
                        + "main{max-width:1680px;margin:0 auto}"
                        + ".navbar{backdrop-filter:saturate(1.05) blur(10px);background:rgba(22,30,38,.92)!important}"
                        + ".navbar-brand{font-weight:700;letter-spacing:-.02em;font-size:1.15rem}"
                        + ".navbar-text{max-width:56rem;font-size:.96rem;line-height:1.45}"
                        + ".lang-switch{display:flex;gap:.4rem;align-items:center}"
                        + ".lang-switch .btn{min-width:3rem;border-radius:999px;padding:.35rem .75rem;font-weight:600}"
                        + ".ops-shell{display:grid;grid-template-columns:minmax(220px,280px) minmax(0,1fr);gap:1.1rem;align-items:start}"
                        + ".ops-sidebar{position:sticky;top:.75rem;display:flex;flex-direction:column;gap:.7rem;max-height:calc(100vh - 1.5rem);overflow-y:auto;overflow-x:hidden;padding-right:.35rem;padding-bottom:.75rem}"
                        + ".ops-sidebar::-webkit-scrollbar{width:10px}.ops-sidebar::-webkit-scrollbar-thumb{background:#c8d4e3;border-radius:999px;border:2px solid transparent;background-clip:padding-box}"
                        + ".ops-workspace{min-width:0}"
                        + ".ops-utility-strip{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:.9rem;margin-bottom:1rem}"
                        + ".ops-data-status{display:flex;align-items:flex-start;justify-content:space-between;gap:1rem;padding:.95rem 1rem;border:1px solid rgba(124,145,168,.22);border-radius:1rem;background:linear-gradient(180deg,rgba(255,255,255,.98),rgba(247,250,253,.98));box-shadow:0 .65rem 1.6rem rgba(15,23,42,.07);margin-bottom:1rem}"
                        + ".ops-data-status-copy{min-width:0}"
                        + ".ops-data-status-title{font-size:.86rem;font-weight:800;color:#102a43;margin-bottom:.2rem}"
                        + ".ops-data-status-detail{font-size:.86rem;color:#52606d;line-height:1.45}"
                        + ".ops-data-status[data-state='loading']{border-color:#cfe0ff;background:linear-gradient(180deg,#f8fbff,#f3f8ff)}"
                        + ".ops-data-status[data-state='success']{border-color:#ccebd7;background:linear-gradient(180deg,#f5fff8,#effbf3)}"
                        + ".ops-data-status[data-state='error']{border-color:#f4c7c3;background:linear-gradient(180deg,#fff7f6,#fff1ef)}"
                        + ".metric-value.metric-placeholder{font-size:1.08rem;color:#627d98;font-weight:700;line-height:1.35}"
                        + ".metric-value.metric-error{font-size:1rem;color:#a61b1b;font-weight:800;line-height:1.35}"
                        + ".ops-utility-strip .nav-panel{height:auto}"
                        + ".ops-workspace > .workspace-group{padding:1.2rem 1.2rem 1.35rem;border:1px solid rgba(124,145,168,.16);border-radius:1.55rem;background:linear-gradient(180deg,rgba(255,255,255,.7),rgba(244,248,252,.94));box-shadow:0 1.25rem 2.8rem rgba(15,23,42,.08);margin-bottom:1.4rem;position:relative;overflow:hidden}"
                        + ".ops-workspace > .workspace-group::before{content:'';position:absolute;inset:0 0 auto 0;height:5px;background:linear-gradient(90deg,#2f6fed,#5fb3ff);opacity:.95}"
                        + ".nav-panel{border:1px solid rgba(124,145,168,.2);box-shadow:0 .65rem 1.8rem rgba(15,23,42,.08);border-radius:1.15rem;background:linear-gradient(180deg,rgba(255,255,255,.99),rgba(248,250,252,.97));overflow:hidden}"
                        + ".nav-panel-header{padding:.78rem .88rem .26rem .88rem;font-size:.93rem;font-weight:800;color:#102a43}"
                        + ".nav-panel-body{padding:.12rem .88rem .78rem .88rem}"
                        + ".nav-panel.compact-panel .nav-panel-body{padding:.02rem .78rem .62rem .78rem}"
                        + ".nav-panel.compact-panel .small.text-muted{font-size:.78rem;line-height:1.35;margin-bottom:.42rem !important}"
                        + ".nav-panel.compact-panel .form-label{font-size:.75rem;font-weight:700;margin-bottom:.25rem}"
                        + ".nav-panel.compact-panel .form-select,.nav-panel.compact-panel .form-control{min-height:2.2rem;padding:.4rem .65rem;border-radius:.76rem;font-size:.88rem}"
                        + ".nav-panel.compact-panel .btn{padding:.44rem .64rem;font-size:.85rem}"
                        + ".nav-panel.compact-panel .btn-danger.w-100{margin-bottom:.45rem !important}"
                        + ".compact-toolbar{display:grid;grid-template-columns:1fr 1fr;gap:.42rem;margin-top:.45rem}"
                        + ".compact-inline{display:flex;align-items:center;justify-content:space-between;gap:.55rem;margin:.32rem 0 .18rem 0}"
                        + ".compact-inline .compact-inline-label{font-size:.76rem;font-weight:700;color:#334e68;white-space:nowrap}"
                        + ".compact-help{font-size:.77rem;line-height:1.35;color:#52606d;margin-bottom:.38rem}"
                        + ".compact-status{margin-top:.45rem;padding:.48rem .58rem;border-radius:.78rem;background:#f8fbff;border:1px solid #dbe7ff;font-size:.8rem;color:#486581}"
                        + ".nav-panel.navigator-panel{display:flex;flex-direction:column;flex:0 0 auto}"
                        + ".nav-panel.navigator-panel .nav-panel-body{overflow:visible;max-height:none;padding-right:0}"
                        + ".nav-tree-list{display:flex;flex-direction:column;gap:.65rem}"
                        + ".nav-tree-section{padding-top:.1rem}"
                        + ".nav-tree-section + .nav-tree-section{border-top:1px solid #e5edf6;padding-top:.65rem}"
                        + ".nav-tree-section-title{font-size:.78rem;font-weight:800;letter-spacing:.02em;color:#7b8794;margin-bottom:.35rem}"
                        + ".nav-tree-links{display:flex;flex-direction:column;gap:.24rem}"
                        + ".nav-tree-link{display:flex;flex-direction:column;align-items:flex-start;gap:.06rem;width:100%;text-align:left;border:1px solid transparent;background:transparent;border-radius:.76rem;padding:.46rem .56rem;color:#17324d;transition:all .18s ease;position:relative;z-index:2;pointer-events:auto;cursor:pointer}"
                        + ".nav-tree-link-title{font-size:.83rem;line-height:1.2}"
                        + ".nav-tree-link-copy{font-size:.73rem;line-height:1.25;color:#627d98}"
                        + ".nav-tree-link:hover{background:#f4f8ff;border-color:#dbe7ff;transform:translateX(2px)}"
                        + ".nav-tree-link.active{background:#eef4ff;border-color:#8ab4ff;box-shadow:inset 0 0 0 1px rgba(47,111,237,.12)}"
                        + ".nav-tree-link-title{font-weight:700;font-size:.86rem}"
                        + ".nav-tree-link-copy{font-size:.74rem;color:#6b7a89;line-height:1.3}"
                        + ".workspace-group{display:block;animation:fadeWorkspace .22s ease}"
                        + ".workspace-group-header{display:flex;justify-content:space-between;gap:1rem;align-items:end;margin-bottom:1rem;padding:.2rem .2rem 1rem .2rem;border-bottom:1px solid rgba(207,217,228,.9)}"
                        + ".workspace-group-title{font-size:1.45rem;font-weight:800;color:#102a43;margin:0}"
                        + ".workspace-group-copy{color:#52606d;font-size:.95rem;max-width:62rem}"
                        + ".workspace-group-copy::before{content:'Çalışma alanı';display:block;font-size:.72rem;letter-spacing:.04em;color:#7b8794;font-weight:800;margin-bottom:.35rem}"
                        + "@keyframes fadeWorkspace{from{opacity:.2;transform:translateY(6px)}to{opacity:1;transform:none}}"
                        + ".btn{border-radius:.85rem;font-weight:600;letter-spacing:-.01em}"
                        + ".btn-sm{border-radius:.75rem}"
                        + ".metric-card{border:1px solid rgba(148,163,184,.18);box-shadow:0 .35rem 1rem rgba(15,23,42,.06);height:100%;border-radius:1rem;background:rgba(255,255,255,.88)}"
                        + ".metric-card [data-nav-card=\"true\"]{cursor:pointer}"
                        + ".metric-card [data-nav-card=\"true\"]:hover .metric-label{text-decoration:underline}"
                        + ".metric-card.metric-primary{border-top:4px solid #a54c39}"
                        + ".metric-card.metric-supporting{border-top:4px solid #2f6fed}"
                        + ".metric-label{font-size:.73rem;letter-spacing:.03em;color:#6b7a89;font-weight:700}"
                        + ".metric-unit{font-size:.72rem;background:#eef3f8;color:#486581;border-radius:999px;padding:.2rem .55rem;white-space:nowrap}"
                        + ".metric-value{font-size:1.68rem;font-weight:700;color:#102a43;line-height:1.15;margin-top:.3rem}"
                        + ".metric-help{margin-top:.55rem;font-size:.9rem;line-height:1.48;color:#52606d}"
                        + ".section-card{position:relative;border:1px solid rgba(124,145,168,.34);box-shadow:0 1rem 2.35rem rgba(15,23,42,.11), inset 0 1px 0 rgba(255,255,255,.72);border-radius:1.35rem;background:linear-gradient(180deg,rgba(255,255,255,1),rgba(246,249,252,.98));overflow:hidden;isolation:isolate;transition:transform .18s ease, box-shadow .18s ease, border-color .18s ease}"
                        + ".section-card:hover{transform:translateY(-2px);box-shadow:0 1.2rem 2.7rem rgba(15,23,42,.13), inset 0 1px 0 rgba(255,255,255,.74)}"
                        + ".section-card::before{content:'';position:absolute;left:0;top:0;bottom:0;width:8px;background:linear-gradient(180deg,#2f6fed,#5fb3ff);z-index:0}"
                        + ".section-card::after{content:'';position:absolute;left:1.2rem;right:1.2rem;top:0;height:3.5rem;border-radius:0 0 1rem 1rem;background:linear-gradient(180deg,rgba(47,111,237,.11),rgba(47,111,237,0));pointer-events:none;z-index:0}"
                        + ".section-card .card-header{position:relative;z-index:1;background:linear-gradient(180deg,rgba(255,255,255,1),rgba(243,247,251,.98));border-bottom:1px solid #d9e5ef;font-weight:800;font-size:1.06rem;padding:1.08rem 1.2rem 1rem 1.55rem;color:#102a43;box-shadow:inset 0 -1px 0 rgba(221,231,240,.9)}"
                        + ".section-card .card-body{position:relative;z-index:1;padding:1.22rem 1.2rem 1.28rem 1.55rem;background:linear-gradient(180deg,rgba(255,255,255,.96),rgba(249,251,253,.98))}"
                        + ".section-card .card-header.text-danger{color:#a61b1b !important}"
                        + ".section-card .card-body > .table-responsive,.section-card .card-body > pre,.section-card .card-body > svg,.section-card .card-body > .row > [class*='col-'] > .card{position:relative;z-index:1}"
                        + ".workspace-group[data-group='overview']::before{background:linear-gradient(90deg,#2f6fed,#65c2ff)}"
                        + ".workspace-group[data-group='overview'] .section-card::before{background:linear-gradient(180deg,#2f6fed,#65c2ff)}"
                        + ".workspace-group[data-group='health']::before{background:linear-gradient(90deg,#a54c39,#f59e0b)}"
                        + ".workspace-group[data-group='health'] .section-card::before{background:linear-gradient(180deg,#a54c39,#f59e0b)}"
                        + ".workspace-group[data-group='routing']::before{background:linear-gradient(90deg,#0f766e,#22c55e)}"
                        + ".workspace-group[data-group='routing'] .section-card::before{background:linear-gradient(180deg,#0f766e,#22c55e)}"
                        + ".workspace-group[data-group='runtime']::before{background:linear-gradient(90deg,#6d28d9,#8b5cf6)}"
                        + ".workspace-group[data-group='runtime'] .section-card::before{background:linear-gradient(180deg,#6d28d9,#8b5cf6)}"
                        + ".workspace-group[data-group='schema']::before{background:linear-gradient(90deg,#374151,#64748b)}"
                        + ".workspace-group[data-group='schema'] .section-card::before{background:linear-gradient(180deg,#374151,#64748b)}"
                        + ".workspace-group[data-group='explain']::before{background:linear-gradient(90deg,#b45309,#f97316)}"
                        + ".workspace-group[data-group='explain'] .section-card::before{background:linear-gradient(180deg,#b45309,#f97316)}"
                        + ".section-intro{margin-bottom:1rem;padding:.9rem 1rem;background:#f8fafc;border:1px solid #e6edf5;border-radius:.9rem;color:#52606d;font-size:.93rem;line-height:1.55}"
                        + ".section-scope-note{display:inline-flex;align-items:center;gap:.45rem;margin:0 0 1rem 0;padding:.46rem .78rem;border-radius:999px;background:#eef4ff;border:1px solid #d7e5ff;color:#234361;font-size:.82rem;font-weight:700;line-height:1.25}"
                        + "ops-panel,ops-metric-card,ops-data-table,ops-taxonomy-card,ops-taxonomy-chip,ops-nav-button,ops-workspace-button,ops-jump-link,ops-signal-widget,ops-signal-board{display:block}"
                        + "ops-panel.section-card,ops-metric-card.metric-card,ops-taxonomy-card.glossary-card,ops-taxonomy-chip.taxonomy-item,ops-signal-widget.signal-widget{contain:layout paint style}"
                        + ".nav-tree-link,.jump-link{user-select:none}"
                        + ".nav-tree-link:focus-visible,.jump-link:focus-visible{outline:3px solid rgba(47,111,237,.28);outline-offset:3px}"
                        + "ops-data-table.table-responsive{display:block}"
                        + "ops-metric-card.metric-card{transform-origin:center;transition:transform .18s ease,box-shadow .18s ease,border-color .18s ease}"
                        + "ops-metric-card.metric-card:hover{transform:translateY(-2px) scale(1.01)}"
                        + "ops-panel.section-card .card-header,ops-panel.section-card .card-body{backface-visibility:hidden}"
                        + ".signal-board{display:grid;grid-template-columns:repeat(12,minmax(0,1fr));gap:1rem}"
                        + ".performance-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(280px,1fr));gap:1rem}"
                        + ".signal-widget{grid-column:span 12;position:relative;padding:1.15rem 1.15rem 1.1rem 1.15rem;border-radius:1.2rem;border:1px solid rgba(148,163,184,.24);background:linear-gradient(180deg,rgba(255,255,255,1),rgba(247,250,252,.98));box-shadow:0 .7rem 1.8rem rgba(15,23,42,.08);overflow:hidden}"
                        + "@media (min-width: 900px){.signal-widget{grid-column:span 6}}"
                        + "@media (min-width: 1440px){.signal-widget{grid-column:span 4}}"
                        + ".signal-widget::before{content:'';position:absolute;left:0;top:0;bottom:0;width:7px;background:linear-gradient(180deg,#2f6fed,#65c2ff)}"
                        + ".signal-widget[data-tone='warning']::before{background:linear-gradient(180deg,#d97706,#f59e0b)}"
                        + ".signal-widget[data-tone='danger']::before{background:linear-gradient(180deg,#b42318,#f04438)}"
                        + ".signal-widget[data-tone='success']::before{background:linear-gradient(180deg,#0f766e,#22c55e)}"
                        + ".signal-head{display:flex;justify-content:space-between;gap:.75rem;align-items:flex-start;margin-bottom:.85rem}"
                        + ".signal-title{font-size:1rem;font-weight:800;color:#102a43;margin:0}"
                        + ".signal-summary{font-size:.86rem;color:#52606d;line-height:1.45;margin-top:.25rem}"
                        + ".signal-badge{display:inline-flex;align-items:center;gap:.35rem;border-radius:999px;padding:.32rem .62rem;font-size:.75rem;font-weight:800;letter-spacing:.02em}"
                        + ".signal-badge[data-tone='neutral']{background:#eef2f6;color:#334e68}"
                        + ".signal-badge[data-tone='success']{background:#dcfce7;color:#166534}"
                        + ".signal-badge[data-tone='warning']{background:#fef3c7;color:#92400e}"
                        + ".signal-badge[data-tone='danger']{background:#fee2e2;color:#b42318}"
                        + ".signal-main{display:flex;align-items:end;justify-content:space-between;gap:1rem;margin-bottom:.8rem}"
                        + ".signal-value{font-size:2rem;font-weight:800;color:#102a43;line-height:1}"
                        + ".signal-caption{font-size:.82rem;color:#6b7a89;margin-top:.35rem}"
                        + ".signal-progress{height:.55rem;border-radius:999px;background:#e9eef5;overflow:hidden;margin-bottom:.85rem}"
                        + ".signal-progress-bar{height:100%;border-radius:999px;background:linear-gradient(90deg,#2f6fed,#65c2ff);width:0}"
                        + ".signal-widget[data-tone='warning'] .signal-progress-bar{background:linear-gradient(90deg,#d97706,#f59e0b)}"
                        + ".signal-widget[data-tone='danger'] .signal-progress-bar{background:linear-gradient(90deg,#b42318,#f04438)}"
                        + ".signal-widget[data-tone='success'] .signal-progress-bar{background:linear-gradient(90deg,#0f766e,#22c55e)}"
                        + ".signal-facts{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:.75rem;margin-bottom:.8rem}"
                        + ".signal-fact{padding:.75rem .8rem;border-radius:.95rem;background:#f8fafc;border:1px solid #e4ecf4}"
                        + ".signal-fact-label{font-size:.72rem;letter-spacing:.03em;color:#7b8794;font-weight:800;margin-bottom:.25rem}"
                        + ".signal-fact-value{font-size:.92rem;font-weight:700;color:#17324d;word-break:break-word}"
                        + ".signal-action{padding:.8rem .9rem;border-radius:1rem;background:linear-gradient(180deg,#fff7ed,#fff);border:1px solid #fed7aa;color:#7c2d12;font-size:.88rem;line-height:1.45}"
                        + ".signal-widget[data-tone='success'] .signal-action{background:linear-gradient(180deg,#ecfdf5,#fff);border-color:#bbf7d0;color:#166534}"
                        + ".signal-widget[data-tone='neutral'] .signal-action{background:linear-gradient(180deg,#f8fafc,#fff);border-color:#dbe7f3;color:#334e68}"
                        + ".performance-widget{position:relative;display:flex;flex-direction:column;gap:.95rem;padding:1.05rem 1.05rem 1.1rem 1.05rem;border-radius:1.15rem;border:1px solid rgba(148,163,184,.24);background:linear-gradient(180deg,#ffffff,rgba(247,250,252,.98));box-shadow:0 .65rem 1.6rem rgba(15,23,42,.07);overflow:hidden;min-width:0}"
                        + ".performance-widget::before{content:'';position:absolute;left:0;top:0;bottom:0;width:6px;background:linear-gradient(180deg,#2f6fed,#65c2ff)}"
                        + ".performance-widget-header{display:flex;flex-direction:column;gap:.3rem;min-width:0;padding-left:.4rem}"
                        + ".performance-widget-header .signal-title{font-size:1rem;line-height:1.2;word-break:break-word}"
                        + ".performance-widget-header .signal-summary{font-size:.84rem;line-height:1.45;color:#52606d}"
                        + ".performance-actions{display:flex;align-items:flex-start;justify-content:space-between;gap:1rem;margin:0 0 1rem 0;padding:.95rem 1rem;border-radius:1rem;background:linear-gradient(180deg,#f8fbff,#fff);border:1px solid #dbe7f3}"
                        + ".performance-actions-copy{display:flex;flex-direction:column;gap:.2rem;min-width:0}"
                        + ".performance-summary-shell{margin:.2rem 0 1rem 0;padding:1rem 1.05rem;border-radius:1rem;background:linear-gradient(180deg,#f8fbff,#fff);border:1px solid #dbe7f3;box-shadow:0 .45rem 1.2rem rgba(15,23,42,.05)}"
                        + ".performance-summary-head{display:flex;align-items:flex-start;justify-content:space-between;gap:1rem;margin-bottom:.4rem}"
                        + ".performance-summary-title{font-size:.76rem;letter-spacing:.05em;text-transform:uppercase;color:#7b8794;font-weight:800;margin-bottom:.32rem}"
                        + ".performance-summary-text{font-size:.96rem;line-height:1.55;color:#17324d;font-weight:600}"
                        + ".performance-pressure-badge{display:inline-flex;align-items:center;justify-content:center;padding:.42rem .74rem;border-radius:999px;font-size:.78rem;font-weight:800;letter-spacing:.02em;white-space:nowrap}"
                        + ".performance-pressure-badge[data-tone='read']{background:#dbeafe;color:#1d4ed8}"
                        + ".performance-pressure-badge[data-tone='write']{background:#fee2e2;color:#b42318}"
                        + ".performance-pressure-badge[data-tone='balanced']{background:#dcfce7;color:#166534}"
                        + ".performance-pressure-badge[data-tone='neutral']{background:#eef2f6;color:#334e68}"
                        + ".performance-pressure-compare{margin-top:.9rem;display:flex;flex-direction:column;gap:.6rem}"
                        + ".performance-pressure-legend{display:flex;align-items:center;justify-content:space-between;gap:.85rem;flex-wrap:wrap}"
                        + ".performance-pressure-pill{display:inline-flex;align-items:center;gap:.42rem;font-size:.8rem;font-weight:700;color:#334e68}"
                        + ".performance-pressure-dot{width:.72rem;height:.72rem;border-radius:999px;display:inline-block}"
                        + ".performance-pressure-dot.read{background:#2563eb}"
                        + ".performance-pressure-dot.write{background:#dc2626}"
                        + ".performance-pressure-track{display:flex;align-items:stretch;width:100%;height:.85rem;border-radius:999px;overflow:hidden;background:#e2e8f0;box-shadow:inset 0 1px 2px rgba(15,23,42,.08)}"
                        + ".performance-pressure-fill{display:flex;align-items:center;justify-content:center;min-width:0;transition:width .25s ease}"
                        + ".performance-pressure-fill.read{background:linear-gradient(90deg,#60a5fa,#2563eb)}"
                        + ".performance-pressure-fill.write{background:linear-gradient(90deg,#fb7185,#dc2626)}"
                        + ".performance-pressure-footer{display:flex;align-items:center;justify-content:space-between;gap:.8rem;font-size:.78rem;color:#52606d;flex-wrap:wrap}"
                        + ".performance-widget-stats{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:.8rem;padding-left:.4rem}"
                        + ".performance-stat{padding:.72rem .78rem;border-radius:.9rem;background:#f8fafc;border:1px solid #e4ecf4;min-width:0}"
                        + ".performance-stat-label{font-size:.72rem;letter-spacing:.03em;color:#7b8794;font-weight:800;margin-bottom:.28rem}"
                        + ".performance-stat-value{font-size:1.02rem;font-weight:700;color:#17324d;line-height:1.25;word-break:break-word}"
                        + ".performance-stat-value.trend-up{color:#b45309}"
                        + ".performance-stat-value.trend-down{color:#0f766e}"
                        + ".performance-stat-value.trend-flat{color:#17324d}"
                        + ".performance-widget-note{padding:.78rem .88rem;border-radius:.95rem;background:linear-gradient(180deg,#f8fbff,#fff);border:1px solid #dbe7f3;color:#334e68;font-size:.85rem;line-height:1.45;margin-left:.4rem}"
                        + ".performance-shape-block{padding:.82rem .88rem;border-radius:.95rem;background:linear-gradient(180deg,#fff7ed,#ffffff);border:1px solid #f6d7b8;color:#7c2d12;font-size:.84rem;line-height:1.45;margin-left:.4rem}"
                        + ".performance-shape-title{font-size:.76rem;font-weight:800;letter-spacing:.08em;text-transform:uppercase;color:#b45309;margin-bottom:.55rem}"
                        + ".performance-shape-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:.55rem}"
                        + ".performance-shape-stat{padding:.58rem .65rem;border-radius:.8rem;background:rgba(255,255,255,.86);border:1px solid rgba(245,158,11,.18)}"
                        + ".performance-shape-stat-label{font-size:.7rem;font-weight:700;letter-spacing:.06em;text-transform:uppercase;color:#9a3412}"
                        + ".performance-shape-stat-value{font-size:1rem;font-weight:800;color:#7c2d12;line-height:1.15;margin-top:.18rem}"
                        + ".performance-shape-stat-hint{font-size:.72rem;color:#92400e;margin-top:.18rem}"
                        + ".performance-widget-footer{display:flex;align-items:center;justify-content:space-between;gap:.85rem;padding-left:.4rem}"
                        + ".performance-rank-badge{display:inline-flex;align-items:center;justify-content:center;min-width:2rem;height:2rem;border-radius:999px;font-size:.82rem;font-weight:800;color:#fff;background:linear-gradient(180deg,#2f6fed,#1e40af);box-shadow:0 .4rem .9rem rgba(47,111,237,.24)}"
                        + ".performance-rank-badge[data-rank='2']{background:linear-gradient(180deg,#0f766e,#155e75)}"
                        + ".performance-rank-badge[data-rank='3']{background:linear-gradient(180deg,#7c3aed,#4c1d95)}"
                        + ".performance-action-link{display:inline-flex;align-items:center;justify-content:center;padding:.55rem .8rem;border-radius:.85rem;border:1px solid #c8d8ea;background:#fff;color:#1d4ed8;font-size:.82rem;font-weight:700;text-decoration:none;transition:all .16s ease}"
                        + ".performance-action-link:hover{transform:translateY(-1px);border-color:#93c5fd;color:#1e3a8a;background:#eff6ff}"
                        + "@media (max-width: 767px){.performance-grid{grid-template-columns:1fr}.performance-widget-stats{grid-template-columns:1fr}.performance-shape-grid{grid-template-columns:1fr}.performance-actions{flex-direction:column;align-items:stretch}}"
                        + ".mono{font-family:'IBM Plex Mono','JetBrains Mono',Consolas,monospace;word-break:break-all;font-size:.92em}"
                        + "pre{background:#111927;color:#edf4ff;padding:1rem 1.05rem;border-radius:.95rem;max-height:24rem;border:1px solid rgba(148,163,184,.18)}"
                        + "svg{background:#fbf7ef;border-radius:.9rem;border:1px solid rgba(148,163,184,.15)}"
                        + ".table-responsive{overflow-x:auto;overflow-y:hidden;border:1px solid rgba(226,232,240,.9);border-radius:1rem;background:linear-gradient(180deg,#ffffff,#fbfdff);box-shadow:inset 0 1px 0 rgba(255,255,255,.8);scrollbar-width:thin}"
                        + ".table-responsive .table{width:100%;min-width:100%;table-layout:fixed}"
                        + ".table-responsive td,.table-responsive th{white-space:normal;text-overflow:clip;overflow:visible;word-break:break-word;overflow-wrap:anywhere}"
                        + ".table-sm td,.table-sm th{vertical-align:top;padding:.52rem .58rem;font-size:.82rem;line-height:1.35}"
                        + ".table thead th{font-size:.69rem;letter-spacing:.03em;color:#6b7a89;background:#f8fafc;border-bottom:1px solid #e8edf3;position:sticky;top:0;z-index:1;padding:.5rem .58rem}"
                        + ".table tbody tr:hover{background:rgba(248,250,252,.88)}"
                        + ".table td .badge{white-space:normal;line-height:1.2}"
                        + ".table td .small{font-size:.76rem}"
                        + ".table td .mono,.table th .mono{white-space:normal;word-break:break-word;overflow-wrap:anywhere}"
                        + ".cell-text{display:block;max-width:none;white-space:normal;overflow:visible;text-overflow:clip;word-break:break-word;overflow-wrap:anywhere;line-height:1.34}"
                        + ".taxonomy-item{padding:1rem 1rem;border-radius:1rem;background:#f8fafc;border:1px solid #e9ecef;height:100%}"
                        + ".taxonomy-item h6{margin-bottom:.45rem;font-size:.95rem;font-weight:700}"
                        + ".glossary-search{border-radius:.9rem;padding:.8rem 1rem;border:1px solid #d9e2ec;background:#fff}"
                        + ".glossary-card{border:1px solid rgba(148,163,184,.18);border-radius:1rem;background:#fff;height:100%;box-shadow:0 .3rem .9rem rgba(15,23,42,.04)}"
                        + ".glossary-card .card-body{padding:1rem 1rem 1.05rem}"
                        + ".glossary-meta{display:flex;gap:.45rem;flex-wrap:wrap;margin-bottom:.7rem}"
                        + ".glossary-meta .badge{font-weight:600}"
                        + ".glossary-definition{font-size:.93rem;color:#334e68;line-height:1.55;margin-bottom:.7rem}"
                        + ".glossary-subtitle{font-size:.78rem;letter-spacing:.03em;color:#7b8794;font-weight:700;margin-bottom:.25rem}"
                        + ".glossary-empty{padding:1rem;border:1px dashed #cbd5e1;border-radius:.9rem;background:#fff;color:#52606d}"
                        + ".jump-nav{display:flex;flex-wrap:wrap;gap:.85rem}"
                        + ".jump-nav .jump-link{display:inline-flex;align-items:center;justify-content:center;min-height:3rem;padding:.9rem 1.15rem;border-radius:1rem;border:1px solid rgba(47,111,237,.22);background:linear-gradient(180deg,#ffffff 0%,#f6f9ff 100%);box-shadow:0 .45rem 1rem rgba(15,23,42,.08);color:#12344d;font-weight:700;font-size:.96rem;line-height:1.2;transition:transform .18s ease,box-shadow .18s ease,border-color .18s ease,background .18s ease}"
                        + ".jump-nav .jump-link:hover,.jump-nav .jump-link:focus{transform:translateY(-2px);box-shadow:0 .65rem 1.25rem rgba(15,23,42,.14);border-color:rgba(47,111,237,.45);background:linear-gradient(180deg,#ffffff 0%,#eef4ff 100%);color:#0b2540}"
                        + ".jump-nav .jump-link:active{transform:translateY(0);box-shadow:0 .35rem .85rem rgba(15,23,42,.12)}"
                        + ".scroll-jump-stack{position:fixed;right:1.25rem;bottom:1.25rem;z-index:1080;display:flex;flex-direction:column;gap:.55rem}"
                        + ".scroll-jump{border-radius:999px;box-shadow:0 .6rem 1.2rem rgba(31,41,51,.16);min-width:9rem;padding:.72rem 1rem;background:#102a43;border:0}"
                        + "@media (max-width: 1199px){.ops-shell{grid-template-columns:1fr}.ops-sidebar{position:static;max-height:none;overflow:visible}.ops-utility-strip{grid-template-columns:1fr}.workspace-group-header{flex-direction:column;align-items:flex-start}.ops-workspace > .workspace-group{padding:1rem}}"
        );
        String navbarSubtitle = escapeHtml(uiString(
                "navbarSubtitle",
                "Operational monitoring console for cache health, write-behind flow, recovery, query behavior, and incident triage."
        ));
        String uiRevisionBadge = escapeHtml(uiString("uiRevisionBadge", localized(language, "Arayüz sürümü: navigator-v3", "UI revision: navigator-v3")));
        String loadingText = escapeHtml(uiString("loadingText", "Loading…"));
        String resetToolsTitle = escapeHtml(uiString("resetToolsTitle", "Admin Reset Tools"));
        String resetTelemetryLabel = escapeHtml(uiString("resetTelemetryLabel", "Reset Telemetry History"));
        String migrationPlannerTitle = escapeHtml(uiString("migrationPlannerTitle", localized(language, "Geçiş Planlayıcı", "Migration Planner")));
        String migrationPlannerIntro = escapeHtml(uiString(
                "migrationPlannerIntro",
                localized(language,
                        "Mevcut PostgreSQL/ORM ekranını CacheDB sıcak pencere, projection ve warm plan kararlarına dönüştürür.",
                        "Turns an existing PostgreSQL/ORM route into CacheDB hot-window, projection, and warm-plan decisions.")
        ));
        String migrationPlannerOpenLabel = escapeHtml(uiString("migrationPlannerOpenLabel", localized(language, "Planlayıcıyı Aç", "Open Planner")));
        String resetTelemetryDescription = escapeHtml(uiString(
                "resetTelemetryDescription",
                "Diagnostics, incident history, monitoring history, alert route history ve gecikme/performance olcumlerini temizler. Demo entity verisini silmez."
        ));
        String liveRefreshTitle = escapeHtml(uiString("liveRefreshTitle", "Live Refresh"));
        String howToReadTitle = escapeHtml(uiString("howToReadTitle", "How To Read This Dashboard"));
        String sectionGuideTitle = escapeHtml(uiString("sectionGuideTitle", "Section Guide"));
        String liveTrendsTitle = escapeHtml(uiString("liveTrendsTitle", "Live Trends"));
        String alertRouteTrendsTitle = escapeHtml(uiString("alertRouteTrendsTitle", "Alert Route Trends"));
        String incidentSeverityTrendsTitle = escapeHtml(uiString("incidentSeverityTrendsTitle", "Incident Severity Trends"));
        String topFailingSignalsTitle = escapeHtml(uiString("topFailingSignalsTitle", "Top Failing Signals"));
        String triageTitle = escapeHtml(uiString("triageTitle", "Triage"));
        String serviceStatusTitle = escapeHtml(uiString("serviceStatusTitle", "Service Status"));
        String backgroundErrorsTitle = escapeHtml(uiString("backgroundErrorsTitle", localized(language, "Arka Plan Hataları", "Background Worker Errors")));
        String healthTitle = escapeHtml(uiString("healthTitle", "Health"));
        String incidentsTitle = escapeHtml(uiString("incidentsTitle", "Incidents"));
        String deploymentTitle = escapeHtml(uiString("deploymentTitle", "Deployment"));
        String schemaStatusTitle = escapeHtml(uiString("schemaStatusTitle", "Schema Status"));
        String schemaHistoryTitle = escapeHtml(uiString("schemaHistoryTitle", "Schema History"));
        String starterProfilesTitle = escapeHtml(uiString("starterProfilesTitle", "Starter Profiles"));
        String apiRegistryTitle = escapeHtml(uiString("apiRegistryTitle", "API Registry"));
        String currentEffectiveTuningTitle = escapeHtml(uiString("currentEffectiveTuningTitle", "Current Effective Tuning"));
        String runtimeProfileControlTitle = escapeHtml(uiString("runtimeProfileControlTitle", "Runtime Profile Control"));
        String certificationTitle = escapeHtml(uiString("certificationTitle", "Certification"));
        String alertRoutingTitle = escapeHtml(uiString("alertRoutingTitle", "Alert Routing"));
        String runbooksTitle = escapeHtml(uiString("runbooksTitle", "Runbooks"));
        String alertRouteHistoryTitle = escapeHtml(uiString("alertRouteHistoryTitle", "Alert Route History"));
        String schemaDdlTitle = escapeHtml(uiString("schemaDdlTitle", "Schema DDL"));
        String runtimeProfileChurnTitle = escapeHtml(uiString("runtimeProfileChurnTitle", "Runtime Profile Churn"));
        String explainTitle = escapeHtml(uiString("explainTitle", "Explain"));
        String metricWriteBehindTitle = escapeHtml(uiString("metric.writeBehindTitle", "Write-behind"));
        String metricDeadLetterTitle = escapeHtml(uiString("metric.deadLetterTitle", "Dead-letter"));
        String metricDiagnosticsTitle = escapeHtml(uiString("metric.diagnosticsTitle", "Diagnostics"));
        String metricIncidentsTitle = escapeHtml(uiString("metric.incidentsTitle", "Incidents"));
        String metricLearnedStatsTitle = escapeHtml(uiString("metric.learnedStatsTitle", "Learned Stats"));
        String metricRedisMemoryTitle = escapeHtml(uiString("metric.redisMemoryTitle", "Redis Memory"));
        String metricCompactionPendingTitle = escapeHtml(uiString("metric.compactionPendingTitle", "Compaction Pending"));
        String metricRuntimeProfileTitle = escapeHtml(uiString("metric.runtimeProfileTitle", "Runtime Profile"));
        String metricAlertDeliveredTitle = escapeHtml(uiString("metric.alertDeliveredTitle", "Alert Delivered"));
        String metricAlertFailedTitle = escapeHtml(uiString("metric.alertFailedTitle", "Alert Failed"));
        String metricAlertDroppedTitle = escapeHtml(uiString("metric.alertDroppedTitle", "Alert Dropped"));
        String metricCriticalSignalsTitle = escapeHtml(uiString("metric.criticalSignalsTitle", "Critical Signals"));
        String metricWarningSignalsTitle = escapeHtml(uiString("metric.warningSignalsTitle", "Warning Signals"));
        String metricProjectionRefreshDlqTitle = escapeHtml(localized(language, "Projection Refresh DLQ", "Projection Refresh DLQ"));
        String metricProjectionRefreshPendingTitle = escapeHtml(localized(language, "Projection Refresh Pending", "Projection Refresh Pending"));
        String metricWriteBehindUnit = escapeHtml(uiString("metric.writeBehindUnit", "count"));
        String metricDeadLetterUnit = escapeHtml(uiString("metric.deadLetterUnit", "count"));
        String metricDiagnosticsUnit = escapeHtml(uiString("metric.diagnosticsUnit", "count"));
        String metricIncidentsUnit = escapeHtml(uiString("metric.incidentsUnit", "count"));
        String metricLearnedStatsUnit = escapeHtml(uiString("metric.learnedStatsUnit", "count"));
        String metricRedisMemoryUnit = escapeHtml(uiString("metric.redisMemoryUnit", "bytes"));
        String metricCompactionPendingUnit = escapeHtml(uiString("metric.compactionPendingUnit", "count"));
        String metricRuntimeProfileUnit = escapeHtml(uiString("metric.runtimeProfileUnit", "state"));
        String metricAlertDeliveredUnit = escapeHtml(uiString("metric.alertDeliveredUnit", "count"));
        String metricAlertFailedUnit = escapeHtml(uiString("metric.alertFailedUnit", "count"));
        String metricAlertDroppedUnit = escapeHtml(uiString("metric.alertDroppedUnit", "count"));
        String metricCriticalSignalsUnit = escapeHtml(uiString("metric.criticalSignalsUnit", "count"));
        String metricWarningSignalsUnit = escapeHtml(uiString("metric.warningSignalsUnit", "count"));
        String metricProjectionRefreshDlqUnit = escapeHtml(localized(language, "adet", "count"));
        String metricProjectionRefreshPendingUnit = escapeHtml(localized(language, "adet", "count"));
        String metricWriteBehindDescription = escapeHtml(uiString("metric.writeBehindDescription", "Redis write-behind kuyruğunda henüz flush edilmemiş iş sayısı."));
        String metricDeadLetterDescription = escapeHtml(uiString("metric.deadLetterDescription", "Normal akışta yazılamayan ve replay bekleyen kayıt sayısı."));
        String metricDiagnosticsDescription = escapeHtml(uiString("metric.diagnosticsDescription", "Tanılama akışında biriken admin/debug kayıtlarının sayısı."));
        String metricIncidentsDescription = escapeHtml(uiString("metric.incidentsDescription", "Şu an aktif alarm veya sağlık problemi başlığı sayısı."));
        String metricLearnedStatsDescription = escapeHtml(uiString("metric.learnedStatsDescription", "Query planner için tutulmuş öğrenilmiş istatistik anahtarı sayısı."));
        String metricRedisMemoryDescription = escapeHtml(uiString("metric.redisMemoryDescription", "Redis used_memory. Entity, cache ve stream anahtarlarının toplam belleği."));
        String metricCompactionPendingDescription = escapeHtml(uiString("metric.compactionPendingDescription", "Sıkıştırılmış ama henüz tam işlenmemiş pending durum sayısı."));
        String metricRuntimeProfileDescription = escapeHtml(uiString("metric.runtimeProfileDescription", "Aktif runtime profili ve son gözlenen pressure seviyesi."));
        String metricAlertDeliveredDescription = escapeHtml(uiString("metric.alertDeliveredDescription", "Alert route’larında başarıyla teslim edilen toplam bildirim sayısı."));
        String metricAlertFailedDescription = escapeHtml(uiString("metric.alertFailedDescription", "Alert route’larında teslimatı başarısız olan toplam bildirim sayısı."));
        String metricAlertDroppedDescription = escapeHtml(uiString("metric.alertDroppedDescription", "Teslim edilmeden düşürülen veya elenen alert sayısı."));
        String metricCriticalSignalsDescription = escapeHtml(uiString("metric.criticalSignalsDescription", "Critical severity seviyesinde açık kalan aktif sinyal sayısı."));
        String metricWarningSignalsDescription = escapeHtml(uiString("metric.warningSignalsDescription", "Warning severity seviyesinde açık kalan aktif sinyal sayısı."));
        String metricProjectionRefreshDlqDescription = escapeHtml(localized(
                language,
                "Projection refresh kalıcı başarısız kayıt kuyruğunda bekleyen read-model olay sayısı.",
                "Number of read-model refresh events currently waiting in the projection refresh dead-letter queue."
        ));
        String metricProjectionRefreshPendingDescription = escapeHtml(localized(
                language,
                "Projection refresh consumer group'unda henüz tamamlanmamış bekleyen olay sayısı.",
                "Number of projection refresh events currently pending in the consumer group."
        ));
        String projectionRefreshSectionTitle = escapeHtml(localized(language, "Projection Refresh", "Projection Refresh"));
        String projectionRefreshSectionIntro = escapeHtml(localized(
                language,
                "Bu bölüm read-model/projection yenileme hattını ayrı izler. Kalıcı başarısız olaylar, bekleyen işler ve replay akışı özellikle çok düğümlü production kurulumlarında burada takip edilir.",
                "This section tracks the read-model/projection refresh pipeline separately. Watch dead-lettered events, pending work, and replay activity here, especially in multi-node production deployments."
        ));
        String projectionRefreshSummaryTitle = escapeHtml(localized(language, "Pipeline Özeti", "Pipeline Summary"));
        String projectionRefreshSummaryBody = escapeHtml(localized(
                language,
                "Akış uzunluğu, bekleyen olay, başarısız olay ve son işlenen zaman birlikte okunmalıdır.",
                "Read stream length, pending work, failed events, and last processed time together."
        ));
        String projectionRefreshFailuresTitle = escapeHtml(localized(language, "Başarısız Projection Refresh Olayları", "Failed Projection Refresh Events"));
        String projectionRefreshFailuresIntro = escapeHtml(localized(
                language,
                "Bu tablo poison queue'ya taşınan projection refresh olaylarını gösterir. Replay aksiyonu tek tek yeniden kuyruğa alır.",
                "This table lists projection refresh events moved to the poison queue. Replay re-enqueues an event one at a time."
        ));
        String projectionRefreshReplayStatusIdle = escapeHtml(localized(language, "Projection refresh replay araçları hazır.", "Projection refresh replay tools are ready."));
        String resetTelemetryNotRunYet = escapeHtml(uiString("resetTelemetryNotRunYet", "No telemetry reset has been run yet."));
        String autoRefreshLabel = escapeHtml(uiString("autoRefreshLabel", "Auto Refresh"));
        String lastUpdatedLabel = escapeHtml(uiString("lastUpdatedLabel", "Last Updated"));
        String lastUpdatedNever = escapeHtml(uiString("lastUpdatedNever", "never"));
        String primarySignalsTitle = escapeHtml(uiString("primarySignalsTitle", "Primary Signals"));
        String primarySignalsBody = escapeHtml(uiString("primarySignalsBody", "İlk bakılacak 6 ana gösterge. Yük artınca önce bunlar oynar."));
        String supportingSignalsTitle = escapeHtml(uiString("supportingSignalsTitle", "Supporting Signals"));
        String supportingSignalsBody = escapeHtml(uiString("supportingSignalsBody", "Ana göstergelerin neden değiştiğini ve operasyon üzerindeki etkisini anlamaya yardımcı olur."));
        String signalDashboardTitle = escapeHtml(localized(language, "Sinyal Panosu", "Signal Dashboard"));
        String signalDashboardIntro = escapeHtml(localized(language, "Bu pano, temel sinyalleri tek tek kart okumadan yorumlamanı sağlar. Her kutu güncel durumu, kısa nedeni ve ilk bakılacak yönü birlikte gösterir.", "This board helps you interpret core signals without reading every card one by one. Each widget combines the current state, the short reason, and the next direction to inspect."));
        String metricTaxonomyTitle = escapeHtml(uiString("metricTaxonomyTitle", "Metric Taxonomy"));
        String operationalTaxonomyTitle = escapeHtml(localized(language, "Terim Sözlüğü", "Operational Taxonomy"));
        String taxonomyQueueTitle = escapeHtml(uiString("metricTaxonomy.queueTitle", "Queue / Backlog"));
        String taxonomyQueueBody = escapeHtml(uiString("metricTaxonomy.queueBody", "Unit: count. If this number keeps rising, the write path may be falling behind."));
        String taxonomyMemoryTitle = escapeHtml(uiString("metricTaxonomy.memoryTitle", "Memory"));
        String taxonomyMemoryBody = escapeHtml(uiString("metricTaxonomy.memoryBody", "Unit: bytes. Redis memory is shown in a human-readable format while preserving the raw byte value."));
        String taxonomyHealthTitle = escapeHtml(uiString("metricTaxonomy.healthTitle", "Health / Signals"));
        String taxonomyHealthBody = escapeHtml(uiString("metricTaxonomy.healthBody", "Unit: count or state. Incident and signal cards show how many problems are currently open."));
        String taxonomyDeliveryTitle = escapeHtml(uiString("metricTaxonomy.deliveryTitle", "Delivery"));
        String taxonomyDeliveryBody = escapeHtml(uiString("metricTaxonomy.deliveryBody", "Unit: count. Summarizes delivery, failure, and drop behavior across alert routes."));
        String metricTaxonomyIntro = escapeHtml(uiString("metricTaxonomy.intro", "Bu bölüm, üst tarafta gördüğün metriklerin neyi anlattığını, hangi birimle okunduğunu ve hangi operasyon sorusuna cevap verdiğini kısa ve net biçimde özetler."));
        String taxonomyQueryTitle = escapeHtml(uiString("metricTaxonomy.queryTitle", "Query / Explain"));
        String taxonomyQueryBody = escapeHtml(uiString("metricTaxonomy.queryBody", "Planner, relation states, and estimated cost live here. It helps explain why a query became slow or degraded."));
        String taxonomyControlsTitle = escapeHtml(uiString("metricTaxonomy.controlsTitle", "Controls / Reset"));
        String taxonomyControlsBody = escapeHtml(uiString("metricTaxonomy.controlsBody", "No unit. Refresh, reset, and tuning export actions live here. They manage observation flow and diagnostic cleanup."));
        String taxonomyUnitsTitle = escapeHtml(uiString("metricTaxonomy.unitsTitle", "Units / Status"));
        String taxonomyUnitsBody = escapeHtml(uiString("metricTaxonomy.unitsBody", "Adet, byte ve durum etiketleri burada ayrılır. Sayısal metriklerle durum göstergelerini karıştırmamak için önce bu bölüme bakılır."));
        String jumpNavTitle = escapeHtml(localized(language, "Bölüme Git", "Jump to Section"));
        String jumpNavIntro = escapeHtml(localized(language, "Uzun dashboard içinde istediğin bölüme tek tıkla geç.", "Use these shortcuts to jump directly to the section you want."));
        String jumpNavTaxonomy = escapeHtml(localized(language, "Metrik Rehberi", "Metric Taxonomy"));
        String jumpNavPrimary = escapeHtml(localized(language, "Ana Göstergeler", "Primary Signals"));
        String jumpNavTrends = escapeHtml(localized(language, "Canlı Trendler", "Live Trends"));
        String jumpNavTriage = escapeHtml(localized(language, "Önceliklendirme", "Triage"));
        String jumpNavIncidents = escapeHtml(localized(language, "Olaylar", "Incidents"));
        String jumpNavRouting = escapeHtml(localized(language, "Uyarı Yönlendirme", "Alert Routing"));
        String jumpNavTuning = escapeHtml(localized(language, "Etkin Ayarlar", "Effective Tuning"));
        String jumpNavExplain = escapeHtml(localized(language, "Açıklama", "Explain"));
        String workspaceNavTitle = escapeHtml(localized(language, "Operasyon Gezgini v3", "Operations Navigator v3"));
        String workspaceNavIntro = escapeHtml(localized(language, "Soldaki başlıklardan bir çalışma alanı seç. Böylece ekranda yalnızca ilgili bilgi grubu görünür.", "Choose a workspace from the left. The console will show only the relevant information group."));
        String workspaceAllLabel = escapeHtml(localized(language, "Tüm Bölümler", "All Sections"));
        String workspaceOverviewLabel = escapeHtml(localized(language, "Genel Bakış", "Overview"));
        String workspaceHealthLabel = escapeHtml(localized(language, "Sağlık ve Teşhis", "Health & Diagnosis"));
        String workspaceRoutingLabel = escapeHtml(localized(language, "Uyarı Akışı", "Alert Delivery"));
        String workspaceRuntimeLabel = escapeHtml(localized(language, "Çalışma Ayarları", "Runtime Controls"));
        String workspaceSchemaLabel = escapeHtml(localized(language, "Şema ve Kayıtlar", "Schema & Registry"));
        String workspaceExplainLabel = escapeHtml(localized(language, "Sorgu Analizi", "Query Analysis"));
        String operationalTaxonomyIntro = escapeHtml(localized(
                language,
                "Bu sözlük ekranda gördüğün tüm önemli terimleri açıklar. Her kartta terimin ne olduğu, birimi, nasıl okunacağı ve ne zaman dikkat gerektirdiği yazıyor.",
                "This glossary explains the important terms shown on the screen. Each card tells you what the term means, which unit it uses, how to read it, and when it deserves attention."
        ));
        String operationalTaxonomySearchLabel = escapeHtml(localized(language, "Terim ara", "Search term"));
        String operationalTaxonomySearchPlaceholder = escapeHtml(localized(language, "Örn: dead-letter, route status, estimated cost", "Example: dead-letter, route status, estimated cost"));
        String operationalTaxonomyCountLabel = escapeHtml(localized(language, "Gösterilen terim", "Visible terms"));
        String operationalTaxonomyEmpty = escapeHtml(localized(language, "Aramaya uyan bir tanım bulunamadı.", "No glossary entry matched your search."));
        String howToReadStep1Title = escapeHtml(uiString("howToRead.step1Title", "1. First look"));
        String howToReadStep1Body = escapeHtml(uiString("howToRead.step1Body", "Check the top cards for write-behind, Redis memory, incidents, and critical signals. These usually move first as load rises."));
        String howToReadStep2Title = escapeHtml(uiString("howToRead.step2Title", "2. Where is the problem?"));
        String howToReadStep2Body = escapeHtml(uiString("howToRead.step2Body", "Triage and Service Status summarize the primary bottleneck and the most likely root cause. Start there operationally."));
        String howToReadStep3Title = escapeHtml(uiString("howToRead.step3Title", "3. What should I do next?"));
        String howToReadStep3Body = escapeHtml(uiString("howToRead.step3Body", "Alert Routing, Runbooks, and Top Failing Signals show which signal is growing and what action is appropriate."));
        String sectionGuideLiveTrendsTitle = escapeHtml(uiString("sectionGuide.liveTrendsTitle", "Live Trends"));
        String sectionGuideLiveTrendsBody = escapeHtml(uiString("sectionGuide.liveTrendsBody", "Follow backlog, memory, and dead-letter movement over time. This is the first place we spot rising load."));
        String sectionGuideTriageTitle = escapeHtml(uiString("sectionGuide.triageTitle", "Triage"));
        String sectionGuideTriageBody = escapeHtml(uiString("sectionGuide.triageBody", "Summarizes the current primary bottleneck in one line so we can decide where to start."));
        String sectionGuideTopSignalsTitle = escapeHtml(uiString("sectionGuide.topSignalsTitle", "Top Failing Signals"));
        String sectionGuideTopSignalsBody = escapeHtml(uiString("sectionGuide.topSignalsBody", "Highlights the noisiest or most severe failure signals so the alarm surface is easier to read."));
        String sectionGuideRoutingTitle = escapeHtml(uiString("sectionGuide.routingTitle", "Alert Routing / Runbooks"));
        String sectionGuideRoutingBody = escapeHtml(uiString("sectionGuide.routingBody", "Shows where the alert goes and which first action the team should take."));
        String performanceSectionTitle = escapeHtml(uiString("performance.sectionTitle", localized(language, "Gecikme ve Performans", "Latency & Performance")));
        String performanceSectionIntro = escapeHtml(uiString("performance.sectionIntro", localized(language, "Bu bölüm Redis ve PostgreSQL üzerinde gözlenen okuma ve yazma gecikmelerini gösterir. Ortalama, p95 ve p99 değerleri tek tek işlemlerin değil, son gözlenen işlem örneklerinin akışını özetler.", "This section shows observed read and write latency across Redis and PostgreSQL. Average, p95, and p99 summarize the recent flow of observed operations rather than a single request.")));
        String performanceScopeNote = escapeHtml(uiString("performance.scopeNote", localized(language, "Son resetten beri biriken süreç içi gözlemler", "Process-local observations accumulated since the last reset")));
        String performanceTrendIntro = escapeHtml(uiString("performance.trendIntro", localized(language, "Bu kartlar son örnekler üzerinden gecikmenin yükselip düşmesini ve işlem akışının yoğunlaşıp sakinleşmesini birlikte gösterir.", "These cards show whether latency is rising or falling together with whether operation flow is intensifying or easing.")));
        String performanceScenarioIntro = escapeHtml(uiString("performance.scenarioIntro", localized(language, "Bu bölüm demo yükünün hangi okuma ve yazma senaryosunda Redis veya PostgreSQL tarafını daha çok zorladığını ayırır.", "This section separates which demo read and write scenarios are stressing Redis or PostgreSQL the most.")));
        String performanceTopReadTitle = escapeHtml(uiString("performance.topReadTitle", localized(language, "En Pahalı 3 Redis Okuma", "Top 3 Costliest Redis Reads")));
        String performanceTopReadIntro = escapeHtml(uiString("performance.topReadIntro", localized(language, "Redis okuma yolunda en yüksek p95 gecikmeyi üreten akışlar burada öne çıkar.", "The flows producing the highest Redis read p95 latency are highlighted here.")));
        String performanceTopWriteTitle = escapeHtml(uiString("performance.topWriteTitle", localized(language, "En Pahalı 3 Redis Yazma", "Top 3 Costliest Redis Writes")));
        String performanceTopWriteIntro = escapeHtml(uiString("performance.topWriteIntro", localized(language, "Redis yazma yolunda en yüksek p95 gecikmeyi üreten burst'ler burada öne çıkar.", "The bursts producing the highest Redis write p95 latency are highlighted here.")));
        String performanceTopPostgresReadTitle = escapeHtml(uiString("performance.topPostgresReadTitle", localized(language, "En Pahalı 3 PostgreSQL Okuma", "Top 3 Costliest PostgreSQL Reads")));
        String performanceTopPostgresReadIntro = escapeHtml(uiString("performance.topPostgresReadIntro", localized(language, "Doğrudan PostgreSQL okuma yolunda en yüksek p95 gecikmeyi üreten senaryolar burada öne çıkar.", "The scenarios producing the highest PostgreSQL read p95 latency are highlighted here.")));
        String performanceTopPostgresWriteTitle = escapeHtml(uiString("performance.topPostgresWriteTitle", localized(language, "En Pahalı 3 PostgreSQL Yazma", "Top 3 Costliest PostgreSQL Writes")));
        String performanceTopPostgresWriteIntro = escapeHtml(uiString("performance.topPostgresWriteIntro", localized(language, "Write-behind flush hattında en yüksek p95 gecikmeyi üreten yazma burst'leri burada öne çıkar.", "The write bursts producing the highest PostgreSQL write-behind p95 latency are highlighted here.")));
        String performanceBottleneckTitle = escapeHtml(uiString("performance.bottleneckTitle", localized(language, "Şu Anki Ana Darboğaz", "Current Main Bottleneck")));
        String performanceBottleneckIdle = escapeHtml(uiString("performance.bottleneckIdle", localized(language, "Henüz senaryo bazlı performans örneği yok. Yükü kısa süre çalıştırınca burada en pahalı akışın özeti görünür.", "There is no scenario-level performance sample yet. Run load briefly and this area will summarize the costliest flow.")));
        String performanceOtherScenarioTitle = escapeHtml(uiString("performance.otherScenarioTitle", localized(language, "Diğer Senaryo Kartları", "Other Scenario Cards")));
        String performanceResetLabel = escapeHtml(uiString("performance.resetLabel", localized(language, "Performans Ölçümlerini Temizle", "Clear Performance Metrics")));
        String performanceResetIdle = escapeHtml(uiString("performance.resetIdle", localized(language, "Yalnız performans metriklerini temizler; diğer telemetriyi etkilemez.", "Clears only performance metrics without affecting the rest of telemetry.")));
        String performanceResetInProgress = escapeHtml(uiString("performance.resetInProgress", localized(language, "Performans metrikleri temizleniyor...", "Clearing performance metrics...")));
        String performanceResetResultPrefix = escapeHtml(uiString("performance.resetResultPrefix", localized(language, "Temizlendi: performans işlemi ", "Cleared: performance ops ")));
        String performanceResetHistorySegment = escapeHtml(uiString("performance.resetHistorySegment", localized(language, ", performans geçmişi ", ", performance history ")));
        String performanceNavCopy = escapeHtml(localized(language, "Redis ve PostgreSQL gecikmesini buradan izlersin.", "Monitor Redis and PostgreSQL latency here."));
        String performanceRedisReadTitle = escapeHtml(uiString("performance.redisReadTitle", localized(language, "Redis Okuma", "Redis Read")));
        String performanceRedisWriteTitle = escapeHtml(uiString("performance.redisWriteTitle", localized(language, "Redis Yazma", "Redis Write")));
        String performancePostgresReadTitle = escapeHtml(uiString("performance.postgresReadTitle", localized(language, "PostgreSQL Okuma", "PostgreSQL Read")));
        String performancePostgresWriteTitle = escapeHtml(uiString("performance.postgresWriteTitle", localized(language, "PostgreSQL Yazma", "PostgreSQL Write")));
        String performanceRedisReadBody = escapeHtml(uiString("performance.redisReadBody", localized(language, "Repository üzerinden yapılan Redis okuma akışının son gecikme görünümü.", "Recent latency view for repository-driven Redis reads.")));
        String performanceRedisWriteBody = escapeHtml(uiString("performance.redisWriteBody", localized(language, "Save ve delete çağrılarının Redis üzerindeki yazma gecikmesi.", "Write latency on Redis for save and delete calls.")));
        String performancePostgresReadBody = escapeHtml(uiString("performance.postgresReadBody", localized(language, "Şu anda PostgreSQL okuma yolu doğrudan gözlenmiyorsa bu kart boş kalır.", "This card stays empty when PostgreSQL reads are not directly observed.")));
        String performancePostgresWriteBody = escapeHtml(uiString("performance.postgresWriteBody", localized(language, "Write-behind flush işlemlerinin PostgreSQL üzerindeki yazma gecikmesi.", "Write latency on PostgreSQL for write-behind flush operations.")));
        String liveTrendsBacklogLabel = escapeHtml(uiString("liveTrends.backlogLabel", "Write-behind backlog"));
        String liveTrendsRedisMemoryLabel = escapeHtml(uiString("liveTrends.redisMemoryLabel", "Redis memory"));
        String liveTrendsDeadLetterLabel = escapeHtml(uiString("liveTrends.deadLetterLabel", "Dead-letter backlog"));
        String liveTrendsDashboardIntro = escapeHtml(uiString("liveTrends.dashboardIntro", "Bu özet pano, trendlerin son yönünü tek bakışta yorumlamanı sağlar. Grafiğe inmeden önce burada hangi sinyalin yükseldiğini ve ilk nereye bakılması gerektiğini görürsün."));
        String alertRouteDeliveredTrendLabel = escapeHtml(uiString("alertRouteTrends.deliveredLabel", "Channel delivered count"));
        String alertRouteFailedTrendLabel = escapeHtml(uiString("alertRouteTrends.failedLabel", "Channel failed count"));
        String explainEntityLabel = escapeHtml(uiString("explain.entityLabel", "Entity"));
        String explainFilterLabel = escapeHtml(uiString("explain.filterLabel", "Filter"));
        String explainSortLabel = escapeHtml(uiString("explain.sortLabel", "Sort"));
        String explainLimitLabel = escapeHtml(uiString("explain.limitLabel", "Limit"));
        String explainIncludeLabel = escapeHtml(uiString("explain.includeLabel", "Include"));
        String tuningCapturedAtLabel = escapeHtml(uiString("tuning.capturedAtLabel", "Captured at"));
        String tuningOverrideCountLabel = escapeHtml(uiString("tuning.overrideCountLabel", "Explicit overrides"));
        String tuningEntryCountLabel = escapeHtml(uiString("tuning.entryCountLabel", "Visible entries"));
        String tuningExportJsonLabel = escapeHtml(uiString("tuning.exportJsonLabel", "Export JSON"));
        String tuningExportMarkdownLabel = escapeHtml(uiString("tuning.exportMarkdownLabel", "Export Markdown"));
        String tuningCopyFlagsLabel = escapeHtml(uiString("tuning.copyFlagsLabel", "Copy Startup Flags"));
        String tuningExportStatusIdle = escapeHtml(uiString("tuning.exportStatusIdle", "Choose an export action."));
        String runtimeProfileControlIntro = escapeHtml(uiString("runtimeProfileControlIntro", "Inspect the active runtime profile, review every property it carries, and switch between AUTO, STANDARD, BALANCED, and AGGRESSIVE without restarting the process."));
        String runtimeProfileActiveLabel = escapeHtml(uiString("runtimeProfile.activeLabel", "Active Profile"));
        String runtimeProfileModeLabel = escapeHtml(uiString("runtimeProfile.modeLabel", "Mode"));
        String runtimeProfilePressureLabel = escapeHtml(uiString("runtimeProfile.pressureLabel", "Observed Pressure"));
        String runtimeProfileSwitchCountLabel = escapeHtml(uiString("runtimeProfile.switchCountLabel", "Switch Count"));
        String runtimeProfileLastSwitchedLabel = escapeHtml(uiString("runtimeProfile.lastSwitchedLabel", "Last Switched"));
        String runtimeProfileManualOverrideLabel = escapeHtml(uiString("runtimeProfile.manualOverrideLabel", "Manual Override"));
        String runtimeProfileAutoLabel = escapeHtml(uiString("runtimeProfile.button.autoLabel", "Auto"));
        String runtimeProfileStandardLabel = escapeHtml(uiString("runtimeProfile.button.standardLabel", "Standard"));
        String runtimeProfileBalancedLabel = escapeHtml(uiString("runtimeProfile.button.balancedLabel", "Balanced"));
        String runtimeProfileAggressiveLabel = escapeHtml(uiString("runtimeProfile.button.aggressiveLabel", "Aggressive"));
        String runtimeProfileStatusIdle = escapeHtml(uiString("runtimeProfile.statusIdle", "Runtime profile controls are ready."));
        String liveRefreshIntro = escapeHtml(uiString("section.liveRefreshIntro", "Refresh cadence controls the whole screen. Use it to keep metrics current during load tests or pause the page while you inspect a moment in time."));
        String resetToolsIntro = escapeHtml(uiString("section.resetToolsIntro", "These actions clean operational traces, not business data. Use them before a fresh observation run so historic noise does not confuse the current session."));
        String liveTrendsIntro = escapeHtml(uiString("section.liveTrendsIntro", "These charts show direction over time. Rising backlog with rising dead-letter usually points to writer pressure; rising memory without backlog points more to cache growth."));
        String alertRouteTrendIntro = escapeHtml(uiString("section.alertRouteTrendIntro", "Compare channel delivery and failure movement here. A widening failure line means alert transport is unhealthy even if the main data path is still up."));
        String incidentSeverityIntro = escapeHtml(uiString("section.incidentSeverityIntro", "Severity trend tells us whether the system is stabilizing or escalating. A short critical spike is different from a long warning plateau."));
        String topFailingSignalsIntro = escapeHtml(uiString("section.topFailingSignalsIntro", "This is the shortest path to the noisiest failures. Start with the first card when you need a fast operational summary."));
        String triageIntro = escapeHtml(uiString("section.triageIntro", "Triage compresses many metrics into one bottleneck guess. Use it as the opening hint, then verify the diagnosis in the sections below."));
        String serviceStatusIntro = escapeHtml(uiString("section.serviceStatusIntro", "Each service row tells you which subsystem is unhealthy and which signal is driving that status."));
        String backgroundErrorsIntro = escapeHtml(uiString("section.backgroundErrorsIntro", localized(language, "Bu bölüm gizli kalan worker hatalarını görünür yapar. Hangi arka plan işleyicisinin hata verdiğini, exception'ın hangi metottan geldiğini ve son stack trace özetini burada görürsün.", "This section makes hidden worker failures explicit. It shows which background worker failed, where the exception came from, and the latest stack trace preview.")));
        String healthIntro = escapeHtml(uiString("section.healthIntro", "Health combines current status with active issues. Read this with incidents: health tells overall state, incidents tell why."));
        String incidentsIntro = escapeHtml(uiString("section.incidentsIntro", "Active incidents are the current alarm set. A low count is good only if critical signals are also low."));
        String deploymentIntro = escapeHtml(uiString("section.deploymentIntro", "Deployment shows operational posture: schema mode, write-behind, compaction, guardrails, and active key prefix. Use it to confirm which runtime envelope you are observing."));
        String schemaStatusIntro = escapeHtml(uiString("section.schemaStatusIntro", "Schema status summarizes migration pressure and validation health. Watch this during upgrades and first starts."));
        String schemaHistoryIntro = escapeHtml(uiString("section.schemaHistoryIntro", "Schema history is the audit trail for structural changes. Failed or repeated entries usually deserve immediate attention."));
        String starterProfilesIntro = escapeHtml(uiString("section.starterProfilesIntro", "Profiles summarize high-level deployment presets. Compare them against the active deployment to spot drift."));
        String apiRegistryIntro = escapeHtml(uiString("section.apiRegistryIntro", "Registry rows show which entities are exposed and how they are shaped. It is useful when query or paging behavior looks surprising."));
        String tuningIntro = escapeHtml(uiString("section.tuningIntro", "This is the effective runtime configuration, not just defaults. Use it when you need to answer which setting is actually active right now."));
        String certificationIntro = escapeHtml(uiString("section.certificationIntro", "Certification reports summarize recent production-gate style runs and benchmark outcomes for this build."));
        String alertRoutingIntro = escapeHtml(uiString("section.alertRoutingIntro", "Alert routing shows destination, escalation level, retry posture, and delivery health for each notification path."));
        String runbooksIntro = escapeHtml(uiString("section.runbooksIntro", "Runbooks are the first operational actions attached to a signal. Use them to turn an alert into a repeatable response."));
        String alertRouteHistoryIntro = escapeHtml(uiString("section.alertRouteHistoryIntro", "Route history shows recent delivery attempts across channels so you can tell whether failures are intermittent or sustained."));
        String schemaDdlIntro = escapeHtml(uiString("section.schemaDdlIntro", "Schema DDL is a quick structural reference for registered entities and generated persistence shape."));
        String runtimeProfileIntro = escapeHtml(uiString("section.runtimeProfileIntro", "Profile churn shows when the system shifted posture under pressure. Frequent changes can indicate unstable thresholds or oscillating load."));
        String explainIntro = escapeHtml(uiString("section.explainIntro", "Explain turns a query into planner, relation, and step-level diagnostics. Start with the triage cards, then move into relation states and step costs."));
        String activeLanguageLabel = escapeHtml(uiString("language.label", "Language"));
        String languageTurkishLabel = escapeHtml(uiString("language.tr", "TR"));
        String languageEnglishLabel = escapeHtml(uiString("language.en", "EN"));
        String scrollToTopLabel = escapeHtml(uiString("scroll.toTopLabel", "Yukarı Çık"));
        String scrollToBottomLabel = escapeHtml(uiString("scroll.toBottomLabel", "Aşağı İn"));
        String trendBacklogColor = escapeJs(uiString("chart.trend.backlogColor", "#9c3f2b"));
        String trendMemoryColor = escapeJs(uiString("chart.trend.memoryColor", "#2563eb"));
        String trendDeadLetterColor = escapeJs(uiString("chart.trend.deadLetterColor", "#dc2626"));
        String chartBackgroundColor = escapeJs(uiString("chart.backgroundColor", "#fbf7ef"));
        String chartAxisColor = escapeJs(uiString("chart.axisColor", "#c9baa2"));
        String chartMutedTextColor = escapeJs(uiString("chart.mutedTextColor", "#7b8794"));
        String routeWebhookColor = escapeJs(uiString("chart.route.webhookColor", "#9c3f2b"));
        String routeQueueColor = escapeJs(uiString("chart.route.queueColor", "#2563eb"));
        String routeSmtpColor = escapeJs(uiString("chart.route.smtpColor", "#0f766e"));
        String routeDlqColor = escapeJs(uiString("chart.route.deliveryDlqColor", "#dc2626"));
        String routeFallbackColor = escapeJs(uiString("chart.route.fallbackColor", "#1d2525"));
        String severityCriticalColor = escapeJs(uiString("chart.severity.criticalColor", "#dc2626"));
        String severityWarningColor = escapeJs(uiString("chart.severity.warningColor", "#d97706"));
        String severityInfoColor = escapeJs(uiString("chart.severity.infoColor", "#2563eb"));
        String churnLineColor = escapeJs(uiString("chart.churn.lineColor", "#9c3f2b"));
        String churnDotColor = escapeJs(uiString("chart.churn.dotColor", "#1d2525"));
        String churnAxisTextColor = escapeJs(uiString("chart.churn.axisTextColor", "#6d5e49"));
        String profileAggressiveLabel = escapeJs(uiString("chart.profile.aggressiveLabel", "AGGRESSIVE"));
        String profileBalancedLabel = escapeJs(uiString("chart.profile.balancedLabel", "BALANCED"));
        String profileStandardLabel = escapeJs(uiString("chart.profile.standardLabel", "STANDARD"));
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>" + title + "</title>"
                + "<link rel=\"preconnect\" href=\"https://fonts.googleapis.com\">"
                + "<link rel=\"preconnect\" href=\"https://fonts.gstatic.com\" crossorigin>"
                + "<link href=\"" + googleFontsUrl + "\" rel=\"stylesheet\">"
                + "<link href=\"" + bootstrapCssUrl + "\" rel=\"stylesheet\">"
                + "<style>"
                + dashboardThemeCss
                + "</style>"
                + "</head><body>"
                + "<nav class=\"navbar navbar-expand-lg bg-dark navbar-dark shadow-sm\"><div class=\"container-fluid px-4 gap-3\"><div class=\"d-flex align-items-center gap-2\"><span class=\"navbar-brand mb-0\">" + title + "</span><span class=\"badge rounded-pill text-bg-light text-dark fw-semibold\">" + uiRevisionBadge + "</span></div>"
                + "<span class=\"navbar-text text-light-emphasis flex-grow-1\">" + navbarSubtitle + "</span>"
                + "<div class=\"lang-switch\"><span class=\"small text-light-emphasis\">" + activeLanguageLabel + "</span>"
                + "<a class=\"btn btn-sm " + ("tr".equals(language) ? "btn-light" : "btn-outline-light") + "\" href=\"" + effectiveDashboardPath + "?lang=tr\">" + languageTurkishLabel + "</a>"
                + "<a class=\"btn btn-sm " + ("en".equals(language) ? "btn-light" : "btn-outline-light") + "\" href=\"" + effectiveDashboardPath + "?lang=en\">" + languageEnglishLabel + "</a>"
                + "</div></div></nav>"
                + "<main class=\"container-fluid px-4 py-4 ops-shell\">"
                + "<aside class=\"ops-sidebar\">"
                + "<div class=\"nav-panel navigator-panel\"><div class=\"nav-panel-header\">" + workspaceNavTitle + "</div><div class=\"nav-panel-body\">"
                + "<div class=\"small text-muted mb-3\">" + workspaceNavIntro + "</div>"
                + "<div class=\"nav-tree-list\">"
                + "<div class=\"nav-tree-section\"><div class=\"nav-tree-section-title\">" + workspaceOverviewLabel + "</div><div class=\"nav-tree-links\">"
                + "<a class=\"nav-tree-link active\" href=\"#primary-signals\" data-target=\"primary-signals\"><span class=\"nav-tree-link-title\">" + jumpNavPrimary + "</span><span class=\"nav-tree-link-copy\">" + escapeHtml(localized(language, "Yük ve baskıyı ilk burada okuyacaksın.", "Start here for the first view of load and pressure.")) + "</span></a>"
                + "<a class=\"nav-tree-link\" href=\"#supporting-signals\" data-target=\"supporting-signals\"><span class=\"nav-tree-link-title\">" + supportingSignalsTitle + "</span><span class=\"nav-tree-link-copy\">" + escapeHtml(localized(language, "Ana göstergeleri açıklayan destek verileri burada.", "Supporting data that explains the primary signals.")) + "</span></a>"
                + "<a class=\"nav-tree-link\" href=\"#performance-section\" data-target=\"performance-section\"><span class=\"nav-tree-link-title\">" + performanceSectionTitle + "</span><span class=\"nav-tree-link-copy\">" + performanceNavCopy + "</span></a>"
                + "<a class=\"nav-tree-link\" href=\"#metric-taxonomy\" data-target=\"metric-taxonomy\"><span class=\"nav-tree-link-title\">" + jumpNavTaxonomy + "</span><span class=\"nav-tree-link-copy\">" + escapeHtml(localized(language, "Metriklerin ne anlattığını buradan gör.", "Use this to understand what the metrics mean.")) + "</span></a>"
                + "<a class=\"nav-tree-link\" href=\"#signal-dashboard\" data-target=\"signal-dashboard\"><span class=\"nav-tree-link-title\">" + signalDashboardTitle + "</span><span class=\"nav-tree-link-copy\">" + escapeHtml(localized(language, "Öne çıkan sinyalleri kısa yorumla birlikte gösterir.", "Shows the headline signals with short guidance.")) + "</span></a>"
                + "<a class=\"nav-tree-link\" href=\"#operational-taxonomy\" data-target=\"operational-taxonomy\"><span class=\"nav-tree-link-title\">" + operationalTaxonomyTitle + "</span><span class=\"nav-tree-link-copy\">" + escapeHtml(localized(language, "Terimlerin ne anlama geldiğini açıklar.", "Explains what the terms mean.")) + "</span></a>"
                + "</div></div>"
                + "<div class=\"nav-tree-section\"><div class=\"nav-tree-section-title\">" + workspaceHealthLabel + "</div><div class=\"nav-tree-links\">"
                + "<a class=\"nav-tree-link\" href=\"#live-trends\" data-target=\"live-trends\"><span class=\"nav-tree-link-title\">" + jumpNavTrends + "</span><span class=\"nav-tree-link-copy\">" + escapeHtml(localized(language, "Zaman içinde baskının artıp artmadığını gösterir.", "Shows whether pressure is rising over time.")) + "</span></a>"
                + "<a class=\"nav-tree-link\" href=\"#triage-section\" data-target=\"triage-section\"><span class=\"nav-tree-link-title\">" + jumpNavTriage + "</span><span class=\"nav-tree-link-copy\">" + escapeHtml(localized(language, "İlk bakılacak darboğazı ve servis durumunu özetler.", "Summarizes the first bottleneck to inspect and service state.")) + "</span></a>"
                + "<a class=\"nav-tree-link\" href=\"#background-errors-section\" data-target=\"background-errors-section\"><span class=\"nav-tree-link-title\">" + backgroundErrorsTitle + "</span><span class=\"nav-tree-link-copy\">" + escapeHtml(localized(language, "Gizli kalan worker hatalarını exception ve stack ile açar.", "Reveals hidden worker failures with exception and stack detail.")) + "</span></a>"
                + "<a class=\"nav-tree-link\" href=\"#diagnostics-section\" data-target=\"diagnostics-section\"><span class=\"nav-tree-link-title\">" + metricDiagnosticsTitle + "</span><span class=\"nav-tree-link-copy\">" + escapeHtml(localized(language, "Toplanan tanılama kayıtlarını ve ayrıntılarını açar.", "Opens collected diagnostic records and their details.")) + "</span></a>"
                + "<a class=\"nav-tree-link\" href=\"#incidents-section\" data-target=\"incidents-section\"><span class=\"nav-tree-link-title\">" + jumpNavIncidents + "</span><span class=\"nav-tree-link-copy\">" + escapeHtml(localized(language, "Açık olayları ve önem düzeylerini listeler.", "Lists active incidents and their severity.")) + "</span></a>"
                + "</div></div>"
                + "<div class=\"nav-tree-section\"><div class=\"nav-tree-section-title\">" + workspaceRoutingLabel + "</div><div class=\"nav-tree-links\">"
                + "<a class=\"nav-tree-link\" href=\"#alert-routing-section\" data-target=\"alert-routing-section\"><span class=\"nav-tree-link-title\">" + jumpNavRouting + "</span><span class=\"nav-tree-link-copy\">" + escapeHtml(localized(language, "Uyarıların hangi hedefe nasıl gittiğini gösterir.", "Shows where alerts go and how they are delivered.")) + "</span></a>"
                + "<a class=\"nav-tree-link\" href=\"#alert-route-history-section\" data-target=\"alert-route-history-section\"><span class=\"nav-tree-link-title\">" + alertRouteHistoryTitle + "</span><span class=\"nav-tree-link-copy\">" + escapeHtml(localized(language, "Son teslim denemelerini ve hata geçmişini gösterir.", "Shows recent delivery attempts and failure history.")) + "</span></a>"
                + "</div></div>"
                + "<div class=\"nav-tree-section\"><div class=\"nav-tree-section-title\">" + workspaceRuntimeLabel + "</div><div class=\"nav-tree-links\">"
                + "<a class=\"nav-tree-link\" href=\"#runtime-profile-section\" data-target=\"runtime-profile-section\"><span class=\"nav-tree-link-title\">" + runtimeProfileControlTitle + "</span><span class=\"nav-tree-link-copy\">" + escapeHtml(localized(language, "Canlı çalışma profilini buradan değiştir.", "Change the live runtime profile here.")) + "</span></a>"
                + "<a class=\"nav-tree-link\" href=\"#runtime-profile-churn-section\" data-target=\"runtime-profile-churn-section\"><span class=\"nav-tree-link-title\">" + runtimeProfileChurnTitle + "</span><span class=\"nav-tree-link-copy\">" + escapeHtml(localized(language, "Profilin zaman içinde nasıl değiştiğini burada gör.", "See how the runtime profile changed over time.")) + "</span></a>"
                + "<a class=\"nav-tree-link\" href=\"#tuning-section\" data-target=\"tuning-section\"><span class=\"nav-tree-link-title\">" + jumpNavTuning + "</span><span class=\"nav-tree-link-copy\">" + escapeHtml(localized(language, "Şu anda geçerli olan ayarları gösterir.", "Shows the settings currently in effect.")) + "</span></a>"
                + "</div></div>"
                + "<div class=\"nav-tree-section\"><div class=\"nav-tree-section-title\">" + workspaceSchemaLabel + "</div><div class=\"nav-tree-links\">"
                + "<a class=\"nav-tree-link\" href=\"#schema-runtime-section\" data-target=\"schema-runtime-section\"><span class=\"nav-tree-link-title\">" + deploymentTitle + " / " + schemaStatusTitle + "</span><span class=\"nav-tree-link-copy\">" + escapeHtml(localized(language, "Canlı yapı ve şema durumunu birlikte gösterir.", "Shows runtime posture and schema status together.")) + "</span></a>"
                + "<a class=\"nav-tree-link\" href=\"#schema-history-section\" data-target=\"schema-history-section\"><span class=\"nav-tree-link-title\">" + schemaHistoryTitle + "</span><span class=\"nav-tree-link-copy\">" + escapeHtml(localized(language, "Şema geçmişi ve kayıt görünümünü açar.", "Opens schema history and registry views.")) + "</span></a>"
                + "</div></div>"
                + "<div class=\"nav-tree-section\"><div class=\"nav-tree-section-title\">" + workspaceExplainLabel + "</div><div class=\"nav-tree-links\">"
                + "<a class=\"nav-tree-link\" href=\"#explain-section\" data-target=\"explain-section\"><span class=\"nav-tree-link-title\">" + jumpNavExplain + "</span><span class=\"nav-tree-link-copy\">" + escapeHtml(localized(language, "Sorgu neden yavaşladı sorusunu açar.", "Explains why a query became slow.")) + "</span></a>"
                + "</div></div>"
                + "</div>"
                + "</div></div>"
                + "</aside>"
                + "<section class=\"ops-workspace\">"
                + "<div class=\"ops-utility-strip\">"
                + "<div class=\"nav-panel compact-panel\"><div class=\"nav-panel-header\">" + liveRefreshTitle + "</div><div class=\"nav-panel-body\">"
                + "<div class=\"compact-help\">" + liveRefreshIntro + "</div>"
                + "<div class=\"compact-inline\"><span class=\"compact-inline-label\">" + autoRefreshLabel + "</span><select id=\"refreshInterval\" class=\"form-select\">"
                + renderRefreshOptionsHtml(language) + "</select></div>"
                + "<div class=\"compact-toolbar\"><button id=\"refreshNow\" class=\"btn btn-outline-primary\">" + escapeHtml(uiString("refreshNowLabel", "Refresh Now")) + "</button><button id=\"toggleRefresh\" class=\"btn btn-outline-secondary\">" + escapeHtml(uiString("toggleRefreshLabel", "Pause")) + "</button></div>"
                + "<div class=\"compact-status\"><div class=\"fw-semibold mb-1\">" + lastUpdatedLabel + "</div><div id=\"lastUpdated\">" + lastUpdatedNever + "</div></div>"
                + "</div></div>"
                + "<div class=\"nav-panel compact-panel\"><div class=\"nav-panel-header text-danger\">" + resetToolsTitle + "</div><div class=\"nav-panel-body\">"
                + "<div class=\"compact-help\">" + resetToolsIntro + "</div>"
                + "<div class=\"fw-semibold mb-1\">" + resetTelemetryLabel + "</div><div class=\"text-muted small mb-2\">" + resetTelemetryDescription + "</div>"
                + "<button id=\"resetTelemetryTop\" class=\"btn btn-danger w-100 mb-2\">" + resetTelemetryLabel + "</button>"
                + "<details class=\"rounded border bg-light p-3 small\">"
                + "<summary class=\"fw-semibold\" style=\"cursor:pointer;list-style:none\">" + escapeHtml(uiString("resetTelemetryExplainTitle", "Reset Telemetry History ne yapar?")) + "</summary>"
                + "<div class=\"text-muted mt-2\">" + escapeHtml(uiString("resetTelemetryExplainBody", "Diagnostics, incident history, monitoring history, alert route history ve gecikme/performance olcumlerini temizler. Demo entity verisini silmez; onun icin demo UI'deki Clear Data kullanilir.")) + "</div>"
                + "</details>"
                + "<div class=\"compact-status\">"
                + "<div id=\"telemetryResetStatusTop\" class=\"text-muted\">" + resetTelemetryNotRunYet + "</div>"
                + "<div id=\"telemetryResetStatus\" class=\"text-muted d-none\">" + resetTelemetryNotRunYet + "</div>"
                + "</div></div></div>"
                + "<div class=\"nav-panel compact-panel\"><div class=\"nav-panel-header\">" + migrationPlannerTitle + "</div><div class=\"nav-panel-body\">"
                + "<div class=\"compact-help\">" + migrationPlannerIntro + "</div>"
                + "<a class=\"btn btn-outline-primary w-100\" href=\"" + migrationPlannerUrl + "\">" + migrationPlannerOpenLabel + "</a>"
                + "<div class=\"compact-status\">" + escapeHtml(localized(language, "Mevcut bir ORM ekranını modelleyip sıcak pencere, warm plan ve karşılaştırma kontrol listesini çıkar.", "Model an existing ORM route and get a hot-window, warm-plan, and comparison checklist.")) + "</div>"
                + "</div></div>"
                + "</div>"
                + "<div id=\"dashboardDataStatus\" class=\"ops-data-status\" data-state=\"loading\">"
                + "<div class=\"ops-data-status-copy\"><div class=\"ops-data-status-title\" id=\"dashboardDataStatusTitle\">" + escapeHtml(uiString("dashboardDataStatus.loadingTitle", "Veriler yükleniyor")) + "</div><div class=\"ops-data-status-detail\" id=\"dashboardDataStatusDetail\">" + escapeHtml(uiString("dashboardDataStatus.loadingDetail", "Yönetim API'lerinden son metrikler ve servis durumu alınıyor.")) + "</div></div>"
                + "<span id=\"dashboardDataStatusBadge\"><span class=\"badge text-bg-warning\">" + escapeHtml(uiString("dashboardDataStatus.loadingBadge", localized(language, "Yükleniyor", "Loading"))) + "</span></span>"
                + "</div>"
                + "<div class=\"row g-3 mb-3\"><div class=\"col-12\"><div class=\"card section-card\"><div class=\"card-header\">" + howToReadTitle + "</div><div class=\"card-body\">"
                + "<div class=\"row g-3\">"
                + "<div class=\"col-12 col-lg-4\"><div class=\"fw-semibold mb-1\">" + howToReadStep1Title + "</div><div class=\"text-muted small\">" + howToReadStep1Body + "</div></div>"
                + "<div class=\"col-12 col-lg-4\"><div class=\"fw-semibold mb-1\">" + howToReadStep2Title + "</div><div class=\"text-muted small\">" + howToReadStep2Body + "</div></div>"
                + "<div class=\"col-12 col-lg-4\"><div class=\"fw-semibold mb-1\">" + howToReadStep3Title + "</div><div class=\"text-muted small\">" + howToReadStep3Body + "</div></div>"
                + "</div></div></div></div></div>"
                + "<div class=\"row g-3 mb-3\"><div class=\"col-12\"><div class=\"card section-card\"><div class=\"card-header\">" + sectionGuideTitle + "</div><div class=\"card-body\">"
                + "<div class=\"row g-3\">"
                + "<div class=\"col-12 col-md-6 col-xl-3\"><div class=\"fw-semibold mb-1\">" + sectionGuideLiveTrendsTitle + "</div><div class=\"text-muted small\">" + sectionGuideLiveTrendsBody + "</div></div>"
                + "<div class=\"col-12 col-md-6 col-xl-3\"><div class=\"fw-semibold mb-1\">" + sectionGuideTriageTitle + "</div><div class=\"text-muted small\">" + sectionGuideTriageBody + "</div></div>"
                + "<div class=\"col-12 col-md-6 col-xl-3\"><div class=\"fw-semibold mb-1\">" + sectionGuideTopSignalsTitle + "</div><div class=\"text-muted small\">" + sectionGuideTopSignalsBody + "</div></div>"
                + "<div class=\"col-12 col-md-6 col-xl-3\"><div class=\"fw-semibold mb-1\">" + sectionGuideRoutingTitle + "</div><div class=\"text-muted small\">" + sectionGuideRoutingBody + "</div></div>"
                + "</div></div></div></div></div>"
                + "<div class=\"row g-3 mb-3\" id=\"operational-taxonomy\"><div class=\"col-12\"><div class=\"card section-card\"><div class=\"card-header\">" + operationalTaxonomyTitle + "</div><div class=\"card-body\">"
                + sectionIntro(operationalTaxonomyIntro)
                + "<div class=\"row g-3 align-items-end mb-3\">"
                + "<div class=\"col-12 col-lg-8\"><label class=\"form-label\">" + operationalTaxonomySearchLabel + "</label><input id=\"taxonomySearch\" class=\"form-control glossary-search\" placeholder=\"" + operationalTaxonomySearchPlaceholder + "\"></div>"
                + "<div class=\"col-12 col-lg-4\"><div class=\"small text-muted\">" + operationalTaxonomyCountLabel + "</div><div id=\"taxonomyCount\" class=\"fw-semibold\">0</div></div>"
                + "</div>"
                + "<div id=\"taxonomyEmptyState\" class=\"glossary-empty d-none\">" + operationalTaxonomyEmpty + "</div>"
                + "<div id=\"taxonomyGrid\" class=\"row g-3\">"
                + renderOperationalTaxonomy(language)
                + "</div></div></div></div></div>"
                + "<div class=\"workspace-group active\" data-group=\"overview\">"
                + "<div class=\"workspace-group-header\"><div><h2 class=\"workspace-group-title\">" + workspaceOverviewLabel + "</h2><div class=\"workspace-group-copy\">" + escapeHtml(localized(language, "Genel bakış alanı, metriklerin ne anlama geldiğini ve ilk bakışta hangi göstergelere odaklanman gerektiğini toplar.", "The overview gathers metric meaning and the first signals worth scanning.")) + "</div></div></div>"
                + "<div class=\"row g-3 mb-3\" id=\"primary-signals\"><div class=\"col-12\"><div class=\"card section-card\"><div class=\"card-header\">" + primarySignalsTitle + "</div><div class=\"card-body\">"
                + "<div class=\"text-muted small mb-3\">" + primarySignalsBody + "</div>"
                + "<div class=\"row g-3\">"
                + metricCard(metricWriteBehindTitle, "wb", metricWriteBehindUnit, metricWriteBehindDescription, "metric-primary")
                + metricCard(metricDeadLetterTitle, "dlq", metricDeadLetterUnit, metricDeadLetterDescription, "metric-primary")
                + metricCard(metricProjectionRefreshDlqTitle, "projectionRefreshDlq", metricProjectionRefreshDlqUnit, metricProjectionRefreshDlqDescription, "metric-primary", "projection-refresh-section")
                + metricCard(metricIncidentsTitle, "inc", metricIncidentsUnit, metricIncidentsDescription, "metric-primary")
                + metricCard(metricCriticalSignalsTitle, "criticalSignals", metricCriticalSignalsUnit, metricCriticalSignalsDescription, "metric-primary")
                + metricCard(metricRedisMemoryTitle, "redisMem", metricRedisMemoryUnit, metricRedisMemoryDescription, "metric-primary")
                + metricCard(metricRuntimeProfileTitle, "runtimeProfile", metricRuntimeProfileUnit, metricRuntimeProfileDescription, "metric-primary")
                + "</div></div></div></div></div>"
                + "<div class=\"row g-3 mb-3\" id=\"supporting-signals\"><div class=\"col-12\"><div class=\"card section-card\"><div class=\"card-header\">" + supportingSignalsTitle + "</div><div class=\"card-body\">"
                + "<div class=\"text-muted small mb-3\">" + supportingSignalsBody + "</div>"
                + "<div class=\"row g-3\">"
                + metricCard(metricDiagnosticsTitle, "diag", metricDiagnosticsUnit, metricDiagnosticsDescription, "metric-supporting", "diagnostics-section")
                + metricCard(metricLearnedStatsTitle, "learned", metricLearnedStatsUnit, metricLearnedStatsDescription, "metric-supporting")
                + metricCard(metricCompactionPendingTitle, "compactionPending", metricCompactionPendingUnit, metricCompactionPendingDescription, "metric-supporting")
                + metricCard(metricProjectionRefreshPendingTitle, "projectionRefreshPending", metricProjectionRefreshPendingUnit, metricProjectionRefreshPendingDescription, "metric-supporting", "projection-refresh-section")
                + metricCard(metricAlertDeliveredTitle, "alertDelivered", metricAlertDeliveredUnit, metricAlertDeliveredDescription, "metric-supporting")
                + metricCard(metricAlertFailedTitle, "alertFailed", metricAlertFailedUnit, metricAlertFailedDescription, "metric-supporting")
                + metricCard(metricAlertDroppedTitle, "alertDropped", metricAlertDroppedUnit, metricAlertDroppedDescription, "metric-supporting")
                + metricCard(metricWarningSignalsTitle, "warningSignals", metricWarningSignalsUnit, metricWarningSignalsDescription, "metric-supporting")
                + "</div></div></div></div></div>"
                + "<div class=\"row g-3 mb-3\" id=\"signal-dashboard\"><div class=\"col-12\"><div class=\"card section-card\"><div class=\"card-header\">" + signalDashboardTitle + "</div><div class=\"card-body\">"
                + sectionIntro(signalDashboardIntro)
                + "<div id=\"signalDashboardGrid\" class=\"signal-board\"></div>"
                + "</div></div></div></div>"
                + "<div class=\"row g-3 mb-3\" id=\"performance-section\"><div class=\"col-12\"><div class=\"card section-card\"><div class=\"card-header\">" + performanceSectionTitle + "</div><div class=\"card-body\">"
                + sectionIntro(performanceSectionIntro)
                + "<div class=\"section-scope-note\">" + performanceScopeNote + "</div>"
                + "<div class=\"performance-actions\">"
                + "<div class=\"performance-actions-copy\">"
                + "<div class=\"small text-muted\">" + performanceTrendIntro + "</div>"
                + "<div id=\"performanceResetStatus\" class=\"small text-muted mt-2\">" + performanceResetIdle + "</div>"
                + "</div>"
                + "<button id=\"resetPerformanceMetrics\" class=\"btn btn-outline-danger\">" + performanceResetLabel + "</button>"
                + "</div>"
                + "<div id=\"performanceTrendDashboard\" class=\"signal-board mb-3\"></div>"
                + "<div class=\"small text-muted mb-3\">" + performanceScenarioIntro + "</div>"
                + renderPerformanceScenarioSummaryShell(
                performanceBottleneckTitle,
                performanceBottleneckIdle,
                performanceScopeNote,
                performanceTopReadTitle,
                performanceTopReadIntro,
                performanceTopWriteTitle,
                performanceTopWriteIntro,
                performanceTopPostgresReadTitle,
                performanceTopPostgresReadIntro,
                performanceTopPostgresWriteTitle,
                performanceTopPostgresWriteIntro,
                performanceOtherScenarioTitle
        )
                + "<div id=\"performanceGrid\" class=\"performance-grid\">"
                + performanceCardShell("performanceRedisRead", performanceRedisReadTitle, performanceRedisReadBody)
                + performanceCardShell("performanceRedisWrite", performanceRedisWriteTitle, performanceRedisWriteBody)
                + performanceCardShell("performancePostgresRead", performancePostgresReadTitle, performancePostgresReadBody)
                + performanceCardShell("performancePostgresWrite", performancePostgresWriteTitle, performancePostgresWriteBody)
                + "</div></div></div></div></div>"
                + "<div class=\"row g-3 mb-3\" id=\"metric-taxonomy\"><div class=\"col-12\"><div class=\"card section-card\"><div class=\"card-header\">" + metricTaxonomyTitle + "</div><div class=\"card-body\">"
                + sectionIntro(metricTaxonomyIntro)
                + "<div class=\"row g-3\">"
                + "<div class=\"col-12 col-md-6 col-xl-4\"><div class=\"taxonomy-item\"><h6>" + taxonomyQueueTitle + "</h6><div class=\"text-muted small\">" + taxonomyQueueBody + "</div></div></div>"
                + "<div class=\"col-12 col-md-6 col-xl-4\"><div class=\"taxonomy-item\"><h6>" + taxonomyMemoryTitle + "</h6><div class=\"text-muted small\">" + taxonomyMemoryBody + "</div></div></div>"
                + "<div class=\"col-12 col-md-6 col-xl-4\"><div class=\"taxonomy-item\"><h6>" + taxonomyHealthTitle + "</h6><div class=\"text-muted small\">" + taxonomyHealthBody + "</div></div></div>"
                + "<div class=\"col-12 col-md-6 col-xl-4\"><div class=\"taxonomy-item\"><h6>" + taxonomyDeliveryTitle + "</h6><div class=\"text-muted small\">" + taxonomyDeliveryBody + "</div></div></div>"
                + "<div class=\"col-12 col-md-6 col-xl-4\"><div class=\"taxonomy-item\"><h6>" + taxonomyQueryTitle + "</h6><div class=\"text-muted small\">" + taxonomyQueryBody + "</div></div></div>"
                + "<div class=\"col-12 col-md-6 col-xl-4\"><div class=\"taxonomy-item\"><h6>" + taxonomyControlsTitle + "</h6><div class=\"text-muted small\">" + taxonomyControlsBody + "</div></div></div>"
                + "<div class=\"col-12\"><div class=\"taxonomy-item\"><h6>" + taxonomyUnitsTitle + "</h6><div class=\"text-muted small\">" + taxonomyUnitsBody + "</div></div></div>"
                + "</div></div></div></div></div>"
                + "</div>"
                + "<div class=\"workspace-group\" data-group=\"health\">"
                + "<div class=\"workspace-group-header\"><div><h2 class=\"workspace-group-title\">" + workspaceHealthLabel + "</h2><div class=\"workspace-group-copy\">" + escapeHtml(localized(language, "Bu alan canlı baskıyı, darboğaz tahminini ve aktif olayları birlikte okumak için tasarlandı.", "This workspace helps read live pressure, bottleneck guesses, and active incidents together.")) + "</div></div></div>"
                + "<div class=\"row g-3 mb-3\" id=\"live-trends\"><div class=\"col-12\"><div class=\"card section-card\"><div class=\"card-header\">" + liveTrendsTitle + "</div><div class=\"card-body\">"
                + sectionIntro(liveTrendsIntro)
                + "<div class=\"small text-muted mb-3\">" + liveTrendsDashboardIntro + "</div>"
                + "<div id=\"liveTrendDashboardGrid\" class=\"signal-board mb-3\"></div>"
                + "<div class=\"row g-3\">"
                + "<div class=\"col-12 col-lg-4\"><div class=\"text-muted small mb-1\">" + liveTrendsBacklogLabel + "</div><svg id=\"backlogTrendSvg\" viewBox=\"0 0 320 120\" class=\"w-100\" style=\"height:120px\"></svg></div>"
                + "<div class=\"col-12 col-lg-4\"><div class=\"text-muted small mb-1\">" + liveTrendsRedisMemoryLabel + "</div><svg id=\"memoryTrendSvg\" viewBox=\"0 0 320 120\" class=\"w-100\" style=\"height:120px\"></svg></div>"
                + "<div class=\"col-12 col-lg-4\"><div class=\"text-muted small mb-1\">" + liveTrendsDeadLetterLabel + "</div><svg id=\"deadLetterTrendSvg\" viewBox=\"0 0 320 120\" class=\"w-100\" style=\"height:120px\"></svg></div>"
                + "</div></div></div></div></div>"
                + "<div class=\"row g-3 mb-3\" id=\"routing-trends-section\"><div class=\"col-12\"><div class=\"card section-card\"><div class=\"card-header\">" + alertRouteTrendsTitle + "</div><div class=\"card-body\">"
                + sectionIntro(alertRouteTrendIntro)
                + "<div class=\"row g-3\">"
                + "<div class=\"col-12 col-lg-6\"><div class=\"text-muted small mb-1\">" + alertRouteDeliveredTrendLabel + "</div><svg id=\"alertDeliveryTrendSvg\" viewBox=\"0 0 480 180\" class=\"w-100\" style=\"height:180px\"></svg></div>"
                + "<div class=\"col-12 col-lg-6\"><div class=\"text-muted small mb-1\">" + alertRouteFailedTrendLabel + "</div><svg id=\"alertFailureTrendSvg\" viewBox=\"0 0 480 180\" class=\"w-100\" style=\"height:180px\"></svg></div>"
                + "</div></div></div></div></div>"
                + "<div class=\"row g-3 mb-3\"><div class=\"col-12 col-xl-7\"><div class=\"card section-card h-100\"><div class=\"card-header\">" + incidentSeverityTrendsTitle + "</div><div class=\"card-body\">"
                + sectionIntro(incidentSeverityIntro)
                + "<svg id=\"incidentSeverityTrendSvg\" viewBox=\"0 0 640 180\" class=\"w-100\" style=\"height:180px\"></svg>"
                + "</div></div></div>"
                + "<div class=\"col-12 col-xl-5\"><div class=\"card section-card h-100\"><div class=\"card-header\">" + topFailingSignalsTitle + "</div><div class=\"card-body\">"
                + sectionIntro(topFailingSignalsIntro)
                + "<div id=\"failingSignalCards\" class=\"row g-3\"></div>"
                + "</div></div></div></div>"
                + "<div class=\"row g-3 mb-3\" id=\"triage-section\">"
                + "<div class=\"col-12 col-xl-5\"><div class=\"card section-card h-100\"><div class=\"card-header\">" + triageTitle + "</div><div class=\"card-body\">"
                + sectionIntro(triageIntro)
                + "<div class=\"mb-2\"><span id=\"triageStatus\" class=\"badge text-bg-secondary\">UNKNOWN</span></div>"
                + "<div class=\"fw-semibold mb-1\" id=\"triageBottleneck\"></div><div class=\"text-muted mb-3\" id=\"triageCause\"></div>"
                + "<div class=\"card border-0 bg-light-subtle mb-3\"><div class=\"card-body py-3\">"
                + "<div class=\"small text-muted mb-2\">" + escapeHtml(localized(language, "İlk müdahale rehberi", "First response guide")) + "</div>"
                + "<div class=\"small mt-1\"><span class=\"fw-semibold\">" + escapeHtml(uiString("explain.guidance.firstFixLabel", "First fix")) + ": </span><span id=\"triageGuideFirstFix\" class=\"text-muted\">-</span></div>"
                + "<div class=\"small mt-1\"><span class=\"fw-semibold\">" + escapeHtml(uiString("explain.guidance.checkAreaLabel", "Check here")) + ": </span><span id=\"triageGuideCheckArea\" class=\"text-muted\">-</span></div>"
                + "<div class=\"small mt-1\"><span class=\"fw-semibold\">" + escapeHtml(uiString("explain.guidance.likelyCauseLabel", "Likely root cause")) + ": </span><span id=\"triageGuideLikelyCause\" class=\"text-muted\">-</span></div>"
                + "</div></div>"
                + tableShellWithHead("triageEvidenceRows", uiString("triage.header.evidence", "Evidence"))
                + "</div></div></div>"
                + "<div class=\"col-12 col-xl-7\"><div class=\"card section-card h-100\"><div class=\"card-header\">" + serviceStatusTitle + "</div><div class=\"card-body\">"
                + sectionIntro(serviceStatusIntro)
                + tableShellWithHead("serviceRows",
                uiString("services.header.service", "Service"),
                uiString("services.header.status", "Status"),
                uiString("services.header.signal", "Key Signal"),
                uiString("services.header.detail", "What It Means"))
                + "<div class=\"card border-0 bg-light-subtle mt-3\"><div class=\"card-body py-3\">"
                + "<div class=\"small text-muted mb-1\">" + escapeHtml(localized(language, "Seçili servis rehberi", "Selected service guide")) + "</div>"
                + "<div id=\"serviceGuideTitle\" class=\"fw-semibold mb-2\">-</div>"
                + "<div class=\"small mt-1\"><span class=\"fw-semibold\">" + escapeHtml(uiString("explain.guidance.firstFixLabel", "First fix")) + ": </span><span id=\"serviceGuideFirstFix\" class=\"text-muted\">-</span></div>"
                + "<div class=\"small mt-1\"><span class=\"fw-semibold\">" + escapeHtml(uiString("explain.guidance.checkAreaLabel", "Check here")) + ": </span><span id=\"serviceGuideCheckArea\" class=\"text-muted\">-</span></div>"
                + "<div class=\"small mt-1\"><span class=\"fw-semibold\">" + escapeHtml(uiString("explain.guidance.likelyCauseLabel", "Likely root cause")) + ": </span><span id=\"serviceGuideLikelyCause\" class=\"text-muted\">-</span></div>"
                + "</div></div>"
                + "</div></div></div></div>"
                + "<div class=\"row g-3 mb-3\" id=\"background-errors-section\"><div class=\"col-12\"><div class=\"card section-card\"><div class=\"card-header\">" + backgroundErrorsTitle + "</div><div class=\"card-body\">"
                + sectionIntro(backgroundErrorsIntro)
                + "<div class=\"card border-0 bg-light-subtle mb-3\"><div class=\"card-body py-3\">"
                + "<div class=\"small text-muted mb-2\">" + escapeHtml(localized(language, "İlk müdahale rehberi", "First response guide")) + "</div>"
                + "<div class=\"small mt-1\"><span class=\"fw-semibold\">" + escapeHtml(uiString("explain.guidance.firstFixLabel", "First fix")) + ": </span><span id=\"backgroundGuideFirstFix\" class=\"text-muted\">-</span></div>"
                + "<div class=\"small mt-1\"><span class=\"fw-semibold\">" + escapeHtml(uiString("explain.guidance.checkAreaLabel", "Check here")) + ": </span><span id=\"backgroundGuideCheckArea\" class=\"text-muted\">-</span></div>"
                + "<div class=\"small mt-1\"><span class=\"fw-semibold\">" + escapeHtml(uiString("explain.guidance.likelyCauseLabel", "Likely root cause")) + ": </span><span id=\"backgroundGuideLikelyCause\" class=\"text-muted\">-</span></div>"
                + "</div></div>"
                + tableShellWithHead("backgroundErrorRows",
                uiString("backgroundErrors.header.worker", localized(language, "İşleyici", "Worker")),
                uiString("backgroundErrors.header.status", localized(language, "Durum", "Status")),
                uiString("backgroundErrors.header.lastSeen", localized(language, "Son Görülme", "Last Seen")),
                uiString("backgroundErrors.header.exception", localized(language, "Exception", "Exception")),
                uiString("backgroundErrors.header.origin", localized(language, "Kaynak Metot", "Origin")),
                uiString("backgroundErrors.header.message", localized(language, "Mesaj", "Message")),
                uiString("backgroundErrors.header.stack", localized(language, "Stack Trace", "Stack")))
                + "</div></div></div></div>"
                + "<div class=\"row g-3 mb-3\" id=\"diagnostics-section\"><div class=\"col-12\"><div class=\"card section-card\"><div class=\"card-header\">" + metricDiagnosticsTitle + "</div><div class=\"card-body\">"
                + sectionIntro(escapeHtml(localized(language, "Bu bölüm hata sayısını değil, inceleme için saklanan yönetim ve tanılama kayıtlarını gösterir. Bir satıra bastığında tam ayrıntısı aşağıda açılır.", "This section does not show an error count; it shows stored admin and diagnostic records for investigation. Click a row to open the full details below.")))
                + tableShellWithHead("diagnosticsRows",
                uiString("diagnostics.header.time", localized(language, "Zaman", "Recorded At")),
                uiString("diagnostics.header.source", localized(language, "Kaynak", "Source")),
                uiString("diagnostics.header.status", localized(language, "Durum", "Status")),
                uiString("diagnostics.header.note", localized(language, "Özet", "Note")))
                + "<div class=\"mt-3\"><div class=\"small text-muted mb-2\">" + escapeHtml(localized(language, "Seçili kayıt ayrıntısı", "Selected record details")) + "</div><pre id=\"diagnosticsDetail\" class=\"mb-0\">" + escapeHtml(localized(language, "Henüz bir kayıt seçilmedi.", "No record selected yet.")) + "</pre></div>"
                + "</div></div></div></div>"
                + "<div class=\"row g-3 mb-3\" id=\"projection-refresh-section\"><div class=\"col-12\"><div class=\"card section-card\"><div class=\"card-header\">" + projectionRefreshSectionTitle + "</div><div class=\"card-body\">"
                + sectionIntro(projectionRefreshSectionIntro)
                + "<div class=\"small text-muted mb-3\">" + projectionRefreshSummaryBody + "</div>"
                + "<div class=\"row g-3 mb-3\">"
                + metricCard(metricProjectionRefreshPendingTitle, "projectionRefreshSectionPending", metricProjectionRefreshPendingUnit, metricProjectionRefreshPendingDescription, "metric-supporting")
                + metricCard(metricProjectionRefreshDlqTitle, "projectionRefreshSectionDlq", metricProjectionRefreshDlqUnit, metricProjectionRefreshDlqDescription, "metric-supporting")
                + metricCard(escapeHtml(localized(language, "Projection Refresh Stream", "Projection Refresh Stream")), "projectionRefreshSectionStream", escapeHtml(localized(language, "adet", "count")), escapeHtml(localized(language, "Ana projection refresh stream'inde bekleyen olay sayısı.", "Number of events currently queued in the main projection refresh stream.")), "metric-supporting")
                + metricCard(escapeHtml(localized(language, "Projection Refresh Replay", "Projection Refresh Replay")), "projectionRefreshSectionReplayed", escapeHtml(localized(language, "adet", "count")), escapeHtml(localized(language, "Poison queue'dan yeniden ana kuyruğa alınmış projection refresh olay sayısı.", "Number of projection refresh events replayed from the poison queue back into the main stream.")), "metric-supporting")
                + "</div>"
                + "<div class=\"row g-3 mb-3\">"
                + "<div class=\"col-12 col-xl-4\"><div class=\"card border-0 bg-light-subtle h-100\"><div class=\"card-body py-3\">"
                + "<div class=\"small text-muted mb-1\">" + projectionRefreshSummaryTitle + "</div>"
                + "<div id=\"projectionRefreshPipelineStatus\" class=\"fw-semibold mb-2\">-</div>"
                + "<div class=\"small text-muted mt-1\"><span class=\"fw-semibold\">" + escapeHtml(localized(language, "Son işlenen", "Last processed")) + ": </span><span id=\"projectionRefreshLastProcessed\">-</span></div>"
                + "<div class=\"small text-muted mt-1\"><span class=\"fw-semibold\">" + escapeHtml(localized(language, "Tahmini gecikme", "Estimated lag")) + ": </span><span id=\"projectionRefreshLagEstimate\">-</span></div>"
                + "<div class=\"small text-muted mt-1\"><span class=\"fw-semibold\">" + escapeHtml(localized(language, "Son zehirli kayıt", "Last poison entry")) + ": </span><span id=\"projectionRefreshLastPoison\">-</span></div>"
                + "<div class=\"small text-muted mt-1\"><span class=\"fw-semibold\">" + escapeHtml(localized(language, "Son hata", "Last error")) + ": </span><span id=\"projectionRefreshLastError\">-</span></div>"
                + "</div></div></div>"
                + "<div class=\"col-12 col-xl-8\"><div class=\"card border-0 bg-light-subtle h-100\"><div class=\"card-body py-3\">"
                + "<div class=\"small text-muted mb-1\">" + escapeHtml(localized(language, "Replay durumu", "Replay status")) + "</div>"
                + "<div id=\"projectionRefreshReplayStatus\" class=\"small text-muted\">" + projectionRefreshReplayStatusIdle + "</div>"
                + "<div class=\"small text-muted mt-3\">" + escapeHtml(localized(language, "Replay bir DLQ kaydını ana projection stream'e tekrar yazar ve poison kaydını temizler.", "Replay writes a DLQ entry back to the main projection stream and removes the poison entry.")) + "</div>"
                + "</div></div></div>"
                + "</div>"
                + "<div class=\"fw-semibold mb-1\">" + projectionRefreshFailuresTitle + "</div>"
                + "<div class=\"small text-muted mb-2\">" + projectionRefreshFailuresIntro + "</div>"
                + tableShellWithHead("projectionRefreshFailureRows",
                localized(language, "Zaman", "Time"),
                localized(language, "Projection", "Projection"),
                localized(language, "Entity", "Entity"),
                localized(language, "İşlem", "Operation"),
                localized(language, "Deneme", "Attempt"),
                localized(language, "Hata", "Error"),
                localized(language, "Kaynak", "Origin"),
                localized(language, "Aksiyon", "Action"))
                + "</div></div></div></div>"
                + "<div class=\"row g-3 mb-3\" id=\"incidents-section\">"
                + "<div class=\"col-12 col-xl-6\"><div class=\"card section-card h-100\"><div class=\"card-header\">" + healthTitle + "</div><div class=\"card-body\">"
                + sectionIntro(healthIntro)
                + "<div class=\"mb-2\"><span id=\"status\" class=\"badge text-bg-secondary\">UNKNOWN</span></div>"
                + "<div class=\"card border-0 bg-light-subtle mb-3\"><div class=\"card-body py-3\">"
                + "<div class=\"small text-muted mb-1\">" + escapeHtml(localized(language, "Bu durumun nedeni", "Why this state happened")) + "</div>"
                + "<div id=\"healthReasonTitle\" class=\"fw-semibold mb-1\">-</div>"
                + "<div id=\"healthReasonBody\" class=\"small text-muted\">-</div>"
                + "<div id=\"healthReasonAction\" class=\"small mt-2\">-</div>"
                + "<div class=\"small mt-3\"><span class=\"fw-semibold\">" + escapeHtml(uiString("explain.guidance.firstFixLabel", "First fix")) + ": </span><span id=\"healthGuideFirstFix\" class=\"text-muted\">-</span></div>"
                + "<div class=\"small mt-1\"><span class=\"fw-semibold\">" + escapeHtml(uiString("explain.guidance.checkAreaLabel", "Check here")) + ": </span><span id=\"healthGuideCheckArea\" class=\"text-muted\">-</span></div>"
                + "<div class=\"small mt-1\"><span class=\"fw-semibold\">" + escapeHtml(uiString("explain.guidance.likelyCauseLabel", "Likely root cause")) + ": </span><span id=\"healthGuideLikelyCause\" class=\"text-muted\">-</span></div>"
                + "</div></div>"
                + tableShellWithHead("issues", uiString("health.header.issue", "Active Issue"))
                + "</div></div></div>"
                + "<div class=\"col-12 col-xl-6\"><div class=\"card section-card h-100\"><div class=\"card-header\">" + incidentsTitle + "</div><div class=\"card-body\">"
                + sectionIntro(incidentsIntro)
                + tableShellWithHead("incidents",
                uiString("incidents.header.severity", "Severity"),
                uiString("incidents.header.code", "Code"),
                uiString("incidents.header.detail", "Meaning"))
                + "</div></div></div></div>"
                + "</div>"
                + "<div class=\"workspace-group\" data-group=\"schema\">"
                + "<div class=\"workspace-group-header\"><div><h2 class=\"workspace-group-title\">" + workspaceSchemaLabel + "</h2><div class=\"workspace-group-copy\">" + escapeHtml(localized(language, "Bu alan canlı şema durumunu, kayıt yapısını ve dağıtım çerçevesini birlikte okumak için ayrıldı.", "This workspace groups live schema state, registry shape, and deployment posture.")) + "</div></div></div>"
                + "<div id=\"schema-runtime-section\">"
                + sectionPair(deploymentTitle, deploymentIntro, "deploymentRows",
                new String[]{uiString("deployment.header.parameter", "Parameter"), uiString("deployment.header.value", "Value")},
                schemaStatusTitle, schemaStatusIntro, "schemaRows",
                new String[]{uiString("schema.header.parameter", "Parameter"), uiString("schema.header.value", "Value")})
                + "</div>"
                + "<div id=\"schema-history-section\">"
                + sectionPair(schemaHistoryTitle, schemaHistoryIntro, "schemaHistoryRows",
                new String[]{uiString("schemaHistory.header.time", "Recorded At"), uiString("schemaHistory.header.operation", "Operation"), uiString("schemaHistory.header.result", "Result"), uiString("schemaHistory.header.progress", "Progress")},
                starterProfilesTitle, starterProfilesIntro, "profileRows",
                new String[]{uiString("profiles.header.name", "Profile"), uiString("profiles.header.schemaMode", "Schema Mode"), uiString("profiles.header.guardrails", "Guardrails"), uiString("profiles.header.note", "Note")})
                + "<div class=\"row g-3 mb-3\"><div class=\"col-12\"><div class=\"card section-card\"><div class=\"card-header\">" + apiRegistryTitle + "</div><div class=\"card-body\">"
                + sectionIntro(apiRegistryIntro)
                + tableShellWithHead("registryRows",
                uiString("registry.header.entity", "Entity"),
                uiString("registry.header.table", "Table"),
                uiString("registry.header.columns", "Columns"),
                uiString("registry.header.hotLimit", "Hot Limit"),
                uiString("registry.header.pageSize", "Page Size"))
                + "</div></div></div></div>"
                + "</div>"
                + "</div>"
                + "<div class=\"workspace-group\" data-group=\"runtime\">"
                + "<div class=\"workspace-group-header\"><div><h2 class=\"workspace-group-title\">" + workspaceRuntimeLabel + "</h2><div class=\"workspace-group-copy\">" + escapeHtml(localized(language, "Bu alan çalışma profilini, etkin ayarları ve profil değişimlerini birlikte yönetmek için hazırlanmıştır.", "This workspace is for managing runtime profile, effective tuning, and profile churn together.")) + "</div></div></div>"
                + "<div class=\"row g-3 mb-3\" id=\"runtime-profile-section\"><div class=\"col-12\"><div class=\"card section-card\"><div class=\"card-header\">" + runtimeProfileControlTitle + "</div><div class=\"card-body\">"
                + sectionIntro(runtimeProfileControlIntro)
                + "<div class=\"row g-3 mb-3\">"
                + "<div class=\"col-6 col-md-2\"><div class=\"small text-muted\">" + runtimeProfileActiveLabel + "</div><div id=\"runtimeProfileActive\" class=\"fw-semibold\">-</div></div>"
                + "<div class=\"col-6 col-md-2\"><div class=\"small text-muted\">" + runtimeProfileModeLabel + "</div><div id=\"runtimeProfileMode\" class=\"fw-semibold\">-</div></div>"
                + "<div class=\"col-6 col-md-2\"><div class=\"small text-muted\">" + runtimeProfilePressureLabel + "</div><div id=\"runtimeProfilePressure\" class=\"fw-semibold\">-</div></div>"
                + "<div class=\"col-6 col-md-2\"><div class=\"small text-muted\">" + runtimeProfileSwitchCountLabel + "</div><div id=\"runtimeProfileSwitchCount\" class=\"fw-semibold\">0</div></div>"
                + "<div class=\"col-6 col-md-2\"><div class=\"small text-muted\">" + runtimeProfileLastSwitchedLabel + "</div><div id=\"runtimeProfileLastSwitched\" class=\"fw-semibold\">-</div></div>"
                + "<div class=\"col-6 col-md-2\"><div class=\"small text-muted\">" + runtimeProfileManualOverrideLabel + "</div><div id=\"runtimeProfileManualOverride\" class=\"fw-semibold\">-</div></div>"
                + "</div>"
                + "<div class=\"d-flex gap-2 flex-wrap mb-3\">"
                + "<button id=\"runtimeProfileAuto\" class=\"btn btn-outline-secondary btn-sm\" type=\"button\">" + runtimeProfileAutoLabel + "</button>"
                + "<button id=\"runtimeProfileStandard\" class=\"btn btn-outline-secondary btn-sm\" type=\"button\">" + runtimeProfileStandardLabel + "</button>"
                + "<button id=\"runtimeProfileBalanced\" class=\"btn btn-outline-secondary btn-sm\" type=\"button\">" + runtimeProfileBalancedLabel + "</button>"
                + "<button id=\"runtimeProfileAggressive\" class=\"btn btn-outline-secondary btn-sm\" type=\"button\">" + runtimeProfileAggressiveLabel + "</button>"
                + "</div>"
                + "<div id=\"runtimeProfileStatus\" class=\"small text-muted mb-3\">" + runtimeProfileStatusIdle + "</div>"
                + tableShellWithHead("runtimeProfilePropertyRows",
                uiString("runtimeProfile.header.property", "Property"),
                uiString("runtimeProfile.header.value", "Value"),
                uiString("runtimeProfile.header.unit", "Unit"),
                uiString("runtimeProfile.header.detail", "Meaning"))
                + "</div></div></div></div>"
                + "<div class=\"row g-3 mb-3\" id=\"tuning-section\"><div class=\"col-12\"><div class=\"card section-card\"><div class=\"card-header\">" + currentEffectiveTuningTitle + "</div><div class=\"card-body\">"
                + sectionIntro(tuningIntro)
                + "<div class=\"row g-3 mb-3\">"
                + "<div class=\"col-md-4\"><div class=\"small text-muted\">" + tuningCapturedAtLabel + "</div><div id=\"tuningCapturedAt\" class=\"fw-semibold\">-</div></div>"
                + "<div class=\"col-md-4\"><div class=\"small text-muted\">" + tuningOverrideCountLabel + "</div><div id=\"tuningOverrideCount\" class=\"fw-semibold\">0</div></div>"
                + "<div class=\"col-md-4\"><div class=\"small text-muted\">" + tuningEntryCountLabel + "</div><div id=\"tuningEntryCount\" class=\"fw-semibold\">0</div></div>"
                + "</div>"
                + "<div class=\"d-flex gap-2 flex-wrap mb-3\"><button id=\"exportTuningJson\" class=\"btn btn-outline-primary btn-sm\">" + tuningExportJsonLabel + "</button><button id=\"exportTuningMarkdown\" class=\"btn btn-outline-secondary btn-sm\">" + tuningExportMarkdownLabel + "</button><button id=\"copyTuningFlags\" class=\"btn btn-outline-danger btn-sm\">" + tuningCopyFlagsLabel + "</button></div>"
                + "<div id=\"tuningExportStatus\" class=\"small text-muted mb-2\">" + tuningExportStatusIdle + "</div>"
                + "<pre id=\"tuningExport\" class=\"mb-3\"></pre>"
                + tableShellWithHead("tuningRows",
                uiString("tuning.header.group", "Group"),
                uiString("tuning.header.property", "Property"),
                uiString("tuning.header.value", "Value"),
                uiString("tuning.header.source", "Source"),
                uiString("tuning.header.detail", "Meaning"))
                + "</div></div></div></div>"
                + "</div>"
                + "<div class=\"workspace-group\" data-group=\"routing\">"
                + "<div class=\"workspace-group-header\"><div><h2 class=\"workspace-group-title\">" + workspaceRoutingLabel + "</h2><div class=\"workspace-group-copy\">" + escapeHtml(localized(language, "Bu alan uyarı rotalarını, teslim davranışını ve müdahale notlarını birlikte izlemek içindir.", "This workspace is for monitoring alert routes, delivery behavior, and response notes together.")) + "</div></div></div>"
                + "<div id=\"alert-routing-section\">"
                + sectionPair(alertRoutingTitle, alertRoutingIntro, "alertRouteRows",
                new String[]{uiString("alertRouting.header.route", "Route"), uiString("alertRouting.header.routeStatus", "Route Status"), uiString("alertRouting.header.escalation", "Escalation"), uiString("alertRouting.header.target", "Target"), uiString("alertRouting.header.delivered", "Delivered"), uiString("alertRouting.header.failed", "Failed"), uiString("alertRouting.header.dropped", "Dropped"), uiString("alertRouting.header.retry", "Retry Policy"), uiString("alertRouting.header.lastState", "Last Delivery State")},
                runbooksTitle, runbooksIntro, "runbookRows",
                new String[]{uiString("runbooks.header.code", "Code"), uiString("runbooks.header.title", "Title"), uiString("runbooks.header.firstAction", "First Action"), uiString("runbooks.header.reference", "Reference")})
                + "</div>"
                + "<div class=\"row g-3 mb-3\" id=\"alert-route-history-section\"><div class=\"col-12\"><div class=\"card section-card\"><div class=\"card-header\">" + alertRouteHistoryTitle + "</div><div class=\"card-body\">"
                + sectionIntro(alertRouteHistoryIntro)
                + tableShellWithHead("alertRouteHistoryRows",
                uiString("alertRouteHistory.header.time", "Recorded At"),
                uiString("alertRouteHistory.header.route", "Route"),
                uiString("alertRouteHistory.header.status", "Status"),
                uiString("alertRouteHistory.header.delivered", "Delivered"),
                uiString("alertRouteHistory.header.failed", "Failed"),
                uiString("alertRouteHistory.header.lastError", "Last Error"))
                + "</div></div></div></div>"
                + "<div class=\"row g-3 mb-3\"><div class=\"col-12\"><div class=\"card section-card\"><div class=\"card-header\">" + schemaDdlTitle + "</div><div class=\"card-body\">"
                + sectionIntro(schemaDdlIntro)
                + "<pre id=\"schemaDdl\" class=\"mb-0\"></pre></div></div></div></div>"
                + "<div class=\"row g-3 mb-3\" id=\"runtime-profile-churn-section\"><div class=\"col-12\"><div class=\"card section-card\"><div class=\"card-header\">" + runtimeProfileChurnTitle + "</div><div class=\"card-body\">"
                + sectionIntro(runtimeProfileIntro)
                + "<svg id=\"churnSvg\" viewBox=\"0 0 960 180\" class=\"w-100 mb-3\" style=\"height:180px\"></svg>"
                + tableShellWithHead("churnRows",
                uiString("churn.header.time", "Recorded At"),
                uiString("churn.header.transition", "Profile Transition"),
                uiString("churn.header.pressure", "Pressure Level"))
                + "</div></div></div></div>"
                + "</div>"
                + "<div class=\"workspace-group\" data-group=\"explain\">"
                + "<div class=\"workspace-group-header\"><div><h2 class=\"workspace-group-title\">" + workspaceExplainLabel + "</h2><div class=\"workspace-group-copy\">" + escapeHtml(localized(language, "Bu alan sorgunun neden yavaşladığını, hangi ilişki yolunda bozulduğunu ve hangi adımın maliyeti büyüttüğünü açar.", "This workspace explains why a query is slow, which relation path degraded, and which step became expensive.")) + "</div></div></div>"
                + "<div class=\"row g-3\" id=\"explain-section\"><div class=\"col-12\"><div class=\"card section-card\"><div class=\"card-header\">" + explainTitle + "</div><div class=\"card-body\">"
                + sectionIntro(explainIntro)
                + "<div class=\"row g-3\">"
                + "<div class=\"col-md-3\"><label class=\"form-label\">" + explainEntityLabel + "</label><input class=\"form-control\" id=\"entity\" value=\"UserEntity\"></div>"
                + "<div class=\"col-md-3\"><label class=\"form-label\">" + explainFilterLabel + "</label><input class=\"form-control\" id=\"filter\" value=\"status:eq:ACTIVE\"></div>"
                + "<div class=\"col-md-2\"><label class=\"form-label\">" + explainSortLabel + "</label><input class=\"form-control\" id=\"sort\" value=\"id:desc\"></div>"
                + "<div class=\"col-md-2\"><label class=\"form-label\">" + explainLimitLabel + "</label><input class=\"form-control\" id=\"limit\" value=\"10\"></div>"
                + "<div class=\"col-md-2\"><label class=\"form-label\">" + explainIncludeLabel + "</label><input class=\"form-control\" id=\"include\" value=\"\"></div>"
                + "</div>"
                + "<div class=\"row g-3 mt-2\"><div class=\"col-md-6\"><label class=\"form-label\">" + escapeHtml(uiString("explain.noteTitleLabel", "Note Title")) + "</label><input class=\"form-control\" id=\"explainNoteTitle\" value=\"" + escapeHtml(uiString("explain.noteTitleDefault", "Explain note")) + "\"></div></div>"
                + "<div class=\"mt-3 d-flex flex-wrap gap-2\"><button id=\"runExplain\" class=\"btn btn-danger\">" + escapeHtml(uiString("runExplainLabel", "Run Explain")) + "</button><button id=\"copyExplainMarkdown\" class=\"btn btn-outline-secondary\">" + escapeHtml(uiString("explain.copyMarkdownLabel", "Copy Explain as Markdown")) + "</button><button id=\"downloadExplainJson\" class=\"btn btn-outline-secondary\">" + escapeHtml(uiString("explain.downloadJsonLabel", "Download Explain JSON")) + "</button><button id=\"saveExplainNote\" class=\"btn btn-outline-secondary\">" + escapeHtml(uiString("explain.saveNoteLabel", "Save as Incident Note")) + "</button></div>"
                + "<div id=\"explainActionStatus\" class=\"small text-muted mt-2\">" + escapeHtml(uiString("explain.actionStatusIdle", "Explain actions are ready.")) + "</div>"
                + "<div class=\"small text-muted mt-2\">" + escapeHtml(uiString("explain.helpText", "Run Explain after changing entity/filter/include. Use Relation States to see whether a relation path was resolved, degraded, or only used for fetch preload.")) + "</div>"
                + "<div class=\"row g-3 mt-3\">"
                + "<div class=\"col-12 col-md-3\"><div class=\"card border-0 bg-light h-100\"><div class=\"card-body\"><div class=\"small text-muted\">" + escapeHtml(uiString("explain.summary.plannerLabel", "Planner")) + "</div><div id=\"explainPlanner\" class=\"fw-semibold fs-5\">-</div></div></div></div>"
                + "<div class=\"col-12 col-md-3\"><div class=\"card border-0 bg-light h-100\"><div class=\"card-body\"><div class=\"small text-muted\">" + escapeHtml(uiString("explain.summary.sortLabel", "Sort")) + "</div><div id=\"explainSort\" class=\"fw-semibold fs-5\">-</div></div></div></div>"
                + "<div class=\"col-6 col-md-3\"><div class=\"card border-0 bg-light h-100\"><div class=\"card-body\"><div class=\"small text-muted\">" + escapeHtml(uiString("explain.summary.candidatesLabel", "Candidates")) + "</div><div id=\"explainCandidates\" class=\"fw-semibold fs-5\">0</div></div></div></div>"
                + "<div class=\"col-6 col-md-3\"><div class=\"card border-0 bg-light h-100\"><div class=\"card-body\"><div class=\"small text-muted\">" + escapeHtml(uiString("explain.summary.warningsLabel", "Warnings")) + "</div><div id=\"explainWarningCount\" class=\"fw-semibold fs-5\">0</div></div></div></div>"
                + "<div class=\"col-6 col-md-4\"><div class=\"card border-0 bg-light h-100\"><div class=\"card-body\"><div class=\"small text-muted\">" + escapeHtml(uiString("explain.summary.estimatedCostLabel", "Estimated Cost")) + "</div><div id=\"explainEstimatedCost\" class=\"fw-semibold fs-5\">0</div></div></div></div>"
                + "<div class=\"col-6 col-md-4\"><div class=\"card border-0 bg-light h-100\"><div class=\"card-body\"><div class=\"small text-muted\">" + escapeHtml(uiString("explain.summary.fullyIndexedLabel", "Fully Indexed")) + "</div><div id=\"explainFullyIndexed\" class=\"fw-semibold fs-5\">-</div></div></div></div>"
                + "<div class=\"col-12 col-md-4\"><div class=\"card border-0 bg-light h-100\"><div class=\"card-body\"><div class=\"small text-muted\">" + escapeHtml(uiString("explain.summary.stepCountLabel", "Explain Steps")) + "</div><div id=\"explainStepCount\" class=\"fw-semibold fs-5\">0</div></div></div></div>"
                + "</div>"
                + "<div class=\"row g-3 mt-1\">"
                + "<div class=\"col-12 col-md-4\"><div class=\"card border-0 bg-light h-100\"><div class=\"card-body\"><div class=\"small text-muted\">" + escapeHtml(uiString("explain.triage.whySlowLabel", "Why Slow")) + "</div><div id=\"explainWhySlow\" class=\"fw-semibold\">-</div><div id=\"explainWhySlowDetail\" class=\"small text-muted mt-2\">-</div></div></div></div>"
                + "<div class=\"col-12 col-md-4\"><div class=\"card border-0 bg-light h-100\"><div class=\"card-body\"><div class=\"small text-muted\">" + escapeHtml(uiString("explain.triage.whyDegradedLabel", "Why Degraded")) + "</div><div id=\"explainWhyDegraded\" class=\"fw-semibold\">-</div><div id=\"explainWhyDegradedDetail\" class=\"small text-muted mt-2\">-</div></div></div></div>"
                + "<div class=\"col-12 col-md-4\"><div class=\"card border-0 bg-light h-100\"><div class=\"card-body\"><div class=\"small text-muted\">" + escapeHtml(uiString("explain.triage.suspiciousStepLabel", "Top Suspicious Step")) + "</div><div id=\"explainSuspiciousStep\" class=\"fw-semibold\">-</div><div id=\"explainSuspiciousStepDetail\" class=\"small text-muted mt-2\">-</div></div></div></div>"
                + "</div>"
                + "<div class=\"row g-3 mt-1\">"
                + "<div class=\"col-12 col-xl-4\"><div class=\"card border-0 bg-light h-100\"><div class=\"card-body\"><div class=\"fw-semibold mb-2\">" + escapeHtml(uiString("explain.warningsTitle", "Explain Warnings")) + "</div>"
                + tableShellWithHead("explainWarningRows", uiString("explain.warningHeader", "warning"))
                + "</div></div></div>"
                + "<div class=\"col-12 col-xl-8\"><div class=\"card border-0 bg-light h-100\"><div class=\"card-body\"><div class=\"d-flex flex-wrap justify-content-between align-items-center gap-2 mb-2\"><div class=\"fw-semibold\">" + escapeHtml(uiString("explain.relationStatesTitle", "Relation States")) + "</div><div class=\"d-flex flex-wrap gap-2 align-items-center\"><select id=\"explainRelationUsageFilter\" class=\"form-select form-select-sm\" style=\"width:auto\"><option value=\"ALL\">" + escapeHtml(uiString("explain.relationFilter.allLabel", "All")) + "</option><option value=\"FILTER\">" + escapeHtml(uiString("explain.relationFilter.filterLabel", "Filter")) + "</option><option value=\"FETCH\">" + escapeHtml(uiString("explain.relationFilter.fetchLabel", "Fetch")) + "</option></select><div class=\"form-check form-switch m-0\"><input class=\"form-check-input\" type=\"checkbox\" id=\"explainRelationWarningsOnly\"><label class=\"form-check-label small\" for=\"explainRelationWarningsOnly\">" + escapeHtml(uiString("explain.relationFilter.warningsOnlyLabel", "Warnings only")) + "</label></div></div></div>"
                + tableShellWithHead("explainRelationRows",
                uiString("explain.relationHeader.relation", "relation"),
                uiString("explain.relationHeader.usage", "usage"),
                uiString("explain.relationHeader.status", "status"),
                uiString("explain.relationHeader.kind", "kind"),
                uiString("explain.relationHeader.target", "target"),
                uiString("explain.relationHeader.mappedBy", "mappedBy"),
                uiString("explain.relationHeader.candidates", "candidates"),
                uiString("explain.relationHeader.detail", "detail"))
                + "</div></div></div>"
                + "</div>"
                + "<div class=\"row g-3 mt-1\">"
                + "<div class=\"col-12\"><div class=\"card border-0 bg-light h-100\"><div class=\"card-body\"><div class=\"fw-semibold mb-2\">" + escapeHtml(uiString("explain.stepsTitle", "Explain Steps")) + "</div>"
                + tableShellWithHead("explainStepRows",
                uiString("explain.stepHeader.stage", "stage"),
                uiString("explain.stepHeader.strategy", "strategy"),
                uiString("explain.stepHeader.indexed", "indexed"),
                uiString("explain.stepHeader.candidates", "candidates"),
                uiString("explain.stepHeader.cost", "cost"),
                uiString("explain.stepHeader.expression", "expression"),
                uiString("explain.stepHeader.detail", "detail"))
                + "</div></div></div>"
                + "</div>"
                + "<div class=\"mt-3\"><div class=\"fw-semibold mb-2\">" + escapeHtml(uiString("explain.rawTitle", "Raw Explain JSON")) + "</div><pre id=\"explain\" class=\"mb-0\"></pre></div>"
                + "</div></div></div></div>"
                + "</div>"
                + "</section></main>"
                + "<script>"
                + "class OpsBaseElement extends HTMLElement{connectedCallback(){if(this.dataset.opsReady==='1'){return;}this.dataset.opsReady='1';}}"
                + "class OpsInteractiveElement extends OpsBaseElement{connectedCallback(){super.connectedCallback();if(!this.hasAttribute('role')){this.setAttribute('role','button');}if(!this.hasAttribute('tabindex')){this.tabIndex=0;}if(this.dataset.opsKeyboardBound==='1'){return;}this.dataset.opsKeyboardBound='1';this.addEventListener('keydown',e=>{if(e.key==='Enter'||e.key===' '){e.preventDefault();this.click();}});}}"
                + "customElements.define('ops-panel',class extends OpsBaseElement{});"
                + "customElements.define('ops-metric-card',class extends OpsBaseElement{});"
                + "customElements.define('ops-data-table',class extends OpsBaseElement{});"
                + "customElements.define('ops-taxonomy-card',class extends OpsBaseElement{});"
                + "customElements.define('ops-taxonomy-chip',class extends OpsBaseElement{});"
                + "customElements.define('ops-nav-button',class extends OpsInteractiveElement{});"
                + "customElements.define('ops-workspace-button',class extends OpsInteractiveElement{});"
                + "customElements.define('ops-jump-link',class extends OpsInteractiveElement{});"
                + "customElements.define('ops-signal-board',class extends OpsBaseElement{});"
                + "customElements.define('ops-signal-widget',class extends OpsBaseElement{});"
                + "function upgradeElement(selector,tagName){document.querySelectorAll(selector).forEach(node=>{if(node.tagName&&node.tagName.toLowerCase()===tagName){return;}const upgraded=document.createElement(tagName);[...node.attributes].forEach(attr=>upgraded.setAttribute(attr.name,attr.value));upgraded.className=node.className;while(node.firstChild){upgraded.appendChild(node.firstChild);}node.replaceWith(upgraded);});}"
                + "function upgradeDashboardComponents(){upgradeElement('.section-card','ops-panel');upgradeElement('.metric-card','ops-metric-card');upgradeElement('.table-responsive','ops-data-table');upgradeElement('.glossary-card','ops-taxonomy-card');upgradeElement('.taxonomy-item','ops-taxonomy-chip');upgradeElement('.signal-widget','ops-signal-widget');upgradeElement('.signal-board','ops-signal-board');}"
                + "upgradeDashboardComponents();"
                + "const adminBasePath='" + adminBasePath + "';"
                + "const dashboardInstanceId='" + escapeJs(dashboardInstanceId) + "';"
                + "function adminApi(path){return (adminBasePath||'')+path;}"
                + "function fetchOptions(method,signal){const options={method:method||'GET',cache:'no-store',headers:{'Cache-Control':'no-cache','Pragma':'no-cache'}};if(signal){options.signal=signal;}return options;}"
                + "function ensureDashboardInstance(meta){const liveId=String(meta&&meta.instanceId||'').trim();if(!liveId||liveId===dashboardInstanceId){return true;}window.location.reload();return false;}"
                + "async function json(u){const r=await fetch(adminApi(u),fetchOptions('GET'));if(!r.ok)throw new Error(await r.text());return r.json();}"
                + "async function postJson(u){const r=await fetch(adminApi(u),fetchOptions('POST'));if(!r.ok)throw new Error(await r.text());return r.json();}"
                + "async function jsonWithTimeout(u,label){const controller=new AbortController();const timeout=setTimeout(()=>controller.abort(),8000);try{const r=await fetch(adminApi(u),fetchOptions('GET',controller.signal));if(!r.ok)throw new Error(await r.text());return {ok:true,label,data:await r.json()};}catch(e){return {ok:false,label,error:(e&&e.name==='AbortError')?('timeout: '+label):(e&&e.message?e.message:String(e))};}finally{clearTimeout(timeout);}}"
                + "async function jsonWithTimeoutRaw(u,label){const controller=new AbortController();const timeout=setTimeout(()=>controller.abort(),8000);try{const r=await fetch(u,fetchOptions('GET',controller.signal));if(!r.ok)throw new Error(await r.text());return {ok:true,label,data:await r.json()};}catch(e){return {ok:false,label,error:(e&&e.name==='AbortError')?('timeout: '+label):(e&&e.message?e.message:String(e))};}finally{clearTimeout(timeout);}}"
                + "async function postJsonRaw(u){const r=await fetch(u,fetchOptions('POST'));if(!r.ok)throw new Error(await r.text());return r.json();}"
                + "async function runBatchedRequests(requests,limit){const batchSize=Math.max(1,limit||4);const results=[];for(let i=0;i<requests.length;i+=batchSize){const batch=requests.slice(i,i+batchSize);const settled=await Promise.all(batch.map(([key,url,label])=>jsonWithTimeout(url,label).then(result=>({...result,key}))));results.push(...settled);}return results;}"
                + "const dashboardDefaults={h:{status:'DOWN',issues:[],metrics:{}},m:{writeBehindStreamLength:0,deadLetterStreamLength:0,plannerStatisticsSnapshot:{learnedStatisticsKeyCount:0},redisGuardrailSnapshot:{usedMemoryBytes:0,compactionPendingCount:0},redisRuntimeProfileSnapshot:{activeProfile:'STANDARD',lastObservedPressureLevel:'NORMAL'},projectionRefreshSnapshot:{streamLength:0,deadLetterStreamLength:0,processedCount:0,retriedCount:0,failedCount:0,replayedCount:0,claimedCount:0,pendingCount:0,lastProcessedAtEpochMillis:0,lastErrorAtEpochMillis:0,lastErrorType:'',lastErrorMessage:'',lastErrorRootType:'',lastErrorRootMessage:'',lastErrorOrigin:'',lastPoisonEntryId:''}},sig:{incidentCount:0,criticalIncidentCount:0,warningIncidentCount:0,alertDeliveredCount:0,alertFailedCount:0,alertDroppedCount:0,alertRouteCount:0,recentBackgroundErrorCount:0,recentBackgroundErrorWorker:'',recentBackgroundErrorType:'',recentBackgroundErrorOrigin:'',triageOverallStatus:'UP',triagePrimaryBottleneck:'healthy',triageSuspectedCause:''},perf:{redisRead:{operationCount:0,averageMicros:0,p95Micros:0,p99Micros:0,maxMicros:0,lastMicros:0,lastObservedAtEpochMillis:0},redisWrite:{operationCount:0,averageMicros:0,p95Micros:0,p99Micros:0,maxMicros:0,lastMicros:0,lastObservedAtEpochMillis:0},postgresRead:{operationCount:0,averageMicros:0,p95Micros:0,p99Micros:0,maxMicros:0,lastMicros:0,lastObservedAtEpochMillis:0},postgresWrite:{operationCount:0,averageMicros:0,p95Micros:0,p99Micros:0,maxMicros:0,lastMicros:0,lastObservedAtEpochMillis:0}},perfHistory:{items:[]},shape:{items:[],recordedAtEpochMillis:0},t:{overallStatus:'DOWN',primaryBottleneck:'unknown',suspectedCause:'',evidence:[]},svc:{items:[]},bg:{items:[]},diagRecords:{items:[]},pr:{streamLength:0,deadLetterStreamLength:0,processedCount:0,retriedCount:0,failedCount:0,replayedCount:0,claimedCount:0,pendingCount:0,lastProcessedAtEpochMillis:0,lastErrorAtEpochMillis:0,lastErrorType:'',lastErrorMessage:'',lastErrorRootType:'',lastErrorRootMessage:'',lastErrorOrigin:'',lastPoisonEntryId:''},prFailed:{items:[]},route:{items:[]},routeHistory:{items:[]},rb:{items:[]},i:{items:[]},incidentTrend:{items:[]},failingSignals:{items:[]},c:{items:[]},d:{schemaBootstrapMode:'-',schemaAutoApplyOnStart:false,writeBehindEnabled:false,writeBehindWorkerThreads:0,durableCompactionEnabled:false,activeWriteStreamCount:0,redisGuardrailsEnabled:false,automaticRuntimeProfileSwitchingEnabled:false,keyPrefix:'-'},s:{bootstrapMode:'-',validationSucceeded:false,migrationStepCount:0,createTableStepCount:0,addColumnStepCount:0,ddlEntityCount:0},sh:{items:[]},ddl:{items:[]},p:{items:[]},r:{entities:[]},rp:{activeProfile:'STANDARD',mode:'AUTO',lastObservedPressureLevel:'NORMAL',switchCount:0,lastSwitchedAtEpochMillis:0,manualOverrideProfile:'',properties:[]},tuning:{capturedAt:'',explicitOverrideCount:0,entryCount:0,items:[]},history:{items:[]}};"
                + "const dashboardLazySections={"
                + "'performance-section':[['perf','/api/performance','" + escapeJs(localized(language, "Performans özeti", "Performance snapshot")) + "',false],['perfHistory','/api/performance/history?limit=60','" + escapeJs(localized(language, "Performans geçmişi", "Performance history")) + "',false],['shape','/demo-load/api/scenario-shapes','" + escapeJs(localized(language, "Senaryo yük şekli", "Scenario shape")) + "',true]],"
                + "'live-trends':[['history','/api/history?limit=60','" + escapeJs(localized(language, "Geçmiş trendler", "Historical trends")) + "',false]],"
                + "'signal-dashboard':[['incidentTrend','/api/incident-severity/history?limit=120','" + escapeJs(localized(language, "Olay trendleri", "Incident trends")) + "',false,false],['failingSignals','/api/failing-signals?limit=6','" + escapeJs(localized(language, "Başarısız sinyaller", "Failing signals")) + "',false,false]],"
                + "'projection-refresh-section':[['pr','/api/projection-refresh','" + escapeJs(localized(language, "Projection refresh özeti", "Projection refresh snapshot")) + "',false,false],['prFailed','/api/projection-refresh/failed?limit=12','" + escapeJs(localized(language, "Projection refresh DLQ", "Projection refresh DLQ")) + "',false,false]],"
                + "'triage-section':[['t','/api/triage','" + escapeJs(localized(language, "Önceliklendirme", "Triage")) + "',false],['svc','/api/services','" + escapeJs(localized(language, "Servisler", "Services")) + "',false]],"
                + "'background-errors-section':[['bg','/api/background-errors','" + escapeJs(localized(language, "Arka plan hataları", "Background worker errors")) + "',false]],"
                + "'incidents-section':[['i','/api/incidents','" + escapeJs(localized(language, "Açık olaylar", "Open incidents")) + "',false]],"
                + "'routing-trends-section':[['routeHistory','/api/alert-routing/history?limit=160','" + escapeJs(localized(language, "Uyarı yönlendirme geçmişi", "Alert route history")) + "',false]],"
                + "'diagnostics-section':[['diagRecords','/api/diagnostics?limit=20','" + escapeJs(localized(language, "Tanılama kayıtları", "Diagnostics records")) + "',false]],"
                + "'runtime-profile-churn-section':[['c','/api/profile-churn?limit=24','" + escapeJs(localized(language, "Profil geçiş geçmişi", "Profile churn")) + "',false]],"
                + "'schema-runtime-section':[['d','/api/deployment','" + escapeJs(localized(language, "Canlı yapı", "Deployment")) + "',false],['s','/api/schema/status','" + escapeJs(localized(language, "Şema durumu", "Schema status")) + "',false]],"
                + "'schema-history-section':[['sh','/api/schema/history?limit=8','" + escapeJs(localized(language, "Şema geçmişi", "Schema history")) + "',false],['ddl','/api/schema/ddl','DDL',false],['p','/api/profiles','" + escapeJs(localized(language, "Başlangıç profilleri", "Starter profiles")) + "',false],['r','/api/registry','" + escapeJs(localized(language, "Varlık kaydı", "Registry")) + "',false]],"
                + "'runtime-profile-section':[['rp','/api/runtime-profile','" + escapeJs(localized(language, "Çalışma profili", "Runtime profile")) + "',false]],"
                + "'tuning-section':[['tuning','/api/tuning','" + escapeJs(localized(language, "Etkin ayarlar", "Effective tuning")) + "',false]],"
                + "'alert-routing-section':[['route','/api/alert-routing','" + escapeJs(localized(language, "Uyarı yönlendirme", "Alert routing")) + "',false],['rb','/api/runbooks','" + escapeJs(localized(language, "Runbook kayıtları", "Runbooks")) + "',false]],"
                + "'alert-route-history-section':[['routeHistory','/api/alert-routing/history?limit=160','" + escapeJs(localized(language, "Uyarı yönlendirme geçmişi", "Alert route history")) + "',false]]"
                + "};"
                + "let refreshTimer=null;let refreshPaused=false;let refreshInFlight=null;window.__dashboardDataCache=window.__dashboardDataCache||{};window.__dashboardHasLoadedOnce=window.__dashboardHasLoadedOnce||false;window.__dashboardLazyLoadedKeys=window.__dashboardLazyLoadedKeys||{};window.__dashboardLazyInflight=window.__dashboardLazyInflight||{};window.__dashboardVisibleLazySections=window.__dashboardVisibleLazySections||{};window.__dashboardLazyObserverReady=window.__dashboardLazyObserverReady||false;"
                + "function mergeDashboardData(data){const cache=window.__dashboardDataCache||{};Object.keys(data||{}).forEach(key=>{if(data[key]!==undefined&&data[key]!==null){cache[key]=data[key];}});window.__dashboardDataCache=cache;return cache;}"
                + "function dashboardData(key){const cache=window.__dashboardDataCache||{};return cache[key]||dashboardDefaults[key];}"
                + "function sectionRequests(sectionId){return dashboardLazySections[sectionId]||[];}"
                + "function captureVisibleLazySections(){Object.keys(dashboardLazySections).forEach(id=>{const target=document.getElementById(id);if(!target){return;}const rect=target.getBoundingClientRect();if(rect.top<(window.innerHeight+320)&&rect.bottom>-320){window.__dashboardVisibleLazySections[id]=true;}});}"
                + "async function loadLazySection(sectionId,force){const requests=sectionRequests(sectionId);if(!requests.length){return {failures:[]};}const jobs=requests.map(([key,url,label,isRaw,blocking])=>{if(window.__dashboardLazyInflight[key]){return window.__dashboardLazyInflight[key];}const shouldSkip=!force&&window.__dashboardLazyLoadedKeys[key];if(shouldSkip){return Promise.resolve({ok:true,key,label,blocking:blocking!==false,skip:true,data:dashboardData(key)});}const loader=((isRaw?jsonWithTimeoutRaw(url,label):jsonWithTimeout(url,label)).then(result=>({...result,key,blocking:blocking!==false}))).finally(()=>{delete window.__dashboardLazyInflight[key];});window.__dashboardLazyInflight[key]=loader;return loader;});const settled=await Promise.all(jobs);settled.forEach(item=>{if(item.ok){mergeDashboardData({[item.key]:item.data});window.__dashboardLazyLoadedKeys[item.key]=true;}});return {failures:settled.filter(item=>!item.ok)};}"
                + "async function loadVisibleLazySections(force){const sectionIds=Object.keys(window.__dashboardVisibleLazySections||{}).filter(key=>window.__dashboardVisibleLazySections[key]);if(!sectionIds.length){return {failures:[]};}const results=await Promise.all(sectionIds.map(sectionId=>loadLazySection(sectionId,force)));return {failures:results.flatMap(result=>result.failures||[])};}"
                + "function markLazySectionVisible(sectionId){if(!sectionId){return;}window.__dashboardVisibleLazySections[sectionId]=true;loadLazySection(sectionId,false).then(result=>renderDashboardFromCache(result.failures||[])).catch(()=>{});}"
                + "function setupLazySectionObserver(){if(window.__dashboardLazyObserverReady){return;}window.__dashboardLazyObserverReady=true;const ids=Object.keys(dashboardLazySections);if(!('IntersectionObserver' in window)){ids.forEach(markLazySectionVisible);return;}const observer=new IntersectionObserver(entries=>{entries.forEach(entry=>{if(entry.isIntersecting){markLazySectionVisible(entry.target.id);}});},{rootMargin:'320px 0px'});window.__dashboardLazyObserver=observer;ids.forEach(id=>{const target=document.getElementById(id);if(target){observer.observe(target);const rect=target.getBoundingClientRect();if(rect.top<(window.innerHeight+320)&&rect.bottom>-320){markLazySectionVisible(id);}}});}"
                + "const metricIds=['wb','dlq','projectionRefreshDlq','diag','inc','learned','redisMem','compactionPending','projectionRefreshPending','runtimeProfile','alertDelivered','alertFailed','alertDropped','criticalSignals','warningSignals','projectionRefreshSectionPending','projectionRefreshSectionDlq','projectionRefreshSectionStream','projectionRefreshSectionReplayed'];"
                + "function clip(v,max){if(!v)return '';return v.length>max?v.slice(0,max-1)+'…':v;}"
                + "function formatDurationMillis(value){const ms=Number(value)||0;if(ms<=0){return '-';}if(ms<1000){return Math.round(ms)+' ms';}const seconds=ms/1000;if(seconds<60){return (seconds>=10?seconds.toFixed(1):seconds.toFixed(2))+' s';}const minutes=Math.floor(seconds/60);const remainingSeconds=Math.round(seconds%60);return minutes+' min '+remainingSeconds+' s';}"
                + "function statusClass(v){return v==='DOWN'?'text-bg-danger':(v==='DEGRADED'?'text-bg-warning':'text-bg-success');}"
                + "function badge(v){return '<span class=\"badge '+statusClass(v)+'\">'+v+'</span>';}"
                + "function rows(id,items,empty,colspan){const span=colspan||8;document.getElementById(id).innerHTML=items.length?items.map(r=>'<tr>'+r.map(c=>'<td>'+c+'</td>').join('')+'</tr>').join(''):'<tr><td colspan=\"'+span+'\" class=\"text-muted\">'+empty+'</td></tr>';}"
                + "function escapeAttr(v){return String(v??'').replace(/&/g,'&amp;').replace(/\"/g,'&quot;').replace(/</g,'&lt;').replace(/>/g,'&gt;');}"
                + "function escapeText(v){return String(v??'').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');}"
                + "function titled(value,max,mono){const raw=String(value??'');const cls=mono?'mono cell-text':'cell-text';return '<span class=\"'+cls+'\" title=\"'+escapeAttr(raw)+'\">'+escapeText(raw)+'</span>';}"
                + "function setDashboardDataState(state,detail){const host=document.getElementById('dashboardDataStatus');const title=document.getElementById('dashboardDataStatusTitle');const copy=document.getElementById('dashboardDataStatusDetail');const badgeHost=document.getElementById('dashboardDataStatusBadge');if(!host||!title||!copy||!badgeHost){return;}host.dataset.state=state;if(state==='loading'){title.textContent='" + escapeJs(uiString("dashboardDataStatus.loadingTitle", "Veriler yükleniyor")) + "';copy.textContent=detail||'" + escapeJs(uiString("dashboardDataStatus.loadingDetail", "Yönetim API'lerinden son metrikler ve servis durumu alınıyor.")) + "';badgeHost.innerHTML=badge('DEGRADED');return;}if(state==='error'){title.textContent='" + escapeJs(uiString("dashboardDataStatus.errorTitle", "Veri alınamadı")) + "';copy.textContent=detail||'" + escapeJs(uiString("dashboardDataStatus.errorDetail", "Yönetim verisi alınamadı. Ağ, tarayıcı konsolu veya admin API durumunu kontrol et.")) + "';badgeHost.innerHTML=badge('DOWN');return;}title.textContent='" + escapeJs(uiString("dashboardDataStatus.successTitle", "Veri güncel")) + "';copy.textContent=detail||'" + escapeJs(uiString("dashboardDataStatus.successDetail", "Metrikler ve servis durumu başarıyla yenilendi.")) + "';badgeHost.innerHTML=badge('UP');}"
                + "function hasDashboardCachedData(){return Object.keys(window.__dashboardDataCache||{}).length>0;}"
                + "function setMetricPlaceholder(id,text,isError){const element=document.getElementById(id);if(!element){return;}element.textContent=text;element.title=String(text||'');element.classList.toggle('metric-placeholder',!isError);element.classList.toggle('metric-error',!!isError);}"
                + "function setMetricsLoadingState(){metricIds.forEach(id=>setMetricPlaceholder(id,'" + escapeJs(uiString("loadingText", "Yükleniyor…")) + "',false));document.getElementById('lastUpdated').textContent='" + escapeJs(uiString("lastUpdatedLoading", "yenileniyor…")) + "';}"
                + "function setMetricsErrorState(detail){metricIds.forEach(id=>setMetricPlaceholder(id,'" + escapeJs(uiString("dashboardDataStatus.metricError", "Veri alınamadı")) + "',true));document.getElementById('lastUpdated').textContent='" + escapeJs(uiString("lastUpdatedError", "güncellenemedi")) + "';const explain=document.getElementById('explain');if(explain&&detail){explain.textContent=detail;}}"
                + "function formatLatencyMicros(value){const micros=Number(value)||0;if(micros<=0){return '-';}if(micros>=1000){const ms=micros/1000;return (ms>=100?Math.round(ms):ms.toFixed(ms>=10?1:2))+' ms';}return Math.round(micros)+' µs';}"
                + "function formatLatencyObservedAt(value){const epoch=Number(value)||0;return epoch>0?new Date(epoch).toLocaleTimeString():'-';}"
                + "function latencyNote(metric,emptyText,observedText){if((Number(metric&&metric.operationCount)||0)===0){return emptyText;}return observedText+' '+formatLatencyObservedAt(metric.lastObservedAtEpochMillis);}"
                + "function setTrendClass(id,direction){const node=document.getElementById(id);if(!node){return;}node.classList.remove('trend-up','trend-down','trend-flat');node.classList.add(direction==='rising'?'trend-up':(direction==='falling'?'trend-down':'trend-flat'));}"
                + "function renderLatencyCard(prefix,metric,descriptor,emptyText,observedText){document.getElementById(prefix+'Count').textContent=String(Number(metric&&metric.operationCount)||0);document.getElementById(prefix+'Avg').textContent=formatLatencyMicros(metric&&metric.averageMicros);document.getElementById(prefix+'P95').textContent=formatLatencyMicros(metric&&metric.p95Micros);document.getElementById(prefix+'P99').textContent=formatLatencyMicros(metric&&metric.p99Micros);document.getElementById(prefix+'Max').textContent=formatLatencyMicros(metric&&metric.maxMicros);document.getElementById(prefix+'LastSeen').textContent=formatLatencyObservedAt(metric&&metric.lastObservedAtEpochMillis);document.getElementById(prefix+'Note').textContent=latencyNote(metric,emptyText,observedText);setTrendClass(prefix+'Avg',descriptor.direction);setTrendClass(prefix+'P95',descriptor.direction);}"
                + "function performanceHistorySeries(items,avgKey,countKey){return {latency:items.map(v=>Number(v[avgKey])||0),operations:items.map(v=>Number(v[countKey])||0)};}"
                + "function performanceTrendTone(metric,descriptor){if(metric.operationCount===0){return 'neutral';}if(descriptor.direction==='rising'&&(Number(metric.p95Micros)||0)>=1000){return 'danger';}if(descriptor.direction==='rising'){return 'warning';}if(descriptor.direction==='falling'){return 'success';}return 'neutral';}"
                + "function performanceTrendSummary(metricName,metric,descriptor,operationsDescriptor,hasHistory){const currentOps=Number(metric.operationCount)||0;if(currentOps===0){return {summary:'" + escapeJs(localized(language, "Henüz gözlem yok.", "No observation yet.")) + "',action:'" + escapeJs(localized(language, "Ölçüm akışı bu tür için henüz örnek toplamadı.", "The collector has not observed samples for this path yet.")) + "'};}if(!hasHistory){return {summary:'" + escapeJs(localized(language, "Trend verisi ısınıyor.", "Trend history is warming up.")) + "',action:'" + escapeJs(localized(language, "Anlık snapshot geldi ama henüz yeterli trend örneği birikmedi. Birkaç yenileme sonra yön daha net görünür.", "The current snapshot is present but not enough trend samples have accumulated yet. The direction will become clearer after a few refreshes.")) + "'};}if(descriptor.direction==='rising'){return {summary:'" + escapeJs(localized(language, "Gecikme yükseliyor.", "Latency is rising.")) + "',action:'" + escapeJs(localized(language, "Ortalama ve p95 birlikte artıyorsa bu yol baskı altında olabilir. İşlem hacmini ve son gözlem saatini birlikte oku.", "If average and p95 rise together, this path may be under pressure. Read operation volume together with the latest sample time.")) + "'};}if(descriptor.direction==='falling'){return {summary:'" + escapeJs(localized(language, "Gecikme gevşiyor.", "Latency is easing.")) + "',action:'" + escapeJs(localized(language, "Yük düşmüş olabilir ya da yol toparlanıyordur. İşlem hacmi hâlâ yüksekse iyileşme daha anlamlıdır.", "Load may have dropped or the path is recovering. Improvement is more meaningful if operation volume remains high.")) + "'};}if(operationsDescriptor.direction==='rising'){return {summary:'" + escapeJs(localized(language, "Hacim artıyor, gecikme dengeli.", "Volume is rising while latency stays stable.")) + "',action:'" + escapeJs(localized(language, "Bu genelde sağlıklı ölçeklenme işaretidir. p95 aniden sıçrarsa alt katmana in.", "This usually indicates healthy scaling. Drill down if p95 suddenly spikes.")) + "'};}return {summary:'" + escapeJs(localized(language, "Gecikme ve hacim dengeli.", "Latency and volume are stable.")) + "',action:'" + escapeJs(localized(language, "Bu yol şu anda sakin görünüyor. Değişim için son birkaç örneği izle.", "This path currently looks calm. Watch the last few samples for change.")) + "'};}"
                + "function renderPerformanceTrendDashboard(perf,perfHistory){const target=document.getElementById('performanceTrendDashboard');if(!target){return;}const data=perf||dashboardDefaults.perf;const items=(perfHistory&&perfHistory.items)||dashboardDefaults.perfHistory.items||[];const hasHistory=items.length>0;const cards=[{prefix:'performanceRedisRead',title:'" + escapeJs(localized(language, "Redis Okuma Trendi", "Redis Read Trend")) + "',metric:data.redisRead||dashboardDefaults.perf.redisRead,avgKey:'redisReadAverageMicros',countKey:'redisReadOperationCount',secondary:'" + escapeJs(localized(language, "anlık ortalama gecikme", "current average latency")) + "'},{prefix:'performanceRedisWrite',title:'" + escapeJs(localized(language, "Redis Yazma Trendi", "Redis Write Trend")) + "',metric:data.redisWrite||dashboardDefaults.perf.redisWrite,avgKey:'redisWriteAverageMicros',countKey:'redisWriteOperationCount',secondary:'" + escapeJs(localized(language, "anlık ortalama gecikme", "current average latency")) + "'},{prefix:'performancePostgresRead',title:'" + escapeJs(localized(language, "PostgreSQL Okuma Trendi", "PostgreSQL Read Trend")) + "',metric:data.postgresRead||dashboardDefaults.perf.postgresRead,avgKey:'postgresReadAverageMicros',countKey:'postgresReadOperationCount',secondary:'" + escapeJs(localized(language, "anlık ortalama gecikme", "current average latency")) + "'},{prefix:'performancePostgresWrite',title:'" + escapeJs(localized(language, "PostgreSQL Yazma Trendi", "PostgreSQL Write Trend")) + "',metric:data.postgresWrite||dashboardDefaults.perf.postgresWrite,avgKey:'postgresWriteAverageMicros',countKey:'postgresWriteOperationCount',secondary:'" + escapeJs(localized(language, "anlık ortalama gecikme", "current average latency")) + "'}];target.innerHTML=cards.map(card=>{const series=performanceHistorySeries(items,card.avgKey,card.countKey);const descriptor=trendDescriptor(series.latency);const operationsDescriptor=trendDescriptor(series.operations);const tone=performanceTrendTone(card.metric,descriptor);const summary=performanceTrendSummary(card.prefix,card.metric,descriptor,operationsDescriptor,hasHistory);return signalWidget({title:card.title,tone:tone,statusLabel:hasHistory?formatTrendDirection(descriptor):'" + escapeJs(localized(language, "Isınıyor", "Warming")) + "',summary:summary.summary,primaryValue:formatLatencyMicros(card.metric.averageMicros),secondaryValue:card.secondary,progress:Math.min(100,descriptor.peak<=0?Math.max(12,(Number(card.metric.operationCount)||0)>0?24:6):Math.max(12,Math.round((descriptor.current/Math.max(1,descriptor.peak))*100))),facts:[{label:'" + escapeJs(localized(language, "İşlem", "Operations")) + "',value:String(card.metric.operationCount||0)},{label:'" + escapeJs(localized(language, "p95", "p95")) + "',value:formatLatencyMicros(card.metric.p95Micros)},{label:'" + escapeJs(localized(language, "Gecikme yönü", "Latency trend")) + "',value:hasHistory?formatTrendDirection(descriptor):'" + escapeJs(localized(language, "Bekleniyor", "Pending")) + "'},{label:'" + escapeJs(localized(language, "Hacim yönü", "Volume trend")) + "',value:hasHistory?formatTrendDirection(operationsDescriptor):'" + escapeJs(localized(language, "Bekleniyor", "Pending")) + "'}],actionText:summary.action});}).join('');}"
                + renderPerformanceScenarioJavaScript(language)
                + "function renderPerformanceSection(perf,perfHistory,shape){const data=perf||dashboardDefaults.perf;const history=perfHistory||dashboardDefaults.perfHistory;const scenarioShape=shape||dashboardDefaults.shape;const redisReadTrend=trendDescriptor(performanceHistorySeries((history.items||[]),'redisReadAverageMicros','redisReadOperationCount').latency);const redisWriteTrend=trendDescriptor(performanceHistorySeries((history.items||[]),'redisWriteAverageMicros','redisWriteOperationCount').latency);const postgresReadTrend=trendDescriptor(performanceHistorySeries((history.items||[]),'postgresReadAverageMicros','postgresReadOperationCount').latency);const postgresWriteTrend=trendDescriptor(performanceHistorySeries((history.items||[]),'postgresWriteAverageMicros','postgresWriteOperationCount').latency);renderPerformanceTrendDashboard(data,history);renderPerformanceScenarioDashboard(data,scenarioShape);renderLatencyCard('performanceRedisRead',data.redisRead||dashboardDefaults.perf.redisRead,redisReadTrend,'" + escapeJs(localized(language, "Henüz Redis okuma ölçümü yok.", "No Redis read measurement yet.")) + "','" + escapeJs(localized(language, "Son Redis okuma örneği:", "Last Redis read sample:")) + "');renderLatencyCard('performanceRedisWrite',data.redisWrite||dashboardDefaults.perf.redisWrite,redisWriteTrend,'" + escapeJs(localized(language, "Henüz Redis yazma ölçümü yok.", "No Redis write measurement yet.")) + "','" + escapeJs(localized(language, "Son Redis yazma örneği:", "Last Redis write sample:")) + "');renderLatencyCard('performancePostgresRead',data.postgresRead||dashboardDefaults.perf.postgresRead,postgresReadTrend,'" + escapeJs(localized(language, "Doğrudan PostgreSQL okuma yolu henüz gözlenmiyor.", "Direct PostgreSQL read path is not currently observed.")) + "','" + escapeJs(localized(language, "Son PostgreSQL okuma örneği:", "Last PostgreSQL read sample:")) + "');renderLatencyCard('performancePostgresWrite',data.postgresWrite||dashboardDefaults.perf.postgresWrite,postgresWriteTrend,'" + escapeJs(localized(language, "Henüz PostgreSQL yazma ölçümü yok.", "No PostgreSQL write measurement yet.")) + "','" + escapeJs(localized(language, "Son PostgreSQL yazma örneği:", "Last PostgreSQL write sample:")) + "');}"
                + "function profileLevel(p){return p==='AGGRESSIVE'?2:(p==='BALANCED'?1:0);}"
                + "function chartGrid(w,h,left,right,top,bottom,max,ticks){let out='';for(let i=0;i<=ticks;i++){const y=top+((h-top-bottom)*(i/ticks));const value=Math.round(max-(max*(i/ticks)));out+='<line x1=\"'+left+'\" y1=\"'+y+'\" x2=\"'+(w-right)+'\" y2=\"'+y+'\" stroke=\"" + chartAxisColor + "\" stroke-opacity=\"0.28\"/>';out+='<text x=\"'+(left-8)+'\" y=\"'+(y+4)+'\" fill=\"" + chartMutedTextColor + "\" font-size=\"10\" text-anchor=\"end\">'+value+'</text>';}return out;}"
                + "function seriesCoords(values,w,h,left,right,top,bottom,max){const span=Math.max(1,values.length-1);return values.map((value,index)=>{const x=left+((w-left-right)*(span===0?0:index/span));const y=top+((h-top-bottom)*(1-((Number(value)||0)/Math.max(1,max))));return {x,y,value:Number(value)||0};});}"
                + "function linePoints(coords){return coords.map(p=>p.x+','+p.y).join(' ');}"
                + "function areaPoints(coords,h,bottom){if(!coords.length){return '';}return coords[0].x+','+(h-bottom)+' '+coords.map(p=>p.x+','+p.y).join(' ')+' '+coords[coords.length-1].x+','+(h-bottom);}"
                + "function renderTrendSvg(id,values,color){const svg=document.getElementById(id);if(!values.length){svg.innerHTML='';return;}const w=320,h=120,left=34,right=10,top=10,bottom=22;const max=Math.max(1,...values.map(v=>Number(v)||0));const coords=seriesCoords(values,w,h,left,right,top,bottom,max);const last=coords[coords.length-1];const sampleLabel=\"" + escapeJs(localized(language, "örnek ", "samples ")) + "\";const lastLabel=\"" + escapeJs(localized(language, "son ", "last ")) + "\";svg.innerHTML='<rect x=\"0\" y=\"0\" width=\"'+w+'\" height=\"'+h+'\" rx=\"12\" fill=\"" + chartBackgroundColor + "\"/>'+chartGrid(w,h,left,right,top,bottom,max,3)+'<polyline fill=\"'+color+'\" fill-opacity=\"0.12\" stroke=\"none\" points=\"'+areaPoints(coords,h,bottom)+'\"/>'+'<polyline fill=\"none\" stroke=\"'+color+'\" stroke-width=\"3\" stroke-linecap=\"round\" stroke-linejoin=\"round\" points=\"'+linePoints(coords)+'\"/>'+'<circle cx=\"'+last.x+'\" cy=\"'+last.y+'\" r=\"4.5\" fill=\"'+color+'\"/>'+'<text x=\"'+left+'\" y=\"'+(h-6)+'\" fill=\"" + chartMutedTextColor + "\" font-size=\"10\">'+sampleLabel+values.length+'</text>'+'<text x=\"'+(w-right)+'\" y=\"'+(h-6)+'\" fill=\"" + chartMutedTextColor + "\" font-size=\"10\" text-anchor=\"end\">'+lastLabel+last.value+'</text>';}"
                + "function renderRouteTrendSvg(id,items,valueKey){const svg=document.getElementById(id);if(!items.length){svg.innerHTML='';return;}const w=480,h=180,left=40,right=12,top=12,bottom=26;const grouped={};items.forEach(v=>{(grouped[v.routeName]=grouped[v.routeName]||[]).push(Number(v[valueKey])||0);});const colors={webhook:'" + routeWebhookColor + "',queue:'" + routeQueueColor + "',smtp:'" + routeSmtpColor + "','delivery-dlq':'" + routeDlqColor + "'};const names=Object.keys(grouped);const max=Math.max(1,...Object.values(grouped).flat());let html='<rect x=\"0\" y=\"0\" width=\"'+w+'\" height=\"'+h+'\" rx=\"12\" fill=\"" + chartBackgroundColor + "\"/>'+chartGrid(w,h,left,right,top,bottom,max,4);names.forEach((name,idx)=>{const coords=seriesCoords(grouped[name],w,h,left,right,top,bottom,max);const last=coords[coords.length-1];const color=colors[name]||'" + routeFallbackColor + "';html+='<polyline fill=\"none\" stroke=\"'+color+'\" stroke-width=\"3\" stroke-linecap=\"round\" stroke-linejoin=\"round\" points=\"'+linePoints(coords)+'\"/>'+'<circle cx=\"'+last.x+'\" cy=\"'+last.y+'\" r=\"4\" fill=\"'+color+'\"/>'+'<text x=\"'+(w-right-4)+'\" y=\"'+(top+14*(idx+1))+'\" fill=\"'+color+'\" font-size=\"11\" text-anchor=\"end\">'+routeNameLabel(name)+' ('+last.value+')</text>';});svg.innerHTML=html;}"
                + "function renderIncidentSeverityTrendSvg(items){const svg=document.getElementById('incidentSeverityTrendSvg');if(!items.length){svg.innerHTML='';return;}const w=640,h=180,left=40,right=12,top=12,bottom=26;const colors={critical:'" + severityCriticalColor + "',warning:'" + severityWarningColor + "',info:'" + severityInfoColor + "'};const labels={critical:'" + escapeJs(localized(language, "kritik", "critical")) + "',warning:'" + escapeJs(localized(language, "uyarı", "warning")) + "',info:'" + escapeJs(localized(language, "bilgi", "info")) + "'};const series={critical:items.map(v=>Number(v.criticalCount)||0),warning:items.map(v=>Number(v.warningCount)||0),info:items.map(v=>Number(v.infoCount)||0)};const max=Math.max(1,...Object.values(series).flat());let html='<rect x=\"0\" y=\"0\" width=\"'+w+'\" height=\"'+h+'\" rx=\"12\" fill=\"" + chartBackgroundColor + "\"/>'+chartGrid(w,h,left,right,top,bottom,max,4);Object.entries(series).forEach(([name,values],idx)=>{const coords=seriesCoords(values,w,h,left,right,top,bottom,max);const last=coords[coords.length-1];html+='<polyline fill=\"none\" stroke=\"'+colors[name]+'\" stroke-width=\"3\" stroke-linecap=\"round\" stroke-linejoin=\"round\" points=\"'+linePoints(coords)+'\"/>'+'<circle cx=\"'+last.x+'\" cy=\"'+last.y+'\" r=\"4\" fill=\"'+colors[name]+'\"/>'+'<text x=\"'+(w-right-4)+'\" y=\"'+(top+14*(idx+1))+'\" fill=\"'+colors[name]+'\" font-size=\"11\" text-anchor=\"end\">'+labels[name]+' ('+last.value+')</text>';});svg.innerHTML=html;}"
                + "let lastExplainPlan=null;"
                + "function isHealthyExplainRelationStatus(status){return status==='RESOLVED'||status==='BATCH_PRELOAD'||status==='NO_MATCHES';}"
                + "function parseFetchDepthExceeded(detail){const match=String(detail||'').match(/depth\\s+(\\d+)\\s+exceeds\\s+configured\\s+maxFetchDepth=(\\d+)/i);if(!match){return null;}return {requested:Number(match[1]||0),allowed:Number(match[2]||0)};}"
                + "function parseFetchLoaderMissing(detail){const match=String(detail||'').match(/registered for\\s+([^\\s]+)/i);if(!match){return null;}return {sourceEntity:match[1]||''};}"
                + "function parseFetchPathUnverified(detail){const text=String(detail||'');let match=text.match(/Source entity binding\\s+([^\\s]+)\\s+is not registered/i);if(match){return {kind:'SOURCE_BINDING_MISSING',sourceEntity:match[1]||''};}match=text.match(/target entity binding\\s+([^\\s]+)\\s+is missing/i);if(match){return {kind:'TARGET_BINDING_MISSING',targetEntity:match[1]||''};}match=text.match(/does not expose mappedBy column\\s+([^\\s]+)/i);if(match){return {kind:'MAPPED_BY_COLUMN_MISSING',mappedBy:match[1]||''};}return {kind:'GENERIC'};}"
                + "function explainRelationStatusLabel(status){const labels={BATCH_PRELOAD:'" + escapeJs(uiString("explain.status.batchPreload", "Batch preload")) + "',RESOLVED:'" + escapeJs(uiString("explain.status.resolved", "Resolved")) + "',NO_MATCHES:'" + escapeJs(uiString("explain.status.noMatches", "No matches")) + "',FETCH_LOADER_MISSING:'" + escapeJs(uiString("explain.status.fetchLoaderMissing", "Fetch loader missing")) + "',FETCH_PATH_UNVERIFIED:'" + escapeJs(uiString("explain.status.fetchPathUnverified", "Fetch path unverified")) + "',FETCH_DEPTH_EXCEEDED:'" + escapeJs(uiString("explain.status.fetchDepthExceeded", "Fetch depth exceeded")) + "',UNKNOWN_RELATION:'" + escapeJs(uiString("explain.status.unknownRelation", "Unknown relation")) + "',TARGET_BINDING_MISSING:'" + escapeJs(uiString("explain.status.targetBindingMissing", "Target binding missing")) + "',MAPPED_BY_UNRESOLVED:'" + escapeJs(uiString("explain.status.mappedByUnresolved", "mappedBy unresolved")) + "'};return labels[String(status||'').trim()]||String(status||'-');}"
                + "function explainRelationUsageLabel(usage){const labels={FILTER:'" + escapeJs(uiString("explain.usage.filter", "Filter")) + "',FETCH:'" + escapeJs(uiString("explain.usage.fetch", "Fetch")) + "'};return labels[String(usage||'').trim()]||String(usage||'-');}"
                + "function explainRelationDetailText(item){if(!item){return '-';}if(item.status==='FETCH_DEPTH_EXCEEDED'){const parsed=parseFetchDepthExceeded(item.detail);if(parsed){return '" + escapeJs(uiString("explain.fetchDepthExceededDetailTemplate", "Requested fetch path exceeds the allowed relation depth. Allowed: {allowed}, requested: {requested}.")) + "'.replace('{allowed}',String(parsed.allowed)).replace('{requested}',String(parsed.requested));}}if(item.status==='FETCH_LOADER_MISSING'){return '" + escapeJs(uiString("explain.fetchLoaderMissingDetailTemplate", "This fetch path was requested, but no concrete relation loader is registered for the source entity. The relation cannot be preloaded until a RelationBatchLoader is bound.")) + "'.replace('{relation}',String(item.relationName||'-'));}if(item.status==='FETCH_PATH_UNVERIFIED'){const parsed=parseFetchPathUnverified(item.detail);if(parsed.kind==='SOURCE_BINDING_MISSING'){return '" + escapeJs(uiString("explain.fetchPathUnverifiedSourceBindingTemplate", "The fetch path is declared, but the source entity binding is not registered. Source: {source}. The path cannot be verified.")) + "'.replace('{source}',String(parsed.sourceEntity||'-'));}if(parsed.kind==='TARGET_BINDING_MISSING'){return '" + escapeJs(uiString("explain.fetchPathUnverifiedTargetBindingTemplate", "A relation loader exists, but the target entity binding is missing. Target: {target}. mappedBy validation could not be completed.")) + "'.replace('{target}',String(parsed.targetEntity||item.targetEntity||'-'));}if(parsed.kind==='MAPPED_BY_COLUMN_MISSING'){return '" + escapeJs(uiString("explain.fetchPathUnverifiedMappedByTemplate", "A relation loader exists, but target metadata does not expose the mappedBy column. mappedBy: {mappedBy}. The fetch path could not be verified.")) + "'.replace('{mappedBy}',String(parsed.mappedBy||item.mappedBy||'-'));}return '" + escapeJs(uiString("explain.fetchPathUnverifiedGenericTemplate", "The fetch path is declared, but its binding could not be fully verified. Check source binding, target binding, and mappedBy configuration.")) + "';}return item.detail||'-';}"
                + "function explainRelationGuidance(item){if(!item){return null;}if(item.status==='FETCH_DEPTH_EXCEEDED'){const parsed=parseFetchDepthExceeded(item.detail)||{allowed:'-',requested:'-'};return {firstFix:'" + escapeJs(uiString("explain.guidance.fetchDepthExceeded.firstFix", "Shorten the requested include path or raise maxFetchDepth if the extra depth is intentional.")) + "',checkArea:'" + escapeJs(uiString("explain.guidance.fetchDepthExceeded.checkArea", "Check RelationConfig.maxFetchDepth and the include paths passed into the query.")) + "',likelyCause:'" + escapeJs(uiString("explain.guidance.fetchDepthExceeded.likelyCauseTemplate", "A nested fetch path is deeper than the configured safety limit. Allowed: {allowed}, requested: {requested}.")) + "'.replace('{allowed}',String(parsed.allowed)).replace('{requested}',String(parsed.requested))};}if(item.status==='FETCH_LOADER_MISSING'){const parsed=parseFetchLoaderMissing(item.detail)||{sourceEntity:'-'};return {firstFix:'" + escapeJs(uiString("explain.guidance.fetchLoaderMissing.firstFix", "Register a concrete RelationBatchLoader for the source entity before using this fetch path.")) + "',checkArea:'" + escapeJs(uiString("explain.guidance.fetchLoaderMissing.checkAreaTemplate", "Check bootstrap/registry binding for source entity: {source}.")) + "'.replace('{source}',String(parsed.sourceEntity||'-')),likelyCause:'" + escapeJs(uiString("explain.guidance.fetchLoaderMissing.likelyCause", "The relation is declared in metadata, but the source binding is still using a no-op or missing relation loader.")) + "'};}if(item.status==='FETCH_PATH_UNVERIFIED'){const parsed=parseFetchPathUnverified(item.detail);if(parsed.kind==='SOURCE_BINDING_MISSING'){return {firstFix:'" + escapeJs(uiString("explain.guidance.fetchPathUnverified.source.firstFix", "Register the source entity binding before requesting this fetch path.")) + "',checkArea:'" + escapeJs(uiString("explain.guidance.fetchPathUnverified.source.checkAreaTemplate", "Check source entity registration order and bootstrap wiring for: {source}.")) + "'.replace('{source}',String(parsed.sourceEntity||'-')),likelyCause:'" + escapeJs(uiString("explain.guidance.fetchPathUnverified.source.likelyCause", "Metadata exists, but the source entity was not fully registered when the fetch path was analyzed.")) + "'};}if(parsed.kind==='TARGET_BINDING_MISSING'){return {firstFix:'" + escapeJs(uiString("explain.guidance.fetchPathUnverified.target.firstFix", "Register the target entity binding so the relation path can be validated end-to-end.")) + "',checkArea:'" + escapeJs(uiString("explain.guidance.fetchPathUnverified.target.checkAreaTemplate", "Check target entity registration and mapping for: {target}.")) + "'.replace('{target}',String(parsed.targetEntity||item.targetEntity||'-')),likelyCause:'" + escapeJs(uiString("explain.guidance.fetchPathUnverified.target.likelyCause", "A loader exists, but the target entity binding is absent so mappedBy validation cannot complete.")) + "'};}if(parsed.kind==='MAPPED_BY_COLUMN_MISSING'){return {firstFix:'" + escapeJs(uiString("explain.guidance.fetchPathUnverified.mappedBy.firstFix", "Align the relation mappedBy value with a real column exposed by the target entity metadata.")) + "',checkArea:'" + escapeJs(uiString("explain.guidance.fetchPathUnverified.mappedBy.checkAreaTemplate", "Check target metadata and relation mappedBy configuration for column: {mappedBy}.")) + "'.replace('{mappedBy}',String(parsed.mappedBy||item.mappedBy||'-')),likelyCause:'" + escapeJs(uiString("explain.guidance.fetchPathUnverified.mappedBy.likelyCause", "The relation points to a mappedBy field that the target entity metadata does not expose.")) + "'};}return {firstFix:'" + escapeJs(uiString("explain.guidance.fetchPathUnverified.generic.firstFix", "Verify source binding, target binding, and mappedBy configuration together.")) + "',checkArea:'" + escapeJs(uiString("explain.guidance.fetchPathUnverified.generic.checkArea", "Inspect relation bootstrap order and entity metadata on both sides of the relation.")) + "',likelyCause:'" + escapeJs(uiString("explain.guidance.fetchPathUnverified.generic.likelyCause", "The relation is declared, but the runtime could not prove that the fetch path is safely wired.")) + "'};}return null;}"
                + "function explainGuidanceRow(label,value){return '<div class=\"small mt-1\"><span class=\"fw-semibold\">'+escapeText(label)+': </span><span class=\"text-muted\">'+escapeText(value)+'</span></div>';}"
                + "function explainRelationDetailCell(item){const detail=explainRelationDetailText(item);const guidance=explainRelationGuidance(item);let html='<div class=\"d-flex flex-column gap-1\"><div>'+titled(detail,0,false)+'</div>';if(guidance){html+='<div class=\"rounded-3 border bg-light-subtle p-2 mt-1\">'+explainGuidanceRow('" + escapeJs(uiString("explain.guidance.firstFixLabel", "First fix")) + "',guidance.firstFix)+explainGuidanceRow('" + escapeJs(uiString("explain.guidance.checkAreaLabel", "Check here")) + "',guidance.checkArea)+explainGuidanceRow('" + escapeJs(uiString("explain.guidance.likelyCauseLabel", "Likely root cause")) + "',guidance.likelyCause)+'</div>';}html+='</div>';return html;}"
                + "function explainRelationStatusCell(item){const tone=isHealthyExplainRelationStatus(item.status)?'UP':'DEGRADED';return '<div class=\"d-flex flex-column gap-1\">'+badge(tone)+'<span class=\"small text-muted\">'+escapeText(explainRelationStatusLabel(item.status))+'</span></div>';}"
                + "function summarizeExplainTriage(plan){const warnings=plan.warnings||[];const steps=plan.steps||[];const relationStates=plan.relationStates||[];const suspicious=steps.slice().sort((a,b)=>(Number(b.estimatedCost)||0)-(Number(a.estimatedCost)||0))[0]||null;let whySlow='" + escapeJs(uiString("explain.triage.whySlowDefault", "No clear bottleneck")) + "';let whySlowDetail='" + escapeJs(uiString("explain.triage.whySlowDefaultDetail", "Estimated cost is low or evenly distributed.")) + "';if((Number(plan.estimatedCost)||0)>=100 || (suspicious && Number(suspicious.estimatedCost||0)>=50)){whySlow=suspicious?clip(suspicious.stage+' / '+suspicious.strategy,42):'" + escapeJs(uiString("explain.triage.highCostLabel", "High estimated cost")) + "';whySlowDetail=suspicious?clip(suspicious.detail||suspicious.expression||'',110):'" + escapeJs(uiString("explain.triage.highCostDetail", "Planner estimated a relatively expensive path.")) + "';}else if((Number(plan.candidateCount)||0)>100){whySlow='" + escapeJs(uiString("explain.triage.largeCandidateSetLabel", "Large candidate set")) + "';whySlowDetail='" + escapeJs(uiString("explain.triage.largeCandidateSetDetail", "A broad candidate set reached later stages, which can increase in-memory work.")) + "';}let whyDegraded='" + escapeJs(uiString("explain.triage.whyDegradedDefault", "No degradation detected")) + "';let whyDegradedDetail='" + escapeJs(uiString("explain.triage.whyDegradedDefaultDetail", "Planner stayed on indexed or healthy relation paths.")) + "';const degradedRelation=relationStates.find(v=>!isHealthyExplainRelationStatus(v.status));if(plan.plannerStrategy==='DEGRADED_FULL_SCAN'){whyDegraded='" + escapeJs(uiString("explain.triage.degradedFullScanLabel", "Full scan fallback")) + "';whyDegradedDetail='" + escapeJs(uiString("explain.triage.degradedFullScanDetail", "Query indexes were bypassed or shed, so the repository scanned entity keys and applied residual filters.")) + "';}else if(degradedRelation&&degradedRelation.status==='FETCH_DEPTH_EXCEEDED'){const parsed=parseFetchDepthExceeded(degradedRelation.detail);whyDegraded=clip((degradedRelation.relationName||'-')+' / '+'" + escapeJs(uiString("explain.status.fetchDepthExceeded", "Fetch depth exceeded")) + "',42);whyDegradedDetail=parsed?'" + escapeJs(uiString("explain.fetchDepthExceededDetailTemplate", "Requested fetch path exceeds the allowed relation depth. Allowed: {allowed}, requested: {requested}.")) + "'.replace('{allowed}',String(parsed.allowed)).replace('{requested}',String(parsed.requested)):clip(degradedRelation.detail||'',110);}else if(degradedRelation){whyDegraded=clip((degradedRelation.relationName||'-')+' / '+explainRelationStatusLabel(degradedRelation.status),42);whyDegradedDetail=clip(explainRelationDetailText(degradedRelation),110);}else if(warnings.length){whyDegraded='" + escapeJs(uiString("explain.triage.warningDrivenLabel", "Warnings present")) + "';whyDegradedDetail=clip(warnings[0],110);}const suspiciousTitle=suspicious?clip(suspicious.stage+' / '+suspicious.strategy,42):'" + escapeJs(uiString("explain.triage.noSuspiciousStepLabel", "No suspicious step")) + "';const suspiciousDetail=suspicious?clip((suspicious.detail||suspicious.expression||'')+' (cost='+(suspicious.estimatedCost||0)+')',110):'" + escapeJs(uiString("explain.triage.noSuspiciousStepDetail", "No explain step stood out as unusually expensive.")) + "';return {whySlow,whySlowDetail,whyDegraded,whyDegradedDetail,suspiciousTitle,suspiciousDetail};}"
                + "function renderExplainRelations(plan){const usage=document.getElementById('explainRelationUsageFilter').value||'ALL';const warningsOnly=document.getElementById('explainRelationWarningsOnly').checked;let items=(plan.relationStates||[]);if(usage!=='ALL'){items=items.filter(v=>v.usage===usage);}if(warningsOnly){items=items.filter(v=>!isHealthyExplainRelationStatus(v.status));}rows('explainRelationRows',items.map(v=>['<span class=\"mono\">'+escapeText(v.relationName||'-')+'</span>',explainRelationUsageLabel(v.usage),explainRelationStatusCell(v),'<span class=\"mono\">'+escapeText(v.kind||'-')+'</span>','<span class=\"mono\">'+escapeText(v.targetEntity||'-')+'</span>','<span class=\"mono\">'+escapeText(clip(v.mappedBy||'',24))+'</span>',String(v.candidateCount||0),explainRelationDetailCell(v)]),'" + escapeJs(uiString("empty.explainRelationStates", "No relation states")) + "',8);}"
                + "function renderExplainSteps(plan){rows('explainStepRows',(plan.steps||[]).map(v=>[v.stage,'<span class=\"mono\">'+v.strategy+'</span>',badge(v.indexed?'UP':'DEGRADED'),String(v.candidateCount||0),String(v.estimatedCost||0),clip(v.expression,48),clip(v.detail,88)]),'" + escapeJs(uiString("empty.explainSteps", "No explain steps")) + "',7);}"
                + "function renderExplainResult(plan){lastExplainPlan=plan;document.getElementById('explainPlanner').textContent=plan.plannerStrategy||'-';document.getElementById('explainSort').textContent=plan.sortStrategy||'-';document.getElementById('explainCandidates').textContent=String(plan.candidateCount||0);document.getElementById('explainWarningCount').textContent=String((plan.warnings||[]).length);document.getElementById('explainEstimatedCost').textContent=String(plan.estimatedCost||0);document.getElementById('explainFullyIndexed').innerHTML=badge(plan.fullyIndexed?'UP':'DEGRADED');document.getElementById('explainStepCount').textContent=String((plan.steps||[]).length);const triage=summarizeExplainTriage(plan);document.getElementById('explainWhySlow').textContent=triage.whySlow;document.getElementById('explainWhySlowDetail').textContent=triage.whySlowDetail;document.getElementById('explainWhyDegraded').textContent=triage.whyDegraded;document.getElementById('explainWhyDegradedDetail').textContent=triage.whyDegradedDetail;document.getElementById('explainSuspiciousStep').textContent=triage.suspiciousTitle;document.getElementById('explainSuspiciousStepDetail').textContent=triage.suspiciousDetail;rows('explainWarningRows',(plan.warnings||[]).map(v=>['<span class=\"mono\">'+v+'</span>']),'" + escapeJs(uiString("empty.explainWarnings", "No explain warnings")) + "',1);renderExplainRelations(plan);renderExplainSteps(plan);document.getElementById('explain').textContent=JSON.stringify(plan,null,2);}"
                + "function renderFailingSignals(items){const host=document.getElementById('failingSignalCards');if(!items.length){host.innerHTML='<div class=\"col-12 text-muted\">" + escapeJs(uiString("empty.highSignalFailures", "No high-signal failures detected.")) + "</div>';return;}host.innerHTML=items.slice(0,6).map(v=>'<div class=\"col-12 col-md-6\"><div class=\"card border-0 bg-light h-100\"><div class=\"card-body\"><div class=\"d-flex justify-content-between align-items-start gap-2\"><div class=\"fw-semibold\">'+v.signalCode+'</div>'+badge(v.severity==='CRITICAL'?'DOWN':(v.severity==='WARNING'?'DEGRADED':'UP'))+'</div><div class=\"small text-muted mt-2\">" + escapeJs(uiString("failingSignals.activeRecentPrefix", "Açık ")) + "'+v.activeCount+'" + escapeJs(uiString("failingSignals.activeRecentSeparator", " / Son dönemde ")) + "'+v.recentCount+'</div><div class=\"small mt-2\">'+clip(v.summary,92)+'</div><div class=\"small text-muted mt-2\">" + escapeJs(uiString("failingSignals.lastSeenPrefix", "Son görülme ")) + "'+new Date(v.lastSeenAt).toLocaleString()+'</div></div></div></div>').join('');}"
                + "function renderChurnSvg(items){const svg=document.getElementById('churnSvg');if(!items.length){svg.innerHTML='';return;}const ordered=[...items].reverse();const w=960,h=180,left=60,right=24,top=20,bottom=34;const span=Math.max(1,ordered.length-1);const coords=ordered.map((item,index)=>{const x=left+((w-left-right)*(span===0?0:index/span));const y=top+((h-top-bottom)*(2-profileLevel(item.toProfile))/2);return {x,y,item};});let html='<rect x=\"0\" y=\"0\" width=\"'+w+'\" height=\"'+h+'\" rx=\"12\" fill=\"" + chartBackgroundColor + "\"/>';['" + profileAggressiveLabel + "','" + profileBalancedLabel + "','" + profileStandardLabel + "'].forEach((label,index)=>{const y=top+((h-top-bottom)*(index/2));html+='<line x1=\"'+left+'\" y1=\"'+y+'\" x2=\"'+(w-right)+'\" y2=\"'+y+'\" stroke=\"" + chartAxisColor + "\" stroke-opacity=\"0.28\"/>'+'<text x=\"'+(left-10)+'\" y=\"'+(y+4)+'\" fill=\"" + churnAxisTextColor + "\" font-size=\"11\" text-anchor=\"end\">'+label+'</text>';});html+='<polyline fill=\"none\" stroke=\"" + churnLineColor + "\" stroke-width=\"3\" stroke-linecap=\"round\" stroke-linejoin=\"round\" points=\"'+coords.map(p=>p.x+','+p.y).join(' ')+'\"/>';html+=coords.map(p=>'<circle cx=\"'+p.x+'\" cy=\"'+p.y+'\" r=\"4.2\" fill=\"" + churnDotColor + "\"><title>'+p.item.recordedAt+' '+p.item.fromProfile+'->'+p.item.toProfile+' '+p.item.pressureLevel+'</title></circle>').join('');svg.innerHTML=html;}"
                + "function average(values){if(!values.length){return 0;}return values.reduce((sum,value)=>sum+(Number(value)||0),0)/values.length;}"
                + "function recentBaseline(values){if(values.length<=1){return Number(values[0])||0;}const recent=values.slice(Math.max(0,values.length-4),values.length-1);return recent.length?average(recent):(Number(values[values.length-2])||0);}"
                + "function trendDescriptor(values){if(!values.length){return {current:0,previous:0,baseline:0,peak:0,delta:0,deltaPercent:0,direction:'flat'};}const current=Number(values[values.length-1])||0;const previous=values.length>1?(Number(values[values.length-2])||0):current;const baseline=recentBaseline(values);const peak=Math.max(0,...values.map(v=>Number(v)||0));const delta=current-previous;const denominator=Math.max(1,Math.abs(previous||baseline||1));const deltaPercent=Math.round((delta/denominator)*100);let direction='flat';if(deltaPercent>=10 || delta>=Math.max(3,baseline*0.1)){direction='rising';}else if(deltaPercent<=-10 || delta<=-Math.max(3,baseline*0.1)){direction='falling';}return {current,previous,baseline,peak,delta,deltaPercent,direction};}"
                + "function formatTrendDirection(descriptor){if(descriptor.direction==='rising'){return '" + escapeJs(localized(language, "Yükseliyor", "Rising")) + "';}if(descriptor.direction==='falling'){return '" + escapeJs(localized(language, "Düşüyor", "Falling")) + "';}return '" + escapeJs(localized(language, "Dengeli", "Stable")) + "';}"
                + "function formatTrendDelta(descriptor,unit){const abs=Math.abs(descriptor.delta);const prefix=descriptor.delta>0?'+':(descriptor.delta<0?'-':'±');if(unit==='bytes'){return prefix+formatBytes(abs);}return prefix+String(abs);}"
                + "function trendTone(kind,descriptor){if(kind==='deadLetter'){if(descriptor.current>0 && descriptor.direction==='rising'){return 'danger';}if(descriptor.current>0){return 'warning';}return 'success';}if(kind==='backlog'){if(descriptor.current>=500 || (descriptor.current>0 && descriptor.direction==='rising' && descriptor.deltaPercent>=20)){return 'danger';}if(descriptor.current>=100 || descriptor.direction==='rising'){return 'warning';}return descriptor.current===0?'success':'neutral';}if(kind==='memory'){if(descriptor.direction==='rising' && descriptor.deltaPercent>=15){return 'warning';}if(descriptor.direction==='falling'){return 'success';}return 'neutral';}return 'neutral';}"
                + "function trendSummary(kind,descriptor){"
                + "if(kind==='backlog'){if(descriptor.current===0){return {summary:'" + escapeJs(localized(language, "Kuyruk şu anda temiz görünüyor.", "The queue is currently clear.")) + "',action:'" + escapeJs(localized(language, "Yazma hattı sakin. Grafik üzerinde yeni bir yükseliş başlayıp başlamadığına bak.", "The writer path is calm. Watch the graph for a new rise.")) + "'};}if(descriptor.direction==='rising'){return {summary:'" + escapeJs(localized(language, "Yazma kuyruğu büyüyor.", "The writer queue is growing.")) + "',action:'" + escapeJs(localized(language, "Arka plan yazma, başarısız kayıt kuyruğu ve işleyici hatalarını birlikte kontrol et.", "Check write-behind, dead-letter, and worker errors together.")) + "'};}return {summary:'" + escapeJs(localized(language, "Kuyruk var ama aşağı doğru geliyor.", "There is backlog, but it is trending down.")) + "',action:'" + escapeJs(localized(language, "Birikim boşalıyor olabilir. Yeni bir sıçrama başlayıp başlamadığına bak.", "Drain may be completing. Watch for a new spike.")) + "'};}"
                + "if(kind==='memory'){if(descriptor.direction==='rising'){return {summary:'" + escapeJs(localized(language, "Redis belleği yukarı gidiyor.", "Redis memory is trending upward.")) + "',action:'" + escapeJs(localized(language, "Kuyruk eşlik etmiyorsa bu daha çok önbellek büyümesine işaret eder.", "If backlog is not rising too, this points more to cache growth.")) + "'};}if(descriptor.direction==='falling'){return {summary:'" + escapeJs(localized(language, "Bellek kullanımı gevşiyor.", "Memory usage is easing.")) + "',action:'" + escapeJs(localized(language, "Yük düştüyse bu normaldir. Ani yeni sıçramalara karşı trendi izle.", "This is normal if load dropped. Keep watching for fresh spikes.")) + "'};}return {summary:'" + escapeJs(localized(language, "Bellek seviyesi dengeli.", "Memory level is stable.")) + "',action:'" + escapeJs(localized(language, "Kapasite baskısı yoksa mevcut seviye kabul edilebilir.", "If capacity pressure is low, the current level is acceptable.")) + "'};}"
                + "if(descriptor.current===0){return {summary:'" + escapeJs(localized(language, "Başarısız iş kuyruğu boş.", "The failed-work queue is empty.")) + "',action:'" + escapeJs(localized(language, "Bu iyi durumdur. Yeni hata birikimi başlayıp başlamadığını izle.", "This is healthy. Watch for any new accumulation.")) + "'};}"
                + "if(descriptor.direction==='rising'){return {summary:'" + escapeJs(localized(language, "Dead-letter birikiyor.", "Dead-letter is accumulating.")) + "',action:'" + escapeJs(localized(language, "Yazma hatası ve PostgreSQL tarafını hemen kontrol et.", "Check writer errors and PostgreSQL immediately.")) + "'};}"
                + "return {summary:'" + escapeJs(localized(language, "Dead-letter var ama büyümüyor.", "There is dead-letter, but it is not growing.")) + "',action:'" + escapeJs(localized(language, "Replay veya temizleme akışının ilerleyip ilerlemediğine bak.", "Check whether replay or cleanup is moving forward.")) + "'};}"
                + "function renderLiveTrendDashboard(history){const target=document.getElementById('liveTrendDashboardGrid');if(!target){return;}const items=history.items||[];if(!items.length){target.innerHTML='<div class=\"col-12 text-muted\">" + escapeJs(localized(language, "Henüz trend örneği yok.", "No trend samples yet.")) + "</div>';return;}const backlog=trendDescriptor(items.map(v=>v.writeBehindBacklog));const memory=trendDescriptor(items.map(v=>v.redisUsedMemoryBytes));const deadLetter=trendDescriptor(items.map(v=>v.deadLetterBacklog));const backlogSummary=trendSummary('backlog',backlog);const memorySummary=trendSummary('memory',memory);const deadLetterSummary=trendSummary('deadLetter',deadLetter);target.innerHTML=[signalWidget({title:'" + escapeJs(localized(language, "Yazma Kuyruğu Trendi", "Writer Queue Trend")) + "',summary:backlogSummary.summary,statusLabel:formatTrendDirection(backlog),tone:trendTone('backlog',backlog),primaryValue:String(backlog.current),secondaryValue:'" + escapeJs(localized(language, "son değer", "latest value")) + "',progress:Math.min(100,backlog.current===0?6:Math.max(12,Math.round((backlog.current/Math.max(1,backlog.peak||backlog.current))*100))),facts:[{label:'" + escapeJs(localized(language, "Değişim", "Change")) + "',value:formatTrendDelta(backlog,'count')},{label:'" + escapeJs(localized(language, "Zirve", "Peak")) + "',value:String(backlog.peak)},{label:'" + escapeJs(localized(language, "Son yön", "Direction")) + "',value:formatTrendDirection(backlog)},{label:'" + escapeJs(localized(language, "Önceki ort.", "Recent avg")) + "',value:String(Math.round(backlog.baseline))}],actionText:backlogSummary.action}),signalWidget({title:'" + escapeJs(localized(language, "Redis Bellek Trendi", "Redis Memory Trend")) + "',summary:memorySummary.summary,statusLabel:formatTrendDirection(memory),tone:trendTone('memory',memory),primaryValue:formatBytes(memory.current),secondaryValue:'" + escapeJs(localized(language, "son değer", "latest value")) + "',progress:Math.min(100,memory.current===0?6:Math.max(12,Math.round((memory.current/Math.max(1,memory.peak||memory.current))*100))),facts:[{label:'" + escapeJs(localized(language, "Değişim", "Change")) + "',value:formatTrendDelta(memory,'bytes')},{label:'" + escapeJs(localized(language, "Zirve", "Peak")) + "',value:formatBytes(memory.peak)},{label:'" + escapeJs(localized(language, "Son yön", "Direction")) + "',value:formatTrendDirection(memory)},{label:'" + escapeJs(localized(language, "Önceki ort.", "Recent avg")) + "',value:formatBytes(Math.round(memory.baseline))}],actionText:memorySummary.action}),signalWidget({title:'" + escapeJs(localized(language, "Dead-letter Trendi", "Dead-letter Trend")) + "',summary:deadLetterSummary.summary,statusLabel:formatTrendDirection(deadLetter),tone:trendTone('deadLetter',deadLetter),primaryValue:String(deadLetter.current),secondaryValue:'" + escapeJs(localized(language, "son değer", "latest value")) + "',progress:Math.min(100,deadLetter.current===0?6:Math.max(12,Math.round((deadLetter.current/Math.max(1,deadLetter.peak||deadLetter.current))*100))),facts:[{label:'" + escapeJs(localized(language, "Değişim", "Change")) + "',value:formatTrendDelta(deadLetter,'count')},{label:'" + escapeJs(localized(language, "Zirve", "Peak")) + "',value:String(deadLetter.peak)},{label:'" + escapeJs(localized(language, "Son yön", "Direction")) + "',value:formatTrendDirection(deadLetter)},{label:'" + escapeJs(localized(language, "Önceki ort.", "Recent avg")) + "',value:String(Math.round(deadLetter.baseline))}],actionText:deadLetterSummary.action})].join('');}"
                + "function renderHistory(history){const items=history.items||[];renderLiveTrendDashboard(history);renderTrendSvg('backlogTrendSvg',items.map(v=>v.writeBehindBacklog),'" + trendBacklogColor + "');renderTrendSvg('memoryTrendSvg',items.map(v=>v.redisUsedMemoryBytes),'" + trendMemoryColor + "');renderTrendSvg('deadLetterTrendSvg',items.map(v=>v.deadLetterBacklog),'" + trendDeadLetterColor + "');}"
                + "function formatBytes(value){const number=Number(value)||0;const units=['B','KB','MB','GB','TB'];let scaled=number;let unitIndex=0;while(scaled>=1024 && unitIndex<units.length-1){scaled/=1024;unitIndex++;}const fixed=scaled>=100?scaled.toFixed(0):scaled>=10?scaled.toFixed(1):scaled.toFixed(2);return fixed+' '+units[unitIndex];}"
                + "function setMetricValue(id,value,unit){const element=document.getElementById(id);if(!element){return;}element.classList.remove('metric-placeholder','metric-error');if(unit==='bytes'){element.textContent=formatBytes(value);}else{element.textContent=String(value);}element.title=unit==='bytes'?(String(value)+' bytes'):String(value);}"
                + "function setMetricText(id,value){const element=document.getElementById(id);if(!element){return;}element.classList.remove('metric-placeholder','metric-error');element.textContent=value;element.title=String(value);}"
                + "function clampPercent(value){const number=Number(value)||0;return Math.max(0,Math.min(100,Math.round(number)));}"
                + "function signalBadge(label,tone){return '<span class=\"signal-badge\" data-tone=\"'+tone+'\">'+escapeText(label)+'</span>';}"
                + "function signalFact(label,value){return '<div class=\"signal-fact\"><div class=\"signal-fact-label\">'+escapeText(label)+'</div><div class=\"signal-fact-value\">'+escapeText(value)+'</div></div>';}"
                + "function signalWidget(config){return '<ops-signal-widget class=\"signal-widget\" data-tone=\"'+config.tone+'\">'+'<div class=\"signal-head\"><div><div class=\"signal-title\">'+escapeText(config.title)+'</div><div class=\"signal-summary\">'+escapeText(config.summary)+'</div></div>'+signalBadge(config.statusLabel,config.tone)+'</div>'+'<div class=\"signal-main\"><div><div class=\"signal-value\">'+escapeText(config.primaryValue)+'</div><div class=\"signal-caption\">'+escapeText(config.secondaryValue)+'</div></div></div>'+'<div class=\"signal-progress\"><div class=\"signal-progress-bar\" style=\"width:'+clampPercent(config.progress)+'%\"></div></div>'+'<div class=\"signal-facts\">'+config.facts.map(f=>signalFact(f.label,f.value)).join('')+'</div>'+'<div class=\"signal-action\">'+escapeText(config.actionText)+'</div>'+'</ops-signal-widget>';}"
                + "function runtimeProfilePropertyMeta(property,fallbackDescription){const map={"
                + "'hotEntityLimitFactor':{label:'" + escapeJs(localized(language, "Sıcak kayıt sınırı katsayısı", "Hot entity limit factor")) + "',description:'" + escapeJs(localized(language, "Aktif çalışma profilinde sıcak kayıtlara ayrılan önbellek sınırını çarpan olarak etkiler.", "Multiplier applied to hot-entity cache limits under the active runtime profile.")) + "'},"
                + "'pageSizeFactor':{label:'" + escapeJs(localized(language, "Sayfa boyutu katsayısı", "Page size factor")) + "',description:'" + escapeJs(localized(language, "Aktif çalışma profilinde sayfalama boyutunu küçültür veya büyütür.", "Multiplier that scales page size under the active runtime profile.")) + "'},"
                + "'entityTtlCapSeconds':{label:'" + escapeJs(localized(language, "Kayıt TTL üst sınırı", "Entity TTL cap")) + "',description:'" + escapeJs(localized(language, "Bu profil çalışırken tekil kayıtların önbellekte tutulabileceği en yüksek süreyi sınırlar.", "Upper cache lifetime allowed for single entity entries while this profile is active.")) + "'},"
                + "'pageTtlCapSeconds':{label:'" + escapeJs(localized(language, "Sayfa TTL üst sınırı", "Page TTL cap")) + "',description:'" + escapeJs(localized(language, "Bu profil çalışırken sayfa sonuçlarının önbellekte tutulabileceği en yüksek süreyi sınırlar.", "Upper cache lifetime allowed for page results while this profile is active.")) + "'},"
                + "'highSleepMillisBonus':{label:'" + escapeJs(localized(language, "Uyarı seviyesi ek bekleme", "Warn-level extra delay")) + "',description:'" + escapeJs(localized(language, "Baskı uyarı seviyesine çıktığında üretici tarafına eklenen ilave bekleme süresidir.", "Extra producer delay added when pressure reaches the warning level.")) + "'},"
                + "'criticalSleepMillisBonus':{label:'" + escapeJs(localized(language, "Kritik seviye ek bekleme", "Critical-level extra delay")) + "',description:'" + escapeJs(localized(language, "Baskı kritik seviyeye çıktığında üretici tarafına eklenen ilave bekleme süresidir.", "Extra producer delay added when pressure reaches the critical level.")) + "'},"
                + "'effectiveHighSleepMillis':{label:'" + escapeJs(localized(language, "Etkin uyarı bekleme süresi", "Effective warn delay")) + "',description:'" + escapeJs(localized(language, "Şu anda uyarı baskısı altında gerçekten uygulanan toplam bekleme süresidir.", "The total delay currently applied under warning pressure.")) + "'},"
                + "'effectiveCriticalSleepMillis':{label:'" + escapeJs(localized(language, "Etkin kritik bekleme süresi", "Effective critical delay")) + "',description:'" + escapeJs(localized(language, "Şu anda kritik baskı altında gerçekten uygulanan toplam bekleme süresidir.", "The total delay currently applied under critical pressure.")) + "'},"
                + "'automaticSwitchingEnabled':{label:'" + escapeJs(localized(language, "Otomatik profil geçişi", "Automatic profile switching")) + "',description:'" + escapeJs(localized(language, "Sistem baskı arttığında çalışma profilini kendiliğinden değiştirebilir mi sorusunun cevabıdır.", "Shows whether the runtime profile is allowed to switch automatically under pressure.")) + "'}"
                + "};return map[property]||{label:property,description:fallbackDescription||''};}"
                + "function runtimeProfileUnitLabel(unit){const labels={ratio:'" + escapeJs(localized(language, "oran", "ratio")) + "',seconds:'" + escapeJs(localized(language, "saniye", "seconds")) + "',milliseconds:'" + escapeJs(localized(language, "milisaniye", "milliseconds")) + "',flag:'" + escapeJs(localized(language, "durum", "flag")) + "'};return labels[unit]||unit||'-';}"
                + "function runtimeProfileName(value){const labels={AUTO:'" + escapeJs(localized(language, "Otomatik", "Auto")) + "',MANUAL:'" + escapeJs(localized(language, "Elle", "Manual")) + "',STANDARD:'" + escapeJs(localized(language, "Standart", "Standard")) + "',BALANCED:'" + escapeJs(localized(language, "Dengeli", "Balanced")) + "',AGGRESSIVE:'" + escapeJs(localized(language, "Agresif", "Aggressive")) + "'};return labels[value]||value||'-';}"
                + "function pressureLabel(value){const labels={NORMAL:'" + escapeJs(localized(language, "Normal", "Normal")) + "',WARN:'" + escapeJs(localized(language, "Uyarı", "Warn")) + "',HIGH:'" + escapeJs(localized(language, "Yüksek", "High")) + "',CRITICAL:'" + escapeJs(localized(language, "Kritik", "Critical")) + "'};return labels[value]||value||'-';}"
                + "function runtimeProfilePropertyCell(item){const meta=runtimeProfilePropertyMeta(item.property,item.description);return '<div class=\"d-flex flex-column gap-1\"><span class=\"fw-semibold\">'+escapeText(meta.label)+'</span><span class=\"mono text-muted small\">'+escapeText(item.property)+'</span></div>';}"
                + "function runtimeProfileDescriptionCell(item){const meta=runtimeProfilePropertyMeta(item.property,item.description);return titled(meta.description,0,false);}"
                + "function tuningGroupLabel(group){const labels={'admin-http':'" + escapeJs(localized(language, "Yönetim HTTP", "Admin HTTP")) + "','admin-monitoring':'" + escapeJs(localized(language, "Yönetim İzleme", "Admin Monitoring")) + "','query-index':'" + escapeJs(localized(language, "Sorgu İndeksi", "Query Index")) + "','cache':'" + escapeJs(localized(language, "Önbellek", "Cache")) + "','page-cache':'" + escapeJs(localized(language, "Sayfa Önbelleği", "Page Cache")) + "','keyspace':'" + escapeJs(localized(language, "Anahtar Alanı", "Keyspace")) + "','schema':'" + escapeJs(localized(language, "Şema", "Schema")) + "','explicit-property':'" + escapeJs(localized(language, "Açıkça Verilen Ayar", "Explicit Property")) + "','write-behind':'" + escapeJs(localized(language, "Arka Plan Yazma", "Write-behind")) + "','dead-letter':'" + escapeJs(localized(language, "Başarısız Kayıt Kurtarma", "Dead-letter Recovery")) + "','guardrail':'" + escapeJs(localized(language, "Koruma Eşikleri", "Guardrail")) + "','runtime-profile':'" + escapeJs(localized(language, "Çalışma Profili", "Runtime Profile")) + "'};return labels[group]||group||'-';}"
                + "function tuningSourceLabel(source){const labels={'explicit override':'" + escapeJs(localized(language, "Açıkça Verilmiş Değer", "Explicit Override")) + "','modeled value':'" + escapeJs(localized(language, "Etkin Değer", "Effective Value")) + "','default/configured':'" + escapeJs(localized(language, "Varsayılan / Yapılandırılmış", "Default / Configured")) + "'};return labels[source]||source||'-';}"
                + "function serviceNameLabel(name){const labels={'write-behind':'" + escapeJs(localized(language, "Arka plan yazma", "Write-behind")) + "','recovery':'" + escapeJs(localized(language, "Kurtarma", "Recovery")) + "','cleanup':'" + escapeJs(localized(language, "Kurtarma temizliği", "Recovery cleanup")) + "','projection-refresh':'" + escapeJs(localized(language, "Projection refresh", "Projection refresh")) + "','incident-delivery-recovery':'" + escapeJs(localized(language, "Olay teslimi kurtarma", "Incident delivery recovery")) + "','admin-report-job':'" + escapeJs(localized(language, "Yönetim raporu işi", "Admin report job")) + "','redis-guardrail':'" + escapeJs(localized(language, "Redis koruma eşiği", "Redis guardrail")) + "','query-layer':'" + escapeJs(localized(language, "Sorgu katmanı", "Query layer")) + "','schema':'" + escapeJs(localized(language, "Şema", "Schema")) + "','incident-delivery':'" + escapeJs(localized(language, "Olay teslimi", "Incident delivery")) + "'};return labels[name]||name||'-';}"
                + "function serviceFieldLabel(key){const labels={backlog:'" + escapeJs(localized(language, "Birikme", "Backlog")) + "',deadLetter:'" + escapeJs(localized(language, "Başarısız kayıt", "Dead-letter")) + "',memory:'" + escapeJs(localized(language, "Bellek", "Memory")) + "',degradedIndexes:'" + escapeJs(localized(language, "Bozulan indeks", "Degraded indexes")) + "',migrationSteps:'" + escapeJs(localized(language, "Geçiş adımı", "Migration steps")) + "',enqueued:'" + escapeJs(localized(language, "Kuyruğa alınan", "Enqueued")) + "',flushed:'" + escapeJs(localized(language, "İşlenen", "Flushed")) + "',replayed:'" + escapeJs(localized(language, "Yeniden işlenen", "Replayed")) + "',failed:'" + escapeJs(localized(language, "Başarısız", "Failed")) + "',pending:'" + escapeJs(localized(language, "Bekleyen", "Pending")) + "',pressure:'" + escapeJs(localized(language, "Baskı düzeyi", "Pressure")) + "',profile:'" + escapeJs(localized(language, "Profil", "Profile")) + "',validatedTables:'" + escapeJs(localized(language, "Doğrulanan tablo", "Validated tables")) + "',issues:'" + escapeJs(localized(language, "Sorun", "Issues")) + "',delivered:'" + escapeJs(localized(language, "Teslim edilen", "Delivered")) + "',retries:'" + escapeJs(localized(language, "Yeniden deneme sayısı", "Retry count")) + "',backoffMs:'" + escapeJs(localized(language, "Bekleme aralığı", "Backoff delay")) + "',replayAttempts:'" + escapeJs(localized(language, "Yeniden işleme denemesi", "Replay attempts")) + "',claimTimeoutMs:'" + escapeJs(localized(language, "Talep zaman aşımı", "Claim timeout")) + "',claimIdleMs:'" + escapeJs(localized(language, "Sahiplenme bekleme süresi", "Claim idle delay")) + "',pollTimeoutMs:'" + escapeJs(localized(language, "Yoklama zaman aşımı", "Poll timeout")) + "',queueDepth:'" + escapeJs(localized(language, "Kuyruk derinliği", "Queue depth")) + "'};return labels[key]||key||'-';}"
                + "function serviceSignalParts(value){const raw=String(value||'').trim();const idx=raw.indexOf('=');if(idx>0){return {key:raw.slice(0,idx).trim(),value:raw.slice(idx+1).trim()};}return {key:'',value:raw};}"
                + "function serviceSignalLabel(value){const raw=String(value||'').trim();const idx=raw.indexOf('=');if(idx>0){return serviceFieldLabel(raw.slice(0,idx))+': '+raw.slice(idx+1);}return raw||'-';}"
                + "function serviceDetailLabel(value){const raw=String(value||'').trim();const labels={'planner healthy':'" + escapeJs(localized(language, "Planlayıcı sağlıklı çalışıyor.", "Planner is healthy.")) + "','no recent errors':'" + escapeJs(localized(language, "Yakın zamanda hata görülmedi.", "No recent errors.")) + "','no active issues':'" + escapeJs(localized(language, "Şu anda aktif bir sorun görünmüyor.", "No active issues.")) + "','delivery pipeline idle':'" + escapeJs(localized(language, "Teslim hattı şu anda sakin görünüyor.", "The delivery pipeline is currently idle.")) + "','recovery pipeline idle':'" + escapeJs(localized(language, "Kurtarma hattı şu anda sakin görünüyor.", "The recovery pipeline is currently idle.")) + "'};if(labels[raw]){return labels[raw];}return raw.split(',').map(part=>{const item=part.trim();const idx=item.indexOf('=');if(idx<=0){return item;}return serviceFieldLabel(item.slice(0,idx).trim())+': '+item.slice(idx+1).trim();}).join(', ');}"
                + "function routeRetryPolicyLabel(value){const raw=String(value||'').trim();if(!raw){return '-';}return raw.split(',').map(part=>{const item=part.trim();const idx=item.indexOf('=');if(idx<=0){return item;}return serviceFieldLabel(item.slice(0,idx).trim())+': '+item.slice(idx+1).trim();}).join(', ');}"
                + "function routeNameLabel(value){const raw=String(value||'').trim();const labels={webhook:'" + escapeJs(localized(language, "Webhook", "Webhook")) + "',queue:'" + escapeJs(localized(language, "Kuyruk", "Queue")) + "',smtp:'" + escapeJs(localized(language, "SMTP", "SMTP")) + "','delivery-dlq':'" + escapeJs(localized(language, "Teslim başarısız kayıt kuyruğu", "Delivery DLQ")) + "'};return labels[raw]||raw||'-';}"
                + "function starterProfileNoteLabel(value){const raw=String(value||'').trim();const labels={'Fast local bootstrap with admin dashboard enabled.':'" + escapeJs(localized(language, "Yönetim ekranı açık hızlı yerel başlangıç profili.", "Fast local bootstrap with admin dashboard enabled.")) + "','Validated runtime defaults with strict schema validation.':'" + escapeJs(localized(language, "Sıkı şema doğrulaması ile güvenli canlı çalışma varsayılanları.", "Validated runtime defaults with strict schema validation.")) + "','High-throughput profile for certification and soak runners.':'" + escapeJs(localized(language, "Sertifikasyon ve soak koşuları için yüksek hacimli profil.", "High-throughput profile for certification and soak runners.")) + "','Guardrail-heavy profile for low-memory deployments.':'" + escapeJs(localized(language, "Düşük bellekli kurulumlar için koruma ağırlıklı profil.", "Guardrail-heavy profile for low-memory deployments.")) + "'};return labels[raw]||raw||'-';}"
                + "function runbookTitleLabel(value){const raw=String(value||'').trim();const labels={'Write-behind backlog triage':'" + escapeJs(localized(language, "Arka plan yazma birikimi incelemesi", "Write-behind backlog triage")) + "','Dead-letter recovery triage':'" + escapeJs(localized(language, "Başarısız kayıt kurtarma incelemesi", "Dead-letter recovery triage")) + "','Redis memory pressure triage':'" + escapeJs(localized(language, "Redis bellek baskısı incelemesi", "Redis memory pressure triage")) + "','Query index degradation triage':'" + escapeJs(localized(language, "Sorgu indeksi bozulması incelemesi", "Query index degradation triage")) + "','Schema lifecycle triage':'" + escapeJs(localized(language, "Şema yaşam döngüsü incelemesi", "Schema lifecycle triage")) + "'};return labels[raw]||raw||'-';}"
                + "function runbookActionLabel(value){const raw=String(value||'').trim();const labels={'Inspect /api/metrics and worker snapshot, then confirm drain slope and runtime profile.':'" + escapeJs(localized(language, "/api/metrics ve worker özetine bak, ardından boşalma eğimini ve çalışma profilini doğrula.", "Inspect /api/metrics and worker snapshot, then confirm drain slope and runtime profile.")) + "','Review /api/incidents, then inspect reconciliation and delivery recovery snapshots.':'" + escapeJs(localized(language, "Önce /api/incidents çıktısını incele, ardından uzlaştırma ve teslim kurtarma özetlerine bak.", "Review /api/incidents, then inspect reconciliation and delivery recovery snapshots.")) + "','Inspect /api/metrics and runtime profile churn, confirm whether shedding/profile switch occurred.':'" + escapeJs(localized(language, "/api/metrics ve profil geçiş geçmişine bak; shedding veya profil değişimi olup olmadığını doğrula.", "Inspect /api/metrics and runtime profile churn, confirm whether shedding/profile switch occurred.")) + "','Inspect degraded entities and trigger targeted rebuild only after confirming current pressure level.':'" + escapeJs(localized(language, "Bozulan varlıkları incele; hedefli yeniden kurmayı ancak mevcut baskı düzeyini doğruladıktan sonra tetikle.", "Inspect degraded entities and trigger targeted rebuild only after confirming current pressure level.")) + "','Inspect schema status, plan, and DDL before applying additional migration steps.':'" + escapeJs(localized(language, "Ek geçiş adımı uygulamadan önce şema durumunu, planı ve DDL çıktısını incele.", "Inspect schema status, plan, and DDL before applying additional migration steps.")) + "'};return labels[raw]||raw||'-';}"
                + "function triageBottleneckLabel(value){const labels={healthy:'" + escapeJs(localized(language, "Belirgin darboğaz yok", "No clear bottleneck")) + "','producer-guardrail':'" + escapeJs(localized(language, "Yazma koruma eşiği", "Producer guardrail")) + "','write-behind':'" + escapeJs(localized(language, "Arka plan yazma hattı", "Write-behind")) + "',recovery:'" + escapeJs(localized(language, "Kurtarma hattı", "Recovery")) + "','projection-refresh':'" + escapeJs(localized(language, "Projection refresh hattı", "Projection refresh pipeline")) + "','redis-memory':'" + escapeJs(localized(language, "Redis belleği", "Redis memory")) + "','query-index':'" + escapeJs(localized(language, "Sorgu indeksi", "Query index")) + "','worker-errors':'" + escapeJs(localized(language, "Arka plan işleyici hataları", "Worker errors")) + "'};return labels[String(value||'').trim()]||String(value||'-');}"
                + "function triageCauseLabel(value){const raw=String(value||'').trim();const labels={'No active production-grade signal exceeds current thresholds.':'" + escapeJs(localized(language, "Şu anda eşikleri aşan belirgin bir üretim sinyali görünmüyor.", "No active production-grade signal exceeds current thresholds.")) + "','Redis hard limit is rejecting producer writes.':'" + escapeJs(localized(language, "Redis sert sınırı aşıldığı için üretici yazmaları reddediliyor.", "Redis hard limit is rejecting producer writes.")) + "','Write-behind backlog is above the critical threshold.':'" + escapeJs(localized(language, "Arka plan yazma birikimi kritik eşiğin üstünde.", "Write-behind backlog is above the critical threshold.")) + "','Write-behind backlog is above the warning threshold.':'" + escapeJs(localized(language, "Arka plan yazma birikimi uyarı eşiğinin üstünde.", "Write-behind backlog is above the warning threshold.")) + "','Dead-letter backlog suggests recovery throughput or replay correctness pressure.':'" + escapeJs(localized(language, "Başarısız kayıt kuyruğu, kurtarma hızının veya replay doğruluğunun baskı altında olduğunu gösteriyor.", "Dead-letter backlog suggests recovery throughput or replay correctness pressure.")) + "','Redis memory is near the configured guardrail threshold.':'" + escapeJs(localized(language, "Redis belleği tanımlı guardrail eşiğine yaklaştı.", "Redis memory is near the configured guardrail threshold.")) + "','Query index maintenance is degraded and the system may be falling back to scans.':'" + escapeJs(localized(language, "Sorgu indeksi bakımı bozulmuş görünüyor; sistem taramaya geri düşüyor olabilir.", "Query index maintenance is degraded and the system may be falling back to scans.")) + "','A recent worker error was observed even though backlog thresholds are not yet critical.':'" + escapeJs(localized(language, "Backlog henüz kritik seviyeye çıkmamış olsa da yakın zamanda bir arka plan işleyici hatası görüldü.", "A recent worker error was observed even though backlog thresholds are not yet critical.")) + "','Write-behind batch sent the same entity id to PostgreSQL more than once in a single bulk upsert statement.':'" + escapeJs(localized(language, "Arka plan yazma batch'i aynı kayıt kimliğini PostgreSQL'e tek bir toplu upsert komutunda birden fazla kez gönderdi.", "Write-behind batch sent the same entity id to PostgreSQL more than once in a single bulk upsert statement.")) + "'};return labels[raw]||raw||'-';}"
                + "function healthReasonActionLabel(bottleneck){const labels={healthy:'" + escapeJs(localized(language, "İlk kontrol için Ana Göstergeler ve Canlı Trendler yeterlidir.", "Primary Signals and Live Trends are enough for a quick check.")) + "','producer-guardrail':'" + escapeJs(localized(language, "İlk olarak Redis Belleği, baskı düzeyi ve reddedilen yazma sayaçlarına bak.", "Check Redis Memory, pressure level, and rejected writes first.")) + "','write-behind':'" + escapeJs(localized(language, "İlk olarak Arka Plan Yazma kartına, birikim eğrisine ve servis durumuna bak.", "Start with Write-behind, backlog trend, and service status.")) + "',recovery:'" + escapeJs(localized(language, "İlk olarak Başarısız Kayıt, Olaylar ve kurtarma servisi detaylarına bak.", "Start with Dead-letter, Incidents, and recovery service details.")) + "','redis-memory':'" + escapeJs(localized(language, "İlk olarak Redis Belleği, Çalışma Profili ve bellek trendine bak.", "Start with Redis Memory, Runtime Profile, and the memory trend.")) + "','query-index':'" + escapeJs(localized(language, "İlk olarak Önceliklendirme, Explain ve bozulan indeks sinyallerine bak.", "Start with Triage, Explain, and degraded index signals.")) + "','worker-errors':'" + escapeJs(localized(language, "İlk olarak Arka Plan Hataları bölümüne bak; worker, method ve stack trace burada görünür.", "Open Background Worker Errors first; the worker, method, and stack trace are shown there.")) + "'};return labels[String(bottleneck||'').trim()]||'" + escapeJs(localized(language, "İlk olarak Önceliklendirme ve Olaylar bölümüne bak.", "Start with Triage and Incidents.")) + "';}"
                + "function bottleneckCheckAreaLabel(bottleneck){const labels={healthy:'" + escapeJs(localized(language, "Ana Göstergeler ve Canlı Trendler", "Primary Signals and Live Trends")) + "','producer-guardrail':'" + escapeJs(localized(language, "Redis Belleği, baskı düzeyi ve guardrail eşikleri", "Redis Memory, pressure level, and guardrail thresholds")) + "','write-behind':'" + escapeJs(localized(language, "Arka Plan Yazma, Canlı Trendler ve Servis Durumu", "Write-behind, Live Trends, and Service Status")) + "',recovery:'" + escapeJs(localized(language, "Başarısız Kayıt Kuyruğu, Olaylar ve kurtarma worker detayları", "Dead-letter queue, Incidents, and recovery worker details")) + "','redis-memory':'" + escapeJs(localized(language, "Redis Belleği, Çalışma Profili ve bellek trendi", "Redis Memory, Runtime Profile, and memory trend")) + "','query-index':'" + escapeJs(localized(language, "Önceliklendirme, Explain ve bozulan indeks sinyalleri", "Triage, Explain, and degraded index signals")) + "','worker-errors':'" + escapeJs(localized(language, "Arka Plan Hataları tablosu, ilgili worker satırı ve stack trace", "Background Worker Errors, the matching worker row, and stack trace")) + "'};return labels[String(bottleneck||'').trim()]||'" + escapeJs(localized(language, "Önceliklendirme ve Olaylar bölümü", "Triage and Incidents")) + "';}"
                + "function recentBackgroundErrors(backgroundErrors){const items=Array.isArray(backgroundErrors&&backgroundErrors.items)?backgroundErrors.items:[];return items.filter(item=>item.recent);}"
                + "function summarizeTriageGuidance(triage,backgroundErrors){const firstRecentError=recentBackgroundErrors(backgroundErrors)[0]||null;const bottleneck=String(triage&&triage.primaryBottleneck||'').trim();let firstFix=healthReasonActionLabel(bottleneck);let checkArea=bottleneckCheckAreaLabel(bottleneck);let likelyCause=triageCauseLabel(triage&&triage.suspectedCause);if(bottleneck==='worker-errors'&&firstRecentError){likelyCause=(firstRecentError.rootErrorMessage||firstRecentError.errorMessage||triageCauseLabel(triage&&triage.suspectedCause)||'-');checkArea=workerNameLabel(firstRecentError.workerName)+(firstRecentError.origin?' / '+firstRecentError.origin:'');}return {firstFix:firstFix,checkArea:checkArea,likelyCause:likelyCause||'-'};}"
                + "function summarizeHealthReason(health,triage,backgroundErrors){const issues=Array.isArray(health.issues)?health.issues:[];const primaryIssue=issues.length?serviceDetailLabel(issues[0]):'';const recentErrors=Array.isArray(backgroundErrors&&backgroundErrors.items)?backgroundErrors.items.filter(item=>item.recent):[];const firstRecentError=recentErrors.length?recentErrors[0]:null;let title=health.status==='UP'?(issues.length?primaryIssue:'" + escapeJs(localized(language, "Şu anda aktif sorun görünmüyor.", "No active issue is currently visible.")) + "'):(primaryIssue||triageCauseLabel(triage.suspectedCause)||'" + escapeJs(localized(language, "Doğrudan neden üretilemedi.", "No direct cause could be derived.")) + "');let body='';let action=healthReasonActionLabel(triage.primaryBottleneck);if((triage.primaryBottleneck||'')==='worker-errors'&&firstRecentError){title=workerNameLabel(firstRecentError.workerName)+' - '+(firstRecentError.rootErrorType||firstRecentError.errorType||'" + escapeJs(localized(language, "Bilinmeyen hata", "Unknown error")) + "');body='" + escapeJs(localized(language, "Bu health sonucu son gözlem penceresinde görülen gerçek bir arka plan worker hatasından geliyor. ", "This health result comes from a real background worker error seen in the recent observation window. ")) + "'+(firstRecentError.rootErrorMessage||firstRecentError.errorMessage||'" + escapeJs(localized(language, "Mesaj bulunamadı.", "No message available.")) + "')+(firstRecentError.origin?'" + escapeJs(localized(language, " Kaynak: ", " Origin: ")) + "'+firstRecentError.origin:'');action='" + escapeJs(localized(language, "Arka Plan Hataları bölümünde aynı satırı aç; exception, kök neden ve stack trace'i birlikte incele.", "Open the matching row in Background Worker Errors and inspect the exception, root cause, and stack trace together.")) + "';return {title:title,body:body,action:action};}if(health.status==='UP'){body=issues.length?'" + escapeJs(localized(language, "Sağlık sonucu UP olsa da gözlem penceresinde bilgi amaçlı bir sinyal bulunuyor.", "Health is UP, but there is still an informational signal in the observation window.")) + "':'" + escapeJs(localized(language, "Sağlık şu anda UP. Warning veya yakın zamanlı worker hatası görünmüyor.", "Health is currently UP. No warning or recent worker error is visible.")) + "';}else{const extra=Math.max(0,issues.length-1);body='" + escapeJs(localized(language, "Sağlık sonucu ", "Health is ")) + "'+String(health.status||'-')+'" + escapeJs(localized(language, ". Bu durumun ana nedeni yukarıdaki sinyal. ", ". The main reason is the signal above. ")) + "'+(extra>0?'" + escapeJs(localized(language, "Buna ek olarak ", "There are also ")) + "'+extra+'" + escapeJs(localized(language, " ek aktif sorun daha var.", " more active issues.")) + "':'" + escapeJs(localized(language, "Şu anda ek aktif sorun görünmüyor.", "No additional active issue is currently visible.")) + "');}return {title:title,body:body,action:action};}"
                + "function summarizeHealthGuidance(health,triage,backgroundErrors){const firstRecentError=recentBackgroundErrors(backgroundErrors)[0]||null;const bottleneck=String(triage&&triage.primaryBottleneck||'').trim();let firstFix=healthReasonActionLabel(bottleneck);let checkArea=bottleneckCheckAreaLabel(bottleneck);let likelyCause=triageCauseLabel(triage&&triage.suspectedCause);if((triage&&triage.primaryBottleneck)==='worker-errors'&&firstRecentError){checkArea=workerNameLabel(firstRecentError.workerName)+(firstRecentError.origin?' / '+firstRecentError.origin:'');likelyCause=(firstRecentError.rootErrorMessage||firstRecentError.errorMessage||firstRecentError.rootErrorType||firstRecentError.errorType||'-');}else if(Array.isArray(health&&health.issues)&&health.issues.length>0){likelyCause=serviceDetailLabel(health.issues[0]);}return {firstFix:firstFix,checkArea:checkArea,likelyCause:likelyCause||'-'};}"
                + "function summarizeBackgroundErrorGuidance(backgroundErrors){const items=recentBackgroundErrors(backgroundErrors);const first=items[0]||null;if(!first){return {firstFix:'" + escapeJs(localized(language, "Şu anda aktif worker hatası görünmüyor. Yeni bir hata oluşursa ilk inceleme buradan başlar.", "No active worker error is visible right now. If a new failure appears, start here first.")) + "',checkArea:'" + escapeJs(localized(language, "Arka Plan Hataları tablosu ve ilgili worker satırı", "Background Worker Errors table and the matching worker row")) + "',likelyCause:'" + escapeJs(localized(language, "Yakın zamanlı exception görünmüyor.", "No recent exception is visible.")) + "'};}const rootType=String(first.rootErrorType||first.errorType||'');let firstFix='" + escapeJs(localized(language, "Stack trace'i aç, kök hata mesajını oku ve aynı worker'ın sinyalleriyle birlikte değerlendir.", "Open the stack trace, read the root error message, and correlate it with the same worker's signals.")) + "';if(rootType.includes('PSQLException')){firstFix='" + escapeJs(localized(language, "Kök hata mesajını ve flush batch içeriğini incele; aynı kimliğin tek batch içinde tekrar edip etmediğini doğrula.", "Inspect the root error message and flush batch composition; confirm the same identity is not repeated inside one batch.")) + "';}else if(rootType.includes('JedisDataException')){firstFix='" + escapeJs(localized(language, "Redis stream/group yolunu ve reset sonrası yeniden oluşturma akışını kontrol et.", "Check the Redis stream/group path and the recreation flow after reset.")) + "';}return {firstFix:firstFix,checkArea:workerNameLabel(first.workerName)+(first.origin?' / '+first.origin:''),likelyCause:(first.rootErrorMessage||first.errorMessage||first.rootErrorType||first.errorType||'-')};}"
                + "function summarizeServiceGuidance(item){if(!item){return {title:'" + escapeJs(localized(language, "Henüz bir servis seçilmedi.", "No service selected yet.")) + "',firstFix:'" + escapeJs(localized(language, "Bir servis satırına basarak ayrıntılı operasyon rehberini aç.", "Click a service row to open the detailed operational guide.")) + "',checkArea:'" + escapeJs(localized(language, "Servis Durumu tablosu", "Service Status table")) + "',likelyCause:'" + escapeJs(localized(language, "Henüz bir servis satırı seçilmedi.", "No service row has been selected yet.")) + "'};}const signal=serviceSignalParts(item.keySignal);const serviceName=String(item.serviceName||'').trim();const status=String(item.status||'UP').trim();let firstFix='" + escapeJs(localized(language, "Önce servis satırındaki ana gösterge ile aşağıdaki ilişki bölümlerini birlikte oku.", "Read the key signal on the service row together with the related sections below.")) + "';let checkArea=serviceSignalLabel(item.keySignal||'-');let likelyCause=serviceDetailLabel(item.detail||'-');if(serviceName==='write-behind'){firstFix=status==='DEGRADED'||status==='DOWN'?'"+escapeJs(localized(language, "Arka plan yazma kuyruğunu, worker hatalarını ve PostgreSQL flush akışını birlikte kontrol et.", "Check the write-behind queue, worker errors, and PostgreSQL flush path together."))+"':'"+escapeJs(localized(language, "Kuyruk eğrisini izle; yeni bir birikim başlarsa Canlı Trendler ile birlikte yeniden değerlendir.", "Watch the backlog trend and reassess with Live Trends if accumulation starts again."))+"';checkArea='"+escapeJs(localized(language, "Arka Plan Yazma, Canlı Trendler ve Arka Plan Hataları", "Write-behind, Live Trends, and Background Worker Errors"))+"';}else if(serviceName==='recovery'||serviceName==='cleanup'||serviceName==='incident-delivery-recovery'){firstFix='"+escapeJs(localized(language, "Başarısız kayıt kuyruğunu, olayları ve yeniden işleme denemelerini birlikte incele.", "Inspect dead-letter backlog, incidents, and replay attempts together."))+"';checkArea='"+escapeJs(localized(language, "Başarısız Kayıt Kuyruğu, Olaylar ve Arka Plan Hataları", "Dead-letter queue, Incidents, and Background Worker Errors"))+"';}else if(serviceName==='redis-guardrail'){firstFix='"+escapeJs(localized(language, "Redis belleği ve baskı düzeyi artıyorsa çalışma profilinin neden değiştiğini doğrula.", "If Redis memory and pressure are rising, confirm why the runtime profile changed."))+"';checkArea='"+escapeJs(localized(language, "Redis Belleği, Çalışma Profili ve Profil Geçiş Geçmişi", "Redis Memory, Runtime Profile, and Profile Churn"))+"';}else if(serviceName==='query-layer'){firstFix='"+escapeJs(localized(language, "Explain ekranına geç ve bozulmuş indeks veya fetch uyarısı var mı bak.", "Open Explain and check for degraded index or fetch warnings."))+"';checkArea='"+escapeJs(localized(language, "Önceliklendirme, Explain ve Öğrenilmiş İstatistikler", "Triage, Explain, and Learned Statistics"))+"';}else if(serviceName==='schema'){firstFix='"+escapeJs(localized(language, "Şema durumu ile geçiş geçmişini birlikte incele; tekrar eden veya eksik adım var mı kontrol et.", "Inspect schema status together with schema history and check for repeated or missing steps."))+"';checkArea='"+escapeJs(localized(language, "Şema Durumu, Şema Geçmişi ve Şema DDL", "Schema Status, Schema History, and Schema DDL"))+"';}else if(serviceName==='incident-delivery'||signal.key==='delivered'||signal.key==='failed'){firstFix='"+escapeJs(localized(language, "Teslim başarısı düşüyorsa rota geçmişi ve yeniden deneme politikasını birlikte oku.", "If delivery success is slipping, read route history together with retry policy."))+"';checkArea='"+escapeJs(localized(language, "Uyarı Yönlendirme, Uyarı Rota Geçmişi ve Runbook'lar", "Alert Routing, Alert Route History, and Runbooks"))+"';}return {title:serviceNameLabel(serviceName),firstFix:firstFix,checkArea:checkArea,likelyCause:likelyCause||'-'};}"
                + "function renderServiceGuide(item){const guide=summarizeServiceGuidance(item);document.getElementById('serviceGuideTitle').textContent=guide.title;document.getElementById('serviceGuideFirstFix').textContent=guide.firstFix;document.getElementById('serviceGuideCheckArea').textContent=guide.checkArea;document.getElementById('serviceGuideLikelyCause').textContent=guide.likelyCause;}"
                + "function humanizePropertyToken(token){const labels={cachedb:'CacheDB',config:'" + escapeJs(localized(language, "ayar", "config")) + "',admin:'" + escapeJs(localized(language, "yönetim", "admin")) + "',http:'HTTP',monitoring:'" + escapeJs(localized(language, "izleme", "monitoring")) + "',query:'" + escapeJs(localized(language, "sorgu", "query")) + "',index:'" + escapeJs(localized(language, "indeks", "index")) + "',planner:'" + escapeJs(localized(language, "planlayıcı", "planner")) + "',statistics:'" + escapeJs(localized(language, "istatistikleri", "statistics")) + "',persisted:'" + escapeJs(localized(language, "saklama", "persisted")) + "',resource:'" + escapeJs(localized(language, "kaynak", "resource")) + "',limits:'" + escapeJs(localized(language, "sınırları", "limits")) + "',default:'" + escapeJs(localized(language, "varsayılan", "default")) + "',cache:'" + escapeJs(localized(language, "önbellek", "cache")) + "',policy:'" + escapeJs(localized(language, "politikası", "policy")) + "',hot:'" + escapeJs(localized(language, "sıcak", "hot")) + "',entity:'" + escapeJs(localized(language, "kayıt", "entity")) + "',limit:'" + escapeJs(localized(language, "limiti", "limit")) + "',page:'" + escapeJs(localized(language, "sayfa", "page")) + "',size:'" + escapeJs(localized(language, "boyutu", "size")) + "',ttl:'TTL',seconds:'" + escapeJs(localized(language, "saniye", "seconds")) + "',read:'" + escapeJs(localized(language, "okuma", "read")) + "',through:'" + escapeJs(localized(language, "geçiş", "through")) + "',enabled:'" + escapeJs(localized(language, "açık", "enabled")) + "',eviction:'" + escapeJs(localized(language, "tahliye", "eviction")) + "',batch:'" + escapeJs(localized(language, "paket", "batch")) + "',keyspace:'" + escapeJs(localized(language, "anahtar alanı", "keyspace")) + "',key:'" + escapeJs(localized(language, "anahtar", "key")) + "',prefix:'" + escapeJs(localized(language, "ön eki", "prefix")) + "',warn:'" + escapeJs(localized(language, "uyarı", "warn")) + "',warning:'" + escapeJs(localized(language, "uyarı", "warning")) + "',critical:'" + escapeJs(localized(language, "kritik", "critical")) + "',threshold:'" + escapeJs(localized(language, "eşiği", "threshold")) + "',history:'" + escapeJs(localized(language, "geçmişi", "history")) + "',sample:'" + escapeJs(localized(language, "örnekleme", "sample")) + "',interval:'" + escapeJs(localized(language, "aralığı", "interval")) + "',millis:'" + escapeJs(localized(language, "milisaniye", "millis")) + "',max:'" + escapeJs(localized(language, "en çok", "max")) + "',samples:'" + escapeJs(localized(language, "örnek", "samples")) + "',host:'" + escapeJs(localized(language, "adresi", "host")) + "',port:'" + escapeJs(localized(language, "portu", "port")) + "',dashboard:'" + escapeJs(localized(language, "ekranı", "dashboard")) + "',schema:'" + escapeJs(localized(language, "şema", "schema")) + "',bootstrap:'" + escapeJs(localized(language, "başlatma", "bootstrap")) + "',mode:'" + escapeJs(localized(language, "modu", "mode")) + "',auto:'" + escapeJs(localized(language, "otomatik", "auto")) + "',apply:'" + escapeJs(localized(language, "uygula", "apply")) + "',start:'" + escapeJs(localized(language, "başlangıç", "start")) + "',name:'" + escapeJs(localized(language, "adı", "name")) + "',write:'" + escapeJs(localized(language, "yazma", "write")) + "',behind:'" + escapeJs(localized(language, "arka plan", "behind")) + "',compaction:'" + escapeJs(localized(language, "sıkıştırma", "compaction")) + "',shard:'" + escapeJs(localized(language, "parça", "shard")) + "',count:'" + escapeJs(localized(language, "sayısı", "count")) + "',flush:'" + escapeJs(localized(language, "işleme", "flush")) + "',stream:'" + escapeJs(localized(language, "akış", "stream")) + "',redis:'Redis',guardrail:'" + escapeJs(localized(language, "koruma eşiği", "guardrail")) + "',backlog:'" + escapeJs(localized(language, "kuyruğu", "backlog")) + "',runtime:'" + escapeJs(localized(language, "çalışma", "runtime")) + "',profile:'" + escapeJs(localized(language, "profili", "profile")) + "',switching:'" + escapeJs(localized(language, "geçişi", "switching")) + "',manual:'" + escapeJs(localized(language, "elle", "manual")) + "',override:'" + escapeJs(localized(language, "zorlaması", "override")) + "'};return labels[token]||token;}"
                + "function humanizePropertyLabel(property){const normalized=String(property||'').replace(/([a-z])([A-Z])/g,'$1 $2').replace(/[._-]+/g,' ');const parts=normalized.split(/\\s+/).filter(Boolean).map(token=>humanizePropertyToken(token.toLowerCase()));return parts.length?parts.join(' '):String(property||'-');}"
                + "function tuningPropertyMeta(property,description){const map={"
                + "'cachedb.config.queryIndex.plannerStatisticsPersisted':{label:'" + escapeJs(localized(language, "Planlayıcı istatistiklerini sakla", "Persist planner statistics")) + "',description:'" + escapeJs(localized(language, "Sorgu planlayıcısının öğrendiği istatistiklerin Redis üzerinde saklanıp saklanmayacağını belirler.", "Determines whether learned planner statistics are persisted in Redis.")) + "'},"
                + "'cachedb.config.resourceLimits.defaultCachePolicy.hotEntityLimit':{label:'" + escapeJs(localized(language, "Varsayılan sıcak kayıt limiti", "Default hot-entity limit")) + "',description:'" + escapeJs(localized(language, "Varsayılan önbellek politikasında sıcak kayıtlar için ayrılan üst sınırı belirler.", "Sets the hot-entity limit in the default cache policy.")) + "'},"
                + "'cachedb.config.resourceLimits.defaultCachePolicy.pageSize':{label:'" + escapeJs(localized(language, "Varsayılan sayfa boyutu", "Default page size")) + "',description:'" + escapeJs(localized(language, "Varsayılan sayfalama boyutunu belirler.", "Sets the default page size.")) + "'},"
                + "'cachedb.config.resourceLimits.defaultCachePolicy.entityTtlSeconds':{label:'" + escapeJs(localized(language, "Varsayılan kayıt TTL süresi", "Default entity TTL")) + "',description:'" + escapeJs(localized(language, "Tekil kayıtların önbellekte kaç saniye tutulacağını belirler.", "Controls how long entity entries stay in cache.")) + "'},"
                + "'cachedb.config.resourceLimits.defaultCachePolicy.pageTtlSeconds':{label:'" + escapeJs(localized(language, "Varsayılan sayfa TTL süresi", "Default page TTL")) + "',description:'" + escapeJs(localized(language, "Sayfa sonuçlarının önbellekte kaç saniye tutulacağını belirler.", "Controls how long page results stay in cache.")) + "'},"
                + "'cachedb.config.pageCache.readThroughEnabled':{label:'" + escapeJs(localized(language, "Sayfa önbelleği read-through", "Page-cache read-through")) + "',description:'" + escapeJs(localized(language, "Sayfa önbelleğinin okuma sırasında eksik veriyi arka planda tamamlayıp tamamlamayacağını belirler.", "Controls whether page-cache read-through is enabled.")) + "'},"
                + "'cachedb.config.pageCache.evictionBatchSize':{label:'" + escapeJs(localized(language, "Sayfa önbelleği tahliye paket boyutu", "Page-cache eviction batch size")) + "',description:'" + escapeJs(localized(language, "Sayfa önbelleği temizliği sırasında tek seferde kaç kaydın ele alınacağını belirler.", "Sets how many entries are evicted per page-cache cleanup batch.")) + "'},"
                + "'cachedb.config.keyspace.keyPrefix':{label:'" + escapeJs(localized(language, "Genel Redis anahtar ön eki", "Global Redis key prefix")) + "',description:'" + escapeJs(localized(language, "Tüm Redis anahtarlarının başına eklenecek ortak ön eki belirler.", "Defines the shared prefix added to Redis keys.")) + "'},"
                + "'cachedb.config.adminMonitoring.writeBehindWarnThreshold':{label:'" + escapeJs(localized(language, "Write-behind uyarı eşiği", "Write-behind warning threshold")) + "',description:'" + escapeJs(localized(language, "Write-behind birikimi için uyarı seviyesinin hangi sayıda açılacağını belirler.", "Sets the warning threshold for write-behind backlog incidents.")) + "'},"
                + "'cachedb.config.adminMonitoring.writeBehindCriticalThreshold':{label:'" + escapeJs(localized(language, "Write-behind kritik eşiği", "Write-behind critical threshold")) + "',description:'" + escapeJs(localized(language, "Write-behind birikimi için kritik seviyenin hangi sayıda açılacağını belirler.", "Sets the critical threshold for write-behind backlog incidents.")) + "'},"
                + "'cachedb.config.adminMonitoring.historySampleIntervalMillis':{label:'" + escapeJs(localized(language, "İzleme örnekleme aralığı", "Monitoring sample interval")) + "',description:'" + escapeJs(localized(language, "Trend geçmişine yeni örneklerin kaç milisaniyede bir ekleneceğini belirler.", "Sets how often monitoring history samples are collected.")) + "'},"
                + "'cachedb.config.adminMonitoring.historyMaxSamples':{label:'" + escapeJs(localized(language, "İzleme geçmişi saklama adedi", "Monitoring history retention")) + "',description:'" + escapeJs(localized(language, "Trend geçmişinde en fazla kaç örneğin saklanacağını belirler.", "Sets the maximum number of monitoring history samples to keep.")) + "'},"
                + "'cachedb.config.adminHttp.enabled':{label:'" + escapeJs(localized(language, "Yönetim HTTP sunucusu açık", "Admin HTTP enabled")) + "',description:'" + escapeJs(localized(language, "Yönetim HTTP sunucusunun açılıp açılmayacağını belirler.", "Controls whether the admin HTTP server is enabled.")) + "'},"
                + "'cachedb.config.adminHttp.host':{label:'" + escapeJs(localized(language, "Yönetim HTTP dinleme adresi", "Admin HTTP host")) + "',description:'" + escapeJs(localized(language, "Yönetim HTTP sunucusunun hangi adrese bağlanacağını belirler.", "Sets the bind host for the admin HTTP server.")) + "'},"
                + "'cachedb.config.adminHttp.port':{label:'" + escapeJs(localized(language, "Yönetim HTTP portu", "Admin HTTP port")) + "',description:'" + escapeJs(localized(language, "Yönetim HTTP sunucusunun hangi portta dinleyeceğini belirler.", "Sets the port used by the admin HTTP server.")) + "'},"
                + "'cachedb.config.adminHttp.dashboardEnabled':{label:'" + escapeJs(localized(language, "Yönetim ekranı açık", "Admin dashboard enabled")) + "',description:'" + escapeJs(localized(language, "Yerleşik yönetim ekranının açık olup olmayacağını belirler.", "Controls whether the built-in admin dashboard is enabled.")) + "'},"
                + "'cachedb.config.schemaBootstrap.mode':{label:'" + escapeJs(localized(language, "Şema başlangıç modu", "Schema bootstrap mode")) + "',description:'" + escapeJs(localized(language, "Şemanın başlangıçta nasıl ele alınacağını belirler.", "Sets how schema bootstrap behaves on startup.")) + "'},"
                + "'cachedb.config.schemaBootstrap.autoApplyOnStart':{label:'" + escapeJs(localized(language, "Şemayı başlangıçta otomatik uygula", "Auto-apply schema on start")) + "',description:'" + escapeJs(localized(language, "Şema adımlarının uygulama başlarken otomatik çalıştırılıp çalıştırılmayacağını belirler.", "Controls whether schema bootstrap is auto-applied on startup.")) + "'},"
                + "'cachedb.config.schemaBootstrap.schemaName':{label:'" + escapeJs(localized(language, "Hedef PostgreSQL şeması", "Target PostgreSQL schema")) + "',description:'" + escapeJs(localized(language, "Yapıların hangi PostgreSQL şemasına kurulacağını belirler.", "Sets the PostgreSQL schema name used for persistence.")) + "'},"
                + "'cachedb.config.writeBehind.batchSize':{label:'" + escapeJs(localized(language, "Arka plan yazma paket boyutu", "Write-behind batch size")) + "',description:'" + escapeJs(localized(language, "Arka plan yazma hattında bir seferde işlenecek kayıt sayısını belirler.", "Sets how many records the write-behind path processes in one batch.")) + "'},"
                + "'cachedb.config.writeBehind.compactionShardCount':{label:'" + escapeJs(localized(language, "Sıkıştırma parça sayısı", "Compaction shard count")) + "',description:'" + escapeJs(localized(language, "Sıkıştırma işinin kaç parçaya bölüneceğini belirler.", "Sets how many shards compaction work is split across.")) + "'},"
                + "'cachedb.config.writeBehind.enabled':{label:'" + escapeJs(localized(language, "Arka plan yazma açık", "Write-behind enabled")) + "',description:'" + escapeJs(localized(language, "Arka plan yazma hattının çalışıp çalışmayacağını belirler.", "Controls whether the write-behind pipeline is enabled.")) + "'},"
                + "'cachedb.config.writeBehind.maxFlushBatchSize':{label:'" + escapeJs(localized(language, "En büyük flush paket boyutu", "Maximum flush batch size")) + "',description:'" + escapeJs(localized(language, "Kalıcı depoya tek seferde yazılabilecek en büyük paket boyutunu belirler.", "Sets the maximum batch size written to durable storage in one flush.")) + "'},"
                + "'cachedb.config.writeBehind.readBatchSize':{label:'" + escapeJs(localized(language, "Akış okuma paket boyutu", "Stream read batch size")) + "',description:'" + escapeJs(localized(language, "Redis akışından bir turda kaç kaydın okunacağını belirler.", "Sets how many records are read from the Redis stream per cycle.")) + "'},"
                + "'cachedb.config.redisGuardrail.writeBehindBacklogWarnThreshold':{label:'" + escapeJs(localized(language, "Yazma kuyruğu uyarı eşiği", "Write backlog warning threshold")) + "',description:'" + escapeJs(localized(language, "Yazma kuyruğu bu değeri aşarsa sistem uyarı seviyesine çıkar.", "Moves the system to warning once write backlog exceeds this value.")) + "'},"
                + "'cachedb.config.redisGuardrail.writeBehindBacklogCriticalThreshold':{label:'" + escapeJs(localized(language, "Yazma kuyruğu kritik eşiği", "Write backlog critical threshold")) + "',description:'" + escapeJs(localized(language, "Yazma kuyruğu bu değeri aşarsa sistem kritik seviyeye çıkar.", "Moves the system to critical once write backlog exceeds this value.")) + "'},"
                + "'cachedb.config.redisGuardrail.usedMemoryWarnBytes':{label:'" + escapeJs(localized(language, "Bellek uyarı eşiği", "Memory warning threshold")) + "',description:'" + escapeJs(localized(language, "Redis kullanılan belleği bu değeri aşarsa sistem uyarı baskısına geçer.", "Moves the system to warning pressure when Redis memory exceeds this value.")) + "'},"
                + "'cachedb.config.redisGuardrail.usedMemoryCriticalBytes':{label:'" + escapeJs(localized(language, "Bellek kritik eşiği", "Memory critical threshold")) + "',description:'" + escapeJs(localized(language, "Redis kullanılan belleği bu değeri aşarsa sistem kritik baskıya geçer.", "Moves the system to critical pressure when Redis memory exceeds this value.")) + "'},"
                + "'cachedb.config.writeBehind.workerThreads':{label:'" + escapeJs(localized(language, "Arka plan yazma iş parçacığı sayısı", "Write-behind worker threads")) + "',description:'" + escapeJs(localized(language, "Arka plan yazma hattında aynı anda çalışacak iş parçacığı sayısını belirler.", "Sets how many worker threads process the write-behind pipeline concurrently.")) + "'},"
                + "'cachedb.config.writeBehind.claimTimeoutSeconds':{label:'" + escapeJs(localized(language, "Kayıt sahiplenme zaman aşımı", "Claim timeout")) + "',description:'" + escapeJs(localized(language, "İşlenmek üzere alınan bir kaydın ne kadar süre sonra yeniden sahiplenilebileceğini belirler.", "Sets how long a claimed entry waits before it can be claimed again.")) + "'},"
                + "'cachedb.config.adminMonitoring.deadLetterWarnThreshold':{label:'" + escapeJs(localized(language, "Başarısız kayıt uyarı eşiği", "Dead-letter warning threshold")) + "',description:'" + escapeJs(localized(language, "Başarısız kayıt kuyruğu bu değeri aşarsa uyarı seviyesi açılır.", "Sets the warning threshold for dead-letter backlog incidents.")) + "'},"
                + "'cachedb.config.adminMonitoring.deadLetterCriticalThreshold':{label:'" + escapeJs(localized(language, "Başarısız kayıt kritik eşiği", "Dead-letter critical threshold")) + "',description:'" + escapeJs(localized(language, "Başarısız kayıt kuyruğu bu değeri aşarsa kritik seviye açılır.", "Sets the critical threshold for dead-letter backlog incidents.")) + "'},"
                + "'cachedb.config.adminMonitoring.incidentDeliveryPollTimeoutMillis':{label:'" + escapeJs(localized(language, "Olay teslimi yoklama zaman aşımı", "Incident delivery poll timeout")) + "',description:'" + escapeJs(localized(language, "Uyarı teslimi kuyruğunun yeni kayıt beklerken ne kadar süre bloklanacağını belirler.", "Sets how long incident delivery waits for new work before polling again.")) + "'},"
                + "'cachedb.config.adminMonitoring.alertRouteHistoryMaxSamples':{label:'" + escapeJs(localized(language, "Rota geçmişi saklama adedi", "Route history retention")) + "',description:'" + escapeJs(localized(language, "Uyarı rota geçmişinde en fazla kaç örneğin saklanacağını belirler.", "Sets the maximum number of alert route history samples to keep.")) + "'},"
                + "'cachedb.config.deadLetterRecovery.enabled':{label:'" + escapeJs(localized(language, "Başarısız kayıt kurtarma açık", "Dead-letter recovery enabled")) + "',description:'" + escapeJs(localized(language, "Başarısız kayıt kuyruğundan yeniden işleme hattının açık olup olmadığını belirler.", "Controls whether dead-letter recovery is enabled.")) + "'},"
                + "'cachedb.config.deadLetterRecovery.maxReplayRetries':{label:'" + escapeJs(localized(language, "En çok yeniden işleme denemesi", "Maximum replay retries")) + "',description:'" + escapeJs(localized(language, "Bir başarısız kaydın en fazla kaç kez yeniden işleneceğini belirler.", "Sets how many replay attempts are allowed for a dead-letter entry.")) + "'},"
                + "'cachedb.config.deadLetterRecovery.workerThreads':{label:'" + escapeJs(localized(language, "Kurtarma iş parçacığı sayısı", "Recovery worker threads")) + "',description:'" + escapeJs(localized(language, "Başarısız kayıt kurtarma hattında aynı anda çalışacak iş parçacığı sayısını belirler.", "Sets how many worker threads process dead-letter recovery.")) + "'},"
                + "'cachedb.config.queryIndex.exactIndexEnabled':{label:'" + escapeJs(localized(language, "Tam eşleşme indeksi açık", "Exact index enabled")) + "',description:'" + escapeJs(localized(language, "Tam eşleşme indekslerinin kullanılabilir olup olmadığını belirler.", "Controls whether exact-match query indexes are enabled.")) + "'},"
                + "'cachedb.config.queryIndex.learnedStatisticsEnabled':{label:'" + escapeJs(localized(language, "Öğrenilmiş istatistikler açık", "Learned statistics enabled")) + "',description:'" + escapeJs(localized(language, "Planlayıcının öğrendiği istatistikleri kullanıp kullanmayacağını belirler.", "Controls whether planner-learned statistics are enabled.")) + "'},"
                + "'cachedb.config.queryIndex.rangeIndexEnabled':{label:'" + escapeJs(localized(language, "Aralık indeksi açık", "Range index enabled")) + "',description:'" + escapeJs(localized(language, "Aralık sorgularında kullanılan indekslerin açık olup olmadığını belirler.", "Controls whether range query indexes are enabled.")) + "'},"
                + "'cachedb.config.writeBehind.postgresCopyThreshold':{label:'" + escapeJs(localized(language, "PostgreSQL COPY eşiği", "PostgreSQL COPY threshold")) + "',description:'" + escapeJs(localized(language, "Arka plan yazma hattının hangi boyuttan sonra COPY yoluna geçeceğini belirler.", "Sets when the write-behind pipeline switches to PostgreSQL COPY mode.")) + "'},"
                + "'cachedb.config.redisGuardrail.automaticRuntimeProfileSwitchingEnabled':{label:'" + escapeJs(localized(language, "Otomatik profil geçişi açık", "Automatic runtime profile switching")) + "',description:'" + escapeJs(localized(language, "Baskı arttığında çalışma profilinin otomatik değişip değişmeyeceğini belirler.", "Controls whether runtime profiles switch automatically under pressure.")) + "'},"
                + "'cachedb.config.redisGuardrail.enabled':{label:'" + escapeJs(localized(language, "Koruma eşikleri açık", "Guardrails enabled")) + "',description:'" + escapeJs(localized(language, "Redis baskı eşiklerinin ve koruma davranışının etkin olup olmadığını belirler.", "Controls whether Redis guardrails are enabled.")) + "'},"
                + "'cachedb.config.redisGuardrail.rejectWritesOnHardLimit':{label:'" + escapeJs(localized(language, "Sert sınırda yazma reddi", "Reject writes on hard limit")) + "',description:'" + escapeJs(localized(language, "Sert bellek sınırında yeni yazmaların reddedilip reddedilmeyeceğini belirler.", "Controls whether writes are rejected when the hard limit is hit.")) + "'},"
                + "'cachedb.demo.jdbcUrl':{label:'" + escapeJs(localized(language, "Demo PostgreSQL bağlantı adresi", "Demo PostgreSQL connection URL")) + "',description:'" + escapeJs(localized(language, "Demo uygulamasının PostgreSQL'e hangi JDBC adresiyle bağlandığını gösterir.", "Shows which JDBC URL the demo application uses for PostgreSQL.")) + "'},"
                + "'cachedb.demo.jdbcUser':{label:'" + escapeJs(localized(language, "Demo PostgreSQL kullanıcı adı", "Demo PostgreSQL user")) + "',description:'" + escapeJs(localized(language, "Demo uygulamasının PostgreSQL bağlantısında kullandığı kullanıcı adını gösterir.", "Shows the PostgreSQL user used by the demo application.")) + "'},"
                + "'cachedb.demo.jdbcPassword':{label:'" + escapeJs(localized(language, "Demo PostgreSQL parolası", "Demo PostgreSQL password")) + "',description:'" + escapeJs(localized(language, "Demo uygulamasının PostgreSQL bağlantısında kullandığı parolayı gösterir.", "Shows the PostgreSQL password used by the demo application.")) + "'},"
                + "'cachedb.demo.redisUri':{label:'" + escapeJs(localized(language, "Demo Redis bağlantı adresi", "Demo Redis URI")) + "',description:'" + escapeJs(localized(language, "Demo uygulamasının Redis'e hangi bağlantı adresiyle bağlandığını gösterir.", "Shows which Redis URI the demo application uses.")) + "'}"
                + "};if(map[property]){return map[property];}if(property&&property.startsWith('cachedb.')){return {label:humanizePropertyLabel(property),description:'" + escapeJs(localized(language, "Bu ayar şu anda etkin. Alttaki teknik anahtar korunur; üst satırda okunabilir adı gösterilir.", "This setting is currently active. The raw technical key is preserved below while the top line shows a readable name.")) + "'};}return {label:property||'-',description:description||''};}"
                + "function tuningPropertyCell(item){const meta=tuningPropertyMeta(item.property,item.description);return '<div class=\"d-flex flex-column gap-1\"><span class=\"fw-semibold\">'+escapeText(meta.label)+'</span><span class=\"mono text-muted small\">'+escapeText(item.property||'-')+'</span></div>';}"
                + "function tuningDescriptionCell(item){const meta=tuningPropertyMeta(item.property,item.description);return titled(meta.description,0,false);}"
                + "function renderSignalDashboard(metrics,health,summary){const target=document.getElementById('signalDashboardGrid');if(!target){return;}const data=summary||dashboardDefaults.sig;const criticalCount=Number(data.criticalIncidentCount)||0;const warningCount=Number(data.warningIncidentCount)||0;const incidentCount=Number(data.incidentCount)||0;const wb=Number(metrics.writeBehindStreamLength)||0;const dlq=Number(metrics.deadLetterStreamLength)||0;const usedMemory=Number(metrics.redisGuardrailSnapshot.usedMemoryBytes)||0;const pending=Number(metrics.redisGuardrailSnapshot.compactionPendingCount)||0;const profile=metrics.redisRuntimeProfileSnapshot.activeProfile||'STANDARD';const pressure=metrics.redisRuntimeProfileSnapshot.lastObservedPressureLevel||'NORMAL';const delivered=Number(data.alertDeliveredCount)||0;const failed=Number(data.alertFailedCount)||0;const dropped=Number(data.alertDroppedCount)||0;const routeCount=Number(data.alertRouteCount)||0;const recentErrorCount=Number(data.recentBackgroundErrorCount)||0;const firstRecentError=recentErrorCount>0?{workerName:data.recentBackgroundErrorWorker||'',rootErrorType:data.recentBackgroundErrorType||'',origin:data.recentBackgroundErrorOrigin||''}:null;const triage={overallStatus:data.triageOverallStatus||'UP',primaryBottleneck:data.triagePrimaryBottleneck||'healthy',suspectedCause:data.triageSuspectedCause||''};const projectionRefresh=metrics.projectionRefreshSnapshot||dashboardDefaults.m.projectionRefreshSnapshot;const projectionDlq=Number(projectionRefresh.deadLetterStreamLength)||0;const projectionPending=Number(projectionRefresh.pendingCount)||0;const projectionReplayed=Number(projectionRefresh.replayedCount)||0;const projectionProcessed=Number(projectionRefresh.processedCount)||0;const projectionRecentErrorAt=Number(projectionRefresh.lastErrorAtEpochMillis)||0;const projectionErrorType=String(projectionRefresh.lastErrorRootType||projectionRefresh.lastErrorType||'').trim();const widgets=[];const writeTone=dlq>0||wb>500||criticalCount>0?'danger':(wb>50||pending>100?'warning':'success');widgets.push({title:'" + escapeJs(localized(language, "Yazma Hattı", "Write Path")) + "',tone:writeTone,statusLabel:writeTone==='danger'?'" + escapeJs(localized(language, "Kritik", "Critical")) + "':(writeTone==='warning'?'" + escapeJs(localized(language, "Dikkat", "Watch")) + "':'" + escapeJs(localized(language, "Akış sağlıklı", "Healthy")) + "'),summary:'" + escapeJs(localized(language, "Arka plan yazma kuyruğu, başarısız kayıtlar ve bekleyen sıkıştırma işlerini birlikte yorumlar.", "Combines write-behind backlog, dead-letter, and pending compaction pressure in one place.")) + "',primaryValue:String(wb),secondaryValue:'" + escapeJs(localized(language, "kuyrukta bekleyen iş", "items waiting in queue")) + "',progress:wb>0?Math.min(100,(wb/1000)*100):(dlq>0?15:5),facts:[{label:'" + escapeJs(localized(language, "Başarısız kayıt", "Dead-letter")) + "',value:String(dlq)},{label:'" + escapeJs(localized(language, "Bekleyen sıkıştırma", "Pending compaction")) + "',value:String(pending)},{label:'" + escapeJs(localized(language, "Ana darboğaz", "Primary bottleneck")) + "',value:triageBottleneckLabel(triage.primaryBottleneck)}],actionText:writeTone==='danger'?'" + escapeJs(localized(language, "Önce başarısız kayıt kuyruğuna ve arka plan işleyici hatalarına bak. Birikim toparlanmıyorsa yazma hattı kalıcı baskı altında olabilir.", "Check dead-letter and worker errors first. If backlog does not recover, the write path is under sustained pressure.")) + "':'" + escapeJs(localized(language, "Birikim kısa süreli yükselip geri düşüyorsa akış normaldir. Uzun süre yüksek kalıyorsa alt bölümlere in.", "If backlog briefly rises and then falls, the flow is healthy. If it stays high, drill into the lower sections.")) + "'});"
                + "const memoryTone=pressure==='CRITICAL'?'danger':(pressure==='HIGH'||pressure==='WARN'?'warning':'success');widgets.push({title:'" + escapeJs(localized(language, "Bellek Baskısı", "Memory Pressure")) + "',tone:memoryTone,statusLabel:pressure==='CRITICAL'?'" + escapeJs(localized(language, "Kritik eşikte", "Critical")) + "':(memoryTone==='warning'?'" + escapeJs(localized(language, "Yükseliyor", "Rising")) + "':'" + escapeJs(localized(language, "Dengeli", "Stable")) + "'),summary:'" + escapeJs(localized(language, "Redis belleğini ve koruma eşiği baskı düzeyini birlikte gösterir.", "Reads Redis memory together with the guardrail pressure level.")) + "',primaryValue:formatBytes(usedMemory),secondaryValue:'" + escapeJs(localized(language, "kullanılan Redis belleği", "Redis memory in use")) + "',progress:pressure==='CRITICAL'?100:(pressure==='HIGH'||pressure==='WARN'?72:28),facts:[{label:'" + escapeJs(localized(language, "Baskı düzeyi", "Pressure")) + "',value:pressureLabel(pressure)},{label:'" + escapeJs(localized(language, "Profil", "Profile")) + "',value:runtimeProfileName(profile)},{label:'" + escapeJs(localized(language, "Sağlık", "Health")) + "',value:String(health.status||'-')}],actionText:memoryTone==='danger'?'" + escapeJs(localized(language, "Profil değişimini ve bellek eğrisini birlikte kontrol et. Sürekli artış varsa yükten bağımsız anahtar birikimi olabilir.", "Inspect profile changes together with the memory trend. If memory keeps rising, keys may be accumulating beyond transient load.")) + "':'" + escapeJs(localized(language, "Bellek değeri tek başına yorumlanmaz; trend ve baskı düzeyiyle birlikte okunur.", "Do not read memory alone; interpret it together with the trend and pressure level.")) + "'});"
                + "const incidentTone=criticalCount>0?'danger':(warningCount>0||health.status==='DEGRADED'?'warning':'success');widgets.push({title:'" + escapeJs(localized(language, "Olay ve Sinyaller", "Incidents & Signals")) + "',tone:incidentTone,statusLabel:criticalCount>0?'" + escapeJs(localized(language, "Kritik olay açık", "Critical open")) + "':(warningCount>0?'" + escapeJs(localized(language, "İzlemeye değer", "Watch")) + "':'" + escapeJs(localized(language, "Sakin", "Quiet")) + "'),summary:'" + escapeJs(localized(language, "Şu anda operasyonu etkileyen açık olay ve sinyal yoğunluğunu özetler.", "Summarizes the current volume of open incidents and signals affecting operations.")) + "',primaryValue:String(incidentCount),secondaryValue:'" + escapeJs(localized(language, "açık olay", "open incidents")) + "',progress:criticalCount>0?100:(warningCount>0?58:12),facts:[{label:'" + escapeJs(localized(language, "Kritik", "Critical")) + "',value:String(criticalCount)},{label:'" + escapeJs(localized(language, "Uyarı", "Warning")) + "',value:String(warningCount)},{label:'" + escapeJs(localized(language, "Sağlık", "Health")) + "',value:String(health.status||'-')}],actionText:incidentTone==='danger'?'" + escapeJs(localized(language, "Önce kritik olay koduna bak, ardından Önceliklendirme Özeti ve Olaylar bölümüne geç.", "Start with the critical incident code, then open Triage and Incidents.")) + "':'" + escapeJs(localized(language, "Kritik olay yoksa bu kutu genel sakinliği gösterir. Trend bozuluyorsa alt bölümlere inmek gerekir.", "If no critical incident is open, this widget reflects overall calm. Drill down if the trend starts to deteriorate.")) + "'});"
                + "const deliveryTone=failed>0||dropped>0?'warning':(delivered>0?'success':'neutral');widgets.push({title:'" + escapeJs(localized(language, "Uyarı Teslimatı", "Alert Delivery")) + "',tone:deliveryTone,statusLabel:failed>0||dropped>0?'" + escapeJs(localized(language, "Teslimatta sorun var", "Delivery issue")) + "':(delivered>0?'" + escapeJs(localized(language, "Teslim ediyor", "Delivering")) + "':'" + escapeJs(localized(language, "Beklemede", "Idle")) + "'),summary:'" + escapeJs(localized(language, "Webhook, kuyruk, SMTP ve teslim başarısız kayıt kuyruğu rotalarının toplam teslim davranışını özetler.", "Summarizes the combined delivery behavior across webhook, queue, SMTP, and the delivery dead-letter route.")) + "',primaryValue:String(delivered),secondaryValue:'" + escapeJs(localized(language, "başarılı teslim", "successful deliveries")) + "',progress:delivered>0?Math.min(100,20+delivered):((failed+dropped)>0?65:10),facts:[{label:'" + escapeJs(localized(language, "Hata", "Failed")) + "',value:String(failed)},{label:'" + escapeJs(localized(language, "Düşen", "Dropped")) + "',value:String(dropped)},{label:'" + escapeJs(localized(language, "Rota sayısı", "Routes")) + "',value:String(routeCount)}],actionText:(failed+dropped)>0?'" + escapeJs(localized(language, "Uyarı Akışı bölümünde hedefi, yeniden deneme kuralını ve son teslim durumunu birlikte kontrol et.", "Check target, retry policy, and last delivery state together in Alert Delivery.")) + "':'" + escapeJs(localized(language, "Teslim tarafı sessizse bu alan genelde sağlıklıdır; test sırasında sayıların hareket etmesi normaldir.", "If delivery is quiet, this area is generally healthy; moving counts during tests is expected.")) + "'});"
                + "const runtimeTone=profile==='AGGRESSIVE'||pressure==='HIGH'||pressure==='CRITICAL'?'warning':'success';widgets.push({title:'" + escapeJs(localized(language, "Çalışma Profili", "Runtime Posture")) + "',tone:runtimeTone,statusLabel:runtimeTone==='warning'?'" + escapeJs(localized(language, "Baskıya uyum sağlıyor", "Adapting")) + "':'" + escapeJs(localized(language, "Dengede", "Balanced")) + "',summary:'" + escapeJs(localized(language, "Aktif profil, baskı düzeyi ve önceliklendirme sonucu birlikte okunur.", "Read the active profile together with pressure level and triage outcome.")) + "',primaryValue:runtimeProfileName(profile),secondaryValue:'" + escapeJs(localized(language, "aktif çalışma profili", "active runtime profile")) + "',progress:profile==='AGGRESSIVE'?88:(profile==='BALANCED'?54:30),facts:[{label:'" + escapeJs(localized(language, "Baskı düzeyi", "Pressure")) + "',value:pressureLabel(pressure)},{label:'" + escapeJs(localized(language, "Önceliklendirme", "Triage")) + "',value:String(triage.overallStatus||'-')},{label:'" + escapeJs(localized(language, "Darboğaz", "Bottleneck")) + "',value:triageBottleneckLabel(triage.primaryBottleneck)}],actionText:runtimeTone==='warning'?'" + escapeJs(localized(language, "Profil agresife geçtiyse neden çoğu zaman bellek ya da backlog baskısıdır. Alt trendleri birlikte izle.", "If the profile turned aggressive, backlog or memory pressure is usually the reason. Watch the lower trends together.")) + "':'" + escapeJs(localized(language, "Profil sabitse sistem yükü daha öngörülebilir davranıyor demektir.", "If the profile is stable, system load is behaving more predictably.")) + "'});"
                + "const projectionTone=projectionDlq>0?'danger':((projectionPending>0||projectionRecentErrorAt>0)?'warning':'success');widgets.push({title:'" + escapeJs(localized(language, "Projection Refresh", "Projection Refresh")) + "',tone:projectionTone,statusLabel:projectionDlq>0?'" + escapeJs(localized(language, "Poison queue aktif", "Poison queue active")) + "':(projectionTone==='warning'?'" + escapeJs(localized(language, "Yetişmeye çalışıyor", "Catching up")) + "':'" + escapeJs(localized(language, "Temiz", "Clear")) + "'),summary:'" + escapeJs(localized(language, "Projection refresh backlog, poison queue ve replay ilerlemesini tek kutuda özetler.", "Summarizes projection refresh backlog, poison queue, and replay progress in one place.")) + "',primaryValue:String(projectionDlq>0?projectionDlq:projectionPending),secondaryValue:projectionDlq>0?'" + escapeJs(localized(language, "poison queue kaydı", "entries in poison queue")) + "':'" + escapeJs(localized(language, "bekleyen refresh olayı", "pending refresh events")) + "',progress:projectionDlq>0?100:(projectionPending>0?Math.min(100,Math.max(18,(projectionPending/200)*100)):8),facts:[{label:'" + escapeJs(localized(language, "Bekleyen", "Pending")) + "',value:String(projectionPending)},{label:'" + escapeJs(localized(language, "Replay edilen", "Replayed")) + "',value:String(projectionReplayed)},{label:'" + escapeJs(localized(language, "İşlenen", "Processed")) + "',value:String(projectionProcessed)},{label:'" + escapeJs(localized(language, "Son hata", "Last error")) + "',value:projectionErrorType||'-'}],actionText:projectionDlq>0?'" + escapeJs(localized(language, "Projection Refresh bölümünü açıp poison queue kayıtlarını replay et veya incele. Aksi halde read model'ler stale kalabilir.", "Open Projection Refresh and replay or inspect poison entries before read models drift stale.")) + "':'" + escapeJs(localized(language, "Bekleyen refresh sayısı yükseliyor veya yakın zamanlı hata görünüyorsa stale read model yayılmadan önce Projection Refresh bölümüne in.", "If pending refreshes rise or a recent error appears, inspect Projection Refresh before stale read models spread.")) + "'});"
                + "const workerTone=recentErrorCount>0?'danger':'success';widgets.push({title:'" + escapeJs(localized(language, "Arka Plan Hata Lambası", "Background Error Lamp")) + "',tone:workerTone,statusLabel:recentErrorCount>0?'" + escapeJs(localized(language, "Son dakika içinde hata var", "Recent error present")) + "':'" + escapeJs(localized(language, "Temiz", "Clear")) + "',summary:'" + escapeJs(localized(language, "Gizli kalan worker hatalarını exception, kök neden ve kaynak metotla birlikte açar.", "Exposes hidden worker failures together with the exception, root cause, and originating method.")) + "',primaryValue:String(recentErrorCount),secondaryValue:'" + escapeJs(localized(language, "yakın zamanlı worker hatası", "recent worker errors")) + "',progress:recentErrorCount>0?100:8,facts:[{label:'" + escapeJs(localized(language, "İlk worker", "First worker")) + "',value:firstRecentError?workerNameLabel(firstRecentError.workerName):'-'},{label:'" + escapeJs(localized(language, "Exception", "Exception")) + "',value:firstRecentError?(firstRecentError.rootErrorType||'-'):'-'},{label:'" + escapeJs(localized(language, "Kaynak", "Origin")) + "',value:firstRecentError?(firstRecentError.origin||'-'):'-'}],actionText:recentErrorCount>0?'" + escapeJs(localized(language, "Arka Plan Hataları bölümüne in; exception mesajını, kök nedeni ve stack trace'i birlikte oku.", "Open Background Worker Errors and inspect the exception message, root cause, and stack trace together.")) + "':'" + escapeJs(localized(language, "Son gözlem penceresinde worker hatası görünmüyor.", "No worker error is visible in the current observation window.")) + "'});"
                + "target.innerHTML=widgets.map(signalWidget).join('');}"
                + "function filterTaxonomy(){const input=document.getElementById('taxonomySearch');if(!input){return;}const q=(input.value||'').trim().toLowerCase();const cards=[...document.querySelectorAll('.taxonomy-card')];let visible=0;cards.forEach(card=>{const haystack=(card.dataset.search||'').toLowerCase();const show=!q||haystack.includes(q);card.classList.toggle('d-none',!show);if(show){visible++;}});const count=document.getElementById('taxonomyCount');if(count){count.textContent=String(visible);}const empty=document.getElementById('taxonomyEmptyState');if(empty){empty.classList.toggle('d-none',visible!==0);}}"
                + "function activateNavLink(targetId){document.querySelectorAll('.nav-tree-link').forEach(link=>link.classList.toggle('active',link.dataset.target===targetId));}"
                + "function scrollToSection(target){if(!target){return;}const navbar=document.querySelector('.navbar');const offset=(navbar?navbar.getBoundingClientRect().height:0)+18;const top=window.scrollY+target.getBoundingClientRect().top-offset;window.scrollTo({top:Math.max(0,top),behavior:'smooth'});}"
                + "function revealWorkspaceTarget(targetId){activateNavLink(targetId);window.requestAnimationFrame(()=>window.requestAnimationFrame(()=>{const target=document.getElementById(targetId);if(target){scrollToSection(target);}}));}"
                + "function workerNameLabel(name){const labels={'write-behind-worker':'" + escapeJs(localized(language, "Arka plan yazma işleyicisi", "Write-behind worker")) + "','dead-letter-recovery-worker':'" + escapeJs(localized(language, "Başarısız kayıt kurtarma işleyicisi", "Dead-letter recovery worker")) + "','recovery-cleanup-worker':'" + escapeJs(localized(language, "Kurtarma temizleme işleyicisi", "Recovery cleanup worker")) + "','projection-refresh-worker':'" + escapeJs(localized(language, "Projection refresh işleyicisi", "Projection refresh worker")) + "','incident-delivery-recovery-worker':'" + escapeJs(localized(language, "Olay teslimi kurtarma işleyicisi", "Incident delivery recovery worker")) + "','admin-report-job-worker':'" + escapeJs(localized(language, "Yönetim raporu işleyicisi", "Admin report job worker")) + "'};return labels[String(name||'').trim()]||String(name||'-');}"
                + "function backgroundErrorStatusLabel(item){if(!item){return '-';}return item.recent?'" + escapeJs(localized(language, "Yakın zamanlı hata", "Recent error")) + "':'" + escapeJs(localized(language, "Geçmiş hata izi", "Historical error")) + "';}"
                + "function backgroundErrorExceptionCell(item){const top=item.rootErrorType||item.errorType||'-';const nested=(item.rootErrorType&&item.errorType&&item.rootErrorType!==item.errorType)?'<div class=\"small text-muted mono\">'+escapeText(item.errorType)+'</div>':'';return '<div class=\"d-flex flex-column gap-1\"><span class=\"mono\">'+escapeText(top)+'</span>'+nested+'</div>';}"
                + "function backgroundErrorMessageCell(item){const top=item.rootErrorMessage||item.errorMessage||'-';const nested=(item.rootErrorMessage&&item.errorMessage&&item.rootErrorMessage!==item.errorMessage)?'<div class=\"small text-muted\">'+escapeText(item.errorMessage)+'</div>':'';return '<div class=\"d-flex flex-column gap-1\"><span>'+escapeText(top)+'</span>'+nested+'</div>';}"
                + "function backgroundErrorStackCell(item){const stack=String(item.stackTrace||'').trim();if(!stack){return '<span class=\"text-muted\">-</span>';}return '<details class=\"stack-preview\"><summary>" + escapeJs(localized(language, "Stack trace'i aç", "Open stack trace")) + "</summary><pre class=\"mb-0 small mono\">'+escapeText(stack)+'</pre></details>';}"
                + "function serviceSummaryCell(item,index){return '<button type=\"button\" class=\"btn btn-link p-0 text-start text-decoration-none\" data-service-entry=\"'+String(index)+'\">'+titled(serviceNameLabel(item.serviceName),36,false)+'</button>';}"
                + "function renderBackgroundErrors(backgroundErrors){const items=Array.isArray(backgroundErrors.items)?backgroundErrors.items:[];rows('backgroundErrorRows',items.map(item=>[titled(workerNameLabel(item.workerName),36,false),badge(item.recent?'DEGRADED':'UP')+' <span class=\"small ms-1\">'+escapeText(backgroundErrorStatusLabel(item))+'</span>',titled(item.lastErrorAtEpochMillis?new Date(Number(item.lastErrorAtEpochMillis)).toLocaleString():'-',32,false),backgroundErrorExceptionCell(item),titled(item.origin||'-',96,true),backgroundErrorMessageCell(item),backgroundErrorStackCell(item)]),'" + escapeJs(localized(language, "Henüz görünür arka plan hatası yok.", "No background worker error is currently visible.")) + "',7);}"
                + "function projectionRefreshStatusLabel(snapshot){const current=snapshot||dashboardDefaults.pr;const dlq=Number(current.deadLetterStreamLength)||0;const pending=Number(current.pendingCount)||0;const lastErrorAt=Number(current.lastErrorAtEpochMillis)||0;const lagMs=Number(current.lagEstimateMillis)||0;if(dlq>0){return '" + escapeJs(localized(language, "Poison queue aktif", "Poison queue active")) + "';}if(lastErrorAt>0){return '" + escapeJs(localized(language, "Yakın zamanlı hata var", "Recent error present")) + "';}if(pending>0){return lagMs>0?'" + escapeJs(localized(language, "Bekleyen refresh olayları var, gecikme ölçülüyor", "Pending refresh events present, lag observed")) + "':'" + escapeJs(localized(language, "Bekleyen refresh olayları var", "Pending refresh events present")) + "';}return '" + escapeJs(localized(language, "Akış temiz", "Pipeline clear")) + "';}"
                + "function projectionRefreshErrorCell(item){const type=item.errorRootType||item.errorType||'-';const message=item.errorRootMessage||item.errorMessage||'-';const origin=item.errorOrigin||'';const messageLine=message&&message!=='-'?'<div class=\"small text-muted\">'+escapeText(clip(message,120))+'</div>':'';const originLine=origin?'<div class=\"small text-muted mono\">'+escapeText(origin)+'</div>':'';return '<div class=\"d-flex flex-column gap-1\"><span class=\"mono\">'+escapeText(type)+'</span>'+messageLine+originLine+'</div>';}"
                + "function projectionRefreshActionCell(item){const entryId=String(item&&item.entryId||'').trim();if(!entryId){return '<span class=\"text-muted\">-</span>';}return '<button type=\"button\" class=\"btn btn-sm btn-outline-primary\" data-projection-refresh-replay=\"'+escapeAttr(entryId)+'\">" + escapeJs(localized(language, "Replay", "Replay")) + "</button>';}"
                + "function renderProjectionRefreshSection(snapshot,failures){const current=snapshot||dashboardDefaults.pr;const items=Array.isArray(failures&&failures.items)?failures.items:[];setMetricValue('projectionRefreshSectionPending',current.pendingCount,'count');setMetricValue('projectionRefreshSectionDlq',current.deadLetterStreamLength,'count');setMetricValue('projectionRefreshSectionStream',current.streamLength,'count');setMetricValue('projectionRefreshSectionReplayed',current.replayedCount,'count');const statusValue=(Number(current.deadLetterStreamLength)||0)>0?'DEGRADED':(((Number(current.pendingCount)||0)>0||(Number(current.lastErrorAtEpochMillis)||0)>0)?'DEGRADED':'UP');document.getElementById('projectionRefreshPipelineStatus').innerHTML=badge(statusValue)+' <span class=\"small ms-1\">'+escapeText(projectionRefreshStatusLabel(current))+'</span>';document.getElementById('projectionRefreshLastProcessed').textContent=(Number(current.lastProcessedAtEpochMillis)||0)>0?new Date(Number(current.lastProcessedAtEpochMillis)).toLocaleString():'-';document.getElementById('projectionRefreshLagEstimate').textContent=String(current.backlogPresent)==='true'?formatDurationMillis(current.lagEstimateMillis):'-';document.getElementById('projectionRefreshLastPoison').textContent=current.lastPoisonEntryId?clip(String(current.lastPoisonEntryId),48):'-';const lastErrorType=current.lastErrorRootType||current.lastErrorType||'';const lastErrorMessage=current.lastErrorRootMessage||current.lastErrorMessage||'';document.getElementById('projectionRefreshLastError').textContent=(Number(current.lastErrorAtEpochMillis)||0)>0?clip((lastErrorType||'-')+(lastErrorMessage?' / '+lastErrorMessage:''),120):'-';const replayStatus=document.getElementById('projectionRefreshReplayStatus');if(replayStatus&&!String(replayStatus.textContent||'').trim()){replayStatus.textContent='" + escapeJs(localized(language, "Projection refresh replay araçları hazır.", "Projection refresh replay tools are ready.")) + "';}rows('projectionRefreshFailureRows',items.map(item=>[titled(item.failedAtEpochMillis?new Date(Number(item.failedAtEpochMillis)).toLocaleString():'-',28,false),titled(item.projectionName||'-',28,false),titled(item.entityName||'-',24,false),titled(item.operation||'-',18,true),String(Number(item.attempt)||0),projectionRefreshErrorCell(item),titled(item.errorOrigin||'-',72,true),projectionRefreshActionCell(item)]),'" + escapeJs(localized(language, "Projection refresh poison queue şu anda boş.", "Projection refresh poison queue is currently empty.")) + "',8);}"
                + "async function replayProjectionRefreshFailure(entryId){const status=document.getElementById('projectionRefreshReplayStatus');if(status){status.textContent='" + escapeJs(localized(language, "Projection refresh replay başlatılıyor...", "Starting projection refresh replay...")) + "';}try{const controller=new AbortController();const timeout=setTimeout(()=>controller.abort(),8000);let result;try{const response=await fetch(adminApi('/api/projection-refresh/replay?entryId='+encodeURIComponent(entryId)),fetchOptions('POST',controller.signal));const text=await response.text();if(!response.ok){throw new Error(text);}result=JSON.parse(text);}finally{clearTimeout(timeout);}if(status){status.textContent=result.message||'" + escapeJs(localized(language, "Replay tamamlandı.", "Replay completed.")) + "';}window.__dashboardVisibleLazySections['projection-refresh-section']=true;delete window.__dashboardLazyLoadedKeys.pr;delete window.__dashboardLazyLoadedKeys.prFailed;delete window.__dashboardDataCache.pr;delete window.__dashboardDataCache.prFailed;await refresh();}catch(e){const message=(e&&e.name==='AbortError')?'timeout: projection refresh replay':(e&&e.message?e.message:String(e));if(status){status.textContent='" + escapeJs(localized(language, "Replay başarısız: ", "Replay failed: ")) + "'+message;}document.getElementById('explain').textContent=message;}}"
                + "function diagnosticsSummaryCell(item){return '<button type=\"button\" class=\"btn btn-link p-0 text-start text-decoration-none\" data-diagnostic-entry=\"'+escapeAttr(item.entryId||'')+'\">'+titled(item.note||'-',88,false)+'</button>';}"
                + "function renderDiagnosticDetail(item){const host=document.getElementById('diagnosticsDetail');if(!host){return;}if(!item){host.textContent='" + escapeJs(localized(language, "Henüz bir kayıt seçilmedi.", "No record selected yet.")) + "';return;}const detail={entryId:item.entryId,source:item.source,status:item.status,recordedAt:item.recordedAt,note:item.note,fields:item.fields||{}};host.textContent=JSON.stringify(detail,null,2);}"
                + "function renderDiagnosticsRecords(diagnostics){const items=Array.isArray(diagnostics.items)?diagnostics.items:[];window.__diagnosticsById={};items.forEach(item=>window.__diagnosticsById[item.entryId]=item);rows('diagnosticsRows',items.map(item=>[titled(item.recordedAt,28,false),titled(item.source||'-',24,true),badge(item.status||'UP'),diagnosticsSummaryCell(item)]),'" + escapeJs(localized(language, "Henüz tanılama kaydı yok.", "No diagnostic record is currently stored.")) + "',4);renderDiagnosticDetail(items[0]||null);}"
                + "function diagnosticsSummaryCell(item){return '<button type=\"button\" class=\"btn btn-link p-0 text-start text-decoration-none\" data-diagnostic-entry=\"'+escapeAttr(item.entryId||'')+'\">'+titled(item.note||'-',88,false)+'</button>';}"
                + "function renderDiagnosticDetail(item){const host=document.getElementById('diagnosticsDetail');if(!host){return;}if(!item){host.textContent='" + escapeJs(localized(language, "Henüz bir kayıt seçilmedi.", "No record selected yet.")) + "';return;}const detail={entryId:item.entryId,source:item.source,status:item.status,recordedAt:item.recordedAt,note:item.note,fields:item.fields||{}};host.textContent=JSON.stringify(detail,null,2);}"
                + "function renderDiagnosticsRecords(diagnostics){const items=Array.isArray(diagnostics.items)?diagnostics.items:[];window.__diagnosticsById={};items.forEach(item=>window.__diagnosticsById[item.entryId]=item);rows('diagnosticsRows',items.map(item=>[titled(item.recordedAt,28,false),titled(item.source||'-',24,true),badge(item.status||'UP'),diagnosticsSummaryCell(item)]),'" + escapeJs(localized(language, "Henüz tanılama kaydı yok.", "No diagnostic record is currently stored.")) + "',4);renderDiagnosticDetail(items[0]||null);}"
                + "function bindIfPresent(id,eventName,handler){const node=document.getElementById(id);if(node){node.addEventListener(eventName,handler);}}"
                + "function refreshNowAction(){refresh().catch(()=>{});}"
                + "function toggleRefreshAction(){const sel=document.getElementById('refreshInterval');if(refreshPaused){sel.value='" + uiInt("defaultRefreshMillis", 5000) + "';}else{sel.value='0';}applyRefreshInterval();}"
                + "function setRuntimeProfileButtonState(activeProfile,mode){[['runtimeProfileAuto','AUTO'],['runtimeProfileStandard','STANDARD'],['runtimeProfileBalanced','BALANCED'],['runtimeProfileAggressive','AGGRESSIVE']].forEach(([id,profile])=>{const btn=document.getElementById(id);if(!btn){return;}const active=(profile==='AUTO'&&mode==='AUTO')||(profile===activeProfile&&mode==='MANUAL');btn.className='btn btn-sm '+(active?'btn-danger':'btn-outline-secondary');});}"
                + "async function setRuntimeProfile(profile){const status=document.getElementById('runtimeProfileStatus');status.textContent='" + escapeJs(uiString("runtimeProfile.statusLoading", "Updating runtime profile...")) + "';try{const result=await postJson('/api/runtime-profile?profile='+encodeURIComponent(profile));status.textContent='" + escapeJs(uiString("runtimeProfile.statusSuccessPrefix", "Runtime profile updated: ")) + "'+result.activeProfile+' / '+result.mode;await refresh();}catch(e){status.textContent='" + escapeJs(uiString("runtimeProfile.statusErrorPrefix", "Runtime profile update failed: ")) + "'+e.message;document.getElementById('explain').textContent=e.message;}}"
                + "function renderDashboardFromCache(failures,hotResult){const h=dashboardData('h');const m=dashboardData('m');const sig=dashboardData('sig');const perf=dashboardData('perf');const perfHistory=dashboardData('perfHistory');const shape=dashboardData('shape');const t=dashboardData('t');const svc=dashboardData('svc');const bg=dashboardData('bg');const diagRecords=dashboardData('diagRecords');const pr=dashboardData('pr');const prFailed=dashboardData('prFailed');const route=dashboardData('route');const routeHistory=dashboardData('routeHistory');const rb=dashboardData('rb');const i=dashboardData('i');const incidentTrend=dashboardData('incidentTrend');const failingSignals=dashboardData('failingSignals');const c=dashboardData('c');const d=dashboardData('d');const s=dashboardData('s');const sh=dashboardData('sh');const ddl=dashboardData('ddl');const p=dashboardData('p');const r=dashboardData('r');const rp=dashboardData('rp');const tuning=dashboardData('tuning');const history=dashboardData('history');const projectionRefresh=(pr&&Object.keys(pr).length)?pr:(m.projectionRefreshSnapshot||dashboardDefaults.m.projectionRefreshSnapshot);"
                + "setMetricValue('wb',m.writeBehindStreamLength,'count');setMetricValue('dlq',m.deadLetterStreamLength,'count');setMetricValue('diag',m.diagnosticsStreamLength,'count');"
                + "setMetricValue('projectionRefreshDlq',projectionRefresh.deadLetterStreamLength,'count');"
                + "setMetricValue('inc',i.items.length,'count');setMetricValue('learned',m.plannerStatisticsSnapshot.learnedStatisticsKeyCount,'count');"
                + "setMetricValue('redisMem',m.redisGuardrailSnapshot.usedMemoryBytes,'bytes');setMetricValue('compactionPending',m.redisGuardrailSnapshot.compactionPendingCount,'count');"
                + "setMetricValue('projectionRefreshPending',projectionRefresh.pendingCount,'count');"
                + "setMetricText('runtimeProfile',runtimeProfileName(m.redisRuntimeProfileSnapshot.activeProfile)+' / '+pressureLabel(m.redisRuntimeProfileSnapshot.lastObservedPressureLevel));"
                + "setMetricValue('alertDelivered',sig.alertDeliveredCount,'count');"
                + "setMetricValue('alertFailed',sig.alertFailedCount,'count');"
                + "setMetricValue('alertDropped',sig.alertDroppedCount,'count');"
                + "setMetricValue('criticalSignals',sig.criticalIncidentCount,'count');"
                + "setMetricValue('warningSignals',sig.warningIncidentCount,'count');"
                + "document.getElementById('lastUpdated').textContent=new Date().toLocaleTimeString();renderPerformanceSection(perf,perfHistory,shape);renderHistory(history);renderSignalDashboard(m,h,sig);"
                + "renderRouteTrendSvg('alertDeliveryTrendSvg',routeHistory.items||[],'deliveredCount');renderRouteTrendSvg('alertFailureTrendSvg',routeHistory.items||[],'failedCount');"
                + "renderIncidentSeverityTrendSvg(incidentTrend.items||[]);renderFailingSignals(failingSignals.items||[]);"
                + "document.getElementById('status').outerHTML=badge(h.status).replace('span class=\"badge','span id=\"status\" class=\"badge');"
                + "const healthReason=summarizeHealthReason(h,t,bg);document.getElementById('healthReasonTitle').textContent=healthReason.title;document.getElementById('healthReasonBody').textContent=healthReason.body;document.getElementById('healthReasonAction').textContent=healthReason.action;"
                + "const healthGuide=summarizeHealthGuidance(h,t,bg);document.getElementById('healthGuideFirstFix').textContent=healthGuide.firstFix;document.getElementById('healthGuideCheckArea').textContent=healthGuide.checkArea;document.getElementById('healthGuideLikelyCause').textContent=healthGuide.likelyCause;"
                + "document.getElementById('triageStatus').outerHTML=badge(t.overallStatus).replace('span class=\"badge','span id=\"triageStatus\" class=\"badge');"
                + "document.getElementById('triageBottleneck').textContent='" + escapeJs(localized(language, "Ana darboğaz: ", "Primary bottleneck: ")) + "'+triageBottleneckLabel(t.primaryBottleneck);document.getElementById('triageCause').textContent=triageCauseLabel(t.suspectedCause);"
                + "const triageGuide=summarizeTriageGuidance(t,bg);document.getElementById('triageGuideFirstFix').textContent=triageGuide.firstFix;document.getElementById('triageGuideCheckArea').textContent=triageGuide.checkArea;document.getElementById('triageGuideLikelyCause').textContent=triageGuide.likelyCause;"
                + "rows('triageEvidenceRows',(t.evidence||[]).map(v=>['<span class=\"mono\">'+v+'</span>']),'" + escapeJs(uiString("empty.triageEvidence", "No triage evidence")) + "');"
                + "window.__servicesByIndex=Array.isArray(svc.items)?svc.items:[];rows('serviceRows',svc.items.map((v,index)=>[serviceSummaryCell(v,index),badge(v.status),titled(serviceSignalLabel(v.keySignal),28,true),titled(serviceDetailLabel(v.detail),72,false)]),'" + escapeJs(uiString("empty.services", "No services")) + "');renderServiceGuide((svc.items||[])[0]||null);"
                + "const backgroundGuide=summarizeBackgroundErrorGuidance(bg);document.getElementById('backgroundGuideFirstFix').textContent=backgroundGuide.firstFix;document.getElementById('backgroundGuideCheckArea').textContent=backgroundGuide.checkArea;document.getElementById('backgroundGuideLikelyCause').textContent=backgroundGuide.likelyCause;"
                + "renderBackgroundErrors(bg);renderDiagnosticsRecords(diagRecords);renderProjectionRefreshSection(projectionRefresh,prFailed);"
                + "rows('issues',h.issues.map(v=>[titled(serviceDetailLabel(v),120,false)]),'" + escapeJs(uiString("empty.activeIssues", "No active issues")) + "');rows('incidents',i.items.map(v=>[badge(v.severity),titled(v.code,28,true),titled(v.description,72,false)]),'" + escapeJs(uiString("empty.activeIncidents", "No active incidents")) + "');"
                + "rows('churnRows',c.items.map(v=>[v.recordedAt,runtimeProfileName(v.fromProfile)+'→'+runtimeProfileName(v.toProfile),pressureLabel(v.pressureLevel)]),'" + escapeJs(uiString("empty.profileSwitches", "No profile switches")) + "');renderChurnSvg(c.items);"
                + "rows('deploymentRows',[[d.schemaBootstrapMode,d.schemaAutoApplyOnStart?'" + escapeJs(uiString("deployment.autoApplyLabel", "auto-apply")) + "':'" + escapeJs(uiString("deployment.manualLabel", "manual")) + "'],[d.writeBehindEnabled?'" + escapeJs(uiString("deployment.writeBehindOnLabel", "write-behind on")) + "':'" + escapeJs(uiString("deployment.writeBehindOffLabel", "write-behind off")) + "',d.writeBehindWorkerThreads+'" + escapeJs(uiString("deployment.workersSuffix", " workers")) + "'],[d.durableCompactionEnabled?'" + escapeJs(uiString("deployment.durableCompactionLabel", "durable compaction")) + "':'" + escapeJs(uiString("deployment.noCompactionLabel", "no compaction")) + "',d.activeWriteStreamCount+'" + escapeJs(uiString("deployment.activeStreamsSuffix", " active streams")) + "'],[d.redisGuardrailsEnabled?'" + escapeJs(uiString("deployment.guardrailsOnLabel", "guardrails on")) + "':'" + escapeJs(uiString("deployment.guardrailsOffLabel", "guardrails off")) + "',d.automaticRuntimeProfileSwitchingEnabled?'" + escapeJs(uiString("deployment.autoProfileSwitchLabel", "auto profile switch")) + "':'" + escapeJs(uiString("deployment.manualProfileLabel", "manual profile")) + "'],['" + escapeJs(uiString("deployment.keyPrefixLabel", "key prefix")) + "','<span class=\"mono\">'+d.keyPrefix+'</span>']],'" + escapeJs(uiString("empty.deploymentData", "No deployment data")) + "');"
                + "rows('schemaRows',[[s.bootstrapMode,s.validationSucceeded?badge('UP'):badge('DEGRADED')],['" + escapeJs(uiString("schema.migrationStepsLabel", "migration steps")) + "',String(s.migrationStepCount)],['" + escapeJs(uiString("schema.createTableStepsLabel", "create table steps")) + "',String(s.createTableStepCount)],['" + escapeJs(uiString("schema.addColumnStepsLabel", "add column steps")) + "',String(s.addColumnStepCount)],['" + escapeJs(uiString("schema.ddlEntitiesLabel", "ddl entities")) + "',String(s.ddlEntityCount)]],'" + escapeJs(uiString("empty.schemaData", "No schema data")) + "');"
                + "rows('schemaHistoryRows',sh.items.map(v=>[titled(v.recordedAt,24,false),titled(v.operation,24,true),v.success?badge('UP'):badge('DOWN'),titled('" + escapeJs(uiString("schema.stepsPrefix", "steps=")) + "'+v.executedStepCount+'/'+v.stepCount,20,false)]),'" + escapeJs(uiString("empty.migrationHistory", "No migration history")) + "');"
                + "rows('profileRows',p.items.map(v=>[titled(v.name,24,false),titled(v.schemaBootstrapMode,24,true),v.redisGuardrailsEnabled?'" + escapeJs(uiString("profiles.guardrailsEnabledLabel", "guardrails")) + "':'" + escapeJs(uiString("profiles.guardrailsDisabledLabel", "no guardrails")) + "',titled(starterProfileNoteLabel(v.note),72,false)]),'" + escapeJs(uiString("empty.starterProfiles", "No starter profiles")) + "');"
                + "rows('registryRows',r.entities.slice(0,8).map(v=>[titled(v.entityName,24,false),titled(v.tableName,24,true),titled('" + escapeJs(uiString("registry.columnsPrefix", "cols=")) + "'+v.columnCount,18,false),titled('" + escapeJs(uiString("registry.hotPrefix", "hot=")) + "'+v.hotEntityLimit,18,false),titled('" + escapeJs(uiString("registry.pagePrefix", "page=")) + "'+v.pageSize,18,false)]),'" + escapeJs(uiString("empty.registeredEntities", "No registered entities")) + "');"
                + "document.getElementById('runtimeProfileActive').textContent=runtimeProfileName(rp.activeProfile);document.getElementById('runtimeProfileMode').innerHTML=badge(rp.mode==='MANUAL'?'DEGRADED':'UP');document.getElementById('runtimeProfilePressure').innerHTML=badge((rp.lastObservedPressureLevel||'NORMAL')==='CRITICAL'?'DOWN':((rp.lastObservedPressureLevel||'NORMAL')==='WARN'?'DEGRADED':'UP'));document.getElementById('runtimeProfileSwitchCount').textContent=String(rp.switchCount||0);document.getElementById('runtimeProfileLastSwitched').textContent=(rp.lastSwitchedAtEpochMillis&&Number(rp.lastSwitchedAtEpochMillis)>0)?new Date(Number(rp.lastSwitchedAtEpochMillis)).toLocaleString():'-';document.getElementById('runtimeProfileManualOverride').textContent=rp.manualOverrideProfile?runtimeProfileName(rp.manualOverrideProfile):'-';rows('runtimeProfilePropertyRows',(rp.properties||[]).map(v=>[runtimeProfilePropertyCell(v),titled(v.value,24,true),titled(runtimeProfileUnitLabel(v.unit),18,false),runtimeProfileDescriptionCell(v)]),'" + escapeJs(uiString("empty.runtimeProfileProperties", "No runtime profile properties")) + "',4);setRuntimeProfileButtonState(rp.activeProfile,rp.mode);"
                + "document.getElementById('tuningCapturedAt').textContent=tuning.capturedAt||'-';document.getElementById('tuningOverrideCount').textContent=String(tuning.explicitOverrideCount||0);document.getElementById('tuningEntryCount').textContent=String(tuning.entryCount||0);"
                + "rows('tuningRows',(tuning.items||[]).map(v=>[titled(tuningGroupLabel(v.group),24,false),tuningPropertyCell(v),titled(v.value,56,true),'<span title=\"'+escapeAttr(String(v.source||''))+'\">'+badge(v.source==='explicit override'?'DEGRADED':'UP')+' <span class=\"small ms-1\">'+escapeText(tuningSourceLabel(v.source))+'</span></span>',tuningDescriptionCell(v)]),'" + escapeJs(uiString("empty.tuning", "No tuning data")) + "');"
                + "rows('alertRouteRows',route.items.map(v=>[titled(routeNameLabel(v.routeName),28,false),badge(v.status),badge(v.escalationLevel==='CRITICAL'?'DOWN':(v.escalationLevel==='WARNING'?'DEGRADED':'UP')),titled(v.target,32,true),String(v.deliveredCount||0),String(v.failedCount||0),String(v.droppedCount||0),titled(routeRetryPolicyLabel(v.retryPolicy),40,false),titled((v.lastErrorType||'')||(v.lastDeliveredAtEpochMillis>0?'" + escapeJs(uiString("alertRouting.deliveredLabel", "delivered")) + "':'" + escapeJs(uiString("alertRouting.idleLabel", "idle")) + "'),40,false)]),'" + escapeJs(uiString("empty.alertRoutes", "No alert routes")) + "');"
                + "rows('alertRouteHistoryRows',(routeHistory.items||[]).slice(-16).reverse().map(v=>[titled(v.recordedAt,24,false),titled(routeNameLabel(v.routeName),28,false),badge(v.status),String(v.deliveredCount),String(v.failedCount),titled(v.lastErrorType||'',48,false)]),'" + escapeJs(uiString("empty.routeHistory", "No route history")) + "');"
                + "rows('runbookRows',rb.items.map(v=>[titled(v.code,20,true),titled(runbookTitleLabel(v.title),36,false),titled(runbookActionLabel(v.firstAction),72,false),titled(v.reference,28,true)]),'" + escapeJs(uiString("empty.runbooks", "No runbooks")) + "');"
                + "document.getElementById('schemaDdl').textContent=(ddl.items||[]).slice(0,8).map(v=>v.entityName+'\\n'+v.sql).join('\\n\\n');window.__dashboardHasLoadedOnce=true;if(hotResult&&hotResult.ok===false){const detail=hasDashboardCachedData()?'" + escapeJs(uiString("dashboardDataStatus.cachedHotFallbackDetail", "Canlı özet gecikse de son iyi görüntü gösteriliyor.")) + "':'" + escapeJs(uiString("dashboardDataStatus.errorDetail", "Yönetim verisi alınamadı. Ağ, tarayıcı konsolu veya admin API durumunu kontrol et.")) + "';setDashboardDataState(hasDashboardCachedData()?'success':'error',detail);}else{setDashboardDataState('success','" + escapeJs(uiString("dashboardDataStatus.successDetail", "Metrikler ve servis durumu başarıyla yenilendi.")) + "');}}"
                + "async function refresh(){if(refreshInFlight){return refreshInFlight;}const coldStart=!window.__dashboardHasLoadedOnce&&!hasDashboardCachedData();if(coldStart){setDashboardDataState('loading');setMetricsLoadingState();}refreshInFlight=(async()=>{const hotResult=await jsonWithTimeout('/api/dashboard/hot','" + escapeJs(localized(language, "Canlı özet", "Hot dashboard snapshot")) + "');if(hotResult.ok){if(!ensureDashboardInstance(hotResult.data&&hotResult.data.meta)){return {hotResult:hotResult,failures:[]};}mergeDashboardData(hotResult.data||{});}renderDashboardFromCache([],hotResult);captureVisibleLazySections();const lazyResult=await loadVisibleLazySections(window.__dashboardHasLoadedOnce);renderDashboardFromCache(lazyResult.failures||[],hotResult);return {hotResult:hotResult,failures:lazyResult.failures||[]};})().catch(e=>{const message=(e&&e.message)?e.message:String(e);setDashboardDataState('error',message);if(!window.__dashboardHasLoadedOnce){setMetricsErrorState(message);}throw e;}).finally(()=>{refreshInFlight=null;});return refreshInFlight;}"
                + "async function loadTuningExport(kind){const status=document.getElementById('tuningExportStatus');const host=document.getElementById('tuningExport');status.textContent='" + escapeJs(uiString("tuning.exportLoading", "Loading export...")) + "';try{let url='/api/tuning/export?format=json';if(kind==='markdown'){url='/api/tuning/export?format=markdown';}if(kind==='flags'){url='/api/tuning/flags';}const r=await fetch(adminApi(url));if(!r.ok)throw new Error(await r.text());const text=await r.text();host.textContent=text;if(kind==='flags' && navigator.clipboard){await navigator.clipboard.writeText(text);status.textContent='" + escapeJs(uiString("tuning.copyFlagsSuccess", "Startup flags copied to clipboard.")) + "';}else if(kind==='markdown'){status.textContent='" + escapeJs(uiString("tuning.exportMarkdownSuccess", "Markdown export loaded below.")) + "';}else{status.textContent='" + escapeJs(uiString("tuning.exportJsonSuccess", "JSON export loaded below.")) + "';}}catch(e){status.textContent='" + escapeJs(uiString("tuning.exportErrorPrefix", "Export failed: ")) + "'+e.message;document.getElementById('explain').textContent=e.message;}}"
                + "function explainParams(format){const p=new URLSearchParams({entity:document.getElementById('entity').value,filter:document.getElementById('filter').value,sort:document.getElementById('sort').value,limit:document.getElementById('limit').value,format:format||'json'});const include=document.getElementById('include').value.trim();if(include)p.append('include',include);return p;}"
                + "async function explain(){const p=explainParams('json');const r=await fetch(adminApi('/api/explain?'+p.toString()));const text=await r.text();if(!r.ok){document.getElementById('explain').textContent=text;throw new Error(text);}const plan=JSON.parse(text);renderExplainResult(plan);}"
                + "async function copyExplainMarkdownAction(){const status=document.getElementById('explainActionStatus');status.textContent='" + escapeJs(uiString("explain.actionLoading", "Processing explain action...")) + "';try{const r=await fetch(adminApi('/api/explain?'+explainParams('markdown').toString()));const text=await r.text();if(!r.ok)throw new Error(text);if(navigator.clipboard){await navigator.clipboard.writeText(text);}document.getElementById('explain').textContent=text;status.textContent='" + escapeJs(uiString("explain.copyMarkdownSuccess", "Explain markdown copied to clipboard.")) + "';}catch(e){status.textContent='" + escapeJs(uiString("explain.actionErrorPrefix", "Explain action failed: ")) + "'+e.message;document.getElementById('explain').textContent=e.message;}}"
                + "async function downloadExplainJsonAction(){const status=document.getElementById('explainActionStatus');status.textContent='" + escapeJs(uiString("explain.actionLoading", "Processing explain action...")) + "';try{const r=await fetch(adminApi('/api/explain?'+explainParams('json').toString()));const text=await r.text();if(!r.ok)throw new Error(text);const blob=new Blob([text],{type:'application/json;charset=utf-8'});const url=URL.createObjectURL(blob);const a=document.createElement('a');a.href=url;a.download=(document.getElementById('entity').value||'entity')+'-explain.json';document.body.appendChild(a);a.click();a.remove();URL.revokeObjectURL(url);document.getElementById('explain').textContent=text;status.textContent='" + escapeJs(uiString("explain.downloadJsonSuccess", "Explain JSON downloaded.")) + "';}catch(e){status.textContent='" + escapeJs(uiString("explain.actionErrorPrefix", "Explain action failed: ")) + "'+e.message;document.getElementById('explain').textContent=e.message;}}"
                + "async function saveExplainNoteAction(){const status=document.getElementById('explainActionStatus');status.textContent='" + escapeJs(uiString("explain.actionLoading", "Processing explain action...")) + "';try{const p=explainParams('json');const title=document.getElementById('explainNoteTitle').value.trim();if(title)p.append('title',title);const r=await fetch(adminApi('/api/explain/note?'+p.toString()),{method:'POST'});const text=await r.text();if(!r.ok)throw new Error(text);const result=JSON.parse(text);status.textContent='" + escapeJs(uiString("explain.saveNoteSuccessPrefix", "Explain note saved: ")) + "'+result.entryId;await refresh();}catch(e){status.textContent='" + escapeJs(uiString("explain.actionErrorPrefix", "Explain action failed: ")) + "'+e.message;document.getElementById('explain').textContent=e.message;}}"
                + "function applyRefreshInterval(){const ms=Number(document.getElementById('refreshInterval').value||0);if(refreshTimer){clearInterval(refreshTimer);refreshTimer=null;}refreshPaused=ms===0;document.getElementById('toggleRefresh').textContent=refreshPaused?'" + escapeJs(uiString("resumeLabel", "Resume")) + "':'" + escapeJs(uiString("pauseLabel", "Pause")) + "';if(ms>0){refreshTimer=setInterval(()=>refresh().catch(()=>{}),ms);}}"
                + "async function resetTelemetryAction(){const top=document.getElementById('telemetryResetStatusTop');const inline=document.getElementById('telemetryResetStatus');top.textContent='" + escapeJs(uiString("resetTelemetryInProgress", "Resetting admin telemetry...")) + "';inline.textContent='" + escapeJs(uiString("resetTelemetryInProgress", "Resetting admin telemetry...")) + "';try{const result=await postJson('/api/telemetry/reset');const msg='" + escapeJs(uiString("resetTelemetryResultPrefix", "Cleared: diagnostics ")) + "'+result.diagnosticsEntriesCleared+'" + escapeJs(uiString("resetTelemetryIncidentsSegment", ", incidents ")) + "'+result.incidentEntriesCleared+'" + escapeJs(uiString("resetTelemetryHistorySegment", ", history ")) + "'+result.monitoringHistorySamplesCleared+'" + escapeJs(uiString("resetTelemetryRouteHistorySegment", ", route history ")) + "'+result.alertRouteHistorySamplesCleared+'" + escapeJs(uiString("resetTelemetryPerformanceSegment", ", performance ops ")) + "'+result.storagePerformanceOperationsCleared;top.textContent=msg;inline.textContent=msg;await refresh();}catch(e){const msg='" + escapeJs(uiString("resetTelemetryErrorPrefix", "Reset failed: ")) + "'+e.message;top.textContent=msg;inline.textContent=msg;document.getElementById('explain').textContent=e.message;}}"
                + "async function resetPerformanceAction(){const status=document.getElementById('performanceResetStatus');if(status){status.textContent='" + escapeJs(uiString("performance.resetInProgress", localized(language, "Performans metrikleri temizleniyor...", "Clearing performance metrics..."))) + "';}try{const result=await postJson('/api/performance/reset');try{await postJsonRaw('/demo-load/api/scenario-shapes/reset');}catch(ignore){}window.__dashboardDataCache=window.__dashboardDataCache||{};window.__dashboardDataCache.perf=dashboardDefaults.perf;window.__dashboardDataCache.perfHistory=dashboardDefaults.perfHistory;window.__dashboardDataCache.shape=dashboardDefaults.shape;const msg='" + escapeJs(uiString("performance.resetResultPrefix", localized(language, "Temizlendi: performans işlemi ", "Cleared: performance ops "))) + "'+result.storagePerformanceOperationsCleared+'" + escapeJs(uiString("performance.resetHistorySegment", localized(language, ", performans geçmişi ", ", performance history "))) + "'+result.performanceHistorySamplesCleared;if(status){status.textContent=msg;}renderPerformanceSection(dashboardDefaults.perf,dashboardDefaults.perfHistory,dashboardDefaults.shape);await refresh();}catch(e){const msg='" + escapeJs(uiString("resetTelemetryErrorPrefix", "Reset failed: ")) + "'+e.message;if(status){status.textContent=msg;}document.getElementById('explain').textContent=e.message;}}"
                + "bindIfPresent('runExplain','click',explain);"
                + "bindIfPresent('copyExplainMarkdown','click',copyExplainMarkdownAction);"
                + "bindIfPresent('downloadExplainJson','click',downloadExplainJsonAction);"
                + "bindIfPresent('saveExplainNote','click',saveExplainNoteAction);"
                + "bindIfPresent('refreshNow','click',refreshNowAction);"
                + "bindIfPresent('resetPerformanceMetrics','click',resetPerformanceAction);"
                + "bindIfPresent('exportTuningJson','click',()=>loadTuningExport('json'));"
                + "bindIfPresent('exportTuningMarkdown','click',()=>loadTuningExport('markdown'));"
                + "bindIfPresent('copyTuningFlags','click',()=>loadTuningExport('flags'));"
                + "bindIfPresent('runtimeProfileAuto','click',()=>setRuntimeProfile('AUTO'));"
                + "bindIfPresent('runtimeProfileStandard','click',()=>setRuntimeProfile('STANDARD'));"
                + "bindIfPresent('runtimeProfileBalanced','click',()=>setRuntimeProfile('BALANCED'));"
                + "bindIfPresent('runtimeProfileAggressive','click',()=>setRuntimeProfile('AGGRESSIVE'));"
                + "document.addEventListener('click',event=>{const navCard=event.target.closest('[data-nav-card=\"true\"]');if(navCard){revealWorkspaceTarget(navCard.dataset.target);return;}const serviceButton=event.target.closest('[data-service-entry]');if(serviceButton){const item=(window.__servicesByIndex||[])[Number(serviceButton.dataset.serviceEntry)||0];renderServiceGuide(item);return;}const projectionReplayButton=event.target.closest('[data-projection-refresh-replay]');if(projectionReplayButton){event.preventDefault();replayProjectionRefreshFailure(projectionReplayButton.dataset.projectionRefreshReplay);return;}const diagnosticButton=event.target.closest('[data-diagnostic-entry]');if(diagnosticButton){const item=(window.__diagnosticsById||{})[diagnosticButton.dataset.diagnosticEntry];if(item){renderDiagnosticDetail(item);}}});"
                + "document.addEventListener('keydown',event=>{const navCard=event.target.closest('[data-nav-card=\"true\"]');if(navCard&&(event.key==='Enter'||event.key===' ')){event.preventDefault();revealWorkspaceTarget(navCard.dataset.target);}});"
                + "bindIfPresent('explainRelationUsageFilter','change',()=>{if(lastExplainPlan)renderExplainRelations(lastExplainPlan);});"
                + "bindIfPresent('explainRelationWarningsOnly','change',()=>{if(lastExplainPlan)renderExplainRelations(lastExplainPlan);});"
                + "bindIfPresent('resetTelemetryTop','click',resetTelemetryAction);"
                + "bindIfPresent('refreshInterval','change',applyRefreshInterval);bindIfPresent('toggleRefresh','click',toggleRefreshAction);setupLazySectionObserver();refreshNowAction();applyRefreshInterval();"
                + "bindIfPresent('taxonomySearch','input',filterTaxonomy);filterTaxonomy();"
                + "document.querySelectorAll('.nav-tree-link').forEach(link=>link.addEventListener('click',event=>{event.preventDefault();revealWorkspaceTarget(link.dataset.target);}));"
                + "activateNavLink('metric-taxonomy');"
                + "</script>"
                + "<div class=\"scroll-jump-stack\"><button id=\"scrollToTopButton\" class=\"btn btn-dark scroll-jump\" type=\"button\">" + scrollToTopLabel + "</button><button id=\"scrollToBottomButton\" class=\"btn btn-dark scroll-jump\" type=\"button\">" + scrollToBottomLabel + "</button></div><script>"
                + "document.getElementById('scrollToTopButton').addEventListener('click',()=>window.scrollTo({top:0,behavior:'smooth'}));"
                + "document.getElementById('scrollToBottomButton').addEventListener('click',()=>window.scrollTo({top:document.body.scrollHeight,behavior:'smooth'}));"
                + "</script></main></body></html>";
    }

    private String metricCard(String title, String valueId, String unit, String description, String emphasisClass) {
        return metricCard(title, valueId, unit, description, emphasisClass, null);
    }

    private String metricCard(String title, String valueId, String unit, String description, String emphasisClass, String targetId) {
        String targetAttr = defaultString(targetId).isBlank()
                ? ""
                : " data-target=\"" + escapeHtml(targetId) + "\" data-nav-card=\"true\" role=\"button\" tabindex=\"0\"";
        String placeholder = escapeHtml(uiString("loadingText", "Loading…"));
        return "<div class=\"col-12 col-md-6 col-xl-4\"><ops-metric-card class=\"card metric-card " + escapeHtml(defaultString(emphasisClass)) + "\"><div class=\"card-body\"" + targetAttr + ">"
                + "<div class=\"d-flex justify-content-between align-items-start gap-2\">"
                + "<div class=\"metric-label\">" + escapeHtml(title) + "</div>"
                + "<span class=\"metric-unit\">" + escapeHtml(unit) + "</span>"
                + "</div>"
                + "<div id=\"" + escapeHtml(valueId) + "\" class=\"metric-value metric-placeholder\">" + placeholder + "</div>"
                + "<div class=\"metric-help\">" + escapeHtml(description) + "</div>"
                + "</div></ops-metric-card></div>";
    }

    private String performanceCardShell(String idPrefix, String title, String description) {
        String language = dashboardLanguage.get();
        return "<div class=\"performance-widget\">"
                + "<div class=\"performance-widget-header\"><div class=\"signal-title\">" + title + "</div><div class=\"signal-summary\">" + description + "</div></div>"
                + "<div class=\"performance-widget-stats\">"
                + performanceStatCell(language, idPrefix + "Count", localized(language, "İşlem", "Operations"), "0")
                + performanceStatCell(language, idPrefix + "Avg", localized(language, "Ortalama", "Average"), "-")
                + performanceStatCell(language, idPrefix + "P95", "p95", "-")
                + performanceStatCell(language, idPrefix + "P99", "p99", "-")
                + performanceStatCell(language, idPrefix + "Max", localized(language, "En yüksek", "Max"), "-")
                + performanceStatCell(language, idPrefix + "LastSeen", localized(language, "Son gözlem", "Last seen"), "-")
                + "</div>"
                + "<div id=\"" + escapeHtml(idPrefix) + "Note\" class=\"performance-widget-note\">-</div>"
                + "</div>";
    }

    private String performanceStatCell(String language, String id, String label, String initialValue) {
        return "<div class=\"performance-stat\">"
                + "<div class=\"performance-stat-label\">" + escapeHtml(label) + "</div>"
                + "<div id=\"" + escapeHtml(id) + "\" class=\"performance-stat-value\">" + escapeHtml(initialValue) + "</div>"
                + "</div>";
    }

    private String renderPerformanceScenarioSummaryShell(
            String performanceBottleneckTitle,
            String performanceBottleneckIdle,
            String performanceScopeNote,
            String performanceTopReadTitle,
            String performanceTopReadIntro,
            String performanceTopWriteTitle,
            String performanceTopWriteIntro,
            String performanceTopPostgresReadTitle,
            String performanceTopPostgresReadIntro,
            String performanceTopPostgresWriteTitle,
            String performanceTopPostgresWriteIntro,
            String performanceOtherScenarioTitle
    ) {
        return "<div class=\"mt-2 mb-2\">"
                + "<div class=\"performance-summary-shell\">"
                + "<div class=\"performance-summary-head\"><div class=\"performance-summary-title\">" + performanceBottleneckTitle + "</div><div id=\"performancePressureBadge\" class=\"performance-pressure-badge\" data-tone=\"neutral\">-</div></div>"
                + "<div id=\"performanceBottleneckSummary\" class=\"performance-summary-text\">" + performanceBottleneckIdle + "</div>"
                + "<div class=\"performance-pressure-compare\">"
                + "<div class=\"performance-pressure-legend\">"
                + "<span class=\"performance-pressure-pill\"><span class=\"performance-pressure-dot read\"></span><span id=\"performancePressureReadLabel\">-</span></span>"
                + "<span class=\"performance-pressure-pill\"><span class=\"performance-pressure-dot write\"></span><span id=\"performancePressureWriteLabel\">-</span></span>"
                + "</div>"
                + "<div class=\"performance-pressure-track\">"
                + "<div id=\"performancePressureReadBar\" class=\"performance-pressure-fill read\" style=\"width:50%\"></div>"
                + "<div id=\"performancePressureWriteBar\" class=\"performance-pressure-fill write\" style=\"width:50%\"></div>"
                + "</div>"
                + "<div class=\"performance-pressure-footer\">"
                + "<span id=\"performancePressureReadValue\">-</span>"
                + "<span id=\"performancePressureWriteValue\">-</span>"
                + "</div>"
                + "<div id=\"performancePressureScopeLabel\" class=\"section-scope-note performance-pressure-scope\">" + performanceScopeNote + "</div>"
                + "</div>"
                + "</div>"
                + "<div class=\"fw-semibold mb-1\">" + performanceTopReadTitle + "</div>"
                + "<div class=\"small text-muted mb-2\">" + performanceTopReadIntro + "</div>"
                + "<div id=\"performanceTopReadGrid\" class=\"performance-grid mb-3\"></div>"
                + "<div class=\"fw-semibold mb-1\">" + performanceTopWriteTitle + "</div>"
                + "<div class=\"small text-muted mb-2\">" + performanceTopWriteIntro + "</div>"
                + "<div id=\"performanceTopWriteGrid\" class=\"performance-grid mb-3\"></div>"
                + "<div class=\"fw-semibold mb-1\">" + performanceTopPostgresReadTitle + "</div>"
                + "<div class=\"small text-muted mb-2\">" + performanceTopPostgresReadIntro + "</div>"
                + "<div id=\"performanceTopPostgresReadGrid\" class=\"performance-grid mb-3\"></div>"
                + "<div class=\"fw-semibold mb-1\">" + performanceTopPostgresWriteTitle + "</div>"
                + "<div class=\"small text-muted mb-2\">" + performanceTopPostgresWriteIntro + "</div>"
                + "<div id=\"performanceTopPostgresWriteGrid\" class=\"performance-grid mb-3\"></div>"
                + "<div class=\"fw-semibold mb-1\">" + performanceOtherScenarioTitle + "</div>"
                + "<div id=\"performanceScenarioGrid\" class=\"performance-grid\"></div>"
                + "</div>";
    }

    private String renderPerformanceScenarioJavaScript(String language) {
        String noDataText = escapeJs(localized(language, "Henüz senaryo verisi yok.", "No scenario data yet."));
        String readLabel = escapeJs(localized(language, "Okuma", "Read"));
        String writeLabel = escapeJs(localized(language, "Yazma", "Write"));
        String postgresReadLabel = escapeJs(localized(language, "PostgreSQL okuma", "PostgreSQL read"));
        String postgresWriteLabel = escapeJs(localized(language, "PostgreSQL yazma", "PostgreSQL write"));
        String operationsLabel = escapeJs(localized(language, "İşlem", "Operations"));
        String averageLabel = escapeJs(localized(language, "Ortalama", "Average"));
        String lastSeenLabel = escapeJs(localized(language, "Son gözlem", "Last seen"));
        String recommendationLabel = escapeJs(localized(language, "İlk optimizasyon önerisi", "First optimization suggestion"));
        String shapeLabel = escapeJs(localized(language, "İş yükü şekli", "Workload shape"));
        String lastLabel = escapeJs(localized(language, "son", "last"));
        String avgLabel = escapeJs(localized(language, "ort", "avg"));
        String primaryCountLabel = escapeJs(localized(language, "Ana kayıt", "Primary rows"));
        String relatedCountLabel = escapeJs(localized(language, "İlişkili kayıt", "Related rows"));
        String stepCountLabel = escapeJs(localized(language, "Sayfa / adım", "Pages / steps"));
        String objectCountLabel = escapeJs(localized(language, "Obje", "Objects"));
        String writeCountLabel = escapeJs(localized(language, "Yazma", "Writes"));
        String actionLabel = escapeJs(localized(language, "Demo yükünü aç", "Open demo load"));
        String topRankPrefix = escapeJs(localized(language, "Sıra", "Rank"));
        String readBottleneckLabel = escapeJs(localized(language, "Okuma tarafında ana baskı", "Primary pressure on the read path"));
        String writeBottleneckLabel = escapeJs(localized(language, "Yazma tarafında ana baskı", "Primary pressure on the write path"));
        String overallBottleneckLabel = escapeJs(localized(language, "Genel ana darboğaz", "Overall main bottleneck"));
        String readPressureLabel = escapeJs(localized(language, "Okuma baskısı", "Read pressure"));
        String writePressureLabel = escapeJs(localized(language, "Yazma baskısı", "Write pressure"));
        String balancedPressureLabel = escapeJs(localized(language, "Dengeli", "Balanced"));
        String neutralPressureLabel = escapeJs(localized(language, "Veri bekleniyor", "Waiting for data"));
        String readScoreLabel = escapeJs(localized(language, "Okuma skoru", "Read score"));
        String writeScoreLabel = escapeJs(localized(language, "Yazma skoru", "Write score"));
        String calmSummary = escapeJs(localized(language, "Bu senaryo şu an dengeli görünüyor.", "This scenario currently looks stable."));
        String warningSummary = escapeJs(localized(language, "Bu senaryo belirgin gecikme üretiyor.", "This scenario is generating noticeable latency."));
        String dangerSummary = escapeJs(localized(language, "Bu senaryo şu an en pahalı akışlardan biri.", "This scenario is currently one of the costliest flows."));
        String emptyRecommendation = escapeJs(localized(language, "Henüz öneri üretmek için yeterli örnek yok.", "There are not enough samples to suggest an optimization yet."));
        String readFallbackRecommendation = escapeJs(localized(language, "Bu okuma yolunda dönen kayıt hacmini, include zincirini ve sayfalama boyutunu küçültmeyi düşün.", "Reduce returned row volume, include depth, and page size on this read path."));
        String writeFallbackRecommendation = escapeJs(localized(language, "Bu yazma burst'ünde batch boyutunu, aynı anahtara tekrar yazımı ve gereksiz mutation sıklığını incele.", "Review batch size, repeated writes to the same key, and unnecessary mutation frequency in this write burst."));
        String bottleneckIdleText = escapeJs(localized(language, "Henüz senaryo bazlı performans örneği yok. Yükü kısa süre çalıştırınca burada en pahalı akışın özeti görünür.", "There is no scenario-level performance sample yet. Run load briefly and this area will summarize the costliest flow."));

        StringBuilder js = new StringBuilder();
        js.append("function performanceScenarioItems(map,kind){return Object.entries(map||{}).map(([key,metric])=>({key:key,metric:metric||{},kind:kind})).filter(item=>(Number(item.metric.operationCount)||0)>0);}");
        js.append("function performanceScenarioLabel(key){const labels={")
                .append("'catalog-read':'").append(escapeJs(localized(language, "Katalog taraması", "Catalog scan"))).append("',")
                .append("'hot-product-read':'").append(escapeJs(localized(language, "Sıcak ürün okuması", "Hot product read"))).append("',")
                .append("'customer-profile-read':'").append(escapeJs(localized(language, "Müşteri profil okuması", "Customer profile read"))).append("',")
                .append("'bulk-customer-read':'").append(escapeJs(localized(language, "Toplu müşteri okuması", "Bulk customer read"))).append("',")
                .append("'postgres-bulk-customer-read':'").append(escapeJs(localized(language, "PostgreSQL toplu müşteri okuması", "PostgreSQL bulk customer read"))).append("',")
                .append("'cart-window-read':'").append(escapeJs(localized(language, "Sepet pencere okuması", "Cart window read"))).append("',")
                .append("'top-customer-orders-read':'").append(escapeJs(localized(language, "Yoğun müşteri sipariş okuması", "Top customer orders read"))).append("',")
                .append("'postgres-top-customer-orders-read':'").append(escapeJs(localized(language, "PostgreSQL yoğun müşteri sipariş okuması", "PostgreSQL top customer orders read"))).append("',")
                .append("'high-line-orders-read':'").append(escapeJs(localized(language, "Yüksek satırlı sipariş okuması", "High-line orders read"))).append("',")
                .append("'postgres-high-line-orders-read':'").append(escapeJs(localized(language, "PostgreSQL yüksek satırlı sipariş okuması", "PostgreSQL high-line orders read"))).append("',")
                .append("'order-window-with-lines-read':'").append(escapeJs(localized(language, "Sipariş ve satır detay okuması", "Order with lines read"))).append("',")
                .append("'product-write-burst':'").append(escapeJs(localized(language, "Ürün yazma burst'ü", "Product write burst"))).append("',")
                .append("'cart-write-burst':'").append(escapeJs(localized(language, "Sepet yazma burst'ü", "Cart write burst"))).append("',")
                .append("'customer-write-burst':'").append(escapeJs(localized(language, "Müşteri yazma burst'ü", "Customer write burst"))).append("',")
                .append("'order-write-burst':'").append(escapeJs(localized(language, "Sipariş yazma burst'ü", "Order write burst"))).append("',")
                .append("'order-line-write-burst':'").append(escapeJs(localized(language, "Sipariş satırı yazma burst'ü", "Order line write burst"))).append("'")
                .append("};return labels[key]||String(key||'-').replace(/-/g,' ');}");
        js.append("function performanceScenarioKindLabel(kind){if(kind==='write'){return '").append(writeLabel).append("';}if(kind==='postgres-read'){return '").append(postgresReadLabel).append("';}if(kind==='postgres-write'){return '").append(postgresWriteLabel).append("';}return '").append(readLabel).append("';}");
        js.append("function performanceScenarioSuggestedLevel(item){const levels={")
                .append("'catalog-read':'MEDIUM',")
                .append("'bulk-customer-read':'MEDIUM',")
                .append("'postgres-bulk-customer-read':'HIGH',")
                .append("'cart-window-read':'MEDIUM',")
                .append("'hot-product-read':'HIGH',")
                .append("'top-customer-orders-read':'HIGH',")
                .append("'postgres-top-customer-orders-read':'HIGH',")
                .append("'high-line-orders-read':'HIGH',")
                .append("'postgres-high-line-orders-read':'HIGH',")
                .append("'order-window-with-lines-read':'HIGH',")
                .append("'product-write-burst':'HIGH',")
                .append("'cart-write-burst':'MEDIUM',")
                .append("'customer-write-burst':'HIGH',")
                .append("'order-write-burst':'HIGH',")
                .append("'order-line-write-burst':'HIGH'")
                .append("};return levels[item&&item.key]||'MEDIUM';}");
        js.append("function performanceScenarioShapeMap(shape){const map={};(((shape&&shape.items)||[])).forEach(item=>{map[item.key]=item;});return map;}");
        js.append("function performanceScenarioShapePrimaryLabel(key){const labels={")
                .append("'hot-product-read':'").append(escapeJs(localized(language, "Ürün", "Products"))).append("',")
                .append("'top-customer-orders-read':'").append(escapeJs(localized(language, "Sipariş", "Orders"))).append("',")
                .append("'high-line-orders-read':'").append(escapeJs(localized(language, "Sipariş", "Orders"))).append("',")
                .append("'order-write-burst':'").append(escapeJs(localized(language, "Sipariş", "Orders"))).append("',")
                .append("'order-line-write-burst':'").append(escapeJs(localized(language, "Dokunulan sipariş", "Touched orders"))).append("'")
                .append("};return labels[key]||'").append(primaryCountLabel).append("';}");
        js.append("function performanceScenarioShapeRelatedLabel(key){const labels={")
                .append("'top-customer-orders-read':'").append(escapeJs(localized(language, "Order line", "Order lines"))).append("',")
                .append("'high-line-orders-read':'").append(escapeJs(localized(language, "Order line", "Order lines"))).append("',")
                .append("'order-write-burst':'").append(escapeJs(localized(language, "Order line", "Order lines"))).append("',")
                .append("'order-line-write-burst':'").append(escapeJs(localized(language, "Order line", "Order lines"))).append("'")
                .append("};return labels[key]||'").append(relatedCountLabel).append("';}");
        js.append("function performanceShapeStat(label,lastValue,avgValue){return '<div class=\"performance-shape-stat\"><div class=\"performance-shape-stat-label\">'+escapeText(label)+'</div><div class=\"performance-shape-stat-value\">'+String(lastValue||0)+'</div><div class=\"performance-shape-stat-hint\">").append(lastLabel).append(": '+String(lastValue||0)+' · ").append(avgLabel).append(": '+String(avgValue||0)+'</div></div>';}");
        js.append("function performanceScenarioShapeMarkup(item,shapeMap){const shape=shapeMap[item.key];if(!shape){return '';}const stats=[];stats.push(performanceShapeStat(performanceScenarioShapePrimaryLabel(item.key),shape.lastPrimaryCount,shape.averagePrimaryCount));if((Number(shape.lastRelatedCount)||0)>0||(Number(shape.averageRelatedCount)||0)>0){stats.push(performanceShapeStat(performanceScenarioShapeRelatedLabel(item.key),shape.lastRelatedCount,shape.averageRelatedCount));}stats.push(performanceShapeStat('").append(stepCountLabel).append("',shape.lastStepCount,shape.averageStepCount));stats.push(performanceShapeStat('").append(objectCountLabel).append("',shape.lastObjectCount,shape.averageObjectCount));if((Number(shape.lastWriteCount)||0)>0||(Number(shape.averageWriteCount)||0)>0){stats.push(performanceShapeStat('").append(writeCountLabel).append("',shape.lastWriteCount,shape.averageWriteCount));}return '<div class=\"performance-shape-block\"><div class=\"performance-shape-title\">").append(shapeLabel).append("</div><div class=\"performance-shape-grid\">'+stats.join('')+'</div></div>';}");
        js.append("function performanceScenarioActionUrl(item){const level=performanceScenarioSuggestedLevel(item);return 'http://127.0.0.1:8090/?focus='+encodeURIComponent(item.key)+'&level='+encodeURIComponent(level)+'#scenario-controls';}");
        js.append("function setPerformancePressureBadge(kind){const badge=document.getElementById('performancePressureBadge');if(!badge){return;}const map={read:{label:'").append(readPressureLabel).append("',tone:'read'},write:{label:'").append(writePressureLabel).append("',tone:'write'},balanced:{label:'").append(balancedPressureLabel).append("',tone:'balanced'},neutral:{label:'").append(neutralPressureLabel).append("',tone:'neutral'}};const config=map[kind]||map.neutral;badge.textContent=config.label;badge.setAttribute('data-tone',config.tone);}");
        js.append("function setPerformancePressureBar(readScore,writeScore){const readBar=document.getElementById('performancePressureReadBar');const writeBar=document.getElementById('performancePressureWriteBar');const readLabel=document.getElementById('performancePressureReadLabel');const writeLabel=document.getElementById('performancePressureWriteLabel');const readValue=document.getElementById('performancePressureReadValue');const writeValue=document.getElementById('performancePressureWriteValue');if(!readBar||!writeBar||!readLabel||!writeLabel||!readValue||!writeValue){return;}const safeRead=Math.max(0,Number(readScore)||0);const safeWrite=Math.max(0,Number(writeScore)||0);const total=Math.max(1,safeRead+safeWrite);const readPercent=Math.max(10,Math.round((safeRead/total)*100));const writePercent=Math.max(10,100-readPercent);readBar.style.width=readPercent+'%';writeBar.style.width=writePercent+'%';readLabel.textContent='").append(readPressureLabel).append("';writeLabel.textContent='").append(writePressureLabel).append("';readValue.textContent='").append(readScoreLabel).append(": '+safeRead.toLocaleString();writeValue.textContent='").append(writeScoreLabel).append(": '+safeWrite.toLocaleString();}");
        js.append("function performanceScenarioTone(item){const p95=Number(item&&item.metric&&item.metric.p95Micros)||0;if(p95>=8000000){return 'danger';}if(p95>=3000000){return 'warning';}return 'success';}");
        js.append("function performanceScenarioSummary(item){const tone=performanceScenarioTone(item);if(tone==='danger'){return '").append(dangerSummary).append("';}if(tone==='warning'){return '").append(warningSummary).append("';}return '").append(calmSummary).append("';}");
        js.append("function performanceScenarioScore(item){const metric=(item&&item.metric)||{};const p95=Number(metric.p95Micros)||0;const avg=Number(metric.averageMicros)||0;const max=Number(metric.maxMicros)||0;const count=Number(metric.operationCount)||0;return (p95*100)+(avg*10)+max+Math.min(count,1000);}");
        js.append("function performanceScenarioRecommendation(item){const recommendations={")
                .append("'hot-product-read':'").append(escapeJs(localized(language, "Sıcak ürün kümesini küçült, ürün satırında dönen alanları azalt ve sonuçları daha kısa sayfalarla parçala.", "Reduce the hot-product set, trim returned product fields, and break results into shorter pages."))).append("',")
                .append("'high-line-orders-read':'").append(escapeJs(localized(language, "Sipariş satırlarını pencerele, her siparişte dönen order line adedini sınırla ve yalnız özet alanları öne çıkar.", "Window order lines, cap line count per order fetch, and lead with summary fields only."))).append("',")
                .append("'top-customer-orders-read':'").append(escapeJs(localized(language, "Tek müşteride çok sipariş dönüyorsa zaman aralığı filtresi ekle ve satır detayını ayrı çağrıya ayır.", "Add a time-range filter for heavy customers and split line details into a separate call."))).append("',")
                .append("'postgres-top-customer-orders-read':'").append(escapeJs(localized(language, "Ağır müşteri siparişlerini PostgreSQL tarafında tarih penceresiyle daralt, satır detayını ikincil sorguya böl ve gereksiz kolonları çıkar.", "Narrow heavy customer order reads with a time window, split line details into a secondary query, and trim unnecessary columns."))).append("',")
                .append("'bulk-customer-read':'").append(escapeJs(localized(language, "Toplu müşteri okumasında sayfa boyutunu düşür ve tam nesne yerine liste görünümü alanlarıyla dön.", "Lower page size for bulk customer reads and return list-view fields instead of full entities."))).append("',")
                .append("'postgres-bulk-customer-read':'").append(escapeJs(localized(language, "PostgreSQL toplu müşteri taramasında daha dar kolon seti, daha küçük pencere ve uygun ORDER BY indeksini kullan.", "Use a narrower column set, smaller windows, and an index-friendly ORDER BY for PostgreSQL bulk customer scans."))).append("',")
                .append("'order-window-with-lines-read':'").append(escapeJs(localized(language, "Sipariş ve satır detayını aynı çağrıda taşımak yerine sipariş özeti + lazy detay akışına böl.", "Split order-with-lines fetch into order summary plus lazy detail loading."))).append("',")
                .append("'product-write-burst':'").append(escapeJs(localized(language, "Aynı ürün üzerinde tekrarlayan mutation'ları birleştir ve yazma dalgasını daha küçük batch'lere böl.", "Coalesce repeated mutations on the same product and split the burst into smaller batches."))).append("',")
                .append("'cart-write-burst':'").append(escapeJs(localized(language, "Sepet yazmalarında aynı kullanıcı için arka arkaya gelen güncellemeleri birleştir.", "Coalesce back-to-back cart updates for the same user."))).append("',")
                .append("'customer-write-burst':'").append(escapeJs(localized(language, "Müşteri yazma dalgasında aynı kayıt üstündeki tekrarları azalt ve batch boyutunu aşağı çek.", "Reduce repeated writes on the same customer and lower the batch size."))).append("',")
                .append("'order-write-burst':'").append(escapeJs(localized(language, "Sipariş yazmalarını daha küçük flush gruplarına ayır ve aynı siparişe peş peşe mutation basmayı azalt.", "Split order writes into smaller flush groups and reduce consecutive mutations on the same order."))).append("',")
                .append("'order-line-write-burst':'").append(escapeJs(localized(language, "Sipariş satırı burst'lerinde aynı sipariş için line update dalgalarını birleştir ve satır sayısını pencerele.", "Coalesce line-update waves for the same order and window the number of lines written together."))).append("',")
                .append("'postgres-high-line-orders-read':'").append(escapeJs(localized(language, "Yüksek satırlı sipariş PostgreSQL sorgusunda önce sipariş listesini pencerele, sonra satır detaylarını ikinci adımda çek ve satır sayısını sınırla.", "Window high-line order lists first, then fetch line details in a second step and cap returned line count."))).append("'")
                .append("};if(!item){return '").append(emptyRecommendation).append("';}return recommendations[item.key]||(item.kind==='write'?'").append(writeFallbackRecommendation).append("':'").append(readFallbackRecommendation).append("');}");
        js.append("function performanceScenarioRankBadge(index){const rank=index+1;return '<span class=\"performance-rank-badge\" data-rank=\"'+rank+'\" title=\"").append(topRankPrefix).append(" '+rank+'\">#'+rank+'</span>';}");
        js.append("function performanceScenarioSummaryLine(item){if(!item){return '").append(bottleneckIdleText).append("';}const metric=item.metric||{};return performanceScenarioLabel(item.key)+' ('+performanceScenarioKindLabel(item.kind)+', p95 '+formatLatencyMicros(metric.p95Micros)+', '+String(metric.operationCount||0)+' ").append(operationsLabel).append(")';}");
        js.append("function renderPerformanceScenarioCards(targetId,items,emptyMessage,shapeMap){const target=document.getElementById(targetId);if(!target){return;}if(!items.length){target.innerHTML='<div class=\"text-muted small\">'+escapeText(emptyMessage)+'</div>';return;}const shapes=shapeMap||{};target.innerHTML=items.map((item,index)=>{const metric=item.metric||{};const tone=performanceScenarioTone(item);const summary=performanceScenarioSummary(item);const recommendation=performanceScenarioRecommendation(item);const lastSeen=(Number(metric.lastObservedAtEpochMillis)||0)>0?new Date(Number(metric.lastObservedAtEpochMillis)).toLocaleTimeString(): '-';const actionUrl=performanceScenarioActionUrl(item);const shapeMarkup=performanceScenarioShapeMarkup(item,shapes);return '<div class=\"performance-widget\">'"
                + "+'<div class=\"performance-widget-header\"><div class=\"d-flex justify-content-between align-items-start gap-2\"><div class=\"d-flex align-items-center gap-2 min-w-0\">'+performanceScenarioRankBadge(index)+'<div class=\"signal-title\">'+escapeText(performanceScenarioLabel(item.key))+'</div></div><span class=\"signal-badge\" data-tone=\"'+tone+'\">'+escapeText(performanceScenarioKindLabel(item.kind))+'</span></div><div class=\"signal-summary\">'+escapeText(summary)+'</div></div>'"
                + "+'<div class=\"performance-widget-stats\">'"
                + "+'<div class=\"performance-stat\"><div class=\"performance-stat-label\">").append(operationsLabel).append("</div><div class=\"performance-stat-value\">'+String(metric.operationCount||0)+'</div></div>'"
                + "+'<div class=\"performance-stat\"><div class=\"performance-stat-label\">p95</div><div class=\"performance-stat-value\">'+formatLatencyMicros(metric.p95Micros)+'</div></div>'"
                + "+'<div class=\"performance-stat\"><div class=\"performance-stat-label\">").append(averageLabel).append("</div><div class=\"performance-stat-value\">'+formatLatencyMicros(metric.averageMicros)+'</div></div>'"
                + "+'<div class=\"performance-stat\"><div class=\"performance-stat-label\">p99</div><div class=\"performance-stat-value\">'+formatLatencyMicros(metric.p99Micros)+'</div></div>'"
                + "+'<div class=\"performance-stat\"><div class=\"performance-stat-label\">Max</div><div class=\"performance-stat-value\">'+formatLatencyMicros(metric.maxMicros)+'</div></div>'"
                + "+'<div class=\"performance-stat\"><div class=\"performance-stat-label\">").append(lastSeenLabel).append("</div><div class=\"performance-stat-value\">'+escapeText(lastSeen)+'</div></div>'"
                + "+'</div>'"
                + "+shapeMarkup"
                + "+'<div class=\"performance-widget-note\"><div class=\"small text-muted mb-1\">").append(recommendationLabel).append("</div>'+escapeText(recommendation)+'</div>'"
                + "+'<div class=\"performance-widget-footer\"><div class=\"small text-muted mono\">'+escapeText(item.key)+'</div><a class=\"performance-action-link\" href=\"'+actionUrl+'\" target=\"_blank\" rel=\"noopener noreferrer\">").append(actionLabel).append("</a></div>'"
                + "+'</div>';}).join('');}");
        js.append("function renderPerformanceScenarioDashboard(perf,shape){const target=document.getElementById('performanceScenarioGrid');const topReadTarget=document.getElementById('performanceTopReadGrid');const topWriteTarget=document.getElementById('performanceTopWriteGrid');const topPostgresReadTarget=document.getElementById('performanceTopPostgresReadGrid');const topPostgresWriteTarget=document.getElementById('performanceTopPostgresWriteGrid');const summaryTarget=document.getElementById('performanceBottleneckSummary');if(!target&&!topReadTarget&&!topWriteTarget&&!topPostgresReadTarget&&!topPostgresWriteTarget&&!summaryTarget){return;}const data=perf||dashboardDefaults.perf;const shapeMap=performanceScenarioShapeMap(shape||dashboardDefaults.shape);const readItems=performanceScenarioItems(data.redisReadBreakdown,'read').sort((a,b)=>performanceScenarioScore(b)-performanceScenarioScore(a));const writeItems=performanceScenarioItems(data.redisWriteBreakdown,'write').sort((a,b)=>performanceScenarioScore(b)-performanceScenarioScore(a));const postgresReadItems=performanceScenarioItems(data.postgresReadBreakdown,'postgres-read').sort((a,b)=>performanceScenarioScore(b)-performanceScenarioScore(a));const postgresWriteItems=performanceScenarioItems(data.postgresWriteBreakdown,'postgres-write').sort((a,b)=>performanceScenarioScore(b)-performanceScenarioScore(a));const topRead=readItems.slice(0,3);const topWrite=writeItems.slice(0,3);const topPostgresRead=postgresReadItems.slice(0,3);const topPostgresWrite=postgresWriteItems.slice(0,3);renderPerformanceScenarioCards('performanceTopReadGrid',topRead,'").append(noDataText).append("',shapeMap);renderPerformanceScenarioCards('performanceTopWriteGrid',topWrite,'").append(noDataText).append("',shapeMap);renderPerformanceScenarioCards('performanceTopPostgresReadGrid',topPostgresRead,'").append(noDataText).append("',shapeMap);renderPerformanceScenarioCards('performanceTopPostgresWriteGrid',topPostgresWrite,'").append(noDataText).append("',shapeMap);const topReadItem=topRead[0]||null;const topWriteItem=topWrite[0]||null;const topReadScore=topReadItem?performanceScenarioScore(topReadItem):0;const topWriteScore=topWriteItem?performanceScenarioScore(topWriteItem):0;if(summaryTarget){let summary='").append(bottleneckIdleText).append("';let pressureKind='neutral';if(topReadItem||topWriteItem){if(topReadItem&&topWriteItem){const overall=(topReadScore>=topWriteScore)?topReadItem:topWriteItem;const ratio=Math.max(topReadScore,topWriteScore)/Math.max(1,Math.min(topReadScore,topWriteScore));pressureKind=ratio<1.18?'balanced':(topReadScore>topWriteScore?'read':'write');summary='").append(overallBottleneckLabel).append(": '+performanceScenarioSummaryLine(overall)+'. ';summary+=(topReadItem?'").append(readBottleneckLabel).append(": '+performanceScenarioSummaryLine(topReadItem)+'. ':'');summary+=(topWriteItem?'").append(writeBottleneckLabel).append(": '+performanceScenarioSummaryLine(topWriteItem)+'.':'');}else if(topReadItem){pressureKind='read';summary='").append(readBottleneckLabel).append(": '+performanceScenarioSummaryLine(topReadItem)+'.';}else if(topWriteItem){pressureKind='write';summary='").append(writeBottleneckLabel).append(": '+performanceScenarioSummaryLine(topWriteItem)+'.';}}if(topPostgresRead[0]){summary+=' ").append(escapeJs(localized(language, "PostgreSQL okuma tarafında öne çıkan akış", "Leading PostgreSQL read flow"))).append(": '+performanceScenarioSummaryLine(topPostgresRead[0])+'.';}if(topPostgresWrite[0]){summary+=' ").append(escapeJs(localized(language, "PostgreSQL yazma tarafında öne çıkan akış", "Leading PostgreSQL write flow"))).append(": '+performanceScenarioSummaryLine(topPostgresWrite[0])+'.';}summaryTarget.textContent=summary;setPerformancePressureBadge(pressureKind);setPerformancePressureBar(topReadScore,topWriteScore);}const topKeys=new Set(topRead.map(item=>'read:'+item.key).concat(topWrite.map(item=>'write:'+item.key)).concat(topPostgresRead.map(item=>'postgres-read:'+item.key)).concat(topPostgresWrite.map(item=>'postgres-write:'+item.key)));const remainder=readItems.map(item=>({bucket:'read',item:item})).concat(writeItems.map(item=>({bucket:'write',item:item}))).concat(postgresReadItems.map(item=>({bucket:'postgres-read',item:item}))).concat(postgresWriteItems.map(item=>({bucket:'postgres-write',item:item}))).filter(entry=>!topKeys.has(entry.bucket+':'+entry.item.key)).map(entry=>entry.item);renderPerformanceScenarioCards('performanceScenarioGrid',remainder,'").append(noDataText).append("',shapeMap);}");
        return js.toString();
    }

    private String renderOperationalTaxonomy(String language) {
        StringBuilder builder = new StringBuilder();
        builder.append(renderTaxonomyCard(
                localized(language, "Kuyruklar", "Queues"),
                localized(language, "Arka Plan Yazma Kuyruğu", "Write-behind"),
                localized(language, "adet", "count"),
                localized(language, "Redis arka plan yazma kuyruğunda işlenmeyi bekleyen iş sayısıdır.", "Count of items still waiting in the Redis write-behind queue."),
                localized(language, "Yük artarken önce yükselir. Uzun süre yüksek kalıyorsa PostgreSQL veya worker hattı yetişmiyor olabilir.", "This usually rises first during load. If it stays high, PostgreSQL or the worker path may be lagging."),
                localized(language, "Başarısız kayıt kuyruğu ile birlikte büyüyorsa yazma hattında kalıcı baskı olabilir.", "If it rises together with dead-letter, the write path is under sustained pressure.")
        ));
        builder.append(renderTaxonomyCard(
                localized(language, "Hata Kurtarma", "Recovery"),
                localized(language, "Başarısız Kayıt Kuyruğu", "Dead-letter"),
                localized(language, "adet", "count"),
                localized(language, "Normal akışta yazılamayan ve yeniden işleme veya inceleme bekleyen kayıt sayısıdır.", "Count of records that failed the normal path and are waiting for replay or investigation."),
                localized(language, "Geçici sıçrama görülebilir ama uzun süre yüksek kalması hatanın biriktiğini gösterir.", "Short spikes can happen, but a high steady value means failures are accumulating."),
                localized(language, "Sürekli artış doğrudan müdahale gerektirir.", "A steadily rising value needs direct intervention.")
        ));
        builder.append(renderTaxonomyCard(
                localized(language, "Bellek", "Memory"),
                localized(language, "Redis Belleği", "Redis Memory"),
                localized(language, "byte", "bytes"),
                localized(language, "Redis used_memory değeridir; entity, cache ve stream anahtarlarının toplam belleğini gösterir.", "Redis used_memory; total memory used by entity, cache, and stream keys."),
                localized(language, "Yazma kuyruğu artmadan yükseliyorsa daha çok önbellek büyümesi düşünülür.", "If it grows without backlog, cache growth is the likely reason."),
                localized(language, "Yazma kuyruğu ile birlikte yükseliyorsa baskı daha kritik hale gelir.", "If it rises with backlog, the pressure is more serious.")
        ));
        builder.append(renderTaxonomyCard(
                localized(language, "Sıkıştırma", "Compaction"),
                localized(language, "Bekleyen Sıkıştırma", "Compaction Pending"),
                localized(language, "adet", "count"),
                localized(language, "Sıkıştırılmış ama tam işlenmesi henüz bitmemiş pending durum sayısıdır.", "Count of compacted states that have not yet fully finished processing."),
                localized(language, "Seed ve kısa yüklerde biraz dalgalanması normaldir.", "Some fluctuation during seed and short bursts is normal."),
                localized(language, "Yük durmuşken de sürekli yüksek kalıyorsa cleanup hattı yavaş olabilir.", "If it stays high even after load stops, the cleanup path may be slow.")
        ));
        builder.append(renderTaxonomyCard(
                localized(language, "Sağlık", "Health"),
                localized(language, "Genel Sağlık", "Health"),
                localized(language, "durum", "state"),
                localized(language, "Sistemin birleşik sağlık sonucudur: UP, DEGRADED veya DOWN.", "The overall combined health result: UP, DEGRADED, or DOWN."),
                localized(language, "Tek başına okunmaz; incidents ve triage ile birlikte yorumlanır.", "Do not read it alone; interpret it together with incidents and triage."),
                localized(language, "DEGRADED sistem ayakta ama sorun var; DOWN aktif kritik problem var demektir.", "DEGRADED means the system is up with problems; DOWN means an active critical problem exists.")
        ));
        builder.append(renderTaxonomyCard(
                localized(language, "Sağlık", "Health"),
                localized(language, "Açık Olaylar", "Incidents"),
                localized(language, "adet", "count"),
                localized(language, "Açık alarm veya sağlık problemi başlıklarının sayısıdır.", "Count of active alarm or health issue topics."),
                localized(language, "Incident sayısının düşük olması tek başına yeterli değildir; kritik sinyaller de düşük olmalıdır.", "A low incident count is good only if critical signals are also low."),
                localized(language, "Kod ve açıklama kolonları hangi problemi yaşadığını söyler.", "The code and meaning columns tell you which problem is active.")
        ));
        builder.append(renderTaxonomyCard(
                localized(language, "Sağlık", "Health"),
                localized(language, "Kritik Sinyaller", "Critical Signals"),
                localized(language, "adet", "count"),
                localized(language, "Critical sınıfında açık olan aktif sinyal sayısıdır.", "Count of active signals currently classified as critical."),
                localized(language, "İlk bakılacak alarm şiddeti göstergesidir.", "This is the first severity signal to check."),
                localized(language, "Sıfır değilse operatör odağı gerekir.", "If not zero, it deserves operator focus.")
        ));
        builder.append(renderTaxonomyCard(
                localized(language, "Sağlık", "Health"),
                localized(language, "Uyarı Sinyalleri", "Warning Signals"),
                localized(language, "adet", "count"),
                localized(language, "Warning sınıfında açık olan aktif sinyal sayısıdır.", "Count of active signals currently classified as warning."),
                localized(language, "Kalıcı warning’ler çoğu zaman daha büyük bir problemin öncüsüdür.", "Persistent warnings are often the precursor to a larger issue."),
                localized(language, "Trendde uzuyorsa threshold veya yük davranışı izlenmelidir.", "If the warning plateau keeps extending, thresholds or load shape should be reviewed.")
        ));
        builder.append(renderTaxonomyCard(
                localized(language, "Teşhis", "Diagnosis"),
                localized(language, "Önceliklendirme Özeti", "Triage"),
                localized(language, "özet", "summary"),
                localized(language, "Birçok metriği birleştirip ana darboğaz tahmini üretir.", "Combines many signals to produce a primary bottleneck guess."),
                localized(language, "İlk ipucu olarak kullanılır; aşağıdaki bölümlerle doğrulanır.", "Use it as the first clue, then confirm with the sections below."),
                localized(language, "Yanlış değil ama indirgenmiş bir özet olduğu için tek kaynak değildir.", "It is helpful but still a compressed summary, not the only source of truth.")
        ));
        builder.append(renderTaxonomyCard(
                localized(language, "Teşhis", "Diagnosis"),
                localized(language, "Servis Durumu", "Service Status"),
                localized(language, "tablo", "table"),
                localized(language, "Her alt sistemin durumunu ve onu bozan ana sinyali gösterir.", "Shows each subsystem state and the key signal driving that state."),
                localized(language, "Sorunun hangi katmanda olduğunu hızlı ayırmak için bakılır.", "Use it to quickly isolate which subsystem is unhealthy."),
                localized(language, "Özellikle worker, recovery ve alert delivery ayrımında değerlidir.", "It is especially useful for separating worker, recovery, and alert delivery issues.")
        ));
        builder.append(renderTaxonomyCard(
                localized(language, "Bildirim", "Notification"),
                localized(language, "Rota Durumu", "Route Status"),
                localized(language, "durum", "state"),
                localized(language, "Alert route’un kendi çalışma sağlığını gösterir.", "Shows the operational health of the alert route itself."),
                localized(language, "UP ise rota sağlıklıdır; DEGRADED veya DOWN ise teslim tarafında sorun vardır.", "UP means the route is healthy; DEGRADED or DOWN indicates delivery-side trouble."),
                localized(language, "Ana veri yolu sağlıklı olsa bile rota bozulabilir.", "The main data path can be healthy while a route is unhealthy.")
        ));
        builder.append(renderTaxonomyCard(
                localized(language, "Bildirim", "Notification"),
                localized(language, "Teslim Hedefi", "Target"),
                localized(language, "hedef", "target"),
                localized(language, "Route’un gerçek teslim hedefidir; örn. webhook URL, queue stream, SMTP host.", "The actual delivery target of the route, such as a webhook URL, queue stream, or SMTP host."),
                localized(language, "Ekranda kısaltılmış görünse bile üzerine gelince tam değer görülür.", "Even if clipped on screen, the full value is visible on hover."),
                localized(language, "Yanlış target operasyonel yanlış yönlendirmeye neden olur.", "A wrong target causes operational misrouting.")
        ));
        builder.append(renderTaxonomyCard(
                localized(language, "Bildirim", "Notification"),
                localized(language, "Yeniden Deneme Kuralı", "Retry Policy"),
                localized(language, "politika", "policy"),
                localized(language, "Teslimat başarısız olduğunda kaç kez ve hangi bekleme ile yeniden deneneceğini söyler.", "Describes how many times and with what delay a failed delivery will be retried."),
                localized(language, "retries ve backoffMs alanları rotanın nasıl davranacağını açıklar.", "The retries and backoffMs values explain route behavior."),
                localized(language, "Aşırı agresif veya aşırı gevşek yeniden deneme ayrı risk taşır.", "Retries that are too aggressive or too lax each create their own risk.")
        ));
        builder.append(renderTaxonomyCard(
                localized(language, "Bildirim", "Notification"),
                localized(language, "Son Teslim Durumu", "Last Delivery State"),
                localized(language, "durum", "state"),
                localized(language, "En son teslim denemesinin genel sonucudur: beklemede, teslim edildi ya da görülen hata türü.", "The summary of the latest delivery attempt: idle, delivered, or an error type."),
                localized(language, "Route durumunu tek bakışta anlamak için en hızlı hücrelerden biridir.", "This is one of the fastest cells for understanding route behavior at a glance."),
                localized(language, "Burada bir hata türü görünüyorsa rota geçmişi ile birlikte okunmalıdır.", "If an error type is shown here, read it together with route history.")
        ));
        builder.append(renderTaxonomyCard(
                localized(language, "Şema", "Schema"),
                localized(language, "Şema Durumu", "Schema Status"),
                localized(language, "tablo", "table"),
                localized(language, "Migration baskısı, validation sonucu ve üretilen DDL hacmi gibi şema sağlığını özetler.", "Summarizes schema health such as migration pressure, validation state, and generated DDL volume."),
                localized(language, "İlk açılış, upgrade ve rollout sırasında özellikle önemlidir.", "It is especially important during first start, upgrade, and rollout."),
                localized(language, "Validation başarısızsa veri yolu düzgün çalışsa bile rollout riski vardır.", "A failed validation means rollout risk exists even if the data path looks healthy.")
        ));
        builder.append(renderTaxonomyCard(
                localized(language, "Ayarlar", "Tuning"),
                localized(language, "Geçerli Etkin Ayarlar", "Current Effective Tuning"),
                localized(language, "tablo", "table"),
                localized(language, "Şu anda gerçekten çalışan runtime ayarlarını gösterir; sadece default’ları değil.", "Shows the runtime settings actually in effect, not just defaults."),
                localized(language, "Bir değerin neden öyle davrandığını anlamak için ilk referans kaynağıdır.", "This is the first reference when you need to understand why a behavior occurred."),
                localized(language, "Source sütunu açıkça verilmiş ayarla varsayılan ayarı ayırt etmeyi sağlar.", "The Source column distinguishes explicit overrides from defaults.")
        ));
        builder.append(renderTaxonomyCard(
                localized(language, "Sorgu", "Query"),
                localized(language, "Sorgu Açıklaması", "Explain"),
                localized(language, "teşhis", "diagnostic"),
                localized(language, "Bir sorguyu planner, relation ve step bazında nasıl yürütüldüğünü gösteren teşhis çıktısıdır.", "A diagnostic view that shows how a query was executed at planner, relation, and step level."),
                localized(language, "Önce triage kartlarına, sonra relation states ve explain steps tablosuna bakılır.", "Start with the triage cards, then inspect relation states and explain steps."),
                localized(language, "Fallback, bozulmuş relation çözümü veya pahalı adım varsa burada görünür.", "Fallbacks, degraded relations, or expensive steps show up here.")
        ));
        builder.append(renderTaxonomyCard(
                localized(language, "Sorgu", "Query"),
                localized(language, "Tahmini Maliyet", "Estimated Cost"),
                localized(language, "puan", "score"),
                localized(language, "Planner’ın göreli maliyet tahminidir; gerçek milisaniye değildir.", "The planner’s relative cost estimate; it is not literal milliseconds."),
                localized(language, "Diğer planlarla karşılaştırmalı yorumlanmalıdır.", "It should be read comparatively across plans."),
                localized(language, "Aynı sorgunun daha pahalı varyantını bulmak için kullanılır.", "Use it to spot a more expensive variation of the same query.")
        ));
        builder.append(renderTaxonomyCard(
                localized(language, "Sorgu", "Query"),
                localized(language, "İlişki Durumları", "Relation States"),
                localized(language, "tablo", "table"),
                localized(language, "İlişki filter veya fetch yolunun çözüldü mü, bozuldu mu, yoksa sadece ön yükleme mi yaptığına burada bakılır.", "Explains whether relation filter/fetch paths resolved, degraded, or only performed preload."),
                localized(language, "Kullanım, durum, eşlenen alan ve aday sayısı birlikte okunmalıdır.", "Read usage, status, mappedBy, and candidateCount together."),
                localized(language, "Sorunlu ilişki satırları, açıklama uyarılarının ana kaynağı olabilir.", "Problematic relation rows are often the source of explain warnings.")
        ));
        builder.append(renderTaxonomyCard(
                localized(language, "Sorgu", "Query"),
                localized(language, "Eşlenen Alan", "mappedBy"),
                localized(language, "alan", "field"),
                localized(language, "İlişkinin hangi alan üzerinden çözüldüğünü gösterir.", "Shows which field was used to resolve the relation."),
                localized(language, "Beklenen alan yerine farklı bir alan görülürse mapping gözden geçirilir.", "If it resolves through an unexpected field, mapping should be reviewed."),
                localized(language, "Boş veya unresolved değer relation tanım problemine işaret edebilir.", "Empty or unresolved values can indicate relation definition issues.")
        ));
        builder.append(renderTaxonomyCard(
                localized(language, "Sorgu", "Query"),
                localized(language, "Aday Sayısı", "candidateCount"),
                localized(language, "adet", "count"),
                localized(language, "Bir step veya relation aşamasına kaç aday kaydın ulaştığını gösterir.", "Shows how many candidate records reached a given step or relation stage."),
                localized(language, "Yüksek candidateCount genelde daha pahalı residual work demektir.", "A high candidateCount usually means more expensive residual work."),
                localized(language, "Aday seti erkenden daralmıyorsa index veya relation stratejisi zayıf olabilir.", "If the candidate set does not narrow early, index or relation strategy may be weak.")
        ));
        builder.append(renderTaxonomyCard(
                localized(language, "Operasyon", "Operations"),
                localized(language, "Runbook", "Runbook"),
                localized(language, "aksiyon", "action"),
                localized(language, "Belirli bir sinyal veya alarm için ilk yapılacak operasyon adımını tanımlar.", "Defines the first operational action for a given signal or alert."),
                localized(language, "Alert geldiğinde 'şimdi ne yapacağız?' sorusunun cevabıdır.", "It answers 'what do we do now?' when an alert fires."),
                localized(language, "Runbook zayıfsa iyi telemetry bile operasyonda yavaş kalır.", "If the runbook is weak, even good telemetry leads to slow operations.")
        ));
        builder.append(renderTaxonomyCard(
                localized(language, "Kontrol", "Controls"),
                localized(language, "Telemetri Geçmişini Sıfırla", "Reset Telemetry History"),
                localized(language, "aksiyon", "action"),
                localized(language, "Diagnostics, incident history, monitoring history ve alert route history verisini temizler.", "Clears diagnostics, incident history, monitoring history, and alert route history."),
                localized(language, "Yeni bir test veya gözlem seansı öncesinde kullanılır.", "Use it before a new test or observation session."),
                localized(language, "Demo entity verisini silmez; bunun için demo ekranındaki Clear Data kullanılır.", "It does not remove demo entity data; use Clear Data on the demo UI for that.")
        ));
        return builder.toString();
    }

    private String renderTaxonomyCard(String category,
                                      String term,
                                      String unit,
                                      String definition,
                                      String howToRead,
                                      String whenToCare) {
        String search = (category + " " + term + " " + unit + " " + definition + " " + howToRead + " " + whenToCare).toLowerCase(Locale.ROOT);
        return "<div class=\"col-12 col-lg-6 col-xxl-4 taxonomy-card\" data-search=\"" + escapeHtml(search) + "\"><ops-taxonomy-card class=\"glossary-card\"><div class=\"card-body\">"
                + "<div class=\"glossary-meta\"><span class=\"badge text-bg-light\">" + escapeHtml(category) + "</span><span class=\"badge text-bg-secondary\">" + escapeHtml(unit) + "</span></div>"
                + "<div class=\"fw-semibold fs-5 mb-2\">" + escapeHtml(term) + "</div>"
                + "<div class=\"glossary-definition\">" + escapeHtml(definition) + "</div>"
                + "<div class=\"glossary-subtitle\">" + escapeHtml(localized(dashboardLanguage.get(), "Nasıl okunur", "How to read")) + "</div>"
                + "<div class=\"small text-muted mb-3\">" + escapeHtml(howToRead) + "</div>"
                + "<div class=\"glossary-subtitle\">" + escapeHtml(localized(dashboardLanguage.get(), "Ne zaman dikkat gerekir", "When to care")) + "</div>"
                + "<div class=\"small text-muted\">" + escapeHtml(whenToCare) + "</div>"
                + "</div></ops-taxonomy-card></div>";
    }

    private String sectionIntro(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return "<div class=\"section-intro\">" + escapeHtml(text) + "</div>";
    }

    private String sectionPair(String leftTitle, String leftTableId, int leftColumns, String rightTitle, String rightTableId, int rightColumns) {
        return "<div class=\"row g-3 mb-3\">"
                + "<div class=\"col-12 col-xl-6\"><ops-panel class=\"card section-card h-100\"><div class=\"card-header\">" + escapeHtml(leftTitle) + "</div><div class=\"card-body\">"
                + tableShell(leftTableId, leftColumns)
                + "</div></ops-panel></div>"
                + "<div class=\"col-12 col-xl-6\"><ops-panel class=\"card section-card h-100\"><div class=\"card-header\">" + escapeHtml(rightTitle) + "</div><div class=\"card-body\">"
                + tableShell(rightTableId, rightColumns)
                + "</div></ops-panel></div></div>";
    }

    private String sectionPair(String leftTitle,
                               String leftIntro,
                               String leftTableId,
                               int leftColumns,
                               String rightTitle,
                               String rightIntro,
                               String rightTableId,
                               int rightColumns) {
        return "<div class=\"row g-3 mb-3\">"
                + "<div class=\"col-12 col-xl-6\"><ops-panel class=\"card section-card h-100\"><div class=\"card-header\">" + escapeHtml(leftTitle) + "</div><div class=\"card-body\">"
                + sectionIntro(leftIntro)
                + tableShell(leftTableId, leftColumns)
                + "</div></ops-panel></div>"
                + "<div class=\"col-12 col-xl-6\"><ops-panel class=\"card section-card h-100\"><div class=\"card-header\">" + escapeHtml(rightTitle) + "</div><div class=\"card-body\">"
                + sectionIntro(rightIntro)
                + tableShell(rightTableId, rightColumns)
                + "</div></ops-panel></div></div>";
    }

    private String sectionPair(String leftTitle,
                               String leftIntro,
                               String leftTableId,
                               String[] leftHeaders,
                               String rightTitle,
                               String rightIntro,
                               String rightTableId,
                               String[] rightHeaders) {
        return "<div class=\"row g-3 mb-3\">"
                + "<div class=\"col-12 col-xl-6\"><ops-panel class=\"card section-card h-100\"><div class=\"card-header\">" + escapeHtml(leftTitle) + "</div><div class=\"card-body\">"
                + sectionIntro(leftIntro)
                + tableShellWithHead(leftTableId, leftHeaders)
                + "</div></ops-panel></div>"
                + "<div class=\"col-12 col-xl-6\"><ops-panel class=\"card section-card h-100\"><div class=\"card-header\">" + escapeHtml(rightTitle) + "</div><div class=\"card-body\">"
                + sectionIntro(rightIntro)
                + tableShellWithHead(rightTableId, rightHeaders)
                + "</div></ops-panel></div></div>";
    }

    private String tableShell(String bodyId, int columns) {
        return "<ops-data-table class=\"table-responsive\"><table class=\"table table-sm table-hover align-middle mb-0\"><tbody id=\""
                + escapeHtml(bodyId)
                + "\"><tr><td colspan=\""
                + columns
                + "\" class=\"text-muted\">" + escapeHtml(uiString("loadingText", "Loading…")) + "</td></tr></tbody></table></ops-data-table>";
    }

    private String tableShellWithHead(String bodyId, String... headers) {
        StringBuilder builder = new StringBuilder("<ops-data-table class=\"table-responsive\"><table class=\"table table-sm table-hover align-middle mb-0\">");
        builder.append("<thead><tr>");
        for (String header : headers) {
            builder.append("<th>").append(escapeHtml(header)).append("</th>");
        }
        builder.append("</tr></thead><tbody id=\"")
                .append(escapeHtml(bodyId))
                .append("\"><tr><td colspan=\"")
                .append(headers.length)
                .append("\" class=\"text-muted\">")
                .append(escapeHtml(uiString("loadingText", "Loading…")))
                .append("</td></tr></tbody></table></ops-data-table>");
        return builder.toString();
    }

    private String uiString(String key, String defaultValue) {
        String language = normalizeDashboardLanguage(dashboardLanguage.get());
        String scoped = System.getProperty("cachedb.admin.ui." + language + "." + key);
        if (scoped != null && !scoped.isBlank()) {
            return scoped;
        }
        String generic = System.getProperty("cachedb.admin.ui." + key);
        if (generic != null && !generic.isBlank()) {
            return generic;
        }
        String builtin = builtinDashboardTranslation(language, key);
        return builtin == null || builtin.isBlank() ? defaultValue : builtin;
    }

    private String escapeJs(String value) {
        return escapeJson(value).replace("'", "\\'");
    }

    private int uiInt(String key, int defaultValue) {
        String value = System.getProperty("cachedb.admin.ui." + key);
        return value == null || value.isBlank() ? defaultValue : Integer.parseInt(value.trim());
    }

    private String localized(String language, String trValue, String enValue) {
        return "en".equals(normalizeDashboardLanguage(language)) ? enValue : trValue;
    }

    private String normalizeDashboardLanguage(String value) {
        String normalized = value == null || value.isBlank()
                ? System.getProperty("cachedb.admin.ui.defaultLang", "tr")
                : value;
        String lower = normalized.trim().toLowerCase(Locale.ROOT);
        return "en".equals(lower) ? "en" : "tr";
    }

    private String builtinDashboardTranslation(String language, String key) {
        if ("en".equals(language)) {
            return switch (key) {
                case "dashboardTitle" -> "CacheDB Operations Console";
                case "navbarSubtitle" -> "Operational console for cache health, write-behind flow, recovery, query behavior, and incident triage.";
                case "loadingText" -> "Loading…";
                case "dashboardDataStatus.loadingTitle" -> "Loading data";
                case "dashboardDataStatus.loadingDetail" -> "Fetching the latest metrics and service state from the admin APIs.";
                case "dashboardDataStatus.loadingBadge" -> "Loading";
                case "dashboardDataStatus.successTitle" -> "Data is current";
                case "dashboardDataStatus.successDetail" -> "Metrics and service state were refreshed successfully.";
                case "dashboardDataStatus.errorTitle" -> "Data could not be loaded";
                case "dashboardDataStatus.errorDetail" -> "Admin data could not be fetched. Check networking, browser console, or admin API health.";
                case "dashboardDataStatus.metricError" -> "Data unavailable";
                case "dashboardDataStatus.partialErrorPrefix" -> "Some data sources could not be loaded: ";
                case "dashboardDataStatus.cachedHotFallbackDetail" -> "Live summary is delayed, so the last good snapshot is being shown.";
                case "liveRefreshTitle" -> "Live Refresh";
                case "lastUpdatedLoading" -> "refreshing…";
                case "lastUpdatedError" -> "update failed";
                case "resetToolsTitle" -> "Admin Reset Tools";
                case "primarySignalsTitle" -> "Primary Signals";
                case "supportingSignalsTitle" -> "Supporting Signals";
                case "metricTaxonomyTitle" -> "Metric Taxonomy";
                case "language.label" -> "Language";
                case "language.tr" -> "TR";
                case "language.en" -> "EN";
                case "scroll.toTopLabel" -> "Jump to Top";
                case "scroll.toBottomLabel" -> "Jump to Bottom";
                case "refreshNowLabel" -> "Refresh Now";
                case "toggleRefreshLabel" -> "Pause";
                case "pauseLabel" -> "Pause";
                case "resumeLabel" -> "Resume";
                case "resetTelemetryLabel" -> "Reset Telemetry History";
                case "resetTelemetryDescription" -> "Clears diagnostics, incident history, monitoring history, alert route history, and latency/performance measurements. It does not remove demo entity data.";
                case "resetTelemetryExplainTitle" -> "What does Reset Telemetry History do?";
                case "resetTelemetryExplainBody" -> "It clears diagnostics, incident history, monitoring history, alert route history, and latency/performance measurements. To remove demo entities, use Clear Data on the demo UI.";
                case "resetTelemetryPerformanceSegment" -> ", performance ops ";
                case "section.liveRefreshIntro" -> "These controls define how often the screen refreshes. Keep them on during load tests and pause when you want to inspect a single snapshot.";
                case "section.resetToolsIntro" -> "Use reset tools before a new observation run so older operational noise does not pollute the current reading.";
                case "section.liveTrendsIntro" -> "Trend charts show movement over time. Rising backlog with rising dead-letter typically points to writer pressure; rising memory without backlog points more to cache growth.";
                case "section.alertRouteTrendIntro" -> "Compare delivery and failure movement by channel here. A widening failure line means notification transport is unhealthy even if the main data path is still working.";
                case "section.incidentSeverityIntro" -> "Severity trend shows whether the platform is calming down or escalating. A short critical spike is operationally different from a long warning plateau.";
                case "section.topFailingSignalsIntro" -> "Start here when you need the shortest path to the loudest operational failures.";
                case "section.triageIntro" -> "Triage compresses many signals into one bottleneck guess. Use it as the first clue, then verify the diagnosis in the sections below.";
                case "section.serviceStatusIntro" -> "Each row represents a subsystem and the signal currently driving its state.";
                case "section.healthIntro" -> "Health gives the overall platform state. Read it together with incidents to understand why the state changed.";
                case "section.incidentsIntro" -> "These are the currently active alarm topics. A low incident count is only good if critical signals are also low.";
                case "section.deploymentIntro" -> "Deployment summarizes runtime posture such as schema mode, write-behind, compaction, guardrails, and key prefix.";
                case "section.schemaStatusIntro" -> "Schema status summarizes migration pressure and validation health. Watch this during upgrades and first starts.";
                case "section.schemaHistoryIntro" -> "Schema history is the structural audit trail. Repeated or failed entries deserve immediate attention.";
                case "section.starterProfilesIntro" -> "Profiles summarize supported deployment presets so you can compare intent versus the live runtime.";
                case "section.apiRegistryIntro" -> "Registry rows show which entities are exposed and how they are shaped. This helps when paging or query behavior looks surprising.";
                case "runtimeProfileControlTitle" -> "Runtime Profile Control";
                case "runtimeProfileControlIntro" -> "Inspect the active runtime profile, review every property it carries, and switch between AUTO, STANDARD, BALANCED, and AGGRESSIVE without restarting the process.";
                case "runtimeProfile.activeLabel" -> "Active Profile";
                case "runtimeProfile.modeLabel" -> "Mode";
                case "runtimeProfile.pressureLabel" -> "Observed Pressure";
                case "runtimeProfile.switchCountLabel" -> "Switch Count";
                case "runtimeProfile.lastSwitchedLabel" -> "Last Switched";
                case "runtimeProfile.manualOverrideLabel" -> "Manual Override";
                case "runtimeProfile.button.autoLabel" -> "Auto";
                case "runtimeProfile.button.standardLabel" -> "Standard";
                case "runtimeProfile.button.balancedLabel" -> "Balanced";
                case "runtimeProfile.button.aggressiveLabel" -> "Aggressive";
                case "runtimeProfile.statusIdle" -> "Runtime profile controls are ready.";
                case "runtimeProfile.statusLoading" -> "Updating runtime profile...";
                case "runtimeProfile.statusSuccessPrefix" -> "Runtime profile updated: ";
                case "runtimeProfile.statusErrorPrefix" -> "Runtime profile update failed: ";
                case "runtimeProfile.header.property" -> "Property";
                case "runtimeProfile.header.value" -> "Value";
                case "runtimeProfile.header.unit" -> "Unit";
                case "runtimeProfile.header.detail" -> "Meaning";
                case "howToReadTitle" -> "How To Read This Dashboard";
                case "sectionGuideTitle" -> "Section Guide";
                case "liveTrendsTitle" -> "Live Trends";
                case "liveTrends.backlogLabel" -> "Write-behind backlog";
                case "liveTrends.redisMemoryLabel" -> "Redis memory";
                case "liveTrends.deadLetterLabel" -> "Dead-letter backlog";
                case "alertRouteTrendsTitle" -> "Alert Route Trends";
                case "alertRouteTrends.deliveredLabel" -> "Channel delivered count";
                case "alertRouteTrends.failedLabel" -> "Channel failed count";
                case "incidentSeverityTrendsTitle" -> "Incident Severity Trends";
                case "topFailingSignalsTitle" -> "Top Failing Signals";
                case "tuning.capturedAtLabel" -> "Captured at";
                case "tuning.overrideCountLabel" -> "Explicit overrides";
                case "tuning.entryCountLabel" -> "Visible entries";
                case "tuning.exportJsonLabel" -> "Export JSON";
                case "tuning.exportMarkdownLabel" -> "Export Markdown";
                case "tuning.copyFlagsLabel" -> "Copy Startup Flags";
                case "tuning.exportStatusIdle" -> "Choose an export action.";
                case "section.tuningIntro" -> "This table shows the effective runtime configuration, not just defaults. Use it to answer which value is actually active right now.";
                case "section.certificationIntro" -> "Certification reports summarize recent production-gate style runs and benchmark outcomes for this build.";
                case "section.alertRoutingIntro" -> "Alert routing shows destination, escalation level, retry behavior, and delivery health for each notification path.";
                case "section.runbooksIntro" -> "Runbooks turn a signal into an operational action sequence. Start here when an alert needs a first response.";
                case "section.alertRouteHistoryIntro" -> "Route history helps distinguish a one-off notification failure from a sustained delivery problem.";
                case "section.schemaDdlIntro" -> "Schema DDL is a quick structural reference for generated persistence shape and registered entities.";
                case "section.runtimeProfileIntro" -> "Profile churn shows when the runtime changed posture under pressure. Frequent changes may indicate unstable thresholds or oscillating load.";
                case "section.explainIntro" -> "Explain turns a query into planner, relation, and step-level diagnostics. Start with triage cards, then inspect relation states and step costs.";
                case "metric.writeBehindDescription" -> "Count of items still waiting in the Redis write-behind queue.";
                case "metric.deadLetterDescription" -> "Count of failed writes that moved to dead-letter and may need replay or investigation.";
                case "metric.diagnosticsTitle" -> "Diagnostic Records";
                case "metric.diagnosticsDescription" -> "This is not an error count. It shows how many admin and diagnostic records are stored for troubleshooting.";
                case "metric.incidentsDescription" -> "Count of currently active alarm or health problem topics.";
                case "metric.learnedStatsDescription" -> "Count of learned planner statistics currently stored for query planning.";
                case "metric.redisMemoryDescription" -> "Redis used_memory across entity, cache, and stream keys.";
                case "metric.compactionPendingDescription" -> "Count of compacted but not yet fully processed pending states.";
                case "metric.runtimeProfileDescription" -> "Active runtime profile and most recent pressure level.";
                case "metric.alertDeliveredDescription" -> "Count of alerts successfully delivered across routes.";
                case "metric.alertFailedDescription" -> "Count of alert delivery failures observed across routes.";
                case "metric.alertDroppedDescription" -> "Count of alert deliveries dropped or abandoned.";
                case "metric.criticalSignalsDescription" -> "Count of active signals currently classified as critical.";
                case "metric.warningSignalsDescription" -> "Count of active signals currently classified as warning.";
                case "metric.writeBehindUnit", "metric.deadLetterUnit", "metric.diagnosticsUnit", "metric.incidentsUnit",
                        "metric.learnedStatsUnit", "metric.compactionPendingUnit", "metric.alertDeliveredUnit",
                        "metric.alertFailedUnit", "metric.alertDroppedUnit", "metric.criticalSignalsUnit",
                        "metric.warningSignalsUnit" -> "count";
                case "metric.redisMemoryUnit" -> "bytes";
                case "metric.runtimeProfileUnit" -> "state";
                case "triage.header.evidence" -> "Evidence";
                case "services.header.service" -> "Service";
                case "services.header.status" -> "Status";
                case "services.header.signal" -> "Key Signal";
                case "services.header.detail" -> "What It Means";
                case "health.header.issue" -> "Active Issue";
                case "incidents.header.severity" -> "Severity";
                case "incidents.header.code" -> "Code";
                case "incidents.header.detail" -> "Meaning";
                case "deployment.header.parameter", "schema.header.parameter" -> "Parameter";
                case "deployment.header.value", "schema.header.value" -> "Value";
                case "schemaHistory.header.time" -> "Recorded At";
                case "schemaHistory.header.operation" -> "Operation";
                case "schemaHistory.header.result" -> "Result";
                case "schemaHistory.header.progress" -> "Progress";
                case "profiles.header.name" -> "Profile";
                case "profiles.header.schemaMode" -> "Schema Mode";
                case "profiles.header.guardrails" -> "Guardrails";
                case "profiles.header.note" -> "Note";
                case "registry.header.entity" -> "Entity";
                case "registry.header.table" -> "Table";
                case "registry.header.columns" -> "Columns";
                case "registry.header.hotLimit" -> "Hot Limit";
                case "registry.header.pageSize" -> "Page Size";
                case "tuning.header.group" -> "Group";
                case "tuning.header.property" -> "Property";
                case "tuning.header.value" -> "Value";
                case "tuning.header.source" -> "Source";
                case "tuning.header.detail" -> "Meaning";
                case "certification.header.report" -> "Report";
                case "certification.header.status" -> "Status";
                case "certification.header.headline" -> "Headline";
                case "certification.header.file" -> "File";
                case "alertRouting.header.route" -> "Route";
                case "alertRouting.header.routeStatus" -> "Route Status";
                case "alertRouting.header.escalation" -> "Escalation";
                case "alertRouting.header.target" -> "Target";
                case "alertRouting.header.delivered" -> "Delivered";
                case "alertRouting.header.failed" -> "Failed";
                case "alertRouting.header.dropped" -> "Dropped";
                case "alertRouting.header.retry" -> "Retry Policy";
                case "alertRouting.header.lastState" -> "Last Delivery State";
                case "runbooks.header.code" -> "Code";
                case "runbooks.header.title" -> "Title";
                case "runbooks.header.firstAction" -> "First Action";
                case "runbooks.header.reference" -> "Reference";
                case "alertRouteHistory.header.time" -> "Recorded At";
                case "alertRouteHistory.header.route" -> "Route";
                case "alertRouteHistory.header.status" -> "Status";
                case "alertRouteHistory.header.delivered" -> "Delivered";
                case "alertRouteHistory.header.failed" -> "Failed";
                case "alertRouteHistory.header.lastError" -> "Last Error";
                case "churn.header.time" -> "Recorded At";
                case "churn.header.transition" -> "Profile Transition";
                case "churn.header.pressure" -> "Pressure Level";
                case "empty.highSignalFailures" -> "No high-signal failures detected.";
                case "empty.activeIssues" -> "No active issues";
                case "empty.activeIncidents" -> "No active incidents";
                case "empty.profileSwitches" -> "No profile switches";
                case "empty.migrationHistory" -> "No migration history";
                case "empty.starterProfiles" -> "No starter profiles";
                case "failingSignals.activeRecentPrefix" -> "Active ";
                case "failingSignals.activeRecentSeparator" -> " / Recent ";
                case "failingSignals.lastSeenPrefix" -> "Last seen ";
                case "profiles.guardrailsEnabledLabel" -> "guardrails";
                case "profiles.guardrailsDisabledLabel" -> "no guardrails";
                case "alertRouting.deliveredLabel" -> "delivered";
                case "alertRouting.idleLabel" -> "idle";
                case "deployment.autoApplyLabel" -> "auto-apply";
                case "deployment.manualLabel" -> "manual";
                case "deployment.writeBehindOnLabel" -> "write-behind on";
                case "deployment.writeBehindOffLabel" -> "write-behind off";
                case "deployment.workersSuffix" -> " workers";
                case "deployment.durableCompactionLabel" -> "durable compaction";
                case "deployment.noCompactionLabel" -> "no compaction";
                case "deployment.activeStreamsSuffix" -> " active streams";
                case "deployment.guardrailsOnLabel" -> "guardrails on";
                case "deployment.guardrailsOffLabel" -> "guardrails off";
                case "deployment.autoProfileSwitchLabel" -> "auto profile switch";
                case "deployment.manualProfileLabel" -> "manual profile";
                case "deployment.keyPrefixLabel" -> "key prefix";
                case "schema.migrationStepsLabel" -> "migration steps";
                case "schema.createTableStepsLabel" -> "create table steps";
                case "schema.addColumnStepsLabel" -> "add column steps";
                case "schema.ddlEntitiesLabel" -> "ddl entities";
                case "registry.columnsPrefix" -> "cols=";
                case "registry.hotPrefix" -> "hot=";
                case "registry.pagePrefix" -> "page=";
                case "explain.status.batchPreload" -> "Batch preload";
                case "explain.status.resolved" -> "Resolved";
                case "explain.status.noMatches" -> "No matches";
                case "explain.status.fetchLoaderMissing" -> "Fetch loader missing";
                case "explain.status.fetchPathUnverified" -> "Fetch path unverified";
                case "explain.status.fetchDepthExceeded" -> "Fetch depth exceeded";
                case "explain.status.unknownRelation" -> "Unknown relation";
                case "explain.status.targetBindingMissing" -> "Target binding missing";
                case "explain.status.mappedByUnresolved" -> "mappedBy unresolved";
                case "explain.usage.filter" -> "Filter";
                case "explain.usage.fetch" -> "Fetch";
                case "explain.fetchDepthExceededDetailTemplate" -> "Requested fetch path exceeds the allowed relation depth. Allowed: {allowed}, requested: {requested}.";
                case "explain.fetchLoaderMissingDetailTemplate" -> "This fetch path was requested, but no concrete relation loader is registered for the source entity. The relation cannot be preloaded until a RelationBatchLoader is bound.";
                case "explain.fetchPathUnverifiedSourceBindingTemplate" -> "The fetch path is declared, but the source entity binding is not registered. Source: {source}. The path cannot be verified.";
                case "explain.fetchPathUnverifiedTargetBindingTemplate" -> "A relation loader exists, but the target entity binding is missing. Target: {target}. mappedBy validation could not be completed.";
                case "explain.fetchPathUnverifiedMappedByTemplate" -> "A relation loader exists, but target metadata does not expose the mappedBy column. mappedBy: {mappedBy}. The fetch path could not be verified.";
                case "explain.fetchPathUnverifiedGenericTemplate" -> "The fetch path is declared, but its binding could not be fully verified. Check source binding, target binding, and mappedBy configuration.";
                case "explain.guidance.firstFixLabel" -> "First fix";
                case "explain.guidance.checkAreaLabel" -> "Check here";
                case "explain.guidance.likelyCauseLabel" -> "Likely root cause";
                case "explain.guidance.fetchDepthExceeded.firstFix" -> "Shorten the requested include path or raise maxFetchDepth if the extra depth is intentional.";
                case "explain.guidance.fetchDepthExceeded.checkArea" -> "Check RelationConfig.maxFetchDepth and the include paths passed into the query.";
                case "explain.guidance.fetchDepthExceeded.likelyCauseTemplate" -> "A nested fetch path is deeper than the configured safety limit. Allowed: {allowed}, requested: {requested}.";
                case "explain.guidance.fetchLoaderMissing.firstFix" -> "Register a concrete RelationBatchLoader for the source entity before using this fetch path.";
                case "explain.guidance.fetchLoaderMissing.checkAreaTemplate" -> "Check bootstrap and registry binding for source entity: {source}.";
                case "explain.guidance.fetchLoaderMissing.likelyCause" -> "The relation is declared in metadata, but the source binding is still using a no-op or missing relation loader.";
                case "explain.guidance.fetchPathUnverified.source.firstFix" -> "Register the source entity binding before requesting this fetch path.";
                case "explain.guidance.fetchPathUnverified.source.checkAreaTemplate" -> "Check source entity registration order and bootstrap wiring for: {source}.";
                case "explain.guidance.fetchPathUnverified.source.likelyCause" -> "Metadata exists, but the source entity was not fully registered when the fetch path was analyzed.";
                case "explain.guidance.fetchPathUnverified.target.firstFix" -> "Register the target entity binding so the relation path can be validated end-to-end.";
                case "explain.guidance.fetchPathUnverified.target.checkAreaTemplate" -> "Check target entity registration and mapping for: {target}.";
                case "explain.guidance.fetchPathUnverified.target.likelyCause" -> "A loader exists, but the target entity binding is absent so mappedBy validation cannot complete.";
                case "explain.guidance.fetchPathUnverified.mappedBy.firstFix" -> "Align the relation mappedBy value with a real column exposed by the target entity metadata.";
                case "explain.guidance.fetchPathUnverified.mappedBy.checkAreaTemplate" -> "Check target metadata and relation mappedBy configuration for column: {mappedBy}.";
                case "explain.guidance.fetchPathUnverified.mappedBy.likelyCause" -> "The relation points to a mappedBy field that the target entity metadata does not expose.";
                case "explain.guidance.fetchPathUnverified.generic.firstFix" -> "Verify source binding, target binding, and mappedBy configuration together.";
                case "explain.guidance.fetchPathUnverified.generic.checkArea" -> "Inspect relation bootstrap order and entity metadata on both sides of the relation.";
                case "explain.guidance.fetchPathUnverified.generic.likelyCause" -> "The relation is declared, but the runtime could not prove that the fetch path is safely wired.";
                case "explain.rawTitle" -> "Raw Explain JSON";
                default -> null;
            };
        }
        return switch (key) {
            case "dashboardTitle" -> "CacheDB Operasyon Konsolu";
            case "navbarSubtitle" -> "Önbellek sağlığı, arka plan yazma akışı, kurtarma süreci, sorgu davranışı ve olay önceliklendirmesi için operasyon ekranı.";
            case "loadingText" -> "Yükleniyor…";
            case "dashboardDataStatus.loadingTitle" -> "Veriler yükleniyor";
            case "dashboardDataStatus.loadingDetail" -> "Yönetim API'lerinden son metrikler ve servis durumu alınıyor.";
            case "dashboardDataStatus.loadingBadge" -> "Yükleniyor";
            case "dashboardDataStatus.successTitle" -> "Veri güncel";
            case "dashboardDataStatus.successDetail" -> "Metrikler ve servis durumu başarıyla yenilendi.";
            case "dashboardDataStatus.errorTitle" -> "Veri alınamadı";
            case "dashboardDataStatus.errorDetail" -> "Yönetim verisi alınamadı. Ağ, tarayıcı konsolu veya admin API durumunu kontrol et.";
            case "dashboardDataStatus.metricError" -> "Veri alınamadı";
            case "dashboardDataStatus.partialErrorPrefix" -> "Bazı veri kaynakları alınamadı: ";
            case "dashboardDataStatus.cachedHotFallbackDetail" -> "Canlı özet gecikse de son iyi görüntü gösteriliyor.";
            case "liveRefreshTitle" -> "Canlı Yenileme";
            case "lastUpdatedLoading" -> "yenileniyor…";
            case "lastUpdatedError" -> "güncellenemedi";
            case "autoRefreshLabel" -> "Otomatik Yenileme";
            case "lastUpdatedLabel" -> "Son Güncelleme";
            case "lastUpdatedNever" -> "henüz yok";
            case "resetToolsTitle" -> "Yönetim Sıfırlama Araçları";
            case "resetTelemetryNotRunYet" -> "Henüz telemetri sıfırlaması çalıştırılmadı.";
            case "primarySignalsTitle" -> "Ana Göstergeler";
            case "supportingSignalsTitle" -> "Destek Göstergeleri";
            case "metricTaxonomyTitle" -> "Metrik Rehberi";
            case "metricTaxonomy.queueTitle" -> "Kuyruk / Birikme";
            case "metricTaxonomy.queueBody" -> "Birim: adet. Bu sayı yükseliyorsa yazma hattı biriken işleri yetiştirmekte zorlanıyor olabilir.";
            case "metricTaxonomy.memoryTitle" -> "Bellek";
            case "metricTaxonomy.memoryBody" -> "Birim: byte. Redis belleği okunabilir biçimde gösterilir; istersen ham byte değerini de görebilirsin.";
            case "metricTaxonomy.healthTitle" -> "Sağlık / Sinyaller";
            case "metricTaxonomy.healthBody" -> "Birim: adet veya durum. Olay ve sinyal kartları şu anda kaç sorunun açık olduğunu gösterir.";
            case "metricTaxonomy.deliveryTitle" -> "Teslimat";
            case "metricTaxonomy.deliveryBody" -> "Birim: adet. Uyarı rotalarının teslim, hata ve düşürme davranışını özetler.";
            case "metricTaxonomy.queryTitle" -> "Sorgu / Açıklama";
            case "metricTaxonomy.queryBody" -> "Planlayıcı, ilişki durumu ve tahmini maliyet gibi teşhis alanları burada yer alır. Sorgunun neden yavaşladığını veya neden bozulduğunu anlamaya yardımcı olur.";
            case "metricTaxonomy.controlsTitle" -> "Kontroller / Sıfırlama";
            case "metricTaxonomy.controlsBody" -> "Birim yok. Yenileme, sıfırlama ve ayar dışa aktarma gibi işlemler burada toplanır. Gözlem akışını ve teşhis temizliğini yönetir.";
            case "metricTaxonomy.unitsTitle" -> "Birimler / Durumlar";
            case "metricTaxonomy.unitsBody" -> "Adet, byte ve durum etiketleri burada ayrılır. Sayısal metriklerle durum göstergelerini karıştırmamak için önce bu bölüme bakılır.";
            case "metricTaxonomy.intro" -> "Bu bölüm, üst tarafta gördüğün metriklerin ne anlattığını, hangi birimle okunduğunu ve hangi operasyon sorusuna cevap verdiğini kısa ve net biçimde özetler.";
            case "language.label" -> "Dil";
            case "language.tr" -> "TR";
            case "language.en" -> "EN";
            case "scroll.toTopLabel" -> "Yukarı Çık";
            case "scroll.toBottomLabel" -> "Aşağı İn";
            case "refreshNowLabel" -> "Şimdi Yenile";
            case "toggleRefreshLabel", "pauseLabel" -> "Duraklat";
            case "resumeLabel" -> "Sürdür";
            case "resetTelemetryLabel" -> "Telemetri Geçmişini Sıfırla";
            case "resetTelemetryDescription" -> "Tanılama, olay geçmişi, izleme geçmişi, uyarı rota geçmişi ve gecikme/performance ölçümlerini temizler. Demo verisini silmez.";
            case "resetTelemetryExplainTitle" -> "Telemetri Geçmişini Sıfırla ne yapar?";
            case "resetTelemetryExplainBody" -> "Tanılama, olay geçmişi, izleme geçmişi, uyarı rota geçmişi ve gecikme/performance ölçümlerini temizler. Demo verisini silmek için demo arayüzündeki Veriyi Temizle düğmesini kullanın.";
            case "resetTelemetryPerformanceSegment" -> ", performans işlemi ";
            case "howToReadTitle" -> "Bu Ekran Nasıl Okunur?";
            case "sectionGuideTitle" -> "Bölüm Rehberi";
            case "liveTrendsTitle" -> "Canlı Trendler";
            case "liveTrends.backlogLabel" -> "Arka plan yazma kuyruğu";
            case "liveTrends.redisMemoryLabel" -> "Redis belleği";
            case "liveTrends.deadLetterLabel" -> "Başarısız kayıt kuyruğu";
            case "alertRouteTrendsTitle" -> "Uyarı Rota Trendleri";
            case "alertRouteTrends.deliveredLabel" -> "Rota bazlı başarılı teslim";
            case "alertRouteTrends.failedLabel" -> "Rota bazlı başarısız teslim";
            case "incidentSeverityTrendsTitle" -> "Olay Öncelik Trendleri";
            case "topFailingSignalsTitle" -> "Öne Çıkan Sinyaller";
            case "howToRead.step1Title" -> "1. İlk bakış";
            case "howToRead.step1Body" -> "Üst kartlardaki arka plan yazma, Redis belleği, olaylar ve kritik sinyaller değerlerine bak. Yük arttığında genelde önce bunlar hareket eder.";
            case "howToRead.step2Title" -> "2. Sorun nerede?";
            case "howToRead.step2Body" -> "Önceliklendirme ve Servis Durumu bölümleri ana darboğazı ve en olası kök nedeni özetler. Operasyonel olarak önce burada başlanır.";
            case "howToRead.step3Title" -> "3. Sonraki adım ne olmalı?";
            case "howToRead.step3Body" -> "Uyarı Yönlendirme, Runbook'lar ve Öne Çıkan Sinyaller hangi sinyalin büyüdüğünü ve hangi aksiyonun uygun olduğunu gösterir.";
            case "tuning.capturedAtLabel" -> "Kaydedilme Zamanı";
            case "tuning.overrideCountLabel" -> "Açıkça Verilen Ayar";
            case "tuning.entryCountLabel" -> "Görünen Kayıt";
            case "tuning.exportJsonLabel" -> "JSON Aktar";
            case "tuning.exportMarkdownLabel" -> "Markdown Aktar";
            case "tuning.copyFlagsLabel" -> "Başlatma Bayraklarını Kopyala";
            case "tuning.exportStatusIdle" -> "Bir dışa aktarma işlemi seç.";
            case "sectionGuide.liveTrendsTitle" -> "Canlı Trendler";
            case "sectionGuide.liveTrendsBody" -> "Zaman içinde yazma kuyruğu, bellek ve başarısız kayıt kuyruğu hareketini izlersin. Yükselen yükü ilk burada fark ederiz.";
            case "sectionGuide.triageTitle" -> "Önceliklendirme";
            case "sectionGuide.triageBody" -> "Geçerli ana darboğazı tek satırda özetler; böylece nereden başlanacağına hızlıca karar veririz.";
            case "sectionGuide.topSignalsTitle" -> "Öne Çıkan Sinyaller";
            case "sectionGuide.topSignalsBody" -> "En gürültülü veya en ağır sinyalleri öne çıkarır; alarm yüzeyini daha kolay okumayı sağlar.";
            case "sectionGuide.routingTitle" -> "Uyarı Akışı / Runbook'lar";
            case "sectionGuide.routingBody" -> "Uyarının nereye gittiğini ve ekibin ilk hangi adımı atması gerektiğini gösterir.";
            case "signalDashboardTitle" -> "Sinyal Panosu";
            case "triageTitle" -> "Önceliklendirme";
            case "serviceStatusTitle" -> "Servis Durumu";
            case "healthTitle" -> "Sağlık";
            case "incidentsTitle" -> "Olaylar";
            case "deploymentTitle" -> "Dağıtım Özeti";
            case "schemaStatusTitle" -> "Şema Durumu";
            case "schemaHistoryTitle" -> "Şema Geçmişi";
            case "starterProfilesTitle" -> "Başlatıcı Profilleri";
            case "apiRegistryTitle" -> "API Kaydı";
            case "schemaDdlTitle" -> "Şema DDL";
            case "runtimeProfileChurnTitle" -> "Profil Geçiş Geçmişi";
            case "currentEffectiveTuningTitle" -> "Etkin Ayarlar";
            case "alertRoutingTitle" -> "Uyarı Yönlendirme";
            case "alertRouteHistoryTitle" -> "Uyarı Rota Geçmişi";
            case "runbooksTitle" -> "Runbook'lar";
            case "explainTitle" -> "Sorgu Açıklaması";
            case "section.liveRefreshIntro" -> "Bu bölüm ekranın ne sıklıkla yenileneceğini belirler. Yük testi sırasında açık tutun, belirli bir ana odaklanmak istediğinizde duraklatın.";
            case "section.resetToolsIntro" -> "Yeni bir gözlem seansından önce eski operasyonel gürültüyü temizlemek için bu araçları kullanın.";
            case "section.liveTrendsIntro" -> "Trend grafikleri yönü gösterir. Yazma kuyruğu ile başarısız kayıt kuyruğu birlikte yükseliyorsa genelde yazma hattında baskı vardır; bellek yazma kuyruğu olmadan artıyorsa daha çok önbellek büyümesi düşünülür.";
            case "section.alertRouteTrendIntro" -> "Burada kanal bazında teslim ve hata hareketini karşılaştırırsınız. Hata çizgisi açılıyorsa veri yolu ayakta olsa bile bildirim taşıma katmanında sorun vardır.";
            case "section.incidentSeverityIntro" -> "Öncelik trendi sistemin sakinleştiğini mi yoksa tırmandığını mı gösterir. Kısa bir kritik sıçrama ile uzun bir uyarı platosu aynı şey değildir.";
            case "section.topFailingSignalsIntro" -> "En gürültülü operasyonel sinyallere en kısa yoldan buradan bakılır.";
            case "section.triageIntro" -> "Önceliklendirme bölümü birçok metriği tek bir darboğaz tahmininde toplar. İlk ipucu olarak kullanın, sonra aşağıdaki bölümlerde doğrulayın.";
            case "section.serviceStatusIntro" -> "Her satır bir alt sistemi ve o alt sistemin durumunu belirleyen ana sinyali gösterir.";
            case "section.healthIntro" -> "Sağlık bölümü genel durumu verir. Neden değiştiğini anlamak için olaylar bölümü ile birlikte okunmalıdır.";
            case "section.incidentsIntro" -> "Bunlar şu anda aktif olan alarm başlıklarıdır. Olay sayısı az olsa bile kritik sinyal yüksekse sistem rahat değildir.";
            case "section.deploymentIntro" -> "Dağıtım bölümü; şema modu, arka plan yazma, sıkıştırma, koruma kuralları ve anahtar ön eki gibi canlı çalışma çerçevesini özetler.";
            case "section.schemaStatusIntro" -> "Şema durumu; geçiş baskısını, doğrulama sonucunu ve şema sağlığını özetler. İlk açılışlarda ve sürüm geçişlerinde özellikle önemlidir.";
            case "section.schemaHistoryIntro" -> "Şema geçmişi yapısal değişikliklerin denetim izidir. Tekrarlayan veya başarısız kayıtlar hemen incelenmelidir.";
            case "section.starterProfilesIntro" -> "Profiller desteklenen dağıtım önayarlarını özetler; hedeflenen yapı ile canlı ortamı karşılaştırmayı kolaylaştırır.";
            case "section.apiRegistryIntro" -> "Kayıt tablosu hangi varlıkların açık olduğunu ve nasıl tanımlandığını gösterir. Sayfalama veya sorgu davranışı şaşırtıcıysa önce burada bakılır.";
            case "runtimeProfileControlTitle" -> "Çalışma Profili Kontrolü";
            case "runtimeProfileControlIntro" -> "Aktif çalışma profilini inceleyin, taşıdığı tüm ayarları görün ve süreci yeniden başlatmadan AUTO, STANDARD, BALANCED ve AGGRESSIVE profilleri arasında geçiş yapın.";
            case "runtimeProfile.activeLabel" -> "Aktif Profil";
            case "runtimeProfile.modeLabel" -> "Mod";
            case "runtimeProfile.pressureLabel" -> "Gözlenen Baskı";
            case "runtimeProfile.switchCountLabel" -> "Geçiş Sayısı";
            case "runtimeProfile.lastSwitchedLabel" -> "Son Geçiş";
            case "runtimeProfile.manualOverrideLabel" -> "Elle Zorlama";
            case "runtimeProfile.button.autoLabel" -> "Otomatik";
            case "runtimeProfile.button.standardLabel" -> "Standart";
            case "runtimeProfile.button.balancedLabel" -> "Dengeli";
            case "runtimeProfile.button.aggressiveLabel" -> "Agresif";
            case "runtimeProfile.statusIdle" -> "Çalışma profili kontrolü hazır.";
            case "runtimeProfile.statusLoading" -> "Çalışma profili güncelleniyor...";
            case "runtimeProfile.statusSuccessPrefix" -> "Çalışma profili güncellendi: ";
            case "runtimeProfile.statusErrorPrefix" -> "Çalışma profili güncellenemedi: ";
            case "runtimeProfile.header.property" -> "Özellik";
            case "runtimeProfile.header.value" -> "Değer";
            case "runtimeProfile.header.unit" -> "Birim";
            case "runtimeProfile.header.detail" -> "Anlamı";
            case "section.tuningIntro" -> "Bu tablo sadece varsayılanları değil, şu anda gerçekten çalışan etkin ayarları gösterir.";
            case "section.certificationIntro" -> "Sertifikasyon raporları bu derleme için son production-gate koşularının ve benchmark sonuçlarının özetidir.";
            case "section.alertRoutingIntro" -> "Uyarı yönlendirme bölümü, her bildirim yolunun hedefini, yükseltme seviyesini, yeniden deneme davranışını ve teslim sağlığını gösterir.";
            case "section.runbooksIntro" -> "Runbook'lar sinyali operasyon adımına çevirir. Uyarı geldiğinde ilk bakılacak yerlerden biridir.";
            case "section.alertRouteHistoryIntro" -> "Rota geçmişi, tek seferlik bildirim hatası ile sürekli teslim sorununu ayırt etmeye yardımcı olur.";
            case "section.schemaDdlIntro" -> "Şema DDL bölümü, üretilen saklama yapısının hızlı bir referans görünümünü verir.";
            case "section.runtimeProfileIntro" -> "Profil geçişleri, sistemin baskı altında ne zaman davranış değiştirdiğini gösterir. Sık değişim eşiklerin veya yük davranışının kararsız olduğuna işaret edebilir.";
            case "section.explainIntro" -> "Sorgu Açıklaması bölümü, bir sorguyu planlayıcı, ilişki çözümü ve adım bazında teşhis eder. Önce özet kartlara, sonra ilişki durumu ve adım maliyetlerine bakın.";
            case "explain.entityLabel" -> "Varlık";
            case "explain.filterLabel" -> "Filtre";
            case "explain.sortLabel" -> "Sıralama";
            case "explain.limitLabel" -> "Sınır";
            case "explain.includeLabel" -> "İlişkiler";
            case "explain.noteTitleLabel" -> "Not Başlığı";
            case "explain.noteTitleDefault" -> "Sorgu açıklaması notu";
            case "runExplainLabel" -> "Açıklamayı Çalıştır";
            case "explain.copyMarkdownLabel" -> "Markdown Olarak Kopyala";
            case "explain.downloadJsonLabel" -> "JSON Olarak İndir";
            case "explain.saveNoteLabel" -> "Olay Notu Olarak Kaydet";
            case "explain.actionStatusIdle" -> "Açıklama araçları hazır.";
            case "explain.helpText" -> "Varlık, filtre veya ilişkileri değiştirdikten sonra açıklamayı çalıştırın. İlişki Durumları tablosu, bir yolun çözüldüğünü mü, bozulduğunu mu, yoksa sadece ön yükleme için mi kullanıldığını gösterir.";
            case "explain.summary.plannerLabel" -> "Planlayıcı";
            case "explain.summary.sortLabel" -> "Sıralama";
            case "explain.summary.candidatesLabel" -> "Adaylar";
            case "explain.summary.warningsLabel" -> "Uyarılar";
            case "explain.summary.estimatedCostLabel" -> "Tahmini Maliyet";
            case "explain.summary.fullyIndexedLabel" -> "Tam İndeksli";
            case "explain.summary.stepCountLabel" -> "Açıklama Adımları";
            case "explain.triage.whySlowLabel" -> "Neden Yavaş?";
            case "explain.triage.whyDegradedLabel" -> "Neden Bozuldu?";
            case "explain.triage.suspiciousStepLabel" -> "En Şüpheli Adım";
            case "explain.warningsTitle" -> "Açıklama Uyarıları";
            case "explain.warningHeader" -> "uyarı";
            case "explain.relationStatesTitle" -> "İlişki Durumları";
            case "explain.relationFilter.allLabel" -> "Tümü";
            case "explain.relationFilter.filterLabel" -> "Filtre";
            case "explain.relationFilter.fetchLabel" -> "Getirme";
            case "explain.relationFilter.warningsOnlyLabel" -> "Sadece uyarılar";
            case "explain.relationHeader.relation" -> "ilişki";
            case "explain.relationHeader.usage" -> "kullanım";
            case "explain.relationHeader.status" -> "durum";
            case "explain.relationHeader.kind" -> "tür";
            case "explain.relationHeader.target" -> "hedef";
            case "explain.relationHeader.mappedBy" -> "eşlenen alan";
            case "explain.relationHeader.candidates" -> "adaylar";
            case "explain.relationHeader.detail" -> "açıklama";
            case "explain.status.batchPreload" -> "Toplu ön yükleme";
            case "explain.status.resolved" -> "Çözüldü";
            case "explain.status.noMatches" -> "Eşleşme yok";
            case "explain.status.fetchLoaderMissing" -> "Fetch yükleyicisi eksik";
            case "explain.status.fetchPathUnverified" -> "Fetch yolu doğrulanamadı";
            case "explain.status.fetchDepthExceeded" -> "Fetch derinliği aşıldı";
            case "explain.status.unknownRelation" -> "Bilinmeyen ilişki";
            case "explain.status.targetBindingMissing" -> "Hedef binding eksik";
            case "explain.status.mappedByUnresolved" -> "mappedBy çözümlenemedi";
            case "explain.usage.filter" -> "Filtre";
            case "explain.usage.fetch" -> "Getirme";
            case "explain.fetchDepthExceededDetailTemplate" -> "İstenen fetch yolu izin verilen ilişki derinliğini aşıyor. İzin verilen: {allowed}, istenen: {requested}.";
            case "explain.fetchLoaderMissingDetailTemplate" -> "Bu fetch yolu istendi, ancak kaynak varlık için somut bir ilişki yükleyicisi kayıtlı değil. Bir RelationBatchLoader bağlanmadan ilişki önceden yüklenemez.";
            case "explain.fetchPathUnverifiedSourceBindingTemplate" -> "Fetch yolu metadata'da tanımlı, ancak kaynak varlık binding'i kayıtlı değil. Kaynak: {source}. Bu yüzden yol doğrulanamadı.";
            case "explain.fetchPathUnverifiedTargetBindingTemplate" -> "Bir ilişki yükleyicisi var, ancak hedef varlığın binding'i eksik. Hedef: {target}. mappedBy doğrulaması tamamlanamadı.";
            case "explain.fetchPathUnverifiedMappedByTemplate" -> "Bir ilişki yükleyicisi var, ancak hedef metadata mappedBy kolonunu göstermiyor. mappedBy: {mappedBy}. Bu yüzden fetch yolu doğrulanamadı.";
            case "explain.fetchPathUnverifiedGenericTemplate" -> "Fetch yolu tanımlı görünüyor, ancak binding doğrulaması tamamlanamadı. Kaynak binding, hedef binding ve mappedBy ayarını kontrol et.";
            case "explain.guidance.firstFixLabel" -> "İlk düzeltme önerisi";
            case "explain.guidance.checkAreaLabel" -> "Kontrol edilecek yer";
            case "explain.guidance.likelyCauseLabel" -> "Muhtemel kök neden";
            case "explain.guidance.fetchDepthExceeded.firstFix" -> "İstenen include yolunu kısalt veya bu derinlik bilinçli ise maxFetchDepth değerini artır.";
            case "explain.guidance.fetchDepthExceeded.checkArea" -> "RelationConfig.maxFetchDepth değerini ve sorguya verilen include yollarını kontrol et.";
            case "explain.guidance.fetchDepthExceeded.likelyCauseTemplate" -> "İç içe bir fetch yolu güvenlik için tanımlanan sınırı aşıyor. İzin verilen: {allowed}, istenen: {requested}.";
            case "explain.guidance.fetchLoaderMissing.firstFix" -> "Bu fetch yolunu kullanmadan önce kaynak varlık için somut bir RelationBatchLoader kaydet.";
            case "explain.guidance.fetchLoaderMissing.checkAreaTemplate" -> "Kaynak varlık için bootstrap ve registry binding tanımını kontrol et: {source}.";
            case "explain.guidance.fetchLoaderMissing.likelyCause" -> "İlişki metadata'da tanımlı, ancak kaynak binding hâlâ no-op veya eksik bir relation loader kullanıyor.";
            case "explain.guidance.fetchPathUnverified.source.firstFix" -> "Bu fetch yolunu istemeden önce kaynak varlık binding'ini kaydet.";
            case "explain.guidance.fetchPathUnverified.source.checkAreaTemplate" -> "Kaynak varlık kayıt sırasını ve bootstrap wiring'ini kontrol et: {source}.";
            case "explain.guidance.fetchPathUnverified.source.likelyCause" -> "Metadata var, ancak fetch yolu analiz edilirken kaynak varlık tam olarak kayıtlı değildi.";
            case "explain.guidance.fetchPathUnverified.target.firstFix" -> "İlişki yolunun uçtan uca doğrulanabilmesi için hedef varlık binding'ini kaydet.";
            case "explain.guidance.fetchPathUnverified.target.checkAreaTemplate" -> "Hedef varlık kayıtlarını ve mapping tanımını kontrol et: {target}.";
            case "explain.guidance.fetchPathUnverified.target.likelyCause" -> "Bir loader var, ancak hedef varlık binding'i olmadığı için mappedBy doğrulaması tamamlanamıyor.";
            case "explain.guidance.fetchPathUnverified.mappedBy.firstFix" -> "Relation mappedBy değerini hedef varlık metadata'sında gerçekten bulunan bir kolonla hizala.";
            case "explain.guidance.fetchPathUnverified.mappedBy.checkAreaTemplate" -> "Hedef metadata'yı ve relation mappedBy ayarını kontrol et. Kolon: {mappedBy}.";
            case "explain.guidance.fetchPathUnverified.mappedBy.likelyCause" -> "İlişki, hedef varlık metadata'sının açığa çıkarmadığı bir mappedBy alanına işaret ediyor.";
            case "explain.guidance.fetchPathUnverified.generic.firstFix" -> "Kaynak binding, hedef binding ve mappedBy ayarını birlikte doğrula.";
            case "explain.guidance.fetchPathUnverified.generic.checkArea" -> "İlişkinin iki ucundaki bootstrap sırasını ve entity metadata tanımını incele.";
            case "explain.guidance.fetchPathUnverified.generic.likelyCause" -> "İlişki tanımlı görünüyor, ancak runtime fetch yolunun güvenle bağlı olduğunu kanıtlayamadı.";
            case "explain.stepsTitle" -> "Açıklama Adımları";
            case "explain.stepHeader.stage" -> "aşama";
            case "explain.stepHeader.strategy" -> "strateji";
            case "explain.stepHeader.indexed" -> "indeksli";
            case "explain.stepHeader.candidates" -> "adaylar";
            case "explain.stepHeader.cost" -> "maliyet";
            case "explain.stepHeader.expression" -> "ifade";
            case "explain.stepHeader.detail" -> "açıklama";
            case "explain.rawTitle" -> "Ham Açıklama JSON";
            case "explain.triage.whySlowDefault" -> "Belirgin bir darboğaz görünmüyor";
            case "explain.triage.whySlowDefaultDetail" -> "Tahmini maliyet düşük ya da adımlara dengeli dağılmış görünüyor.";
            case "explain.triage.highCostLabel" -> "Yüksek tahmini maliyet";
            case "explain.triage.highCostDetail" -> "Planlayıcı görece pahalı bir yol seçmiş görünüyor.";
            case "explain.triage.largeCandidateSetLabel" -> "Geniş aday kümesi";
            case "explain.triage.largeCandidateSetDetail" -> "Geniş aday kümesi sonraki aşamalara kadar taşındığı için bellek içi iş artmış olabilir.";
            case "explain.triage.whyDegradedDefault" -> "Bozulma görünmüyor";
            case "explain.triage.whyDegradedDefaultDetail" -> "Planlayıcı indeksli ya da sağlıklı ilişki yollarında kaldı.";
            case "explain.triage.degradedFullScanLabel" -> "Tam tarama geri dönüşü";
            case "explain.triage.degradedFullScanDetail" -> "Sorgu indeksleri devre dışı kaldığı ya da shed edildiği için depo, anahtarları tarayıp artakalan filtreleri uyguladı.";
            case "explain.triage.warningDrivenLabel" -> "Uyarı bulundu";
            case "explain.triage.noSuspiciousStepLabel" -> "Şüpheli adım görünmüyor";
            case "explain.triage.noSuspiciousStepDetail" -> "Açıklama adımlarında olağan dışı pahalı görünen bir aşama yok.";
            case "empty.explainRelationStates" -> "İlişki durumu yok";
            case "empty.explainSteps" -> "Açıklama adımı yok";
            case "explain.actionLoading" -> "Açıklama işlemi hazırlanıyor...";
            case "explain.copyMarkdownSuccess" -> "Açıklama markdown olarak panoya kopyalandı.";
            case "explain.downloadJsonSuccess" -> "Açıklama JSON olarak indirildi.";
            case "explain.saveNoteSuccessPrefix" -> "Açıklama notu kaydedildi: ";
            case "explain.actionErrorPrefix" -> "Açıklama işlemi başarısız oldu: ";
            case "alertRouting.deliveredLabel" -> "teslim edildi";
            case "alertRouting.idleLabel" -> "beklemede";
            case "metric.writeBehindTitle" -> "Arka Plan Yazma";
            case "metric.deadLetterTitle" -> "Başarısız Kayıt Kuyruğu";
            case "metric.diagnosticsTitle" -> "Tanılama Kayıtları";
            case "metric.incidentsTitle" -> "Açık Olaylar";
            case "metric.learnedStatsTitle" -> "Öğrenilmiş İstatistikler";
            case "metric.redisMemoryTitle" -> "Redis Belleği";
            case "metric.compactionPendingTitle" -> "Bekleyen Sıkıştırma";
            case "metric.runtimeProfileTitle" -> "Çalışma Profili";
            case "metric.alertDeliveredTitle" -> "Teslim Edilen Uyarılar";
            case "metric.alertFailedTitle" -> "Başarısız Uyarılar";
            case "metric.alertDroppedTitle" -> "Düşürülen Uyarılar";
            case "metric.criticalSignalsTitle" -> "Kritik Sinyaller";
            case "metric.warningSignalsTitle" -> "Uyarı Sinyalleri";
            case "metric.writeBehindDescription" -> "Redis arka plan yazma kuyruğunda henüz işlenmemiş iş sayısı.";
            case "metric.deadLetterDescription" -> "Başarısız olup başarısız kayıt kuyruğuna düşmüş, inceleme veya yeniden işleme bekleyen kayıt sayısı.";
            case "metric.diagnosticsDescription" -> "Bu sayı hata adedi değildir. Sorun analizi için saklanan yönetim ve tanılama kaydı sayısını gösterir.";
            case "metric.incidentsDescription" -> "Şu anda aktif alarm veya sağlık problemi başlığı sayısı.";
            case "metric.learnedStatsDescription" -> "Sorgu planlayıcısı için tutulan öğrenilmiş istatistik sayısı.";
            case "metric.redisMemoryDescription" -> "Redis used_memory değeridir. Varlık, önbellek ve stream anahtarlarının toplam belleğini gösterir.";
            case "metric.compactionPendingDescription" -> "Sıkıştırılmış ama henüz tamamen işlenmemiş pending durum sayısı.";
            case "metric.runtimeProfileDescription" -> "Aktif çalışma profili ve son gözlenen baskı seviyesi.";
            case "metric.alertDeliveredDescription" -> "Rotalarda başarıyla teslim edilmiş uyarı sayısı.";
            case "metric.alertFailedDescription" -> "Rotalarda başarısız olmuş uyarı teslim sayısı.";
            case "metric.alertDroppedDescription" -> "Düşürülmüş veya vazgeçilmiş uyarı teslim sayısı.";
            case "metric.criticalSignalsDescription" -> "Şu anda kritik seviyede açık olan sinyal sayısı.";
            case "metric.warningSignalsDescription" -> "Şu anda uyarı seviyesinde açık olan sinyal sayısı.";
            case "metric.writeBehindUnit", "metric.deadLetterUnit", "metric.diagnosticsUnit", "metric.incidentsUnit",
                    "metric.learnedStatsUnit", "metric.compactionPendingUnit", "metric.alertDeliveredUnit",
                    "metric.alertFailedUnit", "metric.alertDroppedUnit", "metric.criticalSignalsUnit",
                    "metric.warningSignalsUnit" -> "adet";
            case "metric.redisMemoryUnit" -> "byte";
            case "metric.runtimeProfileUnit" -> "durum";
            case "triage.header.evidence" -> "Kanıt";
            case "services.header.service" -> "Servis";
            case "services.header.status" -> "Durum";
            case "services.header.signal" -> "Ana Gösterge";
            case "services.header.detail" -> "Ne Anlama Geliyor";
            case "health.header.issue" -> "Aktif Sorun";
            case "incidents.header.severity" -> "Seviye";
            case "incidents.header.code" -> "Kod";
            case "incidents.header.detail" -> "Açıklama";
            case "deployment.header.parameter", "schema.header.parameter" -> "Parametre";
            case "deployment.header.value", "schema.header.value" -> "Değer";
            case "schemaHistory.header.time" -> "Kayıt Zamanı";
            case "schemaHistory.header.operation" -> "İşlem";
            case "schemaHistory.header.result" -> "Sonuç";
            case "schemaHistory.header.progress" -> "İlerleme";
            case "profiles.header.name" -> "Profil";
            case "profiles.header.schemaMode" -> "Şema Modu";
            case "profiles.header.guardrails" -> "Koruma Kuralları";
            case "profiles.header.note" -> "Not";
            case "registry.header.entity" -> "Varlık";
            case "registry.header.table" -> "Tablo";
            case "registry.header.columns" -> "Kolon";
            case "registry.header.hotLimit" -> "Sıcak Kayıt Limiti";
            case "registry.header.pageSize" -> "Sayfa Boyutu";
            case "tuning.header.group" -> "Grup";
            case "tuning.header.property" -> "Özellik";
            case "tuning.header.value" -> "Değer";
            case "tuning.header.source" -> "Kaynak";
            case "tuning.header.detail" -> "Açıklama";
            case "certification.header.report" -> "Rapor";
            case "certification.header.status" -> "Durum";
            case "certification.header.headline" -> "Özet";
            case "certification.header.file" -> "Dosya";
            case "alertRouting.header.route" -> "Rota";
            case "alertRouting.header.routeStatus" -> "Rota Durumu";
            case "alertRouting.header.escalation" -> "Yükseltme Düzeyi";
            case "alertRouting.header.target" -> "Hedef";
            case "alertRouting.header.delivered" -> "Teslim";
            case "alertRouting.header.failed" -> "Hata";
            case "alertRouting.header.dropped" -> "Düşen";
            case "alertRouting.header.retry" -> "Yeniden Deneme Politikası";
            case "alertRouting.header.lastState" -> "Son Teslim Durumu";
            case "runbooks.header.code" -> "Kod";
            case "runbooks.header.title" -> "Başlık";
            case "runbooks.header.firstAction" -> "İlk Aksiyon";
            case "runbooks.header.reference" -> "Referans";
            case "alertRouteHistory.header.time" -> "Kayıt Zamanı";
            case "alertRouteHistory.header.route" -> "Rota";
            case "alertRouteHistory.header.status" -> "Durum";
            case "alertRouteHistory.header.delivered" -> "Teslim";
            case "alertRouteHistory.header.failed" -> "Hata";
            case "alertRouteHistory.header.lastError" -> "Son Hata";
            case "churn.header.time" -> "Kayıt Zamanı";
            case "churn.header.transition" -> "Profil Geçişi";
            case "churn.header.pressure" -> "Baskı Seviyesi";
            case "empty.highSignalFailures" -> "Şu anda öne çıkan yüksek sinyalli hata görünmüyor.";
            case "empty.activeIssues" -> "Şu anda aktif bir sorun görünmüyor.";
            case "empty.activeIncidents" -> "Şu anda aktif bir olay görünmüyor.";
            case "empty.profileSwitches" -> "Henüz profil geçişi yok.";
            case "empty.migrationHistory" -> "Henüz şema geçmişi kaydı yok.";
            case "empty.starterProfiles" -> "Görüntülenecek profil bulunamadı.";
            case "failingSignals.activeRecentPrefix" -> "Açık ";
            case "failingSignals.activeRecentSeparator" -> " / Son dönemde ";
            case "failingSignals.lastSeenPrefix" -> "Son görülme ";
            case "profiles.guardrailsEnabledLabel" -> "koruma kuralları açık";
            case "profiles.guardrailsDisabledLabel" -> "koruma kuralları kapalı";
            case "deployment.autoApplyLabel" -> "otomatik uygula";
            case "deployment.manualLabel" -> "elle";
            case "deployment.writeBehindOnLabel" -> "arka plan yazma açık";
            case "deployment.writeBehindOffLabel" -> "arka plan yazma kapalı";
            case "deployment.workersSuffix" -> " iş parçacığı";
            case "deployment.durableCompactionLabel" -> "kalıcı sıkıştırma";
            case "deployment.noCompactionLabel" -> "sıkıştırma kapalı";
            case "deployment.activeStreamsSuffix" -> " aktif akış";
            case "deployment.guardrailsOnLabel" -> "koruma kuralları açık";
            case "deployment.guardrailsOffLabel" -> "koruma kuralları kapalı";
            case "deployment.autoProfileSwitchLabel" -> "otomatik profil geçişi";
            case "deployment.manualProfileLabel" -> "elle profil seçimi";
            case "deployment.keyPrefixLabel" -> "anahtar ön eki";
            case "schema.migrationStepsLabel" -> "geçiş adımı";
            case "schema.createTableStepsLabel" -> "tablo oluşturma adımı";
            case "schema.addColumnStepsLabel" -> "kolon ekleme adımı";
            case "schema.ddlEntitiesLabel" -> "DDL varlık sayısı";
            case "registry.columnsPrefix" -> "kolon=";
            case "registry.hotPrefix" -> "sıcak=";
            case "registry.pagePrefix" -> "sayfa=";
            default -> null;
        };
    }

    private String renderRefreshOptionsHtml(String language) {
        String raw = System.getProperty(
                "cachedb.admin.ui.refreshOptions",
                localized(language,
                        "0:Duraklatıldı,3000:3 saniye,5000:5 saniye,10000:10 saniye,30000:30 saniye,60000:60 saniye",
                        "0:Paused,3000:3 seconds,5000:5 seconds,10000:10 seconds,30000:30 seconds,60000:60 seconds")
        );
        int selected = uiInt("defaultRefreshMillis", 5000);
        StringBuilder builder = new StringBuilder();
        for (String entry : raw.split(",")) {
            String normalized = entry.trim();
            if (normalized.isEmpty()) {
                continue;
            }
            String[] parts = normalized.split(":", 2);
            if (parts.length != 2) {
                continue;
            }
            builder.append("<option value=\"")
                    .append(escapeHtml(parts[0].trim()))
                    .append("\"");
            if (Integer.parseInt(parts[0].trim()) == selected) {
                builder.append(" selected");
            }
            builder.append(">")
                    .append(escapeHtml(parts[1].trim()))
                    .append("</option>");
        }
        return builder.toString();
    }

    private void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().add("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private String appendQuery(String path, String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return path;
        }
        return path + "?" + rawQuery;
    }

    private void sendJson(HttpExchange exchange, int statusCode, String body) throws IOException {
        sendText(exchange, statusCode, "application/json", body);
    }

    private void sendText(HttpExchange exchange, int statusCode, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        exchange.getResponseHeaders().set("Pragma", "no-cache");
        exchange.getResponseHeaders().set("Expires", "0");
        if (config.corsEnabled()) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        }
        exchange.sendResponseHeaders(statusCode, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private void sendMethodNotAllowed(HttpExchange exchange) throws IOException {
        sendText(exchange, 405, "text/plain; charset=utf-8", "Method not allowed");
    }

    private String toJsonStringArray(List<String> values) {
        StringBuilder builder = new StringBuilder("[");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            builder.append('"').append(escapeJson(values.get(index))).append('"');
        }
        builder.append(']');
        return builder.toString();
    }

    private int parseInt(List<String> values, int defaultValue) {
        if (values == null || values.isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(values.get(0));
        } catch (NumberFormatException exception) {
            return defaultValue;
        }
    }

    private long parseLong(List<String> values, long defaultValue) {
        if (values == null || values.isEmpty()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(values.get(0));
        } catch (NumberFormatException exception) {
            return defaultValue;
        }
    }

    private boolean parseBoolean(List<String> values, boolean defaultValue) {
        if (values == null || values.isEmpty()) {
            return defaultValue;
        }
        String value = defaultString(values.get(0)).trim();
        if (value.isBlank()) {
            return defaultValue;
        }
        if ("true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value) || "on".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value) || "0".equals(value) || "no".equalsIgnoreCase(value) || "off".equalsIgnoreCase(value)) {
            return false;
        }
        return defaultValue;
    }

    private AdminExportFormat parseFormat(String value) {
        return value == null || value.isBlank()
                ? AdminExportFormat.JSON
                : AdminExportFormat.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    private String first(Map<String, List<String>> values, String key) {
        List<String> items = values.get(key);
        return items == null || items.isEmpty() ? null : items.get(0);
    }

    private String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private String escapeJson(String value) {
        String safe = defaultString(value);
        StringBuilder builder = new StringBuilder(safe.length() + 16);
        for (int index = 0; index < safe.length(); index++) {
            char current = safe.charAt(index);
            switch (current) {
                case '\\' -> builder.append("\\\\");
                case '"' -> builder.append("\\\"");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (current < 0x20) {
                        builder.append(String.format(Locale.ROOT, "\\u%04x", (int) current));
                    } else {
                        builder.append(current);
                    }
                }
            }
        }
        return builder.toString();
    }

    private String escapeHtml(String value) {
        String safe = defaultString(value);
        return safe.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private String shellEscape(String value) {
        String safe = defaultString(value);
        if (safe.isEmpty()) {
            return "\"\"";
        }
        if (safe.chars().noneMatch(ch -> Character.isWhitespace(ch) || ch == '"' || ch == '\'')) {
            return safe;
        }
        return "\"" + safe.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private String markdownEscape(String value) {
        String safe = defaultString(value);
        return safe.replace("|", "\\|").replace("\n", " ").replace("\r", " ");
    }

    private String defaultString(String value) {
        return Objects.requireNonNullElse(value, "");
    }

    @Override
    public void close() {
        running.set(false);
        if (server != null) {
            server.stop(0);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private static final class AdminHttpThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "cachedb-admin-http");
            thread.setDaemon(true);
            return thread;
        }
    }

    public static final class AdminHttpResponse {
        private final int statusCode;
        private final Map<String, List<String>> headers;
        private final byte[] body;

        private AdminHttpResponse(int statusCode, Map<String, List<String>> headers, byte[] body) {
            this.statusCode = statusCode;
            this.headers = headers;
            this.body = body;
        }

        public int statusCode() {
            return statusCode;
        }

        public Map<String, List<String>> headers() {
            return headers;
        }

        public byte[] body() {
            return body;
        }
    }

    private record MigrationPlannerPageState(
            MigrationSchemaDiscovery.Result discovery,
            Map<String, String> formValues,
            MigrationPlannerDemoSupport.BootstrapResult demoBootstrapResult,
            String demoBootstrapError,
            MigrationPlanner.Result planResult,
            String planError
    ) {
    }

    private record PlannerSelectOption(String value, String label) {
    }

    public static final class DashboardTemplateModel {
        private final String language;
        private final String headMarkup;
        private final String bodyMarkup;

        private DashboardTemplateModel(String language, String headMarkup, String bodyMarkup) {
            this.language = language;
            this.headMarkup = headMarkup;
            this.bodyMarkup = bodyMarkup;
        }

        public String language() {
            return language;
        }

        public String headMarkup() {
            return headMarkup;
        }

        public String bodyMarkup() {
            return bodyMarkup;
        }
    }

    private static final class InMemoryHttpExchange extends HttpExchange {
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        private final URI requestUri;
        private final String method;
        private final InputStream requestBody;
        private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
        private final Map<String, Object> attributes = new LinkedHashMap<>();
        private int statusCode = 200;

        private InMemoryHttpExchange(String method, URI requestUri, byte[] requestBody) {
            this.method = method == null || method.isBlank() ? "GET" : method;
            this.requestUri = requestUri;
            this.requestBody = new ByteArrayInputStream(requestBody == null ? new byte[0] : requestBody);
        }

        private AdminHttpResponse toResponse() {
            Map<String, List<String>> copiedHeaders = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> entry : responseHeaders.entrySet()) {
                copiedHeaders.put(entry.getKey(), List.copyOf(entry.getValue()));
            }
            return new AdminHttpResponse(
                    statusCode,
                    Collections.unmodifiableMap(copiedHeaders),
                    responseBody.toByteArray()
            );
        }

        @Override
        public Headers getRequestHeaders() {
            return requestHeaders;
        }

        @Override
        public Headers getResponseHeaders() {
            return responseHeaders;
        }

        @Override
        public URI getRequestURI() {
            return requestUri;
        }

        @Override
        public String getRequestMethod() {
            return method;
        }

        @Override
        public HttpContext getHttpContext() {
            return null;
        }

        @Override
        public void close() {
        }

        @Override
        public InputStream getRequestBody() {
            return requestBody;
        }

        @Override
        public OutputStream getResponseBody() {
            return responseBody;
        }

        @Override
        public void sendResponseHeaders(int rCode, long responseLength) {
            this.statusCode = rCode;
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            return new InetSocketAddress("127.0.0.1", 0);
        }

        @Override
        public int getResponseCode() {
            return statusCode;
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            return new InetSocketAddress("127.0.0.1", 0);
        }

        @Override
        public String getProtocol() {
            return "HTTP/1.1";
        }

        @Override
        public Object getAttribute(String name) {
            return attributes.get(name);
        }

        @Override
        public void setAttribute(String name, Object value) {
            attributes.put(name, value);
        }

        @Override
        public void setStreams(InputStream i, OutputStream o) {
            throw new UnsupportedOperationException("In-memory exchange does not support replacing streams");
        }

        @Override
        public HttpPrincipal getPrincipal() {
            return null;
        }
    }
}
