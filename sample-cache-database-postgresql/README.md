# CacheDB PostgreSQL REST API Sample

English | [Türkçe](README.tr.md)

This is a standalone Spring Boot REST API that shows how a normal application can use CacheDB with Redis and PostgreSQL without building the main CacheDB repository locally.

The sample models a commerce support system:

- Customers place many orders.
- Orders have many order lines.
- Products are read frequently by category.
- Support tickets feed a small operational dashboard.
- Customer order timelines are served from a projection/read-model instead of hydrating full aggregates.

## Dependency Model

This project intentionally consumes CacheDB as an external Maven package:

```xml
<repository>
  <id>cache-database-github-packages</id>
  <url>https://maven.pkg.github.com/esasmer-dou/cache-database</url>
</repository>

<dependency>
  <groupId>com.reactor.cachedb</groupId>
  <artifactId>cachedb-spring-boot-starter</artifactId>
  <version>0.1.0</version>
</dependency>
```

Users should not build the parent repository first. If your Maven client asks for GitHub Packages credentials, configure a read token in `~/.m2/settings.xml`. The application code stays the same when the package is later mirrored to Maven Central.

## Run Locally

1. Start Redis and PostgreSQL:

```bash
docker compose up -d
```

2. Start the API:

```bash
mvn spring-boot:run
```

3. Check readiness:

```bash
curl http://127.0.0.1:8091/api/health/ready
```

4. Seed realistic demo data:

```bash
curl -X POST "http://127.0.0.1:8091/api/demo/seed?customers=20&ordersPerCustomer=40&linesPerOrder=4"
```

5. Open the CacheDB admin UI:

```text
http://127.0.0.1:8091/cachedb-admin
```

## Main API Flow

| Step | Endpoint | What it demonstrates |
|---|---|---|
| Health | `GET /api/health/ready` | Redis connectivity and write-behind health summary |
| Seed | `POST /api/demo/seed` | CacheDB write path, SQL persistence, projection refresh |
| Customer detail | `GET /api/customers/1?orderPreview=5` | Entity detail with bounded relation preview |
| Timeline | `GET /api/customers/1/orders?limit=20` | Projection/read-model list route |
| Order detail | `GET /api/orders/10001?linePreview=5` | Explicit detail load with bounded child relation |
| High value list | `GET /api/orders/high-value?minimumAmount=500&limit=25` | Global sorted projection query |
| Dashboard | `GET /api/dashboard/commerce?limit=25` | Small dashboard from projections and ticket entity query |
| Tuning | `GET /api/tuning` | Active CacheDB policy and guardrail summary |

## Why Projection Is Used

The customer order timeline can grow without bound. Loading `Customer -> all Orders -> all Lines` on every list screen is not production-safe. This sample keeps the list screen on `OrderSummary`, then loads full order details only after the user selects one order.

BEST:

- Use `OrderSummary` projection for customer timelines and high-value lists.
- Keep API `limit` inside the configured projection window.
- Load `OrderEntity` with `linePreview` only for a detail screen.
- Keep PostgreSQL as the durable source for full history.

ANTI-PATTERN:

- Returning a customer with thousands of full orders and lines in one response.
- Exposing unbounded `findAll` list endpoints.
- Treating Redis memory as unlimited.

## Tuning Points

The sample configures a realistic starting point in `SampleCacheDbTuningConfig`:

| Area | Sample setting | Why |
|---|---|---|
| Active data window | `hotEntityLimit=5000` | Keeps Redis bounded for local testing |
| Entity TTL | `0` | Entity records are not removed by TTL in this sample |
| Page TTL | `120s` | Short-lived page cache |
| Active data policy | last 90 days or active operational states | Closer to real operational screens than plain LRU |
| Entity query limit | `250` | Prevents accidental large entity scans |
| Projection query limit | `1000` | Allows timeline windows but blocks unbounded reads |
| Redis guardrails | warning 75%, critical 88% | Makes memory pressure visible early |

For production, also set Redis `maxmemory`, keep `maxmemory-policy=noeviction`, size the connection pools, and keep admin UI behind your gateway/auth layer.

## Postman

Import:

```text
postman/cache-database-postgresql-sample.postman_collection.json
```

The collection contains the normal flow: readiness, seed, customer timeline, detail, dashboard, update, delete, and tuning.

## PostgreSQL Notes

The schema is created by `src/main/resources/schema.sql`. It includes primary keys, foreign keys, and indexes for the hot routes:

- `sample_orders(customer_id, order_date DESC, order_id DESC)`
- `sample_orders(priority_score DESC, order_date DESC)`
- `sample_order_lines(order_id, line_number)`
- `sample_support_tickets(status, priority, updated_at DESC)`

The seed endpoint writes through CacheDB and waits for parent rows before writing dependent child rows. That is intentional because the database has foreign keys.

`POST /api/orders` also waits briefly until the customer row is durable in PostgreSQL. If the parent customer was just created and write-behind has not flushed it yet, the endpoint returns `409` and the client should retry.

## Troubleshooting

If the API cannot resolve CacheDB dependencies, verify access to the package repository configured in `pom.xml`.

If the seed endpoint is slow, check `GET /api/health/ready` and the admin UI write-behind section. The seed flow waits for SQL rows so foreign keys are not violated.

If timeline results are empty immediately after seed, wait a few seconds and retry. Projection refresh is asynchronous.
