package com.reactor.cachedb.spring.boot;

import com.reactor.cachedb.jdbc.CacheDbProviderAmbiguousException;
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

public final class CacheDbProviderAmbiguousFailureAnalyzer
        extends AbstractFailureAnalyzer<CacheDbProviderAmbiguousException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, CacheDbProviderAmbiguousException cause) {
        return new FailureAnalysis(
                "CacheDB found multiple SQL providers: " + cause.availableProviders(),
                "Keep one provider starter or set cachedb.sql.provider=POSTGRES or MSSQL explicitly.",
                cause
        );
    }
}
