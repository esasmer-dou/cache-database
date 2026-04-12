package com.reactor.cachedb.examples.demo;

import com.reactor.cachedb.starter.CacheDatabaseAdmin;
import com.reactor.cachedb.starter.MigrationPlanner;
import com.reactor.cachedb.starter.MigrationPlannerDemoSupport;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Random;

public final class DemoMigrationPlannerBootstrapper implements MigrationPlannerDemoSupport {

    private static final String ROOT_SURFACE = "MigrationDemoCustomerEntity";
    private static final String CHILD_SURFACE = "MigrationDemoOrderEntity";
    private static final String ROOT_TABLE = "cachedb_migration_demo_customers";
    private static final String CHILD_TABLE = "cachedb_migration_demo_orders";
    private static final String TIMELINE_VIEW = "cachedb_migration_demo_customer_order_timeline_v";
    private static final String METRICS_VIEW = "cachedb_migration_demo_customer_metrics_v";
    private static final String RANKED_VIEW = "cachedb_migration_demo_ranked_orders_v";
    private static final int DEFAULT_CUSTOMER_COUNT = 120;
    private static final int DEFAULT_HOT_CUSTOMER_COUNT = 12;
    private static final int DEFAULT_MAX_ORDERS_PER_CUSTOMER = 1500;
    private static final int BATCH_SIZE = 1_000;
    private static final String SCAN_CURSOR_START = "0";

    private final DataSource dataSource;
    private final Runnable schemaPlanApplier;
    private final Runnable telemetryResetter;
    private final Runnable redisNamespaceResetter;

    public DemoMigrationPlannerBootstrapper(
            DataSource dataSource,
            JedisPooled jedis,
            CacheDatabaseAdmin admin,
            String keyPrefix
    ) {
        this(
                dataSource,
                admin::applySchemaMigrationPlan,
                () -> admin.resetTelemetry(),
                () -> clearRedisNamespaces(jedis, keyPrefix)
        );
    }

