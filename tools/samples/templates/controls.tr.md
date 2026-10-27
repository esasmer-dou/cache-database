## CacheDB 0.18: Çalışan Senaryolar

Bu bölümdeki sınıflar örnek projede gerçekten bulunur; taslak kod değildir.
Önce mevcut Hızlı Başlangıç adımlarını `demo` profiliyle tamamlayın. Maven,
framework kaynak kodunu derlemeden **CacheDB 0.18.0** paketlerini indirir.
`postman/cache-database-{{PROVIDER}}-v018.postman_collection.json` koleksiyonunu
kayıtlı sırasıyla çalıştırın.

### 1. Kurulum, Kimlik ve Veri Akışı

Demo, `schema.sql` ile sağlayıcıya özel `schema-controls.sql` dosyalarını uygular.
Yeni tablolar `sample_supplier_offers` ve `sample_tenant_stock` adını taşır.
Mevcut veritabanında yeni DDL dosyasını inceleyip kendi migration sürecinizle
uygulayın; canlı ortamda demo şema kurulumunu açmayın.
SQL Server/Oracle dosyalarında Spring'in `@@` komut ayırıcısı kullanılır.

| Demo profilindeki kimlik bilgisi | Sunucunun belirlediği yetki |
| --- | --- |
| `Bearer demo-supplier` | Tedarikçi teklifleri, açık SQL okuması ve sınırlı ön yükleme |
| `Bearer demo-shop-a` | Yalnızca `shop-a` kurumunun depo kayıtları |
| `Bearer demo-shop-b` | Yalnızca `shop-b` kurumunun depo kayıtları |

Bunlar herkese açık **demo kimlik bilgileridir**; canlı ortam sırrı değildir.
Endpoint'ler yalnızca `sample.demo.write-tools-enabled=true` olduğunda açılır.
Demo profilindeki değerleri `SAMPLE_CONTROLS_SUPPLIER_TOKEN`,
`SAMPLE_CONTROLS_TENANT_A_TOKEN` ve `SAMPLE_CONTROLS_TENANT_B_TOKEN` ile değiştirebilirsiniz.
Normal production profili bu endpoint'leri açmaz ve token sağlamaz.
Gerçek uygulamada bu küçük eşleştiricinin yerine doğrulanmış JWT/oturum kimliğini
kullanın. İstekten gelen serbest bir tenant başlığına güvenilmez.

| İşlem | SQL ve Redis davranışı |
| --- | --- |
| Teklif PUT / tenant batch | Önce Redis kabul eder. HTTP 202, **SQL commit anlamına gelmez**. Worker daha sonra kalıcılaştırır. |
| Teklif / depo / toplu GET | Seçilen aktif Redis kümesini okur; uygun kayıtlarda L1 kullanır. Bulunamayan kayıt için SQL çalıştırmaz. |
| GET `/offers/{id}/source` | Açık SQL okumasıdır; Redis'i kendiliğinden doldurmaz. |
| POST `/offers/warm` | Tedarikçiye göre filtrelenmiş, 1–100 satırlık açık ön yükleme işini arka planda başlatır. |
| Dashboard / projection | Seçilmiş Redis verisini kapsar; bütün SQL geçmişini kapsamaz. |

Yalnızca SQL'de bulunan kayıtta beklenen sıra şöyledir: GET 404 döner; source GET
SQL kaydını gösterebilir; normal GET hâlâ 404 döner; warm işini gönderin;
`/api/warm/jobs/{jobId}` üzerinden `COMPLETED` durumunu bekleyin; ardından normal
GET ön yüklenen kaydı döndürebilir. Sınıra sığan bir pencere seçin; bu akış sınırsız
tablo aktarımı değildir. Teklif okumaları bilgilendirme amaçlıdır; fiyat sabitleme
veya stok rezervasyonu garantisi vermez.

### 2. Tedarikçi Teklifleri: Toplu L1, Soft TTL ve Bileşik İndeks

Tedarikçi kimliğiyle `PUT /api/examples/v018/offers/9600001` çağrısını yapın:

```json
{"supplier":"postman-window","status":"ACTIVE","unitPrice":12.1250,"availableUnits":20}
```

