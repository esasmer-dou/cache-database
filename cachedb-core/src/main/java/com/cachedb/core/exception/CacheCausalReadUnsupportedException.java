package com.reactor.cachedb.core.exception;

/** The selected read cannot prove the active minimum-version contract. */
public final class CacheCausalReadUnsupportedException extends IllegalStateException {
    public CacheCausalReadUnsupportedException() {
        super("This read cannot certify the active causal requirements; use versioned point/bulk entity reads "
                + "or perform the eventually consistent read outside the causal scope");
    }
}
