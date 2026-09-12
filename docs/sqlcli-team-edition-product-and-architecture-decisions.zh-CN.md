# SQLCLI 团队版产品与架构决策记录

> 讨论沉淀日期：2026-08-06
>
> 状态：方向性决策，尚未代表团队版已经进入开发。
>
> 目标：记录从个人版 `sql-cli` 向团队协作产品演进时的产品定位、能力边界、技术架构与实施顺序，避免在 CLI、Web、MCP 和团队服务之间重复建设。

## 1. 最终结论

当前阶段继续优先完成个人版闭环，不同时开发完整团队版。

```text
当前切入点：个人开发者 + Coding Agent 的本地数据库工作台
长期方向：团队和 Agent 共同使用的数据库上下文与安全执行平台
```

个人版继续使用 `sql-cli` 作为项目名和命令名。团队版未来可以使用独立产品名称，但在核心价值和真实团队需求得到验证前不急于改名。

两个阶段不是两套独立实现：CLI、Web、未来 MCP 和团队服务必须复用同一套核心业务模块。

## 2. 产品价值

项目不应只定位为 SQL 命令行客户端或数据库图谱浏览器，更完整的定位是：

```text
面向开发者和 Agent 的数据库上下文、规则与安全操作工作台
```

它主要解决以下问题：

1. Agent 能理解代码，但通常不了解真实数据库结构、业务语义和团队规则。
2. 表关系、字段含义、设计规范和故障经验分散在个人经验与临时文档中。
3. SQL 从分析到执行缺少风险检查、恢复、验证和审计闭环。
4. Agent 推断出的关系和知识缺少证据、审核与持续回写机制。

长期壁垒不是 SQL 执行本身，而是团队持续沉淀的“组织数据库记忆”：

- 数据库结构与业务语义。
- 表和字段关系。
- 方言规则与治理规则。
- 历史任务、决策和执行证据。
- 安全操作、恢复和验证路径。

## 3. 两个完整闭环

### 3.1 单次数据库任务闭环

```text
任务目标
→ 获取数据库上下文
→ 形成分析或变更方案
→ 规则与风险检查
→ 必要时审批
→ 执行
→ 验证
→ 恢复与审计
→ 知识回写
```

典型场景是“订单状态异常，需要定位并修复”。用户或 Agent 应能在一个任务中完成表定位、关系理解、查询、修复方案、规则检查、执行、验证和记录，而不是手工在多个工具之间拼接流程。

### 3.2 团队知识演进闭环

```text
DDL / SQL / 代码 / 人工说明等证据
→ Agent 或成员生成候选关系、术语、规则
→ 自动校验
→ 负责人评审
→ 发布可信版本
→ 后续任务使用
→ 使用结果继续修正知识
```

Agent 可以提出候选知识，但不能直接把推测发布为团队事实。

## 4. 个人版与团队版的边界

### 4.1 个人版

个人版采用 local-first 方式：

- CLI 用于查询、DDL、图谱查询和自动化。
- Skill 指导 Agent 组合 CLI 命令完成任务。
- 本地 Web UI 管理别名、驱动、图谱、规则和历史。
- 文件保存配置和可迁移图谱。
- SQLite 保存持续增长的本地运行数据。
- Keychain 或加密存储保存密码。

个人版首先完成以下闭环：

```text
添加数据源
→ 导入 Schema
→ 查询和理解数据库
→ 发现或维护关系
→ 设计、检查 SQL/DDL
→ 安全执行
→ 恢复与验证
→ 保存任务和执行历史
```

### 4.2 团队版

团队版由长期运行的 Team Server 作为可信数据源，负责：

- Workspace、成员和权限。
- 数据源定义与凭据绑定。
- 图谱、规则和术语版本。
- 变更集、评审与发布。
- TaskRun、SQL执行、审批和审计。
- MCP和HTTP接口。

