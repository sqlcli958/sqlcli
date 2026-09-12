# 数据库图谱实现总览

> 审查基线：2026-06-15 —— **部分内容已过时（2026-08-24 标注）**：
> scan-sql 已删除；generation 只保留当前代（不做多版本）；
> Web UI 已收敛为五页（工作台 / 图谱 / 规则 / 评审 / 设置）；
> **关系类型从 7 个砍到 3 个**（`foreign_key` / `join_observed` / `term_mapping`），
> 本文关系表里的 `lineage_to`、`same_as`、`depends_on`、`used_with` 均已删除。
> 当前排期见 [开发清单](dev-checklist-2026-08.zh-CN.md)，实测现状见
> [产品规划报告](archive/product-plan-2026-08.zh-CN.md)。
>
> 事实来源：当前仓库 Java/React 实现、`sql-cli <alias> schema --help` 实际输出。
>
> 本文描述“当前代码实际支持什么”，设计目标和后续计划以文末关联文档为准。

完整可用前尚未完成的开发项、优先级和发布验收场景，以
~~数据库图谱 Web UI 开发清单~~（已删除，UI 现状见 [产品规划报告](archive/product-plan-2026-08.zh-CN.md) 第三节）
为准。

## 1. 定位与结论

当前数据库图谱是一个以本地文件为事实源的数据库元数据工作区，不是独立图数据库，也不是完整
GraphRAG 系统。

它已经支持：

- 从 JDBC 数据库抽取 Schema、表、字段、主键和声明外键。
- 使用 YAML 与 JSONL 持久化结构化图谱。
- 浏览表详情、查询邻接关系、查找表间路径。
- 维护业务术语和人工关系。
- 导出和导入 JSON 快照。
- 建立本地搜索索引并搜索表、字段和术语。
- 生成 Markdown + Mermaid 开发者关联图。
- 启动本地 Web UI 后端和前端静态页面。

当前实现的主要限制：

- 常规数据库导入存在阻断性正确性问题：分批合并会误标记未出现在当前批次的表，FK task 也可能
  因为向空的 incoming workspace 写入而丢失全部声明外键。
- CLI、数据库导入、Web UI 三条写入路径尚未统一到同一个 mutation service。
- revision、锁和关系端点规则没有在所有写入入口一致执行。
- `schema validate` 只做基础完整性校验，不等于关系语义完整校验。
- 搜索索引是本地文档分片和内存线性打分，不是倒排索引、BM25 或向量检索。
- Web UI 查询路径和主要 DTO 已基本对齐，描述/关系编辑界面也已存在；但表详情存在 React Hook
  顺序错误，生产 session 固定只读且不交付 token，默认 JAR 仍打包旧前端，当前不能视为可用完成态。
- 图谱只保留 `com.sqlcli.graph.workspace.*` 一套主模型和存储实现。

## 2. 功能成熟度

| 能力 | 当前状态 | 可用性说明 |
| --- | --- | --- |
| JDBC 元数据导入 | 阻塞 | 任务、状态、恢复和重置已实现；常规分批导入存在表误废弃和 FK 丢失问题，当前不能作为可信刷新链路 |
| 结构化工作区 | 已实现 | YAML 节点、JSONL 边和记录；单文件原子写，整个工作区非事务提交 |
| 表目录与详情 | 已实现 | CLI 可用 |
| 邻接关系查询 | 已实现 | CLI 可用，遍历按无向关系处理 |
| 表间路径 | 已实现 | CLI 可用，反向边为查询时临时构造 |
| 基础搜索 | 已实现 | 无索引时只搜索表和字段 |
| 分片索引搜索 | 已实现 | 搜索表、字段、术语和现存 Domain；全量扫描文档并打分 |
| 表/字段人工描述 | 已实现 | CLI 可用；写入流程未统一 revision 和锁 |
| Term | 已实现 | CLI 可创建和更新，内容建设能力较基础 |
| Domain | 待删除 | CLI、存储、索引和关系类型仍有实现，但不属于精简后的目标模型 |
| 人工关系 | 部分实现 | CLI 可写，但未调用 `RelationValidator`；Web API 有更严格校验 |
| 校验 | 部分实现 | 只校验 ID、悬空边、重复关系 ID 和 confidence 范围 |
| 开发者关联图 | 已实现 | 静态 Markdown/Mermaid 派生产物 |
| Web UI 查询 | 阻塞 | 查询契约基本对齐；表详情有 Hook 运行时错误，默认 JAR 的静态资源仍落后于前端源码 |
| Web UI 编辑 | 阻塞 | 编辑界面已存在；CLI 启动固定只读、token 未交付给页面，且查询 controller 会持续读取启动时旧快照 |
| GraphRAG | 未实现 | 当前图谱可以作为元数据检索底座，但没有完整 RAG 流程 |