Yanıtta `version`, `causalToken`, `redisAccepted=true` ve
`sqlDurabilityConfirmed=false` bulunur. PUT için tam teklif gönderilir.
Yalnızca miktarı değiştirmek için `PATCH /offers/{id}/units` adresine
`{"availableUnits":25}` gönderin. Aşağıdaki yolların başına
`/api/examples/v018` eklenir.

| İstek | Beklenen sonuç |
| --- | --- |
| GET `/offers?ids=9600001,9600002,9600001` | Giriş sırası ve tekrarlar korunur. Redis'ten yalnızca L1'de bulunmayan benzersiz ID'ler istenir; bulunamayanlar sonuçtan çıkarılır. |
| GET `/offers/search?supplier=postman-window&status=ACTIVE` | Tedarikçi/durum bileşik eşitlik indeksini kullanır; en fazla 50 sonuç verir. |
| Aynı ID için PAUSED durumuyla PUT | İndeks üyeliği taşınır; kayıt ACTIVE sonucundan çıkar. |
| Tekrarlanan tekil/toplu okumalar | Sınırlı L1 kullanılabilir. Erişim sıklığına dayalı tahliye, yoğun taramalarda sık okunan kayıtları korumaya yardımcı olur. |

Teklif politikası her JVM için en fazla **256 L1 kaydı / 1 MiB mantıksal veri
rezervasyonu**, **2 saniye soft TTL / 10 saniye hard TTL** tanımlar.
Bu iki süre arasında mevcut değer döndürülebilir; arka planda yalnızca Redis'ten
yenilenir. Ortak havuz iki worker ve 256 kuyruk öğesiyle sınırlıdır; aynı yenileme
tekilleştirilir, reddedilen ve başarısız yenilemeler ölçülebilir.
Hard TTL, Redis'te kalan süre ve bağlantı kesilince geçersizleştirme kuralları
devam eder. Bu davranış güncel stok kararı garantisi değildir.
Tenant depo örneğinde soft TTL bilerek kapalı bırakılmıştır.

Bu sayılar toplam JVM veya Redis belleği değildir: sıklık metaverisi, nesne
maliyeti, Redis entity/indeks/projection/sürüm anahtarları ve yazma kuyrukları da
yer kaplar. Pod sayısı arttıkça toplam L1 belleği artar. Redis komut ve bağlantı
havuzu bekleme sürelerini sınırlayın. Ayarları gerçek erişim oranına, yenileme
baskısına ve bellek ölçümüne göre değiştirin.

<details>
<summary>Tedarikçi entity'si ve üretilen repository için tam tanımlar</summary>

`src/main/java/com/example/cachedb/sample/domain/SupplierOffer.java`
```java
{{CODE:src/main/java/com/example/cachedb/sample/domain/SupplierOffer.java}}
```

`src/main/java/com/example/cachedb/sample/repository/SupplierOfferRepository.java`
```java
{{CODE:src/main/java/com/example/cachedb/sample/repository/SupplierOfferRepository.java}}
```
</details>

### 3. Satın Alma Ekranı: Gruplama ve Hassas Tutarlar

`postman-window` için 12.1250 ve 7.8750 fiyatlı iki ACTIVE teklif,
1.0050 fiyatlı bir PAUSED teklif oluşturun.
`GET /offers/dashboard?supplier=postman-window` şu bilgileri verir:

- `counts`: ACTIVE = 2, PAUSED = 1.
- `activePriceTotal`: 20.0000.
- `priceTotals`: her durumun fiyat toplamı.
- `decimalAverage`: bu tedarikçinin önbellekteki teklifleri için DECIMAL128 ortalama.

Aktif önbellek kümesi, saklama politikasını anlatır; ACTIVE iş durumuyla aynı şey
değildir. PAUSED teklifler de önbellekte tutulup sayılabilir. İstemcide tutarları
ondalık hassasiyetini koruyan bir türle işleyin; fiyat toplamları adetle ağırlıklandırılmış
stok değeri değildir.

