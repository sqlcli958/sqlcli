# ClickHouse 数据源接入设计与开发清单

本文是 ClickHouse 数据源接入的权威开发清单，同时作为后续接入其他数据库类型的范例。

最后审查日期：2026-06-16。

状态约定：

- `[ ]` 未开始
- `[~]` 开发中
- `[x]` 已完成
- `[!]` 阻塞或风险未消除

优先级：

- `P0`：接入范式和 ClickHouse 基础能力必须完成
- `P1`：元数据、图谱和真实可用性必须完成
- `P2`：生产化、性能和体验增强
- `P3`：后续数据库复用增强

## 1. 设计目标

ClickHouse 接入不能只做成一个特殊分支。它要沉淀成后续接入 SQL Server、SQLite、DM、TiDB、
OceanBase、Hive、Presto/Trino 等数据源时可复用的模式。

目标：

- 通过统一扩展点接入新数据库，避免在 CLI、连接、查询、元数据导入中散落 `if dbType`。
- 让每个数据库显式声明自身能力，例如事务、恢复 SQL、DDL、表清单、元数据导入、默认 LIMIT。
- 驱动加载沿用项目现有 `driverRef` / `jars` 配置机制。
- ClickHouse 首期至少完成连接测试、查询、表清单、DDL、图谱导入和只读保护。
- 对 ClickHouse 不支持或语义不同的能力给出明确禁用和错误提示，而不是让 JDBC 异常泄漏给用户。
- 将测试矩阵和验收命令固定下来，作为以后新增数据库类型时必须补齐的清单。

非目标：

- 首期不实现 ClickHouse Native TCP 协议，仅支持官方 JDBC HTTP/HTTPS。
- 首期不实现 ClickHouse mutation 的自动回滚。
- 首期不追求批量写入性能优化；大批量导入数据建议后续单独设计。

## 2. 官方事实基线

截至 2026-06-16 核实：

- Maven Central `com.clickhouse:clickhouse-jdbc` 最新 release 为 `0.9.8`。
- 官方文档推荐依赖 `com.clickhouse:clickhouse-jdbc:0.9.8:all`，即带 shaded dependencies 的 all classifier。
- Driver facade class 为 `com.clickhouse.jdbc.ClickHouseDriver`。
- V2 实现类为 `com.clickhouse.jdbc.Driver`，V1 实现类为 `com.clickhouse.jdbc.DriverV1`。
- URL 支持 `jdbc:ch:` 和 `jdbc:clickhouse:`。
- 常用 HTTP URL：`jdbc:ch:http://host:8123/database`。
- 常用 HTTPS URL：`jdbc:ch:https://host:8443/database?ssl=true&sslmode=strict`。
- V2 `DatabaseMetaData` 使用 Schema 表示 ClickHouse database，Catalog 预留。
- V2 `DatabaseMetaData.supportsTransactions()` 和 `supportsSavepoints()` 返回 `false`。
- V2 不支持多语句 SQL，不支持事务语义，不应复用当前恢复 SQL 的“先查原值再执行 DML”假设。

参考：

- ClickHouse JDBC 文档：https://clickhouse.com/docs/integrations/language-clients/java/jdbc
- Maven metadata：https://repo1.maven.org/maven2/com/clickhouse/clickhouse-jdbc/maven-metadata.xml
- Release 页面：https://github.com/ClickHouse/clickhouse-java/releases

## 3. 当前代码影响面

现有代码已有较好的起点，但扩展点还不够完整。

主要现状：

- `DatabaseStrategy` 已承载 URL 构建、连接属性、SQL 预处理、DDL 和 tables。
- `DatabaseStrategies` 是统一注册入口。
- `DriverResolver` 当前只覆盖 MySQL、Oracle、PostgreSQL 默认驱动。
- `JdbcUrlParser` 只识别 MySQL、Oracle、PostgreSQL。
- `QueryExecutor` 直接把 `UPDATE/DELETE` 送入恢复 SQL 流程，没有数据库能力判断。
- `WorkspaceMetadataExtractor` 通过 JDBC URL 自行探测类型，并在多个位置硬编码 MySQL/Oracle 分支。
- `AliasResolver` 对无 URL 的 alias 默认端口使用 `3306`，这对其他数据库不成立。
- `DatabaseConfig.params` 已经可以透传 JDBC 参数，也支持连接池参数覆盖，是 ClickHouse 连接参数的可用承载点。

