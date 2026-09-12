# 数据库图谱元数据模型设计

> 当前代码实际能力、完整 CLI 命令和实现边界请先查看
> [数据库图谱实现总览](database-graph-implementation-overview.zh-CN.md)。本文包含目标设计和历史方案，
> 文中的命令示例不一定都已实现。

本文设计 `sql-cli` 中数据库图谱的强校验元数据模型。目标不是做自由 Wiki，也不是先做复杂索引层，而是先搭好可扩展、可校验、可追溯的图谱基座。

当前 CLI 主要通过命令管理图谱，因此图谱模型必须满足：

- 数据结构强校验。
- Agent 只能通过受控命令/API 补充图谱。
- 主数据和派生索引分离。
- 图谱事实、业务语义和变更记录分离。
- 支持大项目逐步探索，不要求一次性完整初始化。
- 不依赖向量数据库。

## 1. 设计原则

### 1.1 主数据可信，索引可重建

图谱主数据必须是强模型：

```text
SchemaNode
TableNode
ColumnNode
RelationEdge
ChangeRecord
ValidationIssue
```

搜索索引、BM25、倒排表、缓存统计都属于派生数据，后续可以删除重建，不应该污染主模型。

### 1.2 Node 和 Edge 分离

```text
Node 记录“对象是什么”
Edge 记录“对象之间有什么关系”
```

Agent 的推断如果需要持久化，应直接写入带有 `createdBy`、`confidence`、`verified` 和变更原因的正式对象，
并经过结构化校验；未准备写入的探索结论不进入正式图谱。

### 1.3 连接信息不进入图谱

`sql-cli` 已有数据库连接别名体系。图谱不保存 host、port、username、password、secret、database 连接配置。

图谱只记录：

```text
sourceAlias = sql-cli 数据库连接别名
```

例如：

```yaml
sourceAlias: pct
```

### 1.4 稳定 ID 优先

所有对象都必须有全局稳定 ID。ID 不应该依赖文件路径，也不应该随展示名变化。

推荐 ID 规则：

| 对象 | ID 格式 |
| --- | --- |
| DataSource | `datasource:{alias}` |
| Schema | `schema:{alias}:{schema}` |
| Table | `table:{alias}:{schema}.{table}` |
| Column | `column:{alias}:{schema}.{table}.{column}` |
| Term | `term:{alias}:{name}` |
| Relation | `relation:{alias}:{type}:{sanitize(from)}->{sanitize(to)}` |
| Change | `change:{alias}:{date}:{seq}` |
| ValidationIssue | `issue:{alias}:{date}:{seq}` |

## 2. 模型分层：问答说明

### 问：为什么图谱不能只保存 Table 和 Column？

答：Table 和 Column 只能回答“数据库里有什么”，不能完整回答以下问题：

- 这些表和字段之间是什么关系？
- “买家”“合同”“结算金额”等业务词对应哪些字段？
- 某条推断关系为什么可信？
- 某项人工描述是谁修改的，是否会被下次数据库导入覆盖？

因此模型需要把物理结构、业务语义、关系和治理信息分开。分层不是为了增加目录，
而是为了让不同可信度、不同所有者的数据具有清晰的写入和覆盖规则。

### 问：精简后的模型包含哪些层？

答：当前推荐模型分为六层：

```text
GraphWorkspace
  ├─ 1. 工作区层
  │    ├─ Manifest
  │    └─ DataSource
  ├─ 2. 物理结构层
  │    ├─ SchemaNode
  │    ├─ TableNode
  │    └─ ColumnNode
  ├─ 3. 业务语义层
  │    ├─ TermNode
  │    └─ tags / businessName / semanticType
  ├─ 4. 关系层
  │    └─ RelationEdge
  ├─ 5. 治理层
  │    ├─ ChangeRecord
  │    ├─ ValidationIssue
  │    └─ ImportJob
  └─ 6. 派生层
       ├─ Search Index
       └─ Developer Graph
```

`DomainNode` 不进入精简后的推荐模型。当前没有数据库导入来源，也没有实际工作区文件，
其分类能力可以由 `tags` 和 `TermNode` 覆盖。代码中现存的 Domain 模型属于待清理实现。

### 问：第一层“工作区层”解决什么问题？

答：它定义“这是谁的图谱”和“图谱当前处于什么状态”。

| 对象 | 作用 | Agent 是否直接修改 |
| --- | --- | --- |
| `Manifest` | 保存 alias、模型版本、revision、对象统计、校验和索引时间 | 否，由系统流程维护 |
| `DataSource` | 关联 `sql-cli` 数据库连接别名和数据库类型 | 否，由初始化/导入维护 |

这一层不保存连接密码、host、username 等连接配置，也不保存表字段内容。Agent 用它判断工作区版本、
索引是否过期以及操作目标，但不把它当作业务知识。

### 问：第二层“物理结构层”解决什么问题？

答：它描述数据库中客观存在的结构，是图谱的事实骨架。

| 对象 | 回答的问题 | 主要来源 |
| --- | --- | --- |
| `SchemaNode` | 表属于哪个 schema/catalog？ | 数据库导入 |
| `TableNode` | 有哪些表、视图、主键和表级元数据？ | 数据库导入 |
| `ColumnNode` | 表有哪些字段，字段类型、可空性和默认值是什么？ | 数据库导入 |

这层由 `schema import --from-db` 创建和刷新。数据库拥有类型、主键、字段顺序等物理字段；
用户或 Agent 补充的业务名称、标签、样例和人工描述必须保留。

### 问：ColumnNode 内嵌在 Table YAML 中，为什么还能参与图关系？

答：存储方式和逻辑模型是两件事。

- 物理存储上，Column 只是 Table YAML 中的一条字段记录，不建立独立文件。
- 逻辑寻址上，系统使用父级 Table 的 `sourceAlias/schema/name` 加 Column 的 `name` 计算稳定 ID，
  因而仍可作为关系起点、关系终点和术语映射目标。

Column 自身不重复保存 `id`、`kind`、`sourceAlias`、`schema`、`table` 或 `qualifiedName`。这些值都由
父级 Table 上下文计算，因此不会形成 Table 与 Column 的重复事实。

### 问：第三层“业务语义层”解决什么问题？

答：它把数据库技术名称翻译成人和 Agent 能理解、检索和复用的业务语言。

| 模型 | 适用内容 |
| --- | --- |
| `businessName` | 某张表或字段的业务名称 |
| `semanticType` | `user_id`、`amount`、`status_code` 等通用语义类型 |
| `tags` | `order`、`finance`、`core`、`fact` 等轻量分类 |
| `TermNode` | 有定义、别名、反义/排除词并映射到多个表字段的正式术语 |

Agent 遇到“订单买家”时，可以先通过 Term、别名和业务名称找到候选字段，再进入关系层扩展。
简单分类使用 `tags`，只有需要统一定义、同义词和跨表映射时才创建 `TermNode`。

### 问：为什么当前模型不包含 DomainNode？

答：当前 Domain 没有独立、稳定的数据来源，也没有形成 Agent 必需的查询能力：

- 数据库导入不会生成 Domain。
- 表分类已经可以使用 `tags`。
- 跨表业务概念和自然语言映射已经可以使用 `TermNode`。

在没有“域负责人、域层级、域权限或域级治理”等真实需求前保留 Domain，只会增加同步和校验成本。
如果未来出现这些明确需求，应重新基于实际用例建模，而不是预留空层。

### 问：第四层“关系层”解决什么问题？

答：它描述节点之间可遍历的连接，使图谱从“对象目录”变成真正的图。

```text
Column --foreign_key_declared--> Column
Column --foreign_key_inferred--> Column
Column --join_observed---------> Column
Column --lineage_to------------> Column
Term   --term_mapping----------> Table / Column
```

数据库声明外键由导入负责；推断外键、观察到的 join、血缘和术语映射由规则、用户或 Agent 补充。
关系必须有稳定端点、类型、可信度和创建者。Agent 查找关联表、join 路径和字段血缘时主要遍历这一层。

### 问：第五层“治理层”解决什么问题？

答：它控制图谱如何变化、变化是否有效以及长任务如何恢复。

| 模型 | 作用 |
| --- | --- |
| `ChangeRecord` | 记录谁在什么时间修改了哪个对象 |
| `ValidationIssue` | 记录悬空引用、重复 ID、非法关系端点等问题 |
| `ImportJob` | 记录数据库导入阶段、任务、检查点、失败信息和历史 |

这些数据不是数据库业务事实，但没有它们，CLI、Web 和 Agent 的并发写入、失败恢复及问题诊断都不可控。

### 问：第六层“派生层”为什么不算主数据？

