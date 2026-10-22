## Çalışan 0.14 Özellik Senaryoları

Bu senaryolar yayımlanmış **CacheDB 0.14.0** paketini kullanır. Mevcut sipariş,
destek, lojistik ve arşiv yolları değişmez. Aşağıdaki sınıflar yalnızca anlatım
için yazılmış taslaklar değildir; üç sağlayıcıda da derlenen ve test edilen
örnek kaynak kodlarıdır. Her tam Java bloğunu, üstünde belirtilen dosyaya yerleştirin.

| Gerçek ihtiyaç | Kullanılan yapı | Gösterdiği davranış |
| --- | --- | --- |
| Mağaza ekranında küçük ve hızlı bir stok kartı göstermek | Üretilmiş JSON codec'i ve tipli snapshot | Veriyi SQL'den açıkça hazırlama, yalnızca Redis'ten okuma, Türkçe metin ve ondalık sayı doğruluğu |
| Tedarikçinin aynı yeni ürün listesini iki kez göndermesi | Atomik katalog aktarımı ve sabit batch kimliği | Redis'in tüm grubu birlikte kabul etmesi, tekrar gönderim güvenliği ve ayrı SQL kalıcılığı kontrolü |
| Bir kategorinin stoğu değişirken diğerinde sayfalamaya devam etmek | Kapsamı belirlenmiş snapshot yenileme | Etkilenmeyen kategorinin cursor'ı korunur; değişen kategoride sayfalama yeniden başlar |
| API trafiği ile SQL'e yazma kapasitesini ayrı ölçeklemek | `client` / `worker` profilleri ve Compose | API işi kabul eder; ayrı işleyiciler veriyi kalıcılaştırır |
| Fiziksel silmeden sonra eski bir yazmanın gecikerek gelmesi | Geçici veritabanında sağlayıcı entegrasyon testi | Eski UPSERT elenir; iş verisi ile sürüm koruması birlikte geri alınır |

{{PROVIDER_NOTE}}

### 1. Jackson Eşlemesi Olmadan Stok Kartı Hazırlama

`CatalogCard`, entity'nin tamamı değil, ekranın ihtiyaç duyduğu küçük yanıt
modelidir. Annotation processor, `CatalogCardJsonCodec` sınıfını üretir; bu sınıfı
elle oluşturmayın ve Git'e eklemeyin. Spring MVC'nin normal HTTP JSON işlemesi
değişmez. Üretilmiş codec yalnızca snapshot hazırlama ve tipli snapshot okuma
yolunda açıkça seçilir.

`src/main/java/com/example/cachedb/sample/catalog/CatalogCard.java`:

```java
{{CODE:src/main/java/com/example/cachedb/sample/catalog/CatalogCard.java}}
```

Plan SQL'den yalnızca aktif ürünleri okur. Bütün kök listesini bellekte tutmak
yerine kayıtları sırayla hazırlar. Sıralı indeks kategoriye göre bölünür; kısmi
yenileme için parametreli SQL kapsamı açıkça tanımlanır. Kullanılabilir stok
küçükten büyüğe sıralanır. Eşit stokta ürün kimliğinin metinsel sırası kullanılır.

`src/main/java/com/example/cachedb/sample/catalog/CatalogConfiguration.java`:

```java
{{CODE:src/main/java/com/example/cachedb/sample/catalog/CatalogConfiguration.java}}
```

Örneğin POM dosyasında Gson **2.13.2** açıkça sabitlenmiştir. Aksi hâlde Spring
Boot parent, gerekli `Strictness` sınıfını içermeyen eski Gson sürümünü seçebilir.
POM'da `cachedb-json`, annotation bağımlılığı ve 0.14.0 processor birlikte bulunur.
Gson sürüm ayarını kaldırmayın; farklı framework sürümlerini karıştırmayın.

0.14.0 BOM ve annotation processor ayarları mevcutsa, derlenen örneğin ek çalışma
zamanı bağımlılıkları şunlardır:

```xml
<dependency>
    <groupId>com.reactor.cachedb</groupId>
    <artifactId>cachedb-json</artifactId>
</dependency>
<dependency>
    <groupId>com.google.code.gson</groupId>
    <artifactId>gson</artifactId>
    <version>2.13.2</version>
</dependency>
```

