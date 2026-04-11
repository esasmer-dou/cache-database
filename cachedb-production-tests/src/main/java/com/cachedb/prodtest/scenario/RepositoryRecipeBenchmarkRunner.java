package com.reactor.cachedb.prodtest.scenario;

import com.reactor.cachedb.core.api.CacheSession;
import com.reactor.cachedb.core.api.EntityRepository;
import com.reactor.cachedb.core.api.ProjectionRepository;
import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.cache.PageWindow;
import com.reactor.cachedb.core.model.EntityCodec;
import com.reactor.cachedb.core.model.EntityMetadata;
import com.reactor.cachedb.core.plan.FetchPlan;
import com.reactor.cachedb.core.projection.EntityProjection;
import com.reactor.cachedb.core.query.QueryExplainPlan;
import com.reactor.cachedb.core.query.QueryFilter;
import com.reactor.cachedb.core.query.QuerySort;
import com.reactor.cachedb.core.query.QuerySpec;
import com.reactor.cachedb.prodtest.entity.EcomCustomerEntity;
import com.reactor.cachedb.prodtest.entity.EcomCustomerEntityCacheBinding;
import com.reactor.cachedb.prodtest.entity.EcomOrderEntity;
import com.reactor.cachedb.prodtest.entity.EcomOrderEntityCacheBinding;
import com.reactor.cachedb.prodtest.entity.EcomOrderReadModels;
import com.reactor.cachedb.prodtest.entity.GeneratedCacheModule;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class RepositoryRecipeBenchmarkRunner {

    private static final int DEFAULT_WARMUP_ITERATIONS =
            Integer.getInteger("cachedb.prod.recipeBenchmark.warmupIterations", 10_000);
    private static final int DEFAULT_MEASURED_ITERATIONS =
            Integer.getInteger("cachedb.prod.recipeBenchmark.measuredIterations", 50_000);
    private static final String DISCLAIMER =
            "This benchmark isolates CacheDB API-surface overhead on top of the same repository path. "
                    + "It compares JPA-style CacheDB ergonomics, generated entity bindings, and minimal repository usage. "
                    + "It does not benchmark external Hibernate/JPA runtime or end-to-end Redis/PostgreSQL I/O.";

    private static volatile long benchmarkBlackhole;

    public RepositoryRecipeBenchmarkReport run() {
        return run(DEFAULT_WARMUP_ITERATIONS, DEFAULT_MEASURED_ITERATIONS);
    }

    public RepositoryRecipeBenchmarkReport run(int warmupIterations, int measuredIterations) {
        RecipeFixture fixture = new RecipeFixture();
        ArrayList<RepositoryRecipeModeReport> modeReports = new ArrayList<>();
        for (RepositoryRecipeMode mode : RepositoryRecipeMode.values()) {
            modeReports.add(runMode(mode, fixture, warmupIterations, measuredIterations));
        }
        double minimalAverage = modeReports.stream()
                .filter(report -> report.mode() == RepositoryRecipeMode.MINIMAL_REPOSITORY)
                .mapToLong(RepositoryRecipeModeReport::averageLatencyNanos)
                .findFirst()
                .orElse(0L);
        List<RepositoryRecipeModeReport> normalizedReports = modeReports.stream()
                .map(report -> new RepositoryRecipeModeReport(
                        report.mode(),
                        report.label(),
                        report.positioning(),
                        report.measuredOperations(),
                        report.totalLatencyNanos(),
                        report.averageLatencyNanos(),
                        report.p95LatencyNanos(),
                        report.p99LatencyNanos(),
                        relativeOverheadPercent(report.averageLatencyNanos(), minimalAverage),
                        report.operationReports()
                ))
                .toList();
        String fastestAverageMode = normalizedReports.stream()
                .min(Comparator.comparingLong(RepositoryRecipeModeReport::averageLatencyNanos))
                .map(report -> report.mode().name())
                .orElse("");
        String fastestP95Mode = normalizedReports.stream()
                .min(Comparator.comparingLong(RepositoryRecipeModeReport::p95LatencyNanos))
                .map(report -> report.mode().name())
                .orElse("");
        double maxAverageSpreadPercent = normalizedReports.stream()
                .mapToDouble(report -> Math.abs(report.averageLatencyVsMinimalPercent()))
                .max()
                .orElse(0.0d);
        return new RepositoryRecipeBenchmarkReport(
                "repository-recipe-comparison",
                warmupIterations,
                measuredIterations,
                DISCLAIMER,
                fastestAverageMode,
                fastestP95Mode,
                maxAverageSpreadPercent,
                List.of(
                        "activeCustomers",
                        "customersPage",
                        "topCustomerOrdersSummary",
                        "promoteVipCustomer",
                        "deleteCustomer"
                ),
                normalizedReports
        );
    }

    private RepositoryRecipeModeReport runMode(
            RepositoryRecipeMode mode,
            RecipeFixture fixture,
            int warmupIterations,
            int measuredIterations
    ) {
        ModeExecutionContext context = ModeExecutionContext.forMode(mode, fixture);
        LinkedHashMap<String, OperationAccumulator> accumulators = new LinkedHashMap<>();
        for (OperationSpec operation : OperationSpec.values()) {
            accumulators.put(operation.operationName, new OperationAccumulator(operation, measuredIterations));
        }
        for (int iteration = 0; iteration < warmupIterations; iteration++) {
            executeIteration(mode, fixture, context, iteration, null);
        }
        for (int iteration = 0; iteration < measuredIterations; iteration++) {
            executeIteration(mode, fixture, context, iteration, accumulators);
        }
        ArrayList<RepositoryRecipeOperationReport> operationReports = new ArrayList<>(accumulators.size());
        ArrayList<Long> allSamples = new ArrayList<>(measuredIterations * accumulators.size());
        long totalLatencyNanos = 0L;
        long measuredOperations = 0L;
        for (OperationAccumulator accumulator : accumulators.values()) {
            operationReports.add(accumulator.toReport());
            totalLatencyNanos += accumulator.totalLatencyNanos;
            measuredOperations += accumulator.index;
            for (int index = 0; index < accumulator.index; index++) {
                allSamples.add(accumulator.samples[index]);
            }
        }
        long[] modeSamples = allSamples.stream().mapToLong(Long::longValue).toArray();
        return new RepositoryRecipeModeReport(
                mode,
                mode.label(),
                mode.positioning(),
                measuredOperations,
                totalLatencyNanos,
                measuredOperations == 0 ? 0L : totalLatencyNanos / measuredOperations,
                percentile(modeSamples, 0.95d),
                percentile(modeSamples, 0.99d),
                0.0d,
                List.copyOf(operationReports)
        );
    }

    private void executeIteration(
            RepositoryRecipeMode mode,
            RecipeFixture fixture,
            ModeExecutionContext context,
            int iteration,
            Map<String, OperationAccumulator> accumulators
    ) {
        for (OperationSpec operation : OperationSpec.values()) {
            long startedAt = System.nanoTime();
            Object result = invoke(mode, operation, fixture, context, iteration);
            long elapsed = System.nanoTime() - startedAt;
            consume(result);
            if (accumulators != null) {
                accumulators.get(operation.operationName).record(elapsed);
            }
        }
    }

    private Object invoke(
            RepositoryRecipeMode mode,
            OperationSpec operation,
            RecipeFixture fixture,
            ModeExecutionContext context,
            int iteration
    ) {
        return switch (mode) {
            case JPA_STYLE_DOMAIN_MODULE -> invokeDomainModule(operation, fixture, context, iteration);
            case GENERATED_ENTITY_BINDING -> invokeGeneratedBinding(operation, fixture, context, iteration);
            case MINIMAL_REPOSITORY -> invokeMinimalRepository(operation, fixture, context, iteration);
        };
    }

    private Object invokeDomainModule(
            OperationSpec operation,
            RecipeFixture fixture,
            ModeExecutionContext context,
            int iteration
    ) {
        GeneratedCacheModule.Scope domain = context.domainScope;
        return switch (operation) {
            case ACTIVE_CUSTOMERS -> domain.ecomCustomers().queries().activeCustomers(25);
            case CUSTOMERS_PAGE -> domain.ecomCustomers().pages().customersPage(0, 25);
            case TOP_CUSTOMER_ORDERS_SUMMARY -> EcomOrderEntityCacheBinding.topCustomerOrders(
                    domain.ecomOrders().projections().orderSummary(),
                    fixture.hotCustomerId,
                    12
            );
            case PROMOTE_VIP_CUSTOMER -> domain.ecomCustomers().commands().promoteVipCustomer(
                    fixture.promotedCustomerBaseId + iteration,
                    "LOYAL",
                    "SPRING-WAVE"
            );
            case DELETE_CUSTOMER -> {
                long customerId = fixture.deletedCustomerBaseId + iteration;
                domain.ecomCustomers().deletes().deleteCustomer(customerId);
                yield customerId;
            }
        };
    }

    private Object invokeGeneratedBinding(
            OperationSpec operation,
            RecipeFixture fixture,
            ModeExecutionContext context,
            int iteration
    ) {
        return switch (operation) {
            case ACTIVE_CUSTOMERS -> context.customerScope.queries().activeCustomers(25);
            case CUSTOMERS_PAGE -> context.customerScope.pages().customersPage(0, 25);
            case TOP_CUSTOMER_ORDERS_SUMMARY -> EcomOrderEntityCacheBinding.topCustomerOrders(
                    context.orderScope.projections().orderSummary(),
                    fixture.hotCustomerId,
                    12
            );
            case PROMOTE_VIP_CUSTOMER -> context.customerScope.commands().promoteVipCustomer(
                    fixture.promotedCustomerBaseId + iteration,
                    "LOYAL",
                    "SPRING-WAVE"
            );
            case DELETE_CUSTOMER -> {
                long customerId = fixture.deletedCustomerBaseId + iteration;
                context.customerScope.deletes().deleteCustomer(customerId);
                yield customerId;
            }
        };
    }

    private Object invokeMinimalRepository(
            OperationSpec operation,
            RecipeFixture fixture,
            ModeExecutionContext context,
            int iteration
    ) {
        return switch (operation) {
            case ACTIVE_CUSTOMERS -> context.minimalCustomerRepository.query(
                    QuerySpec.where(QueryFilter.eq("status", "ACTIVE"))
                            .orderBy(QuerySort.asc("tier"), QuerySort.asc("segment"), QuerySort.asc("id"))
                            .limitTo(25)
            );
            case CUSTOMERS_PAGE -> context.minimalCustomerRepository.findPage(new PageWindow(0, 25));
            case TOP_CUSTOMER_ORDERS_SUMMARY -> context.minimalOrderSummaryRepository.query(
                    QuerySpec.where(QueryFilter.eq("customer_id", fixture.hotCustomerId))
                            .orderBy(QuerySort.desc("total_amount"), QuerySort.desc("quantity"), QuerySort.asc("id"))
                            .limitTo(12)
            );
            case PROMOTE_VIP_CUSTOMER -> {
                EcomCustomerEntity entity = new EcomCustomerEntity();
                entity.id = fixture.promotedCustomerBaseId + iteration;
                entity.status = "ACTIVE";
                entity.segment = "LOYAL";
                entity.tier = "VIP";
                entity.lastCampaignCode = "SPRING-WAVE";
                yield context.minimalCustomerRepository.save(entity);
            }
            case DELETE_CUSTOMER -> {
                long customerId = fixture.deletedCustomerBaseId + iteration;
                context.minimalCustomerRepository.deleteById(customerId);
                yield customerId;
            }
        };
    }

    private static void consume(Object result) {
        if (result == null) {
            benchmarkBlackhole ^= 7L;
            return;
        }
        if (result instanceof Collection<?> collection) {
            benchmarkBlackhole ^= collection.size();
            Object first = result instanceof List<?> list && !list.isEmpty()
                    ? list.get(0)
                    : collection.iterator().hasNext() ? collection.iterator().next() : null;
            if (first != null) {
                benchmarkBlackhole ^= first.hashCode();
            }
            return;
        }
        benchmarkBlackhole ^= result.hashCode();
    }

    private static double relativeOverheadPercent(long value, double baseline) {
        if (baseline <= 0.0d) {
            return 0.0d;
        }
        return ((value - baseline) / baseline) * 100.0d;
    }

    private static long percentile(long[] values, double percentile) {
        if (values.length == 0) {
            return 0L;
        }
        long[] copy = values.clone();
        java.util.Arrays.sort(copy);
        int index = (int) Math.ceil(percentile * copy.length) - 1;
        index = Math.max(0, Math.min(index, copy.length - 1));
        return copy[index];
    }

    private enum OperationSpec {
        ACTIVE_CUSTOMERS("activeCustomers", "read"),
        CUSTOMERS_PAGE("customersPage", "read"),
        TOP_CUSTOMER_ORDERS_SUMMARY("topCustomerOrdersSummary", "read"),
        PROMOTE_VIP_CUSTOMER("promoteVipCustomer", "write"),
        DELETE_CUSTOMER("deleteCustomer", "write");

        private final String operationName;
        private final String category;

        OperationSpec(String operationName, String category) {
            this.operationName = operationName;
            this.category = category;
        }
    }

    private static final class OperationAccumulator {
        private final OperationSpec operation;
        private final long[] samples;
        private int index;
        private long totalLatencyNanos;

        private OperationAccumulator(OperationSpec operation, int capacity) {
            this.operation = operation;
            this.samples = new long[capacity];
        }

        private void record(long elapsedNanos) {
            samples[index++] = elapsedNanos;
            totalLatencyNanos += elapsedNanos;
        }

        private RepositoryRecipeOperationReport toReport() {
            long[] measuredSamples = java.util.Arrays.copyOf(samples, index);
            return new RepositoryRecipeOperationReport(
                    operation.operationName,
                    operation.category,
                    index,
                    totalLatencyNanos,
                    index == 0 ? 0L : totalLatencyNanos / index,
                    percentile(measuredSamples, 0.95d),
                    percentile(measuredSamples, 0.99d)
            );
        }
    }

    private static final class RecipeFixture {
        private final CapturingCustomerRepository customerRepository = new CapturingCustomerRepository();
        private final CapturingOrderRepository orderRepository = new CapturingOrderRepository();
        private final BenchmarkSession session = new BenchmarkSession(customerRepository, orderRepository);
        private final long hotCustomerId = 101L;
        private final long promotedCustomerBaseId = 10_000L;
        private final long deletedCustomerBaseId = 20_000L;
    }

    private static final class ModeExecutionContext {
        private final GeneratedCacheModule.Scope domainScope;
        private final EcomCustomerEntityCacheBinding.Scope customerScope;
        private final EcomOrderEntityCacheBinding.Scope orderScope;
        private final EntityRepository<EcomCustomerEntity, Long> minimalCustomerRepository;
        private final ProjectionRepository<EcomOrderReadModels.OrderSummaryReadModel, Long> minimalOrderSummaryRepository;

        private ModeExecutionContext(
                GeneratedCacheModule.Scope domainScope,
                EcomCustomerEntityCacheBinding.Scope customerScope,
                EcomOrderEntityCacheBinding.Scope orderScope,
                EntityRepository<EcomCustomerEntity, Long> minimalCustomerRepository,
                ProjectionRepository<EcomOrderReadModels.OrderSummaryReadModel, Long> minimalOrderSummaryRepository
        ) {
            this.domainScope = domainScope;
            this.customerScope = customerScope;
            this.orderScope = orderScope;
            this.minimalCustomerRepository = minimalCustomerRepository;
            this.minimalOrderSummaryRepository = minimalOrderSummaryRepository;
        }

        private static ModeExecutionContext forMode(RepositoryRecipeMode mode, RecipeFixture fixture) {
            return switch (mode) {
                case JPA_STYLE_DOMAIN_MODULE -> new ModeExecutionContext(
                        GeneratedCacheModule.using(fixture.session),
                        null,
                        null,
                        null,
                        null
                );
                case GENERATED_ENTITY_BINDING -> new ModeExecutionContext(
                        null,
                        EcomCustomerEntityCacheBinding.using(fixture.session),
                        EcomOrderEntityCacheBinding.using(fixture.session),
                        null,
                        null
                );
                case MINIMAL_REPOSITORY -> new ModeExecutionContext(
                        null,
                        null,
                        null,
                        fixture.customerRepository,
                        fixture.orderRepository.projected(EcomOrderEntityCacheBinding.orderSummaryProjection())
                );
            };
        }
    }

    private static final class BenchmarkSession implements CacheSession {
        private final CapturingCustomerRepository customerRepository;
        private final CapturingOrderRepository orderRepository;

        private BenchmarkSession(
                CapturingCustomerRepository customerRepository,
                CapturingOrderRepository orderRepository
        ) {
            this.customerRepository = customerRepository;
            this.orderRepository = orderRepository;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T, ID> EntityRepository<T, ID> repository(EntityMetadata<T, ID> metadata, EntityCodec<T> codec) {
            return (EntityRepository<T, ID>) switch (metadata.entityName()) {
                case "EcomCustomerEntity" -> customerRepository;
                case "EcomOrderEntity" -> orderRepository;
                default -> throw new IllegalArgumentException("Unsupported entity for recipe benchmark: " + metadata.entityName());
            };
        }

        @Override
        public <T, ID> EntityRepository<T, ID> repository(
                EntityMetadata<T, ID> metadata,
                EntityCodec<T> codec,
                CachePolicy cachePolicy
        ) {
            return repository(metadata, codec);
        }
    }

    private static final class CapturingCustomerRepository implements EntityRepository<EcomCustomerEntity, Long> {
        private final List<EcomCustomerEntity> activeCustomers = createActiveCustomers();
        private final Map<Long, EcomCustomerEntity> byId = activeCustomers.stream()
                .collect(java.util.stream.Collectors.toMap(customer -> customer.id, customer -> customer, (left, right) -> left, LinkedHashMap::new));

        @Override
        public Optional<EcomCustomerEntity> findById(Long id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public List<EcomCustomerEntity> findAll(Collection<Long> ids) {
            ArrayList<EcomCustomerEntity> results = new ArrayList<>();
            for (Long id : ids) {
                EcomCustomerEntity entity = byId.get(id);
                if (entity != null) {
                    results.add(entity);
                }
            }
            return List.copyOf(results);
        }

        @Override
        public List<EcomCustomerEntity> findPage(PageWindow pageWindow) {
            int from = Math.max(0, pageWindow.pageNumber() * pageWindow.pageSize());
            int to = Math.min(activeCustomers.size(), from + Math.max(0, pageWindow.pageSize()));
            if (from >= to) {
                return List.of();
            }
            return activeCustomers.subList(from, to);
        }

        @Override
        public QueryExplainPlan explain(QuerySpec querySpec) {
            throw new UnsupportedOperationException("Explain is not needed for the recipe benchmark");
        }

        @Override
        public List<EcomCustomerEntity> query(QuerySpec querySpec) {
            int limit = Math.max(1, Math.min(querySpec.limit(), activeCustomers.size()));
            return activeCustomers.subList(0, limit);
        }

        @Override
        public EcomCustomerEntity save(EcomCustomerEntity entity) {
            return entity;
        }

        @Override
        public void deleteById(Long id) {
        }

        @Override
        public EntityRepository<EcomCustomerEntity, Long> withFetchPlan(FetchPlan fetchPlan) {
            return this;
        }

        private static List<EcomCustomerEntity> createActiveCustomers() {
            ArrayList<EcomCustomerEntity> customers = new ArrayList<>();
            for (long id = 1L; id <= 128L; id++) {
                EcomCustomerEntity entity = new EcomCustomerEntity();
                entity.id = id;
                entity.status = "ACTIVE";
                entity.segment = id % 2 == 0 ? "LOYAL" : "NEW";
                entity.tier = id % 5 == 0 ? "VIP" : "STANDARD";
                entity.lastCampaignCode = "SPRING-WAVE";
                customers.add(entity);
            }
            return List.copyOf(customers);
        }
    }

    private static final class CapturingOrderRepository implements EntityRepository<EcomOrderEntity, Long> {
        private final List<EcomOrderEntity> orders = createOrders();
        private final Map<Long, EcomOrderEntity> byId = orders.stream()
                .collect(java.util.stream.Collectors.toMap(order -> order.id, order -> order, (left, right) -> left, LinkedHashMap::new));

        @Override
        public Optional<EcomOrderEntity> findById(Long id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public List<EcomOrderEntity> findAll(Collection<Long> ids) {
            ArrayList<EcomOrderEntity> results = new ArrayList<>();
            for (Long id : ids) {
                EcomOrderEntity entity = byId.get(id);
                if (entity != null) {
                    results.add(entity);
                }
            }
            return List.copyOf(results);
        }

        @Override
        public List<EcomOrderEntity> findPage(PageWindow pageWindow) {
            int from = Math.max(0, pageWindow.pageNumber() * pageWindow.pageSize());
            int to = Math.min(orders.size(), from + Math.max(0, pageWindow.pageSize()));
            if (from >= to) {
                return List.of();
            }
            return orders.subList(from, to);
        }

        @Override
        public QueryExplainPlan explain(QuerySpec querySpec) {
            throw new UnsupportedOperationException("Explain is not needed for the recipe benchmark");
        }

        @Override
        public List<EcomOrderEntity> query(QuerySpec querySpec) {
            int limit = Math.max(1, Math.min(querySpec.limit(), orders.size()));
            return orders.subList(0, limit);
        }

        @Override
        public EcomOrderEntity save(EcomOrderEntity entity) {
            return entity;
        }

        @Override
        public void deleteById(Long id) {
        }

        @Override
        public EntityRepository<EcomOrderEntity, Long> withFetchPlan(FetchPlan fetchPlan) {
            return this;
        }

        @Override
        public <P> ProjectionRepository<P, Long> projected(EntityProjection<EcomOrderEntity, P, Long> projection) {
            return new CapturingOrderProjectionRepository<>(projection, orders);
        }

        private static List<EcomOrderEntity> createOrders() {
            ArrayList<EcomOrderEntity> orders = new ArrayList<>();
            for (long id = 1L; id <= 128L; id++) {
                EcomOrderEntity entity = new EcomOrderEntity();
                entity.id = id;
                entity.customerId = 101L;
                entity.sku = "SKU-" + id;
                entity.quantity = (int) ((id % 4) + 1);
                entity.totalAmount = 50.0d + id;
                entity.status = id % 3 == 0 ? "PAID" : "READY";
                entity.campaignCode = "SPRING-WAVE";
                orders.add(entity);
            }
            return List.copyOf(orders);
        }
    }

    private static final class CapturingOrderProjectionRepository<P> implements ProjectionRepository<P, Long> {
        private final List<P> projections;
        private final Map<Long, P> byId;

        private CapturingOrderProjectionRepository(EntityProjection<EcomOrderEntity, P, Long> projection, List<EcomOrderEntity> orders) {
            ArrayList<P> projected = new ArrayList<>(orders.size());
            LinkedHashMap<Long, P> indexed = new LinkedHashMap<>();
            for (EcomOrderEntity order : orders) {
                P item = projection.projector().apply(order);
                projected.add(item);
                indexed.put(projection.idAccessor().apply(item), item);
            }
            this.projections = List.copyOf(projected);
            this.byId = Map.copyOf(indexed);
        }

        @Override
        public Optional<P> findById(Long id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public List<P> findAll(Collection<Long> ids) {
            ArrayList<P> results = new ArrayList<>();
            for (Long id : ids) {
                P projection = byId.get(id);
                if (projection != null) {
                    results.add(projection);
                }
            }
            return List.copyOf(results);
        }

        @Override
        public List<P> query(QuerySpec querySpec) {
            int limit = Math.max(1, Math.min(querySpec.limit(), projections.size()));
            return projections.subList(0, limit);
        }
    }
}
