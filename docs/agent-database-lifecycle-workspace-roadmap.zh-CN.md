# Agent 数据库生命周期工作台：定位总结与扩展待办

> **状态（2026-08-20）**：本文余下未完成项不再单独排期。执行以
> [dev-checklist-2026-08](dev-checklist-2026-08.zh-CN.md) 与
> [P0 清单](archive/ui-human-control-plane-agent-cli-mcp-p0-checklist.zh-CN.md) 为准；
> 已完成项作为实现记录保留。scan-sql 相关内容已随功能删除失效。

> 讨论沉淀日期：2026-06-20
>
> 目标：记录本轮关于 `sql-cli` 后续定位、领域视图、规则层和 Agent 数据库生命周期能力的讨论结论，避免后续扩展偏离方向。

## 0. 当前实施状态（2026-07-20）

本路线图中优先实施的 `P0-1`、`P0-2`、`P1-1`、`P1-2`、`P1-4` 已完成最小可用闭环：可信导入与统一写入、规则审计和豁免、SQL/MyBatis 关系候选扫描均已实现并通过回归测试。具体实现状态、命令和未扩展的验收项见 [优先开发清单](agent-database-lifecycle-priority-development-checklist.zh-CN.md)。

本轮额外收口了跨 schema FK、导入审计 hash、Web 校验/索引 revision、一致性 `skipped` 规则结果以及 SQL/Mapper 的 `path:line` evidence。

## 1. 项目定位

`sql-cli` 不应只定位为数据库查询 CLI，也不应只停留在 Schema 知识图谱工具。

更准确的目标是：

```text
面向 Agent 的数据库生命周期工作台
```

英文可暂定为：

```text
Agent Database Lifecycle Workspace
```

项目希望在 Web 项目开发过程中，让 Agent 能借助 CLI 完成数据库相关工作的全生命周期辅助：

- 理解现有数据库结构和业务语义。
- 根据需求辅助设计新表、新字段和关系。
- 审查 DDL、migration、SQL 和变更风险。
- 遵守项目或数据库级设计规则。
- 根据业务入口排查数据问题。
- 生成修复 SQL、恢复 SQL 和验证 SQL。
- 记录变更、规则命中、风险判断和审计信息。

一句话概括：

```text
sql-cli = Agent 的数据库上下文、规则引擎、操作入口和审计工具
```

## 2. 已形成的核心共识

### 2.1 DDL、图谱、领域视图和规则层职责不同

```text
DDL / 元数据层：数据库当前结构事实
关系层：表字段之间的 FK、推断关系、join、lineage、depends_on
领域语义层：业务对象、术语、字段口径、敏感字段、状态含义
任务视图层：排查、建表、改表、迁移、修复、验证流程
规则策略层：设计和变更时必须遵守的约束
```

这些层不是互相替代关系。

- DDL 回答“数据库里有什么”。
- 图谱关系回答“对象之间怎么连”。
- 领域视图回答“这些表字段承载什么业务意义”。
- 规则层回答“Agent 操作时必须遵守什么约束”。
- 任务视图回答“面对一个数据库任务时应该怎么做”。

### 2.2 领域视图需要，但不应急于引入 DomainNode

当前设计文档已经明确：精简模型中 `DomainNode` 不进入推荐主模型，其分类能力先由 `tags` 和 `TermNode` 覆盖。

因此领域视图应该先作为可重建的派生视图，而不是立即新增一等主节点。

建议路径：

```text
TermNode + tags + businessName + semanticType + RelationEdge + index
  -> 生成领域视图
```

只有当未来出现稳定治理需求时，才考虑把领域建成主模型，例如：

- 领域负责人。
- 领域层级。
- 领域权限。
- 领域级质量指标。
- 领域级生命周期事件。

### 2.3 Agent 可以总结，但不能只存总结

领域视图不能只落一段自然语言说明。

Agent 从需求文档、源码、工单和 SQL 历史中总结出的内容，应拆成结构化对象写入：

