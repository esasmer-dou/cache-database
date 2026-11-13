# Redis Aggregate Ve Facet İşlemleri

## Uygunluk Ve Tenant Yalıtımı

aggregate(AggregateSpec) ve aggregateBy(group, AggregateSpec), SQL geçmişini değil Redis'teki sınırlı aktif kümeyi işler.
Tek çağrılı indeks yolu; adayların bütünüyle indekslerden çözümlenmesini, değer/grup kolonlarının indeksli olmasını,
COUNT_WINDOW politikasını ve belirsiz `__NULL__` literalini içermeyen filtreleri gerektirir. Diğer sorgularda mevcut payload/pipeline yolu korunur.

Tenant entity'lerinde yönetim kapsamı dışındaki okumalar açık tenant bağlamı gerektirir. Tenant indeksinden aday bulmak tek başına
yetkilendirme değildir: seçilen native transaction veya Lua script'i, entity'nin varlığını, tombstone bulunmamasını ve `<versionKey>:tenant` değerinin etkin tenant ile
eşitliğini aynı atomik çalışmada denetler. Sahiplik kaydı eksik veya başka tenant'a aitse aday, metadata okunmadan elenir.
Yönetim/global okuma owner filtresi eklemez. Okuma işlemi sahiplik kaydı oluşturmaz veya onarmaz.

Standalone Redis'te, 512'den fazla ve en çok 10.000 adayı olan uygun kiracılı COUNT(*) işlemleri,
sınırlı native transaction'ları ayrılmış tek bağlantı üzerinden toplu gönderebilir. Her pencere en çok
512 kayıt ve 516 komut içerir. Kayıt varlığı, silinme işareti ve sınırlandırılmış sahiplik okuması aynı
pencere içinde atomik olarak denetlenir; işlemin bütünü tek bir anın görüntüsünü garanti etmez.
Bağlantı alınmadan önce istek baytları ile çerçeve maliyeti dahil temkinli hesaplanan beklenen yanıtların
toplamı 16 MiB sınırını aşmamalıdır. Küçük son parçalara ait uyumluluk kuralları, daha büyük işlemler,
desteklenmeyen native yetenekler ve varlık durumu farklı kayıtlar için mevcut geri dönüş yolları korunur.
Bağlantı yeniden kullanılmadan önce yanıtların tamamı okunup doğrulanır. İptal veya protokol durumunun
belirsizliği halinde bağlantı havuza geri verilmez.

Owner dahil bütün script anahtarları aynı Cluster slotunda olmalıdır. Uygun olmayan dağılımda EVAL çalıştırılmadan
Optional.empty() döndürülür; repository sınırlı pipeline yoluna geçer. Tenant fallback'i payload'ı çözümler,
tenant kapsamındaki filtreyi ve tenant doğrulamasını uygular; yalnızca indeks üyeliğine güvenmez.
Bu, mevcut payload tabanlı yalıtımdır; farklı slotlar arasında atomik owner/payload görüntüsü vaat edilmez.
Anahtar düzeni ve hash tag'ler değişmez. Aynı slot gerekiyorsa mevcut hash-tag stratejisi kullanılmalıdır.

## API Ve Davranış

Eski aggregateIndexBatch(entities, tombstones, indexMetadata, field, countOnly) imzası korunur.
Yeni overload, field, countOnly parametrelerinden önce ownerKeys, tenant ekler. Global okumada ikisi de null olmalıdır.
Tenant okumasında ikisi de bulunmalı; owner listesi diğer kimlik listeleriyle aynı uzunlukta olmalıdır.

facetIndexBatch(entities, tombstones, indexMetadata, ownerKeys, tenant, groupField, valueField),
ham [groupRaw, valueRaw|false] çiftlerini ve adayların özgün konumlarını döndürür.
FacetIndexBatch.rows(), değiştirilemeyen FacetIndexRow(candidate, groupRaw, valueRaw) kayıtları sunar.
Değer alanı istenmemişse Java null kullanılır. Boş metin geçerli değerdir; eksik metadata anlamına gelmez.
Gerekli hash alanı bulunmazsa indekslerin yeniden kurulmasını isteyen deterministik hata üretilir.

Bağımsız Redis'te, 512'den fazla adayı olan uygun COUNT(*) gruplamaları için isteğe bağlı
facetIndexCounts yolu, sınırlı grup/sayı eşlemeleri ve belirsiz boş değerlerin özgün konumlarını
döndürür. Repository her aday için sonuç nesnesi oluşturmak yerine grup sayılarını toplar.
Kabul edilen her kaydın sahipliği ve varlığı yine denetlenir. İşlem geneli sınırlar grup sayısına
değil, özgün kayıt sayısına uygulanır. COUNT(field), sayısal gruplamalar, özel adaptörler ve
Cluster mevcut yollarını kullanmayı sürdürür.

