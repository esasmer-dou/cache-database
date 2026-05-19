# Changelog

All notable changes to `cache-database` will be tracked here.

The format is intentionally simple during public beta.

## Unreleased

### Added

- _TBD_

### Changed

- _TBD_

### Fixed

- _TBD_

## 0.1.0-beta.2 - 2026-05-19

### Added

- migration planner schema discovery for PostgreSQL tables, primary keys, foreign keys, sort candidates, and route suggestions
- migration planner scaffold generation for `@CacheEntity`, relation loader, projection support, and generated binding usage
- dry-run and staging warm execution for selected Redis hot windows
- side-by-side PostgreSQL vs CacheDB comparison with parity, latency, route-label, and readiness assessment
- downloadable migration report content with cutover action plan, blockers, rollback notes, and coverage guidance
- admin UI category map with simpler navigation for health, operations, migrations, projections, runtime, and evidence
- richer English and Turkish onboarding docs with use-case driven setup guidance

### Changed

- migration planner UX now favors discovery-first route selection and clearer step-by-step execution
- relation-heavy and global sorted guidance now points users toward projection/read-model and ranked projection paths earlier
- demo documentation now describes exact button order for load testing, demo schema bootstrap, warm, compare, and report download
- Spring Boot starter docs now clarify when `spring-boot-starter-jdbc` is required and when an existing JPA `DataSource` is enough

### Fixed

- migration comparison can use registered projection routes instead of falling back to full entity routes for projection-required shapes
- migration warm and comparison paths surface clearer errors when selected entities are not registered
- Turkish docs were cleaned across the main onboarding path for clearer wording and correct Turkish characters

## 0.1.0-beta.1 - 2026-04-11

### Added

- official production evidence workflow and coordination evidence lane
- compile-time generated domain-module ergonomics and zero-glue starter registration
- ranked projection benchmark and multi-instance coordination smoke evidence
- public-beta repo hygiene package, release checklist, and community templates

### Changed

- relation-heavy read recipes now favor summary/detail and ranked projection paths
- Kubernetes-style runtime coordination now uses instance-aware consumer names and leader leases for singleton-style loops

### Notes

- `cache-database` is currently positioned for public beta, not GA
- projection/read-model discipline remains part of the intended production design
