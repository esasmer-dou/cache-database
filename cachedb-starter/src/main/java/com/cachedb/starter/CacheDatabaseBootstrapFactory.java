package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.config.CacheDatabaseConfig;
import com.reactor.cachedb.core.config.CacheDatabaseConfigOverrides;
import redis.clients.jedis.JedisPooled;

import javax.sql.DataSource;

public final class CacheDatabaseBootstrapFactory {

    private CacheDatabaseBootstrapFactory() {
    }

    public static CacheDatabaseConfig applyGlobalAndScopedOverrides(
            CacheDatabaseConfig baseConfig,
            String scopedPrefix
    ) {
        CacheDatabaseConfig global = CacheDatabaseConfigOverrides.applySystemProperties(baseConfig);
        return CacheDatabaseConfigOverrides.applySystemProperties(global, scopedPrefix);
    }

    public static JedisPooled redisClient(String prefix, String defaultUri) {
        return RedisConnectionConfig.fromSystemProperties(prefix, defaultUri).createClient();
    }

    public static DataSource postgresDataSource(
            String prefix,
            String defaultJdbcUrl,
            String defaultUsername,
            String defaultPassword
    ) {
        return PostgresConnectionConfig.fromSystemProperties(prefix, defaultJdbcUrl, defaultUsername, defaultPassword)
                .createDataSource();
    }
}
