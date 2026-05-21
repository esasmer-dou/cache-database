# Use Case Examples

Turkish version: [../tr/docs/use-case-examples.md](../tr/docs/use-case-examples.md)

This page explains CacheDB behavior through practical use cases. It is written
for application developers who need to decide when to use an entity repository,
a projection repository, a command route, or a PostgreSQL cold path.

The examples use a common customer/order domain:

```text
customers
- customer_id
- tax_number
- customer_type
- status

orders
- order_id
- customer_id
- order_date
- order_amount
- currency_code
- order_type
- status
```

The production rule is simple:

- Redis is the hot read/write acceleration layer.
- PostgreSQL is the durable source of truth and the right place for full history.
- Full entity reads must stay small and bounded.
- Large list screens must use projection/read-model windows.
- Partial changes must use explicit command routes or full entity updates.

## Route Decision Map

| Need | BEST route | Why |
| --- | --- | --- |
| Create or replace one full entity | `EntityRepository.save(fullEntity)` | Redis-first write, PostgreSQL write-behind durability |
| Read one hot entity by id | `EntityRepository.findById(id)` | Fast Redis lookup |
| Read a small full-entity page | `EntityRepository.findPage(PageWindow)` | Bounded full payload read |
| Read a large customer order list | `ProjectionRepository<OrderSummary>` | Summary payload, bounded hot window |
| Read global top-N dashboard rows | Ranked projection | Single ranked index path |
| Read old history or archive data | PostgreSQL cold path | Do not pollute Redis with cold history |
| Change one field on an entity that may not be in Redis | Explicit command route | Avoid writing partial/invalid Redis entities |
| Delete one entity | `deleteById(id)` | Idempotent delete, tombstone, PostgreSQL delete through write-behind |

## Minimal Entity Shape

Example entity definitions are intentionally small. In a real application, keep
entities explicit and avoid hiding relation loading behind implicit lazy loading.

```java
@CacheEntity(table = "customers", redisNamespace = "customers")
public class CustomerEntity {
    @CacheId(column = "customer_id")
    public Long customerId;

    @CacheColumn("tax_number")
    public String taxNumber;

    @CacheColumn("customer_type")
    public String customerType;

    @CacheColumn("status")
    public String status;
}
```

```java
@CacheEntity(table = "orders", redisNamespace = "orders")
public class OrderEntity {
    @CacheId(column = "order_id")
    public Long orderId;

    @CacheColumn("customer_id")
    public Long customerId;

    @CacheColumn("order_date")
    public Instant orderDate;

    @CacheColumn("order_amount")
    public BigDecimal orderAmount;

    @CacheColumn("currency_code")
    public String currencyCode;

    @CacheColumn("order_type")
    public String orderType;

    @CacheColumn("status")
    public String status;
}
```

## More Real-World Entity Models

Customer/order is only one route. Real systems usually have several
one-to-many relationships. The same rule applies to all of them: keep the root
entity small, keep preview relations bounded, and use projections for large
lists.

### Model A: Order -> Order Lines

```text
orders
- order_id
- customer_id
- order_date
- status

order_lines
- line_id
- order_id
- product_id
- quantity
- unit_price
- line_amount
```

BEST:

- `OrderEntity` is the root for order detail.
- `OrderLineEntity` is a child entity.
- The first order detail screen loads only the first 8 or 20 lines as preview.
- The full line list is loaded only when the user opens the line detail tab.

```java
Optional<OrderEntity> order =
        OrderEntityCacheBinding.using(session)
                .repository()
                .withRelationLimit("orderLines", 8)
                .findById(orderId);
```

ANTI-PATTERN: load every order line for every order row in an order list page.

### Model B: Product -> Inventory Movements

```text
products
- product_id
- sku
- product_name
- status

inventory_movements
- movement_id
- product_id
- movement_time
- movement_type
- quantity_delta
- warehouse_id
```

BEST:

- `ProductEntity` can stay hot in Redis.
- Latest stock summary should be a projection.
- Full inventory movement history should stay in PostgreSQL.
- Dashboard widgets read a compact `ProductStockSummary` projection.

```java
ProjectionRepository<ProductStockSummary, Long> stockSummary =
        ProductEntityCacheBinding.using(session).projections().stockSummary();

List<ProductStockSummary> lowStock = stockSummary.query(
        QuerySpec.where(QueryFilter.lt("available_quantity", 10))
                .orderBy(QuerySort.asc("available_quantity"))
                .limitTo(50)
);
```

