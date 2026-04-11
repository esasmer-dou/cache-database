package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.api.EntityRepository;
import com.reactor.cachedb.core.queue.AdminExportFormat;
import com.reactor.cachedb.core.queue.AdminExportResult;
import com.reactor.cachedb.core.query.QueryExplainPlan;
import com.reactor.cachedb.core.query.QueryExplainRelationState;
import com.reactor.cachedb.core.query.QueryExplainStep;
import com.reactor.cachedb.core.query.QuerySpec;
import com.reactor.cachedb.core.registry.EntityBinding;
import com.reactor.cachedb.core.registry.EntityRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class CacheDatabaseDebug {

    private final EntityRegistry entityRegistry;
    private final DefaultCacheSession session;

    public CacheDatabaseDebug(EntityRegistry entityRegistry, DefaultCacheSession session) {
        this.entityRegistry = entityRegistry;
        this.session = session;
    }

    public QueryExplainPlan explain(String entityName, QuerySpec querySpec) {
        EntityBinding<?, ?> binding = entityRegistry.find(entityName)
                .orElseThrow(() -> new IllegalArgumentException("Entity binding not found: " + entityName));
        return explain(binding, querySpec);
    }

    public AdminExportResult exportExplain(String entityName, QuerySpec querySpec, AdminExportFormat format) {
        QueryExplainPlan plan = explain(entityName, querySpec);
        return new AdminExportResult(
                entityName + "-explain." + format.fileExtension(),
                format,
                format.contentType(),
                renderExplain(plan, format)
        );
    }

    public Path writeExplain(Path outputPath, String entityName, QuerySpec querySpec, AdminExportFormat format) throws IOException {
        Path normalized = outputPath.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(normalized, exportExplain(entityName, querySpec, format).content());
        return normalized;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private QueryExplainPlan explain(EntityBinding<?, ?> binding, QuerySpec querySpec) {
        return explainTyped((EntityBinding) binding, querySpec);
    }

    private <T, ID> QueryExplainPlan explainTyped(EntityBinding<T, ID> binding, QuerySpec querySpec) {
        EntityRepository<T, ID> repository = session.repository(binding.metadata(), binding.codec(), binding.cachePolicy());
        return repository.explain(querySpec);
    }

    private String renderExplain(QueryExplainPlan plan, AdminExportFormat format) {
        return switch (format) {
            case JSON -> renderJson(plan);
            case CSV -> renderCsv(plan);
            case MARKDOWN -> renderMarkdown(plan);
        };
    }

    private String renderJson(QueryExplainPlan plan) {
        StringBuilder builder = new StringBuilder();
        builder.append("{\"entity\":\"").append(escapeJson(plan.entityName())).append("\",");
        builder.append("\"plannerStrategy\":\"").append(escapeJson(plan.plannerStrategy())).append("\",");
        builder.append("\"sortStrategy\":\"").append(escapeJson(plan.sortStrategy())).append("\",");
        builder.append("\"estimatedCost\":").append(plan.estimatedCost()).append(",");
        builder.append("\"candidateCount\":").append(plan.candidateCount()).append(",");
        builder.append("\"warnings\":[");
        for (int index = 0; index < plan.warnings().size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            builder.append('"').append(escapeJson(plan.warnings().get(index))).append('"');
        }
        builder.append("],");
        builder.append("\"relationStates\":[");
        for (int index = 0; index < plan.relationStates().size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            QueryExplainRelationState state = plan.relationStates().get(index);
            builder.append("{\"relationName\":\"").append(escapeJson(state.relationName())).append("\",");
            builder.append("\"usage\":\"").append(escapeJson(state.usage())).append("\",");
            builder.append("\"status\":\"").append(escapeJson(state.status())).append("\",");
            builder.append("\"kind\":\"").append(escapeJson(state.kind())).append("\",");
            builder.append("\"targetEntity\":\"").append(escapeJson(state.targetEntity())).append("\",");
            builder.append("\"mappedBy\":\"").append(escapeJson(state.mappedBy())).append("\",");
            builder.append("\"batchLoadOnly\":").append(state.batchLoadOnly()).append(",");
            builder.append("\"indexed\":").append(state.indexed()).append(",");
            builder.append("\"candidateCount\":").append(state.candidateCount()).append(",");
            builder.append("\"detail\":\"").append(escapeJson(state.detail())).append("\"}");
        }
        builder.append("],");
        builder.append("\"steps\":[");
        for (int index = 0; index < plan.steps().size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            QueryExplainStep step = plan.steps().get(index);
            builder.append("{\"stage\":\"").append(escapeJson(step.stage())).append("\",");
            builder.append("\"expression\":\"").append(escapeJson(step.expression())).append("\",");
            builder.append("\"strategy\":\"").append(escapeJson(step.strategy())).append("\",");
            builder.append("\"indexed\":").append(step.indexed()).append(",");
            builder.append("\"candidateCount\":").append(step.candidateCount()).append(",");
            builder.append("\"estimatedCost\":").append(step.estimatedCost()).append(",");
            builder.append("\"detail\":\"").append(escapeJson(step.detail())).append("\"}");
        }
        builder.append("]}");
        return builder.toString();
    }

    private String renderCsv(QueryExplainPlan plan) {
        StringBuilder builder = new StringBuilder();
        builder.append("stage,expression,strategy,indexed,candidateCount,estimatedCost,detail\n");
        for (QueryExplainStep step : plan.steps()) {
            builder.append(csv(step.stage())).append(',')
                    .append(csv(step.expression())).append(',')
                    .append(csv(step.strategy())).append(',')
                    .append(csv(String.valueOf(step.indexed()))).append(',')
                    .append(csv(String.valueOf(step.candidateCount()))).append(',')
                    .append(csv(String.valueOf(step.estimatedCost()))).append(',')
                    .append(csv(step.detail())).append('\n');
        }
        if (!plan.relationStates().isEmpty()) {
            builder.append('\n');
            builder.append("relationName,usage,status,kind,targetEntity,mappedBy,batchLoadOnly,indexed,candidateCount,detail\n");
            for (QueryExplainRelationState state : plan.relationStates()) {
                builder.append(csv(state.relationName())).append(',')
                        .append(csv(state.usage())).append(',')
                        .append(csv(state.status())).append(',')
                        .append(csv(state.kind())).append(',')
                        .append(csv(state.targetEntity())).append(',')
                        .append(csv(state.mappedBy())).append(',')
                        .append(csv(String.valueOf(state.batchLoadOnly()))).append(',')
                        .append(csv(String.valueOf(state.indexed()))).append(',')
                        .append(csv(String.valueOf(state.candidateCount()))).append(',')
                        .append(csv(state.detail())).append('\n');
            }
        }
        return builder.toString();
    }

    private String renderMarkdown(QueryExplainPlan plan) {
        StringBuilder builder = new StringBuilder();
        builder.append("# Explain ").append(plan.entityName()).append('\n').append('\n');
        builder.append("Planner: ").append(plan.plannerStrategy()).append('\n');
        builder.append("Sort: ").append(plan.sortStrategy()).append('\n');
        builder.append("Estimated cost: ").append(plan.estimatedCost()).append('\n');
        builder.append("Candidates: ").append(plan.candidateCount()).append('\n').append('\n');
        if (!plan.warnings().isEmpty()) {
            builder.append("Warnings:\n");
            for (String warning : plan.warnings()) {
                builder.append("- ").append(markdown(warning)).append('\n');
            }
            builder.append('\n');
        }
        if (!plan.relationStates().isEmpty()) {
            builder.append("## Relation States\n\n");
            builder.append("| relation | usage | status | kind | target | mappedBy | batchOnly | indexed | candidates | detail |\n");
            builder.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
            for (QueryExplainRelationState state : plan.relationStates()) {
                builder.append("| ").append(markdown(state.relationName()))
                        .append(" | ").append(markdown(state.usage()))
                        .append(" | ").append(markdown(state.status()))
                        .append(" | ").append(markdown(state.kind()))
                        .append(" | ").append(markdown(state.targetEntity()))
                        .append(" | ").append(markdown(state.mappedBy()))
                        .append(" | ").append(state.batchLoadOnly())
                        .append(" | ").append(state.indexed())
                        .append(" | ").append(state.candidateCount())
                        .append(" | ").append(markdown(state.detail()))
                        .append(" |\n");
            }
            builder.append('\n');
        }
        builder.append("| stage | expression | strategy | indexed | candidates | estimatedCost | detail |\n");
        builder.append("| --- | --- | --- | --- | --- | --- | --- |\n");
        for (QueryExplainStep step : plan.steps()) {
            builder.append("| ").append(markdown(step.stage()))
                    .append(" | ").append(markdown(step.expression()))
                    .append(" | ").append(markdown(step.strategy()))
                    .append(" | ").append(step.indexed())
                    .append(" | ").append(step.candidateCount())
                    .append(" | ").append(step.estimatedCost())
                    .append(" | ").append(markdown(step.detail()))
                    .append(" |\n");
        }
        return builder.toString();
    }

    private String csv(String value) {
        String safe = value == null ? "" : value;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    private String markdown(String value) {
        String safe = value == null ? "" : value;
        return safe.replace("|", "\\|").replace("\n", " ");
    }

    private String escapeJson(String value) {
        String safe = value == null ? "" : value;
        return safe.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r").replace("\n", "\\n");
    }
}
