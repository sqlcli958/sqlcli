# 数据库生命周期规则调研与预置规则草案

> 调研日期：2026-06-20
>
> 目标：为 `sql-cli` 后续引入面向 Agent 的数据库生命周期规则层提供资料来源、规则分类和首批预置规则建议。

## 1. 背景

`sql-cli` 的长期定位不只是查询数据库或浏览 Schema 图谱，而是辅助 Agent 在 Web 项目开发中完成数据库设计、开发、变更、上线、排查、修复和演进。

在这个目标下，数据库规范不应只保存在团队文档里，而应进入 CLI 管理，成为 Agent 的硬约束和审查依据：

```text
DDL / 元数据层：数据库当前结构事实
领域视图层：表字段承载的业务含义
规则策略层：设计、变更、查询和修复必须遵守的约束
任务执行层：Agent 的设计、审查、排查、修复、验证流程
```

本文先整理公开资料，再抽象出适合项目内置的规则清单。规则不直接照搬某一家规范，而是转化为可配置、可审计、可解释的 `PolicyRule`。

## 2. 参考资料

### 2.1 阿里巴巴 Java Coding Guidelines

来源：<https://alibaba.github.io/Alibaba-Java-Coding-Guidelines/>

相关章节：MySQL Rules。

可参考内容：

- 表名、字段名使用小写和下划线风格。
- 表名不使用复数名词。
- 主键索引名建议使用 `pk_` 前缀，唯一索引使用 `uk_` 前缀，普通索引使用 `idx_` 前缀。
- 小数类型使用 `decimal`，不使用 `float` 或 `double` 存储金额。
- 业务表建议包含主键、创建时间、修改时间。
- 唯一业务约束需要建立唯一索引。
- 高并发分布式场景中不建议使用数据库外键和级联，关系完整性由应用层保证。
- SQL 规则中包含避免 `select *`、避免深分页、限制大 `IN`、注意索引使用等建议。

对本项目的启发：

- 适合作为默认 Web 业务系统规则集的基础。
- 其中“禁止数据库外键”不应硬编码为全局规则，应做成可配置策略，因为不同团队对物理外键的取舍不同。

### 2.2 GitLab Database Guidelines

来源：<https://docs.gitlab.com/development/database/>

可参考内容：

- 数据库设计和开发阶段需要关注查询计划、索引、约束、迁移安全和性能。
- Migration 需要避免长时间锁表，复杂变更应拆成多个步骤。
- 大表上创建索引、添加约束和回填数据需要使用分批或并发策略。
- 数据库变更要考虑回滚、验证和线上兼容性。
- 查询开发中需要关注分页、批处理、N+1、低效查询和索引命中。

对本项目的启发：

- 非常适合补足 `sql-cli` 的数据库生命周期能力，尤其是 `migration lint`、`release precheck`、`postcheck`、`backfill plan`。
- 可以把规则分为“设计时规则”和“变更执行时规则”，避免只做静态 DDL 检查。

### 2.3 GitLab Foreign Keys and Associations

来源：<https://docs.gitlab.com/development/database/foreign_keys/>

可参考内容：

- 外键用于维护数据一致性。
- 添加外键前需要确保引用列有合适索引。
- 大表添加和验证外键应拆分步骤，避免长时间锁表。
- 外键命名、级联行为和迁移方式需要规范化。

对本项目的启发：

- 外键规则应拆成多个维度：是否允许物理外键、是否必须维护图谱中的推断关系、关联字段是否必须有索引、是否允许级联删除。
- 对没有物理外键的业务库，仍应通过 `foreign_key_inferred`、`join_observed` 等关系维护逻辑关联。

### 2.4 PostgreSQL Constraints

来源：<https://www.postgresql.org/docs/current/ddl-constraints.html>

可参考内容：

- `CHECK`、`NOT NULL`、`UNIQUE`、`PRIMARY KEY`、`FOREIGN KEY` 分别承担不同的数据完整性职责。
- 主键表示表中行的唯一标识，表通常应该有主键。
- 外键用于保证引用完整性。
- 约束是数据库自身可以执行的数据质量规则。

对本项目的启发：

- 规则层应区分“建议性规范”和“数据库可执行约束”。
- 对支持 `CHECK` 的数据库，可以把部分字段口径和数据质量规则转成实际约束。
- 对不适合物理约束的场景，也应把这些规则保存在图谱中用于 Agent 审查和生成验证 SQL。

