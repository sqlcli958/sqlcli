> # ⚠️ 已归档 · 不是待办清单
>
> 控制面收敛审查（一次性）。
>
> 至少 3 处结论已被推翻：引用了**已删除**的 `QueryExecutor`；称 `DatabaseErrorMessages` 是孤儿类；
> 关系置信度 5 项缺口里有 2 项已由 `RelationValidator` 补上。
>
> **当前状态与优先级一律以 [dev-checklist-2026-08](../dev-checklist-2026-08.zh-CN.md) 为准。**
> 归档于 2026-08-25。

---

# 控制面收敛审查

> 审查日期：2026-08-20
>
> 基线：`ffff426` · main
>
> 依据：[人类控制面与 Agent 接口 P0 开发清单](ui-human-control-plane-agent-cli-mcp-p0-checklist.zh-CN.md)（2026-08-07）
>
> 审查范围：`src/main/java` · `web/src` · `docs` · `pom.xml` · `.gitignore`
> 未覆盖：`drivers/` 与 `tests/` 的运行时验证

## 1. 结论：删代码解决不了这个项目的问题

| 指标 | 数值 |
| --- | --- |
| Java 文件 | 165 |
| 主代码行数 | 22,827 |
| 零引用的类 | 1 |
| Domain 残留 | 0 |
| 前端文件 | 38 |
| 前端行数 | 4,263 |

按「找无用和重复代码」的思路扫过一遍：全仓只有一个类零引用，`database-graph-followup-plan` 里标记待删的 Domain 实现已经清干净，重复的只是 `rootCause()` 这一级的琐碎工具方法。**这个仓库的代码卫生是合格的。**

真正的债在别处，而且 P0 清单早就写明白了：**Web UI 做人类控制面，CLI 和 MCP 退化成 Agent 适配器，两边共用四个核心 Module**。代码停在这个决策之前——CLI 里塞着别名向导、驱动管理、密钥读写、图谱导入导出、规则审计，UI 只实现了图谱三栏页。

所以本次审查的产出不是一张删除清单，是一条**把人工操作从 CLI 搬进 UI** 的收敛路线。真正该删的代码，会在搬完之后自然变成死代码。

## 2. 架构差距：四个 Module 一个都还没有

P0 清单第 4 节要求 CLI、HTTP Controller 和 MCP 都只做 Adapter：解析输入、建立上下文、调用 Module、映射输出，**不能直接碰 `QueryExecutor` 和 `GraphWorkspaceStore`**。现状是两边各自直连。

```text
现状 as-is                              目标 to-be

CLI ────────┐                           CLI ────────┐
            │                                       │  SqlTaskModule
Web UI ─────┼─▶ GraphWorkspaceStore     Web UI ─────┼─▶ KnowledgeModule ─▶ 核心
            │   QueryExecutor                       │  DdlReviewModule
MCP 未实现   ┘                           MCP ────────┘  AdminModule

14 个文件直接引用 Store                   Adapter 只做输入输出映射
规则 / 校验 / revision 各写一遍            业务规则只有一份实现
```

### 2.1 唯一已经成形的 Seam（值得复用）

`SchemaActionCommand` 的 `edit` / `add-term` / `add-relation` 已经在走 `WorkspaceMutationService`，和 UI 的 `WorkspaceMutationController` 共用同一条写入路径。这就是 `KnowledgeModule` 的雏形——不用推倒重来，把 import、index、diagram、policy 也收进去即可。

```text
SchemaActionCommand.java:1061   printMutationResult(WorkspaceMutationService.MutationResult ...)
graph/ui/service/WorkspaceMutationService.java   415 行  ← 两条路径的共同入口
```

### 2.2 CLI 主类自己 new 了 12 个核心组件（阻断收敛）

只要这些字段还在，CLI 就不是 Adapter 而是第二套业务实现。UI 侧的 Controller 也各自 new 一遍同样的组件——这才是「重复代码」的真实形态，它不表现为相似的函数体，而表现为**同一批组件被两处独立编排**。

```text
SchemaActionCommand.java:41-52
  WorkspaceValidator · GraphWorkspaceMerger · WorkspaceIndexer · WorkspaceIndexStore
  WorkspaceImportService · WorkspaceDiagramGenerator · RelationValidator
  SqlRelationScanner · CandidateDdlProjector · SqlStatementAnalyzer …
```

