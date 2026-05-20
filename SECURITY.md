# Security Policy

## Supported Versions

`cache-database` is currently in public-beta posture.

Security fixes are best-effort for:

- the latest `main` branch
- the latest published beta line, once public releases begin

Older snapshots may not receive fixes.

## Reporting a Vulnerability

Please do not file public GitHub issues for security problems.

Use GitHub private vulnerability reporting for this repository when it is
enabled. If that path is unavailable, contact the maintainers through the
private security contact configured for the repository administrators.

When reporting, include:

- affected module(s)
- impacted version or commit
- reproduction details
- whether the issue affects correctness, confidentiality, integrity, or
  availability
- any mitigation you already tested

We will acknowledge the report, assess severity, and work toward a fix and
coordinated disclosure plan.

## Security Expectations

This project relies heavily on Redis and PostgreSQL for correctness and
durability behavior. Production deployments should treat:

- Redis durability and failover posture
- credential management
- network policy
- access control
- private admin surfaces

as part of the security boundary, not just performance tuning.

## Admin UI Exposure

The Spring Boot admin HTTP surface is not published unless
`cachedb.admin.http-enabled=true` is configured explicitly.

Production deployments must keep `/cachedb-admin/**` behind a gateway,
operations network, or equivalent access-control boundary. Application-level
TLS is not mandatory when TLS terminates at the gateway or reverse proxy, but
direct public exposure is not an accepted production posture.

If gateway authentication is not used, enable CacheDB token auth:

```yaml
cachedb:
  admin:
    http-enabled: true
    auth-enabled: true
    auth-token: ${CACHEDB_ADMIN_TOKEN}
```
