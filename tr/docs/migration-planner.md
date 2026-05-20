# Geçiş Planlayıcı

Geçiş Planlayıcı, mevcut PostgreSQL tabloları ve çalışan bir ORM yapısı olan
ekiplerin CacheDB'ye geçişi kanıta dayalı biçimde değerlendirmesi için yönetim
arayüzünde sunulan akıştır.

Bu ekran tek tıkla canlıya geçiş düğmesi değildir. Amacı, tek bir sıcak akışı
keşfetmek, planlamak, Redis tarafını staging ortamında önceden doldurmak,
PostgreSQL ve CacheDB sonuçlarını karşılaştırmak ve geçiş kararını görünür hale
getirmektir.

## Ne Zaman Kullanılır?

Şu durumlarda planlayıcıyı kullan:

- mevcut PostgreSQL tabloların varsa
- akış bugün JPA, Hibernate, MyBatis, JDBC veya başka bir veri erişim katmanı ile çalışıyorsa
- çocuk satır sayısı arttıkça liste veya detay ekranı pahalılaşıyorsa
- akışın entity read, projection read veya ranked projection read olarak mı tasarlanacağını netleştirmek istiyorsan
- production trafiğini değiştirmeden önce staging kanıtı görmek istiyorsan

Bunu production verisini değiştiren bir otomasyon gibi kullanma. Bu ekran bir
geçiş provasıdır.

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
- akış adayları listelenir
- önerilen kök/çocuk tablo çiftleri forma uygulanabilir hale gelir

Keşif başarısız olursa Spring `DataSource` doğru veritabanına gidiyor mu ve
uygulama kullanıcısı `information_schema` metadata'sını okuyabiliyor mu kontrol
et.

### 2. Akış Adayı Seç

Önerilen listeden tek bir aday seç.

Örnekler:

- müşteri zaman akışı için `customers -> orders`
- sipariş detay önizlemesi için `orders -> order_lines`
- stok geçmişi için `products -> inventory_events`
- finansal hareket ekranı için `accounts -> transactions`

`Forma uygula` düğmesine bas.

Beklenen sonuç:

- kök tablo/entity alanı dolar
- çocuk tablo/entity alanı dolar
- keşif biliyorsa primary key kolonları dolar
- foreign key üzerinden ilişki kolonu dolar
- sıralama adayları önerilir
- satır sayısı ve fan-out ipuçları mümkünse doldurulur

### 3. Akış Davranışını Gözden Geçir

Keşif şema bilgisini okuyabilir; ancak ürün davranışını tamamen bilemez. Şu
alanları elle kontrol et:

- ilk sayfa boyutu
- kök kayıt başına sıcak pencere
- kök kayıt başına tipik çocuk satır sayısı
- kök kayıt başına maksimum çocuk satır sayısı
- archive history gerekli mi
- detay lookup sıcak mı
- akış liste ağırlıklı mı
- mevcut ORM eager loading kullanıyor mu
- yan yana karşılaştırma gerekli mi

Pratik kural:

- Liste ekranında summary projection tercih et.
- Detay ekranında full entity gerektiğinde yüklenebilir.
- Genel sıralı ekranda ranked projection tercih et.
- Yüksek fan-out değerine sahip çocuk tabloda Redis'te yalnızca sınırlı sıcak pencere tut.

### 4. Planı Oluştur

`Planı oluştur` düğmesine bas.

Beklenen sonuç:

- önerilen CacheDB kullanım yüzeyi
- Redis yerleşim kararı
- PostgreSQL yerleşim kararı
- projection gerekli mi bilgisi
- ranked projection gerekli mi bilgisi
- sıcak pencere boyutu
- ön ısıtma adımları
- staging karşılaştırma checklist'i
- örnek çocuk tablo SQL'i
- örnek kök tablo SQL'i

Plan görünmüyorsa ekranın sessiz kalması doğru değildir; hata varsa açık biçimde
gösterilmelidir.

### 5. Scaffold Üret

Java kodu için başlangıç iskeleti istiyorsan scaffold üretimini kullan.

Beklenen sonuç:

- kök `@CacheEntity` iskeleti
- çocuk `@CacheEntity` iskeleti
- sıcak liste named query'si
- opsiyonel relation loader iskeleti
- opsiyonel projection support iskeleti
- generated binding kullanım örneği

Bu çıktı production domain modeli değildir. Column type, isimlendirme,
nullability ve index varsayımlarını commit etmeden önce gözden geçir.

### 6. Dry-Run Ön Isıtma Çalıştır

Redis'i değiştirmeden önce dry-run çalıştır.

Beklenen sonuç:

- PostgreSQL çocuk satırları sayılır
- ilişkili kök satırlar sayılır
- üretilecek ön ısıtma SQL'i görünür
- Redis değişmez
- eksik kök ID veya beklenmeyen satır sayısı varsa görünür

