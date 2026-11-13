# Sınırlı Alan Şifreleme

## Kapsam ve Saklama

CacheDB, açıkça işaretlenen kalıcı alanları JCA tabanlı şifreleme motoruyla korur.
Çekirdek paket `com.reactor.cachedb.core.crypto` adını taşır. Üretilen codec,
uygulamanın açıkça verdiği bir anahtar sağlayıcısına bağlanmalıdır; annotation,
çalışma zamanında metot çağrılarını yakalayan bir mekanizma değildir. Alan şifreleme;
yetkilendirmenin, saklama politikasının, güvenli silmenin veya eşzamanlı silme/yazma
işlemlerine karşı kalıcı korumanın yerine geçmez.

```java
import com.reactor.cachedb.annotations.CacheEncrypted;
import com.reactor.cachedb.annotations.EncryptionScope;

@CacheEncrypted(keyRing = "default", scope = EncryptionScope.REDIS_ONLY, blindIndex = true)
String contactAddress;
```

Annotation'ların saklama süresi SOURCE'tur. `@CacheEncrypted`, kalıcı alanlarda
ve record bileşenlerinde kullanılabilir; getter veya erişim metodu üzerinde
kullanılamaz. Desteklenmeyen kullanım derleme hatasıdır. Üretilen bağlamalar,
çalışma zamanında entity alanlarını reflection ile incelemez.

| Kapsam | Redis'te saklanan değer | SQL'de saklanan değer |
| --- | --- | --- |
| `REDIS_ONLY` (varsayılan) | E1 şifreli zarfı | Orijinal açık metin sütun değeri |
| `REDIS_AND_SOURCE` | E1 şifreli zarfı | E1 şifreli zarfı; uyumlu metin şeması gerekir |

Her iki kapsamda da işaretlenen alanlar; Redis entity içeriğinde, yazma kuyruğunda,
birleştirilmiş son durum kayıtlarında, yeniden denemelerde ve hatalı ileti kuyruğunda
(DLQ) şifrelidir. REDIS_ONLY, orijinal sütun değerini yalnızca geçici SQL işleminde
geri oluşturur; kuyruktaki korunan işlem açık metinle değiştirilmez.
REDIS_ONLY; SQL replikalarını, günlükleri, CDC kayıtlarını, dışa aktarımları veya
yedekleri şifrelemez. REDIS_AND_SOURCE, anahtar ya da uyumlu kaynak şeması yoksa
işlemi reddeder; açık metin saklamaya dönmez.
Saklama, null, indeksleme ve kaynak sütunu sözleşmeleri için
[şifreli codec ve kuyruk rehberine](sifreli-codec-kuyruk.md) bakın.

## API ve Protokol

```java
CacheKeyProvider keys = /* uygulamanın açıkça verdiği, sınırlandırılmış sağlayıcı */;
JcaFieldEncryption crypto = new JcaFieldEncryption(keys, 64);
String stored = crypto.encrypt("default", "tenant-a:orders", "42", "contact", serializedValue);
String restored = crypto.decrypt("default", "tenant-a:orders", "42", "contact", stored);
String token = crypto.blindIndex("default", "tenant-a:orders", "contact", serializedValue);
```

Önce `FieldValueCodec.encode` veya üretilmiş eşdeğeriyle alanın kanonik metnini
üretin. Şifreleme ve kör indeks aynı metni kullanmalıdır. Tipe dönüştürme, yalnızca
kimliği doğrulanarak şifresi çözülmüş değer üzerinde yapılır. Örtülü harf dönüşümü,
Unicode normalizasyonu veya tip dönüştürme uygulanmaz.

- JCA `AES/GCM/NoPadding` kullanılır: 256 bit AES anahtarı, her yazmada
  `SecureRandom` ile yeni 12 bayt nonce ve 128 bit doğrulama etiketi.
  AAD, `doFinal` çağrısından önce verilir.
- Zarfın tam biçimi `~E1:<keyId>:<base64url(nonce || ciphertext || tag)>` şeklindedir.
  Base64url, dolgusuz ve kanoniktir. Boşluk, `=`, standart Base64 `+`/`/`,
  kullanılmayan son bitlerde sıfır dışı değer, ek ayraç ve bilinmeyen sürüm reddedilir.