答：搜索索引和开发者图谱都可以从前五层重新生成：

- `index/` 为 Agent 和 CLI 提供快速关键词检索。
- `developer-graph/` 生成人可阅读的目录、概览和关系文档。

派生层可以删除重建，不能反向覆盖 Table、Column、Term 或 Relation。判断索引是否有效，应比较索引的
`sourceRevision` 和工作区 `Manifest.revision`。

### 问：Agent 使用这些层的标准流程是什么？

答：推荐流程如下：

```text
1. 读取 Manifest，确认工作区版本和索引状态
2. 通过 Term、业务名称、标签和索引定位 Table/Column
3. 遍历 RelationEdge 查找关联对象和 join 路径
4. 根据 createdBy、confidence、verified 和变更历史判断可信度
5. 经确认后写入正式节点属性、Term 或 RelationEdge
6. 写 ChangeRecord，执行 Validation
7. 主数据 revision 变化后重建派生索引
```

核心边界是：Agent 的探索过程不进入持久化图谱；只有准备作为正式对象维护的结论才写入工作区。

## 3. 通用基础字段

独立存储的 Node、Edge、ChangeRecord、ValidationIssue 建议包含以下基础元信息。
内嵌 `ColumnWorkspaceNode` 是例外：它不继承这组身份和审计字段，其身份由父级 Table 上下文计算，
修改时间和写入者由所属 Table 及 ChangeRecord 记录。

| 字段 | 类型 | 必填 | 含义 |
| --- | --- | ---: | --- |
| `id` | string | 是 | 全局稳定 ID |
| `kind` | enum | 是 | 模型类型，例如 `table`、`column`、`relation` |
| `version` | int | 是 | 当前对象模型版本 |
| `status` | enum | 是 | 生命周期状态 |
| `confidence` | decimal | 否 | 可信度，范围 `0-1` |
| `verified` | boolean | 否 | 是否人工或强规则确认 |
| `createdAt` | datetime | 是 | 创建时间 |
| `updatedAt` | datetime | 是 | 更新时间 |
| `createdBy` | string | 是 | 创建者，例如 `agent`、`extractor`、`human` |
| `updatedBy` | string | 是 | 最近更新者 |
| `attributes` | object | 否 | 扩展字段 |

`status` 枚举：

| 值 | 含义 |
| --- | --- |
| `discovered` | 刚发现，仅有名称或弱信息 |
| `partial` | 有部分结构，但未完整确认 |
| `verified` | 已确认可信 |
| `deprecated` | 已废弃但保留历史 |
| `conflict` | 存在冲突事实，需要处理 |
| `ignored` | 明确忽略，例如系统表 |

## 4. Manifest：工作区元信息

`Manifest` 记录整个图谱工作区的状态，是 CLI 操作图谱的入口。

示例：

```yaml
id: workspace:pct
kind: manifest
alias: pct
name: pct
modelVersion: 5
storageVersion: 5
revision: 1
defaultSourceAlias: pct
createdAt: 2026-05-20T10:00:00
updatedAt: 2026-05-20T10:00:00
stats:
  schemas: 8
  tables: 2072
  columns: 26376
  relations: 488
lastValidationAt: null
lastIndexedAt: null
```

字段：

| 字段 | 类型 | 必填 | 含义 |
| --- | --- | ---: | --- |
| `id` | string | 是 | 工作区 ID，格式 `workspace:{alias}` |
| `kind` | string | 是 | 固定为 `manifest` |
| `alias` | string | 是 | 图谱别名，通常等于数据库连接别名 |
| `name` | string | 否 | 展示名称 |
| `modelVersion` | int | 是 | 元数据模型版本，当前固定为 `5`；增加数据源版本、表索引和关系 evidence |
| `storageVersion` | int | 是 | 存储结构版本，当前固定为 `5`；主数据使用 generation 原子切换 |
| `revision` | long | 是 | 主数据版本，新工作区从 `1` 开始 |
| `defaultSourceAlias` | string | 是 | 默认数据源别名 |
| `stats` | object | 否 | 聚合统计，可重建 |
| `lastValidationAt` | datetime | 否 | 最近校验时间 |
| `lastIndexedAt` | datetime | 否 | 最近索引时间，先预留 |

## 5. DataSource：数据源别名模型

`DataSource` 第一版只保存 `sql-cli` 连接别名，不保存任何连接敏感信息。

示例：

```yaml
id: datasource:pct
kind: datasource
alias: pct
displayName: pct
dbType: mysql
status: verified
createdAt: 2026-05-20T10:00:00Z
updatedAt: 2026-05-20T10:00:00Z
createdBy: extractor
updatedBy: extractor
```

字段：

| 字段 | 类型 | 必填 | 含义 |
| --- | --- | ---: | --- |
| `id` | string | 是 | 稳定 ID，格式 `datasource:{alias}` |
| `kind` | string | 是 | 固定为 `datasource` |
| `alias` | string | 是 | `sql-cli` 数据库连接别名 |
| `displayName` | string | 否 | 展示名 |
| `dbType` | enum | 否 | `mysql`、`postgresql`、`oracle`、`sqlserver` 等 |
| `status` | enum | 是 | 生命周期状态 |
| `createdAt` | datetime | 是 | 创建时间 |
| `updatedAt` | datetime | 是 | 更新时间 |
| `createdBy` | string | 是 | 创建者 |
| `updatedBy` | string | 是 | 更新者 |

明确不保存：

```yaml
host: ...
port: ...
username: ...
password: ...
database: ...
secretRef: ...
environment: ...
```

这些属于连接配置或运行环境，不属于图谱元数据。

## 6. SchemaNode：Schema 模型

`SchemaNode` 记录数据库 schema/catalog。

示例：

```yaml
id: schema:pct:trade
kind: schema
sourceAlias: pct
name: trade
displayName: 交易域
description: 交易相关表
system: false
status: verified
confidence: 1.0
verified: true
createdAt: 2026-05-20T10:00:00Z
updatedAt: 2026-05-20T10:00:00Z
createdBy: extractor
updatedBy: extractor
version: 1
```

字段：

| 字段 | 类型 | 必填 | 含义 |
| --- | --- | ---: | --- |
| `id` | string | 是 | `schema:{alias}:{schema}` |
| `kind` | string | 是 | 固定为 `schema` |
| `sourceAlias` | string | 是 | 数据源别名 |
| `name` | string | 是 | schema 名 |
| `displayName` | string | 否 | 展示名 |
| `description` | string | 否 | 说明 |
| `system` | boolean | 是 | 是否系统 schema |

## 7. TableNode：表模型

`TableNode` 是核心模型之一，记录表级元数据。

示例：

```yaml
id: table:pct:trade.orders
kind: table
sourceAlias: pct
schema: trade
name: orders
qualifiedName: trade.orders
displayName: 订单表
businessName: 交易订单
comment: 订单主表
tableType: base_table
system: false
primaryKey:
  - column:pct:trade.orders.id
columns:
  - name: id
    dataType:
      raw: bigint
      normalized: bigint
    nullable: false
    ordinal: 1
    primaryKey: true
  - name: buyer_id
    dataType:
      raw: bigint
      normalized: bigint
    nullable: false
    ordinal: 2
    primaryKey: false
    comment: 买家用户ID
    semanticType: user_id
rowEstimate: 12000000
owner: trade-team
tags:
  - core
  - fact
status: partial
confidence: 0.8
verified: false
createdAt: 2026-05-20T10:00:00Z
updatedAt: 2026-05-20T10:00:00Z
createdBy: extractor
updatedBy: agent
version: 1
attributes: {}
```

字段：

| 字段 | 类型 | 必填 | 含义 |
| --- | --- | ---: | --- |
| `id` | string | 是 | `table:{alias}:{schema}.{table}` |
| `kind` | string | 是 | 固定为 `table` |
| `sourceAlias` | string | 是 | 数据源别名 |
| `schema` | string | 是 | schema 名 |
| `name` | string | 是 | 表名 |
| `qualifiedName` | string | 是 | 完整名，通常是 `schema.table` |
| `displayName` | string | 否 | 展示名 |
| `businessName` | string | 否 | 业务名称——短标签/检索键，会进查询结果列头；人/Agent 维护，重导入时保留 |
| `comment` | string | 否 | 数据库注释的镜像；只由导入写入，重导入时 incoming 永远覆盖，无分支判断 |
| `description` | string | 否 | 业务描述——口径、坑、来源，一段话；人/Agent 维护，重导入时保留 |
| `tableType` | enum | 是 | 表类型 |
| `system` | boolean | 是 | 是否系统表 |
| `primaryKey` | string[] | 否 | 主键字段 ID |
| `columns` | `ColumnWorkspaceNode[]` | 否 | 内嵌字段对象列表；字段 ID 在使用时根据 alias、schema、table、name 计算 |
| `rowEstimate` | long | 否 | 行数估计 |
| `owner` | string | 否 | 负责人 |
| `tags` | string[] | 否 | 标签 |