需要避免的反模式：

- 不在 `QueryExecutor`、`WorkspaceMetadataExtractor`、CLI 命令中继续增加大量 `if clickhouse`。
- 不把表名拼接、schema/catalog 差异、系统库列表分散在多个类中。
- 不默认假设所有数据库都有事务、主键、外键、可回滚 DML、准确 affected rows。
- 不默认假设 JDBC `DatabaseMetaData` 对所有数据库都可靠。

## 4. 总体架构

### 4.1 数据源方言层

将当前 `DatabaseStrategy` 演进为“数据库方言统一入口”。ClickHouse 是第一个完整按新范式实现的方言。

建议接口能力：

```java
public interface DatabaseStrategy {
    String type();

    DatabaseCapabilities capabilities();

    DriverDefaults driverDefaults();

    String buildJdbcUrl(DatabaseConfig config);

    void applyConnectionProperties(DatabaseConfig config, Properties properties);

    String preprocessSql(DatabaseConfig config, String sql);

    String quoteIdentifier(String identifier);

    String qualifyTableName(String schemaName, String tableName);

    String getTableDdl(Connection conn, String tableName, String schemaName) throws SQLException;

    List<TableInfo> listTables(Connection conn, String schemaName, String pattern) throws SQLException;

    WorkspaceMetadataProvider createMetadataProvider(Connection conn, DatabaseConfig config) throws SQLException;

    SqlExecutionPolicy executionPolicy();
}
```

首期如果不一次性改完接口，也要至少新增能力声明接口，避免 ClickHouse DML 走错恢复 SQL。

### 4.2 能力声明

新增 `DatabaseCapabilities`，所有数据库都必须声明。

建议字段：

| 字段 | 含义 | ClickHouse 首期值 |
| --- | --- | --- |
| `supportsTransactions` | JDBC 事务是否可用 | `false` |
| `supportsSavepoints` | 保存点是否可用 | `false` |
| `supportsRecoverySql` | 是否允许当前 UPDATE/DELETE 恢复 SQL | `false` |
| `supportsStandardUpdateDelete` | 是否支持标准 UPDATE/DELETE | `false` |
| `supportsInsert` | 是否支持 INSERT | `true` |
| `supportsDdl` | 是否支持 DDL | `true` |
| `supportsShow` | 是否支持 SHOW 类语句 | `true` |
| `supportsMultipleStatements` | 是否支持多语句 | `false` |
| `supportsForeignKeys` | 元数据是否有声明外键 | `false` |
| `affectedRowsReliable` | DML affected rows 是否可靠 | `false` |
| `usesSchemaAsDatabase` | schema 是否表示数据库名 | `true` |
| `usesCatalogAsDatabase` | catalog 是否表示数据库名 | `false` |
| `prefersNativeMetadataQueries` | 是否优先系统表查询元数据 | `true` |
| `defaultPort` | 默认端口 | `8123` |
| `defaultSecurePort` | HTTPS 默认端口 | `8443` |
| `identifierQuote` | 标识符引用符 | `` ` `` |
| `defaultDatabase` | 默认库 | `default` |

后续新数据库必须先填写能力矩阵，再开始编码。

### 4.3 执行策略

新增 `SqlExecutionPolicy` 或等价方法，供 `QueryExecutor` 使用。

ClickHouse 首期策略：

- `SELECT`、`WITH`：执行查询，默认追加 `LIMIT defaultQueryLimit`。
- `SHOW`、`DESC`、`DESCRIBE`、`EXPLAIN`：执行查询，不自动追加 LIMIT，除非确认语法兼容。
- `INSERT`：非 readonly alias 可执行。
- `CREATE`、`ALTER`、`DROP`、`TRUNCATE`：非 readonly alias 可执行。
- 标准 `UPDATE`、`DELETE`：拒绝执行，提示 ClickHouse 不支持标准 DML，且当前恢复 SQL 不适用。
- `ALTER TABLE ... UPDATE/DELETE`：按 `ALTER` 处理，非 readonly alias 可执行，但必须提示“不生成恢复 SQL”。
- `MERGE`、`GRANT`、`REVOKE`：按能力声明决定，首期可保持 JDBC 执行但加测试覆盖。
- 多语句：默认拒绝。使用 `SqlStatementAnalyzer.splitStatements` 检测到多条时直接报错。

### 4.4 元数据提供者

引入 `WorkspaceMetadataProviderFactory`：

```java
WorkspaceMetadataProvider create(DatabaseConfig config, Connection conn)
```

不要再让 `WorkspaceMetadataExtractor` 从 URL 猜数据库类型。数据库类型应来自已解析的 `DatabaseConfig`，
连接元数据只能作为辅助校验。

ClickHouse 使用专用 `ClickHouseWorkspaceMetadataProvider`，优先查询系统表：

- database 列表：`system.databases`
- 表列表：`system.tables`
- 字段列表：`system.columns`
- 表 DDL：`SHOW CREATE TABLE`
- 表注释、engine、排序键、分区键：`system.tables`
- 字段注释、default、codec：`system.columns`
- 外键：无声明外键，直接跳过

系统库默认过滤：

- `system`
- `information_schema`
- `INFORMATION_SCHEMA`

表类型映射建议：

| ClickHouse 信息 | Graph 表类型 |
| --- | --- |
| ordinary table | `base_table` |
| View | `view` |
| MaterializedView | `view`，并在 attributes 中标记 materialized |
| Dictionary | `unknown` 或后续新增 `dictionary` |
| system database tables | `system_table` |

### 4.5 配置模型

驱动配置：

```yaml
driverDefaults:
  clickhouse: clickhouse09

