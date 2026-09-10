package com.reactor.cachedb.oracle;

import java.sql.Connection;
import java.util.Locale;

public record OracleWriteBehindOptions(
        int queryTimeoutSeconds,
        int transactionIsolation,
        int duplicateRaceRetries,
        EmptyStringPolicy emptyStringPolicy
) {
    public OracleWriteBehindOptions {
        if (queryTimeoutSeconds <= 0 || queryTimeoutSeconds > 300) {
            throw new IllegalArgumentException("queryTimeoutSeconds must be between 1 and 300");
        }
        if (transactionIsolation != Connection.TRANSACTION_READ_COMMITTED
                && transactionIsolation != Connection.TRANSACTION_SERIALIZABLE) {
            throw new IllegalArgumentException("Oracle supports READ_COMMITTED or SERIALIZABLE isolation only");
        }
        if (duplicateRaceRetries < 0 || duplicateRaceRetries > 10) {
            throw new IllegalArgumentException("duplicateRaceRetries must be between 0 and 10");
        }
        emptyStringPolicy = emptyStringPolicy == null ? EmptyStringPolicy.REJECT : emptyStringPolicy;
    }

    public static OracleWriteBehindOptions defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public enum EmptyStringPolicy {
        REJECT,
        NORMALIZE_TO_NULL;

        public static EmptyStringPolicy parse(String value, EmptyStringPolicy fallback) {
            return value == null || value.isBlank()
                    ? fallback
                    : value.trim().toUpperCase(Locale.ROOT).equals("NORMALIZE_TO_NULL")
                    ? NORMALIZE_TO_NULL
                    : value.trim().toUpperCase(Locale.ROOT).equals("REJECT")
                    ? REJECT
                    : invalid(value);
        }

        private static EmptyStringPolicy invalid(String value) {
            throw new IllegalArgumentException("Unsupported Oracle empty string policy: " + value);
        }
    }

    public static final class Builder {
        private int queryTimeoutSeconds = 10;
        private int transactionIsolation = Connection.TRANSACTION_READ_COMMITTED;
        private int duplicateRaceRetries = 2;
        private EmptyStringPolicy emptyStringPolicy = EmptyStringPolicy.REJECT;

        public Builder queryTimeoutSeconds(int queryTimeoutSeconds) {
            this.queryTimeoutSeconds = queryTimeoutSeconds;
            return this;
        }

        public Builder transactionIsolation(int transactionIsolation) {
            this.transactionIsolation = transactionIsolation;
            return this;
        }

        public Builder duplicateRaceRetries(int duplicateRaceRetries) {
            this.duplicateRaceRetries = duplicateRaceRetries;
            return this;
        }

        public Builder emptyStringPolicy(EmptyStringPolicy emptyStringPolicy) {
            this.emptyStringPolicy = emptyStringPolicy;
            return this;
        }

        public OracleWriteBehindOptions build() {
            return new OracleWriteBehindOptions(
                    queryTimeoutSeconds,
                    transactionIsolation,
                    duplicateRaceRetries,
                    emptyStringPolicy
            );
        }
    }
}
