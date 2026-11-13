# Redis'te Sınırlı Yeniden Şifreleme

AES anahtarını döndürmek, önceden saklanmış değerleri yeniden yazmaz.
`RedisReencryptionRunner<T, ID>`, Redis varlık yükleri üzerinde açık ve kaldığı yerden
sürdürülebilen bir bakım geçişi uygular. Okumalar, bağlı codec'in bildiği eski anahtarları;
yeni değerler ise çalıştırıcının sabitlediği güncel anahtarları kullanır. Kör eşitlik
indekslerinin değişmemesi için bağımsız HMAC anahtarını koruyun.

## Kapsam

Desteklenen kapsam `REDIS_RESIDENT` değeridir: kayıtlı tek bir ad alanında, açıkça
yetkilendirilmiş tek bir kiracının Redis varlık yükleri. Varlıkta şifreli sütunlar,
indeksli bir kiracı alanı ve `tenantPurgeEnabled=true` bulunmalıdır. Gerçek çalışma
ortamına kayıtlı `RedisTenantReadFence` ile nesil ve sürüm ayırıcıları hazır olmalıdır.
Bu yetenekler yoksa işlem güvenli biçimde reddedilir; bağımsız bir okuma çiti oluşturmak
çalışma ortamına bağlanmanın yerine geçmez.

İşlem kaynak yükleyicisi çağırmaz, SQL çalıştırmaz, iş sürümünü artırmaz ve yazma kuyruğuna
iş eklemez. Akış, sıkıştırma kuyruğu, ölü mektup kuyruğu, L1, izdüşüm ve geçmiş yükler bu
kapsama girmez. Özellikle `REDIS_AND_SOURCE` kapsamındaki SQL şifreli metinleri eski
anahtarlarını kullanmaya devam eder. `Scope.SOURCE`, bakım G/Ç'sinden önce reddedilir.
Kaynağın tümünü yeniden şifrelemek için ayrı ve doğrulanmış bir kaynak geçişi gerekir;
kaydetme döngüsü veya gizli SQL tetikleyicisi kullanılmaz. Kiracı ve nesil koruması
olmayan varlıklar bu çalıştırıcı tarafından desteklenmez.

SCAN geçişi anlık görüntü değildir: eşzamanlı eklenen veya çıkarılan kayıtlar kaçabilir,
aynı anahtar birden fazla kez görülebilir. Sayaçlar benzersiz varlık sayısını değil,
incelenen karşılaşmaları gösterir. `COMPLETE`, yalnızca bu geçişte karşılaşılan sahipli
yerleşik yüklerle ilgilidir; eski anahtarı kaldırma izni değildir. Diğer saklama
alanları ve kaynak satırları kendi doğrulanmış kurallarıyla dönüştürülmeden veya süreleri
dolmadan eski anahtarları kaldırmayın.

## Kullanım

Her kayıtlı varlık ve çalışma ortamı için bir çalıştırıcı oluşturun. Gerçek çalışma
ortamının codec'ini, anahtar sağlayıcısını, Redis işlemlerini, anahtar stratejisini,
nesil bariyerini, kayıtlı okuma çitini ve kimliği doğrulanmış sınırlı taşımasını kullanın.
Statik sağlayıcı veya thread-local codec bağı kullanılmaz.

```java
var runner = new RedisReencryptionRunner<>(
    metadata, runtimeCodec, keyProvider, redisOperations, keyStrategy,
    generationBarrier, enrolledReadFence, boundedRedisWire);

try (var admin = CacheTenantContext.administrative()) {
    var step = runner.reencrypt(Customer.class, "tenant-a", 250);
    // MORE sonucunda bir sonraki sınırlı işçi çağrısını planlayın.
}
```

Kiracı bağlamındaki çağrılar, `CacheTenantContext.withTenant(...)` içinde
`reencrypt(Customer.class, maxRows)` kullanabilir. Açık kiracı, yönetici yetkisi yoksa
bu bağlamla eşleşmelidir. Yönetici kapsamını istek verisinden türetmeyin.
Bakımı ön plan istekleri veya olay döngüsü üzerinde değil, sınırlı bir işçide çalıştırın.

Bir çağrı en fazla 10.000 karşılaşma, 32 yeni tarama çerçevesi ve yaklaşık beş saniyelik
planlama çalışması içerir; devam eden süreli soket veya codec işlemi buna eklenebilir.
Çalıştırıcı beklemeden tek çağrı kabul eder. Redis ilerleme kaydı, diğer çalışma
ortamlarını dışlayan 30 saniyelik bir kiralamaya sahiptir. Zaman aşımı veya bilinmeyen
sonuç sonrasında körlemesine yeniden kaydetmek yerine ilerlemeyi sürdürün.

## Güvenlik Ve İlerleme

Asıl nesil G/Ç'den önce alınır. Tek ve aynı Redis yuvasındaki Lua komutu; tam OPEN
neslini, sürüm ayırıcısının Redis çalışma kimliğini, ham kiracı sahipliğini, okunan
sürümü, eski yükü, kota sahiplik kaydını ve mezar taşı bulunmadığını denetler.
`SET XX KEEPTTL` kalan yaşam süresini korur. Yük değişikliği ile satır ilerlemesi
birlikte yazılır; yeniden başlatma Java nesnesine veya çağıranın tuttuğu imlece dayanmaz.