## 3. 该删的：三项，都不大，但现在就能清

### 3.1 DatabaseErrorMessages 是规范里的孤儿（要拍板）

104 行，全仓零代码引用。但它不是单纯的死代码——`docs/datasource-integration-template.zh-CN.md:232` 把「使用 `DatabaseErrorMessages` 标准提示」写进了新数据源接入的验收清单。也就是说**规范要求用它，实现里没人用**。

二选一，不能拖着：

- 按规范把 JDBC 异常包装接进 `ConnectionManager` 和各 Strategy
- 或者删掉这个类，并同步改掉验收清单那一条

倾向前者——错误信息一致性对 Agent 消费 JSON 输出有实际价值。

### 3.2 构建产物半入库，git 状态自相矛盾（立即修）

`web/dist/index.html` 和 `vite.svg` 被 git 跟踪，同目录的 `assets/` 却是未跟踪状态——所以每次前端构建都会让 `index.html` 出现在 diff 里。根因是 `.gitignore` 写的是 `/dist/`，只匹配仓库根目录，匹配不到 `web/dist/`。

```text
.gitignore:11    /dist/                              ← 匹配不到 web/dist/
pom.xml:124      Web frontend build profile: -Pweb
pom.xml:177      <directory>…/web/dist</directory>   ← 构建时生成并打包
```

前端由 `-Pweb` profile 调 npm 构建后复制进 jar 资源，产物完全不需要入库。

### 3.3 散落的工具方法（顺手做）

`rootCause()` 三份，`blank()` / `nullSafe()` / `maskSecretRef()` 各两份。收益很小，值得做的理由只有一个：`maskSecretRef` 涉及密钥脱敏，两份实现意味着两种脱敏规则，将来改了一处漏一处就是泄露。**只合并这一个就够，其余不必动。**

```text
rootCause()      cli/AliasActionCommand · connection/ConnectionManager · connection/QueryExecutor
maskSecretRef()  cli/AliasCommand:444  ← 与另一处实现并存，脱敏规则可能分叉
```

## 4. 该收进 UI 的：CLI 只该剩四类能力加一个状态查询

P0 清单第 6 节把 Agent 契约收敛到四类：搜索图谱、执行 SQL、提交图谱候选变更、校验 DDL，外加只读的 `task status`。批准、拒绝、恢复、配置管理**只在 UI 提供**。按这个标准盘一遍现在的 CLI：

| CLI 现有 | 体量 | 去向 | 处置 |
| --- | --- | --- | --- |
| `alias add/update/remove/show` | 23.5 KB | 设置页 | 迁移后删除 |
| `AliasAddWizard` 交互向导 | 16.1 KB | 设置页表单 | 迁移后删除 |
| `secret set/delete/status` | 在 24.9 KB 内 | 设置页 · 密钥绑定 | 迁移后删除 |
| `driver list/add/update/default` | 5.7 KB | 设置页 | 迁移后删除 |
| `schema import / export` | 在 90.8 KB 内 | 知识图谱页 | UI 承接后降级 |
| `schema policy waiver/evaluation` | 在 90.8 KB 内 | 评审中心 | UI 承接后降级 |
| `schema diagram / validate` | 在 90.8 KB 内 | 知识图谱页 | UI 承接后降级 |
| `schema edit / add-term / add-relation` | — | 合并为候选入口 | 选一个 canonical |
| `schema search / design review` | — | 保留 | Agent 四类之一 |
| `<alias> SQL / test / ddl / tables` | — | 保留 | Agent 四类之一 |

> **次序很重要。** P0 清单第 1 节写了「当前 CLI 管理命令在 UI 对齐前仅维持兼容，不继续扩展；对齐后再制定移除版本」。别先删 CLI——先让 UI 能干这件事，再把 CLI 命令标记废弃，最后在一个明确版本移除。

### 4.1 顺带一提：1911 行的 SchemaActionCommand

其中 `printActionHelp`（238–511 行）是一个 273 行的 `switch`，每个 case 一段 text block 帮助文本。它现在写得很好，Agent 靠它拿准确参数。但等 CLI 收敛到四类能力后，这段有九成会随命令一起消失——**不要现在去重构它**，那是在给马上要删的东西做保养。