- `TermNode`
- `businessName`
- `semanticType`
- `tags`
- `RelationEdge`
- 字段口径
- 枚举/字典说明
- 敏感字段策略
- 排查 playbook 候选
- 验证 SQL 候选

每条由 Agent 写入的知识都必须带：

- 来源类型。
- 来源引用。
- 置信度。
- 是否人工验证。
- 创建者。
- 变更原因。

否则图谱会变成不可追溯的“业务总结库”。

### 2.4 规则层必须纳入 CLI 管理

每个数据库或项目都有设计规范，例如必备字段、命名、金额类型、敏感字段、索引、迁移安全等。

这些规则不能只放在文档里，应进入 CLI，成为 Agent 设计、审查、排查、修复时的硬约束。

推荐抽象：

```text
RuleSet
PolicyRule
PolicyViolation
RuleEvaluation
RuleWaiver
```

规则结果应能解释：

- 命中哪条规则。
- 哪个对象违规。
- 严重等级是什么。
- 为什么违规。
- 如何修复。
- 是否允许豁免。

### 2.4.1 SQL 方言差异也属于规则资产

这里的规则层，不应只包含“禁止做什么”的约束，也应包含“针对某数据库/版本应该怎么写”的方言规则。

例如：

- MySQL 5.7 和 8.0 的分页、窗口函数、JSON 能力不同。
- Oracle 11g 和 12c+ 的分页推荐写法不同。
- PostgreSQL、ClickHouse 在 schema/catalog、时间函数、标识符引用上有明显差异。

这些内容不适合继续散落在 prompt、经验或临时文档里，应和 `PolicyRule` 一样，进入统一的规则资产模型。

可以把它理解为两类规则：

1. `dialect rule`
   作用：告诉 Agent “怎么写更对”
2. `policy rule`
   作用：告诉 Agent “什么不能做 / 必须满足什么”

因此后续不单独建设“知识库系统”，而是建设统一的“规则资产”。

### 2.4.2 统一 YAML 外壳

虽然 SQL 方言规则和生命周期规则关注维度不同，但建议共用同一套 YAML 外壳和条目字段。

文档层建议统一字段：

| 字段 | 说明 |
| --- | --- |
| `kind` | 文档类型，如 `dialect-ruleset`、`policy-ruleset` |
| `id` | 文档唯一标识 |
| `title` | 文档标题 |
| `version` | 文档版本 |
| `dbType` | 作用数据库类型 |
| `match` | 命中条件，如产品名、版本号、alias、环境等 |
| `scope` | 作用范围 |
| `defaults` | 默认行为，如 `severity`、`enforcement` |
| `rules` | 规则条目数组 |
| `sources` | 来源信息 |
| `metadata` | 扩展字段 |

条目层建议统一字段：

| 字段 | 说明 |
| --- | --- |
| `id` | 规则唯一标识 |
| `title` | 规则标题 |
| `category` | 分类，如 `pagination`、`naming`、`safety` |
| `topic` | 主题，如 `json`、`timezone`、`migration` |
| `when` | 触发条件 |
| `statement` | 规则正文 |
| `reason` | 原因说明 |
| `examples` | 示例 |
| `priority` | 优先级 |
| `severity` | 严重级别 |
| `enforcement` | 执行方式，如 `advisory` / `required` / `blocking` |
| `remediation` | 修复建议 |
| `tags` | 标签 |
| `sources` | 条目级来源 |

统一的关键字段是：

- `when`
- `severity`
- `enforcement`
- `examples`
- `sources`

约束建议：

1. SQL 方言规则默认使用 `kind: dialect-ruleset`、`enforcement: advisory`
2. 生命周期规则默认使用 `kind: policy-ruleset`，可提升为 `required` 或 `blocking`
3. 两类规则共用同一套解析器、展示模型和审计字段，避免维护两套 YAML 规范

### 2.5 外键策略不能硬编码

公开规范对物理外键存在不同取舍。

- 部分高并发或分布式场景不建议使用数据库物理外键。
- PostgreSQL、GitLab 等资料强调外键对数据一致性的价值。

