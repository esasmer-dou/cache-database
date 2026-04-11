# Maven Central Publish Checklist

Use this checklist before the first Maven Central publication.

## Coordinates and Metadata

- confirm final `groupId`, `artifactId`, and version strategy
- confirm repository URL, SCM URL, issue URL, and CI URL in [../pom.xml](../pom.xml)
- replace placeholder maintainer identity fields in [../pom.xml](../pom.xml)
- confirm license choice is final

## Artifact Shape

- `oss-release` profile builds cleanly
- sources jars are attached
- javadocs jars are attached
- signing runs with real keys in CI or release environment

## Publishing Setup

- Sonatype Central account or target registry is ready
- namespace ownership is verified
- secrets are configured in GitHub Actions or release environment
- release workflow knows how to publish signed artifacts

## Versioning

- release version chosen
- `-SNAPSHOT` removed for the release commit
- [../CHANGELOG.md](../CHANGELOG.md) updated
- beta vs GA wording verified in docs and release notes

## Evidence

- production evidence workflow green
- coordination evidence workflow green
- public-beta readiness workflow green
- any recent performance-sensitive change has fresh benchmark evidence

## GitHub Release

- release notes prepared
- tag chosen
- release title chosen
- release artifacts uploaded if needed

## After Publish

- smoke-test dependency consumption from a fresh example project
- verify generated annotation-processor path works from a clean Maven cache
- restore next development version if using a release branch flow
