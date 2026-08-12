# CacheDB Documentation Map

Turkish version: [tr/DOKUMAN_HARITASI.md](tr/DOKUMAN_HARITASI.md)

This page is the entry point for CacheDB documentation. It is organized by the
questions a new user or technical lead is likely to ask.

## First-Time Reader Path

Read in this order:

1. [README](README.md)
2. [PostgreSQL Sample](sample-cache-database-postgresql/README.md) or [MSSQL Sample](sample-cache-database-mssql/README.md)
3. [Getting Started](docs/getting-started.md)
4. [Declarative Repositories](docs/declarative-repositories.md)
5. [Concepts and Assumptions](docs/concepts-and-assumptions.md)
6. [Use Case Examples](docs/use-case-examples.md)
7. [Production Tuning Guide](docs/production-tuning-guide.md)

These seven documents should be enough for a new team to understand what CacheDB
is, how it is installed, how relation/projection decisions are made, and how
production tuning should be approached.

## Pick A Document By Question

| Question | Document |
| --- | --- |
| What is CacheDB, and what is it not? | [README](README.md) |
| How do I run a complete REST API sample? | [PostgreSQL Sample](sample-cache-database-postgresql/README.md) or [MSSQL Sample](sample-cache-database-mssql/README.md) |
| How do I add it to a new project? | [Getting Started](docs/getting-started.md) |
| How do I declare hot, source, warm, and command routes? | [Declarative Repositories](docs/declarative-repositories.md) |
| What changed during the ten framework UX hardening iterations? | [Framework UX Engineering Report](docs/framework-ux-10-iteration-report.md) |
| What changed during the second ten-iteration framework UX cycle? | [Second Framework UX Engineering Report](docs/framework-ux-second-10-iteration-report.md) |
| What changed during the third ten-iteration framework UX cycle? | [Third Framework UX Engineering Report](docs/framework-ux-third-10-iteration-report.md) |
| What changed during the fourth ten-iteration framework UX cycle? | [Fourth Framework UX Engineering Report](docs/framework-ux-fourth-10-iteration-report.md) |
| Do I need the Spring Boot JDBC starter? | [Spring Boot Starter](docs/spring-boot-starter.md) |
| What are entity, relation, projection, and route contract? | [Concepts and Assumptions](docs/concepts-and-assumptions.md) |
| How do insert, read, update, and delete work? | [Use Case Examples](docs/use-case-examples.md) |
| How do I model customer-order, invoice-payment, stock, or ticket cases? | [Use Case Examples](docs/use-case-examples.md) |
| How do I speed up relation-heavy screens? | [Production Recipes](docs/production-recipes.md) |
| How do I control Redis memory? | [Production Tuning Guide](docs/production-tuning-guide.md) |
| How do I refresh and reconcile a hot set periodically across multiple pods? | [Scheduled Warm and Hot-Set Reconciliation](docs/scheduled-warm.md) |
| Where are all config properties? | [Tuning Parameters](docs/tuning-parameters.md) |
| How do I migrate an existing SQL database + ORM app? | [Migration Planner](docs/migration-planner.md) |
| How do I keep Redis fresh when the source database changes outside CacheDB? | [Outbox and CDC Apply Runner](docs/outbox-cdc-apply-runner.md) |
| Can CacheDB support MSSQL or another SQL database? | [Database Provider SPI Direction](docs/database-provider-spi.md) |
| What must be proven before production? | [Production Test Report](docs/production-test-report.md) |
| Stable release or provider-specific production boundary? | [Production GA Criteria](PRODUCTION_GA_CRITERIA.md) and [Stable Release Launch Kit](docs/stable-release-launch-kit.md) |
| How do I decide whether a GA release can ship? | [Production GA Release Runbook](docs/production-ga-release-runbook.md) |
| How do I cut a release? | [Release Flow](docs/release-flow.md) |
| What is missing for Maven Central? | [Maven Central Publish Checklist](docs/maven-central-publish-checklist.md) |

## By Reader Type

### Application Developer

Start with:

- [Getting Started](docs/getting-started.md)
- [PostgreSQL Sample](sample-cache-database-postgresql/README.md) or [MSSQL Sample](sample-cache-database-mssql/README.md)
- [Use Case Examples](docs/use-case-examples.md)
- [Spring Boot Starter](docs/spring-boot-starter.md)

Goal: write entities, use repositories, and understand relation vs projection.

### Technical Lead Or Architect

Start with:

- [Concepts and Assumptions](docs/concepts-and-assumptions.md)
- [ORM Alternative Guide](docs/orm-alternative.md)
- [Production Recipes](docs/production-recipes.md)
- [Production Tuning Guide](docs/production-tuning-guide.md)

Goal: decide which routes fit CacheDB, where projection is mandatory, and what
the production risks are.

### Platform Or SRE Team

Start with:

- [Tuning Parameters](docs/tuning-parameters.md)
- [Production Test Report](docs/production-test-report.md)
- [Production Readiness Report](docs/production-readiness-report.md)
- [Final Production Go/No-Go Report](docs/final-production-go-no-go-report.md)

