package com.reactor.cachedb.core.config;

import com.reactor.cachedb.core.query.HardLimitQueryClass;

public record HardLimitQueryPolicy(
        String namespace,
        HardLimitQueryClass queryClass,
        boolean shedReads,
        boolean shedLearning
) {
    public boolean matches(String candidateNamespace, HardLimitQueryClass candidateQueryClass) {
        return queryClass == candidateQueryClass
                && namespace != null
                && candidateNamespace != null
                && ("*".equals(namespace) || namespace.equals(candidateNamespace));
    }
}