Bu adımı sorgu şekli ve satır sayıları doğru mu diye kontrol etmek için kullan.

### 7. Staging Ön Isıtma Çalıştır

Gerçek ön ısıtmayı yalnızca staging veya güvenli test ortamında çalıştır.

Beklenen sonuç:

- seçilen çocuk sıcak penceresi PostgreSQL'den okunur
- Redis entity yüzeyleri doğrudan doldurulur
- kayıtlı projection'lar aynı akış içinde yenilenir
- seçildiyse ilişkili kök satırlar da doldurulur
- warm istatistiklerinde kök satır, çocuk satır, atlanan satır ve süre görünür

`No registered CacheDB entity found` hatası alırsan seçilen entity çalışan
uygulamada kayıtlı değildir. Önce entity binding'i üret veya bağla, uygulamayı
yeniden build et, sonra planlayıcıyı tekrar çalıştır.

### 8. Yan Yana Karşılaştırma Çalıştır

Ön ısıtma sonrasında karşılaştırma çalıştır.

Beklenen sonuç:

- PostgreSQL referans gecikmesi
- CacheDB akış gecikmesi
- `entity:...` veya `projection:...` akış etiketi
- örnek kök kayıtlarda ilk sayfa ID eşleşmesi
- geçişe hazırlık değerlendirmesi
- blokajlar ve sonraki adımlar

Şu durumlarda canlıya geçme:

- örnekler birebir eşleşmiyorsa
- planner projection isterken CacheDB entity yoluna geri düşüyorsa
- sıralama farklıysa
- p95 gecikme PostgreSQL referansından belirgin şekilde kötüyse
- sıcak veri seti production'daki gerçek sıcak pencereyi temsil etmiyorsa

### 9. Geçiş Raporunu İndir

Karşılaştırma sonrasında raporu indir.

Rapor şunları içermelidir:

- akış özeti
- seçilen tasarım
- ön ısıtma sonuçları
- karşılaştırma sonuçları
- geçişe hazırlık durumu
- canlıya geçiş aksiyon planı
- blokajlar
- geri dönüş notları

## Tam Sistem Migration Coverage

Planner tek seferde bir sıcak akış modeller. Bu bilinçli bir tasarımdır.
Güvenli tam sistem dönüşümü tek büyük otomatik dönüşümden değil, akış
envanterinden gelir.

%100 coverage için:

1. tüm production ekran, API, batch, worker ve rapor akışlarını listele
2. her akışı kök tablo, çocuk tablo, sort, filter ve page size ile eşleştir
3. her akışı generated CRUD, projection, ranked projection, direct repository veya PostgreSQL soğuk veri yolu olarak sınıflandır
4. Redis öncelikli sıcak yol olacak her akış için planner sürecini çalıştır
5. sahip, hazırlık durumu, blokaj ve geri dönüş planı içeren kapsam tablosu tut
6. her akış için açık karar verilmeden migration tamamlandı deme

Önerilen coverage kolonları:

| Kolon | Anlamı |
| --- | --- |
| Akış adı | Ekran/API/job için anlaşılır isim |
| Kök tablo | Ana entity/table |
| Çocuk tablo | Varsa child/fan-out table |
| Sorgu şekli | filter, sort, page, range, threshold |
| CacheDB tasarımı | generated, projection, ranked projection, repository, soğuk veri yolu |
| Ön ısıtma durumu | başlamadı, dry-run ok, warm ok |
| Karşılaştırma durumu | çalışmadı, eşleşti, fark var |
| Canlıya geçiş durumu | blocked, ready, canary, live |
| Geri dönüş planı | net geri dönüş yolu |

## Demo Bootstrap

Spring Boot demo, planner için tek tıkla kurulabilen PostgreSQL migration veri
seti içerir.

`/cachedb-admin/migration-planner` üzerinden şunları yapabilirsin:

1. demo customer/order şemasını oluştur
2. customer ve order geçmişini seed et
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
- kök/çocuk tablo adayları önerir
- geçiş planı üretir
- Java iskeleti üretir
- dry-run ön ısıtma çalıştırır
- Redis'e staging ön ısıtma çalıştırır
- ön ısıtma sırasında kayıtlı projection'ları yeniler
- PostgreSQL ve CacheDB arasında yan yana karşılaştırma çalıştırır
- karşılaştırma sonucundan geçiş değerlendirmesi ve rapor içeriği üretir

Planner henüz şunları yapmaz:

- PostgreSQL'i mutate etmez
- mevcut ORM source class'larını otomatik içeri almaz
- akış envanteri olmadan tam sistem coverage garanti etmez
- tek tıkla production canlı geçişi yapmaz

Bu sınır bilinçlidir. Planner'ın amacı, trafik taşınmadan önce mimari kararları
görünür ve ölçülebilir hale getirmektir.
