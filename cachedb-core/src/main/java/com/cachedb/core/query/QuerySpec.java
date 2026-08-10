package com.reactor.cachedb.core.query;

import com.reactor.cachedb.core.plan.FetchPlan;

import java.util.ArrayList;
import java.util.List;

public final class QuerySpec {
    public static final int MAX_LIMIT = 100_000;

    private final List<QueryFilter> filters;
    private final List<QuerySort> sorts;
    private final int offset;
    private final int limit;
    private final int queryTimeoutSeconds;
    private final FetchPlan fetchPlan;
    private final QueryGroup rootGroup;

    private QuerySpec(Builder builder) {
        if (builder.offset < 0) {
            throw new IllegalArgumentException("offset must not be negative");
        }
        if (builder.limit <= 0 || builder.limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
        if (builder.queryTimeoutSeconds < 0 || builder.queryTimeoutSeconds > 300) {
            throw new IllegalArgumentException("queryTimeoutSeconds must be between 0 and 300");
        }
        this.filters = List.copyOf(builder.filters);
        this.sorts = List.copyOf(builder.sorts);
        this.offset = builder.offset;
        this.limit = builder.limit;
        this.queryTimeoutSeconds = builder.queryTimeoutSeconds;
        this.fetchPlan = builder.fetchPlan;
        this.rootGroup = new QueryGroup(QueryGroupOperator.AND, builder.rootNodes);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static QuerySpec where(QueryNode rootNode, QueryNode... additionalNodes) {
        Builder builder = builder();
        builder.node(rootNode);
        if (additionalNodes != null) {
            for (QueryNode additionalNode : additionalNodes) {
                builder.node(additionalNode);
            }
        }
        return builder.build();
    }

    public static QuerySpec allOf(QueryNode... nodes) {
        return builder().allOf(nodes).build();
    }

    public static QuerySpec anyOf(QueryNode... nodes) {
        return builder().anyOf(nodes).build();
    }

    public List<QueryFilter> filters() {
        return filters;
    }

    public List<QuerySort> sorts() {
        return sorts;
    }

    public int offset() {
        return offset;
    }

    public int limit() {
        return limit;
    }

    /** Zero keeps the repository/provider default. Positive values override it for this query. */
    public int queryTimeoutSeconds() {
        return queryTimeoutSeconds;
    }

    public FetchPlan fetchPlan() {
        return fetchPlan;
    }

    public QueryGroup rootGroup() {
        return rootGroup;
    }

    public QuerySpec and(QueryFilter filter) {
        return toBuilder().filter(filter).build();
    }

    public QuerySpec and(QueryGroup group) {
        return toBuilder().group(group).build();
    }

    public QuerySpec orderBy(QuerySort... sorts) {
        Builder builder = toBuilder();
        if (sorts != null) {
            for (QuerySort sort : sorts) {
                if (sort != null) {
                    builder.sort(sort);
                }
            }
        }
        return builder.build();
    }

    public QuerySpec limitTo(int limit) {
        return toBuilder().limit(limit).build();
    }

    public QuerySpec offsetBy(int offset) {
        return toBuilder().offset(offset).build();
    }

    public QuerySpec withQueryTimeoutSeconds(int queryTimeoutSeconds) {
        return toBuilder().queryTimeoutSeconds(queryTimeoutSeconds).build();
    }

    public QuerySpec fetching(FetchPlan fetchPlan) {
        return toBuilder().fetchPlan(fetchPlan).build();
    }

    public QuerySpec fetching(String... relations) {
        return fetching(FetchPlan.of(relations));
    }

    private Builder toBuilder() {
        Builder builder = builder();
        for (QueryNode node : rootGroup.nodes()) {
            builder.node(node);
        }
        for (QuerySort sort : sorts) {
            builder.sort(sort);
        }
        builder.offset(offset);
        builder.limit(limit);
        builder.queryTimeoutSeconds(queryTimeoutSeconds);
        builder.fetchPlan(fetchPlan);
        return builder;
    }

    public static final class Builder {
        private final List<QueryFilter> filters = new ArrayList<>();
        private final List<QuerySort> sorts = new ArrayList<>();
        private final List<QueryNode> rootNodes = new ArrayList<>();
        private int offset;
        private int limit = 100;
        private int queryTimeoutSeconds;
        private FetchPlan fetchPlan = FetchPlan.empty();

        public Builder filter(QueryFilter filter) {
            this.filters.add(filter);
            this.rootNodes.add(filter);
            return this;
        }

        public Builder node(QueryNode node) {
            if (node == null) {
                return this;
            }
            if (node instanceof QueryFilter filter) {
                return filter(filter);
            }
            if (node instanceof QueryGroup group) {
                return group(group);
            }
            throw new IllegalArgumentException("Unsupported query node type: " + node.getClass().getName());
        }

        public Builder group(QueryGroup group) {
            this.rootNodes.add(group);
            this.filters.addAll(group.flattenFilters());
            return this;
        }

        public Builder anyOf(QueryNode... nodes) {
            return group(QueryGroup.or(nodes));
        }

        public Builder allOf(QueryNode... nodes) {
            return group(QueryGroup.and(nodes));
        }

        public Builder sort(QuerySort sort) {
            this.sorts.add(sort);
            return this;
        }

        public Builder offset(int offset) {
            this.offset = offset;
            return this;
        }

        public Builder limit(int limit) {
            this.limit = limit;
            return this;
        }

        public Builder queryTimeoutSeconds(int queryTimeoutSeconds) {
            this.queryTimeoutSeconds = queryTimeoutSeconds;
            return this;
        }

        public Builder fetchPlan(FetchPlan fetchPlan) {
            this.fetchPlan = fetchPlan;
            return this;
        }

        public QuerySpec build() {
            return new QuerySpec(this);
        }
    }
}
