package com.reactor.cachedb.examples.demo;

import com.reactor.cachedb.core.api.EntityRepository;
import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.cache.PageWindow;
import com.reactor.cachedb.core.plan.FetchPlan;
import com.reactor.cachedb.core.query.QueryFilter;
import com.reactor.cachedb.core.query.QuerySort;
import com.reactor.cachedb.core.query.QuerySpec;
import com.reactor.cachedb.core.queue.PerformanceObservationContext;
import com.reactor.cachedb.core.queue.StoragePerformanceCollector;
import com.reactor.cachedb.examples.demo.entity.DemoCartEntity;
import com.reactor.cachedb.examples.demo.entity.DemoCartEntityCacheBinding;
import com.reactor.cachedb.examples.demo.entity.DemoCustomerEntity;
import com.reactor.cachedb.examples.demo.entity.DemoCustomerEntityCacheBinding;
import com.reactor.cachedb.examples.demo.entity.DemoOrderEntity;
import com.reactor.cachedb.examples.demo.entity.DemoOrderEntityCacheBinding;
import com.reactor.cachedb.examples.demo.entity.DemoOrderLineEntity;
import com.reactor.cachedb.examples.demo.entity.DemoOrderLineEntityCacheBinding;
import com.reactor.cachedb.examples.demo.entity.DemoProductEntity;
import com.reactor.cachedb.examples.demo.entity.DemoProductEntityCacheBinding;
import com.reactor.cachedb.starter.CacheDatabase;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Callable;
import java.util.function.LongSupplier;

public final class DemoScenarioService implements AutoCloseable {
    private final DemoScenarioTuning tuning;
    private final DataSource postgresDataSource;
    private final StoragePerformanceCollector performanceCollector;
    private final EntityRepository<DemoCustomerEntity, Long> customerRepository;
    private final EntityRepository<DemoProductEntity, Long> productRepository;
    private final EntityRepository<DemoCartEntity, Long> cartRepository;
    private final EntityRepository<DemoOrderEntity, Long> orderRepository;
    private final EntityRepository<DemoOrderLineEntity, Long> orderLineRepository;
    private final DemoOrderReadModelPatterns orderReadModelPatterns;
    private final AtomicBoolean seeded = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<DemoScenarioLevel> activeScenario = new AtomicReference<>();
    private final AtomicReference<DemoLoadProfile> activeLoadProfile = new AtomicReference<>();
    private final AtomicLong readCount = new AtomicLong();
    private final AtomicLong writeCount = new AtomicLong();
    private final AtomicLong readSequence = new AtomicLong();
    private final AtomicLong writeSequence = new AtomicLong();
    private final AtomicLong errorCount = new AtomicLong();
    private final AtomicLong lastSeededAtEpochMillis = new AtomicLong();
    private final AtomicLong lastStartedAtEpochMillis = new AtomicLong();
    private final AtomicLong lastStoppedAtEpochMillis = new AtomicLong();
    private final AtomicLong actionGeneration = new AtomicLong();
    private final String instanceId = UUID.randomUUID().toString();
    private final AtomicLong customerViewCount = new AtomicLong();
    private final AtomicLong productViewCount = new AtomicLong();
    private final AtomicLong cartViewCount = new AtomicLong();
    private final AtomicLong orderViewCount = new AtomicLong();
    private final AtomicLong orderLineViewCount = new AtomicLong();
    private final AtomicReference<String> lastError = new AtomicReference<>("");
    private final AtomicReference<String> maintenanceStage = new AtomicReference<>("Hazir");
    private final AtomicLong maintenanceStageUpdatedAtEpochMillis = new AtomicLong(System.currentTimeMillis());
    private final AtomicLong maintenanceGeneration = new AtomicLong();
    private final DemoScenarioShapeCollector scenarioShapeCollector = new DemoScenarioShapeCollector();
    private final Object viewLock = new Object();
    // Keep a small in-memory mirror for the demo UI so seed state is visible immediately.
    private final Map<Long, DemoCustomerEntity> customerViewStore = new LinkedHashMap<>();
    private final Map<Long, DemoProductEntity> productViewStore = new LinkedHashMap<>();
    private final Map<Long, DemoCartEntity> cartViewStore = new LinkedHashMap<>();
    private final Map<Long, DemoOrderEntity> orderViewStore = new LinkedHashMap<>();
    private final Map<Long, DemoOrderLineEntity> orderLineViewStore = new LinkedHashMap<>();
    private final Map<Long, List<Long>> orderLineIdsByOrder = new LinkedHashMap<>();
    private final ExecutorService maintenanceExecutor;
    private final Runnable environmentResetter;
    private final Runnable clearDataResetter;
    private final DemoSeedBootstrapper seedBootstrapper;
    private Future<?> maintenanceTask;
    private ExecutorService executor;

    public DemoScenarioService(CacheDatabase cacheDatabase, DemoScenarioTuning tuning, Runnable environmentResetter) {
        this(cacheDatabase, tuning, environmentResetter, null, null);
    }

    public DemoScenarioService(
            CacheDatabase cacheDatabase,
            DemoScenarioTuning tuning,
            Runnable environmentResetter,
            DemoSeedBootstrapper seedBootstrapper
    ) {
        this(cacheDatabase, tuning, environmentResetter, null, seedBootstrapper);
    }

    public DemoScenarioService(
            CacheDatabase cacheDatabase,
            DemoScenarioTuning tuning,
            Runnable environmentResetter,
            Runnable clearDataResetter,
            DemoSeedBootstrapper seedBootstrapper
    ) {
        this.tuning = tuning;
        this.postgresDataSource = cacheDatabase.dataSource();
        this.performanceCollector = cacheDatabase.storagePerformanceCollector();
        CachePolicy policy = tuning.cachePolicy();
        this.customerRepository = cacheDatabase.repository(DemoCustomerEntityCacheBinding.METADATA, DemoCustomerEntityCacheBinding.CODEC, policy);
        this.productRepository = cacheDatabase.repository(DemoProductEntityCacheBinding.METADATA, DemoProductEntityCacheBinding.CODEC, policy);
        this.cartRepository = cacheDatabase.repository(DemoCartEntityCacheBinding.METADATA, DemoCartEntityCacheBinding.CODEC, policy);
        this.orderRepository = cacheDatabase.repository(DemoOrderEntityCacheBinding.METADATA, DemoOrderEntityCacheBinding.CODEC, policy);
        this.orderLineRepository = cacheDatabase.repository(DemoOrderLineEntityCacheBinding.METADATA, DemoOrderLineEntityCacheBinding.CODEC, policy);
        this.orderReadModelPatterns = new DemoOrderReadModelPatterns(orderRepository, orderLineRepository);
        this.maintenanceExecutor = Executors.newFixedThreadPool(maintenanceParallelism(), new DemoMaintenanceThreadFactory());
        this.environmentResetter = environmentResetter;
        this.clearDataResetter = clearDataResetter;
        this.seedBootstrapper = seedBootstrapper;
    }

    public DemoScenarioSnapshot seedDemoData() {
        long expectedActionGeneration = actionGeneration.get();
        synchronized (this) {
            stopScenario();
            setMaintenanceStage("Hazirlik yapiliyor");
            scenarioShapeCollector.reset();
            synchronized (viewLock) {
                customerViewStore.clear();
                productViewStore.clear();
                cartViewStore.clear();
                orderViewStore.clear();
                orderLineViewStore.clear();
                orderLineIdsByOrder.clear();
                for (long id = 1; id <= tuning.customerSeedCount(); id++) {
                    customerViewStore.put(id, copy(customer(id)));
                }
                for (long id = 1; id <= tuning.productSeedCount(); id++) {
                    productViewStore.put(id, copy(product(id)));
                }
                for (long id = 1; id <= tuning.cartSeedCount(); id++) {
                    cartViewStore.put(id, copy(cart(id)));
                }
                populateOrderAndLineSeedData();
            }
            long generation = maintenanceGeneration.incrementAndGet();
            DemoSeedPayload payload = new DemoSeedPayload(
                    snapshotItems(customerViewStore),
                    snapshotItems(productViewStore),
                    snapshotItems(cartViewStore),
                    snapshotItems(orderViewStore),
                    snapshotItems(orderLineViewStore)
            );
            if (isSuperseded(expectedActionGeneration)) {
                clearViewStores();
                resetCounters();
                customerViewCount.set(0);
                productViewCount.set(0);
                cartViewCount.set(0);
                orderViewCount.set(0);
                orderLineViewCount.set(0);
                seeded.set(false);
                setMaintenanceStage("Iptal edildi");
                maintenanceTask = null;
                return snapshot();
            }
            maintenanceTask = maintenanceExecutor.submit(() -> persistSeedData(generation, payload));
        }
        awaitMaintenanceTask();
        if (isSuperseded(expectedActionGeneration)) {
            return snapshot();
        }
        synchronized (this) {
            customerViewCount.set(tuning.customerSeedCount());
            productViewCount.set(tuning.productSeedCount());
            cartViewCount.set(tuning.cartSeedCount());
            orderViewCount.set(tuning.orderSeedCount());
            orderLineViewCount.set(tuning.orderLineSeedCount());
            seeded.set(true);
            lastSeededAtEpochMillis.set(System.currentTimeMillis());
            setMaintenanceStage("Tamamlandi");
        }
        return snapshot();
    }

