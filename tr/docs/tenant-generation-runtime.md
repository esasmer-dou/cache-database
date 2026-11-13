# Tenant Nesli Çalışma Sözleşmeleri

## Varlığa Ait Kapsam

Varlık temizleme işlemi yalnızca kayıtlı varlığın namespace'i ile istenen tenant'ı ve açıkça
bildirilmiş, bu varlığa ait depoları kapsar. Diğer varlıklarda tenant genelinde yazma kapatma
veya kişisel verileri otomatik bulma işlemi değildir. Genel Spring Cache metot değerleri bir
varlık sınıfına otomatik olarak bağlanamaz; sahiplik katılımcısı açıkça kaydedilip zorunlu
katılımcı listesine eklenmedikçe bu kapsamın dışındadır. Bildirilmiş zorunlu katılımcının
bulunmaması veya sahipliği doğrulayamaması, sonucun eksik olması demektir.

Karışık projeksiyonlar ve atomik kuyruk kayıtları diğer varlıkların ve tenant'ların verilerini
korumalıdır. Desteklenmeyen sahiplik, nesli bilinmeyen eski kayıtlar veya tamamlanmamış sınırlı
doğrulama, başarılı silme olarak raporlanamaz. Bu kapsam evrensel kişisel veri silme, KVKK/GDPR
sertifikası ya da yedek, dışa aktarma, log ve harici sistemlerde silme garantisi değildir.

## Özgün Nesil Ve SQL Yazma Kabulü

Özgün nesil damgaları zarfın ayrı protokol alanlarıdır. İzleme metaverisi, şifreli veri veya
iş verisi sütunları içine gizlenmez. Mevcut yazma, kuyruk, makbuz ve yapılandırma record
türlerinin bileşen sayıları değişmez. Worker kapsamları önceki thread durumunu geri yükler
ve çelişen damgaları reddeder.

JDBC flusher, korunan her özgün nesli aynı iş transaction'ında, yazma fence filtresi ve iş
DML'inden önce denetler. Guard satır kilidi commit veya rollback tamamlanana kadar tutulur.
Metaveri sarmalayıcısı varsayılan kapalı seçeneğini bildirse bile damgalı yazmalar denetlenir.
Eksik kayıt, özgün damga veya tenant sahipliği güvenli biçimde reddedilir. Ön plandaki normal
yazmalara SQL guard sorgusu ya da kimlik rezervasyonu eklenmez.

Özgün ve doğrulanabilir nesli olmayan harici değişiklik payload'ları, özel handler çağrılmadan
reddedilir. Kuyrukta beklemiş eski payload'a güncel nesil atanamaz. Atomik nesil koruması
bulunmayan eski üretici, okuma ve projeksiyon yolları, bu özelliği açan metaveri için kullanılamaz.
Bu karantina bir güvenlik sınırıdır; tamamlanmış temizleme uygulaması değildir.

## Kuyruk Ve Commit Sonrası İşler

Yeniden deneme ve DLQ kopyaları özgün zarfı korur. XADD, stream ile aynı hash slot'undaki nesil
aynasını aynı Redis komutunda denetler. Kullanılan her gerçek stream hedefi bildirilmeli,
kapatılmalı ve doğrulanmalıdır. Varlığın slot'undaki guard, farklı slot'taki kuyruğu korumaz.

Commit sonrası durability işaretleri ile compaction tamamlama ve yeniden kuyruğa alma,
her değişiklikten önce özgün nesli denetler. Kapalı veya değiştirilmiş nesil yeni işaret,
payload veya bildirim oluşturamaz. Compaction bildirimi payload'ın özgün damgasını taşır.
Eksik ya da tutarsız payload sahipliği reddedilir. Tükenen aralığın son kabul edilmiş sürümü
durable olarak işaretlenebilir; yalnızca yeni sürüm ayırma işlemi kapasite hatası verir.

Stream temizleme motoru sabit bir üst ID kaydeder, primary sürecini ve slot sahipliğini
doğrular; bulk/array ayırmadan önce yanıtı 1 MiB ile sınırlar. COUNT 128 bir byte sınırı değildir.
Yanıtın tamamı tüketilmeden önce kalıcı olarak kaydedilmelidir. Yeniden başlatma sonrasında
aynı ayrıştırma sınırlarıyla çözülebilir; süreç veya slot değiştiyse kurtarma gerekir.

Yalnızca bütünüyle hedefe ait, özgün nesli bilinen kayıtlar silinebilir. Karışık tenant'lı atomik
kayıtlar ve nesli bilinmeyen eski zarflar eksik sahiplik sonucu üretir; bölünmez veya silinmez.
Diğer tenant'ların kayıtları korunur. Paylaşılan stream bütünüyle silinmez veya kısaltılmaz.
Sınırlı istek/sonuç kaydı, yanıt kaybından sonraki tekrarda aynı sayıyı döndürür. Redis sonuç
kaydını onaylamadan önce sayı ve bekleyen konum aynı SQL transaction'ında kaydedilmelidir.
Korunmuş sonuç olmadan bulunamayan kayıt, sıfır silme değil sonucu bilinmeyen işlemdir.

## Silme Ve Saklama Ayrımı

includeSource=false seçeneği kanonik SQL DELETE çağırmamalı veya SQL silme mesajı üretmemelidir.
Kaynak satırlar korunur ve kalıcı guard kapalı kalır. Kaynak/geçmiş için ayrıca doğrulanmış
bir geçiş sözleşmesi yoksa açık yeniden etkinleştirme MIGRATION_REQUIRED ile reddedilir.
includeSource=true seçeneği için doğrudan, tam tenant eşleşmeli kaynak silme ayrı ve sınırlı
yürütülür. Saklama DELETE işlemi ise normal SQL write-behind yolunu kullanır; Redis-only
tenant temizliği değildir.

Bu bileşenler tek başına tüm üreticileri, okumaları, L1'i, gecikmiş doldurmaları, projeksiyonları,
bildirilmiş depoları, denetim kayıtlarını veya COMPLETE/REENABLED makbuzunu yönetmez. Uçtan uca
temizleme sunulmadan önce bu yolların tümünün kaydı ve doğrulaması gerekir.

Native kimlik katılımcıları aynı sınırlı katılımcı sözleşmesini kullanır. Kalıcı checkpoint, aşama
kanıtı ve fiziksel anahtar sayısı SQL guard altında birlikte kaydedilir; native Redis çağrısı bu
transaction'ın dışında çalışır. Önceden VERIFIED olmuş aşama, süreç veya kapanma durumu değişince
körü körüne kabul edilmez; yeniden doğrulanır. Mühürlenmiş ID alt akışı, zorunlu KEY/ENTRY temizleme
işlerinden ayrıdır. Bu işler ayrıca yönlendirilip doğrulanmalı, sessizce atlanmamalıdır.
