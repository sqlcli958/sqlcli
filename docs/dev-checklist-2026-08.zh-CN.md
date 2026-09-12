# 开发清单（2026-08-24 重排）

> **2026-08-27 起新待办记到 [2026-09 清单](dev-checklist-2026-09.zh-CN.md)**，本份保留为
> 8 月的交付记录与未结项出处，仍从这里取 8 月遗留的待办。
>
> **这是唯一的执行清单。** 历史清单里没做完的条目已经合并进来，那几份文档
> 从此只作为实现记录和设计依据，不再从里面取待办：
>
> | 来源 | 合并了什么 | 那边还剩什么 |
> |---|---|---|
> | [P0 清单](archive/ui-human-control-plane-agent-cli-mcp-p0-checklist.zh-CN.md) | P0-1 / P0-3 / P0-5 / P0-6 / P0-7 / P0-9 / P0-11 的未完成项 | 架构意志（UI 是人类控制面、CLI/MCP 是 Agent 适配器）仍然有效 |
> | [元数据模型设计](database-graph-metadata-model-design.zh-CN.md) | 21.3 缺口 + 24 章后续清单 | 模型定义本身，仍是权威 |
> | [graphrag 笔记](graphrag-metadata-retrieval-notes.zh-CN.md) | 检索增强、权重、评估集 | 阅读摘要 |
> | [ClickHouse 清单](clickhouse-datasource-integration-checklist.zh-CN.md) | 整体作为一条并行轨道引用 | 13 条细项，按其自身清单推进 |
> | [模块功能设计](archive/module-feature-design-2026-08.zh-CN.md) | SQL 工作台 W1~W6、审批详情 A2 | 功能边界定义 |
> | [生命周期优先清单](agent-database-lifecycle-priority-development-checklist.zh-CN.md) | 导入回归矩阵未自动化 | 其余未勾项是已完成阶段的验收描述，不是待办 |
>
> [datasource-integration-template](datasource-integration-template.zh-CN.md) 里的未勾项是
> **模板**，接新数据库时整份复制去用，不是本清单的待办。

## 优先级怎么定的（2026-08-24 晚更新）

**三条大线已于 2026-08-24 通过三个并行 Opus agent + 合并全部落地**
（SQL 工作台 / Agent 查询正确率 / 审批闭环），370 例后端 + 20 例前端测试全绿。
大块功能没有了，剩下的全是收尾与打磨，按性价比排：

1. **P1 = 三线交付时明确留下的缺口**，都小而清楚：
   UI 搜索结果没跟上 CLI 新字段（B 线点名）、`task status` CLI 命令（数据全就绪，
   纯查询）、工作台三个推迟项（执行中止 / 列头业务含义 / 表名补全）。
2. **P2 = 图谱与 UI 收尾、工程化与契约、ClickHouse 并行轨道**——名单未变。
3. **P3 = 按触发**：共享验证、字段血缘、检索权重与评估集，都不排期。

**2026-08-26 追加三节**（本轮内涵侧审计 + 外延侧方向 + 用户提出的可视化）：

- **P1 · 内涵侧模型补强**：六条，排在 BI 语义层之前——只因为其中第 1 条
  （复合外键被拆成 N 条独立边）不修，metric 的 `joinPath` 接到的就是残缺 join 条件。
  六条里有三条是**静默错误**（不报错、有结果、数是错的），这是它们优先于打磨项的理由。
- **下一阶段 · 外延侧：数据剖析**：新线，未排期。它把「自动发现关系」这件事
  从「等查询流量」解耦成「扫数据本身」。
- **下一阶段 · 可视化**：血缘图（A1 按需追溯 → A2 全库）与数据质量图表
  （B1 图谱完整性 → B2 剖析质量 → B3 业务数据图表）。

**2026-08-26 夜间执行结果**：上面三节里的绝大部分已经落地，见「✅ 已交付 · 2026-08-26 夜间并行批」。
P1 六条已全部完成（含第 4 条函数依赖/权威来源）。剩下的开放项是外延侧第 2/3 层、
可视化 A2/B2/B3，以及那一节里新记的四条发现。

**同日计划变更：整节删除「明确不做（本周期）」。** 那份清单是前期阶段的收敛动作，
用来挡住摊子铺太开；现在产品要往挖掘与分析走，它不再适用。判据（服务于
「让 Agent 生成更正确的 SQL」或「让人更快确认 Agent 的产出」）**保留，但只用来排先后，
不再用来否决**。原清单里被顺带解除的条目见下方各节，未重新立项的按不排期处理。

---

# ✅ 已完成 · SqlTaskModule 与结构化结果（2026-08-24）

> 详细设计：[sql-task-module-design](sql-task-module-design.zh-CN.md)（§15 实现落地记录、
> §16 AppContext 取消理由）。
> **不做兼容层**——产品还没上线，`QueryExecutor.java` 直接删除，没有过渡期、没有旧签名保留。
> 6 步提交计划一次性做完（原计划分批落地，实际一趟改完更省——中间态两条路径并存
> 反而更难验证，"字节不变"这个验收标准换成了"343 个测试全绿 + 真库 smoke test"）。
>
> **前置项 `AppContext` 已取消**（查证后，见设计文档 §16）：占比最大的 `AliasResolver`
> 必须保持每次新建（缓存它等于重新引入「改完别名连不上库」那个已修的 bug）；
> `SecretResolver` 无状态；唯一有构造成本的 `GraphWorkspaceStore` 本来就是每进程一次。
> 而它原定的收益「提取 Module 时少改一遍依赖获取」已经由构造函数注入兑现了。

- [x] 查询结果结构化：`SqlTaskResult` 携带列（名 + JDBC 类型）/ 行（`List<List<Object>>`，
      按位置存）/ 耗时 / **截断标志**。**顺手修了一个暗 bug**：旧渲染器按列名存进
      `Map<String,Object>`，`SELECT a.id, b.id` 两个同名列会互相覆盖丢一列
- [x] `SqlTaskModule` 单一入口：五个 Stage（Guard/Precheck/Approval/Cipher/Dialect）
      + 两个 Backend（Jdbc/Yearning）+ 审计。CLI 与 `SqlRerunController` 都改调它
- [x] **Yearning 路由**：`YearningBackend` 走 `YearningQueryExecutor.query()`（原
      `executeToString` 拆出结构化版本），写语句在 `GuardStage` 就地拒绝
- [x] 接入现有 WHERE 校验、数据库策略、SM4（`CipherStage` 前后对称改写/解密）、
      恢复 SQL、`sql_execution` 落库
- [x] 写操作事务边界：`JdbcBackend.executeWithRecovery` 显式 `setAutoCommit(false)`，
      同连接重读 → 恢复文件先落盘 → 执行 → `commit()`；任一步失败 `rollback()`
      并在 `finally` 里复原 autoCommit
- [x] `task_run` / `task_event` 接线（`RunStateStore.createTaskRun/recordTaskEvent/finishTaskRun`，
      批次 2 建表后一直没人写）；`recovery_artifact` 沿用已有的 `recordRecoveryArtifact`
- [x] 查询上限与超时配置化（详见下方「查询上限」，已完成）
- [x] **`SqlRerunController` 收编进 Module**：删掉自建连接 + 自读 ResultSet 的私有实现，
      只留只读白名单业务规则；自动获得 SM4 解密和执行历史留痕（`source=ui_rerun`，
      `rerun_of` 关联原记录）——**真库验证**：重放一条历史 SELECT，`sql_execution`
      表新增一行 `source='ui_rerun', rerun_of=<原id>`，中文字段值解密正确
- [x] `SqlTaskModuleTest` 9 个用例：无 WHERE 拒绝（不回显敏感值）、多语句拒绝、
      readonly 拒绝（连接未建立）、无主键拒绝（未读未写）、恢复文件落盘失败即不执行写
      （事务已回滚、autoCommit 已复原）、Yearning 写拒绝（后端零调用）、
      dry-run 只出预检不执行、affected-rows 可靠性判断
- [x] **落地后复查修掉的两处架构走样**（2026-08-24）：
      ① `SqlBackend.supportsWrites()` 两个后端都实现了却**全项目无人调用**，
      `GuardStage` 里是硬编码的 `isYearning(config)`——等于"加一个只读后端要改 GuardStage"，
      正好是设计说不该发生的事。改成后端在进 Stage 循环前选好放进 context，
      Guard 问 `ctx.backend.supportsWrites()`；`backendFor` 成为全项目唯一知道
      "哪个别名走哪个后端"的地方。②「Yearning 写拒绝」这条设计点名要的分支一个测试都没有，补上
- [x] `SqlRerunController` 从每请求 `new` 改为 router 字段建一次复用——它内部持有整个
      `SqlTaskModule`（5 Stage + 2 Backend），全部无状态。
      （查过 `PooledDataSourceManager.DATA_SOURCES` 是 `static`，所以之前**不是**连接池泄漏，
      只是白白分配对象）
- [x] **审计新增 `rejected` 状态**：readonly / 多语句 / 无 WHERE / 审批被拒都会落一行
      `sql_execution(status='rejected')`——以前这些拒绝完全无痕，异常直接抛出去，
      真库验证：`SELECT 1; SELECT 2` 之后 `sql_execution` 出现一行 `status='rejected'`
- [x] 顺带实现了 `PrecheckStage` + `dryRun`（design doc §4.2/§4.6，原计划里没排进 P0 但
      写法上是同一批代码的自然产物）：UPDATE/DELETE 命中审批开关或 dry-run 时才碰数据库，
      借连接查完立即还，不带着连接进审批阻塞——避免 `MAX_WAITERS=8` 个等待者把连接池占满

验收：同一 SQL 从 CLI 与 UI 重放得到一致的结构化结果（真库验证过）；JDBC 与 Yearning
都不存在绕过 Module 的执行路径（`QueryExecutor.java` 已删除，全仓唯一入口）。

设计与实现有出入的三处，已回填进设计文档 §15「实现落地记录」：`SqlTaskContext` 直接持有可变的
`resultColumns/resultRows` 字段而不是只用 Builder（`CipherStage.after` 需要读出上一步写的
行原地改一遍，只进不出的 Builder 做不到）；类型检测提到 `SqlTaskModule.execute()` 里做一次
（审计要在进 Stage 循环前就知道是不是写操作）；`--max-rows`/退出码等 CLI 契约按「不必兼容」
做了简化，不是妥协出来的。

## 查询上限（随 P0 一起完成）

- [x] `Statement.setMaxRows`，不改写 SQL——避免方言分裂（LIMIT / FETCH FIRST / ROWNUM）
      和已有 LIMIT/UNION 语句被改坏
- [x] 取 n+1 行判断截断，真库验证：`--max-rows 3` 对着有更多行的表查，返回
      `rowCount:3, truncated:true`，stderr 打印「结果已截断至 3 行，--max-rows 0 可解除限制」
- [x] `-f json` 加 `truncated` 字段（`JsonContractSnapshotTest` 只覆盖 schema/policy 相关
      命令的契约，不涉及 SQL 查询输出，这里不存在快照要更新——真库验证见上面的截断记录）
- [x] 逃生口 `--max-rows N`，`0` 表示不限
- [x] **`setMaxRows` 只设在读 Statement 上**：`JdbcBackend.execute()` 读写各建各的
      `Statement`，写路径那个永远不设 maxRows——恢复读取不可能被截断，这不是「小心」出来的，
      是结构上不存在这个坑
- [x] 配置：复用已存在但从未被消费过的 `DatabaseConfig.defaultQueryLimit`（默认 100，
      按别名 `params` 覆盖）+ 新增 `queryTimeoutSeconds`（默认 30）；请求覆盖 > 别名配置 >
      内置默认值（`EffectiveLimits.resolve`）。`defaultQueryLimit` 是读代码时发现的既有死字段——
      有完整的读写链路却没人真正用它做过任何事，这次顺手接上而不是另开一个新字段
