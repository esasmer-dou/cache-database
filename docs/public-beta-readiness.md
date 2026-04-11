# Public Beta Readiness

This document tracks what is already strong enough for a public beta and what
still blocks a GA-style announcement.

## Ready for Public Beta

- measured runtime evidence exists for the core production story
- multi-instance coordination has an official smoke and CI lane
- generated ergonomics stay near minimal repository overhead
- relation-heavy read recipes are documented and benchmarked
- English and Turkish product-facing docs exist

## Still Required Before Flipping the Repository Public

- finalize repository URL and maintainer metadata in [../pom.xml](../pom.xml)
- enable GitHub security reporting and branch protection
- decide whether the current beta license choice stays as-is
- verify the chosen release/publish channel and credentials

## Still Missing for GA

- longer soak and Redis HA failover evidence in repeatable release lanes
- stronger release engineering around signing and publication
- stable API compatibility policy and upgrade notes
- deeper operational stories for projection replay/backfill and incident drills

## Bottom Line

`cache-database` is in a strong public-beta posture. It is not yet positioned
for a no-caveats GA launch.
