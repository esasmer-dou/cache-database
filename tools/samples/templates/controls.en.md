## CacheDB 0.18: Running Scenarios

This section uses real classes in this sample, not pseudocode. Start with the
existing Quick Start and `demo` profile. Maven downloads CacheDB **0.18.0**
without building the framework. Run the Postman collection
`postman/cache-database-{{PROVIDER}}-v018.postman_collection.json` in its saved order.

### 1. Setup, Identity and Data Flow

The demo initializes `schema.sql` and the provider-specific `schema-controls.sql`.
The two new tables are `sample_supplier_offers` and `sample_tenant_stock`.
For an existing database, review and apply the new provider DDL through your own
migration process before startup; do not enable demo initialization in production.
SQL Server/Oracle scripts use Spring's `@@` statement delimiter.

| Credential in the demo profile | Server-selected permission |
| --- | --- |
| `Bearer demo-supplier` | Supplier offer feed, its SQL source and bounded warm jobs |
| `Bearer demo-shop-a` | Tenant `shop-a` stock only |
| `Bearer demo-shop-b` | Tenant `shop-b` stock only |

These are public **demo-only credentials**, not production secrets. The routes
exist only with `sample.demo.write-tools-enabled=true`. The demo profile supplies
the three credentials; override them with `SAMPLE_CONTROLS_SUPPLIER_TOKEN`,
`SAMPLE_CONTROLS_TENANT_A_TOKEN` and `SAMPLE_CONTROLS_TENANT_B_TOKEN`.
The ordinary production profile does not enable these endpoints or supply tokens.
Replace this small credential adapter with your verified JWT/session principal
before adopting the example in a real application. A raw tenant header is never trusted.

| Operation | SQL and Redis behavior |
| --- | --- |
| PUT offer / tenant batch | Redis accepts first; HTTP 202 is **not SQL commit**. Workers persist asynchronously. |
| GET offer / stock / bulk | Reads the bounded active Redis set, with eligible L1 reuse. A miss does not run SQL. |
| GET `/offers/{id}/source` | Explicit SQL read; does not silently warm Redis. |
| POST `/offers/warm` | Explicit background SQL-to-Redis warm, filtered by supplier and limited to 1–100 rows. |
| Dashboard / projection | Covers the selected Redis set, not the full SQL history. |

For a SQL-only row, the intended sequence is: GET gives 404; source GET can return
the SQL row; GET is still 404; submit warm; poll `/api/warm/jobs/{jobId}` until
`COMPLETED`; then GET can return the warmed row. Choose a window that fits the
limit; this is not an unbounded full-table import. Offer reads are informational,
not a price-lock or stock-reservation guarantee.

### 2. Supplier Quotes: Bulk L1, Soft TTL and Composite Lookup

Use `PUT /api/examples/v018/offers/9600001` with the supplier credential:

```json
{"supplier":"postman-window","status":"ACTIVE","unitPrice":12.1250,"availableUnits":20}
```

The response includes `version`, `causalToken`, `redisAccepted=true` and
`sqlDurabilityConfirmed=false`. Send a complete offer to PUT; use
`PATCH /offers/{id}/units` with `{"availableUnits":25}` for that column only.
All paths in this section are relative to `/api/examples/v018`.

| Request | Expected behavior |
| --- | --- |
| GET `/offers?ids=9600001,9600002,9600001` | Preserves order and duplicate results. Fetches only unique L1 misses from Redis; absent IDs are omitted. |
| GET `/offers/search?supplier=postman-window&status=ACTIVE` | Uses the declared supplier/status composite equality index; at most 50 results. |
| PUT same ID with PAUSED status | Moves its composite membership; it no longer appears in the ACTIVE lookup. |
| Repeated point/bulk reads | May reuse the bounded L1. Frequency-aware eviction protects frequently read keys during cold scans. |

The offer policy reserves at most **256 L1 entries / 1 MiB logical payload budget
per JVM**, with **2 s soft TTL / 10 s hard TTL**. Between those times an old value
can be returned while a Redis-only refresh runs. The shared refresh pool has two
workers and 256 queued tasks, with deduplication and rejection/failure counters.
Hard expiry, Redis TTL and disconnect invalidation still apply. This is not a
guarantee of a fresh stock decision. Tenant stock deliberately keeps soft TTL off.

