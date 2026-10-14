package com.reactor.cachedb.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;

class JdbcPooledSequenceIdGeneratorTest {
    @Test
    void sharesActualValuesWithLegacyWritersAndConcurrentPods() throws Exception {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:seq_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        try (var c = source.getConnection();
                var s = c.createStatement()) {
            s.execute("CREATE SEQUENCE shared_ids START WITH 1 INCREMENT BY 1");
        }
        var first = new JdbcPooledSequenceIdGenerator(source, "shared_ids", 16);
        var second = new JdbcPooledSequenceIdGenerator(source, "shared_ids", 7);
        var ids = ConcurrentHashMap.<Long>newKeySet();
        var executor = Executors.newFixedThreadPool(4);
        try {
            var futures = new ArrayList<Future<?>>();
            for (int pod = 0; pod < 4; pod++) {
                final int p = pod;
                futures.add(
                        executor.submit(
                                () -> {
                                    for (int i = 0; i < 100; i++)
                                        assertTrue(ids.add((p % 2 == 0 ? first : second).nextId()));
                                }));
            }
            for (var future : futures) future.get(10, TimeUnit.SECONDS);
            try (var c = source.getConnection();
                    var s = c.createStatement();
                    var r = s.executeQuery("SELECT NEXT VALUE FOR shared_ids")) {
                assertTrue(r.next());
                assertTrue(ids.add(r.getLong(1)));
            }
            assertEquals(401, ids.size());
        } finally {
            executor.shutdownNow();
            try (var c = source.getConnection();
                    var s = c.createStatement()) {
                s.execute("SHUTDOWN");
            }
        }
    }

    @Test
    void identifiersAndAllocationAreValidated() {
        var source = new JdbcDataSource();
        assertThrows(
                IllegalArgumentException.class,
                () -> new JdbcPooledSequenceIdGenerator(source, "seq;drop", 5));
        assertThrows(
                IllegalArgumentException.class,
                () -> new JdbcPooledSequenceIdGenerator(source, "seq", 0));
    }
}