Hızlı Başlangıç ile SQL şemasını kurup demo API'yi başlattıktan sonra:

```powershell
$base = 'http://127.0.0.1:{{PORT}}'
$job = Invoke-RestMethod "$base/api/catalog/refresh" -Method Post -ContentType 'application/json' -Body '{"ids":[]}'
$deadline = [DateTime]::UtcNow.AddSeconds(30)
do {
    Start-Sleep -Milliseconds 200
    $state = Invoke-RestMethod "$base/api/warm/jobs/$($job.jobId)"
} while ($state.status -notin @('COMPLETED', 'FAILED') -and [DateTime]::UtcNow -lt $deadline)
if ($state.status -ne 'COMPLETED') { throw ($state | ConvertTo-Json -Depth 10) }
$state
Invoke-RestMethod "$base/api/catalog/items/10"
Invoke-RestMethod "$base/api/catalog/items?ids=10,11"
Invoke-RestMethod "$base/api/catalog/categories/electronics?limit=10"
```

Kaynak verinizde bulunan bir kategori kullanın; kategori adı birebir eşleşir.
Demo ürünlerin kategorilerini kaynak veya ürün API'lerinden görebilirsiniz.
`202`, işin kuyruğa alındığını anlatır; snapshot'ın hazır olduğu anlamına gelmez.
Başarılı hazırlık, yeni yayın neslini atomik olarak etkinleştirir. Hazırlık
başarısızsa önceki nesil kullanılmaya devam eder.

| İşlem | Veritabanı ve önbellek davranışı |
| --- | --- |
| `POST /api/catalog/refresh`, gövde `{"ids":[]}` | Sınırları belirlenmiş SQL hazırlığı yapar; Redis'te yeni nesli yayımlar |
| Aynı POST, gövde `{"ids":[10]}` | Yalnızca seçilen kimlikleri parametreli SQL ile yeniler; etkilenen bölümleri günceller |
| `GET /api/catalog/items/10` | Yalnızca Redis; FOUND/STALE_WARNING için `200`, ABSENT_IN_GENERATION için `404`, SNAPSHOT_UNAVAILABLE için `503` |
| `GET /api/catalog/items?ids=10,11` | En fazla 100 kimlik, aynı nesilden okuma; `200` içinde her kimliğin durumu ayrı belirtilir |
| Kategori GET | En fazla 100 satır ve kapalı cursor; hazırlanmamış katalog boş sayfa döndürebilir, kullanılabilirliği tekil sorguyla kontrol edin |

GET isteği veri hazırlamaz ve kendiliğinden SQL'e geçmez. Entity veya projection
güncellendiğinde snapshot **otomatik güncellenmez**. Açık yenileme yapın veya
zamanlamayı etkinleştirin. `STALE_WARNING` okunabilir bir güncellik uyarısıdır.
Aktif ürün kataloğunda bulunmayan kayıt, SQL'de hiç ürün bulunmadığını kanıtlamaz.

Ürün 10'un SQL'e yazıldığı doğrulandıktan sonra `{"ids":[10]}` gönderin.
Etkilenmeyen kategorinin cursor'ı geçerli kalır. Değişen kategorinin eski cursor'ı
`400` üretir; o kategorinin ilk sayfasından yeniden başlayın. Tam yenileme,
önceki nesle ait bütün cursor'ları geçersiz kılar.

### 2. Atomik Tedarikçi Aktarımı, Güvenli Tekrar ve Kalıcılık

Bu endpoint, **yeni ürünler için tasarlanmış, sınırlı bir aktarım örneğidir**;
genel amaçlı Excel güncelleme mekanizması değildir. Bu akışa ait yeni kimlik ve
SKU'lar kullanın. Beklenen Redis sürümü `0`, sürümü bulunan mevcut bir entity
nedeniyle grubun kısmen değişmesini engeller. Bu kontrol SQL unique constraint'inin
veya kaybolmuş sürüm sayaçlarının kurtarılmasının yerine geçmez.