团队版不应只是“共享配置文件的 CLI”，也不应演变成通用聊天或协同文档软件。协同对象是数据库知识变更和数据库任务。

## 5. CLI、Skill、MCP和Web UI分工

### 5.1 CLI

CLI面向开发者、Shell、CI和Agent Skill，建议收敛为：

- SQL查询。
- tables、DDL查看。
- DDL设计、校验和差异分析。
- 图谱查询。
- 提交图谱候选变更。
- 别名只读查看和连接测试。
- 执行历史与任务状态查询。

团队模式下，CLI不直接发布图谱和规则，也不绕过审批执行生产写操作。

别名、驱动和密钥的新增、修改、删除以Web UI为主。现有CLI管理命令在UI能力完全对齐前保留兼容，之后再弃用。

### 5.2 Skill

当前 `CLI + Skill` 已经是有效的Agent工具形态：

```text
Skill：告诉Agent何时调用、如何调用和如何解释结果
CLI：实际执行查询、DDL、图谱和安全检查
```

因此个人版不急于开发MCP，应优先保证CLI具有稳定的机器契约：

- Agent相关命令支持结构化JSON输出。
- stdout只输出结果，日志写入stderr。
- 稳定的退出码、错误码和错误结构。
- Agent调用路径不依赖交互输入。
- 帮助文档、Skill和实际命令保持一致。

### 5.3 MCP

MCP主要解决跨Agent客户端、远程团队服务、结构化工具发现、细粒度授权和持续任务上下文，不应只是通过子进程包装CLI。

团队版可提供以下MCP能力：

```text
Resources
- workspace://schema/{dataSourceId}
- workspace://table/{dataSourceId}/{table}
- workspace://graph/{domain}
- workspace://rules/{environment}
- workspace://tasks/{taskRunId}

Tools
- search_schema
- inspect_table
- find_relation_path
- check_sql
- execute_read_query
- design_ddl
- propose_relation
- request_write_approval
- execute_approved_write
- verify_task
```

MCP是Agent入口，不是团队协作、权限、版本或存储系统。团队服务才是产品本体。

### 5.4 Web UI

Web UI是人类控制面，负责：

- 数据源、驱动和密钥管理。
- 图谱、规则和术语维护。
- 候选变更评审与版本发布。
- SQL工作台与执行历史。
- 写操作审批、恢复和验证。
- 团队成员、权限和审计。

团队版首页应围绕任务和待办，而不是只围绕图谱：

- 我的待评审和待审批。
- 最近任务和执行记录。
- 图谱候选关系。
- 规则命中和误报。
- 数据源健康状态。
- 最近发布的知识版本。

## 6. 团队协作模型

### 6.1 Workspace

Workspace包含：

- 成员与权限。
- 一个或多个数据源。
- 图谱、规则和业务术语。
- TaskRun和执行记录。
- 变更、发布和审计记录。

`alias` 只作为便于人和CLI使用的名称，不再作为全局身份。团队模型需要逐步引入：

```text
workspaceId
dataSourceId
environment
actorType
actorId
revision
```

### 6.2 图谱协同

每条团队关系需要记录：

- 来源和目标。
- 关系类型。
- DDL、SQL、代码或人工说明等证据。
- 置信度。
- 状态：候选、已确认、已废弃。
- 负责人。
- 生效版本。
- 最近验证时间。

### 6.3 规则协同

规则按“草稿、测试、评审、发布、回滚”管理，并支持Workspace、环境、数据源、Schema、表和SQL类型等作用范围。

生产环境默认只读。写操作必须经过规则检查和显式审批；低风险知识修正可以按权限直接发布，避免所有操作都进入重审批。

### 6.4 变更集

图谱和规则不允许成员直接覆盖线上可信版本，统一通过轻量变更集：

```text
草稿 → 自动检查 → 提交评审 → 批准 → 发布 → 新版本
```

第一版使用revision乐观锁处理冲突，不开发实时多人共同编辑。

### 6.5 TaskRun

