> # ⚠️ 已归档 · 不是待办清单
>
> P0 清单（2026-08-07）。
>
> 正文约 80 个未勾选项**与 dev-checklist 的结论相反**，绝大多数已完成或已推翻，**一条都不要照着做**。
> 仍然有效的只有它的**架构意志**：UI 是人类控制面，CLI / MCP 是 Agent 适配器。
>
> **当前状态与优先级一律以 [dev-checklist-2026-08](../dev-checklist-2026-08.zh-CN.md) 为准。**
> 归档于 2026-08-25。

---

# SQLCLI 人类控制面与 Agent 接口 P0 开发清单

> 状态：本文是**架构意志**的来源（Web UI 做人类控制面、CLI/MCP 退化为 Agent 适配器、
> 共用核心 Module），这一条仍然成立且未变。
> **进度与优先级不看这里，看 [dev-checklist-2026-08](../dev-checklist-2026-08.zh-CN.md)。**
>
> 对账（2026-08-22）：
>
> | 项 | 状态 |
> |---|---|
> | P0-1 统一核心 SQL 入口 | ❌ 未做，**当前唯一阻塞项**，P0-3/5/7 都卡在它后面 |
> | P0-2 任务与历史持久化 | ✅ `sql_execution` / `graph_change_log` 已用；`task_run` / `task_event` / `recovery_artifact` 只建表未接线 |
> | P0-3 写操作审批 | ⚠️ 被 P0-6 的阻塞式闸门替代；预检（影响行数、恢复能力）与事务边界仍缺，随 P0-1 做 |
> | P0-4 App Shell 与路由 | ✅（导航于 08-22 从六页收敛为五页，第 3 节的信息架构据此失效） |
> | P0-5 SQL 工作台 | ❌ 未开工，只差编辑器，等 P0-1 |
> | P0-6 评审中心 | ◐ 列表与裁决已上线；审批详情只有 SQL 摘要，缺预检、diff 与 DDL 校验类型 |
> | P0-7 运行记录与恢复 | ◐ 列表、筛选、恢复预览已并入工作台页；审计时间线与「恢复作为新任务」未做 |
> | P0-8 知识图谱页收敛 | ✅ |
> | P0-9 最小 Agent CLI 契约 | ✅ 主体（`JsonContractSnapshotTest` 锁定），`task status` 待 P0-1 |
> | P0-10 MCP Adapter | ➖ 按团队版决策不排期 |
> | P0-11 发布门禁 | ◐ Java/Vitest 已通；Playwright 与安全复查未做 |
>
> 第 3 节的六页信息架构已被超越——导航于 08-22 收敛为五页（工作台 / 图谱 / 规则 / 评审 / 设置）。
>
> **上表所有未完成项已于 2026-08-22 合并进
> [dev-checklist-2026-08](../dev-checklist-2026-08.zh-CN.md)，不要再从本文取待办。**
>
> 日期：2026-08-07
>
> 关联决策：[SQLCLI 团队版产品与架构决策记录](../sqlcli-team-edition-product-and-architecture-decisions.zh-CN.md)

## 1. 本轮结论

当前 UI 以“图谱三栏页”为产品主体，SQL 历史和规则通过查询参数切成独立页面。继续加入 SQL
查询、审批、恢复和任务后，导航、状态和权限都会分散。

下一阶段不新增第二套系统，调整为一个统一工作台：

```text
人类 ── Web UI ─────┐
Agent ─ CLI Adapter ├── Core Modules ── 图谱 / 数据库 / 本地运行状态
Agent ─ MCP Adapter ┘
```

- Web UI 是人类控制面，承载配置、查询、评审、审批、恢复和历史。
- CLI 与 MCP 是 Agent 适配器，只保留图谱搜索、SQL 执行、图谱候选变更和 DDL 校验。
- 三个 Adapter 不实现规则、审批、恢复和审计，只调用同一个核心 Module。
- 当前 CLI 管理命令在 UI 对齐前仅维持兼容，不继续扩展；对齐后再制定移除版本。
- MCP 不通过子进程包装 CLI，直接调用核心 Module。

## 2. 范围与默认假设

本清单先完成个人/本地部署闭环，同时保留以后接团队服务的清晰 Seam。

