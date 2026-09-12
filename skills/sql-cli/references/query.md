# 查数据

## 流程

1. `sql-cli <alias> test` 确认连接；`schema stats` 和 `schema index status` 确认图谱可用。
2. 图谱为空或明显落后时，**先征得用户同意再导入**，优先最小范围：
   `schema import --from-db --schema <schema>` 或 `--table <schema.table>`；导入后 `schema index rebuild`。
3. **用业务词和同义词搜**：`schema search "关键词" --json`。无结果再回退 `tables --pattern "%kw%"`。
   - 中文按二元切词，多个词可跨字段命中（「买家 手机」能命中业务名「买家手机号」）
   - 结果默认截断（JSON 50 条 / 文本 15 组，`--limit N` 调整，0 不限）
   - **命中太多说明关键词太宽，优先加词缩小范围，而不是调大 limit**
4. 候选表用 `schema describe <schema.table> --json` 确认字段语义、主键、敏感标签；
   仍不清楚再 `ddl <table> --schema <schema>`。
5. 多表时 `schema query <schema.table> --depth 1 --json`；两表已知时 `schema path <from> <to> --json`。
   **只用已确认的关系生成 join**；找不到关系要说明不确定性。
6. 先跑带明确 `WHERE` 和 `LIMIT` 的只读验证查询，再执行目标 SQL。
   默认不要 `SELECT *`，回报用了哪些表、关联和过滤条件。

## 搜索结果里的标记要看

`--json` 会带出 `matchedField` / `matchedText`（命中在哪个字段、命中的原文）、
`comment` / `description` / `businessName` / `semanticType` / `candidate`。

- **`comment` 和 `description` 不是一回事**：前者是数据库注释（只由导入写），
  后者是人或 Agent 补的业务解读。两者矛盾时说明库注释过时了，值得向用户指出——
  但修的办法是去库里改再重新导入，见 [graph-write.md](graph-write.md)。
- `candidate: true`（文本输出是 `[候选]`）表示还没人发布。**搜得到不等于已确认**，
  基于候选关系生成 SQL 时要说明。

## 命中场景时，表和 join 已经给你了

搜索命中一条**带入口表**的术语时，它不再是一条和表并列的结果，而是一个场景块：

```
  ⌘ 场景「报事」 — 住户提交的一次维修请求
    同义词: 工单、报修 · 3 张表
    过滤: state IN (0,1,6)  AND  subject_id = :subjectId
    ★ app.erp_prop_report
      app.erp_prop_report_assign   INNER JOIN（推断）  ON app.report.id = app.assign.report_id
      app.erp_prop_report_score    [桥接]  ON ...
```

- **`★` 是入口表**，`FROM` 从它开始
- **`ON` 条件照抄整条**，规矩和 `schema path` 一样：复合外键漏掉租户列不报错、数是错的
- **`[桥接]` 的表术语并没有映射它**，是为了把子图连起来才补进来的
- **`过滤` 那行必须带进 `WHERE`**。含 `:name` 的是必填参数，值要向用户要——
  漏掉项目隔离列是**查到别的项目的数据**，不是多查几行
- **`[连不上入口表]`** 说明那张表和入口之间还没有关系，别硬编一个 join 把它接上

场景块列出的表**不会再单独出现一次**，所以不要因为在下面没看到它就以为漏了。

**`--json` 里对应字段是 `origin`**：`"term"` 是场景术语本身（上下文，不是结果）、
`"term_expansion"` 是被术语带出来的对象（`expandedByTermId` 给出是哪条术语），
`"keyword"` 是纯关键词命中。**两者可信度差一个量级**——`term_expansion` 是术语
明确指过去的，`keyword` 只是名字碰巧像。生成 SQL 时优先信 `term_expansion` 的表。

## `describe` 会给你什么

### 先看第一行：这张表能不能直接信

**图谱是按需生长的**——只有被人干过的那些表有业务语义，没有描述<b>不等于</b>这张表简单。
所以 `describe` 顶部那行 `语义:` 结尾的那句话是你要先读的：

```text
语义: 描述 11/46 · 值域 4/46 · 业务名 13/46 · 关系 14（基数未知 1） · 有人整理过（最近变更 2026-08-31）
语义: 描述 0/30 · 值域 0/30 · 业务名 0/30 · 关系 0 · 从未有人补充——只有库注释，直接用之前先读代码
```

| 看到 | 该做什么 |
|---|---|
| **有人整理过** | 图谱可以当依据用。前面几个计数告诉你哪一类还缺（值域缺就别猜枚举值） |
| **从未有人补充** | **先去代码里确认再动手**。图谱这时只是库注释的镜像，字段名和注释都可能是十年前拍的 |

`--json` 里对应 `completeness.curated`（布尔）。**不要拿覆盖率百分比当判据**——
一张 5 列的字典表 0 条描述很正常，一张 46 列的业务主表 0 条描述说明没人碰过。

### `Grain` 和关系基数：决定 join 之后还能不能直接 SUM

