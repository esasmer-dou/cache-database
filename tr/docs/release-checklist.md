# Release Checklist

Bu kontrol listesi her public release öncesinde kullanılmalıdır.

## Repo Hijyeni

- [LICENSE](../../LICENSE) dosyasının hedeflenen public lisans seçimiyle uyumlu olduğunu doğrula
- [SECURITY.md](../../SECURITY.md), [CONTRIBUTING.md](../../CONTRIBUTING.md), [CODE_OF_CONDUCT.md](../../CODE_OF_CONDUCT.md) ve [SUPPORT.md](../../SUPPORT.md) dosyalarını gözden geçir
- issue template ve pull request template'lerinin güncel sürece uyduğunu doğrula

## Metadata

- [../../pom.xml](../../pom.xml) içindeki placeholder repo metadata alanlarını gerçek değerlerle değiştir:
  - `cachedb.release.projectUrl`
  - `cachedb.release.scmConnection`
  - `cachedb.release.scmDeveloperConnection`
  - `cachedb.release.issueUrl`
  - `cachedb.release.ciUrl`
  - maintainer identity alanları
- artifact coordinate ve public package isimlerini doğrula

## Sürümleme

- `-SNAPSHOT` sürümünü hedef release sürümüne çek
- [../../CHANGELOG.md](../../CHANGELOG.md) dosyasını güncelle
- dokümanlardaki release durumunu kontrol et: stable core, provider bazlı
  sınırlar ve beta/experimental provider ayrımları net olmalı
- release sonrasında [release-flow.md](release-flow.md) üzerinden `main` branch'ini bir sonraki `-SNAPSHOT` sürüme aç

## Kanıt

- production evidence workflow yeşil
- coordination evidence workflow yeşil
- framework GA için lokal Docker veya CI outage/restart evidence yeşil
- release iddiası managed Redis topolojisini kapsıyorsa staging Redis HA
  workflow yeşil
- tam migration route coverage, generic framework release için değil,
  production cutover yapacak uygulama için zorunlu
- runtime hotspot değişiklikleri için güncel benchmark veya smoke kanıtı var

## 0.19.0 Yayım Kanıtı

**UNRELEASED taslak: sınırlı F3 aday kabulü 10 Kasım 2026'da tamamlandı; ham T4, 9 Kasım 2026 tarihli kabul istisnasıyla PASS.**
Yalnız ham COUNT/FACET için zorunlu iki kat işlem hacmi şartı kaldırılmıştır. Geçmiş sayısal
başarısızlıklar korunur; doğruluk, yalıtım, sınırlar ve diğer performans kontrolleri zorunludur.
[Sürüm notları](releases/v0.19.0.md) ve [geçiş rehberi](upgrading-to-0.19.md), yayım duyurusu veya üretime
geçiş onayı değildir. Nihai pakete ait zorunlu kapılar kapanana kadar bu durumu koruyun.

Özeti sabitlenmiş `long-acceptance-20261110e` adayına ait kanıt, bildirilen sınırlı F3 yaşam
döngüsünü doğrular (17 test sınıfı, 188/188 test). Bildirilen dört özellik
(`spring-data-bulk-delete`, `field-encryption`, `retention`, `tenant-purge`) PostgreSQL/MSSQL/Oracle
üzerinde dokuz çalışma hücresiyle doğrulanmıştır: 27 test sınıfı, 141 Java testi, 180 HTTP isteği
ve 381 HTTP doğrulaması; hata ve atlama sıfırdır. Her sağlayıcıda `audit-derived-delete`
(retention dâhil), `encrypted-notes` ve `tenant-purge` grupları çalıştırılmıştır.
Bu kabul yalnız ilgili adaya aittir; nihai paketle birebir eşleştirme, yayım ve özel depodan kanıt
aktarımı beklemededir.

| Aday kimliği | SHA-256 |
| --- | --- |
| Dondurulmuş E kaynak bildirimi | `95bd490a15e4f17f89965667d31635c4a343a671e45ecd1b790c2766a95a6d34` |
| SDK bildirimi | `7e934d3f27a586104341765752454874a87eb4a04077a124a9acac092620eed2` |
| Bağımsız doğrulanmış örnek makbuzu | `03ff2f520571af3ef57e554a793fc589039e0c1fec37c655d5148e4096397f13` |