## 3. 总体架构

```text
sql-cli <alias> schema ...
          |
          v
SqlCli.parseSchemaArgs
          |
          v
SchemaActionCommand
   |          |             |              |
   |          |             |              +--> GraphUiServer -> /api/* + React 静态资源
   |          |             +--> WorkspaceIndexer / WorkspaceDiagramGenerator
   |          +--> WorkspaceImportService -> JDBC DatabaseMetaData
   +--> GraphWorkspaceStore
                  |
                  v
       config/schema-graphs/<alias>/
```

### 3.1 现役代码边界

| 层 | 主要包或类 | 职责 |
| --- | --- | --- |
| CLI 路由 | `SqlCli`、`SchemaActionCommand` | 参数解析、命令分发和终端输出 |
| 工作区模型 | `com.sqlcli.graph.workspace` | 节点、边、变更和校验问题 |
| 存储 | `GraphWorkspaceStore` | YAML/JSONL 加载、全量保存、增量保存、快照导入导出 |
| 数据抽取 | `WorkspaceMetadataExtractor` | 通过 JDBC `DatabaseMetaData` 抽取数据库元数据 |
| 导入任务 | `WorkspaceImportService`、`WorkspaceImportJobStore` | 导入任务、断点、批次落盘、恢复和归档 |
| 合并 | `GraphWorkspaceMerger` | 数据库事实刷新时保留人工字段 |
| 查询 | `WorkspaceSearchEngine`、`WorkspacePathFinder` | 基础搜索和路径查找 |
| 校验 | `WorkspaceValidator`、`RelationValidator` | 基础工作区校验与关系规则校验 |
| 索引 | `workspace.index.*` | 搜索文档生成、分片存储、索引搜索 |
| 关联图 | `WorkspaceDiagramGenerator` | 生成 Markdown/Mermaid 开发者图 |
| Web UI | `com.sqlcli.graph.ui`、`web/`、`src/main/resources/web` | 本地 HTTP API、React 源码、打包静态页面和图画布 |

### 3.2 单一实现约束

图谱模型、导入、查询和存储统一使用 `com.sqlcli.graph.workspace`。项目不保留旧单文件图谱模型、
旧目录迁移器或旧索引读取分支；输入不符合当前模型和存储版本时应直接报错。

## 4. 数据模型

`GraphWorkspace` 是内存聚合根，包含：

| 对象 | 存储形式 | 作用 |
| --- | --- | --- |
| `WorkspaceManifest` | `manifest.yaml` | 模型/存储版本、revision、统计、索引和校验时间 |
| `DataSourceNode` | `datasource.yaml` | 只引用数据库 alias，不存连接密码 |
| `SchemaWorkspaceNode` | 每个 Schema 一个 YAML | Schema 元数据和状态 |
| `TableWorkspaceNode` | 每张表一个 YAML | 表元数据，字段以内嵌列表保存 |
| `ColumnWorkspaceNode` | 内嵌于表 YAML | 字段类型、主键、注释、语义和人工属性 |
| `TermWorkspaceNode` | 每个术语一个 YAML | 术语名称、别名和定义 |
| `RelationWorkspaceEdge` | `relations.jsonl` | 节点之间的类型化关系 |
| `MetricRecord` | `edges/metrics.jsonl` | BI 语义层的指标定义（`expression`/`filters`/`grain`/`dimensions`/`joinPath`），与 `terms` 平级的第四个集合，不是关系边 |
| `ChangeRecord` | `changes.jsonl` | 变更审计记录 |
| `ValidationIssueRecord` | `issues.jsonl` | 校验问题 |

