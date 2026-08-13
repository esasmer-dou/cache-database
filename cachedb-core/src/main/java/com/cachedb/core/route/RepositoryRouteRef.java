package com.reactor.cachedb.core.route;

/** Stable, reflection-free reference to one compile-time generated repository route. */
public record RepositoryRouteRef(
        String repositoryName,
        String entityName,
        RepositoryRouteDefinition definition
) {
    public RepositoryRouteRef {
        repositoryName = requireText(repositoryName, "repositoryName");
        entityName = requireText(entityName, "entityName");
        if (definition == null) {
            throw new IllegalArgumentException("definition must not be null");
        }
    }

    public String methodName() {
        return definition.methodName();
    }

    public String routeName() {
        return definition.routeName();
    }

    public RepositoryRouteKind kind() {
        return definition.kind();
    }

    public boolean projectionBacked() {
        return definition.projectionBacked();
    }

    public RepositoryRouteRef requireKind(RepositoryRouteKind expected) {
        if (expected == null) {
            throw new IllegalArgumentException("expected must not be null");
        }
        if (kind() != expected) {
            throw new IllegalArgumentException(repositoryName + '#' + methodName()
                    + " is " + kind() + ", expected " + expected);
        }
        return this;
    }

    private static String requireText(String value, String name) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
