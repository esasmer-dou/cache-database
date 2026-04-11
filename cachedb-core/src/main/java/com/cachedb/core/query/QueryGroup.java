package com.reactor.cachedb.core.query;

import java.util.ArrayList;
import java.util.List;

public record QueryGroup(
        QueryGroupOperator operator,
        List<QueryNode> nodes
) implements QueryNode {

    public QueryGroup {
        nodes = List.copyOf(nodes);
    }

    public static QueryGroup and(QueryNode... nodes) {
        return new QueryGroup(QueryGroupOperator.AND, List.of(nodes));
    }

    public static QueryGroup or(QueryNode... nodes) {
        return new QueryGroup(QueryGroupOperator.OR, List.of(nodes));
    }

    public List<QueryFilter> flattenFilters() {
        ArrayList<QueryFilter> filters = new ArrayList<>();
        for (QueryNode node : nodes) {
            if (node instanceof QueryFilter filter) {
                filters.add(filter);
                continue;
            }
            if (node instanceof QueryGroup group) {
                filters.addAll(group.flattenFilters());
            }
        }
        return List.copyOf(filters);
    }
}
