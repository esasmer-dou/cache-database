package com.reactor.cachedb.processor;

import com.reactor.cachedb.annotations.CacheCodec;
import com.reactor.cachedb.annotations.CacheColumn;
import com.reactor.cachedb.annotations.CacheDeleteCommand;
import com.reactor.cachedb.annotations.CacheEntity;
import com.reactor.cachedb.annotations.CacheFetchPreset;
import com.reactor.cachedb.annotations.CacheId;
import com.reactor.cachedb.annotations.CacheNamedQuery;
import com.reactor.cachedb.annotations.CachePagePreset;
import com.reactor.cachedb.annotations.CacheProjectionDefinition;
import com.reactor.cachedb.annotations.CacheRelation;
import com.reactor.cachedb.annotations.CacheSaveCommand;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@SupportedAnnotationTypes("com.reactor.cachedb.annotations.CacheEntity")
@SupportedSourceVersion(SourceVersion.RELEASE_17)
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
    private static final ProjectionModel INVALID_PROJECTION_MODEL =
            new ProjectionModel("__invalid__", "__invalid__", "__invalid__", "__invalid__");
    private static final NamedQueryModel INVALID_NAMED_QUERY_MODEL =
            new NamedQueryModel("__invalid__", "__invalid__", List.of());
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
        for (Element element : roundEnv.getElementsAnnotatedWith(CacheEntity.class)) {
            if (element.getKind() != ElementKind.CLASS) {
                messager.printMessage(Diagnostic.Kind.ERROR, "@CacheEntity can only be used on classes", element);
                continue;
            }

            TypeElement typeElement = (TypeElement) element;
            EntityModel entityModel = buildEntityModel(typeElement);
            if (entityModel == null) {
                continue;
            }

            writeBindingClass(entityModel);
            roundPackageEntityModels.computeIfAbsent(entityModel.packageName(), ignored -> new ArrayList<>()).add(entityModel);
        }
        for (Map.Entry<String, List<EntityModel>> entry : roundPackageEntityModels.entrySet()) {
            List<EntityModel> models = entry.getValue().stream()
                    .sorted(Comparator.comparing(EntityModel::simpleName))
                    .toList();
            writePackageBindingsClass(entry.getKey(), models);
            writePackageModuleClass(entry.getKey(), models);
            writePackageBindingsRegistrarClass(entry.getKey(), models);
            generatedRegistrarClassNames.add(entry.getKey() + ".GeneratedCacheBindingsRegistrar");
        }
        return true;
    }

    private EntityModel buildEntityModel(TypeElement typeElement) {
        CacheEntity cacheEntity = typeElement.getAnnotation(CacheEntity.class);
        String packageName = processingEnv.getElementUtils().getPackageOf(typeElement).getQualifiedName().toString();
        String simpleName = typeElement.getSimpleName().toString();
        String redisNamespace = cacheEntity.redisNamespace().isBlank() ? simpleName : cacheEntity.redisNamespace();
        String relationLoaderTypeName = extractLoaderTypeName(cacheEntity, true);
        String pageLoaderTypeName = extractLoaderTypeName(cacheEntity, false);

        if (!hasAccessibleNoArgConstructor(typeElement)) {
            messager.printMessage(Diagnostic.Kind.ERROR, "@CacheEntity class must declare a non-private no-arg constructor", typeElement);
            return null;
        }

        FieldModel idField = null;
        List<FieldModel> persistedFields = new ArrayList<>();
        List<RelationModel> relations = new ArrayList<>();
        List<ProjectionModel> projections = new ArrayList<>();
        List<NamedQueryModel> namedQueries = new ArrayList<>();
        List<FetchPresetModel> fetchPresets = new ArrayList<>();
        List<PagePresetModel> pagePresets = new ArrayList<>();
        List<SaveCommandModel> saveCommands = new ArrayList<>();
        List<DeleteCommandModel> deleteCommands = new ArrayList<>();

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
            if (enclosed.getKind() != ElementKind.FIELD) {
                continue;
            }

            VariableElement field = (VariableElement) enclosed;
            CacheId cacheId = field.getAnnotation(CacheId.class);
            CacheColumn cacheColumn = field.getAnnotation(CacheColumn.class);
            CacheRelation cacheRelation = field.getAnnotation(CacheRelation.class);
            CacheCodec cacheCodec = field.getAnnotation(CacheCodec.class);

            if (cacheRelation != null) {
                relations.add(new RelationModel(
                        field.getSimpleName().toString(),
                        cacheRelation.targetEntity(),
                        cacheRelation.mappedBy(),
                        cacheRelation.kind().name(),
                        cacheRelation.batchLoadOnly()
                ));
            }

            if (cacheId == null && cacheColumn == null) {
                continue;
            }

            if (field.getModifiers().contains(Modifier.PRIVATE) || field.getModifiers().contains(Modifier.FINAL)) {
                messager.printMessage(Diagnostic.Kind.ERROR, "Persisted fields must not be private or final", field);
                return null;
            }

            FieldModel fieldModel = new FieldModel(
                    field.getSimpleName().toString(),
                    field.asType().toString(),
                    cacheId != null ? cacheId.column() : cacheColumn.value(),
                    cacheId != null,
                    isEnumType(field),
                    extractCodecTypeName(cacheCodec)
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

            persistedFields.add(fieldModel);
        }

        if (idField == null) {
            messager.printMessage(Diagnostic.Kind.ERROR, "@CacheEntity class must define one @CacheId field", typeElement);
            return null;
        }

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

        return new EntityModel(
                packageName,
                simpleName,
                simpleName + "CacheBinding",
                cacheEntity.table(),
                redisNamespace,
                idField,
                persistedFields,
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
                resolveMethodParameters(method)
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
                    "float", "java.lang.Float",
                    "short", "java.lang.Short",
                    "byte", "java.lang.Byte",
                    "java.time.Instant",
                    "java.time.LocalDate",
                    "java.time.LocalDateTime",
                    "java.time.OffsetDateTime" -> true;
            default -> false;
        };
    }

    private boolean isEnumType(VariableElement field) {
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
                writer.write(renderPackageBindingsRegistrar(packageName));
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
        builder.append("import com.reactor.cachedb.core.active.ActiveRecordInstance;\n");
        builder.append("import com.reactor.cachedb.core.cache.CachePolicy;\n");
        builder.append("import com.reactor.cachedb.core.cache.PageWindow;\n");
        builder.append("import com.reactor.cachedb.core.plan.FetchPlan;\n");
        builder.append("import com.reactor.cachedb.core.model.EntityCodec;\n");
        builder.append("import com.reactor.cachedb.core.model.EntityMetadata;\n");
        builder.append("import com.reactor.cachedb.core.page.EntityPageLoader;\n");
        builder.append("import com.reactor.cachedb.core.model.RelationDefinition;\n");
        builder.append("import com.reactor.cachedb.core.model.RelationKind;\n");
        builder.append("import com.reactor.cachedb.core.projection.EntityProjection;\n");
        builder.append("import com.reactor.cachedb.core.query.QuerySpec;\n");
        builder.append("import com.reactor.cachedb.core.relation.RelationBatchLoader;\n");
        builder.append("import com.reactor.cachedb.starter.CacheDatabase;\n");
        builder.append("import java.util.LinkedHashMap;\n");
        builder.append("import java.util.List;\n");
        builder.append("import java.util.Map;\n");
        builder.append("import java.util.Optional;\n");
        builder.append("import java.util.function.Function;\n\n");
        builder.append("public final class ").append(model.bindingName()).append(" {\n");
        builder.append("    public static final EntityMetadata<").append(entityName).append(", ").append(model.idField().typeName()).append("> METADATA = new Metadata();\n");
        builder.append("    public static final EntityCodec<").append(entityName).append("> CODEC = new Codec();\n\n");
        for (FieldModel field : model.persistedFields()) {
            if (field.codecTypeName() != null) {
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

        renderMetadata(builder, model);
        builder.append('\n');
        renderCodec(builder, model);
        builder.append('\n');
        renderLoaderFactories(builder, model);
        if (model.relationLoader() != null || model.pageLoader() != null) {
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
        builder.append("    }\n");
        builder.append("}\n");
        return builder.toString();
    }

    private String renderPackageBindingsRegistrar(String packageName) {
        StringBuilder builder = new StringBuilder();
        builder.append("package ").append(packageName).append(";\n\n");
        builder.append("import com.reactor.cachedb.core.cache.CachePolicy;\n");
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
        builder.append("    }\n");
        builder.append("}\n");
        return builder.toString();
    }

    private String renderPackageModule(String packageName, List<EntityModel> models) {
        StringBuilder builder = new StringBuilder();
        builder.append("package ").append(packageName).append(";\n\n");
        builder.append("import com.reactor.cachedb.core.api.CacheSession;\n");
        builder.append("import com.reactor.cachedb.core.cache.CachePolicy;\n");
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
            builder.append("        private ").append(model.bindingName()).append(".Scope ").append(accessorName).append("Scope;\n");
        }
        builder.append('\n');
        builder.append("        private Scope(CacheSession cacheSession, CachePolicy cachePolicy) {\n");
        builder.append("            this.cacheSession = cacheSession;\n");
        builder.append("            this.cachePolicy = cachePolicy;\n");
        builder.append("        }\n\n");
        for (int index = 0; index < models.size(); index++) {
            EntityModel model = models.get(index);
            String accessorName = packageScopeAccessorName(model, models);
            builder.append("        public ").append(model.bindingName()).append(".Scope ").append(accessorName).append("() {\n");
            builder.append("            if (").append(accessorName).append("Scope == null) {\n");
            builder.append("                ").append(accessorName).append("Scope = cachePolicy == null ? ")
                    .append(model.bindingName()).append(".using(cacheSession) : ")
                    .append(model.bindingName()).append(".using(cacheSession, cachePolicy);\n");
            builder.append("            }\n");
            builder.append("            return ").append(accessorName).append("Scope;\n");
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

    private void renderMetadata(StringBuilder builder, EntityModel model) {
        builder.append("    private static final class Metadata implements EntityMetadata<")
                .append(model.simpleName()).append(", ").append(model.idField().typeName()).append("> {\n");
        builder.append("        @Override\n");
        builder.append("        public String entityName() {\n");
        builder.append("            return \"").append(model.simpleName()).append("\";\n");
        builder.append("        }\n\n");
        builder.append("        @Override\n");
        builder.append("        public String tableName() {\n");
        builder.append("            return \"").append(model.tableName()).append("\";\n");
        builder.append("        }\n\n");
        builder.append("        @Override\n");
        builder.append("        public String redisNamespace() {\n");
        builder.append("            return \"").append(model.redisNamespace()).append("\";\n");
        builder.append("        }\n\n");
        builder.append("        @Override\n");
        builder.append("        public String idColumn() {\n");
        builder.append("            return \"").append(model.idField().columnName()).append("\";\n");
        builder.append("        }\n\n");
        builder.append("        @Override\n");
        builder.append("        public Class<").append(model.simpleName()).append("> entityType() {\n");
        builder.append("            return ").append(model.simpleName()).append(".class;\n");
        builder.append("        }\n\n");
        builder.append("        @Override\n");
        builder.append("        public Function<").append(model.simpleName()).append(", ").append(model.idField().typeName()).append("> idAccessor() {\n");
        builder.append("            return entity -> entity.").append(model.idField().fieldName()).append(";\n");
        builder.append("        }\n\n");
        builder.append("        @Override\n");
        builder.append("        public List<String> columns() {\n");
        builder.append("            return List.of(");
        appendQuotedList(builder, model.persistedFields().stream().map(FieldModel::columnName).toList());
        builder.append(");\n");
        builder.append("        }\n\n");
        builder.append("        @Override\n");
        builder.append("        public Map<String, String> columnTypes() {\n");
        builder.append("            LinkedHashMap<String, String> columnTypes = new LinkedHashMap<>();\n");
        for (FieldModel field : model.persistedFields()) {
            builder.append("            columnTypes.put(\"").append(field.columnName()).append("\", \"")
                    .append(columnTypeName(field)).append("\");\n");
        }
        builder.append("            return columnTypes;\n");
        builder.append("        }\n\n");
        builder.append("        @Override\n");
        builder.append("        public List<RelationDefinition> relations() {\n");
        if (model.relations().isEmpty()) {
            builder.append("            return List.of();\n");
        } else {
            builder.append("            return List.of(\n");
            for (int index = 0; index < model.relations().size(); index++) {
                RelationModel relation = model.relations().get(index);
                builder.append("                    new RelationDefinition(\"")
                        .append(relation.name()).append("\", \"")
                        .append(relation.targetEntity()).append("\", \"")
                        .append(relation.mappedBy()).append("\", RelationKind.")
                        .append(relation.kindName()).append(", ")
                        .append(relation.batchLoadOnly()).append(")");
                builder.append(index == model.relations().size() - 1 ? "\n" : ",\n");
            }
            builder.append("            );\n");
        }
        builder.append("        }\n");
        builder.append("    }\n");
    }

    private void renderCodec(StringBuilder builder, EntityModel model) {
        builder.append("    private static final class Codec implements EntityCodec<").append(model.simpleName()).append("> {\n");
        builder.append("        @Override\n");
        builder.append("        public String toRedisValue(").append(model.simpleName()).append(" entity) {\n");
        builder.append("            LinkedHashMap<String, String> values = new LinkedHashMap<>();\n");
        for (FieldModel field : model.persistedFields()) {
            builder.append("            values.put(\"").append(field.columnName()).append("\", ")
                    .append(toStringExpression("entity." + field.fieldName(), field)).append(");\n");
        }
        builder.append("            return LengthPrefixedPayloadCodec.encode(values);\n");
        builder.append("        }\n\n");
        builder.append("        @Override\n");
        builder.append("        public ").append(model.simpleName()).append(" fromRedisValue(String encoded) {\n");
        builder.append("            Map<String, String> values = LengthPrefixedPayloadCodec.decode(encoded);\n");
        builder.append("            ").append(model.simpleName()).append(" entity = new ").append(model.simpleName()).append("();\n");
        for (FieldModel field : model.persistedFields()) {
            builder.append("            entity.").append(field.fieldName()).append(" = ")
                    .append(fromStringExpression("values.get(\"" + field.columnName() + "\")", field)).append(";\n");
        }
        builder.append("            return entity;\n");
        builder.append("        }\n\n");
        builder.append("        @Override\n");
        builder.append("        public ").append(model.simpleName()).append(" fromColumns(Map<String, Object> columns) {\n");
        builder.append("            ").append(model.simpleName()).append(" entity = new ").append(model.simpleName()).append("();\n");
        for (FieldModel field : model.persistedFields()) {
            builder.append("            entity.").append(field.fieldName()).append(" = ")
                    .append(fromColumnExpression("columnValue(columns, \"" + field.columnName() + "\")", field)).append(";\n");
        }
        builder.append("            return entity;\n");
        builder.append("        }\n\n");
        builder.append("        @Override\n");
        builder.append("        public Map<String, Object> toColumns(").append(model.simpleName()).append(" entity) {\n");
        builder.append("            LinkedHashMap<String, Object> columns = new LinkedHashMap<>();\n");
        for (FieldModel field : model.persistedFields()) {
            builder.append("            columns.put(\"").append(field.columnName()).append("\", ")
                    .append(toColumnValueExpression(field)).append(");\n");
        }
        builder.append("            return columns;\n");
        builder.append("        }\n");
        builder.append("    }\n");
    }

    private void renderLoaderFactories(StringBuilder builder, EntityModel model) {
        renderRelationLoaderFactory(builder, model);
        if (model.relationLoader() != null && model.pageLoader() != null) {
            builder.append('\n');
        }
        renderPageLoaderFactory(builder, model);
    }

    private void renderRelationLoaderFactory(StringBuilder builder, EntityModel model) {
        if (model.relationLoader() == null) {
            return;
        }
        builder.append("    public static RelationBatchLoader<").append(model.simpleName())
                .append("> relationLoader(CacheDatabase cacheDatabase) {\n");
        builder.append("        return relationLoader(cacheDatabase, cacheDatabase.config().resourceLimits().defaultCachePolicy());\n");
        builder.append("    }\n\n");
        builder.append("    public static RelationBatchLoader<").append(model.simpleName())
                .append("> relationLoader(CacheDatabase cacheDatabase, CachePolicy cachePolicy) {\n");
        builder.append("        return new ").append(model.relationLoader().typeName()).append("(");
        appendLoaderConstructorArguments(builder, model.relationLoader().constructor());
        builder.append(");\n");
        builder.append("    }\n");
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
                case ENTITY_REPOSITORY -> dependency.bindingTypeName() + ".repository(cacheDatabase, cachePolicy)";
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
        if (model.relationLoader() != null || model.pageLoader() != null) {
            builder.append("        register(cacheDatabase, cacheDatabase.config().resourceLimits().defaultCachePolicy());\n");
        } else {
            builder.append("        cacheDatabase.register(METADATA, CODEC);\n");
        }
        builder.append("    }\n\n");
        builder.append("    public static void register(CacheDatabase cacheDatabase, CachePolicy cachePolicy) {\n");
        if (model.relationLoader() != null || model.pageLoader() != null) {
            builder.append("        cacheDatabase.register(METADATA, CODEC, cachePolicy, ")
                    .append(model.relationLoader() == null ? "null" : "relationLoader(cacheDatabase, cachePolicy)")
                    .append(", ")
                    .append(model.pageLoader() == null ? "null" : "pageLoader(cacheDatabase, cachePolicy)")
                    .append(");\n");
        } else {
            builder.append("        cacheDatabase.register(METADATA, CODEC, cachePolicy);\n");
        }
        builder.append("    }\n\n");
        builder.append("    public static void register(CacheDatabase cacheDatabase, RelationBatchLoader<")
                .append(model.simpleName()).append("> relationBatchLoader) {\n");
        builder.append("        cacheDatabase.register(METADATA, CODEC, cacheDatabase.config().resourceLimits().defaultCachePolicy(), relationBatchLoader, null);\n");
        builder.append("    }\n\n");
        builder.append("    public static void register(CacheDatabase cacheDatabase, CachePolicy cachePolicy, RelationBatchLoader<")
                .append(model.simpleName()).append("> relationBatchLoader) {\n");
        builder.append("        cacheDatabase.register(METADATA, CODEC, cachePolicy, relationBatchLoader, null);\n");
        builder.append("    }\n\n");
        builder.append("    public static void register(CacheDatabase cacheDatabase, RelationBatchLoader<")
                .append(model.simpleName()).append("> relationBatchLoader, EntityPageLoader<").append(model.simpleName())
                .append("> pageLoader) {\n");
        builder.append("        cacheDatabase.register(METADATA, CODEC, cacheDatabase.config().resourceLimits().defaultCachePolicy(), relationBatchLoader, pageLoader);\n");
        builder.append("    }\n\n");
        builder.append("    public static void register(CacheDatabase cacheDatabase, CachePolicy cachePolicy, RelationBatchLoader<")
                .append(model.simpleName()).append("> relationBatchLoader, EntityPageLoader<").append(model.simpleName())
                .append("> pageLoader) {\n");
        builder.append("        cacheDatabase.register(METADATA, CODEC, cachePolicy, relationBatchLoader, pageLoader);\n");
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
        builder.append("        private final CachePolicy cachePolicy;\n");
        builder.append("        private EntityRepository<").append(model.simpleName()).append(", ")
                .append(model.idField().typeName()).append("> repository;\n");
        if (!model.namedQueries().isEmpty()) {
            builder.append("        private Queries queries;\n");
        }
        if (!model.projections().isEmpty()) {
            builder.append("        private Projections projections;\n");
        }
        if (!model.fetchPresets().isEmpty()) {
            builder.append("        private Fetches fetches;\n");
        }
        if (!model.pagePresets().isEmpty()) {
            builder.append("        private Pages pages;\n");
        }
        if (!model.saveCommands().isEmpty()) {
            builder.append("        private Commands commands;\n");
        }
        if (!model.deleteCommands().isEmpty()) {
            builder.append("        private Deletes deletes;\n");
        }
        builder.append('\n');
        builder.append("        private Scope(CacheSession cacheSession, CachePolicy cachePolicy) {\n");
        builder.append("            this.cacheSession = cacheSession;\n");
        builder.append("            this.cachePolicy = cachePolicy;\n");
        builder.append("        }\n\n");
        builder.append("        public EntityRepository<").append(model.simpleName()).append(", ")
                .append(model.idField().typeName()).append("> repository() {\n");
        builder.append("            if (repository == null) {\n");
        builder.append("                repository = cachePolicy == null ? ").append(model.bindingName())
                .append(".repository(cacheSession) : ").append(model.bindingName())
                .append(".repository(cacheSession, cachePolicy);\n");
        builder.append("            }\n");
        builder.append("            return repository;\n");
        builder.append("        }\n\n");
        builder.append("        public ActiveRecordInstance<").append(model.simpleName()).append(", ")
                .append(model.idField().typeName()).append("> attach(").append(model.simpleName())
                .append(" entity) {\n");
        builder.append("            return cachePolicy == null ? ").append(model.bindingName())
                .append(".attach(cacheSession, entity) : ").append(model.bindingName())
                .append(".attach(cacheSession, cachePolicy, entity);\n");
        builder.append("        }\n\n");
        builder.append("        public ").append(model.simpleName()).append(" save(").append(model.simpleName())
                .append(" entity) {\n");
        builder.append("            return cachePolicy == null ? ").append(model.bindingName())
                .append(".save(cacheSession, entity) : ").append(model.bindingName())
                .append(".save(cacheSession, cachePolicy, entity);\n");
        builder.append("        }\n\n");
        builder.append("        public Optional<").append(model.simpleName()).append("> findById(")
                .append(model.idField().typeName()).append(" id) {\n");
        builder.append("            return cachePolicy == null ? ").append(model.bindingName())
                .append(".findById(cacheSession, id) : ").append(model.bindingName())
                .append(".findById(cacheSession, cachePolicy, id);\n");
        builder.append("        }\n\n");
        builder.append("        public List<").append(model.simpleName()).append("> findPage(PageWindow pageWindow) {\n");
        builder.append("            return cachePolicy == null ? ").append(model.bindingName())
                .append(".findPage(cacheSession, pageWindow) : ").append(model.bindingName())
                .append(".findPage(cacheSession, cachePolicy, pageWindow);\n");
        builder.append("        }\n\n");
        builder.append("        public List<").append(model.simpleName()).append("> query(QuerySpec querySpec) {\n");
        builder.append("            return cachePolicy == null ? ").append(model.bindingName())
                .append(".query(cacheSession, querySpec) : ").append(model.bindingName())
                .append(".query(cacheSession, cachePolicy, querySpec);\n");
        builder.append("        }\n\n");
        builder.append("        public void deleteById(").append(model.idField().typeName()).append(" id) {\n");
        builder.append("            if (cachePolicy == null) {\n");
        builder.append("                ").append(model.bindingName()).append(".deleteById(cacheSession, id);\n");
        builder.append("            } else {\n");
        builder.append("                ").append(model.bindingName()).append(".deleteById(cacheSession, cachePolicy, id);\n");
        builder.append("            }\n");
        builder.append("        }\n");
        if (!model.namedQueries().isEmpty()) {
            builder.append('\n');
            builder.append("        public Queries queries() {\n");
            builder.append("            if (queries == null) {\n");
            builder.append("                queries = new Queries(this);\n");
            builder.append("            }\n");
            builder.append("            return queries;\n");
            builder.append("        }\n");
        }
        if (!model.projections().isEmpty()) {
            builder.append('\n');
            builder.append("        public Projections projections() {\n");
            builder.append("            if (projections == null) {\n");
            builder.append("                projections = new Projections(this);\n");
            builder.append("            }\n");
            builder.append("            return projections;\n");
            builder.append("        }\n");
        }
        if (!model.fetchPresets().isEmpty()) {
            builder.append('\n');
            builder.append("        public Fetches fetches() {\n");
            builder.append("            if (fetches == null) {\n");
            builder.append("                fetches = new Fetches(this);\n");
            builder.append("            }\n");
            builder.append("            return fetches;\n");
            builder.append("        }\n");
        }
        if (!model.pagePresets().isEmpty()) {
            builder.append('\n');
            builder.append("        public Pages pages() {\n");
            builder.append("            if (pages == null) {\n");
            builder.append("                pages = new Pages(this);\n");
            builder.append("            }\n");
            builder.append("            return pages;\n");
            builder.append("        }\n");
        }
        if (!model.saveCommands().isEmpty()) {
            builder.append('\n');
            builder.append("        public Commands commands() {\n");
            builder.append("            if (commands == null) {\n");
            builder.append("                commands = new Commands(this);\n");
            builder.append("            }\n");
            builder.append("            return commands;\n");
            builder.append("        }\n");
        }
        if (!model.deleteCommands().isEmpty()) {
            builder.append('\n');
            builder.append("        public Deletes deletes() {\n");
            builder.append("            if (deletes == null) {\n");
            builder.append("                deletes = new Deletes(this);\n");
            builder.append("            }\n");
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
            builder.append("            return scope.repository().query(").append(namedQuery.accessorName()).append("Query(")
                    .append(arguments).append("));\n");
            builder.append("        }\n");
            if (index < model.namedQueries().size() - 1) {
                builder.append('\n');
            }
        }
        builder.append("    }\n");
    }

    private void renderProjectionGroup(StringBuilder builder, EntityModel model) {
        builder.append("    public static final class Projections {\n");
        builder.append("        private final Scope scope;\n");
        for (ProjectionModel projection : model.projections()) {
            builder.append("        private ProjectionRepository<").append(projection.projectionTypeName()).append(", ")
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
            builder.append("            if (").append(projection.accessorName()).append(" == null) {\n");
            builder.append("                ").append(projection.accessorName()).append(" = scope.repository().projected(")
                    .append(projection.accessorName()).append("Projection());\n");
            builder.append("            }\n");
            builder.append("            return ").append(projection.accessorName()).append(";\n");
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
            builder.append('"').append(values.get(index)).append('"');
            if (index < values.size() - 1) {
                builder.append(", ");
            }
        }
    }

    private String toStringExpression(String accessor, FieldModel field) {
        if (field.codecTypeName() != null) {
            return field.fieldName().toUpperCase() + "_CODEC.encode(" + accessor + ")";
        }
        if (field.enumType()) {
            return accessor + " == null ? null : " + accessor + ".name()";
        }
        return switch (field.typeName()) {
            case "java.lang.String" -> accessor;
            case "java.lang.Integer", "java.lang.Long", "java.lang.Boolean", "java.lang.Double",
                    "java.lang.Float", "java.lang.Short", "java.lang.Byte",
                    "java.time.Instant", "java.time.LocalDate", "java.time.LocalDateTime", "java.time.OffsetDateTime" ->
                    accessor + " == null ? null : String.valueOf(" + accessor + ")";
            default -> "String.valueOf(" + accessor + ")";
        };
    }

    private String fromStringExpression(String source, FieldModel field) {
        if (field.codecTypeName() != null) {
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
            case "float" -> source + " == null ? 0F : Float.parseFloat(" + source + ")";
            case "java.lang.Float" -> source + " == null ? null : Float.valueOf(" + source + ")";
            case "short" -> source + " == null ? (short) 0 : Short.parseShort(" + source + ")";
            case "java.lang.Short" -> source + " == null ? null : Short.valueOf(" + source + ")";
            case "byte" -> source + " == null ? (byte) 0 : Byte.parseByte(" + source + ")";
            case "java.lang.Byte" -> source + " == null ? null : Byte.valueOf(" + source + ")";
            case "java.time.Instant" -> source + " == null ? null : java.time.Instant.parse(" + source + ")";
            case "java.time.LocalDate" -> source + " == null ? null : java.time.LocalDate.parse(" + source + ")";
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
            case "float" -> source + " instanceof Number number ? number.floatValue() : (" + source + " == null ? 0F : Float.parseFloat(String.valueOf(" + source + ")))";
            case "java.lang.Float" -> source + " instanceof Number number ? Float.valueOf(number.floatValue()) : (" + source + " == null ? null : Float.valueOf(String.valueOf(" + source + ")))";
            case "short" -> source + " instanceof Number number ? number.shortValue() : (" + source + " == null ? (short) 0 : Short.parseShort(String.valueOf(" + source + ")))";
            case "java.lang.Short" -> source + " instanceof Number number ? Short.valueOf(number.shortValue()) : (" + source + " == null ? null : Short.valueOf(String.valueOf(" + source + ")))";
            case "byte" -> source + " instanceof Number number ? number.byteValue() : (" + source + " == null ? (byte) 0 : Byte.parseByte(String.valueOf(" + source + ")))";
            case "java.lang.Byte" -> source + " instanceof Number number ? Byte.valueOf(number.byteValue()) : (" + source + " == null ? null : Byte.valueOf(String.valueOf(" + source + ")))";
            case "java.time.Instant" -> source + " instanceof java.time.Instant instant ? instant : (" + source + " instanceof java.sql.Timestamp timestamp ? timestamp.toInstant() : (" + source + " instanceof java.time.OffsetDateTime offsetDateTime ? offsetDateTime.toInstant() : (" + source + " == null ? null : java.time.Instant.parse(String.valueOf(" + source + ")))))";
            case "java.time.LocalDate" -> source + " instanceof java.time.LocalDate localDate ? localDate : (" + source + " instanceof java.sql.Date sqlDate ? sqlDate.toLocalDate() : (" + source + " instanceof java.sql.Timestamp timestamp ? timestamp.toLocalDateTime().toLocalDate() : (" + source + " == null ? null : java.time.LocalDate.parse(String.valueOf(" + source + ")))))";
            case "java.time.LocalDateTime" -> source + " instanceof java.time.LocalDateTime localDateTime ? localDateTime : (" + source + " instanceof java.sql.Timestamp timestamp ? timestamp.toLocalDateTime() : (" + source + " instanceof java.time.OffsetDateTime offsetDateTime ? offsetDateTime.toLocalDateTime() : (" + source + " instanceof java.time.Instant instant ? java.time.LocalDateTime.ofInstant(instant, java.time.ZoneOffset.UTC) : (" + source + " == null ? null : java.time.LocalDateTime.parse(String.valueOf(" + source + "))))))";
            case "java.time.OffsetDateTime" -> source + " instanceof java.time.OffsetDateTime offsetDateTime ? offsetDateTime : (" + source + " instanceof java.sql.Timestamp timestamp ? timestamp.toInstant().atOffset(java.time.ZoneOffset.UTC) : (" + source + " instanceof java.time.Instant instant ? instant.atOffset(java.time.ZoneOffset.UTC) : (" + source + " == null ? null : java.time.OffsetDateTime.parse(String.valueOf(" + source + ")))))";
            default -> throw new IllegalArgumentException("Unsupported type: " + field.typeName());
        };
    }

    private String toColumnValueExpression(FieldModel field) {
        if (field.codecTypeName() != null) {
            return field.fieldName().toUpperCase() + "_CODEC.toColumnValue(entity." + field.fieldName() + ")";
        }
        if (field.enumType()) {
            return "entity." + field.fieldName() + " == null ? null : entity." + field.fieldName() + ".name()";
        }
        return "entity." + field.fieldName();
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
            String tableName,
            String redisNamespace,
            FieldModel idField,
            List<FieldModel> persistedFields,
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
            relations = List.copyOf(relations);
            projections = List.copyOf(projections);
            namedQueries = List.copyOf(namedQueries);
            fetchPresets = List.copyOf(fetchPresets);
            pagePresets = List.copyOf(pagePresets);
            saveCommands = List.copyOf(saveCommands);
            deleteCommands = List.copyOf(deleteCommands);
        }
    }

    private record FieldModel(
            String fieldName,
            String typeName,
            String columnName,
            boolean idField,
            boolean enumType,
            String codecTypeName
    ) {
    }

    private record RelationModel(
            String name,
            String targetEntity,
            String mappedBy,
            String kindName,
            boolean batchLoadOnly
    ) {
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
            List<MethodParameterModel> parameters
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
