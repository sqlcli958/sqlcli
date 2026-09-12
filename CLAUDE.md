# CLAUDE.md

本文件为 Claude Code (claude.ai/code) 在此仓库中工作时提供指导。

## 构建与运行

```bash
# 构建（生成 target/sql-cli.jar，唯一的可执行包）
mvn clean package

# 构建并跳过测试
mvn -q -DskipTests package

# 运行测试
mvn test

# 运行单个测试类
mvn test -Dtest=Sm4SqlCipherParserTest

# 运行 CLI（macOS/Linux）
sh ./sql-cli <alias> "SELECT ..."

# 运行 CLI（Windows）
sql-cli.bat <alias> "SELECT ..."
java -jar target/sql-cli.jar <alias> "SELECT ..."
```

## 改完代码就自动打包重启，不用等人开口

```bash
powershell -ExecutionPolicy Bypass -File dev-restart.ps1            # 打包 + 重启
powershell -ExecutionPolicy Bypass -File dev-restart.ps1 -SkipBuild # 只重启
```

**改了 Java 或 `web/` 之后默认就跑这一条**，让人打开浏览器刷新就能验证，
不要改完停在那里等人问「怎么看效果」。

三件事写进脚本里了，手敲容易漏：

- **前端改动也必须重新 `package`**。`web/` 的产物是**打进 jar** 的，
  只改 `web/` 不打包，页面上什么都不会变——最容易白改一轮的地方。
- **先打包再杀进程**。UI 在跑的时候也能打包（jar 没被独占锁），
  这个顺序保证构建失败时旧服务还活着，人不至于对着一个关掉的页面等。
- **`--no-open`**。不加的话每次重启弹一个新浏览器标签，改十次就有十个标签。

脚本等到端口真的开始监听才返回——直接返回会让人对着一个还没起来的端口刷新。

## 加功能之前：先定层，再答三问

**产品只有一个目的**：给 agent 足够准确的**数据库上下文**，让它能**修复 bug、重构业务代码**。
任何功能先对着这句话过一遍。

**边界**：产品只管**数据库这一侧**。哪个文件调了哪个 Mapper、改这个字段要动哪几个类，
**是 agent 的活**——不做代码索引、不做影响面分析、不存代码引用。
（目的收窄的经过与代价见 [产品方向拍板记录](docs/product-direction-2026-09-03.zh-CN.md)。）

### 三层，新功能必须落在其中一层

```
第三层  口径层    metric  ← 已建成、未开放（2026-09-03）      「这个业务问题怎么算」
                      ↑ 消费下面两层，不与它们并列
第二层  语义层    术语 · 业务描述 · 值域 · 语义类型 · 血缘     「这个字段是什么意思」
第一层  结构层    表 · 字段 · 关系                            「库里有什么、怎么连」
```

**第三层暂时不在路径上。** 目的收窄成「修 bug / 重构」之后，口径层不再是战场——
UI 入口已摘、技能已摘，CLI 保留。重新开放的信号是**出现一次真实的口径争议**
（两次计算得出两个数且必须统一）。新功能落在第一、二层，或数据层质量。

**「数据质量」不是一层，是每层各自的正确性**，三层三套工具，别混成一个：

| 层 | 质量问的是 | 工具 |
|---|---|---|
| 结构层 | 库**设计**得对不对 | `schema policy`（只读图谱，**不连库**；作用域已收窄到新 DDL） |
| 语义层 | **图谱**够不够、准不准 | `schema eval`（硬错误探针 + 覆盖率，硬错误 > 0 退出码 1） |
| 数据层 | 库里的**值**对不对 | `schema data-quality`（**连库**跑只读聚合，四条探针，打印查询并要 `--yes` 才跑） |

**三套工具不合并，判据是「连不连库」。** `policy` 和 `eval` 是纯计算，毫秒级、随便跑；
`data-quality` 要往库上打几百条聚合查询。合成一个命令的结果是「跑一次评估」两种代价，
没人敢在生产别名上跑它。`data-quality` 的四条探针回答的是重构最常问的三句——
**这个字段能删吗 / 这个枚举值还有人写吗 / 这张表还活着吗**——而这三句代码永远查不到答案：
代码只能说有没有人写这行，不能说这行还跑不跑。

