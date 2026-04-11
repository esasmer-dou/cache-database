# Contributing

Thanks for spending time on `cache-database`.

This project is optimized around two priorities:

1. keep production runtime overhead low
2. keep the library straightforward to adopt

Please keep both in mind when proposing changes.

## Before You Start

- read [README.md](README.md)
- read [docs/production-recipes.md](docs/production-recipes.md)
- read [docs/tuning-parameters.md](docs/tuning-parameters.md) if you are touching runtime behavior
- search existing issues and reports before opening a new one

## Good First Contribution Shapes

- docs clarifications
- benchmark/report improvements
- focused runtime fixes with measurable before/after evidence
- starter ergonomics that do not add runtime reflection or hidden work

## Development Workflow

1. Make a focused change.
2. Run the smallest relevant verification first.
3. If runtime behavior changes, capture evidence.
4. Update docs when behavior or recommended usage changes.

## Recommended Verification

Typical targeted checks:

```powershell
mvn -q -pl cachedb-starter -am test "-Dtest=LibraryApiErgonomicsTest"
mvn -q -pl cachedb-production-tests -am test "-Dtest=RepositoryRecipeBenchmarkSmokeTest"
./tools/ops/cluster/run-multi-instance-coordination-smoke.ps1
```

For release-facing changes, also use:

```powershell
./tools/ci/run-production-evidence.ps1
./tools/ci/run-multi-instance-coordination-evidence.ps1
./tools/ci/check-tr-docs.ps1
```

If you touch files under `tr/`, run the Turkish docs quality check before opening the PR. It catches common `ı/i`, `ü/u`, `ö/o`, and `ş/s` drift before review.

## Pull Requests

Please keep pull requests:

- small enough to review without re-learning the whole codebase
- explicit about tradeoffs and risk
- honest about what was and was not verified

If your change affects performance, include:

- what shape was expensive before
- what changed
- what evidence shows improvement

## Design Guidelines

- prefer compile-time generation over runtime reflection
- prefer explicit read-model shape over implicit relation loading
- prefer measurable evidence over intuition
- avoid silent behavior changes in public APIs
- preserve low-overhead defaults

## Commit and Review Hygiene

- separate refactors from behavior changes when you can
- do not bundle unrelated cleanup into performance work
- call out user-visible config changes in the PR description

## Security

Please do not open public issues for vulnerabilities. Use the process in
[SECURITY.md](SECURITY.md).
