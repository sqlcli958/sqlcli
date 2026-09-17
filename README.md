# sql-cli

面向 **AI Agent 与开发者** 的多数据库 SQL CLI / 数据库生命周期辅助工具。

`sql-cli` 基于 JDBC，为 MySQL、Oracle、PostgreSQL、ClickHouse 等数据库提供统一的查询与管理入口，并把 Schema 元数据、业务语义、关系图谱、SQL 安全执行、审批与审计能力放在同一个工作流里。

它的目标不是只做一个“能执行 SQL 的命令行工具”，而是让 Agent 和人工操作都尽量基于已确认的数据库结构与业务语义工作，减少猜表名、猜字段、猜 JOIN、猜枚举值带来的错误。

## 核心能力

| 能力 | 说明 |
|---|---|
| 多数据库访问 | 基于 JDBC，支持 MySQL、Oracle、PostgreSQL、ClickHouse 等数据库 |
| Agent 友好 | 提供 `sql-cli` Skill；查询前可先通过 Schema 图谱定位表、字段、关系和值域 |
| Schema 知识图谱 | 管理表/列元数据、业务名称、术语、值域、关系、路径与候选变更 |
| SQL 安全执行 | 统一执行入口、只读限制、写操作预检/审批、行数限制、超时与执行审计 |
| 数据恢复能力 | UPDATE / DELETE 执行前可生成恢复 SQL，并保留执行记录 |
| Web UI | 工作台、图谱、规则、评审、设置五个主要页面 |
| 敏感数据处理 | 支持密码安全存储与 SM4 列级加密/解密 |
| 多格式输出 | CSV、JSON、ASCII Table，方便人工和 Agent/脚本消费 |

## 快速开始

环境要求：**Java 17+**。

```bash
mvn clean package
java -jar target/sql-cli.jar --help
```

安装到本机后可直接使用：

```bash
sh sh/install.sh
sql-cli --help
```

配置数据库别名、驱动、密码以及完整命令说明，请直接查看使用文档，不在项目首页重复维护。

## Agent 集成

仓库提供面向 Agent 的 Skill：

- [sql-cli Skill](skills/sql-cli/SKILL.md)

Skill 会引导 Agent 在执行真实数据库查询前优先使用 Schema 图谱确认表结构、字段语义、关系与值域，并遵守项目的审批和安全执行约束。

## 文档

详细安装、配置、命令、数据库差异、图谱、规则、Web UI、测试与设计说明统一放在 `docs/`：

- [文档导航](docs/README.md)
- [完整使用手册](docs/user-manual.zh-CN.md)

> 路线图、研究文档和开发清单中可能包含尚未开放的能力。当前可用功能以代码实现和完整使用手册为准。

## 开源协议

本项目采用 [Apache License 2.0](LICENSE) 开源。

第三方依赖及数据库驱动仍分别遵循其各自的许可证条款。