### 4.1 公共字段

大多数一等图对象继承 `BaseGraphObject`，包含：

- `id`、`kind`、`version`
- `status`：`discovered`、`partial`、`verified`、`deprecated`、`conflict`、`ignored`
- `confidence`、`verified`
- `createdAt`、`updatedAt`
- `createdBy`、`updatedBy`：`agent`、`extractor`、`human`、`system`
- `attributes`

字段节点当前不是 `BaseGraphObject` 子类，而是表节点内嵌对象；其 ID 在使用时计算。

### 4.2 稳定 ID

| 对象 | 当前格式 |
| --- | --- |
| Workspace | `workspace:<alias>` |
| DataSource | `datasource:<alias>` |
| Schema | `schema:<alias>:<schema>` |
| Table | `table:<alias>:<schema>.<table>` |
| Column | `column:<alias>:<schema>.<table>.<column>` |
| Term | `term:<alias>:<name>` |
| Relation | 基于 alias、type、from、to 生成 |
| Change/Issue | alias、时间和进程序号组合 |

relation ID 包含 relation type，因此同一对端点可以同时记录声明外键、推断外键、观察 Join 等不同事实。

### 4.3 关系类型与规则

| 类型 | from | to | 最低 confidence |
| --- | --- | --- | ---: |
| `contains` | schema | table | 1.0 |
| `foreign_key_declared` | column | column | 1.0 |
| `foreign_key_inferred` | column | column | 0.5 |
| `join_observed` | column | column | 0.5 |
| `lineage_to` | column | column | 0.3 |
| `term_mapping` | term | table/column | 0.5 |
| `same_as` | table/column | table/column | 0.5 |
| `depends_on` | table/column | table/column | 0.5 |
| `used_with` | column | column | 0.3 |

这些规则定义在 `RelationType` 和 `RelationValidator` 中。Web mutation service 和 CLI
`add-relation` 已调用端点及最低可信度校验；通用 `WorkspaceValidator` 尚未统一调用它。

`term_mapping` 固定为 `term -> table/column`，目标 ID 类型区分表级和字段级映射；Term、Table 和
Column 不再保存反向引用。

## 5. 工作区目录

默认根目录由 `settings.yaml` 的 `schemaGraphPath` 决定，未配置时为：

```text
config/schema-graphs/<alias>/
├── current-generation
├── generations/<revision>-<uuid>/
│   ├── manifest.yaml
│   ├── datasource.yaml
│   ├── nodes/
│   │   ├── schemas/<schema>.yaml
│   │   ├── tables/<schema>/<table>.yaml
│   │   └── terms/<term>.yaml
│   ├── edges/relations.jsonl
│   ├── changes/changes.jsonl
│   └── validation/issues.jsonl
├── jobs/
│   ├── import-current.yaml
│   ├── import-history/
│   └── tasks/
├── index/
│   ├── manifest.json
│   └── documents-<generation>-*.json
└── developer-graph/
    ├── README.md
    ├── overview.md
    ├── relations-*.md
    ├── catalog-*.md
    └── manifest.json
```

主数据位于 `current-generation` 指向的 generation 中。
`index/` 与 `developer-graph/` 是可删除重建的派生数据。

### 5.1 保存语义

- `save` 先把完整工作区写入 staging generation，并回读确认结构完整。
- staging 完成后移动为不可变 generation，最后原子替换 `current-generation` 指针。
- 任一步失败都不切换指针，加载器继续读取旧 generation；旧的扁平 v5 工作区仍可读取。
- 表字段只接受内嵌 `ColumnWorkspaceNode` 对象，不接受独立 column 目录或字符串 ID 列表。

## 6. 核心执行流程

### 6.1 数据库导入

```text
schema import --from-db
  -> 创建 ImportJob 和 table/FK tasks
  -> JDBC DatabaseMetaData 抽取 Schema、表、字段、PK
  -> 批次合并并 incrementalSave
  -> 抽取 imported/exported foreign keys
  -> 最终 validate/save
  -> 归档 import job
```