`@CacheFacet` ve `@CacheAggregate`, doğrudan repository çağrıları üretir.
Kayıt sayısına dayalı politika ve indeks kapsamı uygun olduğunda, tekil toplam
işlemleri her salt okunur Lua çağrısında en fazla 512 adayı işler. Farklı Cluster
slot'larına dağılan sorgular sınırlı alternatif yolu kullanır. İndeks ve erişim
kayıtları için ek çağrılar yapılabilir. Ondalık hesap Java'da yapılır;
Lua'nın kayan noktalı hesabına bırakılmaz. Gruplama en fazla 256 grup ve açık
aday/bayt bütçeleriyle sınırlıdır. Sınır aşılırsa eksik toplam yerine hata verilir.
Bu sonuçlar aktif kümenin operasyonel göstergeleridir; yasal mali rapor değildir.

### 4. Tedarikçi Akışı: Bağımsız Karma Yazmalar

`POST /offers/changes`, tek ve sınırlı bir Redis transaction'ı gönderir:

```json
{"putId":9600006,"put":{"supplier":"postman-window","status":"ACTIVE","unitPrice":3.5000,"availableUnits":9},"patchId":9600002,"units":77,"secondPutId":9600007,"secondPut":{"supplier":"postman-window","status":"ACTIVE","unitPrice":4.2500,"availableUnits":10},"secondPatchId":9600001,"secondUnits":78,"deleteId":9600003}
```

Beş ID farklı olmalıdır; PATCH için tam kayıt Redis'te bulunmalıdır.
Bu sırada aynı yapıdaki PUT ve PATCH işlemleri farklı konumlarda yer alır;
worker bağımsız kayıtlar için bunları gruplayabilir.
Komutların tümü Redis kabulünde birlikte başarılı olur veya reddedilir.
Worker daha sonra bu grubu SQL'e kalıcılaştırır. HTTP isteğini yinelemek için
**idempotency garantisi verilmez**. Yanıt belirsizse karma komutu körlemesine
tekrarlamak yerine durumu ve receipt'leri kontrol edin.

Yalnızca `SupplierOffer`, `independentWriteReordering=true` kullanır.
Tabloda foreign key, ek UNIQUE kısıtı veya satırlar arası trigger yoktur.
Bu sözleşme, aynı kaydın işlem sırasını koruyarak uyumlu karma yazmaları
gruplamaya izin verir. Bu ayarı ödeme, sipariş/FK veya trigger bağımlı tablolara
kopyalamayın. Böyle bir kural eklerseniz ayarı kaldırıp sıralamayı yeniden inceleyin.
Batch sayısındaki azalma yüke bağlıdır; örnek belirli bir hız çarpanı vaat etmez
ve Redis/SQL işlemlerini dağıtık XA transaction'ına dönüştürmez.

### 5. Depolar: Tenant Batch, Partition ve Projection

`demo-shop-a` kimliğiyle `POST /stock/batch` çağrısını yapın:

```json
[{"stockId":9700001,"warehouse":"PM1","status":"AVAILABLE","availableUnits":40},{"stockId":9700002,"warehouse":"PM1","status":"AVAILABLE","availableUnits":80},{"stockId":9700003,"warehouse":"PM2","status":"AVAILABLE","availableUnits":20}]
```

`demo-shop-b` ile farklı ID'lere daha yüksek miktarlar yazın.
Bu örnekte depo kaydı ID'si bütün kurumlar arasında benzersizdir.
`tenantId` sunucuda doldurulur; istek gövdesinden alınmaz.
Başka kurumun kaydını okuma, üzerine yazma veya PATCH isteği 403 döner.
Batch içinde başka kuruma ait ID varsa **gruptaki hiçbir satır değişmez**.

Shop A için `GET /stock/top?warehouses=PM1,PM2&limit=1`, shop B'nin miktarları
daha yüksek olsa bile her deponun shop A'ya ait ilk kaydını verir.
`GET /stock/cards?limit=1`, kurum kapsamında bir özet döndürür;
tenant işaretinin yanıtta gösterilmesi gerekmez. Filtreleme sayfalama öncesindedir.
Aday ve bayt sınırları geçerlidir; bu yollar SQL'in tamamını taramaz.
Daha büyük ekranlarda açık tenant indeksli read-model tasarımına geçin.

<details>
<summary>Tam tenant entity'si ve özet projection</summary>

`src/main/java/com/example/cachedb/sample/domain/TenantStock.java`
```java
{{CODE:src/main/java/com/example/cachedb/sample/domain/TenantStock.java}}
```

