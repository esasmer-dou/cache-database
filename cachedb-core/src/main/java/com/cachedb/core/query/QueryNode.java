package com.reactor.cachedb.core.query;

public sealed interface QueryNode permits QueryFilter, QueryGroup {
}