ANTI-PATTERN: read thousands of inventory movement rows into Redis to calculate
the current stock on every request.

### Model C: Invoice -> Payments

```text
invoices
- invoice_id
- customer_id
- invoice_date
- total_amount
- payment_status

payments
- payment_id
- invoice_id
- paid_at
- paid_amount
- payment_method
- payment_status
```

BEST:

- `InvoiceEntity` is a detail entity.
- `PaymentEntity` is a child entity.
- The invoice screen can show the last few payment attempts as preview.
- Accounting reports should use PostgreSQL or a dedicated reporting projection.

```java
EntityRepository<InvoiceEntity, Long> invoices =
        InvoiceEntityCacheBinding.using(session)
                .repository()
                .withRelationLimit("payments", 5);

Optional<InvoiceEntity> invoice = invoices.findById(invoiceId);
```

ANTI-PATTERN: use CacheDB entity reads for large end-of-month accounting
aggregation.

### Model D: Shipment -> Shipment Events

```text
shipments
- shipment_id
- order_id
- carrier_code
- current_status

shipment_events
- event_id
- shipment_id
- event_time
- event_type
- location
```

BEST:

- Current shipment status can be hot in Redis.
- Latest event preview can be a bounded relation or projection.
- Full tracking history can remain in PostgreSQL unless the tracking screen is
  extremely hot.

```java
ProjectionRepository<ShipmentTimelinePreview, Long> timeline =
        ShipmentEntityCacheBinding.using(session).projections().timelinePreview();

List<ShipmentTimelinePreview> latestEvents = timeline.query(
        QuerySpec.where(QueryFilter.eq("shipment_id", shipmentId))
                .orderBy(QuerySort.desc("event_time"))
                .limitTo(10)
);
```

ANTI-PATTERN: keep every tracking event for every shipment in Redis forever.

### Model E: Support Ticket -> Messages

```text
support_tickets
- ticket_id
- customer_id
- priority
- status
- opened_at

ticket_messages
- message_id
- ticket_id
- sender_type
- message_time
- body
```

BEST:

- Ticket inbox screens should use `TicketSummaryProjection`.
- Ticket detail should load the root ticket and the latest message preview.
- Full message history can be paged from PostgreSQL if it is old or rarely read.

```java
ProjectionRepository<TicketSummary, Long> ticketSummaries =
        SupportTicketEntityCacheBinding.using(session).projections().ticketSummary();

List<TicketSummary> urgentOpenTickets = ticketSummaries.query(
        QuerySpec.where(QueryFilter.eq("status", "OPEN"))
                .orderBy(QuerySort.desc("priority_score"), QuerySort.asc("opened_at"))
                .limitTo(25)
);
```

ANTI-PATTERN: decode every ticket message body just to show an inbox row.

### Model F: Customer -> Addresses, Orders, Tickets

A customer often has several child collections.

```text
customer
  -> addresses
  -> orders
  -> support_tickets
```

BEST:

- Customer profile reads the root customer entity.
- Address list can be a small bounded relation if it is naturally small.
- Orders use order-summary projection.
- Support tickets use ticket-summary projection.

```java
Optional<CustomerEntity> customer =
        CustomerEntityCacheBinding.using(session).repository().findById(customerId);

List<OrderSummaryReadModel> latestOrders = orderSummaryRepository.query(
        QuerySpec.where(QueryFilter.eq("customer_id", customerId))
                .orderBy(QuerySort.desc("order_date"))
                .limitTo(10)
);

List<TicketSummary> tickets = ticketSummaryRepository.query(
        QuerySpec.where(QueryFilter.eq("customer_id", customerId))
                .orderBy(QuerySort.desc("opened_at"))
                .limitTo(10)
);
```

ANTI-PATTERN: one customer endpoint that hydrates customer, all addresses, all
orders, all order lines, all tickets, and all ticket messages in one response.

## Expanded Route Examples

### Relation Example 1: Product Detail Page

User opens a product detail page.

BEST route:

- `ProductEntity.findById(productId)` for stable product fields.
- `ProductStockSummary` projection for current stock.
- PostgreSQL cold path for old inventory movement history.

