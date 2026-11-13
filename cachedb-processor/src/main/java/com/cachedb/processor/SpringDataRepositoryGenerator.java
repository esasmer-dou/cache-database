package com.reactor.cachedb.processor;

import com.reactor.cachedb.annotations.*;
import java.io.*;
import java.util.*;
import java.util.regex.Pattern;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.*;
import javax.lang.model.type.*;
import javax.tools.Diagnostic;

/** Optional-module dispatch and derivation, using only javac's compile-time type model. */
final class SpringDataRepositoryGenerator {
    private static final String BASE = "com.reactor.cachedb.spring.data.CacheDbSpringDataRepository";
    private static final String DOMAIN = "org.springframework.data.domain.";
    private static final String QUERY = "com.reactor.cachedb.core.query.";
    private static final String ASSEMBLY = "com.reactor.cachedb.spring.data.SpringDataQueries.";
    private static final Map<String, String> OPS;
    static {
        var ops = new LinkedHashMap<String, String>();
        for (String name : List.of("GreaterThanEqual", "LessThanEqual", "GreaterThan", "LessThan", "StartingWith",
                "IsNotNull", "IsNull", "IsEquals", "Equals", "IsBetween", "Between", "IsNotIn", "NotIn",
                "IsTrue", "IsFalse", "True", "False", "IsNot", "Not", "IsIn", "In", "Is")) {
            String op = switch (name) {
                case "GreaterThanEqual" -> "gte"; case "LessThanEqual" -> "lte";
                case "GreaterThan" -> "gt"; case "LessThan" -> "lt"; case "StartingWith" -> "startsWith";
                case "IsNotNull" -> "notNull"; case "IsNull" -> "null";
                case "IsBetween", "Between" -> "between"; case "IsNotIn", "NotIn" -> "notIn";
                case "IsTrue", "True" -> "true"; case "IsFalse", "False" -> "false";
                case "IsNot", "Not" -> "ne"; case "IsIn", "In" -> "in"; default -> "eq";
            };
            ops.put(name, op);
        }
        OPS = Collections.unmodifiableMap(ops);
    }

    private final ProcessingEnvironment env;
    private final Map<String, String> beanOwners;
    private final RepositoryTypeResolver resolver;
    private record Field(Element element, String property, String column, TypeMirror type, boolean id) {}
    private record Parameter(String name, TypeMirror type) {}
    private record Method(ExecutableElement element, ExecutableType type, String body) {}
    private enum Shape { LIST, OPTIONAL, ENTITY, PAGE, SLICE, WINDOW, COUNT, EXISTS, VOID }

    SpringDataRepositoryGenerator(ProcessingEnvironment env, Map<String, String> beanOwners) {
        this.env = env; this.beanOwners = beanOwners; this.resolver = new RepositoryTypeResolver(env);
    }

    void generate(TypeElement repository) {
        try { generateChecked(repository); }
        catch (Invalid invalid) { env.getMessager().printMessage(Diagnostic.Kind.ERROR, invalid.getMessage(), invalid.element); }
        catch (IOException failure) { env.getMessager().printMessage(Diagnostic.Kind.ERROR, "Cannot write Spring Data implementation: " + failure.getMessage(), repository); }
    }

