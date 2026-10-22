package com.example.cachedb.sample.catalog;

import static org.junit.jupiter.api.Assertions.*;

import com.example.cachedb.sample.domain.ReportJobEntity;
import com.example.cachedb.sample.domain.ReportJobEntityCacheBinding;
import com.reactor.cachedb.core.model.OperationType;
import com.reactor.cachedb.core.queue.QueuedWriteOperation;
import com.reactor.cachedb.jdbc.JdbcStorageProviders;
import com.reactor.cachedb.starter.CacheDatabase;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

/** Replay injection is deliberately test-only and uses disposable provider fixtures. */
public final class WriteFenceScenarios {
    private WriteFenceScenarios() {}

    public static void verify(String provider, DataSource source, CacheDatabase database)
            throws Exception {
        var factory = JdbcStorageProviders.require(provider).writeBehindFlusherFactory(Map.of());
        var writer =
                factory.create(
                        source, database.entityRegistry(), database.config().writeBehind(), null);
        var sql = new JdbcTemplate(source);
        writer.flush(operation(8_100_001, OperationType.UPSERT, 1));
        writer.flush(operation(8_100_001, OperationType.DELETE, 3));
        writer =
                factory.create(
                        source, database.entityRegistry(), database.config().writeBehind(), null);
        writer.flush(operation(8_100_001, OperationType.UPSERT, 2));
        assertEquals(0, count(sql, 8_100_001));
        writer.flush(operation(8_100_001, OperationType.UPSERT, 4));
        assertEquals(1, count(sql, 8_100_001));

        var goodDelete = operation(8_100_001, OperationType.DELETE, 9);
        var invalid = operation(8_100_002, OperationType.UPSERT, 10);
        var badColumns = new LinkedHashMap<>(invalid.columns());
        badColumns.put("report_type", null);
        var bad =
                new QueuedWriteOperation(
                        invalid.type(),
                        invalid.entityName(),
                        invalid.tableName(),
                        invalid.redisNamespace(),
                        "",
                        invalid.idColumn(),
                        invalid.versionColumn(),
                        null,
                        invalid.id(),
                        badColumns,
                        invalid.version(),
                        invalid.createdAt());
        var atomicWriter = writer;
        assertThrows(
                java.sql.SQLException.class,
                () -> atomicWriter.flushAtomicBatch(List.of(goodDelete, bad)));
        assertEquals(1, count(sql, 8_100_001));
        writer.flush(operation(8_100_001, OperationType.UPSERT, 5));
        assertEquals(
                5L,
                sql.queryForObject(
                        "SELECT entity_version FROM sample_report_jobs WHERE report_job_id = ?",
                        Long.class,
                        8_100_001));
    }

    private static int count(JdbcTemplate sql, long id) {
        return sql.queryForObject(
                "SELECT COUNT(*) FROM sample_report_jobs WHERE report_job_id = ?",
                Integer.class,
                id);
    }

    private static QueuedWriteOperation operation(long id, OperationType type, long version) {
        var job = new ReportJobEntity();
        job.reportJobId = id;
        job.reportType = "FENCE_TEST";
        job.status = "COMPLETED";
        job.requestedBy = "fixture";
        job.createdAt = 1_700_000_000L;
        job.updatedAt = job.createdAt;
        job.rowCount = 1;
        var columns = new LinkedHashMap<String, String>();
        ReportJobEntityCacheBinding.CODEC
                .toColumns(job)
                .forEach((key, value) -> columns.put(key, value == null ? null : value.toString()));
        columns.put("entity_version", Long.toString(version));
        return new QueuedWriteOperation(
                type,
                "ReportJobEntity",
                "sample_report_jobs",
                "sample-report-jobs",
                "",
                "report_job_id",
                "entity_version",
                null,
                Long.toString(id),
                columns,
                version,
                Instant.EPOCH);
    }
}
