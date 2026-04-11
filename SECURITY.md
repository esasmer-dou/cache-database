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
