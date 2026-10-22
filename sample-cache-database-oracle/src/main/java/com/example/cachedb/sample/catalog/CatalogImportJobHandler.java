package com.example.cachedb.sample.catalog;

import com.reactor.cachedb.spring.boot.CacheDistributedJobContext;
import com.reactor.cachedb.spring.boot.CacheDistributedJobDefinition;
import com.reactor.cachedb.spring.boot.CacheDistributedJobHandler;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import redis.clients.jedis.exceptions.JedisDataException;

@Component
@ConditionalOnProperty(prefix = "sample.demo", name = "write-tools-enabled", havingValue = "true")
public final class CatalogImportJobHandler
        implements CacheDistributedJobHandler.Typed<CatalogImportCommand> {
    public static final CacheDistributedJobDefinition<CatalogImportCommand> DEFINITION =
            CacheDistributedJobDefinition.of("sample.catalog.import", CatalogImportCommand.class);
    private final CatalogImportService imports;

    public CatalogImportJobHandler(CatalogImportService imports) {
        this.imports = imports;
    }

    @Override
    public CacheDistributedJobDefinition<CatalogImportCommand> definition() {
        return DEFINITION;
    }

    @Override
    public Object execute(CatalogImportCommand command, CacheDistributedJobContext context) {
        try {
            return imports.execute(command);
        } catch (JedisDataException failure) {
            String message = failure.getMessage();
            if (message != null
                    && (message.endsWith("Batch id reused with different operations")
                            || message.endsWith("Batch optimistic version conflict"))) {
                // A deterministic business rejection is not a transient connection failure.
                return new CatalogImportService.Result("REJECTED", null, false, message);
            }
            throw failure;
        }
    }
}
