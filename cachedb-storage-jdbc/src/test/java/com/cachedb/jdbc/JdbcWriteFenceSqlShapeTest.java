package com.reactor.cachedb.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import com.reactor.cachedb.core.model.OperationType;
import com.reactor.cachedb.core.queue.QueuedWriteOperation;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class JdbcWriteFenceSqlShapeTest {
    @ParameterizedTest
    @EnumSource(JdbcWriteFence.Provider.class)
    void onlyMssqlHashComparisonsUseChar64WithoutChangingUnicodeBindings(
            JdbcWriteFence.Provider provider) throws Exception {
        var fixture = new RecordingJdbc();
        var fence = new JdbcWriteFence(fixture.source(), null, provider, 7);
        fence.initialize();
        var operations = List.of(operation("\u0130\u011f\u0131\u015f\u00f6\u00fc:one"), operation("two"));
        assertEquals(operations, fence.filter(fixture.connection(), operations));
        var seed = fixture.calls.stream().filter(Call::seed).findFirst().orElseThrow();
        var lock = fixture.calls.stream().filter(c -> c.sql.contains(" WHERE fence_key IN (")).findFirst().orElseThrow();
        var update = fixture.calls.stream().filter(c -> c.sql.startsWith("UPDATE ")).findFirst().orElseThrow();
        boolean mssql = provider == JdbcWriteFence.Provider.MSSQL;
        assertEquals(mssql ? 2 : 0, occurrences(seed.sql, "CAST(? AS CHAR(64))"));
        assertEquals(mssql ? 2 : 0, occurrences(lock.sql, "CAST(? AS CHAR(64))"));
        assertEquals(mssql ? 1 : 0, occurrences(update.sql, "CAST(? AS CHAR(64))"));
        assertTrue(lock.sql.endsWith(mssql ? "ORDER BY fence_key" : "ORDER BY fence_key FOR UPDATE"));
        if (mssql) {
            assertTrue(seed.sql.contains("WITH (HOLDLOCK)"));
            assertTrue(seed.sql.contains("(CAST(? AS CHAR(64)),?),(CAST(? AS CHAR(64)),?)"));
            assertTrue(lock.sql.contains("WITH (UPDLOCK,HOLDLOCK)"));
            assertTrue(update.sql.endsWith("WHERE fence_key=CAST(? AS CHAR(64))"));
        }
        assertTrue(seed.parameters.get(1).toString().matches("[0-9a-f]{64}"));
        assertTrue(seed.parameters.get(3).toString().matches("[0-9a-f]{64}"));
        assertTrue(seed.parameters.get(2).toString().contains(operations.get(0).id())
                || seed.parameters.get(4).toString().contains(operations.get(0).id()));
        assertEquals(2, update.batches.size());
        assertTrue(fixture.calls.stream().allMatch(c -> c.timeout == 7));
        assertTrue(fixture.calls.stream().noneMatch(c -> c.sql.contains("CAST(fence_key")));
        assertTrue(JdbcWriteFence.createTableSql(provider).contains("fence_key CHAR(64)"));
        if (mssql) assertTrue(JdbcWriteFence.createTableSql(provider).contains("identity_value NVARCHAR(MAX)"));
    }

    @Test
    void mssqlRetains500KeyChunksAndTheKeyIdentityParameterAlignment() throws Exception {
        var fixture = new RecordingJdbc();
        var fence = new JdbcWriteFence(fixture.source(), null, JdbcWriteFence.Provider.MSSQL, 3);
        fence.initialize();
        var operations = new ArrayList<QueuedWriteOperation>();
        for (int i = 0; i < 501; i++) operations.add(operation("id-" + i));
        assertEquals(501, fence.filter(fixture.connection(), operations).size());
        var seeds = fixture.calls.stream().filter(Call::seed).toList();
        assertEquals(2, seeds.size());
        assertEquals(1000, seeds.get(0).parameters.size());
        assertEquals(2, seeds.get(1).parameters.size());
        for (Call seed : seeds) {
            assertEquals(seed.parameters.size() / 2, occurrences(seed.sql, "CAST(? AS CHAR(64))"));
            for (int i = 1; i <= seed.parameters.size(); i += 2) {
                assertTrue(seed.parameters.get(i).toString().matches("[0-9a-f]{64}"));
                assertTrue(seed.parameters.get(i + 1).toString().contains("id-"));
            }
        }
        assertEquals(List.of(500, 1), fixture.updateBatchSizes);
    }

    private static QueuedWriteOperation operation(String id) {
        return new QueuedWriteOperation(OperationType.UPSERT, "Fixture", "business_rows", "test", "",
                "id", "entity_version", null, id, Map.of("id", id), 1, Instant.EPOCH);
    }

    private static int occurrences(String sql, String fragment) {
        return (sql.length() - sql.replace(fragment, "").length()) / fragment.length();
    }

    private static final class Call {
        final String sql;
        final Map<Integer, Object> parameters = new TreeMap<>();
        final List<Map<Integer, Object>> batches = new ArrayList<>();
        int flushed;
        int timeout;
        Call(String sql) { this.sql = sql; }
        boolean seed() { return sql.startsWith("MERGE ") || sql.startsWith("INSERT ") || sql.startsWith("BEGIN "); }
    }

    private static final class RecordingJdbc {
        final List<Call> calls = new ArrayList<>();
        final Map<String, String> identities = new HashMap<>();
        final List<Integer> updateBatchSizes = new ArrayList<>();

        DataSource source() {
            return proxy(DataSource.class, (p, m, a) -> {
                if (m.getName().equals("getConnection")) return connection();
                throw new UnsupportedOperationException(m.getName());
            });
        }

        Connection connection() {
            return proxy(Connection.class, (p, m, a) -> switch (m.getName()) {
                case "getAutoCommit" -> false;
                case "getCatalog" -> "fixture";
                case "getSchema" -> "dbo";
                case "getMetaData" -> proxy(DatabaseMetaData.class, (q, n, b) -> switch (n.getName()) {
                    case "storesUpperCaseIdentifiers" -> false;
                    case "getPrimaryKeys" -> rows(Collections.singletonList(new Object[]{"fence_key"}));
                    default -> throw new UnsupportedOperationException(n.getName());
                });
                case "prepareStatement" -> statement((String) a[0]);
                case "close" -> null;
                default -> throw new UnsupportedOperationException(m.getName());
            });
        }

        PreparedStatement statement(String sql) {
            Call call = new Call(sql);
            calls.add(call);
            return proxy(PreparedStatement.class, (p, m, a) -> {
                switch (m.getName()) {
                    case "setQueryTimeout": call.timeout = (int) a[0]; return null;
                    case "setString": case "setLong": case "setInt":
                        call.parameters.put((int) a[0], a[1]); return null;
                    case "addBatch": call.batches.add(new TreeMap<>(call.parameters)); return null;
                    case "clearBatch": return null;
                    case "executeBatch": {
                        int count = call.batches.size() - call.flushed;
                        call.flushed += count;
                        updateBatchSizes.add(count);
                        int[] result = new int[count]; Arrays.fill(result, 1); return result;
                    }
                    case "executeUpdate":
                        if (!call.seed()) throw new AssertionError(sql);
                        for (int i = 1; i <= call.parameters.size(); i += 2)
                            identities.put((String) call.parameters.get(i), (String) call.parameters.get(i + 1));
                        return call.parameters.size() / 2;
                    case "executeQuery":
                        if (sql.endsWith("WHERE 1=0")) return rows(List.of());
                        if (!sql.contains(" WHERE fence_key IN ("))
                            return rows(Collections.singletonList(new Object[]{"dbo", "business_rows"}));
                        var data = new ArrayList<Object[]>();
                        for (Object key : call.parameters.values())
                            data.add(new Object[]{key, identities.get(key), null, 0});
                        return rows(data);
                    case "close": return null;
                    default: throw new UnsupportedOperationException(m.getName());
                }
            });
        }
    }

    private static ResultSet rows(List<Object[]> data) {
        int[] index = {-1};
        boolean[] wasNull = {false};
        return proxy(ResultSet.class, (p, m, a) -> {
            switch (m.getName()) {
                case "next": return ++index[0] < data.size();
                case "getString": case "getLong": case "getInt": {
                    int column = a[0] instanceof Integer i ? i : 1;
                    Object value = data.get(index[0])[column - 1];
                    wasNull[0] = value == null;
                    if (m.getName().equals("getString")) return (String) value;
                    if (m.getName().equals("getInt")) return value == null ? 0 : ((Number) value).intValue();
                    return value == null ? 0L : ((Number) value).longValue();
                }
                case "wasNull": return wasNull[0];
                case "close": return null;
                default: throw new UnsupportedOperationException(m.getName());
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
}