These budgets are not total JVM or Redis memory: frequency metadata, object
overhead, Redis entity/index/projection/version keys and write queues also consume
space. More application pods multiply L1 memory. Keep Redis command/pool timeouts
finite; tune policy from hit rate, refresh pressure, memory and real request data.

<details>
<summary>Complete supplier entity and generated repository declarations</summary>

`src/main/java/com/example/cachedb/sample/domain/SupplierOffer.java`
```java
{{CODE:src/main/java/com/example/cachedb/sample/domain/SupplierOffer.java}}
```

`src/main/java/com/example/cachedb/sample/repository/SupplierOfferRepository.java`
```java
{{CODE:src/main/java/com/example/cachedb/sample/repository/SupplierOfferRepository.java}}
```
</details>

### 3. Procurement Dashboard: Facets and Exact Money

Create two ACTIVE offers priced `12.1250` and `7.8750`, and one PAUSED offer priced
`1.0050`, all for `postman-window`. GET
`/offers/dashboard?supplier=postman-window` returns:

- `counts`: ACTIVE = 2, PAUSED = 1.
- `activePriceTotal`: 20.0000.
- `priceTotals`: totals per status.
- `decimalAverage`: DECIMAL128 average over this supplier's cached offers.

The active cache set is a retention concept, not the ACTIVE business status.
PAUSED offers can also be cached and counted. Keep decimal precision when parsing
money in a client; the price sums are not quantity-weighted inventory values.

`@CacheFacet` and `@CacheAggregate` generate direct repository calls.
For eligible count-window/index-covered scalar operations, the runtime processes
up to 512 candidates per read-only Lua call; multi-slot Cluster shapes use bounded
fallbacks. Additional index/bookkeeping calls can remain. Decimal arithmetic is
performed in Java, not Lua floating point. Facets have at most 256 groups and
candidate/byte budgets; overflow fails instead of returning incomplete totals.
These are operational figures over the cached set, not statutory financial reports.

### 4. Supplier Feed: Independent Mixed Writes

POST `/offers/changes` submits one bounded Redis transaction:

```json
{"putId":9600006,"put":{"supplier":"postman-window","status":"ACTIVE","unitPrice":3.5000,"availableUnits":9},"patchId":9600002,"units":77,"secondPutId":9600007,"secondPut":{"supplier":"postman-window","status":"ACTIVE","unitPrice":4.2500,"availableUnits":10},"secondPatchId":9600001,"secondUnits":78,"deleteId":9600003}
```

The five IDs must be distinct; PATCH needs a complete hot row. Commands arrive as
PUT, PATCH, PUT, PATCH, DELETE, providing repeated compatible shapes to compact.
All commands succeed or fail together at Redis acceptance. The worker subsequently
persists the batch in SQL. Repeating the HTTP request is **not an idempotency
contract**; after an uncertain response, inspect state/receipts rather than blindly
replaying this mixed command.

Only `SupplierOffer` opts into `independentWriteReordering=true`. Its table has
no foreign key, secondary UNIQUE constraint or cross-row trigger. This allows the
worker to compact compatible mixed writes while preserving same-key barriers.
Do not copy this flag to payment, order/FK or trigger-dependent tables.
If you add such constraints, remove the flag and reassess write ordering.
A lower batch count is workload-dependent; this example does not promise a
throughput multiplier or make Redis/SQL a distributed XA transaction.

### 5. Warehouses: Tenant Batch, Partition and Projection

POST `/stock/batch` as `demo-shop-a`:

```json
[{"stockId":9700001,"warehouse":"PM1","status":"AVAILABLE","availableUnits":40},{"stockId":9700002,"warehouse":"PM1","status":"AVAILABLE","availableUnits":80},{"stockId":9700003,"warehouse":"PM2","status":"AVAILABLE","availableUnits":20}]
```

Create different stock IDs as `demo-shop-b` with larger quantities.
Stock IDs are globally unique in this example. The server fills `tenantId`;
it is not accepted from the request body. A cross-tenant overwrite/read/PATCH
returns 403. If a batch includes a foreign-owned ID, **none of its rows changes**.

