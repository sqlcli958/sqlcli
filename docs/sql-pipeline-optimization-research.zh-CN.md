# SQL 执行管线优化调研：责任链 vs MyBatis 插件模式

> 调研日期：2026-06-20
> 范围：分析当前 SQL 解析→加密→审计→执行→解密→渲染管线的优化可行性，不做开发

---

## 1. 当前管线现状

### 1.1 执行流程（QueryExecutor.executeToString）

```
SQL 输入
  │
  ▼
┌─────────────────────────────┐
│ 1. SQL 类型检测              │  SqlStatementAnalyzer.detectSqlType()
│    (JSqlParser AST / 首词兜底) │
└──────────┬──────────────────┘
           ▼
┌─────────────────────────────┐
│ 2. 只读保护                  │  isWriteOperation() + config.getReadonly()
│    写操作在 readonly alias 下拒绝 │
└──────────┬──────────────────┘
           ▼
┌─────────────────────────────┐
│ 3. 策略解析                  │  DatabaseStrategies.resolve(config)
│    获取 capabilities + policy │
└──────────┬──────────────────┘
           ▼
┌─────────────────────────────┐
│ 4. 多语句检测                │  SqlStatementAnalyzer.splitStatements()
│    policy 不允许时拒绝        │
└──────────┬──────────────────┘
           ▼
┌─────────────────────────────┐
│ 5. SM4 加密改写              │  Sm4SqlCipherParser.rewrite()
│    WHERE/INSERT/UPDATE 中明文│  → SM4Utils.encrypt() 替换为密文
│    值按 decryptColumns 加密   │
└──────────┬──────────────────┘
           ▼
┌─────────────────────────────┐
│ 6. 方言预处理                │  SqlInterceptor → Strategy.preprocessSql()
│    去分号、追加 LIMIT、       │  ClickHouse: FORMAT/SETTINGS 感知
│    ClickHouse SHOW 不加 LIMIT │
└──────────┬──────────────────┘
           ▼
┌─────────────────────────────┐
│ 7. 重新检测 SQL 类型          │  加密改写后 SQL 可能变化，需重新检测
└──────────┬──────────────────┘
           ▼
┌─────────────────────────────┐
│ 8. Yearning 分支             │  accessMode=="yearning" 时走 HTTP 通道
│    跳过所有 JDBC 执行路径     │
└──────────┬──────────────────┘
           ▼
┌─────────────────────────────┐
│ 9. 策略门控执行              │  SqlExecutionPolicy 检查
│    SELECT → executeQuery     │  INSERT → executeUpdate
│    UPDATE/DELETE → recovery  │  DDL → executeDdl
│    ClickHouse 拒绝标准 DML   │
└──────────┬──────────────────┘
           ▼
┌─────────────────────────────┐
│ 10. 结果渲染 + SM4 解密      │  QueryResultRenderer.render()
│     decryptColumns 中的列    │  → SM4Utils.decrypt() 逐值解密
│     自动解密                 │
└─────────────────────────────┘
```

### 1.2 当前问题分析

| 问题 | 位置 | 严重度 |
|------|------|--------|
| **流程硬编码在 QueryExecutor** | executeToString() 中 10 个步骤直接写死 | 高 |
| **步骤间耦合** | SM4 加密改写依赖 SQL 类型检测的结果，但无显式依赖声明 | 中 |
| **扩展困难** | 新增一个"SQL 审计日志"环节需要改 QueryExecutor 本体 | 高 |
| **重复解析** | SQL 被 JSqlParser 解析了 3 次：类型检测、加密改写、恢复 SQL 生成 | 中 |
| **输出侧逻辑分散** | SM4 解密在 QueryResultRenderer 中，与加密改写在 Sm4SqlCipherParser 中不对称 | 中 |
| **Yearning 分支割裂** | Yearning 有独立的 SQL 类型校验、数据库推断、表名限定逻辑 | 低 |

---

## 2. 责任链模式分析

### 2.1 核心思想

将当前 QueryExecutor 中的固定步骤拆解为独立的 Handler，通过链式调用传递 SQL 上下文：

```
HandlerContext(SQL, Config, Options)
    → TypeDetectionHandler
    → ReadonlyGuardHandler
    → MultiStatementHandler
    → CipherRewriteHandler
    → DialectPreprocessHandler
    → PolicyGateHandler
    → ExecutionHandler
    → DecryptionHandler
    → RenderHandler
```

### 2.2 三种责任链实现方式对比

