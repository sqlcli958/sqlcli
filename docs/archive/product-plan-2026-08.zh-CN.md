> # ⚠️ 已归档 · 不是待办清单
>
> 产品规划报告（2026-08-20 快照）。
>
> 第 14 行称 SqlTaskModule 是「唯一阻塞项」——**已于 08-24 完成**，其「最需要开发六项」也已全部交付。
> 仍然有价值的是它的**实测数据与判断依据**（499 表真库实测、命令去向判定、SQLite 存储盘点）。
>
> **当前状态与优先级一律以 [dev-checklist-2026-08](../dev-checklist-2026-08.zh-CN.md) 为准。**
> 归档于 2026-08-25。

---

# 产品规划报告：功能盘点 · 缺口 · UI · MCP · 存储

> 日期：2026-08-20 ｜ 基线：main（scan-sql 移除后）
>
> 输入：全命令实测（真实别名 erp_plush_test，MySQL 8.1，499 表）、docs 全量完成度扫描、
> [团队版决策记录](../sqlcli-team-edition-product-and-architecture-decisions.zh-CN.md)、
> [P0 清单](ui-human-control-plane-agent-cli-mcp-p0-checklist.zh-CN.md)、
> [控制面收敛审查](control-plane-consolidation-review.zh-CN.md)。
>
> 配套执行清单：[dev-checklist-2026-08.zh-CN.md](../dev-checklist-2026-08.zh-CN.md)
>
> **本文是 2026-08-20 的快照，不再逐条维护。** 2.2 节「最需要开发」六项到 08-22 已完成五项
> （SQLite 运行库、policy bindings 修复、别名/驱动/密钥 UI、图谱候选变更、generation 清理），
> 只剩第 2 项 **SqlTaskModule 单一入口** 未做，且它现在是唯一在挡路的东西。
> 三节的 UI 设计也已被超越：导航从六页收敛为五页，评审中心与规则页已上线。
> **当前状态与优先级一律以 [dev-checklist](../dev-checklist-2026-08.zh-CN.md) 为准**，
> 本文只保留当时的实测数据与判断依据。

---

## 一、功能盘点与可用性

全部命令用项目 jar 实测（2026-08-20，Java 17 / corretto-17）。

### 1.1 实测结果

| 功能 | 实测 | 状态 |
| --- | --- | --- |
| `test` / `info` / `tables` / `ddl` | 连接成功，MySQL 8.1，pattern 过滤正常 | ✅ 可用 |
| `<alias> "SQL"` 查询 + 执行历史 | 正常，历史落 JSONL（脱敏） | ✅ 可用 |
| `schema stats / index status` | 499 表 / 9727 字段 / 65 关系，索引 ready | ✅ 可用 |
| `schema search` | 中文搜索命中 70 条，带评分和来源字段 | ✅ 可用 |
| `schema describe / query / path` | 正常 | ✅ 可用 |
| `schema validate` | 通过，端点约束已生效 | ✅ 可用 |
| `schema import`（增量/断点） | 34 次导入历史，job 状态机工作 | ✅ 可用 |
| `schema diagram` | 42 聚合关系 / 34 关联表 / 465 孤立表 | ✅ 可用 |
| `schema edit / add-term / add-relation` | 走 WorkspaceMutationService，校验齐全 | ✅ 可用 |
| Web UI（新壳层） | 六页路由 + 概览页，验证通过 | ✅ 可用 |
| `secret status` / `driver list` / `alias show` | 正常，secretRef 脱敏已统一 | ✅ 可用 |
| `schema policy check` | **默认报错** `ruleSets is required in …/policy/bindings.yaml` | ⚠️ Bug |
| `schema scan-sql` | Mapper.xml 100% 解析失败（DOCTYPE 被 XXE 防护拒绝） | ❌ **已删除** |
| `crypto sm4` | 别名未配 sm4Key，未实测 | ➖ 未验证 |
| Yearning 查询链路 | 无 yearning 别名，未实测 | ➖ 未验证 |

