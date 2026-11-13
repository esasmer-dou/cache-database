package com.reactor.cachedb.spring.boot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.sqlserver.jdbc.SQLServerDataSource;
import com.reactor.cachedb.core.config.ResourceLimits;
import com.reactor.cachedb.core.model.OperationType;
import com.reactor.cachedb.core.queue.QueuedWriteOperation;
import com.reactor.cachedb.core.registry.DefaultEntityRegistry;
import com.reactor.cachedb.jdbc.JdbcWriteFence;
import com.reactor.cachedb.mssql.MssqlWriteBehindFlusher;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.StringReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/** Creates and removes only uniquely named, disposable SQL Server databases. */
@EnabledIfSystemProperty(named = "cachedb.snapshot.providers", matches = "true")
class MssqlWriteFenceSargabilityIntegrationTest {
    private static final int FILLER_ROWS = 40000;
    private static final String SQL_COLLATION = "SQL_Latin1_General_CP1_CI_AS";
    private static final String WINDOWS_COLLATION = "Latin1_General_100_CI_AS_SC";

    @ParameterizedTest
    @ValueSource(strings = {SQL_COLLATION, WINDOWS_COLLATION})
    void actualFenceSqlHasSelectiveSeeksWithoutColumnConversions(String collation) throws Exception {
        try (Fixture fixture = new Fixture(collation)) {
            var capture = new Capture();
            var fence = new JdbcWriteFence(capture.wrap(fixture.source), null, JdbcWriteFence.Provider.MSSQL, 10);
            fence.initialize();
            var initial = List.of(fixture.operation("first", OperationType.UPSERT, 1),
                    fixture.operation("second", OperationType.UPSERT, 1));
            try (var connection = capture.wrapConnection(fixture.source.getConnection())) {
                connection.setAutoCommit(false);
                assertThat(fence.filter(connection, initial)).hasSize(2);
                connection.commit();
                capture.executions.clear();
                assertThat(fence.filter(connection, List.of(
                        fixture.operation("first", OperationType.UPSERT, 2),
                        fixture.operation("second", OperationType.UPSERT, 2)))).hasSize(2);
                connection.rollback();
            }
            var shapes = new LinkedHashMap<String, Execution>();
            for (Execution execution : capture.executions) {
                String sql = execution.sql;
                if (sql.startsWith("MERGE cachedb_write_fence ")) shapes.putIfAbsent("seed", execution);
                if (sql.contains(" WHERE fence_key IN (")) shapes.putIfAbsent("lock", execution);
                if (sql.startsWith("UPDATE cachedb_write_fence ")) shapes.putIfAbsent("update", execution);
            }
            assertThat(shapes.keySet()).containsExactly("seed", "lock", "update");
            for (var entry : shapes.entrySet()) {
                Execution candidate = entry.getValue();
                Plan fixed = fixture.actualPlan(candidate, entry.getKey() + "-fixed");
                assertThat(fixed.columnConversions).as("%s %s column conversion", collation, entry.getKey()).isZero();
                assertThat(fixed.seekPredicates).as("%s %s seek predicates", collation, entry.getKey()).isPositive();
                assertThat(fixed.operators).as("%s %s", collation, entry.getKey()).doesNotContain("Clustered Index Scan");
                assertThat(fixed.rowsRead).isLessThan(20);
                Plan original = fixture.actualPlan(new Execution(
                        candidate.sql.replace("CAST(? AS CHAR(64))", "?"), candidate.parameters), entry.getKey() + "-original");
                assertThat(original.columnConversions).as("unpatched Unicode binding control").isPositive();
                // Windows collations may derive a seek through a conversion; do not outlaw it.
                if (SQL_COLLATION.equals(collation)) {
                    assertThat(original.operators).contains("Clustered Index Scan");
                    assertThat(original.rowsRead).isGreaterThanOrEqualTo(FILLER_ROWS);
                }
                System.out.printf(Locale.ROOT,
                        "FENCE_PLAN collation=%s shape=%s fixed=%s rowsRead=%.0f convertedColumn=%d original=%s rowsRead=%.0f convertedColumn=%d%n",
                        collation, entry.getKey(), fixed.operators, fixed.rowsRead, fixed.columnConversions,
                        original.operators, original.rowsRead, original.columnConversions);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {SQL_COLLATION, WINDOWS_COLLATION})
    void unicodeIdentityDurableDeleteStaleReplayAndRollbackRemainIntact(String collation) throws Exception {
        try (Fixture fixture = new Fixture(collation)) {
            String id = "\u0130\u011f\u0131\u015f\u00f6\u00fc\ud83d\ude80";
            String value = "\u0130\u011f\u0131\u015f\u00f6\u00fc\ud83d\ude80".repeat(600);
            var flusher = fixture.flusher();
            flusher.flush(fixture.operation(id, OperationType.UPSERT, 1, value));
            try (var connection = fixture.source.getConnection();
                    var statement = connection.prepareStatement("SELECT value_text FROM business_rows WHERE id=?")) {
                statement.setString(1, id);
                try (var result = statement.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isEqualTo(value);
                }
            }
            var state = fixture.state(id);
            assertThat(state.identity).endsWith(id);
            assertThat(state.key).matches("[0-9a-f]{64}");
            flusher.flush(fixture.operation(id, OperationType.DELETE, 3));
            var restarted = fixture.flusher();
            restarted.flush(fixture.operation(id, OperationType.UPSERT, 2));
            restarted.flush(fixture.operation(id, OperationType.UPSERT, 3));
            assertThat(fixture.businessRows()).isZero();
            assertThat(fixture.state(id)).isEqualTo(new State(state.key, state.identity, 3, 1));

            assertThatThrownBy(() -> restarted.flushAtomicBatch(List.of(
                    fixture.operation(id, OperationType.UPSERT, 4),
                    fixture.operation("bad", OperationType.UPSERT, 1, "reject"))))
                    .isInstanceOf(SQLException.class);
            assertThat(fixture.state(id).version).isEqualTo(3);
            assertThat(fixture.businessRows()).isZero();
            restarted.flush(fixture.operation(id, OperationType.UPSERT, 4, value));
            assertThat(fixture.state(id).version).isEqualTo(4);
            assertThat(fixture.businessRows()).isEqualTo(1);

            // Fence identities are NVARCHAR(MAX), independent of the business table's ID width.
            String longId = "\u0130\u011f\u0131\u015f\u00f6\u00fc\ud83d\ude80".repeat(700);
            var fence = new JdbcWriteFence(fixture.source, null, JdbcWriteFence.Provider.MSSQL, 10);
            fence.initialize();
            try (var connection = fixture.source.getConnection()) {
                connection.setAutoCommit(false);
                assertThat(fence.filter(connection, List.of(fixture.operation(longId, OperationType.DELETE, 7)))).hasSize(1);
                connection.commit();
            }
            assertThat(fixture.state(longId).identity).endsWith(longId).hasSizeGreaterThan(4000);
            try (var connection = fixture.source.getConnection(); var statement = connection.createStatement();
                    var result = statement.executeQuery("SELECT t.name,c.max_length FROM sys.columns c JOIN sys.types t ON t.user_type_id=c.user_type_id WHERE c.object_id=OBJECT_ID('cachedb_write_fence') AND c.name='identity_value'")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo("nvarchar");
                assertThat(result.getInt(2)).isEqualTo(-1);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {SQL_COLLATION, WINDOWS_COLLATION})
    void parallelOppositeOrderBatchesPreserveCommittedDeletesAcross500KeyBoundary(String collation) throws Exception {
        try (Fixture fixture = new Fixture(collation)) {
            var initial = new ArrayList<QueuedWriteOperation>();
            var deletes = new ArrayList<QueuedWriteOperation>();
            var stale = new ArrayList<QueuedWriteOperation>();
            for (int i = 0; i < 501; i++) {
                String id = "batch-" + i;
                initial.add(fixture.operation(id, OperationType.UPSERT, 1));
                deletes.add(fixture.operation(id, OperationType.DELETE, 3));
                stale.add(fixture.operation(id, OperationType.UPSERT, 2));
            }
            fixture.flusher().flushAtomicBatch(initial);
            Collections.reverse(stale);
            var start = new CountDownLatch(1);
            ExecutorService workers = Executors.newFixedThreadPool(2);
            try {
                var left = workers.submit(() -> { start.await(); fixture.flusher().flushAtomicBatch(deletes); return null; });
                var right = workers.submit(() -> { start.await(); fixture.flusher().flushAtomicBatch(stale); return null; });
                start.countDown();
                left.get(45, TimeUnit.SECONDS);
                right.get(45, TimeUnit.SECONDS);
            } finally {
                workers.shutdownNow();
                assertThat(workers.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
            }
            fixture.flusher().flushAtomicBatch(stale);
            assertThat(fixture.businessRows()).isZero();
            try (var connection = fixture.source.getConnection(); var statement = connection.createStatement();
                    var result = statement.executeQuery("SELECT COUNT(*) FROM cachedb_write_fence WHERE entity_version=3 AND deleted=1")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isEqualTo(501);
            }
        }
    }

    private record State(String key, String identity, long version, int deleted) {}
    private record Execution(String sql, Map<Integer, Object> parameters) {}
    private record Plan(List<String> operators, int columnConversions, double rowsRead, int seekPredicates) {}

    private static final class Fixture implements AutoCloseable {
        final String database;
        final BoundedMssqlTestDatabase ownedDatabase;
        final SQLServerDataSource admin = new SQLServerDataSource();
        final HikariDataSource source;
        final DefaultEntityRegistry registry = new DefaultEntityRegistry(ResourceLimits.defaults());

        Fixture(String collation) throws Exception {
            assertThat(List.of(SQL_COLLATION, WINDOWS_COLLATION)).contains(collation);
            String url = TestFixtureSettings.value("cachedb.snapshot.mssql.url", null);
            if (url == null) throw new IllegalStateException("Explicit disposable SQL Server URL is required");
            admin.setURL(url);
            admin.setDatabaseName("master");
            admin.setUser(TestFixtureSettings.value("cachedb.snapshot.mssql.user", "sa"));
            admin.setPassword(TestFixtureSettings.value("cachedb.snapshot.mssql.password", null));
            admin.setLoginTimeout(5);
            assertThat(admin.getSendStringParametersAsUnicode()).isTrue();
            ownedDatabase = BoundedMssqlTestDatabase.create(
                    admin, BoundedMssqlTestDatabase.Scope.WRITE_FENCE, collation);
            database = ownedDatabase.name();
            HikariDataSource opened = null;
            try {
                var jdbc = new SQLServerDataSource();
                jdbc.setURL(url);
                jdbc.setDatabaseName(database);
                jdbc.setUser(admin.getUser());
                jdbc.setPassword(TestFixtureSettings.value("cachedb.snapshot.mssql.password", null));
                jdbc.setLoginTimeout(5);
                assertThat(jdbc.getSendStringParametersAsUnicode()).isTrue();
                var config = new HikariConfig();
                config.setDataSource(jdbc);
                config.setMaximumPoolSize(4);
                config.setMinimumIdle(0);
                config.setConnectionTimeout(5000);
                config.setConnectionInitSql("SET LOCK_TIMEOUT 5000");
                opened = new HikariDataSource(config);
                try (var connection = opened.getConnection(); var statement = connection.createStatement()) {
                    assertThat(connection.getCatalog()).isEqualTo(database);
                    statement.setQueryTimeout(30);
                    statement.execute(JdbcWriteFence.createTableSql(JdbcWriteFence.Provider.MSSQL));
                    statement.execute("CREATE TABLE business_rows(id NVARCHAR(200) PRIMARY KEY,value_text NVARCHAR(MAX) CHECK(value_text<>N'reject'),entity_version BIGINT)");
                    statement.executeUpdate(";WITH n AS (SELECT TOP (" + FILLER_ROWS + ") ROW_NUMBER() OVER(ORDER BY (SELECT NULL)) i FROM sys.all_objects a CROSS JOIN sys.all_objects b) INSERT cachedb_write_fence(fence_key,identity_value,entity_version,deleted) SELECT CONVERT(CHAR(64),HASHBYTES('SHA2_256',CONVERT(VARCHAR(20),i)),2),N'filler:'+CONVERT(NVARCHAR(20),i),0,0 FROM n");
                    statement.execute("UPDATE STATISTICS cachedb_write_fence WITH FULLSCAN");
                }
                source = opened;
            } catch (Throwable failure) {
                BoundedMssqlTestDatabase.closeAfterFailure(failure, opened, ownedDatabase);
                throw failure;
            }
        }

        MssqlWriteBehindFlusher flusher() { return new MssqlWriteBehindFlusher(source, registry); }

        QueuedWriteOperation operation(String id, OperationType type, long version) {
            return operation(id, type, version, "v" + version);
        }

        QueuedWriteOperation operation(String id, OperationType type, long version, String value) {
            var columns = new LinkedHashMap<String, String>();
            columns.put("id", id); columns.put("value_text", value); columns.put("entity_version", Long.toString(version));
            return new QueuedWriteOperation(type, "Fixture", "business_rows", database, "", "id", "entity_version",
                    null, id, columns, version, Instant.EPOCH);
        }

        State state(String id) throws Exception {
            try (var connection = source.getConnection(); var statement = connection.prepareStatement(
                    "SELECT fence_key,identity_value,entity_version,deleted FROM cachedb_write_fence WHERE identity_value=?")) {
                String scope = "3:dbobusiness_rows";
                statement.setString(1, scope.length() + ":" + scope + id.length() + ":" + id);
                try (var result = statement.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    var state = new State(result.getString(1), result.getString(2), result.getLong(3), result.getInt(4));
                    assertThat(result.next()).isFalse();
                    return state;
                }
            }
        }

        long businessRows() throws Exception {
            try (var connection = source.getConnection(); var statement = connection.createStatement();
                    var result = statement.executeQuery("SELECT COUNT(*) FROM business_rows")) {
                assertThat(result.next()).isTrue(); return result.getLong(1);
            }
        }

        Plan actualPlan(Execution execution, String label) throws Exception {
            var documents = new ArrayList<Document>();
            try (var connection = source.getConnection(); var settings = connection.createStatement()) {
                connection.setAutoCommit(false);
                settings.execute("SET STATISTICS XML ON");
                try (var statement = connection.prepareStatement(execution.sql)) {
                    statement.setQueryTimeout(15);
                    for (var parameter : execution.parameters.entrySet()) {
                        Object value = parameter.getValue();
                        if (value instanceof String string) statement.setString(parameter.getKey(), string);
                        else if (value instanceof Long number) statement.setLong(parameter.getKey(), number);
                        else if (value instanceof Integer number) statement.setInt(parameter.getKey(), number);
                        else throw new AssertionError("Unexpected parameter type");
                    }
                    boolean rows = statement.execute();
                    for (;;) {
                        if (rows) {
                            try (var result = statement.getResultSet()) {
                                if (result.getMetaData().getColumnCount() == 1) {
                                    while (result.next()) {
                                        String xml = result.getString(1);
                                        if (xml != null && xml.contains("<ShowPlanXML")) {
                                            var directory = java.nio.file.Path.of("target", "fence-plans", database);
                                            java.nio.file.Files.createDirectories(directory);
                                            java.nio.file.Files.writeString(directory.resolve(label + "-" + documents.size() + ".xml"), xml);
                                            documents.add(parse(xml));
                                        }
                                    }
                                }
                            }
                        } else if (statement.getUpdateCount() == -1) break;
                        rows = statement.getMoreResults(Statement.CLOSE_CURRENT_RESULT);
                    }
                } finally {
                    settings.execute("SET STATISTICS XML OFF");
                    connection.rollback();
                }
            }
            assertThat(documents).as("actual execution plan returned by SQL Server").isNotEmpty();
            var operators = new ArrayList<String>();
            int conversions = 0;
            double rowsRead = 0;
            int seeks = 0;
            var xpath = XPathFactory.newInstance().newXPath();
            for (Document document : documents) {
                conversions += ((Double) xpath.evaluate("count(//*[local-name()='Convert' and @Implicit='1']//*[local-name()='ColumnReference' and @Column='fence_key'])", document, XPathConstants.NUMBER)).intValue();
                // A selective UPDATE can fold its seek into SimpleUpdate instead of IndexScan.
                NodeList nodes = (NodeList) xpath.evaluate("//*[local-name()='RelOp'][*[local-name()='IndexScan']/*[local-name()='Object' and @Table='[cachedb_write_fence]'] or *[local-name()='SimpleUpdate'][*[local-name()='Object' and @Table='[cachedb_write_fence]'] and *[local-name()='SeekPredicateNew']]]", document, XPathConstants.NODESET);
                for (int i = 0; i < nodes.getLength(); i++) {
                    operators.add(nodes.item(i).getAttributes().getNamedItem("PhysicalOp").getNodeValue());
                    rowsRead += (Double) xpath.evaluate("sum(.//*[local-name()='RunTimeCountersPerThread']/@ActualRowsRead)", nodes.item(i), XPathConstants.NUMBER);
                    seeks += ((Double) xpath.evaluate("count(./*[local-name()='IndexScan' or local-name()='SimpleUpdate']//*[local-name()='SeekPredicateNew'])", nodes.item(i), XPathConstants.NUMBER)).intValue();
                }
            }
            assertThat(operators).isNotEmpty();
            return new Plan(operators, conversions, rowsRead, seeks);
        }

        private static Document parse(String xml) throws Exception {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        }

        @Override public void close() throws Exception {
            BoundedMssqlTestDatabase.closeResources(source, ownedDatabase);
        }
    }

    private static final class Capture {
        final List<Execution> executions = new ArrayList<>();

        DataSource wrap(DataSource source) {
            return (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{DataSource.class},
                    (p, method, args) -> {
                        Object result = invoke(source, method, args);
                        return method.getName().equals("getConnection") ? wrapConnection((Connection) result) : result;
                    });
        }

        Connection wrapConnection(Connection connection) {
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                    (p, method, args) -> {
                        Object result = invoke(connection, method, args);
                        if (!method.getName().equals("prepareStatement")) return result;
                        String sql = (String) args[0];
                        var parameters = new TreeMap<Integer, Object>();
                        return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class},
                                (q, call, values) -> {
                                    if (Set.of("setString", "setLong", "setInt").contains(call.getName()))
                                        parameters.put((Integer) values[0], values[1]);
                                    if (Set.of("executeQuery", "executeUpdate", "addBatch").contains(call.getName()))
                                        executions.add(new Execution(sql, new TreeMap<>(parameters)));
                                    return invoke(result, call, values);
                                });
                    });
        }

        private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
            try { return method.invoke(target, args); }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
        }
    }
}