    public DemoScenarioSnapshot clearDemoData() {
        synchronized (this) {
            actionGeneration.incrementAndGet();
            stopScenario();
            abortPendingMaintenance();
            setMaintenanceStage("Tum demo verisi temizleniyor");
            if (!seeded.get()
                    && customerViewCount.get() == 0
                    && productViewCount.get() == 0
                    && cartViewCount.get() == 0
                    && orderViewCount.get() == 0
                    && orderLineViewCount.get() == 0) {
                resetCounters();
                clearViewStores();
                setMaintenanceStage("Tamamlandi");
                return snapshot();
            }
            seeded.set(false);
            scenarioShapeCollector.reset();
            clearViewStores();
            resetCounters();
            customerViewCount.set(0);
            productViewCount.set(0);
            cartViewCount.set(0);
            orderViewCount.set(0);
            orderLineViewCount.set(0);
        }
        if (clearDataResetter != null) {
            clearDataResetter.run();
            setMaintenanceStage("Tamamlandi");
            return snapshot();
        }
        long generation = maintenanceGeneration.get();
        maintenanceTask = maintenanceExecutor.submit(() -> deleteSeedData(generation));
        awaitMaintenanceTask();
        setMaintenanceStage("Tamamlandi");
        return snapshot();
    }

    public synchronized DemoScenarioSnapshot resetEnvironment() {
        actionGeneration.incrementAndGet();
        stopScenario();
        abortPendingMaintenance();
        setMaintenanceStage("Redis, PostgreSQL ve demo telemetrisi sifirlaniyor");
        seeded.set(false);
        scenarioShapeCollector.reset();
        clearViewStores();
        resetCounters();
        customerViewCount.set(0);
        productViewCount.set(0);
        cartViewCount.set(0);
        orderViewCount.set(0);
        orderLineViewCount.set(0);
        environmentResetter.run();
        setMaintenanceStage("Tamamlandi");
        return snapshot();
    }

    public DemoScenarioSnapshot startScenario(DemoScenarioLevel level) {
        return startScenario(level, null, null);
    }

    public DemoScenarioSnapshot startScenario(DemoScenarioLevel level, Integer readerThreadsOverride, Integer writerThreadsOverride) {
        long expectedActionGeneration = actionGeneration.get();
        if (!seeded.get()) {
            throw new IllegalStateException("Demo verisi henuz hazir degil. Once Seed Demo Data calistir.");
        }
        stopScenario();
        awaitMaintenanceTask();
        if (isSuperseded(expectedActionGeneration) || !seeded.get()) {
            return snapshot();
        }
        DemoLoadProfile profile = effectiveProfile(level, readerThreadsOverride, writerThreadsOverride);
        ExecutorService nextExecutor = Executors.newFixedThreadPool(
                profile.readerThreads() + profile.writerThreads(),
                new DemoThreadFactory()
        );
        synchronized (this) {
            running.set(true);
            activeScenario.set(level);
            activeLoadProfile.set(profile);
            lastStartedAtEpochMillis.set(System.currentTimeMillis());
            executor = nextExecutor;
        }
        for (int i = 0; i < profile.readerThreads(); i++) {
            final int workerId = i;
            nextExecutor.submit(() -> readerLoop(level, workerId));
        }
        for (int i = 0; i < profile.writerThreads(); i++) {
            final int workerId = i;
            nextExecutor.submit(() -> writerLoop(level, workerId));
        }
        return snapshot();
    }