### 1.2 两个已处理的决定

**scan-sql 已删除（2026-08-20）。** 它对主目标文件类型（MyBatis Mapper）解析失败率 100%，且动态 SQL（`<if>/<foreach>/${}`）是静态解析的结构性盲区。JOIN 提取交给 Agent：`grep JOIN 预筛 → 读 mapper → add-relation --type join_observed --join "<条件>"`。Skill 已更新为该配方。

**开发期全局命令用项目 jar。** `~/.sql-cli/sql-cli.bat` 检测到 `D:\project\sql-cli\target\sql-cli.jar` 存在即优先使用；构建链路 mise 固定 corretto-17 + maven 3.8.8（`mise.toml` 已入项目根）。

### 1.3 迁移去向判定

依据团队版决策 5.1 节「CLI 收敛清单」：

| 类别 | 命令 | 去向 |
| --- | --- | --- |
| Agent 保留 | `"SQL"` / `test` / `tables` / `ddl` / `schema search·describe·query·path·stats` / `add-relation·add-term·edit`（未来合并为候选入口）/ `design review` / `migration lint` | CLI 长期保留，加固 JSON 契约 |
| 人工操作 → UI | `alias add/update/remove` + 交互向导、`driver *`、`secret set/delete`、`schema import/export`、`policy waiver/evaluation`、`index rebuild`、`diagram` | UI 承接后 CLI 标记废弃 |
| 已删除 | `scan-sql` | Agent 接管 |
| 待定 | `crypto sm4`（低频工具）、`ui`（保留为启动器） | 保持现状 |

---

## 二、docs 缺口与优先开发判断

### 2.1 完成度扫描（checkbox 统计）

| 文档 | 完成 | 判定 |
| --- | --- | --- |
| clickhouse-datasource-integration | 57/77 | 活跃，接近完成 |
| database-graph-web-ui | 116/328 | 活跃，但其三栏方案已被新壳层取代，需按新 IA 重排 |
| agent-database-lifecycle-priority | 23/208 | 活跃主线，大量未做 |
| recommended-development-checklist | 11/178 | 泛化清单，与主线重叠，**已删除（2026-08-20）** |
| ui-human-control-plane P0 | 0/59 | **当前主线**（壳层已完成其 P0-4 的大半，未勾选） |
| database-design-and-migration-review | 0/144 | design review / migration lint 实际已实现一部分，清单未维护 |
| datasource-integration-template | 0/52 | 模板类，不算债 |
| user-manual | 0/3 | 手册，需同步 scan-sql 删除等变化 |

**诊断：** 文档总 checkbox 约 1050 个，完成率约 20%。问题不是做得少，是**五份清单互相重叠、没有一个单一事实来源**。P0 清单（2026-08-07）是最新意志，其余清单里与它冲突的部分应视为已废弃。

### 2.2 最需要开发的清单（按价值/成本排序）

1. **SQLite 运行库**（P0-2）—— 概览/评审/运行记录三页的共同数据源，也是 TaskRun 的地基。不做，UI 一半页面永远是占位。
2. **结构化查询结果 + SqlTaskModule 单一入口**（P0-1）—— SQL 工作台、MCP `execute_sql`、恢复闭环全部踩在它上面。
3. **policy bindings 默认态修复** —— 新用户第一次 `policy check` 就报错，空 bindings 应视为「无规则，通过」。半天工作量。
4. **别名/驱动/密钥 UI（AdminModule 首件）** —— 唯一还必须离开 UI 才能完成的日常运维。
5. **图谱候选变更（Agent 写入走候选，人在 UI 发布）** —— 团队版知识闭环的种子，个人版先做单人评审形态。
6. **generation 清理**（见第五节，已决策只保留当前代）—— 82MB/别名的增长曲线不能带进多人共享。