Why: product detail needs a small entity and one compact summary, not the full
inventory ledger.

### Relation Example 2: Invoice Detail Page

User opens one invoice.

BEST route:

- `InvoiceEntity.findById(invoiceId)` for invoice header.
- `withRelationLimit("payments", 5)` for recent payment attempts.
- PostgreSQL for full payment audit if the user opens the audit tab.

Why: recent attempts are useful on first paint; full payment history is not.

### Relation Example 3: Shipment Tracking Page

User opens tracking for one shipment.

BEST route:

- current shipment status from Redis.
- latest 10 timeline events from projection.
- full carrier history from PostgreSQL only when requested.

Why: tracking first paint is a bounded timeline, not a full archive export.

### Relation Example 4: Support Inbox

Agent opens the support inbox.

BEST route:

- `TicketSummaryProjection` sorted by priority and open time.
- no message body hydration in the inbox.
- ticket detail loads latest message preview.

Why: inbox rows need summary fields, not the full conversation body.

### Relation Example 5: Admin Customer 360 Screen

User opens a "customer 360" admin screen.

BEST route:

- root `CustomerEntity`.
- latest 10 order summaries.
- latest 10 ticket summaries.
- active invoices summary.
- explicit tabs for full order, ticket, invoice, and audit history.

Why: a wide 360 screen must be summary-first. Full aggregate hydration will
become slower as child counts grow.

## Insert Examples

### Insert 1: Create A Customer

Use this when the request contains the full customer state.

```java
CustomerEntity customer = new CustomerEntity();
customer.customerId = 42L;
customer.taxNumber = "1234567890";
customer.customerType = "CORPORATE";
customer.status = "ACTIVE";

CustomerEntityCacheBinding.using(session).repository().save(customer);
```

Runtime behavior:

- Redis receives the customer payload first.
- Query indexes and hot-set tracking are updated.
- A write-behind event is written to Redis Stream.
- The write-behind worker flushes the row to PostgreSQL.

BEST: use `save(fullEntity)` when you can construct the full valid entity.

### Insert 2: Create An Order For A Customer

Use this when a new order is created and all required fields are known.

```java
OrderEntity order = new OrderEntity();
order.orderId = 9001L;
order.customerId = 42L;
order.orderDate = Instant.now();
order.orderAmount = new BigDecimal("1250.00");
order.currencyCode = "TRY";
order.orderType = "ONLINE";
order.status = "CREATED";

OrderEntityCacheBinding.using(session).repository().save(order);
```

Runtime behavior:

- The order becomes hot in Redis immediately.
- PostgreSQL durability follows through write-behind.
- Registered projections can be refreshed so list screens see the new order.

BEST: create orders as full entities, not as partial payloads.

### Insert 3: Create Through A Generated Command Helper

Use generated command helpers when your entity has a stable application command.

```java
UserEntity activated =
        GeneratedCacheModule.using(session)
                .users()
                .commands()
                .activateUser(84L, "alice");
```

Runtime behavior:

- The generated command builds a valid entity.
- The generated helper calls the repository `save(...)`.
- The write path stays Redis-first.

BEST: use command helpers for common create/update operations that always
produce a complete valid entity.

### Insert 4: Bulk Seed Or Warm Data

Use this for controlled startup, staging warm-up, or migration rehearsal.

```java
for (OrderEntity order : ordersToWarm) {
    OrderEntityCacheBinding.using(session).repository().save(order);
}
```

Runtime behavior:

- Redis is filled with the selected hot set.
- PostgreSQL writes still go through the normal write-behind path.
- If this is a migration warm-up, prefer the Migration Planner warm runner so
  the hot set is based on a planned route.

BEST: warm only the routes you plan to serve from Redis.

ANTI-PATTERN: blindly load the whole database into Redis.

### Insert 5: Create A Projection Source Entity

A projection is not usually inserted directly by application code. You write the
base entity, then CacheDB refreshes the projection.

```java
OrderEntity order = createOrder(...);
OrderEntityCacheBinding.using(session).repository().save(order);

ProjectionRepository<OrderSummaryReadModel, Long> summaries =
        OrderEntityCacheBinding.using(session).projections().orderSummary();
```

Runtime behavior:

- The base entity write is the source event.
- The projection receives a compact read-model payload.
- List screens read summaries instead of full orders.

