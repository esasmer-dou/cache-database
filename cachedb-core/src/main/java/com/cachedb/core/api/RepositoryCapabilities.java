package com.reactor.cachedb.core.api;

import java.util.EnumSet;
import java.util.List;

/** Compact immutable capability set; normal support checks do not allocate. */
public final class RepositoryCapabilities {
    private static final RepositoryCapabilities NONE = new RepositoryCapabilities(0L);
    private static final RepositoryCapabilities ALL = new RepositoryCapabilities(allMask());

    private final long mask;

    private RepositoryCapabilities(long mask) {
        this.mask = mask;
    }

    public static RepositoryCapabilities none() {
        return NONE;
    }

    public static RepositoryCapabilities all() {
        return ALL;
    }

    public static RepositoryCapabilities of(RepositoryCapability... capabilities) {
        if (capabilities == null || capabilities.length == 0) {
            return NONE;
        }
        long resolved = 0L;
        for (RepositoryCapability capability : capabilities) {
            if (capability == null) {
                throw new IllegalArgumentException("capabilities must not contain null");
            }
            resolved |= bit(capability);
        }
        return resolved == ALL.mask ? ALL : new RepositoryCapabilities(resolved);
    }

    public boolean supports(RepositoryCapability capability) {
        if (capability == null) {
            throw new IllegalArgumentException("capability must not be null");
        }
        return (mask & bit(capability)) != 0L;
    }

    public void require(RepositoryCapability capability, Class<?> implementationType) {
        if (!supports(capability)) {
            throw new RepositoryCapabilityUnavailableException(capability, implementationType);
        }
    }

    /** Intended for diagnostics and admin surfaces, not per-request hot paths. */
    public List<RepositoryCapability> asList() {
        EnumSet<RepositoryCapability> result = EnumSet.noneOf(RepositoryCapability.class);
        for (RepositoryCapability capability : RepositoryCapability.values()) {
            if (supports(capability)) {
                result.add(capability);
            }
        }
        return List.copyOf(result);
    }

    private static long bit(RepositoryCapability capability) {
        return 1L << capability.ordinal();
    }

    private static long allMask() {
        RepositoryCapability[] values = RepositoryCapability.values();
        return values.length == Long.SIZE ? -1L : (1L << values.length) - 1L;
    }
}
