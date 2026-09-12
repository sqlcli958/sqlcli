# 审批管线统一与批量提交

> 状态：**已实现（2026-08-30）**。拍板过程与被否决的选项记在
> [开发清单 2026-09](dev-checklist-2026-09.zh-CN.md) 的 A1~A6 一节，本文是落地方案。
>
> 实现与本文的三处偏差，都在讨论后定下：
> 1. **`graphApproval` 没配时是 `auto`**，不是文中写的默认 `manual`。现有别名一律「没配」
>    （`approveGraph: false` 从来不会被写进 aliases.yaml），默认 manual 会让所有 agent 的
>    图谱写入一夜之间全停在队列里。要人工审批显式写 `manual`。
> 2. **单条 SQL 仍然阻塞等审批**（`ApprovalGate.awaitApproval`），只有**批次**走异步落地。
>    单条改成异步会破坏 `sql-cli <alias> "UPDATE ..."` 当场返回结果的契约，而
>    「多条语句改一半」是批次才有的问题。
> 3. **冲突判据多一条**：当前值已经等于 `after` 时同样放行。列编辑每次都无条件写
>    `verified` / `confidence`，少了这一条，任意两条针对同一列的变更都会互判冲突。

## 范围

把 `query` / `update` / `graph` / `recovery` 四类各自为政的审批接法收成一条管线，
并让多条变更能作为**一个业务意图**一次提交、一次裁决、原子落地。

## 两条总规则

**规则一：一条管线，两个结尾。**

```
提交（条目，可选归入批次）
  → 决策（自动批准 / 人工批准）
    → 落地 ┬─ 同步：当场跑完返回结果      （读、自动批准的写）
           └─ 异步：人批准的那一刻才跑    （人工批准的写）
```

分支判据只有一个：**这批要不要等人**。不存在第三个结尾。

**规则二：一个别名上只能有一种行为。**

任何写图谱或写数据库的路径**不许自己决定要不要审批**——包括 `schema value-domain`。
风险高低表达成条目上的标记，由别名策略统一裁决，不由命令自己开小灶。

---

## 数据模型

### 条目是必需单位，批次是可选分组

| 动作 | 条目数 | 批次 |
|---|---|---|
| 一条查询 | 1（仅当别名开了查询审批） | 无 |
| 一条 `schema edit` | 1 | 无 |
| 梳理一个业务域（40 处改动） | 40 | 1 |
| 一个 SQL 脚本（12 条语句） | 12 | 1 |
| `schema import --schema X` | 1 | 无 |
| 提交回滚 | 1（payload 是整份脚本） | 无 |

**条目粒度 = 一次可裁决的动作，不是一个对象。** 一次导入是 1 条，不是一万条。

### 表结构

`approval_request`（已存在，加四列）

| 列 | 说明 |
|---|---|
| `batch_id` | 可空。归入哪一批 |
| `seq` | 批次内序号，SQL 批次靠它定执行顺序 |
| `session_id` | 哪次会话 |
| `agent_id` | 哪个 agent |

`approval_batch`（新表，只在有多条时建）

| 列 | 说明 |
|---|---|
| `id` | 批次号 |
| `alias` | 数据源 |
| `kind` | `graph` \| `sql` |
| `intent` | 业务需求，一句话，**必填**。人在队列里看的就是它 |
| `status` | `draft` \| `pending` \| `approved` \| `partial` \| `rejected` \| `applied` \| `failed` |
| `submitted_at` / `decided_at` / `decided_by` / `reason` | |

溯源字段挂在条目上，批次不重复存（一批内的条目同属一次会话）。

`sql_execution`（已存在，加两列）：`session_id`、`agent_id`。

---

## 行为规格

### 图谱写入

别名配置项 `graphApproval: auto | manual`，默认 `manual`。

| 值 | 行为 |
|---|---|
| `manual` | 条目进队列，等人在评审页裁决，批准才写图谱 |
| `auto` | 条目建好后系统立刻批准并落地 |

两者留底完全一致：intent、agent、会话、提交与批准时间、批次号一个不少。
**`auto` 的条目不进待审批队列**，直接落到审批记录。

不走审批的两类保持现状：`GraphActor.system` 的记账动作（建索引、校验、回写行数）静默放行；
整份快照导入（`commitLocked`、`schema import --input`）明确报错说明原因。

### SQL 写入

条目进队列或自动批准，规则同上，由别名的查询 / 更新审批开关决定。

### 读查询

- 别名**开了**查询审批：建条目，走与写入完全相同的提交 → 决策 → 落地
- 别名**没开**：直接执行，不建条目。溯源落在 `sql_execution` 的新增两列上

**不为自动放行的查询建条目**：无人裁决的 `status=approved` 是假记录，且 SELECT 高频会淹掉审批记录。