    private void generateChecked(TypeElement repository) throws IOException {
        require(repository, repository.getNestingKind() == NestingKind.TOP_LEVEL && repository.getTypeParameters().isEmpty(),
                "Concrete Spring Data repositories must be top-level and have resolved generic types");
        require(repository, resolver.findSupertype(repository.asType(), "com.reactor.cachedb.core.repository.CacheDbRepository") == null,
                "Do not mix CacheDbRepository and CacheDbSpringDataRepository in one interface");
        var declared = resolver.findSupertype(repository.asType(), BASE);
        require(repository, declared != null && declared.getTypeArguments().size() == 2, "Resolve Spring Data entity and ID types");
        TypeMirror entityType = declared.getTypeArguments().get(0), idType = declared.getTypeArguments().get(1);
        var entity = env.getTypeUtils().asElement(entityType);
        require(repository, entity instanceof TypeElement && entity.getAnnotation(CacheEntity.class) != null, "Spring Data entity must be @CacheEntity");
        TypeElement entityElement = (TypeElement) entity;
        require(repository, env.getTypeUtils().isSameType(annotationEntity(repository), entityType), "@CacheRepository.entity differs from Spring Data generic entity");
        var fields = fields(entityElement);
        var id = fields.stream().filter(Field::id).toList();
        require(repository, id.size() == 1 && same(id.get(0).type(), idType), "Spring Data ID must match the single boxed @CacheId type");
        var prefixes = strings(annotation(repository, "com.reactor.cachedb.spring.data.CacheDbSpringDataOptions"), "prefixIndexedColumns");
        var indexed = new LinkedHashSet<String>(List.of(entityElement.getAnnotation(CacheEntity.class).indexedColumns()));
        for (var mirror : entityElement.getAnnotationMirrors()) collectComposite(mirror, indexed);
        for (String prefix : prefixes) {
            var field = fields.stream().filter(f -> f.property().equals(prefix)).findFirst().orElse(null);
            require(repository, field != null && field.type().toString().equals("java.lang.String")
                    && (indexed.contains("*") || indexed.contains(field.property()) || indexed.contains(field.column())),
                    "Prefix index contract must name an indexed String property: " + prefix);
        }
        String pkg = env.getElementUtils().getPackageOf(repository).getQualifiedName().toString();
        require(repository, !pkg.isBlank(), "Spring Data repositories require a named package");
        String entityPkg = env.getElementUtils().getPackageOf(entityElement).getQualifiedName().toString();
        String binding = entityPkg + "." + entityElement.getSimpleName() + "CacheBinding";
        String impl = repository.getSimpleName() + "CacheDbImplementation";
        String config = repository.getSimpleName() + "CacheDbConfiguration";
        var methods = new ArrayList<Method>();
        var seen = new HashSet<String>();
        var crud = new HashSet<String>();
        for (var member : env.getElementUtils().getAllMembers((TypeElement) declared.asElement())) {
            if (member instanceof ExecutableElement method && method.getModifiers().contains(Modifier.ABSTRACT))
                crud.add(signature(method, (ExecutableType) env.getTypeUtils().asMemberOf(declared, method)));
        }
        boolean[] usesPrefix = {false};
        for (var member : env.getElementUtils().getAllMembers(repository)) {
            if (!(member instanceof ExecutableElement method) || !method.getModifiers().contains(Modifier.ABSTRACT)
                    || method.getModifiers().contains(Modifier.STATIC)) continue;
            var type = (ExecutableType) env.getTypeUtils().asMemberOf((DeclaredType) repository.asType(), method);
            String signature = signature(method, type);
            if (!seen.add(signature) || crud.contains(signature)) continue;
            require(method, methods.size() < 256, "At most 256 derived methods per repository");
            methods.add(new Method(method, type, derive(method, type, entityType, fields, prefixes, usesPrefix,
                    repository.getQualifiedName() + "#" + signature)));
        }
        var annotation = repository.getAnnotation(CacheRepository.class);
        String bean = annotation.springBeanName().isBlank() ? repository.getQualifiedName().toString() : annotation.springBeanName();
        if (annotation.springBean()) {
            require(repository, env.getElementUtils().getTypeElement("org.springframework.context.annotation.Configuration") != null
                    && env.getElementUtils().getTypeElement("com.reactor.cachedb.starter.CacheDatabase") != null,
                    "springBean=true requires cachedb-spring-boot-starter (or Spring Context and cachedb-starter)");
            String old = beanOwners.putIfAbsent(bean, repository.getQualifiedName().toString());
            require(repository, old == null || old.equals(repository.getQualifiedName().toString()), "Duplicate Spring repository bean name: " + bean);
        }
        var out = new StringBuilder("package " + pkg + ";\n\npublic final class " + impl
                + " extends com.reactor.cachedb.spring.data.AbstractCacheDbSpringDataRepository<" + entityType + ", " + idType
                + "> implements " + repository.getQualifiedName() + " {\n");
        out.append("  public ").append(impl).append("(com.reactor.cachedb.core.api.CacheSession session) { this(session, false); }\n")
                .append("  public ").append(impl).append("(com.reactor.cachedb.core.api.CacheSession session, boolean prefixIndexesEnabled) {\n")
                .append("    super(").append(usesPrefix[0] ? "requirePrefixIndexes(session, prefixIndexesEnabled)" : "session")
                .append(", ").append(binding).append(".METADATA, ").append(binding).append(".CODEC, java.util.Map.ofEntries(\n");
        var sortable = fields.stream().filter(this::queryable).toList();
        for (int i = 0; i < sortable.size(); i++) {
            var field = sortable.get(i);
            out.append("      java.util.Map.entry(").append(quote(field.property())).append(", ").append(quote(field.column())).append(")")
                    .append(i == sortable.size() - 1 ? "\n" : ",\n");
        }
        out.append("    ));\n");
        out.append("  }\n");
        generatedId(out, id.get(0), entityType.toString(), binding, entityElement);
        for (var method : methods) {
            out.append("  @Override public ").append(method.type().getReturnType()).append(" ").append(method.element().getSimpleName()).append("(");
            for (int i = 0; i < method.type().getParameterTypes().size(); i++) {
                if (i > 0) out.append(", ");
                out.append(method.type().getParameterTypes().get(i)).append(" p").append(i);
            }
            out.append(") {\n").append(method.body()).append("  }\n");
        }
        out.append("}\n");
        write(pkg + "." + impl, out.toString(), repository);
        if (annotation.springBean()) write(pkg + "." + config,
                "package " + pkg + ";\n@org.springframework.context.annotation.Configuration(proxyBeanMethods=false)\npublic class " + config + " {\n"
                + "  @org.springframework.context.annotation.Bean(name=" + quote(bean) + ")\n  public " + repository.getQualifiedName()
                + " repository(com.reactor.cachedb.core.api.CacheSession database) {\n"
                + "    return new " + impl + "(database, database instanceof com.reactor.cachedb.starter.CacheDatabase runtime"
                + " && runtime.config().queryIndex().prefixIndexEnabled());\n  }\n}\n", repository);
    }

