package com.reactor.cachedb.core.observe;

public enum Outcome {
    HIT("hit"), MISS("miss"), TOMBSTONE("tombstone"), ERROR("error");
    private final String tag;
    Outcome(String tag) { this.tag = tag; }
    public String tag() { return tag; }
}