**规则的消费点是执行链路，不是一条要人想起来跑的命令。** 2026-09-09 之前 `schema policy`
只有 `design review` / `migration lint` 两个 CLI 入口，真实 DDL 走过的次数是 0，三个别名的
`bindings.yaml` 全空。现在 `CREATE / ALTER / DROP` 经过 `SqlTaskModule` 时 `PolicyStage` 自动按
绑定的规则查候选工作区：required / blocking 拒绝并回修法，advisory 进 `result.notices()`。
agent 归纳出的约定用 `schema policy rule add` **提议**，跟写图谱走同一条 `graphApproval` 判据
（`PolicyRuleProposals`，payload action `policy`），批准才写进规则文件并绑定。
每一类规则都要照这个样子接上消费点（清单 R 系列），没接上的类别在规则页配了也是死的。

### 三问，每个功能都要在清单里写下答案

| | 问题 | 答不出会怎样 |
|---|---|---|
| **产出** | 谁写、什么时候写 | 永远是空的 |
| **消费** | agent 在**哪一步**读它、读了**做什么决定** | **它是死的**，写了没人用 |
| **失败** | 没有它 agent 会犯**什么具体的错** | **它不可评测**，好坏说不清 |

**第三问同时就是这个功能的评测判据。**

**这条规则是有代价换来的**：血缘写了但搜索不提它、`grain` 填了但没人读（F5 缺口）、
术语挂了映射但搜索不显示——三处都是**写入口做完就算完、消费侧没人做**。
「产品在堆功能」的感觉就是这么来的，不是因为功能多，是因为**消费路径没接通**。

**所以验收单位是「一条闭环」，不是「一个条目」**：一次交付必须同时含产出侧和消费侧，
**不许把这两半切给两个 agent**。切不动说明它太大，该缩小闭环而不是拆成两半——
F5 的死因清单里写着「被切给了两个不同的 agent，于是留成两边都完成后再补，然后就没有再补」。

完整推理见 [概念边界与效果评测](docs/graph-concept-boundaries.zh-CN.md)，
新增条目按这个格式记进当月开发清单。

## 架构概览

**包结构：**
- `com.sqlcli.config` - 别名、驱动和 JDBC URL 配置/解析
- `com.sqlcli.connection` - 连接管理、驱动加载、查询执行、连接池
- `com.sqlcli.secret` - 密码解析（env、keyring、encrypted）
- `com.sqlcli.crypto` - SM4 加密配置
- `com.sqlcli.sql` - SM4 列加密的 SQL 改写器
- `com.sqlcli.parser` - SQL 解析（类型检测、表/列提取）
- `com.sqlcli.recovery` - UPDATE/DELETE 的恢复 SQL 生成
- `com.sqlcli.strategy` - 数据库特定策略（MySQL、Oracle、PostgreSQL）
- `com.sqlcli.output` - 输出格式化器（csv、json、table）

**核心流程：**
1. `SettingsConfig` 加载 `config/settings.yaml` 统一管理所有配置
2. `AliasResolver` 根据 `aliasesPath` 加载别名配置，通过 `SecretResolver` 解析 `secretRef`
3. `DriverResolver` 从 `SettingsConfig` 获取驱动配置
4. `ConnectionManager` 通过 `DriverLoader` 创建连接（使用隔离的 ClassLoader 加载 `./drivers/` 中的外部 JDBC jar）
5. `QueryExecutor` 执行 SQL：
   - `Sm4SqlCipherParser` 在执行前改写 SQL，加密敏感列
   - `RecoveryBuilder` 为 UPDATE/DELETE 操作生成恢复 SQL
   - `FormatterFactory` 根据 `-f` 选项格式化输出

**配置文件：**
- `config/settings.yaml` - 主配置文件，包含：
  - `aliasesPath` - 别名配置文件路径
  - `masterPasswordEnv` - 主密码环境变量名
  - `driverDefaults` - 驱动默认配置（按数据库类型）
  - `drivers` - JDBC 驱动配置（dbType、driverClass、jars）
  - `secrets` - 加密密码存储（AES-GCM）
- `config/aliases.yaml` - macOS 别名配置（使用 keyring）
- `config/aliases-windows.yaml` - Windows 别名配置（使用 encrypted）

**配置文件优先级：**
1. 环境变量 `SQLCLI_ALIASES_PATH` 指定的路径（最高优先级）
2. `config/settings.yaml` 中的 `aliasesPath` 配置
3. 默认 `config/aliases.yaml`

