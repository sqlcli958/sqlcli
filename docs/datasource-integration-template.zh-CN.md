# 数据源接入模板

本文是新增数据库类型接入 sql-cli 的标准模板。以后每接入一种新数据库，必须从此模板复制，
逐项填写并作为开发清单跟踪。ClickHouse 接入清单（`clickhouse-datasource-integration-checklist.zh-CN.md`）
是本模板的首次实例化，可作为参考范例。

最后更新日期：2026-06-19。

> **本文里的未勾选方框是模板占位，不是待办。** 接新数据库时整份复制过去再逐项打勾；
> 当前排期见 [dev-checklist-2026-08](dev-checklist-2026-08.zh-CN.md)。

状态约定：

- `[ ]` 未开始
- `[~]` 开发中
- `[x]` 已完成
- `[!]` 阻塞或风险未消除

---

## 1. 官方事实基线

> 任何新数据库如果无法完成本节，不应进入编码阶段。

### 1.1 驱动信息

| 项目 | 值 |
| --- | --- |
| 数据库类型（dbType） | `{dbType}` |
| JDBC 驱动 Maven 坐标 | `{groupId}:{artifactId}:{version}` |
| 是否需要 shaded/all classifier | `{是/否，说明原因}` |
| Driver class | `{driverClassName}` |
| URL 前缀 | `{jdbc:xxx:}` |
| 官方文档链接 | `{URL}` |
| Maven Central 链接 | `{URL}` |
| Release 页面 | `{URL}` |
| 核实日期 | `{YYYY-MM-DD}` |

### 1.2 URL 格式

列出所有支持的 URL 格式：

```
# 基本格式
jdbc:xxx://host:port/database

# 带参数格式
jdbc:xxx://host:port/database?key=value&key2=value2

# 其他格式（如 SID、SERVICE_NAME 等）
jdbc:xxx:@host:port:sid
```

### 1.3 事务支持

| 项目 | 值 |
| --- | --- |
| JDBC `supportsTransactions()` | `{true/false}` |
| JDBC `supportsSavepoints()` | `{true/false}` |
| 事务隔离级别 | `{说明}` |
| 自动提交行为 | `{说明}` |
| XA 支持 | `{是/否}` |

### 1.4 Schema/Catalog 语义

| 项目 | 值 |
| --- | --- |
| JDBC Schema 含义 | `{对应数据库中的什么概念}` |
| JDBC Catalog 含义 | `{对应数据库中的什么概念}` |
| Schema 等价于数据库名？ | `{是/否}` |
| Catalog 等价于数据库名？ | `{是/否}` |
| 默认 Schema/Catalog | `{值}` |

### 1.5 DDL 获取方式

| 项目 | 值 |
| --- | --- |
| 获取表 DDL 的 SQL | `{SQL 语句}` |
| 是否需要 Schema 参数 | `{是/否}` |
| DDL 输出格式 | `{说明}` |
| 特殊注意事项 | `{说明}` |

### 1.6 元数据获取方式

| 项目 | 值 |
| --- | --- |
| JDBC `DatabaseMetaData` 可靠性 | `{可靠/部分可靠/不可靠}` |
| 是否优先使用系统表查询 | `{是/否}` |
| 数据库/Schema 列表查询 | `{SQL 或方法}` |
| 表列表查询 | `{SQL 或方法}` |
| 字段列表查询 | `{SQL 或方法}` |
| 主键查询 | `{SQL 或方法}` |
| 外键查询 | `{SQL 或方法}` |
| 索引查询 | `{SQL 或方法}` |
| 表注释来源 | `{SQL 或方法}` |
| 字段注释来源 | `{SQL 或方法}` |

### 1.7 DML 限制

| 项目 | 值 |
| --- | --- |
| 标准 UPDATE 支持 | `{是/否，说明限制}` |
| 标准 DELETE 支持 | `{是/否，说明限制}` |
| INSERT 支持 | `{是/否，说明限制}` |
| 批量写入支持 | `{是/否，说明限制}` |
| affected rows 可靠性 | `{可靠/不可靠/部分可靠}` |
| 多语句支持 | `{是/否}` |
| 特殊 DML 语法 | `{说明，如 ClickHouse 的 ALTER TABLE ... UPDATE}` |

---

## 2. 能力矩阵

> 填写 `DatabaseCapabilities` 各字段值。所有字段必须明确填写，不允许使用默认假设。

