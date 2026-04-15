package com.reactor.cachedb.core.projection;

import com.reactor.cachedb.core.model.EntityMetadata;
import com.reactor.cachedb.core.model.RelationDefinition;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

public final class ProjectionEntityMetadata<P, ID> implements EntityMetadata<P, ID> {

    private final String entityName;
    private final String tableName;
    private final String redisNamespace;
    private final String idColumn;
    private final Class<P> entityType;
    private final Function<P, ID> idAccessor;
    private final List<String> columns;
    private final Map<String, String> columnTypes;

    @SuppressWarnings("unchecked")
    public ProjectionEntityMetadata(EntityMetadata<?, ID> baseMetadata, EntityProjection<?, P, ID> projection) {
        this.entityName = baseMetadata.entityName() + "#" + projection.name();
        this.tableName = baseMetadata.tableName() + "__projection__" + projection.name().replace('-', '_');
        this.redisNamespace = baseMetadata.redisNamespace() + ":projection:" + projection.name();
        this.idColumn = baseMetadata.idColumn();
        this.entityType = (Class<P>) Object.class;
        this.idAccessor = projection.idAccessor();
        this.columns = List.copyOf(projection.columns());
        LinkedHashMap<String, String> inheritedColumnTypes = new LinkedHashMap<>();
        Map<String, String> baseColumnTypes = baseMetadata.columnTypes();
        if (baseColumnTypes != null && !baseColumnTypes.isEmpty()) {
            for (String column : this.columns) {
                String declaredType = baseColumnTypes.get(column);
                if (declaredType != null && !declaredType.isBlank()) {
                    inheritedColumnTypes.put(column, declaredType);
                }
            }
            String idDeclaredType = baseColumnTypes.get(this.idColumn);
            if (idDeclaredType != null && !idDeclaredType.isBlank()) {
                inheritedColumnTypes.putIfAbsent(this.idColumn, idDeclaredType);
            }
        }
        this.columnTypes = Map.copyOf(inheritedColumnTypes);
    }

    @Override
    public String entityName() {
        return entityName;
    }

    @Override
    public String tableName() {
        return tableName;
    }

    @Override
    public String redisNamespace() {
        return redisNamespace;
    }

    @Override
    public String idColumn() {
        return idColumn;
    }

    @Override
    public Class<P> entityType() {
        return entityType;
    }

    @Override
    public Function<P, ID> idAccessor() {
        return idAccessor;
    }

    @Override
    public List<String> columns() {
        return columns;
    }

    @Override
    public Map<String, String> columnTypes() {
        return columnTypes;
    }

    @Override
    public List<RelationDefinition> relations() {
        return List.of();
    }
}
