# 自检：确认工具链可用

**什么时候跑**：换了新环境、升级了 `sql-cli`、图谱行为不符合预期、或者用户问「这套东西能用吗」。

**不要在每次查询前跑**——第 9 步是写操作，会往图谱里塞一条测试术语。

按顺序执行，每步核对「应该看到什么」。**任何一步不符就停下来报告，不要继续往下走。**

```bash
ALIAS=<用户指定的别名>
```

| # | 命令 | 应该看到 |
|---|---|---|
| 1 | `sql-cli list` | 至少一个别名；只读的带 `[RO]` |
| 2 | `sql-cli $ALIAS test` | 连接成功。失败就停——后面全依赖它 |
| 3 | `sql-cli $ALIAS schema stats --json` | 表数、字段数、关系数非零；有 `candidateRelations` / `ignoredRelations` 计数。**全零说明图谱没导入过**，停下来问用户要不要导 |
| 4 | `sql-cli $ALIAS schema index status` | 索引存在且不落后于图谱。落后就 `schema index rebuild` |
| 5 | `sql-cli $ALIAS schema search "<一个业务词>" --json` | 有命中，且每条带 `matchedField` / `matchedText` / `comment` / `description` / `candidate` |
| 6 | `sql-cli $ALIAS schema describe <某张表> --json` | 有 `columns`、`relations`；关系带可复制的 JOIN 写法；可能有 `sm4Columns`、`recentSql` |
| 7 | `sql-cli $ALIAS schema path <表A> <表B> --json` | 有路径时每一跳带**完整 `ON` 条件**和 `INNER/LEFT JOIN（推断 / 实测）`。复合外键要能看到 `AND` 连接的多个列对 |
| 8 | `sql-cli $ALIAS schema lineage <某列> --json` | 有血缘则返回 `target`/`sources`/`expression`/`through`；**返回空是正常的**——血缘只能靠 `add-lineage` 写入，见 [code-scan.md](code-scan.md) |

## 写入链路（会改图谱，先问用户）

第 9~11 步会在图谱里留下痕迹，**执行前告知用户**：

| # | 命令 | 应该看到 |
|---|---|---|
| 9 | `sql-cli $ALIAS schema add-term __selfcheck__ --display-name "自检" --aliases "自检测试"` | 退出码 0。输出 `待发布（审批 #N）` 是正常的；输出 `待审批（审批 #N）` 见下 |
| 10 | `sql-cli $ALIAS schema index rebuild` | 重建成功 |
| 11 | `sql-cli $ALIAS schema search "自检测试" --json` | 命中第 9 步那条，且 `candidate: true` |

第 11 步是整条链路的验收：**写入 → 索引 → 检索** 三段都通了，说明图谱可用。

**别名开了 `approveGraph` 时第 9 步不会写图谱**（输出 `待审批（审批 #N）`），
于是第 11 步必然搜不到——**这不是故障，别报成故障**。这种别名上自检到第 8 步为止，
第 9~11 步改成：请用户去评审页批准那条审批，再跑 10、11。

跑完提醒用户清理：`__selfcheck__` 是候选术语，在 Web UI 图谱页删掉；
它同时会在**评审页 · 待审批**留一条发布审批，一并拒掉。

## 常见结果的解读

- **第 3 步全零** → 图谱没导入。这不是故障，是没初始化。**要征得用户同意再导入**
- **第 5 步无命中但第 3 步非零** → 多半是索引没建（回到第 4 步），
  或者关键词太生僻。先换个明确存在的表名试
- **第 8 步空** → 正常。血缘目前没有任何自动来源
- **第 7 步有路径但没有 `ON` 条件** → 那条关系写入时没带 `--join`，
  可以补：`add-relation` 同 id 重写是更新
- **第 11 步搜不到，而第 9 步输出的是 `待审批`** → 图谱审批拦住了写入，不是链路坏了（见上）
- **第 2 步或查询类步骤挂住十分钟** → 查询 / 更新审批开关，见 [troubleshooting.md](troubleshooting.md)