drivers:
  clickhouse09:
    dbType: clickhouse
    driverClass: com.clickhouse.jdbc.ClickHouseDriver
    jars:
      - ./drivers/clickhouse/clickhouse-jdbc-0.9.8-all.jar
```

HTTP 明文连接：

```yaml
aliases:
  analytics-ch:
    dbType: clickhouse
    driverRef: clickhouse09
    jdbcUrl: jdbc:ch:http://clickhouse-host:8123/analytics
    username: readonly_user
    secretRef: keyring:analytics-ch
    readonly: true
    params:
      jdbc_ignore_unsupported_values: "true"
      socket_timeout: "300000"
      connection_timeout: "10000"
      defaultQueryLimit: "100"
      maximumPoolSize: "2"
```

HTTPS 连接：

```yaml
aliases:
  analytics-ch-cloud:
    dbType: clickhouse
    driverRef: clickhouse09
    jdbcUrl: jdbc:ch:https://host:8443/analytics
    username: default
    secretRef: encrypted:analytics-ch-cloud
    readonly: true
    params:
      ssl: "true"
      sslmode: "strict"
      jdbc_ignore_unsupported_values: "true"
      socket_timeout: "300000"
      connection_timeout: "10000"
```

无 `jdbcUrl` 的结构化配置：

```yaml
aliases:
  local-ch:
    dbType: clickhouse
    driverRef: clickhouse09
    host: localhost
    port: 8123
    database: default
    username: default
    secretRef: env:CLICKHOUSE_PASSWORD
    readonly: true
```

后续可增加 `protocol` 字段，但首期可以通过完整 `jdbcUrl` 覆盖。

### 4.6 连接池

当前项目使用 HikariCP 包装 `DriverBackedDataSource`。ClickHouse 官方文档说明底层 HTTP 实现自身也有连接复用，
因此 ClickHouse 默认池不宜过大。

建议默认：

- `maximumPoolSize: 2`
- `minimumIdle: 0`
- `connectionTimeoutMs: 10000`
- `idleTimeoutMs: 120000`
- `maxLifetimeMs: 600000`
- 大查询按 alias 参数调整 `socket_timeout`

连接属性合并顺序：

1. alias `params` 中的 JDBC 参数。
2. 方言默认参数。
3. 用户显式参数优先于默认参数。
4. 连接池参数不写入 JDBC URL，也不写入 JDBC properties。

当前 `DatabaseConfig.appendParamsToUrl` 需要进行 URL 参数编码，至少要覆盖空格、`&`、`=`、`#`。

## 5. 开发清单

### 5.1 P0：接入范式与基础连接

