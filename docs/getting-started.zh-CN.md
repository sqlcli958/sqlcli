# sql-cli 快速开始

这份文档用于从源码构建到第一次成功查询。所有命令、配置项和数据库差异请以[完整使用手册](user-manual.zh-CN.md)为准。

## 1. 环境要求

- Java 17+
- Maven 3.9+
- Node.js 22+（完整打包会把 Web UI 一起构建进可执行 JAR）

## 2. 构建

```bash
mvn clean package
```

可执行产物：

```text
target/sql-cli.jar
```

验证：

```bash
java -jar target/sql-cli.jar --help
```

## 3. 可选：安装到本机

```bash
sh sh/install.sh
sql-cli --help
```

在支持的类 Unix 环境中，安装脚本会把程序放到 `~/.sql-cli/` 并提供 `sql-cli` 命令。

## 4. 配置数据库

sql-cli 将数据库别名、JDBC 驱动和密码分开管理，主要配置位于 `config/`。

不要凭记忆猜参数，先看帮助：

```bash
sql-cli --help
sql-cli alias --help
sql-cli driver --help
```

典型流程：

```bash
sql-cli driver list
sql-cli alias add --help
sql-cli <alias> secret set
sql-cli <alias> test --json
```

外部 JDBC 驱动通过 driver 命令注册，可以统一放在 `drivers/` 下管理。

## 5. 第一次查询

```bash
sql-cli <alias> "SELECT 1" -f table
```

Agent 或脚本调用建议使用 JSON：

```bash
sql-cli <alias> "SELECT 1" -f json
```

## 6. 为 Agent 建立 Schema 上下文

先从数据库导入元数据：

```bash
sql-cli <alias> schema import --from-db
sql-cli <alias> schema stats
sql-cli <alias> schema validate
```

再通过图谱确认结构和语义：

```bash
sql-cli <alias> schema search "订单"
sql-cli <alias> schema describe <schema.table>
sql-cli <alias> schema path <schema.table_a> <schema.table_b>
```

具体 action 参数统一通过 `schema <action> --help` 获取。

## 7. 打开 Web UI

```bash
sql-cli ui --alias <alias>
```

Web UI 包含工作台、图谱、规则、评审和设置。审批与执行记录位于评审区域。

## 8. Agent 集成

仓库内置 [`sql-cli` Skill](../skills/sql-cli/SKILL.md)，用于约束 Agent 如何读取 Schema 上下文、安全执行 SQL、处理审批，并把有价值的业务语义写回图谱。

## 下一步

- [文档导航](README.md)
- [完整使用手册](user-manual.zh-CN.md)
- [参与贡献](../CONTRIBUTING.md)