一次完整数据库任务使用TaskRun串联已有产物：

```text
TaskRun
├── 目标和操作者
├── Workspace与数据源版本
├── 分析过程和证据
├── SQL时间线
├── 规则检查结果
├── 审批记录
├── 执行结果
├── 恢复信息
└── 验证结论
```

TaskRun只引用现有执行、规则、变更和恢复记录，不重复保存相同内容。

## 7. 权限与安全

团队权限按能力控制，而不是只有管理员和普通成员：

- 查看知识。
- 编辑草稿。
- 发布图谱。
- 发布规则。
- 执行只读SQL。
- 申请或执行写SQL。
- 审批生产操作。
- 管理数据源。
- 查看审计记录。

每次重要操作必须记录成员或Agent、Workspace、数据源、任务、规则版本、SQL和结果。

团队不共享明文数据库密码。共享的是数据源定义，凭据由服务器密钥、外部Secret Manager或成员个人授权提供。

## 8. 代码架构演进

不推倒重写现有代码。数据库策略、连接、SQL解析、SM4、恢复SQL、图谱模型、校验、索引、revision和规则实现都可以继续复用。

需要调整的是入口与核心业务之间的seam：

```text
CLI Adapter ─┐
Web Adapter ─┼── Core/Application Modules ── Storage / Database
MCP Adapter ─┘
```

核心业务建议逐步形成以下深模块：

1. SQL Execution：检查、改写、执行、恢复、审计，返回结构化结果。
2. Knowledge Query：Schema、图谱、规则、术语和搜索。
3. Knowledge Change：候选、评审、发布和回滚。
4. DDL Design：生成、校验、差异和影响分析。
5. Admin Config：数据源、驱动、凭据绑定和环境策略。
6. Task/Audit：TaskRun、执行记录和验证。

CLI、HTTP Controller和MCP只做参数解析、身份上下文、调用和结果适配，不承载业务规则。

## 9. 前后端架构决策

### 9.1 前端

保留React、Vite、TanStack Query、Zustand和Sigma.js。随着页面增加，需要把当前查询参数切页方式升级为正式路由和统一App Shell。

建议路由：

```text
/workspaces/:workspaceId/home
/workspaces/:workspaceId/datasources
/workspaces/:workspaceId/knowledge
/workspaces/:workspaceId/changes
/workspaces/:workspaceId/rules
/workspaces/:workspaceId/sql
/workspaces/:workspaceId/tasks
/workspaces/:workspaceId/audit
/workspaces/:workspaceId/settings
```

服务端数据由TanStack Query管理，Zustand只保存图谱画布等客户端交互状态，避免维护第二份服务端事实。

### 9.2 后端与Spring Boot

当前本地单用户UI可以继续使用JDK `HttpServer`。进入团队版开发后，需要独立的Spring Boot服务端来承载认证、权限、事务、校验、健康检查和审计。

建议最终形成：

```text
sqlcli-core       纯Java核心业务
sqlcli-cli        Picocli入口
sqlcli-server     Spring Boot、HTTP、身份、团队存储、MCP
web               React前端
```

Spring Boot只进入 `sqlcli-server`，不让CLI启动Spring容器，也不让核心业务依赖Spring注解。

团队服务第一阶段只需要Web、Validation、Security、JDBC、Actuator和数据库迁移能力，不引入Spring Cloud、微服务、消息队列或复杂JPA模型。

接口逐步迁移到显式、版本化路径：

```text
/api/v1/workspaces/{workspaceId}/...
```

## 10. SQLite边界

SQLite适合个人版的本地运行状态，不作为团队共享数据库。

建议保存：

- SQL执行历史。
- TaskRun和步骤。
- 本地MCP/Agent调用审计。
- 待提交变更。
- 团队同步队列和离线缓存。

暂不保存：

- 明文密码和密钥。
- JDBC驱动文件。
- 团队可信数据。
- 已经由图谱generation文件稳定管理的正式图谱版本。