- [x] `CH-P0-001` 新增 `DatabaseCapabilities`，覆盖事务、恢复 SQL、DML、DDL、SHOW、多语句、元数据、外键、默认端口、标识符引用等能力。
- [x] `CH-P0-002` 为 MySQL、Oracle、PostgreSQL 补齐现有能力声明，确保新增能力不只服务 ClickHouse。
- [x] `CH-P0-003` 新增 `ClickHouseDatabaseStrategy`，注册到 `DatabaseStrategies`。
- [x] `CH-P0-004` `ClickHouseDatabaseStrategy#buildJdbcUrl` 支持 `host/port/database` 结构化配置，默认 `http`、`8123`、`default`。
- [x] `CH-P0-005` `DriverResolver` 支持 `clickhouse` 类型的 driverRef 解析和 dbType 校验。
- [x] `CH-P0-006` `config/settings.yaml` 增加 `driverDefaults.clickhouse` 和 `clickhouse09` driver 配置。
- [x] `CH-P0-007` 新建 `drivers/clickhouse/README.md`，说明下载 `clickhouse-jdbc-0.9.8-all.jar` 的来源和放置路径。
- [x] `CH-P0-008` `JdbcUrlParser` 识别 `jdbc:ch:` 和 `jdbc:clickhouse:`，可从 URL 推断 `dbType=clickhouse`。
- [x] `CH-P0-009` 修正 alias 无 URL 时的默认端口策略，不能在 `AliasResolver` 固定成 `3306`。
- [x] `CH-P0-010` `AliasCommand.validate` 对 ClickHouse 支持 `jdbcUrl` 或 `host/database` 两种配置方式。
- [x] `CH-P0-011` `DatabaseConfig.appendParamsToUrl` 对 URL 参数做编码，避免 ClickHouse 参数值含特殊字符时破坏 URL。
- [x] `CH-P0-012` `ConnectionManager.testConnectionDetailed` 对 ClickHouse 常见错误给出提示：HTTP/HTTPS 协议、8123/8443 端口、认证失败、SSL 配置、网络超时。

验收：

```bash
sql-cli driver list
sql-cli alias show analytics-ch
sql-cli analytics-ch test
sql-cli analytics-ch "SELECT version()"
```

### 5.2 P0：SQL 执行安全

- [x] `CH-P0-013` `QueryExecutor` 在执行前读取 `DatabaseCapabilities`，禁止不支持的语句进入错误流程。
- [x] `CH-P0-014` 对 ClickHouse 标准 `UPDATE`、`DELETE` 明确拒绝，错误信息说明不支持标准 DML，且当前恢复 SQL 不适用。
- [x] `CH-P0-015` 对 ClickHouse `ALTER TABLE ... UPDATE/DELETE` 允许在非 readonly alias 下执行，但明确不生成恢复 SQL。
- [x] `CH-P0-016` `executeUpdateWithRecovery` 仅在 `supportsRecoverySql=true` 时可进入。
- [x] `CH-P0-017` 多语句检测接入执行路径；ClickHouse 检测到多条语句时直接拒绝。
- [x] `CH-P0-018` `executeUpdate` / `executeInsert` 对 affected rows 为 `-1` 输出 `unknown`，避免误导。
- [x] `CH-P0-019` ClickHouse `SHOW`、`DESC`、`DESCRIBE`、`EXPLAIN` 按查询执行并覆盖测试。
- [x] `CH-P0-020` 默认 LIMIT 逻辑确认兼容 ClickHouse；`LIMIT BY`、`SETTINGS`、`FORMAT` 等场景不得错误追加 LIMIT。
- [x] `CH-P0-021` readonly 保护覆盖 `INSERT`、`CREATE`、`ALTER`、`DROP`、`TRUNCATE` 和 mutation。

验收：

```bash
sql-cli analytics-ch "SELECT number FROM system.numbers"
sql-cli analytics-ch "SHOW DATABASES"
sql-cli analytics-ch "DESCRIBE TABLE system.numbers"
sql-cli analytics-ch "EXPLAIN SELECT 1"
sql-cli analytics-ch "UPDATE t SET x = 1 WHERE id = 1"      # 必须拒绝
sql-cli analytics-ch "SELECT 1; SELECT 2"                  # 必须拒绝
```

### 5.3 P0：ClickHouse DDL 与表清单