| 维度 | Servlet Filter 式 | Spring Interceptor 式 | Apache Commons Chain 式 |
|------|-------------------|----------------------|------------------------|
| **接口** | `doFilter(ctx, chain)` | `preHandle/postHandle/afterCompletion` | `execute(ctx) → boolean` |
| **继续/中断** | 调用/不调用 chain.doFilter() | preHandle 返回 true/false | execute 返回 false/true |
| **前后处理** | 对称（doFilter 前后） | 显式三阶段 | 仅前向 |
| **上下文传递** | Request/Response 对象 | Request/Response/ModelAndView | Context(Map) |
| **框架耦合** | Servlet API | Spring MVC | 无 |

### 2.3 推荐方案：Servlet Filter 变体

最适合本项目的是 **Servlet Filter 变体**（对称前后处理），原因：

1. **对称处理需求明确**：加密改写（前）→ 解密输出（后）、策略门控（前）→ 恢复 SQL 生成（后）、连接获取（前）→ 连接释放（后）
2. **链式中断语义清晰**：只读保护、多语句检测、策略拒绝都需要中断链
3. **上下文对象自定义**：不需要 Servlet API，自定义 `SqlContext` 即可

设计草图：

```java
// 上下文对象 —— 贯穿整条链
public class SqlContext {
    String originalSql;          // 原始 SQL
    String processedSql;         // 处理后的 SQL
    String sqlType;              // SQL 类型
    DatabaseConfig config;
    QueryExecutionOptions options;
    DatabaseStrategy strategy;
    SqlExecutionPolicy policy;
    DatabaseCapabilities capabilities;
    Connection connection;        // 执行阶段设置
    ResultSet resultSet;          // 执行阶段设置
    String output;               // 最终输出
    List<String> warnings;       // 收集的警告
    // ... 其他中间状态
}

// Handler 接口
public interface SqlHandler {
    void handle(SqlContext ctx, SqlHandlerChain chain) throws Exception;
}

// 链对象
public interface SqlHandlerChain {
    void next(SqlContext ctx) throws Exception;
}
```

### 2.4 责任链的优缺点

**优点：**

| 优点 | 说明 |
|------|------|
| 步骤解耦 | 每个 Handler 独立开发、测试、替换 |
| 开闭原则 | 新增审计日志 Handler 无需改 QueryExecutor |
| 灵活编排 | 不同数据库类型可组装不同的 Handler 链 |
| 可中断 | 任何 Handler 可直接 return 不调用 chain.next() |
| 对称处理 | 加密/解密、连接获取/释放在同一 Handler 的前后对称处理 |

**缺点：**

| 缺点 | 说明 |
|------|------|
| 调试困难 | 链式调用栈深，异常栈追踪不直观 |
| 性能开销 | 每个节点一次方法调用 + 上下文传递，实际可忽略 |
| 过度设计风险 | 当前只有 10 个步骤，拆成 10 个 Handler 可能过度 |
| 上下文膨胀 | SqlContext 需要承载所有中间状态，容易变成大杂烩 |
| 隐式依赖 | Handler 之间的执行顺序依赖（如类型检测必须在策略门控之前）无法在编译期保证 |
| Yearning 分支 | Yearning 是完全不同的执行路径，不适合线性链 |

---

## 3. MyBatis 插件模式分析

### 3.1 核心思想

MyBatis 的 Interceptor 机制基于 **JDK 动态代理**，通过 `Plugin.wrap()` 嵌套包装目标对象：

```
Proxy_C(Proxy_B(Proxy_A(RealObject)))
```

每个 Interceptor 通过 `@Signature` 声明拦截的方法，`Invocation.proceed()` 驱动链式传递。

MyBatis 有四个拦截点：
- **Executor** — 顶层执行编排（query/update/commit）
- **StatementHandler** — JDBC Statement 级（SQL 改写、参数设置）
- **ParameterHandler** — 参数处理（参数加密）
- **ResultSetHandler** — 结果处理（结果解密）

### 3.2 适配到 sqlcli 的映射

| MyBatis 拦截点 | sqlcli 对应阶段 | 可替换为 Interceptor |
|---------------|----------------|---------------------|
| Executor.query/update | QueryExecutor 的 switch 分发 | ✅ 策略门控、只读保护 |
| StatementHandler.prepare | SqlInterceptor.preprocess | ✅ 方言预处理、LIMIT 追加 |
| ParameterHandler.setParameters | Sm4SqlCipherParser.rewrite | ✅ SM4 加密改写 |
| ResultSetHandler.handleResultSets | QueryResultRenderer.render | ✅ SM4 解密 |

