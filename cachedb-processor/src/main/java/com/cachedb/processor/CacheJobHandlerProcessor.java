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
@SupportedAnnotationTypes("com.reactor.cachedb.annotations.CacheJobHandler")
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
        return true;
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
