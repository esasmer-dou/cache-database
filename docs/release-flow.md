# Stable Release Flow

Every stable release uses one immutable SDK artifact set. The release tag
identifies its source; a reviewed source-equivalence record must explain any
documentation or validation-tool changes made after the SDK build.

## Release Sequence

1. Set the stable semantic version in the root and module POM files.
2. Update `CHANGELOG.md`, English/Turkish release notes, README files, and the
   three standalone samples.
3. Build and run the required reactor, provider, HA, documentation, API,
   benchmark, sample, and artifact checks in the approved Kubernetes environment.
   Preserve original results and artifact checksums; do not rebuild a frozen SDK
   or repeat passed scopes just to obtain a new aggregate log.
4. Complete canonical evidence verification. Keep original failures and link
   each permitted follow-up result explicitly. Missing evidence is a blocker,
   not a successful check.
5. Commit and push `main`; create the annotated stable tag on that exact commit.
   Complete the required remote evidence checks and pre-publication readiness
   check. A tag alone is not release approval.
6. Prepare a draft GitHub Release containing the verified ZIP, BOM, binary,
   source and Javadoc JARs, and checksums. Publish it as a stable release only
   after every required pre-publication check passes.
7. Run `Public Maven Repository Publish`. It imports the published release ZIP;
   it does not rebuild the SDK. Verify anonymous Maven resolution afterward.
8. Publish those same artifacts to GitHub Packages as a compatibility channel.
9. Run post-publication readiness checks. A framework package release does not
   certify an application's own HA topology or migration coverage.
10. In the approved Kubernetes environment, build the standalone PostgreSQL,
    SQL Server, and Oracle samples against the remote anonymous Maven repository.
    Then push their updated source, tags, and release assets.

Do not rebuild or replace an existing version. A correction receives a new
semantic version.

## Next Development Version

After the release is verified, move `main` to the next `-SNAPSHOT` version only
when new development starts. Stable tags and published Maven paths remain
immutable.

## Branch Rule

`main` is the public integration branch. Use short-lived feature branches when
needed, delete them after merge, and never publish internal `codex/*` branches.
