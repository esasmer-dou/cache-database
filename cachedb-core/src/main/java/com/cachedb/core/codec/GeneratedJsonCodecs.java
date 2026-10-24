package com.reactor.cachedb.core.codec;

import java.util.ServiceLoader;

/** Resolves generated service entries once per value type, never during encode/decode. */
public final class GeneratedJsonCodecs {
    private GeneratedJsonCodecs() {}
    private static final ClassValue<JsonArrayCodec<?>> CODECS = new ClassValue<>() {
        protected JsonArrayCodec<?> computeValue(Class<?> type) {
            JsonArrayCodec<?> found = null;
            int count = 0;
            for (var provider : ServiceLoader.load(GeneratedJsonCodecProvider.class, type.getClassLoader())) {
                if (++count > 1024) throw new IllegalStateException("Too many generated JSON providers");
                var candidate = provider.codecFor(type);
                if (candidate == null) continue;
                if (!type.equals(candidate.valueType()) || found != null)
                    throw new IllegalStateException("Conflicting generated JSON codec: " + type.getName());
                found = candidate;
            }
            if (found == null) throw new IllegalArgumentException("No generated JSON codec for " + type.getName() + "; rebuild with @CacheJsonRecord and the matching annotation processor");
            return found;
        }
    };
    @SuppressWarnings("unchecked")
    public static <V> JsonArrayCodec<V> require(Class<V> type) {
        return (JsonArrayCodec<V>) CODECS.get(java.util.Objects.requireNonNull(type));
    }
}
