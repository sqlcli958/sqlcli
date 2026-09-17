# sql-cli 文档导航

项目首页只保留定位、核心能力和快速开始。安装、配置、命令、安全约束、设计说明和开发资料统一在这里维护。

## 用户文档

- [完整使用手册](user-manual.zh-CN.md) — 安装、配置、命令、数据库使用、Schema 图谱、规则、Web UI 与故障处理的主要入口
- [发行与分发指南](sql-cli-distribution-guide.zh-CN.md) — 打包、分发和运行环境说明
- [CLI 命令测试用例](cli-command-test-cases.zh-CN.md) — CLI 行为与验证用例

## Agent 与数据库生命周期

- [Agent 数据库生命周期优先开发清单](agent-database-lifecycle-priority-development-checklist.zh-CN.md)
- [Agent 数据库生命周期工作区路线图](agent-database-lifecycle-workspace-roadmap.zh-CN.md)
- [产品方向（2026-09-03）](product-direction-2026-09-03.zh-CN.md)
- [产品演进方向（2026-08）](product-evolution-direction-2026-08.zh-CN.md)

## Schema 图谱与语义

- [数据库图谱实现概览](database-graph-implementation-overview.zh-CN.md)
- [数据库图谱元数据模型设计](database-graph-metadata-model-design.zh-CN.md)
- [图谱概念边界](graph-concept-boundaries.zh-CN.md)
- [图谱场景模型](graph-scenario-model.zh-CN.md)
- [图谱语义机制](graph-semantic-mechanisms.zh-CN.md)
- [图谱术语网络](graph-term-network.zh-CN.md)
- [GraphRAG 元数据检索笔记](graphrag-metadata-retrieval-notes.zh-CN.md)
- [Schema / Catalog 数据库术语](schema-catalog-database-terminology.zh-CN.md)

## SQL 执行、规则与审批

- [SQL Task 模块设计](sql-task-module-design.zh-CN.md)
- [SQL Pipeline 优化研究](sql-pipeline-optimization-research.zh-CN.md)
- [数据库生命周期 Policy 规则研究](database-lifecycle-policy-rules-research.zh-CN.md)
- [审批批次设计](approval-batch-design.zh-CN.md)
- [Schema Policy 示例规则集](schema-policy-ruleset.example.yaml)

## 数据源接入

- [数据源接入模板](datasource-integration-template.zh-CN.md)
- [ClickHouse 数据源接入清单](clickhouse-datasource-integration-checklist.zh-CN.md)

## 评估与测试资产

- [Graph Eval](graph-eval.md)
- [Search Eval 示例](search-eval-cases.example.yaml)
- [ERP Search Eval](search-eval-erp-inspection.yaml)
- [Task Eval 示例答案](task-eval-answers.example.yaml)
- [ERP Task Eval](task-eval-erp-inspection.yaml)

## 开发资料

- [2026-09 开发清单](dev-checklist-2026-09.zh-CN.md)
- [2026-08 开发清单](dev-checklist-2026-08.zh-CN.md)
- [Team Edition 产品与架构决策](sqlcli-team-edition-product-and-architecture-decisions.zh-CN.md)
- [归档资料](archive/)

## Agent Skill

Agent 的实际使用约束与操作流程维护在：

- [`skills/sql-cli/SKILL.md`](../skills/sql-cli/SKILL.md)

## 文档约定

路线图、研究稿、开发清单可能描述尚未开放或仍在演进中的能力；当前可用功能以代码实现和[完整使用手册](user-manual.zh-CN.md)为准。