BEST: write base entities, read list screens from projections.

ANTI-PATTERN: write separate summary rows by hand and let them drift from the
base entity.

## Read And Query Examples

### Query 1: Read A Hot Customer By Id

Use this for detail screens where the root entity is expected to be hot.

```java
Optional<CustomerEntity> customer =
        CustomerEntityCacheBinding.using(session)
                .repository()
                .findById(42L);
```

Runtime behavior:

- CacheDB checks Redis for `customer_id=42`.
- If the payload is present and not tombstoned, it returns the entity.
- If it is absent, this repository call does not magically scan PostgreSQL.

BEST: use `findById` for hot detail records.

ACCEPTABLE: if the record is old and missing from Redis, call an explicit
PostgreSQL cold-path repository.

### Query 2: Read Latest 10 Orders From A 1,000-Row Hot Projection Window

Use this when Redis keeps the latest 1,000 order summaries per customer, but the
screen only asks for 10.

```java
ProjectionRepository<OrderSummaryReadModel, Long> summaries =
        OrderEntityCacheBinding.using(session).projections().orderSummary();

List<OrderSummaryReadModel> latest10 = summaries.query(
        QuerySpec.where(QueryFilter.eq("customer_id", 42L))
                .orderBy(QuerySort.desc("order_date"), QuerySort.desc("order_id"))
                .limitTo(10)
);
```

Runtime behavior:

- CacheDB uses the projection repository, not the full `OrderEntity` repository.
- Redis may keep a 1,000-row hot summary window.
- The query returns only the requested 10 summaries.
- PostgreSQL is not needed for this hot list route.

BEST: large list window in Redis, small response to the user.

ANTI-PATTERN: query 10 full `OrderEntity` rows and expect CacheDB to guess that
an order-summary projection should be used.

### Query 3: Read A Small Full-Entity Page

Use this for small, bounded full entity lists.

```java
List<CustomerEntity> customers =
        CustomerEntityCacheBinding.using(session)
                .repository()
                .findPage(new PageWindow(0, 25));
```

Runtime behavior:

- The page request is checked by read-shape guardrails.
- If the page fits the configured safe limit, Redis page cache can be used.
- If a page loader is configured and read-through is enabled, the page can be
  loaded and cached.

BEST: keep page size below the entity hot window.

ANTI-PATTERN: use full entity pages for 1,000+ row business screens.

### Query 4: Read Old History From PostgreSQL Cold Path

Use this when the user asks for old data outside the hot projection window.

```java
List<OrderEntity> februaryOrders = jdbcTemplate.query(
        """
        SELECT *
        FROM orders
        WHERE customer_id = ?
          AND order_date >= ?
          AND order_date < ?
        ORDER BY order_date DESC, order_id DESC
        LIMIT ?
        """,
        orderRowMapper,
        42L,
        LocalDate.parse("2026-02-01"),
        LocalDate.parse("2026-03-01"),
        100
);
```

Runtime behavior:

- This route intentionally bypasses Redis.
- Redis hot windows are not polluted by cold archive reads.
- PostgreSQL remains the durable full-history store.

BEST: old history and audit screens use PostgreSQL or a purpose-built archive
read model.

ANTI-PATTERN: load every old archive read into Redis just because it was read
once.

### Query 5: Read A Relation Preview

Use this when a detail page needs only a small preview relation.

```java
EntityRepository<CustomerEntity, Long> repository =
        CustomerEntityCacheBinding.using(session)
                .repository()
                .withRelationLimit("orders", 8);

Optional<CustomerEntity> customer = repository.findById(42L);
```

Runtime behavior:

- The customer root is read as an entity.
- Only 8 related orders are loaded as a preview.
- The full order history remains outside the first paint.

BEST: summary first, explicit preview, detail on demand.

ANTI-PATTERN: eager-load every child row for the first screen.

### Query 6: Explain Before You Cut Over

Use `explain(...)` when deciding whether a route is safe.

```java
QuerySpec query = QuerySpec.where(QueryFilter.eq("customer_id", 42L))
        .orderBy(QuerySort.desc("order_date"))
        .limitTo(100);

QueryExplainPlan plan =
        OrderEntityCacheBinding.using(session).repository().explain(query);
```

Runtime behavior:

- The plan shows the route shape and warnings.
- Use it to catch risky entity reads before production cutover.

