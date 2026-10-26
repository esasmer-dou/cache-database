package com.reactor.cachedb.processor;

import com.reactor.cachedb.annotations.CacheJobHandler;
import java.io.*;
import java.util.*;
import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.*;
import javax.tools.Diagnostic;

/** Emits compile-time validated, direct calls to bean methods, not reflective proxies. */
@SupportedAnnotationTypes({"com.reactor.cachedb.annotations.CacheJobHandler", "com.reactor.cachedb.annotations.CacheWatcher"})
public final class CacheJobHandlerProcessor extends AbstractProcessor {
    private final Set<String> configurations = new TreeSet<>();
    private boolean importsWritten;
    public SourceVersion getSupportedSourceVersion() { return SourceVersion.latestSupported(); }
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        if (round.processingOver() && !importsWritten && !configurations.isEmpty()) {
            importsWritten = true;
            try (Writer writer = processingEnv.getFiler().createResource(javax.tools.StandardLocation.CLASS_OUTPUT,
                    "", "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports").openWriter()) {
                for (String configuration : configurations) writer.write(configuration + "\n");
            } catch (IOException failure) { processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, failure.getMessage()); }
        }
        for (Element element : round.getElementsAnnotatedWith(CacheJobHandler.class)) {
            try { generate((ExecutableElement) element); }
            catch (IllegalArgumentException | IOException failure) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, failure.getMessage(), element);
            }
        }
        for (Element element : round.getElementsAnnotatedWith(com.reactor.cachedb.annotations.CacheWatcher.class)) {
            try { generateWatcher((ExecutableElement) element); }
            catch (IllegalArgumentException | IOException failure) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, failure.getMessage(), element);
            }
        }
        return true;
    }

    private void generateWatcher(ExecutableElement method) throws IOException {
        var declaration = method.getAnnotation(com.reactor.cachedb.annotations.CacheWatcher.class);
        TypeElement owner = (TypeElement) method.getEnclosingElement();
        if (!method.getModifiers().contains(Modifier.PUBLIC) || method.getModifiers().contains(Modifier.STATIC)
                || !owner.getModifiers().contains(Modifier.PUBLIC) || !method.getTypeParameters().isEmpty()
                || !method.getThrownTypes().isEmpty() || method.getReturnType().getKind() != TypeKind.VOID
                || method.getParameters().size() != 1)
            throw new IllegalArgumentException("@CacheWatcher requires a public void bean method with one concrete CacheChangeEvent and no checked exceptions");
        TypeMirror entityMirror;
        try { declaration.entity(); throw new IllegalStateException(); }
        catch (MirroredTypeException mirror) { entityMirror = mirror.getTypeMirror(); }
        TypeElement entity = (TypeElement) processingEnv.getTypeUtils().asElement(entityMirror);
        if (entity.getAnnotation(com.reactor.cachedb.annotations.CacheEntity.class) == null)
            throw new IllegalArgumentException("Watcher entity must be a generated @CacheEntity");
        String field = null, fieldType = null, idType = null;
        for (Element member : entity.getEnclosedElements()) {
            if (member.getKind() != ElementKind.FIELD && member.getKind() != ElementKind.RECORD_COMPONENT) continue;
            var column = member.getAnnotation(com.reactor.cachedb.annotations.CacheColumn.class);
            if (member.getAnnotation(com.reactor.cachedb.annotations.CacheId.class) != null) idType = member.asType().toString();
            String sql = column == null ? member.getSimpleName().toString() : column.value();
            if (member.getSimpleName().contentEquals(declaration.filterField()) || sql.equals(declaration.filterField())) {
                field = sql; fieldType = member.asType().toString();
            }
        }
        if (field == null || idType == null) throw new IllegalArgumentException("Unknown watcher field or missing entity ID");
        var event = method.getParameters().get(0).asType();
        if (!(event instanceof DeclaredType eventType) || eventType.getTypeArguments().size() != 2
                || !processingEnv.getTypeUtils().erasure(event).toString().equals("com.reactor.cachedb.core.watch.CacheChangeEvent")
                || !eventType.getTypeArguments().get(0).toString().equals(entityMirror.toString())
                || !eventType.getTypeArguments().get(1).toString().equals(idType))
            throw new IllegalArgumentException("Watcher event entity and ID types must match @CacheEntity");
        String literal = quoted(declaration.filterEquals());
        String parsed = switch (fieldType) {
            case "java.lang.String" -> literal;
            case "java.lang.Long", "long" -> "java.lang.Long.valueOf(" + literal + ")";
            case "java.lang.Integer", "int" -> "java.lang.Integer.valueOf(" + literal + ")";
            case "java.util.UUID" -> "java.util.UUID.fromString(" + literal + ")";
            default -> throw new IllegalArgumentException("Watcher equality supports String, Long, Integer and UUID fields");
        };
        String pkg = processingEnv.getElementUtils().getPackageOf(owner).getQualifiedName().toString();
        String name = owner.getSimpleName() + "_" + method.getSimpleName() + "CacheWatcherAutoConfiguration";
        String binding = processingEnv.getElementUtils().getPackageOf(entity).getQualifiedName() + "." + entity.getSimpleName() + "CacheBinding";
        try (Writer writer = processingEnv.getFiler().createSourceFile(pkg + "." + name, method).openWriter()) {
            writer.write("package " + pkg + ";\n@org.springframework.boot.autoconfigure.AutoConfiguration(after=com.reactor.cachedb.spring.boot.CacheDatabaseSpringBootAutoConfiguration.class)\n"
                    + "public class " + name + " {\n"
                    + " @org.springframework.context.annotation.Bean(name=" + quoted("cachedbWatcher." + owner.getQualifiedName() + "." + method.getSimpleName()) + ", destroyMethod=\"close\")\n"
                    + " @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(" + owner.getQualifiedName() + ".class)\n"
                    + " public com.reactor.cachedb.core.watch.CacheWatcherRegistration watcher(com.reactor.cachedb.starter.CacheDatabase database, " + owner.getQualifiedName() + " target) {\n"
                    + " return database.repository(" + binding + ".METADATA," + binding + ".CODEC).registerWatcher("
                    + "com.reactor.cachedb.core.query.QuerySpec.where(com.reactor.cachedb.core.query.QueryFilter.eq(" + quoted(field) + "," + parsed + ")), target::" + method.getSimpleName() + ");\n}\n}\n");
        }
        configurations.add(pkg + "." + name);
    }

    private static String quoted(String value) {
        var out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '\\' -> out.append("\\\\"); case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n"); case '\r' -> out.append("\\r"); case '\t' -> out.append("\\t");
                default -> { if (ch < 32) out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) ch)); else out.append(ch); }
            }
        }
        return out.append('"').toString();
    }

    private void generate(ExecutableElement method) throws IOException {
        TypeElement owner = (TypeElement) method.getEnclosingElement();
        var modifiers = method.getModifiers();
        if (!modifiers.contains(Modifier.PUBLIC) || modifiers.contains(Modifier.STATIC)
                || !owner.getModifiers().contains(Modifier.PUBLIC) || !method.getTypeParameters().isEmpty())
            throw new IllegalArgumentException("@CacheJobHandler requires a public non-static, nongeneric bean method");
        var params = method.getParameters();
        if (params.size() < 1 || params.size() > 2 || params.size() == 2 && !params.get(0).asType().toString().equals("com.reactor.cachedb.spring.boot.CacheDistributedJobContext"))
            throw new IllegalArgumentException("Job signature must be (Payload) or (CacheDistributedJobContext, Payload)");
        TypeMirror runtime = processingEnv.getElementUtils().getTypeElement("java.lang.RuntimeException").asType();
        TypeMirror error = processingEnv.getElementUtils().getTypeElement("java.lang.Error").asType();
        for (TypeMirror thrown : method.getThrownTypes()) {
            if (!processingEnv.getTypeUtils().isSubtype(thrown,runtime) && !processingEnv.getTypeUtils().isSubtype(thrown,error))
                throw new IllegalArgumentException("Job methods must handle checked exceptions explicitly");
        }
        TypeMirror payload = params.get(params.size() - 1).asType();
        if (!(processingEnv.getTypeUtils().asElement(payload) instanceof TypeElement payloadType)
                || !payloadType.getTypeParameters().isEmpty()) throw new IllegalArgumentException("Concrete job payload type required");
        String route = method.getAnnotation(CacheJobHandler.class).value();
        if (!route.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) throw new IllegalArgumentException("Invalid job route");
        TypeMirror explicit;
        try { method.getAnnotation(CacheJobHandler.class).codec(); throw new IllegalStateException(); }
        catch (MirroredTypeException failure) { explicit = failure.getTypeMirror(); }
        boolean generated = explicit.toString().equals("java.lang.Void");
        String codec;
        if (generated) {
            if (payloadType.getAnnotation(com.reactor.cachedb.annotations.CacheJsonRecord.class) == null)
                throw new IllegalArgumentException("Job payload needs @CacheJsonRecord or an explicit CacheValueCodec bean");
            List<String> names = new ArrayList<>();
            for (Element current = payloadType; current instanceof TypeElement type; current = current.getEnclosingElement()) names.add(0, current.getSimpleName().toString());
            codec = processingEnv.getElementUtils().getPackageOf(payloadType).getQualifiedName() + "." + String.join("_", names) + "JsonCodec.INSTANCE";
        } else {
            TypeElement contract = processingEnv.getElementUtils().getTypeElement("com.reactor.cachedb.core.codec.CacheValueCodec");
            if (contract == null || !processingEnv.getTypeUtils().isAssignable(explicit,
                    processingEnv.getTypeUtils().getDeclaredType(contract, payload)))
                throw new IllegalArgumentException("Explicit job codec must implement CacheValueCodec");
            codec = "codec";
        }
        String pkg = processingEnv.getElementUtils().getPackageOf(owner).getQualifiedName().toString();
        String name = owner.getSimpleName() + "_" + method.getSimpleName() + "CacheJobAutoConfiguration";
        String call = "target." + method.getSimpleName() + "(" + (params.size() == 2 ? "context, " : "") + "arguments)";
        try (Writer writer = processingEnv.getFiler().createSourceFile(pkg + "." + name, method).openWriter()) {
            writer.write("package " + pkg + ";\n@org.springframework.boot.autoconfigure.AutoConfiguration\npublic class " + name + " {\n"
                    + " @org.springframework.context.annotation.Bean(name=\"cachedbJob." + owner.getQualifiedName() + "." + method.getSimpleName() + "\")\n"
                    + " @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(" + owner.getQualifiedName() + ".class)\n"
                    + " public com.reactor.cachedb.spring.boot.CacheDistributedJobHandler<" + payload + "> handler("
                    + owner.getQualifiedName() + " target" + (generated ? "" : ", " + explicit + " codec") + ") {\n"
                    + " return new com.reactor.cachedb.spring.boot.CacheDistributedJobHandler<" + payload + ">() {\n"
                    + " public String route(){return \"" + route + "\";}\n"
                    + " public Class<" + payload + "> argumentType(){return " + payload + ".class;}\n"
                    + " public com.reactor.cachedb.core.codec.CacheValueCodec<" + payload + "> argumentCodec(){return " + codec + ";}\n"
                    + " public Object execute(" + payload + " arguments, com.reactor.cachedb.spring.boot.CacheDistributedJobContext context){"
                    + (method.getReturnType().getKind() == TypeKind.VOID ? call + ";return null;" : "return " + call + ";") + "}\n};\n}\n}\n");
        }
        configurations.add(pkg + "." + name);
    }
}
