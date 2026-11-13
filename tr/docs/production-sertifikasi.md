# Production Sertifikası

İngilizce sürüm: [../../docs/production-certification.md](../../docs/production-certification.md)

Production trafiğini CacheDB'ye yönlendiren her uygulamada bu kontrolü kullan.
Bu mekanizma; rota kapsamını, veri eşitliğini, bellek kullanımını, failover'ı,
canary sonucunu ve geri dönüş planını yalnızca okunacak bir liste olmaktan
çıkarır. Eksik kanıt varsa Maven derlemesi başarısız olur.

## Örnek Doğrulama Araçlarının Bütünlüğü

Örnek API denetimleri, Linux x64 için Node.js 24.22.1 ve Newman 6.2.1 kullanır.
Bunlar doğrulama araçlarıdır; CacheDB'nin Java çalışma zamanı bağımlılıkları
değildir. [Makine tarafından okunabilir pin kaydı](../../docs/validation-tooling-pins.json),
yalnızca sürüm etiketini değil, arşivin tam baytlarını tanımlar:

- Node arşivi: `node-v24.22.1-linux-x64.tar.gz`, SHA-256 `eb6728e77c576a35162c122c45ccadf224d89ff00abc8dae46cc2897c6002d7c`.
- Newman arşivi: `newman-6.2.1.tgz`, bütünlük değeri `sha512-Zq8Sr5GFF+OXs5yIbyglLMKMh1WNMjYVV0yZaSBZ+DIgQOIWcxT8QTfbrl/YUGrLyT4rjpu+yZ/Z+kozw79GEA==`.

