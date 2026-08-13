# Production Sertifikası

İngilizce sürüm: [../../docs/production-certification.md](../../docs/production-certification.md)

Production trafiğini CacheDB'ye yönlendiren her uygulamada bu kontrolü kullan.
Bu mekanizma; rota kapsamını, veri eşitliğini, bellek kullanımını, failover'ı,
canary sonucunu ve geri dönüş planını yalnızca okunacak bir liste olmaktan
çıkarır. Eksik kanıt varsa Maven derlemesi başarısız olur.

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
framework.version=0.10.0
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
