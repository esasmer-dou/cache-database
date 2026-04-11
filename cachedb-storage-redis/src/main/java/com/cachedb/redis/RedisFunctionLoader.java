package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.config.RedisFunctionsConfig;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.exceptions.JedisDataException;

public final class RedisFunctionLoader {

    private final JedisPooled jedis;
    private final RedisFunctionsConfig config;
    private final RedisFunctionLibrarySource source;

    public RedisFunctionLoader(
            JedisPooled jedis,
            RedisFunctionsConfig config,
            RedisFunctionLibrarySource source
    ) {
        this.jedis = jedis;
        this.config = config;
        this.source = source;
    }

    public void initialize() {
        if (!config.enabled() || !config.autoLoadLibrary()) {
            return;
        }

        String rendered = source.render(config);
        try {
            if (config.replaceLibraryOnLoad()) {
                jedis.functionLoadReplace(rendered);
            } else {
                jedis.functionLoad(rendered);
            }
        } catch (JedisDataException exception) {
            if (!config.strictLoading() && libraryExistsError(exception)) {
                return;
            }
            throw exception;
        }
    }

    private boolean libraryExistsError(JedisDataException exception) {
        String message = exception.getMessage();
        return message != null && message.contains("Library already exists");
    }
}
