# CacheDB MSSQL REST API Örneği

[English](README.md) | Türkçe

Bu proje, CacheDB’nin Redis ve Microsoft SQL Server ile nasıl kullanılacağını gösteren bağımsız bir Spring Boot REST API örneğidir. PostgreSQL örneğiyle aynı domain ve API akışını kullanır; fark olarak SQL provider açıkça MSSQL seçilir.

Örnek senaryo bir e-ticaret destek sistemidir:

- Müşteriler sürekli sipariş verir.
- Siparişlerin birden fazla satırı vardır.
- Ürünler kategoriye göre sık okunur.
- Destek talepleri operasyon paneline veri sağlar.
- Müşteri sipariş zaman çizelgesi, tüm aggregate yüklenmeden özet okuma modeli üzerinden döner.

## Bağımlılık Modeli

Bu proje CacheDB’yi dış Maven paketi olarak kullanır:

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

Yani kullanıcı ana projeyi önce build etmek zorunda değildir. CacheDB `0.1.0`, ana repodan GitHub Packages’a yayınlanır ve bu örnek proje paketi oradan çeker.

GitHub Packages Maven erişimi için kimlik bilgisi gerekir. `pom.xml` içindeki `<repository><id>` değeri ile Maven `settings.xml` içindeki `<server><id>` değeri aynı olmalıdır:

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

`read:packages` yetkisi olan bir token tanımladıktan sonra proje doğrudan build edilir:

```bash
export GITHUB_ACTOR=github-kullanici-adin
export GITHUB_TOKEN=read-packages-token
mvn clean package
```

Bu ayar yapılmazsa repository URL doğru olsa bile Maven genellikle `401 Unauthorized` hatası verir. Tam örnek `settings-github-packages.example.xml` dosyasında durur.

## Yerelde Çalıştırma

1. Redis ve SQL Server’ı başlat:

```bash
docker compose up -d
```

2. API’yi başlat:

```bash
mvn spring-boot:run
```

3. Hazırlık durumunu kontrol et:

```bash
curl http://127.0.0.1:8092/api/health/ready
```

4. Demo verisini üret:

```bash
curl -X POST "http://127.0.0.1:8092/api/demo/seed?customers=20&ordersPerCustomer=40&linesPerOrder=4"
```

5. CacheDB yönetim ekranını aç:

```text
http://127.0.0.1:8092/cachedb-admin
```

## Ana API Akışı

| Adım | Endpoint | Ne gösterir? |
|---|---|---|
| Sağlık | `GET /api/health/ready` | Redis bağlantısı ve arka plan yazma özeti |
| Veri üretme | `POST /api/demo/seed` | CacheDB yazma yolu, SQL Server kalıcılığı, projection yenileme |
| Müşteri detay | `GET /api/customers/1?orderPreview=5` | Sınırlı sipariş önizlemesiyle entity detayı |
| Sipariş listesi | `GET /api/customers/1/orders?limit=20` | Özet okuma modeliyle listeleme |
| Sipariş detay | `GET /api/orders/10001?linePreview=5` | Sınırlı satır önizlemesiyle detay okuma |
| Yüksek değerli sipariş | `GET /api/orders/high-value?minimumAmount=500&limit=25` | Global sıralı projection sorgusu |
| Panel | `GET /api/dashboard/commerce?limit=25` | Projection ve ticket sorgularıyla küçük dashboard |
| Ayarlar | `GET /api/tuning` | Aktif CacheDB politikaları ve koruma eşikleri |

## MSSQL Provider Ayarları

Temel provider seçimi `application.yml` içindedir:

```yaml
cachedb:
  sql:
    provider: mssql
```

Örnek ayrıca MSSQL’e özel kilit ve timeout ayarları verir:

```yaml
cachedb:
  sql:
    mssql:
      lock-timeout-millis: 5000
      query-timeout-seconds: 10
      transaction-isolation: serializable
```

Gerçek ortamda `tempdb` yerine ayrı bir uygulama veritabanı kullanılmalıdır. Hikari pool boyutu hedef trafiğe göre ayarlanmalı ve kilit davranışı kendi SQL Server topolojinizde doğrulanmalıdır.

Örnekteki aktif veri politikası, kaydı son 90 günlük sipariş penceresindeyse veya `ACTIVE`, `NEW`, `PAID`, `OPEN`, `PENDING` gibi aktif operasyon durumlarından birini taşıyorsa Redis’e kabul eder.

## Neden Projection Kullanılıyor?

Bir müşterinin sipariş sayısı zamanla binleri bulabilir. Liste ekranında `Customer -> tüm Orders -> tüm Lines` yüklemek production için doğru değildir. Bu örnekte liste ekranı `OrderSummary` üzerinden döner; kullanıcı tek bir siparişi seçtiğinde detay ayrıca yüklenir.

BEST:

- Müşteri sipariş listesi ve yüksek değerli sipariş listesi için `OrderSummary` kullan.
- API `limit` değerini projection penceresinin içinde tut.
- `OrderEntity` ve satır ilişkisini sadece detay ekranında yükle.
- SQL Server’ı tam geçmiş için kalıcı kaynak olarak bırak.

ANTI-PATTERN:

- Tek response içinde müşterinin tüm siparişlerini ve tüm satırlarını döndürmek.
- Sınırsız `findAll` benzeri endpoint açmak.
- PostgreSQL ve SQL Server’ın kilit, isolation ve batch davranışının bire bir aynı olduğunu varsaymak.

## Postman

İçe aktarılacak dosya:

```text
postman/cache-database-mssql-sample.postman_collection.json
```

Koleksiyon; sağlık kontrolü, veri üretme, müşteri sipariş listesi, detay, dashboard, update, delete ve tuning çağrılarını içerir.

## SQL Server Notları

Şema `src/main/resources/schema.sql` ile kurulur. Primary key, foreign key ve aktif route’lar için indeksler vardır:

- `sample_orders(customer_id, order_date DESC, order_id DESC)`
- `sample_orders(priority_score DESC, order_date DESC)`
- `sample_order_lines(order_id, line_number)`
- `sample_support_tickets(status, priority, updated_at DESC)`

Seed endpoint’i veriyi CacheDB üzerinden yazar ve child kayıtları yazmadan önce parent kayıtların SQL tarafına düşmesini bekler. Bunun nedeni şemada foreign key bulunmasıdır.

`POST /api/orders` da müşteri satırı SQL Server tarafında kalıcı hale gelene kadar kısa süre bekler. Müşteri az önce oluşturulduysa ve write-behind henüz flush etmediyse endpoint `409` döner; client kısa süre sonra tekrar denemelidir.

## Sorun Giderme

SQL Server’ın hazır hale gelmesi Redis’e göre daha uzun sürebilir. İlk başlatmada hata alırsan SQL Server healthcheck tamamlandıktan sonra `mvn spring-boot:run` komutunu tekrar çalıştır.

Dependency çözümleme hatasında `pom.xml` içindeki paket repository erişimini kontrol et.

Seed sonrası liste hemen boş dönerse birkaç saniye bekleyip tekrar dene. Projection yenileme arka planda çalışır.