**密码方案：**
- `env:VAR_NAME` - 环境变量（跨平台）
- `keyring:name` - macOS Keychain（仅 macOS）
- `encrypted:name` - AES-GCM 加密存储在 settings.yaml（跨平台，推荐 Windows）

## 命令结构

所有别名相关操作以 `<alias>` 作为第一级参数：

```bash
sql-cli <alias> "SQL"              # 快速查询（默认 csv 输出）
sql-cli <alias> --file query.sql   # 从文件执行 SQL
sql-cli <alias> test               # 测试连接
sql-cli <alias> ddl <table>        # 获取表 DDL
sql-cli <alias> tables             # 列出表
sql-cli <alias> secret set         # 设置密码（交互式）
sql-cli <alias> secret status      # 查看密码状态
sql-cli <alias> schema <action>    # Schema 知识图谱管理
sql-cli <alias> batch <action>     # 批次（begin/submit/status/list/discard）
sql-cli <alias> ui                 # 启动 Web UI（schema ui 的快捷方式）
```

系统管理命令：

```bash
sql-cli list                       # 列出别名
sql-cli alias list/show/add/update/remove   # 别名管理
sql-cli driver list/show/add/update/remove/default  # 驱动管理
sql-cli session begin/status/end   # 会话身份（溯源）
sql-cli crypto sm4 --encrypt/--decrypt      # SM4 加密工具
```

全局参数（任何命令都认，入口处统一摘掉）：`--session <id>`、`--agent <name>`、`--batch <id>`。

查询选项：

```bash
sql-cli <alias> "SQL" [-f table|json|csv] [--decrypt-cols col1,col2] [--no-decrypt] [-d]
```

| 选项 | 说明 |
|------|------|
| `-f` | 输出格式（table/json/csv），默认 csv |
| `--decrypt-cols` | SM4 解密列名，逗号分隔 |
| `--no-decrypt` | 返回原始加密数据，不解密 |
| `-d` / `--debug` | 启用详细日志 |

## SM4 列加密

工具支持敏感列（手机号、账号等）的自动 SM4 加密/解密。

**别名配置：**
```yaml
aliases:
  my-db:
    sm4Key: "88ED6EA3C8054CD9"        # SM4 密钥（16字符）
    sm4PrivateTag: "ENC"              # 密文前缀（默认：ENC）
    sm4Version: "240606"              # 版本标识（默认：240606）
    decryptColumns: [phone, account]  # 自动解密列
```

**SQL 改写：** `Sm4SqlCipherParser` 自动加密以下位置的明文值：
- SELECT WHERE 比较表达式：`phone = '13800138000'` → `phone = 'ENC#240606#...'`
- SELECT WHERE IN 列表：`phone IN ('138', '139')` → 加密值
- INSERT VALUES：加密列中的明文值被加密
- UPDATE SET：加密列中的明文值被加密

**输出解密：** 查询结果自动解密 `decryptColumns` 中的列，除非指定 `--no-decrypt`。

**手动加密：**
```bash
sql-cli crypto sm4 --encrypt --text "明文" --alias <alias>
sql-cli crypto sm4 --decrypt --text "密文" --alias <alias>
```

## 恢复 SQL

UPDATE/DELETE 操作在执行前自动生成恢复 SQL：
- UPDATE：生成反向 UPDATE，使用原始值
- DELETE：生成 INSERT，包含被删除行的数据

回滚脚本连同执行前的原始行一起存进运行库 `~/.sql-cli/sqlcli.db` 的 `recovery_artifact`
（**不落文件，也没有第二种形态**——升级前留在 `~/.sql-cli/recovery/*.sql` 的老记录已由
schema v7 的一次性回填搬进库里，兼容读分支和 `file_path`/`checksum` 两列都已删除，
那个目录可以直接删掉）。
**先入库再执行**——这个顺序是安全性本身：反过来写，进程在两步之间挂掉就留下一次
改完且无从回滚的写。入库失败即中止，不执行。回滚的查看与执行在 Web UI 评审页的执行记录里。

## 运行库 `~/.sql-cli/sqlcli.db` 装什么

**判据：运行记录进库，人编辑的配置留文件。**