支持 MySQL、PostgreSQL、Oracle、SQL Server 的 URL 类型识别。Oracle 额外尝试从
`ALL_TAB_COMMENTS` 和 `ALL_COL_COMMENTS` 读取注释。

导入合并时：

- 数据库类型、字段定义、主键和声明外键以本次抽取为准。
- 表/字段的 `description`（业务描述），以及业务名、语义、Domain/Term 引用和标签优先保留；
  `comment`（数据库注释镜像）始终按本次抽取刷新。
- 指定导入范围内消失的表可标记为 `deprecated`。
- `--force-overwrite` 可直接覆盖目标表并清理相关外键。

当前限制：

- task 化导入与一次性 extractor 的系统 Schema 标记行为并不完全一致。
- CLI 没有 `import pause`，虽然 job 状态枚举包含 `paused`。

### 6.2 查询和路径

- `describe` 返回表、内嵌字段和直接相关关系。
- `query` 以表为起点按深度 BFS 展开，遍历时把关系视为可双向访问。
- `path` 为查询构造反向临时边，返回按 hop 数排序的候选路径。
- 这些命令查询的是本地工作区，不会实时访问数据库。

### 6.3 搜索和索引

无可用索引时，`search` 使用 `WorkspaceSearchEngine` 搜索表名、表注释、字段名和字段注释。

执行 `index rebuild` 后：

1. `WorkspaceIndexer` 为 table、column、term、domain 生成搜索文档。
2. `WorkspaceIndexStore` 按最大约 1 MiB 分片写入 `documents-*.json`。
3. 最后原子替换 `index/manifest.json`。
4. `WorkspaceIndexedSearchEngine` 加载全部文档并按 exact、suffix、contains 等规则打分。

当前不是倒排索引，查询复杂度随文档数量增长；CLI `index status` 只展示索引清单内容，不报告
ready/stale 判定。CLI `search` 也没有在每次查询前校验 workspace revision 是否已超过索引 revision。

### 6.4 写入

当前存在三条写入路径：

| 入口 | 主要实现 | revision | 锁 | 关系规则 |
| --- | --- | --- | --- | --- |
| CLI edit/add-* | `SchemaActionCommand` 直接改 `GraphWorkspace` | 不统一递增 | 无 | `add-relation` 未调用 `RelationValidator` |
| 数据库导入 | `WorkspaceImportService` | 不统一递增 | 无 | 由 extractor 生成声明外键 |
| Web API | `WorkspaceMutationService` | 有乐观 revision | 进程锁 + 文件锁 | 调用 `RelationValidator` |

这意味着同一工作区从不同入口修改时，revision 和并发语义并不一致。统一 mutation service 是后续
正确性工作的核心。

`GraphWorkspaceStore.save` 会先清理节点目录，再逐文件重写 YAML/JSONL。`WorkspaceCommitService`
虽然已存在，但没有调用方，而且当前 generation store 会再次拼接 alias 目录，主加载器也不读取
`generationRevision`。因此它目前只是未完成骨架，不能视为原子提交实现。

### 6.5 校验

`WorkspaceValidator` 当前执行：

- manifest 存在性。
- 节点 ID 缺失和重复。
- relation ID 重复。
- relation from/to 是否悬空。
- 表、字段、Schema、Domain、Term、Relation 的 confidence 是否在 `[0, 1]`。

当前不执行：

- relation type 的端点类型矩阵。
- Domain、Term 引用完整性。
- 推断关系的创建来源、变更原因和最低可信度规则。
- Schema/Table/Column 父子结构一致性。
- verified 与 confidence 的联合规则。
- 索引和开发者图是否过期。

每次运行只替换 `producer=validator` 的 issue，保留其他 producer 的结果。
`schema validate` 即使发现 error，目前仍返回成功退出码。

manifest 缺失时 validator 会记录结构化 issue。Web `POST /api/validate` 通过统一 mutation
提交 issues 和 `lastValidationAt`，随后查询可读取本次结果。