- `workspaceId` 第一版固定为 `local`，数据源仍使用现有 alias，不提前开发团队、成员和组织模型。
- UI 可直接执行只读 SQL；`UPDATE`、`DELETE` 必须统一进入任务与审批流程。
- 第一版只审批 `UPDATE`、`DELETE`，不扩展到 INSERT、DDL 发布和通用工单系统。
- 图谱维护由 Agent 提交候选变更，人类在 UI 评审后发布；不允许 MCP 直接发布正式图谱。
- DDL 第一版只做校验、投影、差异和规则结果，不在 UI 中直接执行 DDL。
- 继续使用 React、Vite、TanStack Query、Zustand、Sigma 和 JDK `HttpServer`。
- SQL 编辑器第一版使用普通文本域，不引入 Monaco；查询结果不做 BI、图表和保存结果集。

## 3. 新 UI 信息架构

### 3.1 App Shell

```text
┌──────────────────────────────────────────────────────────────────┐
│ Workspace / 数据源切换器       环境 · 连接状态 · 当前操作者      │
├──────────────┬───────────────────────────────────────┬───────────┤
│ 概览         │                                       │ 上下文抽屉│
│ SQL 工作台   │              当前页面                 │ 按需出现  │
│ 知识图谱     │                                       │ 不再常驻  │
│ 评审中心     │                                       │           │
│ 运行记录     │                                       │           │
│ 设置         │                                       │           │
└──────────────┴───────────────────────────────────────┴───────────┘
```

布局原则：

- 左侧导航固定，页面不再用 `?view=` 切换。
- 数据源是全局上下文，切换后所有查询、任务和图谱请求都显式携带 alias。
- 右侧抽屉只用于表详情、SQL 预检、审批详情、恢复详情等上下文，不永久占据屏幕。
- 图谱三栏布局降级为“知识图谱”页面内部布局，不再承担应用导航。
- 服务端数据只由 TanStack Query 管理；Zustand 只保留图画布选择、缩放和过滤等客户端状态。

### 3.2 一级页面

| 页面 | 主要内容 | 不包含 |
| --- | --- | --- |
| 概览 | 待审批、失败任务、可恢复执行、数据源健康、最近操作 | 图谱大画布 |
| SQL 工作台 | SQL 输入、预检、只读查询结果、写操作提交状态 | 通用数据库客户端能力 |
| 知识图谱 | 搜索、表目录、表详情、关系图、关系/术语候选维护 | 审批队列 |
| 评审中心 | SQL 人工审批、图谱候选、DDL 校验结果 | SQL 执行历史 |
| 运行记录 | TaskRun、SQL 历史、恢复、验证结果 | 配置编辑 |
| 设置 | 数据源、驱动、密钥绑定、规则和环境策略 | 团队成员管理 |

### 3.3 路由

```text
/workspaces/local/overview
/workspaces/local/sql
/workspaces/local/knowledge
/workspaces/local/reviews
/workspaces/local/operations
/workspaces/local/settings
```

实现时使用一套正式路由，不长期保留 `?view=` 与新路由两套状态。现有 `?alias=` 链接只做一次跳转兼容。

## 4. 核心 Module 与 Seam

不按每个页面创建一个 Service。P0 只需要以下四个有足够 Depth 的 Module：

| Module | 对外 Interface | 隐藏的 Implementation |
| --- | --- | --- |
| `SqlTaskModule` | 预检、查询、提交写任务、审批后执行、恢复、任务查询 | SQL 解析、策略、连接、事务、恢复文件、审计和状态机 |
| `KnowledgeModule` | 搜索图谱、读取对象、提交/评审候选变更 | 工作区文件、索引、revision、关系矩阵和校验 |
| `DdlReviewModule` | 校验 DDL，返回投影差异、违规和诊断 | 方言解析、候选投影、规则评估和影响分析 |
| `AdminModule` | 数据源、驱动、密钥绑定和环境策略管理 | YAML、Keychain、加密配置和连接测试 |

CLI、HTTP Controller 和 MCP 是 Adapter：只解析输入、建立 actor/context、调用 Module、映射输出。
不能直接调用 `QueryExecutor`、`GraphWorkspaceStore` 或恢复文件实现。

P0 不为未来团队存储预建一组 Repository 接口。先把业务入口收敛进 Module；第二种存储真正出现时再提取
Storage Seam。

## 5. SQL、审批与恢复闭环

### 5.1 统一状态流