`tableType` 枚举：

| 值 | 含义 |
| --- | --- |
| `base_table` | 普通表 |
| `view` | 视图 |
| `materialized_view` | 物化视图 |
| `temporary` | 临时表 |
| `system_table` | 系统表 |
| `external` | 外部表 |
| `unknown` | 未知 |

## 8. ColumnNode：字段模型

Column 是 Table 内嵌的字段记录，不是独立存储对象。它只保存字段自身属性；字段血缘、术语映射和
字段关系使用运行时计算的稳定 ID 寻址。

计算规则：

```text
columnId = column:{table.sourceAlias}:{table.schema}.{table.name}.{column.name}
qualifiedName = {table.schema}.{table.name}.{column.name}
```

以下是 `TableNode.columns[]` 中单个字段的实际存储示例：

```yaml
name: buyer_id
displayName: 买家ID
businessName: 下单用户
comment: 买家用户ID
description: 下单时选择的收货人对应买家账号，与 seller_id 区分；不实时反映库注释变更
dataType:
  raw: bigint(20)
  normalized: bigint
  length: 20
  precision: null
  scale: null
nullable: false
defaultValue: null
ordinal: 5
primaryKey: false
unique: false
indexed: true
semanticType: user_id
valueHints:
  enumValues: []
  format: null
  sampleValues: []
confidence: 0.75
verified: false
```

字段：

| 字段 | 类型 | 必填 | 含义 |
| --- | --- | ---: | --- |
| `name` | string | 是 | 字段名 |
| `displayName` | string | 否 | 展示名 |
| `businessName` | string | 否 | 业务名——短标签/检索键，会进查询结果列头；人/Agent 维护，重导入时保留 |
| `comment` | string | 否 | 数据库字段注释的镜像；只由导入写入，重导入时 incoming 永远覆盖，无分支判断 |
| `description` | string | 否 | 业务描述——口径、坑、来源，一段话；人/Agent 维护，重导入时保留 |
| `dataType.raw` | string | 是 | 原始类型 |
| `dataType.normalized` | string | 是 | 标准化类型 |
| `dataType.length` | int | 否 | 长度 |
| `dataType.precision` | int | 否 | 精度 |
| `dataType.scale` | int | 否 | 小数位 |
| `nullable` | boolean | 是 | 是否允许空 |
| `defaultValue` | string | 否 | 默认值 |
| `ordinal` | int | 是 | 字段顺序 |
| `primaryKey` | boolean | 是 | 是否主键 |
| `unique` | boolean | 是 | 是否唯一 |
| `indexed` | boolean | 是 | 是否有索引 |
| `semanticType` | string | 否 | 语义类型，例如 `user_id`、`amount`、`status_code` |
| `valueHints` | object | 否 | 枚举值、格式、样例值等 |
| `confidence` | decimal | 否 | 字段语义信息的可信度 |
| `verified` | boolean | 否 | 字段语义信息是否已确认 |
| `attributes` | object | 否 | 人工标签、约束等扩展信息 |

不持久化但可计算的字段：

| 字段 | 计算来源 |
| --- | --- |
| `id` | Table 的 `sourceAlias/schema/name` + Column 的 `name` |
| `qualifiedName` | Table 的 `schema/name` + Column 的 `name` |
| 所属数据源、Schema、Table | 当前 Column 所在的父级 Table |

## 9. DomainNode：已从实现移除

早期方案曾定义 `DomainNode`，当前实现已经不保留该模型。业务分类使用 `tags`，需要统一定义和跨表映射的
业务概念使用 `TermNode`。

## 10. TermNode：业务术语模型

`TermNode` 保存业务术语自身的定义，用于自然语言检索和 Agent 理解。术语与字段之间的映射不保存在
Term、Table 或 Column 内，而是统一保存为 `term_mapping` 关系边。

示例：

```yaml
id: term:pct:buyer
kind: term
sourceAlias: pct
name: buyer
displayName: 买家
aliases:
  - 下单用户
  - 购买人
  - buyer
negativeAliases:
  - 商家
description: 发起订单购买行为的用户
status: partial
confidence: 0.8
verified: false
createdAt: 2026-05-20T10:00:00Z
updatedAt: 2026-05-20T10:00:00Z
createdBy: agent
updatedBy: agent
version: 1
```

字段：

| 字段 | 类型 | 必填 | 含义 |
| --- | --- | ---: | --- |
| `sourceAlias` | string | 是 | 数据源别名 |
| `name` | string | 是 | 术语标准名 |
| `displayName` | string | 否 | 中文或业务展示名 |
| `aliases` | string[] | 否 | 同义词、别名 |
| `negativeAliases` | string[] | 否 | 明确不是该术语的词，避免误召回 |
| `description` | string | 否 | 业务定义 |

`id`、`kind`、`version`、`status`、`confidence`、`verified`、创建/更新时间、创建/更新者和
`attributes` 来自 `BaseGraphObject`，不是 Term 重复定义的字段。`id` 固定为
`term:{alias}:{name}`，`kind` 固定为 `term`。

对应字段映射单独保存在 `edges/relations.jsonl`：

```json
{"id":"relation:pct:term_mapping:term_pct_buyer->column_pct_trade.orders.buyer_id","kind":"relation","sourceAlias":"pct","type":"term_mapping","from":"term:pct:buyer","to":"column:pct:trade.orders.buyer_id","direction":"forward","cardinality":"unknown","weight":1.0,"confidence":0.7,"verified":false,"status":"partial","createdBy":"human","updatedBy":"human"}
```

一个 Term 可以映射多个表和字段，每个目标写入一条边，Term 的名称、别名和描述只保存一次。

## 11. RelationEdge：关系边模型

`RelationEdge` 只记录两个已有节点之间的类型化关系，不复制表名、字段名、术语定义等节点内容。
全部关系保存在 `edges/relations.jsonl`，文件中每一行是一个完整 JSON 对象。

### 11.1 一条关系如何唯一标识

关系唯一键由 `sourceAlias + type + from + to` 共同确定：

```text
relation:{alias}:{type}:{sanitize(from)}->{sanitize(to)}
```

例如：

```text
relation:pct:foreign_key_declared:column_pct_trade.orders.buyer_id->column_pct_user.users.id
relation:pct:join_observed:column_pct_trade.orders.buyer_id->column_pct_user.users.id
```

关系类型必须进入 ID。这样同一对字段可以同时存在数据库声明外键、观察到的 Join 和推断关系，
不会因为端点相同而互相覆盖。`sanitize` 只将节点 ID 中的冒号、斜杠、反斜杠和空格替换为下划线。

### 11.2 端点如何记录

`from` 和 `to` 必须保存完整、可解析的节点 ID：

| 节点 | ID 示例 |
| --- | --- |
| Schema | `schema:pct:trade` |
| Table | `table:pct:trade.orders` |
| Column | `column:pct:trade.orders.buyer_id` |
| Term | `term:pct:buyer` |

端点 ID 本身就能区分表和字段：

- `to=table:...`：术语描述或映射到整张表。
- `to=column:...`：术语描述或映射到具体字段。
- 不在 `RelationEdge` 中增加 `targetKind`，加载时以工作区真实节点类型为准。

### 11.3 完整字段

数据库声明外键的完整记录示例：

```yaml
id: relation:pct:foreign_key_declared:column_pct_trade.orders.buyer_id->column_pct_user.users.id
kind: relation
version: 1
status: verified
confidence: 1.0
verified: true
createdAt: 2026-05-20T10:00:00
updatedAt: 2026-05-20T10:00:00
createdBy: extractor
updatedBy: extractor
attributes:
  constraintName: fk_orders_buyer
sourceAlias: pct
type: foreign_key_declared
from: column:pct:trade.orders.buyer_id
to: column:pct:user.users.id
direction: forward
cardinality: many_to_one
joinExpression: trade.orders.buyer_id = user.users.id
weight: 1.0
```