### 6.6 开发者关联图

`schema diagram` 将字段级关系聚合成表级 Mermaid 边，并生成：

- Schema 概览。
- 按节点/边数量切分的关系图。
- 无关系表和全量表目录。
- 生成清单。

默认单个关系图限制约 40 个节点和 80 条边，目录按约 200 张表切片。产物只读且可重建，不反向写回
图谱。

### 6.7 Web UI

后端使用 Java 17 自带 `HttpServer`，只监听本机；前端为 React + TypeScript + Vite +
Sigma.js + Graphology，发布时嵌入 JAR。

后端查询 API：

```text
GET /api/session
GET /api/workspace
GET /api/workspace/stats
GET /api/schemas
GET /api/tables
GET /api/tables/{tableId}
GET /api/search
GET /api/graph
```

后端写 API：

```text
POST   /api/relations
PATCH  /api/relations/{relationId}
DELETE /api/relations/{relationId}
GET    /api/validation/issues
PATCH  /api/tables/{tableId}
PATCH  /api/tables/{tableId}/columns/{columnName}
POST   /api/validate
POST   /api/index/rebuild
GET    /api/index/status
```

当前 UI 由唯一的 `sql-cli ui` 入口启动；首页列出配置别名并标明工作区是否可用，选中后所有 API
请求携带 URL alias。旧实现审查中的路径、DTO 和只读 session 问题已修复。

具体阻断点：

- 前端请求 `/api/workspace/schemas`、`/api/workspace/tables`、`/api/workspace/table`、
  `/api/workspace/search`、`/api/workspace/graph`，后端实际路由为 `/api/schemas`、
  `/api/tables`、`/api/tables/{id}`、`/api/search`、`/api/graph`。
- 前端期待 session 返回 `token`，后端只返回 alias、revision、readOnly 和 capabilities；
  页面因此无法建立 session，也无法携带有效写 token。
- UI 首页无需 alias；从首页或 `--alias` 进入图谱后，URL 始终携带 `?alias=`。
- Java query controller 和 mutation controller 保存启动时的 workspace 引用。mutation service
  虽然每次重新加载并保存，但后续图、详情、搜索回退、默认 revision、关系查找和 index status
  仍可能使用旧快照。
- `GraphViewService` 聚合表级边时按 table ID 排序，改变 `lineage_to` 等有向关系的 source/target。

## 7. CLI 完整命令清单

统一入口：

```bash
sql-cli <alias> schema <action> [options]
```

UI 唯一入口：

```bash
sql-cli ui [--alias <alias>] [--port N] [--no-open]
```

未提供 `--alias` 时先打开别名首页；提供时直接打开对应图谱。

### 7.1 全部 action

| Action | 读/写 | 当前用途 |
| --- | --- | --- |
| `help` | 读 | 显示 schema 命令帮助 |
| `list` | 读 | 按 Schema 列出表 |
| `describe` | 读 | 查看表、字段和直接关系 |
| `query` | 读 | 按深度展开表关系 |
| `path` | 读 | 查找两张表之间的路径 |
| `search` | 读 | 搜索表、字段和术语 |
| `import` | 写 | 从数据库或快照导入工作区 |
| `export` | 读/文件写 | 导出 JSON 快照 |
| `edit` | 写 | 编辑表或字段描述和属性 |
| `stats` | 读 | 查看 manifest 统计 |
| `validate` | 写 | 校验并保存 validation issues |
| `add-term` | 写 | 新增或更新业务术语 |
| `add-relation` | 写 | 新增关系；同 ID 已存在时当前不覆盖 |
| `index` | 读/写 | 查询或重建搜索索引 |
| `diagram` | 读/文件写 | 生成开发者关联图 |
| `ui` | 服务 | 启动本地 Web UI |

### 7.2 浏览和检索

```bash
sql-cli <alias> schema list [--json]

sql-cli <alias> schema describe <schema.table> [--json]

sql-cli <alias> schema query <schema.table> [--depth N] [--json]

sql-cli <alias> schema path <schema.table> <schema.table> [--json]

sql-cli <alias> schema search <keyword> [--json]

sql-cli <alias> schema stats [--json]
```

