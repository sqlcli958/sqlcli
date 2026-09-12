# Agent 数据库生命周期优先开发清单

> **状态（2026-08-22）**：执行以 [dev-checklist-2026-08](dev-checklist-2026-08.zh-CN.md) 为准，
> 本文只作为实现记录。scan-sql 相关内容已随功能删除失效。
>
> 本文里大量未勾选的方框是**已完成阶段的验收描述**（3.5 / 5.4 这类「完成定义」），
> 不是待办，别照着它排期。3.4 的导入回归矩阵已于 2026-08-24 自动化（见
> `WorkspaceImportServiceTest`），本文没有再未完成的条目。

> 生成日期：2026-07-17
>
> 来源：[Agent 数据库生命周期工作台路线图](agent-database-lifecycle-workspace-roadmap.zh-CN.md)
>
> 范围：`P0-1`、`P0-2`、`P1-1`、`P1-2`、`P1-4`

## 实施结果（2026-07-20）

| 阶段 | 状态 | 主要交付 |
| --- | --- | --- |
| P0-1 | `[x]` | 分批/范围删除判定、跨批与跨 schema FK、零 FK 清理、废弃恢复与回归测试 |
| P0-2 | `[x]` | alias 共享锁、统一 mutation、revision/ChangeRecord hash、Web 校验/索引提交、根目录最新图谱持久化、路径边界 |
| P1-1 | `[x]` | product name/version、表级有序索引、复合唯一语义、关系 evidence、模型/存储 v5 |
| P1-2 | `[x]` | 五类固定规则、不可变 evaluation/violation、精确 waiver、显式 skipped、校验查询投影、原子 policy 存储与唯一 CLI 命令组 |
| P1-4 | `[x]` | `.sql`/MyBatis 静态 SELECT JOIN 扫描、XXE 防护、diagnostic、精确 `path:line` evidence、只读 CLI |

验收结果：209 个测试分两组通过：非 Web 测试 163 个；需要本地端口的 Graph UI HTTP 测试 46 个。新增命令同时覆盖帮助路由和实际 handler 执行测试。

下方细项按已实现且有回归覆盖的内容更新；未勾选项保留为后续扩大测试矩阵或性能优化时的验收项。

## 1. 目标和边界

本清单只建设后续规则和关系抽取真正依赖的最小闭环：

```text
可信导入
-> 可信写入
-> 补齐规则所需元数据
-> 可执行、可审计、可豁免的结构规则
-> SQL / Mapper 关系候选抽取
```

本期不包含：

- Markdown、PRD、工单抽取。
- Migration lint。
- 自动诊断、修复 SQL 和验证 SQL 编排。
- 通用规则表达式语言。
- GraphRAG、向量数据库、embedding 和 rerank。
- ORM 注解、Git 历史、慢 SQL 和查询历史扫描。

## 2. 依赖顺序

```text
P0-1 导入正确性
  -> P0-2 统一写入、锁和 revision
    -> P1-1 补齐元数据
      -> P1-2 结构规则、审计和豁免闭环
      -> P1-4 SQL / Mapper 关系候选抽取
```

执行约束：

1. 前一阶段完成定义全部满足后，才能进入下一阶段。
2. `P1-2` 和 `P1-4` 可在 `P1-1` 完成后并行，但不能绕过 `P0`。
3. 同一功能只提供一个 CLI 入口，不增加快捷命令或等价语法。
4. 优先复用现有 Jackson YAML、JSqlParser、`GraphWorkspaceStore`、`WorkspaceValidator`、`RelationValidator` 和写锁实现，不新增依赖。
5. 每个非平凡修复至少增加一个能复现旧问题的回归测试。

状态说明：

| 标记 | 含义 |
| --- | --- |
| `[ ]` | 未开始 |
| `[~]` | 进行中 |
| `[x]` | 已完成并通过验收 |
| `[!]` | 阻塞后续阶段 |

## 3. P0-1：修复数据库导入正确性

### 3.1 阶段目标