```text
提交 SQL
   │
   ▼
解析与预检 ── 不可执行 ──► BLOCKED
   │
   ├─ 只读 SQL ─────────► EXECUTING ─► SUCCEEDED / FAILED
   │
   └─ UPDATE / DELETE
          │
          ├─ 满足自动规则 ─► EXECUTING
          └─ 需要人工 ─────► PENDING_APPROVAL
                                  │
                                  ├─ REJECTED
                                  └─ APPROVED ─► 重新预检 ─► EXECUTING
                                                        │
                                                        └─ SUCCEEDED
                                                           + RECOVERY_AVAILABLE
```

批准只能批准固定的 `sqlHash + dataSource + environment + policyRevision`。SQL 或上下文被修改后必须生成
新任务，不能复用旧批准。

### 5.2 自动审批最低条件

`UPDATE`、`DELETE` 只有同时满足以下条件才可自动执行：

- 单条 SQL，语法可完整解析，包含 `WHERE`。
- 数据源允许写入，数据库策略支持该语句。
- 目标表存在可靠主键，能够生成恢复 SQL。
- 预检影响行数不超过环境阈值。
- 所有阻断级规则通过。
- 当前环境策略允许该 SQL 类型自动审批。
- 执行前再次校验 SQL hash、策略 revision 和影响范围。

安全默认值：生产环境全部人工审批；非生产 `UPDATE` 可按规则自动审批；`DELETE` 默认人工审批。
规则允许显式放宽，但不能通过 UI 临时绕过 `BLOCKED`。

### 5.3 执行事务边界

写任务的正确顺序必须是：

1. 在同一连接中重新读取原始数据并确认影响范围。
2. 生成恢复 SQL，并先安全持久化恢复文件。
3. 在事务中执行原 SQL。
4. 提交成功后记录执行、恢复元数据和任务事件。
5. 任一步失败则回滚，任务标记失败，不留下“已成功”历史。

当前 `QueryExecutor` 已有 WHERE、主键和恢复 SQL 基础能力，但尚未形成上述任务状态和完整事务边界，
应复用后下沉到 `SqlTaskModule`，不能再复制一份 Web 执行器。

### 5.4 本地运行状态

个人模式使用 `~/.sql-cli/sqlcli.db` 保存可查询的运行状态，原生 JDBC 即可，不引入 ORM。

最低数据集合：

- `task_run`：任务类型、actor、alias、环境、当前状态、SQL hash、规则 revision、时间。
- `task_event`：提交、预检、审批、执行、验证、恢复等追加事件。
- `sql_execution`：脱敏 SQL、类型、耗时、影响行数、结果状态和错误摘要。
- `recovery_artifact`：恢复文件路径、校验值、关联执行、创建时间和恢复状态。

原始 SQL 只在待执行任务的受限字段中保存，终态后按保留策略清理；历史列表继续只保存脱敏 SQL。
恢复文件包含原始数据，文件权限必须限制为当前用户，API 默认只返回元数据，不直接返回文件内容。

## 6. Agent 的最小 CLI / MCP 契约

### 6.1 只保留四类能力

| 能力 | CLI 目标入口 | MCP Tool | 结果 |
| --- | --- | --- | --- |
| 搜索图谱 | 复用 `schema search ... --json` | `search_graph` | 对象、关键字段、邻接关系、revision |
| 执行 SQL | 复用 `<alias> "SQL" -f json` | `execute_sql` | `executed`、`approval_required` 或 `blocked`，含 taskId |
| 维护图谱 | 一个候选变更入口 | `propose_graph_change` | candidateId、校验结果、revision |
| 校验 DDL | 复用 `schema design review ... --json` | `validate_ddl` | 投影 diff、violations、diagnostics |

图谱候选变更 CLI 的最终命令名在实现前从现有 `edit`、`add-term`、`add-relation` 中选定一个 canonical
入口并完成迁移；不得新增等价入口后长期并存。

审批可能异步完成，因此只补一个只读状态入口：CLI 使用 `task status <taskId> --json`，MCP 使用
`task://{taskId}` Resource。它们不提供批准、拒绝或执行写操作的能力。

### 6.2 统一机器契约

CLI 与 MCP 共享同一结果 DTO，至少包含：

```json
{
  "ok": true,
  "status": "executed",
  "code": "OK",
  "data": {},
  "taskId": "optional",
  "revision": "optional"
}
```

- stdout 只输出 JSON，日志写 stderr。
- 错误使用稳定 code，不依赖解析自然语言 message。
- Agent 路径不要求交互输入。
- `execute_sql` 自行判断只读、自动审批、人工审批或阻断，不额外提供 MCP 批准工具。
- Agent 通过 taskId 查询状态；批准、拒绝和恢复操作只在 UI 提供。

