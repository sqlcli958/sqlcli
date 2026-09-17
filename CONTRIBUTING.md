# Contributing to sql-cli

Thanks for helping improve sql-cli. The project combines Java backend/CLI code, a React Web UI, database integration code, and agent-facing schema workflows, so please keep changes focused and test the layer you touch.

## Development setup

Requirements:

- Java 17+
- Maven 3.9+
- Node.js 22+
- Docker only when running ClickHouse integration tests

Clone the repository and run the backend unit tests:

```bash
mvn test
```

Run the Web UI tests:

```bash
cd web
npm ci
npm test
npm run build
```

Build the complete executable JAR, including the Web UI:

```bash
mvn clean package
```

The output is `target/sql-cli.jar`.

ClickHouse integration tests are opt-in and require Docker:

```bash
mvn -Pit-clickhouse verify
```

## Before opening a pull request

1. Keep the change scoped to one problem or feature.
2. Add or update tests for behavior changes.
3. Update user-facing documentation when commands, configuration, security behavior, or agent workflows change.
4. Do not commit credentials, local database aliases, generated schema workspaces, IDE state, build output, or agent session state.
5. Prefer current command help (`--help`) and code behavior over stale roadmap documents when they disagree.

## Repository areas

- `src/main/java/com/sqlcli/` — CLI, SQL execution, schema graph, security, approval, and backend logic
- `src/test/` — Java tests
- `web/` — React Web UI
- `skills/sql-cli/` — agent-facing operating rules
- `docs/` — user, architecture, research, and development documentation
- `config/` — checked-in configuration templates and defaults

## Pull requests

A good pull request explains:

- the problem being solved;
- the behavior before and after the change;
- how the change was tested;
- any compatibility or security implications;
- documentation changes, when relevant.

Small, reviewable pull requests are preferred over unrelated changes bundled together.

## Bug reports and feature proposals

Use the repository issue templates. For bugs, include the operating system, Java version, database type/version when relevant, the exact command or workflow, and a minimal reproduction with secrets removed.

For security vulnerabilities, do not publish exploit details in a normal issue. Follow [SECURITY.md](SECURITY.md).
