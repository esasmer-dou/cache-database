package com.reactor.cachedb.oracle;

import java.sql.SQLException;
import java.sql.Statement;

/** Only for disposable test tables that the caller has already dropped. */
final class WriteFenceFixtureCleanup {
    static void afterDrop(Statement owner, String table) throws SQLException {
        if (!table.matches("cachedb_it_[a-z_]+"))
            throw new IllegalArgumentException("Not a test table");
        var connection = owner.getConnection();
        try (var probe = connection.createStatement();
                var result =
                        probe.executeQuery(
                                "SELECT COUNT(*) FROM user_tables WHERE"
                                    + " table_name='CACHEDB_WRITE_FENCE'")) {
            result.next();
            if (result.getInt(1) == 0) return;
        }
        String schema = connection.getSchema();
        String scope = schema.length() + ":" + schema + table.toUpperCase(java.util.Locale.ROOT);
        String prefix =
                (scope.length() + ":" + scope)
                        .replace("!", "!!")
                        .replace("_", "!_")
                        .replace("%", "!%");
        try (var statement =
                connection.prepareStatement(
                        "DELETE FROM cachedb_write_fence WHERE identity_value LIKE ? ESCAPE '!'")) {
            statement.setString(1, prefix + "%");
            statement.executeUpdate();
        }
    }
}
