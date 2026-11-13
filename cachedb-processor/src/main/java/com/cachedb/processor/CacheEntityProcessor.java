package com.reactor.cachedb.processor;

import com.reactor.cachedb.annotations.CacheCodec;
import com.reactor.cachedb.annotations.CacheColumn;
import com.reactor.cachedb.annotations.CacheDeleteCommand;
import com.reactor.cachedb.annotations.CacheDomain;
import com.reactor.cachedb.annotations.CacheEntity;
import com.reactor.cachedb.annotations.CacheEncrypted;
import com.reactor.cachedb.annotations.CacheFetchPreset;
import com.reactor.cachedb.annotations.CacheGeneratedId;
import com.reactor.cachedb.annotations.CacheId;
import com.reactor.cachedb.annotations.CacheNamedQuery;
import com.reactor.cachedb.annotations.CachePagePreset;
import com.reactor.cachedb.annotations.CachePartitionedIndex;
import com.reactor.cachedb.annotations.CacheProjectionDefinition;
import com.reactor.cachedb.annotations.CacheRelation;
import com.reactor.cachedb.annotations.CacheRoute;
import com.reactor.cachedb.annotations.CacheSaveCommand;
import com.reactor.cachedb.annotations.CacheSoftDelete;
import com.reactor.cachedb.annotations.CacheVersion;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;

@SupportedAnnotationTypes({"com.reactor.cachedb.annotations.CacheEntity",
        "com.reactor.cachedb.annotations.CacheEncrypted", "com.reactor.cachedb.annotations.CacheProjectionRecord",
        "com.reactor.cachedb.annotations.CacheRetention"})
public final class CacheEntityProcessor extends AbstractProcessor {

    private static final String CACHE_DATABASE_TYPE = "com.reactor.cachedb.starter.CacheDatabase";
    private static final String CACHE_SESSION_TYPE = "com.reactor.cachedb.core.api.CacheSession";
    private static final String CACHE_POLICY_TYPE = "com.reactor.cachedb.core.cache.CachePolicy";
    private static final String ENTITY_REPOSITORY_TYPE = "com.reactor.cachedb.core.api.EntityRepository";
    private static final String PROJECTION_REPOSITORY_TYPE = "com.reactor.cachedb.core.api.ProjectionRepository";
    private static final String ENTITY_PROJECTION_TYPE = "com.reactor.cachedb.core.projection.EntityProjection";
    private static final String QUERY_SPEC_TYPE = "com.reactor.cachedb.core.query.QuerySpec";
    private static final String FETCH_PLAN_TYPE = "com.reactor.cachedb.core.plan.FetchPlan";
    private static final String RELATION_BATCH_LOADER_TYPE = "com.reactor.cachedb.core.relation.RelationBatchLoader";
    private static final String ENTITY_PAGE_LOADER_TYPE = "com.reactor.cachedb.core.page.EntityPageLoader";
    private static final String GENERATED_BINDINGS_REGISTRAR_TYPE = "com.reactor.cachedb.starter.GeneratedCacheBindingsRegistrar";
    private static final String SQL_IDENTIFIER_SEGMENT = "[A-Za-z_][A-Za-z0-9_]*";
    private static final String SQL_IDENTIFIER_PATTERN = SQL_IDENTIFIER_SEGMENT;
    private static final String QUALIFIED_SQL_IDENTIFIER_PATTERN = SQL_IDENTIFIER_SEGMENT + "(\\." + SQL_IDENTIFIER_SEGMENT + ")*";
    private static final String REDIS_NAMESPACE_PATTERN = "[A-Za-z0-9_.:-]+";
    private static final ProjectionModel INVALID_PROJECTION_MODEL =
            new ProjectionModel("__invalid__", "__invalid__", "__invalid__", "__invalid__");
    private static final NamedQueryModel INVALID_NAMED_QUERY_MODEL =
            new NamedQueryModel("__invalid__", "__invalid__", List.of(), null);
    private static final FetchPresetModel INVALID_FETCH_PRESET_MODEL =
            new FetchPresetModel("__invalid__", "__invalid__", List.of());
    private static final PagePresetModel INVALID_PAGE_PRESET_MODEL =
            new PagePresetModel("__invalid__", "__invalid__", List.of());
    private static final SaveCommandModel INVALID_SAVE_COMMAND_MODEL =
            new SaveCommandModel("__invalid__", "__invalid__", List.of());
    private static final DeleteCommandModel INVALID_DELETE_COMMAND_MODEL =
            new DeleteCommandModel("__invalid__", "__invalid__", "__invalid__", List.of());