推荐布局：

```text
个人模式
├── config/aliases.yaml             数据源配置
├── config/schema-graphs/           图谱与版本
├── ~/.sql-cli/sqlcli.db            历史、任务、审计、同步状态
├── drivers/                        JDBC驱动
└── Keychain / encrypted secrets    密钥

团队模式
├── Team Database                   团队可信数据
├── Secret Manager                  团队凭据
└── ~/.sql-cli/sqlcli.db            可选本地缓存和离线队列
```

SQLite优先用于替换SQL历史JSONL。实现使用原生JDBC即可，不引入ORM；不要一次性迁移所有配置和图谱，避免形成两套可信数据源。

## 11. 实施顺序

### 阶段一：完成个人版闭环

- 收敛CLI命令范围。
- 完善别名、驱动和密钥UI。
- 完成SQL工作台、历史和TaskRun。
- 完成Schema导入、图谱候选和规则检查。
- 完成DDL设计、安全执行、恢复和验证。
- 加固CLI的JSON、错误码和非交互契约。
- 继续完善官方Skill。

### 阶段二：调整核心架构

- 把业务逻辑从CLI和HTTP路由移入核心模块。
- SQL执行返回结构化结果。
- 图谱写入统一经过知识变更模块。
- 引入统一依赖装配入口。
- 拆分共享数据源定义与私密凭据。
- 逐步引入workspaceId、dataSourceId和actorId。

### 阶段三：共享可信知识

- 建立Team Server和团队数据库。
- 增加Workspace、成员和权限。
- 图谱、规则和术语服务端存储。
- 增加变更集、评审、发布和历史版本。
- CLI支持个人模式与团队模式切换。

### 阶段四：安全执行与MCP

- TaskRun贯通团队服务。
- SQL规则检查和生产审批。
- 恢复、验证和完整审计。
- MCP作为Team Server的Agent入口。
- 接入企业身份、密钥和通知系统。

## 12. 启动团队版的触发条件

满足以下多个真实条件后再启动完整团队版：

- 三人以上持续共享同一份图谱和规则。
- 多人修改已经产生冲突。
- 生产SQL需要审批和责任追踪。
- 团队要求统一数据源和密钥管理。
- 用户愿意部署长期运行的服务端。
- 个人版已经在真实任务中持续使用。

## 13. 明确不做

当前阶段不做：

- 通用数据库客户端。
- BI和报表平台。
- 通用数据目录或GraphRAG平台。
- 内置聊天和协同文档系统。
- 实时多人共同编辑。
- 微服务、服务注册中心和消息队列。
- 为尚不存在的第二种实现提前创建大量接口。
- 为了MCP重复包装CLI进程。

## 14. 决策摘要

```text
当前：个人版 sql-cli + Skill + 本地Web UI
近期：完成数据库任务闭环，并调整核心业务seam
团队版：Web控制面 + Team Server可信状态 + MCP Agent入口
CLI：保留为开发者、CI和故障兜底入口
SQLite：个人本地运行数据
Spring Boot：仅用于未来团队服务端
```

最重要的执行原则是：先证明闭环价值，再增加协作基础设施；先让CLI、Web和未来MCP共用核心能力，再增加新的入口。

## 15. 追加决策（2026-08-20）

- **图谱不用 Git 做版本管理，也不做多版本**：generation 目录只保留当前代，`save()`
  提交后即清理旧代；变更历史由 SQLite `graph_change_log` 承担。共享走
  `export` 快照 + `import --merge` 点对点交换，需求成立后直接演进 Team Server。
- **scan-sql 已删除**：SQL/Mapper 的 JOIN 提取交给 Agent（读代码 + `add-relation` 写回），
  不再维护静态解析器。
- **文档收敛**：排期依据收敛为 P0 清单 + dev-checklist-2026-08 两份；
  8 份过时清单/设计文档已删除（git 历史可查）。