### 3.3 MyBatis 插件模式的优缺点

**优点：**

| 优点 | 说明 |
|------|------|
| 声明式拦截 | @Signature 精确声明拦截哪个方法，意图清晰 |
| 无侵入 | 不改目标对象代码，纯代理增强 |
| 多拦截点 | SQL 改写、参数加密、结果解密各拦截点天然分离 |
| 社区验证 | MyBatis 生态大量实践（PageHelper、SQL 审计插件等） |

**缺点：**

| 缺点 | 说明 |
|------|------|
| **严重过度设计** | sqlcli 不是 ORM 框架，没有 Executor/StatementHandler/ParameterHandler/ResultSetHandler 这些抽象层 |
| 代理开销 | JDK 动态代理每次方法调用都有反射开销，CLI 工具无意义 |
| 接口重构量巨大 | 需要先抽象出四大 Handler 接口，再为每个接口写实现，再写拦截器 |
| 调试极难 | 嵌套代理 + 反射调用，断点和异常栈都不友好 |
| 拦截顺序反直觉 | 最后注册的 Interceptor 最先执行（LIFO），容易出错 |
| 本质不匹配 | MyBatis 插件是为"在 ORM 内部织入横切逻辑"设计的，sqlcli 是 CLI 工具，没有 ORM 内部层 |

---

## 4. 综合对比与推荐

### 4.1 三种方案对比

| 维度 | 保持现状 | 责任链模式 | MyBatis 插件模式 |
|------|---------|-----------|-----------------|
| **代码改动量** | 0 | 中（重构 QueryExecutor + 新增 6~8 个 Handler） | 极大（抽象四大接口 + 实现代理机制 + 迁移所有逻辑） |
| **可扩展性** | 差（改 QueryExecutor 本体） | 好（新增 Handler 即可） | 好（新增 Interceptor 即可） |
| **调试友好度** | 高（顺序执行） | 中（链式调用） | 低（嵌套代理 + 反射） |
| **性能影响** | 无 | 微乎其微（多几次方法调用） | 有（反射 + 代理开销） |
| **学习成本** | 无 | 低（责任链是常见模式） | 高（需理解代理机制和四个拦截点） |
| **与项目契合度** | — | 高（管线是线性的） | 低（没有 ORM 抽象层） |
| **过度设计风险** | 无 | 低~中 | **极高** |
| **Yearning 适配** | 直接 if 分支 | 不适合线性链（需分支） | 不适合 |

### 4.2 推荐结论

**不建议采用 MyBatis 插件模式**，原因：

1. **本质不匹配**：MyBatis 插件解决的是"在 ORM 框架内部织入横切逻辑"，sqlcli 没有 ORM 内部的四大抽象层（Executor/StatementHandler/ParameterHandler/ResultSetHandler），强行抽象这些层等于重写整个项目
2. **过度设计**：JDK 动态代理 + @Signature 声明 + Plugin.wrap 嵌套包装，这套机制对一个 CLI 工具来说过重
3. **调试困难**：代理链 + 反射调用会让异常栈变成噩梦

**可以谨慎考虑责任链模式**，但需注意：

1. **仅在步骤确实需要灵活扩展时才做**——如果未来只增加 1~2 个环节（如 SQL 审计日志），直接在 QueryExecutor 里加 if 更简单
2. **不要拆得太细**——10 个步骤拆成 10 个 Handler 是过度设计；建议按关注点合并为 4~5 个 Handler
3. **Yearning 分支不适合线性链**——需要在链的某个节点做条件分支（类似 Servlet Filter 中的 URL pattern 匹配）

### 4.3 务实建议：折中方案

不做全量重构，而是做 **局部改造 + 预留扩展点**：

#### 方案 A：Handler 列表 + 简单循环（推荐）

```java
// 定义 SQL 处理接口
public interface SqlProcessor {
    /** @return 处理后的 SQL，null 表示拒绝执行 */
    String process(SqlProcessContext ctx);
}

// 上下文
public class SqlProcessContext {
    String sql;
    String sqlType;
    DatabaseConfig config;
    QueryExecutionOptions options;
    DatabaseStrategy strategy;
}

// QueryExecutor 中
List<SqlProcessor> preProcessors = List.of(
    new TypeDetectionProcessor(),     // 类型检测
    new ReadonlyGuardProcessor(),     // 只读保护
    new MultiStatementProcessor(),    // 多语句检测
    new Sm4CipherProcessor(),         // SM4 加密改写
    new DialectPreprocessProcessor(), // 方言预处理
    new PolicyGateProcessor()         // 策略门控
);

// 执行前处理
for (SqlProcessor p : preProcessors) {
    String result = p.process(ctx);
    if (result == null) break; // 拒绝执行
    ctx.sql = result;
}
```