BEST: explain and compare important routes in staging.

## Update Examples

### Update 1: Replace A Hot Entity With Full State

Use this when the application has the full current state.

```java
OrderEntity order = existingOrder;
order.status = "PAID";
order.orderAmount = new BigDecimal("1250.00");

OrderEntityCacheBinding.using(session).repository().save(order);
```

Runtime behavior:

- Redis receives the full replacement payload.
- Indexes and projections are refreshed from the valid full state.
- PostgreSQL is updated through write-behind.

BEST: full entity update when you can build a complete valid entity.

### Update 2: Update An Old Entity That Is Not In Redis

Use this only if the request still contains the full valid entity.

```java
OrderEntity order = loadOrderFromTrustedCommandPayload();
order.orderId = 500L;
order.customerId = 42L;
order.orderDate = Instant.parse("2026-02-10T10:15:30Z");
order.orderAmount = new BigDecimal("1000.00");
order.currencyCode = "TRY";
order.orderType = "ONLINE";
order.status = "CANCELLED";

OrderEntityCacheBinding.using(session).repository().save(order);
```

Runtime behavior:

- Redis does not need the old payload to exist.
- CacheDB writes the new complete state.
- PostgreSQL receives the update through write-behind.

BEST: old record update is safe when the new full state is supplied.

ANTI-PATTERN: send only `order_id` and `status` through `save(...)`.

### Update 3: Partial Status Change Through A Command Route

Use this when only one field changes and the entity may not be in Redis.

```java
cancelOrderCommandHandler.cancelOrder(500L, "CUSTOMER_REQUEST");
```

A safe command route can do this:

```sql
UPDATE orders
SET status = 'CANCELLED'
WHERE order_id = :order_id;
```

Redis behavior:

- If the order is hot in Redis, invalidate or refresh the entity/projection.
- If the order is not in Redis, do not create a partial Redis entity.
- Optionally write an invalidation marker so stale cache fill cannot resurrect
  an old view.

BEST: partial updates are commands, not partial entity saves.

### Update 4: Change A Customer Type

Use full state if the customer entity is small and known.

```java
CustomerEntity customer =
        CustomerEntityCacheBinding.using(session).repository()
                .findById(42L)
                .orElseThrow();

customer.customerType = "VIP";

CustomerEntityCacheBinding.using(session).repository().save(customer);
```

Runtime behavior:

- The current customer was hot and read from Redis.
- The updated full entity is written back.
- PostgreSQL follows through write-behind.

ACCEPTABLE: read hot entity, mutate, save full entity.

ANTI-PATTERN: do this inside a loop for thousands of customers without batching
or a dedicated SQL command route.

### Update 5: Bulk Price Or Status Change

Use PostgreSQL batch SQL and then explicitly refresh or invalidate affected hot
routes.

```sql
UPDATE orders
SET status = 'EXPIRED'
WHERE status = 'CREATED'
  AND order_date < now() - interval '30 days';
```

Runtime behavior:

- PostgreSQL handles the bulk mutation.
- CacheDB should invalidate or rebuild affected projections.
- Do not load every affected order as a full entity just to call `save(...)`.

BEST: set-based SQL for bulk mutation, explicit cache/projection refresh after.

ANTI-PATTERN: N+1 update loop through Redis for a broad historical update.

## Delete Examples

### Delete 1: Delete A Hot Order

Use `deleteById` when deleting one known entity.

```java
OrderEntityCacheBinding.using(session).repository().deleteById(9001L);
```

Runtime behavior:

- Redis entity key is deleted.
- Hot-set and query index entries are removed.
- A tombstone is written to prevent stale resurrection.
- PostgreSQL delete is written through write-behind.

BEST: idempotent delete by primary key.

### Delete 2: Delete An Old Order That Is Not In Redis

Use the same delete call.

```java
OrderEntityCacheBinding.using(session).repository().deleteById(500L);
```

Runtime behavior:

- Missing Redis entity is not an error.
- CacheDB still writes the tombstone.
- PostgreSQL delete is still queued.
- If PostgreSQL already has no row, the operation should remain a safe no-op.

BEST: delete does not depend on the entity being hot.

### Delete 3: Delete A Customer And Its Orders

Do not rely on hidden cascade behavior in CacheDB.

```java
customerDeletionService.deleteCustomer(customerId);
```

