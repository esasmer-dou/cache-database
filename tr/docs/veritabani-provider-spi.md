# Veritabanı Provider SPI Yönü

English version: [../../docs/database-provider-spi.md](../../docs/database-provider-spi.md)

Bu sayfa, CacheDB'nin PostgreSQL odaklı durability katmanından Microsoft SQL
Server gibi farklı SQL veritabanlarına nasıl genişlemesi gerektiğini tanımlar.

Kural nettir: MSSQL desteği eklemek, kod tabanını `if (postgres) ... else if
(mssql) ...` bloklarıyla doldurmamalıdır.

## Karar

BEST: Database-specific dialect modülleri olan bir JDBC provider SPI eklemek.

ACCEPTABLE: SPI ve MSSQL test lane tamamlanana kadar GA seviyesinde sadece
PostgreSQL durability provider sunmak.

ANTI-PATTERN: Sadece JDBC URL değiştirerek plug-and-play MSSQL desteği varmış
gibi davranmak.

## Neden Provider SPI Gerekli?

PostgreSQL ve MSSQL production açısından kritik noktalarda farklı davranır:

| Alan | PostgreSQL | MSSQL |
| --- | --- | --- |
| Upsert | `INSERT ... ON CONFLICT` | `MERGE` veya transaction içinde update-then-insert |
| Bulk load | `COPY` | table-valued parameter, `BULK INSERT` veya batch statement |
| Geçici tablo | `CREATE TEMP TABLE ... ON COMMIT DROP` | Connection scope'lu `#temp` tablo |
| Pagination | `LIMIT/OFFSET` | `OFFSET ... FETCH` veya `TOP` |
| Zaman tipi | `TIMESTAMPTZ` | `datetimeoffset` veya `datetime2` |
| Identifier quoting | `"name"` | `[name]` |
| Hata sınıflandırma | SQLSTATE odaklı | SQL Server error code odaklı |

Bu farklar kozmetik değildir. Correctness, idempotency, retry davranışı ve
yazma gecikmesini doğrudan etkiler.

## Önerilen Modül Yapısı

Hedef ayrım:

```text
cachedb-storage-jdbc
- JdbcWriteBehindFlusher
- JdbcDatabaseDialect
- JdbcFailureClassifier
- JdbcOutboxFeedAdapter
- ortak value binding ve statement batching

cachedb-storage-postgres
- PostgresDatabaseDialect
- PostgresFailureClassifier
- opsiyonel COPY optimizasyonu

cachedb-storage-mssql
- MssqlDatabaseDialect
- MssqlFailureClassifier
- MSSQL'e özel upsert ve batch stratejisi
```

`cachedb-starter`, provider-agnostic kontratlara bağlı kalmalı ve provider'ı
configuration üzerinden seçmelidir.

## Gerekli SPI Yüzeyi

İlk SPI versiyonu şunları kapsamalıdır:

- identifier quoting ve doğrulama
- tek satır upsert SQL'i
- çok satır upsert SQL'i veya desteklenen fallback
- version guard ile delete
- checkpoint tablo DDL'i
- outbox checkpoint upsert
- pagination clause
- geçici tablo stratejisi
- hata sınıflandırma
- batch capability flag'leri
- gerekiyorsa connection/session initialization

## Configuration Hedefi

Hedef kullanıcı deneyimi:

```properties
cachedb.database.provider=postgres
cachedb.database.jdbcUrl=jdbc:postgresql://localhost:5432/app
```

veya:

```properties
cachedb.database.provider=mssql
cachedb.database.jdbcUrl=jdbc:sqlserver://localhost:1433;databaseName=app
```

Provider açıkça seçilmelidir. URL'den otomatik tespit yardımcı olabilir; fakat
production configuration okunabilir ve bilinçli olmalıdır.

## MSSQL GA Kapısı

MSSQL production-ready denmeden önce şu testler geçmelidir:

- write-behind upsert/delete idempotency testleri
- aynı batch içinde duplicate id testi
- eski version event testi
- outbox checkpoint retry testi
- deadlock/retry sınıflandırma testi
- batch-size ve parameter-count limit testi
- MSSQL metadata üstünde migration discovery ve warm testi
- MSSQL baseline SQL'e karşı side-by-side comparison testi
- Kubernetes çok pod'lu apply runner smoke testi

## Mevcut Sınır

Public beta davranışı şu an PostgreSQL-first durumdadır.

Bu pakette tamamlanan adım, PostgreSQL CacheDB dışında değiştiğinde Redis'i
güncel tutan outbox/CDC apply desteğidir. MSSQL desteği bir sonraki storage
provider iş akışı olmalı ve PostgreSQL sınıflarına yerinde yamalar eklenerek
değil, yukarıdaki SPI ile geliştirilmelidir.