    private String derive(ExecutableElement method, ExecutableType type, TypeMirror entity, List<Field> fields,
            Set<String> prefixes, boolean[] usesPrefix, String route) {
        require(method, method.getTypeParameters().isEmpty() && method.getThrownTypes().isEmpty(),
                "Derived methods cannot declare generic method parameters or checked throws");
        String name = method.getSimpleName().toString();
        require(method, name.length() <= 1024 && type.getParameterTypes().size() <= 36, "Derived method exceeds name/parameter budget");
        String action, tail; int top = 0;
        if (name.startsWith("countBy")) { action = "count"; tail = name.substring(7); }
        else if (name.startsWith("existsBy")) { action = "exists"; tail = name.substring(8); }
        else if (name.startsWith("deleteBy")) { action = "delete"; tail = name.substring(8); }
        else {
            var matcher = Pattern.compile("^(find|read|get|query)(All)?(?:(Top|First)([0-9]*))?(.*)$").matcher(name);
            require(method, matcher.matches(), "Unsupported query prefix; use find/read/get/query/count/exists/delete");
            action = "read"; tail = matcher.group(5);
            if (matcher.group(3) != null) {
                try { top = matcher.group(4).isEmpty() ? 1 : Integer.parseInt(matcher.group(4)); }
                catch (NumberFormatException invalid) { fail(method, "Top/First limit is not a bounded integer"); }
                require(method, top > 0 && top <= 100_000, "Top/First limit must be 1..100000");
            }
            if (tail.startsWith("By")) tail = tail.substring(2);
            else require(method, tail.isEmpty() && top > 0, "Derived reads require By predicates or Top/First");
        }
        int order = tail.indexOf("OrderBy");
        String orderBy = order < 0 ? "" : tail.substring(order + 7);
        String predicate = order < 0 ? tail : tail.substring(0, order);
        var specials = new LinkedHashMap<String, String>();
        var parameters = new ArrayList<Parameter>();
        var nullChecks = new StringBuilder();
        for (int i = 0; i < type.getParameterTypes().size(); i++) {
            TypeMirror parameter = type.getParameterTypes().get(i);
            String erased = env.getTypeUtils().erasure(parameter).toString();
            String special = switch (erased) {
                case DOMAIN + "Pageable" -> "pageable"; case DOMAIN + "Sort" -> "sort";
                case DOMAIN + "Limit" -> "limit"; case DOMAIN + "ScrollPosition", DOMAIN + "KeysetScrollPosition" -> "scroll";
                default -> null;
            };
            if (special == null) parameters.add(new Parameter("p" + i, parameter));
            else {
                require(method, specials.putIfAbsent(special, "p" + i) == null, "Duplicate special parameter: " + special);
                nullChecks.append("    java.util.Objects.requireNonNull(p").append(i).append(", \"").append(special).append("\");\n");
            }
        }
        require(method, parameters.size() <= 32, "At most 32 derived operands");
        require(method, !specials.containsKey("pageable") || !specials.containsKey("sort") && !specials.containsKey("limit"),
                "Pageable cannot be combined with Sort or Limit");
        require(method, top == 0 || !specials.containsKey("limit"), "Top/First cannot be combined with Limit");
        var nodes = new ArrayList<String>(); int position = 0;
        while (!predicate.isEmpty()) {
            require(method, nodes.size() < 32, "At most 32 derived predicates");
            var field = property(method, predicate, fields);
            require(method, queryable(field), "Derived predicates require a scalar persisted property: " + field.property());
            String rest = predicate.substring(cap(field.property()).length());
            String op = "eq"; String consumed = "";
            for (var candidate : OPS.entrySet()) if (rest.startsWith(candidate.getKey())) {
                String after = rest.substring(candidate.getKey().length());
                if (after.isEmpty() || after.startsWith("And") || after.startsWith("Or")) { op = candidate.getValue(); consumed = candidate.getKey(); break; }
            }
            rest = rest.substring(consumed.length());
            require(method, rest.isEmpty() || rest.startsWith("And"), "Unsupported keyword or Or (only And is supported): " + rest);
            var encrypted = field.element().getAnnotation(CacheEncrypted.class);
            require(method, encrypted == null || encrypted.blindIndex() && op.equals("eq"),
                    "Encrypted derived predicates require blindIndex=true and EQ only");
            int arity = Set.of("true", "false", "null", "notNull").contains(op) ? 0 : op.equals("between") ? 2 : 1;
            require(method, position + arity <= parameters.size(), "Not enough operands for " + field.property() + " " + op);
            var args = new ArrayList<String>();
            for (int i = 0; i < arity; i++) {
                var parameter = parameters.get(position++);
                boolean collection = op.equals("in") || op.equals("notIn");
                if (collection) {
                    var iterable = resolver.findSupertype(parameter.type(), "java.lang.Iterable");
                    require(method, iterable != null && iterable.getTypeArguments().size() == 1
                            && same(upper(iterable.getTypeArguments().get(0)), field.type()), "In/NotIn requires Iterable of the property's boxed type");
                } else require(method, same(parameter.type(), field.type()), "Operand type differs from property " + field.property());
                String operand = parameter.name();
                if (enumField(field)) operand = collection ? ASSEMBLY + "enumValues(" + operand + ")"
                        : "(" + operand + " == null ? null : " + operand + ".name())";
                args.add(operand);
            }
            if (op.equals("true") || op.equals("false")) require(method, boxed(field.type()).toString().equals("java.lang.Boolean"), "True/False requires Boolean property");
            if (op.equals("startsWith")) {
                require(method, prefixes.contains(field.property()), "StartingWith requires @CacheDbSpringDataOptions(prefixIndexedColumns=...)");
                usesPrefix[0] = true;
            }
            String column = quote(field.column());
            String expression = switch (op) {
                case "null" -> QUERY + "QueryFilter.eq(" + column + ", null)";
                case "notNull" -> QUERY + "QueryFilter.ne(" + column + ", null)";
                case "true", "false" -> QUERY + "QueryFilter.eq(" + column + ", " + op + ")";
                case "between" -> ASSEMBLY + "between(" + column + ", " + args.get(0) + ", " + args.get(1) + ")";
                case "notIn" -> ASSEMBLY + "notIn(" + column + ", " + args.get(0) + ")";
                case "in" -> QUERY + "QueryFilter.in(" + column + ", " + ASSEMBLY + "values(" + args.get(0) + "))";
                default -> QUERY + "QueryFilter." + op + "(" + column + ", " + args.get(0) + ")";
            };
            nodes.add(expression);
            predicate = rest.isEmpty() ? "" : rest.substring(3);
            require(method, rest.isEmpty() || !predicate.isEmpty(), "And requires a following property");
        }
        require(method, position == parameters.size(), "Every non-special parameter must bind a derived predicate");
        var sorts = new ArrayList<String>(); var sorted = new HashSet<String>();
        while (!orderBy.isEmpty()) {
            require(method, sorts.size() < 15, "At most 15 explicit sort fields (plus ID)");
            var field = property(method, orderBy, fields);
            require(method, queryable(field), "OrderBy requires a scalar persisted property: " + field.property());
            require(method, field.element().getAnnotation(CacheEncrypted.class) == null, "Encrypted properties cannot be sorted");
            orderBy = orderBy.substring(cap(field.property()).length());
            String direction = "asc";
            if (orderBy.startsWith("Desc")) { direction = "desc"; orderBy = orderBy.substring(4); }
            else if (orderBy.startsWith("Asc")) orderBy = orderBy.substring(3);
            require(method, sorted.add(field.column()), "Duplicate OrderBy property");
            sorts.add(QUERY + "QuerySort." + direction + "(" + quote(field.column()) + ")");
        }
        Shape shape = shape(method, type.getReturnType(), entity);
        String pageable = specials.getOrDefault("pageable", "null"), sort = specials.getOrDefault("sort", "null");
        String limit = specials.getOrDefault("limit", "null"), scroll = specials.getOrDefault("scroll", "null");
        require(method, !Set.of("count", "exists", "delete").contains(action) || specials.isEmpty() && sorts.isEmpty(),
                "Count/exists/delete do not accept paging, sorting, scrolling or limits");
        require(method, !action.equals("count") || shape == Shape.COUNT, "countBy returns long");
        require(method, !action.equals("exists") || shape == Shape.EXISTS, "existsBy returns boolean");
        require(method, !action.equals("delete") || Set.of(Shape.COUNT, Shape.VOID, Shape.LIST).contains(shape), "deleteBy returns long, void or List<Entity>");
        require(method, !action.equals("read") || Set.of(Shape.LIST, Shape.OPTIONAL, Shape.ENTITY, Shape.PAGE, Shape.SLICE, Shape.WINDOW).contains(shape),
                "Read return type must be Entity, Optional, List, Page, Slice or Window; Stream is unsupported");
        require(method, shape != Shape.WINDOW || specials.containsKey("scroll") && !specials.containsKey("pageable"), "Window requires a keyset ScrollPosition and no Pageable");
        require(method, shape != Shape.WINDOW || top <= 1000, "Window Top/First supports at most 1000 rows");
        require(method, !specials.containsKey("scroll") || shape == Shape.WINDOW, "ScrollPosition is only supported with Window");
        require(method, shape != Shape.PAGE && shape != Shape.SLICE || specials.containsKey("pageable"), "Page/Slice requires Pageable");
        require(method, shape != Shape.ENTITY && shape != Shape.OPTIONAL || !specials.containsKey("pageable") && !specials.containsKey("limit") && (top == 0 || top == 1),
                "Single results support Sort and Top/First1, not Pageable/Limit/TopN>1");
        String query = QUERY + "QuerySpec.allOf(" + String.join(", ", nodes) + ")";
        if (!sorts.isEmpty()) query += ".orderBy(" + String.join(", ", sorts) + ")";
        StringBuilder body = new StringBuilder(nullChecks).append("    var query = ").append(query).append(";\n");
        if (action.equals("count")) return body + "    return countMatching(query);\n";
        if (action.equals("exists")) return body + "    return existsMatching(query);\n";
        if (action.equals("delete")) return body + "    " + (shape == Shape.LIST ? "return deleteMatching(query);\n" : shape == Shape.VOID ? "deleteMatching(query);\n" : "return (long) deleteMatching(query).size();\n");
        String result = switch (shape) {
            case LIST -> "list(query, " + pageable + ", " + sort + ", " + limit + ", " + top + ")";
            case PAGE -> "page(query, " + pageable + ", " + sort + ", " + limit + ", " + top + ")";
            case SLICE -> "slice(query, " + pageable + ", " + sort + ", " + limit + ", " + top + ")";
            case WINDOW -> "window(query, " + scroll + ", " + sort + ", " + limit + ", " + top + ", " + quote(route) + ")";
            case OPTIONAL -> "java.util.Optional.ofNullable(single(query, " + sort + ", " + (top == 1) + "))";
            case ENTITY -> "single(query, " + sort + ", " + (top == 1) + ")";
            default -> throw new AssertionError(shape);
        };
        return body + "    return " + result + ";\n";
    }