A safe service orchestration:

```text
1. Stop new writes for the customer or make the operation idempotent.
2. Delete or soft-delete orders through a controlled command/batch route.
3. Delete the customer entity by id.
4. Invalidate customer order projections.
5. Verify PostgreSQL state.
```

BEST: explicit domain orchestration for aggregate-level deletes.

ANTI-PATTERN: delete the customer root and assume every child projection,
history row, and dashboard read-model is automatically correct.

### Delete 4: Soft Delete

Use a full entity update or command route when the row must remain in
PostgreSQL.

```java
OrderEntity order = loadFullOrderForSoftDelete(9001L);
order.status = "DELETED";

OrderEntityCacheBinding.using(session).repository().save(order);
```

Runtime behavior:

- Redis stores the new full state if the route should remain hot.
- Projections can remove or mark the row depending on screen requirements.
- PostgreSQL keeps the row.

BEST: soft delete is an update, not a physical delete.

### Delete 5: Delete By Query

Avoid broad delete-by-query through entity repositories.

ANTI-PATTERN:

```java
List<OrderEntity> oldOrders = orderRepository.query(
        QuerySpec.where(QueryFilter.lt("order_date", cutoff)).limitTo(10_000)
);

for (OrderEntity order : oldOrders) {
    orderRepository.deleteById(order.orderId);
}
```

BEST:

```sql
DELETE FROM orders
WHERE order_date < :cutoff
  AND status = 'ARCHIVED';
```

Then run a controlled projection rebuild or invalidation for affected screens.

Why:

- A broad historical delete is a database operation.
- Redis should not become the execution engine for large archive cleanup.
- CacheDB should only keep bounded hot windows and read models consistent.

## Projection And Read-Model Examples

### Projection 1: Customer Latest Orders

Use this for customer timeline screens.

```java
ProjectionRepository<OrderSummaryReadModel, Long> summaries =
        OrderEntityCacheBinding.using(session).projections().orderSummary();

List<OrderSummaryReadModel> rows = summaries.query(
        QuerySpec.where(QueryFilter.eq("customer_id", customerId))
                .orderBy(QuerySort.desc("order_date"), QuerySort.desc("order_id"))
                .limitTo(100)
);
```

BEST:

- Keep the latest 1,000 summaries per customer hot.
- Return only the requested page, such as 10, 25, or 100.
- Fetch full order detail only after the user selects one order.

### Projection 2: Order Detail With Line Preview

Use summary/detail split.

```java
Optional<OrderEntity> order =
        OrderEntityCacheBinding.using(session)
                .repository()
                .withRelationLimit("orderLines", 8)
                .findById(orderId);
```

BEST:

- First screen gets the order and 8 preview lines.
- Full line history is loaded only when the detail page asks for it.

ANTI-PATTERN: load every line for every order in the list screen.

### Projection 3: Dashboard Top Orders By Business Score

Use ranked projection when the screen is globally sorted.

```java
ProjectionRepository<HighValueOrderReadModel, Long> highValueOrders =
        OrderEntityCacheBinding.using(session).projections().highValueOrders();

List<HighValueOrderReadModel> top50 = highValueOrders.query(
        QuerySpec.builder()
                .sort(QuerySort.desc("rank_score"))
                .limit(50)
                .build()
);
```

BEST:

- Precompute `rank_score`.
- Mark the projection with `rankedBy("rank_score")`.
- Query a single ranked window.

ANTI-PATTERN: scan full order entities and sort in application memory.

### Projection 4: Reporting Snapshot

Use a read model for a frequently opened report.

```java
ProjectionRepository<CustomerRevenueSummary, Long> revenue =
        CustomerEntityCacheBinding.using(session).projections().customerRevenueSummary();

List<CustomerRevenueSummary> rows = revenue.query(
        QuerySpec.where(QueryFilter.eq("period", "2026-05"))
                .orderBy(QuerySort.desc("total_revenue"))
                .limitTo(100)
);
```

BEST:

- Use projection/read-model for operational reports that open frequently.
- Keep only the active reporting window hot.
- Keep raw historical facts in PostgreSQL.

### Projection 5: Existing ORM Route Migration

Use the Migration Planner before converting a production route.