| 字段 | 含义 | `{dbType}` 值 | 说明 |
| --- | --- | --- | --- |
| `supportsTransactions` | JDBC 事务是否可用 | `{true/false}` | |
| `supportsSavepoints` | 保存点是否可用 | `{true/false}` | |
| `supportsRecoverySql` | 是否允许当前 UPDATE/DELETE 恢复 SQL | `{true/false}` | |
| `supportsStandardUpdateDelete` | 是否支持标准 UPDATE/DELETE | `{true/false}` | |
| `supportsInsert` | 是否支持 INSERT | `{true/false}` | |
| `supportsDdl` | 是否支持 DDL | `{true/false}` | |
| `supportsShow` | 是否支持 SHOW 类语句 | `{true/false}` | |
| `supportsMultipleStatements` | 是否支持多语句 | `{true/false}` | |
| `supportsForeignKeys` | 元数据是否有声明外键 | `{true/false}` | |
| `affectedRowsReliable` | DML affected rows 是否可靠 | `{true/false}` | |
| `usesSchemaAsDatabase` | schema 是否表示数据库名 | `{true/false}` | |
| `usesCatalogAsDatabase` | catalog 是否表示数据库名 | `{true/false}` | |
| `prefersNativeMetadataQueries` | 是否优先系统表查询元数据 | `{true/false}` | |
| `defaultPort` | 默认端口 | `{port}` | |
| `defaultSecurePort` | HTTPS/SSL 默认端口 | `{port}` | |
| `identifierQuote` | 标识符引用符 | `` ` `` 或 `"` 或 `'` | |
| `defaultDatabase` | 默认库 | `{name 或 null}` | |

---

## 3. 执行策略

> 填写 `SqlExecutionPolicy` 各字段值。

| 字段 | 含义 | `{dbType}` 值 | 说明 |
| --- | --- | --- | --- |
| `allowStandardUpdate` | 是否允许标准 UPDATE | `{true/false}` | |
| `allowStandardDelete` | 是否允许标准 DELETE | `{true/false}` | |
| `allowAlterMutation` | 是否允许 ALTER 变更 | `{true/false}` | |
| `allowInsert` | 是否允许 INSERT | `{true/false}` | |
| `allowDdl` | 是否允许 DDL | `{true/false}` | |
| `allowShow` | 是否允许 SHOW | `{true/false}` | |
| `allowMultipleStatements` | 是否允许多语句 | `{true/false}` | |
| `generateRecoverySql` | 是否生成恢复 SQL | `{true/false}` | |
| `requireReadonlyGuard` | 是否需要只读保护 | `{true/false}` | |
| `unsupportedUpdateMessage` | UPDATE 不支持时的提示 | `{message 或 null}` | |
| `unsupportedDeleteMessage` | DELETE 不支持时的提示 | `{message 或 null}` | |

### 3.1 SQL 类型处理规则

逐项说明每种 SQL 类型的处理方式：

| SQL 类型 | 处理方式 | 备注 |
| --- | --- | --- |
| SELECT / WITH | `{执行查询/追加 LIMIT/...}` | |
| SHOW / DESC / DESCRIBE | `{执行查询/拒绝/...}` | |
| EXPLAIN | `{执行查询/拒绝/...}` | |
| INSERT | `{允许/拒绝/只读保护}` | |
| UPDATE | `{允许/拒绝/提示替代语法}` | |
| DELETE | `{允许/拒绝/提示替代语法}` | |
| CREATE | `{允许/拒绝/只读保护}` | |
| ALTER | `{允许/拒绝/只读保护}` | |
| DROP / TRUNCATE | `{允许/拒绝/只读保护}` | |
| MERGE | `{允许/拒绝/...}` | |
| GRANT / REVOKE | `{允许/拒绝/...}` | |
| 多语句 | `{允许/拒绝/检测后拒绝}` | |

### 3.2 默认 LIMIT 策略

| 项目 | 值 |
| --- | --- |
| 是否自动追加 LIMIT | `{是/否}` |
| 默认 LIMIT 值来源 | `{config/defaultQueryLimit}` |
| 已有 LIMIT 时是否跳过 | `{是/否}` |
| 特殊子句兼容（如 FORMAT、SETTINGS） | `{说明}` |
| LIMIT 位置规则 | `{说明}` |

---

## 4. 实现清单

### 4.1 DatabaseStrategy 实现