| 字段 | 类型 | 必填 | 含义 |
| --- | --- | ---: | --- |
| `id` | string | 是 | 由 alias、type、from、to 生成的稳定 ID |
| `kind` | string | 是 | 固定为 `relation` |
| `version` | int | 是 | 对象版本，默认 `1` |
| `status` | enum | 是 | `discovered`、`partial`、`verified`、`deprecated` 等状态 |
| `confidence` | decimal | 是 | 关系成立的可信度，范围 `0-1` |
| `verified` | boolean | 是 | 是否经过数据库声明或人工确认 |
| `createdAt` / `updatedAt` | datetime | 是 | 创建和最近更新时间 |
| `createdBy` / `updatedBy` | enum | 是 | `extractor`、`human`、`agent`、`system` |
| `attributes` | object | 否 | 关系类型没有固定字段承载的扩展信息 |
| `sourceAlias` | string | 是 | 数据源别名 |
| `type` | enum | 是 | 关系类型 |
| `from` | string | 是 | 起点节点完整 ID |
| `to` | string | 是 | 终点节点完整 ID |
| `direction` | enum | 是 | 默认 `forward`，表示 `from -> to` |
| `cardinality` | enum | 是 | 相对于 `from -> to` 的基数，不适用时为 `unknown` |
| `joinExpression` | string | 否 | 可执行或可读的 SQL Join 条件，仅字段关系使用 |
| `weight` | decimal | 是 | 图遍历和排序权重，默认 `1.0`；不等同于可信度 |

`id`、`kind`、版本、状态、可信度、验证标记、审计字段和 `attributes` 来自
`BaseGraphObject`；其余字段由 `RelationWorkspaceEdge` 定义。

### 11.4 方向和基数约定

- 关系应优先使用规范方向，通常固定为 `forward`。
- 外键统一记录为“外键字段 -> 被引用字段”，因此常见基数为 `many_to_one`。
- 血缘统一记录为“来源字段 -> 产出字段”。
- 术语统一记录为“Term -> Table/Column”。
- `same_as`、`used_with` 这类对称关系可以使用 `bidirectional`。
- 不建议用 `reverse` 表达同一事实；需要反向查询时由图查询引擎反向遍历，避免生成第二条重复边。

`cardinality` 枚举：

| 值 | 含义 |
| --- | --- |
| `one_to_one` | 一个 from 对应一个 to |
| `one_to_many` | 一个 from 对应多个 to |
| `many_to_one` | 多个 from 值对应一个 to |
| `many_to_many` | 两端均可能对应多个对象 |
| `unknown` | 不适用或尚未确认 |

### 11.5 不同关系如何记录

数据库声明外键，由数据库导入生成：

```json
{"id":"relation:pct:foreign_key_declared:column_pct_trade.orders.buyer_id->column_pct_user.users.id","kind":"relation","type":"foreign_key_declared","from":"column:pct:trade.orders.buyer_id","to":"column:pct:user.users.id","direction":"forward","cardinality":"many_to_one","joinExpression":"trade.orders.buyer_id = user.users.id","confidence":1.0,"verified":true,"status":"verified","createdBy":"extractor"}
```

根据命名或业务规则推断的外键，由用户或 Agent 创建：

```json
{"id":"relation:pct:foreign_key_inferred:column_pct_trade.orders.salesman_id->column_pct_org.users.id","kind":"relation","type":"foreign_key_inferred","from":"column:pct:trade.orders.salesman_id","to":"column:pct:org.users.id","direction":"forward","cardinality":"many_to_one","joinExpression":"trade.orders.salesman_id = org.users.id","confidence":0.7,"verified":false,"status":"partial","createdBy":"agent"}
```

从历史 SQL 中观察到的 Join：

```json
{"id":"relation:pct:join_observed:column_pct_trade.order_items.order_id->column_pct_trade.orders.id","kind":"relation","type":"join_observed","from":"column:pct:trade.order_items.order_id","to":"column:pct:trade.orders.id","direction":"forward","cardinality":"many_to_one","joinExpression":"trade.order_items.order_id = trade.orders.id","confidence":0.6,"verified":false,"status":"partial","createdBy":"agent","attributes":{"observationCount":12}}
```

字段血缘，方向固定为来源字段到产出字段：

```json
{"id":"relation:pct:lineage_to:column_pct_ods.orders.amount->column_pct_dwd.order_fact.order_amount","kind":"relation","type":"lineage_to","from":"column:pct:ods.orders.amount","to":"column:pct:dwd.order_fact.order_amount","direction":"forward","cardinality":"unknown","confidence":0.8,"verified":false,"status":"partial","createdBy":"agent","attributes":{"transform":"CAST(amount AS DECIMAL(18,2))"}}
```

术语描述整张表，目标使用 Table ID：

```json
{"id":"relation:pct:term_mapping:term_pct_order->table_pct_trade.orders","kind":"relation","type":"term_mapping","from":"term:pct:order","to":"table:pct:trade.orders","direction":"forward","cardinality":"unknown","weight":1.0,"confidence":0.8,"verified":true,"status":"verified","createdBy":"human"}
```

术语描述具体字段，目标使用 Column ID：

```json
{"id":"relation:pct:term_mapping:term_pct_buyer->column_pct_trade.orders.buyer_id","kind":"relation","type":"term_mapping","from":"term:pct:buyer","to":"column:pct:trade.orders.buyer_id","direction":"forward","cardinality":"unknown","weight":1.0,"confidence":0.8,"verified":true,"status":"verified","createdBy":"human"}
```

以上示例为便于阅读省略了部分通用审计字段。实际写入时由创建服务补齐默认值。

### 11.6 关系类型和合法端点

| 类型 | from | to | 主要来源 | 字段约定 |
| --- | --- | --- | --- | --- |
| `contains` | schema | table | 结构派生 | 当前可由目录结构推导，不强制落边 |
| `foreign_key_declared` | column | column | JDBC/DDL 导入 | confidence=1、verified=true、建议填写 joinExpression |
| `foreign_key_inferred` | column | column | 人工、规则、Agent | 建议填写推断原因和 joinExpression |
| `join_observed` | column | column | SQL 分析 | attributes 可记录观察次数或查询来源 |
| `lineage_to` | column | column | SQL/ETL 分析 | from 为来源，to 为产出 |
| `term_mapping` | term | table/column | CLI、Web、人工、Agent | to 的节点类型区分表级和字段级映射 |
| `same_as` | table/column | table/column | 人工、Agent | 通常使用 bidirectional |
| `depends_on` | table/column | table/column | SQL/视图/任务分析 | from 依赖 to |
| `used_with` | column | column | SQL 使用统计 | 通常使用 bidirectional |

### 11.7 写入和更新规则

- 新建前必须确认 `from`、`to` 节点真实存在，并校验关系类型允许的端点组合。
- 同一 `type + from + to` 只能有一条边；重复创建应执行 upsert，不得追加重复行。
- 同一对端点允许存在不同类型的关系，因为类型属于关系事实的一部分。
- 修改 `type`、`from` 或 `to` 会改变关系 ID，应按删除旧边、创建新边处理。
- 修改可信度、验证状态、基数、Join 表达式或扩展属性时保持关系 ID 不变。
- `foreign_key_declared` 由数据库导入拥有；刷新导入可以替换它，但不能覆盖或删除人工关系。
- 删除 Term、Table 或 Column 前必须处理指向该节点的关系，禁止留下悬空端点。
- `edges/relations.jsonl` 是唯一关系事实源，节点中不保存反向关系数组。

## 12. ChangeRecord：变更记录

`ChangeRecord` 用于审计和后续回滚设计。

示例：

```yaml
id: change:pct:20260520:0001
kind: change
sourceAlias: pct
actor: agent
operation: upsert_relation
targetId: relation:pct:foreign_key_inferred:column_pct_trade.orders.buyer_id->column_pct_user.users.id
beforeHash: null
afterHash: abc123
reason: SQL 查询中观察到 join
createdAt: 2026-05-20T10:00:00Z
version: 1
```

字段：

| 字段 | 类型 | 必填 | 含义 |
| --- | --- | ---: | --- |
| `id` | string | 是 | 变更 ID |
| `kind` | string | 是 | 固定为 `change` |
| `sourceAlias` | string | 是 | 数据源别名 |
| `actor` | enum | 是 | `agent`、`human`、`extractor` |
| `operation` | enum | 是 | 操作类型 |
| `targetId` | string | 是 | 被修改对象 |
| `beforeHash` | string | 否 | 修改前 hash |
| `afterHash` | string | 否 | 修改后 hash |
| `reason` | string | 否 | 修改原因 |
| `createdAt` | datetime | 是 | 时间 |
| `version` | int | 是 | 模型版本 |

`operation` 枚举：

| 值 | 含义 |
| --- | --- |
| `create` | 创建 |
| `update` | 更新 |
| `delete` | 删除 |
| `upsert` | 创建或更新 |
| `upsert_table` | 创建或更新表 |
| `upsert_column` | 创建或更新字段 |
| `upsert_relation` | 创建或更新关系 |
| `verify` | 确认 |
| `deprecate` | 废弃 |
| `ignore` | 忽略 |

