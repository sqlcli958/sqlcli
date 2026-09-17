# Release checklist

Use the `Release` GitHub Actions workflow for public releases.

1. Merge the intended release changes into `main`.
2. Ensure CI passes on `main`.
3. Open **Actions → Release → Run workflow**.
4. Enter a semantic version such as `v0.1.0`.
5. The workflow builds the full Java + Web UI package, runs the CLI smoke test, creates `sql-cli-<version>.jar`, generates a SHA-256 checksum, creates the Git tag, and publishes a GitHub Release.
6. Verify the release assets and release notes before promoting the release externally.

Do not publish a release from an unreviewed feature branch.
