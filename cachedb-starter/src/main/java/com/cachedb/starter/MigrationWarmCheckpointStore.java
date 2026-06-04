package com.reactor.cachedb.starter;

import java.time.Instant;
import java.util.Optional;

interface MigrationWarmCheckpointStore {
    Optional<MigrationWarmRunner.Checkpoint> load(String jobId);

    void save(MigrationWarmRunner.Checkpoint checkpoint);

    void clear(String jobId);

    static MigrationWarmCheckpointStore noop() {
        return new MigrationWarmCheckpointStore() {
            @Override
            public Optional<MigrationWarmRunner.Checkpoint> load(String jobId) {
                return Optional.empty();
            }

            @Override
            public void save(MigrationWarmRunner.Checkpoint checkpoint) {
            }

            @Override
            public void clear(String jobId) {
            }
        };
    }

    static MigrationWarmRunner.Checkpoint checkpoint(
            String jobId,
            String phase,
            long childRowsRead,
            long childRowsHydrated,
            long rootRowsRead,
            long rootRowsHydrated
    ) {
        return new MigrationWarmRunner.Checkpoint(
                jobId,
                phase,
                childRowsRead,
                childRowsHydrated,
                rootRowsRead,
                rootRowsHydrated,
                Instant.now()
        );
    }
}