- Anahtar halkası ve anahtar kimliği, büyük/küçük harfe duyarlı
  `[A-Za-z0-9][A-Za-z0-9._-]{0,63}` ASCII kuralına uyar. Adlar kişisel veri
  içermeyen opak kimlikler olmalıdır. `:`, `|`, boşluk, Unicode veya sır içeren
  adlar kabul edilmez.
- AAD, `namespace + "|" + id + "|" + column` ifadesinin birebir UTF-8 baytlarıdır.
  Namespace ve id ayrı ayrı en çok 1024, sütun en çok 128 UTF-8 bayt olabilir.
  Bileşenler boş olamaz; `|`, C0 denetim karakterleri, DEL veya bozuk Unicode
  içeremez. Ayraç içeren bileşenler belirsiz birleştirmeye dönüştürülmez.
- Kimlikler kiracılar arasında tekrarlanabiliyorsa namespace, kanonik ve çakışmasız
  biçimde kiracıya bağlı olmalıdır. Ortak namespace ile yalnızca kiracı içinde
  tekil id kullanmak, kiracılar arası kriptografik yalıtım sağlamaz. Okuma, yazma,
  asenkron işlem ve kaynak erişiminde aynı bağlam kullanılmalıdır.
- Null değer, kör indeks belirteci dahil null kalır. Boş metin şifrelenir.
  Null veya eksik alanın bütünlüğü şema ve satır doğrulamasıyla sağlanmalıdır;
  GCM, mevcut olmayan alanı doğrulamaz.

**64 KiB (65.536 bayt) sınırı; başlık, nonce, şifreli içerik, doğrulama etiketi ve
Base64 genişlemesi dahil saklanan ASCII alanın tamamına uygulanır.**
Bu nedenle 64 KiB açık metin sığmaz. Açık metnin üst sınırı,
`JcaFieldEncryption.maxPlaintextBytes(keyId)` sonucudur: bir karakterlik
anahtar kimliğinde 49.119, 64 karakterlik kimlikte 49.072 UTF-8 bayt.
Anahtar kimliği rotasyonda uzayabileceği için uygulama sınırı olarak küçük değeri
kullanın. Dış entity ve içerik sınırları ayrıca geçerlidir. Kodlama öncesinde alan
metinleri için ek 64 KiB sınırı bulunur. Bozuk UTF-16 ve kimliği doğrulanmış olsa
bile geçersiz UTF-8 içerik reddedilir; yerine başka karakter konmaz.
Kör indeks girdisi en çok 64 KiB UTF-8 olabilir; belirteç daima 47 ASCII bayttır.

Alanı yalnızca değerinin önekine bakarak şifreli kabul etmeyin. İşaretlenmemiş alan,
`~E1:` ile başlayan metinde bile mevcut codec davranışını korur.
Açıkça şifreli bir alanın null olmayan okuması geçerli E1 zarfı gerektirir.
Eski açık metin, bilinmeyen anahtar, bozuk zarf veya kimlik doğrulama hatası işlemi
reddettirir. Açık metne geri dönüş ve otomatik açık metin göçü yoktur.
Zarfa benzeyen açık metin de normal biçimde şifrelenir; `encrypt` idempotent
değildir ve aynı saklama değerine iki kez uygulanmamalıdır.

## Anahtar Yönetimi ve Rotasyon

`CacheKeyProvider.current(keyRing)`, her yazma için değiştirilemez
`KeyMaterial` döndürür. `byId(keyRing, keyId)`, yalnızca istenen sürümü veya
`Optional.empty()` döndürür. Motor, anahtar aramasını yeniden denemez, tüm
anahtarları taramaz ve okumada güncel anahtara dönmez. Sınırsız anahtar önbelleği
tutmaz. `KeyMaterial`, birbirinden farklı iki adet 32 bayt anahtar ister,
bunları kopyalar ve `toString` çıktısını gizler. Ham anahtar baytlarını döndüren
herkese açık erişim metodu yoktur. KMS/Vault sağlayıcıları yerel veri şifreleme
anahtarı (DEK) baytları sağlamalıdır. Alan başına uzak şifreleme çağrısı ve dışarı
aktarılamayan HSM anahtar tanıtıcıları bu bayt tabanlı SPI'ın kapsamında değildir.