## 13. ValidationIssue：校验问题模型

强校验输出不要只打印日志，应该结构化保存，便于 CLI 展示和 Agent 修复。

示例：

```yaml
id: issue:pct:20260520:001
kind: validation_issue
sourceAlias: pct
severity: error
code: dangling_relation_target
message: relation target does not exist
targetId: relation:pct:foreign_key_inferred:column_pct_trade.orders.buyer_id->column_pct_user.users.id
field: to
status: open
createdAt: 2026-05-20T10:00:00Z
updatedAt: 2026-05-20T10:00:00Z
version: 1
```

字段：

| 字段 | 类型 | 必填 | 含义 |
| --- | --- | ---: | --- |
| `id` | string | 是 | 问题 ID |
| `kind` | string | 是 | 固定为 `validation_issue` |
| `sourceAlias` | string | 是 | 数据源别名 |
| `severity` | enum | 是 | 严重级别 |
| `code` | string | 是 | 机器可读错误码 |
| `message` | string | 是 | 人类可读说明 |
| `targetId` | string | 是 | 问题对象 |
| `field` | string | 否 | 问题字段 |
| `status` | enum | 是 | 问题状态 |
| `createdAt` | datetime | 是 | 创建时间 |
| `updatedAt` | datetime | 是 | 更新时间 |
| `version` | int | 是 | 模型版本 |

`severity` 枚举：

| 值 | 含义 |
| --- | --- |
| `info` | 信息 |
| `warning` | 警告 |
| `error` | 错误 |

`ValidationIssue.status` 枚举：

| 值 | 含义 |
| --- | --- |
| `open` | 未处理 |
| `fixed` | 已修复 |
| `ignored` | 已忽略 |

常见校验错误码：

| code | 含义 |
| --- | --- |
| `missing_required_field` | 缺少必填字段 |
| `invalid_id_format` | ID 格式错误 |
| `dangling_node_ref` | 节点引用不存在 |
| `dangling_relation_source` | 关系起点不存在 |
| `dangling_relation_target` | 关系终点不存在 |
| `duplicate_node_id` | 节点 ID 重复 |
| `duplicate_relation_id` | 关系 ID 重复 |
| `invalid_confidence` | 可信度不在 `0-1` |
| `invalid_status_transition` | 状态流转非法 |
| `relation_type_not_allowed` | 关系类型不允许 |

## 14. 工作区存储结构

图谱不保存为单个大 JSON，也不再额外拆分“数据库层”和“人工维护层”。当前采用单一工作区：
数据库结构、人工描述和业务术语分别落在职责明确的文件中，通过字段来源和对象类型控制导入覆盖范围。

```text
schema-graphs/{alias}/
  .workspace.lock
  current-generation
  generations/
    {revision}-{uuid}/
      manifest.yaml
      datasource.yaml
      nodes/
        schemas/
          {schema}.yaml
        tables/
          {schema}/
            {table}.yaml
        terms/
          {term}.yaml
      edges/
        relations.jsonl
      changes/
        changes.jsonl
      validation/
        issues.jsonl
  jobs/
    import-current.yaml
    import-history/
      {jobId}.yaml
    tasks/
      {jobId}.json
  index/
    manifest.json
    documents-{generation}-{number}.json
  developer-graph/
    README.md
    overview.md
    catalog-*.md
    relations-*.md
    manifest.json
```

说明：

- `nodes` 和 `edges` 是图谱主数据；字段节点内嵌在表 YAML 的 `columns` 中，没有独立 `nodes/columns` 目录。
- `current-generation` 原子指向完整且不可变的主数据 generation；旧的扁平 v5 布局只用于兼容读取。
- `changes`、`validation` 和 `jobs` 分别保存变更审计、校验结果和导入任务状态。
- `index` 和 `developer-graph` 都是可以从主数据重新生成的派生产物。
- `.workspace.lock` 是并发写保护文件，不属于图谱业务数据。

## 15. 写入语义

所有 CLI/Agent 写入都应该使用 upsert/patch 语义。

### 15.1 UpsertTable

输入：

```text
sourceAlias
schema
name
patch fields
actor
```

规则：

- 如果表不存在，创建 `TableNode`。
- 如果表存在，只更新 patch 中出现的字段。
- 不允许低可信写入覆盖 `verified=true` 的关键字段。
- 自动写入 `ChangeRecord`。

### 15.2 UpsertColumn

规则：

- 字段必须属于已存在的表，或允许自动创建 `partial` 表。
- `ordinal`、`dataType`、`nullable` 来自抽取器时可信度较高。
- `businessName`、`semanticType` 来自 Agent 时应记录合理的 confidence 和变更原因。
- 字段与术语的关联必须写 `term_mapping`，不能写回 Column 属性。

### 15.3 UpsertRelation

规则：

- `from` 和 `to` 必须存在。
- `type` 必须在允许枚举中。
- 已验证关系不能被低可信关系覆盖。

## 16. 校验规则

基础校验：

| 规则 | 说明 |
| --- | --- |
| ID 格式校验 | 所有对象 ID 必须符合固定格式 |
| kind 校验 | `kind` 必须和模型类型一致 |
| 必填字段校验 | 缺少必填字段报 `error` |
| 引用完整性 | relation 的 `from`、`to` 必须可解析 |
| 可信度范围 | `confidence` 必须在 `0-1` |
| 状态枚举 | `status` 必须是允许值 |
| 关系端点类型 | 某些关系类型只允许特定节点类型 |
| verified 保护 | 低可信写入不能覆盖 verified 字段 |

当前代码端点规则：

| 关系类型 | from | to |
| --- | --- | --- |
| `contains` | schema | table |
| `foreign_key_declared` | column | column |
| `foreign_key_inferred` | column | column |
| `join_observed` | column | column |
| `lineage_to` | column | column |
| `term_mapping` | term | table/column |
| `same_as` | table/column | table/column |
| `depends_on` | table/column | table/column |
| `used_with` | column | column |

`term_mapping` 固定使用 `Term -> Table/Column`。目标节点 ID 的 `table:` 或 `column:` 前缀区分
表级和字段级术语映射，禁止再创建反向边。

## 17. CLI 命令建议

第一阶段命令：

```bash
schema graph init pct
schema graph validate pct
schema graph stats pct

schema graph upsert-schema pct trade
schema graph upsert-table pct trade.orders
schema graph upsert-column pct trade.orders buyer_id
schema graph add-relation pct \
  --type foreign_key_inferred \
  --from column:pct:trade.orders.buyer_id \
  --to column:pct:user.users.id

schema graph search pct "orders"
schema graph expand pct table:pct:trade.orders --depth 1
```

第二阶段命令：

```bash
schema graph upsert-term pct buyer
schema graph verify pct relation:pct:foreign_key_inferred:column_pct_trade.orders.buyer_id->column_pct_user.users.id
```

索引阶段再增加：

```bash
schema index rebuild pct
schema index status pct
schema index inspect pct --node table:pct:trade.orders
```

## 18. 和索引层的关系

当前阶段不建议完整实现索引层，但模型必须能被索引消费。

主模型字段中对索引有价值的内容：

| 模型 | 索引字段 |
| --- | --- |
| SchemaNode | `name`、`displayName`、`description` |
| TableNode | `schema`、`name`、`qualifiedName`、`displayName`、`businessName`、`comment`、`tags` |
| ColumnNode | `name`、`displayName`、`businessName`、`comment`、`semanticType` |
| TermNode | `name`、`displayName`、`aliases`、`negativeAliases`、`description` |

第一版简单搜索可以直接扫描工作区文件：

```text
exact match
contains match
type filter
relation expand
explain output
```

后续再替换为：

```text
inverted index
BM25
CJK bigram
source overlap
graph relevance score
incremental index update
```

## 19. 落地阶段

### 第一阶段：模型基座

实现：

- `Manifest`
- `DataSource`
- `SchemaNode`
- `TableNode`
- `ColumnNode`
- `RelationEdge`
- `ChangeRecord`
- `ValidationIssue`
- 工作区拆分存储
- 强校验
- 简单搜索

不实现：

- 复杂索引层
- 向量数据库
- 自动社区发现
- LLM 自由写 Wiki

### 第二阶段：业务增强

实现：

- `TermNode`
- 术语到表或字段的 `term_mapping` 关系

### 第三阶段：索引增强

实现：

- 本地倒排索引
- BM25
- 中文 bigram 或分词
- graph expansion scoring
- source overlap
- incremental index update

## 20. 最终取舍

本设计吸收三个参考项目的优势，但不照搬它们的形态：

