# Sınırlı Saklama Süresi

`RetentionSweeper`, kayıtlı saklama hedeflerinde sonlu yönetim turları yürütür.
Yerel Redis uygulaması olan `RedisRetentionAdapter`, sıralı ZSET penceresi başına en çok 128 aday
seçer; çağrı başına en çok 10.000 adayı inceler. Zamanlayıcı, iş parçacığı havuzu veya SQL bağlantısı oluşturmaz.

## Kapsam ve İşlemler

**ZSET taraması yalnız indekslenmiş önbellek yerleşik kümesini ve eski indeks üyeliklerini kapsar.
SQL'deki bütün soğuk geçmişi keşfetmez veya saklama süresini bu geçmişin tamamında uygulamaz.**
Önbelleğe hiç alınmamış yazılar, önbellekten çıkarılmış kaynak satırları ve indekslenmemiş kayıtlar
için ayrı, indeksli bir kaynak saklama süreci gerekir. Bu API, kiracı temizliği veya SQL'deki
bütün verilerin silindiğine ilişkin bir belge değildir.

`EVICT_CACHE`; önbellek verisini, exact/sort/prefix/token/composite/partition indeks üyeliklerini,
indeks üst verisini, sıcak küme üyeliğini ve ilgili kiracının kabul sayacını temizler.
SQL, kalıcı kiracı sahipliği, sürümler, silme işaretleri, kalıcılık işaretleri ve kuyruktaki
yazılar korunur. Okuma kabulüne aynı saklama kuralı uygulanmazsa kaynak satırı yeniden önbelleğe alınabilir.

`DELETE`, normal Redis yazıcısıyla aynı açık canonical Lua DELETE kaynağını kullanır.
Sürüm, silme işareti, stream/compaction, kiracı denetimi ve yapılandırılmış iz bağlamı korunur.
`deleteAccepted`, Redis kabul sayısıdır; SQL işleminin commit edilmesi veya her yazının ayrı ayrı
kalıcılaşması değildir. Mevcut son-durum kalıcılık sözleşmesi bu turla güçlendirilmez.

## Yapılandırma

```java
@CacheRetention(field = "createdAt", days = 90, action = RetentionAction.EVICT_CACHE)
```

Üretilmiş `EntityMetadata.retentionPolicy()` tanımlayıcısı gereklidir. Anotasyon yalnız SOURCE
düzeyindedir; çalışma anında yansımayla aranmaz. Alan kalıcı ve indeksli olmalı, aralık indeksi
açık bulunmalıdır. Gün sayısı 1..36.500 arasındadır. `Instant`, `LocalDateTime` ve
`OffsetDateTime` epoch milisaniyesi; `LocalDate` UTC epoch günü kullanır.

Adaptörü gerçek üst veri, veri codec'i, tür güvenli kimlik çözücü, `CachePolicy`, anahtar stratejisi,
indeks/function/guardrail/write-behind ayarları, yapılandırılmış `RedisFunctionArgsMapper`,
yazıcı koruması, isteğe bağlı gerçek yerel L1 deposu, saat ve açık ayarlarla oluşturun.
Redis istemcisi ödünç alınır; sonlu havuz/soket/komut zaman aşımlarını ve kapanışı sahibi yönetir.

Normal yazıların kullandığı aynı mapper üzerinde
`withNearInvalidationPublisher(coordinator::publishLegacyInvalidations)` yapılandırılır.
İki işlem de argümanlar oluşturulurken bu güncel kararı kullanır. Eski yayınlama varsayılan açıktır.
Yayınlamanın kapatılması, doğrulanmış SERVER_TRACKING seçimini ve eksiksiz, sağlıklı takibi gerektirir.
Takip bozulursa L1 kabulü kapalı kalırken uyumluluk yayını yeniden açılır.
Yayın kapatıldığında protokoldeki boş yerler korunur; kiracı ve beklenen sürüm konumları kaymaz.
DELETE, paylaşılan yazıcı içinde yalnız bir kez yayınlar. EVICT_CACHE aynı yapılandırılmış
kanal ve ileti kararını kullanır.

Gerçek yerel L1 deposu, değişiklik I/O'sundan önce ve I/O çıktıktan sonra finally bloğunda,
sonucun belirsiz kaldığı durumlarda da geçersizleştirilir. Böylece kimlik/segment damgaları ve
uyumluluk için tutulan genel epoch ilerler; eski okumaların geç doldurmaları reddedilir.
Uzak geçersizleştirme eşzamansızdır.