## 5. 业务层缺口：四个洞，比代码问题严重得多

### 5.1 写操作的事务边界不成立（数据风险）

P0 清单 5.3 规定的顺序是：同连接内重读原始数据 → **恢复 SQL 先安全落盘** → 事务内执行 → 提交后记录 → 任一步失败整体回滚。现在 `QueryExecutor` 有 WHERE 校验、主键判断和恢复 SQL 生成这些零件，但没有把它们组织成这个顺序，也没有任务状态机。

后果是存在「SQL 已执行成功但恢复文件没写成」的窗口。这不是理论风险，是 UPDATE/DELETE 工具的核心承诺。

```text
connection/QueryExecutor.java:71   readonly 检查是目前唯一的写操作闸门
connection/QueryExecutor.java:82   多语句直接拒绝（这条是对的）
```

### 5.2 Agent 可以直接改正式图谱（数据质量）

P0 清单 2 节和 6.1 都写死了：**图谱维护由 Agent 提交候选变更，人类在 UI 评审后发布，不允许直接发布正式图谱。** 现在 `schema edit` / `add-term` / `add-relation` 是直接落盘的。

这一条和上一条的性质不同——它没有数据丢失风险，但它决定了图谱三个月后是资产还是垃圾。Agent 从代码里推断出的关系是有猜测成分的，没有人类闸门就没有质量下限。

### 5.3 关系置信度闸门被默认值架空（正在累积）

`database-graph-followup-plan`（已删除，四项归宿见 dev-checklist）把「关系类型端点严格约束」排在四项优化的第一位，理由写得很直白：*先保证图谱关系不会越写越脏*。

已经做到的部分比预期多：`RelationType.ENDPOINT_RULES` 就是 allowed endpoint matrix，`RelationValidator` 校验端点类型和最低置信度，`WorkspaceValidator.validateRelations` 已接入，三条写入路径（`SchemaActionCommand:1033`、`WorkspaceMutationService:165`、`WorkspaceMutationController:242`）都在写之前拦截。

问题出在默认值上。以下这行出现了两次——`RelationWorkspaceEdge.create()` 和 `SchemaActionCommand:1030`：

```java
Math.max(0.7, rule.getMinConfidence())
```

`join_observed` 的最低置信度是 0.5、`lineage_to` 是 0.3，但不显式传 `--confidence` 时一律得到 0.7。**最低置信度这道闸门因此永远通过**，`confidence` 字段失去全部区分能力：Agent 从 Mapper.xml 猜出来的关系，和人工核对过的关系，在图谱里都是 0.7。

followup-plan 3.1 中仍未落地的具体项：

| 约束 | 现状 |
| --- | --- |
| 推断关系必须携带显式 `confidence` | 缺——被 `Math.max(0.7, …)` 兜底 |
| 推断关系必须携带 `reason` / 来源 | 缺——`RelationEvidence` 字段已定义但无人写入 |
| `verified=true` 需要最低置信度 | 缺——`--verified` 无任何门槛，可标在纯猜测的关系上 |
| 部分关系要求 `joinExpression` | 缺——字段存在，`join_observed` 也不强制 |
| `cardinality` 类型级合理性校验 | 缺——默认 `unknown`，无校验 |

它和上一条是同一个闸门的两半：**约束管机器写入的下限，评审管人类确认的上限。** 下限被默认值架空，上限还没建。

### 5.4 运行状态没有持久化（挡住三个页面）

执行历史目前是 JSONL，UI 的 `ExecutionHistory` 只能列出脱敏 SQL 和耗时。P0-2 要求本地 SQLite 存四张表：`task_run` / `task_event` / `sql_execution` / `recovery_artifact`。

没有它，概览页、评审中心、运行记录三个页面全都没有数据来源。**这是 UI 改造的真正前置依赖**，不是 App Shell。

## 6. UI 整体设计：从「图谱页加查询参数」到六页工作台

现在的 `App.tsx` 用一个三层嵌套三元表达式按 `?view=` 分派页面，跨页全部走 `window.location.assign` 整页跳转。没有 router、没有 App Shell、没有共享导航——五个页面各自画自己的返回箭头。

