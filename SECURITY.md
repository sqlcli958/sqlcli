# Security policy

sql-cli can connect to real databases and may handle database credentials, sensitive query results, write operations, approval state, and recovery SQL. Security reports are therefore treated as high priority.

## Supported versions

The project is currently early-stage and does not yet maintain multiple supported release lines. Security fixes are applied to the latest maintained version on `main` and included in the next release.

## Reporting a vulnerability

Please do **not** publish exploit details, credentials, database connection strings, or sensitive production data in a normal public issue.

Preferred reporting path:

1. Use GitHub's private vulnerability reporting / Security Advisory flow for this repository when it is available.
2. If private reporting is not available, open a minimal public issue titled `Security contact request` without technical exploit details or secrets, so a private reporting channel can be established.

A useful report includes:

- affected sql-cli version or commit;
- affected operating system / Java version;
- affected database type when relevant;
- impact and attack prerequisites;
- minimal reproduction steps with all secrets and real data removed;
- whether the issue involves credential handling, SQL authorization, approval bypass, data exposure, Web UI access control, or recovery/audit data.

## Scope examples

Reports are especially useful for issues involving:

- credential or secret exposure;
- SQL approval or read-only bypass;
- unsafe SQL classification or execution boundaries;
- Web UI authentication/origin/revision protections;
- SM4 encryption/decryption behavior;
- sensitive information in logs or error messages;
- recovery SQL or audit-history data exposure;
- dependency vulnerabilities that are exploitable in sql-cli's runtime path.

Please avoid testing against databases or systems you do not own or have explicit authorization to test.