说明：

- `describe`、`query` 可按完整表名或在无歧义时按简单表名解析。
- `query --depth` 最小按 1 处理。
- `search` 有索引时覆盖 table、column、term；无索引时只覆盖 table、column。
- `stats` 读取最近一次保存到 manifest 的统计，不主动重算整个工作区。

### 7.3 数据库导入

```bash
sql-cli <alias> schema import --from-db \
  [--schema <schema>] \
  [--table <schema.table>] \
  [--batch-size N] \
  [--merge] \
  [--force-overwrite]

sql-cli <alias> schema import --from-db --resume

sql-cli <alias> schema import status [--json]

sql-cli <alias> schema import resume

sql-cli <alias> schema import reset
```

说明：

- `--schema` 限定单个 Schema。
- `--table` 限定单表。
- `--batch-size` 控制任务批次落盘频率。
- `--merge` 在当前实现中参与导入选项，但刷新本身已经使用 `GraphWorkspaceMerger` 保留人工字段。
- `--force-overwrite` 强制用数据库抽取结果覆盖目标表的可覆盖内容。
- `reset` 删除当前导入 job/checkpoint，不删除已经写入的主图谱数据。
- Oracle 未指定 `--schema` 时默认使用连接用户名。

### 7.4 快照导入导出

```bash
sql-cli <alias> schema export \
  [--output <snapshot.json>] \
  [--force]

sql-cli <alias> schema import \
  --input <snapshot.json> \
  [--merge]
```

默认导出文件：

```text
config/schema-graphs/<alias>/workspace-export.json
```

未指定 `--force` 时不会覆盖已有导出文件。快照导入会先运行基础 validator；`--merge` 将快照与当前
工作区合并，否则整体保存导入工作区。

### 7.5 编辑表和字段

```bash
sql-cli <alias> schema edit \
  --table <schema.table> \
  --description <text> \
  [--business-name <text>] \
  [--add-tag <tag>]

sql-cli <alias> schema edit \
  --column <schema.table.column> \
  --description <text> \
  [--business-name <text>] \
  [--semantic-type <type>] \
  [--example <value>] \
  [--add-tag <tag>] \
  [--add-constraint <text>]
```

表编辑会写入 `description`/标签。字段编辑会写入 `description`（人工业务描述）、
示例和通用 attributes。当前字段
`--add-tag`、`--add-constraint` 使用单值 attribute key，多次执行可能覆盖旧值，并非集合追加。

`--business-name`（表/字段都可用）和 `--semantic-type`（仅字段）与 Web UI 的表/列编辑写
同一批字段（见 `WorkspaceMutationService.applyTablePatch` / `applyColumnPatch`），CLI 与 UI 不
分叉。`--semantic-type` 复用 `SemanticType.fromValue` 做枚举校验：非法值直接报错并把全部合法
取值（连中文标签）列出来，不会像 `fromValue` 给旧图谱反序列化兜底时那样悄悄解析成 `null`——
CLI 这一侧吞掉非法值等于让 Agent 以为写成功了，实际字段没变。传空串清空该字段。
固定枚举而非自由文本是因为 `PolicyEvaluator` 的 `semanticTypeAny` 规则条件靠精确匹配，
"手机号"/"phone"/"mobile" 三种写法自由文本一条规则都命中不了。

### 7.6 业务术语

```bash
sql-cli <alias> schema add-term <name> \
  [--display-name <name>] \
  [--description <text>] \
  [--aliases <a1,a2>] \
  [--map <schema.table|schema.table.column,...>]
```

命令按稳定 ID upsert。`--map` 要求表或字段已存在，并为每个目标创建一条
`term_mapping` 关系；`schema.table` 映射表，`schema.table.column` 映射字段，重复执行不会重复创建同一条边。

### 7.7 关系

```bash
sql-cli <alias> schema add-relation \
  --type <relationType> \
  --from <nodeRef> \
  --to <nodeRef> \
  [--join <expression>] \
  [--confidence <0-1>] \
  [--verified]
```