**Veritabanı düzeyinde yalnızca ekleme garantisi vermez.** Aynı kimlik yalnızca
SQL'de bulunuyorsa Redis sürümü `0` bunu anlayamaz; write-behind UPSERT, SQL'deki
mevcut kaydı güncelleyebilir. Primary key kısıtı UPSERT işlemini engellemez.
Demo için yeni bir kimlik/SKU aralığı ayırın; mevcut kataloğun üzerine aktarım
yapmayın. Canlı ortamda yalnızca ekleme isteniyorsa bunu ayrı SQL komutu ve
kısıt sözleşmesiyle sağlayın; Redis'te kayıt bulunmamasına dayanmayın.

`CatalogImportItem`, tam kaydı ve kaynaktan gelen sabit `updatedAt` değerini taşır.
Zamanı, fiyatı, kayıt sırasını veya kimliği değiştirmek batch içeriğini değiştirir.
Aynı teslimatı yeniden gönderirken `Instant.now()` ile yeni zaman üretmeyin.

`src/main/java/com/example/cachedb/sample/catalog/CatalogImportItem.java`:

```java
{{CODE:src/main/java/com/example/cachedb/sample/catalog/CatalogImportItem.java}}
```

`src/main/java/com/example/cachedb/sample/catalog/CatalogImportCommand.java`:

```java
{{CODE:src/main/java/com/example/cachedb/sample/catalog/CatalogImportCommand.java}}
```

Uygulama servisi bellek içi test oturumuyla da doğrudan sınanabilir. İki işlev
referansını HTTP istemcisi değil, `CatalogConfiguration` sağlar. Süre sınırlı
kalıcılık beklemesini arka plan işleyicisi yapar; HTTP isteği SQL için üç saniye
boyunca açık tutulmaz.

`src/main/java/com/example/cachedb/sample/catalog/CatalogImportService.java`:

```java
{{CODE:src/main/java/com/example/cachedb/sample/catalog/CatalogImportService.java}}
```

```powershell
$body = @'
{"batchId":"supplier-delivery-001","items":[{"productId":9200001,"sku":"SUP-9200001","name":"İğne çelik","category":"tools","unitPrice":12.3456,"stock":8,"reserved":2,"updatedAt":1700000000},{"productId":9200002,"sku":"SUP-9200002","name":"Tool B","category":"tools","unitPrice":15.0000,"stock":12,"reserved":0,"updatedAt":1700000000}]}
'@
$job = Invoke-RestMethod "$base/api/catalog/imports" -Method Post -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body))
$deadline = [DateTime]::UtcNow.AddSeconds(30)
do {
    Start-Sleep -Milliseconds 200
    $state = Invoke-RestMethod "$base/api/warm/jobs/$($job.jobId)"
} while ($state.status -notin @('COMPLETED', 'FAILED') -and [DateTime]::UtcNow -lt $deadline)
if ($state.status -ne 'COMPLETED') { throw ($state | ConvertTo-Json -Depth 10) }
$state.result
# Aynı receipt'in döndüğünü görmek için aynı $body değerini yeniden gönderin.
```

| Sonuç | Anlamı ve yapılacak işlem |
| --- | --- |
| `202` | İş kuyruğa kabul edildi; SQL commit'i anlamına gelmez |
| COMPLETED ve `result.durable=true` | Atomik grubun SQL'de kalıcılaştığı gözlemlendi |
| COMPLETED, `result.outcome=ACCEPTED` ve `result.durable=false` | Redis grubu kabul etti, fakat üç saniyede SQL kalıcılığı doğrulanamadı; kuyruk/DLQ durumunu inceleyin, gerekiyorsa yalnızca aynı teslimatı yeniden gönderin |
| COMPLETED, `result.outcome=REJECTED` ve `Batch id reused` | Aynı kimlikle farklı içerik gönderildi; yeni batch uygulanmadı, kesin ret sonucu gereksiz yere tekrar denenmez |
| COMPLETED, `result.outcome=REJECTED` ve optimistic version conflict | Üyelerden biri yeni/sürümü sıfır bir entity değildi; Redis gruptaki hiçbir üyeyi değiştirmez |
| `400` / `413` | Geçersiz istek / 64 KiB'den büyük gövde; komut kuyruğa alınmaz |

`COMPLETED`, işin yaşam döngüsünü anlatır. Teslimatı kabul edilmiş ve kalıcı
saymadan önce `outcome` ve `durable` alanlarını kontrol edin. Beklenmeyen bağlantı
hataları framework'ün sınırlı retry yolunu izler ve FAILED ile sonuçlanabilir.