- **`Grain`** —— 一行代表什么（「一行一条报事」）。没有这行说明没人声明过粒度
- **每条关系带基数** —— `N:1` / `1:N` / `N:1(推断)` / `基数未知`。
  带 **`(推断)`** 的是从主键、唯一约束推出来的，不是人确认的；
  **`基数未知` 就是真的不知道，不要默认按 N:1 处理**
- **`注: 本表处在 N 条一对多关系的「一」侧…`** —— 扇形陷阱警告。
  同时 join 其中两条，本表的行会被乘开，`SUM` 出来的数偏大，
  **SQL 照跑、有结果、不报错**。要么分别汇总再拼，要么先对主键去重

### 单列追问：`describe <table> --column <列>`

紧凑列表为了让几十列排得下，砍掉了格式、示例值、默认值、**置信度**和**有没有人复核过**。
决定要用某一列的语义之前，用 `--column` 把它展开——
`人工确认: 否` 说明这条语义没人复核过，`注: 这一列只有库注释` 说明它根本没有业务语义。

**不要对整表逐列追问。** 一张表的 `describe` 已经上万字符，逐列展开会把上下文吃光；
追问只用在「这一列的取值我要写进 WHERE」的那几列上。

### 其余字段

- **`conventions`** —— 这张表命中的**全库约定过滤**（典型是 `del_flag = 0`）。
  **原样带进 `WHERE`**，漏了就是把已删除的数据一起查出来，不报错。
  它来自规则而不是逐表标注，所以新表自动适用；值域不支持时规则不会给提示，
  给出来的就是可信的
- **`scenarios`** —— 这张表属于哪些业务场景。顺着 `schema search <场景名>`
  能一步拿到完整子图（表 + join + 过滤），比在这里逐条关系去猜省事

- **列的业务名 / 语义类型 / 值域** —— 拼 `WHERE` 的字面量之前先看值域
- **关系** —— 带可直接复制的 JOIN 写法，候选的带 `[候选]` 前缀，被人拒绝过的带 `[已忽略]`
- **`sm4Columns`** —— SM4 加密列名单，见 [troubleshooting.md](troubleshooting.md)
- **`recentSql`** —— 这张表最近的成功查询范例（脱敏后的真实 SQL）。
  **先看它再自己拼**，别人已经跑通的写法比你现推的可靠，见 [execution-mine.md](execution-mine.md)

## `path` 的输出直接决定 JOIN 怎么写

`schema path` 每一跳会给出：

- **完整的 `ON` 条件**。复合外键（如 `(tenant_id, order_no)`）的多个列对已经拼成一条
  `A.x = B.y AND A.z = B.w`。**照抄整条，不要只取一个列对**——漏掉 `tenant_id` 这类租户列
  会产生跨租户笛卡尔积，**不报错、有结果、数是错的**。
- **`INNER JOIN（推断）` 或 `LEFT JOIN（推断：… INNER 会静默丢行）`**。
  括号里是这个结论**怎么来的**，两种：
  - **推断** = 只看了外键列的 `nullable` 声明。声明可空就一律给 LEFT，
    而遗留库里大量列声明可空、实际从来没空过——按它写不会错，只是可能多查一批行。
  - **实测** = `schema value-domain` 采样量过这一列的空值率。比声明准，直接照着写。

  两种都不是人确认过的。想把某条边从「推断」升到「实测」，
  跑一次 `sql-cli $ALIAS schema value-domain --table <schema.table>`——
  它剖析整表时顺手就把这张表的外键可选性改按实测了。

## 上下文预算

查询结果会整个进入上下文，**一条没加 `LIMIT` 的宽表查询就能挤掉后面几步要用的信息**。

- 只选真正需要的列，带 `LIMIT`
- 不确定量级时先 `SELECT count(*)`
- 确实要大批数据时把 stdout 重定向到文件再按需读取，不要把几千行打进对话

## 报错和空结果都先看 stderr

CLI 会主动给纠错线索，照着改通常一次就对：

- **列/表不存在** → 报错里带图谱纠错候选（「相近的列: ...」「相近的表: ...」）
- **SELECT 查回 0 行，且 `WHERE` 的值不在图谱值域内** → stderr 会提示该列的已知值域。
  **0 行往往不是没数据，是值写错了形态**（查 `status = '已付款'` 而库里存的是 `1`）

## 命令

```bash
sql-cli <alias> "SELECT ... LIMIT 10"                      # 默认 csv
sql-cli <alias> "SELECT ..." -f json|table
sql-cli <alias> "SELECT ..." --decrypt-cols phone,account  # SM4 列解密
sql-cli <alias> --file query.sql
sql-cli <alias> test [--json]
sql-cli <alias> tables [--schema S] [--pattern "%kw%"]
sql-cli <alias> ddl <table> [--schema S]
```

**写操作和 DDL 会改数据库，执行前必须取得用户明确授权。**

## 写操作一定能退回去

UPDATE / DELETE 执行前会**自动生成回滚 SQL**，连同执行前的原始行一起存进运行库；
**存不下就不执行**。所以「改错了能不能退回去」的答案永远是能——
在 Web UI 评审页的执行记录里点「回滚」。

执行完成的提示里会带 `Task: <id>`，**把它报给用户**，出问题时按这个 id 查得到：

```bash
sql-cli task status <id> --json
```