### 2.5 OWASP SQL Injection Prevention Cheat Sheet

来源：<https://cheatsheetseries.owasp.org/cheatsheets/SQL_Injection_Prevention_Cheat_Sheet.html>

可参考内容：

- 首选参数化查询。
- 避免字符串拼接动态 SQL。
- 对动态表名、字段名、排序字段等无法参数化的部分使用 allow-list。
- 数据库账号应遵循最小权限原则。

对本项目的启发：

- `sql-cli` 在辅助 Agent 生成 SQL、修复 SQL 和迁移脚本时，应有 SQL 安全规则。
- 对动态 SQL、危险 DML、敏感字段访问和生产库操作，需要明确审查和阻断策略。

## 3. 规则设计原则

### 3.1 规则必须可配置

不同团队、数据库类型和业务场景差异很大。比如外键策略在不同公司可能完全相反：

```yaml
foreignKeyPolicy:
  physicalForeignKey: forbidden | allowed | required
  inferredRelation: required
  joinColumnIndex: required
  cascadeDelete: forbidden | allowed
```

因此内置规则应提供默认模板，但不能强制所有项目使用同一套规范。

### 3.2 规则必须有作用范围

同一条规则不一定适用于所有表。

建议支持以下 scope：

- `global`：整个数据库或工作区。
- `schema`：指定 schema。
- `domain` / `tag`：指定业务领域或标签。
- `tableType`：如 `master`、`transaction`、`detail`、`log`、`audit`、`snapshot`。
- `dbType`：如 MySQL、PostgreSQL、Oracle、ClickHouse。
- `environment`：如 dev、test、staging、prod。
- `operation`：如 design、migration、query、repair、retire。

### 3.3 规则结果应可审计

Agent 使用规则时，必须能解释：

- 命中了哪条规则。
- 违反了什么。
- 影响对象是什么。
- 规则来源是什么。
- 如何修复。
- 是否允许人工豁免。

### 3.4 规则和图谱主数据分离

建议区分：

| 对象 | 类型 | 说明 |
| --- | --- | --- |
| `RuleSet` | 主数据 | 一组规则配置，可按 alias、schema、domain 或环境启用 |
| `PolicyRule` | 主数据 | 单条规则定义 |
| `PolicyViolation` | 派生结果 | 某次检查发现的问题，可重建 |
| `RuleEvaluation` | 派生结果 | 某次规则评估报告 |
| `RuleWaiver` | 主数据或审计记录 | 人工豁免，必须有原因和过期时间 |

## 4. 建议预置规则组

### 4.1 命名规范

目标：让 Agent 设计新表、新字段、新索引时遵守项目风格。

候选规则：

- 表名必须匹配指定正则，如 `^[a-z][a-z0-9_]*$`。
- 字段名必须匹配指定正则。
- 禁止使用数据库关键字作为表名或字段名。
- 索引名使用约定前缀，如 `pk_`、`uk_`、`idx_`。
- 约束名使用约定前缀，如 `fk_`、`ck_`。
- 表名是否允许复数由规则配置决定。
- 临时表、日志表、历史表、关联表可配置不同命名后缀。

建议默认级别：

- 表名/字段名非法：`error`
- 索引命名不规范：`warning`

### 4.2 标准词表与统一命名

目标：同一个业务概念在同一个库或领域中使用统一字段名，避免 `phone`、`mobile`、`tel`、`telephone` 等多套命名并存。

这类规则和普通命名正则不同。普通命名规范检查“名字长得是否合规”，标准词表检查“业务概念是否用对了标准名字”。

候选规则：

- 手机号统一使用 `phone` 或项目配置的标准字段名，禁止混用 `tel`、`mobile`、`telephone`。
- 用户 ID 统一使用 `user_id`，禁止混用 `uid`、`member_id`，除非语义不同且已定义术语。
- 订单号统一使用 `order_no`，禁止混用 `order_code`、`order_num`。
- 金额字段按口径命名，如 `pay_amount`、`refund_amount`、`settle_amount`，禁止泛化为不明语义的 `amount`。
- 时间字段按事件命名，如 `paid_at`、`cancelled_at`、`settled_at`，禁止只有 `time`、`date` 这类弱语义命名。
- 字段别名可以进入 `TermNode.aliases`，但新设计字段必须使用 canonical name。
- 对历史字段允许标记为 legacy alias，但新增表和新增字段不再允许使用。

