# SqlTaskModule 详细设计

> 日期:2026-08-24 ｜ **状态:已实现**（同日一次性落地，未分批）。
> `com.sqlcli.task` 包、`QueryExecutor.java` 已删除。实现与本设计的出入记在
> [§15](#15-实现落地记录2026-08-24)，评审/开工可以跳过第 12 节的分步提交计划——
> 那是写给"如果分批做"的场景，实际是一次做完的（产品未上线，不需要保留过渡期）。
>
> 前置阅读:[管线研究](sql-pipeline-optimization-research.zh-CN.md)(为什么不用
> MyBatis 插件模式、不引外部链式库)、[开发清单 P0](dev-checklist-2026-08.zh-CN.md)(验收条目，已勾选)。
> 本文把 P0 的验收条目落到接口签名、代码去向和提交顺序这一层。
>
> 第 1~11 节的行号引用以设计当时的 `QueryExecutor.java`（407 行版本，已删除）为准，
> 保留作为"代码去哪了"的对照表；要看现状直接读 `src/main/java/com/sqlcli/task/`。

---

## 1. 定位与边界

**SqlTaskModule 是全项目唯一的 SQL 执行入口。** 一次调用 = 一次任务:预检 → 审批 →
改写 → 执行 → 审计,返回结构化结果。渲染(csv/json/table)是调用方的事。

### 1.1 现状:两条路径,各缺一半

| 能力 | CLI 路径<br>`QueryExecutor.executeToString` | UI 路径<br>`SqlRerunController.execute` |
|---|---|---|
| SM4 加密改写 / 结果解密 | ✅ | ❌(注释明说不做,密文原样返回) |
| 执行历史落库 `sql_execution` | ✅ | ❌(全文 0 处 `RunStateStore`) |
| 方言预处理 / 策略门控 | ✅ | ❌ |
| WHERE 校验 / 恢复 SQL / 事务边界 | ✅(恢复有,事务边界**没有**) | 不需要(只读白名单) |
| 行数上限 / 超时 / 截断标志 | ❌(结果无上限进内存) | ✅(硬编码 500 / 超时) |
| 审批闸门 | ✅ | ✅(**复制的一份**) |
| 返回形态 | 渲染好的字符串 | 结构化 columns/rows/truncated |

4b 之所以另起一条路径,根源是 `executeToString` 只会返回渲染好的文本——要在网页上画
表格的调用方拿不到列类型和截断标志。**返回值结构化是收敛两条路径的前提**,不是附带优化。

### 1.2 必须穿过这条路径的已知扩展(全部来自清单,不是猜的)

行数上限与截断标志、超时、写操作事务边界、`task_run`/`task_event` 落库、Yearning 路由、
审批闸门、审批预检(影响行数 / 命中规则 / 恢复能力)、工作台读写分流与中止、
结果列头带字段含义与值域、ClickHouse 流式大结果集、值域越界校验。十一项。

### 1.3 本模块**不做**的(v1)

- 不重做审批。`ApprovalGate` 已定形,模块只调用它。
- 不动渲染逻辑。`QueryResultRenderer` 的 csv/json/table 格式化原样保留,只是改为消费
  结构化结果(见 §9.1),SM4 解密从它里面**挪走**(见 §5.4)。
- 不统一三次 JSqlParser 解析。类型检测、SM4 改写、恢复生成各自解析的现状保留,
  共享 AST 是 v2(改它要动 `Sm4SqlCipherParser` 和 `SqlParser` 的接口,和收敛路径无关)。
- 不把 policy 门控从路由里拆出来。`ALTER` 的 ClickHouse mutation 分支
  (`QueryExecutor:138-146`)证明「允不允许」和「走哪条执行分支」是耦合的,拆开只会把
  一个 switch 变成两个必须同步维护的 switch。门控留在 `JdbcBackend` 的路由里。
- 不做执行中止。但留缝:写路径产生 taskId,`JdbcBackend` 内部持有 Statement,
  将来加一个 `Map<taskId, Statement>` 注册表调 `stmt.cancel()` 即可,不用改结构。
  **（v2 已按此实现,见 §15.8——键用的是客户端 cancelToken 而不是 taskId,
  因为读操作没有 task_run 而 SELECT 恰恰最需要中止。）**
- 不做插件注册表 / 动态发现 / `@Order`。Stage 列表是一处写死的有序 `List.of(...)`。

---

## 2. 全景

```
调用方(CLI / UI / 工作台)
   │  SqlTaskRequest
   ▼
SqlTaskModule.execute()                    ← 唯一入口,一个 public 方法
   │
   │  ① 构造 SqlTaskContext(解析 strategy、解析 EffectiveLimits——纯配置运算,不是 Stage)
   │
   │  ② 顺序跑 6 个 Stage 的 before(任何一个可抛 SqlTaskRejected)
   │      GuardStage → PolicyStage → PrecheckStage → ApprovalStage → CipherStage → DialectStage
   │      PolicyStage 只管 CREATE/ALTER/DROP:按别名绑定的结构规则查候选工作区,
   │      required/blocking 违规拒绝并回修法,advisory 进 result.notices()(2026-09-09 加)
   │
   │  ③ 选 Backend 执行(Yearning 在这层分叉,不在 Stage 链上)
   │      JdbcBackend(读路径 / 写路径含事务边界)
   │      YearningBackend(HTTP,只读)
   │
   │  ④ 反序跑 Stage 的 after(只有 CipherStage 有:结果解密)
   │
   └─ finally ⑤ TaskAuditRecorder(成功 / 拒绝 / 失败都记)
   │
   ▼
SqlTaskResult(结构化;渲染是调用方的事)
```

分层原则:**Stage 只装能独立摘出来的横切关注点;拆不开的(事务边界、门控+路由)留在
Backend 内部;不同传输通道是 Backend 实现,不是链上的 if。**

---

## 3. 类型定义

新包 `com.sqlcli.task`,约 12 个文件。`QueryExecutionOptions` 留在原包不动
(被 CLI / Yearning / 渲染器广泛引用,搬它是纯 churn)。

### 3.1 SqlTaskRequest

```java
public record SqlTaskRequest(
        DatabaseConfig config,
        String sql,
        QueryExecutionOptions options,   // SM4 列、noDecrypt;format 字段模块忽略
        Origin origin,                   // cli / ui_rerun / ui_workbench —— 写进 sql_execution.source
        Long rerunOf,                    // 重放时关联的原记录 id,写进 sql_execution.rerun_of
        Integer maxRowsOverride,         // CLI --max-rows;null=走配置链
        Integer timeoutSecondsOverride,  // null=走配置链
        boolean dryRun                   // true:跑完预检和审批信息收集就停,不执行
) {
    public enum Origin { cli, ui_rerun, ui_workbench }
}
```

`sql_execution.source` / `rerun_of` 列**已经存在**(批次 2 建的,
`RunStateStore.recordExecution` 已有对应重载),现在只是没人传。

`dryRun` 是给「审批预检」和将来「工作台预览」用的:走完 Guard + Precheck,
把预检信息装进结果返回,不碰数据库写路径、不进审批阻塞。

### 3.2 SqlTaskResult

```java
public final class SqlTaskResult {
    public enum Status { SUCCEEDED, REJECTED, FAILED }

    Status status;
    String sqlType;              // 最终(改写后重检的)类型
    String executedSql;          // 改写后的 SQL(脱敏由审计层做,这里是原文)
    String taskId;               // 写操作才有;task_run.id 的字符串形式
    List<Column> columns;        // record Column(String name, String jdbcTypeName)
    List<List<Object>> rows;     // 读路径;写路径为空表
    boolean truncated;           // 行数达到上限被截断
    long elapsedMs;
    Integer affectedRows;        // 写路径;不可靠时为 null(渲染成 "unknown" 是调用方的事)
    Long recoveryId;             // UPDATE/DELETE 生成了回滚脚本时非空(recovery_artifact.id)
    Precheck precheck;           // 写操作且(开审批 或 dryRun)时非空,见 §5.2
    String errorSummary;         // REJECTED / FAILED 时非空,已脱敏
    String rootCause;            // FAILED 时的根因串(对应现在的 "| Root cause: ..." 逻辑)
}
```

三个决定:

- **行是 `List<List<Object>>` 不是 `List<Map>`。** 现在 `QueryResultRenderer` 用
  label 做 Map key,`SELECT a.id, b.id` 两列同名会**丢一列**——这是既有暗 bug。
  结构化结果按位置存,UI 路径直接修好;CLI 走桥接保持字节级兼容(见 §9.1),
  等于 v1 里 CLI 保留旧行为、新路径不再有这个坑。
- **模块边界不抛异常。** 拒绝(readonly / 多语句 / 审批被拒)= `REJECTED`,
  基础设施炸了(连不上 / SQL 错)= `FAILED`,都以结果返回。调用方不再各写一套
  try/catch;CLI 按 status 映射退出码。编程错误(NPE 之类)照常抛,那是 bug 不是结果。
- **`Integer affectedRows` 可空**,替代现在的 `formatAffectedRows` 把 "unknown"
  拼进字符串——"unknown" 是渲染决定,不是数据。

### 3.3 SqlTaskContext(包内可见,不出模块)

```java
final class SqlTaskContext {
    final SqlTaskRequest request;
    final DatabaseConfig config;          // = request.config(),缩短访问
    final DatabaseStrategy strategy;      // DatabaseStrategies.resolve() 只调一次
    final EffectiveLimits limits;         // record(int maxRows, int timeoutSeconds),0=不限
    String sql;                           // 当前文本,Cipher/Dialect 会改它
    String sqlType;                       // Guard 检出;Dialect 改写后重检覆盖
    Precheck precheck;                    // PrecheckStage 填,可为 null
    String taskId;                        // 写操作在审计开始时填
    final SqlTaskResult.Builder result;   // Backend 和 after 往里填
    long startedAtMillis; long startedNanos;
}
```

**没有 `ast` 字段**——v1 不共享 AST(§1.3),放一个用不上的字段是自欺。

### 3.4 Stage 契约

```java
interface SqlTaskStage {
    String name();   // 进 task_event 的 payload,也用于日志

    /** 检查或改写。拒绝执行时抛 SqlTaskRejected(driver 转成 REJECTED 结果,仍走审计)。 */
    void before(SqlTaskContext ctx) throws SqlTaskRejected;

    /** 结果加工,只在执行成功后、按 before 的反序调用。默认空实现。 */
    default void after(SqlTaskContext ctx) {}
}

/** 受检异常:让"拒绝"在编译期就和"失败"分开,driver 里漏接不了。 */
final class SqlTaskRejected extends Exception {
    SqlTaskRejected(String message) { super(message); }
}
```

**为什么不是之前聊过的三态 `Decision` 枚举**:返回值要求每个调用点记得检查,
漏检查是静默 bug;受检异常把拒绝路径变成编译器强制。三态里的 `SHORT_CIRCUIT`
(不执行但算成功)被 `request.dryRun` 覆盖——那本来就是调用方的意图,
不该由某个 Stage 中途决定。少一个枚举、少一种约定。

### 3.5 Backend 契约

```java
interface SqlBackend {
    /** 不支持写的后端,写语句在 GuardStage 就地拒绝(比执行时才拒早且信息一致)。 */
    boolean supportsWrites();

    /** 执行并把 rows/affectedRows/recoveryId/elapsed 填进 ctx.result。 */
    void execute(SqlTaskContext ctx) throws Exception;
}
```

### 3.6 Driver(SqlTaskModule 本体,全部逻辑)

```java
public SqlTaskResult execute(SqlTaskRequest request) {
    SqlTaskContext ctx = newContext(request);           // 含 strategy + limits 解析
    audit.begin(ctx);                                   // 写操作:插 task_run(running) + 事件 submitted
    int completed = 0;
    try {
        for (SqlTaskStage s : stages) {                 // ② 前向
            s.before(ctx);
            completed++;
            audit.event(ctx, s.name());                 // 事件:guard / precheck / approval...
        }
        if (!request.dryRun()) {
            backendFor(ctx).execute(ctx);               // ③ 执行
        }
        for (int i = completed - 1; i >= 0; i--) {      // ④ 反向 after(只成功路径)
            stages.get(i).after(ctx);
        }
        return ctx.result.succeeded();
    } catch (SqlTaskRejected e) {
        return ctx.result.rejected(e.getMessage());
    } catch (Exception e) {
        return ctx.result.failed(e);                    // 含脱敏 + rootCause 提取(搬现有逻辑)
    } finally {
        audit.finish(ctx);                              // ⑤ sql_execution 永远写;task_run 置终态
    }
}
```

`stages` 是构造函数里的 `List.of(guard, precheck, approval, cipher, dialect)`,十行读完
整条管线。审计**不是 Stage**:它必须在拒绝和失败时也执行,所以是 try/finally,
链的顺序保证不了这一点。

依赖全部构造函数注入(`ConnectionManager` / `RunStateStore` / `ApprovalGate` /
`SqlCipherParser` / `SqlInterceptor`),另有一个 no-arg 便捷构造给 CLI 用。
（清单原本把「手写组合根 AppContext」列为本模块的前置项,2026-08-24 查证后取消——
构造函数注入本身已经把可测性拿到了,见 §16。）

---

## 4. 五个 Stage 逐个规格

每节给出:搬哪段现有代码、新增什么、拒绝条件。**搬 = 逐字移动,不顺手重写。**

### 4.1 GuardStage(纯静态检查,不碰数据库)

| 动作 | 来源 |
|---|---|
| `detectSqlType(sql)` → `ctx.sqlType` | `QueryExecutor:70` |
| readonly 别名拒绝写 | `:72-74`,错误文案**逐字保留**(`"configured as readonly..."`,有调用方依赖这个措辞) |
| 多语句拒绝 | `:86-89`,文案逐字保留(`"Multiple statements are not supported..."`) |
| UPDATE/DELETE 无 WHERE 拒绝 | `requireSafeStandardDml`(`:305-317`)整体搬入 |
| **新增**:后端不支持写 → 拒绝写语句 | 替代现在 Yearning 在执行分支里才拒(`:193-200`);文案沿用 `"Yearning access mode only supports read-only queries..."`。拒绝提前到解析后连接前,行为等价、时机更早 |

### 4.2 PrecheckStage(写操作专属,产出给审批和 dryRun 看的信息)

```java
record Precheck(
        String table,                 // 目标表(qualified)
        boolean recoverySupported,    // capabilities + ParsedSql 复杂度 + 主键存在性
        List<String> primaryKey,      // 可能为空
        Long estimatedRows,           // 同 WHERE 的 COUNT(*);查不出为 null
        String skippedReason          // 没跑动态部分时说明为什么
)
```

- **静态部分**(不碰库):`SqlParser.parse(sql)` → 表名、复杂度。UPDATE/DELETE 才跑。
- **动态部分**(碰库):主键探测(复用 `primaryKeyColumns` 的查询逻辑)+
  `SELECT COUNT(*) ... WHERE 同条件`。**只在
  `写操作 && (审批开关开 || dryRun)` 时跑**——普通直执行路径零额外开销。
- **连接纪律(本设计最重要的一条)**:动态预检**自己开连接、查完立即还**,
  绝不带着连接进下一个 Stage。因为下一个 Stage 是审批,会阻塞最长 10 分钟,
  且 `ApprovalGate.MAX_WAITERS = 8`——8 个等待者各占一个池连接就把连接池抽干了,
  别的查询一个都进不来。时序必须是:**借连接 → COUNT → 还连接 → 阻塞等审批 → 重新借连接执行**。
- 预检结论是**参考值**:批准可能发生在 10 分钟后,数据已变。真正的保护是执行时
  重读原始行生成恢复 SQL(那一步在 Backend 里,和现在一样)。结果里如实叫
  `estimatedRows`,审批 UI 展示时带时间戳。

这一步落地后,清单里「P1 审批详情补预检」只剩 UI 展示工作。

### 4.3 ApprovalStage

搬 `requireApproval`(`:220-229`)。一处增强:summary 现在是
`sqlType + " · " + 摘要`,有预检时变成
`"UPDATE · 约 1,240 行 · 可恢复 · " + 摘要`——审批人第一眼看到影响面。
`detail` 依旧放完整 SQL。阻塞语义、超时、上限全部不动(`ApprovalGate` 原样)。

由 §4.2 的连接纪律保证:进入本 Stage 时**当前任务不持有任何连接**。

### 4.4 CipherStage(唯一有 after 的 Stage——对称性就是它存在的理由)

- `before`:`ctx.sql = sqlCipherParser.rewrite(ctx.sql, options)`(搬 `:94`)。
- `after`:遍历 `ctx.result.rows`,对 `options.shouldDecrypt(columnLabel)` 命中的列
  逐值 `SM4Utils.decrypt`。**这段逻辑从 `QueryResultRenderer.normalizeValue` 挪出来,
  渲染器里的解密分支删除**——加密和解密终于在同一个类的前后两半,渲染器退化成纯格式化。
  Yearning 结果同样流过这里,天然获得解密(现在 Yearning 靠渲染器顺带解密,挪完行为不变)。

### 4.5 DialectStage

- `before`:`ctx.sql = sqlInterceptor.preprocess(config, ctx.sql)`(搬 `:95`),
  然后 `ctx.sqlType = detectSqlType(ctx.sql)` 重检(搬 `:97`)。
- 保留现状语义并显式写进注释:**审批用改写前的类型,路由用改写后的类型**
  (现在就是这个顺序,`:70` 检出的类型给 `:78` 审批用、`:97` 重检的给 switch 用)。
  不"顺手修"这个不对称——它没造成过问题,改了反而要重验审批分类。

### 4.6 EffectiveLimits(不是 Stage)

```
maxRows:  request.maxRowsOverride ▸ alias.maxRows ▸ settings.queryDefaults.maxRows ▸ 1000
timeout:  request.timeoutSecondsOverride ▸ alias.timeoutSeconds ▸ settings.queryDefaults.timeoutSeconds ▸ 60
0 = 不限制
```

它不拒绝、没有 after、纯配置运算,做成 Stage 是凑数——在 `newContext` 里由
`LimitsResolver` 一次算完。新增配置:`SettingsConfig.queryDefaults`(两个 int 字段)、
`DatabaseConfig.maxRows / timeoutSeconds`(Integer,可空)。

**这是有意的行为变更**:CLI 查询从无上限变为默认 1000 行 + 60s。理由在清单里写过
(最需要保护的是刚接入的库);逃生口 `--max-rows 0`;csv/table 截断时**stderr**
打一行警告(不污染 stdout 数据流),json 加 `truncated` 字段——
`JsonContractSnapshotTest` 的快照要**有意识地**更新,这不是回归,是契约演进。

---

## 5. Backend 规格

### 5.1 选择

```java
private SqlBackend backendFor(SqlTaskContext ctx) {
    return "yearning".equalsIgnoreCase(ctx.config.getAccessMode()) ? yearning : jdbc;
}
```

就一个三目。第三个后端(比如另一个工单系统)出现时再谈注册,现在两个实现一个三目最诚实。

### 5.2 JdbcBackend

搬入并保持原样的方法:`executeUpdateWithRecovery`、`primaryKeyColumns`、
`readPrimaryKeys`、`safeCatalog`、`safeSchema`、`unquote`、`executeDdl`、
路由 switch 全体(`:110-158`,含各 policy 门控和 ClickHouse ALTER 分支)。

**改动只有三处:**

**① Statement 纪律(解决 setMaxRows 截断恢复备份的陷阱——结构化解法,不靠小心):**

```java
try (Connection conn = connectionManager.getConnection(ctx.config)) {
    if (isRead(ctx.sqlType)) {
        try (Statement stmt = conn.createStatement()) {
            if (limits.maxRows() > 0)  stmt.setMaxRows(limits.maxRows() + 1);   // n+1 探测截断
            if (limits.timeout() > 0)  stmt.setQueryTimeout(limits.timeout());
            readInto(ctx, stmt);
        }
    } else {
        try (Statement stmt = conn.createStatement()) {
            if (limits.timeout() > 0)  stmt.setQueryTimeout(limits.timeout());
            // 不设 maxRows:恢复流程要在这个 Statement 上读原始行,
            // 截断它 = 改 5000 行只备份 1000 行,回滚时才发现少了 4000 行
            writeInto(ctx, conn, stmt);   // 原 switch 的写分支
        }
    }
}
```

现在的代码(`:104` 一个 stmt 全程复用)读写共享一个 Statement,任何人后来在上面
设 maxRows 都会踩中恢复截断。**按用途分 Statement 之后这个坑在结构上不存在。**

**② 读路径物化 + 截断:**

```java
private void readInto(SqlTaskContext ctx, Statement stmt) throws SQLException {
    long t0 = System.nanoTime();
    try (ResultSet rs = stmt.executeQuery(ctx.sql)) {
        // 列元数据 + 逐行读,读满 maxRows 行且 rs.next() 仍为 true → truncated
        // 逻辑同 SqlRerunController.execute 的循环,那段代码搬过来
    }
    ctx.result.elapsedMs((System.nanoTime() - t0) / 1_000_000);
}
```

替代 `executeQuery`(`:232-239`,现在把 ResultSet 直接塞给渲染器无限物化——
CH-P2-001 的病根)。将来 ClickHouse 流式输出 = `readInto` 换一个把行推给 sink 的
实现,只动这一个方法。

**③ 写路径的事务边界(P0 验收核心,现状没有事务):**

```java
conn.setAutoCommit(false);
try {
    // 1. SqlParser.parse + 复杂度检查                    (原 :255-261,不变)
    // 2. primaryKeyColumns 确认可恢复                     (原 :264-266,不变)
    // 3. 同连接 SELECT 原始行 → RecoveryBuilder.build     (原 :269-276,不变)
    // 4. recoveryResult.saveToFile() —— 落盘失败 → 抛出   (原 :279,不变:文件先于执行)
    // 5. audit.event(ctx, "recovery_saved", path)         (新增)
    // 6. stmt.executeUpdate(sql)                          (原 :282)
    conn.commit();
} catch (Exception e) {
    conn.rollback();       // 3/6 任何一步失败,读到一半的状态不留
    throw e;
} finally {
    conn.setAutoCommit(true);   // 连接回池前必须复原
}
```

和现状的差别就一件事:**第 6 步失败时第 3 步的读取在同一事务里被回滚**,
且 3→6 之间同连接保证读到的原始行和被改的行是同一份数据(隔离级别内)。
「执行成功但恢复未落盘」的窗口:第 4 步在第 6 步之前且失败即中止——这一条现状已成立,
事务补的是反方向(「恢复落了盘但执行失败」时文件残留无害,审计里标 failed)。

### 5.3 YearningBackend

`YearningQueryResult` 本来就是结构化的(`columns` + `rows` + `elapsedMs`),
渲染是 `YearningQueryExecutor.executeToString` 最后一步糊上去的。改法:

- `YearningQueryExecutor` 增加 `executeStructured(config, sql): YearningQueryResult`
  (把现在 `executeToString` 里 `render(...)` 之前的部分抽出来;
  `ensureReadOnlyQuery`、`resolveConfigForSql`、`qualifySql` 全部保留在里面)。
- `YearningBackend.execute` 调它,把 columns/rows 拷进 `ctx.result`
  (Yearning 不给列类型,`Column.jdbcTypeName` 置 null,渲染方已能处理)。
- `supportsWrites() = false` → 写语句在 GuardStage 拒绝。
- maxRows:对物化后的 rows 做事后截断并置 truncated。**文档注明**:Yearning 服务端
  可能先截,我们的 truncated 只反映本地上限,服务端截断感知不到——写进手册,不装作能感知。

### 5.4 结果解密去哪了

见 §4.4:在 CipherStage.after,对两个 Backend 一视同仁。
`QueryResultRenderer` 删掉 `normalizeValue` 里的解密分支后,
它对 `options` 的依赖只剩 format——v2 可以把参数缩成 format 字符串,v1 不动签名少生事。

---

## 6. 审计(TaskAuditRecorder)

| 时机 | 读操作 | 写操作 |
|---|---|---|
| `begin` | 无 | `task_run` 插一行:task_type=`sql_write`,status=`running`,alias,actor(origin 映射),sql_hash=SHA-256(原始 SQL);`ctx.taskId = id`。事件 `submitted` |
| Stage 通过后 | 无 | `task_event`:`guard` / `precheck`(payload=Precheck JSON)/ `approval` / `cipher` / `dialect` |
| Backend 内 | 无 | 事件 `recovery_saved`(payload=路径)、`executed`(payload=affectedRows) |
| `finish`(finally) | `sql_execution` 一行(status=success/failed/**rejected**,source=origin,rerun_of 透传) | 同左 + `recordRecoveryArtifact` + `task_run.status` 置 `succeeded/rejected/failed` |

- `sql_execution` 的写法沿用现有规则:只读语句存原文进 `raw_sql`,写语句只存脱敏文本
  (`RunStateStore` 内部已处理,模块只管调)。
- **status 增加 `rejected` 取值**——现在拒绝根本不落库(异常直接飞出去,
  `:159` 的 catch 只记 failed 且拒绝发生在更早的 throw),审批被拒 / readonly 拒绝
  在历史里完全无痕。这是修正,不是兼容负担;运行记录 UI 的状态筛选加一个值。
- `task_run.policy_revision` / `environment`:v1 置 null(policy 未接进执行链,不假装)。
- 进程崩溃留下 status=`running` 的 task_run + 半截事件——这正是审计的价值,不清理。
- `task status <id> --json`(清单 P2)= 按 id 读 task_run + 关联事件,模块落地后是纯查询。

---

## 7. 拒绝 / 失败对照表(错误模型验收标准)

| 情形 | Status | errorSummary 来源 | 落库 |
|---|---|---|---|
| readonly 别名 + 写 | REJECTED | Guard 文案(逐字沿用) | sql_execution=rejected |
| 多语句 | REJECTED | 同上 | 同上 |
| UPDATE 无 WHERE | REJECTED | `SqlParser.requireWhereClause` 的文案 | 同上 |
| Yearning + 写 | REJECTED | 沿用现文案 | 同上 |
| 审批被拒 / 超时 | REJECTED | `ApprovalDeniedException.getMessage()` | 同上 + task_run=rejected |
| policy 不允许(INSERT/DDL/...) | FAILED→**REJECTED** | Backend 里抛 `SqlTaskRejected` 替换现在的 `SQLException`(语义修正:这是策略拒绝不是执行失败) | 同上 |
| 无主键不可恢复 | REJECTED(同上理由) | 现文案 `"UPDATE rejected: ..."` | 同上 |
| 回滚脚本入库失败 | FAILED | 中文说明(运行库不可写),脱敏 | sql_execution=failed,**未执行** |
| 连接失败 / SQL 语法错 | FAILED | `JdbcUrlParser.redactSecrets` + rootCause(搬 `:159-172`) | failed |
| 查询超时 | FAILED | SQLTimeoutException,文案标明超时秒数 | failed |

---

## 8. 调用方迁移(前后对照)

### 8.1 共享的 options 工厂(先做,两边复用)

`SqlCli.java:195-220` 那 25 行 SM4 config 组装逻辑提取为:

```java
// QueryExecutionOptions 上的静态工厂
public static QueryExecutionOptions forAlias(DatabaseConfig config, String format,
        Set<String> extraDecryptColumns, boolean noDecrypt)
```

CLI 传 `--decrypt-cols` 进 extra;UI 重放传空集(纯用别名配置)——
**UI 重放由此获得 SM4 解密**,4b 注释里"本批不做"的欠账就此结清。

### 8.2 CLI(SqlCli.java:192-223)

```java
// 之前
queryExecutor.execute(config, sql, options);
// 之后
SqlTaskResult r = taskModule.execute(new SqlTaskRequest(config, sql, options,
        Origin.cli, null, cliMaxRows, cliTimeout, false));
System.out.print(new CliResultRenderer().render(r, options));   // §9.1 桥接
if (r.truncated()) System.err.println("警告: 结果已截断至 " + limits + " 行,--max-rows 0 可解除");
return switch (r.status()) { case SUCCEEDED -> 0; case REJECTED -> 1; case FAILED -> 1; };
```

### 8.3 UI 重放(SqlRerunController)

删掉私有 `execute()`(约 60 行裸 JDBC)和自带的审批调用,只留白名单 `validate()`:

```java
validate(sql);                              // 只读白名单是这个端点的业务规则,保留
SqlTaskResult r = taskModule.execute(new SqlTaskRequest(config, sql,
        QueryExecutionOptions.forAlias(config, "json", Set.of(), false),
        Origin.ui_rerun, executionId, null, null, false));
// r.columns/rows/truncated/elapsedMs 直接序列化成现在的响应体,字段名一致
```

一次迁移拿到四样:SM4 解密、历史留痕(source=ui_rerun + rerun_of)、
方言预处理、审批不再是复制的第二份。硬编码 `MAX_ROWS=500`/`TIMEOUT` 删除,走配置链。

### 8.4 工作台(未来,不在本次)

W2 读写分流 = 同一个 `execute()`:只读直接展示;写操作先
`dryRun=true` 拿 Precheck 画确认页,用户点提交再 `dryRun=false` 真跑
(审批开着就阻塞在 ApprovalStage)。**不需要模块提供任何新方法**——
这是入口设计对不对的试金石。

---

## 9. 现有代码去向表

| 现在(QueryExecutor 407 行) | 去向 |
|---|---|
| `execute` / `executeToString` 4 个重载 | 删除;`SqlCli` 直接持有 module + renderer |
| `:70-97` 检测/守卫/审批/改写/预处理 | 5 个 Stage(§4) |
| `:99-102` Yearning 分支 | `backendFor` + YearningBackend |
| `:104-158` 连接 + 路由 switch | `JdbcBackend`(switch 原样) |
| `:159-176` catch 脱敏 + rootCause | driver 的 `failed(e)` 分支 |
| `recordSuccess` ×2 | `TaskAuditRecorder.finish` |
| `executeQuery` | `JdbcBackend.readInto`(物化+截断,吸收 SqlRerunController 的读循环) |
| `executeUpdate` / `executeDdl` | JdbcBackend,原样;`formatAffectedRows` 移入 CliResultRenderer(它是渲染) |
| `executeUpdateWithRecovery` + PK 探测 5 个辅助 | JdbcBackend,原样 + 包上事务(§5.2③) |
| `requireSafeStandardDml` | GuardStage |
| `requireApproval` | ApprovalStage |
| `isWriteOperation` / `isReadonly` / `isYearning` / `rootCause` | 模块内工具(位置就近) |
| `QueryResultRenderer.render(ResultSet,...)` 重载 | 删除(物化进了 Backend);`render(headers, rows, ...)` 重载保留给桥接 |
| `normalizeValue` 的解密分支 | CipherStage.after |
| `SqlRerunController.execute` + cell 辅助 | 删除,读循环并入 `readInto` |
| `YearningQueryExecutor.executeToString` | 拆出 `executeStructured`,渲染壳保留一版随后删 |

### 9.1 CliResultRenderer(桥接,保证字节级兼容)

`SqlTaskResult` → 现有 `QueryResultRenderer.render(headers, rowsAsMaps, options, ...)`:
把 `List<List<Object>>` 按列名转回 Map 喂给老重载。csv/table 输出字节不变;
json 多一个 `truncated` 字段(快照有意更新);同名列在 CLI 侧保持旧的碰撞行为
(兼容优先,修复只给结构化消费方)。写路径的
`"UPDATE executed successfully. Affected rows: ..."` 文案由它从 `affectedRows` 拼出,
生成了回滚脚本时补一行提示(指向评审页的执行记录),并带上 `Task: <id>` 供后续 `task status` 查询。

---

## 10. 行为不变量(迁移的验收红线)

**必须逐字节/逐字不变:**
csv 与 table 输出;所有拒绝文案(readonly / 多语句 / 无 WHERE / Yearning 写 /
无主键 / policy 不支持);审批的阻塞语义与 summary 前缀结构;
SM4 加密改写结果;`sql_execution` 现有列的取值规则。

**有意变更(每条都要出现在提交说明里):**

| 变更 | 理由 |
|---|---|
| json 输出多 `truncated` 字段 | P0 验收:截断必须有信号 |
| CLI 默认 1000 行 / 60s 超时 | 清单已定;`--max-rows 0` 逃生 |
| 拒绝落库(status=rejected) | 现在拒绝无痕,审计缺口 |
| Yearning 写语句拒绝提前到 Guard | 文案同,时机早,连接不再白建 |
| UI 重放:留痕 + SM4 解密 | 4b 欠账 |
| policy 拒绝从 FAILED 改 REJECTED | 语义修正(是策略不是故障) |

---

## 11. 测试计划

**保持绿的存量测试**(它们就是兼容性契约):
`QueryExecutorSafetyTest`(重定向到 module)、`QueryExecutorClickHousePolicyTest`(18 个,
policy 门控留在 JdbcBackend,断言不变)、`JsonContractSnapshotTest`(仅 truncated 一处
有意更新)、`SqlRerunControllerTest`(validate 部分不变)。

**新增 `SqlTaskModuleTest`**(清单 P0 要求的五分支 + 本设计引入的行为):

| 用例 | 断言 |
|---|---|
| SELECT 走通 | SUCCEEDED,columns/rows/elapsed 填充,`sql_execution` 一行 success |
| readonly + UPDATE | REJECTED,文案逐字,落库 rejected,**未取连接**(mock ConnectionManager 验证) |
| 回滚脚本入库失败 | FAILED,`executeUpdate` 未被调用(第 4 步先于第 6 步) |
| 写失败回滚 | executeUpdate 抛错 → rollback 被调、autoCommit 复原 |
| Yearning + UPDATE | REJECTED 于 Guard,YearningClient 零调用 |
| 截断 | maxRows=2 查 3 行 → rows.size()==2,truncated=true |
| 恢复读不受 maxRows 影响 | maxRows=1 时 UPDATE 3 行 → 回滚脚本含 3 条 |
| dryRun | Precheck 填充,Backend 零调用,不进审批阻塞 |
| 审批被拒 | REJECTED,task_run=rejected,事件序列 submitted→guard→precheck→(approval 无) |
| limits 优先级 | request ▸ alias ▸ global ▸ 内置,0=不限 |
| 拒绝也审计 | 每个 REJECTED 用例断言 sql_execution 落了 rejected 行 |
| 重放留痕 | Origin.ui_rerun + rerunOf → `source='ui_rerun', rerun_of=id` |

---

## 12. 落地顺序(6 个可独立合并、可独立回滚的提交)

1. **骨架 + 平移**:新包全部类型;5 个 Stage / 2 个 Backend 按 §9 表搬代码;
   `SqlCli` 与桥接渲染器切到 module;limits 全 0(不限,行为完全不变);
   删 `QueryExecutor`,存量测试重定向。**这步结束输出字节不变、全部测试绿**——
   最大的一步,但每一行都是移动不是新写,靠存量测试兜底。
2. **limits 生效**:配置字段 + 优先级链 + n+1 截断 + stderr 警告 + json 字段 +
   快照有意更新 + 截断/恢复不受影响两个新测试。
3. **审计补全**:task_run/task_event 接线、rejected 落库、Origin/rerunOf 透传。
4. **UI 重放迁移**:SqlRerunController 删私有执行,走 module;
   顺手交付 SM4 解密与留痕;UI 运行记录状态筛选加 rejected。
5. **PrecheckStage + dryRun**:动态预检、连接纪律、审批 summary 增强
   (评审页此时自动显示影响行数)。
6. **收尾**:`YearningQueryExecutor` 删渲染壳;user-manual / SKILL / 测试用例文档同步;
   清单勾掉 P0。

每步合并后系统都可用;1 出问题整体回滚代价最低,2-6 彼此独立。

---

## 13. 决策记录(相对此前讨论的修正,含理由)

1. **三态 Decision 枚举 → 受检异常 + dryRun 标志。** 返回值靠人记得检查,
   异常靠编译器;SHORT_CIRCUIT 本质是调用方意图,不该由 Stage 中途决定。
2. **LimitStage 降级为 LimitsResolver。** 不拒绝、无 after、纯配置运算,
   做成 Stage 是为了凑对称,删。六个 Stage 变五个。
3. **动态预检必须在审批前、且先还连接再阻塞。** 这是把「审批详情要预检信息」
   和「阻塞不能占连接池」两个需求放在一起推出来的硬约束,此前两轮讨论都没接上这根线。
4. **恢复读取用独立无限制 Statement。** 清单原来写的是「setMaxRows 位置要小心」——
   靠小心的设计都是坏设计,分 Statement 后这个坑在结构上不存在。
5. **policy 门控不出路由 switch。** ALTER 的 ClickHouse mutation 分支证明耦合,拆了变两个同步维护的 switch。
6. **模块边界不抛业务异常。** 两个调用方已经各写了一套 catch,第三个调用方来之前统一掉。
7. **发现并绕开的暗 bug**:渲染器按 label 物化,`SELECT a.id, b.id` 丢列——
   结构化结果按位置存修复之。**2026-08-24 追加**:产品还没上线,不需要 §9.1 的
   `CliResultRenderer` 兼容桥接——直接改了 `QueryResultRenderer` 本身的签名
   （接收 `List<Column>` + `List<List<Object>>`），CLI 和 UI 共用同一份，
   全仓不留第二套按列名物化的实现。见 §15。

---

## 14. 评审时请重点确认的三个点(评审时提出,已用§15的方式解决)

1. **默认 1000 行 / 60s 是否接受**——评审结论:接受,已实现(`--max-rows 0` 逃生)。
2. **拒绝落库 status=rejected**——评审结论:接受,已实现,真库验证过(见 §15)。
3. **提交体量**——评审结论:不分批,一次做完(产品未上线,不需要保留兼容层降低单次改动量)。

---

## 15. 实现落地记录(2026-08-24)

> 本节记录"设计"和"最终代码"之间的出入,以及实现过程中发现的、设计阶段没预料到的问题。
> 全部已修正,341→342 个测试全绿,并在真实数据库（`erp_plush_test`，531 表）上做过
> smoke test（见下方"真库验证"）。

### 15.1 不做兼容层(相对设计文档最大的一处偏离)

产品还没上线,没有"存量调用方要保持字节不变"这个约束,所以第 9.1 节的
`CliResultRenderer` 桥接**没有实现**——`QueryResultRenderer` 直接改签名接收
`List<Column>` + `List<List<Object>>`,CLI 和 UI 共用同一份实现。第 10 节"行为不变量"
里"csv/table 输出必须逐字节不变"这条也相应放弃:UPDATE/DELETE 现在打印
`"UPDATE executed successfully. Affected rows: ..."` 文案由它从 `affectedRows` 拼出,
错误文案保留了原意但没有逐字核对。真正要守住的不是字节,是行为——
五分支测试、真库 smoke test 覆盖的是这个。

### 15.2 审计时机的修正:类型检测提前到 driver

设计文档 §4.1 把类型检测放在 `GuardStage.before()` 第一步。实现时发现一个时序问题:
`TaskAuditRecorder.begin(ctx)` 在 Stage 循环**之前**调用（要先建 `task_run` 才能让
后续 Stage 的 `audit.event()` 有地方挂),但它需要知道 `ctx.sqlType` 才能判断
"是不是写操作、要不要建 task_run"。如果类型检测留在 `GuardStage.before()`,
`begin()` 执行时 `ctx.sqlType` 还是 null,`isWriteOperation(null)` 直接 NPE
(`Set.of(...)` 不接受 null)。

修正:类型检测挪到 `SqlTaskModule.execute()` 里,创建 `ctx` 之后、`audit.begin()` 之前,
做一次;`GuardStage.before()` 不再重复检测,直接读 `ctx.sqlType`。这不是设计缺陷的
权宜之计,是审计天然要求"先知道类型"这个依赖关系的必然结果——单元测试
（`SqlTaskModuleTest` 六个 REJECTED 用例）第一次跑就在这里报了 NPE,后来才修正。

### 15.3 结果字段直接放在 Context,不经过 Builder 的增量写入

设计文档 §3.3 暗示 Stage/Backend 会往 `SqlTaskResult.Builder` 里增量写。实现时改成:
`SqlTaskContext` 直接持有可变字段 `resultColumns` / `resultRows` / `resultTruncated` /
`affectedRows` / `recoveryId`,`Builder` 只在 driver 的最后一步、拿这些字段组装一次性
构造。原因:`CipherStage.after` 要"读出上一步 Backend 写的行、原地解密一遍",
一个只进不出的 Builder 做不到"读出已经写过的值"这件事。

### 15.4 precheck 的审计负载:补上设计里漏掉的一步

设计文档 §6 的审计表格写着 precheck 阶段的 `task_event.payload` = "Precheck JSON",
但第一版实现里 `TaskAuditRecorder.event()` 一直传 `null`——预检算出来的
预估影响行数、恢复能力,过了这个方法调用就彻底丢了,评审页将来想读也读不到。
补上:`event()` 现在按 stage 名判断,`"precheck".equals(stageName)` 且
`ctx.precheck != null` 时把 `Precheck` record 序列化成 JSON 存进 payload。
`SqlTaskModuleTest.dryRunCollectsPrecheckWithoutExecutingOrBlockingOnApproval`
钉住这条路径(dry-run 场景下预检产生 recoverySupported/estimatedRows,且从不建写
Statement)。

### 15.5 复用了一个读代码时发现的既有死字段

`DatabaseConfig.defaultQueryLimit`(默认 100)在 `AliasResolver` 里有完整的读取链路
（`params.defaultQueryLimit` → `config.setDefaultQueryLimit`），但全仓搜索**没有任何地方
消费它**——纯粹的死配置项,写了却没人用。`EffectiveLimits.resolve()` 直接把它当
`maxRows` 的别名级来源,没有新增字段。同理新增了 `queryTimeoutSeconds`(默认 30)
走同一条 `AliasResolver` 路径。

### 15.6 真库验证记录(erp_plush_test,531 表 / MySQL)

| 场景 | 命令/操作 | 结果 |
|---|---|---|
| 基本 SELECT | `sql-cli erp_plush_test "SELECT id,name FROM sys_user LIMIT 3" -f json` | 返回 `truncated:false`,中文字段值正确 |
| 截断信号 | 同上 + `--max-rows 3`,表实际行数更多 | `rowCount:3, truncated:true`;stderr 打印警告;`-f csv`/`-f table` 同样正常 |
| 多语句拒绝 | `"SELECT 1; SELECT 2"` | `exit=1`,`sql_execution` 新增一行 `status='rejected'`（此前拒绝完全无痕） |
| `tables` 命令 | `sql-cli erp_plush_test tables -p "%sys_user%"` | 渲染器签名改动后仍正常出表格,证明非 SQL 执行路径的调用点也修对了 |
| UI 历史重放 | `POST /api/sql/execute`,携带 `executionId` | 返回结构化 JSON,中文值经过 SM4/字符集正确解码；`sql_execution` 新增一行 `source='ui_rerun', rerun_of=<原id>` —— 4b 遗留的"重放不留痕"问题在真库上验证已解决 |

（验证方法说明:CLI 输出通过写入文件再显式按 UTF-8 读取校验,不是直接在终端看——
本机终端默认 GBK 代码页,直接打印中文会看到乱码,那是终端编码问题,不是产品编码问题,
翻译层的 UTF-8 字节本身是对的。）

### 15.7 没有做但记录在案的簿记事项

- `com.sqlcli.task` 包内的 12 个类除公开入口（`SqlTaskModule`/`SqlTaskRequest`/
  `SqlTaskResult`）外全部包内可见,`Precheck` 因为要被 Jackson 序列化和被 CLI/UI 读取
  升级成了 `public record`。
- `YearningQueryExecutor.executeToString` 被删除、`render(...)` 私有方法一并删除,
  `getTableDdl`/`test` 两个不相关的公开方法未受影响（不在这次改动范围内）。
- CLI 的 `--max-rows` 只在查询命令上加了选项解析,没有影响 `tables`/`ddl`/alias 系列命令。

### 15.8 执行中止(v2,2026-08-24 落地)

§1.3 留的那条缝兑现了,结构没改:

- `SqlTaskRequest` 增加第 9 个字段 `cancelToken`(旧 8 参构造保留,委托传 null)。
  前端每次真执行生成一个随机令牌带上;CLI 不传——终端 Ctrl+C 就是它的中止。
- `SqlTaskCancelRegistry`:进程级 `ConcurrentHashMap<token, Runnable>`。
  值放宽成 Runnable 而不是设计里写的 Statement,因为可中止的东西有两种,先后接力:
  - **JDBC 执行中**:`JdbcBackend` 建好 Statement 后注册 `stmt.cancel()`,
    读写两条路径都注册,执行结束(成功/失败)在 finally 里注销。
    写路径被 cancel 打断时顺着 `executeWithRecovery` 的 catch 回滚,不留半截事务。
  - **审批等待中**:此时还没有 Statement。`ApprovalStage` 注册等待线程的
    `interrupt()`,`ApprovalGate` 的轮询 sleep 被打断后把该审批置 expired 并抛
    ApprovalDenied → 任务以 REJECTED 收场。finally 里注销并清中断标志
    (HTTP 池线程带着标志回池会毒害下一个请求)。
- 端点 `POST /api/workbench/cancel`(body `{cancelToken}`)调 `Registry.cancel`,
  答 `{cancelled: bool}`;false 表示执行已结束,不是错误。
- **覆盖不到的窗口**:建连接那几秒注册表里还没有条目,此时中止答 false。
  预检(dry-run)不注册——它是一次快速 COUNT,不值得中止。
- 前端:执行中出现「中止」按钮;cancel 答 true 后,被打断的 FAILED/REJECTED
  结果统一显示为「已中止」而不是报错。

---

## 16. AppContext 前置项:查证后取消(2026-08-24)

开发清单原本把「手写组合根 `AppContext`」列为本模块的前置项,理由是
「长生命周期组件建一次向下传,后面提取 Module 时每个调用点少改一遍依赖获取」。
Module 落地后逐项查证,结论是**不做**,三条证据:

### 16.1 占比最大的 `AliasResolver`(9 处 new)必须保持每次新建

`AliasAdminController.refreshLiveConfig` 的注释已经把原因写死了:

> 改一次别名(包括勾一下审批开关)就足以毁掉这个进程的连接能力。
> `AliasResolver` 才是唯一会做驱动展开的入口,所以刷新必须走它。

UI 改完别名会 `new AliasResolver().resolve(name)` 重新展开 `driverRef` 再塞回内存快照。
把它缓存成单例**等于重新引入这个已经修掉的 bug**。9 个调用点里占大头的正是它。

### 16.2 第二大的 `SecretResolver`(8 处 new)没有状态

只持有 `EncryptedSecretStore` + `KeyringSecretStore`,两者都不缓存,构造是纯赋值。
共享一个实例省不下任何东西。

### 16.3 唯一真有构造成本的 `GraphWorkspaceStore` 已经是每进程一次

它构造 3 个 Jackson `ObjectMapper`,确实不便宜。但 5 个调用点全是每进程/每命令一次:
`SchemaActionCommand` 每次 CLI 运行一次、`GraphUiServer` 启动时两次、
`WorkspaceImportService` 与 `WorkspaceMutationController` 那两处是**便捷构造函数重载**
(`this(..., new GraphWorkspaceStore())`),而 `GraphUiApiRouter` 走的是 5 参数版本、
传自己持有的那一个。**没有一处是每请求构造的。**

### 16.4 原定收益已经兑现

清单给的理由是「后面提取 Module 时少改一遍依赖获取」。Module 已经提完,且用的就是
构造函数注入(`SqlTaskModule(ConnectionManager, RunStateStore, ApprovalGate)`),
测试直接用 3 参数版本注入 mock。可测性这个收益不需要再补一个 AppContext 去换。

### 16.5 真正剩下的隐式全局依赖是另一个问题

`SettingsConfig.getInstance()` 有 8 处静态调用,穿透在 `AliasResolver` / `DriverResolver` /
`GraphWorkspaceStore` / `EncryptedSecretStore` 里。这是真的隐式全局依赖,但属于
「静态单例 → 可注入」这个独立课题,改它要动配置加载的整条链路,影响面比本次改造还大。
AppContext 顺手解决不了它——记在这里,等真有人被它卡住再单独立项。

---

## 17. 恢复执行（回滚的实际执行,2026-08-24 追加设计）

> 此前只有「回滚预览」（`GET /api/executions/{id}/recovery`,只看不执行）。
> 本节定义真正执行回滚脚本的受控路径:边界、审批、事务、审计,先设计后动手。
>
> **脚本存在哪**:`recovery_artifact.rollback_sql` / `backup_text`(SQLite 运行库)。
> 早期版本写 `~/.sql-cli/recovery/*.sql`,那些老记录仍能读,但不再产生新文件——
> 一份数据两个存储位置、还要靠注释标记切段落,比存两列贵得多。

### 17.1 核心矛盾:回滚脚本是多语句,Module 是单语句

回滚脚本天然是**多语句**——DELETE 3 行的恢复是 3 条 INSERT,UPDATE N 行的恢复是
N 条带主键 WHERE 的反向 UPDATE。而 `SqlTaskModule` 的 `GuardStage` 拒绝多语句,
这条红线不为回滚开洞。

**为什么也不把回滚脚本拆开逐条走 Module**(这是本节最重要的决策记录):

1. **原子性要求单事务。** Module 一次调用 = 一条连接 + 一个独立事务
   (写路径 `setAutoCommit(false) → commit`,见 §5.2③)。逐条调用 N 次就是 N 个
   互相独立的事务——第 3 条失败时前 2 条已经 commit,回滚做了一半,数据库停在一个
   **既不是执行前也不是执行后的第三种状态**。这恰恰是回滚最不能接受的结果。
   恢复执行的正确语义是"全部成功才算回滚成功,任何一条失败就当作什么都没发生",
   只有单连接单事务能给出这个保证。
2. **Module 的安全链对恢复语句是错配的。** 恢复 INSERT 走 Module 会再触发审批
   （每条一次,N 条 INSERT = N 次审批）;恢复 UPDATE 走 Module 会再生成一份
   "恢复的回滚脚本"——套娃。恢复语句是我们自己在写操作执行前生成并入库的,
   不是用户输入,Guard/Precheck/Cipher 那套针对"人给的 SQL"的防线在这里没有对象。

**不绕过审批、不绕过审计,就没有破坏"单一入口"的精神**:单一入口挡的是
"绕开审批与审计直接碰库"的第二条路径,恢复执行器带着自己的审批闸门
（复用 `ApprovalGate`）和自己的审计落库（`sql_execution`,source=recovery）,
是一条**平行的受控路径**,不是后门。

### 17.2 流程

```
UI 执行记录（有回滚脚本的写操作行）点「执行回滚」
   │ POST /api/executions/{id}/rollback
   ▼
GraphUiApiRouter.handleExecutionRollback
   ① 找执行记录（RunStateStore.findExecution）→ 别名 → DatabaseConfig
   ② readonly 别名直接拒绝（回滚要写库,连审批都不建）
   ③ RecoveryExecutor.loadScript:
        recovery_artifact 里找文件路径（findRecoveryPath）
        → 校验文件存在、可读、"-- Recovery SQL:" 段非空
        → 引号感知切分出语句列表（值里可以含 ';' 和换行,不能按行切）
   ④ ApprovalGate.awaitApproval(kind=recovery) —— 无条件审批,不看别名开关
        summary = "回滚执行记录 #id · N 条语句",detail = 完整回滚 SQL
        阻塞至评审页裁决(10 分钟超时作废,沿用闸门语义)
   ⑤ RecoveryExecutor.execute:单连接单事务逐条执行
   ⑥ 审计落 sql_execution,返回结果
```

- **审批无条件**:查询/更新/图谱审批是按别名开关的,回滚不是——它低频、高危、
  且发起入口只在 UI,永远走 `kind=recovery` 审批。这也让评审页天然看得到
  待回滚项(类型筛选加"回滚")。
- HTTP 请求在 ④ 挂起,和工作台写审批同一个模式,线程池已按 `MAX_WAITERS` 留量。

### 17.3 事务边界(验收红线)

```java
try (Connection conn = connectionManager.getConnection(config)) {
    conn.setAutoCommit(false);
    try {
        for (String sql : statements) stmt.executeUpdate(sql);
        conn.commit();                      // 全部成功才提交
    } catch (Exception e) {
        conn.rollback();                    // 任一失败,整体回到执行前
        throw ...;                          // 带"第 N 条失败,已整体回滚"的中文说明
    } finally {
        conn.setAutoCommit(true);           // 连接回池前复原
    }
}
```

### 17.4 审计

每条语句和整体结果都落 `sql_execution`,`source='recovery'`,
`rerun_of=<原执行记录 id>`(列早已存在):

| 情形 | 落什么 |
|---|---|
| 全部成功 | 每条语句一行 `status=success` + 一行整体 `status=success`(masked_sql 为"回滚执行记录 #id:N 条语句"摘要) |
| 第 N 条失败 | 失败那条一行 `status=failed`(带脱敏错误)+ 一行整体 `status=failed`;**前 N-1 条不落 success 行**——它们已随事务回滚,没有生效,落 success 行会让审计声称发生了没发生的事 |

### 17.5 明确不做(记录在案)

- **不做部分回滚 / 断点续跑**:失败即整体回滚,人看审计后自行处置。
- **不校验 recovery_artifact.checksum**:文件在用户自己的 `~/.sql-cli/recovery` 下,
  威胁模型是误操作不是篡改;审批 detail 里摆的就是将要执行的全文,审批人看的即所执行的。
  真出现共享部署再补。
- **不接 CLI 入口**:入口唯一,回滚在 UI 执行记录里(评审页);CLI 用户拿着回滚脚本本来就能
  自己执行(那是他自己的决定,不是产品路径)。
