# Release Checklist

Use this checklist before the first public beta and for later releases.

## Repository Hygiene

- confirm [LICENSE](../LICENSE) matches the intended public licensing choice
- review [SECURITY.md](../SECURITY.md), [CONTRIBUTING.md](../CONTRIBUTING.md), [CODE_OF_CONDUCT.md](../CODE_OF_CONDUCT.md), and [SUPPORT.md](../SUPPORT.md)
- confirm issue templates and pull request template still match the current process

## Metadata

- replace placeholder repository metadata in [../pom.xml](../pom.xml):
  - `cachedb.release.projectUrl`
  - `cachedb.release.scmConnection`
  - `cachedb.release.scmDeveloperConnection`
  - `cachedb.release.issueUrl`
  - `cachedb.release.ciUrl`
  - maintainer identity fields
- confirm artifact coordinates and public package names

## Versioning

- move from `-SNAPSHOT` to the intended release version
- update [../CHANGELOG.md](../CHANGELOG.md)
- confirm public beta vs GA wording in docs
- after the release, reopen `main` to the next `-SNAPSHOT` version with [release-flow.md](release-flow.md)

## Evidence

- production evidence workflow is green
- coordination evidence workflow is green
- any new runtime hotspot changes have fresh benchmark or smoke evidence

## Publishing

- verify the `oss-release` Maven profile builds sources and javadocs
- confirm signing configuration and secrets for the chosen release channel
- confirm the target release workflow and credentials

## GitHub / Repo Settings

- enable private vulnerability reporting
- enable branch protection for the default branch
- enable required CI checks for evidence workflows
- confirm issue labels and milestones are ready for outside contributors

## Documentation

- README and Turkish README are current
- production recipe guidance is current
- any new tuning knobs are documented
- public beta limitations are explicit