保证初始化、刷新、分批和断点恢复导入不会错误废弃表/字段，不会丢失声明 FK，也能检测 FK 删除和对象恢复。

### 3.2 已确认根因

- 批次 `incoming` 只包含当前部分表，却按完整 schema 做删除检测。
- 每次 flush 后清空 `incoming`，FK 提取时无法看到跨批目标表。
- FK 清理范围从“本次成功抽取的 FK 边”反推；某表新结果为零条 FK 时无法清理旧边。
- 已标记为 `deprecated` 的表/字段重新出现后没有恢复逻辑。

### 3.3 开发任务

#### P0-1-01 先补失败用例

- [x] 增加 `batchSize=1` 的两表刷新测试，证明第一批 flush 不会把第二张表标记为 `deprecated`。
- [x] 增加跨表 FK 测试，FK 两端表位于不同批次或不同 schema。
- [x] 增加“原有 FK 全部删除”测试，刷新后旧 FK 必须消失。
- [x] 增加表重新出现测试，状态从 `deprecated` 恢复为数据库导入状态。
- [x] 增加列重新出现测试，清除 `attributes.deprecated` 和 `deprecatedAt`。
- [x] 增加失败后 resume 测试，未完整扫描时不得执行全范围删除检测。

#### P0-1-02 修复表和列废弃判定

- [ ] 批次 flush 只合并当前成功提取的对象，不执行缺失对象判定。
- [ ] 仅在完整 discover 和 table task 成功结束后执行一次删除检测。
- [ ] 删除检测使用本次实际扫描的 table ID 集合，不只使用 schema 名称。
- [ ] 指定 `--table` 时只判断该表，不影响同 schema 的其他表。
- [ ] 指定 `--schema` 时只判断该 schema，不影响其他 schema。
- [ ] 导入存在失败或未完成 task 时，不执行可能造成误删的全范围废弃判定。
- [ ] incoming 中重新出现的表恢复 `status`，重新出现的列清除废弃属性。
- [ ] 表/列的废弃与恢复均生成 `ChangeRecord`，重复刷新不得生成重复状态变更。

#### P0-1-03 修复 FK 提取和替换范围

- [x] FK task 能解析本次导入的全部端点表，不依赖当前批次 `incoming` 是否仍包含目标表。
- [ ] FK 提取使用完整端点 workspace 或等价的只读表索引，不重复加载和复制整份工作区。
- [x] 声明 FK 的替换范围按“已扫描 FK 的表集合”确定。
- [x] 已扫描表返回零条 FK 时，删除该表范围内的旧 `foreign_key_declared`。
- [x] 未扫描表的声明 FK 和所有人工关系保持不变。
- [~] 当前 JDBC 默认路径只采信 `getImportedKeys`，方向和端点稳定；仅提供 exported keys 的驱动兼容性仍待补充验证。
- [x] `supportsForeignKeys=false` 时保持现有关系，不把“不支持读取”解释成“数据库没有 FK”。

#### P0-1-04 统一导入模式

- [ ] merge、`--force-overwrite`、首次导入和刷新使用相同的删除范围语义。
- [ ] batchSize 不改变最终 workspace 结果。
- [ ] resume 后的最终结果与一次性成功导入一致。
- [ ] 导入完成后再执行 `WorkspaceValidator`，错误结果不得被标记为成功 job。

### 3.4 回归矩阵

| 场景 | batchSize | 预期 |
| --- | ---: | --- |
| 首次导入两张无 FK 表 | 1、20 | 表/列一致，无 deprecated |
| 刷新两张无变化表 | 1、20 | workspace 内容稳定 |
| 删除一张表 | 1、20 | 仅目标表 deprecated |
| 删除一列 | 1、20 | 仅目标列 deprecated |
| 表/列重新出现 | 1、20 | 清除 deprecated 状态 |
| 跨表单向 FK | 1、20 | FK 数量和端点一致 |
| 双向/多条 FK | 1、20 | 不重复、不丢失 |
| 删除某表全部 FK | 1、20 | 旧 FK 清零 |
| 指定 schema/table 刷新 | 1 | 范围外对象不变化 |
| task 失败后 resume | 1 | 不误废弃，最终结果一致 |
| 不支持 FK 的数据库 | 任意 | 保留旧关系并记录 skipped |