因此项目不应内置唯一答案，而应配置化：

```yaml
foreignKeyPolicy:
  physicalForeignKey: forbidden | allowed | required
  inferredRelation: required
  joinColumnIndex: required
  cascadeDelete: forbidden | allowed
```

即使不使用物理外键，也应该在图谱中维护逻辑关系，如 `foreign_key_inferred` 和 `join_observed`。

## 3. 数据库生命周期阶段

面向 Web 项目的数据库生命周期可以按以下阶段理解：

```text
需求理解
-> 领域建模
-> 表结构设计
-> 迁移开发
-> 数据访问开发
-> 测试与种子数据
-> 上线发布
-> 运行观测
-> 问题排查与数据修复
-> 结构演进
-> 下线归档
```

`sql-cli` 后续能力应围绕这些阶段组织，而不是只围绕 `schema import/search/diagram`。

## 4. 领域视图需要的原始数据

构建领域视图需要多类输入。

| 原始数据 | 可抽取内容 | 用途 |
| --- | --- | --- |
| DDL / JDBC 元数据 | 表、字段、类型、主键、索引、外键、注释 | 物理事实底座 |
| Migration 脚本 | 建表原因、演进历史、回填逻辑、废弃记录 | 生命周期历史 |
| 源码 SQL / ORM / Mapper | 实际 join、where 条件、字段使用、查询入口 | 真实使用关系 |
| API / Controller / Service | 接口和业务对象入口 | 业务流程定位 |
| DTO / Entity / Model | 代码对象与表字段映射 | 业务对象候选 |
| 需求文档 / PRD | 业务术语、流程、状态、规则 | 领域语义来源 |
| 技术方案 / 设计文档 | 表设计意图、兼容方案、边界 | 建模依据 |
| 工单 / 排障记录 | 常见问题、排查路径、修复 SQL、验证 SQL | 问题排查 playbook |
| SQL 查询历史 / 慢 SQL | 热表、热字段、真实 join、核心路径 | 权重和关系置信度 |
| 测试用例 / 种子数据 | 状态流转、边界场景、字段约束 | 验证规则 |
| 枚举代码 / 字典表 | 状态值、类型值、业务含义 | 字段口径 |
| 数据 profile / 安全采样 | 枚举值、空值率、格式、唯一性 | 字段语义推断 |

第一阶段不必全部接入，优先级建议：

1. DDL / JDBC 元数据。
2. 源码 SQL / ORM / Mapper。
3. Markdown 需求和设计文档。
4. 工单和排障记录。
5. 枚举代码和字典表。

## 5. 预置规则方向

已沉淀独立调研文档：

- [数据库生命周期规则调研与预置规则草案](database-lifecycle-policy-rules-research.zh-CN.md)

首批建议内置规则：

| 规则 ID | 规则名称 | 目标 |
| --- | --- | --- |
| `required_business_columns` | 业务表必备字段 | 检查主键、创建时间、更新时间、租户字段等 |
| `naming_convention` | 命名规范 | 检查表名、字段名、索引名、约束名 |
| `controlled_vocabulary` | 标准词表与统一命名 | 同一业务概念必须使用统一字段名，如手机号统一用 `phone`，避免混用 `tel`、`mobile` |
| `money_type_decimal` | 金额字段类型 | 禁止用浮点类型存金额 |
| `business_unique_index` | 业务唯一键 | 业务编号字段必须有唯一索引 |
| `relation_column_index` | 关联字段索引 | join/关联字段必须有索引或说明 |
| `join_column_type_match` | 关联字段类型一致 | 检查 join 两端类型兼容 |
| `status_field_dictionary` | 状态字段字典 | 状态字段必须有枚举或字典说明 |
| `sensitive_column_policy` | 敏感字段策略 | 敏感字段必须标记并声明加密/脱敏规则 |
| `dangerous_dml_guard` | 危险 DML 保护 | 更新/删除必须有条件、恢复 SQL 和验证 SQL |
| `migration_safety_check` | 迁移安全检查 | 检查锁表、回滚、回填、兼容和验证方案 |

