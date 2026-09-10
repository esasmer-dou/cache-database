package com.reactor.cachedb.oracle;

import com.reactor.cachedb.core.change.ExternalChangeFeedAdapter;
import com.reactor.cachedb.core.change.ExternalChangeSink;
import com.reactor.cachedb.jdbc.JdbcOutboxExternalChangeFeedAdapter;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

public final class OracleOutboxExternalChangeFeedAdapter implements ExternalChangeFeedAdapter {
    private final JdbcOutboxExternalChangeFeedAdapter delegate;

    private OracleOutboxExternalChangeFeedAdapter(Builder builder) {
        this.delegate = builder.delegate.build();
    }

    public static Builder builder(DataSource dataSource) {
        return new Builder(dataSource, 5);
    }

    public static Builder builder(DataSource dataSource, int lockWaitSeconds) {
        return new Builder(dataSource, lockWaitSeconds);
    }

    @Override
    public void start(ExternalChangeSink sink) {
        delegate.start(sink);
    }

    public int pollOnce(ExternalChangeSink sink) {
        return delegate.pollOnce(sink);
    }

    public void initialize() throws SQLException {
        delegate.initialize();
    }

    public RuntimeException lastFailure() {
        return delegate.lastFailure();
    }

    @Override
    public void close() {
        delegate.close();
    }

    public static final class Builder {
        private final JdbcOutboxExternalChangeFeedAdapter.Builder delegate;

        private Builder(DataSource dataSource, int lockWaitSeconds) {
            this.delegate = JdbcOutboxExternalChangeFeedAdapter.builder(
                            dataSource,
                            new OracleOutboxDialect(lockWaitSeconds)
                    )
                    .transactionIsolation(Connection.TRANSACTION_READ_COMMITTED)
                    .createCheckpointTable(false);
        }

        public Builder adapterName(String adapterName) {
            delegate.adapterName(adapterName);
            return this;
        }

        public Builder outboxTable(String outboxTable) {
            delegate.outboxTable(outboxTable);
            return this;
        }

        public Builder checkpointTable(String checkpointTable) {
            delegate.checkpointTable(checkpointTable);
            return this;
        }

        public Builder idColumn(String idColumn) {
            delegate.idColumn(idColumn);
            return this;
        }

        public Builder entityColumn(String entityColumn) {
            delegate.entityColumn(entityColumn);
            return this;
        }

        public Builder entityIdColumn(String entityIdColumn) {
            delegate.entityIdColumn(entityIdColumn);
            return this;
        }

        public Builder typeColumn(String typeColumn) {
            delegate.typeColumn(typeColumn);
            return this;
        }

        public Builder payloadColumn(String payloadColumn) {
            delegate.payloadColumn(payloadColumn);
            return this;
        }

        public Builder versionColumn(String versionColumn) {
            delegate.versionColumn(versionColumn);
            return this;
        }

        public Builder occurredAtColumn(String occurredAtColumn) {
            delegate.occurredAtColumn(occurredAtColumn);
            return this;
        }

        public Builder sourceColumn(String sourceColumn) {
            delegate.sourceColumn(sourceColumn);
            return this;
        }

        public Builder batchSize(int batchSize) {
            delegate.batchSize(batchSize);
            return this;
        }

        public Builder pollIntervalMillis(long pollIntervalMillis) {
            delegate.pollIntervalMillis(pollIntervalMillis);
            return this;
        }

        /** Development-only convenience. Production should provision DDL before startup. */
        public Builder createCheckpointTable(boolean createCheckpointTable) {
            delegate.createCheckpointTable(createCheckpointTable);
            return this;
        }

        public OracleOutboxExternalChangeFeedAdapter build() {
            return new OracleOutboxExternalChangeFeedAdapter(this);
        }
    }
}