### 3.5 完成定义

- [x] 上述回归矩阵全部自动化并通过（`WorkspaceImportServiceTest`，2026-08-24）。
- [ ] 相同数据库快照在不同 batchSize 下生成等价的表、列和关系集合。
- [ ] 未完整扫描时不会产生删除或废弃判断。
- [ ] 非 FK 人工关系、人工描述、业务名和 tags 不受刷新影响。
- [ ] 相关文档不再把导入链路标记为阻塞。

## 4. P0-2：统一写入、revision 和锁

### 4.1 阶段目标

所有可见工作区写入经过同一套 `load -> mutate -> validate -> revision -> commit` 语义；并发写入和非法路径不能覆盖他人修改。

### 4.2 开发任务

#### P0-2-01 收敛写入入口

- [ ] 列出所有写入调用：CLI edit/term/relation/import/validate/index、Web mutation 和导入 flush。
- [ ] 把 UI 包中的通用 mutation 能力移到图谱工作区层，或让 CLI 直接复用同一服务；不得保留两套字段 patch 和关系校验逻辑。
- [ ] 所有对象写入均执行 `WorkspaceValidator`。
- [ ] 所有关系写入和全量校验均执行同一个 `RelationValidator` 端点矩阵。
- [ ] 每次业务写入生成 `ChangeRecord`，包含 actor、operation、targetId 和 reason。
- [ ] 对已有对象的修改填充 `beforeHash/afterHash`；内容未变化时不写 ChangeRecord、不增加 revision。

#### P0-2-02 统一并发语义

- [ ] 写锁覆盖完整的 load、revision 检查、修改、校验和提交过程。
- [ ] CLI、Web 和导入使用同一个 alias 级进程内锁和跨进程文件锁。
- [ ] 需要用户提交的修改必须校验 expected revision。
- [ ] 每个成功的可见提交只增加一次 revision。
- [ ] 校验失败、写入失败和锁冲突不得增加 revision。
- [ ] workspace revision 变化后，现有搜索索引必须报告 `stale`，不得继续报告 `ready`。

#### P0-2-04 路径边界

- [ ] alias、schema、table 和 term 名称进入路径前统一校验。
- [ ] 所有目标路径执行 `normalize`，并验证仍位于预期 workspace 根目录下。
- [ ] 拒绝 `..`、绝对路径、路径分隔符和会覆盖保留目录的名称。
- [ ] 符号链接不能绕过根目录限制。
- [ ] 不完整 workspace、版本错误和 IO 错误必须报错，不能当作“不存在”后重新创建。

#### P0-2-05 迁移现有入口

- [ ] CLI `schema edit` 使用统一 mutation/commit。
- [ ] CLI `schema add-term` 使用统一 mutation/commit。
- [ ] CLI `schema add-relation` 使用统一 mutation/commit。
- [ ] CLI `schema validate` 持久化 issue 时使用统一 commit。
- [ ] CLI index rebuild 更新 manifest 时使用统一 commit。
- [ ] Web 表/字段/关系修改继续通过统一 mutation/commit。
- [x] P0-1 导入批次写入通过统一锁和原子 commit。
- [x] 删除遗留的直接 `workspaceStore.save(workspace)` 业务写入；存储层测试和初始化除外。

### 4.3 必需测试

- [ ] 两个并发 writer 使用相同 revision 时只能有一个成功。
- [ ] CLI 和 Web 并发修改不会丢失更新。
- [ ] 导入与人工编辑并发时，锁冲突可解释且数据不损坏。
- [ ] 在 generation 写入不同阶段注入失败，重新加载始终得到旧版本或完整新版本。
- [ ] ChangeRecord、revision 和实际对象内容保持一致。
- [ ] `../x`、绝对路径、分隔符和符号链接逃逸全部被拒绝。
- [ ] 缺失 manifest、损坏 YAML/JSONL 和版本不匹配不会创建空 workspace 覆盖现场。