| 进库 | 留文件 |
|---|---|
| `sql_execution` / `task_run` / `task_event` 执行与任务 | `config/aliases.yaml`、`settings.yaml`（人编辑，可 diff 可进 Git） |
| `recovery_artifact` 回滚脚本与原始行 | 图谱本体 `generations/`（export/import 的载体，可整目录迁移） |
| `graph_change_log` 图谱变更流水（带 before/after 与放行它的审批） | 搜索索引 `index/`（随时可 rebuild） |
| `approval_request` / `approval_batch` 审批条目与批次（图谱条目的 `payload` 里装着尚未生效的变更本身） | `policy/rules/`、`policy/waivers/` 规则与豁免（人编辑） |
| `policy_evaluation` / `policy_violation` 规则评估结果；`graph_evaluation` / `graph_finding` 图谱评估结果 | 导入任务 `jobs/`（跟图谱同目录，拷走时 resume 断点跟着走） |

规则评估那两张表是 2026-08-26 从 `policy/runs/` 迁进来的：它本来就是运行记录
（有起止时间、状态、actor），却按目录存在图谱工作区里，3 轮评估 2MB、5 万行违规，
而列表每次全目录扫——和当初执行历史 JSONL 一模一样的病。
老目录**不删**，首次读取时一次性导入并在 `policy/.runs-imported` 写标记。

## 审批中心：一件等着我决定的事，只有一个入口

要审批的动作全部排进 `approval_request`，全部在评审页 · 待审批标签裁决，
**并且在那里被执行**。四类：

| kind | 什么时候产生 | 批准时发生什么 | 拒绝时发生什么 |
|---|---|---|---|
| `query` / `update` | 别名开了对应开关，CLI / 工作台执行 SQL | 等待方（另一个进程）轮询到放行后自己继续执行 | 等待方收到拒绝并中止 |
| `graph`（apply） | 别名是 `graphApproval: manual`，任何图谱内容变更 | 把 payload 重放进图谱 | 什么都不做——它从没进过图谱 |
| `graph`（publish） | `auto` 别名上 agent 写出的候选边 | 按置信度定级发布 | 转 `ignored`，不再被重新挖掘 |
| `recovery` | 执行记录里提交回滚（无开关，永远审批） | 单连接单事务执行回滚脚本 | 不执行 |

落地动作集中在 `ApprovalEffects`，**先落地、再改审批状态**：反过来的话落地失败会留下
一条「已批准」但什么也没发生的记录。`query`/`update` 是唯一什么都不做的一类——
等待方是另一个进程，这里替它执行会执行两次。

**这几件事都从别处收回来过**，收回来的理由是同一个：
- **回滚**原来在执行记录页点按钮然后请求挂住，人得切到待审批批准自己刚点的东西，
  中间那个按钮一直转，一个 HTTP 线程耗在人的反应时间上。现在提交即返回，批准时才执行。
- **候选关系的发布 / 拒绝**原来在图谱页（后来是评审页 · 图谱标签）单开一个队列。
  它同样是「等着我决定的事」，拆成两个队列的结果是两边都得看一遍。
  改造前写进去的存量候选边由 `WorkspaceMutationService.syncCandidateApprovals` 补齐，
  UI 每次启动跑一遍，幂等（同 target 已有待审批就跳过），不需要迁移标记。

**导出 CSV 不走审批**：导的是已经显示在屏幕上的那一页数据，查询本身已经审过了。

## 一条管线，两个结尾

所有写入（SQL 与图谱）走同一条管线，分支判据只有一个：**这批要不要等人**。

```
提交（条目，可选归入批次）
  → 决策（自动批准 / 人工批准）
    → 落地 ┬─ 同步：当场跑完返回结果      （读、自动批准的写）
           └─ 异步：人批准的那一刻才跑    （人工批准的写、整个 SQL 批次）
```

**一个别名上只能有一种行为。** 任何写图谱或写数据库的路径**不许自己决定要不要审批**——
`schema value-domain` 也不行。风险高低表达成条目上的说明，由别名策略统一裁决。
反例是真发生过的：`schema edit` 受开关控制、`value-domain` 无条件排队，
一条直通一条排队，中间空出的 75 秒把审批基线改掉了（#154）。

**冲突判据是字段级的。** 从条目的 before/after 算出这次实际改动的字段集合，
只校验这些字段的当前值等于 before，落地时也只写回这些字段；当前值已经等于 after 时
同样放行（那是想到一块去了，不是冲突）。整对象比对会把「并发改了另一个字段」判成冲突。