```text
selectedAlias
  ? view==='rules'      ? <PolicyManager/>
  : view==='executions' ? <ExecutionHistory/>
  : <AppContent/>          ← 图谱三栏
  : view==='add-alias'  ? <AliasAddPage/>
  : <AliasHome/>

6 处 window.location.assign()   刷新丢状态 · 前进后退不可用
```

P0 清单 3.2 定的六个一级页面，对照现状的实际差距：

| 页面 | 现状 | 差距 | 前置依赖 |
| --- | --- | --- | --- |
| 概览 | 无 | 全新：待审批 · 失败任务 · 可恢复执行 · 数据源健康 | P0-2 |
| SQL 工作台 | 无 | 全新：输入 · 预检 · 只读结果 · 写操作提交 | P0-1 · P0-3 |
| 知识图谱 | 三栏已完整 | 降级为页面内布局，不再承担导航 | — |
| 评审中心 | 无 | 全新：SQL 审批 · 图谱候选 · DDL 校验结果 | P0-2 · P0-3 |
| 运行记录 | ExecutionHistory 极简 | 重做：时间线 · 恢复预览 · 筛选 | P0-2 |
| 设置 | 仅新增别名 | 吸收 CLI 的 alias · driver · 密钥 · 环境策略 | AdminModule |

### 6.1 一个和 P0 清单不同的建议

> P0 清单把 App Shell 排在 P0-4，理由是「避免先做页面后补业务闭环」——这个担心是对的，但结论建议调整：**App Shell 和正式路由应该提前到最前面，先于 P0-1 做。**

理由是它**零业务依赖**：装 router、建左侧导航、把 alias 和 readonly capability 提到 Shell 上下文、把现有五个页面搬进壳层，全程不碰后端。而只要它不做，后面每一个新页面都要重新画一遍导航和返回按钮，再在合并时删掉——这是确定会发生的返工。

P0 清单真正想防的是「页面做完了但写操作还能绕过审批」。把 App Shell 和 SQL 工作台拆开就能同时满足两边：**先搭空壳，业务闭环好了再往里填页面。**

## 7. 落地顺序

| # | 事项 | 内容 | 为什么是这个位置 |
| --- | --- | --- | --- |
| 00 | 清 git 状态与孤儿类 | `.gitignore` 补 `web/dist/` 并 `git rm --cached`；`DatabaseErrorMessages` 定去留；合并 `maskSecretRef` | 半天。和后面所有步骤都不冲突，先做掉减少噪音 |
| 01 | App Shell 与正式路由 | 引入 router，落地六个路由和左侧导航，把现有五个页面搬进壳层，删掉全部 `window.location.assign`，旧 `?alias=` 链接只保留一次性跳转兼容 | 纯前端，零后端依赖。不先做，后面每页都要返工 |
| 02 | 关系端点约束进 WorkspaceValidator | allowed endpoint matrix、推断关系强制 `confidence` / `createdBy` / `reason`、`verified` 的最低置信度门槛 | 插队到前面，因为图谱每天都在被写。晚一周就多一周脏数据 |
| 03 | SqlTaskModule 与结构化结果 | 查询结果从格式化字符串改为列 / 类型 / 行 / 耗时 / 截断状态；建立单一入口，CLI 改为调用它；接入 WHERE、策略、SM4、恢复和历史 | P0-1 原样。所有后续能力的地基 |
| 04 | SQLite 运行库 | 四张表，JSONL 历史兼容读取一次后统一写入；恢复文件登记元数据与校验值 | P0-2。概览、评审、运行记录三个页面的共同数据源 |
| 05 | 审批闭环与事务边界 | 预检、自动审批条件、状态机、批准绑定 SQL hash 与规则 revision，恢复文件先落盘再执行 | P0-3。做完这一步，业务层四个洞补掉两个 |
| 06 | 往壳层里填页面 | SQL 工作台 → 评审中心 → 运行记录 → 设置页 | P0-5 到 P0-8。设置页做完，CLI 的 alias / driver / secret 命令才可以标记废弃 |
| 07 | 收敛 CLI 契约，再谈 MCP | 固定四类能力的 JSON schema、错误 code 和退出码，图谱写入改为只提交候选，补 `task status`；MCP 直接调 Module，不包 CLI 子进程 | P0-9 / P0-10。这时候删 CLI 代码才是安全的——UI 已经接住了 |
