package com.reactor.cachedb.jdbc;

import java.sql.*;
import java.util.Objects;

import javax.sql.DataSource;

/**
 * Buffers actual sequence values, so other applications may use the same increment-one sequence.
 */
public final class JdbcPooledSequenceIdGenerator {
    private final DataSource source;
    private final String sequence;
    private final int allocationSize;
    private final int queryTimeoutSeconds;
    private long[] buffer = new long[0];
    private int position;

    public JdbcPooledSequenceIdGenerator(DataSource source, String sequence, int allocationSize) {
        this(source, sequence, allocationSize, 5);
    }

    public JdbcPooledSequenceIdGenerator(
            DataSource source, String sequence, int allocationSize, int timeoutSeconds) {
        this.source = Objects.requireNonNull(source, "source");
        if (sequence == null
                || !sequence.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*"))
            throw new IllegalArgumentException("Unsafe sequence identifier");
        if (allocationSize < 1
                || allocationSize > 1000
                || timeoutSeconds < 1
                || timeoutSeconds > 300)
            throw new IllegalArgumentException("Invalid sequence allocation size/timeout");
        this.sequence = sequence;
        this.allocationSize = allocationSize;
        this.queryTimeoutSeconds = timeoutSeconds;
    }

    public synchronized long nextId() {
        if (position == buffer.length) allocate();
        return buffer[position++];
    }

    public synchronized int remaining() {
        return buffer.length - position;
    }

    private void allocate() {
        try (var connection = source.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName();
            String sql = allocationSql(product, sequence, allocationSize);
            long[] next = new long[allocationSize];
            try (var statement = connection.prepareStatement(sql)) {
                statement.setQueryTimeout(queryTimeoutSeconds);
                statement.setFetchSize(allocationSize);
                try (var result = statement.executeQuery()) {
                    int count = 0;
                    while (result.next()) {
                        if (count >= allocationSize)
                            throw new IllegalStateException("Sequence returned too many IDs");
                        next[count++] = result.getBigDecimal(1).longValueExact();
                    }
                    if (count != allocationSize)
                        throw new IllegalStateException("Incomplete sequence allocation");
                }
            }
            buffer = next;
            position = 0;
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Sequence allocation failed (SQLState=" + failure.getSQLState() + ")", failure);
        }
    }

    static String allocationSql(String product, String sequence, int size) {
        return switch (product) {
            case "PostgreSQL" ->
                    "SELECT nextval('" + sequence + "') FROM generate_series(1," + size + ")";
            case "Microsoft SQL Server" ->
                    "SELECT NEXT VALUE FOR "
                            + sequence
                            + " FROM (VALUES "
                            + String.join(",", java.util.Collections.nCopies(size, "(0)"))
                            + ") AS ids(n)";
            case "Oracle" ->
                    "SELECT "
                            + sequence
                            + ".NEXTVAL FROM (SELECT LEVEL FROM dual CONNECT BY LEVEL <= "
                            + size
                            + ")";
            case "H2" -> "SELECT NEXT VALUE FOR " + sequence + " FROM SYSTEM_RANGE(1," + size + ")";
            default ->
                    throw new IllegalArgumentException("Unsupported sequence provider: " + product);
        };
    }
}
