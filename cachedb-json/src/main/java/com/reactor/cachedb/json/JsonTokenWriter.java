package com.reactor.cachedb.json;

import java.io.IOException;
import java.io.Writer;
import java.util.Objects;

/**
 * Strict token output for generated DTOs. No raw JSON injection, formatter, reflection or registry.
 */
public final class JsonTokenWriter implements AutoCloseable {
    private static final int DOCUMENT = 0,
            DONE = 1,
            ARRAY_EMPTY = 2,
            ARRAY = 3,
            OBJECT_EMPTY = 4,
            OBJECT = 5,
            NAME = 6;
    private static final String HEX = "0123456789abcdef";
    private final Writer target;
    private final JsonCodecLimits limits;
    private final int[] scopes;
    private int size = 1;
    private boolean closed;

    JsonTokenWriter(Writer target, JsonCodecLimits limits) {
        this.target = Objects.requireNonNull(target, "target");
        this.limits = Objects.requireNonNull(limits, "limits");
        scopes = new int[limits.maxDepth() + 1];
    }

    boolean usesLimits(JsonCodecLimits expected) {
        return limits == expected;
    }

    private int top() {
        if (closed) throw new IllegalStateException("JSON writer is closed");
        return scopes[size - 1];
    }

    private void beforeValue() throws IOException {
        switch (top()) {
            case DOCUMENT -> scopes[size - 1] = DONE;
            case ARRAY_EMPTY -> scopes[size - 1] = ARRAY;
            case ARRAY -> target.write(',');
            case NAME -> {
                target.write(':');
                scopes[size - 1] = OBJECT;
            }
            default -> throw new IOException("JSON value is not allowed in this scope");
        }
    }

    private void begin(int scope, char token) throws IOException {
        if (size > limits.maxDepth()) throw new IOException("JSON nesting budget exceeded");
        beforeValue();
        scopes[size++] = scope;
        target.write(token);
    }

    public void beginArray() throws IOException {
        begin(ARRAY_EMPTY, '[');
    }

    public void beginObject() throws IOException {
        begin(OBJECT_EMPTY, '{');
    }

    public void endArray() throws IOException {
        int scope = top();
        if (scope != ARRAY_EMPTY && scope != ARRAY)
            throw new IOException("JSON array scope mismatch");
        size--;
        target.write(']');
    }

    public void endObject() throws IOException {
        int scope = top();
        if (scope != OBJECT_EMPTY && scope != OBJECT)
            throw new IOException("JSON object scope mismatch");
        size--;
        target.write('}');
    }

    public void name(String name) throws IOException {
        Objects.requireNonNull(name, "name");
        int scope = top();
        if (scope != OBJECT_EMPTY && scope != OBJECT)
            throw new IOException("JSON field name is not allowed in this scope");
        JsonStrings.validate(name, limits);
        if (scope == OBJECT) target.write(',');
        quoted(name);
        scopes[size - 1] = NAME;
    }

    public void nullValue() throws IOException {
        beforeValue();
        target.write("null");
    }

    public void value(boolean value) throws IOException {
        beforeValue();
        target.write(value ? "true" : "false");
    }

    public void value(Boolean value) throws IOException {
        if (value == null) nullValue();
        else value(value.booleanValue());
    }

    public void value(String value) throws IOException {
        if (value == null) {
            nullValue();
            return;
        }
        JsonStrings.validate(value, limits);
        beforeValue();
        quoted(value);
    }

    public void value(Number value) throws IOException {
        value(value, limits);
    }

    void value(Number value, JsonCodecLimits numberLimits) throws IOException {
        if (value == null) {
            nullValue();
            return;
        }
        number(JsonValues.numberToken(value, numberLimits));
    }

    public void value(long value) throws IOException {
        number(Long.toString(value));
    }

    public void value(double value) throws IOException {
        number(Double.toString(value));
    }

    public void value(float value) throws IOException {
        number(Float.toString(value));
    }

    private void number(String token) throws IOException {
        if (token.length() > limits.maxNumberDigits())
            throw new IOException("JSON number budget exceeded");
        int i = 0, length = token.length();
        if (i < length && token.charAt(i) == '-') i++;
        if (i >= length) throw new IOException("Invalid JSON number");
        char first = token.charAt(i++);
        if (first == '0') {
            /* A leading zero must not be followed by another integer digit. */
        } else if (first >= '1' && first <= '9') {
            while (i < length && digit(token.charAt(i))) i++;
        } else throw new IOException("Invalid JSON number");
        if (i < length && token.charAt(i) == '.') {
            int begin = ++i;
            while (i < length && digit(token.charAt(i))) i++;
            if (begin == i) throw new IOException("Invalid JSON fraction");
        }
        if (i < length && (token.charAt(i) == 'e' || token.charAt(i) == 'E')) {
            i++;
            if (i < length && (token.charAt(i) == '+' || token.charAt(i) == '-')) i++;
            int begin = i, magnitude = 0;
            while (i < length && digit(token.charAt(i))) {
                magnitude = magnitude * 10 + token.charAt(i++) - '0';
                if (magnitude > limits.maxNumberDigits())
                    throw new IOException("JSON exponent budget exceeded");
            }
            if (begin == i) throw new IOException("Invalid JSON exponent");
        }
        if (i != length) throw new IOException("Invalid JSON number");
        beforeValue();
        target.write(token);
    }

    private static boolean digit(char value) {
        return value >= '0' && value <= '9';
    }

    private void quoted(String value) throws IOException {
        target.write('"');
        int plain = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            String escape =
                    switch (c) {
                        case '"' -> "\\\"";
                        case '\\' -> "\\\\";
                        case '\b' -> "\\b";
                        case '\f' -> "\\f";
                        case '\n' -> "\\n";
                        case '\r' -> "\\r";
                        case '\t' -> "\\t";
                        case '\u2028' -> "\\u2028";
                        case '\u2029' -> "\\u2029";
                        default -> null;
                    };
            if (escape == null && c >= 32) continue;
            target.write(value, plain, i - plain);
            if (escape != null) target.write(escape);
            else {
                target.write("\\u00");
                target.write(HEX.charAt(c >>> 4));
                target.write(HEX.charAt(c & 15));
            }
            plain = i + 1;
        }
        target.write(value, plain, value.length() - plain);
        target.write('"');
    }

    public void flush() throws IOException {
        top();
        target.flush();
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        if (size != 1 || scopes[0] != DONE) throw new IOException("Incomplete JSON document");
        closed = true;
        target.flush();
    }
}