- [ ] `{DBType}DatabaseStrategy` 继承 `AbstractDatabaseStrategy`
- [ ] `type()` 返回 `"{dbType}"`
- [ ] `capabilities()` 返回第 2 节定义的 `DatabaseCapabilities`
- [ ] `executionPolicy()` 返回第 3 节定义的 `SqlExecutionPolicy`
- [ ] `buildJdbcUrl()` 支持 `host/port/database` 结构化配置
- [ ] `applyConnectionProperties()` 设置方言默认连接属性
- [ ] `preprocessSql()` 实现第 3.2 节的 LIMIT 策略
- [ ] `getTableDdl()` 实现第 1.5 节的 DDL 获取
- [ ] `listTables()` 实现第 1.6 节的表列表获取
- [ ] `quoteIdentifier()` 使用正确的标识符引用符
- [ ] `qualifyTableName()` 使用正确的 schema/table 限定格式
- [ ] `buildConnectionHints()` 提供方言专属连接错误提示
- [ ] 注册到 `DatabaseStrategies`

### 4.2 WorkspaceMetadataProvider 实现

- [ ] `{DBType}WorkspaceMetadataProvider` 实现 `WorkspaceMetadataProvider` 接口
- [ ] Schema/Database 发现（第 1.6 节）
- [ ] 表发现（第 1.6 节）
- [ ] 字段发现（第 1.6 节）
- [ ] 主键发现（第 1.6 节）
- [ ] 外键发现（第 1.6 节，如不支持则跳过并记录）
- [ ] 表注释获取
- [ ] 字段注释获取
- [ ] 系统库过滤列表
- [ ] 表类型映射规则
- [ ] 注册到 `WorkspaceMetadataProviderFactory`

### 4.3 驱动配置

- [ ] `config/settings.yaml` 增加 `driverDefaults.{dbType}`
- [ ] `config/settings.yaml` 增加 driver 配置（driverClass、jars）
- [ ] 新建 `drivers/{dbType}/README.md`，说明驱动下载来源和放置路径
- [ ] `DriverResolver` 支持 `{dbType}` 类型的 driverRef 解析和 dbType 校验

### 4.4 URL 推断

- [ ] `JdbcUrlParser` 识别 `{dbType}` 的 URL 前缀
- [ ] 从 URL 可推断 `dbType={dbType}`
- [ ] 从 URL 可提取 host、port、database

### 4.5 QueryExecutor 集成

- [ ] `QueryExecutor` 在执行前读取 `DatabaseCapabilities`，禁止不支持的语句
- [ ] 不支持的 UPDATE/DELETE 明确拒绝，使用 `DatabaseErrorMessages` 标准提示
- [ ] `executeUpdateWithRecovery` 仅在 `supportsRecoverySql=true` 时可进入
- [ ] 多语句检测接入执行路径
- [ ] affected rows 为 `-1` 时输出 `unknown`
- [ ] readonly 保护覆盖所有写操作

### 4.6 CLI 命令

- [ ] `sql-cli {alias} test` 连接测试，输出方言专属提示
- [ ] `sql-cli {alias} tables` 表清单，默认 schema 从方言获取
- [ ] `sql-cli {alias} ddl <table>` DDL 获取
- [ ] `sql-cli {alias} "SQL"` 查询执行
- [ ] `sql-cli {alias} schema import --from-db` 图谱导入
- [ ] `sql-cli list` 和 `alias show` 输出不泄漏密码

### 4.7 测试矩阵

- [ ] `{DBType}DatabaseStrategyTest` 覆盖 URL 构建、标识符引用、表名限定、默认 LIMIT
- [ ] `JdbcUrlParserTest` 覆盖 `{dbType}` URL 格式
- [ ] `DriverResolverTest` 覆盖 `{dbType}` 默认驱动和 driverRef/dbType 不匹配
- [ ] `QueryExecutor{DBType}PolicyTest` 覆盖 SQL 执行策略
- [ ] `{DBType}WorkspaceMetadataProviderTest` 覆盖元数据解析
- [ ] Testcontainers 集成测试 profile（默认本地可跳过，CI 可按 profile 开启）
- [ ] 集成测试覆盖 `test`、`SELECT`、`SHOW`、`DESCRIBE`、`tables`、`ddl`、`schema import`
- [ ] SSL/安全参数构造测试
- [ ] 大结果集 smoke 测试

---

## 5. 验收矩阵

### 5.1 最小可用验收

