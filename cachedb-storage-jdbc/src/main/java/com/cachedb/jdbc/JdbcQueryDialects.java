package com.reactor.cachedb.jdbc;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public final class JdbcQueryDialects {
    private static final JdbcQueryDialect H2 = standard(
            "h2",
            Set.of("h2"),
            32_767,
            true
    );

    private JdbcQueryDialects() {
    }

    public static JdbcQueryDialect resolve(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        try (Connection connection = dataSource.getConnection()) {
            return resolve(connection, contextClassLoader());
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not resolve CacheDB JDBC query dialect: " + exception.getMessage(), exception);
        }
    }

    public static JdbcQueryDialect resolve(Connection connection) throws SQLException {
        return resolve(connection, contextClassLoader());
    }

    public static JdbcQueryDialect resolve(Connection connection, ClassLoader classLoader) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        String productName = connection.getMetaData().getDatabaseProductName();
        ArrayList<JdbcQueryDialect> matches = new ArrayList<>();
        for (JdbcStorageProvider provider : JdbcStorageProviders.discover(classLoader)) {
            JdbcQueryDialect dialect = provider.queryDialect();
            if (dialect.supportsDatabaseProduct(productName)) {
                matches.add(dialect);
            }
        }
        if (matches.size() == 1) {
            return matches.get(0);
        }
        if (matches.size() > 1) {
            throw new IllegalStateException("Multiple CacheDB JDBC query dialects match database product '"
                    + productName + "': " + matches.stream().map(JdbcQueryDialect::name).toList());
        }
        if (H2.supportsDatabaseProduct(productName)) {
            return H2;
        }
        throw new CacheDbDatabaseProductUnsupportedException(
                productName,
                JdbcStorageProviders.discover(classLoader).stream().map(JdbcStorageProvider::id).toList()
        );
    }

    public static JdbcQueryDialect standard(
            String name,
            Set<String> databaseProductTokens,
            int maxInListExpressions,
            boolean limitKeyword
    ) {
        String normalizedName = requireText(name, "name");
        Set<String> normalizedTokens = databaseProductTokens == null
                ? Set.of()
                : databaseProductTokens.stream()
                .map(token -> requireText(token, "databaseProductToken").toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (normalizedTokens.isEmpty()) {
            throw new IllegalArgumentException("databaseProductTokens must not be empty");
        }
        if (maxInListExpressions <= 0) {
            throw new IllegalArgumentException("maxInListExpressions must be greater than zero");
        }
        return new StandardJdbcQueryDialect(
                normalizedName,
                normalizedTokens,
                maxInListExpressions,
                limitKeyword
        );
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static ClassLoader contextClassLoader() {
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        return context == null ? JdbcQueryDialects.class.getClassLoader() : context;
    }

    private record StandardJdbcQueryDialect(
            String name,
            Set<String> databaseProductTokens,
            int maxInListExpressions,
            boolean limitKeyword
    ) implements JdbcQueryDialect {
        @Override
        public boolean supportsDatabaseProduct(String databaseProductName) {
            String normalized = databaseProductName == null ? "" : databaseProductName.toLowerCase(Locale.ROOT);
            return databaseProductTokens.stream().anyMatch(normalized::contains);
        }

        @Override
        public String limitTail(int limit) {
            if (!limitKeyword) {
                return JdbcQueryDialect.super.limitTail(limit);
            }
            return "LIMIT " + Math.max(1, limit);
        }

        @Override
        public String parameterizedLimitTail() {
            return limitKeyword ? "LIMIT :page_size" : JdbcQueryDialect.super.parameterizedLimitTail();
        }
    }
}