GET `/stock/top?warehouses=PM1,PM2&limit=1` as shop A returns its own top row
per warehouse, even if shop B has larger quantities. GET `/stock/cards?limit=1`
returns a tenant-scoped summary; the response does not need to expose the tenant
marker. Filtering occurs before pagination. These paths still have bounded
candidate/byte limits; they are not global SQL scans. Explicit tenant-indexed
read models are the next step for larger screens.

<details>
<summary>Complete tenant entity and summary projection</summary>

`src/main/java/com/example/cachedb/sample/domain/TenantStock.java`
```java
{{CODE:src/main/java/com/example/cachedb/sample/domain/TenantStock.java}}
```

`src/main/java/com/example/cachedb/sample/readmodel/TenantStockCard.java`
```java
{{CODE:src/main/java/com/example/cachedb/sample/readmodel/TenantStockCard.java}}
```
</details>

### 6. Read Your Write on a Different Application Instance

Capture `X-CacheDB-Causal` from a successful PUT/PATCH and send it on the next
entity GET, after normal authentication. `CacheCausalContext` bypasses stale or
unknown-version L1 entries. An unavailable minimum version returns 409 rather
than silently serving an older SQL row. A malformed token returns 400.

The token is bounded to 128 identities / 4096 characters. It is not signed,
not an authentication credential, not proof of SQL durability, and not a projection
freshness guarantee. In the collection, set `peerBaseUrl` to a second application
instance to exercise cross-instance reads; for a single instance set it to
the same value as `baseUrl`. The peer must share Redis/SQL and have the same
entity definitions and verified identity setup.

<details>
<summary>Complete adapter, HTTP contract and identity mapping</summary>

`src/main/java/com/example/cachedb/sample/controls/ControlsStore.java`
```java
{{CODE:src/main/java/com/example/cachedb/sample/controls/ControlsStore.java}}
```

The adapter owns the few bulk/partition primitives; normal facet methods stay
declarative. Its administrative scope only registers projection metadata during
startup; it never grants an HTTP caller administrative access.

`src/main/java/com/example/cachedb/sample/controls/ControlsController.java`
```java
{{CODE:src/main/java/com/example/cachedb/sample/controls/ControlsController.java}}
```

`src/main/java/com/example/cachedb/sample/controls/ControlsAccess.java`
```java
{{CODE:src/main/java/com/example/cachedb/sample/controls/ControlsAccess.java}}
```

`src/main/java/com/example/cachedb/sample/controls/OfferWarmCommand.java`
```java
{{CODE:src/main/java/com/example/cachedb/sample/controls/OfferWarmCommand.java}}
```

`src/main/java/com/example/cachedb/sample/controls/OfferWarmHandler.java`
```java
{{CODE:src/main/java/com/example/cachedb/sample/controls/OfferWarmHandler.java}}
```
</details>

### 7. Verification and Failure Cases

`mvn clean verify -Pprovider-integration` includes the old contracts plus
`ControlsContractTest` and the two additional provider integration tests.
They verify exact decimals, duplicate/order behavior, composite membership updates,
mixed SQL persistence, SQL-only misses and explicit warm, tenant rejection without
partial mutation, partition/projection windows, and minimum-version errors.

The soft-TTL test injects a missed Redis invalidation **only in the disposable test
fixture** and verifies old-then-refreshed data. It does not endorse direct Redis
writes in application code. A bounded cache probe separately verifies frequency
protection and capacity. No wall-clock REST latency SLA is asserted.

The additional Postman collection covers successful requests, 400/401/403/404/409
responses and bounded SQL/job polling. Command bodies, including chunked bodies,
are limited to 64 KiB before JSON materialization; ID/page/batch sizes are bounded.
`scripts/test-catalog-postman.ps1` runs both catalog and 0.18 collections.
For an already running isolated demo:

```sh
npx --yes newman@6.2.1 run postman/cache-database-{{PROVIDER}}-v018.postman_collection.json --env-var baseUrl=http://127.0.0.1:{{PORT}} --env-var peerBaseUrl=http://127.0.0.1:{{PORT}} --bail
```

Use only owned test data. Keep finite SQL/Redis/HTTP timeouts and observe refresh
failures, queue/backlog pressure, memory, tenant errors and source parity.
The framework's 50 ms codec gate is not a guarantee for these REST/SQL requests.

---