    private Shape shape(Element method, TypeMirror result, TypeMirror entity) {
        if (env.getTypeUtils().isSameType(result, entity)) return Shape.ENTITY;
        String erased = env.getTypeUtils().erasure(result).toString();
        if (erased.equals("long") || erased.equals("java.lang.Long")) return Shape.COUNT;
        if (erased.equals("boolean") || erased.equals("java.lang.Boolean")) return Shape.EXISTS;
        if (erased.equals("void")) return Shape.VOID;
        var shapes = Map.of("java.util.List", Shape.LIST, "java.util.Optional", Shape.OPTIONAL,
                DOMAIN + "Page", Shape.PAGE, DOMAIN + "Slice", Shape.SLICE, DOMAIN + "Window", Shape.WINDOW);
        Shape shape = shapes.get(erased);
        require(method, shape != null && result instanceof DeclaredType d && d.getTypeArguments().size() == 1
                && env.getTypeUtils().isSameType(d.getTypeArguments().get(0), entity), "Unsupported return type (including Stream, projections and raw containers): " + result);
        return shape;
    }

    private List<Field> fields(TypeElement entity) {
        var fields = new ArrayList<Field>(); var columns = new HashSet<String>();
        List<? extends Element> members = entity.getKind() == ElementKind.RECORD ? entity.getRecordComponents() : entity.getEnclosedElements();
        for (var member : members) {
            if (member.getKind() != ElementKind.FIELD && member.getKind() != ElementKind.RECORD_COMPONENT) continue;
            var id = member.getAnnotation(CacheId.class); var column = member.getAnnotation(CacheColumn.class);
            if (id == null && column == null) continue;
            require(member, !member.getModifiers().contains(Modifier.STATIC), "Persisted Spring Data fields cannot be static");
            String name = id == null ? column.value() : id.column();
            require(member, name.matches("[A-Za-z_][A-Za-z0-9_]*") && columns.add(name), "Invalid or duplicate persisted column");
            fields.add(new Field(member, member.getSimpleName().toString(), name, member.asType(), id != null));
        }
        require(entity, !fields.isEmpty() && fields.size() <= 256, "Spring Data supports 1..256 persisted properties");
        return fields;
    }

