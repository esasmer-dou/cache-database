package com.reactor.cachedb.processor;

import com.reactor.cachedb.annotations.CacheColumn;
import com.reactor.cachedb.annotations.CacheCommand;
import com.reactor.cachedb.annotations.CacheId;
import com.reactor.cachedb.annotations.CacheGeneratedId;
import com.reactor.cachedb.annotations.CacheLookup;
import com.reactor.cachedb.annotations.CacheOrder;
import com.reactor.cachedb.annotations.CachePredicate;
import com.reactor.cachedb.annotations.CacheProjectionRecord;
import com.reactor.cachedb.annotations.CacheRepository;
import com.reactor.cachedb.annotations.CacheRepositoryDefaults;
import com.reactor.cachedb.annotations.CacheRelation;
import com.reactor.cachedb.annotations.CacheRouteQuery;
import com.reactor.cachedb.annotations.HotRoute;
import com.reactor.cachedb.annotations.SourceRoute;
import com.reactor.cachedb.annotations.SourceSql;
import com.reactor.cachedb.annotations.WarmRoute;
import com.reactor.cachedb.core.repository.SourceSqlValidator;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Generates user-owned repository implementations without runtime proxies or query parsing. */
@SupportedAnnotationTypes("com.reactor.cachedb.annotations.CacheRepository")
public final class CacheRepositoryProcessor extends AbstractProcessor {
    private static final String CACHE_DB_REPOSITORY = "com.reactor.cachedb.core.repository.CacheDbRepository";
    private static final String HOT_WINDOW = "com.reactor.cachedb.core.repository.HotWindow";
    private static final String HOT_LOOKUP = "com.reactor.cachedb.core.repository.HotLookup";
    private static final String SOURCE_WINDOW = "com.reactor.cachedb.core.repository.SourceWindow";
    private static final String CURSOR_PAGE = "com.reactor.cachedb.core.repository.CursorPage";
    private static final String WINDOW_REQUEST = "com.reactor.cachedb.core.repository.WindowRequest";
    private static final String WARM_PLAN = "com.reactor.cachedb.starter.CacheWarmPlan";
    private static final String WARM_TARGET = "com.reactor.cachedb.starter.CacheWarmTarget";

