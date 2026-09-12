# sql-cli

轻量级多数据库 CLI 工具，面向 AI Agent 和人工使用。

基于 JDBC，支持 MySQL、Oracle、PostgreSQL 等主流数据库，提供 SM4 列级加密、Schema 知识图谱、Yearning 审计平台集成等企业级特性。

sql-cli 是面向 Agent 的数据库生命周期辅助工具。
它通过数据库元数据图谱、业务语义、关系推断、SQL 分析和安全执行能力，
帮助 Agent 在 Web 项目开发中完成表设计、变更评审、SQL 编写、数据排查、修复验证和持续演进。

当前版本的安装、配置、命令、安全约束和故障处理请以[完整使用手册](docs/user-manual.zh-CN.md)为准；路线图中的未完成能力不代表当前可用功能。

## 特性一览

| 特性 | 说明 |
|------|------|
| 多数据库支持 | MySQL、Oracle、PostgreSQL、ClickHouse，策略模式自动适配方言 |
| SQL 执行管控 | 全项目唯一执行入口：只读/写分流、写操作预检与审批闸门、行数上限与超时、执行审计 |
| SM4 列级加密 | 基于 BouncyCastle 的 SM4 加密，SQL 改写 + 结果自动解密 |
| Schema 知识图谱 | 表/列元数据与业务语义管理、术语定义、关系维护、路径查找、中文分词搜索 |
| Web UI | 工作台 / 图谱 / 规则 / 评审 / 设置五页：SQL 编辑执行、图谱可视化、结构规则审计、写操作审批 |
| 安全密码管理 | macOS Keychain / Windows DPAPI / AES-GCM 加密存储 |
| 连接池管理 | HikariCP 连接池，指纹去重，隔离 ClassLoader 加载外部驱动 |
| 恢复 SQL 生成 | UPDATE/DELETE 执行前自动生成回滚 SQL，事务内先落盘再执行 |
| Yearning 集成 | 通过 Yearning 审计平台执行只读查询 |
| 多输出格式 | CSV（默认）、JSON、ASCII 表格 |
| AI Agent 友好 | 提供 Claude Code Skill，图谱定位 + 执行反馈闭环，见下节 |

## 面向 Agent 的 SQL 正确率设计

Agent 写错 SQL 的根源几乎都是猜：猜表名、猜字段含义、猜 join 条件、猜字面量。
本项目按「写 SQL 前 → 执行中 → 执行后」三段，把每一类"猜"换成确定的信息来源：

