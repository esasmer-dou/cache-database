package com.reactor.cachedb.spring.boot.test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/** Deterministic connection-acquisition failures for write-behind retry tests. */
public final class FaultInjectingDataSource implements DataSource {
    private final DataSource delegate;
    private final AtomicInteger remainingFailures = new AtomicInteger();

    public FaultInjectingDataSource(DataSource delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    public FaultInjectingDataSource failNextConnections(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("count must not be negative");
        }
        remainingFailures.set(count);
        return this;
    }

    public int remainingFailures() {
        return remainingFailures.get();
    }

    @Override
    public Connection getConnection() throws SQLException {
        failIfArmed();
        return delegate.getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        failIfArmed();
        return delegate.getConnection(username, password);
    }

    private void failIfArmed() throws SQLTransientConnectionException {
        int current;
        do {
            current = remainingFailures.get();
            if (current <= 0) {
                return;
            }
        } while (!remainingFailures.compareAndSet(current, current - 1));
        throw new SQLTransientConnectionException("CacheDB test fault: connection acquisition rejected");
    }

    @Override public PrintWriter getLogWriter() throws SQLException { return delegate.getLogWriter(); }
    @Override public void setLogWriter(PrintWriter out) throws SQLException { delegate.setLogWriter(out); }
    @Override public void setLoginTimeout(int seconds) throws SQLException { delegate.setLoginTimeout(seconds); }
    @Override public int getLoginTimeout() throws SQLException { return delegate.getLoginTimeout(); }
    @Override public Logger getParentLogger() { return Logger.getLogger("com.reactor.cachedb.test"); }
    @Override public <T> T unwrap(Class<T> iface) throws SQLException { return delegate.unwrap(iface); }
    @Override public boolean isWrapperFor(Class<?> iface) throws SQLException { return delegate.isWrapperFor(iface); }
}
