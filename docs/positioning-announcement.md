# CacheDB Positioning Draft

CacheDB is a Redis-first persistence library for teams that care about production runtime overhead but do not want to fall back to low-level infrastructure code everywhere.

The short story:

- Redis-first reads and writes
- PostgreSQL durability through async write-behind
- compile-time generated metadata instead of runtime reflection
- generated domain and binding surfaces for easy onboarding
- measured escape hatches for real hotspots

What makes the positioning credible:

- the project explicitly says when Hibernate/JPA is still the better fit
- the generated ergonomic surfaces are benchmarked against minimal repository usage
- relation-heavy read patterns are backed by a dedicated read-shape benchmark, not just narrative advice

Current proof points inside the repo:

- repository recipe benchmark:
  - generated entity binding is about `+4.84%` average latency vs minimal repository
  - JPA-style domain module is about `+16.83%` average latency vs minimal repository
- read-shape benchmark:
  - makes summary/detail vs preview vs full aggregate costs visible before production rollout

Suggested external positioning:

> CacheDB is an ORM alternative for Redis-centric systems that need lower production overhead, explicit read-model control, and an ergonomic path that does not force every team into low-level repository code.

Suggested guardrail sentence:

> CacheDB is not trying to hide persistence behavior. It is trying to make the low-overhead path easier to consume.

Suggested target audience:

- product services with hot read paths
- teams already operating Redis as a first-class dependency
- dashboards and list-heavy applications that benefit from projections
- teams that want a generated API surface but still need hotspot escape hatches

Suggested non-fit audience:

- applications driven mainly by SQL joins and relational reporting
- teams that want ORM behavior to stay mostly implicit
- systems where Redis is not part of the real runtime design