- [x] `CH-P0-022` `ClickHouseDatabaseStrategy#getTableDdl` 使用 `SHOW CREATE TABLE`。
- [x] `CH-P0-023` DDL 表名支持 schema/database，未传 schema 时使用 alias database 或 `default`。
- [x] `CH-P0-024` 标识符引用使用反引号，并正确转义反引号。
- [x] `CH-P0-025` `ClickHouseDatabaseStrategy#listTables` 使用 `system.tables`，支持 schema/database 过滤。
- [x] `CH-P0-026` `listTables` 支持 pattern，使用大小写敏感或大小写不敏感规则需明确并测试。
- [x] `CH-P0-027` `listTables` 输出 engine、comment；如 `TableInfo` 不够承载，新增扩展字段或 attributes。
- [x] `CH-P0-028` `AliasActionCommand tables` 的默认 schema 逻辑从方言获取，不再只特殊处理 Oracle。

验收：

```bash
sql-cli analytics-ch tables
sql-cli analytics-ch tables --schema analytics
sql-cli analytics-ch tables --schema analytics --pattern "%event%"
sql-cli analytics-ch ddl events --schema analytics
```

### 5.4 P1：图谱元数据导入

- [x] `CH-P1-001` 新增 `WorkspaceMetadataProviderFactory`，通过 `DatabaseConfig.type` 选择 provider。
- [x] `CH-P1-002` 将当前 `WorkspaceMetadataExtractor` 改造成通用 JDBC provider，避免继续硬编码所有数据库差异。
- [x] `CH-P1-003` 新增 `ClickHouseWorkspaceMetadataProvider`。
- [x] `CH-P1-004` ClickHouse schema/database 发现使用 `system.databases`。
- [x] `CH-P1-005` ClickHouse 表发现使用 `system.tables`，记录 engine、comment、total_rows、metadata_modification_time。
- [x] `CH-P1-006` ClickHouse 字段发现使用 `system.columns`，记录 type、default_kind、default_expression、comment、codec_expression。
- [x] `CH-P1-007` ClickHouse 主键和排序键从 `system.tables.primary_key`、`sorting_key` 或 `system.columns.is_in_primary_key` 等可用字段提取；若版本差异导致字段不存在，要降级为空。
- [x] `CH-P1-008` ClickHouse 外键导入直接跳过，并在 ImportJob stats 中记录 `foreignKeysSkipped=true`。
- [x] `CH-P1-009` 系统库过滤支持 `system`、`information_schema`、`INFORMATION_SCHEMA`。
- [x] `CH-P1-010` `schema import --from-db --schema S` 对 ClickHouse 只导入指定 database。
- [x] `CH-P1-011` `schema import --from-db --table T` 支持 ClickHouse 表名过滤。
- [x] `CH-P1-012` ClickHouse 表注释和字段注释写入 `comment` 并始终刷新，不得覆盖 `description`（人工业务描述）。
- [x] `CH-P1-013` 抽取失败时错误信息包含 database、table 和 SQL，便于定位权限问题。

验收：

```bash
sql-cli analytics-ch schema import --from-db --schema analytics
sql-cli analytics-ch schema list
sql-cli analytics-ch schema tables --schema analytics
sql-cli analytics-ch schema show analytics.events
```

### 5.5 P1：配置、CLI 和文档体验

- [x] `CH-P1-014` `README.md` 增加 ClickHouse alias 示例。
- [x] `CH-P1-015` `config/README.md` 增加 `./drivers/clickhouse/` 目录说明。
- [~] `CH-P1-016` `AGENTS.md` 或项目工作说明中补充 ClickHouse 常用命令。
- [~] `CH-P1-017` `sql-cli driver add` 示例覆盖 ClickHouse。
- [~] `CH-P1-018` `sql-cli alias add` 示例覆盖 ClickHouse。
- [~] `CH-P1-019` `sql-cli list` 和 `alias show` 输出不泄漏密码和 URL 中的敏感参数。
- [~] `CH-P1-020` 如果 URL 中含 `password=`，输出时必须脱敏；建议文档要求密码只走 `secretRef`。
- [~] `CH-P1-021` 增加 `--param key=value` 或等价能力，便于 CLI 创建 alias 时写入 params；若首期不做，文档明确只能手改 YAML。

### 5.6 P1：测试矩阵

