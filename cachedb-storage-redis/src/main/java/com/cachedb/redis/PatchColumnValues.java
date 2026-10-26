package com.reactor.cachedb.redis;

/** PATCH is a new envelope kind: distinguish SQL NULL from every possible string value. */
final class PatchColumnValues {
    private PatchColumnValues() { }
    static String encode(Object value) { return value == null ? "~P:N" : "~P:V" + value; }
    static String decode(String value) {
        if ("~P:N".equals(value)) return null;
        if (value != null && value.startsWith("~P:V")) return value.substring(4);
        return "__NULL__".equals(value) ? null : value;
    }
}
