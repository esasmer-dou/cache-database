package com.reactor.cachedb.core.query;

/** Conservative injective score domains. Other types retain their logical comparator. */
public final class RedisLexicalScoreContract {
    public static final long MAX_LONG53 = 9_007_199_254_740_991L;
    private RedisLexicalScoreContract() {}
    public static boolean long53Type(String type) { return "long".equals(type) || "java.lang.Long".equals(type); }
    public static boolean long53Value(Object value) {
        return value instanceof Long number && number >= -MAX_LONG53 && number <= MAX_LONG53;
    }
    /** Long is eligible only behind a namespace/index domain attestation, never from type alone. */
    public static boolean attestableType(String type) { return losslessType(type) || long53Type(type); }
    public static boolean losslessType(String type) {
        if (type == null) return false;
        return switch (type) {
            case "byte", "java.lang.Byte", "short", "java.lang.Short", "int", "java.lang.Integer",
                    "java.time.LocalDate" -> true;
            default -> false;
        };
    }
    public static boolean matchesRuntimeType(String type, Object value) {
        if (type == null) return false;
        return switch (type) {
            case "byte", "java.lang.Byte" -> value instanceof Byte;
            case "short", "java.lang.Short" -> value instanceof Short;
            case "int", "java.lang.Integer" -> value instanceof Integer;
            case "java.time.LocalDate" -> value instanceof java.time.LocalDate;
            case "long", "java.lang.Long" -> long53Value(value);
            default -> false;
        };
    }
}
