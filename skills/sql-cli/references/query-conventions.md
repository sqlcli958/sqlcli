# 查询约定规则的值域校验

`query_convention` 从已绑定规则产生查询上下文。维护枚举过滤时同时写出所需值与业务含义：

```yaml
- id: soft_delete_filter
  title: 仅查询有效记录
  category: query_convention
  severity: info
  enforcement: advisory
  when:
    columnNameRegex: "^del_flag$"
    columnValueIn: ["0"]
    columnValueMeanings: {"0": "存在"}
  statement:
    queryFilter: "{column} = 0"
```

这是一条规则，需放入规则集的 `rules` 数组并按现有流程由人启用。`columnValueMeanings` 为非空映射，键和值都写字符串；若保留 `columnValueIn`，其中每个值都必须有含义。含义去除首尾空白后精确匹配：`存在` 和 `有效` 不自动当作同义词，维护者应核对并统一术语，或按表适用范围分别配置。

字段值域必须恰好支持规则要求的对应含义。缺失、相反含义、同值多义、来源变化待复核时，只输出 `unresolvedConventions`，其中 `proposedFilter` 尚不可应用。规则读取失败也会显示待确认原因。

旧规则只有 `columnValueIn` 时仍能加载，但显示迁移提示，不再放入可直接应用的 `conventions`。升级前检查已绑定规则：根据权威业务代码、字典或人工说明补充含义，再用 `schema describe <table> --json` 核对。不要仅根据样本里出现了 `0` 就填入“存在”。

规则表单目前没有查询约定类别的专用编辑入口，继续使用现有规则 YAML 管理方式；这次修复不增加另一套命令或默认启用规则。