`add-relation --type` 可取第 4.3 节列出的关系类型。

重要边界：

- CLI `add-relation` 当前允许手工写 `foreign_key_declared`，与 Web API 的“系统关系不可人工创建”
  规则不一致。
- relation 的稳定 ID 已存在时，CLI 不会更新原对象，但仍追加 ChangeRecord 并输出
  “已更新”，属于反馈与实际行为不一致。

### 7.8 校验

```bash
sql-cli <alias> schema validate [--json]
```

命令会覆盖当前 validation issue 集合、设置 `lastValidationAt` 并保存工作区。发现 error 时当前仍返回
退出码 0，自动化脚本不能只依赖进程退出码判断图谱是否健康。

### 7.9 索引

```bash
sql-cli <alias> schema index rebuild

sql-cli <alias> schema index status [--json]
```

- `rebuild` 全量重建 table、column、term、domain 搜索文档。
- `status` 显示索引版本、生成时间、分片和文档数量。
- 当前没有 `index update`、`index inspect`、`--explain` 等命令；其他设计文档中出现这些写法时应视为
  规划项。

### 7.10 开发者关联图

```bash
sql-cli <alias> schema diagram \
  [--output <directory>] \
  [--json]
```

默认输出到工作区的 `developer-graph/`。`--json` 输出生成结果摘要，不会把图本身转成 JSON。

### 7.11 Web UI

```bash
sql-cli ui [--alias <alias>] [--port N] [--no-open]
```

- 默认端口为 `9999`；`--port 0` 自动选择空闲端口。
- `--no-open` 不自动打开浏览器。
- 只监听 `127.0.0.1`。
- 未指定 `--alias` 时展示别名目录；目录中的图谱状态、表数和关系数来自本地工作区。

### 7.12 参数解析约束

schema 参数目前由 `SqlCli.parseSchemaArgs` 手工解析，而不是统一命令框架：

- action 和子 action 采用字符串匹配。
- 部分位置参数通过“第一个非 `--` 参数”查找。
- 参数缺失、顺序变化和 option value 被误识别为位置参数的行为需要单独测试。
- 新增命令时必须同步修改 parser、help、`SchemaActionCommand` 和文档。

### 7.13 BI 指标（metric）

```bash
sql-cli <alias> schema add-metric <name> \
  --expression <EXPR> [--filters <TEXT>] \
  [--business-name <N>] [--aliases <a1,a2>] \
  [--grain-column <ref>] [--grains <day,week,month>] \
  [--dimensions <ref,...>] [--join-path <relationId|joinType,...>]

sql-cli <alias> schema metrics [--json]
sql-cli <alias> schema metric <name> [--json]
sql-cli <alias> schema expand-metric <name> \
  [--grain <G>] [--dimensions <ref,...>] [--time-from <D>] [--time-to <D>] [--json]
```

`expand-metric` 是消费方，落在 `com.sqlcli.metric`（新包，只读 `graph.workspace` 和
`strategy`，不修改它们）：

- `expression`/`filters` 是不透明字符串，原样拼进 `SELECT`/`WHERE`，不解析。
- `joinPath` 每一步引用一条已存在的 `RelationWorkspaceEdge`，直接使用它的
  `joinExpression`（复合外键分组后的完整多列条件），不按端点重新拼，避免漏掉复合键
  其余列产出跨租户笛卡尔积。
- 时间粒度截断函数按方言生成（`GrainSqlDialect`，覆盖 mysql/oracle/postgresql/clickhouse
  的 day/week/month/quarter/year），不放进 `com.sqlcli.strategy` 的方言类——那批类的方法
  几乎都要 `Connection`，纯字符串拼接不需要。
- 引用的表/列/关系不存在，或请求的粒度/维度超出该指标声明范围时抛
  `MetricExpansionException`，不产出半截 SQL。

详细参数说明见 user-manual §13.6「关系」下的「BI 指标（metric）」小节。

## 8. 当前未暴露或未完成的代码能力

