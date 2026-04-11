package com.reactor.cachedb.examples.demo;

public enum DemoScenarioLevel {
    LOW("low", 22, 12, 8L, 10L, "Low", "Gunduz trafigine yakin temel senaryo. Redis uzerinden katalog ve musteri akislariyla birlikte PostgreSQL'den sayfali musteri ve siparis okumalarini da dener."),
    MEDIUM("medium", 44, 24, 3L, 4L, "Medium", "Aksam alisveris trafigine yakin dengeli senaryo. Yogun musteri siparisleri, yuksek satirli order line akislari ve dogrudan PostgreSQL rapor okumalari belirginlesir."),
    HIGH("high", 72, 44, 1L, 2L, "High", "Kampanya saati benzeri yogun senaryo. Redis ve PostgreSQL birlikte zorlanir; genis siparis okumasi, agir order line join'leri ve toplu yazma burst'leri one-to-many akislarini sertce baskilar.");

    private final String propertyKey;
    private final int defaultReaderThreads;
    private final int defaultWriterThreads;
    private final long defaultReaderPauseMillis;
    private final long defaultWriterPauseMillis;
    private final String label;
    private final String summary;

    DemoScenarioLevel(
            String propertyKey,
            int defaultReaderThreads,
            int defaultWriterThreads,
            long defaultReaderPauseMillis,
            long defaultWriterPauseMillis,
            String label,
            String summary
    ) {
        this.propertyKey = propertyKey;
        this.defaultReaderThreads = defaultReaderThreads;
        this.defaultWriterThreads = defaultWriterThreads;
        this.defaultReaderPauseMillis = defaultReaderPauseMillis;
        this.defaultWriterPauseMillis = defaultWriterPauseMillis;
        this.label = label;
        this.summary = summary;
    }

    public String propertyKey() {
        return propertyKey;
    }

    public int defaultReaderThreads() {
        return defaultReaderThreads;
    }

    public int defaultWriterThreads() {
        return defaultWriterThreads;
    }

    public long defaultReaderPauseMillis() {
        return defaultReaderPauseMillis;
    }

    public long defaultWriterPauseMillis() {
        return defaultWriterPauseMillis;
    }

    public String label() {
        return label;
    }

    public String summary() {
        return summary;
    }
}
