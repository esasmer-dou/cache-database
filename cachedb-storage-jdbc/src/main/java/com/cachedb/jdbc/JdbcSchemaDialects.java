package com.reactor.cachedb.jdbc;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class JdbcSchemaDialects {
    private static final JdbcSchemaDialect H2 = standard("h2", Family.H2);

    private JdbcSchemaDialects() {
    }

    public static JdbcSchemaDialect resolve(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        try (Connection connection = dataSource.getConnection()) {
            return resolve(connection, contextClassLoader());
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not resolve CacheDB JDBC schema dialect: "
                    + exception.getMessage(), exception);
        }
    }

    public static JdbcSchemaDialect resolve(Connection connection) throws SQLException {
        return resolve(connection, contextClassLoader());
    }

    public static JdbcSchemaDialect resolve(Connection connection, ClassLoader classLoader) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        String productName = connection.getMetaData().getDatabaseProductName();
        ArrayList<JdbcStorageProvider> matches = new ArrayList<>();
        for (JdbcStorageProvider provider : JdbcStorageProviders.discover(classLoader)) {
            if (provider.queryDialect().supportsDatabaseProduct(productName)) {
                matches.add(provider);
            }
        }
        if (matches.size() == 1) {
            return matches.get(0).schemaDialect();
        }
        if (matches.size() > 1) {
            throw new IllegalStateException("Multiple CacheDB JDBC schema dialects match database product '"
                    + productName + "': " + matches.stream().map(JdbcStorageProvider::id).toList());
        }
        if (productName != null && productName.toLowerCase(Locale.ROOT).contains("h2")) {
            return H2;
        }
        throw new CacheDbDatabaseProductUnsupportedException(
                productName,
                JdbcStorageProviders.discover(classLoader).stream().map(JdbcStorageProvider::id).toList()
        );
    }

    public static JdbcSchemaDialect ansi(String name) {
        return standard(name, Family.ANSI);
    }

    public static JdbcSchemaDialect postgres() {
        return standard("postgres", Family.POSTGRES);
    }

    public static JdbcSchemaDialect mssql() {
        return standard("mssql", Family.MSSQL);
    }

    public static JdbcSchemaDialect oracle() {
        return standard("oracle", Family.ORACLE);
    }

    private static JdbcSchemaDialect standard(String name, Family family) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        return new StandardJdbcSchemaDialect(name.trim(), family);
    }

    private static ClassLoader contextClassLoader() {
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        return context == null ? JdbcSchemaDialects.class.getClassLoader() : context;
    }

    private enum Family {
        ANSI,
        H2,
        POSTGRES,
        MSSQL,
        ORACLE
    }

    private record StandardJdbcSchemaDialect(String name, Family family) implements JdbcSchemaDialect {
        @Override
        public String sqlType(String javaTypeName) {
            String type = javaTypeName == null ? "" : javaTypeName;
            return switch (family) {
                case MSSQL -> mssqlType(type);
                case ORACLE -> oracleType(type);
                case ANSI, H2, POSTGRES -> standardType(type);
            };
        }

        private String standardType(String type) {
            return switch (type) {
                case "java.lang.Long", "long" -> "BIGINT";
                case "java.lang.Integer", "int", "java.lang.Short", "short", "java.lang.Byte", "byte" -> "INTEGER";
                case "java.lang.Double", "double" -> "DOUBLE PRECISION";
                case "java.lang.Float", "float" -> "REAL";
                case "java.lang.Boolean", "boolean" -> "BOOLEAN";
                case "java.math.BigDecimal", "java.math.BigInteger" -> "DECIMAL(38, 10)";
                case "java.time.Instant", "java.time.LocalDateTime", "java.time.OffsetDateTime" -> "TIMESTAMP";
                case "java.time.LocalDate" -> "DATE";
                default -> "TEXT";
            };
        }

        private String mssqlType(String type) {
            return switch (type) {
                case "java.lang.Long", "long" -> "BIGINT";
                case "java.lang.Integer", "int", "java.lang.Short", "short", "java.lang.Byte", "byte" -> "INT";
                case "java.lang.Double", "double" -> "FLOAT";
                case "java.lang.Float", "float" -> "REAL";
                case "java.lang.Boolean", "boolean" -> "BIT";
                case "java.math.BigDecimal", "java.math.BigInteger" -> "DECIMAL(38, 10)";
                case "java.time.Instant", "java.time.LocalDateTime", "java.time.OffsetDateTime" -> "DATETIME2(6)";
                case "java.time.LocalDate" -> "DATE";
                default -> "NVARCHAR(MAX)";
            };
        }

        private String oracleType(String type) {
            return switch (type) {
                case "java.lang.Long", "long" -> "NUMBER(19)";
                case "java.math.BigInteger" -> "NUMBER(38, 0)";
                case "java.lang.Integer", "int" -> "NUMBER(10)";
                case "java.lang.Short", "short" -> "NUMBER(5)";
                case "java.lang.Byte", "byte" -> "NUMBER(3)";
                case "java.lang.Double", "double" -> "BINARY_DOUBLE";
                case "java.lang.Float", "float" -> "BINARY_FLOAT";
                case "java.lang.Boolean", "boolean" -> "NUMBER(1)";
                case "java.math.BigDecimal" -> "NUMBER(38, 10)";
                case "java.time.Instant", "java.time.OffsetDateTime" -> "TIMESTAMP(6) WITH TIME ZONE";
                case "java.time.LocalDateTime" -> "TIMESTAMP(6)";
                case "java.time.LocalDate" -> "DATE";
                default -> "VARCHAR2(4000 CHAR)";
            };
        }
    }
}
