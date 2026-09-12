# 指标（metric）

指标是「GMV 怎么算」这种**口径知识**。它不是常规步骤——大多数任务查表、查字段、
写普通 SQL 就够了。**只在真的撞见口径争议时才想起这份文档**：同一个业务问题被
（你自己或不同的人）算出两个数，而且必须统一成一个。查一次空列表就该回退去写普通
查询，不要每次任务开头都先查一遍指标有没有。

完整参数见 `schema add-metric --help` 和 `schema expand-metric --help`，本文只讲判断。

## 什么时候该建

- 口径有争议，需要一个能被人评审、被追溯的地方——**这是唯一值得建的场景**
- 同一个算法你已经在这次会话里拼过第二遍，且后续还会被问到

**不该建的**：一次性统计、只用一次的探索性查询、没人问过第二遍的东西。

## `filters` 是整条指标的存在理由

```text
GMV = SUM(order.amount) WHERE status IN (2, 3)
                        ^^^^^^^^^^^^^^^^^^^^^^ 算不算退款？算不算未支付？
```

`--expression` 通常没有争议，**争议 90% 在 `--filters` 这一行**。不要因为
「用户没说」就把它留空——留空等于悄悄采用了「全都算」这个口径。不确定就问，
或者写上你的假设并在回答里明确说出来。

## 比率口径（复购率、转化率……）用 `--numerator`/`--denominator`

分子分母的过滤条件通常不一样（复购率分子要"下单≥2次"，分母不要），塞进同一个
`--filters` 会变成两者的交集，两个数都错：

```bash
sql-cli <alias> schema add-metric repeat_purchase_rate \
  --numerator "COUNT(DISTINCT app.orders.user_id)" --numerator-filters "order_count >= 2" \
  --denominator "COUNT(DISTINCT app.orders.user_id)" \
  --business-name "复购率" --dimensions app.orders.channel
```

必须成对声明，缺一个不构成完整的比率指标；不用填 `--additivity`，展开器自动按
`non_additive` 处理。

## `--additivity semi_additive` 保护跨时间不可加的快照

库存余额这类快照标 `semi_additive` 后，`expand-metric` 请求 `--grain` 时间分桶会
直接拒绝，而不是生成一段把快照值按天/月错误相加、看起来正确的 SQL。

## `--join-path` 是人工权威声明，不要指望自动推

跨表指标的 JOIN 路径必须显式声明：口径要扯皮的东西本来就该是权威声明而不是猜出来的，
而且自动推导会撞上扇形陷阱（1:N 连出去 `SUM` 被行数乘大）和深坑陷阱（两张事实表
笛卡尔积）。`relationId` 从 `schema describe`/`schema path --json` 里取。

## 建完要展开验证一次，否则又是一份没人用的语义资料

```bash
sql-cli <alias> schema expand-metric gmv_paid --grain month \
  --dimensions app.orders.channel --time-from 2026-01-01 --time-to 2026-02-01
```

展开出的 SQL 是骨架，要不要执行仍按查数据的规矩来。

## 状态

`add-metric` 以 agent 身份写入，初始状态是候选，等人在 Web UI 评审页 · 待审批里发布，
同名重写是幂等更新。`--aliases` 要写业务黑话（"成交额""流水"都该映射到 GMV），
否则用户问的时候搜不到。