**写 SQL 前——用图谱消灭猜测。** Claude Code Skill（`skills/sql-cli/SKILL.md`）规定动作顺序：
`schema search` 用业务语言定位表/字段（CJK 二元切词，命中列注释、业务名、语义类型、术语和同义词）；
`schema describe` 一次给足结构——列的类型/主键/业务名/语义类型/**值域**、SM4 加密列标注
（仅支持等值/IN）、关系附可直接复制的 JOIN 写法、这张表最近的成功查询范例（Recent SQL）；
`schema query --depth` / `schema path` 给多表关联路径，只用图谱里已确认的关系生成 join。
其中值域影响最大：`status=1` 是"已付款"还是"已发货"，猜错的 SQL 有结果、不报错、答案是错的。

**执行中——单一入口兜底。** 所有 SQL（CLI、Web 工作台、历史重放）都走 `SqlTaskModule`：
多语句/readonly 写/无 WHERE 的 UPDATE·DELETE 就地拒绝；写操作先预检影响行数、可配审批闸门；
SM4 列自动加密查询条件、自动解密结果——Agent 不知道加密存在也能查对；行数上限（默认 100，
`--max-rows` 调整）防止一条宽表查询挤爆 Agent 上下文；UPDATE/DELETE 自动生成恢复 SQL；
全部落 `sql_execution` 审计（含被拒绝的）。

**执行后——反馈闭环让重试有方向。** 报「列/表不存在」时错误信息自动附图谱模糊纠错
（"相近的列: app.orders.user_id"）；SELECT 查回 0 行且 WHERE 值不在图谱值域内时提示已知值域；
成功的 SQL 沉淀进执行历史，成为下一次 describe 的范例。

**正循环——Agent 反哺图谱。** Agent 读应用代码（实体类、Mapper XML）发现的表含义、业务名、
JOIN 关系、值域，用 `schema edit / add-term / add-relation` 写回图谱。写入的是候选状态
（candidate），在 Web UI 评审后才转正式：人负责审核，Agent 负责积累，图谱越用越准。

## 快速开始

### 环境要求

- Java 17+

### 构建

```bash
mvn clean package
```

构建产物：
- `target/sql-cli.jar` — 唯一的可执行包（shade），约 17 MB

  内含 sqlite-jdbc 的原生库，只保留 Windows/x86_64、Mac/x86_64、Mac/aarch64、
  Linux/x86_64、Linux/aarch64 五个平台。**其他平台跑不了**（sqlite-jdbc 没有纯 Java 回退），
  需要时在 `pom.xml` 的 shade 过滤器里把对应平台从排除名单去掉再打包。

数据库 JDBC 驱动不内置在上述 JAR 中，需通过 `config/settings.yaml` 配置外部驱动 JAR。

### 安装（可选）

```bash
# 自动检测 Java 环境，安装到 ~/.sql-cli/，创建 /usr/local/bin/sql-cli 软链接
sh sh/install.sh
```

### 运行

```bash
# 通过 shell 脚本运行（开发模式）
sh ./sql-cli <alias> "SELECT ..."

# 通过 java 直接运行
java -jar target/sql-cli.jar <alias> "SELECT ..."

# 安装后可直接使用
sql-cli <alias> "SELECT ..."
```

## 命令结构

### 别名操作（核心用法）

```bash
sql-cli <alias> "SQL"              # 执行 SQL 查询（默认 CSV 输出）
sql-cli <alias> --file query.sql   # 从文件执行 SQL
sql-cli <alias> test               # 测试连接
sql-cli <alias> test --json        # 测试连接（JSON 输出）
sql-cli <alias> tables             # 列出表
sql-cli <alias> tables -s mydb             # 按 schema 过滤
sql-cli <alias> tables -p "%%user%%"       # 按模式搜索表
sql-cli <alias> tables -f json             # JSON 格式输出
sql-cli <alias> ddl <table>        # 获取表 DDL
sql-cli <alias> ddl <table> -s mydb  # 指定 schema 获取 DDL
sql-cli <alias> secret set         # 设置密码（交互式）
sql-cli <alias> secret set --stdin # 从 stdin 读取密码
sql-cli <alias> secret status      # 查看密码状态
sql-cli <alias> secret status --json  # 查看密码状态（JSON 输出）
sql-cli <alias> secret delete      # 删除密码
```

### 系统管理命令

```bash
sql-cli list                       # 列出所有别名
sql-cli list --json                # JSON 格式输出
sql-cli alias show <alias>         # 查看别名详情
sql-cli alias add <alias> --db-type mysql --driver-ref mysql8 \
  --jdbc-url jdbc:mysql://... --username root --secret-ref keyring:<alias>
sql-cli alias update <alias> --jdbc-url jdbc:mysql://...  # 更新别名
sql-cli alias remove <alias>       # 删除别名
sql-cli driver list                # 列出驱动
sql-cli driver show <name>         # 查看驱动详情
sql-cli driver add <name> --db-type mysql \
  --driver-class com.mysql.cj.jdbc.Driver --jar ./drivers/mysql/mysql-connector-j-8.3.0.jar
sql-cli driver update <name> --jar ./drivers/mysql/new.jar  # 更新驱动
sql-cli driver remove <name>       # 删除驱动
sql-cli driver default --db-type mysql --driver-ref mysql8  # 设置默认驱动
sql-cli crypto sm4 --encrypt --text "明文" --alias <alias>   # SM4 加密
sql-cli crypto sm4 --decrypt --text "密文" --alias <alias>   # SM4 解密
```

### 查询选项

```bash
sql-cli <alias> "SQL" [-f table|json|csv] [--decrypt-cols col1,col2] [--no-decrypt] [-d]
```

| 选项 | 说明 |
|------|------|
| `-f` | 输出格式：`table`（ASCII 表格）、`json`、`csv`（默认） |
| `--max-rows` | 行数上限（默认 100，`0` 不限；超限有截断提示，`-f json` 带 `truncated` 字段） |
| `--decrypt-cols` | 指定需要 SM4 解密的列名，逗号分隔 |
| `--no-decrypt` | 返回原始加密数据，不解密 |
| `-d` / `--debug` | 启用详细日志 |
| `--schema` | 指定 schema（Oracle DDL/tables 操作需要） |

## 配置体系

### 配置文件

```
config/
├── settings.yaml            # 主配置（驱动、密码、别名路径）
├── aliases.yaml             # 别名定义（macOS）
├── aliases-windows.yaml     # 别名定义（Windows）
└── schema-graphs/           # Schema 知识图谱工作区
```

**配置文件优先级：**
1. 环境变量 `SQLCLI_ALIASES_PATH` 指定的路径（最高）
2. `config/settings.yaml` 中的 `aliasesPath` 配置
3. 默认 `config/aliases.yaml`

### 别名配置

```yaml
aliases:
  my-mysql:
    dbType: mysql
    driverRef: mysql8
    url: jdbc:mysql://localhost:3306/mydb
    username: root
    secretRef: keyring:my-mysql        # 密码引用
    description: "开发环境 MySQL"
    readonly: false

  my-oracle:
    dbType: oracle
    driverRef: oracle19
    url: jdbc:oracle:thin:@//host:1521/service  # SERVICE_NAME 格式
    # url: jdbc:oracle:thin:@host:1521:sid       # SID 格式
    username: app_user
    secretRef: encrypted:my-oracle
    description: "生产 Oracle（只读）"
    readonly: true

  my-pg:
    dbType: postgresql
    driverRef: postgresql
    url: jdbc:postgresql://localhost:5432/mydb
    username: postgres
    secretRef: env:PG_PASSWORD         # 从环境变量读取
    description: "PostgreSQL"
```

## ClickHouse

### HTTP 连接

```yaml
aliases:
  analytics-ch:
    dbType: clickhouse
    driverRef: clickhouse09
    url: jdbc:clickhouse://clickhouse-host:8123/analytics
    username: readonly_user
    secretRef: keyring:analytics-ch
    readonly: true
    params:
      jdbc_ignore_unsupported_values: "true"
      socket_timeout: "300000"
      connection_timeout: "10000"
      defaultQueryLimit: "100"
```

### HTTPS 连接

```yaml
aliases:
  analytics-ch-cloud:
    dbType: clickhouse
    driverRef: clickhouse09
    url: jdbc:clickhouse://host:8443/analytics
    username: default
    secretRef: encrypted:analytics-ch-cloud
    readonly: true
    params:
      ssl: "true"
      sslmode: "strict"
```

### JDBC URL 规范

别名配置字段统一为 `url`，字段值必须是完整 JDBC URL。ClickHouse 使用
`jdbc:clickhouse://host:port/database`。配置文件不支持 `jdbcUrl` 或
`host/port/database` 等其他连接字段。

### 注意事项

- ClickHouse 不支持标准 UPDATE/DELETE，请使用 `ALTER TABLE ... UPDATE/DELETE`
- ClickHouse 不支持事务和恢复 SQL
- 默认使用 HTTP 协议，端口 8123；HTTPS 端口 8443
- 建议设置 `readonly: true` 并限制查询行数（`defaultQueryLimit`）

支持通过环境变量动态替换：

```yaml
url: "jdbc:mysql://${DB_HOST:localhost}:${DB_PORT:3306}/mydb"
```

### 驱动配置

内置驱动：MySQL 8、Oracle 19、PostgreSQL。

外部驱动放在 `drivers/` 目录，通过 `sql-cli driver add` 注册。驱动加载使用隔离的 ClassLoader，避免类冲突。

### 连接池参数

可在别名中配置 HikariCP 连接池参数：

```yaml
aliases:
  my-db:
    maximumPoolSize: 10
    minimumIdle: 2
    connectionTimeoutMs: 30000
    idleTimeoutMs: 600000
    maxLifetimeMs: 1800000
    keepaliveTimeMs: 300000
    defaultQueryLimit: 1000
```

## 密码管理

三种密码方案，跨平台兼容：

| 方案 | 语法 | 平台 | 说明 |
|------|------|------|------|
| 环境变量 | `env:VAR_NAME` | 全平台 | 从环境变量读取 |
| 系统钥匙串 | `keyring:name` | macOS / Windows | macOS Keychain 或 Windows DPAPI |
| 加密存储 | `encrypted:name` | 全平台 | AES-GCM 加密，存储在 settings.yaml |

```bash
# 设置密码（交互式输入）
sql-cli <alias> secret set

# 查看密码状态
sql-cli <alias> secret status
```

**主密码机制：** 首次使用 `encrypted:*` 方案时需输入主密码，之后自动存储到系统钥匙串，后续使用无需再次输入。

## SM4 列级加密

支持敏感列（手机号、账号等）的自动 SM4 加密/解密，适用于数据库中已启用列级加密的场景。

### 别名配置

```yaml
aliases:
  my-db:
    sm4Key: "88ED6EA3C8054CD9"        # SM4 密钥（16 字符）
    sm4PrivateTag: "ENC"              # 密文前缀（默认 ENC）
    sm4Version: "240606"              # 版本标识（默认 240606）
    decryptColumns: [phone, account]  # 查询时自动解密的列
```

### 加密/解密行为

**写入时自动加密（SQL 改写）：**
- `INSERT INTO t (phone) VALUES ('13800138000')` → 自动加密 VALUES 中的明文
- `UPDATE t SET phone = '13800138000'` → 自动加密 SET 中的明文
- `SELECT * FROM t WHERE phone = '13800138000'` → 自动加密 WHERE 中的明文
- `SELECT * FROM t WHERE phone IN ('138', '139')` → 自动加密 IN 列表

**读取时自动解密：**
- 查询结果中 `decryptColumns` 配置的列自动解密
- 使用 `--no-decrypt` 跳过解密
- 使用 `--decrypt-cols col1,col2` 临时指定解密列

**密文格式：** `ENC#<version>#<hex-ciphertext>`

### 手动加密/解密

```bash
sql-cli crypto sm4 --encrypt --text "13800138000" --alias my-db
sql-cli crypto sm4 --decrypt --text "ENC#240606#..." --alias my-db
```

## 恢复 SQL

UPDATE/DELETE 操作执行前自动查询原始数据并生成恢复 SQL：

- **UPDATE** → 生成反向 UPDATE（恢复原始值）
- **DELETE** → 生成 INSERT（包含被删除行的完整数据）

回滚脚本与执行前的原始行保存在本地运行库 `${user.home}/.sql-cli/sqlcli.db`，在 Web UI 评审页的「执行记录」里查看或执行。

> 注意：复杂 SQL（含 JOIN、子查询、ORDER BY、LIMIT）不支持恢复 SQL 生成，会提示错误。

## Schema 知识图谱

Schema 知识图谱是 sql-cli 的核心子系统，用于管理和查询数据库元数据，支持表/列注释、业务术语定义、关系发现、路径查找等功能。

### 工作区模型

```
GraphWorkspace
├── schemas/          # Schema 节点
│   └── tables/       # 表节点（含列定义、值域、标签、业务名称）
├── terms/            # 业务术语（含同义词）
├── relations/        # 关系，类型只有三种：foreign_key / join_observed / term_mapping
├── changes/          # 变更记录
└── validation/       # 校验问题
```

存储为结构化文件，generation 快照原子切换。对象有正式/候选（candidate）两种状态，
Agent 写入的关系和术语先是候选，人在 Web UI 发布后转正式。

### 命令概览

```bash
# ---- 读取操作 ----
sql-cli <alias> schema list                                    # 列出所有表
sql-cli <alias> schema describe mydb.users                     # 查看表详情
sql-cli <alias> schema describe mydb.users --json              # JSON 输出
sql-cli <alias> schema query mydb.orders --depth 2             # 查看表关联（2 跳）
sql-cli <alias> schema search "手机号"                          # 搜索表/列/术语
sql-cli <alias> schema path mydb.users mydb.orders             # 查找两表间的关系路径

# ---- 数据导入 ----
sql-cli <alias> schema import --from-db                        # 从数据库导入全部元数据
sql-cli <alias> schema import --from-db --schema mydb          # 只导入指定 schema
sql-cli <alias> schema import --from-db --table mydb.users     # 只导入指定表
sql-cli <alias> schema import --from-db --batch-size 20        # 指定批次大小
sql-cli <alias> schema import --from-db --force-overwrite      # 强制覆盖已有数据
sql-cli <alias> schema import --input snapshot.json            # 从 JSON 快照导入
sql-cli <alias> schema import --input snapshot.json --merge    # 合并导入（保留用户数据）
sql-cli <alias> schema import status                           # 查看导入状态
sql-cli <alias> schema import resume                           # 恢复中断的导入
sql-cli <alias> schema import reset                            # 重置导入状态

# ---- 数据导出 ----
sql-cli <alias> schema export                                  # 导出到默认路径
sql-cli <alias> schema export --output snapshot.json           # 导出到指定文件
sql-cli <alias> schema export --output snapshot.json --force   # 覆盖已有文件

# ---- 编辑 ----
sql-cli <alias> schema edit --table mydb.users --description "用户表"
sql-cli <alias> schema edit --table mydb.users --description "用户表" --add-tag PII
sql-cli <alias> schema edit --column mydb.users.phone --description "手机号" --example "13800138000" --add-tag PII --add-constraint "格式: 11位数字"

# ---- 业务对象 ----
sql-cli <alias> schema add-term buyer --display-name "买家" --aliases "下单用户,购买人" --map "mydb.orders.buyer_id"
sql-cli <alias> schema add-relation --type join_observed --from "mydb.orders.user_id" --to "mydb.users.id" --join "orders.user_id = users.id" --confidence 0.9

# ---- 结构规则审计 ----
sql-cli <alias> schema policy check --rules docs/schema-policy-ruleset.example.yaml --json
sql-cli <alias> schema policy evaluation list --json
sql-cli <alias> schema policy violation list --evaluation <evaluation-id> --json
sql-cli <alias> schema policy waiver add --rule <rule-id> --target <target-id> --reason "临时兼容" --expires-at 2026-08-01T00:00:00
sql-cli <alias> schema policy waiver list --active --json
sql-cli <alias> schema policy waiver revoke <waiver-id> --reason "豁免终止"

# ---- 工具 ----
sql-cli <alias> schema stats                                   # 工作区统计
sql-cli <alias> schema validate                                # 校验工作区
sql-cli <alias> schema index rebuild                           # 重建搜索索引
sql-cli <alias> schema index status                            # 查看索引状态
sql-cli <alias> schema diagram                                 # 生成关系图
sql-cli <alias> schema diagram --output ./docs                 # 输出到指定目录
sql-cli ui                                                      # 启动 Web UI 并显示别名首页（默认 9999 端口）
sql-cli ui --alias <alias> --port 9090 --no-open               # 直达指定别名，不自动打开浏览器
```

### 从数据库导入

```bash
sql-cli <alias> schema import --from-db
```

自动通过 JDBC `DatabaseMetaData` 提取数据库产品版本、表/列、主键、索引、外键和注释等元数据。支持：
- 批量处理 + 断点续传（checkpoint/resume）
- 增量合并：系统字段（类型、主键等）始终更新，用户维护字段（业务名称、注释等）保留
- 废弃表检测（数据库中已删除的表）
- generation 快照原子切换；中断时继续读取上一完整版本

`schema policy` 的 evaluation、violation 和 waiver 独立保存在工作区 `policy/` 目录，记录规则集快照和稳定 fingerprint；写入审计记录不会增加图谱 revision。

从代码提取 JOIN 关系（Mapper XML、实体注解等）不做静态扫描器，由 Agent 阅读代码后用 `schema add-relation` 写回（候选状态，人审核发布）。

### Web UI

```bash
sql-cli ui --alias <alias>          # 默认端口 9999
```

启动本地 HTTP 服务器（React 前端），一级导航固定五页：

- **工作台**：数据源目录 / 健康视图、SQL 编辑执行（写语句先预检确认，两步提交）
- **图谱**：表目录、按 schema 导入、画布可视化、表详情（列语义、值域、索引、候选审核）、索引重建
- **规则**：结构规范与 SQL 类规则两个固定分组的审计结果
- **评审**：写操作审批（放行/拒绝）、审批详情带预检与实际影响行数、执行记录（按 schema / 类型 / 时间 / 状态筛选，看完整 SQL、失败原因、回滚脚本）
- **设置**：别名增改删、连接测试、驱动管理（含 jar 上传）、密钥绑定

## Yearning 集成

支持通过 [Yearning](https://github.com/cookieY/Yearning) SQL 审计平台执行只读查询，适用于需要审计合规的生产环境。

```yaml
aliases:
  prod-yearning:
    accessMode: yearning
    yearningHost: "http://yearning-host:8000"
    yearningIdc: prod
    yearningDatabase: application
    username: admin
    secretRef: keyring:yearning-token
```

特性：
- JWT Token 管理（登录、缓存、自动刷新）
- 仅允许 SELECT/WITH/SHOW/DESC/DESCRIBE/EXPLAIN
- 自动表名限定

## Oracle 特殊说明

- 支持两种 URL 格式：
  - SID：`jdbc:oracle:thin:@host:port:sid`
  - SERVICE_NAME：`jdbc:oracle:thin:@//host:port/serviceName`
- 自动应用 VPN 兼容参数：`oracle.jdbc.javaNetNio=false`、`oracle.net.disableOob=true`
- DDL/tables 操作需指定 `--schema`（默认使用用户名）

## Claude Code 集成

sql-cli 提供 Claude Code Skill，让 AI Agent 能够自动管理数据库连接和执行查询。

```bash
# 安装 skill
sh ./install-skills.sh

# 卸载
rm -rf ~/.claude/skills/sql-cli
```

安装后在 Claude Code 中可通过 `/sql-cli` 命令或自动识别场景使用。

## 项目结构

```
sqlcli/
├── pom.xml                          # Maven 构建配置
├── sql-cli / sql-cli.bat            # 启动脚本
├── config/                          # 运行时配置
│   ├── settings.yaml                # 主配置
│   ├── aliases.yaml                 # 别名定义
│   └── schema-graphs/               # Schema 知识图谱数据
├── src/main/java/com/sqlcli/
│   ├── SqlCli.java                  # 入口，命令分发
│   ├── cli/                         # picocli 命令实现
│   ├── config/                      # 配置加载与管理
│   ├── connection/                  # 连接管理、连接池、输出渲染
│   ├── task/                        # SqlTaskModule：全项目唯一 SQL 执行入口（Stage + Backend）
│   ├── approval/                    # 写操作审批闸门
│   ├── runstate/                    # 本地运行库（SQLite）：执行历史、任务、审批、图谱审计
│   ├── secret/                      # 密码解析（env/keyring/encrypted）
│   ├── crypto/                      # SM4 加密工具
│   ├── sql/                         # SM4 SQL 改写器
│   ├── parser/                      # SQL 解析（类型检测、表/列提取）
│   ├── recovery/                    # UPDATE/DELETE 恢复 SQL 生成
│   ├── strategy/                    # 数据库方言策略（MySQL/Oracle/PostgreSQL/ClickHouse）
│   ├── graph/                       # Schema 知识图谱
│   │   ├── workspace/               # 工作区模型、存储、导入合并、搜索（含 index/ 索引、diagram/ 关系图）
│   │   ├── policy/                  # 结构规则审计
│   │   ├── review/                  # 候选变更评审
│   │   └── ui/                      # Web UI 服务端
│   └── yearning/                    # Yearning 集成
├── src/test/                        # 测试
├── web/                             # React 前端（Vite + TypeScript）
├── drivers/                         # 外部 JDBC 驱动
├── sh/                              # 分发脚本（构建、安装、启动）
├── skills/                          # Claude Code Skill 定义
├── docs/                            # 设计文档
└── dist/                            # 分发包输出
```

## 依赖

| 依赖 | 用途 |
|------|------|
| picocli | CLI 框架 |
| JSqlParser | SQL AST 解析与改写 |
| HikariCP | 连接池 |
| BouncyCastle | SM4 加密 |
| Jackson | JSON/YAML 序列化 |
| SnakeYAML | YAML 配置解析 |
| Lombok | 代码简化 |

## 许可

内部项目，未公开发布。
