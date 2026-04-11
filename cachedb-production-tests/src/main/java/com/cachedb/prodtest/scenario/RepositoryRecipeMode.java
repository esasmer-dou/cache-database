package com.reactor.cachedb.prodtest.scenario;

public enum RepositoryRecipeMode {
    JPA_STYLE_DOMAIN_MODULE(
            "JPA-style domain module",
            "Generated package module plus grouped queries/commands/pages. This is the most ORM-like CacheDB surface."
    ),
    GENERATED_ENTITY_BINDING(
            "Generated entity binding",
            "Compile-time generated entity helpers without the extra package-level domain wrapper."
    ),
    MINIMAL_REPOSITORY(
            "Minimal repository",
            "Direct EntityRepository and ProjectionRepository usage with hand-built QuerySpec/PageWindow objects."
    );

    private final String label;
    private final String positioning;

    RepositoryRecipeMode(String label, String positioning) {
        this.label = label;
        this.positioning = positioning;
    }

    public String label() {
        return label;
    }

    public String positioning() {
        return positioning;
    }
}