    DemoMigrationPlannerBootstrapper(
            DataSource dataSource,
            Runnable schemaPlanApplier,
            Runnable telemetryResetter,
            Runnable redisNamespaceResetter
    ) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.schemaPlanApplier = Objects.requireNonNull(schemaPlanApplier, "schemaPlanApplier");
        this.telemetryResetter = Objects.requireNonNull(telemetryResetter, "telemetryResetter");
        this.redisNamespaceResetter = Objects.requireNonNull(redisNamespaceResetter, "redisNamespaceResetter");
    }

    @Override
    public Descriptor descriptor() {
        return new Descriptor(
                true,
                "Customer / order migration demo",
                "Builds a realistic PostgreSQL customer/order schema with PK/FK, indexes, seeded history, and ready-made views so the migration planner can be exercised end to end.",
                DEFAULT_CUSTOMER_COUNT,
                DEFAULT_HOT_CUSTOMER_COUNT,
                DEFAULT_MAX_ORDERS_PER_CUSTOMER,
                defaultPlannerRequest(DEFAULT_CUSTOMER_COUNT, 24_000L, 220L, 1_350L)
        );
    }

    @Override
    public BootstrapResult bootstrap(BootstrapRequest request) {
        BootstrapRequest normalized = request.normalize();
        schemaPlanApplier.run();
        redisNamespaceResetter.run();
        ArrayList<String> sampleRootIds = new ArrayList<>();
        long orderCount = 0L;
        long hottestCustomerOrders = 0L;
        Random random = new Random(42L);
        Instant baseInstant = Instant.parse("2026-04-01T12:00:00Z");

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                ensureSchemaObjects(connection);
                truncateTables(connection);
                insertCustomers(connection, normalized.customerCount(), baseInstant);
                orderCount = insertOrders(
                        connection,
                        normalized.customerCount(),
                        normalized.hotCustomerCount(),
                        normalized.maxOrdersPerCustomer(),
                        baseInstant,
                        random,
                        sampleRootIds
                );
                hottestCustomerOrders = resolveHottestCustomerOrders(normalized.maxOrdersPerCustomer(), normalized.hotCustomerCount());
                createViews(connection);
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not bootstrap migration planner demo schema: " + exception.getMessage(), exception);
        }
        telemetryResetter.run();

        long typicalChildrenPerRoot = Math.max(20L, Math.round(orderCount / (double) normalized.customerCount()));
        MigrationPlanner.Request plannerDefaults = defaultPlannerRequest(
                normalized.customerCount(),
                orderCount,
                typicalChildrenPerRoot,
                hottestCustomerOrders
        );

        ArrayList<String> notes = new ArrayList<>();
        notes.add("PostgreSQL tables were recreated with explicit PK/FK relationships and supporting indexes.");
        notes.add("Views are ready for manual inspection and SQL baselining: " + String.join(", ", List.of(TIMELINE_VIEW, METRICS_VIEW, RANKED_VIEW)) + ".");
        notes.add("Hot customers were seeded with 1000+ orders so the planner can exercise bounded hot-window decisions.");
        notes.add("Existing migration-demo Redis entities, projections, and indexes were cleared before the new warm rehearsal started.");
        if (!sampleRootIds.isEmpty()) {
            notes.add("Suggested sample root ids for compare/warm: " + String.join(", ", sampleRootIds));
        }

        return new BootstrapResult(
                ROOT_SURFACE,
                CHILD_SURFACE,
                ROOT_TABLE,
                CHILD_TABLE,
                List.of(TIMELINE_VIEW, METRICS_VIEW, RANKED_VIEW),
                normalized.customerCount(),
                orderCount,
                hottestCustomerOrders,
                sampleRootIds,
                notes,
                plannerDefaults
        );
    }

    private void ensureSchemaObjects(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS cachedb_migration_demo_customers (
                        customer_id BIGINT PRIMARY KEY,
                        tax_number VARCHAR(32) NOT NULL,
                        customer_type VARCHAR(24) NOT NULL,
                        customer_status VARCHAR(24) NOT NULL,
                        country_code VARCHAR(3) NOT NULL,
                        created_at TIMESTAMP NOT NULL,
                        entity_version BIGINT NOT NULL DEFAULT 1,
                        deleted_flag VARCHAR(16)
                    )
                    """);
            statement.execute("ALTER TABLE cachedb_migration_demo_customers ADD COLUMN IF NOT EXISTS tax_number VARCHAR(32)");
            statement.execute("ALTER TABLE cachedb_migration_demo_customers ADD COLUMN IF NOT EXISTS customer_type VARCHAR(24)");
            statement.execute("ALTER TABLE cachedb_migration_demo_customers ADD COLUMN IF NOT EXISTS customer_status VARCHAR(24)");
            statement.execute("ALTER TABLE cachedb_migration_demo_customers ADD COLUMN IF NOT EXISTS country_code VARCHAR(3)");
            statement.execute("ALTER TABLE cachedb_migration_demo_customers ADD COLUMN IF NOT EXISTS created_at TIMESTAMP");
            statement.execute("ALTER TABLE cachedb_migration_demo_customers ADD COLUMN IF NOT EXISTS entity_version BIGINT DEFAULT 1");
            statement.execute("ALTER TABLE cachedb_migration_demo_customers ADD COLUMN IF NOT EXISTS deleted_flag VARCHAR(16)");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS cachedb_migration_demo_orders (
                        order_id BIGINT PRIMARY KEY,
                        customer_id BIGINT NOT NULL,
                        order_date TIMESTAMP NOT NULL,
                        order_amount DOUBLE PRECISION NOT NULL,
                        currency_code VARCHAR(3) NOT NULL,
                        order_type VARCHAR(24) NOT NULL,
                        rank_score DOUBLE PRECISION NOT NULL,
                        created_at TIMESTAMP NOT NULL,
                        entity_version BIGINT NOT NULL DEFAULT 1,
                        deleted_flag VARCHAR(16)
                    )
                    """);
            statement.execute("ALTER TABLE cachedb_migration_demo_orders ADD COLUMN IF NOT EXISTS customer_id BIGINT");
            statement.execute("ALTER TABLE cachedb_migration_demo_orders ADD COLUMN IF NOT EXISTS order_date TIMESTAMP");
            statement.execute("ALTER TABLE cachedb_migration_demo_orders ADD COLUMN IF NOT EXISTS order_amount DOUBLE PRECISION");
            statement.execute("ALTER TABLE cachedb_migration_demo_orders ADD COLUMN IF NOT EXISTS currency_code VARCHAR(3)");
            statement.execute("ALTER TABLE cachedb_migration_demo_orders ADD COLUMN IF NOT EXISTS order_type VARCHAR(24)");
            statement.execute("ALTER TABLE cachedb_migration_demo_orders ADD COLUMN IF NOT EXISTS rank_score DOUBLE PRECISION");
            statement.execute("ALTER TABLE cachedb_migration_demo_orders ADD COLUMN IF NOT EXISTS created_at TIMESTAMP");
            statement.execute("ALTER TABLE cachedb_migration_demo_orders ADD COLUMN IF NOT EXISTS entity_version BIGINT DEFAULT 1");
            statement.execute("ALTER TABLE cachedb_migration_demo_orders ADD COLUMN IF NOT EXISTS deleted_flag VARCHAR(16)");
            statement.execute("ALTER TABLE cachedb_migration_demo_orders DROP CONSTRAINT IF EXISTS fk_cachedb_migration_demo_orders_customer");
            statement.execute("""
                    ALTER TABLE cachedb_migration_demo_orders
                    ADD CONSTRAINT fk_cachedb_migration_demo_orders_customer
                    FOREIGN KEY (customer_id) REFERENCES cachedb_migration_demo_customers(customer_id)
                    """);
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_cachedb_migration_demo_customers_tax ON cachedb_migration_demo_customers (tax_number)");
            statement.execute("CREATE INDEX IF NOT EXISTS ix_cachedb_migration_demo_orders_customer_date ON cachedb_migration_demo_orders (customer_id, order_date DESC, order_id DESC)");
            statement.execute("CREATE INDEX IF NOT EXISTS ix_cachedb_migration_demo_orders_rank_score ON cachedb_migration_demo_orders (rank_score DESC, order_id DESC)");
            statement.execute("CREATE INDEX IF NOT EXISTS ix_cachedb_migration_demo_orders_order_type ON cachedb_migration_demo_orders (order_type)");
        }
    }

    private void truncateTables(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP VIEW IF EXISTS " + TIMELINE_VIEW);
            statement.execute("DROP VIEW IF EXISTS " + METRICS_VIEW);
            statement.execute("DROP VIEW IF EXISTS " + RANKED_VIEW);
            statement.execute("DELETE FROM " + CHILD_TABLE);
            statement.execute("DELETE FROM " + ROOT_TABLE);
        }
    }

    private void insertCustomers(Connection connection, int customerCount, Instant baseInstant) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO cachedb_migration_demo_customers (
                    customer_id, tax_number, customer_type, customer_status, country_code, created_at, entity_version, deleted_flag
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            int batched = 0;
            for (int customerId = 1; customerId <= customerCount; customerId++) {
                statement.setLong(1, customerId);
                statement.setString(2, String.format(Locale.ROOT, "TAX%09d", customerId));
                statement.setString(3, customerType(customerId));
                statement.setString(4, customerId % 9 == 0 ? "REVIEW" : "ACTIVE");
                statement.setString(5, countryCode(customerId));
                statement.setTimestamp(6, Timestamp.from(baseInstant.minus(customerId, ChronoUnit.DAYS)));
                statement.setLong(7, 1L);
                statement.setString(8, null);
                statement.addBatch();
                batched++;
                if (batched >= BATCH_SIZE) {
                    statement.executeBatch();
                    batched = 0;
                }
            }
            if (batched > 0) {
                statement.executeBatch();
            }
        }
    }

    private long insertOrders(
            Connection connection,
            int customerCount,
            int hotCustomerCount,
            int maxOrdersPerCustomer,
            Instant baseInstant,
            Random random,
            List<String> sampleRootIds
    ) throws SQLException {
        long totalOrders = 0L;
        long nextOrderId = 1L;
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO cachedb_migration_demo_orders (
                    order_id, customer_id, order_date, order_amount, currency_code, order_type, rank_score, created_at, entity_version, deleted_flag
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            int batched = 0;
            for (int customerId = 1; customerId <= customerCount; customerId++) {
                int orderCount = orderCountForCustomer(customerId, hotCustomerCount, maxOrdersPerCustomer);
                if (customerId <= Math.min(3, hotCustomerCount)) {
                    sampleRootIds.add(String.valueOf(customerId));
                }
                for (int orderIndex = 0; orderIndex < orderCount; orderIndex++) {
                    Instant orderDate = baseInstant
                            .minus(orderIndex * 6L + customerId, ChronoUnit.HOURS)
                            .minus(customerId % 5, ChronoUnit.DAYS);
                    double amount = orderAmount(customerId, orderIndex, random);
                    double rankScore = buildRankScore(amount, orderDate, baseInstant, customerId);
                    statement.setLong(1, nextOrderId++);
                    statement.setLong(2, customerId);
                    statement.setTimestamp(3, Timestamp.from(orderDate));
                    statement.setDouble(4, amount);
                    statement.setString(5, currencyCode(customerId, orderIndex));
                    statement.setString(6, orderType(customerId, orderIndex));
                    statement.setDouble(7, rankScore);
                    statement.setTimestamp(8, Timestamp.from(orderDate.minus(2, ChronoUnit.MINUTES)));
                    statement.setLong(9, 1L);
                    statement.setString(10, null);
                    statement.addBatch();
                    batched++;
                    totalOrders++;
                    if (batched >= BATCH_SIZE) {
                        statement.executeBatch();
                        batched = 0;
                    }
                }
            }
            if (batched > 0) {
                statement.executeBatch();
            }
        }
        return totalOrders;
    }

    private void createViews(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE VIEW cachedb_migration_demo_customer_order_timeline_v AS
                    SELECT
                        o.order_id,
                        o.customer_id,
                        c.tax_number,
                        c.customer_type,
                        o.order_date,
                        o.order_amount,
                        o.currency_code,
                        o.order_type,
                        o.rank_score
                    FROM cachedb_migration_demo_orders o
                    JOIN cachedb_migration_demo_customers c ON c.customer_id = o.customer_id
                    """);
            statement.execute("""
                    CREATE VIEW cachedb_migration_demo_customer_metrics_v AS
                    SELECT
                        c.customer_id,
                        c.tax_number,
                        c.customer_type,
                        COUNT(o.order_id) AS order_count,
                        COALESCE(SUM(o.order_amount), 0) AS lifetime_amount,
                        MAX(o.order_date) AS last_order_date
                    FROM cachedb_migration_demo_customers c
                    LEFT JOIN cachedb_migration_demo_orders o ON o.customer_id = c.customer_id
                    GROUP BY c.customer_id, c.tax_number, c.customer_type
                    """);
            statement.execute("""
                    CREATE VIEW cachedb_migration_demo_ranked_orders_v AS
                    SELECT
                        o.order_id,
                        o.customer_id,
                        o.order_date,
                        o.order_amount,
                        o.currency_code,
                        o.order_type,
                        o.rank_score,
                        DENSE_RANK() OVER (ORDER BY o.rank_score DESC, o.order_id DESC) AS global_rank
                    FROM cachedb_migration_demo_orders o
                    """);
        }
    }

    private long resolveHottestCustomerOrders(int maxOrdersPerCustomer, int hotCustomerCount) {
        if (hotCustomerCount <= 0) {
            return 0L;
        }
        return orderCountForCustomer(1, hotCustomerCount, maxOrdersPerCustomer);
    }

    private MigrationPlanner.Request defaultPlannerRequest(
            long customerCount,
            long orderCount,
            long typicalChildrenPerRoot,
            long maxChildrenPerRoot
    ) {
        return new MigrationPlanner.Request(
                "legacy-customer-orders",
                ROOT_SURFACE,
                "customer_id",
                CHILD_SURFACE,
                "order_id",
                "customer_id",
                "order_date",
                "DESC",
                customerCount,
                orderCount,
                typicalChildrenPerRoot,
                maxChildrenPerRoot,
                100,
                1000,
                true,
                false,
                false,
                false,
                true,
                false,
                true,
                true,
                true
        );
    }

    private static void clearRedisNamespaces(JedisPooled jedis, String keyPrefix) {
        Objects.requireNonNull(jedis, "jedis");
        Objects.requireNonNull(keyPrefix, "keyPrefix");
        deleteByPattern(jedis, keyPrefix + ":migration-demo-customers:*");
        deleteByPattern(jedis, keyPrefix + ":migration-demo-orders:*");
    }

    private static void deleteByPattern(JedisPooled jedis, String pattern) {
        ScanParams params = new ScanParams().match(pattern).count(1_000);
        String cursor = SCAN_CURSOR_START;
        do {
            ScanResult<String> scan = jedis.scan(cursor, params);
            cursor = scan.getCursor();
            List<String> keys = scan.getResult();
            if (!keys.isEmpty()) {
                jedis.del(keys.toArray(String[]::new));
            }
        } while (!SCAN_CURSOR_START.equals(cursor));
    }

    private int orderCountForCustomer(int customerId, int hotCustomerCount, int maxOrdersPerCustomer) {
        if (customerId <= hotCustomerCount) {
            int floor = Math.min(maxOrdersPerCustomer, 1_050);
            int spread = Math.max(0, maxOrdersPerCustomer - floor);
            return floor + (spread == 0 ? 0 : (customerId * 17) % (spread + 1));
        }
        return 30 + ((customerId * 53) % 180);
    }

    private String customerType(int customerId) {
        return switch (customerId % 4) {
            case 0 -> "ENTERPRISE";
            case 1 -> "SMB";
            case 2 -> "MARKETPLACE";
            default -> "RETAIL";
        };
    }

    private String countryCode(int customerId) {
        return switch (customerId % 5) {
            case 0 -> "TR";
            case 1 -> "DE";
            case 2 -> "US";
            case 3 -> "AE";
            default -> "NL";
        };
    }

    private double orderAmount(int customerId, int orderIndex, Random random) {
        double base = 150.0d + ((customerId % 9) * 45.0d);
        double dynamic = (orderIndex % 7) * 37.5d;
        return Math.round((base + dynamic + (random.nextDouble() * 90.0d)) * 100.0d) / 100.0d;
    }

    private String currencyCode(int customerId, int orderIndex) {
        int selector = Math.abs(customerId + orderIndex) % 3;
        return selector == 0 ? "TRY" : selector == 1 ? "USD" : "EUR";
    }

    private String orderType(int customerId, int orderIndex) {
        int selector = Math.abs(customerId * 7 + orderIndex) % 4;
        return switch (selector) {
            case 0 -> "STANDARD";
            case 1 -> "RENEWAL";
            case 2 -> "WHOLESALE";
            default -> "EXPRESS";
        };
    }

    private double buildRankScore(double amount, Instant orderDate, Instant baseInstant, int customerId) {
        long recencyHours = Math.max(0L, ChronoUnit.HOURS.between(orderDate, baseInstant));
        double recencyComponent = Math.max(0.0d, 10_000.0d - recencyHours);
        return (amount * 100.0d) + recencyComponent + (customerId % 11);
    }
}