**优点**：简单直接，不需要链对象，不需要代理；List 循环人人能看懂。
**缺点**：只能做前向处理（不能对称处理加密/解密）。

#### 方案 B：前后对称的 Handler 列表

```java
public interface SqlHandler {
    /** 前处理，返回 true 继续，false 中断 */
    boolean before(SqlContext ctx);
    /** 后处理 */
    void after(SqlContext ctx);
}

// QueryExecutor 中
List<SqlHandler> handlers = List.of(
    new TypeDetectionHandler(),
    new ReadonlyGuardHandler(),
    new MultiStatementHandler(),
    new Sm4CipherHandler(),           // before: 加密改写, after: 解密输出
    new DialectPreprocessHandler(),
    new PolicyGateHandler(),
    new ExecutionHandler()             // before: 获取连接执行, after: 释放连接
);

// 前向
for (SqlHandler h : handlers) {
    if (!h.before(ctx)) break;
}
// 后向（反向遍历，只处理 before 成功的）
for (int i = completedIndex; i >= 0; i--) {
    handlers.get(i).after(ctx);
}
```

**优点**：对称处理（加密/解密在同一 Handler 前后对应），类似 Spring HandlerInterceptor。
**缺点**：比方案 A 稍复杂，需要跟踪 completedIndex。

### 4.4 不建议做的

| 不建议 | 原因 |
|--------|------|
| 全量重构为 MyBatis 插件模式 | 过度设计，改动量极大，收益不匹配 |
| 拆成 10 个独立 Handler | 每个步骤一个 Handler 太碎，增加认知负担 |
| 引入 Apache Commons Chain 依赖 | 一个外部依赖只为一个接口，不值得 |
| 当前立即重构 | 功能刚完成（ClickHouse 集成），应先稳定再重构 |

### 4.5 推荐的演进路线

```
阶段 0（当前）: 保持现状，QueryExecutor 中固定流程
  ↓ 等待触发条件：需要新增第 3 个以上横切关注点时
阶段 1: 引入 SqlProcessContext 上下文对象，提取公共参数
  ↓ 
阶段 2: 引入 SqlProcessor 接口 + List<SqlProcessor> 前处理链（方案 A）
  ↓ 如果需要对称处理（加密/解密）
阶段 3: 演进为 SqlHandler 接口 + before/after 对称链（方案 B）
  ↓ 永远不需要
阶段 X: MyBatis 插件模式（除非项目演变为 ORM 框架）
```

---

## 5. 附加发现：可独立优化的点

不依赖责任链重构，也可以立即改善的问题：

| 发现 | 当前状态 | 优化建议 |
|------|---------|---------|
| **SQL 重复解析** | 同一条 SQL 被 JSqlParser 解析 3 次（类型检测、加密改写、恢复 SQL） | 解析一次，缓存 AST，后续阶段复用 |
| **加密/解密不对称** | 加密在 Sm4SqlCipherParser，解密在 QueryResultRenderer | 统一到同一个"加解密处理器"中，即使不重构链 |
| **Yearning 逻辑冗余** | YearningQueryExecutor 重复了类型检测、表名限定等逻辑 | 让 Yearning 也走策略模式，复用方言预处理 |
| **策略门控散落** | 部分 policy 检查在 QueryExecutor switch 中，部分在策略方法中 | 统一收敛到 Strategy.canExecute(sqlType, policy) 方法 |
| **SQL 类型枚举缺失** | sqlType 是 String 字符串，编译期无保证 | 定义 SqlType 枚举，消除字符串比较 |

---

## 6. 结论

| 问题 | 回答 |
|------|------|
| 能否用责任链优化？ | **能**，但性价比取决于未来扩展需求。当前 10 个步骤固定且稳定，收益有限。 |
| 能否用 MyBatis 插件模式？ | **不建议**。项目没有 ORM 抽象层，强行引入代理机制是过度设计。 |
| 推荐做法？ | **保持现状 + 预留扩展点**。引入 SqlProcessContext 上下文对象作为第一步，待有第 3+ 个横切关注点时再抽取 Handler 链。 |
| 立即可做的优化？ | SQL 解析结果缓存（减少 3 次解析为 1 次）、加解密逻辑统一收敛、SqlType 枚举化。 |
