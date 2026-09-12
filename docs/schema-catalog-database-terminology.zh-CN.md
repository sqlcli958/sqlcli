# schema / catalog / database 术语对照

CLAUDE.md 定的调子：MySQL 的 database、Oracle 的用户空间、PostgreSQL 的 schema、
ClickHouse 的 database、SQLite（没有这个概念，见下文），在图谱模型里都建成同一种节点
`SchemaWorkspaceNode`，**对外一律称 schema**——UI 文案、API 字段、CLI 参数（`--schema`）、
文档统一，不出现「数据库」「库」「database」「用户空间」四种叫法混用。

这份文档记的是**每种数据库自己怎么叫、JDBC 元数据怎么取、我们怎么落到 `SchemaWorkspaceNode`
上**——加新数据库时用来判断该走哪条路，不是重新发明一套映射规则。

## 对照表

| 数据库 | 原生叫法 | JDBC 元数据取法 | 落到 `SchemaWorkspaceNode` | 备注 |
|---|---|---|---|---|
| MySQL | database（没有独立的 schema 层，二者是一回事） | `DatabaseMetaData.getCatalogs()`（`TABLE_CAT`）；`WorkspaceMetadataExtractor` 按 `databaseType=mysql` 特判，把 schema 名当 catalog 参数传给 `getTables`/`getColumns`/`getImportedKeys` 等 | 一个 database = 一个 `SchemaWorkspaceNode` | `tables` 命令输出的 schema 列为空，见 user-manual §19；MySQL 概念上确实没有第二层 |
| Oracle | 用户（user）/ 模式（schema）——Oracle 里这两个是同一回事 | `DatabaseMetaData.getSchemas()`（`TABLE_SCHEM`），不走 catalog 参数 | 一个 Oracle 用户 = 一个 `SchemaWorkspaceNode` | `--schema` 不填时默认取连接用户名（`SchemaActionCommand`/DDL/tables 命令一致遵循这条默认值） |
| PostgreSQL | schema，装在一个 database（catalog）内部——PG 是双层结构 | `DatabaseMetaData.getSchemas()`（`TABLE_SCHEM`） | 一个 PG schema = 一个 `SchemaWorkspaceNode`；database 这一层不建模，一个 alias 固定连一个 database | 要连别的 database 得配一个新 alias，同一个 alias 切换不了 database |
| ClickHouse | database（`CREATE DATABASE`、`system.databases`） | 优先查 `system.databases`，权限不足时回退 `DatabaseMetaData.getSchemas()`（`ClickHouseWorkspaceMetadataProvider`） | 一个 CH database = 一个 `SchemaWorkspaceNode` | 系统 database（`system`、`information_schema`）默认过滤，见 `SystemSchemas` |
| SQLite | **没有暴露给 JDBC 的 schema/catalog 概念**；`PRAGMA database_list` 能看到内部确实有，主库固定叫 `main`（`ATTACH` 进来的库才有别的名字） | `getSchemas()`/`getCatalogs()` 实测（sqlite-jdbc 3.46.1.3）都返回空结果集，`getTables()` 的 `TABLE_CAT`/`TABLE_SCHEM` 恒为 null | 固定建一个名为 `main` 的 `SchemaWorkspaceNode`，**不从 JDBC 元数据或文件名推导**——`WorkspaceMetadataExtractor#discoverSchemas` 里加了一个按数据库类型判断的分支 | 五种里唯一一个 schema 名不是数据库自己吐出来的，而是代码写死的规则；`--schema` 传 `main` 以外的值对 SQLite 没有意义 |

## 加新数据库时怎么用这张表

1. 先确定它的原生叫法（是 MySQL 那种「只有 database」，还是 PostgreSQL/Oracle 那种
   「schema 装在 database/user 里」，还是 SQLite 那种「JDBC 层面根本不暴露」）。
2. 再确定 JDBC 驱动把这个概念映射成 `getCatalogs()` 还是 `getSchemas()`——两者选错
   会导致 `discoverSchemas`/`getTables` 拿到空结果（SQLite 接入时踩过这个坑，
   见 `docs/dev-checklist-2026-08.zh-CN.md` 「SQLite 数据源接入」一节）。
3. 如果标准 JDBC `DatabaseMetaData` 就能覆盖（有 schema/catalog 枚举、有 comment），
   在通用的 `WorkspaceMetadataExtractor` 里加分支即可，不需要单独的 provider——SQLite
   就是这样接的。如果需要读数据库自己的系统表才能拿到完整信息（比如 ClickHouse 的
   engine/partition key/TTL），才值得写专门的 `WorkspaceMetadataProvider`。
4. 不管走哪条路，最终都落成 `SchemaWorkspaceNode`，CLI/API/UI 只认这一种节点，
   不要在文档或界面里按数据库类型分叉叫法。

模型细节见 `docs/database-graph-metadata-model-design.zh-CN.md`；新数据库接入的完整
模板见 `docs/datasource-integration-template.zh-CN.md`。
