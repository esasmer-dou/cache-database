package com.reactor.cachedb.migration;

/** Stable names of the OpenRewrite recipes shipped by CacheDB. */
public final class CacheDbMigrationRecipes {
    public static final String USE_POSTGRES_STARTER = "com.reactor.cachedb.migrate.UsePostgresStarter";
    public static final String USE_MSSQL_STARTER = "com.reactor.cachedb.migrate.UseMssqlStarter";

    private CacheDbMigrationRecipes() {
    }
}
