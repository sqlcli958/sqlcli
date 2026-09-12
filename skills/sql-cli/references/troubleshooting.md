# 异常处理

## 审批：一条管线，两个结尾

所有写入——SQL 和图谱——走同一条管线：

```
提交（条目，可选归入批次）
  → 决策（自动批准 / 人工批准）
    → 落地 ┬─ 同步：当场跑完返回结果      （读、自动批准的写）
           └─ 异步：人批准的那一刻才跑    （人工批准的写）
```

分支判据只有一个：**这批要不要等人**。没有第三个结尾。

### 一、SQL 执行——命令挂住等人

```
[审批] 更新操作需要审批（审批 #12 / 任务 #34），请到 Web UI 的评审页批准后继续；Ctrl+C 取消。
[审批] 进度可查：sql-cli task status 34
```

阻塞最多 10 分钟。两件事：

1. **马上把审批号报给用户**——「这条 UPDATE 需要审批（审批 #12），到 Web UI 评审页批准后会自动继续」。
   他不知道有东西在等他，那 10 分钟就是白等。
2. **不要干等**：放后台，回头用任务号查。

```bash
sql-cli task status 34 --json      # 跑到哪一步、影响了多少行
sql-cli task list --json           # 忘了号就在这里找，最新的在最前面
sql-cli task list --status running --json
```

`events` 里 `precheck` 带**预估**影响行数和能否回滚，`executed` 带**实际**影响行数；
`taskRun.status` 是 `running` / `success` / `rejected` / `failed`。

### 二、图谱写入——立刻返回，但可能没生效

别名的 `graphApproval` 决定走哪个结尾：`auto` 当场写进图谱，`manual` 排队等人。
`schema edit` / `add-*` **都不阻塞**，退出码 0，stdout 是这几句之一：

| 输出 | 图谱现在是什么状态 | 接下来 |
|---|---|---|
| （正常的成功提示） | 已经写进去了 | 没有别的事 |
| `待审批（审批 #42）：变更已提交，批准后才写入图谱。` | **一个字节没动**，变更躺在运行库里 | 批准才写进图谱；拒绝则什么都没发生过 |
| `待发布（审批 #42）：已作为候选写入，发布后才进入正式图谱。` | 已经写进去了，但状态是候选 | 批准 = 发布定级；拒绝 = 转「已忽略」，不再被重新挖掘 |

**退出码 0 不等于生效。** 看到后两句就原样转达给用户并给出审批号，
不要接着说「已补充完成」。

**不要重试、不要换命令、不要 Ctrl+C 重来**，重试只会再排一条待审批。
超时或被拒绝时退出码非 0，错误信息里写明原因。
审批号和任务号在评审页卡片上直接显示，用户拿着 CLI 打印的号对得上。

### 三、被否的条目：看理由再改，不要原样重提

批次里的条目可以被单独否掉，理由跟着回来：

```bash
sql-cli <alias> batch status 77
#101 seq1 [approved] 补 orders.status 业务名
#102 seq2 [rejected] 补 orders.status 值域 —— 枚举漏了「已退款」
```

**原样重提会被再否一次。** 按理由改完之后开一个新批次重提被否的那几条，
不要把整批重来——已经批准的那些已经落地了。

批次状态：`applied`（全落地）/ `partial`（有条目被否，其余已落地）/
`rejected`（整批否掉）/ `failed`（落地时出错，**一条都没生效**，条目还在队列里）。

`failed` 要看 `裁决说明`——多半是「该对象在提交审批之后已被改动（某字段）」，
说明有人动过同一个字段。基于最新图谱重新算一遍再提。

## 报错不用你复述全文

**失败的语句也进历史。** 每条执行（成功、失败、被拒绝）都落在运行库里，
Web UI 评审页的「执行记录」能按 schema / 语句类型 / 时间范围 / 状态筛，
点开看完整 SQL 和失败原因。指给用户看那里就行，不必把长报错整段贴进对话。

## stderr 上的红字不一定是错误

`Picked up JAVA_TOOL_OPTIONS: -Dfile.encoding=UTF-8` 是 JVM 提示，每次调用都可能打印。
在 PowerShell 里它会被包装成 `NativeCommandError` 的红字。

**判断成败看退出码和 stdout 内容。** 不要因为这行就重试或换命令。

## `Multiple statements are not supported`

一次只能执行一条语句，分号拼接会被拒绝。需要多条就分多次调用。

## SQLite：连上了但一张表都没有

SQLite 别名连的是**一个文件**（`database: D:/data/shop.db`，没有 host/port/账号密码）。

**`sqlite-jdbc` 对不存在的文件不报错，而是静默新建一个空库。** 所以路径拼错的表现是
「连接成功、`tables` 空空如也」，而不是一句清楚的报错。

工具已经挡了这一层：文件不存在时连接会被拒绝，错误里带解析后的绝对路径。看到那条报错先核对路径，
**确实要新建空库**才在别名的 `params` 里加 `createIfMissing: "true"`。

另外两点和别的库不一样，不是故障：

- **schema 恒为 `main`**（SQLite 只有一个命名空间，`main` 是它自己的叫法）。
  导入和查询都用 `--schema main`
- **没有任何注释**。SQLite 根本没有 `COMMENT` 语法，所以 `comment` 一律为空——
  业务含义只能靠 `--business-name` / `--description` 补，见 [graph-write.md](graph-write.md)

## 连接失败 / 别名不存在 / 密码错误

**直接把错误报给用户。** 别名、驱动和密码是用户的运维范围——
不要代为修改配置、不要重设密码、不要尝试其他别名「碰碰运气」。

## SM4 加密列

`describe --json` 的 `sm4Columns` 列出这张表被 SM4 加密的列。

**只支持等值和 IN 查询。`LIKE` 和范围比较永远查不到**——密文的字节序和明文无关，
`WHERE phone LIKE '138%'` 会返回 0 行而不报错。

- 查询时写明文，工具会自动改写成密文比较
- 读结果时加 `--decrypt-cols phone,account` 解密展示
- **不要把解密后的值写回图谱**（值域、样例值都不行），见 [execution-mine.md](execution-mine.md)

## 列 / 表不存在，或 SELECT 返回 0 行

CLI 会在 stderr 给纠错线索（相近的列 / 相近的表 / 该列的已知值域），照着改通常一次就对。
判读方法见 [query.md](query.md) 的「报错和空结果都先看 stderr」。

## 写图谱的命令失败

看 stderr 的具体原因，常见几种：

| 报错 | 原因 |
|---|---|
| 缺 `--confidence` | `join_observed` 必须显式传，没有默认值兜底 |
| `--verified` 被拒 | verified 要求 `confidence >= 0.9` |
| 值域取值重复 | 同一个值给了两个含义，是写的人搞错了 |
| 未知的 `--semantic-type` | 必须用固定枚举值，报错里会列出全部合法取值 |
| 列 / 关系不存在 | 引用的目标不在图谱里，先 `describe` 确认 |

**不要为了让命令通过就降低 confidence 硬塞进去。** 写不进去通常说明证据不足。

## 搜不到刚写进去的东西

编辑或导入之后要跑一次 `schema index rebuild`。
不是每次搜索前都 rebuild——只在写入之后。

## 图谱是空的

`schema stats` 全零说明没导入过。**这不是故障，是没初始化。**

导入会改工作区，**必须先征得用户同意**，并优先最小范围：

```bash
sql-cli <alias> schema import --from-db --schema <schema>
sql-cli <alias> schema import --from-db --table <schema.table>
```

`--force-overwrite` 和 `import reset` 会重写工作区，**更要明确授权**。
导入后 `schema index rebuild`。
