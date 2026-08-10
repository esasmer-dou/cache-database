package com.reactor.cachedb.jdbc;

import com.reactor.cachedb.core.model.EntityCodec;
import com.reactor.cachedb.core.repository.SourceSqlQuery;
import com.reactor.cachedb.core.repository.SourceSqlRepository;
import com.reactor.cachedb.core.repository.SourceSqlValidator;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

public final class JdbcSourceSqlRepository<T> implements SourceSqlRepository<T> {
    private final DataSource dataSource;
    private final EntityCodec<T> codec;

    public JdbcSourceSqlRepository(DataSource dataSource, EntityCodec<T> codec) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    @Override
    public List<T> query(SourceSqlQuery query) {
        Objects.requireNonNull(query, "query");
        String sql = validateReadOnlySql(query.sql());
        int probeLimit = Math.min(10_001, query.maxRows() + 1);
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setQueryTimeout(query.queryTimeoutSeconds());
                statement.setFetchSize(Math.min(query.maxRows(), 1_000));
                statement.setMaxRows(probeLimit);
                for (int index = 0; index < query.parameters().size(); index++) {
                    statement.setObject(index + 1, query.parameters().get(index));
                }
                try (ResultSet resultSet = statement.executeQuery()) {
                    ArrayList<T> rows = new ArrayList<>(Math.min(query.maxRows(), 128));
                    while (resultSet.next()) {
                        if (rows.size() >= query.maxRows()) {
                            throw new IllegalStateException("Source SQL exceeded maxRows=" + query.maxRows());
                        }
                        rows.add(codec.fromColumns(readColumns(resultSet)));
                    }
                    return List.copyOf(rows);
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("CacheDB source SQL failed: " + exception.getMessage(), exception);
        }
    }

    static String validateReadOnlySql(String sql) {
        return SourceSqlValidator.requireReadOnly(sql);
    }

    private LinkedHashMap<String, Object> readColumns(ResultSet resultSet) throws SQLException {
        ResultSetMetaData metadata = resultSet.getMetaData();
        LinkedHashMap<String, Object> columns = new LinkedHashMap<>(metadata.getColumnCount());
        for (int index = 1; index <= metadata.getColumnCount(); index++) {
            String label = metadata.getColumnLabel(index);
            columns.put(label == null || label.isBlank() ? metadata.getColumnName(index) : label,
                    resultSet.getObject(index));
        }
        return columns;
    }
}