建议默认级别：

- 新表或新增字段使用非标准词：`error`
- 既有字段使用非标准词但已存在：`warning`
- 同一表中出现同义字段混用：`error`

示例配置：

```yaml
controlledVocabulary:
  - concept: phone_number
    displayName: 手机号
    canonicalColumn: phone
    forbiddenAliases: [tel, mobile, telephone, phone_no]
    allowedLegacyAliases: [mobile]
    appliesTo:
      tagsAny: [business, user, customer]

  - concept: order_number
    displayName: 订单号
    canonicalColumn: order_no
    forbiddenAliases: [order_code, order_num, order_number]

  - concept: user_id
    displayName: 用户ID
    canonicalColumn: user_id
    forbiddenAliases: [uid, member_id]
```

### 4.3 必备字段

目标：保证业务表具备基本生命周期和审计能力。

候选规则：

- 业务表必须有主键字段。
- 业务表必须有创建时间字段。
- 业务表必须有更新时间字段。
- 多租户系统业务表必须有 `tenant_id`。
- 软删除表必须有 `deleted`、`is_deleted` 或 `deleted_at`。
- 审计表必须有操作者、操作类型、操作时间。
- 可配置字段命名风格，如 `created_at/updated_at` 或 `gmt_create/gmt_modified`。

建议默认级别：

- 主键缺失：`error`
- 创建/更新时间缺失：`error` 或 `warning`
- 租户字段缺失：按项目配置，通常为 `error`

### 4.4 字段类型规范

目标：减少类型选择错误带来的精度、兼容和性能问题。

候选规则：

- 金额字段必须使用 `decimal` 或 `numeric`，禁止 `float` 和 `double`。
- 业务编号不应使用纯数字类型，除非有明确理由。
- 状态字段应使用统一类型，如 `tinyint`、`smallint` 或 `varchar`，由项目配置。
- 大文本字段应有明确用途，必要时拆表。
- 时间字段应使用统一类型，如 `datetime`、`timestamp` 或数据库对应类型。
- JSON 字段必须有说明，不能作为逃避建模的默认选择。
- 字段长度必须和语义匹配，如手机号、币种、国家码等。

建议默认级别：

- 金额使用浮点：`error`
- 状态字段无枚举说明：`warning`
- JSON 字段无说明：`warning`

### 4.5 主键与唯一性

目标：保证实体可唯一识别，避免重复业务数据。

候选规则：

- 每张业务表必须有主键。
- 主键字段名和类型应符合项目规范。
- 业务编号字段应有唯一索引，如 `order_no`、`user_no`、`bill_no`。
- 唯一索引字段必须和领域术语或业务约束对应。
- 不建议使用可变业务字段作为主键。

建议默认级别：

- 业务表无主键：`error`
- 业务编号无唯一索引：`warning` 或 `error`

### 4.6 索引规范

目标：让 Agent 设计查询和表结构时同时考虑性能。

候选规则：

- 外键字段或 join 字段应有索引。
- 高频查询条件字段应有索引。
- 组合索引字段顺序应匹配主要查询模式。
- 禁止重复索引和明显冗余索引。
- 大字段不应直接作为普通索引，除非数据库类型和索引类型支持。
- `ORDER BY`、分页、范围查询应检查索引支持。
- 低选择性字段是否单独建索引需要结合数据分布判断。

建议默认级别：

- join 字段无索引：`warning`
- 唯一业务键无唯一索引：`error`
- 明显重复索引：`warning`

### 4.7 约束与数据质量

目标：把可执行的数据完整性规则显式化。

候选规则：

- 必填字段应设置 `NOT NULL`。
- 唯一业务约束应使用 `UNIQUE` 或唯一索引。
- 可由数据库表达的范围规则可使用 `CHECK`。
- 状态字段必须有关联枚举或字典说明。
- 金额字段可配置非负检查。
- 逻辑删除字段必须有明确取值范围。
- 外键约束策略按项目配置启用、禁止或要求人工说明。

建议默认级别：

