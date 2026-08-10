package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.change.ExternalChangeFeedAdapter;
import com.reactor.cachedb.core.change.ExternalChangeSink;

import javax.sql.DataSource;
import java.sql.SQLException;

/**
 * @deprecated Use {@link com.reactor.cachedb.postgres.PostgresOutboxExternalChangeFeedAdapter}
 * from the explicit PostgreSQL provider.
 */
@Deprecated(forRemoval = false)
public final class PostgresOutboxExternalChangeFeedAdapter implements ExternalChangeFeedAdapter {
    private final com.reactor.cachedb.postgres.PostgresOutboxExternalChangeFeedAdapter delegate;

    private PostgresOutboxExternalChangeFeedAdapter(Builder builder) {
        this.delegate = builder.delegate.build();
    }

    public static Builder builder(DataSource dataSource) {
        return new Builder(dataSource);
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
        private final com.reactor.cachedb.postgres.PostgresOutboxExternalChangeFeedAdapter.Builder delegate;

        private Builder(DataSource dataSource) {
            this.delegate = com.reactor.cachedb.postgres.PostgresOutboxExternalChangeFeedAdapter.builder(dataSource);
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

        public Builder createCheckpointTable(boolean createCheckpointTable) {
            delegate.createCheckpointTable(createCheckpointTable);
            return this;
        }

        public PostgresOutboxExternalChangeFeedAdapter build() {
            return new PostgresOutboxExternalChangeFeedAdapter(this);
        }
    }
}
