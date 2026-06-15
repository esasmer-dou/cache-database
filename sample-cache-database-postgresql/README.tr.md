# CacheDB PostgreSQL REST API Örneği

[English](README.md) | Türkçe

Bu proje, CacheDB’nin Redis ve PostgreSQL ile nasıl kullanılacağını gösteren bağımsız bir Spring Boot REST API örneğidir. Kullanıcının ana CacheDB reposunu indirip yerelde build etmesi gerekmez; proje CacheDB’yi Maven paketi olarak tüketir.

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
```

Yani kullanıcı ana projeyi önce build etmek zorunda değildir. Maven, GitHub Packages için erişim isterse `~/.m2/settings.xml` içine okuma yetkili token eklenmelidir. Paket daha sonra Maven Central’a taşınırsa uygulama kodu değişmez; sadece repository tanımı sadeleşir.

## Yerelde Çalıştırma

1. Redis ve PostgreSQL’i başlat:

```bash
docker compose up -d
```

2. API’yi başlat:

```bash
mvn spring-boot:run
```

3. Hazırlık durumunu kontrol et:

```bash
curl http://127.0.0.1:8091/api/health/ready
```

4. Demo verisini üret:

```bash
curl -X POST "http://127.0.0.1:8091/api/demo/seed?customers=20&ordersPerCustomer=40&linesPerOrder=4"
```

5. CacheDB yönetim ekranını aç:

```text
http://127.0.0.1:8091/cachedb-admin
```

## Ana API Akışı

| Adım | Endpoint | Ne gösterir? |
|---|---|---|
| Sağlık | `GET /api/health/ready` | Redis bağlantısı ve arka plan yazma özeti |
| Veri üretme | `POST /api/demo/seed` | CacheDB yazma yolu, SQL kalıcılığı, projection yenileme |
| Müşteri detay | `GET /api/customers/1?orderPreview=5` | Sınırlı sipariş önizlemesiyle entity detayı |
| Sipariş listesi | `GET /api/customers/1/orders?limit=20` | Özet okuma modeliyle listeleme |
| Sipariş detay | `GET /api/orders/10001?linePreview=5` | Sınırlı satır önizlemesiyle detay okuma |
| Yüksek değerli sipariş | `GET /api/orders/high-value?minimumAmount=500&limit=25` | Global sıralı projection sorgusu |
| Panel | `GET /api/dashboard/commerce?limit=25` | Projection ve ticket sorgularıyla küçük dashboard |
| Ayarlar | `GET /api/tuning` | Aktif CacheDB politikaları ve koruma eşikleri |

## Neden Projection Kullanılıyor?

Bir müşterinin sipariş sayısı zamanla binleri bulabilir. Liste ekranında `Customer -> tüm Orders -> tüm Lines` yüklemek production için doğru değildir. Bu örnekte liste ekranı `OrderSummary` üzerinden döner; kullanıcı tek bir siparişi seçtiğinde detay ayrıca yüklenir.

BEST:

- Müşteri sipariş listesi ve yüksek değerli sipariş listesi için `OrderSummary` kullan.
- API `limit` değerini projection penceresinin içinde tut.
- `OrderEntity` ve satır ilişkisini sadece detay ekranında yükle.
- PostgreSQL’i tam geçmiş için kalıcı kaynak olarak bırak.

ANTI-PATTERN:

- Tek response içinde müşterinin tüm siparişlerini ve tüm satırlarını döndürmek.
- Sınırsız `findAll` benzeri endpoint açmak.
- Redis belleğini sınırsız kabul etmek.

## Ayar Noktaları

Örnek başlangıç ayarları `SampleCacheDbTuningConfig` içinde yer alır:

| Alan | Örnek ayar | Neden? |
|---|---|---|
| Aktif veri penceresi | `hotEntityLimit=5000` | Yerel testte Redis büyümesini sınırlar |
| Entity TTL | `0` | Bu örnekte entity kayıtları TTL ile silinmez |
| Sayfa TTL | `120s` | Sayfa cache’i kısa süreli tutulur |
| Aktif veri politikası | son 90 gün veya aktif operasyon durumları | Sadece LRU’dan daha gerçekçi bir iş kuralı sağlar |
| Entity sorgu limiti | `250` | Büyük entity taramalarını engeller |
| Projection sorgu limiti | `1000` | Zaman çizelgesi penceresine izin verir, sınırsız okumayı engeller |
| Redis koruma eşikleri | uyarı %75, kritik %88 | Bellek baskısını erken görünür yapar |

Production’da Redis `maxmemory` mutlaka verilmelidir, `maxmemory-policy=noeviction` korunmalıdır, bağlantı havuzları hedef trafiğe göre ayarlanmalıdır ve yönetim ekranı gateway/auth arkasında tutulmalıdır.

## Postman

İçe aktarılacak dosya:

```text
postman/cache-database-postgresql-sample.postman_collection.json
```

Koleksiyon; sağlık kontrolü, veri üretme, müşteri sipariş listesi, detay, dashboard, update, delete ve tuning çağrılarını içerir.

## PostgreSQL Notları

Şema `src/main/resources/schema.sql` ile kurulur. Primary key, foreign key ve sıcak route’lar için indeksler vardır:

- `sample_orders(customer_id, order_date DESC, order_id DESC)`
- `sample_orders(priority_score DESC, order_date DESC)`
- `sample_order_lines(order_id, line_number)`
- `sample_support_tickets(status, priority, updated_at DESC)`

Seed endpoint’i veriyi CacheDB üzerinden yazar ve child kayıtları yazmadan önce parent kayıtların SQL tarafına düşmesini bekler. Bunun nedeni şemada foreign key bulunmasıdır.

`POST /api/orders` da müşteri satırı PostgreSQL tarafında kalıcı hale gelene kadar kısa süre bekler. Müşteri az önce oluşturulduysa ve write-behind henüz flush etmediyse endpoint `409` döner; client kısa süre sonra tekrar denemelidir.

## Sorun Giderme

CacheDB dependency’leri çözülemiyorsa `pom.xml` içindeki paket repository erişimini kontrol et.

Seed endpoint’i yavaş görünüyorsa `GET /api/health/ready` ve yönetim ekranındaki arka plan yazma bölümünü kontrol et. Seed akışı foreign key hatası üretmemek için SQL satırlarının oluşmasını bekler.

Seed sonrası liste hemen boş dönerse birkaç saniye bekleyip tekrar dene. Projection yenileme arka planda çalışır.
