---
name: sql-cli
description: "Query real database data, inspect table structure (DDL, table lists, columns), and maintain the schema knowledge graph — including writing back table/column meaning, business terms, value domains, join relations and column lineage found while reading application code (entities, Mapper XML, .sql files, view definitions, ETL). Also defines BI metrics (add-metric/expand-metric) for the rare case of a real 口径 dispute — not a routine step. Use it whenever a request needs table/column discovery, join selection, business-term interpretation, or a real query: search the schema graph first to ground the work before touching the database. Always call the global `sql-cli` command directly. Triggers include: sql-cli, 执行SQL, 查询数据, 查询数据库, 查库, DDL, 表结构, 表名, 字段含义, 值域, 枚举值, 数据库别名, schema graph, 知识图谱, 图谱, 补充图谱, 表注释, 业务术语, 业务描述, 语义类型, 血缘, lineage, 字段血缘, 指标口径, GMV 怎么算, metric, schema search, database alias, SQL execution, 列出表. Not for SQL syntax teaching, query tuning advice, or SQL that will not be executed."
---

# sql-cli

Agent 用 `sql-cli` 做两件事：**查数据**、**维护图谱**。指标（metric）不是第三件常规事——
只在真的撞见口径争议（同一个业务问题被算出两个数，必须统一）时才用得上，见下表。