    private Field property(Element source, String text, List<Field> fields) {
        return fields.stream().filter(field -> text.startsWith(cap(field.property())))
                .max(Comparator.comparingInt(field -> field.property().length()))
                .orElseThrow(() -> new Invalid(source, "Unknown derived property/unsupported keyword: " + text));
    }

    private void generatedId(StringBuilder out, Field id, String entity, String binding, TypeElement entityElement) {
        var generated = id.element().getAnnotation(CacheGeneratedId.class);
        if (generated == null) return;
        String expression = switch (generated.value()) {
            case UUID -> "session().idGenerator().nextUuid()";
            case ULID -> "session().idGenerator().nextUlid()";
            case SEQUENCE -> "java.lang.Long.valueOf(session().idGenerator().nextSequence("
                    + quote(generated.sequence().isBlank() ? entityElement.getSimpleName().toString().toLowerCase(Locale.ROOT) : generated.sequence())
                    + ", " + generated.allocationSize() + "))";
        };
        out.append("  @Override protected ").append(entity).append(" prepareSave(").append(entity).append(" entity) {\n")
                .append("    return ").append(binding).append(".METADATA.idAccessor().apply(entity) != null ? entity : ")
                .append(binding).append(".withId(entity, ").append(expression).append(");\n  }\n");
    }

