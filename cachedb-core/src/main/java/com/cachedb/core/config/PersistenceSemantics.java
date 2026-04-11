package com.reactor.cachedb.core.config;

public enum PersistenceSemantics {
    LATEST_STATE(true),
    EXACT_SEQUENCE(false);

    private final boolean stateCompactionEnabled;

    PersistenceSemantics(boolean stateCompactionEnabled) {
        this.stateCompactionEnabled = stateCompactionEnabled;
    }

    public boolean stateCompactionEnabled() {
        return stateCompactionEnabled;
    }
}
