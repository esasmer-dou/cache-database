package com.reactor.cachedb.starter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

final class MigrationScaffoldGenerator {

    private static final String DEFAULT_BASE_PACKAGE = "com.example.cachedb.migration";
    private static final Set<String> TECHNICAL_COLUMNS = Set.of(
            "entity_version",
            "deleted_flag",
            "created_at",
            "updated_at"
    );

    private final MigrationSchemaDiscovery discovery;
    private final MigrationPlanner planner = new MigrationPlanner();

    MigrationScaffoldGenerator(MigrationSchemaDiscovery discovery) {
        this.discovery = Objects.requireNonNull(discovery, "discovery");
    }

    Result generate(Request request) {
        Request normalized = Objects.requireNonNull(request, "request").normalize();
        MigrationPlanner.Result plan = planner.plan(normalized.plannerRequest());
        MigrationSchemaDiscovery.Result discoveryResult = discovery.discover();

        MigrationSchemaDiscovery.TableInfo rootTable = resolveTable(discoveryResult.tables(), plan.request().rootTableOrEntity())
                .orElseThrow(() -> new IllegalArgumentException("Could not find root table/entity in discovered schema: " + plan.request().rootTableOrEntity()));
        MigrationSchemaDiscovery.TableInfo childTable = resolveTable(discoveryResult.tables(), plan.request().childTableOrEntity())
                .orElseThrow(() -> new IllegalArgumentException("Could not find child table/entity in discovered schema: " + plan.request().childTableOrEntity()));

        Naming naming = buildNaming(normalized, rootTable, childTable);

        ArrayList<GeneratedFile> files = new ArrayList<>();
        files.add(renderRootEntityFile(naming, rootTable, plan));
        files.add(renderChildEntityFile(naming, childTable, plan));
        if (normalized.includeRelationLoader()) {
            files.add(renderRelationLoaderFile(naming, plan));
        }
        if (normalized.includeProjectionSkeleton()) {
            files.add(renderProjectionSkeletonFile(naming, childTable, plan));
        }
        files.add(renderUsageSnippetFile(naming, plan));

        ArrayList<String> notes = new ArrayList<>();
        notes.add("The generated entity classes are derived from the discovered PostgreSQL schema, not from existing ORM source code.");
        notes.add("Generated binding classes are produced by the annotation processor after these entity skeletons are added to the project.");
        if (plan.projectionRequired()) {
            notes.add("The child route includes a projection skeleton because this screen should move to summary-first reads before cutover.");
        }

        ArrayList<String> warnings = new ArrayList<>(discoveryResult.warnings());
        if (plan.rankedProjectionRequired()) {
            warnings.add("Ranked/global-sorted screens still need a deliberate business sort contract. Review the generated projection skeleton before production cutover.");
        }
        if (rootTable.registeredEntityName() != null && !rootTable.registeredEntityName().isBlank()) {
            warnings.add("The discovered root table already matches a registered CacheDB entity: " + rootTable.registeredEntityName());
        }
        if (childTable.registeredEntityName() != null && !childTable.registeredEntityName().isBlank()) {
            warnings.add("The discovered child table already matches a registered CacheDB entity: " + childTable.registeredEntityName());
        }

        return new Result(
                normalized,
                plan,
                discoveryResult,
                naming.basePackage(),
                List.copyOf(files),
                List.copyOf(notes),
                List.copyOf(warnings)
        );
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

    private Naming buildNaming(
            Request request,
            MigrationSchemaDiscovery.TableInfo rootTable,
            MigrationSchemaDiscovery.TableInfo childTable
    ) {
        String basePackage = request.basePackage() == null || request.basePackage().isBlank()
                ? DEFAULT_BASE_PACKAGE
                : request.basePackage().trim();
        String rootClassName = firstNonBlank(request.rootClassName(), deriveEntityClassName(rootTable));
        String childClassName = firstNonBlank(request.childClassName(), deriveEntityClassName(childTable));
        String relationFieldName = pluralize(camelCase(stripEntitySuffix(childClassName)));
        String relationLoaderClassName = firstNonBlank(
                request.relationLoaderClassName(),
                stripEntitySuffix(rootClassName) + stripEntitySuffix(childClassName) + "RelationBatchLoader"
        );
        String projectionSupportClassName = firstNonBlank(
                request.projectionSupportClassName(),
                stripEntitySuffix(rootClassName) + stripEntitySuffix(childClassName) + "ReadModels"
        );
        return new Naming(
                basePackage,
                rootClassName,
                childClassName,
                relationFieldName,
                relationLoaderClassName,
                projectionSupportClassName
        );
    }

    private String deriveEntityClassName(MigrationSchemaDiscovery.TableInfo table) {
        if (table.registeredEntityName() != null && !table.registeredEntityName().isBlank()) {
            return table.registeredEntityName();
        }
        return pascalCase(table.tableName()) + "Entity";
    }

    private GeneratedFile renderRootEntityFile(
            Naming naming,
            MigrationSchemaDiscovery.TableInfo rootTable,
            MigrationPlanner.Result plan
    ) {
        ArrayList<String> imports = new ArrayList<>();
        imports.add("com.reactor.cachedb.annotations.CacheColumn");
        imports.add("com.reactor.cachedb.annotations.CacheEntity");
        imports.add("com.reactor.cachedb.annotations.CacheId");
        imports.add("com.reactor.cachedb.annotations.CacheRelation");
        imports.add("java.util.List");
        if (plan.summaryFirstRequired()) {
            imports.add(naming.basePackage() + "." + naming.relationLoaderClassName());
        }

        StringBuilder body = new StringBuilder();
        body.append("package ").append(naming.basePackage()).append(";\n\n");
        body.append(renderImports(imports));
        body.append("@CacheEntity(\n")
                .append("        table = \"").append(rootTable.qualifiedTableName()).append("\",\n")
                .append("        redisNamespace = \"").append(redisNamespace(rootTable.tableName())).append("\"");
        if (plan.summaryFirstRequired()) {
            body.append(",\n        relationLoader = ").append(naming.relationLoaderClassName()).append(".class");
        }
        body.append("\n)\n");
        body.append("public class ").append(naming.rootClassName()).append(" {\n\n");
        for (MigrationSchemaDiscovery.ColumnInfo column : rootTable.columns()) {
            body.append(renderEntityField(column, column.name().equalsIgnoreCase(rootTable.primaryKeyColumn())));
        }
        body.append("    @CacheRelation(\n")
                .append("            targetEntity = \"").append(naming.childClassName()).append("\",\n")
                .append("            mappedBy = \"").append(plan.request().relationColumn()).append("\",\n")
                .append("            kind = CacheRelation.RelationKind.ONE_TO_MANY,\n")
                .append("            batchLoadOnly = true\n")
                .append("    )\n")
                .append("    public List<").append(naming.childClassName()).append("> ").append(naming.relationFieldName()).append(";\n\n")
                .append("    public ").append(naming.rootClassName()).append("() {\n")
                .append("    }\n")
                .append("}\n");
        return new GeneratedFile(
                naming.rootClassName() + ".java",
                javaPath(naming.basePackage(), naming.rootClassName() + ".java"),
                "Root entity skeleton",
                "java",
                body.toString()
        );
    }

    private GeneratedFile renderChildEntityFile(
            Naming naming,
            MigrationSchemaDiscovery.TableInfo childTable,
            MigrationPlanner.Result plan
    ) {
        ArrayList<String> imports = new ArrayList<>();
        imports.add("com.reactor.cachedb.annotations.CacheColumn");
        imports.add("com.reactor.cachedb.annotations.CacheEntity");
        imports.add("com.reactor.cachedb.annotations.CacheId");
        imports.add("com.reactor.cachedb.annotations.CacheNamedQuery");
        imports.add("com.reactor.cachedb.core.query.QueryFilter");
        imports.add("com.reactor.cachedb.core.query.QuerySort");
        imports.add("com.reactor.cachedb.core.query.QuerySpec");

        String relationValueType = javaTypeName(columnByName(childTable, plan.request().relationColumn()).orElse(null), plan.request().relationColumn());

        StringBuilder body = new StringBuilder();
        body.append("package ").append(naming.basePackage()).append(";\n\n");
        body.append(renderImports(imports));
        body.append("@CacheEntity(\n")
                .append("        table = \"").append(childTable.qualifiedTableName()).append("\",\n")
                .append("        redisNamespace = \"").append(redisNamespace(childTable.tableName())).append("\"\n")
                .append(")\n");
        body.append("public class ").append(naming.childClassName()).append(" {\n\n");
        for (MigrationSchemaDiscovery.ColumnInfo column : childTable.columns()) {
            body.append(renderEntityField(column, column.name().equalsIgnoreCase(childTable.primaryKeyColumn())));
        }
        body.append("    public ").append(naming.childClassName()).append("() {\n")
                .append("    }\n\n")
                .append("    @CacheNamedQuery(\"listBy").append(stripEntitySuffix(naming.rootClassName())).append("\")\n")
                .append("    public static QuerySpec listBy").append(stripEntitySuffix(naming.rootClassName())).append("Query(")
                .append(relationValueType).append(' ').append(fieldName(plan.request().relationColumn())).append(", int limit) {\n")
                .append("        return QuerySpec.where(QueryFilter.eq(\"").append(plan.request().relationColumn()).append("\", ")
                .append(fieldName(plan.request().relationColumn())).append("))\n")
                .append("                .orderBy(").append(renderSortExpression(plan.request().sortColumn(), plan.request().sortDirection())).append(", QuerySort.desc(\"")
                .append(plan.request().childPrimaryKeyColumn()).append("\"))\n")
                .append("                .limitTo(limit);\n")
                .append("    }\n")
                .append("}\n");
        return new GeneratedFile(
                naming.childClassName() + ".java",
                javaPath(naming.basePackage(), naming.childClassName() + ".java"),
                "Child entity skeleton with a hot-list named query",
                "java",
                body.toString()
        );
    }

    private GeneratedFile renderRelationLoaderFile(Naming naming, MigrationPlanner.Result plan) {
        ArrayList<String> imports = new ArrayList<>();
        imports.add("com.reactor.cachedb.core.api.EntityRepository");
        imports.add("com.reactor.cachedb.core.query.QueryFilter");
        imports.add("com.reactor.cachedb.core.query.QuerySort");
        imports.add("com.reactor.cachedb.core.query.QuerySpec");
        imports.add("com.reactor.cachedb.core.relation.RelationBatchContext");
        imports.add("com.reactor.cachedb.core.relation.RelationBatchLoader");
        imports.add("java.util.List");
        imports.add("java.util.Objects");

        String childIdType = javaTypeName(null, plan.request().childPrimaryKeyColumn());

        StringBuilder body = new StringBuilder();
        body.append("package ").append(naming.basePackage()).append(";\n\n");
        body.append(renderImports(imports));
        body.append("public final class ").append(naming.relationLoaderClassName())
                .append(" implements RelationBatchLoader<").append(naming.rootClassName()).append("> {\n\n")
                .append("    private static final String RELATION_NAME = \"").append(naming.relationFieldName()).append("\";\n")
                .append("    private static final int DEFAULT_PREVIEW_LIMIT = ").append(Math.max(1, plan.request().firstPageSize())).append(";\n\n")
                .append("    private final EntityRepository<").append(naming.childClassName()).append(", ").append(childIdType).append("> childRepository;\n\n")
                .append("    public ").append(naming.relationLoaderClassName()).append("(EntityRepository<")
                .append(naming.childClassName()).append(", ").append(childIdType).append("> childRepository) {\n")
                .append("        this.childRepository = Objects.requireNonNull(childRepository, \"childRepository\");\n")
                .append("    }\n\n")
                .append("    @Override\n")
                .append("    public void preload(List<").append(naming.rootClassName()).append("> entities, RelationBatchContext context) {\n")
                .append("        if (entities.isEmpty() || !context.fetchPlan().includes(RELATION_NAME)) {\n")
                .append("            return;\n")
                .append("        }\n")
                .append("        int requestedLimit = context.relationLimit(RELATION_NAME);\n")
                .append("        int relationLimit = requestedLimit == Integer.MAX_VALUE ? DEFAULT_PREVIEW_LIMIT : Math.max(1, requestedLimit);\n")
                .append("        for (").append(naming.rootClassName()).append(" entity : entities) {\n")
                .append("            if (entity == null || entity.").append(fieldName(plan.request().rootPrimaryKeyColumn())).append(" == null) {\n")
                .append("                continue;\n")
                .append("            }\n")
                .append("            entity.").append(naming.relationFieldName()).append(" = childRepository.query(\n")
                .append("                    QuerySpec.where(QueryFilter.eq(\"").append(plan.request().relationColumn()).append("\", entity.")
                .append(fieldName(plan.request().rootPrimaryKeyColumn())).append("))\n")
                .append("                            .orderBy(").append(renderSortExpression(plan.request().sortColumn(), plan.request().sortDirection())).append(", QuerySort.desc(\"")
                .append(plan.request().childPrimaryKeyColumn()).append("\"))\n")
                .append("                            .limitTo(relationLimit)\n")
                .append("            );\n")
                .append("        }\n")
                .append("    }\n")
                .append("}\n");
        return new GeneratedFile(
                naming.relationLoaderClassName() + ".java",
                javaPath(naming.basePackage(), naming.relationLoaderClassName() + ".java"),
                "Relation batch loader skeleton for bounded first-paint previews",
                "java",
                body.toString()
        );
    }

    private GeneratedFile renderProjectionSkeletonFile(
            Naming naming,
            MigrationSchemaDiscovery.TableInfo childTable,
            MigrationPlanner.Result plan
    ) {
        ArrayList<String> summaryColumns = new ArrayList<>();
        summaryColumns.add(plan.request().childPrimaryKeyColumn());
        if (!plan.request().relationColumn().equalsIgnoreCase(plan.request().childPrimaryKeyColumn())) {
            summaryColumns.add(plan.request().relationColumn());
        }
        if (!plan.request().sortColumn().equalsIgnoreCase(plan.request().childPrimaryKeyColumn())
                && !plan.request().sortColumn().equalsIgnoreCase(plan.request().relationColumn())) {
            summaryColumns.add(plan.request().sortColumn());
        }
        childTable.columns().stream()
                .filter(column -> !summaryColumns.stream().anyMatch(name -> name.equalsIgnoreCase(column.name())))
                .filter(column -> !TECHNICAL_COLUMNS.contains(normalize(column.name())))
                .limit(3)
                .forEach(column -> summaryColumns.add(column.name()));

        StringBuilder body = new StringBuilder();
        body.append("package ").append(naming.basePackage()).append(";\n\n")
                .append("// Skeleton only: wire this class into ").append(naming.childClassName()).append(" with @CacheProjectionDefinition.\n")
                .append("// Suggested projection name: ").append(plan.summaryProjectionName()).append("\n");
        if (plan.rankedProjectionRequired()) {
            body.append("// Suggested ranked column: ").append(plan.request().sortColumn()).append("\n");
        }
        body.append("\n")
                .append("/*\n")
                .append("Suggested summary projection columns:\n");
        for (String column : summaryColumns) {
            body.append(" - ").append(column).append('\n');
        }
        body.append("\n")
                .append("Suggested child entity hook:\n")
                .append("@CacheProjectionDefinition(\"").append(plan.summaryProjectionName()).append("\")\n")
                .append("public static EntityProjection<").append(naming.childClassName()).append(", ")
                .append(stripEntitySuffix(naming.childClassName())).append("SummaryView, ")
                .append(javaTypeName(columnByName(childTable, plan.request().childPrimaryKeyColumn()).orElse(null), plan.request().childPrimaryKeyColumn())).append("> ")
                .append(camelCase(plan.summaryProjectionName())).append("Projection() {\n")
                .append("    // TODO: build EntityProjection.of(...).asyncRefresh()")
                .append(plan.rankedProjectionRequired() ? ".rankedBy(\"" + plan.request().sortColumn() + "\")" : "")
                .append(";\n")
                .append("}\n")
                .append("*/\n");
        return new GeneratedFile(
                naming.projectionSupportClassName() + ".java",
                javaPath(naming.basePackage(), naming.projectionSupportClassName() + ".java"),
                "Projection support skeleton for the hot list route",
                "java",
                body.toString()
        );
    }

    private GeneratedFile renderUsageSnippetFile(Naming naming, MigrationPlanner.Result plan) {
        StringBuilder body = new StringBuilder();
        body.append("package ").append(naming.basePackage()).append(";\n\n")
                .append("// Generated bindings appear after compilation. This is the intended usage shape.\n\n")
                .append("/*\n")
                .append("Projection list route:\n")
                .append("ProjectionRepository<?, ?> projectionRepository = ").append(naming.childClassName()).append("CacheBinding.")
                .append(camelCase(plan.summaryProjectionName())).append("(session);\n")
                .append("List<?> firstPage = ").append(naming.childClassName()).append("CacheBinding.listBy")
                .append(stripEntitySuffix(naming.rootClassName())).append("(projectionRepository, sampleRootId, ")
                .append(plan.request().firstPageSize()).append(");\n\n")
                .append("Detail route:\n")
                .append("Optional<?> detail = ").append(naming.childClassName()).append("CacheBinding.findById(session, sampleChildId);\n")
                .append("*/\n");
        return new GeneratedFile(
                "MigrationUsageExample.java",
                javaPath(naming.basePackage(), "MigrationUsageExample.java"),
                "Copy/paste usage snippet for the generated binding surface",
                "java",
                body.toString()
        );
    }

    private String renderEntityField(MigrationSchemaDiscovery.ColumnInfo column, boolean primaryKey) {
        StringBuilder builder = new StringBuilder();
        if (primaryKey) {
            builder.append("    @CacheId(column = \"").append(column.name()).append("\")\n");
        } else {
            builder.append("    @CacheColumn(\"").append(column.name()).append("\")\n");
        }
        builder.append("    public ").append(javaTypeName(column, column.name())).append(' ').append(fieldName(column.name())).append(";\n\n");
        return builder.toString();
    }

    private String javaTypeName(MigrationSchemaDiscovery.ColumnInfo column, String fallbackColumnName) {
        if (column != null) {
            String type = normalize(column.jdbcTypeName());
            if (type.contains("bigint") || type.contains("bigserial")) {
                return "Long";
            }
            if (type.contains("int") || type.contains("serial")) {
                return "Integer";
            }
            if (type.contains("smallint")) {
                return "Short";
            }
            if (type.contains("decimal") || type.contains("numeric")) {
                return "java.math.BigDecimal";
            }
            if (type.contains("double")) {
                return "Double";
            }
            if (type.contains("float") || type.contains("real")) {
                return "Float";
            }
            if (type.contains("bool")) {
                return "Boolean";
            }
            if (type.contains("timestamp") || type.contains("timestamptz")) {
                return "java.time.Instant";
            }
            if (type.equals("date")) {
                return "java.time.LocalDate";
            }
            if (type.startsWith("time")) {
                return "java.time.LocalTime";
            }
            if (type.contains("uuid")) {
                return "java.util.UUID";
            }
        }
        String normalizedName = normalize(fallbackColumnName);
        if (normalizedName.endsWith("_id") || "id".equals(normalizedName)) {
            return "Long";
        }
        return "String";
    }

    private Optional<MigrationSchemaDiscovery.ColumnInfo> columnByName(MigrationSchemaDiscovery.TableInfo table, String name) {
        return table.columns().stream()
                .filter(column -> normalize(column.name()).equals(normalize(name)))
                .findFirst();
    }

    private String renderImports(List<String> imports) {
        LinkedHashSet<String> ordered = new LinkedHashSet<>(imports);
        StringBuilder builder = new StringBuilder();
        ordered.stream()
                .filter(value -> value != null && !value.isBlank())
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .forEach(value -> builder.append("import ").append(value).append(";\n"));
        builder.append('\n');
        return builder.toString();
    }

    private String renderSortExpression(String sortColumn, String sortDirection) {
        return "ASC".equalsIgnoreCase(sortDirection)
                ? "QuerySort.asc(\"" + sortColumn + "\")"
                : "QuerySort.desc(\"" + sortColumn + "\")";
    }

    private String javaPath(String basePackage, String fileName) {
        return "src/main/java/" + basePackage.replace('.', '/') + "/" + fileName;
    }

    private String redisNamespace(String tableName) {
        return tableName == null ? "" : tableName.trim().replace('_', '-').toLowerCase(Locale.ROOT);
    }

    private String fieldName(String raw) {
        return camelCase(raw);
    }

    private String camelCase(String raw) {
        String pascal = pascalCase(raw);
        if (pascal.isBlank()) {
            return "value";
        }
        return Character.toLowerCase(pascal.charAt(0)) + pascal.substring(1);
    }

    private String pascalCase(String raw) {
        String normalized = raw == null ? "" : raw.trim();
        if (normalized.isBlank()) {
            return "Entity";
        }
        String separated = normalized
                .replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2");
        String[] tokens = separated.split("[^A-Za-z0-9]+");
        StringBuilder builder = new StringBuilder();
        for (String token : tokens) {
            if (token == null || token.isBlank()) {
                continue;
            }
            String lower = token.toLowerCase(Locale.ROOT);
            builder.append(Character.toUpperCase(lower.charAt(0)));
            if (lower.length() > 1) {
                builder.append(lower.substring(1));
            }
        }
        return builder.isEmpty() ? "Entity" : builder.toString();
    }

    private String stripEntitySuffix(String value) {
        if (value != null && value.endsWith("Entity") && value.length() > "Entity".length()) {
            return value.substring(0, value.length() - "Entity".length());
        }
        return value == null || value.isBlank() ? "Entity" : value;
    }

    private String pluralize(String singular) {
        if (singular == null || singular.isBlank()) {
            return "items";
        }
        if (singular.endsWith("s")) {
            return singular + "List";
        }
        if (singular.endsWith("y") && singular.length() > 1) {
            return singular.substring(0, singular.length() - 1) + "ies";
        }
        return singular + "s";
    }

    private String firstNonBlank(String primary, String fallback) {
        return primary != null && !primary.isBlank() ? primary.trim() : fallback;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    record Request(
            MigrationPlanner.Request plannerRequest,
            String basePackage,
            String rootClassName,
            String childClassName,
            String relationLoaderClassName,
            String projectionSupportClassName,
            boolean includeRelationLoader,
            boolean includeProjectionSkeleton
    ) {
        Request normalize() {
            return new Request(
                    plannerRequest == null ? MigrationPlanner.Request.defaults() : plannerRequest.normalize(),
                    basePackage == null || basePackage.isBlank() ? DEFAULT_BASE_PACKAGE : basePackage.trim(),
                    rootClassName == null ? "" : rootClassName.trim(),
                    childClassName == null ? "" : childClassName.trim(),
                    relationLoaderClassName == null ? "" : relationLoaderClassName.trim(),
                    projectionSupportClassName == null ? "" : projectionSupportClassName.trim(),
                    includeRelationLoader,
                    includeProjectionSkeleton
            );
        }
    }

    record Result(
            Request request,
            MigrationPlanner.Result plan,
            MigrationSchemaDiscovery.Result discovery,
            String basePackage,
            List<GeneratedFile> files,
            List<String> notes,
            List<String> warnings
    ) {
    }

    record GeneratedFile(
            String fileName,
            String relativePath,
            String description,
            String language,
            String content
    ) {
    }

    private record Naming(
            String basePackage,
            String rootClassName,
            String childClassName,
            String relationFieldName,
            String relationLoaderClassName,
            String projectionSupportClassName
    ) {
    }
}
