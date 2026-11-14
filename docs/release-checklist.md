# Release Checklist

[Türkçe](../tr/docs/release-checklist.md)

Use this checklist to publish a numbered framework package. Production GA and a
consuming application's cutover are separate decisions; see the
[readiness contract](../PRODUCTION_GA_CRITERIA.md).

## 1. Version and Source

- Keep the parent, BOM, processor, runtime and provider modules at one stable version.
- Update the changelog, release notes and English/Turkish upgrade guidance.
- Identify the commit used to build the runtime separately from later documentation or publication-tool changes.
- Create and push an annotated tag without replacing an existing tag.

## 2. Existing Test Results

- Review correctness, compatibility, provider and performance results for the packaged runtime. Disclose failed targets and approved exceptions.
- Do not rebuild an unchanged SDK or repeat passed tests merely to publish files or assemble a new report.
- Changed runtime behavior still requires targeted tests. Missing results are not a pass. Preserve original failures.
- Private evidence upload and canonical record assembly are optional diagnostics, not package-publication prerequisites.

For 0.19.0, raw COUNT/FACET did not meet its 2x throughput target. The explicit
acceptance exception remains in the [release notes](releases/v0.19.0.md).
Existing Kubernetes results are reused; this is not a new full acceptance run.

## 3. Package Files

- Verify the expected binary, source and Javadoc JARs, POMs, BOM and SHA-256 checksums.
- Check that the distribution ZIP contains the same module/version set.
- The 0.19.0 public set has 63 JARs, 23 POMs and 175 files in total, including checksums, `SHA256SUMS` and the distribution ZIP.
- Never replace published artifacts. A changed binary requires a new version.

## 4. Publish

- Create a draft GitHub release; attach all files and verify uploaded names, sizes and SHA-256 digests before publication.
- Publish the release. The existing distribution workflows import the same artifacts into the public Maven repository and GitHub Packages without rebuilding.
- Verify actual workflow results and anonymous Maven access. A dispatch is not publication success.
- Ensure all three samples reference the published version and include matching English/Turkish dependency instructions.

## 5. Separate Deployment Approval

Package publication does not certify customer routes, a fresh performance result,
Oracle Data Guard/SQL Server Always On or universal Production GA. Run topology
and application checks when that claim or deployment is requested, not as an
implicit file-publication loop.