- 状态字段无字典：`warning`
- 明确唯一业务约束无唯一索引：`error`
- 约束策略冲突：`error`

### 4.8 关系规则

目标：让图谱能描述业务库中的真实关联，即使数据库没有物理外键。

候选规则：

- `_id`、`*_id`、`*_no` 字段应尝试映射到目标表。
- join 两端字段类型应兼容。
- 核心业务对象之间的关系必须进入图谱。
- 无物理外键时应至少维护 `foreign_key_inferred` 或 `join_observed`。
- 物理外键是否允许级联删除由策略控制。
- 同一关系的来源、置信度、验证状态必须记录。

建议默认级别：

- join 字段类型不一致：`warning` 或 `error`
- 核心关系未入图谱：`warning`

### 4.9 SQL 规范

目标：约束 Agent 生成和执行 SQL 的安全性与可维护性。

候选规则：

- 禁止或警告 `select *`。
- `UPDATE` 和 `DELETE` 必须有 `WHERE`。
- 生产环境危险 DML 必须生成 recovery SQL。
- 修复 SQL 必须配套验证 SQL。
- 动态 SQL 中表名、字段名、排序字段必须来自 allow-list。
- 禁止字符串拼接用户输入，必须参数化。
- 限制过大的 `IN` 列表。
- 深分页查询需要替代方案或明确豁免。
- `LIKE '%xxx'` 前缀通配应提示索引风险。

建议默认级别：

- 无条件 `UPDATE/DELETE`：`error`
- 动态 SQL 未 allow-list：`error`
- `select *`：`warning`

### 4.10 Migration 规范

目标：让 Agent 在改表和上线过程中考虑兼容、锁表、回滚和数据回填。

候选规则：

- migration 文件命名必须符合项目规范。
- DDL 变更应有回滚方案或明确不可回滚说明。
- 大表新增非空字段必须有默认值、分步回填或兼容方案。
- 删除字段前必须检查下游依赖和历史查询。
- 重命名字段应优先按新增、双写、回填、切换、删除的步骤执行。
- 添加索引、外键、约束时需要按数据库类型检查锁表风险。
- 回填任务必须支持分批、断点和幂等。
- 生产变更必须有 precheck 和 postcheck SQL。

建议默认级别：

- 大表危险 DDL 无方案：`error`
- 无回滚或验证说明：`warning` 或 `error`

### 4.11 安全与敏感数据

目标：让 Agent 在设计和查询中自动识别敏感字段和权限风险。

候选规则：

- 手机号、证件号、银行卡号、账号等字段必须打敏感标签。
- 敏感字段必须声明加密、脱敏或访问策略。
- 查询敏感字段默认需要脱敏或解密授权。
- 生产库账号必须遵循最小权限。
- 导出数据时必须检查敏感字段。
- 日志表不能存储明文敏感信息。

建议默认级别：

- 敏感字段未标记：`warning`
- 明文存储高敏字段：`error`
- 生产库导出敏感字段无说明：`error`

### 4.12 生命周期与下线归档

目标：覆盖表和字段从创建到废弃的完整生命周期。

候选规则：

- 字段废弃必须记录替代字段、废弃时间和删除计划。
- 表下线前必须检查查询历史、代码依赖、报表依赖和图谱关系。
- 归档表必须有归档策略、保留周期和访问方式。
- 历史表、快照表和审计表必须说明数据来源和生成周期。
- 删除数据前必须检查合规保留要求和恢复方案。

建议默认级别：

- 直接删除字段无影响分析：`error`
- 下线无依赖检查：`error`

## 5. 首批建议内置规则

第一批规则应服务最核心的 Agent 任务：找表字段、设计新表、审查 DDL、生成 SQL、修复数据。

建议优先内置：