Yalnızca geliştirme ve test için açık ortam değişkeni örneği; varsayılan anahtar yoktur:

```java
KeyMaterial oldKey = KeyMaterial.fromBase64("key-1",
        System.getenv("CACHEDB_AES_KEY_1"), System.getenv("CACHEDB_BLIND_KEY"));
KeyMaterial newKey = KeyMaterial.fromBase64("key-2",
        System.getenv("CACHEDB_AES_KEY_2"), System.getenv("CACHEDB_BLIND_KEY"));
CacheKeyProvider dev = new StaticKeyProvider(
        Map.of("default", List.of(oldKey, newKey)), Map.of("default", "key-2"));
```

Örnekteki ek türler: `java.util.List`, `java.util.Map` ve çekirdek kripto
paketindeki sınıflar. Yapılandırma anahtarları, 32 bayta çözülen tam 44 karakterlik,
dolgulu ve kanonik **standart** Base64 olmalıdır. Bu biçim, zarfın Base64url
biçiminden farklıdır. Birbirinden bağımsız iki rastgele sırrı onaylı sır yönetim
araçlarıyla sağlayın. Parola, depoya yazılmış anahtar veya AES anahtarının
kopyası/türevi kullanılmamalıdır. Ortam değişkenleri ve Java String değerleri
güvenilir biçimde silinemez. `StaticKeyProvider`, üretim ortamında sır yaşam
döngüsünü yöneten bir çözüm değildir.

En çok 16 anahtar halkası, halka başına 32 sürüm ve toplam 256 anahtar desteklenir.
Eksik güncel kimlik, yinelenen kimlik, aynı AES anahtarına başka kimlik verme
(halkalar arasında da dahil), herhangi bir AES anahtarını kör indeks anahtarı olarak
kullanma ve aynı halkadaki sürümler arasında farklı HMAC anahtarı kullanma,
nesne oluşturulurken reddedilir. Yapılandırma koleksiyonları kopyalanır;
bellek içi arama karmaşıklığı O(1)'dir.

Üretim ortamı için KMS/Vault bağdaştırıcısı örneği; tam bir sır yöneticisi değildir:

```java
final class VaultBackedKeys implements CacheKeyProvider {
    private final AtomicReference<CacheKeyProvider> active;
    VaultBackedKeys(CacheKeyProvider validatedBoundedSnapshot) {
        active = new AtomicReference<>(Objects.requireNonNull(validatedBoundedSnapshot));
    }
    public KeyMaterial current(String ring) { return active.get().current(ring); }
    public Optional<KeyMaterial> byId(String ring, String id) {
        return active.get().byId(ring, id);
    }
    void publish(CacheKeyProvider validatedBoundedSnapshot) {
        active.set(Objects.requireNonNull(validatedBoundedSnapshot));
    }
}
```

Ek türler: `java.util.Optional`, `java.util.Objects` ve
`java.util.concurrent.atomic.AtomicReference`.
Uygulama, aynı üst sınırlar ve anahtar ayrımı kurallarıyla değiştirilemez bir
anahtar kümesi oluşturmalıdır. Tek ve sınırlandırılmış bir yenileme görevi,
sarılmış DEK'leri onaylı SDK ile alır ve açar; bütün kümeyi doğruladıktan sonra
atomik olarak kullanıma sunar. Çekirdeğe sır yöneticisi bağımlılığı eklenmez.
Uzak erişimde zaman aşımı, yalnızca yönetim düzleminde sınırlı ve rastgele
gecikmeli yeniden deneme, süre sonu/hazırlık politikası ve veri içermeyen hata
sayaçları gerekir. Motor, takılı kalan sağlayıcı çağrısını kesmez; işlem izni
meşgul kalır. Her istekte uzak anahtar araması yapmak uygun değildir.
Hataları gizlemek için süresi dolmuş veya iptal edilmiş anahtarı süresiz tutmayın.
Mevcut bir anahtar kimliğinin arkasındaki baytları asla değiştirmeyin.

