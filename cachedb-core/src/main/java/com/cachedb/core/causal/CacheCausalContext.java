package com.reactor.cachedb.core.causal;

import com.reactor.cachedb.core.model.WriteReceipt;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Explicit bounded minimum-version requirements, not authentication or a distributed transaction. */
public final class CacheCausalContext {
    private static final int MAX_ENTRIES = 128, MAX_TOKEN = 4096;
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    private record Key(String namespace, String id) {}
    private CacheCausalContext() {}

    private static Key key(String namespace, Object id) {
        String value = Objects.requireNonNull(id, "id").toString();
        if (namespace == null || namespace.isBlank() || namespace.length() > MAX_TOKEN || value.length() > MAX_TOKEN)
            throw new IllegalArgumentException("Invalid causal identity");
        checkUnicode(namespace); checkUnicode(value);
        return new Key(namespace, value);
    }
    public static Scope withMinVersion(String namespace, Object id, long version) {
        if (version <= 0) throw new IllegalArgumentException("Causal version must be positive");
        var values = currentValues();
        values.merge(key(namespace, id), version, Math::max);
        return bind(values);
    }
    public static Scope withReceipt(String namespace, WriteReceipt<?, ?> receipt) {
        Objects.requireNonNull(receipt, "receipt");
        if (!receipt.redisNamespace().equals(namespace)) throw new IllegalArgumentException("Receipt namespace mismatch");
        return withMinVersion(namespace, receipt.id(), receipt.version());
    }
    public static long requiredVersion(String namespace, Object id) {
        Scope scope = CURRENT.get();
        return scope == null ? 0 : scope.values.getOrDefault(key(namespace, id), 0L);
    }
    public static Scope withToken(String token) {
        if (token == null || token.length() > MAX_TOKEN) throw new IllegalArgumentException("Invalid causal token size");
        var values = currentValues();
        if (!token.isEmpty()) {
            String[] parts = token.split(";", -1);
            if (parts.length > MAX_ENTRIES) throw new IllegalArgumentException("Too many causal entries");
            for (String part : parts) {
                String[] fields = part.split("\\|", -1);
                if (fields.length != 3) throw new IllegalArgumentException("Malformed causal token");
                long version;
                try { version = Long.parseLong(fields[2]); }
                catch (NumberFormatException invalid) { throw new IllegalArgumentException("Invalid causal version", invalid); }
                if (version <= 0) throw new IllegalArgumentException("Causal version must be positive");
                values.merge(key(decode(fields[0]), decode(fields[1])), version, Math::max);
            }
        }
        return bind(values);
    }
    public static String encodeToken() {
        Scope scope = CURRENT.get();
        return scope == null ? "" : encode(scope.values);
    }
    private static LinkedHashMap<Key, Long> currentValues() {
        Scope scope = CURRENT.get();
        return scope == null ? new LinkedHashMap<>() : new LinkedHashMap<>(scope.values);
    }
    private static Scope bind(Map<Key, Long> values) {
        if (values.size() > MAX_ENTRIES) throw new IllegalArgumentException("Too many causal entries");
        encode(values);
        Scope previous = CURRENT.get();
        if (previous != null && previous.depth >= 64) throw new IllegalStateException("Causal scope nesting exceeds 64");
        Scope scope = new Scope(Collections.unmodifiableMap(new LinkedHashMap<>(values)), previous);
        CURRENT.set(scope);
        return scope;
    }
    private static String encode(Map<Key, Long> values) {
        StringBuilder token = new StringBuilder();
        values.entrySet().stream().sorted(Map.Entry.comparingByKey(
                java.util.Comparator.comparing(Key::namespace).thenComparing(Key::id))).forEach(entry -> {
            if (!token.isEmpty()) token.append(';');
            token.append(encode(entry.getKey().namespace)).append('|').append(encode(entry.getKey().id))
                    .append('|').append(entry.getValue());
            if (token.length() > MAX_TOKEN) throw new IllegalArgumentException("Causal token exceeds 4096 characters");
        });
        return token.toString();
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static void checkUnicode(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i == value.length() || !Character.isLowSurrogate(value.charAt(i))) throw new IllegalArgumentException("Invalid causal Unicode");
            } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("Invalid causal Unicode");
        }
    }
    private static String decode(String value) {
        var bytes = new java.io.ByteArrayOutputStream(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '%') {
                if (i + 2 >= value.length()) throw new IllegalArgumentException("Malformed causal encoding");
                int high = Character.digit(value.charAt(++i),16), low = Character.digit(value.charAt(++i),16);
                if (high < 0 || low < 0) throw new IllegalArgumentException("Malformed causal encoding");
                bytes.write(high * 16 + low);
            } else {
                if (c > 127) throw new IllegalArgumentException("Causal identities must be URL encoded");
                bytes.write(c == '+' ? ' ' : c);
            }
        }
        try { return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString(); }
        catch (java.nio.charset.CharacterCodingException invalid) { throw new IllegalArgumentException("Invalid causal UTF-8", invalid); }
    }
    public static final class Scope implements AutoCloseable {
        private final Map<Key, Long> values;
        private final Scope previous;
        private final Thread owner = Thread.currentThread();
        private final int depth;
        private boolean closed;
        private Scope(Map<Key, Long> values, Scope previous) {
            this.values = values; this.previous = previous; depth = previous == null ? 1 : previous.depth + 1;
        }
        @Override public void close() {
            if (Thread.currentThread() != owner) throw new IllegalStateException("Causal scope belongs to another thread");
            if (closed) return;
            if (CURRENT.get() != this) throw new IllegalStateException("Causal scopes must close in reverse order");
            closed = true;
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }
}
