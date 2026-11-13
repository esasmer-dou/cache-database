# CacheDB PostgreSQL Örneği

> Bu örnek CacheDB 0.19.0 paketlerini aynı sürümde gerektirir. [Yayımlanan sürümler](https://github.com/esasmer-dou/cache-database/releases) | [Maven deposu](https://esasmer-dou.github.io/cache-database/maven2). Başlamadan önce gereken tüm paketlerin kullanılabilir olduğunu doğrulayın.

[English](README.md) | Türkçe

> **Sürüm belgeleri: 0.19.0. Java 21+ gereklidir.** Gerçek paket erişimi ve sağlama toplamları için sürüm sayfası yetkilidir.
> Kabulün yetkili kaynağı bu README değil, tam sürüm commit'inin CI sonuçları ve pakete bağlı kanıttır.
> Paketleri ve örnek proje tag'ini eşleştirin; yalnız bağımlılık sürümünü değiştirmek bu API'leri eski sürüme taşımaz.
> `FrameworkPackage != universalGA`: uygulama yolları, koruma geçişi, HA ve geri yükleme ayrı doğrulama gerektirir.

[Buradan başla](#buradan-başla) | [Hızlı başlangıç](#hızlı-başlangıç) | [API kataloğu](#api-kataloğu) |
[Senaryoya göre ayarlar](#kullanım-senaryosuna-göre-ayar) | [Postman](#postman)

## Sınırlı Denetim Notları: Toplu Silme ve Saklama Süresi

Şifrelenmemiş ordinary entity örnekleri, süresi dolmuş denetim notlarının sınırlı temizliğini
ve iki ayrı saklama politikasını gösterir. Yalnızca geçici veritabanı/Redis alanında yapay notlar
kullanın. Supplier kimliği politika başına sekiz sabit yuvayı yönetir; toplam SQL satır sayısı
on altıdır. Kiracı kimlikleri bu notları okuyamaz, yazamaz veya süpüremez. Bu örnek kiracıya ait
şifreli depolama ya da purge değildir.

### Tek Demo Başlatma Komutu

Sağlayıcının mevcut veritabanı ve Redis servislerini başlatıp aynı sürümdeki CacheDB paketlerini
çözümledikten sonra bu örneğin dizininde çalıştırın:

```powershell
mvn -B -ntp clean spring-boot:run "-Dspring-boot.run.profiles=demo"
```

Yalnızca `application-demo.yml`, `sample.audit-api.enabled=true` ayarını, `schema-audit.sql`
şemasını ve sekiz satırlık iki sıcak kümeyi etkinleştirir. Normal başlangıçta endpoint kapalıdır.
Şifreli örnek derlemesinin `demo,encryption` başlangıcı da bu şemayı içerir.
Okuma birleştirme bağımsız bir opt-in ayarıdır; bu örnek onu etkinleştirmez.
Varsayılan demo token'ları yalnızca yerel gösterim içindir, üretim kimlik doğrulaması değildir.
Mevcut `SAMPLE_CONTROLS_*` ayarlarını kullanın; demo yazma araçlarını internete açmayın.

### Doğrudan Çalıştırılabilir İstekler

`expired` zorunludur. Sunucu ACTIVE/bugün veya EXPIRED/iki UTC gün önce değerlerini seçer.
İstemci keyfî kimlik, kiracı, tarih, tablo ya da saklama eylemi seçemez.
Not; 1..512 UTF-8 bayt, geçerli Unicode içermeli ve NUL içermemelidir.

```powershell
$base = 'http://127.0.0.1:8091'
$headers = @{ Authorization = 'Bearer demo-supplier' }
$body = '{"note":"Süresi dolmuş yapay denetim notu: ğışöüİ","expired":true}'
Invoke-RestMethod -Method Post -Uri "$base/api/examples/v019/audit-notes/delete/1/source-roundtrip" -Headers $headers -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body))
try {
    Invoke-RestMethod -Method Post -Uri "$base/api/examples/v019/audit-notes/delete/spring-data" -Headers $headers
    throw 'Beklenen sıfır sorgu bütçesi reddi dönmedi.'
} catch {
    if ($null -eq $_.Exception.Response -or [int]$_.Exception.Response.StatusCode -ne 409) { throw }
}
$slots = '{"slots":[1]}'
Invoke-RestMethod -Method Post -Uri "$base/api/examples/v019/audit-notes/delete/bulk" -Headers $headers -ContentType 'application/json' -Body $slots
```

`PUT /api/examples/v019/audit-notes/{policy}/{slot}` aynı not gövdesini alır ve gerçek UPSERT
receipt'iyle 202 döndürür; ön planda SQL kalıcılığını doğrulamaz. source-roundtrip rotası ise
receipt'in kalıcılık işaretini en fazla beş saniye bekler ve mantıksal kaynak satırını denetleyerek
200 döndürür. 504, kabul edilmiş ama doğrulanmamış yazmadır; geri alma değildir. Eşzamanlı yazma
gözlemden önce satırı değiştirebilir; 409 otomatik RYOW anlamına gelmez.

Bu demoda sekiz satırlık sıcak küme ve on satırlık genel headroom ayarı, tam entity sorgusu için
sıfır etkin bütçe bırakır. `POST /delete/spring-data`, `AUDIT_DERIVED_DELETE_ZERO_QUERY_BUDGET`
hatasıyla HTTP 409 döndürür; sorgu veya silme başlatılmaz. Bütçeyi büyütmeden, bilinen 1..8 yuva
için `POST /delete/bulk` kullanın. Sıfır bütçe, bilinmeyen veya sınırsız bütçe demek değildir.

Üretilmiş türetilmiş silmenin pozitif bütçeli örneği,
[DerivedDeleteRangeExternalIT](src/test/java/com/example/cachedb/sample/rangedata/DerivedDeleteRangeExternalIT.java)
içindedir: `RangeDataRepository.deleteByRunIdAndStatus`, mevcut `RangeDataRow` üzerinde
run/status ve doğrulanmış kiracı kapsamındaki
eşleşmeler silinir; diğer durumlar, çalışmalar ve kiracılar korunur. Bütçeye tam ulaşan seçimde
eşleşme kümesinin tamamlığı kanıtlanamaz; hiçbir silme yapılmadan istek reddedilir.
Pozitif sonuçtaki entity listesi receipt veya SQL silme sayısı değildir. İşlem transaction
ya da eşzamanlı yazarlara karşı atomik koşul yeniden denetimi sağlamaz.

`POST /delete/bulk`, birbirinden farklı 1..8 yuva alır ve native sınırlı toplu silmenin gerçek
DELETE receipt'lerini döndürür. Başarılı 202 yanıtında da `sqlDurabilityConfirmed=false` kalır.
İki silme rotasında da kısmi hata HTTP 409'dur: gerçek `phase`, `accepted` receipt'leri,
`rejected`, `uncertain`, `cleanupPending` ve `notAttempted` alanları korunur.
Cleanup-pending kimlikleri accepted ile örtüşebilir. Seri tekil silmeye dönüş veya otomatik
mutasyon tekrarı yapılmaz. Belirsiz sonucu ve receipt sürümünü uzlaştırmadan yeniden denemeyin;
iptal ya da taşıma hatası geri alma değildir.

### EVICT_CACHE ve DELETE Farkı

```powershell
$evictBody = '{"note":"Keep this synthetic SQL audit note","expired":true}'
Invoke-RestMethod -Method Post -Uri "$base/api/examples/v019/audit-notes/evict/1/source-roundtrip" -Headers $headers -ContentType 'application/json' -Body $evictBody
$pass = '{"maxRows":8}'
Invoke-RestMethod -Method Post -Uri "$base/api/examples/v019/audit-notes/evict/sweep" -Headers $headers -ContentType 'application/json' -Body $pass
Invoke-RestMethod -Method Get -Uri "$base/api/examples/v019/audit-notes/evict/1/source" -Headers $headers
Invoke-RestMethod -Method Post -Uri "$base/api/examples/v019/audit-notes/delete/sweep" -Headers $headers -ContentType 'application/json' -Body $pass
```

SOURCE saklama türündeki iki `@CacheRetention` anotasyonu bir günlük politikalar için metadata
üretir: `EvictAuditNote`, EVICT_CACHE; `DeleteAuditNote`, DELETE kullanır. Sunucu iki politika
adını sabit entity sınıflarına eşler. Gerçek `RetentionSweeper.sweepOnce` çağrısı yalnızca supplier
yetkisi doğrulandıktan sonra açılan geçici administrative scope içinde çalışır.

EVICT_CACHE, indeksli önbellek payload'ını ve üyeliklerini kaldırır; SQL'i, kuyruktaki yazmaları
ve kalıcı sürümleri silmez. Sıcak GET 404 döndürürken açık `/{policy}/{slot}/source` rotası SQL
notunu döndürebilir. Bu demoda read-through kapalıdır. Başka uygulamalarda admission politikası
saklama süresini de uygulamıyorsa SQL satırı yeniden önbelleğe alınabilir. Receipt içermeyen kaynak
gözlemi kalıcılık doğrulaması değildir.

DELETE, normal sürüm çiti uygulanmış write-behind silmesini kuyruğa alır. `deleteAccepted`,
yalnızca Redis kabul sayısıdır; SQL commit'i veya tekil receipt kalıcılığı değildir. Süpürme yanıtı
`examined`, `evicted`, `indexCleaned`, `deleteAccepted`, `skipped` ve `exhausted` alanlarını
ayırır. Her zaman `indexedResidentOnly=true`, `sqlDurabilityConfirmed=false` ve
`sourceErasureConfirmed=false` döner. Sıcak kümenin sıralı indeksi soğuk SQL geçmişinin tamamını
bulamaz. Hiçbir eylem yedeklerden silinmeyi, genel cache sahipliğini, tüm kiracı kişisel verilerinin
silinmesini veya purge'ü belgelemez. Purge REST endpoint'i yoktur.

Sınırlar: sekiz etkin istek, chunked gövde dahil 2 KiB body, sekiz süpürme adayı, 512 komut anahtarı
ve 64 KiB mutasyon komutu bütçesi. Native lease/sürüm/snapshot denetimleri, producer guardrail'leri,
yapılandırılmış trace/peer invalidation yayıncısı ve gerçek yerel L1 store korunur. Bir tur erken
bitebilir; `exhausted` eşzamanlı veya tüm geçmişi kapsayan snapshot değildir. Native 30 saniyelik
admission bütçesi evrensel I/O timeout'u değildir; sonlu istemci havuzu/socket ve HTTP süre sınırları
gereklidir. BUSY, 429; depolama/lease hatası, `outcomeUnknown=true` ile 503 döndürür. Hata sıfır
silme başarısına çevrilmez. Sınırlı operasyonel backoff kullanın; mutasyonları körlemesine tekrarlamayın.
Boş olmayan causal header veya etkin causal/tenant/admin scope varsa istek fail-closed reddedilir.

### Kayıtlı Sözleşmeler ve Testler

<!-- BEGIN AUDIT API MATRIX -->
| Senaryo | Java Metodu | Kayıtlı İstek |
| --- | --- | --- |
| Açık receipt ve SQL doğrulaması | [AuditNoteController.sourceRoundtrip](src/main/java/com/example/cachedb/sample/audit/AuditNoteController.java) | Expire delete 1 |
| Spring Data sıfır sorgu bütçesi reddi | [AuditNoteController.springData](src/main/java/com/example/cachedb/sample/audit/AuditNoteController.java) | Spring Data rejects zero query budget |
| Native sınırlı receipt grubu | [AuditNoteController.bulk](src/main/java/com/example/cachedb/sample/audit/AuditNoteController.java) | Native bulk returns actual receipts |
| Yalnız önbelleğe uygulanan saklama süresi | [AuditNoteController.sweep](src/main/java/com/example/cachedb/sample/audit/AuditNoteController.java) | Evict indexed expired cache payload |
| Write-behind üzerinden saklama süresi | [AuditNoteController.sweep](src/main/java/com/example/cachedb/sample/audit/AuditNoteController.java) | Delete indexed expired note via write-behind |
| Supplier, kiracı ve nedensellik sınırı | [AuditNoteRules.verify](src/main/java/com/example/cachedb/sample/audit/AuditNoteRules.java) | Tenant A cannot inspect global source |
<!-- END AUDIT API MATRIX -->

Bağımsız [audit koleksiyonu](postman/cache-database-postgresql-audit.postman_collection.json) 42
istek içerir. İlk on altı istek yalnızca on altı sabit yapay satırın üzerine yazar; saklanan gerçek
iş verilerine karşı veya başka bir yazarla eşzamanlı çalıştırmayın.
`auditApiEnabled=true` ve `auditApiCertification=true` birlikte zorunludur. Eksik gate, atlanan
istek, eksik assertion veya gerçek yürütme hatası sertifikasyonu reddeder.

```powershell
./scripts/test-audit-postman.ps1 -BaseUrl http://127.0.0.1:8091 -ConfirmDisposableDemo
mvn -B -ntp test "-Dtest=AuditContractTest,AuditHttpContractTest"
mvn -B -ntp test "-Dtest=AuditExternalIT"
```

Dış ortam testi mevcut özel `CACHEDB_TEST_NAMESPACE` sağlayıcı fixture'larını gerektirir; testin
atlanmadan yürütülmesini şart koşun. Sıfır bütçe reddi, bilinen kimliklerle toplu silme, gerçek receipt
işaretleri ve iki saklama eylemi denetlenir. `DerivedDeleteRangeExternalIT`, pozitif bütçeli
üretilmiş silmeyi ve tam kapasitede mutasyondan önce reddi gerçek Redis/SQL üzerinde denetler. Kontrollü unit/MVC hataları, HTTP hata
enjeksiyon rotası eklemeden kısmi sonuç eşlemesini doğrular. Önceki iş akışı (61+23), range/data
(37) ve encryption (10) koleksiyonlarını ve bağımsız gate'lerini koruyun.
[Yapısal denetleyici](../tools/ci/check-v019-audit-scenarios.mjs), kaynak metotlarını, EN/TR
kılavuzlarını ve kayıtlı istek sözleşmelerini denetler; runtime yeterliliği kanıtı değildir.


## Şifreli Tedarikçi Notları

Bu isteğe bağlı, yalıtılmış demo; gerçek üretilmiş alan codec'lerini ve Spring Boot
CacheKeyProvider bean'ini kullanır. Varsayılan derlemeye dahil değildir: açıkça
sağlanan anahtarlar olmadan şifreli metadata discovery'ye girmemelidir.
encrypted-demo Maven profili src/encryption/java, src/encryption-test/java ve
src/encryption/resources dizinlerini ekler. Profil değiştirirken clean kullanın;
eski üretilmiş sınıfların hedef dizinde kalmasına izin vermeyin.

Yalnızca doğrulanmış tedarikçi kimliği bu **genel kapsamlı** notlara erişebilir.
Kiracı kimlikleri reddedilir. Bu, kiracı kişisel verilerinin sahipliğini oluşturma
örneği değildir. Sunucunun belirlediği tam sekiz kimlik, 1..512 UTF-8 byte not sınırı (boş/NUL notlar reddedilir),
2 KiB istek gövdesi, eş zamanlı sekiz istek ve 300 saniyelik Redis TTL sınırı vardır.
Listeleme, istemcinin seçtiği namespace, kör arama, retention işi veya purge API'si yoktur.

### Açık Anahtarlarla Tek Başlangıç

Önce bu sağlayıcının mevcut, silinebilir veritabanı/Redis kurulumunu tamamlayın.
Şifreli Redis verisi veya kuyruk kaydı kaldığı sürece yeniden başlatmalarda aynı
anahtarları koruyun. Mevcut namespace üzerinde anahtarları yeniden üretmeyin.
Java/YAML içinde varsayılan anahtar yoktur; aşağıdaki bağımsız rastgele demo
anahtarları, canlı ortam anahtar yönetimi yerine geçmez.

~~~powershell
function New-DemoKey {
    $bytes = [byte[]]::new(32)
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    [Convert]::ToBase64String($bytes)
}
$env:SAMPLE_ENCRYPTION_AES_KEY = New-DemoKey
$env:SAMPLE_ENCRYPTION_HMAC_KEY = New-DemoKey
mvn -B -ntp -Pencrypted-demo clean spring-boot:run "-Dspring-boot.run.profiles=demo,encryption"
~~~

Komutu bu örneğin dizininde çalıştırın. Normal derleme değişmez. encrypted-demo ile
üretilmiş paket, demo/encryption profilleri ile geçerli anahtarları gerektirir;
bu paketi canlı ortam uygulaması olarak dağıtmayın. Demo kimliklerini dışarı açmayın.

Şifreleme dahil tüm entegrasyon testlerini çalıştırmak için yukarıdaki iki anahtarı
aynı terminal oturumunda tutun ve test kılavuzundaki silinebilir sağlayıcı ortamını
hazırlayın. Maven profili Java kaynaklarını ekler; Spring çalışma zamanı profilini
etkinleştirmez. Bu tanımları içeren her test uygulaması, yalnızca `demo` profilini
açan mevcut testler de dahil, `encryption` profiline ihtiyaç duyar:

~~~powershell
$previousInclude = $env:SPRING_PROFILES_INCLUDE
try {
    $env:SPRING_PROFILES_INCLUDE = 'encryption'
    mvn -B -ntp -Pprovider-integration,encrypted-demo clean verify
    if ($LASTEXITCODE -ne 0) { throw 'Entegrasyon testleri başarısız oldu' }
} finally {
    $env:SPRING_PROFILES_INCLUDE = $previousInclude
}
~~~

Bu Maven koşusunda `SAMPLE_SCHEDULED_WARM_ENABLED=false` ayarını tüm testlere
uygulamayın: normal entegrasyon testi, zamanlanmış işin kaydını da doğrular.
Gereken testler zamanlamayı kendi ayarlarıyla kapatır. Anahtar eksikse uygulama
başlamamalıdır; şifreli alanlar sessizce düz metne dönüştürülmemelidir.

~~~powershell
$base = 'http://127.0.0.1:8091'
$headers = @{ Authorization = 'Bearer demo-supplier' }
$body = @{ note = 'Türkçe alan: ğışöüİ' } | ConvertTo-Json
Invoke-RestMethod "$base/api/examples/v019/encryption/notes/1" -Method Put -Headers $headers -ContentType 'application/json' -Body ([Text.Encoding]::UTF8.GetBytes($body))
Invoke-RestMethod "$base/api/examples/v019/encryption/notes/1" -Headers $headers
Invoke-RestMethod "$base/api/examples/v019/encryption/notes/1/source-roundtrip" -Method Post -Headers $headers -ContentType 'application/json' -Body ([Text.Encoding]::UTF8.GetBytes($body))
pwsh -NoProfile -File scripts/test-encryption-postman.ps1 -BaseUrl $base -ConfirmDisposableDemo
~~~

PUT, sqlDurabilityConfirmed=false ile 202 döndürür: Redis kabulü SQL kalıcılığı
değildir. GET, yalnızca Redis'teki tekil kaydı çözer; kaydın bulunamaması SQL'de
olmadığını göstermez. source-roundtrip açıkça yazar, o receipt'in kalıcılığını en
fazla beş saniye bekler ve kaynağı kimliğiyle okur. Yalnızca mantıksal satır
eşleşirse confirmed=true döndürür. 504, yazmanın kabul edildiği fakat henüz
doğrulanmadığı anlamına gelir; eş zamanlı kaynak değişikliği 409 üretir. İkisi de
körü körüne tekrar deneme talimatı değildir. Dolu nedensellik gereksinimleri reddedilir.

REDIS_ONLY, işaretli notu Redis payload'larında ve kuyruk yazmalarında şifreler;
SQL'e ise **orijinal düz metni** yazar. TLS, disk şifrelemesi, JDBC loglarının
gizlenmesi ve yedekleme kontrolleri ayrı sorumluluklardır. Dış servis entegrasyon
testi gerçek Redis ciphertext'ini ve sağlayıcının gerçek SQL değerini inceler;
yalnızca REST yanıtı, depolamanın şifreli olduğunu kanıtlamaz. REDIS_AND_SOURCE veya
Oracle CLOB desteği iddia edilmez.

| Senaryo | REST | Gerçek Metot | Kayıtlı İstek |
| --- | --- | --- | --- |
| encrypted-save | PUT /api/examples/v019/encryption/notes/{slot} | [EncryptedNoteController.save](src/encryption/java/com/example/cachedb/sample/encryption/EncryptedNoteController.java) | 4 |
| encrypted-hot | GET /api/examples/v019/encryption/notes/{slot} | [EncryptedNoteController.hot](src/encryption/java/com/example/cachedb/sample/encryption/EncryptedNoteController.java) | 5 |
| encrypted-source | POST /api/examples/v019/encryption/notes/{slot}/source-roundtrip | [EncryptedNoteController.sourceRoundtrip](src/encryption/java/com/example/cachedb/sample/encryption/EncryptedNoteController.java) | 1 |

Ayrı postman/cache-database-postgresql-encryption.postman_collection.json dosyası,
encryptionApiEnabled=true ve encryptionApiCertification=true ister. Runner ikisini
de ayarlar; eksik, atlanan veya başarısız istek/assertion kabul edilmez.
Mevcut 84 business ve 37 range/data isteği korunur. Yalnızca sentetik notlar
kullanın: Postman raporları çözülmüş istek/yanıt verisi içerir ve özel tutulmalıdır.
Şifreli not yolları toplu silme, saklama kurallarını uygulama, kiracı verilerini silme
(purge) veya kiracı sahipliğini oluşturma işlemlerini kapsamaz. Üstteki ayrı,
şifrelenmemiş denetim notu örneği sınırlı toplu silme ve saklama kurallarını gösterir;
şifreli notların sözleşmesini genişletmez.

## Aralık ve Spring Data API'leri (0.19)

Bu örnekler, ${cachedb.version} değerinde eşleşen CacheDB 0.19 core, processor,
Redis storage, starter ve isteğe bağlı cachedb-spring-data paketlerini gerektirir.
[RangeRoutes](src/main/java/com/example/cachedb/sample/rangedata/RangeRoutes.java)
ve [RangeDataRepository](src/main/java/com/example/cachedb/sample/rangedata/RangeDataRepository.java)
derleme sırasında üretilir. Reflection tabanlı repository proxy'si veya SQL'e
örtük geçiş yoktur.

Sağlayıcının veritabanı ve Redis hızlı başlangıcını tamamladıktan sonra,
bu örneğin dizininde tek komutla uygulamayı başlatın:

~~~shell
mvn -B -ntp spring-boot:run "-Dspring-boot.run.profiles=demo"
~~~

Yalnızca demo profili sample.range-data.enabled ayarını açar ve
schema-range-data.sql dosyasını çalıştırır. Metot önbelleği/asenkron ayarları
korunur. Demo olmadan, sample.range-data.enabled verilse bile yeni REST yolları
ve repository bean'leri oluşturulmaz. Sunucuda başka özellik bayrağı gerekmez.
ControlsAccess, doğrulanan kimlikleri shop-a/shop-b kiracılarına eşler;
tedarikçi kimliği bu tenant yollarını kullanamaz. Demo kimliklerini ve yazma
araçlarını güvenilmeyen ağlara açmayın.

### Tam Long Değeriyle Yazma ve Okuma

Aşağıdaki gövde gerçek API sözleşmesidir. rank, JSON sayısı değil ondalık
**metindir**: JavaScript her işaretli Long değerini tam olarak temsil edemez.
Sunucu satır kimliğini doğrulanmış tenant, run ve slot değerlerinden oluşturur;
istek gövdesi tenant seçmez. Yalnızca OPEN/CLOSED durumları kabul edilir.

~~~powershell
$base = 'http://127.0.0.1:8091'
$headers = @{ Authorization = 'Bearer demo-shop-a' }
$run = [Guid]::NewGuid().ToString('N')
$url = "$base/api/examples/v019/range-data/$run"
Invoke-RestMethod "$url/rows/a" -Method Put -Headers $headers -ContentType application/json -Body '{"rank":"9007199254740992","status":"OPEN"}'
Invoke-RestMethod "$url/rows/c" -Method Put -Headers $headers -ContentType application/json -Body '{"rank":"9007199254740993","status":"OPEN"}'
$first = Invoke-RestMethod "$url/native?limit=1" -Headers $headers
$first.items
$next = [Uri]::EscapeDataString($first.nextCursor)
Invoke-RestMethod "$url/native?limit=1&cursor=$next" -Headers $headers
Invoke-RestMethod "$url/native/seek?afterRank=9007199254740992&afterSlot=a&limit=2" -Headers $headers
Invoke-RestMethod "$url/data/page?limit=2" -Headers $headers
Invoke-RestMethod "$url/data/slice?limit=1" -Headers $headers
$window = Invoke-RestMethod "$url/data/window?limit=1" -Headers $headers
$next = [Uri]::EscapeDataString($window.nextCursor)
Invoke-RestMethod "$url/data/window?limit=1&cursor=$next" -Headers $headers
Invoke-RestMethod "$url/data/stats" -Headers $headers
~~~

PUT, row ve sqlDurabilityConfirmed:false içeren 202 döndürür. Spring Data save,
WriteReceipt döndürmediği için bu yollar sürüm veya causal token uydurmaz.
Redis kabulü SQL commit'i değildir; işleyici hatasında veri görünürken kalıcılık
bekleyebilir. Yazmalar otomatik tekrarlanmaz. Aynı PUT aynı mantıksal satırı
hedefler; ancak yeni yazma/sürüm üretir, tam bir kez teslim receipt'i sağlamaz.

### Sorgu Sözleşmeleri

Bütün yollar /api/examples/v019/range-data/{run} altındadır:

| Metot ve Yol | Gerçek API ve Sonuç |
| --- | --- |
| PUT /rows/{slot} | Spring Data save; 202, sunucunun oluşturduğu kimlik/tenant |
| GET /rows/{slot} | Spring Data findById; 200 veya Redis'te yoksa 404 |
| DELETE /rows/{slot} | Spring Data deleteById; 204, SQL silme onayı değildir |
| GET /native?minimum=0&maximum=9223372036854775807&limit=2 | Üretilen Long bileşik aralık; yalnızca OPEN |
| GET /native?descending=true&cursor=... | Azalan rank ve işaretsiz Redis UTF-8 kimlik sırası |
| GET /native/seek?afterRank=42&afterSlot=a&limit=2 | Artan yönde eşlenmiş Long/kimlik devamı |
| GET /data/page?page=0&limit=2 | rankValue ASC,rowId ASC ile PageRequest; satırlar ve ayrı sayım |
| GET /data/slice?page=0&limit=2 | Toplam sayım yapmayan, bir sonraki satıra bakan Slice |
| GET /data/window?limit=2&cursor=... | Spring Data tipli keyset ScrollPosition/Window |
| GET /data/stats | Türetilen count ve exists; birbirinden bağımsız iki sorgu |

Üretilen native rota queryRedisLongLexicalWindow çağırır; sürüm-4 cursor ile
işaretli Long aralığının tamamını korur. Hızlı skor devamı, doğrulanmış Long53
indeksi ve native yazıcı fence'i gerektirir. Kanıt eksikse veya güvenli değilse
**tam ve sınırlı Redis materyalizasyonu** kullanılır; SQL'e veya yuvarlatılmış
double cursor'a geçilmez. Örnekte HTTP üzerinden yönetimsel indeks yenileme
yolu yoktur. Demo başlangıcı, yalnızca sample-range-rows namespace'i için
yetkili cooperative fence altında indeksleri yeniden kurar; doğrulama başarısızsa
uygulama açılmaz. Bu bakım Redis anahtarlarını tarayabilir; her açılışta yalnızca
yalıtılmış ve küçük demo Redis'inde çalıştırın. Eski protokolde yazıcı bırakmayın.
İstek rotaları strict kalır; bozulmuş indekste tam tarama yapmak yerine hata verir.

Native ve Spring Data cursor'ları farklı sözleşmelerdir; birbirinin yerine
kullanılmaz. Spring Data yolu yalnızca adaptörün $cachedb:cursor konumunu geri
kurar; istemciden keyfî sıralama anahtarları almaz. Native devamda tenant, run,
sınırlar ve yönü; Spring Data devamında tenant ve run değerlerini koruyun.
Sözleşmesi değiştirilmiş tekrarlar reddedilir. ASCII satır kimlikleri bu
örnekte artan eşitlik sırasını karşılaştırılabilir yapar; Redis bayt sırası ile
Java metin sırasının her koşulda aynı olduğu iddia edilmez.

Sonuçlar kaynak verinin tamamını değil Redis'te bulunan kümeyi anlatır.
Native sourceCoverageConfirmed mevcut kapsam bilgisini bildirir; yazmalar bu
bilgiyi kendiliğinden tam olarak işaretlemez. Page toplamı ile count/exists farklı
anları görebilir. Keyset sayfalama snapshot yalıtımı değildir. Cursor'ın gerisine
eklenen kayıtlar yeniden ziyaret edilmez; sıralama alanını değiştiren yazmalar
atlama veya tekrara yol açabilir. TTL/kapasite tahliyesi satırları kaldırabilir.
Değişiklik sonrasında devam etmek geçmiş görünümü yeniden oluşturmaz.

### Sınırlar ve Hata Davranışı

run, 1..32 küçük ASCII harf/rakam; slot, 1..8 karakterdir. rank aralığı
-9223372036854775808..9223372036854775807'dir. Yanıt sınırı 1..32, Page/Slice
offset sınırı 128'dir. Chunked istekler dahil gövde JSON çözülmeden önce 2 KiB,
yol 512, query string 12288, cursor metni 8192 karakterle sınırlıdır.
Demo entity politikası Redis'teki satırları 512 ile, entity TTL'ini 1800 saniye,
sayfa TTL'ini beş saniye ile sınırlar. Bunlar toplam Redis baytı veya bütün
metadata için kota değildir. Geçici veri, Redis maxmemory ve üst katmanda
istek/yeni anahtar hız sınırı kullanın.

Her JVM, bekleme kuyruğu olmadan en fazla 32 etkin aralık/veri işlemi kabul eder.
Kimlik doğrulama gövde okunmadan önce yapılır. Ayrı 32 istekli filter kapısı,
gövde çözümlemesini de sınırlar ve istek bitene kadar kapasiteyi tutar.
Mevcut sonlu Redis/JDBC/havuz zaman aşımları geçerlidir; ek executor oluşturulmaz.
Üst katmanda toplam istek/gövde okuma süresini sınırlayın; yalnızca bayt sınırı,
yavaş yüklemeleri engellemez.
Kimlik doğrulama hatası 401, yanlış rol/tenant sahipliği 403, geçersiz girdi veya
cursor 400, büyük gövde 413, uzun URI 414, kapasite reddi 429'dur.
Kullanılamayan native sorgu/bütçe durumu 409 olur; sürücü hataları başarıya çevrilmez.
İstemcinin zaman aşımı, kabul edilmiş yazmayı iptal etmez veya geri almaz.

Bütün bu yollar boş olmayan X-CacheDB-Causal başlığını ve mevcut causal bağlamını
repository erişiminden önce 409 ile reddeder. Sorgu RYOW garantisi vermez.
Kimlik eşleştirmesi yetkilendirmedir; gövdeye tenant yazmak değildir.
Bu aralık/veri yolları şifreleme, saklama kuralları veya kiracı verilerini silme API'si
sunmaz. Üstteki denetim notu ve şifreli not örnekleri ayrı sözleşmelere sahiptir.
Varsayılan range/data yollarında kiracı silme REST yolu yoktur. Aşağıdaki ayrı,
açıkça etkinleştirilen F3 eşlikçisi kendi sınırlı purge HTTP API'sini sunar.

### Postman ve Sağlayıcı Testleri

[Aralık/veri koleksiyonunu](postman/cache-database-postgresql-range-data.postman_collection.json)
içe aktarın. rangeDataEnabled=true ve rangeDataCertification=true ayarlayın.
Beklenen 4xx cevapları dahil 37 isteğin tamamı gerçek cevap almalı ve kaydedilen
bütün doğrulamaları çalıştırmalıdır. Kabul modunda kapalı özellik atlanmaz,
hata verir. Kabul dışında kapalı koleksiyonu atlamak başarı sayılmaz.

~~~powershell
pwsh -NoProfile -File scripts/test-range-data-postman.ps1 -BaseUrl http://127.0.0.1:8091 -ConfirmDisposableDemo
~~~

Çalıştırıcı iki bayrağı da açar; sabitlenmiş Newman kullanır, raporlardaki bearer
kimliklerini gizler ve 37 kayıtlı isteğin tamamını denetler.
test-v019-postman.ps1 önce mevcut 84 iş isteğini, ardından ayrı koleksiyonu
çalıştırır. Eski 61+23 akışını değiştirmez veya kısaltmaz. Koleksiyonlar yalnızca
geçici demo verisine yazmalıdır.

RangeDataExternalIT, üretilen rotaları ve Spring Data işlemlerini gerçek
RedisEntityRepository ve bağımsız beklenen kayıtlarla karşılaştırır. Tam Long
değerleri, eşitlikler, azalan sıra, silinen devam satırı, tenant/cursor/causal
reddi ve gerçek sağlayıcıda JDBC write-behind değerinin tamlığı kapsanır.
Harici servis testleri mevcut yalıtılmış CACHEDB_TEST_NAMESPACE ile datasource/
Redis değişkenlerini gerektirir; alternatif konteyner başlatmaz.

<!-- BEGIN CACHE API MATRIX -->
### REST, Kod ve İstek Matrisi

Her satır gerçek controller metoduna ve kaydedilmiş istek sayısına bağlanır.
Bu tablo çalışma zamanı veya performans kabulü değildir.

| Senaryo | REST | Java | İstek Sayısı |
| --- | --- | --- | --- |
| method-quote | GET /api/examples/v019/cache/quotes/{sku} | [CacheApiController.quote](src/main/java/com/example/cachedb/sample/cacheapi/CacheApiController.java) | 7 |
| method-refresh | POST /api/examples/v019/cache/quotes/{sku}/refresh | [CacheApiController.refresh](src/main/java/com/example/cachedb/sample/cacheapi/CacheApiController.java) | 1 |
| method-evict | DELETE /api/examples/v019/cache/quotes/{sku} | [CacheApiController.evict](src/main/java/com/example/cachedb/sample/cacheapi/CacheApiController.java) | 1 |
| method-clear | DELETE /api/examples/v019/cache/quotes | [CacheApiController.clear](src/main/java/com/example/cachedb/sample/cacheapi/CacheApiController.java) | 4 |
| method-null | GET /api/examples/v019/cache/nullable/{sku} | [CacheApiController.nullable](src/main/java/com/example/cachedb/sample/cacheapi/CacheApiController.java) | 2 |
| offer-save | PUT /api/examples/v019/async/offers/{id} | [CacheApiController.save](src/main/java/com/example/cachedb/sample/cacheapi/CacheApiController.java) | 1 |
| offer-hot | GET /api/examples/v019/async/offers/{id} | [CacheApiController.hot](src/main/java/com/example/cachedb/sample/cacheapi/CacheApiController.java) | 1 |
| offer-source | GET /api/examples/v019/async/offers/{id}/source | [CacheApiController.source](src/main/java/com/example/cachedb/sample/cacheapi/CacheApiController.java) | 1 |
| stock-write | PUT /api/examples/v018/stock/{id} | [ControlsController.stockPut](src/main/java/com/example/cachedb/sample/controls/ControlsController.java) | 2 |
| stock-one | GET /api/examples/v019/reactive/stock/{id} | [CacheApiController.stock](src/main/java/com/example/cachedb/sample/cacheapi/CacheApiController.java) | 2 |
| stock-list | GET /api/examples/v019/reactive/stock | [CacheApiController.stocks](src/main/java/com/example/cachedb/sample/cacheapi/CacheApiController.java) | 1 |
| range-save | PUT /api/examples/v019/range-data/{run}/rows/{slot} | [RangeDataController.save](src/main/java/com/example/cachedb/sample/rangedata/RangeDataController.java) | 8 |
| range-point | GET /api/examples/v019/range-data/{run}/rows/{slot} | [RangeDataController.read](src/main/java/com/example/cachedb/sample/rangedata/RangeDataController.java) | 3 |
| range-delete | DELETE /api/examples/v019/range-data/{run}/rows/{slot} | [RangeDataController.delete](src/main/java/com/example/cachedb/sample/rangedata/RangeDataController.java) | 1 |
| range-window | GET /api/examples/v019/range-data/{run}/native | [RangeDataController.nativeRange](src/main/java/com/example/cachedb/sample/rangedata/RangeDataController.java) | 13 |
| range-seek | GET /api/examples/v019/range-data/{run}/native/seek | [RangeDataController.nativeSeek](src/main/java/com/example/cachedb/sample/rangedata/RangeDataController.java) | 1 |
| data-page | GET /api/examples/v019/range-data/{run}/data/page | [RangeDataController.page](src/main/java/com/example/cachedb/sample/rangedata/RangeDataController.java) | 3 |
| data-slice | GET /api/examples/v019/range-data/{run}/data/slice | [RangeDataController.slice](src/main/java/com/example/cachedb/sample/rangedata/RangeDataController.java) | 1 |
| data-window | GET /api/examples/v019/range-data/{run}/data/window | [RangeDataController.window](src/main/java/com/example/cachedb/sample/rangedata/RangeDataController.java) | 3 |
| data-stats | GET /api/examples/v019/range-data/{run}/data/stats | [RangeDataController.stats](src/main/java/com/example/cachedb/sample/rangedata/RangeDataController.java) | 4 |
<!-- END CACHE API MATRIX -->

## Metot Önbelleği ve Asenkron API'ler (0.19)

İsteğe bağlı metot önbelleği ve asenkron/reactive senaryoları, sağlayıcı starter'ı
ve üretilen repository sınıflarıyla aynı CacheDB sürümünü kullanır. Örnekte
cachedb-reactor bağımlılığı ${cachedb.version} ile optional olarak tanımlıdır.
WebFlux, bağımsız bir Reactor sürümü, scheduler veya ikinci executor eklenmez.

API, demo profili dışında varsayılan olarak kapalıdır. Sağlayıcının veritabanı
ve Redis hızlı başlangıcını tamamladıktan sonra, Java 21 ile bu örneğin
dizininde aşağıdaki tam komutu çalıştırın. Ek metot önbelleği bayrağı gerekmez:

~~~shell
mvn -B -ntp spring-boot:run "-Dspring-boot.run.profiles=demo"
~~~

Demo profili sample.cache-api.enabled ve cachedb.spring-cache.enabled
özelliklerini açar. quotes ve nulls önbellekleri tenant kapsamındadır;
değer sınırları 4096 bayttır. quotes için Redis TTL'i beş dakika, L1 sınırı
256 kayıt, 1 MiB ve on saniyedir. nulls, null değerlerini L1 olmadan
30 saniye saklar. Kabul sınırları 128 işlem, 1024 yükleme anahtarı ve
4096 bekleyen çağrıdır. Her sağlayıcı demo-methods-postgresql önekini kullanır;
bağımsız her dağıtım/veritabanı için ayrı SAMPLE_METHOD_CACHE_PREFIX seçin.
Bunlar demo sınırlarıdır; üretim kapasitesi önerisi değildir.

Hızlı başlangıçtaki veritabanı, Redis ve worker ayarlarını koruyun.
Harici servisler DB_URL, DB_USERNAME, DB_PASSWORD ve REDIS_URI değişkenlerini
kullanır. Varsayılan demo kimlikleri demo-supplier, demo-shop-a ve demo-shop-b'dir.
SAMPLE_CONTROLS_SUPPLIER_TOKEN, SAMPLE_CONTROLS_TENANT_A_TOKEN ve
SAMPLE_CONTROLS_TENANT_B_TOKEN ile değiştirdiğinizde istemciyi de aynı
değerlerle ayarlayın. Demo yazma araçlarını herkese açık erişime açmayın.
ControlsAccess doğrulanan kimlikleri shop-a/shop-b tenant'larına eşler.
İstekteki tenant kimliği veya causal token yetkilendirme değildir.
Demo varsayılanları üretim kimlik bilgisi olarak kullanılmamalıdır.
Demo olmadan yeni API'lerin iki özellik kapısı da kapalı kalır.

[CacheApiConfiguration](src/main/java/com/example/cachedb/sample/cacheapi/CacheApiConfiguration.java)
annotation desteğini açar ve quotes/nulls önbelleklerinin tenant kapsamında
olmasını zorunlu tutar. Bellek içi manager veya sınırsız executor kullanmak yerine
uyumsuz yapılandırmada başlangıcı durdurur. Runtime'ın sahip olduğu async gate
ve yeniden üretilmiş repository binding'leri gerekir. Uygulamanın
CachingConfigurer bean'i değiştirilmez; MethodCacheService açıkça
cacheDbKeyGenerator ve cacheDbCacheManager seçer.

### Entity Kalıcılığından Ayrı Metot Sonuçları

[MethodCacheService](src/main/java/com/example/cachedb/sample/cacheapi/MethodCacheService.java)
tenant/SKU/şube ve yeni bir belirteç içeren, boyutu sınırlı String hesaplar.
Bu işlem SQL sorgusu yapmaz. Sonucun tekrar gelmesi metot önbelleğini gösterir;
SQL güncelliği veya stok rezervasyonu garantisi değildir. Aşağıdaki annotation'lar
gerçek sınıftandır:

~~~java
@CacheConfig(cacheManager = "cacheDbCacheManager", keyGenerator = "cacheDbKeyGenerator")
public class MethodCacheService {
    @Cacheable(cacheNames = "quotes", sync = true)
    public String quote(String sku, int branch) { return calculate(sku, branch); }
    @CachePut(cacheNames = "quotes")
    public String refresh(String sku, int branch) { return calculate(sku, branch); }
}
~~~

Tam sınıfta hesaplama, null döndürebilen okuma ve geçersizleştirme metotları da
bulunur. İki argüman tipli, tam uzunluk çerçeveli anahtar üreticisini kullanır;
refresh aynı argümanlarla aynı kaydı günceller. Keyfî toString, Java nesne
serileştirmesi veya reflection ile SimpleKey parametresi okuma yoktur.
Geçerli L1 isabeti sıfır Redis I/O yapar. Diğer replikadaki görünürlük, uzaktaki
yazmanın dönüşüyle değil geçersizleştirme bildiriminin işlenmesiyle oluşur.
TTL, tespit edilmemiş ağ kopmasında doğrusal tutarlılık sağlamaz. Daha güçlü
garanti gerektiğinde L1'i kapatın.

Aşağıdaki bütün yollar /api/examples/v019 altındadır. Önbellek ve stok
yollarında tenant kimliğini, teklif yollarında tedarikçi kimliğini kullanın:

| Metot ve Yol | Sözleşme |
| --- | --- |
| GET /cache/quotes/{sku}?branch=7 | Sunucunun seçtiği tenant ile önbellekteki String; 200 |
| POST /cache/quotes/{sku}/refresh?branch=7 | Yeniden hesaplama ve aynı anahtara @CachePut; 200 |
| DELETE /cache/quotes/{sku}?branch=7 | Yalnızca bir mantıksal anahtarı çıkarır; 204 |
| DELETE /cache/quotes | Nesil artırarak yalnızca doğrulanmış tenant'ı temizler; 204 |
| GET /cache/nullable/MISSING-ITEM | Açık null değeri önbelleğe alır, value:null döner; 200 |
| PUT /async/offers/{id} | Gerçek WriteReceipt ile 202; kaynak commit'i doğrulanmaz |
| GET /async/offers/{id} | Yalnızca Redis'ten async sıcak okuma; kaçırma 409'dur |
| GET /async/offers/{id}/source | Açık asenkron kaynak okuması; bulunmayan satır 404'tür |
| GET /reactive/stock/{id} | Doğrulanmış abone bağlamıyla soğuk Reactor sıcak okuması |
| GET /reactive/stock?ids=1,2 | Sınırlı Reactor listesi; 1..32 pozitif kimlik |

SKU, 1..32 büyük harf/rakam/alt çizgi/tire; şube ise 0..999 aralığındadır.
Komut gövdeleri, chunked istekler dahil, JSON çözülmeden önce 4 KiB ile
sınırlanır (413). Query string en fazla 4096 karakterdir (414).
Gövde doğrulaması ayrıca teklif verilerini ve değerleri sınırlar. Varsayılan codec
skaler metot değerlerini saklar; keyfî POJO'ları serileştirmez.

### Receipt ve Reactor Bağlamı

[CacheApiOperations](src/main/java/com/example/cachedb/sample/cacheapi/CacheApiOperations.java),
SupplierOfferRepository.asyncWithReceipt() kullanır. Gerçek receipt içindeki
entity, kimlik, namespace'ten türetilmiş causal token ve pozitif sürüm korunur.
202 yalnızca Redis kabulüdür; sqlDurabilityConfirmed her zaman false döner.
Kaynak kalıcılığını ayrı source yoluyla gözleyin. Redis causal token'ını SQL
kalıcılığı kanıtı olarak bu yola göndermeyin. Yazmalar otomatik tekrarlanmaz.

ReactiveEntityRepositoryFactory mevcut stok repository'sinin async
bağdaştırıcısını alır. Publisher'lar soğuktur; her abonelik yeni ve sınırlı
iş başlatır. CacheApiOperations.stock/stocks, CacheReactorContext.TENANT ve
CAUSAL_TOKEN değerlerini sağlar. Tenant yalnızca ControlsAccess üzerinden gelir.
Mevcut nedensellik gereksinimleri abone bağlamına aktarılmadan önce birleştirilir;
worker veya administrative bağlam kendiliğinden taşınmaz. Spring MVC,
toFuture() ile oluşturulan future'ı alır; block(), get(), join() veya WebFlux
bağımlılığı kullanılmaz.

Tenant stok satırı oluşturmak için mevcut PUT /api/examples/v018/stock/{id}
komutunu kullanın. Dönen X-CacheDB-Causal receipt başlığını reactive stok
okumasına gönderin. Gövde stockId, warehouse, status ve availableUnits içerir.
En küçük async teklif gövdesi şöyledir:

~~~json
{"supplier":"cache-api","status":"ACTIVE","unitPrice":12.3400,"availableUnits":7}
~~~

Metot önbelleği yolları, sıcak L1 değeri olsa bile, boş olmayan
X-CacheDB-Causal gereksinimini metot çağrısından önce 409 ile reddeder.
Entity RYOW sağladığını varsaymaz. Async/reactive entity okumaları sürüm
korumalarını sürdürür. Eksik kimlik bilgisi 401, yanlış rol veya başka tenant'ın
verisine erişim 403, geçersiz sınırlı girdi 400'dür. Async kabul reddi
Retry-After başlığıyla 429 döner. Üç saniyelik cevap süresi aşılınca 504 döner;
sürücü işlemi devam edebilir ve fiziksel işlem kapasitesini tutar.
İptal/zaman aşımı rollback değildir. Redis/JDBC/havuz zaman aşımlarını ve kaynak
işlem sürelerini ayrıca sınırlandırın. Runtime kabul sınırını CPU, bellek ve havuz
kapasitesine göre seçin. TTL toplam Redis baytını değil yaşam süresini sınırlar;
maxmemory ve üst katmanda istek/yeni anahtar hızı sınırları uygulayın.

@CachePut ve varsayılan @CacheEvict kaynak yetkilendirmesi değildir.
Controller doğrulanmış kapsamı çağrıdan önce denetler; örneğin eviction
işlemleri beforeInvocation=true kullanır. Bu davranış veya önbellek temizliği
kaynak işini transaction yapmaz. Tenant/causal/codec hatalarını yutmayın.

### Çalıştırılabilir Postman Akışı

Mevcut [örnek koleksiyonu](postman/cache-database-postgresql-sample.postman_collection.json),
23 istekli "07 Method Cache and Async" klasörünü içerir. Uygulama başladıktan sonra
bu klasörü çalıştırın; sunucuyla eşleşen kimlik bilgilerini sağlayın. Klasörü
göndermek için cacheApiEnabled=true ayarlayın. Kabul çalıştırmalarında ayrıca
cacheApiCertification=true zorunludur; kapalı özellik atlanmak yerine hata verir.
Kabul modu dışında kapalı klasör atlanır; bu, çalıştırma başarısı değildir.
Kabul için 23 isteğin tamamı JSON raporunda gerçek cevap ve başarılı,
atlanmamış doğrulama içermelidir. Tek başına sıfır çıkış kodu klasörün
çalıştığını kanıtlamaz.
[pm.execution.skipRequest](https://learning.postman.com/v11/docs/tests-and-scripts/write-scripts/postman-sandbox-reference/pm-execution)
destekleyen bir çalıştırıcı kullanın.

~~~shell
newman run postman/cache-database-postgresql-sample.postman_collection.json \
  --folder "07 Method Cache and Async" \
  --env-var cacheApiEnabled=true \
  --env-var cacheApiCertification=true \
  --env-var baseUrl=http://127.0.0.1:8091 \
  --env-var cacheSupplierToken=demo-supplier \
  --env-var cacheTenantAToken=demo-shop-a \
  --env-var cacheTenantBToken=demo-shop-b \
  --timeout-request 5000 --timeout-script 25000 \
  --reporters cli,json \
  --reporter-json-export ../target/sample-api-reports/cache-api-postgresql.json
~~~

Akış farklı demo kimlikleri ayırır; isabet/yenileme/tek anahtar çıkarma/nesil
temizliği/null/tenant ayrımını denetler. Gerçek async receipt doğrulanır;
kaynak ayrıca gözlenir. Reactive tekil ve sınırlı liste okumaları doğrulanmış
tenant bağlamını ve stok receipt token'ını kullanır. Bildirim beklemeleri
sınırlıdır; kaynak gözlemi 20 saniye içinde en fazla 40 denemeye izin verir.
Hazır olmama veya kalıcılığın gözlenememesi, 202'den başarı çıkarılarak gizlenmez.
Bu örnekler pod'lar arası doğrusal tutarlılık, fiziksel sıfır ağ veya performans
kanıtı değildir.

Mevcut 61 isteğin ve 23 metot önbelleği/asenkron isteğinin tamamını kapsayan
iş akışını, önceden başlatılmış ve verileri silinebilir bir demo üzerinde
aşağıdaki komutla çalıştırın. Bu çalıştırıcı altyapı başlatmaz;
Java/Maven komutu gerektirmez:

~~~shell
pwsh -NoProfile -File scripts/test-v019-postman.ps1 -BaseUrl http://127.0.0.1:8091 -ConfirmDisposableDemo
~~~

Çalıştırıcı cacheApiEnabled ve cacheApiCertification değerlerini her zaman açar.
84 farklı isteğin cevabını, istek/betik/doğrulama hatalarını ve atlanan veya
eksik doğrulamaları denetler. Sınırlı bekleme döngülerinin son doğrulamaları da
zorunludur. Eksik özellik veya atlanan klasör hata verir; HTTP 202, kaynak
kalıcılığı kanıtı değildir. Katalog ve 0.18 denetimleri ayrı 23 ve 40 istekli
sözleşmelerini korur. test-catalog-postman.ps1 yalnızca belgelenmiş, kendisine
ait geçici altyapı kurulumu için kullanılmalıdır; tam iş akışını da çalıştırır.
Hazır harici servisler için test-v019-postman.ps1 çalışan demoya doğrudan
yöneltilebilir.

Raporlar her seferinde yeni bir target/v019-postman dizinine yazılır.
Demo istek/cevap verilerini içerdiğinden raporları özel tutun.
Sağlanan bearer kimlik bilgileri raporlarda maskelenir; geçici ortam dosyası
silinir. Veri değiştiren bu örnek akışı üretim verileri üzerinde çalıştırmayın.

## Sürüm ve Okuma Taşıması

Bu örnek; sağlayıcı, BOM, processor ve plugin için eşleşen 0.19.0 paketlerini
kullanır. cachedb-reactor ve cachedb-spring-data sürümlerini de aynı tutun.
Uygulamayı başlatmadan önce bu paket kümesinin tamamını edinin veya kurun.
Eski üretilmiş binding'ler ile yeni runtime modüllerini karıştırmayın.

Redis nokta okumalarını birleştirme, demo dahil **bu örneklerde isteğe bağlıdır**.
Örnek açıkça
cachedb.redis.read-coalescing.enabled=${SAMPLE_READ_COALESCING_ENABLED:false}
ayarını kullanır. Bu, kütüphanenin varsayılanıyla aynıdır; birleştirmenin her
iş yükünü hızlandırdığı veya dağıtımınız için performans kabulünden geçtiği
anlamına gelmez. Birleştirmeyi kapatmak, sınırlı native asenkron okumaları kapatmaz.

Önce belgelenen demo komutunu ve false varsayılanını kullanın. Birleştirmeyi ayrı
değerlendirmek için aynı uygulamayı başlatmadan önce
SAMPLE_READ_COALESCING_ENABLED=true ayarlayın. Hem tek çağıranda hem temsilî eş
zamanlı yükte throughput, p99 gecikmesi, CPU ve tutulan belleği karşılaştırın;
Redis topolojisi, veri boyutu, bağlantı ve CPU sınırları aynı kalmalıdır.
İşlevsel Postman sonucu performans kabulü değildir. Ölçülen gerileme bütçenizi
aşıyorsa bayrağı geri alın. Kuyruk, yanıt baytı, zaman aşımı veya sahiplik
sınırlarını kaldırarak sonucu düzeltmeye çalışmayın.

Demo profili belgelenen önbellek/aralık API ayarlarını açar. Demo dışında
sample.cache-api.enabled ve sample.range-data.enabled varsayılan olarak false'tur.

## 0.18.1 ile Okuma ve Yazma Kontrolleri

[Çalışan 0.18 senaryoları](#cachedb-018-çalışan-senaryolar); tedarikçi tekliflerini,
toplu L1 okumayı, soft TTL'yi, gruplamayı, bileşik indeksleri ve bağımsız karma
yazmaları gösterir. Depo örneği tenant kapsamında batch, partition, projection
ve minimum sürüm okumasını kapsar. Yeni demo API'si ayrı kimlik eşleştirmesi kullanır;
mevcut REST sözleşmeleri değişmez. Tam Java kaynakları, beklenen sonuçlar ve
Postman istekleri aşağıdadır.

Bu PostgreSQL örneği, aynı sürümdeki onaylı BOM, processor, starter ve doctor
paketlerinin tamamını gerektirir. Paketler erişilebilir olduğunda framework
kaynak kodunu indirip derlemek gerekmez. Java 21 gerekir. Geçiş ve tutarlılık için
[kontrol rehberini](https://github.com/esasmer-dou/cache-database/blob/main/tr/docs/aktif-veri-performans-kontrolleri.md)
inceleyin.

Önce mevcut hızlı başlangıç, seed ve ön yükleme adımlarını tamamlayın. Ardından:

Mevcut PATCH ve sorgu yolları, 0.17'nin toplu JDBC yazmalarından, sınırlı indeks
okumalarından ve son erişim sıralı L1'inden yararlanır. Yeni delta, toplam, tenant,
watcher ve şema/sıkıştırma API'leri isteğe bağlıdır. Örnek bunları kendiliğinden
açmaz veya tenant yetkilendirmesi eklemez. Kendi entity ve dağıtım sözleşmenize
uyarlamadan önce framework'ün [senaryo rehberini](https://github.com/esasmer-dou/cache-database/blob/main/tr/docs/deklaratif-veri-kontrolleri.md)
inceleyin.

1. `CatalogConfiguration`, aktif ürünleri
   `ProductEntityCacheBinding.Columns.ACTIVE_STATUS` ile seçer,
   `Columns.PRODUCT_ID` ile kapsamı belirler ve
   `autoDirtyOnRootEntity()` ile değişiklikleri izler.
2. Repository'deki dar güncelleme derleme sırasında doğrulanır:

```java
@CachePatch
WriteReceipt<ProductEntity, Long> patchStockValues(
        @CacheId Long productId,
        @CacheColumn("stock_quantity") Integer stockQuantity,
        @CacheColumn("reserved_quantity") Integer reservedQuantity,
        @CacheColumn("stock_status") String stockStatus,
        @CacheColumn("updated_at") Long updatedAt);
```

3. `ProductApplicationService.replaceStock`, `0 <= reserved <= stock`
   kuralını denetler, stok durumunu hesaplar ve bu metodu çağırır. Ürün adı,
   kategori ve fiyat değişmez; SQL'e tam satır UPSERT gönderilmez.

```java
return products.patchStockValues(productId, stock, reserved, status,
        Instant.now(clock).getEpochSecond());
```

4. Artırma komutu değil, **yeni mutlak değerleri** gönderin:

```http
PATCH http://127.0.0.1:8091/api/products/1/stock-values
Content-Type: application/json

{"stock": 20, "reserved": 2}
```

HTTP 202, Redis'in komutu kabul ettiğini belirtir; SQL'e aktarımın bittiği
anlamına gelmez. Redis'te tam kayıt yoksa receipt içinde `entity: null` olabilir.
Eksik alanlardan ürün uydurulmaz. İsteğe bağlı alanlarla çalışan eski `/stock`
endpoint'i korunur. `/stock-values` için iki alan da zorunludur; eksik veya
geçersiz istek 400 döndürür.

5. Kataloğun otomatik yenilenmesi için uygulamayı
   `SAMPLE_CATALOG_SCHEDULE_ENABLED=true` ile başlatın. Örnekte aralık bir
   saniye, eşik 20 kayıttır. SQL'e aktarım tamamlanınca ürünün kök kimliği
   yenileme listesine alınır ve yalnızca ilgili katalog kaydı hazırlanır.
   `GET /api/catalog/items/1` ile sonucu izleyin. Zamanlamayı açmadıysanız
   mevcut elle yenileme endpoint'lerini kullanın. Uygulama dışı SQL değişiklikleri
   için açık yenileme, CDC veya periyodik tam uzlaştırma gerekir.

`ProductEntity` için L1 de açıkça etkinleştirilmiştir: 1024 kayıt, iki saniye,
tahmini 4 MiB sınırı. Tekrarlanan ürün detayı okumaları Redis isteği yapmadan
yanıtlanabilir; her yanıtta ayrı bir nesne çözülür. Pod'lar arası geçersiz kılma
asenkron çalışır, anlık tutarlılık garantisi vermez. Her okuma Redis'e gitmeli ise
`@CacheNearPolicy` tanımını kaldırın. Framework uygulama başına tek bir abonelik
bağlantısı açar; arka plan havuzunda bu bağlantı için yer bırakın.

Birden fazla kaydı birlikte değiştirmek için:

```java
var receipt = database.inTransaction(tx -> {
    tx.put(product);
    tx.putAll(otherProducts);
});
boolean durable = database.awaitDurable(receipt, Duration.ofSeconds(5));
```

Bu ertelenmiş kapsamda receipt döndüren `saveAll` yerine `tx.putAll` kullanın.
Gerçek kabul sonucu ancak commit sonrasında oluşur. Commit öncesi hata yazmaları
iptal eder; kabulden sonra SQL bekleme süresinin dolması rollback değildir.
Sınırlar 100 kimlik, 4 MiB ve 2000 kuyruk alanıdır. Cluster aynı hash-tag kapsamını
ister; sınırlı tenant kabul kuralları atomik işlemle atlanamaz.

[![Consumer build](https://github.com/esasmer-dou/sample-cache-database-postgresql/actions/workflows/consumer-build.yml/badge.svg?branch=main)](https://github.com/esasmer-dou/sample-cache-database-postgresql/actions/workflows/consumer-build.yml)
[![CacheDB 0.19.0 documentation](https://img.shields.io/badge/CacheDB-0.19.0_docs-0b7285.svg)](https://github.com/esasmer-dou/cache-database/releases)

Bu proje, CacheDB'nin Redis 8 ve PostgreSQL ile nasıl kullanılacağını gösteren
bir Spring Boot REST API örneğidir. Canlı ortamda verilmesi gereken kararları
açıkça gösterir: operasyonel yollar Redis'teki sınırlı aktif veri setini
kullanır, kalıcı geçmiş PostgreSQL'de tutulur, büyüyen listeler ise bütün nesne
ağacı yerine projection üzerinden okunur.

> Bu örnek CacheDB `0.19.0` sürümünü kullanır. Eşleşen paketlerin tamamını
> yapılandırılmış Maven deposundan veya kurumunuzun onaylı paket kaynağından
> edinin. Önceki bir sürümün mevcut olması, 0.19.0 paketlerinin hazır olduğunu göstermez.

Hızlı başlangıcı tamamladığında canlı ortama benzeyen tek bir yolu baştan sona
doğrulamış olacaksın: kalıcı PostgreSQL verisini oluşturacak, sınırlı arşiv
yolunu okuyacak, karşılık gelen Redis projection'ını hazırlayacak, aktif yolu
çağıracak ve kapsam ile kalıcılık sinyallerini inceleyeceksin. Redis'te kayıt
bulunmaması arka planda gizli bir PostgreSQL sorgusu başlatmaz.

## CacheDB 0.19.0 Sürümüne Geçiş

Örnek POM; BOM, sağlayıcı starter'ı, annotation processor ve doctor plugin'ini
`0.19.0` ile eşitler. Binding'lerin yeniden üretilmesi için örnek projeyi derleyin.
POM ayrıca `jedis.version=5.2.0` ayarını içerir. Bu ayar olmadan Spring Boot parent,
gerekli pipeline API'sini içermeyen eski sürücüyü seçebilir.

- Mevcut REST, arşiv, projection ve ön yükleme yollarının açık davranışı korunur.
- Mevcut SQL worker'larını yükseltmeden önce sağlayıcınıza ait `cachedb_write_fence` migration'ını uygulayın, eski worker'ları devreden çıkarın ve sürüm sayaçlarını koruyun. Ayrı metadata tablosu, geciken eski yazmanın silinmiş kaydı yeniden oluşturmasını engeller; iş tabloları değişmez. [Kalıcı yazma sürümleri](https://github.com/esasmer-dou/cache-database/blob/main/tr/docs/kalici-yazma-surumleri.md) rehberini izleyin.
- Tipli snapshot, Jackson'sız codec ve atomik ürün aktarımı çalışan katalog senaryosunda kullanılır. Ayrı API/worker profilleri de bulunur. [Katalog senaryolarını](#bildirimsel-katalog-senaryoları) adım adım izleyin.
- Katı 50 ms CI hedefi, ilk kullanım dâhil üretilen codec/snapshot senaryosu içindir. REST, SQL, ağ veya canlı sistem SLA'sı değildir; eski mapper API'lerinin uyumluluğu ve tüm JUnit metot süreleri ayrıca raporlanır.
- `hotEntityLimit=0` entity kabulünü kapatır. `-1` değerini yalnızca bilinçli bir sınırsız kayıt politikası için kullanın. Varsayılan 1.000 olarak korunur.
- SQL kolonunuz NULL alabiliyorsa `Boolean` kullanın; primitive `boolean`, SQL NULL değerini reddeder.
- Atomik katalog aktarımı ve indeksli snapshot sayfaları açık endpoint'lerle gösterilir. Redis Cluster dağılımı isteğe bağlı framework altyapısıdır; yerel Compose tek Redis kullanır.

Bu API'leri eklemeden önce [0.19.0 sürüm notlarını](https://github.com/esasmer-dou/cache-database/blob/main/tr/docs/releases/v0.19.0.md),
[geçiş rehberini](https://github.com/esasmer-dou/cache-database/blob/main/tr/docs/upgrading-to-0.19.md)
ve [sınırlı çalışma sözleşmelerini](https://github.com/esasmer-dou/cache-database/blob/main/tr/docs/sinirli-calisma-islemleri.md)
okuyun. Paketin kullanılabilir olması, uygulamanızın canlı ortam topolojisini doğrulamaz.

## Kolon Güvenliği

Örnek uygulamadaki entity'ler bilerek dar tutulur. Sağlayıcıya göre belirlenen kontroller, 0.19.0 bağımlılığı
ile devrededir; ek cache politikası veya iş tablosu değişikliği gerekmez.
Redis'te daha geniş veri kabul edilmesi, SQL tablosunun sınırını kaldırmaz.

| Veri yüzeyi | Varsayılan sayı sınırı |
| --- | --- |
| PostgreSQL tablosu | 1600 fiziksel kolon |
| SQL Server standart tablosu | 1024 fiziksel kolon |
| Oracle standart tablosu | 1000 fiziksel kolon |
| Redis entity/projection verisi | 4096 alan; veri boyutu ve bellek bütçeleri ayrıca geçerlidir |

JDBC pozitif kapasite bildirirse, 4096'yı aşmamak koşuluyla bu değer kullanılır.
Kapasite bilinmiyorsa sağlayıcının varsayılanı esas alınır. ID, sürüm ve mantıksal
silme kolonları toplama katılır. Örneğin PostgreSQL'de ID dâhil 1199 eşlenmiş
kolona ayrı sürüm kolonu eklenirse toplam 1200 olur. Kolon sayısı denetimi geçer;
aynı tanım standart Oracle ve SQL Server sınırını aşar. Satır genişliği ve SQL
parametre bütçesi de uygun olmalıdır. Bir rapor ekranında 1200 alanın tümünü
yüklemek yerine dar bir projection kullanın.

İsteğe bağlı, tek seferlik dağıtım kontrolü için aşağıdaki yardımcı metodu
**üretilmiş entity'ler kaydedildikten sonra**, trafik açılmadan önce çağırın.
Her REST isteğinde çalıştırmayın; ikinci bir otomatik warm döngüsü eklemeyin:

```java
import javax.sql.DataSource;
import com.reactor.cachedb.core.config.SchemaBootstrapConfig;
import com.reactor.cachedb.starter.CacheDatabase;
import com.reactor.cachedb.starter.CacheDatabaseSchemaAdmin;

public final class SampleSchemaSafety {
    private SampleSchemaSafety() {}

    public static void verify(CacheDatabase database, DataSource source) {
        var schema = new CacheDatabaseSchemaAdmin(
                source, database.entityRegistry(), SchemaBootstrapConfig.defaults());
        var result = schema.validate();
        if (!result.success()) {
            throw new IllegalStateException("Schema validation failed: " + result.issues());
        }
    }
}
```

Bu kontrol, iş tablosu oluşturmadan kayıtlı eşlemeleri doğrular. Uyumsuz tanım,
normal trafik başlamadan raporlanır. Write-behind da iş tablosuna SQL göndermeden
önce kolon ve parametre sayısını denetler. JDBC metadata davranışı sürücüye bağlıdır.

1024 alanı aşan veri üretmeden önce tüm okuyucu ve worker'ları yükseltin: eski
0.15.0 okuyucuları bu veriyi okuyamaz. 0.15.0 kalıcı sürüm koruma tablosu zaten
kuruluysa bu yama için ek iş tablosu migration'ı gerekmez. Oracle extended modu
kendiliğinden açılmaz; standart Oracle test kurulumu bu modun kanıtı değildir.

Sürücü varsayılanları, record constructor sınırları ve migration denetimleri için
[kolon sınırları rehberini](https://github.com/esasmer-dou/cache-database/blob/main/tr/docs/sema-kolon-sinirlari.md) okuyun.

## Buradan Başla

| Hedefin | İlgili bölüm |
| --- | --- |
| Örneği çalıştırmak | [Hızlı Başlangıç](#hızlı-başlangıç) |
| Redis ve PostgreSQL davranışını anlamak | [Çalışma Zamanı Sözleşmesi](#çalışma-zamanı-sözleşmesi) |
| Deklaratif Java API'sini görmek | [Kod Üzerinden Akış](#kod-üzerinden-akış) |
| PostgreSQL'deki mevcut veriyi Redis'e hazırlamak | [Mevcut Veriyi Hazırlama](#mevcut-veriyi-hazırlama) |
| Cache sınırlarını belirlemek | [Kullanım Senaryosuna Göre Ayar](#kullanım-senaryosuna-göre-ayar) |
| Tüm yolları denemek | [API Kataloğu](#api-kataloğu) veya [Postman](#postman) |
| Tipli snapshot, atomik aktarım ve ayrı worker'ları çalıştırmak | [Bildirimsel Katalog Senaryoları](#bildirimsel-katalog-senaryoları) |
| Canlı ortam hazırlığını kanıtlamak | [Production Sertifikası](#production-sertifikası) |
| Canlı ortam geçişini hazırlamak | [Canlı Ortam Kontrol Listesi](#canlı-ortam-kontrol-listesi) |
| Başlangıç veya veri yolu sorununu çözmek | [Sorun Giderme](#sorun-giderme) |

## Bu Örnek Ne Öğretiyor?

Örnek, basit bir CRUD uygulamasından daha geniş bir alanı kapsar:

- müşteriler sipariş verir; siparişlerin çok sayıda satırı olabilir
- ürün uygunluğu katalog ve düşük stok ekranlarını besler
- gönderiler aktif, istisna, hareket ve arşiv yollarına ayrılır
- destek talepleri operasyon paneline veri sağlar
- rapor işleri ve denetim olaylarında anlık iş yükü ile kalıcı geçmiş ayrılır

Ürünün sınırı da aynı açıklıkla gösterilir:

| Sınıflandırma | Anlamı |
| --- | --- |
| **BEST** | Sınırlı bir operasyonel yol tanımla, entity veya projection verisini hazırla, ölç ve arşiv/geçmiş okumalarını PostgreSQL'de tut. |
| **ACCEPTABLE** | Redis'teki aktif veri setinin dışında kalan ve seyrek okunan veri için sınırlı bir PostgreSQL yolu kullan. |
| **ANTI-PATTERN** | CacheDB'yi şeffaf bir cache gibi görüp Redis'te bulunmayan her sorgunun otomatik SQL çalıştırmasını ve Redis'i doldurmasını bekleme. |

CacheDB, hangi ekran ve komutların öngörülebilir düşük gecikmeye ihtiyaç
duyduğunu bilen ekipler için güçlü bir çözümdür. Temel iş yükü bütün veritabanı
üzerinde sınırsız ve anlık sorgular çalıştırmak olan uygulamalar için uygun
değildir.

## Mimari

```mermaid
flowchart LR
    Client["REST istemcisi"] --> API["Controller"]
    API --> Service["Uygulama servisi"]
    Service --> Repo["Üretilen CacheDB repository"]
    Repo -->|"HotRoute / CacheLookup"| Redis[(Redis 8 aktif veri seti)]
    Repo -->|"SourceRoute"| PostgreSQL[(PostgreSQL kalıcı geçmiş)]
    Repo -->|"Komut"| Stream["Redis Stream write-behind"]
    Stream --> Worker["Sınırlı kalıcılık işçisi"]
    Worker --> PostgreSQL
    PostgreSQL -->|"WarmRoute"| Warm["Ön yükleme / backfill işi"]
    Warm --> Redis
```

Uygulama kodu repository interface'lerine bağımlıdır. Annotation processor;
implementasyonları, codec'leri, indeksleri, projection binding'lerini ve Spring
bean'lerini derleme sırasında üretir. Entity keşfi için çalışma zamanı
reflection'ı kullanılmaz.

## Kısa Sözlük

| Terim | Bu örnekteki anlamı |
| --- | --- |
| Entity | SQL kolonlarına ve Redis namespace'ine eşlenen komut/detay modeli |
| Projection | `OrderSummary` gibi küçük ve ekrana özel okuma modeli |
| Aktif veri seti | Redis'te bilinçli olarak tutulan sınırlı veri kümesi |
| Aktif yol (`HotRoute`) | Redis'teki aktif veri setini okuyan repository metodu |
| Kaynak yolu (`SourceRoute`) | PostgreSQL'i açıkça ve sınırlı biçimde okuyan repository metodu |
| Ön yükleme (`warm/backfill`) | PostgreSQL'den Redis'e kontrollü veri hazırlama işi |
| Route coverage | Gerekli kapsamın ve pencerenin Redis'te hazır olduğunu gösteren kanıt |
| Write-behind | Redis'in kabul ettiği komutun PostgreSQL'e asenkron yazılması |
| Write receipt | Kimlik, sürüm ve kalıcılık durumunu izlemek için kullanılan komut sonucu |
| Snapshot / yayın nesli | Redis'te tutarlı bir sürüm olarak yayımlanan hazır okuma modeli; okuma isteği bunu kendiliğinden hazırlamaz |
| Üretilmiş JSON codec | Belirlenen DTO için derleme sırasında üretilen JSON dönüştürücü; Spring MVC'nin JSON işlemesinden ayrıdır |
| Atomik batch | Birden fazla komutun Redis'te birlikte kabul edilmesi veya hiçbirinin uygulanmaması; SQL'e yazma daha sonra gerçekleşir |
| SQL kalıcılığı | Kabul edilen yazmanın SQL'e ulaştığının doğrulanması; HTTP `202` bunu tek başına göstermez |
| Çalışma rolü | `CLIENT_ONLY` okuma ve iş kabulünü, `WORKER_ONLY` arka plan işlemesini üstlenir |
| Silme sürüm koruması | Eski UPSERT'in silinen kaydı geri getirmesini engelleyen, SQL'de kalıcı tutulan sürüm bilgisi |
| Snapshot cursor'ı | Hazırlanan nesle/bölüme bağlı kapalı devam anahtarı; rastgele SQL offset'i değildir |

## Gereksinimler

- JDK 21
- Maven 3.9+
- Docker Desktop veya uyumlu bir Docker Engine
- Hazır yük testi için PowerShell 7+

Yerel araçları kontrol et:

```powershell
java -version
mvn -version
docker version
docker compose version
```

## Bağımlılık Modeli

Örnek proje CacheDB'yi Maven artifact'leri üzerinden kullanır. Sample build'i
framework kaynak kodunu kendi içinde derlemez.

```xml
<properties>
    <java.version>21</java.version>
<cachedb.version>0.19.0</cachedb.version>
<jedis.version>5.2.0</jedis.version>
</properties>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>com.reactor.cachedb</groupId>
            <artifactId>cachedb-bom</artifactId>
            <version>${cachedb.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>com.reactor.cachedb</groupId>
        <artifactId>cachedb-spring-boot-starter-postgres</artifactId>
    </dependency>
    <dependency>
        <groupId>com.reactor.cachedb</groupId>
        <artifactId>cachedb-annotations</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-jdbc</artifactId>
    </dependency>
    <dependency>
        <groupId>org.postgresql</groupId>
        <artifactId>postgresql</artifactId>
        <scope>runtime</scope>
    </dependency>
</dependencies>

<build>
    <plugins>
        <plugin>
            <artifactId>maven-compiler-plugin</artifactId>
            <configuration>
                <release>${java.version}</release>
                <annotationProcessorPaths>
                    <path>
                        <groupId>com.reactor.cachedb</groupId>
                        <artifactId>cachedb-processor</artifactId>
                        <version>${cachedb.version}</version>
                    </path>
                </annotationProcessorPaths>
            </configuration>
        </plugin>
    </plugins>
</build>
```

Yönetim ekranı gerekiyorsa `cachedb-spring-boot-starter-admin` ekle. JPA veya
başka bir starter uygulama için zaten `DataSource` oluşturuyorsa
`spring-boot-starter-jdbc` bağımlılığını tekrar eklemen gerekmez. CacheDB'nin
ihtiyacı; çalışan bir `DataSource`, tek bir veritabanı starter'ı, annotations
artifact'i ve annotation processor'dır.

Maven, normal bağımlılıkları ve build plugin'lerini farklı repository
listelerinden çözümler. Bu nedenle genel kullanıma açık CacheDB deposu iki
bölümde de tanımlanır:

```xml
<repositories>
    <repository>
        <id>cachedb-public</id>
        <url>https://esasmer-dou.github.io/cache-database/maven2</url>
    </repository>
</repositories>

<pluginRepositories>
    <pluginRepository>
        <id>cachedb-public</id>
        <url>https://esasmer-dou.github.io/cache-database/maven2</url>
        <releases><enabled>true</enabled></releases>
        <snapshots><enabled>false</enabled></snapshots>
    </pluginRepository>
</pluginRepositories>
```

`repositories`; BOM, starter ve kütüphaneleri çözümler.
`pluginRepositories` ise `cachedb-maven-plugin` eklentisini çözümler. Herkese açık
adres kimlik bilgisi istemez; bu ayar 0.19.0 paketlerinin mevcut olduğunu kanıtlamaz.
Yapılandırdığınız paket kaynağından onaylı ve eşleşen paket setinin tamamını edinin.

## Hızlı Başlangıç

### 1. Eşleşen CacheDB paket setini çözümle

Onaylı 0.19.0 paket setinin tamamı erişilebilir olduğunda örnek projeyi doğrulayın.
Maven; eşleşen BOM, starter, annotation processor ve doctor plugin'ini
yapılandırılmış depodan çözümlemelidir; burada yayımlanmış oldukları iddia edilmez:

```powershell
mvn -U -DskipTests validate
```

Build çıktısında `CacheDB doctor` ve
`OK: CacheDB build contract is consistent` satırları görünmelidir. Build,
yerel olarak kurulmuş bir CacheDB checkout'una bağımlı olmamalıdır.

### 2. Redis ve PostgreSQL'i başlat

```powershell
docker compose up -d
docker compose ps
```

Compose dosyası şu servisleri açar:

| Servis | Adres | Yerel kullanım amacı |
| --- | --- | --- |
| Redis 8.2.1 | `127.0.0.1:56379` | Aktif entity, projection, indeks, stream, lease ve telemetry verileri |
| PostgreSQL 16 | `127.0.0.1:55432` | Kalıcı doğruluk kaynağı |

### 3. API'yi demo profiliyle başlat

Yerel şema kurulumu, seed ve ön yükleme endpoint'leri, periyodik warm ve yönetim
ekranı için `demo` profili zorunludur.

Bu örnekte veritabanı sağlayıcısına özel `schema.sql` dosyasını Spring çalıştırır.
CacheDB bu betikten sonra başlar ve sonucu `VALIDATE_ONLY` modunda doğrular; DDL
yönetimi için Spring ile yarışmaz.

```powershell
$env:SPRING_PROFILES_ACTIVE = "demo"
mvn spring-boot:run
```

Bash karşılığı:

```bash
SPRING_PROFILES_ACTIVE=demo mvn spring-boot:run
```

### 4. Hazırlık durumunu kontrol et

```powershell
Invoke-RestMethod http://127.0.0.1:8091/actuator/health/readiness
```

Durum `UP` olmadan ilerleme. Readiness; Redis, PostgreSQL ve write-behind
durumunu birlikte değerlendirir. Liveness ise yalnızca uygulama sürecinin
çalıştığını gösterir.

### 5. Kalıcı demo verisini oluştur

Seed işlemi sınırlı ve dağıtık bir iş olarak çalışır; `202 Accepted` döner. Tek
HTTP isteğini açık tutmak yerine işi durum endpoint'inden izle:

```powershell
$seed = Invoke-RestMethod -Method Post `
  -Uri "http://127.0.0.1:8091/api/demo/seed?customers=20&ordersPerCustomer=40&linesPerOrder=4"

do {
    Start-Sleep -Milliseconds 250
    $seedState = Invoke-RestMethod "http://127.0.0.1:8091/api/warm/jobs/$($seed.jobId)"
} while ($seedState.status -in @("QUEUED", "RUNNING"))

if ($seedState.status -ne "COMPLETED") {
    throw ($seedState | ConvertTo-Json -Depth 8)
}
```

`COMPLETED`, seed işinin tamamlandığını gösterir. Production geçişinde SQL
kalıcılığının sağlıklı olduğunu doğrulamak için ayrıca readiness durumunu ve
write-behind kuyruğunu izlemelisin.

Seed işlemi kalıcı demo kayıtlarını oluşturur; ancak bütün Redis route'larını
hazır kabul etmez. İlgili warm işi tamamlanıp coverage kaydı oluşmadan hızlı
erişim listesi bilinçli olarak `503 Service Unavailable` döner. Böylece boş ya
da eksik bir Redis penceresi, tam iş sonucu sanılmaz.

### 6. Müşteri sipariş projection'ını hazırla

```powershell
$warm = Invoke-RestMethod -Method Post `
  -Uri "http://127.0.0.1:8091/api/warm/orders/customer/1?limit=100&projectionOnly=true"

do {
    Start-Sleep -Milliseconds 250
    $warmState = Invoke-RestMethod "http://127.0.0.1:8091/api/warm/jobs/$($warm.jobId)"
} while ($warmState.status -in @("QUEUED", "RUNNING"))

if ($warmState.status -ne "COMPLETED") {
    throw ($warmState | ConvertTo-Json -Depth 8)
}
```

### 7. Aktif yol ile arşiv yolunu karşılaştır

```powershell
# Redis projection yolu
$page = Invoke-RestMethod "http://127.0.0.1:8091/api/customers/1/orders?limit=10"
$page.items

# nextCursor varsa offset kullanmadan devam et
if ($page.nextCursor) {
    Invoke-RestMethod "http://127.0.0.1:8091/api/customers/1/orders?limit=10&after=$($page.nextCursor)"
}

# Sınırlı PostgreSQL yolu
Invoke-RestMethod "http://127.0.0.1:8091/api/orders/archive?customerId=1&limit=10"
```

### 8. Operasyon araçlarını aç

- Yönetim ekranı: `http://127.0.0.1:8091/cachedb-admin`
- Güncel ayarlar: `http://127.0.0.1:8091/api/tuning`
- Periyodik warm durumu: `http://127.0.0.1:8091/api/warm/schedules`

PostgreSQL verisini silmeden yerel servisleri durdur:

```powershell
docker compose down
```

`docker compose down -v` komutunu yalnızca yerel PostgreSQL volume'unu bilinçli
olarak silmek istediğinde kullan.

## Çalışma Zamanı Sözleşmesi

| İşlem | Ana veri yolu | Veri Redis'in dışındaysa | Kalıcılık ve güvenlik kuralı |
| --- | --- | --- | --- |
| `save`, update, soft delete | Önce Redis, sonra PostgreSQL write-behind | Veri kabul politikası izin veriyorsa komut Redis'e girer | `202 Accepted`, SQL commit anlamına gelmez; işlem makbuzunu ve readiness ölçümlerini izle |
| Entity detayı | Redis entity sorgusu | Açık bir `unavailable/not-found` sonucu döner; kendiliğinden sınırsız SQL çalıştırmaz | Entity yolunu hazırla veya sınırlı source-detail yolu oluştur |
| Büyüyen liste veya panel | Redis projection | Tam route kapsamı hazır değilse `completeItems()` çağrısı `503 Service Unavailable` döner | Aynı kapsamı hazırlayan warm işini çalıştır, `COMPLETED` durumunu bekle ve geçişten önce coverage doğrula |
| Arşiv, dışa aktarma, denetim geçmişi | Sınırlı PostgreSQL source route | PostgreSQL'i doğrudan okur | Satır sınırı, deterministik sıralama, indeks ve timeout kullan |
| PostgreSQL'deki mevcut kayıt | Warm/backfill PostgreSQL'den okur ve Redis'i doldurur | Uygulama açılırken otomatik içe aktarma yapılmaz | Önce dry-run, ardından sınırlı warm ve coverage kontrolü yap |
| CacheDB dışından PostgreSQL yazısı | Önce PostgreSQL değişir | Bir değişiklik akışı yoksa Redis eski kalabilir | Outbox/CDC kullan; periyodik warm olay aktarımının yerine geçmez |

Aktif veri seti, veritabanının ikinci ve tam kopyası değildir. Redis belleğini;
entity veri yükleri, projection'lar, indeksler, stream durumu, lease kayıtları ve
operasyon metadata'sıyla birlikte hesaplamalısın.

## Kod Üzerinden Akış

Bu bölümdeki kısa parçalar öğretici kesitlerdir; tam kaynak dosyaları bağlantılarda bulunur.
[Katalog senaryolarında](#bildirimsel-katalog-senaryoları) ise import ve constructor'ları dâhil tam, derlenen Java dosyaları yer alır.

### 1. Entity: kalıcı veri şekli

[`OrderEntity`](src/main/java/com/example/cachedb/sample/domain/OrderEntity.java),
SQL kolonlarını, Redis namespace'ini, bölümlenmiş indeksi ve sınırlı ilişkiyi
tanımlar:

```java
@CacheEntity(table = "sample_orders", redisNamespace = "sample-orders")
@CachePartitionedIndex(partitionBy = "customer_id", sortBy = "order_date")
public class OrderEntity {
    @CacheId(column = "order_id")
    public Long orderId;

    @CacheColumn("customer_id")
    public Long customerId;

    @CacheColumn("order_date")
    public Long orderDate;

    @CacheRelation(
            target = OrderLineEntity.class,
            mappedBy = "orderId",
            kind = CacheRelation.RelationKind.ONE_TO_MANY,
            batchLoadOnly = true,
            maxRowsPerParent = 50,
            parentBatchSize = 16,
            orderBy = "lineNumber ASC"
    )
    public List<OrderLineEntity> lines;
}
```

Veritabanındaki foreign key kalıcı ilişki bütünlüğünü korur.
`@CacheRelation` ise CacheDB'ye ilişkinin nasıl ve hangi sınırla yükleneceğini
söyler. Biri olmadan diğeri teknik olarak bulunabilir; canlı ortam modelinde
çoğunlukla ikisine de ihtiyaç vardır.

### 2. Projection: ekranın ihtiyacı olan şekil

[`OrderSummary`](src/main/java/com/example/cachedb/sample/readmodel/OrderSummary.java),
`OrderEntity`'den küçüktür ve sipariş satırı veri yüklerini içermez:

```java
@CacheProjectionRecord(
        source = OrderEntity.class,
        id = "orderId",
        name = "order-summary",
        rankedBy = {"order_date", "priority_score"},
        refresh = CacheProjectionRecord.Refresh.ASYNC
)
public record OrderSummary(
        Long orderId,
        Long customerId,
        Long orderDate,
        BigDecimal orderAmount,
        String currencyCode,
        String orderType,
        String status,
        Integer lineCount,
        Double priorityScore
) {
}
```

Komut ve seçilmiş detay için entity; liste, zaman çizelgesi, panel, top-N ve
global sıralı yol için projection kullan.

### 3. Repository: yol sözleşmesi

[`OrderRepository`](src/main/java/com/example/cachedb/sample/repository/OrderRepository.java)
yolu tanımlar; implementasyonu processor üretir:

```java
@CacheRepository(entity = OrderEntity.class)
@CacheRepositoryDefaults(
        hotPopulation = HotRoute.Population.DECLARED_WARM,
        sourceMaxRows = 500,
        sourceTimeoutSeconds = 15
)
public interface OrderRepository extends CacheDbRepository<OrderEntity, Long> {

    @HotRoute(
            value = "customer-order-timeline",
            projection = OrderSummary.class,
            pageSize = 100,
            hotWindow = 1_000,
            memoryBudgetBytes = CacheMemoryBudget.MIB_16,
            coverageScopeParameter = "customerId"
    )
    @CacheRouteQuery(
            predicates = {
                    @CachePredicate(field = "customerId"),
                    @CachePredicate(field = "status", operator = CachePredicate.Operator.NE, constants = "DELETED")
            },
            orderBy = {
                    @CacheOrder(field = "orderDate", direction = CacheOrder.Direction.DESC),
                    @CacheOrder(field = "orderId", direction = CacheOrder.Direction.DESC)
            }
    )
    CursorPage<OrderSummary> customerTimeline(long customerId, WindowRequest window);

    @WarmRoute(
            value = "warm-customer-order-timeline",
            from = "customerTimeline",
            maxRows = 1_000
    )
    CacheWarmPlan warmCustomerTimeline(
            long customerId,
            int maxRows,
            CacheWarmTarget target
    );
}
```

Yol sözleşmesi; sayfa boyutunu, aktif pencereyi, bellek bütçesini, sıralamayı,
coverage kapsamını ve warm sınırını tek yerde görünür kılar. Processor; alanla
aynı adı taşıyan predicate parametresini, tek `WindowRequest` parametresini,
warm satır sınırını, warm hedefini ve warm coverage kapsamını çıkarır. Birden
fazla aday varsa sessiz seçim yapmaz; derlemeyi açık bir hatayla durdurur.

### 4. Uygulama servisi: iş akışının yönetimi

[`CustomerApplicationService`](src/main/java/com/example/cachedb/sample/application/customer/CustomerApplicationService.java),
Redis client'ı veya generated binding sınıfı yerine interface kullanır:

```java
@Service
public final class CustomerApplicationService {
    private final CustomerRepository customers;
    private final OrderRepository orders;

    public CustomerEntity detail(long customerId, int orderPreview) {
        return SampleHotLookups.require(
                "Customer",
                customerId,
                customers.detail(customerId, orderPreview)
        );
    }

    public CursorPage<OrderSummary> orderTimeline(long customerId, int limit, String after) {
        return orders.customerTimeline(customerId, WindowRequest.of(limit, after));
    }
}
```

Controller HTTP girdisini doğrular. Uygulama servisi kullanım senaryosunu
yönetir. Repository interface'i veri yolu sözleşmesini taşır. Generated kod;
serileştirme, indeks ve veritabanı sağlayıcısı bağlantısını üstlenir.

REST endpoint'i yanıttaki `nextCursor` değerini bir sonraki isteğin isteğe bağlı
`after` parametresi olarak kabul eder. Cursor bu route'a, müşteri kapsamına ve
sıralama sözleşmesine bağlıdır; başka müşteri veya route için kullanılamaz.
Strict bir HOT route doğrudan `CursorPage<T>` dönebilir; generated kod sayfayı
oluşturmadan önce coverage bilgisinin eksiksiz ve güncel olduğunu doğrular.
Uygulama coverage kanıtını ayrıca inceleyecekse dönüş tipi `HotWindow<T>` kalmalıdır.

Processor ayrıca reflection kullanmayan bir route companion sınıfı üretir.
Integration test ve operasyon kodunda route adını string olarak tekrarlamak
yerine bu referansı kullan:

```java
cacheDbTestProbe.requireDeclaredWarmRoute(
        OrderRepositoryCacheDbRoutes.customerTimeline()
);

RouteCoverage coverage = cacheDbTestProbe.coverage(
        OrderRepositoryCacheDbRoutes.customerTimeline(),
        String.valueOf(customerId),
        Duration.ofMinutes(5)
);
```

Repository metodu veya route sözleşmesi değişirse bu kullanım derleme sırasında
değişmek zorunda kalır. Generated companion `target/generated-sources` altında
oluşur; bu sınıfı `src/main/java` dizinine kopyalama.

## Mevcut Veriyi Hazırlama

PostgreSQL'deki mevcut satırlar uygulama açılırken otomatik olarak Redis'e
aktarılmaz. Mevcut sistemden geçerken şu sırayı izle:

1. Sınırlı bir `@HotRoute` veya `@CacheLookup` tanımla.
2. Aynı yol için `@WarmRoute` ekle.
3. `dryRun=true` çalıştır ve aday satır sayısını incele.
4. Gerçek warm işini gönder ve `COMPLETED` durumuna kadar izle.
5. Route coverage ile PostgreSQL üyelik/sıralama karşılaştırmasını doğrula.
6. Trafiği kademeli aç ve PostgreSQL geri dönüş yolunu koru.

Dry-run örneği:

```powershell
Invoke-RestMethod -Method Post `
  -Uri "http://127.0.0.1:8091/api/warm/orders/customer/1?limit=100&projectionOnly=true&dryRun=true"
```

Liste ve panel için yalnızca projection hazırlamak en doğru seçimdir. Tam entity
hazırlama, yalnızca seçilmiş detay veya komut yolu bütün aktif veri yüküne ihtiyaç
duyuyorsa kullanılmalıdır.

Eski tablolarda `entity_version` değeri `NULL` veya `0` ise ilk hazırlama
sırasında başlangıç Redis sürümü kullanılır. Bu davranış yalnızca geçişi
kolaylaştırır; değişiklik akışının yerini tutmaz. Geçişten sonra PostgreSQL'e
dışarıdan yazan her uygulama sürümü düzenli artırmalı ve değişikliği outbox/CDC
ile yayımlamalıdır. Aksi durumda bir sonraki sınırlı uzlaştırma çevrimine kadar
oluşacak gecikme bilinçli olarak kabul edilmelidir.

### Periyodik warm ve uzlaştırma

[`SampleScheduledWarmPlans`](src/main/java/com/example/cachedb/sample/config/SampleScheduledWarmPlans.java),
90 günlük sipariş penceresini deklaratif olarak tanımlar. Redis lease sayesinde
bir döngüyü yalnızca bir pod çalıştırır; diğer pod'lar güvenli biçimde bekler
veya döngüyü atlar. Uzlaştırma, policy kapsamından çıkan kayıtları temizler.

```java
@CacheScheduledWarm(
        name = "sample-active-order-window",
        fixedDelayString = "${sample.scheduled-warm.orders.fixed-delay:PT15M}",
        lockAtMostForString = "${sample.scheduled-warm.orders.lock-at-most-for:PT2M}",
        lockWaitTimeoutString = "${sample.scheduled-warm.orders.lock-wait-timeout:PT20S}",
        minimumIntervalString = "${sample.scheduled-warm.orders.minimum-interval:PT15M}",
        reconcileHotSet = true
)
public CacheWarmPlan activeOrderWindow() {
    long cutoff = Instant.now().minus(Duration.ofDays(90)).getEpochSecond();
    return orders.warmActiveWindow(cutoff, orderWarmMaxRows);
}
```

Periyodik warm seçilen aktif pencereyi korur. CacheDB üzerinden gelen yeni
yazılar normal komut yoluyla hemen işlenir; bir sonraki zamanlanmış döngüyü
beklemez.

Annotation processor metot imzasını derleme sırasında doğrular ve tipli bir
Spring task adapter'ı üretir. Runtime, annotation eklenen metotları taramaz ve
reflection ile çağırmaz.

### Ön yükleme çalıştırma, sorgu niyeti ve kalıcılık

Tipli hedef, entity mi yoksa yalnızca projection mı hazırlanacağını bir kez
seçer. Generated plan bu kararı taşır; çalıştırma aşamasında yalnızca deneme
veya uygulama modu seçilir:

```java
CacheWarmTarget target = projectionOnly
        ? CacheWarmTarget.PROJECTIONS_ONLY
        : CacheWarmTarget.ENTITY_AND_PROJECTIONS;
CacheWarmPlan plan = orders.warmCustomerTimeline(customerId, limit, target);
CacheWarmExecution execution = cacheDatabase.executeWarm(
        plan,
        dryRun ? CacheWarmExecutionMode.DRY_RUN : CacheWarmExecutionMode.APPLY
);
CacheWarmSummary summary = execution.summary("customer-orders");
```

`DRY_RUN` Redis'i değiştirmez. Aynı yol için ayrı entity/projection metotları
oluşturma ve planın kararını ikinci bir `warmProjections`/`warm` koşuluyla
uygulama kodunda tekrar etme.

REST endpoint, ön yüklemeyi HTTP isteğini karşılayan iş parçacığında çalıştırmaz.
Tipli komutu Redis üzerindeki dayanıklı iş kuyruğuna gönderir; `202 Accepted` ve
işi izleyeceğin `Location` başlığını döndürür:

```java
public record SampleWarmCommand(Route route, int limit, boolean projectionOnly,
                                boolean dryRun) {
}

static final CacheDistributedJobDefinition<SampleWarmCommand> WARM_JOB =
        CacheDistributedJobDefinition.of("sample.route.warm", SampleWarmCommand.class);

CacheDistributedJobSnapshot job = jobs.submit(WARM_JOB, command);
// Location: /api/warm/jobs/{jobId}
```

Handler, `CacheDistributedJobHandler.Typed<SampleWarmCommand>` interface'ini
uygular ve aynı tanımı döndürür. Sınırlı ilerleme bilgisi
`CacheDistributedJobProgress` ile yazılır; route metni ve payload tipi tekrar edilmez.

Her pod aynı iş tanımını kaydeder. Redis iş durumunu ve checkpoint bilgisini
tutar; yarım kalan işi başka bir pod devralabilir. Satır sınırı, tekrarlanabilir
ön yükleme davranışı ve generated SQL sözleşmesi korunur. Arka plan işi, sorguyu
sınırsız hâle getirmez.

Sorgu koşullarındaki gruplar da açık bir sözleşmedir. Aynı gruptaki koşullar
AND, farklı gruplar OR ile birleştirilir. Aktif sipariş penceresi bilinçli
olarak "son 90 gün OR aktif durum" anlamına geldiği için
`@CacheRouteQuery`, `0` ve `1` gruplarıyla birlikte
`explicitDisjunction = true` kullanır. Birden fazla grup kullanıp bu onayı
vermeyen sorgu derleme sırasında reddedilir. İş kuralı AND ise bütün koşulları
aynı grupta tut.

Seed ve içe aktarma batch'leri SQL kalıcılık kanıtını korur:

```java
try (var orders = cacheDatabase.durableBatchWriter(
        "sample seed/orders", 128, 1_024, Duration.ofSeconds(30),
        orderRepository::saveAll
)) {
    sourceOrders.forEach(orders::add);
}
```

Timeout, komutun Redis tarafından kabul edildiğini fakat verilen süre içinde
SQL kalıcılığının doğrulanamadığını anlatır. Exception, receipt listesini ve
operasyon adını taşır. Bu nedenle aynı yazıları körlemesine tekrar gönderme;
önce receipt durumunu incele.

### Üretilen route envanteri

Annotation processor her repository için route (erişim yolu) kataloğu üretir.
Starter bu katalogları runtime reflection kullanmadan bir araya getirir:

```powershell
Invoke-RestMethod http://127.0.0.1:8091/actuator/cachedb
```

Yanıt; tanımlı repository ve route sayılarını, route türlerini, hızlı erişim
route'larının doldurma stratejilerini, en fazla 250 route ayrıntısını, en fazla
100 zamanlanmış ön yükleme ayrıntısını ve kesilme işaretlerini içerir. Micrometer
tarafında `cachedb.repositories.declared`, `cachedb.routes.declared`,
`cachedb.routes.hot.population{strategy=...}` ve `cachedb.scheduled.warm.*`
ölçümleri bulunur. Strateji etiketi dört sabit değerle sınırlıdır; route,
customer ve tenant adları metric etiketi yapılmaz. Katalog yalnızca derlenen
yüzeyi kanıtlar; coverage, veri eşitliği, gecikme, bellek ve SQL kalıcılığı ayrı
production kapılarıdır.

## Kullanım Senaryosuna Göre Ayar

Ayar kararına tablo boyutundan değil, ölçülmüş yol ihtiyacından başla.

| Senaryo | Aktif veri politikası | Okuma modeli | Başlangıç sınırı | Aktif setin dışında |
| --- | --- | --- | --- | --- |
| Müşteri sipariş zaman çizelgesi | Son 90 gün **veya** aktif sipariş durumları | Müşteri başına `OrderSummary` | Sayfa `100`, pencere `1.000`, yol bütçesi `16 MiB` | Sınırlı `archive` source route |
| Ürün kataloğu | Aktif ürünler **veya** stokta/düşük stokta olanlar | `ProductAvailability` | Entity sınırı `25.000`, sayfa `100` | Pasif ürün source route'u |
| Destek operasyonu | Son 30 günde güncellenen **veya** `OPEN/PENDING/ESCALATED` kayıtlar | Küçük satırlar için entity | Entity sınırı `50.000`, sayfa `50` | Gerektiğinde açık ticket-history SQL yolu |
| Lojistik kontrol paneli | Son 14 günde güncellenen **veya** aktif/istisna durumları | `ShipmentSummary` | Entity sınırı `150.000`, yol penceresi `2.000-10.000` | Teslim edilmiş gönderi source route'u |
| Rapor çalıştırma | `QUEUED/RUNNING/FAILED` veya son 24 saat | Küçük rapor işi entity'si | Entity sınırı `5.000`, sayfa `50` | Tamamlanan rapor geçmişi PostgreSQL'de |
| Güvenlik denetimi | Son 24 saatteki önemli olaylar | Küçük ve sınırlı entity listesi | Entity sınırı `2.000`, read admission kapalı | Tam denetim arşivi PostgreSQL'de |

Örnek, çözümlenmiş çalışma zamanı ayarlarını API üzerinden gösterir:

```powershell
Invoke-RestMethod http://127.0.0.1:8091/api/tuning
Invoke-RestMethod http://127.0.0.1:8091/api/tuning/profiles
```

[`SampleCacheDbTuningConfig`](src/main/java/com/example/cachedb/sample/config/SampleCacheDbTuningConfig.java)
içindeki temel kontroller:

| Kontrol | Örnek değer | Neden var? |
| --- | ---: | --- |
| `maxEntityQueryLimit` | `250` | Geniş entity materialization işlemini durdurur |
| `maxProjectionQueryLimit` | `1.000` | Küçük projection satırları için daha geniş pencereye izin verir |
| `maxQueryLoadRows` | `1.000` | Kayıtlı source yüklemeyi sınırlar |
| `queryTimeoutSeconds` | `15` | Source okumaya zaman sınırı koyar |
| `workerThreads` | `2` | Eş zamanlı SQL flush baskısını sınırlar |
| `batchSize` / `maxFlushBatchSize` | `128` | Sınırsız batch oluşturmadan SQL round-trip sayısını azaltır |
| Redis uyarı / kritik eşikleri | `%75` / `%88` | Redis maxmemory değerine ulaşmadan backpressure uygular |
| Beklenen eviction policy | `noeviction` | Redis'in koordinasyon veya yazma durumunu sessizce atmasını engeller |

Bu değerleri doğrudan canlı ortama kopyalama. Serileştirilmiş veri yükü boyutunu,
projection/indeks maliyetini, en yüksek eş zamanlı yol trafiğini, SQL flush
gecikmesini ve yeniden hazırlama/failover sırasında gereken Redis boşluğunu ölç.

## API Kataloğu

| Alan | Örnek endpoint'ler | Veri yolu |
| --- | --- | --- |
| Sağlık ve operasyon | `GET /actuator/health/readiness`, `GET /api/tuning`, `GET /api/warm/schedules` | Çalışma zamanı telemetry'si |
| Demo hazırlığı | `POST /api/demo/seed`, `GET /api/warm/jobs/{jobId}` | Dağıtık arka plan işleri |
| Müşteri | `POST /api/customers`, `GET /api/customers/{id}`, `GET /api/customers/{id}/orders` | Komut, entity detayı, projection listesi |
| Sipariş komutları | `POST /api/orders`, `PATCH /api/orders/{id}/status`, `DELETE /api/orders/{id}` | Redis öncelikli write-behind komutları |
| Sipariş okumaları | `GET /api/orders/{id}`, `GET /api/orders/high-value`, `GET /api/orders/archive` | Entity, ranked projection, PostgreSQL source route |
| Ürün | `GET /api/products/active`, `GET /api/products/low-stock`, `PATCH /api/products/{id}/stock` | Projection okumaları ve komut |
| Gönderi | `GET /api/shipments/active`, `GET /api/shipments/exceptions`, `GET /api/shipments/archive` | Projection ve PostgreSQL source route'ları |
| Destek | `GET /api/tickets/open`, `POST /api/tickets`, `PATCH /api/tickets/{id}/status` | Sınırlı entity okuması ve komutlar |
| Raporlama | `GET /api/reports/jobs/live`, `GET /api/reports/audit/security`, `GET /api/reports/audit/archive` | Aktif kayıtlar ve kalıcı arşiv |
| Paneller | `GET /api/dashboard/commerce`, `GET /api/dashboard/operations` | Ekrana göre şekillendirilmiş Redis verisi |
| Katalog snapshot'ları | `GET /api/catalog/items/{id}`, `GET /api/catalog/items?ids=...`, `GET /api/catalog/categories/{category}` | Tipli Redis okumaları ve cursor ile sayfalama |
| Katalog hazırlığı ve aktarım | `POST /api/catalog/refresh`, `POST /api/catalog/imports` | Sınırlı demo işleri; iş sonucunu ve SQL kalıcılığını ayrı kontrol edin |
| Redis'e hazırlama | `POST /api/warm/customers/active`, `/orders/customer/{id}`, `/orders/{id}/lines`, `/orders/high-value`, `/orders/highlighted`, `/products/active`, `/products/low-stock`, `/tickets/open`, `/shipments/active`, `/shipments/customer/{id}`, `/shipments/exceptions`, `/shipments/{id}/events`, `/reports/live`, `/reports/type/{type}`, `/audit/security` | Sınırlı PostgreSQL-Redis işleri; her gönderimden sonra `/api/warm/jobs/{jobId}` durumunu izle |

İstek sınırları doğrulanır. Sınırı aşan değerler sessizce küçültülmek yerine
`400 Bad Request` döner. Kuyruk doluluğu `429 Too Many Requests`; optimistic
conflict ve henüz kalıcılaşmamış ana kayıt ise `409 Conflict` üretir.

## Postman

Şu koleksiyonu içe aktar:

```text
postman/cache-database-postgresql-sample.postman_collection.json
```

Klasörleri şu sırayla çalıştır:

1. Readiness kontrolünü çalıştır, demo seed işini gönder ve `Latest Background
   Job Status` isteği `COMPLETED` gösterene kadar tekrarla.
2. Her iş alanı klasöründe hızlı erişim listesinden önce ilgili `Warm ...`
   isteğini çalıştır.
3. Her `202 Accepted` yanıtından sonra son iş `COMPLETED` olana kadar durum
   isteğini tekrarla.
4. Hızlı erişim route'unu çağır; klasörde karşılığı varsa sınırlı source/arşiv
   route'u ile sonucu karşılaştır.
5. Dashboard klasörünü, kullandığı bütün alt route'lar hazırlandıktan sonra çalıştır.
6. Pencere veya pool değerini değiştirmeden önce tuning ve periyodik warm
   durumunu incele.

Snapshot ve atomik aktarım için ayrı hazırlanan
`postman/cache-database-postgresql-catalog.postman_collection.json` koleksiyonunu
kullanın. Bu koleksiyon kendi dört ürününü oluşturur ve sonuçları doğrular.
Otomatik çalıştırıcı, API/worker kurulumu, tekrar gönderim kuralları ve beklenen
sonuçlar için [çalışan senaryoları](#bildirimsel-katalog-senaryoları) izleyin.

## Yük Testi

Seed ve warm tamamlandıktan sonra:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run-load-test.ps1 `
  -RouteProfile hot-timeline `
  -Concurrency 8 `
  -DurationSeconds 20 `
  -SeedCustomers 20 `
  -OrdersPerCustomer 40 `
  -WarmCustomers 20 `
  -WarmLimit 100 `
  -MaxP95Millis 250
```

Bu komut yerel bir regresyon kapısıdır; production kapasite sonucu değildir.
Production ölçümü gerçek Redis/PostgreSQL gecikmesi, Kubernetes kaynak
sınırları, gerçek payload ve beklenen eş zamanlılıkla yapılmalıdır.

## PostgreSQL Canlı Ortam Notları

- Her source route predicate'i ve deterministik sıralama son eki için uygun
  indeks oluştur.
- Tüm pod'ların toplamını düşünerek HikariCP bağlantı sayısını PostgreSQL
  bütçesinin altında tut.
- Write-behind batch boyutunu WAL baskısı ve lock süresine göre ayarla.
- Warm, arşiv ve geçiş sorgularında statement timeout kullan.
- Aynı tablolara başka uygulamalar da yazıyorsa outbox/CDC ekle.
- Yedekleme, geri yükleme, Redis kaybı/yeniden hazırlama ve uygulamanın geri dönüş yollarını
  kanıtla.
- Operasyonel yol Redis'ten çalışsa bile kalıcı doğruluk kaynağının PostgreSQL
  olduğunu koru.

## Production Sertifikası

Framework deposundaki testler CacheDB'nin kendi Docker ve provider davranışını
kanıtlar. Uygulamanın rotaları ile gerçek staging topolojisi ayrıca
kanıtlanmalıdır. Rota kapsamı, veri eşitliği, bellek, failover, canary ve geri
dönüş kanıtlarını `cachedb-certification/` altında topladıktan sonra çalıştır:

```powershell
mvn verify -Pproduction-certification
```

Eksik rota, başka commit veya ortama ait kanıt, veri eşitliği sorunu, aşılmış
bellek bütçesi ya da eksik failover/geri dönüş provası build'i durdurur.
Paylaşılabilir sonuç `target/cachedb-production-certification.md` altında
oluşur. Ayrıntılı sözleşme için
[production sertifikası rehberini](https://github.com/esasmer-dou/cache-database/blob/main/tr/docs/production-sertifikasi.md)
kullan. Örnek kanıtları başarılı gibi işaretleme.

## Canlı Ortam Kontrol Listesi

- [ ] Her operasyonel endpoint; komut, aktif entity, projection veya source
  route olarak sınıflandırıldı.
- [ ] Her aktif yolun sayfa sınırı, aktif penceresi, bellek bütçesi, sıralaması
  ve coverage kapsamı tanımlandı.
- [ ] İlişki yoğun ve global sıralı ekranlar projection kullanıyor.
- [ ] Warm/backfill sınırlı, kaldığı yerden devam edebilir, izlenebilir ve Redis
  kaybından sonra test edilmiş durumda.
- [ ] Source route'ların uygun indeksleri, timeout ve maksimum satır sınırı var.
- [ ] Çağıran servisler `202 Accepted` kalıcılık anlamını biliyor.
- [ ] Redis için açık `maxmemory`, `noeviction`, alarm ve kapasite boşluğu var.
- [ ] PostgreSQL ve HikariCP bağlantı bütçesi pod başına ve toplam replica sayısı
  için hesaplandı.
- [ ] Çok pod'lu periyodik warm ve yarım kalan işin başka pod tarafından alınması
  test edildi.
- [ ] CacheDB dışındaki veritabanı yazıları outbox/CDC veya açık bir uzlaştırma
  kararıyla kapsandı.
- [ ] Yönetim endpoint'leri kapalı veya iç gateway arkasında korunuyor.
- [ ] Geçiş öncesi veri eşitliği, gecikme, canary, geri dönüş ve recovery kanıtı
  kaydedildi.

## Sorun Giderme

| Belirti | Olası neden | Çözüm |
| --- | --- | --- |
| `/api/demo/seed` veya `/api/warm/**` için `404` | Uygulama `demo` profili olmadan açıldı | `SPRING_PROFILES_ACTIVE=demo` tanımlayıp yeniden başlat |
| CacheDB artifact'leri için `404` alınıyor | İstenen sürüm yayımlanmamış veya depo ayarı eksik | Onaylı ve eşleşen paket setinin tamamını edinin. Mevcut bir sürümü kullanmak için onunla eşleşen örnek kaynak tag'ini seçin; yalnız bağımlılık sürümünü düşürmeyin. |
| Bağımlılıklar çözülüyor ancak `cachedb-maven-plugin` bulunamıyor | `pluginRepositories` tanımı yok | Aynı genel CacheDB adresini `pluginRepositories` altına ekle |
| `production-certification` başarısız oluyor | Rota kanıtı eksik, eski veya başka commit/ortama ait | Üretilen raporu oku, gerçek staging kanıtını yenile ve profili yeniden çalıştır |
| Aktif route `503` döndürüyor, arşiv route'u satır getiriyor | Redis route'u hazırlanmadı, coverage süresi doldu veya kapsam farklı | Dry-run yap, aynı route/scope'u hazırla, `COMPLETED` durumunu bekle ve coverage kaydını incele |
| Detay yolu verinin hazır olmadığını söylüyor | Entity payload'ı aktif setin dışında | O detay kapsamı için entity warm et veya sınırlı source-detail yolu ekle |
| Ana kaydı yazdıktan sonra `409 Conflict` | Ana kayıt henüz kalıcı değil veya optimistic version değişti | `Retry-After` değerine uy, write-behind durumunu kontrol et, idempotent retry yap |
| `429 Too Many Requests` | Sınırlı iş kuyruğu veya backpressure koruması devrede | Üretim hızını düşür, Redis ve write-behind telemetry'sini incele |
| Readiness `DOWN` | Redis, PostgreSQL, dead-letter, recovery veya backlog koşulu başarısız | Readiness ayrıntısını ve logları incele; trafiği henüz yönlendirme |
| Redis bellek uyarısı veriyor | Aktif set, projection/indeks maliyeti veya backlog bütçeyi aştı | Yeni admission'ı yavaşlat, keyspace'i ölç, pencereleri küçült veya kapasite ekle |

## İlgili Dokümanlar

- [Ana CacheDB README](https://github.com/esasmer-dou/cache-database/blob/main/tr/README.md)
- [Deklaratif repository kullanımı](https://github.com/esasmer-dou/cache-database/blob/main/tr/docs/deklaratif-repositoryler.md)
- [Başlangıç rehberi](https://github.com/esasmer-dou/cache-database/blob/main/tr/docs/getting-started.md)
- [Periyodik warm ve uzlaştırma](https://github.com/esasmer-dou/cache-database/blob/main/tr/docs/periodik-warm.md)
- [Production tuning](https://github.com/esasmer-dou/cache-database/blob/main/tr/docs/production-tuning-rehberi.md)
- [Kullanım senaryosu örnekleri](https://github.com/esasmer-dou/cache-database/blob/main/tr/docs/use-case-examples.md)
- [Veritabanı provider SPI](https://github.com/esasmer-dou/cache-database/blob/main/tr/docs/veritabani-provider-spi.md)
- [Production reçeteleri](https://github.com/esasmer-dou/cache-database/blob/main/tr/docs/production-recipes.md)

<!-- BEGIN COMPILED FEATURE EXAMPLES -->
## CacheDB 0.18: Çalışan Senaryolar

Bu bölümdeki sınıflar örnek projede gerçekten bulunur; taslak kod değildir.
Önce eşleşen **CacheDB 0.19.0** paketlerini edinin; ardından mevcut Hızlı
Başlangıç adımlarını `demo` profiliyle tamamlayın.
`postman/cache-database-postgresql-v018.postman_collection.json` koleksiyonunu
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
package com.example.cachedb.sample.domain;

import com.reactor.cachedb.annotations.*;
import java.math.BigDecimal;

/** Independent supplier quotes: no FK, secondary UNIQUE constraint or cross-row trigger. */
@CacheEntity(table = "sample_supplier_offers", redisNamespace = "sample-supplier-offers",
        indexedColumns = {"supplier", "status", "unit_price", "available_units"},
        independentWriteReordering = true)
@CacheCompositeIndex(columns = {"supplier", "status"})
@CacheNearPolicy(maxEntries = 256, maxBytes = 1_048_576, ttlSeconds = 10, softTtlSeconds = 2)
public record SupplierOffer(
        @CacheId(column = "offer_id") Long offerId,
        @CacheColumn("supplier") String supplier,
        @CacheColumn("status") String status,
        @CacheColumn("unit_price") BigDecimal unitPrice,
        @CacheColumn("available_units") Integer availableUnits) {
    public SupplierOffer {
        if (offerId == null || offerId <= 0 || supplier == null || supplier.isBlank() || supplier.length() > 32
                || !("ACTIVE".equals(status) || "PAUSED".equals(status))
                || unitPrice == null || unitPrice.signum() < 0 || unitPrice.scale() > 4
                || (long) unitPrice.precision() - unitPrice.scale() > 15
                || availableUnits == null || availableUnits < 0 || availableUnits > 1_000_000)
            throw new IllegalArgumentException("Invalid bounded supplier offer");
    }
}
```

`src/main/java/com/example/cachedb/sample/repository/SupplierOfferRepository.java`
```java
package com.example.cachedb.sample.repository;

import com.example.cachedb.sample.domain.SupplierOffer;
import com.reactor.cachedb.annotations.*;
import com.reactor.cachedb.core.query.AggregateFunction;
import com.reactor.cachedb.core.repository.CacheDbRepository;
import java.math.BigDecimal;
import java.util.Map;

@CacheRepository(entity = SupplierOffer.class)
public interface SupplierOfferRepository extends CacheDbRepository<SupplierOffer, Long> {
    @CacheFacet(groupBy = "status")
    Map<String, Long> countsBySupplier(String supplier);

    @CacheFacet(groupBy = "status", function = AggregateFunction.SUM, field = "unit_price")
    Map<String, BigDecimal> priceTotalsBySupplier(String supplier);

    @CacheAggregate(function = AggregateFunction.SUM, field = "unit_price")
    BigDecimal sumPriceBySupplierAndStatus(String supplier, String status);

    @CachePatch
    com.reactor.cachedb.core.model.WriteReceipt<SupplierOffer, Long> replaceUnits(
            @CacheId Long offerId, @CacheColumn("available_units") Integer units);
}
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
package com.example.cachedb.sample.domain;

import com.reactor.cachedb.annotations.*;

/** Advisory warehouse availability, not an inventory reservation or payment ledger. */
@CacheEntity(table = "sample_tenant_stock", redisNamespace = "sample-tenant-stock",
        indexedColumns = {"tenant_id", "warehouse", "status", "available_units"})
@CacheCompositeIndex(columns = {"tenant_id", "status"})
@CachePartitionedIndex(partitionBy = "warehouse", sortBy = "available_units")
@CacheNearPolicy(maxEntries = 256, maxBytes = 1_048_576, ttlSeconds = 10)
public record TenantStock(
        @CacheId(column = "stock_id") Long stockId,
        @CacheTenantId @CacheColumn("tenant_id") String tenantId,
        @CacheColumn("warehouse") String warehouse,
        @CacheColumn("status") String status,
        @CacheColumn("available_units") Integer availableUnits) {
    public TenantStock {
        if (stockId == null || stockId <= 0 || tenantId == null || tenantId.isBlank() || tenantId.length() > 32
                || warehouse == null || warehouse.isBlank() || warehouse.length() > 32
                || !("AVAILABLE".equals(status) || "HELD".equals(status))
                || availableUnits == null || availableUnits < 0 || availableUnits > 1_000_000)
            throw new IllegalArgumentException("Invalid bounded tenant stock");
    }
}
```

`src/main/java/com/example/cachedb/sample/readmodel/TenantStockCard.java`
```java
package com.example.cachedb.sample.readmodel;

import com.example.cachedb.sample.domain.TenantStock;
import com.reactor.cachedb.annotations.CacheProjectionRecord;

@CacheProjectionRecord(source = TenantStock.class, id = "stockId", name = "tenant-stock-card")
public record TenantStockCard(Long stockId, String warehouse, String status, Integer availableUnits) {}
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
package com.example.cachedb.sample.controls;

import com.example.cachedb.sample.domain.*;
import com.example.cachedb.sample.readmodel.*;
import com.example.cachedb.sample.repository.SupplierOfferRepository;
import com.reactor.cachedb.core.api.EntityRepository;
import com.reactor.cachedb.core.api.ProjectionRepository;
import com.reactor.cachedb.core.api.CacheWriteBatchReceipt;
import com.reactor.cachedb.core.causal.CacheCausalContext;
import com.reactor.cachedb.core.model.WriteReceipt;
import com.reactor.cachedb.core.query.*;
import com.reactor.cachedb.starter.CacheDatabase;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Service;

/** Small infrastructure adapter for the bulk/partition APIs; generated facets stay in the repository. */
@Service
public final class ControlsStore {
    private final CacheDatabase database;
    private final SupplierOfferRepository generatedOffers;
    private final EntityRepository<SupplierOffer, Long> offers;
    private final EntityRepository<TenantStock, Long> stock;
    private final ProjectionRepository<TenantStockCard, Long> cards;

    public ControlsStore(CacheDatabase database, SupplierOfferRepository generatedOffers) {
        this.database = database;
        this.generatedOffers = generatedOffers;
        offers = SupplierOfferCacheBinding.repository(database);
        stock = TenantStockCacheBinding.repository(database);
        // Bootstrap registers metadata only; request reads still require an authenticated tenant scope.
        try (var bootstrap = com.reactor.cachedb.core.tenant.CacheTenantContext.administrative()) {
            cards = stock.projected(TenantStockCardProjection.PROJECTION);
        }
    }

    public WriteReceipt<SupplierOffer, Long> saveOffer(SupplierOffer offer) { return generatedOffers.save(offer); }
    public SupplierOffer source(long id) {
        return generatedOffers.findSourceById(id).orElseThrow(() -> new NoSuchElementException("Offer is not in SQL"));
    }
    public WriteReceipt<SupplierOffer, Long> replaceUnits(long id, int units) { return generatedOffers.replaceUnits(id, units); }
    public SupplierOffer offer(long id) {
        return offers.findById(id).orElseThrow(() -> new NoSuchElementException("Offer is not in the active set"));
    }
    public List<SupplierOffer> bulk(List<Long> ids) { return offers.findAll(ids); }
    public List<SupplierOffer> search(String supplier, String status) {
        return offers.query(QuerySpec.where(QueryFilter.eq("supplier", supplier))
                .and(QueryFilter.eq("status", status)).orderBy(QuerySort.asc("offer_id")).limitTo(50));
    }
    public Dashboard dashboard(String supplier) {
        var stats = offers.aggregate(AggregateSpec.sum("unit_price", QueryFilter.eq("supplier", supplier)));
        return new Dashboard(generatedOffers.countsBySupplier(supplier), generatedOffers.priceTotalsBySupplier(supplier),
                generatedOffers.sumPriceBySupplierAndStatus(supplier, "ACTIVE"), stats.decimalAverage());
    }
    public record Dashboard(Map<String, Long> counts, Map<String, BigDecimal> priceTotals,
            BigDecimal activePriceTotal, BigDecimal decimalAverage) {}

    /** A real mixed PUT/PATCH/DELETE transaction; only the independent offer table opts into regrouping. */
    public CacheWriteBatchReceipt changes(SupplierOffer put, long patchId, int units,
            SupplierOffer secondPut, long secondPatchId, int secondUnits, long deleteId) {
        return database.inTransaction(tx -> {
            tx.put(put);
            tx.patch(SupplierOffer.class, patchId, Map.of("available_units", units));
            tx.put(secondPut);
            tx.patch(SupplierOffer.class, secondPatchId, Map.of("available_units", secondUnits));
            tx.delete(SupplierOffer.class, deleteId);
        });
    }
    public WriteReceipt<TenantStock, Long> saveStock(TenantStock row) { return stock.saveWithReceipt(row); }
    public WriteReceipt<TenantStock, Long> patchStock(long id, int units) {
        return stock.patchWithReceipt(id, Map.of("available_units", units));
    }
    public CacheWriteBatchReceipt stockBatch(List<TenantStock> rows) {
        return database.inTransaction(tx -> tx.putAll(rows));
    }
    public TenantStock stock(long id) {
        return stock.findById(id).orElseThrow(() -> new NoSuchElementException("Stock is not in the active set"));
    }
    public Map<String, List<TenantStock>> top(List<String> warehouses, int limit) {
        return stock.queryPartitions(PartitionedQuerySpec.top("warehouse", warehouses, limit,
                QuerySort.desc("available_units")));
    }
    public List<TenantStockCard> cards(int limit) {
        return cards.query(QuerySpec.allOf().orderBy(QuerySort.desc("available_units")).limitTo(limit));
    }
    public static String token(WriteReceipt<?, ?> receipt) {
        try (var causal = CacheCausalContext.withReceipt(receipt.redisNamespace(), receipt)) {
            return CacheCausalContext.encodeToken();
        }
    }
}
```

Adaptör yalnızca birkaç toplu okuma/partition işlemini taşır; normal facet
metotları deklaratif kalır. Başlangıçtaki administrative scope yalnızca projection
metaverisini kaydeder; HTTP kullanıcısına yönetici yetkisi vermez.

`src/main/java/com/example/cachedb/sample/controls/ControlsController.java`
```java
package com.example.cachedb.sample.controls;

import com.example.cachedb.sample.domain.*;
import com.example.cachedb.sample.readmodel.TenantStockCard;
import com.reactor.cachedb.core.causal.CacheCausalContext;
import com.reactor.cachedb.core.exception.CacheTenantAccessDeniedException;
import com.reactor.cachedb.core.model.WriteReceipt;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/examples/v018")
@ConditionalOnProperty(prefix = "sample.demo", name = "write-tools-enabled", havingValue = "true")
@Validated
public class ControlsController {
    private final ControlsAccess access;
    private final ControlsStore store;
    private final com.reactor.cachedb.spring.boot.CacheDistributedJobExecutor jobs;
    public ControlsController(ControlsAccess access, ControlsStore store,
            com.reactor.cachedb.spring.boot.CacheDistributedJobExecutor jobs) {
        this.access = access; this.store = store; this.jobs = jobs;
    }

    @PostMapping("/offers/warm")
    public ResponseEntity<com.reactor.cachedb.spring.boot.CacheDistributedJobSnapshot> warm(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestBody OfferWarmCommand command) {
        access.supplier(auth);
        return ResponseEntity.accepted().body(jobs.submit(OfferWarmHandler.DEFINITION, command));
    }
    @GetMapping("/offers/{id}/source")
    public SupplierOffer source(@RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable @Positive long id) {
        access.supplier(auth); return store.source(id);
    }

    public record OfferInput(@NotBlank @Size(max = 32) String supplier,
            @NotBlank @Pattern(regexp = "ACTIVE|PAUSED") String status,
            @NotNull @DecimalMin("0.0000") @Digits(integer = 15, fraction = 4) BigDecimal unitPrice,
            @NotNull @Min(0) @Max(1_000_000) Integer availableUnits) {
        public SupplierOffer entity(long id) { return new SupplierOffer(id, supplier, status, unitPrice, availableUnits); }
    }
    public record StockInput(@NotNull @Positive Long stockId, @NotBlank @Size(max = 32) String warehouse,
            @NotBlank @Pattern(regexp = "AVAILABLE|HELD") String status,
            @NotNull @Min(0) @Max(1_000_000) Integer availableUnits) {
        public TenantStock entity(String tenant) { return new TenantStock(stockId, tenant, warehouse, status, availableUnits); }
    }
    public record Units(@NotNull @Min(0) @Max(1_000_000) Integer availableUnits) {}
    public record Changes(@NotNull @Positive Long putId, @NotNull @Valid OfferInput put,
            @NotNull @Positive Long patchId, @NotNull @Min(0) @Max(1_000_000) Integer units,
            @NotNull @Positive Long secondPutId, @NotNull @Valid OfferInput secondPut,
            @NotNull @Positive Long secondPatchId, @NotNull @Min(0) @Max(1_000_000) Integer secondUnits,
            @NotNull @Positive Long deleteId) {}
    public record Accepted(Object id, long version, String causalToken, boolean redisAccepted, boolean sqlDurabilityConfirmed) {}
    public record BatchAccepted(List<Long> versions, boolean redisAccepted, boolean sqlDurabilityConfirmed) {}

    @PutMapping("/offers/{id}")
    public ResponseEntity<Accepted> put(@RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable @Positive long id, @Valid @RequestBody OfferInput input) {
        access.supplier(auth); return accepted(store.saveOffer(input.entity(id)));
    }
    @PatchMapping("/offers/{id}/units")
    public ResponseEntity<Accepted> patch(@RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable @Positive long id, @Valid @RequestBody Units input) {
        access.supplier(auth); return accepted(store.replaceUnits(id, input.availableUnits()));
    }
    @GetMapping("/offers/{id}")
    public SupplierOffer offer(@RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable @Positive long id,
            @RequestHeader(value = "X-CacheDB-Causal", defaultValue = "") @Size(max = 4096) String token) {
        access.supplier(auth); return causal(token, () -> store.offer(id));
    }
    @GetMapping("/offers")
    public List<SupplierOffer> bulk(@RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam @Size(min = 1, max = 64) List<@NotNull @Positive Long> ids) {
        access.supplier(auth); return store.bulk(ids);
    }
    @GetMapping("/offers/search")
    public List<SupplierOffer> search(@RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam @NotBlank @Size(max = 32) String supplier,
            @RequestParam @Pattern(regexp = "ACTIVE|PAUSED") String status) {
        access.supplier(auth); return store.search(supplier, status);
    }
    @GetMapping("/offers/dashboard")
    public ControlsStore.Dashboard dashboard(@RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam @NotBlank @Size(max = 32) String supplier) {
        access.supplier(auth); return store.dashboard(supplier);
    }
    @PostMapping("/offers/changes")
    public ResponseEntity<BatchAccepted> changes(@RequestHeader(value = "Authorization", required = false) String auth,
            @Valid @RequestBody Changes input) {
        access.supplier(auth);
        if (new HashSet<>(List.of(input.putId(), input.patchId(), input.secondPutId(), input.secondPatchId(), input.deleteId())).size() != 5)
            throw new IllegalArgumentException("Mixed changes require five distinct IDs");
        var receipt = store.changes(input.put().entity(input.putId()), input.patchId(), input.units(),
                input.secondPut().entity(input.secondPutId()), input.secondPatchId(), input.secondUnits(), input.deleteId());
        return ResponseEntity.accepted().body(new BatchAccepted(receipt.versions(), true, false));
    }
    @PostMapping("/stock/batch")
    public ResponseEntity<BatchAccepted> stockBatch(@RequestHeader(value = "Authorization", required = false) String auth,
            @RequestBody @Size(min = 1, max = 32) List<@NotNull @Valid StockInput> input) {
        String tenant = access.tenant(auth);
        if (input.stream().map(StockInput::stockId).distinct().count() != input.size())
            throw new IllegalArgumentException("Stock batch IDs must be distinct");
        return tenant(tenant, () -> {
            var receipt = store.stockBatch(input.stream().map(row -> row.entity(tenant)).toList());
            return ResponseEntity.accepted().body(new BatchAccepted(receipt.versions(), true, false));
        });
    }
    @PatchMapping("/stock/{id}/units")
    public ResponseEntity<Accepted> stockPatch(@RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable @Positive long id, @RequestBody @Valid Units input) {
        return tenant(access.tenant(auth), () -> accepted(store.patchStock(id, input.availableUnits())));
    }
    @GetMapping("/stock/{id}")
    public TenantStock stock(@RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable @Positive long id,
            @RequestHeader(value = "X-CacheDB-Causal", defaultValue = "") @Size(max = 4096) String token) {
        return tenant(access.tenant(auth), () -> causal(token, () -> store.stock(id)));
    }
    @PutMapping("/stock/{id}")
    public ResponseEntity<Accepted> stockPut(@RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable @Positive long id, @RequestBody @Valid StockInput input) {
        if (id != input.stockId()) throw new IllegalArgumentException("Path and body IDs must match");
        String tenant = access.tenant(auth);
        return tenant(tenant, () -> accepted(store.saveStock(input.entity(tenant))));
    }
    @GetMapping("/stock/top")
    public Map<String, List<TenantStock>> top(@RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam @Size(min = 1, max = 8) List<@NotBlank @Size(max = 32) String> warehouses,
            @RequestParam(defaultValue = "2") @Min(1) @Max(10) int limit) {
        return tenant(access.tenant(auth), () -> store.top(warehouses, limit));
    }
    @GetMapping("/stock/cards")
    public List<TenantStockCard> cards(@RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int limit) {
        return tenant(access.tenant(auth), () -> store.cards(limit));
    }
    private static ResponseEntity<Accepted> accepted(WriteReceipt<?, ?> receipt) {
        String token = ControlsStore.token(receipt);
        return ResponseEntity.accepted().header("X-CacheDB-Causal", token)
                .body(new Accepted(receipt.id(), receipt.version(), token, true, false));
    }
    private static <T> T causal(String token, Supplier<T> read) {
        try (var scope = CacheCausalContext.withToken(token)) { return read.get(); }
        catch (IllegalStateException failure) {
            if (failure.getMessage() != null && failure.getMessage().startsWith("Causal "))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Required Redis version is unavailable", failure);
            throw failure;
        }
    }
    private static <T> T tenant(String tenant, Supplier<T> work) {
        try (var scope = CacheTenantContext.withTenant(tenant)) { return work.get(); }
        catch (CacheTenantAccessDeniedException denied) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tenant access denied", denied);
        } catch (IllegalStateException denied) {
            if (denied.getMessage() != null && denied.getMessage().startsWith("Atomic batch") && denied.getMessage().contains("tenant"))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tenant batch denied", denied);
            throw denied;
        }
    }
    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<ProblemDetail> notCached(NoSuchElementException missing) {
        return ResponseEntity.status(404).body(ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, missing.getMessage()));
    }
}
```

`src/main/java/com/example/cachedb/sample/controls/ControlsAccess.java`
```java
package com.example.cachedb.sample.controls;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Demo credential mapping only. Replace with the application's verified identity provider. */
@Component
public final class ControlsAccess {
    private final String supplierToken;
    private final String tenantAToken;
    private final String tenantBToken;

    public ControlsAccess(@Value("${sample.controls.supplier-token:}") String supplierToken,
            @Value("${sample.controls.tenant-a-token:}") String tenantAToken,
            @Value("${sample.controls.tenant-b-token:}") String tenantBToken) {
        this.supplierToken = supplierToken;
        this.tenantAToken = tenantAToken;
        this.tenantBToken = tenantBToken;
        var values = java.util.stream.Stream.of(supplierToken, tenantAToken, tenantBToken)
                .filter(value -> !value.isBlank()).toList();
        if (values.stream().anyMatch(value -> value.length() > 256)
                || values.stream().distinct().count() != values.size())
            throw new IllegalArgumentException("Controls tokens must be distinct and at most 256 characters");
    }

    public String tenant(String authorization) {
        String token = bearer(authorization);
        if (same(token, tenantAToken)) return "shop-a";
        if (same(token, tenantBToken)) return "shop-b";
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tenant credential required");
    }

    public void supplier(String authorization) {
        if (!same(bearer(authorization), supplierToken))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Supplier credential required");
    }

    private String bearer(String authorization) {
        if (supplierToken.isBlank() && tenantAToken.isBlank() && tenantBToken.isBlank())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Controls credentials are not configured");
        if (authorization == null || !authorization.startsWith("Bearer ") || authorization.length() > 263)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Bearer credential required");
        String token = authorization.substring(7);
        if (!same(token, supplierToken) && !same(token, tenantAToken) && !same(token, tenantBToken))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unknown credential");
        return token;
    }

    private static boolean same(String actual, String expected) {
        return !expected.isBlank() && MessageDigest.isEqual(
                actual.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }
}
```

`src/main/java/com/example/cachedb/sample/controls/OfferWarmCommand.java`
```java
package com.example.cachedb.sample.controls;

import com.reactor.cachedb.annotations.CacheJsonRecord;

@CacheJsonRecord
public record OfferWarmCommand(String supplier, int maxRows) {
    public OfferWarmCommand {
        if (supplier == null || supplier.isBlank() || supplier.length() > 32 || maxRows < 1 || maxRows > 100)
            throw new IllegalArgumentException("A supplier and maxRows between 1 and 100 are required");
    }
}
```

`src/main/java/com/example/cachedb/sample/controls/OfferWarmHandler.java`
```java
package com.example.cachedb.sample.controls;

import com.example.cachedb.sample.domain.SupplierOfferCacheBinding;
import com.reactor.cachedb.annotations.CacheJobHandler;
import com.reactor.cachedb.core.query.*;
import com.reactor.cachedb.spring.boot.CacheDistributedJobContext;
import com.reactor.cachedb.spring.boot.CacheDistributedJobDefinition;
import com.reactor.cachedb.starter.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "sample.demo", name = "write-tools-enabled", havingValue = "true")
public final class OfferWarmHandler {
    public static final CacheDistributedJobDefinition<OfferWarmCommand> DEFINITION =
            CacheDistributedJobDefinition.of("sample.offers.warm", OfferWarmCommand.class);
    private final CacheDatabase database;
    public OfferWarmHandler(CacheDatabase database) { this.database = database; }
    @CacheJobHandler("sample.offers.warm")
    public CacheWarmResult execute(CacheDistributedJobContext context, OfferWarmCommand command) {
        return database.warm(CacheWarmPlan.builder(SupplierOfferCacheBinding.METADATA.entityName())
                .name("supplier-offer-window")
                .querySpec(QuerySpec.where(QueryFilter.eq("supplier", command.supplier()))
                        .orderBy(QuerySort.asc("offer_id")).limitTo(command.maxRows()).withQueryTimeoutSeconds(15))
                .maxRows(command.maxRows()).build());
    }
}
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
npx --yes newman@6.2.1 run postman/cache-database-postgresql-v018.postman_collection.json --env-var baseUrl=http://127.0.0.1:8091 --env-var peerBaseUrl=http://127.0.0.1:8091 --bail
```

Yalnızca size ait test verisini kullanın. SQL/Redis/HTTP zaman aşımı sürelerini
sınırlayın; yenileme hatalarını, kuyruk baskısını, belleği, tenant hatalarını ve
kaynak veri eşleşmesini izleyin. Framework'ün 50 ms codec kapısı, bu REST/SQL
istekleri için süre garantisi değildir.

---


## Bildirimsel Katalog Senaryoları

Bu senaryolar **CacheDB 0.19.0** sürümünü kullanır. Mevcut sipariş,
destek, lojistik ve arşiv yolları değişmez. Aşağıdaki sınıflar gerçek örnek
kaynak kodlarıdır; kod blokları üstlerinde belirtilen dosyalarla eşleşir.

### 0.15 ile Gelen Kolaylıklar

Katalog planı, üretilmiş JSON codec'ini tanım oluşturulurken bir kez bulur.
`scopedBy`, SQL sorgusunu daraltan kimlik sütununu belirtir; `plan.bind` ise tipli
Redis okuyucusunu oluşturur. Çalışma anında alan keşfi, kendiliğinden SQL'e geçiş
veya uygulama açılırken otomatik veri aktarımı yapılmaz.
Arşiv sorgusunda yinelenen OR gruplarının yerini `@CacheSeek` alır.
`@CacheJobHandler` eklenen metot için doğrudan çağrı yapan bir işleyici üretilir.
Komut verisi, üretilmiş bir codec veya açıkça tanımlanmış `CacheValueCodec` kullanmalıdır.

### Stok Aralığı ve Birleştirilen Değişiklik Bildirimleri

Okumaya geçmeden önce aşağıdaki yenileme akışıyla kataloğu hazırlayın.
Alt ve üst stok sınırları sonuca dahildir. Sıralama önce kullanılabilir stok,
eşitlik durumunda ise metin biçimindeki ürün kimliğine göre yapılır:

Bu örnekler, 2. bölümdeki tedarikçi aktarımının `tools` kategorisine eklediği
`9200001` ve `9200002` numaralı ürünleri kullanır. Önce SQL kalıcılığını doğrulayın
ve katalog yenilemesini tamamlayın. Spring servisinize, derlenen sınıflardaki gibi
`SnapshotJobs snapshots` ve `TypedSnapshotRepository<Long, CatalogCard> catalog`
bağımlılıklarını ekleyin.

```java
var page = catalog.findTypedPage(new SnapshotPageRequest(
        "tools", 5, 20, WindowRequest.first(25)));
// Sonraki sayfada page.nextCursor() ile AYNI kategori ve sınırları kullanın.
// Sınırlı sayıda Redis indeks/veri okuması yapılır. Kayıt yoksa SQL çalıştırılmaz.
```

Bu sorgunun API karşılığı:
`GET /api/catalog/categories/tools/available?minimum=5&maximum=20&limit=25`.
Geçersiz sınırlar 400 yanıtı üretir. Önceki kategori adresi de çalışmaya devam eder.

Tedarikçi işlemi SQL'de kalıcılaştıktan sonra değişen ürün kimliklerini biriktirin:

```java
snapshots.markDirty(CatalogConfiguration.PLAN, List.of(9200001L, 9200002L, 9200001L));
// İşleyici tarafında, SQL transaction'ı tamamlandıktan sonra:
var refreshed = snapshots.flushDirty(CatalogConfiguration.PLAN);
// Optional.empty(): bekleyen kimlik yoktur. Hatalı yenilemede kimlikler yeniden beklemeye alınır.
```

Aynı ürün için art arda gelen bildirimler sınırlı bir kümede birleştirilir.
Yenileme sürerken yeniden bildirilen ürün bir sonraki yenilemede de işlenir.
Grubu tek işleyici sahiplenir; işleyici kapanırsa sahiplik süresi dolduktan sonra
başka biri devam edebilir. `batch-rows`, bekleyen ve işlenmekte olan benzersiz
kimliklerin toplamını sınırlar. Sınır aşılırsa veri sessizce atılmaz; hata verilir.
Önceden tamamlanmış bir snapshot ve açık bir root scope tanımı gerekir.
Bir çağrıda girilen kimlik sayısı da, tekrarlar birleştirilmeden önce `batch-rows`
sınırıyla kontrol edilir.
Yenileme SQL çalıştırır; GET isteğinin içine gizlenmemelidir.
Bu bildirimler süreli ipuçlarıdır; kalıcı outbox veya CDC yerine geçmez.
Kurtarma için dönemsel tam karşılaştırmayı ya da kalıcı değişiklik kaynağını koruyun.

Yalnızca demoda açık olan `POST /api/catalog/dirty`, `{"ids":[9200001,9200002]}` alır ve
eklenen kimlik sayısıyla 202 döndürür. `POST /api/catalog/dirty/flush` ise 202 ve
işin takip adresini döndürür. Bu adresi COMPLETED veya FAILED görülene kadar izleyin.
Bu komutlar, açıkça etkinleştirilen demo profili dışında kapalıdır.

`src/main/java/com/example/cachedb/sample/catalog/CatalogDirtyJobHandler.java`:

```java
package com.example.cachedb.sample.catalog;

import com.reactor.cachedb.annotations.CacheJobHandler;
import com.reactor.cachedb.spring.boot.CacheDistributedJobDefinition;
import com.reactor.cachedb.spring.boot.snapshot.SnapshotJobs;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** SQL reconciliation is explicit worker work, never a hidden read-endpoint side effect. */
@Component
@ConditionalOnProperty(prefix="sample.demo", name="write-tools-enabled", havingValue="true")
public final class CatalogDirtyJobHandler {
    public static final CacheDistributedJobDefinition<CatalogRefreshCommand> DEFINITION =
            CacheDistributedJobDefinition.of("sample.catalog.flush-dirty", CatalogRefreshCommand.class);
    private final SnapshotJobs snapshots;
    public CatalogDirtyJobHandler(SnapshotJobs snapshots) { this.snapshots = snapshots; }

    @CacheJobHandler("sample.catalog.flush-dirty")
    public Object flush(CatalogRefreshCommand command) {
        if (!command.ids().isEmpty()) throw new IllegalArgumentException("Flush consumes the already marked roots, not an input ID list");
        return snapshots.flushDirty(CatalogConfiguration.PLAN).<Object>map(value -> value)
                .orElseGet(() -> Map.of("status", "NO_CHANGES"));
    }
}
```

API pod'larında `client`, SQL hazırlığını yapan pod'larda `worker` profilini kullanın.
İş yayımlayan executor framework tarafından sağlanır; elle yapılandırma gerekmez.
Yalnızca yayımlayan pod, işleyici/heartbeat veya snapshot zamanlayıcı iş parçacığı
başlatmaz. Redis Cluster'da anahtarlar aynı slot'taysa tek atomik script kullanılır.
Farklı slot'larda kontrollü kuyruğa alma ve hata halinde temizleme yolu korunur.
Redis erişilebilir olmalıdır; bu yapı bellekte çalışan kalıcı bir kuyruk değildir.

`withContentReuse()`, JSON diske yazılırken özetini hesaplar. Değişmeyen içerik
yeniden okunup gönderilmeden paylaşılır. İçerik yayın öncesinde silinirse dosyadaki
veriyle tekrar denenir. Bu seçenek, SQL hazırlığını ortadan kaldırmaz ve belleğe
sınırsız veri alma izni vermez.

| Gerçek ihtiyaç | Kullanılan yapı | Gösterdiği davranış |
| --- | --- | --- |
| Mağaza ekranında küçük ve hızlı bir stok kartı göstermek | Üretilmiş JSON codec'i ve tipli snapshot | Veriyi SQL'den açıkça hazırlama, yalnızca Redis'ten okuma, Türkçe metin ve ondalık sayı doğruluğu |
| Tedarikçinin aynı yeni ürün listesini iki kez göndermesi | Atomik katalog aktarımı ve sabit batch kimliği | Redis'in tüm grubu birlikte kabul etmesi, tekrar gönderim güvenliği ve ayrı SQL kalıcılığı kontrolü |
| Bir kategorinin stoğu değişirken diğerinde sayfalamaya devam etmek | Kapsamı belirlenmiş snapshot yenileme | Etkilenmeyen kategorinin cursor'ı korunur; değişen kategoride sayfalama yeniden başlar |
| API trafiği ile SQL'e yazma kapasitesini ayrı ölçeklemek | `client` / `worker` profilleri ve Compose | API işi kabul eder; ayrı işleyiciler veriyi kalıcılaştırır |
| Fiziksel silmeden sonra eski bir yazmanın gecikerek gelmesi | Geçici veritabanında sağlayıcı entegrasyon testi | Eski UPSERT elenir; iş verisi ile sürüm koruması birlikte geri alınır |



### 1. Jackson Eşlemesi Olmadan Stok Kartı Hazırlama

`CatalogCard`, entity'nin tamamı değil, ekranın ihtiyaç duyduğu küçük yanıt
modelidir. Annotation processor, `CatalogCardJsonCodec` sınıfını üretir; bu sınıfı
elle oluşturmayın ve Git'e eklemeyin. Spring MVC'nin normal HTTP JSON işlemesi
değişmez. Üretilmiş codec yalnızca snapshot hazırlama ve tipli snapshot okuma
yolunda açıkça seçilir.

`src/main/java/com/example/cachedb/sample/catalog/CatalogCard.java`:

```java
package com.example.cachedb.sample.catalog;

import com.example.cachedb.sample.domain.ProductEntity;
import com.reactor.cachedb.annotations.CacheJsonRecord;

import java.math.BigDecimal;

@CacheJsonRecord
public record CatalogCard(
        long productId,
        String sku,
        String name,
        String category,
        int available,
        BigDecimal unitPrice) {
    public static CatalogCard from(ProductEntity product) {
        return new CatalogCard(
                product.productId,
                product.sku,
                product.productName,
                product.category,
                Math.max(0, product.stockQuantity - product.reservedQuantity),
                product.unitPrice);
    }
}
```

Plan SQL'den yalnızca aktif ürünleri okur. Bütün kök listesini bellekte tutmak
yerine kayıtları sırayla hazırlar. Sıralı indeks kategoriye göre bölünür; kısmi
yenileme için parametreli SQL kapsamı açıkça tanımlanır. Kullanılabilir stok
küçükten büyüğe sıralanır. Eşit stokta ürün kimliğinin metinsel sırası kullanılır.

`src/main/java/com/example/cachedb/sample/catalog/CatalogConfiguration.java`:

```java
package com.example.cachedb.sample.catalog;

import com.example.cachedb.sample.domain.ProductEntity;
import com.example.cachedb.sample.domain.ProductEntityCacheBinding;
import com.reactor.cachedb.spring.boot.snapshot.*;
import com.reactor.cachedb.starter.CacheDatabase;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class CatalogConfiguration {
    public static final String PLAN = "stock-catalog";

    @Bean
    SnapshotPlan<ProductEntity, CatalogCard> stockCatalog() {
        var source =
                SnapshotSource.entity("products", ProductEntityCacheBinding.SOURCE)
                        .where(SnapshotPredicate.eq(ProductEntityCacheBinding.Columns.ACTIVE_STATUS, "ACTIVE"));
        return SnapshotPlan.streaming(
                        PLAN,
                        source,
                        row -> row.productId.toString(),
                        CatalogCard.class,
                        row -> List.of(CatalogCard.from(row)))
                .indexed(
                        SnapshotIndexSpec.of(
                                row -> row.category,
                                row -> Math.max(0, row.stockQuantity - row.reservedQuantity)))
                .scopedBy(source, ProductEntityCacheBinding.Columns.PRODUCT_ID)
                .autoDirtyOnRootEntity()
                .withGeneratedJsonCodec()
                .withContentReuse();
    }

    @Bean
    TypedSnapshotRepository<Long, CatalogCard> stockCatalogRepository(
            SnapshotJobs jobs, SnapshotPlan<ProductEntity, CatalogCard> stockCatalog) {
        return stockCatalog.bind(jobs.repository(PLAN), Object::toString);
    }

    @Bean
    CatalogImportService catalogImports(CacheDatabase database) {
        return new CatalogImportService(database::writeBatch, database::awaitDurable);
    }
}
```

Örneğin POM dosyasında Gson **2.13.2** açıkça sabitlenmiştir. Aksi hâlde Spring
Boot parent, gerekli `Strictness` sınıfını içermeyen eski Gson sürümünü seçebilir.
POM'da `cachedb-json`, annotation bağımlılığı ve 0.19.0 processor birlikte bulunur.
Gson sürüm ayarını kaldırmayın; farklı framework sürümlerini karıştırmayın.

0.19.0 BOM ve annotation processor ayarları mevcutsa, derlenen örneğin ek çalışma
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
$base = 'http://127.0.0.1:8091'
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
package com.example.cachedb.sample.catalog;

import com.example.cachedb.sample.domain.ProductEntity;

import java.math.BigDecimal;

/** Complete insert-only source row. updatedAt comes from the source, never from a retry clock. */
public record CatalogImportItem(
        long productId,
        String sku,
        String name,
        String category,
        BigDecimal unitPrice,
        int stock,
        int reserved,
        long updatedAt) {
    public CatalogImportItem {
        if (productId < 1
                || unitPrice == null
                || unitPrice.signum() < 0
                || (long) unitPrice.precision() - unitPrice.scale() > 15
                || unitPrice.scale() > 4
                || stock < 0
                || reserved < 0
                || reserved > stock
                || updatedAt < 1) {
            throw new IllegalArgumentException(
                    "Invalid complete catalog row or NUMERIC(19,4) price");
        }
        text(sku, 64, "sku");
        text(name, 160, "name");
        text(category, 64, "category");
    }

    private static void text(String value, int maximum, String field) {
        if (value == null || value.isBlank() || value.length() > maximum) {
            throw new IllegalArgumentException(
                    field + " must contain 1.." + maximum + " characters");
        }
    }

    public ProductEntity entity() {
        var row = new ProductEntity();
        row.productId = productId;
        row.sku = sku;
        row.productName = name;
        row.category = category;
        row.activeStatus = "ACTIVE";
        row.unitPrice = unitPrice;
        row.stockQuantity = stock;
        row.reservedQuantity = reserved;
        row.stockStatus = stock - reserved == 0 ? "OUT_OF_STOCK" : "IN_STOCK";
        row.updatedAt = updatedAt;
        return row;
    }
}
```

`src/main/java/com/example/cachedb/sample/catalog/CatalogImportCommand.java`:

```java
package com.example.cachedb.sample.catalog;

import java.util.HashSet;
import java.util.List;

public record CatalogImportCommand(String batchId, List<CatalogImportItem> items) {
    public CatalogImportCommand {
        if (batchId == null || !batchId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException(
                    "batchId must contain 1..64 letters, digits, underscores or hyphens");
        }
        if (items == null || items.isEmpty() || items.size() > 25) {
            throw new IllegalArgumentException("A catalog import requires 1..25 complete rows");
        }
        items = List.copyOf(items);
        var ids = new HashSet<Long>();
        var skus = new HashSet<String>();
        for (var item : items) {
            if (!ids.add(item.productId()) || !skus.add(item.sku())) {
                throw new IllegalArgumentException("Duplicate product IDs or SKUs in one batch");
            }
        }
    }
}
```

Uygulama servisi bellek içi test oturumuyla da doğrudan sınanabilir. İki işlev
referansını HTTP istemcisi değil, `CatalogConfiguration` sağlar. Süre sınırlı
kalıcılık beklemesini arka plan işleyicisi yapar; HTTP isteği SQL için üç saniye
boyunca açık tutulmaz.

`src/main/java/com/example/cachedb/sample/catalog/CatalogImportService.java`:

```java
package com.example.cachedb.sample.catalog;

import com.reactor.cachedb.core.api.CacheWriteBatch;
import com.reactor.cachedb.core.api.CacheWriteBatchReceipt;

import java.time.Duration;
import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.Function;

/** Executes on a bounded background worker, not on the HTTP request thread. */
public final class CatalogImportService {
    private final Function<String, CacheWriteBatch> batches;
    private final BiPredicate<CacheWriteBatchReceipt, Duration> durability;

    public CatalogImportService(
            Function<String, CacheWriteBatch> batches,
            BiPredicate<CacheWriteBatchReceipt, Duration> durability) {
        this.batches = Objects.requireNonNull(batches);
        this.durability = Objects.requireNonNull(durability);
    }

    public Result execute(CatalogImportCommand command) {
        var batch = batches.apply("sample-catalog-" + command.batchId());
        for (var item : command.items()) {
            batch.put(item.entity(), 0L);
        }
        var receipt = batch.commit();
        return new Result(
                "ACCEPTED", receipt, durability.test(receipt, Duration.ofSeconds(3)), null);
    }

    public record Result(
            String outcome, CacheWriteBatchReceipt receipt, boolean durable, String message) {}
}
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

Fiyat kontrolü, aşırı büyük bilimsel üsleri kuyruğa alınmadan reddeder.
NUMERIC(19,4) sınırlarına sığan normal bilimsel gösterim kullanılabilir.

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
Ayrıca `spring.main.keep-alive=true` ayarı bulunur. Arka plan havuzları daemon
iş parçacıkları kullanır; web sunucusu olmayan JVM'in kendiliğinden kapanmaması
için uygulama yaşam döngüsü bu ayarla açıkça korunur.
Entity tanımları, JSON codec'leri, Redis, SQL ve iş tanımları ortaktır.
Otomatik yapılandırma, `CLIENT_ONLY` için yalnızca iş yayımlayan
executor'ı da sağlar. Tanımlı komutları kabul eder; işleri tüketmez ve boşta
işleyici/heartbeat iş parçacığı başlatmaz. Ek yapılandırma sınıfı gerekmez.

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
package com.example.cachedb.sample.catalog;

import static org.junit.jupiter.api.Assertions.*;

import com.example.cachedb.sample.domain.ProductEntityCacheBinding;
import com.example.cachedb.sample.repository.ProductRepositoryCacheDbImplementation;
import com.example.cachedb.sample.web.CatalogController;
import com.reactor.cachedb.core.model.OptimisticWriteConflictException;
import com.reactor.cachedb.core.repository.WindowRequest;
import com.reactor.cachedb.core.testing.InMemoryCacheDatabase;
import com.reactor.cachedb.spring.boot.snapshot.*;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

class CatalogFeatureTest {
    private static final class TestClock extends java.time.Clock {
        long now = 1_700_000_000_000L;

        @Override
        public java.time.ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
        }

        @Override
        public java.time.Clock withZone(java.time.ZoneId zone) {
            if (!getZone().equals(zone)) throw new IllegalArgumentException("UTC fixture");
            return this;
        }

        @Override
        public long millis() {
            return now;
        }

        @Override
        public java.time.Instant instant() {
            return java.time.Instant.ofEpochMilli(now);
        }
    }

    @Test
    void staleWarningIsNotAbsenceOrAnImplicitSqlRefresh() {
        var clock = new TestClock();
        var raw = new InMemorySnapshotRepository(clock, java.time.Duration.ofSeconds(1));
        raw.putSnapshot("1", "[]");
        var typed = raw.typedRepository(CatalogCardJsonCodec.INSTANCE);
        assertEquals(SnapshotStatus.FOUND, typed.lookup("1").status());
        clock.now += 2_000;
        assertEquals(SnapshotStatus.STALE_WARNING, typed.lookup("1").status());
        assertTrue(typed.findValuesById("1").orElseThrow().isEmpty());
        assertEquals(SnapshotStatus.ABSENT_IN_GENERATION, typed.lookup("2").status());
    }

    static CatalogImportItem item(long id, String category) {
        return new CatalogImportItem(
                id,
                "SKU-" + id,
                "\u0130\u011fne \u00e7elik \ud83d\ude80",
                category,
                new BigDecimal("12.3456"),
                8,
                2,
                1_700_000_000L);
    }

    @Test
    void generatedCodecPreservesUnicodeMoneyAndEmptyArrays() throws Exception {
        var expected = List.of(CatalogCard.from(item(1, "tools").entity()));
        var output = new ByteArrayOutputStream();
        CatalogCardJsonCodec.INSTANCE.encodeArray(expected, output);
        assertEquals(
                expected,
                CatalogCardJsonCodec.INSTANCE.decodeArray(output.toString(StandardCharsets.UTF_8)));
        assertTrue(CatalogCardJsonCodec.INSTANCE.decodeArray("[]").isEmpty());
        assertThrows(
                java.io.IOException.class,
                () -> CatalogCardJsonCodec.INSTANCE.decodeArray("[{\"extra\":1}]"));
    }

    @Test
    void generatedRepositoryAcceptsTestSessionAndDoesNotInventSqlDurability() {
        var memory = new InMemoryCacheDatabase();
        var repository = new ProductRepositoryCacheDbImplementation(memory);
        var accepted = repository.save(item(1, "tools").entity());
        assertTrue(repository.findHotById(1L).isHit());
        assertFalse(memory.isDurable(accepted));
        memory.markDurable(accepted);
        assertTrue(memory.isDurable(accepted));
    }

    @Test
    void atomicImportReplayRetainsReceiptAndRequiresExplicitDurabilityEvidence() {
        var memory = new InMemoryCacheDatabase();
        ProductEntityCacheBinding.repository(memory);
        var imports =
                new CatalogImportService(
                        memory::writeBatch, (receipt, timeout) -> memory.isDurable(receipt));
        var command =
                new CatalogImportCommand("delivery-1", List.of(item(1, "tools"), item(2, "tools")));
        var first = imports.execute(command);
        assertFalse(first.durable());
        memory.markDurable(first.receipt());
        var replay = imports.execute(command);
        assertEquals(first.receipt(), replay.receipt());
        assertTrue(replay.durable());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        imports.execute(
                                new CatalogImportCommand("delivery-1", List.of(item(3, "tools")))));
    }

    @Test
    void optimisticConflictLeavesAllOtherBatchMembersUnchanged() {
        var memory = new InMemoryCacheDatabase();
        var products = ProductEntityCacheBinding.repository(memory);
        products.saveWithReceipt(item(2, "tools").entity());
        var imports = new CatalogImportService(memory::writeBatch, (receipt, timeout) -> false);
        assertThrows(
                OptimisticWriteConflictException.class,
                () ->
                        imports.execute(
                                new CatalogImportCommand(
                                        "conflict", List.of(item(1, "tools"), item(2, "tools")))));
        assertTrue(products.findById(1L).isEmpty());
    }

    @Test
    void snapshotStateAndUnrelatedPartitionPaginationStayExplicit() throws Exception {
        var raw = new InMemorySnapshotRepository();
        var typed =
                TypedSnapshotRepository.decoding(
                        raw, CatalogCardJsonCodec.INSTANCE, (Long id) -> id.toString());
        var api = new CatalogController(typed);
        assertEquals(503, api.item(1).getStatusCode().value());
        var encoded = new ByteArrayOutputStream();
        CatalogCardJsonCodec.INSTANCE.encodeArray(
                List.of(CatalogCard.from(item(1, "tools").entity())), encoded);
        String payload = encoded.toString(StandardCharsets.UTF_8);
        raw.putSnapshot("1", payload, "tools", 1);
        raw.putSnapshot("2", payload, "tools", 2);
        raw.putSnapshot("3", payload, "other", 3);
        assertEquals(200, api.item(1).getStatusCode().value());
        assertEquals(404, api.item(99).getStatusCode().value());
        var first = typed.findTypedPage("tools", WindowRequest.first(1));
        raw.putSnapshot("3", payload, "other", 4);
        assertEquals(
                "2",
                typed.findTypedPage("tools", WindowRequest.after(first.nextCursor(), 1))
                        .items()
                        .getFirst()
                        .id());
        raw.putSnapshot("1", payload, "tools", 5);
        assertThrows(
                IllegalArgumentException.class,
                () -> typed.findTypedPage("tools", WindowRequest.after(first.nextCursor(), 1)));
        raw.expire();
        assertEquals(503, api.item(1).getStatusCode().value());
    }

    @Test
    void deterministicBatchRejectionIsNotRetriedAsAnInfrastructureFailure() {
        var service =
                new CatalogImportService(
                        id -> {
                            throw new redis.clients.jedis.exceptions.JedisDataException(
                                    "Batch id reused with different operations");
                        },
                        (receipt, timeout) -> false);
        var handler = new CatalogImportJobHandler(service);
        var rejected =
                (CatalogImportService.Result)
                        handler.execute(
                                new CatalogImportCommand("delivery", List.of(item(1, "tools"))),
                                null);
        assertEquals("REJECTED", rejected.outcome());
        assertFalse(rejected.durable());
        assertNull(rejected.receipt());
        var broken =
                new CatalogImportService(
                        id -> {
                            throw new redis.clients.jedis.exceptions.JedisDataException(
                                    "WRONGTYPE unexpected storage error");
                        },
                        (receipt, timeout) -> false);
        assertThrows(
                redis.clients.jedis.exceptions.JedisDataException.class,
                () ->
                        new CatalogImportJobHandler(broken)
                                .execute(
                                        new CatalogImportCommand(
                                                "delivery", List.of(item(1, "tools"))),
                                        null));
    }

    @Test
    void sourceLimitsAndRequestContractsRejectUnboundedOrAmbiguousImports() {
        assertThrows(
                IllegalArgumentException.class, () -> new CatalogImportCommand("x", List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CatalogImportCommand("x", List.of(item(1, "a"), item(1, "b"))));
        assertThrows(IllegalArgumentException.class, () -> new CatalogRefreshCommand(List.of(-1L)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CatalogImportItem(1, "a", "b", "c", new BigDecimal("1E+20"), 1, 0, 1));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new CatalogImportItem(
                                1, "a", "b", "c", new BigDecimal("1E+2147483647"), 1, 0, 1));
        assertDoesNotThrow(
                () -> new CatalogImportItem(1, "a", "b", "c", new BigDecimal("1E+14"), 1, 0, 1));
        var plan = new CatalogConfiguration().stockCatalog();
        assertSame(CatalogCardJsonCodec.INSTANCE, plan.jsonCodec());
        assertNotNull(plan.rootScope());
        assertNotNull(plan.indexSpec());
    }
}
```

Gerçek sağlayıcı entegrasyon testi ayrıca `CatalogScenarios`,
`WriteFenceScenarios` ve `SplitRoleScenarios` kontrollerini çalıştırır. REST ile
gerçek Redis/SQL akışı, fiziksel DELETE sonrasında writer yeniden oluşturulup eski
UPSERT'in gönderilmesi, SQL/metadata'nın birlikte geri alınması ve istemcide
bekleyen yazmanın ayrı işleyici ile onun yerine başlayan işleyicide tamamlanması
doğrulanır. Replay müdahalesi **yalnızca geçici testlerde** vardır; üretim HTTP
endpoint'i olarak sunulmaz. Kalıcı `cachedb_write_fence` tablosunu koruyun;
sağlayıcı migration'ı ve kontrollü yükseltme rehberini uygulayın.

`postman/cache-database-postgresql-catalog.postman_collection.json` dosyasını içe
aktarın. **23 isteği** sırayla çalıştırın; şema kurulmuşsa demo seed gerekmez.
Akış; ilk süre sınırlı bekleme yeterli değilse yalnızca aynı teslimatı tekrar
göndererek SQL kalıcılığını, aynı receipt ile tekrarı, değişen içeriğin reddini,
snapshot yayınını, Türkçe metin/ondalık sayıyı, tek nesilden okumayı, sayfalamayı
ve geçersiz girdi yanıtlarını doğrular. Ayrı iş senaryosu koleksiyonu eski
61 isteği korur ve 23 metot önbelleği/asenkron isteği ekler; toplam 84 istektir.
İki koleksiyon da REST için 50 ms SLA'sı iddia etmez.

```powershell
npx --yes newman@6.2.1 run postman/cache-database-postgresql-catalog.postman_collection.json --env-var baseUrl=$base
```

Entegrasyon testi, etkilenmeyen kategoride cursor'ın korunmasını da doğrular;
Postman akışı SQL'i doğrudan değiştirmez. Redis Cluster namespace dağılımı bir
framework altyapı yeteneğidir; yerel Compose tek Redis kullanır ve Cluster/HA
topolojisini doğruladığını iddia etmez. JSON ve rol seçimleri açıktır; ORM'nin
kendiliğinden SQL'e geçmesi gibi bir davranış eklenmez.

Yalıtılmış komut satırı testi için Node.js/npm ve PowerShell 7.3+ kurulu olmalıdır.
JAR'ı derledikten sonra `./scripts/test-catalog-postman.ps1` çalıştırın. Araç;
geçici sağlayıcı altyapısını ve boş yerel portta JAR'ı başlatır; katalog (23),
denetim (40) ve iş akışı (84) koleksiyonlarını Newman ile çalıştırır.
JSON/JUnit raporlarını `target/catalog-postman` ve `target/v019-postman` altına
yazar; yalnızca kendi test projesini/volume'larını kaldırır.
Bu aracı üretim veritabanına bağlamayın.
`-SplitRoles` seçeneği, yerel şemayı hazırlayıp hazırlık JVM'ini durdurur; aynı
koleksiyonu bir API konteyneri ve **iki worker konteyneri** üzerinde çalıştırır.
Örneklerin CI hattı bu modu kullanır; konteyner logları test kanıtına eklenir.

İş senaryosu koleksiyonunun eski 61 istekli bölümü, kabul edilen her arka plan
işinden sonra kayıtlı durum isteğini otomatik sorgular ve tamamlanınca sıradaki
isteğe geçer. Bekleme en fazla 30 saniye veya 150 denemedir. Her yanıtın HTTP
kodu doğrulanır; başarısız işten sonra okumaya devam edilmez. HTTP yük kontrolünden
hemen önce katalog açıkça yenilenmelidir. `warn-age` süresinden sonra görülen
`STALE_WARNING`, verinin yaşıyla ilgili bilinçli bir uyarıdır; kaydın bulunamadığı
veya sistemin kendiliğinden SQL'e geçmesi gerektiği anlamına gelmez.
<!-- END COMPILED FEATURE EXAMPLES -->

## 0.19 örnek kabulünün sınırı

<!-- BEGIN V019 PENDING COVERAGE -->
Sürüm kontrollerinin yetkili kaynağı, tam sürüm commit'inin CI sonuçları ve kaynak, SDK ve örnek
paketlerine bağlı kanıttır. [0.19.0 sürüm notlarını](https://github.com/esasmer-dou/cache-database/blob/main/tr/docs/releases/v0.19.0.md#sürüm-kanıtının-yetkili-kaynağı) inceleyin.
Aşağıdaki bağlar uygulama ve doğrulama girişlerini gösterir; başarılı çalışma sonucu değildir.
Ana matris ve her özellik kapsamı kendi eşleşen kanıtını gerektirir; tek özellik kanıtı tüm
örneği, uygulamanın koruma geçişini veya sürümü doğrulamaz.

| Özellik | Doğrulamanın yetkili kaynağı | Mevcut kaynak ve test kanıtı | Bağımsız Postman runner |
| --- | --- | --- | --- |
| spring-data-bulk-delete | Sürüm commit CI / pakete bağlı kanıt | [AuditNoteController](src/main/java/com/example/cachedb/sample/audit/AuditNoteController.java), [AuditExternalIT](src/test/java/com/example/cachedb/sample/audit/AuditExternalIT.java), [DerivedDeleteRangeExternalIT](src/test/java/com/example/cachedb/sample/rangedata/DerivedDeleteRangeExternalIT.java) | [42 audit isteği](scripts/test-audit-postman.ps1) |
| field-encryption | Sürüm commit CI / pakete bağlı kanıt | [EncryptedNoteController](src/encryption/java/com/example/cachedb/sample/encryption/EncryptedNoteController.java), [EncryptedNoteContractTest](src/encryption-test/java/com/example/cachedb/sample/encryption/EncryptedNoteContractTest.java), [EncryptedNoteExternalIT](src/encryption-test/java/com/example/cachedb/sample/encryption/EncryptedNoteExternalIT.java) | [10 şifreleme isteği](scripts/test-encryption-postman.ps1) |
| retention | Sürüm commit CI / pakete bağlı kanıt | [AuditNoteController.sweep](src/main/java/com/example/cachedb/sample/audit/AuditNoteController.java), [AuditExternalIT](src/test/java/com/example/cachedb/sample/audit/AuditExternalIT.java), [AuditHttpContractTest](src/test/java/com/example/cachedb/sample/audit/AuditHttpContractTest.java) | [Aynı 42 audit isteği, bir kez](scripts/test-audit-postman.ps1) |
| tenant-purge | Sürüm commit CI / pakete bağlı kanıt | [TenantPurgeRuntime](src/purge/java/com/example/cachedb/purge/TenantPurgeRuntime.java), [TenantPurgeOptInTest](src/purge-test/java/com/example/cachedb/purge/TenantPurgeOptInTest.java), [TenantPurgeContractTest](src/purge-test/java/com/example/cachedb/purge/TenantPurgeContractTest.java), [TenantPurgeExternalIT](src/purge-test/java/com/example/cachedb/purge/TenantPurgeExternalIT.java) | [8 istek / 36 assertion](scripts/test-tenant-purge-postman.ps1) |

Audit yolu sıfır seçim bütçesini reddeder; pozitif sınırlı seçim ve tam kapasitede
mutasyondan önce ret, ayrı range-derived dış testindedir. Redis kabulü veya sweep
yanıtı SQL dayanıklılığı/kaynak silinmesi kanıtı değildir. Şifreli notlar SQL'de
bilinçli açık metin tutan REDIS_ONLY örneğidir; korumalı kaynak onboarding kanıtı
değildir. Ayrı purge eşlikçisi yalnızca taze INITIAL gösterir; restart/HA veya
yedek silme kanıtlamaz. Purge dış testi ve ayrı uygulama için farklı boş şemalar
gerekir. Kanonik migration'ları uygulama çalıştırır; önceden uygulamayın.

Yalnızca parent'ın kabul ettiği K8s kohortunda, mevcut ortak kilit, özgün süreler,
tek CPU/APC1 ve 512 MiB heap ile çalıştırın. Byte-doğrulanmış final SDK/önbellek,
settings/araç pinleri ve sahipliği belirli geçici fixture parent girdisidir.
Yerel çalıştırma veya otomatik online fallback yoktur. Dış SQL/Redis URL'leri
`.<CACHEDB_TEST_NAMESPACE>.svc.cluster.local` içermelidir. Sır/anahtarlar uzak
özel ortamda/Secret mount'ta kalır. `CACHEDB_TEST_DATASOURCE_URL`,
`CACHEDB_TEST_DATASOURCE_USERNAME`, `CACHEDB_TEST_DATASOURCE_PASSWORD` ve
`CACHEDB_TEST_REDIS_URI` gerekir. Eksik fixture veya skip PASS değildir.

BU sağlayıcının üç ayrı, target içermeyen kopyasını `AUDIT_WORK`,
`ENCRYPTION_WORK`, `PURGE_WORK` olarak hazırlayın. Şifreleme target'ını purge
için kullanmayın. `SDK_REPO`, `QUALIFIED_CPU` ve iki Maven settings yolu
parent tarafından doğrulanır. Her lane başlamadan kendi özgün admission süresine
sığmalıdır; 900 saniyelik tavan fixture süresini uzatma izni değildir.

```bash
export MAVEN_OPTS="-Xms64m -Xmx512m -XX:ActiveProcessorCount=1"
common=(-o -B -ntp -C -s "$SETTINGS" -gs "$GLOBAL_SETTINGS" "-Dmaven.repo.local=$SDK_REPO"
  -DskipTests=false -Dmaven.test.skip=false -Dmaven.test.failure.ignore=false
  -DfailIfNoTests=true -Dsurefire.failIfNoSpecifiedTests=true -Dfailsafe.failIfNoSpecifiedTests=true
  -Dsurefire.rerunFailingTestsCount=0 -Dfailsafe.rerunFailingTestsCount=0
  -DforkCount=1 -DreuseForks=true -Djunit.jupiter.execution.parallel.enabled=false
  '-DargLine=-Xms64m -Xmx512m -XX:ActiveProcessorCount=1')
timeout --signal=TERM --kill-after=10s 900s taskset -c "$QUALIFIED_CPU" mvn "${common[@]}" -f "$AUDIT_WORK/pom.xml" -Pprovider-integration -Dtest=AuditContractTest,AuditHttpContractTest -Dit.test=AuditExternalIT,DerivedDeleteRangeExternalIT verify
timeout --signal=TERM --kill-after=10s 900s taskset -c "$QUALIFIED_CPU" mvn "${common[@]}" -f "$ENCRYPTION_WORK/pom.xml" -Pencrypted-demo,provider-integration -Dtest=EncryptedNoteContractTest -Dit.test=EncryptedNoteExternalIT verify
timeout --signal=TERM --kill-after=10s 900s taskset -c "$QUALIFIED_CPU" mvn "${common[@]}" -f "$PURGE_WORK/pom.xml" -Ptenant-purge-demo,provider-integration -Dtest=TenantPurgeOptInTest,TenantPurgeContractTest -Dit.test=TenantPurgeExternalIT verify
```

İlk başarısız komutta durun; otomatik tekrar yoktur. Sağlayıcı başına tam roster:
audit 8 contract + 6 HTTP contract + 5 external + 2 derived-delete = 21;
şifreleme 5 contract + 2 external = 7; purge 7 opt-in + 11 contract + 1 external
= 19. Üç sağlayıcıda 27 suite / 141 case gerekir; failure/error/skip/flaky/rerun
sıfır olmalıdır. Tam metot üyeliğini, XML ve exit'leri, final SDK/kaynak/önbellek
hash'lerini ve preservation kanıtını saklayın; yalnızca sayı yeterli değildir.

Paketlenmiş uygulamalar ayrı admission ile başladıktan sonra mevcut runner'ları
birer kez çalıştırın:
```bash
export npm_config_offline=true
timeout --signal=TERM --kill-after=10s 900s pwsh -NoProfile -File "$AUDIT_WORK/scripts/test-audit-postman.ps1" -BaseUrl "$AUDIT_BASE_URL" -ConfirmDisposableDemo
timeout --signal=TERM --kill-after=10s 900s pwsh -NoProfile -File "$ENCRYPTION_WORK/scripts/test-encryption-postman.ps1" -BaseUrl "$ENCRYPTION_BASE_URL" -ConfirmDisposableDemo
timeout --signal=TERM --kill-after=10s 900s pwsh -NoProfile -File "$PURGE_WORK/scripts/test-tenant-purge-postman.ps1" -BaseUrl "$PURGE_BASE_URL" -RunId "$SAMPLE_PURGE_RUN_ID" -ConfirmDisposableDemo
```
Parent doğrulanmış offline Newman 6.2.1 önbelleğini ve uygulamayla eşleşen özel
kimlik bilgilerini sağlar. Audit/şifreleme wrapper'ları online paket edinmemelidir.
Sağlayıcı başına 60 farklı istek, toplam 180 istek vardır. Bulk ve retention aynı
audit koşusunu paylaşır; iki kez saymayın. Ayrı 834 HTTP kontrolü değişmez.
Bu 180 dört-özellik isteği, tarihsel 180 istekli ana rota kapısı değildir.
Sonuçları tam sürüm commit'inin CI kayıtları ve eşleşen pakete bağlı raporlarla belirleyin.
Ana matris, özellik ve sürüm kapsamlarını ayrı değerlendirin; eksik veya atlanmış zorunlu kanıt
PASS değildir. Geçmiş aday sonuçları değişmiş paketleri kendiliğinden doğrulamaz.
<!-- END V019 PENDING COVERAGE -->

## İsteğe bağlı yerel tenant temizleme (F3)

Bu isteğe bağlı eşlikçinin bağımsız doğrulama kapsamını, tam sürüm commit'inin CI
sonuçları ve pakete bağlı kanıt belirler. Ana matrisi, tüm örneği, gerçek uygulamanın
koruma geçişini veya sürümü doğrulamaz. Aşağıdaki komutlar onaylı ve yalıtılmış
Kubernetes fixture'ı, sıralı çalışma, Java 21+, tek CPU ve 512 MiB test heap gerektirir.
`SDK_REPO`, hash ile doğrulanmış eşleşen `0.19.0` SDK'sını içeren özel Maven önbelleği
olmalıdır; offline çözümleme başarısız olursa eski public SDK'ya dönmeyin.

`tenant-purge-demo` Maven profili yalnızca `src/purge` ve `src/purge-test`
dizinlerini ekler. Ayrı `TenantPurgeApplication`, eski global şifreli notları
taramaz ve onlara tenant eklemez. `TenantPurgeRow`, üretilen native bildirimleri,
Long 1..8 kimlikleri, tenant sahipliği ve mevcut anahtar sağlayıcı düzeniyle
REDIS_AND_SOURCE şifrelemeyi kullanır. CLIENT_ONLY / REDIS_ONLY çalışır; L1
kapalıdır. İki gerçek root, onaylı roster, native namespace bootstrap,
INITIAL enrollment ve seal protokolüne katılır.

Yeni koşu tam sekiz SQL satırı oluşturur: sahip olunan 1..7 ve yabancı tenant'a
ait 8. INITIAL yalnızca 1..2'yi Redis'e alır; 3..7 gerçek soğuk SQL satırlarıdır.
Facade `includeSource=true` kullanır; yalnızca önbellek silmeyi purge diye sunmaz.
HTTP teslim callback'i, native okuma bariyerini yanıtın flush işlemi bitene kadar
tutar. Redis owner kaydı olmayan soğuk satırın HTTP okuması 409 döner; gizli SQL
fallback yoktur. Başarılı purge, SQL ve Redis CLOSED generation/güvenlik
metadata'sını korur. Otomatik açma veya reopen endpoint'i yoktur; yeniden açma
ayrıca incelenmiş açık idari yaşam döngüsü gerektirir. Başlatma yalnızca taze
fixture içindir; uygulamanın crash-resume kanıtı değildir.

Ayrı yönetim akışı için [açık onayla EMPTY yeniden açma](../tr/docs/tenant-veri-koruma.md) sözleşmesine bakın.
EMPTY, silinen kimliklerin sahipliğini geri getirmez. Yeni Long kimlikler için INITIAL
sırasında yalnız bu ad alanına ayrılmış bir kimlik aralığı tanımlanmalı; ardından
[`reserveAndAdmitFreshRange`](../tr/docs/tenant-protection-components.md) ile daha ilerideki,
çakışmayan bir aralık hazırlanmalıdır. Aynı rehberdeki `refillVersionRange` ise kayıt
kimliklerini değil, yazma sürümlerini yeniler. Bu iki işlem örneğin HTTP API'sinde sunulmaz.

**Geçici PostgreSQL fixture:** Önceden oluşturulmuş ayrı bir geçici veritabanı/şema kullanın: CONNECT, şema USAGE/CREATE ve oluşturulan tablo/indekslerde sahiplik, CRUD ve metadata erişimi gerekir. Superuser gerekmez.
Uygulama `com/reactor/cachedb/jdbc/migrations/postgres/` altındaki yedi
kanonik JDBC migration'ını (V014, ardından V019..V019_5) ve sonra
`src/purge/resources/schema-purge.sql` dosyasını çalıştırır.
Bu adımlar idempotent değildir. Her dış test ve ayrı HTTP/Postman başlatması için
farklı boş şema kullanın; migration'ları önceden uygulamayın, eski/ortak şemayı
seçmeyin. Redis, F3 Functions/Lua/transaction yetkileri olan onaylı özel
standalone Redis 7+ DB0 fixture olmalıdır. FLUSH yoktur; Cluster, TLS ve
kimlik bilgisi içeren Redis URI bu sınırlı örnekte desteklenmez.

**Dış test ortamı:** `CACHEDB_TEST_NAMESPACE` (`cachedb-...`),
`CACHEDB_TEST_DATASOURCE_URL`, `CACHEDB_TEST_DATASOURCE_USERNAME`,
`CACHEDB_TEST_DATASOURCE_PASSWORD`, `CACHEDB_TEST_REDIS_URI` değerlerini
özel ortamdan sağlayın. İki URL de `.<namespace>.svc.cluster.local` içermelidir.
Test kendi run/admin/şifreleme değerlerini üretir, yazdırmaz.
Bu örnek proje dizininde:
```sh
mvn -o -Dmaven.repo.local="$SDK_REPO" \
  -Ptenant-purge-demo,provider-integration \
  -Dtest=TenantPurgeOptInTest,TenantPurgeContractTest -Dit.test=TenantPurgeExternalIT \
  -Dsurefire.failIfNoSpecifiedTests=true -Dfailsafe.failIfNoSpecifiedTests=true \
  -DfailIfNoTests=true -DforkCount=1 -DreuseForks=true \
  '-DargLine=-Xmx512m -XX:ActiveProcessorCount=1' verify
```
Yedi `TenantPurgeOptInTest` metodu, on `TenantPurgeContractTest` metodu ve
`TenantPurgeExternalIT.nativeInitialHttpPurgeRemovesHotAndColdAndPreservesForeignState`
sıfır hata/başarısızlık/skip ile çalışmalıdır: sağlayıcı başına 18, toplam 54.
XML, Maven çıkış kodları ve güncel kaynak/SDK hash'lerini saklayın.
Eksik fixture veya atlanan test PASS değildir.

**Ayrı HTTP/Postman çalıştırması:** yeni şema ve özel ortam değişkenleri gerekir:
Postman betiği ayrıca Linux ve tam olarak
`CACHEDB_TEST_NAMESPACE=cachedb-v019-validation` ister. `CACHEDB_TEST_DATASOURCE_URL`,
uygulamanın kullandığı gerçek özel SQL URL'si (`SAMPLE_PURGE_SQL_URL`) olmalı ve
`jdbc:postgresql://postgres.cachedb-v019-validation.svc.cluster.local:5432/` ile başlamalıdır.
`CACHEDB_TEST_REDIS_URI` ve `SAMPLE_PURGE_REDIS_URI` değerleri
`redis://redis.cachedb-v019-validation.svc.cluster.local:6379/0` olmalıdır.
Bunlar betiğin zorunlu girdileridir; HTTP `BaseUrl` adresi yine loopback olabilir.
Uygulamanın özel girdilerini sağlayın:
`SAMPLE_PURGE_SQL_URL`, `SAMPLE_PURGE_SQL_USERNAME`, `SAMPLE_PURGE_SQL_PASSWORD`,
`SAMPLE_PURGE_REDIS_URI` (yukarıdaki Service URI'si, kimlik bilgisi olmadan),
`SAMPLE_PURGE_RUN_ID` (32 küçük hex), `SAMPLE_PURGE_ADMIN_TOKEN`
(32..128 URL-safe karakter), `SAMPLE_PURGE_CONFIRM=CREATE_FRESH_PURGE_DEMO`,
`SAMPLE_ENCRYPTION_AES_KEY` ve `SAMPLE_ENCRYPTION_HMAC_KEY` (özel, Base64
32 bayt anahtarlar). İsteğe bağlı `SAMPLE_PURGE_BIND_ADDRESS` varsayılanı
loopback, `SAMPLE_PURGE_HTTP_PORT` varsayılanı 8080'dir.
Sırları komut satırına veya loglara koymayın.

**Parolalı Redis bağlantısı:** test adresinin içine kullanıcı adı veya parola
yazmayın. TEST sürecine `CACHEDB_TEST_REDIS_PASSWORD`, purge uygulamasına ise
`SAMPLE_PURGE_REDIS_PASSWORD` değerini Kubernetes Secret üzerinden ortam
değişkeni olarak verin; komut satırı argümanı kullanmayın. Her iki bağlantı da
Redis'in varsayılan ACL kullanıcısını kullanır. `ExternalSampleFixtures` ve
`TenantPurgeRuntime` parolayı istemcilere iletir; özel karakterler yalnızca
istemci bir URI gerektirdiğinde kodlanır. Normal uygulamada bağlantı ayarı yine
`REDIS_URI` üzerinden yapılır. İsteğe bağlı parola değişkenleri verilmezse
bağımsız, parolasız demo kullanımı korunur. Örneği çalıştırmak için paylaşılan
veya üretimdeki Redis'in kimlik doğrulamasını kapatmayın.

```sh
mvn -o -Dmaven.repo.local="$SDK_REPO" -Ptenant-purge-demo -DskipTests package
java -XX:ActiveProcessorCount=1 -Xmx512m -jar target/sample-cache-database-postgresql-0.19.0.jar --spring.config.name=application-purge
# Aynı kilitli K8s kohortunda ikinci kabuk; onaylı offline Newman 6.2.1 önbelleği gerekir:
pwsh -NoProfile -File scripts/test-tenant-purge-postman.ps1 -BaseUrl http://127.0.0.1:8080 -RunId "$SAMPLE_PURGE_RUN_ID" -ConfirmDisposableDemo
```
Sabit API kökü `/api/examples/v019/tenant-purge`: GET `/status`,
GET `/rows/1`.. `/rows/7`, POST `/purge`.
`Authorization: Bearer <özel-token>` ve `X-Purge-Run: <run>` zorunludur.
Tek mutasyon gövdesi:
`{"confirmation":"ERASE_OWNED_ROWS","operationId":"<canonical-UUID>"}`.
Wrapper tek operasyon kimliği üretir; sekiz istek/36 assertion ve tek purge POST
çalıştırır. Redirect, retry veya polling yoktur. Özel environment dosyasını siler;
yalnızca sırları ayıklanmış log/JUnit çıktısını saklayın.
`POSTMAN_ONLY_PASS`, örneğin bütünü için yeterlilik değildir.

Gerçek yabancı şifreli SQL satırı ve kayıtlı namespace dışındaki gerçek şifreli
Redis sentinel korunarak doğrulanır. Sentinel, ayrı enrollment yapılmış yabancı
runtime değildir. Crash, HA, yedek silme veya Cluster kanıtı iddia edilmez.
Eşlikçi manifest `tools/samples/v019-tenant-purge-scenarios.json` dosyasıdır.
Bu kapsamı ve mevcut 20 senaryolu ana matrisi, sürüm commit'inin CI sonuçları ve
eşleşen pakete bağlı kanıtla bağımsız değerlendirin; özellik kanıtı tüm ana matrisi veya sürümü doğrulamaz.