Native-thread tükenmesinin kök nedeni, Redis PID 1 altında zombie olarak kalan sahipsiz
BusyBox `timeout` süreçleriydi. Süreç başlatmayan TCP readiness kontrolüne geçildi; kimlik
doğrulamalı Service PING zorunluluğu korundu. NPROC 256/256 olarak kaldı. F3 ve örneklerin
35 dakika 2,749 saniyelik zinciri, üç konteynerde de sıfır zombie ve sıfır OOM/PID-limit olayıyla
tamamlandı. Bu sonuç, her süre ve yük için kesintisiz çalışma garantisi değildir.

E'nin ilk sarmalayıcı/doğrulayıcı çıkış kodu 1 olarak korunur: rapor yolu `target` dışındaydı.
Bağımsız doğrulama, testleri yeniden çalıştırmadan değişmez kanıtı kabul etti. Bu doğrulamanın
iki dosyalık kapsamı `tools/k8s/SampleRuntimeEvidence.java` ve
`tools/ci/test-v019-sample-matrix.mjs` idi; bu kapsamda ürünün çalışma zamanı ve SDK baytları
değişmedi. Daha sonraki belge, test hazırlığı ve yayım aracı değişiklikleri ayrıca incelenip
kaynak kimliğine bağlanmalıdır. Önceki iki dosyalık kabul, güncel ağacın tamamını kapsamaz.
Doğrulama `runtimeQualified=true`, `structurallyComplete=true`, `releaseAccepted=false`
sonucunu verir. Aday özetleri, güncel çalışma ağacını veya nihai sürüm commit'ini tanımlamaz.

Aşağıdaki sürüm geneli gereksinimler açıktır; adayın doğrulanması sürüm kabulünün tamamlandığını göstermez.
Yalnız aday pakete ait sayısal kanıtın kapsamı [performans ölçümlerinde](performans-olcumleri.md) açıklanır.