    public synchronized DemoScenarioSnapshot stopScenario() {
        running.set(false);
        activeScenario.set(null);
        activeLoadProfile.set(null);
        lastStoppedAtEpochMillis.set(System.currentTimeMillis());
        if (executor != null) {
            executor.shutdownNow();
            try {
                executor.awaitTermination(tuning.stopAwaitTerminationMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            executor = null;
        }
        return snapshot();
    }

    public synchronized DemoScenarioSnapshot snapshot() {
        DemoScenarioLevel level = activeScenario.get();
        String scenario = level == null ? "IDLE" : level.name();
        String scenarioLabel = level == null ? "Idle" : level.label();
        DemoLoadProfile profile = activeLoadProfile.get();
        if (profile == null && level != null) {
            profile = tuning.profile(level);
        }
        boolean maintenanceInProgress = isMaintenanceInProgress();
        String scenarioSummary = level == null
                ? "Su anda arka planda demo yuk uretilmiyor. Istersen once Seed Demo Data, sonra Low/Medium/High sec."
                : level.summary();
        String currentAction = maintenanceInProgress
                ? maintenanceStage.get()
                : !seeded.get()
                ? "Henuz seed edilmedi veya veriler temizlendi"
                : running.get()
                ? "Arka planda " + scenarioLabel + " seviyesi demo yuku calisiyor"
                : "Seed verisi hazir, yuk durdurulmus durumda";
        return new DemoScenarioSnapshot(
                seeded.get(),
                running.get(),
                scenario,
                scenarioLabel,
                scenarioSummary,
                profile == null ? 0 : profile.readerThreads(),
                profile == null ? 0 : profile.writerThreads(),
                customerViewCount.get(),
                productViewCount.get(),
                cartViewCount.get(),
                orderViewCount.get(),
                orderLineViewCount.get(),
                readCount.get(),
                writeCount.get(),
                errorCount.get(),
                tuning.seedSummary(),
                currentAction,
                lastSeededAtEpochMillis.get(),
                lastStartedAtEpochMillis.get(),
                lastStoppedAtEpochMillis.get(),
                lastError.get(),
                Instant.now()
        );
    }

    public String instanceId() {
        return instanceId;
    }

    public String maintenanceStage() {
        return maintenanceStage.get();
    }

    public long maintenanceStageUpdatedAtEpochMillis() {
        return maintenanceStageUpdatedAtEpochMillis.get();
    }

    public boolean maintenanceInProgress() {
        return isMaintenanceInProgress();
    }

    public List<DemoCustomerEntity> customersView() {
        synchronized (viewLock) {
            return firstPage(customerViewStore, tuning.entityViewPageSize());
        }
    }

    public List<DemoProductEntity> productsView() {
        synchronized (viewLock) {
            return firstPage(productViewStore, tuning.entityViewPageSize());
        }
    }

    public List<DemoCartEntity> cartsView() {
        synchronized (viewLock) {
            return firstPage(cartViewStore, tuning.entityViewPageSize());
        }
    }

    public List<DemoOrderEntity> ordersView() {
        synchronized (viewLock) {
            return firstPage(orderViewStore, tuning.entityViewPageSize());
        }
    }

    public List<DemoOrderLineEntity> orderLinesView() {
        synchronized (viewLock) {
            return firstPage(orderLineViewStore, tuning.entityViewPageSize());
        }
    }

    public DemoScenarioShapeSnapshot scenarioShapeSnapshot() {
        return scenarioShapeCollector.snapshot();
    }

    public void resetScenarioShapeTelemetry() {
        scenarioShapeCollector.reset();
    }

    private void readerLoop(DemoScenarioLevel level, int workerId) {
        DemoLoadProfile profile = tuning.profile(level);
        long pauseMillis = profile.readerPauseMillis();
        while (running.get() && activeScenario.get() == level) {
            try {
                long cycle = readSequence.incrementAndGet();
                int observed = (int) Math.max(1L, performRead(level, cycle, workerId));
                if (!running.get() || activeScenario.get() != level) {
                    return;
                }
                readCount.addAndGet(observed);
                sleepQuietly(pauseMillis);
            } catch (RuntimeException exception) {
                if (!running.get() || activeScenario.get() != level) {
                    return;
                }
                recordError(exception);
            }
        }
    }

    private void writerLoop(DemoScenarioLevel level, int workerId) {
        DemoLoadProfile profile = tuning.profile(level);
        long pauseMillis = profile.writerPauseMillis();
        while (running.get() && activeScenario.get() == level) {
            try {
                long cycle = writeSequence.incrementAndGet();
                int observed = (int) Math.max(1L, performWrite(level, cycle, workerId));
                if (!running.get() || activeScenario.get() != level) {
                    return;
                }
                writeCount.addAndGet(observed);
                sleepQuietly(pauseMillis);
            } catch (RuntimeException exception) {
                if (!running.get() || activeScenario.get() != level) {
                    return;
                }
                recordError(exception);
            }
        }
    }

    private long performRead(DemoScenarioLevel level, long cycle, int workerId) {
        int slot = scenarioSlot(level, cycle, workerId, 24);
        switch (level) {
            case LOW -> {
                if (slot < 3) {
                    return measureScenarioRead("catalog-read", () -> browseProductCatalog(cycle, workerId, false));
                } else if (slot < 5) {
                    return measureScenarioRead("hot-product-read", () -> readHotProducts(level, cycle, workerId, 32));
                } else if (slot < 7) {
                    return measureScenarioRead("customer-profile-read", () -> readCustomerProfiles(cycle, workerId, 14));
                } else if (slot < 10) {
                    return measureScenarioRead("bulk-customer-read", this::readAllCustomersSnapshot);
                } else if (slot < 12) {
                    return measureScenarioPostgresRead("postgres-bulk-customer-read", () -> readAllCustomersSnapshotFromPostgres(2, tuning.bulkCustomerReadPageSize()));
                } else if (slot < 14) {
                    return measureScenarioRead("cart-window-read", () -> readCartWindow(cycle, workerId));
                } else if (slot < 17) {
                    return measureScenarioRead("top-customer-orders-read", () -> readTopCustomerOrdersWithLines(level));
                } else if (slot < 19) {
                    return measureScenarioPostgresRead("postgres-top-customer-orders-read", () -> readTopCustomerOrdersWithLinesFromPostgres(level));
                } else if (slot < 21) {
                    return measureScenarioRead("high-line-orders-read", () -> readHighLineOrdersWithLines(level));
                } else if (slot < 23) {
                    return measureScenarioPostgresRead("postgres-high-line-orders-read", () -> readHighLineOrdersWithLinesFromPostgres(level));
                }
                return measureScenarioRead("order-window-with-lines-read", () -> readOrderWindowWithLines(cycle, workerId, false));
            }
            case MEDIUM -> {
                if (slot < 3) {
                    return measureScenarioRead("catalog-read", () -> browseProductCatalog(cycle, workerId, true));
                } else if (slot < 5) {
                    return measureScenarioRead("hot-product-read", () -> readHotProducts(level, cycle, workerId, tuning.bulkProductReadSize()));
                } else if (slot < 7) {
                    return measureScenarioRead("customer-profile-read", () -> readCustomerProfiles(cycle, workerId, 24));
                } else if (slot < 9) {
                    return measureScenarioRead("bulk-customer-read", this::readAllCustomersSnapshot);
                } else if (slot < 11) {
                    return measureScenarioPostgresRead("postgres-bulk-customer-read", () -> readAllCustomersSnapshotFromPostgres(3, tuning.bulkCustomerReadPageSize()));
                } else if (slot < 15) {
                    return measureScenarioRead("top-customer-orders-read", () -> readTopCustomerOrdersWithLines(level));
                } else if (slot < 18) {
                    return measureScenarioPostgresRead("postgres-top-customer-orders-read", () -> readTopCustomerOrdersWithLinesFromPostgres(level));
                } else if (slot < 21) {
                    return measureScenarioRead("high-line-orders-read", () -> readHighLineOrdersWithLines(level));
                } else if (slot < 23) {
                    return measureScenarioPostgresRead("postgres-high-line-orders-read", () -> readHighLineOrdersWithLinesFromPostgres(level));
                }
                return measureScenarioRead("order-window-with-lines-read", () -> readOrderWindowWithLines(cycle, workerId, true));
            }
            case HIGH -> {
                if (slot < 3) {
                    return measureScenarioRead("hot-product-read", () -> readHotProducts(level, cycle, workerId, tuning.bulkProductReadSize()));
                } else if (slot < 5) {
                    return measureScenarioRead("catalog-read", () -> browseProductCatalog(cycle, workerId, true));
                } else if (slot < 7) {
                    return measureScenarioRead("bulk-customer-read", this::readAllCustomersSnapshot);
                } else if (slot < 10) {
                    return measureScenarioPostgresRead("postgres-bulk-customer-read", () -> readAllCustomersSnapshotFromPostgres(4, tuning.bulkCustomerReadPageSize()));
                } else if (slot < 14) {
                    return measureScenarioRead("top-customer-orders-read", () -> readTopCustomerOrdersWithLines(level));
                } else if (slot < 17) {
                    return measureScenarioPostgresRead("postgres-top-customer-orders-read", () -> readTopCustomerOrdersWithLinesFromPostgres(level));
                } else if (slot < 20) {
                    return measureScenarioRead("high-line-orders-read", () -> readHighLineOrdersWithLines(level));
                } else if (slot < 22) {
                    return measureScenarioPostgresRead("postgres-high-line-orders-read", () -> readHighLineOrdersWithLinesFromPostgres(level));
                } else if (slot < 23) {
                    return measureScenarioRead("cart-window-read", () -> readCartWindow(cycle, workerId));
                }
                return measureScenarioRead("order-window-with-lines-read", () -> readOrderWindowWithLines(cycle, workerId, true));
            }
        }
        throw new IllegalStateException("Unsupported read scenario level: " + level);
    }

    private long performWrite(DemoScenarioLevel level, long cycle, int workerId) {
        int slot = scenarioSlot(level, cycle, workerId, 20);
        switch (level) {
            case LOW -> {
                if (slot < 5) {
                    return measureScenarioWrite("product-write-burst", () -> mutateProductWave(level, cycle, workerId, 3));
                } else if (slot < 10) {
                    return measureScenarioWrite("cart-write-burst", () -> mutateCartWave(level, cycle, workerId, 3));
                } else if (slot < 14) {
                    return measureScenarioWrite("customer-write-burst", () -> mutateCustomerWave(level, cycle, workerId, 3));
                }
                return measureScenarioWrite("order-write-burst", () -> mutateOrderWave(level, cycle, workerId, 2, 10));
            }
            case MEDIUM -> {
                if (slot < 4) {
                    return measureScenarioWrite("cart-write-burst", () -> mutateCartWave(level, cycle, workerId, 5));
                } else if (slot < 8) {
                    return measureScenarioWrite("product-write-burst", () -> mutateProductWave(level, cycle, workerId, 4));
                } else if (slot < 12) {
                    return measureScenarioWrite("order-write-burst", () -> mutateOrderWave(level, cycle, workerId, 3, 20));
                } else if (slot < 17) {
                    return measureScenarioWrite("order-line-write-burst", () -> mutateOrderLineWave(level, cycle, workerId, 3, 40));
                }
                return measureScenarioWrite("customer-write-burst", () -> mutateCustomerWave(level, cycle, workerId, 4));
            }
            case HIGH -> {
                if (slot < 4) {
                    return measureScenarioWrite("cart-write-burst", () -> mutateCartWave(level, cycle, workerId, 8));
                } else if (slot < 8) {
                    return measureScenarioWrite("product-write-burst", () -> mutateProductWave(level, cycle, workerId, 6));
                } else if (slot < 13) {
                    return measureScenarioWrite("order-write-burst", () -> mutateOrderWave(level, cycle, workerId, 2, 12));
                } else if (slot < 18) {
                    return measureScenarioWrite("order-line-write-burst", () -> mutateOrderLineWave(level, cycle, workerId, 2, 20));
                }
                return measureScenarioWrite("customer-write-burst", () -> mutateCustomerWave(level, cycle, workerId, 5));
            }
        }
        throw new IllegalStateException("Unsupported write scenario level: " + level);
    }

    private long measureScenarioRead(String tag, LongSupplier supplier) {
        return PerformanceObservationContext.supplyWithTag(tag, supplier::getAsLong);
    }

    private long measureScenarioWrite(String tag, LongSupplier supplier) {
        return PerformanceObservationContext.supplyWithTag(tag, supplier::getAsLong);
    }

    private long measureScenarioPostgresRead(String tag, LongSupplier supplier) {
        long startedAt = System.nanoTime();
        try {
            return supplier.getAsLong();
        } finally {
            performanceCollector.recordPostgresRead(tag, (System.nanoTime() - startedAt) / 1_000L);
        }
    }

    private long mutateCustomerWave(DemoScenarioLevel level, long cycle, int workerId, int batchSize) {
        for (int index = 0; index < batchSize; index++) {
            mutateCustomer(level, cycle + index, workerId);
        }
        return batchSize;
    }

    private void mutateCustomer(DemoScenarioLevel level, long cycle, int workerId) {
        long id = customerIdForScenario(level, cycle, workerId);
        DemoCustomerEntity entity;
        synchronized (viewLock) {
            entity = copy(customerViewStore.getOrDefault(id, customer(id)));
        }
        entity.tier = switch ((int) ((cycle + workerId) % 4)) {
            case 0 -> "BRONZE";
            case 1 -> "SILVER";
            case 2 -> "GOLD";
            default -> "PLATINUM";
        };
        entity.status = switch ((int) ((cycle + workerId) % 6)) {
            case 0 -> "ACTIVE";
            case 1 -> "LOYAL";
            case 2 -> "REVIEW";
            case 3 -> "VIP";
            case 4 -> "ONBOARDING";
            default -> level == DemoScenarioLevel.HIGH ? "CAMPAIGN" : "ACTIVE";
        };
        synchronized (viewLock) {
            customerViewStore.put(id, copy(entity));
        }
        customerRepository.save(entity);
    }

    private long mutateProductWave(DemoScenarioLevel level, long cycle, int workerId, int batchSize) {
        for (int index = 0; index < batchSize; index++) {
            mutateProduct(level, cycle + index, workerId);
        }
        return batchSize;
    }

    private void mutateProduct(DemoScenarioLevel level, long cycle, int workerId) {
        long id = productIdForScenario(level, cycle, workerId);
        DemoProductEntity entity;
        synchronized (viewLock) {
            entity = copy(productViewStore.getOrDefault(id, product(id)));
        }
        int stockBase = level == DemoScenarioLevel.HIGH ? 18 : 32;
        entity.stock = Math.max(0, stockBase + (int) ((cycle + workerId) % 70) - (level == DemoScenarioLevel.HIGH ? 12 : 4));
        entity.price = 29.0 + (cycle % 55) + ((workerId % 3) * 0.35);
        synchronized (viewLock) {
            productViewStore.put(id, copy(entity));
        }
        productRepository.save(entity);
    }

    private long mutateCartWave(DemoScenarioLevel level, long cycle, int workerId, int batchSize) {
        for (int index = 0; index < batchSize; index++) {
            mutateCart(level, cycle + index, workerId);
        }
        return batchSize;
    }

    private void mutateCart(DemoScenarioLevel level, long cycle, int workerId) {
        long id = cartIdForScenario(level, cycle, workerId);
        DemoCartEntity entity;
        synchronized (viewLock) {
            entity = copy(cartViewStore.getOrDefault(id, cart(id)));
        }
        entity.customerId = customerIdForScenario(level, cycle + workerId, workerId);
        entity.productId = productIdForScenario(level, cycle + (workerId * 3L), workerId);
        entity.quantity = 1 + (int) ((cycle + workerId) % maxQuantityForLevel(level));
        entity.status = switch ((int) ((cycle + workerId) % 7)) {
            case 0 -> "ACTIVE";
            case 1 -> "REVIEW";
            case 2 -> "CHECKOUT";
            case 3 -> "HOLD";
            case 4 -> "ACTIVE";
            case 5 -> level == DemoScenarioLevel.HIGH ? "FLASH" : "ACTIVE";
            default -> level == DemoScenarioLevel.HIGH ? "BULK_EDIT" : "ABANDONED";
        };
        synchronized (viewLock) {
            cartViewStore.put(id, copy(entity));
        }
        cartRepository.save(entity);
    }

    private long mutateOrderWave(DemoScenarioLevel level, long cycle, int workerId, int orderBatchSize, int lineBurstSize) {
        long writes = 0;
        for (int index = 0; index < orderBatchSize; index++) {
            writes += mutateOrder(level, cycle + index, workerId, lineBurstSize);
        }
        long relatedWrites = Math.max(0L, writes - orderBatchSize);
        long actualWriteCount = writes + orderBatchSize;
        scenarioShapeCollector.recordWrite(
                "order-write-burst",
                orderBatchSize,
                relatedWrites,
                orderBatchSize,
                orderBatchSize + relatedWrites,
                actualWriteCount
        );
        return writes;
    }

    private long mutateOrder(DemoScenarioLevel level, long cycle, int workerId, int lineBurstSize) {
        long id = orderIdForScenario(level, cycle, workerId);
        long writes = mutateOrderLineBurst(level, id, cycle, workerId, lineBurstSize);
        DemoOrderEntity entity;
        synchronized (viewLock) {
            entity = copy(orderViewStore.getOrDefault(id, order(id, Math.max(2, lineBurstSize))));
        }
        entity.status = switch ((int) ((cycle + workerId) % 6)) {
            case 0 -> "PENDING";
            case 1 -> "PAID";
            case 2 -> "PACKING";
            case 3 -> "SHIPPED";
            case 4 -> "DELIVERED";
            default -> level == DemoScenarioLevel.HIGH ? "BULK_PICK" : "PAID";
        };
        synchronized (viewLock) {
            orderViewStore.put(id, copy(entity));
        }
        orderRepository.save(entity);
        return writes + 1;
    }

    private long mutateOrderLineWave(DemoScenarioLevel level, long cycle, int workerId, int orderBatchSize, int lineBurstSize) {
        long writes = 0;
        for (int index = 0; index < orderBatchSize; index++) {
            long orderId = orderIdForScenario(level, cycle + index, workerId);
            writes += mutateOrderLineBurst(level, orderId, cycle + index, workerId, lineBurstSize);
        }
        long actualWriteCount = writes + orderBatchSize;
        scenarioShapeCollector.recordWrite(
                "order-line-write-burst",
                orderBatchSize,
                writes,
                orderBatchSize,
                orderBatchSize + writes,
                actualWriteCount
        );
        return writes;
    }

    private long browseProductCatalog(long cycle, int workerId, boolean widerWindow) {
        int pageSize = widerWindow ? tuning.readerPageSize() : Math.max(8, tuning.readerPageSize() / 2);
        int pageCount = widerWindow ? 3 : 2;
        for (int index = 0; index < pageCount; index++) {
            productRepository.findPage(new PageWindow(pageNumber(cycle + index, workerId, tuning.productSeedCount(), pageSize), pageSize));
        }
        return pageCount;
    }

    private long readHotProducts(DemoScenarioLevel level, long cycle, int workerId, int requestedSize) {
        List<Long> ids = hotIds(
                cycle,
                workerId,
                tuning.productSeedCount(),
                effectiveHotProductReadSize(level, requestedSize),
                32
        );
        long loaded = 0;
        int chunkSize = effectiveHotProductChunkSize(level);
        int chunkCount = 0;
        for (int start = 0; start < ids.size(); start += chunkSize) {
            int end = Math.min(ids.size(), start + chunkSize);
            chunkCount++;
            loaded += productRepository.findAll(ids.subList(start, end)).size();
        }
        scenarioShapeCollector.recordRead("hot-product-read", loaded, 0L, chunkCount, loaded);
        return Math.max(1, loaded);
    }

    private long readCustomerProfiles(long cycle, int workerId, int size) {
        customerRepository.findAll(hotIds(cycle, workerId, tuning.customerSeedCount(), size, 40));
        return 1;
    }

    private long readAllCustomersSnapshot() {
        int pageSize = tuning.bulkCustomerReadPageSize();
        int pages = (int) Math.ceil(tuning.customerSeedCount() / (double) pageSize);
        for (int page = 0; page < pages; page++) {
            customerRepository.findPage(new PageWindow(page, pageSize));
        }
        return pages;
    }

    private long readCartWindow(long cycle, int workerId) {
        int pageSize = Math.max(18, tuning.readerPageSize() / 2);
        int pageCount = 2;
        for (int index = 0; index < pageCount; index++) {
            cartRepository.findPage(new PageWindow(pageNumber(cycle + index, workerId, tuning.cartSeedCount(), pageSize), pageSize));
        }
        return pageCount;
    }

    private long readOrderWindow(long cycle, int workerId, boolean widerWindow) {
        int pageSize = widerWindow ? Math.max(12, tuning.readerPageSize() / 2) : Math.max(8, tuning.readerPageSize() / 3);
        int pageCount = widerWindow ? 2 : 1;
        for (int index = 0; index < pageCount; index++) {
            orderRepository.findPage(new PageWindow(pageNumber(cycle + index, workerId, tuning.orderSeedCount(), pageSize), pageSize));
        }
        return pageCount;
    }

    private long readTopCustomerOrders() {
        long customerId = topCustomerIdByOrders();
        DemoOrderEntityCacheBinding.topCustomerOrders(orderRepository, customerId, tuning.topCustomerOrderReadLimit());
        return 1;
    }

    private long readHighLineOrders() {
        DemoOrderEntityCacheBinding.highLineOrders(
                orderRepository,
                tuning.highLineOrderThreshold(),
                tuning.highLineOrderReadLimit()
        );
        return 1;
    }

    private long readOrderWindowWithLines(long cycle, int workerId, boolean widerWindow) {
        int pageSize = widerWindow ? Math.max(18, tuning.readerPageSize() / 2) : Math.max(12, tuning.readerPageSize() / 3);
        int pageCount = widerWindow ? 2 : 1;
        long loadedRows = 0;
        EntityRepository<DemoOrderEntity, Long> repository = orderRepositoryWithLimitedLines(widerWindow);
        for (int index = 0; index < pageCount; index++) {
            List<DemoOrderEntity> orders = repository.findPage(
                    new PageWindow(pageNumber(cycle + index, workerId, tuning.orderSeedCount(), pageSize), pageSize)
            );
            loadedRows += orders.size() + totalFetchedOrderLines(orders);
        }
        return Math.max(1, loadedRows);
    }

    private long readTopCustomerOrdersWithLines(DemoScenarioLevel level) {
        long customerId = topCustomerIdByOrders();
        List<DemoOrderReadModelPatterns.OrderSummaryReadModel> candidates = orderReadModelPatterns.findCustomerOrderSummaries(
                customerId,
                effectiveTopCustomerCandidateLimit(level)
        );
        boolean usedEntityFallback = false;
        boolean usedViewFallback = false;
        if (candidates.isEmpty()) {
            List<DemoOrderEntity> entityCandidates = DemoOrderEntityCacheBinding.topCustomerOrders(
                    orderRepository,
                    customerId,
                    effectiveTopCustomerCandidateLimit(level)
            );
            if (entityCandidates.isEmpty()) {
                entityCandidates = topCustomerOrderCandidatesFromView(customerId, effectiveTopCustomerCandidateLimit(level));
                usedViewFallback = true;
            } else {
                usedEntityFallback = true;
            }
            candidates = entityCandidates.stream()
                    .map(order -> new DemoOrderReadModelPatterns.OrderSummaryReadModel(
                            order.id,
                            order.customerId,
                            order.status,
                            order.lineItemCount == null ? 0 : order.lineItemCount,
                            order.totalAmount == null ? 0.0 : order.totalAmount
                    ))
                    .toList();
        }
        List<Long> summaryOrderIds = selectLeadingOrderIds(
                candidates,
                effectiveTopCustomerSummaryLimit(level),
                DemoOrderReadModelPatterns.OrderSummaryReadModel::id
        );
        if (summaryOrderIds.isEmpty()) {
            scenarioShapeCollector.recordRead("top-customer-orders-read", 0L, 0L, usedViewFallback ? 3L : usedEntityFallback ? 2L : 1L, 0L);
            return 1;
        }
        long detailOrderId = summaryOrderIds.get(0);
        long relatedCount = orderReadModelPatterns.loadPreviewLinesForOrder(
                detailOrderId,
                effectiveTopCustomerPreviewLineLimit(level)
        ).size();
        long objectCount = summaryOrderIds.size() + relatedCount;
        scenarioShapeCollector.recordRead("top-customer-orders-read", summaryOrderIds.size(), relatedCount, usedViewFallback ? 4L : usedEntityFallback ? 3L : 2L, objectCount);
        return Math.max(1, objectCount);
    }

    private long readHighLineOrdersWithLines(DemoScenarioLevel level) {
        List<DemoOrderReadModelPatterns.HighLineOrderSummaryReadModel> candidates = orderReadModelPatterns.findHighLineCompactSummaries(
                tuning.highLineOrderThreshold(),
                effectiveHighLineCandidateLimit(level)
        );
        boolean usedEntityFallback = false;
        if (candidates.isEmpty()) {
            List<DemoOrderEntity> entityCandidates = DemoOrderEntityCacheBinding.highLineOrders(
                    orderRepository,
                    tuning.highLineOrderThreshold(),
                    effectiveHighLineCandidateLimit(level)
            );
            if (!entityCandidates.isEmpty()) {
                usedEntityFallback = true;
                candidates = entityCandidates.stream()
                        .map(order -> new DemoOrderReadModelPatterns.HighLineOrderSummaryReadModel(
                                order.id,
                                order.status,
                                order.lineItemCount == null ? 0 : order.lineItemCount,
                                order.totalAmount == null ? 0.0 : order.totalAmount,
                                computeHighLineRankScore(
                                        order.lineItemCount == null ? 0 : order.lineItemCount,
                                        order.totalAmount == null ? 0.0 : order.totalAmount
                                )
                        ))
                        .toList();
            }
        }
        List<Long> summaryOrderIds = selectLeadingOrderIds(
                candidates,
                effectiveHighLineSummaryLimit(level),
                DemoOrderReadModelPatterns.HighLineOrderSummaryReadModel::id
        );
        if (summaryOrderIds.isEmpty()) {
            scenarioShapeCollector.recordRead("high-line-orders-read", 0L, 0L, usedEntityFallback ? 2L : 1L, 0L);
            return 1;
        }
        long detailOrderId = summaryOrderIds.get(0);
        long relatedCount = orderReadModelPatterns.loadHighLinePreviewLinesForOrder(
                detailOrderId,
                effectiveHighLinePreviewLineLimit(level)
        ).size();
        long objectCount = summaryOrderIds.size() + relatedCount;
        scenarioShapeCollector.recordRead("high-line-orders-read", summaryOrderIds.size(), relatedCount, usedEntityFallback ? 3L : 2L, objectCount);
        return Math.max(1, objectCount);
    }

    private long readAllCustomersSnapshotFromPostgres(int pageCount, int pageSize) {
        int totalRows = 0;
        try (Connection connection = postgresDataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id, username, tier, status " +
                             "FROM cachedb_demo_customers " +
                             "ORDER BY id " +
                             "LIMIT ? OFFSET ?"
             )) {
            int totalPages = Math.max(1, (int) Math.ceil(tuning.customerSeedCount() / (double) pageSize));
            int startPage = (int) ((readSequence.get() + pageCount) % totalPages);
            for (int page = 0; page < pageCount; page++) {
                statement.setInt(1, pageSize);
                statement.setInt(2, (((startPage + page) % totalPages) * pageSize));
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        totalRows++;
                    }
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("PostgreSQL customer snapshot read failed", exception);
        }
        return Math.max(1, totalRows);
    }

    private long readTopCustomerOrdersWithLinesFromPostgres(DemoScenarioLevel level) {
        try (Connection connection = postgresDataSource.getConnection()) {
            long customerId = topCustomerIdByOrdersFromPostgres(connection);
            List<DemoOrderEntity> candidates = loadTopCustomerOrderCandidates(
                    connection,
                    "SELECT id, line_item_count, total_amount FROM cachedb_demo_orders WHERE customer_id = ? ORDER BY line_item_count DESC, total_amount DESC, id ASC LIMIT ?",
                    customerId,
                    effectiveTopCustomerCandidateLimit(level)
            );
            List<Long> summaryOrderIds = selectLeadingOrderIds(
                    candidates,
                    effectiveTopCustomerSummaryLimit(level),
                    order -> order == null ? null : order.id
            );
            if (summaryOrderIds.isEmpty()) {
                return 1;
            }
            long lineCount = loadOrderLinePreviewCount(connection, summaryOrderIds.get(0), effectiveTopCustomerPreviewLineLimit(level));
            return Math.max(1, summaryOrderIds.size() + lineCount);
        } catch (SQLException exception) {
            throw new IllegalStateException("PostgreSQL top customer orders read failed", exception);
        }
    }

    private long readHighLineOrdersWithLinesFromPostgres(DemoScenarioLevel level) {
        try (Connection connection = postgresDataSource.getConnection()) {
            List<Long> summaryOrderIds = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id FROM cachedb_demo_orders " +
                            "WHERE line_item_count >= ? " +
                            "ORDER BY line_item_count DESC, total_amount DESC, id ASC " +
                            "LIMIT ?"
            )) {
                statement.setInt(1, tuning.highLineOrderThreshold());
                statement.setInt(2, effectiveHighLineSummaryLimit(level));
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next() && summaryOrderIds.size() < effectiveHighLineSummaryLimit(level)) {
                        summaryOrderIds.add(resultSet.getLong(1));
                    }
                }
            }
            if (summaryOrderIds.isEmpty()) {
                return 1;
            }
            long lineCount = loadOrderLinePreviewCount(connection, summaryOrderIds.get(0), effectiveHighLinePreviewLineLimit(level));
            return Math.max(1, summaryOrderIds.size() + lineCount);
        } catch (SQLException exception) {
            throw new IllegalStateException("PostgreSQL high-line orders read failed", exception);
        }
    }

    private long topCustomerIdByOrdersFromPostgres(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT customer_id " +
                        "FROM cachedb_demo_orders " +
                        "GROUP BY customer_id " +
                        "ORDER BY COUNT(*) DESC, customer_id ASC " +
                        "LIMIT 1"
        ); ResultSet resultSet = statement.executeQuery()) {
            if (resultSet.next()) {
                return resultSet.getLong(1);
            }
            return 1L;
        }
    }

    private List<Long> loadOrderIds(Connection connection, String sql, long customerId, int limit) throws SQLException {
        ArrayList<Long> orderIds = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, customerId);
            statement.setInt(2, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    orderIds.add(resultSet.getLong(1));
                }
            }
        }
        return orderIds;
    }

    private List<DemoOrderEntity> loadTopCustomerOrderCandidates(Connection connection, String sql, long customerId, int limit)
            throws SQLException {
        ArrayList<DemoOrderEntity> candidates = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, customerId);
            statement.setInt(2, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    DemoOrderEntity entity = new DemoOrderEntity();
                    entity.id = resultSet.getLong(1);
                    entity.lineItemCount = resultSet.getInt(2);
                    entity.totalAmount = resultSet.getDouble(3);
                    candidates.add(entity);
                }
            }
        }
        return List.copyOf(candidates);
    }

    private long loadOrderLineCount(Connection connection, List<Long> orderIds) throws SQLException {
        if (orderIds.isEmpty()) {
            return 0;
        }
        StringBuilder placeholders = new StringBuilder();
        for (int index = 0; index < orderIds.size(); index++) {
            if (index > 0) {
                placeholders.append(',');
            }
            placeholders.append('?');
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT order_id, id, product_id, line_number, quantity, unit_price, line_total, status " +
                        "FROM cachedb_demo_order_lines " +
                        "WHERE order_id IN (" + placeholders + ") " +
                        "ORDER BY order_id ASC, line_number ASC"
        )) {
            int parameterIndex = 1;
            for (Long orderId : orderIds) {
                statement.setLong(parameterIndex++, orderId);
            }
            long lineCount = 0;
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    lineCount++;
                }
            }
            return lineCount;
        }
    }

    private long loadOrderLinePreviewCount(Connection connection, long orderId, int previewLimit) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM cachedb_demo_order_lines " +
                        "WHERE order_id = ? " +
                        "ORDER BY line_number ASC " +
                        "LIMIT ?"
        )) {
            statement.setLong(1, orderId);
            statement.setInt(2, Math.max(1, previewLimit));
            long count = 0;
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    count++;
                }
            }
            return count;
        }
    }

    private void persistSeedData(long generation, DemoSeedPayload payload) {
        if (generation != maintenanceGeneration.get()) {
            return;
        }
        if (seedBootstrapper != null) {
            seedBootstrapper.seed(payload, this::setMaintenanceStage);
            return;
        }
        setMaintenanceStage("Musteriler yaziliyor");
        persistEntitiesParallel(generation, payload.customers(), customerRepository, 400);
        setMaintenanceStage("Urunler yaziliyor");
        persistEntitiesParallel(generation, payload.products(), productRepository, 400);
        setMaintenanceStage("Sepetler yaziliyor");
        persistEntitiesParallel(generation, payload.carts(), cartRepository, 600);
        setMaintenanceStage("Siparisler yaziliyor");
        persistEntitiesParallel(generation, payload.orders(), orderRepository, 400);
        setMaintenanceStage("Siparis satirlari yaziliyor");
        persistEntitiesParallel(generation, payload.orderLines(), orderLineRepository, 2_500);
    }

    private void deleteSeedData(long generation) {
        setMaintenanceStage("Tum demo verisi temizleniyor");
        deleteEntitiesParallel(generation, tuning.customerSeedCount(), customerRepository, 400);
        deleteEntitiesParallel(generation, tuning.productSeedCount(), productRepository, 400);
        deleteEntitiesParallel(generation, tuning.cartSeedCount(), cartRepository, 600);
        deleteEntitiesParallel(generation, tuning.orderSeedCount(), orderRepository, 400);
        deleteEntitiesParallel(generation, tuning.orderLineSeedCount(), orderLineRepository, 2_500);
    }

    private <T> List<T> snapshotItems(Map<Long, T> source) {
        synchronized (viewLock) {
            return new ArrayList<>(source.values());
        }
    }

    private <T> void persistEntitiesParallel(long generation, List<T> items, EntityRepository<T, Long> repository, int chunkSize) {
        if (generation != maintenanceGeneration.get() || items.isEmpty()) {
            return;
        }
        if (items.size() <= chunkSize) {
            persistEntityChunk(generation, items, repository);
            return;
        }
        ArrayList<Callable<Void>> tasks = new ArrayList<>();
        for (int start = 0; start < items.size(); start += chunkSize) {
            int end = Math.min(items.size(), start + chunkSize);
            List<T> chunk = List.copyOf(items.subList(start, end));
            tasks.add(() -> {
                persistEntityChunk(generation, chunk, repository);
                return null;
            });
        }
        invokeMaintenanceTasks(tasks);
    }

    private <T> void persistEntityChunk(long generation, List<T> items, EntityRepository<T, Long> repository) {
        for (T item : items) {
            if (generation != maintenanceGeneration.get()) {
                return;
            }
            try {
                repository.save(item);
            } catch (RuntimeException exception) {
                recordError(exception);
                return;
            }
        }
    }

    private void deleteEntitiesParallel(long generation, long count, EntityRepository<?, Long> repository, int chunkSize) {
        if (generation != maintenanceGeneration.get() || count <= 0) {
            return;
        }
        if (count <= chunkSize) {
            deleteEntityRange(generation, 1, count, repository);
            return;
        }
        ArrayList<Callable<Void>> tasks = new ArrayList<>();
        for (long start = 1; start <= count; start += chunkSize) {
            long rangeStart = start;
            long rangeEnd = Math.min(count, start + chunkSize - 1);
            tasks.add(() -> {
                deleteEntityRange(generation, rangeStart, rangeEnd, repository);
                return null;
            });
        }
        invokeMaintenanceTasks(tasks);
    }

    private void deleteEntityRange(long generation, long startInclusive, long endInclusive, EntityRepository<?, Long> repository) {
        for (long id = startInclusive; id <= endInclusive; id++) {
            if (generation != maintenanceGeneration.get()) {
                return;
            }
            try {
                repository.deleteById(id);
            } catch (RuntimeException exception) {
                recordError(exception);
                return;
            }
        }
    }

    private DemoCustomerEntity customer(long id) {
        DemoCustomerEntity entity = new DemoCustomerEntity();
        entity.id = id;
        entity.username = "customer-" + id;
        entity.tier = switch ((int) (id % 4)) {
            case 0 -> "BRONZE";
            case 1 -> "SILVER";
            case 2 -> "GOLD";
            default -> "PLATINUM";
        };
        entity.status = switch ((int) (id % 5)) {
            case 0 -> "ACTIVE";
            case 1 -> "LOYAL";
            case 2 -> "REVIEW";
            case 3 -> "VIP";
            default -> "ONBOARDING";
        };
        return entity;
    }

    private DemoProductEntity product(long id) {
        DemoProductEntity entity = new DemoProductEntity();
        entity.id = id;
        entity.sku = String.format(Locale.ROOT, "SKU-%04d", id);
        entity.category = switch ((int) (id % 6)) {
            case 0 -> "electronics";
            case 1 -> "home";
            case 2 -> "fashion";
            case 3 -> "grocery";
            case 4 -> "beauty";
            default -> "sports";
        };
        entity.price = 24.0 + (id % 180) + ((id % 7) * 0.45);
        entity.stock = 40 + (int) (id % 65);
        return entity;
    }

    private DemoCartEntity cart(long id) {
        DemoCartEntity entity = new DemoCartEntity();
        entity.id = id;
        entity.customerId = customerId(id);
        entity.productId = productId(id);
        entity.quantity = 1 + (int) (id % 6);
        entity.status = switch ((int) (id % 5)) {
            case 0 -> "ACTIVE";
            case 1 -> "REVIEW";
            case 2 -> "CHECKOUT";
            case 3 -> "HOLD";
            default -> "ABANDONED";
        };
        return entity;
    }

    private DemoOrderEntity order(long id, int lineItemCount) {
        DemoOrderEntity entity = new DemoOrderEntity();
        entity.id = id;
        entity.customerId = customerIdForSeed(id);
        entity.productId = productIdForSeed(id, 1);
        entity.totalAmount = 0.0;
        entity.lineItemCount = lineItemCount;
        entity.status = switch ((int) (id % 5)) {
            case 0 -> "PENDING";
            case 1 -> "PAID";
            case 2 -> "PACKING";
            case 3 -> "SHIPPED";
            default -> "DELIVERED";
        };
        return entity;
    }

    private DemoOrderLineEntity orderLine(long id, long orderId, int lineNumber) {
        DemoOrderLineEntity entity = new DemoOrderLineEntity();
        entity.id = id;
        entity.orderId = orderId;
        entity.productId = productIdForSeed(orderId, lineNumber);
        entity.lineNumber = lineNumber;
        entity.sku = String.format(Locale.ROOT, "SKU-%04d", entity.productId);
        entity.quantity = 1 + (int) ((orderId + lineNumber) % 6);
        entity.unitPrice = 12.0 + ((orderId + lineNumber) % 160) + ((lineNumber % 7) * 0.55);
        entity.lineTotal = roundCurrency(entity.unitPrice * entity.quantity);
        entity.status = switch ((lineNumber + (int) (orderId % 5)) % 5) {
            case 0 -> "ALLOCATED";
            case 1 -> "PICKING";
            case 2 -> "PACKED";
            case 3 -> "READY";
            default -> "OPEN";
        };
        return entity;
    }

    private int scenarioSlot(DemoScenarioLevel level, long cycle, int workerId, int modulo) {
        long salt = switch (level) {
            case LOW -> 7L;
            case MEDIUM -> 13L;
            case HIGH -> 29L;
        };
        return (int) ((cycle + (workerId * salt)) % modulo);
    }

    private int pageNumber(long cycle, int workerId, int totalCount, int pageSize) {
        int pageCount = Math.max(1, (int) Math.ceil(totalCount / (double) Math.max(1, pageSize)));
        int window = Math.max(1, Math.min(pageCount, tuning.readerPageWindowVariants()));
        return (int) ((cycle + workerId) % window);
    }

    private List<Long> hotIds(long cycle, int workerId, int totalCount, int batchSize, int hotSetSize) {
        int actualBatchSize = Math.max(1, batchSize);
        int scope = Math.max(actualBatchSize, Math.min(totalCount, hotSetSize));
        List<Long> ids = new ArrayList<>(actualBatchSize);
        long start = (cycle + (workerId * 5L)) % scope;
        for (int index = 0; index < actualBatchSize; index++) {
            ids.add(1 + ((start + index) % scope));
        }
        return ids;
    }

    private long customerIdForScenario(DemoScenarioLevel level, long cycle, int workerId) {
        return scopedId(level, cycle, workerId, tuning.customerSeedCount(), 28, 96);
    }

    private long productIdForScenario(DemoScenarioLevel level, long cycle, int workerId) {
        return scopedId(level, cycle, workerId, tuning.productSeedCount(), 36, 120);
    }

    private long cartIdForScenario(DemoScenarioLevel level, long cycle, int workerId) {
        return scopedId(level, cycle, workerId, tuning.cartSeedCount(), 24, 72);
    }

    private long orderIdForScenario(DemoScenarioLevel level, long cycle, int workerId) {
        return scopedId(level, cycle, workerId, tuning.orderSeedCount(), 64, 220);
    }

    private long scopedId(DemoScenarioLevel level, long cycle, int workerId, int totalCount, int hotSetSize, int warmSetSize) {
        long sequence = cycle + (workerId * 11L);
        int hot = Math.max(1, Math.min(totalCount, hotSetSize));
        int warm = Math.max(hot, Math.min(totalCount, warmSetSize));
        if (level == DemoScenarioLevel.HIGH && (sequence % 10) < 7) {
            return 1 + (sequence % hot);
        }
        if (level == DemoScenarioLevel.MEDIUM && (sequence % 10) < 5) {
            return 1 + (sequence % warm);
        }
        return 1 + (sequence % totalCount);
    }

    private long customerId(long cycle) {
        return 1 + (cycle % tuning.customerSeedCount());
    }

    private long productId(long cycle) {
        return 1 + (cycle % tuning.productSeedCount());
    }

    private long cartId(long cycle) {
        return 1 + (cycle % tuning.cartSeedCount());
    }

    private long orderId(long cycle) {
        return 1 + (cycle % tuning.orderSeedCount());
    }

    private long topCustomerIdByOrders() {
        synchronized (viewLock) {
            long selectedCustomer = 1L;
            int highestOrderCount = -1;
            Map<Long, Integer> counts = new LinkedHashMap<>();
            for (DemoOrderEntity entity : orderViewStore.values()) {
                counts.merge(entity.customerId, 1, Integer::sum);
            }
            for (Map.Entry<Long, Integer> entry : counts.entrySet()) {
                if (entry.getValue() > highestOrderCount) {
                    selectedCustomer = entry.getKey();
                    highestOrderCount = entry.getValue();
                }
            }
            return selectedCustomer;
        }
    }

    private int maxQuantityForLevel(DemoScenarioLevel level) {
        return switch (level) {
            case LOW -> Math.max(8, tuning.maxCartQuantity() / 4);
            case MEDIUM -> Math.max(16, tuning.maxCartQuantity() / 2);
            case HIGH -> tuning.maxCartQuantity();
        };
    }

    private int maxOrderLineQuantityForLevel(DemoScenarioLevel level) {
        return switch (level) {
            case LOW -> Math.max(4, tuning.maxOrderLineQuantity() / 3);
            case MEDIUM -> Math.max(8, tuning.maxOrderLineQuantity() / 2);
            case HIGH -> tuning.maxOrderLineQuantity();
        };
    }

    private void populateOrderAndLineSeedData() {
        List<Integer> lineCounts = buildSeedOrderLineCounts();
        long nextOrderLineId = 1L;
        for (long orderId = 1; orderId <= tuning.orderSeedCount(); orderId++) {
            int lineCount = lineCounts.get((int) orderId - 1);
            DemoOrderEntity order = order(orderId, lineCount);
            ArrayList<Long> lineIds = new ArrayList<>(lineCount);
            double totalAmount = 0.0;
            long featuredProductId = productIdForSeed(orderId, 1);
            for (int lineNumber = 1; lineNumber <= lineCount; lineNumber++) {
                DemoOrderLineEntity line = orderLine(nextOrderLineId, orderId, lineNumber);
                orderLineViewStore.put(nextOrderLineId, copy(line));
                lineIds.add(nextOrderLineId);
                featuredProductId = line.productId;
                totalAmount += line.lineTotal;
                nextOrderLineId++;
            }
            order.productId = featuredProductId;
            order.totalAmount = roundCurrency(totalAmount);
            orderViewStore.put(orderId, copy(order));
            orderLineIdsByOrder.put(orderId, List.copyOf(lineIds));
        }
    }

    private List<Integer> buildSeedOrderLineCounts() {
        int orderCount = tuning.orderSeedCount();
        int totalLines = Math.max(orderCount * 3, tuning.orderLineSeedCount());
        int minimumPerOrder = 3;
        int remaining = Math.max(0, totalLines - (orderCount * minimumPerOrder));
        ArrayList<Double> weights = new ArrayList<>(orderCount);
        double totalWeight = 0.0;
        for (int index = 0; index < orderCount; index++) {
            long orderId = index + 1L;
            double weight = 1.0 + (orderId % 5);
            if (orderId % 127 == 0) {
                weight += 160.0;
            } else if (orderId % 31 == 0) {
                weight += 56.0;
            } else if (orderId % 9 == 0) {
                weight += 14.0;
            }
            weights.add(weight);
            totalWeight += weight;
        }

        ArrayList<Integer> counts = new ArrayList<>(orderCount);
        ArrayList<Integer> priority = new ArrayList<>(orderCount);
        int allocated = 0;
        for (int index = 0; index < orderCount; index++) {
            int extra = (int) Math.floor((remaining * weights.get(index)) / totalWeight);
            counts.add(minimumPerOrder + extra);
            allocated += extra;
            priority.add(index);
        }
        priority.sort(Comparator.comparingDouble((Integer idx) -> weights.get(idx)).reversed());
        int leftover = remaining - allocated;
        for (int index = 0; index < leftover; index++) {
            int target = priority.get(index % priority.size());
            counts.set(target, counts.get(target) + 1);
        }
        return counts;
    }

    private long mutateOrderLineBurst(DemoScenarioLevel level, long orderId, long cycle, int workerId, int requestedLineBurstSize) {
        List<Long> lineIds;
        synchronized (viewLock) {
            lineIds = orderLineIdsByOrder.getOrDefault(orderId, List.of());
        }
        if (lineIds.isEmpty()) {
            return 0;
        }
        int lineBurstSize = Math.max(1, Math.min(lineIds.size(), requestedLineBurstSize));
        int start = (int) ((cycle + workerId) % lineIds.size());
        long writes = 0;
        for (int offset = 0; offset < lineBurstSize; offset++) {
            long lineId = lineIds.get((start + offset) % lineIds.size());
            DemoOrderLineEntity line;
            synchronized (viewLock) {
                line = copy(orderLineViewStore.get(lineId));
            }
            if (line == null) {
                continue;
            }
            line.quantity = 1 + (int) ((cycle + workerId + offset) % maxOrderLineQuantityForLevel(level));
            line.unitPrice = roundCurrency(line.unitPrice + ((offset % 5) * 0.35) + (level == DemoScenarioLevel.HIGH ? 1.4 : 0.55));
            line.lineTotal = roundCurrency(line.unitPrice * line.quantity);
            line.status = switch ((int) ((cycle + workerId + offset) % 6)) {
                case 0 -> "OPEN";
                case 1 -> "ALLOCATED";
                case 2 -> "PICKING";
                case 3 -> "PACKED";
                case 4 -> "READY";
                default -> level == DemoScenarioLevel.HIGH ? "BULK_PICK" : "OPEN";
            };
            synchronized (viewLock) {
                orderLineViewStore.put(lineId, copy(line));
            }
            orderLineRepository.save(line);
            writes++;
        }
        recalculateOrderAggregate(orderId, level);
        return writes;
    }

    private void recalculateOrderAggregate(long orderId, DemoScenarioLevel level) {
        DemoOrderEntity order;
        List<Long> lineIds;
        synchronized (viewLock) {
            order = copy(orderViewStore.get(orderId));
            lineIds = orderLineIdsByOrder.getOrDefault(orderId, List.of());
        }
        if (order == null) {
            return;
        }
        double totalAmount = 0.0;
        long featuredProductId = order.productId == null ? 1L : order.productId;
        synchronized (viewLock) {
            for (Long lineId : lineIds) {
                DemoOrderLineEntity line = orderLineViewStore.get(lineId);
                if (line != null) {
                    totalAmount += line.lineTotal;
                    featuredProductId = line.productId;
                }
            }
        }
        order.productId = featuredProductId;
        order.lineItemCount = lineIds.size();
        order.totalAmount = roundCurrency(totalAmount);
        if (level == DemoScenarioLevel.HIGH && order.status != null && !"DELIVERED".equals(order.status)) {
            order.status = "BULK_PICK";
        }
        synchronized (viewLock) {
            orderViewStore.put(orderId, copy(order));
        }
        orderRepository.save(order);
    }

    private long totalFetchedOrderLines(List<DemoOrderEntity> orders) {
        long count = 0;
        for (DemoOrderEntity order : orders) {
            count += order.orderLines == null ? 0 : order.orderLines.size();
        }
        return count;
    }

    private EntityRepository<DemoOrderEntity, Long> orderRepositoryWithLimitedLines(boolean widerWindow) {
        int relationLimit = widerWindow ? 12 : 8;
        return orderRepository.withFetchPlan(FetchPlan.of("orderLines").withRelationLimit("orderLines", relationLimit));
    }

    private int effectiveHotProductReadSize(DemoScenarioLevel level, int requestedSize) {
        return switch (level) {
            case LOW -> Math.min(requestedSize, 32);
            case MEDIUM -> Math.min(requestedSize, 96);
            case HIGH -> Math.min(requestedSize, 40);
        };
    }

    private int effectiveHotProductChunkSize(DemoScenarioLevel level) {
        return switch (level) {
            case LOW -> 16;
            case MEDIUM -> 24;
            case HIGH -> 10;
        };
    }

    private int effectiveHighLineCandidateLimit(DemoScenarioLevel level) {
        return switch (level) {
            case LOW -> Math.min(tuning.highLineOrderReadLimit(), 14);
            case MEDIUM -> Math.min(tuning.highLineOrderReadLimit(), 10);
            case HIGH -> Math.min(tuning.highLineOrderReadLimit(), 14);
        };
    }

    private int effectiveTopCustomerCandidateLimit(DemoScenarioLevel level) {
        return switch (level) {
            case LOW -> Math.min(tuning.topCustomerOrderReadLimit(), 72);
            case MEDIUM -> Math.min(tuning.topCustomerOrderReadLimit(), 64);
            case HIGH -> Math.min(tuning.topCustomerOrderReadLimit(), 84);
        };
    }

    private int effectiveTopCustomerSummaryLimit(DemoScenarioLevel level) {
        return switch (level) {
            case LOW -> 10;
            case MEDIUM -> 8;
            case HIGH -> 10;
        };
    }

    private int effectiveTopCustomerPreviewLineLimit(DemoScenarioLevel level) {
        return switch (level) {
            case LOW -> 8;
            case MEDIUM -> 6;
            case HIGH -> 8;
        };
    }

    private int effectiveHighLineSummaryLimit(DemoScenarioLevel level) {
        return switch (level) {
            case LOW -> 6;
            case MEDIUM -> 5;
            case HIGH -> 6;
        };
    }

    private int effectiveHighLinePreviewLineLimit(DemoScenarioLevel level) {
        return switch (level) {
            case LOW -> 6;
            case MEDIUM -> 6;
            case HIGH -> 8;
        };
    }

    private <T> List<Long> selectLeadingOrderIds(
            List<T> candidates,
            int maxOrders,
            java.util.function.Function<T, Long> idAccessor
    ) {
        ArrayList<Long> selected = new ArrayList<>(Math.min(maxOrders, candidates.size()));
        for (T candidate : candidates) {
            Long id = candidate == null ? null : idAccessor.apply(candidate);
            if (id == null || selected.size() >= maxOrders) {
                continue;
            }
            selected.add(id);
        }
        return List.copyOf(selected);
    }

    private List<DemoOrderEntity> topCustomerOrderCandidatesFromView(long customerId, int candidateLimit) {
        synchronized (viewLock) {
            return orderViewStore.values().stream()
                    .filter(order -> order != null && order.id != null && customerId == (order.customerId == null ? -1L : order.customerId))
                    .sorted(
                            Comparator.comparing((DemoOrderEntity order) -> order.lineItemCount == null ? 0 : order.lineItemCount).reversed()
                                    .thenComparing(order -> order.totalAmount == null ? 0.0 : order.totalAmount, Comparator.reverseOrder())
                                    .thenComparing(order -> order.id == null ? Long.MAX_VALUE : order.id)
                    )
                    .limit(candidateLimit)
                    .map(order -> copy(order))
                    .toList();
        }
    }

    private long customerIdForSeed(long orderId) {
        long hotCustomerCount = Math.min(48, tuning.customerSeedCount());
        if ((orderId % 10) < 4) {
            return 1 + (orderId % hotCustomerCount);
        }
        return 1 + ((orderId * 13) % tuning.customerSeedCount());
    }

    private long productIdForSeed(long orderId, int lineNumber) {
        long hotProductCount = Math.min(96, tuning.productSeedCount());
        long sequence = orderId + (lineNumber * 7L);
        if ((orderId % 9) < 5) {
            return 1 + (sequence % hotProductCount);
        }
        return 1 + (sequence % tuning.productSeedCount());
    }

    private double roundCurrency(double amount) {
        return Math.round(amount * 100.0) / 100.0;
    }

    private void recordError(RuntimeException exception) {
        errorCount.incrementAndGet();
        lastError.set(exception.getClass().getSimpleName() + ": " + exception.getMessage());
        sleepQuietly(tuning.errorBackoffMillis());
    }

    private void clearViewStores() {
        synchronized (viewLock) {
            customerViewStore.clear();
            productViewStore.clear();
            cartViewStore.clear();
            orderViewStore.clear();
            orderLineViewStore.clear();
            orderLineIdsByOrder.clear();
        }
    }

    private void resetCounters() {
        readCount.set(0);
        writeCount.set(0);
        readSequence.set(0);
        writeSequence.set(0);
        errorCount.set(0);
        lastError.set("");
    }

    private void setMaintenanceStage(String label) {
        maintenanceStage.set(label == null || label.isBlank() ? "Hazir" : label);
        maintenanceStageUpdatedAtEpochMillis.set(System.currentTimeMillis());
    }

    private void abortPendingMaintenance() {
        maintenanceGeneration.incrementAndGet();
        Future<?> task = maintenanceTask;
        maintenanceTask = null;
        if (task != null && !task.isDone()) {
            task.cancel(true);
        }
    }

    private boolean isSuperseded(long expectedActionGeneration) {
        return actionGeneration.get() != expectedActionGeneration;
    }

    private synchronized boolean isMaintenanceInProgress() {
        return maintenanceTask != null && !maintenanceTask.isDone();
    }

    private void awaitMaintenanceTask() {
        Future<?> task = maintenanceTask;
        if (task == null || task.isDone()) {
            return;
        }
        try {
            task.get(10, TimeUnit.MINUTES);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Demo seed persistence was interrupted before scenario start", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("Demo seed persistence did not finish before scenario start", exception);
        } finally {
            if (task.isDone()) {
                maintenanceTask = null;
            }
        }
    }

    private void invokeMaintenanceTasks(List<Callable<Void>> tasks) {
        try {
            List<Future<Void>> futures = maintenanceExecutor.invokeAll(tasks);
            for (Future<Void> future : futures) {
                future.get();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Demo maintenance was interrupted", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("Demo maintenance task failed", exception);
        }
    }

    private int maintenanceParallelism() {
        return Math.max(12, Math.min(32, Runtime.getRuntime().availableProcessors() * 4));
    }

    private DemoLoadProfile effectiveProfile(DemoScenarioLevel level, Integer readerThreadsOverride, Integer writerThreadsOverride) {
        DemoLoadProfile baseProfile = tuning.profile(level);
        int readerThreads = readerThreadsOverride == null ? baseProfile.readerThreads() : Math.max(0, readerThreadsOverride);
        int writerThreads = writerThreadsOverride == null ? baseProfile.writerThreads() : Math.max(0, writerThreadsOverride);
        return new DemoLoadProfile(
                readerThreads,
                writerThreads,
                baseProfile.readerPauseMillis(),
                baseProfile.writerPauseMillis()
        );
    }

    private void sleepQuietly(long pauseMillis) {
        try {
            Thread.sleep(Math.max(1L, pauseMillis));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public synchronized void close() {
        stopScenario();
        maintenanceExecutor.shutdownNow();
        try {
            maintenanceExecutor.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }


    private int viewSize(Map<Long, ?> source) {
        synchronized (viewLock) {
            return source.size();
        }
    }

    private static <T> List<T> firstPage(Map<Long, T> source, int limit) {
        List<T> result = new ArrayList<>(Math.min(limit, source.size()));
        int count = 0;
        for (T item : source.values()) {
            result.add(item);
            count++;
            if (count >= limit) {
                break;
            }
        }
        return result;
    }

    private static DemoCustomerEntity copy(DemoCustomerEntity source) {
        DemoCustomerEntity entity = new DemoCustomerEntity();
        entity.id = source.id;
        entity.username = source.username;
        entity.tier = source.tier;
        entity.status = source.status;
        return entity;
    }

    private static DemoProductEntity copy(DemoProductEntity source) {
        DemoProductEntity entity = new DemoProductEntity();
        entity.id = source.id;
        entity.sku = source.sku;
        entity.category = source.category;
        entity.price = source.price;
        entity.stock = source.stock;
        return entity;
    }

    private static DemoCartEntity copy(DemoCartEntity source) {
        DemoCartEntity entity = new DemoCartEntity();
        entity.id = source.id;
        entity.customerId = source.customerId;
        entity.productId = source.productId;
        entity.quantity = source.quantity;
        entity.status = source.status;
        return entity;
    }

    private static DemoOrderEntity copy(DemoOrderEntity source) {
        DemoOrderEntity entity = new DemoOrderEntity();
        entity.id = source.id;
        entity.customerId = source.customerId;
        entity.productId = source.productId;
        entity.totalAmount = source.totalAmount;
        entity.lineItemCount = source.lineItemCount;
        entity.status = source.status;
        entity.orderLines = source.orderLines == null ? null : List.copyOf(source.orderLines);
        return entity;
    }

    private static DemoOrderLineEntity copy(DemoOrderLineEntity source) {
        if (source == null) {
            return null;
        }
        DemoOrderLineEntity entity = new DemoOrderLineEntity();
        entity.id = source.id;
        entity.orderId = source.orderId;
        entity.productId = source.productId;
        entity.lineNumber = source.lineNumber;
        entity.sku = source.sku;
        entity.quantity = source.quantity;
        entity.unitPrice = source.unitPrice;
        entity.lineTotal = source.lineTotal;
        entity.status = source.status;
        return entity;
    }

    private static double computeHighLineRankScore(int lineItemCount, double totalAmount) {
        long normalizedTotal = Math.max(0L, Math.round(totalAmount * 100.0d));
        long cappedTotal = Math.min(normalizedTotal, 999_999_999_999L);
        return (lineItemCount * 1_000_000_000_000d) + cappedTotal;
    }

    private static final class DemoThreadFactory implements ThreadFactory {
        private final AtomicLong sequence = new AtomicLong();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "cachedb-demo-load-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }

    private static final class DemoMaintenanceThreadFactory implements ThreadFactory {
        private final AtomicLong sequence = new AtomicLong();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "cachedb-demo-maintenance-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