- [x] `CH-P1-022` `ClickHouseDatabaseStrategyTest` 覆盖 URL 构建、标识符引用、表名限定、默认 LIMIT。
- [x] `CH-P1-023` `JdbcUrlParserTest` 覆盖 `jdbc:ch:`、`jdbc:clickhouse:`、HTTP、HTTPS。
- [x] `CH-P1-024` `DriverResolverTest` 覆盖 clickhouse 默认驱动和 driverRef/dbType 不匹配。
- [x] `CH-P1-025` `QueryExecutorClickHousePolicyTest` 覆盖标准 UPDATE/DELETE 拒绝、ALTER mutation、readonly、多语句。
- [x] `CH-P1-026` `ClickHouseWorkspaceMetadataProviderTest` 使用 fake ResultSet 或轻量测试替身覆盖系统表解析。
- [x] `CH-P1-027` 增加 Testcontainers ClickHouse 集成测试 profile，默认本地可跳过，CI 可按 profile 开启。
- [x] `CH-P1-028` 集成测试覆盖 `test`、`SELECT`、`SHOW`、`DESCRIBE`、`tables`、`ddl`、`schema import`。
      **注意：这些 `*IT.java` 从未真正跑过**——本机没有 Docker，只验证到能对着真实
      testcontainers API 编译通过（`mvn -Pit-clickhouse test-compile`）。真正的容器启动与
      断言要等 CI 跑 `mvn -Pit-clickhouse verify`。默认构建完全不受影响（依赖只在 profile 内，
      且 `*IT.java` 默认从 testCompile 排除）。
- [x] `CH-P1-029` 增加 HTTPS/SSL 参数构造测试，不要求真实证书环境。
- [x] `CH-P1-030` 增加大结果集 smoke 测试，确认默认 LIMIT 生效。

建议集成测试数据：

```sql
CREATE DATABASE IF NOT EXISTS sqlcli_test;

CREATE TABLE sqlcli_test.events
(
    id UInt64,
    user_id UInt64,
    event_name String COMMENT '事件名',
    event_time DateTime,
    amount Decimal(18, 2) DEFAULT 0,
    tags Array(String)
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(event_time)
ORDER BY (event_time, id)
COMMENT '事件明细表';

CREATE VIEW sqlcli_test.event_view AS
SELECT id, event_name FROM sqlcli_test.events;
```

### 5.7 P2：生产化

- [x] `CH-P2-001` 查询渲染支持流式输出或最大行数保护，避免 ClickHouse 大结果集一次性进入内存。（已并入主线查询上限：`task/EffectiveLimits` 解析 maxRows，`task/JdbcBackend` 读路径 `setMaxRows(maxRows+1)` 并探测截断，非流式但有硬上限保护）
- [x] `CH-P2-002` 对 `FORMAT` 子句做 LIMIT 兼容处理，避免在 `FORMAT JSON` 后追加 LIMIT。
- [x] `CH-P2-003` 支持 ClickHouse `SETTINGS` 子句识别，默认 LIMIT 应放在合法位置。
- [~] `CH-P2-004` 支持 `default_query_settings` 参数文档和示例。
- [x] `CH-P2-005` 支持 `client_name` 默认值，例如 `sql-cli/{version}`，便于 ClickHouse query log 追踪。
- [x] `CH-P2-006` 连接测试输出 server version、current database、readonly 状态。
- [x] `CH-P2-007` schema import 记录 ClickHouse engine、partition key、sorting key、primary key、TTL 到 attributes。
- [x] `CH-P2-008` 支持 Materialized View 与源表依赖关系识别，形成 `depends_on` 或 lineage 关系。
      **2026-08-27 落地说明：写成表 `attributes.depends_on_table`，不是关系边也不是 lineage。**
      现有三种 `RelationType` 的端点规则都要求列级端点，表→表的边会被 `RelationValidator`
      判为 error 直接中止导入；`LineageRecord` 同样是列形状。沿用 CH-P2-007 已有的 attributes 形态。
      解析只认主 `FROM`（不处理 JOIN / 子查询 / UNION），每 schema 一次查询带缓存。
