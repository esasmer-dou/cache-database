package com.reactor.cachedb.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CacheRepositoryProcessorTest {

    @TempDir
    Path temp;

    @Test
    void generatesRepositoryWithCoverageKeysetAndReceiptFirstCommands() throws IOException {
        Compilation compilation = compile(
                source("sample/OrderEntity.java", """
                        package sample;
                        import com.reactor.cachedb.annotations.*;
                        @CacheEntity(table = "orders", redisNamespace = "orders")
                        public record OrderEntity(
                            @CacheId(column = "order_id")
                            @CacheGeneratedId(value = CacheGeneratedId.Strategy.SEQUENCE,
                                              sequence = "orders", allocationSize = 64)
                            Long orderId,
                            @CacheColumn("customer_id") Long customerId,
                            @CacheColumn("order_date") Long orderDate,
                            @CacheColumn("status") String status
                        ) {}
                        """),
                source("sample/OrderSummary.java", """
                        package sample;
                        import com.reactor.cachedb.annotations.*;
                        @CacheProjectionRecord(source = OrderEntity.class, id = "orderId", name = "order-summary")
                        public record OrderSummary(Long orderId, Long customerId, Long orderDate, String status) {}
                        """),
                source("sample/Orders.java", """
                        package sample;
                        import com.reactor.cachedb.annotations.*;
                        import com.reactor.cachedb.core.repository.*;
                        import com.reactor.cachedb.starter.CacheWarmPlan;
                        @CacheRepository(entity = OrderEntity.class, springBean = false)
                        public interface Orders extends CacheDbRepository<OrderEntity, Long> {
                            @HotRoute(value = "customer-timeline", projection = OrderSummary.class,
                                      coverageScopeParameter = "customerId")
                            @CacheRouteQuery(
                                predicates = {
                                    @CachePredicate(field = "customerId", parameter = "customerId"),
                                    @CachePredicate(field = "status", operator = CachePredicate.Operator.NE,
                                                    constants = "DELETED")
                                },
                                orderBy = @CacheOrder(field = "orderDate", direction = CacheOrder.Direction.DESC),
                                windowParameter = "window"
                            )
                            HotWindow<OrderSummary> timeline(long customerId, WindowRequest window);

                            @SourceRoute(value = "customer-archive", projection = OrderSummary.class,
                                         maxRows = 250, timeoutSeconds = 7)
                            @CacheRouteQuery(
                                predicates = @CachePredicate(field = "customerId", parameter = "customerId"),
                                orderBy = @CacheOrder(field = "orderDate", direction = CacheOrder.Direction.DESC),
                                windowParameter = "window"
                            )
                            SourceWindow<OrderSummary> archiveRoute(long customerId, WindowRequest window);

                            @WarmRoute(value = "warm-customer-timeline", from = "timeline", maxRows = 1000,
                                       projectionsOnly = true)
                            CacheWarmPlan warmTimeline(long customerId);

                            @SourceSql(
                                value = "SELECT order_id, customer_id, order_date, status, entity_version FROM orders WHERE customer_id = ? ORDER BY order_date DESC, order_id DESC FETCH FIRST 25 ROWS ONLY",
                                parameters = "customerId",
                                maxRows = 25
                            )
                            SourceWindow<OrderEntity> archive(long customerId);

                            @CacheCommand(
                                operation = CacheCommand.Operation.SAVE,
                                acknowledgement = CacheCommand.Acknowledgement.SQL_DURABLE,
                                durabilityTimeoutMillis = 2500
                            )
                            com.reactor.cachedb.core.model.WriteReceipt<OrderEntity, Long> persist(OrderEntity entity);
                        }
                        """),
                source("sample/CustomerEntity.java", """
                        package sample;
                        import com.reactor.cachedb.annotations.*;
                        @CacheEntity(table = "customers", redisNamespace = "customers")
                        public class CustomerEntity {
                            @CacheId(column = "customer_id") public Long customerId;
                            @CacheRelation(targetEntity = "OrderEntity", mappedBy = "customerId",
                                           kind = CacheRelation.RelationKind.ONE_TO_MANY, batchLoadOnly = true)
                            public java.util.List<OrderEntity> orders;
                            public CustomerEntity() {}
                        }
                        """),
                source("sample/Customers.java", """
                        package sample;
                        import com.reactor.cachedb.annotations.*;
                        import com.reactor.cachedb.core.repository.*;
                        @CacheRepository(entity = CustomerEntity.class, springBean = false)
                        public interface Customers extends CacheDbRepository<CustomerEntity, Long> {
                            @CacheLookup(idParameter = "customerId", relation = "orders",
                                         relationLimitParameter = "previewRows", maxRelationRows = 50)
                            HotLookup<CustomerEntity> detail(Long customerId, int previewRows);
                        }
                        """)
        );

        assertTrue(compilation.success(), compilation.diagnosticsText());
        String generated = Files.readString(compilation.generated().resolve("sample/OrdersCacheDbImplementation.java"));
        assertTrue(generated.contains("sourceFallbackAllowed(false)"));
        assertTrue(generated.contains("ROUTE_CONTRACT_TIMELINE"));
        assertTrue(generated.contains("ROUTE_SORTS_TIMELINE"));
        assertTrue(generated.contains("new java.util.ArrayList<>(entities.size())"));
        assertFalse(generated.contains("entities.stream().map(this::ensureGeneratedId)"));
        assertTrue(generated.contains("QuerySort.desc(\"order_id\")"));
        assertTrue(generated.contains("routeCoverage(\"customer-timeline\""));
        assertTrue(generated.contains("Route customer-timeline accepts at most 100 rows per request"));
        assertTrue(generated.contains(".name(\"warm-customer-timeline\")"));
        assertTrue(generated.contains(".projectionName(orderSummaryProjection.name())"));
        assertTrue(generated.contains(".coverage(\"customer-timeline\", java.lang.String.valueOf(customerId)"));
        assertTrue(generated.contains("hotRepository.saveWithReceipt(ensureGeneratedId(entity))"));
        assertTrue(generated.contains("sourceSqlRepository.query(query)"));
        assertTrue(generated.contains(".withQueryTimeoutSeconds(7)"));
        assertTrue(generated.contains("new com.reactor.cachedb.core.repository.SourceSqlQuery"));
        assertTrue(generated.contains("WriteDurabilityTimeoutException"));
        assertTrue(generated.contains("idGenerator().nextSequence(\"orders\", 64)"));

        String binding = Files.readString(compilation.generated().resolve("sample/OrderEntityCacheBinding.java"));
        assertTrue(binding.contains("return new OrderEntity("));
        assertTrue(binding.contains("entity.orderId()"));
        assertTrue(binding.contains("withId(OrderEntity entity, java.lang.Long id)"));
        String metamodel = Files.readString(compilation.generated().resolve("sample/OrderEntityFields.java"));
        assertTrue(metamodel.contains("CacheField<OrderEntity, java.lang.Long> customerId"));

        String customers = Files.readString(compilation.generated().resolve("sample/CustomersCacheDbImplementation.java"));
        assertTrue(customers.contains("withRelationLimit(\"orders\", resolvedRelationLimit)"));
        assertTrue(customers.contains("Relation preview orders accepts between 1 and 50 rows"));
    }

    @Test
    void generatesCompileTimeCheckedComputedProjectionFactory() throws IOException {
        Compilation compilation = compile(
                source("computed/ProductEntity.java", """
                        package computed;
                        import com.reactor.cachedb.annotations.*;
                        @CacheEntity(table = "products")
                        public class ProductEntity {
                            @CacheId(column = "product_id") public Long productId;
                            @CacheColumn("stock_quantity") public Integer stockQuantity;
                            @CacheColumn("reserved_quantity") public Integer reservedQuantity;
                            public ProductEntity() {}
                        }
                        """),
                source("computed/ProductAvailability.java", """
                        package computed;
                        import com.reactor.cachedb.annotations.*;
                        @CacheProjectionRecord(source = ProductEntity.class, id = "productId",
                                               name = "product-availability", factoryMethod = "fromEntity")
                        public record ProductAvailability(Long productId, Integer availableQuantity) {
                            public static ProductAvailability fromEntity(ProductEntity entity) {
                                int stock = entity.stockQuantity == null ? 0 : entity.stockQuantity;
                                int reserved = entity.reservedQuantity == null ? 0 : entity.reservedQuantity;
                                return new ProductAvailability(entity.productId, Math.max(0, stock - reserved));
                            }
                        }
                        """),
                source("computed/Products.java", """
                        package computed;
                        import com.reactor.cachedb.annotations.*;
                        import com.reactor.cachedb.core.repository.*;
                        @CacheRepository(entity = ProductEntity.class, springBean = false)
                        public interface Products extends CacheDbRepository<ProductEntity, Long> {
                            @HotRoute(value = "available", projection = ProductAvailability.class)
                            @CacheRouteQuery(limitParameter = "limit")
                            HotWindow<ProductAvailability> available(int limit);
                        }
                        """)
        );

        assertTrue(compilation.success(), compilation.diagnosticsText());
        String repository = Files.readString(
                compilation.generated().resolve("computed/ProductsCacheDbImplementation.java")
        );
        assertTrue(repository.contains("WindowRequest.first(limit)"));
        assertTrue(repository.contains(
                "java.util.Collection<computed.ProductEntity> resolved = entities == null ? java.util.List.of() : entities"
        ));
        String projection = Files.readString(
                compilation.generated().resolve("computed/ProductAvailabilityProjection.java")
        );
        assertTrue(projection.contains("return ProductAvailability.fromEntity(entity);"));
    }

    @Test
    void supportsGenericBaseRepositoriesAndDefaultConvenienceOverloads() throws IOException {
        Compilation compilation = compile(
                source("fragments/BaseRepository.java", """
                        package fragments;
                        import com.reactor.cachedb.core.repository.CacheDbRepository;
                        public interface BaseRepository<T, ID> extends CacheDbRepository<T, ID> {
                            default boolean hasHotValue(ID id) {
                                return findHotById(id).isHit();
                            }
                        }
                        """),
                source("fragments/Customer.java", """
                        package fragments;
                        import com.reactor.cachedb.annotations.*;
                        @CacheEntity(table = "customers")
                        public class Customer {
                            @CacheId(column = "customer_id") public Long customerId;
                            @CacheColumn("status") public String status;
                            public Customer() {}
                        }
                        """),
                source("fragments/Customers.java", """
                        package fragments;
                        import com.reactor.cachedb.annotations.*;
                        import com.reactor.cachedb.core.repository.*;
                        @CacheRepository(entity = Customer.class, springBean = false)
                        public interface Customers extends BaseRepository<Customer, Long> {
                            @HotRoute("active-customers")
                            @CacheRouteQuery(
                                predicates = @CachePredicate(field = "status", constants = "ACTIVE"),
                                limitParameter = "limit"
                            )
                            HotWindow<Customer> active(int limit);

                            default java.util.List<Customer> active() {
                                return active(25).completeItems();
                            }
                        }
                        """)
        );

        assertTrue(compilation.success(), compilation.diagnosticsText());
        String repository = Files.readString(
                compilation.generated().resolve("fragments/CustomersCacheDbImplementation.java")
        );
        assertTrue(repository.contains("implements Customers"));
        assertTrue(repository.contains("WindowRequest.first(limit)"));
    }

    @Test
    void generatesReflectionFreeScheduledWarmAdapter() throws IOException {
        Compilation compilation = compile(
                source("scheduled/WarmPlans.java", """
                        package scheduled;
                        import com.reactor.cachedb.spring.boot.CacheScheduledWarm;
                        import com.reactor.cachedb.starter.CacheWarmPlan;
                        public class WarmPlans {
                            @CacheScheduledWarm(
                                name = "active-orders",
                                fixedDelayString = "PT5M",
                                lockAtMostForString = "PT2M",
                                reconcileHotSet = true
                            )
                            public CacheWarmPlan activeOrders() {
                                return null;
                            }
                        }
                        """)
        );

        assertTrue(compilation.success(), compilation.diagnosticsText());
        String generated = Files.readString(
                compilation.generated().resolve("scheduled/WarmPlansActiveOrdersCacheScheduledWarmTask.java")
        );
        assertTrue(generated.contains("implements com.reactor.cachedb.spring.boot.CacheScheduledWarmTask"));
        assertTrue(generated.contains("return target.activeOrders();"));
        assertTrue(generated.contains("\"active-orders\""));
        assertTrue(generated.contains("CacheScheduledWarmMode.ENTITY_AND_PROJECTIONS"));
        assertFalse(generated.contains("java.lang.reflect"));
        assertFalse(generated.contains("Method.invoke"));
    }

    @Test
    void rejectsInvalidScheduledWarmSignatureAtCompileTime() throws IOException {
        Compilation compilation = compile(
                source("scheduled/InvalidWarmPlans.java", """
                        package scheduled;
                        import com.reactor.cachedb.spring.boot.CacheScheduledWarm;
                        public class InvalidWarmPlans {
                            @CacheScheduledWarm(fixedDelayString = "PT5M")
                            public String activeOrders(String tenantId) {
                                return tenantId;
                            }
                        }
                        """)
        );

        assertFalse(compilation.success());
        assertTrue(compilation.diagnosticsText().contains(
                "@CacheScheduledWarm method must be public, non-static, and parameterless"
        ), compilation.diagnosticsText());
    }

    @Test
    void rejectsUnknownQueryFieldAtCompileTime() throws IOException {
        Compilation compilation = compile(
                source("broken/CustomerEntity.java", """
                        package broken;
                        import com.reactor.cachedb.annotations.*;
                        @CacheEntity(table = "customers")
                        public class CustomerEntity {
                            @CacheId(column = "customer_id") public Long customerId;
                            public CustomerEntity() {}
                        }
                        """),
                source("broken/Customers.java", """
                        package broken;
                        import com.reactor.cachedb.annotations.*;
                        import com.reactor.cachedb.core.repository.*;
                        @CacheRepository(entity = CustomerEntity.class, springBean = false)
                        public interface Customers extends CacheDbRepository<CustomerEntity, Long> {
                            @HotRoute("broken-route")
                            @CacheRouteQuery(predicates = @CachePredicate(field = "missing", parameter = "value"),
                                             fixedLimit = 10)
                            HotWindow<CustomerEntity> broken(String value);
                        }
                        """)
        );

        assertFalse(compilation.success());
        assertTrue(compilation.diagnosticsText().contains("field does not exist or is not persisted"),
                compilation.diagnosticsText());
    }

    @Test
    void rejectsIncompatibleAndUnusedRouteParametersAtCompileTime() throws IOException {
        Compilation compilation = compile(
                source("broken/OrderEntity.java", """
                        package broken;
                        import com.reactor.cachedb.annotations.*;
                        @CacheEntity(table = "orders")
                        public class OrderEntity {
                            @CacheId(column = "order_id") public Long orderId;
                            @CacheColumn("customer_id") public Long customerId;
                            public OrderEntity() {}
                        }
                        """),
                source("broken/Orders.java", """
                        package broken;
                        import com.reactor.cachedb.annotations.*;
                        import com.reactor.cachedb.core.repository.*;
                        @CacheRepository(entity = OrderEntity.class, springBean = false)
                        public interface Orders extends CacheDbRepository<OrderEntity, Long> {
                            @HotRoute("customer-orders")
                            @CacheRouteQuery(
                                predicates = @CachePredicate(field = "customerId", parameter = "customerId"),
                                windowParameter = "window"
                            )
                            HotWindow<OrderEntity> broken(
                                    String customerId,
                                    String unused,
                                    WindowRequest window
                            );
                        }
                        """)
        );

        assertFalse(compilation.success());
        assertTrue(compilation.diagnosticsText().contains("parameter type is incompatible")
                        || compilation.diagnosticsText().contains("unused=[unused]"),
                compilation.diagnosticsText());
    }

    @Test
    void rejectsMutatingCteAndInvalidGeneratedIdAtCompileTime() throws IOException {
        Compilation compilation = compile(
                source("broken/AuditEntity.java", """
                        package broken;
                        import com.reactor.cachedb.annotations.*;
                        @CacheEntity(table = "audit")
                        public record AuditEntity(
                            @CacheId(column = "audit_id")
                            @CacheGeneratedId(CacheGeneratedId.Strategy.SEQUENCE)
                            long auditId
                        ) {}
                        """),
                source("broken/Audits.java", """
                        package broken;
                        import com.reactor.cachedb.annotations.*;
                        import com.reactor.cachedb.core.repository.*;
                        @CacheRepository(entity = AuditEntity.class, springBean = false)
                        public interface Audits extends CacheDbRepository<AuditEntity, Long> {
                            @SourceSql("WITH removed AS (DELETE FROM audit RETURNING audit_id) SELECT audit_id FROM removed")
                            SourceWindow<AuditEntity> unsafe();
                        }
                        """)
        );

        assertFalse(compilation.success());
        assertTrue(compilation.diagnosticsText().contains("requires java.lang.Long")
                        || compilation.diagnosticsText().contains("read-only"),
                compilation.diagnosticsText());
    }

    private Compilation compile(Source... sources) throws IOException {
        Path sourceRoot = Files.createDirectories(temp.resolve("sources-" + System.nanoTime()));
        Path generated = Files.createDirectories(temp.resolve("generated-" + System.nanoTime()));
        Path classes = Files.createDirectories(temp.resolve("classes-" + System.nanoTime()));
        ArrayList<Path> sourcePaths = new ArrayList<>();
        for (Source source : sources) {
            Path path = sourceRoot.resolve(source.path());
            Files.createDirectories(path.getParent());
            Files.writeString(path, source.content());
            sourcePaths.add(path);
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, Locale.ROOT, null)) {
            Iterable<? extends JavaFileObject> units = files.getJavaFileObjectsFromPaths(sourcePaths);
            List<String> options = List.of(
                    "--release", "17",
                    "-proc:full",
                    "-classpath", System.getProperty("java.class.path"),
                    "-s", generated.toString(),
                    "-d", classes.toString()
            );
            JavaCompiler.CompilationTask task = compiler.getTask(null, files, diagnostics, options, null, units);
            task.setProcessors(List.of(
                    new CacheEntityProcessor(),
                    new CacheProjectionRecordProcessor(),
                    new CacheRepositoryProcessor(),
                    new CacheScheduledWarmProcessor()
            ));
            boolean success = Boolean.TRUE.equals(task.call());
            return new Compilation(success, generated, diagnostics.getDiagnostics());
        }
    }

    private Source source(String path, String content) {
        return new Source(path, content);
    }

    private record Source(String path, String content) {
    }

    private record Compilation(
            boolean success,
            Path generated,
            List<Diagnostic<? extends JavaFileObject>> diagnostics
    ) {
        String diagnosticsText() {
            return diagnostics.stream()
                    .map(diagnostic -> diagnostic.getKind() + ": " + diagnostic.getMessage(Locale.ROOT))
                    .reduce((left, right) -> left + System.lineSeparator() + right)
                    .orElse("");
        }
    }
}
