# Geçiş Planlayıcı

Admin UI artık mevcut PostgreSQL + ORM yapısından gelen ekipler için bir geçiş
planlayıcı sihirbazı içeriyor.

Şu soruları netleştirmek için kullan:

- Bu route full entity ile mi kalmalı, projection'a mı geçmeli?
- Hangi satırlar Redis'te sıcak kalmalı?
- Soğuk veri / tarihçe sınırı nerede başlamalı?
- İlk Redis çalışma seti nasıl warm edilmeli?
- Cutover öncesi staging ortamında hangi metrikler karşılaştırılmalı?

## Artık Neleri Keşfedebilir

Planlayıcı artık bağlı PostgreSQL şemasını inceleyip sihirbazı etkileşimli
şekilde doldurmak için başlangıç önerileri çıkarabiliyor.

Şunları keşfedebilir:

- yapılandırılmış `DataSource` üzerinden kullanıcı tablolarını
- primary key kolonlarını
- tek kolonlu foreign key ilişkilerini
- zamansal ve sıralama için uygun kolon adaylarını
- forma doğrudan uygulanabilecek root/child route önerilerini
- keşfedilen route için scaffold üretimine uygun sınıf adlarını ve sıcak route varsayılanlarını

Böylece kullanıcı planlamaya başlamadan önce tüm tablo ve kolon adlarını tek
tek elle girmek zorunda kalmaz.

## Etkileşimli Demo Bootstrap

Spring Boot demo artık planlayıcının kendisi için tek tıkla kurulabilen bir
PostgreSQL migration veri seti de içeriyor.

`/cachedb-admin/migration-planner` ekranından artık şunları yapabilirsin:

1. demo customer/order şemasını kur
2. gerçeğe yakın customer ve order tarihçesi seed et
3. explicit PK/FK ve destekleyici index'leri hazırla
4. elle SQL incelemesi için hazır view'leri oluştur
5. discovery'yi yenileyip doğrudan scaffold, warm ve compare adımlarına geç

Hazırlanan demo nesneleri:

- `cachedb_migration_demo_customers`
- `cachedb_migration_demo_orders`
- `cachedb_migration_demo_customer_order_timeline_v`
- `cachedb_migration_demo_customer_metrics_v`
- `cachedb_migration_demo_ranked_orders_v`

Böylece tam akışı tek ekran üzerinden prova etmek kolaylaşır:

- keşfet
- scaffold üret
- dry-run warm çalıştır
- gerçek warm çalıştır
- side-by-side compare yap

## Nerede Açılır

Aynı admin host ve port'u üzerinden:

- Spring Boot: `/cachedb-admin/migration-planner`
- Native admin server: `/migration-planner`

## Kullanıcıdan Ne İster

İlk sürüm aynı anda tek bir sıcak route'u modeller.

Artık tercih edilen akış şu:

1. önce PostgreSQL şemasını keşfet
2. önerilen root/child route'lardan birini forma uygula
3. keşfin bilemeyeceği route davranış bayraklarını elle düzelt

Elle devam edeceksen planlayıcı yine şunları ister:

- kök tablo/entity
- çocuk tablo/entity
- ilişki kolonu
- sıralama kolonu ve yönü
- mevcut kök ve çocuk satır sayıları
- kök başına tipik ve en kötü durum çocuk fan-out değeri
- ilk sayfa boyutu
- kök başına hedef sıcak pencere
- route'un liste ağırlıklı, global sıralı, threshold/range driven veya eager-loading ağırlıklı olup olmadığı
- tüm tarihçenin sıcak kalıp kalmaması gereği

## Ne Üretir

Sihirbaz somut bir geçiş planı ve staging ön ısıtma şekli üretir:

- önerilen CacheDB surface
- projection gerekliliği
- ranked projection gerekliliği
- bounded Redis sıcak pencere önerisi
- Redis yerleşim kararı
- PostgreSQL yerleşim kararı
- warm-up adımları
- staging karşılaştırma kontrol listesi
- PostgreSQL'den ilk sıcak çocuk penceresini çıkarmak için örnek child SQL'i
- ilişkili kök satırları çekmek için örnek root SQL şablonu

## Artık Neyi Üretebilir

