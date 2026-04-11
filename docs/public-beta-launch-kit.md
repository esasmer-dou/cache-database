# Public Beta Launch Kit

This page is the shortest operator-facing package for opening the repository to
outside users.

## GitHub About

### Name

`cache-database`

### Short description

`Redis-first Java persistence with async PostgreSQL write-behind and compile-time generated ORM-like APIs.`

### Website

Use the repository URL or the main docs landing page once a dedicated site
exists.

### Recommended topics

Use these GitHub topics:

- `java`
- `redis`
- `postgresql`
- `orm`
- `persistence`
- `write-behind`
- `annotation-processor`
- `spring-boot`
- `low-latency`
- `read-model`
- `projection`
- `cache`

## Public Beta Positioning

Recommended message:

`cache-database is a Redis-first persistence library for Java teams that care about production runtime overhead and still want an ORM-like developer experience.`

Recommended caveat:

`Public beta: projection/read-model discipline is part of the intended design, especially for relation-heavy and globally ranked screens.`

## First Public Release Title

`cache-database public beta`

Suggested first tag:

`v0.1.0-beta.1`

## First Public Release Notes

```md
## cache-database public beta

This is the first public beta release of `cache-database`.

### What CacheDB is

CacheDB is a Redis-first Java persistence library that keeps PostgreSQL as the
durable store through async write-behind. It is designed for teams that care
about low runtime overhead but still want a serious ORM-like developer
experience.

### What is already strong

- Redis-first read/write path with PostgreSQL durability
- compile-time generated entity metadata and generated ergonomic APIs
- Spring Boot starter plus plain Java bootstrap path
- projection/read-model guidance for relation-heavy screens
- ranked projection support for global sorted business views
- production evidence workflows and multi-instance coordination smoke coverage

### Public beta scope

CacheDB is ready for public beta use, not a no-caveats GA announcement.

The key design rule is explicit read-model shape:

- use generated module/binding surfaces first
- use projections and relation limits for relation-heavy list screens
- use ranked projections for global sorted/range-driven views
- move only measured hotspots down to direct repository usage

### Before production rollout

- read the production recipes
- read the tuning parameters
- run the production evidence workflow
- run the multi-instance coordination smoke for shared Redis/PostgreSQL setups

### Important note

For relation-heavy and globally ranked screens, projection/read-model discipline
is not optional in CacheDB. It is part of the intended production model.
```

## Launch Checklist

- repository visibility changed intentionally
- README and docs links verified
- release checklist reviewed
- placeholder maintainer fields in `pom.xml` updated
- security reporting enabled on GitHub
- branch protection enabled
- release artifacts built with `oss-release`