En fazla **25 kayıt**, **64 KiB HTTP gövdesi**, NUMERIC(19,4)'e sığan fiyat ve
negatif olmayan stok/rezervasyon kabul edilir. SQL unique/constraint hataları yine
operasyonel takip gerektirir. Redis kabulü ile sonraki SQL transaction'ı **tek bir
dağıtık transaction değildir**: SQL aktarımı başarısızken kabul edilmiş Redis
değerleri görülebilir. Aynı içeriğin tekrarı güvenlidir; fakat hatalı SQL kaydını
onarmaz ve batch receipt'ini bir denetim kaydı saklama politikasına dönüştürmez.

Farklı batch kimlikleri Redis'te koordinasyon/receipt bilgisi biriktirir. Bu alan,
TTL ile yönetilen entity penceresi değildir. Büyümeyi ölçüp bütçelendirin; eski
teslimatlar için tekrar koruması sözü verirken receipt'leri silmeyin. Üretim
saklama politikası, açık bir tekrar gönderim süresi ve kontrollü kurtarma/geçiş
planı gerektirir.

### 3. API ve İşleyici Konteynerlerini Ayırma

`mvn clean verify -Pprovider-integration` ile derleyin. SQL'i hazırlamak için Hızlı
Başlangıç adımlarını bir kez tamamlayın, ardından API portunu boşaltmak için yerel
Java sürecini durdurun. Aynı Redis ve SQL verisini kullanın; sürüm sayaçlarını
sıfırlamayın.

```powershell
docker compose -f docker-compose.yml -f docker-compose.roles.yml up -d --scale worker=2
docker compose -f docker-compose.yml -f docker-compose.roles.yml logs worker
```

Aynı JAR, API'de `demo,client`, işleyicilerde `demo,worker` profiliyle çalışır.
Worker profili `web-application-type=none` kullanır; REST dinleyicisi açmaz.
Entity tanımları, JSON codec'leri, Redis, SQL ve iş tanımları ortaktır.
0.14.0'da worker otomatik kurulumu, client için iş yürütücüsü oluşturmaz.
Örnek bu nedenle public API üzerinden yalnızca kuyruğa gönderim yapan bean'i
açıkça tanımlar. Sondaki `false`, iş tüketimini kapatır. Client profilinde demo
iş endpoint'lerini açıyorsanız bu tanımı kaldırmayın.

`src/main/java/com/example/cachedb/sample/catalog/ClientJobSubmissionConfiguration.java`:

```java
{{CODE:src/main/java/com/example/cachedb/sample/catalog/ClientJobSubmissionConfiguration.java}}
```

Bu yerel örnekte her uygulama konteyneri **2 CPU / 768 MiB** ile sınırlıdır.
Bu değerler ölçülmüş üretim kapasitesi önerisi değil, örnek çalışma sınırlarıdır.

İşleyiciler durduğunda API işi kabul edebilir, fakat işler ve SQL yazmaları bekler.
Bir işleyiciyi yeniden başlatın; kurtarma amacıyla farklı batch kimliği üretmeyin.
Üretim yükseltmesinde bekleyen işleri boşaltmadan işleyicileri durdurmayın.
Buradaki Redis geçici yerel test altyapısıdır; HA depolama örneği değildir.

Periyodik stok hazırlığı için worker konteynerlerinde
`SAMPLE_CATALOG_SCHEDULE_ENABLED=true` ayarlayın. Framework hazırlığı yenilenebilir
Redis lease'i ile koordine eder; aynı lease altında başka worker eş zamanlı yayın
yapamaz. Varsayılan aralık beş dakikadır; dış SQL değişikliğinin anında
yansıyacağı sözü verilmez.

| Ayar | Örnek değer | Amacı |
| --- | --- | --- |
| Kaynak kayıt/bayt sınırı | 10.000 / 16 MB | Beklenmeyen geniş okumayı JVM belleğini tüketmeden reddetmek |
| JDBC fetch / hazırlık batch'i | 128 / 100 | Sürücü ve hazırlık işini sınırlamak |
| Sayfa/toplu yanıt bayt hedefi | 1 MB | Yanıt boyutunu sınırlamak; toplam Redis bellek kotası değildir |
| Uyarı / azami yaş | 10 / 30 dakika | Güncellik uyarısı ile kullanım süresinin bitmesini ayırmak |
| Hazırlık / toplam snapshot zaman aşımı | 20 / 30 saniye | İşi sınırlayıp yayına zaman bırakmak |
| Lease süresi | 45 saniye | Pod'lar arasında yenilenebilir sahiplik |