- [x] `CH-P2-009` 对权限不足的系统表查询提供降级路径，例如 JDBC metadata 或 `SHOW TABLES`。
- [ ] `CH-P2-010` 增加性能基线：1 万表、20 万字段元数据导入耗时和内存。
- [x] `CH-P2-011` 发布包中附带 driver 下载脚本或校验说明，至少包含 SHA256 记录流程。

### 5.8 P3：抽象复用到后续数据库

- [x] `DB-P3-001` 为新增数据库编写统一模板：能力矩阵、驱动配置、URL 示例、metadata provider、测试矩阵。
- [x] `DB-P3-002` 建立 `DatabaseStrategyContractTest`，所有 Strategy 必须通过同一组契约测试。（`src/test/java/com/sqlcli/strategy/DatabaseStrategyContractTest.java`，MySQL/Oracle/PostgreSQL/ClickHouse 参数化过同一组契约：类型注册、能力自洽、policy 与 capabilities 一致、标识符转义、URL 构建、默认 LIMIT 只加一次、连接提示非空；顺带修正 Oracle/PostgreSQL 的 identifierQuote 从 `'` 改为 `"`）
- [x] `DB-P3-003` 建立 `WorkspaceMetadataProviderContractTest`，所有 Provider 必须通过 schema/table/column 基础契约。（`src/test/java/com/sqlcli/graph/workspace/WorkspaceMetadataProviderContractTest.java`，覆盖类型声明、系统 schema 识别、null 安全、外键/索引能力开关、close 幂等；抓出并修复 `WorkspaceMetadataExtractor.isSystemSchema` 的 NPE。真实抽取契约由 Testcontainers 集成测试补充）
- [x] `DB-P3-004` 建立错误提示规范：驱动缺失、URL 错误、认证失败、网络不通、权限不足、SQL 不支持。
- [x] `DB-P3-005` 建立 `docs/datasource-integration-template.zh-CN.md`，以后新数据库从模板复制。
- [x] `DB-P3-006` 把系统 schema 识别、标识符引用、表名限定、LIKE pattern 转义抽为可复用组件。
      （标识符引用/表名限定此前已经是 `DatabaseStrategy.quoteIdentifier`/`qualifyTableName`，
      由 `DatabaseStrategyContractTest` 保证各方言一致；新增 `SystemSchemas`
      作为系统 schema 识别与 LIKE pattern 转义的唯一实现——
      `WorkspaceMetadataProvider` 默认方法、`WorkspaceMetadataExtractor` 的重复覆写、
      ClickHouse provider 的独立常量三处已合并，顺带修掉了默认方法里 Oracle
      清单比 Extractor 少一半的漂移）
- [x] `DB-P3-007` 为不同数据库的 schema/catalog/database 概念建立统一术语说明。
- [x] `DB-P3-008` 对恢复 SQL 能力单独建模，避免新数据库默认继承 MySQL/Oracle 假设。（首期仅做能力声明侧：`DatabaseCapabilities.supportsRecoverySql` + `SqlExecutionPolicy.generateRecoverySql` 独立建模，ClickHouse 声明为 false；`JdbcBackend.executeWithRecovery` 按能力门控并给出中文拒绝理由，标准 UPDATE/DELETE 的拒绝信息也改为中文说明 mutation 替代方案。完整的恢复执行新设计留待主线）

## 6. 验收矩阵

### 6.1 最小可用验收

| 场景 | 命令 | 预期 |
| --- | --- | --- |
| 连接测试 | `sql-cli analytics-ch test` | 成功，失败时给出 CK 专属提示 |
| 版本查询 | `sql-cli analytics-ch "SELECT version()"` | 返回版本 |
| 默认 LIMIT | `sql-cli analytics-ch "SELECT number FROM system.numbers"` | 默认限制行数 |
| 显式 LIMIT | `sql-cli analytics-ch "SELECT number FROM system.numbers LIMIT 5"` | 不重复追加 LIMIT |
| SHOW | `sql-cli analytics-ch "SHOW DATABASES"` | 正常输出 |
| DESCRIBE | `sql-cli analytics-ch "DESCRIBE TABLE system.numbers"` | 正常输出 |
| 表清单 | `sql-cli analytics-ch tables --schema system` | 输出表 |
| DDL | `sql-cli analytics-ch ddl numbers --schema system` | 输出 CREATE 语句 |
| 只读保护 | `sql-cli analytics-ch "DROP TABLE t"` | readonly alias 拒绝 |
| 标准 DML | `sql-cli analytics-ch "UPDATE t SET x=1"` | 明确拒绝 |
| 多语句 | `sql-cli analytics-ch "SELECT 1; SELECT 2"` | 明确拒绝 |