## Eşzamanlılık ve Sınırlar

Verinin yaşı çözümlendikten sonra denetlenir. Verinin tamamı, sıralama puanı, sürüm, sahiplik,
indeks görüntüsü, kabul kilidi ve dağıtık kilit sahipliği silmeden önce atomik doğrulanır.
`generationGuarded=true` olduğunda OPEN nesli aynı görüntüde yakalanır ve değişiklik sırasında
aynı kalmalıdır. Görüntüyle değişiklik arasında kapatılıp yeniden açılan neslin satırı atlanır.
Nesil damgalı DELETE, ayrı protokol alanları kullanır; iz üst verisine veya iş kolonlarına gizlenmez.
Nesil güvenliği, bütün yazıcıların, yeniden oynatmanın ve işlemsel SQL yazıcısının aynı protokolü
kullanmasını gerektirir. Kiracı temizliği ve yeniden açma boyunca receipt/nedensel sürümlerin
monoton kalması ayrı bir koşuldur; sürüm anahtarı kaldırıldıktan sonra kimlik başına INCR bunu sağlamaz.

Eksik önbellek verisi DELETE yetkisi vermez. SQL sorgusu veya ön planda SQL rezervasyonu yapılmaz.
Bilinmeyen düzenler güvenli biçimde reddedilir. Projection bağlamaları desteklenmez.
Yerel DELETE, canonical function şablonunu gerektirir. Özel kaynak veya şablonlar kod metni
ayrıştırılarak ya da sessizce başka bir yazı protokolüne çevrilerek kullanılmaz; reddedilir.

Sınırlar: 4096 komut anahtarı, 16 KiB..8 MiB komut bütçesi, 15..120 saniyelik yenilenebilir
dağıtık kilit, 128 compaction parçası, 1024 bayt kimlik, 256 KiB veri, en çok 160 üst veri alanı
ve alan başına 4096 bayt. Toplam veri/üst veri görüntüsü bütçesi 4 MiB'dir.
Aşırı büyük girdiler reddedilir. Her tur ayrıca 256 pencere ve 30 saniyelik yeni iş kabulü bütçesiyle
sınırlıdır; sonlu istemci I/O zaman aşımları yine gereklidir. Küçük komut bütçesi, kimlikleri kaybetmeden turu erken bitirebilir.

`examined`, `indexCleaned`, `evicted`, `deleteAccepted` ve `skipped` farklı sayımlardır.
`exhausted`, mevcut indeks penceresini anlatır; eşzamanlı bir görüntü veya bütün geçmişin kapsamı değildir.
Hata veya zaman aşımında kabul sonucu belirsiz kalabilir; Redis betik hatası geri alma değildir.
Yazıları körlemesine yeniden denemeyin; iptali rollback saymayın.

`RetentionJobAdapter` isteğe bağlıdır ve varsayılan kapalıdır. Mevcut dağıtık iş yürütücüsü ve açık,
sınırlı argüman codec'i üzerinden tur başına en çok 16 iş gönderir; zamanlayıcı oluşturmaz.
Dağıtık kilitler çakışan turları engeller. Kiracı temizliğini veya yeniden açmayı zamanlamaz.
Deneme sayısını ve beklemeyi sınırlayın; metriklerde kiracı/kimlik/veri etiketi kullanmayın.

## Tenant Silme İşleminden Ayrımı

Retention DELETE, normal korumalı SQL write-behind işlemini bilerek kuyruğa alır.
Yalnız Redis tenant silme işlemi bununla uygulanamaz: `PurgeOptions.includeSource=false`,
SQL DELETE kuyruğunu yasaklar ve kaynak satırlarını korur. Tenant silme, kalıcı kabulü kapatır ve
ayrı Redis temizliği kullanır; isteğe bağlı kaynak silme açık, sınırlı JDBC DELETE ile yapılır.

Kuşak korumalı DELETE için `JdbcTenantVersionAllocator` tarafından kalıcı ayrılmış ve
`RedisTenantVersionAllocator` ile kurulmuş doğrulanmış çift-başı sürüm aralığı da gerekir.
Açık ortak Lua kaynağı kullanılır; kimlik-başı INCR yoluna dönüş yoktur. Herhangi bir silmeden
önce bütün grubun kalan aralığa sığması denetlenir. Tükenme kabulü reddeder. Purge/yeniden açma
sonrası receipt/causal artışını doğrulamak için bütün üretici/replay yolları aynı tahsisçiyi kullanmalıdır.