别名管理、驱动管理、密码管理、Web UI 属于人工运维，不要代替用户执行。
结构规则由人在规则页启用；你**不用**在建表前先跑评审——CREATE / ALTER / DROP 经过 `sql-cli`
执行时自动按已启用的规则检查，被拒时错误里带着修法，照改再执行即可。你能做的是**提议**规则
（`schema policy rule add`，见 [graph-write.md](references/graph-write.md#规则从存量表归纳出来提议给人)）。

## 参数一律查 `--help`，不要凭记忆

```bash
sql-cli <alias> schema --help              # 所有 action
sql-cli <alias> schema <action> --help     # 某个 action 的完整参数、取值和示例
```

**每个 action 的 `--help` 里已经写了「为什么这么设计」**——为什么 `--semantic-type` 是固定枚举、
值域该从哪来、为什么 `joinPath` 必须人工声明。本技能不复制这些语法，只讲**判断**：
什么时候做、做到什么程度、什么不该做。语法以 `--help` 为准，冲突时相信 `--help`。

## 开始之前

`sql-cli list` 列出可用别名。**多个别名时问用户用哪个，不要挑一个就开始查**——连错库比查不到更糟。

输出里带 `[RO]` 的别名是只读的，`INSERT/UPDATE/DELETE/DDL` 会被直接拒绝。看到 `[RO]` 就别提议写操作，
改为把 SQL 交给用户自己执行。

连接失败、别名不存在、密码错误时直接把错误报给用户——别名、驱动和密码是用户的运维范围，
不要代为修改配置或重设密码。

## 开工先报会话

```bash
sql-cli session begin --agent claude-code     # 一次就够，之后每条命令自动带上
sql-cli session end                           # 收尾
```

会话 id 按**工作目录**落盘，此后这个目录下每条执行记录和每条审批都带着它和 agent 名字。
不报的话这些记录只剩「某个 agent 干的」，人事后追不到「这一串改动是为了什么」。

`--session <id>` 可以逐条覆盖。**环境变量不行**——每个 Bash 调用都是新 shell，
`export` 不跨调用。

## 三个立刻会踩的坑

**stderr 上的 `Picked up JAVA_TOOL_OPTIONS: ...` 不是错误。** 在 PowerShell 里它会被包装成
`NativeCommandError` 的红字。判断成败看退出码和 stdout，不要因为这行就重试或换命令。

**一次只能执行一条语句。** 分号拼接会报 `Multiple statements are not supported`，分多次调用。

**一条审批管线，两个结尾。** 分支判据只有一个：这次要不要等人。

| 什么被审批 | 命令的表现 | 你该做什么 |
|---|---|---|
| **SQL 执行**（别名开了查询 / 更新审批） | **挂住**等人，stderr 打印 `[审批] …（审批 #12 / 任务 #34）`，最多 10 分钟 | 立刻把审批号报给用户去 Web UI 评审页批准，否则那 10 分钟白等；然后放后台，`sql-cli task status 34` 查结果。多条 SQL 属于同一个需求时不要一条条挂，开 SQL 批次（见下） |
| **图谱写入**（别名的 `graphApproval: manual`，或你写出的是候选边） | **立刻返回，退出码 0**，stdout 打印 `待审批（审批 #42）` 或 `待发布（审批 #42）` | **退出码 0 不等于生效**：`待审批` = 图谱一个字节没动，`待发布` = 已作为候选写入但还不算正式。原样转达这句，别接着说「已补充完成」 |

别名的 `graphApproval` 是 `auto` 时图谱写入当场生效，没有额外输出——**留底照样有**，
只是不用等人。哪个命令要不要审批不由命令自己决定，由别名统一决定。

**不要重试、不要换命令、不要 Ctrl+C 重来**——重试只会再排一条待审批。
审批号和任务号在评审页卡片上直接显示，用户对得上号。
详见 [references/troubleshooting.md](references/troubleshooting.md)。

**收尾时报一次队列**：`sql-cli <alias> approval`（只读，列出待审批和每条已等多久）。
排进队列的东西没人裁决就等于没写——实测 6 条待审批挂了一整天，同期图谱一个字节没长，
而用户根本不知道有这回事。有待审批就把**条数和审批号**说给用户并请他去评审页裁决，
别只说一句「已提交」。裁决不在 CLI 上做，这条命令也没有批准 / 拒绝动作。

## 批次：多条改动作为一个业务意图

判据只有一条：**这些改动是不是同一个业务需求。** 一条查询、一次 `schema edit` 不开批次；
「读一遍代码补一个业务域的 40 处字段」「一个需求要改的建表 + 初始化 + 回填」才开一个。
批次是可选分组，不是强制包装——为一条改动开批次只是多了两步。

批次分两种，`begin` 时用 `--kind` 定，**一批只装一类**，混装直接拒绝：

| kind | 装什么 | 怎么进批次 | 落地方式 |
|---|---|---|---|
| `graph`（默认） | `schema edit` / `add-*` / `value-domain` 等图谱变更 | 原命令加 `--batch <id>` | 一次写入，要么全进要么图谱一个字节没动 |
| `sql` | INSERT / UPDATE / DELETE / DDL（SELECT 也收） | `sql-cli <alias> "SQL" --batch <id>`，一条一次 | 单连接单事务按 seq 顺序跑，任一条失败整批回滚 |

```bash
# 图谱批次
sql-cli demo batch begin --intent "梳理报事域状态字段"        # 返回批次号，比如 77
sql-cli demo schema edit --column ... --batch 77              # 只入批次，图谱不动
sql-cli demo schema value-domain --table ... --batch 77
sql-cli demo batch submit 77                                  # 封口，整批进待审批

# SQL 批次
sql-cli demo batch begin --kind sql --intent "新增报事回访表并回填历史数据"   # 比如 78
sql-cli demo "CREATE TABLE report_visit (...)" --batch 78     # 未执行，只是草稿条目
sql-cli demo "INSERT INTO report_visit SELECT ..." --batch 78
sql-cli demo batch submit 78

sql-cli demo batch status 78      # 裁决结果：哪条被否、理由
sql-cli demo batch discard 78     # 丢掉还没 submit 的草稿，图谱和库从头到尾没被碰
```

三件必须记住的：

- **`--batch` 下的命令不落地、不执行，退出码 0 也一样。** stdout 会说
  `已加入批次 #78（条目 #101，seq 1）。未执行——…`。`submit` 之前它既不在图谱 / 数据库里，
  也不在待审批队列里，人在评审页看不到。**忘了 `submit` = 这批工作等于没做。**
- **`--intent` 必填，而且要具体。** 人在待审批队列里裁决的就是那句话。「维护图谱」的 60 条批次
  等于没有 intent，只能盲批——**盲批比不审批更糟，它制造假的问责记录。**
  整个会话的改动塞进一个批次是最常见的错法：不相干的改动分成两批，或各自单条。
- **SQL 批次里的写语句仍然要先取得用户授权**（铁律 2），进批次不是绕过。含 DDL 的批次在
  `submit` 时会被标成**不可回滚**——DDL 隐式提交，rollback 对它无效——提交前把这一点告诉用户。

`submit` 之后整批进待审批，走上面那条管线。裁决完用 `batch status` 取结果：
条目可以被单独否掉，理由跟着回来，**按理由改完开一个新批次只重提被否的那几条**，
不要整批重来——已批准的已经落地了。状态含义和 `failed` 的处理见
[references/troubleshooting.md](references/troubleshooting.md#三被否的条目看理由再改不要原样重提)。

## 主流程

**图谱是表结构和业务语义的事实来源。** 用户没给出已确认的表名、字段名和关联条件时，
先查图谱，不要从命名猜表和 join。

```
确认连接与图谱可用  →  搜图谱定位表/字段  →  确认字段语义与关系  →  跑验证查询  →  执行  →  写回新知识
      test/stats          search              describe/path            LIMIT     edit/add-*/value-domain
```

最后一步最容易被跳过。**一次查询成功之后，是这次会话里知识最完整的时刻**——表、字段、JOIN、
值域刚被真实验证过。那一刻不写回，之后就永远不会写。

## 按任务找细则

| 你要做的 | 读这份 |
|---|---|
| 查数据：定位表、选 JOIN、控制上下文、报错自愈 | [references/query.md](references/query.md) |
| 写回业务语义：四个描述字段怎么分、术语建不建、值域、置信度、候选机制 | [references/graph-write.md](references/graph-write.md) |
| 自检图谱质量、拿一份带修复命令的工作队列（`schema eval`） | [references/graph-write.md](references/graph-write.md#schema-eval-给你一份工作队列) |
| **重构前**判断：这个字段能删吗 / 这个枚举值还有人写吗 / 这张表还活着吗（`schema data-quality`） | [references/graph-write.md](references/graph-write.md#重构前先跑-data-quality) |
| **改一个字段前**看下游影响：谁抄了它、谁按它算、谁按它决定写不写，改动在哪个方法（`describe --column`，血缘四类） | [references/code-scan.md](references/code-scan.md#字段血缘) |
| 读代码提取关系 / 语义 / 血缘 | [references/code-scan.md](references/code-scan.md) |
| 从执行记录回溯真实用过的 JOIN 和写法 | [references/execution-mine.md](references/execution-mine.md) |
| 出现口径争议，需要定义/展开一条指标（不是常规步骤） | [references/metric.md](references/metric.md) |
| 验证图谱工具链是否可用（也用于验收新装环境） | [references/selfcheck.md](references/selfcheck.md) |
| 报错、阻塞、SM4 加密列、其他异常 | [references/troubleshooting.md](references/troubleshooting.md) |

## 铁律

1. **只通过 `schema` 命令改图谱**，不手改工作区文件。
2. **写操作、DDL、全量导入、`--force-overwrite`、`import reset` 执行前必须取得用户明确授权。**
   这些会改数据库或重写工作区。
3. **编辑或导入之后跑一次 `schema index rebuild`**，否则 `search` 搜不到。不是每次搜索前都跑。
4. **少而准优于多而滥。** 只写回这次真正用到并验证过的知识；写不进去说明证据不足，
   不要降 confidence 硬塞。
5. **不确定就说不确定。** 找不到关系时说明不确定性，不要用命名相似度硬编一个 JOIN。
6. **读代码时发现图谱与代码不一致，必须当场改图谱**——代码是实际在跑的那份。
   不改的代价不是「图谱旧了」，是下次按错的语义写出一条**不报错的错 SQL**：值域少一个取值，
   `WHERE status IN (...)` 就少算一批数据，照跑、有结果、数是错的。
   **只在回答里提一句不算数**——说了不写，下一个 agent 再犯一次。
   见 [references/code-scan.md](references/code-scan.md)。