### 5.1 SQL 方言规则也是预置规则的一部分

除上述项目/生命周期规则外，还应预置一批数据库版本相关的方言规则。

首批建议覆盖：

| 主题 | 说明 |
| --- | --- |
| `pagination` | 各数据库/版本的分页推荐写法 |
| `datetime` | 当前时间、日期计算、时区相关函数 |
| `identifier` | 标识符引用与大小写语义 |
| `schema` | schema/catalog/current schema 语义差异 |
| `json` | JSON 提取、过滤、更新写法 |
| `string` | 字符串处理函数差异 |
| `upsert` | `insert ... on duplicate key` / `merge` / `on conflict` 差异 |

这些规则不用于“阻断”，而用于“给 Agent 正确写法提示”。

### 5.2 统一 YAML 模版

建议统一采用扁平 `rules[]` 结构，不再额外设计一套“知识库专用格式”。

示例一：SQL 方言规则

```yaml
kind: dialect-ruleset
id: mysql-8.0-dialect
title: MySQL 8.0 Dialect Rules
version: "1"
dbType: mysql
match:
  productNameIncludes: ["MySQL"]
  versionRegex: "^8\\."
scope:
  operationAny: [query, ddl, troubleshooting, verification]
defaults:
  severity: info
  enforcement: advisory
rules:
  - id: pagination.limit
    title: MySQL 分页语法
    category: query
    topic: pagination
    when:
      operationAny: [query, troubleshooting, verification]
    statement: 使用 LIMIT [offset,] size 或 LIMIT size OFFSET offset
    reason: MySQL 的主推荐分页语法是 LIMIT，而不是 Oracle 风格 FETCH FIRST
    examples:
      - sql: "SELECT * FROM `orders` ORDER BY `id` DESC LIMIT 100"
    remediation:
      - 不要生成 FETCH FIRST 100 ROWS ONLY
```

示例二：生命周期约束规则

```yaml
kind: policy-ruleset
id: default-web-policy
title: 默认 Web 数据库规则集
version: "1"
dbType: mysql
scope:
  environmentAny: [dev, test, prod]
defaults:
  severity: error
  enforcement: required
rules:
  - id: required_business_columns
    title: 业务表必备字段
    category: design
    topic: columns
    when:
      tableTagsAny: [business]
      operationAny: [design, migration]
    statement: 业务表必须包含主键、创建时间、更新时间
    reason: 保证生命周期审计和后续排查能力
    examples:
      - sql: |
          CREATE TABLE orders (
            id BIGINT PRIMARY KEY,
            created_at DATETIME NOT NULL,
            updated_at DATETIME NOT NULL
          )
    remediation:
      - 添加 id / created_at / updated_at
```

### 5.3 规则资产落点建议

既然这里讨论的是统一规则资产，而不是独立知识库，建议目录也统一表达这一点。

推荐后续目录：

```text
src/main/resources/rules/
  dialect/
    mysql/8.0.yaml
    mysql/5.7.yaml
    postgresql/14.yaml
    oracle/19c.yaml
    clickhouse/24.x.yaml

config/schema-graphs/<alias>/policy/
  bindings.yaml
  rules/
    app-policy.yaml
```

职责：

1. classpath `rules/dialect/` 存数据库类型/版本差异规则，并随 JAR 发布
2. `<alias>/policy/rules/` 存项目、环境、领域和 alias 专属约束
3. `<alias>/policy/bindings.yaml` 声明普通 policy check 长期启用的规则
4. 两者共用统一 YAML 模型；实际评估规则快照进入该 alias 的 `policy/runs/`

## 6. 后续能力地图

### 6.1 理解数据库

目标：让 Agent 能回答“这个业务概念在哪些表字段里”。

待办：

