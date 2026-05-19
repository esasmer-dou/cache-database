# Geçiş Planlayıcı

Geçiş Planlayıcı, mevcut PostgreSQL tabloları ve çalışan bir ORM route'u olan
ekiplerin CacheDB'ye geçişi tahminle değil kanıtla değerlendirmesi için admin UI
içinde sunulan akıştır.

Bu ekran production cutover düğmesi değildir. Tek bir route'u keşfetmek,
planlamak, warm etmek, karşılaştırmak ve raporlamak için kullanılır.

## Ne Zaman Kullanılır?

Şu durumlarda planlayıcıyı kullan:

- mevcut PostgreSQL tabloların varsa
- route bugün JPA, Hibernate, MyBatis, JDBC veya başka bir data layer ile çalışıyorsa
- child satır sayısı arttıkça liste/detail ekranı pahalılaşıyorsa
- route'un entity read, projection read veya ranked projection read olarak mı tasarlanacağını netleştirmek istiyorsan
- production trafiğini değiştirmeden önce staging kanıtı görmek istiyorsan

Bunu production verisini değiştiren bir otomasyon gibi kullanma. Bu ekran bir
geçiş provası ve karar yüzeyidir.

## Nerede Açılır?

Aynı admin host ve port üzerinde:

- Spring Boot: `/cachedb-admin/migration-planner`
- Native admin server: `/migration-planner`

Spring Boot örneği:

```text
http://127.0.0.1:8090/cachedb-admin/migration-planner
```

## Önerilen UI Akışı

### 1. PostgreSQL Şemasını Keşfet

Önce şema keşfi aksiyonunu çalıştır.

Beklenen sonuç:

- kullanıcı tabloları görünür
- primary key bilgileri görünür
- foreign key ilişkileri görünür
- route adayları listelenir
- önerilen root/child çiftleri forma uygulanabilir hale gelir

Keşif başarısız olursa Spring `DataSource` doğru veritabanına gidiyor mu ve
uygulama kullanıcısı `information_schema` metadata'sını okuyabiliyor mu kontrol
et.

### 2. Route Adayı Seç

Önerilen listeden tek bir route adayı seç.

Örnekler:

- müşteri timeline ekranı için `customers -> orders`
- sipariş detay preview için `orders -> order_lines`
- stok geçmişi için `products -> inventory_events`
- finansal hareket ekranı için `accounts -> transactions`

`Forma uygula` düğmesine bas.

Beklenen sonuç:

- root table/entity dolar
- child table/entity dolar
- keşif biliyorsa primary key kolonları dolar
- foreign key üzerinden relation kolonu dolar
- sort adayları önerilir
- row count ve fan-out ipuçları mümkünse doldurulur

### 3. Route Davranışını Gözden Geçir

Keşif şema bilgisini okuyabilir, ama ürün davranışını tamamen bilemez. Şu alanı
elle kontrol et:

- ilk sayfa boyutu
- root başına sıcak pencere
- root başına tipik child sayısı
- root başına maksimum child sayısı
- archive history gerekli mi
- detail lookup sıcak mı
- route liste ağırlıklı mı
- mevcut ORM eager loading kullanıyor mu
- side-by-side comparison gerekli mi

Pratik kural:

- liste ekranı: summary projection tercih et
- detay ekranı: full entity gerektiğinde yüklenebilir
- global sorted ekran: ranked projection tercih et
- yüksek fan-out child tablo: Redis'te yalnızca sınırlı sıcak pencere tut

### 4. Planı Oluştur

`Planı oluştur` düğmesine bas.

Beklenen sonuç:

- önerilen CacheDB kullanım yüzeyi
- Redis yerleşim kararı
- PostgreSQL yerleşim kararı
- projection gerekli mi bilgisi
- ranked projection gerekli mi bilgisi
- sıcak pencere boyutu
- warm-up adımları
- staging comparison checklist
- örnek child SQL
- örnek root SQL

Plan görünmüyorsa ekranın artık sessiz kalmak yerine server-side hata göstermesi
beklenir.

### 5. Scaffold Üret

Java kodu için başlangıç iskeleti istiyorsan scaffold üretimini kullan.

Beklenen sonuç:

- root `@CacheEntity` iskeleti
- child `@CacheEntity` iskeleti
- sıcak liste named query'si
- opsiyonel relation loader iskeleti
- opsiyonel projection support iskeleti
- generated binding kullanım örneği

Bu çıktı production domain modeli değildir. Column type, isimlendirme,
nullability ve index varsayımlarını commit etmeden önce gözden geçir.

### 6. Dry-Run Warm Çalıştır

Redis'i değiştirmeden önce dry-run çalıştır.

Beklenen sonuç:

- PostgreSQL child satırları sayılır
- ilişkili root satırları sayılır
- üretilecek warm SQL görünür
- Redis değişmez
- eksik root ID veya beklenmeyen row count varsa görünür