### 落地与事务

| 类型 | 保证 | 实现 |
|---|---|---|
| 图谱批次 | **原子**。要么全进，要么图谱一个字节没动 | 拿写锁 → load → 逐条 apply → 一次 save。复用 `commitLocked` 路径 |
| SQL 批次（DML） | 单连接单事务，按 `seq` 执行，任一条失败整批 rollback | 回滚脚本按批生成一份，语句逆序 |
| SQL 批次（DDL） | **不保证可回滚** | 允许进批次，提交时强制标注「本批不可回滚」。MySQL DDL 隐式提交，rollback 无效，不得标注为可回滚 |

**先落地、再改状态。** 批次全成才标 `approved` + `applied`；任一条失败整批标 `failed`
并记录失败条目与原因。不允许出现「已批准但什么也没发生」的记录。

**记录写入的降级规则**：读操作建记录失败时照常执行，仅记日志；
写操作不允许降级——回滚脚本先入库再执行是安全性本身。

### 冲突检测

**字段级判据**：从条目的 `before` / `after` 计算本次实际改动的字段集合，
只校验这些字段的当前值等于 `before`，落地时也只写回这些字段。

批次内逐条检测；**任一条真冲突则整批不落**，错误信息列出冲突条目。

### 批准粒度

批次是审批单位，条目是裁决单位。

- 批准 = 落地这一批中所有未被单独否掉的条目
- 有条目被否时批次状态记 `partial`
- 被否条目携带理由，供提交方通过 `batch status` 取回

---

## 会话身份

优先级（高到低）：

1. `--session <id>` 命令行参数
2. 会话状态文件（`sql-cli session begin` 写入，按**工作目录**隔离）
3. `SQLCLI_SESSION` 环境变量（仅面向 CI）
4. 都没有 → 记 `null`

**取不到就记 `null`，不得生成占位 id。**

---

## CLI 接口

```bash
sql-cli session begin                                     # 生成并落盘会话 id
sql-cli session end
sql-cli <alias> "SELECT ..."                              # 自动携带会话 id

sql-cli <alias> batch begin --intent "梳理报事域状态字段"    # 返回批次号
sql-cli <alias> schema edit --column ... --batch 77        # 只入批次，不落图谱
sql-cli <alias> schema value-domain ... --batch 77
sql-cli <alias> batch submit 77                           # 封口，进待审批
sql-cli <alias> batch status 77                           # 裁决结果：哪条被否、理由
sql-cli <alias> batch list --draft                        # 列出未提交的批次
```

不带 `--batch` 的写命令 = 一条独立条目，按别名策略自动批准或排队。
`draft` 批次未 `submit` 即未提交，不落地。

---

## UI

评审页 · 待审批按批次渲染：

- 一张卡 = 一批。标题 `intent`，副行 agent / 会话 / 批次号 / 提交时间
- 展开为条目清单：图谱条目带 before/after，SQL 条目带语句
- 每条一个「否掉这条」，卡片底部「批准这一批」
- 无批次的单条仍单条显示

**不新增一级菜单。** 自动批准的条目不出现在待审批队列。

---

## 技能改动（与代码同批交付）

`skills/sql-cli/`：

| 文件 | 改什么 |
|---|---|
| `graph-write.md` | 新增「什么时候开批次」：判据是这些改动是否属于**同一个业务意图**。反例必须写明：不要把整个会话的所有改动塞进一个批次 |
| `SKILL.md` | 会话开始先 `session begin`；`draft` 未 submit 等于未写入 |
| `troubleshooting.md` | 重写「审批：两类，行为相反」一节为「一条管线两个结尾」；补充被否条目携带理由，需按理由修改后重提，不得原样重提 |

判据表：

| 该开批次 | 不该开 |
|---|---|
| 读一遍代码，补一个业务域的 N 个字段和关系 | 会话中顺手补的两处不相干改动——分成两批或各自单条 |
| 一个需求要改的多条 SQL（建表 + 初始化 + 回填） | 一条查询 |
| 按一张表的语义做整表补全 | 一次 `schema import` |

---

## 迁移

| 对象 | 处理 |
|---|---|
| `approval_request` | 加四列，历史行 `batch_id` 为 `null`，即「无批次的单条」，天然兼容 |
| `approval_batch` | 新表，无历史数据 |
| `sql_execution` | 加两列，历史行为 `null` |
| `approveGraph`（布尔） | 改为 `graphApproval: auto \| manual`。`true → manual`，`false → auto` |

---

## 明确不做

- 跨别名批次
- 批次嵌套
- 草稿批次超时自动提交
- 同目录并发 agent 的会话隔离（用 `--session` 覆盖）
- 把执行结果存进批次表（结果留在 `sql_execution`，条目仅存 `execution_id`）
