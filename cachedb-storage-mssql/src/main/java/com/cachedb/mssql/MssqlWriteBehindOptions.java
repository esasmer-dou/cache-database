package com.reactor.cachedb.mssql;

import java.sql.Connection;

public record MssqlWriteBehindOptions(
        int lockTimeoutMillis,
        int queryTimeoutSeconds,
        int transactionIsolation,
        boolean restoreLockTimeoutAfterTransaction
) {
    public static final int LOCK_TIMEOUT_WAIT_FOREVER = -1;

    public MssqlWriteBehindOptions {
        if (lockTimeoutMillis < LOCK_TIMEOUT_WAIT_FOREVER) {
            throw new IllegalArgumentException("lockTimeoutMillis must be -1 or greater");
        }
        if (queryTimeoutSeconds < 0) {
            throw new IllegalArgumentException("queryTimeoutSeconds must be 0 or greater");
        }
        if (!supportedIsolation(transactionIsolation)) {
            throw new IllegalArgumentException("Unsupported MSSQL transaction isolation: " + transactionIsolation);
        }
    }

    public static MssqlWriteBehindOptions defaults() {
        return sharedPoolDefaults();
    }

    public static MssqlWriteBehindOptions sharedPoolDefaults() {
        return builder()
                .lockTimeoutMillis(5_000)
                .queryTimeoutSeconds(10)
                .transactionIsolation(Connection.TRANSACTION_SERIALIZABLE)
                .restoreLockTimeoutAfterTransaction(true)
                .build();
    }

    public static MssqlWriteBehindOptions dedicatedWorkerPoolDefaults() {
        return builder()
                .lockTimeoutMillis(5_000)
                .queryTimeoutSeconds(10)
                .transactionIsolation(Connection.TRANSACTION_SERIALIZABLE)
                .restoreLockTimeoutAfterTransaction(false)
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return builder()
                .lockTimeoutMillis(lockTimeoutMillis)
                .queryTimeoutSeconds(queryTimeoutSeconds)
                .transactionIsolation(transactionIsolation)
                .restoreLockTimeoutAfterTransaction(restoreLockTimeoutAfterTransaction);
    }

    boolean lockTimeoutEnabled() {
        return lockTimeoutMillis >= 0;
    }

    private static boolean supportedIsolation(int transactionIsolation) {
        return transactionIsolation == Connection.TRANSACTION_READ_COMMITTED
                || transactionIsolation == Connection.TRANSACTION_REPEATABLE_READ
                || transactionIsolation == Connection.TRANSACTION_SERIALIZABLE;
    }

    public static final class Builder {
        private int lockTimeoutMillis = 5_000;
        private int queryTimeoutSeconds = 10;
        private int transactionIsolation = Connection.TRANSACTION_SERIALIZABLE;
        private boolean restoreLockTimeoutAfterTransaction = true;

        public Builder lockTimeoutMillis(int lockTimeoutMillis) {
            this.lockTimeoutMillis = lockTimeoutMillis;
            return this;
        }

        public Builder queryTimeoutSeconds(int queryTimeoutSeconds) {
            this.queryTimeoutSeconds = queryTimeoutSeconds;
            return this;
        }

        public Builder transactionIsolation(int transactionIsolation) {
            this.transactionIsolation = transactionIsolation;
            return this;
        }

        public Builder restoreLockTimeoutAfterTransaction(boolean restoreLockTimeoutAfterTransaction) {
            this.restoreLockTimeoutAfterTransaction = restoreLockTimeoutAfterTransaction;
            return this;
        }

        public MssqlWriteBehindOptions build() {
            return new MssqlWriteBehindOptions(
                    lockTimeoutMillis,
                    queryTimeoutSeconds,
                    transactionIsolation,
                    restoreLockTimeoutAfterTransaction
            );
        }
    }
}