    private Filer filer;
    private Messager messager;
    private final LinkedHashSet<String> generatedRegistrarClassNames = new LinkedHashSet<>();

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        this.filer = processingEnv.getFiler();
        this.messager = processingEnv.getMessager();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver()) {
            writeGeneratedRegistrarServiceFile();
            return true;
        }
        Map<String, List<EntityModel>> roundPackageEntityModels = new LinkedHashMap<>();
        if (!validateEncryptedTargets(roundEnv)) return false;
        for (Element element : roundEnv.getElementsAnnotatedWith(com.reactor.cachedb.annotations.CacheRetention.class)) {
            if (element.getAnnotation(CacheEntity.class) == null)
                messager.printMessage(Diagnostic.Kind.ERROR, "@CacheRetention requires @CacheEntity", element);
        }
        for (Element element : roundEnv.getElementsAnnotatedWith(CacheEntity.class)) {
            if (element.getKind() != ElementKind.CLASS && element.getKind() != ElementKind.RECORD) {
                messager.printMessage(Diagnostic.Kind.ERROR, "@CacheEntity can only be used on classes or records", element);
                continue;
            }

            TypeElement typeElement = (TypeElement) element;
            EntityModel entityModel = buildEntityModel(typeElement);
            if (entityModel == null) {
                continue;
            }

            writeBindingClass(entityModel);
            writeMetamodelClass(entityModel);
            roundPackageEntityModels.computeIfAbsent(entityModel.packageName(), ignored -> new ArrayList<>()).add(entityModel);
        }
        for (Map.Entry<String, List<EntityModel>> entry : roundPackageEntityModels.entrySet()) {
            List<EntityModel> models = entry.getValue().stream()
                    .sorted(Comparator.comparing(EntityModel::simpleName))
                    .toList();
            writePackageBindingsClass(entry.getKey(), models);
            writePackageModuleClass(entry.getKey(), models);
            writePackageBindingsRegistrarClass(entry.getKey(), models);
            if (generateSpringConfiguration(models.get(0))) {
                writePackageSpringConfigurationClass(entry.getKey());
            }
            generatedRegistrarClassNames.add(entry.getKey() + ".GeneratedCacheBindingsRegistrar");
        }
        return false; // Do not claim projection annotations used by the sibling processor.
    }

    private EntityModel buildEntityModel(TypeElement typeElement) {
        CacheEntity cacheEntity = typeElement.getAnnotation(CacheEntity.class);
        String packageName = processingEnv.getElementUtils().getPackageOf(typeElement).getQualifiedName().toString();
        String simpleName = typeElement.getSimpleName().toString();
        String tableName = requireSqlIdentifier(typeElement, cacheEntity.table(), "@CacheEntity.table", true);
        if (tableName == null) {
            return null;
        }
        String redisNamespace = cacheEntity.redisNamespace().isBlank() ? simpleName : cacheEntity.redisNamespace();
        if (!isSafeRedisNamespace(redisNamespace)) {
            messager.printMessage(
                    Diagnostic.Kind.ERROR,
                    "@CacheEntity.redisNamespace must contain only letters, digits, underscore, dot, colon, or dash",
                    typeElement
            );
            return null;
        }
        String relationLoaderTypeName = extractLoaderTypeName(cacheEntity, true);
        String pageLoaderTypeName = extractLoaderTypeName(cacheEntity, false);
        boolean recordType = typeElement.getKind() == ElementKind.RECORD;

        if (!recordType && !hasAccessibleNoArgConstructor(typeElement)) {
            messager.printMessage(Diagnostic.Kind.ERROR, "@CacheEntity class must declare a non-private no-arg constructor", typeElement);
            return null;
        }

        if (recordType) {
            int constructorSlots = 1; // JVM reserves one slot for the receiver.
            for (var component : typeElement.getRecordComponents()) {
                String type = component.asType().toString();
                constructorSlots += type.equals("long") || type.equals("double") ? 2 : 1;
            }
            if (constructorSlots > 255) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                        "Record canonical constructor exceeds the JVM 255-slot limit; use a mutable @CacheEntity class for wide schemas", typeElement);
                return null;
            }
        }

        FieldModel idField = null;
        List<FieldModel> persistedFields = new ArrayList<>();
        List<RelationDeclaration> relationDeclarations = new ArrayList<>();
        List<ProjectionModel> projections = new ArrayList<>();
        List<NamedQueryModel> namedQueries = new ArrayList<>();
        List<FetchPresetModel> fetchPresets = new ArrayList<>();
        List<PagePresetModel> pagePresets = new ArrayList<>();
        List<SaveCommandModel> saveCommands = new ArrayList<>();
        List<DeleteCommandModel> deleteCommands = new ArrayList<>();
        String versionColumn = "entity_version";
        String deletedColumn = null;
        String activeMarkerValue = "false";
        String deletedMarkerValue = "true";
        boolean versionDeclared = false;
        boolean softDeleteDeclared = false;

        for (Element enclosed : typeElement.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.METHOD) {
                int generatedDefinitionCount = 0;
                if (enclosed.getAnnotation(CacheProjectionDefinition.class) != null) {
                    generatedDefinitionCount++;
                }
                if (enclosed.getAnnotation(CacheNamedQuery.class) != null) {
                    generatedDefinitionCount++;
                }
                if (enclosed.getAnnotation(CacheFetchPreset.class) != null) {
                    generatedDefinitionCount++;
                }
                if (enclosed.getAnnotation(CachePagePreset.class) != null) {
                    generatedDefinitionCount++;
                }
                if (enclosed.getAnnotation(CacheSaveCommand.class) != null) {
                    generatedDefinitionCount++;
                }
                if (enclosed.getAnnotation(CacheDeleteCommand.class) != null) {
                    generatedDefinitionCount++;
                }
                if (generatedDefinitionCount > 1) {
                    messager.printMessage(
                            Diagnostic.Kind.ERROR,
                            "Cache-generated helper methods may declare only one generated-helper annotation",
                            enclosed
                    );
                    return null;
                }
                ProjectionModel projectionModel = resolveProjectionModel(typeElement, enclosed);
                if (projectionModel == INVALID_PROJECTION_MODEL) {
                    return null;
                }
                if (projectionModel != null) {
                    projections.add(projectionModel);
                    continue;
                }
                NamedQueryModel namedQueryModel = resolveNamedQueryModel(typeElement, enclosed);
                if (namedQueryModel == INVALID_NAMED_QUERY_MODEL) {
                    return null;
                }
                if (namedQueryModel != null) {
                    namedQueries.add(namedQueryModel);
                    continue;
                }
                FetchPresetModel fetchPresetModel = resolveFetchPresetModel(typeElement, enclosed);
                if (fetchPresetModel == INVALID_FETCH_PRESET_MODEL) {
                    return null;
                }
                if (fetchPresetModel != null) {
                    fetchPresets.add(fetchPresetModel);
                    continue;
                }
                PagePresetModel pagePresetModel = resolvePagePresetModel(typeElement, enclosed);
                if (pagePresetModel == INVALID_PAGE_PRESET_MODEL) {
                    return null;
                }
                if (pagePresetModel != null) {
                    pagePresets.add(pagePresetModel);
                    continue;
                }
                SaveCommandModel saveCommandModel = resolveSaveCommandModel(typeElement, enclosed);
                if (saveCommandModel == INVALID_SAVE_COMMAND_MODEL) {
                    return null;
                }
                if (saveCommandModel != null) {
                    saveCommands.add(saveCommandModel);
                    continue;
                }
                DeleteCommandModel deleteCommandModel = resolveDeleteCommandModel(typeElement, enclosed);
                if (deleteCommandModel == INVALID_DELETE_COMMAND_MODEL) {
                    return null;
                }
                if (deleteCommandModel != null) {
                    deleteCommands.add(deleteCommandModel);
                }
                continue;
            }
            if (recordType && enclosed.getKind() == ElementKind.RECORD_COMPONENT) {
                RecordComponentElement component = (RecordComponentElement) enclosed;
                CacheId cacheId = component.getAnnotation(CacheId.class);
                CacheColumn cacheColumn = component.getAnnotation(CacheColumn.class);
                CacheVersion cacheVersion = component.getAnnotation(CacheVersion.class);
                CacheSoftDelete cacheSoftDelete = component.getAnnotation(CacheSoftDelete.class);
                CacheCodec cacheCodec = component.getAnnotation(CacheCodec.class);
                CacheGeneratedId generatedId = component.getAnnotation(CacheGeneratedId.class);
                if (!validateGeneratedId(component, component.asType().toString(), cacheId, generatedId)) {
                    return null;
                }
                if (cacheId == null && cacheColumn == null && cacheVersion == null && cacheSoftDelete == null) {
                    messager.printMessage(Diagnostic.Kind.ERROR,
                            "Every @CacheEntity record component must be persisted; use a projection for non-persisted values",
                            component);
                    return null;
                }
                String columnName = requireSqlIdentifier(
                        component,
                        cacheId != null ? cacheId.column()
                                : cacheColumn != null ? cacheColumn.value()
                                : cacheVersion != null ? cacheVersion.column() : cacheSoftDelete.column(),
                        cacheId != null ? "@CacheId.column"
                                : cacheColumn != null ? "@CacheColumn.value"
                                : cacheVersion != null ? "@CacheVersion.column" : "@CacheSoftDelete.column",
                        false
                );
                if (columnName == null) return null;
                FieldModel fieldModel = new FieldModel(
                        component.getSimpleName().toString(), component.asType().toString(), columnName,
                        cacheId != null, isEnumType(component), extractCodecTypeName(cacheCodec), encryption(component)
                );
                if (!isSupportedType(fieldModel.typeName(), fieldModel.enumType(), fieldModel.codecTypeName() != null)) {
                    messager.printMessage(Diagnostic.Kind.ERROR,
                            "Unsupported persisted record component type: " + fieldModel.typeName(), component);
                    return null;
                }
                if (fieldModel.idField()) {
                    if (idField != null) {
                        messager.printMessage(Diagnostic.Kind.ERROR, "Only one @CacheId component is allowed", component);
                        return null;
                    }
                    idField = fieldModel;
                }
                if (cacheVersion != null) {
                    if (versionDeclared) {
                        messager.printMessage(Diagnostic.Kind.ERROR, "Only one @CacheVersion component is allowed", component);
                        return null;
                    }
                    versionDeclared = true;
                    versionColumn = columnName;
                }
                if (cacheSoftDelete != null) {
                    if (softDeleteDeclared) {
                        messager.printMessage(Diagnostic.Kind.ERROR, "Only one @CacheSoftDelete component is allowed", component);
                        return null;
                    }
                    softDeleteDeclared = true;
                    deletedColumn = columnName;
                    activeMarkerValue = cacheSoftDelete.activeValue();
                    deletedMarkerValue = cacheSoftDelete.deletedValue();
                }
                persistedFields.add(fieldModel);
                continue;
            }
            if (enclosed.getKind() != ElementKind.FIELD || recordType) {
                continue;
            }

            VariableElement field = (VariableElement) enclosed;
            CacheId cacheId = field.getAnnotation(CacheId.class);
            CacheColumn cacheColumn = field.getAnnotation(CacheColumn.class);
            CacheRelation cacheRelation = field.getAnnotation(CacheRelation.class);
            CacheCodec cacheCodec = field.getAnnotation(CacheCodec.class);
            CacheVersion cacheVersion = field.getAnnotation(CacheVersion.class);
            CacheSoftDelete cacheSoftDelete = field.getAnnotation(CacheSoftDelete.class);
            CacheGeneratedId generatedId = field.getAnnotation(CacheGeneratedId.class);
            if (!validateGeneratedId(field, field.asType().toString(), cacheId, generatedId)) {
                return null;
            }

            if (cacheRelation != null) {
                relationDeclarations.add(new RelationDeclaration(field, cacheRelation));
            }

            if (cacheId == null && cacheColumn == null && cacheVersion == null && cacheSoftDelete == null) {
                continue;
            }

            if (field.getModifiers().contains(Modifier.PRIVATE) || field.getModifiers().contains(Modifier.FINAL)) {
                messager.printMessage(Diagnostic.Kind.ERROR, "Persisted fields must not be private or final", field);
                return null;
            }

            String columnName = requireSqlIdentifier(
                    field,
                    cacheId != null ? cacheId.column()
                            : cacheColumn != null ? cacheColumn.value()
                            : cacheVersion != null ? cacheVersion.column() : cacheSoftDelete.column(),
                    cacheId != null ? "@CacheId.column"
                            : cacheColumn != null ? "@CacheColumn.value"
                            : cacheVersion != null ? "@CacheVersion.column" : "@CacheSoftDelete.column",
                    false
            );
            if (columnName == null) {
                return null;
            }
            FieldModel fieldModel = new FieldModel(
                    field.getSimpleName().toString(),
                    field.asType().toString(),
                    columnName,
                    cacheId != null,
                    isEnumType(field),
                    extractCodecTypeName(cacheCodec), encryption(field)
            );

            if (!isSupportedType(fieldModel.typeName(), fieldModel.enumType(), fieldModel.codecTypeName() != null)) {
                messager.printMessage(Diagnostic.Kind.ERROR, "Unsupported persisted field type: " + fieldModel.typeName(), field);
                return null;
            }

            if (fieldModel.idField()) {
                if (idField != null) {
                    messager.printMessage(Diagnostic.Kind.ERROR, "Only one @CacheId field is allowed", field);
                    return null;
                }
                idField = fieldModel;
            }

            if (cacheVersion != null) {
                if (versionDeclared) {
                    messager.printMessage(Diagnostic.Kind.ERROR, "Only one @CacheVersion field is allowed", field);
                    return null;
                }
                versionDeclared = true;
                versionColumn = columnName;
            }
            if (cacheSoftDelete != null) {
                if (softDeleteDeclared) {
                    messager.printMessage(Diagnostic.Kind.ERROR, "Only one @CacheSoftDelete field is allowed", field);
                    return null;
                }
                softDeleteDeclared = true;
                deletedColumn = columnName;
                activeMarkerValue = cacheSoftDelete.activeValue();
                deletedMarkerValue = cacheSoftDelete.deletedValue();
            }

            persistedFields.add(fieldModel);
        }

        if (idField == null) {
            messager.printMessage(Diagnostic.Kind.ERROR, "@CacheEntity class must define one @CacheId field", typeElement);
            return null;
        }

        List<RelationModel> relations = resolveRelations(typeElement, idField, relationDeclarations, relationLoaderTypeName != null);
        if (relations == null) {
            return null;
        }

        Map<String, List<String>> partitionedSortIndexes = resolvePartitionedSortIndexes(
                typeElement,
                persistedFields
        );
        if (partitionedSortIndexes == null) {
            return null;
        }

        java.util.Set<String> indexedColumns = new java.util.LinkedHashSet<>();
        Map<String, List<String>> compositeIndexes;
        Map<String, String> compositeSortColumns;
        try {
            compositeIndexes = compositeIndexes(typeElement, persistedFields);
            if (!validateEncryptedCompositeColumns(typeElement, persistedFields, compositeIndexes)) return null;
            compositeSortColumns = compositeSortColumns(typeElement, persistedFields);
        }
        catch (IllegalArgumentException invalid) {
            messager.printMessage(Diagnostic.Kind.ERROR, invalid.getMessage(), typeElement); return null;
        }
        List<String> declaredIndexes = List.of(cacheEntity.indexedColumns());
        if (declaredIndexes.contains("*")) {
            persistedFields.forEach(field -> indexedColumns.add(field.columnName()));
        } else {
            for (String column : declaredIndexes) {
                if (persistedFields.stream().noneMatch(field -> field.columnName().equals(column))) {
                    messager.printMessage(Diagnostic.Kind.ERROR, "Unknown indexed column: " + column, typeElement);
                    return null;
                }
                indexedColumns.add(column);
            }
            partitionedSortIndexes.forEach((partition, sorts) -> {
                indexedColumns.add(partition);
                indexedColumns.addAll(sorts);
            });
        }

        compositeIndexes.values().forEach(indexedColumns::addAll);
        indexedColumns.addAll(compositeSortColumns.values());
        if (!validateEncryption(typeElement, persistedFields, idField, versionColumn, deletedColumn, indexedColumns,
                compositeIndexes, compositeSortColumns, partitionedSortIndexes, projections, namedQueries)) return null;
        if (!validateProtectionMetadata(typeElement, persistedFields, indexedColumns, cacheEntity.tenantPurgeEnabled()))
            return null;
        LoaderModel relationLoader = resolveLoaderModel(typeElement, relationLoaderTypeName, RELATION_BATCH_LOADER_TYPE, "relationLoader");
        if (relationLoaderTypeName != null && relationLoader == null) {
            return null;
        }
        LoaderModel pageLoader = resolveLoaderModel(typeElement, pageLoaderTypeName, ENTITY_PAGE_LOADER_TYPE, "pageLoader");
        if (pageLoaderTypeName != null && pageLoader == null) {
            return null;
        }
        if (!deleteCommandsMatchIdType(deleteCommands, idField.typeName())) {
            messager.printMessage(
                    Diagnostic.Kind.ERROR,
                    "@CacheDeleteCommand method must return the entity id type: " + idField.typeName(),
                    typeElement
            );
            return null;
        }
        if (hasGeneratedAccessorConflicts(projections, namedQueries, fetchPresets, pagePresets, saveCommands, deleteCommands)) {
            messager.printMessage(
                    Diagnostic.Kind.ERROR,
                    "Generated accessor names must be unique per entity and must not collide with built-in binding helpers",
                    typeElement
            );
            return null;
        }
        for (NamedQueryModel namedQuery : namedQueries) {
            if (namedQuery.route() == null || namedQuery.route().projectionAccessor().isBlank()) {
                continue;
            }
            boolean projectionExists = projections.stream()
                    .anyMatch(projection -> projection.accessorName().equals(namedQuery.route().projectionAccessor()));
            if (!projectionExists) {
                messager.printMessage(
                        Diagnostic.Kind.ERROR,
                        "@CacheRoute projection must reference a @CacheProjectionDefinition accessor: "
                                + namedQuery.route().projectionAccessor(),
                        typeElement
                );
                return null;
            }
        }

        if (persistedFields.size() > com.reactor.cachedb.core.codec.LengthPrefixedPayloadCodec.MAX_FIELDS) {
            messager.printMessage(Diagnostic.Kind.ERROR, "A CacheDB entity supports at most "
                    + com.reactor.cachedb.core.codec.LengthPrefixedPayloadCodec.MAX_FIELDS + " persisted fields", typeElement);
            return null;
        }
        var constants = new java.util.HashSet<String>();
        for (var field : persistedFields) if (!constants.add(columnConstant(field))) {
            messager.printMessage(Diagnostic.Kind.ERROR, "Typed column constant names collide: " + field.columnName(), typeElement);
            return null;
        }
        var near = typeElement.getAnnotation(com.reactor.cachedb.annotations.CacheNearPolicy.class);
        if (near != null) try {
            new com.reactor.cachedb.core.cache.NearCachePolicy(near.maxEntries(), near.ttlSeconds(), near.maxBytes(), near.softTtlSeconds());
        } catch (IllegalArgumentException invalid) {
            messager.printMessage(Diagnostic.Kind.ERROR, invalid.getMessage(), typeElement); return null;
        }
        if (persistedFields.size() > 1000) {
            messager.printMessage(Diagnostic.Kind.NOTE,
                    "Entity has more than 1000 columns; verify target SQL database dictionary limit "
                            + "(Oracle=1000, MSSQL=1024, PostgreSQL=1600); version/deleted columns also count", typeElement);
        }
        return new EntityModel(
                packageName,
                simpleName,
                simpleName + "CacheBinding",
                recordType,
                tableName,
                redisNamespace,
                versionColumn,
                deletedColumn,
                activeMarkerValue,
                deletedMarkerValue,
                idField,
                persistedFields,
                java.util.Set.copyOf(indexedColumns),
                partitionedSortIndexes,
                relations,
                projections,
                namedQueries,
                fetchPresets,
                pagePresets,
                saveCommands,
                deleteCommands,
                relationLoader,
                pageLoader
        );
    }

    private boolean validateGeneratedId(
            Element element,
            String typeName,
            CacheId cacheId,
            CacheGeneratedId generatedId
    ) {
        if (generatedId == null) {
            return true;
        }
        if (cacheId == null) {
            messager.printMessage(Diagnostic.Kind.ERROR, "@CacheGeneratedId may only be declared on @CacheId", element);
            return false;
        }
        return switch (generatedId.value()) {
            case UUID -> requireGeneratedIdType(element, typeName, "java.util.UUID", "UUID");
            case ULID -> requireGeneratedIdType(element, typeName, "java.lang.String", "ULID");
            case SEQUENCE -> {
                if (!requireGeneratedIdType(element, typeName, "java.lang.Long", "SEQUENCE")) {
                    yield false;
                }
                if (generatedId.allocationSize() <= 0 || generatedId.allocationSize() > 10_000) {
                    messager.printMessage(Diagnostic.Kind.ERROR,
                            "@CacheGeneratedId allocationSize must be between 1 and 10000", element);
                    yield false;
                }
                if (!generatedId.sequence().isBlank()
                        && !generatedId.sequence().matches("[A-Za-z0-9_.:-]+")) {
                    messager.printMessage(Diagnostic.Kind.ERROR,
                            "@CacheGeneratedId sequence contains unsafe characters", element);
                    yield false;
                }
                yield true;
            }
        };
    }

    private boolean requireGeneratedIdType(Element element, String actual, String expected, String strategy) {
        if (expected.equals(actual)) {
            return true;
        }
        messager.printMessage(Diagnostic.Kind.ERROR,
                "@CacheGeneratedId(" + strategy + ") requires " + expected, element);
        return false;
    }

    private boolean generateSpringConfiguration(EntityModel model) {
        TypeElement entityType = processingEnv.getElementUtils().getTypeElement(
                model.packageName() + "." + model.simpleName()
        );
        if (entityType == null) {
            return false;
        }
        CacheDomain domain = processingEnv.getElementUtils().getPackageOf(entityType).getAnnotation(CacheDomain.class);
        return domain != null && domain.spring();
    }

    private List<RelationModel> resolveRelations(
            TypeElement entityType,
            FieldModel parentId,
            List<RelationDeclaration> declarations,
            boolean customLoaderDeclared
    ) {
        List<RelationModel> relations = new ArrayList<>(declarations.size());
        for (RelationDeclaration declaration : declarations) {
            VariableElement relationField = declaration.field();
            CacheRelation relation = declaration.annotation();
            if (relation.kind() != CacheRelation.RelationKind.ONE_TO_MANY) {
                messager.printMessage(
                        Diagnostic.Kind.ERROR,
                        "Only ONE_TO_MANY has a generated, bounded batch loader. Model MANY_TO_ONE/ONE_TO_ONE as an explicit repository lookup and MANY_TO_MANY as a join entity or projection.",
                        relationField
                );
                return null;
            }
            if (relationField.getModifiers().contains(Modifier.PRIVATE)
                    || relationField.getModifiers().contains(Modifier.FINAL)) {
                messager.printMessage(
                        Diagnostic.Kind.ERROR,
                        "@CacheRelation fields must not be private or final so generated loaders can assign them",
                        relationField
                );
                return null;
            }
            if (relation.maxRowsPerParent() <= 0 || relation.parentBatchSize() <= 0) {
                messager.printMessage(
                        Diagnostic.Kind.ERROR,
                        "@CacheRelation maxRowsPerParent and parentBatchSize must be greater than zero",
                        relationField
                );
                return null;
            }

            String targetTypeName = resolveRelationTargetTypeName(entityType, relationField, relation);
            if (targetTypeName == null) {
                return null;
            }
            TypeElement targetType = processingEnv.getElementUtils().getTypeElement(targetTypeName);
            if (targetType == null || targetType.getAnnotation(CacheEntity.class) == null) {
                messager.printMessage(
                        Diagnostic.Kind.ERROR,
                        "@CacheRelation target must be an @CacheEntity type: " + targetTypeName,
                        relationField
                );
                return null;
            }

            VariableElement mappedField = findField(targetType, relation.mappedBy());
            if (mappedField == null) {
                messager.printMessage(
                        Diagnostic.Kind.ERROR,
                        "@CacheRelation.mappedBy field was not found on " + targetTypeName + ": " + relation.mappedBy(),
                        relationField
                );
                return null;
            }
            String partitionColumn = persistedColumn(mappedField);
            if (mappedField.getAnnotation(CacheEncrypted.class) != null) {
                encryptionError(relationField, "encrypted mappedBy relation keys are forbidden"); return null;
            }
            if (partitionColumn == null) {
                messager.printMessage(
                        Diagnostic.Kind.ERROR,
                        "@CacheRelation.mappedBy must reference a persisted @CacheId or @CacheColumn field",
                        relationField
                );
                return null;
            }
            if (!sameRelationKeyType(parentId.typeName(), mappedField.asType().toString())) {
                messager.printMessage(
                        Diagnostic.Kind.ERROR,
                        "@CacheRelation parent id and mappedBy types must match: "
                                + parentId.typeName() + " != " + mappedField.asType(),
                        relationField
                );
                return null;
            }

            List<RelationSortModel> sorts = resolveRelationSorts(relationField, targetType, relation.orderBy());
            if (sorts == null) {
                return null;
            }
            if (sorts.isEmpty()) {
                String targetIdColumn = targetType.getEnclosedElements().stream()
                        .filter(candidate -> candidate.getKind() == ElementKind.FIELD)
                        .map(candidate -> (VariableElement) candidate)
                        .filter(candidate -> candidate.getAnnotation(CacheId.class) != null)
                        .map(candidate -> candidate.getAnnotation(CacheId.class).column())
                        .findFirst()
                        .orElse(null);
                if (targetIdColumn == null) {
                    messager.printMessage(Diagnostic.Kind.ERROR,
                            "Generated ONE_TO_MANY relation requires a target @CacheId for stable ordering", relationField);
                    return null;
                }
                sorts = List.of(new RelationSortModel(targetIdColumn, false));
            }
            boolean generatedLoader = true;
            relations.add(new RelationModel(
                    relationField.getSimpleName().toString(),
                    targetType.getSimpleName().toString(),
                    relation.mappedBy(),
                    relation.kind().name(),
                    relation.batchLoadOnly(),
                    targetTypeName,
                    bindingTypeName(targetTypeName),
                    partitionColumn,
                    relation.maxRowsPerParent(),
                    relation.parentBatchSize(),
                    sorts,
                    generatedLoader
            ));
        }
        return List.copyOf(relations);
    }

    private String resolveRelationTargetTypeName(
            TypeElement entityType,
            VariableElement relationField,
            CacheRelation relation
    ) {
        String typedTarget = null;
        try {
            Class<?> target = relation.target();
            if (target != Void.class) {
                typedTarget = target.getCanonicalName();
            }
        } catch (MirroredTypeException exception) {
            String mirroredType = exception.getTypeMirror().toString();
            if (!Void.class.getCanonicalName().equals(mirroredType)) {
                typedTarget = mirroredType;
            }
        }
        String legacyTarget = relation.targetEntity() == null ? "" : relation.targetEntity().trim();
        if (!legacyTarget.isEmpty() && !legacyTarget.contains(".")) {
            legacyTarget = processingEnv.getElementUtils().getPackageOf(entityType).getQualifiedName() + "." + legacyTarget;
        }
        if (typedTarget != null && !legacyTarget.isEmpty() && !typedTarget.equals(legacyTarget)) {
            messager.printMessage(
                    Diagnostic.Kind.ERROR,
                    "@CacheRelation target and targetEntity point to different types",
                    relationField
            );
            return null;
        }
        String resolved = typedTarget != null ? typedTarget : legacyTarget;
        if (resolved == null || resolved.isBlank()) {
            messager.printMessage(
                    Diagnostic.Kind.ERROR,
                    "@CacheRelation must declare target = Entity.class (preferred) or targetEntity",
                    relationField
            );
            return null;
        }
        return resolved;
    }

    private VariableElement findField(TypeElement type, String fieldName) {
        for (Element enclosed : type.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.FIELD && enclosed.getSimpleName().contentEquals(fieldName)) {
                return (VariableElement) enclosed;
            }
        }
        return null;
    }

    private String persistedColumn(VariableElement field) {
        CacheId cacheId = field.getAnnotation(CacheId.class);
        if (cacheId != null) {
            return cacheId.column();
        }
        CacheColumn cacheColumn = field.getAnnotation(CacheColumn.class);
        return cacheColumn == null ? null : cacheColumn.value();
    }

    private boolean sameRelationKeyType(String parentType, String childType) {
        return boxedTypeName(parentType).equals(boxedTypeName(childType));
    }

    private String boxedTypeName(String typeName) {
        return switch (typeName) {
            case "long" -> "java.lang.Long";
            case "int" -> "java.lang.Integer";
            case "short" -> "java.lang.Short";
            case "byte" -> "java.lang.Byte";
            case "boolean" -> "java.lang.Boolean";
            case "double" -> "java.lang.Double";
            case "float" -> "java.lang.Float";
            case "char" -> "java.lang.Character";
            default -> typeName;
        };
    }

    private List<RelationSortModel> resolveRelationSorts(
            VariableElement relationField,
            TypeElement targetType,
            String[] declarations
    ) {
        List<RelationSortModel> sorts = new ArrayList<>();
        for (String declaration : declarations) {
            if (declaration == null || declaration.isBlank()) {
                messager.printMessage(Diagnostic.Kind.ERROR, "@CacheRelation.orderBy entries must not be blank", relationField);
                return null;
            }
            String[] parts = declaration.trim().split("\\s+");
            if (parts.length > 2 || (parts.length == 2
                    && !"ASC".equalsIgnoreCase(parts[1])
                    && !"DESC".equalsIgnoreCase(parts[1]))) {
                messager.printMessage(
                        Diagnostic.Kind.ERROR,
                        "@CacheRelation.orderBy must use '<field-or-column> ASC|DESC': " + declaration,
                        relationField
                );
                return null;
            }
            String column = resolveTargetColumn(targetType, parts[0]);
            if (column == null) {
                messager.printMessage(
                        Diagnostic.Kind.ERROR,
                        "@CacheRelation.orderBy references a non-persisted target field or column: " + parts[0],
                        relationField
                );
                return null;
            }
            sorts.add(new RelationSortModel(column, parts.length == 2 && "DESC".equalsIgnoreCase(parts[1])));
            for (Element member : targetType.getEnclosedElements())
                if (member instanceof VariableElement candidate && member.getAnnotation(CacheEncrypted.class) != null
                        && column.equals(persistedColumn(candidate))) {
                    encryptionError(relationField, "encrypted relation sort fields are forbidden"); return null;
                }
        }
        return List.copyOf(sorts);
    }

    private String resolveTargetColumn(TypeElement targetType, String fieldOrColumn) {
        for (Element enclosed : targetType.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.FIELD) {
                continue;
            }
            VariableElement field = (VariableElement) enclosed;
            String column = persistedColumn(field);
            if (column != null && (field.getSimpleName().contentEquals(fieldOrColumn) || column.equals(fieldOrColumn))) {
                return column;
            }
        }
        return null;
    }

    private Map<String, List<String>> resolvePartitionedSortIndexes(
            TypeElement typeElement,
            List<FieldModel> persistedFields
    ) {
        CachePartitionedIndex[] declarations = typeElement.getAnnotationsByType(CachePartitionedIndex.class);
        if (declarations == null || declarations.length == 0) {
            return Map.of();
        }
        Set<String> columns = persistedFields.stream()
                .map(FieldModel::columnName)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        LinkedHashMap<String, LinkedHashSet<String>> resolved = new LinkedHashMap<>();
        for (CachePartitionedIndex declaration : declarations) {
            String partitionColumn = declaration.partitionBy() == null ? "" : declaration.partitionBy().trim();
            if (!partitionColumn.matches(SQL_IDENTIFIER_PATTERN) || !columns.contains(partitionColumn)) {
                messager.printMessage(
                        Diagnostic.Kind.ERROR,
                        "@CachePartitionedIndex.partitionBy must reference a persisted column: " + partitionColumn,
                        typeElement
                );
                return null;
            }
            if (declaration.sortBy() == null || declaration.sortBy().length == 0) {
                messager.printMessage(
                        Diagnostic.Kind.ERROR,
                        "@CachePartitionedIndex.sortBy must declare at least one persisted column",
                        typeElement
                );
                return null;
            }
            LinkedHashSet<String> sortColumns = resolved.computeIfAbsent(partitionColumn, ignored -> new LinkedHashSet<>());
            for (String rawSortColumn : declaration.sortBy()) {
                String sortColumn = rawSortColumn == null ? "" : rawSortColumn.trim();
                if (!sortColumn.matches(SQL_IDENTIFIER_PATTERN) || !columns.contains(sortColumn)) {
                    messager.printMessage(
                            Diagnostic.Kind.ERROR,
                            "@CachePartitionedIndex.sortBy must reference persisted columns: " + sortColumn,
                            typeElement
                    );
                    return null;
                }
                sortColumns.add(sortColumn);
            }
        }
        LinkedHashMap<String, List<String>> immutable = new LinkedHashMap<>();
        resolved.forEach((partition, sorts) -> immutable.put(partition, List.copyOf(sorts)));
        return Map.copyOf(immutable);
    }

    private String requireSqlIdentifier(Element element, String value, String label, boolean allowQualified) {
        String normalized = value == null ? "" : value.trim();
        String pattern = allowQualified ? QUALIFIED_SQL_IDENTIFIER_PATTERN : SQL_IDENTIFIER_PATTERN;
        if (!normalized.isBlank() && normalized.matches(pattern)) {
            return normalized;
        }
        messager.printMessage(
                Diagnostic.Kind.ERROR,
                label + " must be a safe SQL identifier. Use letters, digits, and underscores"
                        + (allowQualified ? " with optional schema qualification." : "."),
                element
        );
        return null;
    }

    private boolean isSafeRedisNamespace(String value) {
        return value != null && !value.isBlank() && value.matches(REDIS_NAMESPACE_PATTERN);
    }

    private boolean hasGeneratedAccessorConflicts(
            List<ProjectionModel> projections,
            List<NamedQueryModel> namedQueries,
            List<FetchPresetModel> fetchPresets,
            List<PagePresetModel> pagePresets,
            List<SaveCommandModel> saveCommands,
            List<DeleteCommandModel> deleteCommands
    ) {
        java.util.HashSet<String> names = new java.util.HashSet<>();
        for (ProjectionModel projection : projections) {
            if (!names.add(projection.accessorName()) || isReservedBindingAccessorName(projection.accessorName())) {
                return true;
            }
        }
        for (NamedQueryModel namedQuery : namedQueries) {
            if (!names.add(namedQuery.accessorName()) || isReservedBindingAccessorName(namedQuery.accessorName())) {
                return true;
            }
        }
        for (FetchPresetModel fetchPreset : fetchPresets) {
            if (!names.add(fetchPreset.accessorName()) || isReservedBindingAccessorName(fetchPreset.accessorName())) {
                return true;
            }
        }
        for (PagePresetModel pagePreset : pagePresets) {
            if (!names.add(pagePreset.accessorName()) || isReservedBindingAccessorName(pagePreset.accessorName())) {
                return true;
            }
        }
        for (SaveCommandModel saveCommand : saveCommands) {
            if (!names.add(saveCommand.accessorName()) || isReservedBindingAccessorName(saveCommand.accessorName())) {
                return true;
            }
        }
        for (DeleteCommandModel deleteCommand : deleteCommands) {
            if (!names.add(deleteCommand.accessorName()) || isReservedBindingAccessorName(deleteCommand.accessorName())) {
                return true;
            }
        }
        return false;
    }

    private boolean deleteCommandsMatchIdType(List<DeleteCommandModel> deleteCommands, String idTypeName) {
        for (DeleteCommandModel deleteCommand : deleteCommands) {
            if (!idTypeName.equals(deleteCommand.returnTypeName())) {
                return false;
            }
        }
        return true;
    }

    private boolean isReservedBindingAccessorName(String accessorName) {
        return Set.of(
                "repository",
                "register",
                "registerJdbcBacked",
                "using",
                "queries",
                "projections",
                "fetches",
                "pages",
                "commands",
                "deletes",
                "relationLoader",
                "pageLoader",
                "attach",
                "save",
                "findById",
                "findPage",
                "query",
                "deleteById"
        ).contains(accessorName);
    }

    private boolean hasAccessibleNoArgConstructor(TypeElement typeElement) {
        boolean hasConstructor = false;
        for (Element enclosed : typeElement.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.CONSTRUCTOR) {
                hasConstructor = true;
                if (enclosed.getModifiers().contains(Modifier.PRIVATE)) {
                    continue;
                }
                if (((javax.lang.model.element.ExecutableElement) enclosed).getParameters().isEmpty()) {
                    return true;
                }
            }
        }
        return !hasConstructor;
    }

    private ProjectionModel resolveProjectionModel(TypeElement entityTypeElement, Element enclosed) {
        CacheProjectionDefinition projectionDefinition = enclosed.getAnnotation(CacheProjectionDefinition.class);
        if (projectionDefinition == null) {
            return null;
        }
        ExecutableElement method = (ExecutableElement) enclosed;
        if (!method.getModifiers().contains(Modifier.STATIC) || method.getModifiers().contains(Modifier.PRIVATE)) {
            messager.printMessage(
                    Diagnostic.Kind.ERROR,
                    "@CacheProjectionDefinition method must be static and non-private",
                    method
            );
            return INVALID_PROJECTION_MODEL;
        }
        if (!method.getParameters().isEmpty()) {
            messager.printMessage(
                    Diagnostic.Kind.ERROR,
                    "@CacheProjectionDefinition method must not declare parameters",
                    method
            );
            return INVALID_PROJECTION_MODEL;
        }
        if (!(method.getReturnType() instanceof DeclaredType declaredType)) {
            messager.printMessage(
                    Diagnostic.Kind.ERROR,
                    "@CacheProjectionDefinition method must return EntityProjection<...>",
                    method
            );
            return INVALID_PROJECTION_MODEL;
        }
        TypeElement entityProjectionElement = processingEnv.getElementUtils().getTypeElement(ENTITY_PROJECTION_TYPE);
        if (entityProjectionElement == null
                || !processingEnv.getTypeUtils().isSameType(
                processingEnv.getTypeUtils().erasure(declaredType),
                processingEnv.getTypeUtils().erasure(entityProjectionElement.asType())
        )) {
            messager.printMessage(
                    Diagnostic.Kind.ERROR,
                    "@CacheProjectionDefinition method must return EntityProjection<...>",
                    method
            );
            return INVALID_PROJECTION_MODEL;
        }
        List<? extends TypeMirror> typeArguments = declaredType.getTypeArguments();
        if (typeArguments.size() != 3) {
            messager.printMessage(
                    Diagnostic.Kind.ERROR,
                    "@CacheProjectionDefinition must declare EntityProjection<Entity, Projection, Id>",
                    method
            );
            return INVALID_PROJECTION_MODEL;
        }
        String projectedEntityTypeName = typeArguments.get(0).toString();
        if (!entityTypeElement.getQualifiedName().contentEquals(projectedEntityTypeName)) {
            messager.printMessage(
                    Diagnostic.Kind.ERROR,
                    "@CacheProjectionDefinition must project the enclosing entity type, found: " + projectedEntityTypeName,
                    method
            );
            return INVALID_PROJECTION_MODEL;
        }
        String projectionTypeName = typeArguments.get(1).toString();
        String projectionMethodName = method.getSimpleName().toString();
        String projectionAccessorName = projectionDefinition.value().isBlank()
                ? projectionMethodName
                : projectionDefinition.value().trim();
        if (projectionAccessorName.isEmpty()) {
            messager.printMessage(
                    Diagnostic.Kind.ERROR,
                    "@CacheProjectionDefinition value cannot be blank when provided",
                    method
            );
            return INVALID_PROJECTION_MODEL;
        }
        return new ProjectionModel(
                projectionAccessorName,
                projectionMethodName,
                projectionTypeName,
                method.getReturnType().toString()
        );
    }

    private NamedQueryModel resolveNamedQueryModel(TypeElement entityTypeElement, Element enclosed) {
        CacheNamedQuery namedQuery = enclosed.getAnnotation(CacheNamedQuery.class);
        if (namedQuery == null) {
            return null;
        }
        ExecutableElement method = (ExecutableElement) enclosed;
        if (!method.getModifiers().contains(Modifier.STATIC) || method.getModifiers().contains(Modifier.PRIVATE)) {
            messager.printMessage(
                    Diagnostic.Kind.ERROR,
                    "@CacheNamedQuery method must be static and non-private",
                    method
            );
            return INVALID_NAMED_QUERY_MODEL;
        }
        TypeElement querySpecElement = processingEnv.getElementUtils().getTypeElement(QUERY_SPEC_TYPE);
        if (querySpecElement == null
                || !processingEnv.getTypeUtils().isSameType(
                processingEnv.getTypeUtils().erasure(method.getReturnType()),
                processingEnv.getTypeUtils().erasure(querySpecElement.asType())
        )) {
            messager.printMessage(
                    Diagnostic.Kind.ERROR,
                    "@CacheNamedQuery method must return QuerySpec",
                    method
            );
            return INVALID_NAMED_QUERY_MODEL;
        }
        String accessorName = namedQuery.value().isBlank()
                ? method.getSimpleName().toString()
                : namedQuery.value().trim();
        if (accessorName.isEmpty()) {
            messager.printMessage(
                    Diagnostic.Kind.ERROR,
                    "@CacheNamedQuery value cannot be blank when provided",
                    method
            );
            return INVALID_NAMED_QUERY_MODEL;
        }
        return new NamedQueryModel(
                accessorName,
                method.getSimpleName().toString(),
                resolveMethodParameters(method),
                resolveRouteModel(method, accessorName)
        );
    }

    private RouteModel resolveRouteModel(ExecutableElement method, String accessorName) {
        CacheRoute route = method.getAnnotation(CacheRoute.class);
        if (route == null) {
            return null;
        }
        if (route.pageSize() <= 0 || route.hotWindow() < route.pageSize()
                || route.maxColdReadSize() < 0 || route.memoryBudgetBytes() < 0L) {
            messager.printMessage(
                    Diagnostic.Kind.ERROR,
                    "@CacheRoute requires pageSize > 0, hotWindow >= pageSize, and non-negative limits",
                    method
            );
            return null;
        }
        String limitParameter = route.limitParameter().trim();
        VariableElement limitElement = method.getParameters().stream()
                .filter(parameter -> parameter.getSimpleName().contentEquals(limitParameter))
                .findFirst()
                .orElse(null);
        if (limitParameter.isEmpty() || limitElement == null
                || !("int".equals(limitElement.asType().toString())
                || "java.lang.Integer".equals(limitElement.asType().toString()))) {
            messager.printMessage(
                    Diagnostic.Kind.ERROR,
                    "@CacheRoute.limitParameter must reference an int/Integer query method parameter",
                    method
            );
            return null;
        }
        String routeName = route.value().isBlank() ? accessorName : route.value().trim();
        return new RouteModel(
                routeName,
                route.projection().trim(),
                route.pageSize(),
                route.hotWindow(),
                route.maxColdReadSize(),
                route.memoryBudgetBytes(),
                route.strict(),
                limitParameter
        );
    }

    private FetchPresetModel resolveFetchPresetModel(TypeElement entityTypeElement, Element enclosed) {
        CacheFetchPreset fetchPreset = enclosed.getAnnotation(CacheFetchPreset.class);
        if (fetchPreset == null) {
            return null;
        }
        ExecutableElement method = (ExecutableElement) enclosed;
        if (!method.getModifiers().contains(Modifier.STATIC) || method.getModifiers().contains(Modifier.PRIVATE)) {
            messager.printMessage(
                    Diagnostic.Kind.ERROR,
                    "@CacheFetchPreset method must be static and non-private",
                    method
            );
            return INVALID_FETCH_PRESET_MODEL;
        }
        TypeElement fetchPlanElement = processingEnv.getElementUtils().getTypeElement(FETCH_PLAN_TYPE);
        if (fetchPlanElement == null
                || !processingEnv.getTypeUtils().isSameType(
                processingEnv.getTypeUtils().erasure(method.getReturnType()),
                processingEnv.getTypeUtils().erasure(fetchPlanElement.asType())
        )) {
            messager.printMessage(
                    Diagnostic.Kind.ERROR,
                    "@CacheFetchPreset method must return FetchPlan",
                    method
            );
            return INVALID_FETCH_PRESET_MODEL;
        }
        String accessorName = fetchPreset.value().isBlank()
                ? method.getSimpleName().toString()
                : fetchPreset.value().trim();
        if (accessorName.isEmpty()) {
            messager.printMessage(
                    Diagnostic.Kind.ERROR,
                    "@CacheFetchPreset value cannot be blank when provided",
                    method
            );
            return INVALID_FETCH_PRESET_MODEL;
        }
        return new FetchPresetModel(
                accessorName,
                method.getSimpleName().toString(),
                resolveMethodParameters(method)
        );
    }

    private PagePresetModel resolvePagePresetModel(TypeElement entityTypeElement, Element enclosed) {
        CachePagePreset pagePreset = enclosed.getAnnotation(CachePagePreset.class);
        if (pagePreset == null) {
            return null;
        }
        ExecutableElement method = (ExecutableElement) enclosed;
        if (!method.getModifiers().contains(Modifier.STATIC) || method.getModifiers().contains(Modifier.PRIVATE)) {
            messager.printMessage(
                    Diagnostic.Kind.ERROR,
                    "@CachePagePreset method must be static and non-private",
                    method
            );
            return INVALID_PAGE_PRESET_MODEL;
        }
        TypeElement pageWindowElement = processingEnv.getElementUtils().getTypeElement("com.reactor.cachedb.core.cache.PageWindow");
        if (pageWindowElement == null
                || !processingEnv.getTypeUtils().isSameType(
                processingEnv.getTypeUtils().erasure(method.getReturnType()),
                processingEnv.getTypeUtils().erasure(pageWindowElement.asType())
        )) {
            messager.printMessage(
                    Diagnostic.Kind.ERROR,
                    "@CachePagePreset method must return PageWindow",
                    method
            );
            return INVALID_PAGE_PRESET_MODEL;
        }
        String accessorName = pagePreset.value().isBlank()
                ? method.getSimpleName().toString()
                : pagePreset.value().trim();
        if (accessorName.isEmpty()) {
            messager.printMessage(
                    Diagnostic.Kind.ERROR,
                    "@CachePagePreset value cannot be blank when provided",
                    method
            );
            return INVALID_PAGE_PRESET_MODEL;
        }
        return new PagePresetModel(
                accessorName,
                method.getSimpleName().toString(),
                resolveMethodParameters(method)
        );
    }

    private SaveCommandModel resolveSaveCommandModel(TypeElement entityTypeElement, Element enclosed) {
        CacheSaveCommand saveCommand = enclosed.getAnnotation(CacheSaveCommand.class);
        if (saveCommand == null) {
            return null;
        }
        ExecutableElement method = (ExecutableElement) enclosed;
        if (!method.getModifiers().contains(Modifier.STATIC) || method.getModifiers().contains(Modifier.PRIVATE)) {
            messager.printMessage(
                    Diagnostic.Kind.ERROR,
                    "@CacheSaveCommand method must be static and non-private",
                    method
            );
            return INVALID_SAVE_COMMAND_MODEL;
        }
        if (!entityTypeElement.getQualifiedName().contentEquals(method.getReturnType().toString())) {
            messager.printMessage(
                    Diagnostic.Kind.ERROR,
                    "@CacheSaveCommand method must return the enclosing entity type",
                    method
            );
            return INVALID_SAVE_COMMAND_MODEL;
        }
        String accessorName = saveCommand.value().isBlank()
                ? method.getSimpleName().toString()
                : saveCommand.value().trim();
        if (accessorName.isEmpty()) {
            messager.printMessage(
                    Diagnostic.Kind.ERROR,
                    "@CacheSaveCommand value cannot be blank when provided",
                    method
            );
            return INVALID_SAVE_COMMAND_MODEL;
        }
        return new SaveCommandModel(
                accessorName,
                method.getSimpleName().toString(),
                resolveMethodParameters(method)
        );
    }

    private DeleteCommandModel resolveDeleteCommandModel(TypeElement entityTypeElement, Element enclosed) {
        CacheDeleteCommand deleteCommand = enclosed.getAnnotation(CacheDeleteCommand.class);
        if (deleteCommand == null) {
            return null;
        }
        ExecutableElement method = (ExecutableElement) enclosed;
        if (!method.getModifiers().contains(Modifier.STATIC) || method.getModifiers().contains(Modifier.PRIVATE)) {
            messager.printMessage(
                    Diagnostic.Kind.ERROR,
                    "@CacheDeleteCommand method must be static and non-private",
                    method
            );
            return INVALID_DELETE_COMMAND_MODEL;
        }
        String accessorName = deleteCommand.value().isBlank()
                ? method.getSimpleName().toString()
                : deleteCommand.value().trim();
        if (accessorName.isEmpty()) {
            messager.printMessage(
                    Diagnostic.Kind.ERROR,
                    "@CacheDeleteCommand value cannot be blank when provided",
                    method
            );
            return INVALID_DELETE_COMMAND_MODEL;
        }
        return new DeleteCommandModel(
                accessorName,
                method.getSimpleName().toString(),
                method.getReturnType().toString(),
                resolveMethodParameters(method)
        );
    }

    private List<MethodParameterModel> resolveMethodParameters(ExecutableElement method) {
        List<MethodParameterModel> parameters = new ArrayList<>();
        for (VariableElement parameter : method.getParameters()) {
            parameters.add(new MethodParameterModel(
                    parameter.asType().toString(),
                    parameter.getSimpleName().toString()
            ));
        }
        return List.copyOf(parameters);
    }

    private String extractLoaderTypeName(CacheEntity cacheEntity, boolean relationLoader) {
        try {
            Class<?> loaderType = relationLoader ? cacheEntity.relationLoader() : cacheEntity.pageLoader();
            return normalizeLoaderTypeName(loaderType == null ? null : loaderType.getCanonicalName());
        } catch (MirroredTypeException mirroredTypeException) {
            TypeMirror typeMirror = mirroredTypeException.getTypeMirror();
            return normalizeLoaderTypeName(typeMirror == null ? null : typeMirror.toString());
        }
    }

    private String normalizeLoaderTypeName(String typeName) {
        if (typeName == null || typeName.isBlank() || "java.lang.Void".equals(typeName) || "Void".equals(typeName)) {
            return null;
        }
        return typeName;
    }

    private LoaderModel resolveLoaderModel(
            TypeElement entityTypeElement,
            String loaderTypeName,
            String interfaceTypeName,
            String annotationAttributeName
    ) {
        if (loaderTypeName == null) {
            return null;
        }
        TypeElement loaderTypeElement = processingEnv.getElementUtils().getTypeElement(loaderTypeName);
        if (loaderTypeElement == null) {
            messager.printMessage(
                    Diagnostic.Kind.ERROR,
                    "Could not resolve " + annotationAttributeName + " type: " + loaderTypeName,
                    entityTypeElement
            );
            return null;
        }
        if (loaderTypeElement.getKind() != ElementKind.CLASS) {
            messager.printMessage(
                    Diagnostic.Kind.ERROR,
                    annotationAttributeName + " must point to a concrete class",
                    entityTypeElement
            );
            return null;
        }
        TypeElement expectedInterface = processingEnv.getElementUtils().getTypeElement(interfaceTypeName);
        if (expectedInterface == null) {
            messager.printMessage(
                    Diagnostic.Kind.ERROR,
                    "Could not resolve loader interface type: " + interfaceTypeName,
                    entityTypeElement
            );
            return null;
        }
        TypeMirror loaderErasure = processingEnv.getTypeUtils().erasure(loaderTypeElement.asType());
        TypeMirror interfaceErasure = processingEnv.getTypeUtils().erasure(expectedInterface.asType());
        if (!processingEnv.getTypeUtils().isAssignable(loaderErasure, interfaceErasure)) {
            messager.printMessage(
                    Diagnostic.Kind.ERROR,
                    loaderTypeName + " does not implement " + interfaceTypeName,
                    entityTypeElement
            );
            return null;
        }
        LoaderConstructorModel constructor = resolveLoaderConstructor(entityTypeElement, loaderTypeElement, annotationAttributeName);
        if (constructor == null) {
            return null;
        }
        return new LoaderModel(loaderTypeName, constructor);
    }

    private LoaderConstructorModel resolveLoaderConstructor(
            TypeElement entityTypeElement,
            TypeElement loaderTypeElement,
            String annotationAttributeName
    ) {
        boolean declaresConstructor = false;
        List<ExecutableElement> accessibleConstructors = new ArrayList<>();
        for (Element enclosed : loaderTypeElement.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.CONSTRUCTOR) {
                continue;
            }
            declaresConstructor = true;
            if (!enclosed.getModifiers().contains(Modifier.PRIVATE)) {
                accessibleConstructors.add((ExecutableElement) enclosed);
            }
        }
        if (!declaresConstructor) {
            return new LoaderConstructorModel(List.of());
        }
        if (accessibleConstructors.isEmpty()) {
            messager.printMessage(
                    Diagnostic.Kind.ERROR,
                    annotationAttributeName + " must declare at least one non-private constructor",
                    entityTypeElement
            );
            return null;
        }
        if (accessibleConstructors.size() > 1) {
            messager.printMessage(
                    Diagnostic.Kind.ERROR,
                    annotationAttributeName + " must declare a single non-private constructor so generated bindings can instantiate it deterministically",
                    entityTypeElement
            );
            return null;
        }
        List<LoaderDependencyModel> dependencies = new ArrayList<>();
        for (VariableElement parameter : accessibleConstructors.get(0).getParameters()) {
            LoaderDependencyModel dependency = resolveLoaderDependency(entityTypeElement, parameter, annotationAttributeName);
            if (dependency == null) {
                return null;
            }
            dependencies.add(dependency);
        }
        return new LoaderConstructorModel(List.copyOf(dependencies));
    }

    private LoaderDependencyModel resolveLoaderDependency(
            TypeElement entityTypeElement,
            VariableElement parameter,
            String annotationAttributeName
    ) {
        String parameterTypeName = parameter.asType().toString();
        if (CACHE_DATABASE_TYPE.equals(parameterTypeName)) {
            return new LoaderDependencyModel(LoaderDependencyKind.CACHE_DATABASE, null);
        }
        if (CACHE_SESSION_TYPE.equals(parameterTypeName)) {
            return new LoaderDependencyModel(LoaderDependencyKind.CACHE_SESSION, null);
        }
        if (CACHE_POLICY_TYPE.equals(parameterTypeName)) {
            return new LoaderDependencyModel(LoaderDependencyKind.CACHE_POLICY, null);
        }
        if (parameter.asType() instanceof DeclaredType declaredType) {
            TypeElement entityRepositoryElement = processingEnv.getElementUtils().getTypeElement(ENTITY_REPOSITORY_TYPE);
            if (entityRepositoryElement != null
                    && processingEnv.getTypeUtils().isSameType(
                    processingEnv.getTypeUtils().erasure(declaredType),
                    processingEnv.getTypeUtils().erasure(entityRepositoryElement.asType())
            )) {
                List<? extends TypeMirror> typeArguments = declaredType.getTypeArguments();
                if (typeArguments.size() != 2) {
                    messager.printMessage(
                            Diagnostic.Kind.ERROR,
                            annotationAttributeName + " constructor repository dependency must declare concrete entity and id types",
                            entityTypeElement
                    );
                    return null;
                }
                String repositoryEntityTypeName = typeArguments.get(0).toString();
                TypeElement repositoryEntityElement = processingEnv.getElementUtils().getTypeElement(repositoryEntityTypeName);
                if (repositoryEntityElement == null || repositoryEntityElement.getAnnotation(CacheEntity.class) == null) {
                    messager.printMessage(
                            Diagnostic.Kind.ERROR,
                            annotationAttributeName + " repository dependency must target another @CacheEntity type, found: " + repositoryEntityTypeName,
                            entityTypeElement
                    );
                    return null;
                }
                return new LoaderDependencyModel(
                        LoaderDependencyKind.ENTITY_REPOSITORY,
                        bindingTypeName(repositoryEntityTypeName)
                );
            }
        }
        messager.printMessage(
                Diagnostic.Kind.ERROR,
                annotationAttributeName + " constructor parameter type is not supported for declarative binding generation: " + parameterTypeName,
                entityTypeElement
        );
        return null;
    }

    private String bindingTypeName(String entityTypeName) {
        int separatorIndex = entityTypeName.lastIndexOf('.');
        if (separatorIndex < 0) {
            return entityTypeName + "CacheBinding";
        }
        return entityTypeName.substring(0, separatorIndex + 1)
                + entityTypeName.substring(separatorIndex + 1)
                + "CacheBinding";
    }

    private boolean isSupportedType(String typeName, boolean enumType, boolean customCodec) {
        if (enumType || customCodec) {
            return true;
        }
        return switch (typeName) {
            case "java.lang.String",
                    "int", "java.lang.Integer",
                    "long", "java.lang.Long",
                    "boolean", "java.lang.Boolean",
                    "double", "java.lang.Double",
                     "java.math.BigDecimal",
                     "java.util.UUID",
                     "float", "java.lang.Float",
                    "short", "java.lang.Short",
                    "byte", "java.lang.Byte",
                     "java.time.Instant",
                     "java.time.LocalDate",
                     "java.time.LocalTime",
                     "java.time.LocalDateTime",
                    "java.time.OffsetDateTime" -> true;
            default -> false;
        };
    }

    private boolean isEnumType(Element field) {
        Element element = processingEnv.getTypeUtils().asElement(field.asType());
        return element != null && element.getKind() == ElementKind.ENUM;
    }

    private String extractCodecTypeName(CacheCodec cacheCodec) {
        if (cacheCodec == null) {
            return null;
        }
        try {
            cacheCodec.value();
            return null;
        } catch (MirroredTypeException mirroredTypeException) {
            TypeMirror typeMirror = mirroredTypeException.getTypeMirror();
            return typeMirror == null ? null : typeMirror.toString();
        }
    }

    private void writeBindingClass(EntityModel model) {
        String qualifiedName = model.packageName() + "." + model.bindingName();
        try {
            JavaFileObject fileObject = filer.createSourceFile(qualifiedName);
            try (Writer writer = fileObject.openWriter()) {
                writer.write(renderBinding(model));
            }
        } catch (IOException exception) {
            messager.printMessage(Diagnostic.Kind.ERROR, "Could not generate binding class: " + exception.getMessage());
        }
    }

    private void writePackageBindingsClass(String packageName, List<EntityModel> models) {
        if (models.isEmpty()) {
            return;
        }
        String qualifiedName = packageName + ".GeneratedCacheBindings";
        try {
            JavaFileObject fileObject = filer.createSourceFile(qualifiedName);
            try (Writer writer = fileObject.openWriter()) {
                writer.write(renderPackageBindings(packageName, models));
            }
        } catch (IOException exception) {
            messager.printMessage(Diagnostic.Kind.ERROR, "Could not generate package binding class: " + exception.getMessage());
        }
    }

    private void writePackageBindingsRegistrarClass(String packageName, List<EntityModel> models) {
        if (models.isEmpty()) {
            return;
        }
        String qualifiedName = packageName + ".GeneratedCacheBindingsRegistrar";
        try {
            JavaFileObject fileObject = filer.createSourceFile(qualifiedName);
            try (Writer writer = fileObject.openWriter()) {
                writer.write(renderPackageBindingsRegistrar(packageName, models));
            }
        } catch (IOException exception) {
            messager.printMessage(Diagnostic.Kind.ERROR, "Could not generate package binding registrar class: " + exception.getMessage());
        }
    }

    private void writePackageModuleClass(String packageName, List<EntityModel> models) {
        if (models.isEmpty()) {
            return;
        }
        String qualifiedName = packageName + ".GeneratedCacheModule";
        try {
            JavaFileObject fileObject = filer.createSourceFile(qualifiedName);
            try (Writer writer = fileObject.openWriter()) {
                writer.write(renderPackageModule(packageName, models));
            }
        } catch (IOException exception) {
            messager.printMessage(Diagnostic.Kind.ERROR, "Could not generate package cache module class: " + exception.getMessage());
        }
    }

    private void writeGeneratedRegistrarServiceFile() {
        if (generatedRegistrarClassNames.isEmpty()) {
            return;
        }
        try {
            javax.tools.FileObject resource = filer.createResource(
                    StandardLocation.CLASS_OUTPUT,
                    "",
                    "META-INF/services/" + GENERATED_BINDINGS_REGISTRAR_TYPE
            );
            try (Writer writer = resource.openWriter()) {
                for (String registrarClassName : generatedRegistrarClassNames) {
                    writer.write(registrarClassName);
                    writer.write('\n');
                }
            }
        } catch (IOException exception) {
            messager.printMessage(Diagnostic.Kind.ERROR, "Could not generate service descriptor for generated cache binding registrars: " + exception.getMessage());
        }
    }

    private String renderBinding(EntityModel model) {
        String entityName = model.simpleName();
        StringBuilder builder = new StringBuilder();
        builder.append("package ").append(model.packageName()).append(";\n\n");
        builder.append("import com.reactor.cachedb.core.codec.LengthPrefixedPayloadCodec;\n");
        builder.append("import com.reactor.cachedb.core.api.CacheSession;\n");
        builder.append("import com.reactor.cachedb.core.api.EntityRepository;\n");
        builder.append("import com.reactor.cachedb.core.api.ProjectionRepository;\n");
        builder.append("import com.reactor.cachedb.core.api.SourceRepository;\n");
        builder.append("import com.reactor.cachedb.core.active.ActiveRecordInstance;\n");
        builder.append("import com.reactor.cachedb.core.cache.CachePolicy;\n");
        builder.append("import com.reactor.cachedb.core.cache.PageWindow;\n");
        builder.append("import com.reactor.cachedb.core.plan.FetchPlan;\n");
        builder.append("import com.reactor.cachedb.core.model.EntityCodec;\n");
        builder.append("import com.reactor.cachedb.core.model.EntityMetadata;\n");
        builder.append("import com.reactor.cachedb.core.model.WriteDependency;\n");
        builder.append("import com.reactor.cachedb.core.model.WriteReceipt;\n");
        builder.append("import com.reactor.cachedb.core.page.EntityPageLoader;\n");
        builder.append("import com.reactor.cachedb.core.page.VersionedEntity;\n");
        builder.append("import com.reactor.cachedb.core.model.RelationDefinition;\n");
        builder.append("import com.reactor.cachedb.core.model.RelationKind;\n");
        builder.append("import com.reactor.cachedb.core.projection.EntityProjection;\n");
        builder.append("import com.reactor.cachedb.core.query.QuerySpec;\n");
        builder.append("import com.reactor.cachedb.core.query.PartitionedQuerySpec;\n");
        builder.append("import com.reactor.cachedb.core.query.QuerySort;\n");
        builder.append("import com.reactor.cachedb.core.route.RouteCacheContract;\n");
        builder.append("import com.reactor.cachedb.core.route.RouteCacheContext;\n");
        builder.append("import com.reactor.cachedb.core.route.RouteCacheStrictMode;\n");
        builder.append("import com.reactor.cachedb.core.relation.RelationBatchLoader;\n");
        builder.append("import com.reactor.cachedb.core.relation.CompositeRelationBatchLoader;\n");
        builder.append("import com.reactor.cachedb.core.relation.PartitionedRelationBatchLoader;\n");
        builder.append("import com.reactor.cachedb.starter.CacheDatabase;\n");
        builder.append("import com.reactor.cachedb.starter.CacheWarmPlan;\n");
        builder.append("import java.util.LinkedHashMap;\n");
        builder.append("import java.util.List;\n");
        builder.append("import java.util.Map;\n");
        builder.append("import java.util.Optional;\n");
        builder.append("import java.util.function.Function;\n\n");
        builder.append("public final class ").append(model.bindingName()).append(" {\n");
        builder.append("    public static final EntityMetadata<").append(entityName).append(", ").append(model.idField().typeName()).append("> METADATA = new Metadata();\n");
        builder.append("    public static final EntityCodec<").append(entityName).append("> CODEC = new Codec();\n");
        builder.append("    public static final com.reactor.cachedb.core.model.SourceMapping<").append(entityName)
                .append("> SOURCE = com.reactor.cachedb.core.model.SourceMapping.entity(METADATA, CODEC);\n\n");
        for (FieldModel field : model.persistedFields()) {
            if (field.codecTypeName() != null && !hasEncryption(model)) {
                builder.append("    private static final ").append(field.codecTypeName()).append(" ")
                        .append(field.fieldName().toUpperCase()).append("_CODEC = new ")
                        .append(field.codecTypeName()).append("();\n");
            }
        }
        if (model.persistedFields().stream().anyMatch(field -> field.codecTypeName() != null)) {
            builder.append('\n');
        }
        builder.append("    private ").append(model.bindingName()).append("() {\n");
        builder.append("    }\n\n");

        builder.append("    public static com.reactor.cachedb.core.model.SourceMapping<").append(entityName)
                .append("> sourceMapping(CacheSession session) { return session.sourceMapping(METADATA, CODEC); }\n");
        renderWithIdHelper(builder, model);
        if (hasEncryption(model)) {
            builder.append("    public static EntityCodec<").append(entityName)
                    .append("> codec(com.reactor.cachedb.core.crypto.CacheKeyProvider provider) { return CODEC.bindEncryption(provider); }\n")
                    .append("    public static com.reactor.cachedb.core.model.SourceMapping<").append(entityName)
                    .append("> source(com.reactor.cachedb.core.crypto.CacheKeyProvider provider) { return com.reactor.cachedb.core.model.SourceMapping.entity(METADATA, codec(provider)); }\n");
        }
        builder.append('\n');

        renderTypedColumns(builder, model);

        renderMetadata(builder, model);
        builder.append('\n');
        renderCodec(builder, model);
        builder.append('\n');
        renderLoaderFactories(builder, model);
        if (hasRelationLoader(model) || model.pageLoader() != null) {
            builder.append('\n');
        }
        renderProjectionHelpers(builder, model);
        if (!model.projections().isEmpty()) {
            builder.append('\n');
        }
        renderNamedQueryHelpers(builder, model);
        if (!model.namedQueries().isEmpty()) {
            builder.append('\n');
        }
        renderFetchPresetHelpers(builder, model);
        if (!model.fetchPresets().isEmpty()) {
            builder.append('\n');
        }
        renderPagePresetHelpers(builder, model);
        if (!model.pagePresets().isEmpty()) {
            builder.append('\n');
        }
        renderSaveCommandHelpers(builder, model);
        if (!model.saveCommands().isEmpty()) {
            builder.append('\n');
        }
        renderDeleteCommandHelpers(builder, model);
        if (!model.deleteCommands().isEmpty()) {
            builder.append('\n');
        }
        renderActiveFacade(builder, model);
        builder.append('\n');
        renderUseCaseGroups(builder, model);
        builder.append("}\n");
        return builder.toString();
    }

    private String renderPackageBindings(String packageName, List<EntityModel> models) {
        StringBuilder builder = new StringBuilder();
        builder.append("package ").append(packageName).append(";\n\n");
        builder.append("import com.reactor.cachedb.core.cache.CachePolicy;\n");
        builder.append("import com.reactor.cachedb.core.cache.CachePolicyCatalog;\n");
        builder.append("import com.reactor.cachedb.starter.CacheDatabase;\n\n");
        builder.append("public final class GeneratedCacheBindings {\n");
        builder.append("    private GeneratedCacheBindings() {\n");
        builder.append("    }\n\n");
        builder.append("    public static void register(CacheDatabase cacheDatabase) {\n");
        for (EntityModel model : models) {
            builder.append("        ").append(model.bindingName()).append(".register(cacheDatabase);\n");
        }
        builder.append("    }\n\n");
        builder.append("    public static void register(CacheDatabase cacheDatabase, CachePolicy cachePolicy) {\n");
        for (EntityModel model : models) {
            builder.append("        ").append(model.bindingName()).append(".register(cacheDatabase, cachePolicy);\n");
        }
        builder.append("    }\n\n");
        builder.append("    public static void registerJdbcSources(CacheDatabase cacheDatabase, CachePolicyCatalog policyCatalog) {\n");
        builder.append("        CachePolicyCatalog resolvedCatalog = policyCatalog == null ? CachePolicyCatalog.empty() : policyCatalog;\n");
        builder.append("        CachePolicy fallback = cacheDatabase.config().resourceLimits().defaultCachePolicy();\n");
        for (EntityModel model : models) {
            builder.append("        ").append(model.bindingName()).append(".registerJdbcSource(cacheDatabase, resolvedCatalog.resolve(\"")
                    .append(model.simpleName()).append("\", fallback));\n");
        }
        builder.append("    }\n\n");
        builder.append("    public static void registerDeclaredLoaders(CacheDatabase cacheDatabase, CachePolicyCatalog policyCatalog) {\n");
        builder.append("        CachePolicyCatalog resolvedCatalog = policyCatalog == null ? CachePolicyCatalog.empty() : policyCatalog;\n");
        builder.append("        CachePolicy fallback = cacheDatabase.config().resourceLimits().defaultCachePolicy();\n");
        for (EntityModel model : models) {
            builder.append("        ").append(model.bindingName()).append(".registerDeclaredLoaders(cacheDatabase, resolvedCatalog.resolve(\"")
                    .append(model.simpleName()).append("\", fallback));\n");
        }
        builder.append("    }\n");
        builder.append("}\n");
        return builder.toString();
    }

    private void renderWithIdHelper(StringBuilder builder, EntityModel model) {
        builder.append("    public static ").append(model.simpleName()).append(" withId(")
                .append(model.simpleName()).append(" entity, ").append(model.idField().typeName())
                .append(" id) {\n")
                .append("        java.util.Objects.requireNonNull(entity, \"entity\");\n")
                .append("        java.util.Objects.requireNonNull(id, \"id\");\n");
        if (model.recordType()) {
            builder.append("        return new ").append(model.simpleName()).append("(");
            for (int index = 0; index < model.persistedFields().size(); index++) {
                if (index > 0) {
                    builder.append(", ");
                }
                FieldModel field = model.persistedFields().get(index);
                builder.append(field.idField() ? "id" : "entity." + field.fieldName() + "()");
            }
            builder.append(");\n");
        } else {
            builder.append("        entity.").append(model.idField().fieldName()).append(" = id;\n")
                    .append("        return entity;\n");
        }
        builder.append("    }\n");
    }

    private void writeMetamodelClass(EntityModel model) {
        String qualifiedName = model.packageName() + "." + model.simpleName() + "Fields";
        try {
            JavaFileObject fileObject = filer.createSourceFile(qualifiedName);
            try (Writer writer = fileObject.openWriter()) {
                writer.write(renderMetamodel(model));
            }
        } catch (IOException exception) {
            messager.printMessage(Diagnostic.Kind.ERROR, "Could not generate entity metamodel: " + exception.getMessage());
        }
    }

    private String renderMetamodel(EntityModel model) {
        String generatedName = model.simpleName() + "Fields";
        StringBuilder builder = new StringBuilder();
        builder.append("package ").append(model.packageName()).append(";\n\n");
        builder.append("/** Compile-time generated field metamodel. */\n");
        builder.append("public final class ").append(generatedName).append(" {\n");
        boolean wide = model.persistedFields().size() > 256;
        for (int index = 0; index < model.persistedFields().size(); index++) {
            FieldModel field = model.persistedFields().get(index);
            builder.append("    public static final com.reactor.cachedb.core.query.CacheField<")
                    .append(model.simpleName()).append(", ").append(boxedTypeName(field.typeName())).append("> ")
                    .append(field.fieldName()).append(" = ");
            if (wide) {
                builder.append("Fields").append(index / 128).append(".").append(field.fieldName()).append(";\n");
            } else {
                appendMetamodelField(builder, field);
            }
        }
        if (wide) {
            for (int start = 0; start < model.persistedFields().size(); start += 128) {
                builder.append("    private static final class Fields").append(start / 128).append(" {\n");
                for (FieldModel field : model.persistedFields().subList(start, Math.min(start + 128, model.persistedFields().size()))) {
                    builder.append("        static final com.reactor.cachedb.core.query.CacheField<")
                            .append(model.simpleName()).append(", ").append(boxedTypeName(field.typeName()))
                            .append("> ").append(field.fieldName()).append(" = ");
                    appendMetamodelField(builder, field);
                }
                builder.append("    }\n");
            }
        }
        builder.append("\n    private ").append(generatedName).append("() {\n    }\n");
        builder.append("}\n");
        return builder.toString();
    }

    private void appendMetamodelField(StringBuilder builder, FieldModel field) {
        builder.append("new com.reactor.cachedb.core.query.CacheField<>(")
                    .append(javaStringLiteral(field.fieldName())).append(", ")
                    .append(javaStringLiteral(field.columnName())).append(", ")
                    .append(classLiteral(field)).append(");\n");
    }

    private String classLiteral(FieldModel field) {
        if (field.enumType() || field.codecTypeName() != null) {
            return field.typeName() + ".class";
        }
        return switch (field.typeName()) {
            case "int" -> "java.lang.Integer.class";
            case "long" -> "java.lang.Long.class";
            case "boolean" -> "java.lang.Boolean.class";
            case "double" -> "java.lang.Double.class";
            case "float" -> "java.lang.Float.class";
            case "short" -> "java.lang.Short.class";
            case "byte" -> "java.lang.Byte.class";
            default -> field.typeName() + ".class";
        };
    }

    private void renderTypedColumns(StringBuilder builder, EntityModel model) {
        builder.append("    public static final class Columns {\n");
        for (int index = 0; index < model.persistedFields().size(); index++) {
            FieldModel field = model.persistedFields().get(index);
            String constant = columnConstant(field);
            builder.append("        public static final com.reactor.cachedb.core.query.TypedColumn<")
                    .append(model.simpleName()).append(", ").append(boxedTypeName(field.typeName()))
                    .append("> ").append(constant).append(" = Chunk").append(index / 128)
                    .append('.').append(constant).append(";\n");
        }
        for (int start = 0; start < model.persistedFields().size(); start += 128) {
            builder.append("        private static final class Chunk").append(start / 128).append(" {\n");
            for (FieldModel field : model.persistedFields().subList(start, Math.min(start + 128, model.persistedFields().size()))) {
                builder.append("            static final com.reactor.cachedb.core.query.TypedColumn<")
                        .append(model.simpleName()).append(", ").append(boxedTypeName(field.typeName()))
                        .append("> ").append(columnConstant(field))
                        .append(" = new com.reactor.cachedb.core.query.TypedColumn<>(")
                        .append(model.simpleName()).append(".class, ").append(javaStringLiteral(field.columnName()))
                        .append(", ").append(javaStringLiteral(field.fieldName())).append(", ")
                        .append(classLiteral(field)).append(");\n");
            }
            builder.append("        }\n");
        }
        builder.append("        private Columns() {}\n    }\n");
    }

    private String columnConstant(FieldModel field) {
        return field.columnName().replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(java.util.Locale.ROOT);
    }

    private void renderColumnAccessor(StringBuilder builder, EntityModel model) {
        boolean wide = model.persistedFields().size() > 256;
        builder.append("        public boolean directColumnAccess() { return true; }\n")
                .append("        public Object entityColumnValue(").append(model.simpleName()).append(" entity, String column) {\n")
                .append("            if (column == null) return null;\n");
        if (wide) {
            builder.append("            Integer index = FIELD_INDEX.get(column);\n")
                    .append("            if (index == null) return null;\n")
                    .append("            return switch (index / 128) {\n");
            for (int start = 0; start < model.persistedFields().size(); start += 128)
                builder.append("                case ").append(start / 128).append(" -> value")
                        .append(start / 128).append("(entity, index);\n");
            builder.append("                default -> null;\n            };\n        }\n");
            for (int start = 0; start < model.persistedFields().size(); start += 128) {
                builder.append("        private Object value").append(start / 128).append('(')
                        .append(model.simpleName()).append(" entity, int index) {\n")
                        .append("            return switch (index) {\n");
                for (int index = start; index < Math.min(start + 128, model.persistedFields().size()); index++)
                    builder.append("                case ").append(index).append(" -> ")
                            .append(toColumnValueExpression(model, model.persistedFields().get(index))).append(";\n");
                builder.append("                default -> null;\n            };\n        }\n");
            }
        } else {
            builder.append("            return switch (column) {\n");
            for (FieldModel field : model.persistedFields())
                builder.append("                case ").append(javaStringLiteral(field.columnName())).append(" -> ")
                        .append(toColumnValueExpression(model, field)).append(";\n");
            builder.append("                default -> null;\n            };\n        }\n");
        }
        builder.append("        public Map<String, Object> normalizePatchColumns(Map<String, Object> updates) {\n")
                .append("            Map<String, Object> result = new LinkedHashMap<>();\n")
                .append("            for (var entry : updates.entrySet()) {\n");
        if (wide) {
            builder.append("                Integer index = FIELD_INDEX.get(entry.getKey());\n")
                    .append("                if (index == null) throw new IllegalArgumentException(\"Unknown patch column\");\n")
                    .append("                Object value = switch (index / 128) {\n");
            for (int start = 0; start < model.persistedFields().size(); start += 128)
                builder.append("                    case ").append(start / 128).append(" -> normalize")
                        .append(start / 128).append("(entry.getKey(), entry.getValue());\n");
            builder.append("                    default -> throw new IllegalArgumentException(\"Unknown patch column\");\n                };\n");
        } else builder.append("                Object value = normalize0(entry.getKey(), entry.getValue());\n");
        builder.append("                result.put(entry.getKey(), value);\n            }\n            return result;\n        }\n");
        int chunkSize = wide ? 128 : Math.max(1, model.persistedFields().size());
        for (int start = 0; start < model.persistedFields().size(); start += chunkSize) {
            builder.append("        private Object normalize").append(start / chunkSize)
                    .append("(String column, Object raw) {\n            return switch (column) {\n");
            for (FieldModel field : model.persistedFields().subList(start, Math.min(start + chunkSize, model.persistedFields().size()))) {
                builder.append("                case ").append(javaStringLiteral(field.columnName())).append(" -> {\n");
                if (!boxedTypeName(field.typeName()).equals(field.typeName()))
                    builder.append("                    if (raw == null) throw new IllegalArgumentException(\"Primitive patch column cannot be null\");\n");
                builder.append("                    ").append(field.typeName()).append(" value = ")
                        .append(fromColumnExpression("raw", field)).append(";\n                    yield ")
                        .append(field.codecTypeName() != null ? field.fieldName().toUpperCase() + "_CODEC.toColumnValue(value)"
                                : field.enumType() ? "value == null ? null : value.name()" : "value")
                        .append(";\n                }\n");
            }
            builder.append("                default -> throw new IllegalArgumentException(\"Unknown patch column: \" + column);\n            };\n        }\n");
        }
    }

    private void writePackageSpringConfigurationClass(String packageName) {
        String qualifiedName = packageName + ".GeneratedCacheSpringConfiguration";
        try {
            JavaFileObject fileObject = filer.createSourceFile(qualifiedName);
            try (Writer writer = fileObject.openWriter()) {
                writer.write(renderPackageSpringConfiguration(packageName));
            }
        } catch (IOException exception) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                    "Could not generate package Spring configuration class: " + exception.getMessage());
        }
    }

    private String renderPackageSpringConfiguration(String packageName) {
        return "package " + packageName + ";\n\n"
                + "import com.reactor.cachedb.starter.CacheDatabase;\n"
                + "import org.springframework.context.annotation.Bean;\n"
                + "import org.springframework.context.annotation.Configuration;\n\n"
                + "@Configuration(proxyBeanMethods = false)\n"
                + "public class GeneratedCacheSpringConfiguration {\n\n"
                + "    @Bean\n"
                + "    public GeneratedCacheModule.Scope generatedCacheDomain(CacheDatabase cacheDatabase) {\n"
                + "        return GeneratedCacheModule.using(cacheDatabase);\n"
                + "    }\n"
                + "}\n";
    }

    private String renderPackageBindingsRegistrar(String packageName, List<EntityModel> models) {
        StringBuilder builder = new StringBuilder();
        builder.append("package ").append(packageName).append(";\n\n");
        builder.append("import com.reactor.cachedb.core.cache.CachePolicy;\n");
        builder.append("import com.reactor.cachedb.core.cache.CachePolicyCatalog;\n");
        builder.append("import com.reactor.cachedb.starter.CacheDatabase;\n");
        builder.append('\n');
        builder.append("public final class GeneratedCacheBindingsRegistrar implements ")
                .append("com.reactor.cachedb.starter.GeneratedCacheBindingsRegistrar {\n");
        builder.append("    @Override\n");
        builder.append("    public String packageName() {\n");
        builder.append("        return \"").append(packageName).append("\";\n");
        builder.append("    }\n\n");
        builder.append("    @Override\n");
        builder.append("    public void register(CacheDatabase cacheDatabase) {\n");
        builder.append("        GeneratedCacheBindings.register(cacheDatabase);\n");
        builder.append("    }\n\n");
        builder.append("    @Override\n");
        builder.append("    public void register(CacheDatabase cacheDatabase, CachePolicy cachePolicy) {\n");
        builder.append("        GeneratedCacheBindings.register(cacheDatabase, cachePolicy);\n");
        builder.append("    }\n\n");
        builder.append("    @Override\n");
        builder.append("    public java.util.List<String> entityNames() {\n");
        builder.append("        return java.util.List.of(");
        for (int index = 0; index < models.size(); index++) {
            if (index > 0) {
                builder.append(", ");
            }
            builder.append("\"").append(models.get(index).simpleName()).append("\"");
        }
        builder.append(");\n");
        builder.append("    }\n\n");
        builder.append("    @Override\n");
        builder.append("    public void registerJdbcSources(CacheDatabase cacheDatabase, CachePolicyCatalog policyCatalog) {\n");
        builder.append("        GeneratedCacheBindings.registerJdbcSources(cacheDatabase, policyCatalog);\n");
        builder.append("    }\n\n");
        builder.append("    @Override\n");
        builder.append("    public void registerDeclaredLoaders(CacheDatabase cacheDatabase, CachePolicyCatalog policyCatalog) {\n");
        builder.append("        GeneratedCacheBindings.registerDeclaredLoaders(cacheDatabase, policyCatalog);\n");
        builder.append("    }\n");
        builder.append("}\n");
        return builder.toString();
    }

    private String renderPackageModule(String packageName, List<EntityModel> models) {
        StringBuilder builder = new StringBuilder();
        builder.append("package ").append(packageName).append(";\n\n");
        builder.append("import com.reactor.cachedb.core.api.CacheSession;\n");
        builder.append("import com.reactor.cachedb.core.cache.CachePolicy;\n");
        builder.append("import com.reactor.cachedb.core.cache.CachePolicyCatalog;\n");
        builder.append("import com.reactor.cachedb.starter.CacheDatabase;\n\n");
        builder.append("public final class GeneratedCacheModule {\n");
        builder.append("    private GeneratedCacheModule() {\n");
        builder.append("    }\n\n");
        builder.append("    public static void register(CacheDatabase cacheDatabase) {\n");
        builder.append("        GeneratedCacheBindings.register(cacheDatabase);\n");
        builder.append("    }\n\n");
        builder.append("    public static void register(CacheDatabase cacheDatabase, CachePolicy cachePolicy) {\n");
        builder.append("        GeneratedCacheBindings.register(cacheDatabase, cachePolicy);\n");
        builder.append("    }\n\n");
        builder.append("    public static void registerJdbcBacked(CacheDatabase cacheDatabase) {\n");
        builder.append("        registerJdbcBacked(cacheDatabase, CachePolicyCatalog.empty());\n");
        builder.append("    }\n\n");
        builder.append("    public static void registerJdbcBacked(CacheDatabase cacheDatabase, CachePolicyCatalog policyCatalog) {\n");
        builder.append("        GeneratedCacheBindings.registerJdbcSources(cacheDatabase, policyCatalog);\n");
        builder.append("        GeneratedCacheBindings.registerDeclaredLoaders(cacheDatabase, policyCatalog);\n");
        builder.append("    }\n\n");
        builder.append("    public static Scope using(CacheSession cacheSession) {\n");
        builder.append("        return new Scope(cacheSession, null);\n");
        builder.append("    }\n\n");
        builder.append("    public static Scope using(CacheSession cacheSession, CachePolicy cachePolicy) {\n");
        builder.append("        return new Scope(cacheSession, cachePolicy);\n");
        builder.append("    }\n\n");
        builder.append("    public static final class Scope {\n");
        builder.append("        private final CacheSession cacheSession;\n");
        builder.append("        private final CachePolicy cachePolicy;\n");
        for (EntityModel model : models) {
            String accessorName = packageScopeAccessorName(model, models);
            builder.append("        private volatile ").append(model.bindingName()).append(".Scope ")
                    .append(accessorName).append("Scope;\n");
        }
        builder.append('\n');
        builder.append("        private Scope(CacheSession cacheSession, CachePolicy cachePolicy) {\n");
        builder.append("            this.cacheSession = java.util.Objects.requireNonNull(cacheSession, \"cacheSession\");\n");
        builder.append("            this.cachePolicy = cachePolicy;\n");
        builder.append("        }\n\n");
        for (int index = 0; index < models.size(); index++) {
            EntityModel model = models.get(index);
            String accessorName = packageScopeAccessorName(model, models);
            builder.append("        public ").append(model.bindingName()).append(".Scope ").append(accessorName).append("() {\n");
            builder.append("            ").append(model.bindingName()).append(".Scope resolved = ")
                    .append(accessorName).append("Scope;\n");
            builder.append("            if (resolved == null) {\n");
            builder.append("                synchronized (this) {\n");
            builder.append("                    resolved = ").append(accessorName).append("Scope;\n");
            builder.append("                    if (resolved == null) {\n");
            builder.append("                        resolved = cachePolicy == null ? ")
                    .append(model.bindingName()).append(".using(cacheSession) : ")
                    .append(model.bindingName()).append(".using(cacheSession, cachePolicy);\n");
            builder.append("                        ").append(accessorName).append("Scope = resolved;\n");
            builder.append("                    }\n");
            builder.append("                }\n");
            builder.append("            }\n");
            builder.append("            return resolved;\n");
            builder.append("        }\n");
            if (index < models.size() - 1) {
                builder.append('\n');
            }
        }
        builder.append("    }\n");
        builder.append("}\n");
        return builder.toString();
    }

    private String packageScopeAccessorName(EntityModel model, List<EntityModel> models) {
        String baseName = stripEntitySuffix(model.simpleName());
        String candidate = pluralize(decapitalize(baseName));
        long collisions = models.stream()
                .map(EntityModel::simpleName)
                .map(this::stripEntitySuffix)
                .map(this::decapitalize)
                .map(this::pluralize)
                .filter(candidate::equals)
                .count();
        if (collisions <= 1) {
            return candidate;
        }
        return decapitalize(model.simpleName()) + "Bindings";
    }

    private String stripEntitySuffix(String simpleName) {
        return simpleName.endsWith("Entity") ? simpleName.substring(0, simpleName.length() - "Entity".length()) : simpleName;
    }

    private String decapitalize(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        if (value.length() == 1) {
            return value.toLowerCase();
        }
        return Character.toLowerCase(value.charAt(0)) + value.substring(1);
    }

    private String pluralize(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        if (value.endsWith("y") && value.length() > 1 && isConsonant(value.charAt(value.length() - 2))) {
            return value.substring(0, value.length() - 1) + "ies";
        }
        if (value.endsWith("s") || value.endsWith("x") || value.endsWith("z")
                || value.endsWith("ch") || value.endsWith("sh")) {
            return value + "es";
        }
        return value + "s";
    }

    private boolean isConsonant(char character) {
        char lower = Character.toLowerCase(character);
        return lower != 'a' && lower != 'e' && lower != 'i' && lower != 'o' && lower != 'u';
    }

    private java.util.Set<String> tenantFieldNames(TypeElement element) {
        var tenantNames = new java.util.LinkedHashSet<String>();
        if (element != null) for (var member : element.getEnclosedElements()) {
            if (member.getAnnotation(com.reactor.cachedb.annotations.CacheTenantId.class) == null) continue;
            String name = member.getSimpleName().toString();
            if (member.getKind() == ElementKind.METHOD && name.startsWith("get") && name.length() > 3)
                name = Character.toLowerCase(name.charAt(3)) + name.substring(4);
            tenantNames.add(name);
        }
        return tenantNames;
    }

    private FieldModel tenantField(java.util.Set<String> tenantNames, List<FieldModel> fields) {
        return tenantNames.size() == 1 ? fields.stream()
                .filter(field -> tenantNames.contains(field.fieldName())).findFirst().orElse(null) : null;
    }

    private FieldModel retentionField(TypeElement element, List<FieldModel> fields) {
        var retention = element.getAnnotation(com.reactor.cachedb.annotations.CacheRetention.class);
        if (retention == null) return null;
        return fields.stream().filter(field -> retention.field().equals(field.fieldName())
                || retention.field().equals(field.columnName())).findFirst().orElse(null);
    }

    private boolean validateProtectionMetadata(TypeElement element, List<FieldModel> fields,
            java.util.Set<String> indexes, boolean purge) {
        if (purge) {
            var tenant = tenantField(tenantFieldNames(element), fields);
            if (tenant == null || tenant.idField() || !tenant.typeName().equals("java.lang.String")
                    || !indexes.contains(tenant.columnName())) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                        "tenantPurgeEnabled requires exactly one persisted indexed non-ID String @CacheTenantId", element);
                return false;
            }
        }
        var retention = element.getAnnotation(com.reactor.cachedb.annotations.CacheRetention.class);
        if (retention == null) return true;
        FieldModel field = retentionField(element, fields);
        if (field == null || !indexes.contains(field.columnName()) || field.codecTypeName() != null
                || !java.util.Set.of("java.time.LocalDate", "java.time.Instant", "java.time.LocalDateTime",
                        "java.time.OffsetDateTime").contains(field.typeName())) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                    "@CacheRetention requires a persisted indexed temporal field (LocalDate, Instant, LocalDateTime or OffsetDateTime)", element);
            return false;
        }
        if (retention.days() < 1 || retention.days() > 36_500) {
            messager.printMessage(Diagnostic.Kind.ERROR, "@CacheRetention.days must be between 1 and 36500", element);
            return false;
        }
        return true;
    }

    private void renderMetadata(StringBuilder builder, EntityModel model) {
        var element = processingEnv.getElementUtils().getTypeElement(model.packageName() + "." + model.simpleName());
        var tenantNames = tenantFieldNames(element);
        FieldModel tenant = tenantField(tenantNames, model.persistedFields());
        if (!tenantNames.isEmpty() && (tenant == null || !tenant.typeName().equals("java.lang.String") || tenant.idField()))
            messager.printMessage(Diagnostic.Kind.ERROR, "@CacheTenantId requires exactly one persisted non-ID String field", element);
        var schema = element == null ? null : element.getAnnotation(com.reactor.cachedb.annotations.CacheSchemaVersion.class);
        String upcaster = "com.reactor.cachedb.core.codec.NoOpPayloadUpcaster";
        if (schema != null) {
            try { upcaster = schema.upcaster().getCanonicalName(); }
            catch (javax.lang.model.type.MirroredTypeException mirror) { upcaster = mirror.getTypeMirror().toString(); }
            if (upcaster.equals("java.lang.Void")) upcaster = "com.reactor.cachedb.core.codec.NoOpPayloadUpcaster";
            if (schema.version() < 1 || (schema.version() > 1 && upcaster.endsWith(".NoOpPayloadUpcaster")))
                messager.printMessage(Diagnostic.Kind.ERROR, "Schema version > 1 requires an explicit upcaster", element);
            builder.append("    private static final com.reactor.cachedb.core.codec.CachePayloadUpcaster UPCASTER = new ")
                    .append(upcaster).append("();\n");
        }
        var near = element == null ? null : element.getAnnotation(com.reactor.cachedb.annotations.CacheNearPolicy.class);
        if (near != null) builder.append("    private static final com.reactor.cachedb.core.cache.NearCachePolicy NEAR_POLICY = new com.reactor.cachedb.core.cache.NearCachePolicy(")
                .append(near.maxEntries()).append(", ").append(near.ttlSeconds()).append(", ")
                .append(near.maxBytes()).append("L, ").append(near.softTtlSeconds()).append(");\n");
        builder.append("    private static final class Metadata implements EntityMetadata<")
                .append(model.simpleName()).append(", ").append(model.idField().typeName()).append("> {\n");
        if (element != null && element.getAnnotation(CacheEntity.class).independentWriteReordering())
            builder.append("        public boolean independentWriteReordering() { return true; }\n");
        if (near != null) builder.append("        public com.reactor.cachedb.core.cache.NearCachePolicy nearCachePolicy() { return NEAR_POLICY; }\n");
        if (element != null) {
            var indexes = compositeIndexes(element, model.persistedFields());
            var compositeSorts = compositeSortColumns(element, model.persistedFields());
            if (!compositeSorts.isEmpty()) {
                builder.append("        public java.util.Map<String, String> compositeSortColumns() { return COMPOSITE_SORTS; }\n")
                        .append("        private static final java.util.Map<String, String> COMPOSITE_SORTS = java.util.Map.ofEntries(");
                boolean firstSort = true;
                for (var sort : compositeSorts.entrySet()) {
                    if (!firstSort) builder.append(", ");
                    firstSort = false;
                    builder.append("java.util.Map.entry(").append(javaStringLiteral(sort.getKey())).append(", ")
                            .append(javaStringLiteral(sort.getValue())).append(")");
                }
                builder.append(");\n");
            }
            if (!indexes.isEmpty()) {
                builder.append("        public java.util.Map<String, java.util.List<String>> compositeExactIndexes() { return COMPOSITE_INDEXES; }\n")
                        .append("        private static final java.util.Map<String, java.util.List<String>> COMPOSITE_INDEXES = java.util.Map.ofEntries(");
                boolean first = true;
                for (var index : indexes.entrySet()) {
                    if (!first) builder.append(", "); first = false;
                    builder.append("java.util.Map.entry(").append(javaStringLiteral(index.getKey())).append(", java.util.List.of(");
                    appendQuotedList(builder, index.getValue()); builder.append("))");
                }
                builder.append(");\n");
            }
        }
        if (tenant != null) builder.append("        public String tenantFieldName() { return ")
                .append(javaStringLiteral(tenant.columnName())).append("; }\n")
                .append("        public String extractTenantId(").append(model.simpleName())
                .append(" entity) { return ").append(accessExpression(model, tenant)).append("; }\n");
        var declaration = element == null ? null : element.getAnnotation(CacheEntity.class);
        if (declaration != null) {
            if (declaration.tenantPurgeEnabled())
                builder.append("        public boolean tenantPurgeEnabled() { return true; }\n");
            if (declaration.compressionThresholdBytes() < 0 || declaration.compressionThresholdBytes() > 16 * 1024 * 1024)
                messager.printMessage(Diagnostic.Kind.ERROR, "Invalid compressionThresholdBytes", element);
            builder.append("        public int compressionThresholdBytes() { return ")
                    .append(declaration.compressionThresholdBytes()).append("; }\n");
        }
        var retention = element == null ? null : element.getAnnotation(com.reactor.cachedb.annotations.CacheRetention.class);
        if (retention != null) {
            FieldModel field = retentionField(element, model.persistedFields());
            String policy = "com.reactor.cachedb.core.retention.RetentionPolicy";
            builder.append("        private static final java.util.Optional<").append(policy)
                    .append("> RETENTION_POLICY = java.util.Optional.of(new ").append(policy).append("(")
                    .append(javaStringLiteral(field.columnName())).append(", ").append(retention.days()).append(", ")
                    .append(policy).append(".Action.").append(retention.action().name()).append(", ")
                    .append(policy).append(".ScoreUnit.")
                    .append(field.typeName().equals("java.time.LocalDate") ? "EPOCH_DAY" : "EPOCH_MILLIS")
                    .append("));\n")
                    .append("        public java.util.Optional<").append(policy)
                    .append("> retentionPolicy() { return RETENTION_POLICY; }\n");
        }
        if (schema != null) {
            builder.append("        public int schemaVersion() { return ").append(schema.version()).append("; }\n")
                    .append("        public com.reactor.cachedb.core.codec.CachePayloadUpcaster payloadUpcaster() { return UPCASTER; }\n");
        }
        if (model.recordType() && model.relations().isEmpty()
                && model.persistedFields().stream().allMatch(this::deeplyImmutableField)) {
            builder.append("        public boolean deeplyImmutable() { return true; }\n");
        }
        builder.append("        @Override\n");
        builder.append("        public String entityName() {\n");
        builder.append("            return ").append(javaStringLiteral(model.simpleName())).append(";\n");
        builder.append("        }\n\n");
        builder.append("        @Override\n");
        builder.append("        public String tableName() {\n");
        builder.append("            return ").append(javaStringLiteral(model.tableName())).append(";\n");
        builder.append("        }\n\n");
        builder.append("        @Override\n");
        builder.append("        public String redisNamespace() {\n");
        builder.append("            return ").append(javaStringLiteral(model.redisNamespace())).append(";\n");
        builder.append("        }\n\n");
        builder.append("        @Override\n");
        builder.append("        public String idColumn() {\n");
        builder.append("            return ").append(javaStringLiteral(model.idField().columnName())).append(";\n");
        builder.append("        }\n\n");
        builder.append("        @Override\n");
        builder.append("        public String versionColumn() {\n");
        builder.append("            return ").append(javaStringLiteral(model.versionColumn())).append(";\n");
        builder.append("        }\n\n");
        builder.append("        @Override\n");
        builder.append("        public String deletedColumn() {\n");
        builder.append("            return ").append(model.deletedColumn() == null ? "null" : javaStringLiteral(model.deletedColumn())).append(";\n");
        builder.append("        }\n\n");
        builder.append("        @Override\n");
        builder.append("        public String activeMarkerValue() {\n");
        builder.append("            return ").append(javaStringLiteral(model.activeMarkerValue())).append(";\n");
        builder.append("        }\n\n");
        builder.append("        @Override\n");
        builder.append("        public String deletedMarkerValue() {\n");
        builder.append("            return ").append(javaStringLiteral(model.deletedMarkerValue())).append(";\n");
        builder.append("        }\n\n");
        builder.append("        @Override\n");
        builder.append("        public Class<").append(model.simpleName()).append("> entityType() {\n");
        builder.append("            return ").append(model.simpleName()).append(".class;\n");
        builder.append("        }\n\n");
        builder.append("        @Override\n");
        builder.append("        public Function<").append(model.simpleName()).append(", ").append(model.idField().typeName()).append("> idAccessor() {\n");
        builder.append("            return entity -> ").append(accessExpression(model, model.idField())).append(";\n");
        builder.append("        }\n\n");
        builder.append("        @Override\n");
        builder.append("        public List<String> columns() {\n");
        builder.append("            return List.of(");
        appendQuotedList(builder, model.persistedFields().stream().map(FieldModel::columnName).toList());
        builder.append(");\n");
        builder.append("        }\n\n");
        builder.append("        @Override\n");
        builder.append("        public Map<String, String> columnTypes() {\n");
        builder.append("            return COLUMN_TYPES;\n");
        builder.append("        }\n\n");
        builder.append("        public java.util.Set<String> indexedColumns() { return INDEXED_COLUMNS; }\n")
                .append("        private static final java.util.Set<String> INDEXED_COLUMNS = java.util.Set.of(");
        var indexed = new java.util.TreeSet<>(model.indexedColumns());
        if (tenant != null) indexed.add(tenant.columnName());
        appendQuotedList(builder, List.copyOf(indexed));
        builder.append(");\n");
        builder.append("        private static final Map<String, String> COLUMN_TYPES = createColumnTypes();\n");
        if (hasEncryption(model)) {
            builder.append("        public Map<String, com.reactor.cachedb.core.crypto.EncryptedColumnDescriptor> encryptedColumns() { return ENCRYPTED; }\n")
                    .append("        private static final Map<String, com.reactor.cachedb.core.crypto.EncryptedColumnDescriptor> ENCRYPTED = Map.ofEntries(");
            boolean firstEncrypted = true;
            for (FieldModel field : model.persistedFields()) if (field.encryption() != null) {
                if (!firstEncrypted) builder.append(", ");
                firstEncrypted = false;
                builder.append("Map.entry(").append(javaStringLiteral(field.columnName()))
                        .append(", new com.reactor.cachedb.core.crypto.EncryptedColumnDescriptor(")
                        .append(javaStringLiteral(field.encryption().keyRing()))
                        .append(", com.reactor.cachedb.core.crypto.EncryptedColumnDescriptor.Scope.")
                        .append(field.encryption().scope()).append(", ").append(field.encryption().blindIndex()).append("))");
            }
            builder.append(");\n");
        }
        builder.append("        private static Map<String, String> createColumnTypes() {\n");
        builder.append("            LinkedHashMap<String, String> columnTypes = new LinkedHashMap<>();\n");
        for (FieldModel field : model.persistedFields()) {
            builder.append("            columnTypes.put(").append(javaStringLiteral(field.columnName())).append(", ")
                    .append(javaStringLiteral(field.encryption() != null && field.encryption().scope().equals("REDIS_AND_SOURCE")
                            ? "java.lang.String" : columnTypeName(field))).append(");\n");
        }
        builder.append("            return java.util.Collections.unmodifiableMap(columnTypes);\n");
        builder.append("        }\n\n");
        builder.append("        @Override\n");
        builder.append("        public Map<String, List<String>> partitionedSortIndexes() {\n");
        builder.append("            return PARTITIONED_SORT_INDEXES;\n");
        builder.append("        }\n\n");
        builder.append("        private static final Map<String, List<String>> PARTITIONED_SORT_INDEXES = createPartitionedSortIndexes();\n");
        builder.append("        private static Map<String, List<String>> createPartitionedSortIndexes() {\n");
        if (model.partitionedSortIndexes().isEmpty()) {
            builder.append("            return Map.of();\n");
        } else {
            builder.append("            LinkedHashMap<String, List<String>> indexes = new LinkedHashMap<>();\n");
            for (Map.Entry<String, List<String>> entry : model.partitionedSortIndexes().entrySet()) {
                builder.append("            indexes.put(").append(javaStringLiteral(entry.getKey())).append(", List.of(");
                appendQuotedList(builder, entry.getValue());
                builder.append("));\n");
            }
            builder.append("            return Map.copyOf(indexes);\n");
        }
        builder.append("        }\n\n");
        builder.append("        @Override\n");
        builder.append("        public List<RelationDefinition> relations() {\n");
        if (model.relations().isEmpty()) {
            builder.append("            return List.of();\n");
        } else {
            builder.append("            return List.of(\n");
            for (int index = 0; index < model.relations().size(); index++) {
                RelationModel relation = model.relations().get(index);
                builder.append("                    new RelationDefinition(")
                        .append(javaStringLiteral(relation.name())).append(", ")
                        .append(javaStringLiteral(relation.targetEntity())).append(", ")
                        .append(javaStringLiteral(relation.mappedBy())).append(", RelationKind.")
                        .append(relation.kindName()).append(", ")
                        .append(relation.batchLoadOnly()).append(")");
                builder.append(index == model.relations().size() - 1 ? "\n" : ",\n");
            }
            builder.append("            );\n");
        }
        builder.append("        }\n");
        builder.append("    }\n");
    }

    private EncryptionModel encryption(Element element) {
        CacheEncrypted annotation = element.getAnnotation(CacheEncrypted.class);
        return annotation == null ? null : new EncryptionModel(annotation.keyRing(), annotation.scope().name(), annotation.blindIndex());
    }

    private boolean hasEncryption(EntityModel model) {
        return model.persistedFields().stream().anyMatch(field -> field.encryption() != null);
    }

    private boolean encryptionError(Element element, String message) {
        messager.printMessage(Diagnostic.Kind.ERROR, "F-3 encryption: " + message, element);
        return false;
    }

    private boolean validateEncryptedTargets(RoundEnvironment round) {
        boolean valid = true;
        for (Element member : round.getElementsAnnotatedWith(CacheEncrypted.class)) {
            if (!(member.getEnclosingElement() instanceof TypeElement owner) || owner.getAnnotation(CacheEntity.class) == null) {
                valid = encryptionError(member, "@CacheEncrypted requires an enclosing @CacheEntity"); continue;
            }
            if (owner.getKind() == ElementKind.RECORD) {
                var component = owner.getRecordComponents().stream()
                        .filter(value -> value.getSimpleName().contentEquals(member.getSimpleName())).findFirst().orElse(null);
                if (component == null || encryption(component) == null || !encryption(component).equals(encryption(member))) {
                    valid = encryptionError(member, "annotate the persisted record component, not an accessor or static field");
                }
                continue;
            }
            if (member.getKind() != ElementKind.FIELD || member.getModifiers().contains(Modifier.STATIC)
                    || member.getAnnotation(CacheColumn.class) == null && member.getAnnotation(CacheId.class) == null
                    && member.getAnnotation(CacheVersion.class) == null && member.getAnnotation(CacheSoftDelete.class) == null) {
                valid = encryptionError(member, "@CacheEncrypted supports persisted instance fields, not accessors or transient fields");
            }
        }
        for (Element projection : round.getElementsAnnotatedWith(com.reactor.cachedb.annotations.CacheProjectionRecord.class)) {
            TypeMirror source;
            try {
                TypeElement sourceType = processingEnv.getElementUtils().getTypeElement(projection.getAnnotation(
                        com.reactor.cachedb.annotations.CacheProjectionRecord.class).source().getCanonicalName());
                source = sourceType == null ? null : sourceType.asType();
            } catch (MirroredTypeException mirror) { source = mirror.getTypeMirror(); }
            Element owner = source == null ? null : processingEnv.getTypeUtils().asElement(source);
            if (owner instanceof TypeElement type && type.getEnclosedElements().stream().anyMatch(value -> encryption(value) != null)) {
                valid = encryptionError(projection, "projections of encrypted entities require an explicitly protected projection codec and are unsupported");
            }
        }
        return valid;
    }

    private boolean validateEncryption(TypeElement owner, List<FieldModel> fields, FieldModel id,
            String version, String deleted, Set<String> indexed, Map<String, List<String>> composites,
            Map<String, String> compositeSorts, Map<String, List<String>> partitions,
            List<ProjectionModel> projections, List<NamedQueryModel> namedQueries) {
        long encrypted = fields.stream().filter(field -> field.encryption() != null).count();
        if (encrypted == 0) return true;
        String namespace = owner.getAnnotation(CacheEntity.class).redisNamespace();
        if (namespace.isBlank()) namespace = owner.getSimpleName().toString();
        if (namespace.length() > 1024) return encryptionError(owner, "encrypted namespace exceeds its fixed AAD budget");
        var physicalColumns = fields.stream().map(FieldModel::columnName).collect(java.util.stream.Collectors.toSet());
        if (version != null && !version.isBlank()) physicalColumns.add(version);
        if (deleted != null && !deleted.isBlank()) physicalColumns.add(deleted);
        if (encrypted > 32 || physicalColumns.size() > 128)
            return encryptionError(owner, "at most 32 encrypted and 128 physical columns including framework columns are supported");
        if (id.codecTypeName() != null) return encryptionError(owner, "custom ID codecs are unsupported for encrypted entities");
        if (!projections.isEmpty() || !namedQueries.isEmpty()) return encryptionError(owner,
                "opaque projection and named-query factories are unsupported for encrypted entities; use validated repository queries");
        for (FieldModel field : fields) {
            EncryptionModel protection = field.encryption();
            boolean tenant = owner.getEnclosedElements().stream().anyMatch(member -> {
                String name = member.getSimpleName().toString();
                if (member.getKind() == ElementKind.METHOD && name.startsWith("get") && name.length() > 3)
                    name = Character.toLowerCase(name.charAt(3)) + name.substring(4);
                return member.getAnnotation(com.reactor.cachedb.annotations.CacheTenantId.class) != null && name.equals(field.fieldName());
            });
            if (tenant && field.codecTypeName() != null) return encryptionError(owner, "custom tenant codecs are unsupported for encrypted entities");
            if (protection == null) continue;
            if (field.idField() || tenant || field.columnName().equals(version) || field.columnName().equals(deleted))
                return encryptionError(owner, "ID, tenant, version and soft-delete columns cannot be encrypted");
            if (!protection.keyRing().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))
                return encryptionError(owner, "invalid key-ring name");
            if (field.typeName().contains("<") || field.typeName().contains("[")
                    || field.codecTypeName() != null && boxedTypeName(field.typeName()).equals(field.typeName())
                    && processingEnv.getElementUtils().getTypeElement(field.typeName()) == null)
                return encryptionError(owner, "encrypted generic, array and type-variable fields are unsupported");
            if (!protection.blindIndex() && indexed.contains(field.columnName()))
                return encryptionError(owner, "encrypted columns cannot be indexed without blindIndex=true; replace wildcard indexedColumns explicitly");
            if (composites.values().stream().anyMatch(columns -> columns.contains(field.columnName()))
                    || compositeSorts.containsValue(field.columnName()) || partitions.containsKey(field.columnName())
                    || partitions.values().stream().anyMatch(columns -> columns.contains(field.columnName())))
                return encryptionError(owner, "encrypted columns cannot be composite prefixes/sorts or partition keys/sorts, even with a blind index");
            if (protection.blindIndex()) indexed.add(field.columnName());
        }
        return true;
    }

    private FieldModel encryptedTenant(EntityModel model) {
        TypeElement type = processingEnv.getElementUtils().getTypeElement(model.packageName() + "." + model.simpleName());
        for (Element member : type.getEnclosedElements()) {
            if (member.getAnnotation(com.reactor.cachedb.annotations.CacheTenantId.class) == null) continue;
            String name = member.getSimpleName().toString();
            if (member.getKind() == ElementKind.METHOD && name.startsWith("get") && name.length() > 3)
                name = Character.toLowerCase(name.charAt(3)) + name.substring(4);
            for (FieldModel field : model.persistedFields()) if (field.fieldName().equals(name)) return field;
        }
        return null;
    }

    private boolean validateEncryptedCompositeColumns(TypeElement owner, List<FieldModel> fields, Map<String,List<String>> composites) {
        for (FieldModel field : fields) if (field.encryption() != null) {
            if (composites.values().stream().anyMatch(columns -> columns.contains(field.columnName())))
                return encryptionError(owner, "encrypted composite prefixes are forbidden");
            for (var index : owner.getAnnotationsByType(com.reactor.cachedb.annotations.CacheCompositeIndex.class))
                if (index.sortBy().equals(field.fieldName()) || index.sortBy().equals(field.columnName()))
                    return encryptionError(owner, "encrypted composite sort columns are forbidden");
        }
        return true;
    }

    private void encryptedStart(StringBuilder out, String signature) {
        out.append("        public ").append(signature).append(" {\n            requireEncryption();\n            try {\n");
    }

    private void encryptedEnd(StringBuilder out) {
        out.append("            } catch (com.reactor.cachedb.core.crypto.CacheEncryptionException failure) { throw new com.reactor.cachedb.core.crypto.CacheEncryptionException(failure.code()); }\n")
                .append("            catch (RuntimeException failure) { throw com.reactor.cachedb.core.crypto.EncryptedCodecSupport.invalidValue(); }\n        }\n");
    }

    private void encryptedContext(StringBuilder out, EntityModel model, String source) {
        FieldModel id = model.idField(), tenant = encryptedTenant(model);
        if (!source.equals("entity")) {
            String raw = source.equals("redis") ? "value" + model.persistedFields().indexOf(id)
                    : "columnValue(columns, " + javaStringLiteral(id.columnName()) + ")";
            out.append("                ").append(id.typeName()).append(" aadIdValue = ")
                    .append(source.equals("redis") ? fromStringExpression(raw, id) : fromColumnExpression(raw, id)).append(";\n");
        }
        out.append("                String rowId = com.reactor.cachedb.core.crypto.EncryptedCodecSupport.rowId(")
                .append(toStringExpression(source.equals("entity") ? accessExpression(model, id) : "aadIdValue", id)).append(");\n");
        out.append("                String namespace = ");
        if (tenant == null) out.append(javaStringLiteral(model.redisNamespace()));
        else {
            String raw = source.equals("entity") ? accessExpression(model, tenant) : source.equals("redis")
                    ? "value" + model.persistedFields().indexOf(tenant)
                    : "(String) columnValue(columns, " + javaStringLiteral(tenant.columnName()) + ")";
            out.append("com.reactor.cachedb.core.crypto.EncryptedCodecSupport.tenantNamespace(")
                    .append(javaStringLiteral(model.redisNamespace())).append(", ").append(raw).append(")");
        }
        out.append(";\n");
    }

    private String encryptedExpression(EntityModel model, FieldModel field, String raw, boolean decrypt) {
        return "encryption." + (decrypt ? "decrypt" : "encrypt") + "(" + javaStringLiteral(field.encryption().keyRing())
                + ", namespace, rowId, " + javaStringLiteral(field.columnName()) + ", " + raw + ")";
    }

    private void encryptedHydrate(StringBuilder out, EntityModel model, boolean redis, boolean logical) {
        for (int index = 0; index < model.persistedFields().size(); index++) {
            FieldModel field = model.persistedFields().get(index);
            if (field.encryption() == null || !redis && (logical || !field.encryption().scope().equals("REDIS_AND_SOURCE"))) continue;
            String raw = redis ? "value" + index : "com.reactor.cachedb.core.crypto.EncryptedCodecSupport.sourceEnvelope(columnValue(columns, "
                    + javaStringLiteral(field.columnName()) + "))";
            out.append("                String clear").append(index).append(" = ").append(encryptedExpression(model, field, raw, true)).append(";\n");
        }
        if (model.recordType()) out.append("                return new ").append(model.simpleName()).append("(\n");
        else out.append("                ").append(model.simpleName()).append(" entity = new ").append(model.simpleName()).append("();\n");
        for (int index = 0; index < model.persistedFields().size(); index++) {
            FieldModel field = model.persistedFields().get(index);
            String raw = redis ? "value" + index : "columnValue(columns, " + javaStringLiteral(field.columnName()) + ")";
            String expression;
            if (field.encryption() != null && (redis || !logical && field.encryption().scope().equals("REDIS_AND_SOURCE"))) {
                expression = fromStringExpression("clear" + index, field);
            } else expression = redis ? fromStringExpression(raw, field) : fromColumnExpression(raw, field);
            if (model.recordType()) out.append("                    ").append(expression)
                    .append(index == model.persistedFields().size() - 1 ? "\n                );\n" : ",\n");
            else out.append("                entity.").append(field.fieldName()).append(" = ").append(expression).append(";\n");
        }
        if (!model.recordType()) out.append("                return entity;\n");
    }

    private void renderEncryptedCodec(StringBuilder out, EntityModel model) {
        String entity = model.simpleName();
        out.append("    private static final class Codec implements EntityCodec<").append(entity).append("> {\n")
                .append("        private final com.reactor.cachedb.core.crypto.JcaFieldEncryption encryption;\n");
        for (FieldModel field : model.persistedFields()) if (field.codecTypeName() != null)
            out.append("        private final ").append(field.codecTypeName()).append(' ').append(field.fieldName().toUpperCase()).append("_CODEC;\n");
        out.append("        private Codec() { encryption = null;\n");
        for (FieldModel field : model.persistedFields()) if (field.codecTypeName() != null)
            out.append("            ").append(field.fieldName().toUpperCase()).append("_CODEC = null;\n");
        out.append("        }\n        private Codec(com.reactor.cachedb.core.crypto.CacheKeyProvider provider) {\n            try {\n")
                .append("                encryption = com.reactor.cachedb.core.crypto.EncryptedCodecSupport.bind(provider, java.util.Set.of(");
        appendQuotedList(out, model.persistedFields().stream().filter(field -> field.encryption() != null)
                .map(field -> field.encryption().keyRing()).distinct().sorted().toList());
        out.append("));\n");
        for (FieldModel field : model.persistedFields()) if (field.codecTypeName() != null)
            out.append("                ").append(field.fieldName().toUpperCase()).append("_CODEC = new ").append(field.codecTypeName()).append("();\n");
        out.append("            } catch (com.reactor.cachedb.core.crypto.CacheEncryptionException failure) { throw new com.reactor.cachedb.core.crypto.CacheEncryptionException(failure.code()); }\n")
                .append("            catch (RuntimeException failure) { throw com.reactor.cachedb.core.crypto.EncryptedCodecSupport.unbound(); }\n        }\n")
                .append("        private void requireEncryption() { if (encryption == null) throw com.reactor.cachedb.core.crypto.EncryptedCodecSupport.unbound(); }\n")
                .append("        public EntityCodec<").append(entity).append("> bindEncryption(com.reactor.cachedb.core.crypto.CacheKeyProvider provider) { return new Codec(provider); }\n")
                .append("        public boolean encryptionBound() { return encryption != null; }\n")
                .append("        public boolean encryptedMapping() { return true; }\n")
                .append("        public boolean directColumnAccess() { return true; }\n");
        encryptedStart(out, "String toRedisValue(" + entity + " entity)");
        encryptedContext(out, model, "entity");
        out.append("                StringBuilder payload = new StringBuilder();\n");
        for (FieldModel field : model.persistedFields()) {
            String value = toStringExpression(accessExpression(model, field), field);
            if (field.encryption() != null) value = encryptedExpression(model, field, value, false);
            out.append("                LengthPrefixedPayloadCodec.appendField(payload, ")
                    .append(javaStringLiteral(field.columnName().length() + ":" + field.columnName())).append(", ").append(value).append(");\n");
        }
        out.append("                return payload.toString();\n");
        encryptedEnd(out);
        encryptedStart(out, entity + " fromRedisValue(String encoded)");
        for (int i = 0; i < model.persistedFields().size(); i++) out.append("                String value").append(i).append(" = null;\n");
        out.append("                boolean[] seen = new boolean[").append(model.persistedFields().size()).append("];\n")
                .append("                var cursor = LengthPrefixedPayloadCodec.cursor(encoded);\n")
                .append("                while (cursor.next()) {\n                    String column = cursor.name();\n                    if (column == null) continue;\n                    switch (column) {\n");
        for (int i = 0; i < model.persistedFields().size(); i++) {
            out.append("                        case ").append(javaStringLiteral(model.persistedFields().get(i).columnName())).append(" -> {\n")
                    .append("                            if (seen[").append(i).append("]) throw new com.reactor.cachedb.core.crypto.CacheEncryptionException(com.reactor.cachedb.core.crypto.CacheEncryptionException.Code.INVALID_ENVELOPE);\n")
                    .append("                            seen[").append(i).append("] = true; value").append(i).append(" = cursor.value();\n                        }\n");
        }
        out.append("                        default -> { }\n                    }\n                }\n");
        encryptedContext(out, model, "redis");
        encryptedHydrate(out, model, true, false);
        encryptedEnd(out);
        encryptedStart(out, "Map<String, Object> toColumns(" + entity + " entity)");
        encryptedContext(out, model, "entity");
        out.append("                Map<String, Object> columns = new LinkedHashMap<>();\n");
        for (FieldModel field : model.persistedFields()) out.append("                columns.put(")
                .append(javaStringLiteral(field.columnName())).append(", ").append(toColumnValueExpression(model, field)).append(");\n");
        out.append("                return columns;\n");
        encryptedEnd(out);
        encryptedStart(out, "Map<String, Object> toSourceColumns(" + entity + " entity)");
        encryptedContext(out, model, "entity");
        out.append("                Map<String, Object> columns = new LinkedHashMap<>();\n");
        for (FieldModel field : model.persistedFields()) {
            String value = field.encryption() != null && field.encryption().scope().equals("REDIS_AND_SOURCE")
                    ? encryptedExpression(model, field, toStringExpression(accessExpression(model, field), field), false)
                    : toColumnValueExpression(model, field);
            out.append("                columns.put(").append(javaStringLiteral(field.columnName())).append(", ").append(value).append(");\n");
        }
        out.append("                return columns;\n");
        encryptedEnd(out);
        encryptedStart(out, entity + " fromColumns(Map<String, Object> columns)");
        encryptedContext(out, model, "columns");
        encryptedHydrate(out, model, false, false);
        encryptedEnd(out);
        encryptedStart(out, entity + " fromLogicalColumns(Map<String, Object> columns)");
        encryptedContext(out, model, "columns");
        encryptedHydrate(out, model, false, true);
        encryptedEnd(out);
        encryptedStart(out, "Map<String, Object> toIndexColumns(" + entity + " entity)");
        encryptedContext(out, model, "entity");
        out.append("                Map<String, Object> columns = new LinkedHashMap<>();\n");
        for (FieldModel field : model.persistedFields()) {
            if (field.encryption() != null && !field.encryption().blindIndex()) continue;
            String value = field.encryption() == null ? toColumnValueExpression(model, field)
                    : "encryption.blindIndex(" + javaStringLiteral(field.encryption().keyRing()) + ", "
                    + javaStringLiteral(model.redisNamespace()) + ", " + javaStringLiteral(field.columnName())
                    + ", " + toStringExpression(accessExpression(model, field), field) + ")";
            out.append("                columns.put(").append(javaStringLiteral(field.columnName())).append(", ").append(value).append(");\n");
        }
        out.append("                return columns;\n");
        encryptedEnd(out);
        encryptedStart(out, "Object indexQueryValue(String column, Object value)");
        out.append("                return switch (column) {\n");
        for (FieldModel field : model.persistedFields()) if (field.encryption() != null) {
            out.append("                    case ").append(javaStringLiteral(field.columnName())).append(" -> {\n");
            if (!field.encryption().blindIndex()) out.append("                        throw com.reactor.cachedb.core.crypto.EncryptedCodecSupport.unbound();\n");
            else {
                String boxed = boxedTypeName(field.typeName());
                out.append("                        if (").append(boxed.equals(field.typeName()) ? "value != null && " : "value == null || ")
                        .append("!(value instanceof ").append(boxed).append(")) throw com.reactor.cachedb.core.crypto.EncryptedCodecSupport.invalidValue();\n")
                        .append("                        yield encryption.blindIndex(").append(javaStringLiteral(field.encryption().keyRing())).append(", ")
                        .append(javaStringLiteral(model.redisNamespace())).append(", ").append(javaStringLiteral(field.columnName())).append(", ")
                        .append(toStringExpression("((" + boxed + ") value)", field)).append(");\n");
            }
            out.append("                    }\n");
        }
        for (FieldModel field : model.persistedFields()) if (field.encryption() == null)
            out.append("                    case ").append(javaStringLiteral(field.columnName())).append(" -> value;\n");
        out.append("                    default -> throw com.reactor.cachedb.core.crypto.EncryptedCodecSupport.invalidValue();\n                };\n");
        encryptedEnd(out);
        encryptedStart(out, "Object entityColumnValue(" + entity + " entity, String column)");
        out.append("                if (column == null) return null;\n                return switch (column) {\n");
        for (FieldModel field : model.persistedFields()) out.append("                    case ")
                .append(javaStringLiteral(field.columnName())).append(" -> ").append(toColumnValueExpression(model, field)).append(";\n");
        out.append("                    default -> null;\n                };\n");
        encryptedEnd(out);
        encryptedStart(out, "Map<String, Object> normalizePatchColumns(Map<String, Object> updates)");
        out.append("                if (updates.size() > 128) throw com.reactor.cachedb.core.crypto.EncryptedCodecSupport.invalidValue();\n")
                .append("                Map<String, Object> result = new LinkedHashMap<>();\n")
                .append("                for (var entry : updates.entrySet()) {\n                    Object raw = entry.getValue();\n                    Object normalized = switch (entry.getKey()) {\n");
        for (FieldModel field : model.persistedFields()) {
            out.append("                        case ").append(javaStringLiteral(field.columnName())).append(" -> {\n");
            if (!boxedTypeName(field.typeName()).equals(field.typeName()))
                out.append("                            if (raw == null) throw com.reactor.cachedb.core.crypto.EncryptedCodecSupport.invalidValue();\n");
            out.append("                            ").append(field.typeName()).append(" value = ").append(fromColumnExpression("raw", field)).append(";\n")
                    .append("                            yield ").append(logicalLocalColumn(field, "value")).append(";\n                        }\n");
        }
        out.append("                        default -> throw com.reactor.cachedb.core.crypto.EncryptedCodecSupport.invalidValue();\n")
                .append("                    };\n                    result.put(entry.getKey(), normalized);\n                }\n                return result;\n");
        encryptedEnd(out);
        renderEncryptedIntentCodec(out, model);
        renderEncryptedQueueCodec(out, model);
        out.append("    }\n");
    }

    private String logicalLocalColumn(FieldModel field, String value) {
        if (field.codecTypeName() != null) {
            String expression = field.fieldName().toUpperCase() + "_CODEC.toColumnValue(" + value + ")";
            return field.encryption() != null && boxedTypeName(field.typeName()).equals(field.typeName())
                    ? value + " == null ? null : " + expression : expression;
        }
        return field.enumType() ? value + " == null ? null : " + value + ".name()" : value;
    }

    private void renderEncryptedIntentCodec(StringBuilder out, EntityModel model) {
        String ring = model.persistedFields().stream().filter(field -> field.encryption() != null)
                .map(field -> field.encryption().keyRing()).sorted().findFirst().orElseThrow();
        encryptedStart(out, "String encryptionIntentToken(" + model.simpleName() + " entity, java.util.Set<String> selected)");
        encryptedContext(out, model, "entity");
        out.append("                if (selected == null || selected.isEmpty() || !METADATA.columns().containsAll(selected)) throw com.reactor.cachedb.core.crypto.EncryptedCodecSupport.invalidValue();\n")
                .append("                var parts = new java.util.ArrayList<String>();\n")
                .append("                parts.add(namespace); parts.add(rowId);\n");
        for (FieldModel field : model.persistedFields().stream().sorted(java.util.Comparator.comparing(FieldModel::columnName)).toList()) {
            String protection = field.encryption() == null ? "logical" : field.encryption().keyRing() + ":" + field.encryption().scope();
            out.append("                if (selected.contains(").append(javaStringLiteral(field.columnName())).append(")) {\n")
                    .append("                    parts.add(").append(javaStringLiteral(field.columnName())).append(");\n")
                    .append("                    parts.add(").append(javaStringLiteral(field.typeName())).append(");\n")
                    .append("                    parts.add(").append(javaStringLiteral(protection)).append(");\n")
                    .append("                    parts.add(").append(toStringExpression(accessExpression(model, field), field)).append(");\n                }\n");
        }
        out.append("                return encryption.intentMac(").append(javaStringLiteral(ring)).append(", ")
                .append(javaStringLiteral(model.redisNamespace())).append(", \"row\", parts);\n");
        encryptedEnd(out);
        encryptedStart(out, "String encryptionIntentFingerprint(java.util.List<String> parts)");
        out.append("                return encryption.intentMac(").append(javaStringLiteral(ring)).append(", ")
                .append(javaStringLiteral(model.redisNamespace())).append(", \"atomic\", parts);\n");
        encryptedEnd(out);
    }

    private void renderEncryptedQueueCodec(StringBuilder out, EntityModel model) {
        String entity = model.simpleName();
        String support = "com.reactor.cachedb.core.crypto.EncryptedCodecSupport";
        out.append("        public Map<String, Object> toQueuedColumns(").append(entity)
                .append(" entity) { return toQueuedColumns(entity, toColumns(entity)); }\n");
        encryptedStart(out, "Map<String, Object> toQueuedColumns(" + entity + " entity, Map<String, Object> assignments)");
        encryptedContext(out, model, "entity");
        out.append("                if (assignments.isEmpty() || assignments.size() > 128) throw ").append(support).append(".invalidValue();\n")
                .append("                Map<String, Object> queued = new LinkedHashMap<>();\n")
                .append("                for (var entry : assignments.entrySet()) {\n                    Object raw = entry.getValue();\n")
                .append("                    if (raw instanceof com.reactor.cachedb.core.model.PatchDeltaSpec) throw ").append(support).append(".invalidValue();\n")
                .append("                    Object stored = switch (entry.getKey()) {\n");
        for (FieldModel field : model.persistedFields()) {
            out.append("                        case ").append(javaStringLiteral(field.columnName())).append(" -> {\n")
                    .append("                            if (!java.util.Objects.equals(raw, ").append(toColumnValueExpression(model, field))
                    .append(")) throw ").append(support).append(".invalidValue();\n")
                    .append("                            yield ").append(field.encryption() == null ? "raw"
                            : encryptedExpression(model, field, toStringExpression(accessExpression(model, field), field), false)).append(";\n                        }\n");
        }
        out.append("                        default -> throw ").append(support).append(".invalidValue();\n")
                .append("                    };\n                    queued.put(entry.getKey(), stored);\n                }\n")
                .append("                queued.put(").append(support).append(".QUEUE_FORMAT, ").append(support).append(".QUEUE_VERSION);\n");
        FieldModel tenant = encryptedTenant(model);
        if (tenant != null) out.append("                queued.put(").append(support).append(".QUEUE_TENANT, \"~T1:\" + ")
                .append(accessExpression(model, tenant)).append(");\n");
        out.append("                return queued;\n");
        encryptedEnd(out);
        encryptedStart(out, "Map<String, Object> toQueuedPatchColumns(String id, String verifiedTenant, Map<String, Object> assignments)");
        out.append("                if (assignments == null || assignments.isEmpty() || assignments.size() > 128) throw ").append(support).append(".invalidValue();\n")
                .append("                ").append(model.idField().typeName()).append(" aadIdValue = ")
                .append(fromStringExpression("id", model.idField())).append(";\n")
                .append("                String rowId = ").append(support).append(".rowId(")
                .append(toStringExpression("aadIdValue", model.idField())).append(");\n")
                .append("                if (!rowId.equals(id)) throw ").append(support).append(".invalidValue();\n");
        if (tenant == null) out.append("                if (verifiedTenant != null) throw ").append(support).append(".invalidValue();\n")
                .append("                String namespace = ").append(javaStringLiteral(model.redisNamespace())).append(";\n");
        else out.append("                String namespace = ").append(support).append(".tenantNamespace(")
                .append(javaStringLiteral(model.redisNamespace())).append(", verifiedTenant);\n");
        out.append("                Map<String, Object> queued = new LinkedHashMap<>();\n")
                .append("                for (var entry : assignments.entrySet()) {\n                    Object raw = entry.getValue();\n")
                .append("                    if (raw instanceof com.reactor.cachedb.core.model.PatchDeltaSpec) throw ").append(support).append(".invalidValue();\n")
                .append("                    Object stored = switch (entry.getKey()) {\n");
        for (FieldModel field : model.persistedFields()) {
            if (field == model.idField() || field == tenant || field.columnName().equals(model.versionColumn())
                    || field.columnName().equals(model.deletedColumn())) continue;
            out.append("                        case ").append(javaStringLiteral(field.columnName())).append(" -> {\n");
            if (!boxedTypeName(field.typeName()).equals(field.typeName()))
                out.append("                            if (raw == null) throw ").append(support).append(".invalidValue();\n");
            out.append("                            ").append(field.typeName()).append(" value = ").append(fromColumnExpression("raw", field)).append(";\n")
                    .append("                            yield ").append(field.encryption() == null ? logicalLocalColumn(field, "value")
                            : encryptedExpression(model, field, toStringExpression("value", field), false)).append(";\n                        }\n");
        }
        out.append("                        default -> throw ").append(support).append(".invalidValue();\n")
                .append("                    };\n                    queued.put(entry.getKey(), stored);\n                }\n")
                .append("                queued.put(").append(support).append(".QUEUE_FORMAT, ").append(support).append(".QUEUE_VERSION);\n");
        if (tenant != null) out.append("                queued.put(").append(support).append(".QUEUE_TENANT, \"~T1:\" + verifiedTenant);\n");
        out.append("                return queued;\n");
        encryptedEnd(out);
        encryptedStart(out, "Map<String, String> sourceColumnsFromQueued(String id, Map<String, String> queued)");
        out.append("                if (queued.size() > 130 || !").append(support).append(".QUEUE_VERSION.equals(queued.get(")
                .append(support).append(".QUEUE_FORMAT))) throw ").append(support).append(".invalidValue();\n")
                .append("                ").append(model.idField().typeName()).append(" aadIdValue = ")
                .append(fromStringExpression("id", model.idField())).append(";\n")
                .append("                String rowId = ").append(support).append(".rowId(")
                .append(toStringExpression("aadIdValue", model.idField())).append(");\n")
                .append("                if (!rowId.equals(id)) throw ").append(support).append(".invalidValue();\n")
                .append("                if (queued.containsKey(").append(javaStringLiteral(model.idField().columnName()))
                .append(") && !rowId.equals(queued.get(").append(javaStringLiteral(model.idField().columnName()))
                .append("))) throw ").append(support).append(".invalidValue();\n");
        if (tenant == null) out.append("                if (queued.containsKey(").append(support)
                .append(".QUEUE_TENANT)) throw ").append(support).append(".invalidValue();\n")
                .append("                String namespace = ").append(javaStringLiteral(model.redisNamespace())).append(";\n");
        else out.append("                String tenant = ").append(support).append(".queuedTenant(queued.get(")
                .append(support).append(".QUEUE_TENANT));\n")
                .append("                String namespace = ").append(support).append(".tenantNamespace(")
                .append(javaStringLiteral(model.redisNamespace())).append(", tenant);\n")
                .append("                if (queued.containsKey(").append(javaStringLiteral(tenant.columnName()))
                .append(") && !tenant.equals(queued.get(").append(javaStringLiteral(tenant.columnName()))
                .append("))) throw ").append(support).append(".invalidValue();\n");
        out.append("                Map<String, String> source = new LinkedHashMap<>();\n")
                .append("                for (var entry : queued.entrySet()) {\n")
                .append("                    if (entry.getKey().equals(").append(support).append(".QUEUE_FORMAT) || entry.getKey().equals(")
                .append(support).append(".QUEUE_TENANT)) continue;\n")
                .append("                    String raw = entry.getValue();\n                    String stored = switch (entry.getKey()) {\n");
        for (FieldModel field : model.persistedFields()) {
            out.append("                        case ").append(javaStringLiteral(field.columnName())).append(" -> {\n");
            if (field.encryption() == null) out.append("                            yield raw;\n");
            else {
                out.append("                            String clear = ").append(encryptedExpression(model, field, "raw", true)).append(";\n")
                        .append("                            ").append(field.typeName()).append(" value = ").append(fromStringExpression("clear", field)).append(";\n");
                if (field.encryption().scope().equals("REDIS_AND_SOURCE")) out.append("                            yield raw;\n");
                else out.append("                            Object sql = ").append(logicalLocalColumn(field, "value"))
                        .append(";\n                            yield sql == null ? null : String.valueOf(sql);\n");
            }
            out.append("                        }\n");
        }
        if (model.versionColumn() != null && !model.versionColumn().isBlank()
                && model.persistedFields().stream().noneMatch(field -> field.columnName().equals(model.versionColumn())))
            out.append("                        case ").append(javaStringLiteral(model.versionColumn())).append(" -> {\n")
                    .append("                            if (raw == null || !Long.toString(Long.parseLong(raw)).equals(raw) || Long.parseLong(raw) < 1) throw ")
                    .append(support).append(".invalidValue();\n                            yield raw;\n                        }\n");
        if (model.deletedColumn() != null && !model.deletedColumn().isBlank()
                && model.persistedFields().stream().noneMatch(field -> field.columnName().equals(model.deletedColumn())))
            out.append("                        case ").append(javaStringLiteral(model.deletedColumn())).append(" -> {\n")
                    .append("                            if (!").append(javaStringLiteral(model.activeMarkerValue())).append(".equals(raw)) throw ")
                    .append(support).append(".invalidValue();\n                            yield raw;\n                        }\n");
        out.append("                        default -> throw ").append(support).append(".invalidValue();\n")
                .append("                    };\n                    source.put(entry.getKey(), stored);\n                }\n")
                .append("                if (source.isEmpty()) throw ").append(support).append(".invalidValue();\n                return source;\n");
        encryptedEnd(out);
    }

    private void renderCodec(StringBuilder builder, EntityModel model) {
        if (hasEncryption(model)) {
            renderEncryptedCodec(builder, model);
            return;
        }
        if (!model.recordType() && model.persistedFields().size() > 256) {
            renderWideCodec(builder, model);
            return;
        }
        builder.append("    private static final class Codec implements EntityCodec<").append(model.simpleName()).append("> {\n");
        renderColumnAccessor(builder, model);
        builder.append("        @Override\n");
        builder.append("        public String toRedisValue(").append(model.simpleName()).append(" entity) {\n");
        builder.append("            StringBuilder payload = new StringBuilder();\n");
        for (FieldModel field : model.persistedFields()) {
            builder.append("            LengthPrefixedPayloadCodec.appendField(payload, ").append(javaStringLiteral(field.columnName().length() + ":" + field.columnName())).append(", ")
                    .append(toStringExpression(accessExpression(model, field), field)).append(");\n");
        }
        builder.append("            return payload.toString();\n");
        builder.append("        }\n\n");
        builder.append("        @Override\n");
        builder.append("        public ").append(model.simpleName()).append(" fromRedisValue(String encoded) {\n");
        for (int i = 0; i < model.persistedFields().size(); i++) {
            builder.append("            String value").append(i).append(" = null;\n");
        }
        builder.append("            var cursor = LengthPrefixedPayloadCodec.cursor(encoded);\n");
        builder.append("            while (cursor.next()) {\n");
        builder.append("                String column = cursor.name();\n");
        builder.append("                if (column == null) continue;\n");
        builder.append("                switch (column) {\n");
        for (int i = 0; i < model.persistedFields().size(); i++) {
            builder.append("                    case ").append(javaStringLiteral(model.persistedFields().get(i).columnName()))
                    .append(" -> value").append(i).append(" = cursor.value();\n");
        }
        builder.append("                    default -> { }\n");
        builder.append("                }\n");
        builder.append("            }\n");
        if (model.recordType()) {
            builder.append("            return new ").append(model.simpleName()).append("(\n");
            for (int index = 0; index < model.persistedFields().size(); index++) {
                FieldModel field = model.persistedFields().get(index);
                builder.append("                    ")
                        .append(fromStringExpression("value" + index, field))
                        .append(index == model.persistedFields().size() - 1 ? "\n" : ",\n");
            }
            builder.append("            );\n");
        } else {
            builder.append("            ").append(model.simpleName()).append(" entity = new ").append(model.simpleName()).append("();\n");
            for (int index = 0; index < model.persistedFields().size(); index++) {
                FieldModel field = model.persistedFields().get(index);
                builder.append("            entity.").append(field.fieldName()).append(" = ")
                        .append(fromStringExpression("value" + index, field)).append(";\n");
            }
            builder.append("            return entity;\n");
        }
        builder.append("        }\n\n");
        builder.append("        @Override\n");
        builder.append("        public ").append(model.simpleName()).append(" fromColumns(Map<String, Object> columns) {\n");
        if (model.recordType()) {
            builder.append("            return new ").append(model.simpleName()).append("(\n");
            for (int index = 0; index < model.persistedFields().size(); index++) {
                FieldModel field = model.persistedFields().get(index);
                builder.append("                    ")
                        .append(fromColumnExpression("columnValue(columns, \"" + field.columnName() + "\")", field))
                        .append(index == model.persistedFields().size() - 1 ? "\n" : ",\n");
            }
            builder.append("            );\n");
        } else {
            builder.append("            ").append(model.simpleName()).append(" entity = new ").append(model.simpleName()).append("();\n");
            for (FieldModel field : model.persistedFields()) {
                builder.append("            entity.").append(field.fieldName()).append(" = ")
                        .append(fromColumnExpression("columnValue(columns, \"" + field.columnName() + "\")", field)).append(";\n");
            }
            builder.append("            return entity;\n");
        }
        builder.append("        }\n\n");
        builder.append("        @Override\n");
        builder.append("        public Map<String, Object> toColumns(").append(model.simpleName()).append(" entity) {\n");
        builder.append("            LinkedHashMap<String, Object> columns = new LinkedHashMap<>();\n");
        for (FieldModel field : model.persistedFields()) {
            builder.append("            columns.put(").append(javaStringLiteral(field.columnName())).append(", ")
                    .append(toColumnValueExpression(model, field)).append(");\n");
        }
        builder.append("            return columns;\n");
        builder.append("        }\n");
        builder.append("    }\n");
    }

    // Wide schemas need bounded methods: javac limits each method to 64 KiB of bytecode.
    private void renderWideCodec(StringBuilder builder, EntityModel model) {
        int count = model.persistedFields().size();
        int chunks = (count + 127) / 128;
        String entity = model.simpleName();
        builder.append("    private static final class Codec implements EntityCodec<").append(entity).append("> {\n")
                .append("        private static final Map<String, Integer> FIELD_INDEX = fieldIndexes();\n")
                .append("        private static Map<String, Integer> fieldIndexes() {\n")
                .append("            Map<String, Integer> fields = new LinkedHashMap<>();\n");
        for (int chunk = 0; chunk < chunks; chunk++) builder.append("            index").append(chunk).append("(fields);\n");
        builder.append("            return java.util.Collections.unmodifiableMap(fields);\n        }\n")
                .append("        public String toRedisValue(").append(entity).append(" entity) {\n")
                .append("            StringBuilder payload = new StringBuilder();\n");
        for (int chunk = 0; chunk < chunks; chunk++) builder.append("            encode").append(chunk).append("(entity, payload);\n");
        builder.append("            return payload.toString();\n        }\n")
                .append("        public ").append(entity).append(" fromRedisValue(String encoded) {\n")
                .append("            String[] values = new String[").append(count).append("];\n")
                .append("            var cursor = LengthPrefixedPayloadCodec.cursor(encoded);\n")
                .append("            while (cursor.next()) {\n")
                .append("                Integer field = FIELD_INDEX.get(cursor.name());\n")
                .append("                if (field != null) values[field] = cursor.value();\n            }\n")
                .append("            ").append(entity).append(" entity = new ").append(entity).append("();\n");
        for (int chunk = 0; chunk < chunks; chunk++) builder.append("            decode").append(chunk).append("(entity, values);\n");
        builder.append("            return entity;\n        }\n")
                .append("        public ").append(entity).append(" fromColumns(Map<String, Object> columns) {\n")
                .append("            ").append(entity).append(" entity = new ").append(entity).append("();\n");
        for (int chunk = 0; chunk < chunks; chunk++) builder.append("            readColumns").append(chunk).append("(entity, columns);\n");
        builder.append("            return entity;\n        }\n")
                .append("        public Map<String, Object> toColumns(").append(entity).append(" entity) {\n")
                .append("            Map<String, Object> columns = new LinkedHashMap<>();\n");
        for (int chunk = 0; chunk < chunks; chunk++) builder.append("            writeColumns").append(chunk).append("(entity, columns);\n");
        builder.append("            return columns;\n        }\n");
        renderColumnAccessor(builder, model);
        for (int chunk = 0; chunk < chunks; chunk++) {
            int start = chunk * 128;
            int end = Math.min(start + 128, count);
            builder.append("        private static void index").append(chunk).append("(Map<String, Integer> fields) {\n");
            for (int index = start; index < end; index++) builder.append("            fields.put(")
                    .append(javaStringLiteral(model.persistedFields().get(index).columnName())).append(", ").append(index).append(");\n");
            builder.append("        }\n")
                    .append("        private static void encode").append(chunk).append("(").append(entity).append(" entity, StringBuilder payload) {\n");
            for (FieldModel field : model.persistedFields().subList(start, end)) builder.append("            LengthPrefixedPayloadCodec.appendField(payload, ")
                    .append(javaStringLiteral(field.columnName().length() + ":" + field.columnName())).append(", ")
                    .append(toStringExpression(accessExpression(model, field), field)).append(");\n");
            builder.append("        }\n")
                    .append("        private static void decode").append(chunk).append("(").append(entity).append(" entity, String[] values) {\n");
            for (int index = start; index < end; index++) {
                FieldModel field = model.persistedFields().get(index);
                builder.append("            entity.").append(field.fieldName()).append(" = ")
                        .append(fromStringExpression("values[" + index + "]", field)).append(";\n");
            }
            builder.append("        }\n")
                    .append("        private void readColumns").append(chunk).append("(").append(entity).append(" entity, Map<String, Object> columns) {\n");
            for (FieldModel field : model.persistedFields().subList(start, end)) builder.append("            entity.")
                    .append(field.fieldName()).append(" = ")
                    .append(fromColumnExpression("columnValue(columns, " + javaStringLiteral(field.columnName()) + ")", field)).append(";\n");
            builder.append("        }\n")
                    .append("        private static void writeColumns").append(chunk).append("(").append(entity).append(" entity, Map<String, Object> columns) {\n");
            for (FieldModel field : model.persistedFields().subList(start, end)) builder.append("            columns.put(")
                    .append(javaStringLiteral(field.columnName())).append(", ").append(toColumnValueExpression(model, field)).append(");\n");
            builder.append("        }\n");
        }
        builder.append("    }\n");
    }

    private void renderLoaderFactories(StringBuilder builder, EntityModel model) {
        renderRelationLoaderFactory(builder, model);
        if (hasRelationLoader(model) && model.pageLoader() != null) {
            builder.append('\n');
        }
        renderPageLoaderFactory(builder, model);
    }

    private void renderRelationLoaderFactory(StringBuilder builder, EntityModel model) {
        if (!hasRelationLoader(model)) {
            return;
        }
        builder.append("    public static RelationBatchLoader<").append(model.simpleName())
                .append("> relationLoader(CacheDatabase cacheDatabase) {\n");
        builder.append("        return relationLoader(cacheDatabase, cacheDatabase.config().resourceLimits().defaultCachePolicy());\n");
        builder.append("    }\n\n");
        builder.append("    public static RelationBatchLoader<").append(model.simpleName())
                .append("> relationLoader(CacheDatabase cacheDatabase, CachePolicy cachePolicy) {\n");
        List<RelationModel> generatedRelations = model.relations().stream()
                .filter(RelationModel::generatedLoader)
                .toList();
        int loaderCount = generatedRelations.size() + (model.relationLoader() == null ? 0 : 1);
        if (loaderCount == 1) {
            builder.append("        return ");
            if (model.relationLoader() != null) {
                appendCustomRelationLoader(builder, model);
            } else {
                appendGeneratedRelationLoader(builder, model, generatedRelations.get(0));
            }
            builder.append(";\n");
        } else {
            builder.append("        return new CompositeRelationBatchLoader<>(List.of(\n");
            int index = 0;
            if (model.relationLoader() != null) {
                builder.append("                ");
                appendCustomRelationLoader(builder, model);
                index++;
                builder.append(index == loaderCount ? "\n" : ",\n");
            }
            for (RelationModel relation : generatedRelations) {
                builder.append("                ");
                appendGeneratedRelationLoader(builder, model, relation);
                index++;
                builder.append(index == loaderCount ? "\n" : ",\n");
            }
            builder.append("        ));\n");
        }
        builder.append("    }\n");
    }

    private boolean hasRelationLoader(EntityModel model) {
        return model.relationLoader() != null || model.relations().stream().anyMatch(RelationModel::generatedLoader);
    }

    private void appendCustomRelationLoader(StringBuilder builder, EntityModel model) {
        builder.append("new ").append(model.relationLoader().typeName()).append("(");
        appendLoaderConstructorArguments(builder, model.relationLoader().constructor());
        builder.append(")");
    }

    private void appendGeneratedRelationLoader(StringBuilder builder, EntityModel model, RelationModel relation) {
        builder.append("new PartitionedRelationBatchLoader<>(")
                .append(javaStringLiteral(relation.name())).append(", ")
                .append(relation.maxRowsPerParent()).append(", ")
                .append(relation.parentBatchSize()).append(", ")
                .append(relation.targetBindingTypeName()).append(".repository(cacheDatabase), ")
                .append("parent -> parent.").append(model.idField().fieldName()).append(", ")
                .append("(parent, children) -> parent.").append(relation.name()).append(" = children, ")
                .append(javaStringLiteral(relation.partitionColumn()));
        for (RelationSortModel sort : relation.sorts()) {
            builder.append(", QuerySort.").append(sort.descending() ? "desc" : "asc")
                    .append("(").append(javaStringLiteral(sort.columnName())).append(")");
        }
        builder.append(")");
    }

    private void renderPageLoaderFactory(StringBuilder builder, EntityModel model) {
        if (model.pageLoader() == null) {
            return;
        }
        builder.append("    public static EntityPageLoader<").append(model.simpleName())
                .append("> pageLoader(CacheDatabase cacheDatabase) {\n");
        builder.append("        return pageLoader(cacheDatabase, cacheDatabase.config().resourceLimits().defaultCachePolicy());\n");
        builder.append("    }\n\n");
        builder.append("    public static EntityPageLoader<").append(model.simpleName())
                .append("> pageLoader(CacheDatabase cacheDatabase, CachePolicy cachePolicy) {\n");
        builder.append("        return new ").append(model.pageLoader().typeName()).append("(");
        appendLoaderConstructorArguments(builder, model.pageLoader().constructor());
        builder.append(");\n");
        builder.append("    }\n");
    }

    private void appendLoaderConstructorArguments(StringBuilder builder, LoaderConstructorModel constructor) {
        for (int index = 0; index < constructor.dependencies().size(); index++) {
            if (index > 0) {
                builder.append(", ");
            }
            LoaderDependencyModel dependency = constructor.dependencies().get(index);
            builder.append(switch (dependency.kind()) {
                case CACHE_DATABASE, CACHE_SESSION -> "cacheDatabase";
                case CACHE_POLICY -> "cachePolicy";
                case ENTITY_REPOSITORY -> dependency.bindingTypeName() + ".repository(cacheDatabase)";
            });
        }
    }

    private void renderProjectionHelpers(StringBuilder builder, EntityModel model) {
        for (int index = 0; index < model.projections().size(); index++) {
            ProjectionModel projection = model.projections().get(index);
            builder.append("    public static ").append(projection.entityProjectionTypeName()).append(" ")
                    .append(projection.accessorName()).append("Projection() {\n");
            builder.append("        return ").append(model.simpleName()).append(".")
                    .append(projection.factoryMethodName()).append("();\n");
            builder.append("    }\n\n");
            builder.append("    public static ProjectionRepository<").append(projection.projectionTypeName()).append(", ")
                    .append(model.idField().typeName()).append("> ")
                    .append(projection.accessorName()).append("(CacheSession session) {\n");
            builder.append("        return repository(session).projected(")
                    .append(projection.accessorName()).append("Projection());\n");
            builder.append("    }\n\n");
            builder.append("    public static ProjectionRepository<").append(projection.projectionTypeName()).append(", ")
                    .append(model.idField().typeName()).append("> ")
                    .append(projection.accessorName()).append("(CacheSession session, CachePolicy cachePolicy) {\n");
            builder.append("        return repository(session, cachePolicy).projected(")
                    .append(projection.accessorName()).append("Projection());\n");
            builder.append("    }\n\n");
            builder.append("    public static ProjectionRepository<").append(projection.projectionTypeName()).append(", ")
                    .append(model.idField().typeName()).append("> ")
                    .append(projection.accessorName()).append("(EntityRepository<").append(model.simpleName()).append(", ")
                    .append(model.idField().typeName()).append("> repository) {\n");
            builder.append("        return repository.projected(").append(projection.accessorName()).append("Projection());\n");
            builder.append("    }\n");
            if (index < model.projections().size() - 1) {
                builder.append('\n');
            }
        }
    }

    private void renderNamedQueryHelpers(StringBuilder builder, EntityModel model) {
        for (int index = 0; index < model.namedQueries().size(); index++) {
            NamedQueryModel namedQuery = model.namedQueries().get(index);
            String queryParameters = renderMethodParameters(namedQuery.parameters());
            String queryArguments = renderInvocationArguments(namedQuery.parameters());
            String sessionParameterName = uniqueHelperParameterName(namedQuery.parameters(), "cacheSession");
            String cachePolicyParameterName = uniqueHelperParameterName(namedQuery.parameters(), "cachePolicy");
            String entityRepositoryParameterName = uniqueHelperParameterName(namedQuery.parameters(), "entityRepository");
            String projectionRepositoryParameterName = uniqueHelperParameterName(namedQuery.parameters(), "projectionRepository");

            builder.append("    public static QuerySpec ").append(namedQuery.accessorName()).append("Query(")
                    .append(queryParameters).append(") {\n");
            builder.append("        return ").append(model.simpleName()).append(".")
                    .append(namedQuery.factoryMethodName()).append("(").append(queryArguments).append(");\n");
            builder.append("    }\n\n");
            builder.append("    public static List<").append(model.simpleName()).append("> ")
                    .append(namedQuery.accessorName()).append("(CacheSession ").append(sessionParameterName);
            appendForwardedParameters(builder, namedQuery.parameters());
            builder.append(") {\n");
            builder.append("        return repository(").append(sessionParameterName).append(").query(")
                    .append(namedQuery.accessorName()).append("Query(").append(queryArguments).append("));\n");
            builder.append("    }\n\n");
            builder.append("    public static List<").append(model.simpleName()).append("> ")
                    .append(namedQuery.accessorName()).append("(CacheSession ").append(sessionParameterName)
                    .append(", CachePolicy ").append(cachePolicyParameterName);
            appendForwardedParameters(builder, namedQuery.parameters());
            builder.append(") {\n");
            builder.append("        return repository(").append(sessionParameterName).append(", ")
                    .append(cachePolicyParameterName).append(").query(")
                    .append(namedQuery.accessorName()).append("Query(").append(queryArguments).append("));\n");
            builder.append("    }\n\n");
            builder.append("    public static List<").append(model.simpleName()).append("> ")
                    .append(namedQuery.accessorName()).append("(EntityRepository<").append(model.simpleName()).append(", ")
                    .append(model.idField().typeName()).append("> ").append(entityRepositoryParameterName);
            appendForwardedParameters(builder, namedQuery.parameters());
            builder.append(") {\n");
            builder.append("        return ").append(entityRepositoryParameterName).append(".query(")
                    .append(namedQuery.accessorName()).append("Query(").append(queryArguments).append("));\n");
            builder.append("    }\n\n");
            builder.append("    public static <P> List<P> ").append(namedQuery.accessorName())
                    .append("(ProjectionRepository<P, ").append(model.idField().typeName()).append("> ")
                    .append(projectionRepositoryParameterName);
            appendForwardedParameters(builder, namedQuery.parameters());
            builder.append(") {\n");
            builder.append("        return ").append(projectionRepositoryParameterName).append(".query(")
                    .append(namedQuery.accessorName()).append("Query(").append(queryArguments).append("));\n");
            builder.append("    }\n");
            if (index < model.namedQueries().size() - 1) {
                builder.append('\n');
            }
        }
    }

    private void renderFetchPresetHelpers(StringBuilder builder, EntityModel model) {
        for (int index = 0; index < model.fetchPresets().size(); index++) {
            FetchPresetModel fetchPreset = model.fetchPresets().get(index);
            String presetParameters = renderMethodParameters(fetchPreset.parameters());
            String presetArguments = renderInvocationArguments(fetchPreset.parameters());
            String sessionParameterName = uniqueHelperParameterName(fetchPreset.parameters(), "cacheSession");
            String cachePolicyParameterName = uniqueHelperParameterName(fetchPreset.parameters(), "cachePolicy");
            String entityRepositoryParameterName = uniqueHelperParameterName(fetchPreset.parameters(), "entityRepository");

            builder.append("    public static FetchPlan ").append(fetchPreset.accessorName()).append("FetchPlan(")
                    .append(presetParameters).append(") {\n");
            builder.append("        return ").append(model.simpleName()).append(".")
                    .append(fetchPreset.factoryMethodName()).append("(").append(presetArguments).append(");\n");
            builder.append("    }\n\n");
            builder.append("    public static EntityRepository<").append(model.simpleName()).append(", ")
                    .append(model.idField().typeName()).append("> ").append(fetchPreset.accessorName())
                    .append("Repository(CacheSession ").append(sessionParameterName);
            appendForwardedParameters(builder, fetchPreset.parameters());
            builder.append(") {\n");
            builder.append("        return repository(").append(sessionParameterName).append(").withFetchPlan(")
                    .append(fetchPreset.accessorName()).append("FetchPlan(").append(presetArguments).append("));\n");
            builder.append("    }\n\n");
            builder.append("    public static EntityRepository<").append(model.simpleName()).append(", ")
                    .append(model.idField().typeName()).append("> ").append(fetchPreset.accessorName())
                    .append("Repository(CacheSession ").append(sessionParameterName)
                    .append(", CachePolicy ").append(cachePolicyParameterName);
            appendForwardedParameters(builder, fetchPreset.parameters());
            builder.append(") {\n");
            builder.append("        return repository(").append(sessionParameterName).append(", ")
                    .append(cachePolicyParameterName).append(").withFetchPlan(")
                    .append(fetchPreset.accessorName()).append("FetchPlan(").append(presetArguments).append("));\n");
            builder.append("    }\n\n");
            builder.append("    public static EntityRepository<").append(model.simpleName()).append(", ")
                    .append(model.idField().typeName()).append("> ").append(fetchPreset.accessorName())
                    .append("Repository(EntityRepository<").append(model.simpleName()).append(", ")
                    .append(model.idField().typeName()).append("> ").append(entityRepositoryParameterName);
            appendForwardedParameters(builder, fetchPreset.parameters());
            builder.append(") {\n");
            builder.append("        return ").append(entityRepositoryParameterName).append(".withFetchPlan(")
                    .append(fetchPreset.accessorName()).append("FetchPlan(").append(presetArguments).append("));\n");
            builder.append("    }\n");
            if (index < model.fetchPresets().size() - 1) {
                builder.append('\n');
            }
        }
    }

    private void renderPagePresetHelpers(StringBuilder builder, EntityModel model) {
        for (int index = 0; index < model.pagePresets().size(); index++) {
            PagePresetModel pagePreset = model.pagePresets().get(index);
            String presetParameters = renderMethodParameters(pagePreset.parameters());
            String presetArguments = renderInvocationArguments(pagePreset.parameters());
            String sessionParameterName = uniqueHelperParameterName(pagePreset.parameters(), "cacheSession");
            String cachePolicyParameterName = uniqueHelperParameterName(pagePreset.parameters(), "cachePolicy");
            String entityRepositoryParameterName = uniqueHelperParameterName(pagePreset.parameters(), "entityRepository");

            builder.append("    public static PageWindow ").append(pagePreset.accessorName()).append("Window(")
                    .append(presetParameters).append(") {\n");
            builder.append("        return ").append(model.simpleName()).append(".")
                    .append(pagePreset.factoryMethodName()).append("(").append(presetArguments).append(");\n");
            builder.append("    }\n\n");
            builder.append("    public static List<").append(model.simpleName()).append("> ")
                    .append(pagePreset.accessorName()).append("(CacheSession ").append(sessionParameterName);
            appendForwardedParameters(builder, pagePreset.parameters());
            builder.append(") {\n");
            builder.append("        return repository(").append(sessionParameterName).append(").findPage(")
                    .append(pagePreset.accessorName()).append("Window(").append(presetArguments).append("));\n");
            builder.append("    }\n\n");
            builder.append("    public static List<").append(model.simpleName()).append("> ")
                    .append(pagePreset.accessorName()).append("(CacheSession ").append(sessionParameterName)
                    .append(", CachePolicy ").append(cachePolicyParameterName);
            appendForwardedParameters(builder, pagePreset.parameters());
            builder.append(") {\n");
            builder.append("        return repository(").append(sessionParameterName).append(", ")
                    .append(cachePolicyParameterName).append(").findPage(")
                    .append(pagePreset.accessorName()).append("Window(").append(presetArguments).append("));\n");
            builder.append("    }\n\n");
            builder.append("    public static List<").append(model.simpleName()).append("> ")
                    .append(pagePreset.accessorName()).append("(EntityRepository<").append(model.simpleName()).append(", ")
                    .append(model.idField().typeName()).append("> ").append(entityRepositoryParameterName);
            appendForwardedParameters(builder, pagePreset.parameters());
            builder.append(") {\n");
            builder.append("        return ").append(entityRepositoryParameterName).append(".findPage(")
                    .append(pagePreset.accessorName()).append("Window(").append(presetArguments).append("));\n");
            builder.append("    }\n");
            if (index < model.pagePresets().size() - 1) {
                builder.append('\n');
            }
        }
    }

    private void renderSaveCommandHelpers(StringBuilder builder, EntityModel model) {
        for (int index = 0; index < model.saveCommands().size(); index++) {
            SaveCommandModel saveCommand = model.saveCommands().get(index);
            String commandParameters = renderMethodParameters(saveCommand.parameters());
            String commandArguments = renderInvocationArguments(saveCommand.parameters());
            String sessionParameterName = uniqueHelperParameterName(saveCommand.parameters(), "cacheSession");
            String cachePolicyParameterName = uniqueHelperParameterName(saveCommand.parameters(), "cachePolicy");
            String entityRepositoryParameterName = uniqueHelperParameterName(saveCommand.parameters(), "entityRepository");

            builder.append("    public static ").append(model.simpleName()).append(" ")
                    .append(saveCommand.accessorName()).append("Command(").append(commandParameters).append(") {\n");
            builder.append("        return ").append(model.simpleName()).append(".")
                    .append(saveCommand.factoryMethodName()).append("(").append(commandArguments).append(");\n");
            builder.append("    }\n\n");
            builder.append("    public static ").append(model.simpleName()).append(" ").append(saveCommand.accessorName())
                    .append("(CacheSession ").append(sessionParameterName);
            appendForwardedParameters(builder, saveCommand.parameters());
            builder.append(") {\n");
            builder.append("        return repository(").append(sessionParameterName).append(").save(")
                    .append(saveCommand.accessorName()).append("Command(").append(commandArguments).append("));\n");
            builder.append("    }\n\n");
            builder.append("    public static ").append(model.simpleName()).append(" ").append(saveCommand.accessorName())
                    .append("(CacheSession ").append(sessionParameterName).append(", CachePolicy ")
                    .append(cachePolicyParameterName);
            appendForwardedParameters(builder, saveCommand.parameters());
            builder.append(") {\n");
            builder.append("        return repository(").append(sessionParameterName).append(", ")
                    .append(cachePolicyParameterName).append(").save(")
                    .append(saveCommand.accessorName()).append("Command(").append(commandArguments).append("));\n");
            builder.append("    }\n\n");
            builder.append("    public static ").append(model.simpleName()).append(" ").append(saveCommand.accessorName())
                    .append("(EntityRepository<").append(model.simpleName()).append(", ")
                    .append(model.idField().typeName()).append("> ").append(entityRepositoryParameterName);
            appendForwardedParameters(builder, saveCommand.parameters());
            builder.append(") {\n");
            builder.append("        return ").append(entityRepositoryParameterName).append(".save(")
                    .append(saveCommand.accessorName()).append("Command(").append(commandArguments).append("));\n");
            builder.append("    }\n");
            if (index < model.saveCommands().size() - 1) {
                builder.append('\n');
            }
        }
    }

    private void renderDeleteCommandHelpers(StringBuilder builder, EntityModel model) {
        for (int index = 0; index < model.deleteCommands().size(); index++) {
            DeleteCommandModel deleteCommand = model.deleteCommands().get(index);
            String commandParameters = renderMethodParameters(deleteCommand.parameters());
            String commandArguments = renderInvocationArguments(deleteCommand.parameters());
            String sessionParameterName = uniqueHelperParameterName(deleteCommand.parameters(), "cacheSession");
            String cachePolicyParameterName = uniqueHelperParameterName(deleteCommand.parameters(), "cachePolicy");
            String entityRepositoryParameterName = uniqueHelperParameterName(deleteCommand.parameters(), "entityRepository");

            builder.append("    public static ").append(model.idField().typeName()).append(" ")
                    .append(deleteCommand.accessorName()).append("Id(").append(commandParameters).append(") {\n");
            builder.append("        return ").append(model.simpleName()).append(".")
                    .append(deleteCommand.factoryMethodName()).append("(").append(commandArguments).append(");\n");
            builder.append("    }\n\n");
            builder.append("    public static void ").append(deleteCommand.accessorName())
                    .append("(CacheSession ").append(sessionParameterName);
            appendForwardedParameters(builder, deleteCommand.parameters());
            builder.append(") {\n");
            builder.append("        repository(").append(sessionParameterName).append(").deleteById(")
                    .append(deleteCommand.accessorName()).append("Id(").append(commandArguments).append("));\n");
            builder.append("    }\n\n");
            builder.append("    public static void ").append(deleteCommand.accessorName())
                    .append("(CacheSession ").append(sessionParameterName).append(", CachePolicy ")
                    .append(cachePolicyParameterName);
            appendForwardedParameters(builder, deleteCommand.parameters());
            builder.append(") {\n");
            builder.append("        repository(").append(sessionParameterName).append(", ")
                    .append(cachePolicyParameterName).append(").deleteById(")
                    .append(deleteCommand.accessorName()).append("Id(").append(commandArguments).append("));\n");
            builder.append("    }\n\n");
            builder.append("    public static void ").append(deleteCommand.accessorName())
                    .append("(EntityRepository<").append(model.simpleName()).append(", ")
                    .append(model.idField().typeName()).append("> ").append(entityRepositoryParameterName);
            appendForwardedParameters(builder, deleteCommand.parameters());
            builder.append(") {\n");
            builder.append("        ").append(entityRepositoryParameterName).append(".deleteById(")
                    .append(deleteCommand.accessorName()).append("Id(").append(commandArguments).append("));\n");
            builder.append("    }\n");
            if (index < model.deleteCommands().size() - 1) {
                builder.append('\n');
            }
        }
    }

    private String renderMethodParameters(List<MethodParameterModel> parameters) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < parameters.size(); index++) {
            MethodParameterModel parameter = parameters.get(index);
            if (index > 0) {
                builder.append(", ");
            }
            builder.append(parameter.typeName()).append(' ').append(parameter.parameterName());
        }
        return builder.toString();
    }

    private void appendForwardedParameters(StringBuilder builder, List<MethodParameterModel> parameters) {
        for (MethodParameterModel parameter : parameters) {
            builder.append(", ").append(parameter.typeName()).append(' ').append(parameter.parameterName());
        }
    }

    private String renderInvocationArguments(List<MethodParameterModel> parameters) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < parameters.size(); index++) {
            if (index > 0) {
                builder.append(", ");
            }
            builder.append(parameters.get(index).parameterName());
        }
        return builder.toString();
    }

    private String uniqueHelperParameterName(List<MethodParameterModel> parameters, String baseName) {
        String candidate = baseName;
        int suffix = 2;
        while (containsParameterName(parameters, candidate)) {
            candidate = baseName + suffix;
            suffix++;
        }
        return candidate;
    }

    private boolean containsParameterName(List<MethodParameterModel> parameters, String candidate) {
        for (MethodParameterModel parameter : parameters) {
            if (parameter.parameterName().equals(candidate)) {
                return true;
            }
        }
        return false;
    }

    private void renderActiveFacade(StringBuilder builder, EntityModel model) {
        builder.append("    public static EntityRepository<").append(model.simpleName()).append(", ").append(model.idField().typeName())
                .append("> repository(CacheSession session) {\n");
        builder.append("        return session.repository(METADATA, CODEC);\n");
        builder.append("    }\n\n");
        builder.append("    public static EntityRepository<").append(model.simpleName()).append(", ").append(model.idField().typeName())
                .append("> repository(CacheSession session, CachePolicy cachePolicy) {\n");
        builder.append("        return session.repository(METADATA, CODEC, cachePolicy);\n");
        builder.append("    }\n\n");
        builder.append("    public static void register(CacheDatabase cacheDatabase) {\n");
        if (hasRelationLoader(model) || model.pageLoader() != null) {
            builder.append("        register(cacheDatabase, cacheDatabase.config().resourceLimits().defaultCachePolicy());\n");
        } else {
            builder.append("        cacheDatabase.register(METADATA, CODEC);\n");
            renderProjectionRegistrationStatements(builder, model);
        }
        builder.append("    }\n\n");
        builder.append("    public static void register(CacheDatabase cacheDatabase, CachePolicy cachePolicy) {\n");
        if (hasRelationLoader(model) || model.pageLoader() != null) {
            builder.append("        cacheDatabase.register(METADATA, CODEC, cachePolicy, ")
                    .append(hasRelationLoader(model) ? "relationLoader(cacheDatabase, cachePolicy)" : "null")
                    .append(", ")
                    .append(model.pageLoader() == null ? "null" : "pageLoader(cacheDatabase, cachePolicy)")
                    .append(");\n");
        } else {
            builder.append("        cacheDatabase.register(METADATA, CODEC, cachePolicy);\n");
        }
        renderProjectionRegistrationStatements(builder, model);
        builder.append("    }\n\n");
        builder.append("    public static void registerJdbcBacked(CacheDatabase cacheDatabase) {\n");
        builder.append("        registerJdbcBacked(cacheDatabase, cacheDatabase.config().resourceLimits().defaultCachePolicy());\n");
        builder.append("    }\n\n");
        builder.append("    public static void registerJdbcBacked(CacheDatabase cacheDatabase, CachePolicy cachePolicy) {\n");
        builder.append("        cacheDatabase.registerJdbcBacked(METADATA, CODEC, cachePolicy, ")
                .append(hasRelationLoader(model) ? "relationLoader(cacheDatabase, cachePolicy)" : "null")
                .append(", ")
                .append(model.pageLoader() == null ? "null" : "pageLoader(cacheDatabase, cachePolicy)")
                .append(");\n");
        renderProjectionRegistrationStatements(builder, model);
        builder.append("    }\n\n");
        builder.append("    public static void registerJdbcSource(CacheDatabase cacheDatabase, CachePolicy cachePolicy) {\n");
        builder.append("        cacheDatabase.registerJdbcBacked(METADATA, CODEC, cachePolicy, null, null);\n");
        renderProjectionRegistrationStatements(builder, model);
        builder.append("    }\n\n");
        builder.append("    public static void registerDeclaredLoaders(CacheDatabase cacheDatabase, CachePolicy cachePolicy) {\n");
        if (hasRelationLoader(model) || model.pageLoader() != null) {
            builder.append("        cacheDatabase.register(METADATA, CODEC, cachePolicy, ")
                    .append(hasRelationLoader(model) ? "relationLoader(cacheDatabase, cachePolicy)" : "null")
                    .append(", ")
                    .append(model.pageLoader() == null ? "null" : "pageLoader(cacheDatabase, cachePolicy)")
                    .append(");\n");
        }
        builder.append("    }\n\n");
        builder.append("    public static void registerJdbcBacked(CacheDatabase cacheDatabase, RelationBatchLoader<")
                .append(model.simpleName()).append("> relationBatchLoader) {\n");
        builder.append("        cacheDatabase.registerJdbcBacked(METADATA, CODEC, cacheDatabase.config().resourceLimits().defaultCachePolicy(), relationBatchLoader, null);\n");
        renderProjectionRegistrationStatements(builder, model);
        builder.append("    }\n\n");
        builder.append("    public static void registerJdbcBacked(CacheDatabase cacheDatabase, CachePolicy cachePolicy, RelationBatchLoader<")
                .append(model.simpleName()).append("> relationBatchLoader) {\n");
        builder.append("        cacheDatabase.registerJdbcBacked(METADATA, CODEC, cachePolicy, relationBatchLoader, null);\n");
        renderProjectionRegistrationStatements(builder, model);
        builder.append("    }\n\n");
        builder.append("    public static void registerJdbcBacked(CacheDatabase cacheDatabase, RelationBatchLoader<")
                .append(model.simpleName()).append("> relationBatchLoader, EntityPageLoader<").append(model.simpleName())
                .append("> pageLoader) {\n");
        builder.append("        cacheDatabase.registerJdbcBacked(METADATA, CODEC, cacheDatabase.config().resourceLimits().defaultCachePolicy(), relationBatchLoader, pageLoader);\n");
        renderProjectionRegistrationStatements(builder, model);
        builder.append("    }\n\n");
        builder.append("    public static void registerJdbcBacked(CacheDatabase cacheDatabase, CachePolicy cachePolicy, RelationBatchLoader<")
                .append(model.simpleName()).append("> relationBatchLoader, EntityPageLoader<").append(model.simpleName())
                .append("> pageLoader) {\n");
        builder.append("        cacheDatabase.registerJdbcBacked(METADATA, CODEC, cachePolicy, relationBatchLoader, pageLoader);\n");
        renderProjectionRegistrationStatements(builder, model);
        builder.append("    }\n\n");
        builder.append("    public static void register(CacheDatabase cacheDatabase, RelationBatchLoader<")
                .append(model.simpleName()).append("> relationBatchLoader) {\n");
        builder.append("        cacheDatabase.register(METADATA, CODEC, cacheDatabase.config().resourceLimits().defaultCachePolicy(), relationBatchLoader, null);\n");
        renderProjectionRegistrationStatements(builder, model);
        builder.append("    }\n\n");
        builder.append("    public static void register(CacheDatabase cacheDatabase, CachePolicy cachePolicy, RelationBatchLoader<")
                .append(model.simpleName()).append("> relationBatchLoader) {\n");
        builder.append("        cacheDatabase.register(METADATA, CODEC, cachePolicy, relationBatchLoader, null);\n");
        renderProjectionRegistrationStatements(builder, model);
        builder.append("    }\n\n");
        builder.append("    public static void register(CacheDatabase cacheDatabase, RelationBatchLoader<")
                .append(model.simpleName()).append("> relationBatchLoader, EntityPageLoader<").append(model.simpleName())
                .append("> pageLoader) {\n");
        builder.append("        cacheDatabase.register(METADATA, CODEC, cacheDatabase.config().resourceLimits().defaultCachePolicy(), relationBatchLoader, pageLoader);\n");
        renderProjectionRegistrationStatements(builder, model);
        builder.append("    }\n\n");
        builder.append("    public static void register(CacheDatabase cacheDatabase, CachePolicy cachePolicy, RelationBatchLoader<")
                .append(model.simpleName()).append("> relationBatchLoader, EntityPageLoader<").append(model.simpleName())
                .append("> pageLoader) {\n");
        builder.append("        cacheDatabase.register(METADATA, CODEC, cachePolicy, relationBatchLoader, pageLoader);\n");
        renderProjectionRegistrationStatements(builder, model);
        builder.append("    }\n\n");
        builder.append("    public static CacheWarmPlan warmPlan(String name, QuerySpec querySpec, int maxRows) {\n");
        builder.append("        return CacheWarmPlan.builder(METADATA.entityName())\n");
        builder.append("                .name(name)\n");
        builder.append("                .querySpec(querySpec)\n");
        builder.append("                .maxRows(maxRows)\n");
        builder.append("                .build();\n");
        builder.append("    }\n\n");
        builder.append("    public static ActiveRecordInstance<").append(model.simpleName()).append(", ").append(model.idField().typeName())
                .append("> attach(CacheSession session, ").append(model.simpleName()).append(" entity) {\n");
        builder.append("        return new ActiveRecordInstance<>(entity, METADATA.idAccessor().apply(entity), repository(session));\n");
        builder.append("    }\n\n");
        builder.append("    public static ActiveRecordInstance<").append(model.simpleName()).append(", ").append(model.idField().typeName())
                .append("> attach(CacheSession session, CachePolicy cachePolicy, ").append(model.simpleName()).append(" entity) {\n");
        builder.append("        return new ActiveRecordInstance<>(entity, METADATA.idAccessor().apply(entity), repository(session, cachePolicy));\n");
        builder.append("    }\n\n");
        builder.append("    public static ").append(model.simpleName()).append(" save(CacheSession session, ")
                .append(model.simpleName()).append(" entity) {\n");
        builder.append("        return repository(session).save(entity);\n");
        builder.append("    }\n\n");
        builder.append("    public static ").append(model.simpleName()).append(" save(CacheSession session, CachePolicy cachePolicy, ")
                .append(model.simpleName()).append(" entity) {\n");
        builder.append("        return repository(session, cachePolicy).save(entity);\n");
        builder.append("    }\n\n");
        builder.append("    public static Optional<").append(model.simpleName()).append("> findById(CacheSession session, ")
                .append(model.idField().typeName()).append(" id) {\n");
        builder.append("        return repository(session).findById(id);\n");
        builder.append("    }\n\n");
        builder.append("    public static Optional<").append(model.simpleName()).append("> findById(CacheSession session, CachePolicy cachePolicy, ")
                .append(model.idField().typeName()).append(" id) {\n");
        builder.append("        return repository(session, cachePolicy).findById(id);\n");
        builder.append("    }\n\n");
        builder.append("    public static List<").append(model.simpleName()).append("> findPage(CacheSession session, PageWindow pageWindow) {\n");
        builder.append("        return repository(session).findPage(pageWindow);\n");
        builder.append("    }\n\n");
        builder.append("    public static List<").append(model.simpleName()).append("> findPage(CacheSession session, CachePolicy cachePolicy, PageWindow pageWindow) {\n");
        builder.append("        return repository(session, cachePolicy).findPage(pageWindow);\n");
        builder.append("    }\n\n");
        builder.append("    public static List<").append(model.simpleName()).append("> query(CacheSession session, QuerySpec querySpec) {\n");
        builder.append("        return repository(session).query(querySpec);\n");
        builder.append("    }\n\n");
        builder.append("    public static List<").append(model.simpleName()).append("> query(CacheSession session, CachePolicy cachePolicy, QuerySpec querySpec) {\n");
        builder.append("        return repository(session, cachePolicy).query(querySpec);\n");
        builder.append("    }\n\n");
        builder.append("    public static void deleteById(CacheSession session, ")
                .append(model.idField().typeName()).append(" id) {\n");
        builder.append("        repository(session).deleteById(id);\n");
        builder.append("    }\n");
        builder.append("\n");
        builder.append("    public static void deleteById(CacheSession session, CachePolicy cachePolicy, ")
                .append(model.idField().typeName()).append(" id) {\n");
        builder.append("        repository(session, cachePolicy).deleteById(id);\n");
        builder.append("    }\n");
    }

    private void renderProjectionRegistrationStatements(StringBuilder builder, EntityModel model) {
        for (ProjectionModel projection : model.projections()) {
            builder.append("        cacheDatabase.registerProjection(METADATA, ")
                    .append(projection.accessorName())
                    .append("Projection());\n");
        }
    }

    private void renderUseCaseGroups(StringBuilder builder, EntityModel model) {
        builder.append("    public static Scope using(CacheSession session) {\n");
        builder.append("        return new Scope(session, null);\n");
        builder.append("    }\n\n");
        builder.append("    public static Scope using(CacheSession session, CachePolicy cachePolicy) {\n");
        builder.append("        return new Scope(session, cachePolicy);\n");
        builder.append("    }\n\n");
        renderScopeClass(builder, model);
        if (!model.namedQueries().isEmpty()) {
            builder.append('\n');
            renderQueryGroup(builder, model);
        }
        if (!model.projections().isEmpty()) {
            builder.append('\n');
            renderProjectionGroup(builder, model);
        }
        if (!model.fetchPresets().isEmpty()) {
            builder.append('\n');
            renderFetchGroup(builder, model);
        }
        if (!model.pagePresets().isEmpty()) {
            builder.append('\n');
            renderPageGroup(builder, model);
        }
        if (!model.saveCommands().isEmpty()) {
            builder.append('\n');
            renderCommandGroup(builder, model);
        }
        if (!model.deleteCommands().isEmpty()) {
            builder.append('\n');
            renderDeleteGroup(builder, model);
        }
    }

    private void renderScopeClass(StringBuilder builder, EntityModel model) {
        builder.append("    public static final class Scope {\n");
        builder.append("        private final CacheSession cacheSession;\n");
        builder.append("        private final EntityRepository<").append(model.simpleName()).append(", ")
                .append(model.idField().typeName()).append("> repository;\n");
        builder.append("        private volatile SourceRepository<").append(model.simpleName()).append(", ")
                .append(model.idField().typeName()).append("> sourceRepository;\n");
        if (!model.namedQueries().isEmpty()) {
            builder.append("        private final Queries queries;\n");
        }
        if (!model.projections().isEmpty()) {
            builder.append("        private final Projections projections;\n");
        }
        if (!model.fetchPresets().isEmpty()) {
            builder.append("        private final Fetches fetches;\n");
        }
        if (!model.pagePresets().isEmpty()) {
            builder.append("        private final Pages pages;\n");
        }
        if (!model.saveCommands().isEmpty()) {
            builder.append("        private final Commands commands;\n");
        }
        if (!model.deleteCommands().isEmpty()) {
            builder.append("        private final Deletes deletes;\n");
        }
        builder.append('\n');
        builder.append("        private Scope(CacheSession cacheSession, CachePolicy cachePolicy) {\n");
        builder.append("            this.cacheSession = cacheSession;\n");
        builder.append("            this.repository = cachePolicy == null ? ").append(model.bindingName())
                .append(".repository(cacheSession) : ").append(model.bindingName())
                .append(".repository(cacheSession, cachePolicy);\n");
        if (!model.namedQueries().isEmpty()) {
            builder.append("            this.queries = new Queries(this);\n");
        }
        if (!model.projections().isEmpty()) {
            builder.append("            this.projections = new Projections(this);\n");
        }
        if (!model.fetchPresets().isEmpty()) {
            builder.append("            this.fetches = new Fetches(this);\n");
        }
        if (!model.pagePresets().isEmpty()) {
            builder.append("            this.pages = new Pages(this);\n");
        }
        if (!model.saveCommands().isEmpty()) {
            builder.append("            this.commands = new Commands(this);\n");
        }
        if (!model.deleteCommands().isEmpty()) {
            builder.append("            this.deletes = new Deletes(this);\n");
        }
        builder.append("        }\n\n");
        builder.append("        public EntityRepository<").append(model.simpleName()).append(", ")
                .append(model.idField().typeName()).append("> repository() {\n");
        builder.append("            return repository;\n");
        builder.append("        }\n\n");
        builder.append("        public ActiveRecordInstance<").append(model.simpleName()).append(", ")
                .append(model.idField().typeName()).append("> attach(").append(model.simpleName())
                .append(" entity) {\n");
        builder.append("            return new ActiveRecordInstance<>(entity, METADATA.idAccessor().apply(entity), repository);\n");
        builder.append("        }\n\n");
        builder.append("        public ").append(model.simpleName()).append(" save(").append(model.simpleName())
                .append(" entity) {\n");
        builder.append("            return repository.save(entity);\n");
        builder.append("        }\n\n");
        builder.append("        public WriteReceipt<").append(model.simpleName()).append(", ")
                .append(model.idField().typeName()).append("> saveWithReceipt(")
                .append(model.simpleName()).append(" entity) {\n");
        builder.append("            return repository.saveWithReceipt(entity);\n");
        builder.append("        }\n\n");
        builder.append("        public WriteReceipt<").append(model.simpleName()).append(", ")
                .append(model.idField().typeName()).append("> save(")
                .append(model.simpleName()).append(" entity, long expectedVersion) {\n");
        builder.append("            return repository.save(entity, expectedVersion);\n");
        builder.append("        }\n\n");
        builder.append("        public WriteReceipt<").append(model.simpleName()).append(", ")
                .append(model.idField().typeName()).append("> saveAfter(")
                .append(model.simpleName()).append(" entity, WriteDependency dependency) {\n");
        builder.append("            return repository.saveAfter(entity, dependency);\n");
        builder.append("        }\n\n");
        builder.append("        public List<WriteReceipt<").append(model.simpleName()).append(", ")
                .append(model.idField().typeName()).append(">> saveAll(java.util.Collection<")
                .append(model.simpleName()).append("> entities) {\n");
        builder.append("            return repository.saveAll(entities);\n");
        builder.append("        }\n\n");
        builder.append("        public Optional<").append(model.simpleName()).append("> findById(")
                .append(model.idField().typeName()).append(" id) {\n");
        builder.append("            return repository.findById(id);\n");
        builder.append("        }\n\n");
        builder.append("        public Optional<VersionedEntity<").append(model.simpleName())
                .append(">> findVersionedById(").append(model.idField().typeName()).append(" id) {\n");
        builder.append("            return repository.findVersionedById(id);\n");
        builder.append("        }\n\n");
        builder.append("        public Optional<WriteDependency> dependency(")
                .append(model.idField().typeName()).append(" id) {\n");
        builder.append("            return repository.findVersionedById(id).map(versioned -> ")
                .append("new WriteDependency(METADATA.redisNamespace(), String.valueOf(id), versioned.version()));\n");
        builder.append("        }\n\n");
        builder.append("        public List<").append(model.simpleName()).append("> findPage(PageWindow pageWindow) {\n");
        builder.append("            return repository.findPage(pageWindow);\n");
        builder.append("        }\n\n");
        builder.append("        public List<").append(model.simpleName()).append("> query(QuerySpec querySpec) {\n");
        builder.append("            return repository.query(querySpec);\n");
        builder.append("        }\n\n");
        builder.append("        public <K> Map<K, List<").append(model.simpleName())
                .append(">> queryPartitions(PartitionedQuerySpec<K> querySpec) {\n");
        builder.append("            return repository.queryPartitions(querySpec);\n");
        builder.append("        }\n\n");
        builder.append("        public SourceRepository<").append(model.simpleName()).append(", ")
                .append(model.idField().typeName()).append("> source() {\n");
        builder.append("            SourceRepository<").append(model.simpleName()).append(", ")
                .append(model.idField().typeName()).append("> resolved = sourceRepository;\n");
        builder.append("            if (resolved == null) {\n");
        builder.append("                synchronized (this) {\n");
        builder.append("                    resolved = sourceRepository;\n");
        builder.append("                    if (resolved == null) {\n");
        builder.append("                        resolved = cacheSession.sourceRepository(METADATA, cacheSession.runtimeCodec(METADATA, CODEC));\n");
        builder.append("                        sourceRepository = resolved;\n");
        builder.append("                    }\n");
        builder.append("                }\n");
        builder.append("            }\n");
        builder.append("            return resolved;\n");
        builder.append("        }\n\n");
        builder.append("        public void deleteById(").append(model.idField().typeName()).append(" id) {\n");
        builder.append("            repository.deleteById(id);\n");
        builder.append("        }\n\n");
        builder.append("        public CacheWarmPlan warmPlan(String name, QuerySpec querySpec, int maxRows) {\n");
        builder.append("            return ").append(model.bindingName()).append(".warmPlan(name, querySpec, maxRows);\n");
        builder.append("        }\n");
        if (!model.namedQueries().isEmpty()) {
            builder.append('\n');
            builder.append("        public Queries queries() {\n");
            builder.append("            return queries;\n");
            builder.append("        }\n");
        }
        if (!model.projections().isEmpty()) {
            builder.append('\n');
            builder.append("        public Projections projections() {\n");
            builder.append("            return projections;\n");
            builder.append("        }\n");
        }
        if (!model.fetchPresets().isEmpty()) {
            builder.append('\n');
            builder.append("        public Fetches fetches() {\n");
            builder.append("            return fetches;\n");
            builder.append("        }\n");
        }
        if (!model.pagePresets().isEmpty()) {
            builder.append('\n');
            builder.append("        public Pages pages() {\n");
            builder.append("            return pages;\n");
            builder.append("        }\n");
        }
        if (!model.saveCommands().isEmpty()) {
            builder.append('\n');
            builder.append("        public Commands commands() {\n");
            builder.append("            return commands;\n");
            builder.append("        }\n");
        }
        if (!model.deleteCommands().isEmpty()) {
            builder.append('\n');
            builder.append("        public Deletes deletes() {\n");
            builder.append("            return deletes;\n");
            builder.append("        }\n");
        }
        builder.append("    }\n");
    }

    private void renderQueryGroup(StringBuilder builder, EntityModel model) {
        builder.append("    public static final class Queries {\n");
        builder.append("        private final Scope scope;\n\n");
        builder.append("        private Queries(Scope scope) {\n");
        builder.append("            this.scope = scope;\n");
        builder.append("        }\n\n");
        for (int index = 0; index < model.namedQueries().size(); index++) {
            NamedQueryModel namedQuery = model.namedQueries().get(index);
            String parameters = renderMethodParameters(namedQuery.parameters());
            String arguments = renderInvocationArguments(namedQuery.parameters());

            builder.append("        public QuerySpec ").append(namedQuery.accessorName()).append("Query(")
                    .append(parameters).append(") {\n");
            builder.append("            return ").append(model.bindingName()).append(".")
                    .append(namedQuery.accessorName()).append("Query(").append(arguments).append(");\n");
            builder.append("        }\n\n");
            builder.append("        public List<").append(model.simpleName()).append("> ")
                    .append(namedQuery.accessorName()).append("(").append(parameters).append(") {\n");
            if (namedQuery.route() == null) {
                builder.append("            return scope.repository().query(").append(namedQuery.accessorName()).append("Query(")
                        .append(arguments).append("));\n");
            } else {
                builder.append("            return RouteCacheContext.supplyWithContract(")
                        .append(namedQuery.accessorName()).append("Route(), () -> scope.repository().query(")
                        .append(namedQuery.accessorName()).append("Query(").append(arguments).append(")));\n");
            }
            builder.append("        }\n");
            if (namedQuery.route() != null) {
                builder.append("\n");
                renderGeneratedRoute(builder, model, namedQuery, parameters, arguments);
            }
            if (index < model.namedQueries().size() - 1) {
                builder.append('\n');
            }
        }
        builder.append("    }\n");
    }

    private void renderGeneratedRoute(
            StringBuilder builder,
            EntityModel model,
            NamedQueryModel namedQuery,
            String parameters,
            String arguments
    ) {
        RouteModel route = namedQuery.route();
        builder.append("        public RouteCacheContract ").append(namedQuery.accessorName()).append("Route() {\n");
        builder.append("            return RouteCacheContract.builder()\n");
        builder.append("                    .routeName(").append(javaStringLiteral(route.routeName())).append(")\n");
        builder.append("                    .entityName(METADATA.entityName())\n");
        if (!route.projectionAccessor().isBlank()) {
            builder.append("                    .projectionName(scope.projections().")
                    .append(route.projectionAccessor()).append("Projection().name())\n");
            builder.append("                    .projectionRequired(true)\n");
        }
        builder.append("                    .pageSize(").append(route.pageSize()).append(")\n");
        builder.append("                    .hotWindow(").append(route.hotWindow()).append(")\n");
        builder.append("                    .maxColdReadSize(").append(route.maxColdReadSize()).append(")\n");
        builder.append("                    .memoryBudgetBytes(").append(route.memoryBudgetBytes()).append("L)\n");
        builder.append("                    .strictMode(RouteCacheStrictMode.")
                .append(route.strict() ? "FAIL_FAST" : "WARN").append(")\n");
        builder.append("                    .build();\n");
        builder.append("        }\n");

        builder.append("\n");
        builder.append("        public List<").append(model.simpleName()).append("> ")
                .append(namedQuery.accessorName()).append("Source(").append(parameters).append(") {\n");
        builder.append("            return RouteCacheContext.supplyWithContract(")
                .append(namedQuery.accessorName()).append("Route(), () -> scope.source().query(")
                .append(namedQuery.accessorName()).append("Query(").append(arguments).append(")));\n");
        builder.append("        }\n");

        List<MethodParameterModel> warmParameters = namedQuery.parameters().stream()
                .filter(parameter -> !parameter.parameterName().equals(route.limitParameter()))
                .toList();
        String requestedWarmRows = uniqueHelperParameterName(warmParameters, "warmRows");
        String warmParameterArguments = renderInvocationArguments(warmParameters);
        String warmArguments = renderWarmInvocationArguments(namedQuery, requestedWarmRows);
        builder.append("\n");
        builder.append("        public CacheWarmPlan ").append(namedQuery.accessorName()).append("WarmPlan(")
                .append(renderMethodParameters(warmParameters)).append(") {\n");
        builder.append("            return ").append(namedQuery.accessorName()).append("WarmPlan(")
                .append(warmParameterArguments);
        if (!warmParameterArguments.isEmpty()) {
            builder.append(", ");
        }
        builder.append(route.hotWindow()).append(");\n");
        builder.append("        }\n\n");
        builder.append("        public CacheWarmPlan ").append(namedQuery.accessorName()).append("WarmPlan(")
                .append(renderMethodParameters(warmParameters));
        if (!warmParameters.isEmpty()) {
            builder.append(", ");
        }
        builder.append("int ").append(requestedWarmRows).append(") {\n");
        builder.append("            if (").append(requestedWarmRows).append(" <= 0 || ")
                .append(requestedWarmRows).append(" > ").append(route.hotWindow()).append(") {\n");
        builder.append("                throw new IllegalArgumentException(")
                .append(javaStringLiteral("warmRows must be between 1 and " + route.hotWindow()))
                .append(");\n");
        builder.append("            }\n");
        builder.append("            return ").append(model.bindingName()).append(".warmPlan(")
                .append(javaStringLiteral(route.routeName())).append(", ")
                .append(namedQuery.accessorName()).append("Query(").append(warmArguments)
                .append(").limitTo(").append(requestedWarmRows).append("), ")
                .append(requestedWarmRows).append(");\n");
        builder.append("        }\n");

        if (!route.projectionAccessor().isBlank()) {
            ProjectionModel projection = model.projections().stream()
                    .filter(candidate -> candidate.accessorName().equals(route.projectionAccessor()))
                    .findFirst()
                    .orElseThrow();
            builder.append("\n");
            builder.append("        public List<").append(projection.projectionTypeName()).append("> ")
                    .append(namedQuery.accessorName()).append("Projection(").append(parameters).append(") {\n");
            builder.append("            return RouteCacheContext.supplyWithContract(")
                    .append(namedQuery.accessorName()).append("Route(), () -> scope.projections().")
                    .append(route.projectionAccessor()).append("().query(")
                    .append(namedQuery.accessorName()).append("Query(").append(arguments).append(")));\n");
            builder.append("        }\n");
        }
    }

    private String renderWarmInvocationArguments(NamedQueryModel namedQuery, String limitExpression) {
        StringBuilder arguments = new StringBuilder();
        for (int index = 0; index < namedQuery.parameters().size(); index++) {
            if (index > 0) {
                arguments.append(", ");
            }
            MethodParameterModel parameter = namedQuery.parameters().get(index);
            if (parameter.parameterName().equals(namedQuery.route().limitParameter())) {
                arguments.append(limitExpression);
            } else {
                arguments.append(parameter.parameterName());
            }
        }
        return arguments.toString();
    }

    private void renderProjectionGroup(StringBuilder builder, EntityModel model) {
        builder.append("    public static final class Projections {\n");
        builder.append("        private final Scope scope;\n");
        for (ProjectionModel projection : model.projections()) {
            builder.append("        private volatile ProjectionRepository<").append(projection.projectionTypeName()).append(", ")
                    .append(model.idField().typeName()).append("> ").append(projection.accessorName()).append(";\n");
        }
        builder.append('\n');
        builder.append("        private Projections(Scope scope) {\n");
        builder.append("            this.scope = scope;\n");
        builder.append("        }\n\n");
        for (int index = 0; index < model.projections().size(); index++) {
            ProjectionModel projection = model.projections().get(index);
            builder.append("        public ").append(projection.entityProjectionTypeName()).append(" ")
                    .append(projection.accessorName()).append("Projection() {\n");
            builder.append("            return ").append(model.bindingName()).append(".")
                    .append(projection.accessorName()).append("Projection();\n");
            builder.append("        }\n\n");
            builder.append("        public ProjectionRepository<").append(projection.projectionTypeName()).append(", ")
                    .append(model.idField().typeName()).append("> ").append(projection.accessorName())
                    .append("() {\n");
            builder.append("            ProjectionRepository<").append(projection.projectionTypeName()).append(", ")
                    .append(model.idField().typeName()).append("> resolved = ").append(projection.accessorName()).append(";\n");
            builder.append("            if (resolved == null) {\n");
            builder.append("                synchronized (this) {\n");
            builder.append("                    resolved = ").append(projection.accessorName()).append(";\n");
            builder.append("                    if (resolved == null) {\n");
            builder.append("                        resolved = scope.repository().projected(")
                    .append(projection.accessorName()).append("Projection());\n");
            builder.append("                        ").append(projection.accessorName()).append(" = resolved;\n");
            builder.append("                    }\n");
            builder.append("                }\n");
            builder.append("            }\n");
            builder.append("            return resolved;\n");
            builder.append("        }\n");
            if (index < model.projections().size() - 1) {
                builder.append('\n');
            }
        }
        builder.append("    }\n");
    }

    private void renderFetchGroup(StringBuilder builder, EntityModel model) {
        builder.append("    public static final class Fetches {\n");
        builder.append("        private final Scope scope;\n\n");
        builder.append("        private Fetches(Scope scope) {\n");
        builder.append("            this.scope = scope;\n");
        builder.append("        }\n\n");
        for (int index = 0; index < model.fetchPresets().size(); index++) {
            FetchPresetModel fetchPreset = model.fetchPresets().get(index);
            String parameters = renderMethodParameters(fetchPreset.parameters());
            String arguments = renderInvocationArguments(fetchPreset.parameters());

            builder.append("        public FetchPlan ").append(fetchPreset.accessorName()).append("FetchPlan(")
                    .append(parameters).append(") {\n");
            builder.append("            return ").append(model.bindingName()).append(".")
                    .append(fetchPreset.accessorName()).append("FetchPlan(").append(arguments).append(");\n");
            builder.append("        }\n\n");
            builder.append("        public EntityRepository<").append(model.simpleName()).append(", ")
                    .append(model.idField().typeName()).append("> ").append(fetchPreset.accessorName())
                    .append("(").append(parameters).append(") {\n");
            builder.append("            return scope.repository().withFetchPlan(").append(fetchPreset.accessorName())
                    .append("FetchPlan(").append(arguments).append("));\n");
            builder.append("        }\n");
            if (index < model.fetchPresets().size() - 1) {
                builder.append('\n');
            }
        }
        builder.append("    }\n");
    }

    private void renderPageGroup(StringBuilder builder, EntityModel model) {
        builder.append("    public static final class Pages {\n");
        builder.append("        private final Scope scope;\n\n");
        builder.append("        private Pages(Scope scope) {\n");
        builder.append("            this.scope = scope;\n");
        builder.append("        }\n\n");
        for (int index = 0; index < model.pagePresets().size(); index++) {
            PagePresetModel pagePreset = model.pagePresets().get(index);
            String parameters = renderMethodParameters(pagePreset.parameters());
            String arguments = renderInvocationArguments(pagePreset.parameters());

            builder.append("        public PageWindow ").append(pagePreset.accessorName()).append("Window(")
                    .append(parameters).append(") {\n");
            builder.append("            return ").append(model.bindingName()).append(".")
                    .append(pagePreset.accessorName()).append("Window(").append(arguments).append(");\n");
            builder.append("        }\n\n");
            builder.append("        public List<").append(model.simpleName()).append("> ").append(pagePreset.accessorName())
                    .append("(").append(parameters).append(") {\n");
            builder.append("            return scope.repository().findPage(").append(pagePreset.accessorName())
                    .append("Window(").append(arguments).append("));\n");
            builder.append("        }\n");
            if (index < model.pagePresets().size() - 1) {
                builder.append('\n');
            }
        }
        builder.append("    }\n");
    }

    private void renderCommandGroup(StringBuilder builder, EntityModel model) {
        builder.append("    public static final class Commands {\n");
        builder.append("        private final Scope scope;\n\n");
        builder.append("        private Commands(Scope scope) {\n");
        builder.append("            this.scope = scope;\n");
        builder.append("        }\n\n");
        for (int index = 0; index < model.saveCommands().size(); index++) {
            SaveCommandModel saveCommand = model.saveCommands().get(index);
            String parameters = renderMethodParameters(saveCommand.parameters());
            String arguments = renderInvocationArguments(saveCommand.parameters());

            builder.append("        public ").append(model.simpleName()).append(" ")
                    .append(saveCommand.accessorName()).append("Command(").append(parameters).append(") {\n");
            builder.append("            return ").append(model.bindingName()).append(".")
                    .append(saveCommand.accessorName()).append("Command(").append(arguments).append(");\n");
            builder.append("        }\n\n");
            builder.append("        public ").append(model.simpleName()).append(" ").append(saveCommand.accessorName())
                    .append("(").append(parameters).append(") {\n");
            builder.append("            return scope.repository().save(").append(saveCommand.accessorName())
                    .append("Command(").append(arguments).append("));\n");
            builder.append("        }\n");
            if (index < model.saveCommands().size() - 1) {
                builder.append('\n');
            }
        }
        builder.append("    }\n");
    }

    private void renderDeleteGroup(StringBuilder builder, EntityModel model) {
        builder.append("    public static final class Deletes {\n");
        builder.append("        private final Scope scope;\n\n");
        builder.append("        private Deletes(Scope scope) {\n");
        builder.append("            this.scope = scope;\n");
        builder.append("        }\n\n");
        for (int index = 0; index < model.deleteCommands().size(); index++) {
            DeleteCommandModel deleteCommand = model.deleteCommands().get(index);
            String parameters = renderMethodParameters(deleteCommand.parameters());
            String arguments = renderInvocationArguments(deleteCommand.parameters());

            builder.append("        public ").append(model.idField().typeName()).append(" ")
                    .append(deleteCommand.accessorName()).append("Id(").append(parameters).append(") {\n");
            builder.append("            return ").append(model.bindingName()).append(".")
                    .append(deleteCommand.accessorName()).append("Id(").append(arguments).append(");\n");
            builder.append("        }\n\n");
            builder.append("        public void ").append(deleteCommand.accessorName()).append("(")
                    .append(parameters).append(") {\n");
            builder.append("            scope.repository().deleteById(").append(deleteCommand.accessorName())
                    .append("Id(").append(arguments).append("));\n");
            builder.append("        }\n");
            if (index < model.deleteCommands().size() - 1) {
                builder.append('\n');
            }
        }
        builder.append("    }\n");
    }

    private void appendInvocationArguments(StringBuilder builder, List<MethodParameterModel> parameters) {
        for (MethodParameterModel parameter : parameters) {
            builder.append(", ").append(parameter.parameterName());
        }
    }

    private void appendQuotedList(StringBuilder builder, List<String> values) {
        for (int index = 0; index < values.size(); index++) {
            builder.append(javaStringLiteral(values.get(index)));
            if (index < values.size() - 1) {
                builder.append(", ");
            }
        }
    }

    private String javaStringLiteral(String value) {
        String safe = value == null ? "" : value;
        StringBuilder builder = new StringBuilder(safe.length() + 2);
        builder.append('"');
        for (int index = 0; index < safe.length(); index++) {
            char character = safe.charAt(index);
            switch (character) {
                case '\\' -> builder.append("\\\\");
                case '"' -> builder.append("\\\"");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> builder.append(character);
            }
        }
        builder.append('"');
        return builder.toString();
    }

    private String toStringExpression(String accessor, FieldModel field) {
        if (field.codecTypeName() != null) {
            if (field.encryption() != null && boxedTypeName(field.typeName()).equals(field.typeName()))
                return accessor + " == null ? null : " + field.fieldName().toUpperCase() + "_CODEC.encode(" + accessor + ")";
            return field.fieldName().toUpperCase() + "_CODEC.encode(" + accessor + ")";
        }
        if (field.enumType()) {
            return accessor + " == null ? null : " + accessor + ".name()";
        }
        return switch (field.typeName()) {
            case "java.lang.String" -> accessor;
             case "java.lang.Integer", "java.lang.Long", "java.lang.Boolean", "java.lang.Double",
                     "java.math.BigDecimal",
                     "java.util.UUID",
                     "java.lang.Float", "java.lang.Short", "java.lang.Byte",
                     "java.time.Instant", "java.time.LocalDate", "java.time.LocalTime",
                     "java.time.LocalDateTime", "java.time.OffsetDateTime" ->
                     accessor + " == null ? null : String.valueOf(" + accessor + ")";
            default -> "String.valueOf(" + accessor + ")";
        };
    }

    private String fromStringExpression(String source, FieldModel field) {
        if (field.codecTypeName() != null) {
            if (field.encryption() != null && boxedTypeName(field.typeName()).equals(field.typeName()))
                return source + " == null ? null : " + field.fieldName().toUpperCase() + "_CODEC.decode(" + source + ")";
            return field.fieldName().toUpperCase() + "_CODEC.decode(" + source + ")";
        }
        if (field.enumType()) {
            return source + " == null ? null : " + field.typeName() + ".valueOf(" + source + ")";
        }
        return switch (field.typeName()) {
            case "java.lang.String" -> source;
            case "int" -> source + " == null ? 0 : Integer.parseInt(" + source + ")";
            case "java.lang.Integer" -> source + " == null ? null : Integer.valueOf(" + source + ")";
            case "long" -> source + " == null ? 0L : Long.parseLong(" + source + ")";
            case "java.lang.Long" -> source + " == null ? null : Long.valueOf(" + source + ")";
            case "boolean" -> source + " != null && Boolean.parseBoolean(" + source + ")";
            case "java.lang.Boolean" -> source + " == null ? null : Boolean.valueOf(" + source + ")";
            case "double" -> source + " == null ? 0D : Double.parseDouble(" + source + ")";
            case "java.lang.Double" -> source + " == null ? null : Double.valueOf(" + source + ")";
             case "java.math.BigDecimal" -> source + " == null ? null : new java.math.BigDecimal(" + source + ")";
             case "java.util.UUID" -> source + " == null ? null : java.util.UUID.fromString(" + source + ")";
            case "float" -> source + " == null ? 0F : Float.parseFloat(" + source + ")";
            case "java.lang.Float" -> source + " == null ? null : Float.valueOf(" + source + ")";
            case "short" -> source + " == null ? (short) 0 : Short.parseShort(" + source + ")";
            case "java.lang.Short" -> source + " == null ? null : Short.valueOf(" + source + ")";
            case "byte" -> source + " == null ? (byte) 0 : Byte.parseByte(" + source + ")";
            case "java.lang.Byte" -> source + " == null ? null : Byte.valueOf(" + source + ")";
            case "java.time.Instant" -> source + " == null ? null : java.time.Instant.parse(" + source + ")";
             case "java.time.LocalDate" -> source + " == null ? null : java.time.LocalDate.parse(" + source + ")";
             case "java.time.LocalTime" -> source + " == null ? null : java.time.LocalTime.parse(" + source + ")";
            case "java.time.LocalDateTime" -> source + " == null ? null : java.time.LocalDateTime.parse(" + source + ")";
            case "java.time.OffsetDateTime" -> source + " == null ? null : java.time.OffsetDateTime.parse(" + source + ")";
            default -> throw new IllegalArgumentException("Unsupported type: " + field.typeName());
        };
    }

    private String fromColumnExpression(String source, FieldModel field) {
        if (field.codecTypeName() != null) {
            return source + " == null ? null : " + field.fieldName().toUpperCase() + "_CODEC.decode(String.valueOf(" + source + "))";
        }
        if (field.enumType()) {
            return source + " == null ? null : " + field.typeName() + ".valueOf(String.valueOf(" + source + "))";
        }
        return switch (field.typeName()) {
            case "java.lang.String" -> source + " == null ? null : String.valueOf(" + source + ")";
            case "int" -> source + " instanceof Number number ? number.intValue() : (" + source + " == null ? 0 : Integer.parseInt(String.valueOf(" + source + ")))";
            case "java.lang.Integer" -> source + " instanceof Number number ? Integer.valueOf(number.intValue()) : (" + source + " == null ? null : Integer.valueOf(String.valueOf(" + source + ")))";
            case "long" -> source + " instanceof Number number ? number.longValue() : (" + source + " == null ? 0L : Long.parseLong(String.valueOf(" + source + ")))";
            case "java.lang.Long" -> source + " instanceof Number number ? Long.valueOf(number.longValue()) : (" + source + " == null ? null : Long.valueOf(String.valueOf(" + source + ")))";
            case "boolean" -> source + " instanceof Boolean bool ? bool : (" + source + " != null && Boolean.parseBoolean(String.valueOf(" + source + ")))";
            case "java.lang.Boolean" -> source + " instanceof Boolean bool ? bool : (" + source + " == null ? null : Boolean.valueOf(String.valueOf(" + source + ")))";
            case "double" -> source + " instanceof Number number ? number.doubleValue() : (" + source + " == null ? 0D : Double.parseDouble(String.valueOf(" + source + ")))";
            case "java.lang.Double" -> source + " instanceof Number number ? Double.valueOf(number.doubleValue()) : (" + source + " == null ? null : Double.valueOf(String.valueOf(" + source + ")))";
             case "java.math.BigDecimal" -> source + " == null ? null : new java.math.BigDecimal(String.valueOf(" + source + "))";
             case "java.util.UUID" -> source + " instanceof java.util.UUID uuid ? uuid : (" + source + " == null ? null : java.util.UUID.fromString(String.valueOf(" + source + ")))";
            case "float" -> source + " instanceof Number number ? number.floatValue() : (" + source + " == null ? 0F : Float.parseFloat(String.valueOf(" + source + ")))";
            case "java.lang.Float" -> source + " instanceof Number number ? Float.valueOf(number.floatValue()) : (" + source + " == null ? null : Float.valueOf(String.valueOf(" + source + ")))";
            case "short" -> source + " instanceof Number number ? number.shortValue() : (" + source + " == null ? (short) 0 : Short.parseShort(String.valueOf(" + source + ")))";
            case "java.lang.Short" -> source + " instanceof Number number ? Short.valueOf(number.shortValue()) : (" + source + " == null ? null : Short.valueOf(String.valueOf(" + source + ")))";
            case "byte" -> source + " instanceof Number number ? number.byteValue() : (" + source + " == null ? (byte) 0 : Byte.parseByte(String.valueOf(" + source + ")))";
            case "java.lang.Byte" -> source + " instanceof Number number ? Byte.valueOf(number.byteValue()) : (" + source + " == null ? null : Byte.valueOf(String.valueOf(" + source + ")))";
            case "java.time.Instant" -> source + " instanceof java.time.Instant instant ? instant : (" + source + " instanceof java.sql.Timestamp timestamp ? timestamp.toInstant() : (" + source + " instanceof java.time.OffsetDateTime offsetDateTime ? offsetDateTime.toInstant() : (" + source + " == null ? null : java.time.Instant.parse(String.valueOf(" + source + ")))))";
             case "java.time.LocalDate" -> source + " instanceof java.time.LocalDate localDate ? localDate : (" + source + " instanceof java.sql.Date sqlDate ? sqlDate.toLocalDate() : (" + source + " instanceof java.sql.Timestamp timestamp ? timestamp.toLocalDateTime().toLocalDate() : (" + source + " == null ? null : java.time.LocalDate.parse(String.valueOf(" + source + ")))))";
             case "java.time.LocalTime" -> source + " instanceof java.time.LocalTime localTime ? localTime : (" + source + " instanceof java.sql.Time sqlTime ? sqlTime.toLocalTime() : (" + source + " instanceof java.sql.Timestamp timestamp ? timestamp.toLocalDateTime().toLocalTime() : (" + source + " == null ? null : java.time.LocalTime.parse(String.valueOf(" + source + ")))))";
            case "java.time.LocalDateTime" -> source + " instanceof java.time.LocalDateTime localDateTime ? localDateTime : (" + source + " instanceof java.sql.Timestamp timestamp ? timestamp.toLocalDateTime() : (" + source + " instanceof java.time.OffsetDateTime offsetDateTime ? offsetDateTime.toLocalDateTime() : (" + source + " instanceof java.time.Instant instant ? java.time.LocalDateTime.ofInstant(instant, java.time.ZoneOffset.UTC) : (" + source + " == null ? null : java.time.LocalDateTime.parse(String.valueOf(" + source + "))))))";
            case "java.time.OffsetDateTime" -> source + " instanceof java.time.OffsetDateTime offsetDateTime ? offsetDateTime : (" + source + " instanceof java.sql.Timestamp timestamp ? timestamp.toInstant().atOffset(java.time.ZoneOffset.UTC) : (" + source + " instanceof java.time.Instant instant ? instant.atOffset(java.time.ZoneOffset.UTC) : (" + source + " == null ? null : java.time.OffsetDateTime.parse(String.valueOf(" + source + ")))))";
            default -> throw new IllegalArgumentException("Unsupported type: " + field.typeName());
        };
    }

    private String toColumnValueExpression(EntityModel model, FieldModel field) {
        String access = accessExpression(model, field);
        if (field.codecTypeName() != null) {
            if (field.encryption() != null && boxedTypeName(field.typeName()).equals(field.typeName()))
                return access + " == null ? null : " + field.fieldName().toUpperCase() + "_CODEC.toColumnValue(" + access + ")";
            return field.fieldName().toUpperCase() + "_CODEC.toColumnValue(" + access + ")";
        }
        if (field.enumType()) {
            return access + " == null ? null : " + access + ".name()";
        }
        return access;
    }

    private String accessExpression(EntityModel model, FieldModel field) {
        return model.recordType()
                ? "entity." + field.fieldName() + "()"
                : "entity." + field.fieldName();
    }

    private String columnTypeName(FieldModel field) {
        if (field.codecTypeName() != null || field.enumType()) {
            return "java.lang.String";
        }
        return field.typeName();
    }

    private record EntityModel(
            String packageName,
            String simpleName,
            String bindingName,
            boolean recordType,
            String tableName,
            String redisNamespace,
            String versionColumn,
            String deletedColumn,
            String activeMarkerValue,
            String deletedMarkerValue,
            FieldModel idField,
            List<FieldModel> persistedFields,
            java.util.Set<String> indexedColumns,
            Map<String, List<String>> partitionedSortIndexes,
            List<RelationModel> relations,
            List<ProjectionModel> projections,
            List<NamedQueryModel> namedQueries,
            List<FetchPresetModel> fetchPresets,
            List<PagePresetModel> pagePresets,
            List<SaveCommandModel> saveCommands,
            List<DeleteCommandModel> deleteCommands,
            LoaderModel relationLoader,
            LoaderModel pageLoader
    ) {
        private EntityModel {
            Objects.requireNonNull(packageName);
            Objects.requireNonNull(simpleName);
            Objects.requireNonNull(bindingName);
            persistedFields = List.copyOf(persistedFields);
            partitionedSortIndexes = Map.copyOf(partitionedSortIndexes);
            relations = List.copyOf(relations);
            projections = List.copyOf(projections);
            namedQueries = List.copyOf(namedQueries);
            fetchPresets = List.copyOf(fetchPresets);
            pagePresets = List.copyOf(pagePresets);
            saveCommands = List.copyOf(saveCommands);
            deleteCommands = List.copyOf(deleteCommands);
        }
    }

    private boolean deeplyImmutableField(FieldModel field) {
        return field.enumType() || java.util.Set.of("boolean", "byte", "short", "int", "long", "float", "double", "char",
                "java.lang.Boolean", "java.lang.Byte", "java.lang.Short", "java.lang.Integer", "java.lang.Long",
                "java.lang.Float", "java.lang.Double", "java.lang.Character", "java.lang.String",
                "java.math.BigDecimal", "java.math.BigInteger", "java.util.UUID", "java.time.Instant",
                "java.time.LocalDate", "java.time.LocalTime", "java.time.LocalDateTime", "java.time.OffsetDateTime",
                "java.time.ZonedDateTime", "java.time.Duration", "java.time.Period").contains(field.typeName());
    }

    private Map<String, List<String>> compositeIndexes(TypeElement element, List<FieldModel> fields) {
        var result = new java.util.LinkedHashMap<String, List<String>>();
        var annotations = element.getAnnotationsByType(com.reactor.cachedb.annotations.CacheCompositeIndex.class);
        if (annotations.length > 32) throw new IllegalArgumentException("At most 32 composite indexes per entity");
        for (var annotation : annotations) {
            int minimumPrefix = annotation.sortBy().isEmpty() ? 2 : 1;
            if (annotation.columns().length < minimumPrefix || annotation.columns().length > 4)
                throw new IllegalArgumentException("Composite index requires " + minimumPrefix + "..4 distinct prefix columns");
            var columns = new java.util.ArrayList<String>();
            for (String declared : annotation.columns()) {
                var field = fields.stream().filter(value -> value.columnName().equals(declared) || value.fieldName().equals(declared)).findFirst().orElse(null);
                if (field == null || columns.contains(field.columnName())) throw new IllegalArgumentException("Unknown or duplicate composite index column: " + declared);
                columns.add(field.columnName());
            }
            String name = annotation.name().isBlank() ? String.join("_", columns) : annotation.name();
            if (!name.matches("[A-Za-z_][A-Za-z0-9_]{0,127}") || result.putIfAbsent(name, List.copyOf(columns)) != null)
                throw new IllegalArgumentException("Invalid or duplicate composite index name: " + name);
        }
        return result;
    }

    private Map<String, String> compositeSortColumns(TypeElement element, List<FieldModel> fields) {
        compositeIndexes(element, fields);
        var result = new java.util.LinkedHashMap<String, String>();
        for (var annotation : element.getAnnotationsByType(com.reactor.cachedb.annotations.CacheCompositeIndex.class)) {
            if (annotation.sortBy().isEmpty()) continue;
            var sort = fields.stream().filter(field -> field.fieldName().equals(annotation.sortBy())
                    || field.columnName().equals(annotation.sortBy())).findFirst().orElse(null);
            if (sort == null) throw new IllegalArgumentException("Unknown composite sortBy: " + annotation.sortBy());
            var prefix = new java.util.ArrayList<String>();
            for (String declared : annotation.columns()) {
                prefix.add(fields.stream().filter(field -> field.fieldName().equals(declared)
                        || field.columnName().equals(declared)).findFirst().orElseThrow().columnName());
            }
            if (prefix.contains(sort.columnName()))
                throw new IllegalArgumentException("Composite sortBy must not be a prefix column");
            if (sort.codecTypeName() != null || !java.util.Set.of(
                    "int", "java.lang.Integer", "long", "java.lang.Long",
                    "double", "java.lang.Double", "float", "java.lang.Float",
                    "short", "java.lang.Short", "byte", "java.lang.Byte",
                    "java.time.Instant", "java.time.LocalDate", "java.time.LocalDateTime",
                    "java.time.OffsetDateTime").contains(sort.typeName()))
                throw new IllegalArgumentException("Unsupported composite sortBy type: " + sort.typeName());
            String name = annotation.name().isBlank() ? String.join("_", prefix) : annotation.name();
            result.put(name, sort.columnName());
        }
        return result;
    }

    private record FieldModel(
            String fieldName,
            String typeName,
            String columnName,
            boolean idField,
            boolean enumType,
            String codecTypeName,
            EncryptionModel encryption
    ) {
    }

    private record EncryptionModel(String keyRing, String scope, boolean blindIndex) {}

    private record RelationModel(
            String name,
            String targetEntity,
            String mappedBy,
            String kindName,
            boolean batchLoadOnly,
            String targetTypeName,
            String targetBindingTypeName,
            String partitionColumn,
            int maxRowsPerParent,
            int parentBatchSize,
            List<RelationSortModel> sorts,
            boolean generatedLoader
    ) {
        private RelationModel {
            sorts = List.copyOf(sorts);
        }
    }

    private record RelationDeclaration(VariableElement field, CacheRelation annotation) {
    }

    private record RelationSortModel(String columnName, boolean descending) {
    }

    private record LoaderModel(
            String typeName,
            LoaderConstructorModel constructor
    ) {
    }

    private record LoaderConstructorModel(
            List<LoaderDependencyModel> dependencies
    ) {
    }

    private record LoaderDependencyModel(
            LoaderDependencyKind kind,
            String bindingTypeName
    ) {
    }

    private record ProjectionModel(
            String accessorName,
            String factoryMethodName,
            String projectionTypeName,
            String entityProjectionTypeName
    ) {
    }

    private record NamedQueryModel(
            String accessorName,
            String factoryMethodName,
            List<MethodParameterModel> parameters,
            RouteModel route
    ) {
    }

    private record RouteModel(
            String routeName,
            String projectionAccessor,
            int pageSize,
            int hotWindow,
            int maxColdReadSize,
            long memoryBudgetBytes,
            boolean strict,
            String limitParameter
    ) {
    }

    private record FetchPresetModel(
            String accessorName,
            String factoryMethodName,
            List<MethodParameterModel> parameters
    ) {
    }

    private record PagePresetModel(
            String accessorName,
            String factoryMethodName,
            List<MethodParameterModel> parameters
    ) {
    }

    private record SaveCommandModel(
            String accessorName,
            String factoryMethodName,
            List<MethodParameterModel> parameters
    ) {
    }

    private record DeleteCommandModel(
            String accessorName,
            String factoryMethodName,
            String returnTypeName,
            List<MethodParameterModel> parameters
    ) {
    }

    private record MethodParameterModel(
            String typeName,
            String parameterName
    ) {
    }

    private enum LoaderDependencyKind {
        CACHE_DATABASE,
        CACHE_SESSION,
        CACHE_POLICY,
        ENTITY_REPOSITORY
    }
}