## 批次：多条变更作为一个业务意图

**条目是必需单位，批次是可选分组。** 一条查询、一次 `schema edit` 不建批次；
「梳理一个业务域的 40 处改动」建一个。`intent` 必填——人在队列里裁决的就是那句话，
写「维护图谱」的 60 条批次等于没有 intent，只能盲批，而**盲批比不审批更糟**。

```bash
sql-cli <alias> batch begin --intent "梳理报事域状态字段"   # 返回批次号
sql-cli <alias> schema edit --column ... --batch 77         # 只入批次，图谱不动
sql-cli <alias> batch submit 77                             # 封口，整批进待审批
sql-cli <alias> batch status 77                             # 哪条被否、理由
```

`--batch` 下的条目状态是 `draft`：**submit 之前既不在图谱里、也不在任何队列里**，
两个历史视图都看不到它。落地保证：图谱批次一次 load/apply/save，要么全进要么一个字节没动；
SQL 批次单连接单事务按 `seq` 执行，任一条失败整批 rollback，回滚脚本按批一份、语句逆序。
含 DDL 的 SQL 批次在 submit 时强制标注**不可回滚**——DDL 隐式提交，rollback 对它无效。

**允许部分批准**：逐条可以「否掉这条」，批准这一批 = 落地其余的，批次记 `partial`。
30 条错 2 条就整批打回、让提交方全部重来，代价太大。

## 溯源：session / agent 挂在条目上

`sql-cli session begin [--agent X]` 按**工作目录**落一个状态文件，之后这个目录下的
每条 `sql_execution` 和 `approval_request` 都带上会话号与 agent。
优先级 `--session` > 状态文件 > `SQLCLI_SESSION` 环境变量 > `null`。
**取不到就记 null，不生成占位 id**——每条命令一个新 id 等于没有会话。
环境变量不能当主渠道：agent 每个 Bash 调用是新 shell，`export` 不跨调用。

## 图谱审批：先落 db、批准后才写图谱

别名配置 `graphApproval: auto | manual`（取代原来的布尔 `approveGraph`，`true` 仍读作
`manual`；**没配是 auto**）。`manual` 时图谱变更**不落图谱**，也**不阻塞调用方**：

```
agent: sql-cli demo schema edit --column ... --description '...'
  → 变更存进 approval_request.payload（图谱一个字节没动）
  → 立刻返回「待审批（审批 #42）」
人:  评审页 · 待审批 → 看 before/after → [批准并写入] → 这时才写图谱
                                      → [拒绝]       → 什么都没发生过
     裁决完的记录去评审页 · 图谱标签翻
```

反过来做过两版，都不行：**阻塞等审批**把 agent 钉死最长十分钟；**先写图谱再挂审批**
让「拒绝」变成空动作——表 / 字段描述没有候选态，改都改了，退不回去。

**存的是单对象补丁，不是工作区快照。** payload 是被改的那**一个**对象的
`{targetId, operation, before, after}`（`GraphObjectPatch` 按 id 前缀定位
schema / table / column / relation / term / lineage / metric 七类）。快照在批准时整个
盖回去会抹掉提交到批准之间别人的改动；单对象补丁能重放到当时最新的图谱上，
还能顺带做冲突检测——批准前比对当前值与 `before`，不等就拦下来，不覆盖。

**批准即评审**：重放出来的候选关系顺手按置信度定级发布，不用再去点第二次「发布」。
反过来，**没开** `approveGraph` 的别名上 agent 写出的候选边会排一条 `publish` 审批
（见上表），批准 = 发布、拒绝 = 转 `ignored`——两条路最终都收进同一个待审批队列。

**两类不走审批**，它们都不是「有人主张的内容变更」：
`GraphActor.system` 的记账动作（建索引、跑校验、回写行数——挡下来的后果是不批准就不能
`schema validate`）、整份快照导入（`commitLocked` 与 `schema import --input`，
一次成千上万个对象，要管的是导入这个动作本身）。前者静默放行，后者会明确报错说明原因。

SQL 执行和回滚仍然阻塞等审批（`ApprovalGate.awaitApproval`）：它们没有「写一半」这种状态。

