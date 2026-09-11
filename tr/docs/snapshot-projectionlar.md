# Tanımla Çalışan Katalog Yenileme

SnapshotPlan, çok sayıda tablodan hazırlanan REST cevapları içindir.
Uygulama kaynakları, iş kuralını ve ayarları tanımlar.
JDBC döngüsü, geçici dosya, partileme, kilit ve yayın CacheDB'ye aittir.

SnapshotPlan 0.11.0 ile gelir. Kısa kaynak tanımları için 0.12.0 gerekir.
PostgreSQL, SQL Server ve Oracle desteklenir.
H2 yalnızca yalıtılmış testlerde kullanılır. Tanınmayan veritabanında yenileme başlamaz.

## Veritabanı Koşulları

| Provider | Yenilemenin okuma biçimi | Gereken koşul |
|---|---|---|
| PostgreSQL | Salt okunur REPEATABLE READ | Kaynaklarda SELECT yetkisi |
| SQL Server | SNAPSHOT isolation | DBA kaynak veritabanında ALLOW_SNAPSHOT_ISOLATION açar |
| Oracle | SET TRANSACTION READ ONLY | SELECT yetkisi ve yeterli undo saklama süresi |

CacheDB veritabanı ayarını değiştirmez ve yetki vermez. SQL Server'da yalnızca
READ_COMMITTED_SNAPSHOT açık olması yeterli değildir. Koşul sağlanmazsa önceki katalog
korunur ve yenileme hata verir. İş bitince rollback yapılır; bağlantının isolation,
readOnly ve autoCommit ayarları eski değerlerine döndürülür.
Ayrı bir SELECT hesabı kullanın. SQL Server'da JDBC readOnly bir yetki sınırı değildir.
SQL koşullarını provider'a uygun yazın. Entity kaynağı Oracle nesne adlarını büyük harfe
çevirir, codec alan adlarını alias ile korur. Oracle'da bilerek tırnakla oluşturulmuş
büyük/küçük harfe duyarlı nesneler için açık SnapshotSource SELECT tanımlayın. LOB ve yapılandırılmış değerler reddedilir;
gerekiyorsa sınırlı bir scalar değere açıkça dönüştürün veya ayrı SQL yolu kullanın.

## Uygulamada Ne Kalır?

| Tanım | Sorumluluğu |
|---|---|
| Entity ve generated binding | Gerçek kolon eşlemesi |
| SnapshotSource | Hangi kayıtlar seçilecek? |
| SnapshotPlan | Hangi cevap üretilecek? |
| properties | Zamanlama, süre ve kaynak bütçesi |
| REST sınırı | Yetki, veri yaşı ve HTTP sonucu |

SnapshotSource.entity, generated binding kullanır.
Tekil kimliği olmayan iki kolonlu ilişki için SnapshotRelation kullanın; yapay entity kimliği üretmeyin.
Ek alanlar için @CacheSourceRecord ile record tanımlayın.
[Kaynak ve ilişki başvurusu](snapshot-kaynak-basvurusu.md), alanları ve yanlış seçimlerin sonuçlarını açıklar.
Tablo ve kolon adlarını HTTP isteğinden almayın. Filtre değerleri SQL'e parametre olarak bağlanır.
Yeni SOURCE alanı için entity'leri yeniden derleyin. Eski metadata/codec ve açık SELECT API'leri korunur.
Mapping içinde JDBC, Redis veya dış servis çağrısı yapmayın.

## Planı ve Ayarı Tanımlayın

```java
@Bean
SnapshotPlan<BranchEntity, CampaignDto> campaignPlan() {
    return new SnapshotPlan<>("campaigns", branches, branch -> branch.id,
            sources, CampaignDto.class, rows -> {
                var rules = prepareBusinessRules(rows);
                return branch -> rules.campaignsFor(branch.id);
            });
}
```

Örnekteki entity, sources ve iş kuralı metotları uygulamanıza aittir.
Spring bean'i bulur ve ilk işi başlatır. Özel loader veya warmer yazmanız gerekmez.

```properties
cachedb.snapshots.jobs.campaigns.enabled=true
cachedb.snapshots.jobs.campaigns.interval=PT1M
cachedb.snapshots.jobs.campaigns.warn-age=PT3M
cachedb.snapshots.jobs.campaigns.max-age=PT30M
cachedb.snapshots.jobs.campaigns.retention=PT1H
cachedb.snapshots.jobs.campaigns.batch-rows=256
cachedb.snapshots.jobs.campaigns.batch-target-size=4MB
```

Yanlış ayar adı veya tanımsız plan ayarı başlangıçta hata verir.
enabled=false takvimi kapatır; yetkili manuel işi kapatmaz.
Bu cron garantisi değildir. İş süresi ve pod takvimi fiilî aralığı etkiler.

## Okuma ve Yenileme

```java
var repository = jobs.repository("campaigns");
var snapshot = repository.findById(branchId);
var result = jobs.refresh("campaigns", true);
```