### 6.2 图谱验收

| 场景 | 预期 |
| --- | --- |
| 导入指定 database | 只生成目标 database 的 schema/table/column |
| 系统库过滤 | 默认不导入 system 和 information_schema |
| 表注释 | ClickHouse comment 写入 `comment` 字段，重导入刷新 |
| 人工描述保留 | 重新导入后 `description`、`businessName` 不被覆盖 |
| 字段类型 | Array、Nullable、Decimal、DateTime 等 raw type 保留 |
| 主键排序键 | 可用时写入 primary/sorting key 属性 |
| 外键 | 明确跳过，不生成错误关系 |

### 6.3 回归验收

ClickHouse 接入不能破坏已有数据库：

- MySQL `tables` 和 `ddl` 仍可用。
- Oracle 默认 schema 仍为 username。
- PostgreSQL DDL 生成仍可用。
- SM4 SQL 改写不因 ClickHouse 变更而影响原有测试。
- Yearning access mode 不被 ClickHouse 策略影响。
- `mvn test` 全量通过。

## 7. 风险与决策

### 7.1 是否使用 V1 驱动

决策：默认使用 `ClickHouseDriver` facade 的 V2 实现，不启用 V1。

原因：

- V2 是官方当前默认实现。
- V1 的 fake transaction 和标准 UPDATE/DELETE 支持不应成为 sql-cli 的默认语义。
- 本项目更需要清晰能力边界，而不是用兼容层掩盖 ClickHouse 与关系型数据库的差异。

如果用户确实需要 V1，应通过单独 driverRef 和明确文档配置，不作为默认。

### 7.2 是否支持 ClickHouse UPDATE/DELETE

决策：首期拒绝标准 `UPDATE/DELETE`，允许非 readonly 下的 `ALTER TABLE ... UPDATE/DELETE`。

原因：

- ClickHouse mutation 是异步变更语义，和当前恢复 SQL 的同步 DML 假设不同。
- 当前恢复 SQL 生成依赖先查原始行再生成反向 UPDATE/INSERT，不适合 ClickHouse 大表 mutation。
- 错误允许比明确拒绝风险更高。

### 7.3 是否使用 JDBC DatabaseMetaData

决策：ClickHouse 元数据优先使用系统表，不依赖 JDBC metadata。

原因：

- 官方说明 V2 使用 Schema 表示 database，Catalog 语义特殊。
- 系统表能提供 engine、排序键、分区键等 ClickHouse 关键信息。
- 后续图谱需要比通用 JDBC metadata 更丰富的结构。

## 8. 新数据库接入模板

以后新增数据库必须按以下顺序推进：

1. 填写官方事实基线：驱动、版本、URL、事务、schema/catalog、DDL、metadata、DML 限制。
2. 填写 `DatabaseCapabilities`。
3. 新增 `XxxDatabaseStrategy`。
4. 新增或复用 `WorkspaceMetadataProvider`。
5. 增加 driverDefaults 和配置示例。
6. 增加 URL 推断。
7. 接入 `QueryExecutor` 能力判断。
8. 补齐 DDL、tables、schema import。
9. 补齐单元测试、契约测试、集成测试。
10. 更新 README、config README、技能/AGENTS 文档。
11. 执行最小可用验收、图谱验收和回归验收。

任何新数据库如果无法完成第 1 步和第 2 步，不应进入编码阶段。

## 9. 建议实施顺序

推荐按以下顺序开发，减少返工：

1. 先做 `DatabaseCapabilities` 和 ClickHouse Strategy 空实现。
2. 接入 driver 配置、URL 推断、连接测试。
3. 改造 `QueryExecutor`，先把不支持的 SQL 拦住。
4. 实现 `tables` 和 `ddl`。
5. 引入 metadata provider factory。
6. 实现 ClickHouse metadata provider。
7. 补齐测试矩阵。
8. 更新 README、config README 和示例配置。
9. 做 Testcontainers 集成验证。
10. 再考虑 P2 的流式输出、Materialized View 依赖和性能优化。
