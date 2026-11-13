package com.reactor.cachedb.core.crypto;

import com.reactor.cachedb.core.exception.CacheDatabaseException;
import java.util.Objects;

/** Only fixed codes are observable; provider/JCA causes can contain secrets and are discarded. */
public final class CacheEncryptionException extends CacheDatabaseException {
    private static final long serialVersionUID = 1L;

    public enum Code {
        INVALID_CONFIGURATION, INVALID_CONTEXT, INVALID_TEXT, FIELD_LIMIT,
        INVALID_ENVELOPE, UNKNOWN_KEY, AUTHENTICATION_FAILED,
        PROVIDER_FAILURE, CRYPTO_FAILURE, SATURATED, INTENT_KEY_UNAVAILABLE, UNSUPPORTED_SOURCE_OPERATION
    }

    private final Code code;

    public CacheEncryptionException(Code code) {
        super("CacheDB encryption: " + Objects.requireNonNull(code, "code").name());
        this.code = code;
    }

    public Code code() { return code; }
}