不建议现在做：BM25 调优、字段级血缘（followup-plan 3.3/3.4，价值后置）、Spring Boot 服务端（团队版触发条件未满足）。

---

## 三、UI 重设计

### 3.1 已落地（2026-08-20）

- App Shell：顶部数据源上下文（图谱/索引/只读徽章）+ 左侧六页导航 + 主内容区
- 正式路由 `/workspaces/local/*`，`?alias=` 携带数据源；旧 `?view=` 链接一次性重定向
- 概览页两态：未选数据源=数据源目录；已选=规模指标 + 需要关注（索引/校验/审批占位）+ 最近执行
- 知识图谱页降级为页面内三栏；`window.location.assign` 6 → 0
- 未启用页（SQL 工作台/评审中心）保留入口并说明缺什么

### 3.2 待设计四块

**别名增删改查（设置页 · 数据源标签）**
- 列表已做（表格 + 导入/打开）；「新建」复用 AliasAddPage（已嵌入）
- 缺：编辑（复用新建表单回填）、删除（二次确认 + 关联图谱处置提示）、连接测试按钮、密钥绑定（`secret set` 的 UI 形态：输入即写 keyring/encrypted，不回显）
- 后端缺口：`PATCH/DELETE /api/aliases/{name}`、`POST /api/aliases/{name}/test`、`PUT /api/aliases/{name}/secret`

**规则增删改查（设置页 · 规则标签）**
- PolicyManager 已嵌入设置页，编辑器可用
- 缺：从 `docs/schema-policy-ruleset.example.yaml` 一键创建模板；bindings 可视化（哪些规则集绑定当前别名）；`policy check` 触发按钮 + 违规列表跳转
- 依赖 3 号修复（空 bindings 不报错）

**图谱查看与编辑（知识图谱页）**
- 查看已完备（画布/搜索/表详情/关系列表）
- 编辑缺口：表/字段描述编辑已有（TableInspector），关系新建编辑入口弱 —— 目标形态：选中两表 → 侧栏建关系（类型下拉带端点约束提示、confidence、join 表达式）；校验错误就地显示
- 术语（add-term）无任何 UI —— 新增「术语」子视图：术语列表 + 同义词 + 映射目标
- 索引重建按钮从概览下沉到本页工具条（编辑后就地重建）

**首页（概览页）迭代**
- 现版已可用；SQLite 落地后补齐：待审批数（真实数据替换占位卡）、失败任务、可恢复执行
- 增加「图谱新鲜度」：最近导入时间 vs 数据库 DDL 变更探测（低成本方案：表数量 diff）
- 数据源目录卡片补「最近使用时间」（来自 SQLite 执行历史）

---

## 四、MCP：走向共享型的路径

团队版决策 5.2/5.3 已定调，此处只做执行化拆解。

### 4.1 现在不做 MCP Server，先做「MCP-ready」

个人版 `CLI + Skill` 已是有效 Agent 形态。MCP 的真实价值在**跨 Agent 客户端 + 远程团队服务 + 细粒度授权**，这些都以团队服务存在为前提。提前做 MCP 只会包装 CLI 子进程（决策 13 节明确不做）。

但「一份图谱多人使用维护」的方向要求现在就把契约打直：

1. **稳定机器契约**（P0-9）：四类能力固定 JSON schema、错误码、退出码；stdout 纯结果、日志走 stderr —— 这层契约就是未来 MCP Tools 的参数/返回定义，做一次两处用
2. **图谱写入候选化**：`propose_graph_change` 语义先在 CLI 落地（写入进候选区，UI 评审发布），MCP 到来时直接映射
3. **revision 乐观锁贯穿**：已有，保持所有写路径必带 expectedRevision —— 这是多人并发的冲突基础

### 4.2 共享形态的演进三步