Planlayıcı artık keşfedilen route'tan binding'e hazır bir scaffold da
üretebiliyor.

Bu scaffold şunları içerir:

- kök `@CacheEntity` iskeleti
- sıcak liste named query'si olan çocuk `@CacheEntity` iskeleti
- opsiyonel relation loader iskeleti
- opsiyonel projection destek iskeleti
- derleme sonrası oluşacak generated binding kullanım yüzeyini gösteren bir kullanım örneği

Bu çıktı bilinçli olarak temkinlidir. Amaç, ekibin entity metadata'sını sıfırdan
elle yazması yerine gerçek route şekli üzerinden başlamasını sağlamaktır.

## Artık Neyi Çalıştırabilir

Planlayıcı artık gerçek bir staging ön ısıtma çalıştırması da yapabilir.

Bu ön ısıtma akışı:

- seçilen sıcak pencereyi PostgreSQL'den okur
- PostgreSQL write-behind kuyruğuna tekrar yazmadan Redis entity yüzeylerini doğrudan hydrate eder
- kayıtlı projection'ları inline yeniler; böylece warmed route hemen okunabilir hale gelir
- istenirse aynı sıcak çocuk penceresine karşılık gelen kök satırları da warm eder
- Redis'i değiştirmeden önce dry-run modu ile deneme yapabilir

Bu yüzey staging ve geçiş provası içindir. Production cutover düğmesi olarak
tasarlanmadı.

Planlayıcı artık mevcut PostgreSQL route'una karşı side-by-side comparison da
çalıştırabiliyor.

Bu karşılaştırma şunları yapabilir:

- baseline PostgreSQL liste gecikmesini ölçer
- çözülen CacheDB route'unun gecikmesini ölçer
- temsilî kök örneklerinde ilk sayfa üyeliği ve sıralamasını karşılaştırır
- istenirse karşılaştırmadan hemen önce Redis çalışma setini warm eder
- baseline SQL'i görünür bırakarak ekibin onu incelemesine veya override etmesine izin verir

Karşılaştırma sonucu artık otomatik bir geçiş değerlendirmesi de üretir. Bu
değerlendirme şunları tek bakışta özetler:

- route'un geçiş için hazır olup olmadığını
- örnek sayfaların PostgreSQL ile birebir eşleşip eşleşmediğini
- CacheDB'nin beklenen gecikme aralığında kalıp kalmadığını
- cutover öncesi çözülmesi gereken blokajları
- staging tarafında önerilen sonraki adımları

## Nasıl Kullanılmalı

Bunu aşamalı geçişin bir parçası olarak kullan:

1. mevcut ORM route'unun baseline'ını al
2. route'u planlayıcıda modelle
3. önerilen projection / sıcak pencere tasarımını staging'de kur
4. route için entity/projection scaffold'unu üret
5. önce deneme modunda ön ısıtma çalıştır ve üretilen SQL'i gözden geçir
6. sonra önerilen Redis çalışma seti için gerçek staging ön ısıtmasını çalıştır
7. staging ortamında side-by-side comparison çalıştır
8. sıralama, gecikme ve yük şekli doğru görünmeden production cutover yapma

## Mevcut Kapsam

Bu sürüm, staging ön ısıtma çalıştırabiliyor olsa da bilinçli olarak
muhafazakâr tutuldu.

Şunları yapar:

- hedef veri şeklini çıkarır
- warm-up planı üretir
- karşılaştırma kontrol listesi çıkarır
- örnek backfill SQL'lerini üretir
- binding'e hazır entity/relation/projection scaffold'u üretir
- gerçek staging ön ısıtma çalıştırır
- PostgreSQL ile CacheDB arasında side-by-side comparison çalıştırır
- karşılaştırma sonucundan otomatik geçiş değerlendirmesi üretir
- Redis'i değiştirmeden önce dry-run doğrulaması sunar

Henüz şunları yapmaz:

- PostgreSQL'i mutate etmez
- production verisini değiştirmez
- mevcut ORM source class'larını otomatik içeri aktarmayı yapmaz
- tek tuş production cutover yapmaz

Bu sınır bilinçlidir. Amaç, ekiplerin önce doğru mimari kararı verip staging'de
geçiş provasını yapması, daha sonra kalan otomasyonu güvenle eklemesidir.
