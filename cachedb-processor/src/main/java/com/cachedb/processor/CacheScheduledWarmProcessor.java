package com.reactor.cachedb.processor;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.Writer;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Generates direct Spring adapters for scheduled warm methods without runtime reflection. */
@SupportedAnnotationTypes(CacheScheduledWarmProcessor.ANNOTATION)
public final class CacheScheduledWarmProcessor extends AbstractProcessor {
    static final String ANNOTATION = "com.reactor.cachedb.spring.boot.CacheScheduledWarm";
    private static final String WARM_PLAN = "com.reactor.cachedb.starter.CacheWarmPlan";

    private final Set<String> generated = new LinkedHashSet<>();

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnvironment) {
        TypeElement annotationType = processingEnv.getElementUtils().getTypeElement(ANNOTATION);
        if (annotationType == null) {
            return false;
        }
        for (Element element : roundEnvironment.getElementsAnnotatedWith(annotationType)) {
            if (!(element instanceof ExecutableElement method) || element.getKind() != ElementKind.METHOD) {
                error(element, "@CacheScheduledWarm can only be used on a method");
                continue;
            }
            generate(method);
        }
        return false;
    }

    private void generate(ExecutableElement method) {
        if (!(method.getEnclosingElement() instanceof TypeElement owner)
                || !owner.getModifiers().contains(Modifier.PUBLIC)) {
            error(method, "@CacheScheduledWarm must be declared in a public Spring bean type");
            return;
        }
        if (!method.getModifiers().contains(Modifier.PUBLIC)
                || method.getModifiers().contains(Modifier.STATIC)
                || !method.getParameters().isEmpty()) {
            error(method, "@CacheScheduledWarm method must be public, non-static, and parameterless");
            return;
        }
        if (!processingEnv.getTypeUtils().erasure(method.getReturnType()).toString().equals(WARM_PLAN)) {
            error(method, "@CacheScheduledWarm method must return CacheWarmPlan");
            return;
        }

        AnnotationMirror annotation = findAnnotation(method);
        if (annotation == null) {
            error(method, "Could not read @CacheScheduledWarm metadata");
            return;
        }
        Map<String, AnnotationValue> values = values(annotation);
        String packageName = processingEnv.getElementUtils().getPackageOf(owner).getQualifiedName().toString();
        String ownerBinaryName = processingEnv.getElementUtils().getBinaryName(owner).toString();
        String localOwnerName = ownerBinaryName.substring(packageName.isEmpty() ? 0 : packageName.length() + 1)
                .replace('$', '_');
        String generatedSimpleName = localOwnerName + capitalize(method.getSimpleName().toString())
                + "CacheScheduledWarmTask";
        String qualifiedName = packageName.isEmpty() ? generatedSimpleName : packageName + "." + generatedSimpleName;
        if (!generated.add(qualifiedName)) {
            error(method, "Duplicate generated scheduled-warm task name: " + qualifiedName);
            return;
        }

        String source = render(owner, method, packageName, generatedSimpleName, values);
        try {
            JavaFileObject file = processingEnv.getFiler().createSourceFile(qualifiedName, method);
            try (Writer writer = file.openWriter()) {
                writer.write(source);
            }
        } catch (IOException failure) {
            error(method, "Could not generate scheduled-warm task " + qualifiedName + ": " + failure.getMessage());
        }
    }

    private String render(
            TypeElement owner,
            ExecutableElement method,
            String packageName,
            String generatedSimpleName,
            Map<String, AnnotationValue> values
    ) {
        String ownerType = owner.getQualifiedName().toString();
        StringBuilder out = new StringBuilder(3_072);
        if (!packageName.isEmpty()) {
            out.append("package ").append(packageName).append(";\n\n");
        }
        out.append("@org.springframework.stereotype.Component\n")
                .append("public final class ").append(generatedSimpleName)
                .append(" implements com.reactor.cachedb.spring.boot.CacheScheduledWarmTask {\n")
                .append("    private static final com.reactor.cachedb.spring.boot.CacheScheduledWarmDescriptor DESCRIPTOR = ")
                .append("new com.reactor.cachedb.spring.boot.CacheScheduledWarmDescriptor(\n")
                .append("            ").append(quote(ownerType)).append(",\n")
                .append("            ").append(quote(method.getSimpleName().toString())).append(",\n")
                .append("            ").append(quote(string(values, "name"))).append(",\n")
                .append("            ").append(quote(string(values, "cron"))).append(",\n")
                .append("            ").append(quote(string(values, "zone"))).append(",\n")
                .append("            ").append(quote(string(values, "fixedDelayString"))).append(",\n")
                .append("            ").append(quote(string(values, "fixedRateString"))).append(",\n")
                .append("            ").append(quote(string(values, "initialDelayString"))).append(",\n")
                .append("            ").append(quote(string(values, "enabledString"))).append(",\n")
                .append("            com.reactor.cachedb.spring.boot.CacheScheduledWarmMode.")
                .append(enumName(values, "mode")).append(",\n")
                .append("            ").append(quote(string(values, "lockAtMostForString"))).append(",\n")
                .append("            ").append(quote(string(values, "lockWaitTimeoutString"))).append(",\n")
                .append("            ").append(quote(string(values, "lockRetryIntervalString"))).append(",\n")
                .append("            ").append(quote(string(values, "minimumIntervalString"))).append(",\n")
                .append("            ").append(bool(values, "reconcileHotSet")).append(",\n")
                .append("            ").append(quote(string(values, "reconcileMaxRowsPerRunString"))).append(",\n")
                .append("            ").append(quote(string(values, "reconcileScanCountString"))).append(");\n\n")
                .append("    private final ").append(ownerType).append(" target;\n\n")
                .append("    public ").append(generatedSimpleName).append('(').append(ownerType).append(" target) {\n")
                .append("        this.target = java.util.Objects.requireNonNull(target, \"target\");\n")
                .append("    }\n\n")
                .append("    @Override\n")
                .append("    public com.reactor.cachedb.spring.boot.CacheScheduledWarmDescriptor descriptor() {\n")
                .append("        return DESCRIPTOR;\n")
                .append("    }\n\n")
                .append("    @Override\n")
                .append("    public com.reactor.cachedb.starter.CacheWarmPlan createPlan() {\n")
                .append("        return target.").append(method.getSimpleName()).append("();\n")
                .append("    }\n")
                .append("}\n");
        return out.toString();
    }

    private AnnotationMirror findAnnotation(ExecutableElement method) {
        return method.getAnnotationMirrors().stream()
                .filter(candidate -> candidate.getAnnotationType().toString().equals(ANNOTATION))
                .findFirst()
                .orElse(null);
    }

    private Map<String, AnnotationValue> values(AnnotationMirror annotation) {
        LinkedHashMap<String, AnnotationValue> values = new LinkedHashMap<>();
        processingEnv.getElementUtils().getElementValuesWithDefaults(annotation)
                .forEach((key, value) -> values.put(key.getSimpleName().toString(), value));
        return values;
    }

    private String string(Map<String, AnnotationValue> values, String name) {
        Object value = required(values, name).getValue();
        return value == null ? "" : value.toString();
    }

    private boolean bool(Map<String, AnnotationValue> values, String name) {
        return Boolean.TRUE.equals(required(values, name).getValue());
    }

    private String enumName(Map<String, AnnotationValue> values, String name) {
        Object value = required(values, name).getValue();
        return value instanceof VariableElement element ? element.getSimpleName().toString() : value.toString();
    }

    private AnnotationValue required(Map<String, AnnotationValue> values, String name) {
        AnnotationValue value = values.get(name);
        if (value == null) {
            throw new IllegalStateException("Missing @CacheScheduledWarm member: " + name);
        }
        return value;
    }

    private void error(Element element, String message) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "[CacheDB] " + message, element);
    }

    private String quote(String value) {
        String safe = value == null ? "" : value;
        return '"' + safe.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private String capitalize(String value) {
        return value.isEmpty() ? value : Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