İşlemden önce sınırlı SCAN çerçevesinin tamamı ve çerçeve içi konum Redis hash'ine
kaydedilir. Burada mevcut anahtar adları, yapılandırma parmak izleri ve sayaçlar bulunur;
çözülmüş iş alanı değerleri bulunmaz. Varlık anahtar alanıyla aynı erişim denetimini
uygulayın. Kullanılmayan ilerleme kaydı yedi gün sonra silinir; bu durum tamamlanmış
sayılmak yerine taramanın yeniden başlamasına yol açar.

Redis geri dönüşü, yeniden başlatılması veya topoloji değişikliği eski imleci reddeder.
Nesil, eşleme ya da güncel anahtar değiştiğinde, önceki kiralama bırakıldıktan veya
süresi dolduktan sonra açıkça `startNewPass(entityClass, tenant)` çağırın.
Eski ilerleme kaydını yeni nesle taşımayın.

Taşıma; çalışma ortamıyla aynı birincil sunucuya, TLS ve kimlik doğrulama ayarlarına,
Redis veritabanına bağlanmalıdır. Redis Cluster, mevcut açık hash etiketiyle aynı
yuvaya yerleştirilmiş düzeni gerektirir. MOVED/ASK, bozuk yanıt, bilinmeyen şema ve bütçeyi
aşan çerçeve güvenli biçimde reddedilir. Tahminî yönlendirme veya kesilmiş SCAN ilerlemesi
yoktur. Büyük yanıt aynı imleçte daha düşük COUNT ile yeniden denenir; COUNT=1 de bütçeyi
aşıyorsa işletim müdahalesi gerekir.

## Sınırlar Ve Eksikler

Kodlanmış yerleşik yük sınırı satır başına 256 KiB'dir. Bozuk zarf, bilinmeyen eski
anahtar, kimlik veya kiracı uyuşmazlığı, eksik sürüm ve gidiş-dönüşü korumayan codec
satırı değiştirmez; `blocked` sayacını artırır. Eşzamanlı CAS çakışmaları `conflicts`
sayacını artırır. Bu durumlar tam dönüşüm yerine `COMPLETE_WITH_GAPS` üretir.
Başka kiracının yükü çözülmez ve değiştirilmez. Silinmiş veya bulunmayan yükler ayrıca sayılır.

Kota sahipliği, mevcut sayfa yöneticisinin katı okuyucusuyla doğrulanır. Geçiş kota
rezervasyonunu veya kabul kilidini değiştirmez. Kota kaydı olan satır yalnızca kodlanmış
bayt uzunluğu büyümüyorsa değiştirilebilir; mevcut korumacı ücret korunur. Daha büyük
yükler engellenmiş satır olarak ertelenir. Yeniden denemeden önce rezervasyonu olağan,
doğrulanmış kabul protokolüyle değiştirin; kotayı atlamayın.

Serileştirmeyi bağlı codec yürütür. Mantıksal değerlerin ve indeks değerlerinin
gidiş-dönüşü denetlenir; indeksler açık SQL değerlerinden yeniden üretilmez. Sıkıştırma,
özel alan codec'leri ve NULL davranışı kayıtlı codec'in sözleşmesini izler.
Şifreli alandaki eski açık metin bir geçiş yedeği değildir.

Bakım izlemeye yalnızca durum ve sayaçları aktarın; kiracı kimliği, varlık kimliği,
yük veya sağlayıcı hata nedeni eklemeyin. Eksikler, imleç uyuşmazlığı, yinelenen zaman
aşımları ve kiralama çekişmesi için uyarı üretin. Geçici hatalarda sınırlı geri çekilme
uygulayın; şema, sağlayıcı veya nesil hatasında ilerlemeyi otomatik sıfırlamayın.

## Temizleme Deposu Kaydı

İlerleme hash'ini açık sahipli bir bakım deposu olarak kaydedin. Tam anahtar
`runner.cursorKey(ProtectionKey.of(namespace, tenant))` ile alınır. Onaylı genel okuma ve
üretici kapanışından sonra temizleme koordinatörü
`discardCursorAfterClose(lease, closedGeneration)` çağırabilir. `DRAINING` uzak çalışma
ortamının onayı değildir; `INCOMPLETE` silme izni değildir. Kanca, asıl kapalı nesli,
Redis çalışma kimliğini, temizleme kiralamasını ve yerel fiziksel işlerin bitmesini denetler;
yalnızca ilgili kiracının tanınan ilerleme şemasını siler. Yinelenen temizlik güvenlidir.
Bilinmeyen depo veya şema, gerçek envanter kapsamına alınana kadar eksik kalmalıdır.
Geniş bir tarayıcı istisnasıyla anahtarı gizlemeyin; bu kancayı kalıcı sayaç günlüğü saymayın.
