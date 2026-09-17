# Getting started with sql-cli

This guide gets you from source checkout to a working local CLI. For every command and configuration option, see the [full Chinese user manual](user-manual.zh-CN.md).

## 1. Requirements

- Java 17+
- Maven 3.9+
- Node.js 22+ for a full package build (the Web UI is bundled into the executable JAR)

## 2. Build

```bash
mvn clean package
```

The executable artifact is:

```text
target/sql-cli.jar
```

Verify it:

```bash
java -jar target/sql-cli.jar --help
```

## 3. Optional local installation

```bash
sh sh/install.sh
sql-cli --help
```

The installer places sql-cli under `~/.sql-cli/` and makes the `sql-cli` command available on supported Unix-like systems.

## 4. Configure a database

sql-cli separates database aliases, JDBC drivers, and secrets. The main configuration lives under `config/`.

Start by inspecting the available commands instead of guessing parameters:

```bash
sql-cli --help
sql-cli alias --help
sql-cli driver --help
```

A typical flow is:

```bash
sql-cli driver list
sql-cli alias add --help
sql-cli <alias> secret set
sql-cli <alias> test --json
```

External JDBC driver JARs are registered through the driver commands and can be stored under `drivers/`.

## 5. Run a query

```bash
sql-cli <alias> "SELECT 1" -f table
```

For automation or agent use, JSON output is usually easier to consume:

```bash
sql-cli <alias> "SELECT 1" -f json
```

## 6. Build schema context for agents

Import database metadata into the local schema workspace:

```bash
sql-cli <alias> schema import --from-db
sql-cli <alias> schema stats
sql-cli <alias> schema validate
```

Then ground SQL generation in the graph:

```bash
sql-cli <alias> schema search "orders"
sql-cli <alias> schema describe <schema.table>
sql-cli <alias> schema path <schema.table_a> <schema.table_b>
```

Use `schema <action> --help` for the current parameters and examples.

## 7. Open the Web UI

```bash
sql-cli ui --alias <alias>
```

The Web UI contains workspace, graph, policy, review, and settings views. Approval and execution history are available from the review area.

## 8. Agent integration

The repository includes an [`sql-cli` Skill](../skills/sql-cli/SKILL.md). The skill defines how an agent should inspect schema context, execute SQL safely, handle approvals, and write useful semantics back to the graph.

## Next

- [Documentation index](README.md)
- [Full user manual (Chinese)](user-manual.zh-CN.md)
- [Contributing](../CONTRIBUTING.md)
