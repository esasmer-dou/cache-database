package com.reactor.cachedb.spring.boot;

import com.reactor.cachedb.jdbc.CacheDbProviderUnavailableException;
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

public final class CacheDbProviderFailureAnalyzer
        extends AbstractFailureAnalyzer<CacheDbProviderUnavailableException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, CacheDbProviderUnavailableException cause) {
        String starter = switch (cause.providerId()) {
            case "postgres" -> "com.reactor.cachedb:cachedb-spring-boot-starter-postgres";
            case "mssql" -> "com.reactor.cachedb:cachedb-spring-boot-starter-mssql";
            default -> "a JdbcStorageProvider implementation registered through ServiceLoader";
        };
        return new FailureAnalysis(
                "CacheDB SQL provider '" + cause.providerId() + "' was selected but is not available. "
                        + "Detected providers: " + cause.availableProviders(),
                "Add " + starter + " to the application and keep cachedb.sql.provider aligned with that starter.",
                cause
        );
    }
}