**UI 里改了东西却没生效，必须说出来。** 写操作有十个入口，提示只有一份：
`onMutationSuccess`（所有 UI 写操作的唯一收口）看到 `pendingApprovalId` 就挂进
`sessionStore.pendingApproval`，顶栏下沿通栏提示一句并给一条去评审页的路。
不提示的话编辑器一关就像存成功了，而图谱纹丝不动——这是这套机制唯一会骗到人的地方。

### 每次图谱更新都可追溯

`graph_change_log` 每次更新一条：谁改的、哪个对象、revision 从几到几、改成了什么
（`payload`）、被哪条审批放行的（`approval_id`）。读端点 `GET /api/graph-changes`，
UI 在评审页 · 图谱标签的「变更记录」视图——它比同一页的「审批记录」全，
没开审批的别名直接生效的改动也在里面。

流水里的 payload 只带 `after`：拿 before 得在改动之前就知道 targetId，而 targetId 是
mutation 跑完才产出的。同一个对象上一条流水的 after 就是这一条的 before；开了审批的话
完整 before/after 在 `approval_request.payload` 里，顺着 `approval_id` 查得到。

## Oracle 连接说明

Oracle JDBC URL 支持两种格式：
- SID：`jdbc:oracle:thin:@host:port:sid`
- SERVICE_NAME：`jdbc:oracle:thin:@//host:port/serviceName`

`ConnectionManager` 应用 Oracle VPN 兼容默认值：`oracle.jdbc.javaNetNio=false`、`oracle.net.disableOob=true`。

DDL/tables 操作需要指定 `--schema`（默认使用用户名）。

## MySQL 说明

tables 输出的 schema 列为空（MySQL 无 schema 概念）。
DDL 使用 `SHOW CREATE TABLE`。

## 输出格式

默认为 `csv`。使用 `-f table` 生成可读表格，`-f json` 生成机器可读输出。

## Web UI 规则

改 `web/` 下的界面时遵守以下约定，新增页面同样适用。

**语言**：界面文案一律中文，包括空态、错误、按钮和 `title`。不要留英文标签。
后端返回的英文 message 不要直接拼进成功提示（失败时可以带上，原因有信息量）。
例外：关系类型、语义类型这类**枚举值直接显示英文原值**（`foreign_key`、`join_observed`），
它们是 CLI 与 API 的契约，翻译成中文会和命令行里看到的对不上。

**术语「schema」**：MySQL 的 database、Oracle 的用户空间、PostgreSQL 的 schema，
在图谱模型里都是 `SchemaWorkspaceNode`，**对外一律称 schema**，不要出现
「数据库」「库」「database」三种叫法混用。UI 文案、API 字段、CLI 参数、文档统一。
各数据库原生叫法与 JDBC 元数据取法的完整对照（含 ClickHouse、SQLite）见
`docs/schema-catalog-database-terminology.zh-CN.md`。

**状态色**：绿=正常，黄=可用但需注意或状态未知，红=不可用或受限。
适用于顶栏状态灯（图谱 / 连接 / 索引）、字段可空性、图谱校验等一切三态展示。
用 `.shell-dot` / `.cell-dot` 两个现成类，不要另起配色。

**图标优先**：能用图标表达的不写文字（折叠、关闭、只读、状态）。
图标按钮用 `.btn.is-icon`，必须同时给 `title` 和 `aria-label`——
说明文字放 title，鼠标停留才出现，不占版面。

**文字克制**：一句话能说清就不写两句；页面里已经能看出来的事实不再用文字复述。
"加载中…"不要写成"正在加载数据源…"。解释性长句放 `title`，不放正文。

**基础设施只在 `web/src/ui/` 定义，feature 目录只组装不定义。**
按钮、状态灯、分页、工具条、标签页有且只有一个实现：

| 要什么 | 用什么 |
|---|---|
| 按钮 | `<Button variant size icon>`；`<Link>` 之类非 button 元素用 `buttonClass()` |
| 三态状态灯 | `<StatusDot tone label>` |
| 分页 | `<Pagination page pageSize total onPage onPageSize>` |
| 列表上方工具条 | `<FilterBar>`，右端放 `<Pagination>` |
| 标签页 | `<Tabs items value onChange label>` |
| 行操作下拉菜单 | `<Menu trigger>` + `<MenuItem onSelect>` |
| 增删改图标按钮 | `<ActionIcon action label>` |
| 下拉框 | `<Select size label>` |

