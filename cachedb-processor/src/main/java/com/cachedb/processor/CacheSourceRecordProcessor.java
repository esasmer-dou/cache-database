package com.reactor.cachedb.processor;

import com.reactor.cachedb.annotations.CacheColumn;
import com.reactor.cachedb.annotations.CacheSourceRecord;

import java.io.IOException;
import java.io.Writer;
import java.util.*;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.tools.Diagnostic;

/** Generates direct constructor calls for source records, without runtime reflection. */
@SupportedAnnotationTypes("com.reactor.cachedb.annotations.CacheSourceRecord")
public final class CacheSourceRecordProcessor extends AbstractProcessor {
    private static final Map<String, String> TYPES =
            Map.ofEntries(
                    Map.entry("java.lang.String", "java.lang.String"),
                    Map.entry("java.lang.Integer", "java.lang.Integer"),
                    Map.entry("int", "java.lang.Integer"),
                    Map.entry("java.lang.Long", "java.lang.Long"),
                    Map.entry("long", "java.lang.Long"),
                    Map.entry("java.lang.Double", "java.lang.Double"),
                    Map.entry("double", "java.lang.Double"),
                    Map.entry("java.lang.Boolean", "java.lang.Boolean"),
                    Map.entry("boolean", "java.lang.Boolean"),
                    Map.entry("java.math.BigDecimal", "java.math.BigDecimal"),
                    Map.entry("java.time.LocalDateTime", "java.time.LocalDateTime"),
                    Map.entry("java.time.LocalDate", "java.time.LocalDate"),
                    Map.entry("java.time.Instant", "java.time.Instant"),
                    Map.entry("java.util.UUID", "java.util.UUID"));

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        for (Element element : round.getElementsAnnotatedWith(CacheSourceRecord.class)) {
            try {
                generate((TypeElement) element);
            } catch (IllegalArgumentException | IOException failure) {
                processingEnv
                        .getMessager()
                        .printMessage(Diagnostic.Kind.ERROR, failure.getMessage(), element);
            }
        }
        return true;
    }

    private void generate(TypeElement record) throws IOException {
        if (record.getKind() != ElementKind.RECORD
                || record.getNestingKind() != NestingKind.TOP_LEVEL
                || !record.getModifiers().contains(Modifier.PUBLIC)
                || !record.getTypeParameters().isEmpty())
            throw new IllegalArgumentException(
                    "@CacheSourceRecord requires a public, nongeneric top-level record");
        String table = record.getAnnotation(CacheSourceRecord.class).table();
        if (!table.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*"))
            throw new IllegalArgumentException("Invalid source table identifier");
        String pkg =
                processingEnv.getElementUtils().getPackageOf(record).getQualifiedName().toString();
        if (pkg.isBlank())
            throw new IllegalArgumentException("Source records require a named package");
        String name = record.getSimpleName().toString();
        List<String> columns = new ArrayList<>();
        List<String> values = new ArrayList<>();
        StringBuilder fields =
                new StringBuilder(
                        "package " + pkg + ";\n\npublic final class " + name + "Fields {\n");
        for (RecordComponentElement field : record.getRecordComponents()) {
            CacheColumn annotation = field.getAnnotation(CacheColumn.class);
            String column =
                    annotation == null ? field.getSimpleName().toString() : annotation.value();
            if (!column.matches("[A-Za-z_][A-Za-z0-9_]*") || columns.contains(column))
                throw new IllegalArgumentException("Invalid or duplicate source column: " + column);
            String type = field.asType().toString();
            String boxed = TYPES.get(type);
            if (boxed == null)
                throw new IllegalArgumentException("Unsupported source component type: " + type);
            columns.add(column);
            String read =
                    "com.reactor.cachedb.core.model.SourceValues.read(row, \""
                            + column
                            + "\", "
                            + boxed
                            + ".class)";
            if (type.equals("boolean")) read = "Boolean.TRUE.equals(" + read + ")";
            else if (field.asType().getKind().isPrimitive())
                read =
                        "java.util.Objects.requireNonNull("
                                + read
                                + ", \"Null source column: "
                                + column
                                + "\")";
            values.add(read);
            fields.append("    public static final com.reactor.cachedb.core.query.CacheField<")
                    .append(name)
                    .append(", ")
                    .append(boxed)
                    .append("> ")
                    .append(field.getSimpleName())
                    .append(" = new com.reactor.cachedb.core.query.CacheField<>(\"")
                    .append(field.getSimpleName())
                    .append("\", \"")
                    .append(column)
                    .append("\", ")
                    .append(boxed)
                    .append(".class);\n");
        }
        if (columns.isEmpty())
            throw new IllegalArgumentException("Source record requires at least one column");
        String literals =
                columns.stream()
                        .map(c -> "\"" + c + "\"")
                        .collect(java.util.stream.Collectors.joining(", "));
        String binding =
                "package "
                        + pkg
                        + ";\n\npublic final class "
                        + name
                        + "SourceBinding {\n"
                        + "    private "
                        + name
                        + "SourceBinding() {}\n"
                        + "    public static final com.reactor.cachedb.core.model.SourceMapping<"
                        + name
                        + "> SOURCE =\n"
                        + "            new com.reactor.cachedb.core.model.SourceMapping<>(\""
                        + table
                        + "\", java.util.List.of("
                        + literals
                        + "), row -> new "
                        + name
                        + "(\n                    "
                        + String.join(",\n                    ", values)
                        + "));\n}\n";
        write(pkg + "." + name + "SourceBinding", record, binding);
        write(
                pkg + "." + name + "Fields",
                record,
                fields.append("    private ").append(name).append("Fields() {}\n}\n").toString());
    }

    private void write(String name, Element origin, String content) throws IOException {
        try (Writer writer = processingEnv.getFiler().createSourceFile(name, origin).openWriter()) {
            writer.write(content);
        }
    }
}