`src/main/java/com/example/cachedb/sample/readmodel/TenantStockCard.java`
```java
{{CODE:src/main/java/com/example/cachedb/sample/readmodel/TenantStockCard.java}}
```
</details>

### 6. Yazdığınız Sürümü Başka Bir Uygulama Örneğinden Okuma

Başarılı PUT/PATCH yanıtındaki `X-CacheDB-Causal` değerini alın.
Normal kimlik doğrulamasını yaparak sonraki entity GET isteğinde bu başlığı gönderin.
`CacheCausalContext`, sürümü eski veya bilinmeyen L1 kaydını kullanmaz.
İstenen minimum sürüm yoksa eski SQL kaydına sessizce geçmek yerine 409 döner.
Bozuk token 400 döner.

Token en fazla 128 kimlik / 4096 karakter taşır. İmzalı değildir; kimlik doğrulama
bilgisi, SQL kalıcılığı kanıtı veya projection güncelliği garantisi değildir.
Koleksiyonda `peerBaseUrl` değerini aynı uygulamanın ikinci çalışan örneğine
(instance) yönlendirebilirsiniz. Tek örnek çalıştırırken `baseUrl` ile aynı değeri verin.
İki uygulama örneği aynı Redis/SQL kaynaklarını, entity tanımlarını ve doğrulanmış
kimlik düzenini kullanmalıdır.

<details>
<summary>Tam adaptör, HTTP sözleşmesi ve kimlik eşleştirmesi</summary>

`src/main/java/com/example/cachedb/sample/controls/ControlsStore.java`
```java
{{CODE:src/main/java/com/example/cachedb/sample/controls/ControlsStore.java}}
```

Adaptör yalnızca birkaç toplu okuma/partition işlemini taşır; normal facet
metotları deklaratif kalır. Başlangıçtaki administrative scope yalnızca projection
metaverisini kaydeder; HTTP kullanıcısına yönetici yetkisi vermez.

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

### 7. Doğrulama ve Hatalı İstekler

`mvn clean verify -Pprovider-integration`, önceki sözleşmelerin yanında
`ControlsContractTest` ve iki yeni sağlayıcı entegrasyon testini çalıştırır.
Hassas ondalık hesap, sıra/tekrarlar, bileşik indeks üyeliği, karma SQL yazmaları,
SQL'de bulunan kaydın Redis'te bulunmaması ve açık warm akışı doğrulanır.
Tenant reddinde kısmi değişiklik olmaması, partition/projection pencereleri ve
minimum sürüm hataları da kontrol edilir.

Soft-TTL testi yalnızca yalıtılmış test ortamında kaçırılmış Redis geçersizleştirmesi
oluşturur; önce eski, ardından yenilenmiş veriyi doğrular.
Bu, uygulamanın Redis'e doğrudan yazması için bir öneri değildir.
Ayrı sınırlı önbellek kontrolü, sıklık korumasını ve kapasiteyi doğrular.
REST yanıtları için duvar saati temelli bir SLA iddiası yoktur.

Yeni Postman koleksiyonu başarılı istekleri, 400/401/403/404/409 yanıtlarını ve
sınırlı SQL/iş durumu beklemelerini kapsar. Chunked gövdeler dâhil komutlar,
JSON nesneleri oluşturulmadan önce 64 KiB ile sınırlanır; ID, sayfa ve batch
sayıları da sınırlıdır. `scripts/test-catalog-postman.ps1`, katalog ve 0.18
koleksiyonlarını birlikte çalıştırır. Çalışan, yalıtılmış demo için:

```sh
npx --yes newman@6.2.1 run postman/cache-database-{{PROVIDER}}-v018.postman_collection.json --env-var baseUrl=http://127.0.0.1:{{PORT}} --env-var peerBaseUrl=http://127.0.0.1:{{PORT}} --bail
```

Yalnızca size ait test verisini kullanın. SQL/Redis/HTTP zaman aşımı sürelerini
sınırlayın; yenileme hatalarını, kuyruk baskısını, belleği, tenant hatalarını ve
kaynak veri eşleşmesini izleyin. Framework'ün 50 ms codec kapısı, bu REST/SQL
istekleri için süre garantisi değildir.

---