**判断标准是「长什么样」还是「怎么动」。** 按钮、状态灯、分页、工具条这些扒掉样式就只剩
一个 `div`，自己写更划算；下拉菜单要管点外面关、ESC 关、焦点还回触发按钮、方向键、
贴边翻转、渲染到 portal 躲开表格裁剪——手写六条里至少漏三条。
再要加第三方组件，先用这个标准过一遍。

**下拉框跟菜单是同一道判据的相反结论。** 菜单没有原生元素可用，所以引 radix；下拉框有
原生 `<select>`，键盘上下 / 首字母跳转 / ESC / 触屏原生选择器全是白拿的，所以是**包**
（`ui/Select.tsx` 包一层样式）不是**替**——原生 select 唯一的毛病是不继承页面字体，
`<Select>` 只补这一条「长什么样」，行为一律交还给浏览器。

按这个标准进来的第三方 UI 库有四套，**每套都必须被 `ui/` 里的一个组件包住**：

| 库 | 包装层 | 为什么手写不划算 |
|---|---|---|
| `@radix-ui/react-dropdown-menu` | `ui/Menu.tsx` | 上面那六条行为 |
| `echarts` | `ui/Chart.tsx` | 刻度取整、resize 重算、tooltip 跟随贴边、图例联动、端点标签避让、空数据退化 |
| `sigma` + `graphology` | 图谱页的关系图 | 力导向布局与画布交互 |
| `@xyflow/react` + `@dagrejs/dagre` | `ui/FlowCanvas.tsx` | 拖拽平移、滚轮缩放、小地图、fit view、节点上的端口与边的路由、从左到右分层布局。血缘大图要「表是容器、列是行、边从行到行」，sigma 把节点画成点做不了；手写 SVG 做过一版，十一张各自为政的小图 |

**这条曾经写成「第三方 UI 库只有一个」，那时候 `sigma` 已经在仓库里了**——比任何图表库
都重。一条自己就不准的纪律拦不住任何人，所以改成列表：加一套就往上加一行，
并说清它凭什么过了「怎么动」那道判据。

两条配套约束：**按需引入**（`import * as echarts from 'echarts'` 会把 1MB 全打进包，
只注册当前真的在画的图种），以及**图表库不替你判断该不该画**——「这两次评估可不可比」
「点够不够画一条线」这类判断留在 feature 里，换渲染器不能把它们一起换掉。
`eslint.config.js` 的 `no-restricted-imports` 拦着 feature 里直接 import echarts。

**这条有 lint 兜底**：feature 里直接写 `className="btn ..."` / `cell-dot` / `pager` /
`filter-bar` / `tabs` 会报错，裸 `<select>` 同理（`eslint.config.js` 的 `no-restricted-syntax`）。
之所以从"写在文档里"升级成 lint，是因为光靠约定挡不住——分页被写过两遍、
筛选条被写过两遍、`.exec-pager` 和 `.wb-pager` 并存过。

新增一个基础件时：组件放 `web/src/ui/`，样式放 `App.css` 的对应段落，
然后把类名加进 `eslint.config.js` 的 `infraClasses`。改那条选择器之后**务必真写一个
违规 className 验一遍**——规则失效是静默的。

不要在 feature CSS 里新写一套按钮样式。

**一级导航七项**（原「固定六项」破了一次例）：工作台 / 图谱 / 指标 / 规则 / 评估 / 评审 / 设置。
工作台兼原「概览」——未选数据源时是数据源目录，选中后是健康视图加 SQL 编辑器。

**「固定六项」这条规矩本身管的是防导航膨胀，不是禁止任何新增**——但破例必须留痕，
否则下一个人会照着这次的先例，不写理由就加第八项。「指标」凭什么配得上单独一页：
它的用法是「定义口径 → 展开 SQL → 出图看走势」，跟图谱页「按 schema/表浏览图谱对象」
不是同一种活，塞进图谱页第四个标签是先前一版放错的位置（图谱页现在退回 表目录/术语/血缘
三个标签）。位置排在图谱之后规则之前：三层模型里口径层在语义层之上、消费下面两层，
挨着图谱放也提示它的定义来自图谱里的对象。