## 7. P0 开发清单（按实施顺序）

以下全部属于最高优先级，但必须按顺序交付，避免先做页面后补业务闭环。

### P0-1：统一核心 SQL 结果与任务入口

- [ ] 将查询结果从格式化字符串改为结构化结果：列、类型、行、耗时、截断状态。
- [ ] 建立 `SqlTaskModule` 单一入口，CLI 先改为调用该入口。
- [ ] 设置查询最大行数、执行超时和单语句限制；默认不把完整结果写入历史。
- [ ] 将现有 WHERE、数据库策略、SM4、恢复和历史能力接入该入口。
- [ ] 为读取、阻断、自动执行、待审批四个分支各保留一个最小集成测试。

验收：同一条 SQL 从 CLI 与核心 Module 获得相同状态和结构化结果；不存在绕开 Module 的新执行路径。

### P0-2：任务、历史与恢复持久化

- [ ] 创建本地 SQLite 运行库和四个最低数据集合，不迁移图谱、别名和密钥。
- [ ] 将现有执行历史 JSONL 迁移或兼容读取一次，之后统一写入 SQLite。
- [ ] 为恢复文件登记元数据、校验值、状态和关联 taskId。
- [ ] 明确原始 SQL、脱敏 SQL、错误信息和恢复文件的保留/权限规则。
- [ ] 提供按 alias、状态、类型、时间分页查询的核心接口。

验收：进程重启后仍能查询任务、审批、执行历史和恢复状态；历史接口不泄露 SQL 字面值或恢复数据。

### P0-3：UPDATE / DELETE 自动审批与人工审批

- [ ] 实现预检：SQL 类型、WHERE、目标表、主键、预计影响行数、规则结果和恢复能力。
- [ ] 实现安全默认的环境策略和自动审批条件。
- [ ] 实现 `PENDING_APPROVAL`、批准、拒绝、过期和阻断状态转换。
- [ ] 批准绑定 SQL hash、alias、环境和规则 revision；执行前强制重新预检。
- [ ] 将原始数据读取、恢复文件持久化和写 SQL 放入明确事务边界。
- [ ] 测试审批后 SQL 被修改、影响行数变化、恢复文件保存失败和数据库执行失败。

验收：CLI/MCP 发起的危险写操作不能绕过 UI 人工审批；任何失败都不会产生错误的成功状态或不可恢复提交。

### P0-4：App Shell 与正式路由

- [x] 建立统一头部、左侧导航、主内容区和按需上下文抽屉。（2026-08-20 完成）
- [x] 落地六个一级路由及 404/无数据源状态。（2026-08-20 完成）
- [x] 将 alias、环境、session capability 作为 App Shell 上下文统一提供。（2026-08-20 完成）
- [x] 把现有首页、图谱、规则和执行历史迁入新壳层。（2026-08-20 完成）
- [x] 删除组件中的 `window.location.assign` 和 `?view=` 分支；旧链接只保留跳转兼容。（2026-08-20 完成）
- [ ] 所有写控件统一消费 readonly/capability，禁用时显示原因。

验收：浏览器前进/后退、刷新和直接打开任一路由都能恢复相同页面与数据源；只读 session 看不到可误触的写入口。

### P0-5：SQL 工作台与数据查询

- [ ] 提供 SQL 文本输入、执行/停止、历史引用和清空。
- [ ] 只读 SQL 直接显示结构化表格、耗时、行数和截断提示。
- [ ] 限制最大结果数，支持服务端取消/超时；第一版只提供 CSV 导出。
- [ ] `UPDATE`、`DELETE` 不显示“直接执行”，改为预检摘要和提交任务。
- [ ] 展示规则命中、预计影响行数、审批方式和 taskId，并可跳转评审/运行记录。
- [ ] SQL 与结果显示处理长文本、NULL、二进制、错误和窄屏，保留基本可访问性。

验收：用户可在 UI 完成 SELECT 查询；危险写操作只能进入 P0-3 的统一流程，页面无第二套执行逻辑。

### P0-6：评审中心

- [ ] 默认显示“我的待处理”，支持 SQL 审批、图谱候选和 DDL 校验三个类型筛选。
- [ ] SQL 审批详情显示原 SQL、脱敏摘要、预检、规则、影响范围、恢复能力和提交者。
- [ ] 批准/拒绝必须二次确认并填写最短必要理由；不能在审批页直接修改 SQL。
- [ ] 图谱候选显示 diff、证据、校验结果和 revision 冲突。
- [ ] DDL 校验显示投影前后 diff、violations 和 diagnostics，不提供执行 DDL 按钮。

