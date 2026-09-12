# 读代码提取关系、语义、血缘

## 动态 SQL 只能读，不能正则匹配

MyBatis Mapper XML 里的动态 SQL 是静态解析的盲区：

```xml
<select id="listOrders">
  SELECT o.*, u.name FROM orders o
  <if test="needUser"> LEFT JOIN users u ON o.buyer_id = u.id </if>
  <where> <if test="status != null"> AND o.status = #{status} </if> </where>
</select>
```

这段文本语法上是不完整的，但它表达的是**一条在特定条件下成立的 JOIN**。
所以 `grep` 只用来圈定候选文件，**提取要靠读**——退化成正则匹配就会把条件丢掉。

## 三类产出，三种来源

| 要提取的 | 主要藏在哪 | 写回用 |
|---|---|---|
| **JOIN 关系** | Mapper XML 的 `<select>`、`.sql` 文件、JPA `@JoinColumn`、手写 DAO | `schema add-relation --type join_observed` |
| **字段语义与值域** | 实体类字段注释、枚举类、常量类、`@Column` 注解、列注释 | `schema edit --business-name/--description/--semantic-type/--enum-values` |
| **字段血缘** | 一段代码把值写进一列：Service 里的赋值 / 计算 / 汇总 / 条件写入，视图定义、`INSERT ... SELECT`、ETL | `schema add-lineage --kind`，四类见下 |

**血缘最容易被忘掉，而它只能从这里来。** 没有任何扫描器会产出它，不写就永远是空的。
重构目的下它是最值钱的一类：「改这个字段哪些下游会跟着错、改动在哪个方法里」只有血缘答得出。

## 先比对，再写回：图谱和代码不一致时必须更新图谱

**这是读代码最有价值的产出，不是顺手的附带品。** 图谱里的语义大多来自列注释，
而代码是**实际在跑的那份**。两者一旦分叉，图谱就在骗人——它骗的是下一次查数的你自己：
枚举少了一个 `3=已退款`，下一个 agent 写出的 `WHERE status IN (0,1,2)` 会静悄悄少算一批订单，
不报错、不异常、结果看起来完全正常。**这种错只能在这一步防住，事后没有任何手段能发现它。**

所以动手写之前先看图谱现在写的是什么：

```bash
sql-cli <alias> schema describe <schema.table>    # 表和它所有字段的现有语义
```

| 图谱 vs 代码 | 做法 |
|---|---|
| 图谱没有 | 补上——本文其余部分讲的都是这件事 |
| 一致 | 什么都不做。别为「刷新一下」重写一遍，每次写入都是审批队列里的一条 |
| **不一致** | **更新，以代码为准**，并写清依据：哪个文件、哪个类 / 哪段 SQL |
| 代码里有但已废弃 | 也写，并标明已废弃。不要因为「大概没在用了」就跳过 |

「以代码为准」有一个例外：**不要抹掉图谱里已有的人工标签**。
代码里的 `PAID(1)` 和图谱里的 `1=已付款` 说的是同一件事，而后者是人整理的、更好读。
这时补的是**缺的那部分取值**，不是把整份值域推平重写。

**不要只在回答里说一句「图谱和代码不一致」就完事。** 说了不写，这条知识随会话消失，
下一个 agent 从同一份错的图谱出发再犯一次同样的错。**发现即写回**——
别名开了 `approveGraph` 的话它会排成一条待审批，人在评审页看 before/after 再决定，
这正是那套机制存在的理由。你的职责是把不一致送到人面前，不是替人决定要不要送。

## JOIN 关系

1. **预筛**（只用来圈范围，不用来提取）：

   ```bash
   grep -rl "JOIN" --include="*Mapper.xml" --include="*.sql" <src>
   grep -rl "@JoinColumn\|@ManyToOne\|@OneToMany" --include="*.java" <src>
   ```

2. **逐个读**，理解动态 SQL 的条件后提取**真实成立的等值 JOIN 条件**。
   注意几种容易提错的：

   - **条件 JOIN**：`<if>` 包着的 JOIN 只在特定参数下成立。它仍然是一条真实关系，
     但 confidence 该降（0.6~0.7），并在 `--join` 里说明条件
   - **别名**：`o.buyer_id = u.id` 里的 `o` / `u` 要还原成真实表名
   - **常量条件**：`AND d.dict_type = 'order_status'` 是关系的一部分，**必须写进 `--join`**，
     漏掉会让顺着这条边查字典捞出全表
   - **复合键**：`ON a.tenant_id = b.tenant_id AND a.order_no = b.order_no` 是**一条**关系，
     `--join` 要带全部列对，不要拆成两条

3. **每条写回**，`--confidence` 必填，取值标准见 [graph-write.md](graph-write.md)。

## 字段语义与值域

读实体类时顺手把这几样一起补，比事后回头补便宜得多：

