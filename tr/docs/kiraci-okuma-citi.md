# Kiracı Okumalarında Kuşak Çiti

Okuma çiti, kayıtlı ve kiracı koruması etkin bir varlık için açıkça yapılandırılır. Alan
şifrelemesi tek başına kiracı temizleme korumasını etkinleştirmez.

Çalışma zamanı, okuma G/Ç işleminden önce AÇIK kuşağı alır. Kaynak okuması, önbellek doldurma,
projeksiyon işlemi ve sonucun teslimi aynı özgün kuşağı taşımalıdır. Kabul durumu kapalıysa,
değişmişse, bilinmiyorsa veya doğrulanamıyorsa işlem güvenli biçimde reddedilir. Kuşağı
belirlemek için SQL sorgusu çalıştırılmaz.

Korunan tekil ve toplu okumalar sınırlı kapasite kullanır. Köprü en çok 128 kimliğe, kodlanmış
satır başına 1 MiB'a ve sonuç başına 8 MiB'a izin verir. Yerel eşzamansız taşıma aynı kuşağı
doğrulayana kadar korunan okumalar mevcut sınırlı worker yürütücüsünü kullanır. Korunmayan
yerel okumaların taşıma yolu değişmez.

L1 girdileri kimlik bazlı mevcut geçersizleştirmeyi korur ve yalnızca bellekte tutulan bir
kuşak çerçevesi taşır. Bu çerçeve Redis varlık biçimi değildir. Yumuşak süre aşımı çitsiz bir
yenileme başlatmaz. Okuma sırasında gelen değişiklik bildirimi, eski değerin sonradan L1'e
yerleştirilmesini engeller.

Kaynak yükleyici açıkça sağlanır; köprü SQL yükleyicisi bulmaz veya oluşturmaz. Kaynak sonucu
ancak özgün kuşak yeniden doğrulandıktan sonra döndürülebilir. İsteğe bağlı doldurma planı
kanonik sürüm/indeks üretici protokolünü kullanmalı ve özgün kuşakla aynı Redis yuvasında
korunan bir yayın planı döndürmelidir. Planlama yalnızca işlemci üzerinde yapılır. Köprü yazma
sürümü ayırmaz, güncel kuşaktan yeni damga üretmez, iş yazmasını kuyruğa eklemez ve sonucu
belirsiz bir yayını yeni kuşakla tekrar denemez.

Projeksiyonun tekil okumaları, kimliği koruduğu açıkça incelenmiş bir sözleşme gerektirir.
Sonuç, rastgele kalıcı projeksiyona veya paylaşılan sorgu önbelleğine güvenmek yerine korunan
temel varlıktan üretilir. Kimlik değişikliği ve bilinmeyen sahiplik eksik kabul edilir.
Kalıcı projeksiyon yenilemesi ayrıca incelenmiş kanonik bir yayın planı gerektirir.

Bu köprü varlık sorgusu, sayfa, toplama, imleç, ilişki getirme, izleyici teslimi, rastgele
Spring Cache girdileri veya eski doğrudan doldurma yollarını doğrulamaz. Gerekli protokolü
olmayan korunan yollar eski yoldan okuma ya da doldurma yapmadan reddedilir. Açık kayıt,
eksiksiz sahiplik ve depo bildiriminin yerine geçmez.

Kabulü kapatmak veya bir future'ı iptal etmek, G/Ç işlemlerinin tamamlandığını kanıtlamaz.
Silme ve doğrulamadan önce kayıtlı her çalışma zamanı fiziksel tamamlanmayı onaylamalı ve
bildirdiği L1 depolarını temizlemelidir. Eksik çalışma zamanı veya projeksiyon katılımcısı
başarı sayılmaz. Yeniden açma, eksiksiz kiracı koruma protokolü ve doğrulanmış yeni kuşak
gerektirir.
