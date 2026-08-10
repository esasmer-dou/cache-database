package com.reactor.cachedb.processor;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** Resolves substituted generic repository supertypes at compile time. */
final class RepositoryTypeResolver {
    private final ProcessingEnvironment processingEnvironment;

    RepositoryTypeResolver(ProcessingEnvironment processingEnvironment) {
        this.processingEnvironment = Objects.requireNonNull(processingEnvironment, "processingEnvironment");
    }

    DeclaredType findSupertype(TypeMirror candidate, String targetErasure) {
        return findSupertype(candidate, targetErasure, new HashSet<>());
    }

    private DeclaredType findSupertype(TypeMirror candidate, String targetErasure, Set<String> visited) {
        if (!(candidate instanceof DeclaredType declared)) {
            return null;
        }
        String signature = declared.toString();
        if (!visited.add(signature)) {
            return null;
        }
        String erasure = processingEnvironment.getTypeUtils().erasure(declared).toString();
        if (erasure.equals(targetErasure)) {
            return declared;
        }
        for (TypeMirror parent : processingEnvironment.getTypeUtils().directSupertypes(declared)) {
            DeclaredType match = findSupertype(parent, targetErasure, visited);
            if (match != null) {
                return match;
            }
        }
        return null;
    }
}
