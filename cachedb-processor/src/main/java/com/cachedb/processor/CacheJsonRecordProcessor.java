package com.reactor.cachedb.processor;

import com.reactor.cachedb.annotations.CacheJsonRecord;
import java.io.IOException;
import java.io.Writer;
import java.util.*;
import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.*;
import javax.tools.Diagnostic;

/** Emits direct constructors/accessors and token code. No runtime type discovery is generated. */
@SupportedAnnotationTypes("com.reactor.cachedb.annotations.CacheJsonRecord")
public final class CacheJsonRecordProcessor extends AbstractProcessor {
    private final Set<String> providers = new TreeSet<>();
    private boolean servicesWritten;
    private static final String VALUES = "com.reactor.cachedb.json.JsonValues";
    private static final String STRINGS = "com.reactor.cachedb.json.JsonStrings";
    private static final Map<String, String> READERS =
            Map.ofEntries(
                    Map.entry("java.lang.String", "string"),
                    Map.entry("java.lang.Integer", "integer"),
                    Map.entry("java.lang.Long", "longValue"),
                    Map.entry("java.lang.Short", "shortValue"),
                    Map.entry("java.lang.Byte", "byteValue"),
                    Map.entry("java.lang.Double", "doubleValue"),
                    Map.entry("java.lang.Float", "floatValue"),
                    Map.entry("java.lang.Boolean", "bool"),
                    Map.entry("java.lang.Character", "character"),
                    Map.entry("java.math.BigDecimal", "decimal"),
                    Map.entry("java.math.BigInteger", "bigInteger"));
    private static final Map<String, String> PARSERS =
            Map.ofEntries(
                    Map.entry("java.util.UUID", "fromString"),
                    Map.entry("java.time.Instant", "parse"),
                    Map.entry("java.time.LocalDate", "parse"),
                    Map.entry("java.time.LocalDateTime", "parse"),
                    Map.entry("java.time.LocalTime", "parse"),
                    Map.entry("java.time.OffsetDateTime", "parse"),
                    Map.entry("java.time.ZonedDateTime", "parse"));

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        if (round.processingOver() && !servicesWritten && !providers.isEmpty()) {
            servicesWritten = true;
            try (Writer writer = processingEnv.getFiler().createResource(
                    javax.tools.StandardLocation.CLASS_OUTPUT, "",
                    "META-INF/services/com.reactor.cachedb.core.codec.GeneratedJsonCodecProvider").openWriter()) {
                for (String provider : providers) writer.write(provider + "\n");
            } catch (IOException failure) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, failure.getMessage());
            }
        }
        for (Element element : round.getElementsAnnotatedWith(CacheJsonRecord.class)) {
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

    private String codecName(TypeElement type) {
        List<String> names = new ArrayList<>();
        for (Element current = type;
                current instanceof TypeElement enclosing;
                current = current.getEnclosingElement()) {
            if (!enclosing.getModifiers().contains(Modifier.PUBLIC))
                throw new IllegalArgumentException(
                        "@CacheJsonRecord requires public records and public enclosing types");
            names.add(0, enclosing.getSimpleName().toString());
        }
        String pkg =
                processingEnv.getElementUtils().getPackageOf(type).getQualifiedName().toString();
        return pkg + "." + String.join("_", names) + "JsonCodec";
    }

    private void rejectForeignMapping(Element element) {
        for (AnnotationMirror annotation : element.getAnnotationMirrors()) {
            String name = annotation.getAnnotationType().toString();
            if (name.startsWith("com.fasterxml.jackson.")
                    || name.startsWith("com.google.gson.annotations."))
                throw new IllegalArgumentException(
                        "Generated JSON does not interpret "
                                + name
                                + "; retain the configured Jackson path or supply an explicit"
                                + " JsonArrayCodec");
        }
    }

    private void generate(TypeElement record) throws IOException {
        if (record.getKind() != ElementKind.RECORD || !record.getTypeParameters().isEmpty())
            throw new IllegalArgumentException("@CacheJsonRecord requires a nongeneric record");
        String pkg =
                processingEnv.getElementUtils().getPackageOf(record).getQualifiedName().toString();
        if (pkg.isBlank())
            throw new IllegalArgumentException("JSON records require a named package");
        rejectForeignMapping(record);
        for (Element element : record.getEnclosedElements()) {
            rejectForeignMapping(element);
            if (element instanceof ExecutableElement method)
                for (var parameter : method.getParameters()) rejectForeignMapping(parameter);
        }
        String qualifiedCodec = codecName(record);
        String name = qualifiedCodec.substring(pkg.length() + 1);
        String type = record.getQualifiedName().toString();
        String providerName = name + "Provider";
        try (Writer writer = processingEnv.getFiler().createSourceFile(pkg + "." + providerName, record).openWriter()) {
            writer.write("package " + pkg + ";\npublic final class " + providerName
                    + " implements com.reactor.cachedb.core.codec.GeneratedJsonCodecProvider {\n"
                    + " public com.reactor.cachedb.core.codec.JsonArrayCodec<?> codecFor(Class<?> type) { return type == "
                    + type + ".class ? " + name + ".INSTANCE : null; }\n}\n");
        }
        providers.add(pkg + "." + providerName);
        List<? extends RecordComponentElement> fields = record.getRecordComponents();
        StringBuilder code = new StringBuilder("package " + pkg + ";\n\n");
        code.append("public final class ")
                .append(name)
                .append(" extends com.reactor.cachedb.json.StreamingJsonCodec<")
                .append(type)
                .append("> implements com.reactor.cachedb.json.GeneratedJsonWriter<")
                .append(type)
                .append("> {\n")
                .append(" public static final ")
                .append(name)
                .append(" INSTANCE = new ")
                .append(name)
                .append("();\n")
                .append(" public ")
                .append(name)
                .append("() { super(); }\n")
                .append(" public ")
                .append(name)
                .append("(com.reactor.cachedb.json.JsonCodecLimits limits) { super(limits); }\n")
                .append(" public Class<")
                .append(type)
                .append("> valueType() { return ")
                .append(type)
                .append(".class; }\n")
                .append(" public ")
                .append(type)
                .append(
                        " readValue(com.google.gson.stream.JsonReader reader,"
                                + " com.reactor.cachedb.json.JsonCodecLimits limits) throws"
                                + " java.io.IOException {\n")
                .append(" if (")
                .append(STRINGS)
                .append(".isNull(reader)) return null;\n");
        int index = 0;
        for (RecordComponentElement field : fields) {
            rejectForeignMapping(field);
            code.append(" ")
                    .append(field.asType())
                    .append(" v")
                    .append(index++)
                    .append(" = ")
                    .append(defaultValue(field.asType()))
                    .append(";\n");
        }
        code.append(
                " reader.beginObject();\n"
                        + " while (reader.hasNext()) {\n"
                        + " String key = reader.nextName();\n"
                        + " switch (key) {\n");
        index = 0;
        for (RecordComponentElement field : fields) {
            String expression = read(field.asType(), "reader", "limits", 0);
            code.append(" case \"").append(field.getSimpleName()).append("\" -> { ");
            if (field.asType().getKind().isPrimitive()) {
                code.append(boxed(field.asType()))
                        .append(" decoded = ")
                        .append(expression)
                        .append("; v")
                        .append(index)
                        .append(" = decoded == null ? ")
                        .append(defaultValue(field.asType()))
                        .append(" : decoded; ");
            } else code.append("v").append(index).append(" = ").append(expression).append("; ");
            code.append("}\n");
            index++;
        }
        code.append(" default -> ");
        if (record.getAnnotation(CacheJsonRecord.class).ignoreUnknown())
            code.append("reader.skipValue();\n");
        else code.append("throw new java.io.IOException(\"Unknown JSON field: \" + key);\n");
        code.append(" }\n }\n reader.endObject();\n return new ").append(type).append("(");
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) code.append(", ");
            code.append("v").append(i);
        }
        code.append(");\n }\n");
        appendWriter(code, type, fields, "com.google.gson.stream.JsonWriter");
        appendWriter(code, type, fields, "com.reactor.cachedb.json.JsonTokenWriter");
        code.append("}\n");
        try (Writer writer =
                processingEnv.getFiler().createSourceFile(qualifiedCodec, record).openWriter()) {
            writer.write(code.toString());
        }
    }

    private void appendWriter(
            StringBuilder code,
            String type,
            List<? extends RecordComponentElement> fields,
            String writerType) {
        code.append(" public void writeValue(")
                .append(writerType)
                .append(" writer, ")
                .append(type)
                .append(
                        " value, com.reactor.cachedb.json.JsonCodecLimits limits) throws"
                            + " java.io.IOException {\n")
                .append(
                        " if (value == null) { writer.nullValue(); return; }\n"
                            + " writer.beginObject();\n");
        int index = 0;
        for (var field : fields) {
            String local = "field" + index++;
            code.append(" var ")
                    .append(local)
                    .append(" = value.")
                    .append(field.getSimpleName())
                    .append("();\n")
                    .append(" writer.name(\"")
                    .append(field.getSimpleName())
                    .append("\");\n ")
                    .append(write(field.asType(), "writer", local, "limits", 0))
                    .append(";\n");
        }
        code.append(" writer.endObject();\n }\n");
    }

    private String boxed(TypeMirror type) {
        return type.getKind().isPrimitive()
                ? processingEnv
                        .getTypeUtils()
                        .boxedClass((PrimitiveType) type)
                        .getQualifiedName()
                        .toString()
                : type.toString();
    }

    private String defaultValue(TypeMirror type) {
        return switch (type.getKind()) {
            case BOOLEAN -> "false";
            case CHAR -> "'\\0'";
            case LONG -> "0L";
            case FLOAT -> "0F";
            case DOUBLE -> "0D";
            default -> type.getKind().isPrimitive() ? "0" : "null";
        };
    }

    private String read(TypeMirror type, String reader, String limits, int level) {
        String boxed = boxed(type);
        if (boxed.equals("java.lang.String"))
            return STRINGS + ".read(" + reader + ", " + limits + ")";
        if (READERS.containsKey(boxed))
            return VALUES + "." + READERS.get(boxed) + "(" + reader + ", " + limits + ")";
        if (PARSERS.containsKey(boxed))
            return VALUES
                    + ".mappedString("
                    + reader
                    + ", "
                    + limits
                    + ", "
                    + boxed
                    + "::"
                    + PARSERS.get(boxed)
                    + ")";
        Element element = processingEnv.getTypeUtils().asElement(type);
        if (element != null && element.getKind() == ElementKind.ENUM)
            return VALUES + ".mappedString(" + reader + ", " + limits + ", " + type + "::valueOf)";
        if (type instanceof DeclaredType declared) {
            String raw = ((TypeElement) declared.asElement()).getQualifiedName().toString();
            List<? extends TypeMirror> args = declared.getTypeArguments();
            if (raw.equals("java.util.List") && args.size() == 1
                    || raw.equals("java.util.Map")
                            && args.size() == 2
                            && args.get(0).toString().equals("java.lang.String")) {
                TypeMirror member = args.get(args.size() - 1);
                String r = "r" + level, l = "l" + level;
                return VALUES
                        + (args.size() == 1 ? ".list(" : ".map(")
                        + reader
                        + ", "
                        + limits
                        + ", ("
                        + r
                        + ", "
                        + l
                        + ") -> "
                        + read(member, r, l, level + 1)
                        + ")";
            }
            if (element != null && element.getKind() == ElementKind.RECORD) {
                String codec = codecName((TypeElement) element);
                if (element.getAnnotation(CacheJsonRecord.class) != null
                        || processingEnv.getElementUtils().getTypeElement(codec) != null)
                    return codec + ".INSTANCE.readValue(" + reader + ", " + limits + ")";
            }
        }
        throw new IllegalArgumentException(
                "Unsupported JSON component type: "
                        + type
                        + "; nested records require @CacheJsonRecord");
    }

    private String write(TypeMirror type, String writer, String value, String limits, int level) {
        String boxed = boxed(type);
        if (boxed.equals("java.lang.String"))
            return STRINGS + ".write(" + writer + ", " + value + ", " + limits + ")";
        if (READERS.containsKey(boxed)) {
            String method =
                    switch (boxed) {
                        case "java.lang.String" -> "string";
                        case "java.lang.Boolean" -> "bool";
                        case "java.lang.Character" -> "character";
                        default -> "number";
                    };
            return VALUES + "." + method + "(" + writer + ", " + value + ", " + limits + ")";
        }
        Element element = processingEnv.getTypeUtils().asElement(type);
        if (PARSERS.containsKey(boxed) || element != null && element.getKind() == ElementKind.ENUM)
            return VALUES
                    + ".string("
                    + writer
                    + ", "
                    + value
                    + " == null ? null : "
                    + value
                    + (element != null && element.getKind() == ElementKind.ENUM
                            ? ".name()"
                            : ".toString()")
                    + ", "
                    + limits
                    + ")";
        if (type instanceof DeclaredType declared) {
            String raw = ((TypeElement) declared.asElement()).getQualifiedName().toString();
            List<? extends TypeMirror> args = declared.getTypeArguments();
            if (raw.equals("java.util.List") && args.size() == 1
                    || raw.equals("java.util.Map")
                            && args.size() == 2
                            && args.get(0).toString().equals("java.lang.String")) {
                String w = "w" + level, v = "v" + level, l = "l" + level;
                return VALUES
                        + (args.size() == 1 ? ".list(" : ".map(")
                        + writer
                        + ", "
                        + value
                        + ", "
                        + limits
                        + ", ("
                        + w
                        + ", "
                        + v
                        + ", "
                        + l
                        + ") -> "
                        + write(args.get(args.size() - 1), w, v, l, level + 1)
                        + ")";
            }
            if (element != null && element.getKind() == ElementKind.RECORD) {
                String codec = codecName((TypeElement) element);
                if (element.getAnnotation(CacheJsonRecord.class) != null
                        || processingEnv.getElementUtils().getTypeElement(codec) != null)
                    return codec
                            + ".INSTANCE.writeValue("
                            + writer
                            + ", "
                            + value
                            + ", "
                            + limits
                            + ")";
            }
        }
        throw new IllegalArgumentException("Unsupported JSON component type: " + type);
    }
}
