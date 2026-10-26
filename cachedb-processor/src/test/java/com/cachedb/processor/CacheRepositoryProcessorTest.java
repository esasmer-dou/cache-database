package com.reactor.cachedb.processor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CacheRepositoryProcessorTest {

    @TempDir
    Path temp;

    @Test
    @SuppressWarnings("unchecked")
    void generatedRepositoryAcceptsAnInfrastructureFreeSession() throws Exception {
        var compilation = compile(
                source("sample/Ticket.java", """
                        package sample;
                        import com.reactor.cachedb.annotations.*;
                        @CacheEntity(table="tickets", redisNamespace="tickets")
                        public record Ticket(@CacheId(column="ticket_id") Long id,
                                             @CacheColumn("status") String status) {}
                        """),
                source("sample/Tickets.java", """
                        package sample;
                        import com.reactor.cachedb.annotations.*;
                        import com.reactor.cachedb.core.repository.CacheDbRepository;
                        @CacheRepository(entity=Ticket.class)
                        public interface Tickets extends CacheDbRepository<Ticket, Long> {}
                        """));
        assertTrue(compilation.success(), compilation.diagnosticsText());
        try (var loader = new java.net.URLClassLoader(new java.net.URL[] {compilation.classes().toUri().toURL()}, getClass().getClassLoader())) {
            var memory = new com.reactor.cachedb.core.testing.InMemoryCacheDatabase();
            var implementation = loader.loadClass("sample.TicketsCacheDbImplementation");
            var repository = (com.reactor.cachedb.core.repository.CacheDbRepository<Object, Long>) implementation
                    .getConstructor(com.reactor.cachedb.core.api.CacheSession.class).newInstance(memory);
            Object row = loader.loadClass("sample.Ticket").getConstructor(Long.class, String.class).newInstance(1L, "OPEN");
            var receipt = repository.save(row, 0);
            assertTrue(repository.findHotById(1L).isHit());
            org.junit.jupiter.api.Assertions.assertThrows(com.reactor.cachedb.core.model.OptimisticWriteConflictException.class, () -> repository.save(row, 0));
            assertFalse(repository.isDurable(receipt));
            memory.markDurable(receipt);
            assertTrue(repository.awaitDurable(receipt, java.time.Duration.ofMillis(10)));
            repository.deleteById(1L);
            assertTrue(repository.findHotById(1L).isTombstoned());
            org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class, () -> repository.findSourceById(1L));
        }
    }

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
                        import com.reactor.cachedb.starter.CacheWarmTarget;
                        @CacheRepository(entity = OrderEntity.class)
                        public interface Orders extends CacheDbRepository<OrderEntity, Long> {
                            @HotRoute(value = "customer-timeline", projection = OrderSummary.class,
                                      coverageScopeParameter = "customerId")
                            @CacheRouteQuery(
                                predicates = {
                                    @CachePredicate(field = "customerId"),
                                    @CachePredicate(field = "status", operator = CachePredicate.Operator.NE,
                                                    constants = "DELETED")
                                },
                                orderBy = @CacheOrder(field = "orderDate", direction = CacheOrder.Direction.DESC)
                            )
                            CursorPage<OrderSummary> timeline(long customerId, WindowRequest window);

                            @SourceRoute(value = "customer-archive", projection = OrderSummary.class,
                                         maxRows = 250, timeoutSeconds = 7)
                            @CacheRouteQuery(
                                predicates = @CachePredicate(field = "customerId"),
                                orderBy = @CacheOrder(field = "orderDate", direction = CacheOrder.Direction.DESC)
                            )
                            CursorPage<OrderSummary> archiveRoute(long customerId, WindowRequest window);

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
                            @CacheLookup(relation = "orders", maxRelationRows = 50)
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
        assertTrue(generated.contains(".completePage();"));
        assertTrue(generated.contains(".page();"));
        assertTrue(generated.contains("Route customer-timeline accepts at most 100 rows per request"));
        assertTrue(generated.contains(".name(\"warm-customer-timeline\")"));
        assertTrue(generated.contains(".projectionName(orderSummaryProjection.name())"));
        assertTrue(generated.contains(".coverage(\"customer-timeline\", java.lang.String.valueOf(customerId)"));
        assertTrue(generated.contains("hotRepository.saveWithReceipt(ensureGeneratedId(entity))"));
        assertTrue(generated.contains("sourceSqlRepository.query(cachedb$query)"));
        assertTrue(generated.contains(".withQueryTimeoutSeconds(7)"));
        assertTrue(generated.contains("new com.reactor.cachedb.core.repository.SourceSqlQuery"));
        assertTrue(generated.contains("cacheDatabase.awaitDurableOrThrow(cachedb$receipt, cachedb$timeout)"));
        assertTrue(generated.contains("new java.util.ArrayList<>(cachedb$sourceRows.size())"));
        assertFalse(generated.contains("sourceRepository.query(cachedb$query).stream()"));
        assertTrue(generated.contains("idGenerator().nextSequence(\"orders\", 64)"));
        assertTrue(generated.contains("RepositoryRouteCatalog ROUTE_CATALOG"));
        assertTrue(generated.contains("RepositoryRouteKind.HOT"));
        assertTrue(generated.contains("RepositoryRouteKind.SOURCE_SQL"));
        assertTrue(generated.contains("RepositoryRouteKind.WARM"));
        assertTrue(generated.contains("operation=SAVE;acknowledgement=SQL_DURABLE"));
        assertTrue(generated.contains("public static com.reactor.cachedb.core.route.RepositoryRouteCatalog routeCatalog()"));

        String routeReferences = Files.readString(compilation.generated().resolve("sample/OrdersCacheDbRoutes.java"));
        assertTrue(routeReferences.contains("RepositoryRouteRef timeline()"));
        assertTrue(routeReferences.contains("RepositoryRouteRef warmTimeline()"));
        assertTrue(routeReferences.contains("CATALOG.requireMethod(\"timeline\")"));

        String configuration = Files.readString(compilation.generated().resolve("sample/OrdersCacheDbConfiguration.java"));
        assertTrue(configuration.contains("OrdersCacheDbImplementation.routeCatalog()"));
        assertTrue(configuration.contains("@org.springframework.context.annotation.Bean(name = \"orders\")"));
        assertTrue(configuration.contains("@org.springframework.context.annotation.Bean(name = \"sample.Orders.routeCatalog\")"));

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
                            @CacheRouteQuery
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
    void supportsSqlSchemaTypesInDeclarativeProjectionRecords() throws IOException {
        Compilation compilation = compile(
                source("types/TypedEntity.java", """
                        package types;
                        import com.reactor.cachedb.annotations.*;
                        @CacheEntity(table = "typed_entities")
                        public class TypedEntity {
                            @CacheId(column = "entity_id") public java.util.UUID entityId;
                            @CacheColumn("event_time") public java.time.Instant eventTime;
                            @CacheColumn("business_date") public java.time.LocalDate businessDate;
                            @CacheColumn("business_date_time") public java.time.LocalDateTime businessDateTime;
                            @CacheColumn("business_time") public java.time.LocalTime businessTime;
                            @CacheColumn("short_value") public Short shortValue;
                            @CacheColumn("float_value") public Float floatValue;
                            public TypedEntity() {}
                        }
                        """),
                source("types/TypedSummary.java", """
                        package types;
                        import com.reactor.cachedb.annotations.*;
                        @CacheProjectionRecord(source = TypedEntity.class, id = "entityId", name = "typed-summary")
                        public record TypedSummary(
                            java.util.UUID entityId,
                            java.time.Instant eventTime,
                            java.time.LocalDate businessDate,
                            java.time.LocalDateTime businessDateTime,
                            java.time.LocalTime businessTime,
                            Short shortValue,
                            Float floatValue
                        ) {}
                        """)
        );

        assertTrue(compilation.success(), compilation.diagnosticsText());
        String schema = Files.readString(compilation.generated().resolve("types/TypedSummaryProjectionSchema.java"));
        assertTrue(schema.contains(".uuidColumn(\"entity_id\""));
        assertTrue(schema.contains(".instantColumn(\"event_time\""));
        assertTrue(schema.contains(".localDateColumn(\"business_date\""));
        assertTrue(schema.contains(".localDateTimeColumn(\"business_date_time\""));
        assertTrue(schema.contains(".localTimeColumn(\"business_time\""));
        assertTrue(schema.contains(".shortColumn(\"short_value\""));
        assertTrue(schema.contains(".floatColumn(\"float_value\""));
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
    void rejectsCoverageScopeThatDoesNotEqualityConstrainEveryQueryGroup() throws IOException {
        Compilation compilation = compile(
                source("scope/OrderEntity.java", """
                        package scope;
                        import com.reactor.cachedb.annotations.*;
                        @CacheEntity(table = "orders")
                        public class OrderEntity {
                            @CacheId(column = "order_id") public Long orderId;
                            @CacheColumn("customer_id") public Long customerId;
                            public OrderEntity() {}
                        }
                        """),
                source("scope/Orders.java", """
                        package scope;
                        import com.reactor.cachedb.annotations.*;
                        import com.reactor.cachedb.core.repository.*;
                        @CacheRepository(entity = OrderEntity.class, springBean = false)
                        public interface Orders extends CacheDbRepository<OrderEntity, Long> {
                            @HotRoute(value = "unsafe-scope", coverageScopeParameter = "customerId")
                            @CacheRouteQuery(predicates = @CachePredicate(
                                    field = "customerId", operator = CachePredicate.Operator.GTE))
                            HotWindow<OrderEntity> unsafe(long customerId);
                        }
                        """)
        );

        assertFalse(compilation.success());
        assertTrue(compilation.diagnosticsText().contains("exactly one EQ predicate"),
                compilation.diagnosticsText());
    }

    @Test
    void requiresExplicitOptInForOrPredicateGroups() throws IOException {
        Source entity = source("groups/OrderEntity.java", """
                package groups;
                import com.reactor.cachedb.annotations.*;
                @CacheEntity(table = "orders")
                public class OrderEntity {
                    @CacheId(column = "order_id") public Long orderId;
                    @CacheColumn("status") public String status;
                    @CacheColumn("order_date") public Long orderDate;
                    public OrderEntity() {}
                }
                """);
        Compilation accidentalOr = compile(
                entity,
                source("groups/UnsafeOrders.java", """
                        package groups;
                        import com.reactor.cachedb.annotations.*;
                        import com.reactor.cachedb.core.repository.*;
                        @CacheRepository(entity = OrderEntity.class, springBean = false)
                        public interface UnsafeOrders extends CacheDbRepository<OrderEntity, Long> {
                            @HotRoute("active-orders")
                            @CacheRouteQuery(
                                predicates = {
                                    @CachePredicate(field = "orderDate", parameter = "cutoff", group = 0),
                                    @CachePredicate(field = "status", constants = "OPEN", group = 1)
                                },
                                limitParameter = "limit"
                            )
                            HotWindow<OrderEntity> active(long cutoff, int limit);
                        }
                        """)
        );

        assertFalse(accidentalOr.success());
        assertTrue(accidentalOr.diagnosticsText().contains("explicitDisjunction=true"),
                accidentalOr.diagnosticsText());

        Compilation explicitOr = compile(
                entity,
                source("groups/SafeOrders.java", """
                        package groups;
                        import com.reactor.cachedb.annotations.*;
                        import com.reactor.cachedb.core.repository.*;
                        @CacheRepository(entity = OrderEntity.class, springBean = false)
                        public interface SafeOrders extends CacheDbRepository<OrderEntity, Long> {
                            @SourceRoute("open-or-recent")
                            @CacheRouteQuery(
                                predicates = {
                                    @CachePredicate(field = "orderDate", parameter = "cutoff", group = 0),
                                    @CachePredicate(field = "status", constants = "OPEN", group = 1)
                                },
                                explicitDisjunction = true,
                                limitParameter = "limit"
                            )
                            SourceWindow<OrderEntity> openOrRecent(long cutoff, int limit);
                        }
                        """)
        );

        assertTrue(explicitOr.success(), explicitOr.diagnosticsText());
        String generated = Files.readString(
                explicitOr.generated().resolve("groups/SafeOrdersCacheDbImplementation.java")
        );
        assertTrue(generated.contains("QueryGroup.or("));
    }

    @Test
    void requiresWarmRouteWhenHotPopulationIsDeclaredWarm() throws IOException {
        Source entity = source("population/RouteEntity.java", """
                package population;
                import com.reactor.cachedb.annotations.*;
                @CacheEntity(table = "routes", redisNamespace = "routes")
                public record RouteEntity(
                    @CacheId(column = "route_id") Long routeId,
                    @CacheColumn("created_at") Long createdAt
                ) {}
                """);
        Source projection = source("population/RouteSummary.java", """
                package population;
                import com.reactor.cachedb.annotations.*;
                @CacheProjectionRecord(source = RouteEntity.class, id = "routeId", name = "route-summary")
                public record RouteSummary(Long routeId, Long createdAt) {}
                """);
        Compilation missingWarm = compile(
                entity,
                projection,
                source("population/MissingWarmRepository.java", """
                        package population;
                        import com.reactor.cachedb.annotations.*;
                        import com.reactor.cachedb.core.repository.*;
                        @CacheRepository(entity = RouteEntity.class, springBean = false)
                        public interface MissingWarmRepository extends CacheDbRepository<RouteEntity, Long> {
                            @HotRoute(value = "declared-warm", projection = RouteSummary.class,
                                      population = HotRoute.Population.DECLARED_WARM)
                            @CacheRouteQuery(
                                orderBy = @CacheOrder(field = "createdAt", direction = CacheOrder.Direction.DESC),
                                limitParameter = "limit"
                            )
                            HotWindow<RouteSummary> recent(int limit);
                        }
                        """)
        );

        assertFalse(missingWarm.success());
        assertTrue(missingWarm.diagnosticsText().contains("requires at least one @WarmRoute"),
                missingWarm.diagnosticsText());

        Compilation declaredWarm = compile(
                entity,
                projection,
                source("population/DeclaredWarmRepository.java", """
                        package population;
                        import com.reactor.cachedb.annotations.*;
                        import com.reactor.cachedb.core.repository.*;
                        import com.reactor.cachedb.starter.CacheWarmPlan;
                        import com.reactor.cachedb.starter.CacheWarmTarget;
                        @CacheRepository(entity = RouteEntity.class, springBean = false)
                        public interface DeclaredWarmRepository extends CacheDbRepository<RouteEntity, Long> {
                            @HotRoute(value = "declared-warm", projection = RouteSummary.class,
                                      population = HotRoute.Population.DECLARED_WARM)
                            @CacheRouteQuery(
                                orderBy = @CacheOrder(field = "createdAt", direction = CacheOrder.Direction.DESC),
                                limitParameter = "limit"
                            )
                            HotWindow<RouteSummary> recent(int limit);

                            @WarmRoute(value = "warm-declared", from = "recent", maxRowsParameter = "limit",
                                       targetParameter = "target")
                            CacheWarmPlan warmRecent(int limit, CacheWarmTarget target);
                        }
                        """)
        );

        assertTrue(declaredWarm.success(), declaredWarm.diagnosticsText());
        String generated = Files.readString(declaredWarm.generated()
                .resolve("population/DeclaredWarmRepositoryCacheDbImplementation.java"));
        assertTrue(generated.contains("HotRoutePopulation.DECLARED_WARM"));
        assertTrue(generated.contains("cachedb$target == com.reactor.cachedb.starter.CacheWarmTarget.PROJECTIONS_ONLY"));
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

    @Test
    void rejectsCollidingSpringRepositoryBeanNamesAcrossPackages() throws IOException {
        Compilation compilation = compile(
                source("left/OrderEntity.java", """
                        package left;
                        import com.reactor.cachedb.annotations.*;
                        @CacheEntity(table = "left_orders", redisNamespace = "left-orders")
                        public record OrderEntity(@CacheId(column = "order_id") Long orderId) {}
                        """),
                source("left/Orders.java", """
                        package left;
                        import com.reactor.cachedb.annotations.*;
                        import com.reactor.cachedb.core.repository.CacheDbRepository;
                        @CacheRepository(entity = OrderEntity.class)
                        public interface Orders extends CacheDbRepository<OrderEntity, Long> {}
                        """),
                source("right/OrderEntity.java", """
                        package right;
                        import com.reactor.cachedb.annotations.*;
                        @CacheEntity(table = "right_orders", redisNamespace = "right-orders")
                        public record OrderEntity(@CacheId(column = "order_id") Long orderId) {}
                        """),
                source("right/Orders.java", """
                        package right;
                        import com.reactor.cachedb.annotations.*;
                        import com.reactor.cachedb.core.repository.CacheDbRepository;
                        @CacheRepository(entity = OrderEntity.class)
                        public interface Orders extends CacheDbRepository<OrderEntity, Long> {}
                        """)
        );

        assertFalse(compilation.success());
        assertTrue(compilation.diagnosticsText().contains("set a distinct @CacheRepository.springBeanName"),
                compilation.diagnosticsText());
    }

    @Test
    void generatesDistinctExplicitSpringRepositoryBeanNamesAcrossPackages() throws IOException {
        Compilation compilation = compile(
                source("left/OrderEntity.java", """
                        package left;
                        import com.reactor.cachedb.annotations.*;
                        @CacheEntity(table = "left_orders", redisNamespace = "left-orders")
                        public record OrderEntity(@CacheId(column = "order_id") Long orderId) {}
                        """),
                source("left/Orders.java", """
                        package left;
                        import com.reactor.cachedb.annotations.*;
                        import com.reactor.cachedb.core.repository.CacheDbRepository;
                        @CacheRepository(entity = OrderEntity.class, springBeanName = "leftOrders")
                        public interface Orders extends CacheDbRepository<OrderEntity, Long> {}
                        """),
                source("right/OrderEntity.java", """
                        package right;
                        import com.reactor.cachedb.annotations.*;
                        @CacheEntity(table = "right_orders", redisNamespace = "right-orders")
                        public record OrderEntity(@CacheId(column = "order_id") Long orderId) {}
                        """),
                source("right/Orders.java", """
                        package right;
                        import com.reactor.cachedb.annotations.*;
                        import com.reactor.cachedb.core.repository.CacheDbRepository;
                        @CacheRepository(entity = OrderEntity.class, springBeanName = "rightOrders")
                        public interface Orders extends CacheDbRepository<OrderEntity, Long> {}
                        """)
        );

        assertTrue(compilation.success(), compilation.diagnosticsText());
        String left = Files.readString(compilation.generated().resolve("left/OrdersCacheDbConfiguration.java"));
        String right = Files.readString(compilation.generated().resolve("right/OrdersCacheDbConfiguration.java"));
        assertTrue(left.contains("@org.springframework.context.annotation.Bean(name = \"leftOrders\")"));
        assertTrue(right.contains("@org.springframework.context.annotation.Bean(name = \"rightOrders\")"));
        assertTrue(left.contains("@org.springframework.context.annotation.Bean(name = \"left.Orders.routeCatalog\")"));
        assertTrue(right.contains("@org.springframework.context.annotation.Bean(name = \"right.Orders.routeCatalog\")"));
    }

    @Test
    void appliesRepositoryRouteDefaultsOnlyWhenMethodDoesNotOverrideThem() throws IOException {
        Compilation compilation = compile(
                source("defaults/OrderEntity.java", """
                        package defaults;
                        import com.reactor.cachedb.annotations.*;
                        @CacheEntity(table = "orders", redisNamespace = "orders")
                        public record OrderEntity(
                            @CacheId(column = "order_id") Long orderId,
                            @CacheColumn("created_at") Long createdAt
                        ) {}
                        """),
                source("defaults/Orders.java", """
                        package defaults;
                        import com.reactor.cachedb.annotations.*;
                        import com.reactor.cachedb.core.repository.*;
                        import com.reactor.cachedb.starter.CacheWarmPlan;
                        @CacheRepository(entity = OrderEntity.class, springBean = false)
                        @CacheRepositoryDefaults(
                            hotPopulation = HotRoute.Population.DECLARED_WARM,
                            hotPageSize = 25,
                            hotWindow = 400,
                            hotMemoryBudgetBytes = 8388608,
                            hotMaxStalenessSeconds = 90,
                            sourceMaxRows = 250,
                            sourceTimeoutSeconds = 12,
                            warmMaxRows = 400
                        )
                        public interface Orders extends CacheDbRepository<OrderEntity, Long> {
                            @HotRoute("recent")
                            @CacheRouteQuery(
                                orderBy = @CacheOrder(field = "createdAt", direction = CacheOrder.Direction.DESC),
                                windowParameter = "window"
                            )
                            HotWindow<OrderEntity> recent(WindowRequest window);

                            @SourceRoute("archive")
                            @CacheRouteQuery(
                                orderBy = @CacheOrder(field = "createdAt", direction = CacheOrder.Direction.DESC),
                                windowParameter = "window"
                            )
                            SourceWindow<OrderEntity> archive(WindowRequest window);

                            @WarmRoute(value = "warm-recent", from = "recent")
                            CacheWarmPlan warmRecent();
                        }
                        """)
        );

        assertTrue(compilation.success(), compilation.diagnosticsText());
        String generated = Files.readString(compilation.generated()
                .resolve("defaults/OrdersCacheDbImplementation.java"));
        assertTrue(generated.contains("RouteCacheContract.builder()"));
        assertTrue(generated.contains(".routeName(\"recent\")"));
        assertTrue(generated.contains(".pageSize(25)"));
        assertTrue(generated.contains(".hotWindow(400)"));
        assertTrue(generated.contains("Duration.ofSeconds(90L)"));
        assertTrue(generated.contains("HotRoutePopulation.DECLARED_WARM"));
        assertTrue(generated.contains("resolvedMaxRows = 400"));
        assertTrue(generated.contains(".withQueryTimeoutSeconds(12)"));
    }

    @Test
    void seekExpandsTwoAndThreeColumnLexicographicPredicates() throws Exception {
        for (boolean three : List.of(false, true)) {
            Compilation result = compile(
                    source("seek/Row.java", """
                            package seek;
                            import com.reactor.cachedb.annotations.*;
                            @CacheEntity(table="rows", redisNamespace="rows")
                            @CachePartitionedIndex(partitionBy="priority",sortBy="event_date")
                            public record Row(@CacheId Long id, @CacheColumn("event_date") Long date, @CacheColumn("priority") Long priority) {}
                            """),
                    source("seek/Rows.java", """
                            package seek;
                            import com.reactor.cachedb.annotations.*;
                            import com.reactor.cachedb.core.repository.*;
                            @CacheRepository(entity=Row.class, springBean=false)
                            public interface Rows extends CacheDbRepository<Row,Long> {
                              @SourceRoute("archive")
                              @CacheRouteQuery(seek={%s
                                @CacheSeek(field="date",param="beforeDate"), @CacheSeek(field="id",param="beforeId")},
                                orderBy={%s @CacheOrder(field="date",direction=CacheOrder.Direction.DESC),
                                  @CacheOrder(field="id",direction=CacheOrder.Direction.DESC)})
                              SourceWindow<Row> archive(long beforeDate, long beforeId, %s WindowRequest window);
                            }
                            """.formatted(three ? "@CacheSeek(field=\"priority\",param=\"beforePriority\")," : "",
                                    three ? "@CacheOrder(field=\"priority\",direction=CacheOrder.Direction.DESC)," : "",
                                    three ? "long beforePriority," : "")));
            assertTrue(result.success(), result.diagnosticsText());
            String generated = Files.readString(result.generated().resolve("seek/RowsCacheDbImplementation.java"));
            assertTrue(generated.contains("QueryFilter.lt("), generated);
            assertTrue(generated.contains("QueryFilter.eq("), generated);
            try (var loader = new java.net.URLClassLoader(new java.net.URL[]{result.classes().toUri().toURL()}, getClass().getClassLoader())) {
                var metadata = (com.reactor.cachedb.core.model.EntityMetadata<?, ?>) loader.loadClass("seek.RowCacheBinding").getField("METADATA").get(null);
                org.junit.jupiter.api.Assertions.assertSame(metadata.columnTypes(), metadata.columnTypes());
                org.junit.jupiter.api.Assertions.assertSame(metadata.partitionedSortIndexes(), metadata.partitionedSortIndexes());
                assertFalse(metadata.partitionedSortIndexes().isEmpty());
                org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                        () -> metadata.columnTypes().put("illegal", "java.lang.String"));
            }
        }
    }

    @Test
    void seekRejectsUnknownRepeatedMismatchedAndNonUniqueFields() throws Exception {
        List<String> invalid = List.of(
                "@CacheSeek(field=\"missing\",param=\"before\")",
                "@CacheSeek(field=\"date\",param=\"before\",direction=CachePredicate.Operator.LTE)",
                "@CacheSeek(field=\"date\",param=\"before\")",
                "@CacheSeek(field=\"date\",param=\"before\"),@CacheSeek(field=\"date\",param=\"before\")",
                "@CacheSeek(field=\"id\",param=\"before\",direction=CachePredicate.Operator.GT)",
                String.join(",",java.util.Collections.nCopies(5,"@CacheSeek(field=\"id\",param=\"before\")")));
        for (String seek : invalid) {
            Compilation result = compile(source("invalid/Row.java", """
                    package invalid;
                    import com.reactor.cachedb.annotations.*;
                    @CacheEntity(table="rows",redisNamespace="rows")
                    public record Row(@CacheId Long id,@CacheColumn("event_date") Long date) {}
                    """),source("invalid/Rows.java", """
                    package invalid;
                    import com.reactor.cachedb.annotations.*;
                    import com.reactor.cachedb.core.repository.*;
                    @CacheRepository(entity=Row.class,springBean=false)
                    public interface Rows extends CacheDbRepository<Row,Long> {
                      @SourceRoute("archive")
                      @CacheRouteQuery(seek={%s},orderBy=@CacheOrder(field="date",direction=CacheOrder.Direction.DESC))
                      SourceWindow<Row> archive(long before,WindowRequest window);
                    }
                    """.formatted(seek)));
            assertFalse(result.success(),seek);
            assertTrue(result.diagnosticsText().contains("@CacheSeek"),result.diagnosticsText());
        }
    }

    @Test
    void jobMethodsGenerateDirectTypedCallsAndCodecProviders() throws Exception {
        Compilation result = compile(
                source("jobs/Command.java", """
                        package jobs;
                        @com.reactor.cachedb.annotations.CacheJsonRecord
                        public record Command(String id) {}
                        """),
                source("jobs/Worker.java", """
                        package jobs;
                        public class Worker {
                          @com.reactor.cachedb.annotations.CacheJobHandler("refresh.catalog")
                          public String refresh(com.reactor.cachedb.spring.boot.CacheDistributedJobContext context, Command command) {
                            return command.id();
                          }
                        }
                        """));
        assertTrue(result.success(), result.diagnosticsText());
        String generated = Files.readString(result.generated().resolve("jobs/Worker_refreshCacheJobAutoConfiguration.java"));
        assertTrue(generated.contains("target.refresh(context, arguments)"));
        assertTrue(generated.contains("CommandJsonCodec.INSTANCE"));
        assertFalse(generated.contains("Method.invoke"));
        assertTrue(Files.readString(result.classes().resolve("META-INF/services/com.reactor.cachedb.core.codec.GeneratedJsonCodecProvider"))
                .contains("jobs.CommandJsonCodecProvider"));
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{result.classes().toUri().toURL()}, getClass().getClassLoader())) {
            Class<?> type = loader.loadClass("jobs.Command");
            org.junit.jupiter.api.Assertions.assertSame(com.reactor.cachedb.core.codec.GeneratedJsonCodecs.require(type),
                    com.reactor.cachedb.core.codec.GeneratedJsonCodecs.require(type));
        }
    }

    @Test
    void jobMethodsRejectMissingExplicitSerializationContracts() throws Exception {
        Compilation result = compile(source("jobs/Bad.java", """
                package jobs;
                public class Bad {
                  @com.reactor.cachedb.annotations.CacheJobHandler("bad")
                  public void run(String payload) {}
                }
                """));
        assertFalse(result.success());
        assertTrue(result.diagnosticsText().contains("@CacheJsonRecord or an explicit"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void wideEntityActuallyCompilesAndRoundTripsAtTheAbsoluteLimit() throws Exception {
        StringBuilder fields = new StringBuilder("@com.reactor.cachedb.annotations.CacheId(column=\"id\") public Long id;\n");
        for (int i = 1; i < 4096; i++) fields.append("@com.reactor.cachedb.annotations.CacheColumn(\"c").append(i)
                .append("\") public String c").append(i).append(";\n");
        Compilation result = compile(source("wide/Row.java", "package wide; @com.reactor.cachedb.annotations.CacheEntity(table=\"wide_row\",redisNamespace=\"wide\") public class Row {" + fields + "}"));
        assertTrue(result.success(), result.diagnosticsText());
        assertTrue(result.diagnosticsText().contains("more than 1000 columns"));
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{result.classes().toUri().toURL()}, getClass().getClassLoader())) {
            var type = loader.loadClass("wide.Row");
            var binding = loader.loadClass("wide.RowCacheBinding");
            var codec = (com.reactor.cachedb.core.model.EntityCodec<Object>) binding.getField("CODEC").get(null);
            Object value = type.getConstructor().newInstance();
            type.getField("id").set(value, 42L);
            type.getField("c4095").set(value, "last");
            Object decoded = codec.fromRedisValue(codec.toRedisValue(value));
            org.junit.jupiter.api.Assertions.assertEquals(42L, type.getField("id").get(decoded));
            org.junit.jupiter.api.Assertions.assertEquals("last", type.getField("c4095").get(decoded));
            org.junit.jupiter.api.Assertions.assertEquals("last", type.getField("c4095").get(codec.fromColumns(codec.toColumns(value))));
            org.junit.jupiter.api.Assertions.assertNotNull(loader.loadClass("wide.RowFields").getField("c4095").get(null));
            assertTrue(codec.directColumnAccess());
            org.junit.jupiter.api.Assertions.assertEquals("last", codec.entityColumnValue(value, "c4095"));
            org.junit.jupiter.api.Assertions.assertNotNull(loader.loadClass("wide.RowCacheBinding$Columns").getField("C4095").get(null));
            Object duplicate = codec.fromRedisValue("2:id7:invalid2:id2:42");
            org.junit.jupiter.api.Assertions.assertEquals(42L, type.getField("id").get(duplicate));
        }
    }

    @Test
    void entityBeyondTheAbsoluteLimitGetsAnExplicitDiagnostic() throws Exception {
        StringBuilder fields = new StringBuilder("@com.reactor.cachedb.annotations.CacheId(column=\"id\") public Long id;\n");
        for (int i = 1; i <= 4096; i++) fields.append("@com.reactor.cachedb.annotations.CacheColumn(\"c").append(i)
                .append("\") public String c").append(i).append(";\n");
        Compilation result = compile(source("wide/TooWide.java", "package wide; @com.reactor.cachedb.annotations.CacheEntity(table=\"wide_row\",redisNamespace=\"wide\") public class TooWide {" + fields + "}"));
        assertFalse(result.success());
        assertTrue(result.diagnosticsText().contains("at most 4096 persisted fields"), result.diagnosticsText());
    }

    @Test
    void rejectsRecordsWhoseConstructorCannotFitJvmSlots() throws Exception {
        StringBuilder fields = new StringBuilder("@com.reactor.cachedb.annotations.CacheId(column=\"id\") Long id");
        for (int i = 1; i < 255; i++) fields.append(", @com.reactor.cachedb.annotations.CacheColumn(\"c")
                .append(i).append("\") String c").append(i);
        var result = compile(source("wide/WideRecord.java", "package wide; @com.reactor.cachedb.annotations.CacheEntity(table=\"wide\", redisNamespace=\"wide\") public record WideRecord(" + fields + ") {}"));
        assertFalse(result.success());
        assertTrue(result.diagnosticsText().contains("JVM 255-slot limit"), result.diagnosticsText());
    }

    @Test
    void typedColumnsAndExplicitSparseContractCompileWithoutRemovingLegacyFields() throws Exception {
        var result = compile(source("typed/Product.java", """
                package typed;
                import com.reactor.cachedb.annotations.*;
                @CacheEntity(table="products", indexedColumns={"status"})
                public record Product(@CacheId(column="product_id") Long productId,
                        @CacheColumn("status") String status, @CacheColumn("description") String description) {}
                """), source("typed/Usage.java", """
                package typed;
                import com.reactor.cachedb.core.query.*;
                public class Usage {
                    QueryFilter filter = ProductCacheBinding.Columns.STATUS.eq("ACTIVE");
                    QuerySort sort = ProductCacheBinding.Columns.PRODUCT_ID.desc();
                }
                """));
        assertTrue(result.success(), result.diagnosticsText());
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{result.classes().toUri().toURL()}, getClass().getClassLoader())) {
            var binding = loader.loadClass("typed.ProductCacheBinding");
            var metadata = (com.reactor.cachedb.core.model.EntityMetadata<?, ?>) binding.getField("METADATA").get(null);
            org.junit.jupiter.api.Assertions.assertEquals(java.util.Set.of("status"), metadata.indexedColumns());
            var codec = (com.reactor.cachedb.core.model.EntityCodec<Object>) binding.getField("CODEC").get(null);
            Object row = loader.loadClass("typed.Product").getConstructor(Long.class, String.class, String.class).newInstance(1L, "ACTIVE", "text");
            org.junit.jupiter.api.Assertions.assertEquals("text", codec.entityColumnValue(row, "description"));
            assertTrue(new com.reactor.cachedb.core.query.QueryEvaluator().matches(row, codec,
                    com.reactor.cachedb.core.query.QueryFilter.eq("status", "ACTIVE")));
        }
        var invalid = compile(source("invalid/BadIndex.java", """
                package invalid;
                @com.reactor.cachedb.annotations.CacheEntity(table="bad", indexedColumns={"typo"})
                public record BadIndex(@com.reactor.cachedb.annotations.CacheId(column="id") Long id) {}
                """));
        assertFalse(invalid.success());
        assertTrue(invalid.diagnosticsText().contains("Unknown indexed column"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void declarativePatchIsTypedAndPreservesUnchangedColumns() throws Exception {
        var entity = source("patch/Product.java", """
                package patch;
                import com.reactor.cachedb.annotations.*;
                @CacheEntity(table="products")
                public record Product(@CacheId(column="id") Long id,
                        @CacheColumn("stock_quantity") int stock, @CacheColumn("updated_at") long updatedAt,
                        @CacheColumn("name") String name) {}
                """);
        var result = compile(entity, source("patch/Products.java", """
                package patch;
                import com.reactor.cachedb.annotations.*;
                import com.reactor.cachedb.core.repository.*;
                import com.reactor.cachedb.core.model.WriteReceipt;
                @CacheRepository(entity=Product.class)
                public interface Products extends CacheDbRepository<Product, Long> {
                    @CachePatch WriteReceipt<Product, Long> changeStock(@CacheId Long id,
                            @CacheColumn("stock_quantity") int stock, long updatedAt);
                }
                """));
        assertTrue(result.success(), result.diagnosticsText());
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{result.classes().toUri().toURL()}, getClass().getClassLoader())) {
            var memory = new com.reactor.cachedb.core.testing.InMemoryCacheDatabase();
            Object repository = loader.loadClass("patch.ProductsCacheDbImplementation")
                    .getConstructor(com.reactor.cachedb.core.api.CacheSession.class).newInstance(memory);
            var base = (com.reactor.cachedb.core.repository.CacheDbRepository<Object, Long>) repository;
            var type = loader.loadClass("patch.Product");
            base.save(type.getConstructor(Long.class, int.class, long.class, String.class).newInstance(1L, 20, 1L, "Şehir"));
            var receipt = (com.reactor.cachedb.core.model.WriteReceipt<?, ?>) repository.getClass()
                    .getMethod("changeStock", Long.class, int.class, long.class).invoke(repository, 1L, 30, 2L);
            org.junit.jupiter.api.Assertions.assertEquals(com.reactor.cachedb.core.model.OperationType.PATCH, receipt.operationType());
            Object changed = receipt.entity();
            org.junit.jupiter.api.Assertions.assertEquals(30, type.getMethod("stock").invoke(changed));
            org.junit.jupiter.api.Assertions.assertEquals("Şehir", type.getMethod("name").invoke(changed));
        }
        for (String declaration : List.of("@CacheColumn(\"typo\") int stock", "@CacheColumn(\"stock_quantity\") String stock",
                "@CacheColumn(\"id\") Long stock")) {
            var invalid = compile(entity, source("patch/Invalid.java", """
                    package patch;
                    import com.reactor.cachedb.annotations.*;
                    import com.reactor.cachedb.core.repository.*;
                    @CacheRepository(entity=Product.class)
                    public interface Invalid extends CacheDbRepository<Product, Long> {
                        @CachePatch void invalid(@CacheId Long id, %s);
                    }
                    """.formatted(declaration)));
            assertFalse(invalid.success());
            assertTrue(invalid.diagnosticsText().contains("Invalid @CacheColumn PATCH"), invalid.diagnosticsText());
        }
    }

    @Test void generatesTierOneDeclarationsWithoutRuntimeReflection() throws Exception {
        var result = compile(source("tier/Product.java", """
                package tier;
                import com.reactor.cachedb.annotations.*;
                @CacheEntity(table="products", indexedColumns={"stock"}, compressionThresholdBytes=4096)
                public record Product(@CacheId(column="id") Long id, @CacheColumn("stock") int stock,
                        @CacheColumn("amount") java.math.BigDecimal amount,
                        @CacheColumn("tenant_id") @CacheTenantId String tenant,
                        @CacheColumn("status") String status) {}
                """), source("tier/Products.java", """
                package tier;
                import com.reactor.cachedb.annotations.*;
                import com.reactor.cachedb.core.repository.CacheDbRepository;
                @CacheRepository(entity=Product.class)
                public interface Products extends CacheDbRepository<Product, Long> {
                    @CachePatch boolean reserve(@CacheId Long id, @CacheDelta(value="stock",min=0) int delta);
                    long countByStatus(String status);
                    @CacheAggregate(function=com.reactor.cachedb.core.query.AggregateFunction.SUM, field="amount")
                    java.math.BigDecimal sumAmountByStatus(String status);
                }
                """), source("tier/Monitor.java", """
                package tier;
                import com.reactor.cachedb.annotations.CacheWatcher;
                import com.reactor.cachedb.core.watch.CacheChangeEvent;
                public class Monitor {
                    @CacheWatcher(entity=Product.class,filterField="status",filterEquals="HIGH_RISK")
                    public void risk(CacheChangeEvent<Product,Long> event) {}
                }
                """));
        assertTrue(result.success(), result.diagnosticsText());
        String binding = Files.readString(result.generated().resolve("tier/ProductCacheBinding.java"));
        assertTrue(binding.contains("deeplyImmutable() { return true; }"));
        assertTrue(binding.contains("tenantFieldName()"));
        assertTrue(binding.contains("4096"));
        String repository = Files.readString(result.generated().resolve("tier/ProductsCacheDbImplementation.java"));
        assertTrue(repository.contains("PatchDeltaSpec")); assertTrue(repository.contains("hotRepository.aggregate("));
        String watcher = Files.readString(result.generated().resolve("tier/Monitor_riskCacheWatcherAutoConfiguration.java"));
        assertTrue(watcher.contains("target::risk")); assertFalse(watcher.contains("reflect"));
    }

    @Test void rejectsUnsafeDeltaAndTenantDeclarations() throws Exception {
        for (String delta : List.of("@CacheDelta(value=\"name\") String value", "@CacheDelta(value=\"stock\",min=5,max=1) int value")) {
            var result = compile(source("invalid/Product.java", """
                    package invalid; import com.reactor.cachedb.annotations.*;
                    @CacheEntity(table="products") public record Product(@CacheId(column="id") Long id,
                        @CacheColumn("stock") int stock, @CacheColumn("name") String name) {}
                    """), source("invalid/Products.java", """
                    package invalid; import com.reactor.cachedb.annotations.*;
                    @CacheRepository(entity=Product.class) public interface Products extends com.reactor.cachedb.core.repository.CacheDbRepository<Product,Long> {
                        @CachePatch void change(@CacheId Long id, %s);
                    }
                    """.formatted(delta)));
            assertFalse(result.success()); assertTrue(result.diagnosticsText().contains("@CacheDelta"), result.diagnosticsText());
        }
    }

    @Test void facetsCompositeMetadataAndSoftTtlAreGeneratedWithoutRuntimeReflection() throws Exception {
        var result = compile(source("facet/Ticket.java", """
                package facet; import com.reactor.cachedb.annotations.*;
                @CacheEntity(table="tickets", indexedColumns={"status","active","amount"}, independentWriteReordering=true)
                @CacheCompositeIndex(columns={"status","active"})
                @CacheNearPolicy(maxEntries=32,ttlSeconds=4,maxBytes=65536,softTtlSeconds=1)
                public record Ticket(@CacheId(column="id") Long id, @CacheColumn("status") String status,
                    @CacheColumn("active") boolean active, @CacheColumn("amount") java.math.BigDecimal amount) {}
                """), source("facet/Tickets.java", """
                package facet; import com.reactor.cachedb.annotations.*;
                import com.reactor.cachedb.core.query.*;
                @CacheRepository(entity=Ticket.class)
                public interface Tickets extends com.reactor.cachedb.core.repository.CacheDbRepository<Ticket,Long> {
                    @CacheFacet(groupBy="status") java.util.Map<String,Long> totals();
                    @CacheFacet(groupBy="status",function=AggregateFunction.SUM,field="amount") java.util.Map<String,java.math.BigDecimal> totalsByActive(boolean active);
                    @CacheFacet(groupBy="status") java.util.Map<String,AggregateResult> findAllByActiveTrue();
                }
                """));
        assertTrue(result.success(), result.diagnosticsText());
        String binding = Files.readString(result.generated().resolve("facet/TicketCacheBinding.java"));
        assertTrue(binding.contains("compositeExactIndexes()")); assertTrue(binding.contains("independentWriteReordering()"));
        assertTrue(binding.contains("65536L, 1"));
        String repository = Files.readString(result.generated().resolve("facet/TicketsCacheDbImplementation.java"));
        assertTrue(repository.contains("hotRepository.aggregateBy(")); assertTrue(repository.contains("longValueExact()"));
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{result.classes().toUri().toURL()}, getClass().getClassLoader())) {
            var memory = new com.reactor.cachedb.core.testing.InMemoryCacheDatabase();
            var impl = loader.loadClass("facet.TicketsCacheDbImplementation");
            Object instance = impl.getConstructor(com.reactor.cachedb.core.api.CacheSession.class).newInstance(memory);
            Object row = loader.loadClass("facet.Ticket").getConstructor(Long.class,String.class,boolean.class,java.math.BigDecimal.class)
                    .newInstance(1L,"OPEN",true,new java.math.BigDecimal("12.50"));
            @SuppressWarnings("unchecked") var typed = (com.reactor.cachedb.core.repository.CacheDbRepository<Object,Long>) instance;
            typed.save(row);
            org.junit.jupiter.api.Assertions.assertEquals(java.util.Map.of("OPEN",1L), impl.getMethod("totals").invoke(instance));
            org.junit.jupiter.api.Assertions.assertEquals(java.util.Map.of("OPEN",new java.math.BigDecimal("12.50")), impl.getMethod("totalsByActive",boolean.class).invoke(instance,true));
        }
    }
    @Test void invalidCompositeDeclarationsAndUnindexedFacetsFailCompilation() throws Exception {
        for (String columns : List.of("{\"status\"}", "{\"status\",\"missing\"}", "{\"status\",\"status\"}", "{\"id\",\"status\",\"a\",\"b\",\"c\"}")) {
            var result = compile(source("bad/Row.java", """
                    package bad; import com.reactor.cachedb.annotations.*;
                    @CacheEntity(table="rows") @CacheCompositeIndex(columns=%s)
                    public record Row(@CacheId(column="id") Long id,@CacheColumn("status") String status) {}
                    """.formatted(columns)));
            assertFalse(result.success());
        }
        var result = compile(source("bad/Row.java", """
                package bad; import com.reactor.cachedb.annotations.*;
                @CacheEntity(table="rows",indexedColumns={"id"})
                public record Row(@CacheId(column="id") Long id,@CacheColumn("status") String status) {}
                """), source("bad/Rows.java", """
                package bad; import com.reactor.cachedb.annotations.*;
                @CacheRepository(entity=Row.class) public interface Rows extends com.reactor.cachedb.core.repository.CacheDbRepository<Row,Long> {
                    @CacheFacet(groupBy="status") java.util.Map<String,Long> totals();
                }
                """));
        assertFalse(result.success()); assertTrue(result.diagnosticsText().contains("indexed"));
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
                    new CacheScheduledWarmProcessor(),
                    new CacheJsonRecordProcessor(),
                    new CacheJobHandlerProcessor()
            ));
            boolean success = Boolean.TRUE.equals(task.call());
            return new Compilation(success, generated, classes, diagnostics.getDiagnostics());
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
            Path classes,
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