    private final Set<String> generated = new LinkedHashSet<>();
    private final Map<String, String> springBeanOwners = new HashMap<>();

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (Element element : roundEnv.getElementsAnnotatedWith(CacheRepository.class)) {
            if (!(element instanceof TypeElement repository) || repository.getKind() != ElementKind.INTERFACE) {
                error(element, "@CacheRepository can only be used on an interface");
                continue;
            }
            String qualifiedName = repository.getQualifiedName().toString();
            if (!generated.add(qualifiedName)) {
                continue;
            }
            RepositoryModel model = buildModel(repository);
            if (model != null) {
                if (model.springBean()) {
                    String existingOwner = springBeanOwners.putIfAbsent(model.springBeanName(), qualifiedName);
                    if (existingOwner != null && !existingOwner.equals(qualifiedName)) {
                        error(repository, "Spring repository bean name '" + model.springBeanName()
                                + "' is already used by " + existingOwner
                                + "; set a distinct @CacheRepository.springBeanName");
                        continue;
                    }
                }
                writeSource(model.implementationQualifiedName(), renderImplementation(model), repository);
                writeSource(model.packageName() + "." + model.repositoryName() + "CacheDbRoutes",
                        renderRouteReferences(model), repository);
                if (model.springBean()) {
                    writeSource(model.configurationQualifiedName(), renderSpringConfiguration(model), repository);
                }
            }
        }
        return false;
    }

    private RepositoryModel buildModel(TypeElement repository) {
        TypeElement baseRepository = processingEnv.getElementUtils().getTypeElement(CACHE_DB_REPOSITORY);
        if (baseRepository == null || !processingEnv.getTypeUtils().isAssignable(
                processingEnv.getTypeUtils().erasure(repository.asType()),
                processingEnv.getTypeUtils().erasure(baseRepository.asType()))) {
            error(repository, "@CacheRepository interface must extend CacheDbRepository<Entity, ID>");
            return null;
        }

        CacheRepository annotation = repository.getAnnotation(CacheRepository.class);
        RepositoryDefaultsModel defaults = resolveRepositoryDefaults(repository);
        if (defaults == null) {
            return null;
        }
        TypeMirror entityType = mirroredType(annotation);
        Element entityElement = processingEnv.getTypeUtils().asElement(entityType);
        if (!(entityElement instanceof TypeElement entity)
                || entity.getAnnotation(com.reactor.cachedb.annotations.CacheEntity.class) == null) {
            error(repository, "@CacheRepository.entity must reference an @CacheEntity type");
            return null;
        }
        EntityModel entityModel = resolveEntity(entity);
        if (entityModel == null) {
            return null;
        }
        if (!declaresExpectedRepositoryTypes(repository, entityModel)) {
            return null;
        }

        LinkedHashMap<String, RouteMethod> routeMethods = new LinkedHashMap<>();
        ArrayList<SourceSqlMethod> sourceSqlMethods = new ArrayList<>();
        ArrayList<CommandMethod> commandMethods = new ArrayList<>();
        ArrayList<WarmMethod> warmMethods = new ArrayList<>();
        ArrayList<LookupMethod> lookupMethods = new ArrayList<>();
        LinkedHashMap<String, ProjectionModel> projections = new LinkedHashMap<>();
        LinkedHashSet<String> abstractMethodNames = new LinkedHashSet<>();
        LinkedHashSet<String> routeNames = new LinkedHashSet<>();
        LinkedHashSet<String> warmRouteNames = new LinkedHashSet<>();
        for (Element enclosed : repository.getEnclosedElements()) {
            if (!(enclosed instanceof ExecutableElement method)) {
                continue;
            }
            if (method.getModifiers().contains(Modifier.DEFAULT)
                    || method.getModifiers().contains(Modifier.STATIC)
                    || method.getModifiers().contains(Modifier.PRIVATE)) {
                continue;
            }
            String methodName = method.getSimpleName().toString();
            if (!abstractMethodNames.add(methodName)) {
                error(method, "Overloaded abstract repository methods are not supported; use a distinct method name");
                return null;
            }
            CacheLookup lookup = method.getAnnotation(CacheLookup.class);
            if (lookup != null) {
                LookupMethod lookupMethod = resolveLookupMethod(method, lookup, entityModel);
                if (lookupMethod == null) {
                    return null;
                }
                lookupMethods.add(lookupMethod);
                continue;
            }
            SourceSql sourceSql = method.getAnnotation(SourceSql.class);
            if (sourceSql != null) {
                SourceSqlMethod sqlMethod = resolveSourceSqlMethod(method, sourceSql, entityModel);
                if (sqlMethod == null) {
                    return null;
                }
                if (sqlMethod.projection() != null) {
                    projections.putIfAbsent(sqlMethod.projection().typeName(), sqlMethod.projection());
                }
                sourceSqlMethods.add(sqlMethod);
                continue;
            }
            CacheCommand command = method.getAnnotation(CacheCommand.class);
            if (command != null) {
                CommandMethod commandMethod = resolveCommandMethod(method, command, entityModel);
                if (commandMethod == null) {
                    return null;
                }
                commandMethods.add(commandMethod);
                continue;
            }
            WarmRoute warmRoute = method.getAnnotation(WarmRoute.class);
            if (warmRoute != null) {
                WarmMethod warmMethod = resolveWarmMethod(method, warmRoute, defaults);
                if (warmMethod == null) {
                    return null;
                }
                if (!warmRouteNames.add(warmMethod.routeName())) {
                    error(method, "Warm route name is duplicated in this repository: " + warmMethod.routeName());
                    return null;
                }
                warmMethods.add(warmMethod);
                continue;
            }
            HotRoute hotRoute = method.getAnnotation(HotRoute.class);
            SourceRoute sourceRoute = method.getAnnotation(SourceRoute.class);
            CacheRouteQuery query = method.getAnnotation(CacheRouteQuery.class);
            if (hotRoute == null && sourceRoute == null) {
                error(method, "Abstract repository methods must declare @CacheLookup, @HotRoute, @SourceRoute, "
                        + "@SourceSql, @CacheCommand, or @WarmRoute");
                return null;
            }
            if (hotRoute != null && sourceRoute != null) {
                error(method, "A repository method cannot be both @HotRoute and @SourceRoute");
                return null;
            }
            if (query == null) {
                error(method, "@HotRoute and @SourceRoute methods require @CacheRouteQuery");
                return null;
            }
            RouteMethod route = resolveRouteMethod(method, hotRoute, sourceRoute, query, entityModel, defaults);
            if (route == null) {
                return null;
            }
            if (!routeNames.add(route.routeName())) {
                error(method, "Route name is duplicated in this repository: " + route.routeName());
                return null;
            }
            if (route.projection() != null) {
                projections.putIfAbsent(route.projection().typeName(), route.projection());
            }
            routeMethods.put(methodName, route);
        }
        for (int warmIndex = 0; warmIndex < warmMethods.size(); warmIndex++) {
            WarmMethod warm = warmMethods.get(warmIndex);
            RouteMethod source = routeMethods.get(warm.fromMethod());
            if (source == null) {
                error(warm.element(), "@WarmRoute.from does not reference a declared route method: " + warm.fromMethod());
                return null;
            }
            warm = resolveWarmParameters(warm, source);
            if (warm == null) {
                return null;
            }
            warmMethods.set(warmIndex, warm);
            if (source.kind() != RouteKind.HOT) {
                error(warm.element(), "@WarmRoute.from must reference a @HotRoute method");
                return null;
            }
            if (warm.maxRows() > source.hotWindow()) {
                error(warm.element(), "@WarmRoute.maxRows must not exceed the source @HotRoute.hotWindow");
                return null;
            }
            if ((warm.projectionsOnly() || warm.targetParameter() != null) && source.projection() == null) {
                error(warm.element(), "projection-selectable warm requires a projection-backed @HotRoute");
                return null;
            }
            String sourceScope = source.coverageScopeParameter();
            String warmScope = warm.coverageScopeParameter();
            if (!warmScope.isBlank() && !sourceScope.isBlank() && !warmScope.equals(sourceScope)) {
                error(warm.element(), "@WarmRoute coverage scope must match the source @HotRoute coverage scope");
                return null;
            }
        }
        Set<String> warmedRouteMethods = warmMethods.stream()
                .map(WarmMethod::fromMethod)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        for (RouteMethod route : routeMethods.values()) {
            if (route.kind() == RouteKind.HOT
                    && route.population() == HotRoute.Population.DECLARED_WARM
                    && !warmedRouteMethods.contains(route.name())) {
                error(route.element(), "@HotRoute(population=DECLARED_WARM) requires at least one @WarmRoute "
                        + "whose from value references this method");
                return null;
            }
        }

        String packageName = processingEnv.getElementUtils().getPackageOf(repository).getQualifiedName().toString();
        String repositoryName = repository.getSimpleName().toString();
        String configuredSpringBeanName = annotation.springBeanName().trim();
        if (!configuredSpringBeanName.isEmpty() && !isSafeRouteName(configuredSpringBeanName)) {
            error(repository, "@CacheRepository.springBeanName must be a safe non-blank Spring bean name");
            return null;
        }
        String springBeanName = configuredSpringBeanName.isEmpty()
                ? decapitalize(repositoryName)
                : configuredSpringBeanName;
        return new RepositoryModel(
                packageName,
                repositoryName,
                packageName + "." + repositoryName + "CacheDbImplementation",
                packageName + "." + repositoryName + "CacheDbConfiguration",
                annotation.springBean(),
                springBeanName,
                entityModel,
                List.copyOf(routeMethods.values()),
                List.copyOf(sourceSqlMethods),
                List.copyOf(commandMethods),
                List.copyOf(warmMethods),
                List.copyOf(lookupMethods),
                List.copyOf(projections.values())
        );
    }

    private RepositoryDefaultsModel resolveRepositoryDefaults(TypeElement repository) {
        CacheRepositoryDefaults annotation = repository.getAnnotation(CacheRepositoryDefaults.class);
        RepositoryDefaultsModel defaults = annotation == null
                ? RepositoryDefaultsModel.standard()
                : new RepositoryDefaultsModel(
                        annotation.hotPopulation(),
                        annotation.hotPageSize(),
                        annotation.hotWindow(),
                        annotation.hotMemoryBudgetBytes(),
                        annotation.hotMaxStalenessSeconds(),
                        annotation.hotStrict(),
                        annotation.sourceMaxRows(),
                        annotation.sourceTimeoutSeconds(),
                        annotation.warmMaxRows()
                );
        if (defaults.hotPageSize() <= 0 || defaults.hotPageSize() > 1_000
                || defaults.hotWindow() < defaults.hotPageSize()
                || defaults.hotWindow() > com.reactor.cachedb.core.query.QuerySpec.MAX_LIMIT) {
            error(repository, "@CacheRepositoryDefaults requires hotPageSize between 1 and 1000 and "
                    + "hotWindow between hotPageSize and " + com.reactor.cachedb.core.query.QuerySpec.MAX_LIMIT);
            return null;
        }
        if (defaults.hotMemoryBudgetBytes() < 0L || defaults.hotMaxStalenessSeconds() <= 0L) {
            error(repository, "@CacheRepositoryDefaults hot memory budget must not be negative and "
                    + "hotMaxStalenessSeconds must be greater than zero");
            return null;
        }
        if (defaults.sourceMaxRows() <= 0 || defaults.sourceMaxRows() > 1_000
                || defaults.sourceTimeoutSeconds() <= 0 || defaults.sourceTimeoutSeconds() > 300
                || defaults.warmMaxRows() <= 0 || defaults.warmMaxRows() > 1_000) {
            error(repository, "@CacheRepositoryDefaults requires sourceMaxRows/warmMaxRows between 1 and 1000 "
                    + "and sourceTimeoutSeconds between 1 and 300");
            return null;
        }
        return defaults;
    }

    private <T> T explicitOrDefault(
            Element element,
            Class<?> annotationType,
            String member,
            T explicitValue,
            T repositoryDefault
    ) {
        String annotationName = annotationType.getCanonicalName();
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            Element annotationElement = mirror.getAnnotationType().asElement();
            if (!(annotationElement instanceof TypeElement type)
                    || !type.getQualifiedName().contentEquals(annotationName)) {
                continue;
            }
            boolean explicitlyDeclared = mirror.getElementValues().keySet().stream()
                    .anyMatch(method -> method.getSimpleName().contentEquals(member));
            return explicitlyDeclared ? explicitValue : repositoryDefault;
        }
        return explicitValue;
    }

    private LookupMethod resolveLookupMethod(
            ExecutableElement method,
            CacheLookup annotation,
            EntityModel entity
    ) {
        TypeMirror itemType = resolveWindowItem(method, HOT_LOOKUP);
        if (itemType == null || !processingEnv.getTypeUtils().isSameType(itemType, entity.type().asType())) {
            error(method, "@CacheLookup method must return HotLookup<" + entity.typeName() + ">");
            return null;
        }
        LinkedHashMap<String, ParameterModel> parameters = parameters(method);
        String idParameter = annotation.idParameter().trim();
        ParameterModel id;
        if (idParameter.isEmpty()) {
            List<ParameterModel> candidates = parameters.values().stream()
                    .filter(parameter -> processingEnv.getTypeUtils().isSameType(
                            parameter.type(), entity.idField().type()))
                    .toList();
            if (candidates.size() != 1) {
                error(method, "@CacheLookup requires exactly one ID-compatible parameter when idParameter is omitted");
                return null;
            }
            id = candidates.get(0);
        } else {
            id = parameters.get(idParameter);
        }
        if (id == null || !processingEnv.getTypeUtils().isSameType(id.type(), entity.idField().type())) {
            error(method, "@CacheLookup idParameter must name a " + entity.idField().typeName() + " parameter");
            return null;
        }
        String relation = annotation.relation().trim();
        String relationLimitParameter = annotation.relationLimitParameter().trim();
        ParameterModel relationLimit = null;
        if (relation.isBlank()) {
            if (!relationLimitParameter.isBlank()) {
                error(method, "@CacheLookup relationLimitParameter requires relation");
                return null;
            }
        } else {
            if (!hasRelation(entity.type(), relation)) {
                error(method, "@CacheLookup relation was not found on entity: " + relation);
                return null;
            }
            if (annotation.relationLimit() <= 0 || annotation.maxRelationRows() <= 0
                    || annotation.relationLimit() > annotation.maxRelationRows()
                    || annotation.maxRelationRows() > 1_000) {
                error(method, "@CacheLookup relation limits must satisfy 1 <= relationLimit <= maxRelationRows <= 1000");
                return null;
            }
            if (!relationLimitParameter.isBlank()) {
                relationLimit = parameters.get(relationLimitParameter);
                if (relationLimit == null || !(relationLimit.typeName().equals("int")
                        || relationLimit.typeName().equals("java.lang.Integer"))) {
                    error(method, "@CacheLookup relationLimitParameter must name an int parameter");
                    return null;
                }
            } else {
                List<ParameterModel> candidates = parameters.values().stream()
                        .filter(parameter -> !parameter.name().equals(id.name()))
                        .filter(parameter -> parameter.typeName().equals("int")
                                || parameter.typeName().equals("java.lang.Integer"))
                        .toList();
                if (candidates.size() > 1) {
                    error(method, "Multiple relation limit candidates require an explicit relationLimitParameter");
                    return null;
                }
                if (candidates.size() == 1) {
                    relationLimit = candidates.get(0);
                }
            }
        }
        Set<String> consumed = new LinkedHashSet<>();
        consumed.add(id.name());
        if (relationLimit != null) consumed.add(relationLimit.name());
        if (!consumed.equals(parameters.keySet())) {
            error(method, "@CacheLookup methods may only declare id and relation-limit parameters");
            return null;
        }
        return new LookupMethod(
                method.getSimpleName().toString(),
                method.getReturnType().toString(),
                List.copyOf(parameters.values()),
                id,
                relation,
                relationLimit,
                annotation.relationLimit(),
                annotation.maxRelationRows()
        );
    }

    private boolean hasRelation(TypeElement entity, String relationName) {
        return entity.getEnclosedElements().stream()
                .anyMatch(element -> element.getKind() == ElementKind.FIELD
                        && element.getSimpleName().contentEquals(relationName)
                        && element.getAnnotation(CacheRelation.class) != null);
    }

    private CommandMethod resolveCommandMethod(
            ExecutableElement method,
            CacheCommand annotation,
            EntityModel entity
    ) {
        LinkedHashMap<String, ParameterModel> parameters = parameters(method);
        if (annotation.maxBatchSize() <= 0 || annotation.maxBatchSize() > 10_000) {
            error(method, "@CacheCommand.maxBatchSize must be between 1 and 10000");
            return null;
        }
        if (annotation.acknowledgement() == CacheCommand.Acknowledgement.SQL_DURABLE
                && annotation.durabilityTimeoutMillis() <= 0) {
            error(method, "SQL_DURABLE commands require durabilityTimeoutMillis > 0");
            return null;
        }
        ParameterModel primary;
        ParameterModel expectedVersion = null;
        switch (annotation.operation()) {
            case SAVE -> {
                primary = parameters.get(annotation.entityParameter());
                if (primary == null || !processingEnv.getTypeUtils().isSameType(primary.type(), entity.type().asType())) {
                    error(method, "SAVE command entityParameter must name a " + entity.typeName() + " parameter");
                    return null;
                }
                String expectedName = annotation.expectedVersionParameter().trim();
                if (!expectedName.isEmpty()) {
                    expectedVersion = parameters.get(expectedName);
                    if (expectedVersion == null || !(expectedVersion.typeName().equals("long")
                            || expectedVersion.typeName().equals("java.lang.Long"))) {
                        error(method, "expectedVersionParameter must name a long parameter");
                        return null;
                    }
                }
                if (!isWriteReceipt(method.getReturnType(), entity)) {
                    error(method, "SAVE command must return WriteReceipt<Entity, ID>");
                    return null;
                }
            }
            case DELETE_BY_ID -> {
                primary = parameters.get(annotation.idParameter());
                if (primary == null || !processingEnv.getTypeUtils().isSameType(primary.type(), entity.idField().type())) {
                    error(method, "DELETE_BY_ID idParameter must name a " + entity.idField().typeName() + " parameter");
                    return null;
                }
                if (!isWriteReceipt(method.getReturnType(), entity)) {
                    error(method, "DELETE_BY_ID command must return WriteReceipt<Entity, ID>");
                    return null;
                }
            }
            case SAVE_ALL -> {
                primary = parameters.get(annotation.entityParameter());
                if (primary == null || !isCollectionOf(primary.type(), entity.type().asType())) {
                    error(method, "SAVE_ALL entityParameter must name a Collection<Entity> parameter");
                    return null;
                }
                if (!isReceiptList(method.getReturnType(), entity)) {
                    error(method, "SAVE_ALL command must return List<WriteReceipt<Entity, ID>>");
                    return null;
                }
            }
            default -> throw new IllegalStateException("Unsupported command operation " + annotation.operation());
        }
        LinkedHashSet<String> consumed = new LinkedHashSet<>();
        consumed.add(primary.name());
        if (expectedVersion != null) consumed.add(expectedVersion.name());
        if (!consumed.equals(parameters.keySet())) {
            error(method, "Every @CacheCommand parameter must be referenced by its command contract");
            return null;
        }
        return new CommandMethod(
                method.getSimpleName().toString(),
                method.getReturnType().toString(),
                List.copyOf(parameters.values()),
                primary,
                expectedVersion,
                annotation.operation(),
                annotation.acknowledgement(),
                annotation.maxBatchSize(),
                annotation.durabilityTimeoutMillis()
        );
    }

    private boolean isWriteReceipt(TypeMirror type, EntityModel entity) {
        if (!(type instanceof DeclaredType declared)
                || !processingEnv.getTypeUtils().erasure(type).toString()
                .equals("com.reactor.cachedb.core.model.WriteReceipt")
                || declared.getTypeArguments().size() != 2) {
            return false;
        }
        return processingEnv.getTypeUtils().isSameType(declared.getTypeArguments().get(0), entity.type().asType())
                && processingEnv.getTypeUtils().isSameType(
                        boxed(declared.getTypeArguments().get(1)), boxed(entity.idField().type()));
    }

    private boolean isCollectionOf(TypeMirror type, TypeMirror itemType) {
        TypeElement collection = processingEnv.getElementUtils().getTypeElement("java.util.Collection");
        if (!(type instanceof DeclaredType declared)
                || collection == null
                || !processingEnv.getTypeUtils().isAssignable(
                        processingEnv.getTypeUtils().erasure(type),
                        processingEnv.getTypeUtils().erasure(collection.asType()))
                || declared.getTypeArguments().size() != 1) {
            return false;
        }
        return processingEnv.getTypeUtils().isSameType(declared.getTypeArguments().get(0), itemType);
    }

    private boolean isReceiptList(TypeMirror type, EntityModel entity) {
        TypeElement list = processingEnv.getElementUtils().getTypeElement("java.util.List");
        if (!(type instanceof DeclaredType declared)
                || list == null
                || !processingEnv.getTypeUtils().isSameType(
                        processingEnv.getTypeUtils().erasure(type),
                        processingEnv.getTypeUtils().erasure(list.asType()))
                || declared.getTypeArguments().size() != 1) {
            return false;
        }
        return isWriteReceipt(declared.getTypeArguments().get(0), entity);
    }

    private SourceSqlMethod resolveSourceSqlMethod(
            ExecutableElement method,
            SourceSql annotation,
            EntityModel entity
    ) {
        TypeMirror itemType = resolveWindowItem(method, SOURCE_WINDOW);
        if (itemType == null) {
            return null;
        }
        TypeMirror projectionType = mirroredProjection(annotation);
        ProjectionModel projection = null;
        if (!projectionType.toString().equals(Void.class.getCanonicalName())) {
            projection = resolveProjection(method, projectionType, entity);
            if (projection == null || !processingEnv.getTypeUtils().isSameType(itemType, projectionType)) {
                error(method, "Source SQL window item type must match the declared projection type");
                return null;
            }
        } else if (!processingEnv.getTypeUtils().isSameType(itemType, entity.type().asType())) {
            error(method, "Source SQL entity window item type must be " + entity.typeName());
            return null;
        }

        String sql = annotation.value() == null ? "" : annotation.value().strip();
        try {
            sql = SourceSqlValidator.requireReadOnly(sql);
        } catch (IllegalArgumentException invalidSql) {
            error(method, "@SourceSql must contain one bounded read-only SELECT/WITH statement: "
                    + invalidSql.getMessage());
            return null;
        }
        if (annotation.maxRows() <= 0 || annotation.maxRows() > 10_000) {
            error(method, "@SourceSql.maxRows must be between 1 and 10000");
            return null;
        }
        if (annotation.queryTimeoutSeconds() <= 0 || annotation.queryTimeoutSeconds() > 300) {
            error(method, "@SourceSql.queryTimeoutSeconds must be between 1 and 300");
            return null;
        }

        LinkedHashMap<String, ParameterModel> parameters = parameters(method);
        ArrayList<ParameterModel> bindings = new ArrayList<>();
        for (String name : annotation.parameters()) {
            ParameterModel parameter = parameters.get(name);
            if (parameter == null) {
                error(method, "@SourceSql parameter was not found: " + name);
                return null;
            }
            bindings.add(parameter);
        }
        if (SourceSqlValidator.placeholderCount(sql) != bindings.size()) {
            error(method, "@SourceSql placeholder count must match parameters");
            return null;
        }
        LinkedHashSet<String> boundNames = new LinkedHashSet<>();
        bindings.forEach(binding -> boundNames.add(binding.name()));
        if (!boundNames.equals(parameters.keySet())) {
            error(method, "Every @SourceSql method parameter must be referenced by @SourceSql.parameters");
            return null;
        }
        return new SourceSqlMethod(
                method.getSimpleName().toString(),
                method.getReturnType().toString(),
                List.copyOf(parameters.values()),
                List.copyOf(bindings),
                sql,
                annotation.maxRows(),
                annotation.queryTimeoutSeconds(),
                projection
        );
    }

    private boolean declaresExpectedRepositoryTypes(TypeElement repository, EntityModel entity) {
        DeclaredType declared = new RepositoryTypeResolver(processingEnv)
                .findSupertype(repository.asType(), CACHE_DB_REPOSITORY);
        if (declared == null) {
            error(repository, "@CacheRepository must extend CacheDbRepository<Entity, ID>, directly or through a base interface");
            return false;
        }
        List<? extends TypeMirror> arguments = declared.getTypeArguments();
        if (arguments.size() != 2
                || !processingEnv.getTypeUtils().isSameType(arguments.get(0), entity.type().asType())
                || !processingEnv.getTypeUtils().isSameType(arguments.get(1), entity.idField().type())) {
            error(repository, "CacheDbRepository generic types must match @CacheRepository.entity and its @CacheId");
            return false;
        }
        return true;
    }

    private EntityModel resolveEntity(TypeElement entity) {
        LinkedHashMap<String, FieldModel> fields = new LinkedHashMap<>();
        FieldModel id = null;
        if (entity.getKind() == ElementKind.RECORD) {
            for (RecordComponentElement component : entity.getRecordComponents()) {
                FieldModel field = resolveField(component, component.asType());
                if (field == null) continue;
                fields.put(field.javaName(), field);
                if (field.id()) id = uniqueId(entity, id, field);
            }
        } else {
            for (Element enclosed : entity.getEnclosedElements()) {
                if (!(enclosed instanceof VariableElement fieldElement) || enclosed.getKind() != ElementKind.FIELD) {
                    continue;
                }
                FieldModel field = resolveField(fieldElement, fieldElement.asType());
                if (field == null) continue;
                fields.put(field.javaName(), field);
                if (field.id()) id = uniqueId(entity, id, field);
            }
        }
        if (id == null) {
            error(entity, "@CacheEntity used by a repository must define one @CacheId");
            return null;
        }
        String packageName = processingEnv.getElementUtils().getPackageOf(entity).getQualifiedName().toString();
        return new EntityModel(
                entity,
                entity.getQualifiedName().toString(),
                packageName + "." + entity.getSimpleName() + "CacheBinding",
                id,
                Map.copyOf(fields)
        );
    }

    private FieldModel uniqueId(TypeElement entity, FieldModel current, FieldModel candidate) {
        if (current != null) {
            error(entity, "Only one @CacheId is supported by a repository entity");
            return current;
        }
        return candidate;
    }

    private FieldModel resolveField(Element element, TypeMirror type) {
        CacheId cacheId = element.getAnnotation(CacheId.class);
        CacheColumn cacheColumn = element.getAnnotation(CacheColumn.class);
        CacheGeneratedId generatedId = element.getAnnotation(CacheGeneratedId.class);
        if (generatedId != null && cacheId == null) {
            error(element, "@CacheGeneratedId may only be declared on @CacheId");
            return null;
        }
        if (cacheId == null && cacheColumn == null) {
            return null;
        }
        String column = cacheId != null ? cacheId.column() : cacheColumn.value();
        if (column == null || !column.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            error(element, "Persisted column must be a safe SQL identifier: " + column);
            return null;
        }
        GeneratedIdModel generated = generatedId == null ? null : resolveGeneratedId(element, type, generatedId);
        if (generatedId != null && generated == null) {
            return null;
        }
        return new FieldModel(element.getSimpleName().toString(), column, type, cacheId != null, generated);
    }

    private GeneratedIdModel resolveGeneratedId(Element element, TypeMirror type, CacheGeneratedId annotation) {
        String typeName = type.toString();
        switch (annotation.value()) {
            case UUID -> {
                if (!typeName.equals("java.util.UUID")) {
                    error(element, "@CacheGeneratedId(UUID) requires java.util.UUID");
                    return null;
                }
            }
            case ULID -> {
                if (!typeName.equals("java.lang.String")) {
                    error(element, "@CacheGeneratedId(ULID) requires java.lang.String");
                    return null;
                }
            }
            case SEQUENCE -> {
                if (!typeName.equals("java.lang.Long")) {
                    error(element, "@CacheGeneratedId(SEQUENCE) requires nullable java.lang.Long");
                    return null;
                }
                if (annotation.allocationSize() <= 0 || annotation.allocationSize() > 10_000) {
                    error(element, "@CacheGeneratedId allocationSize must be between 1 and 10000");
                    return null;
                }
                if (!annotation.sequence().isBlank()
                        && !annotation.sequence().matches("[A-Za-z0-9_.:-]+")) {
                    error(element, "@CacheGeneratedId sequence contains unsafe characters");
                    return null;
                }
            }
        }
        return new GeneratedIdModel(annotation.value(), annotation.sequence().trim(), annotation.allocationSize());
    }

    private RouteMethod resolveRouteMethod(
            ExecutableElement method,
            HotRoute hotRoute,
            SourceRoute sourceRoute,
            CacheRouteQuery query,
            EntityModel entity,
            RepositoryDefaultsModel defaults
    ) {
        RouteKind kind = hotRoute != null ? RouteKind.HOT : RouteKind.SOURCE;
        String routeName = hotRoute != null ? hotRoute.value() : sourceRoute.value();
        if (!isSafeRouteName(routeName)) {
            error(method, "Route name must match [A-Za-z0-9][A-Za-z0-9._:-]*");
            return null;
        }
        String expectedContainer = kind == RouteKind.HOT ? HOT_WINDOW : SOURCE_WINDOW;
        RouteReturn routeReturn = resolveRouteReturn(method, expectedContainer);
        if (routeReturn == null) {
            return null;
        }
        TypeMirror itemType = routeReturn.itemType();

        TypeMirror projectionType = hotRoute != null ? mirroredProjection(hotRoute) : mirroredProjection(sourceRoute);
        ProjectionModel projection = null;
        if (!projectionType.toString().equals(Void.class.getCanonicalName())) {
            projection = resolveProjection(method, projectionType, entity);
            if (projection == null) return null;
            if (!processingEnv.getTypeUtils().isSameType(itemType, projectionType)) {
                error(method, "Route window item type must match the declared projection type");
                return null;
            }
        } else if (!processingEnv.getTypeUtils().isSameType(itemType, entity.type().asType())) {
            error(method, "Entity route window item type must be " + entity.typeName());
            return null;
        }

        LinkedHashMap<String, ParameterModel> parameters = parameters(method);
        QueryModel queryModel = resolveQuery(method, query, parameters, entity);
        if (queryModel == null) return null;
        String coverageScopeParameter = hotRoute == null ? "" : hotRoute.coverageScopeParameter().trim();
        if (!coverageScopeParameter.isEmpty() && !parameters.containsKey(coverageScopeParameter)) {
            error(method, "coverageScopeParameter does not name a method parameter: " + coverageScopeParameter);
            return null;
        }
        if (!coverageScopeParameter.isEmpty()
                && !validateCoverageScope(method, queryModel, coverageScopeParameter)) {
            return null;
        }
        int hotPageSize = hotRoute == null ? 0 : explicitOrDefault(
                method, HotRoute.class, "pageSize", hotRoute.pageSize(), defaults.hotPageSize());
        int hotWindow = hotRoute == null ? 0 : explicitOrDefault(
                method, HotRoute.class, "hotWindow", hotRoute.hotWindow(), defaults.hotWindow());
        long hotMemoryBudgetBytes = hotRoute == null ? 0L : explicitOrDefault(
                method, HotRoute.class, "memoryBudgetBytes", hotRoute.memoryBudgetBytes(), defaults.hotMemoryBudgetBytes());
        long hotMaxStalenessSeconds = hotRoute == null ? 0L : explicitOrDefault(
                method, HotRoute.class, "maxStalenessSeconds", hotRoute.maxStalenessSeconds(), defaults.hotMaxStalenessSeconds());
        boolean hotStrict = hotRoute != null && explicitOrDefault(
                method, HotRoute.class, "strict", hotRoute.strict(), defaults.hotStrict());
        HotRoute.Population hotPopulation = hotRoute == null
                ? HotRoute.Population.ON_DEMAND
                : explicitOrDefault(method, HotRoute.class, "population", hotRoute.population(), defaults.hotPopulation());
        if (hotRoute != null) {
            if (hotPageSize <= 0 || hotPageSize > 1_000
                    || hotWindow < hotPageSize
                    || hotWindow > com.reactor.cachedb.core.query.QuerySpec.MAX_LIMIT) {
                error(method, "@HotRoute requires pageSize between 1 and 1000 and hotWindow between pageSize and "
                        + com.reactor.cachedb.core.query.QuerySpec.MAX_LIMIT);
                return null;
            }
            if (hotMemoryBudgetBytes < 0L) {
                error(method, "@HotRoute.memoryBudgetBytes must not be negative");
                return null;
            }
            if (hotMaxStalenessSeconds <= 0L) {
                error(method, "@HotRoute.maxStalenessSeconds must be greater than zero");
                return null;
            }
            if (routeReturn.pageReturn() && !hotStrict) {
                error(method, "A @HotRoute returning CursorPage<T> requires strict=true so partial coverage cannot be hidden");
                return null;
            }
        }
        int maxRows = sourceRoute == null
                ? hotWindow
                : explicitOrDefault(method, SourceRoute.class, "maxRows", sourceRoute.maxRows(), defaults.sourceMaxRows());
        if (maxRows <= 0 || (sourceRoute != null && maxRows > 1_000)) {
            error(method, sourceRoute == null
                    ? "Route max rows must be greater than zero"
                    : "@SourceRoute.maxRows must be between 1 and 1000");
            return null;
        }
        int queryTimeoutSeconds = sourceRoute == null ? 0 : explicitOrDefault(
                method, SourceRoute.class, "timeoutSeconds", sourceRoute.timeoutSeconds(), defaults.sourceTimeoutSeconds());
        if (sourceRoute != null && (queryTimeoutSeconds <= 0 || queryTimeoutSeconds > 300)) {
            error(method, "@SourceRoute.timeoutSeconds must be between 1 and 300");
            return null;
        }
        return new RouteMethod(
                method,
                method.getSimpleName().toString(),
                method.getReturnType().toString(),
                routeReturn.pageReturn(),
                kind,
                routeName.trim(),
                List.copyOf(parameters.values()),
                queryModel,
                projection,
                hotPageSize,
                hotWindow,
                maxRows,
                queryTimeoutSeconds,
                hotMemoryBudgetBytes,
                coverageScopeParameter,
                hotRoute == null ? 0L : Math.max(1L, hotMaxStalenessSeconds),
                hotStrict,
                hotPopulation
        );
    }

    private boolean validateCoverageScope(
            ExecutableElement method,
            QueryModel query,
            String coverageScopeParameter
    ) {
        if (query.groups().isEmpty()) {
            error(method, "coverageScopeParameter requires an EQ predicate in the route query: "
                    + coverageScopeParameter);
            return false;
        }
        String scopedField = null;
        for (List<PredicateModel> group : query.groups()) {
            List<PredicateModel> matches = group.stream()
                    .filter(predicate -> predicate.parameter().equals(coverageScopeParameter))
                    .filter(predicate -> predicate.operator() == CachePredicate.Operator.EQ)
                    .toList();
            if (matches.size() != 1) {
                error(method, "coverageScopeParameter must be used by exactly one EQ predicate in every query group: "
                        + coverageScopeParameter);
                return false;
            }
            String candidateField = matches.get(0).field().javaName();
            if (scopedField == null) {
                scopedField = candidateField;
            } else if (!scopedField.equals(candidateField)) {
                error(method, "coverageScopeParameter must constrain the same field in every query group: "
                        + coverageScopeParameter);
                return false;
            }
        }
        return true;
    }

    private QueryModel resolveQuery(
            ExecutableElement method,
            CacheRouteQuery annotation,
            Map<String, ParameterModel> parameters,
            EntityModel entity
    ) {
        LinkedHashMap<Integer, List<PredicateModel>> groups = new LinkedHashMap<>();
        for (CachePredicate predicate : annotation.predicates()) {
            FieldModel field = entity.fields().get(predicate.field());
            if (field == null) {
                error(method, "@CachePredicate field does not exist or is not persisted: " + predicate.field());
                return null;
            }
            if (predicate.group() < 0) {
                error(method, "@CachePredicate group must not be negative");
                return null;
            }
            String parameter = predicate.parameter().trim();
            List<String> constants = List.of(predicate.constants());
            if (parameter.isEmpty() && constants.isEmpty()) {
                ParameterModel inferred = parameters.get(field.javaName());
                if (inferred != null && isPredicateParameterCompatible(field, predicate.operator(), inferred)) {
                    parameter = inferred.name();
                }
            }
            if (parameter.isEmpty() == constants.isEmpty()) {
                error(method, "@CachePredicate must declare exactly one of parameter or constants for "
                        + predicate.field() + "; parameter may be omitted only when a compatible method parameter "
                        + "has the same name as the field");
                return null;
            }
            if (!parameter.isEmpty()) {
                ParameterModel resolved = parameters.get(parameter);
                if (resolved == null) {
                    error(method, "@CachePredicate parameter was not found: " + parameter);
                    return null;
                }
                if (!isPredicateParameterCompatible(field, predicate.operator(), resolved)) {
                    error(method, "@CachePredicate parameter type is incompatible with " + field.javaName()
                            + " (" + field.typeName() + "): " + resolved.typeName());
                    return null;
                }
            } else if (!areConstantsCompatible(field, predicate.constantType())) {
                error(method, "@CachePredicate constantType " + predicate.constantType()
                        + " is incompatible with " + field.javaName() + " (" + field.typeName() + ")");
                return null;
            } else if (!areConstantValuesValid(constants, predicate.constantType())) {
                error(method, "@CachePredicate contains an invalid " + predicate.constantType() + " constant");
                return null;
            }
            if (predicate.operator() != CachePredicate.Operator.IN && constants.size() > 1) {
                error(method, "Only IN predicates may declare multiple constants");
                return null;
            }
            groups.computeIfAbsent(predicate.group(), ignored -> new ArrayList<>()).add(new PredicateModel(
                    field,
                    predicate.operator(),
                    parameter,
                    constants,
                    predicate.constantType()
            ));
        }
        if (groups.size() > 1 && !annotation.explicitDisjunction()) {
            error(method, "@CacheRouteQuery predicates use multiple groups, which are ORed. "
                    + "Set explicitDisjunction=true to confirm this route widening explicitly");
            return null;
        }

        ArrayList<SortModel> sorts = new ArrayList<>();
        LinkedHashSet<String> sortedFields = new LinkedHashSet<>();
        for (CacheOrder order : annotation.orderBy()) {
            FieldModel field = entity.fields().get(order.field());
            if (field == null) {
                error(method, "@CacheOrder field does not exist or is not persisted: " + order.field());
                return null;
            }
            if (!sortedFields.add(field.javaName())) {
                error(method, "@CacheOrder field is duplicated: " + field.javaName());
                return null;
            }
            sorts.add(new SortModel(field, order.direction()));
        }
        if (!sortedFields.contains(entity.idField().javaName())) {
            CacheOrder.Direction tieBreakerDirection = sorts.isEmpty()
                    ? CacheOrder.Direction.ASC
                    : sorts.get(sorts.size() - 1).direction();
            sorts.add(new SortModel(entity.idField(), tieBreakerDirection));
        }

        String limitParameter = annotation.limitParameter().trim();
        String windowParameter = annotation.windowParameter().trim();
        if (!limitParameter.isEmpty() && !windowParameter.isEmpty()) {
            error(method, "@CacheRouteQuery cannot declare both limitParameter and windowParameter");
            return null;
        }
        if (limitParameter.isEmpty() && windowParameter.isEmpty()) {
            List<ParameterModel> windows = parameters.values().stream()
                    .filter(parameter -> processingEnv.getTypeUtils().erasure(parameter.type()).toString()
                            .equals(WINDOW_REQUEST))
                    .toList();
            if (windows.size() > 1) {
                error(method, "Multiple WindowRequest parameters require an explicit windowParameter");
                return null;
            }
            if (windows.size() == 1) {
                windowParameter = windows.get(0).name();
            } else {
                LinkedHashSet<String> predicateParameters = new LinkedHashSet<>();
                groups.values().forEach(group -> group.stream()
                        .map(PredicateModel::parameter)
                        .filter(value -> !value.isEmpty())
                        .forEach(predicateParameters::add));
                List<ParameterModel> limits = parameters.values().stream()
                        .filter(parameter -> !predicateParameters.contains(parameter.name()))
                        .filter(parameter -> parameter.typeName().equals("int")
                                || parameter.typeName().equals("java.lang.Integer"))
                        .toList();
                if (limits.size() > 1) {
                    error(method, "Multiple unused int parameters require an explicit limitParameter");
                    return null;
                }
                if (limits.size() == 1) {
                    limitParameter = limits.get(0).name();
                }
            }
        }
        if (!limitParameter.isEmpty()) {
            ParameterModel parameter = parameters.get(limitParameter);
            if (parameter == null || !(parameter.typeName().equals("int") || parameter.typeName().equals("java.lang.Integer"))) {
                error(method, "limitParameter must name an int parameter");
                return null;
            }
        }
        if (!windowParameter.isEmpty()) {
            ParameterModel parameter = parameters.get(windowParameter);
            if (parameter == null || !processingEnv.getTypeUtils().erasure(parameter.type()).toString().equals(WINDOW_REQUEST)) {
                error(method, "windowParameter must name a WindowRequest parameter");
                return null;
            }
        }
        if (limitParameter.isEmpty() && windowParameter.isEmpty() && annotation.fixedLimit() <= 0) {
            error(method, "fixedLimit must be greater than zero");
            return null;
        }
        LinkedHashSet<String> consumedParameters = new LinkedHashSet<>();
        groups.values().forEach(group -> group.stream()
                .map(PredicateModel::parameter)
                .filter(value -> !value.isEmpty())
                .forEach(consumedParameters::add));
        if (!limitParameter.isEmpty()) consumedParameters.add(limitParameter);
        if (!windowParameter.isEmpty()) consumedParameters.add(windowParameter);
        if (!consumedParameters.equals(parameters.keySet())) {
            LinkedHashSet<String> unused = new LinkedHashSet<>(parameters.keySet());
            unused.removeAll(consumedParameters);
            error(method, "Every route method parameter must be consumed by @CacheRouteQuery; unused=" + unused);
            return null;
        }
        return new QueryModel(
                groups.entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .map(entry -> List.copyOf(entry.getValue()))
                        .toList(),
                List.copyOf(sorts),
                limitParameter,
                windowParameter,
                Math.max(1, annotation.fixedLimit())
        );
    }

    private boolean isPredicateParameterCompatible(
            FieldModel field,
            CachePredicate.Operator operator,
            ParameterModel parameter
    ) {
        if (operator == CachePredicate.Operator.CONTAINS || operator == CachePredicate.Operator.STARTS_WITH) {
            return boxedTypeName(field.type()).equals(String.class.getCanonicalName())
                    && boxedTypeName(parameter.type()).equals(String.class.getCanonicalName());
        }
        if (operator != CachePredicate.Operator.IN) {
            return processingEnv.getTypeUtils().isSameType(boxed(field.type()), boxed(parameter.type()));
        }
        TypeElement collection = processingEnv.getElementUtils().getTypeElement("java.util.Collection");
        if (!(parameter.type() instanceof DeclaredType declared)
                || collection == null
                || !processingEnv.getTypeUtils().isAssignable(
                        processingEnv.getTypeUtils().erasure(parameter.type()),
                        processingEnv.getTypeUtils().erasure(collection.asType()))
                || declared.getTypeArguments().size() != 1) {
            return false;
        }
        return processingEnv.getTypeUtils().isSameType(
                boxed(field.type()),
                boxed(declared.getTypeArguments().get(0))
        );
    }

    private boolean areConstantsCompatible(FieldModel field, CachePredicate.ConstantType constantType) {
        String expected = switch (constantType) {
            case STRING -> String.class.getCanonicalName();
            case INTEGER -> Integer.class.getCanonicalName();
            case LONG -> Long.class.getCanonicalName();
            case DOUBLE -> Double.class.getCanonicalName();
            case DECIMAL -> "java.math.BigDecimal";
            case BOOLEAN -> Boolean.class.getCanonicalName();
        };
        return boxedTypeName(field.type()).equals(expected);
    }

    private boolean areConstantValuesValid(List<String> constants, CachePredicate.ConstantType type) {
        try {
            for (String constant : constants) {
                switch (type) {
                    case STRING -> {
                        if (constant == null) return false;
                    }
                    case INTEGER -> Integer.parseInt(constant);
                    case LONG -> Long.parseLong(constant);
                    case DOUBLE -> Double.parseDouble(constant);
                    case DECIMAL -> new java.math.BigDecimal(constant);
                    case BOOLEAN -> {
                        if (!"true".equalsIgnoreCase(constant) && !"false".equalsIgnoreCase(constant)) {
                            return false;
                        }
                    }
                }
            }
            return true;
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private TypeMirror boxed(TypeMirror type) {
        if (type.getKind().isPrimitive()) {
            return processingEnv.getTypeUtils().boxedClass((PrimitiveType) type).asType();
        }
        return type;
    }

    private String boxedTypeName(TypeMirror type) {
        return boxed(type).toString();
    }

    private boolean isSafeRouteName(String value) {
        return value != null && value.trim().matches("[A-Za-z0-9][A-Za-z0-9._:-]*");
    }

    private ProjectionModel resolveProjection(ExecutableElement method, TypeMirror projectionType, EntityModel entity) {
        Element projectionElement = processingEnv.getTypeUtils().asElement(projectionType);
        if (!(projectionElement instanceof TypeElement projection)) {
            error(method, "Projection must be a concrete @CacheProjectionRecord type");
            return null;
        }
        CacheProjectionRecord annotation = projection.getAnnotation(CacheProjectionRecord.class);
        if (annotation == null) {
            error(method, "Projection route type must declare @CacheProjectionRecord");
            return null;
        }
        TypeMirror source = mirroredSource(annotation);
        if (!processingEnv.getTypeUtils().isSameType(source, entity.type().asType())) {
            error(method, "Projection source does not match repository entity: " + projection.getQualifiedName());
            return null;
        }
        String packageName = processingEnv.getElementUtils().getPackageOf(projection).getQualifiedName().toString();
        String simpleName = projection.getSimpleName().toString();
        return new ProjectionModel(
                projection.getQualifiedName().toString(),
                packageName + "." + simpleName + "Projection",
                decapitalize(simpleName) + "Projection",
                decapitalize(simpleName) + "Repository"
        );
    }

    private WarmMethod resolveWarmMethod(
            ExecutableElement method,
            WarmRoute annotation,
            RepositoryDefaultsModel defaults
    ) {
        int maxRows = explicitOrDefault(
                method, WarmRoute.class, "maxRows", annotation.maxRows(), defaults.warmMaxRows());
        if (!processingEnv.getTypeUtils().erasure(method.getReturnType()).toString().equals(WARM_PLAN)) {
            error(method, "@WarmRoute method must return CacheWarmPlan");
            return null;
        }
        if (!isSafeRouteName(annotation.value()) || annotation.from().isBlank()
                || maxRows <= 0 || maxRows > 1_000) {
            error(method, "@WarmRoute requires a safe non-blank value/from and maxRows between 1 and 1000");
            return null;
        }
        if (annotation.coverageTtlSeconds() < 60L) {
            error(method, "@WarmRoute.coverageTtlSeconds must be at least 60");
            return null;
        }
        LinkedHashMap<String, ParameterModel> parameters = parameters(method);
        String scope = annotation.coverageScopeParameter().trim();
        if (!scope.isEmpty() && !parameters.containsKey(scope)) {
            error(method, "@WarmRoute coverageScopeParameter was not found: " + scope);
            return null;
        }
        String maxRowsParameter = annotation.maxRowsParameter().trim();
        ParameterModel resolvedMaxRowsParameter = null;
        if (!maxRowsParameter.isBlank()) {
            resolvedMaxRowsParameter = parameters.get(maxRowsParameter);
            if (resolvedMaxRowsParameter == null || !(resolvedMaxRowsParameter.typeName().equals("int")
                    || resolvedMaxRowsParameter.typeName().equals("java.lang.Integer"))) {
                error(method, "@WarmRoute maxRowsParameter must name an int parameter");
                return null;
            }
        }
        String targetParameter = annotation.targetParameter().trim();
        ParameterModel resolvedTargetParameter = null;
        if (!targetParameter.isBlank()) {
            if (annotation.projectionsOnly()) {
                error(method, "@WarmRoute cannot combine projectionsOnly=true with targetParameter");
                return null;
            }
            resolvedTargetParameter = parameters.get(targetParameter);
            if (resolvedTargetParameter == null || !resolvedTargetParameter.typeName().equals(WARM_TARGET)) {
                error(method, "@WarmRoute targetParameter must name a CacheWarmTarget parameter");
                return null;
            }
        }
        return new WarmMethod(
                method,
                method.getSimpleName().toString(),
                method.getReturnType().toString(),
                annotation.value().trim(),
                annotation.from().trim(),
                List.copyOf(parameters.values()),
                maxRows,
                resolvedMaxRowsParameter,
                resolvedTargetParameter,
                scope,
                Math.max(60L, annotation.coverageTtlSeconds()),
                annotation.projectionsOnly()
        );
    }

    private WarmMethod resolveWarmParameters(WarmMethod warm, RouteMethod source) {
        Set<String> required = new LinkedHashSet<>();
        for (List<PredicateModel> group : source.query().groups()) {
            for (PredicateModel predicate : group) {
                if (!predicate.parameter().isEmpty()) required.add(predicate.parameter());
            }
        }
        Map<String, ParameterModel> available = new HashMap<>();
        warm.parameters().forEach(parameter -> available.put(parameter.name(), parameter));
        for (String parameter : required) {
            ParameterModel sourceParameter = source.parameters().stream()
                    .filter(candidate -> candidate.name().equals(parameter))
                    .findFirst().orElse(null);
            ParameterModel warmParameter = available.get(parameter);
            if (sourceParameter == null || warmParameter == null
                    || !processingEnv.getTypeUtils().isSameType(sourceParameter.type(), warmParameter.type())) {
                error(warm.element(), "@WarmRoute must declare query parameter with matching type: " + parameter);
                return null;
            }
        }
        String coverageScope = warm.coverageScopeParameter().isBlank()
                ? source.coverageScopeParameter()
                : warm.coverageScopeParameter();
        if (!coverageScope.isBlank() && !required.contains(coverageScope)) {
            error(warm.element(), "@WarmRoute coverage scope must also be a source route query parameter");
            return null;
        }

        ParameterModel targetParameter = warm.targetParameter();
        if (targetParameter == null) {
            List<ParameterModel> candidates = available.values().stream()
                    .filter(parameter -> !required.contains(parameter.name()))
                    .filter(parameter -> parameter.typeName().equals(WARM_TARGET))
                    .toList();
            if (candidates.size() > 1) {
                error(warm.element(), "Multiple CacheWarmTarget parameters require an explicit targetParameter");
                return null;
            }
            if (candidates.size() == 1) {
                if (warm.projectionsOnly()) {
                    error(warm.element(), "@WarmRoute cannot combine projectionsOnly=true with a CacheWarmTarget parameter");
                    return null;
                }
                targetParameter = candidates.get(0);
            }
        }

        ParameterModel maxRowsParameter = warm.maxRowsParameter();
        if (maxRowsParameter == null) {
            ParameterModel resolvedTarget = targetParameter;
            List<ParameterModel> candidates = available.values().stream()
                    .filter(parameter -> !required.contains(parameter.name()))
                    .filter(parameter -> resolvedTarget == null || !parameter.name().equals(resolvedTarget.name()))
                    .filter(parameter -> parameter.typeName().equals("int")
                            || parameter.typeName().equals("java.lang.Integer"))
                    .toList();
            if (candidates.size() > 1) {
                error(warm.element(), "Multiple warm row-limit candidates require an explicit maxRowsParameter");
                return null;
            }
            if (candidates.size() == 1) {
                maxRowsParameter = candidates.get(0);
            }
        }
        LinkedHashSet<String> allowed = new LinkedHashSet<>(required);
        if (maxRowsParameter != null) allowed.add(maxRowsParameter.name());
        if (targetParameter != null) allowed.add(targetParameter.name());
        if (!allowed.equals(available.keySet())) {
            LinkedHashSet<String> unused = new LinkedHashSet<>(available.keySet());
            unused.removeAll(allowed);
            error(warm.element(), "Every warm method parameter must be used by the source query or maxRows; unused="
                    + unused);
            return null;
        }
        return new WarmMethod(
                warm.element(),
                warm.name(),
                warm.returnType(),
                warm.routeName(),
                warm.fromMethod(),
                warm.parameters(),
                warm.maxRows(),
                maxRowsParameter,
                targetParameter,
                coverageScope,
                warm.coverageTtlSeconds(),
                warm.projectionsOnly()
        );
    }

    private String renderImplementation(RepositoryModel model) {
        EntityModel entity = model.entity();
        String implementationName = simpleName(model.implementationQualifiedName());
        StringBuilder out = new StringBuilder(16_384);
        out.append("package ").append(model.packageName()).append(";\n\n");
        out.append("public final class ").append(implementationName).append(" implements ")
                .append(model.repositoryName()).append(" {\n");
        out.append("    private static final int MAX_BULK_SIZE = 1000;\n");
        renderRepositoryRouteCatalog(out, model);
        renderRouteConstants(out, model);
        out.append("    private final com.reactor.cachedb.starter.CacheDatabase cacheDatabase;\n");
        out.append("    private final com.reactor.cachedb.core.api.EntityRepository<")
                .append(entity.typeName()).append(", ").append(entity.idField().typeName()).append("> hotRepository;\n");
        out.append("    private final com.reactor.cachedb.core.api.SourceRepository<")
                .append(entity.typeName()).append(", ").append(entity.idField().typeName()).append("> sourceRepository;\n");
        out.append("    private final com.reactor.cachedb.core.repository.SourceSqlRepository<")
                .append(entity.typeName()).append("> sourceSqlRepository;\n");
        for (ProjectionModel projection : model.projections()) {
            out.append("    private final com.reactor.cachedb.core.projection.EntityProjection<")
                    .append(entity.typeName()).append(", ").append(projection.typeName()).append(", ")
                    .append(entity.idField().typeName()).append("> ").append(projection.fieldName()).append(";\n");
            out.append("    private final com.reactor.cachedb.core.api.ProjectionRepository<")
                    .append(projection.typeName()).append(", ").append(entity.idField().typeName()).append("> ")
                    .append(projection.repositoryFieldName()).append(";\n");
        }
        out.append('\n');
        out.append("    public ").append(implementationName)
                .append("(com.reactor.cachedb.starter.CacheDatabase cacheDatabase) {\n");
        out.append("        this.cacheDatabase = java.util.Objects.requireNonNull(cacheDatabase, \"cacheDatabase\");\n");
        out.append("        this.hotRepository = ").append(entity.bindingTypeName()).append(".repository(cacheDatabase);\n");
        out.append("        this.sourceRepository = cacheDatabase.sourceRepository(")
                .append(entity.bindingTypeName()).append(".METADATA, ").append(entity.bindingTypeName()).append(".CODEC);\n");
        out.append("        this.sourceSqlRepository = cacheDatabase.sourceSqlRepository(")
                .append(entity.bindingTypeName()).append(".CODEC);\n");
        for (ProjectionModel projection : model.projections()) {
            out.append("        this.").append(projection.fieldName()).append(" = ")
                    .append(projection.generatedTypeName()).append(".PROJECTION;\n");
            out.append("        cacheDatabase.registerProjection(").append(entity.bindingTypeName()).append(".METADATA, this.")
                    .append(projection.fieldName()).append(");\n");
            out.append("        this.").append(projection.repositoryFieldName()).append(" = hotRepository.projected(this.")
                    .append(projection.fieldName()).append(");\n");
        }
        out.append("    }\n\n");

        renderBaseMethods(out, model);
        for (LookupMethod lookup : model.lookupMethods()) {
            renderLookupMethod(out, lookup);
        }
        for (RouteMethod route : model.routes()) {
            renderRouteMethod(out, model, route);
            renderQueryBuilder(out, route);
        }
        for (SourceSqlMethod sourceSql : model.sourceSqlMethods()) {
            renderSourceSqlMethod(out, model, sourceSql);
        }
        for (CommandMethod command : model.commandMethods()) {
            renderCommandMethod(out, model, command);
        }
        for (WarmMethod warm : model.warmMethods()) {
            RouteMethod source = model.routes().stream().filter(route -> route.name().equals(warm.fromMethod())).findFirst().orElseThrow();
            renderWarmMethod(out, model, warm, source);
        }
        out.append("}\n");
        return out.toString();
    }

    private void renderLookupMethod(StringBuilder out, LookupMethod lookup) {
        out.append("    @Override\n    public ").append(lookup.returnType()).append(' ')
                .append(lookup.name()).append('(').append(renderParameters(lookup.parameters())).append(") {\n");
        if (lookup.relation().isBlank()) {
            out.append("        return hotRepository.findHotById(").append(lookup.id().name()).append(");\n");
        } else {
            String limit = lookup.relationLimit() == null
                    ? String.valueOf(lookup.fixedRelationLimit())
                    : lookup.relationLimit().name();
            out.append("        int resolvedRelationLimit = ").append(limit).append(";\n")
                    .append("        if (resolvedRelationLimit <= 0 || resolvedRelationLimit > ")
                    .append(lookup.maxRelationRows()).append(") {\n")
                    .append("            throw new IllegalArgumentException(\"Relation preview ")
                    .append(escapeJava(lookup.relation())).append(" accepts between 1 and ")
                    .append(lookup.maxRelationRows()).append(" rows\");\n        }\n")
                    .append("        return hotRepository.withRelationLimit(")
                    .append(quote(lookup.relation())).append(", resolvedRelationLimit).findHotById(")
                    .append(lookup.id().name()).append(");\n");
        }
        out.append("    }\n\n");
    }

    private void renderCommandMethod(StringBuilder out, RepositoryModel model, CommandMethod command) {
        out.append("    @Override\n    public ").append(command.returnType()).append(' ').append(command.name()).append('(')
                .append(renderParameters(command.parameters())).append(") {\n");
        if (command.operation() == CacheCommand.Operation.SAVE_ALL) {
            out.append("        if (").append(command.primary().name()).append(" != null && ")
                    .append(command.primary().name()).append(".size() > ").append(command.maxBatchSize()).append(") {\n")
                    .append("            throw new IllegalArgumentException(\"Command batch exceeds maxBatchSize=")
                    .append(command.maxBatchSize()).append("\");\n        }\n");
            renderResolvedEntities(out, model, command.primary().name(), "commandEntities", "        ");
            out.append("        java.util.List<com.reactor.cachedb.core.model.WriteReceipt<")
                    .append(model.entity().typeName()).append(", ")
                    .append(model.entity().idField().typeName()).append(">> cachedb$receipts = hotRepository.saveAll(")
                    .append("commandEntities);\n");
            if (command.acknowledgement() == CacheCommand.Acknowledgement.SQL_DURABLE) {
                out.append("        cacheDatabase.awaitDurableOrThrow(cachedb$receipts, java.time.Duration.ofMillis(")
                        .append(command.durabilityTimeoutMillis()).append("L), ")
                        .append(quote("repository command/" + model.packageName() + "."
                                + model.repositoryName() + "#" + command.name()))
                        .append(");\n");
            }
            out.append("        return cachedb$receipts;\n");
        } else {
            String invocation = command.operation() == CacheCommand.Operation.DELETE_BY_ID
                    ? "hotRepository.deleteWithReceipt(" + command.primary().name() + ")"
                    : command.expectedVersion() == null
                            ? "hotRepository.saveWithReceipt(ensureGeneratedId(" + command.primary().name() + "))"
                            : "hotRepository.save(ensureGeneratedId(" + command.primary().name() + "), "
                                    + command.expectedVersion().name() + ")";
            out.append("        ").append(command.returnType()).append(" cachedb$receipt = ").append(invocation).append(";\n");
            if (command.acknowledgement() == CacheCommand.Acknowledgement.SQL_DURABLE) {
                out.append("        java.time.Duration cachedb$timeout = java.time.Duration.ofMillis(")
                        .append(command.durabilityTimeoutMillis()).append("L);\n")
                        .append("        cacheDatabase.awaitDurableOrThrow(cachedb$receipt, cachedb$timeout);\n");
            }
            out.append("        return cachedb$receipt;\n");
        }
        out.append("    }\n\n");
    }

    private void renderSourceSqlMethod(StringBuilder out, RepositoryModel model, SourceSqlMethod method) {
        out.append("    @Override\n    public ").append(method.returnType()).append(' ').append(method.name()).append('(')
                .append(renderParameters(method.parameters())).append(") {\n");
        String bindings = method.bindings().isEmpty()
                ? "java.util.List.of()"
                : "java.util.Arrays.asList(" + method.bindings().stream()
                        .map(ParameterModel::name)
                        .reduce((left, right) -> left + ", " + right).orElse("") + ")";
        out.append("        com.reactor.cachedb.core.repository.SourceSqlQuery cachedb$query = new ")
                .append("com.reactor.cachedb.core.repository.SourceSqlQuery(")
                .append(quote(method.sql())).append(", ").append(bindings).append(", ")
                .append(method.maxRows()).append(", ").append(method.queryTimeoutSeconds()).append(");\n")
                .append("        java.util.List<").append(model.entity().typeName())
                .append("> cachedb$sourceRows = sourceSqlRepository.query(cachedb$query);\n");
        if (method.projection() == null) {
            out.append("        return new com.reactor.cachedb.core.repository.SourceWindow<>(cachedb$sourceRows, null);\n");
        } else {
            renderSourceProjectionMapping(out, model, method.projection(), "cachedb$sourceRows");
            out.append("        return new com.reactor.cachedb.core.repository.SourceWindow<>(cachedb$rows, null);\n");
        }
        out.append("    }\n\n");
    }

    private void renderBaseMethods(StringBuilder out, RepositoryModel model) {
        EntityModel entity = model.entity();
        String entityType = entity.typeName();
        String idType = entity.idField().typeName();
        out.append("    @Override\n    public com.reactor.cachedb.core.repository.HotLookup<").append(entityType)
                .append("> findHotById(").append(idType).append(" id) {\n")
                .append("        return hotRepository.findHotById(id);\n    }\n\n");
        out.append("    @Override\n    public java.util.Optional<com.reactor.cachedb.core.page.VersionedEntity<")
                .append(entityType).append(">> findVersionedHotById(").append(idType).append(" id) {\n")
                .append("        return hotRepository.findVersionedById(id);\n    }\n\n");
        out.append("    @Override\n    public java.util.Optional<com.reactor.cachedb.core.model.WriteDependency> dependency(")
                .append(idType).append(" id) {\n")
                .append("        return hotRepository.findVersionedById(id).map(versioned -> new ")
                .append("com.reactor.cachedb.core.model.WriteDependency(")
                .append(entity.bindingTypeName()).append(".METADATA.redisNamespace(), java.lang.String.valueOf(id), ")
                .append("versioned.version()));\n    }\n\n");
        out.append("    @Override\n    public java.util.Optional<").append(entityType).append("> findSourceById(")
                .append(idType).append(" id) {\n        return sourceRepository.findById(id);\n    }\n\n");
        out.append("    @Override\n    public com.reactor.cachedb.core.model.WriteReceipt<").append(entityType).append(", ")
                .append(idType).append("> save(").append(entityType).append(" entity) {\n")
                .append("        return hotRepository.saveWithReceipt(ensureGeneratedId(entity));\n    }\n\n");
        out.append("    @Override\n    public com.reactor.cachedb.core.model.WriteReceipt<").append(entityType).append(", ")
                .append(idType).append("> save(").append(entityType).append(" entity, long expectedVersion) {\n")
                .append("        return hotRepository.save(ensureGeneratedId(entity), expectedVersion);\n    }\n\n");
        out.append("    @Override\n    public com.reactor.cachedb.core.model.WriteReceipt<").append(entityType).append(", ")
                .append(idType).append("> saveAfter(").append(entityType)
                .append(" entity, com.reactor.cachedb.core.model.WriteDependency dependency) {\n")
                .append("        return hotRepository.saveAfter(ensureGeneratedId(entity), dependency);\n    }\n\n");
        out.append("    @Override\n    public java.util.List<com.reactor.cachedb.core.model.WriteReceipt<")
                .append(entityType).append(", ").append(idType).append(">> saveAll(java.util.Collection<")
                .append(entityType).append("> entities) {\n")
                .append("        if (entities != null && entities.size() > MAX_BULK_SIZE) {\n")
                .append("            throw new IllegalArgumentException(\"Repository bulk command exceeds MAX_BULK_SIZE=\" + MAX_BULK_SIZE);\n")
                .append("        }\n");
        renderResolvedEntities(out, model, "entities", "resolved", "        ");
        out.append("        return hotRepository.saveAll(resolved);\n    }\n\n");
        out.append("    @Override\n    public com.reactor.cachedb.core.model.WriteReceipt<").append(entityType).append(", ")
                .append(idType).append("> deleteById(").append(idType).append(" id) {\n")
                .append("        return hotRepository.deleteWithReceipt(id);\n    }\n\n");
        out.append("    @Override\n    public boolean isDurable(com.reactor.cachedb.core.model.WriteReceipt<?, ?> receipt) {\n")
                .append("        return cacheDatabase.isDurable(receipt);\n    }\n\n");
        out.append("    @Override\n    public boolean awaitDurable(com.reactor.cachedb.core.model.WriteReceipt<?, ?> receipt, java.time.Duration timeout) {\n")
                .append("        return cacheDatabase.awaitDurable(receipt, timeout);\n    }\n\n");
        renderGeneratedIdHelper(out, model);
    }

    private void renderGeneratedIdHelper(StringBuilder out, RepositoryModel model) {
        EntityModel entity = model.entity();
        GeneratedIdModel generated = entity.idField().generatedId();
        out.append("    private ").append(entity.typeName()).append(" ensureGeneratedId(")
                .append(entity.typeName()).append(" entity) {\n")
                .append("        java.util.Objects.requireNonNull(entity, \"entity\");\n");
        if (generated == null) {
            out.append("        return entity;\n");
        } else {
            out.append("        if (").append(entity.bindingTypeName())
                    .append(".METADATA.idAccessor().apply(entity) != null) {\n")
                    .append("            return entity;\n        }\n")
                    .append("        ").append(entity.idField().typeName()).append(" generatedId = ")
                    .append(generatedIdExpression(entity, generated)).append(";\n")
                    .append("        return ").append(entity.bindingTypeName()).append(".withId(entity, generatedId);\n");
        }
        out.append("    }\n\n");
    }

    private String generatedIdExpression(EntityModel entity, GeneratedIdModel generated) {
        return switch (generated.strategy()) {
            case UUID -> "cacheDatabase.idGenerator().nextUuid()";
            case ULID -> "cacheDatabase.idGenerator().nextUlid()";
            case SEQUENCE -> "java.lang.Long.valueOf(cacheDatabase.idGenerator().nextSequence("
                    + quote(generated.sequence().isBlank()
                    ? entity.type().getSimpleName().toString().toLowerCase(java.util.Locale.ROOT)
                    : generated.sequence())
                    + ", " + generated.allocationSize() + "))";
        };
    }

    private void renderRouteMethod(StringBuilder out, RepositoryModel model, RouteMethod route) {
        out.append("    @Override\n    public ").append(route.returnType()).append(' ').append(route.name()).append('(')
                .append(renderParameters(route.parameters())).append(") {\n");
        String window = windowExpression(route.query());
        out.append("        com.reactor.cachedb.core.repository.WindowRequest cachedb$window = ").append(window).append(";\n");
        int routeLimit = route.kind() == RouteKind.HOT ? route.pageSize() : route.maxRows();
        out.append("        if (cachedb$window.limit() > ").append(routeLimit).append(") {\n")
                .append("            throw new IllegalArgumentException(\"Route ")
                .append(escapeJava(route.routeName())).append(" accepts at most ").append(routeLimit)
                .append(" rows per request\");\n        }\n");
        out.append("        com.reactor.cachedb.core.query.QuerySpec cachedb$query = build")
                .append(capitalize(route.name())).append("Query(")
                .append(renderQueryArguments(route, "cachedb$window")).append(");\n");
        String rawType = route.projection() == null ? model.entity().typeName() : route.projection().typeName();
        String repository = route.kind() == RouteKind.HOT && route.projection() != null
                ? route.projection().repositoryFieldName()
                : (route.kind() == RouteKind.HOT ? "hotRepository" : "sourceRepository");
        if (route.kind() == RouteKind.HOT) {
            out.append("        java.util.List<").append(rawType).append("> cachedb$rows = ")
                    .append("com.reactor.cachedb.core.route.RouteCacheContext.supplyWithContract(")
                    .append(contractConstant(route)).append(", () -> ").append(repository).append(".query(cachedb$query));\n");
            out.append("        com.reactor.cachedb.core.route.RouteCoverage cachedb$coverage = cacheDatabase.routeCoverage(")
                    .append(quote(route.routeName())).append(", ").append(scopeExpression(route.coverageScopeParameter()))
                    .append(", java.time.Duration.ofSeconds(").append(route.maxStalenessSeconds()).append("L));\n");
            out.append("        return com.reactor.cachedb.core.query.KeysetPagination.hotWindow(cachedb$rows, cachedb$window, sorts")
                    .append(capitalize(route.name())).append("(), ").append(extractor(model, route))
                    .append(", cachedb$coverage, ").append(quote(route.routeName())).append(", ")
                    .append(scopeExpression(route.coverageScopeParameter())).append(")")
                    .append(route.pageReturn() ? ".completePage()" : "")
                    .append(";\n");
        } else {
            if (route.projection() == null) {
                out.append("        java.util.List<").append(rawType)
                        .append("> cachedb$rows = sourceRepository.query(cachedb$query);\n");
            } else {
                out.append("        java.util.List<").append(model.entity().typeName())
                        .append("> cachedb$sourceRows = sourceRepository.query(cachedb$query);\n");
                renderSourceProjectionMapping(out, model, route.projection(), "cachedb$sourceRows");
            }
            out.append("        return com.reactor.cachedb.core.query.KeysetPagination.sourceWindow(cachedb$rows, cachedb$window, sorts")
                    .append(capitalize(route.name())).append("(), ").append(extractor(model, route)).append(", ")
                    .append(quote(route.routeName())).append(", ")
                    .append(scopeExpression(route.coverageScopeParameter())).append(")")
                    .append(route.pageReturn() ? ".page()" : "")
                    .append(";\n");
        }
        out.append("    }\n\n");
    }

    private void renderSourceProjectionMapping(
            StringBuilder out,
            RepositoryModel model,
            ProjectionModel projection,
            String sourceRows
    ) {
        out.append("        java.util.ArrayList<").append(projection.typeName())
                .append("> cachedb$rows = new java.util.ArrayList<>(").append(sourceRows).append(".size());\n")
                .append("        java.util.function.Function<").append(model.entity().typeName()).append(", ")
                .append(projection.typeName()).append("> cachedb$projector = ")
                .append(projection.fieldName()).append(".projector();\n")
                .append("        for (").append(model.entity().typeName()).append(" cachedb$sourceRow : ")
                .append(sourceRows).append(") {\n")
                .append("            ").append(projection.typeName())
                .append(" cachedb$projected = cachedb$projector.apply(cachedb$sourceRow);\n")
                .append("            if (cachedb$projected != null) {\n")
                .append("                cachedb$rows.add(cachedb$projected);\n")
                .append("            }\n")
                .append("        }\n");
    }

    private void renderQueryBuilder(StringBuilder out, RouteMethod route) {
        String suffix = capitalize(route.name());
        out.append("    private com.reactor.cachedb.core.query.QuerySpec build").append(suffix).append("Query(")
                .append(renderBuilderParameters(route)).append(") {\n");
        out.append("        com.reactor.cachedb.core.query.QuerySpec base = ").append(renderBaseQuery(route.query()));
        if (route.queryTimeoutSeconds() > 0) {
            out.append(".withQueryTimeoutSeconds(").append(route.queryTimeoutSeconds()).append(')');
        }
        out.append(";\n")
                .append("        return com.reactor.cachedb.core.query.KeysetPagination.apply(base, window, sorts")
                .append(suffix).append("(), ").append(quote(route.routeName())).append(", ")
                .append(scopeExpression(route.coverageScopeParameter())).append(");\n    }\n\n");
        out.append("    private java.util.List<com.reactor.cachedb.core.query.QuerySort> sorts").append(suffix).append("() {\n")
                .append("        return ").append(sortsConstant(route)).append(";\n    }\n\n");
    }

    private void renderRouteConstants(StringBuilder out, RepositoryModel model) {
        for (RouteMethod route : model.routes()) {
            out.append("    private static final java.util.List<com.reactor.cachedb.core.query.QuerySort> ")
                    .append(sortsConstant(route)).append(" = java.util.List.of(");
            appendSortDefinitions(out, route);
            out.append(");\n");
            if (route.kind() == RouteKind.HOT) {
                out.append("    private static final com.reactor.cachedb.core.route.RouteCacheContract ")
                        .append(contractConstant(route)).append(" = ")
                        .append(renderContract(model, route)).append(";\n");
            }
        }
        if (!model.routes().isEmpty()) {
            out.append('\n');
        }
    }

    private void appendSortDefinitions(StringBuilder out, RouteMethod route) {
        for (int index = 0; index < route.query().sorts().size(); index++) {
            if (index > 0) out.append(", ");
            SortModel sort = route.query().sorts().get(index);
            out.append("com.reactor.cachedb.core.query.QuerySort.")
                    .append(sort.direction() == CacheOrder.Direction.DESC ? "desc" : "asc")
                    .append('(').append(quote(sort.field().columnName())).append(')');
        }
    }

    private String sortsConstant(RouteMethod route) {
        return "ROUTE_SORTS_" + route.name().replaceAll("[^A-Za-z0-9]", "_")
                .toUpperCase(java.util.Locale.ROOT);
    }

    private String contractConstant(RouteMethod route) {
        return "ROUTE_CONTRACT_" + route.name().replaceAll("[^A-Za-z0-9]", "_")
                .toUpperCase(java.util.Locale.ROOT);
    }

    private void renderResolvedEntities(
            StringBuilder out,
            RepositoryModel model,
            String source,
            String target,
            String indent
    ) {
        String entityType = model.entity().typeName();
        if (model.entity().idField().generatedId() == null) {
            out.append(indent).append("java.util.Collection<").append(entityType).append("> ")
                    .append(target).append(" = ").append(source)
                    .append(" == null ? java.util.List.of() : ").append(source).append(";\n");
            return;
        }
        out.append(indent).append("java.util.List<").append(entityType).append("> ")
                .append(target).append(";\n")
                .append(indent).append("if (").append(source).append(" == null || ").append(source)
                .append(".isEmpty()) {\n")
                .append(indent).append("    ").append(target).append(" = java.util.List.of();\n")
                .append(indent).append("} else {\n")
                .append(indent).append("    java.util.ArrayList<").append(entityType).append("> generated = new ")
                .append("java.util.ArrayList<>(").append(source).append(".size());\n")
                .append(indent).append("    for (").append(entityType).append(" entity : ").append(source).append(") {\n")
                .append(indent).append("        generated.add(ensureGeneratedId(entity));\n")
                .append(indent).append("    }\n")
                .append(indent).append("    ").append(target).append(" = generated;\n")
                .append(indent).append("}\n");
    }

    private void renderWarmMethod(StringBuilder out, RepositoryModel model, WarmMethod warm, RouteMethod source) {
        out.append("    @Override\n    public ").append(warm.returnType()).append(' ').append(warm.name()).append('(')
                .append(renderParameters(warm.parameters())).append(") {\n");
        String maxRows = warm.maxRowsParameter() == null
                ? String.valueOf(warm.maxRows())
                : warm.maxRowsParameter().name();
        out.append("        int resolvedMaxRows = ").append(maxRows).append(";\n")
                .append("        if (resolvedMaxRows <= 0 || resolvedMaxRows > ").append(warm.maxRows()).append(") {\n")
                .append("            throw new IllegalArgumentException(\"Warm route ")
                .append(escapeJava(warm.routeName())).append(" accepts between 1 and ")
                .append(warm.maxRows()).append(" rows\");\n        }\n")
                .append("        com.reactor.cachedb.core.repository.WindowRequest window = ")
                .append("com.reactor.cachedb.core.repository.WindowRequest.first(Math.min(resolvedMaxRows, 1000));\n");
        if (warm.targetParameter() != null) {
            out.append("        com.reactor.cachedb.starter.CacheWarmTarget cachedb$target = java.util.Objects.requireNonNull(")
                    .append(warm.targetParameter().name()).append(", \"target\");\n")
                    .append("        boolean cachedb$projectionsOnly = cachedb$target == ")
                    .append("com.reactor.cachedb.starter.CacheWarmTarget.PROJECTIONS_ONLY;\n");
        } else {
            out.append("        boolean cachedb$projectionsOnly = ").append(warm.projectionsOnly()).append(";\n");
        }
        out.append("        com.reactor.cachedb.core.query.QuerySpec query = build").append(capitalize(source.name()))
                .append("Query(").append(renderWarmQueryArguments(source, "window")).append(").limitTo(resolvedMaxRows);\n");
        out.append("        return com.reactor.cachedb.starter.CacheWarmPlan.builder(")
                .append(model.entity().bindingTypeName()).append(".METADATA.entityName())\n")
                .append("                .name(").append(quote(warm.routeName())).append(")\n")
                .append("                .querySpec(query)\n")
                .append("                .maxRows(resolvedMaxRows)\n")
                .append("                .forceImmediateProjectionRefresh(!cachedb$projectionsOnly)\n")
                .append("                .reindexQueryIndexes(!cachedb$projectionsOnly)\n")
                .append("                .projectionsOnly(cachedb$projectionsOnly)\n")
                .append("                .projectionName(")
                .append(source.projection() == null ? quote("") : source.projection().fieldName() + ".name()")
                .append(")\n")
                .append("                .coverage(").append(quote(source.routeName())).append(", ")
                .append(scopeExpression(effectiveWarmScope(warm, source))).append(", ")
                .append(warm.coverageTtlSeconds()).append("L)\n")
                .append("                .build();\n    }\n\n");
    }

    private String effectiveWarmScope(WarmMethod warm, RouteMethod source) {
        return warm.coverageScopeParameter().isBlank()
                ? source.coverageScopeParameter()
                : warm.coverageScopeParameter();
    }

    private String renderContract(RepositoryModel model, RouteMethod route) {
        return "com.reactor.cachedb.core.route.RouteCacheContract.builder()"
                + ".routeName(" + quote(route.routeName()) + ")"
                + ".entityName(" + model.entity().bindingTypeName() + ".METADATA.entityName())"
                + (route.projection() == null ? "" : ".projectionName("
                + route.projection().generatedTypeName() + ".PROJECTION.name()).projectionRequired(true)")
                + ".pageSize(" + route.pageSize() + ")"
                + ".hotWindow(" + route.hotWindow() + ")"
                + ".maxColdReadSize(0)"
                + ".memoryBudgetBytes(" + route.memoryBudgetBytes() + "L)"
                + ".strictMode(com.reactor.cachedb.core.route.RouteCacheStrictMode."
                + (route.strict() ? "FAIL_FAST" : "WARN") + ")"
                + ".sourceFallbackAllowed(false).build()";
    }

    private String renderBaseQuery(QueryModel query) {
        if (query.groups().isEmpty()) {
            return "com.reactor.cachedb.core.query.QuerySpec.builder().build()";
        }
        ArrayList<String> groups = new ArrayList<>();
        for (List<PredicateModel> predicates : query.groups()) {
            groups.add("com.reactor.cachedb.core.query.QueryGroup.and(" + predicates.stream()
                    .map(this::renderPredicate)
                    .reduce((left, right) -> left + ", " + right).orElse("") + ")");
        }
        String root = groups.size() == 1 ? groups.get(0)
                : "com.reactor.cachedb.core.query.QueryGroup.or(" + String.join(", ", groups) + ")";
        return "com.reactor.cachedb.core.query.QuerySpec.where(" + root + ")";
    }

    private String renderPredicate(PredicateModel predicate) {
        String method = switch (predicate.operator()) {
            case EQ -> "eq";
            case NE -> "ne";
            case GT -> "gt";
            case GTE -> "gte";
            case LT -> "lt";
            case LTE -> "lte";
            case IN -> "in";
            case CONTAINS -> "contains";
            case STARTS_WITH -> "startsWith";
        };
        String value;
        if (!predicate.parameter().isEmpty()) {
            value = predicate.parameter();
        } else if (predicate.operator() == CachePredicate.Operator.IN) {
            value = "java.util.List.of(" + predicate.constants().stream()
                    .map(item -> literal(item, predicate.constantType()))
                    .reduce((left, right) -> left + ", " + right).orElse("") + ")";
        } else {
            value = literal(predicate.constants().get(0), predicate.constantType());
        }
        return "com.reactor.cachedb.core.query.QueryFilter." + method + "("
                + quote(predicate.field().columnName()) + ", " + value + ")";
    }

    private String literal(String value, CachePredicate.ConstantType type) {
        return switch (type) {
            case STRING -> quote(value);
            case INTEGER -> "java.lang.Integer.valueOf(" + quote(value) + ")";
            case LONG -> "java.lang.Long.valueOf(" + quote(value) + ")";
            case DOUBLE -> "java.lang.Double.valueOf(" + quote(value) + ")";
            case DECIMAL -> "new java.math.BigDecimal(" + quote(value) + ")";
            case BOOLEAN -> "java.lang.Boolean.valueOf(" + quote(value) + ")";
        };
    }

    private String extractor(RepositoryModel model, RouteMethod route) {
        return route.projection() == null
                ? model.entity().bindingTypeName() + ".CODEC::toColumns"
                : route.projection().fieldName() + ".columnExtractor()";
    }

    private String renderBuilderParameters(RouteMethod route) {
        ArrayList<String> parameters = new ArrayList<>();
        requiredQueryParameters(route).forEach(parameter -> parameters.add(parameter.typeName() + " " + parameter.name()));
        parameters.add("com.reactor.cachedb.core.repository.WindowRequest window");
        return String.join(", ", parameters);
    }

    private String renderQueryArguments(RouteMethod route, String windowName) {
        ArrayList<String> arguments = new ArrayList<>();
        requiredQueryParameters(route).forEach(parameter -> arguments.add(parameter.name()));
        arguments.add(windowName);
        return String.join(", ", arguments);
    }

    private String renderWarmQueryArguments(RouteMethod source, String windowName) {
        ArrayList<String> arguments = new ArrayList<>();
        requiredQueryParameters(source).forEach(parameter -> arguments.add(parameter.name()));
        arguments.add(windowName);
        return String.join(", ", arguments);
    }

    private List<ParameterModel> requiredQueryParameters(RouteMethod route) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (List<PredicateModel> group : route.query().groups()) {
            for (PredicateModel predicate : group) {
                if (!predicate.parameter().isEmpty()) names.add(predicate.parameter());
            }
        }
        return route.parameters().stream().filter(parameter -> names.contains(parameter.name())).toList();
    }

    private String windowExpression(QueryModel query) {
        if (!query.windowParameter().isEmpty()) return query.windowParameter();
        if (!query.limitParameter().isEmpty()) {
            return "com.reactor.cachedb.core.repository.WindowRequest.first(" + query.limitParameter() + ")";
        }
        return "com.reactor.cachedb.core.repository.WindowRequest.first(" + query.fixedLimit() + ")";
    }

    private String scopeExpression(String parameter) {
        return parameter == null || parameter.isBlank() ? quote("global") : "java.lang.String.valueOf(" + parameter + ")";
    }

    private String renderSpringConfiguration(RepositoryModel model) {
        String configurationName = simpleName(model.configurationQualifiedName());
        String implementationName = simpleName(model.implementationQualifiedName());
        return "package " + model.packageName() + ";\n\n"
                + "@org.springframework.context.annotation.Configuration(proxyBeanMethods = false)\n"
                + "public class " + configurationName + " {\n"
                + "    @org.springframework.context.annotation.Bean(name = " + quote(model.springBeanName()) + ")\n"
                + "    public " + model.repositoryName() + " " + decapitalize(model.repositoryName())
                + "(com.reactor.cachedb.starter.CacheDatabase cacheDatabase) {\n"
                + "        return new " + implementationName + "(cacheDatabase);\n"
                + "    }\n"
                + "\n"
                + "    @org.springframework.context.annotation.Bean(name = "
                + quote(model.packageName() + "." + model.repositoryName() + ".routeCatalog") + ")\n"
                + "    public com.reactor.cachedb.core.route.RepositoryRouteCatalog "
                + decapitalize(model.repositoryName()) + "RouteCatalog() {\n"
                + "        return " + implementationName + ".routeCatalog();\n"
                + "    }\n"
                + "}\n";
    }

    private String renderRouteReferences(RepositoryModel model) {
        String className = model.repositoryName() + "CacheDbRoutes";
        String implementationName = simpleName(model.implementationQualifiedName());
        StringBuilder out = new StringBuilder(2_048);
        out.append("package ").append(model.packageName()).append(";\n\n")
                .append("/** Compile-time generated route references; no reflection or classpath scanning. */\n")
                .append("public final class ").append(className).append(" {\n")
                .append("    public static final com.reactor.cachedb.core.route.RepositoryRouteCatalog CATALOG = ")
                .append(implementationName).append(".routeCatalog();\n\n");
        ArrayList<String> methodNames = new ArrayList<>();
        model.routes().forEach(route -> methodNames.add(route.name()));
        model.sourceSqlMethods().forEach(route -> methodNames.add(route.name()));
        model.warmMethods().forEach(route -> methodNames.add(route.name()));
        model.lookupMethods().forEach(route -> methodNames.add(route.name()));
        model.commandMethods().forEach(route -> methodNames.add(route.name()));
        for (int index = 0; index < methodNames.size(); index++) {
            out.append("    private static final com.reactor.cachedb.core.route.RepositoryRouteRef ROUTE_")
                    .append(index).append(" = CATALOG.requireMethod(")
                    .append(quote(methodNames.get(index))).append(");\n");
        }
        if (!methodNames.isEmpty()) {
            out.append('\n');
        }
        for (int index = 0; index < methodNames.size(); index++) {
            String methodName = methodNames.get(index);
            out.append("    public static com.reactor.cachedb.core.route.RepositoryRouteRef ")
                    .append(methodName).append("() {\n")
                    .append("        return ROUTE_").append(index).append(";\n")
                    .append("    }\n\n");
        }
        out.append("    private ").append(className).append("() {\n    }\n}\n");
        return out.toString();
    }

    private void renderRepositoryRouteCatalog(StringBuilder out, RepositoryModel model) {
        ArrayList<String> definitions = new ArrayList<>();
        for (RouteMethod route : model.routes()) {
            definitions.add(routeDefinition(
                    route.name(),
                    route.kind() == RouteKind.HOT ? "HOT" : "SOURCE",
                    route.routeName(),
                    projectionNameExpression(route.projection()),
                    route.pageSize(),
                    route.maxRows(),
                    route.hotWindow(),
                    route.queryTimeoutSeconds(),
                    route.memoryBudgetBytes(),
                    !route.coverageScopeParameter().isBlank(),
                    false,
                    route.kind() == RouteKind.HOT
                            ? "strict=" + route.strict() + ";return=" + (route.pageReturn() ? "page" : "window")
                            : "bounded-source;return=" + (route.pageReturn() ? "page" : "window"),
                    route.kind() == RouteKind.HOT ? route.population().name() : "NOT_APPLICABLE"
            ));
        }
        for (SourceSqlMethod sourceSql : model.sourceSqlMethods()) {
            definitions.add(routeDefinition(
                    sourceSql.name(), "SOURCE_SQL", sourceSql.name(),
                    projectionNameExpression(sourceSql.projection()),
                    0, sourceSql.maxRows(), 0, sourceSql.queryTimeoutSeconds(), 0L,
                    false, false, "bounded-read-only", "NOT_APPLICABLE"
            ));
        }
        for (WarmMethod warm : model.warmMethods()) {
            RouteMethod source = model.routes().stream()
                    .filter(route -> route.name().equals(warm.fromMethod()))
                    .findFirst()
                    .orElseThrow();
            definitions.add(routeDefinition(
                    warm.name(), "WARM", warm.routeName(), projectionNameExpression(source.projection()),
                    0, warm.maxRows(), source.hotWindow(), 0, 0L,
                    !effectiveWarmScope(warm, source).isBlank(), warm.projectionsOnly(),
                    "from=" + warm.fromMethod(), "NOT_APPLICABLE"
            ));
        }
        for (LookupMethod lookup : model.lookupMethods()) {
            definitions.add(routeDefinition(
                    lookup.name(), "LOOKUP", lookup.name(), quote(""),
                    0, lookup.relation().isBlank() ? 1 : lookup.maxRelationRows(), 0, 0, 0L,
                    false, false,
                    lookup.relation().isBlank() ? "point" : "relation=" + lookup.relation(),
                    "NOT_APPLICABLE"
            ));
        }
        for (CommandMethod command : model.commandMethods()) {
            definitions.add(routeDefinition(
                    command.name(), "COMMAND", command.name(), quote(""),
                    0, command.maxBatchSize(), 0, 0, 0L,
                    false, false,
                    "operation=" + command.operation() + ";acknowledgement=" + command.acknowledgement(),
                    "NOT_APPLICABLE"
            ));
        }

        out.append("    private static final com.reactor.cachedb.core.route.RepositoryRouteCatalog ROUTE_CATALOG = new ")
                .append("com.reactor.cachedb.core.route.RepositoryRouteCatalog(\n")
                .append("            ").append(quote(model.packageName() + "." + model.repositoryName())).append(",\n")
                .append("            ").append(quote(model.entity().typeName())).append(",\n");
        if (definitions.isEmpty()) {
            out.append("            java.util.List.of()\n");
        } else {
            out.append("            java.util.List.of(\n                    ")
                    .append(String.join(",\n                    ", definitions))
                    .append("\n            )\n");
        }
        out.append("    );\n\n")
                .append("    public static com.reactor.cachedb.core.route.RepositoryRouteCatalog routeCatalog() {\n")
                .append("        return ROUTE_CATALOG;\n")
                .append("    }\n\n");
    }

    private String routeDefinition(
            String methodName,
            String kind,
            String routeName,
            String projectionExpression,
            int pageSize,
            int maxRows,
            int hotWindow,
            int queryTimeoutSeconds,
            long memoryBudgetBytes,
            boolean coverageScoped,
            boolean projectionsOnly,
            String detail,
            String population
    ) {
        return "new com.reactor.cachedb.core.route.RepositoryRouteDefinition("
                + quote(methodName) + ", com.reactor.cachedb.core.route.RepositoryRouteKind." + kind + ", "
                + quote(routeName) + ", " + projectionExpression + ", "
                + pageSize + ", " + maxRows + ", " + hotWindow + ", " + queryTimeoutSeconds + ", "
                + memoryBudgetBytes + "L, " + coverageScoped + ", " + projectionsOnly + ", " + quote(detail)
                + ", com.reactor.cachedb.core.route.HotRoutePopulation." + population + ")";
    }

    private String projectionNameExpression(ProjectionModel projection) {
        return projection == null
                ? quote("")
                : projection.generatedTypeName() + ".PROJECTION.name()";
    }

    private TypeMirror resolveWindowItem(ExecutableElement method, String expectedContainer) {
        if (!(method.getReturnType() instanceof DeclaredType declared)
                || !processingEnv.getTypeUtils().erasure(declared).toString().equals(expectedContainer)
                || declared.getTypeArguments().size() != 1) {
            error(method, "Route method must return " + simpleName(expectedContainer) + "<T>");
            return null;
        }
        return declared.getTypeArguments().get(0);
    }

    private RouteReturn resolveRouteReturn(ExecutableElement method, String expectedContainer) {
        if (!(method.getReturnType() instanceof DeclaredType declared)
                || declared.getTypeArguments().size() != 1) {
            error(method, "Route method must return " + simpleName(expectedContainer) + "<T> or CursorPage<T>");
            return null;
        }
        String container = processingEnv.getTypeUtils().erasure(declared).toString();
        if (!container.equals(expectedContainer) && !container.equals(CURSOR_PAGE)) {
            error(method, "Route method must return " + simpleName(expectedContainer) + "<T> or CursorPage<T>");
            return null;
        }
        return new RouteReturn(declared.getTypeArguments().get(0), container.equals(CURSOR_PAGE));
    }

    private LinkedHashMap<String, ParameterModel> parameters(ExecutableElement method) {
        LinkedHashMap<String, ParameterModel> parameters = new LinkedHashMap<>();
        for (VariableElement parameter : method.getParameters()) {
            String name = parameter.getSimpleName().toString();
            parameters.put(name, new ParameterModel(name, parameter.asType().toString(), parameter.asType()));
        }
        return parameters;
    }

    private String renderParameters(List<ParameterModel> parameters) {
        return parameters.stream().map(parameter -> parameter.typeName() + " " + parameter.name())
                .reduce((left, right) -> left + ", " + right).orElse("");
    }

    private TypeMirror mirroredType(CacheRepository annotation) {
        try {
            annotation.entity();
            throw new IllegalStateException("Compiler did not expose @CacheRepository.entity as a type mirror");
        } catch (MirroredTypeException exception) {
            return exception.getTypeMirror();
        }
    }

    private TypeMirror mirroredProjection(HotRoute annotation) {
        try {
            annotation.projection();
            throw new IllegalStateException("Compiler did not expose @HotRoute.projection as a type mirror");
        } catch (MirroredTypeException exception) {
            return exception.getTypeMirror();
        }
    }

    private TypeMirror mirroredProjection(SourceRoute annotation) {
        try {
            annotation.projection();
            throw new IllegalStateException("Compiler did not expose @SourceRoute.projection as a type mirror");
        } catch (MirroredTypeException exception) {
            return exception.getTypeMirror();
        }
    }

    private TypeMirror mirroredProjection(SourceSql annotation) {
        try {
            annotation.projection();
            throw new IllegalStateException("Compiler did not expose @SourceSql.projection as a type mirror");
        } catch (MirroredTypeException exception) {
            return exception.getTypeMirror();
        }
    }

    private TypeMirror mirroredSource(CacheProjectionRecord annotation) {
        try {
            annotation.source();
            throw new IllegalStateException("Compiler did not expose @CacheProjectionRecord.source as a type mirror");
        } catch (MirroredTypeException exception) {
            return exception.getTypeMirror();
        }
    }

    private void writeSource(String qualifiedName, String source, Element origin) {
        try {
            JavaFileObject file = processingEnv.getFiler().createSourceFile(qualifiedName, origin);
            try (Writer writer = file.openWriter()) {
                writer.write(source);
            }
        } catch (IOException exception) {
            error(origin, "Could not generate " + qualifiedName + ": " + exception.getMessage());
        }
    }

    private void error(Element element, String message) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "[CacheDB] " + message, element);
    }

    private static String quote(String value) {
        String safe = value == null ? "" : value;
        return '"' + safe.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private static String escapeJava(String value) {
        String quoted = quote(value);
        return quoted.substring(1, quoted.length() - 1);
    }

    private static String simpleName(String qualifiedName) {
        int separator = qualifiedName.lastIndexOf('.');
        return separator < 0 ? qualifiedName : qualifiedName.substring(separator + 1);
    }

    private static String capitalize(String value) {
        return value.isEmpty() ? value : Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static String decapitalize(String value) {
        return value.isEmpty() ? value : Character.toLowerCase(value.charAt(0)) + value.substring(1);
    }

    private enum RouteKind { HOT, SOURCE }

    private record FieldModel(
            String javaName,
            String columnName,
            TypeMirror type,
            boolean id,
            GeneratedIdModel generatedId
    ) {
        String typeName() { return type.toString(); }
    }

    private record GeneratedIdModel(
            CacheGeneratedId.Strategy strategy,
            String sequence,
            int allocationSize
    ) {
    }

    private record EntityModel(
            TypeElement type,
            String typeName,
            String bindingTypeName,
            FieldModel idField,
            Map<String, FieldModel> fields
    ) {
    }

    private record ParameterModel(String name, String typeName, TypeMirror type) {
    }

    private record PredicateModel(
            FieldModel field,
            CachePredicate.Operator operator,
            String parameter,
            List<String> constants,
            CachePredicate.ConstantType constantType
    ) {
    }

    private record SortModel(FieldModel field, CacheOrder.Direction direction) {
    }

    private record QueryModel(
            List<List<PredicateModel>> groups,
            List<SortModel> sorts,
            String limitParameter,
            String windowParameter,
            int fixedLimit
    ) {
    }

    private record ProjectionModel(
            String typeName,
            String generatedTypeName,
            String fieldName,
            String repositoryFieldName
    ) {
    }

    private record RouteMethod(
            ExecutableElement element,
            String name,
            String returnType,
            boolean pageReturn,
            RouteKind kind,
            String routeName,
            List<ParameterModel> parameters,
            QueryModel query,
            ProjectionModel projection,
            int pageSize,
            int hotWindow,
            int maxRows,
            int queryTimeoutSeconds,
            long memoryBudgetBytes,
            String coverageScopeParameter,
            long maxStalenessSeconds,
            boolean strict,
            HotRoute.Population population
    ) {
    }

    private record RouteReturn(TypeMirror itemType, boolean pageReturn) {
    }

    private record WarmMethod(
            ExecutableElement element,
            String name,
            String returnType,
            String routeName,
            String fromMethod,
            List<ParameterModel> parameters,
            int maxRows,
            ParameterModel maxRowsParameter,
            ParameterModel targetParameter,
            String coverageScopeParameter,
            long coverageTtlSeconds,
            boolean projectionsOnly
    ) {
    }

    private record LookupMethod(
            String name,
            String returnType,
            List<ParameterModel> parameters,
            ParameterModel id,
            String relation,
            ParameterModel relationLimit,
            int fixedRelationLimit,
            int maxRelationRows
    ) {
    }

    private record SourceSqlMethod(
            String name,
            String returnType,
            List<ParameterModel> parameters,
            List<ParameterModel> bindings,
            String sql,
            int maxRows,
            int queryTimeoutSeconds,
            ProjectionModel projection
    ) {
    }

    private record CommandMethod(
            String name,
            String returnType,
            List<ParameterModel> parameters,
            ParameterModel primary,
            ParameterModel expectedVersion,
            CacheCommand.Operation operation,
            CacheCommand.Acknowledgement acknowledgement,
            int maxBatchSize,
            long durabilityTimeoutMillis
    ) {
    }

    private record RepositoryDefaultsModel(
            HotRoute.Population hotPopulation,
            int hotPageSize,
            int hotWindow,
            long hotMemoryBudgetBytes,
            long hotMaxStalenessSeconds,
            boolean hotStrict,
            int sourceMaxRows,
            int sourceTimeoutSeconds,
            int warmMaxRows
    ) {
        private static RepositoryDefaultsModel standard() {
            return new RepositoryDefaultsModel(
                    HotRoute.Population.ON_DEMAND,
                    100,
                    1_000,
                    0L,
                    300L,
                    true,
                    500,
                    30,
                    1_000
            );
        }
    }

    private record RepositoryModel(
            String packageName,
            String repositoryName,
            String implementationQualifiedName,
            String configurationQualifiedName,
            boolean springBean,
            String springBeanName,
            EntityModel entity,
            List<RouteMethod> routes,
            List<SourceSqlMethod> sourceSqlMethods,
            List<CommandMethod> commandMethods,
            List<WarmMethod> warmMethods,
            List<LookupMethod> lookupMethods,
            List<ProjectionModel> projections
    ) {
    }
}
