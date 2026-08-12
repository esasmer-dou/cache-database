package com.reactor.cachedb.core.api;

import java.util.Objects;

/** Raised when a caller uses an optional low-level repository operation without checking support. */
public final class RepositoryCapabilityUnavailableException extends UnsupportedOperationException {
    private final RepositoryCapability capability;
    private final String implementationType;

    public RepositoryCapabilityUnavailableException(
            RepositoryCapability capability,
            Class<?> implementationType
    ) {
        super(message(capability, implementationType));
        this.capability = Objects.requireNonNull(capability, "capability");
        this.implementationType = implementationType == null ? "unknown" : implementationType.getName();
    }

    public RepositoryCapability capability() {
        return capability;
    }

    public String implementationType() {
        return implementationType;
    }

    private static String message(RepositoryCapability capability, Class<?> implementationType) {
        Objects.requireNonNull(capability, "capability");
        String type = implementationType == null ? "unknown" : implementationType.getName();
        return "Repository capability " + capability + " is not supported by " + type
                + ". Check repository.capabilities() or use the generated CacheDbRepository surface.";
    }
}