COUNT(*), kabul edilen canlı satırları sayar. COUNT(field), gerçek null'u saymaz; literal `__NULL__` metnini sayar.
SUM, MIN, MAX ve AVG, sayısal alanı null olan canlı satırlar dahil mevcut Java sayısal/satır sayısı davranışını korur.
Ham ondalık metin Lua number'a dönüştürülmez; sınırlı BigDecimal hesabı ve DECIMAL128 ortalaması Java'da yapılır.

`__NULL__` için yeni bir wire formatı eklenmez. Grup sentinel ise veya COUNT(field) değeri sentinel ise ilgili payload hedefli olarak yeniden okunur.
liveIndexPayloadBatch, payload okumasıyla aynı script'te live/tombstone/owner koşullarını tekrar denetler.
Repository, kapsamlı filtreyi, servis politikasını ve tenant doğrulamasını yeniden uygular.
Gerçek null gruplar FacetAccumulator tarafından atlanır; literal `__NULL__` geçerli grup olarak kalır.
COUNT dışındaki null sayısal değerler mevcut sentinel davranışını korur.
Birden çok batch içeren işlem snapshot değildir; metadata okuması ile sentinel yeniden okuması ayrı gözlemlerdir.

## Kesin Sınırlar Ve Hatalar

- Her indeks veya sentinel batch'i, birbirine hizalanmış 1..512 aday kabul eder. Fazla aday sessizce kesilmez; istek reddedilir.
- Anahtarlar en çok 16.384; alanlar ve düşük seviyeli tenant argümanı en çok 4.096 UTF-16 birimi olabilir.
- Script anahtar/argüman verisi, UTF-16 birimi başına temkinli üç bayt hesabıyla 16 MiB ile sınırlanır.
- Lua, HGET/GET öncesinde HSTRLEN/STRLEN kullanır. Metadata/payload bütçesini aşan alan, değeri alınmadan hata üretir.
- Java ayrıca UTF-16 birimi başına iki bayt hesabıyla 16 MiB içerik sınırı uygular. Bu, bütün JVM heap'i veya RESP çerçevesi için garanti değildir.
- Facet, işlem genelindeki 16 MiB hesabını; 256 grup, 4.096 birim grup anahtarı ve 100.000 aday sınırını korur. İndeks aday ayarı daha düşük olabilir.

Bütçe aşımı, eksik metadata, bozuk yanıt veya sayısal taşma durumunda kısmi sonuç döndürülmez.
Topoloji veya sorgu uygun değilse ya da native komut/sahiplik okuması için açıkça tanınan bir yetki reddi
alınırsa mevcut Lua yoluna dönülebilir. Diğer yetki, anahtar türü, depolama ve bozuk yanıt hataları
başarısızlık olarak kalır; boş ve başarılı bir sonuç gibi sunulmaz.
Salt okunur script yeni retry döngüsü, kilit, thread, executor, bağımlılık veya reflection eklemez.
Mevcut Jedis bağlantı/socket zaman aşımları ve çağıranın hata yönetimi geçerlidir. Bütçe/şema hataları tekrar denenmemelidir.
Mevcut araçlarla istek gecikmesi, Redis commandstats/EVAL süresi, hatalar ve Cluster fallback sıklığı izlenmelidir.
Çağrı eşzamanlılığı ve havuz kapasitesi sınırlanmalıdır; sınırlı Lua batch'i de Redis komut thread'ini meşgul eder.

## Nedensel Okumalar

Bu işlemler, uygulanmış varlık sürümünü doğrulamaz. İlgili etkin nedensel gereksinimler bulunduğunda
doğrulanmamış aggregate sonucu dönmek yerine CacheCausalReadUnsupportedException üretilir.
Nedensel garanti için sürümü doğrulanmış tekil/toplu okumaları kullanın; bağlam taşımak tek başına doğrulama değildir.
Kullanıma almadan önce temsilî kiracı/Cluster iş yüklerinde indeks ve yük sonuçlarını karşılaştırın; Lua maliyetini ölçün.

## Performans Doğrulaması

facet10k20Groups ve kiracılı aggregateCount10k için korunan 0.18.1 referansına göre >=2x işlem hacmi
koşulu FAIL / AÇIK durumundadır. Tanılama koşularında hedefe ulaşılamamıştır. Sonuçların eşleşmesi
bu koşulu sağlamaz; daha az ağ isteği yapılması Redis'in işlemci yükünü ortadan kaldırmaz.
Sahiplik, kayıt varlığı, silinme işareti ve bayt bütçesi denetimlerini koruyun. Kazanç iddiasından önce
[sürümlü Function geçiş sözleşmesini](redis-functions-upgrade.md) uygulayın ve tam olarak yayımlanacak
paketlerle eksiksiz, eşlenmiş karşılaştırma yapın. Bkz. [kanıt kapsamı](performans-olcumleri.md).
