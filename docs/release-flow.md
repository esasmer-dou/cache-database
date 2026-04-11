# Release Flow

Use this flow after each public beta release.

## Recommended cadence

1. ship the current beta release
2. validate the release asset and GitHub release page
3. open the next development cycle immediately
4. keep `main` on the next `-SNAPSHOT` version

## Start the next beta cycle

Run:

```powershell
./tools/release/start-next-beta-cycle.ps1 `
  -CurrentVersion 0.1.0-beta.1 `
  -NextVersion 0.1.0-beta.2-SNAPSHOT `
  -CreateReleaseNotesTemplate
```

This does three things:

- updates root and module `pom.xml` versions
- reopens `CHANGELOG.md` with an `Unreleased` section
- optionally creates the next release note template under `docs/releases/`

## Recommended branch flow

- `main` stays on the next `-SNAPSHOT` version
- short-lived work happens on `codex/*` branches
- beta release tags are created from the commit you actually want to ship

## Before cutting the next beta

- confirm production evidence workflows are green
- confirm coordination smoke is green
- confirm release notes are updated
- confirm `CHANGELOG.md` is ready to freeze
- confirm the release bundle is rebuilt from the intended commit

## Important note

Do not leave `main` pinned to the previous shipped beta version after a public release.
Open the next cycle immediately so dependency consumers and contributors can see where active development continues.
