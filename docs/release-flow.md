# Stable Release Flow

Every stable release is built, tested, distributed, and verified from one
immutable tag.

## Release Sequence

1. Set the stable semantic version in the root and module POM files.
2. Update `CHANGELOG.md`, English/Turkish release notes, README files, and the
   two standalone samples.
3. Run the full reactor, provider integration, Docker HA, documentation,
   compatibility, benchmark, and release-artifact checks locally.
4. Commit and push `main`; wait for `Framework Readiness` and `Production
   Evidence` on that exact commit.
5. Create and push the annotated stable tag.
6. Wait for `Public Maven Repository Publish`; verify anonymous Maven
   resolution for the tag.
7. Publish GitHub Packages as a compatibility channel.
8. Create the non-prerelease GitHub Release with the ZIP, BOM, binary JARs, and
   checksums.
9. Run `Production GA Release Readiness` for the tag.
10. Build the standalone PostgreSQL and SQL Server samples against the remote,
    anonymous Maven repository; then tag and release both samples.

Do not rebuild or replace an existing version. A correction receives a new
semantic version.

## Next Development Version

After the release is verified, move `main` to the next `-SNAPSHOT` version only
when new development starts. Stable tags and published Maven paths remain
immutable.

## Branch Rule

`main` is the public integration branch. Use short-lived feature branches when
needed, delete them after merge, and never publish internal `codex/*` branches.
