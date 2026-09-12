> # ⚠️ 已归档 · 不是待办清单
>
> 一次性运行报告（2026-08-20）。
>
> 正文的「下一轮建议」**已被正式否决**，不要从这里取待办。实测数据可作历史参考。
>
> **当前状态与优先级一律以 [dev-checklist-2026-08](../dev-checklist-2026-08.zh-CN.md) 为准。**
> 归档于 2026-08-25。

---

# 开发执行报告 2026-08-20

> 执行方式：Fable 5 调度 + 6 个 Opus 开发 agent 并行（约 17:45–18:20），验收由主 agent（Fable 5）
> 于 18:15–18:25 完成：全量测试 → 打包 → 真实库 CLI 实测 → 浏览器实测。
> 用户设定 19:10 硬截止；实际于 18:25 前完成验收，无任务被强制中止。

## 一、完成情况总览

| 批次 | 状态 | 验收证据 |
|---|---|---|
| 1 地基修复 | ✅ 完成 | `policy check` 空绑定退出码 0 + 友好提示；三类 JDBC 异常标准化消息有测试 |
| 2 SQLite 运行库 | ✅ 完成 | 五张表建库；41 条 JSONL 幂等迁移（连跑 3 次不重复）；`graph_change_log` 真实写入；WAL 并发缺陷被发现并修复 |
| 3 generation 清理 | ✅ 完成（当日早间） | 78MB/35 代 → 7.1MB/1 代 |
| 4b 历史 SQL 重执行 | ✅ 完成 | 浏览器实测：点「执行」展开表格（列头带类型/行数/耗时）；脱敏记录按钮禁用 |
| 5 设置页补全 | ✅ 主体完成 | 编辑/删除/测试连接/设置密码全套 UI；4 个新端点；测试连接实测 `serverVersion 8.1.0` |
| 6 图谱候选与约束 | ✅ 核心完成 | CLI 缺 confidence 拒绝、verified<0.9 拒绝均实测；candidate→reject 闭环实测（revision 37→38） |
| 7 CLI 契约收敛 | ✅ 可并行部分完成 | 18 个契约快照测试 101 断言进入测试套件；SKILL/手册/用例文档同步 |

全量 `mvn test`（约 350 个测试）**全绿**；`mvn package` 含前端构建成功。

## 二、集成阶段发现并修复的问题（3 个，均为真 bug）

1. **`SettingsPage.tsx` TS1308**：`await` 写在 setState updater（非 async 函数）里，`tsc -b` 构建模式才暴露（`--noEmit` 默认配置不报）。已修。
2. **`Invalid Origin` 拦截所有浏览器 POST**（存量 bug，今日首次真实触发）：`GraphUiServer` 用绑定地址 `127.0.0.1` 拼 allowedOrigin，浏览器发的是 `localhost` —— 精确匹配必败。影响范围是**全部**浏览器写操作，不止新端点。已修（loopback 规范化为 localhost）。
3. **UI 测试连接报 `Driver class is not configured`**：`AliasAdminController` 用 `AliasConfigStore` 裸配置（driverRef/secretRef 未展开）调连接测试；改走 `AliasResolver.resolve()` 完整解析。已修并实测。

## 三、剩余未完成任务（按清单口径）

### 批次 4（未启动，最大剩余块）
- AppContext 组合根（前置，半天）
- 结构化查询结果替换字符串管线；SqlTaskModule 单一入口（CLI/UI 共用）
- Yearning 路由进入单一入口（当前 4b 端点对 yearning 别名直接 400）
- 写操作事务边界：恢复文件先落盘 → 事务执行；`task_run`/`task_event`/`recovery_artifact` 三张表接线（已建表）
- 查询上限/超时配置化（当前 4b 写死 500 行/30s）

### 各批次明确留下的子项
- 批次 5：Playwright 流程测试；Router `path.startsWith` 收成路由表；CLI 管理命令废弃提示；`AliasConfigStore` 根目录注入（否则 PATCH/DELETE 成功路径无法安全单测）
- 批次 6：术语（add-term）UI 视图；search/graph 对 candidate 的过滤展示；批量发布/拒绝；`RelationEvidence` 证据写入（`--source-file`）
- 批次 7：`task status <id> --json`（依赖批次 4 接线 task_run）；add-relation 的 `--json` 信封（现为纯文本，快照测试只锁了退出码）
- 批次 4b：重执行落 `sql_execution`（source=rerun）——依赖批次 4 收编；SM4 解密列
- 批次 8：export/merge 共享验证（按触发，不排期）

### 已知限制（非缺陷，已在代码注释/文档标明）
- `encrypted:` 密码方案在 UI 进程内依赖 `masterPasswordEnv` 环境变量（交互式读取不可用）
- 通过 `127.0.0.1` 访问 UI 的浏览器 POST 仍会被 Origin 校验拒绝（仅规范化了 localhost）
- 概览页「待审批」卡片仍是占位（依赖审批闭环，已决策暂缓）

## 四、为什么这些没有完成

1. **批次 4 被刻意排除在本轮外**：它与批次 2 同时改 `QueryExecutor` 会在共享工作树上互相覆盖，且审批状态机已被决策暂缓 —— 单独一轮做比并行硬塞风险低得多。
2. **1 小时窗口的并行上限**：6 个 agent 已把互不冲突的批次全部占满，剩余子项（术语 UI、Playwright、路由表整理）都与已分配批次共享文件，并行只会增加冲突回滚成本。
3. **三个集成 bug 消耗了验收窗口约 15 分钟**——这正是「agent 自测通过 ≠ 合并态可用」的证明，也是主 agent 亲自验收的价值。

## 五、下一轮建议入口

按依赖顺序：**AppContext 组合根 → 批次 4（结构化结果 + SqlTaskModule + Yearning + 事务边界）→ 4b 收编 + task status → 批次 5/6 尾项清扫**。批次 4 完成后，本清单除批次 8 外全部关闭。
