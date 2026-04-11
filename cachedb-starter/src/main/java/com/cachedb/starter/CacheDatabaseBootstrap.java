package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.config.CacheDatabaseConfig;
import com.reactor.cachedb.core.config.KeyspaceConfig;
import redis.clients.jedis.JedisPooled;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

public final class CacheDatabaseBootstrap {

    private final DataSource dataSource;
    private JedisPooled foregroundJedis;
    private JedisPooled backgroundJedis;
    private String foregroundRedisUri;
    private String backgroundRedisUri;
    private CacheDatabaseConfig config = CacheDatabaseConfig.defaults();
    private final ArrayList<Consumer<CacheDatabase>> registrations = new ArrayList<>();
    private final ArrayList<Consumer<CacheDatabaseConfig.Builder>> configCustomizers = new ArrayList<>();

    private CacheDatabaseBootstrap(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public static CacheDatabaseBootstrap using(DataSource dataSource) {
        return new CacheDatabaseBootstrap(dataSource);
    }

    public static CacheDatabaseBootstrap using(JedisPooled jedis, DataSource dataSource) {
        return using(dataSource).redis(jedis);
    }

    public static CacheDatabaseBootstrap using(
            JedisPooled foregroundJedis,
            JedisPooled backgroundJedis,
            DataSource dataSource
    ) {
        return using(dataSource).redis(foregroundJedis, backgroundJedis);
    }

    public static CacheDatabaseBootstrap connect(String redisUri, DataSource dataSource) {
        return using(dataSource).redis(redisUri);
    }

    public static CacheDatabaseBootstrap connect(String foregroundRedisUri, String backgroundRedisUri, DataSource dataSource) {
        return using(dataSource).redis(foregroundRedisUri, backgroundRedisUri);
    }

    public CacheDatabaseBootstrap redis(JedisPooled jedis) {
        return redis(jedis, jedis);
    }

    public CacheDatabaseBootstrap redis(JedisPooled foregroundJedis, JedisPooled backgroundJedis) {
        this.foregroundJedis = Objects.requireNonNull(foregroundJedis, "foregroundJedis");
        this.backgroundJedis = Objects.requireNonNull(backgroundJedis, "backgroundJedis");
        this.foregroundRedisUri = null;
        this.backgroundRedisUri = null;
        return this;
    }

    public CacheDatabaseBootstrap redis(String redisUri) {
        return redis(redisUri, redisUri);
    }

    public CacheDatabaseBootstrap redis(String foregroundRedisUri, String backgroundRedisUri) {
        this.foregroundRedisUri = requireText(foregroundRedisUri, "foregroundRedisUri");
        this.backgroundRedisUri = requireText(backgroundRedisUri, "backgroundRedisUri");
        this.foregroundJedis = null;
        this.backgroundJedis = null;
        return this;
    }

    public CacheDatabaseBootstrap config(CacheDatabaseConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        this.configCustomizers.clear();
        return this;
    }

    public CacheDatabaseBootstrap customize(Consumer<CacheDatabaseConfig.Builder> customizer) {
        this.configCustomizers.add(Objects.requireNonNull(customizer, "customizer"));
        return this;
    }

    public CacheDatabaseBootstrap development() {
        this.config = CacheDatabaseProfiles.development();
        this.configCustomizers.clear();
        return this;
    }

    public CacheDatabaseBootstrap production() {
        this.config = CacheDatabaseProfiles.production();
        this.configCustomizers.clear();
        return this;
    }

    public CacheDatabaseBootstrap benchmark() {
        this.config = CacheDatabaseProfiles.benchmark();
        this.configCustomizers.clear();
        return this;
    }

    public CacheDatabaseBootstrap memoryConstrained() {
        this.config = CacheDatabaseProfiles.memoryConstrained();
        this.configCustomizers.clear();
        return this;
    }

    public CacheDatabaseBootstrap minimalOverhead() {
        this.config = CacheDatabaseProfiles.minimalOverhead();
        this.configCustomizers.clear();
        return this;
    }

    public CacheDatabaseBootstrap keyPrefix(String keyPrefix) {
        String normalizedKeyPrefix = requireText(keyPrefix, "keyPrefix");
        return customize(builder -> {
            KeyspaceConfig baseKeyspace = config.keyspace();
            builder.keyspace(baseKeyspace.toBuilder().keyPrefix(normalizedKeyPrefix).build());
        });
    }

    public CacheDatabaseBootstrap register(Consumer<CacheDatabase> registration) {
        this.registrations.add(Objects.requireNonNull(registration, "registration"));
        return this;
    }

    public CacheDatabase build() {
        CacheDatabaseConfig resolvedConfig = previewConfig();
        JedisPooled resolvedForeground = foregroundJedis;
        JedisPooled resolvedBackground = backgroundJedis;
        boolean createdForeground = false;
        boolean createdBackground = false;

        if (resolvedForeground == null) {
            resolvedForeground = new JedisPooled(requireText(foregroundRedisUri, "foregroundRedisUri"));
            createdForeground = true;
        }
        if (resolvedBackground == null) {
            resolvedBackground = new JedisPooled(requireText(backgroundRedisUri, "backgroundRedisUri"));
            createdBackground = true;
        }

        CacheDatabase cacheDatabase = null;
        try {
            cacheDatabase = new CacheDatabase(resolvedForeground, resolvedBackground, dataSource, resolvedConfig);
            for (Consumer<CacheDatabase> registration : registrations) {
                registration.accept(cacheDatabase);
            }
            return cacheDatabase;
        } catch (RuntimeException exception) {
            if (cacheDatabase != null) {
                try {
                    cacheDatabase.close();
                } catch (Exception ignored) {
                    // Best-effort close while unwinding bootstrap failure.
                }
            } else {
                closeIfCreated(createdForeground, resolvedForeground);
                closeIfCreated(createdBackground, resolvedBackground, resolvedForeground);
            }
            throw exception;
        }
    }

    public CacheDatabase start() {
        CacheDatabase cacheDatabase = build();
        try {
            cacheDatabase.start();
            return cacheDatabase;
        } catch (RuntimeException exception) {
            try {
                cacheDatabase.close();
            } catch (Exception ignored) {
                // Best-effort close while unwinding startup failure.
            }
            throw exception;
        }
    }

    public CacheDatabaseConfig previewConfig() {
        if (configCustomizers.isEmpty()) {
            return config;
        }
        CacheDatabaseConfig.Builder builder = config.toBuilder();
        for (Consumer<CacheDatabaseConfig.Builder> customizer : List.copyOf(configCustomizers)) {
            customizer.accept(builder);
        }
        return builder.build();
    }

    private static void closeIfCreated(boolean created, JedisPooled jedis) {
        if (!created || jedis == null) {
            return;
        }
        jedis.close();
    }

    private static void closeIfCreated(boolean created, JedisPooled jedis, JedisPooled alreadyClosedGuard) {
        if (!created || jedis == null || jedis == alreadyClosedGuard) {
            return;
        }
        jedis.close();
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(fieldName + " must be configured before building CacheDatabase");
        }
        return value;
    }
}