- [x] `setQueryTimeout` 与 maxRows 同一处配置一起做
- [x] `SqlRerunController` 硬编码的 `MAX_ROWS=500` 已删除，走同一份 `EffectiveLimits`
- [x] Yearning：`YearningBackend` 拿到全部结果后本地截断（服务端可能已经先截过一次，
      文档已注明我们的 `truncated` 只反映本地上限，感知不到服务端那一层）

---

# ✅ 已完成 · SQL 工作台（2026-08-24，三线并行开发之 A 线）

> 详见 [模块功能设计](archive/module-feature-design-2026-08.zh-CN.md#二sql-工作台)。
> 后端 `POST /api/workbench/execute`（新建 `WorkbenchExecuteController`，无只读白名单，
> 永远 200 + 结构化 JSON——REJECTED 是要展示的业务结果不是 HTTP 错误）；
> `SqlTaskModule` 提升为 `GraphUiApiRouter` 共享字段，注入重放与工作台两个控制器。

- [x] W1 编辑执行：`SqlEditor.tsx` 纯 textarea，Ctrl/Cmd+Enter，空语句/执行中禁用；
      插在「图谱规模」之前，**无图谱的空态分支同样渲染**（跑 SQL 只需要连接）
- [x] W2 读写分流：`workbenchSql.ts` 按首关键字判断写语句（跳过前导注释，字符串里的
      UPDATE 不算），只管按钮文案与流程；写语句先发 `dryRun` 拿预检
      （目标表/预估影响行数/可恢复/主键/skippedReason）→ 确认面板 → 「确认执行」才真跑；
      别名开审批开关时按钮变「提交审批」，提交后停在「等待审批中」并带评审页链接，
      前端不设超时中断
- [x] W3 结果区：复用 `ResultTable`（响应设计成 `SqlExecuteResultDto` 超集，无适配层）；
      「导出 CSV」（Blob + BOM）做成 ResultTable 可选参数；截断提示升级为表格上方
      黄色横栏——改在共享组件里，历史重放同时受益
- [x] W4（部分）：表详情「在工作台查询」（sessionStorage 传 `SELECT * ... LIMIT 100`）
- [x] W5 历史回填：执行记录 rawSql 可用的行加「填入编辑器」，填入后 scrollIntoView + focus
- [x] W6 安全约束全部由 `SqlTaskModule` 提供，新端点测试验证了「没有只读白名单、
      readonly/无 WHERE/多语句照样被模块拒绝」
- [x] 测试：`WorkbenchExecuteControllerTest` 6 例（不连库、临时 RunStateStore）+
      前端 vitest 9 例（写判断/CSV 转义/两步提交流程/REJECTED 展示）
- [x] 文档：手册 §16.4 补「SQL 编辑器」整节，§16.6 补入口一行
- [x] 不做（按约定）：多标签页、结果图表、SQL 格式化、执行计划可视化

**推迟项（移入下方 P2）**：执行中止（需要服务端 `Statement.cancel()`，设计文档 v2 留缝——
前端单方面 abort 只会丢结果、语句照跑，审批挂起时更有害，假取消按钮比没有更糟）；
结果列头业务含义与值域（裸列名对不上带别名/跨表同名的图谱列，要先定映射规则）；
表名补全（要自绘候选浮层，独立一块工作量）。

---

# ✅ 主体完成 · Agent 查询正确率（2026-08-24，三线并行开发之 B 线）

> 五项全部落地，纯后端/CLI。核心是新增共享打分器 `SearchMatching`
> （CJK bigram 切词 + 多词条跨字段覆盖率加权），实时与索引两个引擎统一走它。

- [x] search 结果补信息：comment / businessName / semanticType 两个引擎都带；
      CLI 文本输出加 `Business/Semantic/Comment` 行，JSON 自动带出，
      `JsonContractSnapshotTest` 已同步（有意的契约演进，旧字段一个没删）
- [x] 中文多词检索：查询按 CJK bigram 切词（买家手机 → 买家/家手/手机），
      多词条跨字段取最佳命中按覆盖率加权；单词查询仍走整串匹配无回归。
      纯 Java 零依赖。`WorkspaceSearchMatchingTest` 9 例钉住切词/跨字段命中/
      无关词零命中/覆盖率排序
- [x] 搜索解释：两个引擎统一输出 matchedField + matchedText
      （前端契约 matchField 键保留，不破坏 UI）
- [x] 表行数进 CLI：describe 恒定打印 Rows（空值提示采集方式）；
      `--refresh-row-count` 经 ConnectionManager 跑 COUNT(*) 后以 system 身份写回
      rowEstimate（只改字段不动 status，不产生候选）。COUNT 与标识符白名单抽成
      `TableRowCounter`，UI 的 handleRowCount 改为共用（原地重复代码已删）
- [x] 候选在 CLI 侧可见：describe 候选关系加 `[候选]` 前缀；search **默认包含**候选
      并带 candidate 标记（既定决策：默认包含 + 明确标注）；stats 增加
      candidateRelations / candidateTerms 计数。配套测试 `SchemaCandidateAndRowCountTest`
- [x] 文档：SKILL.md 与手册 §13 的「不按状态过滤」备注改为新行为，
      补 --refresh-row-count 与新字段说明

**仍待办（本段剩余）**：

- [x] **表/字段权重**：热度、领域重要性、人工 boost（graphrag 笔记第 5 点）——按约定单独立项
- [x] **检索评估集**：找表/找字段命中率度量（graphrag 笔记第 6 点）——按约定单独立项
- [x] **Web UI 侧的 search 跟上了**（B 线发现并留下的缺口，已补齐）：
      `WorkspaceQueryController` 的 `/api/search` 返回体加了 comment/businessName/semanticType/candidate，
      前端 `SearchBar.tsx` 同步渲染业务名、语义类型标签和候选标记——CLI 和 UI 的搜索体验对齐
- [x] UI 的 `/api/tables/row-count` 只报数不写回 rowEstimate（历史行为如此）；
      CLI 已持久化，UI 是否也写回是一个行为变更决策，顺手做之前先拍板——拍板结果：写回，
      `GraphUiApiRouter.persistRowEstimate` 以 `GraphActor.system` 身份查完顺手写回
      （记账动作，不走审批），新增 `RowCountPersistsRowEstimateTest` 用真实 sqlite 文件验证
      **2026-08-27 补充**：功能已落地（`GraphUiApiRouter.persistRowEstimate`，system 身份、不走审批）。端到端测试 `RowCountPersistsRowEstimateTest` **暂标 `@Disabled`**——它要真建 sqlite 连接，而 `DriverResolver` 在测试环境拿不到驱动配置（挂 `sqlcli.configRoot` 写最小 settings.yaml 无效，说明驱动解析读的不是它）。补一个驱动配置夹具即可解封，是测试基建的洞，不是功能的洞。

---

# ✅ 主体完成 · 审批与运行记录闭环（2026-08-24，三线并行开发之 C 线）

> 核心问题是 approval_request 和 task_run 没有关联——预检数据落在 task_event 里
> 但从审批记录找不到它。解法：审批表挂 task_run_id。

- [x] SQLite 迁移 v3→v4：approval_request 新增 task_run_id 列；新库 DDL 直接带出，
      老库走 `ADDED_COLUMNS` + 幂等 ALTER（合并时又加固为容忍并发重复加列）；
      配套「v3 老库升 v4 不丢行」测试
- [x] `ApprovalGate` / `createApproval` 加 taskRunId 重载（旧签名委托），
      `ApprovalStage` 传 `ctx.taskRunId`，图谱审批传 null
- [x] 补 `executed` 事件：`SqlTaskModule` 在 backend 成功返回后追加，payload 带
      affectedRows / recoveryPath / truncated——时间线有了「执行完成」这一格
- [x] `RunStateStore` 新增 findTaskRun / listTaskEvents + TaskRunRow / TaskEventRow
- [x] `GET /api/approvals/{id}` 详情：审批行 + 关联 taskRun + 事件数组
      （precheck/executed 的 payload 解析成 JSON 对象内联，不是字符串套字符串）；
      未知 id 404
- [x] 评审页：类型筛选（全部/查询/更新/图谱，前端过滤）；「详情」懒加载展开——
      预检块（目标表/预估影响行数/可恢复状态点/主键/skippedReason，无预检时明说
      「未采集预检」）+ 中文审计时间线（提交/校验/预检/审批放行/改写/方言处理/执行完成）
- [x] 测试：迁移 + 新读方法 + 模块两条审计断言 + `ApprovalControllerTest` 3 例 +
      前端 `ReviewsPage.test.tsx` 5 例

**仍待办（本段剩余）**：

- [x] 回滚的实际执行：恢复文件是**多语句**，与 SqlTaskModule 单语句约束冲突，
      需要单独设计（生成新的待审批操作、批量执行边界）——现状保持「只看不执行」
- [x] 图谱候选在评审页展示 diff、证据、校验结果（候选处理仍在图谱页表详情，搬过来是独立 UI 批次）
- [ ] DDL 校验类型接入评审页——执行链里还没有这类审批，无数据可展示，等有再接
- [ ] 「命中规则」展示——policy 尚未进执行链，先决定接不接
- [ ] 备注：类型筛选按现有 kind（query/update/graph）做，不是清单原来写的
      「SQL 写操作/图谱候选/DDL 校验」三分法——后两类没有对应的 approval kind，
      按现有枚举筛才对得上数据

---

# ✅ 已交付 · 2026-08-26 夜间并行批（10 个 agent，Sonnet 开发 / Opus 调度）

> **验证结果：`mvn test` 95 个测试类 / 612 个用例，0 失败 0 错误 2 跳过；
> 前端 `npm run lint` 0 错误、`vitest` 14 文件 56 用例全绿、`tsc -b` 干净。**
>
> 落地范围：P1 内涵侧六条全部、BI 语义层 metric 定义层 + SQL 骨架 + CLI、
> 外延侧单列剖析（引擎 + 写回候选）、可视化 A1 字段级血缘图 + B1 图谱完整性看板，
> 以及一次跨 17 个文件 58 处调用点的 `ignored` 语义收口。

## 交付内容与位置

| 条目 | 落在哪 |
|---|---|
| 测试不再污染真实运行库 | `pom.xml` surefire `systemPropertyVariables` 加 `sqlcli.home=${project.build.directory}/test-sqlcli-home` |
| 复合外键分组 | `WorkspaceMetadataExtractor`（读 `FK_NAME`/`KEY_SEQ`，**按 FK_NAME 分组**）、`GraphWorkspaceMerger`、`RelationWorkspaceEdge` 的 `fkGroup`/`fkKeySeq` attribute |
| optionality | 边的 `fromOptional`/`toOptional`/`optionalitySource` attribute；`WorkspacePathFinder.PathSegment` 带出；`schema path` 文本输出打印 INNER/LEFT 判断 |
| CLI 语义写入 | `schema edit --business-name` / `--semantic-type` |
| 驳回改 `ignored` | `WorkspaceMutationService.rejectRelation` + `/unignore` 路由 + 静态 `isIgnored(...)`；前端关系列表第三组「已忽略」 |
| metric 定义层 | `MetricRecord` + `GraphWorkspace.metrics` + `edges/metrics.jsonl` + 检索 + 校验 |
| metric → SQL | 新包 `com.sqlcli.metric`（`MetricSqlExpander` / `GrainSqlDialect`）；CLI `add-metric` / `metrics` / `metric` / `expand-metric` |
| 单列剖析 | 新包 `com.sqlcli.profile`（`TableProfiler` / `ProfileOptions` / `ProfileGraphWriter`） |
| 血缘图 | `GET /api/lineage`；`TableInspector` 自绘 SVG，无图表库 |
| 完整性看板 | `GET /api/workspace/completeness`；工作台健康视图内 |

## 本轮挖出的、之前没记录过的问题

**1. `ColumnWorkspaceNode` 是唯一不继承 `BaseGraphObject` 的模型类 —— 字段级没有评审状态机。**

```
TableWorkspaceNode / TermWorkspaceNode / MetricRecord / LineageRecord / RelationWorkspaceEdge  → 都继承
ColumnWorkspaceNode                                                                            → 不继承
```

后果：字段没有 `status`，所以 `businessName` / `semanticType` / `valueHints`
**没有候选→评审→发布这套闸门，Agent 写入直接生效**。

这条与整个产品的一个核心主张冲突。`GraphStatus.forActor()` 把「机器写的默认不生效」
做成 enum 默认值而不是流程纪律，这个设计对关系/术语/血缘/metric 成立，**对字段不成立**。
而本批刚把 `--business-name` / `--semantic-type` 给了 CLI，也就是给了 Agent，
所以现在 Agent 写的字段语义是不经评审直接落盘的。

剖析写回因此只能把产出塞进 `column.attributes` 的 `profileEnumValueCandidates` /
`profileSemanticTypeWarning`——~~**这是绕过，不是解法**~~（2026-08-27 复查：这个判断错了，
见下面的决定）。

> **这两个 attribute 已于 2026-09-03 随 W1 删除**（[09 清单](dev-checklist-2026-09.zh-CN.md)）：
> 值域这条路被 `schema value-domain` 的三源交叉取代，标注质量告警归 `schema eval`。
> 下面这条决定本身仍然成立，只是它讨论的那份代码不在了。

- [x] **已决定（2026-08-27）：不建候选态，闸门是已有的 `verified` 位。**
      理由：关系/术语/血缘的 `candidate` 表达的是「这个对象存不存在」，二值，有天然的
      「写进去了但还不算数」态；而 `businessName` / `semanticType` / `valueHints` 是标量值，
      「不生效的值」这回事不存在——要做到候选，就得为每个语义字段再存一份 pending 值，
      模型翻倍去换一个没人要求的能力。
      
      **复查改了原来的问题描述**：现状不是「没闸门」，是闸门的两个零件都在、一个被篡改、
      一个没人读——`ColumnWorkspaceNode` 早就有 `verified` / `confidence`（正是
      `BaseGraphObject` 里对这件事唯一有用的两个），而 `SchemaActionCommand:1446`
      无条件 `setVerified(true); setConfidence(1.0)`，也就是 **Agent 给自己盖章**，
      同时全仓没有一处读 `column.verified`。落地三件事：
      - Agent 写入不自封已确认：CLI `schema edit`（actor=agent）写完把 verified/confidence
        清成 null。值仍然立刻可读（闸门不是拦住写入），只是标着未确认；
        **人先前确认过、Agent 又改了值的，确认一并作废**——留着等于用旧值的确认给新值背书
      - 人在 UI 里改过即确认：`applyColumnPatch`/`applyTablePatch`（actor 恒为 human）
        写完置 verified=true。不另做「确认」按钮——人看过、改过、保存了，比再点一次更能说明问题
      - 闸门必须有人读：`schema describe` 给未确认的语义标 `[待确认]`，
        `/api/workspace/completeness` 新增 `backlog.unverifiedColumnSemantics` 进工作台看板。
        判据只有一份（`ColumnWorkspaceNode.hasUnconfirmedSemantics()`），两处消费方不会漂
      
      **剖析的 attribute 不是绕过**：人写的值域带含义（`0=待付款`），剖析只测得出值本身，
      直接覆盖等于用不知道含义的版本抹掉已有知识。这个理由独立成立，即使字段有了候选态
      也不该改成直写。原文那句「这是绕过，不是解法」判断错了，已在
      `ProfileGraphWriter` 类头改正。
      
      测试：`ColumnSemanticsGateTest` 四例（agent 不自封 / human 改过即确认 /
      agent 改值作废旧确认 / 只有结构事实不算待确认）+ 完整性看板两条断言

**2. `WorkspaceMutationService.hashWorkspace()` 漏加新集合 = 静默不落盘（本批已修，但机制仍是雷）。**

`commitPrepared` 用前后哈希相等判断「什么都没改」而跳过 `store.save()`。
metric 集合加进 `GraphWorkspace` 时没同步加进这个哈希，于是 `schema add-metric`
**退出码 0、提示成功，而 list 数出来是 0**。已补 `state.put("metrics", ...)` 并在
javadoc 里写了警示。

- [x] **已改**（2026-08-26，随 `f7db1bf` 落地）：`hashWorkspace` 改成反射枚举
      `GraphWorkspace` 的声明字段，排除集钉死为 `changes` / `manifest` 并附查证依据，
      「加了新集合忘了登记」在结构上不可能发生。覆盖测试
      `WorkspaceMutationServiceHashCoverageTest`

**3. 两套平行的关系遍历实现。**

`WorkspacePathFinder`（两表最短路径，带反向边与可选性）和
`SchemaActionCommand.expandRelations()`（单表限深多跳，`schema query --depth N` 用，带 `--json`）
是两份独立实现。本批的 `ignored` 过滤**两处都得单独改一遍**——
「改一处漏一处」正是这类 bug 反复发生的根源。

- [x] **已合并**（2026-08-26）：新增 `graph/workspace/RelationAdjacency`，统一做
      ignored 过滤 + 按表分桶。`WorkspacePathFinder` 在其上临时构造反向边（行为不变，
      仍是查询时现造不落盘）；`SchemaActionCommand.expandRelations` 改成在其上做限深
      BFS。迁移前补了 `SchemaQueryDepthTest`（depth 边界、ignored 过滤、复合外键
      joinExpression、以及一个预先存在的怪癖——同一条边在两端都被弹出时会重复两次，
      这个怪癖原样保留，没有顺手修）。`mvn test` 跑了 `WorkspacePathFinderTest` /
      `SchemaPathTextOutputTest` / `SchemaQueryDepthTest` / `JsonContractSnapshotTest`
      等关系相关用例，全绿，无行为变化。

**4. `WorkspaceMetadataExtractor` 的 optionality 只能推 from 端。**

`toOptional`（父表是否必然有子行）单列 `nullable` 推不出来，当前恒为 `true` 保守处理。
实测空值率能给 from 端更准的值（剖析写回已接），**但 to 端仍然没有数据来源**——
需要跨表统计，属外延侧第 3 层。

---

# ✅ 已交付 · 2026-08-26 注释收权与技能重写

> 承接上一节的夜间并行批。这一节记两件事：**`comment` 字段收权**（模型变更）
> 和**技能从单文件重写成分层结构**，以及为技能建立的一套可重复评测基建。
>
> 前三组已随 `fa8f313 产品新增功能` 提交；第四组（评测后的三条优化）**尚未提交**。

## 一、`comment` 收权：数据库注释只由导入维护

**决策**：数据库注释错了，正确修法是**去库里改再重新同步**，不把改注释的权力交给 Agent 或 UI。

改之前 `comment` 是「一个字段两个来源」，靠 `commentSource` 区分 `db` / `user`，
而 `user` 是个**静默的单向闸门**——Agent 写过一次业务描述，那一列的库注释就再也导不进来，
DBA 后来在库里补的注释也进不来。

| 字段 | 定位 | 谁写 | 重导入时 |
|---|---|---|---|
| `comment` | 数据库注释镜像 | **只有导入** | incoming 永远覆盖，无分支判断 |
| `businessName` | 业务名——短标签 / 检索键，会进查询结果列头 | 人 / Agent | existing wins |
| `description` | 业务描述——口径、坑、来源，一段话 | 人 / Agent | existing wins |

- `commentSource` **整个删除**（实测真实图谱 10645 处全是 `"db"`，零条 `user`，无需回填；
  仓库既有惯例也是「不做兼容层，产品还没上线」）
- Web UI 表/字段 patch 白名单里 `comment` **已移除**，写它会被 400 拒绝
- 顺带修正一个长期错位：UI 上标着「业务描述」的输入框存的其实是 `businessName`，
  而 `businessName` 会被塞进查询结果列头——**有人在那框里写一段话，表格就被挤变形**。
  标签改回「业务名」（单行），新增多行「业务描述」绑 `description`
- `GraphViewService` 画布节点 hover 改成「我们写的 > 库里写的」：有 `description` 用它，没有才退回 `comment`
- 四份文档里的 `commentSource` 同步（含权威的元数据模型设计文档）

## 二、技能重写：单文件 → 分层

`~/.claude/skills/sql-cli/` 与仓库 `skills/sql-cli/` 保持一致，`build-dist.sh` 用 `cp -R skills` 自动带上 `references/`。

```
SKILL.md            82 行   路由 + 立刻会踩的坑（JVM 提示 / 单语句 / 审批阻塞）
references/
  query.md          查数据、path 的完整 ON、上下文预算、回滚保证
  graph-write.md    四字段分工、值域、置信度、候选 vs 直写、少而准
  code-scan.md      读代码提取 JOIN / 语义 / 血缘
  execution-mine.md 从 recentSql 回溯
  metric.md         指标定义与展开
  selfcheck.md      12 步工具链自检
  troubleshooting.md 审批后台流、SM4、0 行、写入失败
```

**结构性改变：技能不再复制 CLI 语法，只讲判断。** 各 action 的 `--help` 现在已经把
「为什么这么设计」写进去了，技能里明写「语法以 `--help` 为准，冲突时相信 `--help`」。
这是结构上减少腐烂，而不是承诺记得同步——上一次脱节就是因为技能在复制一份会变的东西。

补回的脱节内容：metric 四个命令、`--business-name` / `--semantic-type` / `--redundant-of`、
`schema path` 的 INNER/LEFT 输出与完整 ON、`[已忽略]` 状态、以及**血缘**——
旧技能里 `lineage` 只出现在「已删除的关系类型」名单里，从没告诉过 Agent `add-lineage` 存在，
这很可能就是真实图谱 `lineage.jsonl` 长期 0 行的原因。

## 三、技能评测基建（可重复，下一轮直接复用）

在 `D:/project/sql-cli-skill-workspace/` 下：

- **隔离环境模板**：H2 文件库（6 表 / 31 字段 / 5 关系 / 真实中文库注释 / 复合外键 /
  5 条预置执行历史），配置、图谱、运行库全部独立。`mkenv.sh <目标>` 一条命令铺一份副本——
  嵌入式 H2 不能并发共享，副本还顺带保证用例之间不互相污染
- **6 个用例**（`evals/evals.json`）：Mapper 提取关系与血缘 / 实体类补字段语义 /
  GMV 指标 / 查数据全流程 / 执行记录回溯 / 拒绝改库注释
- **`grade.py`**：36 条断言机械核验，读 `commands.txt` 和图谱最终状态，不靠主观判断
- **iteration-1 结果：35/36 = 97%**，平均 63k tokens / 402 秒

评测顺带在端到端链路上验证了夜间那批开发的核心：**复合外键分组成立**——
`schema path` 输出的两条边都携带 `ON A AND B` 的完整条件。

## 四、评测后的三条优化（**未提交**）

**1. `--help` 与技能对齐**（改的是 `--help`，不是技能）

评测中两个 agent 对同一条规则行为相反：一个把正确业务含义写进 `--description` 并标注冲突，
另一个引用 `--help` 的「不要在这里改一份跟库不一致的文本」什么都没做。
复查后判断是 `--help` 那句过于绝对——**收权管的是 `comment` 字段，
而把业务含义写进 `description` 是那个字段的正当用途**。两边现在说同一件事，
并把「真正不该做的」收窄成一条：只写 description 却不告诉用户库注释有问题。

**2. `code-scan.md` 补血缘前置条件**

`add-lineage --target` 的目标列必须已是图谱节点，视图**要先 `schema import` 进来**，
否则报 `Column not found`。并明确：视图在库里不存在时**不要为了写血缘去建视图**——
那是改数据库，超出补图谱的范围。

**3. `GrainSqlDialect` 失败提前且可行动**

保留 fail-loud 不猜语法（猜错的截断函数会生成能跑但分桶错的 SQL，不报警、有结果、数是错的）。
改的是：报错信息说明「指标定义本身没问题，去掉 `--grain` 可以正常展开」；
新增 `supportsDialect()`，`add-metric` 在**定义时**就提醒这个库展不开 grains——
定义的人才是能决定怎么办的人。

---

# ✅ 已交付 · 2026-08-26 SQLite 数据源接入

> 按 [datasource-integration-template](datasource-integration-template.zh-CN.md) 整份走完。
> 三个特殊点决定了大部分设计，其余照模板抄。

## 一、连接是「选一个文件」，不是网络端点

URL 形如 `jdbc:sqlite:<文件绝对路径>`，**没有 host / port / username / secretRef**。
模板里「`buildJdbcUrl()` 支持 host/port/database」这条对 sqlite 不适用，已在类 javadoc 里写明偏离理由。

**别名的自然写法是 `database: D:/data/shop.db`**，人不会手写 `jdbc:sqlite:` 前缀。
这暴露出一个**早于 sqlite 的洞**：`AliasResolver.parseConfig` 从来不读 yaml 的 `database` 键，
而 `AliasConfigValidator` 早就允许 mysql/clickhouse 只给 `database`、oracle 只给 `serviceName/sid`——
两边一直矛盾，「结构化配置不写 url」这条路从来没通过，只是 sqlite 让它变成必现。

已补读 `database`，并把「url 缺失」的硬报错改成**只对 sqlite** 放行结构化构建。

> **收窄过一次，值得记下来。** 最初写成「url 为空时一律让策略从结构化配置构建」，
> 结果 yaml 里把 `url` 误拼成 `jdbcUrl` 时，clickhouse 会拼出
> `jdbc:clickhouse://localhost:8123/...` 而不报错——**一个拼写错误变成悄悄连本机**。
> 既有测试 `rejectsNonCanonicalJdbcUrlFieldName` 逮住了它。
> 其余库类型要走这条路，得先把 host/port/serviceName/sid 都读进来并逐个验证，单独立项。

## 二、没有 schema 概念

实测 sqlite-jdbc 3.46：`getSchemas()` / `getCatalogs()` 返回空，`getTables()` 的
`TABLE_CAT` / `TABLE_SCHEM` 恒为 null，`REMARKS` 恒为 null，`sqlite_*` 被驱动自动归为
SYSTEM TABLE（标准类型过滤白拿）。

**这正是之前拿 `GenericDatabaseStrategy` 兜底跑 `schema import` 只导到 0 张表的原因**——
导入按 schema 枚举就落空了。

建模：固定 schema 名 **`main`**（sqlite 自己的语义，`PRAGMA database_list` 可证），
没有另写 provider，而是给通用 `WorkspaceMetadataExtractor` 加了「识别 sqlite」和
「`discoverSchemas()` 返回 `List.of("main")`」两个分支。其余（列、主键、复合外键分组）原样可用。

## 三、没有注释

SQLite 根本没有 `COMMENT` 语法，表/字段注释一律为空。**这是正常情况不是缺陷**，明确跳过不报错。
业务含义靠 `--business-name` / `--description` 补。

## 文件不存在的闸门（最容易踩的坑）

`sqlite-jdbc` 对不存在的文件**不报错，而是静默新建一个空库**——路径拼错的表现是
「连接成功、一张表都没有」，极难排查。

闸门的位置踩过一次坑：`DatabaseConfig.buildBaseJdbcUrl()` 只在 `jdbcUrl` 为空时才调
`strategy.buildJdbcUrl()`，而检查写在那里会**永不执行**。已挪到 `applyConnectionProperties()`——
`test` 和查询两条路径都必经。`params.createIfMissing: "true"` 显式开启新建，默认关。

## 「选文件」的 UI 与向导

浏览器的 `<input type="file">` **拿不到真实路径**（故意隐藏，只给文件名），所以做的是
**服务端目录浏览**：`GET /api/fs/list` 只列目录项、永不读文件内容、文件只返回
`.db`/`.sqlite`/`.sqlite3`、无权限目录跳过而不是 500。

**安全闸门**：只有 UI 绑在回环地址时才允许浏览，否则 403。判据取的是**实际绑定的 socket 地址**
（`boundAddress.getAddress().isLoopbackAddress()`）而不是 Origin 归一化后的字符串——
因为 `0.0.0.0` 绝不能算回环，而 Origin 那套会把它归一成 localhost。

前端：路径输入框保留（粘贴往往比点选快），旁边加「浏览…」展开内联面板。
**没有抽通用弹层**——只有一个调用方，也不需要遮罩/焦点陷阱/ESC，抽 Dialog 等于为不存在的问题付费。

CLI 向导选 sqlite 后只问文件路径，不问驱动和账号密码；路径不存在时循环重问，不落坏配置。

## 验收结果

后端 `mvn test` **672 用例 0 失败**；前端 `tsc -b` 0 错误、lint 0 error、vitest 63 用例全过。

端到端（隔离的临时 sqlite 文件，未碰真实别名配置）：

| 场景 | 结果 |
|---|---|
| 别名只写 `database` 不写 url | 加载成功 |
| `test` / `tables` / 查询 | 全部正常 |
| `schema import --from-db` | **2/2 张表**（此前是 0 张） |
| 复合外键 | 两条边都带完整 `ON A AND B`；sqlite 的 `FK_NAME` 返回**空字符串**，正好走 KEY_SEQ 兜底分支 |
| 文件不存在 | 明确报错 + 绝对路径 + 出路提示，且**确认没有建出空库** |

## 顺带

- 驱动无需下载（`org.sqlite.JDBC` 已在 fat jar 里，运行库就在用），`jars: []`
- `GrainSqlDialect` 加了 sqlite 分支（`date()` 的 modifier + `strftime` 手算周/季度偏移）
- 技能 `references/troubleshooting.md` 加了「连上了但一张表都没有」一节

---

# 下一步 · 2026-08-26 之后（按可动手程度排）

> 这一节是**规划**，不是已交付。上面几节记的是做完的事。

## A. 立刻能做，且有明确判据

### A1. 隔离 `config/`，堵住最后一类「真实文件被误写」

**风险是真的**：`SettingsConfig` 用 `Paths.get("config/settings.yaml")` **相对当前目录**解析，
`AliasConfigStore` 写回同一路径。任何以仓库根为 CWD 的写操作都会打到**真实别名配置**上，
里面有生产库地址和 secretRef。

今晚已经修过同一类问题的一半（surefire 全局设 `sqlcli.home`，把运行库指向 build 目录），
但那只隔离了运行库，**没隔离 `config/`**。

**已完成**（2026-08-26，随 `f7db1bf` 落地，当时漏了勾）：

- [x] `SettingsConfig.CONFIG_ROOT_PROPERTY`（`sqlcli.configRoot`，与 `sqlcli.home` 同风格），
      surefire 的 `systemPropertyVariables` 指向 `${project.build.directory}/test-sqlcli-config`。
      `AliasConfigStore` / `KeyringSecretStore` 共用同一个 `SettingsConfig.configRoot()`
- [x] 守卫测试 `SettingsConfigTest.resolvedPathsStayOutsideRepoConfigDir`：断言 settings /
      aliases / schema-graphs 三条解析路径都不在仓库 `config/` 下，**并正向断言覆盖开关
      确实被设过**——否则 CWD 恰好不在仓库根时它会假绿
- [x] `schemaGraphPath` 同样暴露，已补 `resolveSchemaGraphPath()`（`getSchemaGraphPath()`
      保持返回配置里的裸字符串供回写用）。顺带修掉 `DEFAULT_ALIASES_PATH` 会拼成
      `config/config/aliases.yaml` 的潜伏 bug

> **过程教训（记在这里免得重犯）**：本轮排查时我把一份工作区里的 `config/aliases.yaml`
> 改动当成污染直接 `git checkout --` 掉了，而那其实是人有意做的、未提交的修改，git 无从恢复。
> **对未提交改动做不可逆操作之前先备份或先问**，看了 diff 也不等于知道它的来历。

### A2. iteration-2 评测：把断言换狠

iteration-1 是 35/36 = 97%，**这个数字要打折看**：断言几乎全过，说明它们主要在测
「技能有没有被读懂」，而不是在探边界。

- [ ] 淘汰不具区分度的断言（无论技能怎么写都会过的那些）
- [ ] 新增更狠的：语义正确性而非命令形态。例如——提取的 JOIN 条件在真实数据上
      join 出来的行数是否合理；`--confidence` 的取值是否和证据强度匹配；
      面对图谱里已有的等价关系会不会重复写入
- [ ] 补一个**负面用例**：给一段有陷阱的代码（比如 `<if>` 里嵌 `${}` 拼接的动态表名），
      看技能会不会诚实说「这段解析不了」而不是硬编一条关系
- [ ] 跑 iteration-2，用 `--previous-workspace` 对比

> 真正有信息量的东西断言测不到（提取的语义对不对、给用户的解释清不清楚）。
> 那部分靠人在评审页看，`review-iteration-1.html` 的反馈还没收。

### A3. 技能描述触发词优化

- [ ] 用 skill-creator 的 `run_loop.py` 跑一轮描述优化（20 条触发/不触发查询，
      60/40 分训练与留出集）。当前描述是手写扩充的，没量化验证过

## B. 需要先决策，再动手

这四条是夜间批次挖出来的，**到 2026-08-27 只剩最后一条**：

- [x] **字段级的闸门已定：`verified` 位，不建候选态**（2026-08-27，详见上面「本轮挖出的问题」
      第 1 条的完整记录）。Agent 写入不自封已确认，人在 UI 改过即确认，
      describe 与完整性看板两处消费它
- [x] **`hashWorkspace` 已改成反射枚举**（2026-08-26，随 `f7db1bf`）
- [x] **两套平行的关系遍历实现已合并**（2026-08-26，见上面 P0 那节的记录）：
      共享层 `RelationAdjacency`，两种遍历各自在上面实现自己的走法
- [ ] **`toOptional` 无数据来源**：父表是否必然有子行，单列 `nullable` 推不出来，
      需要跨表统计，属外延侧第 3 层

## C. 已立案未排期（名单不变，仅提醒）

- 外延侧数据剖析第 2 层（包含依赖 IND 发现）、第 3 层（FD / UCC）
- 可视化 A2 全库血缘视图、B2 剖析质量、B3 业务数据图表
- ~~BI 语义层第一批的验收标准还没跑~~ **已跑通（2026-08-27）**，
  连带补上了此前完全不存在的 metric UI 入口，见下方「BI 语义层」一节
- `GrainSqlDialect` 支持更多方言（当前四种；H2 等落在 generic 上展不开时间粒度，
  已有定义时提醒，但要真支持得逐个补截断函数）

---

# P1 · 内涵侧模型补强（2026-08-26 立项 · 六条全部完成）

> 六条全部交付并通过测试。已完成的条目保留在下面作为实现记录与设计依据，
> 不再从这里取待办。

> 来源：本轮把内涵侧（schema 与语义本身，不含数据取值）对着理论基线数了一遍——
> 关系模型 / ER / 业务语义层三条线，逐条比对 `com.sqlcli.graph.workspace` 现有模型。
> 判据仍是那一条：**不影响「Agent 生成的 SQL 对不对」的，不补。**
>
> **四层成熟度极不均匀，先说结论免得误会成「要补全」**：
>
> | 层 | 结论 |
> |---|---|
> | 溯源层（`BaseGraphObject` 的 status/confidence/verified/actor/version、`RelationEvidence`、`LineageRecord`、`graph_change_log`） | **强于同类，一个字段都不用加。** `GraphStatus.forActor()` 把「机器写的默认不生效」做成了 enum 默认值而不是流程纪律，这是整个体系最重要的一行代码 |
> | 结构层 | 扎实，但有一条边在静默丢信息 → 第 1 条 |
> | 概念层（ER） | 缺三样，其中两样已在 ER 文档 §4 立案却从没进过清单 → 第 3、4 条 |
> | 语义层 | 模型够用，但 **Agent 侧写入路径是断的** → 第 2 条 |
>
> 排在 BI 语义层之前的唯一理由是第 1 条：复合外键不修，`MetricRecord.joinPath`
> 接到的就是残缺的 join 条件。其余几条可以并行或延后。

## 1. 复合外键被拆成 N 条独立边（静默错误 · 挡着 metric）

`WorkspaceMetadataExtractor.extractForeignKeysForTable` 逐行读 `getImportedKeys`，
**`FK_NAME` 与 `KEY_SEQ` 两列一次都没读过**。JDBC 对一个两列复合外键返回两行，
于是 `(tenant_id, order_no) → order(tenant_id, order_no)` 落进图谱是
**两条毫无关联的 `foreign_key` 边**，各带一个单列 `joinExpression`；
模型里不存在任何东西表示「这两条边属于同一个约束」。

后果：Agent 沿其中一条生成 `JOIN order o ON t.order_no = o.order_no`，
漏掉 `tenant_id` → 跨租户笛卡尔积 → 行数翻倍。**不报错、有结果、数是错的。**
把租户列放进复合主键在国内企业库里近乎标配，这不是边缘情况。

- [x] `addForeignKeyRelation` 读 `FK_NAME` + `KEY_SEQ`，同名约束的边归一组
      （组标识与列序存 `edge.attributes`，不新增模型字段，旧图谱天然兼容）
- [x] 组内 `joinExpression` 按 `KEY_SEQ` 拼成完整 `AND` 条件，单列约束行为不变
- [x] `WorkspacePathFinder` 与后续 metric 展开**按组取边**，不再按单条取
- [x] 回归：建一张两列复合外键表导入，断言同组两条边可还原、拼出的 join 含全部列对
- [x] 顺带核对：`GraphWorkspaceMerger` 增量合并时同组边不会被拆散或半数丢失

## 2. CLI 写不了 `businessName` / `semanticType`（Agent 补语义的路径是断的）

`schema edit` 只有 `--description` / `--enum-values` / `--format` / `--example` /
`--add-tag` / `--add-constraint`（`SqlCli.java:522` 起那串 `case`）。
而 Web UI 写得了——`WorkspaceMutationController` 的字段白名单里
`businessName` / `semanticType` 都在，`WorkspaceMutationService:397-439` 有实现。
`SchemaActionCommand` 里 `businessName` 出现七八次，**全是 search 结果的读路径**。

也就是说现在的分工是「人能写语义，Agent 不能」，与产品定位正好反了。
Agent 从代码里读出「`buyer_id` 是买家 ID」之后只能塞进 `--description` 自由文本，
而 `SemanticType` 的 javadoc 恰好写着自由文本会让 `PolicyEvaluator` 的
`semanticTypeAny` 一条都命中不了。

- [x] `schema edit --column` 加 `--business-name` 与 `--semantic-type`，
      `--table` 加 `--business-name`
- [x] 校验复用 `SemanticType.fromValue`，非法值报错并列出全部合法取值（不静默吞成 null）
- [x] 与 UI 走同一套字段白名单语义，避免 CLI / UI 再分叉
- [x] 回归：CLI 写入后 `schema describe` 与 UI 表详情读到同一个值

## 3. 参与约束 / optionality（ER 文档 §4.1，已立案未排期）

`RelationCardinality` 只有 one_to_one / one_to_many / many_to_one / many_to_many /
unknown——**只有基数，没有可选性**。可选性直接决定 `INNER JOIN` 还是 `LEFT JOIN`，
是 Text-to-SQL 最高频的错误来源之一，**且错了同样是静默的**（少了行，不报错）。

- [x] 关系两端各加一个可选性标记（沿用「存 `attributes` 不动模型」的做法，与第 1 条一致）
- [x] 导入时给初值：外键列 `nullable = true` → 该端可选。`ColumnWorkspaceNode.nullable` 已有
- [x] 初值一律进候选，由人确认后才影响生成
- [x] 消费方：`schema path` 输出与 metric 展开按可选性选 INNER / LEFT

## 4. 函数依赖 / 权威来源（ER 文档 §4.2）

宽表里的 `customer_name` 是下单那一刻的冗余快照，权威源在 `customer` 表。
Agent 检索到哪个用哪个，可能拿到历史快照而不是当前值——又一个静默错误。

- [x] 标注「此列是彼列的冗余副本」，权威源指向另一列（沿用「存 `attributes` 不动模型」
      的做法，`ColumnWorkspaceNode.ATTR_REDUNDANT_OF` 存权威源列 id；
      `schema edit --column --redundant-of S.T.C` 写入，传空串清空）
- [x] 检索命中冗余列时提示权威源：`WorkspaceSearchEngine.SearchResult` /
      `WorkspaceIndexedSearchEngine.SearchHit` / `WorkspaceIndexDocument` 三处都带上
      `redundantOf` 字段（JSON 契约），CLI 文本输出在命中列后追加
      `[冗余，权威源:schema.table.column]`
- [x] 校验：`WorkspaceValidator` 新增 `dangling_redundant_source`（权威源列不存在）与
      `self_redundant_source`（指向自己）；CLI 写入时也会立即拒绝这两种情况
- [x] 边界不变：**不做规范化建议**（"不满足 3NF 建议拆表"是设计期的事，不是我们的场），
      **也不做自动发现**（从数据推导函数依赖属于外延侧数据剖析，未排期，这次只做承载
      标注的落点）
- [x] 已知限制：`ColumnWorkspaceNode` 不继承 `BaseGraphObject`，这条标注和
      `businessName`/`semanticType` 一样没有候选→评审→发布闸门，写入即生效
      （见上一节"本轮挖出的、之前没记录过的问题"第 1 条，这次不在范围内解决）

## 5. `joinExpression` 目前只写不读

全仓消费者为零：只有 `SchemaActionCommand` 的入参、`GraphWorkspaceMerger:333` 的补空、
以及字段声明本身。这个字段是 metric `joinPath` 唯一能接的东西。

- [x] 随第 1 条一起，让 `schema path` 输出携带可执行的 join 条件
- [x] metric 展开直接消费它，不另起一套 join 表达

## 6. 候选被驳回后信号即丢失

`WorkspaceMutationService.rejectRelation` 直接转调 `deleteRelation`——**拒绝 = 删除**。
除了 `graph_change_log` 里一条 delete 记录，图谱里不留任何痕迹，
下次导入或挖掘会把同一条候选原样再生一遍，人得再拒一次。
`GraphStatus.ignored` 这个态是现成的，但目前只被 `WorkspaceMetadataExtractor` 用来标系统 schema。

- [x] 拒绝改为置 `ignored` 并记原因，而不是物理删除
- [x] 候选生成侧（导入的 FK 之外的推断、未来的 IND 挖掘、metric 建议）先查一次 `ignored`，命中则不再产出
- [x] UI 给「已忽略」一个可翻看、可撤销的入口，否则等于埋了
- [x] 这是**唯一一段不依赖查询流量的反馈闭环**——不用等方向 A 的燃料，随时可做

---

# P2 · 图谱与 UI 收尾

- [x] 工作台执行中止：需要服务端 `Statement.cancel()` + taskId 注册表（设计文档 v2 留缝），
      前端才配得上一个真的「中止」按钮
- [x] 工作台结果列头显示字段业务含义与值域：先解决「结果裸列名 → 图谱列」的映射规则
      （SELECT 别名、跨表同名列对不上）
- [x] 工作台表名补全（带业务描述）：textarea 上自绘候选浮层 + 键盘导航，独立一块
- [x] 术语子视图（列表 + 同义词 + 映射）：仍只有 CLI `add-term`，UI 一个入口都没有
- [x] 候选批量发布 / 拒绝：`POST /api/relations/review`（`action` + `relationIds` 数组，
      逐条处理返回每条成败），`RelationList.tsx` 加全选/勾选和批量发布/拒绝按钮
- [x] 值域的 UI **编辑**：`TableInspector.tsx` 的 `ValueHints` 组件从只读展示加了「编辑」
      表单，走 `schema edit --enum-values` 同一套校验规则
- [x] **写控件已接上 readonly/capability**：`GraphUiApiRouter.newSession` 按别名的
      `DatabaseConfig.getReadonly()` 下发会话，不再写死 `false`；语义定为别名 readonly
      指数据库只读，随会话下发给前端展示与工作台提前禁用，不限制图谱工作区本身的编辑
- [ ] PostgreSQL 跨 database 的降级提示（PG 一个连接绑定一个 database）
- [ ] 多 schema 导入后的规模验证：几千张表时的画布渲染与索引重建耗时
- [x] 增量索引更新：表改动后只更新受影响文档（设计文档 24.2；当前规模全量够快，
      出性能问题再做）

---

# P2 · 工程化与契约

- [x] `task status <id> --json` 只读查询——已实现（`SqlCli.executeTaskCommand`），
      按 id 读 `task_run` + 关联事件，纯查询，不受审批阻塞；配套 `task list` 按
      `--alias`/`--status`/`--limit` 过滤最近的写任务
- [x] Router/Controller 的手工 `path.startsWith` 分发（27 处）收成「路由表 → handler」映射
- [x] CLI 管理命令打废弃提示（仅提示，不移除）：alias/driver 的 add/update/remove 在 stderr
      提示改用 Web UI 设置页
- [x] 安全复查：SQL/错误/日志脱敏、恢复文件权限、session token、Origin、readonly capability。
      五项逐一过了一遍，实际改了三处、两处确认已有防护：
      1）脱敏：`ConnectionManager`/`SqlTaskModule`/`RunStateStore` 早已用 `JdbcUrlParser.redactSecrets`，
      唯独 `PrecheckStage` 的动态预检失败分支直接拼 `e.getMessage()` 落进 `Precheck.skippedReason`
      （会经 `task_event.payload` 落库、也会原样回显到工作台响应），补上 `redactSecrets`；
      2）恢复文件权限：`RecoveryResult.saveToFile` 之前只靠 `Files.createTempFile`/`createDirectories`
      的默认权限，POSIX 系统上补 `Files.setPosixFilePermissions`（文件 600、目录 700），Windows 上
      `supportedFileAttributeViews()` 判空跳过；
      3）session token：`GraphUiSession.validateWriteRequest` 的 `token.equals(sessionToken)` 换成
      `MessageDigest.isEqual`，全仓再无第二处密钥/令牌用 `.equals` 比较；
      4）Origin：`OriginCheck.matches` 逻辑本身没有绕过口子（畸形 Origin、scheme 为空都会落到 false）；
      但 `GraphUiApiRouter.handleRowCount`（`/api/tables/row-count`）会顺手把行数写回图谱
      （`persistRowEstimate`），是个写路径，之前漏了 Origin 校验，补上，和同文件其他写端点对齐；
      5）readonly capability：CLI 直接执行和 Web UI 的 `/api/sql/execute`、`/api/workbench/execute`
      全部唯一收口于 `SqlTaskModule` 的 `GuardStage`（`isReadonlyAlias` 判断），没有另外的
      `RecoveryExecutor` 之类的旁路——恢复 SQL 如果要重放也只能走这同一条执行入口，天然受
      readonly 约束，未发现可绕过的写路径。
- [x] 导入回归矩阵自动化：生命周期清单 3.4 那张表全部落进
      `WorkspaceImportServiceTest`（11 格原有覆盖 + 本次补的 4 个：无变化刷新稳定、
      双向 FK 不重复不丢失、resume 结果与一次性导入一致、不支持 FK 的 provider 保留旧关系）。
      **顺手抓到一个真 bug**：表提取失败时，依赖该表的 FK 提取任务被静默标记
      `completed`（`extractForeignKeysForTable` 里目标表缺失就直接跳过，不算失败），
      `resume` 只重跑 `failed` 任务，于是这条 FK 永远补不回来。修法：FK 任务开始前检查
      同批有没有失败的表提取任务，有就让 FK 任务本身也失败，等 resume 时表补全了一起重跑。
- [x] `DatabaseStrategyContractTest` / `WorkspaceMetadataProviderContractTest`：
      所有 Strategy 和 Provider 过同一组契约（ClickHouse 清单 DB-P3-002/003）
- [ ] 工作区诊断命令：目录完整性、丢失文件、索引与主数据一致性（设计文档 24.4）
- [x] cli-command-test-cases 补：多 schema 导入、驱动上传、规则页新形态、值域写入
- [x] user-manual §13 补按 schema 逐个导入的 UI 路径

**从 2026-08-25 代码审查快照并入（那份快照已删除，剩下的就这些）：**

- [ ] 攒一批做的重复（单独任一条都不值得开一次分支）：
      `AliasCommand.maskSecretRef` 与 `AliasAdminController.maskSecretRef` 逐字相同——
      后者注释里已写明「与 AliasCommand.maskSecretRef 同语义」，**作者知道在复制，是被包可见性逼的**，
      修法是提到公共工具类而不是再抄一遍；前端 `endpointSummary` 两份
      （`RelationEditor.tsx:61`、`RelationList.tsx:23`）+ `GraphCanvas` 第三种解析方式
      （**注意：两份阈值写法不同但对所有输入行为等价，是真重复、不是潜伏 bug**）；
      前端 `err instanceof Error ? err.message : '...'` 7 处
- [x] 后端算了、前端不用的 DTO 字段：`RelationEdgeDto` 的
      `weight`/`sourceAlias`/`createdAt`/`updatedAt`/`createdBy`/`updatedBy`、
      `ValidationIssueDto` 的 `producer`/`runId`，前端 tsx 里 0 命中。
      要么前端用起来，要么后端别算（`recentChanges` 已随血缘 UI 用起来了，不在此列）——
      查完发现这俩 DTO 根本没有独立的 Java 类：接口直接序列化 `RelationWorkspaceEdge` /
      `ValidationIssueRecord`，JSON 既是 API 响应也是图谱落盘格式（`GraphWorkspaceStore`
      拿同一个 ObjectMapper 读写）。只删了真正死掉的 `weight`（Java 里连内部读写都没有，
      纯背景默认值 1.0）；同时补了 `@JsonIgnoreProperties(ignoreUnknown = true)`，
      不然老图谱文件里存量的 `weight` key 会让反序列化直接炸。其余字段留着未删并在此说明：
      `sourceAlias` 是关系自己内部要用的（建反向边、拼 ID，见 `WorkspacePathFinder`）；
      `createdAt`/`updatedAt`/`createdBy`/`updatedBy` 继承自 `BaseGraphObject`，
      跟被禁止改动的 `TableWorkspaceNode` 共用基类，删了影响面出这张清单之外；
      `producer`/`runId` 在 `WorkspaceValidator`/`PolicyService` 里被内部读写（去重、
      关联规则评估批次），且同一个类要落盘重载，`@JsonIgnore` 会连持久化一起削掉，不安全
- [ ] `database-graph-implementation-overview`（652 行）**未被那轮审查覆盖**，需要补一轮
- [x] **测试污染开发机真实运行库**（2026-08-26 实测发现，不是本批引入的）：
      多数用例用 `new RunStateStore()` 默认路径，只有少数几个类设了 `sqlcli.home`。
      实测 `~/.sql-cli/sqlcli.db` 里已有大量测试别名的行——
      `graph_change_log`：`unit` 3519 / `commands` 565 / `e2e-test` 366 / `json-contract` 196 /
      `invalid-validation` 65 / `concurrent` 60 / `lineage-test` 42（真别名 `erp_plush_test` 才 70）；
      `sql_execution`：`safety-test` 32 行。
      **后果不只是脏数据**：图谱变更流水里 98% 是测试行，真要按它查审计等于没有。
      修法不是逐个测试类加 `@BeforeEach`（漏一个就前功尽弃），
      而是在 surefire 的 `systemPropertyVariables` 里全局设 `sqlcli.home` 指向 build 目录——
      一行配置，结构上不可能漏

---

# P2 · ClickHouse 接入收尾（并行轨道）

按其[专属清单](clickhouse-datasource-integration-checklist.zh-CN.md)推进，余 13 条，
以测试与硬化为主。其中：

- `CH-P2-001`（大结果集保护）已并入 P0 的查询上限
- `DB-P3-008`（恢复 SQL 能力建模）在 P0 的事务边界实现时一并考虑
- `CH-P1-027~030` 是 Testcontainers 集成测试，与上面的工程化轨道合并做更划算

---

# 下一阶段 · BI 语义层（metric 定义层 · 第一批已交付，验收 2026-08-27 跑通）

> 方向判断见 [产品演化方向](product-evolution-direction-2026-08.zh-CN.md) §3、§5（2026-08-25 重排）。
> **这一批取代了原来排第一的「执行反哺图谱」**——实测 `sql_execution` 只有 302 行、
> 含 JOIN 的 42 条且基本是自测流量，那一步的燃料还不存在（详见方向文档 2.6）。
>
> **做的是 BI 语义层，不是 BI 展示层。** 这个区分仍然成立——图表见「下一阶段 · 可视化」B3，
> 它和 metric 定义是两条线，做了图表不改变这条线的内容。

**第一批两条必须同批交付**——只交付定义不交付消费方，等于再产出一份没人用的语义资料，
正是这个产品反复犯的那个病。

- [x] **metric 定义层**：`GraphWorkspace` 加第四个集合 `Map<String, MetricRecord>`，
      与 `terms` / `lineage` 平级（已拍板，见方向文档 3.5）。字段最小集：
      `name`/`businessName`/`aliases[]`（复用术语的同义词思路，让检索能命中）、
      `expression`、`filters`（**口径争议 90% 在这一行，是整层的存在理由**）、
      `grain`（时间列 + 支持粒度）、`dimensions[]`、`joinPath`（跨表时人工显式声明）、
      以及 `status`/`confidence`/`verified`/revision（白拿候选评审与审计）。
      配套：CLI 写入命令 + 图谱页评审入口 + 检索能命中 metric
- [x] **metric → SQL 骨架**：把一条 metric 按 `grain` 与 `dimensions` 展开成可执行 SQL。
      这是 metric 价值兑现的唯一时刻，不能排到下一批
- [x] **验收已跑通（2026-08-27）**，但先说跑之前发现的事：**metric 在 Web UI 里一个入口都没有**——
      没有 `/api/metrics` 端点，没有页面，`grep MetricRecord` 在整个 `graph/ui` 包里零命中。
      第一批交付的定义层和 SQL 展开全在 CLI，所以这条验收按字面**根本跑不了**，
      不是"还没抽时间跑"。

      「两条必须同批交付」当时兑现成了「定义层 + 展开层」，而验收标准写的是
      「人在 **UI** 里定义」——交付内容与验收标准从一开始就对不上，这个缺口在清单里
      挂了一整轮没人发现。补的是：
      - `GET/POST /api/metrics` 与 `GET /api/metrics/{name}/sql`（挂在
        `WorkspaceMutationController` 上，沿用关系那套「读写同一个控制器」的既有形态）。
        **UI 写入的 actor 是 human，指标直接 verified**；CLI 写的是候选——这不是权限大小，
        是「人声明口径」与「Agent 猜口径」的分界
      - 图谱页左侧第三个标签「指标」：列表 + 定义表单 + 展开 SQL + 「去工作台执行」
        （复用表详情跳工作台那套 sessionStorage 交接）。不新增一级导航
      - 列引用收 `schema.table.column`，服务端走 `GraphWorkspace.resolveColumnId`——
        这一份解析从 `SchemaActionCommand` 提上来，CLI 与 UI 共用，不再各写一份

      **端到端结果**（隔离的临时 sqlite，`sqlcli.configRoot` + `sqlcli.home` 都指向
      build 目录，全程没碰真实别名配置与图谱）：定义
      `gmv_paid = SUM(amount) WHERE status IN (2,3)`，按天展开，经
      `/api/workbench/execute` 执行 →
      `2026-08-01 = 150.5`、`2026-08-02 = 200.0`，**口径过滤真的生效了**
      （被排除的是 status=1 的 999.0 和 status=9 的 77.0；漏掉 filters 的话这两笔会混进去，
      而那正是"不报错、有结果、数是错的"）。

      **覆盖到哪为止**：浏览器 DOM 那一层由 `MetricList.test.tsx` 四例覆盖（渲染、
      展开后送工作台、表单带对 revision、缺表达式不给保存），服务端那一层由
      `MetricApiTest` 五例 + 上面这次真库执行覆盖。没有做的是真浏览器点一遍。

      ponytail: **joinPath 这一版只展示不编辑**，跨表口径仍走 CLI
      `add-metric --join-path relId|inner`。要在 UI 里编辑得先有个关系选择器
      （关系是几百上千条，不能平铺），那是独立一块——等真有人要在 UI 里定义跨表指标再做。

**明确不装**：物化 / 预聚合 / 调度 / metric store 存储层 / 指标血缘大图——
那是 dbt 与 Cube 的赛道。判据不变：不服务于「让 Agent 生成更正确的 SQL」的，不做。

**这一层的续作（2026-08-27 讨论产出，F1~F6）记在
[2026-09 清单](dev-checklist-2026-09.zh-CN.md)**：粒度字段与扇形陷阱检查、比率指标与可加性、
设计阶段声明粒度的 policy 规则。那份开头还写了「语义层为什么不是 DDD 领域模型」的判断依据，
下次有人想把聚合根建进图谱时先看那一节。

**第二批（执行反哺图谱）的开工信号**，满足任一才动手，在那之前一行代码都不写：

- [ ] 由 metric 展开产生的查询累计过百，且来自真实业务问题而非自测
- [ ] 或：出现 metric 声明里没覆盖、却被反复手写的 JOIN 组合

---

# 下一阶段 · 外延侧：数据剖析（第 1 层已交付，第 2、3 层未排期）

> **这是一条新线，不是上面那些的延伸**，因为它跨过了一条硬分界：
> 内涵（intension）= schema，外延（extension）= 数据取值本身。
> 上面所有条目都在内涵侧；「了解自己的数据 / 挖掘 / 分析」的主体在外延侧。
>
> 理论基线不是本体论，是**数据剖析（data profiling）**——
> Abedjan / Golab / Naumann, *Profiling Relational Data: A Survey*（VLDB J. 2015）。
> 它的三层分类法可直接当路线图：单列 → 多列（同表）→ 跨表。
>
> **现状：外延测量为零。** `ColumnValueHints` 的 `enumValues` / `sampleValues`
> 全部由人或 Agent 手写，从来没有一行代码去数据里量过；
> 模型里不存在 `nullRatio` / `distinctCount` / 直方图；
> `cardinality` 只是关系的声明基数，不是列统计。

## 1. 单列剖析（先做这一层，后面两层都依赖它剪枝）

- [x] 采样或全量统计：行数、空值率、distinct 数、最大/最小、长度分布
- [x] 产出直接回填已有字段：`enumValues` 从**手写**变成**测出来**（低基数列自动候选）
- [x] 空值率喂给 P1 第 3 条的 optionality，给它一个比 `nullable` 更准的初值
- [x] 与 `semanticType` 对账：标了 `phone` 的列实际长度分布不像手机号 → 标注质量告警
- [x] 采样规模、超时、是否走只读连接要可配；大表默认采样不全扫
- [x] 结果一律进候选区，不直接改图谱（与既有评审流程一致，不开后门）

## 2. 包含依赖（IND）发现 → 自动找出未声明的外键

`A.x ⊆ B.y` 成立 → 极可能是一条没声明的外键。**这是「自动发现关系」的正解**，
而且它答的正是方向 A（执行反哺）想答但当前答不了的那个问题。

对比方向 A（挖 `sql_execution` 的 JOIN）：

| | 执行反哺（方向 A） | IND 发现 |
|---|---|---|
| 燃料 | 真实查询流量。实测只有 302 行、含 JOIN 42 条且基本是自测 | **不需要流量，扫数据本身** |
| 幸存者偏差 | 严重：只能学到有人查过的 JOIN | **没有：全部列对都在扫描范围内** |
| 主要风险 | 烂 SQL 被挖进来 | **假阳性多**（`status ⊆ type` 这类小值域列互相包含） |

- [ ] 用第 1 条的单列结果先剪枝：基数过低、类型不兼容、空表的列对直接不参与
- [ ] 候选按包含率与基数加权，低于阈值不产出
- [ ] 产出走 `join_observed` 候选 + `RelationEvidence`（`sourceType` 标明是剖析得来）
- [ ] 命中 P1 第 6 条的 `ignored` 名单则不再产出
- [ ] 先验证一遍规模成本：499 表 / 9727 字段全列对是多少组合、剪枝后剩多少

## 3. 多列 / 同表（函数依赖、唯一列组合）

- [ ] FD 发现给 P1 第 4 条「权威来源」提供候选，而不是纯手工标
- [ ] UCC 发现事实上的候选键（没声明唯一索引但实际唯一的列组合）
- [ ] 优先级低于 1、2 两条；且**仍然不做规范化建议**，边界不变

> **与方向 A 的关系**：IND 不替代执行反哺，它把执行反哺的开工时间提前解耦了。
> 方向 A 仍按 [产品演化方向](product-evolution-direction-2026-08.zh-CN.md) §5.2
> 的信号触发，本节不改那个判断。

---

# 下一阶段 · 可视化：血缘图与数据质量（A1 / B1 已交付）

> **2026-08-26 计划变更**：原「明确不做」是前期阶段的收敛动作，已整节删除。
> 本节此前写成「待裁定」是因为它与那份清单冲突，冲突不存在了，改为正式条目。
>
> 判据仍然保留，但**只用来排先后，不再用来否决**：
> 服务「让 Agent 生成更正确的 SQL」或「让人更快确认 Agent 的产出」的先做。
>
> UI 约束不变：一级导航固定五项，**这几项都不新增菜单**，落在既有页面内。
> 图表配色遵守 CLAUDE.md 的三态状态色（绿正常 / 黄需注意 / 红不可用），
> 复用 `.shell-dot` / `.cell-dot`，不另起一套。

## A. 血缘关系图

现状：`TableInspector.tsx` 的 `ColumnLineagePanel` 已有上下游端点**列表**，没有图。

**A1 · 按需追溯图（先做）**

- [x] 从**某一个字段**进入，上下游展开成图；沿用 `schema lineage --depth`，默认 2~3 跳
- [x] 边上显示 `expression` 与 `through`（列表里已有这两个字段，改图之后别丢）
- [x] 节点超过阈值折叠，不追求把大图画好看
- [x] 落在表详情页内，不新增导航项
- [x] 复用 `schema diagram` 已有的 Mermaid 产出思路，还是前端自绘，先定一次再动手

**A2 · 全库血缘视图（A1 之后）**

- [ ] 入口在图谱页，按 schema 或按表集合圈定范围，不做「一次画全库」
- [ ] 规模先验证：几千张表的渲染与布局耗时（与 P2 的多 schema 规模验证一起做）
- [ ] 需要分层折叠 / 聚焦某节点邻域，否则大图不可读——这是它排在 A1 之后的原因

## B. 数据质量图表

三块，成本与依赖差别很大，按顺序做。

**B1 · 图谱自身完整性看板（成本最低，先做）**

数的是图谱自己的健康度，不碰用户业务数据；数据现在就在 `WorkspaceStats` 附近。

- [x] 覆盖率：多少表/字段有 `comment`、`businessName`、`semanticType`、`valueHints`
- [x] 待办量：候选待评审数、`ignored` 数、`ValidationIssueRecord` 按 severity 分布
- [x] 关系健康：孤立表数、只有 `foreign_key` 没有 `join_observed` 的表
- [x] 落在工作台健康视图或图谱页，不新增导航项

**B2 · 数据剖析质量（依赖「外延侧 · 数据剖析」第 1 条）**

- [ ] 空值率异常、孤儿外键（FK 值在父表找不到）、基数异常、疑似枚举列未标注
- [ ] 这是剖析结果的展示面，剖析没做之前无从谈起

**B3 · 业务数据图表**

对用户查询结果做可视化。**与 B1/B2 是完全不同的东西**：B1/B2 看的是元数据健康度，
B3 看的是业务数据本身，成本也高一个量级（图表类型选择、聚合配置、大结果集渲染）。

- [ ] 先定范围：是「工作台查询结果一键出图」这种轻量形态，还是可保存可配置的看板
- [ ] 轻量形态可先做：结果集已在前端，加折线/柱状/饼三种够用
- [ ] 可保存看板属另一个量级，需要单独立项再评估
- [ ] BI 语义层与 BI 展示层的区分仍然成立——metric 定义那条线不因为做了图表而改变

---

# P3 · 按触发立项，不排期

## 共享验证 → Team Server / MCP

- [ ] `schema export` 快照 + `import --merge` 点对点交换实践与冲突手册
- [ ] 触发条件（团队版决策记录 12 节）：≥3 人共享、冲突高频、跨人审批需求
- [ ] 满足后立项：Team Server（Spring Boot 独立模块）+ MCP Adapter
      （直调核心 Module，四 Tools + `task://` Resource，不包装 CLI 子进程）

## 字段级血缘（已实现，按下面这份设计落地）

> **结论：既不塞进 `RelationType`，也不建独立存储——在 `GraphWorkspace` 里加一个
> 和 `terms` 平级的第三个集合。** 写在这里是为了防止下一个人把 `lineage_to`
> 加回关系类型里——那正是这轮收敛删掉的东西。

**为什么不是一个关系类型：**

1. **它不是二元边，是 n 元推导。** `SUM(a) + SUM(b) → c` 是三个列一条推导。
   拆成 `a→c`、`b→c` 两条边就丢了「a 和 b 一起产生 c」这个结构，表达式还得复制两份。
   关系模型只有 from/to 两个端点，装不下。
2. **它必须带上下文。** 同两个列，ETL 作业 X 里有血缘、作业 Y 里没有——血缘只在某个视图 /
   某条 `INSERT...SELECT` / 某个 Mapper statement 里成立。`RelationEvidence` 是
   「在哪儿看到的」，而血缘的 `through` 是「靠什么产生的」，是血缘本身的一部分，不是佐证。
3. **规模差两个数量级。** 实测 531 表 / 10114 字段只有 **72 条关系**；血缘按
   「列 × 每次派生」计，一条 30 列的 `INSERT...SELECT` 就是 30 条，几百条 ETL 语句轻松上万。
   而 `relations` 是扁平 `ArrayList`，全仓 **26 处线性全扫**，有些嵌在按列的循环里
   （`PolicyEvaluator.hasDictionaryRelation` 就是 O(列数 × 关系数)）。涨 100 倍这些地方直接爆。
4. **查询语义不同。** 血缘是「顺着 target 递归走到源头，每一跳带变换」；
   `schema path` 是「两点之间找 ≤N 跳的连通路径」。同一份数据上跑两套遍历没必要。

**为什么也不建独立存储：** revision、写锁、原子落盘、变更记录、候选状态全要再写一遍。
批次 6 定候选方案时就否过这个思路（「不建独立候选存储」），同样适用。

**形态：**

```java
// GraphWorkspace，与 schemas / tables / terms 平级
private Map<String, LineageRecord> lineage = new LinkedHashMap<>();

LineageRecord {
    String target;         // 列 id，1 个
    List<String> sources;  // 列 id，n 个
    String expression;     // SUM(a)+SUM(b) / CASE WHEN ...
    String through;        // 视图名 / ETL 作业 / Mapper statement id
    // + BaseGraphObject 的 status/actor，直接复用候选那一套
}
```

- 复用：revision、写锁、原子落盘、`graph_change_log`、候选发布/拒绝
- 分开：不进 `relations`（画布与关系列表自然不受影响）、按 `target` 建 map 索引
  （查上游 O(1)）、自己的命令 `schema lineage <column> [--upstream|--downstream] [--depth N]`

**数据从哪来：** 和 JOIN 关系一样由 Agent 读代码写回，**不做静态扫描器**——
`scan-sql` 正是因为对 MyBatis 动态 SQL 解析失败率 100% 被删掉的，血缘抽取比 JOIN 抽取难得多。

---

# 已完成（记录，不再逐条展开）

| 时间 | 内容 |
|---|---|
| 08-20 | App Shell + 正式路由；scan-sql 全链路删除，JOIN 提取移交 Agent |
| 08-20 | 批次 1 地基修复：空 bindings 视为通过；`DatabaseErrorMessages` 接入 `ConnectionManager` |
| 08-20 | 批次 2 SQLite 运行库：`sql_execution` / `graph_change_log` 可用，JSONL 一次性迁移后停写 |
| 08-20 | 批次 3 generation 清理：只保留当前代，78MB/35 代 → 7.1MB/1 代 |
| 08-20 | 批次 4b 历史 SQL 重执行：只读白名单 + `ResultTable`（自包含切片；缺的 SM4 解密与历史留痕已于 08-24 随 SqlTaskModule 补齐） |
| 08-20 | 批次 5 设置页：别名增改删、连接测试、密钥绑定 |
| 08-20 | 表详情单表刷新与行数按需采集 |
| 08-20 | 批次 7 CLI 机器契约：JSON schema / 错误码 / 退出码，`JsonContractSnapshotTest` 锁定 |
| 08-21 | 批次 6 候选变更：`GraphStatus.candidate`、关系编辑侧栏、候选分区、confidence 强制 |
| 08-21 | 批次 9 多 schema：真正枚举全部 schema、按 schema 逐个导入、`defaultSchema` |
| 08-21 | 批次 10 驱动管理 UI：jar 三色灯、引用保护、默认驱动、**jar 上传**（原计划不做） |
| 08-21 | 修复 SQLite 依赖缺失——批次 2 声称完成的运行库其实一直没工作 |
| 08-21 | UI 一致性整理：全量中文化、全局 `.btn` 收敛、入口去重 |
| 08-22 | 一级导航收敛为五页；图谱导入入口收敛到图谱页（图谱页去掉 `needsGraph` 门禁） |
| 08-22 | 规则页重做：两个固定分组、13 类别、规则讲解、预填全部 |
| 08-22 | **审批闸门**：`ApprovalGate` 阻塞式放行 + 评审页（**取代了原 P0-3 的任务队列设计**） |
| 08-22 | 工作台写操作回滚预览（只看不执行）；执行记录读/写两类行为分流 |
| 08-22 | 表详情值域展示与索引列表 |
| 08-22 | **值域闭环**：`--enum-values` / `--format` 写入路径，`status_field_dictionary` 改查字典关系 |
| 08-22 | 修 `ColumnValueHints` NPE——`--example` 从来没能用过 |
| 08-22 | 修 `SHOW TABLES` 被判为非只读；`UnsupportedStatement` 退回按首关键字判断 |
| 08-22 | 修 Origin 校验误杀（localhost / 127.0.0.1 视为同一主机） |
| 08-22 | 修 `RelationEditor` 默认关系类型是已删除的值；关系类型 7 → 3 的文档全面对账 |
| 08-24 | **SqlTaskModule**：全项目唯一 SQL 入口，`QueryExecutor` 删除；结构化结果 + 截断标志、写操作事务边界、`task_run`/`task_event` 接线、行数上限与超时、UI 重放收编（详见本文顶部） |
| 08-24 | 默认 UI 端口 8080 → 9999（含 vite dev 代理、README、手册） |
| 08-24 | **三线并行开发**（3×Opus worktree + Opus 合并，Sonnet 验证按用户要求跳过）：A=SQL 工作台、B=Agent 查询正确率五项、C=审批闭环；合并解 1 处冲突（PrecheckDto 重复声明 + 迁移容忍重复加列）；370 后端 + 20 前端测试全绿 |
| 08-24 | **Agent 上下文补给批次**（共同点：数据早就在图谱/执行历史里，只是没在 Agent 需要的那一刻递出去）：describe 列行补业务名/语义/值域 + SM4 列标注（等值/IN 限制）+ 关系附可复制 JOIN 写法 + `Recent SQL` 成功范例（`sql_execution` 按 masked_sql 去重、词边界过滤）；执行报错「列/表不存在」带图谱模糊纠错（`GraphQueryHints`，MySQL/PG/Oracle 报错格式，ORA-00942 回头解析 SQL 表名）；describe/query 表名拼错带相近表建议；SELECT 0 行时 WHERE 值对照值域提示（值域项 `0=待付款` 只比 `=` 前的值）。describe JSON 契约 3 键 → 5 键（+`sm4Columns`/`recentSql`），SKILL.md 与手册 §13.3 已同步 |
| 08-25 | **执行历史七项**（用户提的一批）：①失败/被拒语句进历史且点开看原因 ②回滚脚本改存 SQLite（`recovery_artifact.rollback_sql`/`backup_text`，不再落文件，执行前入库、入库失败即不执行）③审批详情预估行数旁边补实际影响行数 ④审批不阻塞 Agent：审批提示带 task id，新增 `task list`，SKILL.md 要求「提醒用户去批准 + 命令丢后台 + 用 `task status` 回查」⑤执行记录从工作台挪到评审页（第三个标签），点 SQL 展开看格式化全文 ⑥按 schema / 语句类型 / 时间范围 / 状态四条件筛选（新增 `sql_execution.target_schema`）⑦写语句也存 `raw_sql`——原来只有只读语句存原文，`UPDATE ... WHERE id='<redacted>'` 展开也看不到改的是哪一行 |
| 08-25 | **评审页重做**：三个标签共用一套骨架（标题与标签 → 筛选条 `.review-bar` → 列表 → 分页条），待审批角标独立于当前标签；审批记录补状态/类型/时间范围筛选与分页；筛选**改到服务端**（分页之后前端过滤一页数据给的是错结果）；`ui/Pagination.tsx` 收敛为全站唯一分页条，后端 `countApprovals` / `countExecutions` 与列表共用同一份 WHERE |
| 08-25 | **收尾一批**：① HTTP 层 Origin 校验统一——`JsonHttpSupport.requireAllowedOrigin` 收口重复的 Origin 判断逻辑，`isClientDisconnect` 顺 cause 链找（原来只看最外层异常，断开连接常被包一层再抛）② 搜索权重表合并——`SearchMatching` 新增共享字段权重常量（`WEIGHT_NAME`/`WEIGHT_BUSINESS_NAME`/`WEIGHT_COMMENT`/`WEIGHT_SEMANTIC_TYPE`），实时引擎与索引引擎不再各自维护一份幻数 ③ 能力开关接上——`SqlExecutionPolicy` 新增 `isRequireReadonlyGuard`/`isAllowMultipleStatements`/`isAllowShow`，`GuardStage` 从硬编码判断改为问策略 ④ 前后端死代码清理——`web/src/graph/layoutWorker.ts`、`web/src/features/search/SearchResults.tsx` 及未用的 `getSchemas`/`SchemaDto` 删除 ⑤ **字段血缘 UI 入口**——`TableDetailDto` 带出列级上下游（一跳），表详情每列可展开看血缘，空态「该列暂无血缘记录」；遍历逻辑从 `SchemaActionCommand` 提到共享层，CLI 改为调用它而不是各写一份 ⑥ 搜索评估集与回归下限测试——`SearchEvalRegressionTest` 用 32 条真实评估集钉住 Top1/Top5/MRR 下限（实测 0.906，阈值 0.85），并断言实时引擎与索引引擎 Top1 命中集合一致——这条就是 ② 权重合并的可观测证明 ⑦ 文档：分发指南的构建脚本路径与 `driverRef` 字段名、`alias list` 不存在的错误用例、手册补 `search-eval`/`--boost`/`add-lineage`/`task list`/候选批量发布 ⑧ **顺手修掉一个 fail-open 隐患**：`requireReadonlyGuard` 参数化之后是 Lombok `@Builder` 上的原始 `boolean`，漏写默认 `false`——改动前只读守卫无条件生效，改完变成「新方言忘写一行 → readonly 别名能写库」。加 `@Builder.Default = true` 改回 fail-closed。另两个开关默认 `false` 方向本来就是安全的（默认继续拒绝）。**教训：把无条件的安全校验参数化时，默认值必须落在更严格的一侧** |

| 08-26 | **运行库收口两项**：①回滚脚本迁移收尾——老记录只有 `file_path` 指向 `~/.sql-cli/recovery/*.sql`，schema v7 一次性把文件内容回填进 `rollback_sql`/`backup_text`（文件没了标 `missing`），然后删掉兼容读分支、`RecoveryExecutor.readLegacyFile`、`GraphUiApiRouter.section`、`recordRecoveryArtifact(id,path)`，并 DROP 掉 `file_path`/`checksum` 两列。**动机是那两列真的误导过人**：新行往 NOT NULL 的 `file_path` 里写空串，打开库看见就以为脚本还在文件里 ②规则评估 `policy/runs/` → SQLite——新增 `policy_evaluation`/`policy_violation`，首次读取时按别名一次性导入（老目录保留，`policy/.runs-imported` 做标记）。实测 3 轮评估 2MB、50558 行违规，而 `listEvaluations` 每次全目录扫，和当初执行历史 JSONL 同一个病。顺手修了一个自己引入的隔离缺陷：`PolicyServiceTest`/`SchemaPolicyCommandTest` 没设 `sqlcli.home`，PolicyStore 接上运行库后会往开发机真实的 `~/.sql-cli` 里写评估记录 |

## 几个别再翻案的决策

- **图谱不做多版本管理**，generation 只是原子落盘产物，历史看 `graph_change_log`
- **审批是阻塞闸门不是任务队列**：等待方握着语句在内存里，所以不需要 `sql_hash` 防篡改
- **值域是事实不是规则**：事实存 `ColumnWorkspaceNode`（Agent 维护，跟 revision 走），
  `status_field_dictionary` 是检查它在不在的规则（人维护，不进 revision）
- **字典引用是关系不是属性**：两端都是图谱对象，且字符串装不下 `dict_type` 过滤条件
- **关系类型只有三个**：`foreign_key` / `join_observed` / `term_mapping`，
  语义靠 `--join` 表达不靠类型名
- **SQL 执行只有一个入口**：`SqlTaskModule`。Stage 装能独立摘出的横切关注点，
  拆不开的（写事务边界、policy 门控 + 路由）留在 Backend 内部，不同传输通道是 Backend
  实现而不是链上的 if——加一个只读后端不该需要改 `GuardStage`
- **不做 AppContext**：`AliasResolver` 必须每次新建（缓存会重新引入「改完别名连不上库」），
  `SecretResolver` 无状态，`GraphWorkspaceStore` 本就每进程一次。详见设计文档 §16
- **运行记录进运行库，人编辑的配置留文件**：这是 `~/.sql-cli` 里所有东西的唯一分界。
  执行/任务/审批/图谱变更/**规则评估**都是运行记录（有起止时间、状态、actor，要按条件查、要能清理）；
  别名与驱动配置、规则集与豁免、图谱本体、搜索索引留在文件里（分别因为人要编辑、
  要能 export/import、随时可 rebuild）。**导入任务 `jobs/` 是有意的例外**——
  它跟图谱同目录，拷走图谱时 resume 断点跟着走，这个好处大于「运行态放一起」的一致性
- **把无条件的安全校验参数化时，默认值必须落在更严格的一侧**：`requireReadonlyGuard`
  接成 policy 开关之后，Lombok `@Builder` 的原始 `boolean` 默认 `false`，
  等于「新方言忘写一行 → readonly 别名能写库」。加 `@Builder.Default = true` 才对
- **metric 是图谱工作区里的第四个集合**：「存图谱里 vs 独立一层」是假对立——
  `terms` 和 `lineage` 早就同时拿到了两边好处。**存哪里**（图谱工作区，白拿 revision /
  候选 / 评审 / 审计 / export 载体）和**长什么形状**（不是 `RelationWorkspaceEdge`，
  是独立 Map）是正交的两个问题。**别再把 metric 塞进 `RelationType`**
- **metric 的 `joinPath` 是人工权威声明，不是自动发现的消费者**：口径要扯皮的东西，
  JOIN 路径必须是权威声明而不是从日志里猜的（LookML 的 explore、Cube 的 join 都是人手写的）。
  方向反了会得出「metric 要等执行反哺挖出 join 才能做」的错误排序——
  实际是 metric 声明的 join 质量最高，该反哺给图谱
- **字段血缘不是关系类型**：它是 n 元推导、要带产生上下文、规模比关系大两个数量级，
  该做成和 `terms` 平级的第三个集合（详见 P3）。**别再把 `lineage_to` 加回 `RelationType`**
- **规则分组固定两个**：结构规则和 SQL 类规则在评估器里本来就不能混
- **回滚脚本存运行库不存文件**：一份数据两个存储位置、还要靠 `-- Recovery SQL:` 注释
  切段落，比在 `recovery_artifact` 多两列贵得多。**「先入库再执行」的顺序不能反**——
  反了就存在「已改完且无从回滚」的窗口。老的 `~/.sql-cli/recovery/*.sql` 只读兼容
- **执行记录属于评审页**：审批看「要不要放行」，执行记录看「放行之后发生了什么」，
  同一件事的两半。工作台不再展示它（入口唯一）
- **写语句在 `sql_execution` 里也存原文**：它的原始行和回滚脚本就在隔壁表，
  单把 SQL 打码不增加任何安全性，只让人看不出这条历史改的是哪一行。
  列表仍显示 `masked_sql`，原文只在展开详情时露出
- **列表筛选一律在服务端**：分页之后「当前页里符合条件的那几条」和「符合条件的第 N 页」
  是两回事。count 和 list 必须共用同一份 WHERE（`RunStateStore.Filter`），否则总页数是假的
- **审批不该把 Agent 钉在那儿等**：`ApprovalGate` 仍是阻塞闸门（简单、可靠），
  但调用方应该把命令丢后台，用 `task status <id>` 回查。**别为此改成任务队列**——
  队列要解决的是「谁来执行」，而后台进程本来就在那儿等着执行