**评估为什么配得上单独一页，而不是规则页的一个标签**：规则页答的是
「库**设计**得对不对」（`schema policy`，只读图谱不连库），评估页答的是
「**图谱**够不够、准不准」（`schema eval`）——CLAUDE.md 开头那张三层表里
它们是两层的质量，混进一页读的人分不清在说谁。而且评估自己就有三块独立内容：
**评估集管理**（检索 / 任务两套 case 的增删改）、**趋势对比**（同一评估集在
revision N 和 N+k 上的差，这是整套评估唯一的价值所在）、**任务级评估**
（跑一个 agent 做一个任务，看它做没做对）。塞进规则页的一个标签装不下，
更会让「这次比上次好了还是坏了」这个唯一要回答的问题降级成一个角落。
评估页有「跑一次评估」，但**评估器只有一个**：按钮走 `POST /api/eval/run`，后端调的是
`sql-cli <alias> schema eval` 同一个 `GraphEvalRunner`，同一张表、同一个 `source`。
这条原来写成「页面只读，没有跑一次」，理由是「开出第二条产出路径，两边结果迟早对不上」——
**要防的是两套实现，不是两个入口**。所以规矩改成：入口随便加，**不许在别处再写一段
算覆盖率的代码**；要加新入口就调 `GraphEvalRunner`。回归 `RunEvalFromUiTest` 直接断言
两个入口的硬错误数和 finding 条数一致，有人另抄一份就会挂。
**评审页四个标签**：待审批 / 审批记录 / 图谱 / 执行记录。第一个是待办队列，
后三个是「已经发生过什么」。执行记录在这里是因为审批看「要不要放行」、执行记录看
「放行之后发生了什么」，是同一件事的两半，工作台不再重复展示。

**一件等着我决定的事只有一个入口**：待审批**不按类型拆**——SQL 和图谱都排在这里，
图谱卡片多摊开一份 before/after 而已（不看 diff 批不下去，所以直接摊开、不藏进「详情」）。
按类型拆成两个队列的结果是两边都得看一遍，还会在一边批完另一边还挂着。

**按批渲染**：同一批的条目合成一张卡，标题是 `intent`。无批次的条目仍然一条一张——
一批 40 条摊成 40 张卡就是在逼人盲批。

批次卡**默认折叠**，只给 intent 和条数，点开才出条目。这一条压过图谱审批
「直接摊开、不藏进详情」那个规矩：单条摊开 diff 是为了让人看得见，
而 40 条同时摊开的结果是人拉到底直接点同意——**同一个盲批，换了个形状**。

展开后每条前面有勾选框，默认全选；**取消勾选 = 批准这一批时否掉这条**，
所以一旦有取消勾选，理由就变成必填（跟单条「否掉这条」同一条规矩）。
落地顺序是先逐条否、再批整批，服务端的部分批准本来就是这么设计的。

图谱只在历史侧单开一页：它有自己的一套「已经发生过什么」——审批记录、每次更新的
变更流水、被拒过的边（可撤销回队列），混进通用的审批记录里既排不齐也看不出来。所以
「审批记录」传 `excludeKind=graph`、「图谱」传 `excludeStatus=pending`，
三处加起来不重不漏。
不要再新增一级菜单，新功能进这六页之一。

标签用的是**下划线 tab**（`ui/Tabs.tsx`），不是胶囊分段控件。分段控件是切「同一份数据的
不同看法」的（表格 / 图表），页面内分区用下划线——它通栏的下边框顺带把页头和内容切开。

**入口唯一**：同一个功能只在一个地方提供入口。
图谱导入和索引重建都在图谱页（导入在左侧表目录、按 schema 逐个导），连接配置在设置页。
**图谱的评审动作不在图谱页**——图谱变更和候选边的发布 / 拒绝都在评审页 · 待审批标签，
只有「撤销一次拒绝」在评审页 · 图谱标签（那是反悔，不是待办）。图谱页的关系列表一次
只知道一张表的关系，把动作留在那里等于让人挨张表翻才能清空队列；那边只展示分组并链过来。
工作台不放导入按钮——挑哪个 schema 要看表目录，那是图谱页的东西。
因此**图谱页在图谱未导入时也必须能进**，不能按「有没有图谱」禁用它。
其他页面需要时用跳转链接过去，不要复制一份按钮。

**状态集中**：跨页可见的状态只在顶栏状态灯展示一次，页面内不重复渲染同一状态。

## Java 版本

需要 Java 17（pom.xml 中 maven.compiler.source/target）。