### 4.4 完成定义

- [ ] CLI、Web、导入没有平行的业务写入实现。
- [ ] 任一时刻主加载器只能看到完整 generation。
- [ ] 所有成功修改都有 revision 和 ChangeRecord，失败修改两者都不变化。
- [ ] 全量 validator 能发现非法关系端点。
- [ ] 索引 stale 状态随 revision 正确变化。
- [ ] 并发、故障注入和路径穿越测试全部通过。

## 5. P1-1：补齐规则和抽取所需元数据

### 5.1 阶段目标

只补齐 `P1-2` 和 `P1-4` 会立即使用的数据：数据库产品版本、表索引信息和关系来源证据。暂不建设完整数据库目录模型。

### 5.2 开发任务

#### P1-1-01 数据源版本

- [ ] 导入时记录 `productName` 和 `productVersion`。
- [ ] 保留 `dbType` 作为稳定匹配字段，不从展示名称反推数据库类型。
- [ ] 无法读取版本时明确为 unknown，不伪造默认版本。
- [ ] refresh 时更新系统采集字段，不覆盖人工维护字段。
- [ ] YAML/snapshot round-trip 后字段不丢失。

#### P1-1-02 最小索引模型

- [ ] 为 Table 增加最小索引结构：`name`、`unique`、有序 `columns`。
- [ ] 使用 JDBC `DatabaseMetaData.getIndexInfo` 作为默认抽取路径。
- [ ] 主键继续使用现有 `primaryKey`，不重复建第二套主键模型。
- [ ] 从表级索引派生现有 Column 的 `indexed/unique` 兼容字段。
- [ ] 支持复合索引，不能把复合唯一索引误判为每个列单独唯一。
- [ ] 刷新时数据库索引信息以 incoming 为准，删除的索引必须消失。
- [ ] 不支持索引元数据的数据库记录 capability/skip，不生成虚假空索引结论。

#### P1-1-03 关系来源证据

- [ ] 定义最小关系证据字段：`sourceType`、`sourceRef`、可选 `observedAt`。
- [x] 同一稳定 relation ID 可合并多个 evidence，不重复保存相同来源。
- [x] JDBC 声明 FK 标记来源为数据库元数据。
- [x] P1-4 生成的候选能携带 `path:line` 来源引用。
- [ ] evidence 不包含 SQL 参数值、密码、JDBC URL 或采样业务数据。

#### P1-1-04 存储和兼容边界

- [ ] 更新 model/storage version，并继续严格拒绝不匹配版本。
- [ ] structured workspace 和 snapshot round-trip 覆盖新增字段。
- [ ] merger 明确系统元数据与人工证据的覆盖规则。
- [ ] indexer 继续索引表和字段，不因新增“数据库索引元数据”创建第二套搜索索引概念。

### 5.3 数据库验证矩阵

| 数据库 | 必验内容 |
| --- | --- |
| MySQL | product version、普通索引、唯一索引、复合索引 |
| PostgreSQL | product version、普通索引、唯一索引、复合索引 |
| Oracle | product version、普通索引、唯一索引；schema 语义正确 |
| ClickHouse | product version；不套用传统唯一索引语义 |

### 5.4 完成定义

- [ ] P1-2 不需要再次连接数据库即可评估主键和索引相关事实。
- [ ] 数据库版本可用于规则集 `match`。
- [ ] 复合唯一索引不会产生错误的单列唯一结论。
- [ ] P1-4 每个有效候选都能输出可追溯来源。
- [ ] 未新增与当前目标无关的 ConstraintNode、IndexNode、CatalogNode 或 DomainNode。

## 6. P1-2：结构规则、审计和豁免闭环

### 6.1 阶段目标

提供确定性、可在 CI/Agent 中调用的规则检查入口，并持久化每次评估、违规和豁免。第一期不执行自动修复，不建设通用规则语言。

唯一命令组：

