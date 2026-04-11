package com.reactor.cachedb.prodtest.scenario;

import java.util.List;

public final class FullScaleBenchmarkCatalog {

    private static final String DEFAULT_SCENARIOS =
            "campaign-push-spike-50k;LOAD;SMS, push ve influencer kampanyasi sonrasi browse + cart + checkout dalgasi;50000;300;160;2000000;500000;2000;30;20;20;10;15;5;16;1000;500000;750;300;120|"
                    + "weekend-browse-storm-50k;LOAD;Hafta sonu katalog gezme ve urun detay agirlikli 50k TPS browse firtinasi;50000;300;160;1500000;1200000;12000;55;25;8;3;6;3;12;800;700000;1000;300;120|"
                    + "flash-sale-hot-sku-contention-50k;BREAK;Flash sale aninda dar bir SKU grubunda inventory contention ve checkout baskisi;50000;300;192;1200000;80000;20;10;10;15;35;25;5;6;300;120000;250;180;60|"
                    + "checkout-wave-after-sms-50k;LOAD;SMS kampanyasi sonrasi sepete ekleme ve checkout agirlikli ani donusum dalgasi;50000;300;176;1000000;200000;350;12;10;23;20;30;5;10;600;250000;500;240;90|"
                    + "loyalty-reengagement-mix-50k;LOAD;Sadakat kampanyasi, musteri segment guncelleme ve urun tekrar bakis karisimi;50000;300;144;2500000;400000;1000;22;18;20;12;10;18;10;700;300000;600;240;90|"
                    + "catalog-cache-thrash-50k;BREAK;Genis katalog ve dusuk sicak veri butcesi ile cache churn ve read-through baskisi;50000;300;160;1800000;2000000;100000;48;28;8;4;7;5;8;500;25000;60;90;45|"
                    + "write-behind-backpressure-50k;BREAK;Siparis ve inventory yazmalarini yukseltip PostgreSQL flush backlog olusturur;50000;300;160;1200000;300000;120;8;8;18;28;28;10;4;200;180000;300;180;60|"
                    + "inventory-reconciliation-aftershock-50k;BREAK;Kampanya sonu inventory reserve ve musteri touch karisimi ile flush ve version baskisi;50000;300;144;1600000;250000;80;14;10;12;34;15;15;6;250;160000;250;180;60";

    private FullScaleBenchmarkCatalog() {
    }

    public static List<EcommerceScenarioProfile> all() {
        return ScenarioCatalogOverrides.profiles("cachedb.prod.catalog.fullScaleScenarios", DEFAULT_SCENARIOS);
    }

    public static EcommerceScenarioProfile byName(String name) {
        return all().stream()
                .filter(profile -> profile.name().equalsIgnoreCase(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown full-scale scenario: " + name));
    }
}