### 4. Güvenlik Kontrollerini Yeniden Çalıştırma

```powershell
mvn clean verify -Pprovider-integration
```

`CatalogFeatureTest`, üretilen repository'nin `InMemoryCacheDatabase` alan
constructor'ını, üretilmiş codec'i ve `InMemorySnapshotRepository` sınıfını kullanır.
Docker açmadan tekrar gönderimi, çakışmada geri almayı, Türkçe metin/fiyat
doğruluğunu, okuma durumlarını ve bölüm cursor'larını kontrol eder. Test aracındaki
`markDurable` **yalnızca test onayıdır**; gerçek SQL commit'i değildir.

`src/test/java/com/example/cachedb/sample/catalog/CatalogFeatureTest.java`:

```java
{{CODE:src/test/java/com/example/cachedb/sample/catalog/CatalogFeatureTest.java}}
```

Gerçek sağlayıcı entegrasyon testi ayrıca `CatalogScenarios`,
`WriteFenceScenarios` ve `SplitRoleScenarios` kontrollerini çalıştırır. REST ile
gerçek Redis/SQL akışı, fiziksel DELETE sonrasında writer yeniden oluşturulup eski
UPSERT'in gönderilmesi, SQL/metadata'nın birlikte geri alınması ve istemcide
bekleyen yazmanın ayrı işleyici ile onun yerine başlayan işleyicide tamamlanması
doğrulanır. Replay müdahalesi **yalnızca geçici testlerde** vardır; üretim HTTP
endpoint'i olarak sunulmaz. Kalıcı `cachedb_write_fence` tablosunu koruyun;
sağlayıcı migration'ı ve kontrollü yükseltme rehberini uygulayın.

`postman/cache-database-{{PROVIDER}}-catalog.postman_collection.json` dosyasını içe
aktarın. **15 isteği** sırayla çalıştırın; şema kurulmuşsa demo seed gerekmez.
Akış; ilk süre sınırlı bekleme yeterli değilse yalnızca aynı teslimatı tekrar
göndererek SQL kalıcılığını, aynı receipt ile tekrarı, değişen içeriğin reddini,
snapshot yayınını, Türkçe metin/ondalık sayıyı, tek nesilden okumayı, sayfalamayı
ve geçersiz girdi yanıtlarını doğrular. Mevcut 59 istekli iş senaryosu koleksiyonu
ayrı kalır. İki koleksiyon da REST için 50 ms SLA'sı iddia etmez.

```powershell
npx --yes newman@6.2.1 run postman/cache-database-{{PROVIDER}}-catalog.postman_collection.json --env-var baseUrl=$base
```

Entegrasyon testi, etkilenmeyen kategoride cursor'ın korunmasını da doğrular;
Postman akışı SQL'i doğrudan değiştirmez. Redis Cluster namespace dağılımı bir
framework altyapı yeteneğidir; yerel Compose tek Redis kullanır ve Cluster/HA
topolojisini doğruladığını iddia etmez. JSON ve rol seçimleri açıktır; ORM'nin
kendiliğinden SQL'e geçmesi gibi bir davranış eklenmez.

Yalıtılmış komut satırı testi için Node.js/npm ve PowerShell 7 kurulu olmalıdır.
JAR'ı derledikten sonra `./scripts/test-catalog-postman.ps1` çalıştırın. Araç;
geçici sağlayıcı altyapısını ve boş yerel portta JAR'ı başlatır, kaydedilmiş
koleksiyonu Newman ile çalıştırır, JSON/JUnit raporlarını `target/catalog-postman`
altına yazar ve yalnızca kendi test projesini/volume'larını kaldırır.
Bu aracı üretim veritabanına bağlamayın.
`-SplitRoles` seçeneği, yerel şemayı hazırlayıp hazırlık JVM'ini durdurur; aynı
koleksiyonu bir API konteyneri ve **iki worker konteyneri** üzerinde çalıştırır.
Örneklerin CI hattı bu modu kullanır; konteyner logları test kanıtına eklenir.