```bash
sql-cli <alias> schema policy check [--rules <ruleset.yaml>] [--json]
sql-cli <alias> schema policy evaluation list [--json]
sql-cli <alias> schema policy evaluation show <evaluation-id> [--json]
sql-cli <alias> schema policy violation list [--evaluation <evaluation-id>] [--json]
sql-cli <alias> schema policy waiver add --rule <rule-id> --target <target-id> --reason <text> --expires-at <datetime>
sql-cli <alias> schema policy waiver list [--active] [--json]
sql-cli <alias> schema policy waiver revoke <waiver-id> --reason <text>
sql-cli <alias> schema policy <command> --help
```

不得增加 `policy lint`、`policy validate` 或 `help policy` 等价入口。

### 6.2 规则文件最小模型

- [ ] 使用现有 Jackson YAML 映射，不增加 YAML 依赖。
- [ ] 文档字段仅支持：`kind`、`id`、`title`、`version`、`dbType`、`match`、`defaults`、`rules`、`sources`。
- [ ] 条目字段仅支持：`id`、`title`、`category`、`when`、`statement`、`severity`、`enforcement`、`remediation`、`tags`、`sources`。
- [ ] 必填字段缺失、重复 rule ID、未知 severity/enforcement 和非法正则必须在评估前失败。
- [ ] 禁止 YAML 任意类型实例化。
- [ ] 第一版 `when` 只支持代码中明确列出的固定条件，不解析任意表达式。

第一版允许的条件：

```text
tableTagsAny
tableNameRegex
columnNameRegex
semanticTypeAny
dbTypeAny
```

### 6.3 持久化模型

#### P1-2-01 `RuleEvaluation`

每次成功开始的 policy check 对应一个不可变评估记录。

| 字段 | 要求 |
| --- | --- |
| `id` | 全局唯一 evaluation ID |
| `sourceAlias` | 被评估 workspace alias |
| `workspaceRevision` | 本次读取的工作区 revision |
| `ruleSetId` / `ruleSetVersion` | 规则集身份 |
| `ruleSetHash` | 规范化规则内容哈希 |
| `dbType` / `productVersion` | 实际评估数据库环境 |
| `status` | `passed`、`violations`、`error`、`stale`、`skipped` |
| `startedAt` / `finishedAt` | 运行时间 |
| `actor` | `human` 或 `agent` |
| `evaluatedRules` / `violationCount` / `waivedCount` | 运行统计 |
| `errorMessage` | 仅 error 时记录，不包含敏感信息 |

- [ ] evaluation 创建后不可覆盖或删除。
- [ ] 持久化本次规范化 ruleset 快照，不能只存 hash 后依赖会变化的外部文件。
- [ ] 评估完成前 workspace revision 已变化时标记 `stale`，结果不得作为当前结论。

#### P1-2-02 `PolicyViolation`

每条规则命中生成一条属于本次 evaluation 的不可变违规记录。

| 字段 | 要求 |
| --- | --- |
| `id` | 本次违规记录唯一 ID |
| `evaluationId` | 所属 RuleEvaluation |
| `fingerprint` | `alias + ruleset + rule + target + field` 的稳定哈希 |
| `ruleId` | 命中的规则 ID |
| `targetId` / `field` | 违规对象和字段 |
| `severity` / `enforcement` | 原始规则等级和执行方式 |
| `message` / `remediation` | 原因和修复建议 |
| `status` | `open` 或 `waived` |
| `waiverId` | waived 时必须存在 |
| `observedAt` | 本次观察时间 |

- [ ] fingerprint 跨评估稳定，便于比较新增、持续和已消失违规。
- [ ] 历史 violation 不回写为 resolved；是否解决由最新成功 evaluation 中是否仍存在同 fingerprint 判断。
- [ ] waived violation 保留原 severity/enforcement，不篡改历史规则结果。

#### P1-2-03 `RuleWaiver`

第一版只支持“精确 ruleId + 精确 targetId”豁免，不支持通配符、正则或脚本条件。