Geçici bir araç dizini kullanın. Arşivi açmadan veya çalıştırmadan önce indirilen
baytları doğrulayın; TLS ya da checksum denetimini kapatmayın. Kaynak başvuruları,
[Node checksum listesi](https://nodejs.org/dist/v24.22.1/SHASUMS256.txt) ve
[Newman'ın sürümlenmiş paket metadata'sıdır](https://registry.npmjs.org/newman/6.2.1).

```bash
set -eu
curl --fail --location --proto '=https' --tlsv1.2 -o node-v24.22.1-linux-x64.tar.gz https://nodejs.org/dist/v24.22.1/node-v24.22.1-linux-x64.tar.gz
printf '%s  %s\n' eb6728e77c576a35162c122c45ccadf224d89ff00abc8dae46cc2897c6002d7c node-v24.22.1-linux-x64.tar.gz | sha256sum --check -
tar -xzf node-v24.22.1-linux-x64.tar.gz
curl --fail --location --proto '=https' --tlsv1.2 -o newman-6.2.1.tgz https://registry.npmjs.org/newman/-/newman-6.2.1.tgz
./node-v24.22.1-linux-x64/bin/node -e 'const fs=require("node:fs"),crypto=require("node:crypto");const expected="sha512-Zq8Sr5GFF+OXs5yIbyglLMKMh1WNMjYVV0yZaSBZ+DIgQOIWcxT8QTfbrl/YUGrLyT4rjpu+yZ/Z+kozw79GEA==";const actual="sha512-"+crypto.createHash("sha512").update(fs.readFileSync("newman-6.2.1.tgz")).digest("base64");if(actual!==expected)throw new Error("Newman integrity mismatch");console.log("Newman archive integrity verified");'
```

Yalnızca Newman paketini sabitlemek, geçişli bağımlılıklarını sabitlemez. Kurulumun
`package-lock.json` dosyasını koruyun, SHA-256 değerini kanıtlarla birlikte kaydedin
ve farklı bir bağımlılık grafiğini sessizce çözümlemek yerine
`npm ci --ignore-scripts --no-audit --no-fund` kullanın. Bütünlük doğrulaması,
bağımlılık güvenlik incelemesi veya API testlerinin geçtiğine ilişkin kanıt
değildir. Sağlayıcı testleri, gerçek HTTP assertion'ları, sıfır atlanan test
denetimi ve tam sürüm kapsamı kontrolü ayrı gereklilikler olarak kalır.

## 1. Maven Kontrolünü Ekle

CacheDB BOM, plugin sürümünü yönetir. Aşağıdaki profili uygulamanın POM dosyasına
ekle:

```xml
<profiles>
    <profile>
        <id>production-certification</id>
        <build>
            <plugins>
                <plugin>
                    <groupId>com.reactor.cachedb</groupId>
                    <artifactId>cachedb-maven-plugin</artifactId>
                    <version>${cachedb.version}</version>
                    <executions>
                        <execution>
                            <id>certify-cache-database</id>
                            <phase>verify</phase>
                            <goals>
                                <goal>certify</goal>
                            </goals>
                        </execution>
                    </executions>
                </plugin>
            </plugins>
        </build>
    </profile>
</profiles>
```

Kontrolü çalıştır:

```bash
mvn verify -Pproduction-certification
```

Rapor `target/cachedb-production-certification.md` altında oluşur. Eksik veya
tutarsız bir alan Maven derlemesini durdurur.

## 2. Kanıt Dizinini Oluştur

```text
cachedb-certification/
├── certification.properties
├── route-coverage.csv
└── evidence/
    ├── redis-failover.md
    ├── sql-failover.md
    ├── rollback.md
    ├── canary.md
    ├── customer-orders-warm.md
    ├── customer-orders-parity.md
    └── customer-orders-memory.md
```

Kanıt dosyaları bu dizinin dışına çıkamaz ve aşağıdaki zorunlu başlığı taşır.
Alan eksikse, durum başarısızsa, ortam farklıysa veya commit manifest ile
eşleşmiyorsa Maven kontrolü geçmez.

```text
status: passed
commit: 0123456789abcdef
environment: staging
owner: orders-team
generated-at: 2026-08-13T12:00:00Z
summary: Redis failover tamamlandı ve rota ölçülen servis hedefi içinde toparlandı.
```

Başlığın altına CI bağlantısını, komutu, metrikleri ve gözlemleri ekle. Parola,
token veya kimlik bilgisi içeren JDBC adresi yazma.

## 3. Manifest Dosyasını Ekle

```properties
application=orders-api
environment=staging
application.commit=0123456789abcdef
framework.version=0.11.0
inventory.complete=true
inventory.routeCount=1
redis.failover=passed
sql.failover=passed
rollback.drill=passed
canary.ready=passed
redis.failoverEvidence=evidence/redis-failover.md
sql.failoverEvidence=evidence/sql-failover.md
rollback.drillEvidence=evidence/rollback.md
canary.evidence=evidence/canary.md
```

`application.commit`, bütün kanıtları test edilen uygulamanın kesin commit'ine
bağlar. `framework.version` kararlı bir semantik sürüm olmalıdır.
`inventory.complete=true`; ekran, API, batch, worker ve raporların uygulama
ekibi tarafından eksiksiz çıkarıldığını belirtir. `inventory.routeCount` değeri,
`route-coverage.csv` içindeki benzersiz rota sayısıyla aynı olmalıdır.

## 4. Bütün Production Rotalarını Ekle

[Kapsam şablonunu](../../docs/ga-migration-coverage-template.csv) başlangıç
noktası olarak kullan. Bir satır, bağımsız olarak canlıya alınacak bir rotayı
temsil eder.

```csv
RouteName,RouteKind,Owner,QueryShape,CacheDbShape,WarmStatus,WarmEvidence,CompareStatus,CompareEvidence,MemoryStatus,MemoryEvidence,CutoverStatus,RollbackPlan,RollbackEvidence,Blocker
customer-order-timeline,api,orders-team,"customer filter; date desc",projection,passed,evidence/customer-orders-warm.md,matched,evidence/customer-orders-parity.md,within budget,evidence/customer-orders-memory.md,ready,"CacheDB rota bayrağını kapat ve sınırlandırılmış SQL rotasına dön",evidence/rollback.md,none
```

Rota türü `screen`, `api`, `batch`, `worker` veya `report` olabilir. CacheDB
kullanım şekli `generated`, `projection`, `ranked projection`, `repository` ya
da `cold path` olabilir.

## 5. Hata Sonucunu Yorumla

| Hata | Yapılacak işlem |
| --- | --- |
| Rota sayısı farklı | Envanteri tamamla veya manifestteki sayıyı düzelt. |
| Warm kanıtı yok | Aynı warm rotasını çalıştır ve kapsam sonucunu dışa aktar. |
| Veri eşitliği sağlanmıyor | Kayıt üyeliğini ve sıralamayı sınırlandırılmış SQL rotasıyla karşılaştır. |
| Bellek bütçesi aşılıyor | Aktif veri penceresini veya payload boyutunu küçült; warm ve ölçümü tekrarla. |
| Failover geçmedi | Gerçek staging topolojisinde failover tetikle ve toparlanma kanıtını kaydet. |
| Geri dönüş kanıtı yok | Rota bayrağı veya deployment geri dönüş provasını çalıştır. |
| `Blocker` değeri `none` değil | Rotayı production trafiğine açma. |

BEST: Kanıtları staging CI içinde üret ve geçiş kararında kullanılan değişmez
özet dosyalarını sakla.

ANTI-PATTERN: Örnek dosyaları kanıt gibi kopyalamak, denenmemiş topolojiyi
başarılı işaretlemek veya framework reposundaki Docker testlerinin müşteriye ait
yönetilen altyapıyı sertifikalandırdığını varsaymak.