| 来源 | 吸收 | 不吸收 |
| --- | --- | --- |
| `llm_wiki` | 本地索引思想、source overlap、图扩展、Agent API | 自由 Markdown 作为主模型 |
| `LightRAG` | 存储分层、状态管理、upsert/delete/rebuild 思路 | vector-first 查询 |
| `Microsoft GraphRAG` | 数据产品拆分、Entity/Relationship 和社区摘要思想 | 重批处理 GraphRAG 管线 |

最终目标：

```text
Validated Graph Model
  + Workspace Storage
  + Simple Search
  + Local Document Index
  + Explainable CLI Output
```

这套模型的核心是先把图谱基座搭稳。只要主模型、关系、变更和校验边界清晰，后续无论加业务术语、
SQL 血缘、Agent 自动探索，还是加本地索引，都不会破坏已有结构。

## 21. 当前实现程度

截至当前版本，`sql-cli` 的图谱已经不是“单文件原型”，而是可被 CLI 和 Agent 持续维护的结构化工作区。

### 21.1 已实现

已落地的核心能力：

- 工作区结构化存储
  - 已实现 `GraphWorkspaceStore`
  - 主数据按 generation 内的 `manifest / datasource / nodes / edges / changes / validation` 拆分存储
  - alias 根目录保存 `jobs / index / developer-graph` 等任务和派生数据
  - 支持 generation 原子提交，并兼容读取旧的扁平 v5 工作区
- 主模型
  - `WorkspaceManifest`
  - `DataSourceNode`
  - `SchemaWorkspaceNode`
  - `TableWorkspaceNode`
  - `ColumnWorkspaceNode`
  - `TermWorkspaceNode`
  - `RelationWorkspaceEdge`
  - `ChangeRecord`
  - `ValidationIssueRecord`
- JDBC 元数据导入
  - 已实现 `WorkspaceMetadataExtractor`
  - 可抽取 schema、table、column、主键、外键
  - 可识别部分系统 schema，并标记为 `ignored`
- 增量导入任务
  - 已实现 `WorkspaceImportService` 和 `WorkspaceImportJobStore`
  - 支持 `schema import --from-db`
  - 支持 `--schema`、`--table`、`--batch-size`
  - 支持 `status`、`resume`、`reset`
  - 导入过程会分 task 落盘，而不是一次性全量内存完成后再写
- CLI 维护能力
  - `schema list`
  - `schema describe`
  - `schema query`
  - `schema path`
  - `schema search`
  - `schema stats`
  - `schema validate`
  - `schema edit`
  - `schema add-term`
  - `schema add-relation`
  - `schema export`
  - `schema import --input`
- 校验能力
  - manifest 存在性校验
  - node id 重复校验
  - relation id 重复校验
  - dangling relation 校验
  - `confidence` 范围校验
- 本地索引
  - 已实现 `WorkspaceIndexer`
  - 已实现 `WorkspaceIndexStore`
  - 已实现 `WorkspaceIndexedSearchEngine`
  - 当前索引对象包含 table、column、term，以及待删除的 domain
  - 当前索引使用 `manifest.json + documents 分片` 落盘

### 21.2 已实现但仍偏基础

这些能力已经存在，但仍是第一版：

- 搜索
  - 当前不是 BM25，也没有中文分词
  - 本质上是字段级 exact / suffix / contains 匹配加简单权重
  - 更适合表名、字段名、限定名、注释命中，不适合复杂自然语言检索
- Term
  - 模型和 CLI 命令已存在
  - 主要靠人工维护，还没有自动术语发现
- Domain
  - 代码和 CLI 命令仍存在，但已列入待删除范围
- 导入恢复
  - 已支持任务级恢复
  - 但还没有暂停命令，也没有更细粒度的失败重试策略

### 21.3 尚未实现或明显缺口

> **本节与第 24 章的未完成项已于 2026-08-22 合并进
> [dev-checklist-2026-08](dev-checklist-2026-08.zh-CN.md)，那边才是执行清单。
> 本文继续作为模型定义的权威。**
>
> **2026-08-22 对账**：本节有两条已经过时——
> 「CLI `add-relation` 尚未接入相同端点矩阵」已接入（`SchemaActionCommand` 先跑
> `relationValidator` 再 mutate）；「`unique`、`indexed`、值域、样本值大多未抽取」中
> `unique` 与 `indexed` 已由 `WorkspaceMetadataExtractor` 抽取，
> **真正缺的只有值域（`enumValues`）和样本值**——`enumValues` 至今没有任何写入路径，
> 却已经被 `status_field_dictionary` 规则读取。其余各条仍然成立。

当前还缺这些关键能力：