| 字段 | 要求 |
| --- | --- |
| `id` | 全局唯一 waiver ID |
| `sourceAlias` | 作用 workspace |
| `ruleSetId` / `ruleId` | 作用规则 |
| `targetId` | 精确作用对象 |
| `reason` | 必填、非空 |
| `createdBy` / `createdAt` | 创建审计信息 |
| `expiresAt` | 必填，必须晚于 createdAt |
| `status` | `active` 或 `revoked` |
| `revokedBy` / `revokedAt` / `revokeReason` | revoke 时必填 |

- [ ] policy check 和 Agent 评估流程不得自动创建豁免；只有显式 `waiver add` 命令可以创建。
- [ ] 本地 CLI 没有认证身份边界，`createdBy` 目前只作为审计信息，不能伪装成真正的 RBAC。
- [ ] revoke 使用状态变更，不删除历史文件。
- [ ] 过期豁免自动失效，但不修改或删除原记录。
- [ ] 豁免只影响创建后的 evaluation，不追溯修改历史 violation。

### 6.4 存储布局和原子性

规则审计不属于图谱节点，不写入 `GraphWorkspace` 的 tables/relations/terms，也不因单纯记录 evaluation 而增加 workspace revision。

```text
config/schema-graphs/<alias>/policy/
  waivers/
    <waiver-id>.yaml
  runs/
    <evaluation-id>/
      evaluation.json
      ruleset.yaml
      violations.jsonl
```

- [ ] policy 目录复用 P0-2 的 alias 写锁和路径边界校验。
- [ ] evaluation 先写临时 run 目录，三个文件全部写完并可读取后再原子 rename。
- [ ] 写入失败不得留下可查询的半条 evaluation。
- [ ] waiver 使用单文件原子替换，创建和 revoke 均保留完整审计字段。
- [ ] evaluation/violation/waiver 查询只读取完整记录，损坏文件明确报错。
- [ ] evaluation 使用不可变 ID，waiver 使用 `updatedAt` 和 alias 写锁处理并发；两者不得复用图谱 revision。
- [ ] 单纯写入 policy audit 不得让图谱搜索索引标记 stale。
- [ ] 第一版不做自动清理和归档，达到可测量的磁盘增长问题后再增加 retention。

### 6.5 首批五条规则

#### P1-2-04 `naming_convention`

- [ ] 检查表名、字段名和索引名的配置正则。
- [ ] 数据库大小写语义由 dbType 决定，不统一强制转小写后再判断。

#### P1-2-05 `required_business_columns`

- [x] 仅对 `when` 命中的业务表或 `tableTypeAny` 指定的表类型检查必备字段。
- [x] 必备字段名和可接受别名来自规则配置，不硬编码某家公司规范。
- [x] 可选检查 `type`、`length`、`precision`、`scale`、`nullable`、`defaultValue`、`comment` 和 MySQL `onUpdate`。

#### P1-2-06 `money_type_decimal`

- [ ] 通过字段名、semanticType 或 tags 命中金额候选。
- [ ] 禁止浮点类型，允许的 decimal/numeric 类型按 dbType 配置。

#### P1-2-07 `primary_key_required`

- [ ] 对命中的业务表检查非空主键。
- [ ] ClickHouse 等不适用场景由规则 match 跳过，不在 evaluator 中散落特判。

#### P1-2-08 `sensitive_column_policy`

- [ ] 对命中的敏感字段检查 semanticType/tag 和策略声明。
- [ ] 只检查元数据声明，不读取或采样字段值。
- [ ] 与现有 SM4 配置联动只做提示，不在本阶段修改 SQL 执行路径。

### 6.6 评估、豁免和输出

