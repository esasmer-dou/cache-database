# CacheDB MSSQL REST API Sample

English | [Türkçe](README.tr.md)

This is a standalone Spring Boot REST API that shows how to use CacheDB with Redis and Microsoft SQL Server. It uses the same domain and API flow as the PostgreSQL sample, but selects the MSSQL storage provider explicitly.

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

<dependency>
  <groupId>com.reactor.cachedb</groupId>
  <artifactId>cachedb-storage-mssql</artifactId>
  <version>0.1.0</version>
</dependency>
```

Users should not build the parent repository first. CacheDB `0.1.0` is published from the main repository to GitHub Packages.

GitHub Packages Maven access requires credentials. The `<id>` in `pom.xml` must match the `<server><id>` in Maven `settings.xml`:

```xml
<settings>
  <servers>
    <server>
      <id>cache-database-github-packages</id>
      <username>${env.GITHUB_ACTOR}</username>
      <password>${env.GITHUB_TOKEN}</password>
    </server>
  </servers>
</settings>
```

Use a token with `read:packages` access, then run:

```bash
export GITHUB_ACTOR=your-github-user
export GITHUB_TOKEN=your-read-packages-token
mvn clean package
```

If you do not configure credentials, Maven will usually fail with `401 Unauthorized` even though the repository URL is correct. A complete example is included in `settings-github-packages.example.xml`.

## Run Locally

1. Start Redis and SQL Server:

```bash
docker compose up -d
```

2. Start the API:

```bash
mvn spring-boot:run
```

3. Check readiness:

```bash
curl http://127.0.0.1:8092/api/health/ready
```

4. Seed realistic demo data:

```bash
curl -X POST "http://127.0.0.1:8092/api/demo/seed?customers=20&ordersPerCustomer=40&linesPerOrder=4"
```

5. Open the CacheDB admin UI:

```text
http://127.0.0.1:8092/cachedb-admin
```

## Main API Flow

| Step | Endpoint | What it demonstrates |
|---|---|---|
| Health | `GET /api/health/ready` | Redis connectivity and write-behind health summary |
| Seed | `POST /api/demo/seed` | CacheDB write path, SQL Server persistence, projection refresh |
| Customer detail | `GET /api/customers/1?orderPreview=5` | Entity detail with bounded relation preview |
| Timeline | `GET /api/customers/1/orders?limit=20` | Projection/read-model list route |
| Order detail | `GET /api/orders/10001?linePreview=5` | Explicit detail load with bounded child relation |
| High value list | `GET /api/orders/high-value?minimumAmount=500&limit=25` | Global sorted projection query |
| Dashboard | `GET /api/dashboard/commerce?limit=25` | Small dashboard from projections and ticket entity query |
| Tuning | `GET /api/tuning` | Active CacheDB policy and guardrail summary |

## MSSQL Provider Settings

The important provider switch is in `application.yml`:

```yaml
cachedb:
  sql:
    provider: mssql
```

The sample also sets MSSQL-specific lock and timeout behavior:

```yaml
cachedb:
  sql:
    mssql:
      lock-timeout-millis: 5000
      query-timeout-seconds: 10
      transaction-isolation: serializable
```

For a real environment, use a dedicated database instead of `tempdb`, tune Hikari pool size, and validate lock behavior with your SQL Server topology.

The sample active-data policy admits records when they are inside the 90-day order window or carry an active operational state such as `ACTIVE`, `NEW`, `PAID`, `OPEN`, or `PENDING`.

## Why Projection Is Used

The customer order timeline can grow without bound. Loading `Customer -> all Orders -> all Lines` on every list screen is not production-safe. This sample keeps the list screen on `OrderSummary`, then loads full order details only after the user selects one order.

BEST:

- Use `OrderSummary` projection for customer timelines and high-value lists.
- Keep API `limit` inside the configured projection window.
- Load `OrderEntity` with `linePreview` only for a detail screen.
- Keep SQL Server as the durable source for full history.

ANTI-PATTERN:

- Returning a customer with thousands of full orders and lines in one response.
- Exposing unbounded `findAll` list endpoints.
- Assuming PostgreSQL and SQL Server have identical lock, isolation, and batch behavior.

## Postman

Import:

```text
postman/cache-database-mssql-sample.postman_collection.json
```

The collection contains the normal flow: readiness, seed, customer timeline, detail, dashboard, update, delete, and tuning.

## SQL Server Notes

The schema is created by `src/main/resources/schema.sql`. It includes primary keys, foreign keys, and indexes for the hot routes:

- `sample_orders(customer_id, order_date DESC, order_id DESC)`
- `sample_orders(priority_score DESC, order_date DESC)`
- `sample_order_lines(order_id, line_number)`
- `sample_support_tickets(status, priority, updated_at DESC)`

The seed endpoint writes through CacheDB and waits for parent rows before writing dependent child rows. That is intentional because the database has foreign keys.

`POST /api/orders` also waits briefly until the customer row is durable in SQL Server. If the parent customer was just created and write-behind has not flushed it yet, the endpoint returns `409` and the client should retry.

## Troubleshooting

SQL Server can take longer than Redis to become ready. If the app fails on first start, wait for the SQL Server healthcheck and run `mvn spring-boot:run` again.

If dependency resolution fails, verify access to the package repository configured in `pom.xml`.

If timeline results are empty immediately after seed, wait a few seconds and retry. Projection refresh is asynchronous.
