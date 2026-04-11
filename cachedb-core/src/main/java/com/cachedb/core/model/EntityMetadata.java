package com.reactor.cachedb.core.model;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

public interface EntityMetadata<T, ID> {
    String entityName();
    String tableName();
    String redisNamespace();
    String idColumn();
    default String versionColumn() {
        return "entity_version";
    }

    default String deletedColumn() {
        return null;
    }

    default String activeMarkerValue() {
        return "false";
    }

    default String deletedMarkerValue() {
        return "true";
    }

    Class<T> entityType();
    Function<T, ID> idAccessor();
    List<String> columns();
    default Map<String, String> columnTypes() {
        return Map.of();
    }
    List<RelationDefinition> relations();
}