- [ ] Desteklenen en düşük çalışma zamanı olarak Java 21'i belirt; mevcut genel yapılandırma record'larının biçim uyumluluğunu ve yeni seçeneklerin CacheRuntimeOptions üzerinden sunulduğunu doğrula.
- [ ] Nihai SDK/araç JAR özetlerini, kaynak kodu/kaynak dosyası bildirimlerini, tam ham matrisleri ve incelenmiş CPU/bellek/JVM/Redis koşullarını tam yayım paketine bağla; eski adayın sonucunu devralma.
- [ ] Özeti alınmış nihai pakette okuma birleştirme varsayılanının false olduğunu kanıtla. Tek çalışan için FAIL_DEFAULT_ENABLED sonucunu ve ölçülmüş oranı koru; 64 çalışan ve 1000 sanal thread için açık/kapalı işlem hacmi kazancının ayrı ayrı >=2x olmasını, tam ilerlemeyi ve ham kanıtı zorunlu tut.
- [x] Ham kiracılı/kiracısız aggregateCount10k ve facet10k20Groups maddesini, >=2x işlem hacmi şartına verilen açık sürüm istisnasıyla kabul et. Başarısız ölçümleri koru; ölçülmüş iki kat hızlanma veya teknik imkânsızlık iddiasında bulunma. Nihai paket regresyonu ve diğer özellik kontrolleri bu istisnanın dışındadır. Bkz. [performans kabulü](performans-olcumleri.md#ham-t4-icin-surum-kabulu).
- [ ] İsteğe bağlı özellik matrisini tamamıyla doğrula: gerçek Micrometer NOOP ile kapalı durum arasında tekil okuma p50 <=%3, ayrı raporlanan gerçek işleyici yükü, yerel asenkron kaynak hesabı ve takip/yayın kontrolleri. Asenkron/takip hızı yalnız raporlanır.
- [ ] Yan yana Function kaydını, eski kitaplığın yeniden yüklenmesini, değişmeyen işaretçileri, içerik/sürüm düşürme reddini ve özel isim geçişini [geçiş sözleşmesine](redis-functions-upgrade.md) göre doğrula; ortak işlevleri temizleme ve korunan referans JAR'ını değiştirme.
- [ ] Zorunlu Java 21 tek sunucu/Cluster/sağlayıcı/asenkron/gözlem/koruma testlerini, atlamaları ve ölçüm desteği doğrulanmış JFR kanıtını ayrıca incele; sentetik betik testi veya sayısal başarı bu gereksinimleri kapatmaz.

- [x] E adayının sınırlı F3 kabulü: desteklenen CLOSED sahiplik hazırlığı ve INITIAL profilleri; sahiplik/sürüm envanteri, kesilen sayfadan devam ve onaylı çalışma zamanı listesinin birebir kaydı. Hazırlık, genel bir eski veri geçişine veya işlem kabulünün açılmasına yetki vermez.
- [x] E adayının sınırlı F3 kabulü: özgün kuşağı taşıyan yazıcıların, okuma/doldurma yollarının, worker'ların ve bildirilen depoların kapanışı. Eksik katılımcı, süren teslim, kayıp çalışma zamanı listesi veya doğrulanamayan depo varsa durum kapalı/eksik kalmalıdır; yapay onay veya envanter üretilemez.
- [x] E adayının sınırlı F3 kabulü: PostgreSQL/MSSQL/Oracle üzerinde INITIAL, gerçek birinci kuşak SQL flush, kaynağı da silen purge, açık onaylı EMPTY yeniden açma, yalnız bu ad alanına ayrılmış yeni kimliklerin kabulü, ayrı OPEN sürüm yenileme ve gerçek ikinci kuşak SQL flush. Saklanan birinci kuşak iletinin tekrarı SQL veya Redis verisini geri getirmemeli; hiçbir tahsis kaydı geriye alınmamalı veya tüketilmiş aralık yeniden kullanılmamalıdır.
- [x] E adayının sınırlı F3 kabulü: desteklenen açılış/sürüm yenileme kesintileri, lease devri ve sonucu belirsiz SQL/Redis işlemleri. Değişmez açılış kanıtı ile güncel aralık/sayaç denetimleri korunmalıdır; eksik veya eski yedeğe dönmüş güvenlik kayıtları otomatik benimseme ya da yeniden açma değil, açık kurtarma işlemi gerektirir.
- [ ] Bu adayın F3/örnek kabulünü ve daha sonra açıkça incelenen tüm kaynak değişikliklerini nihai dağıtımla birebir eşleştir. Eşleşen değişmez kanıtları yeniden kullan; sırf sürüm genelindeki başka işler açık diye değişmemiş sınırlı F3 kapsamını yeniden çalıştırma.

Bu F3 kontrolleri bildirilen sınırlı standalone profilleri kapsar; her türlü yedek geri yükleme,
RAC veya HA topolojisinin kabulünü zorunlu tutmaz ve kanıtlamaz. Uygulamaya özel geri yükleme
ve canlıya geçiş kabulü ayrıdır; desteklenmeyen yollar kapalı kalır ve açıkça belgelenir.
Genel geri yükleme veya ek topoloji desteği geliştirmek bu sürüm sözleşmesinin kapsamı dışındadır;
bekleyen bir F3 özelliği değildir.

- [ ] On bir özellik alanını, Spring Data bilinmeyen/sıfır/tam dolu seçim davranışını ve Spring Cache'in eşzamanlı değer sınırını aynı paketlerle doğrula. Kısmi örnek/sağlayıcı kanıtı tam kabul değildir.
- [ ] EN/TR sürüm notları, geçiş rehberleri, README'ler, değişiklik günlüğü ve sürüm geçmişini tutarlı tut. Geçmiş Java gereksinimlerini koru; hedefleri veya eski skorları nihai ölçüm yerine kullanma.
- [ ] UNRELEASED etiketini kaldırma ve tarih/indirme başvurusu ekleme adımını, yalnız yayım onayı ve gerçek uyumlu paketlerin doğrulanmasından sonra yap. Henüz oluşmamış sürüm adresi varsayma.

## Örnek Çalışma Zamanı Kanıtının Devri

`Framework Readiness` için [operatör devir rehberini](../../tools/ci/v019-sample-runtime-evidence.md#framework-readiness-operator-handoff)
izleyin. `workflow_dispatch` çağrısında koordinatörün bağımsız sabitlediği üç
girdiyi birlikte verin:

- `sampleRuntimeArtifactId`: izin listesindeki PRIVATE kaynak aynasında bulunan, süresi dolmamış Actions artifact'inin pozitif sayısal kimliği.
- `sampleRuntimeArtifactSha256`: artifact ZIP dosyasının SHA256 özeti (`upload-artifact` çıktısındaki `artifact-digest`); SDK veya kaynak arşivi özeti değil.
- `sampleRuntimeReceiptSha256`: gerçek producer çıktısı olan değişmemiş `receipt.json` dosyasının SHA256 özeti.

Dispatch deposunda `vars.CACHEDB_EVIDENCE_REPOSITORY` (tam `owner/repository` adı),
`vars.CACHEDB_EVIDENCE_REPOSITORY_ID` ve
`secrets.CACHEDB_EVIDENCE_READ_TOKEN` açıkça yapılandırılmalıdır. Depo metadata'sı
izin listesindeki tam ad/kimlik ile PRIVATE görünürlüğünü doğrulamalıdır. Token
zorunludur; `github.token`, public depo veya başka kanıt kaynağına dönüş yoktur.
Bu belge, özel aynanın/token'ın hazır olduğunu veya canlı devrin yapıldığını
bildirmez; bunlar operatörün tamamlaması gereken önkoşullardır.

İki özet de 64 karakterlik küçük harfli onaltılık değer olmalıdır. Artifact'i
üreten özel kaynak aynası çalışmasının `head_sha` değeri, public dispatch
çalışmasının `GITHUB_SHA` değeriyle birebir aynı olmalıdır. Aynı Git commit'ini
koruyan kaynak aynası gerekir; yalnız arşiv saklayan ilgisiz bir kanıt deposunun
commit'i, kopyalanmış kaynak ağacı veya aynı sürüm numarası yeterli değildir.
ZIP kökünde doğrudan `receipt.json` ve başvurduğu tüm dosyalar bulunmalı, ek üst
dizin olmamalıdır. Eksik yapılandırma/token ile eksik, kısmi veya geçersiz kanıt
girdileri kabul kapısını kapalı tutar.

Nihai dondurulmuş kaynak, ondan üretilip doğrulanmış SDK ve eşleşen manifestler
zorunludur. Dokuz gerçek lane, 18 yeni SQL kapsamı ve yalnız bu çalışmaya ayrılmış
Redis fixture'ı kullanılmalıdır; eski SDK veya benimsenmiş fixture kullanılamaz.
Sentetik verifier/import kontrolleri gerçek örnek kabulü değildir; ham raporların
tam üyeliğini ve özetlerini doğrulamanın yerini tutmaz.

Ham rapor ve günlükler özel kalmalıdır; bunları herkese açık depo artifact'lerine
yüklemeyin. Kanıtı yalnız izin listesindeki erişim kontrollü özel kaynak aynasında
tutun; metadata doğrulaması operatörün erişim ve sır korumasının yerini tutmaz.
Özel ayna, aynı commit, token veya gerçek kanıt yoksa kapı kapalı kalır; public
artifact ya da sentetik kontrol kullanarak aşılmaz. İçe alınan ham kanıt checkout dışında
`RUNNER_TEMP` altında tutulur ve workflow tarafından yeniden yüklenmez; yalnız
özetler/kontrol çıktıları yüklenir. Kaynaktaki özellik durumlarını değiştirmeyin;
bu kapsamın kabulü bütün sürümün yayım onayı değildir.

## Publishing

- `oss-release` Maven profilinin sources ve javadocs üretebildiğini doğrula
- Linux release package yolunun geçtiğini doğrula; yalnızca geliştirici makinesindeki build yeterli değildir
- GitHub Release artifact'inin hedef commit'ten üretildiğini ve bu release için
  resmi dağıtım paketi olduğunu doğrula
- seçilen release kanalı için signing config ve secret'ları doğrula
- hedef release workflow ve credential'ları doğrula
- Maven Central resmi dağıtım kanalı olarak seçildiyse GA duyurusu öncesinde
  signed publish sonucunun başarılı olduğunu doğrula

## GitHub / Repo Ayarları

- private vulnerability reporting'i aç
- default branch için branch protection tanımla
- evidence workflow'larını required check yap
- dış katılımcılar için label ve milestone'ları hazırla

## Dokümantasyon

- README ve Türkçe README güncel
- production recipe rehberi güncel
- yeni tuning parametreleri dokümante edildi
- release sınırları açıkça yazılı; provider veya topoloji sertifikası
  olduğundan fazla anlatılmıyor
