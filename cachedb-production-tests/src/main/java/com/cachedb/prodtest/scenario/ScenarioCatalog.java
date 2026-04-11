package com.reactor.cachedb.prodtest.scenario;

import java.util.List;

public final class ScenarioCatalog {

    private static final String DEFAULT_SCENARIOS =
            "campaign-push-spike;LOAD;SMS ve push kampanyasi sonrasi anlik browse + cart + checkout sikismasi;50000;45;64;200000;50000;250;35;20;20;10;10;5;8;500;100000;500;300;120|"
                    + "weekend-browse-storm;LOAD;Okuma agirlikli hafta sonu katalog tarama ve urun detay firtinasi;35000;60;48;150000;80000;500;55;25;8;4;6;2;6;400;150000;800;300;120|"
                    + "flash-sale-hot-sku-contention;BREAK;Cok az sayida sicak SKU uzerinde inventory contention ve write ordering baskisi;20000;40;48;90000;20000;12;15;10;20;35;15;5;2;80;20000;100;180;60|"
                    + "cache-thrash-breaker;BREAK;Cok dusuk hot-set limiti altinda genis katalog taramasi ile cache churn olusturur;18000;40;32;120000;120000;5000;45;30;10;5;7;3;3;120;1000;25;60;30|"
                    + "write-behind-backpressure-breaker;BREAK;Checkout ve inventory yazmalarini arttirip write-behind backlog biriktirir;25000;45;40;100000;40000;75;10;10;20;25;25;10;1;50;40000;250;180;60";

    private ScenarioCatalog() {
    }

    public static List<EcommerceScenarioProfile> all() {
        return ScenarioCatalogOverrides.profiles("cachedb.prod.catalog.scenarios", DEFAULT_SCENARIOS);
    }

    public static EcommerceScenarioProfile byName(String name) {
        return all().stream()
                .filter(profile -> profile.name().equalsIgnoreCase(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown scenario: " + name));
    }
}