验收：人类不使用 CLI 即可完成所有待审批/待评审操作，结果立即反映到任务或图谱 revision。

### P0-7：运行记录、恢复与验证

- [ ] 统一列表展示 TaskRun 状态、SQL 类型、actor、alias、耗时、影响行数和时间。
- [ ] 详情以时间线展示提交、预检、审批、执行、恢复文件和验证事件。
- [ ] 恢复操作先预览恢复 SQL 和适用条件，再二次确认执行。
- [ ] 恢复本身创建新 taskId，执行后关联原任务并记录结果。
- [ ] 支持按状态、数据源、SQL 类型和时间筛选；不加载或保存结果集。

验收：用户能从一次写操作追溯到提交者、批准者、规则版本、执行结果和恢复结果；恢复动作可审计。

### P0-8：知识图谱页面收敛

- [ ] 将现有 SchemaExplorer、SearchBar、GraphCanvas、TableInspector 和 RelationEditor 迁入知识页。
- [ ] 默认以搜索和表目录为入口，图谱作为切换视图，避免大图成为所有操作的首页。
- [ ] 表详情和关系编辑使用统一上下文抽屉。
- [ ] Agent 变更进入候选列表；人类发布后统一更新 revision 和索引状态。
- [ ] 所有 UI、CLI 和 MCP 图谱写入共用 `KnowledgeModule` 的关系矩阵、校验和 revision 检查。

验收：现有图谱查询/编辑能力不回退；任一 Adapter 写入后，其他 Adapter 读取到相同 revision 和结果。

### P0-9：最小 Agent CLI 契约

- [ ] 为四类能力固定 canonical 命令、JSON schema、错误 code 和退出码。
- [ ] SQL 写操作接入任务/审批，不再由 Agent 路径直接调用旧执行器。
- [ ] 图谱维护只提交候选，不从 Agent 路径直接发布。
- [ ] 提供只读 `task status`，供 Agent 获取审批和执行终态。
- [ ] 补充无交互、stdout/stderr 分离和 JSON 快照测试。
- [ ] 更新内置 help、README 和 Agent Skill，删除重复语法建议。

验收：Agent 只依赖四类能力即可完成搜索、查询/提交写任务、维护候选图谱和校验 DDL。

### P0-10：MCP Adapter

- [ ] 在核心 Module 稳定后实现四个 MCP Tool，不复制 CLI 参数解析和输出拼接。
- [ ] 提供只读 `task://{taskId}` Resource，不增加第五个状态 Tool。
- [ ] 每次调用建立 actor、workspace、alias、环境和权限上下文。
- [ ] 返回与 CLI 相同的业务 status、code、taskId 和 revision。
- [ ] 工具描述明确副作用；`execute_sql` 标明可能创建审批任务。
- [ ] 增加 CLI 与 MCP 对同一 fixture 的契约一致性测试。

验收：四个 Tool 可完成既定 Agent 场景；MCP 中不存在批准、直接发布图谱或绕过策略的隐藏入口。

### P0-11：发布门禁

- [ ] Java 单元/集成测试、Web Vitest 和关键 Playwright 流程全部通过。
- [ ] 覆盖 SELECT、自动审批 UPDATE、人工审批 DELETE、拒绝、阻断、恢复和图谱候选发布 E2E。
- [ ] 检查 SQL/错误/日志脱敏、恢复文件权限、session token、Origin 和 readonly capability。
- [ ] 更新产品决策、用户手册和迁移说明，明确旧 UI 链接及 CLI 命令变化。

验收：上述 E2E 在干净环境可重复通过，且没有一条写 SQL 能绕过同一任务与审计链路。

## 8. 暂不进入 P0

- 团队、成员、RBAC、SSO 和通知系统。
- Spring Boot Team Server、远程 MCP 和多租户存储。
- DDL 在线执行与审批、INSERT/MERGE 审批。
- BI 图表、保存查询结果、SQL 收藏夹和多人共享查询。
- 实时协同编辑、评论系统和通用工单流程。
- Monaco、可视化 SQL Builder、AI 聊天窗口。

这些能力只有在 P0 闭环被真实使用并暴露明确需求后再进入下一优先级。