| 场景 | 命令 | 预期 |
| --- | --- | --- |
| 连接测试 | `sql-cli {alias} test` | 成功，失败时给出方言专属提示 |
| 版本查询 | `sql-cli {alias} "SELECT version()"` | 返回版本 |
| 默认 LIMIT | `sql-cli {alias} "SELECT ..."` | 默认限制行数 |
| 显式 LIMIT | `sql-cli {alias} "SELECT ... LIMIT 5"` | 不重复追加 LIMIT |
| SHOW | `sql-cli {alias} "SHOW ..."` | 正常输出（如支持） |
| DESCRIBE | `sql-cli {alias} "DESCRIBE ..."` | 正常输出（如支持） |
| 表清单 | `sql-cli {alias} tables` | 输出表 |
| DDL | `sql-cli {alias} ddl {table}` | 输出 CREATE 语句 |
| 只读保护 | `sql-cli {alias} "DROP TABLE t"` | readonly alias 拒绝 |
| 不支持 DML | `sql-cli {alias} "UPDATE t SET x=1"` | 明确拒绝（如不支持） |
| 多语句 | `sql-cli {alias} "SELECT 1; SELECT 2"` | 明确拒绝（如不支持） |

### 5.2 图谱验收

| 场景 | 预期 |
| --- | --- |
| 导入指定 schema/database | 只生成目标 schema 的 schema/table/column |
| 系统库过滤 | 默认不导入系统库 |
| 表注释 | 数据库 comment 写入 `comment` 字段，重导入刷新 |
| 人工描述保留 | 重新导入后 `description`、`businessName` 不被覆盖 |
| 字段类型 | 原始类型保留 |
| 主键 | 可用时写入属性 |
| 外键 | 如不支持，明确跳过，不生成错误关系 |

### 5.3 回归验收

新数据库接入不能破坏已有数据库：

- MySQL `tables` 和 `ddl` 仍可用
- Oracle 默认 schema 仍为 username
- PostgreSQL DDL 生成仍可用
- ClickHouse 策略不受影响
- SM4 SQL 改写不因新数据库变更而影响原有测试
- Yearning access mode 不被新数据库策略影响
- `mvn test` 全量通过

---

## 附录 A：配置示例

### A.1 驱动配置

```yaml
driverDefaults:
  {dbType}: {driverRef}

drivers:
  {driverRef}:
    dbType: {dbType}
    driverClass: {driverClassName}
    jars:
      - ./drivers/{dbType}/{jarFileName}
```

### A.2 别名配置（完整 jdbcUrl）

```yaml
aliases:
  {alias-name}:
    dbType: {dbType}
    driverRef: {driverRef}
    jdbcUrl: jdbc:xxx://host:port/database
    username: {username}
    secretRef: keyring:{alias-name}
    readonly: true
    params:
      key1: value1
      key2: value2
```

### A.3 别名配置（结构化 host/port/database）

```yaml
aliases:
  {alias-name}:
    dbType: {dbType}
    driverRef: {driverRef}
    host: {host}
    port: {port}
    database: {database}
    username: {username}
    secretRef: encrypted:{alias-name}
```

### A.4 连接池建议

| 参数 | 建议值 | 说明 |
| --- | --- | --- |
| `maximumPoolSize` | `{值}` | |
| `minimumIdle` | `{值}` | |
| `connectionTimeoutMs` | `{值}` | |
| `idleTimeoutMs` | `{值}` | |
| `maxLifetimeMs` | `{值}` | |

---

## 附录 B：系统库过滤列表

> 列出该数据库类型应默认过滤的系统库/Schema。

```
{system_schema_1}
{system_schema_2}
...
```

---

## 附录 C：表类型映射

| 数据库原始类型 | Graph 表类型 | 备注 |
| --- | --- | --- |
| `{原始类型}` | `base_table` | |
| `{原始类型}` | `view` | |
| `{原始类型}` | `system_table` | |

---

## 附录 D：风险与决策记录

> 记录接入过程中的关键决策和风险。

| 编号 | 决策 | 原因 | 日期 |
| --- | --- | --- | --- |
| D1 | | | |
| D2 | | | |

---

## 附录 E：建议实施顺序

1. 先做 `DatabaseCapabilities` 和 Strategy 空实现
2. 接入 driver 配置、URL 推断、连接测试
3. 改造 `QueryExecutor`，先把不支持的 SQL 拦住
4. 实现 `tables` 和 `ddl`
5. 引入 metadata provider factory
6. 实现 metadata provider
7. 补齐测试矩阵
8. 更新 README、config README 和示例配置
9. 做 Testcontainers 集成验证
10. 再考虑生产化优化
