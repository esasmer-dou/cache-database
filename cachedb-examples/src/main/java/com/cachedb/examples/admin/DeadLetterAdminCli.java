package com.reactor.cachedb.examples.admin;

import com.reactor.cachedb.core.config.CacheDatabaseConfig;
import com.reactor.cachedb.core.config.DeadLetterRecoveryConfig;
import com.reactor.cachedb.core.config.RedisFunctionsConfig;
import com.reactor.cachedb.core.config.WriteBehindConfig;
import com.reactor.cachedb.core.queue.AdminExportFormat;
import com.reactor.cachedb.core.queue.DeadLetterQuery;
import com.reactor.cachedb.core.queue.ReconciliationQuery;
import com.reactor.cachedb.starter.CacheDatabaseBootstrapFactory;
import com.reactor.cachedb.starter.CacheDatabase;
import com.reactor.cachedb.starter.GeneratedCacheBindingsDiscovery;
import redis.clients.jedis.JedisPooled;

public final class DeadLetterAdminCli {

    private DeadLetterAdminCli() {
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            throw new IllegalArgumentException("Expected command: list-dlq | list-reconciliation | list-archive | diagnostics | incidents | incident-history | export-dlq | export-reconciliation | export-archive | export-diagnostics | export-incidents | replay | dry-run-replay | skip | close | bulk-replay | bulk-skip | bulk-close | health | metrics");
        }

        String redisUri = System.getProperty("cachedb.admin.redisUri", "redis://default:welcome1@127.0.0.1:6379");
        String jdbcUrl = System.getProperty("cachedb.admin.jdbcUrl", "jdbc:postgresql://127.0.0.1:5432/postgres");
        String jdbcUser = System.getProperty("cachedb.admin.jdbcUser", "postgres");
        String jdbcPassword = System.getProperty("cachedb.admin.jdbcPassword", "postgresql");
        String deadLetterStreamKey = System.getProperty("cachedb.admin.deadLetterStreamKey", "cachedb:stream:write-behind:dlq");
        String reconciliationStreamKey = System.getProperty("cachedb.admin.reconciliationStreamKey", "cachedb:stream:write-behind:reconciliation");
        String archiveStreamKey = System.getProperty("cachedb.admin.archiveStreamKey", "cachedb:stream:write-behind:archive");

        CacheDatabaseConfig baseConfig = CacheDatabaseConfig.builder()
                .writeBehind(WriteBehindConfig.builder()
                        .deadLetterStreamKey(deadLetterStreamKey)
                        .build())
                .redisFunctions(RedisFunctionsConfig.builder().enabled(false).build())
                .deadLetterRecovery(DeadLetterRecoveryConfig.builder()
                        .reconciliationStreamKey(reconciliationStreamKey)
                        .archiveStreamKey(archiveStreamKey)
                        .build())
                .build();
        CacheDatabaseConfig config = CacheDatabaseBootstrapFactory.applyGlobalAndScopedOverrides(
                baseConfig,
                "cachedb.admin.config"
        );

