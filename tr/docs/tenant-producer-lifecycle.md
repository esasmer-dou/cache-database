# Kiracı Yazma Yaşam Döngüsü

Kiracı koruması, kayıtlı tek bir varlık ad alanı ve tek bir kiracı çiftiyle sınırlıdır. Diğer varlıkları, genel Spring Cache metot sonuçlarını, yedekleri veya bildirilmemiş dış depoları silmez.

## Yapılandırma

Üretilmiş metadata üzerinde `@CacheEntity(tenantPurgeEnabled = true)`, kalıcı ve indeksli bir kiracı alanı ve kanonik Redis işlev kitaplığı kullanın. Şifreli varlıkları kaydetmeden önce şifreleme anahtarlarını yapılandırın.

```java
database.register(CustomerCacheBinding.METADATA, CustomerCacheBinding.CODEC, policy);
var producer = database.configureTenantProducer(CustomerCacheBinding.METADATA, 256);
var customers = database.repository(CustomerCacheBinding.METADATA, CustomerCacheBinding.CODEC, policy);
```

İlk repository alınmadan önce korunan bütün ad alanlarını yapılandırın. Veritabanı nesnesi producer kapanışını yönetir. Bağımsız `DefaultCacheSession` kullanımında döndürülen producer çağıran tarafından kapatılmalıdır. Her ad alanı için eşzamanlı fiziksel işlem sınırı 1 ile 4096 arasında yapılandırılır; yapılandırılan ad alanı sayısı en fazla 128'dir. Repository görünümleri aynı producer ve yerel okuma bariyerini paylaşır. Korunan bir ad alanında tek önbellek politikası kullanılır.

Yapılandırma kiracı neslini oluşturmaz veya yazmaya açmaz. Kabul için doğrulanmış geçiş, kalıcı SQL yazma bariyeri, eksiksiz sahiplik envanteri ve Redis'e kurulmuş sürüm aralığı gerekir. Bilinmeyen sahiplik `MIGRATION_REQUIRED` üretir; normal yazma işlemleri sahiplik oluşturmak için SQL sorgulamaz. Bu denetimi aşmak amacıyla sahiplik işaretlerini elle oluşturmayın.

## Yazma Ve Okuma

`save`, `saveWithReceipt`, iyimser kilitlemeli save, `saveAfter`, `saveAll`, PATCH ve tekil/toplu DELETE kabul anındaki özgün nesli taşır. Toplu çağrılar en fazla 10000 satır içerir ve en fazla 128 satırlık parçalarla hazırlanır; korunan işlev çerçeveleri için parça başına toplam 8 MiB sınırı vardır. Satırlar bağımsız kabul edilir; çağrının bütünü atomik değildir.

Redis, nesli ve sahipliği atomik olarak denetler; kabul edilen sürümü ad alanı/kiracı çiftine ait korunan aralıktan ayırır. Açık bir yeniden etkinleştirme sonrasında sürümler artmaya devam eder. İlk üst sınır 9007199254740991'dir. Aralığın tükenmesi veya tutarsız kurtarma durumu işlemi kapalı tutar. Aralık rezervasyonu yalnızca yönetim veya arka plan SQL işlemlerinde yapılır; normal yazmanın ön planında SQL yoktur. Mevcut genel receipt ve kuyruk record yapıları değişmez.

Receipt içindeki pozitif sürüm Redis kabulünü bildirir, SQL kalıcılığını değil. Mevcut son durum kalıcılık sözleşmesini gözlemek için `awaitDurable(receipt, timeout)` kullanın; bu sözleşme bütün ara yazmaların ayrı ayrı korunmasını garanti etmez.

Korunan noktasal okumalar özgün nesle bağlı biletler ve nesille çerçevelenmiş L1 kayıtları kullanır. Kaynak okumaları için mevcut bir sürümlü loader, kimlikle okuma destekleyen read-through modu ve `hydrateLoadedEntities(false)` gerekir. Sonuç Redis/L1 doldurması yapılmadan döner. Kaynaktan önbelleğe doldurma, sorgu/sayfa/aggregate/cursor yolları, kalıcı projeksiyonlar, watcher, atomik unit-of-work ve nesil damgasız dış olaylar bu bağlamada reddedilir. Korumasız eski yola sessiz geçiş yapılmaz.

## Kapatma Ve Hatalar

Her producer bileti Redis kabul I/O'sundan önce kaydedilir; eşzamanlı indeks, sıcak küme ve geçersizleştirme işleri bitene kadar açık kalır. Çağıranın future nesnesini iptal etmesi çalışan işin biletini serbest bırakmaz. Sonucu belirsiz ağ I/O'su sınırlı bir yuvayı kullanılamaz kılar ve kapanış onayını engeller. Yalnızca yeniden başlatmak, eski yazma bağlantılarının veya geçmiş depoların boşaltıldığını kanıtlamaz.

Dağıtık temizleme, gerçekten onaylanmış çalışma örneği/depo envanterini ve katılan bütün örneklerden güncel kapanış kanıtını gerektirir. Yerel sayacın sıfırlanması dağıtık kanıt değildir. `configureDataProtection`, açık planları veritabanının gerçek eşlemesi ve Redis/anahtar stratejisiyle karşılaştırır; onay oluşturmaz veya eksik kanıtın yerine geçmez. Eksik yollar, bilinmeyen eski sahiplik ve doğrulanmamış geçmiş `INCOMPLETE` olarak kalır.

Temizleme sonrasında kiracı yazmaları kapalı kalır. `includeSource=false` olduğunda kaynak satırlar korunur, SQL DELETE kuyruğa alınmaz ve açıkça doğrulanmış bir geçiş olmadan yeniden etkinleştirme engellenir. `includeSource=true` yalnızca tanımlanmış, sınırlı kiracı kaynak silmesini kapsar. Kalıcı nesil/yüksek sürüm güvenlik metadatası bilinçli olarak korunur. Mevzuat sertifikasyonu veya bütün kişisel verilerin her yerde silindiği iddiası yoktur.

İşlev kitaplığı içerik sapması koruması açık kalır. Ayrı sürümlenmiş uyumlu bir kitaplık dağıtın ve katılan bütün producer'ları eşgüdümleyin; mevcut bir sürümün üzerine farklı gövdeyi zorla yüklemeyin ve eski işleve geri dönüş kullanmayın.