```text
第一步（个人）   图谱工作区文件 + revision 乐观锁，本机单人
第二步（小组）   schema export 快照 + import --merge 点对点交换，
                 用于验证「多人维护一份图谱」的真实需求
第三步（团队）   Team Server 持可信版本，MCP 作为 Agent 入口
                 Tools: search_schema / execute_sql(带审批状态) /
                        propose_graph_change / validate_ddl (+ task:// 资源)
```

已决策（2026-08-20）：**不用 Git 做图谱版本管理**。图谱只维护当前态，不做多版本；
变更历史由 SQLite `graph_change_log` 记录，共享需求出现时直接走 Team Server 形态。

### 4.3 MCP 启动的判据

出现以下任一情况才立项 MCP Server：export/merge 交换出现高频冲突；有第二种 Agent 客户端（非 Claude Code）要接入；写操作需要跨人审批。

---

## 五、SQLite 存储

### 5.1 现状盘点（实测 ~/.sql-cli）

| 数据 | 现存形态 | 大小 | 问题 |
| --- | --- | --- | --- |
| 执行历史 | `execution-history/*.jsonl`（base64 文件名/别名） | 16K | 无查询/分页/过滤能力，UI 只能全量读 |
| 图谱 | `config/schema-graphs/<alias>/generations/` | **82M / 单别名** | **35 个 generation 全量拷贝，零清理** |
| 索引 | `schema-graphs/<alias>/index/`（3 分片） | 含上 | 合理 |
| 导入任务 | `schema-graphs/<alias>/jobs/` | 小 | 合理 |
| 别名/驱动配置 | `config/*.yaml` | 小 | 合理，人可读可 Git |
| 密钥 | keyring / settings.yaml AES-GCM | — | 合理 |

### 5.2 进 SQLite 的（`~/.sql-cli/sqlcli.db`，原生 JDBC，无 ORM）

按团队版决策 10 节 + P0-2 数据集合：

| 表 | 内容 | 替换什么 |
| --- | --- | --- |
| `sql_execution` | 脱敏 SQL、类型、耗时、影响行数、状态、错误摘要 | JSONL（一次性兼容导入后停写） |
| `task_run` | 任务类型、actor、alias、环境、状态、SQL hash、规则 revision | 新增 |
| `task_event` | 提交/预检/审批/执行/验证/恢复追加事件 | 新增 |
| `recovery_artifact` | 恢复文件路径、校验值、关联执行、状态 | 新增（文件本体留文件系统） |
| `graph_change_log` | 图谱变更流水（操作、目标、actor、revision 前后） | 从 generation 里解放审计职责 |

### 5.3 不进 SQLite 的

- **图谱本体**：继续用文件保存当前态 —— 可 diff、可整目录迁移、export/import 即共享载体。历史职责移交 `graph_change_log`，文件层不留多版本
- 明文密码、密钥、驱动 jar、aliases/settings.yaml（人工编辑友好性优先）
- 搜索索引（自身已分片管理，且可随时 rebuild）

### 5.4 必须一起做的：generation 清理（已决策）

图谱不做多版本管理。generation 目录只是原子落盘的实现产物：

- 导入成功完成后只保留最后一代，其余全部清理（分批导入的 25 个中间 checkpoint 一并回收）
- 常规编辑提交成功后同样只保留当前代
- 导入失败/中断不清理，保住 `import resume` 断点续传
- 实测 78MB/35 代 → 预期 ~4MB/1 代

---

## 六、汇总

```text
立即（本周）    policy bindings 修复 · SQLite 运行库(sql_execution 先行)
短期（两周）    结构化结果 + SqlTaskModule · 别名/密钥 UI · generation prune
中期（一月）    图谱候选变更 + 术语/关系编辑 UI · CLI JSON 契约收敛 · 首页真实待办
远期（按触发）  export/merge 共享验证 → Team Server → MCP
```

执行粒度见 [dev-checklist-2026-08.zh-CN.md](../dev-checklist-2026-08.zh-CN.md)。