        try (JedisPooled jedis = CacheDatabaseBootstrapFactory.redisClient("cachedb.admin.redis", redisUri);
             CacheDatabase cacheDatabase = new CacheDatabase(
                     jedis,
                     CacheDatabaseBootstrapFactory.postgresDataSource("cachedb.admin.postgres", jdbcUrl, jdbcUser, jdbcPassword),
                     config
             )) {
            GeneratedCacheBindingsDiscovery.registerDiscovered(
                    cacheDatabase,
                    cacheDatabase.config().resourceLimits().defaultCachePolicy(),
                    DeadLetterAdminCli.class.getClassLoader()
            );
            String command = args[0];
            switch (command) {
                case "list-dlq" -> System.out.println(cacheDatabase.admin().deadLetters(buildDeadLetterQuery(args, 1)).items());
                case "list-reconciliation" -> System.out.println(cacheDatabase.admin().reconciliation(buildReconciliationQuery(args, 1)).items());
                case "list-archive" -> System.out.println(cacheDatabase.admin().archive(buildReconciliationQuery(args, 1)).items());
                case "diagnostics" -> System.out.println(cacheDatabase.admin().diagnostics(parseLimit(args, 1)));
                case "incidents" -> System.out.println(cacheDatabase.admin().incidents());
                case "incident-history" -> System.out.println(cacheDatabase.admin().incidentHistory(parseLimit(args, 1)));
                case "export-dlq" -> System.out.println(cacheDatabase.admin().exportDeadLetters(
                        buildDeadLetterQuery(args, 2),
                        parseFormat(requiredArg(args, 1))
                ).content());
                case "export-reconciliation" -> System.out.println(cacheDatabase.admin().exportReconciliation(
                        buildReconciliationQuery(args, 2),
                        parseFormat(requiredArg(args, 1))
                ).content());
                case "export-archive" -> System.out.println(cacheDatabase.admin().exportArchive(
                        buildReconciliationQuery(args, 2),
                        parseFormat(requiredArg(args, 1))
                ).content());
                case "export-diagnostics" -> System.out.println(cacheDatabase.admin().exportDiagnostics(
                        parseFormat(requiredArg(args, 1)),
                        parseLimit(args, 2)
                ).content());
                case "export-incidents" -> System.out.println(cacheDatabase.admin().exportIncidents(
                        parseFormat(requiredArg(args, 1)),
                        parseLimit(args, 2)
                ).content());
                case "replay" -> System.out.println(cacheDatabase.admin().replay(requiredArg(args, 1), optionalArg(args, 2)));
                case "dry-run-replay" -> System.out.println(cacheDatabase.admin().dryRunReplay(requiredArg(args, 1)));
                case "skip" -> System.out.println(cacheDatabase.admin().skip(requiredArg(args, 1), optionalArg(args, 2)));
                case "close" -> System.out.println(cacheDatabase.admin().close(requiredArg(args, 1), optionalArg(args, 2)));
                case "bulk-replay" -> System.out.println(cacheDatabase.admin().bulkReplay(parseIds(requiredArg(args, 1)), optionalArg(args, 2)));
                case "bulk-skip" -> System.out.println(cacheDatabase.admin().bulkSkip(parseIds(requiredArg(args, 1)), optionalArg(args, 2)));
                case "bulk-close" -> System.out.println(cacheDatabase.admin().bulkClose(parseIds(requiredArg(args, 1)), optionalArg(args, 2)));
                case "health" -> System.out.println(cacheDatabase.admin().health());
                case "metrics" -> System.out.println(cacheDatabase.admin().metrics());
                default -> throw new IllegalArgumentException("Unsupported command: " + command);
            }
        }
    }

    private static DeadLetterQuery buildDeadLetterQuery(String[] args, int startIndex) {
        DeadLetterQuery.Builder builder = DeadLetterQuery.builder();
        for (int index = startIndex; index < args.length; index++) {
            switch (args[index]) {
                case "--limit" -> builder.limit(Integer.parseInt(args[++index]));
                case "--cursor" -> builder.cursor(args[++index]);
                case "--entity" -> builder.entityName(args[++index]);
                case "--operation" -> builder.operationType(args[++index]);
                case "--id" -> builder.entityId(args[++index]);
                case "--error" -> builder.errorType(args[++index]);
                default -> {
                }
            }
        }
        return builder.build();
    }

    private static ReconciliationQuery buildReconciliationQuery(String[] args, int startIndex) {
        ReconciliationQuery.Builder builder = ReconciliationQuery.builder();
        for (int index = startIndex; index < args.length; index++) {
            switch (args[index]) {
                case "--limit" -> builder.limit(Integer.parseInt(args[++index]));
                case "--cursor" -> builder.cursor(args[++index]);
                case "--status" -> builder.status(args[++index]);
                case "--entity" -> builder.entityName(args[++index]);
                case "--operation" -> builder.operationType(args[++index]);
                case "--id" -> builder.entityId(args[++index]);
                default -> {
                }
            }
        }
        return builder.build();
    }

    private static String requiredArg(String[] args, int index) {
        if (args.length <= index) {
            throw new IllegalArgumentException("Missing required argument at index " + index);
        }
        return args[index];
    }

    private static String optionalArg(String[] args, int index) {
        return args.length > index ? args[index] : "";
    }

    private static java.util.List<String> parseIds(String rawIds) {
        return java.util.Arrays.stream(rawIds.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
    }

    private static AdminExportFormat parseFormat(String rawFormat) {
        return AdminExportFormat.valueOf(rawFormat.trim().toUpperCase(java.util.Locale.ROOT));
    }

    private static int parseLimit(String[] args, int startIndex) {
        for (int index = startIndex; index < args.length; index++) {
            if ("--limit".equals(args[index]) && index + 1 < args.length) {
                return Integer.parseInt(args[index + 1]);
            }
        }
        return 20;
    }
}