| 代码 | 当前情况 |
| --- | --- |
| `WorkspaceMutationService` | Web API 使用，CLI 写命令尚未迁入 |
| `WorkspaceDiffService` | 只读取当前 changes，未真正按 revision 过滤，也没有 CLI/API 入口 |
| `WorkspaceCommitService` | 存在但未接入主保存链路 |
| `ValidationIssueStore` | 存在但通用 validator 未按 producer 增量维护问题 |
| relation endpoint/confidence 规则 | 已定义，但 CLI 和全局 validator 未统一执行 |
| 索引 stale 判定 | Web status service 部分具备，CLI search/status 未统一使用 |
| import pause/cancel | 状态模型存在，CLI 未提供 |

## 9. 关键问题与建议优先级

### P0：数据库导入正确性

1. 删除批次级“整 Schema 缺失表检测”，改为完整 discover 结果完成后一次性判定删除。
2. FK 提取必须使用包含全部端点表的 workspace，增加常规模式、跨批次和双向 FK 回归测试。
3. 数据库删除全部 FK 时清理旧关系，对重新出现的表/列清除 deprecated。
4. 工作区根目录存在但结构不完整时直接失败，不允许按新工作区初始化。

### P0：统一写入和可靠保存

1. 让 CLI edit/add-*、数据库导入和 Web API 统一经过 mutation/commit 边界。
2. 所有写操作统一 revision 递增、乐观并发和跨进程文件锁。
3. 修正并接入 generation 提交，主加载器按 active generation 读取。
4. 对 alias、schema、table、domain、term 和导出路径做 normalize + 根目录约束。
5. 增加中断写入、损坏 YAML/JSONL 和并发写入测试。

### P0：校验和关系一致性

1. `WorkspaceValidator` 集成 `RelationValidator`，并校验 Domain/Term 引用和父子结构。
2. [已完成] relation ID 纳入 type，允许同一端点间存在不同类型关系。
3. 所有术语映射只写 `term_mapping`，禁止在节点中重新增加映射引用。
4. Validator 按 producer/runId 替换本次 issue，不清空其他模块问题。
5. `schema validate` 遇到 error 返回非 0，Web validate 保存校验结果。

### P1：可用性

1. 修复 Web UI API 路径、DTO、session alias、只读模式和 workspace 刷新。
2. 保持 `sql-cli ui` 作为唯一入口，并持续执行 UI 端到端验收。
3. 保留有向关系方向，补充 React 真实页面 E2E。
4. `stats` 明确“保存时统计”或改为实时重算。

### P1：检索

1. CLI search 统一检查 source revision 和索引 stale 状态。
2. 引入 token 化、倒排索引/BM25 和中文、snake_case 分析。
3. 支持搜索解释和基于图关系的结果扩展。

## 10. 测试现状

截至 2026-06-12 的本地审查：

- 前端 `npm run build` 通过。
- 完整 `mvn test` 共 93 个测试通过，0 failure、0 error、0 skipped。
- Java 层有工作区、合并、UI server/API 等测试。
- 导入测试的 fake provider 没有生成 FK，未覆盖常规导入 FK 丢失和跨批次误废弃。
- HTTP E2E 直接构造可写 session 并调用 Java API，没有覆盖 CLI 固定只读、token 交付、
  React API 契约和 mutation 后查询刷新。
- 前端缺少真实浏览器端到端和组件测试。
- 构建成功只证明代码可编译和静态资源可生成，不代表当前 Web UI 用户流程可用。

## 11. 关联文档

- [数据库图谱元数据模型设计](database-graph-metadata-model-design.zh-CN.md)：目标模型和长期设计。
- ~~增量导入设计 / 优化与开发者图设计 / Web UI 设计 / Web UI 开发清单 / 后续优化计划~~
  —— 以上五份已于 2026-08-20 文档清理中删除（功能已实现或方案被取代，git 历史可查）。
  现行规划见 [产品规划报告](archive/product-plan-2026-08.zh-CN.md) 与 [开发清单](dev-checklist-2026-08.zh-CN.md)。
- [GraphRAG 元数据检索调研](graphrag-metadata-retrieval-notes.zh-CN.md)：图谱作为 GraphRAG 底座的边界。