| 规则 ID | 规则名称 | 目标 |
| --- | --- | --- |
| `required_business_columns` | 业务表必备字段 | 检查主键、创建时间、更新时间、租户字段等 |
| `naming_convention` | 命名规范 | 检查表名、字段名、索引名、约束名 |
| `controlled_vocabulary` | 标准词表与统一命名 | 同一业务概念必须使用统一字段名，如手机号统一用 `phone` |
| `money_type_decimal` | 金额字段类型 | 禁止用浮点类型存金额 |
| `business_unique_index` | 业务唯一键 | 业务编号字段必须有唯一索引 |
| `relation_column_index` | 关联字段索引 | join/关联字段必须有索引或说明 |
| `join_column_type_match` | 关联字段类型一致 | 检查 join 两端类型兼容 |
| `status_field_dictionary` | 状态字段字典 | 状态字段必须有枚举或字典说明 |
| `sensitive_column_policy` | 敏感字段策略 | 敏感字段必须标记并声明加密/脱敏规则 |
| `dangerous_dml_guard` | 危险 DML 保护 | 更新/删除必须有条件、恢复 SQL 和验证 SQL |
| `migration_safety_check` | 迁移安全检查 | 检查锁表、回滚、回填、兼容和验证方案 |

## 6. 示例规则配置草案

```yaml
ruleSets:
  - id: web_app_default
    displayName: Web 应用默认数据库规范
    dbTypes: [mysql, postgresql, oracle]
    rules:
      - id: required_business_columns
        scope: table
        appliesTo:
          tagsAny: [business, master, transaction, detail]
        severity: error
        requiredColumns:
          - name: id
          - nameAny: [created_at, gmt_create, create_time]
          - nameAny: [updated_at, gmt_modified, update_time]

      - id: money_type_decimal
        scope: column
        appliesTo:
          semanticType: money
        severity: error
        type:
          allowedFamilies: [decimal, numeric]
          forbiddenFamilies: [float, double, real]

      - id: sensitive_column_policy
        scope: column
        appliesTo:
          nameRegex: "(phone|mobile|id_card|cert_no|account|bank_card)"
        severity: warning
        requiredTags: [PII]
        requiredAttributes:
          - encryptionPolicy

      - id: dangerous_dml_guard
        scope: sql
        appliesTo:
          statementsAny: [update, delete]
        severity: error
        requireWhere: true
        requireRecoverySql: true
        requireValidationSql: true

      - id: migration_safety_check
        scope: migration
        severity: error
        requireRollbackPlan: true
        requirePrecheckSql: true
        requirePostcheckSql: true
        largeTablePolicy:
          requireBatchBackfill: true
          forbidDirectNotNullWithoutDefault: true
```

## 7. 与现有图谱模型的关系

现有图谱已经包含：

- `SchemaWorkspaceNode`
- `TableWorkspaceNode`
- `ColumnWorkspaceNode`
- `TermWorkspaceNode`
- `RelationWorkspaceEdge`
- `ChangeRecord`
- `ValidationIssueRecord`

规则层可以先作为独立模块接入，不必立即重构主图谱。

建议关系：

```text
PolicyRule 读取 GraphWorkspace
  -> 评估 Table / Column / Relation / SQL / Migration
  -> 生成 PolicyViolation / RuleEvaluation
  -> 必要时写入 ValidationIssueRecord
```

其中：

- 规则定义是主数据，需要版本、来源和审计。
- 评估结果是派生数据，可重跑。
- 严重问题可以同步生成 `ValidationIssueRecord`，用于现有 `schema validate` 和 Web UI 展示。

## 8. 后续设计问题

需要进一步确定：

- 规则文件放在 `config/schema-graphs/<alias>/policy/`，还是全局 `config/policies/` 后按 alias 引用。
- 是否支持项目级默认规则和 alias 覆盖。
- 规则表达式使用固定结构，还是引入轻量表达式语言。
- `schema validate` 是否直接执行规则，还是新增 `schema policy validate`。
- CLI、Web UI、Agent 写入是否必须统一经过规则评估。
- violation 是否允许豁免，豁免是否有过期时间。
- 规则是否进入导出快照。

## 9. 建议落地顺序

1. 定义 `RuleSet`、`PolicyRule`、`RuleEvaluation`、`PolicyViolation` 的最小模型。
2. 先实现结构型规则：命名、必备字段、字段类型、主键、唯一索引。
3. 再实现关系型规则：join 字段索引、字段类型一致、关系必须入图谱。
4. 接入 SQL 规则：危险 DML、`select *`、动态 SQL allow-list。
5. 接入 migration 规则：回滚、锁表风险、回填、precheck/postcheck。
6. 在 Web UI 中展示规则命中结果和修复建议。
7. 让 Agent 在设计新表、生成 SQL、修复数据前主动调用规则评估。