- **业务名**（`--business-name`）：短标签，来自字段注释的第一句或 `@ApiModelProperty`
- **业务描述**（`--description`）：注释里解释口径、坑、历史包袱的那部分
- **语义类型**（`--semantic-type`）：**必须用枚举值**，见 `schema edit --help`
- **值域**（`--enum-values`）：**枚举类和常量类是金矿**

  ```java
  public enum OrderStatus { PENDING(0, "待付款"), PAID(1, "已付款"), ... }
  ```

  **能连库就别直接写**，交给 `value-domain` 跟库里的实际分布对一遍，
  代码里漏掉的取值当场露出来：

  ```bash
  sql-cli <alias> schema value-domain --table app.orders --column status       --code-enum "0=待付款,1=已付款" --basis "OrderStatus 枚举类"
  ```

  连不上库才用 `schema edit --enum-values` 直接写。判据见 [graph-write.md](graph-write.md)。

- **冗余副本**（`--redundant-of`）：实体里同时有 `buyerId` 和 `buyerName`，
  而 `buyerName` 是下单时冗余进来的 → 标注权威源在 `users.name`

## 字段血缘

血缘回答的是「**这一列的值是哪段代码、从哪几列写进来的**」。判据是有没有一段代码在写：
有 `through` 的是血缘，两列在查询里能对上的是关系。**同一对列可以同时有关系和血缘**——
`plan_id` 既能 JOIN 到 `task_plan.id`（关系），又是 `generatorPlan` 从那里抄来的（血缘）。

### 四类，按代码的形状选，不按文件类型选

| 代码里长什么样 | `--kind` | 读它的 agent 会怎么做 |
|---|---|---|
| `b.setSort(a.getSort())`，`INSERT ... SELECT` 里同名列原样搬 | `identity` | 当快照：要当前值去源列；改源列时连这段拷贝一起改 |
| `prefix + date + seq`，SQL 表达式列，`CASE type WHEN ...` 三选一 | `transformation` | 用 expression 里的公式，不自己猜 |
| 遍历子表得一个结论，`SUM` / `COUNT` / `GROUP BY` | `aggregation` | 当冗余汇总：核对时按 expression 从明细重算，明细改了它不会自己变 |
| `if (全部点位 is_result = 1) setEndTime(now())`，值是当前时间 / 常量 / 用户输入，列只决定写不写 | `rule` | 改源列的含义或值域前先看 `through`，否则这处写入会静默失效 |

`--source` 写**改了它 target 会变的全部列**：`rule` 的条件列、`CASE` 的选择列都算源，
不单独区分。一段代码写一列 = 一条记录，同一列被两段代码写就写两条，`--through` 不同。

四类的唯一区别是读的人下一步做什么，拿不准就问：**源列改了，这一列会跟着变吗？**
会且不用重跑 → `transformation`；会但要重跑 → `aggregation`；值不变但可能不再写 → `rule`；
原样等于源列 → `identity`。

### 写之前

**先确认目标列在图谱里存在。** `--target` 指向的列不在就直接报 `Column not found`。
派生列通常在视图里，视图要先导入才有节点：

```bash
sql-cli <alias> schema describe <schema.视图名>              # 确认在图谱里
sql-cli <alias> schema import --from-db --table <schema.视图名>   # 不在就只导这一张
```

视图在数据库里**不存在**（手上只有一段没上线的 SQL）就不要为了写血缘去建视图——那是改数据库。
如实告诉用户挂不上去，以及他要先做什么。

### 写

```bash
sql-cli <alias> schema add-lineage \
  --target erp.erp_prop_plan_record_detal.inspect_end_time \
  --source erp.erp_prop_plan_point.is_result \
  --kind rule --expression "该任务下全部点位 is_result=1 时写入当前时间并置 state=2" \
  --through "ErpPropPlanPointServiceImpl.submit"
```

- `--through` 必填：视图名 / ETL 作业 / `类名.方法` / Mapper statement id。它是 `identity` 和 `rule`
  的全部价值——没有它 agent 知道「会影响」却不知道去哪改
- `--expression`：`transformation` / `aggregation` 必填，写实际表达式，`CASE WHEN ...` 照抄；
  `rule` 写规则原话；`identity` 不写
- 不给 `--kind` 只推断两种：单源无表达式 → `identity`，有表达式 → `transformation`。
  `aggregation` 和 `rule` 从形状认不出来，必须显式给
- 源含目标本身会被拒收——列不是自己的上游。「巡检员通过 submit 接口回写」这类
  只说了谁在写、没有源列的事实，写进那一列的 `--description`

写错的用 `schema remove-lineage --target <ref> --through <TEXT>` 删，参数以 `--help` 为准。

其他值得找的地方：`INSERT INTO ... SELECT`（多数列是 `identity`，带运算的列才是 `transformation`）、
报表/统计 SQL 的聚合列（`aggregation`）、Mapper 里带计算的 `resultMap`、
Service 里被 `if` 包着的 `setXxx(now())`（`rule`）。

## 收尾

1. 写完跑一次 `schema index rebuild`
2. 报告里说清**哪些是从代码读出来的**、confidence 各是多少、哪些不确定
3. 遵守「少而准」：只提取这次任务真正相关的部分，不要把整个仓库扫一遍