- 强化 `TermNode`：同义词、反义/排除词、定义、来源、验证状态。
- 给表和字段补齐 `businessName`、`semanticType`、`tags`。
- 从 SQL 和源码抽取 `join_observed`。
- 从命名、类型和唯一性推断 `foreign_key_inferred`。
- 建立字段级索引，而不是只做表级索引。
- 支持中文、snake_case、缩写、数字混排的分词。
- 引入表/字段热度、人工 boost、领域过滤。

### 6.2 设计数据库

目标：让 Agent 能根据需求设计新表，并判断是否和现有模型冲突。

待办：

- 设计 `schema design propose` 能力。
- 设计 `schema design review` 能力。
- 输入草稿 DDL 后，返回相似表、相似字段、命名建议、缺失字段、缺失索引、敏感字段提示。
- 检查是否重复创建已有业务对象。
- 支持按领域查找已有设计惯例。
- 支持生成设计说明和待确认问题。

### 6.3 审查 Migration

目标：让 Agent 在改库前识别上线风险。

待办：

- 设计 `migration lint` 能力。
- 检查大表加字段、改类型、删字段、重命名字段、加索引、加约束的风险。
- 要求回滚方案、precheck SQL、postcheck SQL。
- 对回填任务要求分批、断点、幂等。
- 支持数据库类型差异，如 MySQL、PostgreSQL、Oracle、ClickHouse。

### 6.4 辅助 SQL 开发

目标：让 Agent 生成 SQL 时有上下文和规则约束。

待办：

- SQL 解析后匹配图谱中的表、字段和关系。
- 对查询 SQL 给出 join 路径解释。
- 对写 SQL 执行危险操作检查。
- 检查 `select *`、深分页、大 `IN`、前缀模糊查询、动态 SQL allow-list。
- 与现有 SM4 加解密能力联动，识别敏感字段查询和展示风险。

### 6.5 排查数据问题

目标：让 Agent 从业务入口生成排查路径、修复方案和验证方案。

待办：

- 设计 `issue diagnose` 或类似能力。
- 支持从业务编号、手机号、订单号等入口定位核心表。
- 基于领域视图和关系图生成多跳查询路径。
- 沉淀常见问题模式，如状态不一致、金额不平、关联缺失、重复数据。
- 生成修复 SQL 前必须生成查询证据和恢复 SQL。
- 修复后必须生成验证 SQL。
- 将排查步骤沉淀为 playbook 候选。

### 6.6 运行观测和持续演进

目标：让图谱反映数据库真实使用情况。

待办：

- 导入查询历史和慢 SQL。
- 计算表/字段热度。
- 识别长期不用字段和疑似废弃表。
- 记录图谱关系覆盖率。
- 支持 schema diff 和开发者图差异。
- 支持字段废弃、表下线、归档计划和依赖检查。

## 7. 近期优先级建议

### P0：修稳现有图谱底座

这些是继续扩展前的前置条件：

- 修复数据库导入分批误废弃问题。
- 修复常规导入 FK 丢失问题。
- 修复 FK 删除检测问题。
- 统一 CLI、导入、Web UI 写入路径的 mutation、revision、锁和关系校验。
- 补齐 `RelationValidator` 在所有写入入口和全量校验中的使用。
- 解决 Web UI 当前阻塞问题，保证图谱可视化和编辑可用。

原因：领域视图、规则层和 Agent 任务视图都依赖可信的图谱主数据。

### P1：先做规则层最小闭环

最小闭环：

```text
RuleSet / PolicyRule 定义
-> 对 GraphWorkspace 执行规则评估
-> 生成 RuleEvaluation / PolicyViolation
-> CLI 输出可解释结果
-> 严重问题同步到 ValidationIssue
```

优先实现结构型规则：

- 命名。
- 标准词表与统一命名。
- 必备字段。
- 字段类型。
- 主键。
- 唯一索引。
- 敏感字段。

### P1：做领域视图的原始数据接入

优先做两类高价值输入：

- 源码 SQL / Mapper 扫描。
- Markdown 需求/设计文档抽取。

先落结构化候选，不直接写死总结。

### P2：再做完整 GraphRAG