Rotasyonda önce eski ve yeni sürümleri tüm okuyuculara dağıtın, sonra yazma için
güncel sürümü değiştirin. Eski sürümleri okunabilir tutun ve sınırlandırılmış,
kaldığı yerden sürdürülebilen bir yeniden şifreleme işi kullanın. Eski anahtarı
kaldırmadan önce kapsam içindeki Redis/SQL verisini, yazma kuyruğunu, DLQ/yeniden
işleme kayıtlarını ve yedekten dönüş politikasını doğrulayın.
96 bit rastgele nonce, olasılıksal tekillik sağlar; kalıcı küresel sayaç değildir.
Tüm pod'lar ve yeniden başlatmalar boyunca anahtar kullanım süresini ve geçerli
GCM işlem bütçesini operasyonel payla sınırlandırın. Temkinli tavan, anahtar başına
2^32 şifrelemedir. Motor, küme genelinde anahtar kullanım sayacı uygulamaz.

## Kör İndeks

Belirteç, ayrı HMAC anahtarıyla tam HMAC-SHA256 özetinin dolgusuz Base64url
değerine `~B1:` öneki eklenerek üretilir. MAC girdisinin UTF-8 biçimi:
`CacheDB:blind-index:v1|<ring>|<namespace>|<column>|<kanonik alan değeri>`.
Eşitlik araması için satır kimliği dahil edilmez. Alan değeri son bileşendir ve
`|` içerebilir; önceki bütün bileşenler sıkı kurallara uyar.
AES anahtar kimliği dahil edilmez; ayrı HMAC anahtarı sabit kaldığında AES
rotasyonu belirteçleri korur. Kör indeks anahtarının değişmesi, açık bir
belirteç/indeks göçü ve sorgu planı gerektirir; doğrudan değiştirmek eski
eşitlik üyeliklerini erişilemez kılar.

Belirteç eşitlik ve sıklık bilgisi sızdırır; anonim veri değildir ve gizli
tutulmalıdır. Yalnızca EQ desteklenir. Şifreli sütunda aralık, sıralama, facet,
toplulaştırma, bileşik indeks, bölümleme ve kiracı/kimlik kullanımı reddedilir.
Sorgu değeri aynı belirtece dönüştürülmeli; indeks üst verisine açık metin değil
yalnızca belirteç yazılmalıdır.

## Hatalar ve Kaynak Sınırları

Motor hataları yalnızca sabit `CacheEncryptionException.Code` taşır.
Açık/şifreli değer, anahtar baytları, kimlik, bağlam veya sağlayıcı/JCA hata nedeni
içermez. Kodlar; bozuk girdi, bilinmeyen anahtar, doğrulama, yapılandırma,
sağlayıcı/kripto, alan sınırı ve doluluk hatalarını ayırır.
Ölçümlerde yalnızca işlem ve hata kodunu kullanın; değer, kimlik, kiracı adı veya
anahtar kimliğini etiket yapmayın. Motor günlük yazmaz; günlük/ölçüm bağımlılığı
eklemez. Bozuk girdi, bilinmeyen anahtar veya doğrulama hatasını körlemesine
yeniden denemeyin.

Motor başına varsayılan 64 etkin işlem vardır; yapılandırılabilir üst sınır
1..256'dır. `tryAcquire`, dolulukta SATURATED ile hemen reddeder; kuyruk,
bekleme veya yeniden deneme yoktur. Çağıran uygulama sınırlı kabul ve geri basınç
uygulamalıdır. Her alan/istek için motor oluşturarak sınırı aşmak yerine aynı
koruma sınırında motoru paylaşın. Cipher/Mac örnekleri işlem başına ayrıdır;
sanal iş parçacığı başına önbellek veya paylaşılan değişken durum yoktur.
Yürütücü, ThreadLocal ve kilit altında I/O bulunmaz. Geçici açık metin bayt
dizileri finally bloğunda temizlenir; Java String değerlerinin ve JCA iç
tamponlarının silinmesi garanti edilemez.

Protokol kaynakları: [JDK 21 Cipher](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/javax/crypto/Cipher.html),
[GCMParameterSpec](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/javax/crypto/spec/GCMParameterSpec.html),
[NIST SP 800-38D](https://csrc.nist.gov/pubs/sp/800/38/d/final).
