package com.reactor.cachedb.processor;

import com.reactor.cachedb.annotations.CacheColumn;
import com.reactor.cachedb.annotations.CacheProjectionRecord;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@SupportedAnnotationTypes("com.reactor.cachedb.annotations.CacheProjectionRecord")
public final class CacheProjectionRecordProcessor extends AbstractProcessor {

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (Element element : roundEnv.getElementsAnnotatedWith(CacheProjectionRecord.class)) {
            if (element.getKind() != ElementKind.RECORD) {
                processingEnv.getMessager().printMessage(
                        Diagnostic.Kind.ERROR,
                        "@CacheProjectionRecord can only be used on records",
                        element
                );
                continue;
            }
            TypeElement record = (TypeElement) element;
            if (record.getNestingKind() != NestingKind.TOP_LEVEL) {
                processingEnv.getMessager().printMessage(
                        Diagnostic.Kind.ERROR,
                        "@CacheProjectionRecord requires a top-level record",
                        record
                );
                continue;
            }
            List<Component> components = resolveComponents(record);
            if (components != null) {
                writeSchema(record, components);
                ProjectionSource source = resolveProjectionSource(record, components);
                if (source != null) {
                    writeProjection(record, components, source);
                }
            }
        }
        return true;
    }

    private List<Component> resolveComponents(TypeElement record) {
        ArrayList<Component> components = new ArrayList<>();
        java.util.HashSet<String> columns = new java.util.HashSet<>();
        for (RecordComponentElement component : record.getRecordComponents()) {
            CacheColumn cacheColumn = component.getAnnotation(CacheColumn.class);
            String column = cacheColumn == null
                    ? toSnakeCase(component.getSimpleName().toString())
                    : cacheColumn.value().trim();
            if (!column.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                processingEnv.getMessager().printMessage(
                        Diagnostic.Kind.ERROR,
                        "Projection column must be a safe SQL identifier: " + column,
                        component
                );
                return null;
            }
            if (!columns.add(column)) {
                processingEnv.getMessager().printMessage(
                        Diagnostic.Kind.ERROR,
                        "Projection column is declared more than once: " + column,
                        component
                );
                return null;
            }
            String type = component.asType().toString();
            ColumnCodec codec = ColumnCodec.forType(type);
            if (codec == null) {
                processingEnv.getMessager().printMessage(
                        Diagnostic.Kind.ERROR,
                        "Unsupported @CacheProjectionRecord component type: " + type,
                        component
                );
                return null;
            }
            components.add(new Component(component.getSimpleName().toString(), column, type, codec));
        }
        if (components.isEmpty()) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "@CacheProjectionRecord requires at least one component",
                    record
            );
            return null;
        }
        return List.copyOf(components);
    }

    private ProjectionSource resolveProjectionSource(TypeElement record, List<Component> components) {
        CacheProjectionRecord annotation = record.getAnnotation(CacheProjectionRecord.class);
        TypeMirror sourceType;
        try {
            annotation.source();
            return null;
        } catch (MirroredTypeException exception) {
            sourceType = exception.getTypeMirror();
        }
        if (sourceType.toString().equals(Void.class.getCanonicalName())) {
            return null;
        }
        Element sourceElement = processingEnv.getTypeUtils().asElement(sourceType);
        if (!(sourceElement instanceof TypeElement source)) {
            error(record, "Projection source must be a concrete entity type");
            return null;
        }
        String idComponent = annotation.id().trim();
        if (idComponent.isEmpty()) {
            error(record, "@CacheProjectionRecord id is required when source is configured");
            return null;
        }
        Component id = components.stream()
                .filter(component -> component.name().equals(idComponent))
                .findFirst()
                .orElse(null);
        if (id == null) {
            error(record, "Projection id component does not exist: " + idComponent);
            return null;
        }

        Map<String, SourceField> byName = new HashMap<>();
        Map<String, SourceField> byColumn = new HashMap<>();
        for (Element enclosed : source.getEnclosedElements()) {
            if (!(enclosed instanceof VariableElement field) || enclosed.getKind() != ElementKind.FIELD) {
                continue;
            }
            String fieldName = field.getSimpleName().toString();
            CacheColumn column = field.getAnnotation(CacheColumn.class);
            String columnName = column == null ? toSnakeCase(fieldName) : column.value().trim();
            SourceField sourceField = new SourceField(fieldName, columnName, field.asType().toString());
            byName.put(fieldName, sourceField);
            byColumn.put(columnName, sourceField);
        }

        ArrayList<SourceField> mapping = new ArrayList<>(components.size());
        for (Component component : components) {
            SourceField field = byName.get(component.name());
            if (field == null) {
                field = byColumn.get(component.column());
            }
            if (field == null) {
                error(record, "Projection component has no matching source field: " + component.name());
                return null;
            }
            if (!compatibleTypes(field.type(), component.type())) {
                error(record, "Projection component type does not match source field "
                        + field.name() + ": " + field.type() + " -> " + component.type());
                return null;
            }
            mapping.add(field);
        }

        ArrayList<String> rankedBy = new ArrayList<>();
        for (String column : annotation.rankedBy()) {
            String normalized = column == null ? "" : column.trim();
            if (!normalized.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                error(record, "Projection rankedBy value must be a safe column identifier: " + normalized);
                return null;
            }
            rankedBy.add(normalized);
        }
        String projectionName = annotation.name().isBlank()
                ? toKebabCase(record.getSimpleName().toString())
                : annotation.name().trim();
        if (!projectionName.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            error(record, "Projection name contains unsupported characters: " + projectionName);
            return null;
        }
        return new ProjectionSource(
                source.getQualifiedName().toString(),
                id,
                List.copyOf(mapping),
                projectionName,
                List.copyOf(rankedBy),
                annotation.refresh()
        );
    }

    private void writeSchema(TypeElement record, List<Component> components) {
        String packageName = processingEnv.getElementUtils().getPackageOf(record).getQualifiedName().toString();
        String recordName = record.getSimpleName().toString();
        String generatedName = recordName + "ProjectionSchema";
        StringBuilder source = new StringBuilder();
        source.append("package ").append(packageName).append(";\n\n");
        source.append("import com.reactor.cachedb.core.projection.ProjectionSchema;\n\n");
        source.append("public final class ").append(generatedName).append(" {\n");
        source.append("    public static final ProjectionSchema<").append(recordName).append("> SCHEMA =\n");
        source.append("            ProjectionSchema.<").append(recordName).append(">builder()\n");
        for (Component component : components) {
            source.append("                    .").append(component.codec().builderMethod())
                    .append("(\"").append(component.column()).append("\", ")
                    .append(recordName).append("::").append(component.name()).append(")\n");
        }
        source.append("                    .decodeWith(row -> new ").append(recordName).append("(\n");
        for (int index = 0; index < components.size(); index++) {
            Component component = components.get(index);
            source.append("                            row.").append(component.codec().rowMethod())
                    .append("(\"").append(component.column()).append("\")")
                    .append(index == components.size() - 1 ? "\n" : ",\n");
        }
        source.append("                    ))\n");
        source.append("                    .build();\n\n");
        source.append("    private ").append(generatedName).append("() {\n");
        source.append("    }\n");
        source.append("}\n");

        try {
            JavaFileObject file = processingEnv.getFiler().createSourceFile(
                    packageName + "." + generatedName,
                    record
            );
            try (Writer writer = file.openWriter()) {
                writer.write(source.toString());
            }
        } catch (IOException exception) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "Could not generate projection schema: " + exception.getMessage(),
                    record
            );
        }
    }

    private void writeProjection(
            TypeElement record,
            List<Component> components,
            ProjectionSource projection
    ) {
        String packageName = processingEnv.getElementUtils().getPackageOf(record).getQualifiedName().toString();
        String recordName = record.getSimpleName().toString();
        String generatedName = recordName + "Projection";
        StringBuilder source = new StringBuilder();
        source.append("package ").append(packageName).append(";\n\n");
        source.append("import com.reactor.cachedb.core.projection.EntityProjection;\n\n");
        source.append("public final class ").append(generatedName).append(" {\n");
        source.append("    public static final EntityProjection<")
                .append(projection.sourceType()).append(", ").append(recordName).append(", ")
                .append(projection.id().type()).append("> PROJECTION =\n");
        source.append("            EntityProjection.<")
                .append(projection.sourceType()).append(", ").append(recordName).append(", ")
                .append(projection.id().type()).append(">of(\n");
        source.append("                    \"").append(projection.name()).append("\",\n");
        source.append("                    ").append(recordName).append("ProjectionSchema.SCHEMA,\n");
        source.append("                    ").append(recordName).append("::")
                .append(projection.id().name()).append(",\n");
        source.append("                    ").append(generatedName).append("::fromEntity\n");
        source.append("            )");
        if (!projection.rankedBy().isEmpty()) {
            source.append(".rankedBy(");
            for (int index = 0; index < projection.rankedBy().size(); index++) {
                if (index > 0) {
                    source.append(", ");
                }
                source.append("\"").append(projection.rankedBy().get(index)).append("\"");
            }
            source.append(")");
        }
        if (projection.refresh() == CacheProjectionRecord.Refresh.ASYNC) {
            source.append(".asyncRefresh()");
        }
        source.append(";\n\n");
        source.append("    public static ").append(recordName).append(" fromEntity(")
                .append(projection.sourceType()).append(" entity) {\n");
        source.append("        return new ").append(recordName).append("(\n");
        for (int index = 0; index < projection.mapping().size(); index++) {
            SourceField field = projection.mapping().get(index);
            source.append("                entity.").append(field.name())
                    .append(index == projection.mapping().size() - 1 ? "\n" : ",\n");
        }
        source.append("        );\n");
        source.append("    }\n\n");
        source.append("    private ").append(generatedName).append("() {\n");
        source.append("    }\n");
        source.append("}\n");

        try {
            JavaFileObject file = processingEnv.getFiler().createSourceFile(
                    packageName + "." + generatedName,
                    record
            );
            try (Writer writer = file.openWriter()) {
                writer.write(source.toString());
            }
        } catch (IOException exception) {
            error(record, "Could not generate entity projection: " + exception.getMessage());
        }
    }

    private boolean compatibleTypes(String sourceType, String projectionType) {
        return boxed(sourceType).equals(boxed(projectionType));
    }

    private String boxed(String type) {
        return switch (type) {
            case "long" -> "java.lang.Long";
            case "int" -> "java.lang.Integer";
            case "double" -> "java.lang.Double";
            case "boolean" -> "java.lang.Boolean";
            default -> type;
        };
    }

    private String toKebabCase(String value) {
        StringBuilder result = new StringBuilder(value.length() + 4);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isUpperCase(character) && index > 0) {
                result.append('-');
            }
            result.append(Character.toLowerCase(character));
        }
        return result.toString();
    }

    private void error(Element element, String message) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element);
    }

    private String toSnakeCase(String value) {
        StringBuilder result = new StringBuilder(value.length() + 4);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isUpperCase(character) && index > 0) {
                result.append('_');
            }
            result.append(Character.toLowerCase(character));
        }
        return result.toString();
    }

    private record Component(String name, String column, String type, ColumnCodec codec) {
    }

    private record SourceField(String name, String column, String type) {
    }

    private record ProjectionSource(
            String sourceType,
            Component id,
            List<SourceField> mapping,
            String name,
            List<String> rankedBy,
            CacheProjectionRecord.Refresh refresh
    ) {
    }

    private enum ColumnCodec {
        STRING("stringColumn", "string"),
        LONG("longColumn", "longValue"),
        INTEGER("integerColumn", "integer"),
        DOUBLE("doubleColumn", "doubleValue"),
        BOOLEAN("booleanColumn", "booleanValue"),
        DECIMAL("decimalColumn", "decimal");

        private final String builderMethod;
        private final String rowMethod;

        ColumnCodec(String builderMethod, String rowMethod) {
            this.builderMethod = builderMethod;
            this.rowMethod = rowMethod;
        }

        String builderMethod() {
            return builderMethod;
        }

        String rowMethod() {
            return rowMethod;
        }

        static ColumnCodec forType(String type) {
            return switch (type) {
                case "java.lang.String" -> STRING;
                case "long", "java.lang.Long" -> LONG;
                case "int", "java.lang.Integer" -> INTEGER;
                case "double", "java.lang.Double" -> DOUBLE;
                case "boolean", "java.lang.Boolean" -> BOOLEAN;
                case "java.math.BigDecimal" -> DECIMAL;
                default -> null;
            };
        }
    }
}
