package com.reactor.cachedb.core.route;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Compile-time repository inventory; it requires no classpath or runtime reflection. */
public record RepositoryRouteCatalog(
        String repositoryName,
        String entityName,
        List<RepositoryRouteDefinition> routes
) {
    public RepositoryRouteCatalog {
        repositoryName = requireText(repositoryName, "repositoryName");
        entityName = requireText(entityName, "entityName");
        routes = routes == null ? List.of() : List.copyOf(routes);
        Set<String> methodNames = new HashSet<>(Math.max(16, routes.size() * 2));
        for (RepositoryRouteDefinition route : routes) {
            if (route == null) {
                throw new IllegalArgumentException("routes must not contain null");
            }
            if (!methodNames.add(route.methodName())) {
                throw new IllegalArgumentException("duplicate repository route method: " + route.methodName());
            }
        }
    }

    public int count(RepositoryRouteKind kind) {
        if (kind == null) {
            throw new IllegalArgumentException("kind must not be null");
        }
        int count = 0;
        for (RepositoryRouteDefinition route : routes) {
            if (route.kind() == kind) {
                count++;
            }
        }
        return count;
    }

    public List<RepositoryRouteDefinition> routes(RepositoryRouteKind kind) {
        if (kind == null) {
            throw new IllegalArgumentException("kind must not be null");
        }
        ArrayList<RepositoryRouteDefinition> matching = new ArrayList<>();
        for (RepositoryRouteDefinition route : routes) {
            if (route.kind() == kind) {
                matching.add(route);
            }
        }
        return List.copyOf(matching);
    }

    public Optional<RepositoryRouteRef> findMethod(String methodName) {
        String normalized = requireText(methodName, "methodName");
        for (RepositoryRouteDefinition route : routes) {
            if (route.methodName().equals(normalized)) {
                return Optional.of(new RepositoryRouteRef(repositoryName, entityName, route));
            }
        }
        return Optional.empty();
    }

    public RepositoryRouteRef requireMethod(String methodName) {
        return findMethod(methodName).orElseThrow(() -> new IllegalArgumentException(
                "Unknown repository route method: " + repositoryName + '#' + methodName));
    }

    private static String requireText(String value, String name) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