不要过早把重点放在向量数据库或复杂 RAG 编排上。

先把结构化图谱、规则、字段级索引、领域术语和评估集做好，再考虑：

- BM25。
- embedding。
- rerank。
- 多跳 GraphRAG。
- 自动答案生成。

## 8. 待澄清问题

这些问题在本轮讨论中尚未完全确定，后续设计前需要明确。

### 8.1 数据源接入范围

需要确定第一期支持哪些来源：

- 只支持本地文件，还是支持 Git 仓库扫描。
- 是否接入工单系统。
- 是否接入 SQL 查询历史。
- 是否接入慢 SQL。
- 是否接入 ORM metadata。
- 是否允许采样数据库值用于 profile。

### 8.2 Agent 写入权限

需要确定 Agent 可以直接写入哪些对象：

- Term 是否允许直接写入。
- tags 是否允许直接写入。
- businessName 是否允许直接写入。
- Relation 是否允许直接写入，是否必须人工确认。
- PolicyRule 是否只能人工维护。
- Playbook 是否先作为候选，还是可直接落主数据。

### 8.3 人工验证流程

需要设计：

- 哪些内容必须 `verified=true` 才能参与高风险任务。
- 低置信候选如何展示。
- 谁可以确认。
- 确认是否写入 ChangeRecord。
- 过期知识如何重新验证。

### 8.4 规则豁免机制

需要设计：

- 是否允许豁免。
- 豁免是否必须有原因。
- 豁免是否有过期时间。
- 豁免是否按对象、规则、环境区分。
- Agent 是否允许创建豁免，还是只能建议豁免。

### 8.5 CLI 命令边界

需要遵守仓库约束：同一功能只允许一个 CLI 入口，不增加别名命令或另一套等价语法。

后续新增命令时需要特别谨慎，可能优先在 `schema` 子命令下组织：

```text
schema policy ...
schema design ...
schema migration ...
schema issue ...
```

具体命名仍需设计。

### 8.6 与现有恢复 SQL 的关系

现有 `RecoveryBuilder` 已为 UPDATE/DELETE 生成恢复 SQL。

需要确定：

- 规则层如何调用或复用恢复 SQL 能力。
- 修复 SQL 的验证 SQL 如何生成。
- recovery 文件如何进入审计。
- Web UI 是否展示恢复和验证结果。

### 8.7 ClickHouse 等分析型数据库的差异

规则不能只面向 MySQL。

需要区分：

- OLTP 业务库。
- OLAP / ClickHouse。
- Oracle。
- PostgreSQL。

例如 ClickHouse 可能没有传统主键/外键语义，规则层必须按 dbType 和 tableType 差异化。

### 8.8 领域视图是否需要正式主模型

当前建议不引入 `DomainNode`。

但如果未来出现以下需求，需要重新评估：

- 领域负责人。
- 领域权限。
- 领域质量评分。
- 领域级发布流程。
- 跨数据库领域映射。
- 领域生命周期。

## 9. 防偏航原则

后续扩展应遵守以下原则：

1. 不要把项目做成普通数据库客户端。
2. 不要只做可视化 Schema 图。
3. 不要让 Agent 写入不可追溯的自然语言总结。
4. 不要先上复杂 RAG，再补结构化事实。
5. 不要把某一家公司的数据库规范硬编码为唯一规则。
6. 不要把领域视图和 DDL 混为一谈。
7. 不要让规则只存在于文档，必须可执行、可解释、可审计。
8. 不要绕过现有图谱主模型随意新增平行存储。
9. 不要扩展高风险自动修复能力，直到恢复 SQL、验证 SQL、审计和权限都闭环。
10. 不要忽视数据库类型差异和项目级规则差异。

## 10. 建议下一步

建议接下来先做两份更细设计：

1. `PolicyRule` 规则层模型与 CLI 设计。
2. 领域视图生成模型与原始数据接入设计。

其中优先级最高的是规则层，因为它能立即服务“设计新表、审查 DDL、危险 SQL 防护、数据修复验证”等多个目标。