jobs, Spring'den alınan SnapshotOperations arayüzüdür.
Her kök için JSON dizisi tutulur. Eksik kök ile hazırlanmış boş dizi farklıdır.
Repository SQL'e gitmez ve warm başlatmaz.
HTTP yetkisini ve SnapshotValue.refreshedAt üzerinden yaş kontrolünü uygulama yapar.
warn-age ve max-age ortak ayarlardır; repository kendiliğinden HTTP hatası üretmez.

İkinci yenileme BUSY döner; kuyruk biriktirilmez.
Yakın zamanda tamamlanan periyodik iş için diğer pod NOT_DUE alır.
Manuel ucu kimlik doğrulamasız açmayın. Executor ve timeout sınırlı olsun.

Bu, genel ProjectionRepository.query değildir. Kimlikle tam cevap okuma modelidir.
plan.map(...) iş kuralını başka bir özet planında kullanır.
Ayrı planlar ayrı SQL snapshot'ı okur; birlikte atomik yayımlandıkları varsayılmaz.

Yalnızca okuma yapan uygulamada SnapshotReadOnlyProfile.configure(builder, keyPrefix) kullanılabilir.
Profil SQL yazmayı, şema değişimini ve istek sırasında SQL'den tamamlamayı kapatır.
Entity'ler yalnızca kaynak metadata'sıysa otomatik registration'ı kapatın.
CacheDB yazma işlevi gereken uygulamaya bu profili uygulamayın.

## Güvenli Yayın

1. Süreli Redis kilidi alınır ve düzenli uzatılır.
2. Kaynaklar provider'a uygun tek transaction içinde tutarlı biçimde okunur.
3. Cevaplar tek geçici disk dosyasında hazırlanır.
4. Yeni generation'a partilerle yazılır.
5. Sahiplik ve kök sayısı doğrulanır; aktif işaretçi atomik değiştirilir.
6. Önceki generation ve dosya temizlenir.

Her parti ve son yayın Lua içinde sahipliği denetler.
Kilidi kaybeden eski pod yeni kataloğu ezemez.
Hazırlık hatası önceki kataloğu değiştirmez.
Silinen kök sonraki başarılı katalogda bulunmaz.
Commit cevabı kaybolursa sonuç belirsizdir; temizlik aktif kataloğu silmez.

Bu güvence aynı yetkili Redis primary içindir.
Asenkron Redis failover veri kaybını veya split-brain durumunu ortadan kaldırmaz.
Key'ler ortak hash tag kullanır. Spring istemcisi JedisPooled'dur, Redis Cluster istemcisi değildir.

## Kapasite ve İşletim

| Ayar | Varsayılan |
|---|---|
| preparation-timeout / timeout | PT90S / PT110S |
| lease-duration | PT2M; sürenin üçte birinde uzatma |
| batch-rows / fetch-rows | 256 / 256 |
| batch-target-size | 4MB |
| max-source-rows | 300000 |
| max-rows-per-source | 100000 |
| max-source-size | 64MB |
| payload-warning-size / catalog-warning-size | 1MB / 64MB |
| spool-directory | JVM geçici dizini |

Tüm ayarlar cachedb.snapshots.jobs.<plan> altındadır.
withoutPerSourceLimit yalnızca kaynak başına sınırı kapatır. Toplam bütçe devam eder.
Aşımda hazırlık hata verir; ilk N kayıtla devam edilmez.
Parti hedefinden büyük tek cevap yalnız yayımlanır, kesilmez.

SELECT başına timeout en fazla 30 saniyedir.
Havuz, bağlantı ve socket timeout'larını da sınırlayın.
Hazırlık süresi iş timeout'undan küçük olmalıdır.
interval + timeout < max-age; retention > max-age + timeout olmalıdır.
Okuma ana Redis havuzunu, yenileme arka plan havuzunu kullanır.

Kaynak kayıtlar ve ilişki indeksleri heap'tedir. JSON dosyada hazırlanır.
Bir yayın partisi ve tek büyük cevap yine bellek kullanır.
Redis aynı anda eski ve yeni kataloğu tutar. İki katalog, ek yük ve diğer verileri ölçerek bütçe ayırın.
Katalog bütünlüğü için ayrılmış alan ve noeviction politikasını değerlendirin.

Dosyayı private, disk tabanlı emptyDir altında tutun. ephemeral-storage limiti verin.
Dosya açık JSON içerir; dışarıya sunmayın.
Loglarda job, rows, bytes ve durationMs bulunur. Gerçek Redis belleğini ayrıca ölçün.

## Test ve Geçiş

[İngilizce teknik başvuru](../../docs/snapshot-projections.md) test komutunu ve ayrıntılı sözleşmeyi içerir.
Framework testleri izole Redis önekleri ve H2 kullanır.
Gerçek Provider entegrasyon testleri üç veritabanında tutarlılık, yayın, silme, hata sonrası koruma ve havuz ayarlarının geri yüklenmesini doğrular.

Eski projection düzeninden geçerken yeni Redis öneki kullanın.
Yeni kataloğu hazırlayın, cevapları karşılaştırın, sonra trafiği taşıyın.
Geri dönüş için eski JAR ve öneki saklayın. Ortak Redis üzerinde FLUSHDB çalıştırmayın.