```text
1. Discover PostgreSQL schema.
2. Pick root table and child table.
3. Generate entity and projection scaffold.
4. Run dry-run warm.
5. Run staging warm.
6. Run side-by-side comparison.
7. Cut over only when parity and latency are acceptable.
```

BEST: migrate route by route, not table by table.

ANTI-PATTERN: mark every table as a CacheDB entity and assume the whole
application is ready.

## Dashboard And Reporting Examples

### Dashboard 1: Latest Orders Widget

Use a projection with a small limit.

```java
List<OrderSummaryReadModel> latest = orderSummaryRepository.query(
        QuerySpec.builder()
                .sort(QuerySort.desc("order_date"))
                .limit(25)
                .build()
);
```

BEST: Redis projection for a frequently refreshed dashboard widget.

### Dashboard 2: Customer Risk Leaderboard

Use ranked projection.

```java
List<CustomerRiskReadModel> riskyCustomers = riskRepository.query(
        QuerySpec.builder()
                .sort(QuerySort.desc("risk_score"))
                .limit(50)
                .build()
);
```

BEST: precomputed score, bounded top-N read.

### Dashboard 3: Monthly Finance Report

Use PostgreSQL or a dedicated reporting table when the report scans a large
date range.

```sql
SELECT currency_code, sum(order_amount)
FROM orders
WHERE order_date >= :month_start
  AND order_date < :next_month
GROUP BY currency_code;
```

BEST: set-based PostgreSQL reporting for broad historical aggregation.

ANTI-PATTERN: pull all monthly orders into Redis and aggregate in Java.

### Dashboard 4: Audit Trail

Use PostgreSQL cold path for rare audit lookups.

```sql
SELECT *
FROM order_audit_events
WHERE order_id = :order_id
ORDER BY recorded_at DESC;
```

BEST: keep audit trail durable and queryable in PostgreSQL.

ACCEPTABLE: create a narrow audit projection only if the audit screen is hot
and has a stable bounded window.

### Dashboard 5: Operational Health Screen

Use CacheDB admin metrics for runtime health, not application business state.

Important signals:

- Redis used memory and maxmemory percentage.
- Write-behind backlog.
- Dead-letter backlog.
- Projection refresh failures.
- Read-shape guardrail failures.

BEST: separate operational telemetry from business reporting.

## Partial Update Behavior

Partial update is the most important place to avoid magic.

Assume this row exists in PostgreSQL but not in Redis:

```text
order_id = 500
customer_id = 42
order_date = 2026-02-10
order_amount = 1000
currency_code = TRY
order_type = ONLINE
status = CREATED
```

If the request only says:

```text
order_id = 500
status = CANCELLED
```

CacheDB must not create this Redis payload:

```text
order_id = 500
customer_id = null
order_date = null
order_amount = null
currency_code = null
order_type = null
status = CANCELLED
```

That would poison Redis and break indexes/projections.

BEST options:

- Send the full valid `OrderEntity` to `save(...)`.
- Use an explicit command route such as `CancelOrderCommand`.
- If Redis has the entity, update/invalidate it.
- If Redis does not have the entity, update PostgreSQL and do not create a
  partial Redis entity.

ANTI-PATTERN:

```java
OrderEntity partial = new OrderEntity();
partial.orderId = 500L;
partial.status = "CANCELLED";

orderRepository.save(partial);
```

## Memory And Hot Window Rules

CacheDB does not need the whole database in Redis.

BEST:

- Root entities that are frequently read stay hot.
- Large lists use bounded projections.
- Old history stays in PostgreSQL.
- Redis has `maxmemory` configured.
- CacheDB guardrails stop unsafe full-entity reads.

ANTI-PATTERN:

- Treat Redis as a full database mirror.
- Increase `hotEntityLimit` whenever a screen is slow.
- Use full entity reads for dashboard/reporting pages.
- Rely on Redis eviction policy to clean up poor read shapes.

## Final Checklist

Before adding a new CacheDB route, answer these questions:

- Is this a single entity, a small page, a large list, or a report?
- Is the data hot or old history?
- Does the screen need full entity payload or only summary fields?
- What is the max result size?
- Should this route use entity, projection, ranked projection, command, or
  PostgreSQL cold path?
- What happens when Redis does not contain the record?
- What happens when the operation is retried?
- How will projection parity be verified before cutover?

If the route cannot answer these questions, do not put it on the production hot
path yet.