- [ ] evaluator 输入为当前 `GraphWorkspace + RuleSet + active waivers`，不得隐式查询数据库。
- [ ] 先生成全部 PolicyViolation，再按精确 ruleId/targetId 匹配有效 waiver。
- [x] 当前开放的 required/blocking violation 可在 validation 查询中统一展示，但不复制为第二份持久化事实。
- [ ] evaluation、ruleset 快照和 violations 持久化成功后才能返回 check 结果。
- [ ] 文本和 JSON 输出包含：evaluationId、ruleId、targetId、severity、enforcement、status、waiverId、message、remediation。
- [ ] 输出按 ruleId、targetId、fingerprint 稳定排序，方便 CI diff。
- [ ] exit code：`0` 无未豁免的 required/blocking 违规，`1` 存在未豁免的 required/blocking 违规，`2` 参数、规则配置、评估或持久化错误。
- [ ] advisory/info 和已豁免违规不导致非零退出。

### 6.7 必需测试

- [ ] 每条规则至少一个通过和一个违规用例。
- [ ] RuleEvaluation、PolicyViolation、RuleWaiver 均完成存储 round-trip。
- [ ] 同一输入重复评估产生不同 evaluation ID、相同 violation fingerprint。
- [ ] ruleset 文件变化后 hash 和快照变化，旧 evaluation 仍可独立读取。
- [ ] 精确豁免命中、错误 target 不命中、过期不命中、revoke 后不命中。
- [ ] waiver 缺少 reason、缺少 expiresAt 或 expiresAt 非法时拒绝创建。
- [ ] policy check 不会根据违规结果隐式创建 waiver。
- [ ] waived violation 保留原始 severity/enforcement 且 exit code 不受其影响。
- [ ] evaluation 写入中断后不存在半条可查询 run。
- [ ] 评估期间 workspace revision 改变时 evaluation 标记 stale。
- [x] dbType/match 不命中时明确跳过，并记录 `status=skipped` 与 `evaluatedRules=0`。
- [ ] 复合唯一索引不会被错误解释为单列唯一。
- [ ] unknown condition 必须报错，不能静默忽略。
- [ ] malformed YAML、重复 ID 和非法正则返回 exit code 2，并持久化 error evaluation。
- [ ] JSON 输出可由 Jackson 重新解析。

### 6.8 完成定义

- [ ] 五条规则均能通过唯一 policy 命令组执行。
- [ ] 每次 check 都能查询对应 evaluation、ruleset 快照和 violations。
- [ ] waiver 可创建、查询、过期和撤销，完整保留审计历史。
- [ ] CI 可仅依赖退出码和 JSON 输出判断未豁免违规。
- [ ] policy audit 写入不改变图谱 revision，不使搜索索引失效。
- [ ] 没有引入通用表达式解释器、自动修复器或模糊豁免范围。
- [ ] 规则配置、实际命中对象、豁免依据和历史结果均可解释。

## 7. P1-4：SQL / MyBatis Mapper 关系候选抽取

### 7.1 阶段目标

从本地 `.sql` 和 MyBatis Mapper XML 中提取可解析的等值 JOIN，生成 `join_observed` 候选及来源证据。默认只输出候选，不自动写入图谱。

唯一 CLI 入口：

```bash
~~sql-cli <alias> schema scan-sql --path <file-or-directory> [--json]~~ （scan-sql 已于 2026-08-20 移除：Mapper/SQL 的 JOIN 提取交给 Agent 完成，写回走 add-relation）
~~sql-cli <alias> schema scan-sql --help~~ （scan-sql 已于 2026-08-20 移除：Mapper/SQL 的 JOIN 提取交给 Agent 完成，写回走 add-relation）
```

### 7.2 输入边界

- [ ] 支持单个 `.sql` 文件。
- [ ] 支持目录递归扫描 `.sql` 和 MyBatis `*Mapper.xml`。
- [ ] 目录遍历不跟随符号链接。
- [ ] 文件按规范化相对路径稳定排序。
- [ ] XML 使用安全解析配置，禁止外部实体和网络访问。
- [ ] 单文件解析失败记录 diagnostic，不中断其他文件扫描。
- [ ] 不执行任何扫描到的 SQL，不连接业务数据库，不采样数据。

### 7.3 SQL 抽取

