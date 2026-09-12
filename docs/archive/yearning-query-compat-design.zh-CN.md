# Yearning 查询兼容设计

## 背景

部分数据库不能从本机或 Agent 环境直连，只能通过 Yearning 平台发起查询或创建工单执行查询。当前项目已经有一个独立脚本：

```text
docs/yearning-query
```

它可以通过 Yearning API 执行：

- `query`
- `tables`
- `schema`
- `test`
- `config`

但该 Python 脚本只是早期验证工具，后续需要废弃。正式实现应转为 Java 内置 Yearning HTTP 客户端，直接集成到 `sql-cli` 查询链路。

## 目标

让 `sql-cli` 查询入口可以同时支持两类数据源：

```text
direct jdbc datasource
  -> 本机直连数据库

yearning datasource
  -> 通过 Yearning 查询 API 访问数据库
```

当前阶段只做查询兼容。

## 非目标

当前阶段不做：

- 工单创建
- 工单审批
- 写 SQL 执行
- 图谱初始化
- 图谱元数据抽取
- 直接保存 Yearning JWT 到 alias 明文配置
- 继续维护 Python 脚本作为正式入口

## Alias 模型扩展

当前 alias 主要面向 JDBC。建议增加 `accessMode`：

```yaml
oracle-readonly-example:
  type: oracle
  accessMode: yearning
  yearningRef: oracle-readonly-example
  readonly: true
```

Yearning 连接配置单独保存：

```yaml
yearning:
  oracle-readonly-example:
    host: https://yearning.example.com
    source: oracle-readonly-example
  database: CITSONLINE
  tokenRef: keyring:yearning-oracle-readonly-example
```

字段含义：

| 字段 | 含义 |
|---|---|
| `accessMode` | `jdbc` 或 `yearning` |
| `yearningRef` | 指向 Yearning 配置 |
| `host` | Yearning 服务地址 |
| `source` | Yearning 数据源名 |
| `database` | 默认数据库或 schema |
| `tokenRef` | JWT/Token 密钥引用 |

## 命令设计

对用户保持一致入口，不暴露底层是 JDBC 还是 Yearning：

```bash
sql-cli <alias> "SELECT * FROM table WHERE id = 1"
sql-cli <alias> "SELECT * FROM table WHERE id = 1" -f json
```

如果 alias 是 `accessMode: yearning`，内部自动走 Yearning。用户和 Agent 不需要知道访问方式。

不新增对外命令：

```text
sql-cli <alias> yearning ...
```

调试也优先复用现有命令：

```bash
sql-cli <alias> test
sql-cli <alias> "SELECT 1"
```

## 查询执行流程

### SELECT 查询

```text
sql-cli <alias> "SELECT ..."
  -> AliasResolver
  -> accessMode == yearning
  -> YearningQueryExecutor
  -> POST /api/v2/query/results
  -> 标准化输出 table/json/csv
```

第一版只允许安全查询：

- `SELECT`
- `SHOW`
- `DESC`
- `EXPLAIN`

禁止直接通过普通查询入口执行：

- `INSERT`
- `UPDATE`
- `DELETE`
- `DROP`
- `ALTER`
- `TRUNCATE`

当前阶段直接拒绝，不创建工单。

## 组件设计

建议新增包：

```text
src/main/java/com/sqlcli/yearning/
  YearningConfig.java
  YearningClient.java
  YearningQueryExecutor.java
```

职责：

| 组件 | 职责 |
|---|---|
| `YearningConfig` | 保存 Yearning host/source/database/tokenRef |
| `YearningClient` | HTTP API 基础封装 |
| `YearningQueryExecutor` | 执行 SELECT 查询并转成标准结果 |

不新增：

| 组件 | 原因 |
|---|---|
| `YearningMetadataAccess` | 图谱暂不兼容 Yearning |
| `YearningTicketClient` | 工单暂不实现 |

## 配置和密钥

现有脚本使用：

```text
~/.yearning/config.json
```

该文件只用于迁移参考，不作为正式配置来源。

推荐：

```bash
sql-cli alias add oracle-readonly-example \
  --access-mode yearning \
  --yearning-host https://yearning.example.com \
  --yearning-source oracle-readonly-example \
  --yearning-database CITSONLINE \
  --secret-ref keyring:yearning-oracle-readonly-example

sql-cli oracle-readonly-example secret set
```

Token 只走：

- `keyring:*`
- `env:*`

不进入普通 alias 明文配置。

## 输出兼容

Yearning 查询结果需要转成现有 `sql-cli` 输出模型。

Yearning 返回：

```json
{
  "code": 1200,
  "payload": {
    "data": [],
    "title": [],
    "total": 0,
    "time": 0
  }
}
```

转换为：

```text
QueryResult
  columns
  rows
  affectedRows
  elapsedMs
```

这样 `-f table/json/csv`、Agent JSON 输出、后续图谱逻辑都不需要感知 Yearning。

## Agent 使用方式

Agent 不应该直接拼 Yearning API，而是统一调用：

```bash
sql-cli oracle-readonly-example "SELECT * FROM APP.ORDERS WHERE ROWNUM <= 10" -f json
```

Agent 不应该调用：

```text
yearning-query ...
sql-cli <alias> yearning ...
```

## 分阶段落地

### 当前阶段：查询兼容

1. 增加 `accessMode: yearning`
2. 增加 `YearningConfig` / `YearningClient`
3. 支持 `sql-cli <alias> "SELECT ..."` 走 Yearning
4. 支持 `sql-cli <alias> test`
5. 支持 `-f json/table/csv`
6. 非查询 SQL 直接拒绝

## 当前脚本的处理建议

`docs/yearning-query` 不再作为最终运行入口。后续 Java 实现完成后，该脚本标记为 deprecated，并从 skill 中移除。

建议迁移策略：

```text
docs/yearning-query
  -> deprecated，仅作为历史参考

src/main/java/com/sqlcli/yearning/*
  -> 正式查询实现

docs/README-yearning-query.md
  -> 更新为 sql-cli 集成用法
```

## 核心结论

Yearning 不再通过 Python 脚本调用，也不暴露独立命令，而是作为 `sql-cli` alias 的 Java 内置访问模式。

最终形态：

```text
alias 决定访问方式

accessMode=jdbc
  -> 直连数据库

accessMode=yearning
  -> Yearning 查询 API
```

当前阶段只支持查询，不支持图谱和工单。