- 字段级血缘
  - **`lineage_to` 关系类型已随 7→3 的类型收敛删除，不会再加回来。**
    2026-08-24 决策：血缘是 n 元推导（`SUM(a)+SUM(b)→c`）、必须带产生上下文
    （视图 / ETL 作业 / Mapper statement）、规模比关系大两个数量级，
    不适合建模成二元边。该做成与 `terms` 平级的第三个集合 `lineage`，
    形态与理由见 [开发清单 P3](dev-checklist-2026-08.zh-CN.md#字段级血缘形态已定等有人真要再做)。
    本文 8 / 12 / 15 章里出现的 `lineage_to` 示例已失效，保留供历史对照
- 更严格的关系约束
  - Web mutation 已执行 `RelationValidator`
  - CLI `add-relation` 和全量 `WorkspaceValidator` 尚未接入相同端点矩阵
- 更完整的字段元数据
  - `unique`、`indexed`、值域、样本值等字段目前大多未抽取或仅预留
- 索引增强
  - 没有 BM25
  - 没有中文 bigram/分词
  - 没有增量索引更新
  - 没有图扩展打分
- 业务知识层
  - 没有领域热度、人工 boost、术语负例、口径实体、FAQ 实体
- Yearning 图谱兼容
  - 当前图谱导入仍基于 JDBC 元数据
  - Yearning 只支持查询链路，不支持图谱初始化

## 22. 当前工作区文件结构

当前工作区根目录为：

```text
config/schema-graphs/{alias}/
```

例如：

```text
config/schema-graphs/mysql-example/
config/schema-graphs/oracle-readonly-example/
config/schema-graphs/sap/
```

### 22.1 顶层目录结构

```text
schema-graphs/{alias}/
  .workspace.lock
  current-generation
  generations/
    {revision}-{uuid}/
      manifest.yaml
      datasource.yaml
      nodes/
      edges/
      changes/
      validation/
  jobs/
  index/
  developer-graph/
```

工作区不增加第二套“人工元数据目录”。数据库导入结果和人工维护结果仍保存在同一组节点和关系文件中，
由字段本身的所有权（`comment` 只由导入写、`description`/`businessName` 只由人工写）、关系类型、
`createdBy`、`updatedBy` 等信息区分来源。
下表中的主数据路径均相对于 `current-generation` 指向的 generation；`jobs`、`index`、
`developer-graph` 和 `.workspace.lock` 位于 alias 根目录。

#### 当前数据来源与修改能力

| 文件或目录 | 主要数据来源 | CLI 修改能力 | Web 后端能力 | 当前 Web UI 页面 | `schema import --from-db` 处理原则 |
| --- | --- | --- | --- | --- | --- |
| `manifest.yaml` | 工作区保存、导入、校验、索引流程 | 间接更新，不允许手工命令直接编辑 | 间接更新 | 不提供直接编辑 | 更新版本、统计和时间戳 |
| `datasource.yaml` | 初始化和数据库导入 | 间接生成 | 只读 | 只读 | 更新数据源基础标识，不保存连接秘密 |
| `nodes/schemas/*.yaml` | 数据库导入 | 暂无 schema 编辑命令 | 暂无修改接口 | 不支持 | 更新数据库拥有的 schema 信息；人工字段应保留 |
| `nodes/tables/**/*.yaml` | 数据库导入、CLI、Web 后端 | `schema edit` 可修改表和字段元数据 | 表、字段 PATCH 接口已实现 | 当前编辑链路不可用 | 更新物理结构；保留明确标记为人工维护的字段 |
| `nodes/terms/*.yaml` | CLI 人工维护 | `schema add-term` | 暂无 | 不支持 | 不创建、不覆盖 |
| `edges/relations.jsonl` | 数据库外键、CLI、Web 后端 | `schema add-relation` 支持校验式 upsert | relation 新增、修改、删除接口已实现 | 当前编辑链路不可用 | 重建声明外键；保留人工/Agent 关系 |
| `changes/changes.jsonl` | CLI/Web 写操作自动产生 | 不允许直接编辑 | mutation 自动追加 | 不直接展示维护 | 导入流程应追加审计，不应清空历史 |
| `validation/issues.jsonl` | 校验器 | `schema validate` 和导入流程生成 | 校验接口可返回结果 | 暂无完整问题处理页 | 导入结束后重新校验 |
| `jobs/**` | 数据库导入任务 | import/status/resume/reset 间接维护 | 暂无 | 不支持 | 记录当前任务、检查点和历史 |
| `index/**` | 索引构建器 | `schema index rebuild` | 后端有重建能力 | 当前无可用重建入口 | 不直接修改；主数据变化后重建 |
| `developer-graph/**` | 开发者文档生成器 | `schema diagram` 生成 | 暂无 | 不支持 | 不直接修改；需要时重新生成 |
| `.workspace.lock` | 工作区写锁 | 系统自动管理 | 系统自动管理 | 不展示 | 与业务导入内容无关 |

Web 后端能力通过 `sql-cli ui` 统一暴露：首页选择有工作区的 alias 后，React 页面通过 URL alias
访问对应的表、字段和关系编辑接口。未导入图谱的 alias 会在首页禁用，不能进入编辑页面。

### 22.2 顶层文件说明

#### `manifest.yaml`

工作区总入口，记录：

- alias
- modelVersion
- storageVersion
- revision
- stats
- `createdAt`
- `updatedAt`
- `lastValidationAt`
- `lastIndexedAt`

这是 CLI 读取图谱时最先依赖的元信息文件。

职责和维护规则：

- 只保存工作区版本、统计和流程时间，不保存表、字段或业务知识。
- 由存储层、导入、校验和索引流程间接维护，CLI 和 Web UI 都不应提供通用编辑入口。
- `revision` 应作为索引、快照和并发修改判断的主数据版本依据。

#### `datasource.yaml`

记录图谱对应的数据源节点，当前主要保存：

- `id = datasource:{alias}`
- `alias`
- `displayName`
- `dbType`

按设计不保存连接密码、host、port、username、secretRef。

职责和维护规则：

- 只标识这个图谱属于哪个数据源，不承担数据库连接配置职责。
- 由工作区初始化和导入流程生成；CLI/Web UI 不直接编辑。
- `schema import --from-db` 可以刷新数据库类型等系统字段，但不得把 secret 写入工作区。

### 22.3 `nodes/` 目录

```text
nodes/
  schemas/
  tables/
  terms/
```

`nodes/` 是正式图谱节点主数据。当前没有独立 `columns/` 目录，字段作为 `TableWorkspaceNode.columns`
内嵌在对应表文件中，这样一张表的结构和字段描述可以一次读取、一次校验和一次保存。

#### `nodes/schemas/`

每个 schema 一个 YAML 文件：

```text
nodes/schemas/{schema}.yaml
```

例如：

```text
nodes/schemas/qm_pct.yaml
nodes/schemas/CITSONLINE.yaml
```

存储内容：

- schema 节点基础元信息
- `name`
- `displayName`
- `description`
- `system`

数据来源和修改规则：

- 当前主要由 `schema import --from-db` 根据 JDBC 元数据创建。
- CLI 暂无 schema 级编辑命令，Web 后端和 Web UI 也没有 schema mutation。
- 数据库导入可以更新 schema 的系统属性；后续增加人工编辑后，`displayName`、`description`
  等人工字段必须按字段所有权保留，不能整文件替换。

#### `nodes/tables/`

按 schema 分目录，每张表一个 YAML 文件：

```text
nodes/tables/{schema}/{table}.yaml
```

例如：

```text
nodes/tables/qm_pct/pct_contract_case.yaml
nodes/tables/CITSONLINE/HZ_SK_YS_ORDER_INFO.yaml
```

存储内容：

- 表节点基础元信息
- `schema`
- `name`
- `qualifiedName`
- `comment`
- `tableType`
- `primaryKey`
- `columns`
- `tags`

这里同时保存表级主数据和内嵌的字段节点：

- 表物理结构：`schema`、`name`、`qualifiedName`、`tableType`、`primaryKey`
- 字段物理结构：字段名、类型、可空性、默认值、序号、主键标记
- 人工知识：表/字段描述、业务名称、标签、术语、样例值、约束说明

数据来源和修改规则：

- `schema import --from-db` 创建和刷新表、字段物理结构以及数据库 comment。
- CLI `schema edit --table` 可修改表描述和标签。
- CLI `schema edit --column` 可修改字段描述、样例、标签和约束。
- Web 后端已有表和字段 PATCH 接口，但当前 Web UI 编辑链路不可用。

导入覆盖边界：

- 数据库拥有表类型、主键、字段类型、可空性、默认值、序号等物理结构字段。
- 人工拥有业务名称、标签、样例值和人工约束等业务字段；术语映射单独维护为关系边。
- `comment`（数据库注释镜像）和 `description`（业务描述）拆成两个字段，各自只有一个写入方：
  `comment` 只由 `schema import --from-db` / `ClickHouseWorkspaceMetadataProvider` 写入，重导入时
  incoming 永远覆盖，不做分支判断；`description` 只由 CLI `schema edit --description` 和 Web
  的人工编辑写入，重导入时 existing wins。
- 所有权因此由字段本身固定，不再依赖运行时标记：数据库注释写错了，正确的修法是去库里改完再
  重新导入，不把改数据库注释的权力交给 Agent 或 UI。旧设计里 `comment` 一个字段两个来源，靠
  `commentSource` 区分 `db`/`user`，一旦写成 `user` 就是个静默的单向闸门——Agent 写过一次业务
  描述，那一列的库注释从此再也导不进来，DBA 后来在库里补的注释也进不来。字段拆分之后这个闸门
  连同 `commentSource` 一起消失。

#### `nodes/terms/`

每个术语一个 YAML 文件：

```text
nodes/terms/{term}.yaml
```

存储内容通常包括：

- `name`
- `displayName`
- `description`
- `aliases`
- `negativeAliases`

职责和维护规则：

- 只保存业务术语、别名和定义，不保存节点映射。
- 由 CLI `schema add-term` 创建或更新。
- `schema add-term --map` 将映射写入 `edges/relations.jsonl`，不会修改 Term 或 Column 内容。
- 当前 Web 后端和 Web UI 均无术语维护能力。
- 数据库导入不创建、不更新、不删除术语。

### 22.4 `edges/` 目录

#### `edges/relations.jsonl`

图谱关系边集合，JSON Lines 格式，一行一条关系。

当前主要存储：

- `foreign_key_declared`
- 人工补充 relation

每条关系包含：

- `id`
- `type`
- `from`
- `to`
- `direction`
- `cardinality`
- `joinExpression`
- `weight`

当前主关系粒度以字段到字段为主。

数据来源和修改规则：

- 数据库导入根据 JDBC 外键元数据生成 `foreign_key_declared`。
- CLI `schema add-relation` 按 `type + from + to` 校验并 upsert 关系，但当前没有独立 delete 命令。
- Web 后端已支持人工关系的新增、修改和删除，并限制声明外键的人工删除；当前 Web UI 编辑链路不可用。
- 每次导入应以数据库当前结果替换 `foreign_key_declared`，同时完整保留 `foreign_key_inferred`、
  `join_observed`、`lineage_to`、`semantic_related` 等人工或 Agent 关系。
- CLI 目前仍允许直接新增 `foreign_key_declared`，与关系所有权规则不一致，应限制为仅由数据库导入产生。

### 22.5 `changes/` 目录

#### `changes/changes.jsonl`

记录图谱主数据变更。

典型变更包括：

- `upsert_table`
- `upsert_column`
- `upsert_relation`
- 通用 `upsert`

这个文件用于追踪图谱是如何逐步被补齐的。

职责和维护规则：

- 由 CLI 和 Web mutation 自动追加，调用方不直接编辑。
- 应记录 actor、操作类型、目标对象、变更前后摘要和时间。
- 数据库导入也应形成可追踪的批次或对象变更记录；当前导入审计还不完整。
- 这是审计日志，不是恢复主数据的唯一来源，也不应在导入时重写或清空。

### 22.6 `validation/` 目录

#### `validation/issues.jsonl`

记录校验问题列表。

当前主要问题类型：

- `missing_manifest`
- `missing_required_field`
- `duplicate_node_id`
- `duplicate_relation_id`
- `dangling_relation_source`
- `dangling_relation_target`
- `invalid_confidence`

职责和维护规则：

- CLI `schema validate` 和导入完成后的校验流程生成该文件。
- Web 校验接口可以返回校验结果，但当前不保证把独立校验请求持久化为 `issues.jsonl`。
- 文件是当前校验结果快照，不是人工维护的任务清单；重新校验可以替换旧结果。

### 22.7 `jobs/` 目录

```text
jobs/
  import-current.yaml
  import-history/
  tasks/
```

#### `jobs/import-current.yaml`

当前正在运行或最近一次未完成导入任务的状态文件。

存储内容包括：

- `jobId`
- `status`
- `currentPhase`
- `message`
- `stats`
- `checkpoint`
- `options`

这是断点续跑的核心文件。

该文件由导入任务管理器维护，CLI/Web UI 不应直接修改。

#### `jobs/import-history/`

保存历史导入任务归档：

```text
jobs/import-history/{jobId}.yaml
```

每个文件保存一次导入任务结束时的状态、参数、统计和错误摘要，用于审计和诊断。

#### `jobs/tasks/`

保存某次导入任务的 task 列表：

```text
jobs/tasks/{jobId}.json
```

每个 task 至少包含：

- `taskId`
- `type`
- `schema`
- `table`
- `status`
- `attempt`
- `errorMessage`

当前 task 文件是一个 JSON 数组文件，不是 JSONL。

`jobs/` 只描述导入执行过程，不属于图谱主数据。reset 任务状态不能顺带删除已经保存的人工节点或关系。

### 22.8 `index/` 目录

#### `index/manifest.json`

索引清单文件，记录索引版本、构建时间、对象统计、分片大小限制和分片文件名。

#### `index/documents-<generation>-<number>.json`

索引文档分片。所有分片使用紧凑 JSON，默认单文件不超过 1 MiB。新索引构建完成前不会替换
manifest，避免检索读取到只写了一部分的索引。

manifest 存储内容包括：

- `alias`
- `version`
- `builtAt`
- `sourceRevision`
- `documentCount`
- `typeCounts`
- `shards`
- `maxShardBytes`

分片中的 documents 当前主要索引：

- table
- column
- term
- domain

索引只支持当前 `manifest.json + documents 分片` 格式。

每个 `IndexDocument` 主要包含：

- `id`
- `type`
- `title`
- `schema`
- `table`
- `column`
- `fields`

当前索引是可重建的派生数据，不属于主数据。

职责和维护规则：

- CLI `schema index rebuild` 从当前工作区主数据重新生成索引。
- Web 后端具备索引重建能力，但当前 Web UI 没有可用的重建入口。
- 数据库导入不直接编辑索引文档；导入或人工修改导致 `manifest.revision` 变化后，旧索引应被识别为过期。
- 工作区中若存在 `index/index.json`，它不属于当前格式，应直接删除且不提供兼容读取。
  当前实现只使用 `manifest.json + documents-*.json`。

### 22.9 `developer-graph/` 目录

```text
developer-graph/
  README.md
  overview.md
  catalog-*.md
  relations-*.md
  manifest.json
```

职责和维护规则：

- 由 CLI `schema diagram` 根据当前节点和关系生成面向开发者阅读的 Markdown 图谱资料。
- `README.md` 是入口，`overview.md` 是总体概览，`catalog-*.md` 是分片的数据目录，
  `relations-*.md` 是关系分片，`manifest.json` 记录生成批次和文件清单。
- 这些文件不是主数据，不能反向覆盖 `nodes` 或 `edges`。
- 当前 Web 后端/Web UI 不生成或编辑该目录。

### 22.10 `.workspace.lock`

工作区写操作使用的临时并发锁文件：

- 由存储层自动创建和释放。
- 不包含业务数据，不纳入导出、索引或图谱统计。
- CLI 和 Web UI 都不允许人工编辑。
- 异常退出后若存在残留，应由锁实现判断是否过期，不能把“文件存在”直接等同于仍有任务运行。

### 22.11 单工作区下的导入所有权规则

不新增存储层的前提，是所有写入口必须遵守同一套字段所有权：

| 数据类别 | 所有者 | 导入行为 |
| --- | --- | --- |
| schema/table/column 物理结构 | 数据库 | 新增、更新；数据库中消失的对象按导入策略标记失效或删除 |
| 数据库 comment（`comment` 字段） | 数据库 | 始终刷新，incoming 覆盖，无分支判断 |
| 人工描述、业务名、标签、样例、业务约束 | 用户 | 始终保留 |
| term 及映射 | 用户 | 始终保留 |
| `foreign_key_declared` | 数据库 | 按本次完整外键结果重建 |
| 推断关系、观察关系、血缘和语义关系 | 用户/Agent | 始终保留 |
| changes | 用户/Agent/系统日志 | 追加，不由数据库导入清空 |
| validation、index、developer-graph | 派生流程 | 可以重新生成 |

这一点已经通过字段拆分解决：`comment` 与 `description`（业务名同理）分属两个字段，各自只有一个
写入方，不再需要运行时标记去仲裁覆盖。“文件分层”确实只能改变覆盖发生的位置——真正解决所有权
不清的是把来源固定在字段本身，而不是再加一层目录或标记。

## 23. 当前实现与设计的对应关系

### 23.1 已与设计基本一致

- `Manifest`
- `DataSource`
- `SchemaNode`
- `TableNode`
- `ColumnNode`
- `RelationEdge`
- `ChangeRecord`
- `ValidationIssue`
- 结构化工作区落盘
- 独立索引目录
- 导入 job 目录

### 23.2 已实现但与原设计有差异

- `ColumnWorkspaceNode` 当前内嵌在 table YAML 中，而不是“每字段一个文件”
- `TermNode` 已实现，主要靠手工补充
- `DomainNode` 已有代码但没有实际工作区数据，按精简模型应删除
- 索引层已经有第一版快照和检索，不再只是“未来预留”
- 当前只保留结构化工作区目录；JSON 快照仅用于显式 export/import

### 23.3 仍需继续对齐设计

- relation 类型端点约束
- 低可信写入覆盖保护
- 更完整的状态流转规则
- 字段级值域、样本、枚举自动抽取
- 字段级 lineage
- 更强的 explainable search 输出

## 24. 后续开发清单

> **2026-08-22：本章已合并进 [dev-checklist-2026-08](dev-checklist-2026-08.zh-CN.md)，
> 从那里取待办。** 其中 24.1 的 validator 与 ChangeRecord 已完成；
> 24.3 的「字段级值域、样本、枚举」已完成（`schema edit --enum-values`）；
> 24.2 检索增强与 24.4 工程化补强仍在清单里。以下保留原文作为设计依据。

下面的清单按“基座补强 -> 检索增强 -> 业务增强”排序。

### 24.1 P0：基座补强

- 清理无实际数据来源的 Domain 模型
  - 业务分类继续使用 `tags`
  - 业务术语和跨表映射统一使用 `TermNode` 与 `term_mapping`
- 完善 `WorkspaceValidator`
  - 校验 relation 类型与端点类型是否匹配
  - 校验 table/column 的父子引用一致性
- 完善 `ChangeRecord`
  - 增加更细的操作类型
  - 增加 before/after 摘要或 patch 信息
- 为导入任务增加更清晰的控制能力
  - `pause`
  - 对失败 task 的重试
  - 任务级摘要报告

### 24.2 P1：检索增强

- 增强 `manifest.json + documents 分片` 索引
  - 保持字段级索引，不做无意义细分词
  - 强化 table / column / qualifiedName 命中
- 增量索引更新
  - table 修改后只更新受影响文档
  - relation 修改后补充相关字段权重
- 改进搜索解释
  - 输出命中字段
  - 输出命中原因
  - 输出相关表/相关字段摘要
- 增加中文检索能力
  - 先做 bigram 或轻量 token
  - 不引入向量数据库

### 24.3 P1：图谱内容增强

- 抽取字段级血缘
  - SQL
  - 视图定义
  - ETL 语句
- 增加业务术语批量导入
- 增加表/字段权重
  - 热度
  - 领域重要性
  - 人工 boost
- 增加更多关系类型
  - `lineage_to`
  - `term_map`
  - `same_as`

### 24.4 P2：工程化补强

- 增加图谱测试集
  - 搜索命中
  - 路径查找
  - relation 校验
  - 增量导入恢复
- 增加导出/导入的严格模型版本校验
- 增加工作区诊断命令
  - 目录完整性检查
  - 丢失文件检查
  - 索引与主数据一致性检查
- 增加面向页面展示的查询模型
  - 表详情聚合输出
  - 表关系图查询输出
  - 变更时间线输出

### 24.5 当前建议的开发优先级

建议按下面顺序推进：

1. 先补强 validator 和变更审计。
2. 再做增量索引更新和搜索解释。
3. 然后做字段级血缘和业务术语增强。
4. 最后再做页面查询模型和更复杂的图检索。

这样可以保证：

- 主数据先稳定
- CLI 写入边界先清晰
- Agent 后续补图不会把工作区结构搞乱
- 后续页面层和检索层都建立在稳定主模型之上
