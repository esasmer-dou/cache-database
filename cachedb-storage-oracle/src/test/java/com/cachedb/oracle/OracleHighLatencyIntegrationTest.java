package com.reactor.cachedb.oracle;

import com.reactor.cachedb.core.config.ResourceLimits;
import com.reactor.cachedb.core.config.WriteBehindConfig;
import com.reactor.cachedb.core.model.EntityCodec;
import com.reactor.cachedb.core.model.OperationType;
import com.reactor.cachedb.core.queue.QueuedWriteOperation;
import com.reactor.cachedb.core.registry.DefaultEntityRegistry;
import com.reactor.cachedb.core.repository.SourceSqlQuery;
import com.reactor.cachedb.jdbc.JdbcSourceSqlRepository;
import oracle.jdbc.pool.OracleDataSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class OracleHighLatencyIntegrationTest {
    private static final Pattern EASY_CONNECT_URL = Pattern.compile(
            "^jdbc:oracle:thin:@//(\\[[^]]+]|[^:/?#]+):(\\d+)/([^?]+)(?:\\?.*)?$",
            Pattern.CASE_INSENSITIVE
    );
    private static final String DIRECT_URL = System.getProperty(
            "cachedb.it.oracle.url",
            "jdbc:oracle:thin:@//127.0.0.1:15211/FREEPDB1"
    );
    private static final String USER = System.getProperty("cachedb.it.oracle.user", "cachedb");
    private static final String PASSWORD = System.getProperty("cachedb.it.oracle.password", "CacheDbOracle123");

    @Test
    void shouldPreserveWriteAndBoundedReadAcrossInjectedNetworkLatency() throws Exception {
        assumeReachable();
        OracleEndpoint endpoint = OracleEndpoint.from(DIRECT_URL);
        try (Connection connection = openDirect(); Statement statement = connection.createStatement()) {
            drop(statement);
            statement.executeUpdate("""
                    CREATE TABLE cachedb_it_latency_entity (
                        id NUMBER(19) NOT NULL PRIMARY KEY,
                        name VARCHAR2(200 CHAR) NOT NULL,
                        entity_version NUMBER(19) NOT NULL
                    )
                    """);
        }

        long startedAt = System.nanoTime();
        try (LatencyTcpProxy proxy = new LatencyTcpProxy(endpoint.host(), endpoint.port(), 15)) {
            DataSource delayedDataSource = dataSource(
                    "jdbc:oracle:thin:@//127.0.0.1:" + proxy.localPort() + "/" + endpoint.serviceName()
            );
            OracleWriteBehindFlusher flusher = new OracleWriteBehindFlusher(
                    delayedDataSource,
                    new DefaultEntityRegistry(ResourceLimits.defaults()),
                    WriteBehindConfig.builder().maxFlushBatchSize(20).build(),
                    OracleWriteBehindOptions.builder().queryTimeoutSeconds(10).build()
            );
            flusher.flushBatch(operations(20));

            JdbcSourceSqlRepository<String> repository = new JdbcSourceSqlRepository<>(
                    delayedDataSource,
                    new NameCodec()
            );
            List<String> names = repository.query(SourceSqlQuery.of(
                    "SELECT name FROM cachedb_it_latency_entity ORDER BY id FETCH FIRST 20 ROWS ONLY",
                    List.of(),
                    20
            ));

            assertEquals(20, names.size());
            assertEquals("entity-1", names.get(0));
            assertEquals("entity-20", names.get(19));
        } finally {
            try (Connection connection = openDirect(); Statement statement = connection.createStatement()) {
                drop(statement);
            }
        }
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        assertTrue(elapsedMillis >= 100L, "Injected network latency was not observed");
    }

    private List<QueuedWriteOperation> operations(int count) {
        ArrayList<QueuedWriteOperation> operations = new ArrayList<>();
        for (int id = 1; id <= count; id++) {
            LinkedHashMap<String, String> columns = new LinkedHashMap<>();
            columns.put("id", String.valueOf(id));
            columns.put("name", "entity-" + id);
            columns.put("entity_version", "1");
            operations.add(new QueuedWriteOperation(
                    OperationType.UPSERT,
                    "LatencyEntity",
                    "cachedb_it_latency_entity",
                    "oracle",
                    "high-rtt",
                    "id",
                    "entity_version",
                    "deleted",
                    String.valueOf(id),
                    columns,
                    1L,
                    Instant.parse("2026-08-23T00:00:00Z")
            ));
        }
        return List.copyOf(operations);
    }

    private DataSource dataSource(String url) throws SQLException {
        OracleDataSource dataSource = new OracleDataSource();
        dataSource.setURL(url);
        dataSource.setUser(USER);
        dataSource.setPassword(PASSWORD);
        return dataSource;
    }

    private Connection openDirect() throws SQLException {
        return DriverManager.getConnection(DIRECT_URL, USER, PASSWORD);
    }

    private void drop(Statement statement) throws SQLException {
        try {
            statement.executeUpdate("DROP TABLE cachedb_it_latency_entity PURGE");
        } catch (SQLException exception) {
            if (Math.abs(exception.getErrorCode()) != 942) {
                throw exception;
            }
        }
    }

    private void assumeReachable() {
        try (Connection ignored = openDirect()) {
            return;
        } catch (SQLException exception) {
            if (Boolean.getBoolean("cachedb.it.oracle.required")) {
                fail("No reachable Oracle test database found at " + DIRECT_URL, exception);
            }
        }
        Assumptions.assumeTrue(false, "No reachable Oracle test database found");
    }

    private static final class NameCodec implements EntityCodec<String> {
        @Override public String toRedisValue(String entity) { return entity; }
        @Override public String fromRedisValue(String encoded) { return encoded; }
        @Override public Map<String, Object> toColumns(String entity) { return Map.of("name", entity); }
        @Override
        public String fromColumns(Map<String, Object> columns) {
            return columns.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase("name"))
                    .map(entry -> String.valueOf(entry.getValue()))
                    .findFirst()
                    .orElseThrow();
        }
    }

    private record OracleEndpoint(String host, int port, String serviceName) {
        private static OracleEndpoint from(String jdbcUrl) {
            Matcher matcher = EASY_CONNECT_URL.matcher(jdbcUrl);
            if (!matcher.matches()) {
                throw new IllegalArgumentException(
                        "Oracle latency evidence requires an Easy Connect JDBC URL: "
                                + "jdbc:oracle:thin:@//host:port/service"
                );
            }
            String host = matcher.group(1);
            if (host.startsWith("[") && host.endsWith("]")) {
                host = host.substring(1, host.length() - 1);
            }
            return new OracleEndpoint(host, Integer.parseInt(matcher.group(2)), matcher.group(3));
        }
    }

    private static final class LatencyTcpProxy implements AutoCloseable {
        private final String upstreamHost;
        private final int upstreamPort;
        private final int latencyMillis;
        private final ServerSocket serverSocket;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final ExecutorService connections = Executors.newCachedThreadPool();
        private final Thread acceptThread;

        private LatencyTcpProxy(String upstreamHost, int upstreamPort, int latencyMillis) throws IOException {
            this.upstreamHost = upstreamHost;
            this.upstreamPort = upstreamPort;
            this.latencyMillis = latencyMillis;
            this.serverSocket = new ServerSocket(0, 64, InetAddress.getLoopbackAddress());
            this.acceptThread = new Thread(this::acceptLoop, "cachedb-oracle-latency-proxy");
            this.acceptThread.setDaemon(true);
            this.acceptThread.start();
        }

        private int localPort() {
            return serverSocket.getLocalPort();
        }

        private void acceptLoop() {
            while (running.get()) {
                try {
                    Socket client = serverSocket.accept();
                    connections.submit(() -> handle(client));
                } catch (IOException exception) {
                    if (running.get()) {
                        throw new IllegalStateException("Oracle latency proxy accept failed", exception);
                    }
                }
            }
        }

        private void handle(Socket client) {
            try (client; Socket upstream = new Socket(upstreamHost, upstreamPort)) {
                client.setTcpNoDelay(true);
                upstream.setTcpNoDelay(true);
                Future<?> request = connections.submit(() -> relay(client, upstream));
                Future<?> response = connections.submit(() -> relay(upstream, client));
                request.get();
                response.get();
            } catch (Exception ignored) {
                // Socket closure is the normal end of a proxied JDBC connection.
            }
        }

        private void relay(Socket source, Socket target) {
            byte[] buffer = new byte[16 * 1024];
            try {
                InputStream input = source.getInputStream();
                OutputStream output = target.getOutputStream();
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read == 0) {
                        continue;
                    }
                    Thread.sleep(latencyMillis);
                    output.write(buffer, 0, read);
                    output.flush();
                }
            } catch (IOException | InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void close() throws Exception {
            running.set(false);
            serverSocket.close();
            acceptThread.join(2_000L);
            connections.shutdownNow();
            connections.awaitTermination(2, TimeUnit.SECONDS);
        }
    }
}