- [ ] 复用现有 JSqlParser，不引入第二个 SQL parser。
- [ ] 第一版只提取可解析 SELECT 中的 `JOIN ... ON a.col = b.col`。
- [ ] 解析 schema/table alias 和带引号标识符。
- [ ] 两端必须能解析到当前 workspace 的真实 Column ID。
- [ ] 无法确定端点时输出 diagnostic，不生成猜测关系。
- [ ] 同一 ON 中多个等值条件分别生成字段级候选。
- [ ] OR、函数包裹、范围条件、子查询相关条件第一版跳过并说明原因。

### 7.4 MyBatis Mapper 抽取

- [ ] 只读取 `<select>` SQL 内容。
- [ ] `#{...}` 和 `${...}` 只作为参数占位符处理，不读取或展开运行时值。
- [ ] 能安全拼接静态 `<sql>/<include>` 时复用片段；动态分支不做执行模拟。
- [ ] `<if>`、`<choose>`、`<foreach>` 导致 SQL 结构不确定时输出 diagnostic，不生成低质量关系。
- [x] sourceRef 精确到 `relative/path.xml:line` 和 statement id，兼容单、双引号 XML 属性。

### 7.5 候选生成

- [ ] 候选类型固定为 `join_observed`。
- [ ] 候选使用现有 `GraphIds.relationId` 生成稳定 ID。
- [ ] 默认 `confidence=0.7`、`verified=false`、`createdBy=agent`。
- [ ] 同一关系在多个 SQL 中出现时合并为一条候选，并累积去重后的 evidence。
- [ ] 输出包含 relationId、from、to、joinExpression、confidence、verified、evidence 和 diagnostics。
- [ ] 候选方向按 SQL 左右端保留；不得为去重而破坏有向关系语义。
- [ ] 默认不调用 `workspaceStore.save`，不增加 revision，不生成 ChangeRecord。

### 7.6 必需测试

- [ ] `.sql` 中单 JOIN、多 JOIN和复合 ON。
- [ ] schema 限定名、表别名、反引号和双引号标识符。
- [ ] 两个文件观察到同一关系时 evidence 合并。
- [ ] 未知表、未知列和歧义短表名只产生 diagnostic。
- [ ] MyBatis 普通 select、参数占位符和静态 include。
- [ ] MyBatis 动态分支跳过且不误报。
- [ ] 恶意 XML 外部实体无法读取本地文件或访问网络。
- [ ] 破损 SQL/XML 不影响其他文件结果。
- [ ] 重复执行输出顺序和 relation ID 稳定。

### 7.7 完成定义

- [ ] 对受支持 SQL，每条候选都能解析到当前 workspace 的两个真实字段。
- [x] 每条候选至少有一个可定位到文件和行号的 evidence。
- [ ] 扫描过程无数据库写入和工作区写入副作用。
- [ ] 动态 SQL、不支持语法和解析失败均可见，不静默猜测。
- [ ] 未实现 ORM 注解、Markdown、Git 历史或自动 apply。

## 8. 总体验收

五个阶段全部完成时，应满足：

- [ ] 数据库刷新在分批、失败和恢复场景下保持表、列、FK 正确。
- [ ] 所有工作区写入具备统一校验、revision、ChangeRecord 和锁。
- [ ] 工作区包含规则需要的数据库版本、索引和关系来源信息。
- [ ] 五条结构规则可通过唯一 policy 命令组稳定执行，并持久化 evaluation、violation 和 waiver。
- [ ] 本地 SQL/MyBatis 扫描能生成可追溯、未验证、无副作用的关系候选。
- [ ] `mvn test` 全量通过。
- [ ] CLI 命令清单、README、`skills/sql-cli/SKILL.md` 和相关设计文档同步更新。

## 9. 明确延后

以下内容只有出现真实需求后再加：

- Agent 自动确认或自动写入关系。
- 多人审批、通配符豁免和跨 alias 豁免。
- 登录身份、RBAC 和不可伪造的审批人认证。
- 通用 `when` 表达式、脚本规则或插件规则。
- 自动 SQL 修复和 migration 改写。
- 动态 MyBatis SQL 路径枚举。
- 完整 ConstraintNode、IndexNode、CatalogNode 和 DomainNode。