    private boolean queryable(Field field) {
        if (field.element().getAnnotationMirrors().stream().anyMatch(mirror ->
                Set.of("com.reactor.cachedb.annotations.CacheCodec", "com.reactor.cachedb.annotations.CacheEncrypted")
                        .contains(mirror.getAnnotationType().toString()))) return false;
        TypeMirror type = boxed(field.type());
        return enumField(field) || Set.of(
                "java.lang.String", "java.lang.Boolean", "java.lang.Byte", "java.lang.Short", "java.lang.Integer",
                "java.lang.Long", "java.lang.Float", "java.lang.Double", "java.math.BigDecimal", "java.math.BigInteger",
                "java.util.UUID", "java.time.Instant", "java.time.LocalDate", "java.time.LocalDateTime", "java.time.OffsetDateTime")
                .contains(type.toString());
    }

    private boolean enumField(Field field) {
        Element element = env.getTypeUtils().asElement(boxed(field.type()));
        return element != null && element.getKind() == ElementKind.ENUM;
    }

    private String signature(ExecutableElement method, ExecutableType type) {
        return method.getSimpleName() + "(" + String.join(",", type.getParameterTypes().stream()
                .map(parameter -> env.getTypeUtils().erasure(parameter).toString()).toList()) + ")";
    }
    private TypeMirror boxed(TypeMirror type) { return type.getKind().isPrimitive() ? env.getTypeUtils().boxedClass((PrimitiveType) type).asType() : type; }
    private boolean same(TypeMirror a, TypeMirror b) { return env.getTypeUtils().isSameType(boxed(a), boxed(b)); }
    private TypeMirror upper(TypeMirror type) { return type instanceof WildcardType w && w.getExtendsBound() != null ? w.getExtendsBound() : type; }
    private TypeMirror annotationEntity(TypeElement repository) {
        try { repository.getAnnotation(CacheRepository.class).entity(); throw new AssertionError(); }
        catch (MirroredTypeException type) { return type.getTypeMirror(); }
    }
    private AnnotationMirror annotation(Element element, String type) {
        return element.getAnnotationMirrors().stream().filter(m -> m.getAnnotationType().toString().equals(type)).findFirst().orElse(null);
    }
    private Set<String> strings(AnnotationMirror mirror, String name) {
        if (mirror == null) return Set.of();
        var values = new LinkedHashSet<String>();
        env.getElementUtils().getElementValuesWithDefaults(mirror).forEach((key, value) -> {
            if (key.getSimpleName().contentEquals(name) && value.getValue() instanceof List<?> list)
                for (Object entry : list) values.add(String.valueOf(((AnnotationValue) entry).getValue()));
        });
        return values;
    }
    private void collectComposite(AnnotationMirror mirror, Set<String> columns) {
        String type = mirror.getAnnotationType().toString();
        if (type.equals("com.reactor.cachedb.annotations.CacheCompositeIndex")) {
            columns.addAll(strings(mirror, "columns"));
            env.getElementUtils().getElementValuesWithDefaults(mirror).forEach((key, value) -> {
                if (key.getSimpleName().contentEquals("sortBy")) columns.add(String.valueOf(value.getValue()));
            });
        } else if (type.equals("com.reactor.cachedb.annotations.CacheCompositeIndexes")) {
            env.getElementUtils().getElementValuesWithDefaults(mirror).forEach((key, value) -> {
                if (value.getValue() instanceof List<?> list) for (Object item : list)
                    if (((AnnotationValue) item).getValue() instanceof AnnotationMirror child) collectComposite(child, columns);
            });
        }
    }
    private String cap(String value) { return Character.toUpperCase(value.charAt(0)) + value.substring(1); }
    private String quote(String value) { return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""; }
    private void write(String name, String text, Element origin) throws IOException {
        try (var writer = env.getFiler().createSourceFile(name, origin).openWriter()) { writer.write(text); }
    }
    private void require(Element element, boolean condition, String message) { if (!condition) fail(element, message); }
    private void fail(Element element, String message) { throw new Invalid(element, message); }
    private static final class Invalid extends RuntimeException {
        final Element element;
        Invalid(Element element, String message) { super(message); this.element = element; }
    }
}