Bu adımı query şekli ve satır sayıları doğru mu diye kontrol etmek için kullan.

### 7. Staging Warm Çalıştır

Gerçek warm adımını yalnızca staging veya güvenli test ortamında çalıştır.

Beklenen sonuç:

- seçilen child sıcak penceresi PostgreSQL'den okunur
- Redis entity yüzeyleri doğrudan hydrate edilir
- kayıtlı projection'lar inline yenilenir
- seçildiyse ilişkili root satırları da warm edilir
- warm istatistiklerinde root satırı, child satırı, atlanan satır ve süre görünür

`No registered CacheDB entity found` hatası alırsan seçilen route çalışan
uygulamada kayıtlı değildir. Önce entity binding'i üret veya bağla, uygulamayı
yeniden build et, sonra planlayıcıyı tekrar çalıştır.

### 8. Side-By-Side Comparison Çalıştır

Warm sonrasında comparison çalıştır.

Beklenen sonuç:

- PostgreSQL baseline latency
- CacheDB route latency
- `entity:...` veya `projection:...` route etiketi
- örnek root'larda ilk sayfa ID eşleşmesi
- readiness assessment
- blokajlar ve sonraki adımlar

Şu durumlarda cutover yapma:

- örnekler birebir eşleşmiyorsa
- planner projection isterken CacheDB route entity fallback'e düşüyorsa
- sıralama farklıysa
- p95 latency baseline'dan belirgin şekilde kötüyse
- warm set production sıcak pencereyi temsil etmiyorsa

### 9. Migration Report İndir

Comparison sonrasında raporu indir.

Rapor şunları içermelidir:

- route özeti
- seçilen tasarım
- warm sonuçları
- comparison sonuçları
- readiness durumu
- cutover action plan
- blokajlar
- rollback notları

## Tam Sistem Migration Coverage

Planner tek seferde bir sıcak route modeller. Bu bilinçli bir tasarımdır.
Güvenli tam sistem dönüşümü tek büyük otomatik dönüşümden değil, route
envanterinden gelir.

%100 coverage için:

1. tüm production ekran, API, batch, worker ve report route'larını listele
2. her route'u root tablo, child tablo, sort, filter ve page size ile eşleştir
3. her route'u generated CRUD, projection, ranked projection, direct repository veya PostgreSQL cold path olarak sınıflandır
4. Redis-first hot path olacak her route için planner akışını çalıştır
5. owner, readiness, blokaj ve rollback planı içeren coverage tablosu tut
6. her route için açık karar verilmeden migration tamamlandı deme

Önerilen coverage kolonları:

| Kolon | Anlamı |
| --- | --- |
| Route adı | Ekran/API/job için anlaşılır isim |
| Root table | Ana entity/table |
| Child table | Varsa child/fan-out table |
| Query shape | filter, sort, page, range, threshold |
| CacheDB shape | generated, projection, ranked projection, repository, cold path |
| Warm durumu | başlamadı, dry-run ok, warm ok |
| Compare durumu | çalışmadı, eşleşti, fark var |
| Cutover durumu | blocked, ready, canary, live |
| Rollback planı | net fallback yolu |

## Demo Bootstrap

Spring Boot demo, planner için tek tıkla kurulabilen PostgreSQL migration veri
seti içerir.

`/cachedb-admin/migration-planner` üzerinden şunları yapabilirsin:

1. demo customer/order şemasını oluştur
2. customer ve order tarihçesini seed et
3. PK/FK constraint'lerini ve destekleyici index'leri oluştur
4. inceleme view'lerini oluştur
5. discovery'yi yenileyip scaffold, warm ve compare adımlarına geç

Hazırlanan demo nesneleri:

- `cachedb_migration_demo_customers`
- `cachedb_migration_demo_orders`
- `cachedb_migration_demo_customer_order_timeline_v`
- `cachedb_migration_demo_customer_metrics_v`
- `cachedb_migration_demo_ranked_orders_v`

## Mevcut Kapsam

Planner şunları yapar:

- PostgreSQL şema metadata'sını keşfeder
- root/child route adayları önerir
- migration planı üretir
- Java scaffold üretir
- dry-run warm çalıştırır
- Redis'e staging warm çalıştırır
- warm sırasında kayıtlı projection'ları inline yeniler
- PostgreSQL ve CacheDB arasında side-by-side comparison çalıştırır
- comparison sonucundan migration assessment ve report içeriği üretir

Planner henüz şunları yapmaz:

- PostgreSQL'i mutate etmez
- mevcut ORM source class'larını otomatik import etmez
- route envanteri olmadan tam sistem coverage garanti etmez
- tek tık production cutover yapmaz

Bu sınır bilinçlidir. Planner'ın amacı, trafik taşınmadan önce mimari kararları
görünür ve ölçülebilir hale getirmektir.