Goal: understand Redis, durable SQL providers, workers, leader lease, failover,
CI evidence, and observability boundaries.

### Team Migrating From An Existing ORM

Start with:

- [Migration Planner](docs/migration-planner.md)
- [Outbox and CDC Apply Runner](docs/outbox-cdc-apply-runner.md)
- [Use Case Examples](docs/use-case-examples.md)
- [Production Recipes](docs/production-recipes.md)
- [Production Tuning Guide](docs/production-tuning-guide.md)

Goal: discover source-database schema, choose hot routes, produce Redis warm
plans, compare against the source-database baseline, and make cutover decisions
with evidence.

## Document Roles

| Document | Role |
| --- | --- |
| [README](README.md) | Product positioning and first decision gate |
| [Getting Started](docs/getting-started.md) | First working setup |
| [Declarative Repositories](docs/declarative-repositories.md) | Preferred repository-first API, route contracts, warm, commands, tests, and migration |
| [Framework UX Engineering Report](docs/framework-ux-10-iteration-report.md) | Ten review, implementation, safety, performance, and verification iterations |
| [Second Framework UX Engineering Report](docs/framework-ux-second-10-iteration-report.md) | Query intent, window ergonomics, warm/durability APIs, route inventory, observability, and generated-path hardening |
| [Third Framework UX Engineering Report](docs/framework-ux-third-10-iteration-report.md) | Route population, typed warm/jobs, sample orchestration, diagnostics, and regression gates |
| [Fourth Framework UX Engineering Report](docs/framework-ux-fourth-10-iteration-report.md) | Contract-bound cursors, repository defaults, typed job progress, durable batches, and warm journey evidence |
| [Concepts and Assumptions](docs/concepts-and-assumptions.md) | Definitions, assumptions, and design boundaries |
| [Use Case Examples](docs/use-case-examples.md) | Real entity, query, update, delete, projection, and dashboard examples |
| [Production Tuning Guide](docs/production-tuning-guide.md) | Redis memory, hot policy, route contract, write-behind, Kubernetes tuning |
| [Tuning Parameters](docs/tuning-parameters.md) | Property reference and defaults |
| [Migration Planner](docs/migration-planner.md) | Migration flow from existing SQL database systems |
| [Outbox and CDC Apply Runner](docs/outbox-cdc-apply-runner.md) | External database changes, outbox/CDC events, cache-only apply behavior |
| [Scheduled Warm and Hot-Set Reconciliation](docs/scheduled-warm.md) | Declarative periodic warm, Redis lease, bounded waiting, and incremental cache cleanup |
| [Database Provider SPI Direction](docs/database-provider-spi.md) | Storage provider boundary for PostgreSQL, MSSQL, and future SQL dialects |
| [Production Recipes](docs/production-recipes.md) | BEST/ACCEPTABLE/ANTI-PATTERN production usage patterns |
| [Architecture](docs/architecture.md) | Internal architecture, data flow, registry, relation loading |
| [Production Test Report](docs/production-test-report.md) | Test evidence, smoke results, benchmark, certification lane |
| [Production Readiness Report](docs/production-readiness-report.md) | Current production maturity assessment |
| [Production GA Release Runbook](docs/production-ga-release-runbook.md) | Hard go/no-go flow for stable GA releases |
| [v0.8.0 Release Notes](docs/releases/v0.8.0.md) | Contract-bound cursors, repository defaults, typed warm/jobs, durable batches, and provider-equivalent samples |
| [v0.7.1 Release Notes](docs/releases/v0.7.1.md) | Clean-runner Maven plugin resolution and immutable sample consumption |
| [v0.7.0 Release Notes](docs/releases/v0.7.0.md) | Declarative repositories, explicit route coverage, safe updates, compile-time scheduled warm, test tooling, and provider-equivalent samples |
| [v0.6.0 Release Notes](docs/releases/v0.6.0.md) | Declarative domain, generated relations/projections/routes, durable jobs, and upgrade notes |
| [v0.5.0 Release Notes](docs/releases/v0.5.0.md) | Scheduled warm, reconciliation, validation evidence, and production boundaries |
| [Stable Release Launch Kit](docs/stable-release-launch-kit.md) | GitHub description, release message, stable release positioning |
| [Public Beta Launch Kit](docs/public-beta-launch-kit.md) | Historical public beta release positioning |
| [Release Checklist](docs/release-checklist.md) | Release readiness checklist |

## Documentation Quality Rule

Documentation should be treated as part of the product.

Rules:

- Avoid marketing claims that are not supported by evidence.
- Keep public docs aligned with actual implemented behavior.
- Explain production constraints directly.
- Keep code examples compile-oriented and consistent with annotation rules.
- Keep English and Turkish documentation aligned in structure and meaning.
- Do not add future roadmap promises to user-facing docs unless explicitly
  marked as non-GA or planned work.
